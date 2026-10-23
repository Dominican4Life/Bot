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
import androidx.compose.material.icons.outlined.CenterFocusStrong
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
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.max
import kotlin.math.min

private val NodeWidth = 150.dp
private val NodeHeight = 66.dp
private val ColumnGap = 120.dp
private val RowGap = 36.dp
private const val MAX_COLUMN = 6

/** Where each anime sits in the diagram, in dp, left to right from prequels to sequels */
private class GraphLayout(
    val positions: Map<Int, Offset>,
    val width: Float,
    val height: Float,
)

/** Prequels on the left, sequels and side stories to the right, like Hayase's (dagre, left to right) */
private fun layoutGraph(graph: AniListGraph): GraphLayout {
    val ids = graph.nodes.map { it.id }
    val rank = HashMap<Int, Int>().apply { ids.forEach { put(it, 0) } }
    // Longest path from the start, stopping on loops
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
    val columns = ids.groupBy { rank[it] ?: 0 }.toSortedMap()
    // Within a column, keep anime near the ones they come from
    val order = HashMap<Int, Float>()
    val parents = graph.edges.groupBy({ it.to }, { it.from })
    val placed = LinkedHashMap<Int, List<Int>>()
    columns.forEach { (column, members) ->
        val sorted = members.sortedBy { id ->
            parents[id]?.mapNotNull { order[it] }?.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: Float.MAX_VALUE
        }
        sorted.forEachIndexed { index, id -> order[id] = index.toFloat() }
        placed[column] = sorted
    }
    // A tall column (lots of movies and specials off one title) is split into a few narrower ones, the titles
    // linked to nothing else going furthest out, so the diagram spreads out like Hayase's instead of one long line
    val degree = HashMap<Int, Int>()
    graph.edges.forEach { edge ->
        degree[edge.from] = (degree[edge.from] ?: 0) + 1
        degree[edge.to] = (degree[edge.to] ?: 0) + 1
    }
    val outgoing = graph.edges.map { it.from }.toSet()
    val subColumns = placed.values.flatMap { members ->
        if (members.size <= MAX_COLUMN) {
            listOf(members)
        } else {
            val (onward, leaves) = members.partition { it in outgoing || (degree[it] ?: 0) > 1 }
            (onward + leaves).chunked(MAX_COLUMN)
        }
    }
    val stepX = NodeWidth.value + ColumnGap.value
    val stepY = NodeHeight.value + RowGap.value
    val tallest = subColumns.maxOfOrNull { it.size } ?: 1
    val positions = HashMap<Int, Offset>()
    subColumns.forEachIndexed { columnIndex, members ->
        val top = (tallest - members.size) * stepY / 2f
        members.forEachIndexed { index, id ->
            positions[id] = Offset(columnIndex * stepX, top + index * stepY)
        }
    }
    return GraphLayout(
        positions = positions,
        width = max(1, subColumns.size) * stepX - ColumnGap.value,
        height = tallest * stepY - RowGap.value,
    )
}

/**
 * KMK: Hayase's relations diagram: a box you can drag around and pinch to zoom, with every anime of the franchise
 * linked by moving lines. Tapping an anime opens it.
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
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val boxHeight = if (expanded) height * 1.5f else height
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fill) Modifier.fillMaxSize() else Modifier)
            .padding(vertical = 8.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0B0B0B),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(
            modifier = (if (fill) Modifier.fillMaxSize() else Modifier.height(boxHeight)).clipToBounds(),
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
            val fit: () -> Unit = {
                val fitScale = min(min(boxWidthDp / (layout.width + 40f), boxHeightDp / (layout.height + 40f)), 1.2f)
                    .coerceAtLeast(0.15f)
                scale = fitScale
                // Centered, in px
                offset = with(density) {
                    Offset(
                        ((boxWidthDp - layout.width * fitScale) / 2f).dp.toPx(),
                        ((boxHeightDp - layout.height * fitScale) / 2f).dp.toPx(),
                    )
                }
            }
            LaunchedEffect(layout, maxWidth, maxHeight) { fit() }

            val transition = rememberInfiniteTransition(label = "relations")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 40f,
                animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
                label = "dash",
            )
            val lineColor = Color.White.copy(alpha = 0.45f)
            val currentColor = MaterialTheme.colorScheme.primary
            val textMeasurer = rememberTextMeasurer()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(0.15f, 2.5f)
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
                        graph.edges.forEach { edge ->
                            val from = layout.positions[edge.from] ?: return@forEach
                            val to = layout.positions[edge.to] ?: return@forEach
                            val start = Offset(from.x.dp.toPx() + nodeW, from.y.dp.toPx() + nodeH / 2f)
                            val end = Offset(to.x.dp.toPx(), to.y.dp.toPx() + nodeH / 2f)
                            // Lines going back (a loop) come out of the same side
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
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(10.dp.toPx(), 10.dp.toPx()),
                                        -phase.dp.toPx(),
                                    ),
                                ),
                            )
                            val label = AniListLabels.relation(edge.label)
                            val measured = textMeasurer.measure(
                                text = label,
                                style = TextStyle(fontSize = 9.sp, color = color, fontWeight = FontWeight.SemiBold),
                            )
                            val middle = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                            drawRect(
                                color = Color(0xFF0B0B0B),
                                topLeft = Offset(
                                    middle.x - measured.size.width / 2f - 3f,
                                    middle.y - measured.size.height / 2f - 1f,
                                ),
                                size = androidx.compose.ui.geometry.Size(
                                    measured.size.width + 6f,
                                    measured.size.height + 2f,
                                ),
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
            // Fit and Bigger box, bottom left like the diagram's controls
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GraphButton(
                    icon = Icons.Outlined.CenterFocusStrong,
                    label = stringResource(KMR.strings.anilist_relations_fit),
                    onClick = fit,
                )
                if (!fill) {
                    GraphButton(
                        icon = if (expanded) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull,
                        label = stringResource(KMR.strings.anilist_relations_expand),
                        onClick = { expanded = !expanded },
                    )
                }
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
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1E1E1E))
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
