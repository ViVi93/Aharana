"""Android integration contracts; actual phone checks remain required."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

MAIN = Path(__file__).resolve().parents[2]
JAVA = MAIN / 'java/com/vivi/aharana'
A = '{http://schemas.android.com/apk/res/android}'


class StoryRefreshUiContractTests(unittest.TestCase):
    def test_original_site_refresh_is_separate_from_catalogue_preview(self):
        manifest = ET.parse(MAIN / 'AndroidManifest.xml').getroot()
        names = {e.get(A + 'name') for e in manifest.iter('activity')}
        self.assertIn('.StoryRefreshActivity', names)
        detail = (JAVA / 'BookDetailActivity.kt').read_text()
        self.assertIn('StoryRefreshActivity::class.java', detail)
        self.assertIn('STORY_REFRESH_REQUEST', detail)
        self.assertIn('MetadataPreviewActivity::class.java', detail)
        self.assertIn('buttonRefreshStory', detail)
        bridge = (JAVA / 'PythonBridge.kt').read_text()
        self.assertIn('safeCall("preview_story_refresh", epubPath, includeCover)', bridge)
        self.assertIn('safeCall("apply_story_refresh", epubPath, previewJson, selectedFieldsJson, applyCover, outputPath)', bridge)


    def test_selective_apply_uses_retained_state_and_small_results(self):
        path = JAVA / 'StoryRefreshActivity.kt'
        self.assertTrue(path.exists(), 'Story-site preview screen is missing')
        source = path.read_text()
        self.assertIn('StoryRefreshViewModel::class.java', source)
        self.assertIn('Apply selected metadata', source)
        self.assertIn('Apply cover only', source)
        self.assertIn('Apply selected + cover', source)
        self.assertIn('setOnCheckedChangeListener', source)
        self.assertIn('Current:', source)
        self.assertIn('Story site:', source)
        self.assertNotIn('putExtra("preview"', source)
        vm = (JAVA / 'StoryRefreshViewModel.kt').read_text()
        self.assertIn('viewModelScope.launch', vm)
        self.assertIn('FanFicFareGate.run', vm)
        self.assertIn('NonCancellable', vm)
        self.assertIn('StoryRefreshStore', vm)


    def test_apply_persists_exact_library_row_without_whole_library_clear(self):
        path = JAVA / 'StoryRefreshStore.kt'
        self.assertTrue(path.exists(), 'Safe refresh persistence is missing')
        source = path.read_text()
        self.assertIn('database.withTransaction', source)
        self.assertIn('dao.update(current.copy(', source)
        self.assertIn('findByFilePath(originalPath)', source)
        self.assertNotIn('saveLibrary(', source)
        self.assertNotIn('bookDao.clear(', source)
        self.assertIn('verifyCopy', source)
        self.assertIn('StoryRefreshFiles.saveNewCopy', source)
        self.assertIn('delete()', source)
        self.assertIn('bridge.getCoverFromEpub', source)
        self.assertIn('verifiedCover', source)
        main = (JAVA / 'MainActivity.kt').read_text()
        body = main.split('data.getBooleanExtra("story_refreshed", false)', 1)
        self.assertEqual(len(body), 2)
        refresh = body[1].split('val deleted', 1)[0]
        self.assertIn('viewModel.reloadFromStore()', refresh)
        self.assertNotIn('viewModel.saveLibrary()', refresh)


    def test_library_fields_use_only_applied_keys_and_complete_saved_authors(self):
        source = (JAVA / 'StoryRefreshStore.kt').read_text()
        self.assertIn('result.getJSONArray("changed_fields")', source)
        self.assertIn('result.getJSONObject("metadata")', source)
        self.assertIn('savedMetadata.optJSONArray("authors")', source)
        self.assertIn('StoryRefreshLibraryMetadata.merge(', source)
        self.assertIn('title = libraryFields.title', source)
        self.assertIn('author = libraryFields.author', source)
        self.assertNotIn('meta.optString("author"', source)


    def test_readable_preview_and_offline_details_are_wired(self):
        source = (JAVA / 'StoryRefreshActivity.kt').read_text()
        self.assertIn('row.opt("display_current")', source)
        self.assertIn('row.opt("display_online")', source)
        detail = (JAVA / 'BookDetailActivity.kt').read_text()
        self.assertIn('loadStoryDetails()', detail)
        self.assertIn('bridge.getStoryDetails(', detail)
        self.assertIn('StorageBridge.withLocalEpub', detail)
        self.assertIn('Dispatchers.IO', detail)
        self.assertIn('lifecycleScope.launch', detail)
        self.assertNotIn('from saved tags', detail)
        self.assertIn('Story updated: $it', detail)
        layout = ET.parse(MAIN / 'res/layout/activity_book_detail.xml').getroot()
        ids = {e.get(A + 'id') for e in layout.iter()}
        self.assertIn('@+id/textStoryStatus', ids)
        self.assertIn('@+id/textStoryUpdated', ids)
        bridge = (JAVA / 'PythonBridge.kt').read_text()
        self.assertIn('safeCall("get_story_details", epubPath)', bridge)

    def test_lint_error_regressions(self):
        storage = (JAVA / 'StorageBridge.kt').read_text()
        self.assertNotIn('resolver.delete(uri, null)', storage)
        self.assertIn('resolver.delete(uri, null, null)', storage)
        self.assertIn('DocumentsContract.isDocumentUri', storage)
        queue = ET.parse(MAIN / 'res/layout/item_download_job.xml').getroot()
        icon = next(e for e in queue.iter() if e.get(A + 'id') == '@+id/iconStatus')
        self.assertNotIn(A + 'tint', icon.attrib)
        self.assertIn('{http://schemas.android.com/apk/res-auto}tint', icon.attrib)


if __name__ == '__main__':
    unittest.main()
