package eu.kanade.tachiyomi.ui.anime.anilist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.swipeBetweenTabs
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** KMK: The two tabs of anime and manga global search: your sources' results, and AniList */
enum class GlobalSearchMode { RESULTS, ANILIST }

/**
 * KMK: The one row of buttons under global search's search bar, drawn like a source's Browse buttons (square until
 * picked, then the icon and name): Results and AniList, then, on the same row, the picked tab's own buttons:
 * Pinned / All / Has results for Results, or Genres / Season / Format / Status / Sort for AniList. The screenshot
 * search sits at the far right (anime only).
 */
@Composable
fun GlobalSearchModeRow(
    mode: GlobalSearchMode,
    onMode: (GlobalSearchMode) -> Unit,
    /** Null when no source is pinned (no Pinned button) */
    pinnedOnly: Boolean?,
    onPinnedOnly: (Boolean) -> Unit,
    onlyShowHasResults: Boolean,
    onToggleHasResults: () -> Unit,
    openFilter: AniListFilterTab?,
    onOpenFilter: (AniListFilterTab?) -> Unit,
    onRefreshGenres: () -> Unit,
    onScreenshot: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showAniList: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AniListTabChip(
                selected = mode == GlobalSearchMode.RESULTS,
                label = stringResource(KMR.strings.anilist_results),
                icon = Icons.Outlined.Search,
                onClick = { onMode(GlobalSearchMode.RESULTS) },
            )
            if (showAniList) {
                AniListTabChip(
                    selected = mode == GlobalSearchMode.ANILIST,
                    label = stringResource(KMR.strings.anilist_tab),
                    icon = Icons.Outlined.Public,
                    onClick = { onMode(GlobalSearchMode.ANILIST) },
                )
            }
            VerticalDivider(modifier = Modifier.height(24.dp))
            if (mode == GlobalSearchMode.RESULTS) {
                if (pinnedOnly != null) {
                    AniListTabChip(
                        selected = pinnedOnly,
                        label = stringResource(MR.strings.pinned_sources),
                        icon = Icons.Outlined.PushPin,
                        onClick = { onPinnedOnly(true) },
                    )
                    AniListTabChip(
                        selected = !pinnedOnly,
                        label = stringResource(MR.strings.all),
                        icon = Icons.Outlined.DoneAll,
                        onClick = { onPinnedOnly(false) },
                    )
                }
                AniListTabChip(
                    selected = onlyShowHasResults,
                    label = stringResource(MR.strings.has_results),
                    icon = Icons.Outlined.FilterList,
                    onClick = onToggleHasResults,
                )
            } else {
                AniListFilterTab.entries.forEach { tab ->
                    AniListTabChip(
                        selected = openFilter == tab,
                        label = stringResource(tab.label),
                        icon = tab.icon,
                        onClick = { onOpenFilter(if (openFilter == tab) null else tab) },
                        onLongClick = if (tab == AniListFilterTab.GENRES) onRefreshGenres else null,
                    )
                }
            }
        }
        if (onScreenshot != null && mode == GlobalSearchMode.ANILIST) {
            AniListTabChip(
                selected = false,
                label = stringResource(KMR.strings.anilist_screenshot_search),
                icon = Icons.Outlined.ImageSearch,
                onClick = onScreenshot,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * KMK: The AniList tab's results with the open filter list over them. Swiping left or right on the results goes
 * back to Results; swiping on the open list moves between the filter lists.
 */
@Composable
fun AniListSearchPane(
    model: AniListSearchModel,
    openFilter: AniListFilterTab?,
    onOpenFilter: (AniListFilterTab?) -> Unit,
    onClick: (AniListMedia) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AniListResults(
            model = model,
            onClick = onClick,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPadding),
        )
        AniListFilterPanel(open = openFilter, model = model, onOpen = onOpenFilter)
    }
}

/** KMK: Swiping left or right between Results and AniList */
fun Modifier.swipeBetweenModes(mode: GlobalSearchMode, onMode: (GlobalSearchMode) -> Unit, enabled: Boolean = true) =
    if (!enabled) {
        this
    } else {
        swipeBetweenTabs(count = 2, selected = mode.ordinal, onSelect = { onMode(GlobalSearchMode.entries[it]) })
    }
