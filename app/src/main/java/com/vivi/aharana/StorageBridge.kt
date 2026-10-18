package com.vivi.aharana

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

object StorageBridge {

    fun resolveLocalEpub(context: Context, path: String): Pair<File, Boolean>? {
        return if (path.startsWith("content://")) {
            val temp = copyContentUriToTemp(context, Uri.parse(path))
            if (temp != null) Pair(temp, true) else null
        } else {
            val file = File(path)
            if (file.exists() && file.isFile) Pair(file, false) else null
        }
    }

    inline fun <T> withLocalEpub(context: Context, path: String, block: (File) -> T): T? {
        val resolved = resolveLocalEpub(context, path) ?: return null
        val (file, isTemp) = resolved
        return try {
            block(file)
        } finally {
            if (isTemp) {
                file.delete()
            }
        }
    }

    fun deleteEpub(context: Context, path: String): Boolean {
        return try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                val resolver = context.contentResolver
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    // SAF document providers require deleteDocument, not a second delete
                    // after resolver deletion. DocumentFile supports our full API range.
                    DocumentFile.fromSingleUri(context, uri)?.delete() == true
                } else {
                    // This overload is available before API 30 as well as on Android 17.
                    resolver.delete(uri, null, null) > 0
                }
            } else {
                File(path).delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun copyToOutputDir(context: Context, sourceFile: File, outputDir: String): String {
        return if (outputDir.startsWith("content://")) {
            val treeUri = Uri.parse(outputDir)
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IOException("Invalid output directory")
            val mimeType = "application/epub+zip"
            val newFile = treeDoc.createFile(mimeType, sourceFile.name)
                ?: throw IOException("Cannot create file in output directory")
            context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Cannot open output stream")
            newFile.uri.toString()
        } else {
            val destDir = File(outputDir)
            if (!destDir.exists()) destDir.mkdirs()
            val outFile = File(destDir, sourceFile.name)
            sourceFile.copyTo(outFile, overwrite = true)
            outFile.absolutePath
        }
    }

    private fun copyContentUriToTemp(context: Context, uri: Uri): File? {
        val resolver = context.contentResolver
        val fileName = queryDisplayName(resolver, uri) ?: "imported_${System.currentTimeMillis()}.epub"
        val tempFile = File(context.filesDir, "tmp_${System.currentTimeMillis()}_$fileName")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            tempFile.delete()
            null
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        return try {
            val cursor = resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
