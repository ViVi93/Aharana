"""Android integration contracts supplement the real-filesystem JVM behavior tests."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/vivi/aharana'


def source(name):
    return (JAVA / name).read_text()


class DownloadStorageWiringTest(unittest.TestCase):
    def assertIn(self, member, container, msg=None):
        super().assertTrue(member in container, msg or f'Missing integration contract: {member}')

    def assertNotIn(self, member, container, msg=None):
        super().assertTrue(member not in container, msg or f'Unexpected integration contract: {member}')

    def test_setting_defaults_off_and_switch_explains_scope(self):
        settings = source('SettingsActivity.kt')
        self.assertIn('const val KEY_ORGANISE_DOWNLOADS = "organise_downloads"', settings)
        self.assertIn('getBoolean(KEY_ORGANISE_DOWNLOADS, false)', settings)
        self.assertIn('putBoolean(KEY_ORGANISE_DOWNLOADS,', settings)
        layout = ET.parse(ROOT / 'app/src/main/res/layout/activity_settings.xml')
        android = '{http://schemas.android.com/apk/res/android}'
        switch = next((e for e in layout.iter() if e.get(android + 'id') == '@+id/switchOrganiseDownloads'), None)
        self.assertIsNotNone(switch, 'Settings needs a visible future-download switch')
        text = ' '.join(e.get(android + 'text', '') for e in layout.iter())
        self.assertIn('Author/Title.epub', text)
        self.assertIn('existing books', text)
        self.assertIn('filesystem', text)

    def test_new_download_uses_organiser_copy_without_changing_exports(self):
        storage = source('StorageBridge.kt')
        self.assertIn('fun copyDownloadedEpub(', storage)
        self.assertIn('"copy_download"', storage)
        self.assertIn('SettingsActivity.getOrganiseDownloads(context)', storage)
        worker = source('FanFicFareWorker.kt')
        download = worker.split('private suspend fun handleDownload(')[1].split('private suspend fun handleUpdate(')[0]
        self.assertIn('StorageBridge.copyDownloadedEpub(', download)
        self.assertNotIn('StorageBridge.copyToOutputDir(', download)
        self.assertIn('sourceFile.copyTo(outFile, overwrite = true)', storage, 'Unrelated exports keep their contract')

    def test_worker_updates_resolve_canonical_and_replace_in_place(self):
        worker = source('FanFicFareWorker.kt')
        for name, end in [('handleUpdate', 'handleForceDownload'), ('handleForceDownload', 'handleMetadata')]:
            method = worker.split(f'private suspend fun {name}(')[1].split(f'private suspend fun {end}(')[0]
            self.assertIn('val recoveredInputPath = jobDao.getById(job.id)?.inputPath', method)
            self.assertIn('bookDao.findByFilePath(recoveredInputPath)', method)
            self.assertIn('LibraryEpubStorage.existingPath(existing?.filePath, recoveredInputPath)', method)
            self.assertIn('StorageBridge.replaceExistingEpub(', method)
            self.assertNotIn('getOutputDir(', method)
            self.assertNotIn('copyToOutputDir(', method)
            self.assertIn('File(applicationContext.cacheDir, "epub-update-', method)
            self.assertIn('workDir.deleteRecursively()', method)
            self.assertIn('"Generated EPUB is missing; the original was kept"', method)
            self.assertIn('sourcePath = LibraryEpubStorage.sourceAfterReplacement(', method)
        upsert = worker.split('private suspend fun upsertBook(')[1].split('override suspend fun getForegroundInfo')[0]
        self.assertIn('bookDao.findById(candidate.id)', upsert)
        self.assertIn('addedAt = existing.addedAt', upsert)

    def test_worker_metadata_bypasses_storage_gate(self):
        worker = source('FanFicFareWorker.kt')
        entry = worker.split('override suspend fun doWork()')[1].split('private suspend fun doWorkUnlocked')[0]
        self.assertIn('TYPE_METADATA', entry)
        self.assertIn('doWorkUnlocked()', entry)
        self.assertIn('LibraryStorageGate.run', entry)
        self.assertNotIn('markInterruptedJob(', entry, 'Cancelled gate waiters must not probe book paths outside storage exclusion')
        self.assertIn('BookRepository.reconcileOrganisedPaths(applicationContext)', entry)
        self.assertIn('setForeground(getForegroundInfo())', entry, 'Promotion must happen before waiting for the storage gate')

    def test_detail_holds_gate_from_path_resolution_through_persistence(self):
        detail = source('BookDetailActivity.kt')
        updates = detail.split('private fun updateBook()')[1].split('private fun shareBook()')[0]
        self.assertIn('LibraryStorageGate.runBlocking', updates)
        self.assertIn('BookRepository.reconcileOrganisedPaths(applicationContext)', updates)
        self.assertIn('StorageBridge.replaceExistingEpub(', updates)
        self.assertIn('bookDao.update(', updates)
        self.assertIn('response.put("library_persisted", existing != null)', updates)
        self.assertIn('libraryPersisted = result.optBoolean("library_persisted")', updates)
        self.assertIn('if (libraryPersisted) putExtra("story_refreshed", true)', detail,
                      'Reuse the existing sole-writer result contract; Main must only reload Room')
        self.assertNotIn('bookDao.findByUrl(', updates, 'Shared story URLs cannot identify a moved file safely')
        self.assertNotIn('copyToOutputDir(', updates)
        self.assertNotIn('SettingsActivity.getOutputDir(', updates)


if __name__ == '__main__':
    unittest.main()
