package com.vivi.aharana

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Filesystem-only persistence for an existing book; never routes it via the output root. */
internal object LibraryEpubStorage {
    /** Existing picked documents keep their original; only filesystem books replace in place. */
    fun saveExisting(source: File, path: String, replaceFile: (File, String) -> String,
                     saveDocumentCopy: (File) -> String): String =
        if (path.startsWith("content://")) saveDocumentCopy(source) else replaceFile(source, path)

    fun existingPath(canonicalPath: String?, queuedPath: String): String =
        canonicalPath?.takeIf { it.isNotBlank() } ?: queuedPath

    fun sourceAfterReplacement(oldPath: String, sourcePath: String?, finalPath: String): String =
        if (sourcePath.isNullOrBlank() || sourcePath == oldPath) finalPath else sourcePath

    fun copyDownload(
        source: File,
        root: String,
        organise: Boolean,
        legacyCopy: (File, String) -> String,
        organisedCopy: (File, String) -> String
    ): String {
        if (!organise) return legacyCopy(source, root)
        if (root.startsWith("content://")) throw IOException(
            "Author folders need a filesystem output path. Enter a folder path in Settings or turn off Organise future downloads."
        )
        return organisedCopy(source, root)
    }

    fun replace(source: File, existing: File, stageCopy: (File, File) -> Unit = ::copyAndSync): String {
        if (!source.isFile || source.length() == 0L) throw IOException("Generated EPUB is missing or empty: ${source.path}")
        if (!existing.isFile) throw IOException("Existing EPUB is missing: ${existing.path}. Refresh the library and retry.")
        if (source.canonicalFile == existing.canonicalFile) return existing.absolutePath
        val staging = File.createTempFile(".aharana-update-", ".epub", existing.absoluteFile.parentFile)
        try {
            val expectedSize = source.length()
            stageCopy(source, staging)
            if (staging.length() != expectedSize) throw IOException("Incomplete staged EPUB; the original was kept")
            // Android/Linux rename replaces atomically within the same directory, including on
            // API 24/25 where java.nio.file isn't available. Never delete the old book first.
            if (!staging.renameTo(existing)) throw IOException("Cannot replace EPUB at ${existing.path}; the original was kept")
            return existing.absolutePath
        } finally {
            staging.delete()
        }
    }

    private fun copyAndSync(source: File, staging: File) {
        source.inputStream().use { input ->
            FileOutputStream(staging).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }
}
