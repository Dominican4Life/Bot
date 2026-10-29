## 3. JS / JSON sources and the Code editor (app-side engine)

Everything here was added by the fork (first commit d50abb5e, 2026-10-09, authors Claude / Dominican4Life). Until r62 the package was `eu.kanade.tachiyomi.sumizuri` with `Sumizuri*` class names; commit 5fb7b0f4 (r62) renamed it to `jssource` / `Js*` and `ui/sumizuri` to `ui/codeeditor`. Use `git log --follow` or the old path `app/src/main/java/eu/kanade/tachiyomi/sumizuri/` for older history.

How to write a source (fields, methods, selectors) is in `Writing-JS-JSON-extensions.md`. This section covers what the app does with a source: the engine, storage, how results become app objects, and the video plumbing.

Paths below are relative to `app/src/main/java/eu/kanade/tachiyomi/` unless they start with `assets/` (which means `app/src/main/assets/`).

| File | Role |
|---|---|
| `jssource/JsRuntime.kt` | One QuickJS engine per source; host bridge (`sendMessage`) |
| `jssource/JsBridges.kt` | Host code with no network: `JsBytes`, `JsCrypto`, `JsCompress`, `JsHtmlPages` (host.parse) |
| `jssource/JsRender.kt` | `host.render`: a hidden WebView |
| `jssource/JsBrowserFetch.kt` | Sends `host.fetch` from a hidden WebView when Cloudflare blocks OkHttp |
| `jssource/JsSourceManager.kt` | Saved sources on disk, ids, on/off, runtimes cache, import/backup |
| `jssource/JsModels.kt` | `JsSourceInfo` (meta.json), `JsMapping` (JSON to SManga/SChapter/SAnime/SEpisode) |
| `jssource/JsMangaSource.kt`, `JsAnimeSource.kt` | Adapters to `HttpSource` / `AnimeCatalogueSource` |
| `jssource/JsFilters.kt`, `JsTabs.kt`, `JsTags.kt`, `JsPreferences.kt`, `JsSliderPreference.kt` | Filters, tab catalogs, tag lists, settings |
| `jssource/JsVideos.kt` | `getVideoList` output to player `Video`s |
| `jssource/ReaderVideos.kt` | Video pages inside manga chapters, the hidden "Videos in chapters" anime source |
| `jssource/HlsRelay.kt` | Local HLS proxy on 127.0.0.1 |
| `jssource/ChapterVideoDownloads.kt` | Saving chapter videos next to downloaded pictures |
| `jssource/JsIcons.kt`, `JsWebUrls.kt`, `JsListBadges.kt`, `JsLegacy.kt`, `JsJson.kt` | Icons, web links, Browse badges, old-name migration, JSON helpers |
| `ui/codeeditor/*` | Code editor screen, model, text area, templates |
| `assets/jssource/prelude.js`, `stdlib.js`, `json_engine.js` | JS side: `host.*` API, `require` modules, JSON declarative engine |

---

### 3.1 JsRuntime: the QuickJS engine and the host bridge

**What it does.** It runs one source's code and answers its `host.*` calls. Every call from the app (`getPopular`, `search`, …) goes through `JsRuntime.call(method, args…)`.

**Engine and packaging.** It uses `io.github.dokar3:quickjs-kt-android:1.0.15` (`gradle/libs.versions.toml`). The extensions' engine (`app.cash.quickjs`, `libquickjs.so`) has a native library with the same name, so `app/build.gradle.kts` unpacks the quickjs-kt AAR itself (task `unpackQuickjsKt`), renames its `.so` to `libquickjskt.so`, and adds it through `jniLibs.srcDir`. `JsRuntime.loadNativeLibrary()` calls `System.loadLibrary("quickjskt")` before the first `QuickJs.create`. ProGuard keeps `com.dokar.quickjs.**` (JNI calls back into it). The rename came in commit ac022ea5, right after the first JS commit.

**Creating a runtime** (`JsRuntime.create(context, key, sourceId, name, code, json, testing)`):
1. Builds a `Host` with:
   - log tag `js:<name>`
   - storage dir `filesDir/js_storage/<key>`
   - preferences `JsPreferences.of(context, sourceId)`, which are the SharedPreferences `source_<id>`
2. `QuickJs.create(jobDispatcher = Dispatchers.Default)`, then sets `maxStackSize = 2 MiB` and `memoryLimit = 128 MiB`.
3. Defines one async binding, `sendMessage(channel, json)`, which goes to `Host.handle`.
4. Evaluates `prelude.js`, then `stdlib.js`. A stdlib failure is only a warning ("Standard library failed to load"). The prelude's own `require` (entities, url, date) still works then.
5. Sets `host.isTesting` and `host.cacheExtensionDataEnabled = true`.
6. Loads the source:
   - JSON: `stripJsonComments` first, then a strict parse, so a typo is reported as a JSON error. Then `var __sourceConfig = <json>;` plus `json_engine.js`, which builds `extension`.
   - JS: the code as is.
7. Runs `CONTRACT_SCRIPT`. It needs a global `extension` object with `search` and `getChapterList`, plus `getPopular` and `getLatest` unless both `getCatalogs` and `getCatalog` exist, plus `getPageList` or `getVideoList`. Otherwise it throws `JsException("missing required method(s): …")`, which is what the editor's "Couldn't save" dialog shows.
8. If `getSourcePreferences` exists (as `extension.getSourcePreferences` or as a global function), it is called once, and its defaults become `host.preferenceDefaults`.

**Calls.** `call()` builds `await (async function(){ … extension[method](args) … JSON.stringify(result) })()`. The args are JSON-encoded with `toJsonElement()`.
- A missing method becomes `NotImplementedError`. `optionalListing()` (in `JsJson.kt`) turns that into `[]` for `getPopular` and `getLatest` only.
- A `CancellationException` is rethrown, not logged. Before ff339a60 a user leaving Browse produced "getLatest failed: Job was cancelled" errors.
- Other errors are logged as `[error] <method> failed: <msg>` under tag `js:<name>`, and thrown as `JsException` with the JS stack cut off at `"\n    at "`.
- Every `metadata()`, `has()` and `call()` holds the runtime's `Mutex`, so **one call at a time per source**. A slow `getVideoList` holds up Browse paging of the same source.

**`metadata()`** reads:
- `name`, `lang`, `iconUrl`, `baseUrl`, `popularName`, `latestName`, `headers`, `rateLimitMs`, `id`, each from the `extension` object, then from the globals (`NAME`, `LANG`, `ICON_URL`, `BASE_URL`, `HEADERS`, `RATE_LIMIT_MS`), then from the JSON config.
- the `has*` flags.

`id` comes back as a **string** so a 64-bit id stays exact. It also sets `host.rateLimitMs`.

**Host channels** (`Host.handle`). Every reply is a JSON string. An error comes back as `{error: msg}` and the prelude throws it in JS.

| Channel | Notes |
|---|---|
| `fetchUrl` | OkHttp `network.nonCloudflareClient` with `callTimeout(60 s)`. The default UA is added if missing. `binary:true` returns `bodyBase64`. Response header names are lower-cased and multi-values joined with `,`. Rate limit: waits `lastRequestAt + rateLimitMs`. A Cloudflare challenge reply (403/429/503 plus markers, `JsBrowserFetch.isChallenge`) is sent again through `JsBrowserFetch`, and that origin goes into `JsBrowserFetch.browserFirst` for the rest of the process. Binary requests never use the browser. Logged as `[net] GET url -> 200 in N ms`, only when `testing` (editor). |
| `queryHtml`, `parseHtml`, `selectHtml` | Jsoup through `JsHtmlPages`. It keeps the **24** most recent parsed pages (LRU), and elements get numeric ids for `el.select()`. A page that has been evicted gives the error "That parsed page is gone … Call host.parse(html) again". XPath ending `/@attr` or `/text()` returns `values`. |
| `render` | `JsRender`: a WebView on Main, `blockNetworkImage`, default timeout 20 s (`options.timeout`, no upper limit), `waitFor` selector, `waitForResource`/`capture` regex over up to 3000 requested URLs (frames included), `script` polled for up to 30 s. |
| `hash`, `hmac`, `aes`, `rsaEncrypt`, `rsaVerify` | `JsCrypto`. AES cbc/ecb/ctr/gcm, OpenSSL "Salted__" passphrase mode. RSA public keys only. |
| `gzip*`, `deflate*`, `zipEntries`, `zipRead` | `JsCompress` |
| `sleep` | Capped at 10 s |
| `cookiesGet` / `cookiesSet` | The app's shared `network.cookieJar` (same cookies as WebView and APK sources) |
| `storageGet/Set/Delete/Clear/All` | `js_storage/<key>/storage.json`, one JSON object. It is read once into a per-Host cache, and the **whole file** is rewritten on every write. Write errors are swallowed. |
| `preferenceGet/Set/All` | SharedPreferences `source_<id>`. Unset keys fall back to the `getSourcePreferences` defaults. `JsPreferences.write`: array to StringSet, JSON boolean to Boolean, anything else to String. |
| `log`, `trace` | Console ring buffer (500 lines) and `AppLog` under tag `js:<name>` with level E/W/D/I |

**`require` modules.**
- The prelude defines `entities`, `url`, `date`.
- `stdlib.js` (end of file) wraps it and adds `crypto`, `base64`, `unpacker`, `hls`, `dash`, `zip`, `compress`, `jwt`, `subtitles`, a stronger `date`, and `url.absolute`.
- `json_engine.js` calls `require('entities'|'url'|'date')` at its top. Those three must keep working even when stdlib fails.

**Stdlib `fetch`.** On top of `host.fetch` it supports `json`, `form`, `retry {attempts, statuses, baseDelayMs}` and `cache: true`. `cache` stores an ETag or Last-Modified **and the body** in `host.storage` under `__fetchCache:<url>`, so storage.json can grow large. See the risks below.

**Limits that exist:**
- JS heap 128 MiB, JS stack 2 MiB
- `sleep` ≤ 10 s, fetch call ≤ 60 s
- browser fetch poll ≤ 45 s, plus ≤ 30 s waiting out a challenge
- render default 20 s, plus ≤ 30 s for its script

**No `setTimeout`/`setInterval`** is defined in prelude or stdlib.

**Known finding (not fixed): there is no CPU deadline.** Nothing interrupts a JS loop that does not await (`while(true){}`, or a runaway regex). `call()` never returns, the runtime's mutex stays held, and every later call to that source hangs until the app process dies. Coroutine cancellation does not stop it either: `ensureActive` only runs after `evaluate` returns. Browse shows endless loading for that source. No log line appears, because nothing fails.

**Invariants**
- The method name is pasted into JS code (`extension.$method`). Only pass the app's own constant names, never user text.
- Keep the contract script and `metadata()` in step with `JsSourceManager.describe` and the editor's `runAll` / `detectDetails` (they read `hasVideos`, `hasPages`, `hasCatalogs`, `hasPopular`, `hasLatest`).
- `JsRuntime.close()` must also call `host.clearPages()`. r79 (625be48d) added that, because a tag-download runtime kept up to 24 big parsed pages after it finished.
- Storage and preference locations are keyed by **key** (storage) and **id** (preferences). See 3.2.

**How to check.** Logs, filtered on `js:<source name>`.
- Good: no `[error]` lines.
- Bad: `[error] getPopular failed: TypeError: …`, `[warn] fetch failed: …`, `[warn] A bot check answered <url>: sending it from a browser page on the site instead`, `D net BROWSER GET <url> -> 200 in N ms`.
- "JS sources running N" in the memory lines (`MemoryLog`) is `JsSourceManager.runningCount`.

---

### 3.2 JsSourceManager: saving, keys, ids, folders, on/off

Registered in `di/AppModule.kt` (`addSingletonFactory { JsSourceManager(app) }`). Its `init` first runs `JsLegacy.migrate`, which moves `sumizuri_sources`, `sumizuri_storage`, `sumizuri_tags` and `shared_prefs/sumizuri_*` to `js_*` once.

**On-disk layout** (`filesDir`):

| Path | Contents |
|---|---|
| `js_sources/<key>/source.js` or `source.json` | The code. Saving one kind deletes the other. |
| `js_sources/<key>/meta.json` | `JsSourceInfo` serialized |
| `js_sources/<key>/icon-<hash>.img` | Icon (`JsIcons`). There is also a copy as `downloads/Icon/<name>.png`, which survives a reinstall. |
| `js_sources/apk_off.txt` | Ids of APK sources switched off in favour of a JS source with the same id |
| `js_storage/<key>/storage.json` | `host.storage` |
| `js_tags/<key>.json` | `getTags()` result (`JsTags.Cached`) |
| `shared_prefs/source_<id>.xml` | The source's settings (the same file name Mihon uses, so the normal source settings screen works) |
| `shared_prefs/js_tabs.xml` | Last tab catalog per key |
| `shared_prefs/js_weburls_<id>.xml` | url to webUrl map (`JsWebUrls`). Cleared when it passes 5000 entries. |

**Keys and ids.**
- `newKey()` = 16 hex characters from a UUID.
- `JsSourceInfo.idFor(key)` = first 8 bytes of `MD5("sumizuri/<key>/1")`, AND `Long.MAX_VALUE`. **`JsLegacy.ID_SEED = "sumizuri"` must never change**: it would change every source's id, and so the library, history, settings and web URLs.
- `id = customId ?: idFor(key)`. `customId` comes from `extension.id`. `customId()` refuses ids that are not whole numbers, the app's own ids (`reservedIds()`: Local, E-H/ExH and their ext ids, Merged, Device files manga/anime, `ReaderVideos.SOURCE_ID`), or one another saved JS source already uses. A refusal logs W `js` "<name>: id <x> <reason>, so the source keeps its own id".
- A JS source may take an **installed APK's id**. That is the point: library entries made with the APK open through the JS source. `AndroidSourceManager` and `AnimeSourceManager` put JS sources into the map **after** the APKs, so the JS source wins. An APK whose id is in `apkOff` is registered only as a stub.

**Save** (`save(key, code, json, animeHint, overrides)`):
1. Starts a throwaway runtime (testing=false). Construction errors are the save error.
2. `describe()` → `metadata()`, plus `getFilters`, `getCatalogs` and `getSourcePreferences` if their flags are set. **These three are wrapped in `runCatching`**: if they throw, the source is saved with no filters, catalogs or settings, and nothing is logged.
3. Kind: `hasVideos && !hasPages` → anime; `hasPages && !hasVideos` → manga; both → the editor's Manga/Anime switch (`animeHint`).
4. Names: editor Details fields (`overrides`), then the source's own value, then the existing value. `enabled` and `createdAt` are kept from the existing copy.
5. Writes the code, deletes the other kind's file, writes meta.json, calls `invalidate(key)` (the cached runtime is closed asynchronously), and turns the old APK back on if `customId` changed.
6. `showLanguage()`: turns the source's language on in Browse and removes its id from `disabledSources`.
7. Logs I `js` "Saved manga source X (JS)".

**`refreshDescription(info)`.** On the first `runtime()` use of each `JsMangaSource`/`JsAnimeSource` instance, the filters, catalogs, preferences, `hasTags` and `hasRelated` are read again and meta.json is rewritten if they changed. Errors are swallowed. The source flows rebuild **all** source objects on every `_saved` change, so each rebuild triggers another (harmless) refresh.

**Runtimes cache.** `runtime(info)` keeps one runtime per key in `runtimes` (under `runtimeLock`), started on first use and kept for the life of the process. `invalidate` drops it on save, import, disable and delete. `JsTags` uses **its own** short-lived runtime (so a tag download doesn't hold up Browse). The editor also has its own (3.8).

**Enable / disable** (`setEnabled`, from `UnifiedExtensionsScreenModel.setSourceEnabled`). It changes meta.json's `enabled`, and `mangaSourcesFlow`/`animeSourcesFlow` leave out disabled sources. An APK that shares the id uses `setApkEnabled` (writes `apk_off.txt`). The app-wide `disabledSources` pref is deliberately **not** used for JS/APK pairs: it goes by id and would hide both.
- **Known finding:** in `setEnabled` the meta.json write is wrapped in `runCatching { }` with no log. If it fails, the in-memory state says "off" but the source comes back "on" after a restart.
- `setApkEnabled` swallows write errors the same way.

**Delete.** Re-enables the shared APK, then removes the tab memory, runtime, `js_sources/<key>`, `js_storage/<key>` and the tag cache. Not removed: `source_<id>` prefs, `js_weburls_<id>`, icon copies in downloads/Icon.

**Import (pairing) and backup.**
- `exportSources(keys)` gives `(meta JSON, code)` pairs. `PairSyncData.export` sends them; settings are **not** sent.
- `importSource(infoJson, code)` writes the files **as they are, without running the code**. Newer wins by `updatedAt`, so an equal timestamp means no import. Logs I `js` "Got <name> from a paired device". The pairing screen log says "Code editor sources: N new or updated of M".
- Backup: `BackupCreator.backupJsSources()` → `BackupJsSource(info, code, settings)` (proto field 710 in `Backup`). Settings come from `source_<id>`; Int/Long/Float/String/Boolean/StringSet are kept and other types dropped.
- Restore: `BackupRestorer.restoreJsSources` → `restoreSource()` = `importSource()` and then the settings. **Settings are restored only when the source itself is.** If the device copy is the same or newer, the backup's settings are ignored. Errors go to the restore error list as "JS source: …".
- Not backed up or synced: `storage.json`, tag lists, web URLs, last tab, `apk_off.txt`.

**Known findings (not fixed).**
- `importSource` and `restoreSource` use `info.key` from the incoming JSON in `File(root, key)` **without checking it**. A key like `../..` writes outside `js_sources`, and a later `delete(key)` would `deleteRecursively()` there.
- The same path also skips `customId()`. An imported `customId` that equals a reserved id (for example `LocalSource.ID`) or another JS source's id is accepted, and the JS source then replaces that source in the source map.
- Import also doesn't call `setApkEnabled` for an id change.

**Invariants**
- `meta.json` is the only index. `load()` lists folders, and a folder whose meta.json fails to parse is skipped with W `js` "Couldn't read source <folder>: …". The source then vanishes from Browse; its code is still on disk.
- Settings live under the **id**, storage under the **key**. Adding or removing an `id` line makes the settings look reset: they stay in the old `source_<id>`, which nothing reads any more.
- `JsSourceInfo` is kotlinx-serialized with `ignoreUnknownKeys` and `explicitNulls=false`. New fields need defaults, or older meta.json files and backups stop loading.

---

### 3.3 JsMangaSource / JsAnimeSource: how a source maps onto the app's source APIs

| App call | JS call | What the app does with the result |
|---|---|---|
| `getPopularManga(page)` / `getPopularAnime` | `getPopular(page)` (`[]` if missing) | `JsMapping.list` keeps only objects. `toSManga`/`toSAnime` **drops entries with no `url`**. `hasNextPage = list.isNotEmpty()`: return `[]` to end paging. A source that repeats the last page pages forever. |
| `getLatestUpdates(page)` | `getLatest(page)` | Same |
| `getSearchManga(page, query, filters)` | `getCatalog(id, page)` when a catalog (sheet box or tab button) is picked **and** the query is blank; otherwise `search(query, page, {key: value})` | See filters below |
| `getMangaUpdate(...)` (details) / `getAnimeDetails` | `getDetails(url)`, only if `hasDetails` | Merged over the existing manga (missing fields keep their old values). `initialized = true` |
| chapters / `getEpisodeList` | `getChapterList(url)` | `JsMapping.toSChapters` / `toSEpisodes` |
| `getPageList(chapter)` | `getPageList(url)` | Sorted by `index`, then **re-indexed 0..n**. A page with `videoUrl`/`videoListUrl` becomes a video page (3.5). Otherwise `imageUrl` is required (pages without it are dropped). |
| `getVideoList` / `getHosterList` | `getVideoList(url)` | `JsVideos.map` (3.4). Grouped into `Hoster`s by `server`/`hoster` (04dfaf31); ungrouped when none is named. |
| related titles | `getRelated(url)` if `hasRelated` | An array, or `{groupName: [...]}` shown as named groups (`RelatedManga.Success.GROUP_PREFIX`). With `hasRelated`, the title-word searches are turned off (`disableRelatedMangasBySearch`). Anime always turns them off (`disableRelatedAnimesBySearch = true`, because per-word searches got 429s, 25607ae7). |
| Browse preview small pages | `getPagePreviews(chapterUrl)` (optional) | `JsMangaSource.pagePreviews`, used by `BrowsePreviewScreenModel` |
| Settings screen | `getSourcePreferences()` (saved in meta.json at save or refresh) | `JsPreferences.setup`. A "Refresh tag list" button (`key js_refresh_tags`) is added when `hasTags`. |
| Filter sheet | `getFilters()` (saved in meta.json) | `JsFilters.groups` builds the app's own Filter classes |
| Tab buttons | `getCatalogs()` entries with `tab: true` | `JsTabs`: buttons replace Popular/Latest. The last one used is remembered in `js_tabs`. `homeTab()` = last or first. |
| (not called) | `getComments` | `json_engine.js` defines it; no Kotlin code calls it (as of r84). |

`tabNames` no longer exists. It was replaced by tab catalogs in 696a11ab.

**Field mapping (`JsMapping`, `JsModels.kt`).**
- **Entry:** `url` (required), `title`, `coverUrl`, `bannerUrl` (anime background), `author`, `artist`, `description`, `rating`, `genres` or `tags` (array or comma string), `status`, `webUrl`, `badge`/`count`.
  - `rating` > 0 is appended to the description as "Rating: x".
  - `status` is matched against lower-case word lists (ongoing/releasing/airing…, completed/finished…, hiatus, cancelled/dropped…, licensed, anime-only upcoming). Anything else is UNKNOWN.
  - `badge`/`count` is shown in Browse only, as "Title · 272" (`JsListBadges`, kept in memory, never saved; r48).
- **Chapter/episode:**
  - `url` (required). `title`, falling back to "Chapter N"/"Episode N", then to the url.
  - `number`: a number or numeric string. Missing means `-1`.
  - `volume` > 0 prefixes "Vol. N " unless the title already has "vol".
  - `locked: true` prefixes "🔒 ".
  - `scanlator`, `dateUploaded`.
  - Episodes also have `season`/`seasonName` (prefix "S2 · …" only when there is more than one season), `description`/`summary`, and `thumbnailUrl`/`previewUrl`/`coverUrl`.
- **Order:** if **every** item has a number, the list is sorted by number, highest first. Otherwise it is reversed when the first date is older than the last. Otherwise it is kept as given. One item without a number turns number sorting off for the whole list.
- **Dates (`JsMapping.date`):** epoch seconds (< 1e10) or milliseconds, ISO offset date-time, `Instant`, or `YYYY-MM-DD`. Anything else is 0.
- **Web URL (`JsWebUrls.pageFor`):** the remembered `webUrl`. Otherwise the url if it's absolute. Otherwise `baseUrl + path`. For a bare id (no `/` or `.`), the base URL itself.

**Headers.**
- Manga: `HttpSource.headersBuilder()` plus `Referer: <baseUrl>/` plus the source's `headers`. These are used for covers and pages.
- Anime: `JsAnimeSource.headers` (app UA, Referer, source headers) and `client = network.client` (the app's Cloudflare-aware client). Covers and thumbnails load through these, like an Aniyomi extension (d915b4d8).

**Filters to `search()` values (`filterValues`)** — what the source receives:

| Filter type | Value sent to `search()` |
|---|---|
| Select | the option's `value` (any JSON) |
| MultiSelect | an array of values |
| Checkbox | a boolean |
| AutoComplete (tags) | an array of strings |
| Text | a string |
| TriState | `0`/`1`/`2` |
| Sort | `{index, value, ascending}` |
| Group | its children, flattened under their own keys |
| Header / Separator | nothing |

- A filter with no `key` is dropped, except Header, Separator and Group.
- A tab button counts only while every filter is still at its default (`touched()`). Changing any filter in the sheet turns the tab into a normal search.
- With all catalogs `tab: true`, the sheet has no Catalog box, only an invisible `TabHolder` (a blank `Filter.Header("")` that the sheet skips). Don't render blank headers.

**Invariants**
- `JsMangaSource.id`/`name`/`lang` are fixed at construction from `JsSourceInfo`. `lang` is `displayLang`: blank, `multi` or `mul` all become `all`.
- `groups` and `catalogs` are `lazy` from the saved meta.json, not live. A change shows after the next save or refresh and a source-flow rebuild.
- `toString()` = "Name (JS|JSON)". Logs and source lists show it.

**Failure modes.** A broken `getFilters` at save time means an empty filter sheet, with no message. Entries without `url` silently disappear. A `JsException` from a listing shows as the normal Browse error.

---

### 3.4 JsVideos: getVideoList to player Video

`JsVideos.map(videos, baseUrl, sourceHeaders)`:
- `url` must start with `http`, or with `//` (then `https:` is added). Anything else is **dropped silently**.
- Headers, lowest to highest priority: `Referer: baseUrl/`, then the source's `headers`, then the video's `headers`.
- `quality` becomes `videoTitle` ("Video" if blank). The resolution is taken from `(\d{3,4})p` in the quality.
- `subtitles` and `audioTracks` are `[{url, label|language}]`. `intro`/`outro` `{start,end}` become `TimeStamp`s (Opening/Ending).
- Type: HLS if the path contains `m3u8`, DASH if it ends `.mpd`, else VIDEO.
- **Every HLS video is wrapped in `HlsRelay.wrap(url, headers)`** (40bf798d), so `videoUrl` becomes `http://127.0.0.1:PORT/p/<id>?u=…&x=list.m3u8`. `headers` are still set on the Video.
- `server` or `hoster` → `Hoster` grouping in `JsAnimeSource.getHosterList`.

**Invariant.** Any code that inspects a JS video URL must look for `m3u8` **anywhere**, not just as the extension: the relay URL ends in `&x=list.m3u8` after `?`. `HlsVariants.isPlaylist` and `ChapterVideoDownloads.isStream` both do this. r83/r84 history: `HlsVariants.isPlaylist` only checked the path before `?`. Mitaku streams then showed 3 qualities as audio tracks, and downloads saved all variants. Fixed in a693dc09.

---

### 3.5 ReaderVideos: videos inside manga chapters

**What it does.** A manga source's `getPageList` may return pages with `videoUrl` (a direct stream) or `videoListUrl` (resolved later by the same source's `getVideoList`; 883c2142). The reader shows the thumbnail (`imageUrl`/`thumbnailUrl`) or a plain dark tile, with a play button (`ui/reader/viewer/ReaderVideoButton.kt`). Tapping it plays in the app's player. A chapter whose only page is a video, or a video tapped in a Browse or library page grid (`EXTRA_PLAY_VIDEO`), opens the player straight away (`ReaderActivity`, ~line 1105).

**Data flow.**
1. `ReaderVideos.pageOf(index, page, defaults, sourceId)` encodes everything into the **page URL**: `jsvideo:` + base64url(JSON `{url | listUrl+sourceId, thumbnailUrl, quality, title, headers, subtitles, audioTracks}`). `imageUrl` = the thumbnail or `TILE_URL` (`https://jssource.invalid/video-tile.png`). Old builds used `sumizuri-video:` (`JsLegacy.OLD_VIDEO_MARK`); `isVideo`/`decode` still accept it.
2. `JsMangaSource.getImage(page)` makes up a 1280×720 dark PNG for the tile (`tileResponse()`) and **never fetches** `TILE_URL`. A thumbnail that fails to load also falls back to the tile.
3. `ReaderVideos.play(context, readerPage)`:
   - creates or updates a hidden anime in the DB under source `ReaderVideos.SOURCE_ID = 7301844225610480103` (`ReaderVideosSource`, name "Videos in chapters", added in `AnimeSourceManager`), with url `"<mangaSourceId>\n<chapterUrl>"`
   - syncs its episodes = the chapter's video pages (`episodesOf`; episode url = the page url; newest-last order reversed)
   - starts `PlayerActivity`. Next and previous in the player walk the chapter's videos.
4. The player asks `ReaderVideosSource.getVideoList(episode)` → `ReaderVideos.videosOf(pageUrl)`:
   - downloaded in a CBZ (`archive` + `entry`): extracted to cache, played from the file
   - `listUrl`: `JsMangaSource.videoList(listUrl, pageHeaders)` → the source's `getVideoList`. Errors: "The source of this video isn't installed any more", "The source found no video to play here".
   - otherwise `url` (HLS goes through `HlsRelay`)
5. `ReaderVideosSource.getEpisodeList` reads the chapter's page list again from the manga source.

`ReaderVideos.playLinks(...)` reuses the same hidden source for the AniList page's openings and endings (url `-1\nanilist-videos:<title>`).

**Invariants**
- The `jsvideo:` page URL **is** the video. Anything that rebuilds `Page`s must keep `page.url`. `Downloader` re-indexes with `Page(index, page.url, page.imageUrl, page.uri)`, which is fine.
- `SOURCE_ID` is excluded from: History (`HistoryScreen`), the filler lookup and refetch (`PlayerViewModel` ~488 and `canRefetchFromSource`), pairing sync (`PairSyncData`), and the custom-id range (`reservedIds`). A new feature that lists anime may need the same exclusion.
- `TILE_URL` is the same for every tile page, so the chapter cache shares one entry for it (identical bytes, so harmless). Code that loads `page.imageUrl` directly instead of through `source.getImage` fails on tiles (the `.invalid` host).

---

### 3.6 HlsRelay: local proxy for HLS

**Why.** Some hosts glue a PNG/JPEG/GIF/BMP/WebP header in front of each MPEG-TS or fMP4 piece. A browser plays it; mpv sees "Video: png … error reading packet" and the video ends at once (40bf798d, 2026-10-09).

**How.**
- `wrap(url, headers)` stores `sessions[id] = headers` and `originals[id] = url`, and returns `http://127.0.0.1:<port>/p/<id>?u=<enc>&x=list.m3u8`.
- Pieces become `/s/<id>?u=…&x=piece.ts`. The `.m3u8`/`.ts` names are there because newer FFmpeg checks the extension and refused a host's `.png` (bc3bf897).
- The server is a `ServerSocket` on 127.0.0.1 with a random port, started lazily (a daemon thread "HlsRelay" plus a cached thread pool). Socket timeout 30 s, `Connection: close`, one request per connection.
- Incoming request headers are **ignored**. The fetch uses the session's headers.
- Playlist (`p`), or any body starting `#EXTM3U`:
  - rewritten against the final (post-redirect) URL
  - `URI="…"` in `#EXT-X-MEDIA`/`#EXT-X-I-FRAME` becomes a relayed playlist; other `URI=` (maps) become relayed pieces
  - `#EXT-X-KEY` / `#EXT-X-SESSION-KEY` URIs are only made absolute: **keys go direct, not through the relay**
  - any URI line in a playlist containing `#EXT-X-STREAM-INF` is treated as a sub-playlist
- Piece (`s`): if the source's headers get a non-2xx, it tries again with only `User-Agent` + `Accept: */*`. That covers pieces on image CDNs that refuse the Referer. Logged D or W `hls` "Stream piece answered 403 with the source's headers, worked without them: host".
- `stripFakeImage`: if the bytes start with an image signature, it searches the first 1 MB for three TS sync bytes 188 apart, else an fMP4 box (`ftyp/styp/moof/sidx`), and cuts there. Logged D `hls` "Removed a N-byte fake picture header from a stream piece".
- Bodies are read **fully into memory** (`body.bytes()`), not streamed.
- **DNS bypass (r68, b2836e9f).** If a request fails with an IOException mentioning `/0.0.0.0` or `/127.0.0.1:`, or the system resolver returns only any-local or loopback addresses (what ad blockers and filtering DNS answer), the request is retried through a client with DNS-over-HTTPS. It tries Cloudflare (`cloudflare-dns.com`, bootstrap 1.1.1.1/1.0.0.1), then Google (`dns.google`, 8.8.8.8/8.8.4.4), and drops local or loopback answers. The host is remembered in `bypassedHosts` for the process. Logs: W `hls` "<host> is blocked by this device's DNS or ad blocker (it answers 0.0.0.0); asking Cloudflare / Google DNS instead", then I `hls` "<host> reached through Cloudflare / Google DNS (200)". Only relayed requests get this: JS `host.fetch`, ffmpeg on direct URLs and mpv on non-HLS URLs do not.

**Predictable failure modes**
- **Session eviction.** Above 500 sessions, `sessions.keys.take(100)` removes 100 ids in `ConcurrentHashMap` hash order, **not oldest first**. A stream that is playing can lose its session; its next playlist or piece request then gets **404 "Not found"** (no AppLog line). Every `getVideoList` wraps every HLS video, so a long session with many servers or qualities can reach 500. If playback stops mid-way after heavy browsing, suspect this.
- **Sessions are in memory only.** A relayed URL saved anywhere (DB, download queue) is dead after a process restart.
- **`HlsRelay.original()` is never called.** Its comment says it exists for casting, but `ui/player/cast/CastMediaBuilder.kt` doesn't use it. Casting a relayed JS stream probably hands the TV a `127.0.0.1` URL it can't reach. Not verified on a device.
- Keys fetched direct: a host that wants the Referer on key requests depends on mpv sending `Video.headers`.
- Logs with tag `hls`:
  - W "Couldn't reach <host>: <msg>" means a 502 to the player
  - W "Playlist answered 403: <host>"
  - no `hls` lines at all while a JS HLS video fails usually means the URL didn't go through the relay (check `m3u8` detection)

---

### 3.7 ChapterVideoDownloads: saving chapter videos

**What it does.** When a chapter with video pages downloads, each video is saved next to its page's picture as `NNN.video.mkv` (streams, ffmpeg `-c copy`) or `NNN.video.mp4`/`.webm`/`.mkv` (plain files). Offline, the page gets its play button back.

**Flow** (`data/download/Downloader.kt` ~line 375–440):
1. `download.hasVideos = pageList.any { ReaderVideos.isVideo(it.url) }`.
2. Pictures download as usual. Thumbnail or tile pictures are saved under the normal names.
3. Then `ChapterVideoDownloads.saveAll(pageList, tmpDir, filenameOf = "%0Nd".format(page.number), …)`. N = `pageList.size` digits, at least 3: **the same formula `getOrDownloadImage` uses**.
4. `save()` skips if a finished `NNN.video.*` exists. It deletes stale `.tmp` files, gets `ReaderVideos.videosOf(page.url)`, and tries up to 3 videos in order:
   - `isStream` (m3u8 anywhere, `.mpd`, `/hls/`, `x=list.m3u8`) → `ffmpegSave`
   - else `httpSave` (switches to ffmpeg if the Content-Type turns out to be mpegurl or dash)
5. `ffmpegSave`:
   - picks one quality with `HlsVariants.forDownload` (best; r83)
   - passes the headers as `-headers`, plus `ffmpegStreamArgs`
   - `-map 0:v? -map 0:a? -map 0:s? -c copy -f matroska` into `cacheDir/chapter_video_download/<ms>.mkv`
   - copies that into the chapter folder as `.tmp`, then renames
   - progress: duration parsed from the "Duration:" log line, then `stats.time / duration`, capped at 99
6. Progress (a693dc09): `Download` has `videoProgressFlow`. With `hasVideos`, overall progress = pages/2 + video/2. Before that the ring showed 100% while ffmpeg was still running.
7. Offline: `DownloadPageLoader` calls `attachFromDirectory` (maps `NNN` or `NNN__001` split pictures to `NNN.video.*`) or `attachFromArchive` (CBZ: the n-th image entry in natural sort order ↔ page n; the page url gets `{archive, entry}`, and `extract()` copies the entry to `externalCacheDir/chapter_videos/<hash>.<ext>` on first play; that cache is never cleaned by the app).
8. `isVideoFile(name)` (`.video.` in the name) keeps these files out of page counts. It is used in `Downloader` (picture lookup, `isDownloadSuccessful`), `CheckDownloadsScreen` and `BrowsePreviewScreenModel` (`picturesWithVideo` puts ▶ on pictures that have a video).

**Known finding (not fixed): a failed video save leaves a chapter that looks complete.** `saveAll` catches each failure and only logs it (logcat E, so the Logs screen shows it under the class tag): "Couldn't save the video of page N in <title · chapter>; it stays a picture: <msg>". `isDownloadSuccessful` ignores videos, so the chapter is marked DOWNLOADED and nothing retries it. Offline, the page is just the thumbnail or tile, with no play button. Good line: I "Chapter video saved: <title · chapter>, page N".

**Invariants**
- The picture name and the video name must use the same `NNN`. Changing the digit count, or `page.number` versus index, in only one place breaks offline video pairing.
- Video files must keep `.video.` in the name, or they count as pages.
- `attachFromArchive` assumes the page order equals the natural-sorted image entry order.

---

### 3.8 The Code editor screen

**Where.** `ui/codeeditor/SourceEditorScreen.kt` (Voyager `Screen(sourceKey)`), `SourceEditorScreenModel.kt`, `CodeEditor.kt`, `SourceTemplates.kt`. It opens from the code button in Browse › Sources (`SourcesTab`), the JS entries in Extensions (`UnifiedExtensionsTab` `onJsEdit`/`onJsNew`), and the anime Browse panes.

**Layout.** Width ≥ 840 dp: the code on the left, a 420 dp side panel with Details | Test tabs on the right. Narrower: tabs Code | Details | Test.
- Top bar: back, title ("Add source" or the name, plus "Manga/Anime · JS/JSON · not saved"), JS extension | JSON declarative tabs (`KindTabs`), Copy all, Paste (replaces everything after a confirm dialog with the line count; a693dc09), Find, Run all, Save, Created Sources (folder icon), and a menu (Import, Export, Delete).

**Model behaviour.**
- **Its own runtime.** `runtime()` rebuilds when `json + code` changes, with `testing = true`, using `manager.idOf(key)` and the same `key`. **Testing uses the saved source's real `source_<id>` settings and `js_storage/<key>`.** A test that writes storage or settings changes the installed source too.
- **`detectDetails()`.** 1.2 s after typing stops, it creates a runtime (running the source's top-level code and `getSourcePreferences`) and fills name, icon, lang, base URL and the Manga/Anime kind from `metadata()`. It is skipped while the code is still the untouched template.
- **Import** (`importCode`):
  - JSON if the file name ends `.json`, or (not `.js` and the text starts with `{`).
  - Anime if JSON contains `"videos"`, or JS contains `getVideoList`.
  - Details come at once from a regex over the text (`quickInfo`).
  - Importing while a saved source is open **starts a new key**, so it doesn't overwrite.
- **Export** writes the code via `CreateDocument`, named after the source with unsafe characters replaced by `_`.
- **Save** → `manager.save(...)` with the Details fields as overrides. On success, a snackbar "Saved. X is in Browse → Sources (manga)" with Open. On failure, the "Couldn't save" dialog with the error (copyable) and E `js` "Saving X failed: …".
- **Test panel.** A search field, plus square buttons: Run all, Popular, Latest, Catalogs, Filters, Settings (opens `SourceSettingsDialog`, which writes the real `source_<id>`), Clear.
  - The last 20 results are kept, each card showing at most 60 items.
  - Tapping an entry runs `getDetails` + `getChapterList`. Tapping a chapter runs `getPageList` or `getVideoList`. A page with `videoListUrl` shows as "Page N · video (tap to test)" and runs `getVideoList`.
  - `runAll`: tab catalogs (page 1 each), then Popular and Latest (skipped when tabs exist and the flag says missing), then search, then the first entry's chapters, then the first chapter's pages or videos.
- **Console.** The runtime's 500-line buffer; the last 200 lines are shown. Copy (all lines) and Share as .txt (`shareConsole`).
- **Created Sources** dialog: every saved source (enabled or not), plus "New JavaScript" / "New JSON definition".

**CodeEditor.kt.**
- A `BasicTextField` with a `VisualTransformation`: regex syntax colours, turned off above 150,000 characters.
- Line numbers, no wrapping (min width 600 dp, scrolls sideways).
- `keepIndent`: a typed newline copies the indent, plus 2 spaces after `{ [ (`.
- Find bar (r83, 2a101b49): Ctrl+F or the search button; all matches yellow, the current one orange; "N of M"; ▲/▼ or Enter (Shift+Enter = previous); "Aa" for exact case; at most 10,000 matches; Back closes it. It scrolls only on search or navigation (`revealTick`), not while typing.
- The text area keeps its own `TextFieldValue` and only resets from the `code` prop when the two differ, **putting the cursor at 0**. Paste, import and open all jump to the top.

**Predictable problems**
- **Unsaved code is lost without warning.** There is no dirty check on Back, on opening another source from Created Sources, or on New. (Not a recorded finding, but visible in the code.)
- `closeRuntime()` cancels the job and closes the engine outside `runtimeMutex`. Closing while an evaluate is still running relies on quickjs-kt handling that (not verified).
- Each pause in typing builds a new QuickJS engine and runs the source's top-level code. A source that fetches at top level fires requests while you type.

**How to check.** The Console shows `[net] GET <url> -> 200 in 85ms` for each request (testing only) and `[error] search failed: …`. The same lines go to Logs under `js:<name>` (`js:editor` while the name is blank).

---

### 3.9 JsTags, JsBrowserFetch, JsIcons (supporting pieces)

- **JsTags** (`getTags()`):
  - Runs in its **own** runtime on an IO scope, one Mutex per key.
  - The result is saved to `js_tags/<key>.json` and read **once**. Since r80 (1d0c9b48) only "Refresh tag list" reads it again.
  - A failure blocks retries for 1 h (`RETRY_AFTER_FAILURE_MS`) and logs W `js` "<name>: getTags failed: …". Success logs I `js` "<name>: got N tags in M lists". MemoryLog events record "tag download started/done/stopped".
  - Lists are `{name: [...]}` or a plain array (the shared list `"*"`). At most 100,000 per list.
  - Filter boxes get a `LiveList` that follows the cache, so a box created before the download finished still fills in.
  - Suggestion order: the filter's own options, then the lists named by `tags` (default: the filter's key), then `"*"`.
- **JsBrowserFetch:**
  - One hidden WebView per origin, closed after 3 min idle. Requests to the same origin are serialized by a Mutex.
  - It waits up to 30 s for a challenge to pass by itself. Otherwise it asks `BotCheckCoordinator.await` (opens WebView for the user when the app is on screen), then `BotCheckReporter.report` (a notification), and throws "<origin> wants you to pass its bot check: open the source in WebView, pass it, then try again".
  - Headers a page `fetch()` may not set (UA, Referer, Cookie, Origin, …) are dropped. The Referer is passed as `referrer` only if it has the same origin.
  - Responses have a 45 s poll timeout. `browserFirst` is per process.
- **JsIcons:** icon order is `iconUrl`, then `/apple-touch-icon.png`, then `/favicon.ico`. Only icons Android can decode are kept (SVG is skipped). Each is drawn with a 12% margin so it looks like an APK icon. The copy in `downloads/Icon/<name>.png` is restored after a reinstall or restore (r47).

---

### Settings in this area

There are no app-wide preferences specific to JS sources. These stores change their behaviour:

| Setting as shown in the app | Preference key / store | Default | What it changes | Fork-added? |
|---|---|---|---|---|
| Source on/off switch (Extensions, JS entry) | `enabled` in `js_sources/<key>/meta.json` | on | Leaves the source out of the source flows | yes |
| APK on/off when a JS source shares its id | `js_sources/apk_off.txt` | APK on | APK registered as stub only, so the JS source is used | yes |
| Source settings (from `getSourcePreferences`) | SharedPreferences `source_<id>`, author-defined keys | author's `defaultValue` | Whatever the source reads with `host.preference` | yes |
| Refresh tag list | `js_refresh_tags` (not persistent); data in `js_tags/<key>.json` | read once | Reads `getTags()` again | yes |
| Last tab catalog | `js_tabs` → `<key>` | first tab | Tab Browse opens on | yes |
| Languages in Browse | `SourcePreferences.enabledLanguages()` / `animeDisabledLanguages()` | — | Saving a JS source turns its language on | changed |
| Disabled sources | `disabledSources()` | — | Saving removes the JS source's id. JS/APK pairs don't use it. | changed |
| Parallel page downloads | `downloadPreferences.parallelPageLimit()` | upstream | Pictures only; chapter videos save one at a time afterwards | no |
| Save chapters as CBZ | `downloadPreferences.saveChaptersAsCBZ()` | upstream | Videos go inside the CBZ; offline play extracts them to cache | no |
| Extra logging (EH log level) | `EHLogLevel` | off | VERBOSE logcat into Logs | no |

### Cross-feature impact map

- Changing **`JsLegacy.ID_SEED` or `idFor()`** changes the id of every JS source without an `id` line. Their library entries, history, `source_<id>` settings and `js_weburls_<id>` all appear lost.
- Changing **`customId()` / `reservedIds()`** affects which APKs a JS source can replace, and the source maps in `AndroidSourceManager`/`AnimeSourceManager` (JS is put after APKs and wins).
- Changing **`JsVideos` HLS wrapping or the relay URL format** affects `HlsVariants.isPlaylist`/`forDownload` (quality split and best-quality download), `ChapterVideoDownloads.isStream`, and the player's Qualities menu. All of these look for `m3u8` anywhere in the URL.
- Changing **`ReaderVideos` page-url encoding** affects reader video buttons, `ReaderSideSheets` / Browse preview play tiles, `Downloader.hasVideos`, saved-video pairing offline, and episodes already stored under the hidden source (their url is the encoded page url).
- Changing **`ReaderVideos.SOURCE_ID`** orphans the hidden anime rows. It also has to stay in `reservedIds`, `HistoryScreen`, `PlayerViewModel` and `PairSyncData`.
- Changing **Downloader picture file naming** breaks `ChapterVideoDownloads` pairing (`NNN` and `NNN__001`), `picturesWithVideo` and CheckDownloads.
- Changing **`JsRuntime` limits or the mutex** affects Browse, details, the player (`getVideoList`), downloads (`getPageList`), tag downloads and the editor at once. They all share one runtime per source, except tags and the editor.
- Changing **`JsSourceInfo` fields** affects meta.json loading, backups (proto 710 stores it as a JSON string) and pairing payloads between devices on different releases.
- Changing **`JsPreferences.write`/types** affects the backup preference mapping (`toBackupPreferences`), the editor's Settings dialog and the normal source settings screen.
- Changing **`JsHtmlPages`** affects the memory lines in Logs (`JsHtmlPages.totals()`).
- **`host.fetch` cookies** are the app's shared cookie jar, so passing a bot check in WebView helps JS sources and APK sources alike.

### Build pitfalls seen in this area

- **Native library name clash** (ac022ea5): quickjs-kt's `libquickjs.so` collides with the extensions' engine. Keep the `unpackQuickjsKt` rename and `System.loadLibrary("quickjskt")`. Upgrading quickjs-kt means checking that the AAR layout still has `classes.jar` and `jni/**/libquickjs.so`.
- **R8:** `com.dokar.quickjs.**` must stay kept (JNI callbacks). Removing the rule builds fine but crashes at runtime in release.
- **Compile fixes in c80868cc:**
  - `elements += element` inside a `getOrPut {}` lambda was ambiguous for the compiler. Use `elements.add(element)`.
  - A Compose `val openInBrowser = { … }` whose branches return different types needed an explicit `() -> Unit`.
- **r83/r84 Compose notes** (from the session log): keep `remember` calls unconditional (no `remember` inside an `if`), and keep lines under the ktlint length limit (Spotless runs in CI).
