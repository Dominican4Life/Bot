package eu.kanade.tachiyomi.ui.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.diagnostics.ConsoleLine
import eu.kanade.tachiyomi.diagnostics.Diag
import eu.kanade.tachiyomi.diagnostics.Diagnostics
import eu.kanade.tachiyomi.diagnostics.ProbeAutoOff
import eu.kanade.tachiyomi.diagnostics.ProbeBudget
import eu.kanade.tachiyomi.diagnostics.ProbeDescriptor
import eu.kanade.tachiyomi.diagnostics.ProbeDraft
import eu.kanade.tachiyomi.diagnostics.ProbeHook
import eu.kanade.tachiyomi.diagnostics.ProbeLint
import eu.kanade.tachiyomi.diagnostics.ProbeStatus
import eu.kanade.tachiyomi.ui.codeeditor.CodeEditor
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import java.io.File
import java.util.Locale

/**
 * KMK (r97): The probe editor (ph-08): hook chips (a tap inserts or removes a `probe.on` stub; the hooks the code
 * declares are lit), the code area ([CodeEditor], read-only import), lint results under it (errors block Save),
 * the status line, Run once (saves first, then replays the newest events through the saved code), the ⋮ menu
 * (Rename · Duplicate · Export · Delete), the Probe settings card (the five setters), the Hook reference card and
 * the Console pane (the engine's console filtered by this probe; Pause stops auto-scroll; Clear). The status strip
 * stays at the top. Back returns to the Probes tab.
 */
internal class ProbeEditorScreen(
    private val id: String?,
    private val hook: ProbeHook? = null,
    private val initialCode: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { ProbeEditorModel(Diag.get(), id, hook, initialCode) }
        val state by model.state.collectAsState()
        val probes by model.diag.probes.collectAsState()
        val descriptor = state.id?.let { pid -> probes.probes.firstOrNull { it.id == pid } }
        val status = state.id?.let { probes.status[it] }
        var menu by remember { mutableStateOf(false) }
        var renameDialog by remember { mutableStateOf(false) }
        var renameValue by remember { mutableStateOf("") }
        var deleteDialog by remember { mutableStateOf(false) }
        var discardDialog by remember { mutableStateOf(false) }

        // Unsaved code: back (system or the header arrow) asks before leaving
        BackHandler(enabled = state.dirty) { discardDialog = true }
        val back: () -> Unit = { if (state.dirty) discardDialog = true else navigator.pop() }

        LaunchedEffect(state.toast) {
            state.toast?.let {
                context.toast(it)
                model.toastShown()
            }
        }

        Scaffold { padding ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                Column(modifier = Modifier.widthIn(max = 980.dp).fillMaxSize()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp),
                    ) {
                        IconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(
                                state.name,
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "probe · " +
                                    state.declaredHooks.joinToString(" · ") { it.id }.ifEmpty { "no hook yet" } +
                                    (descriptor?.sourceFilter?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalButton(enabled = !state.busy, onClick = { model.runOnce() }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Run once")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Save") },
                                    enabled = state.dirty && state.lint.none { it.isError },
                                    onClick = {
                                        menu = false
                                        model.save()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        menu = false
                                        renameValue = state.name
                                        renameDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    onClick = {
                                        menu = false
                                        model.duplicate { newId -> navigator.replace(ProbeEditorScreen(newId)) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export") },
                                    onClick = {
                                        menu = false
                                        runCatching {
                                            val file = File(context.cacheDir, "${state.name.ifBlank { "probe" }}.js")
                                            file.writeText(state.code)
                                            val uri = file.getUriCompat(context)
                                            context.startActivity(uri.toShareIntent(context, type = "text/javascript"))
                                        }.onFailure { context.toast(it.message ?: "Couldn't export") }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    enabled = state.id != null,
                                    onClick = {
                                        menu = false
                                        deleteDialog = true
                                    },
                                )
                            }
                        }
                    }
                    StatusStripBar(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        item("hooks") {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                ProbeHook.entries.forEach { h ->
                                    val on = h in state.declaredHooks
                                    FilterChip(
                                        selected = on,
                                        onClick = { model.toggleHook(h) },
                                        label = { Mono(h.id, size = 13.sp) },
                                    )
                                }
                            }
                        }
                        item("editor") {
                            EditorCard(state = state, status = status, descriptor = descriptor, model = model)
                        }
                        item("settings") {
                            SettingsCard(descriptor = descriptor, diag = model.diag, onNeedSave = { model.save() })
                        }
                        item("reference") {
                            ReferenceCard(hook = state.declaredHooks.firstOrNull() ?: hook)
                        }
                        item("console") {
                            ConsolePane(
                                diag = model.diag,
                                probeId = state.id,
                                probeName = state.name,
                                hooks = state.declaredHooks,
                                source = descriptor?.sourceFilter,
                                events = status?.events ?: 0L,
                            )
                        }
                    }
                }
            }
        }

        if (renameDialog) {
            AlertDialog(
                onDismissRequest = { renameDialog = false },
                title = { Text("Rename probe") },
                text = {
                    OutlinedTextField(value = renameValue, onValueChange = { renameValue = it }, singleLine = true)
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            renameDialog = false
                            model.rename(renameValue.trim())
                        },
                    ) { Text("Rename") }
                },
                dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } },
            )
        }
        if (discardDialog) {
            AlertDialog(
                onDismissRequest = { discardDialog = false },
                title = { Text("Discard changes?") },
                text = { Text("${state.name}.js has unsaved changes. Save it first, or leave and lose them.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            discardDialog = false
                            navigator.pop()
                        },
                    ) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { discardDialog = false }) { Text("Keep editing") }
                        if (state.lint.none { it.isError }) {
                            TextButton(
                                onClick = {
                                    discardDialog = false
                                    model.save()
                                },
                            ) { Text("Save") }
                        }
                    }
                },
            )
        }
        if (deleteDialog) {
            AlertDialog(
                onDismissRequest = { deleteDialog = false },
                title = { Text("Delete ${state.name}?") },
                text = { Text("The script file and its settings are removed. Console lines stay until cleared.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteDialog = false
                            model.delete { navigator.pop() }
                        },
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun EditorCard(
    state: ProbeEditorState,
    status: ProbeStatus?,
    descriptor: ProbeDescriptor?,
    model: ProbeEditorModel,
) {
    val errorLine = state.lint.firstOrNull { it.isError }?.line ?: state.runErrorLine
    DiagCard(padding = 0) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        ) {
            Text(
                "${state.name}.js",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Mono(
                if (state.dirty) " · unsaved" else " · saved",
                color = if (state.dirty) DiagColors.attention else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Mono(
                formatMsPerEvent(status?.avgMs ?: 0f),
                size = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (state.dirty) {
                Spacer(Modifier.width(8.dp))
                Button(enabled = state.lint.none { it.isError } && !state.busy, onClick = { model.save() }) {
                    Text("Save")
                }
            }
        }
        var findOpen by remember { mutableStateOf(false) }
        CodeEditor(
            code = state.code,
            onCodeChange = model::setCode,
            json = false,
            findOpen = findOpen,
            onOpenFind = { findOpen = true },
            onCloseFind = { findOpen = false },
            revealLine = errorLine,
            modifier = Modifier.fillMaxWidth().height(360.dp),
        )
        if (state.lint.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                state.lint.forEach { l ->
                    Mono(
                        "${if (l.isError) "error" else "warning"} · line ${l.line} · ${l.message}",
                        color = if (l.isError) DiagColors.problem else DiagColors.attention,
                        maxLines = 2,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val running = status?.running == true
            Dot(if (running) DiagColors.good else MaterialTheme.colorScheme.outlineVariant, size = 8)
            Mono(
                if (running) " running" else " stopped",
                color = if (running) DiagColors.good else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Mono("   source ${descriptor?.sourceFilter ?: "any"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Mono(
                "   Experimental ${if (descriptor?.experimental == true) "on" else "off"}",
                color = if (descriptor?.experimental == true) {
                    DiagColors.attention
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Mono("   ${state.lines} lines", color = MaterialTheme.colorScheme.onSurfaceVariant)
            status?.offReason?.let { Mono("   off: $it", color = DiagColors.attention) }
            state.runSummary?.let { Mono("   $it", color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun SettingsCard(descriptor: ProbeDescriptor?, diag: Diagnostics, onNeedSave: () -> Unit) {
    DiagCard {
        CardHeader("Probe settings", if (descriptor == null) "save the script first" else "applied at once")
        if (descriptor == null) {
            TextButton(onClick = onNeedSave) { Text("Save now") }
            return@DiagCard
        }
        SettingRow("Enabled", "runs on its hooks until auto-off or budget") {
            Switch(checked = descriptor.enabled, onCheckedChange = { diag.setProbeEnabled(descriptor.id, it) })
        }
        SettingRow("Experimental", "may modify data: r.replaceBody() runs in line with the request") {
            Switch(
                checked = descriptor.experimental,
                onCheckedChange = { diag.setProbeExperimental(descriptor.id, it) },
            )
        }
        SettingRow("Auto-off", "switches itself off after") {}
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProbeAutoOff.entries.forEach { a ->
                FilterChip(
                    selected = descriptor.autoOff == a,
                    onClick = { diag.setProbeAutoOff(descriptor.id, a) },
                    label = { Text(a.label) },
                )
            }
        }
        SettingRow("Budget", "per event, averaged over the last 50; past it the probe is switched off") {}
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProbeBudget.entries.forEach { b ->
                FilterChip(
                    selected = descriptor.budget == b,
                    onClick = { diag.setProbeBudget(descriptor.id, b) },
                    label = { Text(b.label) },
                )
            }
        }
        SettingRow("Marker on Live graph", "every log.* call draws a teal marker") {
            Switch(checked = descriptor.markerOnGraph, onCheckedChange = { diag.setProbeMarker(descriptor.id, it) })
        }
    }
}

@Composable
private fun SettingRow(title: String, caption: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Caption(caption)
        }
        trailing()
    }
}

@Composable
private fun ReferenceCard(hook: ProbeHook?) {
    DiagCard {
        CardHeader("Hook reference", hook?.id ?: "pick a hook above")
        Mono(
            "probe.on(hook, filter, fn) · filter: { source?, host?, fn?, event? } or null",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Mono(
            "log.info(msg, obj?) · log.warn · log.error · log.debug",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        hook?.reference?.forEach { (field, note) ->
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Mono(field, color = DiagColors.probe, modifier = Modifier.width(190.dp), maxLines = 2)
                Caption(note, modifier = Modifier.weight(1f))
            }
        }
        Caption(
            "No require, network, storage, timers or await. A file may start with // @name my-probe.",
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ConsolePane(
    diag: Diagnostics,
    probeId: String?,
    probeName: String,
    hooks: Set<ProbeHook>,
    source: String?,
    events: Long,
) {
    val console by diag.console.collectAsState()
    var paused by remember { mutableStateOf(false) }
    val lines = remember(console, probeId, probeName) {
        console.filter { it.probeId == probeId || (probeId == null && it.probeName == probeName) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, paused) {
        if (!paused && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    DiagCard(padding = 0) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        ) {
            Text("Console", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            HookChip("probe:$probeName")
            Spacer(Modifier.width(10.dp))
            Mono(
                "${formatCount(events)} events",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { paused = !paused }) { Text(if (paused) "Resume" else "Pause") }
            TextButton(onClick = { diag.clearConsole(probeId) }) { Text("Clear") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(260.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            items(lines.size, key = { i -> "${lines[i].time}_$i" }) { i -> ConsoleRow(lines[i]) }
            item("footer") {
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    Mono(
                        formatHmsMillis(System.currentTimeMillis()).dropLast(3) + "…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(100.dp),
                    )
                    Box(modifier = Modifier.width(2.dp).height(14.dp).background(MaterialTheme.colorScheme.primary))
                    Mono(
                        "  waiting for the next ${hooks.firstOrNull()?.id ?: "event"}" +
                            (source?.let { " from $it" } ?: ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleRow(line: ConsoleLine) {
    val color = when (line.level) {
        'E' -> DiagColors.problem
        'W' -> DiagColors.attention
        'D' -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    var open by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 3.dp)) {
        Mono(
            formatHmsMillis(line.time),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Mono("${line.level}", weight = FontWeight.Bold, color = color, modifier = Modifier.width(16.dp))
        Mono(line.text, color = color, maxLines = if (open) Int.MAX_VALUE else 4, modifier = Modifier.weight(1f))
    }
}

// --- model ---------------------------------------------------------------------------------------------------------

internal data class ProbeEditorState(
    val id: String?,
    val name: String,
    val code: String,
    val savedCode: String,
    val lint: List<ProbeLint> = emptyList(),
    val busy: Boolean = false,
    val runSummary: String? = null,
    val runErrorLine: Int? = null,
    val toast: String? = null,
) {
    val dirty: Boolean get() = code != savedCode
    val lines: Int get() = code.count { it == '\n' } + 1
    val declaredHooks: Set<ProbeHook> = hooksIn(code)
}

/** Hooks the code declares with `probe.on('…')` (the engine confirms them at save). */
private val HOOK_CALL = Regex("""probe\.on\(\s*['"]([a-z.]+)['"]""")

private fun hooksIn(code: String): Set<ProbeHook> =
    HOOK_CALL.findAll(code).mapNotNull { ProbeHook.byId(it.groupValues[1]) }.toSet()

private val NAME_LINE = Regex("""^\s*//\s*@name\s+(\S+)""", RegexOption.MULTILINE)

internal class ProbeEditorModel(
    val diag: Diagnostics,
    id: String?,
    hook: ProbeHook?,
    initialCode: String?,
) : ScreenModel {

    private val _state = MutableStateFlow(
        ProbeEditorState(
            id = id,
            name = initialCode?.let { NAME_LINE.find(it)?.groupValues?.get(1) }
                ?: if (id == null) "new-probe" else "…",
            code = initialCode ?: hook?.let(diag::probeTemplate) ?: "",
            savedCode = if (id == null) "" else (initialCode ?: ""),
        ),
    )
    val state: StateFlow<ProbeEditorState> = _state.asStateFlow()

    init {
        if (id != null) {
            screenModelScope.launch {
                val code = runCatching { diag.probeCode(id) }.getOrDefault("")
                val name = diag.probes.value.probes.firstOrNull { it.id == id }?.name
                    ?: NAME_LINE.find(code)?.groupValues?.get(1)
                    ?: id
                _state.update { it.copy(name = name, code = code, savedCode = code, lint = diag.lintProbe(code)) }
            }
        } else {
            _state.update { it.copy(lint = diag.lintProbe(it.code)) }
        }
    }

    fun setCode(code: String) {
        _state.update { it.copy(code = code, lint = diag.lintProbe(code), runErrorLine = null) }
    }

    fun toastShown() {
        _state.update { it.copy(toast = null) }
    }

    /** A chip tap: adds a `probe.on` stub for the hook, or removes the one the code has for it. */
    fun toggleHook(hook: ProbeHook) {
        val code = _state.value.code
        val call = HOOK_CALL.findAll(code).firstOrNull { it.groupValues[1] == hook.id }
        val next = if (call == null) {
            val arg = if (hook.id.startsWith("net.")) "r" else "e"
            val stub = "probe.on('${hook.id}', null, ($arg) => {\n  log.info('${hook.id}', { })\n})\n"
            if (code.isBlank()) stub else code.trimEnd() + "\n\n" + stub
        } else {
            // Remove from the call to the first line that is just "})" after it
            val start = code.lastIndexOf('\n', call.range.first).let { if (it < 0) 0 else it + 1 }
            val closeAt = Regex("""^\}\)\s*$""", RegexOption.MULTILINE).find(code, call.range.last)
            val end = closeAt?.range?.last?.plus(1) ?: code.length
            (code.substring(0, start) + code.substring(end)).trimEnd() + "\n"
        }
        setCode(next)
    }

    fun save(then: ((ProbeDescriptor) -> Unit)? = null) {
        val s = _state.value
        if (s.lint.any { it.isError }) {
            _state.update { it.copy(toast = "Fix the lint errors first") }
            return
        }
        screenModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { diag.saveProbe(ProbeDraft(s.id, s.name, s.code)) }
                .onSuccess { d ->
                    _state.update {
                        it.copy(id = d.id, name = d.name, savedCode = s.code, busy = false, toast = "Saved")
                    }
                    then?.invoke(d)
                }
                .onFailure { e -> _state.update { it.copy(busy = false, toast = e.message ?: "Save failed") } }
        }
    }

    fun runOnce() {
        val run: (ProbeDescriptor) -> Unit = { d ->
            screenModelScope.launch {
                _state.update { it.copy(busy = true) }
                runCatching { diag.runProbeOnce(d.id) }
                    .onSuccess { r ->
                        _state.update {
                            it.copy(
                                busy = false,
                                runSummary = if (r.ok) {
                                    "run once · ${r.replayed} replayed in ${String.format(Locale.US, "%.1f", r.ms)} ms"
                                } else {
                                    r.error ?: "run failed"
                                },
                                runErrorLine = r.errorLine,
                            )
                        }
                    }
                    .onFailure { e -> _state.update { it.copy(busy = false, toast = e.message ?: "Run failed") } }
            }
        }
        val s = _state.value
        if (s.dirty || s.id == null) save(run) else diag.probes.value.probes.firstOrNull { it.id == s.id }?.let(run)
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        _state.update { it.copy(name = name) }
        if (_state.value.id != null) save()
    }

    fun duplicate(then: (String) -> Unit) {
        val s = _state.value
        screenModelScope.launch {
            runCatching { diag.saveProbe(ProbeDraft(null, "${s.name}-copy", s.code)) }
                .onSuccess { then(it.id) }
                .onFailure { e -> _state.update { it.copy(toast = e.message ?: "Duplicate failed") } }
        }
    }

    fun delete(then: () -> Unit) {
        val id = _state.value.id ?: return
        screenModelScope.launch {
            runCatching { diag.deleteProbe(id) }
                .onSuccess { then() }
                .onFailure { e -> _state.update { it.copy(toast = e.message ?: "Delete failed") } }
        }
    }
}
