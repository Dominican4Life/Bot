package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.domain.ui.BadgeCorner
import eu.kanade.presentation.components.rememberCoverBadgeCorners
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover

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
        ) { libraryItem ->
            val manga = libraryItem.libraryManga.manga
            MangaComfortableGridItem(
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
                // KMK <--
            )
        }
    }
}
