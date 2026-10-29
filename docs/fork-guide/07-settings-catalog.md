## 07 · Settings catalog

Every setting the app has, screen by screen in the order the app shows them, with its preference key, type and
default, the code that reads it, and whether the fork added or changed it. After the Settings screens come the
settings that live in sheets and menus (library sheet, browse display menu, reader sheet, player panels, episode
sheet, migration options, source settings), then the hidden preferences the fork keeps, then lists of dead
settings, conflicts and restart-only settings.

Paths are relative to `app/src/main/java/eu/kanade/` unless they start with `domain/`, `core/` or `mihon/`.

### How this catalog was made (and how far to trust it)

- Each screen was parsed in display order (walking `getPreferences()` and the group functions it calls, including
  the ones in `presentation/more/settings/screen/AnimeSettingsGroups.kt`). Titles are the English text from the
  `base/strings.xml` of `i18n` (`MR`), `i18n-sy` (`SYMR`), `i18n-kmk` (`KMR`) and `i18n-ank` (`AMR`).
- Keys, types and defaults come from the `fun x() = preferenceStore.getX("key", default)` definitions
  (561 of them, 233 fork-authored). Comment-out code (`/* KMK --> … KMK <-- */`) was ignored.
- **Read by** = files (outside the settings screens and the preference class itself) that call the getter. It is a
  name search, so when two classes have a getter with the same name the list says "approximate"; the ones that
  matter were checked by hand and corrected. "**none found**" was checked by hand too (grep for the getter and
  the key string).
- **Fork** column, from `git blame` of the definition line (and of the screen item when the preference itself is
  upstream):
  - `yes <hash>`: the preference was added by a fork author (Claude / Dominican4Life / OmfgItsJesus).
  - `yes (AniZen port 23ba5816)`: added by the fork when it copied AniZen's player and its settings
    ("Anime stage 3", 2026-10-08). The behaviour is AniZen's, but in this repo it is fork code; later
    fork commits (12919569/403d7446 "the 7 player settings work", r56) made several of them actually do something.
  - `UI added/edited <hash>`: upstream preference, but the fork added or changed the row on the screen.
  - `changed`: upstream preference whose default or effect the fork changed.
  - `no`: upstream Komikku/SY/Mihon.

### How settings are stored and when changes apply

- All app settings are in the default SharedPreferences through `tachiyomi.core.common.preference.PreferenceStore`
  (`AndroidPreferenceStore`). Keys made with `Preference.appStateKey(k)` get the prefix `__APP_STATE_` and are
  **never backed up**; `Preference.privateKey(k)` gets `__PRIVATE_` and is backed up only with "Private settings".
  Everything else, including the fork's per-title keys like `anime_anilist_episodes_<animeId>`, goes into the
  "App settings" part of a backup (`tachiyomi/data/backup/create/creators/PreferenceBackupCreator.kt`) and is restored as is
  (`PreferenceRestorer.kt`, untouched by the fork).
- A setting takes effect where it is read: values read through `.collectAsState()` / `.changes()` update live;
  values read with `.get()` in a job, an `init`, or `PlayerActivity.onCreate`/`setupPlayerMPV` apply **the next
  time that job/screen/player starts**. Most player settings are of the second kind: change them, then open a new
  episode. The library update reads "Pause between titles" once at the start of a run.
- Per-source settings (extension "Source settings", JS/JSON sources) are in a separate SharedPreferences file per
  source: `source_<sourceId>` (`tachiyomi/jssource/JsPreferences.kt` uses the same name on purpose so JS source
  settings show in the usual source settings screen).

### Where the screens are

Settings (`presentation/more/settings/screen/SettingsMainScreen.kt`), in order: **Appearance, Library, Reader,
Downloads, Tracking, Browse, Data and storage, Security and privacy, E-Hentai** (only if `eh_is_hentai_enabled`),
**MangaDex** (only if a MangaDex source is enabled), **Advanced, About**.

- The fork moved **Player settings** out of Settings into the **More** tab (`tachiyomi/ui/more/MoreTab.kt` →
  `tachiyomi/ui/setting/PlayerSettingsScreen.kt` → `screen/player/PlayerSettingsMainScreen.kt`, commit 8c36b0c7, r57).
  Its list: Internal player, Player layout, Gestures, Decoder, Subtitles, Audio, Custom buttons, Script editor,
  Advanced.
- The fork **removed Connections (Discord)** from the Settings list (39f455aa, comment
  `// KMK: Connections (Discord) section removed`). `SettingsConnectionScreen`/`SettingsDiscordScreen` still exist
  but nothing navigates to them. Their keys are listed below for completeness.
- Settings search (`SettingsSearchScreen.kt`) indexes the six player screens too (fork lines in that file).


### Settings › Appearance

`presentation/more/settings/screen/SettingsAppearanceScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| **Theme** | | | | |
| App theme | (CustomPreference, action) | | | no |
| Palette Personalizer | (TextPreference, action) | | | no |
| Custom theme style | `"pref_custom_theme_style_key"` | Enum, `PaletteStyle.Fidelity` | TachiyomiTheme.kt | no |
| Pure black dark mode | `"pref_theme_dark_amoled_key"` | Boolean, `false` | ContextExtensions.kt, CoverFillColor.kt, CoverSeedTheme.kt, MangaInfoHeader.kt, TachiyomiTheme.kt … | no |
| **Manga Info** | | | | |
| Theme based on cover | `"pref_theme_cover_based_key"` | Boolean, `true` | AnimeUiAdapters.kt, BrowsePreviewScreenModel.kt, MangaCoverFetcher.kt, MangaScreenModel.kt, MetadataViewScreenModel.kt … | no |
| Cover based theme style | `"pref_theme_cover_based_style_key"` | Enum, `PaletteStyle.Vibrant` | CoverFillColor.kt, CoverSeedTheme.kt, TachiyomiTheme.kt | no |
| Panorama Cover | `"use_panorama_cover_manga_info"` | Boolean, `false` | MangaInfoHeader.kt | no |
| Align Cover to Top | `"top_align_cover"` | Boolean, `false` | MangaInfoHeader.kt | no |
| **Display** | | | | |
| App language | (TextPreference, action) | | | no |
| Tablet UI | `"tablet_ui_mode"` | Enum, `TabletUiMode.AUTOMATIC` | DisplayExtensions.kt | no |
| Date format | `"app_date_format"` | String, `""` | AnimeScreen.kt, DateText.kt, PlayerViewModel.kt, ReaderViewModel.kt, TrackInfoDialog.kt | no |
| Relative timestamps | `"relative_time_v2"` | Boolean, `true` | AnimeScreen.kt, DateText.kt, MoveRelativeTimeSettingMigration.kt, PlayerViewModel.kt, ReaderViewModel.kt | no |
| Render images in manga descriptions | `"pref_render_images_description"` | Boolean, `true` | MangaInfoHeader.kt | no |
| **Navbar** | | | | |
| Show updates in the nav | `"pref_show_updates_button"` | Boolean, `true` | MoreTab.kt, UpdatesTab.kt | no |
| Show history in the nav | `"pref_show_history_button"` | Boolean, `true` | HistoryTab.kt, MoreTab.kt | no |
| Always show nav labels | `"pref_show_bottom_bar_labels"` | Boolean, `true` | HomeScreen.kt | no |
| **Fork Settings** | | | | |
| Panorama Cover Flow | `"use_panorama_cover_flow"` | Boolean, `false` | GlobalSearchCardRow.kt | no |
| Panorama style in the library, History and Updates | `"pref_library_panorama_style"` | Enum, `PanoramaStyle.FILL` | AnimeLibraryContent.kt, AnimeLibrarySettings.kt, HistoryScreen.kt, LibraryComfortableGrid.kt, LibrarySettingsDialog.kt … | yes 4f99fff2 |
| Panorama style in sources | `"pref_browse_panorama_style"` | Enum, `PanoramaStyle.FIT` | AnimeSourceScreen.kt, BrowseSourceComfortableGrid.kt, BrowseSourceToolbar.kt | yes 4f99fff2 |
| Expand all search filters by default | `"eh_expand_filters"` | Boolean, `false` | BrowseSourceScreenModel.kt, SourceFeedScreenModel.kt | no |
| Recommendations in overflow | `"recommends_in_overflow"` | Boolean, `false` | MangaScreenModel.kt | no |
| Merge in overflow | `"merge_in_overflow"` | Boolean, `true` | MangaScreenModel.kt | no |
| Previews row count | `"pref_previews_row_count"` | Int, `4` | MangaScreenModel.kt | no |

Notes:
- **App theme** (custom row) writes `pref_theme_mode_key` (Enum ThemeMode, default `SYSTEM`; read by App.kt,
  ContextExtensions.kt) and `pref_app_theme` (Enum AppTheme, default `MONET`; TachiyomiTheme.kt, ThemingDelegate.kt).
  Palette Personalizer opens `presentation/more/settings/screen/appearance/AppCustomThemeColorPickerScreen.kt` (custom seed colour).
- **Tablet UI** shows "requires app restart" (it is read in `DisplayExtensions.kt` when activities are created).
- **Panorama style in the library, History and Updates** / **Panorama style in sources** (fork, Dominican4Life
  4f99fff2 + UI f4e8c4db, Oct 11): values `FILL` (colour fill: wide cover centred, its colour above and
  below), `FIT` (fit width, shorter card), `WIDE` (same height, takes two places in the row). They only matter when
  the display mode is the Panorama grid (`LibraryDisplayMode.ComfortableGridPanorama`). The same two keys are also
  set from the library sheet's Display tab and the source toolbar's display menu (the Panorama chips set the style
  *and* switch the display mode), so the Appearance rows and the sheets are two views of one value. The library
  style is shared by the manga library, the anime library, History and Updates (`HistoryScreen.kt`,
  `UpdatesScreen.kt`, `MediaTabs.kt`); anime sources follow the *sources* style (`AnimeSourceScreen.kt`).
- **Date format** list was dropped by mistake once and restored in ab0aaa0a ("Appearance settings build").
- **Pure black dark mode** and **Cover based theme style** are also read by the fork's `presentation/components/CoverFillColor.kt` and
  `presentation/theme/CoverSeedTheme.kt` (Panorama colour fill, cover-seeded theme), so changing them changes the
  Panorama FILL colour too.



### Settings › Library

`presentation/more/settings/screen/SettingsLibraryScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| **Categories** | | | | |
| Edit categories | (TextPreference, action) | | | no |
| Default category | `"default_category"` | Int, `-1` | BrowseSourceScreenModel.kt, BulkFavoriteScreenModel.kt, CategoryPreferencesCleanupMigration.kt, DeleteCategory.kt, HistoryScreenModel.kt … | no |
| Per-category settings for sort | `"categorized_display"` | Boolean, `false` | CategoriesRestorer.kt, SetSortModeForCategory.kt | no |
| **Badges** | | | | |
| Cover badges | (TextPreference, action) | | | UI added 3006799e |
| Source badges | (TextPreference, action) | | | UI added 3006799e |
| Reading progress bar | `"cover_progress_bar"` | Boolean, `true` | CoverProgressBar.kt | yes 6265220d |
| **Global update** | | | | |
| Automatic updates | `"pref_library_update_interval_key"` | Int, `0` | LibraryUpdateJob.kt, RemoveShortLibraryUpdatesMigration.kt, RemoveShorterLibraryUpdatesMigration.kt | no |
| Automatic updates device restrictions | `"library_update_restriction"` | StringSet, `setOf( DEVICE_ONLY_ON_WIFI, )` | AnimeLibraryUpdateJob.kt, LibraryUpdateJob.kt, RemoveBatteryNotLowRestrictionMigration.kt | UI edited 44e283c2 |
| Categories | (TextPreference, action) | | | no |
| Library dynamic category updates | `"group_library_update_type"` | Enum, `GroupLibraryMode.GLOBAL` | LibraryUpdateJob.kt | no |
| Automatically refresh metadata | `"auto_update_metadata"` | Boolean, `false` | LibraryUpdateJob.kt | no |
| Smart update | `"library_update_manga_restriction"` | StringSet, `setOf( MANGA_HAS_UNREAD, MANGA_NON_COMPLETED, MANGA_NON_READ` | GetUpcomingManga.kt, LibraryScreenModel.kt, LibrarySettingsDialog.kt, LibraryUpdateJob.kt, MangaInfoHeader.kt … | no |
| Show unread count on Updates icon | `"library_show_updates_count"` | Boolean, `true` | HomeScreen.kt | no |
| Show updating progress banner | `"pref_show_updating_progress_banner_key" (appState)` | Boolean, `true` | MainActivity.kt | no |
| Pause between titles | (CustomPreference, action) | | | UI added 0fa264cd |
| **Anime** | | | | |
| Automatic anime updates | `"anime_update_interval"` | Int, `UPDATE_WITH_MANGA` | AnimeLibraryUpdateJob.kt | yes ab039f1b |
| Anime categories | (TextPreference, action) | | | UI added ab039f1b |
| Skip anime | `"anime_update_skip"` | StringSet, `setOf(SKIP_COMPLETED)` | AnimeLibraryUpdateJob.kt, AnimeStats.kt | yes ab039f1b |
| | (InfoPreference, action) | | | UI added ab039f1b |
| **Behavior** | | | | |
| Chapter on swipe to left | `"pref_chapter_swipe_end_action"` | Enum, `ChapterSwipeAction.ToggleBookmark` | MangaScreenModel.kt, UpdatesScreenModel.kt | no |
| Chapter on swipe to right | `"pref_chapter_swipe_start_action"` | Enum, `ChapterSwipeAction.ToggleRead` | MangaScreenModel.kt, UpdatesScreenModel.kt | no |
| Episode on swipe to left | `"pref_episode_swipe_start_action"` | Enum, `ChapterSwipeAction.ToggleBookmark` | AnimeScreen.kt | yes fd1f8656 |
| Episode on swipe to right | `"pref_episode_swipe_end_action"` | Enum, `ChapterSwipeAction.ToggleRead` | AnimeScreen.kt | yes fd1f8656 |
| Show episode summary | `"anime_episode_show_summaries"` | Boolean, `true` | AnimeScreenModel.kt | yes b9103f1f |
| Show episode thumbnail | `"anime_episode_show_previews"` | Boolean, `true` | AnimeScreenModel.kt | yes b9103f1f |
| Season grouping | `"anime_episode_season_grouping"` | Enum, `AnimeSeasonGrouping.Tabs` | AnimeScreenModel.kt | yes 6b789f59 |
| Mark duplicate read chapter as read | `"mark_duplicate_read_chapter_read"` | StringSet, `emptySet()` | ReaderViewModel.kt, SyncChaptersWithSource.kt | no |
| Hide missing chapter indicators | `"pref_hide_missing_chapter_indicators"` | Boolean, `false` | MangaScreenModel.kt | no |
| Show empty categories during search/filtering | `"show_empty_categories_search"` | Boolean, `false` | LibraryScreenModel.kt | no |
| Fetch metadata when adding to library | `"fetch_metadata_on_add"` | Boolean, `false` | BrowseSourceScreenModel.kt, BulkFavoriteScreenModel.kt | no |
| Fetch chapters when adding to library | `"fetch_chapters_on_add"` | Boolean, `false` | BrowseSourceScreenModel.kt, BulkFavoriteScreenModel.kt | no |
| **Sorting Settings** | | | | |
| Tag sorting tags | `"sort_tags_for_library"` | StringSet, `mutableSetOf()` | CreateSortTag.kt, DeleteSortTag.kt, GetSortTag.kt, LibraryScreenModel.kt, LibrarySettingsDialog.kt … | no |

Notes:
- **Badges** group (fork, 3006799e, Oct 9): two sub-screens, see "Cover badges / Source badges" below, plus
  **Reading progress bar** (`cover_progress_bar`, 6265220d): the thin bar on covers in Library and History, drawn by
  `presentation/components/CoverProgressBar.kt`; live.
- **Automatic updates device restrictions** is upstream, but the fork made its change also reschedule the anime
  update (`AnimeLibraryUpdateJob.setupTask`, 44e283c2) because **the anime update uses the manga restrictions**
  (Wi-Fi / unmetered / charging). The row is disabled (`enabled = autoUpdateInterval > 0`) when the *manga*
  update is "Never", so with manga updates off and an anime schedule on, the restrictions can't be edited (they
  still apply). See conflicts.
- **Pause between titles** (fork, Dominican4Life 0fa264cd, r28): custom row `presentation/components/PauseBetweenTitlesItem.kt`,
  a switch plus a 1–15 s range slider. Keys `pref_library_update_pause_enabled` (Boolean, `false`) and
  `pref_library_update_pause` (String range `"1-3"`, parsed by `tachiyomi/util/PauseBetweenTitles.range()`; an unreadable or
  `0-0` value counts as off). Read once at the start of `LibraryUpdateJob`, `AnimeLibraryUpdateJob`,
  `RefreshMissingCovers`, `AnimeRefreshMissingCovers`; the wait (`PauseBetweenTitles.pause`, random within the
  range) is between titles **of the same source** (sources still run in parallel). Its sibling for migrations is
  `pref_migration_delay_enabled` (**default `true`**) / `pref_migration_delay` (`"1-3"`) in the migration options.
- **Anime** group (fork, ab039f1b/0ba21b48, r54; `AnimeSettingsGroups.kt` `getAnimeLibraryUpdateGroup`, object
  `tachiyomi/data/anime/AnimePrefs.kt`):
  - **Automatic anime updates** `anime_update_interval`: `-1` = "With the manga update" (default;
    `LibraryUpdateJob` calls `AnimeLibraryUpdateJob.startWithManga`), `0` = never, else hours (own WorkManager
    periodic job, re-set on every app start from `MainActivity` and on change).
  - **Anime categories** writes `anime_update_categories` / `anime_update_categories_exclude` (StringSets of anime
    category ids, empty = all). Also read by `GetUpcomingManga.kt`, stats and `DeleteCategory`.
  - **Skip anime** `anime_update_skip`: `completed` (default), `has_unseen`, `not_started`, `smart`.
  - The info row says "Anime updates use the same device restrictions as manga updates. A manual manga library
    refresh also checks anime."
- **Episode on swipe to left/right** (fork fd1f8656, Oct 18): `pref_episode_swipe_start_action` /
  `pref_episode_swipe_end_action`, same `ChapterSwipeAction` enum as manga, read by `tachiyomi/ui/anime/AnimeScreen.kt`.
  (Upstream's manga keys are swapped by name — "swipe to left" = `pref_chapter_swipe_end_action` — that's
  upstream history, don't "fix" it.)
- **Show episode summary / Show episode thumbnail** (`anime_episode_show_summaries` / `anime_episode_show_previews`,
  `tachiyomi/data/anime/AnimeEpisodeDefaults.kt`): **defaults for newly added anime only** (`AnimeEpisodeDefaults.newAnimeFlags()`
  in `AnimeRepository`), and for "Reset" on an anime's episode sheet. Existing anime keep their own
  `HIDE_PREVIEWS`/`HIDE_SUMMARIES` episode flags. The episode sheet's **Set as default** overwrites these two
  switches (`AnimeScreenModel.setEpisodeSettingsAsDefault`).
- **Season grouping** `anime_episode_season_grouping` (`Disabled`/`Headers`/`Tabs`, default `Tabs`): used by anime whose own
  grouping is "Default" (`AnimeEpisodeDefaults.seasonGrouping(anime)`), live.


### Settings › Library › Cover badges / Source badges

`presentation/more/settings/screen/SettingsBadgesScreen.kt` (fork, 3006799e). One multi-select row per corner
(Top left, Top right, Bottom left, Bottom right); each picks which badges go in that corner.

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Cover badges › <corner> | `cover_badges_top_left` / `_top_right` / `_bottom_left` / `_bottom_right` | StringSet; defaults: top left `downloaded, unread, favorite`; top right `language, source`; bottoms empty | `presentation/components/BadgeCorners.kt` (`rememberCorners`), used by library cards, browse grids | yes 3006799e |
| Source badges › <corner> | `source_badges_<corner>` | StringSet; defaults: top left `type, language, nsfw`; top right `settings`; bottom left `pin`; bottom right `latest` | `BadgeCorners.kt`, `presentation/browse/components/SourceTile.kt` | yes 3006799e |
| (hidden) | `source_type_badge_added` | Boolean, `false` | `BadgeCorners.kt`: once, adds the new `type` badge to the corner holding `language` for layouts saved before r63 | yes 355d9ef8 |

Corner keys and defaults are in `domain/ui/BadgeCorner.kt` (`BadgeCorner`, `CoverBadge`, `SourceBadge`).
Interaction: the corner screen only decides *where*; *whether* a cover badge has content still depends on the
library sheet toggles (Downloaded chapters, Unread chapters, Local source, Language, Source icon), because
`LibraryScreenModel` zeroes the counts when those are off. A badge removed from every corner is never shown, even
with its sheet toggle on.


### Settings › Reader

`presentation/more/settings/screen/SettingsReaderScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Default reading mode | `"pref_default_reading_mode_key"` | Int, `ReadingMode.RIGHT_TO_LEFT.flagValue` | ReaderViewModel.kt | no |
| Double tap animation speed | `"pref_double_tap_anim_speed"` | Int, `500` | ViewerConfig.kt | no |
| Show reading mode | `"pref_show_reading_mode"` | Boolean, `true` | ReaderActivity.kt | no |
| Show tap zones overlay | `"reader_navigation_overlay_on_start"` | Boolean, `false` | ViewerConfig.kt | no |
| Smaller tap zones | `"reader_navigation_smaller_tap_zone"` | Boolean, `false` | PagerConfig.kt, ReadingModePage.kt, ViewerNavigation.kt, WebtoonConfig.kt | no |
| Force horizontal seekbar | `"pref_force_horz_seekbar"` | Boolean, `false` | GeneralSettingsPage.kt, ReaderActivity.kt | no |
| Show vertical seekbar in landscape | `"pref_show_vert_seekbar_landscape"` | Boolean, `false` | GeneralSettingsPage.kt, ReaderActivity.kt | no |
| Left-handed vertical seekbar | `"pref_left_handed_vertical_seekbar"` | Boolean, `false` | GeneralSettingsPage.kt, ReaderActivity.kt | no |
| **Display** | | | | |
| Default rotation | `"pref_default_orientation_type_key"` | Int, `ReaderOrientation.FREE.flagValue` | ReaderViewModel.kt | no |
| Background color | `"pref_reader_theme_key"` | Int, `1` | ContextExtensions.kt, GeneralSettingsPage.kt, PagerConfig.kt, ReaderActivity.kt, RemoveOldReaderThemeMigration.kt … | no |
| Fullscreen | `"fullscreen"` | Boolean, `true` | GeneralSettingsPage.kt, ReaderActivity.kt | no |
| Show content in cutout area | `"cutout_short"` | Boolean, `true` | GeneralSettingsPage.kt, ReaderActivity.kt | no |
| Keep screen on | `"pref_keep_screen_on_key"` | Boolean, `false` | GeneralSettingsPage.kt, ReaderActivity.kt | no |
| Show page number | `"pref_show_page_number_key"` | Boolean, `true` | GeneralSettingsPage.kt, ReaderActivity.kt | no |
| **"E-Ink"** | | | | |
| Flash on page change | `"pref_reader_flash"` | Boolean, `false` | GeneralSettingsPage.kt, ReaderActivity.kt | no |
| Flash duration | `"pref_reader_flash_duration"` | Int, `MILLI_CONVERSION` (ms) | DisplayRefreshHost.kt | no |
| Flash every | `"pref_reader_flash_interval"` | Int, `1` (pages) | DisplayRefreshHost.kt | no |
| Flash with | `"pref_reader_flash_mode"` | Enum, `FlashColor.BLACK` | DisplayRefreshHost.kt, GeneralSettingsPage.kt | no |
| **Reading** | | | | |
| Skip chapters marked read | `"skip_read"` | Boolean, `false` | ReaderViewModel.kt | no |
| Skip filtered chapters | `"skip_filtered"` | Boolean, `true` | ReaderViewModel.kt, MangaScreenModel.kt **and PlayerViewModel.kt** (PlayerPreferences.skipFiltered() uses the same key) | no (key shared by fork) |
| Skip duplicate chapters | `"skip_dupe"` | Boolean, `false` | ReaderViewModel.kt | no |
| Always show chapter transition | `"always_show_chapter_transition"` | Boolean, `true` | GeneralSettingsPage.kt, ViewerConfig.kt | no |
| **Paged** | | | | |
| Tap zones | `"reader_navigation_mode_pager"` | Int, `0` | MoveReaderTapSettingMigration.kt, PagerConfig.kt, ReadingModePage.kt | no |
| Invert tap zones | `"reader_tapping_inverted"` | Enum, `TappingInvertMode.NONE` | PagerConfig.kt, ReadingModePage.kt | no |
| Scale type | `"pref_image_scale_type_key"` | Int, `1` | PagerConfig.kt, ReadingModePage.kt | no |
| Zoom start position | `"pref_zoom_start_key"` | Int, `1` | PagerConfig.kt, ReadingModePage.kt | no |
| Crop borders | `"crop_borders"` | Boolean, `false` | PagerConfig.kt, ReaderActivity.kt, ReaderPageImageView.kt, ReaderViewModel.kt, ReadingModePage.kt … | no |
| Animate page transitions | `"pref_enable_transitions_pager_key"` | Boolean, `true` | PagerConfig.kt, ReadingModePage.kt | no |
| Automatically zoom into wide images | `"landscape_zoom"` | Boolean, `true` | PagerConfig.kt, ReaderPageImageView.kt, ReadingModePage.kt | no |
| Wide images zoom mode | `"landscape_zoom_type"` | Enum, `LandscapeZoomScaleType.FIT` | PagerConfig.kt | no |
| Disable zoom in | `"paged_disable_zoom_in"` | Boolean, `false` | PagerConfig.kt, ReadingModePage.kt | no |
| Double tap to zoom | `"pref_enable_double_tap_zoom_paged"` | Boolean, `true` | PagerConfig.kt, ReadingModePage.kt | no |
| Pan wide images | `"navigate_pan"` | Boolean, `true` | PagerConfig.kt, ReadingModePage.kt | no |
| Split wide pages | `"pref_dual_page_split"` | Boolean, `false` | PagerConfig.kt, ReaderActivity.kt, ReadingModePage.kt | no |
| Invert split page placement | `"pref_dual_page_invert"` | Boolean, `false` | PagerConfig.kt, ReadingModePage.kt | no |
| Rotate wide pages to fit | `"pref_dual_page_rotate"` | Boolean, `false` | PagerConfig.kt, ReadingModePage.kt | no |
| Flip orientation of rotated wide pages | `"pref_dual_page_rotate_invert"` | Boolean, `false` | PagerConfig.kt, ReadingModePage.kt | no |
| **Long strip** | | | | |
| Tap zones | `"reader_navigation_mode_webtoon"` | Int, `0` | MoveReaderTapSettingMigration.kt, ReadingModePage.kt, WebtoonConfig.kt | no |
| Invert tap zones | `"reader_tapping_inverted_webtoon"` | Enum, `TappingInvertMode.NONE` | ReadingModePage.kt, WebtoonConfig.kt | no |
| Smart scale on wide screen | `"pref_webtoon_scale_type_key"` | Enum, `WebtoonScaleType.FIT` | ReadingModePage.kt, WebtoonConfig.kt | no |
| Apply Smart scale to Long strip with gaps | `"pref_webtoon_smart_scale_long_strip_gap"` | Boolean, `false` | ReadingModePage.kt, WebtoonViewer.kt | no |
| Side padding | `"webtoon_side_padding"` | Int, `WEBTOON_PADDING_MIN` | ReadingModePage.kt, WebtoonConfig.kt | no |
| Sensitivity for hiding menu on scroll | `"reader_hide_threshold"` | Enum, `ReaderHideThreshold.LOW` | WebtoonViewer.kt | no |
| Crop borders | `"crop_borders_webtoon"` | Boolean, `false` | ReaderActivity.kt, ReaderViewModel.kt, ReadingModePage.kt, WebtoonConfig.kt | no |
| Split wide pages | `"pref_dual_page_split_webtoon"` | Boolean, `false` | ReadingModePage.kt, WebtoonConfig.kt | no |
| Invert split page placement | `"pref_dual_page_invert_webtoon"` | Boolean, `false` | ReadingModePage.kt, WebtoonConfig.kt | no |
| Rotate wide pages to fit | `"pref_dual_page_rotate_webtoon"` | Boolean, `false` | ReadingModePage.kt, WebtoonConfig.kt | no |
| Flip orientation of rotated wide pages | `"pref_dual_page_rotate_invert_webtoon"` | Boolean, `false` | ReadingModePage.kt, WebtoonConfig.kt | no |
| Double tap to zoom | `"pref_enable_double_tap_zoom_webtoon"` | Boolean, `true` | ReadingModePage.kt, WebtoonConfig.kt | no |
| Pinch to zoom | `"pref_enable_pinch_to_zoom_webtoon"` | Boolean, `true` | ReadingModePage.kt, WebtoonConfig.kt | no |
| Disable zoom out | `"webtoon_disable_zoom_out"` | Boolean, `false` | ReadingModePage.kt, WebtoonConfig.kt | no |
| Animate page transitions | `"pref_enable_transitions_webtoon_key"` | Boolean, `true` | ReadingModePage.kt, WebtoonConfig.kt | no |
| **Long strip with gaps** | | | | |
| Tap scroll by page | `"continuous_vertical_tapping_by_page"` | Boolean, `false` | **none found: dead** (upstream SY) | no |
| Crop borders | `"crop_borders_continues_vertical"` | Boolean, `false` | ReaderActivity.kt, ReaderViewModel.kt, ReadingModePage.kt, WebtoonConfig.kt | no |
| **Navigation** | | | | |
| Volume keys | `"reader_volume_keys"` | Boolean, `false` | ViewerConfig.kt | no |
| Invert volume keys | `"reader_volume_keys_inverted"` | Boolean, `false` | ViewerConfig.kt | no |
| **Actions** | | | | |
| Show actions on long tap | `"reader_long_tap"` | Boolean, `true` | GeneralSettingsPage.kt, ViewerConfig.kt | no |
| Save pages into separate folders | `"create_folder_per_manga"` | Boolean, `false` | ReaderViewModel.kt | no |
| **Page Downloading** | | | | |
| Page Preload amount | `"eh_preload_size"` | Int, `10` | HttpPageLoader.kt | no |
| Download threads | `"eh_reader_threads"` | Int, `2` | HttpPageLoader.kt | no |
| Reader cache size | `"eh_cache_size"` | String, `"75"` | ChapterCache.kt | no |
| Aggressively load pages | `"eh_aggressive_page_loading"` | Boolean, `false` | HttpPageLoader.kt | no |
| **Fork Settings** | | | | |
| Skip queue on retry | `"eh_reader_instant_retry"` | Boolean, `true` | HttpPageLoader.kt | no |
| Preserve reading position on read entries | `"eh_preserve_reading_position"` | Boolean, `false` | ChapterLoader.kt, MangaScreenModel.kt, UpdatesScreenModel.kt | no |
| Auto Webtoon Mode | `"eh_use_auto_webtoon"` | Boolean, `true` | GeneralSettingsPage.kt, ReaderActivity.kt, ReaderViewModel.kt | no |
| Reader Bottom Buttons | `"reader_bottom_buttons"` | StringSet, `ReaderBottomButton.BUTTONS_DEFAULTS` | ReaderActivity.kt | no |
| Page layout | `"page_layout"` | Int, `PagerConfig.PageLayout.AUTOMATIC` | PagerConfig.kt, ReaderActivity.kt, ReadingModePage.kt | no |
| Invert double pages | `"invert_double_pages"` | Boolean, `false` | PagerConfig.kt, ReadingModePage.kt | no |
| Center Margin | `"center_margin_type"` | Int, `PagerConfig.CenterMarginType.NONE` | PagerConfig.kt, ReadingModePage.kt | no |
| Archive reader mode | `"archive_reader_mode"` | Int, `ArchiveReaderMode.LOAD_FROM_FILE` | ArchivePageLoader.kt, MoveCacheToDiskSettingMigration.kt | no |

Notes:
- The whole Reader screen and the in-reader settings sheet are upstream; no fork-authored line references a reader
  preference (the fork's reader side sheet/page grid has no settings of its own).
- **Tap scroll by page** has no reader anywhere: dead (upstream SY).
- **Skip filtered chapters** (`skip_filtered`) is *also* the key of `PlayerPreferences.skipFiltered()` (fork port),
  so this reader switch also decides whether the player's next/previous episode skips filtered episodes
  (`PlayerViewModel` ~line 1569). There is no separate player row.
- "Page Downloading", "Fork Settings" (`eh_*`) groups are SY's; **Reader cache size** (`eh_cache_size`) is read when
  `ChapterCache` is created (effectively restart-only).



### Settings › Downloads

`presentation/more/settings/screen/SettingsDownloadScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Only on Wi-Fi | `"pref_download_only_over_wifi_key"` | Boolean, `true` | DownloadJob.kt (manga only) | no |
| Save as CBZ archive | `"save_chapter_as_cbz"` | Boolean, `true` | Downloader.kt | no |
| Split tall images | `"split_tall_images"` | Boolean, `true` | Downloader.kt | no |
| Auto save covers for manga and anime | `"save_covers_in_folders"` | Boolean, `false` | CoverFolders.kt | yes 9532b75f |
| Import covers from download folders | (TextPreference, action) | | | UI added befea648 |
| Concurrent source downloads | `"download_parallel_source_limit"` | Int, `5` | Downloader.kt | no |
| Concurrent page downloads | `"download_parallel_page_limit"` | Int, `5` | Downloader.kt | no |
| **Delete chapters** | | | | |
| After manually marked as read | `"pref_remove_after_marked_as_read_key"` | Boolean, `false` | NotificationReceiver.kt, SetReadStatus.kt | no |
| After reading automatically delete | `"remove_after_read_slots"` | Int, `-1` | ReaderViewModel.kt | no |
| Allow deleting bookmarked chapters | `"pref_remove_bookmarked"` | Boolean, `false` | DownloadManager.kt | no |
| Excluded categories | `"remove_exclude_categories"` | StringSet, `emptySet()` | CategoryPreferencesCleanupMigration.kt, DeleteCategory.kt, DownloadManager.kt | no |
| **Auto-download** | | | | |
| Download new chapters | `"download_new"` | Boolean, `false` | FilterChaptersForDownload.kt, MangaScreenModel.kt | no |
| Skip downloading duplicate read chapters | `"download_new_unread_chapters_only"` | Boolean, `false` | FilterChaptersForDownload.kt | no |
| Categories | (TextPreference, action) | | | no |
| **Download ahead** | | | | |
| Auto download while reading | `"auto_download_while_reading"` | Int, `0` | ReaderViewModel.kt | no |
| (InfoPreference) | (InfoPreference, action) | | | no |
| **Anime** | | | | |
| Download only on Wi-Fi | `"anime_download_only_wifi"` | Boolean, `true` | AnimeDownloadJob.kt (WorkManager network constraint; the switch restarts a running queue) | yes ab039f1b |
| Episodes downloaded at once | `"anime_download_concurrent"` | Int, `1` | AnimeDownloadManager.kt | yes ab039f1b |
| Delete episodes after watching | `"anime_delete_after_seen"` | Boolean, `false` | PlayerViewModel.kt | yes ab039f1b |
| Keep bookmarked episodes | `"anime_delete_keep_bookmarked"` | Boolean, `true` | PlayerViewModel.kt | yes ab039f1b |
| Download ahead while watching | `"anime_download_ahead"` | Int, `0` | PlayerViewModel.kt | yes ab039f1b |
| | (InfoPreference, action) | | | UI added ab039f1b |
| Download new episodes | `"anime_download_new"` | Boolean, `false` | AnimeLibraryUpdateJob.kt | yes ab039f1b |
| Anime categories | (TextPreference, action) | | | UI added ab039f1b |
| **Download cache renew interval** | | | | |
| Download cache renew interval | `"download_cache_renew_interval"` | Int, `1` | DownloadCache.kt | no |
| (InfoPreference) | (InfoPreference, action) | | | no |

Notes:
- **Auto save covers for manga and anime** (`save_covers_in_folders`, Dominican4Life 9532b75f r33, reworked
  c1d306a5 r37): `tachiyomi/data/cache/CoverFolders.kt` (`CoverFolders.enabled`) writes each library title's cover as
  `cover.jpg` in its download folder (anime: plus episode previews) and loads it from there when the app's copy is
  missing. Turning it on saves the whole library's covers.
- **Import covers from download folders** (befea648 r38, progress in r39): one-shot action that copies saved
  `cover.jpg` files back into the app's cover cache; shows "Imported X of Y covers".
- **Anime** group (`getAnimeDownloadGroup`, fork ab039f1b r54), all in `AnimePrefs`:
  - **Download only on Wi-Fi** `anime_download_only_wifi` (`true`): the `AnimeDownloadJob` WorkManager network
    constraint; changing it restarts a non-empty queue. Independent of the manga "Only on Wi-Fi".
  - **Episodes downloaded at once** `anime_download_concurrent` (1, slider 1–5, coerced to `MAX_CONCURRENT`):
    read when the queue starts (`AnimeDownloadManager.runQueue`), so it applies to the next run.
  - **Delete episodes after watching** `anime_delete_after_seen` (`false`) + **Keep bookmarked episodes**
    `anime_delete_keep_bookmarked` (`true`): only for episodes watched in the internal player, when the player
    moves on (`PlayerViewModel` ~2764).
  - **Download ahead while watching** `anime_download_ahead` (0 = off): `PlayerViewModel.downloadAhead`.
  - **Download new episodes** `anime_download_new` + **Anime categories** (`anime_download_new_categories[_exclude]`):
    used by `AnimeLibraryUpdateJob` for new episodes it finds.
- Manga "Concurrent source/page downloads" are upstream and don't affect anime downloads.



### Settings › Tracking

`presentation/more/settings/screen/SettingsTrackingScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Open track menu on adding to library | `"track_on_adding_to_library"` | Boolean, `true` | MangaScreenModel.kt | no |
| Update progress after reading | `"pref_auto_update_manga_sync_key"` | Boolean, `true` | AnimeTracking.kt, ReaderViewModel.kt | no |
| Update progress when marked as read | `"pref_auto_update_manga_on_mark_read"` | Enum, `AutoTrackState.ALWAYS` | MangaScreenModel.kt | no |
| Auto sync progress from Trackers | `"pref_auto_sync_progress_from_trackers_key"` | Boolean, `true` | MangaScreenModel.kt | no |
| Select entries using source metadata | `"pref_resolve_using_source_metadata_key"` | Boolean, `true` | TrackInfoDialog.kt | no |
| **Trackers** | | | | |
| MyAnimeList (log in/out) | tracker login, stored by the tracker | | | no |
| AniList (log in/out) | tracker login, stored by the tracker | | | no |
| Kitsu (log in/out) | tracker login, stored by the tracker | | | no |
| MangaUpdates (log in/out) | tracker login, stored by the tracker | | | no |
| Shikimori (log in/out) | tracker login, stored by the tracker | | | no |
| Simkl (anime-only) (log in/out) | tracker login, stored by the tracker | | | **yes** b9103f1f |
| Bangumi (log in/out) | tracker login, stored by the tracker | | | no (block moved by 8c36b0c7) |
| (info text "tracking_info") | | | | moved by 8c36b0c7 |
| **Enhanced trackers** | | | | |
| one row per enhanced tracker (`EnhancedTracker`s in `trackerManager.trackers`) | tracker login | | | no |
| (InfoPreference) | (InfoPreference, action) | | | no |

Notes:
- The fork added **Simkl** (anime-only, b9103f1f r53) into the main tracker list ("Aniyomi's place in the list",
  8c36b0c7 r57). An "Anime trackers" group with Simkl and Jellyfin existed between r53 and r57 and was merged away.
- "Update progress after reading" (`pref_auto_update_manga_sync_key`) is also read by the fork's
  `tachiyomi/data/anime/track/AnimeTracking.kt`, so it controls anime progress updates after watching too.
- Hidden: `__PRIVATE_anime_track_pending` (`TrackPreferences.animeTrackPending()`, e2691c10): anime track updates
  waiting to be sent (offline queue), read by `AnimeTracking.kt`.



### Settings › Browse

`presentation/more/settings/screen/SettingsBrowseScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| **Sources** | | | | |
| Suggestions | `"related_mangas"` | Boolean, `true` | AnimeScreen.kt, AnimeScreenModel.kt, MangaScreen.kt, MangaScreenModel.kt | no |
| Expand Suggestions | `"expand_related_mangas"` | Boolean, `true` | AnimeScreen.kt, MangaScreen.kt, MangaScreenModel.kt | no |
| Suggestions in overflow | `"related_mangas_in_overflow"` | Boolean, `false` | AnimeScreen.kt, MangaScreen.kt | no |
| Home from Suggestions | `"show_home_on_related_mangas"` | Boolean, `true` | MangaToolbar.kt | no |
| Edit categories | `"sources_tab_categories"` | StringSet, `mutableSetOf()` | CreateSourceCategory.kt, DeleteSourceCategory.kt, GetSourceCategories.kt, RenameSourceCategory.kt | no |
| Filter sources in categories | `"sources_tab_categories_filter"` | Boolean, `false` | GetEnabledSources.kt | no |
| Replace latest button | `"use_new_source_navigation"` | Boolean, `false` | BrowseTab.kt, GetShowLatest.kt, MangaScreenModel.kt, SourcesScreenModel.kt, UnifiedExtensionsTab.kt | **changed**: default true → false (ee503f7b) |
| Local source hidden folders | `"allow_local_source_hidden_folders"` | Boolean, `false` | AndroidSourceManager.kt | no |
| **Feed** | | | | |
| Hide Feed tab | `"hide_latest_tab"` | Boolean, `false` | BrowseTab.kt | no |
| Feed tab position | `"latest_tab_position"` | Boolean, `false` | BrowseTab.kt | no |
| Hide entries already in library | `"feed_hide_in_library_items"` | Boolean, `false` | FeedScreenModel.kt, SourceFeedScreenModel.kt | no |
| **Sources** | | | | |
| Hide entries already in library | `"browse_hide_in_library_items"` | Boolean, `false` | BrowseSourceScreenModel.kt | no |
| Extension stores | (TextPreference, action) | | | no |
| **NSFW (18+) sources** | | | | |
| Show in sources and extensions lists | `"show_nsfw_source"` | Boolean, `true` | AnimeExtensionLoader.kt, ExtensionLoader.kt, GetExtensionsByType.kt, UnifiedExtensionsScreenModel.kt | no |
| (InfoPreference) | (InfoPreference, action) | | | no |

Notes:
- **Replace latest button**: the fork changed the default from `true` to `false` (ee503f7b, Oct 9). Users who never
  touched it got the old Latest button back.
- **Hide Feed tab** (`hide_latest_tab`): `tachiyomi/ui/browse/BrowseTab.kt` forces it back to `false` once, guarded
  by the hidden flag `kmk_feed_tab_restored` (4735057d, "Feed tab restore"). After that it works normally.
- **Show in sources and extensions lists** (NSFW) says "Requires app restart": it is read by `ExtensionLoader` /
  `AnimeExtensionLoader` when extensions are loaded. The fork's unified Extensions screen also reads it live.
- Not on this screen but Browse-related fork settings live in menus: see "Browse source display menu" and the
  hidden list (`anime_hidden_catalogues`, `anime_pinned_catalogues`, `anime_disabled_languages` = the anime twins of
  `hidden_catalogues`, `pinned_catalogues`, `source_languages`).



### Settings › Data and storage

`presentation/more/settings/screen/SettingsDataScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Storage location | `"storage_dir" (appState)` | String, `folderProvider.path()` | StorageManager.kt, StorageStep.kt | no |
| (InfoPreference) | (InfoPreference, action) | | | no |
| **Backup and restore** | | | | |
| Create backup / Restore backup (two buttons) | action; options are per run (`BackupOptions`), not stored | | | no (screens edited by fork: JS sources option, category choice, 9532b75f) |
| Automatic backup frequency | `"backup_interval"` | Int, `12` | AlwaysBackupMigration.kt, BackupCreateJob.kt | no |
| (InfoPreference) | `"last_auto_backup_timestamp" (appState)` | Long, `0L` | BackupCreator.kt | no |
| Show restoring progress banner | `"pref_show_restoring_progress_banner_key" (appState)` | Boolean, `true` | **none found: dead since the fork removed the restore bar (2c614de7, r33)** | changed (made dead) |
| **Storage usage** | | | | |
| Storage usage | (CustomPreference, action) | | | no |
| Clear chapter cache | (TextPreference, action) | | | no |
| Clear page preview cache | (TextPreference, action) | | | no |
| Clear chapter cache on app launch | `"auto_clear_chapter_cache"` | Boolean, `false` | MainActivity.kt | no |
| **Export** | | | | |
| Library List | (TextPreference, action) | | | no |
| **Sync** | | | | |
| Service | `"sync_service"` | Int, `0` | LibraryScreenModel.kt, SyncManager.kt | no |
| Host | `"connection_sync_client_host"` | String, `"https://sync.tachiyomi.org"` | SyncYomiSyncService.kt | no |
| API key | (CustomPreference, action) | | | no |
| Sign in | (TextPreference, action) | | | no |
| Clear Sync Data from Google Drive | (TextPreference, action) | | | no |
| WebDAV Server URL | `"connection_webdav_url"` | String, `""` | WebDavSyncService.kt | no |
| Username | `"connection_webdav_username"` | String, `""` | WebDavSyncService.kt | no |
| Password | (TextPreference, action) | | | no |
| WebDAV data folder | `"connection_webdav_folder"` | String, `"komikku"` | WebDavSyncService.kt | no |
| Choose what to sync | (TextPreference, action) | | | no |
| **Sync Actions** | | | | |
| Create sync triggers | (TextPreference, action) | | | no |
| Sync now | (TextPreference, action) | | | no |
| **Automatic Synchronization** | | | | |
| Synchronization frequency | `"sync_interval"` | Int, `0` | SyncDataJob.kt | no |
| (InfoPreference) | `"last_sync_timestamp" (appState)` | Long, `0L` | SyncManager.kt | no |
| Show syncing progress banner | `"pref_show_syncing_progress_banner_key" (appState)` | Boolean, `true` | MainActivity.kt | no |

Notes:
- **Show restoring progress banner** is dead since the fork removed the restore top bar in r33 (2c614de7, "no
  restore bar"; the bar redrew the whole app for every title). Restore progress is only the notification now.
- Backup creation (`presentation/more/settings/screen/data/CreateBackupScreen.kt`) got two fork options that are per run, not settings: a **JS sources**
  checkbox (`BackupOptions.jsSources`) and a **category choice** card (`BackupCategoryChoice.kt`: ticking all = no
  filter, as before). Restore (`RestoreBackupScreen.kt`) was also changed (Anikku/Aniyomi backups, r55).
- Bluetooth pair sync (a4a9e23c) is not here; it has its own screen (`tachiyomi/ui/pairsync/PairSyncScreen.kt`) and
  keeps `pair_sync_peers` / `pair_sync_selection` (hidden list).



### Settings › Security and privacy

`presentation/more/settings/screen/SettingsSecurityScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| **Security** | | | | |
| Require unlock | `"use_biometric_lock"` | Boolean, `false` | BaseUpdatesGridGlanceWidget.kt, SecureActivityDelegate.kt, WidgetManager.kt | no |
| Lock when idle | `"lock_app_after"` | Int, `0` | SecureActivityDelegate.kt | no |
| Hide notification content | `"hide_notification_content"` | Boolean, `false` | BackupNotifier.kt, DownloadNotifier.kt, EHentaiUpdateNotifier.kt, ExtensionUpdateNotifier.kt, LibraryUpdateNotifier.kt … | no |
| Secure screen | `"secure_screen_v2"` | Enum, `SecureScreenMode.INCOGNITO` | MoveSecureScreenSettingMigration.kt, SecureActivityDelegate.kt | no |
| Password protect downloads | `"password_protect_downloads" (private)` | Boolean, `false` | CbzCrypto.kt | no |
| Encryption type | `"encryption_type"` | Enum, `EncryptionType.AES_256` | CbzCrypto | no |
| Set CBZ archive password | (TextPreference, action) | | | no |
| Delete CBZ archive password | (TextPreference, action) | | | no |
| Edit lock times | (TextPreference, action) | | | no |
| Biometric lock days | (TextPreference, action) | | | no |
| (InfoPreference) | (InfoPreference, action) | | | no |
| **Analytics and Crash logs** | | | | |
| Send crash logs | `"crashlytics"` | Boolean, `true` | App.kt, PermissionStep.kt | no |
| (InfoPreference) | (InfoPreference, action) | | | no |

Notes: all upstream. **Hide notification content** is also honoured by the fork's notifiers that go through the
upstream helpers (BackupNotifier etc.); fork-only notifications (bot-check `BotCheckNotifier`, anime downloads)
were not checked for it.



### Settings › E-Hentai (only when "Enable integrated hentai features" is on)

`presentation/more/settings/screen/SettingsEhScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Use Hentai@Home Network | `"eh_enable_hah"` | Int, `0` | EhUConfigBuilder.kt | no |
| Show Japanese titles in search results | `"use_jp_title"` | Boolean, `false` | EHentaiSearchMetadata.kt, EhUConfigBuilder.kt (name shared with another class; list approximate) | no |
| The quality of the downloaded images | `"ehentai_quality"` | String, `"auto"` | EhUConfigBuilder.kt | no |
| **Source settings** | | | | |
| Incognito mode | `"eh_incognito_mode"` | Boolean, `false` | **none found: dead** (upstream) | no |
| **E-Hentai Website Account Settings** | | | | |
| Enable ExHentai | `"enable_exhentai" (private)` | Boolean, `false` | AndroidSourceManager.kt, BatchAddScreenModel.kt, EHentai.kt, EhLoginActivity.kt, FavoritesSyncHelper.kt … | no |
| Use original images | `"eh_useOrigImages"` | Boolean, `false` | EhUConfigBuilder.kt | no |
| Watched Tags | (TextPreference, action) | | | no |
| Tag Filtering Threshold | `"eh_tag_filtering_value"` | Int, `0` | EhUConfigBuilder.kt | no |
| Tag Watching Threshold | `"eh_tag_watching_value"` | Int, `0` | EhUConfigBuilder.kt | no |
| Language Filtering | (TextPreference, action) | | | no |
| Front Page Categories | (TextPreference, action) | | | no |
| Watched List Filter Default State | `"eh_watched_list_default_state"` | Boolean, `false` | EHentai.kt | no |
| Enhanced E/ExHentai browse | `"enhanced_e_hentai_view"` | Boolean, `true` | BrowseSourceScreenModel.kt | no |
| **E-Hentai Favorites sync** | | | | |
| Disable favorites uploading | `"eh_sync_read_only"` | Boolean, `false` | FavoritesSyncHelper.kt | no |
| Show favorites sync notes | (TextPreference, action) | | | no |
| Ignore sync errors when possible | `"eh_lenient_sync"` | Boolean, `false` | FavoritesSyncHelper.kt | no |
| Force sync state reset | (TextPreference, action) | | | no |
| **Gallery update checker** | | | | |
| Time between update batches | `"eh_auto_update_frequency"` | Int, `1` | EHentaiUpdateWorker.kt | no |
| Auto update restrictions | `"eh_auto_update_restrictions"` | StringSet, `emptySet()` | EHentaiUpdateWorker.kt | no |
| Show updater statistics | (TextPreference, action) | | | no |

Notes: all upstream SY. **Incognito mode** (`eh_incognito_mode`) has no reader in this build: dead. Untitled rows at
the top (Hentai@Home, Japanese titles, image quality) belong to the first group.



### Settings › MangaDex (only when a MangaDex source is enabled)

`presentation/more/settings/screen/SettingsMangadexScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| mdex.name + " Login" | (CustomPreference, action) | | | no |
| Preferred MangaDex source | `"preferred_mangaDex_id"` | String, `"0"` | MdUtil.kt | no |
| Sync MangaDex entries to your library | (TextPreference, action) | | | no |
| Sync library entries to MangaDex | (TextPreference, action) | | | no |

Notes: upstream SY. Shown only when a MangaDex source is enabled.



### Settings › Advanced

`presentation/more/settings/screen/SettingsAdvancedScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Share crash logs | (TextPreference, action) | | | no |
| Debug info | (TextPreference, action) | | | no |
| Onboarding guide | (TextPreference, action) | | | no |
| Manage notifications | (TextPreference, action) | | | no |
| Auto-update app | `"should_auto_update"` | StringSet, `setOf( AppUpdatePolicy.DEVICE_ONLY_ON_WIFI, )` | AppUpdateChecker.kt, AppUpdateDownloadJob.kt | no |
| **Background activity** | | | | |
| Disable battery optimization | (TextPreference, action) | | | no |
| "Don't kill my app!" | (TextPreference, action) | | | no |
| **Data** | | | | |
| Reindex downloads | (TextPreference, action) | | | no |
| Clear Anime database | action → `ClearAnimeDatabaseScreen` | | | **yes** 8c36b0c7 |
| Clear Manga database | action → `ClearDatabaseScreen` (upstream "Clear database", renamed) | | | changed 8c36b0c7 |
| **Networking** | | | | |
| Clear cookies | (TextPreference, action) | | | no |
| Clear WebView data | (TextPreference, action) | | | no |
| Open bot checks automatically | `"auto_open_bot_checks"` | Boolean, `true` | BotCheckNotifier.kt | yes 3db8eea1 |
| DNS over HTTPS (DoH) | `"doh_provider"` | Int, `-1` | MoveDOHSettingMigration.kt, NetworkHelper.kt | no |
| Default user agent string | `"default_user_agent"` | String, `"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTM` | NetworkHelper.kt | no |
| Reset default user agent string | `"default_user_agent"` | String, `"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTM` | NetworkHelper.kt | no |
| **Library** | | | | |
| Refresh library covers | (TextPreference, action) | | | no |
| Preload library's cover color | `"pref_preload_library_color_key"` | Boolean, `true` | MangaCoverFetcher.kt | no |
| Reset per-series reader settings | (TextPreference, action) | | | no |
| Update library manga titles to match source | `"pref_update_library_manga_titles"` | Boolean, `false` | UpdateMangaFromRemote.kt | no |
| Disallow non-ASCII filenames | `"disallow_non_ascii_filenames"` | Boolean, `false` | DownloadProvider.kt | no |
| **Downloads** | | | | |
| Include chapter URL hash | `"download_include_chapter_url_hash"` | Boolean, `false` | ChapterUrlHashMigration.kt, DownloadProvider.kt | no |
| **Reader** | | | | |
| Custom hardware bitmap threshold | `"pref_hardware_bitmap_threshold"` | Int, `GLUtil.SAFE_TEXTURE_LIMIT` | App.kt | no |
| Use legacy decoder for long strip reader | `"pref_always_decode_long_strip_with_ssiv"` | Boolean, `false` | ReaderPageImageView.kt | no |
| Custom display profile | `"pref_display_profile_key"` | String, `""` | ReaderActivity.kt | no |
| **Extensions** | | | | |
| Installer | `"extension_installer"` | Enum (`ExtensionInstallerPreference`) | ExtensionInstaller, extension install code | no (KMK info text below it is upstream) |
| (InfoPreference) | (InfoPreference, action) | | | no |
| Revoke trusted unknown extensions | (TextPreference, action) | | | no |
| **Data Saver** | | | | |
| Data Saver | `"data_saver"` | Enum, `DataSaver.NONE` | DataSaver.kt, MangaDex.kt, SourcesScreenModel.kt | no |
| Bandwidth Hero Proxy Server | `"data_saver_server"` | String, `""` | DataSaver.kt | no |
| Use data saver in the downloader | `"data_saver_downloader"` | Boolean, `true` | Downloader.kt | no |
| Ignore Jpeg Images | `"ignore_jpeg"` | Boolean, `false` | DataSaver.kt | no |
| Ignore Gif Animations | `"ignore_gif"` | Boolean, `true` | DataSaver.kt | no |
| Image Quality | `"data_saver_image_quality"` | Int, `80` | DataSaver.kt | no |
| Compress to Jpeg | `"data_saver_image_format_jpeg"` | Boolean, `false` | DataSaver.kt | no |
| Convert to Black And White | `"data_saver_color_bw"` | Boolean, `false` | DataSaver.kt | no |
| **Developer tools** | | | | |
| Enable integrated hentai features | `"eh_is_hentai_enabled"` | Boolean, `false` | AndroidSourceManager.kt, BatchAddScreenModel.kt, BrowseSourceScreen.kt, ExtensionApi.kt, ExtensionManager.kt … | no |
| Enable source blacklist | `"eh_enable_source_blacklist"` | Boolean, `true` | EHDebugModeOverlay.kt, ExtensionApi.kt, ExtensionManager.kt | no |
| Enable delegated sources | `"eh_delegate_sources"` | Boolean, `true` | EnhancedHttpSource.kt, HttpSource.kt, MoreScreen.kt, NetworkHelper.kt | no |
| Log level | `"eh_log_level"` | Int, `EHLogLevel.defaultLogLevel(isDebugBuildType)` | App.kt | no |
| Encrypt database | `"encrypt_database"` (appState) | Boolean, `false` | database driver at app start (restart) | no |
| Open debug menu | (TextPreference, action) | | | no |
| **Downloader** | | | | |
| Clean up downloaded chapters | (TextPreference, action) | | | no |

Notes:
- **Open bot checks automatically** (`auto_open_bot_checks`, default `true`, fork 3db8eea1 r57): read by
  `tachiyomi/data/notification/BotCheckNotifier.openInWebView`. When on and the app is in the foreground, a Cloudflare/bot
  check the app can't pass alone opens in WebView, closes when passed, and the request is retried. When off, only a
  per-source notification is posted. AppLog tag **`botcheck`**: `not opened: "Open bot checks automatically" is off`.
- **Clear Anime database / Clear Manga database** (8c36b0c7 r57): `presentation/more/settings/screen/advanced/ClearAnimeDatabaseScreen.kt` is new; the
  manga one is upstream's "Clear database" renamed.
- **Verbose logging** is commented out (SY) and not shown; its getter is commented out in `NetworkPreferences`.
- Restart-only here: DNS over HTTPS, user agent (+ reset), Revoke trusted extensions (toast), Enable source
  blacklist and Log level (summary says force-restart), Encrypt database.
- **Installer** has a KMK info row (`pref_private_installer_warning`) under it (upstream KMK).



### Settings › Discord (Connections) — not reachable in this build

`presentation/more/settings/screen/SettingsDiscordScreen.kt`

Reached only from `SettingsConnectionScreen`, which nothing opens since 39f455aa. `DiscordRPCService` still reads
these keys, but with `pref_enable_discord_rpc` defaulting to `false` and no way to turn it on, Rich Presence is off
for every new install. (`MainActivity`/`ReaderActivity` still check it, so a restored backup with it on would probably start it; not tested.)

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Discord Accounts | (TextPreference, action) | | | no |
| **Discord** | | | | |
| Enable Discord Rich Presence | `"pref_enable_discord_rpc"` | Boolean, `false` | DiscordRPCService.kt, MainActivity.kt, ReaderActivity.kt | no |
| Discord Status | `"pref_discord_rpc_status"` | Int, `1` | DiscordRPCService.kt, MainActivity.kt | no |
| **Categories** | | | | |
| Discord Incognito mode | `"pref_discord_rpc_incognito"` | Boolean, `false` | DiscordRPCService.kt | no |
| Categories | `"discord_rpc_incognito_categories"` | StringSet, `emptySet()` | DiscordRPCService.kt | no |
| (InfoPreference) | (InfoPreference, action) | | | no |
| **Discord Customization** | | | | |
| Custom Message | (TextPreference, action) | | | no |
| Show Progress | `"pref_discord_show_progress"` | Boolean, `true` | DiscordRPCService.kt | no |
| Show Chapter Titles | `"pref_discord_rpc_use_chapter_titles"` | Boolean, `false` | DiscordRPCService.kt, ReaderActivity.kt | no |
| Show Time | `"pref_discord_show_timestamp"` | Boolean, `true` | DiscordRPCService.kt | no |
| Show Buttons | `"pref_discord_show_buttons"` | Boolean, `true` | DiscordRPCService.kt | no |
| Show Download Button | `"pref_discord_show_download_button"` | Boolean, `true` | DiscordRPCService.kt | no |
| Show Discord Button | `"pref_discord_show_discord_button"` | Boolean, `true` | DiscordRPCService.kt | no |
| Log out | (CustomPreference, action) | | | no |

### Player settings (More › Player settings)

All player settings were added with the AniZen port (23ba5816, 2026-10-08) and resynced to AniZen 0.5.218 in r56
(1546a765, aadefd3a, 403d7446). r56 ("the 7 player settings work", 12919569) wired up settings that were shown but
did nothing until then: **Preload next episode, Self-healing links, Intelligent buffer handoff, Network-aware
playback, Performance profile, Adaptive Performance Scaling, Disable auto subtitle selection**. Settings in
`tachiyomi/ui/player/settings/*Preferences.kt`; most are read in `tachiyomi/ui/player/PlayerActivity.kt` (`setupPlayerMPV`, on create),
`tachiyomi/ui/player/AniyomiMPVView.kt` (mpv options at init), `tachiyomi/ui/player/PlayerViewModel.kt` and `tachiyomi/ui/player/controls/*`.
**Most take effect the next time the player opens.** The player keeps its own ring buffer of 400 lines,
`tachiyomi/ui/player/PlayerDebugLog.kt` (tags `player`, `network`, …), e.g. `[player] Cache: 64 MiB, read-ahead 1 s`,
`[network] Metered network: '1080p' -> '720p'`. It is **not** in the Logs screen: the owner gets it from the
"Playback problem" dialog's **Copy log** button (`PlayerActivity` ~1714).


### Settings › Player › Internal player

`presentation/more/settings/screen/player/PlayerSettingsPlayerScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| At what point to mark the episode as seen | `"pref_progress_preference"` | Float, `0.85F` | AnimeScreenModel.kt, PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Preserve watch position on seen episodes | `"pref_preserve_watching_position"` | Boolean, `false` | ExternalPlayer.kt, PlayerActivity.kt | yes (AniZen port 23ba5816) |
| **Cast Settings** | | | | |
| Enable or disable Cast | `"pref_enable_cast"` | Boolean, `false` | CastManager.kt, HomeCastMiniController.kt, PlayerButtons.kt | yes (AniZen port 23ba5816) |
| Casting needs Google Play services, which aren't available on this device | (InfoPreference, action) | | | UI added 1546a765 |
| Orientation | `"pref_default_player_orientation_type_key"` | Enum, `PlayerOrientation.SensorLandscape` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| **Controls** | | | | |
| Allow gesture in panels | `"pref_allow_gestures_in_panels"` | Boolean, `false` | GestureHandler.kt | yes (AniZen port 23ba5816) |
| Show loading circle | `"pref_show_loading"` | Boolean, `true` | PlayerControls.kt | yes (AniZen port 23ba5816) |
| Show current chapter indicator | `"pref_show_current_chapter"` | Boolean, `true` | PlayerButtons.kt | yes (AniZen port 23ba5816) |
| Remember and switch to the last used brightness | `"pref_remember_brightness"` | Boolean, `false` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| Remember and switch to the last used volume | `"pref_remember_volume"` | Boolean, `false` | **none found: dead** (and `player_volume_value` is never written) | yes (AniZen port 23ba5816) |
| Remember custom aspect ratio globally | `"pref_remember_aspect_ratio"` | Boolean, `false` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| **Hosters** | | | | |
| Preferred quality | `"pref_preferred_quality"` | String, `"1080"` | HlsVariants.kt (VideoComparator.kt reads it too, but that class is never constructed) | yes (AniZen port 23ba5816) |
| Remember default stream per anime | `"pref_per_anime_default_stream"` | Boolean, `true` | DefaultStreamPreferenceStore.kt, PlayerControls.kt | yes (AniZen port 23ba5816) |
| Highlight default in Qualities | `"pref_show_default_stream_highlight"` | Boolean, `true` | DefaultStreamPreferenceStore.kt, PlayerControls.kt | yes (AniZen port 23ba5816) |
| Auto-scroll to default in Qualities | `"pref_auto_scroll_default_stream"` | Boolean, `true` | DefaultStreamPreferenceStore.kt, PlayerControls.kt | yes (AniZen port 23ba5816) |
| Display failed hosters | `"pref_show_failed_hosters"` | Boolean, `false` | EpisodeOptionsDialog.kt, PlayerControls.kt | yes (AniZen port 23ba5816) |
| Display empty hosters | `"pref_show_empty_hosters"` | Boolean, `false` | EpisodeOptionsDialog.kt, PlayerControls.kt | yes (AniZen port 23ba5816) |
| Clear saved stream defaults | (TextPreference, action) | | | UI added 23ba5816 |
| **Performance** | | | | |
| Performance profile | `"pref_performance_profile"` | Enum, `PlayerEfficiency.Automatic` | PlayerActivity.kt, PlayerProfiles.kt | yes (AniZen port 23ba5816) |
| Preload next episode | `"pref_preload_mode_key"` | Enum, `PreloadMode.WifiOnly` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Network-aware playback | `"pref_network_aware_throttling"` | Boolean, `true` | PlayerActivity.kt, PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Self-healing links | `"pref_self_healing_links"` | Boolean, `true` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Intelligent buffer handoff | `"pref_intelligent_buffer_handoff"` | Boolean, `false` | PlayerActivity.kt, PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| **Display** | | | | |
| Show content in display cutout | `"player_fullscreen"` | Boolean, `true` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| Hide player controls when opening the player | `"player_hide_controls"` | Boolean, `false` | GestureHandler.kt, PlayerActivity.kt, PlayerControls.kt, PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Display volume as percentage | `"pref_display_vol_as_per"` | Boolean, `true` | PlayerControls.kt | yes (AniZen port 23ba5816) |
| Show system status bar with controls | `"pref_show_system_status_bar"` | Boolean, `false` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Reduce player animation | `"pref_reduce_motion"` | Boolean, `false` | PlayerControls.kt | yes (AniZen port 23ba5816) |
| Hide player controls time | `"pref_player_time_to_disappear"` | Int, `4000` | PlayerControls.kt | yes (AniZen port 23ba5816) |
| Panel opacity | `"pref_panel_opacity"` | Int, `60` | PlayerPanels.kt | yes (AniZen port 23ba5816) |
| **Intro skip** | | | | |
| Enable intro skip | `"pref_enable_skip_intro"` | Boolean, `true` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Enable auto skip | `"pref_enable_auto_skip_ani_skip"` | Boolean, `false` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Enable Netflix style | `"pref_enable_netflixStyle_aniskip"` | Boolean, `false` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Button timeout | `"pref_waiting_time_aniskip"` | Int, `5` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Enable AniSkip | `"pref_enable_ani_skip"` | Boolean, `false` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| Disable AniSkip if video already contains chapters | `"pref_disabled_ani_skip_chapters"` | Boolean, `true` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| AniSkip requires the anime to be tracked with MAL or Anilist to work | (InfoPreference, action) | | | UI added 23ba5816 |
| **Filler Skip** | | | | |
| Skip Filler Episodes | `"pref_skip_filler_episodes"` | Boolean, `false` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| **Picture-in-Picture (PiP)** | | | | |
| Enable the use of PiP mode | `"pref_enable_pip"` | Boolean, `true` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| Show episode toasts when switching episodes in PiP mode | `"pref_pip_episode_toasts"` | Boolean, `true` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| Automatically switch to PiP mode on exiting the player | `"pref_pip_on_exit"` | Boolean, `false` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| Replaces the "Skip 10 seconds" option with "Previous episode" | `"pip_replace_with_previous"` | Boolean, `false` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| **External player** | | | | |
| Always use external player | `"pref_always_use_external_player"` | Boolean, `false` | AniListPlayback.kt, AnimeHistoryUpdatesPanes.kt, AnimeScreen.kt, AnimeSourceScreen.kt | yes (AniZen port 23ba5816) |
| External player preference | `"external_player_preference"` | String, `""` | ExternalPlayer.kt | yes (AniZen port 23ba5816) |
| **TorrentServer Preferences** | | | | |
| TorrentServer Port | `"pref_torrent_port"` | String, `"8090"` | TorrentServerUtils.hostUrl (a `val`, read once → restart) | yes 39f455aa |
| Torrent Trackers | `"pref_torrent_trackers"` | String, `"""http://nyaa.tracker.wf:7777/announce http://anidex.moe:69` | TorrentServerUtils.kt | yes 39f455aa |
| Reset default torrent trackers string | `"pref_torrent_trackers"` | String, `"""http://nyaa.tracker.wf:7777/announce http://anidex.moe:69` | TorrentServerUtils.kt | yes 39f455aa |
| **Cast Server** | | | | |
| Cast Server Port | `"pref_cast_server_port"` | String, `"8181"` | LocalHttpServerService.kt, CastMediaBuilder.kt | yes 9f786264 |

Notes:
- **At what point to mark the episode as seen** (`pref_progress_preference`, Float 0.70–1.00, default 0.85) is
  also used by the anime page (`AnimeScreenModel`) to show progress.
- **Enable or disable Cast** (`pref_enable_cast`): group disabled with an info row when Google Play services are
  missing (1546a765). Read by `CastManager`, the player's Cast button and the home mini controller.
- **Remember … last used volume** is dead: nothing reads `pref_remember_volume` and nothing writes
  `player_volume_value`. (Brightness works: `PlayerActivity` reads/writes `player_brightness_value`.)
- **Remember custom aspect ratio globally** works with hidden `pref_player_last_aspect_ratio` /
  `pref_last_aspect_ratio_anime_id` (`PlayerViewModel`).
- **Preferred quality** (`pref_preferred_quality`, "1080"; digits are taken, so "1080p" = 1080) only decides which
  rendition is picked when one stream holds several qualities (HLS master playlists split by
  `tachiyomi/ui/player/loader/HlsVariants.kt`, and multi-quality downloads, r83). The choice of *which video in the hoster
  list* plays first comes from the source's own order plus the saved default stream. `VideoComparator` (which also
  reads it and the audio languages) is never constructed: dead code.
- **Remember default stream per anime** (`pref_per_anime_default_stream`, master switch) + **Highlight default** +
  **Auto-scroll to default** + **Clear saved stream defaults**: `tachiyomi/ui/player/utils/DefaultStreamPreferenceStore.kt`.
  Tapping a stream in Qualities saves a selector per anime into the hidden map `pref_per_anime_default_stream_data`
  (encoded "animeId → selector"), with `pref_default_stream_selector` as the global fallback. Turning the master
  switch off disables saving, auto-load *and* highlight, whatever the other two say.
- **Performance profile** (`pref_performance_profile`, `PlayerEfficiency`): `tachiyomi/ui/player/PlayerProfiles.kt`
  `effectiveVideoSettings()` **overrides** the Decoder screen while a profile is picked:
  - Custom (`Automatic`): Decoder settings as set, Anime4K up to High, cache ×1.
  - Best quality (`MaxPerformance`): high-quality scaling forced on, cache ×2, rest as set.
  - Balanced: hardware decoding on, HQ scaling on, smooth motion off, Anime4K capped at Fast.
  - Power saver: hardware decoding on, HQ scaling off, smooth motion off, Anime4K off, cache ×0.5.
  The Decoder switches still show their own values, so the screen can look "on" while the profile has it off.
- **Preload next episode** (`pref_preload_mode_key`: Off / Wi-Fi only (default) / Always): near 80 % of an
  episode (or with < 2 min left) the next episode's hosters are fetched (`maybePreloadNextEpisode`).
- **Network-aware playback** (`pref_network_aware_throttling`, `true`): on a metered network, online episodes are
  capped to the best ≤ 720p video (`capQualityIndex`), the demuxer cache is halved (16–32 MiB, read-ahead 1 s,
  `PlayerActivity.applyStreamCache`), and preloading waits while the current video buffers. It **overrides
  Preferred quality** on mobile data.
- **Self-healing links** (`true`): when a link fails, fresh links are fetched from the source and playback resumes
  at the same position (`PlayerViewModel` ~2511).
- **Intelligent buffer handoff** (`false`): on quality/server switch keeps position and paused state, and enlarges
  the cache ×1.5 with 20 s read-ahead (also applies when not switching).
- **Show content in display cutout** is `player_fullscreen` (the key name is historical).
- **Enable AniSkip** needs the anime tracked on MAL or AniList (info row). **Default skip intro length** (Gestures
  screen) is the fallback length; a per-anime length is kept in hidden `anime_skip_intro_length_<animeId>`.
- **External player**: **Always use external player** is read by every play entry point (anime page, History/Updates
  panes, anime source screen, AniList playback), so turning it on bypasses the internal player and therefore every
  internal-player setting (delete after watching, download ahead, AniSkip…). `pref_use_external_downloader`
  exists but has no row and no reader: dead.
- **Torrent server** (OmfgItsJesus 39f455aa, UI 79f82694): port `pref_torrent_port` ("8090") and trackers
  `pref_torrent_trackers` (multi-line). `TorrentServerUtils.hostUrl` is a `val` built once from the port, hence the
  "requires app restart" toasts; changes also stop `TorrentServerService`.
- **Cast server port** `pref_cast_server_port` ("8181", `tachiyomi/util/LocalHttpServerHolder.kt`, 1024–65535 accepted):
  the local HTTP server that serves downloaded episodes / relayed HLS to the Chromecast; a change stops
  `LocalHttpServerService` so it restarts on the new port next cast. Torrent and cast ports must differ.



### Settings › Player › Gestures

`presentation/more/settings/screen/player/PlayerSettingsGesturesScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| **Sliders** | | | | |
| Enable Volume and Brightness Gestures | `"pref_gesture_volume_brightness"` | Boolean, `true` | GestureHandler.kt | yes (AniZen port 23ba5816) |
| Swap volume and brightness slider | `"pref_swap_volume_and_brightness"` | Boolean, `false` | GestureHandler.kt, PlayerControls.kt | yes (AniZen port 23ba5816) |
| Pinch-to-Zoom Gesture | `"pref_gesture_video_zoom"` | Boolean, `false` | GestureHandler.kt, VideoZoomSheet.kt | yes (AniZen port 23ba5816) |
| **Seeking** | | | | |
| Enable Horizontal Seek Gesture | `"pref_gesture_horizontal_seek"` | Boolean, `true` | GestureHandler.kt | yes (AniZen port 23ba5816) |
| Show seekbar when seeking | `"pref_show_seekbar"` | Boolean, `false` | GestureHandler.kt, PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Default skip intro length | `"pref_default_intro_length"` | Int, `85` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Double tap to skip length | `"pref_skip_length_preference"` | Int, `10` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Enable precise seeking | `"pref_player_smooth_seek"` | Boolean, `false` | GestureHandler.kt, PlayerControls.kt, PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| **Double tap** | | | | |
| Show double tap background | `"pref_show_double_tap_ovals"` | Boolean, `true` | PlayerControls.kt | yes (AniZen port 23ba5816) |
| Show seek icon | `"pref_show_seek_icon"` | Boolean, `true` | PlayerControls.kt | yes (AniZen port 23ba5816) |
| Show seek time while seeking | `"pref_show_seek_time_while_seeking"` | Boolean, `true` | PlayerControls.kt | yes (AniZen port 23ba5816) |
| Left double tap | `"pref_left_double_tap"` | Enum, `SingleActionGesture.Seek` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Double tap (center) | `"pref_center_double_tap"` | Enum, `SingleActionGesture.PlayPause` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Double tap (right) | `"pref_right_double_tap"` | Enum, `SingleActionGesture.Seek` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| When a tap gesture is set to "Custom", it can be bound through input.conf. The key codes are 0x10001 for left, 0x10002 for center, and 0x10003 for right. | (InfoPreference, action) | | | UI added 23ba5816 |
| **Media controls** | | | | |
| Previous | `"pref_media_previous"` | Enum, `SingleActionGesture.Switch` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| Play/Pause | `"pref_media_playpause"` | Enum, `SingleActionGesture.PlayPause` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| Next | `"pref_media_next"` | Enum, `SingleActionGesture.Switch` | PlayerActivity.kt | yes (AniZen port 23ba5816) |
| When a media control is set to "Custom", it can be bound through input.conf. The key codes are 0x10004 for previous, 0x10005 for play/pause, and 0x10006 for next. | (InfoPreference, action) | | | UI added 23ba5816 |

Notes: **Double tap (left/center/right)** and **Media controls** set to "Custom" send key codes 0x10001–0x10006 that
must be bound in input.conf (Advanced › Edit MPV input file). Long-press actions and "long press speed sliding" are
only in the player's More sheet (see below).



### Settings › Player › Decoder

`presentation/more/settings/screen/player/PlayerSettingsDecoderScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Try hardware decoding | `"pref_try_hwdec"` | Boolean, `true` | AnimePreviewPlayer.kt, PlayerProfiles.kt, PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Enable gpu-next | `"pref_gpu_next"` | Boolean, `false` | AnimePreviewPlayer.kt, AniyomiMPVView.kt, VideoFilterUtils.kt | yes (AniZen port 23ba5816) |
| Debanding | `"pref_video_debanding"` | Enum, `Debanding.None` | AniyomiMPVView.kt, VideoFiltersPanel.kt | yes (AniZen port 23ba5816) |
| High quality scaling | `"pref_high_quality_scaling"` | Boolean, `false` | PlayerProfiles.kt | yes (AniZen port 23ba5816) |
| Interpolation (Smooth Motion) | `"pref_smooth_motion"` | Boolean, `false` | PlayerProfiles.kt | yes (AniZen port 23ba5816) |
| "Smooth Motion Mode" | `"pref_interpolation_mode"` | Enum, `InterpolationMode.Oversample` | AniyomiMPVView.kt | yes (AniZen port 23ba5816) |
| "Smooth Motion FPS Limit" | `"pref_interpolation_fps_limit_int"` | Int, `0` | AniyomiMPVView.kt | yes (AniZen port 23ba5816) |
| Adaptive Performance Scaling | `"pref_adaptive_shader_scaling"` | Boolean, `false` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Use YUV420P pixel format | `"use_yuv420p"` | Boolean, `true` | AniyomiMPVView.kt, VideoFilterUtils.kt | yes (AniZen port 23ba5816) |

Notes:
- **Try hardware decoding**, **High quality scaling**, **Interpolation (Smooth Motion)** are only used as-is when the
  Performance profile is "Custom" (see above). Hardware decoding is applied as mpv `hwdec=mediacodec-copy` or `no`
  at init (`AniyomiMPVView` ~192; r82 aa5d1710 made the player start in mediacodec-copy to avoid a 5–10 s decoder
  retry). The player's More sheet has a per-session "Hardware decoding mode" that does not change the setting.
- **Smooth Motion Mode / FPS Limit** are disabled unless Smooth Motion is on.
- **Adaptive Performance Scaling** (`false`): when frames drop with Anime4K on, steps Anime4K down a level and then
  off for this session (`PlayerViewModel` ~2621, `effectiveAnime4K(sessionQualityCap, sessionOff)`).
- The Browse/Library **preview player** (`presentation/anime/AnimePreviewPlayer.kt`) reads `pref_try_hwdec`,
  `pref_gpu_next`, filters and Anime4K directly, **without** the performance profile.
- Anime4K itself (`pref_enable_anime4k`, `pref_anime4k_mode` "OFF", `pref_anime4k_quality` "BALANCED") and the video
  filters are set in the player's Filters panel, not here.



### Settings › Player › Subtitles

`presentation/more/settings/screen/player/PlayerSettingsSubtitleScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Disable auto subtitle selection | `"pref_disable_auto_subtitles"` | Boolean, `false` | PlayerViewModel.kt | yes (AniZen port 23ba5816) |
| Preferred languages | `"pref_subtitle_lang"` | String, `""` | TrackSelect.kt | yes (AniZen port 23ba5816) |
| Whitelist | `"pref_subtitle_whitelist"` | String, `""` | TrackSelect.kt | yes (AniZen port 23ba5816) |
| Blacklist | `"pref_subtitle_blacklist"` | String, `""` | TrackSelect.kt | yes (AniZen port 23ba5816) |

Notes: languages/whitelist/blacklist are read by `tachiyomi/ui/player/domain/TrackSelect.kt` when tracks load. Subtitle look
(font, size, colours, border, position, delay…) is set in the player's subtitle panels (below). Hidden, read but with
no UI: `pref_subtitle_system_fonts` (false), `pref_subtitle_black_bars` (false), `pref_screenshot_subtitles` (false,
used by the screenshot action).



### Settings › Player › Audio

`presentation/more/settings/screen/player/PlayerSettingsAudioScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Preferred languages | `"pref_audio_lang"` | String, `""` | AniyomiMPVView.kt, TrackSelect.kt, VideoComparator.kt | yes (AniZen port 23ba5816) |
| Enable audio pitch correction | `"pref_audio_pitch_correction"` | Boolean, `true` | AniyomiMPVView.kt, PlayerControls.kt | yes (AniZen port 23ba5816) |
| Audio channels | `"pref_audio_config"` | Enum, `AudioChannels.AutoSafe` | MoreSheet.kt, PlayerActivity.kt | yes (AniZen port 23ba5816) |
| Volume boost cap | `"pref_audio_volume_boost_cap"` | Int, `30` | AniyomiMPVView.kt, GestureHandler.kt, PlayerControls.kt, PlayerViewModel.kt | yes (AniZen port 23ba5816) |

Notes: **Volume boost cap** (0–200 %, default 30) bounds the volume gesture/slider boost. **Audio channels** is also in
the player's More sheet (same key). Audio delay (`pref_audio_delay`) is set in the player's audio delay panel.



### Settings › Player › Advanced

`presentation/more/settings/screen/player/PlayerSettingsAdvancedScreen.kt`

| Setting | Key | Type, default | Read by | Fork |
|---|---|---|---|---|
| Enable MPV scripts | `"mpv_scripts"` | Boolean, `false` | via `mpvUserFiles()` → animiru/feature/mpvfiles/MpvConfig.kt | yes (AniZen port 23ba5816) |
| Edit MPV configuration file for further player settings | `"pref_mpv_conf"` | String, `""` | AniyomiMPVView.kt, PlayerActivity.kt | yes (AniZen port 23ba5816) |
| Edit MPV input file for keyboard mapping configuration | `"pref_mpv_input"` | String, `""` | PlayerActivity.kt | yes (AniZen port 23ba5816) |

Notes: **Enable MPV scripts** is read through `mpvUserFiles()` in `animiru/feature/mpvfiles/MpvConfig.kt` (copies user
mpv files/scripts; needs storage permission). mpv.conf / input.conf text is applied in `PlayerActivity` at start;
options set in mpv.conf win over the app's cache sizing (`applyStreamCache` comment). The **Statistics page**
(`pref_player_statistics_page`) is set from the More sheet.


### Player › Player layout, Custom buttons, Script editor

- **Player layout** (`presentation/more/settings/screen/player/layout/PlayerSettingsLayoutMainScreen.kt`, `…ScreenModel.kt`): comma-separated button
  lists `pref_top_right_controls` ("AutoPlay,Cast,SubtitleTracks,AudioTracks,QualityTracks,MoreOptions"),
  `pref_bottom_left_controls` ("Episodes,LockControls,ScreenRotation,PlaybackSpeed,CurrentChapter"),
  `pref_bottom_right_controls` ("SkipIntro,CustomButton,PictureInPicture,VideoZoom,AspectRatio"),
  `pref_portrait_bottom_controls` (long list). Read by `tachiyomi/ui/player/controls/PlayerControls.kt`. `pref_top_left_controls`
  ("BackArrow,VideoTitle") exists but has no reader and no editor: dead (the top-left is fixed). The Cast button is
  left out of the layout summary when Cast is off. Hidden one-shot flag `kmk_player_episodes_cast_buttons` puts the fork's
  **Episodes** button into layouts saved before it existed (`PlayerPreferences.addEpisodesButton`, 2e678f65).
  The fork edited these defaults (Episodes button added, Cast's place changed; see `git log -p` of `PlayerPreferences.kt`).
- **Custom buttons** (`presentation/more/settings/screen/player/custombutton/…`): Lua buttons kept through `GetCustomButtons` (repository), not preferences.
- **Script editor** (`presentation/more/settings/screen/player/editor/…`): edits mpv script / script-opts files in app storage (`SCRIPTS_PATH`, `SCRIPT_OPTS_PATH`), not preferences.

### Settings in sheets and menus (not on a Settings screen)

#### Manga library sheet (Library › filter icon) — `presentation/library/LibrarySettingsDialog.kt`

Upstream Komikku, with fork edits (Panorama chips, columns max; OmfgItsJesus/Dominican4Life). Keys in
`domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt`:

| Tab | Setting | Key | Default |
|---|---|---|---|
| Filter | Downloaded / Unread / Started / Bookmarked / Completed / Customized update frequency / Lewd | `pref_filter_library_downloaded_v2`, `pref_filter_library_unread_v2`, `pref_filter_library_started_v2`, `pref_filter_library_bookmarked_v2`, `pref_filter_library_completed_v2`, `pref_filter_library_interval_custom`, `pref_filter_library_lewd_v2` (TriState) | DISABLED |
| Filter | Tracked (per tracker) | `pref_filter_library_tracked_<trackerId>_v2` | DISABLED |
| Filter | Categories (+ include / exclude) | `pref_filter_library_categories`, `pref_filter_library_categories_include`, `pref_filter_library_categories_exclude` | false / empty |
| Sort | Sort mode + direction; Random seed; Tag sorting | `library_sorting_mode` (object), `library_random_sort_seed`, `sort_tags_for_library` | `LibrarySort.default`, 0 |
| Display | Display mode (+ the three Panorama chips, fork) | `pref_display_mode_library` + `pref_library_panorama_style` (fork) | `LibraryDisplayMode.default` / FILL |
| Display | Items per row (portrait / landscape); fork raised the max from 10 to 15 (87733145) | `pref_library_columns_portrait_key` / `pref_library_columns_landscape_key` | 0 = auto |
| Display | Badges: Downloaded / Unread / Local / Language / Use language icon / Source icon | `display_download_badge` (false), `display_unread_badge` (true), `display_local_badge` (true), `display_language_badge` (true), `display_language_text` (true), `display_source_badge` (true) | |
| Display | Continue reading button | `display_continue_reading_button` | false |
| Display | Tabs: Show category tabs / Show hidden categories / Show number of items | `display_category_tabs` (true), `hide_hidden_categories` (false), `display_number_of_items` (false) | |
| Group | Group by | `group_library_by` | `LibraryGroup.BY_DEFAULT` |

Several of these getters (`downloadBadge`, `localBadge`, `languageBadge`, `useLangIcon`, `sourceBadge`,
`showHiddenCategories`, filters) have same-named twins in the fork's `AnimeLibraryPrefs`; when grepping for
readers, check which object the call is on.

#### Anime library sheet — `presentation/anime/AnimeLibrarySettings.kt` (fork, b9103f1f r53, 8c36b0c7 r57, 86b1a58d r58)

From Anikku's library sheet; object `AnimeLibraryPrefs`. **Display mode, items per row, category tabs and number of
items are shared with the manga library** (`libraryPreferences.displayMode()`, `portraitColumns()`,
`categoryTabs()`, `categoryNumberOfItems()`) and so is the Panorama style; filters, sort and badges are anime's own.
Read by `presentation/anime/AnimeLibraryContent.kt` (`filterAndSortAnime`).

| Setting | Key | Type, default |
|---|---|---|
| Filter: Downloaded / Unseen / Started / Bookmarked / Completed / Lewd / Tracked (any) | `anime_library_filter_downloaded`, `…_unseen`, `…_started`, `…_bookmarked`, `…_completed`, `…_lewd`, `…_tracked_any` | TriState, DISABLED |
| Filter: Categories (+ include/exclude) | `anime_library_filter_categories`, `…_include`, `…_exclude` | Boolean false / StringSets |
| Sort (Alphabetical, Total episodes, Last seen, Last update check, Unseen count, Latest episode, Episode fetch date, Date added, Tracker score, **Airing time** (AniList, unknown last), Random) | `anime_library_sort`, `anime_library_sort_ascending`, `anime_library_random_seed` | ALPHABETICAL, true, 0 |
| Badges: Downloaded / Unseen / Local / Language (+icon) / Source; Continue watching button | `anime_library_badge_downloaded` (false), `…_unseen` (true), `…_local` (true), `…_language` (false), `…_language_icon` (true), `…_source` (false), `anime_library_continue_button` (false) | Boolean |
| Show hidden categories | `anime_library_show_hidden_categories` (+ set `anime_library_hidden_categories`) | false |
| Group | `anime_library_group_type` | `LibraryGroup.BY_DEFAULT` |

Dead: `AnimeLibraryPrefs.filterTracked(trackerId)` (`anime_library_filter_tracked_<id>`): the sheet uses only
"Tracked (any)" (comment "only Tracked (any tracker), like Anikku").

#### Browse source display menu — `presentation/browse/components/BrowseSourceToolbar.kt`, `tachiyomi/ui/browse/source/browse/BrowseSourceScreen*.kt`

| Setting | Key | Default | Notes |
|---|---|---|---|
| Display mode (Comfortable / Compact / Cover-only / List / Panorama ×3) | `pref_display_mode_catalogue` + `pref_browse_panorama_style` (fork) | CompactGrid / FIT | anime sources follow both |
| Items per row (0–15) | `pref_browse_columns_portrait_key` / `…_landscape_key` (OmfgItsJesus 87733145) | **-1 = follow the library value**, 0 = auto | `BrowseSourceScreenModel.resolveBrowseColumns`; anime: `AnimeSourceScreen` |
| Preview panels (left / right / both / none) | `pref_browse_preview_panels` (a84459f6) | 1 = both | which panels the first tap on a cover opens; 0 left, 1 both, 2 none, 3 right |
| (library) Preview panels | `pref_library_preview_panels` (ed849e93 r67) | 2 = none | set by the panel buttons in the manga/anime library preview (`tachiyomi/ui/library/LibraryMangaPreview.kt`, `AnimeLibraryPreview.kt`) |

Browse preview caches: hidden SharedPreferences file `browse_preview_fetched` (`PreviewFetchTimes.kt`): manga id →
last fetch time; a cover tapped again within 24 h shows the saved details/chapters; trimmed above 2000 entries.

#### History / Updates display menus — `tachiyomi/ui/history/HistoryTab.kt`, `tachiyomi/ui/updates/UpdatesTab.kt` (Dominican4Life ecc71cb2)

| Setting | Key | Default |
|---|---|---|
| All / Manga / Anime tab (remembered) | `pref_history_media_tab` / `pref_updates_media_tab` | 0 |
| Display mode | `pref_history_display_mode` / `pref_updates_display_mode` | List |
| Covers per row | `pref_history_columns` / `pref_updates_columns` | 0 = auto |
| Panorama cover (upstream KMK) | `pref_updates_history_screen_use_panorama_cover` — **one key shared by both** `HistoryPreferences.usePanoramaCover()` and `UpdatesPreferences.usePanoramaCover()` | false |
| Device files only (History) | `history_device_files_only` | false |

Remembered Manga/Anime switches elsewhere: `pref_library_show_anime` (Home/Library, `HomeScreen.kt`),
`pref_browse_show_anime` (`BrowseTab.kt`). The old keys `pref_history_show_anime`, `pref_updates_show_anime`,
`pref_history_grid_mode`, `pref_history_show_summary`, `pref_updates_timeline_view`, `pref_updates_show_carousel`
were removed by the fork; their stored values are orphaned.

#### Reader settings sheet (in the reader) — `presentation/reader/settings/*` (all upstream)

Reading mode page: per-series reading mode/rotation (stored in the manga's viewer flags, DB), then the same keys as
Settings › Reader › Paged / Long strip (`ReadingModePage.kt`). General page: background colour, show page number,
seekbar options, fullscreen, cutout, keep screen on, long tap, always show transition, auto webtoon, e-ink flash
(`GeneralSettingsPage.kt`). Colour filter page: `pref_custom_brightness_key`, `custom_brightness_value`,
`pref_color_filter_key`, `color_filter_value`, `color_filter_mode`, `pref_grayscale`, `pref_inverted_colors`
(`ColorFilterPage.kt`). No fork changes.

#### In-player sheets and panels — `tachiyomi/ui/player/controls/components/{sheets,panels}/*`

| Where | Setting | Key | Default |
|---|---|---|---|
| Speed sheet | Speed, Long press speed, "Make default speed", presets | `pref_player_speed`, `pref_player_speed_long_press`, `default_speed_presets`, `default_long_press_speed_presets` | 1.0, 2.0 |
| Speed sheet | Pitch correction (same as Audio screen) | `pref_audio_pitch_correction` | true |
| More sheet | Statistics page | `pref_player_statistics_page` | 0 = off |
| More sheet | Long press action / paused long press action / speed sliding | `pref_long_press_action` (Speed), `pref_paused_long_press_action` (DoNothing), `pref_gesture_long_press_speed_sliding` (true) | |
| More sheet | Audio channels | `pref_audio_config` | AutoSafe |
| More sheet | Hardware decoding mode, Sleep timer | session only | |
| Filters panel | brightness/contrast/saturation/gamma/hue/sharpen, presets, deband, Anime4K on/mode/quality | `pref_player_filter_*`, `pref_video_filter_theme`, `pref_video_debanding`, `pref_enable_anime4k`, `pref_anime4k_mode`, `pref_anime4k_quality` | 0 / None / off |
| Hidden deband tuning (no UI) | `pref_player_filter_deband` (1), `…_grain` (32), `…_deband_threshold` (48), `…_deband_range` (16) | read by `VideoFilterUtils.kt` |
| Subtitle panels | font, size, scale, border style/size, shadow offset, bold, italic, justification, colours (text/border/background), position, override ASS | `pref_subtitle_font`, `pref_subtitles_font_size` (55), `pref_sub_scale`, `pref_border_style_subtitles`, `pref_sub_border_size` (3), `sub_shadow_offset`, `pref_bold_subtitles`, `pref_italic_subtitles`, `pref_sub_justify`, `pref_text_color_subtitles`, `pref_border_color_subtitles`, `pref_background_color_subtitles`, `pref_sub_pos` (100), `pref_override_subtitles_ass_enum` | applied as mpv options in `AniyomiMPVView` |
| Subtitle delay panel | delay, speed ("Set as default") | `pref_subtitles_delay`, `pref_subtitles_speed`; `pref_subtitles_secondary_delay` read, set from the panel's Secondary/Both | 0 / 1.0 |
| Audio delay panel | delay | `pref_audio_delay` | 0 |
| Aspect sheet | aspect, custom ratios | `pref_player_aspect_state` (Fit), `pref_player_custom_aspect_ratios` | |
| Zoom sheet | Pinch-to-zoom (same key as Gestures) | `pref_gesture_video_zoom` | false |
| Top bar | Autoplay next | `pref_auto_play_enabled` (also used by Cast) | false |
| Time label | invert duration (tap) | `invert_duration` | false |
| Cast subtitle dialog | size/colour/background/shadow/font family/border style | `pref_cast_subtitle_size`, `…_color`, `…_background`, `…_shadow`, `…_font_family`, `…_border_style` (`CastSubtitlePreferences.kt`, saved together by `saveTextTrackStyle`) | read by `CastManager` (font family / border style through `getFontFamily()` / `getBorderStyle()`) |

Hidden, read by the player but with no UI (stay at defaults): `pref_switch_on_failure` (true: try the next video on
error, `PlayerActivity`), `create_folder_per_anime` (false, screenshots), `skip_seen` (false).

#### Anime page: episode sheet and per-anime values — `tachiyomi/ui/anime/AnimeScreenModel.kt`

Sort/filter/display/previews/summaries/season grouping of one anime are bits of its `episodeFlags` (DB). "Set as
default" writes `anime_default_episode_flags` (Long) and the two Library switches; "Reset" uses them. Per anime
(hidden): `anime_last_selected_season_<animeId>`, `anime_notes_<animeId>`, `anime_excluded_scanlators_<animeId>`
(the last two also backed up with the anime by `AnimeBackupHelper`).

#### Migration options — `mihon/feature/migration/config/MigrationStartSheet.kt`, `…/anime/AnimeMigrationStartSheet.kt`

Upstream manga keys (`migration_flags`, `migration_sources`, `migration_deep_search`, `migration_prioritize_by_chapters`,
`migration_hide_unmatched`, `migration_hide_without_updates`, `migration_smart_search_single_entry`) plus the fork's **Pause between titles** for
migration (`pref_migration_delay_enabled` **true**, `pref_migration_delay` "1-3"; 0fa264cd/57bd5e26 "paced,
background migration") and the anime twins `anime_migration_sources`, `anime_migration_flags`,
`anime_migration_deep_search`, `anime_migration_prioritize_by_episodes`, `anime_migration_hide_unmatched`,
`anime_migration_hide_without_updates` (b9103f1f/e2691c10). Migration source pickers share the manga
`pref_migration_sorting` / `pref_migration_direction`.

#### Source settings (per source)

- Extension sources: upstream `tachiyomi/ui/browse/extension/details/SourcePreferencesScreen.kt` shows the extension's own
  `setupPreferenceScreen`, stored in `source_<id>`. Anime extensions use the same screen from
  `AnimeExtensionDetailsScreen` / `AnimeBrowsePanes` (fork 3e5bb411).
- JS / JSON ("Code editor") sources: `tachiyomi/jssource/JsPreferences.kt` turns the source's
  `getSourcePreferences()` list into the same kind of screen (types Text, Switch, List, MultiList, and fork-added
  Category, Info, Slider — fd1f8656; Mangayomi-style `listPreference: {entries, entryValues}` is accepted). Values in
  `source_<sourceId>`; sliders are saved as text "3" or "1-5". Keyless Category/Info rows get synthetic keys
  `__category_<index>`. **A JS source and an APK with the same source id share this file.**
- Extension stores (Browse › Extension stores, `presentation/more/settings/screen/browse/ExtensionStoresScreen.kt`): repos are in the DB/upstream keys;
  the fork remembers every repo ever added in `known_extension_repos` (StringSet, `RepoAdding.kt` `RepoMemory`) to offer
  them again.


### Hidden and internal preferences the fork added

Keys used in code but not shown as a setting. All are plain keys (so they **are** in the "App settings" part of a
backup and are restored verbatim) unless marked private. Keys ending in `<animeId>` hold local database ids: after
a restore onto a fresh install the ids can differ, so these values may point at the wrong title or nothing.

| Key | Type, default | Kept by (file) | What it holds | Commit |
|---|---|---|---|---|
| `anime_anilist_episodes_<animeId>` | String, `""` | `tachiyomi/ui/anime/anilist/AniListEpisodeMatch.kt` | AniList id used for episode titles/pictures: `""` not looked up yet, `none`, the id, or `auto:<id>` when matched by title | aa5d1710 (r82) |
| `anilist_link_manga_<anilistId>` / `anilist_link_anime_<anilistId>` | String, `""` | `tachiyomi/ui/anime/anilist/AniListPlayback.kt` (`AniListLinks`) | the source + local id an AniList title is read/watched from | 9aec4911 (r60) |
| `anilist_search_large_cards`, `anilist_search_genres`, `anilist_search_tags` | Boolean false, String `""` ×2 | `tachiyomi/ui/anime/anilist/AniListSearchModel.kt` | AniList search page layout and the genre/tag lists it fetched | e72cd842 |
| `manga_global_search_mode`, `anime_global_search_mode` | String, `RESULTS` | `presentation/browse/GlobalSearchScreen.kt`, `tachiyomi/ui/anime/AnimeGlobalSearchScreen.kt` | Results vs AniList row in global search | 9aec4911 |
| `anime_global_search_pinned_only` | Boolean, false | `AnimeGlobalSearchScreen.kt` | pinned-sources-only toggle | 9aec4911 |
| `anime_last_selected_season_<animeId>` | String, `""` | `tachiyomi/data/anime/AnimeEpisodeDefaults.kt` | season tab last open on that anime | 6b789f59 |
| `anime_notes_<animeId>`, `anime_excluded_scanlators_<animeId>` | String / StringSet | `tachiyomi/data/anime/AnimeExtraPrefs.kt`, `AnimeScreenModel.kt` | notes and excluded scanlators of one anime (also written into the anime's backup entry by `AnimeBackupHelper`) | df4a9b1c, b9103f1f |
| `anime_skip_intro_length_<animeId>` | Int, 0 | `tachiyomi/ui/player/compat/PlayerCompat.kt` | per-anime intro length (0 = use "Default skip intro length") | 23ba5816 |
| `anime_default_episode_flags` | Long, 0 | `AnimeEpisodeDefaults.flags()` | episode sort/filter/display for newly added anime ("Set as default") | b9103f1f |
| `anime_episode_display_flags_migrated` | Boolean, false | `AnimeScreenModel` init | one-shot: copied the old app-wide hide-pictures/summaries choice onto every library anime (r57) | 6b789f59 |
| `anime_airing_cache` | String (JSON), `""` | `tachiyomi/data/anime/AnimeAiring.kt` | next airing episode per anime (AniList), for "Airing time" sort and Coming up | 9f786264 (r55) |
| `anime_download_queue` | StringSet | `tachiyomi/data/anime/download/AnimeDownloadManager.kt` | the episode download queue, so it survives restarts | 17da394b |
| `__PRIVATE_anime_track_pending` | StringSet (private) | `TrackPreferences.animeTrackPending()`, `AnimeTracking.kt` | anime track updates not sent yet | e2691c10 |
| `pref_per_anime_default_stream_data` | String | `DefaultStreamPreferenceStore.kt` | encoded map anime id → saved stream selector | 23ba5816 |
| `pref_default_stream_selector` | String, `""` | same | global fallback selector | 23ba5816 |
| `pref_player_last_aspect_ratio`, `pref_last_aspect_ratio_anime_id`, `player_brightness_value` | Float -1, Long -1, Float -1 | `PlayerViewModel`, `PlayerActivity` | remembered aspect / brightness | 23ba5816 |
| `pref_player_custom_aspect_ratios`, `default_speed_presets`, `default_long_press_speed_presets` | StringSets | player sheets | user lists | 23ba5816 |
| `anime_preview_folded_groups` | StringSet | `presentation/anime/AnimePreviewPlayer.kt` | which video-settings groups are folded in the preview player | ed849e93 (r67) |
| `feed_anime_rows` | String, `""` | `tachiyomi/ui/browse/feed/AnimeFeedScreenModel.kt` | the anime Feed's saved rows | 1b2fb9a5 (Dominican4Life) |
| `feed_tag_mixes` | String (JSON), `"[]"` | `tachiyomi/ui/browse/feed/TagMixFeed.kt` | Feed "tag mixes" (replaced Suggestions) | a048d24a (Dominican4Life) |
| `kmk_feed_tab_restored` | Boolean, false | `tachiyomi/ui/browse/BrowseTab.kt` | one-shot: un-hid the Feed tab once | 4735057d |
| `kmk_player_episodes_cast_buttons` | Boolean, false | `PlayerPreferences.addEpisodesButton()` | one-shot: added the Episodes button to old layouts | 2e678f65 |
| `source_type_badge_added` | Boolean, false | `presentation/components/BadgeCorners.kt` | one-shot: placed the new source-type badge | 355d9ef8 (r63) |
| `cover_badges_<corner>`, `source_badges_<corner>` | StringSets | `UiPreferences`, `BadgeCorners.kt` | Badges screens (shown, listed above) | 3006799e |
| `known_extension_repos` | StringSet | `presentation/more/settings/screen/browse/RepoAdding.kt` (`RepoMemory`) | every repo ever added, offered again in the add dialog | c36ecadb |
| `sideloaded_extensions` | StringSet | `tachiyomi/extension/util/SideloadFolder.kt` | sideloaded (folder-installed) extensions it tracks | fork (`c1c3e33e`) |
| `pair_sync_peers`, `pair_sync_selection` | String (JSON) | `tachiyomi/pairsync/PairSync.kt` | paired Bluetooth devices and what to sync | a4a9e23c |
| `device_files_show_hidden`, `_sort`, `_sort_desc`, `_grid`, `_play_in_app`, `_read_in_app`, `_show_recent`, `_favourites`; `history_device_files_only` | various | `tachiyomi/devicefiles/DeviceFiles.kt` | the Device files browser's own options | 1836136a, 929c55ac |
| `pref_library_show_anime`, `pref_browse_show_anime`, `pref_history_media_tab`, `pref_updates_media_tab` | Boolean / Int | `HomeScreen`, `BrowseTab`, `HistoryTab`, `UpdatesTab` | remembered Manga/Anime switches | e284e45c, a0d856f2, ecc71cb2 |
| `anime_pinned_catalogues`, `anime_hidden_catalogues`, `anime_disabled_languages` | StringSets | `SourcePreferences`; Browse, global search, Feed, migration pickers, JS sources | anime twins of pinned / disabled sources / languages (set from Extensions and source lists) | bcc3e4ae, df4a9b1c |

Separate SharedPreferences files and files on disk (not in the app-settings backup):

| Name | Kept by | What |
|---|---|---|
| `browse_preview_fetched` | `tachiyomi/ui/browse/source/browse/PreviewFetchTimes.kt` | manga id → last fetch time (24 h freshness; trimmed past 2000) |
| `js_weburls_<sourceId>` | `tachiyomi/jssource/JsWebUrls.kt` | url → real site page for JS items with `webUrl` (cleared past 5000) |
| `js_tabs` | `tachiyomi/jssource/JsSourceManager.kt` (`lastTab`/`rememberTab`) | the tab each JS source was last opened on |
| `source_<sourceId>` | `tachiyomi/jssource/JsPreferences.kt` (and extensions) | per-source settings |
| `filesDir/js_tags/<key>.json` | `tachiyomi/jssource/JsTags.kt` | **tag-list cache** from a JS source's `getTags()`; read once, re-fetched only by "Refresh tag list" in that source's settings; failures not retried each time |
| `filesDir/js_sources`, `js_storage` | `tachiyomi/jssource/*` (renamed once by `JsLegacy.migrate`, 5fb7b0f4 r62) | JS source code and its storage |

### Settings that do nothing (no reader found)

| Setting (screen) | Key | Origin |
|---|---|---|
| Remember and switch to the last used volume (Player › Internal player) | `pref_remember_volume` (+ `player_volume_value` never written) | AniZen port |
| Show restoring progress banner (Data and storage) | `__APP_STATE_pref_show_restoring_progress_banner_key` | made dead by the fork (restore bar removed, 2c614de7 r33) |
| Tap scroll by page (Reader › Long strip with gaps) | `continuous_vertical_tapping_by_page` | upstream SY |
| Incognito mode (E-Hentai) | `eh_incognito_mode` | upstream SY |
| (no row) | `pref_top_left_controls`, `pref_use_external_downloader` | AniZen port |
| (no row) | `anime_library_filter_tracked_<trackerId>` (sheet only uses "Tracked (any)") | fork |
| (code) | `VideoComparator` reads `pref_preferred_quality` / `pref_audio_lang` but is never constructed | AniZen port |
| Discord settings | `pref_enable_discord_rpc` etc. | live code, but the screen is unreachable (39f455aa) |

Shown and read, but overridden: **Try hardware decoding / High quality scaling / Smooth Motion** whenever the
Performance profile is not "Custom".

**r86 changes to the list above:**
- *Remember … last used volume* now works: `PlayerActivity.onStop` saves the media volume as a fraction (0–1) in
  `player_volume_value`, and the first `onStart` of a player restores it (`I Player Restored last used volume`).
- *Show restoring progress banner*: row **hidden** (key kept). The restore bar was removed on purpose in r33.
- *Tap scroll by page* now works: `WebtoonViewer.scrollDown` reads the setting live (nothing passed `tapByPage`).
- *E-Hentai Incognito mode* was not dead: its `onValueChanged` adds/removes `EH_PACKAGE` in `incognito_extensions`,
  which `GetIncognitoState` reads. Since r86 the switch re-syncs from that list when the screen opens.
- Left as they are (no row, so nothing to hide): `pref_top_left_controls` (would need a new layout region in
  `PlayerControls`), `pref_use_external_downloader` (no external-download code), `anime_library_filter_tracked_<id>`
  (per-tracker filters were dropped on purpose for "Tracked (any)"), `VideoComparator` (dead class; preferred
  quality is read by `HlsVariants`, preferred audio by `TrackSelect`/mpv `alang`). Discord stays unreachable.
- Decoder screen (r86): when a profile overrides a switch, the switch shows the value in use, greyed
  (`SwitchPreference.locked`), with "Set by the <profile> profile; choose Custom to change". Best quality locks
  only High quality scaling; Balanced/Battery saver lock all three. Note `enabled = false` *hides* a settings row in
  this app (`StatusWrapper`), which is why `locked` exists.

### Settings that interact or conflict

- **Performance profile ⟶ Decoder screen.** Balanced and Power saver force hardware decoding on and smooth motion
  off; Power saver forces Anime4K off and HQ scaling off; Best quality forces HQ scaling on. The Decoder switches keep
  showing the user's own value. Anime4K quality is also capped (Balanced: Fast) and may be lowered further for the
  session by **Adaptive Performance Scaling**.
- **Network-aware playback ⟶ Preferred quality / Preload / cache.** On metered networks it caps online video at
  720p even if Preferred quality says 1080, halves the cache, and pauses preloading while buffering. **Preload next
  episode = "Wi-Fi only"** also checks metered networks separately.
- **Intelligent buffer handoff + Network-aware** both size the demuxer cache in `PlayerActivity.applyStreamCache`
  (handoff ×1.5 / 20 s read-ahead, then network-aware halves it); a `demuxer-max-bytes` in mpv.conf wins over both.
- **Remember default stream per anime** off disables Highlight and Auto-scroll as well.
- **Always use external player** bypasses every internal-player setting and the AniSkip / delete-after-watching /
  download-ahead behaviour.
- **Skip filtered chapters** (Reader) and the player's **Skip filtered episodes** have separate keys since r86
  (`skip_filtered` / `pref_player_skip_filtered_episodes`). The player key copies `skip_filtered` the first time
  it's read. The player switch is in Player settings › Internal player › Filler.
- **Library display mode, items per row, category tabs, number of items and Panorama style are shared between the
  manga and anime libraries**; the Panorama library style is also used by History and Updates. The Appearance
  Panorama rows and the sheet/menu Panorama chips write the same keys.
- **Browse items per row** `-1` follows the library value; once moved in a source it is separate (per orientation).
- **Anime library update** uses the manga update's device restrictions, and runs with the manga update when its
  interval is "With the manga update"; with the manga update set to Never, the restrictions row is disabled (still
  applied to anime).
- **Pause between titles**: library updates (manga + anime) and Refresh missing covers share one switch/range
  (default off); migrations have their own pair (default **on**, 1–3 s).
- **Settings › Library › Show episode summary/thumbnail** are overwritten by an anime's episode sheet "Set as
  default", and only affect anime added afterwards.
- **Cover badges corners vs. library sheet badge toggles**: both must allow a badge for it to show.
- **History and Updates "Panorama cover"** share one key (`pref_updates_history_screen_use_panorama_cover`), so
  toggling it in one changes the other (upstream KMK).
- **Hide Feed tab** was force-reset to off once by the fork (`kmk_feed_tab_restored`).
- **Torrent server port vs Cast server port**: both are local HTTP servers (defaults 8090 and 8181); nothing checks
  they differ.

### Settings whose effect needs an app restart

Shown with a "requires app restart" toast or summary: Tablet UI; DNS over HTTPS; Default user agent (+ reset);
Revoke trusted unknown extensions; Show NSFW in sources and extensions lists; Enable source blacklist; Log level;
Encrypt database; TorrentServer port, Torrent trackers (+ reset) (fork: `TorrentServerUtils.hostUrl` is computed
once). Effectively restart-only without a toast: Reader cache size (`eh_cache_size`, read when `ChapterCache` is
built). **Next player start** (not app restart):
nearly every Player setting, mpv.conf / input.conf, Decoder settings. **Next job run**: Pause between titles, anime
Episodes downloaded at once.

### Settings in this area (fork-added or fork-changed only)

| Setting as shown | Key | Default | What it changes | Fork |
|---|---|---|---|---|
| Badges › Cover badges / Source badges | `cover_badges_<corner>` / `source_badges_<corner>` | see Badges table | which corner each badge is drawn in | yes |
| Reading progress bar | `cover_progress_bar` | true | bar on covers (Library, History) | yes |
| Panorama style in the library, History and Updates | `pref_library_panorama_style` | FILL | Panorama grid look | yes |
| Panorama style in sources | `pref_browse_panorama_style` | FIT | Panorama grid look in sources | yes |
| Pause between titles (Library) | `pref_library_update_pause_enabled` / `pref_library_update_pause` | false / "1-3" | random wait between same-source titles in updates and cover refresh | yes |
| Pause between titles (Migration) | `pref_migration_delay_enabled` / `pref_migration_delay` | true / "1-3" | same, for migrations | yes |
| Automatic anime updates | `anime_update_interval` | -1 (with manga) | anime update schedule | yes |
| Anime categories (update) | `anime_update_categories[_exclude]` | all | which anime update | yes |
| Skip anime | `anime_update_skip` | completed | which anime the update skips | yes |
| Episode on swipe to left / right | `pref_episode_swipe_start_action` / `…_end_action` | bookmark / seen | episode row swipes | yes |
| Show episode summary / thumbnail | `anime_episode_show_summaries` / `…_previews` | true / true | defaults for new anime | yes |
| Season grouping | `anime_episode_season_grouping` | Tabs | episode list grouping for "Default" anime | yes |
| Auto save covers for manga and anime | `save_covers_in_folders` | false | cover.jpg in download folders | yes |
| Import covers from download folders | action | – | copies them back | yes |
| Anime downloads: Wi-Fi only / at once / delete after watching / keep bookmarked / download ahead / download new + categories | `anime_download_only_wifi`, `anime_download_concurrent`, `anime_delete_after_seen`, `anime_delete_keep_bookmarked`, `anime_download_ahead`, `anime_download_new`, `anime_download_new_categories[_exclude]` | true, 1, false, true, 0, false, all | anime download queue and player clean-up | yes |
| Simkl tracker | tracker login | – | anime tracking | yes |
| Replace latest button | `use_new_source_navigation` | **false** (was true) | Latest button vs combined browse | changed |
| Show restoring progress banner | `__APP_STATE_pref_show_restoring_progress_banner_key` | true | nothing any more | changed (dead) |
| Open bot checks automatically | `auto_open_bot_checks` | true | auto-open WebView for bot checks and retry | yes |
| Clear Anime database / Clear Manga database | actions | – | delete non-library entries | yes / renamed |
| All Player settings screens | see tables | – | AniZen player | yes (port) |
| Cast server port | `pref_cast_server_port` | "8181" | local server for Chromecast | yes |
| TorrentServer port / trackers | `pref_torrent_port` / `pref_torrent_trackers` | "8090" / list | torrent streams | yes (OmfgItsJesus) |
| Browse items per row | `pref_browse_columns_portrait_key` / `…_landscape_key` | -1 | source grid columns | yes (OmfgItsJesus) |
| Preview panels (browse / library) | `pref_browse_preview_panels` / `pref_library_preview_panels` | both / none | panels on first cover tap | yes |
| History / Updates display mode and columns | `pref_history_display_mode`, `pref_history_columns`, `pref_updates_display_mode`, `pref_updates_columns` | List, 0 | History / Updates layout | yes (Dominican4Life) |
| Anime library sheet (filters, sort, badges, group) | `anime_library_*` | see table | anime library | yes |

### Cross-feature impact map

- Changing a **getter's key string** in any `*Preferences` class silently resets that setting for every user and
  breaks restore of older backups (keys are stored and restored by name). The fork's `skip_filtered` reuse shows
  keys can be shared on purpose; check with `grep '"<key>"'` before renaming or reusing one.
- Changing **`AnimeLibraryPrefs`** getter names collides easily with `LibraryPreferences` (same names:
  `downloadBadge`, `localBadge`, `filterDownloaded`…); a wrong import compiles and silently uses the manga value.
- Changing **`libraryPreferences.displayMode()` / columns / `libraryPanoramaStyle`** affects manga library, anime
  library, History and Updates at once.
- Changing **`DecoderPreferences.effectiveVideoSettings()`** (`PlayerProfiles.kt`) changes what every Decoder switch
  means; the preview player (`AnimePreviewPlayer.kt`) does *not* go through it, so the two players can decode
  differently.
- Changing **`PlayerActivity.applyStreamCache`** affects Performance profile (cacheScale), buffer handoff and
  network-aware playback together.
- Changing **`PauseBetweenTitles`** (range parsing, defaults) affects library update, anime update, both
  Refresh-missing-covers jobs and both migrations.
- Changing **`AnimeLibraryUpdateJob.setupTask`** or the manga restrictions row affects the anime schedule; the anime
  job is re-set on every app start from `MainActivity`.
- Changing **`BadgeCorner` keys/defaults** (`domain/ui/BadgeCorner.kt`) resets users' corner layouts; adding a badge
  type needs a one-shot migration flag like `source_type_badge_added`, or old layouts never show it.
- Changing **player layout defaults** (`pref_*_controls`) does nothing for users who already saved a layout; add a
  one-shot like `addEpisodesButton()`.
- Changing **`JsPreferences.of()`** naming breaks JS source settings *and* the APK source that shares the id.
- Adding a setting with **`appStateKey`** keeps it out of backups; with a plain key it will be backed up and
  restored, including id-keyed values that may not match after a restore.

### Build pitfalls seen in this area

- `getObjectFromString` is a member of `PreferenceStore`, not an extension: an `import
  tachiyomi.core.common.preference.getObjectFromString` fails the build (1c15d291). `getEnum` *is* an extension
  and does need `import tachiyomi.core.common.preference.getEnum`.
- `Preference.PreferenceItem.InfoPreference` has no `enabled` parameter (7d617511 removed `enabled = isIntroSkipEnabled`).
- `NetworkPreferences.verboseLogging()` does not exist in this fork (commented out by KMK); ported AniZen code that
  used it had to be changed (7d617511).
- Composable reads (`stringResource`, `MaterialTheme.colorScheme`) inside a non-composable lambda/function that builds
  `AppBar.Action`s don't compile; hoist them into the composable first (534da8e3, `BrowseTab.withAnimeSwitch`).
- A settings screen rewrite dropped the `DateFormats` list from `SettingsAppearanceScreen.kt` and broke the build;
  it was restored at the bottom of the file (ab0aaa0a).

### How to check settings from the owner's side

- A setting that "does nothing": first see the tables above (dead list, overrides). Player settings apply on the
  next episode opened; job settings on the next run.
- Logs screen (`tachiyomi/ui/logs/LogsScreen.kt`, the bug icon on the home navigation rail; AppLog): tag `botcheck` for "Open bot
  checks automatically". Lines starting `not opened:` give the reason (`"Open bot checks automatically" is off`,
  `the app isn't on screen`, `… isn't one of your sources' sites`, `… is turned off`); other `botcheck` lines come
  from `BotCheckCoordinator` while it opens and retries. Player settings are checked with the player's own log
  (Playback problem › Copy log, `PlayerDebugLog`): `[player] Cache: N MiB, read-ahead S s` shows the Performance
  profile / buffer handoff / Network-aware cache sizing; `[network] Metered network: 'X' -> 'Y'` shows the
  Network-aware quality cap.
- To see raw values, a backup's app settings list every non-app-state key.
