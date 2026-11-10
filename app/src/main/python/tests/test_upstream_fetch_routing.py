"""Offline request-routing tests; no site requests or browser dependencies."""
import unittest
from unittest.mock import patch

from fanficfare import adapters  # Initialize registry before configurable's legacy circular import.
from fanficfare.configurable import Configuration
from fanficfare.requestable import Requestable
from fanficfare.fetchers.base_fetcher import FetcherResponse
from fanficfare.fetchers.fetcher_requests import RequestsFetcher


class FetchRoutingTests(unittest.TestCase):
    def test_get_applies_url_modifier_once(self):
        config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
        seen = []

        class ModifiedRequest(Requestable):
            def mod_url_request(self, url):
                seen.append(url)
                return url + '?fixture=1'

        def request(fetcher, method, url, **kwargs):
            self.assertEqual(url, 'https://archiveofourown.org/works/123?fixture=1')
            return FetcherResponse(b'fixture', url, False)

        with patch.object(RequestsFetcher, 'request', request):
            self.assertEqual(ModifiedRequest(config).get_request('https://archiveofourown.org/works/123'), 'fixture')
        self.assertEqual(seen, ['https://archiveofourown.org/works/123'])

    def test_local_files_bypass_all_optional_network_fetchers(self):
        import os
        import tempfile
        from pathlib import Path

        for setting in ('use_nsapa_proxy', 'use_flaresolverr_proxy', 'use_cloudscraper', 'use_camoufox'):
            with self.subTest(setting=setting), tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as directory:
                path = Path(directory) / 'fixture.txt'
                path.write_bytes(b'local fixture')
                config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
                config.add_section('overrides')
                config.set('overrides', setting, 'true')
                requestable = Requestable(config)
                with patch('socket.socket.connect', side_effect=AssertionError('network forbidden')):
                    self.assertEqual(requestable.get_request(path.as_uri()), 'local fixture')
                    self.assertEqual(requestable.get_request_raw(path.as_uri(), image=True), b'local fixture')
                    self.assertEqual(config._read_file_opener(path.as_uri()), 'local fixture')
                self.assertIs(config.get_fetcher(path.as_uri()), config.get_file_fetcher())
                self.assertIsNone(config.fetcher)

    def test_camoufox_selection_is_explicitly_unsupported_without_calibre_import(self):
        import builtins
        from fanficfare import exceptions

        original_import = builtins.__import__

        def guarded_import(name, *args, **kwargs):
            if name == 'calibre' or name.startswith('calibre.'):
                self.fail('unsupported selection tried to import Calibre')
            return original_import(name, *args, **kwargs)

        for value in ('true', 'directimages'):
            with self.subTest(value=value):
                config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
                config.add_section('overrides')
                config.set('overrides', 'use_camoufox', value)
                with patch('builtins.__import__', guarded_import):
                    with self.assertRaisesRegex(exceptions.FailedToDownload, 'Camoufox.*not supported.*Android'):
                        config.get_fetcher()

    def test_camoufox_modules_are_import_safe_and_direct_selection_is_disabled(self):
        import importlib
        from fanficfare import exceptions

        browser = importlib.import_module('fanficfare.fetchers.camoufoxbrowser')
        fetcher = importlib.import_module('fanficfare.fetchers.fetcher_camoufox')
        config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
        for construct in (lambda: browser.CamoufoxBrowserSingleton(False),
                          lambda: fetcher.CamoufoxFetcher(config.getConfig, config.getConfigList)):
            with self.assertRaisesRegex(exceptions.FailedToDownload, 'Camoufox.*not supported.*Android'):
                construct()

    def test_nsapa_malformed_base64_reports_fanficfare_error(self):
        from unittest.mock import MagicMock
        from fanficfare import exceptions
        from fanficfare.fetchers.fetcher_nsapa_proxy import NSAPA_ProxyFetcher

        config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
        proxy = NSAPA_ProxyFetcher(config.getConfig, config.getConfigList)
        sock = MagicMock()
        sock.recv.return_value = b'1||text-b64$END_OF_HEADER$a'
        with patch('fanficfare.fetchers.fetcher_nsapa_proxy.socket.socket', return_value=sock):
            with self.assertRaisesRegex(exceptions.FailedToDownload, 'base64 decoding failed'):
                proxy.proxy_request('https://fixture.invalid/')
        sock.close.assert_called_once()

    def test_quit_decorator_is_inert_unless_explicitly_registered(self):
        from fanficfare import exceptions

        config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
        response = FetcherResponse(b'fixture', 'https://fixture.invalid/', False)
        with patch.object(RequestsFetcher, 'request', return_value=response) as request:
            self.assertEqual(Requestable(config).get_request('https://fixture.invalid/'), 'fixture')
            config.set_quit(lambda: True)
            with self.assertRaises(exceptions.QuitDownloadException):
                Requestable(config).get_request('https://fixture.invalid/')
            request.assert_called_once()

    def test_validation_is_safe_when_configuration_is_imported_first(self):
        import os
        import subprocess
        import sys
        for module in ('fanficfare.configurable', 'fanficfare.requestable', 'fanficfare.story'):
            with self.subTest(first_import=module):
                code = ("import " + module + "; "
                        "from fanficfare.configurable import Configuration; "
                        "cfg=Configuration(['archiveofourown.org'],'epub',lightweight=True); "
                        "assert cfg.test_config() == []")
                result = subprocess.run([sys.executable, '-c', code], capture_output=True,
                                        text=True, timeout=20, env=os.environ.copy())
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_new_configuration_options_validate_values_and_site_scope(self):
        from fanficfare.configurable import get_valid_keywords

        config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
        config.read_string('[defaults]\nuse_camoufox: invalid\ndo_camoufox_warmup: invalid\n'
                           '[fanfiction.net]\nuse_series_page_metadata: true\n')
        messages = '\n'.join(message for line, message in config.test_config())
        self.assertIn('invalid not a valid value for use_camoufox', messages)
        self.assertIn('invalid not a valid value for do_camoufox_warmup', messages)
        self.assertIn('use_series_page_metadata not valid in section [fanfiction.net]', messages)
        self.assertIn('first_chapter', get_valid_keywords())
        self.assertIn('last_chapter', get_valid_keywords())

    def test_supported_fetchers_keep_cookies_and_basic_cache_on_recreation(self):
        from requests.cookies import create_cookie
        from fanficfare.fetchers import BasicCache
        from fanficfare.fetchers.fetcher_cloudscraper import CloudScraperFetcher
        from fanficfare.fetchers.fetcher_nsapa_proxy import NSAPA_ProxyFetcher
        from fanficfare.fetchers.fetcher_flaresolverr_proxy import FlareSolverr_ProxyFetcher

        for setting, cls in ((None, RequestsFetcher), ('use_cloudscraper', CloudScraperFetcher),
                             ('use_nsapa_proxy', NSAPA_ProxyFetcher), ('use_flaresolverr_proxy', FlareSolverr_ProxyFetcher)):
            with self.subTest(setting=setting):
                config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
                config.read_string('[defaults]\nuse_basic_cache: true\n')
                if setting:
                    config.set('defaults', setting, 'true')
                first_fetcher = config.get_fetcher()
                self.assertIsInstance(first_fetcher, cls)
                jar = config.get_cookiejar()
                jar.set_cookie(create_cookie('fixture', 'value', domain='fixture.invalid'))
                url = 'https://fixture.invalid/'
                with patch.object(cls, 'request', return_value=FetcherResponse(b'fixture', url, False)) as request:
                    self.assertEqual(Requestable(config).get_request(url), 'fixture')
                    self.assertEqual(Requestable(config).get_request(url), 'fixture')
                    request.assert_called_once()
                    replacement = BasicCache()
                    config.set_basic_cache(replacement)
                    self.assertIsNot(config.get_fetcher(), first_fetcher)
                    self.assertIs(config.get_basic_cache(), replacement)
                    self.assertIs(config.get_cookiejar(), jar)
                    self.assertEqual([cookie.value for cookie in jar], ['value'])
                    self.assertEqual(Requestable(config).get_request(url), 'fixture')
                    self.assertEqual(request.call_count, 2)

    def test_direct_image_fetcher_still_bypasses_proxy_and_browser_cache(self):
        from fanficfare.story import Story

        for setting in ('use_flaresolverr_proxy', 'use_browser_cache'):
            with self.subTest(setting=setting):
                config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
                config.read_string('[defaults]\n%s: directimages\n' % setting)
                story = Story(config)
                url = 'https://fixture.invalid/cover.jpg'
                def request(fetcher, method, actual_url, **kwargs):
                    self.assertIs(type(fetcher), RequestsFetcher)
                    self.assertEqual(actual_url, url)
                    return FetcherResponse(b'fixture image', url, False)
                with patch.object(RequestsFetcher, 'request', request):
                    self.assertEqual(story.direct_fetcher(url, image=True), b'fixture image')
                self.assertIsNone(config.fetcher)


if __name__ == '__main__':
    unittest.main()
