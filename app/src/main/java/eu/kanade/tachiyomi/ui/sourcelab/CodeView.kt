package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.jssource.lab.ConsoleRow
import eu.kanade.tachiyomi.jssource.lab.ExplainEntry
import eu.kanade.tachiyomi.jssource.lab.LintFinding
import eu.kanade.tachiyomi.jssource.lab.LintSeverity
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.TestRequest
import eu.kanade.tachiyomi.jssource.lab.TestRun
import eu.kanade.tachiyomi.jssource.lab.TestRuns
import eu.kanade.tachiyomi.jssource.lab.TestStep
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// KMK --> (r106) the Code view's two side columns (mock ECode): the Parts outline on the left of the code and the
// Test Run panel on its right, plus where the last error is.

/** Where the newest run's error is: the line the code view marks red, and how many error lines the console had */
internal data class LastError(val line: Int?, val count: Int)

/**
 * The newest run's error: the first failed step's evidence naming `source.js:N` / "line N", else the run's console
 * (live while it runs, else its saved `.console`) and its first error line with a `source.js:N` stack.
 */
@Composable
internal fun rememberLastError(key: String?, run: TestRun?): State<LastError> =
    // KMK (r106 fix): the live console is collected inside this producer (not with collectAsState), mapped to its
    // error lines only, so a run's ordinary console lines don't recompose the whole code screen; only a changed
    // LastError does. The Test Run panel / drawer and the Test pane's console collect the full console themselves.
    produceState(LastError(null, 0), key, run) {
        TestRuns.consoles
            .map { lives -> key?.let { lives[it] }?.takeIf { it.running || it.rows.isNotEmpty() }?.rows?.filter { it.level == "E" } }
            .distinctUntilChanged()
            .collectLatest { liveErrors ->
                val errors: List<ConsoleRow> = liveErrors ?: run?.let { r ->
                    withContext(Dispatchers.IO) { runCatching { TestRuns.loadConsole(r) }.getOrDefault(emptyList()) }
                }.orEmpty().filter { it.level == "E" }
                val red = run?.steps?.firstOrNull { it.status == StepStatus.Red }
                val fromStep = LabDiff.errorLineOf(red?.evidence)
                val fromConsole = errors.firstNotNullOfOrNull { LabDiff.errorLineOf(it.text + "\n" + it.detail.orEmpty()) }
                val count = if (errors.isNotEmpty()) errors.size else run?.steps?.count { it.status == StepStatus.Red } ?: 0
                value = LastError(fromStep ?: fromConsole, count)
            }
    }

/** The Parts outline: one row per part (from the code's part markers / methods), its dot = the last test's status */
@Composable
internal fun PartsOutline(
    parts: List<ExplainEntry>,
    statuses: Map<String, StepStatus>,
    selected: String?,
    onPart: (ExplainEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
        // KMK (r115, mock M3Code): the card heading's small caps in the primary colour
        Text(
            "PARTS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
        )
        if (parts.isEmpty()) {
            Text(
                "No parts found in the code yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            items(parts, key = { it.id }) { part ->
                val on = part.id == selected
                val status = statuses[part.id]
                val dot = when {
                    part.notWritten -> StripColor.Amber.tint()
                    status != null -> status.strip().tint()
                    else -> MaterialTheme.colorScheme.outline
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                        .clickable(enabled = part.lines != null) { onPart(part) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dot),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        part.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            on -> MaterialTheme.colorScheme.onSecondaryContainer
                            part.lines == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * KMK (r115, mock M3Code): the Code tab's left column: the Parts outline in a card, then a "Last test" card (k of n
 * passed · time, and the first step that didn't pass, in its colour).
 */
@Composable
internal fun PartsColumn(
    state: SourceLabScreenModel.State,
    statuses: Map<String, StepStatus>,
    onPart: (ExplainEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val run = state.lastRun
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            PartsOutline(
                parts = state.explain,
                statuses = statuses,
                selected = state.selectedPart,
                onPart = onPart,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    "LAST TEST",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    when {
                        state.running -> "Testing now…"
                        run == null -> "Not tested yet"
                        else -> "${run.passed} of ${run.counted} passed · ${msText(run.totalMs)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val worst = run?.steps?.firstOrNull { it.status == StepStatus.Red }
                    ?: run?.steps?.firstOrNull { it.status == StepStatus.Amber }
                if (worst != null && !state.running) {
                    Text(
                        worst.name + worst.evidence.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = worst.status.strip().tint(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * KMK (r115, mock M3Code): under the code, the lint finding of the part shown (else the first error / warning) with
 * its fix when the rule has one that applies here ("Apply fix"), else "Go to line"; × hides it until the source is
 * left. Nothing when there is no finding.
 */
@Composable
internal fun CodeFindingBar(
    state: SourceLabScreenModel.State,
    findings: List<LintFinding>,
    onApply: (LintFinding) -> Unit,
    onGoToLine: (Int) -> Unit,
) {
    var hidden by remember(state.key) { mutableStateOf(emptySet<String>()) }
    val open = findings.filter { f -> !f.ignored && f.line != null && "${f.ruleId}:${f.line}" !in hidden }
    val range = state.explain.firstOrNull { it.id == state.selectedPart }?.lines
    val inPart = if (range != null) open.firstOrNull { f -> f.line?.let { it in range } == true } else null
    val finding = inPart ?: open.firstOrNull { it.severity != LintSeverity.Info } ?: return
    val line = finding.line ?: return
    val rule = state.rules?.rules?.firstOrNull { it.id == finding.ruleId }
    val canApply = finding.applicable && rule?.apply != null
    val color = when (finding.severity) {
        LintSeverity.Error -> StripColor.Red
        LintSeverity.Warn -> StripColor.Amber
        LintSeverity.Info -> StripColor.Neutral
    }.tint()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(color))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Line $line · ${finding.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    finding.found,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canApply) {
                OutlinedButton(onClick = { onApply(finding) }) { Text("Apply fix") }
            } else {
                TextButton(onClick = { onGoToLine(line) }) { Text("Go to line") }
            }
            IconButton(onClick = { hidden = hidden + "${finding.ruleId}:${finding.line}" }) {
                Icon(Icons.Outlined.Close, contentDescription = "Hide")
            }
        }
    }
}

/**
 * The Test Run panel beside the code: a header (query · time, Run again / Stop), compact step rows with their ms; a
 * failed step shows its reason and "Go to line" (the line its error names, else its part's first line); the run's
 * console collapsed at the bottom.
 */
@Composable
internal fun TestRunSide(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editor: SourceEditorScreenModel,
    editorState: SourceEditorScreenModel.State,
    query: String,
    onGoToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // KMK (r106): the phone's drawer draws its own peek row instead of this header
    showHeader: Boolean = true,
) {
    val run = state.lastRun
    val steps = if (state.running || state.runSteps.isNotEmpty()) state.runSteps else run?.steps.orEmpty()
    Column(modifier = modifier) {
        if (showHeader) Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text("Test Run", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (run != null && !state.running) {
                Text(
                    (if (run.query.isNotBlank()) "\"${run.query}\" · " else "") + "%.1f s".format(run.totalMs / 1000.0),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (state.running) {
                IconButton(onClick = model::cancelTest) { Icon(Icons.Outlined.Stop, contentDescription = "Stop") }
            } else {
                IconButton(onClick = { model.runTest(editor, query) }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = if (run == null) "Run" else "Run again")
                }
            }
        }
        if (showHeader) HorizontalDivider()
        if (steps.isEmpty()) {
            Column(modifier = Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (state.running) "Starting…" else "Not tested yet. The Test Run calls the source's methods in order on the editor's test copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.running) {
                    OutlinedButton(onClick = { model.runTest(editor, query) }) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Run")
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(steps) { step ->
                    SideStepRow(
                        step = step,
                        goTo = stepLine(step, state.explain),
                        onGoToLine = onGoToLine,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
        }
        TestConsole(
            sourceKey = state.key,
            sourceName = editorState.name,
            run = run,
            steps = steps,
            onRevealLine = onGoToLine,
            maxListHeight = 260.dp,
        )
    }
}

/** The line a step's "Go to line" opens: the line its evidence names, else the first line of its first part */
internal fun stepLine(step: TestStep, parts: List<ExplainEntry>): Int? =
    LabDiff.errorLineOf(step.evidence)
        ?: step.partIds.firstNotNullOfOrNull { id -> parts.firstOrNull { it.id == id }?.lines?.first }

@Composable
private fun SideStepRow(step: TestStep, goTo: Int?, onGoToLine: (Int) -> Unit) {
    val failed = step.status == StepStatus.Red
    val tint = step.status.strip().tint()
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (failed) tint.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(tint),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            val words = when (step.status) {
                StepStatus.Running -> "running"
                StepStatus.Skipped -> "skipped"
                else -> countWords(step)
            }
            Text(
                step.name + if (words.isNotBlank()) " · $words" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (step.evidence.isNotBlank()) {
                Text(
                    step.evidence,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed || step.status == StepStatus.Amber) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (failed) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // KMK (r106): the step's requests with their reply sizes (a 1 KB bot-check page shows at a glance)
            step.requests.take(3).forEach { r ->
                Text(
                    requestLine(r),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (r.status == null || r.status >= 400) StripColor.Red.tint() else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (step.requests.size > 3) {
                Text(
                    "+${step.requests.size - 3} more requests",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (failed && goTo != null) {
                OutlinedButton(
                    onClick = { onGoToLine(goTo) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("Go to line $goTo", style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (step.status != StepStatus.Running && step.status != StepStatus.Skipped) {
            Text(
                msText(step.ms),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
        }
    }
}

/** "640 ms", "1.8 s" */
internal fun msText(ms: Long): String = if (ms >= 1000) "%.1f s".format(ms / 1000.0) else "$ms ms"

/** KMK (r106): a reply size: "512 B", "3 KB", "1.2 MB" */
internal fun bytesText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** KMK (r106): the path (and query) of a request's address, for its one-line form */
internal fun requestPath(url: String): String {
    val after = url.substringAfter("://", url)
    val slash = after.indexOf('/')
    return (if (slash >= 0) after.substring(slash) else "/").ifBlank { "/" }
}

/** KMK (r106): "200 · POST · /api · 3 KB · 640 ms" (the size only when known) */
internal fun requestLine(r: TestRequest): String = buildString {
    append(r.status?.toString() ?: r.error?.take(40) ?: "no reply")
    append(" · ").append(r.method)
    if (r.url.isNotEmpty()) append(" · ").append(requestPath(r.url))
    r.bytes?.let { append(" · ").append(bytesText(it)) }
    if (r.ms > 0) append(" · ").append(msText(r.ms))
    if (r.viaBrowser) append(" · browser")
}

/**
 * KMK (r106): the phone's Parts row above the code: one chip per part with its status dot (the Parts outline's
 * reading); a tap scrolls the code to the part.
 */
@Composable
internal fun PartsChipRow(
    parts: List<ExplainEntry>,
    statuses: Map<String, StepStatus>,
    selected: String?,
    onPart: (ExplainEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (parts.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(parts, key = { it.id }) { part ->
            val status = statuses[part.id]
            val dot = when {
                part.notWritten -> StripColor.Amber.tint()
                status != null -> status.strip().tint()
                else -> MaterialTheme.colorScheme.outline
            }
            FilterChip(
                selected = part.id == selected,
                onClick = { onPart(part) },
                enabled = part.lines != null,
                label = { Text(part.title, maxLines = 1) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dot),
                    )
                },
            )
        }
    }
}

/**
 * KMK (r106): the phone's Test Run drawer under the code: a peek row ("● k of n passing", Run / Stop, ▴) that opens
 * to [TestRunSide]'s step rows (Go to line) and console, [openHeight] tall.
 */
@Composable
internal fun TestRunDrawer(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editor: SourceEditorScreenModel,
    editorState: SourceEditorScreenModel.State,
    query: String,
    onGoToLine: (Int) -> Unit,
    openHeight: Dp,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val (badgeText, badgeColor) = runBadge(state.running, state.lastRun)
    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Text("Test Run", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            StatusBadge(badgeText, badgeColor)
            Spacer(Modifier.weight(1f))
            if (state.running) {
                IconButton(onClick = model::cancelTest) { Icon(Icons.Outlined.Stop, contentDescription = "Stop") }
            } else {
                IconButton(onClick = { model.runTest(editor, query) }) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Run the test")
                }
            }
            Icon(
                if (open) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                contentDescription = if (open) "Close the Test Run" else "Open the Test Run",
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        if (open) {
            TestRunSide(
                state = state,
                model = model,
                editor = editor,
                editorState = editorState,
                query = query,
                onGoToLine = onGoToLine,
                showHeader = false,
                modifier = Modifier.fillMaxWidth().height(openHeight),
            )
        }
    }
}
// KMK <--
