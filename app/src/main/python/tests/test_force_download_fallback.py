"""Force recovery: real bundled EPUB reader/writer, offline adapter boundary."""
import json
import socket
import io
import re
import zipfile
from pathlib import Path
from xml.parsers.expat import ExpatError

import pytest

import fanficfare_bridge as bridge
import fanficfare_config
from fanficfare import adapters, diagnostics, epubutils, writers
from fanficfare.exceptions import FailedToLogin, HTTPErrorFFF
from fanficfare.writers.writer_epub import EpubWriter
from upstream_core_helpers import CH, STORY_URL, StagedAdapter, chapter_html, config, read, write


FALLBACK = 'https://example.com/story/67890-stored-story'


@pytest.fixture(autouse=True)
def offline(monkeypatch):
    def deny(*args, **kwargs):
        pytest.fail('Force recovery attempted a real network connection')
    for name in ('connect', 'connect_ex'):
        monkeypatch.setattr(socket.socket, name, deny)
    for name in ('getaddrinfo', 'create_connection'):
        monkeypatch.setattr(socket, name, deny)


@pytest.fixture
def engine(tmp_path, monkeypatch):
    diagnostics.configure(str(tmp_path / 'config'))
    monkeypatch.setattr(bridge, '_import_fanficfare', lambda: True)
    builds, created = [], []

    def build(url, fileform):
        builds.append((url, fileform))
        return config()

    def create(configuration, url):
        adapter = StagedAdapter(configuration, {1: '<p>FRESH_ONE</p>', 2: '<p>FRESH_TWO</p>'})
        adapter._setURL(url)
        created.append(adapter)
        return adapter

    monkeypatch.setattr(fanficfare_config, 'build_configuration', build)
    monkeypatch.setattr(adapters, 'getAdapter', create)
    output = tmp_path / 'output'
    output.mkdir()
    yield output, builds, created
    diagnostics.configure(None)


def assert_fresh(result, engine, url):
    output, builds, created = engine
    assert result['ok'], result
    assert result['url'] == url
    assert builds == [(url, 'epub')]
    assert len(created) == 1
    adapter = created[0]
    assert adapter.fetched == [CH % 1, CH % 2]
    assert not adapter.oldchapters and not adapter.oldchaptersmap
    assert adapter.configuration.getConfig('include_images') == 'coveronly'
    assert result['chapter_added_count'] == result['chapter_written_count'] == 2
    assert result['chapter_updated_count'] == result['chapter_error_count'] == 0
    assert result['warnings'] == []
    path = Path(result['path'])
    assert path.parent == output
    assert path.stat().st_size == result['size'] > 0
    assert read(path.read_bytes())[0] == url
    assert result['file']['exists_after']
    html = ''.join(chapter_html(path.read_bytes()))
    assert 'FRESH_ONE' in html and 'FRESH_TWO' in html
    assert list(output.iterdir()) == [path]


def test_missing_epub_recovers_with_stored_url(tmp_path, engine):
    result = json.loads(bridge.force_download_from_epub(
        str(tmp_path / 'missing.epub'), str(engine[0]), FALLBACK))
    assert_fresh(result, engine, FALLBACK)


@pytest.mark.parametrize('fallback', [
    None, '', ' ', '\t', 'ftp://example.com/story/1', 'file:///story.epub',
    'javascript:alert(1)', '//example.com/story/1', 'https:///story/1',
    'https://user:secret@example.com/story/1', 'https://user@example.com/story/1',
    'https://@example.com/story/1', 'https://example.com:bad/story/1',
    'https://example.com:65536/story/1', 'https://[invalid/story/1',
    ' ' + FALLBACK, FALLBACK + ' ', FALLBACK + '\n',
    'https://exam\tple.com/story/1', FALLBACK + '\u00a0',
    '\x00' + FALLBACK, 'https://example.com\\@other.com/story/1',
    123, [], {},
])
def test_invalid_stored_url_fails_safely_without_engine_or_write(tmp_path, engine, fallback):
    result = json.loads(bridge.force_download_from_epub(
        str(tmp_path / 'missing.epub'), str(engine[0]), fallback))
    assert not result['ok']
    assert result['error'] == 'No usable story URL: EPUB unreadable or source missing, and no valid stored HTTP(S) URL.'
    assert engine[1:] == ([], [])
    assert list(engine[0].iterdir()) == []


def fallback_events():
    return [row for line in diagnostics.read().splitlines()
            if (row := json.loads(line))['event'] == 'force_source_fallback']


@pytest.mark.parametrize('kind,exception_type', [
    ('missing', 'FileNotFoundError'), ('corrupt', 'BadZipFile'),
    ('empty_path', 'FileNotFoundError'), ('source_missing', None),
    ('source_empty', None), ('source_whitespace', None),
])
def test_local_source_failures_recover_with_safe_diagnostic(tmp_path, engine, kind, exception_type):
    old = tmp_path / 'old.epub'
    if kind == 'corrupt':
        old.write_bytes(b'not a zip; SECRET_LOCAL_CONTENT')
    elif kind.startswith('source_'):
        data = write(StagedAdapter(config(), {1: '<p>OLD_CONTENT</p>'}))
        stream = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(stream, 'w') as target:
            for info in source.infolist():
                body = source.read(info.filename)
                if info.filename.endswith('.opf'):
                    replacement = {'source_missing': '', 'source_empty': '<dc:source/>',
                                   'source_whitespace': '<dc:source>  </dc:source>'}[kind]
                    body = re.sub(rb'<dc:source>.*?</dc:source>', replacement.encode(), body)
                target.writestr(info, body)
        old.write_bytes(stream.getvalue())
    result = json.loads(bridge.force_download_from_epub(
        '' if kind == 'empty_path' else str(old), str(engine[0]), FALLBACK))
    assert_fresh(result, engine, FALLBACK)
    assert 'OLD_CONTENT' not in ''.join(chapter_html(Path(result['path']).read_bytes()))
    events = fallback_events()
    assert len(events) == 1
    assert events[0]['stage'] == 'local_source'
    assert events[0]['outcome'] == 'stored_url'
    assert events[0]['exception_type'] == exception_type
    assert events[0]['reason'] == ('source_extraction_failed' if exception_type else 'source_missing')
    assert str(old) not in diagnostics.read()
    assert FALLBACK not in diagnostics.read()
    assert 'SECRET_LOCAL_CONTENT' not in diagnostics.read()


@pytest.mark.parametrize('exception', [
    PermissionError('SECRET_LOCAL_PATH'), OSError('SECRET_LOCAL_PATH'),
    KeyError('SECRET_OPF'), ExpatError('SECRET_XML'), RuntimeError('SECRET_READER'),
])
def test_extraction_exception_class_only_is_reported(tmp_path, engine, monkeypatch, exception):
    def fail(path):
        raise exception
    monkeypatch.setattr(epubutils, 'get_update_data', fail)
    result = json.loads(bridge.force_download_from_epub('unreadable', str(engine[0]), FALLBACK))
    assert_fresh(result, engine, FALLBACK)
    assert fallback_events()[0]['exception_type'] == type(exception).__name__
    assert 'SECRET_' not in diagnostics.read()


def test_extraction_exception_class_is_bounded(tmp_path, engine, monkeypatch):
    failure_class = type('Reader' + 'X' * 200, (Exception,), {})
    def fail(path):
        raise failure_class('SECRET_MESSAGE' * 1000)
    monkeypatch.setattr(epubutils, 'get_update_data', fail)
    result = json.loads(bridge.force_download_from_epub('missing', str(engine[0]), FALLBACK))
    assert_fresh(result, engine, FALLBACK)
    assert fallback_events()[0]['exception_type'] == failure_class.__name__[:80]
    assert 'SECRET_MESSAGE' not in diagnostics.read()


@pytest.mark.parametrize('kind', ['missing', 'corrupt', 'source_missing', 'unreadable'])
def test_unusable_local_source_without_fallback_never_builds_or_writes(
        tmp_path, engine, monkeypatch, kind):
    old = tmp_path / 'old.epub'
    if kind == 'corrupt':
        old.write_bytes(b'SECRET_CORRUPT')
    elif kind == 'source_missing':
        monkeypatch.setattr(epubutils, 'get_update_data', lambda path: (None,))
    elif kind == 'unreadable':
        def fail(path):
            raise PermissionError('SECRET_PATH')
        monkeypatch.setattr(epubutils, 'get_update_data', fail)
    result = json.loads(bridge.force_download_from_epub(str(old), str(engine[0])))
    assert not result['ok']
    assert result['error'].startswith('No usable story URL:')
    assert engine[1:] == ([], [])
    assert list(engine[0].iterdir()) == []
    assert 'SECRET_' not in json.dumps(result) + diagnostics.read()


def test_rejected_fallback_has_safe_outcome_diagnostic(tmp_path, engine):
    result = json.loads(bridge.force_download_from_epub('missing', str(engine[0]), 'bad SECRET_URL'))
    assert not result['ok']
    assert fallback_events()[0]['outcome'] == 'no_usable_url'
    assert fallback_events()[0]['exception_type'] == 'FileNotFoundError'
    assert 'SECRET_URL' not in diagnostics.read()


@pytest.mark.parametrize('fallback', [FALLBACK, 'invalid stored URL', None])
def test_embedded_source_wins_and_force_never_reuses_old_chapters(tmp_path, engine, fallback):
    old = tmp_path / 'old.epub'
    old.write_bytes(write(StagedAdapter(config(), {1: '<p>OLD_CONTENT</p>'})))
    before = old.read_bytes()
    result = json.loads(bridge.force_download_from_epub(str(old), str(engine[0]), fallback))
    assert_fresh(result, engine, STORY_URL)
    assert fallback_events() == []
    assert old.read_bytes() == before
    assert 'OLD_CONTENT' not in ''.join(chapter_html(Path(result['path']).read_bytes()))


@pytest.mark.parametrize('missing', [False, True])
def test_two_argument_call_remains_compatible(tmp_path, engine, missing):
    old = tmp_path / 'old.epub'
    if not missing:
        old.write_bytes(write(StagedAdapter(config(), {1: '<p>OLD</p>'})))
    result = json.loads(bridge.force_download_from_epub(str(old), str(engine[0])))
    if missing:
        assert not result['ok']
        assert engine[1:] == ([], [])
        assert list(engine[0].iterdir()) == []
    else:
        assert_fresh(result, engine, STORY_URL)


@pytest.mark.parametrize('fallback', [
    'http://example.com/story/1', 'HTTPS://EXAMPLE.com/story/1?Case=A%20B#part',
    'https://example.com:443/story/1',
])
def test_valid_stored_url_is_passed_through_unchanged(tmp_path, engine, fallback):
    result = json.loads(bridge.force_download_from_epub('missing', str(engine[0]), fallback))
    assert_fresh(result, engine, fallback)


@pytest.mark.parametrize('local_missing', [False, True])
@pytest.mark.parametrize('stage', ['configuration', 'adapter', 'http', 'login', 'writer_factory', 'writer'])
def test_remote_and_writer_errors_do_not_restart_or_try_other_url(
        tmp_path, engine, monkeypatch, local_missing, stage):
    old = tmp_path / 'old.epub'
    if not local_missing:
        old.write_bytes(write(StagedAdapter(config(), {1: '<p>OLD</p>'})))
    selected = FALLBACK if local_missing else STORY_URL
    errors = {'http': HTTPErrorFFF(selected, 503, 'offline'),
              'login': FailedToLogin(selected, 'fixture-user')}
    failure = errors.get(stage, PermissionError('fixture boundary failure'))
    attempts, write_attempts = [], []
    create, make_writer = adapters.getAdapter, writers.getWriter

    def fail(*args, **kwargs):
        raise failure

    def create_once(configuration, url):
        attempts.append(url)
        if stage == 'adapter':
            raise failure
        adapter = create(configuration, url)
        if stage in ('http', 'login'):
            monkeypatch.setattr(adapter, 'getStoryMetadataOnly', fail)
        return adapter

    def writer_once(*args, **kwargs):
        write_attempts.append(True)
        if stage == 'writer_factory':
            raise failure
        return make_writer(*args, **kwargs)

    if stage == 'configuration':
        original_build = fanficfare_config.build_configuration
        def build_then_fail(*args):
            original_build(*args)
            raise failure
        monkeypatch.setattr(fanficfare_config, 'build_configuration', build_then_fail)
    monkeypatch.setattr(adapters, 'getAdapter', create_once)
    monkeypatch.setattr(writers, 'getWriter', writer_once)
    if stage == 'writer':
        monkeypatch.setattr(EpubWriter, 'writeStoryImpl', fail)
    result = json.loads(bridge.force_download_from_epub(str(old), str(engine[0]), FALLBACK))
    assert not result['ok']
    assert result['exception_type'] == type(failure).__name__
    assert result['http_status'] == (503 if stage == 'http' else None)
    assert engine[1] == [(selected, 'epub')]
    assert attempts == ([] if stage == 'configuration' else [selected])
    assert len(write_attempts) == int(stage in ('writer_factory', 'writer'))
    assert len(fallback_events()) == int(local_missing)
    assert list(engine[0].iterdir()) == []
    if stage == 'writer':
        assert engine[2][0].fetched == [CH % 1, CH % 2]
