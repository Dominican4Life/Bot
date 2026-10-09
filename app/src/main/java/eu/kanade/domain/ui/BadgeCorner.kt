package eu.kanade.domain.ui

/**
 * KMK: The four corners badges can be placed in, on covers and on source tiles.
 * Each badge lives in at most one corner; a badge in no corner is hidden.
 */
enum class BadgeCorner(
    val key: String,
    val defaultCoverBadges: Set<String>,
    val defaultSourceBadges: Set<String>,
) {
    TopLeft(
        "top_left",
        setOf(CoverBadge.DOWNLOADED, CoverBadge.UNREAD, CoverBadge.FAVORITE),
        setOf(SourceBadge.LANGUAGE, SourceBadge.NSFW),
    ),
    TopRight("top_right", setOf(CoverBadge.LANGUAGE, CoverBadge.SOURCE), setOf(SourceBadge.SETTINGS)),
    BottomLeft("bottom_left", emptySet(), setOf(SourceBadge.PIN)),
    BottomRight("bottom_right", emptySet(), setOf(SourceBadge.LATEST)),
}

/** Badges shown on manga/anime covers */
object CoverBadge {
    const val DOWNLOADED = "downloaded"
    const val UNREAD = "unread"
    const val LANGUAGE = "language"
    const val SOURCE = "source"
    const val FAVORITE = "favorite"

    val all = listOf(DOWNLOADED, UNREAD, LANGUAGE, SOURCE, FAVORITE)
}

/** Badges shown on source tiles in Browse */
object SourceBadge {
    const val LANGUAGE = "language"
    const val NSFW = "nsfw"
    const val SETTINGS = "settings"
    const val PIN = "pin"
    const val LATEST = "latest"

    val all = listOf(LANGUAGE, NSFW, SETTINGS, PIN, LATEST)
}
