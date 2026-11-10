# FanFicFare Embedded Engine

| Field | Value |
|-------|-------|
| embedded engine | 4.63.0 |
| upstream tag | v4.63.0 |
| upstream commit | a7d198ab6a31efc2418108061182210b76d67d98 |
| import/update date | 2026-11-04 |
| upstream release date | 2026-11-02 |
| upstream repository | https://github.com/JimmXinu/FanFicFare |
| app shipping version | Unchanged during this local test batch |

This is a coordinated source migration with deliberate Android/local differences, not a byte-identical upstream package. Implementation, host testing, packaged-code verification, independent review and phone verification are separate gates. A source version field alone proves none of the latter gates.

## Updater warning

**Do not use `tools/update_fanficfare.py` to apply upgrades.** It remains an unaudited legacy helper: the extracted temporary directory goes out of scope before comparison/copying, the preserve list omits real local patches, whole-file preservation is not a merge, and its generated documentation has drifted. This upgrade was merged by reviewed source slices, not that tool. Do not regenerate this document with the legacy helper.

## Retained Android modifications

- `adapters/__init__.py`: excludes the four test-only adapters. Generic page discovery must not depend on test1.com being in the registry.
- `adapters/base_adapter.py`: `getChapterTextNum()` retries once on transient `ChunkedEncodingError`, `ProtocolError` and `IncompleteRead`; retries once after five seconds for HTTP 429/500/502/503/504. Retain this wrapper when merging new base-class code, not the entire old class.
- `fetchers/fetcher_requests.py`: bounded page backoff (`backoff_max=10`, `retry_after_max=60`) via `BoundedRetry`; separate image session/retry policy (`total=1`, `backoff_max=2`, `retry_after_max=2`, default image timeout 15 seconds); per-run memo of failed image hosts; cookie propagation to page and image sessions. The cap survives retry cloning without requiring urllib3's newer `retry_after_max` constructor keyword. Offline checks cover urllib3 2.2.3, 2.7.0 and the app's 2.8.0, not unsupported 1.26.x. The existing `requests_file.FileAdapter` import is intentional: requests-file is declared in the Chaquopy pip block.
- `fetchers/fetcher_cloudscraper.py`: `do_mounts(session, retries=None)` propagates the selected page/image retry policy to both ordinary adapters and the HTTPS `CipherSuiteAdapter`; real offline CloudScraper session creation checks both ladders.
- `diagnostics.py` and scoped engine hooks: bounded app-private JSONL captures operation/chapter/request timings and retry/politeness delays without exception text, headers or bodies. URL queries, fragments, userinfo and recognized credential-path suffixes/assignments are excluded (including overlapping and percent-encoded credential markers). Diagnostic operation setup/teardown failures do not block or repeat the underlying call. Politeness sleeps retain their own completed request's sequence/kind through a scoped capture, not a persistent last-request slot.
- `browsercache/__init__.py` and `browsercache/browsercache_sqldb.py`: optional APSW import guards. No Android APSW wheel is assumed; desktop audit dependencies are not application requirements.
- `adapters/adapter_storiesonlinenet.py`: retains `.get('action', '')` and `urljoin` semantics for missing, empty and relative form actions. Upstream 4.63's list-literal action branch is intentionally not imported; missing-action submission must preserve the redirected WLPC return query.
- `adapters/adapter_royalroadcom.py`: retains rating-tag guards in both layouts (six `stars_tag` occurrences); also normalizes chapter and JSON-LD dates to UTC before comparing naive/aware values. Missing timezone offsets are treated explicitly as UTC.
- `dateutils.py`: retains relative-date debug logging.

Upstream's brotli/brotlidecpy browser-cache fallback is not an Android patch. The previously claimed Literotica is_adult debug patch is not present and is not a preservation requirement.

## 4.63 compatibility and safety differences

### Chapter model and existing EPUBs

`base_adapter.py`, `story.py`, `epubutils.py`, `writers/writer_epub.py` and their callers were migrated together: chapterInfos/get_chapter APIs, mark_new/origchapter/tocchapter fields, JSON fff_chapter_data, preserved URL/title/date metadata, counters and error-placeholder readback.

The prior 4.62-labelled app omitted the base preservation/recheck implementations even though defaults/configuration advertised them. This migration restores them. Preservation and recent edit checking remain opt-in; no library-wide rewrite or silent preference enabling is performed.

Local reader hardening accepts only object-shaped chapter JSON with string URL/title fields; malformed/wrong-shaped JSON falls back to usable legacy tags. Ancient anchor-only chapter URLs remain supported. Sparse preserved metadata uses a safe title/URL fallback instead of indexing a missing origchapter. The separate unnew path also tolerates malformed/sparse JSON.

`updategate.py` rejects an existing zero-recognizable-chapter EPUB before equality/meta-only/force exceptions, unlike the upstream ordering. Preservation rejects conflicting chapter ranges. The Android bridge retains smaller-source protection when preservation is off and rejects a source with zero chapters.

Counter-only rewrite suppression is intentionally not used in the bundled CLI: zero added/updated counters do not prove unchanged chapter titles/order. This may conservatively rewrite an unchanged opted-in CLI update.

### Android update and force behavior

`fanficfare_bridge.update_epub_from_path()` loads old EPUB state before chapter assembly and applies real preservation/recheck predicates. Equal chapter counts do not establish that metadata, chapter titles/order or URL identities are unchanged. It assembles to a sibling candidate and replaces output atomically only after successful assembly. `epub_update_comparison.py` compares all ZIP payloads and recognizes only writer-generated packaging-clock differences: it replays the active metadata-page templates at the prior packaging time and checks the known OPF clock fields and exact empty default log append. Chapter text, order, images, CSS, source dates and real metadata/settings changes remain significant. An unchanged result leaves the original EPUB bytes and mtime untouched; Android skips file replacement and book-row timestamp updates. Unknown or ambiguous custom layouts conservatively rewrite.

Force download remains fresh generation without old-chapter preservation; it is not a workaround for preserving site-deleted content. Explicit Force Download can now recover a missing/unreadable/corrupt EPUB using the saved HTTP(S) story URL from its canonical library row. A usable embedded source retains priority, and only local source-extraction failures select the stored-URL fallback; HTTP/login/generation failures do not trigger a second download or switch URLs. Worker, queue Retry of a force job, and Book Details share this behavior. Recovery preserves the existing book ID, date added and independent source provenance. A missing filename is restored without overwriting a late collision where possible; an unavailable old location falls back to a unique verified copy in the current output folder. Normal Update remains incremental and gives Force Download guidance when the old EPUB cannot be resolved.

### Manual library filters and bounded shelf artwork

Filter data is a user-controlled snapshot. Startup restores cached facets and saved selections without an all-library source-stat/EPUB scan. Downloads, opening filters and Apply do not rebuild it. **Refresh filter data** is available in the library overflow menu and filter dialog; it explicitly rereads local EPUB metadata. New/changed entries are reported as pending. Existing cached facets remain usable; books without cached data are not evaluated by active filters until refresh, and clearing filters shows them immediately. Visible-row completion-status reads do not overwrite the durable filter snapshot.

Explicit refresh stages reads privately and commits the complete current snapshot, including pruning, in one SQLite transaction. A known unreadable-book result does not abort the scan: retain its prior facets where available, otherwise publish explicit unreadable fallback values, and persist a stale/failure marker so a restart does not claim that metadata is fresh. The filter dialog summarizes the failure count and up to five affected titles; diagnostic export includes the sanitized per-book exception. Retrying refresh rereads those books and clears recovered markers. Cancellation, unexpected extraction exceptions, source-identity changes or write failure before commit preserve the prior durable snapshot across restart. An accepted blocking commit completes memory publication even if its waiter is cancelled; newer selections and coordinator ownership remain fenced.

Shelf Room queries explicitly exclude inline `coverData`. Visible artwork is read one row at a time on IO, sampled to a maximum 320-pixel edge, and held in an 8 MiB allocation-byte-bounded thumbnail cache. Original stored covers and EPUB artwork are preserved. Library repository subscriptions are process-owned, rather than accumulating permanent observers as activities are recreated. The library book popup and Book Detail share the same **Open** EPUB launcher.

The supplied phone crash was a Java heap OOM during Room's library observer, at a title-string allocation with a 256 MiB heap limit. This identifies exhaustion, not the single allocation responsible. Lightweight queries, shared subscriptions and bounded artwork address confirmed source-level memory risks; host tests/builds cannot establish on-device heap usage or rule out future Android process eviction.

Successful download/update/force results include `chapter_error_count`, `chapter_added_count`, `chapter_updated_count`, `chapter_written_count` and `warnings`. Saved partial EPUBs return ok=true with explicit warnings. Kotlin persists a bounded allowlisted queue outcome containing these counters and warning summaries, plus skipped/reason/status fields, rather than retaining the full image-bearing bridge response. Bulk queue queries exclude legacy/raw metadata bodies before Room materialization; an idempotent, work-identity-fenced cleanup streams old download outcomes in bounded chunks without changing metadata jobs. Warning rows stay visible in Queue with Remove (not automatic Retry), and Main/Book Detail display warning-aware completion text. A file being saved is not proof that every chapter downloaded. Recent-recheck failures that upstream handles by reusing old content are not reported as failed chapter placeholders.

### Discovery, configuration and request routing

Settings opens without synchronously entering Python or reading full logs. Its Python-debug section directly reads a bounded last-30-line preview on a background worker (32 KiB total across rotations, 1,024 characters per line). A partial entry cut at the byte boundary is omitted rather than exposing an unredactable credential suffix. Configuration status/mutations and DNS diagnostics are also background work with lifecycle/generation-fenced delivery. **Share full diagnostics** in Settings exports the retained log bundle independently of Python. The separate Diagnostics screen keeps its existing last-30-line preview and sharing action.

Offline facet readers retain their safe fixed error results, while optional nonfatal callbacks report the actual read failure class/errno and a safe reason code to engine diagnostics. Raw exception strings, XML and paths are not emitted by this side channel. Android document-open failures are recorded through the sanitized app logger before the established null/failure return; a provider returning no input stream is not accepted as an empty temporary EPUB.

- Bridge list_story_urls/download_story_list use GetURLs and the app's per-URL configuration builder. Preserve cache/cookie sharing during the migration.
- Discovery JSON allowlists URL/dedupe information plus string name/description/status. Raw AO3 storydata contains datetime objects and is not passed to Android. BeautifulSoup descriptions are explicitly converted to strings.
- GetURLs uses a configured Requestable for unknown-domain pages rather than instantiating an intentionally excluded test adapter. Callback-only construction has a safe seed/bootstrap path.
- Configuration validation resolves adapter imports lazily, so importing configurable/requestable/story first does not leave validation with an undefined adapters global.
- New local-file routing and single GET URL-modifier application are included. Normal requests, cloudscraper, FlareSolverr and NSAPA paths remain available. NSAPA's missing binascii import is included.
- Optional upstream QuitDecorator infrastructure is present but no Android job signal is wired. Do not advertise improved cancellation from this engine update.
- Camoufox module imports remain safe for package consistency, but selection and direct constructors reject it explicitly before importing Calibre. No Calibre/browser runtime or new pip dependency was added.

## Site changes included

- RoyalRoad public-chapter filtering based on empty/null subscription tiers, not inaccurate isUnlocked. This is not subscription-content support. Both layouts use corrected chapter dates; legacy description selector is updated. The upstream five-second default request delay is included and personal.ini can override it.
- AO3/OTW ordinary metadata parser and series discovery share the new parser. This runs independently of optional series-page metadata acceleration. A local compatibility correction reads each series anchor's preceding position text rather than its whole parent, retaining distinct positions for works in multiple series. The regression asserts both metadata and actual EPUB title-page XHTML for dd/ul shapes.
- SoFurry detects current successful-login markup.
- fanfics.me selects the word-count-specific link rather than the first size link.
- Fimfiction, Literotica, Syosetu and TtH page/series hooks accept the shared GetURLs context.

## Application configuration and dependencies

All operations continue to use `fanficfare_config.py`. Imported personal.ini stays in app-private writable storage, not the packaged source tree. Download/update/force embed chapter images and covers by default; the existing `download_chapter_images:false` override retains cover-only behavior. Do not log credentials or imported INI contents.

The Chaquopy pip block is unchanged: beautifulsoup4, chardet, html5lib, html2text, cloudscraper, requests, requests-file, urllib3 and Brotli. Android Python remains 3.12.

The inspected baseline APK contains certifi 2026.7.22 and urllib3 2.8.0. Upstream's desktop-bundled certifi 2026.10.23 update does not automatically update Android's transitive package; no trust-store upgrade is claimed for this source port.

## Verification and repeatable update procedure

1. Pin actual upstream tag/SHA; compare notes AND complete commit/file delta against the previous imported tag and app source.
2. Preserve a tracked-source archive, source hashes and signed known-good debug APK before edits. Inventory local behavior and dependencies, not just version strings or preserve lists.
3. Write behavioral RED→GREEN regression tests through real engine/bridge boundaries; merge coherent slices and keep local safeguards.
4. Run unittest-compatible tests, focused pytest suites and procedural EPUB runners separately. Do not interpret missing pytest fixtures in procedural runners as application regressions.
5. Run Kotlin/storage/queue/cache/organisation runners and `./gradlew :app:compileDebugKotlin :app:lintDebug :app:assembleDebug`; compare failures with baseline. Do not clean healthy incremental outputs by default.
6. Inspect the actual APK signature/package/checksum and Python 3.12 code in assets/chaquopy/app.imy. Compare packaged function semantics to current source, not just the presence of a version string.
7. Obtain independent correctness/data-safety review. Then deliver a cumulative uncommitted debug APK with everyday-flow and copied-book update-edge phone checklists. Track automated PASS separately from user PASS.
8. No commit/push until the user's final shipping approval. Apply the app version bump in that shipping commit; never hand-edit derived versionCode.

The upgrade adds test_upstream_chapter_roundtrip, test_upstream_update_policy, test_upstream_bridge_discovery, test_upstream_discovery, test_upstream_fetch_routing, test_upstream_site_parsers and test_upstream_patch_integrity. StoriesOnline form-action regression tests remain in tests/test_storiesonline_login.py. Kotlin warning checks run with tools/run_download_warning_tests.py.

Local implementation evidence and pending device checks are maintained outside this source tree in workspace `research/aharana/upstream-463/implementation-checklist.md`. Do not mark phone or release gates passed because the host build succeeds.

## Known limitations and deferred scope

- APSW browser SQLite cache and Calibre/Camoufox runtime are unavailable on Android.
- Test adapters remain excluded.
- No new cancellation signal, series-page metadata acceleration or remove-new-marks UI was added.
- RoyalRoad legacy parsing may return zero public chapters where redesign raises an error; callers must keep zero-source protection.
- Existing upstream stderr logging can still include raw exception text and URLs (for example chapter-error tracebacks in `adapters/base_adapter.py` and failed-image messages in `story.py`); the structured diagnostics' payload exclusions are not a claim that all upstream stderr is share-safe. requests-file host resource warnings also remain.
- Device behavior, live authenticated sites and actual Room process-restart warning persistence require phone checks; host fixtures do not establish them.
