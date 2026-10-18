package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.domain.ui.BadgeCorner
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.presentation.components.ProvideCoverFillSettings
import eu.kanade.presentation.components.favoriteBadgeSlot
import eu.kanade.presentation.components.rememberFavoriteBadgeCorner
import eu.kanade.presentation.components.rememberWideCovers
import eu.kanade.presentation.components.wideSpan
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.tachiyomi.R
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.RankedSearchMetadata
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPrefAsState
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun BrowseSourceComfortableGrid(
    mangaList: LazyPagingItems<StateFlow</* SY --> */Pair<Manga, RaisedSearchMetadata?>/* SY <-- */>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    // KMK -->
    selection: List<Manga>,
    usePanoramaCover: Boolean = false,
    /** Held by the screen so each tab keeps its scroll position */
    gridState: LazyGridState = rememberLazyGridState(),
    // KMK <--
) {
    // KMK -->
    val panoramaStyle by remember { Injekt.get<UiPreferences>().browsePanoramaStyle() }.collectPrefAsState()
    val doubleWide = usePanoramaCover && panoramaStyle == PanoramaStyle.WIDE
    // Wide covers take two places in the "Wide" style; read here, not inside the span lambda
    val wideCovers = rememberWideCovers()
    val wide = wideCovers.ids
    // KMK <--
    // KMK: the color fill settings are read once for the whole grid
    ProvideCoverFillSettings {
        LazyVerticalGrid(
            columns = columns,
            state = gridState,
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
            horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
        ) {
            if (mangaList.loadState.prepend is LoadState.Loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BrowseSourceLoadingItem()
                }
            }

            items(
                count = mangaList.itemCount,
                // KMK -->
                span = { index ->
                    wideSpan(doubleWide, wide, if (doubleWide) mangaList.peek(index)?.value?.first?.id else null)
                },
                // KMK <--
            ) { index ->
                // SY -->
                val pair by mangaList[index]?.collectAsState() ?: return@items
                val manga = pair.first
                val metadata = pair.second
                // SY <--

                BrowseSourceComfortableGridItem(
                    manga = manga,
                    // SY -->
                    metadata = metadata,
                    // SY <--
                    onClick = { onMangaClick(manga) },
                    onLongClick = { onMangaLongClick(manga) },
                    // KMK -->
                    isSelected = selection.fastAny { selected -> selected.id == manga.id },
                    usePanoramaCover = usePanoramaCover,
                    panoramaStyle = panoramaStyle,
                    onCoverRatio = { if (doubleWide) wideCovers.report(manga.id, it) },
                    // KMK <--
                )
            }

            if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BrowseSourceLoadingItem()
                }
            }
        }
    }
}

@Composable
internal fun BrowseSourceComfortableGridItem(
    manga: Manga,
    // SY -->
    metadata: RaisedSearchMetadata?,
    // SY <--
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    // KMK -->
    isSelected: Boolean = false,
    usePanoramaCover: Boolean,
    panoramaStyle: PanoramaStyle = PanoramaStyle.FIT,
    onCoverRatio: ((Float) -> Unit)? = null,
    // KMK <--
) {
    // KMK -->
    val favoriteCorner = rememberFavoriteBadgeCorner()
    // KMK <--
    MangaComfortableGridItem(
        // KMK: with the badge a JS source gave the entry, if any (Browse only)
        title = eu.kanade.tachiyomi.sumizuri.SumizuriListBadges.browseTitle(manga.source, manga.url, manga.title),
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            ogUrl = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        // KMK -->
        isSelected = isSelected,
        usePanoramaCover = usePanoramaCover,
        fitToPanoramaCover = true,
        panoramaStyle = panoramaStyle,
        onCoverRatio = onCoverRatio,
        // KMK <--
        coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        // KMK --> "In library" goes in the corner chosen in Settings › Library › Badges
        coverBadgeStart = favoriteBadgeSlot(BadgeCorner.TopLeft, favoriteCorner, manga.favorite),
        coverBadgeBottomStart = favoriteBadgeSlot(BadgeCorner.BottomLeft, favoriteCorner, manga.favorite),
        coverBadgeBottomEnd = favoriteBadgeSlot(BadgeCorner.BottomRight, favoriteCorner, manga.favorite),
        // KMK <--
        // SY -->
        coverBadgeEnd = {
            // KMK -->
            if (favoriteCorner == BadgeCorner.TopRight) InLibraryBadge(enabled = manga.favorite)
            // KMK <--
            if (metadata is MangaDexSearchMetadata) {
                metadata.followStatus?.let { followStatus ->
                    val text = LocalResources.current
                        .let { resources ->
                            remember(resources, followStatus) {
                                resources.getStringArray(R.array.md_follows_options)
                                    .getOrNull(followStatus)
                            }
                        }
                        ?: return@let
                    Badge(
                        text = text,
                        color = MaterialTheme.colorScheme.tertiary,
                        textColor = MaterialTheme.colorScheme.onTertiary,
                    )
                }
                metadata.relation?.let {
                    Badge(
                        text = stringResource(it.res),
                        color = MaterialTheme.colorScheme.tertiary,
                        textColor = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            } else if (metadata is RankedSearchMetadata) {
                metadata.rank?.let {
                    Badge(
                        text = "+$it",
                        color = MaterialTheme.colorScheme.tertiary,
                        textColor = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            }
        },
        // SY <--
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
