## Library, downloads, backup/restore, tracking, pair sync, logs, database

Paths are relative to `app/src/main/java/eu/kanade/` unless they start with `data/src`, `domain/src` or `core/`.
"Fork" means commits by Claude / Dominican4Life / OmfgItsJesus (from f87f01a5, 2026-10-07). Verified against HEAD a693dc09 (r84).

---

### Database schema: what the fork added (read this before touching any `.sq` file)

**How it works.** SQLDelight builds the schema for a fresh install from the `.sq` files in `data/src/main/sqldelight/tachiyomi/data/`. An upgrade runs the `.sqm` files in `data/src/main/sqldelight/tachiyomi/migrations/` from the installed version up. The DB version is the highest migration number + 1 (now 58). `data/build.gradle.kts` does not turn on `verifyMigrations`, so nothing checks at build time that the `.sq` files and the `.sqm` files agree.

The fork only **added** tables and columns. It changed no upstream manga table (no fork commit touches `mangas.sq`, `chapters.sq`, `history.sq` and so on). Upstream's last migration is 46.

| Migration | Commit / release | What it does |
|---|---|---|
| 47 | 39f455aa (OmfgItsJesus) | `anime_extension_store` (Aniyomi repos; same columns as `extension_store`) |
| 48 | e284e45c, anime stage 2 | `animes`, `episodes` (+ indexes `kmk_animes_favorite_index`, `kmk_animes_url_index`, `kmk_episodes_anime_id_index`) |
| 49 | 23ba5816, player | `custom_buttons` (AniZen player buttons) + seeded row `_id=1` "+85 s" |
| 50 | d8f5a7e9, anime stage 4 | `anime_history(episode_id UNIQUE, last_seen)` |
| 51 | ff5b7e58 | `anime_merged(merge_id, anime_id, position)` |
| 52 | 02da1f2a | `anime_categories`, `animes_categories` |
| 53 | f79865f1 (2026-10-19) | `anime_sync` (anime tracking, `UNIQUE(anime_id, sync_id) ON CONFLICT REPLACE`) |
| 54 | b9103f1f, r53 | `anime_custom_info` (Edit info / custom cover) — uses `CREATE TABLE IF NOT EXISTS` |
| 55 | ab039f1b, r54 | `ALTER anime_history ADD time_watch INTEGER NOT NULL DEFAULT 0` |
| 56 | 9f786264, r55 | `ALTER animes ADD parent_id, season_flags, season_number (REAL, -1), season_source_order` + `kmk_animes_parent_id_index` |
| 57 | 8c36b0c7, r57 | `DELETE FROM anime_sync WHERE sync_id = 102` (Jellyfin tracker removed) |

Query-only `.sq` files the fork added: `anime_cleanup.sq` ("Clear Anime database" in Settings › Advanced, which keeps seasons and merge members of library anime) and `custom_buttons.sq`. `episodes.sq` was edited 6 times, but only its queries changed; the table still matches migration 48.

**Invariants.**
- Each new column needs **both** a change to the `CREATE TABLE` in the `.sq` file **and** a new `NN.sqm` with the next number (58). A fresh install reads only the `.sq` file. An upgrade reads only the `.sqm` files. If they disagree, either upgraded users crash on queries that use the column ("no such column"), or a fresh install fails on a duplicate column.
- Never edit or renumber a migration that has shipped (47–57). Devices that already ran it won't run it again.
- Table and index names on the anime side start with `kmk_` or `anime_`. Keep them apart from the manga tables: backups, Clear database and the library queries all assume the two never share a table.

**Failure mode the owner can't see today.** The owner uninstalls before every release because the signing key changes each build, so their device always gets a fresh `.sq` schema and **no migration has ever run on it**. A missing or wrong `.sqm` would not show up until installs over the old app start working. The transcript's r85 plan says r85 fixes signing and r86+ install over the old app. From then on a bad migration bricks the upgrade: the app crashes on open, and the only way out is to uninstall, which loses data that is not in the backup. Check every new `.sqm` by hand against the `.sq` diff.

---

### Backup creation

**Where.** `tachiyomi/data/backup/create/BackupCreator.kt`, `BackupOptions.kt`, `BackupCreateJob.kt`, `creators/*`. Fork model files: `models/Backup.kt` (proto fields 700–710), `models/BackupAnime.kt`, `models/BackupJsSource.kt`. Anime parts: `data/backup/AnimeBackupHelper.kt`. Category picking: `data/backup/CategoryChoice.kt`.

**Proto fields** (`Backup`): 1 manga, 2 categories, 101 sources, 104 app prefs, 105 source prefs, 106 extension repos, 600 saved searches (SY), 610 feeds (KMK upstream). Fork-added: **700 `backupAnime`**, **701 `backupAnimeExtensionStores`**, **702 `backupAnimeCategories`** (name + order), **710 `backupJsSources`** (info JSON, code, settings). Never reuse or renumber these. An older or newer app reads unknown numbers as empty. Field 501 (Anikku anime) and field 3 (old Aniyomi anime) belong to other apps' formats. `AnikkuBackupConverter` reads them, so never put fork data at 3 or 501.

**Flow** (`BackupCreator.backup`):
1. Manga = favorites + read-but-not-in-library manga (only with the "non-library" option, `readEntries`) + merged-manga children. This is upstream.
2. With a category choice (`categoryKeys`, keys `manga:<name>` / `anime:<name>`, empty name = no category): library manga outside the chosen categories are dropped. Non-library manga always stay.
3. Anime (`AnimeBackupHelper.createAnime()`): favorites **plus the seasons of favorite anime, favorite or not**, each once. Each anime carries: episodes (seen, bookmark, fillermark, last/total seconds), history (`lastSeen` only), category names, tracks (`anime_sync`), notes and excluded scanlators (read from app prefs `anime_notes_<id>` / `anime_excluded_scanlators_<id>`), Edit info (`anime_custom_info`, minus `file:` cover URLs), and its season parent as **source + url** (ids differ between devices).
4. JS sources (`JsSourceManager.backupSources()`): each saved Code editor source's info JSON, code and every key of its `source_<id>` SharedPreferences.
5. Written gzip+protobuf, then checked with `BackupFileValidator`. Auto backups keep 4 files (`MAX_AUTO_BACKUPS`).

**History.** r72 (70071322) read the whole manga backup in one transaction. r73 (ea9e889c) put that back to per-call reads and kept only the "one chapter lookup per title for history" speed-up (`MangaBackupCreator`: chapter urls by id, read once per title). History entries whose chapter is gone are now skipped rather than throwing.

**Invariants.**
- `BackupOptions.asBooleanArray()` / `fromBooleanArray()` are stored in WorkManager input and in preferences by **position**. Add new options only at the end, and read them with `getOrElse(i) { default }`, as `jsSources = array.getOrElse(12) { true }` does. Inserting in the middle shifts every later choice.
- The Chapters / Tracking / History / Custom info switches apply to **manga only**. Anime always go out with episodes, history, tracks and Edit info whenever "Library entries" is on. Anime categories go out when Library entries **or** Categories is on.

### What a backup contains and what it doesn't (owner reinstalls every release)

**In the backup** (with the default options):
- Manga library, chapters (read/bookmark/progress), manga categories (and order), manga tracking, manga history with read duration, custom manga info, merged manga, flat metadata, excluded scanlators; read non-library manga only if "non-library" is ticked.
- Anime library + seasons of library anime, episodes and progress, anime watch history (last seen time), anime categories with order, anime tracking, notes, hidden scanlators, Edit info (not a local cover file), season links.
- App settings: the whole default SharedPreferences, minus `__APP_STATE_` keys, and minus `__PRIVATE_` keys unless "Private settings" is ticked. This covers **every fork setting stored through `PreferenceStore`**: player, anime library sheet, history/updates display, Pause between titles, Save covers in folders, pair sync peers (`pair_sync_peers`, **including the shared secret**), the anime download queue (`anime_download_queue`) and all per-anime keys.
- Source settings: only `sourceManager.getAll()` that are `ConfigurableSource`, i.e. manga extensions and **enabled** JS manga sources (their `source_<id>` file).
- Manga extension repos (106) and anime extension repos (701).
- Saved searches and feeds. Anime saved searches use the same `saved_search` table, so they come along too.
- JS sources: code, details (name, id, on/off, `updatedAt`, …) and settings.

**Not in the backup** (lost or reset after uninstall + restore):
- **Anime extension (Aniyomi) source settings.** `AnimeSourceManager` sources are not in `PreferenceBackupCreator.createSource`.
- **Merged anime** (`anime_merged`): the members come back as separate anime.
- **Player custom buttons** (`custom_buttons` table): only the seeded "+85 s" comes back, from migration 49 / `.sq`.
- **Anime watch time** (`anime_history.time_watch`): Stats › Anime watch time starts again at 0.
- JS source runtime data: `files/js_storage/<key>`, the last tab per source (`js_tabs` prefs), web URLs (`js_weburls_<id>` prefs). JS icons are not in the backup either, but they are kept in `downloads/Icon` and found again from there.
- Tracker logins and `anime_track_pending`: these are `__PRIVATE_` keys, so you must log in to trackers again unless "Private settings" was ticked.
- Other SharedPreferences files: manga download queue (`active_downloads`), `chapters_to_delete`, `tracking_queue`, `browse_preview_fetched`.
- The in-app log (`files/app_log/log.txt`). **Share the Logs before you uninstall** if they matter.
- Cover cache and custom manga cover files (upstream behaviour). Covers can come back from `cover.jpg` in download folders (see below).
- Downloads, `mpv-config/` and Sideloaded APKs are files in the storage folder, not in the backup. They survive an uninstall when the storage folder is outside the app.
- Installed extension APKs. Repos come back, and sideloaded APKs come back from the `Sideloaded/` folder (`SideloadFolder.afterRestore`).

**Id-keyed settings: suspected wrong-title risk (not seen on device).** Anime get **new row ids** on restore. The app-settings restore brings back keys that hold *old* anime, episode or category ids, unchanged:
- `anime_notes_<id>`, `anime_excluded_scanlators_<id>` (the anime restore also writes the right values under the new id, but only when the backup value is non-blank, so an old key can land on a different anime);
- `anime_skip_intro_length_<id>`, `anime_last_selected_season_<id>`, `pref_last_aspect_ratio_anime_id`;
- anime category id sets: `anime_update_categories(_exclude)`, `anime_download_new_categories(_exclude)`, `anime_library_hidden_categories`, `anime_library_filter_categories_include/_exclude`;
- `anime_download_queue` (`"<animeId>:<episodeId>"`). On the next start `AnimeDownloadManager.init` queues whatever episode now has that id.

The manga side doesn't have this problem for categories: upstream `PreferenceRestorer.restoreCategoriesPreference` remaps the ids, but only for `LibraryPreferences.categoryPreferenceKeys + DownloadPreferences.categoryPreferenceKeys`. The anime keys are not in that list. Also, the app-prefs restore and the anime restore run **at the same time** (see restore order), so which value wins for `anime_notes_*` is a race. To fix this, map the anime keys by name in `PreferenceRestorer`, or leave them out of backups.

---

### Restore

**Where.** `data/backup/restore/BackupRestorer.kt`, `BackupRestoreJob.kt`, `RestoreOptions.kt`, `restorers/MangaRestorer.kt`, `data/backup/BackupDecoder.kt`, `AnikkuBackupConverter.kt`, `models/AnikkuBackup.kt`, `BackupNotifier.kt`, `ui/library/LibraryRefresh.kt`.

**Decoding** (`BackupDecoder.decode`). The file is gunzipped if needed and decoded as our `Backup`. If that has no anime, the bytes are also tried as an Anikku backup (anime at 501) or an old Aniyomi backup (anime at 3). Anikku anime and categories are merged in, and when the file isn't one of ours, Anikku source preferences too. A JSON file is rejected.

**Order, verified in `restoreFromFile`:**
1. Decode. With a category choice, filter manga (non-library manga count as "no category" since r71/r72; before that they all came back, which looked like a full restore), anime and anime categories.
2. **Manga categories**, awaited before anything else. Only the chosen ones are created.
3. Then **in parallel** (`launch` inside one `coroutineScope`):
   - saved searches + feeds;
   - app preferences (the default-category and category-set keys are remapped by name), then `LibraryUpdateJob/BackupCreateJob/AppUpdateJob.setupTask`;
   - source preferences;
   - manga, one title per transaction, sorted with merged-source titles last and new titles before existing ones;
   - manga repos;
   - anime: `restoreCategories` first (missing ones added in backup order after the existing ones), then each anime (`AnimeRepository.restoreAnime` + `restoreEpisodes` + extras), then **season links in a second pass** (`restoreSeasonLink`, which finds the parent by source+url);
   - anime repos;
   - JS sources.
4. After that: `CoverFolders.fillMissingAfterRestore()` (only if "Auto save covers" is on; it **starts** a background import and doesn't wait), `DownloadCache.invalidateCache()` (Reindex downloads), `LibraryRefresh.restoring = false` in `finally`, `LibraryRefresh.request()` (badges worked out again), `SideloadFolder.afterRestore` (when app settings or repos were restored), error log, done notification.

**Merge rules (restoring on top of existing data).**
- Manga: upstream rules. One fork change (r48, 38f24ac1, `keepLibraryTitle`): if the title existed here only from browsing and the backup has it in the library, the backup's `ogTitle` wins, because download folders are named after it ("Byoru · 272" problem).
- Anime (`restoreAnime`): an existing anime keeps its own details (title, description… are **not** updated). Favorite = backup OR here. Episode flags and season flags come from the backup. Categories are linked by name, case-insensitive, and created if missing. Episodes: seen, bookmark and filler are OR-ed, last/total seconds take the max, history is upserted. Tracks: `lastEpisodeSeen` takes the max, and Jellyfin (102) or other unknown trackers are skipped.
- JS sources: `JsSourceManager.restoreSource` → `importSource` **returns early when this device already has that key with `updatedAt >= backup's`**. In that case **the backup's settings are not written either** (the settings callback is never called). On a fresh install nothing exists, so everything is restored. Enabled JS *manga* sources also get their settings through the generic Source settings restore (same `source_<id>` file), so for them the early return doesn't lose settings. JS *anime* sources and disabled JS sources do lose them. An r84 device review checked that a deleted JS source comes back with identical code, and that a changed setting on unchanged code comes back through Source settings. `importSource` also logs `I js Got <name> from a paired device` for **backup** restores, which is misleading. Known review finding, planned for r85: the source `key` from a backup or paired device goes into `File(root, key)` unchecked.

**History of the restore (each of these was a real failure).**
- r29 (ba9254b9): the restore checked every source first and held blocked sources until WebView passed (Waiting notification + Skip). r33/r34 removed the waiting notification and then all holds ("no more holds"). Don't bring back network checks inside the restore.
- r32 (2901ef9e, 9dbef2ba): **the restore crashed and stopped** because the parallel parts built the shared progress notification at the same moment. Fix: `BackupNotifier` changes the builder under a lock, and a notification error never stops a restore. The first r32 attempt failed to build because of duplicated functions. Any new parallel restore part must go through `notifier.showRestoreProgress(...)`.
- r33 (9532b75f): the restore bar at the top was removed because it redrew the app for every title. Progress is throttled to every 300 ms (`PROGRESS_EVERY_MS`, `progressDue()`).
- r72 (70071322): titles were restored 50 per transaction (`restoreGroup`, failing groups redone one by one). **r73 (ea9e889c): the restore got stuck**, so this was reverted to one title per transaction. The real cost turned out to be the libraries re-reading the whole library after every write. Fix: `LibraryRefresh.restoring` + `pausedWhileRestoring(flow)` in `LibraryScreenModel` (manga) and `presentation/anime/AnimeLibraryContent.kt` (favorites, stats, category links). **Don't bring back multi-title transactions.**
- r72 kept: device categories read once per restore (`dbCategoriesCache`, reset in `sortByNew`), and history restored with one chapter + history lookup per title.
- r69 (09926c1b): anime restore "flashed through Default" because categories were linked in a separate step. They are now linked in the same transaction that sets favorite.
- r47 (0ae92725): Reindex downloads after a restore, and folder scans skipped on an empty library. r70: badges are read again after a restore and after downloads are scanned.

**Invariants.**
- `MangaRestorer.dbCategoriesCache` assumes no category is created while manga are restoring. Categories must stay restored (step 2) **before** the parallel step. Pair sync also creates categories before it calls `restore`.
- `restoreSeasonLink` must run after **all** anime are in.
- `LibraryRefresh.restoring` must be reset in `finally`. Otherwise the libraries stay frozen until the app restarts.
- `restoreProgress` / `restoreAmount` are plain ints changed from parallel coroutines. A miscount only affects the progress bar.

**How to check (Logs, tag `Restore`).** Every step logs `I Restore <step>: N.N s`, for example `Reading the backup file: 0.8 s`, `Manga (1234): 95.2 s`, `Anime (56): 4.1 s`, `Covers from download folders: 0.0 s`, `Sideloaded extensions: 1.3 s`, `Whole restore: 110.4 s`. If `Manga (N)` is much longer than a few hundred ms per title, something is re-reading the DB per title (a library flow not wrapped in `pausedWhileRestoring`). If `Whole restore` is missing, the restore died; check for `E` lines just before. Per-title errors go to `komikku_restore_error.txt` (the done notification links it), not to Logs. Lines like `[anime]`, `[anime season]`, `Anime categories:` and `JS source:` show which part failed. The cover import logs `I cover Covers imported from download folders: X of Y missing (Z had no folder)`.

---

### Covers from download folders (restore-related part)

`data/cache/CoverFolders.kt`, `data/library/RefreshMissingCovers.kt`, `data/anime/AnimeRefreshMissingCovers.kt`.

- Setting **Auto save covers for manga and anime** (`save_covers_in_folders`, default **false**, Settings › Downloads, added r33). When on, each title's cover is kept as `cover.jpg` in its download folder (turning it on saves all existing ones), and the cover is removed when the title leaves the library. **Import covers from download folders** (r38/r39) copies them back once, with "Imported X of Y covers".
- After a restore, the import runs only if this setting is on, and it reads the **restored** value, since app prefs finish before the post-step. If you restore without App settings on a fresh install, the setting is still false, so nothing is imported (use the Import button).
- Library menu **Refresh missing covers** (61478cd7, 2026-10-12): for the tab being shown, it first loads the cover again, then reads details from the source. It honours "Pause between titles". Notification id −104 (anime −105).
- Logs tag `cover`: `No download folder for Manga/<src>/<title>`, `Cover folder found as …`, `No cover.jpg in …`, `Couldn't read …`.

---

### Manga downloads

**Where.** `tachiyomi/data/download/Downloader.kt`, `DownloadManager.kt`, `DownloadProvider.kt`, `DownloadCache.kt`, `model/Download.kt`, fork files `SourceFolders.kt`, `MangaIndexFile.kt`, `jssource/ChapterVideoDownloads.kt`; `domain/src/main/java/tachiyomi/domain/storage/service/StorageManager.kt`.

**Folders** (r37, c1d306a5): manga downloads go to `<storage>/downloads/Manga/<Source (LANG)>/<title>/`, anime to `downloads/Anime/…`, JS icons to `downloads/Icon`. `StorageManager.childDirectory` finds `Manga`/`Anime` in any letter case (SD cards). The download index cache file was bumped to `dl_index_cache_v4`. **No code moves pre-r37 folders from `downloads/<source>` into `downloads/Manga/`.** Old downloads left there are invisible unless moved by hand.

**Source folder matching** (r63, 355d9ef8, `SourceFolders`): a folder belongs to a source when `rank()` is 0 (exact `Name (EN)`), 1 (same name, another tag that fits the source: its language, its base language, `ALL` for all/multi, `JS`/`JSON` for JS sources) or 2 (Android's numbered copy `" (1)"`). `findOrCreate` is `synchronized`, so two downloads can't both create a folder (this used to make "Hiperdex (EN) (1)"). `DownloadCache` merges titles from every matching folder of a source, the best folder winning. `DownloadManager.renameSource` leaves a folder alone if it already matches the new source.

**Download of one chapter** (`Downloader.downloadChapter`):
1. Page list (re-indexed), then `download.hasVideos = any page is ReaderVideos.isVideo(url)`, `videoProgressFlow = 0`.
2. Pictures are downloaded, `parallelPageLimit` at a time, into `<chapter>_tmp`. `notifier.onProgressChange` runs per page.
3. **If it has videos**: `ChapterVideoDownloads.saveAll(pages, tmpDir, "%0Nd" names, …, onPercent)` saves each video page next to its picture as `NNN.video.*`. HTTP files are streamed with percent progress, and HLS/DASH go through ffmpeg. A failed video is logged (`Couldn't save the video of page N in <title · chapter>; it stays a picture`) and **the chapter still counts as downloaded**. This is a known review finding: a chapter can look complete while a video is missing.
4. `isDownloadSuccessful` ignores `*.video.*` files when counting pages. ComicInfo, CBZ or rename, `cache.addChapter`. Then **the title's first download writes `index.json`** (r47, `MangaIndexFile`: details + full chapter list).
5. `MangaIndexFile.read` is used by `mihon/domain/source/interactor/UpdateMangaFromRemote.kt`: a title that isn't initialized or has no chapters (after a reinstall or restore), on a non-manual fetch, fills in from `index.json` instead of asking the source.

**Progress, with the r84 video half** (a693dc09, `Download.kt`): with `hasVideos`, `progress = pages/2 + video/2`, so pictures fill 0–50 % and the videos 50–100 %. Without videos, the progress is the plain page average. `progressFlow` combines the page flows with `videoProgressFlow`. Before r84 a chapter showed 100 % while its video was still downloading. **The notification still shows `downloadedImages / pages`** (`DownloadNotifier`), not the video half. That is expected, not a bug in the change.

**DownloadCache fork changes.** Empty library (fresh install): the folder scan is skipped and retried in 1 minute (`libraryWasEmpty`). A restore forces the reindex. Folders are scanned **3 sources at a time** (`Semaphore(3)`, r74). An `OutOfMemoryError` while listing keeps the old listing and logs `E DownloadCache Ran out of memory listing the download folders; trying again later`, instead of crashing. The listing finishing calls `LibraryRefresh.request()`, so the download badges update.

**Check downloads** (`ui/library/checkdownloads/CheckDownloadsScreen.kt`, 9b2cde16, 2026-10-17): library selection menu. It compares each title's folder with its chapters, lists extras (renamed or removed posts, unfinished `_tmp`), and lets you open them (pictures, plus videos in a player) or delete them. Local and merged entries are skipped.

**Invariants.**
- Never name a picture file `NNN.video.*`. `isVideoFile` filters these out of page counts and readers.
- Folders must only be made through `SourceFolders.findOrCreate` / `DownloadProvider.getMangaDir`. A direct `createDirectory` brings the numbered copies back.
- `index.json` and `cover.jpg` live in the title folder. Anything that lists chapter folders must skip files.

**Anime downloads** are a separate system (`data/anime/download/AnimeDownloadManager.kt`, `AnimeDownloadJob.kt`). They share the queue screen (All | Manga | Anime tabs, 5e5cf6a1). Their queue is in app prefs (`anime_download_queue`), so it is included in backups (see the id risk above). Settings: `anime_download_concurrent` (1), `anime_download_only_wifi` (true), `anime_download_ahead` (0), `anime_download_new` (false), `anime_delete_after_seen` (false).

---

### Library (manga tab) and shared library plumbing

`tachiyomi/ui/library/LibraryScreenModel.kt`, `LibraryTab.kt`, `LibraryRefresh.kt`, `presentation/library/components/*`. The anime library is `tachiyomi/ui/library/AnimeLibraryTab.kt` + `presentation/anime/AnimeLibraryContent.kt` (covered in the anime section).

- `LibraryRefresh.tick` makes both libraries work out badges again and re-read the badge prefs (`downloadBadge`, `unreadBadge`, `localBadge`, `languageBadge`, `sourceBadge`, `useLangIcon`). Before r67/r70, badges stayed wrong after a restore until a badge setting was switched off and on. The library also refreshes when the number of sources changes, so language badges appear once extensions load.
- r38 (befea648) bulk remove: the titles leave the library in one `updateManga.awaitAll` first, then covers are cleaned up and `CoverFolders.onRemovedFromLibrary` runs. Removals of 50 or more show "Removing…/Removed" toasts (`BULK_REMOVE_NOTICE`).
- dd72a003 (2026-10-12): covers keyed by title, badges worked out once. r27 changed this again: c677e717 was reverted (c215e465), then 621fefd5 made cover colour fill quicker. Migrate moved to the Library menu (r27).
- `DownloadAction.ALL_CHAPTERS` in the library means "every unread chapter" (`downloadNextChapters(null)`). Its `when` must stay exhaustive: 87b66486 was a build fix for exactly this.
- The manga library menu's update runs manga only (`LibraryUpdateJob` input `with_anime`, default true). Scheduled manga updates take the anime update along via `AnimeLibraryUpdateJob.startWithManga`, unless anime has its own schedule.
- **Pause between titles** (r28): `pref_library_update_pause_enabled` (false) + `pref_library_update_pause` ("1-3" seconds). This is a random delay between titles **of the same source** during library updates and Refresh missing covers (`util/PauseBetweenTitles`). Migration has its own pair (`pref_migration_delay_enabled` true, `pref_migration_delay` "1-3").

### Categories

`tachiyomi/ui/category/CategoryScreen.kt` (tabs **Manga | Anime | Sources**, swipeable, `initialTab`), `ui/category/anime/AnimeCategoryScreenModel.kt`, `ui/category/sources/*`. Anime categories live in `anime_categories` and are linked by `animes_categories`. They are matched **by name, case-insensitive** in restores and sync, and by **id** in anime prefs (the restore risk above). Hidden anime categories: `anime_library_hidden_categories` (ids) + `anime_library_show_hidden_categories`.

### History, Updates, Stats

- History (`ui/history/HistoryTab.kt`, `HistoryScreenModel.kt`, `presentation/history/HistoryScreen.kt`, `components/HistoryKototoro.kt`): Kototoro style (6265220d, 2026-10-09), with Anime | Manga tabs (`pref_history_media_tab`, `MediaTab.fromPref`) and the library's display modes (`pref_history_display_mode`, default List; `pref_history_columns`, 0 = auto). The **Device files** button shows only items opened from Device files, and those are left out of the manga and anime history (b640770e). On open, HistoryTab **deletes** the old filter prefs (`filterUnfinishedManga/Chapter`, `filterNonLibraryManga`), because the filters left the toolbar and must not stay on unseen.
- Updates (`ui/updates/UpdatesTab.kt`, `UpdatesScreenModel.kt`, `AnimeUpdatesActions.kt`, `presentation/updates/UpdatesTimeline.kt`): `pref_updates_display_mode` (List), `pref_updates_columns` (0). Anime selection has existed since r54. The old keys `pref_updates_timeline_view` / `pref_updates_show_carousel` / `pref_history_grid_mode` / `pref_history_show_summary` were removed in ecc71cb2. Don't reuse those names.
- Stats (`ui/stats/StatsScreen.kt`, `AnimeStats.kt`): Anime | Manga tabs, **opening on Anime** (`rememberSaveable`, not saved). The anime numbers include watch time (`anime_history.time_watch`, summed by `getTotalTimeWatch`, written by `data/anime/AnimeWatchTime.kt`). **The downloaded-episodes count lists every library anime's folder each time Stats opens**, which is slow on a big library or SAF storage.

---

### Tracking (manga trackers used for anime)

`tachiyomi/data/track/TrackerManager.kt`, `data/track/simkl/*` (fork-added), `tachiyomi/data/anime/track/AnimeTracking.kt`, `AnimeTrackServices.kt`, `AnimeDelayedTrackingJob.kt`.

- Anime tracking **uses the manga trackers' logins** (Settings › Tracking). Services, in Aniyomi's order: MyAnimeList, AniList, Kitsu, Shikimori, Simkl, Bangumi. `Anilist.animeClient` is the signed-in client. Simkl (id **101**) is anime-only: it sits in `TrackerManager.animeOnlyTrackers`, deliberately **not** in `trackers`, so manga sheets, filters, stats and backups never list it. Jellyfin (102) was removed in r57, and migration 57 deletes its rows.
- Data: `anime_sync`, one row per (anime, tracker), replaced on conflict. `AnimeTrackRepository`.
- Flow: `AnimeTracking.onEpisodeSeen(animeId, n)` runs when the player marks an episode seen. It reports once per (anime, n) (`lastReported`). It is skipped if "Update progress after reading" (`autoUpdateTrack`) is off or the source is in incognito (`AnimeIncognito`). Progress only moves forward, status becomes Watching, or Completed at the last episode, and start/finish dates are set. A failed send is saved here and marked pending (`__PRIVATE_anime_track_pending`, keys `"animeId:trackerId"`), then retried by `AnimeDelayedTrackingJob` (unique work "AnimeDelayedTracking", network required, exponential 5 min, up to 3 retries) and before every `refresh`. `refresh` pulls each tracker's entry, except where a local change is pending; local wins.
- Restore: track progress takes the max, and unknown trackers are skipped. Pending keys are private and hold old anime ids, so they are not in normal backups.
- Logs: logcat lines with tag = service class, e.g. `W AnilistAnime Anime tracking: AniList update failed`, `Anime tracking: refresh …`. Build pitfall 320144f9: inside these service files `logcat { }` needs a receiver (`service.logcat(...)`) or an explicit tag. A bare `logcat(priority, e) { }` in a non-class scope doesn't compile.

---

### Pair sync (Bluetooth)

`tachiyomi/pairsync/PairSync.kt`, `PairSyncData.kt`, `tachiyomi/ui/pairsync/PairSyncScreen.kt` (opened from a button in `ui/home/HomeScreen.kt`). Added a4a9e23c (2026-10-09). Permissions: `BLUETOOTH_CONNECT/SCAN/ADVERTISE`.

**Handshake.**
- Transport: insecure RFCOMM, service UUID `6f1c2a7e-4b8d-4e5a-9c3f-2d7b8e1a5c90`, name "Komikku sync". **The waiting device must have its Sync page open**: `startListening` runs in a `DisposableEffect`, and `stopListening` runs on dispose.
- Frames: `int length` + AES-GCM (12-byte IV + ciphertext + 16-byte tag). The length must be in `13..256 MiB` (`MAX_FRAME = 256 * 1024 * 1024`), otherwise "Bad data from the other device". Buffers are 64 KiB.
- Pairing: one side shows a 6-digit code (`SecureRandom`, 100000–999999). The other side sends `{"t":"pair","name":…}` encrypted with PBKDF2-HMAC-SHA256(code, salt `"komikku-pair-sync"`, 60 000 rounds, 256-bit). The listener answers `{"t":"paired","name","secret"}` with a random 32-byte secret. Both save `Peer(address, name, secret, lastSync)` in `pair_sync_peers` (JSON).
- Sync: the initiator first builds its own export (`PairSyncData.export`), connects, and sends `{"t":"sync","name","selection"}` with the peer secret. The listener tries **every saved peer key** on the first frame (its own address first, so a changed MAC still works), then the showing code. It sends its export, receives the initiator's, imports, and sends `{"t":"done"}`. The initiator imports after sending. There are **no socket timeouts**: a side that disappears leaves the other blocked until Bluetooth drops the link.

**Payload** (`SyncPayload`, JSON, gzipped): `sources` = Code editor sources (info + code, **no settings**), plus `backup` = Base64 of a gzipped `Backup` holding the chosen manga (library by category name, `""` = Default, and/or history-only titles, whose favorite is turned off and categories cleared) and anime. Device files and ReaderVideos entries are never sent. The whole payload sits in memory several times over (JSON + Base64 + gzip), so a huge library approaches the 256 MiB frame limit and needs a lot of RAM.

**Import** uses the restore code: `JsSourceManager.importSource` (same early return: same/newer copy wins), `CategoriesRestorer`, `MangaRestorer(isSync=false).restore` per title, `AnimeBackupHelper.restoreAnime`. Not done in sync: anime category order (`restoreCategories`, so categories are created as anime arrive), **season links** (`restoreSeasonLink` isn't called, so synced seasons arrive unlinked), and JS source settings. Selection is saved in `pair_sync_selection`.

**Logs (tag `sync`)** mirror the screen's status lines: `Pairing with X…`, `Paired with X`, `A device tried the wrong code`, `A device that isn't paired tried to connect`, `Getting ready to sync …`, `Got 1.2 MB from X, sending …`, `Code editor sources: N new or updated of M`, `Merging N manga…`, `Synced with X: 2 sources, 10 manga, 3 anime[, K couldn't be added]`, `Sync stopped: …` / `Couldn't reach X. It needs Bluetooth on and its Sync page open`.

---

### In-app Logs (AppLog)

`tachiyomi/util/system/AppLog.kt`, screen `tachiyomi/ui/logs/LogsScreen.kt` (bug button on the navigation rail; since r86 also More › Logs, for phones). Added 4735057d (2026-10-09); Copy fix 18009078 (2026-10-11).

- **How lines get in:** `AppLog.Logger` (installed as the logcat logger, priority ≥ DEBUG; tag = the logcat tag, normally the calling class's simple name), `AppLog.XLogPrinter` (XLog `xLogD/E…`), the crash handler, and direct `AppLog.add(level, tag, msg)`.
- **Dedupe:** logcat lines also go through XLog. The logger stores the message in a `ThreadLocal` (`lastFromLogcat`), and the XLog printer drops the identical message on the same thread. Logging the same text from a different thread is not deduped.
- **Storage:** an in-memory deque of the last `MAX_LINES = 4000` lines, plus `files/app_log/log.txt` (tab-separated `time level tag message`, with `\n` escaped). Lines are appended by a background writer, except `E` lines, which are written at once so a crash is kept. The file is trimmed to 4000 lines only at app start (`init`), so it grows during one run. Earlier runs show negative times (`-12.345s`). The file is in app storage, so **an uninstall deletes it**.
- **Screen:** All / Info (I, W, E) / Problems (W, E) filters, search, selectable text (limited; Copy and Share take everything). Copy keeps the newest lines under about 1 MB, because a larger clipboard copy crashed the app (18009078), and suggests Share for the rest.
- **Direct tags you'll see from this area:** `Restore`, `sync`, `DownloadCache`, `js` (`Got X from a paired device`, also on backup restore), `cover` (logcat with explicit tag). Downloader and video-save lines come through logcat with the class tag (`Downloader`, `ChapterVideoDownloads`: `Chapter video saved: <title · chapter>, page N`).

---

### Notifications in this area (fork ids)

`data/notification/Notifications.kt`: `ID_NEW_EPISODES −1801`, `ID_ANIME_LIBRARY_PROGRESS −1802`, `ID_ANIME_DOWNLOAD_PROGRESS −1803`, `ID_ANIME_DOWNLOAD_ERROR −1804`, `ID_MIGRATION_PROGRESS −1806`, `CHANNEL_BOT_CHECK` + `ID_BOT_CHECK_SUMMARY −1807` / `WAITING −1808`, `ID_RESTORE_COVERS −1809`, `ID_MIGRATION_DONE −1810`, `ID_SAVE_COVERS −1811`, anime migration −1812/−1813, torrent server channel `ID_TORRENT_SERVER −801`, `LocalHttpServerService −802`. Private ids: `RefreshMissingCovers −104`, `AnimeRefreshMissingCovers −105`. Upstream uses −101..−103 and −198/−199. Pick new ids outside all of these.

---

### Settings in this area

| Setting (as shown) | Preference key | Default | What it changes | Fork-added? |
|---|---|---|---|---|
| Backup / restore option "JS sources" | in the options boolean array (index 12 backup, 6 restore) | on | Code editor sources in the backup/restore | yes |
| Backup / restore category choice | WorkManager input `categories` | all | only titles in the chosen manga/anime categories | yes |
| Auto save covers for manga and anime (Downloads) | `save_covers_in_folders` | false | keeps `cover.jpg` in download folders; needed for the cover import after a restore | yes |
| Import covers from download folders (Downloads) | (action) | – | copies saved covers into the app | yes |
| Pause between titles (Library) | `pref_library_update_pause_enabled` / `pref_library_update_pause` | false / "1-3" | random seconds between same-source titles in updates and Refresh missing covers | yes |
| Show updating progress banner (Library) | `showUpdatingProgressBanner()` | upstream KMK | banner during library update | no (KMK upstream) |
| Pause between titles (Migration) | `pref_migration_delay_enabled` / `pref_migration_delay` | true / "1-3" | same, for migration | yes |
| History / Updates tab (Anime/Manga) | `pref_history_media_tab` (in SourcePreferences) | 0 | last tab shown | yes |
| History display mode / columns | `pref_history_display_mode` / `pref_history_columns` | List / 0 | list or library grid | yes |
| Updates display mode / columns | `pref_updates_display_mode` / `pref_updates_columns` | List / 0 | list or library grid | yes |
| Library preview panels | `pref_library_preview_panels` | none | Browse preview panels in the library | yes |
| Panorama style | `pref_library_panorama_style` | FILL | wide-cover style | yes |
| Cover progress bar | `cover_progress_bar` | true | reading progress bar on covers | yes |
| Episode swipe start/end | `pref_episode_swipe_start_action` / `_end_action` | ToggleBookmark / ToggleRead | anime episode swipes | yes |
| Anime update categories (include/exclude) | `anime_update_categories(_exclude)` | empty = all | anime library update scope (**ids**) | yes |
| Anime download new / categories | `anime_download_new`, `anime_download_new_categories(_exclude)` | false / empty | auto download new episodes (**ids**) | yes |
| Anime downloads at once / Wi-Fi only / ahead | `anime_download_concurrent` / `anime_download_only_wifi` / `anime_download_ahead` | 1 / true / 0 | anime downloader | yes |
| Delete after watched / keep bookmarked | `anime_delete_after_seen` / `anime_delete_keep_bookmarked` | false / true | anime download cleanup | yes |
| Hidden anime categories | `anime_library_hidden_categories`, `anime_library_show_hidden_categories` | empty / false | anime library tabs (**ids**) | yes |
| Pair sync devices / selection | `pair_sync_peers` / `pair_sync_selection` | `[]` / all | paired devices (with secrets) and what to sync | yes |
| Update progress after reading (Tracking) | `autoUpdateTrack()` | upstream | also gates anime tracker updates | changed (now used by anime) |
| (internal) anime tracking pending | `__PRIVATE_anime_track_pending` | empty | changes to send later | yes |
| (internal) anime download queue | `anime_download_queue` | empty | queue across restarts (**ids**, and in backups) | yes |

### Cross-feature impact map

- Changing **`MangaRestorer.restore` or `AnimeBackupHelper.restoreAnime`** affects **pair sync**, because `PairSyncData.import` calls them directly.
- Changing **`JsSourceManager.importSource`** affects both the **backup restore** and **pair sync** (same early-return rule and log line), and the Code editor list (`_saved`).
- Changing a **library flow** (`getLibraryManga.subscribe`, `AnimeRepository.subscribeFavorites/LibraryStats/CategoryLinks`) without `LibraryRefresh.pausedWhileRestoring` brings back **slow, stuck restores** (r72/r73).
- Changing **`DownloadProvider` / `SourceFolders` naming** affects **download badges, Check downloads, the cover import after restore (`CoverFolders` looks for `Manga/<src>/<title>`), `index.json` lookups and JS icons**.
- Changing **`StorageManager` folders** (`Manga`, `Anime`, `Icon`, `Sideloaded`, `mpv-config`) affects downloads, the anime player config, sideload restore and the download index (bump `dl_index_cache_vN` if the root changes).
- Changing **`Download.progress` / `progressFlow`** affects the download queue screen's bars (manga tab and All tab) and anything that waits for 100 %.
- Changing **`ReaderVideos.isVideo` / `ChapterVideoDownloads.isVideoFile`** affects downloads (`hasVideos`, page counting, `isDownloadSuccessful`), the reader and page grids.
- Changing **anime category ids or ordering** affects anime prefs that store ids (update, download, hidden, filter categories), so restores can mis-point them.
- Adding an **app pref keyed by an anime/manga/episode id** puts it into backups, where it is restored under ids that no longer match.
- Changing **`TrackerManager.trackers`** (for example adding Simkl to it) affects manga track sheets, library tracker filters, Stats and manga backups. Anime-only trackers must stay in `animeOnlyTrackers`.
- Changing **`BackupNotifier`** affects every parallel restore part. Unlocked builder access crashed restores (r32).
- Changing **proto numbers in `Backup` / `BackupAnime`** breaks reading older backups and the Anikku import (3/501 are taken by other apps' formats).
- Changing **`.sq` without `.sqm`** goes unnoticed on the owner's device until installs over the old app work (r85+), and then crashes upgrades.

### Build pitfalls seen in this area

- **Duplicated functions** after a partial edit of `BackupNotifier` (9dbef2ba, r32 rebuild).
- **`logcat` without a receiver** in `AnimeTrackSheet` / service helpers (320144f9): use `service.logcat(...)` or `logcat("tag", …)`.
- **Wrong import**: `getObjectFromString` is a `PreferenceStore` member, not an importable function (1c15d291, HistoryPreferences/UpdatesPreferences).
- **Menu lambda must return `Unit`**: an `if/else` of toasts as the last expression made a non-Unit lambda (d4095f30, LibraryTab Refresh missing covers).
- **Non-exhaustive `when`** after adding `DownloadAction.ALL_CHAPTERS` (87b66486, LibraryScreenModel + AnimeScreenModel).
- **Missing import** `rememberScreenModel` in the anime library (76a264d6).
- `[skip ci]` WIP commits are not built. A WIP that compiles only together with the next commit is fine, but don't push a non-skip commit in between.
