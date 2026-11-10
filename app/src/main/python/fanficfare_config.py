import json
import os
import traceback

SRC_DIR = os.path.dirname(os.path.abspath(__file__))

_ANDROID_CONFIG_DIR = None
_FANFICFARE_AVAILABLE = None
_FANFICFARE_ERROR = None
_FANFICFARE_TRACEBACK = None


def set_config_dir(path):
    global _ANDROID_CONFIG_DIR
    if path:
        _ANDROID_CONFIG_DIR = os.path.abspath(str(path))
    else:
        _ANDROID_CONFIG_DIR = None


def get_personal_ini_path():
    if _ANDROID_CONFIG_DIR:
        return os.path.join(_ANDROID_CONFIG_DIR, "personal.ini")
    return os.path.join(os.path.expanduser("~"), "fanficfare", "personal.ini")


def get_bundled_defaults_path():
    return os.path.join(SRC_DIR, "fanficfare", "defaults.ini")


def build_configuration(url, fileform="epub", overrides=None):
    if not _import_fanficfare():
        raise RuntimeError("FanFicFare not available")
    from fanficfare.configurable import Configuration
    from fanficfare import adapters

    try:
        sections = adapters.getConfigSectionsFor(url)
    except Exception:
        sections = ["test1.com"]

    configuration = Configuration(sections, fileform)

    bundled_defaults = get_bundled_defaults_path()
    if bundled_defaults and os.path.isfile(bundled_defaults):
        try:
            configuration.read(bundled_defaults)
        except Exception:
            pass

    personal_ini = get_personal_ini_path()
    if personal_ini and os.path.isfile(personal_ini):
        try:
            configuration.read(personal_ini)
        except Exception:
            pass

    if overrides:
        for key, value in overrides.items():
            try:
                configuration.set("overrides", key, value)
            except Exception:
                pass

    configuration.addUrlConfigSection(url)

    return configuration


def _safe_config_value(value):
    if value is None:
        return None
    v = str(value).strip()
    if not v:
        return None
    return v


def _count_sections(config):
    try:
        return len(config.sections())
    except Exception:
        pass
    return 0


def _detect_credentials(config):
    credentials_present = False
    sections = []
    try:
        sections = config.sections()
    except Exception:
        pass

    for section in sections:
        try:
            options = []
            try:
                options = config.options(section)
            except Exception:
                pass
            for key in options:
                lowered = key.lower()
                if lowered in {"username", "password", "token", "cookie", "api_key", "apikey"}:
                    val = _safe_config_value(config.get(section, key, fallback=None))
                    if val:
                        credentials_present = True
                        break
            if credentials_present:
                break
        except Exception:
            continue

    return credentials_present


def get_config_status():
    path = get_personal_ini_path()
    exists = bool(path and os.path.isfile(path))
    size = 0
    modified = 0
    parse_error = None
    sections = 0
    credentials_present = False
    configuration_valid = False
    fanficfare_version = None
    initialized = _ANDROID_CONFIG_DIR is not None
    config_dir = _ANDROID_CONFIG_DIR or ""

    if exists:
        try:
            size = os.path.getsize(path)
            st = os.stat(path)
            modified = int(st.st_mtime * 1000)
        except Exception:
            pass

        try:
            cfg = build_configuration("https://test1.com", "epub")
            sections = _count_sections(cfg)
            credentials_present = _detect_credentials(cfg)
            configuration_valid = True
            parse_error = None
        except Exception as e:
            configuration_valid = False
            parse_error = "{}: {}".format(type(e).__name__, e)
    else:
        configuration_valid = False
        parse_error = None

    try:
        fanficfare_version = _get_fanficfare_version()
    except Exception:
        fanficfare_version = None

    status = {
        "ok": True,
        "initialized": initialized,
        "config_dir": config_dir,
        "personal_ini_path": path or "",
        "exists": exists,
        "is_file": exists,
        "size": size,
        "modified": modified,
        "imported": exists,
        "parse_error": parse_error,
        "sections": sections,
        "credentials_present": credentials_present,
        "configuration_valid": configuration_valid,
        "fanficfare_version": fanficfare_version,
        "import_error": _FANFICFARE_ERROR,
        "import_traceback": _FANFICFARE_TRACEBACK,
    }
    return json.dumps(status)


def test_configuration(url):
    try:
        if not url:
            return json.dumps({
                "ok": False,
                "error_code": "CONFIG_ERROR",
                "message": "URL is required",
                "detail": "A story URL is needed to determine site-specific configuration",
            })

        path = get_personal_ini_path()
        personal_exists = bool(path and os.path.isfile(path))

        try:
            cfg = build_configuration(url, "epub")
            sections = []
            try:
                if hasattr(cfg, "sections"):
                    sections = cfg.sections()
                elif hasattr(cfg, "get_sections"):
                    sections = cfg.get_sections()
            except Exception:
                pass

            credentials_present = _detect_credentials(cfg)
            configuration_valid = True
            parse_error = None
        except Exception as e:
            configuration_valid = False
            parse_error = "{}: {}".format(type(e).__name__, e)
            sections = []
            credentials_present = False

        try:
            from fanficfare import adapters
            site = None
            try:
                found = adapters._get_class_for(url)
                if found and found[0]:
                    site = found[0].getSiteDomain()
            except Exception:
                pass

            matched_section = None
            try:
                site_sections = adapters.getConfigSectionsFor(url)
                cfg_for_section = build_configuration(url, "epub")
                for section in site_sections:
                    try:
                        if hasattr(cfg_for_section, "has_section") and cfg_for_section.has_section(section):
                            matched_section = section
                            break
                    except Exception:
                        continue
            except Exception:
                pass

            site_supported = site is not None
        except Exception:
            site = None
            matched_section = None
            site_supported = False

        return json.dumps({
            "ok": True,
            "url": url,
            "personal_ini_exists": personal_exists,
            "personal_ini_path": path or "",
            "configuration_valid": configuration_valid,
            "parse_error": parse_error,
            "sections": sections,
            "credentials_present": credentials_present,
            "site": site,
            "matched_section": matched_section,
            "site_supported": site_supported,
        })
    except Exception as e:
        return json.dumps({
            "ok": False,
            "error_code": "UNKNOWN",
            "message": str(e),
            "detail": "{}: {}".format(type(e).__name__, e),
        })


def _get_fanficfare_version():
    try:
        # Chaquopy may strip __file__, so prefer the imported module namespace.
        import fanficfare.cli as cli_mod
        raw = getattr(cli_mod, "version", None)
        if raw:
            return str(raw)
    except Exception:
        pass
    try:
        import fanficfare as _ff_pkg
        for key in ("__version__", "version", "VERSION"):
            raw = getattr(_ff_pkg, key, None)
            if raw:
                return str(raw)
    except Exception:
        pass
    return None


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
        try:
            log_path = os.path.join(get_personal_ini_path(), "..", "import_error.log")
            log_path = os.path.normpath(log_path)
            with open(log_path, "w") as f:
                f.write("ERROR: " + _FANFICFARE_ERROR + "\n\n")
                f.write(_FANFICFARE_TRACEBACK)
        except Exception:
            pass
        _FANFICFARE_AVAILABLE = False
        return False
