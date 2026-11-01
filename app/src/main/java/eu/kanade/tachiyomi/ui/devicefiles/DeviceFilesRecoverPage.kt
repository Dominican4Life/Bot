package eu.kanade.tachiyomi.ui.devicefiles

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.devicefiles.DeviceFilesDeletionLog
import eu.kanade.tachiyomi.devicefiles.DeviceFilesPreferences
import eu.kanade.tachiyomi.devicefiles.DeviceFilesRecover
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * KMK (r97): where to look for a file that was deleted for good. The app can't undelete anything itself (see the
 * note at the bottom of the page), so this page points at the device's own bins, searches the app's folders and
 * caches for a copy, and shows what Device files deleted (from [DeviceFilesDeletionLog]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecoverPage(navigator: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val binApps = remember { DeviceFilesRecover.installedBinApps(context) }
    val roots = remember { DeviceFilesRecover.scanRoots(context) }
    var logVersion by remember { mutableStateOf(0) }
    val log by produceState(emptyList<DeviceFilesDeletionLog.Op>(), logVersion) {
        value = withContext(Dispatchers.IO) { DeviceFilesDeletionLog.read(context) }
    }
    var query by remember { mutableStateOf("") }
    var sizeText by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf<List<DeviceFilesRecover.Match>?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val runSearch: () -> Unit = {
        searchJob?.cancel()
        val size = sizeText.trim().toLongOrNull()
        if (query.isNotBlank() || (size != null && size > 0)) {
            searching = true
            searchJob = scope.launch {
                val found = withContext(Dispatchers.IO) {
                    DeviceFilesRecover.searchCaches(context, query, size) { !isActive }
                }
                matches = found
                searching = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Recover deleted files") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item { SectionHeader("First: this app's Trash") }
            item {
                RecoverCard {
                    Text(
                        "Files deleted in Device files since r97 go to Trash on the main page and can be restored " +
                            "from there for ${DeviceFilesPreferences.instance.trashDays.get()} days. " +
                            "This page is for everything else.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item { SectionHeader("Other apps' bins") }
            if (binApps.isEmpty()) {
                item {
                    RecoverCard {
                        Text(
                            "No app with its own recycle bin is installed (Samsung My Files, Samsung Gallery, Files by Google, " +
                                "Google Photos).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            items(binApps, key = { it.packageName }) { app ->
                RecoverCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                app.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            if (!DeviceFilesRecover.openBinApp(context, app)) context.toast("Couldn't open ${app.title}")
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Open", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            item {
                Text(
                    "Also worth a look: Google Photos keeps backed-up pictures in its Trash for 60 days, Files by Google " +
                        "keeps its own Trash for 30 days, and anything synced to a cloud drive (Google Drive, OneDrive, " +
                        "Dropbox) has a bin on the web. A file deleted with a USB cable or another file manager is only in " +
                        "that manager's bin, if it has one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { SectionHeader("Search this app's folders") }
            item {
                RecoverCard {
                    Text(
                        "A copy of the file may still be in the app's own folders: " +
                            roots.joinToString(", ") { it.label }.ifEmpty { "none found" } + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Part of the name") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                            modifier = Modifier.weight(1.6f),
                        )
                        OutlinedTextField(
                            value = sizeText,
                            onValueChange = { sizeText = it.filter { c -> c.isDigit() } },
                            label = { Text("Bytes") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(
                            when {
                                searching -> "Searching…"
                                matches == null -> "Matches by name, or by exact size"
                                matches!!.isEmpty() -> "No copy found"
                                else -> "${matches!!.size} found"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        TextButton(onClick = runSearch, enabled = !searching && (query.isNotBlank() || sizeText.isNotBlank())) {
                            Text("Search")
                        }
                    }
                }
            }
            items(matches.orEmpty(), key = { "m-" + it.file.path }) { match ->
                FileRow(
                    file = match.file,
                    favourite = false,
                    selected = false,
                    selecting = true,
                    onClick = {},
                    onLongClick = {},
                    onAction = {},
                    subtitle = formatSize(context, match.file.size) + " · " + match.where +
                        (if (match.bySize) " · same size" else ""),
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = {
                        scope.launch {
                            // a cache entry has no useful name: the searched name is used when the match was by size
                            val name = if (match.bySize && query.isNotBlank()) query.trim() else match.file.name
                            val copy = withContext(Dispatchers.IO) {
                                DeviceFilesRecover.copyToRecovered(context, match.file.file, name)
                            }
                            context.toast(if (copy != null) "Copied to Downloads/Recovered/${copy.name}" else "Couldn't copy")
                            logVersion++
                        }
                    }) { Text("Copy to Downloads/Recovered") }
                }
            }

            item { SectionHeader("Deleted by Device files") }
            if (log.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. From r97 on, every delete, trash move and restore is listed here (the last 200).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(log, key = { "${it.time}-${it.path}" }) { op ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(op.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(op.action)
                                append(" · ")
                                append(op.result)
                                append(" · ")
                                append(formatSize(context, op.size))
                                if (op.files > 0) append(" · ${op.files} files")
                                append(" · ")
                                append(
                                    DateUtils.formatDateTime(
                                        context,
                                        op.time,
                                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                                    ),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            op.path.substringBeforeLast('/'),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (op.files == 0) {
                        TextButton(onClick = {
                            query = op.name
                            sizeText = op.size.toString()
                            runSearch()
                        }) { Text("Search caches") }
                    }
                }
            }

            item { SectionHeader("Why the app can't do more") }
            item {
                RecoverCard {
                    Text(
                        "When a file is deleted for good, Android only forgets where its bytes were; the bytes stay until " +
                            "something writes over them. Getting them back needs raw access to the storage chip, which no " +
                            "app has on a normal phone (and the storage is encrypted, so even a computer sees only noise " +
                            "without the phone's keys). The only real safety nets are a bin that moves the file instead of " +
                            "deleting it (this app's Trash, Samsung's, Google's) and backups.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecoverCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}
