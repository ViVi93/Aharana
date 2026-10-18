import json
import os
import re
import sys
import tempfile
import time
import traceback
import zipfile
import xml.etree.ElementTree as ET

SRC_DIR = os.path.dirname(os.path.abspath(__file__))
if SRC_DIR not in sys.path:
    sys.path.insert(0, SRC_DIR)

from fanficfare_config import (
    get_config_status,
    get_personal_ini_path,
    get_bundled_defaults_path,
    build_configuration,
    set_config_dir as _cfg_set_config_dir,
    test_configuration as _cfg_test_configuration,
    _get_fanficfare_version as _cfg_get_fanficfare_version,
)

_FANFICFARE_AVAILABLE = None
_FANFICFARE_ERROR = None
_FANFICFARE_TRACEBACK = None
_DOWNLOAD_DEBUG_PATH = os.path.join(tempfile.gettempdir(), "fanficfare_download_debug.log")


def _download_debug_write(line):
    try:
        with open(_DOWNLOAD_DEBUG_PATH, "a", encoding="utf-8") as f:
            f.write("[{}] {}\n".format(time.strftime("%H:%M:%S"), line))
    except Exception:
        pass


def _download_debug_clear():
    try:
        with open(_DOWNLOAD_DEBUG_PATH, "w", encoding="utf-8") as f:
            f.write("")
    except Exception:
        pass


def _download_debug_read():
    try:
        with open(_DOWNLOAD_DEBUG_PATH, "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return ""


def _chapter_images_enabled(configuration, default=True):
    """Whether this download should embed chapter images.

    Android patch companion to the ``include_images`` handling in
    ``download_story()``.  Chapter images are wanted content, but they are also
    the dominant cost of a download, so a site can be opted out with
    ``download_chapter_images:false`` (any section) in personal.ini - that gets
    the cover only, matching the update/force paths.

    Unknown keys return ``default``; an explicit config value wins in either
    direction.
    """
    truthy = ("true", "yes", "1", "on")
    falsy = ("false", "no", "0", "off")
    try:
        sections = configuration.sections()
    except Exception:
        return default
    for section in sections:
        for key in ("download_chapter_images", "include_images"):
            try:
                value = str(configuration.get(section, key, fallback="")).strip().lower()
            except Exception:
                continue
            if value in truthy:
                return True
            if value in falsy:
                return False
    return default


def _apply_image_setting(configuration):
    """Apply the chapter-image preference to a built configuration.

    Android patch shared by download/update/force: images are wanted content and
    are embedded by default ('true' - which is also what makes the cover load).
    ``download_chapter_images:false`` in personal.ini (any section) falls back to
    cover-only, which is much faster on image-heavy or dead-image-host stories.
    """
    configuration.set(
        "overrides",
        "include_images",
        "true" if _chapter_images_enabled(configuration) else "coveronly",
    )
    return configuration


def _exception_http_status(exc):
    """Return the HTTP status behind a fetch failure, or None.

    ``fanficfare.exceptions.HTTPErrorFFF`` carries ``status_code``;
    ``requests.exceptions.HTTPError`` carries it on the response.  The Kotlin
    worker uses this to decide whether re-running the whole story fetch is worth
    it (transient 5xx / 429 from a rate limiter) instead of surfacing a failure.
    """
    status = getattr(exc, "status_code", None)
    if isinstance(status, int):
        return status
    response = getattr(exc, "response", None)
    if response is not None:
        status = getattr(response, "status_code", None)
        if isinstance(status, int):
            return status
    return None


def _write_story_to_output(writer, outpath, **kwargs):
    """Run ``writer.writeStory`` into ``outpath`` atomically.

    The writer streams into whatever file object it is given, so a failure part
    way through used to leave a truncated EPUB behind in the output directory.
    Write to a sibling temp file and rename on success; remove it on failure.
    """
    tmpfd, tmppath = tempfile.mkstemp(
        dir=os.path.dirname(outpath) or ".", prefix=".fff-", suffix=".epub.part"
    )
    try:
        with os.fdopen(tmpfd, "wb") as out:
            writer.writeStory(outstream=out, **kwargs)
        os.replace(tmppath, outpath)
    except Exception:
        try:
            os.remove(tmppath)
        except Exception:
            pass
        raise


def _import_fanficfare():
    global _FANFICFARE_AVAILABLE, _FANFICFARE_ERROR, _FANFICFARE_TRACEBACK
    if _FANFICFARE_AVAILABLE is not None:
        return _FANFICFARE_AVAILABLE
    try:
        from fanficfare import adapters
        from fanficfare.configurable import Configurable
        from fanficfare import writers
        from fanficfare.epubutils import get_dcsource_chaptercount, get_update_data, get_cover_img
        _FANFICFARE_AVAILABLE = True
        return True
    except Exception as e:
        _FANFICFARE_ERROR = "{}: {}".format(type(e).__name__, e)
        _FANFICFARE_TRACEBACK = traceback.format_exc()
        print("FANFICFARE_IMPORT_ERROR: " + _FANFICFARE_ERROR)
        print(_FANFICFARE_TRACEBACK)
        _FANFICFARE_AVAILABLE = False
        return False


def diagnose_fanficfare_imports():
    global _FANFICFARE_ERROR, _FANFICFARE_TRACEBACK
    results = []
    tests = [
        ("import requests", "import requests"),
        ("import requests_file", "import requests_file"),
        ("import bs4", "import bs4"),
        ("import fanficfare", "import fanficfare"),
        ("from fanficfare import adapters", "from fanficfare import adapters"),
        ("from fanficfare.configurable import Configurable", "from fanficfare.configurable import Configurable"),
        ("from fanficfare import writers", "from fanficfare import writers"),
        ("from fanficfare.epubutils import get_dcsource_chaptercount, get_update_data, get_cover_img", "from fanficfare.epubutils import get_dcsource_chaptercount, get_update_data, get_cover_img"),
        ("import apsw", "import apsw"),
    ]
    for label, stmt in tests:
        try:
            exec(stmt, globals())
            results.append({"test": label, "ok": True})
        except Exception as e:
            tb = traceback.format_exc()
            results.append({
                "test": label,
                "ok": False,
                "error_type": type(e).__name__,
                "error": str(e),
                "traceback": tb,
            })
    return json.dumps({"ok": True, "results": results})


def get_fanficfare_version():
    try:
        return _cfg_get_fanficfare_version() or ""
    except Exception:
        return ""


def set_config_dir(path):
    try:
        _cfg_set_config_dir(path)
    except Exception:
        pass


def test_configuration(url):
    try:
        return _cfg_test_configuration(url)
    except Exception as e:
        return json.dumps({
            "ok": False,
            "error_code": "UNKNOWN",
            "message": str(e),
            "detail": "{}: {}".format(type(e).__name__, e),
        })


def get_login_status(url):
    try:
        from fanficfare import adapters
        sections = adapters.getConfigSectionsFor(url)
        configuration = build_configuration(url, "epub")
        username_present = False
        password_present = False
        matched_section = None
        login_keys = []
        always_login = None
        for section in sections:
            try:
                if configuration.has_section(section):
                    matched_section = section
                    options = configuration.options(section)
                    for key in options:
                        lowered = key.lower()
                        login_keys.append(key)
                        if lowered == "username":
                            username_present = bool(configuration.get(section, key, fallback=None))
                        elif lowered == "password":
                            password_present = bool(configuration.get(section, key, fallback=None))
                if username_present and password_present:
                    break
            except Exception:
                pass
        try:
            always_login = configuration.get("defaults", "always_login", fallback=None)
        except Exception:
            pass
        site = None
        try:
            found = adapters._get_class_for(url)
            if found and found[0]:
                site = found[0].getSiteDomain()
        except Exception:
            pass
        return json.dumps({
            "ok": True,
            "site": site,
            "sections": sections,
            "matched_section": matched_section,
            "username_present": username_present,
            "password_present": password_present,
            "always_login": always_login,
            "login_keys": login_keys,
        })
    except Exception as e:
        return json.dumps({
            "ok": False,
            "error": "{}: {}".format(type(e).__name__, e),
            "sections": [],
            "personal_exists": os.path.isfile(get_personal_ini_path()) if get_personal_ini_path() else False,
        })


def get_literotica_config_status(url):
    try:
        from fanficfare import adapters
        sections = adapters.getConfigSectionsFor(url)
        configuration = build_configuration(url, "epub")
        adapter = adapters.getAdapter(configuration, url)
        cfg_value = adapter.getConfig("is_adult", default=False)
        raw_value = None
        matched_section = None
        for section in sections:
            try:
                if configuration.has_section(section):
                    raw_value = configuration.get(section, "is_adult", fallback=None)
                    matched_section = section
                    break
            except Exception:
                continue
        return json.dumps({
            "ok": True,
            "sections": sections,
            "matched_section": matched_section,
            "key": "is_adult",
            "raw_present": raw_value is not None,
            "raw_value": raw_value,
            "configuration_value": cfg_value,
        })
    except Exception as e:
        return json.dumps({"ok": False, "error": "{}: {}".format(type(e).__name__, e)})


def list_sites():
    if not _import_fanficfare():
        return json.dumps({"ok": False, "error": "FanFicFare not available", "detail": _FANFICFARE_ERROR or ""})
    try:
        from fanficfare import adapters
        sites = []
        for site, examples in adapters.getSiteExamples():
            sites.append({"site": site, "examples": examples[:3]})
        return json.dumps({"ok": True, "sites": sites})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})


def get_metadata(url):
    if not _import_fanficfare():
        return json.dumps({"ok": False, "error": "FanFicFare not available", "detail": _FANFICFARE_ERROR or ""})
    try:
        configuration = _apply_image_setting(build_configuration(url, "epub"))
        from fanficfare import adapters
        adapter = adapters.getAdapter(configuration, url)
        adapter.getStoryMetadataOnly()
        return json.dumps({
            "ok": True,
            "title": adapter.story.getMetadata("title") or "",
            "author": adapter.story.getMetadata("author") or "",
            "chapters": adapter.story.getChapterCount(),
        })
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e), "exception_type": type(e).__name__, "http_status": _exception_http_status(e), "detail": traceback.format_exc()})


def download_story(url, outDir):
    if not _import_fanficfare():
        return json.dumps({"ok": False, "error": "FanFicFare not available", "detail": _FANFICFARE_ERROR or ""})
    _download_debug_clear()
    try:
        _download_debug_write("download_story ENTER url={}".format(url))
        from fanficfare import adapters, writers
        _download_debug_write("download_story configuration_start")
        t0 = time.time()
        ## Android patch: images are embedded by default (see
        ## _apply_image_setting) - <img> URLs from dead hosts now fail fast
        ## rather than costing the full retry ladder each (fetcher_requests).
        configuration = _apply_image_setting(build_configuration(url, "epub"))
        _download_debug_write("download_story configuration_ready elapsed={:.3f}s".format(time.time() - t0))
        try:
            cfg = configuration.get("defaults", "is_adult")
        except Exception:
            cfg = None
        _download_debug_write("download_story configuration_is_adult={}".format(cfg))
        t0 = time.time()
        _download_debug_write("download_story adapter_start")
        adapter = adapters.getAdapter(configuration, url)
        _download_debug_write("download_story adapter_ready elapsed={:.3f}s".format(time.time() - t0))
        _download_debug_write("download_story writer_start")
        t0 = time.time()
        writer = writers.getWriter("epub", configuration, adapter)
        _download_debug_write("download_story writer_ready elapsed={:.3f}s".format(time.time() - t0))
        filename = writer.getOutputFileName()
        outpath = os.path.join(outDir, os.path.basename(filename))
        _download_debug_write("download_story fanficfare_call_start")
        t0 = time.time()
        _write_story_to_output(writer, outpath)
        _download_debug_write("download_story fanficfare_call_returned elapsed={:.3f}s".format(time.time() - t0))
        stat = os.stat(outpath)
        meta = _extract_epub_metadata(outpath)
        _download_debug_write("download_story RETURN")
        return json.dumps({
            "ok": True,
            "title": meta.get("title") or adapter.story.getMetadata("title") or filename,
            "author": meta.get("author") or adapter.story.getMetadata("author") or "",
            "chapters": meta.get("chapters") or adapter.story.getChapterCount() or 0,
            "cover": meta.get("cover") or "",
            "url": meta.get("url") or url,
            "path": outpath,
            "modified": int(stat.st_mtime * 1000),
            "size": stat.st_size,
        })
    except Exception as e:
        _download_debug_write("download_story EXCEPTION type={} msg={}".format(type(e).__name__, e))
        return json.dumps({
            "ok": False,
            "error": str(e),
            "exception_type": type(e).__name__,
            "http_status": _exception_http_status(e),
            "detail": traceback.format_exc(),
        })
def _extract_epub_metadata(epubPath):
    title = os.path.basename(epubPath).replace(".epub", "").replace("_", " ")
    author = ""
    url = ""
    chapters = 0
    cover = None

    with zipfile.ZipFile(epubPath, "r") as z:
        names = z.namelist()
        opf_name = None
        for name in names:
            if name.endswith(".opf"):
                opf_name = name
                break
        if opf_name:
            try:
                opf = z.read(opf_name).decode("utf-8", errors="ignore")
                root = ET.fromstring(opf)
                ns = {"dc": "http://purl.org/dc/elements/1.1/", "opf": "http://www.idpf.org/2007/opf/"}
                title_node = root.find(".//dc:title", ns)
                if title_node is not None and title_node.text:
                    title = title_node.text.strip()
                author_node = root.find(".//dc:creator", ns)
                if author_node is not None and author_node.text:
                    author = author_node.text.strip()
                manifest = None
                for elem in root.iter():
                    if elem.tag.endswith("manifest"):
                        manifest = elem
                        break
                # The OPF namespace has no trailing slash: with one, every
                # <meta> written in the OPF's own namespace was invisible here,
                # which is why a merged book's chapter count fell through to the
                # TOC heuristic below (counting section headers and title pages).
                for meta in root.findall(".//{http://purl.org/dc/elements/1.1/}meta") + root.findall(".//{http://www.idpf.org/2007/opf}meta") + root.findall(".//meta"):
                    name_attr = meta.get("name", "") or meta.get("{http://purl.org/dc/elements/1.1/}name", "") or meta.get("{http://www.idpf.org/2007/opf}name", "")
                    content = meta.get("content", "") or ""
                    if "chaptercount" in name_attr.lower() and content.isdigit():
                        chapters = int(content)
                    elif name_attr.lower() in {"calibredit", "calibreurl", "calibrecontributor"}:
                        pass
                    elif name_attr.lower() == "cover":
                        cover_id = content.strip()
                        if cover_id and manifest is not None:
                            for item in manifest:
                                if item.tag.endswith("item") and item.get("id") == cover_id:
                                    href = item.get("href", "")
                                    media_type = item.get("media-type", "")
                                    if href and media_type.startswith("image/"):
                                        try:
                                            data = z.read(href)
                                            cover = "data:%s;base64,%s" % (media_type, __import__("base64").b64encode(data).decode("ascii"))
                                        except Exception:
                                            pass
                                        break
                    elif "url" in name_attr.lower() or "source" in name_attr.lower():
                        if content.startswith("http://") or content.startswith("https://"):
                            url = content.strip()
                if not url:
                    ident = root.find(".//dc:identifier", ns)
                    if ident is not None and ident.text:
                        text = ident.text.strip()
                        if text.startswith("http://") or text.startswith("https://"):
                            url = text
                if not url:
                    source = root.find(".//dc:source", ns)
                    if source is not None and source.text:
                        text = source.text.strip()
                        if text.startswith("http://") or text.startswith("https://"):
                            url = text
            except Exception:
                pass

        if chapters == 0:
            has_title_page = any(name.lower().endswith("/title_page.xhtml") or name.lower() == "oebps/title_page.xhtml" for name in names)
            for name in names:
                if name.lower().endswith(".ncx"):
                    try:
                        content = z.read(name).decode("utf-8", errors="ignore")
                        count = content.lower().count("<navpoint ")
                        if count > 0:
                            chapters = max(0, count - 1) if has_title_page else count
                    except Exception:
                        pass

        if cover is None:
            for name in names:
                lower = name.lower()
                if lower.endswith((".jpg", ".jpeg", ".png", ".gif", ".webp")):
                    base = lower.split("/")[-1].replace(".jpg", "").replace(".jpeg", "").replace(".png", "").replace(".gif", "").replace(".webp", "")
                    if base in {"cover", "coverimage"} or lower.count("/") == 1 and "cover" in base:
                        try:
                            data = z.read(name)
                            mime = "image/jpeg"
                            if lower.endswith(".png"):
                                mime = "image/png"
                            elif lower.endswith(".gif"):
                                mime = "image/gif"
                            elif lower.endswith(".webp"):
                                mime = "image/webp"
                            cover = "data:%s;base64,%s" % (mime, __import__("base64").b64encode(data).decode("ascii"))
                            break
                        except Exception:
                            pass

        if cover is None:
            try:
                manifest = None
                if opf_name:
                    try:
                        opf_bytes = z.read(opf_name)
                        root_for_manifest = ET.fromstring(opf_bytes)
                        for elem in root_for_manifest.iter():
                            if elem.tag.endswith("manifest"):
                                manifest = elem
                                break
                    except Exception:
                        pass
                if manifest is None:
                    for name in names:
                        if name.lower().endswith(".opf"):
                            try:
                                opf_bytes = z.read(name)
                                root_for_manifest = ET.fromstring(opf_bytes)
                                for elem in root_for_manifest.iter():
                                    if elem.tag.endswith("manifest"):
                                        manifest = elem
                                        break
                                if manifest is not None:
                                    break
                            except Exception:
                                pass
                if manifest is not None:
                    for item in manifest:
                        if item.tag.endswith("item"):
                            href = item.get("href", "")
                            media_type = item.get("media-type", "")
                            if media_type.startswith("image/"):
                                try:
                                    data = z.read(href)
                                    cover = "data:%s;base64,%s" % (media_type, __import__("base64").b64encode(data).decode("ascii"))
                                    break
                                except Exception:
                                    pass
            except Exception:
                pass

    return {
        "title": title,
        "author": author,
        "url": url,
        "chapters": chapters,
        "cover": cover,
    }


def _get_dcsource_chaptercount(epubPath):
    try:
        import re
        meta = _extract_epub_metadata(epubPath)
        source = meta.get("url") or ""
        chaptercount = meta.get("chapters") or 0
        if not source:
            with zipfile.ZipFile(epubPath, "r") as z:
                for name in z.namelist():
                    if "calibre" in name.lower() or name.endswith(".txt"):
                        try:
                            content = z.read(name).decode("utf-8", errors="ignore")
                            for line in content.splitlines():
                                if line.startswith("http://") or line.startswith("https://"):
                                    source = line.strip()
                                    break
                            if source:
                                break
                        except Exception:
                            pass
        return source or "", int(chaptercount)
    except Exception:
        pass
    return "", 0


def get_cover_from_epub(epubPath):
    try:
        from fanficfare.epubutils import get_cover_img
        from cover_manager import validate_image_content
        try:
            # The declared OPF cover wins over retained images and ZIP order.
            mime, data = get_cover_img(epubPath)
        except Exception:
            mime, data = None, None
        if data is None:
            # Upstream handles EPUB2 meta covers, but not EPUB3 cover-image
            # properties or URI-escaped hrefs. Resolve those before heuristics.
            import epub_editor
            import posixpath
            from urllib.parse import unquote, urlsplit
            with zipfile.ZipFile(epubPath) as z:
                opf = epub_editor._find_opf_path(z)
                if opf:
                    root = ET.fromstring(z.read(opf))
                    cover_id = epub_editor._find_manifest_cover_item_id(root)
                    item = next((e for e in root.iter() if e.get('id') == cover_id
                                 and e.tag == '{%s}item' % epub_editor.OPF_NS), None)
                    if item is not None:
                        href = urlsplit(item.get('href', ''))
                        if href.scheme or href.netloc:
                            raise ValueError('Cover is not a local EPUB image.')
                        path = posixpath.normpath(posixpath.join(
                            posixpath.dirname(opf), unquote(href.path)))
                        data = z.read(path)
        if data is None:
            mime, data = _get_cover_img(epubPath)
        if data is None:
            return json.dumps({"ok": True, "cover": ""})
        mime = validate_image_content(data)['mime_type']
        encoded = __import__("base64").b64encode(data).decode("ascii")
        return json.dumps({
            "ok": True,
            "cover": "data:%s;base64,%s" % (mime, encoded),
            "original_bytes": len(data),
            "jpeg_bytes": len(data),
        })
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e), "cover": ""})


def _get_cover_img(epubPath):
    try:
        with zipfile.ZipFile(epubPath, "r") as z:
            for name in z.namelist():
                lower = name.lower()
                if lower.endswith((".jpg", ".jpeg", ".png")) and ("/cover" in lower or lower.endswith("/cover.jpg") or lower.endswith("/cover.png")):
                    try:
                        data = z.read(name)
                        mime = "image/png" if lower.endswith(".png") else "image/jpeg"
                        return mime, data
                    except Exception:
                        pass
    except Exception:
        pass
    return None, None


def scan_epub_dir(directory):
    try:
        books = []
        for root, dirs, files in os.walk(directory):
            for f in files:
                # Legacy refresh backups used .epub and share the source URL.
                # Match only the generated tempfile name, not user backup books.
                if re.fullmatch(r'\.story-refresh-backup-[a-z0-9_]{8}\.epub', f):
                    continue
                if f.lower().endswith(".epub"):
                    path = os.path.join(root, f)
                    try:
                        stat = os.stat(path)
                        meta = _extract_epub_metadata(path)
                        meta["path"] = path
                        meta["size"] = stat.st_size
                        meta["modified"] = int(stat.st_mtime * 1000)
                        books.append(meta)
                    except Exception as file_err:
                        print("[FFF-ScanSkip] Skipping corrupt EPUB %s: %s" % (path, file_err))
        return json.dumps({"ok": True, "books": books})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e), "books": []})


def extract_epub_metadata(epubPath):
    try:
        return _extract_epub_metadata(epubPath)
    except Exception as e:
        return {
            "title": os.path.basename(epubPath).replace(".epub", ""),
            "author": "",
            "url": "",
            "chapters": 0,
            "cover": None,
        }


def get_epub_update_url(epubPath):
    try:
        source, chaptercount = _get_dcsource_chaptercount(epubPath)
        return json.dumps({
            "ok": True,
            "url": source or "",
            "chapters": chaptercount or 0,
        })
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})


def delete_epub(epubPath):
    try:
        os.remove(epubPath)
        return json.dumps({"ok": True})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})


def update_epub_from_path(epubPath, outDir):
    if not _import_fanficfare():
        return json.dumps({"ok": False, "error": "FanFicFare not available", "detail": _FANFICFARE_ERROR or ""})
    try:
        from fanficfare.epubutils import get_update_data, get_dcsource_chaptercount
        from fanficfare import adapters, writers
        from fanficfare_config import build_configuration
        source, chaptercount = get_update_data(epubPath)[0:2]
        if not source:
            return json.dumps({"ok": False, "error": "No story URL found in epub"})
        configuration = _apply_image_setting(build_configuration(source, "epub"))
        adapter = adapters.getAdapter(configuration, source)
        url, ch_begin, ch_end = adapters.get_url_chapter_range(source)
        adapter.setChaptersRange(ch_begin, ch_end)
        adapter.getStoryMetadataOnly()
        urlchaptercount = adapter.getStoryMetadataOnly().getChapterCount()
        if chaptercount == urlchaptercount:
            return json.dumps({"ok": True, "skipped": True, "reason": "already current", "url": source, "chapters": chaptercount})
        elif chaptercount > urlchaptercount:
            return json.dumps({"ok": True, "skipped": True, "reason": "local has more chapters than source", "url": source, "chapters": chaptercount})
        elif chaptercount == 0:
            return json.dumps({"ok": False, "error": "existing epub has 0 recognizable chapters"})
        else:
            (_,
             _,
             adapter.oldchapters,
             adapter.oldimgs,
             adapter.oldcover,
             adapter.calibrebookmark,
             adapter.logfile,
             adapter.oldchaptersmap,
             adapter.oldchaptersdata) = get_update_data(epubPath)[0:9]
            writer = writers.getWriter("epub", configuration, adapter)
            filename = writer.getOutputFileName()
            outpath = os.path.join(outDir, os.path.basename(filename))
            _write_story_to_output(writer, outpath, metaonly=False)
            stat = os.stat(outpath)
            meta = _extract_epub_metadata(outpath)
            return json.dumps({
                "ok": True,
                "title": meta.get("title") or adapter.story.getMetadata("title") or filename,
                "author": meta.get("author") or adapter.story.getMetadata("author") or "",
                "chapters": meta.get("chapters") or adapter.story.getChapterCount() or 0,
                "cover": meta.get("cover") or "",
                "url": source,
                "path": outpath,
                "modified": int(stat.st_mtime * 1000),
                "size": stat.st_size,
            })
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e), "exception_type": type(e).__name__, "http_status": _exception_http_status(e)})


def force_download_from_epub(epubPath, outDir):
    if not _import_fanficfare():
        return json.dumps({"ok": False, "error": "FanFicFare not available", "detail": _FANFICFARE_ERROR or ""})
    try:
        from fanficfare.epubutils import get_update_data
        from fanficfare import adapters, writers
        from fanficfare_config import build_configuration
        source = get_update_data(epubPath)[0]
        if not source:
            return json.dumps({"ok": False, "error": "No story URL found in epub"})
        configuration = _apply_image_setting(build_configuration(source, "epub"))
        adapter = adapters.getAdapter(configuration, source)
        url, ch_begin, ch_end = adapters.get_url_chapter_range(source)
        adapter.setChaptersRange(ch_begin, ch_end)
        adapter.getStoryMetadataOnly()
        writer = writers.getWriter("epub", configuration, adapter)
        filename = writer.getOutputFileName()
        outpath = os.path.join(outDir, os.path.basename(filename))
        out_exists_before = False
        out_size_before = None
        out_mtime_before = None
        try:
            if os.path.exists(outpath):
                st = os.stat(outpath)
                out_exists_before = True
                out_size_before = st.st_size
                out_mtime_before = int(st.st_mtime * 1000)
        except Exception:
            pass
        _write_story_to_output(writer, outpath, metaonly=False)
        stat = os.stat(outpath)
        meta = _extract_epub_metadata(outpath)
        out_exists_after = os.path.exists(outpath)
        out_size_after = stat.st_size
        out_mtime_after = int(stat.st_mtime * 1000)
        return json.dumps({
            "ok": True,
            "title": meta.get("title") or adapter.story.getMetadata("title") or filename,
            "author": meta.get("author") or adapter.story.getMetadata("author") or "",
            "chapters": meta.get("chapters") or adapter.story.getChapterCount() or 0,
            "cover": meta.get("cover") or "",
            "url": source,
            "path": outpath,
            "modified": out_mtime_after,
            "size": out_size_after,
            "file": {
                "exists_before": out_exists_before,
                "size_before": out_size_before,
                "mtime_before": out_mtime_before,
                "exists_after": out_exists_after,
                "size_after": out_size_after,
                "mtime_after": out_mtime_after,
                "changed": out_exists_before and (out_size_before != out_size_after or out_mtime_before != out_mtime_after),
            },
        })
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e), "exception_type": type(e).__name__, "http_status": _exception_http_status(e)})


def save_library_index(indexPath, booksJson):
    try:
        with open(indexPath, "w", encoding="utf-8") as f:
            f.write(booksJson)
        return json.dumps({"ok": True})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})


def load_library_index(indexPath):
    try:
        if not os.path.exists(indexPath):
            return json.dumps({"ok": True, "books": []})
        with open(indexPath, "r", encoding="utf-8") as f:
            data = f.read()
        return json.dumps({"ok": True, "books": json.loads(data).get("books", [])})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e), "books": []})


def get_fanficfare_error():
    if _FANFICFARE_ERROR is None:
        return ""
    return str(_FANFICFARE_ERROR)


def clear_download_debug():
    _download_debug_clear()
    return json.dumps({"ok": True})


def read_download_debug():
    text = _download_debug_read()
    return json.dumps({"ok": True, "log": text})


def run_dns_diagnostics():
    print("[fanficfare_bridge] run_dns_diagnostics ENTER")
    try:
        from dns_diagnostic import run_dns_diagnostics
        result = run_dns_diagnostics()
        print("[fanficfare_bridge] run_dns_diagnostics RETURN len={}".format(len(result) if result else 0))
        return result
    except Exception as e:
        msg = "{}: {}".format(type(e).__name__, e)
        print("[fanficfare_bridge] run_dns_diagnostics ERROR {}".format(msg))
        return json.dumps({"ok": False, "error": msg})


def parse_story_url_text(text, normalize=True):
    """Parse pasted story URLs locally, without fetching pages or metadata."""
    try:
        from bulk_urls import parse_story_url_text as parse_urls
        return json.dumps(parse_urls(text, normalize))
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e),
                           "exception_type": type(e).__name__,
                           "detail": traceback.format_exc()})


def canonical_story_urls(urls_json):
    """Return offline identity keys for URLs, including unsupported HTTP URLs."""
    try:
        from bulk_urls import canonical_story_urls as canonical_urls
        return json.dumps(canonical_urls(urls_json))
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e),
                           "exception_type": type(e).__name__,
                           "detail": traceback.format_exc()})


def _dedupe_urls(urls):
    if not urls:
        return []
    seen = {}
    for url in urls:
        key = _canonical_dedupe_key(url)
        if key not in seen or len(url) < len(seen[key]):
            seen[key] = url
    return list(seen.values())


def _canonical_dedupe_key(url):
    url = url.rstrip('/')
    lowered = url.lower()
    if 'archiveofourown.org/works/' in lowered or 'ao3.org/works/' in lowered:
        m = re.search(r'/works/(\d+)', lowered)
        if m:
            return 'ao3_work:' + m.group(1)
    return lowered


def list_story_urls(page_url, normalize=False):
    if not _import_fanficfare():
        return json.dumps({"ok": False, "error": "FanFicFare not available", "detail": _FANFICFARE_ERROR or ""})
    try:
        from fanficfare.geturls import get_urls_from_page
        from fanficfare_config import build_configuration
        _download_debug_write("list_story_urls START url={} normalize={}".format(page_url, normalize))
        configuration = build_configuration(page_url, "epub")
        result = get_urls_from_page(page_url, configuration=configuration, normalize=normalize)
        if not isinstance(result, dict):
            result = {}
        urls = result.get('urllist', []) or []
        urls = _dedupe_urls(urls)
        payload = {"ok": True}
        payload.update(result)
        payload['urllist'] = urls
        payload['duplicates_removed'] = len(result.get('urllist', []) or []) - len(urls)
        _download_debug_write("list_story_urls RETURN count={} deduped_from={}".format(len(urls), len(result.get('urllist', []) or [])))
        return json.dumps(payload)
    except Exception as e:
        _download_debug_write("list_story_urls EXCEPTION {}: {}".format(type(e).__name__, e))
        return json.dumps({"ok": False, "error": str(e), "detail": "{}: {}".format(type(e).__name__, e)})


def normalize_story_urls(page_url):
    return list_story_urls(page_url, normalize=True)


def download_story_list(page_url):
    if not _import_fanficfare():
        return json.dumps({"ok": False, "error": "FanFicFare not available", "detail": _FANFICFARE_ERROR or ""})
    try:
        from fanficfare.geturls import get_urls_from_page
        from fanficfare_config import build_configuration
        _download_debug_write("download_story_list START url={}".format(page_url))
        configuration = build_configuration(page_url, "epub")
        result = get_urls_from_page(page_url, configuration=configuration, normalize=False)
        if not isinstance(result, dict):
            result = {}
        urls = _dedupe_urls(result.get('urllist', []) or [])
        if not urls:
            _download_debug_write("download_story_list RETURN no_urls")
            return json.dumps({"ok": True, "count": 0, "stories": []})
        stories = []
        errors = 0
        for url in urls:
            try:
                meta_raw = get_metadata(url)
                meta = json.loads(meta_raw)
                if meta.get("ok"):
                    stories.append({
                        "url": url,
                        "title": meta.get("title") or "",
                        "chapters": meta.get("chapters") or 0,
                    })
                else:
                    stories.append({"url": url, "title": "", "chapters": 0})
                    errors += 1
            except Exception:
                stories.append({"url": url, "title": "", "chapters": 0})
                errors += 1
        _download_debug_write("download_story_list RETURN count={} errors={}".format(len(stories), errors))
        return json.dumps({"ok": True, "count": len(stories), "errors": errors, "stories": stories})
    except Exception as e:
        _download_debug_write("download_story_list EXCEPTION {}: {}".format(type(e).__name__, e))
        return json.dumps({"ok": False, "error": str(e), "detail": "{}: {}".format(type(e).__name__, e)})


# ---- EPUB Metadata & Cover Editor ----
# Wrapper functions that delegate to the self-contained epub_editor module.
# These provide the same capabilities as calibre's epubmeta tool:
#   - export_opf: extract OPF XML for viewing/editing
#   - import_opf: write back edited OPF XML
#   - read_metadata: read structured metadata fields
#   - write_metadata: edit structured metadata fields
#   - replace_cover: replace the cover image


def _ensure_epub_editor():
    """Import the epub_editor module, adding SRC_DIR to path if needed.
    Raises ImportError if the module cannot be loaded.
    """
    try:
        import epub_editor
        return epub_editor
    except ImportError:
        if SRC_DIR not in sys.path:
            sys.path.insert(0, SRC_DIR)
        import epub_editor
        return epub_editor


def _ensure_epub_merge():
    """Import the epub_merge module, adding SRC_DIR to path if needed."""
    try:
        import epub_merge
        return epub_merge
    except ImportError:
        if SRC_DIR not in sys.path:
            sys.path.insert(0, SRC_DIR)
        import epub_merge
        return epub_merge


def _epub_path_list(paths_json):
    """Accept a JSON array (or newline separated string) of book paths."""
    if isinstance(paths_json, str):
        text = paths_json.strip()
        if text.startswith('['):
            return [p for p in json.loads(text) if p]
        return [line.strip() for line in text.splitlines() if line.strip()]
    return [p for p in (paths_json or []) if p]


def epub_merge_preview(paths_json, base_index=0):
    """Summarise a proposed merge so the UI can confirm before writing."""
    try:
        module = _ensure_epub_merge()
        result = module.preview_merge(_epub_path_list(paths_json), base_index)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def epub_merge_books(paths_json, output_path=None, title=None, author=None,
                     base_index=0, toc_style='sections', shorten_labels=False,
                     renumber_chapters=False):
    """Merge several EPUBs into one new book. Sources are never modified.

    ``toc_style`` is 'sections' (one TOC section per source) or 'flat' (a single
    chapter list, for sets of one-chapter-per-file books). ``shorten_labels``
    strips the prefix the chapter labels share, and ``renumber_chapters`` numbers
    them in reading order.
    """
    try:
        module = _ensure_epub_merge()
        result = module.merge_books(_epub_path_list(paths_json),
                                    output_path=output_path,
                                    title=title or None,
                                    author=author or None,
                                    base_index=base_index,
                                    toc_style=toc_style or 'sections',
                                    shorten_labels=bool(shorten_labels),
                                    renumber_chapters=bool(renumber_chapters))
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def epub_metadata_json(epub_path):
    """Metadata for a single EPUB as JSON (the raw dict is not JSON-serializable
    for the Kotlin side, which needs a string it can parse)."""
    try:
        return json.dumps(_extract_epub_metadata(epub_path))
    except Exception as e:
        return json.dumps({"title": os.path.basename(epub_path).replace(".epub", ""),
                           "author": "", "url": "", "chapters": 0, "cover": None,
                           "error": "%s: %s" % (type(e).__name__, e)})


def export_epub_opf(epub_path, output_path=None):
    """Export OPF XML from an EPUB file for viewing or editing."""
    try:
        module = _ensure_epub_editor()
        result = module.export_opf(epub_path, output_path)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def import_epub_opf(epub_path, opf_path_or_xml, output_path=None, backup_suffix=None):
    """Import OPF XML back into an EPUB file."""
    try:
        module = _ensure_epub_editor()
        result = module.import_opf(epub_path, opf_path_or_xml, output_path, backup_suffix)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def read_epub_metadata(epub_path):
    """Read structured metadata fields from an EPUB's OPF."""
    try:
        module = _ensure_epub_editor()
        result = module.read_metadata_fields(epub_path)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def write_epub_metadata(epub_path, fields_json, output_path=None, backup_suffix=None):
    """
    Write structured metadata fields to an EPUB's OPF.

    Args:
        epub_path: Path to the source EPUB
        fields_json: JSON string of metadata fields dict
        output_path: If provided, write to this path; otherwise modify in-place
        backup_suffix: If provided, create a backup with this suffix
    """
    try:
        fields = json.loads(fields_json) if isinstance(fields_json, str) else fields_json
    except (json.JSONDecodeError, TypeError) as e:
        return json.dumps({"ok": False, "error": "Invalid fields JSON: %s" % e})
    try:
        module = _ensure_epub_editor()
        result = module.write_metadata(epub_path, fields, output_path, backup_suffix)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def replace_epub_cover(epub_path, image_data, image_mime, output_path=None, backup_suffix=None):
    """
    Replace the cover image in an EPUB file.

    Args:
        epub_path: Path to the source EPUB
        image_data: Base64-encoded image data string
        image_mime: MIME type (e.g. "image/jpeg", "image/png")
        output_path: If provided, write to this path; otherwise modify in-place
        backup_suffix: If provided, create a backup with this suffix
    """
    try:
        import base64
        raw_image_data = base64.b64decode(image_data)
        module = _ensure_epub_editor()
        result = module.replace_cover(epub_path, raw_image_data, image_mime, output_path, backup_suffix)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def get_epub_metadata_summary(epub_path):
    """Get a quick metadata summary including OPF path and cover info."""
    try:
        module = _ensure_epub_editor()
        result = module.get_metadata_summary(epub_path)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


# ---------------------------------------------------------------------------
# Phase 7 bridge adapters: metadata preview, cover preview, and apply.
# These are thin wrappers around existing Phase 5 / 6 APIs.  They do NOT
# reimplement or redesign the underlying Python functionality.
# ---------------------------------------------------------------------------

def _ensure_metadata_providers():
    """Import metadata_providers, adding SRC_DIR to path if needed."""
    import metadata_providers
    return metadata_providers

def _ensure_cover_manager():
    """Import cover_manager, adding SRC_DIR to path if needed."""
    import cover_manager
    return cover_manager

def _ensure_metadata_diff():
    """Import metadata_diff, adding SRC_DIR to path if needed."""
    import metadata_diff
    return metadata_diff


def lookup_online_metadata(title, author, isbn):
    """Look up online metadata for a book.

    Delegates to Phase 5 ``metadata_providers.lookup_metadata``.
    Accepts an ISBN-preferred query; falls back to title+author.

    Returns JSON: ``{"ok": true, "results": [...]}`` where each result is
    a serialised ``ProviderResult`` dict with normalised metadata.
    """
    try:
        mp = _ensure_metadata_providers()
        query = mp.MetadataQuery(title=title, author=author, isbn=isbn)
        if not query.is_empty():
            results = mp.lookup_metadata(query)
            serialised = [dict(r) for r in results]
            return json.dumps({"ok": True, "results": serialised})
        return json.dumps({"ok": True, "results": []})
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def diff_metadata(current_json, online_json):
    """Diff two metadata dicts using Phase 3 ``metadata_diff``.

    Args:
        current_json: JSON string of the current EPUB metadata.
        online_json: JSON string of the online metadata.

    Returns JSON: ``{"ok": true, "diff": {...}, "has_changed": bool, "changes": [...]}``.
    """
    try:
        md = _ensure_metadata_diff()
        current = json.loads(current_json) if isinstance(current_json, str) else current_json
        online = json.loads(online_json) if isinstance(online_json, str) else online_json
        diff_result = md.diff_metadata(current, online)
        return json.dumps({
            "ok": True,
            "diff": diff_result.get("diff", {}),
            "has_changed": md.has_changed(diff_result),
            "changes": md.get_changes(diff_result),
        })
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def extract_cover_candidates(results_json):
    """Extract cover candidates from Phase 5 provider results.

    Args:
        results_json: JSON string of a list of ``ProviderResult`` dicts
                      (as returned by ``lookup_online_metadata``).

    Returns JSON: ``{"ok": true, "candidates": [...]}`` where each candidate
    is a serialised ``CoverCandidate`` dict.
    """
    try:
        cm = _ensure_cover_manager()
        results = json.loads(results_json) if isinstance(results_json, str) else results_json
        # ProviderResult is a dict subclass with an .ok property.  When results
        # arrive as JSON from the Android bridge they are plain dicts, so we
        # reconstruct ProviderResult objects to restore the property accessors
        # that cover_manager relies on (e.g. result.ok).
        mp = _ensure_metadata_providers()
        provider_results = []
        for r in results:
            if isinstance(r, mp.ProviderResult):
                provider_results.append(r)
            else:
                provider_results.append(mp.ProviderResult(
                    provider=r.get("provider", ""),
                    metadata=r.get("metadata", {}),
                    source_id=r.get("source_id", ""),
                    source_url=r.get("source_url", ""),
                    error=r.get("error", ""),
                ))
        candidates = cm.extract_cover_candidates(provider_results)
        ranked = cm.rank_cover_candidates(candidates)
        return json.dumps({"ok": True, "candidates": [dict(c) for c in ranked]})
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def download_cover(url, timeout=15):
    """Download and validate a cover image (Phase 6).

    Returns JSON: ``{"ok": true, "image_data": "<base64>", "mime_type": "...",
    "width": N, "height": N, "url": "..."}`` on success, or
    ``{"ok": false, "error": "...", "error_type": "..."}`` on failure.
    """
    cm = None
    try:
        cm = _ensure_cover_manager()
        raw = cm.download_cover(url, timeout=timeout)
        import base64 as _b64
        return json.dumps({
            "ok": True,
            "url": url,
            "image_data": _b64.b64encode(raw["data"]).decode("ascii"),
            "mime_type": raw["mime_type"],
            "width": raw["width"],
            "height": raw["height"],
        })
    except Exception as e:
        error_type = "cover_error"
        if cm is not None:
            if isinstance(e, cm.CoverDownloadTimeout):
                error_type = "timeout"
            elif isinstance(e, cm.CoverDownloadHTTPError):
                error_type = "http_error"
            elif isinstance(e, cm.CoverValidationError):
                error_type = "validation_error"
        return json.dumps({
            "ok": False,
            "error": str(e),
            "error_type": error_type,
        })


def apply_metadata_and_cover(epub_path, fields_json, image_data_base64=None,
                              image_mime=None, output_path=None,
                              backup_suffix=None):
    """Apply metadata (and optionally a cover) to an EPUB in a single atomic write.

    Uses Phase 4.1+ ``epub_editor.write_metadata_and_cover`` which reads the
    EPUB once, applies both metadata and cover changes to the same in-memory
    entry list, and writes the result in a single zip write followed by an
    atomic temp-file rename.  This guarantees the EPUB is never left in a
    partially-written state (e.g. metadata written but cover failed).

    Does NOT auto-apply — the caller (UI) must have received explicit user
    confirmation.

    Returns JSON: ``{"ok": true, "metadata_written": bool,
    "cover_written": bool, "output_path": "...", "changed_fields": [...]}``.
    """
    try:
        module = _ensure_epub_editor()
        fields = json.loads(fields_json) if isinstance(fields_json, str) else fields_json

        # Defensive: reject applying a result that has no metadata fields
        # and no cover image — this catches the case where a failed provider
        # result (empty metadata) is accidentally passed through from the UI.
        if not fields and not (image_data_base64 and image_mime):
            return json.dumps({
                "ok": False,
                "error": "No metadata or cover to apply (result may be a failed provider result)",
                "metadata_written": False,
                "cover_written": False,
            })

        raw_image = None
        if image_data_base64 and image_mime:
            import base64 as _b64
            raw_image = _b64.b64decode(image_data_base64)
            cm = _ensure_cover_manager()
            # Validate before applying — reuse Phase 6 validation
            cm.validate_image_content(raw_image)

        # Single atomic write — metadata + cover applied in one pass
        result = module.write_metadata_and_cover(
            epub_path, fields,
            image_data=raw_image,
            image_mime=image_mime,
            output_path=output_path,
            backup_suffix=backup_suffix,
        )

        return json.dumps({
            "ok": result.get("ok", False),
            "metadata_written": result.get("metadata_written", False),
            "cover_written": result.get("cover_written", False),
            "output_path": result.get("output_path", epub_path),
            "changed_fields": result.get("changed_fields", []),
            "metadata_error": result.get("error", ""),
        })
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def preview_story_refresh(epub_path, include_cover=False):
    """Preview original-site metadata/cover without downloading chapter content."""
    try:
        import story_refresh
        return json.dumps(story_refresh.preview_story_refresh(epub_path, include_cover))
    except Exception as exc:
        return json.dumps({"ok": False, "error": "Story refresh preview failed.",
                           "exception_type": type(exc).__name__,
                           "detail": "The refresh preview could not be processed."})


def apply_story_refresh(epub_path, preview_json, selected_fields_json='[]',
                        apply_cover=False, output_path=None):
    """Apply only selected original-site preview values, without network access."""
    try:
        import story_refresh
        return json.dumps(story_refresh.apply_story_refresh(
            epub_path, preview_json, selected_fields_json, apply_cover, output_path))
    except Exception as exc:
        return json.dumps({"ok": False, "error": "Story refresh apply failed.",
                           "exception_type": type(exc).__name__,
                           "detail": "The refresh selection could not be applied."})


def get_story_details(epub_path):
    """Read saved story status/update date offline, including legacy FFF tags."""
    try:
        from story_metadata import read_story_details
        return json.dumps(read_story_details(epub_path))
    except Exception:
        return json.dumps({"ok": False, "status": "", "date_updated": "",
                           "status_source": "unknown", "date_updated_source": "unknown",
                           "error": "EPUB story details could not be read."})


def get_library_facets(epub_path):
    """Read saved library filter values offline, without modifying the EPUB."""
    try:
        from library_facets import get_library_facets as read_facets
        return json.dumps(read_facets(epub_path))
    except Exception:
        return json.dumps({"ok": False, "error": "Saved book filters could not be read."})
