package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.TestRuns
import eu.kanade.tachiyomi.jssource.lab.TestStep
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.codeeditor.EditorTestPanel
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import tachiyomi.domain.source.interactor.GetRemoteManga

/**
 * KMK (r96): the Test pane (D§2.5): the Test Run as step rows (status, name, count, ms, one evidence line, a
 * timing bar relative to the slowest step), "What came back" (covers, first chapters, the video lines) and
 * "Against the previous run". The editor's manual tests and console sit behind the "Manual" chip.
 */
@Composable
internal fun TestPane(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editor: SourceEditorScreenModel,
    editorState: SourceEditorScreenModel.State,
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var manual by rememberSaveable { mutableStateOf(false) }
    val navigator = LocalNavigator.current
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            FilterChip(selected = !manual, onClick = { manual = false }, label = { Text("Test Run") })
            FilterChip(selected = manual, onClick = { manual = true }, label = { Text("Manual · Console") })
        }
        HorizontalDivider()
        if (manual) {
            EditorTestPanel(state = editorState, model = editor, query = query, onQuery = onQuery, modifier = Modifier.fillMaxSize())
        } else {
            val steps = if (state.running || state.runSteps.isNotEmpty()) state.runSteps else state.lastRun?.steps.orEmpty()
            val slowest = steps.maxOfOrNull { it.ms }?.coerceAtLeast(1L) ?: 1L
            val run = state.lastRun
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "query") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQuery,
                        label = { Text("Search word (empty: the first title's first word)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "run-head") {
                    LabCard(title = "Test Run · ${editorState.name.ifBlank { "source" }}") {
                        Text(
                            text = when {
                                state.running -> "running · ${steps.size} steps so far"
                                run == null -> "not run yet · Run tests the source's methods in order on the editor's test copy"
                                else -> "${SourceLabScreenModel.agoText(run.startedAt)} · ${"%.1f".format(run.totalMs / 1000.0)} s · ${run.passed} of ${run.counted} passed" +
                                    (if (run.query.isNotBlank()) " · \"${run.query}\"" else "") +
                                    (run.rulesVersion?.let { " · rules v$it" }.orEmpty())
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (steps.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        steps.forEachIndexed { index, step ->
                            if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            StepRow(step = step, slowest = slowest, onOpenLine = { model.setTab(LabTab.Code) })
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            if (state.running) {
                                OutlinedButton(onClick = model::cancelTest) {
                                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("  Stop")
                                }
                            } else {
                                Button(onClick = { model.runTest(editor, query) }) {
                                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(if (run == null) "  Run" else "  Run again")
                                }
                            }
                            OutlinedButton(onClick = { model.setTab(LabTab.Report) }, enabled = run != null) {
                                Icon(
                                    Icons.Outlined.BugReport,
                                    contentDescription = null,
                                    tint = if (run?.worst == StepStatus.Red) StripColor.Red.tint() else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text("  Issue report")
                            }
                            val source = state.source
                            if (source != null && editorState.saved) {
                                OutlinedButton(
                                    onClick = {
                                        val id = editor.sourceId(source.key)
                                        navigator?.push(if (source.anime) AnimeSourceScreen(id) else BrowseSourceScreen(id, GetRemoteManga.QUERY_POPULAR))
                                    },
                                ) { Text("Open in Browse") }
                            }
                        }
                    }
                }
                if (steps.any { it.items.isNotEmpty() }) {
                    item(key = "came-back") {
                        LabCard(title = "What came back") {
                            steps.filter { it.items.isNotEmpty() }.forEach { step -> CameBack(step) }
                        }
                    }
                }
                if (run != null && state.previousRun != null) {
                    item(key = "previous") {
                        val diffs = TestRuns.compare(state.previousRun, run)
                        LabCard(title = "Against the previous run · ${SourceLabScreenModel.agoText(state.previousRun.startedAt)}") {
                            diffs.forEach { d ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(d.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(
                                        text = if (d.was == null) {
                                            "new → ${d.now.status.name.lowercase()} ${d.now.count}"
                                        } else {
                                            "${d.was.status.name.lowercase()} ${d.was.count} · ${d.was.ms} ms → ${d.now.status.name.lowercase()} ${d.now.count} · ${d.now.ms} ms"
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
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: TestStep, slowest: Long, onOpenLine: () -> Unit) {
    val tint = step.status.strip().tint()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tint),
            )
            Spacer(Modifier.width(10.dp))
            Text(step.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(
                text = when (step.status) {
                    StepStatus.Running -> "running"
                    StepStatus.Skipped -> "skipped"
                    else -> countWords(step)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (step.status != StepStatus.Running && step.status != StepStatus.Skipped) {
                Text(
                    text = if (step.ms >= 1000) "%.1f s".format(step.ms / 1000.0) else "${step.ms} ms",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (step.evidence.isNotBlank()) {
            Text(
                step.evidence,
                style = MaterialTheme.typography.bodySmall,
                color = if (step.status == StepStatus.Red || step.status == StepStatus.Amber) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp),
            )
        }
        if (step.status != StepStatus.Running && step.status != StepStatus.Skipped) {
            val fraction = (step.ms.toFloat() / slowest.toFloat()).coerceIn(0.02f, 1f)
            Row(modifier = Modifier.padding(start = 22.dp, top = 6.dp).fillMaxWidth(0.5f)) {
                Box(
                    modifier = Modifier
                        .weight(fraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint),
                )
                if (fraction < 1f) {
                    Box(
                        modifier = Modifier
                            .weight(1f - fraction)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    )
                }
            }
        }
        if (step.partIds.isNotEmpty() && step.status == StepStatus.Red) {
            Text(
                "in ${step.partIds.first()} · tap Code to read it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 22.dp, top = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onOpenLine),
            )
        }
    }
}

private fun countWords(step: TestStep): String = when (step.id) {
    "title" -> if (step.count > 0) "${step.count} of 8 fields" else ""
    "chapters" -> step.count.toString()
    "hosters" -> "${step.count} ${if (step.count == 1) "server" else "servers"}"
    "video" -> "${step.count} of ${step.items.size} played"
    "pages" -> "${step.count} pages"
    else -> "${step.count} titles"
}

/** The covers of a list step, the first three chapters, the video lines */
@Composable
private fun CameBack(step: TestStep) {
    val coversScroll = rememberScrollState()
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            "${step.name} · ${step.items.size}${if (step.count > step.items.size) " of ${step.count}" else ""}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        when (step.items.first().kind) {
            "entry" -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .horizontalScroll(coversScroll),
            ) {
                step.items.take(12).forEach { item ->
                    Column(modifier = Modifier.width(72.dp)) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 72.dp, height = 100.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        Text(item.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            else -> step.items.take(3).forEach { item ->
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
    }
}
