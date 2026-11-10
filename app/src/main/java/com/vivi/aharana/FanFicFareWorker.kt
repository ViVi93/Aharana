package com.vivi.aharana

import android.app.NotificationChannel
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.vivi.aharana.data.local.AppDatabase
import com.vivi.aharana.data.local.BookDao
import com.vivi.aharana.data.local.BookEntity
import com.vivi.aharana.data.local.DownloadJobDao
import com.vivi.aharana.data.local.DownloadJobEntity
import com.vivi.aharana.data.local.toEntity
import com.vivi.aharana.model.BookItem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class FanFicFareWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "fanficfare_worker_channel"
        const val NOTIFICATION_ID = 1
        const val KEY_TYPE = "type"
        const val KEY_URL = "url"
        const val KEY_INPUT_PATH = "inputPath"
        const val KEY_BOOK_ID = "bookId"
        const val KEY_WORK_ID = "workId"
        const val TYPE_DOWNLOAD = "download"
        const val TYPE_UPDATE = "update"
        const val TYPE_FORCE_DOWNLOAD = "force_download"
        const val TYPE_METADATA = "metadata"
        const val UNIQUE_WORK_NAME = "fanficfare_unique_work"
        const val PROGRESS_STATUS = "status"
        const val PROGRESS_PHASE = "phase"
        const val PROGRESS_INDETERMINATE = "indeterminate"

        /** Job is enqueued but has not acquired the engine gate yet. */
        const val STATUS_WAITING = "waiting"

        /** WorkManager tags used to cancel queued/running engine jobs by type. */
        const val TAG_DOWNLOAD = "fanficfare_download"
        const val TAG_UPDATE = "fanficfare_update"
        const val TAG_FORCE_DOWNLOAD = "fanficfare_force_download"

        /** HTTP statuses worth re-running a whole story fetch for. */
        val RETRYABLE_HTTP_STATUS = setOf(429, 500, 502, 503, 504)

        /** Delay before each extra whole-story attempt after a retryable status. */
        val SERVER_RETRY_DELAYS_MS = longArrayOf(10_000L, 30_000L)

        /** Interval between "engine still busy" heartbeats while the gate is held. */
        const val HEARTBEAT_MS = 15_000L
    }

    init {
        createNotificationChannel()
    }

    private suspend fun setPhase(status: String, indeterminate: Boolean = true) {
        setProgress(
            Data.Builder()
                .putString(PROGRESS_STATUS, status)
                .putString(PROGRESS_PHASE, humanize(status))
                .putBoolean(PROGRESS_INDETERMINATE, indeterminate)
                .build()
        )
    }

    private suspend fun updateJobStatus(jobDao: DownloadJobDao, job: DownloadJobEntity, status: String) {
        jobDao.updateFromWorker(job.copy(status = status))
        logWorker("doWork", "db_status jobId=${job.id} status=$status")
    }

    private fun humanize(status: String): String = when (status) {
        "queued" -> "Queued"
        "waiting" -> "Waiting for other downloads"
        "preparing" -> "Preparing"
        "downloading" -> "Downloading"
        "retrying" -> "Server busy - retrying"
        "processing" -> "Processing"
        "copying" -> "Copying"
        "completed" -> "Complete"
        "failed" -> "Failed"
        "cancelled" -> "Cancelled"
        "fetching_metadata" -> "Fetching metadata"
        else -> status.replaceFirstChar { it.uppercase() }
    }

    private var diagnosticJobId: Long = 0
    @Volatile private var workerStage = "entry"

    private fun stopReasonLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason.toString() else "unavailable_before_api31"

    private suspend fun ensureWorkerActive(stage: String) {
        workerStage = stage
        currentCoroutineContext().ensureActive()
        if (isStopped) throw CancellationException("worker_stopped")
        if (diagnosticJobId > 0) {
            val current = AppDatabase.getInstance(applicationContext).downloadJobDao().getById(diagnosticJobId)
            if (current == null || current.workId != inputData.getString(KEY_WORK_ID) ||
                current.status in setOf("cancelled", "superseded")) {
                throw CancellationException("job_no_longer_active")
            }
        }
    }

    private fun logWorker(tag: String, message: String) {
        val text = DiagnosticFiles.sanitize("event=$tag job_id=$diagnosticJobId work_name=${inputData.getString(KEY_WORK_ID)} " +
            "request_id=$id run_attempt=$runAttemptCount operation=${inputData.getString(KEY_TYPE)} " +
            "stage=$workerStage is_stopped=$isStopped stop_reason=${stopReasonLabel()} $message")
            .replace("\n", " ").replace("\r", " ").take(2048)
        android.util.Log.d("FFF-Worker", text)
        try {
            DiagnosticLog.append(applicationContext, "FFF-Worker", text)
        } catch (_: Exception) {
            android.util.Log.e("FFF-Worker", "log_failed")
        }
    }

    /** Does not inspect files: cancellation can occur before storage exclusion is acquired. */
    private suspend fun markInterruptedJob(workId: String, reason: String) {
        if (workId.isBlank()) return
        val dao = AppDatabase.getInstance(applicationContext).downloadJobDao()
        val current = dao.findByWorkId(workId) ?: return
        val changed = dao.updateFromWorker(current.copy(status = "failed", error = reason,
            finishedAt = System.currentTimeMillis()))
        logWorker("interruption_persisted", "updated=$changed current_status=${current.status}")
    }

    private suspend fun upsertBook(
        bookDao: BookDao,
        candidate: BookEntity,
        filePath: String
    ): BookEntity {
        val existing = (if (candidate.id > 0) bookDao.findById(candidate.id) else null) ?: if (!candidate.url.isNullOrBlank()) {
            bookDao.findByUrl(candidate.url) ?: bookDao.findByFilePath(filePath)
        } else {
            bookDao.findByFilePath(filePath)
        }
        return if (existing != null) {
            val merged = candidate.copy(
                id = existing.id,
                addedAt = existing.addedAt
            )
            bookDao.update(merged)
            android.util.Log.d("FFF-Dup", "upsertBook updated id=${merged.id}")
            merged
        } else {
            val newId = bookDao.insert(candidate)
            android.util.Log.d("FFF-Dup", "upsertBook inserted id=$newId")
            candidate.copy(id = newId)
        }
    }

    /** Room serializes explicit Cancel with library+success commit; no stale worker book write. */
    private suspend fun completeBook(
        bookDao: BookDao,
        jobDao: DownloadJobDao,
        candidate: BookEntity,
        path: String,
        completed: DownloadJobEntity
    ): Boolean {
        val database = AppDatabase.getInstance(applicationContext)
        return database.withTransaction {
            ensureWorkerActive("commit_book")
            if (!QueueJobPolicy.canWorkerWrite(jobDao.getById(completed.id)?.status)) return@withTransaction false
            val saved = upsertBook(bookDao, candidate, path)
            ensureWorkerActive("commit_book_result")
            if (!jobDao.updateFromWorker(completed.copy(bookId = saved.id))) {
                // Throw so the book upsert rolls back too, including if a row was removed.
                throw CancellationException("job_no_longer_active")
            }
            logWorker("book_committed", "book_id=${saved.id}")
            true
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("FanFicFare")
            .setContentText("Running ${inputData.getString(KEY_TYPE) ?: "task"}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        val started = SystemClock.elapsedRealtime()
        val workName = inputData.getString(KEY_WORK_ID).orEmpty()
        logWorker("doWork", "ENTER") // Even foreground/storage wait failures need an entry.
        return try {
            diagnosticJobId = AppDatabase.getInstance(applicationContext).downloadJobDao()
                .findByWorkId(workName)?.id ?: 0
            workerStage = "foreground"
            setForeground(getForegroundInfo())
            ensureWorkerActive("before_storage")
            if (inputData.getString(KEY_TYPE) == TYPE_METADATA) {
                doWorkUnlocked()
            } else {
                workerStage = "storage_wait"
                val waitingAt = SystemClock.elapsedRealtime()
                logWorker("storage_wait", "wait_started")
                try {
                    LibraryStorageGate.run {
                        val acquiredAt = SystemClock.elapsedRealtime()
                        logWorker("storage_acquired", "wait_ms=${acquiredAt - waitingAt}")
                        try {
                            ensureWorkerActive("storage_recovery")
                            BookRepository.reconcileOrganisedPaths(applicationContext)
                            doWorkUnlocked()
                        } finally {
                            logWorker("storage_released", "held_ms=${SystemClock.elapsedRealtime() - acquiredAt}")
                        }
                    }
                } finally {
                    logWorker("storage_wait_finished", "elapsed_ms=${SystemClock.elapsedRealtime() - waitingAt}")
                }
            }
        } catch (e: CancellationException) {
            logWorker("cancellation", "error_class=${e.javaClass.simpleName}")
            withContext(NonCancellable) {
                try {
                    markInterruptedJob(workName, QueueJobPolicy.interruptionReason(workerStage, stopReasonLabel()))
                } catch (persistError: Exception) {
                    logWorker("interruption_persist_failed", "error_class=${persistError.javaClass.simpleName}")
                }
            }
            // WorkManager owns scheduler resumption. Never return failure or persist user
            // cancellation merely because its coroutine/constraints were stopped.
            throw e
        } catch (e: Exception) {
            logWorker("failure", "error_class=${e.javaClass.simpleName}")
            markInterruptedJob(workName, "Worker failed at $workerStage (${e.javaClass.simpleName}). Retry to resume.")
            setPhase("failed")
            Result.failure()
        } finally {
            logWorker("exit", "elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        }
    }

    private suspend fun doWorkUnlocked(): Result {
        ensureWorkerActive("initialise")
        val type = inputData.getString(KEY_TYPE) ?: error("Missing operation type")
        val url = inputData.getString(KEY_URL).orEmpty()
        val inputPath = inputData.getString(KEY_INPUT_PATH).orEmpty()
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        val workId = inputData.getString(KEY_WORK_ID).orEmpty()
        if (!com.chaquo.python.Python.isStarted()) {
            try {
                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(applicationContext))
                logWorker("python_started", "ready")
            } catch (e: Exception) {
                if (!com.chaquo.python.Python.isStarted()) throw e
                logWorker("python_start_raced", "already_started error_class=${e.javaClass.simpleName}")
            }
        }
        val database = AppDatabase.getInstance(applicationContext)
        val bookDao = database.bookDao()
        val jobDao = database.downloadJobDao()
        val existing = workId.takeIf { it.isNotBlank() }?.let { jobDao.findByWorkId(it) }
        if (existing == null && workId.isNotBlank()) return Result.failure()
        if (existing?.status == "success") return recoverExistingSuccess(type, existing, bookDao, jobDao)
        if (existing?.status in setOf("cancelled", "superseded")) return Result.failure()
        val job = if (existing != null) {
            existing.copy(status = STATUS_WAITING, error = null, finishedAt = null).also {
                if (!jobDao.updateFromWorker(it)) throw CancellationException("job_no_longer_active")
            }
        } else {
            val pending = DownloadJobEntity(bookId = bookId, type = type, status = STATUS_WAITING,
                inputUrl = url.ifBlank { null }, inputPath = inputPath.ifBlank { null },
                workId = workId.ifBlank { null }, createdAt = System.currentTimeMillis())
            pending.copy(id = jobDao.insert(pending))
        }
        diagnosticJobId = job.id
        ensureWorkerActive("before_engine_wait")
        setPhase("waiting")
        workerStage = "engine_wait"
        val waitingAt = SystemClock.elapsedRealtime()
        logWorker("engine_wait", "wait_started")
        return try {
            FanFicFareGate.run(visible = type != TYPE_METADATA) {
                val acquiredAt = SystemClock.elapsedRealtime()
                logWorker("engine_acquired", "wait_ms=${acquiredAt - waitingAt}")
                try {
                    ensureWorkerActive("engine_acquired")
                    val running = job.copy(status = "running")
                    if (!jobDao.updateFromWorker(running)) throw CancellationException("job_no_longer_active")
                    setPhase("preparing")
                    withEngineHeartbeat(type) {
                        when (type) {
                            TYPE_DOWNLOAD -> handleDownload(url, bookDao, jobDao, running)
                            TYPE_UPDATE -> handleUpdate(bookId, inputPath, bookDao, jobDao, running)
                            TYPE_FORCE_DOWNLOAD -> handleForceDownload(bookId, inputPath, bookDao, jobDao, running)
                            TYPE_METADATA -> handleMetadata(url, jobDao, running)
                            else -> error("Unknown operation type")
                        }
                    }
                } finally {
                    logWorker("engine_released", "held_ms=${SystemClock.elapsedRealtime() - acquiredAt}")
                }
            }
        } finally {
            logWorker("engine_wait_finished", "elapsed_ms=${SystemClock.elapsedRealtime() - waitingAt}")
        }
    }

    private suspend fun recoverExistingSuccess(
        type: String,
        existingJob: DownloadJobEntity,
        bookDao: BookDao,
        jobDao: DownloadJobDao
    ): Result {
        ensureWorkerActive("recover_success")
        // A completed worker must not replay library writes or overwrite its warning payload.
        if (jobDao.getById(existingJob.id)?.status != "success") return Result.failure()
        if (type == TYPE_METADATA) return Result.success()
        val path = existingJob.outputPath?.takeIf { it.isNotBlank() } ?: return Result.failure()
        if (existingJob.bookId > 0 && bookDao.findById(existingJob.bookId)?.filePath != path) return Result.failure()
        val file = File(path)
        return if (file.isFile && file.length() > 0) Result.success() else Result.failure()
    }

    /**
     * Runs [block] while logging an "engine still busy" heartbeat every
     * [HEARTBEAT_MS] and refreshing the foreground notification with the elapsed
     * time.
     *
     * The engine gate admits one call at a time, so a call that never returns
     * freezes every job queued behind it with nothing in the log to show for it
     * (the app has no generic timeouts by design). This makes the wait visible -
     * Diagnostics gets `gate_heartbeat type=download elapsed=45s` and the
     * notification reads "Running download · 45s" - so a stall cannot be mistaken
     * for an idle queue. It never kills the work.
     */
    private suspend fun <T> withEngineHeartbeat(type: String, block: suspend () -> T): T = coroutineScope {
        val startedAt = SystemClock.elapsedRealtime()
        val normalCleanup = java.util.concurrent.atomic.AtomicBoolean(false)
        val beat = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    delay(HEARTBEAT_MS)
                    val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
                    logWorker("heartbeat", "gate_heartbeat type=$type elapsed=${seconds}s")
                    updateForegroundNotification("Running $type · ${seconds}s")
                }
            } catch (e: CancellationException) {
                // Runs on cancellation even when synchronous Python has not returned yet.
                // Normal beat.cancel() after a completed bridge is not a scheduler stop.
                if (QueueJobPolicy.shouldLogHeartbeatStop(normalCleanup.get(), isStopped)) {
                    logWorker("heartbeat_cancelled", "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} error_class=${e.javaClass.simpleName}")
                }
                throw e
            }
        }
        try {
            block()
        } finally {
            normalCleanup.set(true)
            beat.cancel()
        }
    }

    /** Re-posts the foreground notification with [text]; never throws. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun updateForegroundNotification(text: String) {
        try {
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle("FanFicFare")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            androidx.core.app.NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            android.util.Log.w("FFF-Heartbeat", "notification update failed: ${e.message}")
        }
    }

    /**
     * Runs a FanFicFare bridge call, re-running the whole operation when the site
     * answered with a retryable status (429/5xx).
     *
     * Safe to re-run: the engine only produces output once the writer completes, and
     * the bridge writes the EPUB through a temp file that is renamed on success.
     * Retrying here means a burst that tripped a site's rate limiter recovers inside
     * the job instead of leaving a failed row for the user to retry by hand.
     *
     * Runs while holding the engine gate, so the waits also keep other jobs out of
     * the site's face.
     */
    private suspend fun callBridgeWithRetry(
        label: String,
        bridge: PythonBridge,
        call: () -> String
    ): String {
        var attempt = 0
        while (true) {
            ensureWorkerActive("bridge_call")
            val started = SystemClock.elapsedRealtime()
            logWorker("bridge_start", "bridge_attempt=${attempt + 1} operation_label=$label")
            bridge.setDownloadDiagnosticContext(JSONObject()
                .put("job_id", diagnosticJobId)
                .put("work_name", inputData.getString(KEY_WORK_ID).orEmpty())
                .put("request_id", id.toString())
                .put("run_attempt", runAttemptCount)
                .put("operation", inputData.getString(KEY_TYPE).orEmpty())
                .put("bridge_attempt", attempt + 1).toString())
            val raw = call()
            logWorker("bridge_return", "bridge_attempt=${attempt + 1} response_length=${raw.length} elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            ensureWorkerActive("bridge_return")
            val status = retryableHttpStatus(raw)
            if (status == null || attempt >= SERVER_RETRY_DELAYS_MS.size) return raw
            val delayMs = SERVER_RETRY_DELAYS_MS[attempt]
            attempt++
            logWorker(label, "server_busy status=$status retry=$attempt delayMs=$delayMs")
            setPhase("retrying")
            delay(delayMs)
            setPhase("downloading")
        }
    }

    /** Returns the HTTP status when the bridge reported a retryable failure, else null. */
    private fun retryableHttpStatus(raw: String): Int? = try {
        val json = JSONObject(raw)
        if (json.optBoolean("ok")) {
            null
        } else {
            val status = json.optInt("http_status", -1)
            if (RETRYABLE_HTTP_STATUS.contains(status)) status else null
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun handleDownload(
        url: String,
        bookDao: BookDao,
        jobDao: DownloadJobDao,
        job: DownloadJobEntity
    ): Result {
        if (url.isBlank()) {
            jobDao.updateFromWorker(
                job.copy(
                    status = "failed",
                    error = "Missing URL",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        }
        ensureWorkerActive("operation_boundary")
        setPhase("downloading")

        val bridge = PythonBridge(applicationContext)
        val outputDir = applicationContext.filesDir.absolutePath
        logWorker("handleDownload", "bridge_call")
        val raw = callBridgeWithRetry("handleDownload", bridge) { bridge.fanficfareDownload(url, outputDir) }
        logWorker("handleDownload", "bridge_raw_len=${raw.length}")
        ensureWorkerActive("operation_boundary")
        val result = try {
            JSONObject(raw)
        } catch (e: Exception) {
            logWorker("handleDownload", "json_parse_failed error_class=${e.javaClass.simpleName} response_length=${raw.length}")
            jobDao.updateFromWorker(
                job.copy(
                    status = "failed",
                    error = "Invalid response from bridge: ${e.message ?: "unknown"}",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        }
        logWorker("handleDownload", "result_json_ok=${result.optBoolean("ok")} title=${result.optString("title", "")}")
        if (!result.optBoolean("ok")) {
            val errorMsg = result.optString("error", "unknown")
            logWorker("handleDownload", "bridge_failure http_status=${result.optInt("http_status", -1)} error_length=${errorMsg.length}")
            jobDao.updateFromWorker(
                job.copy(
                    status = "failed",
                    error = errorMsg,
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        }

        val title = result.optString("title", "story")
        val internalPath = result.optString("path", "")
        logWorker("handleDownload", "result_path=$internalPath")
        if (internalPath.isBlank()) {
            jobDao.updateFromWorker(
                job.copy(
                    status = "failed",
                    error = "missing output path",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        }

        val source = File(internalPath)
        logWorker("handleDownload", "source_path=$internalPath exists=${source.exists()} isFile=${source.isFile} size=${source.length()}")
        if (!source.exists() || !source.isFile) {
            jobDao.updateFromWorker(
                job.copy(
                    status = "failed",
                    error = "generated file missing",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        }
        ensureWorkerActive("operation_boundary")
        setPhase("processing")

        val savedOutputDir = SettingsActivity.getOutputDir(applicationContext)
        logWorker("handleDownload", "output_dir=$savedOutputDir")
        val finalPath = try {
            val copied = StorageBridge.copyDownloadedEpub(applicationContext, source, savedOutputDir)
            logWorker("handleDownload", "copied_to=$copied")
            copied
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logWorker("handleDownload", "copy_exception=${e.javaClass.simpleName}: ${e.message}")
            jobDao.updateFromWorker(
                job.copy(
                    status = "failed",
                    error = e.message,
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        } finally {
            source.delete()
        }
        ensureWorkerActive("operation_boundary")
        setPhase("copying")

        val author = result.optString("author", "")
        val bookUrl = result.optString("url", url)
        val chapters = result.optInt("chapters", 0)
        val cover = result.optString("cover", "")
        val size = result.optLong("size", 0L)
        val modified = result.optLong("modified", System.currentTimeMillis())
        val current = jobDao.getById(job.id)
        if (current?.status == "cancelled") {
            setPhase("cancelled")
            return Result.failure()
        }
        val entity = BookItem(
            title = title,
            author = author,
            uriString = finalPath,
            lastModified = modified,
            sizeBytes = size,
            coverUriString = cover,
            url = bookUrl,
            chapters = chapters,
            sourceUriString = finalPath
        ).toEntity()

        if (!completeBook(bookDao, jobDao, entity, finalPath,
            job.copy(status = "success", outputPath = finalPath,
                resultJson = DownloadOutcome.compact(result),
                error = null, finishedAt = System.currentTimeMillis()))) return Result.failure()
        // Failed and explicitly cancelled attempts stay visible until Retry/Remove.
        logWorker("handleDownload", DownloadWarnings.parse(result).completionMessage("EPUB saved"))
        setPhase("completed", indeterminate = false)
        return Result.success()
    }

    private suspend fun handleUpdate(
        bookId: Long,
        inputPath: String,
        bookDao: BookDao,
        jobDao: DownloadJobDao,
        job: DownloadJobEntity
    ): Result {
        // Room recovery remaps queued jobs; immutable WorkData may still name the old file.
        val recoveredInputPath = jobDao.getById(job.id)?.inputPath?.takeIf { it.isNotBlank() } ?: inputPath
        val existing = if (bookId > 0) {
            bookDao.findById(bookId)
                ?: error("Book is no longer in the library. Reload the library before applying.")
        } else bookDao.findByFilePath(recoveredInputPath)
        val path = LibraryEpubStorage.existingPath(existing?.filePath, recoveredInputPath)
        if (path.isBlank()) {
            jobDao.updateFromWorker(
                job.copy(
                    status = "failed",
                    error = "missing input path",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        }

        val bridge = PythonBridge(applicationContext)
        val workDir = File(applicationContext.cacheDir, "epub-update-${java.util.UUID.randomUUID()}")
        check(workDir.mkdirs()) { "Cannot create EPUB staging directory" }
        try {
            val outputDir = workDir.absolutePath
            ensureWorkerActive("operation_boundary")
            setPhase("downloading")
            val raw = StorageBridge.withLocalEpub(applicationContext, path) { localFile ->
                callBridgeWithRetry("handleUpdate", bridge) { bridge.updateEpubFromPath(localFile.absolutePath, outputDir) }
            } ?: run {
                jobDao.updateFromWorker(job.copy(status = "failed",
                    error = "Existing EPUB is missing or unreadable. Use Force Download to restore it from the saved story URL.",
                    finishedAt = System.currentTimeMillis()))
                setPhase("failed")
                return Result.failure()
            }
            ensureWorkerActive("operation_boundary")
            val result = JSONObject(raw)
            if (!result.optBoolean("ok")) {
                jobDao.updateFromWorker(
                    job.copy(
                        status = "failed",
                        error = result.optString("error", "unknown"),
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("failed")
                return Result.failure()
            }

            val title = result.optString("title", existing?.title ?: "story")
            val internalPath = result.optString("path", "")
            if (result.optBoolean("skipped")) {
                jobDao.updateFromWorker(job.copy(status = "success", bookId = existing?.id ?: job.bookId,
                    outputPath = path, resultJson = DownloadOutcome.compact(result),
                    finishedAt = System.currentTimeMillis()))
                setPhase("completed", indeterminate = false)
                return Result.success()
            }
            check(internalPath.isNotBlank() && File(internalPath).isFile) {
                "Generated EPUB is missing; the original was kept"
            }
            ensureWorkerActive("operation_boundary")
            setPhase("processing")
            val finalPath = try {
                StorageBridge.replaceExistingEpub(applicationContext, File(internalPath), path)
            } finally {
                if (File(internalPath).canonicalPath != File(path).canonicalPath) File(internalPath).delete()
            }
            ensureWorkerActive("operation_boundary")
            setPhase("copying")
            val updated = if (existing != null) {
                existing.copy(
                    id = existing.id,
                    title = title,
                    filePath = finalPath,
                    sourcePath = LibraryEpubStorage.sourceAfterReplacement(existing.filePath, existing.sourcePath, finalPath),
                    lastModified = result.optLong("modified", System.currentTimeMillis()),
                    sizeBytes = result.optLong("size", 0L),
                    coverData = result.optString("cover", "").ifBlank { existing.coverData ?: "" },
                    url = result.optString("url", existing.url ?: ""),
                    chapters = result.optInt("chapters", existing.chapters)
                )
            } else {
                BookItem(
                    title = title,
                    author = "",
                    uriString = finalPath,
                    lastModified = result.optLong("modified", System.currentTimeMillis()),
                    sizeBytes = result.optLong("size", 0L),
                    coverUriString = result.optString("cover", ""),
                    url = result.optString("url", ""),
                    chapters = result.optInt("chapters", 0),
                    sourceUriString = finalPath
                ).toEntity()
            }
            if (!completeBook(bookDao, jobDao, updated, finalPath,
                job.copy(status = "success", outputPath = finalPath,
                    resultJson = DownloadOutcome.compact(result),
                    error = null, finishedAt = System.currentTimeMillis()))) return Result.failure()
            logWorker("completion", DownloadWarnings.parse(result).completionMessage("EPUB saved"))
            setPhase("completed", indeterminate = false)
            return Result.success()
        } finally { workDir.deleteRecursively() }
    }

    private suspend fun handleForceDownload(
        bookId: Long,
        inputPath: String,
        bookDao: BookDao,
        jobDao: DownloadJobDao,
        job: DownloadJobEntity
    ): Result {
        // Room recovery remaps queued jobs; immutable WorkData may still name the old file.
        val recoveredInputPath = jobDao.getById(job.id)?.inputPath?.takeIf { it.isNotBlank() } ?: inputPath
        val existing = if (bookId > 0) {
            bookDao.findById(bookId)
                ?: error("Book is no longer in the library. Reload the library before applying.")
        } else bookDao.findByFilePath(recoveredInputPath)
        val path = LibraryEpubStorage.existingPath(existing?.filePath, recoveredInputPath)
        val fallbackUrl = existing?.url
        val replaceAllowed = path.startsWith("content://") || File(path).isFile
        if (path.isBlank() && fallbackUrl.isNullOrBlank()) {
            jobDao.updateFromWorker(
                job.copy(
                    status = "failed",
                    error = "missing input path",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        }

        val bridge = PythonBridge(applicationContext)
        val workDir = File(applicationContext.cacheDir, "epub-update-${java.util.UUID.randomUUID()}")
        check(workDir.mkdirs()) { "Cannot create EPUB staging directory" }
        try {
            val outputDir = workDir.absolutePath
            ensureWorkerActive("operation_boundary")
            setPhase("downloading")
            val raw = StorageBridge.withLocalEpub(applicationContext, path) { localFile ->
                callBridgeWithRetry("handleForceDownload", bridge) {
                    bridge.forceDownloadFromEpub(localFile.absolutePath, outputDir, fallbackUrl)
                }
            } ?: run {
                logWorker("force_recovery", "local_epub_unavailable saved_url=${!fallbackUrl.isNullOrBlank()}")
                callBridgeWithRetry("handleForceDownload", bridge) { bridge.forceDownloadFromEpub("", outputDir, fallbackUrl) }
            }
            ensureWorkerActive("operation_boundary")
            val result = JSONObject(raw)
            if (!result.optBoolean("ok")) {
                jobDao.updateFromWorker(
                    job.copy(
                        status = "failed",
                        error = result.optString("error", "unknown"),
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("failed")
                return Result.failure()
            }

            val title = result.optString("title", existing?.title ?: "story")
            val internalPath = result.optString("path", "")
            if (result.optBoolean("skipped")) {
                jobDao.updateFromWorker(job.copy(status = "success", bookId = existing?.id ?: job.bookId,
                    outputPath = path, resultJson = DownloadOutcome.compact(result),
                    finishedAt = System.currentTimeMillis()))
                setPhase("completed", indeterminate = false)
                return Result.success()
            }
            check(internalPath.isNotBlank() && File(internalPath).isFile) {
                "Generated EPUB is missing; the original was kept"
            }
            ensureWorkerActive("operation_boundary")
            setPhase("processing")
            val finalPath = try {
                LibraryEpubStorage.saveForced(File(internalPath), path,
                    replaceAllowed = replaceAllowed,
                    replaceExisting = { source, target -> StorageBridge.replaceExistingEpub(applicationContext, source, target) },
                    recoveryCopy = { source -> StorageBridge.saveUpdatedDocumentCopy(applicationContext, source) })
            } finally {
                if (File(internalPath).canonicalPath != File(path).canonicalPath) File(internalPath).delete()
            }
            ensureWorkerActive("operation_boundary")
            setPhase("copying")
            val updatedEntity = if (existing != null) {
                existing.copy(
                    id = existing.id,
                    title = title,
                    filePath = finalPath,
                    sourcePath = LibraryEpubStorage.sourceAfterReplacement(existing.filePath, existing.sourcePath, finalPath),
                    lastModified = result.optLong("modified", System.currentTimeMillis()),
                    sizeBytes = result.optLong("size", 0L),
                    coverData = result.optString("cover", "").ifBlank { existing.coverData ?: "" },
                    url = result.optString("url", existing.url ?: ""),
                    chapters = result.optInt("chapters", existing.chapters)
                )
            } else {
                BookItem(
                    title = title,
                    author = "",
                    uriString = finalPath,
                    lastModified = result.optLong("modified", System.currentTimeMillis()),
                    sizeBytes = result.optLong("size", 0L),
                    coverUriString = result.optString("cover", ""),
                    url = result.optString("url", ""),
                    chapters = result.optInt("chapters", 0),
                    sourceUriString = finalPath
                ).toEntity()
            }
            if (!completeBook(bookDao, jobDao, updatedEntity, finalPath,
                job.copy(status = "success", outputPath = finalPath,
                    resultJson = DownloadOutcome.compact(result),
                    error = null, finishedAt = System.currentTimeMillis()))) return Result.failure()
            logWorker("completion", DownloadWarnings.parse(result).completionMessage("EPUB saved"))
            setPhase("completed", indeterminate = false)
            return Result.success()
        } finally { workDir.deleteRecursively() }
    }

    private suspend fun handleMetadata(
        url: String,
        jobDao: DownloadJobDao,
        job: DownloadJobEntity
    ): Result {
        if (url.isBlank()) {
            jobDao.updateFromWorker(
                job.copy(
                    status = "failed",
                    error = "Missing URL",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        }
        val bridge = PythonBridge(applicationContext)
        setPhase("fetching_metadata")
        val raw = callBridgeWithRetry("handleMetadata", bridge) { bridge.fanficfareMetadata(url) }
        val result = JSONObject(raw)
        val status = if (result.optBoolean("ok")) "success" else "failed"
        val errorMessage = result.optString("error", "")
        if (errorMessage.isNotBlank()) {
            logWorker("metadata_failure", "http_status=${result.optInt("http_status", -1)} error_length=${errorMessage.length}")
        }
        jobDao.updateFromWorker(
            job.copy(
                status = status,
                error = errorMessage.ifBlank { null },
                resultJson = if (result.optBoolean("ok")) result.toString() else null,
                finishedAt = System.currentTimeMillis()
            )
        )
        setPhase(if (result.optBoolean("ok")) "completed" else "failed", indeterminate = !result.optBoolean("ok"))
        return if (result.optBoolean("ok")) Result.success() else Result.failure()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "FanFicFare Worker", android.app.NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun chooseCover(existing: String?, returned: String?): String {
        val newCover = returned?.trim().orEmpty()
        return if (newCover.isNotEmpty()) newCover else existing?.trim().orEmpty()
    }
}
