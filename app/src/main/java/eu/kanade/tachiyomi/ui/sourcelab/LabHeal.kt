package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.jssource.lab.History
import eu.kanade.tachiyomi.jssource.lab.TestRun
import eu.kanade.tachiyomi.jssource.lab.heal.HealedPick
import eu.kanade.tachiyomi.jssource.lab.heal.PickHealer

// KMK --> (r116) self-healing picks (mock XHeal): what the Test Run found moved, and the owner's choice

/** KMK (r116): every healed pick of [run] (one per label; a label healed in two steps keeps its best score) */
internal fun healedPicksOf(run: TestRun?): List<HealedPick> =
    run?.steps.orEmpty()
        .flatMap { it.healed }
        .groupBy { it.label }
        .map { (_, picks) -> picks.maxBy { it.score } }

/**
 * KMK (r116, mock XHeal): "The site changed: N picks moved" on a Test step with healed picks: each pick's old → new
 * selector, its score as a bar, how many items it matches, and a preview of the texts it finds. Nothing is written
 * until the owner taps [onUse] (the new picks go into the editor as an unsaved change and the test runs again);
 * [onPickAgain] opens the builder (null: the source wasn't made by tapping). [code] is the editor's code, for
 * "See the code change".
 */
@Composable
internal fun HealCard(
    stepName: String,
    picks: List<HealedPick>,
    code: String,
    canUse: Boolean,
    onUse: () -> Unit,
    onPickAgain: (() -> Unit)?,
) {
    var showChange by remember { mutableStateOf(false) }
    val amber = StripColor.Amber.tint()
    if (showChange) {
        HealCodeChangeDialog(code = code, picks = picks, onDismiss = { showChange = false })
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = amber.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, amber.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
        ) {
            Text(
                "The site changed: ${picks.size} ${if (picks.size == 1) "pick" else "picks"} moved",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Found by their fingerprints · $stepName page",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            picks.forEach { pick -> HealRow(pick) }
            val samples = picks.firstOrNull { it.samples.isNotEmpty() }?.samples.orEmpty()
            if (samples.isNotEmpty()) {
                LabCaption("Preview with the new picks")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    samples.take(6).forEach { sample ->
                        Text(
                            sample.take(40) + if (sample.length > 40) "…" else "",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Text(
                "Same tag family and position inside each card, text shaped like the old one, same neighbours. " +
                    "Below 70 % it doesn't guess: pick it again by tapping.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onUse, enabled = canUse) { Text("Use the new picks and test again") }
                OutlinedButton(onClick = { showChange = true }, enabled = canUse) { Text("See the code change") }
                if (onPickAgain != null) TextButton(onClick = onPickAgain) { Text("Pick again by tapping") }
            }
            Text(
                "The editor changes; nothing is saved until you tap Save, and the saved version stays in History. " +
                    "Browse keeps the old picks until then.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One pick: "Title  h3.card__title → h2.item-name", the score bar, "92% · 24 of 24" */
@Composable
private fun HealRow(pick: HealedPick) {
    val color = when {
        pick.score >= 85 -> StripColor.Green
        else -> StripColor.Amber
    }.tint()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                pick.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(72.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pick.oldSelector + "  →  " + pick.newSelector,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pick.score.coerceIn(0, 100) / 100f)
                        .fillMaxHeight()
                        .background(color),
                )
            }
            Text(
                "${pick.score}% · ${pick.matches} of ${pick.total}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "See the code change": the lines [PickHealer.applyToCode] would change, before anything is written */
@Composable
private fun HealCodeChangeDialog(code: String, picks: List<HealedPick>, onDismiss: () -> Unit) {
    val changed = remember(code, picks) {
        runCatching {
            val fixed = PickHealer.applyToCode(code, picks)
            History.diff(code, fixed).filter { it.kind == '+' || it.kind == '-' }
        }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The code change") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (changed.isEmpty()) {
                    Text(
                        "No line changes: these picks aren't written as plain values in the code. Pick them again by tapping.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Column(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
                                changed.forEach { line ->
                                    Text(
                                        "${line.kind} ${line.text.trim()}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        softWrap = false,
                                        color = if (line.kind == '+') StripColor.Green.tint() else StripColor.Red.tint(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
// KMK <--
