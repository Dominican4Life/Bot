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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.jssource.JsIcons
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.lab.ApkImport
import eu.kanade.tachiyomi.jssource.lab.HealthState
import eu.kanade.tachiyomi.jssource.lab.LabSource
import eu.kanade.tachiyomi.jssource.lab.LabStore
import eu.kanade.tachiyomi.jssource.lab.LabTemplate
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.Templates
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r96, r106): the Lab's Home (mock EHome): the totals (sources · passing · passing with warnings · failing),
 * filter chips (All, Failing, Anime, Manga, Made by tapping, Converted), then every saved source as a row: letter
 * tile, name, where it came from, kind · format, version, last test, status badge, lint count and ⋮ (open it on a
 * tab). [query] is the top bar's "Find a source". A wide pane draws the rows as a table with a header; a narrow one
 * (phone, portrait tablet) as two-line rows. The Rules / Re-test cards moved to the top bar, the rail and ⋮.
 * KMK (r115, mock M3Home): no totals tiles or table header any more: counted chips (status chips in their colour),
 * one row per source with a status dot, the reason in one line and a Fix / Test button, and "Needs you" (failing,
 * old template, never tested) beside the list from 840 dp, else above it.
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
    // KMK (r115, mock M3Home): the totals tiles gave way to counted chips and the "Needs you" card (beside the list
    // from 840 dp, else above it); each row says how the source is doing and why, with the button that helps
    val hasNeeds = state.sources.any { needsYou(it) } || templates.isNotEmpty()
    val needsCard: @Composable () -> Unit = {
        NeedsYouCard(sources = state.sources, stale = templates, onOpen = onOpen, onTemplates = onTemplates)
    }
    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= 840.dp
        val rowWide = maxWidth >= 600.dp
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = if (wide) 8.dp else 16.dp, top = 14.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                item(key = "chips") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(chipsScroll),
                    ) {
                        HomeFilter.entries.forEach { f ->
                            val count = if (f == HomeFilter.All) state.sources.size else state.sources.count { f.matches(it) }
                            // the status chips only when they have something
                            if (f.status != null && count == 0 && filter != f) return@forEach
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = {
                                    Text(
                                        "${f.label} $count",
                                        color = if (filter != f && f.status != null) f.status.tint() else Color.Unspecified,
                                    )
                                },
                            )
                        }
                    }
                }
                state.retestProgress?.let { progress ->
                    item(key = "retest") {
                        Text(
                            "Re-testing · $progress",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (!wide && hasNeeds) item(key = "needs") { needsCard() }
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
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                shown.forEachIndexed { index, source ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.padding(horizontal = 14.dp),
                                        )
                                    }
                                    SourceRow(source = source, wide = rowWide, onOpen = onOpen)
                                }
                            }
                        }
                    }
                }
            }
            if (wide) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 6.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                ) {
                    if (hasNeeds) {
                        needsCard()
                    } else if (state.sources.isNotEmpty()) {
                        LabCaptionCard(caption = "Needs you") {
                            Text(
                                "Nothing: every source passed its last test.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** KMK (r115): a source that needs you: failing, or never tested */
internal fun needsYou(s: LabSource): Boolean = s.health.state == HealthState.Failing || s.health.state == HealthState.Unknown

/** KMK (r115): how many sources need you (the Home top bar's "· N need you") */
internal fun needsYouCount(sources: List<LabSource>): Int = sources.count { needsYou(it) }

/**
 * KMK (r115, mock M3Home): "Needs you": failing sources (Open: the Test tab), sources on an older template (Update:
 * Templates), never-tested sources (Test: the Test tab). Eight rows at most, then "and N more".
 */
@Composable
private fun NeedsYouCard(
    sources: List<LabSource>,
    stale: List<Pair<LabSource, LabTemplate>>,
    onOpen: (String, LabTab) -> Unit,
    onTemplates: () -> Unit,
) {
    val failing = sources.filter { it.health.state == HealthState.Failing }
    val untested = sources.filter { it.health.state == HealthState.Unknown }
    val rows = buildList {
        failing.forEach { s ->
            add(
                Need(
                    color = StripColor.Red,
                    title = "${s.name.ifBlank { "Untitled" }} is failing",
                    note = s.health.note ?: "the last test failed",
                    action = "Open",
                    onAction = { onOpen(s.key, LabTab.Test) },
                ),
            )
        }
        if (stale.isNotEmpty()) {
            val n = stale.size
            val first = stale.first()
            add(
                Need(
                    color = StripColor.Amber,
                    title = "$n ${if (n == 1) "source" else "sources"} on an old template",
                    note = "${first.second.name} v${first.first.lab?.templateVersion ?: "?"} → v${first.second.version}" +
                        if (stale.map { it.second.id }.distinct().size > 1) " and others" else "",
                    action = "Update",
                    onAction = onTemplates,
                ),
            )
        }
        untested.forEach { s ->
            add(
                Need(
                    color = StripColor.Neutral,
                    title = "${s.name.ifBlank { "Untitled" }} not tested",
                    note = originOf(s),
                    action = "Test",
                    onAction = { onOpen(s.key, LabTab.Test) },
                ),
            )
        }
    }
    LabCaptionCard(caption = "Needs you") {
        rows.take(8).forEachIndexed { index, need ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            NeedsRow(need.color, need.title, need.note, need.action, need.onAction)
        }
        if (rows.size > 8) {
            Text(
                "and ${rows.size - 8} more",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** KMK (r115): one "Needs you" row */
private class Need(val color: StripColor, val title: String, val note: String, val action: String, val onAction: () -> Unit)

@Composable
private fun NeedsRow(color: StripColor, title: String, note: String, action: String, onAction: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(color.tint()))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** Home's filter chips */
internal enum class HomeFilter(val label: String, val status: StripColor? = null) {
    All("All"),
    Anime("Anime"),
    Manga("Manga"),
    // KMK (r115, mock M3Home): the status chips in their colour (shown when they count something)
    Failing("Failing", StripColor.Red),
    Warnings("Warnings", StripColor.Amber),
    NotTested("Not tested", StripColor.Neutral),
    Tapping("Made by tapping"),
    Converted("Converted"),
    ;

    fun matches(s: LabSource): Boolean = when (this) {
        All -> true
        Failing -> s.health.state == HealthState.Failing
        Warnings -> s.health.state == HealthState.Attention
        NotTested -> s.health.state == HealthState.Unknown
        Anime -> s.anime
        Manga -> !s.anime
        Tapping -> madeByTapping(s)
        Converted -> !s.lab?.fromExtension.isNullOrBlank()
    }
}

/**
 * KMK (r115, mock M3Home): one source: its icon, name and "kind · format · vN", then how it is doing (dot + word in
 * its colour) and why in one line ("10 of 10 · 14 min ago", the failure, "made by tapping · never tested"), the
 * button that helps (Failing: Fix → Test tab; Not tested: Test → Test tab) and ⋮. [wide]: the status sits in its own
 * 230 dp column; else under the name.
 */
@Composable
private fun SourceRow(source: LabSource, wide: Boolean, onOpen: (String, LabTab) -> Unit) {
    val lint = source.lastLint?.findings?.count { !it.ignored } ?: 0
    val run = source.lastRun
    val kind = "${if (source.anime) "Anime" else if (source.hasVideos) "Manga + videos" else "Manga"} · ${if (source.json) "JSON" else "JS"}" +
        if (source.version > 0) " · v${source.version}" else ""
    val (word, color) = when (source.health.state) {
        HealthState.Good -> "Healthy" to StripColor.Green
        HealthState.Attention -> "Warnings" to StripColor.Amber
        HealthState.Failing -> "Failing" to StripColor.Red
        HealthState.Unknown -> "Not tested" to StripColor.Neutral
    }
    val why = when (source.health.state) {
        HealthState.Unknown -> originOf(source) + " · never tested"
        HealthState.Failing, HealthState.Attention -> source.health.note
            ?: run?.let { "${it.passed} of ${it.counted} · ${SourceLabScreenModel.agoText(it.startedAt)}" }
            ?: "see the last test"
        HealthState.Good -> run?.let { "${it.passed} of ${it.counted} · ${SourceLabScreenModel.agoText(it.startedAt)}" }
            ?: "passing"
    } + if (lint > 0) " · lint $lint" else ""
    val action = when (source.health.state) {
        HealthState.Failing -> "Fix"
        HealthState.Unknown -> "Test"
        else -> null
    }
    val status: @Composable (Modifier) -> Unit = { m ->
        Column(modifier = m) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color.tint()))
                Spacer(Modifier.width(7.dp))
                Text(word, style = MaterialTheme.typography.labelLarge, color = color.tint(), maxLines = 1)
            }
            Text(
                why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(source.key, LabTab.Code) }
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        LetterTile(source.name, source.key)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                source.name.ifBlank { "Untitled" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                kind,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!wide) status(Modifier.padding(top = 4.dp))
        }
        if (wide) status(Modifier.width(230.dp))
        if (action != null) {
            OutlinedButton(
                onClick = { onOpen(source.key, LabTab.Test) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(action) }
        }
        RowMenu(source, onOpen)
    }
}

/** ⋮ on a row: opens the source on one of its tabs */
@Composable
private fun RowMenu(source: LabSource, onOpen: (String, LabTab) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // KMK (r109): Delete, asked first (the source, its versions, tests and settings go; library entries stay)
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.current
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${source.name.ifBlank { "this source" }}?") },
            text = {
                Text(
                    "Its code, every saved version, test runs, settings and stored data are deleted. Titles in your " +
                        "library stay, but won't open until a source with the same id is added again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { Injekt.get<JsSourceManager>().delete(source.key) }
                                    .onSuccess { AppLog.add('I', "lab", "Deleted the source ${source.name} (${source.key})") }
                                    .onFailure { AppLog.add('W', "lab", "Couldn't delete ${source.name}: ${ApkImport.describe(it)}") }
                                LabStore.invalidate(source.key)
                            }
                        }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
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
            // KMK (r109): Browse (switched on first if it's off, and opened once Browse has it) and WebView
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Open in Browse") },
                leadingIcon = { Icon(Icons.Outlined.Explore, contentDescription = null) },
                onClick = {
                    open = false
                    scope.launch {
                        val manager = Injekt.get<JsSourceManager>()
                        val id = manager.idOf(source.key)
                        if (manager.get(source.key)?.enabled == false) {
                            withContext(Dispatchers.IO) { manager.setEnabled(source.key, true) }
                            AppLog.add('I', "lab", "Open in Browse: ${source.name} was switched off; switched it on")
                        }
                        var tries = 0
                        while (tries < 30 && !inBrowse(source.anime, id)) {
                            delay(100)
                            tries++
                        }
                        navigator?.push(
                            if (source.anime) AnimeSourceScreen(id) else BrowseSourceScreen(id, GetRemoteManga.QUERY_POPULAR),
                        )
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("WebView") },
                leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null) },
                onClick = {
                    open = false
                    val manager = Injekt.get<JsSourceManager>()
                    val url = manager.get(source.key)?.baseUrl.orEmpty().ifBlank { source.lab?.siteUrl.orEmpty() }
                    if (url.isNotBlank()) {
                        navigator?.push(WebViewScreen(url, source.name, manager.idOf(source.key)))
                    } else {
                        AppLog.add('W', "lab", "WebView: ${source.name} has no site address")
                    }
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    confirmDelete = true
                },
            )
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
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        return
    }
    val color = tileColor(name)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
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

/** KMK (r109): whether Browse has the source with [id] (its source manager knows it) */
private fun inBrowse(anime: Boolean, id: Long): Boolean = runCatching {
    if (anime) Injekt.get<AnimeSourceManager>().get(id) != null else Injekt.get<SourceManager>().get(id) != null
}.getOrDefault(true)

