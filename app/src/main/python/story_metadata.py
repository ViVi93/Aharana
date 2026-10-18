# -*- coding: utf-8 -*-
__license__ = 'GPL v3'
"""Offline, read-only story details from modern metadata or exact saved FFF tags.

No source URL, adapters, config, network, filesystem dates, or EPUB writes are used.
Unknown and conflicting values are deliberately left blank, independently per field.
"""
import datetime
import re
import zipfile
import xml.etree.ElementTree as ET

import epub_editor

# Exact values emitted by bundled adapters (base_otw_adapter, fanfiktionde,
# royalroadcom, storiesonlinenet). Do not infer status from prose or genre labels.
STATUSES = frozenset(('Completed', 'In-Progress', 'Paused', 'Cancelled', 'Hiatus',
                      'Stub', 'Dropped', 'Inactive', 'Incomplete'))


def _unique(values, validator):
    """All candidates must validate and agree; invalid/conflicting is unknown."""
    converted = [validator(value) for value in values]
    return converted[0] if converted and all(converted) and len(set(converted)) == 1 else ''


def _tag_date(value):
    match = re.fullmatch(r'Last Update: ([0-9]{4})/([0-9]{2})/([0-9]{2})', value)
    if match:
        try:
            return datetime.date(*(int(part) for part in match.groups())).isoformat()
        except ValueError:
            pass
    return ''


def _iso_date(value):
    if not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\.[0-9]+)?)?(?:Z|[+-][0-9]{2}:[0-9]{2})?)?', value):
        return ''
    try:
        datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))
        return value
    except ValueError:
        return ''


def _explicit_status(value):
    # Explicit app metadata is authoritative even for an adapter-specific status.
    return value if len(value) <= 256 and not any(ord(c) < 32 for c in value) else ''


def read_story_details(epub_path):
    """Return {ok, status, date_updated, status_source, date_updated_source}.

    A readable EPUB with no known details is still successful. Failure messages are
    fixed strings and do not expose arbitrary archive/path or XML exception content.
    """
    result = {'ok': True, 'status': '', 'date_updated': '',
              'status_source': 'unknown', 'date_updated_source': 'unknown'}
    try:
        with zipfile.ZipFile(epub_path, 'r') as archive:
            opf_path = epub_editor._find_opf_path(archive)
            root = ET.fromstring(archive.read(opf_path))
        meta = next((e for e in root if e.tag.rsplit('}', 1)[-1] == 'metadata'), None)
        if meta is None:
            return result
        tags = [(e.text or '').strip() for e in meta if e.tag == '{%s}subject' % epub_editor.DC_NS]
        status = _unique([tag for tag in tags if tag in STATUSES], lambda value: value)
        updated = _unique([tag for tag in tags if tag.startswith('Last Update:')], _tag_date)
        for key, value in (('status', status), ('date_updated', updated)):
            name = 'aharana:story:' + key
            explicit = [(e.get('content') if e.get('content') is not None else e.text or '').strip()
                        for e in meta if e.tag.rsplit('}', 1)[-1] == 'meta'
                        and not e.get('refines')
                        and (e.get('name') == name or e.get('property') == name)]
            explicit = [text for text in explicit if text]
            if explicit:
                value = _unique(explicit, _iso_date if key == 'date_updated' else _explicit_status)
                source = 'metadata'
            else:
                source = 'tags'
                # FFF EPUB2 writes dateUpdated as dc:date event=modification.
                # EPUB3 dcterms:modified is an edit timestamp, NEVER story data.
                # Use this fallback only when no full-date tag candidate exists
                # and the producer/version establish the bundled writer's meaning.
                if (key == 'date_updated' and not any(tag.startswith('Last Update:') for tag in tags)
                        and root.get('version') == '2.0'
                        and any(e.tag == '{%s}contributor' % epub_editor.DC_NS
                                and (e.text or '').strip() ==
                                'FanFicFare [https://github.com/JimmXinu/FanFicFare]' for e in meta)):
                    dates = [(e.text or '').strip() for e in meta
                             if e.tag == '{%s}date' % epub_editor.DC_NS
                             and e.get('{%s}event' % epub_editor.OPF_NS, e.get('event')) == 'modification']
                    value = _unique(dates, _iso_date)
                    source = 'metadata'
            if value:
                result[key], result[key + '_source'] = value, source
        return result
    except Exception:
        result.update(ok=False, error='EPUB story details could not be read.')
        return result
