package com.vivi.aharana

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 7: Metadata & Cover Preview / Selection screen.
 *
 * Workflow:
 *   1. User has an EPUB (existing) or a story URL.
 *   2. Online metadata is looked up via Phase 5 providers (Python-side).
 *   3. Current EPUB metadata is read via the existing epub_editor (Python-side).
 *   4. Phase 3 metadata_diff shows what would change.
 *   5. Phase 6 cover candidates are extracted and ranked.
 *   6. User reviews online metadata and cover candidates.
 *   7. User explicitly confirms by pressing "Apply Metadata" or "Apply Cover".
 *   8. Existing epub_editor writers are called (Python-side).
 *
 * Covers are NOT automatically replaced.
 * Metadata is NOT automatically written.
 *
 * This activity is launched from BookDetailActivity via "Preview Online Metadata".
 */
class MetadataPreviewActivity : BaseActivity() {

    companion object {
        private const val TAG = "MetadataPreview"
    }

    private lateinit var bridge: PythonBridge
    private lateinit var epubPath: String
    private var bookId: Long = 0L
    private lateinit var bookTitle: String
    private lateinit var bookAuthor: String
    private var bookIsbn: String = ""

    // State
    private var resultsJson: JSONArray = JSONArray()
    private var validResultIndices: MutableList<Int> = mutableListOf()
    private var selectedResultIndex = 0
    private var currentMetadata: JSONObject = JSONObject()
    private var allCandidates: MutableList<CoverCandidate> = mutableListOf()
    private var selectedCandidate: CoverCandidate? = null

    /** True if the currently selected result has no error. */
    private fun isResultOk(index: Int): Boolean {
        if (index < 0 || index >= resultsJson.length()) return false
        val r = resultsJson.optJSONObject(index) ?: return false
        return r.optString("error", "").isEmpty()
    }

    /** Rebuild the list of indices into resultsJson that are successful (ok). */
    private fun rebuildValidIndices() {
        validResultIndices.clear()
        for (i in 0 until resultsJson.length()) {
            if (isResultOk(i)) validResultIndices.add(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_metadata_preview)
        applyTopInset(findViewById<android.view.View>(R.id.appBar))
        applyBottomInset(findViewById<android.view.View>(R.id.contentScroll))

        epubPath = intent.getStringExtra("epub_path") ?: ""
        bookId = intent.getLongExtra("book_id", 0L)
        bookTitle = intent.getStringExtra("title") ?: ""
        bookAuthor = intent.getStringExtra("author") ?: ""
        bookIsbn = intent.getStringExtra("isbn") ?: ""

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)
        supportActionBar?.title = "Preview Online Metadata"

        bridge = PythonBridge(applicationContext)
        if (bridge.getInitError() != null) {
            showMessage("Bridge not available: ${bridge.getInitError()}")
            finish()
            return
        }
        bridge.initialize(SettingsActivity.getConfigDir(this).absolutePath)

        setupApplyButtons()

        // Kick off lookup + current metadata read on background thread
        loadEverything()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ========================================================================
    // Loading
    // =========================================================================

    private fun loadEverything() {
        Thread {
            // Guard: bail out if the Activity has already been destroyed
            if (isFinishing || isDestroyed) return@Thread
            // 1. Read current EPUB metadata
            val current = try {
                StorageBridge.withLocalEpub(this, epubPath) { localPath ->
                    bridge.readEpubMetadata(localPath.absolutePath)
                }
            } catch (e: Exception) {
                safeRunOnUiThread { showError("Cannot read EPUB: ${e.message ?: e.javaClass.simpleName}") }
                return@Thread
            } ?: run {
                safeRunOnUiThread { showError("Cannot read EPUB from this location") }
                return@Thread
            }

            try {
                val curObj = JSONObject(current)
                if (curObj.optBoolean("ok")) {
                    currentMetadata = curObj.optJSONObject("metadata") ?: JSONObject()
                } else {
                    Log.w(TAG, "read_epub_metadata returned error: ${curObj.optString("error")}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "parse current metadata failed", e)
            }

            // 2. Lookup online metadata (Phase 5 via bridge)
            val isbn = currentMetadata.optString("isbn", bookIsbn)
            val title = currentMetadata.optString("title", bookTitle)
            val authors = currentMetadata.optJSONArray("authors")
            var authorStr = ""
            if (authors != null) {
                val list = mutableListOf<String>()
                for (i in 0 until authors.length()) list.add(authors.optString(i, ""))
                authorStr = list.joinToString(", ")
            }
            if (authorStr.isBlank()) authorStr = bookAuthor

            Log.d(TAG, "lookup_start title=$title author=$authorStr isbn=$isbn")
            val raw = bridge.lookupOnlineMetadata(title, authorStr, isbn)
            Log.d(TAG, "lookup_returned len=${raw.length}")

            try {
                val result = JSONObject(raw)
                if (result.optBoolean("ok")) {
                    resultsJson = result.optJSONArray("results") ?: JSONArray()
                    Log.d(TAG, "results_count=${resultsJson.length()}")
                    safeRunOnUiThread { populateResults() }
                } else {
                    safeRunOnUiThread { showError("Online lookup failed: ${result.optString("error")}") }
                }
            } catch (e: Exception) {
                safeRunOnUiThread { showError("Failed to parse online results: ${e.message ?: "unknown"}") }
            }
        }.start()
    }

    // =========================================================================
    // Populate UI
    // =========================================================================

    private fun populateResults() {
        rebuildValidIndices()

        if (validResultIndices.isEmpty()) {
            findViewById<TextView>(R.id.textOnlineProvider).text =
                if (resultsJson.length() == 0)
                    "No results found"
                else
                    "All providers failed"
            findViewById<LinearLayout>(R.id.providerResultSelector).visibility = View.GONE
            // Clear metadata diff
            findViewById<TextView>(R.id.textDiffSummary).text = "No valid metadata to apply"
            allCandidates.clear()
            selectedCandidate = null
            val container = findViewById<LinearLayout>(R.id.coverCandidateContainer)
            container.removeAllViews()
            findViewById<TextView>(R.id.textCoversHeader).text = "Cover Candidates"
            updateApplyButtonsEnabled()
            return
        }

        // Default to the highest-ranked successful result
        if (!isResultOk(selectedResultIndex)) {
            selectedResultIndex = validResultIndices[0]
        }

        // Build provider result selector if there are multiple valid results
        val selector = findViewById<LinearLayout>(R.id.providerResultSelector)
        selector.removeAllViews()
        if (validResultIndices.size > 1) {
            selector.visibility = View.VISIBLE
            val inflater = LayoutInflater.from(this)
            for (i in validResultIndices.indices) {
                val idx = validResultIndices[i]
                val result = resultsJson.getJSONObject(idx)
                val provider = result.optString("provider", "provider ${i + 1}")
                val btn = inflater.inflate(
                    R.layout.item_metadata_provider_selector, selector, false
                ) as com.google.android.material.button.MaterialButton
                btn.text = provider
                btn.isSelected = (i == validResultIndices.indexOf(selectedResultIndex))
                btn.setOnClickListener {
                    selectedResultIndex = idx
                    // Refresh button states
                    for (j in 0 until selector.childCount) {
                        val sibling = selector.getChildAt(j) as com.google.android.material.button.MaterialButton
                        sibling.isSelected = (j == i)
                    }
                    // Reset stale cover selection when provider changes
                    selectedCandidate = null
                    allCandidates.clear()
                    // Re-display metadata and covers for the selected provider
                    displaySelectedResult()
                }
                selector.addView(btn)
            }
        } else {
            selector.visibility = View.GONE
        }

        displaySelectedResult()
        updateApplyButtonsEnabled()
    }

    private fun displaySelectedResult() {
        // Guard against empty or invalid index
        if (resultsJson.length() == 0 || !isResultOk(selectedResultIndex)) {
            findViewById<TextView>(R.id.textOnlineProvider).text = "No valid metadata"
            return
        }

        // Use the currently selected result (highest-ranked by default)
        val onlineResult = resultsJson.getJSONObject(selectedResultIndex)
        val onlineMeta = onlineResult.optJSONObject("metadata") ?: JSONObject()
        val provider = onlineResult.optString("provider", "unknown")

        // Update provider text + selector button states
        findViewById<TextView>(R.id.textOnlineProvider).text = "Source: $provider"
        val selector = findViewById<LinearLayout>(R.id.providerResultSelector)
        for (i in 0 until selector.childCount) {
            val btn = selector.getChildAt(i) as com.google.android.material.button.MaterialButton
            btn.isSelected = (i == selectedResultIndex)
        }

        // Populate metadata field rows.
        setFieldRow("row_title", "Title", "title", onlineMeta, currentMetadata)
        setFieldRow("row_subtitle", "Subtitle", "subtitle", onlineMeta, currentMetadata)
        setFieldRow("row_author", "Author(s)", "authors", onlineMeta, currentMetadata)
        setFieldRow("row_language", "Language", "languages", onlineMeta, currentMetadata)
        setFieldRow("row_publisher", "Publisher", "publisher", onlineMeta, currentMetadata)
        setFieldRow("row_description", "Description", "description", onlineMeta, currentMetadata)
        setFieldRow("row_tags", "Tags", "tags", onlineMeta, currentMetadata)
        setFieldRow("row_series", "Series", "series", onlineMeta, currentMetadata)
        setFieldRow("row_series_index", "Series Index", "series_index", onlineMeta, currentMetadata)
        setFieldRow("row_rating", "Rating", "rating", onlineMeta, currentMetadata)
        setFieldRow("row_isbn", "ISBN", "isbn", onlineMeta, currentMetadata)
        setFieldRow("row_pubdate", "Publication Date", "pubdate", onlineMeta, currentMetadata)
        setFieldRow("row_rights", "Rights", "rights", onlineMeta, currentMetadata)

        // Diff summary
        val diffRaw = bridge.diffMetadata(currentMetadata.toString(), onlineMeta.toString())
        try {
            val diffResult = JSONObject(diffRaw)
            val changes = mutableListOf<String>()
            if (diffResult.optBoolean("ok")) {
                val changeArr = diffResult.optJSONArray("changes")
                if (changeArr != null) {
                    for (i in 0 until changeArr.length()) {
                        changes.add(changeArr.getString(i))
                    }
                }
            }
            val diffText = if (changes.isEmpty()) {
                "No changes detected"
            } else {
                "Changes: ${changes.size} field(s) — ${changes.take(5).joinToString(", ")}" +
                    (if (changes.size > 5) "..." else "")
            }
            findViewById<TextView>(R.id.textDiffSummary).text = diffText
        } catch (e: Exception) {
            findViewById<TextView>(R.id.textDiffSummary).text = "Diff unavailable"
        }

        // Reset cover candidates and re-extract for the selected result
        allCandidates.clear()
        selectedCandidate = null
        extractCovers()
    }

    data class CoverCandidate(
        val url: String,
        val source: String,
        val sourceId: String,
        val width: String,
        val height: String,
        val mimeType: String,
        var downloadedData: String? = null,
        var downloadedMime: String? = null,
        var downloadError: String? = null,
    ) {
        fun toJSON(): JSONObject = JSONObject().apply {
            put("url", url)
            put("source", source)
            put("source_id", sourceId)
            put("width", width)
            put("height", height)
            put("mime_type", mimeType)
        }
    }

    private fun extractCovers() {
        val container = findViewById<LinearLayout>(R.id.coverCandidateContainer)
        container.removeAllViews()

        // Build a JSONArray of ALL valid (ok) results — not just the selected one.
        // This lets the user see cover candidates from every provider that
        // returned one, rather than only from the currently selected provider.
        val allResults = JSONArray()
        for (i in 0 until resultsJson.length()) {
            if (isResultOk(i)) {
                allResults.put(resultsJson.getJSONObject(i))
            }
        }
        if (allResults.length() == 0) return

        val raw = bridge.extractCoverCandidates(allResults.toString())
        try {
            val result = JSONObject(raw)
            if (!result.optBoolean("ok")) {
                findViewById<TextView>(R.id.textCoversHeader).text =
                    "Cover Candidates (error: ${result.optString("error")})"
                return
            }
            val candsJson = result.optJSONArray("candidates") ?: JSONArray()
            if (candsJson.length() == 0) {
                findViewById<TextView>(R.id.textCoversHeader).text =
                    "Cover Candidates (none available)"
                return
            }

            allCandidates = parseCandidates(candsJson).toMutableList()
            renderCandidateViews(allCandidates, container)
        } catch (e: Exception) {
            findViewById<TextView>(R.id.textCoversHeader).text = "Cover Candidates"
            Log.e(TAG, "extract_covers_error", e)
        }
    }

    private fun parseCandidates(arr: JSONArray): List<CoverCandidate> {
        val list = mutableListOf<CoverCandidate>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val url = obj.optString("url", "")
            if (url.isBlank()) continue
            list.add(CoverCandidate(
                url = url,
                source = obj.optString("source", ""),
                sourceId = obj.optString("source_id", ""),
                width = obj.optString("width", ""),
                height = obj.optString("height", ""),
                mimeType = obj.optString("mime_type", ""),
            ))
        }
        return list
    }

    private fun renderCandidateViews(cands: MutableList<CoverCandidate>, container: LinearLayout) {
        val inflater = LayoutInflater.from(this)
        for ((idx, cand) in cands.withIndex()) {
            val view = inflater.inflate(R.layout.item_cover_candidate, container, false)
            val providerText = view.findViewById<TextView>(R.id.coverProvider)
            val dimText = view.findViewById<TextView>(R.id.coverDimensions)
            val statusText = view.findViewById<TextView>(R.id.coverStatus)

            providerText.text = cand.source.ifBlank { "Online" }
            dimText.text = if (cand.width.isNotBlank() && cand.height.isNotBlank())
                "${cand.width}×${cand.height}" else "(dimensions unknown)"
            statusText.text = "Loading..."

            view.setOnClickListener {
                selectedCandidate = cand
                updateApplyButtonsEnabled()
                showMessage("Selected: ${cand.source}")
            }

            container.addView(view)

            // Start download
            Thread {
                // Guard: bail out if the Activity has already been destroyed
                if (isFinishing || isDestroyed) return@Thread
                val raw = bridge.downloadCover(cand.url, 15)
                try {
                    val result = JSONObject(raw)
                    if (result.optBoolean("ok")) {
                        val b64 = result.optString("image_data", "")
                        val mime = result.optString("mime_type", "")
                        val w = result.optInt("width", 0)
                        val h = result.optInt("height", 0)
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        safeRunOnUiThread {
                            if (idx < cands.size) {
                                val cv = container.getChildAt(idx)
                                val img = cv.findViewById<ImageView>(R.id.coverImage)
                                val prog = cv.findViewById<ProgressBar>(R.id.coverProgress)
                                val st = cv.findViewById<TextView>(R.id.coverStatus)
                                if (bitmap != null) {
                                    img.setImageBitmap(bitmap)
                                    img.visibility = View.VISIBLE
                                    prog.visibility = View.GONE
                                    st.text = "Ready (${w}x${h})"
                                    st.visibility = View.GONE
                                    cands[idx].downloadedData = b64
                                    cands[idx].downloadedMime = mime
                                    updateApplyButtonsEnabled()
                                } else {
                                    prog.visibility = View.GONE
                                    st.text = "Invalid image"
                                }
                            }
                        }
                    } else {
                        safeRunOnUiThread {
                            if (idx < cands.size) {
                                val cv = container.getChildAt(idx)
                                val prog = cv.findViewById<ProgressBar>(R.id.coverProgress)
                                val st = cv.findViewById<TextView>(R.id.coverStatus)
                                prog.visibility = View.GONE
                                st.text = "Download failed: ${result.optString("error")}"
                                cands[idx].downloadError = result.optString("error")
                                updateApplyButtonsEnabled()
                            }
                        }
                    }
                } catch (e: Exception) {
                    safeRunOnUiThread {
                        if (idx < cands.size) {
                            val cv = container.getChildAt(idx)
                            val prog = cv.findViewById<ProgressBar>(R.id.coverProgress)
                            val st = cv.findViewById<TextView>(R.id.coverStatus)
                            prog.visibility = View.GONE
                            st.text = "Error: ${e.message ?: "unknown"}"
                            cands[idx].downloadError = e.message
                            updateApplyButtonsEnabled()
                        }
                    }
                }
            }.start()
        }
    }

    // =========================================================================
    // Field helpers
    // =========================================================================

    private fun setFieldRow(rowId: String, label: String, field: String,
                            online: JSONObject, current: JSONObject) {
        val rowIdRes = resources.getIdentifier(rowId, "id", packageName)
        if (rowIdRes == 0) return
        val row = findViewById<LinearLayout>(rowIdRes)
        if (row == null) return

        val lbl = row.findViewById<TextView>(R.id.textFieldLabel)
        val cur = row.findViewById<TextView>(R.id.textFieldCurrent)
        val onl = row.findViewById<TextView>(R.id.textFieldOnline)

        lbl.text = label
        val curVal = formatField(field, current.opt(field))
        val onlVal = formatField(field, online.opt(field))
        cur.text = "Current: ${curVal.ifEmpty { "(none)" }}"
        onl.text = "Online: ${onlVal.ifEmpty { "(none)" }}"
    }

    private fun formatField(_field: String, value: Any?): String {
        return when {
            value == null -> ""
            value is JSONArray -> {
                val list = mutableListOf<String>()
                for (i in 0 until value.length()) list.add(value.optString(i, ""))
                list.filter { it.isNotBlank() }.joinToString(", ")
            }
            else -> value.toString().trim()
        }
    }

    /** Extract authors from online metadata as an ArrayList<String> for the
     *  result Intent, matching BookDetailActivity's getStringArrayListExtra("authors"). */
    private fun extractAuthors(onlineMeta: JSONObject): ArrayList<String> {
        val list = ArrayList<String>()
        val authors = onlineMeta.optJSONArray("authors")
        if (authors != null) {
            for (i in 0 until authors.length()) {
                val a = authors.optString(i, "")
                if (a.isNotBlank()) list.add(a)
            }
        }
        if (list.isEmpty()) {
            val authorStr = onlineMeta.optString("author", "")
            if (authorStr.isNotBlank()) list.add(authorStr)
        }
        if (list.isEmpty()) {
            // Fall back to the current book author
            if (bookAuthor.isNotBlank()) list.add(bookAuthor)
        }
        return list
    }

    // =========================================================================
    // Apply actions (require explicit user confirmation)
    // =========================================================================

    private fun setupApplyButtons() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonApplyMetadata).setOnClickListener {
            confirmAndApplyMetadata()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonApplyCover).setOnClickListener {
            confirmAndApplyCover()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonApplyBoth).setOnClickListener {
            confirmAndApplyBoth()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonCancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    /** Enable/disable Apply buttons based on whether a valid result is selected. */
    private fun updateApplyButtonsEnabled() {
        val hasValid = resultsJson.length() > 0 && isResultOk(selectedResultIndex)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonApplyMetadata).isEnabled = hasValid
        val hasCover = hasValid && selectedCandidate?.downloadedData?.isNotBlank() == true
        findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonApplyCover).isEnabled = hasCover
        findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonApplyBoth).isEnabled = hasValid && hasCover
    }

    private fun confirmAndApplyMetadata() {
        if (resultsJson.length() == 0 || !isResultOk(selectedResultIndex)) {
            showMessage("No online metadata to apply")
            return
        }
        val online = resultsJson.getJSONObject(selectedResultIndex)
        val onlineMeta = online.optJSONObject("metadata") ?: JSONObject()
        val fields = buildFieldsFromResult(onlineMeta)

        MaterialAlertDialogBuilder(this)
            .setTitle("Apply Metadata?")
            .setMessage("This will write the online metadata to the EPUB. A backup will be created. Continue?")
            .setPositiveButton("Apply") { _, _ ->
                Thread {
                    // Guard: bail out if the Activity has already been destroyed
                    if (isFinishing || isDestroyed) return@Thread
                    try {
                        val result = applyMetadataAndCoverSafely(
                            fields.toString(), null, null, ".metadata_edit_bak"
                        )
                        safeRunOnUiThread {
                            try {
                                val r = JSONObject(result)
                                if (r.optBoolean("ok") && r.optBoolean("metadata_written")) {
                                    val changed = r.optJSONArray("changed_fields")
                                    val list = mutableListOf<String>()
                                    if (changed != null) {
                                        for (i in 0 until changed.length()) list.add(changed.getString(i))
                                    }
                                    showMessage("Metadata applied: ${list.joinToString(", ")}")
                                    val out = Intent().apply {
                                        putExtra("title", onlineMeta.optString("title", bookTitle))
                                        putStringArrayListExtra("authors", extractAuthors(onlineMeta))
                                        putExtra("story_refreshed", true)
                                        putExtra("output_path", r.optString("output_path", epubPath))
                                        putExtra("modified", System.currentTimeMillis())
                                    }
                                    setResult(RESULT_OK, out)
                                    finish()
                                } else {
                                    showError("Apply failed: ${r.optString("metadata_error", "unknown")}")
                                }
                            } catch (e: Exception) {
                                showError("Failed to parse result: ${e.message ?: "unknown"}")
                            }
                        }
                    } catch (e: Exception) {
                        safeRunOnUiThread { showError("Apply failed: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildFieldsFromResult(onlineMeta: JSONObject): JSONObject {
        val fields = JSONObject()
        val keys = listOf("title", "subtitle", "authors", "languages",
            "contributors", "publisher", "description", "tags",
            "series", "series_index", "rating", "identifiers",
            "isbn", "isbn10", "isbn13", "pubdate", "rights")
        for (key in keys) {
            val val_ = onlineMeta.opt(key)
            if (val_ != null) {
                when (val_) {
                    is JSONArray -> fields.put(key, val_)
                    is JSONObject -> fields.put(key, val_.toString())
                    else -> fields.put(key, val_.toString())
                }
            }
        }
        return fields
    }

    private fun confirmAndApplyBoth() {
        if (resultsJson.length() == 0 || !isResultOk(selectedResultIndex)) {
            showMessage("No online metadata to apply")
            return
        }
        val cand = selectedCandidate
        if (cand == null || cand.downloadedData.isNullOrBlank()) {
            showMessage("No cover selected for combined apply")
            return
        }

        val online = resultsJson.getJSONObject(selectedResultIndex)
        val onlineMeta = online.optJSONObject("metadata") ?: JSONObject()
        val fields = buildFieldsFromResult(onlineMeta)
        val mime = cand.downloadedMime ?: "image/jpeg"

        MaterialAlertDialogBuilder(this)
            .setTitle("Apply Metadata + Cover?")
            .setMessage("This will write the online metadata and replace the cover in the EPUB using a single atomic write. A backup will be created. Continue?")
            .setPositiveButton("Apply") { _, _ ->
                Thread {
                    // Guard: bail out if the Activity has already been destroyed
                    if (isFinishing || isDestroyed) return@Thread
                    try {
                        val result = applyMetadataAndCoverSafely(
                            fields.toString(), cand.downloadedData, mime, ".metadata_edit_bak"
                        )
                        safeRunOnUiThread {
                            try {
                                val r = JSONObject(result)
                                if (r.optBoolean("ok") && r.optBoolean("metadata_written") && r.optBoolean("cover_written")) {
                                    val changed = r.optJSONArray("changed_fields")
                                    val list = mutableListOf<String>()
                                    if (changed != null) {
                                        for (i in 0 until changed.length()) list.add(changed.getString(i))
                                    }
                                    showMessage("Metadata + cover applied: ${list.joinToString(", ")}")
                                    val out = Intent().apply {
                                        putExtra("title", onlineMeta.optString("title", bookTitle))
                                        putStringArrayListExtra("authors", extractAuthors(onlineMeta))
                                        putExtra("story_refreshed", true)
                                        putExtra("output_path", r.optString("output_path", epubPath))
                                        putExtra("modified", System.currentTimeMillis())
                                    }
                                    setResult(RESULT_OK, out)
                                    finish()
                                } else {
                                    showError("Apply failed: ${r.optString("metadata_error", "unknown")}")
                                }
                            } catch (e: Exception) {
                                showError("Failed to parse result: ${e.message ?: "unknown"}")
                            }
                        }
                    } catch (e: Exception) {
                        safeRunOnUiThread { showError("Apply failed: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmAndApplyCover() {
        val cand = selectedCandidate ?: run {
            showMessage("No cover selected")
            return
        }
        if (cand.downloadedData.isNullOrBlank()) {
            showMessage("Please wait for cover download, or select a cover with an image")
            return
        }
        val mime = cand.downloadedMime ?: "image/jpeg"

        MaterialAlertDialogBuilder(this)
            .setTitle("Apply Cover?")
            .setMessage("This will replace the existing cover in the EPUB with the selected image. A backup will be created. Continue?")
            .setPositiveButton("Apply") { _, _ ->
                Thread {
                    // Guard: bail out if the Activity has already been destroyed
                    if (isFinishing || isDestroyed) return@Thread
                    try {
                        val result = applyMetadataAndCoverSafely(
                            JSONObject().toString(), cand.downloadedData, mime, ".cover_edit_bak"
                        )
                        safeRunOnUiThread {
                            try {
                                val r = JSONObject(result)
                                if (r.optBoolean("ok") && r.optBoolean("cover_written")) {
                                    showMessage("Cover applied successfully")
                                    val out = Intent().apply {
                                        putExtra("story_refreshed", true)
                                        putExtra("output_path", r.optString("output_path", epubPath))
                                        putExtra("modified", System.currentTimeMillis())
                                    }
                                    setResult(RESULT_OK, out)
                                    finish()
                                } else {
                                    showError("Cover apply failed: ${r.optString("metadata_error", "unknown")}")
                                }
                            } catch (e: Exception) {
                                showError("Failed to parse result: ${e.message ?: "unknown"}")
                            }
                        }
                    } catch (e: Exception) {
                        safeRunOnUiThread { showError("Apply failed: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showError(message: String) {
        safeRunOnUiThread {
            showMessage(message)
        }
    }

    /** Run the given block on the UI thread only if the Activity is still
     * active.  Prevents leaked-window / WindowManager crashes and silent
     * failures when background Threads post after the Activity is gone. */
    private fun safeRunOnUiThread(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread(block)
    }

    /**
     * Phase 10: Apply metadata and/or cover to an EPUB, ensuring the result
     * is persisted when the source is a `content://` URI.
     *
     * For `content://` EPUBs, StorageBridge.withLocalEpub() copies the source
     * to a temporary file in filesDir, runs the block, then deletes the temp
     * file. If applyMetadataAndCover() writes in-place to that temp file
     * (output_path=null), the modified EPUB is lost when the temp is deleted.
     *
     * This helper writes the modified EPUB to a separate output file in filesDir
     * (inside the withLocalEpub block), so it survives the temp cleanup.
     * After withLocalEpub returns (temp deleted), it copies the output file
     * to the user's configured output directory via copyToOutputDir().
     *
     * For `file://` / filesystem EPUBs, behavior is unchanged: the EPUB is
     * modified in-place and no additional copy is introduced.
     *
     * Returns the JSON result string from applyMetadataAndCover(), with
     * output_path updated to the persisted output path for content:// sources.
     */
    private fun applyMetadataAndCoverSafely(
        fieldsJson: String,
        imageDataBase64: String?,
        imageMime: String?,
        backupSuffix: String?,
    ): String = LibraryBookMutations.runBlocking(applicationContext, epubPath, expectedBookId = bookId) { mutation ->
        val raw = applyMetadataAndCoverAtPath(mutation.path, fieldsJson, imageDataBase64, imageMime, backupSuffix)
        val result = JSONObject(raw)
        if (result.optBoolean("ok")) {
            mutation.persist(result.optString("output_path").ifBlank { mutation.path })
        }
        result.toString()
    }

    private fun applyMetadataAndCoverAtPath(
        path: String,
        fieldsJson: String,
        imageDataBase64: String?,
        imageMime: String?,
        backupSuffix: String?,
    ): String {
        val isContentUri = path.startsWith("content://")
        return StorageBridge.withLocalEpub(this, path) { localPath ->
            val outputPath: String? = if (isContentUri) {
                // Write to a separate file in filesDir so it survives
                // withLocalEpub()'s temp-file cleanup in its finally block.
                File(localPath.parentFile, "modified_${System.currentTimeMillis()}_${localPath.name}")
                    .absolutePath
            } else {
                null  // file:// — modify in-place as before
            }
            bridge.applyMetadataAndCover(
                localPath.absolutePath,
                fieldsJson,
                imageDataBase64,
                imageMime,
                outputPath,
                backupSuffix
            )
        } ?: run {
            // resolveLocalEpub returned null — cannot read the EPUB
            JSONObject().put("ok", false).put("error", "Cannot read EPUB from this location").toString()
        }.let { resultJson ->
            if (!isContentUri) {
                return resultJson  // file:// — output_path already correct
            }
            // content:// — persist the modified output to the user's output dir
            val r = JSONObject(resultJson)
            val outputPath = r.optString("output_path", "")
            if (!r.optBoolean("ok") || outputPath.isBlank()) {
                return resultJson  // operation failed; no file to persist
            }
            val sourceFile = File(outputPath)
            if (!sourceFile.exists() || !sourceFile.isFile) {
                return resultJson  // output file missing — report as-is
            }
            val outputDir = SettingsActivity.getOutputDir(this)
            return try {
                val finalPath = StorageBridge.copyToOutputDir(this, sourceFile, outputDir)
                // Clean up the filesDir output copy now that it's persisted
                sourceFile.delete()
                JSONObject(r.toString()).put("output_path", finalPath).put(
                    "persisted", true
                ).toString()
            } catch (e: Exception) {
                Log.w("MetadataPreview", "Failed to persist modified EPUB: ${e.message}")
                // Persistence failed: clean up the filesDir temp copy and
                // report failure so the caller does not treat this as success.
                sourceFile.delete()
                JSONObject(r.toString()).put("ok", false).put(
                    "error", "Failed to persist modified EPUB: ${e.message}"
                ).put("output_path", "").toString()
            }
        }
    }
}
