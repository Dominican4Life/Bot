package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import tachiyomi.domain.manga.model.Manga

/**
 * KMK: The top of the details page (title, author, status, library/WebView buttons, description and tags),
 * shown at the top of the browse chapters panel. Description and tags can be collapsed from their headers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowsePreviewInfoHeader(
    manga: Manga,
    source: Source,
    onAddToLibrary: () -> Unit,
    onWebView: () -> Unit,
    onOpenDetails: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
) {
    val sourceName = remember(source) { source.getNameForMangaInfo() }
    BrowsePreviewInfoHeader(
        manga = manga,
        sourceName = sourceName,
        onAddToLibrary = onAddToLibrary,
        onWebView = onWebView,
        onOpenDetails = onOpenDetails,
        onSearch = onSearch,
    )
}

/** KMK: Same header for anime, which come from anime sources */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowsePreviewInfoHeader(
    manga: Manga,
    sourceName: String,
    onAddToLibrary: () -> Unit,
    onWebView: () -> Unit,
    onOpenDetails: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
) {
    // KMK (r123): the title page's own left-pane pieces, so the Browse / Library preview panel looks like it: the
    // header (B with "Title page header" on: the whole cover with title / author / source on it), the buttons, and
    // the description with the grouped tag rows (label on the left, values beside it)
    val context = androidx.compose.ui.platform.LocalContext.current
    val coverRatio = remember(manga.id) { androidx.compose.runtime.mutableFloatStateOf(1f) }
    Column(modifier = Modifier.fillMaxWidth()) {
        eu.kanade.presentation.manga.components.MangaInfoBox(
            isTabletUi = true,
            appBarPadding = 0.dp,
            manga = manga,
            sourceName = sourceName,
            isStubSource = false,
            isSourceIncognito = false,
            onCoverClick = onOpenDetails,
            doSearch = onSearch,
            librarySearch = { onSearch(it, false) },
            onSourceClick = onOpenDetails,
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

        val tags = remember(manga.genre) { manga.genre?.filter { it.isNotBlank() } }
        eu.kanade.presentation.manga.components.ExpandableMangaDescription(
            defaultExpandState = true,
            description = manga.description,
            tagsProvider = { tags },
            notes = manga.notes,
            onTagSearch = { onSearch(it, false) },
            onCopyTagToClipboard = { tag ->
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText(tag, tag))
            },
            // Notes are edited on the full title page
            onEditNotes = onOpenDetails,
            searchMetadataChips = remember(manga.source, tags) {
                eu.kanade.presentation.manga.components.SearchMetadataChips(null, manga.source, tags)
            },
            doSearch = onSearch,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
