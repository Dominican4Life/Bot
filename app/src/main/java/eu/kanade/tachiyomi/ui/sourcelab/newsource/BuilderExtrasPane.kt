package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * KMK (r100, Source Lab): "Lists and options" of Build by tapping: four sections that open and close (Search, Tabs,
 * Filters, Settings), each with a one-line status. Same look as the Labels card. State lives in [BuilderExtrasModel];
 * the text fields keep their own text and re-read the model's when [BuilderExtras.rev] changes (a recorded search,
 * "Use this page", options from the page), so typing is never fed back through the flow.
 *
 * [onAskOptions] asks the page for a parameter's options (picker.js `optionsFor`); the answer comes back through the
 * bridge to [BuilderExtrasModel.onPageOptions].
 */
@Composable
internal fun BuilderExtrasPane(
    extras: BuilderExtrasModel,
    pageUrl: String?,
    siteUrl: String,
    onAskOptions: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by extras.state.collectAsState()
    LaunchedEffect(siteUrl) { extras.setSite(siteUrl) }
    var open by rememberSaveable { mutableStateOf("") }
    // r101: the page "Read the whole list" reads (null: the dialog is closed)
    var readUrl by rememberSaveable { mutableStateOf<String?>(null) }
    fun toggle(section: String) {
        open = if (open == section) "" else section
    }
    // r101: closing the Filters section (or opening another) ends "Pick a list on the site"
    LaunchedEffect(open) {
        if (open != "filters" && extras.state.value.groupMode) extras.setGroupMode(false)
    }

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Lists and options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Search, tabs, filters and settings without code. Searching and changing filters need the site to work: switch to Browse first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            val message = s.message
            if (message != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        )
                        IconButton(onClick = extras::clearMessage, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            SectionHeader("Search", searchStatus(s), open == "search") { toggle("search") }
            if (open == "search") SearchSection(s, extras)
            HorizontalDivider()
            SectionHeader("Tabs", tabsStatus(s), open == "tabs") { toggle("tabs") }
            if (open == "tabs") TabsSection(s, extras, pageUrl)
            HorizontalDivider()
            SectionHeader("Filters", filtersStatus(s), open == "filters") { toggle("filters") }
            if (open == "filters") FiltersSection(s, extras, pageUrl, onAskOptions) { readUrl = it }
            HorizontalDivider()
            SectionHeader("Settings", settingsStatus(s), open == "settings") { toggle("settings") }
            if (open == "settings") SettingsSection(s, extras)
        }
    }
    val reading = readUrl
    if (reading != null) {
        ReadListDialog(
            url = reading,
            onDismiss = { readUrl = null },
            onDone = { result ->
                extras.onListRead(reading, result)
                readUrl = null
            },
        )
    }
}

// ---------- status lines ----------

private fun searchStatus(s: BuilderExtras): String =
    s.searchPath?.let { if (s.searchFromForm) "Site's search box ✓ $it" else "Recorded ✓ $it" }
        ?: "Not recorded: the Lab guesses /search?q="

private fun tabsStatus(s: BuilderExtras): String {
    val names = buildList {
        add(s.popularName.ifBlank { "Popular" } + if (s.popularPath != null) " ✓" else "")
        add(s.latestName.ifBlank { "Latest" } + if (s.latestPath != null) " ✓" else "")
        s.tabs.forEach { add(it.name.ifBlank { it.path }) }
    }
    val extra = if (s.tabs.isEmpty()) "no extra tabs" else "${s.tabs.size} extra tab" + if (s.tabs.size == 1) "" else "s"
    return "$extra · " + names.joinToString(", ")
}

private fun filtersStatus(s: BuilderExtras): String {
    val n = s.filters.size
    val base = when (n) {
        0 -> "No filters"
        1 -> "1 filter"
        else -> "$n filters"
    }
    val offers = s.offers.size
    return if (offers > 0) "$base · $offers offered" else base
}

private fun settingsStatus(s: BuilderExtras): String {
    val names = buildList {
        if (s.qualitySetting) add("Quality")
        if (s.serverSetting) add("Server")
        if (s.hideWordsSetting) add("Hide words")
        s.custom.forEach { add(it.name.ifBlank { it.key }) }
    }
    return if (names.isEmpty()) "None" else names.joinToString(", ")
}

// ---------- sections ----------

@Composable
private fun SectionHeader(title: String, status: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.width(80.dp))
        Text(
            status,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (expanded) "Close" else "Open",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchSection(s: BuilderExtras, extras: BuilderExtrasModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        ExtrasField(s.rev, "searchWord", s.searchWord, extras::setSearchWord, "The word you'll search for")
        val word = s.searchWord.trim().ifEmpty { "a word" }
        val path = s.searchPath
        if (path == null) {
            Hint(
                "Switch to Browse and search the site for \"$word\": the address of the results is recorded here. " +
                    "If the results show but nothing is recorded, this site searches without changing the address: " +
                    "not supported here yet.",
            )
        } else {
            Text(
                if (s.searchFromForm) {
                    "Found the site's search box ✓ (a search you make in Browse replaces it)"
                } else {
                    "Recorded ✓" + (s.searchFrom?.let { " from $it" } ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ExtrasField(s.rev, "searchPath", path, extras::setSearchPath, "Search address ({q} = the words, {page} = the page)", monospace = true)
            TextButton(onClick = extras::clearSearch) { Text("Forget it and record again") }
        }
    }
}

@Composable
private fun TabsSection(s: BuilderExtras, extras: BuilderExtrasModel, pageUrl: String?) {
    var newName by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Hint("Open the list on the site (Browse to move around), then \"Use this page\". An empty name keeps the app's own.")
        val rule = s.pageRule
        Hint(
            if (rule != null) {
                "Pages: ${rule.shown}, from the Next link you picked ✓"
            } else {
                "Pages: not known yet. Pick the site's Next link as Next (Labels) and lists follow it; until then the Lab tries ?page=2."
            },
        )
        ListRow(
            title = "Popular",
            rev = s.rev,
            id = "popular",
            name = s.popularName,
            path = s.popularPath,
            onName = extras::setPopularName,
            onPath = extras::setPopularPath,
            onUse = { extras.usePageForPopular(pageUrl) },
            onRemove = null,
        )
        ListRow(
            title = "Latest",
            rev = s.rev,
            id = "latest",
            name = s.latestName,
            path = s.latestPath,
            onName = extras::setLatestName,
            onPath = extras::setLatestPath,
            onUse = { extras.usePageForLatest(pageUrl) },
            onRemove = null,
        )
        s.tabs.forEachIndexed { index, tab ->
            ListRow(
                title = "Tab ${index + 1}",
                rev = s.rev,
                id = "tab$index",
                name = tab.name,
                path = tab.path,
                onName = { extras.setTabName(index, it) },
                onPath = { extras.setTabPath(index, it) },
                onUse = { extras.useThisPageForTab(index, pageUrl) },
                onRemove = { extras.removeTab(index) },
            )
        }
        Text("Add a tab", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    extras.addTab(newName, pageUrl)
                    newName = ""
                },
                enabled = pageUrl != null,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("This page")
            }
        }
    }
}

@Composable
private fun ListRow(
    title: String,
    rev: Int,
    id: String,
    name: String,
    path: String?,
    onName: (String) -> Unit,
    onPath: (String) -> Unit,
    onUse: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onUse) { Text("Use this page") }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                }
            }
        }
        ExtrasField(rev, "$id.name", name, onName, "Name", hint = if (onRemove == null) "$title (the app's name)" else null)
        ExtrasField(
            rev,
            "$id.path",
            path.orEmpty(),
            onPath,
            "Path ({page} = the page number)",
            monospace = true,
            hint = if (onRemove == null) "the one the Lab derived" else null,
        )
    }
}

@Composable
private fun FiltersSection(
    s: BuilderExtras,
    extras: BuilderExtrasModel,
    pageUrl: String?,
    onAskOptions: (String) -> Unit,
    onReadList: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Hint(
            "Pick a list on the site (its genres, its sort menu, its types) and its entries become a filter. Or switch " +
                "to Browse and change a sort or a filter on the site: when the address changes, the Lab offers the changed part.",
        )
        if (s.groupMode) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                    Text(
                        "Tap one entry of the list: a genre, a sort option, a type… (menus that fold open count too)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                    TextButton(onClick = { extras.setGroupMode(false) }) { Text("Cancel") }
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { extras.setGroupMode(true) }) {
                    Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pick a list on the site")
                }
                TextButton(onClick = { pageUrl?.let(onReadList) }, enabled = pageUrl != null) {
                    Text("Read the whole list of this page")
                }
            }
        }
        val offer = s.listOffer
        if (offer != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "\"${offer.text}\" opens a list page",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        offer.url,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row {
                        TextButton(onClick = { onReadList(offer.url) }) { Text("Read the whole list") }
                        TextButton(onClick = extras::dismissListOffer) { Text("Not now") }
                    }
                }
            }
        }
        s.offers.forEach { offer ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "The address changed ${offer.param}=${offer.seen.joinToString(", ").ifEmpty { "…" }}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row {
                        TextButton(onClick = {
                            extras.acceptOffer(offer.param)
                            onAskOptions(offer.param)
                        }) { Text("Make this a filter") }
                        TextButton(onClick = { extras.dismissOffer(offer.param) }) { Text("Not a filter") }
                    }
                }
            }
        }
        s.filters.forEachIndexed { index, filter ->
            FilterEditor(s.rev, index, filter, extras, onAskOptions)
            HorizontalDivider()
        }
        TextButton(onClick = extras::addFilter) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add a filter by hand")
        }
    }
}

@Composable
private fun FilterEditor(rev: Int, index: Int, filter: ExtrasFilter, extras: BuilderExtrasModel, onAskOptions: (String) -> Unit) {
    val isPath = filter.mode == "path"
    val param = filter.param.ifBlank { if (filter.askParam) "" else filter.key }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filter ${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { extras.removeFilter(index) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }
        }
        ExtrasField(rev, "f$index.name", filter.name, { extras.setFilterName(index, it) }, "Name")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter.type == "select", onClick = { extras.setFilterType(index, "select") }, label = { Text("Dropdown") })
            FilterChip(selected = filter.type == "sort", onClick = { extras.setFilterType(index, "sort") }, label = { Text("Sort") })
            FilterChip(selected = filter.type == "checkbox", onClick = { extras.setFilterType(index, "checkbox") }, label = { Text("Checkbox") })
            FilterChip(selected = filter.type == "text", onClick = { extras.setFilterType(index, "text") }, label = { Text("Text") })
        }
        if (!isPath && filter.askParam) {
            ExtrasField(
                rev,
                "f$index.param",
                filter.param,
                { extras.setFilterParam(index, it) },
                "Parameter in the address (genre in ?genre=action)",
                monospace = true,
            )
        }
        when (filter.type) {
            "select", "sort" -> {
                ExtrasField(
                    rev,
                    "f$index.options",
                    filter.optionsText,
                    { extras.setFilterOptionsText(index, it) },
                    "Options, one per line: Label = value",
                    singleLine = false,
                    monospace = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${filter.options.size} options · " +
                            if (filter.type == "sort") "the first is picked at first" else "the first is used when nothing is picked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (!isPath) {
                        TextButton(onClick = { onAskOptions(param) }, enabled = param.isNotBlank()) { Text("Read from the page") }
                    }
                }
            }
            "checkbox" -> ExtrasField(
                rev,
                "f$index.on",
                filter.onValue,
                { extras.setFilterOnValue(index, it) },
                "Value when ticked",
                monospace = true,
            )
            else -> Unit
        }
        Text(
            when {
                isPath -> "Picks change the address to ${filter.path.ifBlank { "…" }} (without a search word)"
                param.isBlank() -> "Picks add …=value to the search address: type the parameter above"
                filter.type == "text" -> "What is typed is added to the search address as ?$param=…"
                else -> "Picks add ?$param=… to the search address"
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSection(s: BuilderExtras, extras: BuilderExtrasModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        SwitchRow("Preferred quality", "Best, 1080p, 720p, 480p, 360p: videos of that quality are listed first", s.qualitySetting, extras::setQualitySetting)
        SwitchRow("Preferred server", "Videos from a server whose name contains the text are listed first", s.serverSetting, extras::setServerSetting)
        SwitchRow("Hide titles containing", "Words separated by commas; titles with any of them are left out of every list", s.hideWordsSetting, extras::setHideWordsSetting)
        s.custom.forEachIndexed { index, setting ->
            HorizontalDivider()
            CustomSettingEditor(s.rev, index, setting, extras)
        }
        TextButton(onClick = extras::addCustomSetting) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add a setting")
        }
        if (s.custom.isNotEmpty()) Hint("Your code reads it with host.preference.get(\"key\")")
    }
}

@Composable
private fun CustomSettingEditor(rev: Int, index: Int, setting: ExtrasCustomSetting, extras: BuilderExtrasModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Setting ${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { extras.removeCustomSetting(index) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExtrasField(rev, "c$index.name", setting.name, { extras.setCustomName(index, it) }, "Name", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            ExtrasField(
                rev,
                "c$index.key",
                setting.key,
                { extras.setCustomKey(index, it) },
                "Key",
                modifier = Modifier.weight(1f),
                monospace = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = setting.type == "switch", onClick = { extras.setCustomType(index, "switch") }, label = { Text("Switch") })
            FilterChip(selected = setting.type == "list", onClick = { extras.setCustomType(index, "list") }, label = { Text("List") })
            FilterChip(selected = setting.type == "text", onClick = { extras.setCustomType(index, "text") }, label = { Text("Text") })
        }
        if (setting.type == "list") {
            ExtrasField(
                rev,
                "c$index.options",
                setting.optionsText,
                { extras.setCustomOptionsText(index, it) },
                "Entries, one per line (the first is the default)",
                singleLine = false,
            )
        }
    }
}

// ---------- small parts ----------

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A text field that keeps its own text while typing (each change goes to [onChange] at once) and takes [value]
 * again only when [rev] changes, i.e. when the model itself changed it
 */
@Composable
private fun ExtrasField(
    rev: Int,
    id: String,
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    hint: String? = null,
) {
    var text by remember(rev, id) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label) },
        placeholder = { if (hint != null) Text(hint) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        modifier = modifier.fillMaxWidth(),
    )
}
