## Images, covers, preview panels and the reader

Paths are relative to `app/src/main/java/eu/kanade/` unless they start with another module. "Fork" means commits by `Claude`, `Dominican4Life` or `OmfgItsJesus` (from f87f01a5, 2026-10-07). The `// KMK` markers alone don't tell you whether code is upstream or fork.

Log tags named below (`cover`, `pages`, `Memory`, `reader`) are the tag column on the app's Logs screen (`tachiyomi/util/system/AppLog.kt`, which keeps the last 4000 lines across restarts). Code writes to them with `logcat.logcat("<tag>", LogPriority.X) { … }` or `AppLog.add(level, tag, msg)`.

---

### 1. The image loader (Coil 3) setup

**Where:** `tachiyomi/App.kt` › `newImageLoader()`. Coil BOM is 3.5.0 (`gradle/libs.versions.toml`, bundle `coil` = core, gif, compose, network-okhttp).

**What is registered, in order:**

| Component | Handles | Fork? |
|---|---|---|
| `OkHttpNetworkFetcherFactory(NetworkHelper.client)` | plain URLs | upstream |
| `TachiyomiImageDecoder.Factory` | AVIF / JXL / HEIF and SY's encrypted-CBZ covers | upstream |
| `BufferedSourceFetcher`, `MangaCoverFetcher.MangaCoverFactory`/`MangaFactory`, `MangaCoverKeyer`, `MangaKeyer` | covers | upstream, heavily changed by the fork |
| `PagePreviewKeyer`/`PagePreviewFetcher` | SY page previews (E-Hentai, NHentai, Lanraragi) | SY |
| `ReaderPageKeyer`/`ReaderPageFetcher` | reader Pages-panel tiles | fork (f87f01a5) |
| `SourcePageKeyer`/`SourcePageFetcher` | Browse/library preview page tiles from the site | fork (87733145) |
| `LocalPageKeyer`/`LocalPageFetcher` | preview tiles of downloaded chapters | fork (dfe45014) |
| `EpisodePreviewKeyer`/`EpisodePreviewFetcher` | anime episode pictures | fork (c1d306a5, r37) |

**Caches and limits (upstream values; don't assume otherwise):**
- **Disk cache** `cacheDir/image_cache`, `maxSizePercent(0.02)`. Coil clamps this to **at most 250 MB** (`DiskCache.Builder` defaults: min 10 MB, max 250 MB; checked in the Coil source). Everything below shares this one cache: covers outside the library, Browse preview page tiles (`source-page;…`), and JS `getPagePreviews` tiles.
- **Memory cache**: `MemoryCache.Builder().maxSizePercent(context)` (Coil's default share of the app's memory). `MemoryLog.trim()` clears it when the heap passes 75 % of the limit.
- `fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))` and `decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))` are upstream. They are why the tile timing log separates "waiting for a reader" (a fetcher slot) from "waiting for a decoder" (a decoder slot).
- `allowRgb565` is on only on low-RAM devices. `crossfade` is 300 ms × the animator scale.
- **Fork addition (r81, 51aa5c18):** `eventListenerFactory(ImageEvents)`. See §3.

**Invariant:** any new `Fetcher.Factory<T>` needs a matching `Keyer<T>`. Without one there is no memory-cache key, so the same image is fetched and decoded again on every recomposition.

---

### 2. Covers: `MangaCoverFetcher`

**Where:** `tachiyomi/data/coil/MangaCoverFetcher.kt`, `MangaCoverKeyer.kt`, `MangaCoverMetadata.kt`. Related: `tachiyomi/data/cache/CoverCache.kt`, `tachiyomi/data/cache/CoverFolders.kt` (download-folder copies, covered with downloads/backups).

**Key:** `MangaCoverKeyer` gives `"<url>;<lastModified>"`, or `"<mangaId>;<lastModified>"` when a custom cover exists. The same string is the memory-cache key and the disk-cache key, because `diskCacheKeyLazy` = `imageLoader.components.key(data, options)`. So the same title in Popular, Latest, a kept Browse tab and the wide-cover variant all map to **one key**.

**Flow of `fetch()`:**
1. A custom cover file exists → `fileLoader(customCoverFile)`.
2. URL type: `/…` or `file://` → file; `content…` → `UniFile` stream; `http…`/`Custom-…` → `httpLoader()`.
3. `httpLoader()`:
   - **Library title** (`isLibraryManga`), file in `CoverCache` → load it.
   - Coil disk snapshot exists → for a library title, move it into `CoverCache` (and `CoverFolders.save`); otherwise show the snapshot.
   - **Library title, not cached** → `LibraryCoverStore.obtain(coverFile.path) { fetchLibraryCover() }` runs in its own `SupervisorJob` scope with a **gate of 6** (`PARALLEL_COVERS`). It keeps running when the cover scrolls off and Coil cancels the request, so the next view loads from disk (r38, befea648). `fetchLibraryCover` tries the download folder (`CoverFolders.restoreInto`), then the network.
   - **Not in library** (r81) → `SharedCoverDownloads.obtain(diskCacheKey) { downloadIntoDiskCache() }`. This means **one in-flight download per key**, with a **gate of 8** (`PARALLEL_DOWNLOADS`), also in its own scope. Every waiter then reads the saved snapshot (`readFromDiskCache()`). `downloadIntoDiskCache()` returns early if a snapshot already exists, and returns quietly if `openEditor` gives null (someone else is writing).
   - Fallback (disk cache off or a cache policy disabled): the upstream network path. In the "can't write to disk" branch the body is now **read whole into an `okio.Buffer`**, and the colour reader gets `bytes.copy()`.
4. After a successful load, `setRatioAndColorsInScope()` launches `MangaCoverMetadata.setRatioAndColors` on `Dispatchers.IO`. Only when `pref_preload_library_color_key` (default true) is on; `onlyFavorite = !pref_theme_cover_based_key`. It decodes the cover with `BitmapFactory` at `inSampleSize = SUB_SAMPLE` to pick Palette colours and the aspect ratio.
5. Network errors go through `executeNetworkRequest()`. It writes `cover` WARN `Cover failed (<reason>): <url>`, calls `SourceStatusCheck.onCoverFailed(sourceId)`, and classifies bot checks / 429 with `BotCheckReporter` (notification, `SiteBlockedException`). Every thrown error is `ImageEvents.markLogged(e)`, so it isn't logged twice.
6. Anime covers: if `sourceLazy` (manga `HttpSource`) is null, `animeSourceLazy` looks the id up in `AnimeSourceManager`. An `AnimeHttpSource` or `JsAnimeSource` lends its `client` and `headers` (d915b4d8: JS sources' covers need the site Referer, the app UA and Cloudflare cookies).

**History: "covers don't load until I switch between Popular and Latest" (fixed in r81, 51aa5c18)**
- **Symptom:** opening a source, many covers stayed blank or broken. Switching Popular ↔ Latest a few times made more appear. Logs showed covers being downloaded again.
- **Cause:** Popular and Latest share titles, Browse keeps several tabs alive (`BrowseTabCache`), and a wide cover is requested a second time in its wide shape. So two or more requests for the **same key** ran at once.
  - The second request couldn't open a disk-cache editor (`openEditor` returns null while another edit is open). It fell through to "read from response".
  - In that branch the colour reader was handed **the same response stream** that Coil was decoding. The two readers consumed each other's bytes, so the decode failed (a broken cover) and nothing was saved.
  - Every tab switch retried, and some retries won.
- **Fix:** one download per key (`SharedCoverDownloads`). Everyone shows the saved snapshot. Where a raw response must still be used, it is buffered first and the colour reader gets its own copy.

**Invariants**
- The colour reader **must never read the same `BufferedSource` Coil decodes.** Use a fresh `snapshot.toImageSource().source()` (opens the file again), `bytes.copy()`, or a file path.
- `SharedCoverDownloads` and `LibraryCoverStore` must keep `inFlight.remove(key)` in `finally`. Without it a failed download sticks, and that cover never loads again until the app restarts.
- Don't make these jobs children of the request. They are deliberately in their own `SupervisorJob` scope so a cancelled request (scrolling) doesn't throw away a nearly finished download.
- Changing the keyer string (for example adding a size) splits Popular/Latest/wide back into separate downloads and brings the r81 bug back.

**Failure modes**
- The image disk cache is ≤ 250 MB and shared with preview page tiles (§5). A long preview session on big animated galleries evicts non-library covers, and they download again (not broken, just slower). Library covers are safe in `CoverCache`.
- `setRatioAndColorsInScope(…, saved.toImageSource().source())` reads the disk file while the snapshot may already be closed by Coil. That works on a normal filesystem, but if the entry were evicted at that moment the colour pick silently fails. Harmless; the cover still shows.
- Raising the gates (6/8) means more parallel requests to one site, which causes more 429s and bot-check notifications.

**How to check (Logs, tag `cover`)**
- Good: no `cover` lines while browsing.
- `Cover failed (HTTP 403): https://…` / `(SocketTimeoutException: …)`: the download failed. The source may be blocked, so look for a bot-check notification.
- `Cover downloaded but couldn't be shown (<Exception>: <msg>): <url>`: the bytes arrived but didn't decode. Usually the site sent HTML (Cloudflare page) or a format with no decoder. A burst of these on a whole page of covers = suspect a regression of the r81 fix.

---

### 3. `ImageEvents`: image-loader logs (r81)

**Where:** `tachiyomi/data/coil/ImageEvents.kt`, registered in `App.kt`.

`create(request)` picks a listener by `request.data`:
- `MangaCover` / `Manga` → `CoverListener`. `onError` logs `cover` WARN "Cover downloaded but couldn't be shown …". It skips cancellations, blank URLs, and errors already in `logged` (a weak set filled by `markLogged`).
- `ReaderPage`, `SourcePageThumbnail`, `LocalPageThumbnail` → `TileListener`. It times `onStart → fetchStart → fetchEnd → decodeStart → decodeEnd` and writes to tag `pages`:
  - `Page N still frame shown in X ms: waiting for a reader A ms, reading the file B ms, waiting for a decoder C ms, decoding D ms (BitmapFactoryDecoder)`: only when X ≥ 1500 ms (`SLOW_MS`) and the result didn't come from the memory cache.
  - `Page N playing shown in …`: always, for a tapped animated tile.
  - `Page N … failed after X ms (<Exception>): …` (WARN), and `Page N playing stopped before it showed …` when a playing request is cancelled.
- Everything else → `EventListener.NONE` (SY `PagePreview` and episode previews aren't timed).

**Invariant:** "playing" is detected by `request.memoryCacheKeyExtras["thumb_play"] == "true"`, which `AnimatedThumbnail` sets. Renaming that extra silently turns every playing tile into a "still frame" in the logs.

**Reading a slow tile line:**
- Big "waiting for a reader": all 8 fetcher slots are busy (too many tiles at once).
- Big "reading the file": network or a slow archive.
- Big "waiting for a decoder": the 3 decoder slots are busy.
- Big "decoding": a huge image, or an animated decoder starting.

---

### 4. Page tiles and animated pictures: `AnimatedThumbnail`

**Where:** `presentation/components/AnimatedThumbnail.kt` (fork). Used by the reader's Pages panel (`presentation/reader/ReaderSideSheets.kt` › `PageThumbnail`) and by the Browse/library page panel (`presentation/browse/components/BrowsePreviewPanels.kt` › `PageThumbnailBox`).

**What the user sees:**
- Every tile is a still picture.
- An animated page (GIF, animated WebP) shows a small round ▶ badge top-left.
- The **first tap** on an animated tile plays it inside the tile and frames it with a 3 dp primary border. The **second tap** opens the page in the reader.
- Only one tile plays at a time.
- While the moving version loads, the still frame stays visible with a small white loading circle (r81).

#### 4.1 How "is it animated?" is decided: only the first bytes are read

This is the part the owner asked to have explained.

`kindOf(source: BufferedSource)` in `AnimatedThumbnail.kt`:

```kotlin
val peek = source.peek()                 // a look-ahead: nothing is consumed
if (!peek.request(21)) return Kind.STILL // buffer at most 21 bytes
val head = peek.readByteArray(21)
// "GIF"                                        -> GIF
// "RIFF" ....  "WEBP" "VP8X" + flag 0x02 @20    -> ANIMATED_WEBP
// anything else                                -> STILL
```

- It runs inside `KindDecoderFactory.create()`, on the `SourceFetchResult` the fetcher already produced, **before any decoder is chosen**.
- `peek()` means the bytes are still there for the real decoder. Only the first 21 bytes are pulled into memory; the rest of the file is not read for this check.
- For WebP, `VP8X` plus the animation bit (byte 20, `0x02`) is required. This was added in r79. Before that, every WebP (most still pages on many sites) counted as animated and was decoded with the animated decoder.
- The answer is remembered in the process-wide map `kinds[thumbKeyOf(model)]`. When the still frame later comes from the memory cache, which skips the decoder, the ▶ badge is still correct. `onAnimated(Boolean)` reports it to the grid, which stores it in `animated[index]`.

The decoder is then chosen like this:
1. `TachiyomiImageDecoder.Factory().create(...)` first (AVIF/JXL/HEIF/encrypted cover; it also peeks the header, 32 bytes via `ImageUtil.findImageType`).
2. Only if `animate` (this tile is the playing one) **and** the kind is animated: `AnimatedImageDecoder` (Android 9+) or `GifDecoder`.
3. Otherwise `BitmapFactoryDecoder`. It decodes **only the first frame**, subsampled to tile size, as an ordinary bitmap.

**Why: the memory history (r64 → r79, 2026-10-25 … 10-27)**
- Up to r63, tiles were loaded through Coil "plainly" (r63 = the reference "good" behaviour the owner kept asking to return to).
- r64–r71 tried several ways to make animated previews smoother:
  - r64 "smoother animated previews", undone in r65.
  - r65: decode at tile size, animate only while on screen, two at a time.
  - r66: "flipbooks", frames captured once into `cacheDir/page_thumbs` by `PageFlipbooks.kt`.
  - r67: the APNG4Android decoder from kept copies (`PageThumbFiles.kt`, plus a gradle dependency).
  - r68: fallback to Android's decoder and a loading circle.
  - r69: hold-and-slide to play; APNG4Android dependency removed, "covers back to before r67".
  - r70–r71: back to r63 loading, with a GIF/WEBP label.
  - None of these survived. `AnimatedThumbnail` still deletes the old `page_thumbs` folder once per run (`oldCopiesCleared`).
- r77 (dc45de5e): back to r63 loading, plus the small play button (first tap plays, second opens). It also added `MemoryLog` lines every minute.
- **r79 (f2a7c7b5): the out-of-memory crash.** The Pages panel on a gallery of **381 animated pages ran out of memory**.
  - Cause: every visible tile was decoded with the animated decoder. An animated drawable keeps the **whole file** (all frames' source) in memory while shown, so dozens of tiles × several MB each filled the heap.
  - Fix: still tiles are decoded as small first-frame bitmaps. Only the one playing tile loads the animated file. The kind is found from the header bytes, so no tile has to be decoded "to find out" whether it moves.
  - `AnimatedTiles.count` (incremented in a `DisposableEffect` while a tile holds an `Animatable`) is printed in every `Memory` line as `animated tiles N`. It should be 0 or 1.

#### 4.2 Still vs playing requests

- The request is rebuilt by `remember(model, play)`.
- `.memoryCacheKeyExtra("thumb_play", play.toString())` gives the still and the playing version **different memory-cache keys**. Coil's memory key does not include the decoder, so without the extra a playing tile would get the still bitmap back from memory (it never moves). Worse, a still tile could get an animated drawable back (memory blow-up again).
- `.crossfade(!play)`: no crossfade when switching to playing.
- r81: `.placeholderMemoryCacheKey(stillKey)`. `stillKey` is captured from the still's `SuccessResult.memoryCacheKey`, so the tile isn't blank while the moving picture loads. It works only while the still is still in the memory cache; after a `Memory low` trim the tile can go blank under the circle for a moment.
- `LaunchedEffect(animatable, play)` starts/stops the drawable. `DisposableEffect` stops it on dispose.

**Invariants**
- Keep `kindOf` header-only (a `peek()`, a bounded `request(n)`). Never `readByteArray()` without a size, and never decode to find out if a page moves. That is exactly the r79 crash.
- Keep "only the playing tile uses the animated decoder". Any change that animates several tiles (autoplay on screen, hold-and-slide) brings the out-of-memory crash back on big galleries.
- Keep the `thumb_play` extra (it is also read by `ImageEvents`, §3).
- `thumbKeyOf()` key strings (`reader;…`, `source;…`, `local;…`) are separate from the Coil keyers. Changing either is fine, but a model type added to the grids needs a case in both `thumbKeyOf` and `ImageEvents.create`.
- `kinds` is an unbounded process-wide map (string → enum). It's tiny per entry, but don't store anything big in it.

**How to check:**
- Logs, `Memory` lines: `animated tiles 0` or `1`, and `images X/Y MB` steady while scrolling a big gallery.
- Tag `pages`: `Page 12 playing shown in 900 ms: … decoding 300 ms (AnimatedImageDecoder)` is good. `… (BitmapFactoryDecoder)` on a *playing* line means the header check said STILL. Look at the file's real type: an animated WebP without VP8X, or APNG, which is not detected and plays as still.

---

### 5. Preview panels on Browse and library cards

**Where:**
- UI: `presentation/browse/components/BrowsePreviewPanels.kt` (`BrowseChapterPanel` left, `BrowsePagePanel` right, `PageThumbnailBox`, `SourcePageThumbnailItem`, `AnimatedTap`).
- State: `tachiyomi/ui/browse/source/browse/BrowsePreviewScreenModel.kt`.
- Hosts: `ui/browse/source/browse/BrowseSourceScreen.kt` and `tachiyomi/ui/library/LibraryMangaPreview.kt` (the library version, added r67 ed849e93; `AnimeLibraryPreview.kt` is the anime one).
- Panel container and width: `SlidingSidePanel` / `sidePanelWidth()` in `presentation/reader/ReaderSideSheets.kt`. On phones (< 600 dp) it is 48 % of the width, so both panels fit; on tablets 27 %, clamped to 300–380 dp.

**What it does:** tapping a cover (first tap, when a panel is on) previews the title. The left panel shows details and chapters; the right panel shows a 3-column grid of the selected chapter's pages. A second tap on the cover opens the title. Panels are toggled by the Left Panel / Right Panel buttons:
- `pref_browse_preview_panels` (default `PREVIEW_PANELS_BOTH` = 1);
- `pref_library_preview_panels` (default `PREVIEW_PANELS_NONE` = 2);
- values LEFT = 0, BOTH = 1, NONE = 2, RIGHT = 3, in `domain/source/service/SourcePreferences.kt`.

**How the page grid gets its pages** (`BrowsePreviewScreenModel.selectChapter`, on `launchIO`; the previous `pagesJob` is cancelled). It tries these in order:
1. **Downloaded chapter** → `localPages(chapter)`. No network at all.
   - CBZ: the archive's entries are listed. Each entry is kept if `ImageUtil.isImage(name) { stream }` (extension first; only an unknown extension opens the entry and reads 32 header bytes). Video files (`ChapterVideoDownloads.isVideoFile`, name contains the video mark) are skipped. The result is sorted by natural order and becomes `LocalPageThumbnail(chapterId, index, archiveUri, entryName, isVideo)`.
   - Folder: `downloadManager.buildPageList(...)` → `LocalPageThumbnail(fileUri = page.uri)`.
   - `isVideo` comes from file names (`ChapterVideoDownloads.picturesWithVideo`), never from reading files.
2. **Source previews** → `previewModels()`. A JS source's `pagePreviews(url)` becomes `SourcePageThumbnail(httpSource, "preview:" + chapterUrl, page)`. Otherwise, for SY `PagePreviewSource`s on a one-chapter title, `GetPagePreviews` loads up to `MAX_PREVIEW_PAGES` preview pages, and the tiles are shown "plain" (no `AnimatedThumbnail`). `MemoryLog.event("previews started/done …")` lines bracket it.
3. **Full pages from the site**: `chapterCache.getPageListFromCache(chapter)`, else `httpSource.getPageList()` (then cached), re-indexed as `Page(index, url, imageUrl)` "like the reader does". Each tile is `SourcePageThumbnail(source, chapterUrl, page)`.

**Fetchers:**
- `LocalPageFetcher`: a single file is streamed. A CBZ entry is **copied whole into an `okio.Buffer`** so the archive can be closed at once; it opens a new `archiveReader` per tile.
- `SourcePageFetcher` (r80, f6681dee, the "preview disk cache"): the downloaded page is written into the **Coil disk cache** under `source-page;<sourceId>;<chapterUrl>;<pageIndex>`, and later loads open that snapshot. Before r80, tapping an animated tile to play it downloaded the whole file again (5–20 s for a big GIF), because the still had been decoded straight from the response.
- `SourcePageKeyer` returns the same string as the disk key.

**Tap handling (`AnimatedTap`):** `active` = the playing index, `animated[index]` = from `onAnimated`. `onTap(i)`: if `animated[i] == true` and `active != i`, play it; otherwise open the page in the reader. **Video tiles** (r82, aa5d1710) skip all of this and call `onOpenPage(chapter, index, true)` → `ReaderActivity.newIntent(..., page = index, playVideo = true)` (§7).

**Invariants**
- Page indexes in the grid are the list's own (0..n-1). They are passed to the reader as the `page` extra, and the reader treats it as an index into `currChapter.pages`. Don't use `page.number` or a source-provided index.
- Preview tiles use `"preview:" + chapterUrl` so they never share a disk-cache entry with the full page of the same index.
- `SourcePageKeyer.key()` and `SourcePageFetcher.diskCacheKey` build the same string in two places. Change both together.

**Predictable failure modes**
- The preview's page files live in Coil's ≤ 250 MB image cache, **not** in the reader's chapter cache. Opening the reader from a preview downloads the pages again. A big animated gallery in the preview also pushes other entries (non-library covers) out of that 250 MB.
- `LocalPageFetcher` buffers a whole CBZ entry per tile, up to 8 at once (fetcher slots). CBZs of 20–50 MB GIFs can spike memory. If a downloaded-gallery out-of-memory crash appears, look here first (stream from a temp file instead).
- Opening an archive per tile is slow for big CBZ/CBR files (shows as "reading the file" in tile logs).
- r65's "loaded two at a time" and r66's "loads only the pages shown" were undone. The panel now relies on `LazyVerticalGrid` composing only visible tiles and on Coil's 8 fetch slots.

---

### 6. The reader's side panels (Usagi-style)

**Where:** `presentation/reader/ReaderSideSheets.kt` (`ReaderChapterListPanel`, `ReaderPageGridPanel`, `PageGridContent`, `PageThumbnail`, `VideoPlayButton`, `SlidingSidePanel`). Hosted in `tachiyomi/ui/reader/ReaderActivity.kt` (compose overlay, around line 370). State: `ReaderViewModel` `chapterListPanelOpen`, `pageGridPanelOpen` (`openPageGridPanel()`/`closePageGridPanel()`).

**History:**
- f87f01a5 (first fork commit): chapter menu as a right-side sheet with a page grid.
- e62ba84b: narrower, 3 columns like Usagi.
- e590c6d4: the chapter list slides from the left, and the page grid gets its own bottom-bar button (`onClickPageGrid`).
- c75fb9c7: panels stay open and can be open together.

**Page grid flow:**
- `pages` come from `ReaderChapter.State.Loaded`. The grid is keyed by `currentChapter.chapter.id`, so it starts fresh per chapter, and it auto-scrolls to keep `currentPage` in view.
- Each `PageThumbnail` calls `chapter.pageLoader.loadPage(page)` in a `LaunchedEffect(page)`. For an online chapter this queues the download in `HttpPageLoader` (priority 1, plus `preloadNextPages(preloadSize)`), and leaving the screen removes it from the queue.
- When `status == Ready` the tile shows `AnimatedThumbnail(model = page)`. `ReaderPageFetcher` reads `page.stream()` (chapter cache file / download / archive entry). The keyer is `reader-page;<chapterId>;<index>;<imageUrl>`.
- Tap: a video page → `ReaderVideos.play(context, page)` straight away (r82); an animated page → first tap plays, second opens; else `onClickPage(page.index)` → `moveToPageIndex`. Reading on to another page clears `active`.
- r81 timing: `LaunchedEffect(page, status)` logs `pages` INFO `Page N downloaded for the Pages panel in X s` when a page took ≥ 1.5 s to become Ready.

**`HttpPageLoader` re-download log (r81):** in `loadPage()`, if a page is `Ready` but `!chapterCache.isImageInCache(imageUrl)`, it logs `pages` INFO `Page N: its file is no longer in the reader cache, downloading it again` and re-queues it.

**History: "the animated gallery takes so long to play" (r80 → r81)**
- The owner sent a log and video of an animated gallery that was slow to play.
- **Wrong first diagnosis:** that the reader cache was the default 75 MB, so pages were being evicted and re-downloaded. The owner showed the setting at **5 GB** (`eh_cache_size` = "5000"). The default really is "75" (`ReaderPreferences.cacheSize()`), but that wasn't this user's case.
- Instead of guessing again, r81 added measurement: the tile timings (§3), the Pages-panel download time, and the re-download line.
- Lesson: **check the owner's actual setting before blaming a default**. With 5 GB, a `Page N: its file is no longer in the reader cache` line should practically never appear. If it does, something else is clearing `chapter_disk_cache`.

**Reader cache facts:** `tachiyomi/data/cache/ChapterCache.kt`, a `DiskLruCache` in `cacheDir/chapter_disk_cache`, size = `eh_cache_size` MB. Changing the setting re-opens the cache. Choices are 50 MB … 5 GB (SY list, not fork). `MemoryLog` lines include the reader cache size.

**Invariants**
- The grid keys items `"page-${page.index}"`, and `animated`/`active` are keyed by the grid index. Those are the same as `page.index` only because the reader re-indexes pages itself. Keep it that way.
- `PageThumbnail` must request the page through the loader (`loadPage`). Reading `page.stream` before `Ready` throws "Page N is not loaded yet" (`ReaderPageFetcher`).

**Failure modes:** opening the grid on a 300-page online chapter queues many downloads through the same `eh_reader_threads` (default 2) workers the reader uses. The page being read can wait behind tiles. The `pages` timing lines show it.

---

### 7. Videos inside manga chapters: reader hand-off

**Where:** `tachiyomi/ui/reader/ReaderActivity.kt` (`newIntent`, `EXTRA_PLAY_VIDEO = "play_video"`, `handOffLoneVideo`, `setChapters`/`showChapters`); `tachiyomi/jssource/ReaderVideos.kt` (`isVideo(pageUrl)` = the URL starts with the video mark; `play()`); `ui/reader/viewer/ReaderVideoButton.kt` (▶ on video pages in pager/webtoon holders).

**Flow:**
- `setChapters()` first calls `handOffLoneVideo(viewerChapters)`, once per activity (`videoChecked`).
- The page to play is either the tapped page (`play_video` extra true + `page` index, from a grid; r82) or the chapter's **single** page (645874ba / afaf348b, 2026-10-11).
- If it is a video: `handingOffVideo = true`, so the reader draws nothing, and `ReaderVideos.play()` runs.
- On success: `finish()` with no animation, so Back returns to the title.
- On failure: `AppLog` `reader` E `Couldn't start the video: …`, a toast, then `showChapters()` shows the page with its play button.
- b3e647b1 fixed a crash when a lone video opened on a tablet in landscape.

**Invariant:** `handOffLoneVideo` must run **before** `showChapters()` draws the viewer, so no frame of the reader flashes. That was afaf348b: "hand a lone video to the player before the reader draws it".

**Check:** the `reader` tag shows `Couldn't start the video` on failure. A success has no reader line; the player's logs follow.

---

### 8. How local and device files are read (and where headers are sniffed)

This is a map of every place in this area that **looks at a file's first bytes instead of loading it**, and what decides the type elsewhere.

| Where | What is read | Purpose | Fork? |
|---|---|---|---|
| `AnimatedThumbnail.kt` › `kindOf` | 21 bytes via `peek()` | GIF / animated WebP / still → which decoder, ▶ badge | fork (r79) |
| `core/common/.../ImageUtil.kt` › `getImageType` | 32 bytes (`mark`/`reset` if supported) → `ImageDecoder.findType` | `findImageType`, `isAnimatedAndSupported` (pager/webtoon page holders pick animated vs SubsamplingScaleImageView), `isImage(name){stream}` when the extension is unknown | upstream |
| `TachiyomiImageDecoder.Factory.isApplicable` | peek of the header | route AVIF/JXL/HEIF/encrypted covers to the native decoder | upstream |
| `ImageUtil.extractImageOptions` | `inJustDecodeBounds` | size of a page without decoding pixels (wide/tall checks) | upstream |
| `ArchivePageLoader`, `DirectoryPageLoader`, `BrowsePreviewScreenModel.localPages` | `ImageUtil.isImage(name) { stream }` | which entries/files are pages; the extension decides first, header bytes only for odd names | upstream / fork caller |
| HLS relay (player area) | first bytes of each segment | strips fake PNG/JPEG headers some hosts prepend (40bf798d) | fork, see the player section |

**What does *not* sniff bytes:**
- **Device files** (`tachiyomi/devicefiles/DeviceFiles.kt`, `DeviceFilesSources.kt`, `ui/devicefiles/**`, from 1836136a): `FileTypes.kindOf(name)` uses the **extension only**. Category lists come from MediaStore queries (`MEDIA_TYPE`, plus the MTP `format` column to skip folders, 44603691). No file is opened to list or classify it.
- Device-file thumbnails (`DeviceFilesComponents.kt` › `FileThumbnail`):
  - images go through Coil (`AsyncImage(model = file.file)`), so they are subsampled to the tile size;
  - videos use `ThumbnailUtils.createVideoThumbnail(384×384)`;
  - APKs use the package icon;
  - audio uses the embedded art;
  - the last three are kept in a 24 MB `LruCache` (`ThumbnailCache`), and a set of "missing" keys avoids retrying.
  - GIFs in Device files never animate, because the app's `ImageLoader` registers no animated decoder globally (only `AnimatedThumbnail` adds one, per request).
- Reading a device comic: `ChapterLoader.deviceFilesLoader(path)` picks `DirectoryPageLoader` / `EpubPageLoader` / `ArchivePageLoader` by folder or extension. Archive pages follow `archive_reader_mode`:
  - default `LOAD_FROM_FILE` = 0, which streams per page;
  - `LOAD_INTO_MEMORY` = 1 reads **every entry's bytes into memory**;
  - `CACHE_TO_DISK` = 2 extracts to `externalCacheDir/reader_<hash>`.
- Downloaded-chapter video detection uses names only (`ChapterVideoDownloads.isVideoFile`, `picturesWithVideo`).

**Why headers and not decoding:** the out-of-memory crash of r79 (§4.1). The app had been decoding animated files fully just to show them, or to find out whether they move. A header check costs a few bytes and no bitmap. The same reasoning is why the still frame is a subsampled `BitmapFactory` decode and why only one tile may hold an animated drawable.

**Don't break:**
- Never replace a header check with a full decode or a `readBytes()` "to be sure".
- Don't switch the default archive mode to `LOAD_INTO_MEMORY`.
- If a new format needs detecting (APNG, animated AVIF), extend `kindOf` with a bounded `peek().request(n)`.

---

### 9. Long-strip (webtoon) and pager decoding

The fork did not change the image decoding of the viewers, as far as git authorship shows: `ui/reader/viewer/**` fork commits only add `ReaderVideoButton` (1adf5817, 5fb7b0f4).
- `WebtoonPageHolder`/`PagerPageHolder` call `ImageUtil.isAnimatedAndSupported(source)` (header peek).
- Animated pages go to an `AppCompatImageView` with Coil.
- Stills go to `SubsamplingScaleImageView` (tiled decoding; `SCALE_TYPE_FIT_WIDTH` in webtoon).
- Webtoon settings are upstream: `crop_borders_webtoon`, `webtoon_side_padding`, `pref_webtoon_scale_type_key`, `pref_webtoon_smart_scale_long_strip_gap`, `webtoon_disable_zoom_out`, the dual-page split/rotate keys.

I found no fork history or transcript discussion of a "long-strip decoder setting". If one was meant, it isn't in the current code.

---

### Settings in this area

| Setting (as shown) | Key | Default | What it changes | Fork-added? |
|---|---|---|---|---|
| Reader cache size (Reader › Page downloading) | `eh_cache_size` | `"75"` (MB); owner uses 5000 | `ChapterCache` size; evictions → "no longer in the reader cache" re-downloads | no (SY) |
| Page Preload amount | `eh_preload_size` | 10 | pages queued after each requested page, also from Pages-panel tiles | no (SY) |
| Download threads (reader) | `eh_reader_threads` | 2 | `HttpPageLoader` workers shared by reader and Pages panel | no (SY) |
| Aggressively load pages | `eh_aggressive_page_loading` | false | queues every page on chapter open | no (SY) |
| Instant retry | `eh_reader_instant_retry` | true | retry boost for failed pages | no (SY) |
| Archive reader mode | `archive_reader_mode` | 0 (from file) | how CBZ/CBR pages are read; mode 1 = all in memory | no (SY) |
| Preload library's cover color (Advanced) | `pref_preload_library_color_key` | true | runs the cover colour reader on every cover load | no (KMK upstream) |
| Theme based on cover | `pref_theme_cover_based_key` | true | colour reader also computes vibrant colour for non-library covers | no (KMK upstream) |
| Save covers in download folders (Downloads) | `save_covers_in_folders` | false | library covers copied to and restored from the title's download folder (`CoverFolders`) | yes |
| Browse preview panels (Left/Right Panel buttons) | `pref_browse_preview_panels` | 1 (both) | first cover tap in Browse opens panels | yes |
| Library preview panels | `pref_library_preview_panels` | 2 (none) | same for the library | yes |
| Device files: play videos in app / read comics in app | `device_files_play_in_app` / `device_files_read_in_app` | true / true | Device files open in the app's player/reader | yes |
| Clear page preview cache (Data) | action | – | clears SY's page-preview cache (not Coil's `image_cache`) | no (SY) |

### Cross-feature impact map

- **Changing `MangaCoverKeyer`** affects Browse Popular/Latest, kept Browse tabs, wide covers and the library, because one key = one shared download (r81). A size or extra in the key splits them and brings broken or duplicate cover downloads back.
- **Changing `MangaCoverMetadata` input handling** affects every cover. The colour reader runs on almost every load and must get its own bytes.
- **Raising the Coil disk-cache limit or filling it** affects non-library covers and Browse preview tiles (`source-page;…`), and JS preview tiles. They all share `image_cache` (≤ 250 MB).
- **Changing `AnimatedThumbnail`** affects both the reader Pages panel and the Browse/library page panels (one component), plus `MemoryLog`'s `animated tiles` counter and `ImageEvents` "playing" detection.
- **Changing `HttpPageLoader` queueing or `eh_reader_threads`** affects reading itself and the Pages panel (same queue).
- **Changing the `page` extra meaning in `ReaderActivity.newIntent`** affects SY page previews on the manga screen, the Browse/library page panels and the video hand-off (`handOffLoneVideo` reads `pages[page]`).
- **Changing `ReaderVideos.isVideo`** affects the ▶ badges in all grids, the lone-video hand-off, tap behaviour in the Pages panel, and downloads (`ChapterVideoDownloads`).
- **`MemoryLog.trim()` (clears Coil memory cache)** affects `placeholderMemoryCacheKey(stillKey)`: the tapped tile may go blank under the circle until the animation loads.
- **`App.kt` fetcher/decoder parallelism (8/3)** affects all images. The tile logs' "waiting for a reader/decoder" numbers depend on them.

### Build pitfalls seen in this area

- **r71 (c6c0bc04), "fix thumbnail decoder key type":** inside `KindDecoderFactory`, `if (key != null) runCatching { kinds[key] = … }` didn't compile (smart cast of a nullable property inside the lambda). Use `key?.let { k -> … }`.
- **r77 (57ff3b63):**
  - `out += result.pagePreviews` on a `MutableList<Any>` is ambiguous (add one element vs add all). Use `out.addAll(...)`.
  - A top-level `private val oldCopiesCleared = AtomicBoolean(false)` was lost in a rewrite. When rewriting `AnimatedThumbnail.kt`, keep its top-level helpers (`oldCopiesCleared`, `AnimatedTiles`, `kinds`, `Kind`, `kindOf`, `KindDecoderFactory`).
- **r81:** a missing Compose layout import (`fillMaxSize`, in `AutoCompleteItem.kt`, outside this area). Compose layout modifiers each need their own import.
- **84935397, "Fix preview player compile"** (anime preview player, 2026-10-08): after editing preview panels, compile-check the anime counterpart (`AnimePreviewPlayer.kt`, `AnimeLibraryPreview.kt`). It shares `SlidingSidePanel` and the panel-button helpers.
- **r67/r69:** adding and removing a gradle image-decoder dependency (APNG4Android) cost two releases. Coil's own decoders plus the header check cover GIF and animated WebP.
- Compose rule used here: `remember` must not be called conditionally (the fork wraps requests in an unconditional `remember(model, play)`), and lines must stay within ktlint length.
