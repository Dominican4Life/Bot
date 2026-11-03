package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt
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
 *
 * r106 (mockups Main / Learn / Learned / Links / Directory / TFilters): Filters is one "Pick a menu on the site" button,
 * a card per picked menu with only its fitting buttons (Learn the codes with its pause and progress inside the card,
 * Make filter / Add as tabs, Read all pages, a heading's sub-menus with Read all N; Replace "<name>" / Keep both when a
 * name is taken), then the filters as compact rows in Browse order (tap = the editor). The r102 Learn card above the
 * pane and "Fetch the full list of this page" are gone. A card asked for by the model ([BuilderExtras.scrollTarget])
 * opens Filters and brings itself into view inside the builder's scrolling panel.
 *
 * r107 (mocks CScan / BRead): a directory card shows its scan ([CollectionSection]): each part of an entry with a tick
 * and page 1's example (Follow greyed, never kept), the drop-down row they give, the site's sort options as chips and
 * its other menus, then Live, as you scroll / Read pages now (page range, pause, time it takes), and the progress card
 * with Stop while pages are read. The built-in "List" row is gone (Browse's tabs pick the list).
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
    // r106 fix: the open section lives in the model (a tab switch disposes this pane: it comes back as it was)
    val open = s.openSection
    // r101: the page "Read the whole list" reads (null: the dialog is closed); r103: the menu group's name for it
    var readUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var readName by rememberSaveable { mutableStateOf<String?>(null) }
    fun toggle(section: String) {
        extras.setOpenSection(if (extras.state.value.openSection == section) "" else section)
    }
    // r101: closing the Filters section (or opening another) ends "Pick a list on the site"
    LaunchedEffect(open) {
        if (open != "filters" && extras.state.value.groupMode) extras.setGroupMode(false)
    }

    // r106: a menu card that appears or starts working opens Filters (the card then scrolls itself into view)
    // (once per request: coming back to the pane later doesn't open it again. r106 fix: the request handled is kept in
    // the model, so a tab switch, which disposes this pane, doesn't count an old request as new)
    LaunchedEffect(s.scrollTick) {
        val now = extras.state.value
        val target = now.scrollTarget
        if (now.scrollTick > now.scrollOpened) {
            // KMK (r109) fix: a read's progress box ("read:<id>") is in Filters too
            if (target != null && (target.startsWith("menu:") || target.startsWith("read:") || target == "pending")) {
                extras.setOpenSection("filters")
            }
            extras.scrollOpened(now.scrollTick)
        }
    }
    // r106: learning runs on its card inside Filters: a run started elsewhere opens Filters too
    LaunchedEffect(s.learnGroup?.menuId, s.learnGroup?.name) {
        if (s.learnGroup != null) extras.setOpenSection("filters")
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxWidth()) {
        if (s.menuHint) MenuHintCard(text = s.menuHintText, onDismiss = extras::dismissMenuHint)
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

/**
 * r106 (mockups Main / Learn / Learned / Links / Directory / TFilters): ONE "Pick a menu on the site" button; every
 * picked menu is a card ([MenuCard]) with only the buttons it needs; then the filters in Browse order as compact rows
 * ([FilterRow]: tap to edit, ▲▼). A filter whose name is taken asks Replace / Keep both on its card ([ReplaceBox]).
 */
/**
 * r106 (mockups Main / Learn / Learned / Links / Directory / TFilters): ONE "Pick a menu on the site" button; every
 * picked menu is a card ([MenuCard]) with only the buttons it needs; then the filters in Browse order as compact rows
 * ([FilterRow]: tap to edit, ▲▼). A filter whose name is taken asks Replace / Keep both on its card ([ReplaceBox]).
 */
@Composable
private fun FiltersSection(
    s: BuilderExtras,
    extras: BuilderExtrasModel,
    pageUrl: String?,
    onAskOptions: (String) -> Unit,
    codeSearchPath: String?,
    onReadList: (String, String?) -> Unit,
) {
    // r106: the filter whose editor is open (one at a time), by key + name (indices move with ▲▼)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Hint(
            "Tap a menu, a dropdown or one entry of a list on the site. Each one gets a card here with only the buttons " +
                "it needs. Side menus that fold count too (open the menu first with nothing armed).",
        )
        if (s.groupMode) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
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
            Button(onClick = { extras.setGroupMode(true) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pick a menu on the site")
            }
        }
        // r106: a filter made without a card (a list picked the r101 way) that asks Replace / Keep both
        // r106 fix: every question without its card (one per card; the card closed or replaced by a new pick)
        s.pendings.values.filter { p -> p.menuId == null || s.menuGroups.none { it.id == p.menuId } }.forEach { p ->
            key("pending:${p.menuId ?: BuilderExtrasLogic.NO_CARD}") {
                ReplaceBox(p, extras, p.menuId == null && s.scrollTarget == "pending", s.scrollTick, scrollDoneTick = s.scrollDone)
            }
        }
        // A list picked the r101 / r102 way (no card): its learning shows here
        val looseLearn = s.learnGroup?.takeIf { g -> g.menuId == null || s.menuGroups.none { it.id == g.menuId } }
        if (looseLearn != null) {
            CardBox(active = s.learn?.running == true, target = null, scrollTarget = s.scrollTarget, scrollTick = s.scrollTick) {
                LearnSection(looseLearn, s.learn, extras, s, menuId = null)
            }
        }
        val top = s.menuGroups.filter { g -> g.parent == null || s.menuGroups.none { it.id == g.parent } }
        if (top.isNotEmpty()) SectionLabel("Menus you picked")
        top.forEach { group ->
            key(group.id) { MenuCard(group, s, extras, nested = false) }
        }
        val offer = s.listOffer
        if (offer != null) {
            CardBox(active = false, target = null, scrollTarget = s.scrollTarget, scrollTick = s.scrollTick) {
                Text("“${offer.text}” opens a list page", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                MonoLine(offer.url)
                Row {
                    TextButton(onClick = { onReadList(offer.url, null) }) { Text("Fetch the full list") }
                    TextButton(onClick = extras::dismissListOffer) { Text("Not now") }
                }
            }
        }
        s.offers.forEach { offer ->
            CardBox(active = false, target = null, scrollTarget = s.scrollTarget, scrollTick = s.scrollTick) {
                MonoLine("The address changed ${offer.param}=${offer.seen.joinToString(", ").ifEmpty { "…" }}", lines = 2)
                Row {
                    TextButton(onClick = {
                        extras.acceptOffer(offer.param)
                        onAskOptions(offer.param)
                    }) { Text("Make this a filter") }
                    TextButton(onClick = { extras.dismissOffer(offer.param) }) { Text("Not a filter") }
                }
            }
        }
        // r106: the filters in Browse order, compact (tap one to edit it)
        val searchPath = s.searchPath ?: codeSearchPath
        SectionLabel("Your filters · in Browse order")
        if (s.filters.isEmpty()) {
            Hint("None yet: pick a menu on the site, or add one by hand.")
        } else {
            // r107: no built-in List filter: Popular, Latest and the tabs are Browse's own buttons
            Hint("Popular, Latest and your tabs are Browse's own buttons: they pick the list, so there's no List filter.")
        }
        val groupNames = s.filters.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct()
        s.filters.forEachIndexed { index, filter ->
            val group = filter.group.trim()
            if (group.isNotEmpty() && s.filters.getOrNull(index - 1)?.group?.trim() != group) {
                val members = s.filters.filter { it.group.trim() == group }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("$group · group", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        members.joinToString(" · ") { it.name.ifBlank { it.key } },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val id = "${filter.key}|${filter.name}|$index"
            key(id) {
                FilterRow(
                    index = index,
                    count = s.filters.size,
                    filter = filter,
                    searchPath = searchPath,
                    indent = group.isNotEmpty(),
                    expanded = editing == id,
                    onToggle = { editing = if (editing == id) null else id },
                    extras = extras,
                )
                if (editing == id) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            FilterEditor(s.rev, index, s.filters.size, filter, searchPath, extras, onAskOptions, groupNames)
                        }
                    }
                }
            }
        }
        if (s.filters.isNotEmpty()) {
            Hint("Tap a filter to edit its name, options and role. Use ▲ ▼ to change the order (a group's filters stay together).")
        }
        TextButton(onClick = extras::addFilter) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add a filter by hand")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun MonoLine(text: String, lines: Int = 1, color: Color = Color.Unspecified) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The mockups' green "learned / made" tick */
private val OkGreen = Color(0xFF7FD99A)

/** The mockups' amber warning */
private val WarnAmber = Color(0xFFF2B33D)

/**
 * r106: a card of the Filters section: rounded, a primary outline while it works ([active]); when [target] is the
 * model's scroll target ([BuilderExtras.scrollTarget]) it brings itself into view on every new request
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardBox(
    active: Boolean,
    target: String?,
    scrollTarget: String?,
    scrollTick: Int,
    nested: Boolean = false,
    /** r106 fix: the last request already brought into view ([BuilderExtras.scrollDone]) and how to say one was */
    scrollDoneTick: Int = 0,
    onScrolled: (Int) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    val wanted = target != null && scrollTarget == target
    LaunchedEffect(scrollTick, wanted) {
        // r106 fix: a request already brought into view (before a tab switch) isn't done again: no jump to an old card
        if (wanted && scrollTick > 0 && scrollTick > scrollDoneTick) {
            delay(120)
            runCatching { requester.bringIntoView() }
            onScrolled(scrollTick)
        }
    }
    Surface(
        shape = RoundedCornerShape(if (nested) 12.dp else 16.dp),
        color = if (nested) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = if (nested) 12.dp else 14.dp, vertical = if (nested) 10.dp else 12.dp),
            content = content,
        )
    }
}

/** r106: what a card's kind line says ("Dropdown · 9 entries · buttons, not links") */
private fun kindLine(g: MenuGroup, kids: Int): String {
    val probe = g.probe
    val scanned = probe?.scan?.takeIf { it.ok }
    return when (g.kind) {
        "buttons" -> "Dropdown · ${g.items.size} entries · buttons, not links"
        "select" -> "Dropdown · ${g.items.size} entries · its values are the codes"
        "links" -> "Links · ${g.items.size} entries · each opens its own list"
        "heading" -> "Heading · $kids sub-menus"
        else -> when {
            probe == null -> "A list page"
            probe.state == "reading" -> "Name list · reading its first page…"
            probe.state == "failed" -> "Couldn't read its first page (${probe.note})"
            // r107: scanned part by part
            scanned != null -> "Directory · scanned · " + entriesText(scanned) +
                if (probe.pages > 1) " · ${BuilderExtrasLogic.thousands(probe.pages)} pages" else " · one page"
            probe.pages > 1 || probe.hasNext ->
                "Name list with pages found (1, 2, 3 … ${if (probe.pages > 2) probe.pages.toString() else "more"}) · ${probe.entries} names on page 1"
            probe.entries > 0 -> "Name list · ${probe.entries} names on one page"
            else -> "No list of names found on it" + if (probe.note.isNotBlank()) " (${probe.note})" else ""
        }
    }
}

/** r107: "25,786 entries" (shown by the site), "about 1,800 entries" (pages × page 1) or page 1's count */
private fun entriesText(scan: BuilderListReader.DirScan): String = when {
    scan.total > 0 -> "${BuilderExtrasLogic.thousands(scan.total)} entries"
    scan.pages > 1 -> "about ${BuilderExtrasLogic.thousands(scan.entries.size * scan.pages)} entries"
    else -> "${scan.entries.size} entries"
}

/**
 * r106: one picked menu as a card (mock Main): its name and kind, then only what fits: a dropdown of buttons learns its
 * codes here ([LearnSection]), links make a filter or tabs, a directory reads all its pages ([ReadSection]); a heading's
 * sub-menus are rows inside it (each its own small card) with "Read all N"
 */
@Composable
private fun MenuCard(group: MenuGroup, s: BuilderExtras, extras: BuilderExtrasModel, nested: Boolean, openAtFirst: Boolean = false) {
    val kids = s.menuGroups.filter { it.parent == group.id }
    val learning = s.learnGroup?.takeIf { it.menuId == group.id }
    val pending = s.pendings[group.id]
    val run = group.doAll
    val reading = group.read?.running == true || kids.any { it.read?.running == true } || run?.running == true
    // KMK (r112): a finished card folds to one line (tap it to open); remembered before any branch
    var unfolded by rememberSaveable(group.id) { mutableStateOf(false) }
    // KMK (r112): the directory row of "Do all N" opened under its row (to untick parts), null = none
    var openKid by rememberSaveable(group.id) { mutableStateOf<Int?>(null) }
    val fold = foldLine(group, s, kids)
    val folded = fold != null && !unfolded && !openAtFirst && learning == null && pending == null && !reading
    if (folded && fold != null) {
        CardBox(
            active = false,
            target = "menu:${group.id}",
            scrollTarget = s.scrollTarget,
            scrollTick = s.scrollTick,
            nested = nested,
            scrollDoneTick = s.scrollDone,
            onScrolled = extras::scrollDone,
        ) {
            // KMK (r112, mock BEasy): "✓ Sort · 9 codes · default "Recently added" ▸"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { unfolded = true },
            ) {
                Text("✓", color = OkGreen, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                Text(
                    group.name.ifBlank { "Menu" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    " · $fold",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("▸", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
        }
        return
    }
    CardBox(
        active = learning != null || pending != null || reading,
        target = "menu:${group.id}",
        scrollTarget = s.scrollTarget,
        scrollTick = s.scrollTick,
        nested = nested,
        scrollDoneTick = s.scrollDone,
        onScrolled = extras::scrollDone,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                val made = group.made
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!made.isNullOrEmpty()) {
                        Text("✓ ", color = OkGreen, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        group.name.ifBlank { "Menu" } + when {
                            kids.isNotEmpty() && group.kind == "heading" -> " · group"
                            made == "tabs" -> " · added as tabs"
                            !made.isNullOrEmpty() -> " · filter made"
                            else -> ""
                        },
                        style = if (nested) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    kindLine(group, kids.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // KMK (r112): a finished card opened from its one line folds again
            if (fold != null && unfolded && !openAtFirst) {
                IconButton(onClick = { unfolded = false }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.ExpandLess, contentDescription = "Fold", modifier = Modifier.size(16.dp))
                }
            }
            IconButton(onClick = { extras.dismissMenuGroup(group.id) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Not this", modifier = Modifier.size(16.dp))
            }
        }
        if (group.made == "") {
            Text("No filter could be made of it (see the note above)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        when {
            learning != null -> LearnSection(learning, s.learn, extras, s, menuId = group.id)
            pending != null -> ReplaceBox(pending, extras, false, s.scrollTick, inCard = true)
            else -> MenuActions(group, extras)
        }
        if (kids.isNotEmpty()) {
            if (run != null) {
                // KMK (r112, mock BEasy): one progress box and a row per directory; a row opens its card under it
                DoAllBox(group, run, kids, s, extras, openKid) { id -> openKid = if (openKid == id) null else id }
            } else {
                if (group.kind == "heading") {
                    Hint(
                        "A heading with ${kids.size} sub-menus. Each one becomes a filter inside “${group.name.ifBlank { "this group" }}” in Browse.",
                    )
                }
                kids.forEach { kid -> key(kid.id) { MenuCard(kid, s, extras, nested = true) } }
            }
            val todo = extras.doAllKids(s, group.id)
            if (todo.isNotEmpty() && run?.running != true) {
                // KMK (r112): "Do all N" (r106's "Make all N"): scans, learns and makes each directory in a row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { extras.doAllUnder(group.id) }, enabled = !reading) {
                        Text(if (todo.size == 1) "Do the last one" else "Do all ${todo.size}")
                    }
                    Spacer(Modifier.width(8.dp))
                    Hint("scans each directory, learns its sort and menus, makes its filter, one after another")
                }
            }
        }
    }
}

/**
 * KMK (r112, mock BEasy): what a finished card folds to ("9 codes · default "Recently added"", "505 · 6 sorts · live",
 * "picks the list", "Genres · Makers · made"); null = not finished (it stays open)
 */
private fun foldLine(g: MenuGroup, s: BuilderExtras, kids: List<MenuGroup>): String? {
    if (g.dirLearn?.running == true || g.read?.running == true || g.doAll?.running == true) return null
    if (s.dirPick == g.id || s.picturePick == g.id || s.pendings.containsKey(g.id)) return null
    // a "needs you" row of Do all, or a menu that couldn't be learned / was cut short, keeps the card open
    if (g.doAll?.rows.orEmpty().values.any { it.state == "needs" }) return null
    if (g.dirLearn?.menus.orEmpty().any { it.state == "failed" || it.state == "stopped" }) return null
    if (kids.isNotEmpty()) {
        if (g.kind != "heading" || kids.any { it.made.isNullOrEmpty() }) return null
        return kids.joinToString(" · ") { it.name.ifBlank { "menu" } } + " · made"
    }
    val made = g.made?.takeIf { it.isNotEmpty() } ?: return null
    if (made == "tabs") return "added as tabs"
    val f = s.filters.firstOrNull { it.key == made }
    val c = f?.collection
    return when {
        f == null -> "made"
        c != null -> listOfNotNull(
            BuilderExtrasLogic.thousands(if (c.total > 0) c.total else f.options.size),
            c.sorts.size.takeIf { it > 0 }?.let { "$it sorts" },
            c.menus.size.takeIf { it > 0 }?.let { "$it menus" },
            if (c.live) "live" else "kept",
        ).joinToString(" · ")
        f.role == "page" -> "picks the list"
        else -> {
            val codes = f.options.count { it.value.isNotEmpty() }
            val default = f.siteDefault.ifEmpty { f.options.firstOrNull { it.value.isEmpty() }?.label.orEmpty() }
            "$codes codes" + if (default.isNotEmpty()) " · default \"$default\"" else ""
        }
    }
}

/**
 * KMK (r112, mock BEasy): "Do all N" on the group's card: "<directory> · learning Cup (8 of 19)", time left, one bar and
 * Stop for the whole group; then a row per directory (✓ ready with its counts and sorts · … what it is doing · waiting
 * · ! needs you and why) with its part chips; a tap opens that directory's card under its row (untick a part there: the
 * run uses it when it gets there, or makes the filter again when it was made)
 */
@Composable
private fun DoAllBox(
    group: MenuGroup,
    run: DoAllRun,
    kids: List<MenuGroup>,
    s: BuilderExtras,
    extras: BuilderExtrasModel,
    openKid: Int?,
    onRow: (Int) -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run.running) {
        while (run.running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val order = run.kids.mapNotNull { id -> kids.firstOrNull { it.id == id } } + kids.filter { k -> k.id !in run.kids }
    val cur = order.firstOrNull { it.id == run.current }
    val total = run.kids.size.coerceAtLeast(1)
    val finished = run.rows.count { (_, r) -> r.state == "ready" || r.state == "needs" }
    val part = if (cur == null) 0f else doAllPart(cur, run.phase)
    val fraction = ((finished + part) / total).coerceIn(0f, 1f)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (run.running) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (run.running) {
                            (cur?.name?.ifBlank { null } ?: "Directory") + " · " + doAllPhaseText(cur, run.phase)
                        } else {
                            run.note.ifEmpty { "Done" }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (run.running) {
                        val elapsed = (now - run.started).coerceAtLeast(0L)
                        val left = if (fraction > 0.03f) (elapsed * (1f - fraction) / fraction).toLong() else -1L
                        Text(
                            "${finished + if (cur != null) 1 else 0} of ${run.kids.size}" +
                                if (left >= 0L) " · ${BuilderExtrasLogic.durationText(left)} left" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (run.running) OutlinedButton(onClick = { extras.stopDoAll(group.id) }) { Text("Stop") }
            }
            if (run.running) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            order.forEachIndexed { i, kid ->
                key(kid.id) {
                    DoAllRowLine(i + 1, kid, run.rows[kid.id], s, open = openKid == kid.id) { onRow(kid.id) }
                    if (openKid == kid.id) MenuCard(kid, s, extras, nested = true, openAtFirst = true)
                }
            }
            Hint(
                "Each directory is scanned, its parts ticked by the usual defaults (tap a row to change them), its sort and " +
                    "menus learned, and its filter made, one after another. Live as you scroll unless you choose otherwise.",
            )
        }
    }
}

/** KMK (r112): how far the directory being done is (0–1) by what it is doing, for the group's one bar */
private fun doAllPart(kid: MenuGroup, phase: String): Float = when (phase) {
    "scanning" -> 0.05f
    "learning" -> {
        val l = kid.dirLearn
        0.1f + 0.7f * if (l == null || l.total <= 0) 0f else (l.done.toFloat() / l.total).coerceIn(0f, 1f)
    }
    "reading" -> {
        val r = kid.read
        0.8f + 0.2f * if (r == null || r.maxPages <= 0) 0f else ((r.page - 1).toFloat() / r.maxPages).coerceIn(0f, 1f)
    }
    "making" -> 0.9f
    else -> 0f
}

/** KMK (r112): "learning Cup (8 of 19)", "reading page 3 of 10", "reading its first page", "making its filter" */
private fun doAllPhaseText(kid: MenuGroup?, phase: String): String = when (phase) {
    "scanning" -> "reading its first page"
    "learning" -> {
        val l = kid?.dirLearn
        if (l == null || !l.running || l.label.isEmpty()) "learning its menus" else "learning ${l.label} (${l.done} of ${l.total})"
    }
    "reading" -> kid?.read?.takeIf { it.running }?.let { "reading page ${it.page} of ${it.maxPages}" } ?: "reading its pages"
    "making" -> "making its filter"
    else -> "starting"
}

/** KMK (r112): one directory's row of "Do all N" (mock BEasy) */
@Composable
private fun DoAllRowLine(n: Int, kid: MenuGroup, row: DoAllRow?, s: BuilderExtras, open: Boolean, onClick: () -> Unit) {
    val made = !kid.made.isNullOrEmpty()
    val state = when {
        s.pendings.containsKey(kid.id) -> "needs"
        // made by hand after the run stopped or asked: ready, unless a menu still can't be learned
        row != null && row.state != "ready" && row.state != "running" && made && " learn" !in row.note -> "ready"
        row != null -> row.state
        made -> "ready"
        else -> "waiting"
    }
    val (mark, tint) = when (state) {
        "ready" -> "✓" to OkGreen
        "running" -> "…" to MaterialTheme.colorScheme.primary
        "needs" -> "!" to WarnAmber
        "stopped" -> "■" to WarnAmber
        else -> "$n" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scan = kid.probe?.scan?.takeIf { it.ok }
    val people = BuilderExtrasLogic.peopleLike(kid.name, scan?.url)
    val chips = if (scan == null) {
        emptyList()
    } else {
        val choice = kid.choice ?: BuilderExtrasLogic.defaultChoice(kid.name, scan)
        BuilderExtrasLogic.COLLECTION_FIELDS.filter { (it == "title" || it in choice.fields) && scan.part(it) != null }
            .map { if (it == "picture" && people) "Faces" else partName(it, people) }
    }
    val right = when (state) {
        "ready" -> readyText(kid, s)
        "running" -> runningText(kid)
        "needs" -> row?.note?.ifEmpty { null } ?: "a filter with its name is there: Replace or Keep both on its card"
        "stopped" -> row?.note?.ifEmpty { null } ?: "stopped"
        else -> "waiting"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = tint.copy(alpha = 0.18f), modifier = Modifier.size(20.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(mark, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(kid.name.ifBlank { "Directory" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                chips.take(3).forEach { RoleChip(it) }
            }
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (open) "Close its card" else "Open its card",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            right,
            style = MaterialTheme.typography.bodySmall,
            color = if (state == "needs") WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 28.dp),
        )
    }
}

/** KMK (r112): a made directory's row: "505 · 6 sorts · 3 menus · ready" */
private fun readyText(kid: MenuGroup, s: BuilderExtras): String {
    val f = s.filters.firstOrNull { it.key == kid.made && it.type == "collection" }
    val c = f?.collection ?: return "ready"
    return listOfNotNull(
        BuilderExtrasLogic.thousands(if (c.total > 0) c.total else f.options.size),
        c.sorts.size.takeIf { it > 0 }?.let { "$it sorts" },
        c.menus.size.takeIf { it > 0 }?.let { "$it menus" },
        if (c.live) "live · ready" else "ready",
    ).joinToString(" · ")
}

/** KMK (r112): the directory being done: "Sort ✓ Height ✓ Cup … Age" while learning, else its read */
private fun runningText(kid: MenuGroup): String {
    val l = kid.dirLearn
    val r = kid.read
    return when {
        l != null && l.running && l.menus.isNotEmpty() -> l.menus.joinToString(" ") { m ->
            m.label + when (m.state) {
                "done" -> " ✓"
                "running" -> " …"
                "failed" -> " !"
                "stopped" -> " ■"
                else -> ""
            }
        }
        r != null && r.running -> "reading page ${r.page} of ${r.maxPages} · ${BuilderExtrasLogic.thousands(r.entries)} so far"
        kid.probe?.state == "reading" -> "reading its first page"
        else -> "working"
    }
}

/** r106: a card's own buttons by its kind (nothing learning or asking on it) */
@Composable
private fun MenuActions(group: MenuGroup, extras: BuilderExtrasModel) {
    val read = group.read
    // (remembered before any branch: no remember inside a condition)
    var start by remember(group.id) { mutableStateOf(false) }
    when (group.kind) {
        "buttons" -> {
            if (!group.made.isNullOrEmpty()) return
            if (!start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { start = true }) {
                        Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Learn the codes")
                    }
                    Spacer(Modifier.width(8.dp))
                    Hint("presses each entry, reads the address")
                }
            } else {
                PauseAndStart(
                    extras = extras,
                    startLabel = "Learn the codes",
                    onStart = { min, max -> extras.startMenuLearn(group.id, min, max) },
                    onCancel = { start = false },
                )
            }
        }
        "select" -> Row {
            Button(onClick = { extras.makeMenuFilter(group.id) }) { Text(if (group.made.isNullOrEmpty()) "Make filter" else "Make it again") }
        }
        "links" -> {
            group.items.take(3).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.text.ifBlank { item.value.orEmpty() }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    MonoLine(item.href?.let { BuilderExtrasLogic.relative(it, it.toSiteOrigin()) }.orEmpty())
                }
            }
            if (group.items.size > 3) Hint("… and ${group.items.size - 3} more")
            if (group.made.isNullOrEmpty()) {
                Hint("Links: each entry opens its own list, so there's nothing to learn. A filter of them picks the list.")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { extras.makeMenuFilter(group.id) }) { Text(if (group.made.isNullOrEmpty()) "Make filter" else "Make it again") }
                OutlinedButton(onClick = { extras.addMenuAsTabs(group.id) }) { Text("Add as tabs instead") }
            }
        }
        "directory" -> {
            val probe = group.probe
            val scan = probe?.scan?.takeIf { it.ok }
            when {
                read != null && read.running -> ReadSection(group, read, extras)
                // r107: scanned: the parts, sorts, menus and how Browse gets the names (mocks CScan / BRead)
                scan != null -> CollectionSection(group, scan, read, extras)
                read != null -> {
                    Text(
                        "${read.entries} names · ${read.page} page${if (read.page == 1) "" else "s"} read" +
                            (group.href?.let { " · " + BuilderExtrasLogic.relative(it, it.toSiteOrigin()) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (read.entries > 0) OkGreen else MaterialTheme.colorScheme.error,
                    )
                    read.reason?.let { Hint("Stopped: $it") }
                    TextButton(onClick = { extras.readMenuGroup(group.id) }) { Text("Read again") }
                }
                probe == null || probe.state == "reading" -> Unit
                probe.pages > 1 || probe.hasNext -> {
                    Button(onClick = { extras.readMenuGroup(group.id) }) { Text("Read all pages") }
                    Hint(
                        "${BuilderExtrasLogic.READ_PAGES} pages at most. Any other name you type in Browse still works: " +
                            "it's turned into the site's address form.",
                    )
                }
                else -> Button(onClick = { extras.readMenuGroup(group.id, maxPages = 1) }) { Text("Make filter") }
            }
        }
        else -> Unit
    }
}

/**
 * r106: "Read all pages" running on a card: page k of the limit, names so far, Stop. r107 (mock BRead's progress card):
 * "<name> · reading page 14 of 40", the names so far, the countdown to the next page and the time left
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadSection(group: MenuGroup, read: GroupRead, extras: BuilderExtrasModel) {
    // KMK (r109): when the read starts (the model's "read:<id>" request), this progress box comes to the top of the
    // panel once; later progress updates and other cards never move the panel
    val st by extras.state.collectAsState()
    val requester = remember { BringIntoViewRequester() }
    // KMK (r109) fix: the panel's own scroll and this box's top in the root (px, not state: read when scrolling)
    val panel = LocalPanelScroll.current
    val boxTop = remember { FloatArray(1) }
    val gap = with(LocalDensity.current) { 8.dp.toPx() }
    val target = "read:${group.id}"
    val wanted = st.scrollTarget == target
    LaunchedEffect(st.scrollTick, wanted) {
        val tick = st.scrollTick
        if (wanted && tick > 0 && tick > st.scrollDone) {
            delay(150)
            if (panel != null) {
                // only the panel scrolls: its top to this box's top
                val shift = boxTop[0] - panel.viewportTop - gap
                val to = (panel.state.value + shift).toInt().coerceIn(0, panel.state.maxValue)
                runCatching { panel.state.animateScrollTo(to) }
            } else {
                runCatching { requester.bringIntoView(Rect(0f, 0f, 1f, 1f)) }
            }
            extras.scrollDone(tick)
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val wait = (read.waitUntil - now).coerceAtLeast(0L)
    val (low, high) = extras.pauseSeconds()
    val perPage = if (read.pageMs > 0) read.pageMs else ((low + high) * 500f).toLong() + BuilderExtrasLogic.PAGE_FETCH_MS
    val pagesLeft = (read.maxPages - read.page + 1).coerceAtLeast(0)
    val leftMs = wait + (pagesLeft - if (wait > 0) 0 else 1).coerceAtLeast(0) * perPage + if (wait > 0) 0L else BuilderExtrasLogic.PAGE_FETCH_MS
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { boxTop[0] = it.positionInRoot().y }
            .bringIntoViewRequester(requester),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${group.name.ifBlank { "List" }} · reading page ${read.page} of ${read.maxPages}" +
                        if (read.first > 1) " (site page ${read.first + read.page - 1})" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { extras.stopRead(group.id) }) { Text("Stop") }
            }
            LinearProgressIndicator(
                progress = { ((read.page - 1).toFloat() / read.maxPages.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${BuilderExtrasLogic.thousands(read.entries)} names so far" +
                    (if (wait > 0) " · next page in ${String.format(Locale.US, "%.1f", wait / 1000f)} s" else "") +
                    " · ${BuilderExtrasLogic.durationText(leftMs)} left",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** r107: a part's name on the scan card */
private fun partName(kind: String, people: Boolean): String = when (kind) {
    "number" -> "Number"
    "title" -> if (people) "Name" else "Title"
    "videos" -> "Videos"
    "views" -> "Views"
    "likes" -> "Likes"
    "picture" -> "Picture"
    "follow" -> "Follow"
    else -> kind
}

/** r107: "4,476 videos · 6.2M views · 17.2K ♥" of the parts kept */
private fun countsLine(e: BuilderListReader.DirEntry, fields: Collection<String>): String = listOfNotNull(
    e.videos.takeIf { it.isNotEmpty() && "videos" in fields }?.let { "$it videos" },
    e.views.takeIf { it.isNotEmpty() && "views" in fields }?.let { "$it views" },
    e.likes.takeIf { it.isNotEmpty() && "likes" in fields }?.let { "$it ♥" },
).joinToString(" · ")

/**
 * r107 (mocks CScan / BRead): a scanned directory on its card: each part of an entry with a tick and page 1's example
 * (the name always kept, Follow greyed and never kept), the drop-down row they give, the sort options found on the page
 * (they go behind Browse's sort button) and its other menus; then how Browse gets the names: Live, as you scroll (the
 * source reads the site's pages as the drop-down scrolls) or Read pages now (the page range, the pause between pages
 * and the time it takes); Make filter
 */
@Composable
private fun CollectionSection(group: MenuGroup, scan: BuilderListReader.DirScan, read: GroupRead?, extras: BuilderExtrasModel) {
    // KMK (r109): the menu pick / picture tap waiting for this card
    val st by extras.state.collectAsState()
    var pause by remember { mutableStateOf(extras.pauseSeconds().let { (a, b) -> a.coerceIn(0f, 10f)..b.coerceIn(0f, 10f) }) }
    val choice = group.choice ?: BuilderExtrasLogic.defaultChoice(group.name, scan)
    val people = BuilderExtrasLogic.peopleLike(group.name, scan.url)
    val fields = choice.fields + "title"
    val canLive = scan.pageTemplate != null || scan.pages <= 1
    val live = choice.live && canLive
    fun seconds(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    fun set(c: DirChoice) = extras.setDirChoice(group.id, c)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            entriesText(scan) + " · each entry has these parts. Tick what to keep.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        MonoLine("page 1")
    }
    val first = scan.entries.first()
    Column {
        scan.parts.forEach { p ->
            val never = p.kind == "follow"
            val checked = !never && p.kind in fields
            val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = if (never || p.kind == "title") {
                        null
                    } else {
                        { on: Boolean -> set(choice.copy(fields = if (on) choice.fields + p.kind else choice.fields - p.kind)) }
                    },
                    enabled = !never,
                )
                Text(
                    partName(p.kind, people),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (never) dim else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(76.dp),
                )
                if (p.kind == "picture" && checked && first.picture.isNotEmpty()) {
                    AsyncImage(
                        model = first.picture,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(30.dp)
                            .clip(if (people) CircleShape else RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }
                Text(
                    when {
                        never -> "a button · never kept"
                        p.kind == "picture" && !checked -> (if (people) "face" else "logo") + " · left out"
                        else -> p.example
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (never || !checked) dim else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    Hint("In the drop-down:")
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if ("number" in fields && first.number.isNotEmpty()) MonoLine(first.number)
            if ("picture" in fields && first.picture.isNotEmpty()) {
                AsyncImage(
                    model = first.picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(if (people) CircleShape else RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
            Text(
                first.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val counts = countsLine(first, fields)
            if (counts.isNotEmpty()) {
                Text(counts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
    if (scan.sorts.isNotEmpty()) {
        Hint("Sort found on the page (goes behind the ⇅ button):")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // KMK (r109): the site's default (value "") comes first and says so
            scan.sorts.forEachIndexed { i, o -> RoleChip(if (i == 0 && o.value.isEmpty()) "${o.label} · default" else o.label) }
        }
    }
    if (scan.menus.isNotEmpty()) {
        Hint("The site's own menus found (the drop-down offers them too):")
        Text(
            scan.menus.joinToString(" · ") { m -> m.name + (m.options.firstOrNull { it.value.isEmpty() }?.let { " (${it.label})" } ?: "") },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    // KMK (r109): the site's custom drop-downs still to learn on the page, picking a menu by hand, tapping a picture
    DirMenusBox(group, scan, st, extras)
    if (scan.sorts.isEmpty() && scan.menus.isEmpty() && scan.custom.isEmpty() && group.dirLearn == null) {
        Hint("No sort or menu found on this page: the drop-down keeps the site's order unless you pick the site's menu.")
    }

    // How Browse gets the names (mock BRead)
    Text("How should Browse get the names?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    val recommended = scan.total > BuilderExtrasLogic.LIVE_ENTRIES || scan.pages > BuilderExtrasLogic.LIVE_PAGES
    ModeRow(
        selected = live,
        enabled = canLive,
        title = "Live, as you scroll",
        tag = if (recommended && canLive) "recommended for big lists" else null,
        text = if (canLive) {
            "Browse reads the site's pages when the drop-down opens" +
                (if (scan.sorts.isNotEmpty() || scan.menus.isNotEmpty()) ", with the site's own sort and menus" else "") +
                ". Nothing to read now."
        } else {
            "Needs page numbers in the directory's address: none were found on its first page."
        },
        onClick = { set(choice.copy(live = true)) },
    )
    ModeRow(
        selected = !live,
        enabled = true,
        title = "Read pages now and keep them",
        tag = null,
        text = "For short lists, or to type names offline with suggestions.",
        onClick = { set(choice.copy(live = false)) },
    )
    if (!live) {
        val pages = scan.pages.coerceAtLeast(1)
        val perPage = scan.entries.size
        val from = if (scan.pageTemplate == null) 1 else choice.from.coerceIn(1, pages)
        val to = choice.to.coerceIn(from, pages)
        val count = to - from + 1
        if (pages > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pages to read", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                MonoLine(
                    "$from – $to of ${BuilderExtrasLogic.thousands(pages)} · ${BuilderExtrasLogic.thousands(count * perPage)} names",
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            // (a page range when page N has an address; else page 1 and its Next links, as many as set)
            val steps = if (pages <= 101) (pages - 2).coerceAtLeast(0) else 0
            if (scan.pageTemplate != null) {
                RangeSlider(
                    value = from.toFloat()..to.toFloat(),
                    onValueChange = { r ->
                        val a = r.start.roundToInt().coerceIn(1, pages)
                        val b = r.endInclusive.roundToInt().coerceIn(a, pages)
                        set(choice.copy(from = a, to = b))
                    },
                    valueRange = 1f..pages.toFloat(),
                    steps = steps,
                )
            } else {
                Slider(
                    value = to.toFloat(),
                    onValueChange = { v -> set(choice.copy(from = 1, to = v.roundToInt().coerceIn(1, pages))) },
                    valueRange = 1f..pages.toFloat(),
                    steps = steps,
                )
            }
        }
        val lowMs = (pause.start * 1000f).roundToLong()
        val highMs = (pause.endInclusive * 1000f).roundToLong()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pause between pages", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            MonoLine(
                "${seconds(pause.start)} – ${seconds(pause.endInclusive)} s · ${BuilderExtrasLogic.readEstimate(count, lowMs, highMs)}",
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        RangeSlider(
            value = pause,
            onValueChange = {
                pause = it
                extras.setReadPause((it.start * 1000f).roundToLong(), (it.endInclusive * 1000f).roundToLong())
            },
            valueRange = 0f..10f,
            // half-second stops, as in "Read the whole list"
            steps = 19,
        )
        Hint("Random pause inside the range, one page after another, with Stop.")
    }
    if (read != null && !read.running) {
        Text(
            "${BuilderExtrasLogic.thousands(read.entries)} names · ${read.page} page${if (read.page == 1) "" else "s"} read",
            style = MaterialTheme.typography.bodySmall,
            color = if (read.entries > 0) OkGreen else MaterialTheme.colorScheme.error,
        )
        read.reason?.let { Hint("Stopped: $it") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { extras.makeCollection(group.id) }) {
            Text(
                when {
                    !group.made.isNullOrEmpty() -> "Make it again"
                    live -> "Make filter"
                    else -> "Read and make filter"
                },
            )
        }
    }
}

/**
 * KMK (r109): on a scanned directory card: the site's custom drop-downs found but not learned yet ("Sort: Most videos",
 * "Height: Any height"…) with "Learn the menus" (pressed on the page, progress and Stop), what was learned; "Pick the
 * site's Sort menu" / "Pick another menu" (the next menu pick is this card's); "Tap the picture on the page" when the
 * cards' pictures weren't found
 */
@Composable
private fun DirMenusBox(group: MenuGroup, scan: BuilderListReader.DirScan, st: BuilderExtras, extras: BuilderExtrasModel) {
    val learning = group.dirLearn
    if (scan.custom.isNotEmpty() || learning != null) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // KMK (r110): the progress box of mock CLearn: menu, entry being pressed, n of m, time left, bar, Stop
                if (learning != null && learning.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                learning.label + if (learning.entry.isNotEmpty()) " · pressing \"${learning.entry}\"" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${learning.done} of ${learning.total} entries" + leftText(learning.leftMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = extras::stopLearn) { Text("Stop") }
                    }
                    LinearProgressIndicator(
                        progress = { (learning.done.toFloat() / learning.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(
                        "Before each menu the page goes back to the plain address, so one menu's choice isn't still set " +
                            "while the next is learned. An entry that shows \"Nothing found\" is still learned.",
                    )
                } else if (scan.custom.isNotEmpty()) {
                    Text(
                        "The site's own menus: " + scan.custom.joinToString(" · ") { m -> if (m.current.isNotEmpty()) "${m.label}: ${m.current}" else m.label },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Hint(
                        "Their entries are buttons, or show only when the menu opens: the Lab presses each one on this page " +
                            "and writes down the address it opens (the pause set for reading pages is used).",
                    )
                    val entries = scan.custom.sumOf { it.items.size }
                    Button(onClick = { extras.learnDirMenus(group.id) }) {
                        Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (entries > 0) "Learn the menus ($entries entries)" else "Learn the menus")
                    }
                }
                if (learning != null && learning.menus.isNotEmpty()) {
                    learning.menus.forEach { m ->
                        key(m.label) {
                            DirLearnMenuRow(
                                m,
                                learning.collection,
                                running = learning.running,
                                onPick = { extras.pickDirMenu(group.id, sort = m.sort) },
                                onLearnAgain = { extras.learnDirMenus(group.id) },
                            )
                        }
                    }
                } else {
                    learning?.found?.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, color = if (line.contains(" options")) OkGreen else WarnAmber)
                    }
                }
                if (learning != null && !learning.running && learning.note.isNotEmpty()) Hint(learning.note)
            }
        }
    }
    if (st.dirPick == group.id) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    if (st.dirPickSort) {
                        "On the page: tap the site's sort button (\"Sort: …\") or one of its entries"
                    } else {
                        "On the page: tap the menu's button (\"Height: …\") or one of its entries"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                TextButton(onClick = extras::cancelDirPick) { Text("Cancel") }
            }
        }
    } else if (learning?.running != true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (scan.sorts.isEmpty() && scan.custom.none { it.sort }) {
                OutlinedButton(onClick = { extras.pickDirMenu(group.id, sort = true) }) { Text("Pick the site's Sort menu") }
            }
            TextButton(onClick = { extras.pickDirMenu(group.id, sort = false) }) { Text("Pick another menu") }
        }
    }
    if (scan.part("picture") == null) {
        if (st.picturePick == group.id) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        "On the page: tap one card's picture (a face, a logo)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                    TextButton(onClick = extras::cancelPicturePick) { Text("Cancel") }
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("No pictures found on the cards.")
                TextButton(onClick = { extras.pickDirPicture(group.id) }) { Text("Tap the picture on the page") }
            }
        }
    }
}

/** KMK (r110): " · about 1 min left" from [ms] (-1 = not known yet) */
private fun leftText(ms: Long): String = when {
    ms < 0L -> ""
    ms < 60_000L -> " · less than a minute left"
    else -> " · about ${(ms + 30_000L) / 60_000L} min left"
}

/**
 * KMK (r110): one menu's row while / after "Learn the menus" (mock CLearn): ✓ done (its parameter, option count,
 * default, entries that showed nothing / failed; tap to see the learned codes "only for <collection>"), … running
 * (the last code, n of m so far), waiting, ! can't be learned (why, and "Pick it on the page")
 */
@Composable
private fun DirLearnMenuRow(m: DirLearnMenu, collection: String, running: Boolean, onPick: () -> Unit, onLearnAgain: () -> Unit) {
    // KMK (r110) fix: a finished menu's codes show open at first (tap the row to fold them). KMK (r112): only while the
    // other menus are still being learned; once the whole set is done each menu is one line ("✓ Height · 14 options ·
    // default …"), its codes open on tap
    val isDone = m.state == "done"
    var open by remember(m.label, isDone, running) { mutableStateOf(isDone && running) }
    // KMK (r110) fix: "12 actresses" on the codes' rows when the collection's name is known, else "12 found"
    val noun = collection.trim().lowercase().ifEmpty { "found" }
    val (mark, tint) = when (m.state) {
        "done" -> "✓" to OkGreen
        "running" -> "…" to MaterialTheme.colorScheme.primary
        "failed" -> "!" to WarnAmber
        "stopped" -> "■" to WarnAmber
        else -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val param = when {
        m.pathMode -> "own addresses"
        m.param.isNotEmpty() -> "${m.param}="
        else -> ""
    }
    val detail = when (m.state) {
        "done" -> buildString {
            append("${m.options} options")
            if (m.defaultLabel.isNotEmpty()) append(" · default \"${m.defaultLabel}\"")
            if (m.empty > 0) append(" · ${m.empty} showed nothing, kept")
            if (m.failed > 0) append(" · ${m.failed} failed")
        }
        "running" -> {
            val last = m.rows.lastOrNull { it.status != "pressing" }
            val so = "${m.rows.count { it.status != "pressing" }} of ${m.entries.coerceAtLeast(m.rows.size)} so far"
            if (last == null) so else "${last.text} = ${last.code.ifEmpty { "no change" }} · $so"
        }
        "failed" -> "can't be learned: ${m.note.ifEmpty { "no entry changed a value in the address" }}"
        // KMK (r110) fix: Stop: the menu cut short isn't saved; the ones never reached aren't learned
        "stopped" -> m.note.ifEmpty { "stopped" } + if (m.note == "stopped") " · not saved" else ""
        else -> "waiting"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = m.state == "done" && m.rows.isNotEmpty()) { open = !open },
        ) {
            Text(mark, color = tint, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (param.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(param, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (m.state == "failed") WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (m.state == "done" && m.rows.isNotEmpty()) {
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        if (m.state == "failed" && !running) {
            TextButton(onClick = onPick, modifier = Modifier.padding(start = 14.dp)) { Text("Pick it on the page") }
        }
        if (m.state == "stopped" && !running) {
            TextButton(onClick = onLearnAgain, modifier = Modifier.padding(start = 14.dp)) { Text("Learn again") }
        }
        if (open && m.state == "done") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = 20.dp)) {
                Text(
                    "${m.label} · learned" + if (collection.isNotEmpty()) " · only for $collection" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                m.rows.take(LEARNED_SHOWN).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.text + if (r.status == "default") " (default)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when (r.status) {
                                "default" -> "no change"
                                "failed" -> "failed"
                                else -> r.code
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (r.status == "failed") WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                r.status == "empty" -> "nothing found · kept"
                                // KMK (r112): page 1's count when the page has more pages ("24+ (page 1)")
                                r.cards > 0 && r.more -> "${r.cards}+ (page 1)"
                                r.cards > 0 -> "${r.cards} $noun"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.status == "empty") WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 72.dp).padding(start = 6.dp),
                        )
                    }
                }
                if (m.rows.size > LEARNED_SHOWN) {
                    Text("… ${m.rows.size - LEARNED_SHOWN} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** KMK (r110): how many learned entries a done menu's row lists */
private const val LEARNED_SHOWN = 12

/** r107: one way to get the names (mock BRead): a radio, its title (and tag), what it means; tap anywhere to pick it */
@Composable
private fun ModeRow(selected: Boolean, enabled: Boolean, title: String, tag: String?, text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, top = 4.dp, bottom = 8.dp)) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tag != null) {
                        Text(" · $tag", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** r106: the pause slider and Start shown once on the card before learning (mock Learn, "Before it starts") */
@Composable
private fun PauseAndStart(extras: BuilderExtrasModel, startLabel: String, onStart: (Long, Long) -> Unit, onCancel: () -> Unit) {
    var pause by remember { mutableStateOf(extras.pauseSeconds().let { (a, b) -> a.coerceIn(0f, 10f)..b.coerceIn(0f, 10f) }) }
    fun seconds(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Pause between presses", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        MonoLine("${seconds(pause.start)} – ${seconds(pause.endInclusive)} s")
    }
    RangeSlider(
        value = pause,
        onValueChange = { pause = it },
        valueRange = 0f..10f,
        // half-second stops, as in "Read the whole list"
        steps = 19,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { onStart((pause.start * 1000f).roundToLong(), (pause.endInclusive * 1000f).roundToLong()) }) { Text(startLabel) }
        OutlinedButton(onClick = onCancel) { Text("Not now") }
    }
}

private fun learnDone(status: String): Boolean = status == "learned" || status == "nochange" || status == "filled" || status == "missed" || status == "missing"

/**
 * r106: learning on its card (mocks Learn / Learned): while it runs, the progress and one row per entry (✓ code,
 * pressing…, waiting · ) with Stop; when done, what it learned: the address name, every code, Works on (Any list / This
 * page only / Search too) and Make filter, or Replace "<name>" / Keep both when a filter has its name
 */
@Composable
private fun LearnSection(group: LearnGroup, run: LearnRun?, extras: BuilderExtrasModel, s: BuilderExtras, menuId: Int?) {
    // Everything remembered up front (no remember inside a condition): the preview of a finished run, the card's Works
    // on choice and the filters it would replace
    val preview = remember(run, s.filters, s.pageRule) { if (run == null || run.running) null else extras.learnedPreview() }
    var role by remember(run, preview) { mutableStateOf(if (preview?.mode == "path") "page" else preview?.role?.ifEmpty { null } ?: "extra") }
    var searchToo by remember(run, preview) { mutableStateOf(preview?.onSearch == true) }
    val made = preview?.copy(role = role)
    val clash = remember(made, s.filters) { if (made == null) emptyList() else extras.learnedConflicts(made) }
    if (run == null) {
        Text(
            if (group.hasLinks) "Check where each entry of “${group.name}” goes" else "Learn the codes of “${group.name}”",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Hint(
            "The Lab presses each entry for you in this page and writes down the address it opens, then comes back " +
                "(${group.entries.size} entries).",
        )
        PauseAndStart(
            extras = extras,
            startLabel = if (group.hasLinks) "Check them" else "Learn the codes",
            onStart = { min, max -> if (menuId != null) extras.startMenuLearn(menuId, min, max) else extras.startLearn(min, max) },
            onCancel = extras::dismissLearn,
        )
        return
    }
    val codes = preview?.options?.filter { it.value.isNotEmpty() }?.associate { it.label to it.value }.orEmpty()
    val done = run.rows.count { learnDone(it.status) }
    if (run.running) {
        Text("${run.name} · learning the codes", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Hint("Pressing each entry on the page and reading the address it makes")
        LinearProgressIndicator(
            progress = { done.toFloat() / run.rows.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "$done of ${run.rows.size} · ${secondsMs(run.pauseMinMs)}–${secondsMs(run.pauseMaxMs)} s between presses",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "${run.name} · ${run.rows.count { it.status == "learned" || it.status == "filled" }} codes learned",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (preview != null) {
            Hint(
                if (preview.mode == "path") {
                    "The address's path changed: it picks the list"
                } else if (preview.role == "page") {
                    "The entries open another page: it picks the list"
                } else {
                    "Only the part after ? changed, so it works on any list"
                },
            )
            if (preview.mode != "path" && preview.param.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Address name", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    MonoLine(preview.param, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    Column {
        run.rows.take(MAX_LEARN_ROWS).forEach { row -> LearnRowLine(row, codes, group.pageUrl, s.lastUrl) }
        if (run.rows.size > MAX_LEARN_ROWS) Hint("+ ${run.rows.size - MAX_LEARN_ROWS} more")
    }
    val note = run.note ?: run.pattern?.let { "Pattern found: $it — the rest are filled in without pressing." }
    if (note != null && !run.running) {
        Text(note, style = MaterialTheme.typography.bodySmall, color = if (run.pattern != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (run.running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = extras::stopLearn) { Text("Stop") }
            Spacer(Modifier.width(8.dp))
            Hint("keeps what it learned so far")
        }
        return
    }
    if (preview == null || made == null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (menuId != null) OutlinedButton(onClick = { extras.startMenuLearn(menuId, run.pauseMinMs, run.pauseMaxMs) }) { Text("Learn again") }
            TextButton(onClick = extras::dismissLearn) { Text("Close") }
        }
        return
    }
    // KMK (r109): the site's own default is named after its entry, never "Site default"
    Hint(
        "The entry whose press left the address as it was (else the one the menu's button shows) comes first and sends " +
            "nothing: the site's own default. Entries whose address didn't change stay grey.",
    )
    // Works on: Any list (an extra) / This page only (picks the list: this page with the code) / Search too
    val canPage = preview.mode == "path" || "{value}" in preview.linkPath
    if (preview.mode != "path") {
        Text("Works on", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = role == "extra", onClick = { role = "extra" }, label = { Text("Any list") })
            FilterChip(selected = role == "page", enabled = canPage, onClick = { role = "page" }, label = { Text("This page only") })
            FilterChip(selected = searchToo, enabled = role == "extra", onClick = { searchToo = !searchToo }, label = { Text("Search too") })
        }
    }
    if (clash.isNotEmpty()) {
        ConflictText(made.name, clash)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { extras.makeLearnedFilter(role, searchToo && role == "extra", onConflict = true) }) {
                Text("Replace “${clash.first().name.ifBlank { made.name }}”")
            }
            OutlinedButton(onClick = { extras.makeLearnedFilter(role, searchToo && role == "extra", onConflict = false) }) { Text("Keep both") }
        }
    } else {
        Button(onClick = { extras.makeLearnedFilter(role, searchToo && role == "extra") }) { Text("Make “${made.name}” filter") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (menuId != null) TextButton(onClick = { extras.startMenuLearn(menuId, run.pauseMinMs, run.pauseMaxMs) }) { Text("Learn again") }
        TextButton(onClick = extras::dismissLearn) { Text("Close") }
    }
}

private fun secondsMs(ms: Long): String {
    val v = ms / 500L / 2f
    return if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}

/** r106: the amber note over Replace / Keep both: which filters have the name, and what they send */
@Composable
private fun ConflictText(name: String, clash: List<ExtrasFilter>) {
    val first = clash.first()
    val what = if (BuilderExtrasLogic.sendsLabels(first)) " (it sends the words, not the codes)" else ""
    Row(verticalAlignment = Alignment.Top) {
        Text("! ", color = WarnAmber, fontWeight = FontWeight.Bold)
        Text(
            if (clash.size == 1) {
                "You already have a filter named “${first.name.ifBlank { first.key }}”$what. Replacing it means Browse shows one “$name”."
            } else {
                "You already have ${clash.size} filters with this name or address name (${clash.joinToString(", ") { it.name.ifBlank { it.key } }}). Replace takes their place."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** r106: a filter waiting for Replace / Keep both (made from a card, or from a list picked the r101 way) */
@Composable
private fun ReplaceBox(p: PendingFilter, extras: BuilderExtrasModel, scroll: Boolean, tick: Int, inCard: Boolean = false, scrollDoneTick: Int = 0) {
    val body: @Composable ColumnScope.() -> Unit = {
        Text("“${p.filter.name}” is ready", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        ConflictText(p.filter.name, p.conflicts)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // r106 fix: the answer goes to this card's question (one per card)
            Button(onClick = { extras.resolvePending(true, p.menuId) }) { Text("Replace “${p.conflicts.first().name.ifBlank { p.filter.name }}”") }
            OutlinedButton(onClick = { extras.resolvePending(false, p.menuId) }) { Text("Keep both") }
            TextButton(onClick = { extras.dismissPending(p.menuId) }) { Text("Not now") }
        }
    }
    if (inCard) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = body)
    } else {
        CardBox(
            active = true,
            target = "pending",
            scrollTarget = if (scroll) "pending" else null,
            scrollTick = tick,
            scrollDoneTick = scrollDoneTick,
            onScrolled = extras::scrollDone,
            content = body,
        )
    }
}

@Composable
private fun LearnRowLine(row: LearnRow, codes: Map<String, String>, pageUrl: String, siteUrl: String?) {
    val site = siteUrl?.toSiteOrigin().orEmpty().ifEmpty { pageUrl.toSiteOrigin() }
    val (mark, right) = when (row.status) {
        "pressing" -> "◌" to "pressing…"
        "waiting" -> "·" to ""
        "nochange" -> "–" to "no change"
        "missed" -> "?" to "no address"
        "missing" -> "✕" to "not found on the page" // r102 fix: never made the filter's "Any"
        "filled" -> "✓" to (codes[row.text] ?: row.url?.let { BuilderExtrasLogic.relative(it, site) + " (filled in)" }.orEmpty())
        else -> "✓" to (codes[row.text] ?: row.url?.let { BuilderExtrasLogic.relative(it, site) }.orEmpty())
    }
    val color = when (row.status) {
        "learned", "filled" -> OkGreen
        "pressing" -> MaterialTheme.colorScheme.primary
        "missing" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
        Text(
            row.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.status == "waiting" || row.status == "nochange") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (right.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                right,
                fontFamily = if (row.status == "learned" || row.status == "filled") FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
    }
}

private const val MAX_LEARN_ROWS = 12

@Composable
private fun RoleChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** r106: what a filter row says under its name: what it sends ✓, why it isn't in the source, or that it sends labels */
private fun rowSummary(f: ExtrasFilter): String {
    val values = f.options.filter { it.value.isNotEmpty() }.map { it.value }
    val more = if (values.size > 2) " …" else ""
    val c = f.collection
    return when {
        // r107: "Collection · live · 25,786 · /en/series/…"
        f.type == "collection" && c != null -> "Collection · " +
            (if (c.live) "live" else "${BuilderExtrasLogic.thousands(values.size)} kept") +
            (if (c.total > 0) " of ${BuilderExtrasLogic.thousands(c.total)}" else "") + " · " + f.path.substringBefore("{value}") + "…"
        f.mode == "path" -> f.path.ifBlank { "…" }.substringBefore("{value}") + "…"
        "{value}" in f.linkPath && f.role == "page" -> f.linkPath.substringBefore("{value}") + values.firstOrNull().orEmpty() + more
        f.param.isNotBlank() -> "${f.param} = " + values.take(2).joinToString(", ") + more
        else -> values.take(3).joinToString(", ") + more
    }
}

/**
 * r106: one filter as a compact row (mock Main "Your filters"): name, ✓ what it sends (or the problem in red, or the
 * amber "Sends labels: learn the codes"), its role chip, ▲▼; tap opens its editor under it
 */
@Composable
private fun FilterRow(
    index: Int,
    count: Int,
    filter: ExtrasFilter,
    searchPath: String?,
    indent: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    extras: BuilderExtrasModel,
) {
    val problem = BuilderExtrasLogic.filterProblem(filter)
    val labels = problem == null && BuilderExtrasLogic.sendsLabels(filter)
    val role = BuilderExtrasLogic.roleOf(filter, searchPath)
    // KMK (r109) fix: its empty first option will be "Site default" (a parameter filter, not one opening its links' page)
    val linkPage = filter.mode != "path" && "{value}" in filter.linkPath &&
        if (filter.role.isEmpty()) BuilderExtrasLogic.usesLinkPage(filter, searchPath) else role == "page"
    val unnamed = problem == null && !linkPage && BuilderExtrasLogic.unnamedDefault(filter)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(start = if (indent) 14.dp else 0.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                filter.name.ifBlank { filter.key.ifBlank { "Filter ${index + 1}" } },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                problem != null -> Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                labels -> Text("Sends labels: learn the codes", style = MaterialTheme.typography.bodySmall, color = WarnAmber)
                filter.noLinkAddress -> Text("Pick this menu again: its own page wasn't saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rowSummary(filter),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(" ✓", color = OkGreen, fontSize = 12.sp)
                }
            }
            if (unnamed) {
                Text(
                    "Learn this menu again to name its default",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        RoleChip(if (role == "page") "Picks the list" else "Added to any list")
        IconButton(onClick = { extras.moveFilter(index, -1) }, enabled = index > 0, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { extras.moveFilter(index, 1) }, enabled = index < count - 1, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(18.dp))
        }
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Close the editor" else "Edit",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
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
            // r107: a scanned directory's collection (parts, sorts, menus, live)
            if (filter.collection != null) {
                FilterChip(
                    selected = filter.type == "collection",
                    onClick = { extras.setFilterType(index, "collection") },
                    label = { Text("Collection") },
                )
            }
        }
        val collection = filter.collection
        if (filter.type == "collection" && collection != null) {
            val canLive = collection.item.isNotEmpty() && (collection.page.isNotEmpty() || collection.pages <= 1)
            SwitchRow(
                title = "Live, as you scroll",
                summary = if (canLive) {
                    "Browse reads the directory's pages as its drop-down scrolls (the entries below are only page 1's or those read)"
                } else {
                    "Needs page numbers in the directory's address: scan it again from its menu card"
                },
                checked = collection.live,
                onChange = { extras.setCollectionLive(index, it) },
            )
            val found = collection.parts.map { it.kind }.filter { it in BuilderExtrasLogic.COLLECTION_FIELDS && it != "title" }
            if (found.isNotEmpty()) {
                Text("Parts shown in the drop-down", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    found.forEach { kind ->
                        FilterChip(
                            selected = kind in collection.fields,
                            onClick = { extras.setCollectionField(index, kind, kind !in collection.fields) },
                            label = { Text(partName(kind, false)) },
                        )
                    }
                }
            }
            if (collection.sorts.isNotEmpty() || collection.menus.isNotEmpty()) {
                Hint(
                    listOfNotNull(
                        collection.sorts.takeIf { it.isNotEmpty() }?.let { l -> "Sort: " + l.joinToString(", ") { it.label } },
                        collection.menus.takeIf { it.isNotEmpty() }?.let { l -> "Menus: " + l.joinToString(", ") { it.name } },
                    ).joinToString(" · "),
                )
            }
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
            "select", "sort", "autocomplete", "collection" -> {
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
                            "collection" -> "the drop-down's entries, in this order (a name not in it is sent as a slug)"
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
