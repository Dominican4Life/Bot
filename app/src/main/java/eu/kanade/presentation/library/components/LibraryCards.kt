package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import eu.kanade.domain.ui.BadgeCorner
import eu.kanade.presentation.components.BadgeCorners
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover

/**
 * KMK: What a library cover shows, worked out once per title instead of on every redraw (the way Kototoro's
 * library keeps a ready-made card per title). A cover only redraws when its own title changes.
 */
@Immutable
internal class LibraryBadgeSlots(
    val topStart: (@Composable RowScope.() -> Unit)?,
    val topEnd: (@Composable RowScope.() -> Unit)?,
    val bottomStart: (@Composable RowScope.() -> Unit)?,
    val bottomEnd: (@Composable RowScope.() -> Unit)?,
)

/** The badges of [item] in the corners chosen in Settings › Library › Badges */
@Composable
internal fun rememberLibraryBadgeSlots(item: LibraryItem, corners: BadgeCorners): LibraryBadgeSlots =
    remember(item, corners) {
        val all = libraryBadges(item)
        LibraryBadgeSlots(
            topStart = corners.slot(BadgeCorner.TopLeft, all),
            topEnd = corners.slot(BadgeCorner.TopRight, all),
            bottomStart = corners.slot(BadgeCorner.BottomLeft, all),
            bottomEnd = corners.slot(BadgeCorner.BottomRight, all),
        )
    }

/** The cover of [manga]; making one looks up the title's custom cover, so it's kept */
@Composable
internal fun rememberLibraryCover(manga: Manga): MangaCover =
    remember(manga.id, manga.source, manga.favorite, manga.thumbnailUrl, manga.coverLastModified) {
        MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            ogUrl = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        )
    }

/** Each title once, so every cover can have its own key */
@Composable
internal fun rememberUniqueLibraryItems(items: List<LibraryItem>): List<LibraryItem> =
    remember(items) { items.distinctBy { it.libraryManga.manga.id } }

/** The key of a cover in a library grid or list: its title, so covers that didn't change aren't redrawn */
internal fun libraryItemKey(item: LibraryItem): Any = "library_${item.libraryManga.manga.id}"
