package com.vivi.aharana

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.BackoffPolicy
import java.util.concurrent.TimeUnit
import com.vivi.aharana.data.local.AppDatabase
import com.vivi.aharana.data.local.BookDao
import com.vivi.aharana.data.local.BookEntity
import com.vivi.aharana.data.local.LibraryBookRow
import com.vivi.aharana.data.local.DownloadJobDao
import com.vivi.aharana.data.local.DownloadJobEntity
import com.vivi.aharana.model.BookItem
import com.vivi.aharana.data.local.toBookItem
import com.vivi.aharana.data.local.toEntity
import com.vivi.aharana.FanFicFareWorker
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class BookRepository(context: Context) {

    private val context = context.applicationContext
    private val database = AppDatabase.getInstance(context)
    private val bookDao = database.bookDao()
    private val downloadJobDao = database.downloadJobDao()
    val jobDao: com.vivi.aharana.data.local.DownloadJobDao = downloadJobDao
    private val processState = sharedState.get { LibraryState() }
    private val scope = processState.scope
    private val prefs: SharedPreferences = context.getSharedPreferences("fanficfare_prefs", Context.MODE_PRIVATE)

    companion object {
        private class LibraryState {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val books = MutableLiveData<List<BookItem>>(emptyList())
            val jobs = MutableLiveData<List<DownloadJobEntity>>(emptyList())
            val started = ProcessValue<Unit>()
            val writes = LibraryWrites()
        }
        private val sharedState = ProcessValue<LibraryState>()
        private val instance = ProcessValue<BookRepository>()

        /** Application-owned repository. Legacy wrappers also share this subscription/state. */
        fun getInstance(context: Context): BookRepository =
            instance.get { BookRepository(context.applicationContext) }

        private val metadataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val metadataSubmissionMutex = Mutex()
        private val metadataGeneration = java.util.concurrent.atomic.AtomicInteger(0)

        /** Caller holds LibraryStorageGate. Safe to replay after process death or DB failure. */
        suspend fun reconcileOrganisedPaths(context: Context, failOnError: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
            val journalDir = java.io.File(context.filesDir, "organisation")
            if (!journalDir.exists()) return@withContext JSONObject().put("ok", true).put("can_undo", false)
            if (!com.chaquo.python.Python.isStarted()) {
                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(context.applicationContext))
            }
            val recovery = JSONObject(PythonBridge(context).organiseLibrary("recovery"))
            val deletedBooks = recovery.optJSONArray("deleted_books") ?: JSONArray()
            val rows = recovery.optJSONArray("moves") ?: JSONArray()
            val moves = buildMap<String, String> {
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    put(row.getString("source"), row.getString("target"))
                }
            }
            if (moves.isNotEmpty() || deletedBooks.length() > 0) {
                val database = AppDatabase.getInstance(context.applicationContext)
                val bookDao = database.bookDao()
                val jobDao = database.downloadJobDao()
                database.withTransaction {
                    for (i in 0 until deletedBooks.length()) {
                        val deletion = deletedBooks.getJSONObject(i)
                        val current = bookDao.findRowById(deletion.optLong("book_id"))
                        // Never delete a replacement row merely because it reused a path.
                        if (current != null && current.filePath == deletion.getString("path")) bookDao.deleteById(current.id)
                    }
                    for (book in bookDao.getAll()) {
                        val (path, source) = remapOrganisedPaths(book.filePath, book.sourcePath, moves)
                        if (path != book.filePath || source != book.sourcePath) {
                            bookDao.updatePaths(book.id, path, source)
                        }
                    }
                    for (job in jobDao.getAll()) {
                        val input = job.inputPath?.let { moves[it] ?: it }
                        val output = job.outputPath?.let { moves[it] ?: it }
                        if (input != job.inputPath || output != job.outputPath) {
                            jobDao.updatePaths(job.id, job.workId, input, output)
                        }
                    }
                }
            }
            // A later transfer may need manual attention while earlier moves succeeded.
            // Commit those exact paths before surfacing the recovery failure.
            if (failOnError) check(recovery.optBoolean("ok")) { recovery.optString("error", "Cannot recover organisation journal") }
            recovery
        }

        private const val BULK_METADATA_TAG = "bulk_metadata_v1"
        private const val KEY_SORT = "sort"
        private const val KEY_SORT_DIRECTION = "sort_direction"

        /**
         * A queue row younger than this is never treated as an orphan by
         * reconciliation - it may simply be between insert and WorkManager enqueue.
         */
        private const val RECONCILE_GRACE_MS = 2 * 60 * 1000L
    }

    private val _books = processState.books
    val books: LiveData<List<BookItem>> = _books
    private val _latestJobs = processState.jobs
    val latestJobs: LiveData<List<DownloadJobEntity>> = _latestJobs

    init {
        processState.started.get {
            android.util.Log.d("FFF-UI-OBS", "BookRepository init starting")
            scope.launch {
                migrateIfNeeded()
                withContext(Dispatchers.Main) {
                    // Intentionally process-owned: no Activity/repository wrapper can
                    // install a second permanent Room subscription.
                    android.util.Log.d("FFF-UI-OBS", "BookRepository init observing books")
                    bookDao.observeAll().observeForever { entities ->
                        _books.postValue(entities.map { it.toBookItem() })
                    }
                    android.util.Log.d("FFF-UI-OBS", "BookRepository init observing jobs")
                    downloadJobDao.observeAll().observeForever { jobs ->
                        android.util.Log.d("FFF-UI-OBS", "BookRepository job observer fired count=${jobs.size}")
                        _latestJobs.postValue(jobs)
                    }
                }
                // Projections are safe even before cleanup; keep the shelf and storage recovery
                // independent of legacy history size. Process-owned startup runs this only once.
                scope.launch {
                    try {
                        val count = QueueHistory.compactLegacy(downloadJobDao)
                        DiagnosticLog.append(context, "Queue.History", "compacted_rows=$count")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DiagnosticLog.appendException(context, "Queue.History", "compaction_failed", e)
                    }
                }
                // Keep the cached shelf usable while a worker owns storage during network I/O.
                // All file mutations resolve recovery again under the same gate before acting.
                try {
                    LibraryStorageGate.run {
                        reconcileOrganisedPaths(context)
                        reconcileStaleRunningJobs()
                    }
                } catch (e: Exception) {
                    DiagnosticLog.appendException(context, "Library.Organise", "recovery_failed", e)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "File organisation recovery needs attention: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            Unit
        }
    }

    /**
     * Bring Room back in line with WorkManager for rows that still claim to be in
     * flight.
     *
     * These rows are enqueued with `requestWorkId` as the *unique work name*, not as
     * a WorkManager work id, so WorkManager has to be queried by name
     * (`getWorkInfosForUniqueWork`). The previous version called
     * `getWorkInfoById(UUID.fromString(workId))`, which never matched anything -
     * so a row left at "queued"/"waiting"/"running" by an interrupted run was never
     * reconciled and stayed in the download queue for good (the "4 jobs in queue
     * with nothing downloaded" report).
     */
    private suspend fun reconcileStaleRunningJobs() = withContext(Dispatchers.IO) {
        try {
            val workManager = WorkManager.getInstance(context)

            val stale = downloadJobDao.getByStatus("running") +
                downloadJobDao.getByStatus("queued") +
                downloadJobDao.getByStatus(FanFicFareWorker.STATUS_WAITING)
            for (job in stale) {
                val workName = job.workId?.ifBlank { null }
                if (workName == null) {
                    // A row is only without a work name between insert and enqueue,
                    // so never judge a fresh one: doing that marked a just-shared
                    // download as failed while its worker was still starting up.
                    if (System.currentTimeMillis() - job.createdAt < RECONCILE_GRACE_MS) continue
                    markInterrupted(job, "interrupted_no_work_name")
                    continue
                }
                try {
                    val infos = workManager.getWorkInfosForUniqueWork(workName).get()
                    val live = infos?.lastOrNull()
                    if (live == null) {
                        if (System.currentTimeMillis() - job.createdAt < RECONCILE_GRACE_MS) continue
                        // The work itself is gone (cancelled, pruned, or lost with a
                        // force-stop): the row can never complete on its own.
                        markInterrupted(job, "interrupted_work_gone")
                        continue
                    }
                    when (live.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            if (outputIsUsable(job)) {
                                downloadJobDao.updateFromWorker(job.copy(status = "success", finishedAt = System.currentTimeMillis()))
                            } else {
                                downloadJobDao.updateFromWorker(job.copy(status = "failed", error = "stale_recovery_missing_output", finishedAt = System.currentTimeMillis()))
                            }
                        }
                        androidx.work.WorkInfo.State.FAILED ->
                            downloadJobDao.updateFromWorker(job.copy(status = "failed", error = "workmanager_failed", finishedAt = System.currentTimeMillis()))
                        androidx.work.WorkInfo.State.CANCELLED ->
                            markInterrupted(job, "WorkManager stopped this request. Retry to resume.")
                        // ENQUEUED / BLOCKED / RUNNING: still genuinely in flight
                        else -> {}
                    }
                } catch (e: Exception) {
                    markInterrupted(job, "stale_recovery_failed")
                }
            }
        } catch (e: Exception) {
            // ignore reconciliation failures
        }
    }


    private fun normalizeStoryUrl(url: String): String {
        var value = url.trim().lowercase()
        value = value.substringBefore('?').substringBefore('#')
        value = value.removePrefix("https://").removePrefix("http://")
        value = value.removePrefix("www.")
        return value.trimEnd('/')
    }

    private fun outputIsUsable(job: DownloadJobEntity): Boolean {
        val outputPath = job.outputPath?.ifBlank { null } ?: return false
        val file = java.io.File(outputPath)
        return file.exists() && file.isFile && file.length() > 0
    }

    /**
     * Terminal state for a row whose work no longer exists: success if a usable
     * EPUB is already on disk, otherwise failed with a reason the user can act on
     * (Retry) instead of an indefinite "waiting" row.
     */
    private suspend fun markInterrupted(job: DownloadJobEntity, reason: String) {
        if (job.status == "success" || job.status == "cancelled") return
        val now = System.currentTimeMillis()
        if (outputIsUsable(job)) {
            downloadJobDao.updateFromWorker(job.copy(status = "success", finishedAt = now))
            DiagnosticLog.append(context, "Queue.Reconcile", "jobId=${job.id} success_output_present")
        } else {
            downloadJobDao.updateFromWorker(job.copy(status = "failed", error = reason, finishedAt = now))
            DiagnosticLog.append(context, "Queue.Reconcile", "jobId=${job.id} failed reason=$reason")
        }
    }

    suspend fun getAllDownloadJobs(): List<DownloadJobEntity> = withContext(Dispatchers.IO) {
        downloadJobDao.getAll()
    }

    suspend fun updateDownloadJobStatus(jobId: Long, status: String, finishedAt: Long) {
        withContext(Dispatchers.IO) {
            val job = downloadJobDao.getById(jobId)
            if (job != null) downloadJobDao.updateFromWorker(job.copy(status = status, finishedAt = finishedAt))
        }
    }

    fun getBooks(): List<BookItem> = _books.value ?: emptyList()

    fun getBooksSnapshot(): List<BookItem> = (_books.value ?: emptyList()).toList()

    /** Index of the in-memory entry for this book, path first (see findByExisting). */
    fun findByIdentity(book: BookItem): Int {
        val list = _books.value ?: return -1
        val normalized = book.uriString.trim()
        if (normalized.isNotBlank()) {
            val byPath = list.indexOfFirst { it.uriString.trim() == normalized }
            if (byPath >= 0) return byPath
        }
        if (book.url.isNotBlank()) {
            return list.indexOfFirst { it.url.isNotBlank() && it.url == book.url }
        }
        return -1
    }

    fun addOrUpdate(book: BookItem) {
        val list = (_books.value ?: emptyList()).toMutableList()
        val idx = findByIdentity(book)
        val msg = "title=${book.title} url=${book.url} path=${book.uriString} idx=$idx sizeBefore=${list.size}"
        android.util.Log.d("FFF-Dup", msg)
        DiagnosticLog.append(context, "FFF-Dup", msg)
        if (idx >= 0) {
            list[idx] = book.copy(coverUriString = null)
            val msg2 = "addOrUpdate replaced_at=$idx"
            android.util.Log.d("FFF-Dup", msg2)
            DiagnosticLog.append(context, "FFF-Dup", msg2)
        } else {
            list.add(0, book.copy(coverUriString = null))
            val msg2 = "addOrUpdate appended"
            android.util.Log.d("FFF-Dup", msg2)
            DiagnosticLog.append(context, "FFF-Dup", msg2)
        }
        _books.value = list
        val msg3 = "addOrUpdate sizeAfter=${list.size}"
        android.util.Log.d("FFF-Dup", msg3)
        DiagnosticLog.append(context, "FFF-Dup", msg3)
        processState.writes.launch(scope) {
            LibraryStorageGate.run { upsertBookEntity(book) }
        }
    }

    fun updateBook(oldBook: BookItem, newBook: BookItem) {
        val list = (_books.value ?: emptyList()).toMutableList()
        val idx = findByIdentity(oldBook)
        val shelfBook = newBook.copy(coverUriString = null)
        if (idx >= 0) list[idx] = shelfBook else list.add(0, shelfBook)
        _books.value = list
        processState.writes.launch(scope) {
            LibraryStorageGate.run { upsertBookEntity(newBook) }
        }
    }

    fun remove(book: BookItem) {
        val list = (_books.value ?: emptyList()).toMutableList()
        val removed = list.removeAll { it.uriString == book.uriString }
        _books.value = list
        if (removed) {
            processState.writes.launch(scope) {
                LibraryStorageGate.run {
                    if (book.libraryId > 0) bookDao.deleteById(book.libraryId)
                    else findByExisting(book)?.let { bookDao.deleteById(it.id) }
                }
            }
        }
    }

    fun clear() {
        _books.value = emptyList()
        processState.writes.launch(scope) {
            LibraryStorageGate.run { bookDao.clear() }
        }
    }

    fun setBooks(newBooks: List<BookItem>) {
        val msg = "setBooks count=${newBooks.size}"
        android.util.Log.d("FFF-Dup", msg)
        DiagnosticLog.append(context, "FFF-Dup", msg)
        // A snapshot's omissions are not deletions: workers may have inserted rows
        // since it was taken. Deletion must use remove/clear or an exact row mutation.
        _books.value = newBooks.map { it.copy(coverUriString = null) }
        processState.writes.launch(scope) {
            LibraryStorageGate.run {
                database.withTransaction {
                    newBooks.forEach { upsertBookEntity(it) }
                }
            }
        }
    }

    /** Caller holds the storage gate from recursive scan through commit. */
    suspend fun importOrganisedLibrary(rows: JSONArray): Int = withContext(Dispatchers.IO) {
        val seen = mutableSetOf<String>()
        database.withTransaction {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val path = row.optString("path")
                if (path.isBlank() || !seen.add(path)) continue
                val existing = bookDao.findRowByFilePath(path)
                val book = BookEntity(
                    id = existing?.id ?: 0,
                    title = row.optString("title", "untitled"),
                    author = row.optString("author"),
                    url = row.optString("url").ifBlank { null },
                    filePath = path,
                    sourcePath = existing?.sourcePath ?: path,
                    lastModified = row.optLong("modified"),
                    sizeBytes = row.optLong("size"),
                    coverData = row.optString("cover").ifBlank { null },
                    chapters = row.optInt("chapters"),
                    addedAt = existing?.addedAt ?: System.currentTimeMillis()
                )
                if (existing != null) {
                    bookDao.updateMetadata(existing.id, book.title, book.author, book.url, book.filePath,
                        book.sourcePath, book.lastModified, book.sizeBytes, book.chapters, book.coverData)
                } else bookDao.insert(book)
            }
        }
        seen.size
    }

    suspend fun loadLibrary(): Boolean {
        val entities = bookDao.getAll()
        val msg = "loadLibrary entityCount=${entities.size}"
        android.util.Log.d("FFF-Dup", msg)
        DiagnosticLog.append(context, "FFF-Dup", msg)
        withContext(Dispatchers.Main) { _books.value = entities.map { it.toBookItem() } }
        return true
    }

    /**
     * Insert or update a single book row, leaving the in-memory list alone.
     *
     * Background entry point for callers already holding storage exclusion.
     * Unlike [addOrUpdate] it never assigns LiveData (main thread only).
     */
    suspend fun upsertBook(book: BookItem) {
        upsertBookEntity(book)
    }

    suspend fun saveLibrary(): Boolean {
        // Every mutation persists individually. Await writes submitted before this
        // call, never publish older shelf metadata over a worker/organisation commit.
        processState.writes.awaitPending()
        return withContext(Dispatchers.IO) {
            database.withTransaction { bookDao.count() >= 0 }
        }
    }

    private suspend fun upsertBookEntity(book: BookItem) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val existing = findByExisting(book)
                if (existing != null) {
                    bookDao.updateMetadata(existing.id, book.title, book.author, book.url.ifBlank { null },
                        book.uriString, book.sourceUriString, book.lastModified, book.sizeBytes,
                        book.chapters, book.coverUriString)
                } else {
                    bookDao.insert(book.toEntity())
                }
            }
        }
    }

    /**
     * The row that already represents this book, if any.
     *
     * The path is checked before the URL: a file is identified by where it is, and
     * two different books can legitimately share a source URL (a merged book and
     * the work it was merged from). Matching URL-first meant registering a merge
     * found the base book's row and updated it, so the merged book never appeared.
     */
    private suspend fun findByExisting(book: BookItem): LibraryBookRow? {
        return withContext(Dispatchers.IO) {
            // Positive IDs are mandatory identity, never a hint to select a replacement.
            if (book.libraryId > 0) {
                return@withContext checkNotNull(bookDao.findRowById(book.libraryId)) {
                    "Book is no longer in the library"
                }
            }
            val normalizedPath = book.uriString.trim()
            if (normalizedPath.isNotBlank()) {
                bookDao.findRowByFilePath(normalizedPath)?.let { return@withContext it }
            }
            val normalizedUrl = book.url.trim().ifBlank { null }
            if (!normalizedUrl.isNullOrBlank()) {
                bookDao.findRowByUrl(normalizedUrl)?.let { return@withContext it }
            }
            null
        }
    }

    private suspend fun migrateIfNeeded() {
        withContext(Dispatchers.IO) {
            if (prefs.getBoolean("library_migrated_v1", false)) {
                return@withContext
            }
            if (bookDao.count() > 0) {
                prefs.edit().putBoolean("library_migrated_v1", true).apply()
                return@withContext
            }
            val indexPath = getFilePath("library_index.json")
            if (!java.io.File(indexPath).exists()) {
                prefs.edit().putBoolean("library_migrated_v1", true).apply()
                return@withContext
            }
            val text = try { java.io.File(indexPath).readText() } catch (e: Exception) { null }
            if (text.isNullOrBlank()) {
                prefs.edit().putBoolean("library_migrated_v1", true).apply()
                return@withContext
            }
            try {
                val result = JSONObject(text)
                val booksArray = result.optJSONArray("books") ?: JSONArray()
                val entities = mutableListOf<BookEntity>()
                for (i in 0 until booksArray.length()) {
                    val b = booksArray.getJSONObject(i)
                    val now = System.currentTimeMillis()
                    entities += BookEntity(
                        title = b.optString("title", "untitled"),
                        author = b.optString("author", ""),
                        url = b.optString("url", "").trim().ifBlank { null },
                        filePath = b.optString("path", "").trim(),
                        sourcePath = b.optString("source", "").trim().ifBlank { null },
                        lastModified = b.optLong("modified", 0L),
                        sizeBytes = b.optLong("size", 0L),
                        coverData = b.optString("cover", "").ifBlank { null },
                        chapters = b.optInt("chapters", 0),
                        addedAt = now
                    )
                }
                bookDao.insertAll(entities)
                prefs.edit().putBoolean("library_migrated_v1", true).apply()
            } catch (e: Exception) {
                // corrupt JSON: do not mark migrated; leave JSON intact
            }
        }
    }

    private fun getFilePath(name: String): String {
        val dir = context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return java.io.File(dir, name).absolutePath
    }

    fun getSavedSort(): String? = prefs.getString(KEY_SORT, null)

    fun setSavedSort(sort: String) {
        prefs.edit().putString(KEY_SORT, sort).apply()
    }

    fun getSavedSortDirection(): Boolean = prefs.getBoolean(KEY_SORT_DIRECTION, true)

    fun setSavedSortDirection(descending: Boolean) {
        prefs.edit().putBoolean(KEY_SORT_DIRECTION, descending).apply()
    }

    private suspend fun bulkSnapshot(): BulkQueueSnapshot {
        val books = bookDao.getAll().map { book ->
            BulkQueueBook(book.id, book.url, book.filePath,
                BulkQueueStorage.isAvailable(book.filePath) { path ->
                    context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(path), "r")?.use { descriptor ->
                        // -1 means a provider cannot report length; probe only one byte, not the whole EPUB.
                        descriptor.length != 0L && descriptor.createInputStream().use { it.read() != -1 }
                    } ?: false
                })
        }
        val jobs = downloadJobDao.getAll().map {
            BulkQueueJob(it.type, it.status, it.inputUrl, it.bookId, it.inputPath)
        }
        return BulkQueueSnapshot(books, jobs)
    }

    suspend fun previewBulkUrls(urls: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        bulkSnapshot().preview(urls)
    }

    suspend fun enqueueBulkUrls(urls: List<String>, mode: String): BulkQueueResult = withContext(Dispatchers.IO) {
        require(mode in setOf("new", "update", "smart")) { "Unknown bulk action" }
        BulkQueueCoordinator().enqueue(urls, mode, { bulkSnapshot() },
            { url -> withContext(NonCancellable) { enqueueDownload(url) }; Unit },
            { book -> withContext(NonCancellable) { enqueueUpdate(book.id, book.path) }; Unit })
    }

    /** Submission survives Activity recreation; each row is recoverable by its own work name.
     * Reopening a preview retries failed/interrupted metadata, never from an observer loop.
     */
    suspend fun ensureBulkMetadata(urls: List<String>): List<Long> {
        val generation = metadataGeneration.get()
        return metadataScope.async {
            metadataSubmissionMutex.withLock {
                val manager = WorkManager.getInstance(context.applicationContext)
                val existing = downloadJobDao.getAll().filter { it.type == "metadata" }
                val requested = urls.distinct().toMutableSet()
                val infos = manager.getWorkInfosByTag("fanficfare_metadata").get()
                val legacyLive = infos.any { !it.state.isFinished && BULK_METADATA_TAG !in it.tags }
                if (legacyLive && generation == metadataGeneration.get()) {
                    // Legacy batch requests had no discoverable row/work-name link. Migrate
                    // metadata only, cancelling them before resubmission to avoid duplicates.
                    manager.cancelAllWorkByTag("fanficfare_metadata").result.get()
                    existing.filter { it.status in setOf("queued", "waiting", "running") }.forEach { row ->
                        row.inputUrl?.let { requested.add(it) }
                        downloadJobDao.update(row.copy(status = "cancelled", finishedAt = System.currentTimeMillis()))
                    }
                }
                val latest = existing.filter { !it.inputUrl.isNullOrBlank() }
                    .groupBy { it.inputUrl!! }.mapValues { it.value.maxByOrNull { row -> row.createdAt }!! }
                val submitted = mutableListOf<Long>()
                for ((index, url) in requested.withIndex()) {
                    if (generation != metadataGeneration.get()) break
                    if (index > 0 && index % 5 == 0) kotlinx.coroutines.delay(200)
                    val old = latest[url]
                    // Metadata success is committed only for ok=true. Bulk rows intentionally
                    // omit response bodies; do not refetch completed metadata just for its cover.
                    val successful = old?.status == "success"
                    val live = old?.workId?.let { name ->
                        manager.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }
                    } ?: false
                    if (!BulkQueueMetadataPolicy.needsSubmission(successful, live)) continue
                    if (generation != metadataGeneration.get()) break
                    // A non-cancellable bounded row+enqueue operation cannot leave a fresh
                    // row unscheduled merely because a screen was rotated or replaced.
                    withContext(NonCancellable) {
                        val workName = java.util.UUID.randomUUID().toString()
                        val row = DownloadJobEntity(type = "metadata", status = "queued", inputUrl = url,
                            workId = workName, createdAt = System.currentTimeMillis())
                        val id = downloadJobDao.insert(row)
                        try {
                            val work = OneTimeWorkRequestBuilder<FanFicFareWorker>()
                                .setInputData(workDataOf(FanFicFareWorker.KEY_TYPE to "metadata",
                                    FanFicFareWorker.KEY_URL to url, FanFicFareWorker.KEY_WORK_ID to workName))
                                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                                .addTag("fanficfare_metadata").addTag(BULK_METADATA_TAG).build()
                            manager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, work).result.get()
                            submitted.add(id)
                        } catch (e: Exception) {
                            downloadJobDao.getById(id)?.let { current ->
                                downloadJobDao.update(current.copy(status = "failed", error = "metadata_submission_failed",
                                    finishedAt = System.currentTimeMillis()))
                            }
                            throw e
                        }
                    }
                }
                submitted.toList()
            }
        }.await()
    }

    fun cancelBulkMetadata() {
        metadataGeneration.incrementAndGet()
        metadataScope.launch {
            try {
                metadataSubmissionMutex.withLock {
                    downloadJobDao.getAll().filter {
                        it.type == "metadata" && it.status in setOf("queued", "waiting", "running")
                    }.forEach { row -> cancelJobAwaited(row) }
                }
            } catch (e: Exception) {
                DiagnosticLog.appendException(context, "BulkMetadata", "cancel_failed", e)
            }
        }
    }

    private suspend fun scheduleUserWork(jobId: Long, workName: String, work: OneTimeWorkRequest) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, work).result.get()
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                downloadJobDao.getById(jobId)?.let { current ->
                    if (current.status == "queued" && current.workId == workName) downloadJobDao.updateFromWorker(current.copy(status = "failed",
                        error = "work_submission_failed", finishedAt = System.currentTimeMillis()))
                }
            }
            throw e
        }
    }

    suspend fun enqueueDownload(url: String): Long {
        // The work name is generated first and written on the row at insert time:
        // the worker looks the row up by it the instant it starts, and the
        // reconciler treats a queued row with no work name as an orphan. Inserting
        // without it left a window where a fresh share was marked failed by
        // reconciliation and then duplicated by the worker (device log 2026-10-08).
        val requestWorkId = java.util.UUID.randomUUID().toString()
        val job = DownloadJobEntity(
            bookId = 0,
            type = "download",
            status = "queued",
            inputUrl = url,
            workId = requestWorkId,
            createdAt = System.currentTimeMillis()
        )
        val jobId = downloadJobDao.insert(job)
        val work = OneTimeWorkRequestBuilder<FanFicFareWorker>()
            .setInputData(
                workDataOf(
                    FanFicFareWorker.KEY_TYPE to "download",
                    FanFicFareWorker.KEY_URL to url,
                    FanFicFareWorker.KEY_WORK_ID to requestWorkId
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(FanFicFareWorker.TAG_DOWNLOAD)
            .build()
        scheduleUserWork(jobId, requestWorkId, work)
        return jobId
    }

    suspend fun enqueueUpdate(bookId: Long, inputPath: String): Long {
        // The work name is generated first and written on the row at insert time:
        // the worker looks the row up by it the instant it starts, and the
        // reconciler treats a queued row with no work name as an orphan. Inserting
        // without it left a window where a fresh share was marked failed by
        // reconciliation and then duplicated by the worker (device log 2026-10-08).
        val requestWorkId = java.util.UUID.randomUUID().toString()
        val job = DownloadJobEntity(
            bookId = bookId,
            type = "update",
            status = "queued",
            inputPath = inputPath,
            workId = requestWorkId,
            createdAt = System.currentTimeMillis()
        )
        val jobId = downloadJobDao.insert(job)
        val work = OneTimeWorkRequestBuilder<FanFicFareWorker>()
            .setInputData(
                workDataOf(
                    FanFicFareWorker.KEY_TYPE to "update",
                    FanFicFareWorker.KEY_BOOK_ID to bookId,
                    FanFicFareWorker.KEY_INPUT_PATH to inputPath,
                    FanFicFareWorker.KEY_WORK_ID to requestWorkId
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(FanFicFareWorker.TAG_UPDATE)
            .build()
        scheduleUserWork(jobId, requestWorkId, work)
        return jobId
    }

    suspend fun enqueueForceDownload(bookId: Long, inputPath: String): Long {
        // The work name is generated first and written on the row at insert time:
        // the worker looks the row up by it the instant it starts, and the
        // reconciler treats a queued row with no work name as an orphan. Inserting
        // without it left a window where a fresh share was marked failed by
        // reconciliation and then duplicated by the worker (device log 2026-10-08).
        val requestWorkId = java.util.UUID.randomUUID().toString()
        val job = DownloadJobEntity(
            bookId = bookId,
            type = "force_download",
            status = "queued",
            inputPath = inputPath,
            workId = requestWorkId,
            createdAt = System.currentTimeMillis()
        )
        val jobId = downloadJobDao.insert(job)
        val work = OneTimeWorkRequestBuilder<FanFicFareWorker>()
            .setInputData(
                workDataOf(
                    FanFicFareWorker.KEY_TYPE to "force_download",
                    FanFicFareWorker.KEY_BOOK_ID to bookId,
                    FanFicFareWorker.KEY_INPUT_PATH to inputPath,
                    FanFicFareWorker.KEY_WORK_ID to requestWorkId
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(FanFicFareWorker.TAG_FORCE_DOWNLOAD)
            .build()
        scheduleUserWork(jobId, requestWorkId, work)
        return jobId
    }

    suspend fun enqueueMetadata(url: String): Long {
        // The work name is generated first and written on the row at insert time:
        // the worker looks the row up by it the instant it starts, and the
        // reconciler treats a queued row with no work name as an orphan. Inserting
        // without it left a window where a fresh share was marked failed by
        // reconciliation and then duplicated by the worker (device log 2026-10-08).
        val requestWorkId = java.util.UUID.randomUUID().toString()
        val job = DownloadJobEntity(
            bookId = 0,
            type = "metadata",
            status = "queued",
            inputUrl = url,
            workId = requestWorkId,
            createdAt = System.currentTimeMillis()
        )
        val jobId = downloadJobDao.insert(job)
        val work = OneTimeWorkRequestBuilder<FanFicFareWorker>()
            .setInputData(
                workDataOf(
                    FanFicFareWorker.KEY_TYPE to "metadata",
                    FanFicFareWorker.KEY_URL to url,
                    FanFicFareWorker.KEY_WORK_ID to requestWorkId
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("fanficfare_metadata")
            .build()
        scheduleUserWork(jobId, requestWorkId, work)
        return jobId
    }

    /** Compatibility entry point: submission is serialized, durable and recoverable. */
    suspend fun enqueueMetadataBatch(urls: List<String>): List<Long> = ensureBulkMetadata(urls)

    suspend fun getJob(jobId: Long): DownloadJobEntity? = downloadJobDao.getById(jobId)

    fun cancelCurrentDownload() {
        scope.launch(Dispatchers.IO) {
            try {
                val active = downloadJobDao.getAll().filter {
                    it.type in setOf("download", "update", "force_download") &&
                        it.status in setOf("queued", "waiting", "running", "failed")
                }
                // Cancel the snapshot by name, not tags: newly submitted work must not be
                // stopped without first persisting explicit cancellation on its own row.
                active.forEach { cancelJobAwaited(it) }
            } catch (e: Exception) {
                DiagnosticLog.appendException(context, "Main.Cancel", "cancel_failed", e)
            }
        }
    }

    /**
     * Re-runs a queue row and reports what it did ("download", "update",
     * "force", "metadata", "none").
     *
     * A download row whose story is already in the library is retried as an
     * *update* rather than a full download: re-downloading every chapter for a book
     * you already have is the expensive no-op users hit by tapping Retry, while an
     * update fetches only chapters that are actually new (and does nothing when the
     * local copy is already current). Force download stays an explicit action on the
     * book's detail screen.
     */
    suspend fun retryJob(job: DownloadJobEntity): String = withContext(NonCancellable + Dispatchers.IO) {
        val current = downloadJobDao.getById(job.id) ?: return@withContext "none"
        if (current.status !in setOf("failed", "cancelled")) return@withContext "none"
        var replacement = current
        when (current.type) {
            "download" -> {
                val url = current.inputUrl?.takeIf { it.isNotBlank() } ?: return@withContext "none"
                val book = libraryBookForUrl(url)
                if (book != null) {
                    replacement = current.copy(type = "update", bookId = book.id, inputPath = book.filePath)
                }
            }
            "update", "force_download" -> {
                if (current.inputPath.isNullOrBlank()) return@withContext "none"
            }
            "metadata" -> {
                if (current.inputUrl.isNullOrBlank()) return@withContext "none"
            }
            else -> return@withContext "none"
        }
        val action = if (replacement.type == "force_download") "force" else replacement.type
        val workName = UUID.randomUUID().toString()
        replacement = replacement.copy(workId = workName, status = "queued", error = null,
            resultJson = null, outputPath = null, finishedAt = null)
        val tag = when (replacement.type) {
            "download" -> FanFicFareWorker.TAG_DOWNLOAD
            "update" -> FanFicFareWorker.TAG_UPDATE
            "force_download" -> FanFicFareWorker.TAG_FORCE_DOWNLOAD
            else -> "fanficfare_metadata"
        }
        val work = OneTimeWorkRequestBuilder<FanFicFareWorker>()
            .setInputData(workDataOf(FanFicFareWorker.KEY_TYPE to replacement.type,
                FanFicFareWorker.KEY_URL to replacement.inputUrl.orEmpty(),
                FanFicFareWorker.KEY_INPUT_PATH to replacement.inputPath.orEmpty(),
                FanFicFareWorker.KEY_BOOK_ID to replacement.bookId,
                FanFicFareWorker.KEY_WORK_ID to workName))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(tag).build()
        QueueJobPolicy.retryReplacement(action, claim = {
            downloadJobDao.claimRetry(current.id, current.workId, workName, replacement.type,
                replacement.bookId, replacement.inputUrl, replacement.inputPath, System.currentTimeMillis()) > 0
        }, submit = {
            try {
                // The claim changed the row's work identity first, fencing every old
                // worker write. No second row or hidden/pending claim can be orphaned.
                current.workId?.takeIf(String::isNotBlank)?.let {
                    WorkManager.getInstance(context).cancelUniqueWork(it).result.get()
                }
                scheduleUserWork(current.id, workName, work)
                DiagnosticLog.append(context, "Queue.Retry",
                    "submitted job_id=${current.id} old_work_name=${current.workId} work_name=$workName")
            } catch (e: Exception) {
                downloadJobDao.updateFromWorker(replacement.copy(status = "failed",
                    error = "Retry could not be submitted (${e.javaClass.simpleName}). Retry again or remove it.",
                    finishedAt = System.currentTimeMillis()))
                throw e
            }
        })
    }

    /**
     * Library row for a story URL, only when its file is really on disk. The
     * library stores the URL from the EPUB's <dc:source>, so the comparison is on
     * the normalised form (see [normalizeStoryUrl]).
     */
    private suspend fun libraryBookForUrl(url: String): LibraryBookRow? {
        val target = normalizeStoryUrl(url)
        for (book in bookDao.getAll()) {
            val bookUrl = book.url?.ifBlank { null } ?: continue
            if (normalizeStoryUrl(bookUrl) != target) continue
            val path = book.filePath.ifBlank { null } ?: continue
            val file = java.io.File(path)
            if (file.exists() && file.isFile && file.length() > 0L) return book
        }
        return null
    }

    fun cancelJob(job: DownloadJobEntity) {
        scope.launch(Dispatchers.IO) {
            try {
                cancelJobAwaited(job)
            } catch (e: Exception) {
                DiagnosticLog.appendException(context, "Queue.Cancel", "cancel_failed", e)
            }
        }
    }

    private suspend fun cancelJobAwaited(job: DownloadJobEntity) = withContext(NonCancellable + Dispatchers.IO) {
        val current = downloadJobDao.getById(job.id) ?: return@withContext
        if (current.workId != job.workId) return@withContext // A later Retry owns a new request.
        val active = setOf("queued", "waiting", "running")
        val includeInterrupted = job.status in active || current.status in active ||
            (current.status == "failed" && current.workId?.takeIf(String::isNotBlank)?.let { name ->
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }
            } == true)
        val changed = downloadJobDao.cancelActive(job.id, current.workId, includeInterrupted,
            "Cancelled by user. Retry to resume.", System.currentTimeMillis())
        if (changed == 0) return@withContext // A fresh success must remain success.
        DiagnosticLog.append(context, "Queue.Cancel",
            "intent_persisted job_id=${job.id} work_name=${current.workId}")
        val workName = current.workId?.takeIf { it.isNotBlank() } ?: return@withContext
        WorkManager.getInstance(context).cancelUniqueWork(workName).result.get()
        DiagnosticLog.append(context, "Queue.Cancel", "stop_requested job_id=${job.id} work_name=$workName")
    }

    fun hasRunningJob(): Boolean {
        val jobs = _latestJobs.value ?: return false
        return jobs.any { it.status == "running" || it.status == "queued" || it.status == FanFicFareWorker.STATUS_WAITING }
    }
}
