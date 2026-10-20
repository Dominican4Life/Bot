package eu.kanade.tachiyomi.data.anime

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: the episode sheet's "Set as default" and the app-wide defaults for episode rows */
object AnimeEpisodeDefaults {
    private val store get() = Injekt.get<PreferenceStore>()

    /** Episode sort, filters and display given to newly added anime (0 = the app's own default) */
    fun flags() = store.getLong("anime_default_episode_flags", 0L)

    /** Default for newly added anime: show the episode's preview picture when the source has one */
    fun showPreviews() = store.getBoolean("anime_episode_show_previews", true)

    /** Default for newly added anime: show the episode's summary when the source has one */
    fun showSummaries() = store.getBoolean("anime_episode_show_summaries", true)

    /** Settings › Library › Season grouping: used by anime whose own setting is "Default" (like AniZen) */
    fun seasonGrouping() = store.getEnum("anime_episode_season_grouping", AnimeSeasonGrouping.Tabs)

    /** The season tab last picked on an anime's page */
    fun lastSelectedSeason(animeId: Long) = store.getString("anime_last_selected_season_$animeId", "")

    /** Episode flags for an anime added now: "Set as default" plus the picture / summary defaults */
    fun newAnimeFlags(): Long {
        val base = flags().get() and
            (Anime.HIDE_PREVIEWS or Anime.HIDE_SUMMARIES or Anime.SEASON_GROUP_MASK).inv()
        return base or
            (if (showPreviews().get()) 0L else Anime.HIDE_PREVIEWS) or
            (if (showSummaries().get()) 0L else Anime.HIDE_SUMMARIES)
    }

    /** This anime's season grouping, following the app setting when it's on "Default" */
    fun seasonGrouping(anime: Anime): AnimeSeasonGrouping = when (anime.seasonGroupRaw) {
        Anime.SEASON_GROUP_HEADERS -> AnimeSeasonGrouping.Headers
        Anime.SEASON_GROUP_TABS -> AnimeSeasonGrouping.Tabs
        Anime.SEASON_GROUP_OFF -> AnimeSeasonGrouping.Disabled
        else -> seasonGrouping().get()
    }

    /** Before r57 pictures / summaries were app-wide; copies a hidden choice onto the anime once */
    fun displayFlagsMigrated() = store.getBoolean("anime_episode_display_flags_migrated", false)
}

/** KMK: how the episode list is split by season (AniZen's SeasonGrouping) */
enum class AnimeSeasonGrouping {
    Disabled,
    Headers,
    Tabs,
}

/**
 * KMK: Works out the season of each episode like AniZen's EpisodeSeasonUtils: "S2", "Season 2", "s2e1" in the name,
 * Season 0 or 0.x numbering is "Specials", and episodes without a season name are "Extras".
 */
object AnimeEpisodeSeasons {
    // Added |_ to delimiters to support patterns like _s1e1_
    private val seasonRegex = Regex("""(?i)(?:^|\b|\s|\[|_)(?:s|season\s*)(\d+)(?:\s|e|x|\||-|\.|\b|\]|_|$)""")

    const val SPECIALS = "specials"
    const val EXTRAS = "extras"

    /** Season number from the episode name, or null */
    private fun seasonNumber(episode: Episode): Int? =
        seasonRegex.find(episode.name)?.groupValues?.get(1)?.toIntOrNull()

    private fun isSeasonZero(episode: Episode): Boolean =
        seasonNumber(episode) == 0 || (episode.episodeNumber >= 0 && episode.episodeNumber < 1.0)

    /** The season number of a key made here, or null for [SPECIALS] / [EXTRAS] */
    fun numberOf(key: String): Int? = key.removePrefix("s:").takeIf { key.startsWith("s:") }?.toIntOrNull()

    /**
     * Season key of each episode ("s:2", [SPECIALS], [EXTRAS]) and the seasons in order (seasons by number, then
     * specials, then extras). [inSourceOrder] is the episodes in the source's order.
     */
    fun group(inSourceOrder: List<Episode>): Pair<Map<Long, String>, List<String>> {
        // The source may list newest first: go oldest to newest to find the blocks
        val firstWithNumber = inSourceOrder.firstOrNull { it.episodeNumber >= 0 }
        val lastWithNumber = inSourceOrder.lastOrNull { it.episodeNumber >= 0 }
        val descending = firstWithNumber != null && lastWithNumber != null && firstWithNumber !== lastWithNumber &&
            firstWithNumber.episodeNumber > lastWithNumber.episodeNumber
        val chronological = if (descending) inSourceOrder.reversed() else inSourceOrder

        // A new block starts whenever the season name (or being a special) changes
        val blocks = mutableListOf<MutableList<Episode>>()
        var previous: Episode? = null
        for (episode in chronological) {
            val prev = previous
            val newBlock = prev == null ||
                isSeasonZero(episode) != isSeasonZero(prev) ||
                seasonNumber(episode) != seasonNumber(prev)
            if (newBlock || blocks.isEmpty()) blocks.add(mutableListOf())
            blocks.last().add(episode)
            previous = episode
        }

        val mapping = HashMap<Long, String>()
        val seasons = mutableListOf<String>()
        blocks.forEach { block ->
            val key = when {
                block.any(::isSeasonZero) -> SPECIALS
                else -> block.firstNotNullOfOrNull { ep -> seasonNumber(ep)?.takeIf { it != 0 } }?.let { "s:$it" }
                    ?: EXTRAS
            }
            if (key !in seasons) seasons.add(key)
            block.forEach { mapping[it.id] = key }
        }
        return mapping to seasons.sortedWith(comparator)
    }

    /** Seasons by number, then specials, then extras */
    private val comparator = compareBy<String>(
        { key ->
            when (key) {
                SPECIALS -> 1
                EXTRAS -> 2
                else -> 0
            }
        },
        { numberOf(it) ?: 0 },
    )
}
