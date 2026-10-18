package com.vivi.aharana

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Cache policy independent of Android. Callbacks provide a bulk SQLite store and source IO. */
internal class LibraryFacetIndex<T>(
    private val identity: (T) -> String,
    private val bookSignature: (T) -> String,
    private val fileSignature: (T) -> String,
    private val loadAll: () -> Map<String, Entry>,
    private val save: (Entry) -> Unit,
    private val delete: (String) -> Unit,
    private val extract: suspend (T) -> Pair<LibraryFacetRecord, Boolean>
) {
    data class Entry(val bookSignature: String, val fileSignature: String, val record: LibraryFacetRecord)
    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null

    private fun loaded(): MutableMap<String, Entry> = entries ?: loadAll().toMutableMap().also { entries = it }

    /** Startup reads only the bulk index and saved library revisions; no source stat or open. */
    suspend fun cached(books: List<T>): Map<String, LibraryFacetRecord> = mutex.withLock {
        val all = loaded()
        books.mapNotNull { book ->
            val path = identity(book)
            all[path]?.takeIf { it.bookSignature == bookSignature(book) && it.record.path == path }
                ?.let { path to it.record }
        }.toMap()
    }

    suspend fun read(book: T, force: Boolean = false): Pair<LibraryFacetRecord, Boolean> = mutex.withLock {
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
