package eu.kanade.presentation.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/**
 * KMK: Reader chapter menu that slides in from the right edge of the screen.
 *
 * It has two views, switched with the icons at the top:
 *  - a list of all chapters (same as the old centered dialog)
 *  - a grid with a preview of every page in the chapter currently being read
 */
private enum class SideSheetTab { Chapters, Pages }

/** Remembers the last used view for as long as the app is alive. */
private var lastSideSheetTab = SideSheetTab.Chapters

@Composable
fun ReaderChapterSideSheet(
    onDismissRequest: () -> Unit,
    screenModel: ReaderSettingsScreenModel,
    chapters: ImmutableList<ReaderChapterItem>,
    onClickChapter: (Chapter) -> Unit,
    onBookmark: (Chapter) -> Unit,
    dateRelativeTime: Boolean,
    onDownloadAction: ((Chapter, ChapterDownloadAction) -> Unit)?,
    currentChapter: ReaderChapter?,
    currentPage: Int,
    onClickPage: (pageIndex: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val visibleState = remember { MutableTransitionState(false) }
    var dismissing by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(lastSideSheetTab) }

    LaunchedEffect(Unit) { visibleState.targetState = true }

    val animateDismiss: () -> Unit = {
        dismissing = true
        visibleState.targetState = false
    }

    // Only tell the caller after the slide-out animation has finished
    LaunchedEffect(dismissing, visibleState.isIdle, visibleState.currentState) {
        if (dismissing && visibleState.isIdle && !visibleState.currentState) {
            onDismissRequest()
        }
    }

    BackHandler(onBack = animateDismiss)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheetWidth = if (maxWidth < 600.dp) {
            maxWidth * 0.88f
        } else {
            min(420.dp, maxWidth * 0.4f)
        }
        val sheetWidthPx = with(LocalDensity.current) { sheetWidth.toPx() }
        var dragOffset by remember { mutableFloatStateOf(0f) }

        // Scrim
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = animateDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                modifier = Modifier
                    .offset { IntOffset(dragOffset.roundToInt(), 0) }
                    .width(sheetWidth)
                    .fillMaxHeight()
                    // Swipe the sheet to the right to close it
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            dragOffset = (dragOffset + delta).coerceIn(0f, sheetWidthPx)
                        },
                        onDragStopped = { velocity ->
                            if (dragOffset > sheetWidthPx * 0.3f || velocity > 1500f) {
                                animateDismiss()
                            } else {
                                scope.launch {
                                    animate(initialValue = dragOffset, targetValue = 0f) { value, _ ->
                                        dragOffset = value
                                    }
                                }
                            }
                        },
                    ),
                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
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
                                .only(WindowInsetsSides.Vertical + WindowInsetsSides.End),
                        ),
                ) {
                    SideSheetHeader(
                        tab = tab,
                        onTabChange = {
                            tab = it
                            lastSideSheetTab = it
                        },
                        onClose = animateDismiss,
                    )
                    HorizontalDivider()

                    when (tab) {
                        SideSheetTab.Chapters -> ChapterListContent(
                            screenModel = screenModel,
                            chapters = chapters,
                            onClickChapter = {
                                onClickChapter(it)
                                animateDismiss()
                            },
                            onBookmark = onBookmark,
                            dateRelativeTime = dateRelativeTime,
                            onDownloadAction = onDownloadAction,
                            modifier = Modifier.fillMaxSize(),
                        )
                        SideSheetTab.Pages -> PageGridContent(
                            chapter = currentChapter,
                            currentPage = currentPage,
                            onClickPage = {
                                onClickPage(it)
                                animateDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SideSheetHeader(
    tab: SideSheetTab,
    onTabChange: (SideSheetTab) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(MR.strings.chapters),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.action_close),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            FilledTonalIconToggleButton(
                checked = tab == SideSheetTab.Chapters,
                onCheckedChange = { onTabChange(SideSheetTab.Chapters) },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.FormatListBulleted,
                    contentDescription = stringResource(KMR.strings.reader_chapter_list_view),
                )
            }
            FilledTonalIconToggleButton(
                checked = tab == SideSheetTab.Pages,
                onCheckedChange = { onTabChange(SideSheetTab.Pages) },
            ) {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = stringResource(KMR.strings.reader_page_grid_view),
                )
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items = pages,
                key = { "page-${it.index}" },
            ) { page ->
                PageThumbnail(
                    page = page,
                    chapter = chapter,
                    isCurrent = page.number == currentPage,
                    onClick = { onClickPage(page.index) },
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
                AsyncImage(
                    model = remember(page) {
                        ImageRequest.Builder(context)
                            .data(page)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
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
