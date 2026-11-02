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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
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
import kotlin.math.roundToLong

/**
 * KMK (r100, Source Lab): "Lists and options" of Build by tapping: four sections that open and close (Search, Tabs,
 * Filters, Settings), each with a one-line status. Same look as the Labels card. State lives in [BuilderExtrasModel];
 * the text fields keep their own text and re-read the model's when [BuilderExtras.rev] changes (a recorded search,
 * "Use this page", options from the page), so typing is never fed back through the flow.
 *
 * [onAskOptions] asks the page for a parameter's options (picker.js `optionsFor`); the answer comes back through the
 * bridge to [BuilderExtrasModel.onPageOptions].
 *
 * r102: above the card, the menu hint (the owner tapped a menu's button instead of an entry) and the "Learn this
 * list" card for the last list picked for a filter (mockup 3).
 *
 * r103: Filters → **Pick a menu on the site** (replaces "List for a filter" / "Pick a list on the site"): the next tap
 * on the site picks a menu group; a card per group (one per sub-heading) with Make filter / Learn addresses / Fetch
 * the full list; a fetched list becomes a Dropdown or a Text box with suggestions.
 *
 * r104: each filter card has ▲ / ▼ (the order the source and the Browse sheet use); a filter the source can't use yet
 * says so in red ("Not in the source yet — …"); a filter made from another page's links says it opens that page.
 */
@Composable
internal fun BuilderExtrasPane(
    extras: BuilderExtrasModel,
    pageUrl: String?,
    siteUrl: String,
    onAskOptions: (String) -> Unit,
    /** r104 fix: the edited source's own search path (no search recorded here): which page is the search's */
    codeSearchPath: String? = null,
    modifier: Modifier = Modifier,
) {
    val s by extras.state.collectAsState()
    LaunchedEffect(siteUrl) { extras.setSite(siteUrl) }
    var open by rememberSaveable { mutableStateOf("") }
    // r101: the page "Read the whole list" reads (null: the dialog is closed); r103: the menu group's name for it
    var readUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var readName by rememberSaveable { mutableStateOf<String?>(null) }
    fun toggle(section: String) {
        open = if (open == section) "" else section
    }
    // r101: closing the Filters section (or opening another) ends "Pick a list on the site"
    LaunchedEffect(open) {
        if (open != "filters" && extras.state.value.groupMode) extras.setGroupMode(false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxWidth()) {
        if (s.menuHint) MenuHintCard(text = s.menuHintText, onDismiss = extras::dismissMenuHint)
        val learnGroup = s.learnGroup
        if (learnGroup != null) LearnCard(learnGroup, s.learn, extras, siteUrl)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Lists and options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Search, tabs, filters and settings without code. Searching and changing filters need the site to work: " +
                        "use the page with nothing armed in \"Tapping for…\".",
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
                if (open == "filters") {
                    FiltersSection(s, extras, pageUrl, onAskOptions, codeSearchPath) { url, name ->
                        readName = name
                        readUrl = url
                    }
                }
                HorizontalDivider()
                SectionHeader("Settings", settingsStatus(s), open == "settings") { toggle("settings") }
                if (open == "settings") SettingsSection(s, extras)
            }
        }
    }
    val reading = readUrl
    if (reading != null) {
        ReadListDialog(
            url = reading,
            onDismiss = { readUrl = null },
            onDone = { result, form ->
                extras.onListRead(reading, result, form, readName)
                readUrl = null
            },
            name = readName,
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
    // r104: filters the source can't use yet are counted, never hidden
    val notReady = s.filters.count { BuilderExtrasLogic.filterProblem(it) != null }
    val withReady = if (notReady > 0) "$base · $notReady not in the source yet" else base
    val offers = s.offers.size
    return if (offers > 0) "$withReady · $offers offered" else withReady
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
                "With nothing armed, search the site for \"$word\": the address of the results is recorded here. " +
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
        Hint("Open the list on the site (nothing armed to move around), then \"Use this page\". An empty name keeps the app's own.")
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
    codeSearchPath: String?,
    onReadList: (String, String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Hint(
            "Pick a menu on the site: tap a menu's heading (Trending, Types…), a dropdown's button (Year: All ▾) or one " +
                "entry of a list; each group of it gets a card below and becomes its own filter. Side menus that fold " +
                "count too (open the menu first with nothing armed). Or change a sort or a filter on the site with " +
                "nothing armed: when the address changes, the Lab offers the changed part.",
        )
        if (s.groupMode) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                    Text(
                        "Tap a menu's heading, a dropdown's button or one entry of a list on the site",
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
                    Text("Pick a menu on the site")
                }
                TextButton(onClick = { pageUrl?.let { onReadList(it, null) } }, enabled = pageUrl != null) {
                    Text("Fetch the full list of this page")
                }
            }
        }
        // r103: one card per menu group of the last menu picked
        s.menuGroups.forEach { group -> MenuGroupCard(group, extras, onReadList) }
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
                        TextButton(onClick = { onReadList(offer.url, null) }) { Text("Fetch the full list") }
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
        // r105: the Browse sheet starts with the built-in "List" (Popular, Latest, the tabs)
        if (s.filters.isNotEmpty()) {
            Hint(
                "The Browse sheet starts with \u201cList\u201d (Popular, Latest — picked at first — and the tabs). A page " +
                    "filter picks another list (only the first one changed applies); extras add their code to whichever list is shown.",
            )
        }
        val groupNames = s.filters.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct()
        s.filters.forEachIndexed { index, filter ->
            // r105: a group's heading above its first filter
            val group = filter.group.trim()
            if (group.isNotEmpty() && s.filters.getOrNull(index - 1)?.group?.trim() != group) {
                Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            FilterEditor(s.rev, index, s.filters.size, filter, s.searchPath ?: codeSearchPath, extras, onAskOptions, groupNames)
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
private fun FilterEditor(
    rev: Int,
    index: Int,
    count: Int,
    filter: ExtrasFilter,
    searchPath: String?,
    extras: BuilderExtrasModel,
    onAskOptions: (String) -> Unit,
    groupNames: List<String>,
) {
    val isPath = filter.mode == "path"
    val param = filter.param.ifBlank { if (filter.askParam) "" else filter.key }
    // r104: not usable yet (shown in red, left out of the source); opens its links' own page (not the search page)
    val problem = BuilderExtrasLogic.filterProblem(filter)
    // r105: page (picks the list) or extra (added to any list); a filter made before r105 keeps its old rule
    val role = BuilderExtrasLogic.roleOf(filter, searchPath)
    val linkPage = problem == null && filter.mode != "path" && "{value}" in filter.linkPath &&
        if (filter.role.isEmpty()) BuilderExtrasLogic.usesLinkPage(filter, searchPath) else role == "page"
    val canPage = isPath || "{value}" in filter.linkPath
    var askGroup by remember(index) { mutableStateOf(false) }
    if (askGroup) {
        GroupDialog(
            current = filter.group,
            names = groupNames,
            onDone = { name ->
                askGroup = false
                if (name != null) extras.setFilterGroup(index, name)
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Filter ${index + 1}" + (if (filter.group.isNotBlank()) " · in ${filter.group.trim()}" else ""),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            // r104: the order the source's filter sheet shows
            IconButton(onClick = { extras.moveFilter(index, -1) }, enabled = index > 0, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { extras.moveFilter(index, 1) }, enabled = index < count - 1, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { extras.removeFilter(index) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }
        }
        ExtrasField(rev, "f$index.name", filter.name, { extras.setFilterName(index, it) }, "Name")
        // r105: the role, the group and (for an extra) whether it also goes into a search for words
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = role == "page",
                enabled = canPage,
                onClick = { extras.setFilterRole(index, "page") },
                label = { Text("Picks the list") },
            )
            FilterChip(
                selected = role == "extra",
                enabled = !isPath,
                onClick = { extras.setFilterRole(index, "extra") },
                label = { Text("Added to any list") },
            )
            TextButton(onClick = { askGroup = true }) { Text(if (filter.group.isBlank()) "Move to group…" else "Other group…") }
            if (filter.group.isNotBlank()) {
                TextButton(onClick = { extras.setFilterGroup(index, "") }) { Text("Out of group") }
            }
        }
        if (role == "extra" && filter.role.isNotEmpty()) {
            SwitchRow(
                title = "Also while searching for words",
                summary = if (BuilderExtrasLogic.madeOnSearchPage(filter, searchPath)) {
                    "Made on the search page: it is sent there"
                } else {
                    "Only when the site's search page takes ?${param.ifBlank { "…" }}= too"
                },
                checked = BuilderExtrasLogic.worksOnSearch(filter, searchPath),
                onChange = { extras.setFilterOnSearch(index, it) },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter.type == "select", onClick = { extras.setFilterType(index, "select") }, label = { Text("Dropdown") })
            FilterChip(selected = filter.type == "sort", onClick = { extras.setFilterType(index, "sort") }, label = { Text("Sort") })
            FilterChip(selected = filter.type == "checkbox", onClick = { extras.setFilterType(index, "checkbox") }, label = { Text("Checkbox") })
            FilterChip(selected = filter.type == "text", onClick = { extras.setFilterType(index, "text") }, label = { Text("Text") })
            // r103: a text box whose suggestions are the options (generic-list v6)
            FilterChip(
                selected = filter.type == "autocomplete",
                onClick = { extras.setFilterType(index, "autocomplete") },
                label = { Text("Text box with suggestions") },
            )
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
            "select", "sort", "autocomplete" -> {
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
                        "${filter.options.size} options · " + when (filter.type) {
                            "autocomplete" -> "suggested while typing; an empty box is no filter"
                            // r104 fix: filtersJson puts an empty "Any" first for a filter that opens its own page
                            else -> if ((isPath || linkPage) && filter.options.none { it.value.isEmpty() }) {
                                "the source adds an empty \u201cAny\u201d first: its page opens only when one is picked"
                            } else if (filter.type == "sort") {
                                "the first is picked at first"
                            } else {
                                "the first is used when nothing is picked"
                            }
                        },
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
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // r104 fix: made from links before r104 (the owner's Trending): its own page wasn't saved
        if (problem == null && filter.noLinkAddress && !linkPage) {
            Text(BuilderExtrasLogic.OLD_LINKS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            when {
                linkPage && filter.role.isNotEmpty() -> "Picks the list ${filter.linkPath} (left out while searching for words)"
                filter.role.isNotEmpty() && role == "extra" && filter.type == "autocomplete" ->
                    "The names typed are added to whichever list is shown as ?$param=a,b"
                filter.role.isNotEmpty() && role == "extra" -> "Picks add ?$param=… to whichever list is shown (Latest, Popular, a tab or a page filter's list)"
                filter.role.isNotEmpty() && isPath -> "Picks the list ${filter.path.ifBlank { "…" }} (left out while searching for words)"
                linkPage -> "Picks open ${filter.linkPath} (its own page, not the search page; left out while searching for words)"
                isPath && filter.type == "autocomplete" ->
                    "The first name typed changes the address to ${filter.path.ifBlank { "…" }} (without a search word)"
                isPath -> "Picks change the address to ${filter.path.ifBlank { "…" }} (without a search word)"
                param.isBlank() -> "Picks add …=value to the search address: type the parameter above"
                filter.type == "text" -> "What is typed is added to the search address as ?$param=…"
                filter.type == "autocomplete" -> "The names typed are added to the search address as ?$param=a,b"
                else -> "Picks add ?$param=… to the search address"
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** r105: "Move to group…": an existing group's name or a new one; [onDone] null = cancelled */
@Composable
private fun GroupDialog(current: String, names: List<String>, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Move to group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Filters of one group sit under its heading in the Browse sheet (Collections: Genres, Actresses, Makers…).",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (names.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        names.forEach { n -> FilterChip(selected = text.trim() == n, onClick = { text = n }, label = { Text(n) }) }
                    }
                }
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Group name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("Move") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
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

// ---------- r102: menu hint and Learn this list ----------

@Composable
private fun MenuHintCard(text: String?, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                // r103 fix: picker.js's hint (a menu added only when it opens) has its own title and text
                if (text != null) "This menu opens later" else "That's the menu's button",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text ?: "That's the menu's button: use Pick a menu on the site (Lists and options › Filters) and tap it again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}

private fun learnDone(status: String): Boolean = status == "learned" || status == "nochange" || status == "filled" || status == "missed" || status == "missing"

@Composable
private fun LearnCard(group: LearnGroup, run: LearnRun?, extras: BuilderExtrasModel, siteUrl: String) {
    var pause by remember { mutableStateOf(BuilderListReader.defaultPause()) }
    fun seconds(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    fun secondsMs(ms: Long): String = seconds(ms / 500L / 2f)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (group.hasLinks) "Check where each entry goes" else "Learn this list",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${group.name} · ${group.entries.size} entries",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Hint(
                if (group.hasLinks) {
                    "The Lab presses each entry for you in this page and writes down the address it really opens, then comes back."
                } else {
                    "These are buttons, not links, so the Lab can't see where they go. It presses each one for you in this " +
                        "page and writes down the address it leads to, then comes back."
                },
            )
            if (run == null) {
                Text(
                    "Pause between presses: ${seconds(pause.start)}–${seconds(pause.endInclusive)} s",
                    style = MaterialTheme.typography.bodyMedium,
                )
                RangeSlider(
                    value = pause,
                    onValueChange = { pause = it },
                    valueRange = 0f..10f,
                    // half-second stops, as in "Read the whole list"
                    steps = 19,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            extras.startLearn(
                                (pause.start * 1000f).roundToLong(),
                                (pause.endInclusive * 1000f).roundToLong(),
                            )
                        },
                    ) {
                        Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (group.hasLinks) "Check them" else "Learn this list")
                    }
                    TextButton(onClick = extras::dismissLearn) { Text("Not now") }
                }
            } else {
                val shown = run.rows.filter { it.status != "waiting" }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    shown.take(MAX_LEARN_ROWS).forEach { row -> LearnRowLine(row, group.pageUrl, siteUrl) }
                    if (shown.size > MAX_LEARN_ROWS) Hint("… and ${shown.size - MAX_LEARN_ROWS} more")
                }
                val done = run.rows.count { learnDone(it.status) }
                val total = run.rows.size.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text(
                    "$done of ${run.rows.size} · pause ${secondsMs(run.pauseMinMs)}–${secondsMs(run.pauseMaxMs)} s between presses",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val note = run.note ?: run.pattern?.let { "Pattern found: $it — the rest are filled in without pressing." }
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (run.pattern != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (run.running) OutlinedButton(onClick = extras::stopLearn) { Text("Stop") }
                    Button(
                        onClick = extras::makeLearnedFilter,
                        enabled = run.rows.count { it.url != null || it.status == "nochange" } >= 2,
                    ) {
                        Text("Make \"${run.name}\" filter")
                    }
                    if (!run.running) TextButton(onClick = extras::dismissLearn) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun LearnRowLine(row: LearnRow, pageUrl: String, siteUrl: String) {
    val url = row.url
    val (mark, where) = when (row.status) {
        "pressing" -> "…" to "pressing…"
        "nochange" -> "–" to "no change (${BuilderExtrasLogic.relative(pageUrl, siteUrl)})"
        "missed" -> "?" to "no address"
        "missing" -> "✕" to "not found on the page" // r102 fix: never made the filter's "Any"
        "filled" -> "✓" to (url?.let { BuilderExtrasLogic.relative(it, siteUrl) + " (filled in)" } ?: "")
        else -> "✓" to (url?.let { BuilderExtrasLogic.relative(it, siteUrl) } ?: "")
    }
    Text(
        "$mark ${row.text} → $where",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = when (row.status) {
            "pressing" -> MaterialTheme.colorScheme.primary
            "nochange", "missed" -> MaterialTheme.colorScheme.onSurfaceVariant
            "missing" -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val MAX_LEARN_ROWS = 12

/**
 * r103: one menu group picked on the site: "<Heading> — N options: a, b, c…" and its actions: Make filter (its
 * entries only), Learn addresses (entries without links: the Learn card presses them), Fetch the full list (the
 * group's own link is a list page: read it all, then Dropdown or Text box with suggestions)
 */
@Composable
private fun MenuGroupCard(group: MenuGroup, extras: BuilderExtrasModel, onReadList: (String, String?) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.name.ifBlank { "Menu" } + if (group.items.isEmpty()) " — a list page" else " — ${group.items.size} options",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sample = group.items.take(4).joinToString(", ") { it.text.ifBlank { it.value.orEmpty() } } +
                        if (group.items.size > 4) "…" else ""
                    val where = if (group.headingPath.size > 1) group.headingPath.joinToString(" › ") + " · " else ""
                    Text(
                        where + sample.ifEmpty { group.href?.let { BuilderExtrasLogic.relative(it, it.toSiteOrigin()) }.orEmpty() },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { extras.dismissMenuGroup(group.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Not this", modifier = Modifier.size(16.dp))
                }
            }
            val made = group.made
            if (made != null) {
                Text(
                    if (made.isEmpty()) "No filter could be made of it (see the note above)" else "✓ Filter made ($made)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (made.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (group.items.size >= 2) {
                    TextButton(onClick = { extras.makeMenuFilter(group.id) }) { Text("Make filter") }
                }
                if (group.items.size >= 2 && group.noLinks) {
                    TextButton(onClick = { extras.learnMenuGroup(group.id) }) { Text("Learn addresses") }
                }
                val href = group.href
                if (href != null) {
                    TextButton(onClick = { onReadList(href, group.name) }) { Text("Fetch the full list") }
                }
            }
        }
    }
}

/** The address's origin ("https://example.com"), for showing a path */
private fun String.toSiteOrigin(): String = substringBefore("://", "").let { scheme ->
    if (scheme.isEmpty()) "" else scheme + "://" + substringAfter("://").substringBefore('/')
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
