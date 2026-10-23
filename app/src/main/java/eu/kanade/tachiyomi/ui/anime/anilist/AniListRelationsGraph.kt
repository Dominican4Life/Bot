package eu.kanade.tachiyomi.ui.anime.anilist

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.systemBarsPadding
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.max
import kotlin.math.min

private val NodeWidth = 150.dp
private val NodeHeight = 66.dp
private val ColumnGap = 120.dp
private val RowGap = 50.dp
private val DotSpacing = 20.dp

/** Where each anime sits in the diagram, in dp, left to right from prequels to sequels */
private class GraphLayout(
    val positions: Map<Int, Offset>,
    val width: Float,
    val height: Float,
)

/**
 * Hayase's layout (dagre, left to right): prequels on the left, sequels and side stories to the right, each title
 * placed right next to the one it's linked to, and lined up with its neighbors.
 */
private fun layoutGraph(graph: AniListGraph): GraphLayout {
    val ids = graph.nodes.map { it.id }
    if (ids.isEmpty()) return GraphLayout(emptyMap(), 1f, 1f)
    val preds = graph.edges.groupBy({ it.to }, { it.from })
    val succs = graph.edges.groupBy({ it.from }, { it.to })
    val rank = HashMap<Int, Int>().apply { ids.forEach { put(it, 0) } }
    // 1. Longest path, stopping on loops
    repeat(ids.size) {
        var changed = false
        graph.edges.forEach { edge ->
            val from = rank[edge.from] ?: return@forEach
            val to = rank[edge.to] ?: return@forEach
            if (to < from + 1 && from + 1 < ids.size) {
                rank[edge.to] = from + 1
                changed = true
            }
        }
        if (!changed) return@repeat
    }
    // 2. Tighten: pull each title next to the ones it's linked to (dagre's tight tree)
    repeat(8) {
        ids.forEach { id ->
            val p = preds[id].orEmpty().mapNotNull { rank[it] }
            val s = succs[id].orEmpty().mapNotNull { rank[it] }
            val low = (p.maxOrNull() ?: Int.MIN_VALUE / 2) + 1
            val high = (s.minOrNull() ?: Int.MAX_VALUE / 2) - 1
            rank[id] = when {
                p.isNotEmpty() -> low.coerceAtMost(maxOf(low, high))
                s.isNotEmpty() -> high
                else -> rank[id] ?: 0
            }
        }
    }
    val minRank = rank.values.minOrNull() ?: 0
    ids.forEach { rank[it] = (rank[it] ?: 0) - minRank }
    val columnCount = (rank.values.maxOrNull() ?: 0) + 1
    val columns = MutableList(columnCount) { mutableListOf<Int>() }
    ids.forEach { columns[rank[it] ?: 0] += it }
    val neighbors = HashMap<Int, MutableList<Int>>()
    graph.edges.forEach { edge ->
        neighbors.getOrPut(edge.from) { mutableListOf() } += edge.to
        neighbors.getOrPut(edge.to) { mutableListOf() } += edge.from
    }
    // 3. Order inside each column: next to the neighbors (barycenter, both ways a few times)
    val order = HashMap<Int, Float>()
    columns.forEach { column -> column.forEachIndexed { i, id -> order[id] = i.toFloat() } }
    fun sweep(range: IntProgression, side: Int) {
        for (c in range) {
            val column = columns[c]
            val sorted = column.sortedBy { id ->
                neighbors[id].orEmpty().filter { rank[it] == c + side }.mapNotNull { order[it] }
                    .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: order[id] ?: 0f
            }
            column.clear()
            column += sorted
            sorted.forEachIndexed { i, id -> order[id] = i.toFloat() }
        }
    }
    repeat(3) {
        sweep(1 until columnCount, -1)
        sweep(columnCount - 2 downTo 0, 1)
    }
    // 4. Heights: each title lined up with its neighbors, without overlapping
    val stepY = NodeHeight.value + RowGap.value
    val y = HashMap<Int, Float>()
    columns.forEach { column -> column.forEachIndexed { i, id -> y[id] = i * stepY } }
    fun place(c: Int, side: Int) {
        val column = columns[c]
        if (column.isEmpty()) return
        val wanted = column.map { id ->
            neighbors[id].orEmpty().filter { rank[it] == c + side }.mapNotNull { y[it] }
                .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: y[id] ?: 0f
        }
        val placed = FloatArray(column.size)
        column.indices.forEach { i ->
            placed[i] = if (i == 0) wanted[i] else maxOf(wanted[i], placed[i - 1] + stepY)
        }
        // Shift the column back toward where it wanted to be
        val shift = (wanted.sum() - placed.sum()) / column.size
        column.forEachIndexed { i, id -> y[id] = placed[i] + shift }
    }
    repeat(4) {
        for (c in 1 until columnCount) place(c, -1)
        for (c in columnCount - 2 downTo 0) place(c, 1)
    }
    val top = y.values.minOrNull() ?: 0f
    val stepX = NodeWidth.value + ColumnGap.value
    val positions = ids.associateWith { id -> Offset((rank[id] ?: 0) * stepX, (y[id] ?: 0f) - top) }
    val bottom = positions.values.maxOfOrNull { it.y } ?: 0f
    return GraphLayout(
        positions = positions,
        width = columnCount * stepX - ColumnGap.value,
        height = bottom + NodeHeight.value,
    )
}

/**
 * KMK: Hayase's relations diagram: a box you can drag around and pinch to zoom (dots behind that move with it),
 * every anime of the franchise linked by moving lines. Tapping a title switches the page to it.
 * Bottom left: zoom in, zoom out, fit, full window and refresh, like Hayase.
 */
@Composable
fun AniListRelationsGraph(
    graph: AniListGraph?,
    loading: Boolean,
    currentId: Int,
    onClickNode: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Takes all the space it's given (the tablet's right side) instead of a set height */
    fill: Boolean = false,
    height: androidx.compose.ui.unit.Dp = 420.dp,
    onRefresh: (() -> Unit)? = null,
    /** Inside the full-window view: the expand button closes it */
    onCloseFullWindow: (() -> Unit)? = null,
) {
    var fullWindow by remember { mutableStateOf(false) }
    if (fullWindow) {
        Dialog(
            onDismissRequest = { fullWindow = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AniListRelationsGraph(
                graph = graph,
                loading = loading,
                currentId = currentId,
                onClickNode = {
                    fullWindow = false
                    onClickNode(it)
                },
                fill = true,
                onRefresh = onRefresh,
                onCloseFullWindow = { fullWindow = false },
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(8.dp),
            )
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fill) Modifier.fillMaxSize() else Modifier)
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF050505),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(
            modifier = (if (fill) Modifier.fillMaxSize() else Modifier.height(height)).clipToBounds(),
        ) {
            if (graph == null || graph.nodes.size <= 1) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(
                        text = stringResource(
                            if (loading) KMR.strings.anilist_relations_loading else KMR.strings.anilist_no_relations,
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                return@BoxWithConstraints
            }
            val layout = remember(graph) { layoutGraph(graph) }
            val density = LocalDensity.current
            val boxWidthDp = maxWidth.value
            val boxHeightDp = maxHeight.value
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val fitScale = min(min(boxWidthDp / (layout.width + 40f), boxHeightDp / (layout.height + 40f)), 1.2f)
                .coerceAtLeast(0.05f)
            val fit: () -> Unit = {
                scale = fitScale
                offset = with(density) {
                    Offset(
                        ((boxWidthDp - layout.width * fitScale) / 2f).dp.toPx(),
                        ((boxHeightDp - layout.height * fitScale) / 2f).dp.toPx(),
                    )
                }
            }
            // Like Hayase when zoomed in: readable, around the title this page is about
            val focusCurrent: () -> Unit = {
                val target = maxOf(fitScale, 0.85f)
                val position = layout.positions[currentId]
                if (position == null || target == fitScale) {
                    fit()
                } else {
                    scale = target
                    offset = with(density) {
                        Offset(
                            (boxWidthDp / 2f - (position.x + NodeWidth.value / 2f) * target).dp.toPx(),
                            (boxHeightDp / 2f - (position.y + NodeHeight.value / 2f) * target).dp.toPx(),
                        )
                    }
                }
            }
            LaunchedEffect(layout, currentId, maxWidth, maxHeight) { focusCurrent() }
            val zoomBy: (Float) -> Unit = { factor ->
                val newScale = (scale * factor).coerceIn(0.05f, 2.5f)
                val center = with(density) { Offset(boxWidthDp.dp.toPx() / 2f, boxHeightDp.dp.toPx() / 2f) }
                offset = (offset - center) * (newScale / scale) + center
                scale = newScale
            }

            val transition = rememberInfiniteTransition(label = "relations")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 20f,
                animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
                label = "dash",
            )
            val lineColor = Color.White.copy(alpha = 0.35f)
            val currentColor = MaterialTheme.colorScheme.primary
            val textMeasurer = rememberTextMeasurer()

            // The dots behind, moving and growing with the diagram
            Canvas(modifier = Modifier.fillMaxSize()) {
                val spacing = DotSpacing.toPx() * scale
                if (spacing >= 4f) {
                    val radius = (1.dp.toPx() * scale).coerceIn(0.6f, 2.5f)
                    val startX = ((offset.x % spacing) + spacing) % spacing
                    val startY = ((offset.y % spacing) + spacing) % spacing
                    var x = startX
                    while (x < size.width) {
                        var yy = startY
                        while (yy < size.height) {
                            drawCircle(Color.White.copy(alpha = 0.12f), radius, Offset(x, yy))
                            yy += spacing
                        }
                        x += spacing
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(0.05f, 2.5f)
                            // Zoom around the fingers
                            val factor = newScale / scale
                            offset = (offset - centroid) * factor + centroid + pan
                            scale = newScale
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .size(layout.width.dp, layout.height.dp)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val nodeW = NodeWidth.toPx()
                        val nodeH = NodeHeight.toPx()
                        // The lines first, then the labels over them
                        val labels = mutableListOf<Pair<Offset, Pair<String, Color>>>()
                        graph.edges.forEach { edge ->
                            val from = layout.positions[edge.from] ?: return@forEach
                            val to = layout.positions[edge.to] ?: return@forEach
                            val start = Offset(from.x.dp.toPx() + nodeW, from.y.dp.toPx() + nodeH / 2f)
                            val end = Offset(to.x.dp.toPx(), to.y.dp.toPx() + nodeH / 2f)
                            val bend = max(60.dp.toPx(), kotlin.math.abs(end.x - start.x) / 2f)
                            val path = Path().apply {
                                moveTo(start.x, start.y)
                                cubicTo(start.x + bend, start.y, end.x - bend, end.y, end.x, end.y)
                            }
                            val touchesCurrent = edge.from == currentId || edge.to == currentId
                            val color = if (touchesCurrent) currentColor else lineColor
                            drawPath(
                                path = path,
                                color = color,
                                style = Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(5.dp.toPx(), 5.dp.toPx()),
                                        -phase.dp.toPx(),
                                    ),
                                ),
                            )
                            val middle = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                            labels += middle to (edge.label.replace('_', ' ').uppercase() to color)
                        }
                        labels.forEach { (middle, label) ->
                            val (text, color) = label
                            val measured = textMeasurer.measure(
                                text = text,
                                style = TextStyle(fontSize = 8.sp, color = color, fontWeight = FontWeight.Medium),
                            )
                            val pad = 3.dp.toPx()
                            drawRoundRect(
                                color = Color(0xFF141414),
                                topLeft = Offset(
                                    middle.x - measured.size.width / 2f - pad,
                                    middle.y - measured.size.height / 2f - pad / 2f,
                                ),
                                size = androidx.compose.ui.geometry.Size(
                                    measured.size.width + pad * 2,
                                    measured.size.height + pad,
                                ),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            )
                            drawText(
                                textLayoutResult = measured,
                                topLeft = Offset(
                                    middle.x - measured.size.width / 2f,
                                    middle.y - measured.size.height / 2f,
                                ),
                            )
                        }
                    }
                    graph.nodes.forEach { node ->
                        val position = layout.positions[node.id] ?: return@forEach
                        RelationNode(
                            node = node,
                            current = node.id == currentId,
                            onClick = { if (node.id != currentId) onClickNode(node.id) },
                            modifier = Modifier.offset(position.x.dp, position.y.dp),
                        )
                    }
                }
            }
            // Hayase's controls, bottom left
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1E1E1E)),
            ) {
                GraphButton(Icons.Outlined.Add, "+", onClick = { zoomBy(1.25f) })
                GraphButton(Icons.Outlined.Remove, "-", onClick = { zoomBy(0.8f) })
                GraphButton(
                    icon = Icons.Outlined.CenterFocusStrong,
                    label = stringResource(KMR.strings.anilist_relations_fit),
                    onClick = fit,
                )
                GraphButton(
                    icon = if (onCloseFullWindow != null) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull,
                    label = stringResource(KMR.strings.anilist_relations_expand),
                    onClick = { if (onCloseFullWindow != null) onCloseFullWindow() else fullWindow = true },
                )
                if (onRefresh != null) GraphButton(Icons.Outlined.Refresh, "Refresh", onClick = onRefresh)
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun GraphButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RelationNode(
    node: AniListRelationNode,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .size(NodeWidth, NodeHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF111111))
            .border(1.dp, if (current) accent else Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = node.title,
                color = if (current) accent else Color.White,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val textColor = if (current) accent else Color.White.copy(alpha = 0.8f)
            Text(text = AniListLabels.format(node.format), color = textColor, fontSize = 8.sp)
            Text(
                text = node.episodes?.let { "$it Episodes" } ?: AniListLabels.status(node.status),
                color = textColor,
                fontSize = 8.sp,
            )
        }
    }
}
