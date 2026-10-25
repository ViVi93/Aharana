package com.vivi.aharana

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vivi.aharana.adapter.BookAdapter
import com.vivi.aharana.model.BookItem
import com.chaquo.python.Python
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import org.json.JSONObject
import java.io.File

class MainActivity : BaseActivity() {

    private lateinit var bookAdapter: BookAdapter
    private var pythonBridge: PythonBridge? = null
    private var selectedBook: BookItem? = null
    private val REQUEST_BOOK_DETAIL = 1003
    private val REQUEST_MERGE_BOOKS = 1005
    private val REQUEST_POST_NOTIFICATIONS = 1004

    private lateinit var viewModel: LibraryViewModel
    private var libraryFolderPath: String? = null
    private var selectionMenu: Menu? = null
    private var baseMenu: Menu? = null

    private val statusProgress get() = findViewById<android.widget.ProgressBar>(R.id.status_progress)
    private val statusText get() = findViewById<TextView>(R.id.status_text)
    private val statusContainer get() = findViewById<android.view.ViewGroup>(R.id.status_container)

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            val t0 = System.currentTimeMillis()
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)
            applyTopInset(findViewById<android.view.View>(R.id.appBar))
            applyBottomInset(findViewById<android.view.View>(R.id.contentContainer))
            supportActionBar?.hide()
            DiagnosticLog.append(this, "Main.Startup", "begin elapsed=${System.currentTimeMillis() - t0}")
            if (!Python.isStarted()) {
                try {
                    Python.start(com.chaquo.python.android.AndroidPlatform(this))
                } catch (e: Exception) {
                    DiagnosticLog.append(this, "Main.Startup", "python_failed")
                }
            }
            DiagnosticLog.append(this, "Main.Startup", "python_ready elapsed=${System.currentTimeMillis() - t0}")

            pythonBridge = PythonBridge(this)
            pythonBridge?.getInitError()?.let { error ->
                DiagnosticLog.append(this, "Main.Startup", "bridge_init_failed")
            }
            pythonBridge?.getFanFicFareError()?.let { error ->
                DiagnosticLog.append(this, "Main.Startup", "fanficfare_init_failed")
            }
            pythonBridge?.initialize(SettingsActivity.getConfigDir(this).absolutePath)
            DiagnosticLog.append(this, "Main.Startup", "bridge_ready elapsed=${System.currentTimeMillis() - t0}")

            val repository = BookRepository(this)
            DiagnosticLog.append(this, "Main.Startup", "repository_ready elapsed=${System.currentTimeMillis() - t0}")

            viewModel = ViewModelProvider(this, ViewModelFactory(repository))[LibraryViewModel::class.java]
            DiagnosticLog.append(this, "Main.Startup", "viewmodel_ready elapsed=${System.currentTimeMillis() - t0}")

            android.util.Log.d("FFF-UI-OBS", "MainActivity direct observer repo=${true}")
            repository.jobDao.observeAll().observe(this) { jobs ->
                android.util.Log.d("FFF-UI-OBS", "MainActivity direct observeAll count=${jobs.size}")
                val latest = jobs.maxByOrNull { it.createdAt }
                android.util.Log.d("FFF-UI-OBS", "MainActivity direct latest job=${latest?.id} status=${latest?.status}")
                if (latest == null) {
                    statusContainer?.visibility = android.view.View.GONE
                    return@observe
                }
                val text = when (latest.status) {
                    "queued" -> "Queued"
                    "waiting" -> "Waiting..."
                    "running" -> "Running..."
                    "success" -> "✓ Complete"
                    "failed" -> "✗ Failed"
                    "cancelled" -> "⏹ Cancelled"
                    else -> latest.status.replaceFirstChar { it.uppercase() }
                }
                statusContainer?.visibility = android.view.View.VISIBLE
                statusProgress?.visibility = if (latest.status == "running") android.view.View.VISIBLE else android.view.View.GONE
                statusText?.text = text
            }

            bookAdapter = BookAdapter(viewModel.getBooksSnapshot(), { book ->
                selectedBook = book
                // In selection mode the adapter intercepts the tap itself.
                showBookOptionsDialog(book)
            }, { book ->
                if (!bookAdapter.isSelectionMode()) {
                    bookAdapter.enterSelectionMode(book)
                    swapMenu(true)
                }
            }, {
                updateSelectionUi()
            }, statusScope = lifecycleScope)
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.bookList).layoutManager = LinearLayoutManager(this)
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.bookList).adapter = bookAdapter
            FastScrollerBuilder(findViewById<RecyclerView>(R.id.bookList))
                .setPopupTextProvider { _, position ->
                    viewModel.getVisibleBooks().getOrNull(position)?.title
                        ?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: ""
                }
                .useMd2Style()
                .build()

            viewModel.configureFilters(applicationContext)
            viewModel.filterProgress.observe(this) { updateFilterSummary() }
            viewModel.filterError.observe(this) { message ->
                if (message.isNotBlank()) toast(message)
            }
            viewModel.visibleBooks.observe(this) { books ->
                syncBooks(books)
            }

            DiagnosticLog.append(this, "Main.Startup", "load_persisted_library_begin elapsed=${System.currentTimeMillis() - t0}")
            loadPersistedLibrary()
            DiagnosticLog.append(this, "Main.Startup", "load_persisted_library_end elapsed=${System.currentTimeMillis() - t0}")
            ensureNotificationPermission()
            DiagnosticLog.append(this, "Main.Startup", "complete elapsed=${System.currentTimeMillis() - t0}")

            viewModel.uiJobState.observe(this) { state ->
                if (state == null) {
                    statusContainer?.visibility = android.view.View.VISIBLE
                    statusProgress?.visibility = android.view.View.GONE
                    statusText?.text = "Ready"
                    return@observe
                }
                statusContainer?.visibility = android.view.View.VISIBLE
                statusProgress?.isIndeterminate = state.indeterminate
                statusProgress?.visibility = if (state.indeterminate) android.view.View.VISIBLE else android.view.View.GONE
                statusText?.text = when {
                    state.finished && state.status == "success" -> "✓ Complete"
                    state.finished && state.status == "failed" -> "✗ Failed"
                    state.finished && state.status == "cancelled" -> "⏹ Cancelled"
                    state.phase.isBlank() -> "Ready"
                    else -> "${state.phase.replaceFirstChar { it.uppercase() }}..."
                }
                if (state.finished && state.status != state.phase) {
                    statusText?.text = state.phase
                }
            }
            val dao = (viewModel as? LibraryViewModel)?.let { 
                try { (it as Any).javaClass.getDeclaredField("repository").let { f -> f.isAccessible = true; f.get(it) } } catch (e: Exception) { null }
            } as? com.vivi.aharana.BookRepository
            android.util.Log.d("FFF-UI-OBS", "MainActivity direct observer repo=${dao != null}")
            dao?.jobDao?.observeAll()?.observe(this) { jobs ->
                android.util.Log.d("FFF-UI-OBS", "MainActivity direct observeAll count=${jobs.size}")
                val latest = jobs.maxByOrNull { it.createdAt }
                android.util.Log.d("FFF-UI-OBS", "MainActivity direct latest job=${latest?.id} status=${latest?.status}")
                if (latest == null) {
                    statusContainer?.visibility = android.view.View.GONE
                    return@observe
                }
                val text = when (latest.status) {
                    "queued" -> "Queued"
                    "waiting" -> "Waiting..."
                    "running" -> "Running..."
                    "success" -> "✓ Complete"
                    "failed" -> "✗ Failed"
                    "cancelled" -> "⏹ Cancelled"
                    else -> latest.status.replaceFirstChar { it.uppercase() }
                }
                statusContainer?.visibility = android.view.View.VISIBLE
                statusProgress?.visibility = if (latest.status == "running") android.view.View.VISIBLE else android.view.View.GONE
                statusText?.text = text
            }

            val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            toolbar.inflateMenu(R.menu.main_menu)
            baseMenu = toolbar.menu
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_download -> {
                        showDownloadDialog()
                        true
                    }
                    R.id.action_add_from_page -> {
                        startActivity(Intent(this, AddFromPageActivity::class.java))
                        true
                    }
                    R.id.action_select -> {
                        enterSelectionMode()
                        true
                    }
                    R.id.action_load_library -> {
                        showLoadLibraryDialog()
                        true
                    }
                    R.id.action_organise_library -> {
                        startActivity(Intent(this, OrganiseLibraryActivity::class.java)
                            .putExtra("library_root", libraryFolderPath ?: SettingsActivity.getOutputDir(this)))
                        true
                    }
                    R.id.action_settings -> {
                        startActivity(android.content.Intent(this, SettingsActivity::class.java))
                        true
                    }
                    R.id.action_diagnostics -> {
                        startActivity(Intent(this, DiagnosticsActivity::class.java))
                        true
                    }
                    R.id.action_queue -> {
                        startActivity(Intent(this, DownloadQueueActivity::class.java))
                        true
                    }
                    R.id.action_refresh_all -> {
                        refreshAllBooks()
                        true
                    }
                    R.id.action_sort -> {
                        showSortDialog()
                        true
                    }
                    R.id.action_filter_library -> {
                        showLibraryFilters()
                        true
                    }
                    R.id.action_search -> {
                        showSearchDialog()
                        true
                    }
                    R.id.action_merge_selected -> {
                        mergeSelectedBooks()
                        true
                    }
                    R.id.action_update_selected -> {
                        updateSelectedBooks()
                        true
                    }
                    R.id.action_cancel_selection -> {
                        clearSelectionMode()
                        true
                    }
                    R.id.action_cancel_download -> {
                        DiagnosticLog.append(this, "Main.Cancel", "cancel_pressed")
                        toast("Cancelling download...")
                        viewModel.cancelCurrentDownload()
                        true
                    }
                    else -> false
                }
            }

            handleSharedUrl(intent)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "onCreate crash", e)
            runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Startup Error")
                    .setMessage("${e.javaClass.simpleName}: ${e.message ?: "null"}")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun handleSharedUrl(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val url = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (url.isBlank() || !url.startsWith("http")) return
        DiagnosticLog.append(this, "Main.Shared", "shared_url=$url")
        toast("Sharing download: ${android.net.Uri.parse(url).host ?: url}")
        viewModel.enqueueDownload(url)
        DiagnosticLog.append(this, "Main.Shared", "enqueued_download url=$url")
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    private fun toast(text: String) {
        showMessage(text)
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            // silently ignore; Worker continues without notification if denied
        }
    }

    private fun showError(message: String) {
        toast(message)
    }

    private fun getPythonBridge(): PythonBridge? = pythonBridge

    private fun updateEmptyState() {
        val emptyState = findViewById<android.view.View>(R.id.emptyState)
        val bookList = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.bookList)
        val hasBooks = bookAdapter.itemCount > 0
        emptyState.visibility = if (hasBooks) android.view.View.GONE else android.view.View.VISIBLE
        bookList.visibility = if (hasBooks) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun syncBooks(books: List<BookItem>) {
        bookAdapter.updateBooks(viewModel.projectLibrary(books))
        updateEmptyState()
        updateFilterSummary()
        // A background update can prune a selected book; keep the count honest.
        if (bookAdapter.isSelectionMode()) updateSelectionUi()
    }

    private fun enterSelectionMode() {
        // Enter with nothing selected: the user chooses what to act on.
        // The adapter fires onSelectionChanged -> updateSelectionUi().
        bookAdapter.enterSelectionMode()
        swapMenu(true)
    }

    private fun updateSelectionUi() {
        if (!bookAdapter.isSelectionMode()) {
            clearSelectionMode()
            return
        }
        val count = bookAdapter.getSelectedBooks().size
        viewModel.setSelectionMode(true)
        // Dedicated row: never write the selection count into the job-status
        // views, or the download observer and this fight over the same line.
        val selectionCount = findViewById<android.widget.TextView>(R.id.selectionCount)
        selectionCount.visibility = android.view.View.VISIBLE
        selectionCount.text = getString(R.string.selection_count_format, count)
    }

    private fun clearSelectionMode() {
        bookAdapter.clearSelection()
        viewModel.clearSelectionState()
        findViewById<android.widget.TextView>(R.id.selectionCount).visibility = android.view.View.GONE
        swapMenu(false)
    }

    private fun updateSelectedBooks() {
        val selected = bookAdapter.getSelectedBooks().filter { it.url.isNotBlank() }
        if (selected.isEmpty()) {
            toast("No updatable books selected")
            return
        }
        toast("Updating ${selected.size} books...")
        for (book in selected) {
            viewModel.enqueueUpdate(book.libraryId, book.uriString)
        }
        clearSelectionMode()
    }

    /**
     * Open the merge screen for the selected books.
     *
     * The adapter does not preserve the order books were tapped in, so the merge
     * screen shows the list and lets it be reordered before merging.
     */
    private fun mergeSelectedBooks() {
        val selected = bookAdapter.getSelectedBooks().filter { it.uriString.isNotBlank() }
        if (selected.size < 2) {
            toast("Select at least two books to merge")
            return
        }
        val intent = Intent(this, MergeBooksActivity::class.java).apply {
            putStringArrayListExtra(
                MergeBooksActivity.EXTRA_PATHS,
                ArrayList(selected.map { it.uriString })
            )
            putStringArrayListExtra(
                MergeBooksActivity.EXTRA_TITLES,
                ArrayList(selected.map { it.title })
            )
            putStringArrayListExtra(
                MergeBooksActivity.EXTRA_AUTHORS,
                ArrayList(selected.map { it.author })
            )
            putStringArrayListExtra(
                MergeBooksActivity.EXTRA_COVERS,
                ArrayList(selected.map { it.coverUriString ?: "" })
            )
        }
        clearSelectionMode()
        startActivityForResult(intent, REQUEST_MERGE_BOOKS)
    }

    private fun swapMenu(selecting: Boolean) {
        val menu = if (selecting) R.menu.selection_menu else R.menu.main_menu
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.menu.clear()
        toolbar.inflateMenu(menu)
    }

    override fun onBackPressed() {
        if (bookAdapter.isSelectionMode()) {
            clearSelectionMode()
            return
        }
        super.onBackPressed()
    }

    private fun onSelectionCountClicked() {
        val selected = bookAdapter.getSelectedBooks()
        if (selected.isEmpty()) return
        clearSelectionMode()
    }

    private fun showBookOptionsDialog(book: BookItem) {
        val options = mutableListOf<String>()
        if (book.url.isNotBlank()) {
            options.add("Update")
            options.add("Force Download")
        }
        options.add("Details")
        options.add("Delete")

        MaterialAlertDialogBuilder(this)
            .setTitle(book.title.ifBlank { "Book" })
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when (choice) {
                    "Update" -> updateBook(book)
                    "Force Download" -> forceDownloadBook(book)
                    "Details" -> openBookDetail(book)
                    "Delete" -> deleteBook(book)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openBookDetail(book: BookItem) {
        val intent = Intent(this, BookDetailActivity::class.java)
            .putExtra("book_id", book.libraryId)
            .putExtra("title", book.title)
            .putExtra("author", book.author)
            .putExtra("path", book.uriString)
            .putExtra("modified", book.lastModified)
            .putExtra("size", book.sizeBytes)
            .putExtra("url", book.url)
            .putExtra("chapters", book.chapters)
        startActivityForResult(intent, REQUEST_BOOK_DETAIL)
    }

    private fun updateBook(book: BookItem) {
        DiagnosticLog.append(this, "Main.Update", "button_pressed title=${book.title} url=${book.url} path=${book.uriString}")
        val url = book.url
        if (url.isBlank()) {
            DiagnosticLog.append(this, "Main.Update", "validation_failed=blank_url")
            toast("No URL found for this book")
            return
        }
        DiagnosticLog.append(this, "Main.Update", "starting")
        viewModel.enqueueUpdate(book.libraryId, book.uriString)
        DiagnosticLog.append(this, "Main.Update", "enqueued path=${book.uriString}")
    }

    private fun forceDownloadBook(book: BookItem) {
        DiagnosticLog.append(this, "Main.ForceDownload", "button_pressed title=${book.title} url=${book.url} path=${book.uriString}")
        val url = book.url
        if (url.isBlank()) {
            DiagnosticLog.append(this, "Main.ForceDownload", "validation_failed=blank_url")
            toast("No URL found for this book")
            return
        }
        DiagnosticLog.append(this, "Main.ForceDownload", "starting")
        viewModel.enqueueForceDownload(book.libraryId, book.uriString)
        DiagnosticLog.append(this, "Main.ForceDownload", "enqueued path=${book.uriString}")
    }

    private fun deleteBook(book: BookItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete")
            .setMessage("Delete ${book.title.ifBlank { "this book" }}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        LibraryBookMutations.run(applicationContext, book.uriString, expectedBookId = book.libraryId) { mutation -> mutation.delete() }
                        viewModel.reloadFromStore()
                        syncBooks(viewModel.getBooksSnapshot())
                        updateEmptyState()
                        toast("Deleted")
                    } catch (e: Exception) {
                        showError("Delete failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshBookPath(oldBook: BookItem, title: String, author: String, path: String) {
        val updated = oldBook.copy(
            title = title,
            author = author,
            uriString = path,
            lastModified = System.currentTimeMillis()
        )
        viewModel.updateBook(oldBook, updated)
        syncBooks(viewModel.getBooksSnapshot())
    }

    private fun showDownloadDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_download, null)
        val input = view.findViewById<EditText>(R.id.inputUrl)
        view.findViewById<android.widget.Button>(R.id.buttonDownload).setOnClickListener {
            val url = input.text.toString().trim()
            if (url.isBlank()) return@setOnClickListener
            viewModel.enqueueDownload(url)
            DiagnosticLog.append(this, "Main.Download", "enqueued url=$url")
        }
        view.findViewById<android.widget.Button>(R.id.buttonUpdate).setOnClickListener {
            val url = input.text.toString().trim()
            if (url.isBlank()) return@setOnClickListener
            lifecycleScope.launch {
                viewModel.enqueueMetadata(url)
                DiagnosticLog.append(this@MainActivity, "Main.Update", "enqueued_metadata url=$url")
            }
        }
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showLoadLibraryDialog() {
        val options = mutableListOf<String>()
        options.add("Current")
        options.add("Change Folder...")
        options.add("Clear Library Folder")

        MaterialAlertDialogBuilder(this)
            .setTitle("EPUB Library")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Current" -> {
                        if (!libraryFolderPath.isNullOrBlank()) {
                            scanSavedLibraryFolder()
                        } else {
                            showError("No library folder configured yet")
                        }
                    }
                    "Change Folder..." -> showManualLibraryDialog()
                    "Clear Library Folder" -> {
                        libraryFolderPath = null
                        saveLibraryLocation()
                        viewModel.clearLibrary()
                        syncBooks(viewModel.getBooksSnapshot())
                        updateEmptyState()
                        toast("Library folder cleared")
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showManualLibraryDialog() {
        val input = EditText(this)
        val libraryHint = libraryFolderPath ?: "/storage/emulated/0/Download"
        input.hint = libraryHint
        MaterialAlertDialogBuilder(this)
            .setTitle("Load EPUB Library")
            .setMessage("Enter a library folder path to scan.")
            .setView(input)
            .setPositiveButton("Scan") { _, _ ->
                val dir = input.text.toString().trim()
                if (dir.isBlank()) return@setPositiveButton
                val folder = java.io.File(dir)
                if (!folder.exists() || !folder.isDirectory) {
                    showError("Folder not found: $dir")
                    return@setPositiveButton
                }
                libraryFolderPath = folder.absolutePath
                saveLibraryLocation()
                scanManualEpubDir(folder)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scanSavedLibraryFolder() {
        toast("Scanning saved library folder...")
        val path = libraryFolderPath
        if (path.isNullOrBlank()) {
            showError("No saved library folder path")
            return
        }
        val folder = java.io.File(path)
        if (!folder.exists() || !folder.isDirectory) {
            showError("Saved library folder not found: $path")
            return
        }
        scanManualEpubDir(folder)
    }

    private fun scanManualEpubDir(folder: java.io.File) {
        toast("Scanning ${folder.absolutePath}...")
        lifecycleScope.launch {
            try {
                val count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    LibraryStorageGate.run {
                        BookRepository.reconcileOrganisedPaths(applicationContext)
                        val result = JSONObject(pythonBridge?.scanEpubDir(folder.absolutePath)
                            ?: error("Bridge not available"))
                        check(result.optBoolean("ok")) { result.optString("error", "Scan failed") }
                        viewModel.importOrganisedLibrary(
                            result.optJSONArray("books") ?: org.json.JSONArray())
                    }
                }
                viewModel.reloadFromStore()
                syncBooks(viewModel.getBooksSnapshot())
                updateEmptyState()
                toast(if (count == 0) "No readable EPUBs found (including subfolders)" else "Loaded $count EPUBs")
            } catch (e: Exception) {
                showError("Scan failed: ${e.message}")
                DiagnosticLog.appendException(applicationContext, "Library.Scan", "scan_failed", e)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BOOK_DETAIL && resultCode == RESULT_OK && data != null) {
            if (data.getBooleanExtra("story_refreshed", false)) {
                // StoryRefreshStore is the sole writer. In particular do not clear/reinsert
                // a stale Activity snapshot over the updated Room row or its new cover.
                lifecycleScope.launch {
                    viewModel.reloadFromStore()
                    syncBooks(viewModel.getBooksSnapshot())
                    updateEmptyState()
                }
                return
            }
            val deleted = data.getBooleanExtra("deleted", false)
            if (deleted) {
                val position = selectedBook?.let { viewModel.findByIdentity(it) } ?: -1
                if (position >= 0) {
                    val current = viewModel.getBooksSnapshot().toMutableList()
                    current.removeAt(position)
                    viewModel.setBooks(current)
                    syncBooks(viewModel.getBooksSnapshot())
                    updateEmptyState()
                    viewModel.saveLibrary()
                }
                return
            }

            val title = data.getStringExtra("title") ?: ""
            val author = data.getStringExtra("author") ?: ""
            val path = data.getStringExtra("path") ?: ""
            val modified = data.getLongExtra("modified", 0L)
            val book = selectedBook
            if (book != null && path.isNotBlank()) {
                val updated = book.copy(title = title, author = author, uriString = path, lastModified = modified)
                viewModel.updateBook(book, updated)
                syncBooks(viewModel.getBooksSnapshot())
                viewModel.saveLibrary()
            }
            return
        }

        if (requestCode == REQUEST_MERGE_BOOKS && resultCode == RESULT_OK) {
            // The worker is the only writer for a merge and has already upserted
            // the new book. Re-read the store instead of inserting a second copy
            // here: two writers racing is what produced duplicate library rows.
            lifecycleScope.launch {
                viewModel.reloadFromStore()
                syncBooks(viewModel.getBooksSnapshot())
                updateEmptyState()
            }
        }
    }

    private fun saveLibraryLocation() {
        val prefs = getSharedPreferences("fanficfare_prefs", MODE_PRIVATE)
        val editor = prefs.edit()
        if (!libraryFolderPath.isNullOrBlank()) {
            editor.putString("library_folder_path", libraryFolderPath)
        } else {
            editor.remove("library_folder_path")
        }
        editor.apply()
    }

    private fun loadLibraryLocation() {
        val prefs = getSharedPreferences("fanficfare_prefs", MODE_PRIVATE)
        libraryFolderPath = prefs.getString("library_folder_path", null)
    }

    private fun loadPersistedLibrary() {
        loadLibraryLocation()
        val success = viewModel.loadLibrary()
        if (success) {
            viewModel.setSort(viewModel.getCurrentSort())
        }
        syncBooks(viewModel.getBooksSnapshot())
        updateEmptyState()
    }

    private fun copyToOutputDir(sourceFile: File, outputDir: String): String {
        return StorageBridge.copyToOutputDir(this, sourceFile, outputDir)
    }

    private fun json(text: String): JSONObject? {
        return try { JSONObject(text) } catch (e: Exception) { null }
    }

    private fun applySort(books: List<BookItem>, sort: String): List<BookItem> {
        return when (sort) {
            "title" -> books.sortedBy { it.title.lowercase() }
            "author" -> books.sortedBy { it.author.lowercase() }
            "chapters" -> books.sortedByDescending { it.chapters }
            "size" -> books.sortedByDescending { it.sizeBytes }
            else -> books.sortedByDescending { it.lastModified }
        }
    }

    /**
     * The order for a sort key, in the user's own terms. "Newest first" only
     * means anything for the date sort, so every key names its own two orders.
     */
    private fun sortOrderLabel(sort: String, descending: Boolean): String = when (sort) {
        "title", "author" -> if (descending) "Z to A" else "A to Z"
        "chapters" -> if (descending) "Most chapters first" else "Fewest chapters first"
        "size" -> if (descending) "Largest first" else "Smallest first"
        else -> if (descending) "Newest first" else "Oldest first"
    }

    private fun showSortDialog() {
        val options = arrayOf("Recent", "Title", "Author", "Chapters", "Size")
        val sortKeys = arrayOf("modified", "title", "author", "chapters", "size")
        val checked = sortKeys.indexOf(viewModel.getCurrentSort()).coerceAtLeast(0)

        val dialogView = layoutInflater.inflate(R.layout.dialog_sort_direction, null)
        val toggle = dialogView.findViewById<MaterialButton>(R.id.sortDirectionToggle)

        fun refreshOrder() {
            val descending = viewModel.isSortDirectionDescending()
            toggle.text = sortOrderLabel(viewModel.getCurrentSort(), descending)
            toggle.setIconResource(
                if (descending) R.drawable.ic_sort_descending else R.drawable.ic_sort_ascending
            )
        }

        refreshOrder()
        toggle.setOnClickListener {
            viewModel.flipSortDirection()
            refreshOrder()
            syncBooks(viewModel.getVisibleBooks())
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Sort By")
            .setView(dialogView)
            .setSingleChoiceItems(options, checked) { _, which ->
                viewModel.setSort(sortKeys[which])
                // The order wording depends on the key, so relabel on every change.
                refreshOrder()
                syncBooks(viewModel.getBooksSnapshot())
            }
            .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showLibraryFilters() {
        LibraryFilterDialog(this, viewModel).show()
    }

    private fun updateFilterSummary() {
        val selected = viewModel.getLibraryFilters()
        val search = viewModel.searchQuery.value.orEmpty()
        val visible = bookAdapter.itemCount
        val total = viewModel.getBooksSnapshot().size
        val active = selected.entries.joinToString(" · ") { (key, values) ->
            "${LibraryFilters.categories[key]}: ${values.joinToString(", ")}"
        }
        val progress = viewModel.filterProgress.value.orEmpty()
        val summary = findViewById<android.widget.TextView>(R.id.libraryFilterSummary)
        summary.visibility = if (progress.isNotBlank() || selected.isNotEmpty() || search.isNotBlank())
            android.view.View.VISIBLE else android.view.View.GONE
        summary.text = when {
            progress.isNotBlank() -> "Filters · $progress"
            selected.isNotEmpty() -> "Filters · $visible/$total books · $active"
            search.isNotBlank() -> "Filters · $visible/$total books · search active"
            else -> "Filter library · $total books"
        }
        val narrowed = selected.isNotEmpty() || search.isNotBlank()
        val presentation = LibraryListPresentation.choose(visible, viewModel.isRestoringLibrary(), progress.isNotBlank(), narrowed,
            indexReady = viewModel.filterIndexReady())
        val waiting = presentation == LibraryListPresentation.LOADING || presentation == LibraryListPresentation.REFRESHING
        findViewById<android.widget.TextView>(R.id.textEmptyLibraryTitle).text =
            when (presentation) {
                LibraryListPresentation.LOADING -> "Loading saved library…"
                LibraryListPresentation.REFRESHING -> if (viewModel.filterIndexReady()) "Checking for changes…" else "Preparing library…"
                LibraryListPresentation.NO_MATCHES -> "No matching books"
                LibraryListPresentation.UNAVAILABLE -> "Filter index unavailable"
                else -> getString(R.string.empty_library_title)
            }
        findViewById<android.widget.TextView>(R.id.textEmptyLibrarySubtitle).text =
            when {
                waiting -> "Your saved filters are kept. Results will appear when the index is ready."
                presentation == LibraryListPresentation.UNAVAILABLE -> "Saved filters are kept. Open Filter library to retry or clear filters."
                narrowed -> "Change library filters or clear Search to show more books."
                else -> getString(R.string.empty_library_subtitle)
            }
        findViewById<android.view.View>(R.id.buttonEmptyDownload).visibility =
            if (waiting) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun showSearchDialog() {
        val input = EditText(this)
        input.hint = "Search title or author"
        input.setText(viewModel.searchQuery.value.orEmpty())
        MaterialAlertDialogBuilder(this)
            .setTitle("Search")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                viewModel.setSearchQuery(query.ifBlank { null })
                syncBooks(viewModel.getVisibleBooks())
            }
            .setNegativeButton("Clear") { _, _ ->
                viewModel.setSearchQuery(null)
                syncBooks(viewModel.getVisibleBooks())
            }
            .show()
    }

    private fun refreshAllBooks() {
        if (viewModel.hasRunningJob()) {
            toast("Download in progress, try again later")
            return
        }
        val updatable = viewModel.getBooksSnapshot().filter { it.url.isNotBlank() }
        if (updatable.isEmpty()) {
            toast("No updatable books")
            return
        }
        // Use the canonical worker path: it resolves the current Room path under
        // LibraryStorageGate and replaces that exact file, including author folders.
        // The old loop copied results back to the output root and rewrote the table.
        for (book in updatable) viewModel.enqueueUpdate(book.libraryId, book.uriString)
        toast("Queued updates for ${updatable.size} books. See Queue for progress.")
    }
}
