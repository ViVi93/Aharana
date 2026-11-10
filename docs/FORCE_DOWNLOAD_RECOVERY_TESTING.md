# Force Download — recovery testing batch

Original testing base: `d8df743351cd4e71579633772a8d86ebae7d9d08`, cumulative `1.15-debug` batch. The user subsequently authorized local commit and GitHub publication; the feature batch was committed as `1.16`. A CI compatibility follow-up uses `1.17`: Ubuntu's moving latest runner no longer supplies the configured `/usr/bin/python3.12`, so the workflow now selects Ubuntu 24.04. The debug-artifact verification below records the pre-publication checkpoint. Phone checks remain pending and are not implied by publication.

## Contract

- Explicit **Force Download** can regenerate from the canonical library row's saved story URL if the original EPUB is missing, inaccessible, corrupt, or has no embedded source URL. A readable embedded source retains priority. Do not use an unrelated/stale Activity URL when a positive Room identity is missing.
- Ordinary **Update** remains incremental and requires its original EPUB. When it cannot be resolved, keep the failed queue row and give actionable Force Download guidance rather than a generic retry loop.
- Python fallback applies only to local source extraction. HTTP/login/writer failures are not reclassified as file failures or silently downloaded a second time.
- Keep existing same-book Room identity, date added and independent source provenance. Publish the recovered path only after successful generation and safe file persistence; queue identity/cancellation protections still apply.
- Restore a missing filesystem filename without overwrite when possible. If its old location cannot be used, save a unique verified copy in the configured output folder. A target that appears during download must not be overwritten as if it were the missing original. Existing picked documents retain the established save-new-copy behavior.
- Preserve original bytes and library metadata on generation failure. Use staged writes, never write EPUB ZIPs in place. Do not clear filter data or trigger automatic all-library metadata scans.

## Host verification

- PASS: observed RED before Kotlin implementation, GREEN after. `tests/test_force_download_recovery.py` exercises real JVM filesystem recovery, path collisions (including late arrival), original preservation, failed saves, cancellation propagation, existing document-copy routing, actual production BookEntity copy expressions, and Android source wiring.
- PASS: `python tools/run_download_storage_tests.py` — staged replacement, same-path handling, failed/short copies, author-folder paths, document copies, source provenance, and worker/detail storage exclusion.
- PASS: `python tools/run_queue_policy_tests.py` — lifecycle/cancellation, same-row Retry, identity-fenced SQL and worker wiring.
- PASS: `python tools/run_story_refresh_tests.py` — existing manual-filter/coordinator and metadata behavior retained.
- PASS: 63 Python fallback cases using the real bundled EPUB reader/writer with offline adapter boundaries, covering missing/corrupt/unreadable/source-less originals, valid and invalid stored URLs, embedded-source priority, two-argument compatibility, safe persisted fallback diagnostics, original preservation, and no source switching/restart after configuration/HTTP/login/writer failures.
- PASS: cumulative project-venv root unittest suite (63 tests) and selected Python suite (611 tests + 243 subtests).
- Review finding repaired: an explicit empty bridge cover could erase cached Room artwork. Worker Update/Force and the shared Details copy now retain the old cover for absent/empty/whitespace values and accept nonblank new art. The actual production copy-expression test was observed RED with explicit empty-cover payloads before the fix, then GREEN for all three branches and all four payload cases. Manual cover-removal actions are unchanged.
- PASS: cumulative `assembleDebug` and `lintDebug`; warnings remain but no lint errors. The new nullable-parent compiler warning was removed with an explicit parent guard; force/storage regressions passed again afterward.
- PASS: actual APK Python bytecode has the optional third fallback argument and source-validation helper; DEX verifies both Worker and Details call safe Force persistence and the Kotlin bridge accepts the stored URL. All prior Settings/filter/queue/memory payload checks remain present, source hashes match and the debug signing certificate matches the prior delivered APK.
- Current rebuilt/verified artifact: `Aharana-debug-force-recovery-20261110T030247Z.apk`, SHA-256 `d6232c1da3c1918cf1123d76496c8e9f429479691acd370d6fbd4f0abf9e0e05`. Includes cover-preservation correction. Canonical output remains `app/build/outputs/apk/debug/app-debug.apk`; prior force-recovery artifacts are superseded.
- PASS: independent narrow cover-preservation follow-up review confirms missing/empty/whitespace cover retention and nonblank replacement across Worker Update/Force and shared Details; all three recovery tests passed. No remaining scoped review blocker. Ready for phone testing; no commit or push made.

Host tests do not establish Android storage-provider behavior, network success on the phone, or process-death recovery of every cross-file/database boundary.

## Phone checks — pending

Use a disposable copied/imported book with a working stored story URL; retain the original elsewhere.

1. **Library popup:** make its test EPUB unavailable, then choose Force Download. Confirm a readable book is restored without resharing the URL, no duplicate library card is created, and its date added remains unchanged.
2. **Book Details:** repeat through the Force Download button, including reopening details after any path change.
3. **Unreadable metadata:** use a corrupt disposable EPUB with its saved URL retained. Confirm force recovery works; a normal readable EPUB still uses its embedded source.
4. **Failure preservation:** with network unavailable or a bad/unreachable story URL, confirm failure remains actionable and the previous book/library entry is not removed or reset.
5. **Normal Update:** a missing EPUB should produce Force Download guidance, not silently change to a fresh download. An unchanged readable update should still preserve its Updated timestamp.
6. **Filters:** explicitly Refresh filter data after recovery. Confirm the recovered copy becomes readable; recovery itself must not start an automatic full filter scan.

The user's earlier successful fresh-URL reshare established a workaround, not verification of this implementation or the original file-loss cause.
