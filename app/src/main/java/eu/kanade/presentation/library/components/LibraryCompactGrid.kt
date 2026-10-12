package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.readingProgress
import eu.kanade.presentation.components.rememberCoverBadgeCorners
import eu.kanade.presentation.components.rememberCoverProgressBarEnabled
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryManga

@Composable
internal fun LibraryCompactGrid(
    items: List<LibraryItem>,
    showTitle: Boolean,
    columns: Int,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
) {
    // KMK -->
    val badgeCorners = rememberCoverBadgeCorners()
    val progressBar = rememberCoverProgressBarEnabled()
    val uniqueItems = rememberUniqueLibraryItems(items)
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
            contentType = { "library_compact_grid_item" },
        ) { libraryItem ->
            val manga = libraryItem.libraryManga.manga
            // KMK: badges worked out once per title
            val badges = rememberLibraryBadgeSlots(libraryItem, badgeCorners)
            MangaCompactGridItem(
                // KMK -->
                progress = if (progressBar) {
                    readingProgress(libraryItem.libraryManga.readCount, libraryItem.libraryManga.totalChapters)
                } else {
                    null
                },
                // KMK <--
                isSelected = manga.id in selection,
                title = manga.title.takeIf { showTitle },
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
            )
        }
    }
}
