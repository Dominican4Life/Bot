package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.reader.SlidingSidePanel
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.coil.SourcePageThumbnail
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowsePreviewScreenModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

/**
 * KMK: Left panel on the browse source screen with the chapters of the selected cover.
 * Tap a chapter to preview its pages in the right panel; tap it again to start reading it.
 * Each chapter has read, bookmark and download buttons; the header has download and filter menus.
 */
@Composable
fun BrowseChapterPanel(
    state: BrowsePreviewScreenModel.State,
    onClose: () -> Unit,
    onSelectChapter: (Chapter) -> Unit,
    onOpenChapter: (Chapter) -> Unit,
    onToggleRead: (Chapter) -> Unit,
    onToggleBookmark: (Chapter) -> Unit,
    onChapterDownloadAction: (Chapter, ChapterDownloadAction) -> Unit,
    onDownloadAction: (DownloadAction?) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
    /** Manga details shown above the chapter list, like the left side of the details page */
    infoHeader: @Composable (Manga) -> Unit = {},
) {
    SlidingSidePanel(
        visible = state.chapterPanelOpen,
        fromStart = true,
        title = stringResource(MR.strings.chapters),
        onClose = onClose,
        modifier = modifier,
        applyTopInset = false,
        showClose = false,
        squareTop = true,
        showHeader = false,
    ) {
        key(state.manga?.id) {
            val listState = rememberLazyListState()
            val showNumber = state.manga?.displayMode == Manga.CHAPTER_DISPLAY_NUMBER
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.manga?.let { manga ->
                    item(key = "info") { infoHeader(manga) }
                    item(key = "chapters-header") {
                        HorizontalDivider()
                        // Chapter count with the download and filter buttons, right above the list
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp),
                        ) {
                            Text(
                                text = pluralStringResource(
                                    MR.plurals.manga_num_chapters,
                                    state.chapters.size,
                                    state.chapters.size,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            var showDownloadMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showDownloadMenu = true }, enabled = state.manga != null) {
                                    Icon(
                                        imageVector = Icons.Outlined.Download,
                                        contentDescription = stringResource(MR.strings.manga_download),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                DropdownMenu(
                                    expanded = showDownloadMenu,
                                    onDismissRequest = { showDownloadMenu = false },
                                ) {
                                    listOf(
                                        DownloadAction.UNREAD_CHAPTERS to stringResource(MR.strings.download_unread),
                                        null to stringResource(KMR.strings.browse_download_all_chapters),
                                        DownloadAction.BOOKMARKED_CHAPTERS to stringResource(MR.strings.download_bookmarked),
                                    ).forEach { (action, label) ->
                                        DropdownMenuItem(
                                            text = { Text(text = label) },
                                            onClick = {
                                                showDownloadMenu = false
                                                onDownloadAction(action)
                                            },
                                        )
                                    }
                                }
                            }
                            val filterActive = state.manga?.chaptersFiltered() == true || state.scanlatorFilterActive
                            IconButton(onClick = onOpenFilters, enabled = state.manga != null) {
                                Icon(
                                    imageVector = Icons.Outlined.FilterList,
                                    contentDescription = stringResource(MR.strings.action_filter),
                                    tint = if (filterActive) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            }
                        }
                    }
                }
                when {
                    state.chapters.isEmpty() && state.chaptersLoading -> item(key = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    state.chapters.isEmpty() -> item(key = "empty") {
                        Text(
                            text = state.chaptersError ?: stringResource(MR.strings.no_chapters_error),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                    else -> items(items = state.chapters, key = { it.chapter.id }) { item ->
                        val chapter = item.chapter
                        BrowseChapterRow(
                            item = item,
                            title = if (showNumber) {
                                stringResource(
                                    MR.strings.display_mode_chapter,
                                    formatChapterNumber(chapter.chapterNumber),
                                )
                            } else {
                                chapter.name
                            },
                            selected = chapter.id == state.selectedChapterId,
                            onClick = {
                                if (chapter.id == state.selectedChapterId) {
                                    onOpenChapter(chapter)
                                } else {
                                    onSelectChapter(chapter)
                                }
                            },
                            onToggleRead = { onToggleRead(chapter) },
                            onToggleBookmark = { onToggleBookmark(chapter) },
                            onDownloadAction = { onChapterDownloadAction(chapter, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseChapterRow(
    item: BrowsePreviewScreenModel.ChapterItem,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDownloadAction: (ChapterDownloadAction) -> Unit,
) {
    val chapter = item.chapter
    val subtitle = remember(chapter.dateUpload, chapter.scanlator) {
        listOfNotNull(
            chapter.dateUpload.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) },
            chapter.scanlator?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
    }
    // Live progress while this chapter is downloading
    val downloadManager = remember { Injekt.get<DownloadManager>() }
    val progress = if (item.downloadState == Download.State.DOWNLOADING) {
        remember(chapter.id) {
            downloadManager.progressFlow()
                .filter { it.chapter.id == chapter.id }
                .map { it.progress }
        }.collectAsState(0).value
    } else {
        0
    }
    // Styled like the chapter list on the details page
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectedBackground(selected)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!chapter.read) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 6.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = LocalContentColor.current.copy(alpha = if (chapter.read) DISABLED_ALPHA else 1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(
                        alpha = if (chapter.read) DISABLED_ALPHA else SECONDARY_ALPHA,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Read / unread
        IconButton(onClick = onToggleRead, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (chapter.read) Icons.Filled.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = stringResource(
                    if (chapter.read) MR.strings.action_mark_as_unread else MR.strings.action_mark_as_read,
                ),
                tint = if (chapter.read) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
        // Bookmark
        IconButton(onClick = onToggleBookmark, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (chapter.bookmark) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = stringResource(
                    if (chapter.bookmark) MR.strings.action_remove_bookmark else MR.strings.action_bookmark,
                ),
                tint = if (chapter.bookmark) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
        // Download
        ChapterDownloadIndicator(
            enabled = true,
            downloadStateProvider = { item.downloadState },
            downloadProgressProvider = { progress },
            onClick = onDownloadAction,
            modifier = Modifier.size(36.dp),
        )
    }
}

/**
 * KMK: Right panel on the browse source screen with every page of the chapter selected in the left panel.
 * Tap a page to start reading from it.
 */
@Composable
fun BrowsePagePanel(
    state: BrowsePreviewScreenModel.State,
    source: HttpSource?,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onOpenPage: (Chapter, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SlidingSidePanel(
        visible = state.pagePanelOpen,
        fromStart = false,
        title = stringResource(KMR.strings.reader_pages),
        onClose = onClose,
        modifier = modifier,
        applyTopInset = false,
        showClose = false,
        squareTop = true,
        showHeader = false,
    ) {
        val chapter = state.selectedChapter
        if (chapter != null) {
            Text(
                text = chapter.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        val pages = state.pages
        val localPages = state.localPages
        when {
            // KMK: downloaded chapters show their pages from the files, even without the source
            chapter != null && localPages != null -> key(chapter.id, "local") {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items = localPages, key = { index, _ -> "local-page-$index" }) { index, page ->
                        PageThumbnailBox(
                            model = page,
                            number = index + 1,
                            onClick = { onOpenPage(chapter, index) },
                        )
                    }
                }
            }
            source == null -> CenteredBox {
                Text(
                    text = stringResource(KMR.strings.browse_page_previews_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
            state.pagesError != null -> CenteredBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.pagesError ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(MR.strings.action_retry)) }
                }
            }
            chapter == null || pages == null -> CenteredBox {
                if (state.pagesLoading || state.chaptersLoading) CircularProgressIndicator()
            }
            else -> key(chapter.id) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items = pages, key = { index, _ -> "page-$index" }) { index, page ->
                        SourcePageThumbnailItem(
                            source = source,
                            chapterUrl = chapter.url,
                            page = page,
                            onClick = { onOpenPage(chapter, index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePageThumbnailItem(
    source: HttpSource,
    chapterUrl: String,
    page: Page,
    onClick: () -> Unit,
) {
    PageThumbnailBox(
        model = remember(source.id, chapterUrl, page.index) { SourcePageThumbnail(source, chapterUrl, page) },
        number = page.number,
        onClick = onClick,
    )
}

/** One page tile: the image (from the source or a download) with its number in the corner */
@Composable
private fun PageThumbnailBox(
    model: Any,
    number: Int,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = remember(model) {
                ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(true)
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .sizeIn(minWidth = 26.dp, minHeight = 26.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}
