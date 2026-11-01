package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.jssource.lab.HealthState

/**
 * KMK (r96): the Source Lab's shared look: the status strip under the header (a coloured head word and a few
 * small figures), the kind pill of a source row, the health dot and the mono card. Half B's New-source screens
 * import these, so the signatures are fixed by the contract (CONTRACT-LAB.md §5.4).
 */
internal enum class StripColor { Green, Amber, Red, Blue, Neutral }

/** The one place the Lab's status colours live (C§8.1) */
@Composable
internal fun StripColor.tint(): Color = when (this) {
    StripColor.Green -> Color(0xFF4CAF50)
    StripColor.Amber -> Color(0xFFFFB300)
    StripColor.Red -> MaterialTheme.colorScheme.error
    StripColor.Blue -> MaterialTheme.colorScheme.primary
    StripColor.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The status strip: [head] in [color] with a dot, then [figures] as (label, value) cells. Four cells fit at
 * 360 dp; more are cut off at the end rather than wrapped.
 */
@Composable
internal fun LabStatusStrip(head: String, color: StripColor, figures: List<Pair<String, String>>) {
    val tint = color.tint()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(tint),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = head,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            figures.forEach { (label, value) ->
                VerticalDivider(modifier = Modifier.height(40.dp))
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** "Manga · JS", "Anime · JSON", "Manga + videos · JS" as a small outlined pill */
@Composable
internal fun KindPill(anime: Boolean, json: Boolean, hasVideos: Boolean) {
    val kind = when {
        anime -> "Anime"
        hasVideos -> "Manga + videos"
        else -> "Manga"
    }
    val text = "$kind · ${if (json) "JSON" else "JS"}"
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** A 10 dp dot in the health colour (Unknown is neutral) */
@Composable
internal fun HealthDot(state: HealthState) {
    val color = when (state) {
        HealthState.Good -> StripColor.Green
        HealthState.Attention -> StripColor.Amber
        HealthState.Failing -> StripColor.Red
        HealthState.Unknown -> StripColor.Neutral
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.tint()),
    )
}

/** A card of monospace text (a request chain, a diff, a summary line), with an optional title */
@Composable
internal fun MonoCard(title: String?, text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            SelectionContainer {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
