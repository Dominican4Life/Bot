package eu.kanade.tachiyomi.ui.sourcelab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.jssource.lab.ConsoleRow
import eu.kanade.tachiyomi.jssource.lab.StepId
import eu.kanade.tachiyomi.jssource.lab.TestRun
import eu.kanade.tachiyomi.jssource.lab.TestRuns
import eu.kanade.tachiyomi.jssource.lab.TestStep
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KMK (r102): the Test Run console, docked at the bottom of the Test pane (replaces the "Manual · Console" pane).
 * Every console line of the newest run, live while it runs ([TestRuns.consoles]), else the run's saved console
 * ([TestRuns.loadConsole]): grouped under step headers, each with its time since the run started and its level
 * colour. A request row reads `GET /path → 200 · 340 ms` and opens its headers (and a failed reply's first 2 KB);
 * an error with a `source.js:N` stack line opens that line of the code. Filter chips, a search box, Copy (the lines
 * shown) and Share (the whole console as a .txt, the way the Logs screen shares its file).
 */
@Composable
internal fun TestConsole(
    sourceKey: String?,
    sourceName: String,
    run: TestRun?,
    steps: List<TestStep>,
    onRevealLine: (Int) -> Unit,
    maxListHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lives by TestRuns.consoles.collectAsState()
    val live = sourceKey?.let { lives[it] }
    val saved by produceState(initialValue = emptyList<ConsoleRow>(), run?.key, run?.id) {
        val r = run
        value = if (r == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { runCatching { TestRuns.loadConsole(r) }.getOrDefault(emptyList()) }
        }
    }
    val liveRows = live?.takeIf { it.running || it.rows.isNotEmpty() }?.rows
    val rows = liveRows ?: saved
    val liveRunning = live?.running == true

    var open by rememberSaveable { mutableStateOf(false) }
    var chip by rememberSaveable { mutableStateOf(ConsoleFilter.All) }
    var search by rememberSaveable { mutableStateOf("") }
    // r102 fix: expanded rows are remembered by a stable id (time, step, level, text, nth such line), not by position:
    // live rows becoming the saved ones, or a trimmed live list, kept the old positions open on other lines
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val ids = remember(rows) {
        val seen = HashMap<String, Int>()
        rows.map { r ->
            val base = "${r.ms}|${r.step}|${r.level}|${r.text.hashCode()}"
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            "$base#$n"
        }
    }

    val names = remember(steps) { steps.filter { it.id.isNotEmpty() }.associate { it.id to it.name } }
    val errors = remember(rows) { rows.count { it.level == "E" } }
    val warnings = remember(rows) { rows.count { it.level == "W" } }
    val shown = remember(rows, chip, search) {
        rows.withIndex().filter { (_, r) ->
            chip.matches(r) && (search.isBlank() || r.text.contains(search, ignoreCase = true))
        }
    }
    val entries = remember(shown, names, ids) {
        buildList<ConsoleEntry> {
            var last: String? = null
            shown.forEach { (index, r) ->
                if (r.step != last) {
                    add(ConsoleEntry.Header(stepLabel(r.step, names)))
                    last = r.step
                }
                add(ConsoleEntry.Line(ids.getOrElse(index) { "i$index" }, r))
            }
        }
    }
    val listState = rememberLazyListState()
    val chipsScroll = rememberScrollState()
    LaunchedEffect(entries.size, open) {
        if (open && liveRunning && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                "Console · ${rows.size} ${if (rows.size == 1) "line" else "lines"}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (errors > 0) {
                Text(
                    " · $errors ${if (errors == 1) "error" else "errors"}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
            if (warnings > 0) {
                Text(
                    " · $warnings ${if (warnings == 1) "warning" else "warnings"}",
                    style = MaterialTheme.typography.titleSmall,
                    color = StripColor.Amber.tint(),
                    maxLines = 1,
                )
            }
            if (liveRunning) {
                Text(" · live", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    if (shown.isEmpty()) {
                        context.toast("Nothing to copy")
                    } else {
                        copyLines(context, shown.joinToString("\n") { (_, r) -> lineText(r, stepLabel(r.step, names)) }, shown.size)
                    }
                },
            ) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy the lines shown") }
            IconButton(
                onClick = {
                    if (rows.isEmpty()) {
                        context.toast("The console is empty")
                    } else {
                        shareConsole(context, sourceName, rows, names)
                    }
                },
            ) { Icon(Icons.Outlined.Share, contentDescription = "Share the console") }
            Icon(
                if (open) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                contentDescription = if (open) "Hide the console" else "Show the console",
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (open) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(chipsScroll)
                    .padding(horizontal = 12.dp),
            ) {
                ConsoleFilter.entries.forEach { f ->
                    FilterChip(selected = chip == f, onClick = { chip = f }, label = { Text(f.label) })
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search the console") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            when {
                rows.isEmpty() -> Text(
                    if (liveRunning) "Waiting for the first line…" else "Run the test: every console line, request and error of the run shows here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                entries.isEmpty() -> Text(
                    "No line matches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight)
                        .padding(bottom = 6.dp),
                ) {
                    items(entries) { entry ->
                        when (entry) {
                            is ConsoleEntry.Header -> Text(
                                entry.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                            )
                            is ConsoleEntry.Line -> ConsoleLine(
                                row = entry.row,
                                expanded = expanded[entry.id] == true,
                                onToggle = { expanded[entry.id] = expanded[entry.id] != true },
                                onRevealLine = onRevealLine,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal enum class ConsoleFilter(val label: String) {
    All("All"),
    Errors("Errors"),
    Warnings("Warnings"),
    Network("Network"),
    Logs("Logs"),
    ;

    fun matches(row: ConsoleRow): Boolean = when (this) {
        All -> true
        Errors -> row.level == "E"
        Warnings -> row.level == "W"
        Network -> row.level == "N"
        Logs -> row.level == "I" || row.level == "D"
    }
}

private sealed interface ConsoleEntry {
    data class Header(val name: String) : ConsoleEntry
    data class Line(val id: String, val row: ConsoleRow) : ConsoleEntry
}

@Composable
private fun ConsoleLine(row: ConsoleRow, expanded: Boolean, onToggle: () -> Unit, onRevealLine: (Int) -> Unit) {
    val net = if (row.level == "N") TestRuns.parseNetLine("[net] " + row.text) else null
    val jsLine = if (row.level == "E" || row.level == "W") jsLineOf(row.text + "\n" + row.detail.orEmpty()) else null
    val failedRequest = net != null && (net.status == null || net.status >= 400)
    val color = when {
        row.level == "E" || failedRequest -> MaterialTheme.colorScheme.error
        row.level == "W" -> StripColor.Amber.tint()
        row.level == "D" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val body = if (net != null) {
        "${net.method} ${pathOf(net.url)} → ${net.status ?: net.error ?: "no reply"}" +
            (if (net.ms > 0) " · ${net.ms} ms" else "") +
            (if (net.viaBrowser) " · browser" else "")
    } else {
        row.text
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (jsLine != null) onRevealLine(jsLine) else onToggle() }
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                timeText(row.ms),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(52.dp),
            )
            Text(
                row.level,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = levelColor(row.level, color),
                modifier = Modifier.width(16.dp),
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.detail != null && jsLine == null) {
                Text(
                    if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (jsLine != null) {
            Text(
                "line $jsLine · tap to open it in the code",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 68.dp),
            )
        }
        if (expanded) {
            if (net != null && net.url.isNotEmpty()) {
                Text(
                    net.url,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 68.dp, top = 2.dp),
                )
            }
            row.detail?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 68.dp, top = 2.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun levelColor(level: String, lineColor: Color): Color = when (level) {
    "N" -> MaterialTheme.colorScheme.primary
    else -> lineColor
}

private val JS_LINE = Regex("""source\.js:(\d+)""")

/** The source's own line from a QuickJS stack (`at list (source.js:381:38)`) */
private fun jsLineOf(text: String): Int? = JS_LINE.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun pathOf(url: String): String {
    val after = url.substringAfter("://", url)
    val slash = after.indexOf('/')
    return (if (slash >= 0) after.substring(slash) else "/").take(90)
}

private fun timeText(ms: Long): String = if (ms < 0) "" else "+" + "%.1f".format(Locale.US, ms / 1000.0) + " s"

private fun stepLabel(step: String, names: Map<String, String>): String = names[step] ?: when {
    step.isEmpty() -> "Before the steps"
    step == TestRuns.STEP_FILTERS -> "Filters"
    step == TestRuns.STEP_SETTINGS -> "Settings"
    step == StepId.VIDEO -> "Video"
    step.startsWith("tab:") -> "Tab ${step.removePrefix("tab:")}"
    else -> step.replaceFirstChar { it.uppercase() }
}

private fun lineText(row: ConsoleRow, stepName: String): String =
    "${timeText(row.ms).padEnd(8)} ${row.level} [$stepName] ${row.text}"

private fun copyLines(context: Context, text: String, count: Int) {
    runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: throw IllegalStateException("No clipboard")
        clipboard.setPrimaryClip(ClipData.newPlainText("Test Run console", text))
        context.toast("Copied $count ${if (count == 1) "line" else "lines"}")
    }.onFailure { context.toast(it.message ?: "Couldn't copy the console") }
}

/** The whole console as a .txt in the cache folder, through the share sheet (as the Logs screen shares its file) */
private fun shareConsole(context: Context, name: String, rows: List<ConsoleRow>, names: Map<String, String>) {
    runCatching {
        val safe = name.ifBlank { "source" }.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val file = File(context.cacheDir, "$safe-test-console.txt")
        val text = buildString {
            append("Test Run console · ").append(name.ifBlank { "source" }).append(" · ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append(rows.size).append(" lines · ")
            append(rows.count { it.level == "E" }).append(" errors · ")
            append(rows.count { it.level == "W" }).append(" warnings\n")
            var last: String? = null
            rows.forEach { r ->
                if (r.step != last) {
                    append("\n== ").append(stepLabel(r.step, names)).append(" ==\n")
                    last = r.step
                }
                append(lineText(r, stepLabel(r.step, names))).append('\n')
                r.detail?.let { d -> d.lineSequence().forEach { append("            ").append(it).append('\n') } }
            }
        }
        file.writeText(text)
        context.startActivity(file.getUriCompat(context).toShareIntent(context, type = "text/plain"))
        AppLog.add('I', "lab", "Test Run console shared: ${rows.size} lines of $name")
    }.onFailure { context.toast(it.message ?: "Couldn't share the console") }
}
