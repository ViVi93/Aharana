# -*- coding: utf-8 -*-
__license__ = 'GPL v3'
"""Offline, read-only library facets from saved EPUB metadata.

No adapters, configuration, source URL normalization, or EPUB writes are used.
"""
import zipfile
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit

import epub_editor
import story_metadata


_ERROR = {'ok': False, 'error': 'Saved book filters could not be read.'}


def _values(values):
    """Trim empty values and deduplicate in saved order, never split names/tags."""
    return list(dict.fromkeys(value.strip() for value in values
                              if isinstance(value, str) and value.strip()))


def _site(epub_path):
    with zipfile.ZipFile(epub_path, 'r') as archive:
        opf_path = epub_editor._find_opf_path(archive)
        if not opf_path:
            raise ValueError('Missing OPF')
        root = ET.fromstring(archive.read(opf_path))
    meta = epub_editor._get_all_metadata_elements(root)
    if meta is None:
        raise ValueError('Missing metadata')
    sources = _values(e.text or '' for e in meta.findall('.//{%s}source' % epub_editor.DC_NS))
    url = sources[0] if sources else ''
    if not url:
        identifiers = _values(e.text or '' for e in meta.findall('.//{%s}identifier' % epub_editor.DC_NS))
        url = next((value for value in identifiers
                    if value.lower().startswith(('http://', 'https://'))), '')
    try:
        host = (urlsplit(url).hostname or '').lower()
        return host[4:] if host.startswith('www.') else host
    except ValueError:
        return ''


def get_library_facets(epub_path):
    """Return {ok, authors, status, languages, series, tags, site} as a dict.

    Lists retain whole saved values in first-seen order. A readable source-less
    book is valid; unknown facets are empty. Errors never expose paths or XML.
    """
    try:
        fields = epub_editor.read_metadata_fields(epub_path)
        details = story_metadata.read_story_details(epub_path)
        if not fields.get('ok') or not details.get('ok'):
            return dict(_ERROR)
        metadata = fields['metadata']
        return {'ok': True,
                'authors': _values(metadata.get('authors', [])),
                'status': details.get('status', ''),
                'languages': _values(metadata.get('languages', [])),
                'series': _values([metadata.get('series', '')]),
                'tags': _values(metadata.get('tags', [])),
                'site': _site(epub_path)}
    except Exception:
        return dict(_ERROR)
