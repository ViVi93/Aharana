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
    private var bookId: Long = 0L
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
        bookId = intent.getLongExtra("book_id", 0L)
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
                    .putExtra("book_id", bookId)
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

    private fun updateBook() = downloadExistingBook(force = false)

    private fun forceDownloadBook() = downloadExistingBook(force = true)

    /** Resolve after recovery, then keep storage exclusion through generation, replacement and Room. */
    private fun downloadExistingBook(force: Boolean) {
        val tag = if (force) "Detail.ForceDownload" else "Detail.Update"
        val label = if (force) "Force download" else "Update"
        DiagnosticLog.append(this, tag, "START path=$bookPath")
        Thread {
            try {
                val result = LibraryStorageGate.runBlocking {
                    val recovery = BookRepository.reconcileOrganisedPaths(applicationContext)
                    val moves = recovery.optJSONArray("moves")
                    // Recovery is already flattened; remap this Intent's input exactly once.
                    val recoveryPaths = buildMap<String, String> {
                        if (moves != null) for (i in 0 until moves.length()) {
                            val move = moves.getJSONObject(i)
                            put(move.getString("source"), move.getString("target"))
                        }
                    }
                    val resolvedPath = recoveryPaths[bookPath] ?: bookPath
                    val bookDao = com.vivi.aharana.data.local.AppDatabase.getInstance(applicationContext).bookDao()
                    val existing = if (bookId > 0) {
                        bookDao.findById(bookId)
                            ?: error("Book is no longer in the library. Reload the library before applying.")
                    } else bookDao.findByFilePath(resolvedPath)
                    val path = LibraryEpubStorage.existingPath(existing?.filePath, resolvedPath)
                    val bridge = PythonBridge(applicationContext)
                    check(bridge.getInitError() == null) { "Bridge not available: ${bridge.getInitError()}" }
                    val workDir = File(cacheDir, "epub-detail-${java.util.UUID.randomUUID()}")
                    check(workDir.mkdirs()) { "Cannot create EPUB staging directory" }
                    try {
                        DiagnosticLog.append(this, tag, "bridge_start input=$path")
                        val raw = StorageBridge.withLocalEpub(applicationContext, path) { localFile ->
                            FanFicFareGate.runBlocking {
                                if (force) bridge.forceDownloadFromEpub(localFile.absolutePath, workDir.absolutePath)
                                else bridge.updateEpubFromPath(localFile.absolutePath, workDir.absolutePath)
                            }
                        } ?: error("Cannot read EPUB from this location. Refresh the library and retry.")
                        val response = JSONObject(raw)
                        DiagnosticLog.append(this, tag, "bridge_returned ok=${response.optBoolean("ok")} skipped=${response.optBoolean("skipped")}")
                        if (!response.optBoolean("ok")) {
                            val error = response.optString("error", "Unknown bridge error")
                            val detail = response.optString("detail")
                            error(if (detail.isBlank()) error else "$error\n$detail")
                        }
                        if (!response.optBoolean("skipped")) {
                            val internalPath = response.optString("path")
                            check(internalPath.isNotBlank()) { "Bridge returned no generated EPUB; the original was kept" }
                            val finalPath = StorageBridge.replaceExistingEpub(applicationContext, File(internalPath), path)
                            val sourcePath = LibraryEpubStorage.sourceAfterReplacement(
                                existing?.filePath ?: bookPath, existing?.sourcePath ?: bookSource, finalPath)
                            if (existing != null) {
                                bookDao.update(existing.copy(
                                    title = response.optString("title", existing.title),
                                    author = response.optString("author", existing.author),
                                    filePath = finalPath,
                                    sourcePath = sourcePath,
                                    lastModified = response.optLong("modified", System.currentTimeMillis()),
                                    sizeBytes = File(finalPath).length(),
                                    coverData = response.optString("cover", existing.coverData ?: ""),
                                    url = response.optString("url", existing.url ?: ""),
                                    chapters = response.optInt("chapters", existing.chapters)
                                ))
                            }
                            response.put("path", finalPath).put("source", sourcePath)
                            response.put("library_persisted", existing != null)
                            DiagnosticLog.append(this, tag, "result=SUCCESS path=$finalPath")
                        } else {
                            DiagnosticLog.append(this, tag, "result=SKIPPED reason=${response.optString("reason")}")
                        }
                        response
                    } finally {
                        workDir.deleteRecursively()
                    }
                }
                runOnUiThread {
                    if (result.optBoolean("skipped")) {
                        showMessage("$label skipped: ${result.optString("reason", "already current")}")
                    } else {
                        val title = result.optString("title", bookTitle)
                        Toast.makeText(applicationContext, if (force) "Force downloaded: $title" else "Updated: $title", Toast.LENGTH_LONG).show()
                        if (!isFinishing && !isDestroyed) {
                            window?.decorView?.postDelayed({
                                if (!isFinishing && !isDestroyed) finishWithResult(title,
                                    result.optString("author", bookAuthor), result.getString("path"),
                                    result.optLong("modified", System.currentTimeMillis()), result.optString("source"),
                                    libraryPersisted = result.optBoolean("library_persisted"))
                            }, 150)
                        }
                    }
                }
            } catch (e: Exception) {
                DiagnosticLog.appendException(this, tag, "result=FAILED", e)
                runOnUiThread { showError("$label failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                DiagnosticLog.append(this, tag, "END")
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
            try {
                LibraryBookMutations.runBlocking(applicationContext, bookPath, expectedBookId = bookId) { mutation -> mutation.delete() }
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra("deleted", true).putExtra("story_refreshed", true))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread { showError("Delete failed: ${e.message ?: e.javaClass.simpleName}") }
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
                        putExtra("book_id", bookId)
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
            putExtra("book_id", bookId)
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
                if (resultCode == RESULT_OK && data?.getBooleanExtra("story_refreshed", false) == true) {
                    // Child committed Room while holding storage exclusion. Never replay its old path.
                    setResult(RESULT_OK, Intent(data).putExtra("story_refreshed", true))
                    finish()
                } else if (resultCode == RESULT_OK) {
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

                val result = LibraryBookMutations.runBlocking(applicationContext, bookPath, expectedBookId = bookId) { mutation ->
                    val isDocument = mutation.path.startsWith("content://")
                    val raw = StorageBridge.withLocalEpub(this, mutation.path) { localPath ->
                        // Filesystem books stay at the exact current path, including author folders.
                        // Picked documents retain the existing separate-copy workflow.
                        val outPath = if (mutation.path.startsWith("content://")) {
                            File(localPath.parentFile, "covered_${java.util.UUID.randomUUID()}.epub").absolutePath
                        } else null
                        bridge.replaceEpubCover(localPath.absolutePath, base64, mime, outPath, ".bak")
                    } ?: error("Cannot read EPUB from this location")
                    val saved = JSONObject(raw)
                    check(saved.optBoolean("ok")) { saved.optString("error", "Cover replacement failed") }
                    val generated = File(saved.getString("output_path"))
                    check(generated.isFile && generated.length() > 0) { "Cover output file missing" }
                    val finalPath = if (isDocument) {
                        try {
                            StorageBridge.copyToOutputDir(this, generated, SettingsActivity.getOutputDir(this))
                        } finally { generated.delete() }
                    } else mutation.path
                    mutation.persist(finalPath)
                    saved.put("output_path", finalPath)
                }
                runOnUiThread {
                    showMessage("Cover replaced: ${result.optString("cover_path_in_epub", "")}")
                    finishWithResult(bookTitle, bookAuthor, result.getString("output_path"),
                        System.currentTimeMillis(), bookSource, libraryPersisted = true)
                }
            } catch (e: Exception) {
                runOnUiThread { showError("Cover replacement failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun finishWithResult(title: String, author: String, path: String, modified: Long, source: String?, libraryPersisted: Boolean = false) {
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
            // Existing MainActivity contract: the sole writer has already committed Room;
            // reload instead of saving a stale Activity snapshot over this or an organiser move.
            if (libraryPersisted) putExtra("story_refreshed", true)
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
