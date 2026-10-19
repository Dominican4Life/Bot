package eu.kanade.tachiyomi.data.anime

import tachiyomi.core.common.preference.TriState

/**
 * KMK: seasons of an anime, saved like Anikku's: each season is its own anime row with [Anime.parentId] set to the
 * anime it belongs to. The parent's [Anime.seasonFlags] hold how its season list is sorted, filtered and shown.
 *
 * The filter and sort bits are the same as Anikku's; direction, layout and titles are KMK's own (default 0 = the
 * horizontal row in the source's order, with titles).
 */
object AnimeSeasonFlags {
    /** Default (0) is ascending: the source's order, or lowest number / A first */
    const val SORT_DESCENDING = 0x00000001L

    const val SHOW_DOWNLOADED = 0x00000002L
    const val SHOW_NOT_DOWNLOADED = 0x00000004L
    const val SHOW_UNSEEN = 0x00000008L
    const val SHOW_SEEN = 0x00000010L
    const val SHOW_STARTED = 0x00000020L
    const val SHOW_NOT_STARTED = 0x00000040L
    const val SHOW_COMPLETED = 0x00000080L
    const val SHOW_NOT_COMPLETED = 0x00000100L

    const val ALL_FILTERS = SHOW_DOWNLOADED or SHOW_NOT_DOWNLOADED or SHOW_UNSEEN or SHOW_SEEN or
        SHOW_STARTED or SHOW_NOT_STARTED or SHOW_COMPLETED or SHOW_NOT_COMPLETED

    const val SORT_SOURCE = 0x00000000L
    const val SORT_NUMBER = 0x00002000L
    const val SORT_ALPHABET = 0x00006000L
    const val SORT_MASK = 0x0000E000L

    /** Layout of the season list: [LAYOUT_ROW], [LAYOUT_GRID] or [LAYOUT_LIST] */
    const val LAYOUT_ROW = 0x00000000L
    const val LAYOUT_GRID = 0x10000000L
    const val LAYOUT_LIST = 0x20000000L
    const val LAYOUT_MASK = 0x30000000L

    const val HIDE_TITLES = 0x40000000L

    /** Sets one filter's pair of bits from a [TriState] */
    fun withFilter(flags: Long, include: Long, exclude: Long, state: TriState): Long =
        Anime.withTriState(flags, include, exclude, state)

    /** Picking the current sort again flips its direction */
    fun withSort(flags: Long, mode: Long): Long =
        if (flags and SORT_MASK == mode) {
            flags xor SORT_DESCENDING
        } else {
            (flags and SORT_MASK.inv() and SORT_DESCENDING.inv()) or mode
        }

    fun withLayout(flags: Long, layout: Long): Long = (flags and LAYOUT_MASK.inv()) or layout

    fun withTitles(flags: Long, show: Boolean): Long = if (show) flags and HIDE_TITLES.inv() else flags or HIDE_TITLES
}

val Anime.seasonSortMode: Long
    get() = seasonFlags and AnimeSeasonFlags.SORT_MASK

val Anime.seasonSortDescending: Boolean
    get() = seasonFlags and AnimeSeasonFlags.SORT_DESCENDING != 0L

val Anime.seasonLayout: Long
    get() = seasonFlags and AnimeSeasonFlags.LAYOUT_MASK

val Anime.seasonShowTitles: Boolean
    get() = seasonFlags and AnimeSeasonFlags.HIDE_TITLES == 0L

val Anime.seasonFilterActive: Boolean
    get() = seasonFlags and AnimeSeasonFlags.ALL_FILTERS != 0L

private fun Anime.seasonTriState(include: Long, exclude: Long): TriState = when {
    seasonFlags and include != 0L -> TriState.ENABLED_IS
    seasonFlags and exclude != 0L -> TriState.ENABLED_NOT
    else -> TriState.DISABLED
}

val Anime.seasonDownloadedFilter: TriState
    get() = seasonTriState(AnimeSeasonFlags.SHOW_DOWNLOADED, AnimeSeasonFlags.SHOW_NOT_DOWNLOADED)

val Anime.seasonUnseenFilter: TriState
    get() = seasonTriState(AnimeSeasonFlags.SHOW_UNSEEN, AnimeSeasonFlags.SHOW_SEEN)

val Anime.seasonStartedFilter: TriState
    get() = seasonTriState(AnimeSeasonFlags.SHOW_STARTED, AnimeSeasonFlags.SHOW_NOT_STARTED)

val Anime.seasonCompletedFilter: TriState
    get() = seasonTriState(AnimeSeasonFlags.SHOW_COMPLETED, AnimeSeasonFlags.SHOW_NOT_COMPLETED)

/** KMK: reads a season number from a season's title, like Anikku's SeasonRecognition (simplified). */
object AnimeSeasonRecognition {
    private const val NUMBER_PATTERN = """([0-9]+)(\.[0-9]+)?"""
    private val basic = Regex("""(?<=\bs\.|\bs|season|part|cour) *$NUMBER_PATTERN""")
    private val number = Regex(NUMBER_PATTERN)
    private val tags = Regex("""^\s*(\[[^]]+]\s*|\([^)]+\)\s*)+|\s*(\[[^]]+]\s*|\([^)]+\)\s*)+$""")
    private val unwanted = Regex("""\b\d+p\b|\d+x\d+|Hi10|\(\d+\)""")

    /** [sourceNumber] wins when the source gave one */
    fun parse(animeTitle: String, seasonTitle: String, sourceNumber: Double): Double {
        if (sourceNumber == -2.0 || sourceNumber > -1.0) return sourceNumber
        val clean = seasonTitle.lowercase()
            .replace(animeTitle.lowercase(), "")
            .replace(tags, "")
            .replace(unwanted, "")
            .replace(',', '.')
            .replace('-', ' ')
            .trim()
        val match = basic.find(clean) ?: number.find(clean) ?: return -1.0
        val whole = match.groups[1]?.value?.toDoubleOrNull() ?: return -1.0
        val decimal = match.groups[2]?.value?.toDoubleOrNull() ?: 0.0
        return whole + decimal
    }
}
