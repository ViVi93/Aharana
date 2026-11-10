"""Real GetURLs + AO3 series parsing, with only network I/O replaced."""
import json
import socket
from datetime import datetime

import pytest

import fanficfare_bridge as bridge
import fanficfare_config
from fanficfare.adapters.base_otw_adapter import BaseOTWAdapter
from fanficfare.geturls import GetURLs


DISCOVERY_SERIES = 'https://archiveofourown.org/series/123'
DISCOVERY_WORK = 'https://archiveofourown.org/works/456'
DISCOVERY_HTML = '''<html><body>
<div class="series-show"><h2 class="heading">A Fixture Series</h2></div>
<div class="wrapper"><dl><dd><blockquote class="userstuff"><p>Series <b>description</b>.</p></blockquote></dd></dl></div>
<dl class="series"><dd><dl class="stats"><dt>Complete:</dt><dd>Yes</dd></dl></dd></dl>
<ul class="series"><li class="work"><div class="header">
<h4 class="heading"><a href="/works/456">A Fixture Work</a></h4>
<a rel="author" href="/users/Fixture/pseuds/Fixture">Fixture</a>
<p class="datetime">10 Jul 2026</p></div>
<dl class="stats"><dd class="words">123</dd><dd class="chapters">1/1</dd></dl>
</li><li class="work"><div class="header">
<h4 class="heading"><a href="/works/456">A Fixture Work</a></h4>
<p class="datetime">10 Jul 2026</p></div>
<dl class="stats"><dd class="chapters">1/1</dd></dl></li></ul>
</body></html>'''


@pytest.fixture
def discovery_offline(monkeypatch, tmp_path):
    def deny(*args, **kwargs):
        pytest.fail('Discovery fixture attempted network access')
    monkeypatch.setattr(socket.socket, 'connect', deny)
    monkeypatch.setattr(socket, 'getaddrinfo', deny)
    monkeypatch.setattr(BaseOTWAdapter, 'get_request', lambda *args, **kwargs: DISCOVERY_HTML)
    monkeypatch.setattr(fanficfare_config, '_ANDROID_CONFIG_DIR', str(tmp_path))
    config = fanficfare_config.build_configuration(DISCOVERY_SERIES, 'epub')
    config.set('overrides', 'is_adult', 'false')
    config.set('overrides', 'username', '')
    monkeypatch.setattr(fanficfare_config, 'build_configuration', lambda *args, **kwargs: config)
    monkeypatch.setattr(bridge, 'build_configuration', lambda *args, **kwargs: config)
    return config


def test_list_series_omits_raw_dates_and_stringifies_description(discovery_offline):
    result = json.loads(bridge.list_story_urls(DISCOVERY_SERIES))
    assert result['ok'], result
    from fanficfare import adapters
    adapter = adapters.getAdapter(discovery_offline, DISCOVERY_SERIES, anyurl=True)
    raw = adapter.get_series_from_page(DISCOVERY_SERIES, DISCOVERY_HTML)
    assert isinstance(raw['storydata'][0]['dateUpdated'], datetime)
    assert not isinstance(raw['desc'], str)
    assert result['urllist'] == [DISCOVERY_WORK]
    assert result['duplicates_removed'] == 1
    assert result['name'] == 'A Fixture Series'
    assert '<b>description</b>' in result['desc']
    assert 'storydata' not in result
    assert set(result) <= {'ok', 'name', 'desc', 'status', 'urllist', 'duplicates_removed'}


def test_download_list_uses_real_series_and_dedupes_before_metadata(discovery_offline, monkeypatch):
    calls = []
    def metadata(url):
        calls.append(url)
        return json.dumps({'ok': True, 'title': 'Fetched title', 'chapters': 1})
    monkeypatch.setattr(bridge, 'get_metadata', metadata)
    result = json.loads(bridge.download_story_list(DISCOVERY_SERIES))
    assert result['ok'], result
    assert calls == [DISCOVERY_WORK]
    assert result['count'] == 1 and result['errors'] == 0
    assert result['stories'] == [{'url': DISCOVERY_WORK, 'title': 'Fetched title', 'chapters': 1}]
    assert result['name'] == 'A Fixture Series'
    assert '<b>description</b>' in result['desc']
    assert result['urllist'] == [DISCOVERY_WORK]
    assert 'storydata' not in result


@pytest.mark.parametrize('api', ['list_story_urls', 'download_story_list'])
def test_both_lists_pass_site_configuration_callback_and_share_session(discovery_offline, monkeypatch, api):
    from fanficfare.configurable import Configuration
    import fanficfare.geturls as geturls_module
    created = []
    builds = []
    original_init = GetURLs.__init__
    def capture(self, *args, **kwargs):
        original_init(self, *args, **kwargs)
        created.append(self)
    def build(url, fileform):
        builds.append((url, fileform))
        if url == DISCOVERY_SERIES:
            return discovery_offline
        config = Configuration(['royalroad.com'], fileform, lightweight=True)
        config.add_section('overrides')
        config.set('overrides', 'username', 'fixture-user-no-secret')
        return config
    monkeypatch.setattr(geturls_module.GetURLs, '__init__', capture)
    monkeypatch.setattr(fanficfare_config, 'build_configuration', build)
    monkeypatch.setattr(bridge, 'get_metadata', lambda url: json.dumps({'ok': True, 'title': 'Fixture', 'chapters': 1}))
    result = json.loads(getattr(bridge, api)(DISCOVERY_SERIES))
    assert result['ok'], result
    assert len(created) == 1
    getter = created[0]
    site_url = 'https://www.royalroad.com/fiction/123'
    derived = getter.get_configuration(site_url)
    assert builds == [(DISCOVERY_SERIES, 'epub'), (site_url, 'epub')]
    assert derived.getConfig('username') == 'fixture-user-no-secret'
    assert derived.get_cookiejar() is discovery_offline.get_cookiejar()
    assert derived.get_basic_cache() is discovery_offline.get_basic_cache()
