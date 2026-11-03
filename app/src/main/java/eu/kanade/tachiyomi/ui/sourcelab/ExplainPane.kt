package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.tachiyomi.jssource.lab.ExplainEntry
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel

/**
 * KMK (r96): the Explain pane (D§2.3): every part of the source with its line range, a tested / optional pill and
 * the plain words (from the template's sidecar for generated code, from the method's `//` comment otherwise).
 * Tapping a part lights up its lines in the code (tablet: side by side; phone: switches to Code and scrolls).
 * Below: "Why these choices", the rule ids the template leaned on, opening the Rules file at that rule.
 *
 * KMK (r115, mock M3Explain): the parts in one card, in the order the app calls them, each with a dot for how it did
 * in the last test, its name in mono, its sentence and "Show code"; an "In short" card (where the source came from
 * and chips: kind, format, template, parts, the hidden browser) on the right when there is room (≥ 720 dp), else on
 * top. [codeBeside]: the code is shown beside this pane (a tapped part lights up there), else a tap opens Code.
 */
@Composable
internal fun ExplainPane(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editorState: SourceEditorScreenModel.State,
    modifier: Modifier = Modifier,
    codeBeside: Boolean = false,
) {
    val navigator = LocalNavigator.current
    val tested = state.lastRun?.steps.orEmpty()
        .filter { it.status != StepStatus.Skipped }
        .flatMap { step -> step.partIds.map { it to step.status } }
        .groupBy({ it.first }, { it.second })
    val ruleIds = state.explain.flatMap { it.ruleIds }.distinct()
    val source = state.source
    val origin = buildString {
        val lab = source?.lab
        when {
            lab?.templateId != null -> append("Written by the Lab from the ${lab.templateId} template v${lab.templateVersion ?: 1}")
            lab?.engine != null -> append("Written by the Lab (${lab.engine})")
            lab?.fromExtension != null -> append("Imported from the extension ${lab.fromExtension}")
            else -> append("Parts read from the code's methods and their comments")
        }
        append(" · ${state.explain.size} parts")
    }
    val chips = buildList {
        add(if (editorState.anime) "Anime" else "Manga")
        add(if (editorState.json) "JSON" else "JS")
        source?.lab?.let { lab -> lab.templateId?.let { add("$it v${lab.templateVersion ?: 1}") } }
        if ("host.render(" in editorState.code) add("hidden browser")
        if ("getFilters" in editorState.code || "FILTERS" in editorState.code) add("filters")
        val notWritten = state.explain.count { it.notWritten }
        if (notWritten > 0) add("$notWritten not written")
    }
    val onPart: (ExplainEntry) -> Unit = { entry ->
        model.showPart(entry)
        if (entry.lines != null && !codeBeside) model.setTab(LabTab.Code)
    }
    val inShort: @Composable () -> Unit = {
        LabCaptionCard(caption = "In short") {
            Text(origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                chips.forEach { LabChip(it) }
            }
            if (ruleIds.isNotEmpty()) {
                Text(
                    "Why these choices: the rules the template leaned on. Tap one to read it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    ruleIds.take(8).forEach { id ->
                        AssistChip(
                            onClick = { navigator?.push(RulesScreen(ruleId = id)) },
                            label = { Text(id, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
    val partsCard: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (state.explain.isEmpty()) {
                    Text(
                        if (editorState.code.isBlank()) "No code yet." else "No `extension` methods found in the code yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                state.explain.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    PartRow(
                        entry = entry,
                        tested = tested[entry.id]?.let { statuses -> statuses.minByOrNull { it.ordinal } },
                        selected = state.selectedPart == entry.id,
                        onClick = { onPart(entry) },
                    )
                }
            }
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    item(key = "parts") { partsCard() }
                }
                Column(modifier = Modifier.width(300.dp).verticalScroll(rememberScrollState())) { inShort() }
            }
        } else {
            LazyColumn(
                // KMK (r106): the revamp's frame (16 dp around, 12 dp between cards)
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "short") { inShort() }
                item(key = "parts") { partsCard() }
            }
        }
    }
}

/** KMK (r115): one part: status dot, its name (mono) and lines, its sentence, a pill when it's special, Show code */
@Composable
private fun PartRow(entry: ExplainEntry, tested: StepStatus?, selected: Boolean, onClick: () -> Unit) {
    val lines = entry.lines
    val dot = when {
        entry.notWritten -> StripColor.Amber.tint()
        tested != null -> tested.strip().tint()
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    entry.id.ifBlank { entry.title },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = if (lines == null) "" else if (lines.first == lines.last) "L${lines.first}" else "L${lines.first}–${lines.last}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    entry.notWritten -> Pill("not written", StripColor.Amber.tint())
                    tested == StepStatus.Red -> Pill("failed", StripColor.Red.tint())
                    tested == null && lines == null -> Pill("missing", StripColor.Neutral.tint())
                    tested == null && entry.optional -> Pill("optional", MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Unit
                }
            }
            val words = if (entry.id != entry.title && entry.title.isNotBlank()) "${entry.title}: ${entry.text}" else entry.text
            Text(
                words,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (lines != null) {
            TextButton(onClick = onClick) { Text("Show code") }
        }
    }
}

/** KMK (r115, mocks M3*): a card with a small-caps primary caption ("IN SHORT", "NEEDS YOU") over [content] */
@Composable
internal fun LabCaptionCard(caption: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            LabCaption(caption)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** KMK (r115): the mocks' card heading: small caps, primary colour */
@Composable
internal fun LabCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** KMK (r115): a small tinted chip (8 dp corners) in [color], neutral by default */
@Composable
internal fun LabChip(text: String, color: StripColor? = null) {
    val tint = color?.tint()
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint?.copy(alpha = 0.16f) ?: MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
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
