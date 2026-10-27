package eu.kanade.presentation.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.jssource.ReaderVideos
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue

/**
 * KMK: Reader panels that slide in from the side of the screen, like Usagi.
 *  - [ReaderChapterListPanel]: the list of chapters, on the left.
 *  - [ReaderPageGridPanel]: thumbnails of every page in the current chapter, on the right.
 *
 * They are not dialogs: the reader stays usable while they are shown, both can be open at the same
 * time, and they only close with their X button.
 */
@Composable
fun ReaderChapterListPanel(
    visible: Boolean,
    onClose: () -> Unit,
    screenModel: ReaderSettingsScreenModel,
    chapters: ImmutableList<ReaderChapterItem>,
    onClickChapter: (Chapter) -> Unit,
    onBookmark: (Chapter) -> Unit,
    dateRelativeTime: Boolean,
    onDownloadAction: ((Chapter, ChapterDownloadAction) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SlidingSidePanel(
        visible = visible,
        fromStart = true,
        title = stringResource(MR.strings.chapters),
        onClose = onClose,
        modifier = modifier,
    ) {
        // Re-create the list when the current chapter changes so it scrolls to it
        key(chapters.firstOrNull { it.isCurrent }?.chapter?.id) {
            ChapterListContent(
                screenModel = screenModel,
                chapters = chapters,
                onClickChapter = onClickChapter,
                onBookmark = onBookmark,
                dateRelativeTime = dateRelativeTime,
                onDownloadAction = onDownloadAction,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun ReaderPageGridPanel(
    visible: Boolean,
    onClose: () -> Unit,
    currentChapter: ReaderChapter?,
    currentPage: Int,
    onClickPage: (pageIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SlidingSidePanel(
        visible = visible,
        fromStart = false,
        title = stringResource(KMR.strings.reader_pages),
        onClose = onClose,
        modifier = modifier,
    ) {
        // Start fresh (scrolled to the current page) whenever another chapter is opened
        key(currentChapter?.chapter?.id) {
            PageGridContent(
                chapter = currentChapter,
                currentPage = currentPage,
                onClickPage = onClickPage,
            )
        }
    }
}

/**
 * Width of a [SlidingSidePanel] inside a container of [containerWidth].
 */
fun sidePanelWidth(containerWidth: Dp): Dp {
    return if (containerWidth < 600.dp) {
        // On phones leave room so both panels can be open at once
        containerWidth * 0.48f
    } else {
        // Narrow drawer like Usagi: about a quarter of a tablet screen
        (containerWidth * 0.27f).coerceIn(300.dp, 380.dp)
    }
}

/**
 * A panel docked to the left ([fromStart]) or right edge that slides in and out.
 * Fills the height of its container. Set [applyTopInset] to false when the container already
 * starts below the status bar (e.g. below a toolbar).
 */
@Composable
fun SlidingSidePanel(
    visible: Boolean,
    fromStart: Boolean,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    applyTopInset: Boolean = true,
    /** Show the X close button in the header */
    showClose: Boolean = true,
    /** Square corner on the top edge next to the screen's middle (when docked below a toolbar row) */
    squareTop: Boolean = false,
    /** Extra buttons shown in the header, left of the X */
    actions: @Composable RowScope.() -> Unit = {},
    /** Show the title bar at the top of the panel */
    showHeader: Boolean = true,
    content: @Composable () -> Unit,
) {
    // +1 slides towards the right edge, -1 towards the left edge
    val direction = if (fromStart) -1 else 1

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panelWidth = sidePanelWidth(maxWidth)

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally { it * direction },
            exit = slideOutHorizontally { it * direction },
            modifier = Modifier.align(if (fromStart) Alignment.CenterStart else Alignment.CenterEnd),
        ) {
            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    // Keep touches on the panel from reaching the reader underneath
                    .pointerInput(Unit) {},
                shape = if (fromStart) {
                    RoundedCornerShape(topEnd = if (squareTop) 0.dp else 28.dp, bottomEnd = 28.dp)
                } else {
                    RoundedCornerShape(topStart = if (squareTop) 0.dp else 28.dp, bottomStart = 28.dp)
                },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.statusBars
                                .union(WindowInsets.navigationBars)
                                .union(WindowInsets.displayCutout)
                                .only(
                                    (if (applyTopInset) WindowInsetsSides.Top else WindowInsetsSides.Bottom) +
                                        WindowInsetsSides.Bottom +
                                        if (fromStart) WindowInsetsSides.Start else WindowInsetsSides.End,
                                ),
                        ),
                ) {
                    if (showHeader) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        actions()
                        if (showClose) {
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(MR.strings.action_close),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    }

                    content()
                }
            }
        }
    }
}

@Composable
private fun PageGridContent(
    chapter: ReaderChapter?,
    currentPage: Int,
    onClickPage: (Int) -> Unit,
) {
    val chapterState = chapter?.stateFlow?.collectAsState()?.value
    val pages = (chapterState as? ReaderChapter.State.Loaded)?.pages

    Column(modifier = Modifier.fillMaxSize()) {
        if (chapter != null) {
            Text(
                text = chapter.chapter.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            )
        }

        if (pages.isNullOrEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (chapterState is ReaderChapter.State.Loading || chapterState is ReaderChapter.State.Wait) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = stringResource(KMR.strings.reader_chapter_not_loaded),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            return@Column
        }

        val gridState = rememberLazyGridState(
            initialFirstVisibleItemIndex = (currentPage - 1).coerceIn(0, pages.lastIndex),
        )
        // Keep the current page in view as you read
        LaunchedEffect(currentPage) {
            val index = (currentPage - 1).coerceIn(0, pages.lastIndex)
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            val shown = visibleItems.isNotEmpty() &&
                visibleItems.any { it.index == index } &&
                visibleItems.first().index != index &&
                visibleItems.last().index != index
            if (!shown) gridState.animateScrollToItem(index)
        }
        // KMK: the first tap on an animated page plays it and moves the frame to it, the second opens it; reading
        // on to another page hands the frame back to the page being read
        var active by remember { mutableStateOf<Int?>(null) }
        val animated = remember { mutableStateMapOf<Int, Boolean>() }
        LaunchedEffect(currentPage) {
            if (active != null && active != currentPage - 1) active = null
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(
                items = pages,
                key = { _, page -> "page-${page.index}" },
            ) { index, page ->
                PageThumbnail(
                    page = page,
                    chapter = chapter,
                    isCurrent = if (active != null) index == active else page.number == currentPage,
                    playing = index == active,
                    onAnimated = { animated[index] = it },
                    onClick = {
                        if (animated[index] == true && active != index) {
                            active = index
                        } else {
                            active = index.takeIf { animated[index] == true }
                            onClickPage(page.index)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PageThumbnail(
    page: ReaderPage,
    chapter: ReaderChapter,
    isCurrent: Boolean,
    playing: Boolean,
    onAnimated: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val status by page.statusFlow.collectAsState()
    val progress by page.progressFlow.collectAsState()

    // Ask the chapter's loader for this page while the thumbnail is on screen.
    // For online chapters this queues the download; leaving the screen cancels it.
    LaunchedEffect(page) {
        val loader = chapter.pageLoader ?: return@LaunchedEffect
        if (!loader.isRecycled) loader.loadPage(page)
    }

    // KMK: how long a page took to arrive, written to Logs when slow (with the tile timings of the image loader)
    var waitingSince by remember(page) { mutableStateOf(0L) }
    LaunchedEffect(page, status) {
        val now = android.os.SystemClock.uptimeMillis()
        when (status) {
            Page.State.Ready -> if (waitingSince > 0) {
                val took = now - waitingSince
                waitingSince = 0L
                if (took >= 1500) {
                    logcat.logcat("pages", logcat.LogPriority.INFO) {
                        "Page ${page.number} downloaded for the Pages panel in ${took / 1000.0} s"
                    }
                }
            }
            is Page.State.Error -> waitingSince = 0L
            else -> if (waitingSince == 0L) waitingSince = now
        }
    }

    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (isCurrent) {
                    Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = status) {
            Page.State.Ready -> {
                // KMK: animated pages hold still with a small play button until tapped
                eu.kanade.presentation.components.AnimatedThumbnail(
                    model = page,
                    playing = playing,
                    onAnimated = onAnimated,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            is Page.State.Error -> {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = s.error.message,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { chapter.pageLoader?.retryPage(page) },
                )
            }
            else -> {
                if (s == Page.State.DownloadImage && progress > 0) {
                    CircularProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }
        }

        // KMK: a video page gets the reader's round play button; tapping it plays the video
        if (ReaderVideos.isVideo(page.url)) {
            VideoPlayButton(page)
        }

        // Page number badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .sizeIn(minWidth = 26.dp, minHeight = 26.dp)
                .clip(CircleShape)
                .background(
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Black.copy(alpha = 0.7f)
                    },
                )
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = page.number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else Color.White,
            )
        }
    }
}

/** KMK: the reader's play button (dark circle, white ring, white arrow), sized for a page thumbnail */
@Composable
private fun VideoPlayButton(page: ReaderPage) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 170f / 255f))
            .border(BorderStroke(1.5.dp, Color.White.copy(alpha = 200f / 255f)), CircleShape)
            .clickable {
                scope.launch {
                    try {
                        ReaderVideos.play(context, page)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        context.toast("Couldn't play this video: ${e.message}")
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Play video",
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}
