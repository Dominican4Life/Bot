package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import kotlinx.collections.immutable.ImmutableSet
import tachiyomi.presentation.core.components.material.padding

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)

// SY -->
enum class NavBarType {
    VerticalRight,
    VerticalLeft,
    Bottom,
}
// SY <--

@Composable
fun ReaderAppBars(
    visible: Boolean,

    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    viewer: Viewer?,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,

    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // SY -->
    isExhToolsVisible: Boolean,
    onSetExhUtilsVisibility: (Boolean) -> Unit,
    isAutoScroll: Boolean,
    isAutoScrollEnabled: Boolean,
    onToggleAutoscroll: (Boolean) -> Unit,
    autoScrollFrequency: String,
    onSetAutoScrollFrequency: (String) -> Unit,
    onClickAutoScrollHelp: () -> Unit,
    onClickRetryAll: () -> Unit,
    onClickRetryAllHelp: () -> Unit,
    onClickBoostPage: () -> Unit,
    onClickBoostPageHelp: () -> Unit,
    navBarType: NavBarType,
    currentPageText: String,
    enabledButtons: ImmutableSet<String>,
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    onClickChapterList: () -> Unit,
    // KMK -->
    onClickPageGrid: () -> Unit,
    // KMK <--
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
    // SY <--
    // KMK (r125): the glass bars (Settings › Reader › "Glass reader bars")
    glass: Boolean = false,
    onSetReadingMode: ((ReadingMode) -> Unit)? = null,
    // KMK (r128): the Chapters / Pages sheet is open (the slider steps aside)
    sheetOpen: Boolean = false,
) {
    if (glass) {
        GlassReaderAppBars(
            sheetOpen = sheetOpen,
            visible = visible,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            navigateUp = navigateUp,
            onOpenInWebView = onOpenInWebView,
            onOpenInBrowser = onOpenInBrowser,
            onShare = onShare,
            viewer = viewer,
            onNextChapter = onNextChapter,
            enabledNext = enabledNext,
            onPreviousChapter = onPreviousChapter,
            enabledPrevious = enabledPrevious,
            currentPage = currentPage,
            totalPages = totalPages,
            onPageIndexChange = onPageIndexChange,
            readingMode = readingMode,
            onClickReadingMode = onClickReadingMode,
            onSetReadingMode = onSetReadingMode,
            orientation = orientation,
            onClickOrientation = onClickOrientation,
            cropEnabled = cropEnabled,
            onClickCropBorder = onClickCropBorder,
            onClickSettings = onClickSettings,
            isExhToolsVisible = isExhToolsVisible,
            onSetExhUtilsVisibility = onSetExhUtilsVisibility,
            isAutoScroll = isAutoScroll,
            isAutoScrollEnabled = isAutoScrollEnabled,
            onToggleAutoscroll = onToggleAutoscroll,
            autoScrollFrequency = autoScrollFrequency,
            onSetAutoScrollFrequency = onSetAutoScrollFrequency,
            onClickAutoScrollHelp = onClickAutoScrollHelp,
            onClickRetryAll = onClickRetryAll,
            onClickRetryAllHelp = onClickRetryAllHelp,
            onClickBoostPage = onClickBoostPage,
            onClickBoostPageHelp = onClickBoostPageHelp,
            navBarType = navBarType,
            currentPageText = currentPageText,
            enabledButtons = enabledButtons,
            currentReadingMode = currentReadingMode,
            dualPageSplitEnabled = dualPageSplitEnabled,
            doublePages = doublePages,
            onClickChapterList = onClickChapterList,
            onClickPageGrid = onClickPageGrid,
            onClickPageLayout = onClickPageLayout,
            onClickShiftPage = onClickShiftPage,
        )
        return
    }
    val isRtl = viewer is R2LPagerViewer
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

    Column(modifier = Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeIn(animationSpec = readerBarsFadeAnimationSpec),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeOut(animationSpec = readerBarsFadeAnimationSpec),
        ) {
            // SY -->
            Column {
                // SY <--
                ReaderTopBar(
                    modifier = Modifier
                        .background(backgroundColor)
                        .clickable(onClick = onClickTopAppBar),
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    navigateUp = navigateUp,
                    bookmarked = bookmarked,
                    onToggleBookmarked = onToggleBookmarked,
                    // SY -->
                    onOpenInWebView = null, // onOpenInWebView,
                    onOpenInBrowser = null, // onOpenInBrowser,
                    onShare = null, // onShare,
                    // SY <--
                )
                // SY -->
                ExhUtils(
                    isVisible = isExhToolsVisible,
                    onSetExhUtilsVisibility = onSetExhUtilsVisibility,
                    backgroundColor = backgroundColor,
                    isAutoScroll = isAutoScroll,
                    isAutoScrollEnabled = isAutoScrollEnabled,
                    onToggleAutoscroll = onToggleAutoscroll,
                    autoScrollFrequency = autoScrollFrequency,
                    onSetAutoScrollFrequency = onSetAutoScrollFrequency,
                    onClickAutoScrollHelp = onClickAutoScrollHelp,
                    onClickRetryAll = onClickRetryAll,
                    onClickRetryAllHelp = onClickRetryAllHelp,
                    onClickBoostPage = onClickBoostPage,
                    onClickBoostPageHelp = onClickBoostPageHelp,
                )
            }
            // SY <--
        }

        // KMK -->
        when (navBarType) {
            NavBarType.VerticalLeft -> {
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = readerBarsSlideAnimationSpec,
                    ) +
                        fadeIn(animationSpec = readerBarsFadeAnimationSpec),
                    exit = slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = readerBarsSlideAnimationSpec,
                    ) +
                        fadeOut(animationSpec = readerBarsFadeAnimationSpec),
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.Start),
                ) {
                    ChapterNavigator(
                        isRtl = isRtl,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        // SY -->
                        isVerticalSlider = true,
                        currentPageText = currentPageText,
                        // SY <--
                    )
                }
            }

            NavBarType.VerticalRight -> {
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = readerBarsSlideAnimationSpec,
                    ) +
                        fadeIn(animationSpec = readerBarsFadeAnimationSpec),
                    exit = slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = readerBarsSlideAnimationSpec,
                    ) +
                        fadeOut(animationSpec = readerBarsFadeAnimationSpec),
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.End),
                ) {
                    ChapterNavigator(
                        isRtl = isRtl,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        // SY -->
                        isVerticalSlider = true,
                        currentPageText = currentPageText,
                        // SY <--
                    )
                }
            }
            // KMK <--
            else -> Spacer(modifier = Modifier.weight(1f))
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeIn(animationSpec = readerBarsFadeAnimationSpec),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeOut(animationSpec = readerBarsFadeAnimationSpec),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                // SY -->
                if (navBarType == NavBarType.Bottom) {
                    // SY <--
                    ChapterNavigator(
                        isRtl = isRtl,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        // SY -->
                        isVerticalSlider = false,
                        currentPageText = currentPageText,
                        // SY <--
                    )
                }
                ReaderBottomBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .padding(horizontal = MaterialTheme.padding.small)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    readingMode = readingMode,
                    onClickReadingMode = onClickReadingMode,
                    orientation = orientation,
                    onClickOrientation = onClickOrientation,
                    cropEnabled = cropEnabled,
                    onClickCropBorder = onClickCropBorder,
                    onClickSettings = onClickSettings,
                    // SY -->
                    enabledButtons = enabledButtons,
                    currentReadingMode = currentReadingMode,
                    dualPageSplitEnabled = dualPageSplitEnabled,
                    doublePages = doublePages,
                    onClickChapterList = onClickChapterList,
                    // KMK -->
                    onClickPageGrid = onClickPageGrid,
                    // KMK <--
                    onClickWebView = onOpenInWebView,
                    onClickBrowser = onOpenInBrowser,
                    onClickShare = onShare,
                    onClickPageLayout = onClickPageLayout,
                    onClickShiftPage = onClickShiftPage,
                    // SY <--
                )
            }
        }
    }
}

// KMK (r125) --> the glass bars: round back, title pill (tap = Chapters / Pages), ⋮ sheet, glass slider
@Composable
private fun GlassReaderAppBars(
    sheetOpen: Boolean,
    visible: Boolean,
    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    viewer: Viewer?,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    onSetReadingMode: ((ReadingMode) -> Unit)?,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    isExhToolsVisible: Boolean,
    onSetExhUtilsVisibility: (Boolean) -> Unit,
    isAutoScroll: Boolean,
    isAutoScrollEnabled: Boolean,
    onToggleAutoscroll: (Boolean) -> Unit,
    autoScrollFrequency: String,
    onSetAutoScrollFrequency: (String) -> Unit,
    onClickAutoScrollHelp: () -> Unit,
    onClickRetryAll: () -> Unit,
    onClickRetryAllHelp: () -> Unit,
    onClickBoostPage: () -> Unit,
    onClickBoostPageHelp: () -> Unit,
    navBarType: NavBarType,
    currentPageText: String,
    enabledButtons: ImmutableSet<String>,
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    onClickChapterList: () -> Unit,
    onClickPageGrid: () -> Unit,
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
) {
    val isRtl = viewer is R2LPagerViewer
    var actionsOpen by remember { mutableStateOf(false) }
    val pageText = if (totalPages > 0) "$currentPageText / $totalPages" else null
    // KMK (r128): the slider slides away while a sheet (Chapters / Pages, or ⋮) is open
    val sliderVisible = visible && !sheetOpen && !actionsOpen

    // KMK (r129): like WWDC25's Liquid Glass, the glass appears by growing out of nothing (not sliding in), and the
    // page dims softly at the top and bottom edges under the floating controls so they stay clear ("scroll edge")
    val still = remember { eu.kanade.presentation.browse.components.glassReduceMotion() }
    val popIn = { origin: androidx.compose.ui.graphics.TransformOrigin ->
        if (still) {
            fadeIn(animationSpec = readerBarsFadeAnimationSpec)
        } else {
            androidx.compose.animation.scaleIn(
                initialScale = 0.86f,
                transformOrigin = origin,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f),
            ) + fadeIn(animationSpec = readerBarsFadeAnimationSpec)
        }
    }
    val popOut = { origin: androidx.compose.ui.graphics.TransformOrigin ->
        if (still) {
            fadeOut(animationSpec = readerBarsFadeAnimationSpec)
        } else {
            androidx.compose.animation.scaleOut(targetScale = 0.9f, transformOrigin = origin) +
                fadeOut(animationSpec = readerBarsFadeAnimationSpec)
        }
    }
    val topOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
    val bottomOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)

    CompositionLocalProvider(LocalReaderGlass provides true) {
        Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = readerBarsFadeAnimationSpec),
            exit = fadeOut(animationSpec = readerBarsFadeAnimationSpec),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }
        AnimatedVisibility(
            visible = sliderVisible && navBarType == NavBarType.Bottom,
            enter = fadeIn(animationSpec = readerBarsFadeAnimationSpec),
            exit = fadeOut(animationSpec = readerBarsFadeAnimationSpec),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.5f),
                        ),
                    ),
            )
        }
        Column(modifier = Modifier.fillMaxHeight()) {
            AnimatedVisibility(
                visible = visible,
                enter = popIn(topOrigin),
                exit = popOut(topOrigin),
            ) {
                Column {
                    GlassTopBar(
                        mangaTitle = mangaTitle,
                        chapterTitle = chapterTitle,
                        pageText = pageText,
                        navigateUp = navigateUp,
                        onClickTitle = onClickChapterList,
                        onClickMore = { actionsOpen = true },
                    )
                    // KMK (r127): no ⌄ strip under the glass top bar; the tools open from the ⋮ sheet
                    if (isExhToolsVisible) ExhUtils(
                        isVisible = isExhToolsVisible,
                        onSetExhUtilsVisibility = onSetExhUtilsVisibility,
                        backgroundColor = ReaderGlass.tint,
                        isAutoScroll = isAutoScroll,
                        isAutoScrollEnabled = isAutoScrollEnabled,
                        onToggleAutoscroll = onToggleAutoscroll,
                        autoScrollFrequency = autoScrollFrequency,
                        onSetAutoScrollFrequency = onSetAutoScrollFrequency,
                        onClickAutoScrollHelp = onClickAutoScrollHelp,
                        onClickRetryAll = onClickRetryAll,
                        onClickRetryAllHelp = onClickRetryAllHelp,
                        onClickBoostPage = onClickBoostPage,
                        onClickBoostPageHelp = onClickBoostPageHelp,
                    )
                }
            }

            if (navBarType == NavBarType.Bottom) {
                Spacer(modifier = Modifier.weight(1f))
            } else {
                val fromStart = navBarType == NavBarType.VerticalLeft
                AnimatedVisibility(
                    visible = sliderVisible,
                    enter = slideInHorizontally(
                        initialOffsetX = { if (fromStart) -it else it },
                        animationSpec = readerBarsSlideAnimationSpec,
                    ) + fadeIn(animationSpec = readerBarsFadeAnimationSpec),
                    exit = slideOutHorizontally(
                        targetOffsetX = { if (fromStart) -it else it },
                        animationSpec = readerBarsSlideAnimationSpec,
                    ) + fadeOut(animationSpec = readerBarsFadeAnimationSpec),
                    modifier = Modifier
                        .weight(1f)
                        .align(if (fromStart) Alignment.Start else Alignment.End)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    ChapterNavigator(
                        isRtl = isRtl,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        isVerticalSlider = true,
                        currentPageText = currentPageText,
                    )
                }
            }

            AnimatedVisibility(
                visible = sliderVisible && navBarType == NavBarType.Bottom,
                enter = popIn(bottomOrigin),
                exit = popOut(bottomOrigin),
            ) {
                Box(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 8.dp),
                ) {
                    ChapterNavigator(
                        isRtl = isRtl,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        isVerticalSlider = false,
                        currentPageText = currentPageText,
                    )
                }
            }
        }
        }
    }

    if (actionsOpen) {
        GlassActionSheet(
            onDismissRequest = { actionsOpen = false },
            readingMode = readingMode,
            onSetReadingMode = onSetReadingMode,
            onClickReadingMode = onClickReadingMode,
            orientation = orientation,
            onClickOrientation = onClickOrientation,
            cropEnabled = cropEnabled,
            onClickCropBorder = onClickCropBorder,
            onClickSettings = onClickSettings,
            currentReadingMode = currentReadingMode,
            dualPageSplitEnabled = dualPageSplitEnabled,
            doublePages = doublePages,
            enabledButtons = enabledButtons,
            onClickChapterList = onClickChapterList,
            onClickPageGrid = onClickPageGrid,
            onClickWebView = onOpenInWebView,
            onClickBrowser = onOpenInBrowser,
            onClickShare = onShare,
            onClickPageLayout = onClickPageLayout,
            onClickShiftPage = onClickShiftPage,
            onClickTools = { onSetExhUtilsVisibility(true) },
        )
    }
}
// KMK (r125) <--
