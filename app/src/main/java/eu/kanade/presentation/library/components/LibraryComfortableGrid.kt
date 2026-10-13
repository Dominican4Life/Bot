package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.presentation.components.WideCovers
import eu.kanade.presentation.components.readingProgress
import eu.kanade.presentation.components.rememberCoverBadgeCorners
import eu.kanade.presentation.components.rememberCoverProgressBarEnabled
import eu.kanade.presentation.components.rememberWideCovers
import eu.kanade.presentation.components.wideSpan
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun LibraryComfortableGrid(
    items: List<LibraryItem>,
    columns: Int,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    // KMK -->
    usePanoramaCover: Boolean = false,
    // KMK <--
) {
    // KMK -->
    val badgeCorners = rememberCoverBadgeCorners()
    val progressBar = rememberCoverProgressBarEnabled()
    val panoramaStyle by remember { Injekt.get<UiPreferences>().libraryPanoramaStyle() }.collectAsState()
    val doubleWide = usePanoramaCover && panoramaStyle == PanoramaStyle.WIDE
    // Wide covers take two places in the "Wide" style; read here, not inside the span lambda
    val wideCovers = rememberWideCovers()
    val uniqueItems = rememberUniqueLibraryItems(items)
    // Covers already known to be wide (their shape is saved): placed right away, without redrawing the grid later
    val knownWide = remember(uniqueItems, doubleWide) {
        if (!doubleWide) {
            emptySet<Long>()
        } else {
            uniqueItems.mapNotNullTo(HashSet()) { item ->
                val id = item.libraryManga.manga.id
                // The saved shape is width / height (a book cover is about 0.7); wide means height / width <= the limit
                val widthOverHeight = MangaCover.coverRatioMap[id] ?: return@mapNotNullTo null
                id.takeIf { widthOverHeight > 0f && 1f / widthOverHeight <= WideCovers.WIDE_RATIO }
            }
        }
    }
    val found = wideCovers.ids
    val wide = remember(knownWide, found) { if (found.isEmpty()) knownWide else knownWide + found }
    // KMK <--
    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        contentPadding = contentPadding,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        items(
            // KMK: each title once, with its own key, so only covers that changed are redrawn
            items = uniqueItems,
            key = ::libraryItemKey,
            contentType = { "library_comfortable_grid_item" },
            // KMK -->
            span = { libraryItem -> wideSpan(doubleWide, wide, libraryItem.libraryManga.manga.id) },
            // KMK <--
        ) { libraryItem ->
            val manga = libraryItem.libraryManga.manga
            // KMK: badges worked out once per title
            val badges = rememberLibraryBadgeSlots(libraryItem, badgeCorners)
            MangaComfortableGridItem(
                // KMK -->
                progress = if (progressBar) {
                    readingProgress(libraryItem.libraryManga.readCount, libraryItem.libraryManga.totalChapters)
                } else {
                    null
                },
                // KMK <--
                isSelected = manga.id in selection,
                title = manga.title,
                coverData = rememberLibraryCover(manga),
                // KMK --> badges go in the corners chosen in Settings › Library › Badges
                coverBadgeStart = badges.topStart,
                coverBadgeEnd = badges.topEnd,
                coverBadgeBottomStart = badges.bottomStart,
                coverBadgeBottomEnd = badges.bottomEnd,
                // KMK <--
                onLongClick = { onLongClick(libraryItem.libraryManga) },
                onClick = { onClick(libraryItem.libraryManga) },
                onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                    { onClickContinueReading(libraryItem.libraryManga) }
                } else {
                    null
                },
                // KMK -->
                usePanoramaCover = usePanoramaCover,
                panoramaStyle = panoramaStyle,
                onCoverRatio = { if (doubleWide && manga.id !in knownWide) wideCovers.report(manga.id, it) },
                // KMK <--
            )
        }
    }
}
