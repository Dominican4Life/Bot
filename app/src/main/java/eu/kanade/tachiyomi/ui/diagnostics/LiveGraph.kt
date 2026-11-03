package eu.kanade.tachiyomi.ui.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.diagnostics.MarkerKind
import eu.kanade.tachiyomi.diagnostics.Resource
import eu.kanade.tachiyomi.diagnostics.SampleWindow
import java.util.Locale
import kotlin.math.abs

/**
 * KMK (r97): The Option D graph (ph-01): a stacked area per [SampleWindow] series, a 4×5 grid, the top-of-scale
 * label, the dashed overlay (upload, the trim line), a marker row above the plot and dotted marker lines through it,
 * an optional cursor line, and a smooth one-step scroll between ticks.
 *
 * Contract §8 rule 1: nothing is allocated per frame. [GraphCache] keeps one [Path] per series and rebuilds them
 * only when the window instance or the plot size changes (once per tick); the frame loop only moves a float from
 * 0 → 1 over `stepMs` and `onDraw` translates the cached paths by it. Labels are measured once per window.
 * [compact] is the floating window's 56 dp strip: no labels, no marker row.
 */
@Composable
internal fun LiveGraph(
    window: SampleWindow,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    cursorTimeMs: Long? = null,
    onTap: ((Long) -> Unit)? = null,
) {
    val cache = remember { GraphCache() }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val latest by rememberUpdatedState(window)
    val tapHandler by rememberUpdatedState(onTap)

    // KMK (r111): smooth motion. Up to r110 a new window restarted a 0 → 1 slide that started a frame late and stopped
    // dead when the next tick was late (a pause, then a jump), only fine windows slid at all, and the scale's top
    // snapped to its new value each tick so the whole shape jumped up or down. Now one frame loop moves [motion]:
    // each new window adds the steps it advanced to the pending shift (what's left of the last slide is carried over,
    // so early or late ticks never jump), the shift drains at one step per stepMs, and the shown top of the scale
    // eases towards the window's top. The loop stops when both have settled, so an idle graph costs no frames.
    val motion = remember { GraphMotion() }
    LaunchedEffect(window) {
        motion.arrive(window)
        var last = withFrameNanos { it }
        while (!motion.settled()) {
            val now = withFrameNanos { it }
            motion.advance((now - last) / 1_000_000f)
            last = now
        }
    }

    val labelStyle = remember { TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    val labelColor = Color(0xFF9AA3B2)
    // Measured once per window (once per tick), never per frame
    val scaleLabels: List<TextLayoutResult> = remember(window.topOfScale, window.resource, compact) {
        if (compact) {
            emptyList()
        } else {
            listOf(
                textMeasurer.measure(formatScale(window.topOfScale, window.resource, withUnit = true), labelStyle),
                textMeasurer.measure(formatScale(window.topOfScale / 2f, window.resource, false), labelStyle),
                textMeasurer.measure("0", labelStyle),
            )
        }
    }
    val timeLabels: List<TextLayoutResult> = remember(window.startMs, window.endMs, compact) {
        if (compact || window.endMs <= window.startMs) {
            emptyList()
        } else {
            val span = window.endMs - window.startMs
            val coarse = !window.window.isFine
            listOf(0.25f, 0.5f, 0.75f).map { f ->
                val t = window.startMs + (span * f).toLong()
                textMeasurer.measure(if (coarse) formatHm(t) else formatHms(t), labelStyle)
            }
        }
    }

    val gutterRight = with(density) { if (compact) 0f else 52.dp.toPx() }
    val gutterBottom = with(density) { if (compact) 0f else 18.dp.toPx() }
    val markerRow = with(density) { if (compact) 0f else 22.dp.toPx() }
    val dots = remember { PathEffect.dashPathEffect(floatArrayOf(3f, 6f)) }
    val stroke = remember { Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 56.dp else 260.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val handler = tapHandler ?: return@detectTapGestures
                    val win = latest
                    val plotW = cache.w
                    if (plotW <= 0f || win.stepMs <= 0L) return@detectTapGestures
                    val points = cache.points
                    if (points < 2) return@detectTapGestures
                    val index = ((offset.x / plotW) * (points - 1)).toInt().coerceIn(0, points - 1)
                    handler(win.startMs + index * win.stepMs)
                }
            },
    ) {
        val plotW = size.width - gutterRight
        val plotH = size.height - gutterBottom - markerRow
        val plotTop = markerRow
        cache.ensure(window, plotW, plotH)
        // KMK (r111): the pending shift in steps → pixels; the paths were built at the window's own top and are
        // stretched to the shown (easing) top around the plot's bottom
        val shift = motion.shiftSteps * cache.stepPx
        val stretch = if (motion.shownTop > 0f && cache.builtTop > 0f) cache.builtTop / motion.shownTop else 1f

        // Grid: 4 horizontal, 5 vertical lines
        val grid = Color(0x22FFFFFF)
        for (i in 0..4) {
            val y = plotTop + plotH * i / 4f
            drawLine(grid, Offset(0f, y), Offset(plotW, y), strokeWidth = 1f)
        }
        for (i in 0..5) {
            val x = plotW * i / 5f
            drawLine(grid, Offset(x, plotTop), Offset(x, plotTop + plotH), strokeWidth = 1f)
        }

        clipRect(left = 0f, top = plotTop, right = plotW, bottom = plotTop + plotH) {
            translate(left = shift, top = plotTop) {
                val series = window.series
                scale(scaleX = 1f, scaleY = stretch, pivot = Offset(0f, plotH)) {
                    for (i in series.indices) {
                        val color = DiagColors.fixed(series[i].color)
                        drawPath(cache.paths[i], color, alpha = 0.55f)
                        drawPath(cache.lines[i], color, style = cache.lineStroke)
                    }
                    if (window.overlay != null) {
                        drawPath(cache.overlay, DiagColors.fixed(window.overlay.color), style = stroke)
                    }
                }
                // Dotted marker lines
                for (i in 0 until cache.markerCount) {
                    val x = cache.markerX[i]
                    drawLine(
                        DiagColors.fixed(cache.markerKinds[i]!!.color).copy(alpha = 0.7f),
                        Offset(x, 0f),
                        Offset(x, plotH),
                        strokeWidth = 2f,
                        pathEffect = dots,
                    )
                }
            }
            // Cursor
            if (cursorTimeMs != null && window.stepMs > 0L) {
                val span = (window.endMs - window.startMs).coerceAtLeast(1L)
                val x = (cursorTimeMs - window.startMs).toFloat() / span * plotW
                if (x in 0f..plotW) {
                    drawLine(Color.White, Offset(x, plotTop), Offset(x, plotTop + plotH), strokeWidth = 2f)
                }
            }
        }
        // The right edge fades the newest sample in (the still's "live" edge)
        drawRect(
            Color(0x14FFFFFF),
            topLeft = Offset(plotW - plotW * 0.025f, plotTop),
            size = Size(plotW * 0.025f, plotH),
        )

        if (!compact) {
            // Marker row above the plot
            translate(left = shift) {
                for (i in 0 until cache.markerCount) {
                    val x = cache.markerX[i]
                    if (x < 0f || x > plotW) continue
                    drawMarkerGlyph(cache.markerKinds[i]!!, x, markerRow / 2f)
                }
            }
            // Scale labels on the right
            scaleLabels.forEachIndexed { i, label ->
                val y = when (i) {
                    0 -> plotTop
                    1 -> plotTop + plotH / 2f - label.size.height / 2f
                    else -> plotTop + plotH - label.size.height
                }
                drawText(label, color = labelColor, topLeft = Offset(plotW + 8f, y))
            }
            // Time labels under the plot
            timeLabels.forEachIndexed { i, label ->
                val x = plotW * (0.25f + 0.25f * i) - label.size.width / 2f
                drawText(label, color = labelColor, topLeft = Offset(x, plotTop + plotH + 2f))
            }
        }
    }
}

private fun DrawScope.drawMarkerGlyph(kind: MarkerKind, x: Float, cy: Float) {
    val color = DiagColors.fixed(kind.color)
    when (kind) {
        MarkerKind.PROBE -> rotate(45f, Offset(x, cy)) {
            drawRect(color, topLeft = Offset(x - 5f, cy - 5f), size = Size(10f, 10f))
        }
        MarkerKind.MARK, MarkerKind.RECORD -> {
            drawLine(color, Offset(x, cy - 8f), Offset(x, cy + 8f), strokeWidth = 2f)
            drawRect(color, topLeft = Offset(x, cy - 8f), size = Size(8f, 6f))
        }
        else -> {
            drawCircle(color, radius = 7f, center = Offset(x, cy))
            if (kind.isIncident) drawCircle(Color.Black, radius = 2.5f, center = Offset(x, cy))
        }
    }
}

/** "4.0 MB/s", "512 MB", "64", "60 q/s" */
internal fun formatScale(value: Float, resource: Resource, withUnit: Boolean): String {
    val number = when (resource) {
        Resource.NETWORK, Resource.PLAYER -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.0f", value)
    }
    return if (withUnit) "$number ${resource.unit}" else number
}

/**
 * The paths of one window at one size. Everything is reused: `ensure` returns at once while the window instance
 * and the size are the ones it was built for, so a frame never allocates.
 */
private class GraphCache {
    var window: SampleWindow? = null
        private set
    var w = 0f
        private set
    var h = 0f
        private set
    var points = 0
        private set
    var stepPx = 0f
        private set

    /** KMK (r111): the top of the scale the paths were built at (the draw stretches them to the shown top) */
    var builtTop = 1f
        private set
    val paths = ArrayList<Path>()
    val lines = ArrayList<Path>()
    val overlay = Path()
    val lineStroke = Stroke(width = 1.5f)
    var markerX = FloatArray(16)
        private set
    var markerKinds = arrayOfNulls<MarkerKind>(16)
        private set
    var markerCount = 0
        private set
    private var lower = FloatArray(0)
    private var upper = FloatArray(0)

    // KMK (r111): y positions and their tangents for the curves (reused, grown when a window is longer)
    private var ys = FloatArray(0)
    private var tangents = FloatArray(0)

    fun ensure(win: SampleWindow, width: Float, height: Float) {
        if (win === window && abs(width - w) < 0.5f && abs(height - h) < 0.5f) return
        window = win
        w = width
        h = height
        rebuild(win)
    }

    private fun rebuild(win: SampleWindow) {
        val n = win.series.firstOrNull()?.values?.size ?: win.overlay?.values?.size ?: 0
        points = n
        stepPx = if (n > 1) w / (n - 1) else w
        val top = win.topOfScale.takeIf { it > 0f } ?: 1f
        builtTop = top
        if (lower.size < n) {
            lower = FloatArray(n)
            upper = FloatArray(n)
            ys = FloatArray(n)
            tangents = FloatArray(n)
        }
        lower.fill(0f, 0, n)
        while (paths.size < win.series.size) {
            paths.add(Path())
            lines.add(Path())
        }
        // KMK (r111): each band's edges are smooth curves through the samples (monotone, so a curve never dips
        // below 0 or rises above its neighbours), and they reach two steps past the left edge so the slide in from
        // the right never shows an empty strip on the left
        val lead = 2f * stepPx
        for (s in win.series.indices) {
            val values = win.series[s].values
            val area = paths[s]
            val line = lines[s]
            area.rewind()
            line.rewind()
            if (n == 0) continue
            for (i in 0 until n) {
                val v = values.getOrElse(i) { 0f }
                upper[i] = lower[i] + (if (v.isNaN()) 0f else v)
            }
            // Top edge left → right
            for (i in 0 until n) ys[i] = yOf(upper[i], top)
            slopes(n)
            area.moveTo(-lead, ys[0])
            area.lineTo(0f, ys[0])
            line.moveTo(-lead, ys[0])
            line.lineTo(0f, ys[0])
            curveRight(area, n)
            curveRight(line, n)
            // Bottom edge right → left along the band below
            for (i in 0 until n) ys[i] = yOf(lower[i], top)
            slopes(n)
            area.lineTo((n - 1) * stepPx, ys[n - 1])
            curveLeft(area, n)
            area.lineTo(-lead, ys[0])
            area.close()
            val swap = lower
            lower = upper
            upper = swap
        }
        overlay.rewind()
        val ov = win.overlay
        if (ov != null && n > 0) {
            // Each run of samples without a gap is its own curve
            var i = 0
            while (i < n) {
                while (i < n && ov.values.getOrElse(i) { Float.NaN }.isNaN()) i++
                if (i >= n) break
                val from = i
                while (i < n && !ov.values.getOrElse(i) { Float.NaN }.isNaN()) i++
                val count = i - from
                for (k in 0 until count) ys[k] = yOf(ov.values[from + k], top)
                slopes(count)
                overlay.moveTo(from * stepPx, ys[0])
                for (k in 1 until count) segment(overlay, (from + k - 1) * stepPx, k - 1, k)
            }
        }
        // Markers inside the window → x positions
        val span = (win.endMs - win.startMs).coerceAtLeast(1L).toFloat()
        var count = 0
        for (m in win.markers) {
            if (m.timeMs < win.startMs || m.timeMs > win.endMs) continue
            if (count == markerX.size) {
                markerX = markerX.copyOf(markerX.size * 2)
                markerKinds = markerKinds.copyOf(markerKinds.size * 2)
            }
            markerX[count] = (m.timeMs - win.startMs) / span * w
            markerKinds[count] = m.kind
            count++
        }
        markerCount = count
    }

    // KMK (r111): not capped at the top any more: the draw stretches the paths to the shown top, which eases towards
    // a new top, and the plot's clip cuts what's above it meanwhile
    private fun yOf(value: Float, top: Float): Float = h - (value / top).coerceAtLeast(0f) * h

    /**
     * KMK (r111): monotone cubic tangents (Fritsch–Carlson) of ys[0 until n], in pixels of y per step: a peak or a
     * flat part gets a flat tangent, so the curve never overshoots its samples
     */
    private fun slopes(n: Int) {
        if (n < 2) {
            if (n == 1) tangents[0] = 0f
            return
        }
        for (i in 0 until n) {
            val before = if (i > 0) ys[i] - ys[i - 1] else ys[1] - ys[0]
            val after = if (i < n - 1) ys[i + 1] - ys[i] else ys[n - 1] - ys[n - 2]
            tangents[i] = if (before * after <= 0f) 0f else (before + after) / 2f
        }
        for (i in 0 until n - 1) {
            val d = ys[i + 1] - ys[i]
            if (d == 0f) {
                tangents[i] = 0f
                tangents[i + 1] = 0f
                continue
            }
            val a = tangents[i] / d
            val b = tangents[i + 1] / d
            val sum = a * a + b * b
            if (sum > 9f) {
                val t = 3f / kotlin.math.sqrt(sum)
                tangents[i] = t * a * d
                tangents[i + 1] = t * b * d
            }
        }
    }

    /** One Hermite segment from sample [a] (at x0) to sample [b] (one step right), as a cubic Bézier */
    private fun segment(path: Path, x0: Float, a: Int, b: Int) {
        val third = stepPx / 3f
        path.cubicTo(
            x0 + third, ys[a] + tangents[a] / 3f,
            x0 + 2f * third, ys[b] - tangents[b] / 3f,
            x0 + stepPx, ys[b],
        )
    }

    /** The curve through ys[1 until n] from the path's current point at sample 0 */
    private fun curveRight(path: Path, n: Int) {
        for (i in 1 until n) segment(path, (i - 1) * stepPx, i - 1, i)
    }

    /** The curve back through ys from sample n-1 to sample 0 (the band's lower edge, right → left) */
    private fun curveLeft(path: Path, n: Int) {
        val third = stepPx / 3f
        for (i in n - 1 downTo 1) {
            val x1 = i * stepPx
            path.cubicTo(
                x1 - third, ys[i] - tangents[i] / 3f,
                x1 - 2f * third, ys[i - 1] + tangents[i - 1] / 3f,
                x1 - stepPx, ys[i - 1],
            )
        }
    }
}

/**
 * KMK (r111): the graph's motion between windows. [shiftSteps]: how many steps the paths are still drawn to the
 * right of their place (it drains at one step per stepMs); [shownTop]: the top of the scale as drawn, easing towards
 * the window's. Read in the draw only, so a frame redraws the Canvas without recomposing.
 */
private class GraphMotion {
    var shiftSteps by mutableFloatStateOf(0f)
        private set
    var shownTop by mutableFloatStateOf(0f)
        private set
    private var targetTop = 0f
    private var stepMs = 1_000f
    private var lastStart = Long.MIN_VALUE
    private var lastKey: Any? = null

    fun arrive(win: SampleWindow) {
        val top = win.topOfScale.takeIf { it > 0f } ?: 1f
        targetTop = top
        val key = win.resource to win.window
        if (key != lastKey || shownTop <= 0f || win.stepMs <= 0L) {
            // Another graph (or the first one): no slide, no easing
            lastKey = key
            shownTop = top
            shiftSteps = 0f
            lastStart = win.startMs
            stepMs = win.stepMs.coerceAtLeast(1L).toFloat()
            return
        }
        stepMs = win.stepMs.toFloat()
        val advanced = ((win.startMs - lastStart).toFloat() / stepMs).coerceAtLeast(0f)
        lastStart = win.startMs
        // What's left of the last slide is kept; a long gap (the page was away) doesn't slide at all
        shiftSteps = if (advanced > 3f) 0f else (shiftSteps + advanced).coerceAtMost(2f)
    }

    fun advance(dtMs: Float) {
        if (shiftSteps > 0f) {
            // Drains a little faster when more than a step is pending, so it catches up instead of lagging
            val speed = if (shiftSteps > 1f) 1.5f else 1f
            shiftSteps = (shiftSteps - speed * dtMs / stepMs).coerceAtLeast(0f)
        }
        if (shownTop != targetTop) {
            val k = 1f - kotlin.math.exp(-dtMs / TOP_EASE_MS)
            val next = shownTop + (targetTop - shownTop) * k
            shownTop = if (abs(next - targetTop) <= targetTop * 0.002f) targetTop else next
        }
    }

    fun settled(): Boolean = shiftSteps <= 0f && shownTop == targetTop

    private companion object {
        /** How fast the shown top follows a new top (about 3 × this to settle) */
        const val TOP_EASE_MS = 180f
    }
}
