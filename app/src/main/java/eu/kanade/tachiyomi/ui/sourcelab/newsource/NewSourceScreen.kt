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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
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
import eu.kanade.tachiyomi.jssource.lab.FingerprintFile
import eu.kanade.tachiyomi.jssource.lab.LabFlowScreen
import eu.kanade.tachiyomi.jssource.lab.LabTemplate
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.ui.sourcelab.LabStatusStrip
import eu.kanade.tachiyomi.ui.sourcelab.MonoCard
import eu.kanade.tachiyomi.ui.sourcelab.StripColor
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
 */
internal class NewSourceScreen(private val kindHint: Boolean? = null) : Screen(), LabFlowScreen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { NewSourceScreenModel(kindHint) }
        val state by model.state.collectAsState()
        val tablet = isTabletUi()

        LaunchedEffect(state.delivered) {
            if (state.delivered) navigator.popUntil { it !is LabFlowScreen }
        }
        // The system Back does what the bar's arrow does: a detection result goes back to the entry page first
        BackHandler(enabled = state.phase != NewSourceScreenModel.Phase.Entry) { model.backToEntry() }

        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) model.importFile(uri)
        }
        val pickFile: (String) -> Unit = { mime -> filePicker.launch(mime) }

        val host = state.url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        val subtitle = when (state.phase) {
            NewSourceScreenModel.Phase.Entry -> "paste the site address"
            NewSourceScreenModel.Phase.Detecting -> "$host · detecting the engine"
            NewSourceScreenModel.Phase.Detected -> state.best?.let { "$host · ${it.engineName} ${it.confidence} %" } ?: "$host · no engine matched"
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = "New source",
                    subtitle = subtitle,
                    navigateUp = {
                        if (state.phase != NewSourceScreenModel.Phase.Entry) model.backToEntry() else navigator.pop()
                        Unit
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                val (head, color) = when (state.phase) {
                    NewSourceScreenModel.Phase.Entry -> "New" to StripColor.Blue
                    NewSourceScreenModel.Phase.Detecting -> "Detecting" to StripColor.Blue
                    NewSourceScreenModel.Phase.Detected -> if (state.result?.writeOffered == true) "Detected" to StripColor.Green else "Detected" to StripColor.Amber
                }
                LabStatusStrip(
                    head = head,
                    color = color,
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
                if (state.busy != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Text(
                        text = state.busy.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (tablet) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        MainColumn(state, model, pickFile, modifier = Modifier.weight(1f).fillMaxHeight())
                        Column(
                            modifier = Modifier
                                .width(380.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(end = 16.dp, bottom = 16.dp),
                        ) {
                            HowDetectionWorksCard(state.fingerprints)
                        }
                    }
                } else {
                    MainColumn(state, model, pickFile, modifier = Modifier.fillMaxSize(), howCard = true)
                }
            }
        }

        // Pickers and cards over the screen
        when (state.picker) {
            NewSourceScreenModel.Picker.Template -> TemplatePickerDialog(
                templates = state.templates,
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
                if (import != null) {
                    ExtensionImportDialog(
                        result = import,
                        onWrite = model::confirmExtensionImport,
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
            AlertDialog(
                onDismissRequest = model::backToEntry,
                title = { Text("The site wants a bot check") },
                text = { Text("Open it in WebView, pass the check, then detect again. The check counts for the app's connection too.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            model.backToEntry()
                            navigator.push(WebViewScreen(url = botCheck, initialTitle = host, sourceId = null))
                        },
                    ) { Text("Open in WebView") }
                },
                dismissButton = { TextButton(onClick = model::backToEntry) { Text("Later") } },
            )
        }
    }
}

// ---------- the main column ----------

@Composable
private fun MainColumn(
    state: NewSourceScreenModel.State,
    model: NewSourceScreenModel,
    pickFile: (String) -> Unit,
    modifier: Modifier = Modifier,
    howCard: Boolean = false,
) {
    val navigator = LocalNavigator.currentOrThrow
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.phase == NewSourceScreenModel.Phase.Entry) {
            item(key = "entry") { EntryCard(state, model) }
            item(key = "start-from") {
                StartFromCard(
                    templates = state.templates,
                    savedNames = state.savedSources.map { it.second },
                    onTemplate = { model.openPicker(NewSourceScreenModel.Picker.Template) },
                    onBuild = { navigator.push(BuilderScreen(state.url.trim().ifBlank { null }, state.kindHint == true)) },
                    onImport = { pickFile("*/*") },
                    onDuplicate = { model.openPicker(NewSourceScreenModel.Picker.Duplicate) },
                    onExtension = { model.openPicker(NewSourceScreenModel.Picker.Extension) },
                )
            }
            if (howCard) item(key = "how") { HowDetectionWorksCard(state.fingerprints) }
        } else {
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

@Composable
private fun EntryCard(state: NewSourceScreenModel.State, model: NewSourceScreenModel) {
    LabCard(title = "Site address", trailing = "paste or type") {
        OutlinedTextField(
            value = state.url,
            onValueChange = model::setUrl,
            singleLine = true,
            placeholder = { Text("https://mangaread.org") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            FilterChip(selected = state.kindHint == null, onClick = { model.setKind(null) }, label = { Text("Let the Lab decide") })
            FilterChip(selected = state.kindHint == false, onClick = { model.setKind(false) }, label = { Text("Manga") })
            FilterChip(selected = state.kindHint == true, onClick = { model.setKind(true) }, label = { Text("Anime") })
        }
        Text(
            text = "The Lab loads the site through the app's connection (a hidden browser page when a bot check answers), " +
                "compares it with the engines it knows, and writes the source from the matching template. Nothing is saved until you save.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = model::detect, enabled = state.busy == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Outlined.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Detect the site", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun StartFromCard(
    templates: List<LabTemplate>,
    savedNames: List<String>,
    onTemplate: () -> Unit,
    onBuild: () -> Unit,
    onImport: () -> Unit,
    onDuplicate: () -> Unit,
    onExtension: () -> Unit,
) {
    val engineNames = templates.filter { !it.id.startsWith("blank-") && !it.id.startsWith("generic") }.map { it.name.substringBefore(" (") }
    LabCard(title = "Or start from") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StartTile(Icons.Outlined.Description, "A template", engineNames.take(3).joinToString(", ").ifBlank { "blank skeletons" }, onTemplate, Modifier.weight(1f))
            StartTile(Icons.Outlined.TouchApp, "Build by tapping", "unknown engine", onBuild, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StartTile(Icons.Outlined.FileUpload, "Import a file", ".js or .json", onImport, Modifier.weight(1f))
            StartTile(Icons.Outlined.ContentCopy, "Duplicate a source", savedNames.take(2).joinToString(", ").ifBlank { "a saved source" }, onDuplicate, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StartTile(Icons.Outlined.Extension, "Import from extension", "an installed manga or anime APK", onExtension, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StartTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 72.dp).clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HowDetectionWorksCard(fingerprints: FingerprintFile?) {
    LabCard(title = "How detection works", trailing = "no guessing: fixed markers") {
        val rows = listOf(
            "1 · Fetch" to "home page, then one title it links to, then the chapter request a candidate needs (3 at most)",
            "2 · Compare" to "each engine has 3–6 markers (a theme path, selector counts, a script name, a request)",
            "3 · Score" to "matched markers weighted 3 / 2 / 1 ÷ the engine's total · ties by marker count",
            "4 · Write" to "template + the site's own paths and attributes, every part with a description",
            "Below 60 %" to "the Lab offers Build by tapping instead of writing from a template",
        )
        rows.forEach { (k, v) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
                Text(v, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
        }
        if (fingerprints != null) {
            Text(
                text = "Engines in fingerprints.json v${fingerprints.version}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            fingerprints.engines.forEach { engine ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(engine.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${engine.markers.size} markers · ${engine.kind}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
private fun ExtensionImportDialog(result: ApkImport.ImportResult, onWrite: () -> Unit, onGitHub: () -> Unit, onDismiss: () -> Unit) {
    val g = result.generated
    val facts = result.facts
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result.themeRecognised) "Imported from the extension" else "Custom extension") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp)) {
                if (!result.themeRecognised) {
                    Text(
                        text = if (result.themeKnown) {
                            "The extension is built on ${facts.theme}, which the Lab knows but has no template for. It kept the id, name and URL and used the closest template; the parts that read pages are marked not written."
                        } else if (facts.error != null) {
                            "${facts.error.replaceFirstChar { it.uppercase() }}. The Lab kept the id, name and URL. Use Build by tapping for the rest, or open its source on GitHub."
                        } else {
                            "Custom extension: the Lab can't read its logic; it kept the id, name and URL. Use Build by tapping for the rest, or open its source on GitHub."
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
            }
        },
        confirmButton = { TextButton(onClick = onWrite) { Text("Write the source") } },
        dismissButton = {
            Row {
                TextButton(onClick = onGitHub) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("GitHub")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
