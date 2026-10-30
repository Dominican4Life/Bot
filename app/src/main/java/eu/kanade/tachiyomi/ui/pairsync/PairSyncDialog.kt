package eu.kanade.tachiyomi.ui.pairsync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.pairsync.PairSync
import eu.kanade.tachiyomi.pairsync.PairSyncData
import eu.kanade.tachiyomi.pairsync.PairSyncDiscovery
import eu.kanade.tachiyomi.pairsync.PairSyncInstall
import eu.kanade.tachiyomi.pairsync.PairSyncPreferences
import eu.kanade.tachiyomi.pairsync.Peer
import eu.kanade.tachiyomi.pairsync.SyncSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.presentation.core.util.collectAsState as collectPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** The Bluetooth permissions this needs, by Android version */
private fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )
} else {
    // Finding nearby devices needs location on Android 11 and older
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun hasPermissions(context: Context) = bluetoothPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/** The pop-up's pages; back goes to [parent] */
private enum class Page(val parent: Page?) {
    Main(null),
    Anime(Main),
    Manga(Main),
    History(Main),
    Extensions(Main),
    Repos(Extensions),
    CodeSources(Extensions),
    PairNew(Main),
    ShowCode(PairNew),
    EnterCode(PairNew),
    Activity(Main),
}

/**
 * KMK (r89): Sync with another device over Bluetooth, as a pop-up over the current screen (it was a full screen on
 * the tablet rail only, so phones had no way in): pair once with a 6-digit code, pick what to sync, and press Sync.
 * Both devices end up with everything from both. The other device needs this pop-up open while syncing: this
 * device listens for it while the pop-up shows.
 */
@SuppressLint("MissingPermission")
@Composable
fun PairSyncDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val adapter = remember { PairSync.adapter(context) }
    val prefs = PairSyncPreferences.instance
    val selectionJson by prefs.selectionJson.collectPreference()
    val selection = remember(selectionJson) { PairSyncPreferences.parseSelection(selectionJson) }

    var page by rememberSaveable { mutableStateOf(Page.Main) }
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasPermissions(context) }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        enabled = adapter?.isEnabled == true
    }

    // Listen for the other device while the pop-up is open and Bluetooth is ready
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasPermissions(context)
        enabled = adapter?.isEnabled == true
    }
    val ready = adapter != null && granted && enabled
    DisposableEffect(ready) {
        if (ready) PairSync.startListening(context.applicationContext)
        onDispose { PairSync.stopListening() }
    }

    val mangaCategories by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { Injekt.get<GetCategories>().await().map { it.name } }.getOrDefault(emptyList())
        }
    }
    val animeCategories by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { Injekt.get<AnimeRepository>().getCategories().map { it.name } }.getOrDefault(emptyList())
        }
    }
    val sources by remember { Injekt.get<JsSourceManager>().savedFlow }.collectAsState()
    val onChange: (SyncSelection) -> Unit = prefs::setSelection

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A sub-page goes back to its parent page; the main page closes the pop-up
        BackHandler(enabled = page != Page.Main) { page = page.parent ?: Page.Main }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                ) {
                    val back = { page = page.parent ?: Page.Main }
                    when (page) {
                        Page.Main -> MainPage(
                            ready = ready,
                            hasAdapter = adapter != null,
                            granted = granted,
                            selection = selection,
                            mangaCategories = mangaCategories,
                            animeCategories = animeCategories,
                            onAllow = { permissionLauncher.launch(bluetoothPermissions()) },
                            onTurnOn = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                            onChange = onChange,
                            onOpen = { page = it },
                            onClose = onDismissRequest,
                        )
                        Page.Anime -> {
                            PageTitle("Anime", back)
                            LibraryPage(
                                icon = Icons.Outlined.Movie,
                                title = "Sync anime library",
                                checked = selection.animeLibrary,
                                onCheckedChange = { onChange(selection.copy(animeLibrary = it)) },
                                categories = animeCategories,
                                picked = selection.animeCategories,
                                onPick = { onChange(selection.copy(animeCategories = it)) },
                                note = "Seen episodes and where you stopped come along. Anime the other device " +
                                    "doesn't have goes into the same category there.",
                            )
                        }
                        Page.Manga -> {
                            PageTitle("Manga", back)
                            LibraryPage(
                                icon = Icons.Outlined.CollectionsBookmark,
                                title = "Sync manga library",
                                checked = selection.mangaLibrary,
                                onCheckedChange = { onChange(selection.copy(mangaLibrary = it)) },
                                categories = mangaCategories,
                                picked = selection.mangaCategories,
                                onPick = { onChange(selection.copy(mangaCategories = it)) },
                                note = "Read chapters, bookmarks and page progress come along. Progress keeps " +
                                    "whichever device is further.",
                            )
                        }
                        Page.History -> {
                            PageTitle("History", back)
                            HistoryPage(selection, onChange)
                        }
                        Page.Extensions -> {
                            PageTitle("Extensions", back)
                            ExtensionsPage(selection, sources.size, onChange, onOpen = { page = it })
                        }
                        Page.Repos -> {
                            PageTitle("Extensions › Repos", back)
                            ReposPage(selection, onChange)
                        }
                        Page.CodeSources -> {
                            PageTitle("Extensions › Code sources", back)
                            ChipChoices(
                                options = sources.map {
                                    it.key to "${it.name} (${if (it.anime) "anime" else "manga"})"
                                },
                                picked = selection.sourceKeys,
                                onPick = { onChange(selection.copy(sourceKeys = it)) },
                            )
                            if (sources.isEmpty()) Note("No sources made in the Code editor yet.")
                        }
                        Page.PairNew -> {
                            PageTitle("Pair new device", back)
                            PairPage(ready = ready, onOpen = { page = it })
                        }
                        Page.ShowCode -> {
                            PageTitle("Pair new device", back)
                            ShowCodePage(onPaired = { page = Page.Main }, onCancel = back)
                        }
                        Page.EnterCode -> {
                            PageTitle("Pair new device", back)
                            EnterCodePage(onPaired = { page = Page.Main }, onCancel = back)
                        }
                        Page.Activity -> {
                            PageTitle("Activity", back)
                            ActivityPage()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainPage(
    ready: Boolean,
    hasAdapter: Boolean,
    granted: Boolean,
    selection: SyncSelection,
    mangaCategories: List<String>,
    animeCategories: List<String>,
    onAllow: () -> Unit,
    onTurnOn: () -> Unit,
    onChange: (SyncSelection) -> Unit,
    onOpen: (Page) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = PairSyncPreferences.instance
    val status by PairSync.status.collectAsState()
    val result by PairSync.result.collectAsState()
    val peersVersion by remember { prefs.peersChanges }.collectAsState(initial = "")
    val peers = remember(peersVersion) { prefs.peers() }
    var removing by remember { mutableStateOf<Peer?>(null) }

    // Header
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                Icons.Outlined.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(10.dp).size(24.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Sync devices", style = MaterialTheme.typography.titleLarge)
            if (ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text(
                        "${PairSync.deviceName(context)} · ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
    }
    when {
        !hasAdapter -> Notice("This device has no Bluetooth, so it can't sync this way.")
        !granted -> Notice(
            "Sync needs permission to use Bluetooth and find nearby devices.",
            action = "Allow" to onAllow,
        )
        !ready -> Notice("Bluetooth is off.", action = "Turn on" to onTurnOn)
    }

    // Paired devices
    SectionLabel("Paired devices")
    if (peers.isEmpty()) {
        Note("None yet. Tap Pair device below, once; after that, Sync works straight away.")
    }
    peers.forEach { peer ->
        PeerRow(
            peer = peer,
            status = status,
            syncedAt = result?.takeIf { it.peerAddress == peer.address }?.time,
            canSync = ready && !status.busy && !selection.isEmpty,
            onSync = { PairSync.sync(context.applicationContext, peer, selection) },
            onUnpair = { removing = peer },
        )
    }
    result?.let { ResultCard(it) }

    // What to sync
    SectionLabel("What to sync")
    val tilesEnabled = !status.busy
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(
            icon = Icons.Outlined.Movie,
            title = "Anime",
            subtitle = categorySubtitle(selection.animeLibrary, selection.animeCategories, animeCategories.size + 1),
            checked = selection.animeLibrary,
            enabled = tilesEnabled,
            onToggle = { onChange(selection.copy(animeLibrary = !selection.animeLibrary)) },
            onOpen = { onOpen(Page.Anime) },
            modifier = Modifier.weight(1f),
        )
        Tile(
            icon = Icons.Outlined.CollectionsBookmark,
            title = "Manga",
            subtitle = categorySubtitle(
                selection.mangaLibrary,
                selection.mangaCategories,
                (listOf("") + mangaCategories).distinct().size,
            ),
            checked = selection.mangaLibrary,
            enabled = tilesEnabled,
            onToggle = { onChange(selection.copy(mangaLibrary = !selection.mangaLibrary)) },
            onOpen = { onOpen(Page.Manga) },
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val historyOn = selection.mangaHistory || selection.animeHistory
        Tile(
            icon = Icons.Outlined.History,
            title = "History",
            subtitle = when {
                selection.mangaHistory && selection.animeHistory -> "Anime & manga"
                selection.animeHistory -> "Anime"
                selection.mangaHistory -> "Manga"
                else -> "Off"
            },
            checked = historyOn,
            enabled = tilesEnabled,
            onToggle = { onChange(selection.copy(mangaHistory = !historyOn, animeHistory = !historyOn)) },
            onOpen = { onOpen(Page.History) },
            modifier = Modifier.weight(1f),
        )
        Tile(
            icon = Icons.Outlined.Extension,
            title = "Extensions",
            subtitle = if (!selection.extensions) {
                "Off"
            } else {
                listOfNotNull(
                    "Repos".takeIf { selection.repos },
                    "installed".takeIf { selection.installedExtensions },
                    "APKs".takeIf { selection.sideloadedApks },
                    "code".takeIf { selection.sources },
                ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Nothing picked" }
            },
            checked = selection.extensions,
            enabled = tilesEnabled,
            onToggle = { onChange(selection.copy(extensions = !selection.extensions)) },
            onOpen = { onOpen(Page.Extensions) },
            modifier = Modifier.weight(1f),
        )
    }

    // Footer
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedButton(
            onClick = { onOpen(Page.PairNew) },
            enabled = ready && !status.busy,
            modifier = Modifier.weight(1f),
        ) { Text("+ Pair device") }
        FilledTonalButton(onClick = { onOpen(Page.Activity) }, modifier = Modifier.weight(1f)) { Text("Activity") }
    }
    Note("The other device needs this pop-up open while you sync.")

    removing?.let { peer ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Unpair ${peer.name}?") },
            text = { Text("To sync with it again, you'll pair the two with a code again. Nothing synced is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    prefs.removePeer(peer.address)
                    removing = null
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

private fun categorySubtitle(on: Boolean, picked: Set<String>?, total: Int): String = when {
    !on -> "Off"
    picked == null -> "All categories"
    else -> "${picked.size} of $total categories"
}

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1024f / 1024f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeerRow(
    peer: Peer,
    status: PairSync.Status,
    syncedAt: Long?,
    canSync: Boolean,
    onSync: () -> Unit,
    onUnpair: () -> Unit,
) {
    val syncing = status.busy && status.peerAddress == peer.address
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { if (!syncing) menu = true })
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(Icons.Outlined.Devices)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(peer.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    val subtitle = when {
                        syncing && status.total > 0L ->
                            "${if (status.sending) "Sending" else "Receiving"} ${mb(status.done)} of " +
                                "${mb(status.total)} MB…"
                        syncing -> status.step ?: "Syncing…"
                        syncedAt != null -> "✓ Synced " + if (System.currentTimeMillis() - syncedAt < 60_000L) {
                            "just now"
                        } else {
                            DateUtils.getRelativeTimeSpanString(syncedAt).toString()
                        }
                        peer.lastSync > 0L -> "Last synced " + DateUtils.getRelativeTimeSpanString(peer.lastSync)
                        else -> "Not synced yet"
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (syncedAt != null && !syncing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (syncing) {
                    TextButton(onClick = PairSync::stopSync) { Text("Stop") }
                } else {
                    Button(onClick = onSync, enabled = canSync) { Text("Sync") }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Unpair") },
                                onClick = {
                                    menu = false
                                    onUnpair()
                                },
                            )
                        }
                    }
                }
            }
            if (syncing) {
                if (status.total > 0L) {
                    LinearProgressIndicator(
                        progress = { (status.done.toFloat() / status.total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }
        }
    }
}

/** What the last sync brought, and the extensions it can install here (only when tapped) */
@Composable
private fun ResultCard(result: PairSync.SyncResult) {
    val context = LocalContext.current
    val summary = result.summary
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Got from ${result.peerName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${summary.anime} anime · ${summary.manga} manga · ${summary.historyEntries} history entries",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${summary.reposAdded} repos · ${summary.sourcesAdded} code sources",
                style = MaterialTheme.typography.bodySmall,
            )
            if (summary.errors > 0) {
                Text(
                    "${summary.errors} couldn't be added (see Activity)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    val toInstall = summary.missing.size + summary.apks.size
    if (toInstall > 0) {
        OfferRow(
            icon = Icons.Outlined.Download,
            title = if (toInstall == 1) "1 extension missing" else "$toInstall extensions missing",
            subtitle = if (result.missingAsked) {
                "Installing; each install asks first"
            } else {
                val apks = summary.apks.size
                listOfNotNull(
                    summary.missing.size.takeIf { it > 0 }?.let { "$it from repos" },
                    (if (apks == 1) "1 sideloaded APK" else "$apks sideloaded APKs").takeIf { apks > 0 },
                ).joinToString(" · ")
            },
            action = "Install".takeUnless { result.missingAsked },
            onAction = {
                PairSync.markAsked(missing = true)
                PairSyncInstall.installMissing(summary.missing)
                PairSyncInstall.installApks(context, summary.apks)
            },
        )
    }
    if (summary.updates.isNotEmpty()) {
        OfferRow(
            icon = Icons.Outlined.Extension,
            title = if (summary.updates.size == 1) "1 newer version" else "${summary.updates.size} newer versions",
            subtitle = if (result.updatesAsked) {
                "Updating"
            } else {
                summary.updates.take(3).joinToString { "${it.name} ${it.fromVersion} → ${it.toVersion}" } +
                    if (summary.updates.size > 3) "…" else ""
            },
            action = "Update".takeUnless { result.updatesAsked },
            onAction = {
                PairSync.markAsked(updates = true)
                PairSyncInstall.installUpdates(summary.updates)
            },
        )
    }
}

@Composable
private fun OfferRow(icon: ImageVector, title: String, subtitle: String, action: String?, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Avatar(icon)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/** A "What to sync" tile: tap it to turn it on or off, tap its subtitle to open its page */
@Composable
private fun Tile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = modifier.alpha(if (enabled) 1f else 0.55f),
    ) {
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onToggle)
                    .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (checked) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.size(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "$subtitle ›",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled, onClick = onOpen)
                        .padding(vertical = 8.dp),
                )
            }
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                if (checked) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "On",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Box(
                        Modifier
                            .padding(2.dp)
                            .size(18.dp)
                            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryPage(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    categories: List<String>,
    picked: Set<String>?,
    onPick: (Set<String>?) -> Unit,
    note: String,
) {
    // Default ("") first: entries in no category
    val options = (listOf("") + categories).distinct()
    SwitchCard(
        icon = icon,
        title = title,
        subtitle = when (picked) {
            null -> "All ${options.size} categories"
            else -> "${picked.size} of ${options.size} categories"
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
        highlighted = true,
    )
    if (checked) {
        SectionLabel("Categories")
        ChipChoices(
            options = options.map { it to it.ifEmpty { "Default" } },
            picked = picked,
            onPick = onPick,
        )
    }
    Note(note)
}

@Composable
private fun HistoryPage(selection: SyncSelection, onChange: (SyncSelection) -> Unit) {
    val on = selection.mangaHistory || selection.animeHistory
    SwitchCard(
        icon = Icons.Outlined.History,
        title = "Sync history",
        subtitle = "Recently watched and read",
        checked = on,
        onCheckedChange = { onChange(selection.copy(mangaHistory = it, animeHistory = it)) },
        highlighted = true,
    )
    SectionLabel("Which")
    val chosen = when {
        selection.mangaHistory && selection.animeHistory -> 0
        selection.animeHistory -> 1
        selection.mangaHistory -> 2
        else -> -1
    }
    MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("Both", "Anime", "Manga").forEachIndexed { index, label ->
            SegmentedButton(
                checked = chosen == index,
                onCheckedChange = {
                    onChange(
                        selection.copy(
                            animeHistory = index == 0 || index == 1,
                            mangaHistory = index == 0 || index == 2,
                        ),
                    )
                },
                enabled = on,
                shape = SegmentedButtonDefaults.itemShape(index, 3),
            ) { Text(label) }
        }
    }
    Note("History only adds entries; it never adds titles to the library.")
}

@Composable
private fun ExtensionsPage(
    selection: SyncSelection,
    sourceCount: Int,
    onChange: (SyncSelection) -> Unit,
    onOpen: (Page) -> Unit,
) {
    val repos by produceState(emptyList<Triple<String, String, Boolean>>()) {
        value = withContext(Dispatchers.IO) { runCatching { PairSyncData.repos() }.getOrDefault(emptyList()) }
    }
    val sideloaded = remember { runCatching { PairSyncData.sideloaded().size }.getOrDefault(0) }
    val on = selection.extensions
    SwitchCard(
        icon = Icons.Outlined.Extension,
        title = "Sync extensions",
        subtitle = "Repos, installed extensions, APKs and code sources",
        checked = on,
        onCheckedChange = { onChange(selection.copy(extensions = it)) },
        highlighted = true,
    )
    SectionLabel("From repos")
    val repoUrls = repos.map { it.first }.distinct()
    SwitchCard(
        icon = Icons.Outlined.Storage,
        title = "Repos",
        subtitle = when (val picked = selection.repoUrls) {
            null -> "All ${repoUrls.size} · anime & manga"
            else -> "${repoUrls.count { it in picked }} of ${repoUrls.size} · anime & manga"
        },
        checked = selection.repos,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(repos = it)) },
        onOpen = { onOpen(Page.Repos) },
    )
    SwitchCard(
        icon = Icons.Outlined.Extension,
        title = "Installed extensions",
        subtitle = "Offer missing ones and newer versions",
        checked = selection.installedExtensions,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(installedExtensions = it)) },
    )
    SectionLabel("Not from a repo")
    SwitchCard(
        icon = Icons.Outlined.Android,
        title = "Sideloaded APKs",
        subtitle = "${if (sideloaded == 1) "1 APK" else "$sideloaded APKs"} · only ones the other lacks",
        checked = selection.sideloadedApks,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(sideloadedApks = it)) },
    )
    SwitchCard(
        icon = Icons.Outlined.Code,
        title = "Code sources",
        subtitle = when (val picked = selection.sourceKeys) {
            null -> "All $sourceCount sources"
            else -> "${picked.size} of $sourceCount sources"
        },
        checked = selection.sources,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(sources = it)) },
        onOpen = { onOpen(Page.CodeSources) },
    )
    Note("Installs always ask you first. Nothing is removed on either device.")
}

@Composable
private fun ReposPage(selection: SyncSelection, onChange: (SyncSelection) -> Unit) {
    val repos by produceState<List<Triple<String, String, Boolean>>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { PairSyncData.repos() }.getOrDefault(emptyList()) }
    }
    val list = repos
    if (list == null) {
        CircularProgressIndicator(Modifier.size(24.dp))
        return
    }
    if (list.isEmpty()) {
        Note("No extension repos on this device yet.")
        return
    }
    val all = list.map { it.first }.toSet()
    val picked = selection.repoUrls
    val toggle = { url: String ->
        val now = picked ?: all
        val next = if (url in now) now - url else now + url
        // Every repo picked means all (repos added later come along too)
        onChange(selection.copy(repoUrls = if (next.containsAll(all)) null else next))
    }
    listOf(true to "Anime", false to "Manga").forEach { (anime, label) ->
        val group = list.filter { it.third == anime }
        if (group.isEmpty()) return@forEach
        SectionLabel(label)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                group.forEach { (url, name) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggle(url) }
                            .padding(start = 14.dp, end = 4.dp),
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(name.ifBlank { url }, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                url.removePrefix("https://"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Checkbox(checked = picked == null || url in picked, onCheckedChange = { toggle(url) })
                    }
                }
            }
        }
    }
    Note("Only repos missing on the other device are added there. None are removed.")
}

@Composable
private fun PairPage(ready: Boolean, onOpen: (Page) -> Unit) {
    val status by PairSync.status.collectAsState()
    val can = ready && !status.busy
    OptionCard(
        icon = Icons.Outlined.Bluetooth,
        title = "Show a code",
        subtitle = "On this device. The other device then enters it.",
        enabled = can,
        onClick = { onOpen(Page.ShowCode) },
    )
    OptionCard(
        icon = Icons.Outlined.Devices,
        title = "Enter a code",
        subtitle = "The other device shows a code; pick it here and type the code.",
        enabled = can,
        onClick = { onOpen(Page.EnterCode) },
    )
    Note("Pair once: after that, Sync works straight away. Both devices need Bluetooth on and Sync open.")
}

@Composable
private fun ShowCodePage(onPaired: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val code by PairSync.pairingCode.collectAsState()
    val name = remember { PairSyncDiscovery.bluetoothName(context) }
    val before = remember { PairSync.justPaired.value }
    var left by remember { mutableIntStateOf(300) }

    val start = {
        PairSync.startPairing(context.applicationContext)
        // Let the other device find this one for a few minutes
        runCatching {
            context.startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
            )
        }
        Unit
    }
    DisposableEffect(Unit) {
        start()
        onDispose { PairSync.stopPairing() }
    }
    LaunchedEffect(Unit) {
        PairSync.justPaired.first { it != null && it != before }
        onPaired()
    }
    LaunchedEffect(code) {
        if (code == null) return@LaunchedEffect
        left = 300
        while (left > 0) {
            delay(1000)
            left--
        }
        // Other devices stop seeing this one after five minutes
        PairSync.stopPairing()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                Icons.Outlined.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(18.dp).size(28.dp),
            )
        }
        Text("Pairing code", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
        val shown = code
        if (shown != null) {
            Text(
                shown.chunked(3).joinToString(" "),
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                "On the other device tap Pair device → Enter a code. This device shows there as",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(name, fontWeight = FontWeight.SemiBold)
                    KomikkuTag(Modifier.padding(start = 8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    "Waiting for the other device… ${left / 60}:${(left % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        } else {
            Text(
                "Time's up: the other device may no longer see this one.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Button(onClick = start) { Text("Show a new code") }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.padding(top = 12.dp)) { Text("Cancel") }
    }
}

/** Finds nearby devices (and ones already paired in Android's Bluetooth settings), then pairs with a code */
@SuppressLint("MissingPermission")
@Composable
private fun EnterCodePage(onPaired: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adapter = remember { PairSync.adapter(context) }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    val leScan = remember { PairSyncDiscovery.Scan(context.applicationContext) }
    val hashes by leScan.hashes.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<BluetoothDevice?>(null) }
    var code by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var showOthers by remember { mutableStateOf(false) }

    DisposableEffect(round) {
        runCatching {
            adapter?.bondedDevices.orEmpty().forEach { if (found.none { f -> f.address == it.address }) found += it }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (device != null && found.none { it.address == device.address }) found.add(0, device)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        scanning = runCatching { adapter?.startDiscovery() == true }.getOrDefault(false)
        // KMK (r89): alongside, a Bluetooth LE scan for devices showing a code (they tag themselves)
        leScan.start()
        onDispose {
            leScan.stop()
            runCatching { adapter?.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val named = found.map { it to runCatching { it.name }.getOrNull() }
    val tagged = named.filter { (_, name) -> name != null && PairSyncDiscovery.nameHash(name) in hashes }
    val rest = named - tagged.toSet()
    // Without a tagged device, phones and computers stay in view; headphones, TVs, watches and the like are hidden
    val likely = if (tagged.isEmpty()) rest.filterNot { (d, _) -> PairSyncDiscovery.isUnlikely(d) } else emptyList()
    val hidden = rest - likely.toSet()
    LaunchedEffect(tagged.size) {
        if (chosen == null && tagged.size == 1) chosen = tagged.first().first
    }

    Text("Pick the other device", style = MaterialTheme.typography.titleLarge)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (scanning) {
                "Looking for devices with Komikku open…"
            } else {
                "On the other device tap Pair device → Show a code first."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (scanning) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = { round++ }) { Icon(Icons.Outlined.Refresh, "Look again") }
        }
    }

    if (tagged.isNotEmpty()) {
        SectionLabel("Komikku nearby")
        tagged.forEach { (d, name) ->
            DeviceRow(d, name, "Showing a code", tagged = true, selected = chosen?.address == d.address) {
                chosen = d
                error = null
            }
        }
    }
    if (likely.isNotEmpty()) {
        SectionLabel("Nearby devices")
        likely.forEach { (d, name) ->
            DeviceRow(d, name, d.address, tagged = false, selected = chosen?.address == d.address) {
                chosen = d
                error = null
            }
        }
    }
    if (hidden.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showOthers = !showOthers }
                .padding(vertical = 8.dp, horizontal = 4.dp),
        ) {
            Icon(
                if (showOthers) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${hidden.size} other Bluetooth ${if (hidden.size == 1) "device" else "devices"} " +
                    (if (showOthers) "" else "hidden ") + "(headphones, TVs, watches…)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (showOthers) {
            hidden.forEach { (d, name) ->
                DeviceRow(d, name, d.address, tagged = false, selected = chosen?.address == d.address) {
                    chosen = d
                    error = null
                }
            }
        }
    }

    val device = chosen
    SectionLabel("Enter its code")
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6) },
        singleLine = true,
        enabled = !pairing,
        placeholder = { Text("6-digit code") },
        supportingText = {
            if (device != null) Text("Showing on ${runCatching { device.name }.getOrNull() ?: device.address}")
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
    if (pairing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Pairing…", Modifier.padding(start = 10.dp))
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedButton(onClick = onCancel, enabled = !pairing, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(
            onClick = {
                device?.let { target ->
                    pairing = true
                    error = null
                    scope.launch {
                        val result = PairSync.pair(context.applicationContext, target, code)
                        pairing = false
                        if (result.isSuccess) onPaired() else error = result.exceptionOrNull()?.message
                    }
                }
            },
            enabled = device != null && code.length == 6 && !pairing,
            modifier = Modifier.weight(1f),
        ) { Text("Pair") }
    }
}

@Composable
private fun DeviceRow(
    device: BluetoothDevice,
    name: String?,
    subtitle: String,
    tagged: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = if (selected) {
            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
        } else {
            Modifier
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        ) {
            Avatar(Icons.Outlined.Devices)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(name ?: "Unnamed device", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    subtitle.ifBlank { device.address },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tagged) KomikkuTag()
        }
    }
}

@Composable
private fun ActivityPage() {
    val status by PairSync.status.collectAsState()
    if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (status.lines.isEmpty()) {
        Note("Nothing yet. Pairing and sync steps show here (and in Logs, tag sync).")
    } else {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                status.lines.reversed().forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
        if (!status.busy) {
            TextButton(onClick = PairSync::clearLog) { Text("Clear") }
        }
    }
}

@Composable
private fun PageTitle(text: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
        }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun Notice(text: String, action: Pair<String, () -> Unit>? = null) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Text(text, modifier = Modifier.weight(1f))
            action?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
        }
    }
}

@Composable
private fun Avatar(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun KomikkuTag(modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary, modifier = modifier) {
        Text(
            "KOMIKKU",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** A big choice on the Pair page */
@Composable
private fun OptionCard(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.alpha(if (enabled) 1f else 0.55f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(14.dp),
        ) {
            Avatar(icon)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A switch row; with [onOpen], a "›" opens its page */
@Composable
private fun SwitchCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier.alpha(if (enabled) 1f else 0.55f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (onOpen == null) 12.dp else 0.dp),
        ) {
            Avatar(icon)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            if (onOpen != null) {
                TextButton(onClick = onOpen, enabled = enabled) {
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/** "All" or some of [options] (value to label); [picked] null means all */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipChoices(
    options: List<Pair<String, String>>,
    picked: Set<String>?,
    onPick: (Set<String>?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        FilterChip(selected = picked == null, onClick = { onPick(null) }, label = { Text("All") })
        options.forEach { (value, label) ->
            val on = picked != null && value in picked
            FilterChip(
                selected = on,
                onClick = {
                    val now = picked.orEmpty()
                    val next = if (on) now - value else now + value
                    // Nothing left picked means back to all
                    onPick(next.ifEmpty { null })
                },
                label = { Text(label) },
            )
        }
    }
}
