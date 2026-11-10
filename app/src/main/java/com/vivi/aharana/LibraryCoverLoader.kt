package com.vivi.aharana

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Base64InputStream
import com.vivi.aharana.data.local.AppDatabase
import com.vivi.aharana.model.BookItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** One process cache and decoder, not one full payload/bitmap per library row. */
class LibraryCoverLoader private constructor(context: Context) {
    private val bookDao = AppDatabase.getInstance(context.applicationContext).bookDao()
    private val decodeMutex = Mutex()
    private val revision = AtomicLong()
    private data class Key(val id: Long, val path: String, val modified: Long, val size: Long, val revision: Long)
    private val cache = CoverMemoryCache<Key, Bitmap>(8L * 1024 * 1024) { it.allocationByteCount.toLong() }

    companion object {
        private val instance = ProcessValue<LibraryCoverLoader>()
        fun getInstance(context: Context): LibraryCoverLoader =
            instance.get { LibraryCoverLoader(context.applicationContext) }
    }

    fun invalidate() {
        revision.incrementAndGet()
        cache.clear()
    }

    suspend fun load(book: BookItem): Bitmap? = withContext(Dispatchers.IO) {
        val key = Key(book.libraryId, book.uriString, book.lastModified, book.sizeBytes, revision.get())
        cache.get(key)?.let { return@withContext it }
        decodeMutex.withLock {
            currentCoroutineContext().ensureActive()
            cache.get(key)?.let { return@withLock it }
            // Full cover materialization is deliberately single-row and serialized.
            val cover = book.coverUriString?.takeIf { it.isNotBlank() } ?: if (book.libraryId > 0) {
                bookDao.getCoverData(book.libraryId)
            } else {
                bookDao.getCoverDataByPath(book.uriString)
            } ?: return@withLock null
            currentCoroutineContext().ensureActive()
            val bitmap = decode(cover) ?: return@withLock null
            currentCoroutineContext().ensureActive()
            // An invalidation while decoding must not repopulate the new cache generation.
            if (revision.get() == key.revision) cache.put(key, bitmap)
            bitmap
        }
    }

    private fun decode(cover: String): Bitmap? {
        if (!cover.startsWith("data:")) return null
        val comma = cover.indexOf(',')
        if (comma < 0) return null
        fun stream() = Base64InputStream(AsciiSliceInputStream(cover, comma + 1), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = CoverDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight, 320)
            inScaled = false
        }
        return stream().use { BitmapFactory.decodeStream(it, null, options) }
    }
}
