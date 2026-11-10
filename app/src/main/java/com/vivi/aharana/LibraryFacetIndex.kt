package com.vivi.aharana

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** A private builder; commit invokes publication on the caller dispatcher after durable success. */
internal interface LibraryFacetRefresh<T> {
    fun restart(): LibraryFacetRefresh<T>
    suspend fun read(book: T): Pair<LibraryFacetRecord, Boolean>
    suspend fun commit(isCurrent: () -> Boolean, publish: () -> Unit): Boolean
}

/** Cache policy independent of Android. Callbacks provide a bulk SQLite store and source IO. */
internal class LibraryFacetIndex<T>(
    private val identity: (T) -> String,
    private val bookSignature: (T) -> String,
    private val fileSignature: (T) -> String,
    private val loadAll: () -> Map<String, Entry>,
    private val save: (Entry) -> Unit,
    private val delete: (String) -> Unit,
    private val extract: suspend (T) -> Pair<LibraryFacetRecord, Boolean>,
    private val replaceSnapshot: (Collection<Entry>) -> Unit = { error("Atomic snapshot store required") },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
) {
    private companion object {
        // Reserved cache revision namespace: readable production revisions start with facet-v2.
        const val UNREADABLE_REVISION = "unreadable-facet-v1:"
    }
    data class Entry(val bookSignature: String, val fileSignature: String, val record: LibraryFacetRecord)
    private data class Staged<T>(val book: T, val revision: String, val fingerprint: String, val entry: Entry)
    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null
    private val refreshGeneration = AtomicLong()

    /** New builders supersede uncommitted older builders, including those owned by another VM. */
    fun beginRefresh(): LibraryFacetRefresh<T> = refresh(refreshGeneration.incrementAndGet())

    private fun refresh(generation: Long): LibraryFacetRefresh<T> {
        return object : LibraryFacetRefresh<T> {
            private val staged = linkedMapOf<String, Staged<T>>()
            override fun restart(): LibraryFacetRefresh<T> {
                if (generation != refreshGeneration.get()) throw CancellationException("Filter refresh superseded")
                // Retry the current library snapshot without claiming a new owner generation.
                return refresh(generation)
            }
            override suspend fun read(book: T): Pair<LibraryFacetRecord, Boolean> = withContext(ioDispatcher) {
                mutex.withLock {
                    coroutineContext.ensureActive()
                    val path = identity(book)
                    val revision = bookSignature(book)
                    val before = fileSignature(book)
                    val result = extract(book)
                    coroutineContext.ensureActive()
                    check(result.first.path == path) { "Metadata identity changed during read" }
                    check(before == fileSignature(book)) { "Book changed while metadata was being read; retry refresh" }
                    // A known per-book read failure is not a failed scan. Keep its prior
                    // facets (or the explicit unreadable fallback) and persist that it is
                    // stale, without advancing any durable entry until the whole commit.
                    val record = if (result.second) result.first else loaded()[path]?.record ?: result.first
                    val storedRevision = if (result.second) revision else UNREADABLE_REVISION + revision
                    staged[path] = Staged(book, revision, before, Entry(storedRevision, before, record))
                    record to result.second
                }
            }

            override suspend fun commit(isCurrent: () -> Boolean, publish: () -> Unit): Boolean = mutex.withLock {
                coroutineContext.ensureActive()
                if (generation != refreshGeneration.get()) throw CancellationException("Filter refresh superseded")
                // Recheck ALL sources, not just B: A may have changed while B was read.
                val unchanged = withContext(ioDispatcher) {
                    staged.values.all { (book, revision, fingerprint, _) ->
                        revision == bookSignature(book) && fingerprint == fileSignature(book)
                    }
                }
                coroutineContext.ensureActive()
                check(unchanged) { "Books changed before filter snapshot commit; retry refresh" }
                if (generation != refreshGeneration.get()) throw CancellationException("Filter refresh superseded")
                if (!isCurrent()) return@withLock false
                // COMMIT POINT: accepted on the owner dispatcher. Cancellation after this
                // point cannot abandon disk success before memory publication. The mutex
                // orders commits/prunes; later builders can never be overwritten by this one.
                withContext(NonCancellable) {
                    val next = staged.mapValues { it.value.entry }
                    withContext(ioDispatcher) { replaceSnapshot(next.values) }
                    entries = next.toMutableMap() // Only after the transaction commits.
                    publish() // Still on the owner dispatcher, even if its Job was cancelled.
                }
                true
            }
        }
    }

    private fun loaded(): MutableMap<String, Entry> = entries ?: loadAll().toMutableMap().also { entries = it }

    /** Persisted failures are stale but usable, even after process recreation; never stat sources here. */
    suspend fun unreadable(books: List<T>): Set<String> = mutex.withLock {
        val all = loaded()
        books.mapNotNull { book -> identity(book).takeIf { path ->
            all[path]?.bookSignature?.startsWith(UNREADABLE_REVISION) == true
        } }.toSet()
    }

    /** No source stat/open. Stale records are usable snapshots, not a freshness guarantee. */
    suspend fun cached(books: List<T>, allowStale: Boolean = false): Map<String, LibraryFacetRecord> = mutex.withLock {
        val all = loaded()
        books.mapNotNull { book ->
            val path = identity(book)
            all[path]?.takeIf { (allowStale || it.bookSignature == bookSignature(book)) && it.record.path == path }
                ?.let { path to it.record }
        }.toMap()
    }

    suspend fun read(book: T, force: Boolean = false, cacheResult: Boolean = true): Pair<LibraryFacetRecord, Boolean> = mutex.withLock {
        coroutineContext.ensureActive()
        val all = loaded()
        val path = identity(book)
        val revision = bookSignature(book)
        val before = fileSignature(book)
        if (!force) all[path]?.takeIf { it.bookSignature == revision && it.fileSignature == before }
            ?.let { return@withLock it.record to true }
        val result = extract(book)
        coroutineContext.ensureActive()
        check(result.first.path == path) { "Metadata identity changed during read" }
        check(before == fileSignature(book)) { "Book changed while metadata was being read; retry refresh" }
        if (!cacheResult) return@withLock result // Visible-row reads cannot mutate manual filter snapshots.
        if (result.second) {
            val entry = Entry(revision, before, result.first)
            // The store callback surfaces/logs failures. Do not mark an unsuccessful save durable.
            save(entry)
            all[path] = entry
        } else {
            delete(path)
            all.remove(path) // Never retain an old successful entry across an observed read failure.
        }
        result
    }

    suspend fun invalidate(path: String) = mutex.withLock {
        loaded().remove(path)
        delete(path)
    }

    suspend fun prune(paths: Set<String>) = prune { paths }

    suspend fun prune(currentPaths: () -> Set<String>) = mutex.withLock {
        val all = loaded()
        val removed = all.keys.filter { it !in currentPaths() }
        removed.forEach {
            coroutineContext.ensureActive()
            if (it !in currentPaths()) { delete(it); all.remove(it) }
        }
    }
}
