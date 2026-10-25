"""Storage transaction boundaries, including the delayed Activity-result handoff."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/vivi/aharana'


class MutationStorageWiringTest(unittest.TestCase):
    def test_delete_journal_covers_file_and_room_crash_window(self):
        source = (SRC / 'LibraryBookMutations.kt').read_text().split('suspend fun delete()', 1)[1]
        self.assertLess(source.index('"delete_prepare"'), source.index('StorageBridge.deleteEpub'))
        self.assertLess(source.index('"delete_finish"'), source.index('database.bookDao().delete'))
        self.assertIn('put("book_id", original.id)', source)
        self.assertIn('prepared.getString("delete_path")', source)
        self.assertIn('StorageBridge.deleteEpub(context, deletePath)', source)
        repository = (SRC / 'BookRepository.kt').read_text()
        self.assertIn('recovery.optJSONArray("deleted_books")', repository)
        self.assertIn('current.filePath == deletion.getString("path")', repository)

    def test_metadata_save_commits_before_activity_result(self):
        edit = (SRC / 'EditMetadataActivity.kt').read_text()
        save = edit.split('private fun saveMetadata()', 1)[1].split('private fun exportOpf()', 1)[0]
        self.assertIn('LibraryBookMutations.runBlocking', save)
        self.assertIn('mutation.persist', save)
        self.assertIn('StorageBridge.saveUpdatedDocumentCopy', save)
        self.assertIn('mutation.persist(finalPath)', save)
        self.assertIn('putExtra("story_refreshed", true)', save)
        detail = (SRC / 'BookDetailActivity.kt').read_text()
        handoff = detail.split('EDIT_METADATA_REQUEST, PREVIEW_METADATA_REQUEST ->', 1)[1].split('REPLACE_COVER_REQUEST ->', 1)[0]
        self.assertIn('getBooleanExtra("story_refreshed", false)', handoff)


    def test_catalogue_apply_commits_inside_storage_transaction(self):
        source = (SRC / 'MetadataPreviewActivity.kt').read_text()
        helper = source.split('private fun applyMetadataAndCoverSafely(', 1)[1]
        self.assertIn('LibraryBookMutations.runBlocking', helper)
        self.assertIn('mutation.persist', helper)
        self.assertNotIn('withLocalEpub(this, epubPath)', helper)
        self.assertEqual(source.count('putExtra("story_refreshed", true)'), 3)


    def test_deletes_remove_exact_room_row_before_reporting_success(self):
        for name, end in [('MainActivity.kt', 'private fun refreshBookPath'),
                          ('BookDetailActivity.kt', 'private fun editMetadata')]:
            body = (SRC / name).read_text().split('private fun deleteBook(', 1)[1].split(end, 1)[0]
            with self.subTest(name=name):
                self.assertIn('LibraryBookMutations.run', body)
                self.assertIn('mutation.delete()', body)
                self.assertNotIn('saveLibrary()', body)
                self.assertNotIn('StorageBridge.deleteEpub', body)


    def test_local_cover_replacement_preserves_current_organised_path(self):
        source = (SRC / 'BookDetailActivity.kt').read_text()
        body = source.split('private fun replaceCoverWithImage(', 1)[1].split('private fun finishWithResult', 1)[0]
        self.assertIn('LibraryBookMutations.runBlocking', body)
        self.assertIn('mutation.persist', body)
        self.assertIn('if (mutation.path.startsWith("content://"))', body)
        self.assertIn('libraryPersisted = true', body)
        self.assertNotIn('withLocalEpub(this, bookPath)', body)


    def test_story_apply_orders_storage_before_engine_and_remaps_stale_input(self):
        store = (SRC / 'StoryRefreshStore.kt').read_text()
        apply = store.split('suspend fun apply(', 1)[1].split('private suspend fun applyAtPath', 1)[0]
        self.assertIn('LibraryBookMutations.run(context, originalPath, expectedBookId = expectedBookId)', apply)
        self.assertLess(apply.index('LibraryBookMutations.run'), apply.index('FanFicFareGate.run'))
        self.assertIn('mutation.path', apply)
        vm = (SRC / 'StoryRefreshViewModel.kt').read_text().split('fun apply(withMetadata:', 1)[1]
        self.assertNotIn('FanFicFareGate.run', vm)


    def test_merge_holds_storage_through_registration_without_background_repository(self):
        worker = (SRC / 'MergeBooksWorker.kt').read_text()
        self.assertIn('LibraryBookMutations.withPaths', worker)
        self.assertIn('JSONArray(currentPaths)', worker)
        self.assertNotIn('BookRepository(applicationContext)', worker)
        self.assertLess(worker.index('LibraryBookMutations.withPaths'), worker.index('bridge.mergeBooks('))


    def test_story_preview_reads_snapshot_before_network_without_storage_engine_inversion(self):
        vm = (SRC / 'StoryRefreshViewModel.kt').read_text().split('fun load(includeCover:', 1)[1].split('fun apply(', 1)[0]
        self.assertIn('store.snapshot(path, expectedBookId = bookId)', vm)
        self.assertLess(vm.index('store.snapshot(path, expectedBookId = bookId)'), vm.index('FanFicFareGate.run'))
        self.assertIn('snapshot.delete()', vm)
        self.assertNotIn('withLocalEpub', vm)


    def test_detail_download_uses_one_exact_flattened_mapping(self):
        source = (SRC / 'BookDetailActivity.kt').read_text()
        download = source.split('private fun downloadExistingBook(', 1)[1].split('private fun shareBook', 1)[0]
        self.assertNotIn('== resolvedPath', download)
        self.assertIn('recoveryPaths[bookPath] ?: bookPath', download)


if __name__ == '__main__':
    unittest.main()
