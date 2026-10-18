# -*- coding: utf-8 -*-
__license__ = 'GPL v3'
"""Original-site metadata preview and selective, body-preserving EPUB refresh.

No chapter downloader or EPUB writer is used. Applying a preview is offline.
"""
import base64
import datetime
import hashlib
import json
import os
import posixpath
import copy
import shutil
import tempfile
import threading
import re
import zipfile
import xml.etree.ElementTree as ET

from urllib.parse import urlsplit, unquote

import epub_editor
from fanficfare_config import build_configuration

LABELS = {'title': 'Title', 'authors': 'Authors', 'description': 'Description',
          'tags': 'Tags', 'languages': 'Languages', 'series': 'Series',
          'series_index': 'Series index', 'pubdate': 'Publication date',
          'status': 'Status', 'word_count': 'Word count', 'date_updated': 'Updated date'}
CUSTOM = {key: 'aharana:story:' + key for key in ('status', 'word_count', 'date_updated')}


def _online_metadata(story):
    result = {}
    for key, source in [('title', 'title'), ('description', 'description'),
                        ('series', 'series'), ('series_index', 'seriesIndex'),
                        ('status', 'status')]:
        value = story.getMetadata(source)
        if value is not None and str(value).strip():
            result[key] = str(value).strip()
    for key, value in [('authors', story.getList('author')),
                       ('languages', story.getList('langcode')),
                       ('tags', story.getSubjectTags())]:
        if value:
            result[key] = value
    value = story.getMetadata('numWords')
    if value is not None:
        value = str(value).replace(',', '').strip()
        if re.fullmatch(r'[0-9]{1,15}', value):
            result['word_count'] = str(int(value))
    for key, source in [('pubdate', 'datePublished'), ('date_updated', 'dateUpdated')]:
        value = story.getMetadataRaw(source)
        if isinstance(value, (datetime.date, datetime.datetime)):
            result[key] = value.isoformat()
    series = result.get('series', '')
    match = re.fullmatch(r'(.+) \[([0-9]+(?:\.[0-9]+)?)\]', series)
    if match:
        result['series'], result['series_index'] = match.groups()
    return result


def _description_values(value):
    """Return comparison tokens and readable text, never a replacement HTML value.

    Class attributes and plain span/div wrappers are cosmetic. Block boundaries,
    emphasis, links (including exact destinations), and other attributes survive in
    comparison tokens. Raw HTML is retained separately for an explicitly chosen write.
    """
    from bs4 import BeautifulSoup, Comment, NavigableString
    soup = BeautifulSoup(value or '', 'html.parser')
    tokens, display = [], []
    blocks = {'div', 'p', 'section', 'article', 'blockquote', 'li', 'ul', 'ol',
              'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'pre', 'hr'}

    def walk(node, pre=False):
        if isinstance(node, Comment):
            return
        if isinstance(node, NavigableString):
            raw = str(node)
            # Blank lines in saved plain descriptions denote paragraphs, even
            # inside a cosmetic wrapper. Indentation-only HTML nodes do not.
            parts = re.split(r'\n[ \t\r]*\n', raw) if not pre and raw.strip() else [raw]
            for index, part in enumerate(parts):
                if index:
                    tokens.append(('paragraph',))
                    display.append('\n\n')
                text = part if pre else re.sub(r'\s+', ' ', part)
                tokens.append(('pretext' if pre else 'text', text))
                display.append(text)
            return
        name = node.name
        block = name in blocks
        if block:
            tokens.append(('paragraph',))
            display.append('\n\n')
        attrs = tuple(sorted((key, tuple(val) if isinstance(val, list) else val)
                             for key, val in node.attrs.items() if key != 'class'))
        semantic = name not in ('[document]', 'div', 'span', 'p') or bool(attrs)
        if semantic:
            tokens.append(('open', {'b': 'strong', 'i': 'em'}.get(name, name), attrs))
        if name == 'br':
            display.append('\n')
        for child in node.children:
            walk(child, pre or name == 'pre')
        if semantic:
            tokens.append(('close', {'b': 'strong', 'i': 'em'}.get(name, name)))
        if block:
            tokens.append(('paragraph',))
            display.append('\n\n')

    walk(soup)
    normalized = []
    for token in tokens:
        if token[0] == 'text':
            if normalized and normalized[-1][0] == 'text':
                normalized[-1] = ('text', normalized[-1][1] + token[1])
            else:
                normalized.append(token)
        elif token != ('paragraph',) or not normalized or normalized[-1] != token:
            normalized.append(token)
    # Ignore outer block wrappers, and layout-only whitespace between paragraphs.
    cleaned = []
    for index, token in enumerate(normalized):
        if token[0] == 'text':
            text = re.sub(r'\s+', ' ', token[1])
            if index == 0 or normalized[index - 1] == ('paragraph',):
                text = text.lstrip()
            if index == len(normalized) - 1 or normalized[index + 1] == ('paragraph',):
                text = text.rstrip()
            if not text:
                continue
            token = ('text', text)
        if token == ('paragraph',) and (not cleaned or cleaned[-1] == token):
            continue
        cleaned.append(token)
    while cleaned and cleaned[-1] == ('paragraph',):
        cleaned.pop()
    readable = re.sub(r'[ \t\f\v]+', ' ', ''.join(display))
    readable = re.sub(r' *\n *', '\n', readable).strip()
    readable = re.sub(r'\n{3,}', '\n\n', readable)
    return tuple(cleaned), readable


def _date_comparison_value(value):
    """Only date-only and timezone-naive midnight have equivalent precision.

    Non-midnight times and every timezone offset remain meaningful; no calendar-day
    truncation or timezone conversion is performed. Original values are written as-is.
    """
    if not isinstance(value, str):
        return value
    try:
        parsed = datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        return value
    if parsed.tzinfo is None and parsed.time() == datetime.time():
        return parsed.date().isoformat()
    return parsed.isoformat()


def _metadata_equal(key, current, online):
    if key == 'description':
        return _description_values(current)[0] == _description_values(online)[0]
    if key in ('pubdate', 'date_updated'):
        return _date_comparison_value(current) == _date_comparison_value(online)
    return current == online


def _preview_field(key, current, online):
    result = {'key': key, 'label': LABELS[key], 'current': current, 'online': online,
              'changed': not _metadata_equal(key, current, online), 'selected': False}
    if key == 'description':
        result.update(display_current=_description_values(current)[1],
                      display_online=_description_values(online)[1])
    return result


def _image_policy(config, include_cover):
    # Operation policy must beat URL/site sections and additive/filelist options.
    # Copy no credentials and expose no configuration values in results.
    for section in config.sections():
        for key in ('include_images_filelist', 'add_to_include_images',
                    'add_to_include_images_filelist'):
            config.remove_option(section, key)
    if not config.has_section('overrides'):
        config.add_section('overrides')
    config.set('overrides', 'include_images', 'coveronly' if include_cover else 'false')
    config.reset_cached_config()


def _cover_preview(story, enabled):
    from cover_manager import MAX_DOWNLOAD_BYTES, validate_image_content
    result = {'available': False, 'image_data': '', 'mime_type': '', 'width': 0, 'height': 0}
    if not enabled or not story.cover:
        return result
    try:
        # getImgUrls is safe in coveronly mode: never invokes additional_images.
        info = next((i for i in story.getImgUrls() if i.get('newsrc') == story.cover), None)
        if info is None:
            return result
        data = info.get('data')
        if not isinstance(data, bytes) or len(data) > MAX_DOWNLOAD_BYTES:
            raise RefreshError('Cover exceeds the safe size limit.')
        dimensions = validate_image_content(data)
        if not dimensions['width'] or not dimensions['height']:
            raise RefreshError('Cover dimensions could not be validated.')
        result.update(dimensions, available=True, image_data=base64.b64encode(data).decode('ascii'))
    except Exception:
        result['error'] = 'The site cover could not be validated.'
    return result


def _sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


class RefreshError(ValueError):
    """A fixed, user-visible validation message (never upstream exception text)."""


def _failure(exc):
    message = str(exc) if isinstance(exc, RefreshError) else 'Story refresh failed.'
    return {'ok': False, 'error': message, 'exception_type': type(exc).__name__,
            'detail': message if isinstance(exc, RefreshError) else
            'The site or EPUB could not be processed. Check the source and site configuration.'}


def _source_url(root):
    sources = root.findall('.//{%s}source' % epub_editor.DC_NS)
    if sources:
        source = sources[0].text or ''
    else:
        source = next((e.text for e in root.findall('.//{%s}identifier' % epub_editor.DC_NS)
                       if (e.text or '').startswith(('http://', 'https://'))), '')
    try:
        parts = urlsplit(source)
        decoded = unquote(source)
        if (not source or len(source) > 4096 or source != source.strip() or
                any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in decoded) or
                '\\' in decoded or parts.scheme not in ('http', 'https') or
                not parts.hostname or parts.username is not None or parts.password is not None or
                (parts.port is not None and not 0 < parts.port < 65536) or
                not re.fullmatch(r'[A-Za-z0-9.-]+', parts.hostname)):
            raise ValueError()
    except ValueError:
        raise RefreshError('EPUB has no valid original-site source URL.') from None
    return source


def preview_story_refresh(epub_path, include_cover=False):
    """Return an unchecked diff; failures never expose personal.ini or site secrets."""
    try:
        return _preview_story_refresh(epub_path, include_cover)
    except Exception as exc:
        return _failure(exc)


def _publication_dates(meta):
    dates = meta.findall('{%s}date' % epub_editor.DC_NS)
    explicit = [e for e in dates if e.get('{%s}event' % epub_editor.OPF_NS, e.get('event')) == 'publication']
    return explicit or [e for e in dates if not e.get('{%s}event' % epub_editor.OPF_NS, e.get('event'))]


def _current_metadata(path, root):
    result = epub_editor.read_metadata_fields(path)
    if not result.get('ok'):
        raise RefreshError('EPUB metadata could not be read.')
    fields = result['metadata']
    meta = epub_editor._ensure_metadata_element(root)
    dates = _publication_dates(meta)
    fields['pubdate'] = (dates[0].text or '').strip() if dates else ''
    for key, name in CUSTOM.items():
        fields[key] = next((e.get('content', '') for e in meta if e.get('name') == name), '')
    # Reuse the same offline interpretation as Book Detail for pre-refresh EPUBs.
    from story_metadata import read_story_details
    details = read_story_details(path)
    if not details['ok']:
        raise RefreshError('EPUB story details could not be read.')
    for key in ('status', 'date_updated'):
        fields[key] = details[key]
    return fields


def _write_metadata(root, fields):
    standard = {key: value for key, value in fields.items() if key not in CUSTOM and key != 'pubdate'}
    epub_editor._apply_metadata_to_opf(root, standard)
    meta = epub_editor._ensure_metadata_element(root)
    if 'pubdate' in fields:
        dates = _publication_dates(meta)
        if dates:
            elem = dates[0]
            for duplicate in dates[1:]:
                meta.remove(duplicate)
        else:
            elem = ET.SubElement(meta, '{%s}date' % epub_editor.DC_NS,
                                 {'{%s}event' % epub_editor.OPF_NS: 'publication'})
        elem.text = fields['pubdate']
    for key, name in CUSTOM.items():
        if key in fields:
            matches = [e for e in meta if e.get('name') == name]
            for elem in matches:
                meta.remove(elem)
            ET.SubElement(meta, '{%s}meta' % epub_editor.OPF_NS,
                          {'name': name, 'content': fields[key]})
    # The shared editor gives every new author id="creator". Make only the
    # replaced creators unique, without touching IDs on unrelated elements.
    if 'authors' in fields:
        creators = meta.findall('{%s}creator' % epub_editor.DC_NS)
        used = {e.get('id') for e in root.iter() if e not in creators and e.get('id')}
        for creator in creators:
            ident, number = 'creator', 1
            while ident in used:
                ident = 'creator-%d' % number
                number += 1
            creator.set('id', ident)
            used.add(ident)


_APPLY_LOCK = threading.RLock()


def _atomic_write(source, target, replacements, additions, expected_hash):
    """Stream unchanged resources; stage and fsync before an atomic install."""
    temp_path = None
    backup_path = None
    try:
        fd, temp_path = tempfile.mkstemp(prefix='.story-refresh-', suffix='.epub.tmp', dir=os.path.dirname(target))
        os.close(fd)
        with zipfile.ZipFile(source) as zin, zipfile.ZipFile(temp_path, 'w') as zout:
            zout.comment = zin.comment
            original_entries = zin.infolist()
            # Keep mimetype first/uncompressed, then add the new resources.
            # Existing resource bytes are never replaced or removed by ordering.
            for info in [i for i in original_entries if i.filename == 'mimetype']:
                zout.writestr(copy.copy(info), zin.read(info))
            for name, data in additions.items():
                zout.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
            for info in original_entries:
                if info.filename == 'mimetype':
                    continue
                if info.filename in replacements:
                    zout.writestr(copy.copy(info), replacements[info.filename])
                else:
                    with zin.open(info) as src, zout.open(copy.copy(info), 'w') as dst:
                        shutil.copyfileobj(src, dst, 1024 * 1024)
        with open(temp_path, 'rb') as stream:
            os.fsync(stream.fileno())
        if source == target:
            fd, backup_path = tempfile.mkstemp(prefix='.story-refresh-backup-', suffix='.epub.bak', dir=os.path.dirname(source))
            with os.fdopen(fd, 'wb') as backup, open(source, 'rb') as original:
                shutil.copyfileobj(original, backup, 1024 * 1024)
                backup.flush()
                os.fsync(backup.fileno())
        if ((backup_path and _sha256(backup_path) != expected_hash) or
                _sha256(source) != expected_hash):
            raise RefreshError('The EPUB changed since preview. Fetch a new preview.')
        if source == target:
            os.replace(temp_path, target)
        else:
            # link() publishes a complete inode and fails atomically if occupied.
            os.link(temp_path, target)
            os.unlink(temp_path)
        temp_path = None
        return backup_path
    except Exception:
        if backup_path and os.path.exists(backup_path):
            os.unlink(backup_path)
        raise
    finally:
        if temp_path and os.path.exists(temp_path):
            os.unlink(temp_path)


def apply_story_refresh(epub_path, preview_json, selected_fields_json='[]',
                        apply_cover=False, output_path=None):
    """Apply explicitly selected preview fields offline; return a safe result dict."""
    try:
        with _APPLY_LOCK:
            return _apply_story_refresh(epub_path, preview_json, selected_fields_json, apply_cover, output_path)
    except Exception as exc:
        return _failure(exc)


def _validated_cover(cover):
    from cover_manager import MAX_DOWNLOAD_BYTES, validate_image_content
    if not isinstance(cover, dict) or cover.get('available') is not True:
        raise RefreshError('No validated cover is available. Fetch a cover preview first.')
    encoded = cover.get('image_data')
    if not isinstance(encoded, str) or len(encoded) > ((MAX_DOWNLOAD_BYTES + 2) // 3) * 4:
        raise RefreshError('Cover is missing or exceeds the safe size limit.')
    try:
        data = base64.b64decode(encoded, validate=True)
        if len(data) > MAX_DOWNLOAD_BYTES:
            raise ValueError()
        info = validate_image_content(data)
        if (not info['width'] or not info['height'] or
                any(cover.get(key) != info[key] for key in ('mime_type', 'width', 'height'))):
            raise ValueError()
    except Exception:
        raise RefreshError('Cover content, dimensions or MIME type is invalid.') from None
    return data, info['mime_type']


def _zip_href(base, href):
    parts = urlsplit(href)
    if parts.scheme or parts.netloc or parts.query or '\\' in href:
        raise RefreshError('EPUB contains an unsafe cover reference.')
    path = unquote(parts.path)
    result = posixpath.normpath(posixpath.join(posixpath.dirname(base), path))
    if path.startswith('/') or result.startswith('../') or result == '..':
        raise RefreshError('EPUB contains an unsafe cover reference.')
    return result


def _apply_cover(root, opf_name, z, data, mime):
    """Add a new image, retaining the old resource AND its manifest entry."""
    import epub_xml
    ns = '{%s}' % epub_editor.OPF_NS
    manifest = root.find(ns + 'manifest')
    if manifest is None:
        raise RefreshError('EPUB has no manifest for a cover.')
    old_id = epub_editor._find_manifest_cover_item_id(root)
    old_item = next((e for e in manifest if e.get('id') == old_id), None)
    old_path = _zip_href(opf_name, old_item.get('href', '')) if old_item is not None else None
    extension = {'image/png': '.png', 'image/jpeg': '.jpg', 'image/gif': '.gif', 'image/webp': '.webp'}[mime]
    occupied = set(z.namelist()) | {_zip_href(opf_name, e.get('href', '')) for e in manifest}
    ids = {e.get('id') for e in root.iter() if e.get('id')}
    number = 1
    while True:
        ident = 'aharana-refresh-cover-%d' % number
        href = 'images/cover-refresh-%d%s' % (number, extension)
        path = _zip_href(opf_name, href)
        if path not in occupied and ident not in ids:
            break
        number += 1
    for item in manifest:
        props = item.get('properties', '').split()
        if 'cover-image' in props:
            props.remove('cover-image')
            if props:
                item.set('properties', ' '.join(props))
            else:
                item.attrib.pop('properties', None)
    ET.SubElement(manifest, ns + 'item', {'id': ident, 'href': href,
                  'media-type': mime, 'properties': 'cover-image'})
    meta = epub_editor._ensure_metadata_element(root)
    for elem in list(meta):
        if elem.get('name') == 'cover':
            meta.remove(elem)
    ET.SubElement(meta, ns + 'meta', {'name': 'cover', 'content': ident})
    replacements = {}
    # Only an explicit guide cover reference or epub:type=cover establishes
    # a cover page. Never infer one from a first chapter/first image.
    cover_pages = set()
    for ref in root.findall('.//' + ns + 'reference'):
        if ref.get('type') == 'cover':
            candidate = _zip_href(opf_name, ref.get('href', ''))
            if candidate == old_path:
                ref.set('href', href)
            else:
                cover_pages.add(candidate)
    for item in manifest:
        if item.get('media-type') != 'application/xhtml+xml':
            continue
        candidate = _zip_href(opf_name, item.get('href', ''))
        # Title pages are immutable even if an incorrect guide marks them cover.
        basename = posixpath.basename(candidate).lower()
        if basename in ('title_page.xhtml', 'titlepage.xhtml', 'title.xhtml'):
            continue
        raw = z.read(candidate)
        if len(raw) > 4 * 1024 * 1024:
            raise RefreshError('Cover page exceeds the safe size limit.')
        if candidate not in cover_pages and b'cover' not in raw:
            continue
        try:
            page = ET.fromstring(raw)
        except ET.ParseError:
            if candidate in cover_pages:
                raise RefreshError('Cover page is not safe XML; no changes were applied.') from None
            continue
        semantic_cover = any('cover' in e.get('{%s}type' % epub_xml.OPS_NS, '').split() for e in page.iter())
        if candidate not in cover_pages and not semantic_cover:
            continue
        body = next((e for e in page.iter() if epub_xml.localname(e.tag) == 'body'), None)
        # Refuse to rewrite a guide mislabelled as cover if it has narrative.
        if body is None or ''.join(body.itertext()).strip().lower() not in ('', 'cover'):
            continue
        changed = False
        for elem in body.iter():
            local = epub_xml.localname(elem.tag)
            attrs = ('src',) if local == 'img' else ('href', '{%s}href' % epub_xml.XLINK_NS) if local == 'image' else ()
            for attr in attrs:
                value = elem.get(attr)
                if value and _zip_href(candidate, value) == old_path:
                    elem.set(attr, posixpath.relpath(path, posixpath.dirname(candidate)))
                    changed = True
        if changed:
            header, footer = epub_xml.split_prolog(raw)
            replacements[candidate] = (epub_xml.prolog_for_utf8(header) +
                                       epub_xml.serialize(page, epub_xml.XHTML, xml_declaration=False) + footer)
    return replacements, {path: data}


def _bounded_json(value, maximum):
    try:
        raw = value if isinstance(value, str) else json.dumps(value, allow_nan=False)
        if len(raw) > maximum or len(raw.encode('utf-8')) > maximum:
            raise ValueError()
        result = json.loads(raw, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError, TypeError, RecursionError, UnicodeError):
        raise RefreshError('Invalid or oversized refresh JSON.') from None
    return result


def _validate_online(online):
    if not isinstance(online, dict) or set(online) - set(LABELS):
        raise RefreshError('Preview contains unknown or missing metadata fields.')
    for key, value in online.items():
        values = value if key in ('authors', 'tags', 'languages') else [value]
        if not isinstance(values, list) or not values or len(values) > 512:
            raise RefreshError('Preview contains invalid metadata values.')
        for text in values:
            if (not isinstance(text, str) or not text.strip() or text != text.strip() or
                    len(text) > (200000 if key == 'description' else 8192) or
                    any(not (c in '\t\n\r' or '\x20' <= c <= '\ud7ff' or
                             '\ue000' <= c <= '\ufffd' or '\U00010000' <= c <= '\U0010ffff') for c in text)):
                raise RefreshError('Preview contains invalid metadata values.')
        if key == 'word_count' and not re.fullmatch(r'[0-9]{1,15}', value):
            raise RefreshError('Preview contains an invalid word count.')
        if key == 'series_index' and not re.fullmatch(r'[0-9]{1,12}(?:\.[0-9]{1,8})?', value):
            raise RefreshError('Preview contains an invalid series index.')
        if key in ('pubdate', 'date_updated'):
            try:
                if not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T[0-9:.]+(?:Z|[+-][0-9]{2}:[0-9]{2})?)?', value):
                    raise ValueError()
                datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))
            except ValueError:
                raise RefreshError('Preview contains an invalid ISO date.') from None


def _apply_story_refresh(epub_path, preview_json, selected_fields_json, apply_cover, output_path):
    if type(apply_cover) is not bool:
        raise RefreshError('Cover selection must be a boolean.')
    source = os.path.abspath(epub_path)
    target = os.path.abspath(output_path) if output_path else source
    if os.path.islink(source) or (target != source and os.path.lexists(target)):
        raise RefreshError('Output already exists or source is a symbolic link.')
    preview = _bounded_json(preview_json, 8 * 1024 * 1024)
    selected = _bounded_json(selected_fields_json, 4096)
    if not isinstance(preview, dict) or preview.get('ok') is not True:
        raise RefreshError('A successful preview is required.')
    digest = preview.get('source_sha256')
    if not isinstance(digest, str) or not re.fullmatch(r'[0-9a-f]{64}', digest) or _sha256(source) != digest:
        raise RefreshError('The EPUB changed since preview. Fetch a new preview.')
    online = preview.get('online')
    _validate_online(online)
    if (not isinstance(selected, list) or len(selected) > len(LABELS) or
            any(not isinstance(key, str) or key not in online for key in selected) or
            len(set(selected)) != len(selected)):
        raise RefreshError('Select only available, supported metadata fields.')
    with zipfile.ZipFile(source) as z:
        opf_name = epub_editor._find_opf_path(z)
        root = ET.fromstring(z.read(opf_name))
    if _source_url(root) != preview.get('source_url'):
        raise RefreshError('Preview does not match the original-site source URL.')
    current = _current_metadata(source, root)
    fields = {key: online[key] for key in selected
              if not _metadata_equal(key, current.get(key), online[key])}
    if not fields and not apply_cover:
        raise RefreshError('No changed metadata or cover selected.')
    _write_metadata(root, fields)
    import epub_xml
    replacements, additions = {}, {}
    if apply_cover:
        image, mime = _validated_cover(preview.get('cover'))
        with zipfile.ZipFile(source) as z:
            replacements, additions = _apply_cover(root, opf_name, z, image, mime)
    replacements[opf_name] = epub_xml.serialize(root)
    backup = _atomic_write(source, target, replacements, additions, preview['source_sha256'])
    return {'ok': True, 'output_path': target, 'backup_path': backup,
            'metadata_written': bool(fields), 'cover_written': apply_cover,
            'changed_fields': list(fields), 'metadata': _current_metadata(target, root),
            'source_sha256': preview['source_sha256'], 'chapters_preserved': True}


def _preview_story_refresh(epub_path, include_cover=False):
    """Return an unchecked diff of metadata fetched from the embedded source URL."""
    from fanficfare import adapters
    if type(include_cover) is not bool:
        raise RefreshError('Cover preview selection must be a boolean.')
    source_hash = _sha256(epub_path)
    with zipfile.ZipFile(epub_path) as z:
        root = ET.fromstring(z.read(epub_editor._find_opf_path(z)))
    current = _current_metadata(epub_path, root)
    source = _source_url(root)
    config = build_configuration(source, 'epub', {'include_images': 'false'})
    _image_policy(config, include_cover)
    adapter = adapters.getAdapter(config, source)
    story = adapter.getStoryMetadataOnly(get_cover=include_cover)
    online = _online_metadata(story)
    cover = _cover_preview(story, include_cover)
    if _sha256(epub_path) != source_hash:
        raise RefreshError('The EPUB changed during preview. Fetch a new preview.')
    return {'ok': True, 'source_url': source, 'source_sha256': source_hash,
            'current': current, 'online': online,
            'fields': [_preview_field(key, current.get(key), value)
                       for key, value in online.items()],
            'cover': cover, 'warnings': ['Chapter content and source-site title page text are not rewritten.']}
