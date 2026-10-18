"""Offline pasted-URL parsing and story identity using the bundled engine.

No personal.ini, metadata, or page requests are used. Resource limits apply to
UTF-8 bytes and to input tokens (including invalid entries), before deduplication.
The bridge wrappers serialize these payloads and expose full exception tracebacks.
"""
import json
import re
from urllib.parse import urlsplit, urlunsplit

from fanficfare import adapters
from fanficfare.configurable import Configuration
from fanficfare.exceptions import InvalidStoryURL, UnknownSite

MAX_INPUT_BYTES = 1024 * 1024
MAX_URLS = 5000
MAX_URL_BYTES = 8192


def _bounded_text(text):
    if not isinstance(text, str):
        raise TypeError("URL input must be a string")
    if len(text) > MAX_INPUT_BYTES or len(text.encode("utf-8")) > MAX_INPUT_BYTES:
        raise ValueError("URL input exceeds the 1 MiB limit")
    return text


def _http_url(url):
    """Validate before adapters can permissively rewrite malformed input."""
    if (not isinstance(url, str) or not url
            or re.search(r'[\s\x00-\x1f\x7f<>"\\\\]', url)
            or re.search(r"%(?![0-9a-fA-F]{2})", url)):
        raise ValueError("Invalid HTTP URL")
    if len(url) > MAX_URL_BYTES or len(url.encode("utf-8")) > MAX_URL_BYTES:
        raise ValueError("URL exceeds the 8192-byte limit")
    parts = urlsplit(url)
    if (parts.scheme.lower() not in ("http", "https") or not parts.hostname
            or parts.username is not None or parts.password is not None):
        raise ValueError("Expected an HTTP(S) URL without credentials")
    # Reading port also validates its syntax and range.
    port = parts.port
    netloc = parts.netloc.lower()
    if port == (443 if parts.scheme.lower() == "https" else 80):
        netloc = netloc.rsplit(":", 1)[0]
    return urlunsplit((parts.scheme.lower(), netloc, parts.path, parts.query, parts.fragment))


def _adapter_key(adapter):
    """Use the engine's story identity, retaining case and query IDs."""
    url = adapter.get_section_url(adapter.url)
    parts = urlsplit(_http_url(url))
    return urlunsplit((parts.scheme, parts.netloc, parts.path, parts.query, ""))


def canonical_story_urls(urls_json):
    """Map a JSON array of URLs to offline story identity keys.

    Known sites use adapter normalization and its section/story identity. Unknown
    HTTP(S) sites retain path case, slash, query case/order and nondefault ports;
    only host/scheme case, default ports and fragments are canonicalized there.
    Invalid HTTP URLs fail the whole request rather than returning partial keys.
    """
    urls = json.loads(_bounded_text(urls_json))
    if not isinstance(urls, list) or any(not isinstance(url, str) for url in urls):
        raise TypeError("Expected a JSON array of URL strings")
    if len(urls) > MAX_URLS:
        raise ValueError("URL input exceeds the 5000-item limit")
    config = Configuration(["test1.com"], "epub", lightweight=True)
    keys = {}
    for url in urls:
        if url in keys:
            continue
        validated = _http_url(url)
        try:
            adapter = adapters.getAdapter(config, validated)
        except (InvalidStoryURL, UnknownSite, ValueError, IndexError):
            parts = urlsplit(validated)
            keys[url] = urlunsplit((parts.scheme, parts.netloc, parts.path, parts.query, ""))
        else:
            keys[url] = _adapter_key(adapter)
    return {"ok": True, "keys": keys}


def parse_story_url_text(text, normalize=True):
    """Parse supported HTTP(S) story URLs, returning first-seen identities.

    Split whitespace within each line; skip blank lines, an initial UTF-8 BOM and
    whole lines starting with # after indentation. Count each invalid/unsupported
    token, but not comments, as invalid_count. Equivalent valid stories contribute
    to duplicates_removed. normalize=False keeps the first original URL spelling;
    True returns that input's adapter.url, never metadata-resolved URLs.

    Over 1 MiB or 5000 input tokens fails atomically. Tokens over 8192 UTF-8 bytes
    are counted as invalid. AO3 chapter-only links cannot be mapped to work IDs
    offline; Literotica chapter-like slugs are never heuristically merged.
    """
    text = _bounded_text(text)
    if not isinstance(normalize, bool):
        raise TypeError("normalize must be a boolean")
    tokens = [url for line in text.lstrip("\ufeff").splitlines()
              if not line.lstrip().startswith("#") for url in line.split()]
    if len(tokens) > MAX_URLS:
        raise ValueError("URL input exceeds the 5000-item limit")
    config = Configuration(["test1.com"], "epub", lightweight=True)
    urls = []
    seen = set()
    accepted_inputs = set()
    duplicates = 0
    invalid = 0
    for url in tokens:
        if url in accepted_inputs:
            duplicates += 1
            continue
        try:
            validated = _http_url(url)
        except ValueError:
            invalid += 1
            continue
        try:
            adapter = adapters.getAdapter(config, validated)
        except (InvalidStoryURL, UnknownSite, ValueError, IndexError):
            invalid += 1
            continue
        key = _adapter_key(adapter)
        accepted_inputs.add(url)
        if key in seen:
            duplicates += 1
            continue
        seen.add(key)
        urls.append(adapter.url if normalize else url)
    return {"ok": True, "urllist": urls,
            "duplicates_removed": duplicates, "invalid_count": invalid}
