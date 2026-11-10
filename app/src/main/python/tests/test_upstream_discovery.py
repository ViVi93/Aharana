"""Offline discovery contracts for the pinned FanFicFare 4.63 API."""
import unittest

from fanficfare import geturls
from fanficfare.configurable import Configuration


class GetURLsDiscoveryTests(unittest.TestCase):
    def test_html_and_text_deduplicate_canonical_story_urls(self):
        discovery = geturls.GetURLs(Configuration(['archiveofourown.org'], 'epub', lightweight=True))
        url = 'https://archiveofourown.org/works/123'
        html = '<a href="/works/123">A</a><a href="/works/123/chapters/456">B</a>'
        self.assertEqual(discovery.get_urls_from_html(html, url, normalize=True), [url])
        self.assertEqual(discovery.get_urls_from_text(url + ' ' + url, normalize=True), [url])

    def test_site_hooks_accept_shared_discovery_context(self):
        from fanficfare import adapters
        from unittest.mock import patch

        real_get_adapter = adapters.getAdapter
        cases = [
            ('https://www.fimfiction.net/story/123/example', '<a href="/story/456/next">Next</a>', 'https://www.fimfiction.net/story/456/'),
            ('https://www.literotica.com/s/example', '<a href="https://www.literotica.com/s/next">Next</a>', 'https://www.literotica.com/s/next'),
            ('https://ncode.syosetu.com/n1234ab/', '<a href="https://ncode.syosetu.com/n5678ab/">Next</a>', 'https://ncode.syosetu.com/n5678ab/'),
            ('https://www.tthfanfic.org/Series-123', '<title>TtH • Series • Example</title><div class="storybody">Summary</div><div class="storylistitem"><a class="storylink" href="/Story-456">Next</a></div>', 'https://www.tthfanfic.org/Story-456'),
            ('https://archiveofourown.org/tags/example/works', '<a href="/works/456">Next</a>', 'https://archiveofourown.org/works/456'),
        ]
        for page, html, expected in cases:
            with self.subTest(page=page):
                config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
                config.add_section('overrides')
                config.set('overrides', 'fetch_stories_from_api', 'false')
                discovery = geturls.GetURLs(config)
                adapter = adapters.getAdapter(config, page, anyurl=True)
                with patch.object(adapters, 'getAdapter', return_value=adapter), \
                     patch.object(adapter, 'before_get_urls_from_page'), \
                     patch.object(adapter, 'get_request', return_value=html):
                    # Restore real adapter lookup while parsing the linked URLs.
                    original = discovery.get_urls_from_html
                    def parse(*args, **kwargs):
                        with patch.object(adapters, 'getAdapter', real_get_adapter):
                            return original(*args, **kwargs)
                    with patch.object(discovery, 'get_urls_from_html', side_effect=parse):
                        result = discovery.get_urls_from_page(page, normalize=True)
                self.assertEqual(result['urllist'], [expected])

    def test_ao3_series_returns_structured_metadata_per_work(self):
        from datetime import datetime
        from unittest.mock import patch
        from fanficfare import adapters

        config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
        discovery = geturls.GetURLs(config)
        url = 'https://archiveofourown.org/series/77'
        html = '''<div class="series-show"><h2 class="heading">Fixture series</h2></div>
          <div class="wrapper"><dl><dd><blockquote class="userstuff">Series summary</blockquote></dd></dl></div>
          <dl class="series meta"><dd><dl class="stats"><dt>Complete:</dt><dd>No</dd></dl></dd></dl>
          <ul class="series">%s%s</ul>''' % (ao3_list_work(123, 'First work'), ao3_list_work(456, 'Second work'))
        adapter = adapters.getAdapter(config, url, anyurl=True)
        with patch.object(adapters, 'getAdapter', return_value=adapter), \
             patch.object(adapter, 'get_request', return_value=html):
            result = discovery.get_urls_from_page(url)
        self.assertEqual(result['name'], 'Fixture series')
        self.assertEqual(result['urllist'], ['https://archiveofourown.org/works/123', 'https://archiveofourown.org/works/456'])
        self.assertEqual([d['title'] for d in result['storydata']], ['First work', 'Second work'])
        for url, data in zip(result['urllist'], result['storydata']):
            self.assertEqual(data['storyUrl'], url)
            self.assertEqual(data['author'], ['Fixture Author'])
            self.assertEqual(data['numWords'], '1,234')
            self.assertEqual(data['status'], 'Completed')
            self.assertEqual(data['seriesUrl'], 'https://archiveofourown.org/series/77')
            self.assertIsInstance(data['dateUpdated'], datetime)
        self.assertIsNot(result['storydata'][0], result['storydata'][1])

    def test_ao3_ordinary_work_uses_full_metadata_not_navigation_title(self):
        from datetime import datetime
        from unittest.mock import patch
        from fanficfare import adapters

        config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
        adapter = adapters.getAdapter(config, 'https://archiveofourown.org/works/123')
        navigation = '<a href="/works/123">Navigation title</a><a href="/works/123/chapters/111">One chapter</a>'
        metadata = '''<h2 class="title heading">Full &amp; Correct Title</h2>
            <h3 class="byline heading"><a rel="author" href="/users/fixture/pseuds/fixture">Fixture Author</a></h3>
            <blockquote class="userstuff"><p>Normal summary</p></blockquote>
            <dl class="work meta"><dd class="rating tags">General Audiences</dd><dd class="language">English</dd>
            <dd class="fandom tags"><a class="tag">Fixture Fandom</a></dd>
            <dd class="series">Part 2 of <a href="/series/77">Fixture series</a></dd></dl>
            <dl class="stats"><dt class="words">Words:</dt><dd class="words">1,234</dd>
            <dt class="chapters">Chapters:</dt><dd class="chapters">1/1</dd>
            <dt class="published">Published:</dt><dd class="published">2026-06-01</dd>
            <dt class="status">Completed:</dt><dd class="status">2026-07-10</dd>
            <dt class="kudos">Kudos:</dt><dd class="kudos">12</dd></dl>'''
        def response(url, **kwargs):
            return navigation if '/navigate' in url else metadata
        with patch.object(adapter, 'get_request', side_effect=response):
            adapter.extractChapterUrlsAndMetadata()
        raw = adapter.story.metadata
        self.assertEqual(raw['title'], 'Full &amp; Correct Title')
        self.assertEqual(raw['author'], ['Fixture Author'])
        self.assertEqual(raw['fandoms'], ['Fixture Fandom'])
        self.assertEqual(raw['status'], 'Completed')
        self.assertEqual(raw['numWords'], '1,234')
        self.assertEqual(raw['kudos'], '12')
        self.assertEqual(raw['datePublished'], datetime(2026, 6, 1))
        self.assertEqual(raw['dateUpdated'], datetime(2026, 7, 10))
        self.assertEqual(raw['seriesUrl'], 'https://archiveofourown.org/series/77')
        self.assertEqual(adapter.num_chapters(), 1)
        self.assertIn('Normal summary', raw['description'])

    def test_multiple_series_keep_each_position_in_metadata_and_epub(self):
        import io
        import zipfile
        from pathlib import Path
        from unittest.mock import patch
        from fanficfare import adapters, writers

        for markup in (
                '<dd class="series">Part 1 of <a href="/series/77">First Series</a>, '
                'Part 3 of <a href="/series/88">Second Series</a></dd>',
                '<ul class="series"><li>Part 1 of <a href="/series/77">First Series</a>, '
                'Part 3 of <a href="/series/88">Second Series</a></li></ul>'):
            with self.subTest(series_markup=markup):
                config = Configuration(['base_otw', 'archiveofourown.org'], 'epub', lightweight=True)
                config.read(str(Path(__file__).resolve().parents[1] / 'fanficfare/defaults.ini'))
                config.read_string('[overrides]\nextra_titlepage_entries:series01HTML\ninclude_images:false\n')
                adapter = adapters.getAdapter(config, 'https://archiveofourown.org/works/123')
                navigation = '<a href="/works/123">Title</a><a href="/works/123/chapters/111">One</a>'
                metadata = ('<h2 class="title heading">Title</h2>'
                            '<h3 class="byline heading"><a rel="author" '
                            'href="/users/fixture/pseuds/fixture">Author</a></h3>'
                            '<dl class="work meta">' + markup + '</dl>'
                            '<dl class="stats"><dd class="chapters">1/1</dd>'
                            '<dd class="published">2026-06-01</dd></dl>')
                output = io.BytesIO()
                with patch.object(adapter, 'get_request', side_effect=lambda url, **kw:
                                  navigation if '/navigate' in url else metadata), \
                     patch.object(adapter, 'getChapterTextNum', return_value='<p>Fixture chapter</p>'):
                    adapter.getStoryMetadataOnly()
                    self.assertEqual(adapter.story.getMetadataRaw('series00'), 'First Series [1]')
                    self.assertEqual(adapter.story.getMetadataRaw('series01'), 'Second Series [3]')
                    self.assertEqual(adapter.story.getMetadataRaw('series00Url'),
                                     'https://archiveofourown.org/series/77')
                    self.assertEqual(adapter.story.getMetadataRaw('series01Url'),
                                     'https://archiveofourown.org/series/88')
                    writers.getWriter('epub', config, adapter).writeStory(outstream=output)
                with zipfile.ZipFile(io.BytesIO(output.getvalue())) as book:
                    titlepage = book.read('OEBPS/title_page.xhtml').decode('utf-8')
                self.assertIn('Second Series [3]</a>', titlepage)
                self.assertNotIn('Second Series [1]', titlepage)

    def test_config_factory_bootstraps_without_seed_and_keeps_site_options(self):
        calls = []
        def factory(url):
            calls.append(url)
            config = Configuration(['archiveofourown.org'], 'epub', lightweight=True)
            config.read_string('[defaults]\nuser_agent: FixtureAgent\n[archiveofourown.org]\nis_adult: true\n')
            return config
        discovery = geturls.GetURLs(get_config=factory)
        root = discovery.configuration
        child = discovery.get_configuration('https://archiveofourown.org/works/123')
        self.assertEqual(calls, ['test1.com', 'https://archiveofourown.org/works/123'])
        self.assertEqual(child.getConfig('user_agent'), 'FixtureAgent')
        self.assertTrue(child.getConfig('is_adult'))
        self.assertIs(child.get_basic_cache(), root.get_basic_cache())
        self.assertIs(child.get_cookiejar(), root.get_cookiejar())

    def test_unknown_page_uses_configured_requestable_without_test_adapters(self):
        from unittest.mock import patch
        from fanficfare.requestable import Requestable
        config = Configuration(['test1.com'], 'epub', lightweight=True)
        config.read_string('[defaults]\nuser_agent: FixtureAgent\n')
        discovery = geturls.GetURLs(config)
        def fetch(requestable, url):
            self.assertIs(requestable.configuration, config)
            self.assertEqual(requestable.getConfig('user_agent'), 'FixtureAgent')
            self.assertEqual(url, 'https://fixture.invalid/list')
            return '<a href="https://archiveofourown.org/works/123">Work</a>'
        with patch.object(Requestable, 'get_request', fetch):
            self.assertEqual(discovery.get_urls_from_page('https://fixture.invalid/list'),
                             {'urllist': ['https://archiveofourown.org/works/123']})


def ao3_list_work(storyid, title):
    return '''<li class="work"><div class="header"><h4 class="heading"><a href="/works/%s">%s</a></h4>
        <a rel="author" href="/users/fixture/pseuds/fixture">Fixture Author</a></div>
        <p class="datetime">10 Jul 2026</p><blockquote class="userstuff">Work summary</blockquote>
        <ul class="series"><li>Part 2 of <a href="/series/77">Fixture series</a></li></ul>
        <dl class="stats"><dd class="words">1,234</dd><dd class="chapters">1/1</dd></dl></li>''' % (storyid, title)


if __name__ == '__main__':
    unittest.main()
