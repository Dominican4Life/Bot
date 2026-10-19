package eu.kanade.tachiyomi.data.anime

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: settings for the anime library update and anime downloads (Settings › Library and Settings › Downloads,
 * "Anime" groups). Kept apart from the manga preferences so each side can be set on its own.
 */
object AnimePrefs {

    private val store: PreferenceStore get() = Injekt.get()

    // Library update

    /** Hours between automatic anime updates. [UPDATE_WITH_MANGA] runs it with the manga update, 0 = never */
    fun updateInterval(): Preference<Int> = store.getInt("anime_update_interval", UPDATE_WITH_MANGA)

    /** Which anime the update skips, from [SKIP_COMPLETED], [SKIP_HAS_UNSEEN], [SKIP_NOT_STARTED], [SKIP_SMART] */
    fun updateSkip(): Preference<Set<String>> = store.getStringSet("anime_update_skip", setOf(SKIP_COMPLETED))

    /** Anime category ids to update (empty = all) */
    fun updateCategories(): Preference<Set<String>> = store.getStringSet("anime_update_categories", emptySet())

    fun updateCategoriesExclude(): Preference<Set<String>> =
        store.getStringSet("anime_update_categories_exclude", emptySet())

    // Downloads

    fun downloadOnlyOverWifi(): Preference<Boolean> = store.getBoolean("anime_download_only_wifi", true)

    /** Episodes downloaded at the same time, 1..5 */
    fun concurrentDownloads(): Preference<Int> = store.getInt("anime_download_concurrent", 1)

    fun deleteAfterSeen(): Preference<Boolean> = store.getBoolean("anime_delete_after_seen", false)

    /** With [deleteAfterSeen], bookmarked episodes stay on the device */
    fun deleteKeepBookmarked(): Preference<Boolean> = store.getBoolean("anime_delete_keep_bookmarked", true)

    /** Next episodes to download while watching (0 = off) */
    fun downloadAhead(): Preference<Int> = store.getInt("anime_download_ahead", 0)

    /** Download new episodes found by the library update */
    fun downloadNewEpisodes(): Preference<Boolean> = store.getBoolean("anime_download_new", false)

    /** Anime category ids whose new episodes are downloaded (empty = all) */
    fun downloadNewCategories(): Preference<Set<String>> = store.getStringSet("anime_download_new_categories", emptySet())

    fun downloadNewCategoriesExclude(): Preference<Set<String>> =
        store.getStringSet("anime_download_new_categories_exclude", emptySet())

    const val UPDATE_WITH_MANGA = -1

    const val SKIP_COMPLETED = "completed"
    const val SKIP_HAS_UNSEEN = "has_unseen"
    const val SKIP_NOT_STARTED = "not_started"
    const val SKIP_SMART = "smart"

    /** Category id used for anime in no category (matches the "Default" row of the category dialogs) */
    const val UNCATEGORIZED = 0L

    /**
     * True when an anime with [categoryIds] passes the include / exclude [included] / [excluded] category sets.
     * Anime in no category count as [UNCATEGORIZED].
     */
    fun matchesCategories(categoryIds: Set<Long>, included: Set<String>, excluded: Set<String>): Boolean {
        val ids = categoryIds.ifEmpty { setOf(UNCATEGORIZED) }.map { it.toString() }.toSet()
        if (excluded.isNotEmpty() && ids.any { it in excluded }) return false
        return included.isEmpty() || ids.any { it in included }
    }
}
