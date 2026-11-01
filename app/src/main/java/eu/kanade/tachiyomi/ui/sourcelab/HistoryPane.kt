package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.jssource.lab.History
import eu.kanade.tachiyomi.jssource.lab.LabVersion
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KMK (r96): the History pane (D§2.9): every version (note, time, +added −removed, the Test Run at that time),
 * a line diff of the selected version against the code in the editor, and Restore vN.
 *
 * r98: Restore makes vN the source's code and marks it **current** (no new version; the list says which one is
 * current); a later edit + Save appends after the newest as before. Unsaved edits are asked about first
 * ([askBeforeReplacing], the editor's Save / Discard / Cancel). Delete removes versions (one per row, or several
 * after a long press), never the current one nor the one the code was generated from, after a dialog that names them.
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
    val deletable: (Int) -> Boolean = { n -> n != current && n != state.history.generatedFrom }
    // Versions gone from the list (deleted, trimmed) leave the selection
    val shownSelection = selection.filter { n -> versions.any { it.n == n } && deletable(n) }.toSet()
    val format = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
    val restore: (Int) -> Unit = { n ->
        if (editorState.dirty) {
            // The editor's own dialog: Save (then restore), Discard (then restore), Cancel
            askBeforeReplacing("Restoring v$n") { model.restore(editor, n) }
        } else {
            confirmRestore = n
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        item(key = "head") {
            Text(
                text = when {
                    versions.isEmpty() -> "No versions yet. Every Save keeps one here (30 at most; the one the code was generated from is never trimmed)."
                    else -> "${versions.size} versions · current v$current" +
                        (if (versions.first().n != current) " · newest v${versions.first().n}" else "") +
                        (state.history.generatedFrom?.let { " · generated at v$it" }.orEmpty()) +
                        (if (editorState.dirty) " · the editor has unsaved changes" else "") +
                        (if (versions.size > 1 && shownSelection.isEmpty()) " · long-press to select several" else "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (shownSelection.isNotEmpty()) {
            item(key = "selection") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
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
        items(versions, key = { it.n }) { version ->
            val canDelete = deletable(version.n)
            VersionRow(
                version = version,
                current = version.n == current,
                generatedFrom = version.n == state.history.generatedFrom,
                selected = state.selectedVersion == version.n,
                checked = version.n in shownSelection,
                canDelete = canDelete,
                time = format.format(Date(version.time)),
                onClick = {
                    if (shownSelection.isNotEmpty()) {
                        if (canDelete) selection = if (version.n in shownSelection) shownSelection - version.n else shownSelection + version.n
                    } else {
                        model.selectVersion(if (state.selectedVersion == version.n) null else version.n, editorState.code)
                    }
                },
                onLongClick = {
                    if (canDelete) selection = if (version.n in shownSelection) shownSelection - version.n else shownSelection + version.n
                },
                onRestore = { restore(version.n) },
                onDelete = { confirmDelete = listOf(version.n) },
            )
        }
        val selected = state.selectedVersion
        if (selected != null) {
            item(key = "diff") {
                DiffCard(
                    title = "v$selected → the editor" + if (state.diff.isEmpty()) " (reading…)" else "",
                    diff = state.diff,
                )
            }
        }
    }
    confirmRestore?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore v$n?") },
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
                ) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
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

@Composable
private fun VersionRow(
    version: LabVersion,
    current: Boolean,
    generatedFrom: Boolean,
    selected: Boolean,
    checked: Boolean,
    canDelete: Boolean,
    time: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    checked -> MaterialTheme.colorScheme.secondaryContainer
                    selected -> MaterialTheme.colorScheme.surfaceContainerHigh
                    current -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else -> Color.Transparent
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            "v${version.n}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(44.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(version.note, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = buildString {
                    append(time)
                    append(" · +${version.added} −${version.removed}")
                    if (version.testRunId != null) append(" · tested")
                    if (generatedFrom) append(" · generated")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) {
            Text(
                "Current",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        } else {
            OutlinedButton(onClick = onRestore) { Text("Restore") }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete v${version.n}")
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}

/** The mono diff card: '-' lines in red, '+' lines in green, context dimmed; capped at 400 lines */
@Composable
internal fun DiffCard(title: String, diff: List<History.DiffLine>) {
    val red = StripColor.Red.tint()
    val green = StripColor.Green.tint()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val shown = remember(diff) { compact(diff).take(400) }
    LabCard(title = title) {
        Text(
            "${diff.count { it.kind == '+' }} added · ${diff.count { it.kind == '-' }} removed",
            style = MaterialTheme.typography.bodySmall,
            color = dim,
        )
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                shown.forEach { line ->
                    Text(
                        text = "${line.kind} ${line.text}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = when (line.kind) {
                            '+' -> green
                            '-' -> red
                            else -> dim
                        },
                    )
                }
            }
        }
        if (diff.size > shown.size && shown.size >= 400) {
            Text("… ${diff.size - shown.size} more lines", style = MaterialTheme.typography.bodySmall, color = dim)
        }
    }
}

/** Keeps two lines of context around every change (the rest reads as `…`) */
private fun compact(diff: List<History.DiffLine>): List<History.DiffLine> {
    if (diff.none { it.kind != ' ' }) return diff.take(40)
    val keep = BooleanArray(diff.size)
    diff.forEachIndexed { i, line ->
        if (line.kind != ' ') for (j in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(diff.size - 1)) keep[j] = true
    }
    val out = ArrayList<History.DiffLine>()
    var gap = false
    diff.forEachIndexed { i, line ->
        if (keep[i]) {
            out.add(line)
            gap = false
        } else if (!gap) {
            out.add(History.DiffLine(' ', "…", null, null))
            gap = true
        }
    }
    return out
}
