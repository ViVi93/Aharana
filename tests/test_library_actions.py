"""Android wiring checks; launcher behavior is exercised by the Kotlin host runner."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/vivi/aharana'


class LibraryActionWiringTests(unittest.TestCase):
    def test_library_book_menu_has_open_using_shared_launcher(self):
        source = (JAVA / 'MainActivity.kt').read_text()
        menu = source.split('private fun showBookOptionsDialog(', 1)[1].split('private fun openBookDetail(', 1)[0]
        self.assertIn('options.add("Open")', menu)
        self.assertIn('"Open" -> EpubOpener.open(this, book.uriString, ::toast)', menu)

    def test_details_uses_same_launcher(self):
        source = (JAVA / 'BookDetailActivity.kt').read_text()
        opening = source.split('private fun openBook()', 1)[1].split('private fun updateBook()', 1)[0]
        self.assertIn('EpubOpener.open(this, bookPath, ::showMessage)', opening)

    def test_manual_filter_refresh_menu_is_explicit_and_separate_from_site_updates(self):
        android = '{http://schemas.android.com/apk/res/android}'
        menu = ET.parse(ROOT / 'app/src/main/res/menu/main_menu.xml').getroot()
        refresh = [item for item in menu if item.get(android + 'id') == '@+id/action_refresh_filter_data']
        self.assertEqual(1, len(refresh))
        self.assertEqual('Refresh filter data', refresh[0].get(android + 'title'))
        source = (JAVA / 'MainActivity.kt').read_text()
        action = source.split('R.id.action_refresh_filter_data ->', 1)[1].split('true', 1)[0]
        self.assertIn('LibraryFilterDialog(this, viewModel).show(force = ', action)
        self.assertNotIn('refreshAllBooks', action)


if __name__ == '__main__':
    unittest.main()
