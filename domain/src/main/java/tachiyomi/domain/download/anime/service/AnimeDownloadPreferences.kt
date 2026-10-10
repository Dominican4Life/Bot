package tachiyomi.domain.download.anime.service

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Download settings for anime episodes, kept apart from the manga
 * [tachiyomi.domain.download.service.DownloadPreferences].
 *
 * Ported from Aniyomi.
 */
class AnimeDownloadPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun useExternalDownloader() = preferenceStore.getBoolean("use_external_downloader", false)

    fun externalDownloaderSelection() = preferenceStore.getString("external_downloader_selection", "")

    fun autoDownloadWhileWatching() = preferenceStore.getInt("auto_download_while_watching", 0)

    fun downloadFillermarkedItems() = preferenceStore.getBoolean("pref_download_fillermarked", false)

    fun removeExcludeAnimeCategories() = preferenceStore.getStringSet(
        REMOVE_EXCLUDE_ANIME_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    fun downloadNewEpisodes() = preferenceStore.getBoolean("download_new_episode", false)

    fun downloadNewEpisodeCategories() = preferenceStore.getStringSet(
        DOWNLOAD_NEW_ANIME_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    fun downloadNewEpisodeCategoriesExclude() = preferenceStore.getStringSet(
        DOWNLOAD_NEW_ANIME_CATEGORIES_EXCLUDE_PREF_KEY,
        emptySet(),
    )

    fun numberOfDownloads() = preferenceStore.getInt("anime_download_slots", 1)

    fun downloadSpeedLimit() = preferenceStore.getInt("download_speed_limit", 0)

    fun downloadNewUnseenEpisodesOnly() =
        preferenceStore.getBoolean("download_new_unread_episodes_only", false)

    companion object {
        private const val REMOVE_EXCLUDE_ANIME_CATEGORIES_PREF_KEY = "remove_exclude_anime_categories"
        private const val DOWNLOAD_NEW_ANIME_CATEGORIES_PREF_KEY = "download_new_anime_categories"
        private const val DOWNLOAD_NEW_ANIME_CATEGORIES_EXCLUDE_PREF_KEY = "download_new_anime_categories_exclude"
        val categoryPreferenceKeys = setOf(
            REMOVE_EXCLUDE_ANIME_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_ANIME_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_ANIME_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}
