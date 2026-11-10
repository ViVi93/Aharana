"""Offline EPUB fixtures adapted from FanFicFare tests/adapters/update_harness.py.

Source: JimmXinu/FanFicFare a7d198ab6a31efc2418108061182210b76d67d98.
Copyright FanFicFare team; Apache-2.0, http://www.apache.org/licenses/LICENSE-2.0
Modified for Android: network-forbidden staged content, in-memory EPUB rewrites.
"""
import io
import re
import zipfile
from datetime import datetime
from pathlib import Path

from fanficfare.adapters.base_adapter import BaseSiteAdapter
from fanficfare.configurable import Configuration
from fanficfare.epubutils import get_update_data
from fanficfare.writers.writer_epub import EpubWriter

STORY_URL = 'http://example.com/story/12345-test-story'
CH = 'http://example.com/story/ch/%d'


def config(**settings):
    c = Configuration(['example.com'], 'epub', lightweight=True)
    c.read(str(Path(__file__).resolve().parents[1] / 'fanficfare/defaults.ini'))
    if not c.has_section('overrides'):
        c.add_section('overrides')
    for key, value in dict(include_images='false', **settings).items():
        c.set('overrides', key, str(value))
    return c


class StagedAdapter(BaseSiteAdapter):
    @classmethod
    def getSiteDomain(cls):
        return 'example.com'

    def __init__(self, configuration, content, titles=None):
        super().__init__(configuration, STORY_URL)
        self.content = {CH % n: value for n, value in content.items()}
        self.titles = titles or {}
        self.fetched = []

    def extractChapterUrlsAndMetadata(self):
        for key, value in dict(storyId='12345', title='Test Story', author='Test Author',
                               authorId='12345', authorUrl='http://example.com/user/12345',
                               langcode='en', publisher='example.com',
                               datePublished=datetime(2020, 1, 1),
                               dateUpdated=datetime(2020, 1, 1), status='In-Progress',
                               description='A test story.').items():
            self.story.setMetadata(key, value)
        for url in self.content:
            n = int(url.rsplit('/', 1)[1])
            self.add_chapter(self.titles.get(n, 'Chapter %d' % n), url)

    def get_request(self, *args, **kwargs):
        raise AssertionError('NETWORK FORBIDDEN')

    def getChapterText(self, url):
        # Use the real base getChapterTextNum Android retry wrapper.
        self.fetched.append(url)
        value = self.content[url]
        if isinstance(value, BaseException):
            raise value
        return value


def load_old(adapter, data):
    (adapter.oldchapters, adapter.oldimgs, adapter.oldcover,
     adapter.calibrebookmark, adapter.logfile, adapter.oldchaptersmap,
     adapter.oldchaptersdata) = get_update_data(io.BytesIO(data))[2:9]


def write(adapter):
    output = io.BytesIO()
    EpubWriter(adapter.configuration, adapter).writeStory(outstream=output)
    return output.getvalue()


def read(data):
    return get_update_data(io.BytesIO(data))


def chapter_html(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return [archive.read(name).decode('utf-8') for name in sorted(archive.namelist())
                if re.match(r'^OEBPS/file\d+\.xhtml$', name)]


def rewrite_chapters(data, transform):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(output, 'w') as target:
        for info in source.infolist():
            body = source.read(info.filename)
            if re.match(r'^OEBPS/file\d+\.xhtml$', info.filename):
                body = transform(body.decode('utf-8')).encode('utf-8')
            target.writestr(info, body)
    return output.getvalue()


def legacy(data):
    return rewrite_chapters(data, lambda text: re.sub(
        r'<meta name="fff_chapter_data"[^>]*>(?:</meta>)?', '', text))
