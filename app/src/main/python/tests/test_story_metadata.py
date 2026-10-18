"""Read-only legacy/modern story detail extraction; no adapter or URL required."""
import hashlib
import importlib
import json
import os
import socket
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from xml.sax.saxutils import escape


class StoryMetadataTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.epub = os.path.join(self.tmp.name, 'merged.epub')

    def fixture(self, tags=(), extra='', description=''):
        metadata = ''.join('<dc:subject>%s</dc:subject>' % escape(tag) for tag in tags)
        opf = ('<package xmlns="http://www.idpf.org/2007/opf" '
               'xmlns:opf="http://www.idpf.org/2007/opf" '
               'xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">'
               '<metadata><dc:title>Merged book without source URL</dc:title>'
               '<dc:description>%s</dc:description>%s%s</metadata>'
               '<manifest/><spine/></package>') % (escape(description), metadata, extra)
        with zipfile.ZipFile(self.epub, 'w') as archive:
            archive.writestr('mimetype', 'application/epub+zip')
            archive.writestr('META-INF/container.xml', '<container><rootfiles><rootfile full-path="Books/book.opf"/></rootfiles></container>')
            archive.writestr('Books/book.opf', opf)
            archive.writestr('Books/chapter.xhtml', '<html><body>Unchanged</body></html>')

    def read(self):
        self.assertIsNotNone(importlib.util.find_spec('story_metadata'), 'Offline story metadata API is missing')
        return importlib.import_module('story_metadata').read_story_details(self.epub)

    def test_explicit_stats_take_priority_independently_over_legacy_tags(self):
        self.fixture(['Completed', 'Last Update: 2013/08/29'], extra=(
            '<meta name="aharana:story:status" content="In-Progress"/>'
            '<meta name="aharana:story:date_updated" content="2024-04-05T12:34:56+02:00"/>'
            '<meta property="dcterms:modified">2026-10-17T00:00:00Z</meta>'))
        result = self.read()
        self.assertEqual('In-Progress', result['status'])
        self.assertEqual('2024-04-05T12:34:56+02:00', result['date_updated'])
        self.assertEqual('metadata', result['status_source'])
        self.assertEqual('metadata', result['date_updated_source'])
        self.fixture(['Completed', 'Last Update: 2013/08/29'], extra=(
            '<meta property="aharana:story:status">Paused</meta>'))
        result = self.read()
        self.assertEqual(('Paused', 'metadata', '2013-08-29', 'tags'),
                         tuple(result[k] for k in ('status', 'status_source', 'date_updated', 'date_updated_source')))

    def test_malformed_conflicting_and_month_bucket_tags_are_unknown(self):
        for tags in (
            ['Completed', 'In-Progress', 'Last Update: 2013/08/29', 'Last Update: 2014/08/29'],
            ['Last Update Year/Month: 2013/08'],
            ['Last Update: 2013/02/29'],
            ['Last Update: 2013/08/29', 'Last Update: not a date'],
            ['Last Update: 2013/08'], ['Last Update: 29/08/2013'],
            ['Last Update: 2013/8/29'], ['Last Update: 2013/08/29 extra'],
            ['Completed adventure', 'An In-Progress romance', 'Status: Completed'],
        ):
            with self.subTest(tags=tags):
                self.fixture(tags, description='Completed. Last Update: 2013/08/29')
                result = self.read()
                self.assertTrue(result['ok'], result)
                self.assertEqual(('', 'unknown', '', 'unknown'),
                                 tuple(result[k] for k in ('status', 'status_source', 'date_updated', 'date_updated_source')))

    def test_fields_are_independent_and_duplicate_identical_tags_are_unambiguous(self):
        self.fixture(['Completed', 'Last Update: 2013/02/29'])
        result = self.read()
        self.assertEqual(('Completed', 'tags', '', 'unknown'),
                         tuple(result[k] for k in ('status', 'status_source', 'date_updated', 'date_updated_source')))
        self.fixture(['Last Update: 2016/02/29', 'Last Update: 2016/02/29'])
        result = self.read()
        self.assertEqual(('', 'unknown', '2016-02-29', 'tags'),
                         tuple(result[k] for k in ('status', 'status_source', 'date_updated', 'date_updated_source')))
        for status in ('Completed', 'In-Progress', 'Paused', 'Cancelled', 'Hiatus', 'Stub', 'Dropped', 'Inactive', 'Incomplete'):
            with self.subTest(status=status):
                self.fixture([status])
                self.assertEqual(status, self.read()['status'])

    def test_modification_date_requires_epub2_fanficfare_provenance_and_tags_win(self):
        standard = ('<dc:contributor>FanFicFare [https://github.com/JimmXinu/FanFicFare]</dc:contributor>'
                    '<dc:date opf:event="modification">2012-02-03</dc:date>')
        self.fixture(extra=standard)
        result = self.read()
        self.assertEqual(('2012-02-03', 'metadata'),
                         (result['date_updated'], result['date_updated_source']))
        self.fixture(['Last Update: 2013/08/29'], extra=standard)
        result = self.read()
        self.assertEqual(('2013-08-29', 'tags'),
                         (result['date_updated'], result['date_updated_source']))
        for tags, extra in (
            ([], '<dc:date opf:event="modification">2012-02-03</dc:date>'),
            ([], '<meta property="dcterms:modified">2026-10-17T00:00:00Z</meta>'),
            ([], '<meta name="calibre:timestamp" content="2012-02-03T00:00:00"/>'),
            ([], '<dc:date>2012-02-03</dc:date>'),
            ([], '<dc:date opf:event="publication">2012-02-03</dc:date>'),
            (['Last Update: bad'], standard),
            (['Last Update: 2013/08/29', 'Last Update: 2014/08/29'], standard),
        ):
            with self.subTest(tags=tags, extra=extra):
                self.fixture(tags, extra=extra)
                result = self.read()
                self.assertEqual('', result['date_updated'])
                self.assertEqual('unknown', result['date_updated_source'])

    def test_invalid_or_conflicting_explicit_metadata_is_unknown_not_stale_tags(self):
        for extra in (
            '<meta name="aharana:story:date_updated" content="2013-02-29"/>',
            '<meta name="aharana:story:date_updated" content="2013-08"/>',
            '<meta name="aharana:story:date_updated" content="2013-08-29"/>'
            '<meta name="aharana:story:date_updated" content="2014-08-29"/>',
        ):
            with self.subTest(extra=extra):
                self.fixture(['Completed', 'Last Update: 2013/08/29'], extra=extra)
                result = self.read()
                self.assertEqual('', result['date_updated'])
                self.assertEqual('unknown', result['date_updated_source'])
                self.assertEqual('Completed', result['status'])

    def test_missing_corrupt_and_invalid_xml_files_fail_safely(self):
        for content in (None, b'not a ZIP', b'PK broken'):
            with self.subTest(content=content):
                if content is not None:
                    with open(self.epub, 'wb') as stream:
                        stream.write(content)
                result = self.read()
                self.assertFalse(result['ok'], result)
                self.assertEqual('', result['status'])
                self.assertEqual('', result['date_updated'])
                self.assertNotIn(self.epub, json.dumps(result))
        with zipfile.ZipFile(self.epub, 'w') as archive:
            archive.writestr('book.opf', '<package>private malformed XML')
        result = self.read()
        self.assertFalse(result['ok'], result)
        self.assertNotIn('private', json.dumps(result))

    def test_bridge_json_contract_and_safe_failure(self):
        import fanficfare_bridge
        self.assertTrue(hasattr(fanficfare_bridge, 'get_story_details'), 'Bridge wrapper is missing')
        self.fixture(['Completed', 'Last Update: 2013/08/29'])
        self.assertEqual(self.read(), json.loads(fanficfare_bridge.get_story_details(self.epub)))
        with patch('story_metadata.read_story_details', side_effect=RuntimeError('secret=PRIVATE')):
            result = json.loads(fanficfare_bridge.get_story_details(self.epub))
        self.assertFalse(result['ok'])
        self.assertEqual('unknown', result['status_source'])
        self.assertEqual('unknown', result['date_updated_source'])
        self.assertNotIn('PRIVATE', json.dumps(result))

    def test_old_exact_tags_work_offline_without_source_and_do_not_modify_epub(self):
        self.fixture(['Completed', 'Last Update: 2013/08/29', 'Last Update Year/Month: 2013/08'])
        with open(self.epub, 'rb') as stream:
            before = stream.read()
        with patch.object(socket, 'create_connection', side_effect=AssertionError('No network')), \
                patch.object(socket.socket, 'connect', side_effect=AssertionError('No network')):
            result = self.read()
        self.assertEqual({'ok': True, 'status': 'Completed', 'date_updated': '2013-08-29',
                          'status_source': 'tags', 'date_updated_source': 'tags'}, result)
        with open(self.epub, 'rb') as stream:
            self.assertEqual(hashlib.sha256(before).digest(), hashlib.sha256(stream.read()).digest())
        self.assertEqual(['merged.epub'], os.listdir(self.tmp.name))


if __name__ == '__main__':
    unittest.main()
