"""Android's offline shared chapter-core contract (upstream pinned a7d198ab)."""
import argparse
import contextlib
import io
import os
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import call, patch

from upstream_core_helpers import (CH, StagedAdapter, config, load_old, write, read,
                                   chapter_html, legacy, rewrite_chapters)


class NetworkForbiddenCase(unittest.TestCase):
    def setUp(self):
        for target in ('socket.socket.connect', 'socket.socket.connect_ex', 'socket.create_connection'):
            guard = patch(target, side_effect=AssertionError('NETWORK FORBIDDEN'))
            guard.start()
            self.addCleanup(guard.stop)


class ChapterRoundtripTests(NetworkForbiddenCase):
    def test_sequential_preservation_recent_recheck_and_json_schema(self):
        old = write(StagedAdapter(config(), {1: '<p>OLD_ONE</p>', 2: '<p>OLD_TWO</p>'}))
        c = config(update_preserve_deleted_chapters='true', update_check_recent_chapters='1')
        a = StagedAdapter(c, {2: '<p>EDIT_TWO</p>', 3: '<p>NEW_THREE</p>', 4: '<p>NEW_FOUR</p>'})
        load_old(a, old)
        updated = write(a)
        data = read(updated)
        self.assertEqual(list(data[7]), [CH % n for n in (1, 2, 3, 4)])
        self.assertEqual(a.fetched, [CH % n for n in (2, 3, 4)])
        self.assertIn('OLD_ONE', ''.join(chapter_html(updated)))
        self.assertIn('EDIT_TWO', ''.join(chapter_html(updated)))
        self.assertEqual((a.story.chapter_updated_count, a.story.chapter_added_count,
                          a.story.chapter_written_count), (1, 2, 4))
        self.assertEqual(data[8][CH % 1]['url'], CH % 1)
        self.assertEqual(data[8][CH % 1]['site_title'], 'Chapter 1')
        self.assertNotIn('html', data[8][CH % 1])
        b = StagedAdapter(c, {3: '<p>NEW_THREE</p>', 4: '<p>NEW_FOUR</p>', 5: '<p>NEW_FIVE</p>'})
        load_old(b, updated)
        again = write(b)
        self.assertEqual(list(read(again)[7]), [CH % n for n in (1, 2, 3, 4, 5)])
        self.assertEqual(b.story.getChapterCount(), 5)
        self.assertEqual((b.story.chapter_updated_count, b.story.chapter_added_count,
                          b.story.chapter_written_count), (0, 1, 5))
        self.assertIn('EDIT_TWO', ''.join(chapter_html(again)))
        noop = StagedAdapter(c, {3: '<p>NEW_THREE</p>', 4: '<p>NEW_FOUR</p>', 5: '<p>NEW_FIVE</p>'})
        load_old(noop, again)
        self.assertEqual(list(read(write(noop))[7]), [CH % n for n in (1, 2, 3, 4, 5)])
        self.assertEqual((noop.story.chapter_updated_count, noop.story.chapter_added_count,
                          noop.story.chapter_written_count), (0, 0, 5))

    def test_sparse_legacy_metadata_preserves_deleted_url_without_origchapter(self):
        old = write(StagedAdapter(config(), {1: '<p>ONE</p>', 2: '<p>TWO</p>'}))
        old = rewrite_chapters(legacy(old), lambda text: re.sub(
            r'<meta name="chapter(?:origtitle|title)"[^>]*>(?:</meta>)?', '', text))
        a = StagedAdapter(config(update_preserve_deleted_chapters='true'), {2: '<p>TWO</p>'})
        load_old(a, old)
        updated = write(a)
        self.assertEqual(list(read(updated)[7]), [CH % 1, CH % 2])
        self.assertEqual(read(updated)[8][CH % 1]['site_title'], '1')
        self.assertFalse(read(updated)[8][CH % 1]['mark_new'])
        self.assertIn('ONE', ''.join(chapter_html(updated)))

    def test_invalid_chapter_json_falls_back_to_legacy_metadata(self):
        old = write(StagedAdapter(config(), {1: '<p>ONE</p>', 2: '<p>TWO</p>'}))
        for payload in ('{bad-json', '[]', 'null', '42', '&quot;text&quot;',
                        '{&quot;url&quot;: []}', '{&quot;origchapter&quot;: []}'):
            with self.subTest(payload=payload):
                bad = rewrite_chapters(old, lambda text: re.sub(
                    r'(<meta name="fff_chapter_data" content=")[^"]*',
                    lambda match: match[1] + payload, text))
                data = read(bad)
                self.assertEqual(list(data[7]), [CH % 1, CH % 2])
                self.assertEqual(data[8][CH % 1]['origchapter'], 'Chapter 1')
                a = StagedAdapter(config(update_preserve_deleted_chapters='true'), {2: '<p>TWO</p>'})
                load_old(a, bad)
                self.assertEqual(list(read(write(a))[7]), [CH % 1, CH % 2])
        missing = rewrite_chapters(old, lambda text: re.sub(
            r'<meta name="fff_chapter_data"[^>]*>', '<meta name="fff_chapter_data">', text))
        self.assertEqual(list(read(missing)[7]), [CH % 1, CH % 2])

    def test_ancient_anchor_only_urls_survive_sequential_updates(self):
        old = write(StagedAdapter(config(), {1: '<p>ONE</p>', 2: '<p>TWO</p>'}))
        def anchor_only(text):
            match = re.search(r'<meta name="chapterurl" content="([^"]*)"', text)
            text = re.sub(r'<meta name="(?:fff_chapter_data|chapterurl)"[^>]*>(?:</meta>)?', '', text)
            return re.sub(r'<body[^>]*>', lambda _: '<body><a class="chapterurl" href="%s">source</a>' % match[1], text, count=1)
        old = rewrite_chapters(old, anchor_only)
        self.assertEqual(read(old)[1], 2)
        self.assertEqual(list(read(old)[7]), [CH % 1, CH % 2])
        self.assertNotIn('class="chapterurl"', str(read(old)[7][CH % 1]))
        for content, expected in (({2: '<p>TWO</p>', 3: '<p>THREE</p>'}, (1, 2, 3)),
                                  ({3: '<p>THREE</p>', 4: '<p>FOUR</p>'}, (1, 2, 3, 4))):
            a = StagedAdapter(config(update_preserve_deleted_chapters='true'), content)
            load_old(a, old)
            old = write(a)
            self.assertEqual(list(read(old)[7]), [CH % n for n in expected])


    def test_defaults_leave_preservation_and_recent_rechecks_off(self):
        old = write(StagedAdapter(config(), {1: '<p>ONE</p>', 2: '<p>OLD_TWO</p>'}))
        a = StagedAdapter(config(), {2: '<p>EDIT_TWO</p>', 3: '<p>THREE</p>'})
        self.assertFalse(a.preserve_deleted_chapters())
        self.assertFalse(a.recheck_recent_chapters())
        load_old(a, old)
        updated = write(a)
        self.assertEqual(a.fetched, [CH % 3])
        self.assertEqual(list(read(updated)[7]), [CH % 2, CH % 3])
        self.assertIn('OLD_TWO', ''.join(chapter_html(updated)))
        self.assertNotIn('EDIT_TWO', ''.join(chapter_html(updated)))

    def test_source_shrink_preserves_only_when_enabled(self):
        old = write(StagedAdapter(config(), {1: '<p>ONE</p>', 2: '<p>TWO</p>', 3: '<p>THREE</p>'}))
        for preserve, expected in (('false', (2,)), ('true', (1, 2, 3))):
            with self.subTest(preserve=preserve):
                a = StagedAdapter(config(update_preserve_deleted_chapters=preserve), {2: '<p>EDIT_TWO</p>'})
                load_old(a, old)
                data = read(write(a))
                self.assertEqual(list(data[7]), [CH % n for n in expected])
                self.assertEqual(a.fetched, [])
                self.assertEqual(a.story.chapter_added_count, 0)
                self.assertEqual(a.story.chapter_written_count, len(expected))

    def test_recent_window_tracks_old_epub_not_new_source_tail(self):
        old = write(StagedAdapter(config(), {n: '<p>OLD_%d</p>' % n for n in (1, 2, 3)}))
        for recent in ('0', '1'):
            with self.subTest(recent=recent):
                a = StagedAdapter(config(update_check_recent_chapters=recent),
                                  {n: '<p>EDIT_%d</p>' % n for n in (1, 2, 3, 4, 5)})
                load_old(a, old)
                updated = write(a)
                self.assertEqual(a.fetched, [CH % n for n in ((4, 5) if recent == '0' else (3, 4, 5))])
                self.assertEqual(a.story.chapter_updated_count, int(recent))
                self.assertIn('OLD_2', ''.join(chapter_html(updated)))
                self.assertEqual('EDIT_3' in ''.join(chapter_html(updated)), recent == '1')

    def test_recent_failure_reuses_old_content(self):
        old = write(StagedAdapter(config(), {1: '<p>ONE</p>', 2: '<p>TWO</p>'}))
        a = StagedAdapter(config(update_check_recent_chapters='1'),
                          {1: '<p>ONE</p>', 2: RuntimeError('offline recheck failure')})
        load_old(a, old)
        updated = write(a)
        self.assertIn('TWO', ''.join(chapter_html(updated)))
        self.assertEqual((a.story.chapter_updated_count, a.story.chapter_error_count), (0, 0))

    def test_preservation_conflicts_with_explicit_and_configured_ranges(self):
        from fanficfare.exceptions import ConflictingOptions
        for kwargs, range_args in (({}, ('1', '2')), ({'first_chapter': '1'}, ()),
                                   ({'last_chapter': '2'}, ())):
            with self.subTest(kwargs=kwargs):
                a = StagedAdapter(config(update_preserve_deleted_chapters='true', **kwargs), {1: '<p>ONE</p>'})
                a.setChaptersRange(*range_args)
                with self.assertRaises(ConflictingOptions):
                    a.preserve_deleted_chapters()

    def test_mark_new_survives_only_true_not_latestonly_or_off(self):
        for mark in ('true', 'latestonly', 'false'):
            with self.subTest(mark=mark):
                c = config(update_preserve_deleted_chapters='true', mark_new_chapters=mark)
                old = write(StagedAdapter(c, {1: '<p>ONE</p>', 2: '<p>TWO</p>'}))
                a = StagedAdapter(c, {2: '<p>TWO</p>', 3: '<p>THREE</p>'})
                load_old(a, old)
                first = write(a)
                self.assertFalse(read(first)[8][CH % 1]['mark_new'])
                self.assertTrue(read(first)[8][CH % 3]['mark_new'])
                b = StagedAdapter(c, {2: '<p>TWO</p>', 4: '<p>FOUR</p>'})
                load_old(b, first)
                second = read(write(b))
                self.assertEqual(list(second[7]), [CH % n for n in (1, 2, 3, 4)])
                self.assertEqual(second[8][CH % 3]['mark_new'], mark == 'true')
                self.assertTrue(second[8][CH % 4]['mark_new'])
                self.assertFalse(second[8][CH % 2]['mark_new'])

    def test_legacy_title_metadata_maps_to_new_schema_and_keeps_new_flag(self):
        c = config(mark_new_chapters='true', update_preserve_deleted_chapters='true')
        old = write(StagedAdapter(c, {1: '<p>ONE</p>', 2: '<p>TWO</p>'}))
        a = StagedAdapter(c, {1: '<p>ONE</p>', 2: '<p>TWO</p>', 3: '<p>THREE</p>'})
        load_old(a, old)
        old = legacy(write(a))
        self.assertIn('origchapter', read(old)[8][CH % 3])
        b = StagedAdapter(c, {1: '<p>ONE</p>', 2: '<p>TWO</p>', 4: '<p>FOUR</p>'})
        load_old(b, old)
        data = read(write(b))
        self.assertTrue(data[8][CH % 3]['mark_new'])
        self.assertFalse(data[8][CH % 1]['mark_new'])
        self.assertEqual(data[8][CH % 3]['url'], CH % 3)

    def test_chapter_failure_placeholders_are_not_reused_and_retry_without_duplicates(self):
        c = config(continue_on_chapter_error='true', continue_on_chapter_error_try_limit='1')
        a = StagedAdapter(c, {1: '<p>ONE</p>', 2: RuntimeError('fixture failure'), 3: '<p>THREE</p>'})
        broken = write(a)
        self.assertEqual(a.story.chapter_error_count, 2)
        self.assertEqual(a.fetched, [CH % 1, CH % 2])
        self.assertEqual(len(chapter_html(broken)), 3)
        self.assertEqual(read(broken)[1], 1)
        self.assertEqual(list(read(broken)[7]), [CH % 1])
        self.assertIn('CHAPTER ERROR', ''.join(chapter_html(broken)))
        b = StagedAdapter(c, {1: '<p>ONE</p>', 2: '<p>TWO</p>', 3: '<p>THREE</p>'})
        load_old(b, broken)
        fixed = write(b)
        self.assertEqual(b.fetched, [CH % 2, CH % 3])
        self.assertEqual(b.story.chapter_error_count, 0)
        self.assertEqual(list(read(fixed)[7]), [CH % n for n in (1, 2, 3)])
        self.assertEqual(len(chapter_html(fixed)), 3)
        self.assertNotIn('CHAPTER ERROR', ''.join(chapter_html(fixed)))

    def test_titles_roundtrip_json_and_legacy_then_preserve(self):
        from fanficfare.htmlcleanup import removeAllEntities
        for title in ('Plain title', 'A "quoted" title', 'A &quot;quoted&quot; title',
                      'A &amp; B', 'A &lt; B'):
            for legacy_format in (False, True):
                with self.subTest(title=title, legacy=legacy_format):
                    old = write(StagedAdapter(config(), {1: '<p>ONE</p>', 2: '<p>TWO</p>'}, titles={1: title}))
                    if legacy_format:
                        old = legacy(old)
                    a = StagedAdapter(config(update_preserve_deleted_chapters='true'), {2: '<p>TWO</p>'})
                    load_old(a, old)
                    updated = read(write(a))
                    self.assertEqual(removeAllEntities(updated[8][CH % 1]['site_title']), removeAllEntities(title))
                    self.assertFalse(updated[8][CH % 1]['mark_new'])


    def test_unnew_uses_legacy_fallback_for_invalid_or_sparse_json(self):
        from fanficfare.epubutils import unnew_chapters_epub
        c = config(mark_new_chapters='true')
        old = write(StagedAdapter(c, {1: '<p>ONE</p>', 2: '<p>TWO</p>'}))
        a = StagedAdapter(c, {1: '<p>ONE</p>', 2: '<p>TWO</p>', 3: '<p>THREE</p>'})
        load_old(a, old)
        marked = write(a)
        for payload in (None, '{bad-json', '{}', '[]'):
            with self.subTest(payload=payload):
                data = marked if payload is None else rewrite_chapters(marked, lambda text: re.sub(
                    r'(<meta name="fff_chapter_data" content=")[^"]*',
                    lambda match: match[1] + payload, text))
                output = io.BytesIO()
                self.assertTrue(unnew_chapters_epub(io.BytesIO(data), output))
                result = read(output.getvalue())
                self.assertEqual(list(result[7]), [CH % n for n in (1, 2, 3)])
                self.assertNotIn('(new)', ''.join(chapter_html(output.getvalue())))
                self.assertEqual(result[8][CH % 3]['title'], result[8][CH % 3]['origchapter'])
                self.assertFalse(result[8][CH % 3].get('mark_new', False))


class AndroidChapterRetryTests(NetworkForbiddenCase):
    def test_transient_and_busy_http_retries_stay_bounded(self):
        from fanficfare.adapters import base_adapter
        from fanficfare.exceptions import HTTPErrorFFF
        failures = [base_adapter.ChunkedEncodingError('offline'),
                    base_adapter.ProtocolError('offline'), base_adapter.IncompleteRead(0, 1)]
        failures.extend(HTTPErrorFFF(CH % 1, code, 'offline') for code in (429, 500, 502, 503, 504))
        for failure in failures:
            with self.subTest(failure=type(failure).__name__, status=getattr(failure, 'status_code', None)):
                a = StagedAdapter(config(), {1: '<p>ONE</p>'})
                with patch.object(a, 'getChapterText', side_effect=[failure, '<p>ONE</p>']) as fetch, \
                        patch.object(base_adapter.time, 'sleep') as sleep:
                    self.assertEqual(a.getChapterTextNum(CH % 1, 0), '<p>ONE</p>')
                    self.assertEqual(fetch.call_count, 2)
                    self.assertEqual(sleep.call_args_list, [call(5)] if isinstance(failure, HTTPErrorFFF) else [])
                with patch.object(a, 'getChapterText', side_effect=failure) as fetch, \
                        patch.object(base_adapter.time, 'sleep'):
                    with self.assertRaises(type(failure)):
                        a.getChapterTextNum(CH % 1, 0)
                    self.assertEqual(fetch.call_count, 2)

    def test_nonretry_http_errors_propagate_without_sleep(self):
        from fanficfare.adapters import base_adapter
        from fanficfare.exceptions import HTTPErrorFFF
        a = StagedAdapter(config(), {1: '<p>ONE</p>'})
        with patch.object(a, 'getChapterText', side_effect=HTTPErrorFFF(CH % 1, 404, 'missing')) as fetch, \
                patch.object(base_adapter.time, 'sleep') as sleep:
            with self.assertRaises(HTTPErrorFFF):
                a.getChapterTextNum(CH % 1, 0)
            self.assertEqual(fetch.call_count, 1)
            sleep.assert_not_called()


class UpdateGateTests(NetworkForbiddenCase):
    def test_safety_and_opt_in_decisions(self):
        from fanficfare.updategate import decide_update
        cases = [
            (0, 0, {}, 'no_chapters'),  # deliberate Android difference from upstream
            (0, 10, {}, 'no_chapters'),
            (0, 0, dict(updatealways=True, force_update_epub_always=True), 'no_chapters'),
            (2, 2, {}, 'already_contains'),
            (2, 2, dict(preserve_deleted=True), 'proceed'),
            (2, 2, dict(needs_edit_check=True), 'proceed'),
            (2, 2, dict(metaonly=True), 'proceed'),
            (2, 2, dict(updatealways=True), 'proceed'),
            (3, 2, {}, 'more_than_source'),
            (3, 2, dict(needs_edit_check=True), 'more_than_source'),
            (3, 2, dict(preserve_deleted=True), 'proceed'),
            (3, 2, dict(updatealways=True), 'more_than_source'),
            (3, 2, dict(updatealways=True, force_update_epub_always=True), 'proceed'),
            (2, 3, {}, 'proceed'),
        ]
        for old, site, options, expected in cases:
            with self.subTest(old=old, site=site, options=options):
                self.assertEqual(decide_update(old, site, **options), expected)


class CliChapterTests(NetworkForbiddenCase):
    # Options adapted from upstream tests/test_cli_update_gate.py, a7d198ab,
    # FanFicFare team, Apache-2.0. Actual EPUB reader/writer stays real here.
    def run_update(self, content, titles=None, **settings):
        from fanficfare import cli
        options = argparse.Namespace(
            update=True, updatealways=False, force=False, metaonly=False, unnew=False,
            normalize=False, list=False, downloadlist=None, nooutput=False, format='epub',
            interactive=False, jsonmeta=False, jsonmetafile=None, nometachapters=False,
            updatecover=False, save_cache=False, progressbar=False, mozillacookies=None,
            begin=None, end=None, configfile=None, options=None)
        adapter = StagedAdapter(config(update_preserve_deleted_chapters='true', **settings), content, titles=titles)
        writes, warnings = [], []
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as directory:
            target = Path(directory) / 'story.epub'
            target.write_bytes(write(StagedAdapter(config(), {1: '<p>ONE</p>', 2: '<p>TWO</p>'})))
            def write_story(configuration, adapter, fileformat, **kwargs):
                writes.append(fileformat)
                target.write_bytes(write(adapter))
                return str(target)
            with patch.object(cli.adapters, 'getConfigSectionsFor', return_value=['example.com']), \
                    patch.object(cli.adapters, 'getAdapter', return_value=adapter), \
                    patch.object(cli, 'call', return_value=0), \
                    patch.object(cli, 'write_story', side_effect=write_story), \
                    contextlib.redirect_stdout(io.StringIO()):
                defaults = Path(cli.__file__).with_name('defaults.ini').read_text()
                cli.do_download(str(target), options, defaults,
                                '[defaults]\nupdate_preserve_deleted_chapters:true\ninclude_images:false\n',
                                warn=warnings.append, fail=warnings.append)
            result = target.read_bytes()
        self.assertEqual(warnings, [])
        return adapter, writes, result

    def test_cli_equal_count_swap_writes_preserved_epub(self):
        adapter, writes, result = self.run_update({2: '<p>TWO</p>', 3: '<p>THREE</p>'})
        self.assertEqual(writes, ['epub'])
        self.assertEqual(list(read(result)[7]), [CH % 1, CH % 2, CH % 3])
        self.assertEqual(adapter.story.chapter_added_count, 1)


    def test_cli_zero_counters_do_not_discard_order_changes(self):
        adapter, writes, result = self.run_update({2: '<p>TWO</p>', 1: '<p>ONE</p>'})
        self.assertEqual((adapter.story.chapter_updated_count, adapter.story.chapter_added_count), (0, 0))
        self.assertEqual(writes, ['epub'])
        self.assertEqual(list(read(result)[7]), [CH % 2, CH % 1])

    def test_cli_zero_counters_do_not_discard_title_metadata_changes(self):
        adapter, writes, result = self.run_update({1: '<p>ONE</p>', 2: '<p>TWO</p>'},
                                                titles={2: 'Renamed chapter'})
        self.assertEqual((adapter.story.chapter_updated_count, adapter.story.chapter_added_count), (0, 0))
        self.assertEqual(writes, ['epub'])
        self.assertEqual(read(result)[8][CH % 2]['site_title'], 'Renamed chapter')


if __name__ == '__main__':
    unittest.main()
