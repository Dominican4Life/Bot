package eu.kanade.presentation.manga.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.components.TitleSheet
import eu.kanade.presentation.components.TitleSheetSizeMenu
import eu.kanade.presentation.components.pinchColumns
import eu.kanade.tachiyomi.data.coil.SourcePageThumbnail
import eu.kanade.tachiyomi.jssource.ReaderVideos
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowsePreviewScreenModel
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.ceil

/** A page taller than this (height / width) is a long strip */
private const val STRIP_RATIO = 2.2f

/** A long-strip slice: about one phone screen (height / width) */
private const val SLICE_RATIO = 16f / 9f

private data class PreviewTile(val page: Int, val slice: Int, val slices: Int)

/**
 * KMK (r124): ▦ Preview on the phone title page: the pages of one chapter (picked with the chip) in a grid. Tap a
 * page to read from it. A long-strip chapter is cut into screen-high slices so its panels can be seen; the columns,
 * tile shape and sheet stop are set with ⤢ (or pinch the grid), separately for long strips.
 */
@Composable
fun TitlePagePreview(
    state: BrowsePreviewScreenModel.State,
    source: HttpSource?,
    onSelectChapter: (Chapter) -> Unit,
    onOpenPage: (Chapter, Int, Boolean) -> Unit,
    onRetry: () -> Unit,
    bottomSpace: Dp,
) {
    val chapter = state.selectedChapter
    // Height / width of each page, learned as the images load
    val ratios = remember(chapter?.id) { mutableStateMapOf<Int, Float>() }
    val detectedStrip = ratios.values.any { it > STRIP_RATIO }
    val kind = if (detectedStrip) "strip" else "manga"
    val columnsPref = remember(kind) { TitleSheet.columns(kind) }
    val columns by columnsPref.collectAsState()
    val shapePref = remember(kind) { TitleSheet.shape(kind) }
    val shape by shapePref.collectAsState()
    val sliced = shape == TitleSheet.SHAPE_STRIP || (shape == TitleSheet.SHAPE_AUTO && detectedStrip)
    var pickChapter by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AssistChip(
                onClick = { pickChapter = true },
                label = {
                    Text(
                        text = chapter?.name ?: stringResource(KMR.strings.title_sheet_pick_chapter),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            )
            if (detectedStrip) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = stringResource(KMR.strings.title_sheet_long_strip),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { sizeMenu = true }) {
                    Icon(Icons.Outlined.AspectRatio, contentDescription = stringResource(KMR.strings.title_sheet_size))
                }
                TitleSheetSizeMenu(
                    expanded = sizeMenu,
                    onDismissRequest = { sizeMenu = false },
                    kind = kind,
                    showShape = true,
                )
            }
        }

        val pages = state.pages
        val localPages = state.localPages
        when {
            chapter != null && localPages == null && source == null -> Centered {
                Text(
                    text = stringResource(KMR.strings.browse_page_previews_unavailable),
                    modifier = Modifier.padding(24.dp),
                )
            }
            state.pagesError != null -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = state.pagesError ?: "", modifier = Modifier.padding(horizontal = 24.dp))
                    TextButton(onClick = onRetry) { Text(stringResource(MR.strings.action_retry)) }
                }
            }
            chapter == null || (pages == null && localPages == null) -> Centered {
                if (state.pagesLoading || state.chaptersLoading) {
                    CircularProgressIndicator()
                } else {
                    Text(text = stringResource(KMR.strings.title_sheet_no_pages))
                }
            }
            else -> key(chapter.id) {
                // KMK (r129): animated pages as in the old preview panel: still with ▶, first tap plays it (framed),
                // second tap opens it; one plays at a time
                var playingPage by remember { mutableStateOf<Int?>(null) }
                val animatedPages = remember { mutableStateMapOf<Int, Boolean>() }
                val count = localPages?.size ?: pages?.size ?: 0
                val tiles = remember(count, sliced, ratios.toMap()) {
                    (0 until count).flatMap { page ->
                        val n = if (sliced) {
                            ratios[page]?.let { ceil(it / SLICE_RATIO).toInt() }?.coerceIn(1, 40) ?: 1
                        } else {
                            1
                        }
                        (0 until n).map { PreviewTile(page, it, n) }
                    }
                }
                val tileRatio = when {
                    sliced -> 1f / SLICE_RATIO
                    shape == TitleSheet.SHAPE_SQUARE -> 1f
                    shape == TitleSheet.SHAPE_PAGE -> 2f / 3f
                    else -> 0.7f
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns.coerceIn(1, 6)),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottomSpace),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .pinchColumns(columns) { columnsPref.set(it) },
                ) {
                    items(tiles, key = { "${it.page}-${it.slice}" }) { tile ->
                        val local = localPages?.getOrNull(tile.page)
                        val page = pages?.getOrNull(tile.page)
                        val model: Any? = when {
                            local != null -> local
                            state.previewModels != null -> state.previewModels?.getOrNull(tile.page)
                            source != null && page != null -> SourcePageThumbnail(source, chapter.url, page)
                            else -> null
                        }
                        val isVideo = local?.isVideo == true || (page?.url?.let { ReaderVideos.isVideo(it) } == true)
                        PreviewTileBox(
                            model = model,
                            tile = tile,
                            ratio = tileRatio,
                            isVideo = isVideo,
                            onRatio = { if (tile.slice == 0) ratios[tile.page] = it },
                            onClick = {
                                if (!isVideo && animatedPages[tile.page] == true && playingPage != tile.page) {
                                    playingPage = tile.page
                                } else {
                                    onOpenPage(chapter, tile.page, isVideo)
                                }
                            },
                            // SY's preview images (previewModels) stay plain pictures, as in the old panel
                            animatedTile = state.previewModels == null && !isVideo,
                            playing = playingPage == tile.page,
                            onAnimated = { animatedPages[tile.page] = it },
                        )
                    }
                }
            }
        }
    }

    if (pickChapter) {
        ChapterPickerDialog(
            chapters = state.chapters.map { it.chapter },
            selectedId = chapter?.id,
            onDismissRequest = { pickChapter = false },
            onPick = {
                pickChapter = false
                onSelectChapter(it)
            },
        )
    }
}

@Composable
private fun PreviewTileBox(
    model: Any?,
    tile: PreviewTile,
    ratio: Float,
    isVideo: Boolean,
    onRatio: (Float) -> Unit,
    onClick: () -> Unit,
    animatedTile: Boolean = false,
    playing: Boolean = false,
    onAnimated: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(8.dp)
    val request = remember(model) { model?.let { ImageRequest.Builder(context).data(it).crossfade(true).build() } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (playing) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape) else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null && animatedTile) {
            val bias = if (tile.slices <= 1) -1f else -1f + 2f * tile.slice / (tile.slices - 1)
            eu.kanade.presentation.components.AnimatedThumbnail(
                model = model,
                playing = playing,
                onAnimated = onAnimated,
                alignment = BiasAlignment(0f, bias),
                onRatio = onRatio,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (request != null) {
            // Slice i of n shows the i-th part of the page, top to bottom
            val bias = if (tile.slices <= 1) -1f else -1f + 2f * tile.slice / (tile.slices - 1)
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, bias),
                onSuccess = { success ->
                    val size = success.painter.intrinsicSize
                    if (size.width > 0f && size.height > 0f) onRatio(size.height / size.width)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.66f))
                    .border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.8f)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .sizeIn(minWidth = 22.dp, minHeight = 20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (tile.slices > 1) "${tile.page + 1}·${tile.slice + 1}" else "${tile.page + 1}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun ChapterPickerDialog(
    chapters: List<Chapter>,
    selectedId: Long?,
    onDismissRequest: () -> Unit,
    onPick: (Chapter) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(KMR.strings.title_sheet_pick_chapter)) },
        text = {
            val start = chapters.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 460.dp)) {
                items(chapters, key = { it.id }) { chapter ->
                    Text(
                        text = chapter.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (chapter.id == selectedId) FontWeight.Bold else FontWeight.Normal,
                        color = if (chapter.id == selectedId) {
                            MaterialTheme.colorScheme.primary
                        } else if (chapter.read) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(chapter) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.Center) {
        content()
    }
}
