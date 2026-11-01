package eu.kanade.tachiyomi.ui.diagnostics

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.diagnostics.ReportOptions
import eu.kanade.tachiyomi.diagnostics.ReportPart
import eu.kanade.tachiyomi.diagnostics.ReportPartSize
import eu.kanade.tachiyomi.diagnostics.ReportResult
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import java.io.File

/**
 * KMK (r97): The Report tab (ph-10): the checklist of bundle parts with their sizes (refreshed on open), the two
 * privacy switches, "Create report · ≈ N MB" (disabled while the engine builds; shows its progress), "Copy live
 * snapshot as text", and the footer with the last report. When a recording just ended the engine sets
 * `pendingShare`: the share sheet opens once and the tab tells the engine it was consumed.
 */
@Composable
internal fun ReportTab(model: DiagnosticsScreenModel, header: HeaderSlot) {
    val diag = model.diag
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val report by diag.report.collectAsState()
    var options by remember { mutableStateOf(ReportOptions()) }
    var sizes by remember { mutableStateOf<List<ReportPartSize>>(emptyList()) }

    DisposableEffect(Unit) {
        header.subtitle.value = "Report · build and share a bundle"
        onDispose { header.subtitle.value = null }
    }
    LaunchedEffect(Unit) {
        sizes = runCatching { diag.refreshReportSizes() }.getOrDefault(report.sizes)
    }
    // A recording's report: share it once, then tell the engine
    LaunchedEffect(report.pendingShare) {
        val pending = report.pendingShare ?: return@LaunchedEffect
        share(context, pending)
        diag.consumePendingShare()
    }
    val shownSizes = if (sizes.isEmpty()) report.sizes else sizes
    val sizeOf = remember(shownSizes) { shownSizes.associateBy { it.part } }
    // Zipped size: the engine's estimate (sizes ÷ 3) for the default part set, else the same ratio over the picked parts
    val estimate = remember(options.parts, shownSizes, report.estimateBytes) {
        if (options.parts == ReportOptions().parts && report.estimateBytes > 0L) {
            report.estimateBytes
        } else {
            shownSizes.filter { it.part in options.parts }.sumOf { it.bytes } / 3
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
    ) {
        item("include") {
            DiagCard(padding = 0) {
                CardHeader("Include", "sizes known before zipping", modifier = Modifier.padding(16.dp))
                ReportPart.entries.forEach { part ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    val checked = part in options.parts
                    val size = sizeOf[part]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val parts = if (checked) options.parts - part else options.parts + part
                                options = options.copy(parts = parts)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(part.label, style = MaterialTheme.typography.bodyLarge)
                            Caption(size?.detail?.takeIf { it.isNotBlank() } ?: part.description)
                        }
                        Mono(size?.let { formatBytes(it.bytes) } ?: "…", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        item("privacy") {
            DiagCard {
                CardHeader("Privacy", "what leaves the phone")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Strip source names and URLs", style = MaterialTheme.typography.bodyLarge)
                        Mono("source-<id>, host-<n> in every file", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = options.redact, onCheckedChange = { options = options.copy(redact = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Include capture settings", style = MaterialTheme.typography.bodyLarge)
                        Caption("capture.json: levels, traces, sampling, probes")
                    }
                    Switch(
                        checked = options.includeCapture,
                        onCheckedChange = { options = options.copy(includeCapture = it) },
                    )
                }
                Caption(
                    "Cookie and authorization values are removed either way. The name map stays on the phone as a " +
                        "-keys.json next to the zip.",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item("create") {
            Column {
                Button(
                    enabled = !report.building && options.parts.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            runCatching { diag.createReport(options) }
                                .onSuccess { share(context, it) }
                                .onFailure { context.toast(it.message ?: "Couldn't build the report") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (report.building) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(report.progress ?: "Building…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Icon(Icons.Outlined.Share, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Create report · ≈ ${formatBytes(estimate)}", fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(diag.liveSnapshotText()))
                        context.toast("Snapshot copied")
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Copy live snapshot as text")
                }
                val last = report.last
                Caption(
                    if (last != null) {
                        "Last report ${formatHm(last.createdAt)} · ${formatBytes(last.bytes)} · " +
                            "${File(last.path).name}. " +
                            "The last three are kept in cacheDir."
                    } else {
                        "No report yet. The last three are kept in cacheDir."
                    },
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
                if (last != null) {
                    Text(
                        "Share the last report again",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp).clickable { share(context, last) },
                    )
                }
            }
        }
    }
}

private fun share(context: Context, result: ReportResult) {
    runCatching {
        val file = File(result.path)
        context.startActivity(file.getUriCompat(context).toShareIntent(context, type = "application/zip"))
    }.onFailure { context.toast(it.message ?: "Couldn't share the report") }
}
