"""Wiring checks supplement, not replace, behavioral filesystem and Kotlin tests."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

MAIN = Path(__file__).resolve().parents[2]
JAVA = MAIN / 'java/com/vivi/aharana'
A = '{http://schemas.android.com/apk/res/android}'


class OrganisationUiTests(unittest.TestCase):
    def test_only_trusted_private_paths_are_canonicalised_at_bridge_boundary(self):
        bridge = (JAVA / 'PythonBridge.kt').read_text()
        organisation = bridge.split('fun organiseLibrary(', 1)[1].split('fun scanEpubDir(', 1)[0]
        self.assertIn('organisationPrivatePath(context.filesDir, "organisation").absolutePath', organisation)
        self.assertNotIn('File(context.filesDir, "organisation").absolutePath', organisation)
        # Generated app-private source paths have the same Android alias. Do not
        # canonicalise library roots or the final source file/symlink itself.
        storage = (JAVA / 'StorageBridge.kt').read_text().split('fun copyDownloadedEpub(', 1)[1].split('fun replaceExistingEpub(', 1)[0]
        self.assertIn('source.absoluteFile.parentFile == context.filesDir.absoluteFile', storage)
        self.assertIn('organisationPrivatePath(context.filesDir, source.name)', storage)
        self.assertIn('.put("root", root)', storage)

    def test_preview_apply_undo_screen_registered_and_reachable(self):
        manifest = ET.parse(MAIN / 'AndroidManifest.xml').getroot()
        self.assertIn('.OrganiseLibraryActivity', {e.get(A + 'name') for e in manifest.iter('activity')})
        self.assertIn('OrganiseLibraryActivity::class.java', (JAVA / 'MainActivity.kt').read_text())
        activity = (JAVA / 'OrganiseLibraryActivity.kt').read_text()
        self.assertIn('RecyclerView', activity)
        self.assertIn('setPositiveButton("Organise"', activity)
        self.assertIn('setPositiveButton("Undo"', activity)
        self.assertNotIn('putExtra("plan"', activity)
        vm = (JAVA / 'OrganiseLibraryViewModel.kt').read_text()
        self.assertIn('LibraryStorageGate.run', vm)
        self.assertIn('NonCancellable', vm)
        self.assertIn('reconcileOrganisedPaths', vm)
        self.assertIn('Dispatchers.IO', vm)

    def test_rescan_uses_recursive_bridge_and_same_storage_exclusion(self):
        main = (JAVA / 'MainActivity.kt').read_text()
        scan = main.split('private fun scanManualEpubDir', 1)[1].split('override fun onActivityResult', 1)[0]
        self.assertNotIn('folder.listFiles()', scan, 'All EPUBs may now live exclusively in author subfolders')
        self.assertIn('LibraryStorageGate.run', scan)
        self.assertIn('scanEpubDir(folder.absolutePath)', scan)
        self.assertIn('importOrganisedLibrary', scan)
        self.assertNotIn('saveLibrary()', scan)

    def test_cached_room_display_is_restored_before_waiting_for_long_storage_work(self):
        source = (JAVA / 'BookRepository.kt').read_text().split('    init {', 1)[1]
        self.assertLess(source.index('bookDao.observeAll().observeForever'),
                        source.index('LibraryStorageGate.run'),
                        'A running network download must not hold the cached shelf blank')

    def test_database_preflight_runs_before_filesystem_mutations(self):
        vm = (JAVA / 'OrganiseLibraryViewModel.kt').read_text()
        body = vm.split('val bridge = PythonBridge(context)', 1)[1]
        self.assertLess(body.index('database.withTransaction'), body.index('bridge.organiseLibrary(action, payload)'))
        self.assertLess(body.index('requireUnoccupiedOrganisationTargets'), body.index('bridge.organiseLibrary(action, payload)'))
        self.assertIn('"undo_preview"', body)
        self.assertIn('bookDao().getAll()', body)

    def test_recovery_updates_rows_and_job_paths_without_clearing_library(self):
        source = (JAVA / 'BookRepository.kt').read_text()
        self.assertIn('suspend fun reconcileOrganisedPaths', source)
        body = source.split('suspend fun reconcileOrganisedPaths', 1)[1].split('private const val BULK_METADATA_TAG', 1)[0]
        self.assertIn('database.withTransaction', body)
        self.assertIn('bookDao.update(', body)
        self.assertIn('sourcePath =', body)
        self.assertIn('inputPath =', body)
        self.assertIn('outputPath =', body)
        self.assertNotIn('clear()', body)
        self.assertNotIn('insertAll(', body)
        self.assertLess(body.index('bookDao.update('), body.index('check(recovery.optBoolean("ok"))'),
                        'Completed moves must reconnect even when another interrupted copy needs attention')
        self.assertIn('reconcileOrganisedPaths(context)', source.split('init {', 1)[1])


if __name__ == '__main__':
    unittest.main()
