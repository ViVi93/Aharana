package com.vivi.aharana

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Both book entry points use the same URI permissions and reader/error behavior. */
internal object EpubOpener {
    fun open(activity: Activity, path: String, onError: (String) -> Unit) {
        try {
            if (path.isBlank()) {
                onError("Book file not found. Reload the library to check its location.")
                return
            }
            val document = path.startsWith("content://")
            val uri = if (document) Uri.parse(path) else {
                val file = File(path)
                if (!file.isFile) {
                    onError("Book file not found. Reload the library to check its location.")
                    return
                }
                FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
            }
            val mime = if (document) activity.contentResolver.getType(uri) ?: "application/epub+zip"
                else "application/epub+zip"
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            onError("No EPUB reader installed")
        } catch (_: Exception) {
            onError("Could not open this book. Check that the file is accessible and folder access is granted.")
        }
    }
}
