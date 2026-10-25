package com.vivi.aharana

import android.app.NotificationChannel
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
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
        jobDao.update(job.copy(status = status))
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

    private fun logWorker(tag: String, message: String) {
        val text = "[$tag] $message"
        android.util.Log.d("FFF-Worker", text)
        try {
            DiagnosticLog.append(applicationContext, "FFF-Worker", text)
        } catch (e: Exception) {
            android.util.Log.e("FFF-Worker", "log_failed", e)
        }
    }

    /**
     * Terminal state for a job whose worker was stopped before it did any work.
     *
     * Returning Result.failure() on its own left the Room row at "queued"/"waiting"
     * forever: WorkManager considers the work finished, so nothing will ever run it
     * again, while the row still claims to be in flight - which is how downloads
     * piled up in the queue with no Retry button and nothing to identify them.
     * Cancelled rows are left alone; anything else becomes failed (retryable), or
     * success when a usable EPUB is already on disk.
     */
    private suspend fun markInterruptedJob(workId: String) {
        if (workId.isBlank()) return
        try {
            val dao = AppDatabase.getInstance(applicationContext).downloadJobDao()
            val job = dao.findByWorkId(workId) ?: return
            if (job.status == "success" || job.status == "cancelled") return
            val output = job.outputPath?.ifBlank { null }
            val file = output?.let { File(it) }
            if (file != null && file.exists() && file.isFile && file.length() > 0) {
                dao.update(job.copy(status = "success", finishedAt = System.currentTimeMillis()))
                logWorker("doWork", "stopped_marked_success id=${job.id}")
            } else {
                dao.update(
                    job.copy(
                        status = "failed",
                        error = "interrupted_worker_stopped",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                logWorker("doWork", "stopped_marked_failed id=${job.id}")
            }
        } catch (e: Exception) {
            logWorker("doWork", "stopped_mark_failed=${e.message}")
        }
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
        // Foreground promotion must precede a possibly long wait for an organiser transaction.
        setForeground(getForegroundInfo())
        if (inputData.getString(KEY_TYPE) == TYPE_METADATA) return doWorkUnlocked()
        return try {
            LibraryStorageGate.run {
                BookRepository.reconcileOrganisedPaths(applicationContext)
                doWorkUnlocked()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                // Cancellation can happen BEFORE acquiring storage exclusion: update only the
                // job row here, never inspect an output file while the organiser may move it.
                val dao = AppDatabase.getInstance(applicationContext).downloadJobDao()
                dao.findByWorkId(inputData.getString(KEY_WORK_ID).orEmpty())?.let { job ->
                    if (job.status != "success" && job.status != "cancelled") {
                        dao.update(job.copy(status = "cancelled", finishedAt = System.currentTimeMillis()))
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            logWorker("doWork", "storage_recovery_failed=${e.message}")
            val dao = AppDatabase.getInstance(applicationContext).downloadJobDao()
            dao.findByWorkId(inputData.getString(KEY_WORK_ID).orEmpty())?.let { job ->
                if (job.status != "cancelled") dao.update(job.copy(status = "failed", error = e.message,
                    finishedAt = System.currentTimeMillis()))
            }
            setPhase("failed")
            Result.failure()
        }
    }

    private suspend fun doWorkUnlocked(): Result {
        logWorker("doWork", "ENTER url=${inputData.getString(KEY_URL)} type=${inputData.getString(KEY_TYPE)} workId=${inputData.getString(KEY_WORK_ID)}")
        val type = inputData.getString(KEY_TYPE) ?: return Result.failure().also { logWorker("doWork", "no_type") }
        val url = inputData.getString(KEY_URL) ?: ""
        val inputPath = inputData.getString(KEY_INPUT_PATH) ?: ""
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        val workId = inputData.getString(KEY_WORK_ID) ?: ""
        logWorker("doWork", "start type=$type url=$url workId=$workId")

        if (isStopped) {
            logWorker("doWork", "stopped_early")
            markInterruptedJob(workId)
            return Result.failure()
        }

        if (isStopped) {
            logWorker("doWork", "stopped_early")
            markInterruptedJob(workId)
            return Result.failure()
        }

        try {
            if (!com.chaquo.python.Python.isStarted()) {
                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(applicationContext))
                logWorker("doWork", "python_started")
            }
        } catch (e: Exception) {
            logWorker("doWork", "python_start_failed=${e.message}")
        }

        val database = AppDatabase.getInstance(applicationContext)
        val bookDao = database.bookDao()
        val jobDao = database.downloadJobDao()

        val existingJob = if (workId.isNotBlank()) jobDao.findByWorkId(workId) else null
        val recovery = when {
            existingJob == null -> null.also { logWorker("doWork", "no_existing_job") }
            existingJob.status == "success" -> recoverExistingSuccess(type, existingJob, bookDao, jobDao).also { logWorker("doWork", "recovered_success") }
            existingJob.status == "cancelled" -> Result.failure().also { logWorker("doWork", "existing_cancelled") }
            else -> null.also { logWorker("doWork", "existing_status=${existingJob.status}") }
        }
        if (recovery != null) return recovery

        // CRITICAL: Update the existing job (found by workId) rather than inserting
        // a new one.  The original enqueueDownload/enqueueMetadata/etc. already
        // created a job row with status="queued".  If we insert a NEW row here,
        // the original row stays stuck at "queued" forever and shows up in the
        // Download Queue even after the download completes successfully.
        var job: DownloadJobEntity
        var jobId: Long
        if (existingJob != null) {
            // Do NOT flip to "running" here: the engine gate below may keep this job
            // waiting behind another download, and the queue UI must say so.
            job = existingJob.copy(status = STATUS_WAITING)
            jobDao.update(job)
            jobId = existingJob.id
            logWorker("doWork", "awaiting_gate id=$jobId")
        } else {
            job = DownloadJobEntity(
                bookId = bookId,
                type = type,
                status = STATUS_WAITING,
                inputUrl = url.ifBlank { null },
                inputPath = inputPath.ifBlank { null },
                createdAt = System.currentTimeMillis()
            )
            jobId = jobDao.insert(job)
            logWorker("doWork", "job_inserted id=$jobId")
        }

        return try {
            if (isStopped) {
                jobDao.update(
                    job.copy(
                        id = jobId,
                        status = "cancelled",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                logWorker("doWork", "stopped_before_phase")
                return Result.failure()
            }

            setPhase("waiting")
            logWorker("doWork", "phase=waiting type=$type")

            // Only one FanFicFare engine call runs at a time. Sharing several URLs
            // used to run them in parallel, which tripped the host's rate limiter
            // (503 for every job in the burst). Metadata lookups are background
            // work and must not queue in front of anything the user asked for.
            FanFicFareGate.run(visible = type != TYPE_METADATA) {
                if (isStopped) {
                    jobDao.update(
                        job.copy(
                            id = jobId,
                            status = "cancelled",
                            finishedAt = System.currentTimeMillis()
                        )
                    )
                    logWorker("doWork", "stopped_while_waiting")
                    return@run Result.failure()
                }

                job = job.copy(status = "running")
                jobDao.update(job)
                logWorker("doWork", "gate_acquired id=$jobId")

                setPhase("preparing")
                logWorker("doWork", "phase=preparing")

                // Heartbeat: the gate is one-call-at-a-time, so a call that never
                // returns stalls the whole queue. This does not kill anything (the
                // app deliberately has no generic timeouts) - it makes the stall
                // visible in Diagnostics and in the foreground notification instead
                // of looking like an idle queue.
                withEngineHeartbeat(type) {
                    when (type) {
                        TYPE_DOWNLOAD -> {
                            logWorker("doWork", "calling_handleDownload")
                            handleDownload(url, bookDao, jobDao, job.copy(id = jobId)).also { logWorker("doWork", "handleDownload_result=$it") }
                        }
                        TYPE_UPDATE -> {
                            logWorker("doWork", "calling_handleUpdate")
                            handleUpdate(bookId, inputPath, bookDao, jobDao, job.copy(id = jobId)).also { logWorker("doWork", "handleUpdate_result=$it") }
                        }
                        TYPE_FORCE_DOWNLOAD -> {
                            logWorker("doWork", "calling_handleForceDownload")
                            handleForceDownload(bookId, inputPath, bookDao, jobDao, job.copy(id = jobId)).also { logWorker("doWork", "handleForceDownload_result=$it") }
                        }
                        TYPE_METADATA -> {
                            logWorker("doWork", "calling_handleMetadata")
                            handleMetadata(url, jobDao, job.copy(id = jobId)).also { logWorker("doWork", "handleMetadata_result=$it") }
                        }
                        else -> {
                            jobDao.update(
                                job.copy(
                                    id = jobId,
                                    status = "failed",
                                    error = "Unknown type: $type",
                                    finishedAt = System.currentTimeMillis()
                                )
                            )
                            logWorker("doWork", "unknown_type")
                            setPhase("failed")
                            Result.failure()
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled while waiting for, or using, the engine. Record it and let
            // the cancellation propagate instead of reporting a failure.
            jobDao.update(
                job.copy(
                    id = jobId,
                    status = "cancelled",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("cancelled")
            logWorker("doWork", "cancelled")
            throw e
        } catch (e: Exception) {
            logWorker("doWork", "exception=${e.javaClass.simpleName}: ${e.message ?: "null"}")
            jobDao.update(
                job.copy(
                    id = jobId,
                    status = "failed",
                    error = e.message,
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            Result.failure()
        }.also { result ->
            logWorker("doWork", "final_result=$result")
        }
    }

    private suspend fun recoverExistingSuccess(
        type: String,
        existingJob: DownloadJobEntity,
        bookDao: BookDao,
        jobDao: DownloadJobDao
    ): Result {
        if (isStopped) {
            return Result.failure()
        }
        when (type) {
            TYPE_METADATA -> return Result.success()
        }
        val outputPath = existingJob.outputPath?.ifBlank { null } ?: return Result.failure()
        // A completed job may outlive its book; never re-register a replacement at its old path.
        if (existingJob.bookId > 0) {
            val expected = bookDao.findById(existingJob.bookId)
            if (expected == null || expected.filePath != outputPath) return Result.failure()
        }
        val file = File(outputPath)
        val valid = file.exists() && file.isFile && file.length() > 0
        if (!valid) return Result.failure()
        if (isStopped) {
            return Result.failure()
        }
        return when (type) {
            TYPE_DOWNLOAD, TYPE_FORCE_DOWNLOAD -> {
                val existing = existingJob.bookId.takeIf { it > 0 }?.let { bookDao.findById(it) }
                val current = jobDao.getById(existingJob.id)
                if (current?.status == "cancelled") return Result.failure()
                if (existing == null) {
                    val book = BookItem(
                        title = File(outputPath).nameWithoutExtension,
                        author = "",
                        uriString = outputPath,
                        lastModified = System.currentTimeMillis(),
                        sizeBytes = file.length(),
                        coverUriString = null,
                        url = existingJob.inputUrl ?: "",
                        chapters = 0,
                        sourceUriString = outputPath
                    ).toEntity()
                    val before = bookDao.findByFilePath(outputPath)
                    android.util.Log.d("FFF-Dup", "recoverDownload title=${book.title} path=${outputPath} before=${before?.id}")
                    val saved = upsertBook(bookDao, book, outputPath)
                    android.util.Log.d("FFF-Dup", "recoverDownload inserted id=${saved.id}")
                    jobDao.update(
                        existingJob.copy(
                            bookId = saved.id,
                            status = "success",
                            finishedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    jobDao.update(
                        existingJob.copy(
                            status = "success",
                            finishedAt = System.currentTimeMillis()
                        )
                    )
                }
                setPhase("completed", indeterminate = false)
                Result.success()
            }
            TYPE_UPDATE -> {
                val existing = existingJob.bookId.takeIf { it > 0 }?.let { bookDao.findById(it) }
                val current = jobDao.getById(existingJob.id)
                if (current?.status == "cancelled") return Result.failure()
                if (existing != null) {
                    val before = bookDao.findByFilePath(outputPath)
                    android.util.Log.d("FFF-Dup", "recoverUpdate title=${existing.title} path=${outputPath} existingId=${existing.id} before=${before?.id}")
                    val saved = upsertBook(bookDao, existing.copy(filePath = outputPath, lastModified = System.currentTimeMillis(), sizeBytes = file.length()), outputPath)
                    android.util.Log.d("FFF-Dup", "recoverUpdate updated id=${saved.id}")
                }
                jobDao.update(
                    existingJob.copy(
                        status = "success",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("completed", indeterminate = false)
                Result.success()
            }
            else -> Result.failure()
        }
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
        val startedAt = System.currentTimeMillis()
        val beat = launch {
            while (true) {
                delay(HEARTBEAT_MS)
                val seconds = (System.currentTimeMillis() - startedAt) / 1000
                logWorker("heartbeat", "gate_heartbeat type=$type elapsed=${seconds}s")
                updateForegroundNotification("Running $type · ${seconds}s")
            }
        }
        try {
            block()
        } finally {
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
        call: () -> String
    ): String {
        var attempt = 0
        while (true) {
            val raw = call()
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
            jobDao.update(
                job.copy(
                    status = "failed",
                    error = "Missing URL",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        }
        if (isStopped) {
            jobDao.update(
                job.copy(
                    status = "cancelled",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("cancelled")
            return Result.failure()
        }
        setPhase("downloading")

        val bridge = PythonBridge(applicationContext)
        val outputDir = applicationContext.filesDir.absolutePath
        logWorker("handleDownload", "bridge_call url=$url outputDir=$outputDir")
        val raw = callBridgeWithRetry("handleDownload") { bridge.fanficfareDownload(url, outputDir) }
        logWorker("handleDownload", "bridge_raw_len=${raw.length}")
        if (isStopped) {
            jobDao.update(
                job.copy(
                    status = "cancelled",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("cancelled")
            return Result.failure()
        }
        val result = try {
            JSONObject(raw)
        } catch (e: Exception) {
            logWorker("handleDownload", "json_parse_failed type=${e.javaClass.simpleName} msg=${e.message ?: ""} raw_prefix=${raw.take(200)}")
            jobDao.update(
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
            logWorker("handleDownload", "result_error=$errorMsg raw_prefix=$raw")
            jobDao.update(
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
            jobDao.update(
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
            jobDao.update(
                job.copy(
                    status = "failed",
                    error = "generated file missing",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("failed")
            return Result.failure()
        }
        if (isStopped) {
            jobDao.update(
                job.copy(
                    status = "cancelled",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("cancelled")
            return Result.failure()
        }
        setPhase("processing")

        val savedOutputDir = SettingsActivity.getOutputDir(applicationContext)
        logWorker("handleDownload", "output_dir=$savedOutputDir")
        val finalPath = try {
            val copied = StorageBridge.copyDownloadedEpub(applicationContext, source, savedOutputDir)
            logWorker("handleDownload", "copied_to=$copied")
            copied
        } catch (e: Exception) {
            logWorker("handleDownload", "copy_exception=${e.javaClass.simpleName}: ${e.message}")
            jobDao.update(
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
        if (isStopped) {
            jobDao.update(
                job.copy(
                    status = "cancelled",
                    finishedAt = System.currentTimeMillis()
                )
            )
            setPhase("cancelled")
            return Result.failure()
        }
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

        val before = bookDao.findByFilePath(finalPath)
        android.util.Log.d("FFF-Dup", "handleDownload title=${entity.title} path=${finalPath} before=${before?.id}")
        val saved = upsertBook(bookDao, entity, finalPath)
        android.util.Log.d("FFF-Dup", "handleDownload inserted id=${saved.id}")
        logWorker("handleDownload", "upserted id=${saved.id} path=$finalPath")
        jobDao.update(
            job.copy(
                bookId = saved.id,
                status = "success",
                outputPath = finalPath,
                finishedAt = System.currentTimeMillis()
            )
        )
        // The story is now in the library, so any older row for the same URL is
        // obsolete: without this, a failed/queued row from an earlier attempt (or
        // from the enqueue race fixed in v1.10) stays in the queue asking to be
        // retried or removed for a book that is already downloaded.
        supersedeOlderJobsForSameStory(jobDao, url, job.id)
        setPhase("completed", indeterminate = false)
        return Result.success()
    }

    /**
     * Retire older job rows for the same story URL once one of them has succeeded.
     * They are marked cancelled, which removes them from the download queue.
     */
    private suspend fun supersedeOlderJobsForSameStory(jobDao: DownloadJobDao, url: String, keepJobId: Long) {
        try {
            val now = System.currentTimeMillis()
            for (stale in jobDao.findSuperseded(url, keepJobId)) {
                jobDao.update(stale.copy(status = "cancelled", finishedAt = now))
                logWorker("doWork", "superseded_stale_job id=${stale.id} was=${stale.status}")
            }
        } catch (e: Exception) {
            logWorker("doWork", "supersede_failed=${e.message}")
        }
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
            jobDao.update(
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
            if (isStopped) {
                jobDao.update(
                    job.copy(
                        status = "cancelled",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("cancelled")
                return Result.failure()
            }
            setPhase("downloading")
            val raw = StorageBridge.withLocalEpub(applicationContext, path) { localFile ->
                callBridgeWithRetry("handleUpdate") { bridge.updateEpubFromPath(localFile.absolutePath, outputDir) }
            } ?: error("Cannot read existing EPUB at $path. Refresh the library and retry.")
            if (isStopped) {
                jobDao.update(
                    job.copy(
                        status = "cancelled",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("cancelled")
                return Result.failure()
            }
            val result = JSONObject(raw)
            if (!result.optBoolean("ok")) {
                jobDao.update(
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
                jobDao.update(job.copy(status = "success", bookId = existing?.id ?: job.bookId,
                    outputPath = path, finishedAt = System.currentTimeMillis()))
                setPhase("completed", indeterminate = false)
                return Result.success()
            }
            check(internalPath.isNotBlank() && File(internalPath).isFile) {
                "Generated EPUB is missing; the original was kept"
            }
            if (isStopped) {
                jobDao.update(
                    job.copy(
                        status = "cancelled",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("cancelled")
                return Result.failure()
            }
            setPhase("processing")
            val finalPath = try {
                StorageBridge.replaceExistingEpub(applicationContext, File(internalPath), path)
            } finally {
                if (File(internalPath).canonicalPath != File(path).canonicalPath) File(internalPath).delete()
            }
            if (isStopped) {
                jobDao.update(
                    job.copy(
                        status = "cancelled",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("cancelled")
                return Result.failure()
            }
            setPhase("copying")
            val updated = if (existing != null) {
                existing.copy(
                    id = existing.id,
                    title = title,
                    filePath = finalPath,
                    sourcePath = LibraryEpubStorage.sourceAfterReplacement(existing.filePath, existing.sourcePath, finalPath),
                    lastModified = result.optLong("modified", System.currentTimeMillis()),
                    sizeBytes = result.optLong("size", 0L),
                    coverData = result.optString("cover", existing.coverData ?: ""),
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
            val current = jobDao.getById(job.id)
            if (current?.status == "cancelled") {
                setPhase("cancelled")
                return Result.failure()
            }
            val before = bookDao.findByFilePath(finalPath)
            android.util.Log.d("FFF-Dup", "handleUpdate title=${updated.title} path=${finalPath} existingId=${existing?.id} before=${before?.id}")
            val saved = upsertBook(bookDao, updated, finalPath)
            android.util.Log.d("FFF-Dup", "handleUpdate inserted id=${saved.id}")
            jobDao.update(
                job.copy(
                    bookId = saved.id,
                    status = "success",
                    outputPath = finalPath,
                    finishedAt = System.currentTimeMillis()
                )
            )
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
        if (path.isBlank()) {
            jobDao.update(
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
            if (isStopped) {
                jobDao.update(
                    job.copy(
                        status = "cancelled",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("cancelled")
                return Result.failure()
            }
            setPhase("downloading")
            val raw = StorageBridge.withLocalEpub(applicationContext, path) { localFile ->
                callBridgeWithRetry("handleForceDownload") { bridge.forceDownloadFromEpub(localFile.absolutePath, outputDir) }
            } ?: error("Cannot read existing EPUB at $path. Refresh the library and retry.")
            if (isStopped) {
                jobDao.update(
                    job.copy(
                        status = "cancelled",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("cancelled")
                return Result.failure()
            }
            val result = JSONObject(raw)
            if (!result.optBoolean("ok")) {
                jobDao.update(
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
                jobDao.update(job.copy(status = "success", bookId = existing?.id ?: job.bookId,
                    outputPath = path, finishedAt = System.currentTimeMillis()))
                setPhase("completed", indeterminate = false)
                return Result.success()
            }
            check(internalPath.isNotBlank() && File(internalPath).isFile) {
                "Generated EPUB is missing; the original was kept"
            }
            if (isStopped) {
                jobDao.update(
                    job.copy(
                        status = "cancelled",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("cancelled")
                return Result.failure()
            }
            setPhase("processing")
            val finalPath = try {
                StorageBridge.replaceExistingEpub(applicationContext, File(internalPath), path)
            } finally {
                if (File(internalPath).canonicalPath != File(path).canonicalPath) File(internalPath).delete()
            }
            if (isStopped) {
                jobDao.update(
                    job.copy(
                        status = "cancelled",
                        finishedAt = System.currentTimeMillis()
                    )
                )
                setPhase("cancelled")
                return Result.failure()
            }
            setPhase("copying")
            val updatedEntity = if (existing != null) {
                existing.copy(
                    title = title,
                    filePath = finalPath,
                    sourcePath = LibraryEpubStorage.sourceAfterReplacement(existing.filePath, existing.sourcePath, finalPath),
                    lastModified = result.optLong("modified", System.currentTimeMillis()),
                    sizeBytes = result.optLong("size", 0L),
                    coverData = result.optString("cover", existing.coverData ?: ""),
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
            val current = jobDao.getById(job.id)
            if (current?.status == "cancelled") {
                setPhase("cancelled")
                return Result.failure()
            }
            val before = bookDao.findByFilePath(finalPath)
            android.util.Log.d("FFF-Dup", "handleForceDownload title=${updatedEntity.title} path=${finalPath} existingId=${existing?.id} before=${before?.id}")
            val saved = upsertBook(bookDao, updatedEntity, finalPath)
            android.util.Log.d("FFF-Dup", "handleForceDownload inserted id=${saved.id}")
            jobDao.update(
                job.copy(
                    bookId = saved.id,
                    status = "success",
                    outputPath = finalPath,
                    finishedAt = System.currentTimeMillis()
                )
            )
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
            jobDao.update(
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
        val raw = callBridgeWithRetry("handleMetadata") { bridge.fanficfareMetadata(url) }
        val result = JSONObject(raw)
        val status = if (result.optBoolean("ok")) "success" else "failed"
        val errorMessage = result.optString("error", "")
        if (errorMessage.isNotBlank()) {
            android.util.Log.e("FanFicFareWorker", "FanFicFare error: $errorMessage")
        }
        jobDao.update(
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
