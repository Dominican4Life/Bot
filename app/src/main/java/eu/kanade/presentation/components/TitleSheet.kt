package eu.kanade.presentation.components

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import eu.kanade.presentation.browse.components.liquidGlass
import dev.chrisbanes.haze.hazeSource
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * KMK (r124): the phone title page's sheet. The chapter / episode list sits in a sheet that stops at peek, half and
 * full; a dock at the bottom switches between the list and ▦ Preview and has a split Play button. Phones only: the
 * tablet layout keeps its two columns.
 */
object TitleSheet {
    private val store get() = Injekt.get<PreferenceStore>()

    const val PEEK = 0
    const val HALF = 1
    const val FULL = 2
    const val LAST_USED = 3

    const val SHAPE_AUTO = 0
    const val SHAPE_PAGE = 1
    const val SHAPE_SQUARE = 2
    const val SHAPE_STRIP = 3

    /** Settings › Appearance: the sheet on phones (off = the classic list) */
    fun enabled(): Preference<Boolean> = store.getBoolean("title_sheet_on", true)

    /** KMK (r129): the sheet, its dock and the top buttons in glass over the cover and description */
    fun glass(): Preference<Boolean> = store.getBoolean("title_sheet_glass", true)

    /** KMK (r132): the AniList banner picture behind the title page (looked up once per title) */
    fun anilistBanner(): Preference<Boolean> = store.getBoolean("title_anilist_banner", true)

    /** KMK (r132): tablet title page: the Preview card beside the chapters (the toggle in the top capsule) */
    fun tabletPreview(): Preference<Boolean> = store.getBoolean("tablet_title_preview", true)

    /** KMK (r132): tablet title page: the Suggestions row above the chapters */
    fun tabletSuggestions(): Preference<Boolean> = store.getBoolean("tablet_title_suggestions", true)

    /** Where the sheet stopped last */
    fun lastStop(): Preference<Int> = store.getInt("title_sheet_last_stop", HALF)

    /** Where the sheet opens: PEEK / HALF / FULL / LAST_USED */
    fun openAt(): Preference<Int> = store.getInt("title_sheet_open_at", LAST_USED)

    /** Preview columns, per kind: "manga", "strip", "anime" */
    fun columns(kind: String): Preference<Int> =
        store.getInt("title_sheet_columns_$kind", if (kind == "anime") 2 else 3)

    /** Preview tile shape, per kind */
    fun shape(kind: String): Preference<Int> = store.getInt("title_sheet_shape_$kind", SHAPE_AUTO)

    fun startStop(): Int = openAt().get().let { if (it == LAST_USED) lastStop().get() else it }.coerceIn(PEEK, FULL)
}

enum class TitleSheetTab { LIST, PREVIEW }

/** Remembers the sheet's stop and tab for one title page */
class TitleSheetState(initialStop: Int) {
    var stop by mutableStateOf(initialStop)
    var tab by mutableStateOf(TitleSheetTab.LIST)

    /** Set by [TitleSheetBox]: moves the sheet to a stop, animated */
    internal var animateTo: (Int) -> Unit = { stop = it }

    fun moveTo(target: Int) = animateTo(target.coerceIn(TitleSheet.PEEK, TitleSheet.FULL))
}

@Composable
fun rememberTitleSheetState(): TitleSheetState = remember { TitleSheetState(TitleSheet.startStop()) }

/**
 * The page under the sheet ([background], given the bottom space the peeking sheet and dock take), the sheet with
 * [sheetHeader] (the drag handle and its buttons) and [sheetContent], and the dock.
 */
@Composable
fun TitleSheetBox(
    state: TitleSheetState,
    topInset: Dp,
    showDock: Boolean,
    playLabel: String?,
    onPlay: () -> Unit,
    playMenu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    background: @Composable (bottomSpace: Dp) -> Unit,
    sheetHeader: @Composable ColumnScope.() -> Unit,
    sheetContent: @Composable (bottomSpace: Dp) -> Unit,
    modifier: Modifier = Modifier,
    // KMK (r130): a second blur source for the page (the frosted top bar)
    frostSource: dev.chrisbanes.haze.HazeState? = null,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val peekHeight = 132.dp
    val dockSpace = 76.dp
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fullHeightPx = constraints.maxHeight.toFloat()
        val navBottom = with(density) {
            WindowInsets.navigationBars.getBottom(this).toFloat()
        }
        val fullY = with(density) { topInset.toPx() }
        val peekY = (fullHeightPx - with(density) { (peekHeight + dockSpace).toPx() } - navBottom)
            .coerceAtLeast(fullY)
        val halfY = (fullHeightPx * 0.42f).coerceIn(fullY, peekY)
        fun yOf(stop: Int) = when (stop) {
            TitleSheet.FULL -> fullY
            TitleSheet.HALF -> halfY
            else -> peekY
        }
        var sheetY by remember { mutableFloatStateOf(yOf(state.stop)) }
        // The running snap animation, stopped when a finger moves the sheet again
        val animation = remember { arrayOfNulls<Job>(1) }
        fun snapTo(target: Float, velocity: Float) {
            animation[0]?.cancel()
            animation[0] = scope.launch {
                animate(sheetY, target, velocity, spring(dampingRatio = 0.9f, stiffness = 500f)) { v, _ -> sheetY = v }
            }
        }
        fun stopAnimation() {
            animation[0]?.cancel()
            animation[0] = null
        }
        // The screen size changed (rotation, keyboard): stay on the same stop
        LaunchedEffect(fullHeightPx, fullY) { sheetY = yOf(state.stop) }

        fun settle(velocity: Float) {
            val anchors = listOf(TitleSheet.FULL to fullY, TitleSheet.HALF to halfY, TitleSheet.PEEK to peekY)
            val target = when {
                velocity < -1200f -> anchors.filter { it.second < sheetY - 1f }.maxByOrNull { it.second }
                velocity > 1200f -> anchors.filter { it.second > sheetY + 1f }.minByOrNull { it.second }
                else -> null
            } ?: anchors.minBy { abs(it.second - sheetY) }
            state.stop = target.first
            TitleSheet.lastStop().set(target.first)
            snapTo(target.second, velocity)
        }
        state.animateTo = { target ->
            state.stop = target
            TitleSheet.lastStop().set(target)
            snapTo(yOf(target), 0f)
        }

        // Back from a full sheet drops it to half first
        BackHandler(enabled = state.stop == TitleSheet.FULL) { state.moveTo(TitleSheet.HALF) }

        // The list inside scrolls only when the sheet is full; before that a drag moves the sheet
        val connection = remember(fullY, halfY, peekY) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y < 0f && sheetY > fullY + 0.5f) {
                        stopAnimation()
                        val next = (sheetY + available.y).coerceAtLeast(fullY)
                        val used = next - sheetY
                        sheetY = next
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y > 0f) {
                        stopAnimation()
                        val next = (sheetY + available.y).coerceAtMost(peekY)
                        val used = next - sheetY
                        sheetY = next
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val atAnchor = listOf(fullY, halfY, peekY).any { abs(it - sheetY) < 1f }
                    if (atAnchor) return Velocity.Zero
                    settle(available.y)
                    return available
                }
            }
        }

        val bottomSpace = with(density) { (fullHeightPx - peekY).toDp() }
        // KMK (r129): glass (WWDC25): the cover and description stay the content layer; the sheet, the dock and the
        // top buttons float over it as glass that shows them blurred. The sheet turns solid as it reaches full height
        // (as a full sheet does), so the list reads like a page.
        val glassPref = remember { TitleSheet.glass() }
        val glass by glassPref.collectAsState()
        val haze = remember { dev.chrisbanes.haze.HazeState() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (glass) Modifier.hazeSource(haze) else Modifier)
                .then(if (frostSource != null) Modifier.hazeSource(frostSource) else Modifier),
        ) {
            background(bottomSpace)
        }

        val sheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
        androidx.compose.runtime.CompositionLocalProvider(
            eu.kanade.presentation.browse.components.LocalGlassHaze provides haze.takeIf { glass },
        ) {
        Surface(
            shape = sheetShape,
            color = if (glass) androidx.compose.ui.graphics.Color.Transparent else surfaceColor,
            tonalElevation = if (glass) 0.dp else 2.dp,
            shadowElevation = if (glass) 0.dp else 12.dp,
            modifier = Modifier
                .offset { IntOffset(0, sheetY.roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { (fullHeightPx - fullY).toDp() })
                .then(
                    if (glass) {
                        Modifier
                            .shadow(16.dp, sheetShape)
                            .liquidGlass(sheetShape, surfaceColor, thick = true)
                            .drawBehind {
                                // Solid as it nears full height (read here, so a drag only redraws)
                                val fullness = if (peekY - fullY > 1f) {
                                    ((peekY - sheetY) / (peekY - fullY)).coerceIn(0f, 1f)
                                } else {
                                    1f
                                }
                                val solid = (fullness - 0.55f).coerceAtLeast(0f) / 0.45f
                                if (solid > 0f) drawRect(surfaceColor.copy(alpha = 0.9f * solid))
                            }
                    } else {
                        Modifier
                    },
                )
                .nestedScroll(connection),
        ) {
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            onDragStarted = { stopAnimation() },
                            state = rememberDraggableState { delta ->
                                sheetY = (sheetY + delta).coerceIn(fullY, peekY)
                            },
                            onDragStopped = { velocity -> settle(velocity) },
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                state.moveTo(if (state.stop == TitleSheet.FULL) TitleSheet.HALF else TitleSheet.FULL)
                            }
                            .padding(top = 8.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 38.dp, height = 4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.outline),
                        )
                    }
                    sheetHeader()
                }
                // The part of the sheet below the screen at half / peek isn't reachable: the list's bottom padding
                // keeps its last rows above the dock once the sheet is full
                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    sheetContent(dockSpace + with(density) { navBottom.toDp() } + 8.dp)
                }
            }
        }

        if (showDock) {
            TitleSheetDock(
                glass = glass,
                tab = state.tab,
                onTab = { tab ->
                    state.tab = tab
                    if (state.stop == TitleSheet.PEEK) state.moveTo(TitleSheet.HALF)
                },
                playLabel = playLabel,
                onPlay = onPlay,
                playMenu = playMenu,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        }
    }
}

@Composable
private fun TitleSheetDock(
    glass: Boolean,
    tab: TitleSheetTab,
    onTab: (TitleSheetTab) -> Unit,
    playLabel: String?,
    onPlay: () -> Unit,
    playMenu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dockColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        shape = CircleShape,
        color = if (glass) androidx.compose.ui.graphics.Color.Transparent else dockColor,
        shadowElevation = if (glass) 0.dp else 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            // KMK (r129): a glass capsule; only Play keeps its colour (tint only the main action)
            .then(if (glass) Modifier.shadow(10.dp, CircleShape).liquidGlass(CircleShape, dockColor) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DockTab(
                selected = tab == TitleSheetTab.LIST,
                icon = GlassIcons.Chapters,
                label = stringResource(KMR.strings.title_sheet_list),
                onClick = { onTab(TitleSheetTab.LIST) },
            )
            DockTab(
                selected = tab == TitleSheetTab.PREVIEW,
                icon = GlassIcons.Preview,
                label = stringResource(KMR.strings.title_sheet_preview),
                onClick = { onTab(TitleSheetTab.PREVIEW) },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (playLabel != null) {
                SplitPlayButton(label = playLabel, onPlay = onPlay, menu = playMenu)
            }
        }
    }
}

@Composable
private fun RowScope.DockTab(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 42.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun SplitPlayButton(
    label: String,
    onPlay: () -> Unit,
    // KMK (r125): the ⌄ menu was taken off (owner's request); the parameter stays for the callers
    @Suppress("UNUSED_PARAMETER") menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.height(42.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable(onClick = onPlay)
                    .padding(start = 14.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 170.dp),
                )
            }
        }
    }
}

/**
 * The ⤢ size controls: columns (or pinch the grid), tile shape (not for anime) and where the sheet opens. [kind] is
 * "manga", "strip" or "anime"; each keeps its own columns and shape.
 */
@Composable
fun TitleSheetSizeMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    kind: String,
    showShape: Boolean,
) {
    // KMK (r134): the glass menu look (rounded, lit rim, dark glass), wider than the 196 dp glass DropdownMenu
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = GlassMenuStyle.shape,
        containerColor = GlassMenuStyle.container(),
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        border = GlassMenuStyle.rim(),
    ) {
        val columnsPref = remember(kind) { TitleSheet.columns(kind) }
        val columns by columnsPref.collectAsState()
        val shapePref = remember(kind) { TitleSheet.shape(kind) }
        val shape by shapePref.collectAsState()
        val openAtPref = remember { TitleSheet.openAt() }
        val openAt by openAtPref.collectAsState()
        // KMK (r133): glass version: stepped glass slider, glass switches with pictures for the shape
        Column(modifier = Modifier.width(340.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(KMR.strings.title_sheet_size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            GlassSliderRow(
                label = stringResource(KMR.strings.title_sheet_columns),
                value = columns,
                range = 1..6,
                onValueChange = { columnsPref.set(it) },
            )
            if (showShape) {
                Text(
                    text = stringResource(KMR.strings.title_sheet_shape),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                )
                GlassChoiceSwitch(
                    choices = listOf(
                        TitleSheet.SHAPE_AUTO to stringResource(KMR.strings.title_sheet_shape_auto),
                        TitleSheet.SHAPE_PAGE to stringResource(KMR.strings.title_sheet_shape_page),
                        TitleSheet.SHAPE_SQUARE to stringResource(KMR.strings.title_sheet_shape_square),
                        TitleSheet.SHAPE_STRIP to stringResource(KMR.strings.title_sheet_shape_strip),
                    ),
                    selected = shape,
                    onSelect = { shapePref.set(it) },
                    icons = mapOf(
                        TitleSheet.SHAPE_AUTO to GlassIcons.ShapeAuto,
                        TitleSheet.SHAPE_PAGE to GlassIcons.ShapePage,
                        TitleSheet.SHAPE_SQUARE to GlassIcons.ShapeSquare,
                        TitleSheet.SHAPE_STRIP to GlassIcons.ShapeStrip,
                    ),
                )
            }
            Text(
                text = stringResource(KMR.strings.title_sheet_opens_at),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            GlassChoiceSwitch(
                choices = listOf(
                    TitleSheet.PEEK to stringResource(KMR.strings.title_sheet_peek),
                    TitleSheet.HALF to stringResource(KMR.strings.title_sheet_half),
                    TitleSheet.FULL to stringResource(KMR.strings.title_sheet_full),
                    TitleSheet.LAST_USED to stringResource(KMR.strings.title_sheet_last_used),
                ),
                selected = openAt,
                onSelect = { openAtPref.set(it) },
            )
            Text(
                text = stringResource(KMR.strings.title_sheet_size_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(selected: Int, choices: List<Pair<Int, String>>, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

/** Pinch the grid out for fewer, bigger tiles and in for more, smaller ones (1 to 6 columns) */
fun Modifier.pinchColumns(columns: Int, onChange: (Int) -> Unit): Modifier = pointerInput(columns) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        var pinching = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                pinching = true
                zoom *= event.calculateZoom()
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (pinching) {
            when {
                zoom > 1.25f -> onChange((columns - 1).coerceAtLeast(1))
                zoom < 0.8f -> onChange((columns + 1).coerceAtMost(6))
            }
        }
    }
}

/** The menu items under the Play button's ⌄ */
@Composable
fun ColumnScope.TitleSheetPlayMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}
