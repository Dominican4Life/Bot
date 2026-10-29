# Fork guide: how this Komikku fork works, why, and what breaks what

This is the engineering reference for the owner's fork of Komikku (GitHub repo `Dominican4Life/Bot`, app id `app.komikku.beta`). It covers what the fork added on top of upstream Komikku (Mihon + TachiyomiSY): the anime side (Anikku/Aniyomi), AniZen's mpv player, in-app JS/JSON sources and the Code editor, the browse/extension redesign, previews, and many fixes. For each part it records **how it works, why it was built that way (the bug it fixed), what must not change, and what will break if it does**.

**Who it's for:** the owner, and any AI session that changes this code. The owner can only test on a phone/tablet, and every build costs a release cycle (r1, r2, …). So **read the relevant section before changing anything, and check its "Cross-feature impact map" and "Predictable failure modes"**.

**Repo rename:** the GitHub repo was renamed from `Dominican4Life/Komikku` to `Dominican4Life/Bot` (local remote `https://github.com/Dominican4Life/bot`, CI paths `/home/runner/work/Bot/Bot/`). The app, the releases (`Komikku beta rN`) and the APK file names stay "Komikku".

Written 28–29 October 2026, at r84 (commit a693dc09), and updated for r85 (0144ac1a) and r86 (f80f3b5a). The sections were written from the code, the fork's 237 commits and the conversation history. Facts marked unverified were not tested on a device. When the code and this guide disagree, the code wins; update the guide.

## Sections

| # | File | Covers |
|---|------|--------|
| 1 | [01-build-ci-conventions.md](01-build-ci-conventions.md) | Modules, build variants and version numbers, the CI workflow, **signing (why releases up to r85 needed an uninstall, fixed in r85)**, release flow, repo rules, logging, networking, past compile errors, **"Before you push" checklist** |
| 2 | [02-browse-extensions.md](02-browse-extensions.md) | Browse tabs, browsing a source, filters and tag boxes, saved searches, Feed, global search, the unified Extensions screen, **repositories (the repo-issue history r64–r76)**, installers and Sideload, the r84 outside review (addressed in r85) |
| 3 | [03-js-sources.md](03-js-sources.md) | The app side of JS/JSON sources: QuickJS runtime and host API, saving/keys/ids, how a source maps onto the app, videos (JsVideos, ReaderVideos), the HLS relay, saving chapter videos, the Code editor |
| 4 | [04-player-anime.md](04-player-anime.md) | How playback starts, decoder, **multi-quality HLS splitting (HlsVariants)**, Qualities menu order and button, audio tracks, anime downloads, AniList episode cards, IPv4-first DNS, the `Video` model, Cast/torrent |
| 5 | [05-images-previews-reader.md](05-images-previews-reader.md) | Coil setup, covers (one download per cover), image logs, **animated tiles and header sniffing (why only the first bytes are read)**, preview panels, the reader's side panels, video hand-off |
| 6 | [06-library-downloads-backup.md](06-library-downloads-backup.md) | **Database migrations the fork added**, backup contents and restore order, downloads, library refresh, tracking, pair sync, the Logs screen |
| 7 | [07-settings-catalog.md](07-settings-catalog.md) | **Every setting**: title, key, default, what reads it, fork-added or not; hidden keys; dead settings; settings that conflict; restart-only settings |
| 8 | [08-writing-js-json-sources.md](08-writing-js-json-sources.md) | The rules guide for writing JS/JSON sources (for source authors), including "Mistakes to avoid" |

## Ten rules that would have prevented most past problems

1. **Explain the plan to the owner before building.** Builds go out as releases; a wrong guess costs a cycle.
2. **Measure before diagnosing.** Add a log line (AppLog) and get the owner's Logs first. A wrong diagnosis cost a cycle before: the reader cache was assumed to be 75 MB, but the owner had 5 GB (section 5).
3. **Never reorder a list that something indexes.** Selection, download and video state use list positions. Reorder only for display, through an index map (`QualityOrder`, section 4).
4. **One shared download per cache key.** Covers broke when Popular and Latest downloaded the same cover twice (r81, section 5). Keep `MangaCoverKeyer` keys identical across screens.
5. **Read headers, not whole files.** Animated tiles are recognised from the first 21 bytes. Loading whole files for the Pages panel of a 381-page animated gallery ran out of memory (r79, section 5). Only one tile animates at a time.
6. **Detect streams by content or by "m3u8" anywhere in the URL, never by the path ending.** Relayed streams look like `http://127.0.0.1:PORT/p/<id>?u=…&x=list.m3u8` (r84, sections 3–4).
7. **Database: every `.sq` change needs a matching `.sqm` migration.** Up to r85 the owner reinstalled every release (r85 needed the last uninstall, for the signing fix), so no migration had run on a device. From r86 on, releases install over each other, so a bad migration crashes the app on open (sections 1 and 6). App `Migration`s need a version above the latest published release.
8. **Backups: anything keyed by a database id comes back pointing at the wrong row after a restore** (ids change on a fresh install). Prefer keys by source + url (sections 6 and 7).
9. **`source-api` changes break installed extensions at runtime, not at compile time** (`NoSuchMethodError`). Add overloads; don't change signatures (`Video` has three `copy` overloads for this reason, one of them hidden for ext-lib 16; sections 1 and 4).
10. **Compile risks CI catches only after a push:** missing imports (r81), Compose `remember` inside conditions, the `Video.copy` overloads (pass `videoPageUrl`/`type`/`mimeType` to pick the full one). Use the checklist in section 1. Style is not checked at all: CI only runs `assemblePreview` (no Spotless/ktlint), and ktlint's max-line-length rule is disabled, so long lines don't break the build; still keep lines ≤ 120 characters (`.editorconfig`) so a later `spotlessApply` diff stays small.

## Shared code: changing one thing affects another

The biggest hubs. Every section ends with a fuller impact map.

| If you change… | …you also affect | Section |
|---|---|---|
| `NetworkHelper` client / DNS wrapper | every source, extension, cover, JS `fetch`, HLS relay, trackers, AniList | 1, 4 |
| `HlsRelay` URL format | `HlsVariants.isPlaylist`/`forDownload` (player qualities, best-quality downloads), `ChapterVideoDownloads.isStream`, FFmpeg's `.m3u8`/`.ts` extension check | 3, 4 |
| `HlsVariants` (split / expand) | every anime source in the player, downloaded-episode playback, anime downloads **and** chapter-video downloads | 4 |
| Split video titles | saved default stream, self-healing, `QualityOrder` grouping, metered-network quality cap | 4 |
| `ReaderVideos.isVideo` / page-url encoding | ▶ badges in all grids, lone-video hand-off, chapter downloads (`hasVideos`), offline video pairing, hidden anime rows | 3, 5, 6 |
| `MangaCoverKeyer` | Browse Popular/Latest, kept tabs, library covers (one key = one download) | 5 |
| `AnimatedThumbnail` | reader Pages panel and Browse/library preview panels (same component) | 5 |
| `JsRuntime` limits / mutex | Browse, details, player, downloads of that JS source (one runtime per source) | 3 |
| JS source id rules (`idFor`, `customId`) | library entries, history and settings of every JS source (they look lost) | 3 |
| `ExtensionStoreUrls.key()` / available-extension map key (`availableKey`: repo + package + signing key since r86) | repo counts, duplicates, updates, sideload restore | 2 |
| Manga `ExtensionManager` install steps | **anime** installs too (they share the installer and are forwarded from the manga manager) | 2 |
| `SideloadFolder.sync` | can uninstall extensions whose APK is missing from the folder, on every start (since r86 not when the folder is missing, empty or unreadable) | 2 |
| `AutoCompleteItem` (tag boxes) | E-Hentai tags, JS tag filters, anime tag filters, Browse preview tag hold | 2 |
| Library flows without `LibraryRefresh.pausedWhileRestoring` | slow/stuck restores come back (r72/r73) | 6 |
| Download folder naming | download badges, Check downloads, cover import after restore, `index.json`, chapter video pairing (`NNN`, `NNN__001`) | 3, 6 |
| `Download.progress` | the queue's bars; anything waiting for 100 % | 6 |
| Backup proto numbers | reading older backups and Anikku imports | 6 |
| Settings keys | renaming a key loses the saved value; settings named after database ids need the backup skip list (`AnimeBackupHelper.isAnimeIdKey`). (Up to r85 the reader and player shared the skip-filtered key and profiles silently overrode the decoder switches; since r86 the player has `pref_player_skip_filtered_episodes` and the switches are `locked` to the profile's values.) | 7 |

## Known open issues and risks (at r86)

Collected from all sections. These are what to look at first when something odd is reported. Items fixed in r85/r86 are listed under "Fixed since r84" below, not here.

**Release and update**
- r85 was the last release that needed an uninstall (it moved to the committed key). r86 and later install over the previous one, so database and app migrations now really run on the owner's device (rule 7, section 1).

**Extensions and repos (section 2)**
- The r85 fixes from the r84 review (chips, Repositories (0), keyboard, return after "Install unknown apps", installer sessions, Sideload wording) are not yet confirmed on a device.

**JS sources (section 3)**
- The 30 s JS limit (r86) pauses during host calls, so a host call that itself never returns isn't stopped by it (fetch has its own 60 s limit; `host.render` waits as long as `options.timeout` says, uncapped).
- The HLS relay drops the 100 oldest sessions (by when they were handed out, not by last use) once it passes 500, so a video that has played for a long time while many new relay addresses were made can still 404.
- Chapter video save failures still leave a chapter counted as downloaded (since r85 the Logs say which chapter is missing videos).
- Importing a source doesn't call `setApkEnabled`, and `setApkEnabled` swallows write errors.
- The unsaved-changes dialog (r86) only appears when leaving the editor screen, not when switching sources or tabs inside it.

**Player and anime (section 4)**
- The 4 s limit can't interrupt ffprobe.
- The preview player, the outside player and anime downloads don't go through `split`.
- Network-aware preloading caps quality at 720p on metered networks, which its label doesn't say.

**Images (section 5)**
- Coil's image disk cache is ≤ 250 MB and shared by non-library covers, Browse preview pages and JS preview tiles. (Since r86 the reader reuses a page already downloaded by a preview panel when the saved image URL matches, `HttpPageLoader.reuseFromPreview()`.)

**Backup (section 6)**
- Settings named after anime ids aren't restored (per-anime intro length and last season start come back empty), and anime category settings are mapped by name only for backups made with r85 or later (older ones don't carry anime category ids).
- Not in a backup:
  - Merged anime.
  - JS source storage.
  - Tracker logins, unless "Private settings" is ticked.
  - The Logs file.

### Fixed since r84 (removed from the list above)

- **Signing** (r85): one key for all releases; CI checks it.
- **Extensions** (r86): repos with the same (or no) signing key no longer overwrite each other's packages (`availableKey`, `copyFor`). Manga install errors show a toast and a `W extensions` line instead of silently resetting the row.
- **JS** (r85/r86): JS that runs 30 s without returning to the host is stopped (`Watchdog`, r86). Imports check source keys (r85) and custom ids (r86). The relay trims the oldest sessions, not random ones (r86). One shared `JsStorage` per storage folder, so runtime copies don't overwrite each other (r86). Editor tests use a test copy of settings and storage (r86). An unsaved-changes dialog when leaving the editor (r86).
- **Player** (r86): quality lists with only bandwidth pick the highest, not the lowest. Quality lists are fetched with the source's headers.
- **Images** (r86): preview-panel pages are reused by the reader. CBZ preview tiles are streamed instead of copied into memory.
- **Backup** (r85/r86): anime extension settings (proto 703), player custom buttons (704) and watch time (`BackupAnimeHistory` 3) are in backups since r86. Settings holding anime ids, including `anime_download_queue`, are skipped on restore since r85.
- **Settings** (r86): "Show restoring progress banner" is hidden (it did nothing); "Remember last used volume" and webtoon "Tap scroll by page" work.

## Changes in r85 (29 October 2026)

- **Signing:** `app/build.gradle.kts` has an explicit `signingConfigs.debug` pointing at `.github/custom-debug.keystore` (PKCS12, alias `androiddebugkey`, passwords `android`); the preview build type uses it. CI's "Check signing key" step fails the build if an APK's certificate SHA-256 isn't `2c43cb4f…264e34c4f`. r85 was verified to carry it. `.gitattributes` marks `*.keystore` binary. **Never change the key**: every install would need an uninstall again.
- **Version code = release number.** CI's "Pick release number" step sets `KMK_RELEASE` before the build. Gradle uses it as `versionCode` (at least 82) and `BuildConfig.RELEASE_NUMBER`. About shows "Beta r85 (commit count · sha)". App `Migration`s run for versions above the installed one, so a new migration must use a version above the last release that's already out.
- **First launch:** a fresh install says "Welcome to v…" (`KMR.welcome_version`) instead of "Updated to".
- **Code editor:** the top ▶ is gone (Run all is in Test). Copy, Paste and Search code are at the top of the ⋮ menu; Search switches the phone layout to the Code tab (`phoneTab`). The subtitle is one line. An empty successful run says "No results".
- **Extensions:** filter rows hide while the keyboard is open, and the pager has `imePadding()`. While searching, tabs read "X (n of m)" and empty lists say "No matches", with no update banner. Repo kind chips use `FlowRow`. The Sideload warning wording was fixed.
- **Install recovery:** `ExtensionsReturn` (a separate prefs file, not in backups) remembers the search when an install starts. If the app starts again within 10 minutes (Android restarts it after "Install unknown apps" is turned on), Home opens Browse › Extensions on Packages with that search. The flag is cleared when the install flow ends.
- **PackageInstaller:** status broadcasts for any session other than the active one are ignored and abandoned, and the app's leftover sessions are abandoned when the installer starts. An in-app cancel of the active install resets the row and continues the queue.
- **JS sources:** keys must match `[A-Za-z0-9_-]{1,64}` and resolve inside the sources folder (import, restore, delete, `dir()`). Switching a source on or off only happens once `meta.json` is written. A restore writes the backup's settings even when the device keeps its own code.
- **Chapter videos:** a failed rename is a failure; `saveAll` returns the failure count and logs `download` W "X of N videos couldn't be saved".
- **Backup:** `BackupAnimeCategory` has `id` (proto 3). Anime category settings (`AnimeBackupHelper.CATEGORY_KEYS`) are mapped by name after the anime categories are restored. Settings named after anime ids (`isAnimeIdKey`) are skipped in the app settings restore.

## Changes in r86 (29 October 2026)

- **Library preview:** switching to a title from another source picks it on the new source's model before swapping models, so the panels never close and the covers don't jump (`LibraryMangaPreview.kt`, `modelSourceId`).
- **Logs:** the quality probe of a downloaded file (`HlsVariants.localQualities`) is filtered from `mpv/ffmpeg` lines for 3 s (`isProbeOutput`); a plain file path no longer logs "unknown scheme". More tab has a Logs entry (phones).
- **JS:** a `Watchdog` in `JsRuntime` interrupts JS that runs 30 s without returning to the host (host calls don't count). Imports get the custom-id checks. `HlsRelay` trims the oldest sessions. Editor tests use a test copy (`source_<id>_editor`, `js_storage/<key>_editor`). An unsaved-changes dialog appears when leaving the editor. One shared `JsStorage` per storage folder.
- **Player/anime:** variants are ranked by height, then bandwidth (`BEST` for downloads). Quality lists are fetched with the source's headers (`split(videos, sourceHeaders)`, `forDownload(video, headers)`). Anime downloads detect relayed and untagged playlists. `AnimeRepository.refreshFromSource` runs one load per anime at a time, with a 60 s skip in `AnimeScreenModel` init. Episode cards are swipeable.
- **Extensions:** the available map key includes the repo (`availableKey`, `copyFor`). Install failures show a toast plus a `W extensions` line (`Installer.failureReasons`). `SideloadFolder.sync` removes nothing when the folder is missing, empty or unreadable.
- **Settings:** "Remember last used volume" and webtoon "Tap scroll by page" work. The restore-banner row is hidden. Decoder switches are locked to the values a Performance profile actually uses (`locked` on switch preferences). The player has its own `pref_player_skip_filtered_episodes` (seeded from the reader's value).
- **Backup:** proto 703 holds anime extension source settings, 704 player custom buttons, and `BackupAnimeHistory` 3 watch time (larger value kept).
- **Images:** `HttpPageLoader.reuseFromPreview()` copies a preview-downloaded page into the reader cache when the saved image URL matches. CBZ preview tiles are streamed (`ArchiveEntrySource`).

## Changes in r87 (29 October 2026)

- **Faster video downloads:** `data/download/video/ParallelVideoDownload.kt` handles both anime episodes and chapter videos.
  - **HLS:** it picks the highest resolution, then the highest bitrate, plus the audio and subtitle groups that variant uses. It downloads the pieces, the `#EXT-X-MAP` init segment and the AES-128 keys with K workers into `cache/video_pieces/<key>/`. Pieces from an earlier try are kept, matched by a manifest fingerprint. Each piece gets 4 retries, and a 429/503 lowers K. Local playlists are then joined by FFmpegKit with `-protocol_whitelist file,crypto,data -allowed_extensions ALL` and the same `-map` and copy options as before.
  - **Plain files:** a `Range: bytes=0-0` probe. A 206 with the full size (at least 4 MB) is split into ranges written into a pre-sized file.
  - **Fallback:** anything unsupported uses the old ffmpeg-from-URL path. Unsupported means: no ENDLIST, BYTERANGE, SAMPLE-AES, separate VIDEO groups, DASH, or `ffmpegStreamArgs`.
  - **Setting:** `pref_video_download_connections` (1–8, default 4; 1 = old behaviour).
  - **Logs:** tag `download` covers start, progress every 10 %, retries, slow-down, join, fallback reason and the summary.
- **HlsVariants:** keeps the highest-bandwidth copy per height. The "Mbps" label is `Locale.US`.
- **Download the picked stream:** `EpisodeOptionsDialog.onDownload(Video)` goes to `downloadEpisode(episode, video)` and then `enqueue(video=)`. It's not kept across an app restart.
- **Episode file names:** new downloads are named `scanlator_name_E<number>` (`_U<hash>` when the number is unknown). Lookups try the new name, then the old one, with exact name matching.
- **Episode sync safety:** `AnimeRepository.loadFromSourceNow` throws `NoEpisodesException` (and deletes nothing) when the source returns no episodes but some are saved. `syncEpisodes` carries seen, progress, bookmark, filler and history over when one removed row and one new row share an episode number (the source's links changed).
- **Anime page:**
  - Card titles via `ownEpisodeName()`, and cards follow the Display setting.
  - `State.watchingList()` (excluded scanlators out, filtered episodes out when skip-filtered is on) drives Resume, toolbar downloads and mark-previous.
  - Selection and dialogs live in the screen model, or use `rememberSaveable`; one list state is shared by both layouts.
  - Download ticks only update progress, and `nextEpisode` is kept in state.
  - Flag writes go through a queue in tap order.
- **AniList:**
  - Tab loads are tracked and cancelled per title.
  - Match order: none/manual, tracks, `auto:`, search.
  - "Watch now" skips the reload when the last one was under 60 s ago.
- **Tags:** `onHold` uses a stable `pointerInput`, and a double tap copies the tag. A single tap waits about 300 ms before searching.
- **Smaller fixes:**
  - The "Uncategorized" (0) category is kept on restore. A set where no category matches stays unchanged.
  - The editor asks before replacing unsaved code (open, new, import). Test storage is `js_storage/<key>.editor`.
  - Sideload APK names are matched case-insensitively.
  - The relay evicts least recently used sessions.
  - `JsStorage.forget` closes stores.
  - CI concurrency is repo-wide, with no parallel builds, and publishing skips taken tags.

## Changes in r88 (29 October 2026)

- **Player:**
  - `PlayerActivity` no longer replaces the app's uncaught-exception handler. Errors go through `SetVideoLoadError` and `setInitialEpisodeError`.
  - `loadEpisode` clears `currentHosterList`, and a failure arrives as `EpisodeLoadResult.error`.
  - `hosterGeneration` guards every late write from `loadVideo`, `loadHosters`, `loadBestVideo`, `onVideoClicked` and `onHosterClicked`. `updateAt` ignores out-of-range indexes.
  - Progress, history and watch-time saves are caught. If the episode row is gone, `findReplacedEpisodeId` re-points them, and `episodeIdRemap` keeps the mapping.
  - `init` resets the quality index and hoster state when a new episode opens in the running player.
  - Self-heal resets the selection.
  - Preloaded hosters aren't split twice (`alreadySplit`).
  - `filterEpisodeList` drops excluded scanlators.
  - Reverse landscape now uses `SCREEN_ORIENTATION_REVERSE_LANDSCAPE`.
  - `CastManager` registers one callback and stops the previous collector.
- **Downloads:**
  - Each running episode is its own Job. Cancel and delete stop it and remove its pieces and `.tmp` file.
  - The same episode can't run twice at once.
  - Old-name and prefix lookups are used only when exactly one episode has that name.
  - `ParallelVideoDownload.cleanUp` runs at manager start and when the queue empties. Pieces are discarded on cancel, delete and final failure.
  - The resume fingerprint keeps the query when two piece addresses would be the same. Keys are re-checked on resume.
  - HTML or non-media pieces are rejected.
  - A failed rename fails the download.
  - The summary speed counts only this run's bytes, and progress never goes backwards (`monotonic`).
  - Pieces with no extension are saved as `.m4s` when the playlist has `#EXT-X-MAP`.
  - `downloadedCount` and name matching count only video files (`isEpisodeVideo`), so `cover.jpg` no longer shows as "2 downloaded".
  - `ChapterVideoDownloads.isStream` matches the anime side.
- **Library and backup:**
  - `syncEpisodesNew` returns the truly new ids, and auto-download uses them.
  - `refreshFromSourceResult` doesn't throw on a details-only failure.
  - Restore maps anime category settings with the unfiltered category list, also when only App settings is chosen.
  - `anime_airing_cache` and `__PRIVATE_anime_track_pending` are in `ID_KEYS`.
  - Webtoon "tap scroll by page" works in both directions.
  - Anime update counts and a failure notification (id −1814).
  - Global search doesn't cache errors.
  - Browse writes results only when the load generation still matches.
  - `LocalPageFetcher` closes the source when a fetch is cancelled.
- **Extensions and editor:**
  - Repo chips and source rows have unique keys.
  - `ExtensionsReturn` is taken before the tabs are composed and cleared when no install is left running.
  - The editor keeps edits made during a save, cancels `detectJob` when the source changes, and shows snackbars until they're dismissed. Test copies of unsaved keys are dropped.
  - The relay trims using a snapshot.
  - `InstallOrigins` records the repo an install came from, and `copyFor(installedFrom)` prefers it.
  - `SideloadFolder` tracking is locked.
- **Anime preview panels:**
  - Each episode row has a download button (`AnimeEpisodeDownloadIndicator`), and there's a download menu.
  - The preview player's Qualities menu is grouped by server (`AnimeVideoFetcher.fetchServerVideos`, sorted with `QualityOrder`).

## How to use this guide when adding a feature

1. Find the section for the area you're changing, and read its feature entry, invariants and impact map.
2. Search this folder for the class or preference key you'll touch (`grep -rn "HlsVariants" docs/fork-guide`). Every section that mentions it is a place that can break.
3. Plan the change, list what it could break, and tell the owner before building.
4. Add an AppLog line that proves the new path works (section 1, "Logging conventions"), so one test run tells you whether it worked.
5. Run section 1's "Before you push" checklist.
6. After the release, update the relevant section: what changed, why, and which release.
