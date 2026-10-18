"""Integration contracts for single search/filter/sort projection."""
from pathlib import Path
import unittest
MAIN=Path(__file__).resolve().parents[2]
JAVA=MAIN/'java/com/vivi/aharana'
class LibraryFilterUiTests(unittest.TestCase):
    def test_library_card_has_no_status_prefix(self):
        strings=(MAIN/'res/values/strings.xml').read_text()
        self.assertIn('<string name="library_status_format">%1$s</string>', strings)

    def test_filters_are_available_and_retained_in_viewmodel(self):
        vm=(JAVA/'LibraryViewModel.kt').read_text()
        self.assertIn('fun setLibraryFilters(', vm)
        self.assertIn('fun projectLibrary(', vm)
        self.assertIn('LibraryFilters.matches', vm)
        self.assertIn('recomputeVisible(books)', vm)
        self.assertNotIn('_visibleBooks.value = sorted(books)', vm)
        self.assertIn('removeObserver(booksObserver)', vm)
        main=(JAVA/'MainActivity.kt').read_text()
        self.assertIn('R.id.action_filter_library', main)
        self.assertIn('showLibraryFilters()', main)
        self.assertIn('viewModel.projectLibrary(books)', main)
        self.assertNotIn('if (books.isNotEmpty()) {\n                    syncBooks(books)', main)
        dialog=(JAVA/'LibraryFilterDialog.kt').read_text()
        self.assertIn('Search values', dialog)
        self.assertIn('Clear all', dialog)
        self.assertIn('model.prepareFilterIndex(force)', dialog)
        self.assertNotIn('cancelFilterIndex()', dialog)
        self.assertIn('scan?.cancel()', dialog)
        self.assertIn('filterState.apply(selection)', vm)
        self.assertIn('filterState.booksChanged()', vm)
        self.assertIn('filterState.indexedBooks', vm)
        self.assertIn('selection', dialog)

    def test_persistent_filters_and_overflow_only(self):
        main=(JAVA/'MainActivity.kt').read_text()
        self.assertNotIn('R.id.buttonLibraryFilters', main)
        self.assertIn('R.id.libraryFilterSummary', main)
        layout=(MAIN/'res/layout/activity_main.xml').read_text()
        self.assertNotIn('@+id/buttonLibraryFilters', layout)
        self.assertIn('@+id/libraryFilterSummary', layout)
        vm=(JAVA/'LibraryViewModel.kt').read_text()
        self.assertIn('readSelection()', vm)
        self.assertIn('saveSelection(', vm)
        self.assertIn('restoreFiltersJob?.join()', vm)
        loader=(JAVA/'LibraryFacetLoader.kt').read_text()
        for marker in ('LibraryFacetDiskStore', 'filesDir', 'store.readSelection()', 'store.writeSelection(', 'database.readAll()', 'database.upsert(', 'lastModified()', 'NonCancellable'):
            self.assertIn(marker, loader)
        dialog=(JAVA/'LibraryFilterDialog.kt').read_text()
        self.assertNotIn('app restart starts unfiltered', dialog)
        self.assertIn('persist across app restarts', dialog)

    def test_bulk_index_startup_and_loading_states(self):
        vm = (JAVA/'LibraryViewModel.kt').read_text()
        self.assertIn('filterState.restore(saved)', vm)
        self.assertNotIn('filterState.apply(saved).join()', vm)
        self.assertIn('libraryReady.await()', vm)
        self.assertIn('loadCached =', vm)
        self.assertIn('validateInBackground()', vm)
        loader = (JAVA/'LibraryFacetLoader.kt').read_text()
        self.assertIn('LibraryFacetIndexDatabase', loader)
        self.assertIn('database.readAll()', loader)
        self.assertIn('suspend fun cached(', loader)
        main = (JAVA/'MainActivity.kt').read_text()
        self.assertIn('LibraryListPresentation.choose(', main)
        self.assertIn('Loading saved library', main)
        self.assertIn('Checking for changes', main)

    def test_review_fixes_keep_sort_read_only_and_delete_by_identity(self):
        vm = (JAVA/'LibraryViewModel.kt').read_text()
        sort = vm.split('fun setSort(sort: String)', 1)[1].split('fun setSearchQuery', 1)[0]
        self.assertNotIn('repository.setBooks(', sort)
        self.assertNotIn('if (filterState.selection.isNotEmpty()) filterState.validateInBackground()', vm)
        self.assertIn('loader.setMembership(books)', vm)
        repo = (JAVA/'BookRepository.kt').read_text()
        remove = repo.split('fun remove(book: BookItem)', 1)[1].split('fun clear()', 1)[0]
        self.assertNotIn('list.remove(book)', remove)
        self.assertIn('uriString == book.uriString', remove)
        for method, end in (('fun setBooks(newBooks:', 'suspend fun loadLibrary'), ('suspend fun saveLibrary()', 'private suspend fun upsertBookEntity')):
            section = repo.split(method, 1)[1].split(end, 1)[0]
            self.assertIn('database.withTransaction', section)
        self.assertIn('indexReady = viewModel.filterIndexReady()', (JAVA/'MainActivity.kt').read_text())

    def test_filters_persist_and_only_overflow_opens_them(self):
        main = (JAVA/'MainActivity.kt').read_text()
        layout = (MAIN/'res/layout/activity_main.xml').read_text()
        self.assertNotIn('buttonLibraryFilters', main)
        self.assertNotIn('buttonLibraryFilters', layout)
        self.assertIn('libraryFilterSummary', main)
        self.assertIn('setOnClickListener', main)  # unrelated actions retained
        self.assertIn('R.id.action_filter_library', main)
        vm = (JAVA/'LibraryViewModel.kt').read_text()
        self.assertIn('readSelection()', vm)
        self.assertIn('saveSelection(', vm)
        self.assertIn('persistSelection =', vm)
        self.assertIn('restoreFiltersJob?.join()', vm)
        loader = (JAVA/'LibraryFacetLoader.kt').read_text()
        self.assertIn('database.readAll()', loader)
        self.assertIn('database.upsert(', loader)
        self.assertIn('index.read(book, force)', loader)
        self.assertIn('force: Boolean', loader)
        self.assertIn('lastModified()', loader)
        self.assertIn('filesDir', loader)
        self.assertIn('getLibraryFacets', loader)
        dialog = (JAVA/'LibraryFilterDialog.kt').read_text()
        self.assertNotIn('app restart starts unfiltered', dialog)

if __name__=='__main__': unittest.main()
