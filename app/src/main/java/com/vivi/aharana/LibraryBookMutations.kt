package com.vivi.aharana

import android.content.Context
import androidx.room.withTransaction
import com.vivi.aharana.data.local.AppDatabase
import com.vivi.aharana.data.local.BookEntity
import org.json.JSONObject

/** Android boundary for pre-existing book mutations. Never writes a delayed Activity snapshot. */
internal object LibraryBookMutations {
    suspend fun <T> withPaths(context: Context, paths: List<String>, block: suspend (List<String>) -> T): T =
        LibraryMutationCoordinator.run(paths, {
            val rows = BookRepository.reconcileOrganisedPaths(context).optJSONArray("moves")
            buildMap {
                if (rows != null) for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    put(row.getString("source"), row.getString("target"))
                }
            }
        }, block)

    suspend fun <T> run(context: Context, path: String, expectedBookId: Long = 0, block: suspend (Mutation) -> T): T =
        withPaths(context, listOf(path)) { paths ->
            val database = AppDatabase.getInstance(context.applicationContext)
            val book = if (expectedBookId > 0) database.bookDao().findById(expectedBookId)
                else database.bookDao().findByFilePath(paths.single())
            requireExpectedBookIdentity(expectedBookId, book?.id)
            checkNotNull(book) { "Book is no longer in the library. Reload the library before applying." }
            check(book.filePath == paths.single()) { "Book changed location. Reload the library before applying." }
            block(Mutation(context.applicationContext, book))
        }

    /** Only for existing background Thread entry points, never the UI thread. */
    fun <T> runBlocking(context: Context, path: String, expectedBookId: Long = 0, block: suspend (Mutation) -> T): T =
        kotlinx.coroutines.runBlocking { run(context, path, expectedBookId, block) }

    class Mutation internal constructor(private val context: Context, private val original: BookEntity) {
        val path: String get() = original.filePath

        suspend fun persist(finalPath: String = path) {
            val bridge = PythonBridge(context)
            val metadata = StorageBridge.withLocalEpub(context, finalPath) { local ->
                val read = JSONObject(bridge.readEpubMetadata(local.absolutePath))
                check(read.optBoolean("ok")) { read.optString("error", "Cannot read saved metadata") }
                JSONObject(bridge.epubMetadataJson(local.absolutePath))
            } ?: error("Cannot read saved EPUB")
            val database = AppDatabase.getInstance(context)
            database.withTransaction {
                val current = database.bookDao().findById(original.id) ?: error("Library row was removed")
                check(current.filePath == path) { "Library book changed location" }
                database.bookDao().update(current.copy(
                    filePath = finalPath,
                    sourcePath = if (finalPath != path) path else current.sourcePath,
                    title = metadata.optString("title", current.title),
                    author = metadata.optString("author", current.author),
                    lastModified = metadata.optLong("modified", System.currentTimeMillis()),
                    sizeBytes = metadata.optLong("size", current.sizeBytes),
                    coverData = metadata.optString("cover").ifBlank { current.coverData }
                ))
            }
        }

        suspend fun delete() {
            val bridge = PythonBridge(context)
            var deletePath = path
            val token = if (!path.startsWith("content://")) {
                val request = JSONObject().put("path", path).put("book_id", original.id)
                val prepared = JSONObject(bridge.organiseLibrary("delete_prepare", request.toString()))
                check(prepared.optBoolean("ok")) { prepared.optString("error", "Cannot prepare safe deletion") }
                deletePath = prepared.getString("delete_path")
                prepared.getString("token")
            } else null // Picked documents never participate in filesystem organisation.
            val deleted = StorageBridge.deleteEpub(context, deletePath)
            if (token != null) {
                val finished = JSONObject(bridge.organiseLibrary("delete_finish",
                    JSONObject().put("token", token).put("deleted", deleted).toString()))
                check(finished.optBoolean("ok")) { finished.optString("error", "Deletion journal needs recovery") }
            }
            check(deleted) { "Delete failed; original undo record retained" }
            val database = AppDatabase.getInstance(context)
            database.withTransaction {
                val current = database.bookDao().findById(original.id) ?: return@withTransaction
                check(current.filePath == path) { "Library book changed location" }
                database.bookDao().delete(current)
            }
        }
    }
}
