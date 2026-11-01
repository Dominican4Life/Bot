package eu.kanade.tachiyomi.ui.diagnostics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.tachiyomi.diagnostics.DiagBudget
import eu.kanade.tachiyomi.diagnostics.DiagCategory
import eu.kanade.tachiyomi.diagnostics.DiagPaths
import eu.kanade.tachiyomi.diagnostics.LogChips
import eu.kanade.tachiyomi.diagnostics.LogEntry
import eu.kanade.tachiyomi.diagnostics.LogLevelFilter
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * KMK (r97): The Log tab (ph-05): the old Logs screen's body — newest first, "N new lines · tap to show", copy
 * limit, select-text box, share as a text file, clear — plus the chips the engine offers ([LogChips]: categories by
 * count, tags such as `probe:mitaku`, `source=` values), the `key=value` search, E lines tinted with a left rule,
 * MARK lines in the primary colour, a "Next problem · N below" button that wraps, a Mark sheet, and a long-press menu
 * (copy
 * the line, move its tag to a category). The list is rebuilt when the owner asks (chip, filter, "new lines"), never
 * on every line that arrives (contract §8 rule 4).
 */
@Composable
internal fun LogTab(model: DiagnosticsScreenModel, header: HeaderSlot) {
    val diag = model.diag
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val version by diag.logVersion.collectAsState()
    val level by model.logLevel.collectAsState()
    val chipKeys by model.logChipKeys.collectAsState()
    val query by model.logQuery.collectAsState()
    val around by model.logAround.collectAsState()
    // The filter lives in the model (the search box is debounced there, 150 ms)
    val filter by model.logFilterFlow.collectAsState()
    // KMK: the lines as they were when opened (or last refreshed); new lines don't redraw the list as they come
    var shownVersion by remember { mutableIntStateOf(diag.logVersion.value) }
    val selecting = remember { mutableStateOf(false) }
    var markSheet by remember { mutableStateOf(false) }
    val waiting = (version - shownVersion).coerceAtLeast(0)
    // KMK (r103): the chips, the filtered lines, the problem positions and the row keys are built on
    // Dispatchers.Default (r96 rule: nothing in composition walks the whole log). The last result stays on screen
    // while the next one is built; the chips are only re-counted when the shown version changes.
    // Builds this visit: count, slowest ms, lines of the slowest; written as one D line when the tab closes (a line
    // written at once would itself show as "1 new lines")
    val buildStats = remember { LongArray(3) }
    val view by produceState(LogView.EMPTY, shownVersion, filter) {
        val previous = value
        val started = System.nanoTime()
        val next = withContext(Dispatchers.Default) {
            val chips = if (previous.loaded && previous.version == shownVersion) previous.chips else diag.logChips()
            val lines = diag.logLines(filter)
            val problems = ArrayList<Int>()
            lines.forEachIndexed { index, line -> if (line.isProblem) problems.add(index) }
            LogView(shownVersion, chips, lines, problems.toIntArray(), logLineKeys(lines), loaded = true)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        buildStats[0]++
        if (ms >= buildStats[1]) {
            buildStats[1] = ms
            buildStats[2] = next.lines.size.toLong()
        }
        value = next
    }
    DisposableEffect(Unit) {
        onDispose {
            if (buildStats[0] > 0) {
                AppLog.add(
                    'D',
                    "diag",
                    "Log tab: ${buildStats[0]} list builds off the main thread, slowest ${buildStats[1]} ms " +
                        "(${buildStats[2]} lines)",
                )
            }
        }
    }
    val chips = view.chips
    val shown = view.lines
    val problemIndices = view.problems
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Opened because a W/E arrived since the last visit: scroll to the newest problem once (read before the
    // effects below mark the log as seen)
    val jumpToProblem = remember { model.scrollToNewestProblem }
    var jumped by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        model.markLogSeen()
        onDispose { model.markLogSeen() }
    }
    LaunchedEffect(view) {
        if (view.loaded && jumpToProblem && !jumped) {
            jumped = true
            problemIndices.firstOrNull()?.let { listState.scrollToItem(it) }
        }
    }
    val selectLines = remember(shown) { shown.take(SELECT_LIMIT) }

    fun copyShown() {
        // The clipboard holds about 1 MB at most; a bigger copy crashed the app. Copy the newest lines that fit.
        var size = 0
        val fitting = shown.takeWhile { line ->
            size += line.text.length + 1
            size <= DiagBudget.CLIPBOARD_LIMIT
        }
        clipboard.setText(AnnotatedString(fitting.asReversed().joinToString("\n") { it.text }))
        if (fitting.size < shown.size) {
            context.toast("Copied the newest ${fitting.size} of ${shown.size} lines. Use Share for all of them")
        } else {
            context.toast("Copied ${shown.size} lines")
        }
    }

    fun shareShown() {
        runCatching {
            val file = File(context.cacheDir, DiagPaths.LOG_TEXT)
            file.writeText(shown.asReversed().joinToString("\n") { it.text })
            context.startActivity(file.getUriCompat(context).toShareIntent(context, type = "text/plain"))
        }.onFailure { context.toast(it.message ?: "Couldn't share the logs") }
    }

    DisposableEffect(shown, chips) {
        header.subtitle.value =
            "Log · ${formatCount(shown.size)} lines · ${chips.marks} marks · ${chips.problems} problems"
        header.actions.value = {
            IconButton(onClick = { markSheet = true }) { Icon(Icons.Outlined.Flag, contentDescription = "Mark") }
            IconButton(onClick = { copyShown() }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy") }
            IconButton(
                onClick = {
                    diag.clearLog()
                    shownVersion = diag.logVersion.value
                },
            ) { Icon(Icons.Outlined.Delete, contentDescription = "Clear") }
        }
        onDispose {
            header.subtitle.value = null
            header.actions.value = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            ChipsRow(
                chips = chips,
                level = level,
                onLevel = model::setLogLevel,
                selected = chipKeys,
                onToggle = model::toggleLogChip,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                SourcesSearchBox(
                    searchQuery = query,
                    onChangeSearchQuery = { model.setLogQuery(it.orEmpty()) },
                    placeholderText = "Search · tag or text · key=value, e.g. source=kiutaku",
                    modifier = Modifier.weight(1f),
                )
                // KMK: select across lines (the newest ones) in Android's own text box
                IconButton(onClick = { selecting.value = !selecting.value }) {
                    Icon(
                        Icons.Outlined.SelectAll,
                        contentDescription = "Select text",
                        tint = if (selecting.value) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = { shareShown() }) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (around != null) {
                    InputChip(
                        selected = true,
                        onClick = { model.clearLogAround() },
                        label = { Text("around ${formatHms(around!!)} · ±30 s · ×") },
                    )
                }
                if (waiting > 0) {
                    AssistChip(
                        onClick = { shownVersion = diag.logVersion.value },
                        label = { Text("$waiting new lines · tap to show") },
                    )
                }
            }
            if (selecting.value) {
                SelectableLogText(lines = selectLines, modifier = Modifier.fillMaxSize())
            } else {
                LogLineList(
                    lines = shown,
                    keys = view.keys,
                    state = listState,
                    onMoveTag = { tag, category -> diag.setTagCategory(tag, category) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (!selecting.value && problemIndices.isNotEmpty()) {
            // layoutInfo changes on every scroll frame: read it through derivedStateOf so only a change of the
            // visible range recomposes the button, not every pixel
            val lastVisible by remember(listState) {
                derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            }
            val firstVisible by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex } }
            val below = problemIndices.count { it > lastVisible }
            val visible = problemIndices.any { it >= firstVisible && it <= lastVisible }
            if (!(visible && below == 0 && problemIndices.size == 1)) {
                ExtendedFloatingActionButton(
                    text = { Text(if (below > 0) "Next problem · $below below" else "First problem · wraps") },
                    icon = { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) },
                    onClick = {
                        val next = problemIndices.firstOrNull { it > lastVisible } ?: problemIndices.first()
                        scope.launch { listState.animateScrollToItem(next) }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
    }

    if (markSheet) {
        MarkSheet(
            onDismiss = { markSheet = false },
            onMark = { note ->
                markSheet = false
                scope.launch {
                    // The list refreshes once the engine has written the line (logVersion moves), not before
                    val before = diag.logVersion.value
                    diag.mark(note.takeIf { it.isNotBlank() })
                    withTimeoutOrNull(1_000L) { diag.logVersion.first { it > before } }
                    shownVersion = diag.logVersion.value
                    context.toast("Mark written")
                }
            },
        )
    }
}

@Composable
private fun ChipsRow(
    chips: LogChips,
    level: LogLevelFilter,
    onLevel: (LogLevelFilter) -> Unit,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
    ) {
        LogLevelFilter.entries.forEach { f ->
            val problem = f == LogLevelFilter.PROBLEMS && chips.problems > 0
            val label = if (problem) "Problems · ${chips.problems}" else f.label
            FilterChip(
                selected = level == f,
                onClick = { onLevel(f) },
                label = { Text(label) },
                border = if (problem) {
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = level == f,
                        borderColor = DiagColors.problem.copy(alpha = 0.6f),
                    )
                } else {
                    FilterChipDefaults.filterChipBorder(enabled = true, selected = level == f)
                },
            )
        }
        chips.categories.forEach { (category, _) ->
            val key = "cat:${category.name}"
            FilterChip(selected = key in selected, onClick = { onToggle(key) }, label = { Text(category.label) })
        }
        chips.tags.take(12).forEach { (tag, _) ->
            val key = "tag:$tag"
            FilterChip(
                selected = key in selected,
                onClick = { onToggle(key) },
                label = { Text(tag, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            )
        }
        chips.sources.forEach { (source, _) ->
            val key = "src:$source"
            FilterChip(
                selected = key in selected,
                onClick = { onToggle(key) },
                label = { Text("source=$source", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            )
        }
    }
}

/** KMK (r103): what the Log tab shows, built off the main thread */
private class LogView(
    /** The log version the chips were counted at */
    val version: Int,
    val chips: LogChips,
    val lines: List<LogEntry>,
    val problems: IntArray,
    val keys: LongArray,
    val loaded: Boolean,
) {
    companion object {
        val EMPTY = LogView(
            version = -1,
            chips = LogChips(problems = 0, marks = 0, categories = emptyList(), tags = emptyList(), sources = emptyList()),
            lines = emptyList(),
            problems = IntArray(0),
            keys = LongArray(0),
            loaded = false,
        )
    }
}

/** Stable row keys: time + text hash (not the index, which shifts with every refresh); duplicates get bumped */
internal fun logLineKeys(lines: List<LogEntry>): LongArray {
    val seen = HashSet<Long>(lines.size)
    return LongArray(lines.size) { i ->
        var k = lines[i].time * 31 + lines[i].text.hashCode()
        while (!seen.add(k)) k++
        k
    }
}

/** Lines put in the select-text box: more made it slow to lay out */
private const val SELECT_LIMIT = 300

/**
 * KMK: The log as a list: only the lines on screen are drawn. A long line (a stack trace) shows its first lines
 * until tapped; holding a line opens the menu (copy, move the tag to a category).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LogLineList(
    lines: List<LogEntry>,
    /** Row keys built with [logLineKeys] off the main thread; null builds them here (short lists) */
    keys: LongArray? = null,
    state: LazyListState = rememberLazyListState(),
    onMoveTag: ((String, DiagCategory) -> Unit)? = null,
    modifier: Modifier = Modifier,
    fontSize: Int = 12,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val error = MaterialTheme.colorScheme.error
    val errorTint = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    val warn = MaterialTheme.colorScheme.tertiary
    val info = MaterialTheme.colorScheme.onSurface
    val debug = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val verbose = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val primary = MaterialTheme.colorScheme.primary
    val primaryTint = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    // Stable keys: time + text hash (not the index, which shifts with every refresh); duplicates get bumped
    val rowKeys = remember(lines, keys) { keys?.takeIf { it.size == lines.size } ?: logLineKeys(lines) }
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
    ) {
        items(lines.size, key = { index -> rowKeys[index] }) { index ->
            val line = lines[index]
            val id = rowKeys[index]
            val open = id in expanded
            var pickCategory by remember { mutableStateOf(false) }
            val mark = line.isMark
            val probe = line.tag.startsWith("probe:")
            val color = when {
                mark -> primary
                line.level == 'E' -> error
                line.level == 'W' -> warn
                probe -> DiagColors.probe
                line.level == 'I' -> info
                line.level == 'V' -> verbose
                else -> debug
            }
            val rule = when {
                mark -> primary
                line.level == 'E' -> error
                else -> Color.Transparent
            }
            val tint = when {
                mark -> primaryTint
                line.level == 'E' -> errorTint
                else -> Color.Transparent
            }
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tint)
                        .combinedClickable(
                            onClick = { expanded = if (open) expanded - id else expanded + id },
                            onLongClick = { menuFor = id },
                        ),
                ) {
                    Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(rule))
                    Text(
                        text = line.text,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 3).sp,
                        fontWeight = if (mark) FontWeight.Bold else FontWeight.Normal,
                        maxLines = if (open) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                DropdownMenu(expanded = menuFor == id, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text("Copy line") },
                        onClick = {
                            clipboard.setText(AnnotatedString(line.text.take(DiagBudget.CLIPBOARD_LIMIT)))
                            context.toast("Line copied")
                            menuFor = null
                        },
                    )
                    if (onMoveTag != null) {
                        DropdownMenuItem(
                            text = { Text("Move tag ${line.tag} to category…") },
                            onClick = { pickCategory = !pickCategory },
                        )
                        if (pickCategory) {
                            DiagCategory.entries.filter { it != line.category }.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text("    ${category.label}") },
                                    onClick = {
                                        onMoveTag(line.tag, category)
                                        context.toast("${line.tag} → ${category.label}")
                                        menuFor = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableLogText(lines: List<LogEntry>, modifier: Modifier = Modifier) {
    val error = MaterialTheme.colorScheme.error.toArgb()
    val warn = MaterialTheme.colorScheme.tertiary.toArgb()
    val info = MaterialTheme.colorScheme.onSurface.toArgb()
    val debug = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f).toArgb()
    val text = remember(lines, error, warn, info, debug) {
        val builder = android.text.SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) builder.append("\n")
            val start = builder.length
            builder.append(line.text)
            val color = when (line.level) {
                'E' -> error
                'W' -> warn
                'I' -> info
                else -> debug
            }
            builder.setSpan(
                android.text.style.ForegroundColorSpan(color),
                start,
                builder.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        builder
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            androidx.core.widget.NestedScrollView(context).apply {
                isFillViewport = true
                addView(
                    android.widget.TextView(context).apply {
                        setTextIsSelectable(true)
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = 12f
                        setLineSpacing(0f, 1.25f)
                        val pad = (12 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad / 2, pad, pad * 2)
                    },
                )
            }
        },
        update = { scroll ->
            val view = scroll.getChildAt(0) as android.widget.TextView
            // Only when the lines changed: setting it again would drop what's being selected
            if (view.tag !== text) {
                view.tag = text
                view.text = text
            }
        },
    )
}

/** The Mark sheet: an optional note and "Mark" → `=== MARK: note ===` in the log and a flag on the graph. */
@Composable
internal fun MarkSheet(onDismiss: () -> Unit, onMark: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Mark this moment", style = MaterialTheme.typography.titleMedium)
            Caption("Writes === MARK: note === into the log and a flag on the graph timeline.")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("What just happened (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onMark(note) }) { Text("Mark") }
            }
        }
    }
}
