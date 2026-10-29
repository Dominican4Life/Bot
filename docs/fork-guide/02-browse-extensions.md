## Browse, sources, extensions, repositories and installers

Paths are relative to `app/src/main/java/eu/kanade/` unless they start with `data/`, `domain/`, `source-api/` or `i18n-kmk/`.
"Fork" means a commit by Claude / Dominican4Life / OmfgItsJesus (from f87f01a5, 2026-10-07). Version codes: the Logs "app start" line shows `1.14.1-108xx`; r84 is 10873.

Preview panels on browse cards (chapter panel, page panel, animated tiles) are covered in the preview-panels section. JS / JSON source internals are covered in `03-js-sources.md`. This section only covers how they appear in Browse and Extensions.

---

### Browse tabs (Sources, Feed, Extensions)

**What / where.** `tachiyomi/ui/browse/BrowseTab.kt`. The tabs are Sources, Feed and Extensions (commit 1492a86a, r29 era). Migrate moved to the Library menu. Two settings change the order:
- `hide_latest_tab` (Hide feed tab) gives Sources and Extensions only.
- `latest_tab_position` (Feed in front) gives Feed, Sources, Extensions.

The Sources tab has a manga/anime toggle in its toolbar (`withAnimeSwitch`, pref `pref_browse_show_anime`, default false). When anime is on, the global-search action and a reselect of the Browse tab open `AnimeGlobalSearchScreen` instead of `GlobalSearchScreen`.

**How it works.**
- `BrowseTab.Content()` creates these screen models: `ExtensionsScreenModel` (the old upstream one, still hoisted for `TabbedScreen`'s search box), `FeedScreenModel`, `BulkFavoriteScreenModel`, `AnimeBrowseScreenModel`, `UnifiedExtensionsScreenModel`, and two `ExtensionStoresScreenModel`s with tags `manga_stores` and `anime_stores`.
- So opening Browse at all, even on the Sources tab, starts several repo reads:
  - `ExtensionsScreenModel.init` reads the manga repos.
  - `UnifiedExtensionsScreenModel.init` calls `refresh()`, which reads manga and anime.
  - `AnimeBrowseScreenModel` reads anime.
- These reads are folded together by `findLock` (see Repositories).
- `BrowseTab.showExtension()` jumps to page `if (hideFeedTab) 1 else 2`. This hard-codes Extensions as the last tab.
- One-time fix: `kmk_feed_tab_restored` (bool). On the first run after 4735057d it forces `hideFeedTab = false` once, for users who had lost the Feed tab.

**Invariants.**
- Extensions must stay the last tab, or `showExtension()` (called from `HomeScreen` for `Tab.Browse(toExtensions = true)`, e.g. the extension-update notification) lands on the wrong page.
- The old `ExtensionsScreenModel` is still created and still triggers a manga repo read, even though its UI (`ExtensionsTab.kt` / `presentation/browse/ExtensionsScreen.kt`) is no longer shown in Browse. Removing it also removes `extensionsState.searchQuery` from `TabbedScreen`. The unified tab sets `searchEnabled = false`, so that is harmless, but it must be done deliberately.

**Failure modes.**
- Adding a tab before Extensions breaks `showExtension()`.
- Creating more screen models in `BrowseTab` adds more startup network reads. Each one is cheap only because of `findLock`.

---

### Sources tab (manga) and the anime sources pane

**What / where.**
- Manga: `tachiyomi/ui/browse/source/SourcesTab.kt` and `presentation/browse/SourcesScreen.kt`.
- Anime: `presentation/browse/anime/AnimeSourcesPane` (from `BrowseTab`).
- Sources show as tiles (3006799e). They have corner badges for Mihon / JS / JSON (r63), a search box with the filter button next to it, a "Device files" tile after Local source (1836136a), and a per-source sheet on long press (de81a0de: details, on/off, pin, WebView, cookies and site data).
- JS / JSON sources join the list under their own kind badge (ab0aaa0a).

**How a source opens.**
- If `use_new_source_navigation` (SY "Replace latest button", default false) is on, the source opens its Feed screen (`SourceFeedScreen` / `AnimeSourceFeedScreen`).
- Otherwise it opens `BrowseSourceScreen(id, QUERY_POPULAR)` / `AnimeSourceScreen`.
- The same rule is repeated in `UnifiedExtensionsTab.onSourceClick` and in `BrowseTab.animeSources`. Change all three together.

---

### Browsing a source (BrowseSourceScreen, AnimeSourceScreen)

**What / where.**
- Manga: `tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt` (UI), `BrowseSourceScreenModel.kt`, `BrowseTabCache.kt`, `SourceFilterDialog.kt`.
- Anime: `tachiyomi/ui/anime/source/AnimeSourceScreen(Model).kt`.
- The tab row is: Popular, Latest (when `supportsLatest`), a "Filter" tab once a filter or typed search exists, a JS source's catalog tabs, then saved searches. You can swipe left/right over the covers to move along that row (96 dp threshold). Swiping is off while the filter or settings panel is open.

**How it works.**
- `mangaPagerFlowFlow` maps `(listing, reloadToken)` to a `Pager(pageSize = 25)`.
- `tabCacheKey(listing)` is `"<ScreenModel qualifiedName>:<sourceId>:<popular|latest|filter|catalog:<id>|saved:<id>>"`.
- When the key is non-null, the pager comes from `BrowseTabCache.pager(key)`. That is a `cachedIn` flow in its own `SupervisorJob` scope, kept until the app closes.
- `MAX_TABS = 10`: the least recently used tabs are dropped. Before r74 every tab was kept forever (33fe09d2, "Browse tabs no longer kept forever").
- `BrowseTabCache.trim(keep)` is called when memory is low.
- Scroll positions are saved per key (`saveScroll`).
- Tapping the selected tab calls `reloadListing()`, which drops the key and bumps `reloadToken`.
- The Filter tab (r49, 2710c2ee):
  - `search()` builds `Listing.Search(tabKey = "filter")` and stores it in `BrowseTabCache.filterTabs["<class>:<sourceId>"]`, so it survives leaving the screen.
  - Clear in the filter panel calls `clearFilterTab()`, which resets the filters, drops the tab, forgets it, and goes back to Popular if the Filter tab was showing.
  - A saved search opened from the row uses `tabKey = "saved:<id>"`.
- From Popular or Latest, a typed search uses whatever is selected in the Filter panel. This is deliberate (KMK comment in `search()`).

**Invariants.**
- The cache key includes the screen-model class name. That is why migration and other screens that extend `BrowseSourceScreenModel` never share Browse's lists. Keep the class name in the key.
- Only titles (name, url, cover url) are cached, not bitmaps.
- `Listing.Search.filters` holds the live `FilterList` object. Saved Filter tabs hold that same object, so mutating filters elsewhere changes the kept tab. Not verified in every path; be careful.

**Failure modes.**
- Raising `MAX_TABS` brings back the memory growth of r73 and earlier. The MemoryLog lines in Logs count kept tabs (`BrowseTabCache.size`).
- A source whose `getFilterList()` changes shape breaks saved searches. See Saved searches.

---

### Filters and tag boxes (AutoCompleteItem, JsTags)

**What / where.**
- `tachiyomi/ui/browse/source/browse/AutoCompleteItem.kt` is SY's tag box, used by E-Hentai's `AutoCompleteTags`, JS `TagFilter` (`jssource/JsMangaSource.kt:362`, also `JsAnimeSource`) and anime via `presentation/anime/AnimeFilterBridge.kt`.
- `jssource/JsTags.kt` provides tag lists from a JS source's `getTags()`.
- `SourceFilterDialog.kt:223` feeds a `Filter.AutoComplete` into `AutoCompleteItem`.

**Behaviour (r80, 1d0c9b48; r81, 51aa5c18).**
- **Tap the box**: opens the dropdown list of every tag (no keyboard). **Hold the box**, or tap a box that has no list: opens the typing bar.
- **In the list, tap a tag**: include it (blue `+`, `0xFF3DA5F4`). **Hold a tag**: leave it out (red `−`, `0xFFE85D75`). Tapping the same mark again clears it.
- A left-out tag is stored as `excludeMark + tag`. `excludeMark` is `"-"` if `"-"` is in `validPrefixes`, otherwise the first prefix. If the filter has **no** `validPrefixes`, holding includes instead. The JS source then receives the strings as they are, with `-tag` for excluded tags, in `search()` (`JsMangaSource.filterValues`: `JsonArray(filter.state)`).
- Sort order is `tagOrder`: tags starting with a digit, then letters, then the rest, case-insensitive. Display uses `tagLabel()`, which capitalises each word. **This is display only**: the source always gets the raw tag. Don't send `tagLabel()` output to sources.
- The typing bar (r81) is a `Dialog` with `decorFitsSystemWindows = false`. Its own window uses `SOFT_INPUT_ADJUST_RESIZE` with `.imePadding()`. While it is open, the activity window is switched to `SOFT_INPUT_ADJUST_NOTHING` (and restored in `onDispose`), so the filter sheet behind doesn't jump.

**Tag lists (JsTags).**
- `getFilterList()` calls `manager.tags.ensure(info)` when any group is AutoComplete.
- `ensure` runs `load(force = false)` on `Dispatchers.IO` in a **separate** `JsRuntime`, which is closed afterwards.
- The result is saved to `filesDir/js_tags/<key>.json` (`Cached(at, lists)`).
- Lists are read **once**. Only "Refresh tag list" in the source's settings (`addRefreshButton`, pref key `js_refresh_tags`, not persistent) reads them again. Before r80 they were re-read after a week (43973abb).
- After a failure there is no retry for 1 h (`RETRY_AFTER_FAILURE_MS`).
- Limit: 100 000 tags per list (`MAX_PER_LIST`).
- `getTags()` returns `{ listName: [...] }`, or a plain array, which becomes the shared list `"*"`. Each box suggests `extra` (its own options), then the lists it names (`group.tagLists`), then `"*"`.
- `suggestions()` returns a `LiveList` that re-reads `cache[key]` on access, so a filter built before the download finished can still fill in later.

**History / warnings.**
- 85fa314c: opening a tag box crashed with `IllegalStateException: intrinsic measurements of SubcomposeLayout`. `ExposedDropdownMenu` asks for intrinsic width, which a `LazyColumn` can't give. Fix: `TagList` has a **fixed width** (anchor width, min 200 dp) **and fixed height** (`min(6, n) * 48 dp`). Don't remove either.
- bab9ab9e: the whole list is shown (lazy) instead of the first 100.
- a2741c97: r81 build failed on a missing `fillMaxSize` import in this file.
- `items(tags, key = { it })`: keys must be unique. Both lists go through `.distinct()` (`allValues`, `matches`). Dropping the `distinct()` crashes with duplicate tags.

**Invariants.**
- The dropdown must keep a fixed width and height.
- Tag keys must stay distinct.
- The exclude mark must stay `"-"` when the filter allows it. Sources and E-Hentai parse `-tag`.
- `getTags` must run in its own runtime and close it. r79 (625be48d) added "JS sources let go of their parsed pages when their copy shuts down (tag downloads)" after memory growth.

**Failure modes.**
- `SourceFilterDialog` passes `filter.values.toImmutableList()`. That copies the `LiveList` at composition time, so a box only shows newly downloaded tags after the sheet recomposes (a pick, or reopening). The JsTags kdoc claims more than the dialog delivers. Not verified on a device.
- Copying a 100 000-tag list on each recomposition costs time on big lists.

**How to check (Logs).**
- `I js <Source>: got N tags in M lists` means success. `W js <Source>: getTags failed: …` means failure.
- MemoryLog events "`<Source>`: tag download started / done (N tags) / stopped".

---

### Saved searches (FilterSerializer, GetExhSavedSearch)

**Where.**
- `source-api/src/commonMain/kotlin/xyz/nulldev/ts/api/http/serializer/FilterSerializer.kt`
- `domain/source/interactor/GetExhSavedSearch.kt`
- Anime saved searches: `tachiyomi/ui/anime/source/AnimeSourceScreenModel.kt` (fd1f8656, r50s). These use the same `saved_search` table and `FilterSerializer` through `toMangaFilterList()`.

**How it works.**
- Saved filters are a JSON array matched to the source's current `getFilterList()` **by position** (`zip`).
- r81 change: `deserialize(filters, json, label)` counts the entries that don't fit and returns the count. It logs **one** warning per saved set per run (dedup via `reported`, keyed by label plus the JSON hash), not an error per filter.

**Invariants / failure modes.**
- Positional matching means any change to the order or number of a source's filters shifts every saved value.
- For JS sources, `getFilterList()` prepends `TabHolder`, or `CatalogFilter` + `Header` + `Separator`, depending on whether **all** catalogs are tabs. Changing a JS source's catalogs from tabs to non-tabs (or back) invalidates its saved searches.
- Two sources with the same id (a JS source that took an APK's id, c2f5a6dd) share saved searches. The warning text names this case.

**How to check.**
- Good: nothing in Logs.
- Bad: `W FilterSerializer Saved search "<name>": X of Y saved filters don't fit this source's filters now (...); left at their defaults. First: <Exception>`.

---

### Feed

**Where.**
- `tachiyomi/ui/browse/feed/FeedTab.kt`, `FeedScreenModel.kt` (SY's saved-search feed)
- `AnimeFeedScreenModel.kt`, `AnimeFeedComponents.kt`
- `TagMixFeed.kt`, `TagMixEditScreen.kt`

**What.**
- The + dialog has Manga | Anime tabs.
- Anime rows are a source's Latest or Popular. They are stored in pref `feed_anime_rows` as `"sourceId:latest;sourceId:popular"` and shown after the manga rows (1b2fb9a5).
- A "Tag mix" (a048d24a) is one row built from several sources for some tags:
  - A source with a matching CheckBox, include box or AutoComplete filter gets that filter ticked (`applyMangaTags`, case-insensitive name match).
  - Other sources are searched for the words.
  - The results are mixed.
  - Tag mixes are stored as a JSON list in pref `feed_tag_mixes`.
- Feeds of turned-off sources are hidden until the source is turned on again.
- Suggestions were removed in a048d24a. Their old background job is cancelled on start.

---

### Global search

**Where.**
- `tachiyomi/ui/browse/source/globalsearch/SearchScreenModel.kt`, `GlobalSearchScreen(Model).kt`
- Anime: `tachiyomi/ui/anime/AnimeGlobalSearchScreen.kt`, `ui/anime/anilist/GlobalSearchModeRow.kt` (Pinned / All / Results / AniList row, r59–r61; see the anime section).

**Fork change (r62, 3a0e589a).**
- Finished results are kept per (query, sourceId) in a file-level `resultCache` (a `ConcurrentHashMap`) until the app closes, when `keepsResults` is true.
- Holding Pinned or All calls `refreshAll()`, which drops the query's cache. Holding a source name calls `refreshSource()`.

**Failure mode.** `resultCache` has **no size limit**. Each distinct query searched in a session stays in memory. If memory complaints come back after many global searches, look here.

---

### Unified Extensions screen (Sources / Repositories / Packages)

**What / where.**
- UI: `presentation/browse/UnifiedExtensionsScreen.kt`
- Wiring and dialogs: `tachiyomi/ui/browse/extension/unified/UnifiedExtensionsTab.kt`
- State: `UnifiedExtensionsScreenModel.kt`
- Models: `UnifiedExtensionsModels.kt`
- Source tests: `SourceTester.kt`
- Added in 1f2ede48 ("Kototoro-style"). Reduced to Mihon + Aniyomi in 80295be4, after the JAR (Kotatsu/Tsuki) and Cloudstream work was reverted (6ec8fecc, aab25f17, 443edf52). Some kdoc still mentions JAR/Cloudstream; ignore it.

**Layout, top to bottom** (`UnifiedExtensionsContent`):
1. `SecondaryTabRow` with "Sources (n)", "Repositories (n)", "Packages (n)". The counts are the **filtered** list sizes.
2. Search box (`SourcesSearchBox`) and refresh button (a spinner while `isRefreshing`).
3. `FilterRows`:
   - A repository chip row ("All repositories" plus one chip per repo). Shown on Sources and Packages when there is more than one repo.
   - On Packages only, a status row: All / Updates / Installed / Not installed.
   - A kind row on every tab: All sources, Mihon, Aniyomi, JS, JSON. Each chip has a colour dot from `presentation/browse/components/sourceKindColor`.
4. `LinearProgressIndicator` while `isLoading`.
5. `HorizontalPager` (`beyondViewportPageCount = 1`) with the three lists.

**Sources tab.**
- Lists all sources of installed packages plus JS/JSON sources.
- Action chips: New JS source (only with the JS or JSON chip on), Enable all, Disable all, Select, Test sources, Failed only, Clear results.
- Each row: icon, name, tags (kind, Manga/Anime for JS, language, NSFW, test badge, repo name), pin, edit (JS), settings, delete (JS), switch.
- Long press starts selection mode, with chips for picked count, Pick all shown, Enable/Disable all, Pin/Unpin, Test, Delete JS.

**Repositories tab.**
- "Add repository" chip, then cards with: name, kind tag, address, "N packages · M installed" (plus "· K hidden by Show NSFW"), a "No signing fingerprint …" line, "Updated <relative>" or "Last check failed: …", refresh, delete, switch.
- Tap opens the Packages tab filtered to that repo. Long press deletes.

**Packages tab.**
- An update banner ("N extension updates available" plus Update all) when `updateCount > 0`.
- Each row shows kind and state tags, a Sideloaded tag, version · repo · active/total sources, language, installed version, NSFW, "<repo> version installed", source names, and an install progress bar.
- Buttons: Install + Sideload (available), Update, Trust, Cancel (installing), Remove + Details (installed).

**How the lists are built** (`UnifiedExtensionsScreenModel.build`, on IO):
- One `combine` of the manga flows, anime flows, a prefs flow, the `steps` map and `ExtensionStoreStatus.entries`.
  - Manga flows: installed, untrusted, available, stores.
  - Prefs flow: disabled, pinned, anime disabled and pinned, languages, disabled repos, JS `apkOffFlow`.
- A failed rebuild is logged and skipped: `E extensions Rebuilding the extension list failed: …`.
- Package keys:
  - installed and untrusted: `"<KIND>:<pkgName>"`
  - available: `"<KIND>:<pkgName>@<ExtensionStoreUrls.key(repo)>"`. The same package from two repos gives two rows (r74, 33fe09d2). Before that only the first copy was kept, and the LIVID repo showed 0 packages.
- `otherCopy()` (r76, 40518ba3): a repo's copy of a package that is already installed is:
  - hidden if it **is** the installed copy (same repo key, or same signature);
  - otherwise listed with `otherInstalledFrom = <repo name>`.
  - Tapping Install or Sideload on such a row opens the "remove first" dialog in `UnifiedExtensionsTab`, because Android refuses a copy signed by someone else.
- `offeredBy()` (r75): an untrusted or installed package from a repo **without a fingerprint** is matched to that repo **by package name**.
- `showNsfw` (`show_nsfw_source`, default true) hides NSFW available packages. The repo row counts what was hidden.
- **Every** package of every repo is listed regardless of the language setting. Languages (Extensions › filter action, `source_languages`) only decide which sources show in Browse (comment in `build`).
- Sorting:
  - packages: updates/installing first, then installed, then by name
  - sources: pinned first, then by name
  - repos: by name
- `lastBuilt` (file-level, `@Volatile`) keeps the last built lists for the rest of the app run. The screen opens with them at once (r64, "Extensions opens at once") and refreshes behind them.
- In `init`, repos never read this session (their `indexUrl` is not in `ExtensionStoreStatus.entries`, e.g. after a restore) trigger `findAvailableExtensions()` (r75, cc0d838b). `refresh()` also runs on every screen-model creation.
- JS sources come from a separate `combine` over `jsSources.savedFlow` and the disabled/pinned prefs. They go into `allJsSources`, which is never part of packages or repos.

**Filters** (`State.packages` / `sources` / `repos`).
- The query matches:
  - packages: name, pkgName and any source name
  - sources: name, package name
  - repos: name, indexUrl
- Kind is one chip at a time (`toggleKind`). Repo is exact `indexUrl` equality. Status applies to packages only.
- **Note:** the repo row's "Repositories (n)" and its empty state follow the **query**. See the known issues.

**Install / update / cancel / trust actions.**
- `install(item, sideload)` calls `mangaManager.installExtension(ext, sideload)` or `animeManager.installExtension(...)` and `.track(item.key)`.
- `track` collects steps into `steps` until `Installed`, then removes the key.
- A row shows INSTALLING while `steps[key]?.isCompleted() == false`. `InstallStep.isCompleted()` is true for `Installed`, `Error` and `Idle`. So `Error` puts the row back to Install. Since r86 both installers (manga and anime) report it: a long toast "Couldn't install <name>: <reason>" (`KMR.anime_ext_install_failed`, or `KMR.ext_install_failed_no_reason` without a reason) and `W extensions Couldn't install <name> (manga|anime): <reason>`. The reason comes from the download exception, the anime loader's `lastError`, or Android's `EXTRA_STATUS_MESSAGE` (PackageInstaller/Shizuku, passed through `Installer.continueQueue(step, reason)` into `Installer.failureReasons`). A cancel (`CancellationException`) is not reported.
- `update(item)` uses `updateExtension`, which installs the repo's copy with `sideload = !ext.isShared`. So a sideloaded extension stays sideloaded.
- `cancel(item)` calls `cancelInstallUpdateExtension(...)` with `"<pkg>_<item.signatureHash>"` and removes the key from `steps`.
- `trust(item)` calls `manager.trust(untrusted)`. In practice it never runs: `TrustExtension.TRUST_ALL = true` (4735057d) means every installed extension is trusted without a prompt, like AniZen. `trusted_extensions` and "Revoke trusted extensions" are effectively dead. The Untrusted paths stay for safety.
- Remove: if the package is installed in Android, the system uninstall dialog opens. Otherwise (sideloaded or private) a confirm dialog appears and then `ExtensionLoader.uninstallPrivateExtension` runs, plus `SideloadFolder.remove`.
- Source switch (`setSourceEnabled`):
  - The row updates optimistically (r63, "APK switches move at once").
  - It writes `hidden_catalogues` / `anime_hidden_catalogues`.
  - A JS source and an APK sharing an id each keep their own switch via `JsSourceManager.setEnabled` / `setApkEnabled` (stored in `apk_off.txt`, see `03-js-sources.md`). Turning one off hands the id to the other.
  - Logs: `I extensions <name> (<Kind>) switched on|off`.
- Test sources (`SourceTester`): the first popular page of each source, 4 at a time (`Semaphore(4)`), 25 s timeout. Results:
  - "OK x.x s"
  - "Not loaded" / "Switched off" (source not in the manager)
  - "Opened, but listed nothing"
  - "No answer in 25 s"
  - the first line of the error
  - Logs: `I extensions Tested N sources: X working, Y failed`.

**Invariants.**
- Available keys must include the repo key, or two repos' copies collide in `LazyColumn(key = { it.key })`, which **crashes** with a duplicate key.
- Installed keys must **not** include it. `steps` lookups for update rows use `"<KIND>:<pkg>"`.
- `selectionKey = "<kind>_<id>"` is shared by selection, tests and the `LazyColumn` source keys. JS and APK sources with the same id differ by kind, so they don't collide.
- `UNIFIED_TAB_*` constants (0, 1, 2) are used by `UnifiedExtensionsTab.onRepoOpen`.

---

### Repositories (stores): adding, reading, status

**What / where.**
- Add / preview / confirm / delete dialogs: `presentation/more/settings/screen/browse/components/ExtensionStoresDialogs.kt`
- Add logic: `ExtensionStoresScreenModel.kt`
- `RepoKind` (Auto / Mihon (manga) / Aniyomi (anime)), `RepoInput`, `RepoMemory`: `RepoAdding.kt`
- Fetch and parse: `data/src/main/java/mihon/data/extension/service/ExtensionStoreService.kt`, which also holds the `ExtensionStoreUrls` helper object
- Status: `ExtensionStoreStatus.kt`
- DB and read: `data/.../repository/ExtensionStoreRepositoryImpl.kt` and `AnimeExtensionStoreRepositoryImpl.kt` (two tables: manga and anime stores)
- Aniyomi protobuf model: `data/.../model/AniyomiNetworkExtensionStore.kt`
- The old Settings › Browse › Extension repos screen (`ExtensionStoresScreen.kt`) still exists, and handles deep links.

**Adding a repo (r64, c36ecadb / f37b8ec9).**
1. `RepoInput.urls(text)`:
   - A pasted repo.json gives its `index_v2`.
   - Otherwise every `https?://` in the text, minus `/apk/`, `/icon/`, `.apk`, `.png` links.
   - Plain text without https is kept as typed.
   - Duplicates are removed by repo key.
   - More than one address calls `addMany`, which adds them all as Auto with no preview.
2. `lookUp` calls `ExtensionStoreService.resolve(input)`.
   - `ExtensionStoreUrls.candidates()` tries, in order: the file as typed, `<base>/repo.json`, `<base>/index.min.json`, `<base>/index.pb`, and the url with its query.
   - `https://` is added if missing. GitHub `/blob/` links become raw links.
   - The first one that reads as a repo wins.
   - `fetch()` looks at the first byte:
     - `[` is a legacy index.min.json. Details are read from repo.json next to it; with no repo.json the repo is named after its address with `NO_SIGNING_KEY`.
     - `{` is a repo.json (follows `index_v2` up to depth 2) or a JSON store.
     - Anything else is protobuf.
   - Gzip is detected by the `0x1f8b` magic.
3. `getExtensions(store)` counts manga vs anime packages (`pkgName` contains `.animeextension.`). The Preview dialog shows website, address, signing key (first 32 hex), package counts, "Already added as …", and the "Add as" chips.
4. `add(found, kind)`:
   - Auto adds as manga if it has manga packages **or** no anime packages, and as anime if it has anime packages. A mixed repo goes into both tables.
   - It then calls `RepoMemory.remember` (pref `known_extension_repos`, a tab-separated string set, kept after removal, offered as "Added before") and `findAvailableExtensions()` for the kinds it was added as.
   - Toast: "Added <name> (<Mihon + Aniyomi>)".

**Protobuf format quirk.** Mihon and Aniyomi `index.pb` differ in one field. `decodeEither()` tries Mihon's model, then Aniyomi's when Mihon's throws (`SerializationException` / `IllegalArgumentException`) **or** finds no sources. Keep both decoders.

**Reading repos (every refresh).**
- Manga: `ExtensionManager.findAvailableExtensions()` calls `ExtensionApi.findExtensions()`, which calls `ExtensionStoreRepositoryImpl.fetchExtensions(disabledRepos)`. That runs one `async` per enabled repo, on IO, under `supervisorScope`. For each repo:
  - `ExtensionStoreStatus.record(indexUrl, result)`.
  - Log `I ExtensionStoreRepositoryImpl Repo <name>: N packages read (<url>)`, or `E … Failed to fetch extensions for store '<name> (<url>)'`.
- Anime: the same, plus `storeRepository.refreshAll()` (re-reads each repo's details) **on every read**.
- `findLock` (r76): a `Mutex` per manager. A read requested while one runs waits for it and returns, without reading again. Before this, opening Extensions, a restore and a refresh each read every repo at the same moment. Logs from r74 (10859) show each repo read three times within milliseconds.
- Kind sorting:
  - The manga side partitions out packages containing `.animeextension.`. If a manga repo has anime packages, the repo is **inserted into the anime table**, removed from the manga table if it has nothing for manga, and the user sees the toast `anime_repo_moved`.
  - The anime side drops packages containing `.tachiyomi.extension.` and strips the `"Aniyomi: "` name prefix.
- `availableExtensionMapFlow` is keyed by `availableKey()` = `"<pkg>_<signatureHash>@<ExtensionStoreUrls.key(indexUrl)>"` (r86; before it was `pkg_signatureHash`, so two repos with the same or no key overwrote each other). **`signatureHash` of an available package is the repo's `signingKey`** (`NetworkExtensionStore.kt:109`, `NetworkLegacyExtension.kt:40`).
- Every lookup of "the" copy for an installed package goes through `Collection<Extension.Available>.copyFor(pkg, signatureHash, store)` (top of `ExtensionManager.kt`, used by both managers and the unified screen): same signature, else a no-fingerprint repo by name; among those the copy from the installed extension's `store` first, else the first.
- Installed-to-repo matching (`updatedInstalledExtensionsStatuses`, via `copyFor` since r86): first by same signature and package name, then (r75) by package name alone against a repo whose `signingKey` is not a real fingerprint (`ExtensionStoreUrls.hasFingerprint`: 32 hex bytes, colons optional). This exists for LIVID(18+), whose repo.json has no fingerprint, so installed LIVID packages never matched and their repo showed nothing installed.

**Repo enable switch.** Pref `disabled_repos` is a string set of **exact** `indexUrl`s. Disabled repos are skipped in `fetchExtensions`, so they never get a status entry.

**History of "issues with repos"** (symptom, cause, fix):

| Release | Symptom | Cause | Fix |
|---|---|---|---|
| r64 | Aniyomi repos added wrong / 0 packages; only some address forms worked | Aniyomi index fields not read; only exact index URL accepted | candidates(), decodeEither(), Preview step, mixed repos into both tables |
| r74 | LIVID repo showed 0 packages | same package offered by two repos; screen kept only the first (`distinctBy pkgName`) | `distinctBy { pkg to repoKey }` |
| r74 | repo looked emptier than it is | NSFW packages hidden by Show NSFW | "K hidden by Show NSFW" count, "No signing fingerprint" line |
| r75 | after a restore repos sat at 0 until manual refresh | restored stores never read this session | read unread repos when Extensions opens |
| r75 | LIVID installed/untrusted packages unclaimed | no fingerprint, so signature never matches | claim by package name (`offeredBy`, manager fallback) |
| r76 | can't see / install another repo's copy of an installed package | copies filtered out | list every copy, tagged, remove-first prompt; `findLock` |

**Invariants.**
- `ExtensionStoreUrls.key()` is the identity of a repo everywhere: screen counts, `otherCopy`, duplicate detection, RepoMemory, the add dialog. It lowercases, strips the scheme and index file, and maps `raw.githubusercontent.com/` to `github.com/`, `/raw/` to `/`, and `/refs/heads/` to `/`. Changing it changes which repos count as "the same".
- Build pitfall: 154bede3 (r75 fix). The `.removePrefix/.replace` chain had been pasted onto `hasFingerprint` instead of `key`, so the first r75 build failed. Keep each function's body next to its own signature.
- `ExtensionStoreStatus` and `disabled_repos` use the **exact** `indexUrl`, while counts use `key()`. `refreshAll()` can rewrite a store's `indexUrl` (it deletes the old row when `fetch` returns a different one). When that happens, the repo's status line and its disabled flag no longer match it.

**Predictable failure modes.**
- Fixed in r86: two repos with the **same signing key** (mirrors, or two `NO_SIGNING_KEY` repos) offering the same package used to collide in `availableExtensionMapFlow` (last one wins). The key now includes the repo. A package installed from repo A that repo B offers with the same signature is not listed under B (the installed row stands for it), but B's row counts it in "N packages · M installed". So tapping B's row can list fewer rows than its count. Check the `Repo X: N packages read` line in Logs against the count on the repo's row.
- Two repos' copies with the same package and signature share one install download id (`pkg_sig`), so installing one cancels an install of the other in progress.
- `ExtensionApi.checkForUpdates` (the background update notifier, upstream) matches by package name only. It can announce an update that comes from another repo's copy.
- A manga repo that briefly serves only anime packages is moved out of the manga table. This is automatic and not undone.
- Disabled repos never get a status entry, so the "unread repos" check in `UnifiedExtensionsScreenModel.init` triggers a full read on **every** screen-model creation while any repo is disabled. The read is folded by `findLock`, but it still costs network.

**How to check (Logs).**
- Good: one line per repo and kind per refresh, for example
  `I ExtensionStoreRepositoryImpl Repo Keiyoushi: 1435 packages read (https://github.com/keiyoushi/extensions/raw/repo/index.pb)` and
  `I AnimeExtensionStoreRepositoryImpl Repo Yūzōnō: 248 packages read (…)`.
- The same repo three times within a few ms means reads are no longer folded (`findLock` regression).
- `E … Failed to fetch extensions for store '<name> (<url>)'` means a network or parse error. The repo row shows "Last check failed: …".
- When adding a repo: `E ExtensionStoreService No repository found at '<input>' (tried …)`.
- The repo row's "Updated N min ago" comes from the same status map.

---

### Installers (Install, Sideload, Legacy, PackageInstaller, Shizuku)

**Where.**
- `domain/base/BasePreferences.kt` (`ExtensionInstaller` enum) and `domain/base/ExtensionInstallerPreference.kt`
- `tachiyomi/extension/util/ExtensionInstaller.kt` (manga) and `tachiyomi/animeextension/util/AnimeExtensionInstaller.kt` (anime, a copy)
- `extension/util/ExtensionInstallActivity.kt` (Legacy)
- `extension/util/ExtensionInstallService.kt` (foreground service, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`)
- `extension/installer/Installer.kt` (queue), `PackageInstallerInstaller.kt`, `ShizukuInstaller.kt`. These are upstream code, not changed by the fork.
- `extension/util/SideloadFolder.kt`
- Setting: Settings › Advanced › Extensions › Extension installer (`SettingsAdvancedScreen.kt` ~L503–565). It is followed by the info line `KMR.pref_private_installer_warning`.

**Setting.**
- Key `extension_installer`. Default `PACKAGEINSTALLER`, or `LEGACY` on MIUI.
- `SHIZUKU` falls back to the default if Shizuku isn't installed.
- `PRIVATE` is labelled "Sideload" since r75 (c1c3e33e, `KMR.ext_installer_sideload`). It is hidden only in `release` builds, so CI's preview builds show it.

**Flow** (`downloadAndInstall(url, ext, sideload)`).
1. `pkgName = "<pkg>_<signatureHash>"`, `downloadId = pkgName.hashCode() & 0xFFFFFFFF`. `cancelInstall(pkgName)` first cancels any earlier attempt of the same package and signature.
2. `MutableStateFlow(Pending)`, then `Downloading`. OkHttp downloads to `cacheDir/extension_<pkg>_<sig>.apk` (anime: `anime_extension_…`). Then `Installing`.
3. `installApk`. The installer used is:
   - `sideload == true`: PRIVATE.
   - `false` (the **Install** button): the setting, **except that PRIVATE becomes PACKAGEINSTALLER**. Install never sideloads.
   - `null` (updates and other callers): the setting.
4. Per installer:
   - LEGACY: `ExtensionInstallActivity` (`ACTION_INSTALL_PACKAGE` with `startActivityForResult`). `RESULT_OK` gives Installed, `RESULT_CANCELED` gives Idle, anything else gives Error. Has a MIUI 1 s ignore window.
   - PRIVATE: `ExtensionLoader.installPrivateExtensionFile` copies into `filesDir/exts/<pkg>.ext` (anime: `filesDir/anime_exts/`). Then `SideloadFolder.save(kind, pkg, apk)` copies into `<app folder>/Sideloaded/Manga|Anime/<pkg>.apk`, and the step becomes Installed.
   - PACKAGEINSTALLER / SHIZUKU: `ExtensionInstallService`, then `Installer.addToQueue`. One entry at a time (`waitingInstall`). The result comes back through `continueQueue(step)`, then `ExtensionManager.updateInstallStep(downloadId, step)`.
5. **Anime shares the service and activity.** `ExtensionManager.setInstalling` / `updateInstallStep` forward to `AnimeExtensionManager.updateInstallStep` (KMK). Each manager only updates ids it knows (`activeSteps[downloadId]`). If that forwarding is removed, every anime install via PackageInstaller, Shizuku or Legacy stays on "Installing" forever.
6. The app learns that an install finished from the package broadcast (`ExtensionInstallReceiver` / `AnimeExtensionInstallReceiver`, for `ACTION_PACKAGE_ADDED/REPLACED` and the private `ACTION_EXTENSION_*`), which registers the new extension.

**Sideloaded folder (r75).**
- `SideloadFolder.sync(kind)` runs on each manager's init:
  - An APK in the folder that the app doesn't have is installed privately.
  - A package tracked as sideloaded whose APK was deleted from the folder is **uninstalled from the app**, but only when the folder is readable and still lists other APKs (r86).
  - Nothing is removed when the folder is missing, can't be listed, or has no APKs at all while extensions are tracked (moved or cleared storage). Logs: `W Sideload The Sideloaded/<Manga|Anime> folder is missing or can't be reached / couldn't be read / has no APKs; kept the N sideloaded extensions`.
- Tracking pref: `sideloaded_extensions`, a string set of `manga:<pkg>` / `anime:<pkg>`. It is backed up.
- After a restore, `afterRestore` syncs, then re-downloads (sideloaded) the tracked packages that are missing, one after another, from whichever repo offers them.
- Logs (tag `Sideload`): `Put back …`, `Removed …: its APK was deleted from the Sideloaded folder`, `… was sideloaded, but no repo offers it now`, `Downloading … again from <repo>`, `Couldn't save … to the Sideloaded folder: …`.
- `UnifiedPackage.sideloaded = !ext.isShared`, which means private, not installed in Android.

**Extension class loading (r74, db8e7f0b, `util/system/ChildFirstPathClassLoader.kt`).**
- `SHARED_PREFIXES` always load from the app: `kotlin.`, `kotlinx.coroutines.`, `eu.kanade.tachiyomi.source.`, `…animesource.`, `…network.`, `okhttp3.`, `okio.`, `rx.`, `uy.kohesive.injekt.`, `androidx.preference.`.
- Reason: extensions that pack their own Kotlin failed with `LinkageError … resolves differently in interface AnimeSource`.
- The anime loader also retries parent-first on `LinkageError` (ee2a7fb0).
- The same prefix block appears twice in `loadClass`. That is harmless, but keep the copies in sync if you edit it.

**Invariants.**
- `pkgName_signatureHash` must be built the same way in `downloadAndInstall`, `cancelInstall`, `ExtensionManager.cancelInstallUpdateExtension` and the anime one. The download id is derived from it.
- The forwarding of steps between managers must stay (see step 5).
- `SideloadFolder` file names must be `<packageName>.apk`. `sync` derives the package from the file name. If a user drops a renamed APK (e.g. `Hiperdex.apk`), it is installed under its real package but tracked as `Hiperdex`: it is re-installed on every start and never removed when deleted. A predictable failure.

**How to check (Logs).**
- Successful installs via PackageInstaller / Legacy write **no** line of their own. Failures write `W extensions Couldn't install …` (r86) and also: `PackageInstallerInstaller` `xLogE "Failed to install extension <downloadId> <uri>"` and `Fatal error for <intent>`; `ExtensionInstaller` logcat errors on download failure. Shizuku logs `Shizuku is not ready to use`.
- To debug an install, look for the absence of these and check whether the row's state changes. Adding an AppLog line per install step would make this traceable.

---

### Known issues from the outside review of r84 (not fixed here)

Source: `/mnt/user-data/attachments/3fdd1b3f-356f-56ad-91d4-b4f370f0ebf7/da09188b-Komikku-feedback-for-friend.txt` and `de4cc437-Komikku-agent-handoff.md`. The main session planned fixes for r85 (transcript 2026-10-29 00:58 and 01:07). The code locations below are as of HEAD a693dc09.

1. **Repo kind chips cut off at "Aniyo".** `presentation/more/settings/screen/browse/components/ExtensionStoresDialogs.kt`: the Create dialog (~L124–133) and the Preview dialog (~L244–248) put the three `RepoKind` chips ("Auto", "Mihon (manga)", "Aniyomi (anime)", labels from `RepoAdding.kt`) in a plain `Row` with no wrapping. The last chip is squeezed inside the `AlertDialog` width. In the Create dialog, "Add from a file" sits below them, inside a `verticalScroll` column that the keyboard covers. A fix: `FlowRow` (as used in `UnifiedExtensionsScreen`), or stacked chips.
2. **"Repositories (0)" while searching.** `UnifiedExtensionsScreenModel.State.repos` (~L742) filters repos by `matchesQuery(r.name, r.indexUrl)`. The tab titles (`UnifiedExtensionsScreen.kt` ~L151–154) show filtered sizes. Typing an extension name therefore makes the Repositories count 0, and `RepoList` shows its empty text under "Add repository" (~L620–629). The Packages empty text, `KMR.ext_no_packages` ("No packages match. Add a repository to find more."), also suggests adding a repo when it is only a filtered-empty result.
3. **Keyboard hides results and Install buttons.** `UnifiedExtensionsContent` (~L149–241) stacks the tab row, search row, up to three chip rows, the progress bar, the update banner and the action chips (Sources) above the list. There is no `imePadding()`, and the list's bottom padding is only `contentPadding.calculateBottomPadding()` (~L184–189). Nothing hides the keyboard on scroll. The review also noted that only 2–3 entries fit even without the keyboard (item 4).
4. **Returning from Android's "Install unknown apps" lands on the library.**
   - The app never checks `packageManager.canRequestPackageInstalls()` before installing. It is only used in `presentation/util/Permissions.kt` and `data/updater/AppUpdateDownloadJob.kt`. So the user meets Android's own block screen and goes to Settings from there.
   - According to the review and the main session's analysis (not proven from code), turning the permission on restarts the app process.
   - After a restart, `tachiyomi/ui/home/HomeScreen.kt` ~L104–105 starts the `TabNavigator` at `LibraryTab` / `AnimeLibraryTab`. The Browse pager page, the Extensions tab page (`rememberPagerState`) and `UnifiedExtensionsScreenModel` filters and query are in memory only. Nothing is saved.
   - The install's `activeSteps` and the service queue die with the process.
5. **Android's prompt named another extension (Asura Scans update instead of Comic Days).** Cause **not proven**. The handoff says not to assert a wrong-download bug. Candidate code locations, all in upstream `PackageInstallerInstaller.kt` / `Installer.kt`:
   - (a) `packageActionReceiver` handles every `INSTALL_ACTION` broadcast without comparing the broadcast's `PackageInstaller.EXTRA_SESSION_ID` to `activeSession`. A status from another or leftover session advances or completes whatever entry is current (`continueQueue` acts on `waitingInstall`).
   - (b) Sessions are never cleaned up. There is no `packageInstaller.mySessions` / `abandonSession` sweep at start, so sessions left by earlier runs (the emulator had them) stay alive.
   - (c) The user-action intent is started with `FLAG_ACTIVITY_NEW_TASK` from the service. If the system installer's confirm task for an older session is still open, Android may bring that task to the front instead. Unverified.
   - The download side looks right: the row passes its own `Extension.Available`, the temp file name includes package and signature, and the Legacy path named the package correctly in the review.
6. **Row stuck on "Installing" after Android Cancel** (PackageInstaller path; Legacy cancel worked in the review). The row leaves INSTALLING only when its `steps` entry gets `Idle`, `Error` or `Installed`. Ways this can fail to happen:
   - (a) The `STATUS_FAILURE_ABORTED` for this session never arrives, or arrives after `waitingInstall` was already taken by another session's status (see 5a). `continueQueue` then does nothing for this entry.
   - (b) `Installer.cancelQueue` returns early when `PackageInstallerInstaller.cancelEntry` abandons the **active** session (it returns `false`). The step is not reset and `waitingInstall` is not cleared, so later installs stay queued behind it. Upstream behaviour, unverified on a device.
   - (c) In-app Cancel on an **update** uses the installed extension's `signatureHash`, while the download used the repo's copy, which differs for name-matched no-fingerprint repos. The cancel then targets a different download id. The row still clears, because `steps` drops the key locally, but the real install continues.
   - Only in-app Cancel (`UnifiedExtensionsScreenModel.cancel`, which removes the key from `steps`) clears the row. That matches the review ("remained on Installing until cancelled inside the app").
7. **"Private Installer" vs "Sideload" names.** The warning `i18n-kmk/.../base/strings.xml:136` (`pref_private_installer_warning`) says "Private Installer". The option label (`BasePreferences.kt:31`, `KMR.ext_installer_sideload`) says "Sideload". The old `presentation/browse/ExtensionsScreen.kt:439` still uses `MR.ext_installer_private` ("Private"). They are the same installer (`PRIVATE`).
8. Also seen in this area. Not part of the review, but likely to be reported:
   - A manga install `Error` gives no feedback: the row just returns to Install (see Unified Extensions → actions).
   - `checkForUpdates` matches packages by name only.

---

### Settings in this area

| Setting (as shown) | Preference key | Default | What it changes | Fork-added? |
|---|---|---|---|---|
| Extension installer (Advanced) | `extension_installer` | PackageInstaller (Legacy on MIUI) | how the Install button / updates install; PRIVATE shown as "Sideload"; Install never uses PRIVATE | changed |
| Show NSFW sources | `show_nsfw_source` | true | NSFW packages hidden from Packages; repo row shows hidden count | changed (counts) |
| Languages (Extensions › filter) | `source_languages` | device language(s) | which sources show in Browse; **not** the package list any more | changed |
| Source on/off switch | `hidden_catalogues` / `anime_hidden_catalogues` | empty | hides a source in Browse, Feed, search | anime key yes |
| Pin | `pinned_catalogues` / `anime_pinned_catalogues` | empty | sort order, global search Pinned | anime key yes |
| Repo switch | `disabled_repos` | empty | repo skipped when reading (exact indexUrl) | no (Komikku) |
| Manga / Anime toggle in Browse | `pref_browse_show_anime` | false | Sources pane and global search kind | yes |
| Hide feed tab | `hide_latest_tab` | false | Browse tabs | no (SY); forced off once by `kmk_feed_tab_restored` |
| Feed tab in front | `latest_tab_position` | false | Browse tab order | no (SY) |
| Replace latest button | `use_new_source_navigation` | false | sources open their Feed screen | no (SY) |
| Hide in-library items | `browse_hide_in_library_items` | false | filters Browse lists | no |
| (internal) known repos | `known_extension_repos` | empty | "Added before" in Add repository | yes |
| (internal) sideloaded list | `sideloaded_extensions` | empty | Sideloaded folder sync / restore | yes |
| (internal) trusted extensions | appState `trusted_extensions` | empty | ignored while `TRUST_ALL = true` | changed |
| (internal) Feed anime rows | `feed_anime_rows` | "" | anime Feed rows | yes |
| (internal) Feed tag mixes | `feed_tag_mixes` | "[]" | tag-mix Feed rows | yes |
| Refresh tag list (JS source settings) | `js_refresh_tags` (not persisted) | — | re-reads `getTags()` | yes |

---

### Cross-feature impact map

- Changing `ExtensionStoreUrls.key()` affects repo counts, the duplicate check in Add repository, RepoMemory, `otherCopy` (which copy is "the installed one") and available package keys, because all of them compare repos through it.
- Changing the `availableExtensionMapFlow` key (`pkg_signatureHash`) affects updates (`updateExtension` lookup), `updateExists`, sideload restore (`availableNow()`) and repo package counts, because the key decides which copies survive.
- Changing `TrustExtension.TRUST_ALL` brings back the Untrusted state in both loaders. The unified screen's Trust dialog, `offeredBy`, and the `trust()` paths are then live again.
- Changing `ExtensionManager.updateInstallStep` / `setInstalling` affects **anime** installs, because they share `ExtensionInstallService` / `ExtensionInstallActivity` / `Installer`, and the manga manager forwards steps to the anime one.
- Changing the installer setting's semantics affects `SideloadFolder.afterRestore` and `updateExtension` (`sideload = !isShared`). Updates follow how the extension was installed, not the setting.
- Changing `SideloadFolder.sync` risks deleting extensions on start. It removes tracked packages whose APK is missing from the folder, and runs from both managers' `init`.
- Changing a JS source's catalog tabs (all-tabs vs not) shifts its filter positions, so its saved searches, Feed rows built from saved searches, and the kept Filter tab deserialize wrongly.
- Changing `AutoCompleteItem` affects E-Hentai/ExHentai tag search, JS tag filters, anime tag filters (via `AnimeFilterBridge`) and the Browse preview tag hold (9b2cde16).
- Changing `BrowseTabCache` (size, key) affects every source screen subclass (migration search, SY feeds), because the key includes the class name and the cache is global.
- Changing Browse tab order affects `BrowseTab.showExtension()` (notification "open extensions") and Feed-in-front logic.
- Adding screen models to `BrowseTab` adds repo reads on every Browse open (each model calls `findAvailableExtensions`). They only stay cheap because of `findLock`.
- Changing the source-open rule (`use_new_source_navigation`) means updating three places: `SourcesTab`, `BrowseTab.animeSources`, `UnifiedExtensionsTab.onSourceClick`.

---

### Build pitfalls seen in this area

- **154bede3 (r75):** a helper's body was pasted onto the wrong function. The `ExtensionStoreUrls.key()` string chain was attached to `hasFingerprint(): Boolean`, which does not compile. When moving code between functions, check that each expression is still attached to its own function.
- **a2741c97 (r81):** a missing `androidx.compose.foundation.layout.fillMaxSize` import in `AutoCompleteItem.kt`. New Compose modifiers need their own import. CI reports these as annotations.
- **509b7360:** a stray parenthesis in `presentation/browse/anime/AnimeBrowsePanes.kt`.
- **66ae32e2:** `stringResource()` called inside a `LazyListScope` builder (not a `@Composable` context). Resolve strings before the `LazyColumn { }` block.
- **85fa314c (runtime crash, not compile):** `ExposedDropdownMenu` with a `LazyColumn` inside needs a fixed width and height (no intrinsic measurement).
- Duplicate `LazyColumn` keys crash at runtime. This applies to package keys (must include the repo for available copies), tag keys (must be distinct) and source keys (`kind_id`).
- **AGENTS.md rule:** fork-only strings go in `i18n-kmk/.../base/strings.xml` (`KMR`). Several strings in this area are still hard-coded English in Kotlin ("Select", "Test sources", the repo status lines, the remove-first dialog, the Sideload log lines). That is fine to build, but follow the rule for new strings.
