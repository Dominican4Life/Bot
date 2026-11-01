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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import eu.kanade.tachiyomi.jssource.lab.TemplateUpdate

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

/**
 * KMK (r100): under the status strip when the open source was written with an older template version:
 * "Template v2 → v3 · <note>" and a small Update button (the confirm dialog is [TemplateUpdateDialog]).
 */
@Composable
internal fun TemplateUpdateBar(offer: TemplateUpdate.Offer, busy: Boolean, onUpdate: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Template v${offer.from} → v${offer.to}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = offer.problem
                        ?: (if (offer.parts.isEmpty()) "Nothing to replace for this source: marks it as v${offer.to}" else null)
                        ?: offer.notes.lastOrNull()
                        ?: "New ${offer.partWords} part",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onUpdate, enabled = !busy) {
                Text(if (busy) "Updating…" else "Update")
            }
        }
    }
}

/** KMK (r100): "Update template?": the parts it replaces, what changed, and that the old code stays in History */
@Composable
internal fun TemplateUpdateDialog(offer: TemplateUpdate.Offer, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val problem = offer.problem
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update to ${offer.templateName} v${offer.to}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (problem != null) {
                    Text(problem)
                } else if (offer.parts.isEmpty()) {
                    // r100 fix pass: the listed parts were left out when the source was written (a manga source has
                    // no Videos part): only the template version is raised, with a note in History
                    Text(
                        "${offer.sourceName} has no ${offer.skippedTitles.joinToString(", ")} part, so there is " +
                            "nothing to replace: this only marks it as written with v${offer.to} (noted in History).",
                    )
                } else {
                    Text("Replaces these parts of ${offer.sourceName}: ${offer.partWords}")
                    offer.notes.forEach { note ->
                        Text(
                            text = "• $note",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Your edits in these parts are replaced; the old code stays in History.")
                    Text(
                        text = "Run the Test afterwards to check it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (problem == null) {
                TextButton(onClick = onConfirm) { Text("Update") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (problem == null) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
