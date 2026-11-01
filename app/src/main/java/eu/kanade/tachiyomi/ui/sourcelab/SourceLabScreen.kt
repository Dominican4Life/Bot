package eu.kanade.tachiyomi.ui.sourcelab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.diagnostics.DiagTab
import eu.kanade.tachiyomi.jssource.lab.GeneratedSource
import eu.kanade.tachiyomi.jssource.lab.HealthState
import eu.kanade.tachiyomi.jssource.lab.LabHandoff
import eu.kanade.tachiyomi.jssource.lab.LabSource
import eu.kanade.tachiyomi.ui.codeeditor.CodeTab
import eu.kanade.tachiyomi.ui.codeeditor.EditorDialogs
import eu.kanade.tachiyomi.ui.codeeditor.MenuItem
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import eu.kanade.tachiyomi.ui.codeeditor.rememberEditorActions
import eu.kanade.tachiyomi.ui.diagnostics.DiagnosticsScreen
import eu.kanade.tachiyomi.ui.sourcelab.newsource.LabFlowScreenFactory
import eu.kanade.tachiyomi.ui.sourcelab.newsource.TemplatesPane
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * KMK (r96): the Source Lab, in place of the Code editor (D§1). `key == null` is Home: the source list with health,
 * the Rules file and Re-test all cards. With a key: the source's six tabs Code · Explain · Test · Lint · Report ·
 * History (phone: a tab row; tablet: a rail and two panes, the code on the left). The only Lab entry from outside;
 * Half B's New-source flow comes back through [LabHandoff].
 */
internal class SourceLabScreen(
    private val sourceKey: String? = null,
    private val initialTab: LabTab = LabTab.Code,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { SourceLabScreenModel(sourceKey, initialTab) }
        val editor = rememberScreenModel(tag = "editor") { SourceEditorScreenModel(sourceKey) }
        val state by model.state.collectAsState()
        val editorState by editor.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        val tablet = isTabletUi()
        var findOpen by remember { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf(model.lastQuery()) }
        var railDest by rememberSaveable { mutableStateOf(RailDest.Sources) }
        var showMenu by remember { mutableStateOf(false) }
        var confirmTemplateUpdate by remember { mutableStateOf(false) } // KMK (r100)
        // KMK (r101): the tablet's Source info panel (open / hidden, kept across sessions) and the phone's sheet
        val sourceInfo = rememberSourceInfoOpen()
        var infoSheet by rememberSaveable { mutableStateOf(false) }
        val pop: () -> Unit = { navigator.pop() }
        // r98: the app's Log (Diagnostics, Log tab) on top of the Lab; Back returns here with the editor untouched
        val openLogs: () -> Unit = { navigator.push(DiagnosticsScreen(DiagTab.LOG)) }
        val actions = rememberEditorActions(editor, editorState, onLeave = pop)

        // The New-source flow's result (C§5.2): taken once on arrival, and whenever one lands while this is on top.
        // Opening it in the editor goes through the unsaved-changes dialog: edits typed meanwhile aren't thrown away
        // (the generated source is saved either way; a cancelled open leaves it on Home)
        // (reads the editor's live state, not the composition's: the effects below keep their first lambda)
        val adopt: (GeneratedSource) -> Unit = { generated ->
            model.adoptGenerated(editor, generated) { what, open ->
                if (editor.state.value.dirty) actions.confirmReplace.value = what to open else open()
            }
        }
        LaunchedEffect(Unit) { LabHandoff.take()?.let(adopt) }
        val pending by LabHandoff.pending.collectAsState()
        LaunchedEffect(pending) {
            if (pending != null) LabHandoff.take()?.let(adopt)
        }
        LaunchedEffect(editorState.justSaved) { editorState.justSaved?.let { model.onEditorSaved(it) } }
        LaunchedEffect(editorState.code, editorState.json, state.key) {
            if (state.key == null) return@LaunchedEffect
            delay(1200)
            model.relineExplain(editorState.code, editorState.json)
        }
        LaunchedEffect(state.message) {
            val message = state.message ?: return@LaunchedEffect
            try {
                snackbar.showSnackbar(message)
            } finally {
                model.consumeMessage(message)
            }
        }
        LaunchedEffect(state.idNote) {
            val note = state.idNote ?: return@LaunchedEffect
            try {
                snackbar.showSnackbar(note, withDismissAction = true, duration = SnackbarDuration.Long)
            } finally {
                model.consumeIdNote()
            }
        }

        // KMK (r100): "Update template?" (Save / Discard / Cancel first when the editor has unsaved edits)
        val offerToConfirm = state.templateOffer
        if (confirmTemplateUpdate && offerToConfirm != null) {
            TemplateUpdateDialog(
                offer = offerToConfirm,
                onConfirm = {
                    confirmTemplateUpdate = false
                    actions.askBeforeReplacing("Updating the template") { model.updateTemplate(editor) }
                },
                onDismiss = { confirmTemplateUpdate = false },
            )
        }

        // KMK (r101): the phone's Source info, from the Code tab's header row (the code isn't pushed down any more)
        if (infoSheet && !tablet && state.key != null) {
            ModalBottomSheet(onDismissRequest = { infoSheet = false }) {
                SourceInfoPanel(
                    editor = editor,
                    editorState = editorState,
                    labState = state,
                    onHide = null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        EditorDialogs(
            model = editor,
            state = editorState,
            actions = actions,
            snackbar = snackbar,
            onLeave = pop,
            onDeleted = { model.open(null) },
        )

        // Whether this screen has shown Home: a source opened from Browse (key given, Home never shown) has nothing
        // of the Lab below it, so Back leaves; after Home was shown, Back from a source returns to Home
        var visitedHome by rememberSaveable { mutableStateOf(sourceKey == null) }
        LaunchedEffect(state.key) { if (state.key == null) visitedHome = true }
        // Home from an open source, through the unsaved-changes dialog (Save / Discard / Cancel); on Discard the
        // editor drops the edits now, so Home doesn't ask about them a second time
        val goHome: () -> Unit = {
            actions.askBeforeReplacing("Going to the source list") {
                val s = editor.state.value
                if (s.dirty) {
                    if (s.saved) editor.open(s.key) else editor.newSource(s.json, s.anime)
                }
                railDest = RailDest.Sources
                if (model.state.value.key != null) model.open(null)
            }
        }

        val openSource: (String, LabTab) -> Unit = { key, tab ->
            val name = editor.savedName(key)
            actions.askBeforeReplacing("Opening ${name ?: "another source"}") {
                editor.open(key)
                model.open(key, tab)
                railDest = RailDest.Sources
            }
        }
        // New source asks about unsaved edits first (Save / Discard / Cancel); on Discard the editor drops them now,
        // so the generated source doesn't ask a second time when it arrives
        val newSource: () -> Unit = {
            actions.askBeforeReplacing("Starting a new source") {
                val s = editor.state.value
                if (s.dirty) {
                    if (s.saved) {
                        editor.open(s.key)
                    } else {
                        editor.newSource(s.json, s.anime)
                        model.open(null)
                    }
                }
                navigator.push(LabFlowScreenFactory.newSource(null))
            }
        }
        // Back inside the Lab: Templates → what was behind it; a source opened from Home → Home (phone and tablet
        // alike; r96 had `!tablet` here, so the tablet always left); a source opened from Browse, or Home → the caller
        val backInsideLab = railDest != RailDest.Sources || (state.key != null && visitedHome)
        fun back() {
            when {
                railDest != RailDest.Sources -> railDest = RailDest.Sources
                state.key != null && visitedHome -> goHome()
                else -> actions.leave()
            }
        }
        // The system Back too: without this it popped the screen (Browse) from a source opened on Home. Declared
        // after EditorDialogs' dirty handler so it wins while enabled; the Code tab's find bar, declared later, wins
        // over both while open
        BackHandler(enabled = backInsideLab) { back() }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (state.key == null && !tablet && railDest == RailDest.Sources) {
                    ExtendedFloatingActionButton(
                        onClick = newSource,
                        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        text = { Text("New source") },
                    )
                }
            },
        ) { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (tablet) {
                    LabRail(
                        state = state,
                        dest = railDest,
                        onSources = goHome,
                        onNewSource = newSource,
                        onRules = { navigator.push(RulesScreen()) },
                        onTemplates = { railDest = RailDest.Templates },
                        onRetest = model::retestAll,
                        onTab = model::setTab,
                        onLogs = openLogs,
                    )
                    VerticalDivider()
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    LabHeader(
                        state = state,
                        editorState = editorState,
                        dest = railDest,
                        onBack = { back() },
                        onSave = editor::save,
                        menu = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = null)
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    if (state.key == null) {
                                        MenuItem("New source", Icons.Outlined.Add) {
                                            showMenu = false
                                            newSource()
                                        }
                                        MenuItem("Templates", Icons.Outlined.Dashboard) {
                                            showMenu = false
                                            railDest = RailDest.Templates
                                        }
                                        MenuItem("Rules file", Icons.Outlined.Checklist) {
                                            showMenu = false
                                            navigator.push(RulesScreen())
                                        }
                                        MenuItem("Re-test all", Icons.Outlined.Refresh) {
                                            showMenu = false
                                            model.retestAll()
                                        }
                                        MenuItem("Check all sources", Icons.AutoMirrored.Outlined.List) {
                                            showMenu = false
                                            model.checkAllSources()
                                        }
                                        MenuItem("Logs", Icons.Outlined.Terminal) {
                                            showMenu = false
                                            openLogs()
                                        }
                                    } else {
                                        MenuItem("Copy code", Icons.Outlined.ContentCopy) {
                                            showMenu = false
                                            actions.copyCode()
                                        }
                                        MenuItem("Paste code", Icons.Outlined.ContentPaste) {
                                            showMenu = false
                                            actions.pasteCode()
                                        }
                                        MenuItem("Search code", Icons.Outlined.Search) {
                                            showMenu = false
                                            model.setTab(LabTab.Code)
                                            findOpen = true
                                        }
                                        HorizontalDivider()
                                        MenuItem("Import Source", Icons.Outlined.FileUpload) {
                                            showMenu = false
                                            actions.importFile()
                                        }
                                        MenuItem("Export Source", Icons.Outlined.FileDownload) {
                                            showMenu = false
                                            actions.exportFile()
                                        }
                                        if (editorState.saved) {
                                            MenuItem("Duplicate", Icons.Outlined.ContentCopy) {
                                                showMenu = false
                                                model.duplicate(editor)
                                            }
                                            MenuItem("Delete Source", Icons.Outlined.DeleteOutline) {
                                                showMenu = false
                                                actions.deleteSource()
                                            }
                                        }
                                        HorizontalDivider()
                                        MenuItem("Logs", Icons.Outlined.Terminal) {
                                            showMenu = false
                                            openLogs()
                                        }
                                    }
                                }
                            }
                        },
                    )
                    LabStrip(state = state, editorState = editorState)
                    // KMK (r100): the source's template has a newer version
                    val templateOffer = state.templateOffer
                    if (templateOffer != null && state.key != null && railDest == RailDest.Sources) {
                        TemplateUpdateBar(
                            offer = templateOffer,
                            busy = state.updatingTemplate,
                            onUpdate = { confirmTemplateUpdate = true },
                        )
                    }
                    if (editorState.running != null || state.running || state.retestProgress != null || state.reportBuilding) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        HorizontalDivider()
                    }
                    when {
                        railDest == RailDest.Templates -> TemplatesPane(modifier = Modifier.fillMaxSize())
                        state.key == null -> HomePane(
                            state = state,
                            model = model,
                            onOpen = { key -> openSource(key, LabTab.Code) },
                            onRules = { navigator.push(RulesScreen()) },
                            onTemplates = { railDest = RailDest.Templates },
                            tablet = tablet,
                            modifier = Modifier.fillMaxSize(),
                        )
                        tablet -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            // r101 fix: the code column leaves the pane at least 380 dp (a portrait tablet), never
                            // under 360 dp itself, and at most 700 dp (Explain) / 600 dp (the other tabs)
                            val room = maxWidth
                            fun codeWidth(cap: Dp): Dp = maxOf(360.dp, minOf(cap, room - 380.dp))
                            Row(modifier = Modifier.fillMaxSize()) {
                                // KMK (r101): one CodeTab for every tab (its scroll and find bar survive a tab switch),
                                // only its width changes: Code fills the room beside the Source info panel, Explain
                                // gets up to 700 dp, the other tabs up to 600 dp
                                val codeModifier = when (state.tab) {
                                    LabTab.Code -> Modifier.weight(1f)
                                    LabTab.Explain -> Modifier.width(codeWidth(700.dp))
                                    else -> Modifier.width(codeWidth(600.dp))
                                }
                                CodeTab(
                                    model = editor,
                                    state = editorState,
                                    findOpen = findOpen,
                                    onFindOpenChange = { findOpen = it },
                                    highlight = state.highlight,
                                    revealLine = state.revealLine,
                                    modifier = codeModifier.fillMaxHeight(),
                                )
                                VerticalDivider()
                                if (state.tab == LabTab.Code) {
                                    if (sourceInfo.open) {
                                        SourceInfoPanel(
                                            editor = editor,
                                            editorState = editorState,
                                            labState = state,
                                            onHide = { sourceInfo.set(false) },
                                            modifier = Modifier.width(340.dp).fillMaxHeight(),
                                        )
                                    } else {
                                        SourceInfoStrip(
                                            iconUrl = editorState.iconUrl,
                                            onOpen = { sourceInfo.set(true) },
                                            modifier = Modifier.width(44.dp).fillMaxHeight(),
                                        )
                                    }
                                } else {
                                    SourcePane(
                                        tab = state.tab,
                                        state = state,
                                        model = model,
                                        editor = editor,
                                        editorState = editorState,
                                        query = query,
                                        onQuery = { query = it },
                                        askBeforeReplacing = actions.askBeforeReplacing,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                            }
                        }
                        else -> Column(modifier = Modifier.fillMaxSize()) {
                            SecondaryTabRow(selectedTabIndex = state.tab.ordinal) {
                                LabTab.entries.forEach { tab ->
                                    Tab(
                                        selected = state.tab == tab,
                                        onClick = { model.setTab(tab) },
                                        text = { Text(tab.label, fontSize = 12.sp, maxLines = 1) },
                                    )
                                }
                            }
                            if (state.tab == LabTab.Code) {
                                CodeTab(
                                    model = editor,
                                    state = editorState,
                                    findOpen = findOpen,
                                    onFindOpenChange = { findOpen = it },
                                    highlight = state.highlight,
                                    revealLine = state.revealLine,
                                    modifier = Modifier.fillMaxSize(),
                                    onOpenInfo = { infoSheet = true },
                                )
                            } else {
                                SourcePane(
                                    tab = state.tab,
                                    state = state,
                                    model = model,
                                    editor = editor,
                                    editorState = editorState,
                                    query = query,
                                    onQuery = { query = it },
                                    askBeforeReplacing = actions.askBeforeReplacing,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the tablet rail (or the phone's ⋮) shows in the main area besides a source */
internal enum class RailDest { Sources, Templates }

/** The source tabs other than Code (the phone shows Code through [CodeTab]) */
@Composable
private fun SourcePane(
    tab: LabTab,
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editor: SourceEditorScreenModel,
    editorState: SourceEditorScreenModel.State,
    query: String,
    onQuery: (String) -> Unit,
    askBeforeReplacing: (String, () -> Unit) -> Unit,
    modifier: Modifier,
) {
    when (tab) {
        LabTab.Code, LabTab.Explain -> ExplainPane(state = state, model = model, editorState = editorState, modifier = modifier)
        LabTab.Test -> TestPane(state = state, model = model, editor = editor, editorState = editorState, query = query, onQuery = onQuery, modifier = modifier)
        LabTab.Lint -> LintPane(state = state, model = model, editor = editor, modifier = modifier)
        LabTab.Report -> ReportPane(state = state, model = model, modifier = modifier)
        LabTab.History -> HistoryPane(
            state = state,
            model = model,
            editor = editor,
            editorState = editorState,
            askBeforeReplacing = askBeforeReplacing,
            modifier = modifier,
        )
    }
}

@Composable
private fun LabHeader(
    state: SourceLabScreenModel.State,
    editorState: SourceEditorScreenModel.State,
    dest: RailDest,
    onBack: () -> Unit,
    onSave: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val source = state.source
    val title = when {
        dest == RailDest.Templates -> "Templates"
        state.key == null -> "Source Lab"
        editorState.saved -> editorState.name.ifBlank { "Source" }
        else -> editorState.name.ifBlank { "New source" }
    }
    val subtitle = when {
        dest == RailDest.Templates -> "the files the Lab writes sources from"
        state.key == null -> buildString {
            append("${state.sources.size} ${if (state.sources.size == 1) "source" else "sources"}")
            state.rules?.let { append(" · rules v${it.version}") }
            append(" · last re-test ${state.retestLast}")
        }
        else -> buildString {
            append(if (editorState.anime) "Anime" else "Manga")
            append(" · ").append(if (editorState.json) "JSON" else "JS")
            if (source != null && source.version > 0) append(" · v${source.version}")
            source?.lastRun?.let { append(" · tested ${SourceLabScreenModel.agoText(it.startedAt)}") }
            if (!editorState.saved) append(" · not saved yet") else if (editorState.dirty) append(" · not saved")
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.key != null && dest == RailDest.Sources) {
            FilledTonalButton(onClick = onSave, enabled = editorState.running == null) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.width(18.dp))
                Text("  Save")
            }
        }
        menu()
    }
}

/** The status strip: Home sums the sources up; a source shows its own state */
@Composable
private fun LabStrip(state: SourceLabScreenModel.State, editorState: SourceEditorScreenModel.State) {
    if (state.key == null) {
        val good = state.sources.count { it.health.state == HealthState.Good }
        val attention = state.sources.count { it.health.state == HealthState.Attention }
        val failing = state.sources.count { it.health.state == HealthState.Failing }
        val (head, color) = when {
            failing > 0 -> "Failing" to StripColor.Red
            attention > 0 -> "Attention" to StripColor.Amber
            good > 0 -> "Good" to StripColor.Green
            else -> "New" to StripColor.Neutral
        }
        LabStatusStrip(
            head = if (state.retestProgress != null) "Testing" else head,
            color = if (state.retestProgress != null) StripColor.Blue else color,
            figures = listOf(
                "Sources" to state.sources.size.toString(),
                "Good" to good.toString(),
                "Attention" to attention.toString(),
                "Failing" to failing.toString(),
            ),
        )
        return
    }
    val source: LabSource? = state.source
    val (head, color) = when {
        state.running -> "Testing" to StripColor.Blue
        !editorState.saved -> "New" to StripColor.Blue
        source == null -> "New" to StripColor.Neutral
        else -> when (source.health.state) {
            HealthState.Good -> "Good" to StripColor.Green
            HealthState.Attention -> "Attention" to StripColor.Amber
            HealthState.Failing -> "Failing" to StripColor.Red
            HealthState.Unknown -> "Untested" to StripColor.Neutral
        }
    }
    val lintCount = state.lint?.findings?.count { !it.ignored } ?: source?.lastLint?.findings?.count { !it.ignored }
    LabStatusStrip(
        head = head,
        color = color,
        figures = listOf(
            "Version" to (source?.version?.takeIf { it > 0 }?.let { "v$it" } ?: "—"),
            "Last test" to (state.lastRun?.let { SourceLabScreenModel.agoText(it.startedAt).removeSuffix(" ago") } ?: "never"),
            "Lint" to (lintCount?.toString() ?: "—"),
            "Errors" to (source?.health?.errorsToday?.toString() ?: "0"),
        ),
    )
}

@Composable
private fun LabRail(
    state: SourceLabScreenModel.State,
    dest: RailDest,
    onSources: () -> Unit,
    onNewSource: () -> Unit,
    onRules: () -> Unit,
    onTemplates: () -> Unit,
    onRetest: () -> Unit,
    onTab: (LabTab) -> Unit,
    onLogs: () -> Unit,
) {
    NavigationRail {
        NavigationRailItem(
            selected = state.key == null && dest == RailDest.Sources,
            onClick = onSources,
            icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
            label = { Text("Sources") },
        )
        NavigationRailItem(
            selected = false,
            onClick = onNewSource,
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            label = { Text("New source") },
        )
        NavigationRailItem(
            selected = false,
            onClick = onRules,
            icon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
            label = { Text("Rules file") },
        )
        NavigationRailItem(
            selected = dest == RailDest.Templates,
            onClick = onTemplates,
            icon = { Icon(Icons.Outlined.Dashboard, contentDescription = null) },
            label = { Text("Templates") },
        )
        NavigationRailItem(
            selected = false,
            onClick = onRetest,
            enabled = state.retestProgress == null,
            icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
            label = { Text("Re-test all") },
        )
        if (state.key != null && dest == RailDest.Sources) {
            // A fixed width, never fillMaxWidth: the rail's Column is wrap-content (`widthIn(min = 80.dp)`) and is
            // measured with the whole screen as its max, so a fillMaxWidth divider (M3's default) grew the rail to
            // the full width and the content Column next to it got 0 dp (r96: empty panes once a source opened)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp).width(56.dp))
            LabTab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = state.tab == tab,
                    onClick = { onTab(tab) },
                    icon = { Text(tab.label.take(1), style = MaterialTheme.typography.titleMedium) },
                    label = { Text(tab.label) },
                )
            }
        }
        // r98: Logs at the bottom of the rail (the rail's Column fills the height, so the spacer takes what's left)
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = false,
            onClick = onLogs,
            icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
            label = { Text("Logs") },
        )
    }
}
