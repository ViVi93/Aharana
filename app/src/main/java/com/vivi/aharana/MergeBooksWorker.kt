package com.vivi.aharana

import android.content.Context
import androidx.room.withTransaction
import com.vivi.aharana.data.local.AppDatabase
import com.vivi.aharana.data.local.toEntity
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vivi.aharana.model.BookItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Merges several EPUBs into one new book, off the main thread.
 *
 * The Python side opens every source read-only and writes the merged book beside
 * the first source, so nothing is lost if the job is killed. On success the new
 * row is upserted into the library here rather than by the calling screen, so the
 * book still appears even if the user navigates away mid-merge. Only a single row
 * is ever written -- see registerMergedBook for why that constraint matters.
 */
class MergeBooksWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_PATHS = "paths"
        const val KEY_TITLE = "title"
        const val KEY_AUTHOR = "author"
        const val KEY_BASE_INDEX = "baseIndex"
        const val KEY_OUTPUT = "outputPath"
        const val KEY_COVER = "cover"
        const val KEY_TOC_STYLE = "tocStyle"
        const val KEY_SHORTEN_LABELS = "shortenLabels"
        const val KEY_RENUMBER_CHAPTERS = "renumberChapters"
        const val KEY_ERROR = "error"
        const val KEY_MESSAGE = "message"
        const val UNIQUE_WORK_PREFIX = "merge_books_"
    }

    override suspend fun doWork(): Result {
        val paths = inputData.getStringArray(KEY_PATHS)?.toList().orEmpty()
        if (paths.size < 2) {
            return Result.failure(workDataOf(KEY_ERROR to "Select at least two books"))
        }
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val author = inputData.getString(KEY_AUTHOR).orEmpty()
        val baseIndex = inputData.getInt(KEY_BASE_INDEX, 0)
        val outputPath = inputData.getString(KEY_OUTPUT)
        val cover = inputData.getString(KEY_COVER).orEmpty()
        val tocStyle = inputData.getString(KEY_TOC_STYLE) ?: "sections"
        val shortenLabels = inputData.getBoolean(KEY_SHORTEN_LABELS, false)
        val renumberChapters = inputData.getBoolean(KEY_RENUMBER_CHAPTERS, false)

        val bridge = PythonBridge(applicationContext)
        return try {
            LibraryBookMutations.withPaths(applicationContext, paths) { currentPaths ->
                val result = JSONObject(
                    bridge.mergeBooks(
                        JSONArray(currentPaths).toString(), outputPath, title, author, baseIndex,
                        tocStyle, shortenLabels, renumberChapters
                    )
                )
                if (!result.optBoolean("ok", false)) {
                    return@withPaths Result.failure(
                        workDataOf(KEY_ERROR to result.optString("error", "Merge failed"))
                    )
                }
                val mergedPath = result.optString("output_path", "")
                val registered = registerMergedBook(mergedPath, cover)
                Result.success(
                    workDataOf(
                        KEY_OUTPUT to mergedPath,
                        KEY_MESSAGE to if (registered) {
                            "Merged ${paths.size} books into one"
                        } else {
                            // The file is on disk; only the library entry failed.
                            "Merged into one book, but it could not be added to the library"
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    /**
     * Add the merged file to the library.
     *
     * Metadata is read back from the file itself; the cover is inherited from the
     * first source, because the merged book keeps that source's cover.
     */
    private suspend fun registerMergedBook(path: String, cover: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        if (!file.exists()) return false

        val meta = try {
            JSONObject(PythonBridge(applicationContext).epubMetadataJson(path))
        } catch (e: Exception) {
            JSONObject()
        }
        val book = BookItem(
            title = meta.optString("title", file.nameWithoutExtension),
            author = meta.optString("author", ""),
            uriString = path,
            lastModified = file.lastModified(),
            sizeBytes = file.length(),
            coverUriString = cover.ifBlank { meta.optString("cover", "") }.ifBlank { null },
            url = meta.optString("url", ""),
            chapters = meta.optInt("chapters", 0)
        )
        // Only ever upsert this one row. A worker's BookRepository is a fresh
        // instance whose in-memory list is empty, so addOrUpdate() would throw
        // (LiveData.setValue off the main thread) and saveLibrary() would be far
        // worse: it clears the table and re-inserts that empty list, wiping the
        // whole library.
        return try {
            val database = AppDatabase.getInstance(applicationContext)
            database.withTransaction {
                val dao = database.bookDao()
                val existing = dao.findByFilePath(path)
                val entity = book.toEntity()
                if (existing == null) dao.insert(entity)
                else dao.update(entity.copy(id = existing.id, addedAt = existing.addedAt, sourcePath = existing.sourcePath))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}