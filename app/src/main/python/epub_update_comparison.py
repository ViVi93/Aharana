"""Conservative no-op proof for the bundled FanFicFare EPUB writer.

Do not scrub dates from book text. Recover the previous packaging clock from a
writer-shaped title-page entry, then replay the *loaded* story with that clock.
Every story/template/CSS/image byte must still match. The real candidate is never
modified, so genuine changes retain the new packaging time.
"""
import io
import re
import string
import zipfile
import xml.etree.ElementTree as ET
from datetime import datetime

OPF = '{http://www.idpf.org/2007/opf}'
DC = '{http://purl.org/dc/elements/1.1/}'


def _previous_packaging_time(archive, writer):
    """Recognize the active writer's exact dateCreated entry, not arbitrary dates."""
    table = writer.getConfig('titlepage_use_table')
    if table:
        template = writer.EPUB_TABLE_TITLE_ENTRY
        no_title = writer.EPUB_TABLE_NO_TITLE_ENTRY
        if 'dateCreated' in writer.getConfigList('wide_titlepage_entries'):
            template = writer.EPUB_TABLE_TITLE_WIDE_ENTRY
            option = 'titlepage_wide_entry'
        else:
            option = 'titlepage_entry'
    else:
        template = writer.EPUB_TITLE_ENTRY
        no_title = writer.EPUB_NO_TITLE_ENTRY
        option = 'titlepage_entry'
    if writer.hasConfig(option):
        template = string.Template(writer.getConfig(option))
    label = writer.get_label('dateCreated')
    if not label:
        template = (string.Template(writer.getConfig('titlepage_no_title_entry'))
                    if writer.hasConfig('titlepage_no_title_entry') else no_title)
    marker = '__FFF_PACKAGING_CLOCK__'
    fragment = template.substitute(label=label, id='dateCreated', value=marker)
    if fragment.count(marker) != 1:
        return None
    pattern = re.escape(fragment).replace(marker, r'(?P<clock>[^<>\r\n]+)')
    fmt = writer.getConfig('dateCreated_format', '%Y-%m-%d %H:%M:%S')
    if 'OEBPS/title_page.xhtml' in archive.namelist():
        page = archive.read('OEBPS/title_page.xhtml').decode('utf-8')
        matches = list(re.finditer(pattern, page))
        if len(matches) == 1:
            value = matches[0]['clock']
            clock = datetime.strptime(value, fmt)
            # strptime accepts non-canonical forms; require writer round-trip.
            return clock if clock.strftime(fmt) == value else None
    if writer.story.logfile:
        value = writer._getLastLogData(writer.story.logfile).get('dateCreated')
        if value:
            clock = datetime.strptime(value, fmt)
            return clock if clock.strftime(fmt) == value else None
    # EPUB2 records only the day in OPF. That is sufficient when the full
    # packaging time is not rendered elsewhere; otherwise replay won't match.
    root = ET.fromstring(archive.read('content.opf'))
    dates = root.findall(f'{OPF}metadata/{DC}date')
    dates = [node for node in dates if node.attrib == {OPF + 'event': 'creation'}]
    return datetime.strptime(dates[0].text or '', '%Y-%m-%d') if len(dates) == 1 else None


def _comparable_opf(payload, previous=None, current=None):
    """Stabilize only the writer's direct OPF packaging-clock properties."""
    root = ET.fromstring(payload)
    metadata = root.find(OPF + 'metadata')
    if metadata is None:
        return payload
    if root.get('version') == '3.0':
        clocks = [node for node in metadata if node.tag == OPF + 'meta'
                  and node.attrib == {'property': 'dcterms:modified'} and not len(node)]
        if len(clocks) != 1 or (clocks[0].tail and not clocks[0].tail.isspace()):
            return payload
        datetime.strptime(clocks[0].text or '', '%Y-%m-%dT%H:%M:%SZ')
        metadata.remove(clocks[0])
    elif previous is not None and current is not None:
        dates = [node for node in metadata if node.tag == DC + 'date'
                 and node.attrib == {OPF + 'event': 'creation'} and not len(node)]
        if len(dates) == 1 and dates[0].text == current.strftime('%Y-%m-%d'):
            dates[0].text = previous.strftime('%Y-%m-%d')
    return ET.tostring(root)


def _render_page(writer, clock, log=False):
    """Replay metadata pages only: full serialization can re-fetch extra images."""
    current = writer.story.getMetadataRaw('dateCreated')
    try:
        writer.story.setMetadata('dateCreated', clock)
        out = io.BytesIO()
        if log:
            writer.writeLogPage(out)
        else:
            # Use the same default template selection as EpubWriter, then let
            # BaseStoryWriter apply all active user templates/labels/settings.
            table = writer.getConfig('titlepage_use_table')
            prefix = 'EPUB_TABLE_' if table else 'EPUB_'
            writer.writeTitlePage(
                out=out, START=getattr(writer, prefix + 'TITLE_PAGE_START'),
                ENTRY=getattr(writer, prefix + 'TITLE_ENTRY'),
                END=getattr(writer, prefix + 'TITLE_PAGE_END'),
                WIDE_ENTRY=writer.EPUB_TABLE_TITLE_WIDE_ENTRY if table else None,
                NO_TITLE_ENTRY=getattr(writer, prefix + 'NO_TITLE_ENTRY'))
        return out.getvalue()
    finally:
        writer.story.setMetadata('dateCreated', current)


def _same_payloads(original, replay, writer, previous):
    current = writer.story.getMetadataRaw('dateCreated')
    with zipfile.ZipFile(original) as old, zipfile.ZipFile(replay) as new:
        if old.namelist() != new.namelist():
            return False
        for item in old.infolist():
            left, right = old.read(item), new.read(item.filename)
            if left == right:
                continue
            if item.filename == 'content.opf':
                if _comparable_opf(left) == _comparable_opf(right, previous, current):
                    continue
            elif previous is not None and item.filename == 'OEBPS/title_page.xhtml':
                if (right == _render_page(writer, current)
                        and left == _render_page(writer, previous)):
                    continue
            elif (previous is not None and item.filename == 'OEBPS/log_page.xhtml'
                  and writer.story.logfile):
                if right != _render_page(writer, current, log=True):
                    return False
                stabilized = _render_page(writer, previous, log=True)
                if left == stabilized:
                    continue
                # The upstream writer appends a p/hr even when all logged
                # metadata is identical. Ignore only that exact empty append,
                # never arbitrary log entries or customized wrappers.
                if not any(writer.hasConfig(key) for key in
                           ('logpage_update_start', 'logpage_update_end')):
                    empty = (writer.EPUB_LOG_UPDATE_START.template
                             + writer.EPUB_LOG_UPDATE_END.template)
                    if left.decode('utf-8') == writer.story.logfile:
                        expected = writer.story.logfile.replace('</body>', empty + '</body>')
                        if stabilized == expected.encode('utf-8'):
                            continue
            return False
    return True


def is_unchanged(original, candidate, writer, identical):
    """Compare all payloads, replaying only known volatile metadata pages."""
    if identical(original, candidate):
        return True
    try:
        with zipfile.ZipFile(original) as archive:
            previous = _previous_packaging_time(archive, writer)
        return _same_payloads(original, candidate, writer, previous)
    except (KeyError, ValueError, UnicodeError, ET.ParseError):
        # Unrecognized/custom/legacy layouts are never declared unchanged.
        return False
