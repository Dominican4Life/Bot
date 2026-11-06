package eu.kanade.tachiyomi.ui.diagnostics

import eu.kanade.presentation.browse.components.liquidGlass
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.tachiyomi.diagnostics.Diag
import eu.kanade.tachiyomi.diagnostics.DiagBudget
import eu.kanade.tachiyomi.diagnostics.DiagKeys
import eu.kanade.tachiyomi.diagnostics.FloatingLogState
import eu.kanade.tachiyomi.diagnostics.LogEntry
import eu.kanade.tachiyomi.diagnostics.Resource
import eu.kanade.tachiyomi.diagnostics.TimeWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.math.roundToInt

/**
 * KMK (r97): The floating log window (design §2.6, ph-11 / tb-11): a draggable, resizable log tail that floats over
 * every screen of the app while a bug is reproduced. Hosted as the last child of the root Box in MainActivity,
 * ReaderActivity and PlayerActivity; [FloatingLogState] is a process singleton, so the window keeps its content
 * and place across activities. **Hidden = not composed**: no collector, no sampler, no layout. Visible: the mini
 * network graph (the engine's `(NETWORK, M1)` window), a readout from the strip, chips All · 3 top tags · Problems,
 * the last 50 lines re-read on `logVersion` at most 10×/s, Mark, collapse to a 52 dp bubble (badge = W/E since
 * collapsed, red ring while recording), close. Position and size are kept per orientation.
 */
@Composable
internal fun FloatingLogHost(hidden: Boolean = false) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { restoreOnce(context) }
    val visible by FloatingLogState.visible.collectAsState()
    // One window app-wide: only a STARTED activity's host may compose it, and only the one holding the owner token
    // (MainActivity's host lets go when the reader or player comes to the front, and takes over again after)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val token = remember { Any() }
    val owner by hostOwner.collectAsState()
    val eligible = visible && !hidden && started
    LaunchedEffect(eligible) {
        if (!eligible) return@LaunchedEffect
        hostOwner.collect { current -> if (current == null) hostOwner.compareAndSet(null, token) }
    }
    DisposableEffect(eligible) {
        onDispose { hostOwner.compareAndSet(token, null) }
    }
    if (!eligible || owner !== token) return
    val collapsed by FloatingLogState.collapsed.collectAsState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val parent = IntSize(with(density) { maxWidth.roundToPx() }, with(density) { maxHeight.roundToPx() })
        if (collapsed) {
            FloatingBubble(parent = parent)
        } else {
            // KMK (r131): light text on the dark glass, whatever the app theme
            val base = MaterialTheme.colorScheme
            MaterialTheme(
                colorScheme = base.copy(
                    onSurface = Color(0xFFE6E8EE),
                    onSurfaceVariant = Color(0xFFB8BCC6),
                    surfaceContainerHighest = Color.White.copy(alpha = 0.08f),
                    surfaceContainerHigh = Color.White.copy(alpha = 0.05f),
                    outline = Color.White.copy(alpha = 0.4f),
                    outlineVariant = Color.White.copy(alpha = 0.2f),
                    secondaryContainer = Color.White.copy(alpha = 0.16f),
                    onSecondaryContainer = Color.White,
                ),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides Color(0xFFE6E8EE),
                ) {
                    FloatingWindow(parent = parent)
                }
            }
        }
    }
}

private var restored = false

/** The host that composes the window right now (one per process) */
private val hostOwner = MutableStateFlow<Any?>(null)

/** The orientation `FloatingLogState.rect` was saved in (the per-orientation prefs are preferred anyway) */
private var rectLandscape: Boolean? = null

/** `FLOAT_ON` brings the window back after a restart during testing; once per process. */
private fun restoreOnce(context: Context) {
    if (restored) return
    restored = true
    if (diagPrefs(context).getBoolean(DiagKeys.FLOAT_ON, false)) FloatingLogState.visible.value = true
}

private const val MIN_W_DP = 220
private const val MIN_H_DP = 140

// KMK (r107): wrap and text size of the floating window only (the `diagnostics` prefs file, like FLOAT_RECT_*)
private const val FLOAT_WRAP = "float_wrap"
private const val FLOAT_TEXT_SP = "float_text_sp"
private const val TEXT_SP_DEFAULT = 10.5f
private const val TEXT_SP_MIN = 8f
private const val TEXT_SP_MAX = 18f
private const val TEXT_SP_STEP = 1f

/** KMK (r107): below this window width the time and level sit on their own line above the message */
private const val NARROW_DP = 520

@Composable
private fun FloatingWindow(parent: IntSize) {
    val diag = remember { Diag.get() }
    val context = LocalContext.current
    val density = LocalDensity.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val minW = with(density) { MIN_W_DP.dp.roundToPx() }
    val minH = with(density) { MIN_H_DP.dp.roundToPx() }
    val margin = with(density) { 12.dp.roundToPx() }
    // KMK (r126): kept inside the status bar, the navigation / gesture bar, a cutout and the keyboard, so the title
    // bar can always be grabbed (it used to slide under the bars, where Android takes the touches)
    val safe = rememberSafeInsets()
    val maxW = (parent.width - safe.left - safe.right - 2 * margin).coerceAtLeast(minW)
    val maxH = (parent.height - safe.top - safe.bottom - 2 * margin).coerceAtLeast(minH)

    // The rect for this orientation: the one saved for it, else the singleton's (same orientation only), else the
    // default corner
    var rect by remember(landscape, parent) {
        mutableStateOf(
            loadRect(context, landscape)
                ?: FloatingLogState.rect.value?.takeIf { it.size == 4 && rectLandscape == landscape }
                ?: intArrayOf(
                    margin,
                    (parent.height - with(density) { 400.dp.roundToPx() }).coerceAtLeast(margin),
                    with(density) { 300.dp.roundToPx() }.coerceAtMost(maxW),
                    with(density) { 260.dp.roundToPx() }.coerceAtMost(maxH),
                ),
        )
    }
    // KMK (r131): it moves anywhere again (even partly off the side or bottom); only its title bar has to stay
    // clear of the bars so it can always be grabbed
    val keep = with(density) { 96.dp.roundToPx() }
    val titleBar = with(density) { 36.dp.roundToPx() }
    fun clamp(r: IntArray): IntArray {
        val w = r[2].coerceIn(minW, maxW)
        val h = r[3].coerceIn(minH, maxH)
        // KMK (r133): the left and right edges stay fully on screen too (it can't be pushed off a side any more)
        val x = r[0].coerceIn(safe.left, (parent.width - safe.right - w).coerceAtLeast(safe.left))
        // KMK (r132): top and bottom stay fully between the status bar and the navigation bar / taskbar (with gesture
        // navigation that's the thin gesture line); left and right stay free
        val y = r[1].coerceIn(safe.top, (parent.height - safe.bottom - h).coerceAtLeast(safe.top))
        return intArrayOf(x, y, w, h)
    }
    fun persist() {
        FloatingLogState.rect.value = rect
        rectLandscape = landscape
        saveRect(context, landscape, rect)
    }
    LaunchedEffect(parent, safe) { rect = clamp(rect) }

    val strip by diag.strip.collectAsState()
    val recording by diag.recording.collectAsState()
    val window by remember { diag.samples(Resource.NETWORK, TimeWindow.M1) }.collectAsState()
    val tagFilter by FloatingLogState.tagFilter.collectAsState()
    val paused by FloatingLogState.paused.collectAsState()
    var problemsOnly by remember { mutableStateOf(false) }
    var tail by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    val listState = rememberLazyListState()
    // KMK (r107): lines wrap to the window width and reflow on resize (↵ off = one line each + sideways scroll);
    // A− / A+ change this window's text size only; both are remembered
    var wrap by remember { mutableStateOf(diagPrefs(context).getBoolean(FLOAT_WRAP, true)) }
    var textSp by remember {
        mutableFloatStateOf(
            diagPrefs(context).getFloat(FLOAT_TEXT_SP, TEXT_SP_DEFAULT).coerceIn(TEXT_SP_MIN, TEXT_SP_MAX),
        )
    }
    val sideScroll = rememberScrollState()
    // KMK (r107) fix: the widest row seen while not wrapped (px), again from 0 when wrap or the text size changes
    var widest by remember(wrap, textSp) { mutableIntStateOf(0) }
    val narrow = with(density) { rect[2].toDp() } < NARROW_DP.dp
    fun setTextSize(value: Float) {
        textSp = value.coerceIn(TEXT_SP_MIN, TEXT_SP_MAX)
        diagPrefs(context).edit { putFloat(FLOAT_TEXT_SP, textSp) }
    }

    DisposableEffect(Unit) {
        diag.setStripVisible(true)
        diag.setLiveVisible(true)
        FloatingLogState.errorsSinceSeen.value = 0
        onDispose {
            diag.setStripVisible(false)
            diag.setLiveVisible(false)
        }
    }
    // ≤ 50 rows, re-read at most every 100 ms (contract §8 rule 5)
    LaunchedEffect(tagFilter, problemsOnly, paused) {
        if (paused) return@LaunchedEffect
        diag.logVersion.collect {
            tail = diag.logTail(DiagBudget.FLOAT_TAIL_LINES, tagFilter, problemsOnly)
            delay(DiagBudget.FLOAT_REFRESH_MIN_MS)
        }
    }
    LaunchedEffect(tail.size, paused) {
        if (!paused && tail.isNotEmpty()) listState.scrollToItem(tail.size - 1)
    }
    // KMK (r128): only a finger dragging the list pauses it (its own scroll to the newest line used to count as a
    // touch, so a busy log was paused most of the time); it goes on again after 10 s, or at once when scrolled back
    // to the bottom
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) {
            FloatingLogState.paused.value = true
        } else if (FloatingLogState.paused.value) {
            delay(10_000L)
            FloatingLogState.paused.value = false
        }
    }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(atBottom, dragged) {
        if (atBottom && !dragged && FloatingLogState.paused.value) FloatingLogState.paused.value = false
    }
    val topTags = remember(tail) {
        tail.groupingBy { it.tag }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
    }

    Column(
        modifier = Modifier
            .offset { IntOffset(rect[0], rect[1]) }
            .size(with(density) { rect[2].toDp() }, with(density) { rect[3].toDp() })
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            // KMK (r131): frosted glass (the page behind, blurred, where the screen offers it)
            .liquidGlass(RoundedCornerShape(16.dp), Color(0xFF14161E))
            // KMK (r132): denser frost so the lines stay readable over busy pages
            .background(Color(0xFF14161E).copy(alpha = 0.55f)),
    ) {
        // Title bar: drag grip, readout, Mark, collapse, close
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(Color.White.copy(alpha = 0.06f))
                .pointerInput(parent, safe) {
                    detectDragGestures(onDragEnd = { persist() }) { change, drag ->
                        change.consume()
                        val r = rect
                        rect = clamp(intArrayOf(r[0] + drag.x.roundToInt(), r[1] + drag.y.roundToInt(), r[2], r[3]))
                    }
                }
                .padding(start = 12.dp, end = 2.dp),
        ) {
            Text("Live", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(
                String.format(
                    Locale.US,
                    "%.1f MB/s · %d MB · %d/%d",
                    strip.netMbps,
                    strip.heapMb,
                    strip.workersBusy,
                    strip.workersTotal,
                ),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // KMK (r107): wrap toggle and text size
            TextAction("↵", "Wrap lines", selected = wrap) {
                wrap = !wrap
                diagPrefs(context).edit { putBoolean(FLOAT_WRAP, wrap) }
            }
            TextAction("A−", "Smaller text", selected = false) { setTextSize(textSp - TEXT_SP_STEP) }
            TextAction("A+", "Bigger text", selected = false) { setTextSize(textSp + TEXT_SP_STEP) }
            SmallAction(Icons.Outlined.Flag, "Mark") { diag.mark(null) }
            SmallAction(Icons.Outlined.KeyboardArrowDown, "Collapse") { FloatingLogState.collapsed.value = true }
            SmallAction(Icons.Outlined.Close, "Close") { toggleFloating(context, false) }
        }
        LiveGraph(window = window, compact = true, modifier = Modifier.fillMaxWidth())
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            MiniChip("All", tagFilter == null && !problemsOnly) {
                FloatingLogState.tagFilter.value = null
                problemsOnly = false
                FloatingLogState.paused.value = false
            }
            topTags.forEach { tag ->
                MiniChip(tag, tagFilter == tag) {
                    FloatingLogState.tagFilter.value = if (tagFilter == tag) null else tag
                    FloatingLogState.paused.value = false
                }
            }
            MiniChip("Problems", problemsOnly, color = DiagColors.problem) {
                problemsOnly = !problemsOnly
                FloatingLogState.paused.value = false
            }
            if (paused) {
                Text(
                    "paused · touch to resume",
                    fontSize = 10.sp,
                    color = DiagColors.attention,
                    modifier = Modifier.clickable { FloatingLogState.paused.value = false },
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // KMK (r107): wrapped = rows as wide as the window (Text reflows when it is resized); not wrapped = the
            // list is measured unbounded in width and scrolls sideways as one block
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (wrap) {
                            Modifier
                        } else {
                            // KMK (r107) fix: as wide as the widest row seen (never narrower as rows scroll by),
                            // so the sideways scroll doesn't jump
                            Modifier
                                .horizontalScroll(sideScroll)
                                .widthIn(min = with(density) { widest.toDp() })
                        },
                    ),
            ) {
                items(tail.size, key = { i -> "${tail[i].time}_$i" }) { i ->
                    // KMK (r107) fix: not wrapped, the widest row seen so far sets the list's width
                    Box(modifier = if (wrap) Modifier else Modifier.onSizeChanged { if (it.width > widest) widest = it.width }) {
                        TailRow(tail[i], wrap = wrap, narrow = narrow, textSp = textSp)
                    }
                }
            }
            // Resize handle
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .pointerInput(parent, safe) {
                        detectDragGestures(onDragEnd = { persist() }) { change, drag ->
                            change.consume()
                            val r = rect
                            rect = clamp(intArrayOf(r[0], r[1], r[2] + drag.x.roundToInt(), r[3] + drag.y.roundToInt()))
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
            if (recording != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(DiagColors.problem),
                )
            }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp)) }
}

/** KMK (r107): a 28 dp text button in the title bar (↵ · A− · A+); [selected] tints it like the mockup's "on" state */
@Composable
private fun TextAction(text: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun MiniChip(
    label: String,
    selected: Boolean,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * KMK (r107): one log line. The time and "level tag" are split off the text (exactly `AppLog.format()`); when
 * [narrow] they sit on their own line above the message. With [wrap] the text wraps to the row width (reflowing
 * as the window is resized), else it stays on one line (the list scrolls sideways). The text is never cut: what
 * the row shows is the whole line.
 */
@Composable
private fun TailRow(line: LogEntry, wrap: Boolean, narrow: Boolean, textSp: Float) {
    val color = when {
        line.isMark -> MaterialTheme.colorScheme.primary
        line.level == 'E' -> DiagColors.problem
        line.level == 'W' -> DiagColors.attention
        line.tag.startsWith("probe:") -> DiagColors.probe
        line.level == 'V' -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        line.level == 'D' -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val timeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val levelColor = when (line.level) {
        'E' -> DiagColors.problem
        'W' -> DiagColors.attention
        'I' -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = line.text
    val time = text.substringBefore(' ')
    val head = "$time ${line.level} ${line.tag} "
    // Fallback: a line that doesn't have the usual shape is shown whole, in one piece
    val split = text.startsWith(head)
    val message = if (split) text.substring(head.length) else text
    val headText = buildAnnotatedString {
        withStyle(SpanStyle(color = timeColor)) { append(time) }
        append(" ")
        withStyle(SpanStyle(color = levelColor)) { append("${line.level} ${line.tag}") }
    }
    val oneLine = buildAnnotatedString {
        if (split) {
            append(headText)
            append(" ")
        }
        withStyle(SpanStyle(color = color)) { append(message) }
    }
    val fontSize = textSp.sp
    val lineHeight = (textSp * 1.25f).sp
    val weight = if (line.isMark) FontWeight.Bold else FontWeight.Normal
    // KMK (r107) fix: the error / mark bar is drawn behind the row: a row sized by its intrinsic height measured each
    // text on one unwrapped line per paragraph, so a wrapped message (a stack trace's long lines) was cut short
    val bar = line.level == 'E' || line.isMark
    Row(
        modifier = Modifier
            .then(if (wrap) Modifier.fillMaxWidth() else Modifier)
            .then(
                if (bar) {
                    Modifier
                        .drawBehind { drawRect(color, topLeft = Offset.Zero, size = Size(2.dp.toPx(), size.height)) }
                        .padding(start = 5.dp)
                } else {
                    Modifier
                },
            ),
    ) {
        if (narrow && split) {
            Column {
                Text(
                    headText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    message,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = color,
                    fontWeight = weight,
                    softWrap = wrap,
                    maxLines = if (wrap) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Clip,
                )
            }
        } else {
            Text(
                oneLine,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = weight,
                softWrap = wrap,
                maxLines = if (wrap) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** The 52 dp bubble: bug icon, the W/E count since collapsed, a red ring while recording; snaps to the nearest edge. */
@Composable
private fun FloatingBubble(parent: IntSize) {
    val diag = remember { Diag.get() }
    val density = LocalDensity.current
    val sizePx = with(density) { 52.dp.roundToPx() }
    val margin = with(density) { 12.dp.roundToPx() }
    // KMK (r126): clear of the bars, the cutout and the keyboard, like the window
    val safe = rememberSafeInsets()
    val minX = safe.left + margin
    // KMK (r132): room for the counts inside the capsule
    val maxX = (parent.width - safe.right - sizePx - with(density) { 44.dp.roundToPx() } - margin).coerceAtLeast(minX)
    // KMK (r133): it can go up to just under the status bar
    val minY = safe.top + margin / 2
    val maxY = (parent.height - safe.bottom - sizePx - margin).coerceAtLeast(minY)
    val errors by FloatingLogState.errorsSinceSeen.collectAsState()
    val warnings by FloatingLogState.warningsSinceSeen.collectAsState()
    val recording by diag.recording.collectAsState()
    val saved = FloatingLogState.rect.value
    // KMK (r131): where it was left is kept (it used to jump back to the edge whenever the bars showed or hid)
    var pos by remember {
        mutableStateOf(
            bubblePos ?: IntOffset(
                maxX,
                (saved?.getOrNull(1) ?: (parent.height * 2 / 3)).coerceIn(minY, maxY),
            ),
        )
    }
    val shown = IntOffset(pos.x.coerceIn(minX, maxX), pos.y.coerceIn(minY, maxY))
    var play by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        play++
    }
    // KMK (r132): one glass capsule: the live Logs icon (the recording ring around it) and the counts inside, amber
    // for warnings and red for errors (99+), instead of a badge stuck on the corner
    val capsule = androidx.compose.foundation.shape.RoundedCornerShape(27.dp)
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset { shown }
            .height(54.dp)
            .shadow(8.dp, capsule)
            .clip(capsule)
            .liquidGlass(capsule, Color(0xFF14161E))
            .background(Color(0xFF14161E).copy(alpha = 0.45f))
            .pointerInput(minX, minY, maxX, maxY) {
                // KMK (r131): anywhere on the screen (no snapping to a side)
                detectDragGestures(onDragEnd = { bubblePos = pos }) { change, drag ->
                    change.consume()
                    val from = IntOffset(pos.x.coerceIn(minX, maxX), pos.y.coerceIn(minY, maxY))
                    pos = IntOffset(
                        (from.x + drag.x.roundToInt()).coerceIn(minX, maxX),
                        (from.y + drag.y.roundToInt()).coerceIn(minY, maxY),
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    play++
                    FloatingLogState.errorsSinceSeen.value = 0
                    FloatingLogState.warningsSinceSeen.value = 0
                    FloatingLogState.collapsed.value = false
                }
            }
            .padding(horizontal = 9.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .then(if (recording != null) Modifier.border(3.dp, DiagColors.problem, CircleShape) else Modifier),
        ) {
            // KMK (r131): the animated Logs icon from More
            eu.kanade.presentation.components.LiveIcon(
                kind = eu.kanade.presentation.components.LiveIconKind.Logs,
                play = play,
                tint = Color.White,
                size = 24.dp,
            )
        }
        if (warnings > 0) CountPill(warnings, Color(0xFFF2C94C), Color(0xFF2A2105))
        if (errors > 0) CountPill(errors, DiagColors.problem, Color.White)
    }
}

@Composable
private fun CountPill(count: Int, color: Color, text: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 5.dp)
            .height(22.dp)
            .widthIn(min = 22.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(11.dp))
            .background(color.copy(alpha = 0.92f))
            .padding(horizontal = 6.dp),
    ) {
        Text(
            text = if (count > 99) "99+" else "$count",
            color = text,
            fontSize = 11.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}

/** KMK (r131): where the bubble was left, while the app runs */
private var bubblePos: IntOffset? = null

private fun rectKey(landscape: Boolean) = if (landscape) DiagKeys.FLOAT_RECT_LANDSCAPE else DiagKeys.FLOAT_RECT_PORTRAIT

private fun loadRect(context: Context, landscape: Boolean): IntArray? {
    val raw = diagPrefs(context).getString(rectKey(landscape), null) ?: return null
    val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
    return parts.takeIf { it.size == 4 }?.toIntArray()
}

private fun saveRect(context: Context, landscape: Boolean, rect: IntArray) {
    diagPrefs(context).edit { putString(rectKey(landscape), rect.joinToString(",")) }
}

/** KMK (r126): the screen edges the floating window and bubble must stay clear of, in px */
private data class SafeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Composable
private fun rememberSafeInsets(): SafeInsets {
    val density = LocalDensity.current
    val insets = androidx.compose.foundation.layout.WindowInsets.safeDrawing
        .union(androidx.compose.foundation.layout.WindowInsets.ime)
    return SafeInsets(
        left = insets.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr),
        top = insets.getTop(density),
        right = insets.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr),
        bottom = insets.getBottom(density),
    )
}
