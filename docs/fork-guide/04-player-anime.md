## Video player and the anime side

Paths are relative to `app/src/main/java/eu/kanade/` unless they start with a module name. "Fork" means commits by
`Claude` / `Dominican4Life` / `OmfgItsJesus`. The player itself is AniZen's mpv player (0.5.218, synced in r56,
403d7446). Its code is marked `// ANZ`, and Anikku code is marked `// ANK`. The fork's own changes are inside `// KMK`.
The anime side came in four "Anime stage" commits on 2026-10-08 (39f455aa, e284e45c, 23ba5816, d8f5a7e9).

The newest work (r82 aa5d1710, r83 0b1ac366, r84 a693dc09) is all about **multi-quality streams**. Most of the
warnings below come from there.

### How playback starts (the pipeline)

The pipeline, in order:

1. `PlayerViewModel.loadEpisode(id)` sets the episode. Then `EpisodeLoader.getHosters(episode, anime, source,
   allowDownloaded = true)` (`tachiyomi/ui/player/loader/EpisodeLoader.kt`) picks one of these:
   - **Downloaded first.** `getHostersOnDownloaded` asks `AnimeDownloadManager.findEpisodeFile`. If there is a file, it
     returns one hoster called `"Downloaded"` with one `Video(videoUrl = file.uri, videoTitle = "Downloaded",
     initialized = true)`. Subtitles are any `<episode>.<lang>.srt|ass|ssa|vtt` files next to it.
   - `AnimeHttpSource`: hosters if the extension class declares `getHosterList` / `hosterListRequest` /
     `hosterListParse` (`checkHasHosters`, which uses reflection). Otherwise `getVideoList().sortVideos().toHosterList()`.
   - `JsAnimeSource` (Code editor): `source.getHosterList()` (the source groups videos by server itself).
   - Any other source: `getVideoList().toHosterList()`.
2. `PlayerViewModel.loadHosters(source, hosters, hosterIndex, videoIndex, preferredVideoTitle)` sets each hoster to
   `Idle` (lazy), `Loading`, or `Ready` (if the video list is already there). Then it runs
   `EpisodeLoader.loadHosterVideos` **in parallel for every hoster**.
3. `loadHosterVideos` → `getVideos`, which:
   - resolves "null" URLs (ext-lib 16 compatibility),
   - sorts with the source's `sortVideos()`,
   - resolves Stremio subtitles,
   - finally calls **`HlsVariants.split(videos)`** (since r82; see below).

   The result is `HosterState.Ready(name, videos, states)`, or `HosterState.Error` if it throws.
4. Which video starts:
   - With `hosterIndex == -1` (normal start), the per-anime "default stream" selector (`DefaultStreamSelector`,
     remembered when the user taps a video) is used if one exists.
   - Otherwise the first `video.preferred`.
   - Otherwise `HosterLoader.selectBestVideo` (the first READY/QUEUE video).
   - On a metered network with "Network-aware preloading" on, `capQualityIndex` lowers the pick to ≤720p and logs
     `Metered network: 'X' -> 'Y'` to PlayerDebugLog.
5. `loadVideo` resolves the video (`HosterLoader.getResolvedVideo` → `source.resolveVideo` when `!initialized`), sets
   `currentVideo`, and sends `Event.SetVideo(video, startAt)`.
6. `PlayerActivity.setVideo(video, position)`, step by step:
   - `PlayerDebugLog` gets `Loading video '<title>': <url>`.
   - `applyStreamCache` sets the demuxer cache (MiB from the performance profile × buffer handoff × metered) and logs
     `Cache: N MiB, read-ahead S s`.
   - `setHttpOptions` sets the headers.
   - It sets `start`.
   - Torrent URLs go to the torrent server. Everything else goes to `loadFile(url, mpvArgs)`.

**Threading:** hoster and video loading run on `viewModelScope.launchIO`, one `async` per hoster.
`getHosterVideoLinksJob` is cancelled when a new `loadHosters` starts. mpv events arrive on mpv's thread.
`PlayerObserver.event` calls `activity.event` directly; property events are posted with `runOnUiThread`.

**Invariant:** the episode doesn't start until `HlsVariants.split` has returned for the chosen hoster, because `split`
runs inside `loadHosterVideos`. Anything slow added to `getVideos` delays every episode start, for every source.

### `loadFile`, forced `sid`/`aid`, headers

- **Where:** `PlayerActivity.loadFile(url, mpvArgs)`.
- **What it does:** it always appends `sid=no`, `aid=no` to the per-file options of `loadfile url replace 0
  <options>`. The view model chooses tracks later, in `onTracksLoaded` (~line 735): preferred subtitle, then preferred
  audio. `AniyomiMPVView.init` also sets `sid=no` / `aid=no` globally.
  - It is the only path that issues `loadfile` (the torrent path included), so the torrent branch can't inherit the
    previous file's track choice (ANK comment).
- **`formatMpvOptions`:** escapes awkward values as `%<bytes>%value` and drops invalid option names with a WARN.
  - Don't swap it for the FFmpeg sanitizers. They throw on `$ ( \ -`, and that kills the event collector.
- **`video.mpvArgs` are per-file options.** `HlsVariants` uses this to pass `vid=N` for downloaded multi-quality files
  (see below).
  - **Don't add `vid` to the forced options**, or downloaded qualities will all show the same picture.
- **Headers** (`setHttpOptions`):
  - Source: the video's own `headers`, else the `AnimeHttpSource` / `JsAnimeSource` headers.
  - **User-Agent** is taken out and set as mpv's `user-agent` option (a0ee681d, r53). mpv sends its own UA, and
    Cloudflare `cf_clearance` only works with the exact UA it was issued for.
  - The other headers go in `http-header-fields`.
  - PlayerDebugLog: `HTTP headers: Referer, Cookie, …`.

### Decoder: `hwdec = mediacodec-copy` (r82)

- **Where:**
  - `AniyomiMPVView.applyVideoProfile()`, called from `init` and again when the performance profile changes while
    playing.
  - `PlayerProfiles.kt` `DecoderPreferences.effectiveVideoSettings()`.
- **Now:** `hwdec = if (settings.hardwareDecoding) "mediacodec-copy" else "no"`.
  - `hardwareDecoding` = "Try hardware decoding" (`pref_try_hwdec`, default true) under the Custom profile. Balanced
    and Battery saver force it on.
- **History (r82):**
  - It used to be `"auto"`. mpv's auto tries zero-copy `mediacodec` first. That can't render through the app's video
    output (`gpu` / `gpu-next`).
  - The owner's log showed this sequence:
    `E mpv/ffmpeg/video h264_mediacodec: Both surface and native_window are NULL`, then about 14 s later
    `I mpv/vd Using hardware decoding (mediacodec-copy).`
  - So every video lost 5–10+ s before falling back.
- **Don't change it back** to `auto` or `mediacodec` without also changing the video output.
- **Manual decoder:**
  - The MoreSheet "Hardware decoding mode" calls `PlayerViewModel.updateDecoder` → `mpv.setPropertyString("hwdec",
    …)`. `Decoder` enum: Auto-copy / Auto / SW / HW = `mediacodec-copy` / HW+ = `mediacodec`.
  - Picking HW+ brings back the NULL-surface stall for that session.
  - A quality/server handoff keeps the manual decoder (`_manualDecoder` is restored in `loadVideo`).
- **Check it:** the Logs screen should show `mpv/vd Using hardware decoding (mediacodec-copy)` right after
  `mpv/cplayer ● Video --vid=1 …`. `Both surface and native_window are NULL` should no longer appear.
- **Other options in `init`:**
  - `msg-level=all=status`. 7d617511 set it to `all=warn`, and r56 (1546a765) changed it to `status` so PlayerDebugLog
    gets the status lines.
  - `tls-ca-file` points to mpv's cacert.
  - Demuxer cache is 64 MiB (32 below API 27).
  - `use_yuv420p` → `vf=format=yuv420p`.
  - `setSafeOptionString` skips any option the user set in mpv.conf (`pref_mpv_conf`), so mpv.conf always wins. The
    `hwdec` line is **not** "safe": it's set with `setOptionString` directly, so an `hwdec=` in mpv.conf is overridden.
    That's probably unintended (unverified on device).

### Splitting multi-quality HLS: `HlsVariants` (r82, r83, r84)

File: `tachiyomi/ui/player/loader/HlsVariants.kt` (fork).

**What it does:**
- A stream that is an HLS master playlist with several qualities (480p/720p/1080p, each with muxed audio) becomes
  one `Video` per quality, plus the whole list at the end as **"Auto"**.
- The player starts on the preferred quality straight away.
- The Qualities sheet lists each quality.

**Why (r82):**
- Given the whole master, mpv read two segments of *every* variant one after another before starting (**~12 s** on
  Mitaku).
- It then played the best picture with the *lowest* variant's sound, and downloaded two streams the whole time.
- The user saw "3 audio options and no qualities", because each variant's audio showed up as an audio track.
- A source like Anikoto that gives one link per quality was unaffected.

**How `expand(video, preferredHeight)` works:**
1. It only runs for `http…` URLs where `isPlaylist` is true: **"m3u8" anywhere in the URL** (case-insensitive) or
   `mimeType` contains "mpegurl".
2. `read(video, sourceHeaders)`: GET with **`video.headers ?: sourceHeaders`** (since r86; up to r85 only
   `video.headers`) through `NetworkHelper.client`. `EpisodeLoader` passes the source's headers
   (`split(videos, sourceHeaders)`), downloads pass their own headers (`forDownload(video, headers)`). It reads at
   most 256 KB (`MAX_BYTES`) of the body. The body must start with `#EXTM3U`.
3. If any `#EXT-X-MEDIA:…TYPE=AUDIO…URI=` line is present (separate audio), it returns null, so the list is not split.
   A split would leave the picture with no sound.
4. It parses `#EXT-X-STREAM-INF` (`RESOLUTION=WxH` → height, `BANDWIDTH=`) and resolves the next URI line against
   the final (post-redirect) URL. `distinctBy { height ?: bandwidth ?: url }` keeps one entry per quality.
5. Fewer than 2 variants: the video is left as is.
6. Sort best first by height, then bandwidth (r86: `compareByDescending { height ?: 0 }.thenByDescending
   { bandwidth ?: 0 }`). Pick, in order:
   - no variant has a height, or a download (`BEST`): the first (highest),
   - exact preferred height,
   - else the best height ≤ preferred,
   - else the lowest sized one.

   The pick goes first in the list. Only the pick inherits `video.preferred`.
7. Titles: `"720p"` (or `"<original title> · 720p"` when the original title isn't generic: video/auto/default/stream/
   hls/source), and `"… · Auto"` for the untouched master.
8. Limits:
   - `TIMEOUT_MS = 4000` per video.
   - `MAX_VIDEOS = 6`: a hoster with more than 6 videos is not split at all.
   - The videos of a hoster are expanded in parallel.
9. AppLog `I hls Split a stream into 3 qualities (1080p, 720p, 480p), starting on 1080p`.

`preferredHeight()` = digits of `pref_preferred_quality` (default `"1080"`; the "Highest quality" entry is also
"1080"), else 1080.

**r84 fix: relayed playlists were not detected.**
- Symptom: Mitaku still showed qualities as audio tracks, and there was no "Split a stream" log line.
- Cause: Code editor HLS streams play through `jssource/HlsRelay`, whose URLs look like
  `http://127.0.0.1:PORT/p/<id>?u=<encoded>&x=list.m3u8`. The old check looked for ".m3u8" only **before the "?"**.
- The same bug made downloads save every quality.
- Fix: "m3u8" anywhere in the URL.
- The relay rewrites every variant line of a master to relay URLs (`HlsRelay.rewrite`). So the split videos are still
  relay URLs and keep the fake-PNG stripping. `read()` going through the relay is fine.

**Downloaded files that hold several qualities (r83, `localQualities`):**
- Files downloaded before r83 (and before r84 for relayed streams) contain every variant: video streams in variant
  order, audio streams in the same order. This comes from ffmpeg `-map 0:v? -map 0:a?`.
- For `content://` (via `FFmpegKitConfig.getSafParameterForRead`), `file://` or `/` URLs, it runs
  `FFprobeKit -show_entries stream=index,codec_type,height -of json`.
- If there are ≥2 video streams with ≥2 distinct heights, it builds one video per picture:
  - `videoTitle = "720p"`,
  - `mpvArgs += ("vid" to N)`,
  - `internalData = "kmk-quality-track:N"`, only when the counts of audio and video streams are equal.
- The preferred height goes first.
- Log: `I hls A downloaded video holds 3 qualities (1080p, 720p, 480p), starting on 1080p`.
- `HlsVariants.soundTrackOf(video)` reads the marker. `PlayerViewModel` uses it twice:
  - the `audioTracks` flow shows only that audio track id,
  - `onTracksLoaded` selects it instead of the language preference.
- **Invariants:**
  - mpv numbers each track type from 1, in file order. ffprobe's order must match, and audio N must belong to video
    N. That holds only for files our own ffmpeg command wrote.
  - The marker prefix `kmk-quality-track:` is shared by `HlsVariants` and the two `PlayerViewModel` call sites. If you
    rename it, change all three.
  - It overwrites `internalData`. Fine for downloaded files, whose internalData is empty. Never apply it to source
    videos, because extensions use internalData.

**`forDownload(video)` (r83, changed in r84):**
- It returns the variant to save, or `video` unchanged.
- r83 saved the *preferred* quality. r84 (the owner asked "save best quality only") calls
  `expand(video, Int.MAX_VALUE)`, which picks the highest known height.
- Callers:
  - `AnimeDownloadManager.ffmpegDownload`. Its comment now says "(the best)" and that the list is read with the
    download's headers (the source's when the video has none).
  - `jssource/ChapterVideoDownloads` (videos in manga chapters).
- For comparison, AniZen's downloader takes the *first* listed variant ("Resolved master playlist to variant"). Ours
  takes the highest.

**Predictable failure modes:**
- Fixed in r86: **no `RESOLUTION=` in the master (only BANDWIDTH).** Every height was null, so `pick` fell to the
  **lowest bandwidth** for playback and for "best" downloads. Now such a list is ranked by bandwidth and starts on (and
  downloads) the highest; labels read e.g. "2.5 Mbps".
- Fixed in r86: **hosts that need the source's Referer/Cookie** but don't put headers on the `Video`. `read()` used
  only `video.headers`, got 403, and the video stayed unsplit (old slow start, all-qualities download). Now it falls
  back to the source's headers. If it still happens, look for `W net GET <master> -> 403` with no `hls Split` line
  after it: the host wants something neither set of headers has.
- **The timeout is weak against blocking reads.** `withTimeoutOrNull` can't interrupt a blocking call:
  - `FFprobeKit.executeWithArguments` is blocking,
  - `source.request(MAX_BYTES)` is a blocking read.

  A stuck local probe or a slow body can hold the episode start longer than 4 s. Only the suspending `await()` (the
  response headers) is cancellable.
- **The default-stream selector and self-healing match by title.**
  - `loadHosters(preferredVideoTitle=…)` finds the video by `videoTitle`, and `DefaultStreamSelector` fingerprints
    titles and resolution.
  - The split titles are deterministic (`"<base> · 720p"`). Keep them that way. If you change the label format, every
    saved per-anime default stream (`pref_per_anime_default_stream_data`) stops matching.
- `MAX_VIDEOS = 6`: a source with 7+ links in one hoster gets no splitting at all, even if one of them is a master.
- **Casting a split quality:** the Cast path doesn't know `vid=N` (the downloaded-file case), so the TV plays the
  file's default video track. `HlsRelay.original()` exists for casting, but nothing calls it. Not verified on a device.

**How to check (Logs screen):**
- Good stream: `I hls Split a stream into 3 qualities (1080p, 720p, 480p), starting on 1080p`, then
  `mpv/cplayer ● Video --vid=1 (h264 1920x1080 …)` with one `Audio --aid=1`.
- Bad (not split): no `hls` line, and several `○ Audio --aid=2/3` lines for one stream.
- Downloaded file: `I hls A downloaded video holds …`.

### Qualities sheet order: `QualityOrder` (r84)

- **Where:** `tachiyomi/ui/player/controls/components/sheets/QualityOrder.kt`, used by `QualitySheet.kt`
  (`hosterContent`, `QualitySheetVideoContent`, `computeFlatVideoLazyIndex`, scroll target).
- **What it does:** within each server, the menu shows highest first (1080p → 720p → 480p → 360p), like AniZen.
  - Videos that differ only by quality stay grouped. The group key is the title with the height, "auto" and
    separators removed, so "SUB - …" stays apart from "DUB - …" and groups keep the source's order.
  - Videos with no height ("Auto") go last in their group. The sort is stable.
  - The height comes from `video.resolution`, else the last `\d{3,4}p` in the title.
- **Why:** the owner asked "quality list always appears highest to lowest … like anizen". In AniZen each extension
  orders its list. The fork does it in the app so no JS source needs changing.
- **Invariant (the important one):**
  - `QualityOrder.of(list)` returns **indexes into the list**. Every click, selection, `videoState[idx]`,
    default-highlight and scroll target still uses the list's own index.
  - Only the drawing order changes. The video that *starts* is still the preferred/selector pick.
  - If you ever sort the `videoList` itself, the `HosterState` indexes, `selectedHosterVideoIndex`, `qualityIndex`
    and saved default streams all break.
  - Lazy keys are `"video-$hosterIdx-$videoIdx-<url hash>"` and must stay unique.

### Qualities button visibility (`PlayerButtons.kt`, r84)

- `PlayerButton.QualityTracks` shows when `isEpisodeOnline == true` **or** the Ready hosters hold more than one video
  in total: `hosters.sumOf { (it as? HosterState.Ready)?.videoList?.size ?: 0 } > 1`.
- Before r84 it showed only online. A downloaded multi-quality file was split correctly (the log said so) but had no
  button.
- `isEpisodeOnline()` = (`AnimeHttpSource` or `JsAnimeSource`) and not downloaded.
- Other rules in the same `when` block:
  - Cast needs `pref_enable_cast` plus an available Cast context.
  - PiP needs `isPipSupportedAndEnabled`.
  - VideoZoom shows only when zoom ≠ 0.
- `collectAsState` inside a `when` branch is fine because each branch is a stable button.

### Audio track filtering

`PlayerViewModel.audioTracks` = `combine(track-list, currentVideo)`, which:
- drops the tracks titled with `VideoTrack.TRACK_TITLE_TAG`,
- with a quality marker, keeps only `id == soundTrackOf(video)`.

Selection order in `onTracksLoaded`:
1. the marker's track,
2. else `TrackSelect.getPreferredTrackIndex` (language prefs `pref_audio_lang`; AniZen "audio track memory").

`hasLoadedAudio` / `checkFileLoaded()` release the loading state. If you add a path that skips both selections, still
call `checkFileLoaded()`, or the spinner never ends (ANK comment).

### Playback problems: logs and self-healing

- **Two logs:**
  - `AppLog`: the Logs screen, from the debug button on the navigation rail or (since r86, phones too) More › Logs.
    It gets everything from logcat/XLog at
    DEBUG and above, including mpv INFO/WARN/ERROR as tag `mpv/<prefix>` (`PlayerObserver.logMessage`), network
    lines `net GET … -> 200 in N ms`, `hls`, and `anilist`.
  - `PlayerDebugLog` (`PlayerDebugLog.kt`): 400 lines, cleared each time the player opens. It holds the `player`,
    `self-heal` and `network` lines plus mpv `status`-level lines. You only see it through "Copy log" in the
    **Playback problem** dialog (`showPlaybackProblem`). Lines like `Loading video…`, `Cache:` and `HTTP headers:`
    are **not** in the Logs screen.
- **End of file with an error:**
  - If an HTTP error happened or the message contains "loading failed", it calls `trySelfHeal` (`pref_self_healing_links`,
    default true).
  - That refetches hosters with `allowDownloaded = false`, reloads the same hoster/title, and resumes at the live
    position. On a second failure it moves to the next video; it stops after `MAX_SELF_HEALS` tries.
- **`eof-reached` early:**
  - Before 15 s, or 30+ s before the end: self-heal, else the "ended right after it started" dialog.
  - `isLocalFile` checks only `file…` URLs, so a SAF `content://` download that ends early can still show the dialog
    (a12386f1 meant to exclude device files).

### Downloads (`tachiyomi/data/anime/download/`)

- **Files:** `AnimeDownloadManager` (queue + files) and `AnimeDownloadJob` (WorkManager foreground worker,
  notification `ID_ANIME_DOWNLOAD_PROGRESS`, updates at most every 700 ms).
- **Videos:** come from `data/anime/AnimeVideoFetcher.fetchVideos`. This is **not** `EpisodeLoader`, so downloads
  never go through `HlsVariants.split`. Titles become `"<hoster> • <video>"`, sorted by `sortVideos()`.
- **Fallback:** it tries the first `MAX_TRIES = 3` videos in order and falls back to the next if one fails.
- **Where files go:**
  - `<downloads>/Anime/<Source (LANG)>/<title>/<scanlator_name>.mkv|mp4|webm`.
  - A `.tmp` while writing.
  - The source folder is matched through `SourceFolders.match` (any tag such as "(JS)", numbered copies).
  - Stored with `UniFile` (SAF).
- **`isStream`:** `.m3u8`/`.mpd` before "?", `/hls/`, "m3u8" anywhere, or (r86) a `mimeType` containing
  "mpegurl", so relayed and untagged playlists are caught. Streams go to `ffmpegDownload`, everything
  else to `httpDownload`.
- **`httpDownload`:**
  - Uses the source's client (cookies, Cloudflare), with a percent from Content-Length.
  - If the content type turns out to be mpegurl/dash, it switches to ffmpeg.
- **`ffmpegDownload`:**
  - `forDownload(stream)` first.
  - `probeDuration` (ffprobe, used for the percent).
  - `ffmpeg -headers … <ffmpegStreamArgs> -i URL -map 0:v? -map 0:a? -map 0:s? -c copy -f matroska` into
    `cacheDir/anime_download/<uuid>.mkv`, then copied into the SAF folder.
  - Percent = `stats.time / duration`, capped at 99 until the copy finishes.
- **History:** "downloads saving all qualities". `-map 0:v? -map 0:a?` maps *every* variant of a master. That's why
  `forDownload` must run before it (r83), and why relayed URLs had to be detected (r84). Don't remove the `?` maps or
  the pre-selection.
- **Queue:**
  - Saved in `anime_download_queue` (string set, `"<anime>:<episode>"`), restored at app start. It holds ids, so a
    backup restore skips it (r85, `AnimeBackupHelper.isAnimeIdKey`).
  - Workers = `anime_download_concurrent` (1..5).
  - Errors go to `failures`; cancelled items go back to QUEUE.
- **The player and downloads:** `EpisodeLoader.isDownload` → `AnimeDownloadManager.isDownloaded`, which uses an
  in-memory `fileCache` per anime. `refresh(anime)` reloads it when the anime page opens. A file added outside the app
  isn't seen until then.
- **Prefs (`data/anime/AnimePrefs.kt`):**
  - `anime_download_only_wifi` (true), `anime_download_concurrent` (1).
  - `anime_delete_after_seen` (false), `anime_delete_keep_bookmarked` (true).
  - `anime_download_ahead` (0), `anime_download_new` (false), plus the category include/exclude sets.

### Anime details screen: episode cards with AniList/ani.zip info (r82)

- **Files:**
  - `tachiyomi/ui/anime/AnimeScreen.kt` (`episodeItems`, `EpisodeCardItem`, `EpisodeInfoLine`,
    `AnimeDialog.EpisodeInfo`),
  - `AnimeEpisodeCards.kt` (`AnimeEpisodeCard`, `AniListEpisodeInfoLine`, `EpisodeInfoDialog`),
  - `AnimeScreenModel.kt` (`loadEpisodeInfo`, `changeEpisodeInfo`, `resetEpisodeInfo`; state `episodeInfo`,
    `episodeInfoLoading`, `episodeInfoChecked`),
  - `anilist/AniListEpisodeMatch.kt`, `anilist/AniListApi.kt`.
- **What it does:**
  - When the anime's "show previews" or "show summaries" display flag is on, episodes are drawn as AniList-page-style
    cards: picture, "N. Title", summary, minutes, ★ rating, filler badge, date, progress, download button.
  - Cards are 1 per line on the phone layout (`AnimeScreenSmall`) and 2 on the tablet layout (`AnimeScreenLarge`).
    `episodeColumns` is hard-coded per layout, not per width.
  - A line above the cards says "Episode info: AniList · <title>" with a **Change** button.
  - The data comes from the app's AniList data (the same ani.zip list as the AniList page), not from each source's own
    "outside episode lookups". In the JS sources those lookups are now off by default.
- **Which AniList entry (`AniListEpisodeMatch.find`):**
  1. Pref `anime_anilist_episodes_<animeId>`: `""` = not looked up, `"none"` = no entry, `"<id>"` = picked with
     Change, `"auto:<id>"` = found by title.
  2. AniList track (`TrackerManager.ANILIST`).
  3. MAL track (tracker id 1) → `AniListApi.idForMal`.
  4. Title search: an exact normalized title match with the closest episode count, else a prefix match (≥4 chars).
     The result is saved as `auto:<id>`.
- **Offset:** a source that numbers season 2 as 13–25 while AniList has 1–13 gets `offset = min-1`, shown as
  "(from episode 13)". `of(number)` maps only whole positive numbers.
- **Time limits (r82):**
  - Owner symptom: "Episode details lookups hold up the episode list".
  - Now `AniListEpisodeMatch.load` is capped at 20 s overall (`TIMEOUT_MS`).
  - Inside `AniListApi.episodes`, ani.zip (`api.ani.zip/v1/episodes?anilist_id=…`, falling back to `mal_id` on 404)
    and fillers (`api.tenrai.org`, up to 20 pages) each get `EXTRA_LOOKUP_MS = 6000`.
  - The lookup runs once per screen (`episodeInfoJob`, which must be declared before `init` because init can start it).
    The episode list shows first and the cards fill in later.
- **Caches:** `AniListApi.detailsCache` and `episodesCache` are per process.
- **Logs:**
  - `I anilist <title>: episode info from AniList "<title>" (N episodes[, source numbers start at K])`
  - `I anilist <title>: no AniList entry found for the episode info`
  - `W anilist <title>: no episode info (…)`
- **Fallbacks:** each field falls back to the source's own `previewUrl` (through the `EpisodePreview` Coil model,
  kept with the anime) and `summary`.
- **Known gaps:**
  - Fixed in r86: cards swipe like the episode rows, with the same settings (`episodeSwipeToEndAction` /
    `episodeSwipeToStartAction`, `getSwipeAction` in `ui/anime/AnimeScreen.kt`). Before, card mode returned before
    the row list and had no swipe actions.
  - The card UI strings are hard-coded English ("Change", "Looking for this anime on AniList…") instead of `KMR`.
  - The lookup isn't redone when new episodes arrive during the same visit, so the offset can be stale until reopen.

### Network bit the player relies on: IPv4 first (r82)

- **Where:** `core/common/.../network/NetworkHelper.kt` `clientBuilder()`. After the DoH choice it wraps the resolver:
  `resolver.lookup(host).sortedBy { if (it is Inet4Address) 0 else 1 }`.
- **Why:** on networks where IPv6 doesn't get through, hosts with AAAA records waited out the connect timeout first.
  jikan.moe took 5 s and then failed.
- **Scope:** applies to every OkHttp client from `NetworkHelper`:
  - `client` and `nonCloudflareClient`,
  - extension clients built from them,
  - `HlsVariants.read`, `HlsRelay`, AniList/ani.zip, `httpDownload`.
- **Doesn't apply** to mpv or FFmpeg, which have their own network stacks. A player stall on an IPv6-only path isn't
  fixed by this.
- **`HlsRelay`'s fallback:** when a host resolves to 0.0.0.0 (ad-block DNS), it re-resolves through Cloudflare/Google
  DoH (r68), with log `hls <host> is blocked by this device's DNS…`.

### Video model (`source-api/.../animesource/model/Video.kt`)

- **Fields:**
  - Constructor: `videoUrl` (var), `videoTitle`, `resolution`, `bitrate`, `headers`, `preferred`,
    `subtitleTracks`, `audioTracks`, `timestamps`, `mpvArgs`, `ffmpegStreamArgs`, `ffmpegVideoArgs`, `internalData`,
    `initialized`, `memo` (ext-lib 17, 668512a4).
  - Body `var`s: `type`, `mimeType`, `videoPageUrl` (legacy `url`).
  - A hidden ext-lib-16 constructor and a hidden ext-lib-16 `copy` (no `memo`). They keep old extensions binary
    compatible, so **don't delete them**.
- **Two visible `copy` overloads:**
  - one without `type`/`mimeType`/`videoPageUrl`,
  - one with them.

  Both carry the body vars over. A call with only shared parameters resolves to the shorter one (fewer defaulted
  parameters). Passing `videoPageUrl = …` (as `HlsVariants` does) selects the second.
- **Keep both** when you add a field: add it to both, or an `ambiguity`/missing-field build error follows. Also add it
  to `SerializableVideo` (serialize/toVideoList), or it's lost on the preload/cast round-trip.

### Chromecast and torrent

- **Chromecast** (`ui/player/CastManager.kt`, `ui/player/cast/**`, AniZen r56):
  - `CastMediaBuilder.buildMediaInfo` serves `content://`/`file://` through the local HTTP server.
  - Videos that need headers go through the local proxy (`getProxyUrl`, which also rewrites HLS).
  - Off unless `pref_enable_cast`.
  - The first Cast button attempt (2e678f65) was undone in 3d76c44f and redone in r55/r56.
- **Torrent:**
  - Magnet/`.torrent`/torrent-server URLs: `TorrentServerService.start()`, then a 10 s wait, then
    `torrentLinkHandler` → `loadFile`.
  - Errors go to PlayerDebugLog `Torrent error:` and a toast.
  - Self-healing skips torrent URLs.

### Anime library / updates: what differs from manga

- **`data/anime/AnimeLibraryUpdateJob.kt`:**
  - Runs with the manga update (`anime_update_interval = -1`, the default) or on its own schedule in hours (0 = never).
  - 5 sources in parallel, titles of one source one after another, with the manga "pause between titles" setting.
  - Skips per `anime_update_skip` (default `completed`; also `has_unseen`, `not_started`, `smart`).
    `isBeforeExpectedRelease` uses the median gap of the last 5 upload days.
  - Waits up to 10 s for extensions to load.
  - A manual run with ids (one library page) ignores the category filters.
  - Log: `anime Anime update: N checked, S skipped, F failed, U with new episodes`.

---

### Settings in this area

| Setting (as shown) | Key | Default | What it changes | Fork-added? |
|---|---|---|---|---|
| Preferred quality (Player › Hosters) | `pref_preferred_quality` | `"1080"` ("Highest quality" = 1080) | `VideoComparator` sort; `HlsVariants` start quality for streams and downloaded files. Not used for downloads since r84 | changed (also drives HlsVariants) |
| Performance profile | `pref_performance_profile` | `Automatic` (shown "Custom (your settings)") | Overrides HW decoding, scaling, smooth motion, Anime4K cap, cache ×0.5/1/2 | yes |
| Try hardware decoding | `pref_try_hwdec` | true | `hwdec = mediacodec-copy` vs `no` (Custom / Best quality) | changed (value used) |
| Enable gpu-next | `pref_gpu_next` | false | vo `gpu-next` vs `gpu` | no |
| Use YUV420P pixel format | `use_yuv420p` | true | `vf=format=yuv420p` | no |
| High quality scaling / Smooth motion | `pref_high_quality_scaling` / `pref_smooth_motion` | false / false | scalers, interpolation, display-resample | no (profile-aware: yes) |
| Self-healing links | `pref_self_healing_links` | true | Refetch and resume on HTTP failure / early end | no (made to work r56) |
| Intelligent buffer handoff | `pref_intelligent_buffer_handoff` | false | Cache ×1.5, read-ahead 20 s, keeps position on quality switch | no (made to work r56) |
| Network-aware preloading | `pref_network_aware_throttling` | true | **Also**: on metered networks the automatic pick is capped at 720p and the cache is halved. The label doesn't say this | changed |
| Preload next episode | `pref_preload_mode_key` | `WifiOnly` | Prefetch of next episode's hosters | no |
| Default stream per anime | `pref_per_anime_default_stream` (+ `_data`) | true | Remembers the tapped video, matched by title/resolution fingerprint | no |
| Preferred audio languages | `pref_audio_lang` | "" | mpv `alang`, track pick (not for downloaded qualities) | no |
| mpv.conf | `pref_mpv_conf` | "" | Wins over every `setSafe…` option (not over `hwdec`) | no |
| Enable Cast | `pref_enable_cast` | false | Cast button | no |
| Anime: download only on Wi-Fi | `anime_download_only_wifi` | true | Download job constraint | yes |
| Anime: concurrent downloads | `anime_download_concurrent` | 1 (1..5) | Workers in `runQueue` | yes |
| Anime: delete after seen / keep bookmarked | `anime_delete_after_seen` / `anime_delete_keep_bookmarked` | false / true | Deletes on moving to the next episode | yes |
| Anime: download ahead | `anime_download_ahead` | 0 | Next N episodes queued while watching | yes |
| Anime: download new episodes | `anime_download_new` (+ category sets) | false | Library update queues new episodes | yes |
| Anime update interval / skip | `anime_update_interval` / `anime_update_skip` | -1 (with manga) / {completed} | `AnimeLibraryUpdateJob` | yes |
| Episode pictures / summaries default | `anime_episode_show_previews` / `anime_episode_show_summaries` | true / true | Default for new anime; per-anime flags `HIDE_PREVIEWS`/`HIDE_SUMMARIES` turn the card mode on | yes |
| (Change dialog) AniList entry for episode info | `anime_anilist_episodes_<animeId>` | "" | `none` / `<id>` / `auto:<id>` | yes |
| DNS over HTTPS (Advanced) | `doh_provider` | -1 (off) | Resolver that the IPv4-first sort wraps | no |

### Cross-feature impact map

- **Changing `EpisodeLoader.getVideos` / `HlsVariants.split`** affects every anime source in the player,
  downloaded-episode playback, self-healing and the episode options dialog (`ui/anime/EpisodeOptionsDialog.kt`). All of
  them get their videos through `loadHosterVideos`, and each episode start waits for `split`. The Browse preview player
  (`presentation/anime/AnimePreviewPlayer.kt`), the outside player (`AnimeScreenModel` ~line 768) and downloads use
  `AnimeVideoFetcher.fetchVideos` instead, so they get no per-quality split.
- **Changing `HlsVariants.isPlaylist` / `expand`** also changes **downloads** of anime episodes *and* videos in manga
  chapters (`ChapterVideoDownloads`), through `forDownload`.
- **Changing `HlsRelay` URL format** (`/p/<id>?u=…&x=list.m3u8`): `HlsVariants.isPlaylist` relies on "m3u8" being in
  it, and FFmpeg's extension check relies on `.m3u8`/`.ts` at the end (bc3bf897).
- **Changing split video titles:** `DefaultStreamSelector` saved defaults, `trySelfHeal`/`loadHosters(
  preferredVideoTitle)`, `QualityOrder` grouping (it strips heights and "auto" to group) and `capQualityIndex`'s
  title-height regex all read titles.
- **Reordering `HosterState.Ready.videoList`:** breaks selection indexes, `videoState`, default highlight and scroll
  in `QualitySheet`. Use `QualityOrder` (index map) for display only.
- **Changing `PlayerActivity.loadFile` forced options:** downloaded qualities need per-file `vid`, and the track pick in
  `onTracksLoaded` assumes `sid`/`aid` start as `no`.
- **Changing the `kmk-quality-track:` marker or `internalData` use:** `PlayerViewModel.audioTracks` and
  `onTracksLoaded` stop picking the right sound.
- **Changing `ffmpegDownload` `-map` args:** older files' layout (video N ↔ audio N) is what `localQualities` assumes.
- **Changing `NetworkHelper.clientBuilder` DNS:** affects all manga and anime networking, AniList, the relay and
  downloads. It doesn't affect mpv/ffmpeg.
- **Changing `Video` fields:** both `copy` overloads, the hidden ext-lib-16 overloads, `SerializableVideo`, and
  extension ABI (`source-api`, don't break).
- **Changing the AniList episode lookup:** the AniList page and global search share `AniListApi` caches and its
  query/rate limits.

### Build pitfalls seen in this area

- `logcat(LogPriority.WARN, e) { … }` with a throwable needs a receiver (`tachiyomi.core.common.util.system.logcat`
  is an extension). In a composable or top-level function, write `context.logcat(…)` / `service.logcat(…)`
  (84935397, 320144f9).
- `AnimatedVisibility` inside a Row/Column scope picked the wrong overload. Fully qualify it as
  `androidx.compose.animation.AnimatedVisibility` (84935397).
- Missing imports broke CI builds:
  - `tachiyomi.core.common.preference.getAndSet` (AnimeIncognito, 2d7b748d),
  - `rememberScreenModel` (AnimeLibraryTab, 76a264d6).

  The fork often writes fully-qualified names in KMK blocks (`eu.kanade.tachiyomi.ui.player.loader.HlsVariants…`)
  to avoid this.
- Composable reads inside a non-composable lambda builder fail. Hoist `stringResource`/`collectAsState` out of lazy
  list builders and helper lambdas (66ae32e2, 534da8e3).
- `Video.copy` overloads: adding a parameter to only one of them can cause overload ambiguity. Pass `videoPageUrl` to
  force the full overload.
- Kotlin `private var` used in `init` must be declared above `init` (`episodeInfoJob` in `AnimeScreenModel`). Otherwise
  it's null or reset at runtime, not a compile error.
- `7d617511`: a reference to `networkPreferences.verboseLogging()` in `AniyomiMPVView` didn't compile in the first
  player build. The mpv `msg-level` is now fixed (`all=status`).
