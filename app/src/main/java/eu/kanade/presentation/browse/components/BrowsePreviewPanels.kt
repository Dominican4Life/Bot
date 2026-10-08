package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import eu.kanade.presentation.reader.SlidingSidePanel
import eu.kanade.tachiyomi.data.coil.SourcePageThumbnail
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowsePreviewScreenModel
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DateFormat
import java.util.Date

/**
 * KMK: Left panel on the browse source screen with the chapters of the selected cover.
 * Tap a chapter to preview its pages in the right panel; tap it again to start reading it.
 */
@Composable
fun BrowseChapterPanel(
    state: BrowsePreviewScreenModel.State,
    onClose: () -> Unit,
    onSelectChapter: (Chapter) -> Unit,
    onOpenChapter: (Chapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    SlidingSidePanel(
        visible = state.chapterPanelOpen,
        fromStart = true,
        title = stringResource(MR.strings.chapters),
        onClose = onClose,
        modifier = modifier,
        applyTopInset = false,
    ) {
        state.manga?.let {
            Text(
                text = it.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        when {
            state.chapters.isEmpty() && state.chaptersLoading -> CenteredBox { CircularProgressIndicator() }
            state.chapters.isEmpty() -> CenteredBox {
                Text(
                    text = state.chaptersError ?: stringResource(MR.strings.no_chapters_error),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> key(state.manga?.id) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = state.chapters, key = { it.id }) { chapter ->
                        BrowseChapterRow(
                            chapter = chapter,
                            selected = chapter.id == state.selectedChapterId,
                            onClick = {
                                if (chapter.id == state.selectedChapterId) {
                                    onOpenChapter(chapter)
                                } else {
                                    onSelectChapter(chapter)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseChapterRow(
    chapter: Chapter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val subtitle = remember(chapter.dateUpload, chapter.scanlator) {
        listOfNotNull(
            chapter.dateUpload.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) },
            chapter.scanlator?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (chapter.read && !selected) 0.6f else 1f),
    ) {
        Text(
            text = chapter.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
        when {
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
            model = remember(source.id, chapterUrl, page.index) {
                ImageRequest.Builder(context)
                    .data(SourcePageThumbnail(source, chapterUrl, page))
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
                text = page.number.toString(),
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
