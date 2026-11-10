"""Android-specific safeguards retained across upstream engine migrations.

These tests exercise configuration and request boundaries without live networking.
"""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import requests
from requests.exceptions import ChunkedEncodingError
from requests.cookies import RequestsCookieJar

from fanficfare import adapters
from fanficfare.configurable import Configuration
from fanficfare.exceptions import HTTPErrorFFF
from fanficfare.fetchers.fetcher_requests import RequestsFetcher
import fanficfare_config


class AndroidPatchIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.config_patch = patch.object(fanficfare_config, '_ANDROID_CONFIG_DIR', self.folder.name)
        self.config_patch.start()
        self.addCleanup(self.config_patch.stop)

    def configuration(self, url='https://www.fanfiction.net/s/12345/1/Test'):
        return fanficfare_config.build_configuration(url, 'epub')

    def fetcher(self):
        config = self.configuration()
        return RequestsFetcher(config.getConfig, config.getConfigList)

    def test_embedded_engine_reports_pinned_463_release(self):
        from fanficfare import cli
        self.assertEqual(cli.version, '4.63.0')
        self.assertEqual(fanficfare_config._get_fanficfare_version(), '4.63.0')

    def test_royalroad_default_request_delay_is_five_seconds(self):
        config = self.configuration('https://www.royalroad.com/fiction/12345')
        self.assertEqual(float(config.getConfig('slow_down_sleep_time') or 0), 5)

    def test_royalroad_personal_delay_still_overrides_default(self):
        Path(self.folder.name, 'personal.ini').write_text(
            '[www.royalroad.com]\nslow_down_sleep_time:7\n', encoding='utf-8')
        config = self.configuration('https://www.royalroad.com/fiction/12345')
        self.assertEqual(float(config.getConfig('slow_down_sleep_time')), 7)

    def test_preservation_and_recent_checks_are_not_globally_enabled(self):
        config = self.configuration()
        self.assertFalse(config.getConfig('update_preserve_deleted_chapters'))
        self.assertEqual(int(config.getConfig('update_check_recent_chapters') or 0), 0)

    def test_request_retry_backoff_remains_bounded(self):
        fetcher = self.fetcher()
        self.assertEqual(fetcher.retries.backoff_max, 10)
        if hasattr(fetcher.retries, 'retry_after_max'):
            self.assertEqual(fetcher.retries.retry_after_max, 60)
        self.assertIn(503, fetcher.retries.status_forcelist)

    def test_images_keep_the_separate_short_retry_policy(self):
        fetcher = self.fetcher()
        self.assertEqual(fetcher.image_retries.total, 1)
        self.assertEqual(fetcher.image_retries.backoff_max, 2)
        self.assertEqual(fetcher.image_retries.allowed_methods, {'GET'})
        self.assertIsNot(fetcher.get_image_session(), fetcher.get_requests_session())

    def test_image_host_failure_is_not_paid_again(self):
        fetcher = self.fetcher()
        session = Mock()
        session.request.side_effect = requests.exceptions.ConnectionError('fixture dead host')
        with patch.object(fetcher, 'get_image_session', return_value=session):
            for name in ('one.png', 'two.png'):
                with self.assertRaises(requests.exceptions.ConnectionError):
                    fetcher.request('GET', 'https://dead.example.invalid/' + name,
                                    headers={'Accept': 'image/*'})
        self.assertEqual(session.request.call_count, 1)
        self.assertIn('dead.example.invalid', fetcher.dead_image_hosts)

    def test_cookiejar_reaches_both_image_and_page_sessions(self):
        fetcher = self.fetcher()
        page = fetcher.get_requests_session()
        image = fetcher.get_image_session()
        jar = RequestsCookieJar()
        fetcher.set_cookiejar(jar)
        self.assertIs(page.cookies, jar)
        self.assertIs(image.cookies, jar)

    def test_transient_chapter_retry_survives_core_migration(self):
        adapter = adapters.getAdapter(self.configuration(),
                                      'https://www.fanfiction.net/s/12345/1/Test')
        with patch.object(adapter, 'getChapterText',
                          side_effect=[ChunkedEncodingError('fixture drop'), '<p>recovered</p>']) as get:
            self.assertEqual(adapter.getChapterTextNum('https://example.invalid/chapter', 0),
                             '<p>recovered</p>')
        self.assertEqual(get.call_count, 2)

    def test_busy_chapter_retry_survives_core_migration(self):
        adapter = adapters.getAdapter(self.configuration(),
                                      'https://www.fanfiction.net/s/12345/1/Test')
        with patch.object(adapter, 'getChapterText', side_effect=[
                HTTPErrorFFF('https://example.invalid/chapter', 503, 'fixture busy', None),
                '<p>recovered</p>']) as get, patch(
                    'fanficfare.adapters.base_adapter.time.sleep') as sleep:
            self.assertEqual(adapter.getChapterTextNum('https://example.invalid/chapter', 0),
                             '<p>recovered</p>')
        self.assertEqual(get.call_count, 2)
        sleep.assert_called_once_with(5)


if __name__ == '__main__':
    unittest.main()
