package eu.kanade.tachiyomi.ui.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.diagnostics.HealthEvent
import eu.kanade.tachiyomi.diagnostics.LogEntry
import eu.kanade.tachiyomi.diagnostics.MarkerKind
import eu.kanade.tachiyomi.diagnostics.PreviousRun
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * KMK (r97): The Health tab (ph-09), the Reliability-Monitor idea: a 7-day strip with a dot per event (coloured by
 * its [MarkerKind], overlapping dots spread vertically), the legend counts, the Previous run card
 * (`ApplicationExitInfo`), and the events list newest first. A tap on a dot or an event opens the Log tab ±30 s
 * around it when the run is the current one, else the excerpt the engine kept; long-press copies. Share writes the
 * health zip through the engine and opens the share sheet.
 */
@Composable
internal fun HealthTab(model: DiagnosticsScreenModel, header: HeaderSlot) {
    val diag = model.diag
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val health by diag.health.collectAsState()
    val scope = rememberCoroutineScope()
    var excerpt by remember { mutableStateOf<Pair<HealthEvent, List<LogEntry>>?>(null) }

    DisposableEffect(health.watchdogsOn, health.lastStallText) {
        header.subtitle.value = "Health · watchdogs ${if (health.watchdogsOn) "on" else "off"}" +
            (health.lastStallText?.let { " · last stall $it" } ?: "")
        header.actions.value = {
            IconButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            val path = diag.exportHealth()
                            val uri = File(path).getUriCompat(context)
                            context.startActivity(uri.toShareIntent(context, type = "application/zip"))
                        }.onFailure { context.toast(it.message ?: "Couldn't export health") }
                    }
                },
            ) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
        }
        onDispose {
            header.subtitle.value = null
            header.actions.value = null
        }
    }

    fun open(event: HealthEvent) {
        // This run: the Log tab ±30 s. Anything else (an older run, an exit-info event with session 0): the excerpt
        // the engine kept with the event, or its detail
        if (event.session == AppLog.sessionStart) {
            model.openLogAround(event.time)
            return
        }
        scope.launch {
            val lines = runCatching { diag.healthExcerpt(event) }.getOrDefault(emptyList())
            if (lines.isEmpty()) {
                context.toast("No log kept for this event: ${event.detail.ifBlank { event.title }}")
            } else {
                excerpt = event to lines
            }
        }
    }

    val sorted = remember(health.events) { health.events.sortedByDescending { it.time } }
    val counts = remember(health.counts) {
        val bad = health.counts.filterKeys { it.marker == MarkerKind.CRASH }.values.sum()
        val warn = health.counts
            .filterKeys { it.marker != MarkerKind.CRASH && (it.bad || it.marker == MarkerKind.STALL) }
            .values.sum()
        val ok = health.counts.filterKeys { !it.bad && it.marker != MarkerKind.CRASH }.values.sum()
        Triple(bad, warn, ok)
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
    ) {
        item("strip") {
            DiagCard {
                CardHeader("Last 7 days", "${health.events.size} events · tap a dot for the log ±30 s")
                HealthStrip(events = sorted, onTap = { open(it) }, modifier = Modifier.padding(top = 12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    LegendItem(DiagColors.crash, "crash · OOM kill · ANR", "${counts.first}")
                    LegendItem(DiagColors.stall, "stall · trim · failed update", "${counts.second}")
                    LegendItem(DiagColors.sync, "sync · update ok", "${counts.third}")
                }
            }
        }
        item("previous") { PreviousRunCard(run = health.previousRun, watchdogsOn = health.watchdogsOn) }
        item("events-header") {
            DiagCard(padding = 0) {
                CardHeader("Events", "newest first · long-press copies", modifier = Modifier.padding(16.dp))
                if (sorted.isEmpty()) {
                    Caption(
                        "Nothing in 7 days: no crash, stall, trim, failed update or sync.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
                sorted.forEach { event ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    EventRow(
                        event = event,
                        onClick = { open(event) },
                        onLongClick = {
                            clipboard.setText(AnnotatedString(eventText(event)))
                            context.toast("Event copied")
                        },
                    )
                }
            }
        }
    }

    val shownExcerpt = excerpt
    if (shownExcerpt != null) {
        AlertDialog(
            onDismissRequest = { excerpt = null },
            title = { Text(shownExcerpt.first.title) },
            text = {
                Column {
                    Caption("${formatDayHm(shownExcerpt.first.time)} · the log around it, kept with the event")
                    LogLineList(lines = shownExcerpt.second, modifier = Modifier.height(360.dp), fontSize = 11)
                }
            },
            confirmButton = {
                TextButton(onClick = { excerpt = null }) { Text("Close") }
            },
        )
    }
}

private fun eventText(event: HealthEvent): String = buildString {
    append(formatDayHm(event.time)).append(' ').append(event.kind.badge).append(' ').append(event.title).append('\n')
    append(event.detail)
    event.dump?.let { d ->
        append('\n').append(d.summary)
        d.groups.forEach { g -> append('\n').append(g.count).append(' ').append(g.title) }
    }
}

private const val SEVEN_DAYS_MS = 7L * 86_400_000L

/** The strip: day ticks, a dot per event; dots closer than 14 dp spread over three rows; tap → nearest dot. */
@Composable
private fun HealthStrip(events: List<HealthEvent>, onTap: (HealthEvent) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val ring = remember { Stroke(1f) }
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(96.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val now = remember(events) { System.currentTimeMillis() }
        val start = now - SEVEN_DAYS_MS
        val minGap = with(density) { 14.dp.toPx() }
        // Positions once per events list: x from time, a row offset when a dot would overlap the previous one
        val dots = remember(events, widthPx) {
            val ordered = events.filter { it.time >= start }.sortedBy { it.time }
            val xs = FloatArray(ordered.size)
            val rows = IntArray(ordered.size)
            var lastX = -1_000f
            var row = 0
            ordered.forEachIndexed { i, e ->
                val x = 8f + (e.time - start).toFloat() / SEVEN_DAYS_MS * (widthPx - 16f)
                row = if (x - lastX < minGap) (row + 1) % 3 else 0
                xs[i] = x
                rows[i] = row
                lastX = x
            }
            Triple(ordered, xs, rows)
        }
        val dayLabels = remember(now, widthPx) {
            val fmt = SimpleDateFormat("EEE", Locale.US)
            (0 until 7).map { d ->
                val t = start + d * 86_400_000L + 43_200_000L
                measurer.measure(fmt.format(Date(t)), labelStyle)
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val (ordered, xs, _) = dots
                        var best = -1
                        var bestD = Float.MAX_VALUE
                        for (i in xs.indices) {
                            val d = abs(xs[i] - offset.x)
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                        }
                        if (best >= 0 && bestD <= minGap * 1.5f) onTap(ordered[best])
                    }
                },
        ) {
            val baseline = 34.dp.toPx()
            val dayW = (size.width - 16f) / 7f
            drawLine(lineColor, Offset(8f, baseline), Offset(size.width - 8f, baseline), strokeWidth = 2f)
            for (d in 0..7) {
                val x = 8f + d * dayW
                drawLine(lineColor, Offset(x, baseline - 10f), Offset(x, baseline + 10f), strokeWidth = 2f)
            }
            drawLine(
                primary,
                Offset(size.width - 8f, baseline - 18f),
                Offset(size.width - 8f, baseline + 18f),
                strokeWidth = 3f,
            )
            val (ordered, xs, rows) = dots
            for (i in ordered.indices) {
                val y = baseline - rows[i] * 9f
                val color = DiagColors.fixed(ordered[i].kind.marker.color)
                drawCircle(color, radius = 11f, center = Offset(xs[i], y))
                drawCircle(Color.Black.copy(alpha = 0.25f), radius = 11f, center = Offset(xs[i], y), style = ring)
            }
            dayLabels.forEachIndexed { d, label ->
                val x = 8f + d * dayW + dayW / 2f - label.size.width / 2f
                drawText(label, color = labelColor, topLeft = Offset(x, baseline + 20f))
            }
        }
    }
}

@Composable
private fun PreviousRunCard(run: PreviousRun?, watchdogsOn: Boolean) {
    DiagCard {
        CardHeader("Previous run", "ApplicationExitInfo")
        if (run == null) {
            Caption(
                "No exit recorded yet (Android 11+ reports them; the first run of a new install has none).",
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            val bad = run.reasonCode != 10 && run.reasonCode != 0 && run.reasonCode != 1 && run.reasonCode != 11
            KeyValueRow(
                "Ended",
                "${formatDayHm(run.endedAt)} · ${run.reason}",
                valueColor = if (bad) DiagColors.attention else MaterialTheme.colorScheme.onSurface,
            )
            KeyValueRow("Memory at exit", "pss ${run.pssKb / 1_024} MB · rss ${run.rssKb / 1_024} MB")
            run.screen?.let { KeyValueRow("Screen", it) }
            KeyValueRow("Uptime", "${formatDuration(run.uptimeMs)} · ${formatCount(run.linesKept)} lines kept")
            KeyValueRow("Watchdogs", "main 3 s · IO pool 5 s · ${if (watchdogsOn) "on" else "off"}")
            val dim = MaterialTheme.colorScheme.onSurfaceVariant
            run.description?.takeIf { it.isNotBlank() }?.let { Mono(it, color = dim, maxLines = 3) }
            run.lastMemoryLine?.let { Mono(it, color = dim, maxLines = 3, modifier = Modifier.padding(top = 4.dp)) }
        }
        Caption(
            "Each exit becomes an event and an I app Previous run ended: … line at start, so the log explains " +
                "itself.",
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRow(event: HealthEvent, onClick: () -> Unit, onLongClick: () -> Unit) {
    val color = DiagColors.fixed(event.kind.marker.color)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Mono(
            formatDayHm(event.time).removePrefix("today "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        KindBadge(event.kind.badge, color)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = event.dump?.let { d -> d.groups.firstOrNull()?.let { "${it.count} ${it.title}" } ?: d.summary }
                ?: event.detail
            if (detail.isNotBlank()) Mono(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 11.sp,
                maxLines = 2,
            )
        }
    }
}
