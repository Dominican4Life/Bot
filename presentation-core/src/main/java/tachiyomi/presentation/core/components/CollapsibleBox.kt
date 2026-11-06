package tachiyomi.presentation.core.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.theme.header

@Composable
fun CollapsibleBox(
    heading: String,
    // SY -->
    startExpanded: Boolean = false,
    // SY <--
    // KMK (r137, 4A): a glass row (rounded, faint fill) with what is picked shown in a pill before the chevron
    glassRow: Boolean = false,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(/* SY --> */startExpanded/* SY <-- */) }

    Column {
        val rowShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = if (glassRow) {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(rowShape)
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
                    .clickable { expanded = !expanded }
                    .padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            },
        ) {
            Text(
                text = heading,
                style = MaterialTheme.typography.header,
            )

            Spacer(modifier = Modifier.weight(1f))
            if (!summary.isNullOrBlank()) {
                val accent = LocalGlassAccent.current
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = 10.dp, end = 6.dp)
                        .clip(rowShape)
                        .background(accent.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            // KMK (r135): the glass chevron (round-ended stroke) that turns over as the group opens
            val turn by androidx.compose.animation.core.animateFloatAsState(
                if (expanded) 180f else 0f,
                label = "collapsibleChevron",
            )
            val tint = androidx.compose.material3.LocalContentColor.current
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = turn },
            ) {
                val w = size.width
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.25f, w * 0.40f)
                    lineTo(w * 0.5f, w * 0.65f)
                    lineTo(w * 0.75f, w * 0.40f)
                }
                drawPath(
                    path,
                    color = tint,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.2.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            content()
        }
    }
}
