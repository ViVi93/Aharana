package com.vivi.aharana

import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-owner filter/index state, used on one dispatcher (the ViewModel main dispatcher).
 * Scans belong to the supplied scope, never an Activity. Dialogs own only awaiters.
 * A complete snapshot and its filters are published atomically; partial/failed work stays private.
 */
internal class LibraryFilterState<T>(
    private val scope: CoroutineScope,
    private val snapshot: () -> List<T>,
    private val signature: (List<T>) -> List<String>,
    private val read: suspend (T) -> Pair<LibraryFacetRecord, Boolean>,
    private val onChanged: () -> Unit = {},
    private val onProgress: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val persistSelection: suspend (Map<String, Set<String>>) -> Unit = {},
    private val readFresh: suspend (T) -> Pair<LibraryFacetRecord, Boolean> = read,
    private val loadCached: suspend (List<T>) -> Map<String, LibraryFacetRecord> = { emptyMap() },
    private val identity: (T) -> String = { signature(listOf(it)).single() }
) {
    var selection: Map<String, Set<String>> = emptyMap()
        private set
    var records: Map<String, LibraryFacetRecord> = emptyMap()
        private set
    var indexedBooks: List<T> = emptyList()
        private set
    var failures: Int = 0
        private set
    private var indexedSignature: List<String>? = null
    private val persistenceMutex = Mutex()
    private var invalidation = 0L
    private var publishedInvalidation = -1L
    private var scan: Deferred<Unit>? = null
    private var owner = 0L
    private var waiters = 0
    private var pins = 0
    private var applySerial = 0L
    private var applyJob: Job? = null
    private var maintenance: Job? = null
    private var validationJob: Job? = null
    private var unreadablePaths: Set<String> = emptySet()
    private var validation = 0L
    private var publishedValidation = 0L

    fun isFresh(): Boolean = indexedSignature == signature(snapshot()) && publishedInvalidation == invalidation

    /** Restore derived data without source IO. Never replay a preference save at startup. */
    suspend fun restore(saved: Map<String, Set<String>>) {
        val serial = applySerial
        while (true) {
            val books = snapshot().toList()
            val expected = signature(books)
            val cached = loadCached(books)
            coroutineContext.ensureActive()
            if (serial != applySerial) return // An explicit Apply/Clear always wins.
            if (expected != signature(snapshot())) continue
            val paths = books.mapTo(hashSetOf(), identity)
            records = cached.filter { (path, record) -> path in paths && record.path == path }
            indexedBooks = books.filter { identity(it) in records }
            selection = saved.filter { it.key in LibraryFilters.categories && it.value.isNotEmpty() }
                .mapValues { it.value.toSet() }
            if (indexedBooks.size == books.size) {
                indexedBooks = books
                indexedSignature = expected
                publishedInvalidation = invalidation
            }
            onChanged()
            return
        }
    }

    /** Preview cancellation only releases that preview; it cannot cancel an Apply or maintenance. */
    suspend fun prepare(force: Boolean = false) {
        if (force) invalidation++
        waiters++
        try { awaitFresh() }
        finally { waiters--; cancelUnneededScan() }
    }

    private suspend fun awaitFresh(includeValidation: Boolean = false) {
        while (!isFresh() || (includeValidation && validation > publishedValidation)) {
            coroutineContext.ensureActive()
            val previous = scan
            // A cancelled IO read may still be unwinding. Wait before starting
            // another builder so even rapid cancel/reopen never overlaps scans.
            if (previous != null && !previous.isActive && !previous.isCompleted) {
                previous.join()
                continue
            }
            val active = scan?.takeIf { it.isActive } ?: startScan()
            active.await()
        }
    }

    private fun startScan(): Deferred<Unit> {
        val token = ++owner
        val task = scope.async(start = CoroutineStart.LAZY) {
            try {
                while (!isFresh() || validation > publishedValidation) {
                    val books = snapshot().toList()
                    val expected = signature(books)
                    val version = invalidation
                    val validating = validation
                    val checkSources = validating > publishedValidation
                    val next = linkedMapOf<String, LibraryFacetRecord>()
                    val failed = mutableSetOf<String>()
                    val previousBooks = indexedBooks.associateBy(identity)
                    var errors = 0
                    for ((index, book) in books.withIndex()) {
                        ensureActive()
                        if (owner == token && (index % 50 == 0 || index == books.lastIndex))
                            onProgress(if (indexedSignature != null) "Checking for changes ${index + 1}/${books.size}…"
                                else "Preparing library ${index + 1}/${books.size}…")
                        val path = identity(book)
                        val force = version > publishedInvalidation && version > 0L
                        val oldBook = previousBooks[path]
                        val reusable = !force && !checkSources && path !in unreadablePaths && oldBook != null &&
                            signature(listOf(oldBook)) == signature(listOf(book))
                        val prior = records[path]?.takeIf { reusable }
                        val (record, readable) = if (prior != null) prior to true
                            else if (force) readFresh(book) else read(book)
                        next[record.path] = record
                        if (!readable) { errors++; failed.add(record.path) }
                    }
                    ensureActive()
                    if (expected != signature(snapshot()) || version != invalidation) continue
                    // No suspension from validation through publication on the owner dispatcher.
                    records = next.toMap()
                    indexedBooks = books
                    failures = errors
                    unreadablePaths = failed
                    indexedSignature = expected
                    publishedInvalidation = version
                    publishedValidation = validating
                    onChanged()
                }
            } finally {
                if (owner == token) onProgress("")
            }
        }
        scan = task
        task.start()
        return task
    }

    /** Recheck at Apply, not merely when the dialog was opened. Newer Apply/Clear wins. */
    fun apply(requested: Map<String, Set<String>>): Job {
        val draft = requested.filter { it.key in LibraryFilters.categories && it.value.isNotEmpty() }
            .mapValues { it.value.toSet() }
        val serial = ++applySerial
        applyJob?.cancel()
        val task = scope.launch(start = CoroutineStart.LAZY) {
            pins++
            try {
                do {
                    if (draft.isNotEmpty()) awaitFresh()
                    ensureActive()
                    // Finish atomic disk saves in request order, including superseded
                    // requests; the newer Apply/Clear must be last on disk as well.
                    withContext(NonCancellable) {
                        persistenceMutex.withLock { persistSelection(draft) }
                    }
                    ensureActive()
                    if (serial != applySerial) return@launch
                } while (draft.isNotEmpty() && !isFresh())
                selection = draft
                if (draft.isEmpty()) maintenance?.cancel()
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onError("Filters could not be saved/applied. Previous filters and results were kept; reopen filters to retry.")
            } finally {
                pins--
                cancelUnneededScan()
            }
        }
        applyJob = task
        task.start()
        return task
    }

    fun booksChanged() {
        // Existing or pending scans independently revalidate their source before publishing.
        if (selection.isEmpty() || isFresh() || maintenance?.isActive == true) return
        maintenance = scope.launch {
            pins++
            try { awaitFresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                onError("Filter metadata could not be refreshed. Last complete results were kept; reopen filters to retry.")
            } finally { pins--; cancelUnneededScan() }
        }
    }

    /** Source validation owns its pin independently of applied-filter maintenance and Clear. */
    fun validateInBackground(): Job {
        validation++
        validationJob?.takeIf { it.isActive }?.let { return it }
        val task = scope.launch(start = CoroutineStart.LAZY) {
            pins++
            try { awaitFresh(includeValidation = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onError("Changes could not be checked. Cached library results were kept.") }
            finally { pins--; cancelUnneededScan() }
        }
        validationJob = task
        task.start()
        return task
    }

    private fun cancelUnneededScan() {
        if (waiters == 0 && pins == 0 && selection.isEmpty()) {
            scan?.cancel()
            // Clear immediately; a cancelled LAZY scan might never execute its finally.
            onProgress("")
        }
    }
}
