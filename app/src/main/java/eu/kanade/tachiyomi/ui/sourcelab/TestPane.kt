package eu.kanade.tachiyomi.ui.sourcelab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.notification.BotChecks
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.lab.ExplainEntry
import eu.kanade.tachiyomi.jssource.lab.StepId
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.TestItem
import eu.kanade.tachiyomi.jssource.lab.TestRun
import eu.kanade.tachiyomi.jssource.lab.TestRuns
import eu.kanade.tachiyomi.jssource.lab.TestStep
import eu.kanade.tachiyomi.jssource.lab.videodoctor.DecryptSnippet
import eu.kanade.tachiyomi.jssource.lab.videodoctor.VideoDiagnosis
import eu.kanade.tachiyomi.jssource.lab.videodoctor.VideoFix
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import eu.kanade.tachiyomi.ui.player.VideoPreview
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r96, r106): the Test pane (mock ETest): a bar with the search word, Run all (Stop while running), Issue report,
 * Open in Browse and Edit by tapping (builder sources only); the steps on the left and the selected step's detail on
 * the right (what came back: covers with titles, chapters, videos; its requests with status and time; a failed step's
 * reason with Go to line / Show the part); "Against the previous run" under the steps; the run's console docked at
 * the bottom ([TestConsole]). Narrow (phone): the steps stack and the selected one opens its detail under it.
 * [onGoToLine] opens the code at a line (the Code tab).
 * KMK (r116): a step with healed picks shows [HealCard]; a bot check waiting for this source shows [BotCheckCard];
 * the Video step's videos and "Why won't it play"'s try-play open [VideoPreview].
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
    // KMK (r115): "Why won't it play": the fix waiting for the owner's OK (never written silently)
    var fixAsk by remember { mutableStateOf<Pair<VideoFix, VideoDiagnosis>?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // KMK (r116): Preview plays the address with its headers in the app's player, straight into a small window over
    // the Lab (VideoPreview); r115 copied them to the clipboard for an outside player
    val preview: (String, Map<String, String>, String, android.graphics.Rect?) -> Unit = { url, headers, title, rect ->
        val activity = context.findLabActivity()
        if (activity == null) {
            AppLog.add('W', "lab", "Preview: no activity to start the player from")
            model.say("The preview couldn't start here.")
        } else {
            val who = editorState.name.ifBlank { state.key.orEmpty() }
            AppLog.add('I', "lab", "Preview of a video of $who: ${url.take(120)} (${headers.size} headers)")
            runCatching { VideoPreview.open(activity, url, headers, title, rect) }
                .onFailure {
                    AppLog.add('W', "lab", "Preview failed: $it")
                    model.say("The preview couldn't start: ${it.message ?: it.javaClass.simpleName}")
                }
        }
    }
    val sourceHeaders = state.key?.let { Injekt.get<JsSourceManager>().get(it)?.headers }.orEmpty()
    val previewItem: (TestItem, android.graphics.Rect?) -> Unit = { item, rect ->
        preview(item.url, sourceHeaders + videoHeadersOf(item), previewTitle(editorState.name, item.title), rect)
    }
    // KMK (r116): bot checks waiting for this source (BotChecks, the network side's), and the picks a Test step healed
    val waitingChecks by BotChecks.waiting.collectAsState()
    val labSourceId = state.key?.let { editor.sourceId(it) }
    val waiting = labSourceId?.let { waitingChecks[it] }
    val doctor: @Composable (TestStep) -> Unit = { step ->
        if (step.healed.isNotEmpty()) {
            HealCard(
                stepName = step.name,
                picks = step.healed,
                code = editorState.code,
                canUse = !state.running && !editorState.json,
                onUse = { model.useHealedPicks(editor, step.healed, query) },
                onPickAgain = onEditByTapping?.takeIf { state.source?.let { madeByTapping(it) } == true },
            )
        }
        if (step.id == StepId.VIDEO) {
            VideoDoctorBlock(
                step = step,
                state = state,
                onCheck = { model.checkWhyNoPlay(editor) },
                onFix = { fix, diagnosis -> fixAsk = fix to diagnosis },
                onTryPlay = { url, headers, rect ->
                    preview(url, headers, previewTitle(editorState.name, "with the fixes"), rect)
                },
            )
        }
    }
    fixAsk?.let { (fix, diagnosis) ->
        val snippet = videoFixSnippet(fix, diagnosis)
        val canInsert = !editorState.json && state.explain.any { it.id == VIDEO_PART && it.lines != null }
        VideoFixDialog(
            snippet = snippet,
            canInsert = canInsert,
            onCopy = {
                clipboard.setText(AnnotatedString(snippet))
                model.say("Copied. Paste it into $VIDEO_PART, then Save.")
                fixAsk = null
            },
            onInsert = {
                model.insertIntoPart(editor, VIDEO_PART, snippet)
                fixAsk = null
            },
            onDismiss = { fixAsk = null },
        )
    }
    val selected = steps.firstOrNull { stepKey(it) == selectedKey }
        ?: steps.firstOrNull { it.status == StepStatus.Red }
        // KMK (r116): a step with healed picks is the one to look at when nothing failed
        ?: steps.firstOrNull { it.healed.isNotEmpty() }
        ?: steps.firstOrNull()
    val source = state.source
    val showPart: (ExplainEntry) -> Unit = { entry ->
        model.showPart(entry)
        model.setTab(LabTab.Code)
    }
    val browseScope = rememberCoroutineScope()
    val openInBrowse: (() -> Unit)? = if (source != null && editorState.saved) {
        {
            // KMK (r109): a source switched off in Extensions (or saved a moment ago) isn't in Browse yet, which showed
            // "Source not installed: <id>": switch it on and wait (3 s at most) until Browse has it, then open it
            browseScope.launch {
                val id = editor.sourceId(source.key)
                val manager = Injekt.get<JsSourceManager>()
                if (manager.get(source.key)?.enabled == false) {
                    withContext(Dispatchers.IO) { manager.setEnabled(source.key, true) }
                    AppLog.add('I', "lab", "Open in Browse: ${source.name} was switched off; switched it on")
                }
                var tries = 0
                while (tries < 30 && !inBrowse(source.anime, id)) {
                    delay(100)
                    tries++
                }
                navigator?.push(if (source.anime) AnimeSourceScreen(id) else BrowseSourceScreen(id, GetRemoteManga.QUERY_POPULAR))
            }
        }
    } else {
        null
    }
    // KMK (r103): Edit by tapping, only for sources the builder wrote (generic-list)
    val edit = onEditByTapping?.takeIf { source != null && editorState.saved && madeByTapping(source) }
    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= 720.dp
        val consoleHeight = maxHeight * 0.4f
        // KMK (r106): a phone opens a tapped step as its own page. KMK (r115): as a bottom sheet over the full-width
        // step list (swipe down / Back closes it)
        val page = if (wide) null else steps.firstOrNull { stepKey(it) == selectedKey }
        if (page != null) {
            ModalBottomSheet(onDismissRequest = { selectedKey = null }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
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
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                ) {
                    StepDetail(
                        step = page,
                        parts = state.explain,
                        onGoToLine = onGoToLine,
                        onShowPart = showPart,
                        showTitle = false,
                        onPreview = previewItem,
                    )
                    doctor(page)
                }
            }
        }
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
            // KMK (r116, mock XBot): the site asks for a bot check: the owner solves it (the app never does)
            if (waiting != null) {
                BotCheckCard(waiting, modifier = Modifier.padding(horizontal = if (wide) 20.dp else 12.dp).padding(bottom = 8.dp))
            }
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
                                StepDetail(
                                    step = selected,
                                    parts = state.explain,
                                    onGoToLine = onGoToLine,
                                    onShowPart = showPart,
                                    onPreview = previewItem,
                                )
                                doctor(selected)
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
    // KMK (r116): Preview of one of the Video step's videos (with the box's bounds for the small window)
    onPreview: ((TestItem, android.graphics.Rect?) -> Unit)? = null,
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
    StepChecks(step)
    if (step.items.isNotEmpty()) CameBack(step, onPreview)
    if (step.requests.isNotEmpty()) {
        LabCaption("Requests")
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

/**
 * KMK (r115, mock M3Test): "Against the previous run" lists only what changed: a step whose result or count moved
 * ("Filters · 9 → 8"), a new step, a step the previous run had and this one hasn't. Nothing changed says so in one line.
 */
@Composable
private fun PreviousRunCard(previous: TestRun, run: TestRun) {
    val changed = TestRuns.compare(previous, run).filter { it.changed }
    val gone = previous.steps.filter { old -> run.steps.none { it.id == old.id } }
    LabCard(title = "Against the previous run · ${SourceLabScreenModel.agoText(previous.startedAt)}") {
        if (changed.isEmpty() && gone.isEmpty()) {
            Text(
                "Nothing changed: every step came back as before.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        changed.forEach { d ->
            val was = d.was
            DiffRow(
                name = d.name,
                text = when {
                    was == null -> "new · ${d.now.count}"
                    was.status != d.now.status -> "${statusWord(was.status)} → ${statusWord(d.now.status)} · ${was.count} → ${d.now.count}"
                    else -> "${was.count} → ${d.now.count}"
                },
                color = d.now.status.strip().tint(),
            )
        }
        gone.forEach { old ->
            DiffRow(name = old.name, text = "not run this time", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiffRow(name: String, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun statusWord(status: StepStatus): String = when (status) {
    StepStatus.Green -> "passed"
    StepStatus.Amber -> "warned"
    StepStatus.Red -> "failed"
    StepStatus.Skipped -> "skipped"
    StepStatus.Running -> "running"
}

/** KMK (r115, mock M3Test): a list step's checks as chips: "covers 12/12", "all with a link" / "3 without a link" */
@Composable
private fun StepChecks(step: TestStep) {
    val entries = step.items.filter { it.kind == "entry" }
    if (entries.isEmpty()) return
    val covers = entries.count { !it.imageUrl.isNullOrBlank() }
    val noLink = entries.count { it.url.isBlank() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabChip("covers $covers/${entries.size}", if (covers == entries.size) StripColor.Green else StripColor.Amber)
        LabChip(
            if (noLink == 0) "all with a link" else "$noLink without a link",
            if (noLink == 0) StripColor.Green else StripColor.Red,
        )
        if (step.count > entries.size) LabChip("showing ${entries.size} of ${step.count}")
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
private fun CameBack(step: TestStep, onPreview: ((TestItem, android.graphics.Rect?) -> Unit)? = null) {
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
                // KMK (r116): each video with an http address gets Preview (the app's player, in a small window)
                VideoLine(item = item, onPreview = onPreview?.takeIf { item.url.startsWith("http") })
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

/** KMK (r109): whether Browse has the source with [id] (its source manager knows it) */
private fun inBrowse(anime: Boolean, id: Long): Boolean = runCatching {
    if (anime) Injekt.get<AnimeSourceManager>().get(id) != null else Injekt.get<SourceManager>().get(id) != null
}.getOrDefault(true)

// KMK --> (r115) "Why won't it play" (W7's VideoDoctor and VideoDoctorPanel) in the Video step

/** The source part the fixes go into */
private const val VIDEO_PART = "getVideoList"

/**
 * Under the Video step's detail: W7's panel at once when the step failed or warned (or a check ran / is running);
 * a passing step shows only a small "Check why it won't play" (it opens the panel and starts the check).
 */
@Composable
private fun VideoDoctorBlock(
    step: TestStep,
    state: SourceLabScreenModel.State,
    onCheck: () -> Unit,
    onFix: (VideoFix, VideoDiagnosis) -> Unit,
    onTryPlay: (String, Map<String, String>, android.graphics.Rect?) -> Unit,
) {
    var opened by rememberSaveable(state.key) { mutableStateOf(false) }
    val diagnosis = state.key?.let { state.diagnoses[it] }
    val running = state.doctorKey != null && state.doctorKey == state.key
    val bad = step.status == StepStatus.Red || step.status == StepStatus.Amber
    if (bad || opened || diagnosis != null || running) {
        VideoDoctorPanel(
            diagnosis = diagnosis,
            running = running,
            onCheck = onCheck,
            onFix = { fix -> diagnosis?.let { onFix(fix, it) } },
            onTryPlay = onTryPlay,
            progress = if (running) state.doctorProgress else null,
        )
    } else {
        TextButton(
            onClick = {
                opened = true
                onCheck()
            },
        ) { Text("Check why it won't play") }
    }
}

/** The fix's JS, shown before anything is written ("Add this to the source's video part") */
internal fun videoFixSnippet(fix: VideoFix, diagnosis: VideoDiagnosis): String = when (fix) {
    is VideoFix.DecryptWithKey -> DecryptSnippet.HELPER.trim() + "\n\n" +
        "// the page's text in html (e.g. var html = (await fetch(url)).text), then:\n" +
        DecryptSnippet.forFix(fix, "html") + "\n" +
        "// use url as the video's address"
    is VideoFix.AddHeaders -> {
        val pairs = fix.headers.entries.joinToString(", ") { (k, v) -> DecryptSnippet.jsString(k) + ": " + DecryptSnippet.jsString(v) }
        "// the headers the player needs, on every video this part returns (videos = the list it returns)\n" +
            "videos = videos.map(function (v) {\n" +
            "  v.headers = Object.assign({}, v.headers || {}, { $pairs });\n" +
            "  return v;\n" +
            "});"
    }
    VideoFix.UseHiddenBrowser -> "// the hidden browser plays the episode page and catches the stream" +
        (diagnosis.playerPage?.let { "\n// (the check found it on " + it + ")" }.orEmpty()) + "\n" +
        "// url = the episode address this part gets\n" +
        "var found = await host.findVideo(url);\n" +
        "return found.videos;"
}

/** KMK (r116): one video of the Video step: "server · address" and Preview; the box's bounds go to the player */
@Composable
private fun VideoLine(item: TestItem, onPreview: ((TestItem, android.graphics.Rect?) -> Unit)?) {
    val bounds = remember { LabRectHolder() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .onGloballyPositioned { bounds.rect = it.boundsInWindow().toAndroidRect() },
    ) {
        Text(
            "${item.title} · ${item.subtitle.orEmpty().ifBlank { item.url }.take(120)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onPreview != null) {
            TextButton(onClick = { onPreview(item, bounds.rect) }) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Preview")
            }
        }
    }
}

/** KMK (r116): the headers a video of the Test Run carries itself (its raw `headers` object) */
private fun videoHeadersOf(item: TestItem): Map<String, String> =
    (item.raw?.get("headers") as? JsonObject)
        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
        ?.toMap()
        .orEmpty()

private fun previewTitle(sourceName: String, what: String): String =
    listOf(sourceName.ifBlank { "Source Lab" }, what.ifBlank { "Preview" }).joinToString(" · ")

/** KMK (r116): where a box was last laid out (read on tap; not state, so scrolling doesn't recompose) */
internal class LabRectHolder {
    var rect: android.graphics.Rect? = null
}

/** KMK (r116): a Compose rect in window pixels as the android Rect the player's small window starts from */
internal fun androidx.compose.ui.geometry.Rect.toAndroidRect(): android.graphics.Rect =
    android.graphics.Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

/** KMK (r116): the Activity behind a Compose context (VideoPreview needs one) */
internal tailrec fun android.content.Context.findLabActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findLabActivity()
    else -> null
}

@Composable
private fun VideoFixDialog(
    snippet: String,
    canInsert: Boolean,
    onCopy: () -> Unit,
    onInsert: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add this to the source's video part") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    if (canInsert) {
                        "Insert puts these lines at the end of $VIDEO_PART in the editor; move them where the video's " +
                            "address is read. Nothing is saved until you tap Save."
                    } else {
                        "Copy these lines into $VIDEO_PART where the video's address is read, then Save."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            snippet,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                            softWrap = false,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (canInsert) {
                TextButton(onClick = onInsert) { Text("Insert at the end of $VIDEO_PART") }
            } else {
                TextButton(onClick = onCopy) { Text("Copy") }
            }
        },
        dismissButton = {
            Row {
                if (canInsert) TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
// KMK <--
