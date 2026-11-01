package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
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
 * a line diff of the selected version against the code in the editor, and Restore vN (a new version through the
 * manager's save; nothing is deleted).
 */
@Composable
internal fun HistoryPane(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editor: SourceEditorScreenModel,
    editorState: SourceEditorScreenModel.State,
    modifier: Modifier = Modifier,
) {
    var confirmRestore by remember { mutableStateOf<Int?>(null) }
    val versions = state.history.versions.asReversed()
    val format = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        item(key = "head") {
            Text(
                text = when {
                    versions.isEmpty() -> "No versions yet. Every Save keeps one here (30 at most; the one the code was generated from is never trimmed)."
                    else -> "${versions.size} versions · newest v${versions.first().n}" +
                        (state.history.generatedFrom?.let { " · generated at v$it" }.orEmpty()) +
                        if (editorState.dirty) " · the editor has unsaved changes" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(versions, key = { it.n }) { version ->
            VersionRow(
                version = version,
                newest = version.n == versions.first().n,
                generatedFrom = version.n == state.history.generatedFrom,
                selected = state.selectedVersion == version.n,
                time = format.format(Date(version.time)),
                onClick = { model.selectVersion(if (state.selectedVersion == version.n) null else version.n, editorState.code) },
                onRestore = { confirmRestore = version.n },
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
                    "The code of v$n is saved as a new version (the newer versions stay in the list). " +
                        "The source's settings, test copy and library titles are untouched." +
                        if (editorState.dirty) "\n\nThe editor's unsaved changes are replaced." else "",
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
}

@Composable
private fun VersionRow(
    version: LabVersion,
    newest: Boolean,
    generatedFrom: Boolean,
    selected: Boolean,
    time: String,
    onClick: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            "v${version.n}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
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
                    if (newest) append(" · current")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!newest) {
            OutlinedButton(onClick = onRestore) { Text("Restore") }
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
