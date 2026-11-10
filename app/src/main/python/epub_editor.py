# -*- coding: utf-8 -*-
__license__ = 'GPL v3'
__copyright__ = '2026, Community'
__docformat__ = 'restructuredtext en'

"""
Self-contained EPUB metadata and cover editor.

Provides functions to read, edit, and write back EPUB OPF metadata and
cover images using only the Python standard library (zipfile + xml.etree).

Inspired by calibre's epub.py / opf3.py metadata editing logic, but
rewritten to avoid the heavy calibre/lxml dependencies for use in
Chaquopy on Android.
"""

import os
import tempfile
import shutil
import zipfile
import xml.etree.ElementTree as ET
from xml.dom import minidom

import epub_xml

# Namespace prefixes used in OPF files
DC_NS = "http://purl.org/dc/elements/1.1/"
OPF_NS = "http://www.idpf.org/2007/opf"

# Register namespaces so ET doesn't mangle them
ET.register_namespace('', OPF_NS)
ET.register_namespace('dc', DC_NS)


def _find_opf_path(z):
    """Find the OPF file path inside the EPUB zip archive."""
    # Check container.xml first
    try:
        container = z.read("META-INF/container.xml")
        container_dom = ET.fromstring(container)
        container_ns = container_dom.tag.split("}")[0] + "}" if "}" in container_dom.tag else ""
        for rootfile in container_dom.iter():
            if rootfile.tag == container_ns + "rootfile":
                full_path = rootfile.get("full-path")
                if full_path:
                    return full_path
    except Exception:
        pass
    # Fallback: search for .opf files
    for name in z.namelist():
        if name.endswith(".opf"):
            return name
    return None


def _find_manifest_cover_item_id(opf_root):
    """Find the cover item id from the OPF's meta[name=cover] tag (EPUB 2 style)."""
    for meta in opf_root.findall(".//{%s}meta" % OPF_NS):
        if meta.get("name") == "cover":
            return meta.get("content")
    # EPUB 3 style: look for item with properties="cover-image"
    for item in opf_root.findall(".//{%s}item" % OPF_NS):
        props = item.get("properties", "")
        if "cover-image" in props:
            return item.get("id")
    return None


def _get_all_metadata_elements(opf_root):
    """Get the metadata element from the OPF root."""
    meta = opf_root.find(".//{%s}metadata" % OPF_NS)
    if meta is None:
        meta = opf_root.find("{%s}metadata" % OPF_NS)
    return meta


def _ensure_metadata_element(root):
    """Ensure the metadata element exists in the OPF root."""
    meta = root.find("{%s}metadata" % OPF_NS)
    if meta is None:
        meta = ET.SubElement(root, "{%s}metadata" % OPF_NS)
        # Add required namespaces
        meta.set("xmlns:dc", DC_NS)
        meta.set("xmlns:opf", OPF_NS)
    return meta


def _remove_all_dc_elements(meta, tag_local):
    """Remove all dc tag elements with the given local name."""
    pm = epub_xml.parent_map(meta)
    for elem in list(meta.findall(".//{%s}%s" % (DC_NS, tag_local))):
        parent = pm.get(elem)
        if parent is not None:
            parent.remove(elem)


def _set_dc_text_element(meta, tag_local, value, replace_all=True):
    """Set a dc: text element. If replace_all, removes existing ones first."""
    if replace_all:
        _remove_all_dc_elements(meta, tag_local)

    if value and value.strip():
        metadata = meta
        new_elem = ET.SubElement(metadata, "{%s}%s" % (DC_NS, tag_local))
        new_elem.text = value.strip()
    return


def _set_meta_element(meta, name, content):
    """Set or update an opf:meta[name=...] element."""
    for m in meta.findall(".//{%s}meta" % OPF_NS):
        if m.get("name") == name:
            m.set("content", content)
            return m
    # Create new
    m = ET.SubElement(meta, "{%s}meta" % OPF_NS)
    m.set("name", name)
    m.set("content", content)
    return m


def _get_meta_content(meta, name):
    """Get content from an opf:meta[name=...] element."""
    for m in meta.findall(".//{%s}meta" % OPF_NS):
        if m.get("name") == name:
            return m.get("content", "")
    return None


def _resolve_target(epub_path, output_path, backup_suffix):
    """
    Resolve the output target path and whether we need a temp file.
    Returns (target, temp_path, backup_path).
    """
    if output_path:
        target = output_path
        if backup_suffix:
            backup_path = epub_path + backup_suffix if target != epub_path + backup_suffix else None
            if backup_path and os.path.abspath(backup_path) != os.path.abspath(output_path):
                shutil.copy2(epub_path, backup_path)
            else:
                backup_path = None
        else:
            backup_path = None
        return target, None, backup_path
    else:
        # In-place modification: use a temp file
        if backup_suffix:
            backup_path = epub_path + backup_suffix
            shutil.copy2(epub_path, backup_path)
        else:
            backup_path = None
        # Create temp file in same directory for atomic rename
        temp_fd, temp_path = tempfile.mkstemp(
            suffix=".epub", dir=os.path.dirname(os.path.abspath(epub_path)) or None
        )
        os.close(temp_fd)
        # Remove the empty temp file so zipfile can create it fresh
        os.remove(temp_path)
        return epub_path, temp_path, backup_path


def _finalize_write(target, temp_path, backup_path):
    """
    If temp_path was used, move it to target. Remove backup if appropriate.
    Returns the final output path.
    """
    if temp_path and temp_path != target:
        shutil.move(temp_path, target)
    return target


def export_opf(epub_path, output_path=None):
    """
    Export the OPF XML from an EPUB file.

    Returns JSON: {"ok": true, "opf": "<xml string>", "path_in_epub": "..."}
    If output_path is provided, writes the XML to that file path.
    """
    try:
        with zipfile.ZipFile(epub_path, "r") as z:
            opf_name = _find_opf_path(z)
            if not opf_name:
                return {"ok": False, "error": "No OPF file found in EPUB"}
            opf_data = z.read(opf_name)
            opf_str = opf_data.decode("utf-8", errors="replace")
            # Pretty-print the XML
            try:
                dom = minidom.parseString(opf_str)
                pretty = dom.toprettyxml(indent="  ", encoding="utf-8")
                if isinstance(pretty, bytes):
                    pretty = pretty.decode("utf-8")
            except Exception:
                pretty = opf_str
            result = {
                "ok": True,
                "opf": pretty,
                "path_in_epub": opf_name,
            }
            if output_path:
                with open(output_path, "w", encoding="utf-8") as f:
                    f.write(pretty)
                result["written_path"] = output_path
            return result
    except Exception as e:
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


def import_opf(epub_path, opf_path_or_xml, output_path=None, backup_suffix=None):
    """
    Import OPF XML into an EPUB file, replacing the existing OPF.

    Args:
        epub_path: Path to the source EPUB
        opf_path_or_xml: Either a file path containing OPF XML, or raw XML string
        output_path: If provided, write to this path; otherwise modify in-place
        backup_suffix: If provided, create a backup with this suffix before modifying

    Returns JSON: {"ok": true, "output_path": "...", "backup_path": "..."}
    """
    try:
        # Read the new OPF content
        if os.path.isfile(opf_path_or_xml):
            with open(opf_path_or_xml, "r", encoding="utf-8") as f:
                new_opf_str = f.read()
        else:
            new_opf_str = opf_path_or_xml

        # Validate it's parseable XML
        try:
            ET.fromstring(new_opf_str)
        except ET.ParseError as e:
            return {"ok": False, "error": "Invalid OPF XML: %s" % e}

        with zipfile.ZipFile(epub_path, "r") as zin:
            opf_name = _find_opf_path(zin)
            if not opf_name:
                return {"ok": False, "error": "No OPF file found in EPUB"}

            target, temp_path, backup_path = _resolve_target(
                epub_path, output_path, backup_suffix
            )
            write_path = temp_path if temp_path else target

            # Read all entries into memory first (source stays open for reading)
            entries = []
            for item in zin.namelist():
                data = zin.read(item)
                if item == opf_name:
                    entries.append((item, new_opf_str.encode("utf-8")))
                elif item == "mimetype":
                    entries.append((item, data, zipfile.ZIP_STORED))
                else:
                    entries.append((item, data))

            # Write to temp or target
            with zipfile.ZipFile(write_path, "w", zipfile.ZIP_DEFLATED) as zout:
                for entry in entries:
                    if len(entry) == 3:
                        zout.writestr(entry[0], entry[1], entry[2])
                    else:
                        zout.writestr(entry[0], entry[1])

            final_path = _finalize_write(target, temp_path, backup_path)

            result = {"ok": True, "output_path": final_path, "path_in_epub": opf_name}
            if backup_path:
                result["backup_path"] = backup_path
            return result
    except Exception as e:
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


# ---------------------------------------------------------------------------
# EPUB 3 refinement helpers (subtitle, contributor roles/file-as)
# ---------------------------------------------------------------------------

def _get_meta_property_refines(meta):
    """Build a dict mapping refines-target-id → {property: value}.

    Scans all <meta> elements that have a ``refines`` attribute and
    ``property`` attribute, grouping them by the element they refine.
    For EPUB 3-style <meta property="..."], the value is the element text.
    """
    result = {}
    for m in meta.findall(".//{%s}meta" % OPF_NS):
        refines = m.get("refines", "")
        prop = m.get("property", "")
        if not refines or not prop:
            continue
        # strips leading '#' from refines value
        target_id = refines.lstrip("#")
        # EPUB 3 <meta property="..."> uses element text as the value.
        # If text is empty, fall back to content attribute (mixed usage).
        value = (m.text or "").strip() if m.text else m.get("content", "")
        if not value:
            value = m.get("content", "")
        result.setdefault(target_id, {})[prop] = value
    return result


def _find_title_with_id(meta):
    """Find the primary dc:title element and return it (or None).

    Also works when the title has no id — the caller can assign one when
    writing.
    """
    return meta.find(".//{%s}title" % DC_NS)


def _read_subtitle(meta):
    """Read an EPUB 3 subtitle from the metadata element.

    Looks for a dc:title element with a sibling <meta property="title-type"
    refines="#<title-id>">subtitle</meta>.

    Returns the subtitle string, or empty string if not found.
    """
    refines_map = _get_meta_property_refines(meta)
    # Check all dc:title elements for subtitle refinements.
    for title_elem in meta.findall(".//{%s}title" % DC_NS):
        title_id = title_elem.get("id", "")
        if not title_id:
            continue
        props = refines_map.get(title_id, {})
        if props.get("title-type", "") == "subtitle":
            # The subtitle is the text of this title element.
            if title_elem.text and title_elem.text.strip():
                return title_elem.text.strip()
    return ""


def _read_contributors(meta):
    """Read dc:contributor elements with their role and file-as refinements.

    Returns a list of dicts: [{'name': str, 'role': str, 'file_as': str}, ...]
    Preserves contributor order. Missing role/file_as are represented as empty
    strings.
    """
    refines_map = _get_meta_property_refines(meta)
    contributors = []
    for elem in meta.findall(".//{%s}contributor" % DC_NS):
        contrib_id = elem.get("id", "")
        props = refines_map.get(contrib_id, {}) if contrib_id else {}
        name = elem.text.strip() if elem.text else ""
        role = props.get("role", "")
        file_as = props.get("file-as", "")
        # Only include if there's actually a name.
        if name:
            contributors.append({
                'name': name,
                'role': role,
                'file_as': file_as,
            })
    return contributors


# ---------------------------------------------------------------------------
# EPUB 3 refinement write helpers
# ---------------------------------------------------------------------------

def _ensure_element_id(elem, meta):
    """Ensure an XML element has an 'id' attribute, assigning one if missing.
    Returns the id string.  *meta* is passed so we can scan siblings."""
    elem_id = elem.get("id")
    if elem_id:
        return elem_id
    # Generate a stable id based on element tag.
    tag_local = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag
    # Collect existing ids among siblings to avoid collisions.
    existing_ids = set()
    for sibling in list(meta):
        existing_ids.add(sibling.get("id", ""))
    idx = 1
    elem_id = "%s-%d" % (tag_local, idx)
    while elem_id in existing_ids:
        idx += 1
        elem_id = "%s-%d" % (tag_local, idx)
    elem.set("id", elem_id)
    return elem_id


def _find_or_create_meta_refinement(meta, target_id, property_name):
    """Find or create a <meta property=... refines=...> element.

    Returns the meta element. If a matching refinement already exists,
    returns it (so its content can be updated).
    """
    for m in meta.findall(".//{%s}meta" % OPF_NS):
        if m.get("refines", "").lstrip("#") == target_id and m.get("property") == property_name:
            return m
    # Create new.
    m = ET.SubElement(meta, "{%s}meta" % OPF_NS)
    m.set("refines", "#" + target_id)
    m.set("property", property_name)
    return m


def _remove_all_refinements_for(meta, target_id):
    """Remove all <meta refines="#target_id"> elements."""
    pm = epub_xml.parent_map(meta)
    for m in list(meta.findall(".//{%s}meta" % OPF_NS)):
        if m.get("refines", "").lstrip("#") == target_id:
            parent = pm.get(m)
            if parent is not None:
                parent.remove(m)


def _remove_all_contributors(meta):
    """Remove all dc:contributor elements and their associated refinements."""
    # One parent map serves the whole pass: the nested refinements call removes
    # <meta> elements, never contributors, so contributor lookups stay valid.
    pm = epub_xml.parent_map(meta)
    for elem in list(meta.findall(".//{%s}contributor" % DC_NS)):
        contrib_id = elem.get("id", "")
        if contrib_id:
            _remove_all_refinements_for(meta, contrib_id)
        parent = pm.get(elem)
        if parent is not None:
            parent.remove(elem)


def _remove_subtitle(meta):
    """Remove subtitle dc:title element(s) and their title-type refinement metas.

    A subtitle is a dc:title element that has an associated
    <meta property="title-type" refines="#<title-id>">subtitle</meta>.
    """
    refines_map = _get_meta_property_refines(meta)
    pm = epub_xml.parent_map(meta)
    for title_elem in list(meta.findall(".//{%s}title" % DC_NS)):
        title_id = title_elem.get("id", "")
        if not title_id:
            continue
        props = refines_map.get(title_id, {})
        if props.get("title-type", "") == "subtitle":
            # Remove the refinement meta(s) for this title.
            for m in list(meta.findall(".//{%s}meta" % OPF_NS)):
                if m.get("refines", "").lstrip("#") == title_id:
                    parent = pm.get(m)
                    if parent is not None:
                        parent.remove(m)
            # Remove the subtitle title element itself.
            tp = pm.get(title_elem)
            if tp is not None:
                tp.remove(title_elem)


def _write_subtitle(meta, subtitle):
    """Write or update a subtitle as an EPUB 3 title-type refinement.

    If *subtitle* is empty/whitespace, removes any existing subtitle.
    Otherwise, removes any existing subtitle, then creates a new dc:title
    element containing the subtitle text, gives it a stable id, and adds
    a <meta property="title-type" refines="#<id>">subtitle</meta>.
    """
    subtitle = subtitle.strip() if isinstance(subtitle, str) else ''

    # Always remove existing subtitle first (idempotent).
    _remove_subtitle(meta)

    if not subtitle:
        return

    # Create the subtitle dc:title element.
    subtitle_elem = ET.SubElement(meta, "{%s}title" % DC_NS)
    subtitle_elem.text = subtitle
    subtitle_id = _ensure_element_id(subtitle_elem, meta)

    # Add the refinement meta: <meta property="title-type" refines="#<id>">subtitle</meta>
    ref_meta = _find_or_create_meta_refinement(meta, subtitle_id, "title-type")
    ref_meta.text = "subtitle"


def _write_contributors(meta, contributors):
    """Write dc:contributor elements with optional role and file-as refinements.

    *contributors* is a list of dicts: [{'name': str, 'role': str, 'file_as': str}].
    Removes any existing contributors first to avoid duplicates. Preserves order.
    Does not write empty refinements (role/file_as left blank are skipped).
    """
    # Remove all existing contributors and their refinements.
    _remove_all_contributors(meta)

    for contrib in contributors:
        if not isinstance(contrib, dict):
            contrib = {'name': str(contrib), 'role': '', 'file_as': ''}
        name = contrib.get('name', '')
        if not name or not isinstance(name, str) or not name.strip():
            continue
        name = name.strip()
        role = contrib.get('role', '')
        file_as = contrib.get('file_as', '')

        elem = ET.SubElement(meta, "{%s}contributor" % DC_NS)
        elem.text = name
        elem_id = _ensure_element_id(elem, meta)

        if role and isinstance(role, str):
            role = role.strip()
            if role:
                role_meta = _find_or_create_meta_refinement(meta, elem_id, "role")
                role_meta.set("scheme", "marc:relators")
                role_meta.text = role

        if file_as and isinstance(file_as, str):
            file_as = file_as.strip()
            if file_as:
                fa_meta = _find_or_create_meta_refinement(meta, elem_id, "file-as")
                fa_meta.text = file_as


def _notify_read_error(callback, error):
    """Optional diagnostic side channel; never alter the reader's public result."""
    if callback is not None:
        try:
            callback(error)
        except Exception:
            pass


def read_metadata_fields(epub_path, _on_error=None):
    """
    Read structured metadata fields from an EPUB's OPF.

    Returns JSON: {"ok": true, "metadata": {"title": ..., "authors": [...], ...}}
    """
    try:
        with zipfile.ZipFile(epub_path, "r") as z:
            opf_name = _find_opf_path(z)
            if not opf_name:
                _notify_read_error(_on_error, ValueError('Missing OPF'))
                return {"ok": False, "error": "No OPF file found in EPUB"}
            opf_data = z.read(opf_name)
            root = ET.fromstring(opf_data)

            meta = _get_all_metadata_elements(root)
            if meta is None:
                _notify_read_error(_on_error, ValueError('Missing metadata'))
                return {"ok": False, "error": "No metadata element found in OPF"}

            result = {
                "title": "",
                "authors": [],
                "languages": [],
                "publisher": "",
                "description": "",
                "tags": [],
                "series": "",
                "series_index": "",
                "rating": "",
                "identifiers": {},
                "isbn": "",
                "pubdate": "",
                "rights": "",
                "subtitle": "",
                "contributors": [],
            }

            # Title
            title_elem = meta.find(".//{%s}title" % DC_NS)
            if title_elem is not None and title_elem.text:
                result["title"] = title_elem.text.strip()

            # Subtitle (EPUB 3 title-type refinement)
            result["subtitle"] = _read_subtitle(meta)

            # Contributors (dc:contributor with optional role/file-as)
            result["contributors"] = _read_contributors(meta)

            # Authors (creators)
            for creator in meta.findall(".//{%s}creator" % DC_NS):
                if creator.text:
                    result["authors"].append(creator.text.strip())

            # Languages
            for lang in meta.findall(".//{%s}language" % DC_NS):
                if lang.text:
                    result["languages"].append(lang.text.strip())

            # Publisher
            pub_elem = meta.find(".//{%s}publisher" % DC_NS)
            if pub_elem is not None and pub_elem.text:
                result["publisher"] = pub_elem.text.strip()

            # Description
            desc_elem = meta.find(".//{%s}description" % DC_NS)
            if desc_elem is not None and desc_elem.text:
                result["description"] = desc_elem.text.strip()

            # Tags (subjects)
            for subject in meta.findall(".//{%s}subject" % DC_NS):
                if subject.text:
                    result["tags"].append(subject.text.strip())

            # Series and series_index (Calibre-specific metadata)
            for m in meta.findall(".//{%s}meta" % OPF_NS):
                name = m.get("name", "")
                content = m.get("content", "")
                if name == "calibre:series":
                    result["series"] = content
                elif name == "calibre:series_index":
                    result["series_index"] = content
                elif name == "calibre:rating":
                    result["rating"] = content

            # Also check for dc:date
            date_elem = meta.find(".//{%s}date" % DC_NS)
            if date_elem is not None and date_elem.text:
                result["pubdate"] = date_elem.text.strip()

            # Rights
            rights_elem = meta.find(".//{%s}rights" % DC_NS)
            if rights_elem is not None and rights_elem.text:
                result["rights"] = rights_elem.text.strip()

            # Identifiers
            uid = root.get("unique-identifier", "")
            for ident in meta.findall(".//{%s}identifier" % DC_NS):
                ident_id = ident.get("id", "")
                if ident.text:
                    result["identifiers"][ident_id or "id"] = ident.text.strip()
                    if uid and ident_id == uid:
                        result["identifiers"]["_primary_id"] = ident_id

            # ISBN: look for identifier containing ISBN
            for ident in meta.findall(".//{%s}identifier" % DC_NS):
                if ident.text and ident.text.lower().startswith("isbn"):
                    result["isbn"] = ident.text.replace("ISBN:", "").strip()

            return {"ok": True, "metadata": result, "path_in_epub": opf_name}
    except Exception as e:
        _notify_read_error(_on_error, e)
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


def write_metadata(epub_path, fields, output_path=None, backup_suffix=None):
    """
    Write structured metadata fields to an EPUB's OPF.

    Args:
        epub_path: Path to the source EPUB
        fields: dict with any of: title, authors, languages, publisher,
                description, tags, series, series_index, rating,
                isbn, pubdate, rights
        output_path: If provided, write to this path; otherwise modify in-place
        backup_suffix: If provided, create a backup with this suffix

    Returns JSON: {"ok": true, "output_path": "...", "changed_fields": [...]}
    """
    try:
        # Accept fields as either a dict or a JSON string
        if isinstance(fields, str):
            import json as _json
            fields = _json.loads(fields)
        changed_fields = []

        with zipfile.ZipFile(epub_path, "r") as zin:
            opf_name = _find_opf_path(zin)
            if not opf_name:
                return {"ok": False, "error": "No OPF file found in EPUB"}
            opf_data = zin.read(opf_name)
            root = ET.fromstring(opf_data)
            meta = _ensure_metadata_element(root)

            relpath = opf_name.rsplit("/", 1)[0] + "/" if "/" in opf_name else ""

            # Title
            if "title" in fields and fields["title"] is not None:
                _set_dc_text_element(meta, "title", fields["title"])
                changed_fields.append("title")

            # Subtitle (EPUB 3 title-type refinement)
            if "subtitle" in fields and fields["subtitle"] is not None:
                _write_subtitle(meta, fields["subtitle"])
                changed_fields.append("subtitle")

            # Contributors (dc:contributor with optional role/file-as)
            if "contributors" in fields and fields["contributors"] is not None:
                _write_contributors(meta, fields["contributors"])
                changed_fields.append("contributors")

            # Authors
            if "authors" in fields and fields["authors"] is not None:
                _remove_all_dc_elements(meta, "creator")
                for author in fields["authors"]:
                    if author and author.strip():
                        elem = ET.SubElement(meta, "{%s}creator" % DC_NS)
                        elem.text = author.strip()
                        elem.set("id", "creator")
                changed_fields.append("authors")

            # Languages
            if "languages" in fields and fields["languages"] is not None:
                _remove_all_dc_elements(meta, "language")
                for lang in fields["languages"]:
                    if lang and lang.strip():
                        elem = ET.SubElement(meta, "{%s}language" % DC_NS)
                        elem.text = lang.strip()
                changed_fields.append("languages")

            # Publisher
            if "publisher" in fields and fields["publisher"] is not None:
                _remove_all_dc_elements(meta, "publisher")
                if fields["publisher"].strip():
                    _set_dc_text_element(meta, "publisher", fields["publisher"])
                changed_fields.append("publisher")

            # Description
            if "description" in fields and fields["description"] is not None:
                _remove_all_dc_elements(meta, "description")
                if fields["description"].strip():
                    _set_dc_text_element(meta, "description", fields["description"])
                changed_fields.append("description")

            # Tags (subjects)
            if "tags" in fields and fields["tags"] is not None:
                _remove_all_dc_elements(meta, "subject")
                for tag in fields["tags"]:
                    if tag and tag.strip():
                        elem = ET.SubElement(meta, "{%s}subject" % DC_NS)
                        elem.text = tag.strip()
                changed_fields.append("tags")

            # Series (Calibre-specific)
            if "series" in fields and fields["series"] is not None:
                _set_meta_element(meta, "calibre:series", fields["series"])
                changed_fields.append("series")
            if "series_index" in fields and fields["series_index"] is not None:
                _set_meta_element(meta, "calibre:series_index", str(fields["series_index"]))
                changed_fields.append("series_index")

            # Rating (Calibre-specific)
            if "rating" in fields and fields["rating"] is not None:
                _set_meta_element(meta, "calibre:rating", str(fields["rating"]))
                changed_fields.append("rating")

            # ISBN
            if "isbn" in fields and fields["isbn"] is not None:
                isbn = fields["isbn"].strip()
                if isbn:
                    existing_id = None
                    for ident in meta.findall(".//{%s}identifier" % DC_NS):
                        if ident.text and "isbn" in ident.text.lower():
                            existing_id = ident
                            break
                    if existing_id is not None:
                        existing_id.text = "ISBN:%s" % isbn
                    else:
                        elem = ET.SubElement(meta, "{%s}identifier" % DC_NS)
                        elem.text = "ISBN:%s" % isbn
                changed_fields.append("isbn")

            # Pubdate
            if "pubdate" in fields and fields["pubdate"] is not None:
                _remove_all_dc_elements(meta, "date")
                if fields["pubdate"].strip():
                    elem = ET.SubElement(meta, "{%s}%s" % (DC_NS, "date"))
                    elem.text = fields["pubdate"].strip()
                changed_fields.append("pubdate")

            # Rights
            if "rights" in fields and fields["rights"] is not None:
                _remove_all_dc_elements(meta, "rights")
                if fields["rights"].strip():
                    _set_dc_text_element(meta, "rights", fields["rights"])
                changed_fields.append("rights")

            # Serialize the modified OPF
            opf_bytes = ET.tostring(root, encoding="utf-8")
            try:
                dom = minidom.parseString(opf_bytes)
                opf_str = dom.toprettyxml(indent="  ", encoding="utf-8")
                if isinstance(opf_str, bytes):
                    opf_str = opf_str.decode("utf-8")
                opf_bytes = opf_str.encode("utf-8")
            except Exception:
                pass

            target, temp_path, backup_path = _resolve_target(
                epub_path, output_path, backup_suffix
            )
            write_path = temp_path if temp_path else target

            # Read all entries into memory first
            entries = []
            for item in zin.namelist():
                data = zin.read(item)
                if item == opf_name:
                    entries.append((item, opf_bytes))
                elif item == "mimetype":
                    entries.append((item, data, zipfile.ZIP_STORED))
                else:
                    entries.append((item, data))

            # Write to temp or target
            with zipfile.ZipFile(write_path, "w", zipfile.ZIP_DEFLATED) as zout:
                for entry in entries:
                    if len(entry) == 3:
                        zout.writestr(entry[0], entry[1], entry[2])
                    else:
                        zout.writestr(entry[0], entry[1])

            final_path = _finalize_write(target, temp_path, backup_path)

            return {
                "ok": True,
                "output_path": final_path,
                "changed_fields": changed_fields,
                "path_in_epub": opf_name,
            }
    except Exception as e:
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


def replace_cover(epub_path, image_data, image_mime, output_path=None, backup_suffix=None):
    """
    Replace the cover image in an EPUB file.

    Args:
        epub_path: Path to the source EPUB
        image_data: Raw image bytes
        image_mime: MIME type (e.g. "image/jpeg", "image/png")
        output_path: If provided, write to this path; otherwise modify in-place
        backup_suffix: If provided, create a backup with this suffix

    Returns JSON: {"ok": true, "output_path": "...", "cover_path_in_epub": "...", ...}
    """
    # Track temp path for cleanup on failure (same pattern as write_metadata_and_cover)
    _temp_path = None
    try:
        # Determine a reasonable filename for the new cover
        ext = ".jpg"
        if image_mime == "image/png":
            ext = ".png"
        elif image_mime == "image/gif":
            ext = ".gif"
        elif image_mime == "image/webp":
            ext = ".webp"
        new_cover_name = "cover" + ext

        with zipfile.ZipFile(epub_path, "r") as zin:
            opf_name = _find_opf_path(zin)
            if not opf_name:
                return {"ok": False, "error": "No OPF file found in EPUB"}
            opf_data = zin.read(opf_name)
            root = ET.fromstring(opf_data)

            manifest_elem = root.find(".//{%s}manifest" % OPF_NS)
            if manifest_elem is None:
                manifest_elem = root.find("{%s}manifest" % OPF_NS)
            if manifest_elem is None:
                return {"ok": False, "error": "No manifest element found in OPF"}

            meta = _get_all_metadata_elements(root)
            if meta is None:
                meta = _ensure_metadata_element(root)

            # Determine OPF path prefix
            relpath = opf_name.rsplit("/", 1)[0] + "/" if "/" in opf_name else ""
            cover_full_path = relpath + new_cover_name if relpath else new_cover_name

            # Find existing cover item reference
            old_cover_href = None
            cover_item_id = _find_manifest_cover_item_id(root)

            if cover_item_id:
                for item in manifest_elem.findall("{%s}item" % OPF_NS):
                    if item.get("id") == cover_item_id:
                        old_cover_href = item.get("href")
                        # Update the href and media-type
                        item.set("href", new_cover_name)
                        item.set("media-type", image_mime)
                        break
            else:
                # No cover found; need to add one
                item = ET.SubElement(manifest_elem, "{%s}item" % OPF_NS)
                item.set("id", "cover")
                item.set("href", new_cover_name)
                item.set("media-type", image_mime)
                item.set("properties", "cover-image")

                # Add cover meta tag (EPUB 2 style)
                m = ET.SubElement(meta, "{%s}meta" % OPF_NS)
                m.set("name", "cover")
                m.set("content", "cover")

            # Serialize modified OPF
            opf_bytes = ET.tostring(root, encoding="utf-8")
            try:
                dom = minidom.parseString(opf_bytes)
                opf_str = dom.toprettyxml(indent="  ", encoding="utf-8")
                if isinstance(opf_str, bytes):
                    opf_str = opf_str.decode("utf-8")
                opf_bytes = opf_str.encode("utf-8")
            except Exception:
                pass

            target, temp_path, backup_path = _resolve_target(
                epub_path, output_path, backup_suffix
            )
            _temp_path = temp_path  # for cleanup on failure
            write_path = temp_path if temp_path else target

            # Read all entries into memory, skipping old cover image
            old_cover_zip_path = None
            if old_cover_href:
                old_cover_zip_path = relpath + old_cover_href if relpath else old_cover_href

            entries = []
            for item_name in zin.namelist():
                data = zin.read(item_name)
                if item_name == opf_name:
                    entries.append((item_name, opf_bytes))
                elif item_name == "mimetype":
                    entries.append((item_name, data, zipfile.ZIP_STORED))
                elif old_cover_zip_path and item_name == old_cover_zip_path:
                    # Always skip old cover image — we'll write the new one later
                    pass
                else:
                    entries.append((item_name, data))

            # Write to temp or target
            with zipfile.ZipFile(write_path, "w", zipfile.ZIP_DEFLATED) as zout:
                for entry in entries:
                    if len(entry) == 3:
                        zout.writestr(entry[0], entry[1], entry[2])
                    else:
                        zout.writestr(entry[0], entry[1])
                # Write the new cover image
                zout.writestr(cover_full_path, image_data, compress_type=zipfile.ZIP_DEFLATED)

            final_path = _finalize_write(target, temp_path, backup_path)
            _temp_path = None  # successfully renamed; no cleanup needed

            result = {
                "ok": True,
                "output_path": final_path,
                "cover_path_in_epub": cover_full_path,
                "old_cover_path": old_cover_href,
                "old_cover_removed": old_cover_href is not None,
            }
            if backup_path:
                result["backup_path"] = backup_path
            return result
    except Exception as e:
        # Clean up the temp file if it was created but not renamed.
        # The original EPUB is never touched on this path.
        if _temp_path is not None and os.path.exists(_temp_path):
            try:
                os.remove(_temp_path)
            except Exception:
                pass
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


def _apply_metadata_to_opf(root, fields):
    """Apply structured metadata fields to a parsed OPF XML root (in-place).

    Extracted from write_metadata() so it can be reused by the atomic
    write_metadata_and_cover() function.  Returns the list of changed
    field names.
    """
    meta = _ensure_metadata_element(root)
    changed_fields = []

    # Title
    if "title" in fields and fields["title"] is not None:
        _set_dc_text_element(meta, "title", fields["title"])
        changed_fields.append("title")

    # Subtitle (EPUB 3 title-type refinement)
    if "subtitle" in fields and fields["subtitle"] is not None:
        _write_subtitle(meta, fields["subtitle"])
        changed_fields.append("subtitle")

    # Contributors (dc:contributor with optional role/file-as)
    if "contributors" in fields and fields["contributors"] is not None:
        _write_contributors(meta, fields["contributors"])
        changed_fields.append("contributors")

    # Authors
    if "authors" in fields and fields["authors"] is not None:
        _remove_all_dc_elements(meta, "creator")
        for author in fields["authors"]:
            if author and author.strip():
                elem = ET.SubElement(meta, "{%s}creator" % DC_NS)
                elem.text = author.strip()
                elem.set("id", "creator")
        changed_fields.append("authors")

    # Languages
    if "languages" in fields and fields["languages"] is not None:
        _remove_all_dc_elements(meta, "language")
        for lang in fields["languages"]:
            if lang and lang.strip():
                elem = ET.SubElement(meta, "{%s}language" % DC_NS)
                elem.text = lang.strip()
        changed_fields.append("languages")

    # Publisher
    if "publisher" in fields and fields["publisher"] is not None:
        _remove_all_dc_elements(meta, "publisher")
        if fields["publisher"].strip():
            _set_dc_text_element(meta, "publisher", fields["publisher"])
        changed_fields.append("publisher")

    # Description
    if "description" in fields and fields["description"] is not None:
        _remove_all_dc_elements(meta, "description")
        if fields["description"].strip():
            _set_dc_text_element(meta, "description", fields["description"])
        changed_fields.append("description")

    # Tags (subjects)
    if "tags" in fields and fields["tags"] is not None:
        _remove_all_dc_elements(meta, "subject")
        for tag in fields["tags"]:
            if tag and tag.strip():
                elem = ET.SubElement(meta, "{%s}subject" % DC_NS)
                elem.text = tag.strip()
        changed_fields.append("tags")

    # Series (Calibre-specific)
    if "series" in fields and fields["series"] is not None:
        _set_meta_element(meta, "calibre:series", fields["series"])
        changed_fields.append("series")
    if "series_index" in fields and fields["series_index"] is not None:
        _set_meta_element(meta, "calibre:series_index", str(fields["series_index"]))
        changed_fields.append("series_index")

    # Rating (Calibre-specific)
    if "rating" in fields and fields["rating"] is not None:
        _set_meta_element(meta, "calibre:rating", str(fields["rating"]))
        changed_fields.append("rating")

    # ISBN
    if "isbn" in fields and fields["isbn"] is not None:
        isbn = fields["isbn"].strip()
        if isbn:
            existing_id = None
            for ident in meta.findall(".//{%s}identifier" % DC_NS):
                if ident.text and "isbn" in ident.text.lower():
                    existing_id = ident
                    break
            if existing_id is not None:
                existing_id.text = "ISBN:%s" % isbn
            else:
                elem = ET.SubElement(meta, "{%s}identifier" % DC_NS)
                elem.text = "ISBN:%s" % isbn
        changed_fields.append("isbn")

    # Pubdate
    if "pubdate" in fields and fields["pubdate"] is not None:
        _remove_all_dc_elements(meta, "date")
        if fields["pubdate"].strip():
            elem = ET.SubElement(meta, "{%s}%s" % (DC_NS, "date"))
            elem.text = fields["pubdate"].strip()
        changed_fields.append("pubdate")

    # Rights
    if "rights" in fields and fields["rights"] is not None:
        _remove_all_dc_elements(meta, "rights")
        if fields["rights"].strip():
            _set_dc_text_element(meta, "rights", fields["rights"])
        changed_fields.append("rights")

    return changed_fields


def _apply_cover_to_opf(root, opf_name, image_data, image_mime):
    """Apply a cover image to a parsed OPF XML root (in-place).

    Extracted from replace_cover() so it can be reused by the atomic
    write_metadata_and_cover() function.

    Returns (old_cover_zip_path, cover_full_path) where old_cover_zip_path
    is None if no existing cover was found.
    """
    manifest_elem = root.find(".//{%s}manifest" % OPF_NS)
    if manifest_elem is None:
        manifest_elem = root.find("{%s}manifest" % OPF_NS)

    meta = _get_all_metadata_elements(root)
    if meta is None:
        meta = _ensure_metadata_element(root)

    # Determine OPF path prefix
    relpath = opf_name.rsplit("/", 1)[0] + "/" if "/" in opf_name else ""

    # Determine new cover filename
    ext = ".jpg"
    if image_mime == "image/png":
        ext = ".png"
    elif image_mime == "image/gif":
        ext = ".gif"
    elif image_mime == "image/webp":
        ext = ".webp"
    new_cover_name = "cover" + ext
    cover_full_path = relpath + new_cover_name if relpath else new_cover_name

    # Find existing cover item reference
    old_cover_href = None
    cover_item_id = _find_manifest_cover_item_id(root)

    if cover_item_id:
        for item in manifest_elem.findall("{%s}item" % OPF_NS):
            if item.get("id") == cover_item_id:
                old_cover_href = item.get("href")
                item.set("href", new_cover_name)
                item.set("media-type", image_mime)
                break
    else:
        # No cover found; need to add one
        item = ET.SubElement(manifest_elem, "{%s}item" % OPF_NS)
        item.set("id", "cover")
        item.set("href", new_cover_name)
        item.set("media-type", image_mime)
        item.set("properties", "cover-image")

        # Add cover meta tag (EPUB 2 style)
        m = ET.SubElement(meta, "{%s}meta" % OPF_NS)
        m.set("name", "cover")
        m.set("content", "cover")

    old_cover_zip_path = None
    if old_cover_href:
        old_cover_zip_path = relpath + old_cover_href if relpath else old_cover_href

    return old_cover_zip_path, cover_full_path


def _serialize_opf(root):
    """Serialize the OPF XML root to bytes with pretty-printing."""
    # Routed through epub_xml.serialize so the process-global namespace registry
    # is set for OPF at this exact moment: another module may have registered the
    # empty prefix for XHTML, which would otherwise make this emit ns0: prefixes.
    # xml_declaration stays False because minidom adds its own below.
    opf_bytes = epub_xml.serialize(root, epub_xml.OPF, xml_declaration=False)
    try:
        dom = minidom.parseString(opf_bytes)
        opf_str = dom.toprettyxml(indent="  ", encoding="utf-8")
        if isinstance(opf_str, bytes):
            opf_str = opf_str.decode("utf-8")
        opf_bytes = opf_str.encode("utf-8")
    except Exception:
        pass
    return opf_bytes


def _build_entry_list(zin, opf_name, opf_bytes, old_cover_zip_path=None):
    """Read all entries from the source zip into memory, substituting the
    modified OPF and skipping the old cover image.

    Returns a list of tuples: (name, data) or (name, data, compress_type).
    """
    entries = []
    for item_name in zin.namelist():
        data = zin.read(item_name)
        if item_name == opf_name:
            entries.append((item_name, opf_bytes))
        elif item_name == "mimetype":
            entries.append((item_name, data, zipfile.ZIP_STORED))
        elif old_cover_zip_path and item_name == old_cover_zip_path:
            # Skip old cover image — new one is appended separately
            pass
        else:
            entries.append((item_name, data))
    return entries


def write_metadata_and_cover(epub_path, fields, image_data=None, image_mime=None,
                              output_path=None, backup_suffix=None):
    """Write metadata and (optionally) a cover to an EPUB in a single atomic operation.

    Unlike calling write_metadata() then replace_cover(), this function reads
    the EPUB once, applies both changes to the same in-memory entry list, and
    writes the result in a single zip write followed by an atomic temp-file
    rename.  This ensures the EPUB is never left in a partially-written state
    (metadata written but cover failed, etc.).

    Args:
        epub_path: Path to the source EPUB.
        fields: dict of metadata fields (same format as write_metadata).
        image_data: Raw image bytes, or None to skip cover replacement.
        image_mime: MIME type for the cover image, or None.
        output_path: If provided, write to this path; otherwise modify in-place.
        backup_suffix: If provided, create a backup with this suffix.

    Returns JSON: {"ok": true, "output_path": "...", "changed_fields": [...],
    "metadata_written": bool, "cover_written": bool, "cover_path_in_epub": "..."}
    """
    # Track temp path for cleanup on failure
    _temp_path = None
    try:
        # Accept fields as either a dict or a JSON string
        if isinstance(fields, str):
            import json as _json
            fields = _json.loads(fields)

        cover_ok = False
        old_cover_zip_path = None
        cover_full_path = None

        with zipfile.ZipFile(epub_path, "r") as zin:
            opf_name = _find_opf_path(zin)
            if not opf_name:
                return {"ok": False, "error": "No OPF file found in EPUB"}

            opf_data = zin.read(opf_name)
            root = ET.fromstring(opf_data)

            # Apply metadata changes to the XML tree
            changed_fields = _apply_metadata_to_opf(root, fields)

            # Apply cover changes to the XML tree (if requested)
            if image_data is not None and image_mime:
                old_cover_zip_path, cover_full_path = _apply_cover_to_opf(
                    root, opf_name, image_data, image_mime
                )
                cover_ok = True

            # Serialize the modified OPF
            opf_bytes = _serialize_opf(root)

            # Build the entry list (substituting OPF, skipping old cover)
            entries = _build_entry_list(zin, opf_name, opf_bytes, old_cover_zip_path)

            # If cover was added/changed, append the new cover image
            if cover_ok and image_data and cover_full_path:
                entries.append((cover_full_path, image_data))

            target, temp_path, backup_path = _resolve_target(
                epub_path, output_path, backup_suffix
            )
            _temp_path = temp_path  # for cleanup on failure
            write_path = temp_path if temp_path else target

            # Write all entries in a single zip write
            with zipfile.ZipFile(write_path, "w", zipfile.ZIP_DEFLATED) as zout:
                for entry in entries:
                    if len(entry) == 3:
                        zout.writestr(entry[0], entry[1], entry[2])
                    else:
                        zout.writestr(entry[0], entry[1])

            final_path = _finalize_write(target, temp_path, backup_path)
            _temp_path = None  # successfully renamed; no cleanup needed

            result = {
                "ok": True,
                "output_path": final_path,
                "changed_fields": changed_fields,
                "metadata_written": True,
                "cover_written": cover_ok,
                "path_in_epub": opf_name,
            }
            if cover_ok and cover_full_path:
                result["cover_path_in_epub"] = cover_full_path
                result["old_cover_removed"] = old_cover_zip_path is not None
            if backup_path:
                result["backup_path"] = backup_path
            return result

    except Exception as e:
        # Clean up the temp file if it was created but not renamed.
        # The original EPUB is never touched on this path.
        if _temp_path is not None and os.path.exists(_temp_path):
            try:
                os.remove(_temp_path)
            except Exception:
                pass
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


def get_metadata_summary(epub_path):
    """
    Get a quick metadata summary including OPF path and cover info.
    """
    try:
        with zipfile.ZipFile(epub_path, "r") as z:
            opf_name = _find_opf_path(z)
            if not opf_name:
                return {"ok": False, "error": "No OPF file found in EPUB"}
            names = z.namelist()
            # Find cover image
            cover_info = {"path": None, "mime": None, "size": 0}
            for name in names:
                lower = name.lower()
                if lower.endswith((".jpg", ".jpeg", ".png")):
                    base = lower.split("/")[-1]
                    if "cover" in base:
                        cover_info = {
                            "path": name,
                            "mime": "image/jpeg" if lower.endswith((".jpg", ".jpeg")) else "image/png",
                            "size": z.getinfo(name).file_size,
                        }
                        break
            opf_bytes = z.read(opf_name)
            root = ET.fromstring(opf_bytes)
            uid = root.get("unique-identifier", "")
            return {
                "ok": True,
                "opf_path": opf_name,
                "unique_identifier_attr": uid,
                "total_entries": len(names),
                "cover": cover_info,
            }
    except Exception as e:
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


def pretty_print_opf(opf_path):
    """Read and pretty-print an OPF file."""
    try:
        with open(opf_path, "r", encoding="utf-8") as f:
            xml = f.read()
        dom = minidom.parseString(xml)
        return dom.toprettyxml(indent="  ")
    except Exception as e:
        return "Error: %s: %s" % (type(e).__name__, e)
