package eu.kanade.presentation.browse.components

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * KMK (r127): real glass, after Apple's Liquid Glass. A glass surface shows what is behind it, blurred and a little
 * more colourful, bends it slightly at its rounded edge like the thick rim of a lens, and catches light: a bright line
 * along the top edge, a softer one round the rest, and a diagonal sheen. Only a thin tint is laid on top so the text
 * stays readable.
 *
 * Two sources for "what is behind":
 *  - [LocalGlassBackdrop]: a classic View (the reader's pages). [GlassBackdrop] keeps a small live copy of it in a
 *    RenderNode with a blur (Android 12+), re-recorded only while some glass is on screen.
 *  - [LocalGlassHaze]: Compose content marked with `hazeSource` (the browse covers), blurred by Haze.
 *
 * With neither (or on Android 11 and older, Solid, Reduce transparency or high contrast) the surface falls back to
 * the tinted look of r125/r126, still with the light on its edges.
 */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }
val LocalGlassHaze = staticCompositionLocalOf<HazeState?> { null }

class GlassBackdrop {
    private var source: View? = null
    private var users = 0
    private val listener = ViewTreeObserver.OnPreDrawListener {
        record()
        true
    }
    private var nodeHolder: Any? = null

    /** The scale of the copy: a third of the size is plenty under a blur and keeps it cheap */
    val scale = 1f / 3f

    val ready: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && source != null

    fun attach(view: View) {
        if (source === view) return
        detach()
        source = view
        if (users > 0) view.viewTreeObserver.addOnPreDrawListener(listener)
    }

    fun detach() {
        source?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        source = null
    }

    internal fun acquire() {
        users++
        if (users == 1) {
            source?.viewTreeObserver?.addOnPreDrawListener(listener)
            source?.invalidate()
        }
    }

    internal fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) source?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
    }

    /** Where the source sits in its window, in px */
    internal fun sourceInWindow(): Offset {
        val view = source ?: return Offset.Zero
        val at = IntArray(2)
        view.getLocationInWindow(at)
        return Offset(at[0].toFloat(), at[1].toFloat())
    }

    @RequiresApi(Build.VERSION_CODES.S)
    internal fun node(): RenderNode {
        (nodeHolder as? RenderNode)?.let { return it }
        return RenderNode("glassBackdrop").also { node ->
            val saturate = ColorMatrix().apply { setSaturation(1.5f) }
            node.setRenderEffect(
                RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate)),
                    RenderEffect.createBlurEffect(9f, 9f, Shader.TileMode.CLAMP),
                ),
            )
            nodeHolder = node
        }
    }

    private fun record() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val view = source ?: return
        if (view.width <= 0 || view.height <= 0) return
        runCatching {
            val node = node()
            node.setPosition(0, 0, (view.width * scale).toInt() + 1, (view.height * scale).toInt() + 1)
            val canvas = node.beginRecording()
            try {
                canvas.scale(scale, scale)
                view.draw(canvas)
            } finally {
                node.endRecording()
            }
        }
    }
}

/** Keep [backdrop] copying [view] while this is composed */
@Composable
fun GlassBackdropSource(backdrop: GlassBackdrop, view: View?) {
    DisposableEffect(backdrop, view) {
        if (view != null) backdrop.attach(view)
        onDispose { backdrop.detach() }
    }
}

/** True when the glass should be see-through (not Solid, not reduced, not high contrast) */
internal fun glassSeeThrough(): Boolean =
    GlassStyle.material().get() != GlassStyle.SOLID && !GlassStyle.reduceTransparency().get() && !GlassStyle.highContrast()

/** The tint laid over a real backdrop: much lighter than the fallback, since the blur already does the work */
internal fun glassOverlay(base: Color): Color = when (GlassStyle.material().get()) {
    GlassStyle.CLEAR -> base.copy(alpha = 0.16f)
    else -> base.copy(alpha = (0.62f - GlassStyle.seeThrough().get() / 100f * 0.5f).coerceIn(0.18f, 0.6f))
}

/**
 * A glass surface in [shape] with its [base] colour. Draws the backdrop (when there is one), the tint and the light;
 * the content goes on top. Use instead of `clip + background`.
 */
fun Modifier.liquidGlass(shape: Shape, base: Color = Color(0xFF12141B)): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    val haze = LocalGlassHaze.current
    val density = LocalDensity.current
    val seeThrough = glassSeeThrough()
    val useBackdrop = seeThrough && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val useHaze = seeThrough && !useBackdrop && haze != null
    if (useBackdrop) {
        DisposableEffect(backdrop) {
            backdrop!!.acquire()
            onDispose { backdrop.release() }
        }
    }
    val position = remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val lens = with(density) { 7.dp.toPx() }
    val edge = with(density) { GlassStyle.edgeWidth().toPx() }
    var m: Modifier = Modifier
        .onGloballyPositioned {
            position.value = it.positionInWindow()
        }
    if (useHaze) {
        m = m.then(
            Modifier.hazeEffect(
                state = haze,
                style = HazeStyle(
                    backgroundColor = base,
                    tint = HazeTint(glassOverlay(base)),
                    blurRadius = 26.dp,
                    noiseFactor = 0f,
                ),
            ),
        )
    }
    m.then(
        Modifier.drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            if (useBackdrop && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                drawBackdrop(backdrop!!, outline, position.value, lens)
                drawOutline(outline, glassOverlay(base))
            } else if (!useHaze) {
                drawOutline(outline, GlassStyle.fill(base))
            }
            drawGlassLight(outline, edge)
        },
    )
}

@RequiresApi(Build.VERSION_CODES.S)
private fun DrawScope.drawBackdrop(backdrop: GlassBackdrop, outline: Outline, at: Offset, lens: Float) {
    val node = backdrop.node()
    val source = backdrop.sourceInWindow()
    val shift = Offset(source.x - at.x, source.y - at.y)
    val path = Path().apply { addOutline(outline) }
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        if (!native.isHardwareAccelerated) return@drawIntoCanvas
        // The page behind, blurred
        native.save()
        native.clipPath(path.asAndroidPath())
        native.translate(shift.x, shift.y)
        native.scale(1f / backdrop.scale, 1f / backdrop.scale)
        native.drawRenderNode(node)
        native.restore()
        // The lens rim: a thin band along the edge shows the backdrop a little magnified, as a thick glass edge would
        if (lens > 0f && size.minDimension > lens * 4) {
            val inner = Path().apply {
                addOutline(
                    shapeInset(outline, lens),
                )
            }
            val ring = Path().apply { op(path, inner, PathOperation.Difference) }
            native.save()
            native.clipPath(ring.asAndroidPath())
            val cx = size.width / 2f
            val cy = size.height / 2f
            native.translate(cx, cy)
            native.scale(1.06f, 1.06f)
            native.translate(-cx, -cy)
            native.translate(shift.x, shift.y)
            native.scale(1f / backdrop.scale, 1f / backdrop.scale)
            native.drawRenderNode(node)
            native.restore()
        }
    }
}

/** [outline] shrunk by [by] px on every side (rounded rectangles keep their shape) */
private fun DrawScope.shapeInset(outline: Outline, by: Float): Outline = when (outline) {
    is Outline.Rounded -> {
        val r = outline.roundRect
        Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(
                left = r.left + by,
                top = r.top + by,
                right = r.right - by,
                bottom = r.bottom - by,
                topLeftCornerRadius = shrink(r.topLeftCornerRadius, by),
                topRightCornerRadius = shrink(r.topRightCornerRadius, by),
                bottomRightCornerRadius = shrink(r.bottomRightCornerRadius, by),
                bottomLeftCornerRadius = shrink(r.bottomLeftCornerRadius, by),
            ),
        )
    }
    is Outline.Rectangle -> Outline.Rectangle(outline.rect.deflate(by))
    else -> Outline.Rectangle(androidx.compose.ui.geometry.Rect(Offset(by, by), Size(size.width - 2 * by, size.height - 2 * by)))
}

private fun shrink(r: androidx.compose.ui.geometry.CornerRadius, by: Float) =
    androidx.compose.ui.geometry.CornerRadius((r.x - by).coerceAtLeast(0f), (r.y - by).coerceAtLeast(0f))

/** The light on the glass: diagonal sheen, bright top edge fading down the sides, a faint bottom rim */
private fun DrawScope.drawGlassLight(outline: Outline, edge: Float) {
    val high = GlassStyle.highContrast()
    drawOutline(
        outline,
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.16f),
            0.35f to Color.White.copy(alpha = 0.03f),
            0.7f to Color.Transparent,
            1f to Color.White.copy(alpha = 0.06f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )
    if (high) {
        drawOutline(outline, Color.White, style = Stroke(edge))
        return
    }
    drawOutline(
        outline,
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.62f),
            0.18f to Color.White.copy(alpha = 0.22f),
            0.6f to Color.White.copy(alpha = 0.07f),
            1f to Color.White.copy(alpha = 0.20f),
        ),
        style = Stroke(edge * 1.2f),
    )
}
