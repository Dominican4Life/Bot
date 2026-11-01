package eu.kanade.tachiyomi.ui.diagnostics

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.diagnostics.ProbeDescriptor
import eu.kanade.tachiyomi.diagnostics.ProbeHook
import eu.kanade.tachiyomi.diagnostics.ProbeStatus
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch

/**
 * KMK (r97): The Probes tab (ph-07): the Scripts list (code icon, name, Experimental badge, hook chips in mono, the
 * cost badge, "2 min ago · 41 events · auto-off 23 h" or "never run", the switch), the header line from
 * [eu.kanade.tachiyomi.diagnostics.ProbesSnapshot], the Add menu (New script → hook picker, Import file, Paste) and,
 * on tablets, the "How probes run" card. A row opens [ProbeEditorScreen].
 */
@Composable
internal fun ProbesTab(model: DiagnosticsScreenModel, header: HeaderSlot, tablet: Boolean) {
    val diag = model.diag
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snapshot by diag.probes.collectAsState()
    var addMenu by remember { mutableStateOf(false) }
    var hookPicker by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        header.subtitle.value = "Probes · scripts that watch the app"
        onDispose { header.subtitle.value = null }
    }
    LaunchedEffect(Unit) {
        model.takeProbeId()?.let { navigator.push(ProbeEditorScreen(it)) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val code = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    ?: error("Couldn't read the file")
                val descriptor = diag.importProbe(context.fileName(uri) ?: "probe.js", code)
                navigator.push(ProbeEditorScreen(descriptor.id))
            }.onFailure { context.toast(it.message ?: "Import failed") }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        ) {
            item("scripts") {
                DiagCard(padding = 0) {
                    val perEvent = formatMsPerEvent(snapshot.totalMsPerEvent).removeSuffix("/event")
                    CardHeader(
                        "Scripts",
                        "${snapshot.runningCount} running · $perEvent per event in total · " +
                            "${snapshot.experimentalCount} Experimental",
                        modifier = Modifier.padding(16.dp),
                    )
                    if (snapshot.probes.isEmpty()) {
                        Caption(
                            "No scripts yet. Add one: a few lines of JS that run on a hook (a response, a JS call, " +
                                "an mpv event…) and write to the log.",
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        )
                    }
                    snapshot.probes.forEach { probe ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        ProbeRow(
                            probe = probe,
                            status = snapshot.status[probe.id],
                            onOpen = { navigator.push(ProbeEditorScreen(probe.id)) },
                            onToggle = { diag.setProbeEnabled(probe.id, it) },
                        )
                    }
                }
            }
            if (tablet) {
                item("how") { HowProbesRunCard(snapshot.runtimeMb) }
            }
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(
                text = { Text("Add") },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { addMenu = true },
            )
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text("New script") },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    onClick = {
                        addMenu = false
                        hookPicker = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Import file") },
                    leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    trailingIcon = { Mono(".js", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {
                        addMenu = false
                        importLauncher.launch(arrayOf("text/javascript", "application/javascript", "text/plain"))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Paste") },
                    leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                    trailingIcon = { Mono("clipboard", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {
                        addMenu = false
                        val text = clipboard.getText()?.text
                        if (text.isNullOrBlank()) {
                            context.toast("The clipboard is empty")
                        } else {
                            navigator.push(ProbeEditorScreen(null, null, text))
                        }
                    },
                )
            }
            DropdownMenu(expanded = hookPicker, onDismissRequest = { hookPicker = false }) {
                Text(
                    "Hook",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                ProbeHook.entries.forEach { hook ->
                    DropdownMenuItem(
                        text = { Mono(hook.id, size = 13.sp) },
                        onClick = {
                            hookPicker = false
                            navigator.push(ProbeEditorScreen(null, hook))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProbeRow(probe: ProbeDescriptor, status: ProbeStatus?, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) { Icon(Icons.Outlined.Code, contentDescription = null, tint = DiagColors.probe) }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    probe.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (probe.experimental) {
                    Spacer(Modifier.width(8.dp))
                    KindBadge("Experimental", DiagColors.attention)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                probe.hooks.forEach { HookChip(it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Mono(
                    formatMsPerEvent(status?.avgMs ?: 0f),
                    size = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(8.dp))
                Caption(statusLine(probe, status))
            }
            status?.offReason?.let { Caption("off: $it", color = DiagColors.attention) }
            status?.lastError?.let { Caption(it, color = DiagColors.problem) }
        }
        Switch(checked = probe.enabled, onCheckedChange = onToggle)
    }
}

private fun statusLine(probe: ProbeDescriptor, status: ProbeStatus?): String {
    val st = status ?: return "never run"
    val last = st.lastRunAt ?: return "never run"
    val autoOff = if (probe.autoOffAt > 0L && probe.enabled) " · auto-off ${formatUntil(probe.autoOffAt)}" else ""
    val dropped = if (st.dropped > 0L) " · ${st.dropped} dropped" else ""
    return "${formatAgo(last)} · ${formatCount(st.events)} events$autoOff$dropped"
}

@Composable
internal fun HookChip(id: String, selected: Boolean = true, onClick: (() -> Unit)? = null) {
    val color = if (selected) DiagColors.probe else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        id,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) DiagColors.probe.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun HowProbesRunCard(runtimeMb: Float) {
    DiagCard {
        CardHeader("How probes run", "runtime ${formatMb(runtimeMb)} MB while one is on")
        Caption(
            "One small QuickJS context for all scripts, created when the first probe is enabled and closed a minute " +
                "after the last one is disabled. Each hook site checks one volatile mask before building anything, " +
                "so " +
                "a probe that is off costs nothing. Events reach the script through a 256-entry queue; an " +
                "Experimental probe on net.response runs in line with the request (200 ms deadline) and may " +
                "replace the body. A probe switches itself off past its budget (per event, averaged over 50), past " +
                "5 % of one core over a minute, or at its auto-off time. No network, storage, timers or await.",
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

internal fun Context.fileName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment
