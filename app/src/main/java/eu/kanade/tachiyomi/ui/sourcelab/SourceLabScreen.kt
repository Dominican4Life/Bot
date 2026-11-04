package eu.kanade.tachiyomi.ui.sourcelab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.diagnostics.DiagTab
import eu.kanade.tachiyomi.jssource.lab.GeneratedSource
import eu.kanade.tachiyomi.jssource.lab.History
import eu.kanade.tachiyomi.jssource.lab.LabHandoff
import eu.kanade.tachiyomi.ui.codeeditor.CodeTab
import eu.kanade.tachiyomi.ui.codeeditor.EditorDialogs
import eu.kanade.tachiyomi.ui.codeeditor.MenuItem
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import eu.kanade.tachiyomi.ui.codeeditor.rememberEditorActions
import eu.kanade.tachiyomi.ui.diagnostics.DiagnosticsScreen
import eu.kanade.tachiyomi.ui.sourcelab.newsource.BuilderScreen
import eu.kanade.tachiyomi.ui.sourcelab.newsource.LabNewSourcePane
import eu.kanade.tachiyomi.ui.sourcelab.newsource.TemplatesPane
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * KMK (r96): the Source Lab, in place of the Code editor (D§1). `key == null` is Home: the source list with health.
 * With a key: the source's six tabs Code · Explain · Test · Lint · History · Report (phone: a tab row; tablet: the
 * rail). The only Lab entry from outside; Half B's New-source flow comes back through [LabHandoff].
 *
 * KMK (r106, the approved mocks EHome / ECode / ETest / ELint / EHistory): one top bar per place replaces the header
 * and the status strip (Home: Find a source, Re-test all, New source, ⋮; a source: name + "kind · format · vN · tested
 * …", the "k of n passing" badge, Test, Save, ⋮). The tablet rail is labelled (home: Sources, New, Rules, Templates;
 * a source: ‹ Sources, its tabs with Lint's count, Logs). Code = Parts outline · code · Test Run panel; Explain keeps
 * the code beside it; Test / Lint / History / Report fill the width and "Go to line" opens the Code tab.
 *
 * KMK (r115): from 600 dp of screen width (the same test as More) one rail (LabRail): the Lab's places on top, the
 * open source's name and tabs under a divider, Logs at the bottom. A phone (< 600 dp) gets a bottom bar instead
 * (LabBottomBar: the places, or the open source's tabs), a back arrow to Sources in the source's top bar, Logs in ⋮,
 * and single-column panes (Code: Parts chips · code · the finding bar).
 */
internal class SourceLabScreen(
    private val sourceKey: String? = null,
    private val initialTab: LabTab = LabTab.Code,
) : Screen() {

    @Composable
    override fun Content() {
        // KMK (r115): every width; under 600 dp the phone layout (bottom bar, single column)
        LabContent()
    }

    @Composable
    private fun LabContent() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { SourceLabScreenModel(sourceKey, initialTab) }
        val editor = rememberScreenModel(tag = "editor") { SourceEditorScreenModel(sourceKey) }
        val state by model.state.collectAsState()
        val editorState by editor.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        // KMK (r115): the Lab always has the rail now (it only opens at ≥ 600 dp); [tablet] only says whether the
        // top bars have room for their labelled buttons and the search field (≥ 840 dp), else their icon forms
        val tablet = LocalConfiguration.current.screenWidthDp >= LAB_ROOMY_BAR_DP
        // KMK (r115): the phone layout (owner: "as best as possible" on phones): bottom bar, no rail
        val phone = LocalConfiguration.current.screenWidthDp < LAB_MIN_WIDTH_DP
        var findOpen by remember { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf(model.lastQuery()) }
        var railDest by rememberSaveable { mutableStateOf(RailDest.Sources) }
        // KMK (r110): bumped once the embedded New source delivered, so the next New starts on a fresh entry page
        var newSourceGeneration by rememberSaveable { mutableStateOf(0) }
        var showMenu by remember { mutableStateOf(false) }
        var confirmTemplateUpdate by remember { mutableStateOf(false) } // KMK (r100)
        // KMK (r101, r106): Source info as a sheet (⋮ Source info; the phone's Code header row too). r106: the tablet's
        // panel beside the code gave its place to the Test Run panel
        var infoSheet by rememberSaveable { mutableStateOf(false) }
        // KMK (r116): ⋮ › Connection (the source's bot-check session and its pace)
        var connectionSheet by rememberSaveable { mutableStateOf(false) }
        val pop: () -> Unit = { navigator.pop() }
        // r98: the app's Log (Diagnostics, Log tab) on top of the Lab; Back returns here with the editor untouched
        val openLogs: () -> Unit = { navigator.push(DiagnosticsScreen(DiagTab.LOG)) }
        val actions = rememberEditorActions(editor, editorState, onLeave = pop)
        // KMK (r105): ⋮ Convert again (a source made by Record and convert)
        val convertAgain = rememberScreenModel(tag = "convertAgain") { ConvertAgainScreenModel() }
        val convertState by convertAgain.state.collectAsState()

        // The New-source flow's result (C§5.2): taken once on arrival, and whenever one lands while this is on top.
        // Opening it in the editor goes through the unsaved-changes dialog: edits typed meanwhile aren't thrown away
        // (the generated source is saved either way; a cancelled open leaves it on Home)
        // (reads the editor's live state, not the composition's: the effects below keep their first lambda)
        val adopt: (GeneratedSource) -> Unit = { generated ->
            // KMK (r110): New is a rail place now: the arriving source shows on Sources (from the embedded New, or
            // from the builder pushed over it)
            railDest = RailDest.Sources
            // KMK (r110) fix: from any flow (Build by tapping pushed over New, a template, an import…): the next New
            // starts on a fresh entry page, not on the finished one
            newSourceGeneration++
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

        // KMK (r105): Convert again records the extension once more and saves a new version of this source; once
        // saved, the editor reopens it and Test Run runs on it
        val convertAgainNow: () -> Unit = {
            actions.askBeforeReplacing("Converting again") {
                val s = editor.state.value
                if (s.dirty && s.saved) editor.open(s.key)
                infoSheet = false
                convertAgain.start(s.key)
            }
        }
        LaunchedEffect(convertState.savedAt) {
            if (convertState.savedAt == 0L) return@LaunchedEffect
            val key = convertState.key
            // Only while that source is still the open one (the version is saved either way)
            if (key != null && model.state.value.key == key) {
                editor.open(key)
                model.loadSource(key)
                model.setTab(LabTab.Test)
                model.runTest(editor, query)
            }
            convertAgain.consumeSaved()
        }
        if (convertState.open) {
            ConvertAgainDialog(state = convertState, onStop = convertAgain::stop, onClose = convertAgain::close)
        }

        // KMK --> (r102) Edit by tapping (r103: the Edit button in Test Run, builder sources only): the builder opens
        // on this source's site with its picks and saves back to the same key. Unsaved edits are asked about first
        // (Save / Discard / Cancel; on Discard the editor drops them now, so the code the builder saves over is the
        // saved one)
        // KMK (r106 fix): [startTab] = the builder panel's tab to open on (Lint's "Learn the codes" → "filters")
        val openBuilder: (String?) -> Unit = { startTab ->
            actions.askBeforeReplacing("Editing by tapping") {
                val s = editor.state.value
                if (s.dirty && s.saved) editor.open(s.key)
                val lab = model.state.value.source?.lab
                val start = lab?.siteUrl?.trim()?.takeIf { it.startsWith("http") }
                    ?: s.baseUrl.trim().takeIf { it.startsWith("http") }
                infoSheet = false
                navigator.push(BuilderScreen(startUrl = start, anime = s.anime, editKey = s.key, startTab = startTab))
            }
        }
        val editByTapping: () -> Unit = { openBuilder(null) }
        val learnCodes: () -> Unit = { openBuilder("filters") }
        // The source was saved elsewhere (the builder's "Save to this source", a restore): the editor reopens it
        // unless it has unsaved edits, and the Lab re-reads History / Test / Lint
        val savedSources by editor.savedSources.collectAsState()
        val savedAt = savedSources.firstOrNull { it.key == editorState.key }?.updatedAt
        LaunchedEffect(savedAt) {
            if (savedAt != null && editor.reloadIfChangedOnDisk()) {
                model.state.value.key?.let { model.loadSource(it) }
            }
        }
        // KMK <--

        // KMK (r101): the phone's Source info, from the Code tab's header row (the code isn't pushed down any more)
        if (infoSheet && state.key != null) {
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

        // KMK (r116, mock XBot): ⋮ › Connection: what BotChecks knows about this source and "Ask less often"
        val connectionKey = state.key
        if (connectionSheet && connectionKey != null) {
            ConnectionSheet(
                sourceId = editor.sourceId(connectionKey),
                sourceName = editorState.name.ifBlank { state.source?.name.orEmpty() },
                onDismiss = { connectionSheet = false },
            )
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
                // KMK (r110): the rail place New (next to the rail / above the bottom bar), no pushed screen
                railDest = RailDest.New
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

        // KMK --> (r106) the code editor revamp: top bars, rail, Fix all, the last error, the Parts outline's dots
        var homeQuery by rememberSaveable { mutableStateOf("") }
        var homeSearchOpen by rememberSaveable { mutableStateOf(false) }
        var nightly by remember { mutableStateOf(model.retestNightly()) }
        var wifiOnly by remember { mutableStateOf(model.retestWifiOnly()) }
        var confirmFixAll by remember { mutableStateOf(false) }
        val lintFindings = (state.lint ?: state.source?.lastLint)?.findings.orEmpty()
        val lintCount = lintFindings.count { !it.ignored }
        val safeFixes = remember(lintFindings, state.rules) { model.safeFixes(lintFindings) }
        val lastError by rememberLastError(state.key, state.lastRun)
        val partStatuses = remember(state.lastRun) { LabDiff.partStatuses(state.lastRun) }
        // Every pane's "Go to line": the code at that line (the Code tab; the tablet's Test / Lint / History panes
        // fill the width now, so the code isn't beside them any more)
        val goToLine: (Int) -> Unit = { line ->
            model.revealLine(line)
            model.setTab(LabTab.Code)
        }
        val runTestNow: () -> Unit = {
            model.runTest(editor, query)
            if (model.state.value.tab != LabTab.Code) model.setTab(LabTab.Test)
        }
        // KMK (r110): the rail place Rules (Lint's / Explain's "Read the rule" still push RulesScreen(ruleId))
        val openRules: () -> Unit = { railDest = RailDest.Rules }
        if (confirmFixAll && safeFixes.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { confirmFixAll = false },
                title = { Text("Apply ${safeFixes.size} safe ${if (safeFixes.size == 1) "fix" else "fixes"}?") },
                text = {
                    Text(
                        "Each rule's replacement is put into the code: " +
                            safeFixes.joinToString { "${it.ruleId} (line ${it.line})" } +
                            ". The editor changes; nothing is saved until you tap Save.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmFixAll = false
                            model.applyAllFixes(editor, lintFindings)
                        },
                    ) { Text("Apply") }
                },
                dismissButton = { TextButton(onClick = { confirmFixAll = false }) { Text("Cancel") } },
            )
        }
        // KMK <--

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            // KMK (r115): a phone's bottom bar: the Lab's places, or the open source's tabs (back arrow: Sources)
            bottomBar = {
                if (phone) {
                    LabBottomBar(
                        sourceOpen = state.key != null && railDest == RailDest.Sources,
                        tab = state.tab,
                        dest = railDest,
                        lintCount = lintCount,
                        onSources = goHome,
                        onNewSource = newSource,
                        onRules = openRules,
                        onTemplates = { railDest = RailDest.Templates },
                        onTab = model::setTab,
                    )
                }
            },
        ) { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                // KMK (r115): one rail: the Lab's places, then (a source open) its name and tabs; a tab from New /
                // Rules / Templates goes back to the open source on that tab
                if (!phone) LabRail(
                    sourceName = if (state.key != null) {
                        editorState.name.ifBlank { state.source?.name.orEmpty() }.ifBlank { "Source" }
                    } else {
                        null
                    },
                    tab = state.tab,
                    dest = railDest,
                    lintCount = lintCount,
                    onSources = goHome,
                    onNewSource = newSource,
                    onRules = openRules,
                    onTemplates = { railDest = RailDest.Templates },
                    onTab = { t ->
                        railDest = RailDest.Sources
                        model.setTab(t)
                    },
                    onLogs = openLogs,
                )
                if (!phone) VerticalDivider()
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // KMK (r106): one top bar per place (Templates, Home, a source); the status strip is gone
                    // KMK (r110): New and Rules draw their own LabTopBar (their subtitles come from their state); no
                    // rail place has a back arrow (the rail / bottom bar switches places, the system Back goes to Sources)
                    when {
                        railDest == RailDest.New || railDest == RailDest.Rules -> Unit
                        railDest == RailDest.Templates -> LabTopBar(
                            title = "Templates",
                            subtitle = "the files the Lab writes sources from",
                            onBack = null,
                        ) {}
                        state.key == null -> LabTopBar(
                            title = "Source Lab",
                            subtitle = buildString {
                                append("${state.sources.size} ${if (state.sources.size == 1) "source" else "sources"}")
                                // KMK (r115, mock M3Home): how many sources need you (failing or never tested)
                                val needs = needsYouCount(state.sources)
                                if (needs > 0) append(" · $needs ${if (needs == 1) "needs" else "need"} you")
                                state.rules?.let { append(" · rules v${it.version}") }
                                append(" · last re-test ${state.retestLast}")
                            },
                            onBack = if (tablet) null else ({ back() }),
                        ) {
                            if (tablet) {
                                OutlinedTextField(
                                    value = homeQuery,
                                    onValueChange = { homeQuery = it },
                                    placeholder = { Text("Find a source") },
                                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(24.dp),
                                    modifier = Modifier.width(280.dp),
                                )
                                OutlinedButton(onClick = model::retestAll, enabled = state.retestProgress == null) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(if (state.retestProgress == null) "  Re-test all" else "  Re-testing…")
                                }
                                Button(onClick = newSource) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("  New source")
                                }
                            } else {
                                IconButton(onClick = { homeSearchOpen = !homeSearchOpen }) {
                                    Icon(Icons.Outlined.Search, contentDescription = "Find a source")
                                }
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
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
                                        openRules()
                                    }
                                    MenuItem("Re-test all", Icons.Outlined.Refresh) {
                                        showMenu = false
                                        model.retestAll()
                                    }
                                    MenuItem("Check all sources", Icons.AutoMirrored.Outlined.List) {
                                        showMenu = false
                                        model.checkAllSources()
                                    }
                                    HorizontalDivider()
                                    // r96's Re-test all card: the nightly schedule and Wi-Fi only
                                    DropdownMenuItem(
                                        text = { Text("Re-test nightly (03:00)") },
                                        trailingIcon = { Checkbox(checked = nightly, onCheckedChange = null) },
                                        onClick = {
                                            nightly = !nightly
                                            model.setRetestSchedule(nightly)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Nightly only on Wi-Fi") },
                                        enabled = nightly,
                                        trailingIcon = { Checkbox(checked = wifiOnly, onCheckedChange = null, enabled = nightly) },
                                        onClick = {
                                            wifiOnly = !wifiOnly
                                            model.setRetestWifiOnly(wifiOnly)
                                        },
                                    )
                                    HorizontalDivider()
                                    MenuItem("Logs", Icons.Outlined.Terminal) {
                                        showMenu = false
                                        openLogs()
                                    }
                                }
                            }
                        }
                        else -> LabTopBar(
                            title = if (editorState.saved) editorState.name.ifBlank { "Source" } else editorState.name.ifBlank { "New source" },
                            subtitle = buildString {
                                val source = state.source
                                append(if (editorState.anime) "Anime" else "Manga")
                                append(" · ").append(if (editorState.json) "JSON" else "JS")
                                if (source != null && source.version > 0) append(" · v${source.version}")
                                state.lastRun?.let { append(" · tested ${SourceLabScreenModel.agoText(it.startedAt)}") }
                                if (state.tab == LabTab.Lint) state.rules?.let { append(" · rules v${it.version}") }
                                if (!editorState.saved) append(" · not saved yet") else if (editorState.dirty) append(" · not saved")
                            },
                            // The rail's Sources (and the system Back) go home. KMK (r115): a phone's bottom bar
                            // holds the source's tabs, so its way back to Sources is this arrow
                            onBack = if (phone) goHome else null,
                            badge = runBadge(state.running, state.lastRun),
                            badgeInline = !tablet,
                        ) {
                            if (tablet && state.tab == LabTab.Lint && safeFixes.isNotEmpty()) {
                                OutlinedButton(onClick = { confirmFixAll = true }) {
                                    Text("Fix all ${safeFixes.size} safe ${if (safeFixes.size == 1) "fix" else "fixes"}")
                                }
                            }
                            if (tablet && state.tab != LabTab.Test && railDest == RailDest.Sources) {
                                if (state.running) {
                                    OutlinedButton(onClick = model::cancelTest) {
                                        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("  Stop")
                                    }
                                } else {
                                    OutlinedButton(onClick = runTestNow, enabled = editorState.running == null) {
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("  Test")
                                    }
                                }
                            }
                            // KMK (r106): the phone's Test is an icon
                            // KMK (r115): a phone's top bar stays compact (name, pill, Save, ⋮): Test is in the Test tab
                            if (!tablet && !phone && railDest == RailDest.Sources) {
                                if (state.running) {
                                    IconButton(onClick = model::cancelTest) {
                                        Icon(Icons.Outlined.Stop, contentDescription = "Stop the test")
                                    }
                                } else {
                                    IconButton(onClick = runTestNow, enabled = editorState.running == null) {
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Test")
                                    }
                                }
                            }
                            SaveSplitButton(
                                state = state,
                                editorState = editorState,
                                onSave = { inPlace -> editor.save(inPlace) },
                                compact = !tablet,
                                iconOnly = phone,
                            )
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    // KMK (r106): Source info moved here from beside the code (the Test Run panel is there now)
                                    MenuItem("Source info", Icons.Outlined.Info) {
                                        showMenu = false
                                        infoSheet = true
                                    }
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
                                    // KMK (r105): Convert again, on a saved source made by Record and convert
                                    val lab = state.source?.lab
                                    if (editorState.saved && convertAgain.offered(lab?.fromExtension, lab?.templateId)) {
                                        MenuItem("Convert again", Icons.Outlined.Refresh) {
                                            showMenu = false
                                            convertAgainNow()
                                        }
                                    }
                                    // KMK (r116): the source's bot-check session and pace
                                    MenuItem("Connection", Icons.Outlined.Public) {
                                        showMenu = false
                                        connectionSheet = true
                                    }
                                    // KMK (r115): the page's Raw / Rendered / Requests, for this source's site
                                    MenuItem("Page source", Icons.Outlined.Code) {
                                        showMenu = false
                                        val s = editor.state.value
                                        val site = state.source?.lab?.siteUrl?.trim()?.takeIf { it.startsWith("http") }
                                            ?: s.baseUrl.trim().takeIf { it.startsWith("http") }
                                        if (site != null) {
                                            navigator.push(eu.kanade.tachiyomi.ui.sourcelab.pagesource.PageSourceScreen(site, s.key))
                                        }
                                    }
                                    // KMK (r102): Duplicate removed from the menu (the owner's request)
                                    if (editorState.saved) {
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
                    }
                    // KMK (r110): New and Rules draw their own divider, under their own top bar
                    val ownTopBar = railDest == RailDest.New || railDest == RailDest.Rules
                    if (!ownTopBar && (editorState.running != null || state.running || state.retestProgress != null || state.reportBuilding)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (!ownTopBar) {
                        HorizontalDivider()
                    }
                    // KMK (r100): the source's template has a newer version (Lint has its own card for it)
                    val templateOffer = state.templateOffer
                    if (templateOffer != null && state.key != null && railDest == RailDest.Sources && state.tab != LabTab.Lint) {
                        TemplateUpdateBar(
                            offer = templateOffer,
                            busy = state.updatingTemplate,
                            onUpdate = { confirmTemplateUpdate = true },
                        )
                    }
                    when {
                        // KMK (r110): New and Rules next to the rail like Sources and Templates. New's model belongs to
                        // this screen (tagged by generation); Rules shares the Lab's model (its snackbar, its rules)
                        railDest == RailDest.New -> LabNewSourcePane(
                            owner = this@SourceLabScreen,
                            generation = newSourceGeneration,
                            onDelivered = { newSourceGeneration++ },
                            modifier = Modifier.fillMaxSize(),
                        )
                        railDest == RailDest.Rules -> RulesContent(
                            model = model,
                            ruleId = null,
                            onBack = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                        railDest == RailDest.Templates -> TemplatesPane(modifier = Modifier.fillMaxSize())
                        state.key == null -> Column(modifier = Modifier.fillMaxSize()) {
                            if (!tablet && homeSearchOpen) {
                                OutlinedTextField(
                                    value = homeQuery,
                                    onValueChange = { homeQuery = it },
                                    placeholder = { Text("Find a source") },
                                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                                )
                            }
                            HomePane(
                                state = state,
                                query = if (tablet || homeSearchOpen) homeQuery else "",
                                onOpen = openSource,
                                onTemplates = { railDest = RailDest.Templates },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // KMK (r115): the rail's layout on every width (the phone branch with the Parts chips and the
                        // Test Run drawer is gone with the phone layout)
                        else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val room = maxWidth
                            when (state.tab) {
                                // KMK (r106, mock ECode; r115 M3Code): Parts card (+ Last test) · code (+ the lint
                                // finding of the part shown, with its fix) · Test Run panel (each side column only
                                // when the room allows: the code keeps at least ~400 dp)
                                // KMK (r115): a phone: Parts chips · code · the finding bar, one column
                                LabTab.Code -> if (phone) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        PartsChipRow(
                                            parts = state.explain,
                                            statuses = partStatuses,
                                            selected = state.selectedPart,
                                            onPart = model::showPart,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                        CodeTab(
                                            model = editor,
                                            state = editorState,
                                            findOpen = findOpen,
                                            onFindOpenChange = { findOpen = it },
                                            highlight = state.highlight,
                                            revealLine = state.revealLine,
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            errorLine = lastError.line,
                                            errorCount = lastError.count,
                                        )
                                        CodeFindingBar(
                                            state = state,
                                            findings = lintFindings,
                                            onApply = { f -> model.applyFix(editor, f) },
                                            onGoToLine = goToLine,
                                        )
                                    }
                                } else Row(modifier = Modifier.fillMaxSize()) {
                                    if (room >= 760.dp) {
                                        PartsColumn(
                                            state = state,
                                            statuses = partStatuses,
                                            onPart = model::showPart,
                                            modifier = Modifier.width(210.dp).fillMaxHeight(),
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                        CodeTab(
                                            model = editor,
                                            state = editorState,
                                            findOpen = findOpen,
                                            onFindOpenChange = { findOpen = it },
                                            highlight = state.highlight,
                                            revealLine = state.revealLine,
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            errorLine = lastError.line,
                                            errorCount = lastError.count,
                                        )
                                        CodeFindingBar(
                                            state = state,
                                            findings = lintFindings,
                                            onApply = { f -> model.applyFix(editor, f) },
                                            onGoToLine = goToLine,
                                        )
                                    }
                                    if (room >= 980.dp) {
                                        VerticalDivider()
                                        TestRunSide(
                                            state = state,
                                            model = model,
                                            editor = editor,
                                            editorState = editorState,
                                            query = query,
                                            onGoToLine = goToLine,
                                            modifier = Modifier.width(360.dp).fillMaxHeight(),
                                        )
                                    }
                                }
                                // r101: Explain keeps the code beside it (a tapped part lights up its lines there);
                                // the code gets the room minus 380 dp, 360–700 dp. KMK (r115): under 760 dp of room
                                // Explain is alone and a tapped part opens the Code tab
                                LabTab.Explain -> if (room >= 760.dp) {
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        CodeTab(
                                            model = editor,
                                            state = editorState,
                                            findOpen = findOpen,
                                            onFindOpenChange = { findOpen = it },
                                            highlight = state.highlight,
                                            revealLine = state.revealLine,
                                            modifier = Modifier.width(maxOf(360.dp, minOf(700.dp, room - 380.dp))).fillMaxHeight(),
                                            errorLine = lastError.line,
                                            errorCount = lastError.count,
                                        )
                                        VerticalDivider()
                                        ExplainPane(
                                            state = state,
                                            model = model,
                                            editorState = editorState,
                                            codeBeside = true,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                        )
                                    }
                                } else {
                                    ExplainPane(
                                        state = state,
                                        model = model,
                                        editorState = editorState,
                                        codeBeside = false,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                else -> SourcePane(
                                    tab = state.tab,
                                    state = state,
                                    model = model,
                                    editor = editor,
                                    editorState = editorState,
                                    query = query,
                                    onQuery = { query = it },
                                    askBeforeReplacing = actions.askBeforeReplacing,
                                    onEditByTapping = editByTapping,
                                    onLearnCodes = learnCodes,
                                    onGoToLine = goToLine,
                                    onUpdateTemplate = { confirmTemplateUpdate = true },
                                    onFixAll = { confirmFixAll = true },
                                    fixAllInBar = tablet,
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

/**
 * What the tablet rail (or the phone's bottom bar) shows in the main area besides a source.
 * KMK (r110): New and Rules joined (they were pushed screens with a back arrow)
 */
internal enum class RailDest { Sources, New, Rules, Templates }

/** The source tabs other than Code (Code is [CodeTab], with the Parts outline and Test Run panel on a tablet) */
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
    onEditByTapping: () -> Unit,
    onLearnCodes: () -> Unit,
    onGoToLine: (Int) -> Unit,
    onUpdateTemplate: () -> Unit,
    onFixAll: () -> Unit,
    fixAllInBar: Boolean,
    modifier: Modifier,
) {
    when (tab) {
        LabTab.Code, LabTab.Explain -> ExplainPane(
            state = state,
            model = model,
            editorState = editorState,
            codeBeside = false,
            modifier = modifier,
        )
        LabTab.Test -> TestPane(
            state = state,
            model = model,
            editor = editor,
            editorState = editorState,
            query = query,
            onQuery = onQuery,
            onGoToLine = onGoToLine,
            modifier = modifier,
            onEditByTapping = onEditByTapping,
        )
        LabTab.Lint -> LintPane(
            state = state,
            model = model,
            editor = editor,
            editorState = editorState,
            onGoToLine = onGoToLine,
            // "Learn the codes" on filter-value-is-label: the builder on this source (Edit by tapping)
            onLearnCodes = onLearnCodes,
            onUpdateTemplate = onUpdateTemplate,
            onFixAll = onFixAll,
            fixAllInBar = fixAllInBar,
            modifier = modifier,
        )
        LabTab.History -> HistoryPane(
            state = state,
            model = model,
            editor = editor,
            editorState = editorState,
            askBeforeReplacing = askBeforeReplacing,
            modifier = modifier,
        )
        LabTab.Report -> ReportPane(state = state, model = model, modifier = modifier)
    }
}

/**
 * KMK (r102, r103): Save as a split button. r103: the main action always saves to the version open now ("Save to v3":
 * overwritten in place, no new version), also when it is a restored older version or the generated-from one; no
 * remembered choice. ▾ = "Save as new version (vM)" (the next number ever given). The open version being a Baseline
 * (the pre-Lab original code, never overwritten yet) asks first ([History.overwriteConfirm]): Overwrite / Save as new.
 * A source with no version yet (not saved, or no history) has a plain Save (a new version).
 */
@Composable
private fun SaveSplitButton(
    state: SourceLabScreenModel.State,
    editorState: SourceEditorScreenModel.State,
    onSave: (inPlace: Boolean) -> Unit,
    // KMK (r106): the phone's top bar: the save icon alone (its description and ▾ name the version)
    compact: Boolean = false,
    // KMK (r115): the phone's compact top bar: one Save icon; with a version open it opens the two choices
    iconOnly: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val history = state.history
    val current = History.currentOf(history)
    val next = maxOf(history.lastN, history.versions.lastOrNull()?.n ?: 0) + 1
    val enabled = editorState.running == null
    if (!editorState.saved || current == 0 || History.updateBlockedReason(history) != null) {
        if (iconOnly) {
            IconButton(onClick = { onSave(false) }, enabled = enabled) {
                Icon(Icons.Outlined.Save, contentDescription = "Save")
            }
            return
        }
        FilledTonalButton(
            onClick = { onSave(false) },
            enabled = enabled,
            contentPadding = if (compact) PaddingValues(horizontal = 12.dp) else ButtonDefaults.ContentPadding,
        ) {
            Icon(Icons.Outlined.Save, contentDescription = if (compact) "Save" else null, modifier = Modifier.width(18.dp))
            if (!compact) Text("  Save")
        }
        return
    }
    val question = History.overwriteConfirm(history)
    if (iconOnly) {
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                Icon(Icons.Outlined.Save, contentDescription = "Save")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Save to v$current") },
                    leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        if (question != null) confirm = question else onSave(true)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Save as new version (v$next)") },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onSave(false)
                    },
                )
            }
        }
    } else Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = { if (question != null) confirm = question else onSave(true) },
                enabled = enabled,
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 12.dp),
            ) {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = if (compact) "Save to v$current" else null,
                    modifier = Modifier.width(18.dp),
                )
                Text(if (compact) " v$current" else "  Save to v$current", maxLines = 1)
            }
            Spacer(Modifier.width(2.dp))
            FilledTonalButton(
                onClick = { menuOpen = true },
                enabled = enabled,
                shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(40.dp),
            ) {
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Save options")
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Save as new version (v$next)")
                        Text(
                            "Keeps v$current as it is and adds v$next",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onSave(false)
                },
            )
        }
    }
    confirm?.let { text ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Overwrite v$current?") },
            text = { Text(text) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        onSave(true)
                    },
                ) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        onSave(false)
                    },
                ) { Text("Save as new") }
            },
        )
    }
}
