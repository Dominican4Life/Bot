package eu.kanade.tachiyomi.ui.logs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.JsTiming
import eu.kanade.tachiyomi.ui.diagnostics.CardHeader
import eu.kanade.tachiyomi.ui.diagnostics.Caption
import eu.kanade.tachiyomi.ui.diagnostics.DiagCard
import eu.kanade.tachiyomi.ui.diagnostics.DiagColors
import eu.kanade.tachiyomi.ui.diagnostics.Dot
import eu.kanade.tachiyomi.ui.diagnostics.Mono
import eu.kanade.tachiyomi.ui.diagnostics.formatHms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * KMK (r119): Diagnostics' "Source Lab sources · JS time" card: per JS source, the time its JS ran, the time its
 * calls waited on the network, the call count and the slowest call, for this session or the last 24 h, sorted by
 * JS time ([JsTiming]). Tap a row for its last 20 calls (name, JS ms, network ms, ok / error). Reset clears only
 * this card's figures. Refreshed every 5 s while shown. Hard-coded English, like the rest of Diagnostics.
 */
@Composable
internal fun JsTimeCard(modifier: Modifier = Modifier) {
    val manager = remember { runCatching { Injekt.get<JsSourceManager>() }.getOrNull() }
    val noSources = remember { MutableStateFlow(emptyList<JsSourceInfo>()) }
    val saved by (manager?.savedFlow ?: noSources).collectAsState()
    var period by remember { mutableStateOf(JsTiming.Period.DAY) }
    var rows by remember { mutableStateOf<List<JsTiming.SourceRow>>(emptyList()) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var recent by remember { mutableStateOf<List<JsTiming.Call>>(emptyList()) }
    var askReset by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val names = remember(saved) { saved.associate { it.id to it.name } }

    LaunchedEffect(period, openId, version) {
        while (true) {
            val id = openId
            rows = withContext(Dispatchers.IO) { runCatching { JsTiming.rows(period) }.getOrDefault(emptyList()) }
            recent = if (id == null) emptyList() else withContext(Dispatchers.IO) { JsTiming.recent(id) }
            delay(5_000L)
        }
    }

    DiagCard(modifier = modifier) {
        CardHeader(
            title = "Source Lab sources · JS time",
            caption = "tap a source for its last calls",
            trailing = { TextButton(onClick = { askReset = true }) { Text("Reset") } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            JsTiming.Period.entries.forEach { p ->
                FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) })
            }
        }
        Row(modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) {
            Caption("source", modifier = Modifier.weight(1f))
            HeaderCell("JS", Modifier.width(60.dp))
            HeaderCell("network", Modifier.width(64.dp))
            HeaderCell("calls", Modifier.width(44.dp))
            Spacer(Modifier.width(ARROW_WIDTH))
        }
        HorizontalDivider()
        if (rows.isEmpty()) {
            Caption(
                "No Source Lab source has run yet (${period.label}).",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        rows.forEach { row ->
            val open = openId == row.id
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openId = if (open) null else row.id }
                    .padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            names[row.id] ?: row.name.ifBlank { "source ${row.id}" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Mono(
                            buildString {
                                append("slowest ")
                                append(row.slowestName ?: "—")
                                append(" ")
                                append(formatMs(row.slowestMs))
                                if (row.errors > 0) append(" · ").append(row.errors).append(" failed")
                            },
                            color = if (row.errors > 0) DiagColors.problem else MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 11.sp,
                        )
                    }
                    Mono(formatMs(row.jsMs), textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                    Mono(
                        formatMs(row.netMs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(64.dp),
                    )
                    Mono("${row.calls}", textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                    Icon(
                        if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (open) "Hide last calls" else "Show last calls",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (open) RecentCalls(recent)
            }
        }
    }

    if (askReset) {
        AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text("Reset JS time?") },
            text = {
                Text(
                    "Clears only this card's figures (this session, the last 24 h and the last calls of every " +
                        "source). Logs, network usage and the rest of Diagnostics stay as they are.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        JsTiming.reset()
                        askReset = false
                        openId = null
                        rows = emptyList()
                        recent = emptyList()
                        version++
                    },
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { askReset = false }) { Text("Cancel") } },
        )
    }
}

/** The last 20 calls of the open source, newest first */
@Composable
private fun RecentCalls(calls: List<JsTiming.Call>) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp)) {
        Row(modifier = Modifier.padding(bottom = 2.dp)) {
            Caption("last calls", modifier = Modifier.weight(1f))
            HeaderCell("JS", Modifier.width(60.dp))
            HeaderCell("network", Modifier.width(64.dp))
            HeaderCell("", Modifier.width(44.dp))
            Spacer(Modifier.width(ARROW_WIDTH - 12.dp))
        }
        if (calls.isEmpty()) Caption("No calls kept for this source.")
        calls.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Dot(if (c.ok) DiagColors.good else DiagColors.problem, size = 8)
                Mono(
                    "${formatHms(c.at)} ${c.name}",
                    size = 11.sp,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                Mono(formatMs(c.jsMs), size = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                Mono(
                    formatMs(c.netMs),
                    size = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(64.dp),
                )
                Mono(
                    if (c.ok) "ok" else "error",
                    size = 11.sp,
                    color = if (c.ok) MaterialTheme.colorScheme.onSurfaceVariant else DiagColors.problem,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
                Spacer(Modifier.width(ARROW_WIDTH - 12.dp)) // lines up with the source rows' columns
            }
        }
    }
}

/** The open / close arrow of a source row (4 dp gap + 24 dp icon) */
private val ARROW_WIDTH = 28.dp

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier,
    )
}

/** 850 ms · 4.2 s · 3 m 12 s · 2 h 05 m */
private fun formatMs(ms: Long): String = when {
    ms < 1_000L -> "$ms ms"
    ms < 60_000L -> String.format(Locale.US, "%.1f s", ms / 1_000.0)
    ms < 3_600_000L -> "${ms / 60_000L} m ${(ms / 1_000L) % 60L} s"
    else -> String.format(Locale.US, "%d h %02d m", ms / 3_600_000L, (ms / 60_000L) % 60L)
}
