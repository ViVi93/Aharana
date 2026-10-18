"""Offline library filters read saved EPUB metadata without changing the book."""
import builtins
import hashlib
import importlib
import importlib.util
import json
import os
import tempfile
import unittest
import zipfile
from contextlib import ExitStack
from unittest.mock import patch
from xml.sax.saxutils import escape


class LibraryFacetsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.epub = os.path.join(self.tmp.name, 'book.epub')

    def fixture(self, authors=(), languages=(), tags=(), extra='', version='2.0'):
        fields = ''.join('<dc:%s>%s</dc:%s>' % (name, escape(value), name)
                         for name, values in (('creator', authors), ('language', languages),
                                              ('subject', tags)) for value in values)
        opf = ('<package xmlns="http://www.idpf.org/2007/opf" '
               'xmlns:dc="http://purl.org/dc/elements/1.1/" version="%s">'
               '<metadata><dc:title>Saved book</dc:title>%s%s</metadata>'
               '<manifest/><spine/></package>') % (version, fields, extra)
        with zipfile.ZipFile(self.epub, 'w') as archive:
            archive.writestr('mimetype', 'application/epub+zip')
            archive.writestr('META-INF/container.xml', '<container><rootfiles><rootfile full-path="Books/book.opf"/></rootfiles></container>')
            archive.writestr('Books/book.opf', opf)
            archive.writestr('Books/chapter.xhtml', '<html><body>Unchanged</body></html>')

    def read(self):
        self.assertIsNotNone(importlib.util.find_spec('library_facets'),
                             'Offline library facets API is missing')
        return importlib.import_module('library_facets').get_library_facets(self.epub)

    def test_legacy_metadata_preserves_full_authors_and_ordered_unique_values(self):
        self.fixture(authors=[' Doe, Jane ', 'Second Writer', 'Doe, Jane', ' '],
                     languages=[' en ', 'fr', 'en', ' '],
                     tags=[' Adventure ', 'Completed', 'Adventure', '', 'Sci-Fi, Fantasy'],
                     extra='<meta name="calibre:series" content=" Saved Series "/>'
                           '<meta name="calibre:rating" content="8"/>'
                           '<dc:source>https://WWW.Example.COM:8443/unsupported/book?x=1</dc:source>')
        self.assertEqual({'ok': True, 'authors': ['Doe, Jane', 'Second Writer'],
                          'status': 'Completed', 'languages': ['en', 'fr'],
                          'series': ['Saved Series'],
                          'tags': ['Adventure', 'Completed', 'Sci-Fi, Fantasy'],
                          'site': 'example.com'}, self.read())

    def test_modern_explicit_status_wins_over_tags_without_rating_mixup(self):
        for status_meta in ('<meta name="aharana:story:status" content=" Paused "/>',
                            '<meta property="aharana:story:status"> Paused </meta>'):
            with self.subTest(status_meta=status_meta):
                self.fixture(authors=['Writer'], languages=['en-GB'], tags=['Completed', 'Mature'],
                             version='3.0', extra=status_meta +
                             '<meta name="calibre:rating" content="10"/>')
                result = self.read()
                self.assertEqual('Paused', result['status'])
                self.assertEqual(['Completed', 'Mature'], result['tags'])
                self.assertEqual(['en-GB'], result['languages'])
                self.assertEqual([], result['series'])
                self.assertEqual({'ok', 'authors', 'status', 'languages', 'series', 'tags', 'site'},
                                 set(result))

    def test_unknown_or_conflicting_status_is_blank_not_inferred_from_prose(self):
        for tags, extra in ((['Completed adventure'], ''),
                            (['Completed', 'In-Progress'], ''),
                            (['Completed'], '<meta name="aharana:story:status" content="Paused"/>'
                             '<meta name="aharana:story:status" content="Cancelled"/>')):
            with self.subTest(tags=tags, extra=extra):
                self.fixture(tags=tags, extra=extra + '<dc:description>Completed</dc:description>')
                self.assertEqual('', self.read()['status'])

    def test_source_less_merged_book_has_empty_unknown_facets(self):
        self.fixture(extra='<dc:identifier>urn:uuid:merged-book</dc:identifier>'
                           '<dc:description>https://www.not-the-source.example/book</dc:description>')
        self.assertEqual({'ok': True, 'authors': [], 'status': '', 'languages': [],
                          'series': [], 'tags': [], 'site': ''}, self.read())

    def test_site_uses_source_before_url_identifier_and_never_validates_story_urls(self):
        for extra, expected in (
            ('<dc:source>https://WWW.Private.EXAMPLE:443/not-a-supported-story</dc:source>'
             '<dc:identifier>https://other.example/book</dc:identifier>', 'private.example'),
            ('<dc:identifier>urn:uuid:not-a-url</dc:identifier>'
             '<dc:identifier>HTTPS://WWW.Archive.EXAMPLE/book</dc:identifier>', 'archive.example'),
            ('<dc:source> </dc:source>'
             '<dc:identifier>https://www.fallback.example/book</dc:identifier>', 'fallback.example'),
            ('<dc:identifier>ISBN:9780000000000</dc:identifier>', ''),
            ('<dc:source>not a URL</dc:source>', ''),
            ('<dc:source>http://[broken</dc:source>', ''),
        ):
            with self.subTest(extra=extra):
                self.fixture(extra=extra)
                result = self.read()
                self.assertTrue(result['ok'], result)
                self.assertEqual(expected, result['site'])

    def test_missing_corrupt_and_unreadable_books_fail_with_only_fixed_safe_error(self):
        expected = {'ok': False, 'error': 'Saved book filters could not be read.'}
        self.assertEqual(expected, self.read())
        with open(self.epub, 'wb') as stream:
            stream.write(b'private invalid ZIP')
        self.assertEqual(expected, self.read())
        for entries in ({'Books/book.opf': '<package>private invalid XML'},
                        {'mimetype': 'application/epub+zip'},
                        {'Books/book.opf': '<package xmlns="http://www.idpf.org/2007/opf"/>'}):
            with self.subTest(entries=entries):
                with zipfile.ZipFile(self.epub, 'w') as archive:
                    for name, text in entries.items():
                        archive.writestr(name, text)
                self.assertEqual(expected, self.read())
        self.fixture()
        with patch('library_facets.zipfile.ZipFile', side_effect=PermissionError('private path')):
            self.assertEqual(expected, self.read())

    def test_bridge_exceptions_and_reader_errors_use_same_safe_failure(self):
        import fanficfare_bridge
        self.fixture()
        expected = {'ok': False, 'error': 'Saved book filters could not be read.'}
        for error in (RuntimeError('private token'), ImportError('private import')):
            with patch('library_facets.get_library_facets', side_effect=error):
                self.assertEqual(expected, json.loads(fanficfare_bridge.get_library_facets(self.epub)))
        os.remove(self.epub)
        self.assertEqual(expected, json.loads(fanficfare_bridge.get_library_facets(self.epub)))

    def test_extraction_is_offline_read_only_and_does_not_import_engine_or_config(self):
        import epub_editor
        import fanficfare_bridge
        self.fixture(authors=['Doe, Jane'], languages=['en'], tags=['Completed'],
                     extra='<dc:source>https://www.offline.example/book</dc:source>')
        with open(self.epub, 'rb') as stream:
            before = hashlib.sha256(stream.read()).digest()
        real_import = builtins.__import__

        def offline_import(name, *args, **kwargs):
            if name == 'fanficfare' or name.startswith('fanficfare.') or name == 'fanficfare_config':
                raise AssertionError('Engine/config must not be imported')
            return real_import(name, *args, **kwargs)

        with ExitStack() as stack:
            stack.enter_context(patch('builtins.__import__', side_effect=offline_import))
            for target in ('socket.create_connection', 'socket.socket.connect',
                           'fanficfare_bridge.build_configuration', 'fanficfare_bridge._import_fanficfare',
                           'epub_editor.write_metadata', 'epub_editor.write_metadata_and_cover'):
                stack.enter_context(patch(target, side_effect=AssertionError('Offline reads only')))
            # Reload verifies the new module's import path as well as both API calls.
            module = importlib.import_module('library_facets')
            importlib.reload(module)
            result = module.get_library_facets(self.epub)
            bridge_result = json.loads(fanficfare_bridge.get_library_facets(self.epub))
        self.assertEqual(result, bridge_result)
        self.assertTrue(result['ok'], result)
        self.assertEqual('Completed', result['status'])
        self.assertEqual(['Doe, Jane'], result['authors'])
        self.assertEqual('offline.example', result['site'])
        with open(self.epub, 'rb') as stream:
            self.assertEqual(before, hashlib.sha256(stream.read()).digest())
        self.assertEqual(['book.epub'], os.listdir(self.tmp.name))

    def test_bridge_exposes_same_json_schema(self):
        import fanficfare_bridge
        self.assertTrue(hasattr(fanficfare_bridge, 'get_library_facets'),
                        'Offline library facets bridge is missing')
        self.fixture(authors=['Writer'], tags=['Completed'])
        self.assertEqual(self.read(), json.loads(fanficfare_bridge.get_library_facets(self.epub)))


if __name__ == '__main__':
    unittest.main()
