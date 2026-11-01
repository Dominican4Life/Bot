package eu.kanade.tachiyomi.ui.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.diagnostics.CursorInfo
import eu.kanade.tachiyomi.diagnostics.Diagnostics
import eu.kanade.tachiyomi.diagnostics.JobRow
import eu.kanade.tachiyomi.diagnostics.JobState
import eu.kanade.tachiyomi.diagnostics.Marker
import eu.kanade.tachiyomi.diagnostics.MarkerKind
import eu.kanade.tachiyomi.diagnostics.MemoryBreakdown
import eu.kanade.tachiyomi.diagnostics.PlayerLive
import eu.kanade.tachiyomi.diagnostics.Resource
import eu.kanade.tachiyomi.diagnostics.SampleWindow
import eu.kanade.tachiyomi.diagnostics.StorageSnapshot
import eu.kanade.tachiyomi.diagnostics.ThreadDump
import eu.kanade.tachiyomi.diagnostics.TimeWindow
import eu.kanade.tachiyomi.diagnostics.UsageBy
import eu.kanade.tachiyomi.diagnostics.UsageMetric
import eu.kanade.tachiyomi.diagnostics.UsageRow
import eu.kanade.tachiyomi.diagnostics.WorkerSnapshot
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * KMK (r97): The Live tab, Option D (ph-01…04, tb-01): a resource switcher, one big graph card with legend, the
 * time chips, the scrub slider with marker ticks, the cursor card (ph-02), the per-resource detail blocks (Memory
 * ph-03, Workers ph-04, Database sizes, Player figures), the Network usage table, "Events on this timeline",
 * Running now / Engines and the Advanced expander. The sampler runs only while this page is composed
 * ([Diagnostics.setLiveVisible] in a [DisposableEffect]); the engine publishes one [SampleWindow] per tick for the
 * (resource, window) this page collects.
 */
@Composable
internal fun LiveTab(model: DiagnosticsScreenModel, header: HeaderSlot, tablet: Boolean) {
    val diag = model.diag
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val resource by model.resource.collectAsState()
    val timeWindow by model.window.collectAsState()
    val capture by diag.capture.collectAsState()
    val player by diag.player.collectAsState()
    val markers by diag.markers.collectAsState()
    val window by remember(resource, timeWindow) { diag.samples(resource, timeWindow) }.collectAsState()
    var cursor by remember { mutableStateOf<CursorInfo?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        diag.setLiveVisible(true)
        onDispose { diag.setLiveVisible(false) }
    }
    DisposableEffect(capture.sampleMs) {
        header.subtitle.value = "live · sampling ${sampleLabel(capture.sampleMs)}"
        header.actions.value = {
            IconButton(
                onClick = {
                    runCatching {
                        val file = File(context.cacheDir, "komikku-live-snapshot.txt")
                        file.writeText(diag.liveSnapshotText())
                        context.startActivity(file.getUriCompat(context).toShareIntent(context, type = "text/plain"))
                    }.onFailure { context.toast(it.message ?: "Couldn't share the snapshot") }
                },
            ) { Icon(Icons.Outlined.Share, contentDescription = "Share snapshot") }
        }
        onDispose {
            header.subtitle.value = null
            header.actions.value = null
        }
    }

    fun pick(timeMs: Long) {
        scope.launch {
            cursor = runCatching { diag.cursor(resource, timeWindow, timeMs) }.getOrNull()
        }
    }

    val resources = remember(player != null, tablet) {
        Resource.entries.filter { it != Resource.PLAYER || player != null || tablet }
    }

    // KMK (r98): the tablet is packed like tb-01 (graph + legend in one card, slider under it, usage | events side by
    // side) so the usage rows and the events show without scrolling at 2560×1600; the phone keeps ph-01
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(if (tablet) 8.dp else 12.dp),
        contentPadding = if (tablet) {
            PaddingValues(start = 0.dp, end = 0.dp, top = 4.dp, bottom = 24.dp)
        } else {
            PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp)
        },
    ) {
        item("chips") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                ) {
                    resources.forEach { r ->
                        FilterChip(
                            selected = resource == r,
                            onClick = {
                                cursor = null
                                model.setResource(r)
                            },
                            label = { Text(r.label) },
                        )
                    }
                }
                if (tablet) {
                    Spacer(Modifier.width(16.dp))
                    TimeChips(timeWindow) {
                        cursor = null
                        model.setWindow(it)
                    }
                }
            }
        }
        item("graph") {
            GraphCard(
                window = window,
                sampleMs = capture.sampleMs,
                cursor = cursor,
                tablet = tablet,
                onTap = { pick(it) },
                onDismissCursor = { cursor = null },
            )
        }
        if (!tablet) {
            item("time") {
                TimeChips(timeWindow) {
                    cursor = null
                    model.setWindow(it)
                }
            }
        }
        item("slider") {
            // Released at the right end = follow "now" again (the cursor card goes away); elsewhere = pick that time
            MarkerSlider(window = window, markers = markers, onRelease = { t -> if (t == null) cursor = null else pick(t) })
        }
        val c = cursor
        if (c != null) {
            item("cursor") { CursorCard(info = c, onClose = { cursor = null }) }
        }
        when (resource) {
            Resource.MEMORY -> item("memory") { MemoryBlock(diag = diag) }
            Resource.WORKERS -> item("workers") { WorkersBlock(diag = diag) }
            Resource.DATABASE -> item("database") { DatabaseBlock(diag = diag) }
            Resource.PLAYER -> item("player") { PlayerBlock(player = player) }
            Resource.NETWORK -> Unit
        }
        if (tablet) {
            item("usage-events") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.weight(1.6f)) { UsageCard(diag = diag, wide = true) }
                    Box(modifier = Modifier.weight(1f)) {
                        EventsCard(window = window, onOpen = { m -> model.openLogAround(m.timeMs, m.tag) })
                    }
                }
            }
        } else {
            if (resource == Resource.NETWORK) item("usage") { UsageCard(diag = diag, wide = false) }
            item("events") { EventsCard(window = window, onOpen = { m -> model.openLogAround(m.timeMs, m.tag) }) }
        }
        item("running") { RunningCard(diag = diag, onOpen = { model.setResource(it) }) }
        item("advanced") {
            AdvancedCard(
                model = model,
                onCopySnapshot = {
                    clipboard.setText(AnnotatedString(diag.liveSnapshotText()))
                    context.toast("Snapshot copied")
                },
            )
        }
    }
}

private fun sampleLabel(ms: Long): String =
    if (ms >= 1_000L) "${ms / 1_000L} s" else String.format(Locale.US, "%.2f s", ms / 1_000.0)

@Composable
private fun TimeChips(selected: TimeWindow, onPick: (TimeWindow) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeWindow.entries.forEach { w ->
            FilterChip(selected = selected == w, onClick = { onPick(w) }, label = { Text(w.label) })
        }
    }
}

@Composable
private fun GraphCard(
    window: SampleWindow,
    sampleMs: Long,
    cursor: CursorInfo?,
    tablet: Boolean,
    onTap: (Long) -> Unit,
    onDismissCursor: () -> Unit,
) {
    val probeCounts = remember(window.markers) {
        window.markers.filter { it.kind == MarkerKind.PROBE }.groupingBy { it.tag }.eachCount().toList()
    }
    val headRow: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Headline(window, compact = tablet)
                Mono(
                    window.subline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(DiagColors.good, size = 8)
                    Spacer(Modifier.width(6.dp))
                    Text("live", color = DiagColors.good, style = MaterialTheme.typography.bodyMedium)
                }
                Caption("last ${window.window.label}${if (tablet) " · sampling ${sampleLabel(sampleMs)}" else ""}")
            }
        }
    }
    DiagCard(padding = if (tablet) 14 else 16) {
        if (tablet) {
            // tb-01: the headline and the graph on the left, the legend in its own column on the right (beside the
            // headline too, so the legend's rows don't add to the card's height)
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Column(modifier = Modifier.weight(1f)) {
                    headRow()
                    LiveGraph(
                        window = window,
                        cursorTimeMs = cursor?.timeMs,
                        onTap = onTap,
                        modifier = Modifier.height(TABLET_GRAPH_HEIGHT).padding(top = 6.dp),
                    )
                }
                VerticalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Column(modifier = Modifier.width(220.dp)) {
                    Caption(
                        "${window.resource.label.uppercase()} · ${window.resource.unit} NOW",
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                    window.series.asReversed().forEach { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            Swatch(DiagColors.fixed(s.color))
                            Text(
                                s.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Mono(formatValue(s.current, window.resource))
                        }
                    }
                    window.overlay?.let { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            Box(modifier = Modifier.width(12.dp)) {
                                HorizontalDivider(thickness = 2.dp, color = DiagColors.fixed(s.color))
                            }
                            Text(
                                s.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            Mono(formatValue(s.current, window.resource))
                        }
                    }
                    probeCounts.forEach { (tag, n) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            Dot(DiagColors.probe, size = 10)
                            Text(
                                tag,
                                color = DiagColors.probe,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            Mono("$n ev")
                        }
                    }
                }
            }
        } else {
            headRow()
            LiveGraph(
                window = window,
                cursorTimeMs = cursor?.timeMs,
                onTap = onTap,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                window.series.asReversed().forEach { s ->
                    LegendItem(DiagColors.fixed(s.color), s.label, formatValue(s.current, window.resource))
                }
                window.overlay?.let { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(modifier = Modifier.width(14.dp)) {
                            HorizontalDivider(thickness = 2.dp, color = DiagColors.fixed(s.color))
                        }
                        Text(s.label, style = MaterialTheme.typography.bodyMedium)
                        Mono(formatValue(s.current, window.resource), weight = FontWeight.Medium)
                    }
                }
                probeCounts.forEach { (tag, n) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Dot(DiagColors.probe, size = 10)
                        Text(tag, color = DiagColors.probe, style = MaterialTheme.typography.bodyMedium)
                        Mono("$n ev")
                    }
                }
            }
        }
        if (cursor != null) {
            Caption(
                "cursor at ${formatHms(cursor.timeMs)} · tap to clear",
                modifier = Modifier.padding(top = 6.dp).clickable { onDismissCursor() },
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** "1.0 MB/s" or "231 / 512 MB" (headline from the engine, unit beside it). */
@Composable
private fun Headline(window: SampleWindow, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom) {
        val parts = window.headline.split(' ', limit = 2)
        Text(
            parts.firstOrNull() ?: window.headline,
            style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        if (parts.size > 1) {
            Text(
                parts[1],
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp, bottom = if (compact) 4.dp else 6.dp),
            )
        }
    }
}

/** The tablet graph's height (tb-01); the phone keeps LiveGraph's 260 dp */
private val TABLET_GRAPH_HEIGHT = 210.dp

internal fun formatValue(value: Float, resource: Resource): String = when (resource) {
    Resource.NETWORK, Resource.PLAYER -> String.format(Locale.US, "%.1f", value)
    Resource.MEMORY, Resource.WORKERS, Resource.DATABASE -> String.format(Locale.US, "%.0f", value)
}

/** The scrub slider "−6 min … drag to go back … now" with a tick per marker in its range (contract §8 rule 1) */
@Composable
private fun MarkerSlider(window: SampleWindow, markers: List<Marker>, onRelease: (Long?) -> Unit) {
    val rangeMs = window.window.seconds * 6L * 1_000L
    var value by remember(window.window) { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    // Ticks change when the markers list or the range does, not per tick of the sampler
    val now = window.endMs.takeIf { it > 0L } ?: System.currentTimeMillis()
    val ticks = remember(markers, rangeMs, now / 10_000L) {
        val start = now - rangeMs
        markers.filter { it.timeMs in start..now }.map { ((it.timeMs - start).toFloat() / rangeMs) to it.kind }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(40.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(40.dp)) {
                val cy = size.height / 2f
                for ((f, kind) in ticks) {
                    val x = 12f + f * (size.width - 24f)
                    val color = DiagColors.fixed(kind.color)
                    if (kind == MarkerKind.PROBE) {
                        drawCircle(color, radius = 4f, center = Offset(x, cy))
                    } else {
                        drawRoundRect(
                            color,
                            topLeft = Offset(x - 2f, cy - 9f),
                            size = Size(4f, 18f),
                            cornerRadius = CornerRadius(2f),
                        )
                    }
                }
            }
            Slider(
                value = value,
                onValueChange = {
                    dragging = true
                    value = it
                },
                onValueChangeFinished = {
                    dragging = false
                    if (value >= 0.995f) {
                        value = 1f
                        onRelease(null)
                    } else {
                        onRelease(now - ((1f - value) * rangeMs).toLong())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // KMK (r98): three separate slots — start left, hint centred (ellipsized, never under the ends), "now" right —
        // so "−6 min" and the hint can't run together ("−6 mindrag to go back") at any width
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Mono("−${window.window.seconds * 6L / 60L} min", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Mono(
                if (dragging) formatHms(now - ((1f - value) * rangeMs).toLong()) else "drag to go back · markers are events",
                color = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Mono("now", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** ph-02: the values at the picked second and, for Network, the requests that finished in it. */
@Composable
private fun CursorCard(info: CursorInfo, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    DiagCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Mono(
                    formatHms(info.timeMs),
                    color = MaterialTheme.colorScheme.primary,
                    weight = FontWeight.Bold,
                    size = 14.sp,
                )
                Text(
                    "${String.format(Locale.US, "%.1f", info.total)} ${info.unit}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(
                onClick = {
                    val text = buildString {
                        append(formatHms(info.timeMs)).append(' ').append(info.total).append(' ').append(info.unit)
                        append('\n')
                        info.perSeries.forEach { (s, v) -> append(s.label).append('\t').append(v).append('\n') }
                        info.requests.forEach { r ->
                            append(r.method).append(' ').append(r.host).append(r.path).append('\t').append(r.status)
                                .append('\t').append(r.ms).append(" ms\t").append(r.bytes).append('\n')
                        }
                    }
                    clipboard.setText(AnnotatedString(text))
                    context.toast("Copied")
                },
            ) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy") }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        info.perSeries.forEach { (s, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Swatch(DiagColors.fixed(s.color))
                Mono(s.label, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Mono(String.format(Locale.US, "%.1f", v), weight = FontWeight.Medium)
            }
        }
        info.note?.let { Caption(it, modifier = Modifier.padding(top = 4.dp)) }
        if (info.requests.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            val total = info.requests.sumOf { it.bytes }
            CardHeader(
                "Requests at ${formatHms(info.timeMs)}",
                "${info.requests.size} in this second · ${formatBytes(total)}",
            )
            info.requests.take(40).forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Swatch(DiagColors.fixed(r.color))
                    Mono("${r.method} ${r.host}${r.path}", modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Mono(
                        "${r.status}",
                        color = if (r.status in 200..399) DiagColors.good else DiagColors.problem,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Mono("${r.ms} ms", modifier = Modifier.padding(start = 10.dp))
                    Mono(formatBytes(r.bytes), modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

// --- Memory (ph-03) ----------------------------------------------------------------------------------------------

@Composable
private fun MemoryBlock(diag: Diagnostics) {
    val mem by diag.memory.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DiagCard {
            CardHeader("Two limits", "the 512 MB cap is the Java heap only")
            KeyValueRow("Java heap", "${mem.javaMb} / ${mem.javaMaxMb} MB · trim at ${mem.trimAtMb}")
            KeyValueRow("Images", "${mem.imagesMb} / ${mem.imagesMaxMb} MB · inside the heap")
            KeyValueRow("Native", "${mem.nativeMb} MB = QuickJS ${mem.jsMb} + diag ${mem.diagMb} + other")
            KeyValueRow("Process RSS", "${mem.rssMb} MB of ${mem.deviceTotalMb} MB · not capped")
            KeyValueRow(
                "Browse caches",
                "tabs ${mem.browseTabs} · listings ${mem.folderListings} · tiles ${mem.animatedTiles} · " +
                    "JS pages ${mem.jsPages}",
            )
            mem.lastTrimText?.let { KeyValueRow("Last trim", it, valueColor = DiagColors.trim) }
        }
        DiagCard {
            CardHeader(
                "JS engines",
                "${mem.engines.size} runtimes · ${mem.jsCapMb} MB cap each · page text ${mem.jsPageTextMb} MB · " +
                    "DOM ≈ ${mem.jsDomMb} MB",
            )
            Row(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Caption("source", modifier = Modifier.weight(1f))
                Caption("MB", modifier = Modifier.width(56.dp))
                Caption("pages", modifier = Modifier.width(52.dp))
                Caption("vs cap", modifier = Modifier.width(96.dp))
            }
            HorizontalDivider()
            mem.engines.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Dot(
                        when {
                            e.busy -> DiagColors.attention
                            e.stale -> DiagColors.attention.copy(alpha = 0.5f)
                            else -> DiagColors.good
                        },
                        size = 8,
                    )
                    Text(
                        e.name,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Mono(formatMb(e.mb), modifier = Modifier.width(56.dp))
                    Mono("${e.pages}", modifier = Modifier.width(52.dp))
                    LinearProgressIndicator(
                        progress = { (e.mb / mem.jsCapMb.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        color = DiagColors.fixed(e.color),
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.width(88.dp).height(6.dp),
                    )
                }
            }
            if (mem.engines.isEmpty()) Caption("No JS runtime is open.", modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            context.toast(runCatching { diag.trimMemory() }.getOrElse { it.message ?: "Trim failed" })
                        }
                    },
                ) { Text("Trim now") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val n = runCatching { diag.closeIdleRuntimes() }.getOrDefault(0)
                            context.toast("Closed $n idle runtime${if (n == 1) "" else "s"}")
                        }
                    },
                ) { Text("Close idle runtimes") }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(memoryText(mem)))
                        context.toast("Copied")
                    },
                ) { Text("Copy") }
            }
        }
    }
}

private fun memoryText(mem: MemoryBreakdown): String = buildString {
    append("Java ${mem.javaMb}/${mem.javaMaxMb} MB · images ${mem.imagesMb}/${mem.imagesMaxMb} · JS ${mem.jsMb} · ")
    append("native ${mem.nativeMb} (diag ${mem.diagMb}) · RSS ${mem.rssMb}/${mem.deviceTotalMb} · ")
    append("trim at ${mem.trimAtMb}\n")
    mem.engines.forEach { append("${e(it.busy)} ${it.name}\t${formatMb(it.mb)} MB\t${it.pages} pages\n") }
}

private fun e(busy: Boolean): String = if (busy) "●" else "○"

private val WorkerSnapshot.free: Int
    get() = (ioTotal - ioBusy - ioParked - ioBlocked).coerceAtLeast(0)

// --- Workers (ph-04) ---------------------------------------------------------------------------------------------

@Composable
private fun WorkersBlock(diag: Diagnostics) {
    val workers by diag.workers.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var dumping by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DiagCard {
            // KMK (r98): 64 slots = the IO limit; Default's workers carry the same thread name, so the slots are filled by
            // state (running, blocked, parked) and idle threads past the limit aren't drawn
            CardHeader("IO pool", "Dispatchers.IO · limit ${workers.ioTotal} · Default shares these threads")
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                Text("${workers.ioBusy}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    " busy · ${workers.ioParked} parked · ${workers.ioBlocked} blocked · " +
                        "${workers.free} free",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            SlotGrid(workers, modifier = Modifier.padding(top = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                LegendItem(DiagColors.heap, "running", "")
                LegendItem(DiagColors.blocked, "blocked", "")
                LegendItem(DiagColors.parked, "parked", "")
                LegendItem(MaterialTheme.colorScheme.outlineVariant, "free", "")
            }
            Caption(
                "Default busy ${workers.defaultBusy}" +
                    (if (workers.defaultTotal > 0) " / ${workers.defaultTotal}" else "") +
                    " · JS busy ${workers.jsBusy} · ${workers.threads} threads in the app · slots are filled by state, " +
                    "idle workers past the limit aren't slots" +
                    (if (workers.starved) " · STARVED" else ""),
                modifier = Modifier.padding(top = 6.dp),
                color = if (workers.starved) DiagColors.problem else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                FilledTonalButton(
                    enabled = !dumping,
                    onClick = {
                        dumping = true
                        scope.launch {
                            val dump = runCatching { diag.threadDump() }.getOrNull()
                            dumping = false
                            context.toast(dump?.summary ?: "Thread dump failed")
                        }
                    },
                ) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Thread dump now")
                }
                val dump = workers.lastDump
                OutlinedButton(
                    enabled = dump != null,
                    onClick = {
                        clipboard.setText(AnnotatedString(dumpText(dump!!)))
                        context.toast("Copied")
                    },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy")
                }
                OutlinedButton(
                    enabled = dump != null,
                    onClick = {
                        runCatching {
                            val file = File(context.cacheDir, "komikku-thread-dump.txt")
                            file.writeText(dumpText(dump!!))
                            val uri = file.getUriCompat(context)
                            context.startActivity(uri.toShareIntent(context, type = "text/plain"))
                        }.onFailure { context.toast(it.message ?: "Couldn't share") }
                    },
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
        }
        workers.lastDump?.let { dump -> ThreadDumpCard(dump) }
        DiagCard {
            CardHeader("Watchdogs", "stack traces only when a rule fires")
            KeyValueRow("Main thread", if (workers.mainWatchdogArmed) "armed · one post / 3 s" else "off")
            KeyValueRow("IO pool", if (workers.ioWatchdogArmed) "armed · one enumerate / 5 s" else "off")
            KeyValueRow(
                "Last stall",
                workers.lastStallText?.let { "${workers.lastStallAt?.let(::formatDayHm) ?: ""} · $it" }
                    ?: "none this run",
                valueColor = if (workers.lastStallText != null) {
                    DiagColors.stall
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun SlotGrid(workers: WorkerSnapshot, modifier: Modifier = Modifier) {
    val slots = workers.slots
    val columns = 16
    val rows = (slots.size + columns - 1) / columns
    val running = DiagColors.heap
    val blocked = DiagColors.blocked
    val parked = DiagColors.parked
    val free = MaterialTheme.colorScheme.outlineVariant
    val outline = remember { Stroke(1.5f) }
    // One pitch for x and y (min of the width / 16 and 28 dp) so the boxes stay square and the rows don't overlap
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val pitch = minOf(maxWidth / columns, 28.dp)
        Canvas(modifier = Modifier.fillMaxWidth().height(pitch * rows.coerceAtLeast(1))) {
            val cell = pitch.toPx()
            val gap = cell * 0.2f
            val box = (cell - gap).coerceAtLeast(1f)
            for (i in slots.indices) {
                val x = (i % columns) * cell + gap / 2
                val y = (i / columns) * cell + gap / 2
                val state = slots[i].toInt()
                val color = when (state) {
                    1 -> running
                    2 -> parked
                    3 -> blocked
                    else -> free
                }
                if (state == 0) {
                    drawRoundRect(
                        color,
                        topLeft = Offset(x, y),
                        size = Size(box, box),
                        cornerRadius = CornerRadius(6f),
                        style = outline,
                    )
                } else {
                    drawRoundRect(color, topLeft = Offset(x, y), size = Size(box, box), cornerRadius = CornerRadius(6f))
                }
            }
        }
    }
}

@Composable
internal fun ThreadDumpCard(dump: ThreadDump) {
    DiagCard {
        CardHeader("Thread dump", "${dump.threads} threads · ${formatHms(dump.timeMs)} · grouped by top frames")
        dump.groups.forEach { g ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 10.dp)) {
                Mono(
                    "${g.count}",
                    size = 16.sp,
                    weight = FontWeight.Bold,
                    color = when (g.state) {
                        "BLOCKED" -> DiagColors.problem
                        "RUNNABLE" -> DiagColors.heap
                        else -> DiagColors.stall
                    },
                    modifier = Modifier.width(40.dp),
                )
                Column {
                    Text(g.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    g.frames.take(3).forEach { f ->
                        Mono("at $f", color = MaterialTheme.colorScheme.onSurfaceVariant, size = 11.sp)
                    }
                }
            }
        }
    }
}

private fun dumpText(dump: ThreadDump): String = buildString {
    append(dump.summary).append('\n')
    dump.groups.forEach { g ->
        append(g.count).append(' ').append(g.title).append(" [").append(g.state).append("]\n")
        g.frames.forEach { append("    at ").append(it).append('\n') }
    }
}

// --- Database / Player ---------------------------------------------------------------------------------------------

@Composable
private fun DatabaseBlock(diag: Diagnostics) {
    val tables by diag.tables.collectAsState()
    val storage: StorageSnapshot? = tables.storage
    DiagCard {
        CardHeader("Database & storage", "sizes from stat; the downloads walk has a 10-min TTL")
        KeyValueRow("Database", storage?.let { formatBytes(it.dbBytes) } ?: "…")
        KeyValueRow("WAL", storage?.let { formatBytes(it.walBytes) } ?: "…")
        KeyValueRow("Downloads", storage?.downloadsBytes?.let(::formatBytes) ?: "…")
        KeyValueRow("Cache", storage?.cacheBytes?.let(::formatBytes) ?: "…")
        KeyValueRow("Reader cache", storage?.readerCacheBytes?.let(::formatBytes) ?: "…")
        KeyValueRow("Free", storage?.let { formatBytes(it.freeBytes) } ?: "…")
        storage?.computedAt?.let { Caption("computed ${formatAgo(it)}", modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun PlayerBlock(player: PlayerLive?) {
    DiagCard {
        if (player == null) {
            CardHeader("Player", "no episode playing")
            Caption("Fills while an episode plays: position, demuxer cache, dropped frames, codecs, relay.")
            return@DiagCard
        }
        CardHeader(player.title, player.source ?: "")
        KeyValueRow("Position", "${formatClock(player.posS * 1_000L)} / ${formatClock(player.durationS * 1_000L)}")
        KeyValueRow(
            "Demuxer cache",
            String.format(Locale.US, "%.1f s", player.demuxerCacheS) +
                (if (player.pausedForCache) " · paused for cache" else "") +
                (if (player.seeking) " · seeking" else ""),
            valueColor = if (player.pausedForCache) DiagColors.attention else MaterialTheme.colorScheme.onSurface,
        )
        KeyValueRow("Dropped", "${player.droppedFrames} frames")
        KeyValueRow(
            "Video",
            "${player.vcodec ?: "?"} · ${player.width}×${player.height} · " +
                "${String.format(Locale.US, "%.2f", player.fps)} fps · hwdec ${player.hwdec ?: "no"}",
        )
        KeyValueRow("Audio", player.acodec ?: "?")
        KeyValueRow(
            "Relay",
            "${player.relayServed} served · ${formatBytes(player.relayBytes)} · ${player.relayErrors} errors",
        )
        if (player.lastHosterSteps.isNotEmpty()) {
            Caption("Last hoster steps", modifier = Modifier.padding(top = 8.dp))
            player.lastHosterSteps.forEach { Mono(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// --- Network usage table (ph-01 bottom) ---------------------------------------------------------------------------

@Composable
private fun UsageCard(diag: Diagnostics, wide: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var usageBy by remember { mutableStateOf(UsageBy.SOURCE) }
    var metric by remember { mutableStateOf(UsageMetric.BYTES) }
    var period by remember { mutableStateOf(TimeWindow.H1) }
    var periodMenu by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<UsageRow>>(emptyList()) }
    LaunchedEffect(usageBy, period) {
        while (true) {
            rows = runCatching { diag.usage(usageBy, period) }.getOrDefault(emptyList())
            delay(10_000L)
        }
    }
    val sorted = remember(rows, metric) {
        when (metric) {
            UsageMetric.BYTES -> rows.sortedByDescending { it.bytes }
            UsageMetric.REQUESTS -> rows.sortedByDescending { it.requests }
            UsageMetric.AVG_MS -> rows.sortedByDescending { it.avgMs }
        }
    }
    val max = remember(sorted, metric) {
        when (metric) {
            UsageMetric.BYTES -> sorted.maxOfOrNull { it.bytes } ?: 0L
            UsageMetric.REQUESTS -> sorted.maxOfOrNull { it.requests.toLong() } ?: 0L
            UsageMetric.AVG_MS -> sorted.maxOfOrNull { it.avgMs } ?: 0L
        }.coerceAtLeast(1L)
    }
    DiagCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Network usage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.height(34.dp)) {
                UsageBy.entries.forEachIndexed { index, b ->
                    SegmentedButton(
                        selected = usageBy == b,
                        onClick = { usageBy = b },
                        shape = SegmentedButtonDefaults.itemShape(index, UsageBy.entries.size),
                        icon = {},
                        label = { Text(b.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        ) {
            UsageMetric.entries.forEach { m ->
                FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(m.label) })
            }
            Box {
                FilterChip(
                    selected = false,
                    onClick = { periodMenu = true },
                    label = { Text("last ${period.label}") },
                    trailingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) },
                )
                DropdownMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                    TimeWindow.entries.forEach { w ->
                        DropdownMenuItem(
                            text = { Text("last ${w.label}") },
                            onClick = {
                                period = w
                                periodMenu = false
                            },
                        )
                    }
                }
            }
            FilterChip(
                selected = false,
                onClick = {
                    scope.launch {
                        runCatching {
                            val path = diag.usageCsv(usageBy, period)
                            val uri = File(path).getUriCompat(context)
                            context.startActivity(uri.toShareIntent(context, type = "text/csv"))
                        }.onFailure { context.toast(it.message ?: "Couldn't write the CSV") }
                    }
                },
                label = { Text("CSV") },
                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
            )
            FilterChip(
                selected = false,
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            sorted.joinToString("\n") {
                                "${it.name}\t${it.hosts}\t${it.bytes}\t${it.requests}\t${it.avgMs}\t${it.errors}"
                            },
                        ),
                    )
                    context.toast("Table copied")
                },
                label = { Text("Copy") },
            )
        }
        Row(modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) {
            Caption("#", modifier = Modifier.width(24.dp))
            Caption("${usageBy.label.lowercase()} · hosts", modifier = Modifier.weight(1f))
            val align = if (wide) TextAlign.End else TextAlign.Start
            HeaderCell(metric.label, align, Modifier.width(72.dp))
            HeaderCell("req", align, Modifier.width(44.dp))
            HeaderCell("avg ms", align, Modifier.width(52.dp))
        }
        HorizontalDivider()
        if (sorted.isEmpty()) Caption("No requests in this period yet.", modifier = Modifier.padding(top = 8.dp))
        // KMK (r98): tablet rows are one line (tb-01: name · hosts · bar · figures), so four or more fit on screen
        if (wide) {
            sorted.take(20).forEachIndexed { index, row ->
                val value = when (metric) {
                    UsageMetric.BYTES -> row.bytes
                    UsageMetric.REQUESTS -> row.requests.toLong()
                    UsageMetric.AVG_MS -> row.avgMs
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Mono(
                        "${index + 1}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(24.dp),
                    )
                    Swatch(DiagColors.fixed(row.color))
                    Text(
                        row.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(0.9f).padding(start = 8.dp),
                    )
                    Mono(
                        row.hosts,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 11.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    UsageBar(fraction = value.toFloat() / max, color = DiagColors.fixed(row.color), modifier = Modifier.width(88.dp))
                    Mono(
                        when (metric) {
                            UsageMetric.BYTES -> formatBytes(row.bytes)
                            UsageMetric.REQUESTS -> "${row.requests}"
                            UsageMetric.AVG_MS -> "${row.avgMs}"
                        },
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(72.dp),
                    )
                    Mono("${row.requests}", textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                    Mono(
                        "${row.avgMs}",
                        color = if (row.avgMs > 1_000L) DiagColors.problem else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(52.dp),
                    )
                }
            }
            return@DiagCard
        }
        sorted.take(20).forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                Mono(
                    "${index + 1}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                Swatch(DiagColors.fixed(row.color))
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        row.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Mono(row.hosts, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 11.sp)
                    val value = when (metric) {
                        UsageMetric.BYTES -> row.bytes
                        UsageMetric.REQUESTS -> row.requests.toLong()
                        UsageMetric.AVG_MS -> row.avgMs
                    }
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .fillMaxWidth(0.9f)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(2.dp),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((value.toFloat() / max).coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(DiagColors.fixed(row.color), RoundedCornerShape(2.dp)),
                        )
                    }
                }
                Mono(
                    when (metric) {
                        UsageMetric.BYTES -> formatBytes(row.bytes)
                        UsageMetric.REQUESTS -> "${row.requests}"
                        UsageMetric.AVG_MS -> "${row.avgMs}"
                    },
                    modifier = Modifier.width(72.dp),
                )
                Mono("${row.requests}", modifier = Modifier.width(44.dp))
                Mono(
                    "${row.avgMs}",
                    color = if (row.avgMs > 1_000L) DiagColors.problem else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(52.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, align: TextAlign, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 1,
        modifier = modifier,
    )
}

/** The thin share bar of a usage row */
@Composable
private fun UsageBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(4.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
    }
}

// --- Events on this timeline --------------------------------------------------------------------------------------

@Composable
private fun EventsCard(window: SampleWindow, onOpen: (Marker) -> Unit) {
    val inWindow = remember(window.markers) { window.markers.asReversed().take(30) }
    DiagCard {
        CardHeader(
            "Events on this timeline",
            "${window.markers.size} in the last ${window.window.label} · tap to jump",
        )
        if (inWindow.isEmpty()) Caption(
            "Marks, probe lines and incidents show here as they happen.",
            modifier = Modifier.padding(top = 8.dp),
        )
        inWindow.forEach { m ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(m) }.padding(vertical = 6.dp),
            ) {
                Mono(
                    formatAgo(m.timeMs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(84.dp),
                )
                KindBadge(m.kind.label, DiagColors.fixed(m.kind.color))
                Text(
                    m.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

// --- Running now / Engines ----------------------------------------------------------------------------------------

@Composable
private fun RunningCard(diag: Diagnostics, onOpen: (Resource) -> Unit) {
    val tables by diag.tables.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DiagCard {
            CardHeader("Running now", "jobs, downloaders, pair sync")
            if (tables.running.isEmpty()) Caption("Nothing is running.", modifier = Modifier.padding(top = 8.dp))
            tables.running.forEach { JobLine(it, onOpen) }
        }
        DiagCard {
            CardHeader("Engines", "JS sources, image cache, storage, database, relay")
            tables.engines.forEach { JobLine(it, onOpen) }
        }
    }
}

@Composable
private fun JobLine(row: JobRow, onOpen: (Resource) -> Unit) {
    val stateColor = when (row.state) {
        JobState.RUNNING -> MaterialTheme.colorScheme.primary
        JobState.OK -> DiagColors.good
        JobState.WARN -> DiagColors.attention
        JobState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(row.opens?.let { r -> Modifier.clickable { onOpen(r) } } ?: Modifier)
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.sub?.let { Caption(it) }
        }
        Mono(row.progress, modifier = Modifier.padding(horizontal = 10.dp))
        KindBadge(row.state.label, stateColor)
    }
}

// --- Advanced -----------------------------------------------------------------------------------------------------

@Composable
private fun AdvancedCard(model: DiagnosticsScreenModel, onCopySnapshot: () -> Unit) {
    val open by model.liveAdvanced.collectAsState()
    val mem by model.diag.memory.collectAsState()
    DiagCard(padding = 0) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { model.setLiveAdvanced(!open) }.padding(16.dp),
        ) {
            Icon(
                if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text("Advanced", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Caption("per-source memory · raw snapshot")
        }
        if (open) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Caption("Per-source memory")
                mem.engines.forEach { e ->
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Mono(e.name, modifier = Modifier.weight(1f))
                        Mono("${formatMb(e.mb)} MB · ${e.pages} pages${if (e.busy) " · busy" else ""}")
                    }
                }
                OutlinedButton(onClick = onCopySnapshot, modifier = Modifier.padding(top = 10.dp)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy raw snapshot as text")
                }
            }
        }
    }
}
