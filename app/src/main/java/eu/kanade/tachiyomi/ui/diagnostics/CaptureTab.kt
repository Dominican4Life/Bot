package eu.kanade.tachiyomi.ui.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.diagnostics.CaptureState
import eu.kanade.tachiyomi.diagnostics.DiagCategory
import eu.kanade.tachiyomi.diagnostics.DiagLevel
import eu.kanade.tachiyomi.diagnostics.DiagPreset
import eu.kanade.tachiyomi.diagnostics.Diagnostics
import eu.kanade.tachiyomi.diagnostics.FloatingLogState
import eu.kanade.tachiyomi.diagnostics.RecordDuration
import eu.kanade.tachiyomi.diagnostics.SampleRate
import eu.kanade.tachiyomi.diagnostics.TraceKind
import eu.kanade.tachiyomi.util.system.toast
import kotlin.math.roundToInt

/**
 * KMK (r97): The Capture tab (ph-06): Record session (1 / 5 / 15 min / until stopped) and Mark cards with the
 * "Floating log" toggle, the presets, the Sampling slider with its cost note, the Customize expander (one
 * Off / Normal / Verbose row per category, APP always on) and the Traces section (a card per trace with its ring
 * size; the network trace adds the source chips and the headers / body-head switches). Every change is one engine
 * call; the engine publishes the new [CaptureState].
 */
@Composable
internal fun CaptureTab(model: DiagnosticsScreenModel, header: HeaderSlot) {
    val diag = model.diag
    val context = LocalContext.current
    val capture by diag.capture.collectAsState()
    val recording by diag.recording.collectAsState()
    val floating by FloatingLogState.visible.collectAsState()
    var markSheet by remember { mutableStateOf(false) }
    val verboseNames = remember(capture) {
        capture.levels.filterValues { it == DiagLevel.VERBOSE }.keys
            .sortedBy { it.ordinal }
            .joinToString(", ") { it.label }
    }

    DisposableEffect(capture.preset, capture.anyVerbose, recording != null) {
        header.subtitle.value = when {
            recording != null -> "Capture · recording · everything Verbose"
            capture.preset != null -> "Capture · preset ${capture.preset!!.label}"
            capture.anyVerbose -> "Capture · Verbose on $verboseNames"
            else -> "Capture · Normal · what the app logged before"
        }
        onDispose { header.subtitle.value = null }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
    ) {
        item("record-mark") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RecordCard(
                    diag = diag,
                    recordingRunning = recording != null,
                    duration = recording?.duration,
                    modifier = Modifier.weight(1f),
                )
                MarkCard(
                    onMark = { markSheet = true },
                    floating = floating,
                    onFloating = { toggleFloating(context, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item("presets") { PresetsCard(capture = capture, diag = diag, verboseNames = verboseNames) }
        item("sampling") { SamplingCard(capture = capture, diag = diag) }
        item("customize") { CustomizeCard(capture = capture, diag = diag, recording = recording != null) }
        item("traces-title") {
            Column(modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                Text("Traces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Caption("Verbose detail in side rings, off the log. Each is a file in the Report.")
            }
        }
        TraceKind.entries.forEach { kind ->
            item("trace-${kind.name}") { TraceCard(kind = kind, capture = capture, diag = diag) }
        }
    }

    if (markSheet) {
        MarkSheet(
            onDismiss = { markSheet = false },
            onMark = { note ->
                diag.mark(note.takeIf { it.isNotBlank() })
                markSheet = false
                context.toast("Mark written")
            },
        )
    }
}

@Composable
private fun RecordCard(
    diag: Diagnostics,
    recordingRunning: Boolean,
    duration: RecordDuration?,
    modifier: Modifier = Modifier,
) {
    var chooser by remember { mutableStateOf(false) }
    DiagCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.RadioButtonChecked, contentDescription = null, tint = DiagColors.problem)
            Spacer(Modifier.width(8.dp))
            Text("Record session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Caption(
            "Everything Verbose for 1 · 5 · 15 min or until stopped, then the Report builds itself.",
            modifier = Modifier.padding(top = 6.dp),
        )
        Box(modifier = Modifier.padding(top = 10.dp)) {
            if (recordingRunning) {
                OutlinedButton(onClick = { diag.stopRecording() }) {
                    Dot(DiagColors.problem, size = 8)
                    Spacer(Modifier.width(8.dp))
                    Text("${duration?.label ?: ""} · running · Stop", color = DiagColors.problem)
                }
            } else {
                OutlinedButton(onClick = { chooser = true }) { Text("Record…") }
            }
            DropdownMenu(expanded = chooser, onDismissRequest = { chooser = false }) {
                RecordDuration.entries.forEach { d ->
                    DropdownMenuItem(
                        text = { Text(d.label) },
                        onClick = {
                            chooser = false
                            diag.record(d)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkCard(
    onMark: () -> Unit,
    floating: Boolean,
    onFloating: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    DiagCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Mark", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Caption(
            "Writes === MARK: note === into the log and a flag on the graph timeline.",
            modifier = Modifier.padding(top = 6.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            OutlinedButton(onClick = onMark) { Text("Mark now") }
            FilterChip(
                selected = floating,
                onClick = { onFloating(!floating) },
                label = { Text("Floating log") },
            )
        }
    }
}

@Composable
private fun PresetsCard(capture: CaptureState, diag: Diagnostics, verboseNames: String) {
    DiagCard {
        CardHeader("Presets", "one tap sets the categories")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            DiagPreset.entries.forEach { preset ->
                FilterChip(
                    selected = capture.preset == preset,
                    onClick = { diag.applyPreset(preset) },
                    label = { Text(preset.label) },
                )
            }
        }
        val autoOff = capture.earliestAutoOff
        val note = when {
            capture.preset == DiagPreset.EVERYTHING -> DiagPreset.EVERYTHING.note
            capture.anyVerbose && autoOff != null ->
                "Verbose on for $verboseNames: auto-off in ${formatUntil(autoOff)}. Normal is what the app logged " +
                    "before; Off still keeps W and E lines."
            capture.anyVerbose ->
                "Verbose on for $verboseNames. Normal is what the app logged before; Off still keeps W and E lines."
            else ->
                capture.preset?.note ?: "Normal everywhere: what the app logged before. Off still keeps W and E lines."
        }
        Text(
            note,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                capture.preset == DiagPreset.EVERYTHING -> DiagColors.problem
                capture.anyVerbose -> DiagColors.attention
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun SamplingCard(capture: CaptureState, diag: Diagnostics) {
    val current = SampleRate.of(capture.sampleMs)
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging?.let { SampleRate.entries[it] } ?: current
    DiagCard {
        CardHeader("Sampling", shown.costNote)
        Slider(
            value = (dragging ?: current.ordinal).toFloat(),
            onValueChange = { dragging = it.roundToInt().coerceIn(0, SampleRate.entries.size - 1) },
            onValueChangeFinished = {
                val picked = SampleRate.entries[dragging ?: current.ordinal]
                dragging = null
                diag.setSampleMs(picked.ms)
            },
            valueRange = 0f..(SampleRate.entries.size - 1).toFloat(),
            steps = SampleRate.entries.size - 2,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            SampleRate.entries.forEach { rate ->
                Mono(
                    rate.label,
                    color = if (rate == shown) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Caption(
            "Drives the Live tick and the Memory line cadence. The 5-min ring always holds 5 min.",
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Expected level of [category] under [preset] (everything not listed is Normal). */
private fun DiagPreset.levelOf(category: DiagCategory): DiagLevel = when (category) {
    in verbose -> DiagLevel.VERBOSE
    in off -> DiagLevel.OFF
    else -> DiagLevel.NORMAL
}

@Composable
private fun CustomizeCard(capture: CaptureState, diag: Diagnostics, recording: Boolean) {
    val differs = remember(capture) {
        val preset = capture.preset
        capture.levels.any { (c, level) ->
            c != DiagCategory.APP && level != (preset?.levelOf(c) ?: DiagLevel.NORMAL)
        }
    }
    var expanded by remember { mutableStateOf(differs) }
    DiagCard(padding = 0) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
        ) {
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text("Customize", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Caption(
                when {
                    recording -> "all Verbose while recording"
                    differs -> "changed by hand"
                    else -> "per category"
                },
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                DiagCategory.entries.forEach { category ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    CategoryRow(
                        category = category,
                        level = capture.levels[category] ?: DiagLevel.NORMAL,
                        until = capture.verboseUntil[category],
                        enabled = !recording && category != DiagCategory.APP,
                        onLevel = { diag.setLevel(category, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: DiagCategory,
    level: DiagLevel,
    until: Long?,
    enabled: Boolean,
    onLevel: (DiagLevel) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                category.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Mono(
                if (category == DiagCategory.APP) "always on · ${category.detail}" else category.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 11.sp,
            )
            if (level == DiagLevel.VERBOSE && until != null) {
                Caption("auto-off in ${formatUntil(until)}", color = DiagColors.attention)
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.width(196.dp).height(36.dp)) {
            DiagLevel.entries.forEachIndexed { index, l ->
                SegmentedButton(
                    selected = level == l,
                    onClick = { onLevel(l) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, DiagLevel.entries.size),
                    icon = {},
                    label = { Text(l.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

@Composable
private fun TraceCard(kind: TraceKind, capture: CaptureState, diag: Diagnostics) {
    val on = kind in capture.traces
    val bytes = capture.traceBytes[kind] ?: 0L
    DiagCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(kind.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Caption(kind.detail)
                Mono(
                    "${kind.fileStem}.ring · ${if (on) formatBytes(bytes) else "off"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(checked = on, onCheckedChange = { diag.setTrace(kind, it) })
        }
        if (kind == TraceKind.NET) {
            NetTraceOptions(capture = capture, diag = diag)
        }
    }
}

private const val VISIBLE_SOURCE_CHIPS = 8

@Composable
private fun NetTraceOptions(capture: CaptureState, diag: Diagnostics) {
    val sources by produceState(initialValue = emptyList<Pair<String, String>>()) {
        value = runCatching { diag.traceableSources() }.getOrDefault(emptyList())
    }
    var picker by remember { mutableStateOf(false) }
    val selected = capture.netTraceSources
    // Selected ones first so a picked APK source is visible among the chips
    val ordered = remember(sources, selected) { sources.sortedByDescending { it.first in selected } }
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
    Caption(if (selected.isEmpty()) "Sources · all (tap to limit)" else "Sources · ${selected.size} picked")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        ordered.take(VISIBLE_SOURCE_CHIPS).forEach { (key, name) ->
            FilterChip(
                selected = key in selected,
                onClick = { diag.setNetTraceSources(if (key in selected) selected - key else selected + key) },
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        val more = ordered.size - VISIBLE_SOURCE_CHIPS
        if (more > 0) {
            FilterChip(selected = false, onClick = { picker = true }, label = { Text("+ $more more…") })
        }
        if (selected.isNotEmpty()) {
            FilterChip(selected = false, onClick = { diag.setNetTraceSources(emptySet()) }, label = { Text("all") })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Headers", style = MaterialTheme.typography.bodyLarge)
            Caption("Cookie, Set-Cookie and Authorization values are never kept")
        }
        Switch(
            checked = capture.netTraceHeaders,
            onCheckedChange = { diag.setNetTraceOptions(headers = it, bodyHead = capture.netTraceBodyHead) },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Body head", style = MaterialTheme.typography.bodyLarge)
            Caption("first 8 KB of text, JSON, XML and JS responses")
        }
        Switch(
            checked = capture.netTraceBodyHead,
            onCheckedChange = { diag.setNetTraceOptions(headers = capture.netTraceHeaders, bodyHead = it) },
        )
    }
    if (picker) {
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("Sources to trace") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    sources.forEach { (key, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    diag.setNetTraceSources(if (key in selected) selected - key else selected + key)
                                }
                                .padding(vertical = 2.dp),
                        ) {
                            Checkbox(checked = key in selected, onCheckedChange = null)
                            Text(
                                name,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = false }) { Text("Done") } },
        )
    }
}
