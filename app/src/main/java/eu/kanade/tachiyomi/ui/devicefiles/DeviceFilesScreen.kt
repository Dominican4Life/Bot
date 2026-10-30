package eu.kanade.tachiyomi.ui.devicefiles

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.devicefiles.CategorySummary
import eu.kanade.tachiyomi.devicefiles.DeviceFile
import eu.kanade.tachiyomi.devicefiles.DeviceFilesOpener
import eu.kanade.tachiyomi.devicefiles.DeviceFilesPreferences
import eu.kanade.tachiyomi.devicefiles.DeviceFilesRepository
import eu.kanade.tachiyomi.devicefiles.FileCategory
import eu.kanade.tachiyomi.devicefiles.FileKind
import eu.kanade.tachiyomi.devicefiles.FileSort
import eu.kanade.tachiyomi.devicefiles.StorageRoot
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.util.collectAsState as collectPreference

/**
 * KMK: Device files, everything on the device's storage, laid out like the Browse page of Google's Files app.
 * The main page has recent files, the categories, the favourites collection and the storage devices; the other pages
 * list a category, a folder, the favourites or the recent files. Videos play in the app's player, comics and
 * pictures open in the reader, and anything else opens in another app.
 */
class DeviceFilesScreen(val where: String = "") : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val location = remember(where) { FilesLocation.decode(where) }
        val model = rememberScreenModel { DeviceFilesScreenModel(context.applicationContext, location) }

        // Back from the system settings (after allowing access) or from the player: the files may have changed
        var resumed by remember { mutableStateOf(false) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (resumed) model.refresh()
            resumed = true
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            model.refresh()
        }
        val askForAccess: () -> Unit = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val appPage = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
                runCatching { context.startActivity(appPage) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            } else {
                permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (location == FilesLocation.Home) {
            HomePage(model, navigator, askForAccess)
        } else {
            ListPage(model, location, navigator, askForAccess)
        }
    }
}

private fun Navigator.openLocation(location: FilesLocation) {
    val target = location.encode()
    // Going back up a folder path returns to that page rather than stacking another
    val existing = items.lastOrNull { (it as? DeviceFilesScreen)?.where == target }
    if (existing != null) popUntil { it == existing } else push(DeviceFilesScreen(target))
}

// Main page

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePage(model: DeviceFilesScreenModel, navigator: Navigator, askForAccess: () -> Unit) {
    val context = LocalContext.current
    val state by model.state.collectAsState()
    val results by model.searchResults.collectAsState()
    var query by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    val prefs = DeviceFilesPreferences.instance
    val favourites by prefs.favourites.collectPreference()
    val actions = rememberFileActionsState(model)

    BackHandler(enabled = query != null) {
        query = null
        model.search(null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (query != null) {
                                query = null
                                model.search(null)
                            } else {
                                navigator.pop()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    val text = query
                    if (text == null) {
                        Text("Device files")
                    } else {
                        SearchField(text, "Search this device") {
                            query = it
                            model.search(it)
                        }
                    }
                },
                actions = {
                    if (query == null) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
                    }
                    IconButton(onClick = { settings = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        val searchResults = results
        LazyVerticalGrid(
            columns = GridCells.Adaptive(170.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) =
                { GridItemSpan(maxLineSpan) }
            if (!state.hasAccess) {
                item(span = full) { AccessCard(askForAccess, Modifier.padding(top = 8.dp)) }
                return@LazyVerticalGrid
            }
            if (query != null) {
                if (searchResults == null || query.isNullOrBlank()) return@LazyVerticalGrid
                item(span = full) {
                    SectionHeader(if (searchResults.isEmpty()) "No files found" else "${searchResults.size} files")
                }
                items(searchResults, key = { "s-" + it.path }, span = { GridItemSpan(maxLineSpan) }) { file ->
                    FileRow(
                        file = file,
                        favourite = file.path in favourites,
                        selected = false,
                        selecting = false,
                        onClick = { actions.open(file, navigator) },
                        onLongClick = { actions.run(FileAction.Info, listOf(file)) },
                        onAction = { actions.run(it, listOf(file)) },
                        subtitle = "${formatSize(context, file.size)} · ${file.parent}",
                    )
                }
                return@LazyVerticalGrid
            }
            if (state.recent.isNotEmpty()) {
                item(span = full) {
                    SectionHeader("Recent") {
                        TextButton(onClick = { navigator.openLocation(FilesLocation.Recent) }) { Text("See all") }
                    }
                }
                item(span = full) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.recent, key = { it.path }) { file ->
                            Box(Modifier.width(132.dp)) {
                                FileTile(
                                    file = file,
                                    favourite = file.path in favourites,
                                    selected = false,
                                    onClick = { actions.open(file, navigator) },
                                    onLongClick = { actions.run(FileAction.Info, listOf(file)) },
                                    subtitle = formatDate(file.modified),
                                )
                            }
                        }
                    }
                }
            }
            item(span = full) { SectionHeader("Categories") }
            items(FileCategory.entries, key = { it.name }) { category ->
                CategoryTile(category, state.summaries[category]) {
                    navigator.openLocation(FilesLocation.Category(category))
                }
            }
            item(span = full) { SectionHeader("Collections") }
            item(key = "favourites") {
                CollectionTile(
                    title = "Favourites",
                    subtitle = state.favouriteCount.let { if (it == 1) "1 item" else "$it items" },
                    icon = Icons.Filled.Star,
                    tint = Color(0xFFF2B01E),
                ) { navigator.openLocation(FilesLocation.Favourites) }
            }
            item(span = full) { SectionHeader("Storage devices") }
            items(state.roots, key = { "root-" + it.path }, span = { GridItemSpan(maxLineSpan) }) { root ->
                StorageCard(root) { navigator.openLocation(FilesLocation.Folder(root.path)) }
            }
            if (state.loading) {
                item(span = full) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }

    if (settings) {
        FilesSettingsDialog(onDismiss = {
            settings = false
            model.refresh()
        })
    }
    actions.Dialogs()
}

@Composable
private fun SearchField(text: String, hint: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TextField(
        value = text,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear") }
            }
        },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

private fun FileCategory.icon(): ImageVector = when (this) {
    FileCategory.Downloads -> Icons.Outlined.Download
    FileCategory.Images -> Icons.Outlined.Image
    FileCategory.Videos -> Icons.Outlined.Movie
    FileCategory.Audio -> Icons.Outlined.MusicNote
    FileCategory.Documents -> Icons.Outlined.Description
    FileCategory.Apps -> Icons.Outlined.Apps
}

private fun FileCategory.tint(): Color = when (this) {
    FileCategory.Downloads -> FileKind.Folder.tint()
    FileCategory.Images -> FileKind.Image.tint()
    FileCategory.Videos -> FileKind.Video.tint()
    FileCategory.Audio -> FileKind.Audio.tint()
    FileCategory.Documents -> FileKind.Archive.tint()
    FileCategory.Apps -> FileKind.Apk.tint()
}

@Composable
private fun CategoryTile(category: FileCategory, summary: CategorySummary?, onClick: () -> Unit) {
    val context = LocalContext.current
    CollectionTile(
        title = category.title,
        subtitle = summary?.let {
            (if (it.count == 1) "1 item" else "${it.count} items") + " · " + formatSize(context, it.size)
        } ?: "…",
        icon = category.icon(),
        tint = category.tint(),
        onClick = onClick,
    )
}

@Composable
private fun CollectionTile(title: String, subtitle: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).background(tint.copy(alpha = 0.16f), CircleShape),
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StorageCard(root: StorageRoot, onClick: () -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
            ) {
                Icon(
                    if (root.isPrimary) Icons.Outlined.Smartphone else Icons.Outlined.SdCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(root.label, style = MaterialTheme.typography.titleSmall)
                if (root.total > 0) {
                    Text(
                        "${formatSize(context, root.free)} free of ${formatSize(context, root.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StorageBar(1f - root.free.toFloat() / root.total, Modifier.padding(top = 8.dp))
                }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
}

// List pages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPage(
    model: DeviceFilesScreenModel,
    location: FilesLocation,
    navigator: Navigator,
    askForAccess: () -> Unit,
) {
    val context = LocalContext.current
    val state by model.state.collectAsState()
    val prefs = DeviceFilesPreferences.instance
    val favourites by prefs.favourites.collectPreference()
    val grid by prefs.gridView.collectPreference()
    val sortName by prefs.sort.collectPreference()
    val descending by prefs.sortDescending.collectPreference()
    val sort = remember(sortName) { runCatching { FileSort.valueOf(sortName) }.getOrDefault(FileSort.Name) }
    val actions = rememberFileActionsState(model)
    var filter by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<String>() }
    val mediaCategory = (location as? FilesLocation.Category)?.category?.takeIf {
        it == FileCategory.Images || it == FileCategory.Videos || it == FileCategory.Audio
    }
    var byFolder by remember { mutableStateOf(mediaCategory != null && mediaCategory != FileCategory.Audio) }

    val title = when (location) {
        is FilesLocation.Category -> location.category.title
        is FilesLocation.Folder -> state.rootLabel(location.path) ?: location.path.substringAfterLast('/')
        FilesLocation.Favourites -> "Favourites"
        FilesLocation.Recent -> "Recent files"
        FilesLocation.Home -> "Device files"
    }
    // Recent files stay newest first; everything else follows the sort setting
    val shown = remember(state.files, sort, descending, filter, location) {
        val matching = state.files.filter { filter.isNullOrBlank() || it.name.contains(filter!!.trim(), ignoreCase = true) }
        if (location == FilesLocation.Recent) matching else DeviceFilesRepository.sorted(matching, sort, descending)
    }
    val albums = remember(state.albums, filter) {
        state.albums.filter { filter.isNullOrBlank() || it.folder.name.contains(filter!!.trim(), ignoreCase = true) }
    }
    val selecting = selection.isNotEmpty()
    val selectedFiles = shown.filter { it.path in selection }

    BackHandler(enabled = selecting || filter != null) {
        if (selecting) selection.clear() else filter = null
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selection.clear() }) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") }
                    },
                    title = { Text("${selection.size} selected") },
                    actions = {
                        IconButton(onClick = {
                            selection.clear()
                            selection.addAll(shown.map { it.path })
                        }) { Icon(Icons.Outlined.SelectAll, contentDescription = "Select all") }
                        IconButton(onClick = {
                            model.toggleFavourites(selectedFiles)
                            selection.clear()
                        }) {
                            Icon(
                                if (selectedFiles.all { it.path in favourites }) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Favourite",
                            )
                        }
                        if (selectedFiles.none { it.isDirectory }) {
                            IconButton(onClick = { DeviceFilesOpener.share(context, selectedFiles.map { it.file }) }) {
                                Icon(Icons.Outlined.Share, contentDescription = "Share")
                            }
                        }
                        IconButton(onClick = { actions.run(FileAction.Delete, selectedFiles) { selection.clear() } }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { if (filter != null) filter = null else navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        val text = filter
                        if (text == null) {
                            Column {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!state.loading) {
                                    val count = if (mediaCategory != null && byFolder) albums.size else shown.size
                                    val noun = if (mediaCategory != null && byFolder) "folders" else "items"
                                    Text(
                                        "$count $noun",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            SearchField(text, "Search in $title") { filter = it }
                        }
                    },
                    actions = {
                        if (filter == null) {
                            IconButton(onClick = { filter = "" }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
                        }
                        IconButton(onClick = { prefs.gridView.set(!grid) }) {
                            Icon(
                                if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                                contentDescription = if (grid) "List view" else "Grid view",
                            )
                        }
                        if (location != FilesLocation.Recent) SortMenu(sort, descending)
                        IconButton(onClick = { settings = true }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (location is FilesLocation.Folder) Breadcrumbs(location.path, state.roots, navigator)
            if (mediaCategory != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    FilterChip(selected = byFolder, onClick = { byFolder = true }, label = { Text("Folders") })
                    FilterChip(selected = !byFolder, onClick = { byFolder = false }, label = { Text("All files") })
                }
            }
            when {
                !state.hasAccess -> AccessCard(askForAccess, Modifier.padding(16.dp))
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                mediaCategory != null && byFolder -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(albums, key = { it.folder.path }) { album ->
                        FileTile(
                            file = album.folder,
                            favourite = album.folder.path in favourites,
                            selected = false,
                            onClick = { navigator.openLocation(FilesLocation.Folder(album.folder.path)) },
                            onLongClick = { actions.run(FileAction.Info, listOf(album.folder)) },
                            cover = album.cover,
                            subtitle = "${album.folder.childCount} items · ${formatSize(context, album.folder.size)}",
                        )
                    }
                }
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            !filter.isNullOrBlank() -> "Nothing matches \"$filter\""
                            location == FilesLocation.Favourites -> "Star files and folders from their ⋮ menu to keep them here"
                            location is FilesLocation.Folder -> "This folder is empty"
                            else -> "No files"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
                else -> {
                    val onClick: (DeviceFile) -> Unit = { file ->
                        if (selecting) {
                            if (file.path in selection) selection.remove(file.path) else selection.add(file.path)
                        } else {
                            actions.open(file, navigator)
                        }
                    }
                    val onLongClick: (DeviceFile) -> Unit = { file ->
                        if (file.path in selection) selection.remove(file.path) else selection.add(file.path)
                    }
                    if (grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(130.dp),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(shown, key = { it.path }) { file ->
                                FileTile(
                                    file = file,
                                    favourite = file.path in favourites,
                                    selected = file.path in selection,
                                    onClick = { onClick(file) },
                                    onLongClick = { onLongClick(file) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                            items(shown, key = { it.path }) { file ->
                                FileRow(
                                    file = file,
                                    favourite = file.path in favourites,
                                    selected = file.path in selection,
                                    selecting = selecting,
                                    onClick = { onClick(file) },
                                    onLongClick = { onLongClick(file) },
                                    onAction = { actions.run(it, listOf(file)) },
                                    subtitle = if (location is FilesLocation.Folder) {
                                        null
                                    } else {
                                        subtitleOf(context, file) + " · " + file.parent.substringAfterLast('/')
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (settings) {
        FilesSettingsDialog(onDismiss = {
            settings = false
            model.refresh()
        })
    }
    actions.Dialogs()
}

private fun DeviceFilesScreenModel.State.rootLabel(path: String): String? =
    roots.firstOrNull { it.path == path }?.label

@Composable
private fun SortMenu(sort: FileSort, descending: Boolean) {
    val prefs = DeviceFilesPreferences.instance
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FileSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                    onClick = {
                        // Picking the current order again flips it
                        if (option == sort) prefs.sortDescending.set(!descending) else prefs.sort.set(option.name)
                        open = false
                    },
                    trailingIcon = if (option == sort) {
                        { Text(if (descending) "↓" else "↑", fontWeight = FontWeight.Bold) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** The folder's path from its storage device, each part a step back up */
@Composable
private fun Breadcrumbs(path: String, roots: List<StorageRoot>, navigator: Navigator) {
    val root = roots.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
    val steps = remember(path, root) {
        if (root == null) {
            listOf(path.substringAfterLast('/') to path)
        } else {
            val parts = path.removePrefix(root.path).split('/').filter { it.isNotEmpty() }
            listOf(root.label to root.path) + parts.mapIndexed { i, name ->
                name to root.path + "/" + parts.take(i + 1).joinToString("/")
            }
        }
    }
    // Scrolled to the end, so the current folder shows
    val scroll = rememberScrollState()
    LaunchedEffect(steps, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp),
    ) {
        steps.forEachIndexed { i, (name, stepPath) ->
            if (i > 0) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val last = i == steps.lastIndex
            TextButton(
                onClick = { if (!last) navigator.openLocation(FilesLocation.Folder(stepPath)) },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    name,
                    color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

// Actions on files, shared by the pages

private class FileActionsState(
    private val model: DeviceFilesScreenModel,
    private val context: android.content.Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var renaming by mutableStateOf<DeviceFile?>(null)
    var deleting by mutableStateOf<List<DeviceFile>?>(null)
    var info by mutableStateOf<DeviceFile?>(null)
    private var afterDelete: (() -> Unit)? = null

    fun open(file: DeviceFile, navigator: Navigator) {
        if (file.isDirectory) {
            navigator.openLocation(FilesLocation.Folder(file.path))
        } else {
            scope.launch { DeviceFilesOpener.open(context, file.file) }
        }
    }

    fun run(action: FileAction, files: List<DeviceFile>, onDone: (() -> Unit)? = null) {
        if (files.isEmpty()) return
        when (action) {
            FileAction.OpenWith -> DeviceFilesOpener.openWith(context, files.first().file, chooser = true)
            FileAction.Share -> DeviceFilesOpener.share(context, files.filterNot { it.isDirectory }.map { it.file })
            FileAction.Favourite, FileAction.Unfavourite -> model.toggleFavourites(files)
            FileAction.Rename -> renaming = files.first()
            FileAction.Delete -> {
                afterDelete = onDone
                deleting = files
            }
            FileAction.Info -> info = files.first()
        }
    }

    @Composable
    fun Dialogs() {
        renaming?.let { file ->
            RenameDialog(file, onDismiss = { renaming = null }) { name ->
                scope.launch {
                    if (model.rename(file, name)) {
                        renaming = null
                    } else {
                        context.toast("Couldn't rename to \"$name\"")
                    }
                }
            }
        }
        deleting?.let { files ->
            DeleteDialog(files, onDismiss = { deleting = null }) {
                deleting = null
                scope.launch {
                    val done = model.delete(files)
                    // KMK: says what did go, since part of a selection (or of a folder) may be gone already
                    if (done < files.size) context.toast("Deleted $done of ${files.size}")
                    afterDelete?.invoke()
                    afterDelete = null
                }
            }
        }
        info?.let { InfoDialog(it, onDismiss = { info = null }) }
    }
}

@Composable
private fun rememberFileActionsState(model: DeviceFilesScreenModel): FileActionsState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(model) { FileActionsState(model, context, scope) }
}
