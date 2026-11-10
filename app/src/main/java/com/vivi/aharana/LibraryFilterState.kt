package com.vivi.aharana

import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-owner filter/index state, used on one dispatcher (the ViewModel main dispatcher).
 * Scans belong to the supplied scope, never an Activity. Dialogs own only awaiters.
 * A complete snapshot and its filters are published atomically; partial/failed work stays private.
 * Production opts into manualRefreshOnly: cached data is usable without source validation,
 * membership reconciliation prunes deleted identities, and only prepare(force=true) reads sources.
 * Automatic mode is retained for legacy callers/tests, not the production ViewModel.
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
    private val identity: (T) -> String = { signature(listOf(it)).single() },
    private val manualRefreshOnly: Boolean = false,
    private val cachedCurrentPaths: (suspend (List<T>) -> Set<String>)? = null,
    private val beginRefresh: (() -> LibraryFacetRefresh<T>)? = null,
    private val cachedUnreadablePaths: suspend (List<T>) -> Set<String> = { emptySet() }
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
    private var cacheRestored = false
    private var recordRevisions: Map<String, List<String>> = emptyMap()

    /** Matches known library revisions only; external changes require an explicit refresh. */
    fun isFresh(): Boolean = if (manualRefreshOnly)
        cacheRestored && pendingCount() == 0 && publishedInvalidation == invalidation
        else indexedSignature == signature(snapshot()) && publishedInvalidation == invalidation

    fun pendingCount(): Int = snapshot().count { book ->
        identity(book) !in records || recordRevisions[identity(book)] != signature(listOf(book))
    }

    fun unknownCount(): Int = snapshot().count { identity(it) !in records }
    fun needsRefresh(): Boolean = !isFresh() || failures > 0

    fun unreadableSummary(label: (String) -> String): String {
        if (failures == 0) return ""
        val names = unreadablePaths.take(5).map { path ->
            label(path).replace(Regex("[\\p{Cntrl}]"), " ").take(120).ifBlank { "Untitled book" }
        }
        return "$failures books could not be read (file access or EPUB metadata). " +
            "Previous cached facets were kept where available; otherwise marked (Unreadable). " +
            "Check files/permissions and use Refresh filter data to retry. Full reasons are in Diagnostics → Share.\n" +
            names.joinToString("\n") { "• $it" } +
            if (failures > names.size) "\n…and ${failures - names.size} more." else ""
    }

    /** Restore derived data without source IO. Never replay a preference save at startup. */
    suspend fun restore(saved: Map<String, Set<String>>) {
        val serial = applySerial
        while (true) {
            val books = snapshot().toList()
            val expected = signature(books)
            val cached = loadCached(books)
            val currentPaths = cachedCurrentPaths?.invoke(books) ?: cached.keys
            val failedPaths = cachedUnreadablePaths(books)
            coroutineContext.ensureActive()
            if (serial != applySerial) return // An explicit Apply/Clear always wins.
            if (expected != signature(snapshot())) continue
            val paths = books.mapTo(hashSetOf(), identity)
            records = cached.filter { (path, record) -> path in paths && record.path == path }
            indexedBooks = books.filter { identity(it) in records }
            recordRevisions = books.filter { identity(it) in records && identity(it) in currentPaths }
                .associate { identity(it) to signature(listOf(it)) }
            cacheRestored = true
            if (manualRefreshOnly) publishedInvalidation = invalidation
            unreadablePaths = failedPaths.intersect(paths)
            failures = unreadablePaths.size
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

    /** Ordinary manual preview is cache-only. Forced preview owns a cancellable fresh reread. */
    suspend fun prepare(force: Boolean = false) {
        if (manualRefreshOnly && !force) { reconcileMembership(); return }
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
                var refresh = if (manualRefreshOnly) beginRefresh?.invoke() else null
                var retrying = false
                while (!isFresh() || validation > publishedValidation) {
                    if (retrying) refresh = refresh?.restart()
                    retrying = true
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
                            onProgress(if (manualRefreshOnly) "Refreshing filter data ${index + 1}/${books.size}…"
                                else if (indexedSignature != null) "Checking for changes ${index + 1}/${books.size}…"
                                else "Preparing library ${index + 1}/${books.size}…")
                        val path = identity(book)
                        val force = version > publishedInvalidation && version > 0L
                        val oldBook = previousBooks[path]
                        val reusable = !force && !checkSources && path !in unreadablePaths && oldBook != null &&
                            signature(listOf(oldBook)) == signature(listOf(book))
                        val prior = records[path]?.takeIf { reusable }
                        val (record, readable) = if (refresh != null) refresh.read(book)
                            else if (prior != null) prior to true
                            else if (force) readFresh(book) else read(book)
                        next[record.path] = record
                        if (!readable) { errors++; failed.add(record.path) }
                    }
                    ensureActive()
                    val isCurrent = { owner == token && expected == signature(snapshot()) && version == invalidation }
                    if (!isCurrent()) continue
                    val publish = {
                        records = next.toMap()
                        indexedBooks = books
                        failures = errors
                        unreadablePaths = failed
                        indexedSignature = expected
                        recordRevisions = books.associate { identity(it) to signature(listOf(it)) }
                        cacheRestored = true
                        publishedInvalidation = version
                        publishedValidation = validating
                        // A library mutation AFTER commit acceptance is a later operation:
                        // retain the accepted facets for surviving identities, never resurrect
                        // deleted rows or mark edited/new revisions current.
                        if (manualRefreshOnly) reconcileMembership() else onChanged()
                    }
                    if (refresh != null) refresh.commit(isCurrent, publish) else publish()
                }
            } finally {
                if (owner == token) onProgress("")
            }
        }
        scan = task
        task.start()
        return task
    }

    /** Manual Apply only persists choices against the cached snapshot. Newer Apply/Clear wins. */
    fun apply(requested: Map<String, Set<String>>): Job {
        val draft = requested.filter { it.key in LibraryFilters.categories && it.value.isNotEmpty() }
            .mapValues { it.value.toSet() }
        val serial = ++applySerial
        applyJob?.cancel()
        val task = scope.launch(start = CoroutineStart.LAZY) {
            if (!manualRefreshOnly) pins++
            try {
                do {
                    if (!manualRefreshOnly && draft.isNotEmpty()) awaitFresh()
                    ensureActive()
                    // Finish atomic disk saves in request order, including superseded
                    // requests; the newer Apply/Clear must be last on disk as well.
                    withContext(NonCancellable) {
                        persistenceMutex.withLock {
                            persistSelection(draft)
                            // Publish an accepted manual save even if its waiter was
                            // cancelled during disk IO; newer Apply/Clear still wins.
                            if (manualRefreshOnly && serial == applySerial) {
                                selection = draft
                                if (draft.isEmpty()) maintenance?.cancel()
                                onChanged()
                            }
                        }
                    }
                    ensureActive()
                    if (serial != applySerial) return@launch
                } while (!manualRefreshOnly && draft.isNotEmpty() && !isFresh())
                if (!manualRefreshOnly) {
                    selection = draft
                    if (draft.isEmpty()) maintenance?.cancel()
                    onChanged()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onError("Filters could not be saved/applied. Previous filters and results were kept; reopen filters to retry.")
            } finally {
                if (!manualRefreshOnly) pins--
                cancelUnneededScan()
            }
        }
        applyJob = task
        task.start()
        return task
    }

    fun booksChanged() {
        if (manualRefreshOnly) { reconcileMembership(); return }
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

    /** Membership/revision reconciliation is in memory only, never source validation. */
    private fun reconcileMembership() {
        val books = snapshot().toList()
        val paths = books.mapTo(hashSetOf(), identity)
        records = records.filterKeys { it in paths }
        recordRevisions = recordRevisions.filterKeys { it in paths }
        indexedBooks = books.filter { identity(it) in records }
        unreadablePaths = unreadablePaths.intersect(paths)
        failures = unreadablePaths.size
        onChanged()
    }

    /** Source validation owns its pin independently of applied-filter maintenance and Clear. */
    fun validateInBackground(): Job {
        if (manualRefreshOnly) return scope.launch { }
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
        if (waiters == 0 && pins == 0 && (manualRefreshOnly || selection.isEmpty())) {
            scan?.cancel()
            // Clear immediately; a cancelled LAZY scan might never execute its finally.
            onProgress("")
        }
    }
}
