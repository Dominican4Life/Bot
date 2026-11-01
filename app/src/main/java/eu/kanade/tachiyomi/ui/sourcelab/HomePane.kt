package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.jssource.lab.ApkImport
import eu.kanade.tachiyomi.jssource.lab.HealthState
import eu.kanade.tachiyomi.jssource.lab.LabSource
import eu.kanade.tachiyomi.jssource.lab.LabTemplate
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.Templates
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KMK (r96): the Lab's Home (D§2.1): the Rules file and Re-test all cards, then every saved source as a row with
 * its kind pill, version, last test, requests per day, errors, a note line and the health dot.
 */
@Composable
internal fun HomePane(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    onOpen: (String) -> Unit,
    onRules: () -> Unit,
    onTemplates: () -> Unit,
    tablet: Boolean,
    modifier: Modifier = Modifier,
) {
    // Template files are read from storage: off the main thread, like every other sidecar read
    val templates by produceState(initialValue = emptyList<Pair<LabSource, LabTemplate>>() to 0, state.sources) {
        value = withContext(Dispatchers.IO) {
            // Logged, not only swallowed: in r96 this was the first use of `Templates`, and its init failure
            // (an ICU-invalid regex) vanished here, leaving every later use a bare NoClassDefFoundError
            val stale = runCatching { Templates.staleSources(state.sources) }
                .onFailure { AppLog.add('W', "lab", "Templates not read on Home: ${ApkImport.describe(it)}") }
                .getOrDefault(emptyList())
            stale to runCatching { Templates.list().size }.getOrDefault(0)
        }
    }
    val (stale, templateCount) = templates
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        item(key = "cards") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RulesCard(state = state, onOpen = onRules, modifier = Modifier.weight(1f))
                RetestCard(state = state, model = model, modifier = Modifier.weight(1f))
            }
        }
        if (tablet || stale.isNotEmpty()) {
            item(key = "templates") {
                LabCard(title = "Templates", onClick = onTemplates) {
                    Text(
                        text = buildString {
                            append("$templateCount template files")
                            if (stale.isNotEmpty()) {
                                append(" · ${stale.size} ${if (stale.size == 1) "source" else "sources"} written with an older template")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "sources-head") {
            val good = state.sources.count { it.health.state == HealthState.Good }
            val attention = state.sources.count { it.health.state == HealthState.Attention }
            val failing = state.sources.count { it.health.state == HealthState.Failing }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "  ${state.sources.size} · $good good · $attention attention · $failing failing · tap for the tabs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.sources.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No sources yet. New source pastes a site's address and writes one; Import brings a .js or .json file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.sources, key = { it.key }) { source ->
            SourceRow(source = source, onClick = { onOpen(source.key) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun SourceRow(source: LabSource, onClick: () -> Unit) {
    val note = source.health.note
    val noteColor = when (source.health.state) {
        HealthState.Failing -> StripColor.Red.tint()
        HealthState.Attention -> StripColor.Amber.tint()
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tileColor(source.name)),
        ) {
            Text(
                source.name.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF14171F),
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    source.name.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                KindPill(anime = source.anime, json = source.json, hasVideos = source.hasVideos)
            }
            Text(
                text = buildString {
                    append(if (source.version > 0) "v${source.version}" else "no history")
                    append(" · ")
                    append(source.lastRun?.let { "tested ${SourceLabScreenModel.agoText(it.startedAt)}" } ?: "not tested")
                    source.lastRun?.let { append(" · ${it.passed}/${it.counted}") }
                    append(" · ${source.health.requestsPerDay} req/day · ${source.health.errorsToday} ${if (source.health.errorsToday == 1) "error" else "errors"}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = noteColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(modifier = Modifier.padding(start = 8.dp, top = 6.dp)) { HealthDot(state = source.health.state) }
    }
}

/** A steady pastel per name, like the stills' tiles */
private fun tileColor(name: String): Color {
    val palette = listOf(
        Color(0xFF8AB4F8), Color(0xFFFFB74D), Color(0xFFF48FB1), Color(0xFF80CBC4),
        Color(0xFFCE93D8), Color(0xFFFFD54F), Color(0xFFA5D6A7), Color(0xFF90CAF9),
    )
    val index = name.lowercase().sumOf { it.code }.mod(palette.size)
    return palette[index]
}

@Composable
internal fun LabCard(title: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (onClick != null) m.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick) else m },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun RulesCard(state: SourceLabScreenModel.State, onOpen: () -> Unit, modifier: Modifier) {
    val rules = state.rules
    val machine = rules?.rules?.count { eu.kanade.tachiyomi.jssource.lab.LintCheck.of(it.check) != null } ?: 0
    val hit = state.sources.count { s -> s.lastLint?.findings?.any { !it.ignored } == true }
    val findings = state.sources.sumOf { s -> s.lastLint?.findings?.count { !it.ignored } ?: 0 }
    Box(modifier = modifier) {
        LabCard(title = "Rules file", onClick = onOpen) {
            Text(
                text = if (rules == null) {
                    state.rulesError ?: "no rules file"
                } else {
                    "v${rules.version} · ${rules.rules.size} rules · $machine checked by the Lab · $findings findings in $hit ${if (hit == 1) "source" else "sources"}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpen) { Text("Open") }
        }
    }
}

@Composable
private fun RetestCard(state: SourceLabScreenModel.State, model: SourceLabScreenModel, modifier: Modifier) {
    var nightly by remember { mutableStateOf(model.retestNightly()) }
    var wifiOnly by remember { mutableStateOf(model.retestWifiOnly()) }
    Box(modifier = modifier) {
        LabCard(title = "Re-test all") {
            Text(
                text = state.retestProgress?.let { "running · $it" } ?: buildString {
                    append(state.retestLast)
                    append(" · ")
                    append(if (nightly) "nightly 03:00" else "no schedule")
                    if (nightly && wifiOnly) append(", Wi-Fi only")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = model::retestAll, enabled = state.retestProgress == null) { Text("Run now") }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(
                    selected = nightly,
                    onClick = {
                        nightly = !nightly
                        model.setRetestSchedule(nightly)
                    },
                    label = { Text(if (nightly) "nightly 03:00" else "off") },
                )
                Spacer(Modifier.width(8.dp))
                Text("Wi-Fi only", style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = {
                        wifiOnly = it
                        model.setRetestWifiOnly(it)
                    },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** A step status as a strip colour (shared by the panes) */
internal fun StepStatus.strip(): StripColor = when (this) {
    StepStatus.Green -> StripColor.Green
    StepStatus.Amber -> StripColor.Amber
    StepStatus.Red -> StripColor.Red
    StepStatus.Skipped -> StripColor.Neutral
    StepStatus.Running -> StripColor.Blue
}
