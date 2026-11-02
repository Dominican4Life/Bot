package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.jssource.lab.ExplainEntry
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.TestItem
import eu.kanade.tachiyomi.jssource.lab.TestRun
import eu.kanade.tachiyomi.jssource.lab.TestRuns
import eu.kanade.tachiyomi.jssource.lab.TestStep
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import tachiyomi.domain.source.interactor.GetRemoteManga

/**
 * KMK (r96, r106): the Test pane (mock ETest): a bar with the search word, Run all (Stop while running), Issue report,
 * Open in Browse and Edit by tapping (builder sources only); the steps on the left and the selected step's detail on
 * the right (what came back: covers with titles, chapters, videos; its requests with status and time; a failed step's
 * reason with Go to line / Show the part); "Against the previous run" under the steps; the run's console docked at
 * the bottom ([TestConsole]). Narrow (phone): the steps stack and the selected one opens its detail under it.
 * [onGoToLine] opens the code at a line (the Code tab).
 */
@Composable
internal fun TestPane(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editor: SourceEditorScreenModel,
    editorState: SourceEditorScreenModel.State,
    query: String,
    onQuery: (String) -> Unit,
    onGoToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onEditByTapping: (() -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val steps = if (state.running || state.runSteps.isNotEmpty()) state.runSteps else state.lastRun?.steps.orEmpty()
    val run = state.lastRun
    var selectedKey by rememberSaveable(state.key) { mutableStateOf<String?>(null) }
    val selected = steps.firstOrNull { stepKey(it) == selectedKey }
        ?: steps.firstOrNull { it.status == StepStatus.Red }
        ?: steps.firstOrNull()
    val source = state.source
    val showPart: (ExplainEntry) -> Unit = { entry ->
        model.showPart(entry)
        model.setTab(LabTab.Code)
    }
    val openInBrowse: (() -> Unit)? = if (source != null && editorState.saved) {
        {
            val id = editor.sourceId(source.key)
            navigator?.push(if (source.anime) AnimeSourceScreen(id) else BrowseSourceScreen(id, GetRemoteManga.QUERY_POPULAR))
        }
    } else {
        null
    }
    // KMK (r103): Edit by tapping, only for sources the builder wrote (generic-list)
    val edit = onEditByTapping?.takeIf { source != null && editorState.saved && madeByTapping(source) }
    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= 720.dp
        val consoleHeight = maxHeight * 0.4f
        // KMK (r106): a phone opens a tapped step as its own page (back arrow / Back returns to the list)
        val page = if (wide) null else steps.firstOrNull { stepKey(it) == selectedKey }
        BackHandler(enabled = page != null) { selectedKey = null }
        if (page != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                ) {
                    IconButton(onClick = { selectedKey = null }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to the steps")
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(page.status.strip().tint()),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        page.name + countWords(page).takeIf { it.isNotBlank() && page.status != StepStatus.Skipped }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        msText(page.ms),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    StepDetail(step = page, parts = state.explain, onGoToLine = onGoToLine, onShowPart = showPart, showTitle = false)
                }
            }
        } else {
        Column(modifier = Modifier.fillMaxSize()) {
            TestBar(
                wide = wide,
                query = query,
                onQuery = onQuery,
                running = state.running,
                run = run,
                onRun = { model.runTest(editor, query) },
                onStop = model::cancelTest,
                onReport = { model.setTab(LabTab.Report) },
                onBrowse = openInBrowse,
                onEdit = edit,
            )
            Text(
                text = when {
                    state.running -> "Running · ${steps.size} ${if (steps.size == 1) "step" else "steps"} so far"
                    run == null -> "Not run yet. Run all calls the source's methods in order on the editor's test copy."
                    else -> "${SourceLabScreenModel.agoText(run.startedAt)} · ${"%.1f".format(run.totalMs / 1000.0)} s · " +
                        "${run.passed} of ${run.counted} passed" +
                        (if (run.query.isNotBlank()) " · \"${run.query}\"" else "") +
                        (run.rulesVersion?.let { " · rules v$it" }.orEmpty())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            if (wide) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                ) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
                    ) {
                        itemsIndexed(steps) { _, step ->
                            StepCard(step = step, selected = step === selected, onClick = { selectedKey = stepKey(step) })
                        }
                        if (run != null && state.previousRun != null) {
                            item { PreviousRunCard(previous = state.previousRun, run = run) }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            if (selected == null) {
                                Text(
                                    if (state.running) "Waiting for the first step…" else "Run all to see what each step brings back.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                StepDetail(step = selected, parts = state.explain, onGoToLine = onGoToLine, onShowPart = showPart)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    itemsIndexed(steps) { _, step ->
                        // a tap opens the step's page (above)
                        StepCard(step = step, selected = false, onClick = { selectedKey = stepKey(step) })
                    }
                    if (run != null && state.previousRun != null) {
                        item { PreviousRunCard(previous = state.previousRun, run = run) }
                    }
                }
            }
            TestConsole(
                sourceKey = state.key,
                sourceName = editorState.name,
                run = run,
                steps = steps,
                onRevealLine = onGoToLine,
                maxListHeight = consoleHeight,
            )
        }
        }
    }
}

private fun stepKey(step: TestStep): String = step.id + "|" + step.name

@Composable
private fun TestBar(
    wide: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    running: Boolean,
    run: TestRun?,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onReport: () -> Unit,
    onBrowse: (() -> Unit)?,
    onEdit: (() -> Unit)?,
) {
    // r103: wraps (phone) instead of scrolling sideways, so Edit is never hidden off the edge
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (wide) 20.dp else 12.dp, vertical = 12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search word") },
            placeholder = { Text("empty: the first title's first word") },
            singleLine = true,
            modifier = if (wide) Modifier.width(280.dp) else Modifier.fillMaxWidth(),
        )
        if (running) {
            OutlinedButton(onClick = onStop) {
                Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Stop")
            }
        } else {
            Button(onClick = onRun) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Run all")
            }
        }
        OutlinedButton(onClick = onReport, enabled = run != null) {
            Icon(
                Icons.Outlined.BugReport,
                contentDescription = null,
                tint = if (run?.worst == StepStatus.Red) StripColor.Red.tint() else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text("  Issue report")
        }
        if (onBrowse != null) {
            OutlinedButton(onClick = onBrowse) { Text("Open in Browse") }
        }
        if (onEdit != null) {
            OutlinedButton(onClick = onEdit, enabled = !running) {
                Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Edit by tapping")
            }
        }
    }
}

/** One step: dot, "Popular · 20 titles", ms; [extra] below it (the phone's inline detail) */
@Composable
private fun StepCard(
    step: TestStep,
    selected: Boolean,
    onClick: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val tint = step.status.strip().tint()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(tint),
                )
                Spacer(Modifier.width(12.dp))
                val words = when (step.status) {
                    StepStatus.Running -> "running"
                    StepStatus.Skipped -> "skipped"
                    else -> countWords(step)
                }
                Text(
                    step.name + if (words.isNotBlank()) " · $words" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (step.status == StepStatus.Running || step.status == StepStatus.Skipped) "–" else msText(step.ms),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            extra()
        }
    }
}

/** The selected step: title, its evidence (a failure's reason with Go to line), what came back, its requests */
@Composable
private fun StepDetail(
    step: TestStep,
    parts: List<ExplainEntry>,
    onGoToLine: (Int) -> Unit,
    onShowPart: (ExplainEntry) -> Unit,
    showTitle: Boolean = true,
) {
    val tint = step.status.strip().tint()
    val bad = step.status == StepStatus.Red || step.status == StepStatus.Amber
    if (showTitle) {
        Text(
            step.name + countWords(step).takeIf { it.isNotBlank() && step.status != StepStatus.Skipped }?.let { " · $it" }.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    if (step.evidence.isNotBlank()) {
        Text(
            step.evidence,
            style = MaterialTheme.typography.bodySmall,
            color = if (bad) tint else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (step.status == StepStatus.Red) {
        val line = stepLine(step, parts)
        val part = step.partIds.firstNotNullOfOrNull { id -> parts.firstOrNull { it.id == id && it.lines != null } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (line != null) OutlinedButton(onClick = { onGoToLine(line) }) { Text("Go to line $line") }
            if (part != null) TextButton(onClick = { onShowPart(part) }) { Text("Show ${part.title}") }
        }
    }
    if (step.items.isNotEmpty()) CameBack(step)
    if (step.requests.isNotEmpty()) {
        Text(
            "REQUESTS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            step.requests.take(20).forEach { r ->
                val failed = r.status == null || r.status >= 400
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Text(
                        r.status?.toString() ?: "—",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (failed) StripColor.Red.tint() else StripColor.Green.tint(),
                    )
                    Text(r.method, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                    Text(
                        requestPath(r.url) + (if (r.viaBrowser) " · browser" else "") + (r.error?.let { " · $it" }.orEmpty()),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // KMK (r106): the reply's size next to the time ("3 KB · 640 ms")
                    Text(
                        listOfNotNull(r.bytes?.let { bytesText(it) }, msText(r.ms)).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (step.requests.size > 20) {
                Text(
                    "… ${step.requests.size - 20} more in the console",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviousRunCard(previous: TestRun, run: TestRun) {
    val diffs = TestRuns.compare(previous, run)
    LabCard(title = "Against the previous run · ${SourceLabScreenModel.agoText(previous.startedAt)}") {
        diffs.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(d.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    text = if (d.was == null) {
                        "new → ${d.now.status.name.lowercase()} ${d.now.count}"
                    } else {
                        "${d.was.status.name.lowercase()} ${d.was.count} → ${d.now.status.name.lowercase()} ${d.now.count}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (d.changed) d.now.status.strip().tint() else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun countWords(step: TestStep): String = when (step.id) {
    "title" -> if (step.count > 0) "${step.count} of 8 fields" else ""
    "chapters" -> step.count.toString()
    "hosters" -> "${step.count} ${if (step.count == 1) "server" else "servers"}"
    "video" -> "${step.count} of ${step.items.size} played"
    "pages" -> "${step.count} pages"
    TestRuns.STEP_FILTERS -> if (step.items.isEmpty()) "" else "${step.count} of ${step.items.size} change the list"
    TestRuns.STEP_SETTINGS -> "${step.count} ${if (step.count == 1) "setting" else "settings"}"
    // KMK (r107): Suggestions: every card of every group, then how many groups
    TestRuns.STEP_RELATED -> "${step.count} cards · ${step.items.size} ${if (step.items.size == 1) "group" else "groups"}"
    else -> "${step.count} titles"
}

/**
 * KMK (r107): one group of the Suggestions step: its name and card count (or "failed" in red with the reason), the
 * first titles below. The counts come from the item's raw `{ group, cards, titles, error? }` (TestRuns.relatedCheck).
 */
@Composable
private fun RelatedGroupRow(item: TestItem) {
    val raw = item.raw
    val cards = (raw?.get("cards") as? JsonPrimitive)?.intOrNull ?: 0
    val error = (raw?.get("error") as? JsonPrimitive)?.contentOrNull
    val titles = (raw?.get("titles") as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { t -> t.isNotBlank() } }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    error != null -> "failed"
                    cards == 1 -> "1 card"
                    else -> "$cards cards"
                },
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = when {
                    error != null -> StripColor.Red.tint()
                    cards == 0 -> StripColor.Amber.tint()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        val below = error ?: titles.joinToString(" · ").ifBlank { null }
        if (below != null) {
            Text(
                below,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) StripColor.Red.tint() else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (error != null) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What came back: the covers of a list step with their titles, the first chapters, the video lines */
@Composable
private fun CameBack(step: TestStep) {
    Column {
        Text(
            if (step.id == TestRuns.STEP_RELATED) {
                "${step.items.size} ${if (step.items.size == 1) "group" else "groups"} came back"
            } else {
                "${step.items.size}${if (step.count > step.items.size) " of ${step.count}" else ""} came back"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // KMK (r107): Suggestions: one row per group (name, card count or why it failed, first titles)
        if (step.id == TestRuns.STEP_RELATED) {
            step.items.forEach { item -> RelatedGroupRow(item) }
        } else when (step.items.first().kind) {
            "entry" -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                step.items.take(10).forEach { item ->
                    Column(modifier = Modifier.width(88.dp)) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 88.dp, height = 122.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        Text(
                            item.title,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            "video" -> step.items.take(6).forEach { item ->
                Text(
                    "${item.title} · ${item.subtitle.orEmpty().take(120)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            else -> step.items.take(8).forEach { item ->
                Text(
                    listOfNotNull(item.title, item.subtitle).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
