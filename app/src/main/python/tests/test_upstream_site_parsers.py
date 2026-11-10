"""Offline adapter regressions for the pinned FanFicFare v4.63 port.

RR/SoFurry fixture shapes adapted from the local read-only audit script
fff-adapter-audit-probe.py; fanfics.me size links from fff-adapter-extra-probe.py
(2026-11-03 audit). These are hand-built markup fixtures, not live responses.
The actual adapter methods are executed; only HTTP/cover I/O is intercepted.
"""
import json
import socket
from datetime import datetime, timezone

import pytest

from fanficfare import exceptions
from fanficfare.configurable import Configuration
from fanficfare.adapters.adapter_royalroadcom import RoyalRoadAdapter
from fanficfare.adapters.adapter_sofurrycom import SoFurryComAdapter
from fanficfare.adapters.adapter_fanficsme import FanFicsMeAdapter


@pytest.fixture(autouse=True)
def block_network(monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError('Offline adapter fixtures must not connect to the network')
    monkeypatch.setattr(socket.socket, 'connect', forbidden)
    monkeypatch.setattr(socket, 'create_connection', forbidden)


@pytest.mark.parametrize('word_link_first', [False, True])
def test_fanficsme_word_count_uses_word_link_not_page_count(monkeypatch, word_link_first):
    adapter = FanFicsMeAdapter(Configuration(['fanfics.me'], 'epub', lightweight=True),
                               'https://fanfics.me/fic123')
    pages = '<a onclick="pftake(properties_item_value=1)">100 страниц</a>'
    words = '<a onclick="pftake(properties_item_value=2)">193 676 слов</a>'
    links = words + pages if word_link_first else pages + words
    data = ('<div class="FicHead"><h1>Fixture <span>(гет)</span></h1>'
            '<div>Рейтинг:</div><div>General</div>'
            '<div>Автор:</div><div><a class="user" href="/user1">Author</a></div>'
            '<div>Жанр:</div><div>Adventure</div>'
            '<div>Размер:</div><div id="FicSize">' + links + '</div>'
            '<div>Статус:</div><div><span class="green">Completed</span></div>'
            '</div><div id="summary_123">Summary</div>'
            '<span class="DateUpdate">01.01.2026 - 01.10.2026</span>')
    monkeypatch.setattr(adapter, 'get_request', lambda *args, **kwargs: data)
    adapter.extractChapterUrlsAndMetadata()
    assert adapter.story.getMetadataRaw('numWords') == '193676'
    assert adapter.story.getMetadataRaw('title') == 'Fixture'
    assert adapter.num_chapters() == 1


@pytest.mark.parametrize('success_markup', [True, False])
def test_sofurry_login_checks_new_success_markup_with_intercepted_post(monkeypatch, success_markup):
    adapter = SoFurryComAdapter(Configuration(['sofurry.com'], 'epub', lightweight=True),
                               'https://sofurry.com/s/abcd')
    adapter.username = 'fixture@example.invalid'
    adapter.password = 'offline-fixture-only'
    requests = []

    def get(url, **kwargs):
        requests.append(('GET', url, kwargs))
        return '<meta name="csrf-token" content="fixture-token">'

    def post(url, params, **kwargs):
        requests.append(('POST', url, dict(params), kwargs))
        return ('<button aria-label="Upload content">Upload</button>' if success_markup
                else '<form>Sign in</form>')

    monkeypatch.setattr(adapter, 'get_request', get)
    monkeypatch.setattr(adapter, 'post_request', post)
    if success_markup:
        adapter.performLogin(adapter.url)
    else:
        with pytest.raises(exceptions.FailedToLogin):
            adapter.performLogin(adapter.url)
    assert requests == [
        ('GET', 'https://sofurry.com/fe/auth/sofurry', {'usecache': False}),
        ('POST', 'https://sofurry.com/login',
         {'_token': 'fixture-token', 'email': 'fixture@example.invalid',
          'password': 'offline-fixture-only'}, {'usecache': False}),
    ]


@pytest.mark.parametrize('layout', ['legacy', 'redesign'])
@pytest.mark.parametrize('rated', [False, True])
def test_rr_unrated_and_chapter_url_normalization(monkeypatch, layout, rated):
    adapter = parse_rr(monkeypatch, layout, [rr_chapter(1, '2026-01-01T00:00:00Z')], rated=rated)
    assert adapter.story.metadata.get('stars') == ('4.5' if rated else None)
    assert adapter.normalize_chapterurl('https://www.royalroad.com/fiction/chapter/1') == adapter.get_chapter(0, 'url')
    assert adapter.normalize_chapterurl('https://www.royalroad.com/fiction/chapter/999') == 'https://www.royalroad.com/fiction/chapter/999'


def test_rr_legacy_description_no_longer_requires_hidden_content(monkeypatch):
    adapter = parse_rr(monkeypatch, 'legacy', [rr_chapter(1, '2026-01-01T00:00:00Z')], hidden=False)
    assert 'Legacy summary' in adapter.story.getMetadataRaw('description')


def rr_chapter(chapter_id, date, *, unlocked=True, tiers=None):
    return dict(id=chapter_id, title=f"Chapter {chapter_id}",
                url=f"/fiction/123/test/chapter/{chapter_id}/chapter-{chapter_id}",
                date=date, isUnlocked=unlocked, subscriptionTiers=tiers)


def parse_rr(monkeypatch, layout, chapters, *, rated=True, hidden=True,
             modified="2026-01-01T00:00:00", published="2026-01-01T00:00:00"):
    adapter = RoyalRoadAdapter(
        Configuration(['www.royalroad.com'], 'epub', lightweight=True),
        'https://www.royalroad.com/fiction/123/test')
    story = dict(name='Fixture', author=dict(name='Author',
                 url='https://www.royalroad.com/user/profile/1'),
                 description='Redesign summary', dateModified=modified,
                 datePublished=published)
    rating = '<meta property="books:rating:value" content="4.5">' if rated else ''
    switch = '<a href="/home/reverttolegacyui">Legacy UI</a>' if layout == 'redesign' else ''
    descclass = ' class="hidden-content"' if hidden else ''
    data = (rating + switch + '<meta property="books:author" content="Author">'
            '<script type="application/ld+json">' + json.dumps(story) + '</script>'
            '<div id="chapterHeroData"></div>'
            '<div class="row fic-header"><h1>Fixture</h1>'
            '<img src="https://example.invalid/cover.jpg"></div>'
            '<div class="mt-card-social"><a href="/user/profile/1">Author</a></div>'
            '<div class="description"><div' + descclass + '>Legacy summary</div></div>'
            '<table id="chapters"><tbody><tr><td>Only visible page</td>'
            '<td><time unixtime="1780272000">Jun 1</time></td></tr></tbody></table>\n'
            'window.chapters = ' + json.dumps(chapters) + ';\n')
    monkeypatch.setattr(adapter, 'get_request', lambda *args, **kwargs: data)
    monkeypatch.setattr(adapter, 'setCoverImage', lambda *args: ('ok', None))
    adapter.extractChapterUrlsAndMetadata()
    return adapter


@pytest.mark.parametrize('layout', ['legacy', 'redesign'])
def test_rr_public_chapters_use_subscription_tiers_not_unlock_flag(monkeypatch, layout):
    adapter = parse_rr(monkeypatch, layout, [
        rr_chapter(1, '2026-01-01T00:00:00Z', unlocked=False),
        rr_chapter(2, '2026-10-01T00:00:00Z', tiers=[]),
        rr_chapter(3, '2026-11-01T00:00:00Z', tiers=[1]),
    ])
    assert adapter.num_chapters() == 2
    assert [adapter.get_chapter(i, 'title') for i in range(2)] == ['Chapter 1', 'Chapter 2']
    assert adapter.chapterURLIndex == {'1': 0, '2': 1}


@pytest.mark.parametrize('layout', ['legacy', 'redesign'])
def test_rr_dates_use_all_public_chapters_not_visible_page_or_stale_json(monkeypatch, layout):
    adapter = parse_rr(monkeypatch, layout, [
        rr_chapter(2, '2026-10-01T00:00:00Z'),
        rr_chapter(1, '2026-01-01T00:00:00Z'),
        rr_chapter(3, '2026-11-01T00:00:00Z', tiers=[1]),
    ])
    assert adapter.story.getMetadataRaw('dateUpdated') == datetime(2026, 10, 1, tzinfo=timezone.utc)
    assert adapter.story.getMetadataRaw('datePublished').replace(tzinfo=None) == datetime(2026, 1, 1)
    assert adapter.get_chapter(0, 'date') == '01/10/2026 00:00:00 AM'


@pytest.mark.parametrize('layout', ['legacy', 'redesign'])
@pytest.mark.parametrize('chapter_dates', [
    ['2026-01-01T00:00:00', '2026-10-01T00:00:00Z'],
    ['2026-10-01T00:00:00Z', '2026-01-01T00:00:00'],
    ['2026-01-01T00:00:00', '2026-10-01T00:00:00'],
    ['2026-01-01T00:00:00Z', '2026-10-01T02:00:00+02:00'],
])
def test_rr_dates_normalize_mixed_naive_and_aware_to_utc(monkeypatch, layout, chapter_dates):
    adapter = parse_rr(monkeypatch, layout, [
        rr_chapter(i, value) for i, value in enumerate(chapter_dates, 1)
    ], modified='2026-01-01T00:00:00Z')
    assert adapter.story.getMetadataRaw('dateUpdated') == datetime(2026, 10, 1, tzinfo=timezone.utc)
    if layout == 'legacy':
        assert adapter.story.getMetadataRaw('datePublished') == datetime(2026, 1, 1, tzinfo=timezone.utc)


@pytest.mark.parametrize('layout', ['legacy', 'redesign'])
def test_rr_all_subscription_chapters_are_excluded(monkeypatch, layout):
    chapters = [rr_chapter(1, '2026-11-01T00:00:00Z', tiers=[1])]
    if layout == 'redesign':
        with pytest.raises(exceptions.FailedToDownload, match='Story has no chapters'):
            parse_rr(monkeypatch, layout, chapters)
    else:
        # Retain final upstream legacy behavior; the caller's zero-chapter gate
        # must reject this result before replacing a local book.
        adapter = parse_rr(monkeypatch, layout, chapters)
        assert adapter.num_chapters() == 0
        assert adapter.chapterURLIndex == {}
        assert adapter.story.getMetadataRaw('dateUpdated') is None
        assert adapter.story.getMetadataRaw('datePublished') is None


def test_rr_redesign_keeps_newer_json_modified_date_and_description(monkeypatch):
    adapter = parse_rr(monkeypatch, 'redesign', [
        rr_chapter(1, '2026-10-01T00:00:00Z'),
    ], modified='2026-11-01T02:00:00+02:00', published='2026-01-01T00:00:00Z')
    assert adapter.story.getMetadataRaw('dateUpdated') == datetime(2026, 11, 1, tzinfo=timezone.utc)
    assert adapter.story.getMetadataRaw('datePublished') == datetime(2026, 1, 1, tzinfo=timezone.utc)
    assert adapter.story.getMetadataRaw('description') == 'Redesign summary'


def test_sofurry_login_rechecks_success_markup_after_post_404(monkeypatch):
    adapter = SoFurryComAdapter(Configuration(['sofurry.com'], 'epub', lightweight=True),
                               'https://sofurry.com/s/abcd')
    adapter.username = 'fixture@example.invalid'
    adapter.password = 'offline-fixture-only'
    gets = []

    def get(url, **kwargs):
        gets.append((url, kwargs))
        return ('<meta name="csrf-token" content="fixture-token">' if len(gets) == 1
                else '<button aria-label="Upload content">Upload</button>')

    def post(url, params, **kwargs):
        assert url == 'https://sofurry.com/login'
        assert params['_token'] == 'fixture-token'
        raise exceptions.HTTPErrorFFF(url, 404, 'Offline fixture redirect')

    monkeypatch.setattr(adapter, 'get_request', get)
    monkeypatch.setattr(adapter, 'post_request', post)
    adapter.performLogin(adapter.url)
    assert gets == [('https://sofurry.com/fe/auth/sofurry', {'usecache': False})] * 2
