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

## Changes in r89 (30 October 2026)

Commit 4cd8b984. Signing key checked (same SHA-256 as always).

- **Pair sync, new pop-up** (`ui/pairsync/PairSyncDialog.kt`; `PairSyncScreen.kt` is deleted):
  - Opens as a `Dialog` from the tablet rail (`HomeScreen`) and from More › Sync devices (`MoreScreen` `onClickPairSync`), so phones can sync now.
  - Pages: main, Anime, Manga, History, Extensions, Repos, Code sources, Pair, Show a code, Enter a code, Activity. Listening runs in a `DisposableEffect` while the pop-up is open.
  - The main page has 4 tiles in this order: Anime, Manga, History, Extensions. The owner asked for anime before manga.
- **Spotting other Komikku devices** (`pairsync/PairSyncDiscovery.kt`):
  - The device showing a code sends a BLE advert with its own service-data UUID and a 4-byte hash of its Bluetooth name.
  - The device entering the code scans for that advert and tags classic devices whose name hash matches (KOMIKKU tag).
  - Devices with audio, wearable, health, toy, peripheral or imaging classes are hidden.
  - BLE may be filtered on some phones (`neverForLocation`). Then there's no tag, but phones and computers stay visible.
- **Sync data** (`pairsync/PairSyncData.kt`):
  - New `SyncSelection` fields: `animeCategories`, `extensions`, `repos`, `repoUrls`, `installedExtensions`, `sideloadedApks`. Old names are kept, so saved selections still parse.
  - `backupAnimeCategories` is now sent and restored before the anime. Before r89 the category list wasn't sent, and anime categories were created in whatever order the anime arrived.
  - `SyncPayload` adds `repos`, `extensions`, `apks` and `installed`, all with defaults.
  - The sync request carries a `have` list of installed packages, so only APKs the other device lacks are sent. The initiator now builds its export after receiving the other device's payload.
  - Missing repos are added, never removed.
  - Missing extensions and newer versions are offered with Install / Update buttons (`pairsync/PairSyncInstall.kt`). Nothing installs without a tap.
  - Received APKs go to `cacheDir/pair_sync_apks`. Limits: 64 MB per APK, 256 MB in total.
- **Pair sync fixes** (`pairsync/PairSync.kt`):
  - `answer()` catches everything, and the scope has a `CoroutineExceptionHandler`. A dropped connection used to crash the waiting device.
  - The first frame (before the key is known) is capped at 256 KiB. After that, frames are capped at 512 MiB and read in 64 KiB pieces, so a lying length can't allocate memory up front.
  - Send and receive report progress.
  - Stop (`stopSync`) closes the socket. A run counter stops an old run from resetting a newer one.
  - Peers are now stored under `Preference.privateKey("pair_sync_peers")` (`__PRIVATE_pair_sync_peers`), so pairing secrets are no longer in normal backups. The old key is moved over once, then deleted.
  - Merging never removes anything: something deleted on one device comes back from the other on the next sync.
- **The Updates tile was dropped on purpose.** The Updates tab is built from chapter and episode `dateFetch` plus read/seen marks. Library sync already copies both, so the two Updates lists already match.
- **AppLog:**
  - `init` no longer reads the whole file on the main thread. The writer loads the newest 2 MB / 4000 lines in the background.
  - The file is trimmed to 1 MB when it passes 2 MB, checked every 200 writes.
  - Messages are cut at 16,000 characters.
- **Updater:** "What's new" reads Dominican4Life/Bot releases (`AppUpdateChecker.FORK_RELEASES_REPO`, including pre-releases). The Auto-update row is hidden unless `updaterEnabled`. Check for updates is still off.
- **Device files:**
  - Album art is decoded small (`decodeSmall`, about 384 px).
  - A rename that only changes letter case goes through a temp name.
  - Favourites follow folder renames.
  - Search runs again after a rename or delete.
  - The delete message says "Deleted N of M".
  - `summary()` counts while walking the cursor instead of loading every row.
  - Also in r89 (local commit 08f01392): a JS source stays off after a save, empty test results say "No results", search walks storage for files MediaStore misses, and folder listing is parallel.
- **Browse:**
  - The Filter panel works on a copy (`copyOf`), so the shown tab's `FilterList` is no longer shared. The old "live FilterList" invariant in section 02 no longer holds.
  - Unknown JS filter types become text boxes, and `SelectItem` uses `getOrNull`.
  - Migration candidates use `distinctBy`.
  - Manga global search caches only successful results.
  - A JS saved search keeps its tab (`HeaderSerializer` saves text state; anime still doesn't, because `AnimeFilterBridge` drops it).
  - `JsSourceInfo.hasLatest` decides `supportsLatest`.
  - Anime Feed and TagMix wait up to 20 s for sources (`SOURCE_WAIT_MS`).
  - `JsBrowserFetch.openTab` destroys the WebView on cancel.
  - "m3u8" anywhere in the URL counts as HLS.
  - Each Browse tab gets its own child scope, and the kept pager holds only `getRemoteManga`.
  - Swipe order matches the chips.
  - `AnimeTracking.refresh` re-reads the track before writing.
- **Manga side:**
  - Preview chapter rows listen to `statusFlow()`.
  - Merged titles open the title page instead of the preview.
  - `videoChecked` is kept across rotation.
  - Local-source titles get page grids (`localPages`, shared `archivePages`).
  - The library preview model is cleared when it leaves the screen.
  - `selectChapter` writes are guarded.
  - Remove/add from the preview uses the library's normal dialogs (`openDeleteMangaDialogFor`, `addToLibraryFromPreview`).
- **Still open:** the anime library preview probably has the same model-clearing and remove-flow issues (not in scope).

## Changes in r90 (30 October 2026)

Commit 97876610. Built after three rounds of independent testers (protocol, pop-up, non-sync, library/preview) and three compile reviews.

- **Pair sync transports** (`pairsync/PairSync.kt`, new `PairSyncWifi.kt`):
  - Connection choice (`pair_sync_transport`, default `auto`): Auto = Wi-Fi Direct, then same Wi-Fi (LAN), then Bluetooth; or a forced choice. Bluetooth RFCOMM stays the control channel (pairing, auth, negotiation); data frames move to a TCP socket with the same AES-GCM key.
  - Negotiation over Bluetooth: `sync` (with `transport`, `selection`, `have`, `p2p`, `nonce`) → answerer offers `p2p` / `lan` / `bt` / `none` → initiator joins and sends `hello` on the new socket, replies `ok`/`fail` → answerer checks the hello and sends `go` or the next offer. `wait` keep-alive frames (every 30 s) cover long export builds; `READ_TIMEOUT` 180 s on Wi-Fi sockets.
  - Wi-Fi Direct: the answerer is group owner (`createGroup` with a `WifiP2pConfig` network name/passphrase, API 29+, no accept prompt); `clearGroup` removes a stale group first (this also ends another app's Wi-Fi Direct group); the client joins with the same config and connects with retries. Everything is closed in every path (`removeGroup`, `cancelConnect`, channel close).
  - r89 peers: detected by a gzip first frame; fall back to the r89 Bluetooth flow (no APKs).
  - Each device decides what it **receives** (switches and categories); the **send** pickers (repos, installed extensions, sideloaded APKs, code sources) decide what it sends. History-only titles never become favourites or get categories. APKs go as raw frames (32 MB each, 128 MB total). Errors show under the device row (`Status.lastError`). Both pressing Sync: a nonce tie-break keeps one.
  - Pairing runs in `PairSync`'s scope (`startPair`, `pairing`, `pairError`), so rotation keeps the code and the result.
- **Chapter sync keeps chapters whose address changed** (`domain/chapter/interactor/SyncChaptersWithSource.kt`): a removed and an added chapter with the same number (rounded to 1/1000, same scanlator unless one is blank, one-to-one only) is the same chapter: the row's URL is updated in place, so its id, history, read, bookmark and last page stay, and it isn't reported as new. Writes run in one transaction under `NonCancellable`; download folders are renamed after. Cause: the Hiperdex JS source wrote `/1` where the APK had saved `/1.0` (see guide 08 › Mistakes).
- **Library preview** (`ui/library/LibraryMangaPreview.kt`, `LibraryTab.kt`, `BrowsePreviewScreenModel.kt`):
  - `LibraryOrderFreeze`: while a preview is open each category keeps its order (per category id + flags); badges update in place; released when the preview closes, sort/grouping/search change, pull to refresh, or the settings sheet opens. Range select uses the order on screen.
  - The first chapter is picked after the chapter refresh (`selectDefaultChapter`); a page-list error "Refresh the chapter list" refreshes once and retries (one `Mutex` with the refresh); a vanished selected chapter is replaced by the one with the same number.
- **Anime downloads, case twins** (`data/anime/download/AnimeDownloadManager.kt`): Android storage matches folder names ignoring case, so two titles whose names differ only by case shared one folder (both covers showed its videos; deleting one with downloads would have deleted the other's). Now a title with a case twin finds its folder by exact name, new folders get `<title> [<hash>]`, only its own episode files count, and `deleteAnime` removes only its own files. Titles without a twin are unchanged. Covers (`CoverFolders`) still match case-insensitively (open).
- **Library crash** (`NoWhenBranchMatchedException` in the pager): `LibraryDisplayMode.default` could be stored as null through class start-up order (interface default methods in Kotlin 2.4); it's now a getter, and the pagers have `else` branches.
- **Other:** HlsRelay passes non-playlist files through unchanged on a client with no call timeout and no cache (videos were cut after 2 minutes); What's new lists `rN` releases with the commit message as notes (CI `--notes-file`); anime Feed/TagMix wait once for sources, not 20 s per missing one; Browse filter copy falls back on failure, old listing scopes retire after 5 s, Filter chip fix; Device Files search/rename/listing races; reader hand-off on rotation; AppLog clear/startup.
- **Still open:** cover folders match names ignoring case; a JS anime saved search doesn't keep its tab.

## Changes in r91 (30 October 2026)

Commit 754a2d27. Owner-approved design: the mockup video made with `scratchpad/syncvideo/mock2.py` (tablet and phone side by side). Two independent testers (protocol, pop-up) and two compile reviews before building.

- **Meaning flipped: `SyncSelection` = what THIS device sends** (`pairsync/PairSyncData.kt`). Categories, repos, installed extensions, APKs and code sources are picked from this device's own lists; an empty category set is treated as "all" and the UI turns the tile off instead of saving one. The receiver no longer filters by its own tiles. Why: in r90 the receiver picked categories it didn't have (a fresh phone showed "0 of 1 categories" and got no library, only history).
- **Accept / Decline** (`PairSync.kt`): the request carries the sender's summary (`SummaryRow`s); an r91 receiver answers `asking`, shows `IncomingRequest` for 60 s, then `accepted` (with its own summary) / `declined` / `timeout`; the sender answers `start`, or `cancel` from Stop. The sender waits with `Status.waitingForAccept` (90 s cap). Both pressing at once: the yielding side gets `busy` with `tie`, and the next request from that device needs no prompt. r90 receivers: "older version", no prompt.
- **Live preview**: while the pop-up is open, `goLive` checks in with paired devices over Bluetooth (`{"t":"live"}` with the send summary, `all` when everything is on), every ~20 s with jitter, skipping devices heard from recently; `selectionChanged` pushes changes (400 ms debounce); `leaveLive` sends `leaving`; a backgrounded app answers `away`. One mutex for all outgoing RFCOMM connects; sync and pairing cancel check-ins first. Last known "what they send" is stored under `Preference.appStateKey("pair_sync_views")` (not in backups). UI: "Get:" / "Send:" lines, Live on the subtitle line, button word Sync / Send / Get (all the same action), disabled when nothing moves either way.
- **APKs only when needed**: the receiver sends its repo packages as `pkg:signature:version` lines (gzip+Base64, capped at 150 KB); the sender skips an APK when the receiver's repo has the same signer at least as new; received APKs from a matching repo become repo offers. The initiator merges received repos before building its export, using the sideloaded set taken before the merge. APK files get a new name per run; Clear keeps files saved after the running sync started.
- **Wi-Fi Direct retries once** (fresh group); failure steps are logged. **VPN**: `PairSyncWifi.vpnOn()` (TRANSPORT_VPN); in Auto with a VPN, Wi-Fi Direct is tried once, then Bluetooth; forced Wi-Fi options fail with "A VPN is on and blocks Wi-Fi sync…". The owner's VPN blocks all non-VPN traffic, so Bluetooth is the reliable path there.
- **Last sync**: Clear (`clearResult`: card, offers and waiting APK files), × on the card (`SyncResult.cardDismissed`) and on the offers box (`dismissOffers`).
- **Pairing page** back to r89 style: only KOMIKKU-tagged devices (auto-picked if one), every other device folded into "N other devices", six code boxes over one hidden `BasicTextField` (digits keyboard, Done pairs, Paste button, scrolls into view).

## Changes in r92 (30 October 2026)

Commit 10c9a288 (plus a949f519, the Sent/Got result card). Design: `scratchpad/syncvideo/mock3.py` (video "Sync-covers-downloads"). Two independent testers and two compile reviews before building.

- **Covers and downloads travel with a sync** (new `pairsync/PairSyncExtras.kt`), only over Wi-Fi Direct or Wi-Fi; over Bluetooth only the library list goes (`SyncSummary.extrasSkipped`, `IncomingRequest.extrasNeedWifi`).
  - Selection (sender's): `mangaCovers` / `animeCovers` (default on, library + history titles), `mangaDownloads` / `animeDownloads` (default off, need the library tile), `…DownloadKeys` (`"<sourceId>:<url>"`, null = all), `…DownloadMode` (`all` / `unread` / `last10` = 10 highest numbers).
  - Covers are looked up in this order: custom cover → `CoverCache` file → Coil disk cache (keys as `MangaCoverKeyer`: `"url;lastModified"`, anime `"url;0"` with id −id). Receiver: library titles → its cover folder, history-only → its Coil disk cache under the same key; a custom cover there is never replaced.
  - Downloads are written to the receiver's own paths from its own rows (`DownloadProvider.getMangaDir`, `AnimeDownloadManager.animeDirLocked`), into `_tmp` / `.cbz_tmp` / `.tmp` names renamed when complete; `DownloadCache.addChapter` / anime refresh afterwards. Already-downloaded items and sources not installed there are skipped, so a stopped sync carries on next time.
  - Protocol (both sides r92, capability `extras`): after the library payloads, manifest ⇄, needs ⇄, files ⇄ (chunks ≤ 4 MiB, first byte 0 more / 1 end / 2 failed; per-file byte count checked), then `done`. Keep-alive `wait` frames only before manifests/needs. Manifest capped at ~60 MB unpacked.
  - Free space never blocks Accept: "Up to X · Y free", amber warning if X > Y; `plan()` trims and the result says how many were skipped. Wake lock + Wi-Fi locks during the extras phase; the pop-up keeps the screen on while syncing; write watchdog closes a stalled link after `READ_TIMEOUT`.
  - Scans are cached (downloads 10 min or until anime downloads change; totals only, no file handles). `EXTRAS_CHECKING` rows are retried by the UI and re-pushed to live peers when counted.
- **UI:** Manga/Anime pages have "Also send" (Covers, Downloaded chapters/episodes with sizes and a picker page with All / Unread only / Last 10); progress shows one card per part (`Status.parts`); the result card shows "Sent:" and "Got:" lines with covers and download counts, and the size is labelled "moved" (both ways).

## Changes in r93 (30 October 2026)

Commit 3ce17608. Brief and contract: `scratchpad/r93/`. Two writers in parallel (core vs notification/pop-up), a compile review, two independent testers, a fix pass and a final compile review before building.

- **Transfer, disconnect, then apply** (new `pairsync/PairSyncStage.kt`, flag `"stage": 1` in the request and in "accepted"; r92 and older peers get the old flow). Everything is written to `filesDir/pair_sync_stage/<id>` (payload + backup, then extras as `items/n/i` with an `ok` marker per finished item), then `done` both ways, then the link closes and the apply runs offline: library merge → covers → downloads. A stage counts only once the other device's library arrived in full; if the transfer stops after that, whatever arrived complete is still applied.
  - Resume: `resumePendingApply` runs from `App.onStart` (foreground only, never `onCreate`) and after a non-staged sync. `journal.attempts` counts real failures only (Stop/cancel is undone); after 3 the stage is dropped and shown as an error, never silently.
  - Keep: received APKs referenced by a pending stage (`stagedApks()`) survive `cleanApks` / `cleanOldApks`.
- **Compare-then-replace:** each side decides its needs from its own rows: covers, .cbz and episodes by size, chapter folders by page count + total size. Equal → skipped; different → re-sent; title not here → needed whole. Replace = temp name → old renamed to `_old` → new takes the name → old deleted (leftover `_old`/`.part` cleaned first). Custom covers never replaced. A replaced library cover bumps `coverLastModified`; anime covers and cache covers clear Coil's memory cache.
- **Downloads only for titles sent to the library** (`libraryKeys()` in `outgoing` and `downloadsEstimate`). Before, unselected categories' downloads crossed the link and were dropped.
- **`historyCovers`** (History page, "Covers of history titles"); Manga/Anime "Covers" = library titles only. Saved selections without the field default to `mangaCovers && animeCovers`.
- **`PairSyncService`** (foreground service, type dataSync, plus `PairSyncNotifier` polling status every 500 ms): progress with Stop (also while Applying), Accept/Decline as a notification when the pop-up isn't visible, a result notification, tapping opens the pop-up (`PairSyncPopup.openRequest`, `MainActivity` `ACTION_OPEN`). `foregroundTried` is set only when the start succeeded (background starts are refused on Android 12+, a notification tap allows a retry). The notifier never stops the service before `startForeground` ran (`running` flag); `onTimeout` handles the Android 15 6-hour dataSync limit. `Status.phase` = Idle / Asking (I pressed Sync) / Waiting (I was asked) / Transferring / Applying.

### Mistakes to avoid (learned in r92 → r93)

- **Slow merge (7 min for 4,097 titles):** pair sync never set `LibraryRefresh.restoring` the way a backup restore does, so every merged title made the open library reload all titles merged so far, which got slower with every title. r90 looked fast only because the fresh phone got history-only titles, which the library query doesn't count. Any bulk insert into the library must set `restoring` (in a `finally`) and request one refresh at the end.
- **"Broken pipe" / "other device stopped":** the tablet showed "Synced" before covers and downloads, the user left the app, and Android 15/16 cut the network for a background app without a foreground service. Long transfers need a foreground service from the moment the sync starts, and "done" may only be shown after the apply. The low-latency Wi-Fi lock was removed too (it was taken about 2 s before the drop).

## How to use this guide when adding a feature

1. Find the section for the area you're changing, and read its feature entry, invariants and impact map.
2. Search this folder for the class or preference key you'll touch (`grep -rn "HlsVariants" docs/fork-guide`). Every section that mentions it is a place that can break.
3. Plan the change, list what it could break, and tell the owner before building.
4. Add an AppLog line that proves the new path works (section 1, "Logging conventions"), so one test run tells you whether it worked.
5. Run section 1's "Before you push" checklist.
6. After the release, update the relevant section: what changed, why, and which release.
