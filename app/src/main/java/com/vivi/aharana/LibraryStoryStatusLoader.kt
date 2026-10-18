package com.vivi.aharana

import android.content.Context
import android.util.LruCache
import com.vivi.aharana.model.BookItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Bounded visible-row cache. Reads local EPUB metadata, never a story site or Room writes. */
internal class LibraryStoryStatusLoader(context: Context) {
    private val context = context.applicationContext
    private val cache = LruCache<String, String>(256)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val facetLoader by lazy { LibraryFacetLoader.shared(this.context) }

    // A new library snapshot invalidates SAF entries too (provider mtime may be unavailable).
    fun invalidate() { generation.incrementAndGet(); cache.evictAll() }

    suspend fun load(book: BookItem): String = withContext(Dispatchers.IO) {
        val epoch = generation.get()
        val file = File(book.uriString)
        val fingerprint = if (book.uriString.startsWith("content://")) "document"
            else "${file.length()}:${file.lastModified()}"
        val key = "$epoch:${book.uriString}:${book.lastModified}:${book.sizeBytes}:$fingerprint"
        mutex.withLock {
            coroutineContext.ensureActive()
            cache.get(key)?.let { return@withLock it }
            val (record, readable) = facetLoader.read(book)
            coroutineContext.ensureActive()
            check(readable) { "Cannot read saved story status" }
            val status = record.facets["status"]?.firstOrNull().orEmpty()
            if (epoch == generation.get()) cache.put(key, status)
            status
        }
    }
}
