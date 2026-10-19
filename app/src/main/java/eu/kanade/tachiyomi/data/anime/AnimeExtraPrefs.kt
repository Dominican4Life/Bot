package eu.kanade.tachiyomi.data.anime

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: per-anime values that have no column in the anime tables yet, so they live in preferences keyed by the
 * anime id: the user's notes and the scanlators hidden from the episode list. Shared by the anime screen and
 * backup/restore.
 */
object AnimeExtraPrefs {

    private val store: PreferenceStore get() = Injekt.get()

    fun notes(animeId: Long): Preference<String> =
        store.getString("anime_notes_$animeId", "")

    fun excludedScanlators(animeId: Long): Preference<Set<String>> =
        store.getStringSet("anime_excluded_scanlators_$animeId", emptySet())
}
