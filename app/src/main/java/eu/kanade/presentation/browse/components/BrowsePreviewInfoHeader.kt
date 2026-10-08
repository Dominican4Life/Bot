package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.manga.components.SearchMetadataChips
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.isIncognitoModeEnabled
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.domain.manga.model.Manga

/**
 * KMK: The top of the details page (cover, title, author, status, library/WebView buttons, description and
 * tags), shown at the top of the browse chapters panel.
 */
@Composable
fun BrowsePreviewInfoHeader(
    manga: Manga,
    source: Source,
    onAddToLibrary: () -> Unit,
    onWebView: () -> Unit,
    onOpenDetails: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val coverRatio = remember { mutableFloatStateOf(1f) }
    Column {
        MangaInfoBox(
            isTabletUi = true,
            appBarPadding = 0.dp,
            manga = manga,
            sourceName = remember(source) { source.getNameForMangaInfo() },
            isStubSource = false,
            isSourceIncognito = remember(source) { source.isIncognitoModeEnabled() },
            onCoverClick = onOpenDetails,
            doSearch = onSearch,
            librarySearch = { onSearch(it, false) },
            onSourceClick = {},
            onCoverLoaded = {},
            coverRatio = coverRatio,
        )
        MangaActionRow(
            favorite = manga.favorite,
            trackingCount = 0,
            nextUpdate = null,
            isUserIntervalMode = manga.fetchInterval < 0,
            onAddToLibraryClicked = onAddToLibrary,
            onWebViewClicked = onWebView,
            onWebViewLongClicked = null,
            // Tracking and update interval need the full details page
            onTrackingClicked = onOpenDetails,
            onEditIntervalClicked = null,
            onEditCategory = null,
            onMergeClicked = null,
            status = manga.status,
            interval = manga.fetchInterval,
        )
        ExpandableMangaDescription(
            defaultExpandState = false,
            description = manga.description,
            tagsProvider = { manga.genre },
            notes = manga.notes,
            onTagSearch = { onSearch(it, false) },
            onCopyTagToClipboard = { context.copyToClipboard(it, it) },
            onEditNotes = onOpenDetails,
            searchMetadataChips = remember(source.id, manga.genre) {
                SearchMetadataChips(null, source.id, manga.genre)
            },
            doSearch = onSearch,
        )
    }
}
