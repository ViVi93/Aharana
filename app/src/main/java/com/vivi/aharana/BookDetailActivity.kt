package com.vivi.aharana

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class BookDetailActivity : BaseActivity() {

    companion object {
        private const val TAG = "BookDetailDiag"
        const val EDIT_METADATA_REQUEST = 1001
        const val REPLACE_COVER_REQUEST = 1002
        const val PREVIEW_METADATA_REQUEST = 1003
        const val STORY_REFRESH_REQUEST = 1004
    }

    private lateinit var bookTitle: String
    private lateinit var bookAuthor: String
    private lateinit var bookPath: String
    private var bookSource: String? = null
    private var bookModified: Long = 0
    private var bookSize: Long = 0
    private var bookChapters: Int = 0
    private var bookCover: String? = null
    private lateinit var bookUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_detail)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }
        applyTopInset(findViewById<android.view.View>(R.id.appBar))
        applyBottomInset(findViewById<android.view.View>(R.id.rootScroll))
        Log.d(TAG, "BOOK_DETAILS ENTER")

        bookTitle = intent.getStringExtra("title") ?: ""
        bookAuthor = intent.getStringExtra("author") ?: ""
        bookPath = intent.getStringExtra("path") ?: ""
        bookSource = intent.getStringExtra("source")
        bookModified = intent.getLongExtra("modified", 0L)
        bookSize = intent.getLongExtra("size", 0L)
        bookChapters = intent.getIntExtra("chapters", 0)
        bookUrl = intent.getStringExtra("url") ?: ""
        Log.d(TAG, "intent_extras_read")

        val coverFile = File(bookPath)
        val fileSize = if (coverFile.exists()) coverFile.length() else -1
        Log.d(
            TAG,
            "metadata_fields_assigned title_len=${bookTitle.length} author_len=${bookAuthor.length} " +
                "path=$bookPath source=${bookSource ?: "<null>"} modified=$bookModified size=$fileSize " +
                "chapters=$bookChapters url=$bookUrl"
        )

        Log.d(TAG, "layout_inflated")
        val cover = findViewById<ImageView>(R.id.imageCover)
        val title = findViewById<TextView>(R.id.textTitle)
        val author = findViewById<TextView>(R.id.textAuthor)
        val chapters = findViewById<TextView>(R.id.textChapters)
        val url = findViewById<TextView>(R.id.textUrl)
        val path = findViewById<TextView>(R.id.textPath)
        val size = findViewById<TextView>(R.id.textSize)
        val modified = findViewById<TextView>(R.id.textModified)

        title.text = bookTitle.ifBlank { "Untitled" }
        author.text = bookAuthor.ifBlank { "Unknown author" }
        chapters.text = if (bookChapters > 0) "$bookChapters chapters" else ""
        url.text = bookUrl.ifBlank { "" }
        val displayPath = if (!bookSource.isNullOrBlank() && bookSource != bookPath) bookSource else bookPath
        path.text = displayPath
        size.text = formatSize(bookSize)
        modified.text = if (bookModified > 0) {
            DateFormat.format("yyyy-MM-dd HH:mm", bookModified).toString()
        } else {
            if (coverFile.exists()) DateFormat.format("yyyy-MM-dd HH:mm", coverFile.lastModified()).toString() else "Unknown date"
        }

        Log.d(TAG, "ui_population_complete")
        loadCoverFromEpub(coverFile, cover)
        loadStoryDetails()

        val buttonUrl = findViewById<Button>(R.id.buttonUrl)
        if (bookUrl.isBlank()) {
            buttonUrl.visibility = View.GONE
        } else {
            buttonUrl.visibility = View.VISIBLE
            buttonUrl.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bookUrl)))
            }
        }

        findViewById<Button>(R.id.buttonOpen).setOnClickListener { openBook() }
        findViewById<Button>(R.id.buttonUpdate).setOnClickListener { updateBook() }
        findViewById<Button>(R.id.buttonForce).setOnClickListener { forceDownloadBook() }
        findViewById<Button>(R.id.buttonShare).setOnClickListener { shareBook() }
        findViewById<Button>(R.id.buttonDelete).setOnClickListener { deleteBook() }
        findViewById<Button>(R.id.buttonEditMetadata).setOnClickListener { editMetadata() }
        findViewById<Button>(R.id.buttonReplaceCover).setOnClickListener { replaceCover() }
        findViewById<Button>(R.id.buttonPreviewOnline).setOnClickListener { previewOnlineMetadata() }
        findViewById<Button>(R.id.buttonRefreshStory).apply {
            // The preview reads the authoritative story URL from the EPUB itself.
            setOnClickListener {
                startActivityForResult(Intent(this@BookDetailActivity, StoryRefreshActivity::class.java)
                    .putExtra("epub_path", bookPath), STORY_REFRESH_REQUEST)
            }
        }
    }

    /** Read existing OPF/tags only. No story-site request, EPUB rewrite or library migration. */
    private fun loadStoryDetails() {
        val statusView = findViewById<TextView>(R.id.textStoryStatus)
        val updatedView = findViewById<TextView>(R.id.textStoryUpdated)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val bridge = PythonBridge(applicationContext)
                    val raw = StorageBridge.withLocalEpub(applicationContext, bookPath) {
                        bridge.getStoryDetails(it.absolutePath)
                    } ?: error("Cannot read this EPUB")
                    JSONObject(raw).also {
                        check(it.optBoolean("ok")) { it.optString("error", "Cannot read story metadata") }
                    }
                }
                fun value(key: String): String = if (result.isNull(key)) "" else result.optString(key).trim()
                statusView.text = value("status").takeIf { it.isNotBlank() }?.let {
                    "Status: $it" } ?: "Status: Not stored"
                updatedView.text = value("date_updated").takeIf { it.isNotBlank() }?.let {
                    "Story updated: $it" } ?: "Story updated: Not stored"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                statusView.text = "Status: Unavailable"
                updatedView.text = "Story updated: Unavailable"
                DiagnosticLog.appendException(applicationContext, "Detail.StoryMetadata", "read_failed", e)
                showMessage("Cannot read saved story details: ${e.message}")
            }
        }
    }

    private fun loadCoverFromEpub(epubFile: File, coverView: ImageView) {
        Log.d(TAG, "cover_processing_start path=${epubFile.absolutePath} exists=${epubFile.exists()}")
        if (!epubFile.exists() || !epubFile.isFile) {
            coverView.visibility = View.GONE
            Log.d(TAG, "cover_processing_complete result=missing_file")
            return
        }
        val bridge = PythonBridge(applicationContext).takeIf { it.getInitError() == null } ?: run {
            coverView.visibility = View.GONE
            Log.d(TAG, "cover_processing_complete result=bridge_unavailable")
            return
        }
        try {
            val raw = bridge.getCoverFromEpub(epubFile.absolutePath)
            Log.d(TAG, "cover_bridge_returned=${!raw.isNullOrBlank()}")
            val result = org.json.JSONObject(raw ?: "{}")
            val coverData = result.optString("cover", "")
            if (coverData.isBlank() || !coverData.startsWith("data:")) {
                coverView.visibility = View.GONE
                Log.d(TAG, "cover_processing_complete result=no_cover_data")
                return
            }
            val comma = coverData.indexOf(",")
            if (comma <= 0) {
                coverView.visibility = View.GONE
                Log.d(TAG, "cover_processing_complete result=missing_comma")
                return
            }
            val base64 = coverData.substring(comma + 1)
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val bitmap = decodeSampledBitmap(bytes, 160)
            if (bitmap != null) {
                coverView.setImageBitmap(bitmap)
                coverView.visibility = View.VISIBLE
                Log.d(
                    TAG,
                    "cover_processing_complete result=bitmap width=${bitmap.width} height=${bitmap.height} bytes=${bytes.size} original=${result.optInt("original_bytes", 0)} jpeg=${result.optInt("jpeg_bytes", 0)}"
                )
            } else {
                coverView.visibility = View.GONE
                Log.d(TAG, "cover_processing_complete result=null_bitmap bytes=${bytes.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "cover_processing_failed type=${e.javaClass.simpleName} msg=${e.message ?: ""}")
            coverView.visibility = View.GONE
        }
    }

    private fun openBook() {
        val file = File(bookPath)
        val uri = when {
            file.exists() && file.isFile -> FileProvider.getUriForFile(this, "${packageName}.provider", file)
            else -> Uri.parse(bookPath)
        }
        val mime = if (bookPath.startsWith("content://")) contentResolver.getType(uri) ?: "application/epub+zip" else "application/epub+zip"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showMessage("No EPUB reader installed")
        }
    }

    private fun updateBook() {
        val bridge = PythonBridge(applicationContext).takeIf { it.getInitError() == null } ?: run {
            DiagnosticLog.append(this, "Detail.Update", "bridge_unavailable")
            showMessage("Bridge not available")
            return
        }
        DiagnosticLog.append(this, "Detail.Update", "button_pressed title=$bookTitle url=$bookUrl path=$bookPath source=$bookSource")
        val url = bookUrl
        if (url.isBlank()) {
            DiagnosticLog.append(this, "Detail.Update", "validation_failed reason=blank_url")
            showError("No URL found for this book")
            return
        }
        DiagnosticLog.append(this, "Detail.Update", "validation_passed url=$url")
        DiagnosticLog.append(this, "Detail.Update", "starting")
        Thread {
            val resultJson = try {
                DiagnosticLog.append(this, "Detail.Update", "bridge_start")
                val raw = StorageBridge.withLocalEpub(this, bookPath) { localPath ->
                    DiagnosticLog.append(this, "Detail.Update", "bridge_input=${localPath.absolutePath}")
                    FanFicFareGate.runBlocking {
                        bridge.updateEpubFromPath(localPath.absolutePath, filesDir.absolutePath)
                    }
                }
                DiagnosticLog.append(this, "Detail.Update", "bridge_returned=${raw != null}")
                raw
            } catch (e: Exception) {
                DiagnosticLog.appendException(this, "Detail.Update", "storage_bridge_exception", e)
                runOnUiThread { showError("Cannot read EPUB from this location: ${e.message ?: e.javaClass.simpleName}") }
                return@Thread
            } ?: run {
                DiagnosticLog.append(this, "Detail.Update", "bridge_null")
                runOnUiThread { showError("Cannot read EPUB from this location") }
                return@Thread
            }
            runOnUiThread {
                try {
                    val result = org.json.JSONObject(resultJson)
                    DiagnosticLog.append(this, "Detail.Update", "parsed ok=${result.optBoolean("ok")} skipped=${result.optBoolean("skipped")}")
                    if (result.optBoolean("ok")) {
                        if (result.optBoolean("skipped")) {
                            DiagnosticLog.append(this, "Detail.Update", "result=SKIPPED reason=${result.optString("reason", "already current")}")
                            showError("Update skipped: ${result.optString("reason", "already current")}")
                            return@runOnUiThread
                        }
                        val title = result.optString("title", bookTitle)
                        val author = result.optString("author", bookAuthor)
                        val internalPath = result.optString("path", "")
                        if (internalPath.isBlank()) {
                            DiagnosticLog.append(this, "Detail.Update", "result=FAILED empty_output_path")
                            showError("Update failed: bridge returned empty output path")
                            return@runOnUiThread
                        }
                        val outputDir = SettingsActivity.getOutputDir(this)
                        try {
                            val source = File(internalPath)
                            if (!source.exists() || !source.isFile) {
                                DiagnosticLog.append(this, "Detail.Update", "result=FAILED generated_file_missing=$internalPath")
                                showError("Update failed: generated file missing at $internalPath")
                                return@runOnUiThread
                            }
                            val finalPath = copyToOutputDir(source, outputDir)
                            DiagnosticLog.append(this, "Detail.Update", "copied finalPath=$finalPath")
                            Toast.makeText(applicationContext, "Updated: $title", Toast.LENGTH_LONG).show()
                            if (!isFinishing && !isDestroyed) {
                                window?.decorView?.postDelayed({
                                    if (!isFinishing && !isDestroyed) finishWithResult(title, author, finalPath, System.currentTimeMillis(), null)
                                }, 150)
                            }
                            DiagnosticLog.append(this, "Detail.Update", "result=SUCCESS title=$title path=$finalPath lifecycle_finishing=$isFinishing destroyed=$isDestroyed")
                        } catch (e: Exception) {
                            DiagnosticLog.appendException(this, "Detail.Update", "copy_output_exception", e)
                            showError("Update failed: ${e.message ?: "copy/output error"}")
                        }
                    } else {
                        DiagnosticLog.append(this, "Detail.Update", "result=FAILED error=${result.optString("error") ?: "unknown"}")
                        showError("Update failed: ${result.optString("error") ?: "unknown"}")
                    }
                } catch (e: Exception) {
                    DiagnosticLog.appendException(this, "Detail.Update", "result_parse_exception", e)
                    showError("Update failed: invalid response from bridge")
                }
            }
        }.start()
    }

    private fun forceDownloadBook() {
        val bridge = PythonBridge(applicationContext).takeIf { it.getInitError() == null } ?: run {
            DiagnosticLog.append(this, "Detail.ForceDownload", "bridge_unavailable")
            showMessage("Bridge not available")
            return
        }
        DiagnosticLog.append(this, "Detail.ForceDownload", "button_pressed title=$bookTitle url=$bookUrl path=$bookPath source=$bookSource")
        val url = bookUrl
        if (url.isBlank()) {
            DiagnosticLog.append(this, "Detail.ForceDownload", "validation_failed reason=blank_url")
            showError("No URL found for this book")
            return
        }
        DiagnosticLog.append(this, "Detail.ForceDownload", "validation_passed url=$url")
        DiagnosticLog.append(this, "Detail.ForceDownload", "starting")
        val t0 = System.currentTimeMillis()
        Thread {
            var resultJson: String? = null
            try {
                DiagnosticLog.append(this, "Detail.ForceDownload", "bridge_start elapsed=${System.currentTimeMillis() - t0}")
                resultJson = StorageBridge.withLocalEpub(this, bookPath) { localPath ->
                    DiagnosticLog.append(this, "Detail.ForceDownload", "bridge_input=${localPath.absolutePath} elapsed=${System.currentTimeMillis() - t0}")
                    FanFicFareGate.runBlocking {
                        bridge.forceDownloadFromEpub(localPath.absolutePath, filesDir.absolutePath)
                    }
                }
                DiagnosticLog.append(this, "Detail.ForceDownload", "bridge_returned=${resultJson != null} elapsed=${System.currentTimeMillis() - t0}")
            } catch (e: Exception) {
                DiagnosticLog.appendException(this, "Detail.ForceDownload", "storage_bridge_exception", e)
                runOnUiThread { showError("Cannot read EPUB from this location: ${e.message ?: e.javaClass.simpleName}") }
                return@Thread
            }
            if (resultJson == null) {
                DiagnosticLog.append(this, "Detail.ForceDownload", "bridge_null")
                runOnUiThread { showError("Cannot read EPUB from this location") }
                return@Thread
            }
            runOnUiThread {
                try {
                    val result = org.json.JSONObject(resultJson)
                    DiagnosticLog.append(this, "Detail.ForceDownload", "parsed ok=${result.optBoolean("ok")}")
                    if (result.optBoolean("ok")) {
                        val title = result.optString("title", bookTitle)
                        val author = result.optString("author", bookAuthor)
                        val internalPath = result.optString("path", "")
                        if (internalPath.isNotBlank()) {
                            showMessage("Force downloaded: $title")
                            Thread {
                                val outputDir = SettingsActivity.getOutputDir(this)
                                try {
                                    val source = java.io.File(internalPath)
                                    val finalPath = if (source.exists() && source.isFile) {
                                        copyToOutputDir(source, outputDir)
                                    } else {
                                        null
                                    }
                                    runOnUiThread {
                                        if (!isFinishing && !isDestroyed) {
                                            if (finalPath != null) {
                                                finishWithResult(title, author, finalPath, System.currentTimeMillis(), null)
                                            } else {
                                                showError("Force downloaded: $title")
                                            }
                                        }
                                        DiagnosticLog.append(this, "Detail.ForceDownload", "result=SUCCESS title=$title path=$finalPath lifecycle_finishing=$isFinishing destroyed=$isDestroyed")
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        showError("Force download failed: ${e.message ?: "copy/output error"}")
                                    }
                                    DiagnosticLog.appendException(this, "Detail.ForceDownload", "copy_output_exception", e)
                                }
                            }.start()
                        } else {
                            DiagnosticLog.append(this, "Detail.ForceDownload", "result=SUCCESS empty_internal_path")
                            showError("Force downloaded: $title")
                        }
                    } else {
                        val errorMsg = result.optString("error") ?: "unknown"
                        val detail = result.optString("detail")
                        val fullMsg = if (!detail.isNullOrBlank()) "$errorMsg\n$detail" else errorMsg
                        DiagnosticLog.append(this, "Detail.ForceDownload", "result=FAILED $fullMsg")
                        showError("Force download failed: $fullMsg")
                    }
                } catch (e: Exception) {
                    DiagnosticLog.appendException(this, "Detail.ForceDownload", "result_parse_exception", e)
                    showError("Force download failed: invalid response from bridge")
                }
            }
        }.start()
    }

    private fun shareBook() {
        val file = File(bookPath)
        val uri = when {
            file.exists() && file.isFile -> FileProvider.getUriForFile(this, "${packageName}.provider", file)
            else -> Uri.parse(bookPath)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/epub+zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("epub", uri)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share EPUB"))
        } catch (e: Exception) {
            showMessage("Cannot share this file")
        }
    }

    private fun deleteBook() {
        Thread {
            val ok = StorageBridge.deleteEpub(this, bookPath)
            runOnUiThread {
                if (ok) {
                    setResult(RESULT_OK, Intent().putExtra("deleted", true))
                    finish()
                } else {
                    showError("Delete failed")
                }
            }
        }.start()
    }

    private fun editMetadata() {
        val bridge = PythonBridge(applicationContext).takeIf { it.getInitError() == null } ?: run {
            showError("Bridge not available")
            return
        }
        Thread {
            val resultJson = try {
                StorageBridge.withLocalEpub(this, bookPath) { localPath ->
                    bridge.readEpubMetadata(localPath.absolutePath)
                }
            } catch (e: Exception) {
                runOnUiThread { showError("Cannot read EPUB: ${e.message ?: e.javaClass.simpleName}") }
                return@Thread
            } ?: run {
                runOnUiThread { showError("Cannot read EPUB from this location") }
                return@Thread
            }
            runOnUiThread {
                try {
                    val result = JSONObject(resultJson)
                    if (!result.optBoolean("ok")) {
                        showError("Cannot read metadata: ${result.optString("error")}")
                        return@runOnUiThread
                    }
                    val metadata = result.optJSONObject("metadata") ?: JSONObject()
                    val authorsList = mutableListOf<String>()
                    val authorsJson = metadata.optJSONArray("authors")
                    if (authorsJson != null) {
                        for (i in 0 until authorsJson.length()) {
                            authorsList.add(authorsJson.getString(i))
                        }
                    }
                    val intent = Intent(this, EditMetadataActivity::class.java).apply {
                        putExtra("epub_path", bookPath)
                        putExtra("title", metadata.optString("title", ""))
                        putStringArrayListExtra("authors", ArrayList(authorsList))
                        putExtra("language", metadata.optString("languages", ""))
                        putExtra("publisher", metadata.optString("publisher", ""))
                        putExtra("description", metadata.optString("description", ""))
                        putExtra("tags", metadata.optString("tags", ""))
                        putExtra("series", metadata.optString("series", ""))
                        putExtra("series_index", metadata.optString("series_index", ""))
                        putExtra("rating", metadata.optString("rating", ""))
                        putExtra("isbn", metadata.optString("isbn", ""))
                        putExtra("pubdate", metadata.optString("pubdate", ""))
                        putExtra("rights", metadata.optString("rights", ""))
                    }
                    startActivityForResult(intent, EDIT_METADATA_REQUEST)
                } catch (e: Exception) {
                    showError("Failed to parse metadata: ${e.message ?: "unknown"}")
                }
            }
        }.start()
    }

    private fun replaceCover() {
        // Launch image picker to select a new cover image
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REPLACE_COVER_REQUEST)
    }

    private fun previewOnlineMetadata() {
        // Launch MetadataPreviewActivity for user to preview online metadata
        // and cover candidates before explicitly applying.
        val intent = Intent(this, MetadataPreviewActivity::class.java).apply {
            putExtra("epub_path", bookPath)
            putExtra("title", bookTitle)
            putExtra("author", bookAuthor)
            putExtra("url", bookUrl)
            putExtra("isbn", "")
        }
        startActivityForResult(intent, PREVIEW_METADATA_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            STORY_REFRESH_REQUEST -> {
                if (resultCode == RESULT_OK && data != null) {
                    // The refresh store already persisted the exact library row including cover.
                    setResult(RESULT_OK, Intent(data).putExtra("story_refreshed", true))
                    finish()
                }
            }
            EDIT_METADATA_REQUEST, PREVIEW_METADATA_REQUEST -> {
                if (resultCode == RESULT_OK) {
                    val title = data?.getStringExtra("title") ?: bookTitle
                    val author = data?.getStringArrayListExtra("authors")?.joinToString(", ") ?: bookAuthor
                    val path = data?.getStringExtra("output_path") ?: bookPath
                    val modified = data?.getLongExtra("modified", System.currentTimeMillis()) ?: System.currentTimeMillis()
                    finishWithResult(title, author, path, modified, bookSource)
                }
            }
            REPLACE_COVER_REQUEST -> {
                if (resultCode == RESULT_OK && data != null) {
                    val uri = data.data
                    if (uri != null) {
                        replaceCoverWithImage(uri)
                    }
                }
            }
        }
    }

    private fun replaceCoverWithImage(imageUri: Uri) {
        val bridge = PythonBridge(applicationContext).takeIf { it.getInitError() == null } ?: run {
            showError("Bridge not available")
            return
        }
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(imageUri)
                val bytes = inputStream?.readBytes() ?: run {
                    runOnUiThread { showError("Cannot read selected image") }
                    return@Thread
                }
                inputStream?.close()

                // Determine MIME type
                val mime = contentResolver.getType(imageUri) ?: "image/jpeg"

                // Encode as base64
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

                // Use a temp file for the operation
                val resultJson = StorageBridge.withLocalEpub(this, bookPath) { localPath ->
                    val outPath = localPath.absolutePath.replace(".epub", "_covered.epub")
                    bridge.replaceEpubCover(localPath.absolutePath, base64, mime, outPath, ".bak")
                }

                if (resultJson == null) {
                    runOnUiThread { showError("Cannot read EPUB from this location") }
                    return@Thread
                }

                val result = JSONObject(resultJson)
                if (result.optBoolean("ok")) {
                    val outputPath = result.optString("output_path", "")
                    val outputDir = SettingsActivity.getOutputDir(this)
                    val source = File(outputPath)
                    if (source.exists() && source.isFile) {
                        val finalPath = StorageBridge.copyToOutputDir(this, source, outputDir)
                        // Clean up the local _covered.epub working file now that
                        // it has been persisted to the user's output directory.
                        // This mirrors the Phase 10 cleanup pattern in
                        // MetadataPreviewActivity.applyMetadataAndCoverSafely.
                        source.delete()
                        runOnUiThread {
                            showMessage("Cover replaced: ${result.optString("cover_path_in_epub", "")}")
                            finishWithResult(bookTitle, bookAuthor, finalPath, System.currentTimeMillis(), null)
                        }
                    } else {
                        runOnUiThread { showError("Cover replacement failed: output file missing") }
                    }
                } else {
                    runOnUiThread { showError("Cover replacement failed: ${result.optString("error")}") }
                }
            } catch (e: Exception) {
                runOnUiThread { showError("Cover replacement failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun finishWithResult(title: String, author: String, path: String, modified: Long, source: String?) {
        val blocked = isFinishing || isDestroyed
        if (blocked) {
            DiagnosticLog.append(this, "Detail.Update", "finishWithResult blocked isFinishing=$isFinishing isDestroyed=$isDestroyed")
        }
        if (isFinishing || isDestroyed) return
        val data = Intent().apply {
            putExtra("title", title)
            putExtra("author", author)
            putExtra("path", path)
            putExtra("modified", modified)
            if (!source.isNullOrBlank()) putExtra("source", source)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun copyToOutputDir(sourceFile: File, outputDir: String): String {
        return StorageBridge.copyToOutputDir(this, sourceFile, outputDir)
    }

    private fun decodeSampledBitmap(data: ByteArray, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val scale = calculateInSampleSize(opts, maxDim, maxDim)
        return BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = scale
        }.let { BitmapFactory.decodeByteArray(data, 0, data.size, it) }
    }

    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        var h = opts.outHeight
        var w = opts.outWidth
        var inSampleSize = 1
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun showError(message: String) {
        runOnUiThread {
            showMessage(message)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format("%.1f KB", kb) else String.format("%.1f MB", kb / 1024.0)
    }
}
