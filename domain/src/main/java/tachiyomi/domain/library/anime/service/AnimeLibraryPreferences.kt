package tachiyomi.domain.library.anime.service

import aniyomi.domain.anime.SeasonDisplayMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.library.anime.model.AnimeLibrarySort
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * Library settings for anime, kept apart from the manga [tachiyomi.domain.library.service.LibraryPreferences]
 * so the anime library can have its own filter, sort, display and group options.
 *
 * Ported from Aniyomi.
 */
class AnimeLibraryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun displayMode() = preferenceStore.getObject(
        "pref_display_mode_animelib",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    fun animeSortingMode() = preferenceStore.getObject(
        "animelib_sorting_mode",
        AnimeLibrarySort.default,
        AnimeLibrarySort.Serializer::serialize,
        AnimeLibrarySort.Serializer::deserialize,
    )

    fun lastUpdatedTimestamp() =
        preferenceStore.getLong(Preference.appStateKey("animelib_update_last_timestamp"), 0L)

    fun showContinueViewingButton() =
        preferenceStore.getBoolean("display_continue_watching_button", false)

    // Category

    fun categoryTabs() = preferenceStore.getBoolean("animelib_display_category_tabs", true)

    fun categoryNumberOfItems() = preferenceStore.getBoolean("animelib_display_number_of_items", false)

    fun categorizedDisplaySettings() = preferenceStore.getBoolean("animelib_categorized_display", false)

    fun hideHiddenCategoriesSettings() = preferenceStore.getBoolean("animelib_hidden_categories", false)

    // Badges

    fun downloadBadge() = preferenceStore.getBoolean("animelib_display_download_badge", false)

    fun unseenBadge() = preferenceStore.getBoolean("animelib_display_unseen_badge", true)

    fun localBadge() = preferenceStore.getBoolean("animelib_display_local_badge", true)

    fun languageBadge() = preferenceStore.getBoolean("animelib_display_language_badge", false)

    fun useLangIcon() = preferenceStore.getBoolean("animelib_display_language_icon", true)

    fun sourceBadge() = preferenceStore.getBoolean("animelib_display_source_badge", true)

    fun newShowUpdatesCount() = preferenceStore.getBoolean("animelib_show_updates_count", true)

    fun randomAnimeSortSeed() = preferenceStore.getInt("library_random_anime_sort_seed", 0)

    // Columns

    fun animePortraitColumns() = preferenceStore.getInt("pref_animelib_columns_portrait_key", 0)

    fun animeLandscapeColumns() = preferenceStore.getInt("pref_animelib_columns_landscape_key", 0)

    // Filter

    fun filterDownloadedAnime() =
        preferenceStore.getEnum("pref_filter_animelib_downloaded_v2", TriState.DISABLED)

    fun filterUnseen() =
        preferenceStore.getEnum("pref_filter_animelib_unread_v2", TriState.DISABLED)

    fun filterStartedAnime() =
        preferenceStore.getEnum("pref_filter_animelib_started_v2", TriState.DISABLED)

    fun filterBookmarkedAnime() =
        preferenceStore.getEnum("pref_filter_animelib_bookmarked_v2", TriState.DISABLED)

    fun filterCompletedAnime() =
        preferenceStore.getEnum("pref_filter_animelib_completed_v2", TriState.DISABLED)

    fun filterTrackedAnime(id: Int) =
        preferenceStore.getEnum("pref_filter_animelib_tracked_${id}_v2", TriState.DISABLED)

    fun filterIntervalCustom() = preferenceStore.getEnum(
        "pref_filter_animelib_interval_custom",
        TriState.DISABLED,
    )

    // Update count

    fun newAnimeUpdatesCount() = preferenceStore.getInt("library_unseen_updates_count", 0)

    // Category

    fun defaultAnimeCategory() = preferenceStore.getInt(DEFAULT_ANIME_CATEGORY_PREF_KEY, -1)

    fun lastUsedAnimeCategory() = preferenceStore.getInt(Preference.appStateKey("last_used_anime_category"), 0)

    fun animeUpdateCategories() =
        preferenceStore.getStringSet(LIBRARY_UPDATE_ANIME_CATEGORIES_PREF_KEY, emptySet())

    fun animeUpdateCategoriesExclude() =
        preferenceStore.getStringSet(LIBRARY_UPDATE_ANIME_CATEGORIES_EXCLUDE_PREF_KEY, emptySet())

    // Episodes

    fun filterEpisodeBySeen() =
        preferenceStore.getLong("default_episode_filter_by_seen", Anime.SHOW_ALL)

    fun filterEpisodeByDownloaded() =
        preferenceStore.getLong("default_episode_filter_by_downloaded", Anime.SHOW_ALL)

    fun filterEpisodeByBookmarked() =
        preferenceStore.getLong("default_episode_filter_by_bookmarked", Anime.SHOW_ALL)

    fun filterEpisodeByFillermarked() =
        preferenceStore.getLong("default_episode_filter_by_fillermarked", Anime.SHOW_ALL)

    fun sortEpisodeBySourceOrNumber() = preferenceStore.getLong(
        "default_episode_sort_by_source_or_number",
        Anime.EPISODE_SORTING_SOURCE,
    )

    fun displayEpisodeByNameOrNumber() = preferenceStore.getLong(
        "default_episode_display_by_name_or_number",
        Anime.EPISODE_DISPLAY_NAME,
    )

    fun sortEpisodeByAscendingOrDescending() = preferenceStore.getLong(
        "default_episode_sort_by_ascending_or_descending",
        Anime.EPISODE_SORT_DESC,
    )

    fun showEpisodeThumbnailPreviews() = preferenceStore.getLong(
        "default_episode_show_thumbnail_previews",
        Anime.EPISODE_SHOW_PREVIEWS,
    )

    fun showEpisodeSummaries() = preferenceStore.getLong(
        "default_episode_show_summaries",
        Anime.EPISODE_SHOW_SUMMARIES,
    )

    fun setEpisodeSettingsDefault(anime: Anime) {
        filterEpisodeBySeen().set(anime.unseenFilterRaw)
        filterEpisodeByDownloaded().set(anime.downloadedFilterRaw)
        filterEpisodeByBookmarked().set(anime.bookmarkedFilterRaw)
        filterEpisodeByFillermarked().set(anime.fillermarkedFilterRaw)
        sortEpisodeBySourceOrNumber().set(anime.sorting)
        displayEpisodeByNameOrNumber().set(anime.displayMode)
        sortEpisodeByAscendingOrDescending().set(
            if (anime.sortDescending()) Anime.EPISODE_SORT_DESC else Anime.EPISODE_SORT_ASC,
        )
        showEpisodeThumbnailPreviews().set(anime.showPreviewsRaw)
        showEpisodeSummaries().set(anime.showSummariesRaw)
    }

    // Seasons

    fun filterSeasonByDownload() =
        preferenceStore.getLong("default_season_filter_by_downloaded", Anime.SHOW_ALL)

    fun filterSeasonByUnseen() =
        preferenceStore.getLong("default_season_filter_by_unseen", Anime.SHOW_ALL)

    fun filterSeasonByStarted() =
        preferenceStore.getLong("default_season_filter_by_started", Anime.SHOW_ALL)

    fun filterSeasonByCompleted() =
        preferenceStore.getLong("default_season_filter_by_completed", Anime.SHOW_ALL)

    fun filterSeasonByBookmarked() =
        preferenceStore.getLong("default_season_filter_by_bookmarked", Anime.SHOW_ALL)

    fun filterSeasonByFillermarked() =
        preferenceStore.getLong("default_season_filter_by_fillermarked", Anime.SHOW_ALL)

    fun sortSeasonBySourceOrNumber() = preferenceStore.getLong(
        "default_season_sort_by_source_or_number",
        Anime.SEASON_SORT_SOURCE,
    )

    fun sortSeasonByAscendingOrDescending() = preferenceStore.getLong(
        "default_season_sort_by_ascending_or_descending",
        Anime.SEASON_SORT_DESC,
    )

    fun seasonDisplayGridMode() = preferenceStore.getLong(
        "default_season_grid_display_mode",
        SeasonDisplayMode.toLong(SeasonDisplayMode.CompactGrid),
    )

    fun seasonDisplayGridSize() = preferenceStore.getInt("default_season_grid_display_size", 0)

    fun seasonDownloadOverlay() = preferenceStore.getBoolean("default_season_download_overlay", false)

    fun seasonUnseenOverlay() = preferenceStore.getBoolean("default_season_unseen_overlay", true)

    fun seasonLocalOverlay() = preferenceStore.getBoolean("default_season_local_overlay", true)

    fun seasonLangOverlay() = preferenceStore.getBoolean("default_season_lang_overlay", false)

    fun seasonContinueOverlay() = preferenceStore.getBoolean("default_season_continue_overlay", true)

    fun seasonDisplayMode() = preferenceStore.getLong(
        "default_season_display_mode",
        Anime.SEASON_DISPLAY_MODE_SOURCE,
    )

    fun setSeasonSettingsDefault(anime: Anime) {
        filterSeasonByDownload().set(anime.seasonDownloadedFilterRaw)
        filterSeasonByUnseen().set(anime.seasonUnseenFilterRaw)
        filterSeasonByStarted().set(anime.seasonStartedFilterRaw)
        filterSeasonByCompleted().set(anime.seasonCompletedFilterRaw)
        filterSeasonByBookmarked().set(anime.seasonBookmarkedFilterRaw)
        filterSeasonByFillermarked().set(anime.seasonFillermarkedFilterRaw)
        sortSeasonBySourceOrNumber().set(anime.seasonSorting)
        sortSeasonByAscendingOrDescending().set(
            if (anime.seasonSortDescending()) Anime.SEASON_SORT_DESC else Anime.SEASON_SORT_ASC,
        )
        seasonDisplayGridMode().set(SeasonDisplayMode.toLong(anime.seasonDisplayGridMode))
        seasonDisplayGridSize().set(anime.seasonDisplayGridSize)
        seasonDownloadOverlay().set(anime.seasonDownloadedOverlay)
        seasonUnseenOverlay().set(anime.seasonUnseenOverlay)
        seasonLocalOverlay().set(anime.seasonLocalOverlay)
        seasonLangOverlay().set(anime.seasonLangOverlay)
        seasonContinueOverlay().set(anime.seasonContinueOverlay)
        seasonDisplayMode().set(anime.seasonDisplayMode)
    }

    fun updateSeasonOnRefresh() = preferenceStore.getBoolean("pref_update_season_on_refresh", false)

    fun updateSeasonOnLibraryUpdate() =
        preferenceStore.getBoolean("pref_update_season_on_library_update", false)

    // Episode behavior

    fun swipeEpisodeStartAction() =
        preferenceStore.getEnum("pref_episode_swipe_end_action", EpisodeSwipeAction.ToggleSeen)

    fun swipeEpisodeEndAction() =
        preferenceStore.getEnum("pref_episode_swipe_start_action", EpisodeSwipeAction.ToggleBookmark)

    fun markDuplicateSeenEpisodeAsSeen() =
        preferenceStore.getStringSet("mark_duplicate_seen_episode_seen", emptySet())

    enum class EpisodeSwipeAction {
        ToggleSeen,
        ToggleBookmark,
        ToggleFillermark,
        Download,
        Disabled,
    }

    companion object {
        const val MARK_DUPLICATE_EPISODE_SEEN_NEW = "new_episode"
        const val MARK_DUPLICATE_EPISODE_SEEN_EXISTING = "existing_episode"

        const val DEFAULT_ANIME_CATEGORY_PREF_KEY = "default_anime_category"
        private const val LIBRARY_UPDATE_ANIME_CATEGORIES_PREF_KEY = "animelib_update_categories"
        private const val LIBRARY_UPDATE_ANIME_CATEGORIES_EXCLUDE_PREF_KEY = "animelib_update_categories_exclude"
        val categoryPreferenceKeys = setOf(
            DEFAULT_ANIME_CATEGORY_PREF_KEY,
            LIBRARY_UPDATE_ANIME_CATEGORIES_PREF_KEY,
            LIBRARY_UPDATE_ANIME_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}
