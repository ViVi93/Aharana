package com.vivi.aharana

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Page discovery, local file import and pasted lists share one persisted preview. */
class AddFromPageActivity : BaseActivity() {
    companion object {
        private const val MAX_INPUT_BYTES = 1024 * 1024
    }

    private lateinit var inputUrl: TextInputEditText
    private lateinit var checkNormalize: MaterialCheckBox
    private lateinit var buttonFetch: MaterialButton
    private lateinit var buttonImportFile: MaterialButton
    private lateinit var buttonPasteUrls: MaterialButton
    private lateinit var buttonDownloadSelected: MaterialButton
    private lateinit var buttonUpdateExisting: MaterialButton
    private lateinit var buttonSkipExisting: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var textStatus: TextView
    private lateinit var textSeriesInfo: TextView
    private lateinit var textEmpty: TextView
    private lateinit var repository: BookRepository
    private lateinit var viewModel: LibraryViewModel
    private var pythonBridge: PythonBridge? = null
    private var fetchJob: Job? = null
    private var metadataJob: Job? = null
    private var previewJob: Job? = null
    private var busy = false
    private var sourceLabel = ""
    private var metadataPaused = false
    private val adapter = StoryCheckAdapter()
    private val previewPrefs by lazy { getSharedPreferences("bulk_import", MODE_PRIVATE) }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fetchJob?.cancel()
            fetchJob = lifecycleScope.launch {
                setLoading(true)
                try {
                    val text = withContext(Dispatchers.IO) {
                        val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                            val buffer = ByteArray(MAX_INPUT_BYTES + 1)
                            var used = 0
                            while (used < buffer.size) {
                                val count = stream.read(buffer, used, buffer.size - used)
                                if (count < 0) break
                                if (count == 0) error("File provider returned no data")
                                used += count
                            }
                            buffer.copyOf(used)
                        } ?: error("Cannot read the selected file")
                        require(bytes.size <= MAX_INPUT_BYTES) { "URL list exceeds 1 MiB" }
                        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    }
                    parseUrlText(text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    status("Import failed: ${e.message}")
                    showMessage("Import failed: ${e.message}")
                } finally {
                    setLoading(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_from_page)
        applyTopInset(findViewById(R.id.appBar))
        applyBottomInset(findViewById(R.id.recyclerStories))
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        inputUrl = findViewById(R.id.inputUrl)
        checkNormalize = findViewById(R.id.checkNormalize)
        buttonFetch = findViewById(R.id.buttonFetch)
        buttonImportFile = findViewById(R.id.buttonImportFile)
        buttonPasteUrls = findViewById(R.id.buttonPasteUrls)
        buttonDownloadSelected = findViewById(R.id.buttonDownloadSelected)
        buttonUpdateExisting = findViewById(R.id.buttonUpdateExisting)
        buttonSkipExisting = findViewById(R.id.buttonSkipExisting)
        progressBar = findViewById(R.id.progressBar)
        textStatus = findViewById(R.id.textStatus)
        textSeriesInfo = findViewById(R.id.textSeriesInfo)
        textEmpty = findViewById(R.id.textEmpty)
        findViewById<RecyclerView>(R.id.recyclerStories).apply {
            layoutManager = LinearLayoutManager(this@AddFromPageActivity)
            adapter = this@AddFromPageActivity.adapter
        }
        try {
            if (!com.chaquo.python.Python.isStarted()) {
                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this))
            }
            pythonBridge = PythonBridge(this).also {
                it.initialize(SettingsActivity.getConfigDir(this).absolutePath)
            }
        } catch (e: Exception) {
            DiagnosticLog.appendException(this, "BulkImport", "bridge_init_failed", e)
            status("Python unavailable: ${e.message}")
        }
        repository = BookRepository(this)
        viewModel = ViewModelProvider(this, ViewModelFactory(repository))[LibraryViewModel::class.java]
        restorePreviewState()
        intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotBlank() }?.let {
            inputUrl.setText(it)
        }
        buttonFetch.setOnClickListener {
            val page = inputUrl.text.toString().trim()
            if (page.isBlank()) showMessage("Enter a page URL or use Paste URL list")
            else fetchStoryList(page, checkNormalize.isChecked)
        }
        buttonImportFile.setOnClickListener {
            try {
                filePicker.launch(arrayOf("text/*", "application/octet-stream"))
            } catch (e: Exception) {
                showMessage("Cannot open file picker: ${e.message}")
            }
        }
        buttonPasteUrls.setOnClickListener { showPasteUrlsDialog() }
        findViewById<MaterialButton>(R.id.buttonSelectAll).setOnClickListener {
            adapter.submitList(adapter.currentList.map { it.copy(checked = it.state != "queued") })
            savePreviewState()
        }
        findViewById<MaterialButton>(R.id.buttonSelectNone).setOnClickListener {
            adapter.submitList(adapter.currentList.map { it.copy(checked = false) })
            savePreviewState()
        }
        buttonSkipExisting.setOnClickListener { skipExisting() }
        buttonDownloadSelected.setOnClickListener { queueSelected("new") }
        buttonUpdateExisting.setOnClickListener { queueSelected("update") }
        viewModel.latestJobs.observe(this) { jobs ->
            applyMetadataResults(jobs)
            refreshLibraryStates()
        }
        repository.books.observe(this) { refreshLibraryStates() }
        checkNormalize.setOnCheckedChangeListener { _, _ -> savePreviewState() }
        updateActions()
        if (adapter.currentList.isNotEmpty()) startLazyMetadata()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.add_from_page_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.action_cancel_metadata -> {
            metadataPaused = true
            metadataJob?.cancel()
            repository.cancelBulkMetadata()
            savePreviewState()
            status("Metadata fetch cancelled; import/fetch again to retry")
            true
        }
        R.id.action_clear_saved -> {
            if (!busy) {
                metadataJob?.cancel()
                previewJob?.cancel()
                adapter.submitList(emptyList())
                inputUrl.setText("")
                sourceLabel = ""
                previewPrefs.edit().remove("state").apply()
                // Remove legacy state as well, otherwise it would be migrated again.
                File(filesDir, "add_from_page/add_from_page_items.json").delete()
                textSeriesInfo.visibility = View.GONE
                status("Cleared saved entries")
                updateActions()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onPause() {
        savePreviewState()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // The preview is persisted on disk, never parcel thousands of rows into Binder.
        savePreviewState()
        super.onSaveInstanceState(outState)
    }

    private fun fetchStoryList(page: String, normalize: Boolean) {
        fetchJob?.cancel()
        fetchJob = lifecycleScope.launch {
            setLoading(true)
            status("Fetching stories…")
            try {
                val result = withContext(Dispatchers.IO) { safeJson(pythonBridge?.listStoryUrls(page, normalize)) }
                if (result == null || !result.optBoolean("ok")) {
                    status("Fetch failed: ${result?.optString("error") ?: "Bridge unavailable"}")
                    return@launch
                }
                val urls = jsonStringList(result, "urllist")
                // The same offline parser normalizes and deduplicates every input route.
                val parsed = withContext(Dispatchers.IO) {
                    safeJson(pythonBridge?.parseStoryUrlText(urls.joinToString("\n"), normalize))
                }
                if (parsed == null || !parsed.optBoolean("ok")) {
                    status("Preview failed: ${parsed?.optString("error") ?: "Bridge unavailable"}")
                    return@launch
                }
                val valid = jsonStringList(parsed, "urllist")
                if (valid.isEmpty()) { status("No supported story URLs found on this page"); return@launch }
                showUrlPreview(valid, "Found ${valid.size} stories", page)
                val name = result.optString("name")
                val desc = result.optString("desc")
                if (name.isNotBlank()) {
                    sourceLabel = "Series: $name"
                    showSourceLabel()
                    savePreviewState()
                }
                if (desc.isNotBlank()) MaterialAlertDialogBuilder(this@AddFromPageActivity)
                    .setTitle(name.ifBlank { "Series" }).setMessage(desc).setPositiveButton("OK", null).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                status("Fetch failed: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showPasteUrlsDialog() {
        val input = EditText(this).apply {
            hint = "One story URL per line"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            maxLines = 8
            gravity = android.view.Gravity.TOP
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Paste story URLs").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Preview", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString()
                when {
                    text.isBlank() -> input.error = "Paste at least one story URL"
                    text.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES -> input.error = "URL list exceeds 1 MiB"
                    else -> {
                        dialog.dismiss()
                        fetchJob?.cancel()
                        fetchJob = lifecycleScope.launch {
                            setLoading(true)
                            try { parseUrlText(text) } finally { setLoading(false) }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private suspend fun parseUrlText(text: String) {
        val normalize = checkNormalize.isChecked
        val result = try {
            withContext(Dispatchers.IO) { safeJson(pythonBridge?.parseStoryUrlText(text, normalize)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            status("Import failed: ${e.message}")
            return
        }
        if (result == null || !result.optBoolean("ok")) {
            status("Import failed: ${result?.optString("error") ?: "Bridge unavailable"}")
            return
        }
        val urls = jsonStringList(result, "urllist")
        if (urls.isEmpty()) {
            status("No supported story URLs found; ${result.optInt("invalid_count")} invalid entries")
            return
        }
        showUrlPreview(urls, "Imported ${urls.size} stories; ${result.optInt("duplicates_removed")} duplicates removed; ${result.optInt("invalid_count")} invalid entries")
    }

    private fun showUrlPreview(urls: List<String>, summary: String, page: String = "") {
        metadataJob?.cancel()
        previewJob?.cancel()
        metadataPaused = false
        inputUrl.setText(page)
        sourceLabel = summary
        showSourceLabel()
        adapter.submitList(urls.map { StoryItem(it) })
        status(summary)
        savePreviewState()
        updateActions()
        refreshLibraryStates()
        startLazyMetadata()
    }

    private fun skipExisting() {
        val urls = adapter.currentList.map { it.url }
        lifecycleScope.launch {
            setLoading(true)
            try {
                val states = withContext(Dispatchers.IO) { repository.previewBulkUrls(urls) }
                if (adapter.currentList.map { it.url } != urls) return@launch
                adapter.submitList(adapter.currentList.map { item ->
                    val state = states[item.url] ?: "new"
                    item.copy(state = state, checked = item.checked && state == "new")
                })
                savePreviewState()
                status("Existing and already queued stories deselected")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                status("Cannot skip existing: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun refreshLibraryStates() {
        previewJob?.cancel()
        if (adapter.currentList.isEmpty()) return
        val urls = adapter.currentList.map { it.url }
        previewJob = lifecycleScope.launch {
            delay(100)
            try {
                val states = withContext(Dispatchers.IO) { repository.previewBulkUrls(urls) }
                if (adapter.currentList.map { it.url } != urls) return@launch
                adapter.submitList(adapter.currentList.map { item ->
                    val state = states[item.url] ?: "new"
                    item.copy(state = state, checked = item.checked && state != "queued")
                })
                savePreviewState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                status("Cannot check library: ${e.message}. Queue actions will recheck before starting.")
            }
        }
    }

    private fun applyMetadataResults(jobs: List<com.vivi.aharana.data.local.DownloadJobEntity>) {
        val latest = jobs.filter { it.type == "metadata" && !it.inputUrl.isNullOrBlank() }
            .groupBy { it.inputUrl!! }.mapValues { it.value.maxByOrNull { job -> job.createdAt }!! }
        var changed = false
        // Always derive from CURRENT rows, never a snapshot captured before a checkbox click.
        val rows = adapter.currentList.map { item ->
            val job = latest[item.url]
            if (item.title.isBlank() && job?.status == "success") {
                val meta = safeJson(job.resultJson)
                if (meta?.optBoolean("ok") == true) {
                    changed = true
                    item.copy(title = meta.optString("title").ifBlank { item.url }, chapters = meta.optInt("chapters"))
                } else item
            } else item
        }
        if (changed) { adapter.submitList(rows); savePreviewState() }
    }

    private fun startLazyMetadata() {
        if (metadataPaused) return
        metadataJob?.cancel()
        val urls = adapter.currentList.filter { it.title.isBlank() }.map { it.url }
        if (urls.isEmpty()) return
        metadataJob = lifecycleScope.launch {
            try {
                val jobs = withContext(Dispatchers.IO) { repository.getAllDownloadJobs() }
                applyMetadataResults(jobs)
                withContext(Dispatchers.IO) { repository.ensureBulkMetadata(urls) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                status("Preview ready; metadata lookup failed: ${e.message}")
            }
        }
    }

    private fun queueSelected(mode: String) {
        val selected = adapter.currentList.filter { it.checked }.map { it.url }
        if (selected.isEmpty()) { showMessage("No stories selected"); return }
        lifecycleScope.launch {
            setLoading(true)
            try {
                val result = withContext(Dispatchers.IO) { repository.enqueueBulkUrls(selected, mode) }
                val summary = "Downloads: ${result.downloads}, updates: ${result.updates}, skipped: ${result.skipped}, failed: ${result.failed}"
                status(summary)
                showMessage(summary)
                refreshLibraryStates()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                status("Queue failed: ${e.message}")
                showMessage("Queue failed: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun savePreviewState() {
        if (!::inputUrl.isInitialized) return
        val rows = JSONArray()
        for (item in adapter.currentList) rows.put(JSONObject().apply {
            put("url", item.url); put("title", item.title); put("chapters", item.chapters); put("checked", item.checked)
        })
        val root = JSONObject().apply {
            put("pageUrl", inputUrl.text.toString()); put("normalize", checkNormalize.isChecked)
            put("sourceLabel", sourceLabel); put("items", rows)
            put("metadataPaused", metadataPaused)
        }
        // SharedPreferences applies in memory immediately and serializes disk writes atomically.
        previewPrefs.edit().putString("state", root.toString()).apply()
    }

    private fun restorePreviewState() {
        try {
            val legacy = File(filesDir, "add_from_page/add_from_page_items.json")
            val text = previewPrefs.getString("state", null) ?: if (legacy.exists()) legacy.readText() else return
            val root = JSONObject(text)
            inputUrl.setText(root.optString("pageUrl"))
            checkNormalize.isChecked = root.optBoolean("normalize", true)
            sourceLabel = root.optString("sourceLabel")
            metadataPaused = root.optBoolean("metadataPaused", false)
            val rows = root.optJSONArray("items") ?: return
            val items = (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                val url = row.optString("url")
                if (url.isBlank()) null else StoryItem(url, row.optString("title"), row.optInt("chapters"), row.optBoolean("checked", true))
            }
            adapter.submitList(items.distinctBy { it.url })
            showSourceLabel()
        } catch (e: Exception) {
            status("Cannot restore saved preview: ${e.message}")
        }
    }

    private fun showSourceLabel() {
        textSeriesInfo.text = sourceLabel
        textSeriesInfo.visibility = if (sourceLabel.isBlank()) View.GONE else View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        busy = loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        buttonFetch.isEnabled = !loading
        buttonImportFile.isEnabled = !loading
        buttonPasteUrls.isEnabled = !loading
        inputUrl.isEnabled = !loading
        checkNormalize.isEnabled = !loading
        buttonDownloadSelected.isEnabled = !loading
        buttonUpdateExisting.isEnabled = !loading
        buttonSkipExisting.isEnabled = !loading
    }

    private fun updateActions() {
        val hasRows = adapter.itemCount > 0
        textEmpty.visibility = if (hasRows) View.GONE else View.VISIBLE
        findViewById<View>(R.id.selectionBar).visibility = if (hasRows) View.VISIBLE else View.GONE
    }

    private fun status(message: String) {
        textStatus.text = message
        textStatus.visibility = View.VISIBLE
    }

    private fun safeJson(raw: String?): JSONObject? = try {
        if (raw.isNullOrBlank()) null else JSONObject(raw)
    } catch (_: Exception) { null }

    private fun jsonStringList(obj: JSONObject, key: String): List<String> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotBlank() }
    }

    private data class StoryItem(val url: String, val title: String = "", val chapters: Int = 0,
                                 val checked: Boolean = true, val state: String = "checking")

    private inner class StoryCheckAdapter : RecyclerView.Adapter<StoryCheckAdapter.StoryVH>() {
        private var rows: List<StoryItem> = emptyList()
        val currentList: List<StoryItem> get() = rows
        fun submitList(items: List<StoryItem>) { rows = items.toList(); notifyDataSetChanged() }
        override fun getItemCount(): Int = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryVH = StoryVH(
            LayoutInflater.from(parent.context).inflate(R.layout.row_story_item, parent, false))
        override fun onBindViewHolder(holder: StoryVH, position: Int) = holder.bind(rows[position])
        inner class StoryVH(view: View) : RecyclerView.ViewHolder(view) {
            private val check = view.findViewById<MaterialCheckBox>(R.id.checkSelected)
            fun bind(item: StoryItem) {
                itemView.findViewById<TextView>(R.id.textTitle).text = item.title.ifBlank { item.url }
                val label = when (item.state) { "queued" -> "Already queued"; "library" -> "Already in library"; "new" -> "New"; else -> "Checking library…" }
                itemView.findViewById<TextView>(R.id.textMeta).text = "$label${if (item.chapters > 0) " · ${item.chapters} chapters" else ""}\n${item.url}"
                check.setOnCheckedChangeListener(null)
                check.isChecked = item.checked
                check.isEnabled = item.state != "queued" && item.state != "checking"
                check.setOnCheckedChangeListener { _, checked ->
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        rows = rows.mapIndexed { i, row -> if (i == position) row.copy(checked = checked) else row }
                        savePreviewState()
                    }
                }
                itemView.setOnClickListener { if (check.isEnabled) check.isChecked = !check.isChecked }
            }
        }
    }
}
