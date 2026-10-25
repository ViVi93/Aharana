package com.vivi.aharana

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivi.aharana.data.local.DownloadJobEntity
import com.vivi.aharana.model.BookItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import androidx.lifecycle.Observer

class JobUiState(
    val jobId: Long = 0,
    val type: String = "",
    val status: String = "",
    val phase: String = "",
    val indeterminate: Boolean = true,
    val finished: Boolean = false
)

class LibraryViewModel(private val repository: BookRepository) : ViewModel() {

    val books: LiveData<List<BookItem>> = repository.books
    val latestJobs: LiveData<List<DownloadJobEntity>> = repository.latestJobs
    private val _uiJobState = MediatorLiveData<JobUiState?>(null)
    val uiJobState: LiveData<JobUiState?> = _uiJobState
    private val _currentSort = MutableLiveData<String>("modified")
    val currentSort: LiveData<String> = _currentSort
    private val _searchQuery = MutableLiveData<String?>(null)
    val searchQuery: LiveData<String?> = _searchQuery
    private val _visibleBooks = MutableLiveData<List<BookItem>>(emptyList())
    val visibleBooks: LiveData<List<BookItem>> = _visibleBooks

    // Declared BEFORE init on purpose: Kotlin runs property initializers and
    // init blocks in declaration order, so anything declared after init is
    // still null when init runs. The init block below restores the saved sort
    // direction, so this state must already exist by then.
    private var _sortDirection = true
    private val _sortDirectionLive = MutableLiveData<Boolean>(true)
    val sortDirection: LiveData<Boolean> = _sortDirectionLive

    private var facetLoader: LibraryFacetLoader? = null
    private var restoreFiltersJob: Job? = null
    private var reconcileJob: Job? = null
    private val libraryReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var filtersRestored = false
    private var userChoseFilters = false
    val filterProgress = MutableLiveData("")
    val filterError = MutableLiveData("")
    private val filterState by lazy {
        LibraryFilterState(viewModelScope, repository::getBooksSnapshot, ::signature,
            read = { book: BookItem -> checkNotNull(facetLoader).read(book) },
            onChanged = { recomputeVisible() },
            onProgress = { filterProgress.value = it },
            onError = { filterError.value = it },
            persistSelection = { checkNotNull(facetLoader).saveSelection(it) },
            readFresh = { book: BookItem -> checkNotNull(facetLoader).read(book, force = true) },
            loadCached = { rows -> checkNotNull(facetLoader).cached(rows) },
            identity = { it.uriString })
    }
    val filterReadFailures: Int get() = filterState.failures
    private val booksObserver = Observer<List<BookItem>> { books ->
        recomputeVisible(books)
        filterState.booksChanged()
        if (filtersRestored) facetLoader?.let { loader ->
            loader.setMembership(books)
            reconcileJob?.cancel()
            reconcileJob = viewModelScope.launch { loader.reconcile() }
        }
    }

    init {
        repository.getSavedSort()?.let { _currentSort.value = it }
        _sortDirection = repository.getSavedSortDirection()
        _sortDirectionLive.value = _sortDirection
        android.util.Log.d("FFF-UI-OBS", "LibraryViewModel init observer")
        _uiJobState.addSource(repository.latestJobs) { jobs ->
            android.util.Log.d("FFF-UI-OBS", "latestJobs fired count=${jobs.size}")
            val latest = jobs.maxWithOrNull(compareBy<DownloadJobEntity> { it.finishedAt ?: it.createdAt }.thenBy { it.id })
            android.util.Log.d("FFF-UI-OBS", "selected job=${latest?.id} status=${latest?.status} finished=${latest?.status in setOf("success","failed","cancelled")}")
            android.util.Log.d("FFF-UI", "selected job=${latest?.id} status=${latest?.status} finished=${latest?.status in setOf("success","failed","cancelled")}")
            val terminal = latest?.status?.let { it == "success" || it == "failed" || it == "cancelled" } ?: false
            if (latest == null) {
                _lastNotifiedTerminalJobId = null
                _uiJobState.value = null
                return@addSource
            }
            if (terminal) {
                _lastNotifiedTerminalJobId = latest.id
                _uiJobState.value = JobUiState(
                    jobId = latest.id,
                    type = latest.type,
                    status = latest.status,
                    phase = humanizeJobStatus(latest.status),
                    indeterminate = false,
                    finished = true
                )
                _observedNonTerminalJobIds.remove(latest.id)
            } else {
                _lastNotifiedTerminalJobId = null
                _observedNonTerminalJobIds.add(latest.id)
                _uiJobState.value = JobUiState(
                    jobId = latest.id,
                    type = latest.type,
                    status = latest.status,
                    phase = humanizeJobStatus(latest.status),
                    indeterminate = true,
                    finished = false
                )
            }
        }
        repository.books.observeForever(booksObserver)
    }

    private var _lastNotifiedTerminalJobId: Long? = null
    private val _observedNonTerminalJobIds = mutableSetOf<Long>()

    fun getCurrentSort(): String = _currentSort.value ?: "modified"

    fun setSort(sort: String) {
        _currentSort.value = sort
        repository.setSavedSort(sort)
        repository.setSavedSortDirection(_sortDirection)
        recomputeVisible() // Sorting is a projection, never a Room rewrite.
    }

    fun setSearchQuery(query: String?) {
        _searchQuery.value = query
        recomputeVisible()
    }

    fun setSortDirection(descending: Boolean) {
        _sortDirection = descending
        _sortDirectionLive.value = descending
        // Persist here as well as in setSort(): flipping the direction is its own
        // change, and without this it only survived until the process died — the
        // next launch restored whatever setSort() last wrote, or the default.
        repository.setSavedSortDirection(descending)
        val current = repository.getBooks().toList()
        recomputeVisible(current)
    }

    fun flipSortDirection() {
        setSortDirection(!_sortDirection)
    }

    fun isSortDirectionDescending(): Boolean = _sortDirection

    private fun sorted(books: List<BookItem>): List<BookItem> {
        val sort = _currentSort.value ?: "modified"
        return when (sort) {
            "title" -> if (_sortDirection) books.sortedByDescending { it.title.lowercase() } else books.sortedBy { it.title.lowercase() }
            "author" -> if (_sortDirection) books.sortedByDescending { it.author.lowercase() } else books.sortedBy { it.author.lowercase() }
            "chapters" -> if (_sortDirection) books.sortedByDescending { it.chapters } else books.sortedBy { it.chapters }
            "size" -> if (_sortDirection) books.sortedByDescending { it.sizeBytes } else books.sortedBy { it.sizeBytes }
            else -> if (_sortDirection) books.sortedByDescending { it.lastModified } else books.sortedBy { it.lastModified }
        }
    }

    fun recomputeVisible(books: List<BookItem> = repository.getBooks().toList()) {
        _visibleBooks.value = projectLibrary(books)
    }

    fun projectLibrary(books: List<BookItem>): List<BookItem> {
        if (!filtersRestored) return emptyList() // Do not flash an unfiltered list during saved-choice restore.
        val selection = filterState.selection
        // While refreshing, retain the previous coherent book/index pair. Never
        // interpret a newly added/unscanned book as a negative filter match.
        val existingPaths = repository.getBooksSnapshot().mapTo(hashSetOf()) { it.uriString }
        val base = if (selection.isEmpty()) books else filterState.indexedBooks.filter { it.uriString in existingPaths }
        val q = _searchQuery.value
        val source = if (q.isNullOrBlank()) base else {
            val query = q.trim().lowercase()
            base.filter { it.title.lowercase().contains(query) || it.author.lowercase().contains(query) }
        }
        val filtered = if (selection.isEmpty()) source else source.filter { book ->
            filterState.records[book.uriString]?.let { LibraryFilters.matches(it, selection) } == true
        }
        return sorted(filtered)
    }

    fun configureFilters(context: android.content.Context) {
        if (facetLoader != null) return
        val loader = LibraryFacetLoader.shared(context.applicationContext)
        facetLoader = loader
        val restore = viewModelScope.launch(start = CoroutineStart.LAZY) {
            filterProgress.value = "Loading saved library…"
            try {
                libraryReady.await() // An initially empty LiveData is not a loaded empty library.
                val saved = loader.readSelection()
                if (!userChoseFilters) filterState.restore(saved)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { filterError.value = "Saved filters could not be restored. Open Filter library to choose them again." }
            finally {
                filtersRestored = true
                filterProgress.value = if (filterState.selection.isNotEmpty() && !filterState.isFresh())
                    "Preparing library…" else ""
                recomputeVisible()
            }
            // Publish cached results first. Source stats and missing/changed metadata happen later.
            kotlinx.coroutines.yield()
            loader.setMembership(repository.getBooksSnapshot())
            loader.reconcile()
            filterState.validateInBackground()
        }
        restoreFiltersJob = restore
        restore.start()
    }

    fun getLibraryFilters(): Map<String, Set<String>> = filterState.selection.mapValues { it.value.toSet() }

    fun setLibraryFilters(selection: Map<String, Set<String>>) {
        userChoseFilters = true
        filterError.value = ""
        filterState.apply(selection)
    }

    fun filterOptions(key: String): Map<String, Int> = LibraryFilters.options(filterState.records.values, key)
    fun filterIndexReady(): Boolean = filtersRestored && filterState.isFresh()
    fun isRestoringLibrary(): Boolean = !filtersRestored

    private fun signature(books: List<BookItem>): List<String> = books.map(LibraryFacetLoader::bookSignature).sorted()

    suspend fun prepareFilterIndex(force: Boolean = false) {
        checkNotNull(facetLoader) { "Filters are not initialized" }
        restoreFiltersJob?.join()
        filterState.prepare(force)
    }

    override fun onCleared() {
        repository.books.removeObserver(booksObserver)
        super.onCleared()
    }

    fun loadLibrary(): Boolean {
        var ok = false
        viewModelScope.launch {
            try {
                ok = repository.loadLibrary()
                libraryReady.complete(Unit)
            } catch (e: Exception) {
                libraryReady.completeExceptionally(e)
                throw e
            }
            if (ok) {
                val sort = _currentSort.value ?: "modified"
                setSort(sort)
            }
        }
        return ok
    }

    /**
     * Re-read the store and re-sort. Must run on the main thread: it assigns
     * LiveData (repository.loadLibrary and setSort both setValue).
     *
     * Used after a worker has written a new book, so the caller sees it without
     * inserting a second copy itself.
     */
    suspend fun reloadFromStore(): Boolean {
        val ok = repository.loadLibrary()
        libraryReady.complete(Unit)
        if (ok) {
            setSort(_currentSort.value ?: "modified")
        }
        return ok
    }

    fun saveLibrary(): Boolean {
        var ok = false
        viewModelScope.launch {
            ok = repository.saveLibrary()
        }
        return ok
    }

    fun addOrUpdate(book: BookItem) {
        repository.addOrUpdate(book)
        recomputeVisible()
    }

    fun updateBook(oldBook: BookItem, newBook: BookItem) {
        repository.updateBook(oldBook, newBook)
        recomputeVisible()
    }

    fun remove(book: BookItem) {
        repository.remove(book)
        recomputeVisible()
    }

    fun deleteBook(book: BookItem) {
        viewModelScope.launch {
            repository.remove(book)
        }
    }

    fun clearLibrary() {
        repository.clear()
        recomputeVisible()
    }

    private val _selectionMode = MutableLiveData<Boolean?>(null)
    val selectionMode: LiveData<Boolean?> = _selectionMode

    fun setSelectionMode(mode: Boolean) {
        _selectionMode.value = mode
    }

    fun isInSelectionMode(): Boolean = _selectionMode.value == true

    fun clearSelectionState() {
        _selectionMode.value = null
    }

    fun findByIdentity(book: BookItem): Int = repository.findByIdentity(book)

    private fun humanizeJobStatus(status: String): String = when (status) {
        "queued" -> "Queued"
        "waiting" -> "Waiting"
        "running" -> "Running"
        "success" -> "Complete"
        "failed" -> "Failed"
        "cancelled" -> "Cancelled"
        else -> status.replaceFirstChar { it.uppercase() }
    }

    fun getBooksSnapshot(): List<BookItem> = repository.getBooksSnapshot()
    fun getVisibleBooks(): List<BookItem> = _visibleBooks.value.orEmpty().toList()
    fun setBooks(newBooks: List<BookItem>) {
        repository.setBooks(newBooks)
        recomputeVisible(newBooks)
    }

    fun hasRunningJob(): Boolean = repository.hasRunningJob()

    fun cancelCurrentDownload() {
        repository.cancelCurrentDownload()
    }

    fun enqueueDownload(url: String) = viewModelScope.launch { repository.enqueueDownload(url) }
    suspend fun importOrganisedLibrary(rows: org.json.JSONArray): Int = repository.importOrganisedLibrary(rows)

    fun enqueueUpdate(bookId: Long, inputPath: String) = viewModelScope.launch { repository.enqueueUpdate(bookId, inputPath) }
    fun enqueueForceDownload(bookId: Long, inputPath: String) = viewModelScope.launch { repository.enqueueForceDownload(bookId, inputPath) }
    suspend fun enqueueMetadata(url: String): Long = repository.enqueueMetadata(url)
}
