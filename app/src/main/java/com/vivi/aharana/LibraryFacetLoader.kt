package com.vivi.aharana

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.vivi.aharana.model.BookItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/** Durable per-book offline metadata cache. Shared by filter scans and visible status rows. */
internal class LibraryFacetLoader private constructor(context: Context) {
    companion object {
        @Volatile private var instance: LibraryFacetLoader? = null
        fun shared(context: Context): LibraryFacetLoader = instance ?: synchronized(this) {
            instance ?: LibraryFacetLoader(context.applicationContext).also { instance = it }
        }
        fun bookSignature(book: BookItem): String = listOf("facet-v2", book.uriString,
            book.lastModified.toString(), book.sizeBytes.toString(), book.title, book.author,
            book.chapters.toString(), book.url).joinToString("") { "${it.length}:$it" }
    }
    private val context = context.applicationContext
    private val bridge by lazy { PythonBridge(this.context) }
    private val store by lazy { LibraryFacetDiskStore(File(this.context.filesDir, "library_facets_v1")) }
    private val database by lazy { LibraryFacetIndexDatabase(this.context) }
    private val mutex = Mutex()
    private val index by lazy {
        LibraryFacetIndex<BookItem>(identity = { it.uriString }, bookSignature = ::bookSignature,
            fileSignature = ::fingerprint,
            loadAll = {
                try { database.readAll().mapValues { (_, entry) ->
                    LibraryFacetIndex.Entry(entry.bookSignature, entry.fileSignature, entry.record)
                } } catch (e: Exception) {
                    DiagnosticLog.appendException(this.context, "LibraryFacetCache", "bulk_read_failed", e)
                    emptyMap()
                }
            }, save = { entry ->
                try { database.upsert(LibraryFacetIndexEntry(entry.bookSignature, entry.fileSignature, entry.record)) }
                catch (e: Exception) { DiagnosticLog.appendException(this.context, "LibraryFacetCache", "index_write_failed", e) }
            }, delete = { path ->
                try { database.invalidate(path) }
                catch (e: Exception) { DiagnosticLog.appendException(this.context, "LibraryFacetCache", "index_delete_failed", e) }
            }, extract = ::extract)
    }

    /** One SQLite bulk read per process; no per-book source opens/stats on the startup path. */
    suspend fun cached(books: List<BookItem>): Map<String, LibraryFacetRecord> = withContext(Dispatchers.IO) {
        index.cached(books)
    }

    @Volatile private var membership: Set<String> = emptySet()
    fun setMembership(books: List<BookItem>) { membership = books.mapTo(hashSetOf()) { it.uriString } }

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        index.prune { membership }
    }

    suspend fun readSelection(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        mutex.withLock { store.readSelection().filterKeys { it in LibraryFilters.categories } }
    }

    suspend fun saveSelection(selection: Map<String, Set<String>>) {
        // Accepted preference writes finish in order even if a newer Apply cancels its waiter.
        withContext(NonCancellable + Dispatchers.IO) { mutex.withLock { store.writeSelection(selection) } }
    }

    private fun fingerprint(book: BookItem): String {
        if (book.uriString.startsWith("content://")) {
            // Providers may omit mtime; explicit Re-read remains available in that case.
            return try {
                context.contentResolver.query(Uri.parse(book.uriString), arrayOf(OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) "document:missing" else {
                        val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val modified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        "document:${if (size >= 0) cursor.getString(size) else "unknown"}:${if (modified >= 0) cursor.getString(modified) else "unknown"}"
                    }
                } ?: "document:unknown"
            } catch (_: Exception) { "document:unknown" }
        }
        val file = File(book.uriString)
        return "${file.exists()}:${file.length()}:${file.lastModified()}"
    }

    suspend fun read(book: BookItem, force: Boolean = false): Pair<LibraryFacetRecord, Boolean> =
        withContext(Dispatchers.IO) {
            index.read(book, force)
        }

    private suspend fun extract(book: BookItem): Pair<LibraryFacetRecord, Boolean> {
        val base = mutableMapOf("authors" to setOf(book.author).filter { it.isNotBlank() }.toSet(),
            "chapters" to setOf(LibraryFilters.chapterBucket(book.chapters)))
        try {
            val raw = StorageBridge.withLocalEpub(context, book.uriString) {
                bridge.getLibraryFacets(it.absolutePath)
            } ?: error("Unreadable EPUB")
            coroutineContext.ensureActive()
            val meta = JSONObject(raw)
            check(meta.optBoolean("ok"))
            for (key in listOf("authors", "languages", "series", "tags")) {
                val array = meta.optJSONArray(key) ?: JSONArray()
                val values = (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotBlank() }.toSet()
                if (values.isNotEmpty() || key != "authors") base[key] = values
            }
            base["status"] = setOf(LibraryStoryStatus.label(meta.optString("status")))
            base["site"] = setOf(meta.optString("site")).filter { it.isNotBlank() }.toSet()
            return LibraryFacetRecord(book.uriString, base.toMap()) to true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            for (key in listOf("status", "site", "languages", "series", "tags")) base[key] = setOf("(Unreadable)")
            return LibraryFacetRecord(book.uriString, base.toMap()) to false
        }
    }
}
