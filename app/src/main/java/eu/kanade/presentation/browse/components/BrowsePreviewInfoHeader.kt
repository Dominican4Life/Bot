package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
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

        // KMK (r123): the text and the tag rows in ONE swipe card (page 1 text, page 2 tags), dots under it, no
        // headings; the card keeps one height so the episode list below doesn't jump
        val tags = remember(manga.genre) { manga.genre?.filter { it.isNotBlank() }.orEmpty() }
        val chips = remember(manga.source, tags) {
            eu.kanade.presentation.manga.components.SearchMetadataChips(null, manga.source, tags)
        }
        val description = manga.description?.takeIf { it.isNotBlank() }
        SwipeInfoCard(
            description = description,
            tags = tags,
            chips = chips,
            onSearch = onSearch,
            onCopy = { tag ->
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText(tag, tag))
            },
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

/** KMK (r123): which page of the swipe card shows when a panel opens (kept while the app is open) */
private var swipeCardPage = 0

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwipeInfoCard(
    description: String?,
    tags: List<String>,
    chips: eu.kanade.presentation.manga.components.SearchMetadataChips?,
    onSearch: (query: String, global: Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    val pages = buildList {
        if (description != null) add(0)
        if (tags.isNotEmpty()) add(1)
    }
    if (pages.isEmpty()) return
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = swipeCardPage.coerceIn(0, pages.size - 1),
    ) { pages.size }
    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) { swipeCardPage = pagerState.currentPage }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    androidx.compose.foundation.pager.HorizontalPager(
        state = pagerState,
        pageSpacing = 12.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(CardHeight),
    ) { index ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(vertical = 10.dp),
        ) {
            when (pages[index]) {
                0 -> androidx.compose.foundation.text.selection.SelectionContainer {
                    androidx.compose.material3.Text(
                        text = description.orEmpty(),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                else -> if (chips != null) {
                    // grouped rows: label on the left, its values beside it (like the title page)
                    eu.kanade.presentation.manga.components.NamespaceTags(
                        tags = chips,
                        onClick = { onSearch(it, false) },
                        onLongClick = { onSearch(it, true) },
                        onDoubleClick = onCopy,
                    )
                } else {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        tags.forEach { tag ->
                            eu.kanade.presentation.manga.components.TagsChip(
                                text = tag,
                                onClick = { onSearch(tag, false) },
                                onLongClick = { onSearch(tag, true) },
                                onDoubleClick = { onCopy(tag) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (pages.size > 1) {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, androidx.compose.ui.Alignment.CenterHorizontally),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            pages.indices.forEach { i ->
                val selected = pagerState.currentPage == i
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .height(7.dp)
                        .width(if (selected) 18.dp else 7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            if (selected) {
                                androidx.compose.material3.MaterialTheme.colorScheme.primary
                            } else {
                                androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                )
            }
        }
    }
}

private val CardHeight = 150.dp
