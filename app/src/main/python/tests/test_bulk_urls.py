"""Offline bulk bridge contracts, exercised with the real bundled adapters."""
from contextlib import ExitStack, redirect_stdout, redirect_stderr
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fanficfare_bridge as bridge


class BulkUrlTests(unittest.TestCase):
    def setUp(self):
        # Deny both DNS and transport; keep real adapter construction/validation.
        self.network_attempts = []

        def deny_network(*args, **kwargs):
            self.network_attempts.append(True)
            raise AssertionError("Bulk URL parsing attempted network access")

        for target in ("socket.socket.connect", "socket.getaddrinfo"):
            guard = patch(target, side_effect=deny_network)
            guard.start()
            self.addCleanup(guard.stop)

    def tearDown(self):
        self.assertEqual(self.network_attempts, [], "No network attempts allowed")

    def parse(self, text, normalize=True):
        self.assertTrue(hasattr(bridge, "parse_story_url_text"),
                        "Missing parse_story_url_text bridge API")
        return json.loads(bridge.parse_story_url_text(text, normalize))

    def test_invalid_and_unsupported_tokens_are_counted_not_returned(self):
        invalid = [
            "not-a-url", "file:///etc/passwd", "ftp://archiveofourown.org/works/123",
            "javascript:alert(1)", "https://unknown.example/story?id=1",
            "https://archiveofourown.org/", "https://archiveofourown.org/works/nope",
            "https://archiveofourown.org.evil.example/works/123",
            "https://user:private@archiveofourown.org/works/123",
            "https://archiveofourown.org:99999/works/123",
            "https://archiveofourown.org/works/123%ZZ",
            "https://archiveofourown.org/works/123\x00",
            "https://[broken/works/123",
        ]
        result = self.parse("\n".join(invalid + ["https://archiveofourown.org/works/123"]))
        self.assertEqual(result, {
            "ok": True, "urllist": ["https://archiveofourown.org/works/123"],
            "duplicates_removed": 0, "invalid_count": len(invalid),
        })

    def test_story_aliases_dedupe_in_first_seen_order_without_normalizing_output(self):
        urls = [
            "http://ao3.org/works/0012345/chapters/67890?view_adult=true#main",
            "https://archiveofourown.org/works/12345",
            "https://archiveofourown.com/collections/Examples/works/12345",
            "http://m.fanfiction.net/s/9876/7/Old-Title",
            "https://www.fanfiction.net/s/9876/1/New-Title",
            "https://www.fanfiction.net/s/9877/1/New-Title",
        ]
        result = self.parse(" ".join(urls), normalize=False)
        self.assertEqual(result, {
            "ok": True, "urllist": [urls[0], urls[3], urls[5]],
            "duplicates_removed": 3, "invalid_count": 0,
        })
        result = self.parse("\n".join(urls))
        self.assertEqual(result["urllist"], [
            "https://archiveofourown.org/works/12345",
            "https://www.fanfiction.net/s/9876/1/Old-Title",
            "https://www.fanfiction.net/s/9877/1/New-Title",
        ])
        self.assertEqual(result["duplicates_removed"], 3)

    def canonical(self, urls):
        self.assertTrue(hasattr(bridge, "canonical_story_urls"),
                        "Missing canonical_story_urls bridge API")
        return json.loads(bridge.canonical_story_urls(json.dumps(urls)))

    def test_canonical_api_shares_parser_identity_for_known_stories(self):
        urls = [
            "http://ao3.org/works/123/chapters/456",
            "https://archiveofourown.org/works/123?view_full_work=true",
            "http://m.fanfiction.net/s/987/2/Old-Title",
            "https://www.fanfiction.net/s/987/1/New-Title",
        ]
        result = self.canonical(urls)
        self.assertTrue(result["ok"], result)
        self.assertEqual(set(result["keys"]), set(urls))
        self.assertEqual(result["keys"][urls[0]], result["keys"][urls[1]])
        self.assertEqual(result["keys"][urls[2]], result["keys"][urls[3]])
        self.assertNotEqual(result["keys"][urls[0]], result["keys"][urls[2]])

    def test_unknown_http_identity_preserves_query_path_case_and_trailing_slash(self):
        urls = [
            "HTTPS://Unknown.Example:443/Story?sid=ABC&x=1#chapter",
            "https://unknown.example/Story?sid=ABC&x=1#other",
            "https://unknown.example/Story?sid=abc&x=1",
            "https://unknown.example/story?sid=ABC&x=1",
            "https://unknown.example/Story/?sid=ABC&x=1",
            "https://unknown.example/Story?x=1&sid=ABC",
            "https://unknown.example/Story?sid=XYZ&x=1",
            "https://unknown.example:8443/Story?sid=ABC&x=1",
            "http://unknown.example/Story?sid=ABC&x=1",
        ]
        result = self.canonical(urls)
        self.assertTrue(result["ok"], result)
        keys = result["keys"]
        self.assertEqual(keys[urls[0]], "https://unknown.example/Story?sid=ABC&x=1")
        self.assertEqual(keys[urls[0]], keys[urls[1]])
        self.assertEqual(len(set(keys.values())), len(urls) - 1)

    def test_resource_limits_fail_explicitly_without_partial_results(self):
        url = "https://archiveofourown.org/works/123"
        for text, message in ((" " * (1024 * 1024 + 1), "1 MiB"),
                              ("\u00e9" * (512 * 1024 + 1), "1 MiB"),
                              ((url + "\n") * 5001, "5000")):
            with self.subTest(message=message, length=len(text)):
                result = self.parse(text)
                self.assertFalse(result["ok"], result)
                self.assertIn(message, result["error"])
                self.assertEqual(result["exception_type"], "ValueError")
                self.assertIn("Traceback (most recent call last):", result["detail"])
                self.assertNotIn("urllist", result)
        for urls, message in (([url] * 5001, "5000"),
                              (["https://unknown.example/" + "x" * 8192], "8192"),
                              (["x" * (1024 * 1024)], "1 MiB")):
            with self.subTest(canonical_limit=message):
                result = self.canonical(urls)
                self.assertFalse(result["ok"], result)
                self.assertIn(message, result["error"])
                self.assertNotIn("keys", result)
        too_long = "https://www.literotica.com/s/" + "x" * 8192
        result = self.parse(too_long + " " + url)
        self.assertEqual(result, {"ok": True, "urllist": [url],
                                  "invalid_count": 1, "duplicates_removed": 0})

    def test_bridge_rejects_wrong_types_instead_of_coercing_them(self):
        for value in (None, 12, [], b"https://archiveofourown.org/works/123"):
            with self.subTest(text=value):
                result = self.parse(value)
                self.assertFalse(result["ok"], result)
                self.assertEqual(result["exception_type"], "TypeError")
                self.assertIn("Traceback (most recent call last):", result["detail"])
        for value in ("false", 0, None):
            with self.subTest(normalize=value):
                result = self.parse("https://archiveofourown.org/works/123", value)
                self.assertFalse(result["ok"], result)
                self.assertEqual(result["exception_type"], "TypeError")
        for value in ({}, {"https://archiveofourown.org/works/123": "ignored"},
                      "https://archiveofourown.org/works/123", None, [None], [123]):
            with self.subTest(canonical=value):
                result = self.canonical(value)
                self.assertFalse(result["ok"], result)
                self.assertEqual(result["exception_type"], "TypeError")
                self.assertIn("Traceback (most recent call last):", result["detail"])
                self.assertNotIn("keys", result)

    def test_query_story_ids_are_not_collapsed(self):
        urls = ["https://tgstorytime.com/viewstory.php?sid=123",
                "https://tgstorytime.com/viewstory.php?sid=456"]
        result = self.parse("\n".join(urls + [urls[0]]))
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["urllist"], [url.replace("https:", "http:") for url in urls])
        self.assertEqual(result["duplicates_removed"], 1)
        self.assertEqual(result["invalid_count"], 0)
        keys = self.canonical(urls)["keys"]
        self.assertNotEqual(keys[urls[0]], keys[urls[1]])

    def test_literotica_slugs_and_languages_stay_distinct_offline(self):
        urls = ["https://www.literotica.com/s/example-story",
                "https://www.literotica.com/s/example-story-ch-01",
                "https://www.literotica.com/s/example-story-ch-02",
                "https://german.literotica.com/s/example-story",
                "https://www.literotica.com/s/Example-Story"]
        result = self.parse(" ".join(urls))
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["urllist"], urls)
        self.assertEqual(result["duplicates_removed"], 0)
        keys = self.canonical(urls)["keys"]
        self.assertEqual(len(set(keys.values())), len(urls))

    def test_ao3_chapter_only_url_cannot_be_inferred_as_a_work_offline(self):
        urls = ["http://ao3.org/chapters/456",
                "https://archiveofourown.org/chapters/456",
                "https://archiveofourown.org/works/123/chapters/456"]
        result = self.parse("\n".join(urls))
        self.assertEqual(result, {
            "ok": True, "urllist": ["https://archiveofourown.org/chapters/456",
                                     "https://archiveofourown.org/works/123"],
            "duplicates_removed": 1, "invalid_count": 0,
        })

    def test_empty_input_and_comment_only_input_succeed(self):
        for text in ("", " \n\ufeff".lstrip(), "\ufeff# comment\n  # second comment\n\n"):
            with self.subTest(text=text):
                result = self.parse(text)
                self.assertEqual(result, {"ok": True, "urllist": [],
                                          "duplicates_removed": 0, "invalid_count": 0})
        self.assertEqual(self.canonical([]), {"ok": True, "keys": {}})

    def test_invalid_canonical_url_and_json_return_full_tracebacks(self):
        for url in ("file:///etc/passwd", "https://[invalid/path", "not-a-url",
                    "https://user:private@archiveofourown.org/works/123"):
            with self.subTest(url=url):
                result = self.canonical([url])
                self.assertFalse(result["ok"], result)
                self.assertEqual(result["exception_type"], "ValueError")
                self.assertIn("Traceback (most recent call last):", result["detail"])
                self.assertNotIn("private", result["detail"])
                self.assertNotIn("keys", result)
        result = json.loads(bridge.canonical_story_urls("[not json"))
        self.assertFalse(result["ok"], result)
        self.assertEqual(result["exception_type"], "JSONDecodeError")
        self.assertIn("Traceback (most recent call last):", result["detail"])

    def test_fragments_do_not_hide_supported_story_identity(self):
        urls = ["http://m.fanfiction.net/s/123/8/Title#chapter",
                "https://www.fanfiction.net/s/123/1/Other-Title"]
        result = self.parse(" ".join(urls))
        self.assertEqual(result, {
            "ok": True, "urllist": ["https://www.fanfiction.net/s/123/1/Title"],
            "duplicates_removed": 1, "invalid_count": 0,
        })
        result = self.canonical(urls)
        self.assertTrue(result["ok"], result)
        self.assertEqual(len(set(result["keys"].values())), 1)

    def test_exact_limits_are_accepted(self):
        url = "https://archiveofourown.org/works/123"
        result = self.parse((url + "\n") * 5000)
        self.assertEqual(result, {"ok": True, "urllist": [url],
                                  "duplicates_removed": 4999, "invalid_count": 0})
        self.assertEqual(self.canonical([url] * 5000), {"ok": True, "keys": {url: url}})
        result = self.parse("#" + " " * (1024 * 1024 - 1))
        self.assertEqual(result, {"ok": True, "urllist": [],
                                  "duplicates_removed": 0, "invalid_count": 0})
        prefix = "https://www.literotica.com/s/"
        url = prefix + "x" * (8192 - len(prefix))
        self.assertEqual(self.parse(url)["urllist"], [url])

    def test_no_metadata_page_fetch_or_personal_configuration_is_used(self):
        targets = (
            "fanficfare.adapters.base_adapter.BaseSiteAdapter.getStoryMetadataOnly",
            "fanficfare.adapters.base_adapter.BaseSiteAdapter.getStory",
            "fanficfare.geturls.get_urls_from_page",
            "fanficfare.configurable.Configuration.get_fetcher",
            "fanficfare.configurable.Configuration.read",
            "fanficfare_config.build_configuration",
        )
        with ExitStack() as stack:
            guards = [stack.enter_context(patch(target, side_effect=AssertionError(target)))
                      for target in targets]
            urls = ["https://archiveofourown.org/works/123",
                    "https://www.fanfiction.net/s/456/2/Title",
                    "https://www.literotica.com/s/example-story"]
            self.assertTrue(self.parse("\n".join(urls))["ok"])
            self.assertTrue(self.canonical(urls)["ok"])
            for guard in guards:
                guard.assert_not_called()

    def test_credentials_are_rejected_without_logging_them(self):
        url = "https://private-user:private-password@archiveofourown.org/works/123"
        captured = io.StringIO()
        with redirect_stdout(captured), redirect_stderr(captured), \
                self.assertLogs("fanficfare", level="DEBUG") as logs:
            self.assertEqual(self.parse(url)["invalid_count"], 1)
            result = self.canonical([url])
            self.assertFalse(result["ok"])
        emitted = captured.getvalue() + "\n".join(logs.output) + json.dumps(result)
        self.assertNotIn("private-user", emitted)
        self.assertNotIn("private-password", emitted)

    def test_unexpected_engine_failure_retains_the_full_traceback(self):
        with patch("fanficfare.adapters.getAdapter", side_effect=RuntimeError("engine failed")):
            for result in (self.parse("https://archiveofourown.org/works/123"),
                           self.canonical(["https://archiveofourown.org/works/123"])):
                self.assertFalse(result["ok"], result)
                self.assertEqual(result["exception_type"], "RuntimeError")
                self.assertEqual(result["error"], "engine failed")
                self.assertIn("Traceback (most recent call last):", result["detail"])
                self.assertIn("RuntimeError: engine failed", result["detail"])

    def test_default_normalizes_while_existing_page_dedupe_remains_unchanged(self):
        result = json.loads(bridge.parse_story_url_text("http://m.fanfiction.net/s/123/8/Title"))
        self.assertEqual(result["urllist"], ["https://www.fanfiction.net/s/123/1/Title"])
        # Bulk helpers must not change the older online page-list contract.
        self.assertEqual(bridge._canonical_dedupe_key("https://example.com/Story?ID=AbC/"),
                         "https://example.com/story?id=abc")

    def test_copied_urls_accept_bom_whitespace_blank_lines_and_comments(self):
        result = self.parse(
            "\ufeff  # copied from reading list\r\n\r\n"
            "http://archiveofourown.org/works/12345/chapters/67890\t"
            "http://m.fanfiction.net/s/9876/2/Example\n"
            "  # https://archiveofourown.org/works/11111\n"
        )
        self.assertEqual(result, {
            "ok": True,
            "urllist": ["https://archiveofourown.org/works/12345",
                        "https://www.fanfiction.net/s/9876/1/Example"],
            "duplicates_removed": 0,
            "invalid_count": 0,
        })


if __name__ == "__main__":
    unittest.main()
