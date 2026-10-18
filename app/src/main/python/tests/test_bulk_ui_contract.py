"""Host-side Android integration contracts (not a substitute for device UI tests)."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

MAIN = Path(__file__).resolve().parents[2]
JAVA = MAIN / 'java/com/vivi/aharana'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class BulkUiContractTests(unittest.TestCase):
    def test_local_inputs_share_the_preview_and_safe_parser(self):
        source = (JAVA / 'AddFromPageActivity.kt').read_text()
        layout = ET.parse(MAIN / 'res/layout/activity_add_from_page.xml')
        ids = {node.get(ANDROID + 'id', '').split('/')[-1] for node in layout.iter()}
        self.assertTrue({'buttonImportFile', 'buttonPasteUrls'} <= ids,
                        'Bulk file and pasted-list inputs are missing')
        self.assertIn('ActivityResultContracts.OpenDocument()', source)
        self.assertIn('parseStoryUrlText', source)
        self.assertIn('openInputStream', source)
        self.assertIn('ByteArray(MAX_INPUT_BYTES + 1)', source)
        self.assertIn('showUrlPreview', source)
        bridge = (JAVA / 'PythonBridge.kt').read_text()
        self.assertIn('safeCall("parse_story_url_text", text, normalize)', bridge)


    def test_queue_actions_use_repository_preview_and_bulk_policy(self):
        source = (JAVA / 'AddFromPageActivity.kt').read_text()
        layout = ET.parse(MAIN / 'res/layout/activity_add_from_page.xml')
        ids = {node.get(ANDROID + 'id', '').split('/')[-1] for node in layout.iter()}
        self.assertTrue({'buttonUpdateExisting', 'buttonSkipExisting'} <= ids,
                        'Grouped queue actions are missing')
        self.assertTrue('previewBulkUrls' in source and 'enqueueBulkUrls' in source)
        self.assertTrue('"Already queued"' in source and '"Already in library"' in source)
        self.assertFalse('BookRepository(this@AddFromPageActivity).enqueueDownload(item.url)' in source)

    def test_metadata_updates_do_not_replace_current_selection(self):
        source = (JAVA / 'AddFromPageActivity.kt').read_text()
        self.assertFalse('val updated = items.toMutableList()' in source,
                         'Captured metadata snapshot can overwrite newer selections')
        self.assertTrue('applyMetadataResults' in source)
        self.assertTrue('adapter.currentList.map' in source)

    def test_persistence_uses_small_bundle_and_saves_selection(self):
        source = (JAVA / 'AddFromPageActivity.kt').read_text()
        self.assertFalse('putParcelableArrayList' in source,
                         'Large bulk lists must not be placed in Android saved-state bundles')
        self.assertTrue('savePreviewState' in source)
        self.assertTrue('setOnCheckedChangeListener' in source)
        self.assertTrue('getSharedPreferences("bulk_import"' in source)


    def test_skip_existing_rechecks_library_and_header_can_collapse(self):
        source = (JAVA / 'AddFromPageActivity.kt').read_text()
        self.assertIn('private fun skipExisting()', source)
        body = source.split('private fun skipExisting()', 1)[-1].split('private fun refreshLibraryStates()', 1)[0]
        self.assertIn('repository.previewBulkUrls(urls)', body)
        root = ET.parse(MAIN / 'res/layout/activity_add_from_page.xml').getroot()
        appbar = next(e for e in root.iter() if e.get(ANDROID + 'id') == '@+id/appBar')
        app = '{http://schemas.android.com/apk/res-auto}'
        collapsing = [e for e in appbar if 'scroll' in e.get(app + 'layout_scrollFlags', '')]
        self.assertTrue(collapsing, 'Bulk controls need a collapsible header for short screens')
        self.assertTrue(any(e.get(ANDROID + 'id') == '@+id/selectionBar' for header in collapsing for e in header.iter()))

    def test_metadata_submission_uses_recoverable_repository_path(self):
        source = (JAVA / 'AddFromPageActivity.kt').read_text()
        self.assertIn('repository.ensureBulkMetadata(urls)', source)
        self.assertNotIn('val known = jobs.filter', source)


    def test_repository_connects_bulk_policy_and_durable_work_names(self):
        source = (JAVA / 'BookRepository.kt').read_text()
        self.assertTrue('suspend fun previewBulkUrls(' in source,
                        'Repository bulk preview is not connected')
        self.assertTrue('suspend fun enqueueBulkUrls(' in source)
        self.assertTrue('suspend fun ensureBulkMetadata(' in source)
        self.assertTrue('BulkQueueCoordinator().enqueue' in source)
        self.assertFalse('FanFicFareWorker.UNIQUE_WORK_NAME,' in source,
                         'Shared KEEP name silently drops selected updates')
        self.assertTrue('metadataSubmissionMutex' in source and 'metadataScope.async' in source)
        self.assertTrue('getWorkInfosForUniqueWork' in source)


    def test_metadata_cancel_stops_submission_and_survives_rotation(self):
        source = (JAVA / 'AddFromPageActivity.kt').read_text()
        self.assertTrue('repository.cancelBulkMetadata()' in source,
                        'Cancel must stop the process-owned scheduler, not only existing workers')
        self.assertTrue('if (metadataPaused) return' in source)
        self.assertTrue('put("metadataPaused", metadataPaused)' in source)
        self.assertTrue('root.optBoolean("metadataPaused", false)' in source)


    def test_failed_work_submission_is_not_left_queued(self):
        source = (JAVA / 'BookRepository.kt').read_text()
        self.assertTrue('private suspend fun scheduleUserWork(' in source,
                        'Submission failures need to mark their own Room row failed')
        body = source.split('private suspend fun scheduleUserWork(', 1)[-1].split('suspend fun enqueueDownload(', 1)[0]
        self.assertTrue('status = "failed"' in body and 'work_submission_failed' in body)
        self.assertTrue('enqueueUniqueWork(workName' in body)
        self.assertEqual(source.count('scheduleUserWork(jobId, requestWorkId, work)'), 3)


if __name__ == '__main__':
    unittest.main()
