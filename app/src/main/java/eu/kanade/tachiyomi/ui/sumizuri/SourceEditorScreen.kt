package eu.kanade.tachiyomi.ui.sumizuri

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.sumizuri.SumizuriPreferences
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * KMK: The Code editor, opened from the code button in Browse. Write a Sumizuri-style source as JavaScript or as a
 * JSON declaration (tabs at the top), test it in the side panel (Details / Test / Console) and save it: it then
 * shows in Browse with the other sources. Existing .js / .json sources can be imported and exported.
 */
class SourceEditorScreen(private val sourceKey: String? = null) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { SourceEditorScreenModel(sourceKey) }
        val state by model.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        val saveClipboard = LocalClipboardManager.current
        var showMenu by remember { mutableStateOf(false) }
        var showSaved by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }

        LaunchedEffect(state.justSaved) {
            val info = state.justSaved ?: return@LaunchedEffect
            model.consumeSaved()
            val result = snackbar.showSnackbar(
                message = "Saved. ${info.name} is in Browse → Sources (${if (info.anime) "anime" else "manga"})",
                actionLabel = "Open",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                navigator.push(
                    if (info.anime) {
                        AnimeSourceScreen(info.id)
                    } else {
                        BrowseSourceScreen(info.id, GetRemoteManga.QUERY_POPULAR)
                    },
                )
            }
        }

        LaunchedEffect(state.message) {
            val message = state.message ?: return@LaunchedEffect
            model.consumeMessage()
            snackbar.showSnackbar(message)
        }

        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                model.importCode(context.fileName(uri), text)
            }.onFailure { context.toast("Couldn't read the file: ${it.message}") }
        }
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(if (state.json) "application/json" else "text/javascript"),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(state.code) }
                context.toast("Exported")
            }.onFailure { context.toast("Couldn't write the file: ${it.message}") }
        }
        val openInBrowser: () -> Unit = {
            val url = state.baseUrl.trim()
            if (url.startsWith("http")) {
                navigator.push(WebViewScreen(url = url, initialTitle = state.name.ifBlank { url }, sourceId = null))
            } else {
                context.toast("Set the Base URL first")
            }
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                val wide = maxWidth >= 840.dp
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top bar: back, title, the JS / JSON tabs, run, save and the menu
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        }
                        Column(modifier = Modifier.padding(start = 4.dp, end = 12.dp).weight(if (wide) 0.35f else 1f)) {
                            Text(
                                text = if (state.saved) state.name.ifBlank { "Source" } else "Add source",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append(if (state.anime) "Anime" else "Manga")
                                    append(" · ")
                                    append(if (state.json) "JSON" else "JavaScript")
                                    if (state.dirty) append(" · not saved")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (wide) {
                            KindTabs(json = state.json, onJson = model::setJson, modifier = Modifier.width(380.dp))
                            Spacer(Modifier.weight(0.65f))
                        }
                        IconButton(onClick = { model.runAll(query) }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Run all", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = model::save) {
                            Icon(Icons.Outlined.Save, contentDescription = "Save")
                        }
                        IconButton(onClick = { showSaved = true }) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "My sources")
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                MenuItem("New JS source", Icons.Outlined.Add) {
                                    showMenu = false
                                    model.newSource(json = false, anime = state.anime)
                                }
                                MenuItem("New JSON source", Icons.Outlined.Add) {
                                    showMenu = false
                                    model.newSource(json = true, anime = state.anime)
                                }
                                MenuItem("Import .js / .json file", Icons.Outlined.FileUpload) {
                                    showMenu = false
                                    importLauncher.launch("*/*")
                                }
                                MenuItem("Export to ${if (state.json) ".json" else ".js"} file", Icons.Outlined.FileDownload) {
                                    showMenu = false
                                    val base = state.name.ifBlank { "source" }.replace(Regex("[^A-Za-z0-9._-]+"), "_")
                                    exportLauncher.launch(base + if (state.json) ".json" else ".js")
                                }
                                MenuItem("Open in browser", Icons.AutoMirrored.Outlined.OpenInNew) {
                                    showMenu = false
                                    openInBrowser()
                                }
                                if (state.saved) {
                                    MenuItem("Delete source", Icons.Outlined.DeleteOutline) {
                                        showMenu = false
                                        confirmDelete = true
                                    }
                                }
                            }
                        }
                    }
                    if (!wide) {
                        KindTabs(json = state.json, onJson = model::setJson, modifier = Modifier.fillMaxWidth())
                    }
                    if (state.running != null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        HorizontalDivider()
                    }

                    val editor = @Composable { modifier: Modifier ->
                        CodeEditor(code = state.code, onCodeChange = model::setCode, json = state.json, modifier = modifier)
                    }
                    if (wide) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            editor(Modifier.weight(1f).fillMaxHeight())
                            VerticalDivider()
                            SidePanel(
                                state = state,
                                model = model,
                                query = query,
                                onQuery = { query = it },
                                onOpenInBrowser = openInBrowser,
                                modifier = Modifier.width(420.dp).fillMaxHeight(),
                            )
                        }
                    } else {
                        var tab by rememberSaveable { mutableIntStateOf(0) }
                        SecondaryTabRow(selectedTabIndex = tab) {
                            listOf("Code", "Details", "Test").forEachIndexed { index, label ->
                                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                            }
                        }
                        when (tab) {
                            0 -> editor(Modifier.fillMaxSize())
                            else -> SidePanel(
                                state = state,
                                model = model,
                                query = query,
                                onQuery = { query = it },
                                onOpenInBrowser = openInBrowser,
                                fixedTab = tab - 1,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        if (showSaved) {
            SavedSourcesDialog(
                model = model,
                onNew = { json ->
                    showSaved = false
                    model.newSource(json = json, anime = state.anime)
                },
                onOpen = {
                    showSaved = false
                    model.open(it)
                },
                onDismiss = { showSaved = false },
            )
        }
        state.saveError?.let { error ->
            AlertDialog(
                onDismissRequest = model::dismissSaveError,
                title = { Text("Couldn't save") },
                text = {
                    SelectionContainer {
                        Text(
                            "The source has to run to be saved, and it stopped with:\n\n$error",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = model::dismissSaveError) { Text("OK") } },
                dismissButton = {
                    TextButton(
                        onClick = {
                            saveClipboard.setText(AnnotatedString(error))
                            context.toast("Copied")
                        },
                    ) { Text("Copy") }
                },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete ${state.name}?") },
                text = { Text("It is removed from Browse. Titles in your library from it stay, but can't be updated.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            if (model.delete()) navigator.pop()
                        },
                    ) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
        state.settings?.let { definitions ->
            SourceSettingsDialog(
                definitions = definitions,
                sourceId = model.sourceId(state.key),
                onDismiss = model::closeSettings,
            )
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
}

/** "JS extension | JSON declarative", at the top like Sumizuri's first editor */
@Composable
private fun KindTabs(json: Boolean, onJson: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SecondaryTabRow(selectedTabIndex = if (json) 1 else 0, modifier = modifier) {
        Tab(selected = !json, onClick = { onJson(false) }, text = { Text("JS extension") })
        Tab(selected = json, onClick = { onJson(true) }, text = { Text("JSON declarative") })
    }
}

@Composable
private fun SidePanel(
    state: SourceEditorScreenModel.State,
    model: SourceEditorScreenModel,
    query: String,
    onQuery: (String) -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier,
    fixedTab: Int? = null,
) {
    var tab by rememberSaveable { mutableIntStateOf(1) }
    val shown = fixedTab ?: tab
    Column(modifier = modifier) {
        if (fixedTab == null) {
            SecondaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Details") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Test") })
            }
        }
        when (shown) {
            0 -> DetailsPanel(state, model, onOpenInBrowser)
            else -> TestPanel(state, model, query, onQuery)
        }
    }
}

@Composable
private fun DetailsPanel(
    state: SourceEditorScreenModel.State,
    model: SourceEditorScreenModel,
    onOpenInBrowser: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = state.iconUrl.ifBlank { null },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(state.name.ifBlank { "Untitled source" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    state.baseUrl.ifBlank { "No base URL yet" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            "Left empty, these come from the source's own name, lang, iconUrl and baseUrl.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(state.name, model::setName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.iconUrl, model::setIconUrl, label = { Text("Icon URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.lang, model::setLang, label = { Text("Language") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Content", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !state.anime, onClick = { model.setAnime(false) }, label = { Text("Manga") })
            FilterChip(selected = state.anime, onClick = { model.setAnime(true) }, label = { Text("Anime") })
        }
        OutlinedTextField(state.baseUrl, model::setBaseUrl, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenInBrowser) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open in browser")
            }
            OutlinedButton(onClick = model::openSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Source settings")
            }
        }
        OutlinedButton(onClick = model::loadInfo) { Text("Read details from the code") }
    }
}

@Composable
private fun TestPanel(
    state: SourceEditorScreenModel.State,
    model: SourceEditorScreenModel,
    query: String,
    onQuery: (String) -> Unit,
) {
    var consoleOpen by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search the site") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { if (query.isNotBlank()) model.runSearch(query) }) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Search")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                AssistChip(onClick = { model.runAll(query) }, label = { Text("Run all") })
                AssistChip(onClick = { model.runPopular() }, label = { Text("Popular") })
                AssistChip(onClick = { model.runLatest() }, label = { Text("Latest") })
                AssistChip(onClick = model::runCatalogs, label = { Text("Catalogs") })
                AssistChip(onClick = model::runFilters, label = { Text("Filters") })
                AssistChip(onClick = model::openSettings, label = { Text("Source settings") })
                AssistChip(onClick = model::loadInfo, label = { Text("Info") })
                AssistChip(onClick = model::clearResults, label = { Text("Clear") })
            }
            state.running?.let {
                Text("Running: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider()
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (state.results.isEmpty()) {
                item {
                    Text(
                        "Run Popular, Latest or a search to try the source. Tap a title to load its " +
                            if (state.anime) "episodes, then an episode for its videos." else "chapters, then a chapter for its pages.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.results) { result -> ResultCard(result, onOpen = model::openItem) }
        }
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { consoleOpen = !consoleOpen }
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Text("Console", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "${state.console.size} lines · ${if (consoleOpen) "hide" else "show"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Copies the whole console (not only the lines shown)
            IconButton(
                onClick = {
                    if (state.console.isEmpty()) {
                        context.toast("The console is empty")
                    } else {
                        clipboard.setText(AnnotatedString(state.console.joinToString("\n")))
                        context.toast("Copied ${state.console.size} lines")
                    }
                },
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy console", modifier = Modifier.size(20.dp))
            }
        }
        if (consoleOpen) {
            SelectionContainer {
                Text(
                    text = state.console.takeLast(200).joinToString("\n").ifBlank { "console.log output and requests show here" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .verticalScroll(rememberScrollState(), reverseScrolling = true)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultCard(result: SourceEditorScreenModel.Result, onOpen: (SourceEditorScreenModel.Item) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (result.ok) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (result.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    result.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                Text("${result.millis} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            result.text?.let { text ->
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) FontFamily.Monospace else null,
                        color = if (result.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            result.items.take(60).forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = item.kind != SourceEditorScreenModel.Kind.Page) { onOpen(item) }
                        .padding(vertical = 6.dp),
                ) {
                    if (item.imageUrl != null) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 36.dp, height = 50.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        item.subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (result.items.size > 60) {
                Text("… and ${result.items.size - 60} more", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SavedSourcesDialog(
    model: SourceEditorScreenModel,
    onNew: (json: Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val saved by model.savedSources.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My sources") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                if (saved.isEmpty()) {
                    item { Text("Nothing saved yet. Sources you save show here and in Browse.") }
                }
                items(saved, key = { it.key }) { info ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpen(info.key) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        AsyncImage(
                            model = info.iconUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(info.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${if (info.anime) "Anime" else "Manga"} · ${if (info.json) "JSON" else "JS"} · ${info.lang}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onNew(false) }) { Text("New JavaScript") } },
        dismissButton = { TextButton(onClick = { onNew(true) }) { Text("New JSON definition") } },
    )
}

/** The source's own settings (getSourcePreferences), saved where Browse's source settings read them */
@Composable
private fun SourceSettingsDialog(
    definitions: List<SumizuriPreferences.Definition>,
    sourceId: Long,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(sourceId) { SumizuriPreferences.of(context, sourceId) }
    var version by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Source settings") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (definitions.isEmpty()) Text("This source has no settings.")
                definitions.forEach { def ->
                    // Read again after every change
                    val current = remember(version, def.key) { SumizuriPreferences.current(preferences, def) }
                    fun set(value: kotlinx.serialization.json.JsonElement?) {
                        SumizuriPreferences.write(preferences, def.key, value)
                        version++
                    }
                    // KMK: a section header
                    if (def.type == SumizuriPreferences.Type.Category) {
                        Text(
                            def.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        return@forEach
                    }
                    Column {
                        if (def.title.isNotEmpty()) Text(def.title, style = MaterialTheme.typography.titleSmall)
                        def.summary?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when (def.type) {
                            // KMK -->
                            SumizuriPreferences.Type.Category, SumizuriPreferences.Type.Info -> Unit
                            SumizuriPreferences.Type.Slider -> {
                                val slider = def.slider ?: SumizuriPreferences.Slider(0, 10, 1, false, "")
                                val saved = SumizuriPreferences.sliderValues((current as? JsonPrimitive)?.contentOrNull, slider)
                                var dragging by remember(def.key, version) { mutableStateOf<Pair<Int, Int>?>(null) }
                                val shown = dragging ?: saved
                                val bounds = slider.min.toFloat()..slider.max.toFloat()
                                val stops = ((slider.max - slider.min) / slider.step - 1).coerceAtLeast(0)
                                val save = {
                                    dragging?.let { (a, b) -> set(JsonPrimitive(SumizuriPreferences.sliderText(a, b, slider))) }
                                    dragging = null
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (slider.range) {
                                        androidx.compose.material3.RangeSlider(
                                            value = shown.first.toFloat()..shown.second.toFloat(),
                                            onValueChange = {
                                                dragging = it.start.roundToInt() to it.endInclusive.roundToInt()
                                            },
                                            onValueChangeFinished = save,
                                            valueRange = bounds,
                                            steps = stops,
                                            modifier = Modifier.weight(1f),
                                        )
                                    } else {
                                        androidx.compose.material3.Slider(
                                            value = shown.first.toFloat(),
                                            onValueChange = { dragging = it.roundToInt() to it.roundToInt() },
                                            onValueChangeFinished = save,
                                            valueRange = bounds,
                                            steps = stops,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Text(
                                        SumizuriPreferences.sliderLabel(shown.first, shown.second, slider),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
                                }
                            }
                            // KMK <--
                            SumizuriPreferences.Type.Switch -> Switch(
                                checked = (current as? JsonPrimitive)?.booleanOrNull ?: false,
                                onCheckedChange = { set(JsonPrimitive(it)) },
                            )
                            SumizuriPreferences.Type.List -> def.options.forEach { option ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { set(JsonPrimitive(option.value)) },
                                ) {
                                    RadioButton(
                                        selected = (current as? JsonPrimitive)?.contentOrNull == option.value,
                                        onClick = { set(JsonPrimitive(option.value)) },
                                    )
                                    Text(option.label)
                                }
                            }
                            SumizuriPreferences.Type.MultiList -> {
                                val selected = (current as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
                                def.options.forEach { option ->
                                    val checked = option.value in selected
                                    val toggle = {
                                        val next = if (checked) selected - option.value else selected + option.value
                                        set(JsonArray(next.map { JsonPrimitive(it) }))
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().clickable(onClick = toggle),
                                    ) {
                                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                                        Text(option.label)
                                    }
                                }
                            }
                            SumizuriPreferences.Type.Text -> {
                                var text by remember(def.key) { mutableStateOf((current as? JsonPrimitive)?.contentOrNull.orEmpty()) }
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = {
                                        text = it
                                        set(JsonPrimitive(it))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun Context.fileName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment
