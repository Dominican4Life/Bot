# Fork guide: how this Komikku fork works, why, and what breaks what

This is the engineering reference for the owner's fork of Komikku (`Dominican4Life/Komikku`, app id `app.komikku.beta`). It covers what the fork added on top of upstream Komikku (Mihon + TachiyomiSY): the anime side (Anikku/Aniyomi), AniZen's mpv player, in-app JS/JSON sources and the Code editor, the browse/extension redesign, previews, and many fixes. For each part it records **how it works, why it was built that way (the bug it fixed), what must not change, and what will break if it does**.

**Who it's for:** the owner, and any AI session that changes this code. The owner can only test on a phone/tablet, and every build costs a release cycle (r1, r2, …). So **read the relevant section before changing anything, and check its "Cross-feature impact map" and "Predictable failure modes"**.

Written 28–29 October 2026, at r84 (commit a693dc09). The sections were written from the code, the fork's 237 commits and the conversation history. Facts marked unverified were not tested on a device. When the code and this guide disagree, the code wins; update the guide.

## Sections

| # | File | Covers |
|---|------|--------|
| 1 | [01-build-ci-conventions.md](01-build-ci-conventions.md) | Modules, build variants and version numbers, the CI workflow, **signing (why every release needs an uninstall)**, release flow, repo rules, logging, networking, past compile errors, **"Before you push" checklist** |
| 2 | [02-browse-extensions.md](02-browse-extensions.md) | Browse tabs, browsing a source, filters and tag boxes, saved searches, Feed, global search, the unified Extensions screen, **repositories (the repo-issue history r64–r76)**, installers and Sideload, known issues from the r84 outside review |
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
7. **Database: every `.sq` change needs a matching `.sqm` migration.** The owner has been reinstalling every release, so no migration has really run on a device yet. Once updates install over each other (after the signing fix), a bad migration crashes the app on open (sections 1 and 6).
8. **Backups: anything keyed by a database id comes back pointing at the wrong row after a restore** (ids change on a fresh install). Prefer keys by source + url (sections 6 and 7).
9. **`source-api` changes break installed extensions at runtime, not at compile time** (`NoSuchMethodError`). Add overloads; don't change signatures (`Video` has two `copy` overloads for this reason; section 4).
10. **Compile risks CI catches only after a push:** missing imports (r81), Compose `remember` inside conditions, lines over 120 characters, the two `Video.copy` overloads (pass `videoPageUrl` to pick the right one). Use the checklist in section 1.

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
| `ExtensionStoreUrls.key()` / available-extension map key | repo counts, duplicates, updates, sideload restore | 2 |
| Manga `ExtensionManager` install steps | **anime** installs too (they share the installer and are forwarded from the manga manager) | 2 |
| `SideloadFolder.sync` | can uninstall extensions whose APK is missing from the folder, on every start | 2 |
| `AutoCompleteItem` (tag boxes) | E-Hentai tags, JS tag filters, anime tag filters, Browse preview tag hold | 2 |
| Library flows without `LibraryRefresh.pausedWhileRestoring` | slow/stuck restores come back (r72/r73) | 6 |
| Download folder naming | download badges, Check downloads, cover import after restore, `index.json`, chapter video pairing (`NNN`, `NNN__001`) | 3, 6 |
| `Download.progress` | the queue's bars; anything waiting for 100 % | 6 |
| Backup proto numbers | reading older backups and Anikku imports | 6 |
| Settings keys | the reader's "Skip filtered chapters" and the player's skip-filtered-episodes share a key; the Performance profile silently overrides decoder switches | 7 |

## Known open issues and risks (not fixed at r84)

Collected from all sections. These are what to look at first when something odd is reported.

**Release and update**
- Every release is signed with a new random key (the committed keystore isn't used), so no release installs over the previous one. Fix: an explicit `signingConfigs` block (section 1). After that fix, migrations start running for real (rule 7).
- `versionCode` is fixed at 81. New `Migration`s in `Migrator` won't run unless it is bumped (section 1).
- About shows the commit count ("Beta r10873"), not the release number (r84).

**Extensions and repos (section 2)**
- Repos are keyed by package + signing key. Two repos with the same (or no) key offering the same package overwrite each other, so one repo shows fewer packages. This is the most likely cause of the next "repo shows 0" report.
- Manga install errors silently put the row back to Install with no message.
- From the r84 review:
  - The repo kind chips are cut off ("Aniyo").
  - Search filters the Repositories count down to 0.
  - The keyboard hides results.
  - Returning from "Install unknown apps" opens the library.
  - The warning says "Private Installer", but the option is named "Sideload".
  - Once, Android's prompt named the wrong extension and the row was stuck on "Installing" after Cancel (cause unproven).

**JS sources (section 3)**
- No time limit on JS: a loop that never awaits blocks that source everywhere until restart, with nothing in Logs.
- Imported/restored source keys go into file paths unchecked, and imports skip the id checks.
- The HLS relay drops 100 sessions when it passes 500, which can 404 a video that's playing.
- Each runtime copy of a source caches `storage.json` separately, so one can overwrite what another wrote.
- Testing in the Code editor writes to the saved source's real settings.
- No "unsaved changes" warning in the editor.
- Chapter video save failures leave a chapter looking complete.

**Player and anime (section 4)**
- A quality list with only bandwidth (no resolution) makes HlsVariants pick the lowest quality, for playback and "best" downloads.
- The quality list is fetched with only the video's own headers.
- The 4 s limit can't interrupt ffprobe.
- The preview player, the outside player and anime downloads don't go through `split`.
- Network-aware preloading caps quality at 720p on metered networks, which its label doesn't say.

**Images (section 5)**
- Coil's image disk cache is ≤ 250 MB and shared by non-library covers, Browse preview pages and JS preview tiles. Pages viewed in a preview panel are downloaded again when the reader opens.
- Downloaded CBZ preview tiles copy whole entries into memory (up to 8 at once).

**Backup (section 6)**
- Anime ids change on restore, but settings that store anime/category ids are restored unchanged: notes, update/download/hidden categories, intro length, the download queue.
- Not in a backup:
  - Aniyomi extension settings.
  - Merged anime.
  - Player custom buttons.
  - Watch time.
  - JS source storage.
  - Tracker logins, unless "Private settings" is ticked.
  - The Logs file.

**Settings (section 7)**
- Some settings do nothing (listed in section 7), e.g. "Show restoring progress banner" and the player's "Remember last used volume".

## How to use this guide when adding a feature

1. Find the section for the area you're changing, and read its feature entry, invariants and impact map.
2. Search this folder for the class or preference key you'll touch (`grep -rn "HlsVariants" docs/fork-guide`). Every section that mentions it is a place that can break.
3. Plan the change, list what it could break, and tell the owner before building.
4. Add an AppLog line that proves the new path works (section 1, "Logging conventions"), so one test run tells you whether it worked.
5. Run section 1's "Before you push" checklist.
6. After the release, update the relevant section: what changed, why, and which release.
