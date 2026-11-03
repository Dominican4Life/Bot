package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.lab.IssueReport
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r96): the Report pane (D§2.8): the one line first in a red-edged card, the facts under it, the bundle
 * checklist with sizes, the failing request chain, the privacy switches and Share.
 * KMK (r115, mock M3Report): captioned cards ("Goes in", "Privacy", "Always removed") and "About this source".
 */
@Composable
internal fun ReportPane(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val run = state.lastRun
    val lint = state.lint ?: state.source?.lastLint
    val red = run?.steps?.firstOrNull { it.status == StepStatus.Red }
    val line = IssueReport.Summary.line(run, lint)
    val previousGreen = red != null && state.previousRun?.steps?.firstOrNull { it.id == red.id }?.status == StepStatus.Green
    val total = state.reportPlan.sumOf { it.bytes }
    // KMK (r106): the revamp's frame: one centred column (at most 860 dp) of 16 dp cards on the plain background
    // KMK (r115, mock M3Report): "Report a problem" (the line, what goes in, what is always removed, the switches,
    // Share) and, from 840 dp, "About this source" beside it (else under it)
    val about: @Composable () -> Unit = { AboutSourceCard(state) }
    BoxWithConstraints(contentAlignment = Alignment.TopCenter, modifier = modifier) {
        val wide = maxWidth >= 840.dp
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.widthIn(max = 1100.dp).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = if (wide) 0.dp else 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                item(key = "line") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, if (red != null) StripColor.Red.tint() else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Report a problem", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "Makes one zip you can send. Nothing leaves the app until you share it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                            )
                            SelectionContainer {
                                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = buildString {
                                    append("source v${run?.version ?: state.source?.version ?: 0}")
                                    append(" · rules v${lint?.rulesVersion ?: run?.rulesVersion ?: "—"}")
                                    append(" · app r${run?.appRelease?.ifBlank { "local" } ?: "—"}")
                                    run?.let { append(" · ${SourceLabScreenModel.agoText(it.startedAt)}") }
                                    if (previousGreen) append(" · the same step passed ${SourceLabScreenModel.agoText(state.previousRun?.startedAt ?: 0L)}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row {
                                TextButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(line))
                                        context.toast("Copied")
                                    },
                                ) { Text("Copy the line") }
                                if (red != null) {
                                    TextButton(onClick = { model.setTab(LabTab.Test) }) { Text("Open the step") }
                                }
                            }
                        }
                    }
                }
                item(key = "bundle") {
                    LabCaptionCard(caption = "Goes in · ≈ ${total / 1024} KB") {
                        if (state.reportPlan.isEmpty()) {
                            Text("Save the source first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.reportPlan.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                                Text(entry.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                entry.note?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Text(sizeText(entry.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (red != null && red.requests.isNotEmpty()) {
                    item(key = "request") {
                        MonoCard(
                            title = "The failing request · ${red.name}",
                            text = red.requests.joinToString("\n") { r ->
                                "${r.method} ${r.url} -> ${r.status ?: "no reply"} in ${r.ms} ms" +
                                    (if (r.viaBrowser) " (through the browser)" else "") + (r.error?.let { " · $it" }.orEmpty())
                            },
                        )
                    }
                }
                item(key = "privacy") {
                    LabCaptionCard(caption = "Privacy") {
                        val options = state.reportOptions
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Strip other hosts and names", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "The source's own host and the failing hoster stay readable: they are the problem. Other sites' addresses become host-1, host-2…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = options.redactHosts, onCheckedChange = { model.setReportOptions(options.copy(redactHosts = it)) })
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Include the source's settings", style = MaterialTheme.typography.bodyMedium)
                                Text("Off by default; passwords, tokens and cookies show as <set>.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = options.includeSettings, onCheckedChange = { model.setReportOptions(options.copy(includeSettings = it)) })
                        }
                        LabCaption("Always removed", modifier = Modifier.padding(top = 12.dp))
                        Text(
                            "Cookies, logins and tokens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                item(key = "share") {
                    Column {
                        Button(
                            onClick = { model.buildReport(context) },
                            enabled = !state.reportBuilding && state.reportPlan.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(if (state.reportBuilding) "  Building…" else "  Share report · ≈ ${total / 1024} KB")
                        }
                        state.reportFile?.let {
                            Text(
                                "Last report: ${it.name} (${it.length() / 1024} KB in the app's cache; the last five are kept)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                if (!wide) item(key = "about") { about() }
            }
            if (wide) {
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(end = 16.dp, top = 16.dp, bottom = 16.dp),
                ) { about() }
            }
        }
    }
}

/** KMK (r115, mock M3Report): what this source is: site, kind, template, source id, where it came from, version */
@Composable
private fun AboutSourceCard(state: SourceLabScreenModel.State) {
    // remembered before the early return (never a remember after a condition)
    val key = state.source?.key
    val id = remember(key) { key?.let { k -> runCatching { Injekt.get<JsSourceManager>().idOf(k).toString() }.getOrNull() } }
    val source = state.source ?: return
    val lab = source.lab
    val site = lab?.siteUrl?.substringAfter("://", "")?.substringBefore('/')?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    val rows = listOfNotNull(
        site?.let { "Site" to it },
        "Kind" to "${if (source.anime) "Anime" else "Manga"} · ${if (source.json) "JSON" else "JS"}",
        lab?.templateId?.let { "Template" to "$it v${lab.templateVersion ?: 1}" },
        "Version" to if (source.version > 0) "v${source.version}" else "not saved yet",
        id?.let { "Source id" to it },
        "Made" to originOf(source),
    )
    LabCaptionCard(caption = "About this source") {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 7.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (label == "Site" || label == "Source id") FontFamily.Monospace else null,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun sizeText(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
