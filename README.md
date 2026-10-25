# Aharana

[![Android CI & Release](https://github.com/ViVi93/fanficfare-android/actions/workflows/build.yml/badge.svg)](https://github.com/ViVi93/fanficfare-android/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**Aharana** (from the Sanskrit आहरण, *"fetching, bringing, collecting"*) is an Android
app that downloads fanfiction from the web straight to **EPUB** on your phone, using the
upstream [FanFicFare](https://github.com/JimmXinu/FanFicFare) adapter engine embedded via
**Chaquopy + Python 3.12**. No Calibre, no desktop, no account — paste a story URL and
read it in your favourite e-reader.

> **Unofficial project.** Aharana is an independent front-end for the FanFicFare
> engine. It is **not** affiliated with, endorsed by, or supported by the FanFicFare
> project or its author. The name "FanFicFare" is used descriptively to refer to the
> upstream engine, as permitted by Apache-2.0 §6; the FanFicFare project and its name
> belong to its authors. Please respect the terms of service and copyright of any site
> you download from.

---

## Table of contents

- [What it does](#what-it-does)
- [Screenshots](#screenshots)
- [Features](#features)
  - [Library management](#library-management)
  - [Downloading](#downloading)
  - [Add from page (bulk URL harvesting)](#add-from-page-bulk-url-harvesting)
  - [Metadata editing](#metadata-editing)
  - [Online metadata & covers](#online-metadata--covers)
  - [Merging books](#merging-books)
  - [Configuration (`personal.ini`)](#configuration-personalini)
  - [Diagnostics](#diagnostics)
  - [Appearance](#appearance)
- [Supported sites](#supported-sites)
- [How it works (architecture)](#how-it-works-architecture)
- [Tech stack](#tech-stack)
- [Project structure](#project-structure)
- [Building from source](#building-from-source)
- [Testing](#testing)
- [Configuration reference](#configuration-reference)
- [Privacy & security](#privacy--security)
- [Known limitations](#known-limitations)
- [License & attribution](#license--attribution)

---

## What it does

Aharana puts the FanFicFare download engine on the device. The UI is
native Kotlin/Material 3; the story-parsing and book-writing logic runs in an
embedded CPython 3.12 interpreter (Chaquopy) that ships the full upstream adapter
library. Every story is written as an **EPUB** file, either into a folder you pick
(SAF) or into a public `Download/FanFicFare` folder.

Typical flow:

1. **Download** → paste a story URL → the app fetches metadata, downloads every
   chapter (and the story's images), and writes an EPUB with cover and metadata.
2. The book appears in the **library**, where you can sort, search, edit its
   metadata, replace its cover, update it when new chapters appear, merge it with
   other books, or open it in any EPUB reader.
3. **Add from page** lets you paste a series/listing/blog page and harvest *all* the
   story URLs on it at once.

---

## Screenshots

<!-- Add screenshots below, e.g.:
| Library | Book detail | Add from page | Merge books |
|---|---|---|---|
| ![Library](docs/screenshots/library.png) | ![Detail](docs/screenshots/detail.png) | ![Add from page](docs/screenshots/add-from-page.png) | ![Merge](docs/screenshots/merge.png) |
-->

_Coming soon._

---

## Features

### Library management

- **Organise existing books** from the overflow menu: preview moves based on embedded
  EPUB metadata into `Author/Title.epub`, then apply or undo. Filename collisions
  are handled without overwriting unrelated books. Back up your library first.
- **Organise future downloads** is a separate, opt-in setting: enable it in Settings
  and tap **Save** to use author folders and title filenames for new books.
  Organising existing books does not enable this switch. Normal updates and force
  downloads retain an existing book's organised location. Organisation requires a
  filesystem folder; SAF `content://` destinations are not supported by this mode.

- **Library list** of every downloaded/imported EPUB, with the real cover extracted
  from the EPUB (falls back to a generated cover with the story's initials on a
  deterministic colour).
- **Scan a folder** for existing EPUBs — *Load Library* takes a folder path, scans it
  for `.epub` files, and imports their metadata, cover, story URL and
  chapter count. The folder is remembered between launches.
- **Sort** by Recent, Title, or Author; the choice is persisted.
- **Search** to filter the library by title/author.
- **Multi-select** mode: *Update Selected* (refresh several stories in one go) and
  *Merge Selected* (combine several books into one).
- **Refresh All** to update every book.
- **Delete** a book (with confirmation), **Share** an EPUB, or **Open** it in an
  external EPUB reader.
- Library state survives restarts (stored in a Room database, with a JSON index
  used for import/migration).

### Downloading

- **Download** a story by URL from the main menu (or by **sharing a URL** to the app
  from any browser/app — a translucent receiver activity picks it up).
- **Update latest EPUB** — re-fetch only the chapters that were added since your copy
  was downloaded, from the story URL stored in the EPUB.
- **Force Download** — re-download a story from scratch, ignoring the existing file.
- All downloads run in the background through **WorkManager** with a foreground
  service notification and live progress/status (Queued → Preparing → Downloading →
  Processing → Copying → Complete).
- **Download Queue** screen shows every job — queued, running, succeeded, failed or
  cancelled — with **retry** and **remove** actions for failures.
- **Cancel** an in-progress download from the menu.
- Images in the story text are downloaded into the EPUB on a normal download; update
  and force-download refresh the cover.

### Add from page (bulk URL harvesting)

- Paste any **series page, author page, listing or blog index**, and the app extracts
  every story URL on it (*Fetch stories*).
- **Normalize URLs** to canonical story links and de-duplicate the results (exact
  duplicates, series URLs and chapter-slug duplicates are collapsed).
- **Select all / Select none**, then **Download selected** to queue them all.
- Story metadata (title/author/chapter count) is fetched **lazily in the background**
  per row, so the list is usable immediately; a *Cancel metadata fetch* action stops
  the background pass.
- The fetched list and its selection state are **persisted**, and *Clear saved
  entries* resets it.

### Metadata editing

- **Edit Metadata** screen for the standard EPUB fields: title, author(s),
  description, ISBN, language, publication date, publisher, rating (0–5), rights,
  series, series index, and tags.
- Reads and writes **OPF** metadata inside the EPUB. The original file is preserved
  as a backup (`.metadata_edit_bak`) and the EPUB is rewritten atomically
  (temp file + rename) so a failed write can't corrupt your book.
- **Export OPF…** to view/save the raw OPF XML, and **import** OPF XML back into an
  EPUB.

### Online metadata & covers

- **Preview Online Metadata** looks the book up in three providers:
  **Google Books**, **Open Library**, and **iTunes**.
- A **provider selector** lets you switch between every result that came back, and a
  **field-by-field diff** shows what would change versus the EPUB's current metadata.
- **Cover Candidates** are collected from *all* providers that returned one; the cover
  is downloaded, validated as a real image (PNG/JPEG/GIF/WebP, with size limits), and
  ranked.
- Three apply actions, each **atomic** (metadata *and* cover written in a single
  rewrite with a backup):
  - **Apply Metadata** — write the online metadata.
  - **Apply Cover** — replace only the cover.
  - **Apply All** — write metadata **and** cover together.
- **Replace Cover** directly from the device's gallery/photo picker.
- A `content://` source EPUB is handled safely: the app works on a temporary copy and
  copies the modified result back to your output folder.

### Merging books

Combine **two or more EPUBs into one** — useful for per-chapter files or for joining
the parts of an anthology:

- **Order the sources** (move up/down); the **first book is the base** and defines the
  merged book's metadata and cover.
- **Preview** shows how many chapters and contents entries each source contributes,
  and warns if the base book has no table of contents.
- **Table of contents**: *One section per book* (each book gets its own title page and
  its chapters nest beneath it) or *Single chapter list* (one straight list, with each
  book named on its title page).
- **Chapter names**: optionally *Shorten repeated names* (strip redundant shared
  prefixes) and *Renumber chapters in order*.
- Preview of the **output filename** derived from the title, with a non-clobbering
  name if the file already exists.

### Configuration (`personal.ini`)

- **Import `personal.ini`** from device storage — this is how you supply
  site credentials and FanFicFare options that a site requires (for example
  username/password for credential-protected sites).
- **Remove `personal.ini`** to clear it.
- **Configuration status** shows the resolved config path, file size, parse errors,
  and whether **credentials are present** — without ever displaying the credential
  values.
- Configuration is stored in the app's private files dir
  (`files/fanficfare/personal.ini`) and persists across restarts.

### Diagnostics

- **Diagnostics** screen: Python runtime status, bridge/module status, app version,
  a rolling diagnostic log (view last 30 lines, **clear**, or **share** the full log),
  and a **FanFicFare import diagnostic** that reports per-import pass/fail with
  tracebacks.
- **Settings** also exposes a **Python download debug log** (refresh/clear) written by
  the download path, and a **DNS diagnostics** tool that repeats resolution attempts
  against the target host and a comparison host.
- Startup, bridge initialisation and worker steps are timestamped in the diagnostic
  log to make device-only issues debuggable.

### Appearance

- **Material 3** (`Theme.Material3.DayNight.NoActionBar`) with an app-defined colour
  scheme.
- **Theme** setting: System / Light / Dark.
- RTL support, vector empty-state illustrations, and dark-mode colour resources.

---

## Supported sites

The app bundles the **full upstream FanFicFare adapter set** — 107 site adapters
covering **100+ fanfiction sites**, including:

- **Archive of Our Own** (`archiveofourown.org`, `ao3.org`, mirrors)
- **FanFiction.net** and **FictionPress**
- **StoriesOnline**, **Literotica**, **Royal Road**, **Fimfiction**
- **SpaceBattles**, **Sufficient Velocity**, **Questionable Questing** forums
- **ScribbleHub**, **Wattpad**, **Ficbook**, **fanfics.me**, **FictionHunt**
- **SquidgeWorld**, **Twilighted**, **Dark Solace / Nine Lives Archive**
- **AdultFanFiction** network, **SycophantHex** network, **Fanficauthors.net**
- **Kakuyomu / Syosetu** (JP), **FanFiktion.de**, **Fanfictions.fr**, **AsianFanfics**,
  **Spirit Fanfiction**, **Inkbunny**, **SoFurry**, **DeviantArt** and many more.

Sites that require a login (for example StoriesOnline) work once you import a
`personal.ini` containing your credentials — there is **no in-app login screen**.

The authoritative, always-current list lives in the upstream wiki:
<https://github.com/JimmXinu/FanFicFare/wiki/SupportedSites>.

---

## How it works (architecture)

```
┌───────────────────────────── Android (Kotlin) ─────────────────────────────┐
│  Activities          LibraryViewModel ── BookRepository ── Room (books, jobs)│
│  MainActivity        ▲                                                       │
│  BookDetailActivity  │ LiveData                                              │
│  AddFromPageActivity │                                                       │
│  MetadataPreview…    │                                                       │
│  MergeBooksActivity  ▼                                                       │
│  Settings / Diagnostics                                                      │
│                          WorkManager                                         │
│                     FanFicFareWorker / MergeBooksWorker                      │
└──────────────────────────────┬───────────────────────────────────────────────┘
                               │ Chaquopy (Java ⇄ Python)
┌──────────────────────────────▼───────────────────────────────────────────────┐
│  Python 3.12 (embedded)                                                       │
│  fanficfare_bridge.py   ← single entry point, JSON in/JSON out                │
│  fanficfare_config.py   ← builds FanFicFare Configuration (personal.ini)      │
│  fanficfare/            ← upstream engine + 107 adapters (writers: EPUB/…)    │
│  metadata_providers.py  ← Google Books / Open Library / iTunes               │
│  metadata_normalizer.py, metadata_diff.py, cover_manager.py                  │
│  epub_editor.py, epub_container.py, epub_xml.py, epub_merge.py               │
│  cover_manager.py, dns_diagnostic.py                                          │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **`fanficfare_bridge.py`** is the *only* surface the Kotlin side calls. `PythonBridge.kt`
  wraps it with `safeCall()` so every call returns a JSON string with an `ok` flag —
  Python exceptions become JSON errors instead of crashing the app.
- **`fanficfare_config.py`** centralises configuration: `set_config_dir()`,
  `build_configuration()`, `get_config_status()`. Every FanFicFare call goes through
  it, so `personal.ini` is applied consistently.
- **Downloads** are executed by `FanFicFareWorker` (WorkManager, foreground service,
  cancellation-aware); merging is done by `MergeBooksWorker`. Job state lives in a Room
  table and is surfaced on the Queue screen and the main status bar.
- **Epub editing** (`epub_editor.py`, `epub_merge.py`) is deliberately **stdlib-only**
  (`zipfile` + `xml.etree.ElementTree`) — no `lxml`, no Calibre — so it runs under
  Chaquopy on ARM64. Writes are atomic: back up the original, write a temp file, rename
  into place.

---

## Tech stack

| Component | Version |
|---|---|
| Kotlin | 1.9.22 |
| Android Gradle Plugin | 8.7.2 |
| Gradle | 8.11.1 |
| Chaquopy | 17.0.0 |
| Embedded Python | 3.12 |
| Embedded FanFicFare | 4.60.0 (upstream `v4.60.0`, commit `86832ac`) |
| Min SDK | 24 |
| Target / Compile SDK | 35 |
| ABI | `arm64-v8a` |
| App version | 0.1.4 (`versionCode` 5) |
| Application ID | `com.vivi.aharana` |

**AndroidX / libraries:** AppCompat, ConstraintLayout, RecyclerView, SwipeRefreshLayout,
Material Components 1.14.0, Lifecycle 2.8.0, Room 2.6.1, WorkManager 2.9.1,
Kotlin coroutines 1.8.0, DocumentFile.

**Python packages bundled via Chaquopy:** `beautifulsoup4`, `chardet`, `html5lib`,
`html2text`, `cloudscraper`, `requests`, `requests-file`, `urllib3`, `Brotli`.

---

## Project structure

```
fanficfare-android/
├── app/src/main/
│   ├── java/com/example/fanficfare/     # Kotlin UI + workers
│   │   ├── MainActivity.kt              # library, menus, add/download dialogs
│   │   ├── BookDetailActivity.kt        # open/update/force/share/delete/cover
│   │   ├── AddFromPageActivity.kt       # bulk URL harvesting
│   │   ├── EditMetadataActivity.kt      # OPF field editor
│   │   ├── MetadataPreviewActivity.kt   # online metadata + cover apply
│   │   ├── MergeBooksActivity.kt        # merge UI
│   │   ├── SettingsActivity.kt          # output dir, personal.ini, theme, debug
│   │   ├── DiagnosticsActivity.kt       # runtime + log + import diagnostics
│   │   ├── DownloadQueueActivity.kt     # job queue
│   │   ├── ShareReceiverActivity.kt     # handles shared URLs
│   │   ├── FanFicFareWorker.kt          # download/update/metadata worker
│   │   ├── MergeBooksWorker.kt          # merge worker
│   │   ├── PythonBridge.kt              # Kotlin ⇄ Python JSON bridge
│   │   ├── BookRepository.kt            # Room + preferences + job enqueue
│   │   ├── data/local/                  # Room entities, DAOs, database
│   │   └── util/, adapter/, model/      # helpers, RecyclerView adapters, model
│   ├── python/                          # embedded Python
│   │   ├── fanficfare/                  # upstream engine + adapters (107 sites)
│   │   ├── fanficfare_bridge.py         # bridge entry point
│   │   ├── fanficfare_config.py         # configuration layer
│   │   ├── metadata_providers.py        # Google Books / Open Library / iTunes
│   │   ├── metadata_normalizer.py, metadata_diff.py
│   │   ├── cover_manager.py             # cover download/validation/ranking
│   │   ├── epub_editor.py               # OPF metadata + cover editing
│   │   ├── epub_container.py, epub_xml.py, epub_merge.py
│   │   ├── dns_diagnostic.py
│   │   └── tests/                       # Python test suite
│   └── res/                             # Material 3 themes, layouts, strings
├── docs/FANFICFARE.md                   # embedded-engine version + update notes
├── tools/update_fanficfare.py           # upstream update helper
└── .github/workflows/build.yml          # CI: debug + release APK
```

---

## Building from source

**Requirements:** JDK 17, Android SDK (platform 35 / build-tools 34), Python 3.12
on `PATH` as `/usr/bin/python3.12` (used by Chaquopy to install its pip packages),
and an `arm64-v8a` device or emulator.

```bash
# Debug APK
./gradlew clean :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK (unsigned unless a signing config is supplied)
./gradlew :app:assembleRelease
```

Release signing is optional and driven by Gradle properties; when present the
`release` build type picks them up:

```bash
./gradlew :app:assembleRelease \
  -PSIGNING_KEY_STORE_PATH=/path/to/keystore.jks \
  -PSIGNING_KEY_ALIAS=<alias> \
  -PSIGNING_STORE_PASSWORD=<store-pass> \
  -PSIGNING_KEY_PASSWORD=<key-pass>
```

### Continuous integration

`.github/workflows/build.yml` runs on pushes/PRs to `main`/`master`:

1. Builds the **debug** APK.
2. On a push, attempts a **signed release** build (from the
   `SIGNING_KEY_STORE_BASE64` + alias/password repository secrets) and
   **falls back to an unsigned release** if signing credentials are absent or wrong.
3. Uploads the APKs as the `fanficfare-apk` artifact.
4. On a push, creates a **GitHub Release** tagged `build-<sha>` with the release APK
   (or the debug APK if no release was produced).

---

## Testing

The Python layer has a self-contained test suite under `app/src/main/python/tests/`
(~298 test functions) covering the bridge, configuration, metadata providers/diff/
normaliser, cover manager, and the EPUB editing/merging tools:

```bash
cd app/src/main/python
python3 -m unittest discover -s tests
```

The EPUB tests use a stdlib-only container assertion helper (`tests/epub_assert.py`)
and generated fixtures, so they don't need Calibre or `lxml`.

---

## Configuration reference

| Setting | Where | Notes |
|---|---|---|
| Output directory | Settings → *Default Output Directory* / *Pick Folder* | SAF tree URI or filesystem path; defaults to `Download/FanFicFare` |
| All Files Access | Settings → *Grant All Files Access* | Optional; needed for reading/writing arbitrary paths on Android 11+ |
| Theme | Settings → *Theme* | System / Light / Dark |
| `personal.ini` | Settings → *Import / Remove* | Stored at `files/fanficfare/personal.ini` |
| Library folder | Main menu → *Load Library* | Folder scanned for EPUBs; remembered |

Never commit a `personal.ini` or any credentials — they are git-ignored and belong only
in the app's private storage.

---

## Privacy & security

- **No analytics, no ads, no accounts.** The app talks only to the fanfiction sites you
  ask it to download from, plus **Google Books / Open Library / iTunes** when you
  explicitly tap *Preview Online Metadata*.
- Credentials live in `personal.ini` inside the app's private files directory and are
  never displayed — the UI only reports whether they are present.
- EPUB edits are **non-destructive**: the original is backed up and the rewrite is
  atomic (temp file + rename).
- `personal.ini`, keystores (`*.jks`) and secret files are excluded via `.gitignore`.

---

## Known limitations

Being explicit about what this app **is not**, so expectations match reality:

- **EPUB only.** Downloads and updates always produce EPUB. There is **no HTML, TXT,
  MOBI, AZW3/Kindle, or PDF export option** in the app UI, and no Calibre integration.
- **No login UI.** Sites that need credentials require a manually imported
  `personal.ini`.
- **No in-app reader.** Books are opened in an external EPUB reader app.
- **arm64-v8a only.** The build ships a single ARM64 ABI (no x86/ARMv7 APK).
- **No APSW / SQLite browser-cache.** The optional FanFicFare SQLite browser-cache is
  not bundled; the non-SQLite cache path is used. (See `docs/FANFICFARE.md`.)
- **No cloud sync, no update-log/anthology extras.** Only the features listed above.
- Downloading relies on the target sites' current markup; a site redesign can break an
  adapter until the embedded engine is updated from upstream.

---

## License & attribution

This project is released under the **GNU General Public License v3.0 or later** —
see [`LICENSE`](LICENSE). The GPLv3 is required because the app links the EPUB editing
modules, which are themselves GPLv3 (they reimplement metadata handling inspired by
[calibre](https://calibre-ebook.com/), which is GPLv3).

It bundles third-party work whose notices are preserved:

- **[FanFicFare](https://github.com/JimmXinu/FanFicFare)** by Jim Miller and
  contributors — the download engine and site adapters bundled in
  `app/src/main/python/fanficfare/` (version 4.60.0), licensed under the
  **Apache License 2.0**. Full text: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).
- **[Chaquopy](https://chaquo.com/chaquopy/)** — embeds CPython in the Android app
  (**MIT**).
- AndroidX / Material Components / Kotlin libraries (**Apache 2.0**), and a set of
  Python packages installed at build time.

See **[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)** for the complete list, the
Apache-2.0 notice, and guidance for redistributors. If you redistribute a build or a
modified tree, keep the upstream copyright and licence notices intact, and mark any
files you have changed.