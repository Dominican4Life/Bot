package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.jssource.lab.ExplainEntry
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel

/**
 * KMK (r96): the Explain pane (D§2.3): every part of the source with its line range, a tested / optional pill and
 * the plain words (from the template's sidecar for generated code, from the method's `//` comment otherwise).
 * Tapping a part lights up its lines in the code (tablet: side by side; phone: switches to Code and scrolls).
 * Below: "Why these choices", the rule ids the template leaned on, opening the Rules file at that rule.
 */
@Composable
internal fun ExplainPane(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editorState: SourceEditorScreenModel.State,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    // KMK (r101): the tablet's Explain tab has the code beside it, so a tapped part lights up there; the phone
    // switches to Code to show the lines
    val tablet = isTabletUi()
    val tested = state.lastRun?.steps.orEmpty()
        .filter { it.status != StepStatus.Skipped }
        .flatMap { step -> step.partIds.map { it to step.status } }
        .groupBy({ it.first }, { it.second })
    val ruleIds = state.explain.flatMap { it.ruleIds }.distinct()
    val source = state.source
    LazyColumn(
        // KMK (r106): the revamp's frame (16 dp around, 12 dp between cards)
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        item(key = "head") {
            Text(
                text = buildString {
                    val lab = source?.lab
                    when {
                        lab?.templateId != null -> append("Written by the Lab from the ${lab.templateId} template v${lab.templateVersion ?: 1}")
                        lab?.engine != null -> append("Written by the Lab (${lab.engine})")
                        lab?.fromExtension != null -> append("Imported from the extension ${lab.fromExtension}")
                        else -> append("Parts read from the code's methods and their comments")
                    }
                    append(" · ${state.explain.size} parts")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.explain.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (editorState.code.isBlank()) "No code yet." else "No `extension` methods found in the code yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.explain, key = { it.id }) { entry ->
            PartCard(
                entry = entry,
                tested = tested[entry.id]?.let { statuses -> statuses.minByOrNull { it.ordinal } },
                selected = state.selectedPart == entry.id,
                onClick = {
                    model.showPart(entry)
                    if (entry.lines != null && !tablet) model.setTab(LabTab.Code)
                },
            )
        }
        if (ruleIds.isNotEmpty()) {
            item(key = "why") {
                Column {
                    Text("Why these choices", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "The rules the template leaned on. Tap one to read it in the Rules file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        ruleIds.take(6).forEach { id ->
                            AssistChip(
                                onClick = { navigator?.push(RulesScreen(ruleId = id)) },
                                label = { Text(id, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartCard(entry: ExplainEntry, tested: StepStatus?, selected: Boolean, onClick: () -> Unit) {
    val lines = entry.lines
    // KMK (r106): the cards of the revamp (mock ELint / ETest): 14 dp corners, the selected one tinted and outlined
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (lines == null) "—" else if (lines.first == lines.last) "L${lines.first}" else "L${lines.first}–${lines.last}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(76.dp),
                )
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    entry.notWritten -> Pill("not written", StripColor.Amber.tint())
                    tested != null -> Pill(if (tested == StepStatus.Red) "failed" else "tested", tested.strip().tint())
                    lines == null -> Pill("missing", StripColor.Neutral.tint())
                    entry.optional -> Pill("optional", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 76.dp),
            )
            if (entry.id != entry.title) {
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    entry.id,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 76.dp),
                )
            }
        }
    }
}

/** A small outlined word in [color] */
@Composable
internal fun Pill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
