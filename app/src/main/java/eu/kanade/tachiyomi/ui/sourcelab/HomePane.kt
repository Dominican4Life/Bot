package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.jssource.JsIcons
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.lab.ApkImport
import eu.kanade.tachiyomi.jssource.lab.HealthState
import eu.kanade.tachiyomi.jssource.lab.LabSource
import eu.kanade.tachiyomi.jssource.lab.LabTemplate
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.Templates
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r96, r106): the Lab's Home (mock EHome): the totals (sources · passing · passing with warnings · failing),
 * filter chips (All, Failing, Anime, Manga, Made by tapping, Converted), then every saved source as a row: letter
 * tile, name, where it came from, kind · format, version, last test, status badge, lint count and ⋮ (open it on a
 * tab). [query] is the top bar's "Find a source". A wide pane draws the rows as a table with a header; a narrow one
 * (phone, portrait tablet) as two-line rows. The Rules / Re-test cards moved to the top bar, the rail and ⋮.
 */
@Composable
internal fun HomePane(
    state: SourceLabScreenModel.State,
    query: String,
    onOpen: (String, LabTab) -> Unit,
    onTemplates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Template files are read from storage: off the main thread, like every other sidecar read
    val templates by produceState(initialValue = emptyList<Pair<LabSource, LabTemplate>>(), state.sources) {
        value = withContext(Dispatchers.IO) {
            // Logged, not only swallowed: in r96 this was the first use of `Templates`, and its init failure
            // (an ICU-invalid regex) vanished here, leaving every later use a bare NoClassDefFoundError
            runCatching { Templates.staleSources(state.sources) }
                .onFailure { AppLog.add('W', "lab", "Templates not read on Home: ${ApkImport.describe(it)}") }
                .getOrDefault(emptyList())
        }
    }
    var filter by rememberSaveable { mutableStateOf(HomeFilter.All) }
    val shown = remember(state.sources, filter, query) {
        val words = query.trim()
        state.sources.filter { s ->
            filter.matches(s) && (
                words.isEmpty() || s.name.contains(words, ignoreCase = true) ||
                    s.lab?.siteUrl?.contains(words, ignoreCase = true) == true
                )
        }
    }
    val chipsScroll = rememberScrollState()
    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= 840.dp
        val grid = maxWidth < 600.dp
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "totals") { Totals(state.sources, retest = state.retestProgress, grid = grid) }
            item(key = "chips") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(chipsScroll),
                ) {
                    HomeFilter.entries.forEach { f ->
                        val count = if (f == HomeFilter.All) state.sources.size else state.sources.count { f.matches(it) }
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(if (f == HomeFilter.All) f.label else "${f.label} $count") },
                        )
                    }
                }
            }
            if (templates.isNotEmpty()) {
                item(key = "stale") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                        ) {
                            Text(
                                "${templates.size} ${if (templates.size == 1) "source was" else "sources were"} written with an older template",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onTemplates) { Text("Templates") }
                        }
                    }
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
            } else if (shown.isEmpty()) {
                item(key = "none") {
                    Text(
                        "No source matches.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (shown.isNotEmpty()) {
                item(key = "table") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            if (wide) TableHeader()
                            shown.forEachIndexed { index, source ->
                                if (index > 0 || wide) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                SourceRow(source = source, wide = wide, onOpen = onOpen)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Home's filter chips */
internal enum class HomeFilter(val label: String) {
    All("All"),
    Failing("Failing"),
    Anime("Anime"),
    Manga("Manga"),
    Tapping("Made by tapping"),
    Converted("Converted"),
    ;

    fun matches(s: LabSource): Boolean = when (this) {
        All -> true
        Failing -> s.health.state == HealthState.Failing
        Anime -> s.anime
        Manga -> !s.anime
        Tapping -> madeByTapping(s)
        Converted -> !s.lab?.fromExtension.isNullOrBlank()
    }
}

@Composable
private fun Totals(sources: List<LabSource>, retest: String?, grid: Boolean) {
    val good = sources.count { it.health.state == HealthState.Good }
    val attention = sources.count { it.health.state == HealthState.Attention }
    val failing = sources.count { it.health.state == HealthState.Failing }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // KMK (r106): a phone gets the four totals as a 2×2 grid
        if (grid) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TotalTile(sources.size.toString(), "sources", null, Modifier.weight(1f))
                TotalTile(good.toString(), "passing", StripColor.Green, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TotalTile(attention.toString(), "passing with warnings", StripColor.Amber, Modifier.weight(1f))
                TotalTile(failing.toString(), "failing", StripColor.Red, Modifier.weight(1f))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TotalTile(sources.size.toString(), "sources", null, Modifier.weight(1f))
                TotalTile(good.toString(), "passing", StripColor.Green, Modifier.weight(1f))
                TotalTile(attention.toString(), "passing with warnings", StripColor.Amber, Modifier.weight(1f))
                TotalTile(failing.toString(), "failing", StripColor.Red, Modifier.weight(1f))
            }
        }
        if (retest != null) {
            Text(
                "Re-testing · $retest",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TotalTile(number: String, label: String, color: StripColor?, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                number,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = color?.tint() ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// The table's fixed columns (mock: Kind 110, Version 90, Last test 150, Status 120, Lint 70, ⋮ 44)
private val COL_KIND = 110.dp
private val COL_VERSION = 80.dp
private val COL_TEST = 130.dp
private val COL_STATUS = 130.dp
private val COL_LINT = 56.dp
private val COL_MENU = 44.dp

@Composable
private fun TableHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 16.dp),
    ) {
        HeaderCell("Source", Modifier.weight(1f))
        HeaderCell("Kind", Modifier.width(COL_KIND))
        HeaderCell("Version", Modifier.width(COL_VERSION))
        HeaderCell("Last test", Modifier.width(COL_TEST))
        HeaderCell("Status", Modifier.width(COL_STATUS))
        HeaderCell("Lint", Modifier.width(COL_LINT))
        Spacer(Modifier.width(COL_MENU))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun SourceRow(source: LabSource, wide: Boolean, onOpen: (String, LabTab) -> Unit) {
    val (badgeText, badgeColor) = healthBadge(source.health.state)
    val lint = source.lastLint?.findings?.count { !it.ignored }
    val lastTest = source.lastRun?.let { SourceLabScreenModel.agoText(it.startedAt) } ?: "never"
    val kind = "${if (source.anime) "Anime" else if (source.hasVideos) "Manga + videos" else "Manga"} · ${if (source.json) "JSON" else "JS"}"
    val version = if (source.version > 0) "v${source.version}" else "–"
    if (wide) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(source.key, LabTab.Code) }
                .height(58.dp)
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                LetterTile(source.name, source.key)
                NameAndOrigin(source, Modifier.padding(start = 12.dp))
            }
            Cell(kind, Modifier.width(COL_KIND))
            Cell(version, Modifier.width(COL_VERSION), mono = true)
            Cell(lastTest, Modifier.width(COL_TEST))
            Box(modifier = Modifier.width(COL_STATUS)) { StatusBadge(badgeText, badgeColor) }
            Cell(lint?.toString() ?: "–", Modifier.width(COL_LINT), mono = true)
            Box(modifier = Modifier.width(COL_MENU)) { RowMenu(source, onOpen) }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(source.key, LabTab.Code) }
                .padding(start = 12.dp, end = 0.dp, top = 10.dp, bottom = 10.dp),
        ) {
            LetterTile(source.name, source.key)
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
                    StatusBadge(badgeText, badgeColor)
                }
                Text(
                    originOf(source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$kind · $version · tested $lastTest" + (lint?.takeIf { it > 0 }?.let { " · lint $it" }.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                source.health.note?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (source.health.state) {
                            HealthState.Failing -> StripColor.Red.tint()
                            HealthState.Attention -> StripColor.Amber.tint()
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RowMenu(source, onOpen)
        }
    }
}

@Composable
private fun NameAndOrigin(source: LabSource, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            source.name.ifBlank { "Untitled" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            source.health.note?.takeIf { source.health.state == HealthState.Failing } ?: originOf(source),
            style = MaterialTheme.typography.bodySmall,
            color = if (source.health.state == HealthState.Failing && source.health.note != null) {
                StripColor.Red.tint()
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Cell(text: String, modifier: Modifier, mono: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (mono) FontFamily.Monospace else null,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** ⋮ on a row: opens the source on one of its tabs */
@Composable
private fun RowMenu(source: LabSource, onOpen: (String, LabTab) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${source.name}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                LabTab.Code to "Open the code",
                LabTab.Test to "Test",
                LabTab.Lint to "Lint",
                LabTab.History to "History",
                LabTab.Report to "Issue report",
            ).forEach { (tab, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(tab.icon(), contentDescription = null) },
                    onClick = {
                        open = false
                        onOpen(source.key, tab)
                    },
                )
            }
        }
    }
}

/**
 * KMK (r108): the source's own icon (the one Browse shows, [JsIcons]) when it has one, else its first letter. The icon
 * may still be downloading when the list first shows, so it's looked up again for a few seconds.
 */
@Composable
private fun LetterTile(name: String, key: String) {
    val icon by produceState<ImageBitmap?>(initialValue = null, key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val id = Injekt.get<JsSourceManager>().idOf(key)
                var bitmap = JsIcons.bitmap(id)
                var tries = 0
                while (bitmap == null && tries < 10) {
                    delay(1_000)
                    bitmap = JsIcons.bitmap(id)
                    tries++
                }
                bitmap?.asImageBitmap()
            }.getOrNull()
        }
    }
    val loaded = icon
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        return
    }
    val color = tileColor(name)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.22f)),
    ) {
        Text(
            name.trim().take(1).uppercase().ifBlank { "?" },
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
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

/** The Lab's card: a title over [content] (Test / Lint / Report / Explain share it) */
@Composable
internal fun LabCard(title: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (onClick != null) m.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick) else m },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            content()
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
