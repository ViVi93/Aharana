"""Offline bridge updates using the real engine, EPUB reader and writer."""
import io
import json
import re
import socket
import zipfile
from datetime import datetime
from pathlib import Path

import pytest

import fanficfare_bridge as bridge
import fanficfare_config
from fanficfare import adapters
from fanficfare.adapters.base_adapter import BaseSiteAdapter
from fanficfare.configurable import Configuration
from fanficfare.epubutils import get_update_data
from fanficfare.writers.writer_epub import EpubWriter


POLICY_URL = 'http://policy.example/story/123'


class BridgePolicyAdapter(BaseSiteAdapter):
    @staticmethod
    def getSiteDomain():
        return 'policy.example'

    def getSiteURLPattern(self):
        return r'^http://policy\.example/story/123$'

    def normalize_chapterurl(self, url):
        return url

    def extractChapterUrlsAndMetadata(self):
        for key, value in dict(storyId='123', title=self.book_title,
                               author='Policy Author', authorId='42',
                               authorUrl='http://policy.example/author/42',
                               langcode='en', status='In-Progress',
                               description=self.description,
                               datePublished=datetime(2020, 1, 1),
                               dateUpdated=datetime(2020, 1, 2)).items():
            self.story.setMetadata(key, value)
        for number in self.content:
            self.add_chapter(self.titles.get(number, 'Chapter %s' % number),
                             POLICY_URL + '/chapter/%s' % number)

    def getChapterTextNum(self, url, index):
        self.fetched.append(url)
        value = self.content[int(url.rsplit('/', 1)[1])]
        if isinstance(value, Exception):
            raise value
        return value


def policy_adapter(content, preserve=False, recent=0, **options):
    config = Configuration(['policy.example'], 'epub', lightweight=True)
    config.read(str(Path(bridge.__file__).parent / 'fanficfare/defaults.ini'))
    if not config.has_section('overrides'):
        config.add_section('overrides')
    values = dict(include_images='false', download_chapter_images='false',
                  update_preserve_deleted_chapters=str(preserve).lower(),
                  update_check_recent_chapters=str(recent),
                  # Avoid elapsed-time metadata when proving identical books.
                  dateCreated_format='%%Y-%%m-%%d')
    values.update(options)
    for key, value in values.items():
        config.set('overrides', key, str(value))
    adapter = BridgePolicyAdapter(config, POLICY_URL)
    adapter.content = content
    adapter.book_title = 'Policy Story'
    adapter.description = '<p>Policy description.</p>'
    adapter.titles = {}
    adapter.fetched = []
    return adapter


def policy_write_old(tmp_path, content=None):
    old = policy_adapter(content if content is not None else {1: '<p>ONE</p>', 2: '<p>TWO</p>'})
    out = io.BytesIO()
    EpubWriter(old.configuration, old).writeStory(outstream=out)
    path = tmp_path / 'old.epub'
    path.write_bytes(out.getvalue())
    return path


def policy_bind(monkeypatch, adapter):
    monkeypatch.setattr(adapters, 'getAdapter', lambda *args, **kwargs: adapter)
    monkeypatch.setattr(fanficfare_config, 'build_configuration', lambda *args, **kwargs: adapter.configuration)
    monkeypatch.setattr(bridge, 'build_configuration', lambda *args, **kwargs: adapter.configuration)


def policy_update(monkeypatch, tmp_path, old_path, adapter):
    policy_bind(monkeypatch, adapter)
    outdir = tmp_path / 'output'
    outdir.mkdir(exist_ok=True)
    return json.loads(bridge.update_epub_from_path(str(old_path), str(outdir)))


def policy_bodies(path):
    with zipfile.ZipFile(path) as archive:
        return b'\n'.join(archive.read(name) for name in archive.namelist() if name.endswith('.xhtml'))


@pytest.fixture(autouse=True)
def policy_no_network(monkeypatch):
    def deny(*args, **kwargs):
        pytest.fail('Bridge update test attempted network access')
    monkeypatch.setattr(socket.socket, 'connect', deny)
    monkeypatch.setattr(socket, 'getaddrinfo', deny)


def test_bridge_equal_count_swap_preserves_deleted_and_adds_new(monkeypatch, tmp_path):
    old = policy_write_old(tmp_path)
    current = policy_adapter({2: '<p>TWO</p>', 3: '<p>THREE</p>'}, preserve=True)
    result = policy_update(monkeypatch, tmp_path, old, current)
    assert result['ok'], result
    assert not result.get('skipped'), result
    assert result['chapters'] == 3
    body = policy_bodies(result['path'])
    assert b'ONE' in body and b'THREE' in body
    assert list(get_update_data(result['path'])[7]) == [POLICY_URL + '/chapter/%s' % n for n in (1, 2, 3)]


def test_download_reports_partial_epub_chapter_errors(monkeypatch, tmp_path):
    current = policy_adapter({1: '<p>ONE</p>', 2: RuntimeError('offline chapter failure')},
                             continue_on_chapter_error='true')
    policy_bind(monkeypatch, current)
    result = json.loads(bridge.download_story(POLICY_URL, str(tmp_path)))
    assert result['ok'], result
    assert result['chapter_error_count'] == 1
    assert len(result['warnings']) == 1
    assert 'chapter' in result['warnings'][0].lower()
    assert '1' in result['warnings'][0]
    assert Path(result['path']).is_file()
    assert b'ONE' in policy_bodies(result['path'])


@pytest.mark.parametrize('operation', ['update', 'force'])
def test_update_and_force_report_partial_epub_errors(monkeypatch, tmp_path, operation):
    old = policy_write_old(tmp_path)
    current = policy_adapter({1: '<p>ONE</p>', 2: '<p>TWO</p>',
                              3: RuntimeError('offline chapter failure')},
                             continue_on_chapter_error='true')
    if operation == 'update':
        result = policy_update(monkeypatch, tmp_path, old, current)
    else:
        policy_bind(monkeypatch, current)
        result = json.loads(bridge.force_download_from_epub(str(old), str(tmp_path)))
    assert result['ok'], result
    assert result['chapter_error_count'] == 1
    assert len(result['warnings']) == 1
    assert result['chapter_written_count'] == 3
    assert len(current.fetched) == (1 if operation == 'update' else 3)


def test_unchanged_update_skips_only_after_real_assembly(monkeypatch, tmp_path):
    old = policy_write_old(tmp_path)
    original = old.read_bytes()
    original_stat = old.stat()
    current = policy_adapter({1: '<p>ONE</p>', 2: '<p>TWO</p>'})
    result = policy_update(monkeypatch, tmp_path, old, current)
    assert result['ok'], result
    assert result.get('skipped'), result
    assert result['reason'] == 'already current'
    assert current.storyDone, 'A count match alone cannot prove the EPUB is unchanged'
    assert current.fetched == []
    assert old.read_bytes() == original
    assert old.stat().st_mtime_ns == original_stat.st_mtime_ns
    assert list((tmp_path / 'output').iterdir()) == []
    assert result['chapter_error_count'] == 0 and result['warnings'] == []


@pytest.mark.parametrize('change', ['metadata', 'chapter_title', 'order'])
def test_equal_counts_do_not_discard_metadata_titles_or_order(monkeypatch, tmp_path, change):
    old = policy_write_old(tmp_path)
    current = policy_adapter({1: '<p>ONE</p>', 2: '<p>TWO</p>'})
    if change == 'metadata':
        current.book_title = 'A changed title'
        current.description = '<p>A changed description</p>'
    elif change == 'chapter_title':
        current.titles[2] = 'A changed chapter title'
    else:
        current.content = {2: '<p>TWO</p>', 1: '<p>ONE</p>'}
    result = policy_update(monkeypatch, tmp_path, old, current)
    assert result['ok'] and not result.get('skipped'), result
    assert result['chapter_added_count'] == result['chapter_updated_count'] == 0
    assert current.fetched == []
    if change == 'metadata':
        assert result['title'] == 'A changed title'
        assert b'A changed description' in policy_bodies(result['path'])
    elif change == 'chapter_title':
        assert b'A changed chapter title' in policy_bodies(result['path'])
    else:
        assert list(get_update_data(result['path'])[7]) == [POLICY_URL + '/chapter/%s' % n for n in (2, 1)]


def test_equal_count_recent_edit_rechecks_old_window(monkeypatch, tmp_path):
    old = policy_write_old(tmp_path)
    current = policy_adapter({1: '<p>ONE</p>', 2: '<p>EDITED TWO</p>'}, recent=1)
    result = policy_update(monkeypatch, tmp_path, old, current)
    assert result['ok'] and not result.get('skipped'), result
    assert b'EDITED TWO' in policy_bodies(result['path'])
    assert current.fetched == [POLICY_URL + '/chapter/2']
    assert result['chapter_updated_count'] == 1


@pytest.mark.parametrize('preserve', [False, True])
def test_shrinking_source_preserves_only_when_enabled(monkeypatch, tmp_path, preserve):
    old = policy_write_old(tmp_path)
    current = policy_adapter({2: '<p>TWO</p>'}, preserve=preserve)
    result = policy_update(monkeypatch, tmp_path, old, current)
    assert result['ok'], result
    if preserve:
        assert not result.get('skipped'), result
        assert result['chapters'] == 2
        assert b'ONE' in policy_bodies(result['path'])
    else:
        assert result['skipped']
        assert result['reason'] == 'local has more chapters than source'
        assert result['warnings'] == [] and result['chapter_error_count'] == 0
        assert not current.storyDone
    assert current.fetched == []


def test_zero_chapter_epub_is_rejected_before_zero_count_match(monkeypatch, tmp_path):
    old = tmp_path / 'empty.epub'
    with zipfile.ZipFile(old, 'w') as archive:
        archive.writestr('mimetype', 'application/epub+zip')
        archive.writestr('META-INF/container.xml', '<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>')
        archive.writestr('OEBPS/content.opf', '<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:source>%s</dc:source></metadata><manifest/><spine/></package>' % POLICY_URL)
    assert get_update_data(str(old))[1] == 0
    result = policy_update(monkeypatch, tmp_path, old, policy_adapter({}))
    assert not result['ok'] and '0 recognizable chapters' in result['error'], result


def test_update_failure_keeps_existing_destination_and_removes_staging(monkeypatch, tmp_path):
    old = policy_write_old(tmp_path)
    current = policy_adapter({1: '<p>ONE</p>', 2: '<p>TWO</p>',
                              3: RuntimeError('required chapter unavailable')})
    filename = EpubWriter(current.configuration, current).getOutputFileName()
    outdir = tmp_path / 'output'
    outdir.mkdir()
    destination = outdir / Path(filename).name
    destination.write_bytes(old.read_bytes())
    before = destination.read_bytes()
    result = policy_update(monkeypatch, tmp_path, old, current)
    assert not result['ok'], result
    assert 'required chapter unavailable' in result['error']
    assert destination.read_bytes() == before
    assert sorted(p.name for p in outdir.iterdir()) == [destination.name]


def test_same_path_update_replaces_atomically(monkeypatch, tmp_path):
    old = policy_write_old(tmp_path)
    current = policy_adapter({1: '<p>ONE</p>', 2: '<p>TWO</p>', 3: '<p>THREE</p>'})
    filename = EpubWriter(current.configuration, current).getOutputFileName()
    destination = tmp_path / Path(filename).name
    old.rename(destination)
    policy_bind(monkeypatch, current)
    result = json.loads(bridge.update_epub_from_path(str(destination), str(tmp_path)))
    assert result['ok'] and not result.get('skipped'), result
    assert result['path'] == str(destination)
    assert b'THREE' in policy_bodies(destination)
    assert not any(p.name.startswith('.fff-') for p in tmp_path.iterdir())


def policy_rewrite_epub(path, transform):
    output = io.BytesIO()
    with zipfile.ZipFile(path) as source, zipfile.ZipFile(output, 'w') as target:
        for item in source.infolist():
            target.writestr(item, transform(item.filename, source.read(item)))
    path.write_bytes(output.getvalue())


def test_legacy_epub_survives_two_preserving_updates(monkeypatch, tmp_path):
    old = policy_write_old(tmp_path)
    policy_rewrite_epub(old, lambda name, data: re.sub(
        rb'<meta name="fff_chapter_data".*?</meta>', b'', data, flags=re.S)
        if name.endswith('.xhtml') else data)
    assert b'fff_chapter_data' not in policy_bodies(old)
    first = policy_adapter({2: '<p>EDITED TWO</p>', 3: '<p>THREE</p>'}, preserve=True, recent=1)
    result = policy_update(monkeypatch, tmp_path, old, first)
    assert result['ok'] and result['chapters'] == 3, result
    second = policy_adapter({3: '<p>EDITED THREE</p>', 4: '<p>FOUR</p>'}, preserve=True, recent=1)
    result = policy_update(monkeypatch, tmp_path, Path(result['path']), second)
    assert result['ok'] and result['chapters'] == 4, result
    assert list(get_update_data(result['path'])[7]) == [POLICY_URL + '/chapter/%s' % n for n in (1, 2, 3, 4)]
    body = policy_bodies(result['path'])
    assert all(text in body for text in (b'ONE', b'EDITED TWO', b'EDITED THREE', b'FOUR'))
    assert result['chapter_added_count'] == 1 and result['chapter_updated_count'] == 1


def test_preserve_range_conflict_is_returned_without_writing(monkeypatch, tmp_path):
    old = policy_write_old(tmp_path)
    policy_rewrite_epub(old, lambda name, data: data.replace(
        ('<dc:source>' + POLICY_URL + '</dc:source>').encode(),
        ('<dc:source>' + POLICY_URL + '[1-2]</dc:source>').encode())
        if name.endswith('.opf') else data)
    assert get_update_data(str(old))[0].endswith('[1-2]')
    current = policy_adapter({1: '<p>ONE</p>', 2: '<p>TWO</p>'}, preserve=True)
    result = policy_update(monkeypatch, tmp_path, old, current)
    assert not result['ok'] and result['exception_type'] == 'ConflictingOptions', result
    assert 'chapter range' in result['error']
    assert list((tmp_path / 'output').iterdir()) == []
