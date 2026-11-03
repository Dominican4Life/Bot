package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen as VoyagerScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.jssource.lab.ApkImport
import eu.kanade.tachiyomi.jssource.lab.DetectionStep
import eu.kanade.tachiyomi.jssource.lab.EngineGuess
import eu.kanade.tachiyomi.jssource.lab.ExtensionRecorder
import eu.kanade.tachiyomi.jssource.lab.LabFlowScreen
import eu.kanade.tachiyomi.jssource.lab.LabTemplate
import eu.kanade.tachiyomi.jssource.lab.RecordedConverter
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.sitecheck.SiteCheck
import eu.kanade.tachiyomi.ui.sourcelab.LabStatusStrip
import eu.kanade.tachiyomi.ui.sourcelab.LabTopBar
import eu.kanade.tachiyomi.ui.sourcelab.MonoCard
import eu.kanade.tachiyomi.ui.sourcelab.StripColor
import eu.kanade.tachiyomi.ui.sourcelab.pagesource.PageSourceScreen
import eu.kanade.tachiyomi.ui.sourcelab.tint
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.openInBrowser
import tachiyomi.presentation.core.components.material.Scaffold

/** How Half A pushes Half B's entry screen without naming the class (CONTRACT-LAB §5.4) */
internal object LabFlowScreenFactory {
    fun newSource(kindHint: Boolean?): VoyagerScreen = NewSourceScreen(kindHint)
}

/**
 * KMK (r96, Source Lab): "New source" (D§2.2, stills 10 and 02): paste a site address, let the Lab fetch three
 * pages and compare them with the engines it knows, then write the source from the matching template. Below,
 * the other ways in: a template, Build by tapping, Import a file, Duplicate a source, Import from extension.
 * The finished source goes to Half A through LabHandoff; this screen then pops itself.
 *
 * KMK (r115, mockups M3New / ESiteCheck): one address box with one main action, "Check the site" ([SiteCheckPane]:
 * raw vs rendered, data requests, next page, embedded data, the engine; then the plan), and the four ways to start
 * side by side (Site check, Build by tapping, From a template, Record and convert), then Import from an extension or
 * a file. The engine's markers (the r96 Detected page) open from the plan's "See the markers".
 */
internal class NewSourceScreen(private val kindHint: Boolean? = null) : Screen(), LabFlowScreen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { NewSourceScreenModel(kindHint) }
        val state by model.state.collectAsState()

        // KMK (r110): the pushed form (the Lab itself shows the embedded one, [LabNewSourcePane]); the body, Back and
        // the dialogs are [NewSourceContent], shared by both
        Scaffold(
            topBar = {
                AppBar(
                    title = "New source",
                    subtitle = newSourceSubtitle(state),
                    navigateUp = {
                        // KMK (r115): the engine's markers go back to the Site check first
                        if (state.phase != NewSourceScreenModel.Phase.Entry) model.back() else navigator.pop()
                        Unit
                    },
                )
            },
        ) { padding ->
            NewSourceContent(
                model = model,
                embedded = false,
                onDelivered = { navigator.popUntil { it !is LabFlowScreen } },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

/**
 * KMK (r110): "New source" inside the Source Lab, next to its rail / above its bottom bar (the rail's New is a place,
 * not a pushed screen). The model belongs to [owner] (the Lab screen) under a tag with [generation] in it: the Lab
 * bumps the generation once a source was delivered, so the next New starts on a fresh entry page, while switching to
 * another rail place and back keeps what was typed. The older models are removed with the Lab screen.
 * [onDelivered] is called once the hand-off happened (the Lab opens the source through LabHandoff as before).
 */
@Composable
internal fun LabNewSourcePane(
    owner: VoyagerScreen,
    generation: Int,
    onDelivered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = owner.rememberScreenModel(tag = "labNewSource:$generation") { NewSourceScreenModel(null) }
    NewSourceContent(model = model, embedded = true, onDelivered = onDelivered, modifier = modifier)
}

/** KMK (r110): the top bar's subtitle, for both forms */
private fun newSourceSubtitle(state: NewSourceScreenModel.State): String {
    val host = state.url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
    return when (state.phase) {
        NewSourceScreenModel.Phase.Entry -> "Paste a site, or pick how you want to start"
        NewSourceScreenModel.Phase.Detecting -> "$host · detecting the engine"
        NewSourceScreenModel.Phase.Detected -> state.best?.let { "$host · ${it.engineName} ${it.confidence} %" } ?: "$host · no engine matched"
        // KMK (r115): the Site check
        NewSourceScreenModel.Phase.Checking -> "$host · Site check (about 10 s)"
        NewSourceScreenModel.Phase.Checked -> "$host · Site check · " + (state.siteCheck?.best?.title?.lowercase() ?: "no plan fits")
    }
}

/**
 * KMK (r110): the New-source screen's body (strip, entry / detection, the how-it-works card) and its dialogs.
 * [embedded]: inside the Lab, which has no back arrow for its rail places: the Lab's top bar ([LabTopBar]) is drawn
 * here, with "Start over" in place of the arrow while a detection is shown (the system Back does the same).
 */
@Composable
private fun NewSourceContent(
    model: NewSourceScreenModel,
    embedded: Boolean,
    onDelivered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val state by model.state.collectAsState()
    val tablet = isTabletUi()

    LaunchedEffect(state.delivered) {
        if (state.delivered) onDelivered()
    }
    // The system Back does what the bar's arrow does: a detection result goes back to the entry page first
    // (KMK r115: the engine's markers back to the Site check)
    BackHandler(enabled = state.phase != NewSourceScreenModel.Phase.Entry) { model.back() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.importFile(uri)
    }
    val pickFile: (String) -> Unit = { mime -> filePicker.launch(mime) }

    val host = state.url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')

    Column(modifier = modifier) {
        if (embedded) {
            LabTopBar(title = "New source", subtitle = newSourceSubtitle(state), onBack = null) {
                if (state.phase != NewSourceScreenModel.Phase.Entry) {
                    OutlinedButton(onClick = model::backToEntry) { Text("Start over") }
                }
            }
            HorizontalDivider()
        }
        // KMK (r115): the strip only on the engine pages (the entry and the Site check follow mockups M3New / ESiteCheck)
        val detectionPhase = state.phase == NewSourceScreenModel.Phase.Detecting || state.phase == NewSourceScreenModel.Phase.Detected
        if (detectionPhase) {
            LabStatusStrip(
                head = if (state.phase == NewSourceScreenModel.Phase.Detecting) "Detecting" else "Detected",
                color = when {
                    state.phase == NewSourceScreenModel.Phase.Detecting -> StripColor.Blue
                    state.result?.writeOffered == true -> StripColor.Green
                    else -> StripColor.Amber
                },
                figures = listOf(
                    "Requests" to state.requests.toString(),
                    "Engine" to (state.best?.engineName?.substringBefore(" (") ?: "—"),
                    "Kind" to when (state.best?.kind ?: state.kindHint?.let { if (it) "anime" else "manga" }) {
                        "anime" -> "Anime"
                        "manga" -> "Manga"
                        else -> "auto"
                    },
                ),
            )
        }
        if (state.busy != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Text(
                text = state.busy.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val anime = state.kindHint == true || state.best?.kind == "anime"
        val siteUrl = state.siteCheck?.url ?: state.url.trim().ifBlank { null }?.let { SiteCheck.address(it) }
        when (state.phase) {
            NewSourceScreenModel.Phase.Entry -> EntryColumn(
                state = state,
                model = model,
                wide = tablet,
                onBuild = { navigator.push(BuilderScreen(startUrl = state.url.trim().ifBlank { null }, anime = state.kindHint == true)) },
                onImport = { pickFile("*/*") },
                modifier = Modifier.fillMaxSize(),
            )
            NewSourceScreenModel.Phase.Checking, NewSourceScreenModel.Phase.Checked -> SiteCheckPane(
                result = state.siteCheck,
                wide = tablet,
                onPlan = { plan ->
                    when (plan.kind) {
                        SiteCheck.PlanKind.Template -> model.writeDetected()
                        SiteCheck.PlanKind.Html -> navigator.push(BuilderScreen(startUrl = siteUrl, anime = anime))
                        SiteCheck.PlanKind.Browser -> navigator.push(
                            BuilderScreen(startUrl = siteUrl, anime = anime, siteNote = browserNote(state.siteCheck)),
                        )
                        SiteCheck.PlanKind.Data -> if (siteUrl != null) navigator.push(PageSourceScreen(url = siteUrl, sourceKey = null))
                    }
                },
                onMarkers = if (state.result != null) model::showDetection else null,
                onTemplate = { model.openPicker(NewSourceScreenModel.Picker.Template) },
                onRecord = model::openRecord,
                onPageSource = { if (siteUrl != null) navigator.push(PageSourceScreen(url = siteUrl, sourceKey = null)) },
                onAgain = model::checkSite,
                onSkip = model::backToEntry,
                modifier = Modifier.fillMaxSize(),
            )
            NewSourceScreenModel.Phase.Detecting, NewSourceScreenModel.Phase.Detected ->
                MainColumn(state, model, modifier = Modifier.fillMaxSize())
        }
    }

    // Pickers and cards over the screen
    when (state.picker) {
        NewSourceScreenModel.Picker.Template -> TemplatePickerDialog(
            // KMK (r98): the recorded-* templates are only written by Record and convert
            templates = state.templates.filter { !it.id.startsWith("recorded-") },
            onPick = model::writeTemplate,
            onDismiss = model::closePicker,
        )
        NewSourceScreenModel.Picker.Duplicate -> ListPickerDialog(
            title = "Duplicate a source",
            empty = "No saved sources yet",
            items = state.savedSources.map { it.first to it.second },
            onPick = model::duplicate,
            onDismiss = model::closePicker,
        )
        NewSourceScreenModel.Picker.Extension -> {
            val import = state.extensionImport
            val recordSteps = state.recordSteps
            if (recordSteps != null) {
                // KMK (r98): Record and convert
                RecordDialog(
                    row = state.extensionRow,
                    steps = recordSteps,
                    running = state.recording,
                    result = state.recorded,
                    error = state.recordError,
                    busy = state.busy,
                    onStop = model::stopRecording,
                    onWrite = model::confirmRecorded,
                    onBack = model::closeRecording,
                )
            } else if (import != null) {
                ExtensionImportDialog(
                    result = import,
                    host = state.extensionRow?.baseUrl.orEmpty(),
                    onWrite = model::confirmExtensionImport,
                    onRecord = model::startRecording,
                    onGitHub = {
                        val q = import.generated.lab.fromExtension.orEmpty().substringAfterLast('.')
                        context.openInBrowser("https://github.com/keiyoushi/extensions-source/search?q=$q")
                    },
                    onDismiss = model::closePicker,
                )
            } else {
                ExtensionPickerDialog(
                    rows = state.extensions,
                    busy = state.busy,
                    error = state.error,
                    onPick = model::importExtension,
                    onDismiss = model::closePicker,
                )
            }
        }
        NewSourceScreenModel.Picker.None -> Unit
    }
    val error = state.error
    if (error != null && state.picker == NewSourceScreenModel.Picker.None) {
        AlertDialog(
            onDismissRequest = model::clearError,
            title = { Text("Source Lab") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = model::clearError) { Text("OK") } },
        )
    }
    val botCheck = state.botCheckUrl
    if (botCheck != null) {
        // KMK (r115): the Site check's result stays behind the card (Check again after passing the check)
        AlertDialog(
            onDismissRequest = model::dismissBotCheck,
            title = { Text("The site wants a bot check") },
            text = { Text("Open it in WebView, pass the check, then check again. The check counts for the app's connection too.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.dismissBotCheck()
                        navigator.push(WebViewScreen(url = botCheck, initialTitle = host, sourceId = null))
                    },
                ) { Text("Open in WebView") }
            },
            dismissButton = { TextButton(onClick = model::dismissBotCheck) { Text("Later") } },
        )
    }
}

// ---------- KMK (r115, mockup M3New): the entry page ----------

/** The note the builder shows once when the Site check's plan is the hidden browser */
private fun browserNote(result: SiteCheck.Result?): String {
    val raw = result?.rawCards ?: 0
    val rendered = result?.renderedCards ?: 0
    return "Site check: the raw page has $raw title links, the browser shows $rendered (the list is built by the site's scripts). " +
        "Tap as usual; then look at ⋮ › Page source: a source reading the plain page gets only what Raw has."
}

/**
 * The address box with its one main action (Check the site) and the kind, then the four ways to start side by side
 * (Site check recommended, Build by tapping, From a template, Record and convert), then Import from an extension / a
 * file. [wide]: the address row on one line and the four tiles in one row; a phone stacks them.
 */
@Composable
private fun EntryColumn(
    state: NewSourceScreenModel.State,
    model: NewSourceScreenModel,
    wide: Boolean,
    onBuild: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val field: @Composable (Modifier) -> Unit = { m ->
            OutlinedTextField(
                value = state.url,
                onValueChange = model::setUrl,
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                placeholder = { Text("https://example.com") },
                shape = RoundedCornerShape(28.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = m,
            )
        }
        val kinds: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = state.kindHint == null, onClick = { model.setKind(null) }, label = { Text("Let the Lab decide") })
                FilterChip(selected = state.kindHint == false, onClick = { model.setKind(false) }, label = { Text("Manga") })
                FilterChip(selected = state.kindHint == true, onClick = { model.setKind(true) }, label = { Text("Anime") })
            }
        }
        val checkButton: @Composable (Modifier) -> Unit = { m ->
            Button(onClick = model::checkSite, enabled = state.busy == null, modifier = m.heightIn(min = 48.dp)) {
                Text("Check the site")
            }
        }
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                field(Modifier.weight(1f))
                kinds()
                checkButton(Modifier)
            }
        } else {
            field(Modifier.fillMaxWidth())
            kinds()
            checkButton(Modifier.fillMaxWidth())
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                (0 until 4).forEach { i -> StartTile(i, state, model, wide, onBuild, Modifier.weight(1f).fillMaxHeight()) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ImportRows(model, onImport, Modifier.weight(1f))
            }
        } else {
            (0 until 4).forEach { i -> StartTile(i, state, model, wide, onBuild, Modifier.fillMaxWidth()) }
            ImportRows(model, onImport, Modifier.fillMaxWidth())
        }
    }
}

/** One of the four ways to start ([index] 0 Site check, 1 Build by tapping, 2 From a template, 3 Record and convert) */
@Composable
private fun StartTile(
    index: Int,
    state: NewSourceScreenModel.State,
    model: NewSourceScreenModel,
    wide: Boolean,
    onBuild: () -> Unit,
    m: Modifier,
) {
    val templates = state.templates.count { !it.id.startsWith("recorded-") }
    val engineNames = state.templates
        .filter { !it.id.startsWith("blank-") && !it.id.startsWith("generic") && !it.id.startsWith("recorded-") }
        .map { it.name.substringBefore(" (") }
    when (index) {
        0 -> StartCard(
            icon = Icons.Outlined.AutoFixHigh,
            title = "Site check",
            badge = "Recommended",
            body = "The Lab opens the site, compares what a source receives with what you see, finds its data requests " +
                "and next page, and suggests the best way to read it.",
            chips = listOf("~10 s", "fills what it can"),
            steps = listOf("Paste the address", "Wait ~10 s", "Fix what's left (1–2 things)"),
            action = "Check the site",
            primary = true,
            onClick = model::checkSite,
            stretch = wide,
            modifier = m,
        )
        1 -> StartCard(
            icon = Icons.Outlined.TouchApp,
            title = "Build by tapping",
            badge = null,
            body = "Open the site and tap the list, a title, an episode and the player. A checklist tests every tap on 3 titles.",
            chips = listOf("no code", "HTML sites"),
            steps = listOf("Tap a card on the list", "Tap the title, cover, episodes", "Tap the player"),
            action = "Start",
            primary = false,
            onClick = onBuild,
            stretch = wide,
            modifier = m,
        )
        2 -> StartCard(
            icon = Icons.Outlined.Description,
            title = "From a template",
            badge = null,
            body = "Start from one of $templates templates" +
                (if (engineNames.isNotEmpty()) " (generic list, ${engineNames.take(2).joinToString(", ")}…)" else "") +
                " and fill in the blanks.",
            chips = listOf("$templates templates", "code"),
            steps = listOf("Pick a template", "Fill the blanks it asks for", "Run the test"),
            action = "Start",
            primary = false,
            onClick = { model.openPicker(NewSourceScreenModel.Picker.Template) },
            stretch = wide,
            modifier = m,
        )
        else -> StartCard(
            icon = Icons.Outlined.FiberManualRecord,
            title = "Record and convert",
            badge = null,
            body = "Use an installed extension while the Lab records its requests, then turn them into a JS source.",
            chips = listOf("from an APK", "${ExtensionRecorder.STEP_TIMEOUT_MS / 1000} s a step"),
            steps = listOf("Pick the extension", "Let it run its short steps", "Convert"),
            action = "Start",
            primary = false,
            onClick = model::openRecord,
            stretch = wide,
            modifier = m,
        )
    }
}

@Composable
private fun StartCard(
    icon: ImageVector,
    title: String,
    badge: String?,
    body: String,
    chips: List<String>,
    steps: List<String>,
    action: String,
    primary: Boolean,
    onClick: () -> Unit,
    /** the four tiles side by side: the card fills the row's height and its button sits at the bottom */
    stretch: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val green = StripColor.Green.tint()
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (primary) colors.primaryContainer.copy(alpha = 0.22f) else colors.surfaceContainer,
        border = if (primary) BorderStroke(1.dp, colors.primary.copy(alpha = 0.6f)) else null,
        modifier = modifier,
    ) {
        Column(modifier = (if (stretch) Modifier.fillMaxHeight() else Modifier).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = colors.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = green.copy(alpha = 0.16f)) {
                        Text(badge, fontSize = 12.sp, color = green, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                chips.forEach { chip ->
                    Surface(shape = RoundedCornerShape(8.dp), color = if (primary) green.copy(alpha = 0.14f) else colors.surfaceContainerHighest) {
                        Text(
                            chip,
                            fontSize = 12.sp,
                            color = if (primary) green else colors.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Text(
                "YOU'LL DO",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
            steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(20.dp).clip(CircleShape).background(colors.surfaceContainerHighest),
                    ) {
                        Text((index + 1).toString(), fontSize = 11.sp, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(step, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (stretch) Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(14.dp))
            if (primary) {
                Button(onClick = onClick) { Text(action) }
            } else {
                OutlinedButton(onClick = onClick) { Text(action) }
            }
        }
    }
}

/** Import from an extension · Import a file (each gets [modifier]: side by side in a row, or stacked) */
@Composable
private fun ImportRows(model: NewSourceScreenModel, onImport: () -> Unit, modifier: Modifier) {
    ImportRow(
        icon = Icons.Outlined.Extension,
        title = "Import from an extension",
        subtitle = "Read an installed extension's web source",
        action = "Pick",
        onClick = { model.openPicker(NewSourceScreenModel.Picker.Extension) },
        modifier = modifier,
    )
    ImportRow(
        icon = Icons.Outlined.FileUpload,
        title = "Import a file",
        subtitle = ".js or .json from a friend or a backup",
        action = "Choose",
        onClick = onImport,
        modifier = modifier,
    )
}

@Composable
private fun ImportRow(icon: ImageVector, title: String, subtitle: String, action: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

// ---------- the engine's page (r96; KMK r115: opened from the Site check's plan, "See the markers") ----------

@Composable
private fun MainColumn(
    state: NewSourceScreenModel.State,
    model: NewSourceScreenModel,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.currentOrThrow
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "steps") { StepsCard(state.steps) }
        val result = state.result
        if (result != null) {
            val best = result.best
            item(key = "fingerprint") { FingerprintCard(best, result.others) }
            if (best != null) {
                item(key = "write") { WhatTheLabWillWriteCard(best, state.templateForBest) }
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = model::writeDetected,
                        enabled = result.writeOffered && state.busy == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Write the source") }
                    OutlinedButton(
                        onClick = { navigator.push(BuilderScreen(result.siteUrl, (result.best?.kind ?: "") == "anime" || state.kindHint == true)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Build by tapping instead") }
                }
                if (best != null && !result.writeOffered) {
                    Text(
                        text = "Below 60 % the Lab doesn't write from a template: the markers are shown so you can see why, and Build by tapping is one tap away.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            item(key = "running") {
                Text(
                    text = "Fetching through the app's connection…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LabCard(title: String?, trailing: String? = null, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (trailing != null) {
                        Text(text = trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            content()
        }
    }
}

// ---------- detecting ----------

@Composable
private fun StatusDot(status: StepStatus) {
    val color = when (status) {
        StepStatus.Green -> StripColor.Green
        StepStatus.Amber -> StripColor.Amber
        StepStatus.Red -> StripColor.Red
        StepStatus.Running -> StripColor.Blue
        StepStatus.Skipped -> StripColor.Neutral
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp).clip(CircleShape).background(color.tint().copy(alpha = 0.18f)),
    ) {
        if (status == StepStatus.Running) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = color.tint(), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StepsCard(steps: List<DetectionStep>) {
    LabCard(title = null) {
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                StatusDot(step.status)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(step.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        val figures = buildList<String> {
                            if (step.ms > 0) add("${step.ms} ms")
                            if (step.bytes > 0) add(if (step.bytes >= 1024) "${step.bytes / 1024} KB" else "${step.bytes} B")
                        }
                        if (figures.isNotEmpty()) {
                            Text(figures.joinToString(" · "), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val note = step.note ?: step.url
                    if (note != null) {
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (step.status == StepStatus.Red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (index < steps.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun FingerprintCard(best: EngineGuess?, others: List<EngineGuess>) {
    LabCard(title = null) {
        if (best == null) {
            Text("No engine matched", style = MaterialTheme.typography.headlineSmall)
            Text(
                "None of the markers in fingerprints.json were found on the pages. Build by tapping writes the source from what you point at.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            val matched = best.markers.count { it.matched }
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(best.engineName.substringBefore(" ("), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    val family = best.engineName.substringAfter(" (", "").removeSuffix(")")
                    if (family.isNotBlank()) {
                        Text(family, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "$matched of ${best.markers.size} markers matched · confidence from marker weights, not a guess",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    "${best.confidence} %",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = (if (best.confidence >= 60) StripColor.Green else StripColor.Amber).tint(),
                )
            }
            Spacer(Modifier.height(8.dp))
            best.markers.forEach { marker ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    if (marker.matched) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = StripColor.Green.tint(), modifier = Modifier.size(20.dp))
                    } else {
                        Box(modifier = Modifier.padding(4.dp).size(12.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Text(marker.id, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("weight ${marker.weight}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(marker.evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
        }
        if (others.isNotEmpty()) {
            Text("Other candidates", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            others.take(5).forEach { other ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(other.engineName.substringBefore(" ("), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = (other.confidence / 100f).coerceIn(0.02f, 1f))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("${other.confidence} %", fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                }
            }
        }
    }
}

@Composable
private fun WhatTheLabWillWriteCard(best: EngineGuess, template: LabTemplate?) {
    LabCard(title = "What the Lab will write", trailing = template?.let { "${it.name} v${it.version}" } ?: "template missing") {
        val rows = buildList<Pair<String, String>> {
            add("kind" to best.kind)
            add("template" to (template?.let { "${it.id}.${if (it.json) "json" else "js"}" } ?: best.templateId))
            best.fill.forEach { (k, v) -> add(k to v) }
            if (template != null) {
                val unfilled = template.placeholders.filter { it !in best.fill }
                if (unfilled.isNotEmpty()) add("from defaults" to unfilled.joinToString(", "))
            }
        }
        MonoCard(title = null, text = rows.joinToString("\n") { (k, v) -> "${k.padEnd(14)} ${v.ifBlank { "—" }}" })
        if (template != null) {
            Text(
                "Parts: " + template.explain.joinToString(" · ") { it.title },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ---------- pickers ----------

@Composable
private fun TemplatePickerDialog(templates: List<LabTemplate>, onPick: (LabTemplate) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A template") },
        text = {
            if (templates.isEmpty()) {
                Text("No templates found")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(templates, key = { it.id }) { template ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(template) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(template.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${template.kind} · ${if (template.json) "JSON" else "JS"} · v${template.version} · ${template.source} · ${template.explain.size} parts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ListPickerDialog(title: String, empty: String, items: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (items.isEmpty()) {
                Text(empty)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(items, key = { it.first }) { (key, name) ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(key) }
                                .padding(vertical = 12.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExtensionPickerDialog(
    rows: List<ApkImport.ExtensionSourceRow>?,
    busy: String?,
    error: String?,
    onPick: (ApkImport.ExtensionSourceRow) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from extension") },
        text = {
            when {
                busy != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(busy)
                }
                rows == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Reading the installed extensions…")
                }
                rows.isEmpty() -> Text("No installed extension has a web source")
                else -> {
                    val groups = rows.groupBy { it.pkgName }
                    LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                        // KMK (r98): an import that failed says why here (the screen's error dialog waits for the picker)
                        if (error != null) {
                            item(key = "error") {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                        }
                        groups.forEach { (pkg, sources) ->
                            val first = sources.first()
                            item(key = "pkg:$pkg") {
                                Text(
                                    text = buildString {
                                        append(first.extensionName)
                                        append(" ")
                                        append(first.versionName)
                                        append(" · ")
                                        append(if (first.anime) "anime" else "manga")
                                        if (first.sideloaded) append(" · sideloaded")
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                                )
                            }
                            items(sources, key = { "${it.pkgName}:${it.sourceId}" }) { row ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(row) }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Text("${row.sourceName} (${row.lang})", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${row.baseUrl} · id ${row.sourceId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            item(key = "div:$pkg") { HorizontalDivider() }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExtensionImportDialog(
    result: ApkImport.ImportResult,
    host: String,
    onWrite: () -> Unit,
    onRecord: () -> Unit,
    onGitHub: () -> Unit,
    onDismiss: () -> Unit,
) {
    val g = result.generated
    val facts = result.facts
    // KMK (r99): the actions sit in their own column under the scrolling text, inside the dialog's text slot. In
    // r98 Record and convert was the last thing of the scrolling text (cut off at 460 dp on the tablet) and the
    // three text buttons in the dialog's own row ("GitHub · Cancel · Template only") were all that showed. Now the
    // text takes what is left (weight(1f, fill = false)) and the buttons always show: the primary full width, the
    // secondary outlined full width, GitHub / Cancel as text buttons at the end.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result.themeRecognised) "Imported from the extension" else "Custom extension") },
        text = {
            Column {
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (!result.themeRecognised) {
                        Text(
                            text = if (result.themeKnown) {
                                "The extension is built on ${facts.theme}, which the Lab knows but has no template for. It kept the id, name and URL and used the closest template; the parts that read pages are marked not written."
                            } else if (facts.error != null) {
                                "${facts.error.replaceFirstChar { it.uppercase() }}. The Lab kept the id, name and URL. Use Build by tapping for the rest, or open its source on GitHub."
                            } else {
                                "Custom extension: the APK's strings don't tell its logic; the template only keeps the id, name and URL. Record and convert below runs the extension and writes the parts from what it really does."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                    val lines = buildList<String> {
                        add("id            ${g.sourceId} (kept: library titles keep working)")
                        add("name          ${g.suggestedName}")
                        add("baseUrl       ${g.baseUrl}")
                        add("theme         ${facts.themeClass ?: facts.error ?: "none found"}")
                        add("template      ${result.templateId}")
                        add("dex files     ${facts.dexFiles} · ${facts.strings} strings")
                        if (facts.dateFormats.isNotEmpty()) add("date formats  ${facts.dateFormats.take(3).joinToString(", ")}")
                        if (facts.chapterPaths.isNotEmpty()) add("chapter paths ${facts.chapterPaths.take(3).joinToString(", ")}")
                        if (facts.selectors.isNotEmpty()) add("selectors     ${facts.selectors.take(4).joinToString(", ")}")
                        if (facts.sameHostUrls.isNotEmpty()) add("addresses     ${facts.sameHostUrls.take(3).joinToString(", ")}")
                    }
                    MonoCard(title = null, text = lines.joinToString("\n"))
                    Text(
                        text = g.explain.firstOrNull()?.text.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    // KMK (r98): the second level, for any source; the main way for a custom extension
                    Text(
                        text = if (result.themeRecognised) {
                            "Record and convert runs the extension itself and writes every part from what it really does, settings and filters included."
                        } else {
                            "Record and convert runs the extension itself, captures its requests and writes the source from them: settings, filters, and every step it can derive."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    RecordNote(host)
                }
                Spacer(Modifier.height(12.dp))
                // Primary: Record and convert for a custom extension, Write the source for a themed one
                if (result.themeRecognised) {
                    Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) { Text("Write the source") }
                    OutlinedButton(onClick = onRecord, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(Icons.Outlined.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Record and convert")
                    }
                } else {
                    Button(onClick = onRecord, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Record and convert")
                    }
                    OutlinedButton(onClick = onWrite, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Template only") }
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    TextButton(onClick = onGitHub) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("GitHub")
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
        // The actions are in the text slot above (always visible); the dialog's own button row stays empty
        confirmButton = {},
    )
}

// ---------- KMK (r98): Record and convert ----------

@Composable
private fun RecordNote(host: String) {
    val site = host.removePrefix("https://").removePrefix("http://").trimEnd('/').ifBlank { "the site" }
    Text(
        text = "It uses your installed extension and your connection: about 10–20 requests to $site and the hosts it calls, " +
            "${ExtensionRecorder.STEP_TIMEOUT_MS / 1000} s per step (up to ${ExtensionRecorder.STEP_MAX_MS / 1000} s while a reply is on its way). Nothing is added to your library. Cookie and login " +
            "values are left out of what is kept. The extension may save to its own settings as it always does. Only its " +
            "requests are recorded, but one the app makes meanwhile to the same site can slip in, so wait on this screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun RecordDialog(
    row: ApkImport.ExtensionSourceRow?,
    steps: List<ExtensionRecorder.StepProgress>,
    running: Boolean,
    result: RecordedConverter.Result?,
    error: String?,
    busy: String?,
    onStop: () -> Unit,
    onWrite: () -> Unit,
    onBack: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!running) onBack() },
        title = { Text("Record and convert") },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (row != null) {
                    Text(
                        "${row.sourceName} (${row.lang}) · ${row.extensionName} ${row.versionName}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (running) RecordNote(row?.baseUrl.orEmpty())
                Spacer(Modifier.height(8.dp))
                if (steps.isEmpty() && running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading the extension's source…")
                    }
                }
                steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        StatusDot(step.status)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(step.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            step.note?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (step.status == StepStatus.Red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (step.ms > 0) {
                            Text(
                                "${step.requests} req · ${step.ms / 100 / 10.0} s",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index < steps.lastIndex) HorizontalDivider()
                }
                if (error != null) {
                    Text(
                        if (error == "Stopped") "Stopped: nothing was written." else "Recording failed: $error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (result != null) {
                    Spacer(Modifier.height(10.dp))
                    val lines = buildList {
                        add("settings        ${result.settings}")
                        add("filters         ${result.filters}")
                        add("steps written   ${result.written.size}${if (result.written.isNotEmpty()) " (" + result.written.joinToString(", ") + ")" else ""}")
                        add("TODO parts      ${result.todo.size}${if (result.todo.isNotEmpty()) " (" + result.todo.joinToString(", ") + ")" else ""}")
                        // KMK (r105): each written list / title page run on the recorded replies against the extension's own results
                        if (result.checks.isNotEmpty()) add("checked         ${result.checks.joinToString(", ")}")
                        result.botCheck?.let { add("bot check       $it (passed in the hidden browser)") }
                        add("id              ${result.generated.sourceId} (kept: library titles stay linked)")
                    }
                    MonoCard(title = null, text = lines.joinToString("\n"))
                    result.notes.forEach { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                running -> TextButton(onClick = onStop) { Text("Stop") }
                result != null -> TextButton(onClick = onWrite, enabled = busy == null) { Text("Write the source") }
                else -> TextButton(onClick = onBack) { Text("Back") }
            }
        },
        dismissButton = {
            if (!running && result != null) TextButton(onClick = onBack) { Text("Back") }
        },
    )
}
