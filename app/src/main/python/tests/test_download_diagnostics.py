"""Offline diagnostics/retry regressions; no credentials or live network."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from urllib3.response import HTTPResponse
from urllib3.util.retry import Retry
from fanficfare.fetchers.fetcher_requests import RequestsFetcher


class RetryCapTests(unittest.TestCase):
    def test_real_cloudscraper_sessions_use_distinct_page_and_image_policies(self):
        import cloudscraper
        from fanficfare.fetchers.fetcher_cloudscraper import CloudScraperFetcher
        fetcher = CloudScraperFetcher(lambda key, default=None: default, lambda key: [])
        with patch('socket.create_connection', side_effect=AssertionError('NETWORK FORBIDDEN')), patch(
                'requests.sessions.Session.request', side_effect=AssertionError('NETWORK FORBIDDEN')):
            image = fetcher.get_image_session()
            page = fetcher.get_requests_session()
            self.addCleanup(image.close)
            self.addCleanup(page.close)
            self.assertIsInstance(image, cloudscraper.CloudScraper)
            self.assertIsInstance(page, cloudscraper.CloudScraper)
            self.assertIsNot(image, page)
            for session, policy, cap in ((image, fetcher.image_retries, 2), (page, fetcher.retries, 60)):
                self.assertIsInstance(session.get_adapter('https://example.invalid'), cloudscraper.CipherSuiteAdapter)
                for scheme in ('http', 'https'):
                    with self.subTest(scheme=scheme, cap=cap):
                        adapter = session.get_adapter(scheme + '://example.invalid')
                        from requests.adapters import HTTPAdapter
                        assert isinstance(adapter, HTTPAdapter)
                        mounted = adapter.max_retries
                        self.assertIs(mounted, policy)
                        response = HTTPResponse(status=503, headers={'Retry-After': '3600'})
                        clone = mounted.increment(method='GET', url='/image', response=response)
                        with patch('urllib3.util.retry.time.sleep') as sleep:
                            clone.sleep(response)
                        sleep.assert_called_once_with(cap)

    def test_http_dates_and_exponential_backoff_remain_bounded(self):
        from email.utils import formatdate
        response = HTTPResponse(status=503, headers={'Retry-After': formatdate(100000, usegmt=True)})
        for policy, cap in ((self.fetcher().retries, 60), (self.fetcher().image_retries, 2)):
            with self.subTest(cap=cap), patch('fanficfare.fetchers.fetcher_requests.time.time', return_value=0):
                clone = policy.increment(method='GET', url='/x', response=response)
                with patch('urllib3.util.retry.time.sleep') as sleep:
                    clone.sleep(response)
                sleep.assert_called_once_with(cap)
                self.assertEqual(clone.get_retry_after(response), cap)
        policy = self.fetcher().retries
        for _ in range(4):
            policy = policy.increment(method='GET', url='/x', response=HTTPResponse(status=503))
        with patch('urllib3.util.retry.time.sleep') as sleep:
            policy.sleep()
        sleep.assert_called_once_with(10)

    def test_malformed_retry_after_is_not_copied_to_error(self):
        from urllib3.exceptions import InvalidHeader
        response = HTTPResponse(status=503, headers={'Retry-After': 'password=PRIVATE_HEADER'})
        with self.assertRaises(InvalidHeader) as caught:
            self.fetcher().image_retries.sleep(response)
        self.assertNotIn('PRIVATE_HEADER', str(caught.exception))

    def test_retry_sleep_logs_requested_and_bounded_delay_on_each_clone(self):
        from fanficfare import diagnostics
        with tempfile.TemporaryDirectory() as root:
            diagnostics.configure(root)
            self.addCleanup(diagnostics.configure, None)
            for policy, cap in ((self.fetcher().retries, 60), (self.fetcher().image_retries, 2)):
                response = HTTPResponse(status=503, headers={'Retry-After': '999999'})
                policy = policy.increment(method='GET', url='/chapter?secret=HIDDEN', response=response)
                with patch('urllib3.util.retry.time.sleep') as sleep:
                    policy.sleep(response)
                sleep.assert_called_once_with(cap)
            rows = [json.loads(line) for line in diagnostics.read().splitlines()]
            sleeps = [r for r in rows if r['event'] == 'sleep_begin']
            self.assertEqual([r['requested_sleep_s'] for r in sleeps], [999999, 999999])
            self.assertEqual([r['actual_sleep_s'] for r in sleeps], [60, 2])
            self.assertTrue(all(r['retry_attempt'] == 2 for r in sleeps))
            self.assertTrue(all(r['http_status'] == 503 for r in sleeps))
            self.assertEqual(len([r for r in rows if r['event'] == 'sleep_end']), 2)
            self.assertNotIn('HIDDEN', diagnostics.read())

    def fetcher(self):
        return RequestsFetcher(lambda key, default=None: default, lambda key: [])

    def test_old_urllib3_caps_both_ladders_and_clones(self):
        original_init = Retry.__init__
        def old_init(policy, *args, **kwargs):
            if 'retry_after_max' in kwargs:
                raise TypeError('unsupported keyword')
            original_init(policy, *args, **kwargs)
        with patch.object(Retry, '__init__', old_init), patch.object(
                Retry, 'parse_retry_after', lambda self, value: int(value)):
            fetcher = self.fetcher()
            for policy, cap in ((fetcher.retries, 60), (fetcher.image_retries, 2)):
                with self.subTest(cap=cap):
                    # Older Retry.new does not know about retry_after_max.
                    with patch.object(Retry, 'new', lambda self, **kw: type(self)(
                            total=self.total, history=self.history, **kw)):
                        policy = policy.new()
                    response = HTTPResponse(status=503, headers={'Retry-After': '3600'})
                    with patch('urllib3.util.retry.time.sleep') as sleep:
                        policy.sleep(response)
                    sleep.assert_called_once_with(cap)

    def test_image_retry_after_is_capped_even_after_increment(self):
        policy = self.fetcher().image_retries
        response = HTTPResponse(status=503, headers={'Retry-After': '3600'})
        policy = policy.increment(method='GET', url='/image', response=response)
        with patch('urllib3.util.retry.time.sleep') as sleep:
            policy.sleep(response)
        sleep.assert_called_once_with(2)


class DiagnosticRequestTests(unittest.TestCase):
    def test_sensitive_path_suffixes_and_assignments_are_redacted(self):
        from fanficfare import diagnostics
        paths = (
            '/auth/token/NESTED_SECRET',
            '/access_token/ROUTE_SECRET',
            '/password=ASSIGNMENT_SECRET',
            '/chapter/api_key=ASSIGNMENT_SECRET/next',
            '/%74oken/ENCODED_SECRET',
            '/token%2FENCODED_SECRET',
            '/chapter%2Fauth%2Ftoken%2FENCODED_SECRET',
            '/password%3DENCODED_SECRET',
            '/auth/token/NESTED_SECRET/password/SECOND_SECRET',
        )
        for path in paths:
            with self.subTest(path=path):
                safe = diagnostics.safe_url('https://user:pass@example.invalid' + path + '?token=QUERY_SECRET#FRAGMENT_SECRET')
                self.assertEqual(safe['host'], 'example.invalid')
                self.assertNotIn('SECRET', safe['path'])
                self.assertIn('[redacted]', safe['path'])
        self.assertEqual(diagnostics.safe_url('https://example.invalid/works/123/chapters/456')['path'],
                         '/works/123/chapters/456')

    def test_request_boundaries_status_kind_duration_and_safe_exception(self):
        from fanficfare import diagnostics
        import requests
        with tempfile.TemporaryDirectory() as root:
            diagnostics.configure(root)
            self.addCleanup(diagnostics.configure, None)
            fetcher = RequestsFetcher(lambda key, default=None: default, lambda key: [])
            response = requests.Response()
            response.status_code = 200
            response.url = 'https://example.invalid/chapter?private=HIDDEN'
            response._content = b'BODY_MUST_NOT_APPEAR'
            session = Mock()
            session.request.side_effect = [response, requests.ConnectionError('password=TOPSECRET')]
            with self.assertLogs('fanficfare.fetchers.fetcher_requests', level='DEBUG') as captured, patch.object(
                    fetcher, 'get_requests_session', return_value=session), patch.object(
                    fetcher, 'get_image_session', return_value=session):
                fetcher.do_request('GET', 'https://user:pass@example.invalid/chapter?private=HIDDEN')
                with self.assertRaises(requests.ConnectionError):
                    fetcher.do_request('GET', 'https://images.invalid/pic.jpg?token=TOKEN', image=True)
            log = diagnostics.read()
            for secret in ('HIDDEN', 'TOPSECRET', 'TOKEN', 'user:', 'pass@'):
                self.assertNotIn(secret, '\n'.join(captured.output))
            rows = [json.loads(line) for line in log.splitlines()]
            self.assertEqual([r['event'] for r in rows],
                             ['request_begin', 'request_return', 'request_begin', 'request_exception'])
            self.assertEqual(rows[1]['http_status'], 200)
            self.assertEqual(rows[0]['kind'], 'page')
            self.assertEqual(rows[2]['kind'], 'image')
            self.assertEqual(rows[3]['error_type'], 'ConnectionError')
            self.assertGreaterEqual(rows[1]['duration_ms'], 0)
            self.assertEqual(rows[0]['request_sequence'], rows[1]['request_sequence'])
            for secret in ('HIDDEN', 'TOPSECRET', 'BODY_MUST_NOT_APPEAR', 'TOKEN', 'user:', 'pass@'):
                self.assertNotIn(secret, log)


class DiagnosticChapterTests(unittest.TestCase):
    def test_politeness_sleep_uses_only_its_own_completed_request_context(self):
        from fanficfare import diagnostics
        from fanficfare.fetchers.base_fetcher import FetcherResponse
        from fanficfare.fetchers.decorators import SleepDecorator
        fetcher = RequestsFetcher(lambda key, default=None: 1 if key == 'slow_down_sleep_time' else default,
                                  lambda key: [])
        decorator = SleepDecorator()
        decorator.decorate_fetcher(fetcher)
        @diagnostics.operation('download')
        @diagnostics.phase('chapter')
        def operation():
            fetcher.do_request('GET', 'https://example.invalid/chapter')
            fetcher.do_request('GET', 'https://images.invalid/image', image=True)
            # A chain which performs no request must not inherit the last span.
            decorator.fetcher_do_request(fetcher, lambda *a, **k: FetcherResponse(b'OK'),
                                         'GET', 'https://other.invalid/no-request')
            diagnostics.emit('after_sleep')
            return '{"ok":true}'
        with tempfile.TemporaryDirectory() as root:
            diagnostics.configure(root)
            self.addCleanup(diagnostics.configure, None)
            with patch.object(fetcher, 'request', return_value=FetcherResponse(b'OK')), patch(
                    'fanficfare.fetchers.decorators.time.sleep'):
                operation()
            rows = [json.loads(line) for line in diagnostics.read().splitlines()]
            requests = [r for r in rows if r['event'] == 'request_return']
            sleeps = [r for r in rows if r['event'] == 'sleep_begin']
            self.assertEqual(len(requests), 2)
            self.assertEqual(len(sleeps), 3)
            for request, sleep in zip(requests, sleeps):
                for key in ('request_sequence', 'kind', 'host', 'path', 'method'):
                    self.assertEqual(sleep.get(key), request[key], key)
            self.assertEqual([r['kind'] for r in sleeps[:2]], ['chapter', 'image'])
            self.assertNotIn('request_sequence', sleeps[2])
            for row in rows:
                if row['event'] in ('after_sleep', 'operation_end'):
                    self.assertNotIn('request_sequence', row)

    def test_placeholder_chapter_logs_safe_error_type_and_completed_progress(self):
        from fanficfare import diagnostics
        from upstream_core_helpers import StagedAdapter, config
        adapter = StagedAdapter(config(continue_on_chapter_error='true'), {1: RuntimeError('PASSWORD_SECRET')})
        with tempfile.TemporaryDirectory() as root:
            diagnostics.configure(root)
            self.addCleanup(diagnostics.configure, None)
            adapter.getStory()
            rows = [json.loads(line) for line in diagnostics.read().splitlines()]
            failures = [r for r in rows if r['event'] == 'chapter_exception']
            self.assertEqual(len(failures), 1)
            self.assertEqual(failures[0]['error_type'], 'RuntimeError')
            self.assertEqual([r['outcome'] for r in rows if r['event'] == 'chapter_end'], ['placeholder'])
            self.assertNotIn('PASSWORD_SECRET', diagnostics.read())

    def test_chapter_retry_attempt_is_correlated_with_subsequent_requests(self):
        from fanficfare import diagnostics
        from fanficfare.fetchers.base_fetcher import FetcherResponse
        from fanficfare.exceptions import HTTPErrorFFF
        from upstream_core_helpers import StagedAdapter, config
        adapter = StagedAdapter(config(), {1: '<p>ONE</p>', 2: '<p>TWO</p>'})
        fetcher = RequestsFetcher(lambda key, default=None: default, lambda key: [])
        def chapter(url):
            fetcher.do_request('GET', url)
            return '<p>TEXT</p>'
        with tempfile.TemporaryDirectory() as root:
            diagnostics.configure(root)
            self.addCleanup(diagnostics.configure, None)
            with patch.object(fetcher, 'request', side_effect=[
                    HTTPErrorFFF(adapter.url, 503, 'PRIVATE_BODY', None),
                    FetcherResponse(b'OK'), FetcherResponse(b'OK')]), patch.object(
                    adapter, 'getChapterText', side_effect=chapter), patch(
                    'fanficfare.adapters.base_adapter.time.sleep'):
                adapter.getStory()
            rows = [json.loads(line) for line in diagnostics.read().splitlines()]
            requests = [r for r in rows if r['event'] == 'request_begin']
            self.assertEqual([r.get('chapter_attempt') for r in requests], [1, 2, 1])
            self.assertEqual([r['chapter_index'] for r in requests], [1, 1, 2])
            self.assertEqual([r['outcome'] for r in rows if r['event'] == 'chapter_end'], ['success', 'success'])
            self.assertNotIn('PRIVATE_BODY', diagnostics.read())

    def test_politeness_and_chapter_retry_delays_are_observable_and_unchanged(self):
        from fanficfare import diagnostics
        from fanficfare.fetchers.base_fetcher import FetcherResponse
        from fanficfare.fetchers.decorators import SleepDecorator
        from fanficfare.exceptions import HTTPErrorFFF
        from upstream_core_helpers import StagedAdapter, config
        with tempfile.TemporaryDirectory() as root:
            diagnostics.configure(root)
            self.addCleanup(diagnostics.configure, None)
            fetcher = RequestsFetcher(lambda key, default=None: 5 if key == 'slow_down_sleep_time' else default,
                                      lambda key: [])
            chain = Mock(return_value=FetcherResponse(b'BODY', 'https://example.invalid'))
            with patch('fanficfare.fetchers.decorators.random.uniform', return_value=4), patch(
                    'fanficfare.fetchers.decorators.time.sleep') as sleep:
                SleepDecorator().fetcher_do_request(fetcher, chain, 'GET', 'https://example.invalid')
            sleep.assert_called_once_with(4)
            adapter = StagedAdapter(config(), {1: '<p>ONE</p>'})
            with patch.object(adapter, 'getChapterText', side_effect=[
                    HTTPErrorFFF('https://example.invalid', 503, 'BODY_SECRET', None), '<p>recovered</p>']), patch(
                    'fanficfare.adapters.base_adapter.time.sleep') as sleep:
                adapter.getChapterTextNum('https://example.invalid', 0)
            sleep.assert_called_once_with(5)
            sleeps = [json.loads(line) for line in diagnostics.read().splitlines()
                      if json.loads(line)['event'] == 'sleep_begin']
            self.assertEqual([r['sleep_source'] for r in sleeps], ['politeness', 'chapter_retry'])
            self.assertEqual([r['actual_sleep_s'] for r in sleeps], [4, 5])
            self.assertEqual(sleeps[1]['chapter_attempt'], 2)
            self.assertEqual(sleeps[1]['http_status'], 503)
            self.assertNotIn('BODY_SECRET', diagnostics.read())

    def test_real_bridge_download_records_metadata_chapters_and_actual_write_phases(self):
        import fanficfare_bridge as bridge
        from upstream_core_helpers import StagedAdapter, config
        from fanficfare.fetchers.base_fetcher import FetcherResponse
        configuration = config()
        adapter = StagedAdapter(configuration, {1: '<p>PRIVATE_BODY</p>', 2: '<p>SECOND</p>'})
        # Exercise the generic request boundary from an adapter override, not
        # only BaseSiteAdapter.getChapterTextNum (several adapters override it).
        def chapter(url, index):
            fetcher = RequestsFetcher(lambda key, default=None: default, lambda key: [])
            with patch.object(fetcher, 'request', return_value=FetcherResponse(b'PRIVATE_BODY', url)):
                fetcher.do_request('GET', url)
            return adapter.getChapterText(url)
        with tempfile.TemporaryDirectory() as root:
            bridge.set_config_dir(root)
            self.addCleanup(bridge.set_config_dir, None)
            with patch.object(bridge, 'build_configuration', return_value=configuration), patch(
                    'fanficfare.adapters.getAdapter', return_value=adapter), patch.object(
                    adapter, 'getChapterTextNum', side_effect=chapter), patch(
                    'socket.create_connection', side_effect=AssertionError('NETWORK FORBIDDEN')):
                result = json.loads(bridge.download_story(adapter.url, root))
            self.assertTrue(result['ok'], result)
            rows = [json.loads(line) for line in json.loads(bridge.read_download_debug())['log'].splitlines()]
            starts = [r for r in rows if r['event'] == 'chapter_begin']
            self.assertEqual([r['chapter_index'] for r in starts], [1, 2])
            self.assertTrue(all(r['chapter_total'] == 2 for r in starts))
            self.assertEqual(len([r for r in rows if r['event'] == 'chapter_end']), 2)
            phases = [r['phase'] for r in rows if r['event'] == 'phase_begin']
            self.assertIn('metadata', phases)
            self.assertIn('chapter', phases)
            self.assertIn('write', phases)
            requests = [r for r in rows if r['event'] == 'request_begin']
            self.assertEqual([r['chapter_index'] for r in requests], [1, 2])
            self.assertTrue(all(r['kind'] == 'chapter' for r in requests))
            self.assertNotIn('PRIVATE_BODY', json.dumps(rows))
            completed = next(r for r in rows if r.get('stage') == 'fanficfare_call_returned')
            self.assertGreaterEqual(completed.get('duration_ms', -1), 0)


class DiagnosticStorageTests(unittest.TestCase):
    def test_rotation_is_bounded_and_oversized_rows_cannot_bypass_cap(self):
        from fanficfare import diagnostics
        with tempfile.TemporaryDirectory() as root:
            diagnostics.configure(root)
            self.addCleanup(diagnostics.configure, None)
            with patch.object(diagnostics, 'MAX_BYTES', 1024):
                for index in range(60):
                    diagnostics.emit('stage', stage='iteration_' + str(index))
                diagnostics.emit('stage', stage='x' * 10000)
            paths = list(Path(root, 'diagnostics').iterdir())
            self.assertEqual({p.name for p in paths}, {'engine.jsonl', 'engine.jsonl.1', 'engine.jsonl.2', 'engine.jsonl.3'})
            self.assertTrue(all(p.stat().st_size <= 1024 for p in paths))
            rows = [json.loads(line) for line in diagnostics.read().splitlines()]
            self.assertTrue(any(r.get('stage') == 'iteration_59' for r in rows))
            diagnostics.clear()
            self.assertEqual(diagnostics.read(), '')

    def test_version_probe_failure_does_not_block_bridge_operation(self):
        import fanficfare_bridge as bridge
        from fanficfare import diagnostics
        with tempfile.TemporaryDirectory() as root:
            bridge.set_config_dir(root)
            self.addCleanup(bridge.set_config_dir, None)
            with patch.object(diagnostics, 'versions', side_effect=RuntimeError('probe unavailable')), patch.object(
                    bridge, '_import_fanficfare', return_value=False):
                result = json.loads(bridge.get_metadata('https://example.invalid'))
            self.assertFalse(result['ok'])
            rows = [json.loads(line) for line in diagnostics.read().splitlines()]
            self.assertEqual([r['event'] for r in rows], ['operation_begin', 'operation_end'])


class DiagnosticOperationTests(unittest.TestCase):
    def test_diagnostic_setup_failure_never_blocks_or_repeats_operation(self):
        from fanficfare import diagnostics
        calls = []
        @diagnostics.operation('download')
        def operation(fail=False):
            calls.append(fail)
            if fail:
                raise ValueError('BUSINESS_SECRET')
            return '{"ok":true}'
        with tempfile.TemporaryDirectory() as root:
            self.addCleanup(diagnostics.configure, None)
            for enabled in (False, True):
                diagnostics.configure(root if enabled else None)
                for target, attribute in ((diagnostics.uuid, 'uuid4'), (diagnostics.time, 'monotonic'),
                                          (diagnostics, 'emit'), (diagnostics, 'versions')):
                    for fail in (False, True):
                        with self.subTest(enabled=enabled, fault=attribute, fail=fail):
                            diagnostics.set_context('{"job_id": 987}')
                            before = len(calls)
                            with patch.object(target, attribute, side_effect=OSError('DIAGNOSTIC_SECRET')):
                                if fail:
                                    with self.assertRaisesRegex(ValueError, 'BUSINESS_SECRET'):
                                        operation(fail=True)
                                else:
                                    self.assertEqual(operation(), '{"ok":true}')
                            self.assertEqual(len(calls), before + 1)
                            self.assertIsNone(getattr(diagnostics._local, 'active', None))
                            self.assertIsNone(getattr(diagnostics._local, 'request', None))
                            self.assertEqual(diagnostics._local.pending, {})
            self.assertNotIn('DIAGNOSTIC_SECRET', diagnostics.read())
            self.assertNotIn('BUSINESS_SECRET', diagnostics.read())

    def test_worker_context_is_thread_local_and_invalid_replacement_clears_pending(self):
        from concurrent.futures import ThreadPoolExecutor
        import threading
        import fanficfare_bridge as bridge
        barrier = threading.Barrier(2)
        def worker(job):
            bridge.set_download_diagnostic_context(json.dumps({'job_id': job, 'request_id': 'req-' + str(job)}))
            barrier.wait(timeout=5)
            bridge.get_metadata('https://example.invalid')
            bridge.get_metadata('https://example.invalid/direct')
        with tempfile.TemporaryDirectory() as root:
            bridge.set_config_dir(root)
            self.addCleanup(bridge.set_config_dir, None)
            with patch.object(bridge, '_import_fanficfare', return_value=False):
                with ThreadPoolExecutor(max_workers=2) as pool:
                    list(pool.map(worker, (31, 32)))
                bridge.set_download_diagnostic_context('{"job_id": 999}')
                self.assertFalse(json.loads(bridge.set_download_diagnostic_context('NOT JSON'))['ok'])
                bridge.get_metadata('https://example.invalid')
            rows = [json.loads(line) for line in json.loads(bridge.read_download_debug())['log'].splitlines()]
            starts = [r for r in rows if r['event'] == 'operation_begin']
            self.assertEqual(sorted(r['job_id'] for r in starts if 'job_id' in r), [31, 32])
            self.assertEqual(len(starts), 5)
            self.assertEqual(len({r['thread_id'] for r in starts if 'job_id' in r}), 2)
            for first in starts:
                end = next(r for r in rows if r['event'] == 'operation_end' and r['operation_id'] == first['operation_id'])
                self.assertEqual(end.get('job_id'), first.get('job_id'))

    def test_real_update_force_and_metadata_success_keep_diagnostics(self):
        import fanficfare_bridge as bridge
        from upstream_core_helpers import StagedAdapter, config, write
        with tempfile.TemporaryDirectory() as root:
            source = Path(root, 'old.epub')
            source.write_bytes(write(StagedAdapter(config(), {1: '<p>ONE</p>'})))
            bridge.set_config_dir(root)
            self.addCleanup(bridge.set_config_dir, None)
            for operation, function in (('update', bridge.update_epub_from_path),
                                        ('force_download', bridge.force_download_from_epub)):
                adapter = StagedAdapter(config(), {1: '<p>ONE</p>', 2: '<p>TWO</p>'})
                with patch('fanficfare_config.build_configuration', return_value=adapter.configuration), patch(
                        'fanficfare.adapters.getAdapter', return_value=adapter), patch(
                        'socket.create_connection', side_effect=AssertionError('NETWORK FORBIDDEN')):
                    result = json.loads(function(str(source), root))
                self.assertTrue(result['ok'], result)
            adapter = StagedAdapter(config(), {1: '<p>ONE</p>'})
            with patch.object(bridge, 'build_configuration', return_value=adapter.configuration), patch(
                    'fanficfare.adapters.getAdapter', return_value=adapter):
                self.assertTrue(json.loads(bridge.get_metadata(adapter.url))['ok'])
            rows = [json.loads(line) for line in json.loads(bridge.read_download_debug())['log'].splitlines()]
            self.assertEqual([r['operation'] for r in rows if r['event'] == 'operation_end'],
                             ['update', 'force_download', 'metadata'])
            self.assertTrue(all(r['outcome'] == 'success' for r in rows if r['event'] == 'operation_end'))
            self.assertEqual(len([r for r in rows if r['event'] == 'chapter_end']), 4)
            self.assertEqual(len([r for r in rows if r['event'] == 'phase_begin' and r['phase'] == 'write']), 2)

    def test_disabled_failed_chapter_cannot_bleed_into_later_operation(self):
        import fanficfare_bridge as bridge
        from fanficfare import diagnostics
        from upstream_core_helpers import StagedAdapter, config
        adapter = StagedAdapter(config(continue_on_chapter_error='false'), {1: RuntimeError('PRIVATE_FAILURE')})
        bridge.set_config_dir(None)
        self.addCleanup(bridge.set_config_dir, None)
        with tempfile.TemporaryDirectory() as root:
            with patch.object(bridge, 'build_configuration', return_value=adapter.configuration), patch(
                    'fanficfare.adapters.getAdapter', return_value=adapter), patch.object(
                    diagnostics, 'versions', side_effect=AssertionError('disabled means no version/file probes')):
                self.assertFalse(json.loads(bridge.download_story(adapter.url, root))['ok'])
            self.assertEqual(diagnostics.read(), '')
            self.assertFalse(Path(root, 'diagnostics').exists())
            bridge.set_config_dir(root)
            with patch.object(bridge, '_import_fanficfare', return_value=False):
                bridge.get_metadata('https://example.invalid')
            rows = [json.loads(line) for line in diagnostics.read().splitlines()]
            self.assertTrue(rows)
            self.assertTrue(all('chapter_index' not in row for row in rows), rows)

    def test_download_update_force_have_scoped_failure_events_without_erasing_history(self):
        import fanficfare_bridge as bridge
        with tempfile.TemporaryDirectory() as root:
            bridge.set_config_dir(root)
            self.addCleanup(bridge.set_config_dir, None)
            with patch.object(bridge, 'build_configuration', side_effect=ValueError('password=TOPSECRET')), patch(
                    'fanficfare.epubutils.get_update_data', return_value=('', 0)), patch.object(
                    bridge, '_import_fanficfare', return_value=True):
                for name, function, target in (
                        ('download', bridge.download_story, 'https://example.invalid/story'),
                        ('update', bridge.update_epub_from_path, '/fixture.epub'),
                        ('force_download', bridge.force_download_from_epub, '/fixture.epub')):
                    bridge.set_download_diagnostic_context(json.dumps({'job_id': 12}))
                    result = json.loads(function(target, root))
                    self.assertFalse(result['ok'])
            rows = [json.loads(line) for line in json.loads(bridge.read_download_debug())['log'].splitlines()]
            self.assertEqual([r['operation'] for r in rows if r['event'] == 'operation_begin'],
                             ['download', 'update', 'force_download'])
            ends = [r for r in rows if r['event'] == 'operation_end']
            self.assertEqual(len(ends), 3)
            self.assertTrue(all(r['outcome'] == 'failed' for r in ends))
            self.assertNotIn('TOPSECRET', json.dumps(rows))
            self.assertTrue(any(r.get('stage') == 'EXCEPTION' for r in rows))

    def test_bridge_context_is_one_shot_and_operations_persist(self):
        import fanficfare_bridge as bridge
        self.assertTrue(callable(getattr(bridge, 'set_download_diagnostic_context', None)),
                        'bridge needs allowlisted thread-local diagnostic context')
        with tempfile.TemporaryDirectory() as root:
            bridge.set_config_dir(root)
            self.addCleanup(bridge.set_config_dir, None)
            context = {'job_id': 7, 'work_name': 'work-1', 'request_id': 'req-1',
                       'run_attempt': 2, 'operation': 'metadata', 'bridge_attempt': 3,
                       'password': 'NEVER_LOG_ME'}
            self.assertTrue(json.loads(bridge.set_download_diagnostic_context(json.dumps(context)))['ok'])
            with patch.object(bridge, '_import_fanficfare', return_value=False):
                bridge.get_metadata('https://user:secret@example.invalid/chapter?token=HIDDEN')
                bridge.get_metadata('https://example.invalid/second')
            log = json.loads(bridge.read_download_debug())['log']
            records = [json.loads(line) for line in log.splitlines()]
            starts = [row for row in records if row['event'] == 'operation_begin']
            ends = [row for row in records if row['event'] == 'operation_end']
            self.assertEqual(len(starts), 2)
            self.assertEqual(len(ends), 2)
            self.assertEqual(starts[0]['job_id'], 7)
            self.assertEqual(starts[0]['bridge_attempt'], 3)
            self.assertNotIn('job_id', starts[1])
            self.assertNotEqual(starts[0]['operation_id'], starts[1]['operation_id'])
            self.assertEqual(starts[0]['operation_id'], ends[0]['operation_id'])
            self.assertEqual(starts[0]['host'], 'example.invalid')
            self.assertEqual(starts[0]['path'], '/chapter')
            self.assertIn('T', starts[0]['timestamp'])
            self.assertTrue(starts[0]['timestamp'].endswith('Z'))
            self.assertIsInstance(starts[0]['pid'], int)
            self.assertIsInstance(starts[0]['thread_id'], int)
            self.assertIn('engine_version', starts[0])
            self.assertIn('urllib3_version', starts[0])
            self.assertGreaterEqual(ends[0]['duration_ms'], 0)
            self.assertEqual(ends[0]['outcome'], 'failed')
            for secret in ('NEVER_LOG_ME', 'secret', 'HIDDEN', 'user:', 'token='):
                self.assertNotIn(secret, log)
            self.assertTrue(Path(root, 'diagnostics', 'engine.jsonl').is_file())


if __name__ == '__main__':
    unittest.main()
