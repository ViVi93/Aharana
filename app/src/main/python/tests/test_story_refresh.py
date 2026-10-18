"""Offline original-site refresh tests: real Story and EPUB, adapter boundary only."""
import base64
import datetime
import hashlib
import json
import os
import struct
import tempfile
import unittest
import zipfile
import zlib
import xml.etree.ElementTree as ET
from unittest.mock import patch

import epub_editor
import fanficfare_config
from fanficfare.story import Story

ROOT = os.path.dirname(os.path.dirname(__file__))
URL = 'https://www.fanfiction.net/s/12345/1/Example'
OPF = 'OEBPS/content.opf'
def png_image():
    def chunk(kind, payload):
        return (struct.pack('>I', len(payload)) + kind + payload +
                struct.pack('>I', zlib.crc32(kind + payload)))
    return (b'\x89PNG\r\n\x1a\n' +
            chunk(b'IHDR', struct.pack('>IIBBBBB', 100, 150, 8, 2, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress((b'\x00' + b'\x33\x66\x99' * 100) * 150)) +
            chunk(b'IEND', b''))


PNG = png_image()


def gif_image():
    """A real two-colour GIF; reset LZW between pixels for a tiny encoder."""
    width, height = 100, 150
    codes = [code for _ in range(width * height) for code in (4, 1)] + [5]
    packed = bytearray()
    bits = count = 0
    for code in codes:
        bits |= code << count
        count += 3
        while count >= 8:
            packed.append(bits & 255)
            bits >>= 8
            count -= 8
    if count:
        packed.append(bits)
    blocks = b''.join(bytes([len(packed[i:i + 255])]) + packed[i:i + 255]
                      for i in range(0, len(packed), 255))
    return (b'GIF89a' + struct.pack('<HHBBB', width, height, 0x80, 0, 0) +
            b'\x00\x00\x00\x33\x66\x99' + b',' +
            struct.pack('<HHHHB', 0, 0, width, height, 0) + b'\x02' + blocks + b'\x00;')


# Real 100x150 image payloads encoded by ffmpeg; tests need no image libraries.
JPEG = base64.b64decode(
    '/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYwLjMxLjEwMgD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABNAAEBAAAAAAAAAAAAAAAAAAAABgEBAQEAAAAAAAAAAAAAAAAAAAUGEAEAAAAAAAAAAAAAAAAAAAAAEQEAAAAAAAAAAAAAAAAAAAAA/8AAEQgAlgBkAwEiAAIRAAMRAP/aAAwDAQACEQMRAD8AmwGiQQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAH/9k=')
WEBP = base64.b64decode('UklGRiQAAABXRUJQVlA4TBcAAAAvY0AlAAfQsso0s/9hABLC//9KRP9TzgA=')
GIF = gif_image()


def fixture(path, source=URL):
    metadata = '''<dc:title>Manual title</dc:title><dc:creator id="creator">Local author</dc:creator>
    <dc:source>{source}</dc:source><dc:language>en</dc:language>
    <dc:description>Manual description</dc:description><dc:subject>Local tag</dc:subject>
    <dc:date opf:event="publication">2000-01-01</dc:date>
    <dc:date opf:event="modification">2001-01-01</dc:date>
    <meta name="calibre:rating" content="8"/><meta name="numChapters" content="1"/>
    <meta name="cover" content="cover-image"/>'''.format(source=source)
    opf = '''<package xmlns="http://www.idpf.org/2007/opf" xmlns:opf="http://www.idpf.org/2007/opf"
    xmlns:dc="http://purl.org/dc/elements/1.1/" unique-identifier="uid" version="3.0">
    <metadata><dc:identifier id="uid">urn:test:one</dc:identifier>{}</metadata>
    <manifest><item id="cover-image" href="images/cover-old.png" media-type="image/png" properties="cover-image"/>
    <item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/>
    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
    <item id="title" href="title_page.xhtml" media-type="application/xhtml+xml"/></manifest>
    <spine><itemref idref="cover-page"/><itemref idref="title"/><itemref idref="chapter"/></spine>
    <guide><reference type="cover" href="cover.xhtml"/></guide></package>'''.format(metadata)
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr('mimetype', b'application/epub+zip', compress_type=zipfile.ZIP_STORED)
        z.writestr('META-INF/container.xml', '<container><rootfiles><rootfile full-path="'+OPF+'"/></rootfiles></container>')
        z.writestr(OPF, opf)
        z.writestr('OEBPS/images/cover-old.png', PNG)
        z.writestr('OEBPS/images/inline.png', PNG + b'original')
        z.writestr('OEBPS/chapter.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Original chapter.</p><img src="images/inline.png"/><img src="images/cover-old.png"/></body></html>')
        z.writestr('OEBPS/title_page.xhtml', '<html><body>Source title page stays unchanged<img src="images/cover-old.png"/></body></html>')
        z.writestr('OEBPS/cover.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml"><body><img src="images/cover-old.png"/></body></html>')
        z.comment = b'preserve archive comment'


def entries(path):
    with zipfile.ZipFile(path) as z:
        return {i.filename: z.read(i) for i in z.infolist()}


class RefreshTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.epub = os.path.join(self.tmp.name, 'story.epub')
        fixture(self.epub)

    def adapter(self, config, url):
        self.assertEqual(URL, url)
        story = Story(config)
        story.setMetadata('title', 'Online title')
        story.addToList('author', 'Online author')
        story.setMetadata('description', 'Online description')
        story.setMetadata('numChapters', 20)
        story.setMetadata('numWords', '12345')
        story.setMetadata('status', 'Completed')
        story.setMetadata('rating', 'Mature')
        story.setMetadata('datePublished', datetime.datetime(2020, 2, 3))
        story.setMetadata('dateUpdated', datetime.datetime(2024, 4, 5))
        class Adapter:
            def getStoryMetadataOnly(inner, get_cover=True):
                return story
        return Adapter()

    def preview(self, cover=False):
        import story_refresh
        with patch('fanficfare.adapters.getAdapter', side_effect=self.adapter):
            return story_refresh.preview_story_refresh(self.epub, cover)

    def test_cosmetic_description_html_has_readable_preview_and_cannot_be_forced_to_write(self):
        import story_refresh
        def adapter(config, url):
            obj = self.adapter(config, url)
            obj.getStoryMetadataOnly().setMetadata(
                'description', '<div class="_widget_info_spvht_132">Manual description</div>')
            return obj
        with patch('fanficfare.adapters.getAdapter', side_effect=adapter):
            preview = story_refresh.preview_story_refresh(self.epub)
        self.assertTrue(preview['ok'], preview)
        row = next(f for f in preview['fields'] if f['key'] == 'description')
        self.assertFalse(row['changed'])
        self.assertEqual('Manual description', row['display_current'])
        self.assertEqual('Manual description', row['display_online'])
        self.assertIn('<div', preview['online']['description'])
        before = story_refresh._sha256(self.epub)
        # Caller-supplied UI flags are not authority for the write decision.
        row['changed'] = True
        result = story_refresh.apply_story_refresh(self.epub, preview, ['description'])
        self.assertFalse(result['ok'], result)
        self.assertEqual(before, story_refresh._sha256(self.epub))
        self.assertEqual(['story.epub'], os.listdir(self.tmp.name))

    def test_publication_date_only_matches_naive_midnight_without_rewrite(self):
        import story_refresh
        self.assertTrue(epub_editor.write_metadata(self.epub, {'pubdate': '2020-02-03'})['ok'])
        preview = self.preview()
        row = next(f for f in preview['fields'] if f['key'] == 'pubdate')
        self.assertFalse(row['changed'])
        before = story_refresh._sha256(self.epub)
        result = story_refresh.apply_story_refresh(self.epub, preview, ['pubdate'])
        self.assertFalse(result['ok'], result)
        self.assertEqual(before, story_refresh._sha256(self.epub))

    def test_meaningful_description_html_is_compared_and_saved_without_stripping(self):
        import story_refresh
        cases = [
            ('One Two', '<p>One</p><p>Two</p>', 'One\n\nTwo'),
            ('One Two', 'One <em>Two</em>', 'One Two'),
            ('One Two', 'One<br/>Two', 'One\nTwo'),
            ('<a href="https://example.org/a">Same</a>',
             '<a href="https://example.org/b">Same</a>', 'Same'),
            ('<p><strong>One</strong> Two</p>',
             '<p>One <strong>Two</strong></p>', 'One Two'),
        ]
        for current, online, readable in cases:
            with self.subTest(current=current, online=online):
                fixture(self.epub)
                self.assertTrue(epub_editor.write_metadata(self.epub, {'description': current})['ok'])
                def adapter(config, url):
                    obj = self.adapter(config, url)
                    obj.getStoryMetadataOnly().setMetadata('description', online)
                    return obj
                with patch('fanficfare.adapters.getAdapter', side_effect=adapter):
                    preview = story_refresh.preview_story_refresh(self.epub)
                self.assertTrue(preview['ok'], preview)
                row = next(f for f in preview['fields'] if f['key'] == 'description')
                self.assertTrue(row['changed'], row)
                self.assertEqual(readable, row['display_online'])
                result = story_refresh.apply_story_refresh(self.epub, preview, ['description'])
                self.assertTrue(result['ok'], result)
                self.assertEqual(['description'], result['changed_fields'])
                self.assertEqual(online, result['metadata']['description'])

    def test_cosmetic_nested_wrappers_keep_paragraph_emphasis_and_link_semantics(self):
        import story_refresh
        current = '<p>First <em>paragraph</em>.</p><p><a href="https://example.org/a">Second</a></p>'
        online = ('<div class="outer"><p class="x">First <span class="inner"><i>paragraph</i></span>.</p>'
                  '<p><a class="link" href="https://example.org/a">Second</a></p></div>')
        self.assertTrue(epub_editor.write_metadata(self.epub, {'description': current})['ok'])
        def adapter(config, url):
            obj = self.adapter(config, url)
            obj.getStoryMetadataOnly().setMetadata('description', online)
            return obj
        with patch('fanficfare.adapters.getAdapter', side_effect=adapter):
            preview = story_refresh.preview_story_refresh(self.epub)
        self.assertTrue(preview['ok'], preview)
        row = next(f for f in preview['fields'] if f['key'] == 'description')
        self.assertFalse(row['changed'], row)
        self.assertEqual('First paragraph.\n\nSecond', row['display_online'])
        before = story_refresh._sha256(self.epub)
        result = story_refresh.apply_story_refresh(self.epub, preview, ['description', 'title'])
        self.assertTrue(result['ok'], result)
        self.assertEqual(['title'], result['changed_fields'])
        self.assertEqual(current, result['metadata']['description'])
        self.assertNotEqual(before, story_refresh._sha256(self.epub))

    def test_legacy_saved_tags_populate_refresh_current_and_equal_rows_do_not_write(self):
        import story_refresh
        self.assertTrue(epub_editor.write_metadata(self.epub, {
            'tags': ['Completed', 'Last Update: 2013/08/29', 'Last Update Year/Month: 2013/08']})['ok'])
        def adapter(config, url):
            obj = self.adapter(config, url)
            obj.getStoryMetadataOnly().setMetadata('dateUpdated', datetime.datetime(2013, 8, 29))
            return obj
        with patch('fanficfare.adapters.getAdapter', side_effect=adapter):
            preview = story_refresh.preview_story_refresh(self.epub)
        self.assertTrue(preview['ok'], preview)
        self.assertEqual('Completed', preview['current']['status'])
        self.assertEqual('2013-08-29', preview['current']['date_updated'])
        for key in ('status', 'date_updated'):
            self.assertFalse(next(f['changed'] for f in preview['fields'] if f['key'] == key))
        before = story_refresh._sha256(self.epub)
        result = story_refresh.apply_story_refresh(self.epub, preview, ['status', 'date_updated'])
        self.assertFalse(result['ok'], result)
        self.assertEqual(before, story_refresh._sha256(self.epub))

    def test_meaningful_publication_date_time_and_timezone_changes_are_saved(self):
        import story_refresh
        for current, online in (
            ('2020-02-03', '2020-02-04T00:00:00'),
            ('2020-02-03', '2020-02-03T12:34:56'),
            ('2020-02-03', '2020-02-03T00:00:00+00:00'),
            ('2020-02-03T00:00:00+00:00', '2020-02-03T00:00:00+02:00'),
        ):
            with self.subTest(current=current, online=online):
                fixture(self.epub)
                self.assertTrue(epub_editor.write_metadata(self.epub, {'pubdate': current})['ok'])
                def adapter(config, url):
                    obj = self.adapter(config, url)
                    obj.getStoryMetadataOnly().setMetadata('datePublished', datetime.datetime.fromisoformat(online))
                    return obj
                with patch('fanficfare.adapters.getAdapter', side_effect=adapter):
                    preview = story_refresh.preview_story_refresh(self.epub)
                self.assertTrue(preview['ok'], preview)
                self.assertTrue(next(f['changed'] for f in preview['fields'] if f['key'] == 'pubdate'))
                result = story_refresh.apply_story_refresh(self.epub, preview, ['pubdate'])
                self.assertTrue(result['ok'], result)
                self.assertEqual(online, result['metadata']['pubdate'])

    def test_description_cosmetic_whitespace_collapses_but_link_targets_remain_semantic(self):
        import story_refresh
        self.assertTrue(story_refresh._metadata_equal(
            'description', 'One Two', '<span class="x"> One </span> <span>Two </span>'))
        self.assertEqual('One Two', story_refresh._description_values(
            '<span class="x"> One </span> <span>Two </span>')[1])
        self.assertFalse(story_refresh._metadata_equal(
            'description', '<a id="first">Same</a>', '<a id="second">Same</a>'))
        self.assertFalse(story_refresh._metadata_equal(
            'description', '<span>Same</span>', '<span style="font-style:italic">Same</span>'))

    def test_plain_text_paragraph_boundaries_remain_meaningful(self):
        import story_refresh
        self.assertFalse(story_refresh._metadata_equal('description', 'One\n\nTwo', 'One Two'))
        self.assertTrue(story_refresh._metadata_equal('description', 'One\n\nTwo', '<p>One</p><p>Two</p>'))
        self.assertEqual('One\n\nTwo', story_refresh._description_values('One\n\nTwo')[1])

    def test_preview_metadata_normalization_respects_personal_ini_and_image_policy(self):
        import story_refresh
        configdir = os.path.join(self.tmp.name, 'config')
        os.mkdir(configdir)
        with open(os.path.join(configdir, 'personal.ini'), 'w') as f:
            f.write('[defaults]\ninclude_images: true\ndatePublished_format: %B %Y\n'
                    'replace_metadata:\n title=>Online=>Configured\n'
                    '[' + URL + ']\ninclude_images: true\nadditional_images: file:///bad\n')
        def adapter(config, url):
            self.assertFalse(config.getConfig('include_images'))
            result = self.adapter(config, url)
            story = result.getStoryMetadataOnly()
            story.addToList('genre', 'Adventure')
            story.setMetadata('langcode', 'en')
            story.setMetadata('series', 'The series [2.5]')
            return result
        with patch.object(fanficfare_config, '_ANDROID_CONFIG_DIR', configdir), \
                patch('fanficfare.adapters.getAdapter', side_effect=adapter):
            result = story_refresh.preview_story_refresh(self.epub)
        self.assertTrue(result['ok'], result)
        online = result['online']
        self.assertEqual('Configured title', online['title'])
        self.assertEqual('2020-02-03T00:00:00', online['pubdate'])
        self.assertEqual('2024-04-05T00:00:00', online['date_updated'])
        self.assertEqual('12345', online['word_count'])
        self.assertEqual('Completed', online['status'])
        self.assertEqual(['en'], online['languages'])
        self.assertIn('Adventure', online['tags'])
        self.assertEqual('The series', online['series'])
        self.assertEqual('2.5', online['series_index'])

    def test_preview_rejects_unsafe_sources_without_network_and_redacts_errors(self):
        import story_refresh
        for source in ('', 'not a URL', 'https://user:secret@www.fanfiction.net/s/1/1',
                       'file:///private', 'https://www.fanfiction.net:bad/s/1/1',
                       'https://www.fanfiction.net/space here', 'https://www.fanfiction.net/%0a'):
            with self.subTest(source=source):
                fixture(self.epub, source)
                with patch('fanficfare.adapters.getAdapter') as factory:
                    result = story_refresh.preview_story_refresh(self.epub)
                self.assertFalse(result['ok'])
                factory.assert_not_called()
                self.assertNotIn('secret', json.dumps(result))
        fixture(self.epub)
        with patch('fanficfare.adapters.getAdapter', side_effect=RuntimeError('password=SECRET')):
            result = story_refresh.preview_story_refresh(self.epub)
        self.assertFalse(result['ok'])
        self.assertEqual('RuntimeError', result['exception_type'])
        self.assertNotIn('SECRET', json.dumps(result))
        self.assertTrue(result['detail'])

    def test_cover_preview_uses_only_already_fetched_story_cover(self):
        import story_refresh
        def adapter(config, url):
            self.assertEqual('coveronly', config.getConfig('include_images'))
            obj = self.adapter(config, url)
            story = obj.getStoryMetadataOnly()
            story.img_store.add_img('https://example.org/inline.png', 'png', 'image/png', PNG + b'inline')
            info = story.img_store.add_img('https://example.org/cover.png', 'png', 'image/png', PNG, cover=True)
            story.cover = info['newsrc']
            return obj
        with patch('fanficfare.adapters.getAdapter', side_effect=adapter):
            result = story_refresh.preview_story_refresh(self.epub, True)
        self.assertTrue(result['ok'], result)
        self.assertTrue(result['cover']['available'])
        self.assertEqual(PNG, base64.b64decode(result['cover']['image_data']))
        self.assertEqual(('image/png', 100, 150), tuple(result['cover'][k] for k in ('mime_type', 'width', 'height')))
        with patch.object(Story, 'getImgUrls', side_effect=AssertionError('images disabled')):
            self.assertTrue(self.preview()['ok'])

    def test_selective_metadata_apply_is_offline_atomic_and_preserves_every_resource(self):
        import story_refresh
        preview = self.preview()
        before = entries(self.epub)
        with open(self.epub, 'rb') as f:
            original_bytes = f.read()
        selected = ['title', 'authors', 'pubdate', 'status', 'word_count', 'date_updated']
        with patch('fanficfare.adapters.getAdapter', side_effect=AssertionError('apply is offline')):
            result = story_refresh.apply_story_refresh(self.epub, json.dumps(preview), json.dumps(selected))
        self.assertTrue(result['ok'], result)
        self.assertTrue(result['metadata_written'])
        self.assertFalse(result['cover_written'])
        self.assertTrue(result['chapters_preserved'])
        self.assertEqual(set(selected), set(result['changed_fields']))
        self.assertEqual(preview['source_sha256'], result['source_sha256'])
        with open(result['backup_path'], 'rb') as f:
            self.assertEqual(original_bytes, f.read())
        after = entries(self.epub)
        self.assertEqual(set(before), set(after))
        for name in before:
            if name != OPF:
                self.assertEqual(hashlib.sha256(before[name]).digest(), hashlib.sha256(after[name]).digest(), name)
        self.assertEqual('Online title', result['metadata']['title'])
        self.assertEqual('Manual description', result['metadata']['description'])
        self.assertEqual('8', result['metadata']['rating'])
        root = ET.fromstring(after[OPF])
        self.assertIn('2001-01-01', [e.text for e in root.findall('.//{'+epub_editor.DC_NS+'}date')])
        self.assertEqual('1', next(e.get('content') for e in root.iter() if e.get('name') == 'numChapters'))
        for key in ('status', 'word_count', 'date_updated'):
            self.assertEqual(preview['online'][key], result['metadata'][key])
        with zipfile.ZipFile(self.epub) as z:
            self.assertEqual(b'preserve archive comment', z.comment)
            self.assertEqual(zipfile.ZIP_STORED, z.getinfo('mimetype').compress_type)

    def test_apply_rejects_invalid_selection_values_and_stale_preview_without_writing(self):
        import copy
        import story_refresh
        preview = self.preview()
        before = entries(self.epub)
        invalid = [([], {}, None), (['rating'], {}, None), (['title', 'title'], {}, None),
                   (['title'], {'title': {'bad': 'object'}}, None),
                   (['title'], {'title': chr(0) + 'invalid'}, None),
                   (['title'], {'title': 'x' * 8193}, None),
                   (['authors'], {'authors': 'not a list'}, None),
                   (['pubdate'], {'pubdate': 'not an ISO date'}, None),
                   (['word_count'], {'word_count': '-1'}, None),
                   (['title'], {}, '0' * 64)]
        for selected, changed, digest in invalid:
            with self.subTest(selected=selected, changed=list(changed), digest=digest):
                data = copy.deepcopy(preview)
                data['online'].update(changed)
                if digest:
                    data['source_sha256'] = digest
                result = story_refresh.apply_story_refresh(self.epub, json.dumps(data), json.dumps(selected))
                self.assertFalse(result['ok'], result)
                self.assertEqual(before, entries(self.epub))
                self.assertEqual(['story.epub'], os.listdir(self.tmp.name))
        data = copy.deepcopy(preview)
        data['source_url'] = 'https://wrong.example/story'
        self.assertFalse(story_refresh.apply_story_refresh(self.epub, data, ['title'])['ok'])
        data = copy.deepcopy(preview)
        data['online']['rating'] = 'Mature'
        self.assertFalse(story_refresh.apply_story_refresh(self.epub, data, ['title'])['ok'])
        with zipfile.ZipFile(self.epub, 'a') as z:
            z.writestr('new-resource.txt', 'changed after preview')
        self.assertFalse(story_refresh.apply_story_refresh(self.epub, preview, ['title'])['ok'])

    def test_cover_and_combined_apply_preserve_chapters_titlepage_and_old_cover(self):
        import story_refresh
        for selected in ([], ['title']):
            with self.subTest(selected=selected):
                fixture(self.epub)
                preview = self.preview()
                preview['cover'] = {'available': True, 'image_data': base64.b64encode(PNG + b'new').decode(),
                                    'mime_type': 'image/png', 'width': 100, 'height': 150}
                before = entries(self.epub)
                result = story_refresh.apply_story_refresh(self.epub, preview, selected, True)
                self.assertTrue(result['ok'], result)
                self.assertTrue(result['cover_written'])
                self.assertEqual(bool(selected), result['metadata_written'])
                after = entries(self.epub)
                for name in before:
                    if name not in (OPF, 'OEBPS/cover.xhtml'):
                        self.assertEqual(hashlib.sha256(before[name]).digest(), hashlib.sha256(after[name]).digest(), name)
                self.assertNotEqual(before['OEBPS/cover.xhtml'], after['OEBPS/cover.xhtml'])
                root = ET.fromstring(after[OPF])
                cover_id = next(e.get('content') for e in root.iter() if e.get('name') == 'cover')
                item = next(e for e in root.iter() if e.get('id') == cover_id)
                self.assertEqual(PNG + b'new', after['OEBPS/' + item.get('href')])
                self.assertNotEqual('images/cover-old.png', item.get('href'))
                self.assertIn(item.get('href'), after['OEBPS/cover.xhtml'].decode())
                self.assertTrue(any(e.get('href') == 'images/cover-old.png' for e in root.iter()))
                self.assertEqual(1, len([e for e in root.iter() if 'cover-image' in e.get('properties', '').split()]))

    def test_repeated_refresh_backups_are_not_imported_by_library_rescan(self):
        import fanficfare_bridge
        import story_refresh
        from pathlib import Path
        backups = []
        for title in ('First refreshed title', 'Second refreshed title'):
            before = Path(self.epub).read_bytes()
            preview = self.preview()
            preview['online']['title'] = title
            result = story_refresh.apply_story_refresh(self.epub, preview, ['title'])
            self.assertTrue(result['ok'], result)
            backup = result['backup_path']
            backups.append(backup)
            self.assertEqual(before, Path(backup).read_bytes())
            self.assertTrue(zipfile.is_zipfile(backup))
            self.assertEqual('Original chapter.', ET.fromstring(
                entries(backup)['OEBPS/chapter.xhtml']).find('.//{http://www.w3.org/1999/xhtml}p').text)
            scan = json.loads(fanficfare_bridge.scan_epub_dir(self.tmp.name))
            self.assertTrue(scan['ok'], scan)
            self.assertEqual([self.epub], [book['path'] for book in scan['books']])
            self.assertEqual(title, scan['books'][0]['title'])
            self.assertFalse(backup.lower().endswith('.epub'))
        self.assertEqual(2, len(set(backups)))
        self.assertTrue(all(os.path.exists(path) for path in backups))

    def test_library_rescan_during_atomic_publish_ignores_staged_epub(self):
        import fanficfare_bridge
        import story_refresh
        scans = []
        replace = os.replace
        def publish(staged, target):
            scans.append(json.loads(fanficfare_bridge.scan_epub_dir(self.tmp.name)))
            return replace(staged, target)
        preview = self.preview()
        with patch('story_refresh.os.replace', side_effect=publish):
            result = story_refresh.apply_story_refresh(self.epub, preview, ['title'])
        self.assertTrue(result['ok'], result)
        self.assertEqual(1, len(scans))
        self.assertTrue(scans[0]['ok'], scans[0])
        self.assertEqual([self.epub], [book['path'] for book in scans[0]['books']])

    def test_scan_excludes_legacy_refresh_backups_but_keeps_user_epubs(self):
        import fanficfare_bridge
        ignored = '.story-refresh-backup-a1_b2c3d.epub'
        # Only the exact generated tempfile shape is private, not arbitrary backups.
        visible = ['backup.epub', 'story-refresh-backup-a1_b2c3d.epub',
                   '.story-refresh-backup-my-story.epub',
                   '.story-refresh-backup-short.epub', '.reader.epub']
        nested = os.path.join(self.tmp.name, 'nested')
        os.mkdir(nested)
        for name in [ignored] + visible:
            fixture(os.path.join(nested, name))
        scan = json.loads(fanficfare_bridge.scan_epub_dir(self.tmp.name))
        self.assertTrue(scan['ok'], scan)
        self.assertEqual({self.epub} | {os.path.join(nested, name) for name in visible},
                         {book['path'] for book in scan['books']})

    def test_second_preview_reads_custom_stats_and_reapply_has_no_duplicates(self):
        import story_refresh
        preview = self.preview()
        preview['online']['authors'] = ['Online author', 'Second author']
        selected = ['authors', 'status', 'word_count', 'date_updated', 'pubdate']
        first = story_refresh.apply_story_refresh(self.epub, preview, selected)
        self.assertTrue(first['ok'], first)
        second = self.preview()
        for key in ('status', 'word_count', 'date_updated'):
            self.assertEqual(preview['online'][key], second['current'][key])
            self.assertFalse(next(f['changed'] for f in second['fields'] if f['key'] == key))
        second['online']['status'] = 'In-Progress'
        second['online']['authors'] = ['Online author', 'Second author']
        result = story_refresh.apply_story_refresh(self.epub, second, selected)
        self.assertTrue(result['ok'], result)
        self.assertEqual(['status'], result['changed_fields'])
        root = ET.fromstring(entries(self.epub)[OPF])
        ids = [e.get('id') for e in root.iter() if e.get('id')]
        self.assertEqual(len(ids), len(set(ids)))
        for name in story_refresh.CUSTOM.values():
            self.assertEqual(1, len([e for e in root.iter() if e.get('name') == name]))
        self.assertEqual(2, len(root.findall('.//{'+epub_editor.DC_NS+'}date')))

    def test_preview_rejects_source_changed_during_network_fetch(self):
        import story_refresh
        def adapter(config, url):
            with zipfile.ZipFile(self.epub, 'a') as z:
                z.writestr('changed-during-fetch', b'new data')
            return self.adapter(config, url)
        with patch('fanficfare.adapters.getAdapter', side_effect=adapter):
            result = story_refresh.preview_story_refresh(self.epub)
        self.assertFalse(result['ok'], result)
        self.assertIn('changed', result['error'])

    def test_cover_only_apply_accepts_preview_with_no_online_metadata(self):
        import story_refresh
        preview = self.preview()
        preview['online'] = {}
        preview['fields'] = []
        preview['cover'] = {'available': True, 'image_data': base64.b64encode(PNG + b'new').decode(),
                            'mime_type': 'image/png', 'width': 100, 'height': 150}
        result = story_refresh.apply_story_refresh(self.epub, preview, [], True)
        self.assertTrue(result['ok'], result)
        self.assertEqual('Manual title', result['metadata']['title'])

    def test_bridge_and_upstream_cover_extraction_return_newest_cover(self):
        import fanficfare_bridge
        import story_refresh
        from fanficfare.epubutils import get_cover_img
        for suffix in (b'first', b'second'):
            preview = self.preview()
            preview['cover'] = {'available': True, 'image_data': base64.b64encode(PNG + suffix).decode(),
                                'mime_type': 'image/png', 'width': 100, 'height': 150}
            result = story_refresh.apply_story_refresh(self.epub, preview, [], True)
            self.assertTrue(result['ok'], result)
            bridge = json.loads(fanficfare_bridge.get_cover_from_epub(self.epub))
            self.assertTrue(bridge['ok'], bridge)
            self.assertEqual('image/png', get_cover_img(self.epub)[0])
            self.assertEqual(PNG + suffix, get_cover_img(self.epub)[1])
            self.assertEqual(PNG + suffix, base64.b64decode(bridge['cover'].split(',', 1)[1]))
            with zipfile.ZipFile(self.epub) as z:
                self.assertEqual('mimetype', z.namelist()[0])
                self.assertEqual(len(z.namelist()), len(set(z.namelist())))

    def test_bridge_reads_declared_cover_in_all_refresh_formats(self):
        import fanficfare_bridge
        import story_refresh
        from cover_manager import validate_image_content
        from fanficfare.epubutils import get_cover_img
        for image, mime in ((GIF, 'image/gif'), (WEBP, 'image/webp'),
                            (JPEG, 'image/jpeg'), (PNG, 'image/png')):
            with self.subTest(mime=mime):
                before = entries(self.epub)
                preview = self.preview()
                preview['cover'] = dict(validate_image_content(image), available=True,
                                        image_data=base64.b64encode(image).decode())
                result = story_refresh.apply_story_refresh(self.epub, preview, [], True)
                self.assertTrue(result['ok'], result)
                self.assertEqual((mime, image), get_cover_img(self.epub))
                bridge = json.loads(fanficfare_bridge.get_cover_from_epub(self.epub))
                self.assertTrue(bridge['ok'], bridge)
                self.assertEqual('data:' + mime + ';base64', bridge['cover'].split(',', 1)[0])
                self.assertEqual(image, base64.b64decode(bridge['cover'].split(',', 1)[1]))
                after = entries(self.epub)
                for name in before:
                    if name not in (OPF, 'OEBPS/cover.xhtml'):
                        self.assertEqual(before[name], after[name], name)

    def test_bridge_honours_epub3_cover_property_with_encoded_generic_filename(self):
        import fanficfare_bridge
        import epub_xml
        before = entries(self.epub)
        root = ET.fromstring(before[OPF])
        ns = '{' + epub_editor.OPF_NS + '}'
        metadata = root.find(ns + 'metadata')
        for elem in list(metadata):
            if elem.get('name') == 'cover':
                metadata.remove(elem)
        manifest = root.find(ns + 'manifest')
        for item in manifest:
            item.attrib.pop('properties', None)
        ET.SubElement(manifest, ns + 'item', {'id': 'actual-cover',
                      'href': 'images/new%20art.bin', 'media-type': 'image/jpeg',
                      'properties': 'cover-image'})
        before[OPF] = epub_xml.serialize(root)
        before['OEBPS/images/new art.bin'] = GIF
        with zipfile.ZipFile(self.epub, 'w') as z:
            for name, data in before.items():
                z.writestr(name, data)
        bridge = json.loads(fanficfare_bridge.get_cover_from_epub(self.epub))
        self.assertTrue(bridge['ok'], bridge)
        self.assertEqual('data:image/gif;base64', bridge['cover'].split(',', 1)[0])
        self.assertEqual(GIF, base64.b64decode(bridge['cover'].split(',', 1)[1]))

    def test_preview_bridge_returns_json_contract(self):
        import fanficfare_bridge
        with patch('fanficfare.adapters.getAdapter', side_effect=self.adapter):
            result = json.loads(fanficfare_bridge.preview_story_refresh(self.epub, False))
        self.assertTrue(result['ok'], result)
        self.assertEqual(URL, result['source_url'])
        self.assertTrue(all(f['selected'] is False for f in result['fields']))

    def test_apply_bridge_separate_output_leaves_source_untouched_and_has_exact_flags(self):
        import fanficfare_bridge
        preview = self.preview()
        with open(self.epub, 'rb') as f:
            original = f.read()
        output = os.path.join(self.tmp.name, 'separate.epub')
        result = json.loads(fanficfare_bridge.apply_story_refresh(
            self.epub, json.dumps(preview), '["title"]', False, output))
        self.assertTrue(result['ok'], result)
        self.assertEqual({'ok', 'output_path', 'backup_path', 'metadata_written', 'cover_written',
                          'changed_fields', 'metadata', 'source_sha256', 'chapters_preserved'}, set(result))
        self.assertEqual(output, result['output_path'])
        self.assertIsNone(result['backup_path'])
        self.assertIs(result['metadata_written'], True)
        self.assertIs(result['cover_written'], False)
        self.assertIs(result['chapters_preserved'], True)
        self.assertEqual('Online title', epub_editor.read_metadata_fields(output)['metadata']['title'])
        with open(self.epub, 'rb') as f:
            self.assertEqual(original, f.read())
        with open(output, 'rb') as f:
            before = f.read()
        second = json.loads(fanficfare_bridge.apply_story_refresh(
            self.epub, json.dumps(preview), '["title"]', False, output))
        self.assertFalse(second['ok'])
        with open(output, 'rb') as f:
            self.assertEqual(before, f.read())

    def test_bad_cover_never_writes_selected_metadata(self):
        import copy
        import story_refresh
        preview = self.preview()
        before = entries(self.epub)
        valid = {'available': True, 'image_data': base64.b64encode(PNG).decode(),
                 'mime_type': 'image/png', 'width': 100, 'height': 150}
        for changed in ({'available': False}, {'image_data': 'not base64!'},
                        {'mime_type': 'image/jpeg'}, {'width': 999999},
                        {'image_data': base64.b64encode(b'not an image').decode()}):
            data = copy.deepcopy(preview)
            data['cover'] = dict(valid, **changed)
            result = story_refresh.apply_story_refresh(self.epub, data, ['title'], True)
            self.assertFalse(result['ok'], result)
            self.assertEqual(before, entries(self.epub))
            self.assertEqual(['story.epub'], os.listdir(self.tmp.name))

    def test_atomic_install_failure_preserves_original_and_cleans_staging(self):
        import story_refresh
        preview = self.preview()
        with open(self.epub, 'rb') as f:
            before = f.read()
        with patch('story_refresh.os.replace', side_effect=OSError('simulated failure')):
            result = story_refresh.apply_story_refresh(self.epub, preview, ['title'])
        self.assertFalse(result['ok'])
        with open(self.epub, 'rb') as f:
            self.assertEqual(before, f.read())
        self.assertEqual(['story.epub'], os.listdir(self.tmp.name))

    def test_preview_uses_original_site_real_story_and_opt_in_fields(self):
        result = self.preview()
        self.assertTrue(result['ok'])
        self.assertEqual(URL, result['source_url'])
        with open(self.epub, 'rb') as f:
            self.assertEqual(hashlib.sha256(f.read()).hexdigest(), result['source_sha256'])
        self.assertEqual('Manual title', result['current']['title'])
        self.assertEqual('Online title', result['online']['title'])
        self.assertEqual(['Online author'], result['online']['authors'])
        self.assertNotIn('chapters', result['online'])
        self.assertNotIn('rating', result['online'])
        self.assertTrue(all(f['selected'] is False for f in result['fields']))
        self.assertEqual(set(result['online']), {f['key'] for f in result['fields']})
        self.assertFalse(result['cover']['available'])


if __name__ == '__main__':
    unittest.main()
