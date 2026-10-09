package eu.kanade.presentation.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.domain.ui.BadgeCorner
import eu.kanade.domain.ui.UiPreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: Which corner each badge goes in, from Settings › Library › Badges. */
@Immutable
class BadgeCorners(private val byCorner: Map<BadgeCorner, List<String>>) {

    fun badgesIn(corner: BadgeCorner): List<String> = byCorner[corner].orEmpty()

    /**
     * The content for one corner: the [available] badges assigned to it, in a fixed order,
     * or null when the corner is empty.
     */
    fun slot(
        corner: BadgeCorner,
        available: Map<String, @Composable RowScope.() -> Unit>,
    ): (@Composable RowScope.() -> Unit)? {
        val badges = badgesIn(corner).mapNotNull { available[it] }
        if (badges.isEmpty()) return null
        return { badges.forEach { it() } }
    }
}

@Composable
private fun rememberCorners(source: Boolean): BadgeCorners {
    val prefs = remember { Injekt.get<UiPreferences>() }
    val corners = BadgeCorner.entries.associateWith { corner ->
        val pref = remember(corner) { if (source) prefs.sourceBadges(corner) else prefs.coverBadges(corner) }
        val value by pref.collectAsState()
        value
    }
    return remember(corners) {
        // Keep a stable order inside each corner
        val order = if (source) eu.kanade.domain.ui.SourceBadge.all else eu.kanade.domain.ui.CoverBadge.all
        BadgeCorners(corners.mapValues { (_, set) -> order.filter { it in set } })
    }
}

@Composable
fun rememberCoverBadgeCorners(): BadgeCorners = rememberCorners(source = false)

@Composable
fun rememberSourceBadgeCorners(): BadgeCorners = rememberCorners(source = true)

/** The corner of the "in library" badge on browse covers, or null when it's hidden */
@Composable
fun rememberFavoriteBadgeCorner(): BadgeCorner? {
    val corners = rememberCoverBadgeCorners()
    return BadgeCorner.entries.firstOrNull { eu.kanade.domain.ui.CoverBadge.FAVORITE in corners.badgesIn(it) }
}

/** The "in library" badge for [corner], when that's where it goes */
fun favoriteBadgeSlot(
    corner: BadgeCorner,
    favoriteCorner: BadgeCorner?,
    favorite: Boolean,
): (@Composable RowScope.() -> Unit)? {
    if (corner != favoriteCorner || !favorite) return null
    return { eu.kanade.presentation.browse.components.InLibraryBadge(enabled = true) }
}
