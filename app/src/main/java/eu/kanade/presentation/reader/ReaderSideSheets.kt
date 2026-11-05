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
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.SwapHoriz
import tachiyomi.presentation.core.util.collectAsState as collectPrefState
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.GridView
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
import eu.kanade.presentation.browse.components.panelGlassEdge
import eu.kanade.presentation.browse.components.liquidGlass
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
    // KMK (r125): eye / bookmark buttons and swipes on the rows; the header's Pages button
    onToggleRead: ((Chapter) -> Unit)? = null,
    onSwitchToPages: (() -> Unit)? = null,
) {
    SlidingSidePanel(
        visible = visible,
        fromStart = true,
        title = stringResource(MR.strings.chapters),
        onClose = onClose,
        modifier = modifier,
        actions = {
            if (onSwitchToPages != null) {
                IconButton(onClick = onSwitchToPages) {
                    Icon(
                        imageVector = Icons.Outlined.GridView,
                        contentDescription = stringResource(KMR.strings.reader_pages),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
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
                onToggleRead = onToggleRead,
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
    // KMK (r125): the header's Chapters button (glass reader)
    onSwitchToChapters: (() -> Unit)? = null,
) {
    SlidingSidePanel(
        visible = visible,
        fromStart = false,
        title = stringResource(KMR.strings.reader_pages),
        onClose = onClose,
        modifier = modifier,
        actions = {
            if (onSwitchToChapters != null) {
                IconButton(onClick = onSwitchToChapters) {
                    Icon(
                        imageVector = Icons.Outlined.FormatListNumbered,
                        contentDescription = stringResource(MR.strings.chapters),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
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
 * KMK (r127): the glass reader's Chapters / Pages sheet, like the phone title page's sheet: a glass card from the
 * bottom (the page stays visible, blurred, behind it), a handle to drag it between half and full height (down to
 * close), the title row, Chapters | Pages, and the same lists as the side panels (eye / bookmark / download and the
 * swipes on each chapter). Tapping the page above it closes it.
 */
@Composable
fun ReaderGlassSheet(
    visible: Boolean,
    showPages: Boolean,
    onShowPages: (Boolean) -> Unit,
    onClose: () -> Unit,
    title: String?,
    subtitle: String?,
    cover: Any?,
    chapters: @Composable () -> Unit,
    pages: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fullPx = constraints.maxHeight.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current
        var full by remember { mutableStateOf(false) }
        val drag = remember { androidx.compose.animation.core.Animatable(0f) }
        val scope = rememberCoroutineScope()
        val target = if (full) 0.9f else 0.56f
        val fraction by androidx.compose.animation.core.animateFloatAsState(target, label = "sheetHeight")
        if (visible) {
            // The page above the sheet: a tap closes it
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGesturesCompat(onClose) },
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val shape = RoundedCornerShape(eu.kanade.presentation.browse.components.GlassStyle.cornerDp() + 6.dp)
            val heightPx = (fullPx * fraction - drag.value).coerceIn(fullPx * 0.2f, fullPx * 0.94f)
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(
                    onSurface = eu.kanade.presentation.reader.appbars.ReaderGlass.content,
                    onSurfaceVariant = eu.kanade.presentation.reader.appbars.ReaderGlass.content.copy(alpha = 0.72f),
                    surface = Color.Transparent,
                    surfaceContainerLow = Color.Transparent,
                    primary = eu.kanade.presentation.reader.appbars.ReaderGlass.accent,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
                        .padding(8.dp)
                        .sizeIn(maxWidth = 640.dp)
                        .fillMaxWidth()
                        .height(with(density) { heightPx.toDp() })
                        .shadow(18.dp, shape)
                        .liquidGlass(shape, Color(0xFF14161E))
                        .pointerInput(Unit) {},
                ) {
                    // Handle: drag up for full height, down for half, further down to close
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .pointerInput(full) {
                                androidx.compose.foundation.gestures.detectVerticalDragGestures(
                                    onDragEnd = {
                                        val now = fullPx * fraction - drag.value
                                        scope.launch {
                                            when {
                                                now < fullPx * 0.32f -> onClose()
                                                now > fullPx * 0.72f -> full = true
                                                else -> full = false
                                            }
                                            drag.snapTo(0f)
                                        }
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    scope.launch { drag.snapTo(drag.value + amount) }
                                }
                            }
                            .clickable { full = !full },
                    ) {
                        Box(
                            Modifier
                                .size(width = 40.dp, height = 5.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.6f)),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, bottom = 6.dp),
                    ) {
                        if (cover != null) {
                            AsyncImage(
                                model = cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 34.dp, height = 48.dp)
                                    .clip(RoundedCornerShape(7.dp)),
                            )
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                text = title.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = eu.kanade.presentation.reader.appbars.ReaderGlass.content,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = eu.kanade.presentation.reader.appbars.ReaderGlass.content.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(MR.strings.action_close),
                                tint = eu.kanade.presentation.reader.appbars.ReaderGlass.content,
                            )
                        }
                    }
                    // Chapters | Pages
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 6.dp)
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.22f))
                            .padding(3.dp),
                    ) {
                        listOf(
                            false to (Icons.Outlined.FormatListNumbered to stringResource(MR.strings.chapters)),
                            true to (Icons.Outlined.GridView to stringResource(KMR.strings.reader_pages)),
                        ).forEach { (pagesTab, iconLabel) ->
                            val on = pagesTab == showPages
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                                    .clickable { onShowPages(pagesTab) }
                                    .padding(vertical = 7.dp),
                            ) {
                                Icon(
                                    iconLabel.first,
                                    contentDescription = null,
                                    tint = eu.kanade.presentation.reader.appbars.ReaderGlass.content,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = iconLabel.second,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = eu.kanade.presentation.reader.appbars.ReaderGlass.content,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        if (showPages) pages() else chapters()
                    }
                }
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(onTap: () -> Unit) {
    androidx.compose.foundation.gestures.detectTapGestures(onTap = { onTap() })
}

/**
 * Width of a [SlidingSidePanel] inside a container of [containerWidth].
 */
fun sidePanelWidth(containerWidth: Dp): Dp {
    return if (containerWidth < 600.dp) {
        // KMK (r125): on phones one panel at a time (the header switches Chapters / Pages), wide enough for the
        // eye / bookmark / download buttons on each chapter
        containerWidth * 0.88f
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
    /** KMK (r125): a floating glass card (rounded on every side, a margin, see-through) instead of a docked sheet */
    glass: Boolean = false,
    /**
     * KMK (r127): the glass preview panel can be dragged to the other side (or swapped with ⇄), and tucked into the
     * screen edge as a slim tab so the covers behind it can be tapped; [tuckKey] changing (another title) opens it
     * again, [tuckCover] is drawn on the tab.
     */
    movable: Boolean = false,
    tuckKey: Any? = null,
    tuckCover: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val canMove = glass && movable
    val sidePref = remember { eu.kanade.presentation.browse.components.PreviewGlass.leftSide() }
    val leftSide by sidePref.collectPrefState()
    val start = if (canMove) leftSide else fromStart
    var tucked by remember(tuckKey) { mutableStateOf(false) }
    val dragX = remember { androidx.compose.animation.core.Animatable(0f) }
    val moveScope = rememberCoroutineScope()
    // +1 slides towards the right edge, -1 towards the left edge
    val direction = if (start) -1 else 1

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panelWidth = sidePanelWidth(maxWidth)
        val containerPx = constraints.maxWidth.toFloat()
        val panelPx = with(androidx.compose.ui.platform.LocalDensity.current) { (panelWidth + 16.dp).toPx() }

        // The grip row of a movable panel: ⇄ swaps sides, the grip drags (to the other side, or into the edge to
        // tuck), the chevron tucks
        val gripRow: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .pointerInput(start, containerPx, panelPx) {
                        androidx.compose.foundation.gestures.detectHorizontalDragGestures(
                            onDragEnd = {
                                val offset = dragX.value
                                val outward = if (start) -offset else offset
                                val crossed = if (start) offset > (containerPx - panelPx) / 2 else -offset > (containerPx - panelPx) / 2
                                moveScope.launch {
                                    when {
                                        outward > panelPx * 0.3f -> {
                                            tucked = true
                                            dragX.snapTo(0f)
                                        }
                                        crossed -> {
                                            // Keep it where it is on screen, then let it settle on the new side
                                            val travel = containerPx - panelPx
                                            sidePref.set(!start)
                                            dragX.snapTo(if (start) offset - travel else offset + travel)
                                            dragX.animateTo(0f)
                                        }
                                        else -> dragX.animateTo(0f)
                                    }
                                }
                            },
                            onDragCancel = { moveScope.launch { dragX.animateTo(0f) } },
                        ) { change, amount ->
                            change.consume()
                            moveScope.launch { dragX.snapTo(dragX.value + amount) }
                        }
                    },
            ) {
                IconButton(onClick = { sidePref.set(!start) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 5.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.55f)),
                    )
                }
                IconButton(onClick = { tucked = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = if (start) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Tucked: a slim glass tab on the panel's side; tap it (or drag it out) to bring the panel back as it was
        if (canMove && visible && tucked) {
            val tabShape = if (start) {
                RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
            } else {
                RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(if (start) Alignment.CenterStart else Alignment.CenterEnd)
                    .width(46.dp)
                    .clip(tabShape)
                    .liquidGlass(tabShape)
                    .clickable { tucked = false }
                    .pointerInput(start) {
                        androidx.compose.foundation.gestures.detectHorizontalDragGestures { change, amount ->
                            change.consume()
                            if ((start && amount > 6f) || (!start && amount < -6f)) tucked = false
                        }
                    }
                    .padding(vertical = 10.dp, horizontal = 5.dp),
            ) {
                if (tuckCover != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.7f)
                            .clip(RoundedCornerShape(6.dp)),
                    ) { tuckCover() }
                }
                Icon(
                    imageVector = if (start) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }

        AnimatedVisibility(
            visible = visible && !(canMove && tucked),
            enter = slideInHorizontally { it * direction },
            exit = slideOutHorizontally { it * direction },
            modifier = Modifier
                .align(if (start) Alignment.CenterStart else Alignment.CenterEnd)
                .graphicsLayer { translationX = dragX.value },
        ) {
            val glassShape = RoundedCornerShape(eu.kanade.presentation.browse.components.GlassStyle.cornerDp())
            Surface(
                modifier = Modifier
                    .then(if (glass) Modifier.padding(8.dp) else Modifier)
                    .width(panelWidth)
                    .fillMaxHeight()
                    // Keep touches on the panel from reaching the reader underneath
                    .pointerInput(Unit) {}
                    .then(
                        if (glass) {
                            Modifier
                                .shadow(14.dp, glassShape)
                                .liquidGlass(glassShape)
                        } else {
                            Modifier
                        },
                    ),
                shape = when {
                    glass -> glassShape
                    start -> RoundedCornerShape(topEnd = if (squareTop) 0.dp else 28.dp, bottomEnd = 28.dp)
                    else -> RoundedCornerShape(topStart = if (squareTop) 0.dp else 28.dp, bottomStart = 28.dp)
                },
                color = if (glass) {
                    // KMK (r127): the glass draws itself (liquidGlass)
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                tonalElevation = if (glass) 0.dp else 1.dp,
                shadowElevation = if (glass) 0.dp else 8.dp,
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
                                        if (start) WindowInsetsSides.Start else WindowInsetsSides.End,
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

                    if (canMove) gripRow()
                    content()
                }
            }
        }
    }
}

@Composable
internal fun PageGridContent(
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
        val tileContext = LocalContext.current
        val tileScope = rememberCoroutineScope()
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
                        if (ReaderVideos.isVideo(page.url)) {
                            // KMK: a video tile plays straight away, the whole tile and not only its ▶
                            tileScope.launch {
                                try {
                                    ReaderVideos.play(tileContext, page)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    tileContext.toast("Couldn't play this video: ${e.message}")
                                }
                            }
                        } else if (animated[index] == true && active != index) {
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
    // KMK (r103): at the lowest priority and without preloading the pages after it (loadThumbnail), so the tiles
    // don't hold up the page being read
    LaunchedEffect(page) {
        val loader = chapter.pageLoader ?: return@LaunchedEffect
        if (!loader.isRecycled) loader.loadThumbnail(page)
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
