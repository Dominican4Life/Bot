package eu.kanade.tachiyomi.ui.logs

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.presentation.core.components.material.Scaffold
import java.io.File

/**
 * KMK: Everything the app logged (requests, sources, library updates, extension loading, crashes), newest first,
 * with All / Info / Problems filters, search, copy and clear. Opened from the bug button on the navigation rail.
 */
class LogsScreen : Screen() {

    private enum class Filter(val label: String) { All("All"), Info("Info"), Problems("Problems") }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        val version by AppLog.version.collectAsState()
        var filter by rememberSaveable { mutableStateOf(Filter.All) }
        var query by rememberSaveable { mutableStateOf("") }
        // KMK: the lines as they were when opened (or last refreshed). New lines don't redraw the list as they come:
        // the app writes many a second, and rebuilding everything each time froze the screen
        var shownVersion by remember { mutableIntStateOf(AppLog.version.value) }
        var selecting by rememberSaveable { mutableStateOf(false) }
        val all = remember(shownVersion) { AppLog.snapshot().asReversed() }
        val waiting = (version - shownVersion).coerceAtLeast(0)
        val shown = remember(all, filter, query) {
            all.filter { line ->
                when (filter) {
                    Filter.All -> true
                    Filter.Info -> line.isInfo
                    Filter.Problems -> line.isProblem
                } && (query.isBlank() || line.message.contains(query, true) || line.tag.contains(query, true))
            }
        }

        Scaffold { padding ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 980.dp)
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                            Text("Logs", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "${all.size} lines, newest first",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                // The clipboard holds about 1 MB at most; a bigger copy crashed the app.
                                // Copy the newest lines that fit and point to Share for the whole log.
                                var size = 0
                                val fitting = shown.takeWhile { line ->
                                    size += AppLog.format(line).length + 1
                                    size <= CLIPBOARD_LIMIT
                                }
                                clipboard.setText(AnnotatedString(fitting.asReversed().joinToString("\n") { AppLog.format(it) }))
                                if (fitting.size < shown.size) {
                                    context.toast("Copied the newest ${fitting.size} of ${shown.size} lines. Use Share for all of them")
                                } else {
                                    context.toast("Copied ${shown.size} lines")
                                }
                            },
                        ) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy") }
                        // The whole log as a text file, for any size
                        IconButton(
                            onClick = {
                                runCatching {
                                    val file = File(context.cacheDir, "komikku-logs.txt")
                                    file.writeText(shown.asReversed().joinToString("\n") { AppLog.format(it) })
                                    val uri = file.getUriCompat(context)
                                    context.startActivity(uri.toShareIntent(context, type = "text/plain"))
                                }.onFailure { context.toast(it.message ?: "Couldn't share the logs") }
                            },
                        ) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
                        // KMK: select across lines (the newest ones) in Android's own text box
                        IconButton(onClick = { selecting = !selecting }) {
                            Icon(
                                Icons.Outlined.SelectAll,
                                contentDescription = "Select text",
                                tint = if (selecting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                AppLog.clear()
                                shownVersion = AppLog.version.value
                            },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Clear")
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        Filter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                        SourcesSearchBox(
                            searchQuery = query,
                            onChangeSearchQuery = { query = it.orEmpty() },
                            placeholderText = "Search the log",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (waiting > 0) {
                        AssistChip(
                            onClick = { shownVersion = AppLog.version.value },
                            label = { Text("$waiting new lines · tap to show") },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    if (selecting) {
                        // KMK: Android's own text view, with the newest lines only so it stays quick
                        SelectableLogText(lines = remember(shown) { shown.take(SELECT_LIMIT) }, modifier = Modifier.fillMaxSize())
                    } else {
                        LogLineList(lines = shown, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** Lines shown at most in the selectable text (Copy and Share still take everything) */
private const val SHOWN_LIMIT = 4000

/** Lines put in the select-text box: more made it slow to lay out */
private const val SELECT_LIMIT = 300

/**
 * KMK: The log as a list: only the lines on screen are drawn. A long line (a stack trace) shows its first lines
 * until tapped; holding a line copies it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LogLineList(lines: List<AppLog.Line>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val error = MaterialTheme.colorScheme.error
    val warn = MaterialTheme.colorScheme.tertiary
    val info = MaterialTheme.colorScheme.onSurface
    val debug = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
    ) {
        items(lines.size, key = { index -> "${lines[index].time}_${index}" }) { index ->
            val line = lines[index]
            val text = remember(line) { AppLog.format(line) }
            val id = line.time * 31 + index
            val open = id in expanded
            Text(
                text = text,
                color = when (line.level) {
                    'E' -> error
                    'W' -> warn
                    'I' -> info
                    else -> debug
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = if (open) Int.MAX_VALUE else 6,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { expanded = if (open) expanded - id else expanded + id },
                        onLongClick = {
                            clipboard.setText(AnnotatedString(text.take(CLIPBOARD_LIMIT)))
                            context.toast("Line copied")
                        },
                    )
                    .padding(vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun SelectableLogText(lines: List<AppLog.Line>, modifier: Modifier = Modifier) {
    val error = MaterialTheme.colorScheme.error.toArgb()
    val warn = MaterialTheme.colorScheme.tertiary.toArgb()
    val info = MaterialTheme.colorScheme.onSurface.toArgb()
    val debug = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f).toArgb()
    val text = remember(lines, error, warn, info, debug) {
        val builder = android.text.SpannableStringBuilder()
        lines.take(SHOWN_LIMIT).forEachIndexed { index, line ->
            if (index > 0) builder.append("\n")
            val start = builder.length
            builder.append(AppLog.format(line))
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
    androidx.compose.ui.viewinterop.AndroidView(
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

/** Characters copied at most: well under the clipboard's 1 MB limit */
private const val CLIPBOARD_LIMIT = 300_000
