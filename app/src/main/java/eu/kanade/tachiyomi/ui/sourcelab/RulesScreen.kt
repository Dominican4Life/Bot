package eu.kanade.tachiyomi.ui.sourcelab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.jssource.lab.LintCheck
import eu.kanade.tachiyomi.jssource.lab.LintRule
import eu.kanade.tachiyomi.jssource.lab.LintSeverity
import eu.kanade.tachiyomi.jssource.lab.RuleOverride
import eu.kanade.tachiyomi.jssource.lab.RuleOverrides
import eu.kanade.tachiyomi.jssource.lab.RulesFile
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * KMK (r96): the Rules file screen (D§2.7). KMK (r115): redesigned per M3Rules: the rules as a short list with
 * filter chips (All / Code / Site / Results / Off), a switch and a level chip per row, and an edit panel (level,
 * wording, Reset to built-in) beside the list on a wide screen, above it on a narrow one. The overrides live in
 * [RuleOverrides] (`sourcelab/rule_overrides.json`), never in rules.md. The long text is the Help view; Update
 * from file, Export, Check all sources and Versions › Roll back stay. [ruleId] opens that rule's edit panel.
 */
internal class RulesScreen(private val ruleId: String? = null) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { SourceLabScreenModel(null, LabTab.Code) }
        val state by model.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(state.message) {
            val message = state.message ?: return@LaunchedEffect
            try {
                snackbar.showSnackbar(message)
            } finally {
                model.consumeMessage(message)
            }
        }
        // KMK (r110): the pushed form (Lint's / Explain's "Read the rule"); the Lab's rail shows [RulesContent] in place
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            RulesContent(
                model = model,
                ruleId = ruleId,
                onBack = { navigator.pop() },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

// KMK (r115): which list a rule sits in, from its check kind
private enum class RuleKind(val label: String) { Code("Code"), Site("Site"), Results("Results"), Tip("Tip") }

private enum class RuleFilter(val label: String) { All("All"), Code("Code"), Site("Site"), Results("Results"), Off("Off") }

private fun kindOf(rule: LintRule): RuleKind = when (LintCheck.of(rule.check)) {
    LintCheck.RegexInCode, LintCheck.SelectorPattern, LintCheck.RenderOption -> RuleKind.Code
    LintCheck.HeaderPresent, LintCheck.ResponseStatus -> RuleKind.Site
    LintCheck.ResultField, LintCheck.CompareSavedChapterUrls -> RuleKind.Results
    // `text` is a tip; another unknown check kind is listed with the code checks as "not checked by this build"
    null -> if (rule.check == "text" || rule.check.isBlank()) RuleKind.Tip else RuleKind.Code
}

private fun isTip(rule: LintRule) = rule.check == "text" || rule.check.isBlank()

/** "Runs on" in plain words */
private fun runsOn(rule: LintRule): String = when (LintCheck.of(rule.check)) {
    LintCheck.RegexInCode -> "the code" + (rule.params["part"]?.let { " (part $it)" } ?: "")
    LintCheck.SelectorPattern -> "the selectors in the code"
    LintCheck.RenderOption -> "host.render calls in the code"
    LintCheck.HeaderPresent -> if (rule.params["on"] == "images") "the first page image of the last Test Run" else "the videos of the last Test Run"
    LintCheck.ResponseStatus -> "the site's replies in the last Test Run" + (rule.params["status"]?.let { " ($it)" } ?: "")
    LintCheck.ResultField -> "what the last Test Run returned" + (rule.params["step"]?.let { " ($it)" } ?: "")
    LintCheck.CompareSavedChapterUrls -> "the chapters against your library"
    null -> if (isTip(rule)) "nothing: a tip, listed to read when it fits the source" else "not checked by this build (${rule.check})"
}

@Composable
private fun LintSeverity.color(): Color = when (this) {
    LintSeverity.Error -> StripColor.Red.tint()
    LintSeverity.Warn -> StripColor.Amber.tint()
    LintSeverity.Info -> MaterialTheme.colorScheme.primary
}

/**
 * KMK (r110): the Rules file's body, shared by the pushed [RulesScreen] and the Lab's rail place "Rules" (shown next
 * to the rail, no back arrow: [onBack] null). [model] is the caller's: the Lab passes its own, so its snackbar shows
 * the messages (Check all sources, Update from file) and Lint sees a new rules file at once. The top bar is the Lab's
 * [LabTopBar] in both forms (the pushed one with its back arrow).
 */
@Composable
internal fun RulesContent(
    model: SourceLabScreenModel,
    ruleId: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsState()
    val overrides by RuleOverrides.flow.collectAsState()
    var view by rememberSaveable { mutableStateOf(0) } // 0 rules, 1 help, 2 versions
    var filterName by rememberSaveable { mutableStateOf(RuleFilter.All.name) }
    var selectedId by rememberSaveable { mutableStateOf(ruleId) }
    var helpSection by rememberSaveable { mutableStateOf<String?>(null) }
    val rules = state.rules
    val helpState = rememberLazyListState()
    val listState = rememberLazyListState()
    // KMK (r103): the markdown is split, and the asset rules.md plus the imported versions are read, off the main
    // thread (they were read in composition)
    val blocks by produceState(emptyList<Block>(), rules?.body) {
        val body = rules?.body
        value = if (body == null) emptyList() else withContext(Dispatchers.Default) { markdownBlocks(body) }
    }
    val versions by produceState(emptyList<Pair<Int, String>>(), rules) {
        value = withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val list = runCatching { model.rulesVersions() }.getOrDefault(emptyList())
            val ms = (System.nanoTime() - started) / 1_000_000
            AppLog.add('D', "lab", "Rules: ${list.size} versions read off the main thread in $ms ms")
            list
        }
    }
    // KMK (r115): the overrides file is read off the main thread (a restore may have replaced it)
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { RuleOverrides.refresh() } } }
    val hits = remember(state.sources) {
        state.sources.flatMap { s -> s.lastLint?.findings.orEmpty().filter { !it.ignored }.map { it.ruleId }.distinct() }
            .groupingBy { it }.eachCount()
    }
    val all = rules?.rules.orEmpty()
    val checks = remember(all) { all.filter { !isTip(it) } }
    val tips = remember(all) { all.filter { isTip(it) } }
    val filter = RuleFilter.entries.firstOrNull { it.name == filterName } ?: RuleFilter.All
    val counts = remember(all, overrides) {
        RuleFilter.entries.associateWith { f ->
            when (f) {
                RuleFilter.All -> all.size
                RuleFilter.Off -> all.count { overrides[it.id]?.enabled == false }
                RuleFilter.Code -> checks.count { kindOf(it) == RuleKind.Code }
                RuleFilter.Site -> checks.count { kindOf(it) == RuleKind.Site }
                RuleFilter.Results -> checks.count { kindOf(it) == RuleKind.Results }
            }
        }
    }
    val shownChecks = remember(checks, overrides, filter) {
        when (filter) {
            RuleFilter.All -> checks
            RuleFilter.Off -> checks.filter { overrides[it.id]?.enabled == false }
            RuleFilter.Code -> checks.filter { kindOf(it) == RuleKind.Code }
            RuleFilter.Site -> checks.filter { kindOf(it) == RuleKind.Site }
            RuleFilter.Results -> checks.filter { kindOf(it) == RuleKind.Results }
        }
    }
    val shownTips = remember(tips, overrides, filter) {
        when (filter) {
            RuleFilter.All -> tips
            RuleFilter.Off -> tips.filter { overrides[it.id]?.enabled == false }
            else -> emptyList()
        }
    }
    val selected = selectedId?.let { id -> all.firstOrNull { it.id == id } }
    val selectedHelp = remember(selected?.id, rules) { selected?.let { helpBlocks(rules, it) }.orEmpty() }

    fun store(id: String, override: RuleOverride?) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { RuleOverrides.set(id, override) }.isSuccess }
            if (!ok) context.toast("Couldn't save the rule's change")
        }
    }

    // Read the rule (from Lint / Explain): show its row once the rules are there
    LaunchedEffect(ruleId, all.size) {
        val id = ruleId ?: return@LaunchedEffect
        val inChecks = checks.indexOfFirst { it.id == id }
        val inTips = tips.indexOfFirst { it.id == id }
        // item 0 is the filter chips; on a narrow screen item 1 is the panel, so the row stays just below it
        val index = when {
            inChecks >= 0 -> 1 + inChecks
            inTips >= 0 -> 2 + checks.size + inTips
            else -> return@LaunchedEffect
        }
        if (filterName == RuleFilter.All.name) listState.scrollToItem(index)
    }
    // Help: jump to a rule's section once the body is there
    LaunchedEffect(view, blocks.size, helpSection) {
        val section = helpSection ?: return@LaunchedEffect
        if (view != 1) return@LaunchedEffect
        val index = blocks.indexOfFirst { it is Block.Heading && it.number == section }
        if (index >= 0) {
            helpState.scrollToItem(index)
            helpSection = null
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment
            model.updateRules(text, name)
        }.onFailure { context.toast("Couldn't read the file: ${it.message}") }
    }
    val offCount = counts[RuleFilter.Off] ?: 0
    // The edit panel, hoisted out of the BoxWithConstraints below
    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
        if (selected != null) {
            RuleEditPanel(
                rule = selected,
                override = overrides[selected.id],
                hits = hits[selected.id] ?: 0,
                help = selectedHelp,
                onSave = { store(selected.id, it) },
                onReset = { store(selected.id, null) },
                onOpenHelp = {
                    helpSection = selected.section
                    view = 1
                },
                onClose = { selectedId = null },
                modifier = panelModifier,
            )
        } else {
            EmptyPanel(panelModifier)
        }
    }

    Column(modifier = modifier) {
        LabTopBar(
            title = "Rules",
            subtitle = if (rules == null) {
                "no rules file"
            } else {
                "${checks.size} checks · ${tips.size} tips · rules v${rules.version} · " +
                    (rules.importedAt?.let { "imported ${SourceLabScreenModel.agoText(it)}" } ?: "built in") +
                    if (offCount > 0) " · $offCount off" else ""
            },
            onBack = onBack,
        ) {}
        HorizontalDivider()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            FilterChip(selected = view == 0, onClick = { view = 0 }, label = { Text("Rules") })
            FilterChip(selected = view == 1, onClick = { view = 1 }, label = { Text("Help") })
            FilterChip(selected = view == 2, onClick = { view = 2 }, label = { Text("Versions") })
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) { Text("Update from file") }
            OutlinedButton(
                onClick = {
                    val file = model.exportRules()
                    if (file == null) {
                        context.toast("Nothing to export")
                    } else {
                        runCatching { context.startActivity(file.getUriCompat(context).toShareIntent(context, type = "text/plain")) }
                            .onFailure { context.toast(it.message ?: "Couldn't share") }
                    }
                },
            ) { Text("Export") }
            OutlinedButton(onClick = model::checkAllSources) { Text("Check all sources") }
        }
        val problem = when {
            rules == null -> state.rulesError ?: "The built-in rules file didn't parse."
            state.rulesError != null -> "Last import refused: ${state.rulesError}"
            else -> null
        }
        if (problem != null) {
            Text(
                problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when (view) {
            0 -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val wide = maxWidth >= 720.dp
                if (wide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        RuleList(
                            listState = listState,
                            filter = filter,
                            counts = counts,
                            checks = shownChecks,
                            tips = shownTips,
                            overrides = overrides,
                            hits = hits,
                            selectedId = selectedId,
                            onFilter = { filterName = it.name },
                            onSelect = { selectedId = it },
                            onToggle = { rule, on -> store(rule.id, (overrides[rule.id] ?: RuleOverride()).copy(enabled = on)) },
                            showPanel = false,
                            panel = {},
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        Box(
                            modifier = Modifier
                                .width(380.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(end = 16.dp, top = 4.dp, bottom = 32.dp),
                        ) {
                            panel(Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    RuleList(
                        listState = listState,
                        filter = filter,
                        counts = counts,
                        checks = shownChecks,
                        tips = shownTips,
                        overrides = overrides,
                        hits = hits,
                        selectedId = selectedId,
                        onFilter = { filterName = it.name },
                        onSelect = { selectedId = if (selectedId == it) null else it },
                        onToggle = { rule, on -> store(rule.id, (overrides[rule.id] ?: RuleOverride()).copy(enabled = on)) },
                        showPanel = selected != null,
                        panel = { panel(Modifier.fillMaxWidth()) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            1 -> LazyColumn(
                state = helpState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(blocks.size, key = { "b$it" }) { index ->
                    val block = blocks[index]
                    val machineRule = (block as? Block.Heading)?.let { h -> checks.firstOrNull { it.section == h.number } }
                    MarkdownBlock(block, machineRule?.id)
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "file") {
                    Text(
                        text = if (rules == null) {
                            "No rules file"
                        } else {
                            "v${rules.version}" +
                                (rules.importedAt?.let { " · imported ${SourceLabScreenModel.agoText(it)}" } ?: " · built into this release") +
                                (rules.fileName?.let { " from $it" } ?: "") +
                                " · your switches and wording are kept apart and stay across versions"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (versions.isEmpty()) {
                    item(key = "no-versions") { Text("No versions yet.", style = MaterialTheme.typography.bodyMedium) }
                }
                items(versions.size, key = { "v${versions[it].first}" }) { index ->
                    val (version, label) = versions[index]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        val current = rules != null &&
                            ((version == 0 && rules.importedAt == null) || (version > 0 && rules.importedAt != null && rules.version == version))
                        if (current) {
                            Text("current", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        } else {
                            TextButton(onClick = { model.rollBackRules(version) }) { Text("Roll back") }
                        }
                    }
                    HorizontalDivider()
                }
                if (overrides.isNotEmpty()) {
                    item(key = "reset-all") {
                        TextButton(
                            onClick = {
                                scope.launch { withContext(Dispatchers.IO) { runCatching { RuleOverrides.resetAll() } } }
                            },
                        ) { Text("Reset every rule to built-in (${overrides.size} changed)") }
                    }
                }
            }
        }
    }
}

/** KMK (r115): the first lines of [rule]'s Help section, for the edit panel's "Why" */
private fun helpBlocks(rules: RulesFile?, rule: LintRule): List<Block> {
    val range = rules?.sections?.get(rule.section) ?: return emptyList()
    val lines = rules.body.lines()
    val from = range.first.coerceIn(0, lines.size)
    val to = (range.last + 1).coerceIn(from, lines.size)
    return markdownBlocks(lines.subList(from, to).take(14).joinToString("\n")).filter { it !is Block.Heading }
}

@Composable
private fun RuleList(
    listState: LazyListState,
    filter: RuleFilter,
    counts: Map<RuleFilter, Int>,
    checks: List<LintRule>,
    tips: List<LintRule>,
    overrides: Map<String, RuleOverride>,
    hits: Map<String, Int>,
    selectedId: String?,
    onFilter: (RuleFilter) -> Unit,
    onSelect: (String) -> Unit,
    onToggle: (LintRule, Boolean) -> Unit,
    showPanel: Boolean,
    panel: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        modifier = modifier,
    ) {
        item(key = "filters") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                RuleFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { onFilter(f) },
                        label = { Text("${f.label} ${counts[f] ?: 0}") },
                    )
                }
            }
        }
        if (showPanel) {
            item(key = "panel") {
                Box(modifier = Modifier.padding(bottom = 10.dp)) { panel() }
            }
        }
        if (checks.isEmpty() && tips.isEmpty()) {
            item(key = "none") {
                Text(
                    if (filter == RuleFilter.Off) "No rule is switched off." else "No rules here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        items(checks.size, key = { "c${checks[it].id}" }) { index ->
            val rule = checks[index]
            RuleRow(
                rule = rule,
                override = overrides[rule.id],
                hits = hits[rule.id] ?: 0,
                selected = rule.id == selectedId,
                shape = groupShape(index, checks.size),
                divider = index > 0,
                onClick = { onSelect(rule.id) },
                onToggle = { onToggle(rule, it) },
            )
        }
        if (tips.isNotEmpty()) {
            item(key = "tips-head") {
                Text(
                    "TIPS TO READ",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp),
                )
            }
        }
        items(tips.size, key = { "t${tips[it].id}" }) { index ->
            val rule = tips[index]
            RuleRow(
                rule = rule,
                override = overrides[rule.id],
                hits = hits[rule.id] ?: 0,
                selected = rule.id == selectedId,
                shape = groupShape(index, tips.size),
                divider = index > 0,
                onClick = { onSelect(rule.id) },
                onToggle = { onToggle(rule, it) },
            )
        }
    }
}

/** Rows of one soft group: the first rounds its top, the last its bottom */
private fun groupShape(index: Int, size: Int): Shape {
    val r = 16.dp
    return when {
        size == 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        index == size - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RectangleShape
    }
}

@Composable
private fun RuleRow(
    rule: LintRule,
    override: RuleOverride?,
    hits: Int,
    selected: Boolean,
    shape: Shape,
    divider: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val on = override?.enabled != false
    val level = override?.severity ?: rule.severity
    val title = override?.title?.takeIf { it.isNotBlank() } ?: rule.title
    val known = isTip(rule) || LintCheck.of(rule.check) != null
    val kind = kindOf(rule)
    val levelColor = level.color()
    val dot = if (on) levelColor else MaterialTheme.colorScheme.outline
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick),
    ) {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dot))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(kind.label)
                        append(" · ")
                        append(if (known) rule.id else "not checked by this build")
                        if (!on) append(" · switched off")
                        if (hits > 0) append(" · $hits ${if (hits == 1) "source" else "sources"} hit")
                        if (override != null && on) append(" · changed")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (known) MaterialTheme.colorScheme.onSurfaceVariant else StripColor.Amber.tint(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LevelChip(level, if (on) levelColor else MaterialTheme.colorScheme.outline)
            Switch(checked = on, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun LevelChip(level: LintSeverity, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.16f)) {
        Text(
            level.name.lowercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun PanelCard(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            content()
        }
    }
}

@Composable
private fun EmptyPanel(modifier: Modifier) {
    PanelCard(modifier) {
        Text("Tap a rule to change it", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Each rule has a switch and a level. Open one to change its level or its words, or to read why it exists. " +
                "Your changes are kept apart from the rules file, so an update of the file keeps them; Reset to built-in drops them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One labelled row of the edit panel: the label on the left, [content] on the right */
@Composable
private fun PanelRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(84.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { content() }
    }
}

@Composable
private fun RuleEditPanel(
    rule: LintRule,
    override: RuleOverride?,
    hits: Int,
    help: List<Block>,
    onSave: (RuleOverride) -> Unit,
    onReset: () -> Unit,
    onOpenHelp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The draft starts from the rule as it runs now, and starts again when another rule or a saved change arrives
    var enabled by remember(rule.id, override) { mutableStateOf(override?.enabled != false) }
    var level by remember(rule.id, override) { mutableStateOf(override?.severity ?: rule.severity) }
    var title by remember(rule.id, override) { mutableStateOf(override?.title ?: rule.title) }
    var says by remember(rule.id, override) { mutableStateOf(override?.fix ?: rule.fix) }
    val draft = RuleOverride(
        enabled = enabled,
        severity = level.takeIf { it != rule.severity },
        title = title.trim().takeIf { it.isNotEmpty() && it != rule.title },
        fix = says.trim().takeIf { it.isNotEmpty() && it != rule.fix },
    )
    val saved = override ?: RuleOverride()
    val dirty = draft != saved
    val tip = isTip(rule)
    val codeColor = MaterialTheme.colorScheme.primary

    PanelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${rule.id} · ${kindOf(rule).label}" + if (hits > 0) " · $hits ${if (hits == 1) "source" else "sources"} hit" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        PanelRow("On") { Switch(checked = enabled, onCheckedChange = { enabled = it }) }
        PanelRow("Level") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LintSeverity.entries.reversed().forEach { s ->
                    FilterChip(
                        selected = level == s,
                        onClick = { level = s },
                        label = { Text(s.name.lowercase()) },
                    )
                }
            }
        }
        if (level != rule.severity) {
            Text(
                "Built in: ${rule.severity.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PanelRow("Runs on") {
            Text(runsOn(rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = says,
            onValueChange = { says = it },
            label = { Text(if (tip) "Says" else "Says (the fix shown with a finding)") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!tip) {
            PanelRow("Fix button") {
                val apply = rule.apply
                if (apply == null) {
                    Text("none", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        inlineText("`${apply.find}` → `${apply.replace}`", codeColor),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (help.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text(
                "WHY · §${rule.section}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            help.forEach { MarkdownBlock(it, null) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rule.section.isNotBlank()) TextButton(onClick = onOpenHelp) { Text("Open in Help") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onReset, enabled = override != null) { Text("Reset to built-in") }
            Button(onClick = { onSave(draft) }, enabled = dirty) { Text("Save") }
        }
        Text(
            "Lint uses changes the next time it runs (after a save or a Test Run, or Check all sources).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
    }
}

// ---------- a small markdown renderer: headings, paragraphs with bold / code, lists, fenced code, tables ----------

internal sealed interface Block {
    data class Heading(val level: Int, val text: String, val number: String?) : Block
    data class Paragraph(val text: String) : Block
    data class Bullet(val text: String, val ordered: Boolean) : Block
    data class Code(val text: String) : Block
}

internal fun markdownBlocks(markdown: String): List<Block> {
    val out = ArrayList<Block>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    fun flush() {
        if (paragraph.isNotBlank()) out.add(Block.Paragraph(paragraph.toString().trim()))
        paragraph.setLength(0)
    }
    markdown.lines().forEach { raw ->
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out.add(Block.Code(code.toString().trimEnd()))
                code.setLength(0)
            } else {
                flush()
            }
            inCode = !inCode
            return@forEach
        }
        if (inCode) {
            code.appendLine(raw)
            return@forEach
        }
        val heading = Regex("""^(#{1,6})\s+(.*)$""").find(line)
        when {
            heading != null -> {
                flush()
                val text = heading.groupValues[2].trim()
                out.add(Block.Heading(heading.groupValues[1].length, text, Regex("""^(\d+(?:\.\d+)*)\.?\s""").find(text)?.groupValues?.get(1)))
            }
            line.isBlank() -> flush()
            Regex("""^\s*[-*]\s+""").containsMatchIn(line) -> {
                flush()
                out.add(Block.Bullet(line.replaceFirst(Regex("""^\s*[-*]\s+"""), ""), ordered = false))
            }
            Regex("""^\s*\d+\.\s+""").containsMatchIn(line) -> {
                flush()
                out.add(Block.Bullet(line.replaceFirst(Regex("""^\s*\d+\.\s+"""), ""), ordered = true))
            }
            line.trimStart().startsWith("|") -> {
                flush()
                if (!Regex("""^\|[\s:|-]+\|$""").matches(line.trim())) out.add(Block.Code(line.trim()))
            }
            else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(line.trim())
        }
    }
    flush()
    if (inCode && code.isNotBlank()) out.add(Block.Code(code.toString().trimEnd()))
    return out
}

/** `**bold**`, `` `code` `` and `[text](link)` inline */
internal fun inlineText(text: String, codeColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val linkless = text.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    while (i < linkless.length) {
        when {
            linkless.startsWith("**", i) -> {
                val end = linkless.indexOf("**", i + 2)
                if (end < 0) {
                    append(linkless.substring(i))
                    i = linkless.length
                } else {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(linkless.substring(i + 2, end))
                    pop()
                    i = end + 2
                }
            }
            linkless[i] == '`' -> {
                val end = linkless.indexOf('`', i + 1)
                if (end < 0) {
                    append(linkless.substring(i))
                    i = linkless.length
                } else {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, fontSize = 12.sp))
                    append(linkless.substring(i + 1, end))
                    pop()
                    i = end + 1
                }
            }
            else -> {
                append(linkless[i])
                i++
            }
        }
    }
}

@Composable
internal fun MarkdownBlock(block: Block, machineRuleId: String?) {
    val codeColor = MaterialTheme.colorScheme.primary
    when (block) {
        is Block.Heading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (block.level <= 2) 12.dp else 8.dp)) {
            Text(
                inlineText(block.text, codeColor),
                style = when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    3 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (machineRuleId != null) Pill("machine rule", codeColor)
        }
        is Block.Paragraph -> Text(inlineText(block.text, codeColor), style = MaterialTheme.typography.bodyMedium)
        is Block.Bullet -> Row(modifier = Modifier.padding(start = 8.dp)) {
            Text(if (block.ordered) "·" else "•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
            Text(inlineText(block.text, codeColor), style = MaterialTheme.typography.bodyMedium)
        }
        is Block.Code -> MonoCard(title = null, text = block.text)
    }
}
