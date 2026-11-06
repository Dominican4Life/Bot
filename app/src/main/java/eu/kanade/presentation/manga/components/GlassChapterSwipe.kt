package eu.kanade.presentation.manga.components

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min

/**
 * KMK (r132, r133): the liquid-glass chapter swipe (idea J2). r133: clear glass with a lit rim, a later trigger
 * point (about a third of the row, so the stretch shows) and the pill melts back into the bar when the row returns.
 * While the row is swiped, it becomes a glass bar; a piece stays
 * stuck to the edge the swipe started from and a thick waist of glass stretches between them, narrowing as you pull.
 * At the trigger point it lets go: both ends are round straight away and ease back into their own side (no thread, no
 * tips); the piece on the edge swells into the gold action pill (Mark as read, Bookmark …). When the row springs back
 * the two melt together again.
 *
 * The merge/split is a blur + alpha threshold on the drawn shapes (Android 12+); older Android draws the same shapes
 * without the merge. [dx] is how far the row is moved (+ = to the right, the start action).
 */
@Composable
fun BoxScope.GlassSwipeLayer(
    dx: () -> Float,
    thresholdPx: Float,
    startIcon: ImageVector?,
    startLabel: String?,
    endIcon: ImageVector?,
    endLabel: String?,
) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(Size.Zero) }
    // Latched once the swipe crossed the trigger point, until the row is back home
    val latch = remember { BooleanArray(1) }
    // KMK (r133): the last pull, to know when the row is springing back (the pill then melts back into the bar)
    val last = remember { FloatArray(1) }
    val d = dx()
    if (abs(d) < 2f) latch[0] = false else if (abs(d) >= thresholdPx) latch[0] = true
    val snapped = latch[0]
    val returning = abs(d) < last[0] - 0.5f
    last[0] = abs(d)
    if (abs(d) < 1f) return
    val goo = with(density) { 8.dp.toPx() }
    // KMK (r133): clear glass (a light frost) instead of the dark grey bar
    val glass = Color(0xFFE6ECF7)
    val gold = tachiyomi.presentation.core.components.LocalGlassAccent.current
    val toRight = d > 0
    val pillMax = with(density) { 168.dp.toPx() }
    val inset = with(density) { 10.dp.toPx() }
    Canvas(
        modifier = Modifier
            .matchParentSize()
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .graphicsLayer {
                alpha = 0.22f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val blur = RenderEffect.createBlurEffect(goo, goo, Shader.TileMode.DECAL)
                    val threshold = RenderEffect.createColorFilterEffect(
                        ColorMatrixColorFilter(
                            floatArrayOf(
                                1f, 0f, 0f, 0f, 0f,
                                0f, 1f, 0f, 0f, 0f,
                                0f, 0f, 1f, 0f, 0f,
                                0f, 0f, 0f, 26f, -11f * 255f,
                            ),
                        ),
                        blur,
                    )
                    renderEffect = threshold.asComposeRenderEffect()
                }
            },
    ) {
        val w = this.size.width
        val h = this.size.height
        val pull = abs(d)
        // The bar (the row itself), from its moved edge to the far side
        if (toRight) drawBar(pull, w + goo * 2, h, glass) else drawBar(-goo * 2, w - pull, h, glass)
        val progress = (pull / thresholdPx).coerceIn(0f, 1f)
        if (!snapped) {
            // The stuck piece on the edge, bulging toward the bar, and the waist between them
            val anchorW = h * 0.6f + pull * 0.25f
            val waist = h * (0.92f - 0.42f * progress)
            if (toRight) {
                drawPiece(-goo * 2, anchorW, h, h * 0.96f, glass)
                drawPiece(anchorW - h * 0.4f, pull + h * 0.4f, h, waist, glass)
            } else {
                drawPiece(w - anchorW, w + goo * 2, h, h * 0.96f, glass)
                drawPiece(w - pull - h * 0.4f, w - anchorW + h * 0.4f, h, waist, glass)
            }
        } else {
            // Let go: the piece on the edge is the gold pill in the gap
            val pillW = min(pillMax, pull - inset * 2)
            if (pillW > h * 0.5f) {
                if (toRight) drawPiece(inset, inset + pillW, h, h * 0.72f, gold) else drawPiece(w - inset - pillW, w - inset, h, h * 0.72f, gold)
            }
            // KMK (r133): springing back, a glass neck joins the pill to the bar again so they melt together
            if (returning && pillW > h * 0.5f) {
                val neck = h * (0.35f + 0.5f * (1f - progress))
                if (toRight) drawPiece(inset + pillW - h * 0.4f, pull + h * 0.4f, h, neck, glass) else drawPiece(w - pull - h * 0.4f, w - inset - pillW + h * 0.4f, h, neck, glass)
            }
        }
    }
    // KMK (r133): a lit rim along the bar's top and bottom edges (the glass catching the light)
    Canvas(modifier = Modifier.matchParentSize()) {
        val w = this.size.width
        val h = this.size.height
        val pull = abs(d)
        val (from, to) = if (toRight) pull to w else 0f to (w - pull)
        if (to - from > 1f) {
            val px = with(density) { 1.dp.toPx() }
            drawRect(Color.White.copy(alpha = 0.55f), topLeft = Offset(from, 0f), size = Size(to - from, px))
            drawRect(Color.White.copy(alpha = 0.18f), topLeft = Offset(from, h - px), size = Size(to - from, px))
        }
    }
    // The pill's icon and name (not part of the merging layer)
    if (snapped) {
        val pull = abs(d)
        val pillW = min(pillMax, pull - inset * 2)
        val icon = if (toRight) startIcon else endIcon
        val label = if (toRight) startLabel else endLabel
        if (pillW > with(density) { 96.dp.toPx() } && icon != null) {
            val wDp = with(density) { pillW.toDp() }
            Row(
                modifier = Modifier
                    .align(if (toRight) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(start = if (toRight) 10.dp else 0.dp, end = if (toRight) 0.dp else 10.dp)
                    .width(wDp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tachiyomi.presentation.core.components.onGlassAccent(gold), modifier = Modifier.size(18.dp))
                if (label != null && pillW > with(density) { 140.dp.toPx() }) {
                    Text(
                        text = label,
                        color = tachiyomi.presentation.core.components.onGlassAccent(gold),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawBar(left: Float, right: Float, h: Float, color: Color) {
    if (right <= left) return
    drawRect(color, topLeft = Offset(left, 0f), size = Size(right - left, h))
}

/** A rounded piece from [left] to [right], [height] tall, centred on the row */
private fun DrawScope.drawPiece(left: Float, right: Float, rowH: Float, height: Float, color: Color) {
    if (right <= left) return
    val top = (rowH - height) / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(right - left, height),
        cornerRadius = CornerRadius(height / 2f, height / 2f),
    )
}
