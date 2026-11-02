package eu.kanade.tachiyomi.ui.sourcelab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.jssource.lab.History
import eu.kanade.tachiyomi.jssource.lab.LabVersion
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import eu.kanade.tachiyomi.ui.sourcelab.SourceLabScreenModel.Companion.EDITOR_VERSION
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KMK (r96, r106): the History pane (mock EHistory): a timeline (the editor's unsaved edits on top, then every
 * version with its tags: open = the current one, baseline, generated, the Test Run result) and, for the selected row,
 * a compare with the version before it (the parts it touches with +/− counts, the changed lines) and its actions:
 * Open vN (r98's Restore: vN becomes the source's code and is marked current, no new version), Compare with… (another
 * version or your edits) and Delete vN. Long-press still selects several to delete. Unsaved edits are asked about
 * before an Open ([askBeforeReplacing]). Narrow: the compare view follows the timeline.
 */
@Composable
internal fun HistoryPane(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editor: SourceEditorScreenModel,
    editorState: SourceEditorScreenModel.State,
    askBeforeReplacing: (String, () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmRestore by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf<List<Int>?>(null) }
    // Long-press selection for deleting several; per visit of the source (a stale selection is a hidden argument)
    var selection by remember(state.key) { mutableStateOf(emptySet<Int>()) }
    val versions = state.history.versions.asReversed()
    val current = History.currentOf(state.history)
    // KMK (r106): the baseline (the stored pointer, else the implied one) is never deleted while it is the baseline
    val baseline = History.baselineOf(state.history)
    var confirmBaseline by remember { mutableStateOf<Int?>(null) }
    val deletable: (Int) -> Boolean = { n -> n != current && n != state.history.generatedFrom && n != baseline }
    // Versions gone from the list (deleted, trimmed) leave the selection
    val shownSelection = selection.filter { n -> versions.any { it.n == n } && deletable(n) }.toSet()
    val format = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
    val unsaved = editorState.dirty && editorState.saved
    val restore: (Int) -> Unit = { n ->
        if (editorState.dirty) {
            // The editor's own dialog: Save (then restore), Discard (then restore), Cancel
            askBeforeReplacing("Restoring v$n") { model.restore(editor, n) }
        } else {
            confirmRestore = n
        }
    }
    val select: (Int?, Int?) -> Unit = { n, against -> model.selectVersion(n, editorState.code, against, editorState.json) }
    val onRow: (Int) -> Unit = { n ->
        if (shownSelection.isNotEmpty() && n != EDITOR_VERSION) {
            if (deletable(n)) selection = if (n in shownSelection) shownSelection - n else shownSelection + n
        } else {
            select(if (state.selectedVersion == n) null else n, null)
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= 760.dp
        // A wide pane opens on the current version compared with the one before it
        LaunchedEffect(state.key, wide, versions.isNotEmpty()) {
            if (wide && state.selectedVersion == null && versions.isNotEmpty()) select(current, null)
        }
        val compare: @Composable () -> Unit = {
            CompareView(
                state = state,
                current = current,
                versions = versions,
                canDelete = state.selectedVersion?.let { it != EDITOR_VERSION && deletable(it) } == true,
                onOpen = restore,
                onCompareWith = { against -> state.selectedVersion?.let { select(it, against) } },
                onDelete = { n -> confirmDelete = listOf(n) },
                baseline = baseline,
                onMakeBaseline = { n -> confirmBaseline = n },
            )
        }
        // KMK (r106): a phone opens a tapped row as its compare page (back arrow / Back returns to the timeline)
        val page = !wide && state.selectedVersion != null
        BackHandler(enabled = page) { select(null, null) }
        if (page) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { select(null, null) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to the versions")
                    }
                    Text("Compare", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) { compare() }
            }
        } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (wide) 20.dp else 0.dp),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = if (wide) 0.dp else 12.dp, end = if (wide) 0.dp else 12.dp, top = 12.dp, bottom = 24.dp),
                modifier = if (wide) Modifier.width(420.dp).fillMaxHeight() else Modifier.fillMaxSize(),
            ) {
                item(key = "head") {
                    Text(
                        text = when {
                            versions.isEmpty() -> "No versions yet. Every Save keeps one here (30 at most; the one the code was generated from is never trimmed)."
                            else -> "${versions.size} versions · open v$current" +
                                (if (versions.first().n != current) " · newest v${versions.first().n}" else "") +
                                (if (versions.size > 1 && shownSelection.isEmpty()) " · long-press to select several" else "")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (shownSelection.isNotEmpty()) {
                    item(key = "selection") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "${shownSelection.size} selected · " + shownSelection.sortedDescending().take(6).joinToString { "v$it" } +
                                    if (shownSelection.size > 6) " …" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { selection = emptySet() }) { Text("Clear") }
                            TextButton(onClick = { confirmDelete = shownSelection.sortedDescending() }) { Text("Delete") }
                        }
                    }
                }
                if (unsaved) {
                    item(key = "unsaved") {
                        TimelineRow(
                            title = "Your edits",
                            tags = listOf("not saved" to StripColor.Amber),
                            time = "now",
                            note = "Since v$current · not saved yet",
                            counts = null,
                            selected = state.selectedVersion == EDITOR_VERSION,
                            checked = false,
                            last = versions.isEmpty(),
                            onClick = { onRow(EDITOR_VERSION) },
                            onLongClick = {},
                        )
                    }
                }
                items(versions, key = { it.n }) { version ->
                    TimelineRow(
                        title = "v${version.n}",
                        tags = versionTags(version, current, baseline, state),
                        time = format.format(Date(version.time)),
                        note = version.note,
                        counts = "+${version.added} −${version.removed}",
                        selected = state.selectedVersion == version.n,
                        checked = version.n in shownSelection,
                        last = version === versions.last(),
                        onClick = { onRow(version.n) },
                        onLongClick = {
                            if (deletable(version.n)) {
                                selection = if (version.n in shownSelection) shownSelection - version.n else shownSelection + version.n
                            }
                        },
                    )
                }
            }
            if (wide) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 12.dp, bottom = 16.dp),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        if (state.selectedVersion == null) {
                            Text(
                                if (versions.isEmpty()) "Save the source to start its history." else "Pick a version to compare it with the one before.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            compare()
                        }
                    }
                }
            }
        }
        }
    }
    confirmRestore?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Open v$n?") },
            text = {
                Text(
                    "The code of v$n becomes the source's code and v$n is marked current. No new version is added and " +
                        "every version stays in the list; your next Save adds a new version as usual. " +
                        "The source's settings, test copy and library titles are untouched.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = null
                        model.restore(editor, n)
                    },
                ) { Text("Open v$n") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
    // KMK (r106): "Make vN the baseline" asks first
    confirmBaseline?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmBaseline = null },
            title = { Text("Make v$n the baseline?") },
            text = {
                Text(
                    "v$n becomes the baseline" + (baseline?.let { "; v$it stops being it" } ?: "") + ". " +
                        "The baseline is kept when old versions are trimmed, can't be deleted while it is the " +
                        "baseline, and Save asks before overwriting it. The code isn't changed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBaseline = null
                        model.makeBaseline(n)
                    },
                ) { Text("Make v$n the baseline") }
            },
            dismissButton = { TextButton(onClick = { confirmBaseline = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { ns ->
        val names = ns.take(6).joinToString { "v$it" } + if (ns.size > 6) " and ${ns.size - 6} more" else ""
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (ns.size == 1) "Delete v${ns.first()}?" else "Delete ${ns.size} versions?") },
            text = {
                Text(
                    "Deletes $names from this source's history for good (the files are removed). " +
                        "The source's code, the current version (v$current) and the version it was generated from are kept.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        selection = emptySet()
                        model.deleteVersions(ns.toSet())
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** A version's tags: open (current), baseline, generated, and the Test Run made on it */
private fun versionTags(version: LabVersion, current: Int, baseline: Int?, state: SourceLabScreenModel.State): List<Pair<String, StripColor>> = buildList {
    if (version.n == current) add("open" to StripColor.Blue)
    // KMK (r106): History.baselineOf (made by hand, else the "Baseline" note, generated-from or first version)
    if (version.n == baseline) add("baseline" to StripColor.Neutral)
    if (version.n == state.history.generatedFrom) add("generated" to StripColor.Neutral)
    val run = state.lastRun?.takeIf { it.version == version.n && it.counted > 0 }
    when {
        run != null -> add("${run.passed} of ${run.counted}" to if (run.worst == StepStatus.Red) StripColor.Red else if (run.worst == StepStatus.Amber) StripColor.Amber else StripColor.Green)
        version.testRunId != null -> add("tested" to StripColor.Neutral)
        else -> Unit
    }
}

/** One timeline row: the node and stem on the left, the card (title, tags, time, note, +/−) on the right */
@Composable
private fun TimelineRow(
    title: String,
    tags: List<Pair<String, StripColor>>,
    time: String,
    note: String,
    counts: String?,
    selected: Boolean,
    checked: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp).fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (selected) accent else MaterialTheme.colorScheme.outline),
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(if (last) Color.Transparent else MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = when {
                checked -> MaterialTheme.colorScheme.tertiaryContainer
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
            border = if (selected) BorderStroke(2.dp, accent) else null,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(14.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    tags.forEach { (text, color) -> Tag(text, color) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        time,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    note + (counts?.let { " · $it" }.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: StripColor) {
    val tint = color.tint()
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The selected row compared: title, part chips, counts, the changed lines, then Open / Compare with… / Delete */
@Composable
private fun CompareView(
    state: SourceLabScreenModel.State,
    current: Int,
    versions: List<LabVersion>,
    canDelete: Boolean,
    baseline: Int?,
    onMakeBaseline: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    onCompareWith: (Int?) -> Unit,
    onDelete: (Int) -> Unit,
) {
    // remembered before the early return (never a remember after a condition)
    var pick by remember { mutableStateOf(false) }
    val n = state.selectedVersion ?: return
    val name = if (n == EDITOR_VERSION) "Your edits" else "v$n"
    Text(
        if (state.diffReady) state.diffTitle.ifBlank { name } else "$name · reading…",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (state.diffReady) {
        val added = state.diff.count { it.kind == '+' }
        val removed = state.diff.count { it.kind == '-' }
        if (state.diffParts.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.diffParts.forEach { p ->
                    Text(
                        "${p.title} +${p.added} −${p.removed}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Text(
            if (added == 0 && removed == 0) {
                "No line differs."
            } else {
                "${state.diffParts.count { it.id.isNotEmpty() }} parts changed · $added lines added · $removed removed"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (added > 0 || removed > 0) DiffLines(diff = LabDiff.compact(state.diff))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (n != EDITOR_VERSION && n != current) {
            Button(onClick = { onOpen(n) }) { Text("Open v$n") }
        }
        Box {
            OutlinedButton(onClick = { pick = true }) { Text("Compare with…") }
            DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                DropdownMenuItem(
                    text = { Text("The one before") },
                    onClick = {
                        pick = false
                        onCompareWith(null)
                    },
                )
                if (n != EDITOR_VERSION) {
                    DropdownMenuItem(
                        text = { Text("The code in the editor") },
                        onClick = {
                            pick = false
                            onCompareWith(EDITOR_VERSION)
                        },
                    )
                }
                versions.filter { it.n != n }.forEach { v ->
                    DropdownMenuItem(
                        text = { Text("v${v.n} · ${v.note}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            pick = false
                            onCompareWith(v.n)
                        },
                    )
                }
            }
        }
        if (n != EDITOR_VERSION && n != baseline) {
            OutlinedButton(onClick = { onMakeBaseline(n) }) { Text("Make v$n the baseline") }
        }
        if (canDelete) {
            OutlinedButton(onClick = { onDelete(n) }) { Text("Delete v$n", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * KMK (r106): changed lines as the mocks draw them: the line number, then the line on a red (removed) or green
 * (added) band; context lines dimmed. Capped at [maxLines]; scrolls sideways.
 */
@Composable
internal fun DiffLines(diff: List<History.DiffLine>, maxLines: Int = 400, modifier: Modifier = Modifier) {
    val red = StripColor.Red.tint()
    val green = StripColor.Green.tint()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                diff.take(maxLines).forEach { line ->
                    val number = when (line.kind) {
                        '-' -> line.lineA
                        else -> line.lineB ?: line.lineA
                    }
                    Row(
                        modifier = Modifier.background(
                            when (line.kind) {
                                '+' -> green.copy(alpha = 0.14f)
                                '-' -> red.copy(alpha = 0.14f)
                                else -> Color.Transparent
                            },
                        ),
                    ) {
                        Text(
                            number?.toString().orEmpty(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 18.sp,
                            color = dim.copy(alpha = 0.6f),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.width(40.dp).padding(end = 10.dp),
                        )
                        Text(
                            "${line.kind} ${line.text}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = when (line.kind) {
                                '+' -> green
                                '-' -> red
                                else -> dim
                            },
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                }
                if (diff.size > maxLines) {
                    Text(
                        "… ${diff.size - maxLines} more lines",
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                        modifier = Modifier.padding(start = 50.dp, top = 4.dp),
                    )
                }
            }
        }
    }
}
