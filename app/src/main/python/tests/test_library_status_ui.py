"""Library status integration contracts; physical UI checks are separate."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
MAIN = Path(__file__).resolve().parents[2]
JAVA = MAIN / 'java/com/vivi/aharana'
A = '{http://schemas.android.com/apk/res/android}'

class LibraryStatusUiTests(unittest.TestCase):
    def test_chapter_count_uses_android_quantity_resource(self):
        adapter = (JAVA / 'adapter/BookAdapter.kt').read_text()
        self.assertIn('getQuantityString(R.plurals.library_chapter_count, book.chapters, book.chapters)', adapter)
        self.assertNotIn('"${book.chapters} chapters"', adapter)
        plurals = ET.parse(MAIN / 'res/values/plurals.xml').getroot()
        count = next(e for e in plurals if e.get('name') == 'library_chapter_count')
        forms = {e.get('quantity'): e.text for e in count}
        self.assertEqual('%d chapter', forms['one'])
        self.assertEqual('%d chapters', forms['other'])
        self.assertIn('if (book.chapters > 0)', adapter)

    def test_detail_source_notes_removed(self):
        detail = (JAVA / 'BookDetailActivity.kt').read_text()
        self.assertNotIn('from saved tags', detail)
        self.assertIn('Status: $it', detail)
        self.assertIn('Story updated: $it', detail)

    def test_library_status_read_off_main_thread_and_recycle_safe(self):
        adapter = (JAVA / 'adapter/BookAdapter.kt').read_text()
        self.assertIn('R.id.textLibraryStatus', adapter)
        self.assertIn('statusLoader.load(book)', adapter)
        self.assertIn('statusJob?.cancel()', adapter)
        self.assertIn('onViewRecycled', adapter)
        self.assertIn('LibraryStoryStatus.label', adapter)
        main = (JAVA / 'MainActivity.kt').read_text()
        self.assertIn('statusScope = lifecycleScope', main)
        loader = (JAVA / 'LibraryStoryStatusLoader.kt').read_text()
        for marker in ('Dispatchers.IO', 'Mutex()', 'facetLoader.read(book)', 'LibraryFacetLoader.shared', 'LruCache', 'lastModified()'):
            self.assertIn(marker, loader)
        self.assertNotIn('enqueueDownload', loader)
        layout = ET.parse(MAIN / 'res/layout/item_book.xml').getroot()
        status = next(e for e in layout.iter() if e.get(A+'id') == '@+id/textLibraryStatus')
        self.assertEqual('@id/textChapters', status.get('{http://schemas.android.com/apk/res-auto}layout_constraintTop_toBottomOf'))

if __name__ == '__main__':
    unittest.main()
