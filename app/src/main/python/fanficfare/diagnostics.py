"""Bounded, credential-free Android engine diagnostics (local divergence).

No file or home-directory access until configure(config_dir) is called. Logs are
JSON lines in config_dir/diagnostics/engine.jsonl[.1-.3]; newest file is unsuffixed.
Diagnostic failures must never change engine behavior. Exception text, HTTP
headers/bodies and configuration values are deliberately never accepted.
"""
import functools
import importlib.metadata
import json
import os
import platform
import re
import threading
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from urllib.parse import unquote, urlsplit

MAX_BYTES = 512 * 1024
BACKUP_COUNT = 3
_lock = threading.RLock()
_local = threading.local()
_directory = None
_FIELDS = frozenset({
    'host', 'path', 'phase', 'stage', 'duration_ms', 'outcome', 'error_type',
    'http_status', 'request_sequence', 'method', 'kind', 'retry_attempt',
    'requested_sleep_s', 'actual_sleep_s', 'sleep_source', 'chapter_index',
    'chapter_total', 'from_cache', 'engine_version', 'python_version',
    'requests_version', 'urllib3_version', 'chapter_attempt',
    'exception_type', 'reason', 'errno', 'epub_key',
})
_CONTEXT_KEYS = frozenset({'job_id', 'work_name', 'request_id', 'run_attempt',
                           'operation', 'bridge_attempt'})
_ID = re.compile(r'^[A-Za-z0-9_.:-]{1,128}$')


def configure(config_dir):
    global _directory
    with _lock:
        _directory = os.path.join(os.path.abspath(str(config_dir)), 'diagnostics') if config_dir else None


def set_context(context_json):
    # Clear even on malformed input, so a failed setter cannot reuse a prior job.
    _local.pending = {}
    try:
        context = json.loads(context_json)
        if not isinstance(context, dict):
            raise ValueError('context must be an object')
        safe = {}
        for key in _CONTEXT_KEYS:
            value = context.get(key)
            if type(value) is int and value >= 0:
                safe[key] = value
            elif isinstance(value, str) and _ID.fullmatch(value):
                safe[key] = value
        _local.pending = safe
        return json.dumps({'ok': True})
    except Exception:
        return json.dumps({'ok': False, 'error': 'invalid diagnostic context'})


def safe_url(url):
    try:
        parsed = urlsplit(str(url))
        if parsed.scheme not in ('http', 'https'):
            return {}
        host = parsed.hostname or ''
        path = parsed.path or '/'
        # Classify decoded segments, but never emit decoded control characters.
        # Once a credential route begins, redact its entire suffix: overlapping
        # markers such as /auth/token/value must not consume one another.
        path = re.sub(r';[^/]*', '', path)
        segments = path.split('/')
        sensitive = False
        for index, segment in enumerate(segments):
            marker = any(re.match(
                r'(?i)^(?:token|access[_-]?token|refresh[_-]?token|password|secret|auth|apikey|api_key)(?:=|$)',
                part) for part in unquote(segment).split('/'))
            if sensitive or marker:
                segments[index] = '[redacted]' if segment else ''
                sensitive = True
        path = '/'.join(segments)
        return {'host': re.sub(r'[^A-Za-z0-9.:[\]-]', '_', host)[:160],
                'path': ''.join(c for c in path if c.isprintable())[:240]}
    except Exception:
        return {}


def _path(index=0):
    assert _directory is not None
    return os.path.join(_directory, 'engine.jsonl' + ('.' + str(index) if index else ''))


def emit(event, **fields):
    if _directory is None:
        return
    try:
        state = getattr(_local, 'active', None) or {}
        row = dict(state.get('context', {}))
        row.update(getattr(_local, 'chapter', None) or {})
        row.update(getattr(_local, 'request', None) or {})
        row.update({key: value for key, value in fields.items() if key in _FIELDS
                    and (value is None or type(value) in (str, int, float, bool))})
        row.update(timestamp=datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z'),
                   pid=os.getpid(), thread_id=threading.get_ident(), event=event,
                   operation_id=state.get('operation_id'),
                   operation=state.get('operation'), phase=fields.get('phase', state.get('phase', 'idle')))
        data = (json.dumps(row, ensure_ascii=True, separators=(',', ':')) + '\n').encode('utf-8')
        if len(data) > MAX_BYTES:
            return
        with _lock:
            if _directory is None:
                return
            os.makedirs(_directory, mode=0o700, exist_ok=True)
            if os.path.exists(_path()) and os.path.getsize(_path()) + len(data) > MAX_BYTES:
                for index in range(BACKUP_COUNT, 0, -1):
                    source = _path(index - 1)
                    if os.path.exists(source):
                        os.replace(source, _path(index))
            with open(_path(), 'ab') as out:
                out.write(data)
    except Exception:
        pass


def read():
    with _lock:
        if _directory is None:
            return ''
        chunks = []
        for index in range(BACKUP_COUNT, -1, -1):
            try:
                with open(_path(index), encoding='utf-8') as source:
                    chunks.append(source.read())
            except OSError:
                pass
        return ''.join(chunks)


def clear():
    with _lock:
        if _directory is not None:
            for index in range(BACKUP_COUNT + 1):
                try:
                    os.remove(_path(index))
                except OSError:
                    pass


@functools.lru_cache(maxsize=1)
def versions():
    from .cli import version
    result = {'engine_version': version, 'python_version': platform.python_version()}
    for package in ('requests', 'urllib3'):
        try:
            result[package + '_version'] = importlib.metadata.version(package)
        except Exception:
            result[package + '_version'] = 'unknown'
    return result


def phase(name):
    """Scoped metadata/chapter/serialization phases, including adapter overrides."""
    def decorate(function):
        @functools.wraps(function)
        def wrapped(*args, **kwargs):
            if _directory is None:
                return function(*args, **kwargs)
            previous = getattr(_local, 'active', None)
            state = dict(previous or {})
            state['phase'] = name
            previous_chapter = getattr(_local, 'chapter', None)
            _local.active = state
            start = time.monotonic()
            emit('phase_begin')
            outcome = 'success'
            try:
                return function(*args, **kwargs)
            except BaseException as exc:
                outcome = 'failed'
                if name == 'chapter':
                    finish_chapter('failed', error_type=type(exc).__name__)
                emit('phase_exception', error_type=type(exc).__name__)
                raise
            finally:
                if name == 'chapter':
                    _local.chapter = previous_chapter
                emit('phase_end', outcome=outcome,
                     duration_ms=round((time.monotonic() - start) * 1000, 3))
                _local.active = previous
        return wrapped
    return decorate


def start_chapter(index, total, url):
    if _directory is None:
        return
    _local.chapter = {'chapter_index': index + 1, 'chapter_total': total, 'chapter_attempt': 1}
    _local.chapter_started = time.monotonic()
    emit('chapter_begin', **safe_url(url))


def chapter_retry(error):
    chapter = getattr(_local, 'chapter', None)
    if chapter is not None:
        chapter['chapter_attempt'] = chapter.get('chapter_attempt', 1) + 1
    emit('chapter_retry', chapter_attempt=chapter['chapter_attempt'] if chapter else 2,
         error_type=type(error).__name__, http_status=getattr(error, 'status_code', None))


def finish_chapter(outcome='success', **fields):
    if getattr(_local, 'chapter', None) is not None:
        emit('chapter_end', outcome=outcome,
             duration_ms=round((time.monotonic() - _local.chapter_started) * 1000, 3), **fields)
        _local.chapter = None


def timed_sleep(actual, source, requested=None, sleep_fn=time.sleep, **fields):
    """Log the scheduled delay separately from elapsed monotonic sleep time."""
    data = dict(fields, sleep_source=source,
                requested_sleep_s=actual if requested is None else requested,
                actual_sleep_s=actual)
    emit('sleep_begin', **data)
    start = time.monotonic()
    try:
        if actual > 0:
            sleep_fn(actual)
    except BaseException as exc:
        emit('sleep_exception', error_type=type(exc).__name__,
             duration_ms=round((time.monotonic() - start) * 1000, 3), **data)
        raise
    else:
        emit('sleep_end', duration_ms=round((time.monotonic() - start) * 1000, 3), **data)


def retry_attempt(number):
    request = getattr(_local, 'request', None)
    if request is not None:
        request['retry_attempt'] = number


def response_status(status):
    request = getattr(_local, 'request', None)
    if request is not None and type(status) is int:
        request['http_status'] = status


@contextmanager
def capture_request():
    """Capture only this chain's completed span for an outer politeness delay."""
    previous = getattr(_local, 'request_capture', None)
    captured = {}
    _local.request_capture = captured
    try:
        yield captured
    finally:
        _local.request_capture = previous


def request_call(function):
    """One request span across generic/custom fetchers and Requests subclasses."""
    @functools.wraps(function)
    def wrapped(self, method, url, *args, **kwargs):
        if _directory is None or getattr(_local, 'request', None) is not None:
            return function(self, method, url, *args, **kwargs)
        sequence = getattr(_local, 'sequence', 0) + 1
        _local.sequence = sequence
        headers = kwargs.get('headers') or {}
        image = kwargs.get('image') or headers.get('Accept') == 'image/*'
        state = getattr(_local, 'active', None) or {}
        _local.request = dict(safe_url(url), method=method if method in ('GET', 'POST') else 'OTHER',
                              kind='image' if image else 'chapter' if state.get('phase') == 'chapter' else 'page',
                              request_sequence=sequence, retry_attempt=1)
        start = time.monotonic()
        emit('request_begin')
        try:
            result = function(self, method, url, *args, **kwargs)
            emit('request_return', from_cache=bool(getattr(result, 'fromcache', False)),
                 duration_ms=round((time.monotonic() - start) * 1000, 3))
            captured = getattr(_local, 'request_capture', None)
            if captured is not None:
                captured.update(_local.request)
            return result
        except BaseException as exc:
            response_status(getattr(exc, 'status_code', None))
            emit('request_exception', error_type=type(exc).__name__,
                 duration_ms=round((time.monotonic() - start) * 1000, 3))
            raise
        finally:
            _local.request = None
    return wrapped


def operation(name, url_argument=False):
    """Scope a JSON bridge call, including early returns, without API changes."""
    def decorate(function):
        @functools.wraps(function)
        def wrapped(*args, **kwargs):
            context = getattr(_local, 'pending', {})
            _local.pending = {}
            previous = getattr(_local, 'active', None)
            previous_chapter = getattr(_local, 'chapter', None) if previous else None
            previous_request = getattr(_local, 'request', None) if previous else None
            previous_sequence = getattr(_local, 'sequence', 0) if previous else 0
            _local.chapter = None
            _local.request = None
            _local.sequence = 0
            _local.active = None
            start = None
            outcome = 'exception'
            terminal = {}
            try:
                # Setup is optional too: entropy, clocks and version probes may
                # fail. Never wrap the real operation in a fallback/retry block.
                try:
                    if _directory is not None:
                        _local.active = {'context': context, 'operation_id': str(uuid.uuid4()),
                                         'operation': name, 'phase': 'metadata'}
                        start = time.monotonic()
                        target = safe_url(args[0] if args else kwargs.get('url', '')) if url_argument else {}
                        try:
                            version_fields = versions()
                        except Exception:
                            version_fields = {key: 'unknown' for key in (
                                'engine_version', 'python_version', 'requests_version', 'urllib3_version')}
                        emit('operation_begin', **target, **version_fields)
                except Exception:
                    pass
                result = function(*args, **kwargs)
                try:
                    parsed = json.loads(result)
                    outcome = 'skipped' if parsed.get('ok') and parsed.get('skipped') else 'success' if parsed.get('ok') else 'failed'
                    status = parsed.get('http_status')
                    if type(status) is int:
                        terminal['http_status'] = status
                    error_type = parsed.get('exception_type')
                    if isinstance(error_type, str) and _ID.fullmatch(error_type):
                        terminal['error_type'] = error_type
                except (TypeError, ValueError, AttributeError):
                    outcome = 'invalid_result'
                return result
            except BaseException as exc:
                terminal['error_type'] = type(exc).__name__
                raise
            finally:
                try:
                    if _directory is not None and start is not None:
                        emit('operation_end', outcome=outcome,
                             duration_ms=round((time.monotonic() - start) * 1000, 3), **terminal)
                except Exception:
                    pass
                finally:
                    _local.active = previous
                    _local.chapter = previous_chapter
                    _local.request = previous_request
                    _local.sequence = previous_sequence
                    _local.pending = {}
        return wrapped
    return decorate
