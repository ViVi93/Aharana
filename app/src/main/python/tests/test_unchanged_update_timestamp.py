"""Timestamp regression: offline real bundled adapter core, reader and EPUB writer.

Only the source adapter/network boundary is replaced; writer output is never
faked. Distinct packaging times make the former payload comparison fail reliably.
"""
import io
import json
import os
import socket
import zipfile
from datetime import datetime
from pathlib import Path

import pytest

import fanficfare_bridge as bridge
from fanficfare import dateutils
from fanficfare.writers.writer_epub import EpubWriter
from test_upstream_update_policy import policy_adapter, policy_bind, policy_rewrite_epub


OLD_TIME = datetime(2021, 2, 3, 4, 5, 6)
NEW_TIME = datetime(2026, 8, 9, 10, 11, 12)
CONTENT = {1: '<p>ONE</p>', 2: '<p>TWO</p>'}


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    def deny(*args, **kwargs):
        pytest.fail('Unchanged-update test attempted network access')
    for target in ('connect', 'connect_ex'):
        monkeypatch.setattr(socket.socket, target, deny)
    monkeypatch.setattr(socket, 'getaddrinfo', deny)
    monkeypatch.setattr(socket, 'create_connection', deny)


def adapter_at(when, **settings):
    # Use the real default full timestamp, unlike the older policy fixture's
    # date-only override which accidentally hid this bug on same-day updates.
    adapter = policy_adapter(dict(CONTENT), dateCreated_format='%%Y-%%m-%%d %%H:%%M:%%S', **settings)
    adapter.story.setMetadata('dateCreated', when)
    return adapter


def write_old(tmp_path, monkeypatch, prepare=None, **settings):
    adapter = adapter_at(OLD_TIME, **settings)
    if prepare:
        prepare(adapter)
    monkeypatch.setattr(dateutils, 'utcnow', lambda: OLD_TIME)
    writer = EpubWriter(adapter.configuration, adapter)
    old = tmp_path / Path(writer.getOutputFileName()).name
    output = io.BytesIO()
    writer.writeStory(outstream=output)
    old.write_bytes(output.getvalue())
    os.utime(old, ns=(1_600_000_000_000_000_000, 1_600_000_000_000_000_000))
    monkeypatch.setattr(dateutils, 'utcnow', lambda: NEW_TIME)
    return old


def update(old, adapter, monkeypatch, outdir):
    policy_bind(monkeypatch, adapter)
    outdir.mkdir(exist_ok=True)
    return json.loads(bridge.update_epub_from_path(str(old), str(outdir)))


@pytest.mark.parametrize('same_path', [False, True])
def test_default_real_writer_unchanged_preserves_original_bytes_and_mtime(tmp_path, monkeypatch, same_path):
    old = write_old(tmp_path, monkeypatch)
    before, mtime = old.read_bytes(), old.stat().st_mtime_ns
    current = adapter_at(NEW_TIME)
    outdir = tmp_path if same_path else tmp_path / 'staging'
    result = update(old, current, monkeypatch, outdir)
    assert result['ok'] and result.get('skipped'), result
    assert result['reason'] == 'already current'
    assert current.storyDone and current.fetched == []
    assert old.read_bytes() == before
    assert old.stat().st_mtime_ns == mtime
    assert list(outdir.iterdir()) == ([old] if same_path else [])
    assert current.story.getMetadataRaw('dateCreated') == NEW_TIME


@pytest.mark.parametrize('settings', [
    {'epub_version': '3.0'},
    {'include_titlepage': 'false'},
    {'include_logpage': 'true'},
    {'epub_version': '3.0', 'include_titlepage': 'false'},
    {'epub_version': '3.0', 'include_logpage': 'true'},
    {'include_logpage': 'true', 'include_titlepage': 'false'},
    {'titlepage_use_table': 'true'},
    {'titlepage_use_table': 'true', 'wide_titlepage_entries': 'dateCreated'},
    {'dateCreated_label': ''},
    {'titlepage_entry': '<section>${label}: ${value}</section>\n'},
])
def test_writer_clock_variants_do_not_turn_noop_into_update(tmp_path, monkeypatch, settings):
    old = write_old(tmp_path, monkeypatch, **settings)
    before, mtime = old.read_bytes(), old.stat().st_mtime_ns
    result = update(old, adapter_at(NEW_TIME, **settings), monkeypatch, tmp_path / 'staging')
    assert result['ok'] and result.get('skipped'), result
    assert old.read_bytes() == before and old.stat().st_mtime_ns == mtime
    assert list((tmp_path / 'staging').iterdir()) == []


def test_noop_comparison_does_not_repeat_image_collection(tmp_path, monkeypatch):
    old = write_old(tmp_path, monkeypatch, include_images='true')
    current = adapter_at(NEW_TIME, include_images='true')
    collect = current.story.getImgUrls
    calls = []
    def collect_once():
        calls.append(True)
        assert len(calls) == 1, 'getImgUrls may fetch additional_images; do not replay it'
        return collect()
    monkeypatch.setattr(current.story, 'getImgUrls', collect_once)
    result = update(old, current, monkeypatch, tmp_path / 'staging')
    assert result['ok'] and result.get('skipped'), result
    assert calls == [True]


@pytest.mark.parametrize('change', [
    'title', 'description', 'dateUpdated', 'datePublished', 'chapter_title',
    'chapter_edit', 'order', 'added_chapter', 'css', 'label', 'template',
    'epub_version', 'log_entry',
])
def test_packaging_clock_does_not_hide_real_changes(tmp_path, monkeypatch, change):
    settings = {'include_logpage': 'true'} if change == 'log_entry' else {}
    old = write_old(tmp_path, monkeypatch, **settings)
    current = adapter_at(NEW_TIME, **settings)
    if change == 'title':
        current.book_title = 'Changed title'
    elif change == 'description':
        # A real story timestamp and a lookalike packaging label must survive.
        current.description = '<p>Packaged: 2026-08-09 10:11:12</p>'
    elif change in ('dateUpdated', 'datePublished'):
        current.getStoryMetadataOnly()
        current.story.setMetadata(change, datetime(2022, 4, 5))
    elif change == 'chapter_title':
        current.titles[2] = 'Chapter renamed'
    elif change == 'chapter_edit':
        current.configuration.set('overrides', 'update_check_recent_chapters', '1')
        current.content[2] = '<p>Edited TWO: 2026-08-09 10:11:12</p>'
    elif change == 'order':
        current.content = {2: CONTENT[2], 1: CONTENT[1]}
    elif change == 'added_chapter':
        current.content[3] = '<p>THREE</p>'
    else:
        key, value = {
            'css': ('output_css', 'body { color: blue; }'),
            'label': ('dateCreated_label', 'Assembled'),
            'template': ('titlepage_entry', '<section>${label}: ${value}</section>'),
            'epub_version': ('epub_version', '3.0'),
            'log_entry': ('extra_logpage_entries', 'Manual audit marker'),
        }[change]
        current.configuration.set('overrides', key, value)
    result = update(old, current, monkeypatch, tmp_path / 'staging')
    assert result['ok'] and not result.get('skipped'), result
    with zipfile.ZipFile(result['path']) as output:
        assert b'2026-08-09 10:11:12' in output.read('OEBPS/title_page.xhtml')
    assert current.story.getMetadataRaw('dateCreated') == NEW_TIME
    assert current.fetched == ([current.url + '/chapter/2'] if change == 'chapter_edit'
                               else [current.url + '/chapter/3'] if change == 'added_chapter' else [])


def add_image(adapter, color):
    payload = ('<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1">'
               '<rect width="1" height="1" fill="%s"/></svg>' % color).encode()
    adapter.story.img_store.add_img('http://policy.example/illustration.svg',
                                    'svg', 'image/svg+xml', payload)


@pytest.mark.parametrize('change', ['unchanged', 'added', 'removed', 'edited'])
def test_real_writer_image_payload_changes_are_not_ignored(tmp_path, monkeypatch, change):
    old = write_old(tmp_path, monkeypatch, include_images='true',
                    prepare=None if change == 'added' else lambda a: add_image(a, 'red'))
    current = adapter_at(NEW_TIME, include_images='true')
    if change != 'removed':
        add_image(current, 'blue' if change == 'edited' else 'red')
    result = update(old, current, monkeypatch, tmp_path / 'staging')
    assert result['ok'], result
    assert bool(result.get('skipped')) == (change == 'unchanged'), result
    if change == 'edited':
        with zipfile.ZipFile(result['path']) as output:
            images = [output.read(name) for name in output.namelist() if name.endswith('.svg')]
            assert len(images) == 1 and b'fill="blue"' in images[0]


def test_android_skips_do_not_replace_or_persist_book_timestamps():
    """Source contracts only: host Python cannot execute Android/Room here."""
    java = Path(bridge.__file__).resolve().parents[4] / 'app/src/main/java/com/vivi/aharana'
    detail = (java / 'BookDetailActivity.kt').read_text()
    begin = detail.index('if (!response.optBoolean("skipped"))')
    end = detail.index('response\n', begin)
    guarded, skipped = detail[begin:end].split('} else {', 1)
    assert 'replaceExistingEpub' in guarded and 'bookDao.update' in guarded
    assert all(token not in skipped for token in
               ('replaceExistingEpub', 'bookDao.update', 'lastModified', 'finishWithResult'))
    worker = (java / 'FanFicFareWorker.kt').read_text()
    start = worker.index('if (result.optBoolean("skipped"))')
    end = worker.index('return Result.success()', start)
    skipped = worker[start:end]
    assert 'jobDao.updateFromWorker' in skipped
    assert all(token not in skipped for token in
               ('replaceExistingEpub', 'upsertBook', 'completeBook(', 'lastModified'))
    assert worker.index('StorageBridge.replaceExistingEpub', start) > end


def test_recent_chapter_recheck_with_identical_content_keeps_timestamp(tmp_path, monkeypatch):
    old = write_old(tmp_path, monkeypatch)
    before, mtime = old.read_bytes(), old.stat().st_mtime_ns
    current = adapter_at(NEW_TIME, update_check_recent_chapters='1')
    result = update(old, current, monkeypatch, tmp_path / 'staging')
    assert result['ok'] and result.get('skipped'), result
    assert current.fetched == [current.url + '/chapter/2']
    assert old.read_bytes() == before and old.stat().st_mtime_ns == mtime


@pytest.mark.parametrize('field', ['datePublished', 'dateUpdated'])
def test_source_dates_stored_only_in_opf_are_not_packaging_clocks(tmp_path, monkeypatch, field):
    settings = {'titlepage_entries': 'description,dateCreated'}
    old = write_old(tmp_path, monkeypatch, **settings)
    current = adapter_at(NEW_TIME, **settings)
    current.getStoryMetadataOnly()
    current.story.setMetadata(field, datetime(2022, 4, 5))
    result = update(old, current, monkeypatch, tmp_path / 'staging')
    assert result['ok'] and not result.get('skipped'), result
    with zipfile.ZipFile(result['path']) as output:
        assert b'2022-04-05' in output.read('content.opf')


def test_noop_after_a_real_metadata_update_keeps_new_original(tmp_path, monkeypatch):
    old = write_old(tmp_path, monkeypatch, include_logpage='true')
    first = adapter_at(NEW_TIME, include_logpage='true')
    first.description = '<p>New description with Packaged: 2026-08-09 10:11:12</p>'
    changed = update(old, first, monkeypatch, tmp_path / 'staging')
    assert changed['ok'] and not changed.get('skipped'), changed
    new_original = Path(changed['path'])
    before, mtime = new_original.read_bytes(), new_original.stat().st_mtime_ns
    again = adapter_at(datetime(2026, 9, 10, 11, 12, 13), include_logpage='true')
    again.description = first.description
    result = update(new_original, again, monkeypatch, tmp_path / 'next-staging')
    assert result['ok'] and result.get('skipped'), result
    assert new_original.read_bytes() == before and new_original.stat().st_mtime_ns == mtime


def test_epub3_clock_adjacent_nonclock_metadata_is_not_ignored(tmp_path, monkeypatch):
    old = write_old(tmp_path, monkeypatch, epub_version='3.0')
    clock = b'<meta property="dcterms:modified">2021-02-03T04:05:06Z</meta>'
    policy_rewrite_epub(old, lambda name, data: data.replace(clock, clock + b'Manual metadata text')
                        if name == 'content.opf' else data)
    result = update(old, adapter_at(NEW_TIME, epub_version='3.0'), monkeypatch, tmp_path / 'staging')
    assert result['ok'] and not result.get('skipped'), result
