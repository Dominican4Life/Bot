package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.domain.ui.BadgeCorner
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.presentation.components.readingProgress
import eu.kanade.presentation.components.rememberCoverBadgeCorners
import eu.kanade.presentation.components.rememberCoverProgressBarEnabled
import eu.kanade.presentation.manga.components.RatioSwitchToPanorama
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
    // Height / width of each loaded cover: wide ones take two places in the "Wide" style
    val ratios = remember { mutableStateMapOf<Long, Float>() }
    // KMK <--
    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        contentPadding = contentPadding,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        items(
            items = items,
            contentType = { "library_comfortable_grid_item" },
            // KMK -->
            span = { libraryItem ->
                val ratio = ratios[libraryItem.libraryManga.manga.id]
                if (doubleWide && ratio != null && ratio <= RatioSwitchToPanorama) {
                    GridItemSpan(minOf(2, maxLineSpan))
                } else {
                    GridItemSpan(1)
                }
            },
            // KMK <--
        ) { libraryItem ->
            val manga = libraryItem.libraryManga.manga
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
                coverData = MangaCover(
                    mangaId = manga.id,
                    sourceId = manga.source,
                    isMangaFavorite = manga.favorite,
                    ogUrl = manga.thumbnailUrl,
                    lastModified = manga.coverLastModified,
                ),
                // KMK --> badges go in the corners chosen in Settings › Library › Badges
                coverBadgeStart = badgeCorners.slot(BadgeCorner.TopLeft, libraryBadges(libraryItem)),
                coverBadgeEnd = badgeCorners.slot(BadgeCorner.TopRight, libraryBadges(libraryItem)),
                coverBadgeBottomStart = badgeCorners.slot(BadgeCorner.BottomLeft, libraryBadges(libraryItem)),
                coverBadgeBottomEnd = badgeCorners.slot(BadgeCorner.BottomRight, libraryBadges(libraryItem)),
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
                onCoverRatio = { ratios[manga.id] = it },
                // KMK <--
            )
        }
    }
}
