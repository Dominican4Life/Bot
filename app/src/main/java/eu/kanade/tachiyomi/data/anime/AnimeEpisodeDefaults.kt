package eu.kanade.tachiyomi.data.anime

import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: the episode sheet's "Set as default" and its show/hide choices for episode rows */
object AnimeEpisodeDefaults {
    private val store get() = Injekt.get<PreferenceStore>()

    /** Episode sort, filters and display given to newly added anime (0 = the app's own default) */
    fun flags() = store.getLong("anime_default_episode_flags", 0L)

    /** Show the episode's preview picture when the source has one */
    fun showPreviews() = store.getBoolean("anime_episode_show_previews", true)

    /** Show the episode's summary when the source has one */
    fun showSummaries() = store.getBoolean("anime_episode_show_summaries", true)
}
