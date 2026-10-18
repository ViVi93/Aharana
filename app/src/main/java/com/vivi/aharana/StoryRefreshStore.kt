package com.vivi.aharana

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.vivi.aharana.data.local.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** File/SAF persistence for explicit story refresh, always off the main thread and inside engine gate. */
internal class StoryRefreshStore(context: Context, private val bridge: PythonBridge) {
    private val context = context.applicationContext
    private val database = AppDatabase.getInstance(this.context)

    suspend fun apply(originalPath: String, preview: JSONObject, selected: Set<String>, cover: Boolean): JSONObject {
        val dao = database.bookDao()
        val original = dao.findByFilePath(originalPath)
            ?: error("Book is no longer in the library. Reload the library before applying.")
        val selectedJson = JSONArray(selected.sorted()).toString()
        val isDocument = originalPath.startsWith("content://")
        val workspace = File.createTempFile("story_refresh_", ".work", context.cacheDir).apply {
            check(delete() && mkdir()) { "Cannot create refresh workspace" }
        }
        try {
            val resolved = StorageBridge.resolveLocalEpub(context, originalPath) ?: error("Cannot read this EPUB")
            val (local, temporary) = resolved
            try {
                val out = if (isDocument) File(workspace, "refreshed.epub").absolutePath else null
                val result = checked(bridge.applyStoryRefresh(local.absolutePath, preview.toString(), selectedJson, cover, out))
                check(result.optBoolean("chapters_preserved")) { "Chapter preservation was not verified" }
                val generated = File(result.getString("output_path"))
                check(generated.isFile && generated.length() > 0) { "Refresh output is missing" }
                val savedMetadata = result.getJSONObject("metadata")
                val changedArray = result.getJSONArray("changed_fields")
                val changedFields = (0 until changedArray.length()).map { changedArray.getString(it) }.toSet()
                check(selected.containsAll(changedFields)) { "Saved fields do not match your selection" }
                val authors = savedMetadata.optJSONArray("authors") ?: JSONArray()
                val savedAuthors = (0 until authors.length()).map { authors.getString(it) }
                // The legacy metadata extractor may fall back to an OLD resource. Follow
                // the declared OPF cover via upstream get_cover_img instead.
                val verifiedCover = if (cover) checked(bridge.getCoverFromEpub(generated.absolutePath))
                    .getString("cover").also { check(it.startsWith("data:image/")) { "Saved cover cannot be verified" } }
                    else null
                val finalPath = if (isDocument) {
                    val name = DocumentFile.fromSingleUri(context, Uri.parse(originalPath))?.name
                        ?: "${original.title}.epub"
                    saveNewCopy(generated, SettingsActivity.getOutputDir(context), name)
                } else generated.absolutePath
                val backupPath = if (isDocument) originalPath else result.optString("backup_path")
                // Persist exactly the existing row, including its ID and addedAt. No library clear/reinsert.
                try {
                    database.withTransaction {
                        val current = dao.findById(original.id) ?: error("Library row was removed during refresh")
                        check(current.filePath == originalPath) { "Library book changed location during refresh" }
                        val libraryFields = StoryRefreshLibraryMetadata.merge(
                            current.title, current.author, changedFields,
                            savedMetadata.optString("title"), savedAuthors)
                        dao.update(current.copy(
                            title = libraryFields.title,
                            author = libraryFields.author,
                            filePath = finalPath,
                            sourcePath = if (isDocument) originalPath else current.sourcePath,
                            lastModified = System.currentTimeMillis(),
                            sizeBytes = generated.length(),
                            coverData = verifiedCover ?: current.coverData
                            // Preserve source URL, local chapter count, addedAt and all other library fields.
                        ))
                    }
                } catch (e: Exception) {
                    error("EPUB saved to $finalPath, but library update failed. Original/backup: $backupPath. Reload the library. ${e.message}")
                }
                return JSONObject(result.toString()).put("output_path", finalPath).put("backup_path", backupPath)
                    .put("notice", if (isDocument) "Saved a refreshed copy. Original document kept unchanged; chapters and title page unchanged."
                        else "Saved with an original backup. Chapters and title page unchanged.")
            } finally {
                if (temporary) {
                    local.delete()
                    // Backups of a temporary SAF input are redundant: the original document was never changed.
                    File(local.absolutePath + ".story_refresh_bak").delete()
                }
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun checked(raw: String): JSONObject = JSONObject(raw).also {
        check(it.optBoolean("ok")) { it.optString("error", "Refresh failed") }
    }

    /** Never overwrite an unrelated output book. Read back the exact destination before success. */
    private fun saveNewCopy(source: File, directory: String, originalName: String): String {
        val base = originalName.substringBeforeLast('.', originalName)
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(100).ifBlank { "Story" }
        val unique = java.util.UUID.randomUUID().toString().take(8)
        val name = "$base (refreshed-$unique).epub"
        if (directory.startsWith("content://")) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(directory)) ?: error("Invalid output folder")
            val document = tree.createFile("application/epub+zip", name) ?: error("Cannot create refreshed EPUB")
            try {
                context.contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                    source.inputStream().use { it.copyTo(output) }
                } ?: error("Cannot write refreshed EPUB")
                context.contentResolver.openInputStream(document.uri)?.use { StoryRefreshFiles.verifyCopy(source, it) }
                    ?: error("Cannot verify saved EPUB")
                return document.uri.toString()
            } catch (e: Exception) {
                document.delete()
                throw e
            }
        }
        return StoryRefreshFiles.saveNewCopy(source, File(directory), name).absolutePath
    }
}
