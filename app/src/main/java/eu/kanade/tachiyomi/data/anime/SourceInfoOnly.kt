package eu.kanade.tachiyomi.data.anime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getAndSet
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r117): "Source info only", per anime source (Browse ⋮, under Incognito). While it's on, titles from that
 * source use only what the source gives: no AniList episode pictures / titles / summaries, no next-episode airing
 * lookup and no title search on AniList. Tracking that you add yourself keeps working.
 *
 * Also the per-source default for "Show episode thumbnail" / "Show episode summary" (the Display tab's
 * "All titles from this source"), which titles added later from that source start with.
 */
object SourceInfoOnly {
    private val store get() = Injekt.get<PreferenceStore>()

    // KMK (r120): the switch is now "AniList" and OFF by default: a source uses AniList only once you turn it on in
    // its ⋮. `anilist_on` holds the sources where it's on; the r117 `source_info_only` set isn't read any more (those
    // sources are off anyway). "Source info only" = AniList off.
    private fun pref() = store.getStringSet("anilist_on", emptySet())

    private fun key(sourceId: Long) = "anime:$sourceId"

    /** True when AniList is OFF for this source (the default): only the source's own info */
    fun isOn(sourceId: Long?): Boolean = sourceId == null || key(sourceId) !in pref().get()

    fun subscribe(sourceId: Long): Flow<Boolean> =
        pref().changes().map { key(sourceId) !in it }.distinctUntilChanged()

    /** Flips the source's AniList switch */
    fun toggle(sourceId: Long) {
        val key = key(sourceId)
        pref().getAndSet { if (key in it) it - key else it + key }
    }

    /** -1 = follow the app default (Settings › Library), 0 = hide, 1 = show */
    private fun previewsDefault(sourceId: Long) = store.getInt("anime_source_show_previews_$sourceId", -1)
    private fun summariesDefault(sourceId: Long) = store.getInt("anime_source_show_summaries_$sourceId", -1)

    fun setSourceDefaults(sourceId: Long, previews: Boolean?, summaries: Boolean?) {
        previews?.let { previewsDefault(sourceId).set(if (it) 1 else 0) }
        summaries?.let { summariesDefault(sourceId).set(if (it) 1 else 0) }
    }

    /** [flags] (the app's new-anime flags) with this source's own picture / summary defaults, when it has any */
    fun applySourceDefaults(sourceId: Long, flags: Long): Long {
        var result = flags
        when (previewsDefault(sourceId).get()) {
            0 -> result = result or Anime.HIDE_PREVIEWS
            1 -> result = result and Anime.HIDE_PREVIEWS.inv()
        }
        when (summariesDefault(sourceId).get()) {
            0 -> result = result or Anime.HIDE_SUMMARIES
            1 -> result = result and Anime.HIDE_SUMMARIES.inv()
        }
        return result
    }
}
