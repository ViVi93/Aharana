"""Real ZIP fixtures and filesystem integration tests; stdlib only."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def epub(path, title: str | None = 'A Story', creators='<dc:creator opf:role="aut">An Author</dc:creator>', extra='', identifier='https://archiveofourown.org/works/12345'):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)
        z.writestr('META-INF/container.xml', '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        z.writestr('OPS/book.opf', '<package version="3.0" unique-identifier="story-id" xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf"><metadata>' + ('<dc:title>' + title + '</dc:title>' if title is not None else '') + creators + '<dc:identifier id="story-id">' + identifier + '</dc:identifier><dc:language>en</dc:language>' + extra + '</metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>')
        z.writestr('OPS/chapter.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head><body><p>A genuine chapter</p></body></html>')
    return path


def core():
    spec = importlib.util.find_spec('library_organizer')
    assert spec is not None, 'library_organizer module must exist'
    import library_organizer
    return library_organizer


class OrganizerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'library'
        self.root.mkdir()
        self.journal = Path(self.tmp.name) / 'journal'

    def test_android_search_only_ancestors_allow_full_organiser_lifecycle(self):
        module = core()
        source = epub(self.root / 'old.epub')
        original = source.read_bytes()
        native_open = os.open
        denied = []
        ancestors = {str(p) for p in self.root.parents}
        search_only = getattr(os, 'O_PATH', 0x200000)

        def android_open(path, flags, *args, **kwargs):
            if str(path) in ancestors and not flags & search_only:
                denied.append(str(path))
                raise PermissionError(13, 'Permission denied', str(path))
            return native_open(path, flags, *args, **kwargs)

        with mock.patch.object(module.os, 'open', side_effect=android_open):
            plan = module.preview(str(self.root))
            self.assertTrue(plan['ok'], plan)
            self.assertEqual(1, plan['move_count'])
            self.assertEqual(original, source.read_bytes())
            applied = module.apply(plan, str(self.journal))
            self.assertTrue(applied['ok'], applied)
            target = Path(applied['moves'][0]['target'])
            self.assertEqual(original, target.read_bytes())
            self.assertTrue(module.recovery(str(self.journal))['ok'])
            self.assertTrue(module.undo_preview(str(self.journal))['ok'])
            self.assertTrue(module.undo(str(self.journal))['ok'])
            self.assertEqual(original, source.read_bytes())
            download = module.copy_download(str(source), str(self.root / 'new-output'))
            self.assertTrue(download['ok'], download)
            prepared = module.delete_prepare(download['path'], str(self.journal), 51)
            self.assertTrue(prepared['ok'], prepared)
            Path(prepared['delete_path']).unlink()
            self.assertTrue(module.delete_finish(prepared['token'], True, str(self.journal))['ok'])
            self.assertTrue(module.recovery(str(self.journal))['ok'])
        self.assertEqual([], denied, 'Must never attempt to read protected ancestors')

    def test_real_search_only_parent_and_missing_opath_constant(self):
        module = core()
        source = epub(self.root / 'old.epub')
        parent = self.root.parent
        self.journal.mkdir()
        original_mode = parent.stat().st_mode & 0o777
        try:
            parent.chmod(0o111)
            # Linux supports the flag even when Python/Chaquopy does not expose it.
            with mock.patch.object(module.os, 'O_PATH', create=True) as unused:
                del module.os.O_PATH
                preview = module.preview(str(self.root))
                self.assertTrue(preview['ok'], preview)
                applied = module.apply(preview, str(self.journal))
                self.assertTrue(applied['ok'], applied)
                undone = module.undo(str(self.journal))
                self.assertTrue(undone['ok'], undone)
                self.assertTrue(source.is_file())
        finally:
            parent.chmod(original_mode)

    def test_final_directory_read_denial_does_not_leak_search_descriptors(self):
        module = core()
        native_open = os.open
        native_close = os.close
        outstanding = set()
        search_only = getattr(os, 'O_PATH', 0x200000)

        def tracked_open(path, flags, *args, **kwargs):
            if str(path) == '.' and not flags & search_only:
                raise PermissionError(13, 'Permission denied', str(self.root))
            fd = native_open(path, flags, *args, **kwargs)
            outstanding.add(fd)
            return fd

        def tracked_close(fd):
            outstanding.remove(fd)
            return native_close(fd)

        with mock.patch.object(module.os, 'open', side_effect=tracked_open), \
                mock.patch.object(module.os, 'close', side_effect=tracked_close):
            result = module.preview(str(self.root))
            self.assertFalse(result['ok'])
            self.assertIn('Permission denied', result['error'])
        self.assertEqual(set(), outstanding)

    def test_unorganised_delete_through_storage_alias_keeps_lexical_room_path(self):
        module = core()
        original = epub(self.root / 'book.epub')
        alias = Path(self.tmp.name) / 'storage-alias'
        alias.symlink_to(self.root, target_is_directory=True)
        lexical = alias / original.name
        prepared = module.delete_prepare(str(lexical), str(self.journal), 41)
        self.assertTrue(prepared['ok'], prepared)
        self.assertEqual(str(original), prepared['delete_path'])
        Path(prepared['delete_path']).unlink()
        recovered = module.recovery(str(self.journal))
        self.assertTrue(recovered['ok'], recovered)
        self.assertIn({'book_id': 41, 'path': str(lexical)}, recovered['deleted_books'])
        self.assertFalse(original.exists())

    def test_organised_delete_via_alias_still_retires_history(self):
        module = core()
        original = epub(self.root / 'old.epub')
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        target = Path(applied['moves'][0]['target'])
        alias = Path(self.tmp.name) / 'storage-alias'
        alias.symlink_to(self.root, target_is_directory=True)
        lexical = alias / target.relative_to(self.root)
        prepared = module.delete_prepare(str(lexical), str(self.journal), 42)
        self.assertTrue(prepared['ok'], prepared)
        Path(prepared['delete_path']).unlink()
        finished = module.delete_finish(prepared['token'], True, str(self.journal))
        self.assertTrue(finished['ok'], finished)
        epub(target, title='Replacement', identifier='different-story')
        recovered = module.recovery(str(self.journal))
        self.assertTrue(recovered['ok'], recovered)
        self.assertEqual([], recovered['moves'])
        self.assertEqual([], module.undo_preview(str(self.journal))['moves'])
        self.assertTrue(module.undo(str(self.journal))['ok'])
        self.assertTrue(target.exists())
        self.assertFalse(original.exists())

    def test_alias_retarget_does_not_change_prepared_delete_identity(self):
        module = core()
        original = epub(self.root / 'book.epub')
        other_root = Path(self.tmp.name) / 'other-library'
        replacement = epub(other_root / 'book.epub', identifier='replacement')
        alias = Path(self.tmp.name) / 'storage-alias'
        alias.symlink_to(self.root, target_is_directory=True)
        lexical = alias / 'book.epub'
        prepared = module.delete_prepare(str(lexical), str(self.journal), 43)
        self.assertTrue(prepared['ok'], prepared)
        alias.unlink()
        alias.symlink_to(other_root, target_is_directory=True)
        failed = module.delete_finish(prepared['token'], False, str(self.journal))
        self.assertTrue(failed['ok'], failed)
        self.assertEqual([], module.recovery(str(self.journal))['deleted_books'])
        self.assertTrue(original.exists())
        self.assertTrue(replacement.exists())
        prepared = module.delete_prepare(str(original), str(self.journal), 44)
        self.assertTrue(prepared['ok'], prepared)
        Path(prepared['delete_path']).unlink()
        recovered = module.recovery(str(self.journal))
        self.assertTrue(recovered['ok'], recovered)
        self.assertIn({'book_id': 44, 'path': str(original)}, recovered['deleted_books'])
        self.assertTrue(replacement.exists())

    def test_delete_protocol_skips_deleted_book_and_undo_restores_survivor(self):
        module = core()
        first = epub(self.root / 'a.epub', title='First')
        second = epub(self.root / 'b.epub', title='Second')
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        self.assertTrue(applied['ok'], applied)
        target = Path(applied['moves'][1]['target'])
        prepared = json.loads(module.json_call('delete_prepare', json.dumps({
            'path': str(target)}), str(self.journal)))
        self.assertTrue(prepared['ok'], prepared)
        self.assertTrue(prepared['token'])
        pending = json.loads((self.journal / 'organizer.json').read_text())
        self.assertEqual('pending', pending['deletions'][-1]['state'])
        target.unlink()
        finished = json.loads(module.json_call('delete_finish', json.dumps({
            'token': prepared['token'], 'deleted': True}), str(self.journal)))
        self.assertTrue(finished['ok'], finished)
        self.assertEqual([applied['moves'][0]], module.recovery(str(self.journal))['moves'])
        undone = module.undo(str(self.journal))
        self.assertTrue(undone['ok'], undone)
        self.assertEqual([{'source': applied['moves'][0]['target'],
                           'target': str(first)}], undone['moves'])
        self.assertTrue(first.exists())
        self.assertFalse(second.exists())
        self.assertFalse(undone['can_undo'])

    def test_failed_delete_restores_aliases_and_undo_eligibility(self):
        module = core()
        source = epub(self.root / 'old.epub')
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        target = applied['moves'][0]['target']
        prepared = json.loads(module.json_call('delete_prepare', json.dumps({
            'path': target}), str(self.journal)))
        self.assertTrue(prepared['ok'], prepared)
        finished = json.loads(module.json_call('delete_finish', json.dumps({
            'token': prepared['token'], 'deleted': False}), str(self.journal)))
        self.assertTrue(finished['ok'], finished)
        recovered = module.recovery(str(self.journal))
        self.assertTrue(recovered['can_undo'])
        self.assertEqual(applied['moves'], recovered['moves'])
        self.assertTrue(module.undo(str(self.journal))['ok'])
        self.assertTrue(source.exists())

    def test_crashed_pending_delete_recovers_from_exact_file_evidence(self):
        module = core()
        for outcome in ('unchanged', 'absent', 'reused'):
            with self.subTest(outcome=outcome):
                root = self.root / outcome
                source = epub(root / 'old.epub')
                journal = self.journal / outcome
                applied = module.apply(module.preview(str(root)), str(journal))
                target = Path(applied['moves'][0]['target'])
                save = module._save

                def crash_after_prepare(directory, data):
                    save(directory, data)
                    if any(d['state'] == 'pending' for d in data.get('deletions', [])):
                        raise KeyboardInterrupt('process death after durable prepare')

                with mock.patch.object(module, '_save', side_effect=crash_after_prepare):
                    with self.assertRaises(KeyboardInterrupt):
                        module.json_call('delete_prepare', json.dumps({'path': str(target)}), str(journal))
                replacement = None
                if outcome != 'unchanged':
                    target.unlink()
                if outcome == 'reused':
                    # Even a replacement with the SAME story id is not the deleted object.
                    epub(target, title='A new download of this story')
                    replacement = target.read_bytes()
                for _ in range(2):
                    recovered = module.recovery(str(journal))
                    self.assertTrue(recovered['ok'], recovered)
                    self.assertEqual(outcome == 'unchanged', recovered['can_undo'])
                    self.assertEqual(applied['moves'] if outcome == 'unchanged' else [], recovered['moves'])
                    saved = json.loads((journal / 'organizer.json').read_text())
                    self.assertEqual('cancelled' if outcome == 'unchanged' else 'deleted',
                                     saved['deletions'][-1]['state'])
                undone = module.undo(str(journal))
                self.assertTrue(undone['ok'], undone)
                self.assertEqual(outcome == 'unchanged', source.exists())
                if outcome == 'reused':
                    self.assertEqual(replacement, target.read_bytes())

    def test_deleted_lineage_never_revives_when_historical_target_is_reused(self):
        module = core()
        source = epub(self.root / 'old.epub')
        first = module.apply(module.preview(str(self.root)), str(self.journal))
        middle = Path(first['moves'][0]['target'])
        epub(middle, title='Renamed')
        second = module.apply(module.preview(str(self.root)), str(self.journal))
        target = Path(second['moves'][0]['target'])
        prepared = json.loads(module.json_call('delete_prepare', json.dumps({
            'path': str(target)}), str(self.journal)))
        self.assertTrue(prepared['ok'], prepared)
        target.unlink()
        self.assertTrue(json.loads(module.json_call('delete_finish', json.dumps({
            'token': prepared['token'], 'deleted': True}), str(self.journal)))['ok'])
        # Same story identifier must not revive a known-deleted identity, either.
        epub(middle, title='Fresh download')
        epub(target, title='Another fresh download')
        before = middle.read_bytes(), target.read_bytes()
        for _ in range(2):
            recovered = module.recovery(str(self.journal))
            self.assertTrue(recovered['ok'], recovered)
            self.assertEqual([], recovered['moves'])
            self.assertFalse(recovered['can_undo'])
            self.assertTrue(module.undo(str(self.journal))['ok'])
            self.assertEqual(before, (middle.read_bytes(), target.read_bytes()))
            self.assertFalse(source.exists())

    def test_external_replacement_is_not_a_trusted_organised_identity(self):
        module = core()
        for identifier in ('https://archiveofourown.org/works/99999', ''):
            with self.subTest(identifier=identifier):
                root = self.root / ('identified' if identifier else 'anonymous')
                original_id = 'https://archiveofourown.org/works/12345' if identifier else ''
                source = epub(root / 'old.epub', identifier=original_id)
                journal = self.journal / root.name
                applied = module.apply(module.preview(str(root)), str(journal))
                target = Path(applied['moves'][0]['target'])
                replacement = epub(root / 'incoming.epub', title='Unrelated replacement', identifier=identifier)
                os.replace(replacement, target)
                before = target.read_bytes()
                recovered = module.recovery(str(journal))
                self.assertFalse(recovered['ok'], recovered)
                self.assertIn('identity', recovered['error'].lower())
                self.assertEqual([], recovered['moves'])
                undone = module.undo(str(journal))
                self.assertFalse(undone['ok'], undone)
                self.assertEqual([], undone['moves'])
                self.assertEqual(before, target.read_bytes())
                self.assertFalse(source.exists())

    def test_undo_preview_returns_all_live_reverse_moves_without_moving(self):
        module = core()
        for name in ('a', 'b', 'c'):
            epub(self.root / (name + '.epub'), title=name)
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        deleted_target = Path(applied['moves'][1]['target'])
        prepared = json.loads(module.json_call('delete_prepare', json.dumps({
            'path': str(deleted_target)}), str(self.journal)))
        deleted_target.unlink()
        self.assertTrue(json.loads(module.json_call('delete_finish', json.dumps({
            'token': prepared['token'], 'deleted': True}), str(self.journal)))['ok'])
        # A legitimate atomic update is still eligible despite a new inode/title.
        target = Path(applied['moves'][2]['target'])
        incoming = epub(self.root / 'incoming.epub', title='Updated c')
        os.replace(incoming, target)
        before = {str(p): (p.read_bytes(), p.stat().st_mtime_ns)
                  for p in Path(self.tmp.name).rglob('*') if p.is_file()}
        preview = json.loads(module.json_call('undo_preview', '', str(self.journal)))
        self.assertTrue(preview['ok'], preview)
        expected = [{'source': m['target'], 'target': m['source']}
                    for m in reversed(applied['moves']) if m['target'] != str(deleted_target)]
        self.assertEqual(expected, preview['moves'])
        after = {str(p): (p.read_bytes(), p.stat().st_mtime_ns)
                 for p in Path(self.tmp.name).rglob('*') if p.is_file()}
        self.assertEqual(before, after)
        self.assertEqual(expected, module.undo(str(self.journal))['moves'])

    def test_undo_identity_ambiguity_preflights_entire_reverse_plan(self):
        module = core()
        first = epub(self.root / 'a.epub', title='First')
        second = epub(self.root / 'b.epub', title='Second')
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        target = Path(applied['moves'][0]['target'])
        epub(target, title='Different story', identifier='https://example.test/story/other')
        before = target.read_bytes()
        preview = json.loads(module.json_call('undo_preview', '', str(self.journal)))
        self.assertFalse(preview['ok'], preview)
        self.assertEqual([], preview['moves'])
        undone = module.undo(str(self.journal))
        self.assertFalse(undone['ok'], undone)
        self.assertEqual([], undone['moves'])
        self.assertFalse(first.exists())
        self.assertFalse(second.exists())
        self.assertEqual(before, target.read_bytes())
        self.assertTrue(Path(applied['moves'][1]['target']).exists())

    def test_crashed_delete_reports_exact_room_identity_even_without_organisation(self):
        module = core()
        for organised in (False, True):
            with self.subTest(organised=organised):
                root = self.root / str(organised)
                journal = self.journal / str(organised)
                source = epub(root / 'old.epub')
                target = source
                if organised:
                    applied = module.apply(module.preview(str(root)), str(journal))
                    target = Path(applied['moves'][0]['target'])
                else:
                    self.assertFalse(journal.exists())
                prepared = json.loads(module.json_call('delete_prepare', json.dumps({
                    'path': str(target), 'book_id': 42}), str(journal)))
                self.assertTrue(prepared['ok'], prepared)
                self.assertTrue(prepared['token'])
                target.unlink()
                # Crash before finish and before Room removal; row 43 is a replacement.
                epub(target, title='Replacement', identifier='https://example.test/story/43')
                before = target.read_bytes()
                for _ in range(2):
                    recovered = module.recovery(str(journal))
                    self.assertTrue(recovered['ok'], recovered)
                    self.assertEqual([{'book_id': 42, 'path': str(target)}], recovered.get('deleted_books'))
                    self.assertIn(str(source), recovered['retired_paths'])
                    self.assertIn(str(target), recovered['retired_paths'])
                    self.assertEqual([], recovered['moves'])
                    self.assertFalse(recovered['can_undo'])
                self.assertEqual(before, target.read_bytes())

    def test_deleting_reused_historical_path_does_not_delete_surviving_lineage(self):
        module = core()
        source = epub(self.root / 'old.epub')
        first = module.apply(module.preview(str(self.root)), str(self.journal))
        middle = Path(first['moves'][0]['target'])
        epub(middle, title='Renamed original')
        second = module.apply(module.preview(str(self.root)), str(self.journal))
        target = second['moves'][0]['target']
        epub(middle, title='Unrelated historical-path occupant',
             identifier='https://example.test/story/new')
        prepared = json.loads(module.json_call('delete_prepare', json.dumps({
            'path': str(middle), 'book_id': 99}), str(self.journal)))
        self.assertTrue(prepared['ok'], prepared)
        middle.unlink()
        self.assertTrue(json.loads(module.json_call('delete_finish', json.dumps({
            'token': prepared['token'], 'deleted': True}), str(self.journal)))['ok'])
        recovered = module.recovery(str(self.journal))
        self.assertTrue(recovered['ok'], recovered)
        self.assertEqual([{'source': str(source), 'target': target}], recovered['moves'])
        self.assertTrue(recovered['can_undo'])
        self.assertTrue(module.undo(str(self.journal))['ok'])
        self.assertTrue(middle.exists())

    def test_finished_deletion_token_cannot_ever_revive_a_tombstone(self):
        module = core()
        source = epub(self.root / 'old.epub')
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        target = Path(applied['moves'][0]['target'])
        retained_link = self.root / 'retained-backup'
        os.link(target, retained_link)
        prepared = json.loads(module.json_call('delete_prepare', json.dumps({
            'path': str(target), 'book_id': 12}), str(self.journal)))
        self.assertTrue(prepared['ok'], prepared)
        target.unlink()
        finish_payload = {'token': prepared['token'], 'deleted': True}
        self.assertTrue(json.loads(module.json_call('delete_finish', json.dumps(finish_payload), str(self.journal)))['ok'])
        os.replace(retained_link, target)
        # Even reusing the exact old inode/hash/mtime cannot undo a finished deletion.
        finish_payload['deleted'] = False
        self.assertTrue(json.loads(module.json_call('delete_finish', json.dumps(finish_payload), str(self.journal)))['ok'])
        recovered = module.recovery(str(self.journal))
        self.assertFalse(recovered['can_undo'])
        self.assertEqual([], recovered['moves'])
        self.assertEqual([{'book_id': 12, 'path': str(target)}], recovered['deleted_books'])
        self.assertTrue(module.undo(str(self.journal))['ok'])
        self.assertFalse(source.exists())
        self.assertTrue(target.exists())

    def test_apply_cannot_bless_external_replacement_into_old_alias_history(self):
        module = core()
        source = epub(self.root / 'old.epub')
        first = module.apply(module.preview(str(self.root)), str(self.journal))
        target = Path(first['moves'][0]['target'])
        epub(target, title='Unrelated replacement', identifier='https://example.test/story/new')
        before = target.read_bytes()
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        self.assertFalse(applied['ok'], applied)
        self.assertIn('identity', applied['error'].lower())
        self.assertEqual([], applied['moves'])
        self.assertEqual(before, target.read_bytes())
        self.assertFalse(source.exists())
        recovered = module.recovery(str(self.journal))
        self.assertFalse(recovered['ok'], recovered)
        self.assertEqual([], recovered['moves'])

    def test_delete_finish_requires_json_boolean_without_changing_pending_intent(self):
        module = core()
        source = epub(self.root / 'old.epub')
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        prepared = json.loads(module.json_call('delete_prepare', json.dumps({
            'path': applied['moves'][0]['target']}), str(self.journal)))
        self.assertTrue(prepared['ok'], prepared)
        before = (self.journal / 'organizer.json').read_bytes()
        for invalid in ('false', 0, 1, None, [], {}):
            with self.subTest(invalid=invalid):
                result = json.loads(module.json_call('delete_finish', json.dumps({
                    'token': prepared['token'], 'deleted': invalid}), str(self.journal)))
                self.assertFalse(result['ok'], result)
                self.assertIn('boolean', result['error'].lower())
                self.assertEqual(before, (self.journal / 'organizer.json').read_bytes())
        self.assertTrue(json.loads(module.json_call('delete_finish', json.dumps({
            'token': prepared['token'], 'deleted': False}), str(self.journal)))['ok'])
        self.assertTrue(module.undo(str(self.journal))['ok'])
        self.assertTrue(source.exists())

    def test_preview_uses_opf_metadata_without_writing(self):
        source = epub(self.root / 'old.epub')
        before = sorted(str(p.relative_to(self.tmp.name)) for p in Path(self.tmp.name).rglob('*'))
        plan = core().preview(str(self.root))
        self.assertTrue(plan['ok'])
        self.assertEqual((1, 0, 0), (plan['move_count'], plan['unchanged_count'], plan['skipped_count']))
        entry = plan['entries'][0]
        self.assertEqual(str(source), entry['source'])
        self.assertEqual(str(self.root / 'An Author' / 'A Story.epub'), entry['target'])
        self.assertEqual(('A Story', 'An Author', 'move'), (entry['title'], entry['author'], entry['status']))
        self.assertTrue(entry['fingerprint'])
        self.assertEqual(before, sorted(str(p.relative_to(self.tmp.name)) for p in Path(self.tmp.name).rglob('*')))

    def test_epub3_author_roles_choose_first_author_not_editor(self):
        epub(self.root / 'roles.epub', creators='<dc:creator id="editor">Editor</dc:creator><dc:creator id="author">Writer</dc:creator><dc:creator opf:role="aut">Other Writer</dc:creator>', extra='<meta refines="#editor" property="role">edt</meta><meta refines="#author" property="role">aut</meta>')
        self.assertEqual('Writer', core().preview(str(self.root))['entries'][0]['author'])

    def test_preview_skips_bad_epubs_without_losing_good_missing_author(self):
        epub(self.root / 'unknown.EPUB', creators='')
        epub(self.root / 'no-title.epub', title=None)
        (self.root / 'broken.epub').write_bytes(b'not a ZIP')
        result = core().preview(str(self.root))
        self.assertTrue(result['ok'])
        self.assertEqual((1, 2), (result['move_count'], result['skipped_count']))
        good = next(e for e in result['entries'] if e['status'] == 'move')
        self.assertEqual('Unknown Author', good['author'])
        self.assertTrue(all(e.get('error') for e in result['entries'] if e['status'] == 'skipped'))

    def test_metadata_names_are_safe_bounded_unicode_components(self):
        epub(self.root / 'unsafe.epub', title='../A/B\\\\C:*?&lt;&gt;|.' + '文' * 160, creators='<dc:creator>CON</dc:creator>')
        entry = core().preview(str(self.root))['entries'][0]
        relative = Path(entry['target']).relative_to(self.root)
        self.assertEqual(2, len(relative.parts))
        self.assertNotEqual('CON', relative.parts[0])
        self.assertLessEqual(len(relative.name.encode('utf-8')), 240)
        self.assertFalse(any(c in relative.name for c in '/\\\\:*?<>|'))
        self.assertNotIn('..', relative.parts)

    def test_collisions_use_story_id_only_when_needed_and_are_stable(self):
        first = epub(self.root / 'a.epub', title='Café')
        second = epub(self.root / 'b.epub', title='CAFE\u0301')
        (self.root / 'AN AUTHOR').mkdir()
        plan = core().preview(str(self.root))
        paths = [Path(e['target']) for e in plan['entries']]
        self.assertEqual('AN AUTHOR', paths[0].parent.name)
        self.assertEqual('Café.epub', paths[0].name)
        self.assertIn('12345', paths[1].stem)
        self.assertNotEqual(paths[0].name.casefold(), paths[1].name.casefold())
        for e in plan['entries']:
            os.rename(e['source'], e['target'])
        second_plan = core().preview(str(self.root))
        self.assertEqual(2, second_plan['unchanged_count'])
        self.assertEqual(0, second_plan['move_count'])

    def test_preview_refuses_symlinks_in_root_sources_and_author_dirs(self):
        outside = Path(self.tmp.name) / 'outside'
        outside.mkdir()
        real = epub(outside / 'real.epub')
        (self.root / 'linked.epub').symlink_to(real)
        (self.root / 'An Author').symlink_to(outside, target_is_directory=True)
        epub(self.root / 'ordinary.epub')
        result = core().preview(str(self.root))
        self.assertEqual(2, result['skipped_count'])
        self.assertEqual(0, result['move_count'])
        self.assertFalse(core().preview(str(self.root / 'An Author'))['ok'])
        self.assertFalse(core().preview(str(self.root / 'absent'))['ok'])
        self.assertEqual([real], list(outside.iterdir()))

    def test_apply_preserves_bytes_mtime_and_writes_a_journal(self):
        source = epub(self.root / 'old.epub')
        os.utime(source, ns=(1234567890000000000, 1234567890123456789))
        before = source.read_bytes(), source.stat().st_mtime_ns
        plan = core().preview(str(self.root))
        result = core().apply(plan, str(self.journal))
        self.assertTrue(result['ok'], result)
        target = Path(plan['entries'][0]['target'])
        self.assertEqual([{'source': str(source), 'target': str(target)}], result['moves'])
        self.assertFalse(source.exists())
        self.assertEqual(before, (target.read_bytes(), target.stat().st_mtime_ns))
        self.assertTrue(any(self.journal.glob('*.json')))

    def test_apply_rejects_stale_or_tampered_plan_before_any_move(self):
        first = epub(self.root / 'a.epub')
        second = epub(self.root / 'b.epub', title='Second')
        plan = core().preview(str(self.root))
        with second.open('ab') as stream:
            stream.write(b'changed')
        result = core().apply(plan, str(self.journal))
        self.assertFalse(result['ok'])
        self.assertEqual([], result['moves'])
        self.assertTrue(first.exists())
        self.assertTrue(second.exists())
        self.assertFalse(self.journal.exists())
        fresh = core().preview(str(self.root))
        fresh['entries'][0]['target'] = str(Path(self.tmp.name) / 'escaped.epub')
        self.assertFalse(core().apply(fresh, str(self.journal))['ok'])
        self.assertFalse((Path(self.tmp.name) / 'escaped.epub').exists())

    def test_undo_restores_updated_book_and_recovery_never_replays_forward(self):
        source = epub(self.root / 'old.epub')
        plan = core().preview(str(self.root))
        target = Path(plan['entries'][0]['target'])
        self.assertTrue(core().apply(plan, str(self.journal))['ok'])
        forward = core().recovery(str(self.journal))
        self.assertEqual([{'source': str(source), 'target': str(target)}], forward['moves'])
        self.assertEqual(str(self.root), forward['root'])
        self.assertTrue(forward['can_undo'])
        epub(target, title='Updated Story')
        updated = target.read_bytes()
        result = core().undo(str(self.journal))
        self.assertTrue(result['ok'], result)
        reverse = [{'source': str(target), 'target': str(source)}]
        self.assertEqual(reverse, result['moves'])
        self.assertEqual(updated, source.read_bytes())
        for _ in range(2):
            recovered = core().recovery(str(self.journal))
            self.assertTrue(recovered['ok'])
            self.assertEqual(reverse, recovered['moves'])
            self.assertFalse(recovered['can_undo'])
        self.assertTrue((self.journal / 'organizer.json').exists())

    def test_recovery_does_not_remap_reused_historic_source(self):
        module = core()
        source = epub(self.root / 'old.epub')
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        self.assertTrue(applied['ok'], applied)
        target = Path(applied['moves'][0]['target'])
        epub(target, title='Updated organised story')
        organised_bytes = target.read_bytes()
        # Content updates do not invalidate aliases while the old path is absent.
        self.assertEqual(applied['moves'], module.recovery(str(self.journal))['moves'])
        epub(source, title='A different downloaded story',
             identifier='https://archiveofourown.org/works/98765')
        downloaded_bytes = source.read_bytes()
        for _ in range(2):
            recovered = module.recovery(str(self.journal))
            self.assertTrue(recovered['ok'], recovered)
            self.assertEqual([], recovered['moves'])
            self.assertEqual(downloaded_bytes, source.read_bytes())
            self.assertEqual(organised_bytes, target.read_bytes())
        # Once the independent occupant is gone, the historic alias is useful again.
        source.unlink()
        self.assertEqual(applied['moves'], module.recovery(str(self.journal))['moves'])

    def test_recovery_finishes_crash_between_rename_and_journal_record(self):
        source = epub(self.root / 'old.epub')
        plan = core().preview(str(self.root))
        target = Path(plan['entries'][0]['target'])
        module = core()
        save = module._save

        def crash_after_move(directory, data):
            if any(r['state'] == 'done' for op in data['operations'] for r in op['records']):
                raise KeyboardInterrupt('simulated process death')
            save(directory, data)

        with mock.patch.object(module, '_save', side_effect=crash_after_move):
            with self.assertRaises(KeyboardInterrupt):
                module.apply(plan, str(self.journal))
        self.assertFalse(source.exists())
        recovered = module.recovery(str(self.journal))
        self.assertTrue(recovered['ok'], recovered)
        self.assertEqual([{'source': str(source), 'target': str(target)}], recovered['moves'])
        self.assertTrue(recovered['can_undo'])
        self.assertTrue(module.undo(str(self.journal))['ok'])

    def test_failed_apply_reports_move_even_when_failure_follows_rename(self):
        source = epub(self.root / 'old.epub')
        plan = core().preview(str(self.root))
        module = core()
        move = module._move

        def move_then_fail(*args, **kwargs):
            move(*args, **kwargs)
            raise OSError('simulated fsync failure after rename')

        with mock.patch.object(module, '_move', side_effect=move_then_fail):
            result = module.apply(plan, str(self.journal))
        self.assertFalse(result['ok'])
        self.assertEqual([{'source': str(source), 'target': plan['entries'][0]['target']}], result['moves'])
        self.assertTrue(result['can_undo'])
        self.assertEqual(result['moves'], module.recovery(str(self.journal))['moves'])

    def test_destination_casefold_race_never_clobbers_apply_or_undo(self):
        source = epub(self.root / 'old.epub')
        module = core()
        plan = module.preview(str(self.root))
        target = Path(plan['entries'][0]['target'])
        move = module._move

        def competing_file(*args, **kwargs):
            target.parent.mkdir(exist_ok=True)
            (target.parent / target.name.upper()).write_bytes(b'unrelated')
            return move(*args, **kwargs)

        with mock.patch.object(module, '_move', side_effect=competing_file):
            result = module.apply(plan, str(self.journal))
        self.assertFalse(result['ok'])
        self.assertEqual([], result['moves'])
        self.assertTrue(source.exists())
        (target.parent / target.name.upper()).unlink()
        self.assertTrue(module.apply(module.preview(str(self.root)), str(self.journal))['ok'])
        source.write_bytes(b'new original occupant')
        undone = module.undo(str(self.journal))
        self.assertFalse(undone['ok'])
        self.assertEqual([], undone['moves'])
        self.assertTrue(undone['can_undo'])
        self.assertEqual(b'new original occupant', source.read_bytes())
        self.assertTrue(target.exists())
        source.unlink()
        self.assertTrue(module.undo(str(self.journal))['ok'])

    def test_android_fallback_streams_exclusively_when_rename_and_links_unavailable(self):
        source = epub(self.root / 'old.epub')
        before = source.read_bytes(), source.stat().st_mtime_ns
        module = core()
        plan = module.preview(str(self.root))
        import errno
        unavailable = OSError(errno.ENOSYS, 'not supported')
        with mock.patch.object(module, '_rename_noreplace', side_effect=unavailable), mock.patch.object(module.os, 'link', side_effect=OSError(errno.EPERM, 'emulated storage')):
            result = module.apply(plan, str(self.journal))
            self.assertTrue(result['ok'], result)
            target = Path(result['moves'][0]['target'])
            self.assertEqual(before, (target.read_bytes(), target.stat().st_mtime_ns))
            self.assertFalse(source.exists())
            self.assertTrue(module.undo(str(self.journal))['ok'])
        self.assertEqual(before, (source.read_bytes(), source.stat().st_mtime_ns))

    def test_recovery_completes_durable_copy_before_source_unlink(self):
        import errno
        source = epub(self.root / 'old.epub')
        module = core()
        plan = module.preview(str(self.root))
        save = module._save

        def crash_when_ready(directory, data):
            save(directory, data)
            if any(r.get('transfer', {}).get('phase') == 'ready' for op in data['operations'] for r in op['records']):
                raise KeyboardInterrupt('power loss before unlink')

        with mock.patch.object(module, '_rename_noreplace', side_effect=OSError(errno.ENOSYS, 'unsupported')), mock.patch.object(module.os, 'link', side_effect=OSError(errno.EPERM, 'unsupported')), mock.patch.object(module, '_save', side_effect=crash_when_ready):
            with self.assertRaises(KeyboardInterrupt):
                module.apply(plan, str(self.journal))
        self.assertTrue(source.exists())
        self.assertTrue(Path(plan['entries'][0]['target']).exists())
        result = module.recovery(str(self.journal))
        self.assertTrue(result['ok'], result)
        self.assertEqual([{'source': str(source), 'target': plan['entries'][0]['target']}], result['moves'])
        self.assertFalse(source.exists())

    def test_download_creates_missing_configured_output_root(self):
        source = epub(Path(self.tmp.name) / 'download.epub')
        before = source.read_bytes(), source.stat().st_mtime_ns
        root = Path(self.tmp.name) / 'future' / 'nested' / 'library'
        self.assertFalse(root.exists())
        result = json.loads(core().json_call('copy_download', json.dumps({
            'source': str(source), 'root': str(root)})))
        self.assertTrue(result['ok'], result)
        target = root / 'An Author' / 'A Story.epub'
        self.assertEqual(str(target), result['path'])
        self.assertEqual(before, (target.read_bytes(), target.stat().st_mtime_ns))
        self.assertEqual(before, (source.read_bytes(), source.stat().st_mtime_ns))

    def test_download_rejects_symlink_output_ancestors_before_creating_children(self):
        source = epub(Path(self.tmp.name) / 'download.epub')
        outside = Path(self.tmp.name) / 'outside'
        outside.mkdir()
        linked = self.root / 'linked'
        linked.symlink_to(outside, target_is_directory=True)
        for root in (linked, linked / 'missing' / 'library'):
            with self.subTest(root=root):
                result = core().copy_download(str(source), str(root))
                self.assertFalse(result['ok'], result)
                self.assertEqual('', result['path'])
                self.assertEqual([], list(outside.iterdir()))
                self.assertTrue(source.exists())

    def test_json_download_copy_uses_same_metadata_and_never_overwrites(self):
        source = epub(Path(self.tmp.name) / 'download.epub')
        module = core()
        payload = json.dumps({'source': str(source), 'root': str(self.root)})
        first = json.loads(module.json_call('copy_download', payload))
        second = json.loads(module.json_call('copy_download', payload))
        self.assertTrue(first['ok'], first)
        self.assertTrue(second['ok'], second)
        self.assertEqual(str(self.root / 'An Author' / 'A Story.epub'), first['path'])
        self.assertIn('12345', Path(second['path']).name)
        self.assertNotEqual(first['path'], second['path'])
        self.assertEqual(source.read_bytes(), Path(first['path']).read_bytes())
        self.assertTrue(source.exists())
        preview = json.loads(module.json_call('preview', str(self.root)))
        self.assertEqual(2, preview['unchanged_count'])
        self.assertFalse(json.loads(module.json_call('unknown', ''))['ok'])
        self.assertFalse(json.loads(module.json_call('apply', '{bad', str(self.journal)))['ok'])
        self.assertFalse(json.loads(module.json_call('copy_download', json.dumps({'source': str(self.root / 'missing'), 'root': str(self.root)})))['ok'])

    def test_tampered_journal_cannot_move_outside_root(self):
        source = epub(self.root / 'old.epub')
        module = core()
        self.assertTrue(module.apply(module.preview(str(self.root)), str(self.journal))['ok'])
        journal_file = self.journal / 'organizer.json'
        data = json.loads(journal_file.read_text())
        outside = Path(self.tmp.name) / 'outside.epub'
        data['operations'][0]['records'][0]['source'] = str(outside)
        journal_file.write_text(json.dumps(data))
        self.assertFalse(module.undo(str(self.journal))['ok'])
        self.assertFalse(outside.exists())
        self.assertFalse(module.recovery(str(self.journal))['ok'])

    def test_history_flattens_aliases_and_noop_apply_keeps_latest_undo(self):
        module = core()
        source = epub(self.root / 'old.epub')
        first = module.apply(module.preview(str(self.root)), str(self.journal))
        middle = first['moves'][0]['target']
        epub(middle, title='Renamed')
        last = module.apply(module.preview(str(self.root)), str(self.journal))['moves'][0]['target']
        noop = module.apply(module.preview(str(self.root)), str(self.journal))
        self.assertTrue(noop['can_undo'])
        forward = {m['source']: m['target'] for m in module.recovery(str(self.journal))['moves']}
        self.assertEqual({str(source): last, middle: last}, forward)
        self.assertTrue(module.undo(str(self.journal))['ok'])
        reverse = {m['source']: m['target'] for m in module.recovery(str(self.journal))['moves']}
        self.assertEqual({str(source): middle, last: middle}, reverse)
        self.assertFalse(module.recovery(str(self.journal))['can_undo'])
        self.assertEqual([], module.undo(str(self.journal))['moves'])

    def test_each_move_rechecks_source_fingerprint_after_preflight(self):
        source = epub(self.root / 'old.epub')
        module = core()
        plan = module.preview(str(self.root))
        move = module._move

        def updated_during_apply(*args, **kwargs):
            epub(source, title='Concurrent update')
            return move(*args, **kwargs)

        with mock.patch.object(module, '_move', side_effect=updated_during_apply):
            result = module.apply(plan, str(self.journal))
        self.assertFalse(result['ok'])
        self.assertEqual([], result['moves'])
        self.assertTrue(source.exists())
        self.assertFalse(Path(plan['entries'][0]['target']).exists())

    def test_oversized_metadata_is_skipped_instead_of_unbounded_xml_read(self):
        epub(self.root / 'huge.epub', title='x' * (2 * 1024 * 1024))
        result = core().preview(str(self.root))
        self.assertEqual(1, result['skipped_count'])
        self.assertIn('metadata', result['entries'][0]['error'].lower())

    def test_interrupted_partial_copy_retains_files_and_prior_reconciliation(self):
        import errno
        first = epub(self.root / 'a.epub', title='First')
        second = epub(self.root / 'b.epub', title='Second')
        module = core()
        plan = module.preview(str(self.root))
        save = module._save

        def crash_on_second_copy(directory, data):
            save(directory, data)
            records = data['operations'][-1]['records']
            if len(records) == 2 and records[-1].get('transfer', {}).get('phase') == 'copying':
                raise KeyboardInterrupt('mid-copy power loss')

        with mock.patch.object(module, '_rename_noreplace', side_effect=OSError(errno.ENOSYS, 'unsupported')), mock.patch.object(module.os, 'link', side_effect=OSError(errno.EPERM, 'unsupported')), mock.patch.object(module, '_save', side_effect=crash_on_second_copy):
            with self.assertRaises(KeyboardInterrupt):
                module.apply(plan, str(self.journal))
        result = module.recovery(str(self.journal))
        self.assertFalse(result['ok'])
        self.assertIn('copy', result['error'].lower())
        self.assertEqual([{'source': str(first), 'target': plan['entries'][0]['target']}], result['moves'])
        self.assertTrue(second.exists())
        self.assertTrue(Path(plan['entries'][1]['target']).exists())
        self.assertTrue(result['can_undo'])

    def test_recovery_retains_safe_alias_when_another_endpoint_is_unsafe(self):
        first = epub(self.root / 'a.epub', title='First',
                     creators='<dc:creator>Safe Author</dc:creator>')
        epub(self.root / 'b.epub', title='Second',
             creators='<dc:creator>Unsafe Author</dc:creator>')
        module = core()
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        self.assertTrue(applied['ok'], applied)
        unsafe = Path(applied['moves'][1]['target'])
        outside = Path(self.tmp.name) / 'relocated'
        unsafe.parent.rename(outside)
        unsafe.parent.symlink_to(outside, target_is_directory=True)
        for _ in range(2):
            recovered = module.recovery(str(self.journal))
            self.assertFalse(recovered['ok'], recovered)
            self.assertEqual([applied['moves'][0]], recovered['moves'])
            self.assertEqual(str(first), recovered['moves'][0]['source'])
            self.assertTrue(recovered['can_undo'])
            self.assertEqual(str(self.root), recovered['root'])
        self.assertTrue((outside / unsafe.name).exists())

    def test_recovery_refuses_replaced_symlink_author_directory(self):
        source = epub(self.root / 'old.epub')
        module = core()
        result = module.apply(module.preview(str(self.root)), str(self.journal))
        target = Path(result['moves'][0]['target'])
        outside = Path(self.tmp.name) / 'relocated'
        target.parent.rename(outside)
        target.parent.symlink_to(outside, target_is_directory=True)
        recovered = module.recovery(str(self.journal))
        self.assertFalse(recovered['ok'])
        self.assertEqual([], recovered['moves'])
        self.assertFalse(module.undo(str(self.journal))['ok'])
        self.assertTrue((outside / target.name).exists())
        self.assertFalse(source.exists())

    def test_new_author_directory_is_durable_before_publishing_move(self):
        epub(self.root / 'old.epub')
        module = core()
        plan = module.preview(str(self.root))
        synced = set()
        fsync, rename = module.os.fsync, module._rename_noreplace
        root_identity = self.root.stat().st_dev, self.root.stat().st_ino

        def record_fsync(fd):
            details = os.fstat(fd)
            synced.add((details.st_dev, details.st_ino))
            return fsync(fd)

        def check_publish(*args):
            self.assertIn(root_identity, synced, 'parent directory must be synced before rename')
            return rename(*args)

        with mock.patch.object(module.os, 'fsync', side_effect=record_fsync), mock.patch.object(module, '_rename_noreplace', side_effect=check_publish):
            self.assertTrue(module.apply(plan, str(self.journal))['ok'])

    def test_long_collision_names_do_not_drift_on_later_previews(self):
        title = '文' * 90
        identifier = 'https://example.test/story/' + 'a' * 32
        epub(self.root / 'a.epub', title=title, identifier=identifier)
        epub(self.root / 'b.epub', title=title, identifier=identifier)
        module = core()
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        self.assertTrue(applied['ok'], applied)
        self.assertTrue(all(len(Path(m['target']).name.encode('utf-8')) <= 240 for m in applied['moves']))
        self.assertEqual(2, module.preview(str(self.root))['unchanged_count'])

    def test_undo_crash_after_rename_recovers_reverse_alias_once(self):
        source = epub(self.root / 'old.epub')
        module = core()
        result = module.apply(module.preview(str(self.root)), str(self.journal))
        target = result['moves'][0]['target']
        save = module._save

        def crash_after_reverse(directory, data):
            if any(r['state'] == 'undone' for op in data['operations'] for r in op['records']):
                raise KeyboardInterrupt('process died after undo rename')
            save(directory, data)

        with mock.patch.object(module, '_save', side_effect=crash_after_reverse):
            with self.assertRaises(KeyboardInterrupt):
                module.undo(str(self.journal))
        for _ in range(2):
            recovered = json.loads(module.json_call('recovery', '', str(self.journal)))
            self.assertTrue(recovered['ok'], recovered)
            self.assertEqual([{'source': target, 'target': str(source)}], recovered['moves'])
            self.assertFalse(recovered['can_undo'])
        self.assertTrue(source.exists())
        self.assertFalse(Path(target).exists())

    def test_failed_undo_retry_reports_only_moves_from_that_call(self):
        for name in ('a', 'b', 'c'):
            epub(self.root / (name + '.epub'), title=name)
        module = core()
        applied = module.apply(module.preview(str(self.root)), str(self.journal))
        occupied = self.root / 'b.epub'
        occupied.write_bytes(b'unrelated new book')
        first = module.undo(str(self.journal))
        self.assertFalse(first['ok'])
        self.assertEqual([{'source': applied['moves'][2]['target'], 'target': applied['moves'][2]['source']}], first['moves'])
        retry = module.undo(str(self.journal))
        self.assertFalse(retry['ok'])
        self.assertEqual([], retry['moves'])
        self.assertEqual(b'unrelated new book', occupied.read_bytes())
        occupied.unlink()
        last = module.undo(str(self.journal))
        self.assertTrue(last['ok'], last)
        self.assertEqual(2, len(last['moves']))
        recovered = module.recovery(str(self.journal))
        self.assertEqual(3, len(recovered['moves']))
        self.assertTrue(all(Path(m['target']).exists() for m in recovered['moves']))

    def test_hardlink_interruption_recovers_without_copying_epub(self):
        import errno
        source = epub(self.root / 'old.epub')
        module = core()
        plan = module.preview(str(self.root))
        unlink = module.os.unlink

        def crash_on_source_unlink(path, *args, **kwargs):
            if path == source.name:
                raise KeyboardInterrupt('power loss after exclusive hardlink')
            return unlink(path, *args, **kwargs)

        with mock.patch.object(module, '_rename_noreplace', side_effect=OSError(errno.ENOSYS, 'unsupported')), mock.patch.object(module.os, 'unlink', side_effect=crash_on_source_unlink), mock.patch.object(module, '_copy_exclusive', side_effect=AssertionError('hardlink needs no full copy')):
            with self.assertRaises(KeyboardInterrupt):
                module.apply(plan, str(self.journal))
        target = Path(plan['entries'][0]['target'])
        self.assertEqual(source.stat().st_ino, target.stat().st_ino)
        recovered = module.recovery(str(self.journal))
        self.assertTrue(recovered['ok'], recovered)
        self.assertEqual([{'source': str(source), 'target': str(target)}], recovered['moves'])
        self.assertFalse(source.exists())

    def test_download_exclusive_create_race_retries_without_overwriting(self):
        source = epub(Path(self.tmp.name) / 'download.epub')
        module = core()
        open_fd = module.os.open
        target = self.root / 'An Author' / 'A Story.epub'
        raced = []

        def competing_create(name, flags, *args, **kwargs):
            if name == target.name and flags & os.O_EXCL and not raced:
                raced.append(True)
                target.write_bytes(b'other download won the race')
            return open_fd(name, flags, *args, **kwargs)

        with mock.patch.object(module.os, 'open', side_effect=competing_create):
            result = module.copy_download(str(source), str(self.root))
        self.assertTrue(result['ok'], result)
        self.assertNotEqual(str(target), result['path'])
        self.assertEqual(b'other download won the race', target.read_bytes())
        self.assertEqual(source.read_bytes(), Path(result['path']).read_bytes())

    def test_planned_author_folders_share_normalized_casefold_identity(self):
        epub(self.root / 'a.epub', title='First', creators='<dc:creator>Écrivain</dc:creator>')
        epub(self.root / 'b.epub', title='Second', creators='<dc:creator>E\u0301CRIVAIN</dc:creator>')
        plan = core().preview(str(self.root))
        self.assertEqual(2, plan['move_count'])
        self.assertEqual(Path(plan['entries'][0]['target']).parent, Path(plan['entries'][1]['target']).parent)
        self.assertTrue(core().apply(plan, str(self.journal))['ok'])
        self.assertEqual(1, len([p for p in self.root.iterdir() if p.is_dir()]))

    def test_chaquopy_json_preview_apply_undo_roundtrip(self):
        source = epub(self.root / 'old.epub')
        module = core()
        plan_json = module.json_call('preview', str(self.root))
        applied = json.loads(module.json_call('apply', plan_json, str(self.journal)))
        self.assertTrue(applied['ok'], applied)
        self.assertEqual(str(self.root), applied['root'])
        self.assertTrue(applied['can_undo'])
        undone = json.loads(module.json_call('undo', '', str(self.journal)))
        self.assertTrue(undone['ok'], undone)
        self.assertEqual([{'source': applied['moves'][0]['target'], 'target': str(source)}], undone['moves'])
        self.assertFalse(undone['can_undo'])


if __name__ == '__main__':
    unittest.main()
