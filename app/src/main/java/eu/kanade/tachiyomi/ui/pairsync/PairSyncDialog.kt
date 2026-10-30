package eu.kanade.tachiyomi.ui.pairsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.pairsync.LocalExtension
import eu.kanade.tachiyomi.pairsync.PairSync
import eu.kanade.tachiyomi.pairsync.PairSyncData
import eu.kanade.tachiyomi.pairsync.PairSyncDiscovery
import eu.kanade.tachiyomi.pairsync.PairSyncInstall
import eu.kanade.tachiyomi.pairsync.PairSyncPreferences
import eu.kanade.tachiyomi.pairsync.Peer
import eu.kanade.tachiyomi.pairsync.SyncSelection
import eu.kanade.tachiyomi.pairsync.SyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    // KMK (r90): Android 11 and older need nothing at runtime to listen and sync; location (for finding nearby
    // devices) is asked for only on the Enter a code page, so a denied location no longer blocks everything
    emptyArray()
}

private fun isGranted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasPermissions(context: Context) = bluetoothPermissions().all { isGranted(context, it) }

/** Finding nearby devices needs location on Android 11 and older */
private val needsLocation get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

private fun hasLocation(context: Context) = !needsLocation ||
    isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

private fun isLocationOn(context: Context): Boolean = !needsLocation || runCatching {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    manager == null || LocationManagerCompat.isLocationEnabled(manager)
}.getOrDefault(true)

/** KMK (r90): the Wi-Fi Direct permissions this device still lacks (none below Android 10) */
private fun missingWifi(context: Context): List<String> =
    PairSync.wifiPermissions().filterNot { isGranted(context, it) }

/** KMK (r90): the Wi-Fi Direct permission's name in Android's settings */
private val wifiPermissionName
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Nearby devices" else "Location"

/** KMK (r90): after a refusal, true when Android won't ask again (Allow then opens the app's settings page) */
private fun deniedForGood(activity: Activity?, context: Context, permissions: Array<String>): Boolean =
    activity != null && permissions.any {
        !isGranted(context, it) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
    }

/** KMK (r90): the app's page in Android's settings, where a permission denied for good can be allowed */
private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** The pop-up's pages; back goes to [parent] */
private enum class Page(val parent: Page?) {
    Main(null),
    Anime(Main),
    Manga(Main),
    History(Main),
    Extensions(Main),
    Repos(Extensions),
    InstalledPicker(Extensions),
    ApkPicker(Extensions),
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

    val transportKey by prefs.transport.collectPreference()
    val transport = SyncTransport.of(transportKey)

    // KMK (r90): kept by name across rotation (the open flag in HomeScreen/MoreTab is saveable too)
    var pageName by rememberSaveable { mutableStateOf(Page.Main.name) }
    val page = Page.entries.firstOrNull { it.name == pageName } ?: Page.Main
    val goTo: (Page) -> Unit = { pageName = it.name }
    val activity = LocalActivity.current
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    val status by PairSync.status.collectAsState()
    val busy by remember { derivedStateOf { status.busy } }

    // KMK (r90): a permission denied for good: Allow opens the app's settings page instead (the request did nothing)
    var bluetoothForGood by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = hasPermissions(context)
        bluetoothForGood = !granted && deniedForGood(activity, context, bluetoothPermissions())
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        enabled = adapter?.isEnabled == true
    }
    // KMK (r90): Wi-Fi Direct needs its own permissions on both devices (the waiting one makes the group). Asked
    // before a sync and once while the pop-up listens. Denied: Auto still syncs (Wi-Fi or Bluetooth), a forced
    // Wi-Fi Direct doesn't start (it could only fail); the notice says why.
    var wifiMissing by remember { mutableStateOf(missingWifi(context).isNotEmpty()) }
    var wifiForGood by rememberSaveable { mutableStateOf(false) }
    var askedWifi by rememberSaveable { mutableStateOf(false) }
    val wifiNeeded = transport == SyncTransport.Auto || transport == SyncTransport.WifiDirect
    val afterWifiAsk: () -> Unit = {
        val missing = missingWifi(context).isNotEmpty()
        wifiMissing = missing
        wifiForGood = missing && deniedForGood(activity, context, PairSync.wifiPermissions())
    }
    var pendingSync by rememberSaveable { mutableStateOf<String?>(null) }
    val wifiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterWifiAsk()
        val address = pendingSync
        pendingSync = null
        val forced = SyncTransport.of(prefs.transport.get()) == SyncTransport.WifiDirect
        if (!(forced && wifiMissing)) {
            prefs.peers().firstOrNull { it.address == address }?.let { peer ->
                PairSync.sync(context.applicationContext, peer, prefs.selection())
            }
        }
    }
    val listenWifiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { afterWifiAsk() }
    val startSync: (Peer) -> Unit = { peer ->
        val missing = if (wifiNeeded) missingWifi(context) else emptyList()
        wifiMissing = missing.isNotEmpty()
        // A forced Wi-Fi Direct that Android won't ask for again doesn't start (the notice's Settings is the way)
        val blocked = wifiForGood && transport == SyncTransport.WifiDirect
        if (missing.isEmpty()) {
            PairSync.sync(context.applicationContext, peer, selection)
        } else if (!blocked) {
            pendingSync = peer.address
            wifiLauncher.launch(missing.toTypedArray())
        }
    }
    val allowWifi: () -> Unit = {
        val missing = missingWifi(context)
        wifiMissing = missing.isNotEmpty()
        if (missing.isNotEmpty()) {
            if (wifiForGood) openAppSettings(context) else listenWifiLauncher.launch(missing.toTypedArray())
        }
    }

    // Listen for the other device while the pop-up is open and Bluetooth is ready
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasPermissions(context)
        enabled = adapter?.isEnabled == true
        wifiMissing = missingWifi(context).isNotEmpty()
    }
    // KMK (r90): Bluetooth turned off or on from the quick settings (no ON_RESUME then): listening stops or restarts
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                enabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val ready = adapter != null && granted && enabled
    DisposableEffect(ready) {
        if (ready) PairSync.startListening(context.applicationContext)
        onDispose {
            // KMK (r90): a rotation keeps listening (and a code showing); a real close or Bluetooth off stops it
            if (activity?.isChangingConfigurations != true) PairSync.stopListening()
        }
    }
    // KMK (r90): the waiting device makes the Wi-Fi Direct group, so it's asked here too (once per opening)
    LaunchedEffect(ready, wifiNeeded) {
        if (ready && wifiNeeded && !askedWifi) {
            val missing = missingWifi(context)
            if (missing.isNotEmpty()) {
                askedWifi = true
                listenWifiLauncher.launch(missing.toTypedArray())
            }
        }
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
        // KMK (r90): a tap outside or back can't close the pop-up mid-sync (the X still does)
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
        ),
    ) {
        // A sub-page goes back to its parent page; the main page closes the pop-up
        BackHandler(enabled = page != Page.Main) { goTo(page.parent ?: Page.Main) }
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
                    val back = { goTo(page.parent ?: Page.Main) }
                    when (page) {
                        Page.Main -> MainPage(
                            ready = ready,
                            hasAdapter = adapter != null,
                            granted = granted,
                            selection = selection,
                            transport = transport,
                            mangaCategories = mangaCategories,
                            animeCategories = animeCategories,
                            onTransport = { prefs.transport.set(it.key) },
                            onSync = startSync,
                            onAllow = {
                                if (bluetoothForGood) {
                                    openAppSettings(context)
                                } else {
                                    permissionLauncher.launch(bluetoothPermissions())
                                }
                            },
                            allowLabel = if (bluetoothForGood) "Settings" else "Allow",
                            wifiNotice = wifiNeeded && wifiMissing,
                            onAllowWifi = allowWifi,
                            allowWifiLabel = if (wifiForGood) "Settings" else "Allow",
                            onTurnOn = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                            onChange = onChange,
                            onOpen = goTo,
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
                                note = "Seen episodes and where you stopped come to this device. Anime this " +
                                    "device doesn't have goes into the same category here.",
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
                                note = "Read chapters, bookmarks and page progress come to this device. Progress " +
                                    "keeps whichever device is further.",
                            )
                        }
                        Page.History -> {
                            PageTitle("History", back)
                            HistoryPage(selection, onChange)
                        }
                        Page.Extensions -> {
                            PageTitle("Extensions", back)
                            ExtensionsPage(selection, sources.map { it.key }, onChange, onOpen = goTo)
                        }
                        Page.Repos -> {
                            PageTitle("Extensions › Repos", back)
                            ReposPage(selection, onChange)
                        }
                        Page.InstalledPicker -> {
                            PageTitle("Extensions › Installed", back)
                            ExtensionPicker(
                                fromRepo = true,
                                picked = selection.installedKeys,
                                onPick = { onChange(selection.copy(installedKeys = it)) },
                                note = "Only extensions picked here are offered to the other device.",
                            )
                        }
                        Page.ApkPicker -> {
                            PageTitle("Extensions › Sideloaded APKs", back)
                            ExtensionPicker(
                                fromRepo = false,
                                picked = selection.apkKeys,
                                onPick = { onChange(selection.copy(apkKeys = it)) },
                                note = "Only APKs picked here are sent, and only ones the other device lacks.",
                            )
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
                            Note(
                                if (sources.isEmpty()) {
                                    "No sources made in the Code editor yet."
                                } else {
                                    "Only sources picked here are sent."
                                },
                            )
                        }
                        Page.PairNew -> {
                            PageTitle("Pair new device", back)
                            PairPage(ready = ready, onOpen = goTo)
                        }
                        Page.ShowCode -> {
                            PageTitle("Pair new device", back)
                            ShowCodePage(onPaired = { goTo(Page.Main) }, onCancel = back)
                        }
                        Page.EnterCode -> {
                            PageTitle("Pair new device", back)
                            EnterCodePage(onPaired = { goTo(Page.Main) }, onCancel = back)
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
    transport: SyncTransport,
    mangaCategories: List<String>,
    animeCategories: List<String>,
    onTransport: (SyncTransport) -> Unit,
    onSync: (Peer) -> Unit,
    onAllow: () -> Unit,
    allowLabel: String,
    wifiNotice: Boolean,
    onAllowWifi: () -> Unit,
    allowWifiLabel: String,
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
    // KMK (r90): "Synced just now" ages while the pop-up is open
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }

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
        // KMK (r90): answering a device that isn't in the list (its address changed): no row to stop it from.
        // Pairing is busy too but has no peerAddress: there's no sync to stop then.
        if (status.busy && status.peerAddress != null && peers.none { it.address == status.peerAddress }) {
            TextButton(onClick = PairSync::stopSync) { Text("Stop") }
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
    }
    when {
        !hasAdapter -> Notice("This device has no Bluetooth, so it can't sync this way.")
        !granted -> Notice(
            "Sync needs permission to use Bluetooth and find nearby devices.",
            action = allowLabel to onAllow,
        )
        !ready -> Notice("Bluetooth is off.", action = "Turn on" to onTurnOn)
        // KMK (r90): this device may be the waiting one, which makes the Wi-Fi Direct group
        wifiNotice -> Notice(
            if (transport == SyncTransport.WifiDirect) {
                "Wi-Fi Direct needs the $wifiPermissionName permission on both devices, so Sync can't start this " +
                    "way. Allow it, or pick Auto."
            } else {
                "Wi-Fi Direct needs the $wifiPermissionName permission on both devices. Without it, Auto uses " +
                    "Wi-Fi or Bluetooth."
            },
            action = allowWifiLabel to onAllowWifi,
        )
    }

    // KMK (r90): how the two devices talk
    SectionLabel("Connection")
    TransportPicker(transport = transport, enabled = !status.busy, onPick = onTransport)

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
            syncedVia = result?.takeIf { it.peerAddress == peer.address }?.via,
            now = now,
            // KMK (r90): receiving nothing still sends (the core supports it); the button then says Send
            canSync = ready && !status.busy,
            sendOnly = selection.isEmpty,
            onSync = { onSync(peer) },
            onUnpair = { removing = peer },
            onActivity = { onOpen(Page.Activity) },
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
            // KMK (r90): counted like the Anime page does (Default once, even when a category is named "")
            subtitle = categorySubtitle(
                selection.animeLibrary,
                selection.animeCategories,
                (listOf("") + animeCategories).distinct().size,
            ),
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
        // KMK (r90): an icon and a short label, so it doesn't wrap at phone width
        OutlinedButton(
            onClick = { onOpen(Page.PairNew) },
            enabled = ready && !status.busy,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Pair", maxLines = 1, modifier = Modifier.padding(start = 6.dp))
        }
        FilledTonalButton(onClick = { onOpen(Page.Activity) }, modifier = Modifier.weight(1f)) {
            Text("Activity", maxLines = 1)
        }
    }
    Note("Each device picks what it gets, and which of its own extensions, repos and sources it sends.")

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
    else -> "${picked.size.coerceAtMost(total)} of $total categories"
}

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1024f / 1024f)

private const val MIB = 1024L * 1024L

/** KMK (r90): "64 MB", "3.5 MB" or "420 KB" */
private fun sizeLabel(bytes: Long): String = when {
    bytes >= 10 * MIB -> "%.0f MB".format(bytes / MIB.toFloat())
    bytes >= MIB -> "%.1f MB".format(bytes / MIB.toFloat())
    else -> "${(bytes / 1024L).coerceAtLeast(1L)} KB"
}

/** KMK (r90): "26 MB/s"; KB/s under 1 MB/s */
private fun speed(bytesPerSecond: Long): String = "${sizeLabel(bytesPerSecond)}/s"

/** KMK (r90): "3 s" or "2 min 5 s" */
private fun duration(millis: Long): String {
    val seconds = ((millis + 500L) / 1000L).coerceAtLeast(1L)
    return if (seconds < 60L) "$seconds s" else "${seconds / 60L} min ${seconds % 60L} s"
}

/** KMK (r90): Auto / Wi-Fi / Wi-Fi Direct / Bluetooth, as one row of pills, and a hint for the one picked */
@Composable
private fun TransportPicker(transport: SyncTransport, enabled: Boolean, onPick: (SyncTransport) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(4.dp)) {
            SyncTransport.entries.forEach { option ->
                val selected = option == transport
                // Wider pills for the longer labels, so "Wi-Fi Direct" fits on one line at phone width
                val weight = 0.5f + option.label.length * 0.08f
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(weight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        )
                        .clickable(enabled = enabled && !selected) { onPick(option) }
                        .padding(vertical = 8.dp),
                ) {
                    FitText(
                        option.label,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
    val error = MaterialTheme.colorScheme.error
    val hint = when (transport) {
        SyncTransport.Wifi -> buildAnnotatedString {
            append("Through your router. Both devices must be on the same Wi-Fi")
        }
        SyncTransport.WifiDirect -> buildAnnotatedString { append("Straight between the two devices, no router") }
        SyncTransport.Bluetooth -> buildAnnotatedString {
            withStyle(SpanStyle(color = error)) { append("Slow, about 0.2 MB/s") }
            append(" · works anywhere, no Wi-Fi needed")
        }
        else -> buildAnnotatedString { append("Tries Wi-Fi Direct, then Wi-Fi, then Bluetooth") }
    }.let {
        // KMK (r90): the waiting device uses what the other one picked
        buildAnnotatedString {
            append(it)
            append(". The device pressing Sync picks the connection.")
        }
    }
    Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * KMK (r90): a one-line pill label that gets smaller until it fits (no clipped "Wi-Fi Direc" at a large font scale).
 * The size is kept once it fits.
 */
@Composable
private fun FitText(text: String, color: Color) {
    val base = MaterialTheme.typography.labelMedium
    var fontSize by remember(text) { mutableStateOf(base.fontSize) }
    Text(
        text,
        style = base.copy(fontSize = fontSize, letterSpacing = 0.sp),
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        onTextLayout = { layout ->
            if (layout.didOverflowWidth && fontSize.value > 8f) fontSize = fontSize * 0.9f
        },
    )
}

/** KMK (r90): the way a sync is going, e.g. "WI-FI DIRECT" */
@Composable
private fun ViaTag(via: SyncTransport, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Text(
            via.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeerRow(
    peer: Peer,
    status: PairSync.Status,
    syncedAt: Long?,
    syncedVia: SyncTransport?,
    now: Long,
    canSync: Boolean,
    sendOnly: Boolean,
    onSync: () -> Unit,
    onUnpair: () -> Unit,
    onActivity: () -> Unit,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                ) {
                    Text(
                        peer.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // KMK (r90): which way this sync is going
                    val via = status.via
                    if (syncing && via != null) ViaTag(via, Modifier.padding(start = 6.dp))
                }
                if (syncing) {
                    TextButton(onClick = PairSync::stopSync) { Text("Stop") }
                } else {
                    // KMK (r90): narrower, so the name keeps its room at phone width
                    Button(
                        onClick = onSync,
                        enabled = canSync,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) { Text(if (sendOnly) "Send" else "Sync", maxLines = 1) }
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
            // KMK (r90): the subtitle on its own full-width line (it wrapped to 3-4 lines next to the buttons)
            val subtitle = when {
                syncing && status.total > 0L ->
                    "${if (status.sending) "Sending" else "Receiving"} ${mb(status.done)} of " +
                        "${mb(status.total)} MB" +
                        if (status.bytesPerSecond > 0L) " · ${speed(status.bytesPerSecond)}" else "…"
                syncing -> status.step ?: "Syncing…"
                syncedAt != null -> {
                    val ago = if (now - syncedAt < 60_000L) {
                        "just now"
                    } else {
                        DateUtils.getRelativeTimeSpanString(syncedAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
                    }
                    "✓ Synced $ago" + (syncedVia?.let { " · via ${it.label}" } ?: "")
                }
                peer.lastSync > 0L -> "Last synced " +
                    DateUtils.getRelativeTimeSpanString(peer.lastSync, now, DateUtils.MINUTE_IN_MILLIS)
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
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            // KMK (r90): why the last sync with this device failed (it used to look like nothing happened)
            val error = status.lastError
            if (!syncing && error != null && status.errorAddress == peer.address) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text(
                    "See Activity ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onActivity)
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                )
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
                // KMK (r90): the bytes count both ways
                "Got from ${result.peerName}" + if (result.bytes > 0L && result.millis > 0L) {
                    " · ${sizeLabel(result.bytes)} moved in ${duration(result.millis)}"
                } else {
                    ""
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${summary.anime} anime · ${summary.manga} manga · ${summary.historyEntries} history entries",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${summary.reposAdded} repos · ${summary.sourcesAdded} code sources" +
                    if (summary.apks.isNotEmpty()) {
                        " · ${summary.apks.size} ${if (summary.apks.size == 1) "APK" else "APKs"}"
                    } else {
                        ""
                    },
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
            // KMK (r90): Retry stays after the installs were asked (one cancelled can be tried again)
            subtitle = if (result.missingAsked) {
                "Each install asks first. Retry for any you skipped"
            } else {
                val apks = summary.apks.size
                listOfNotNull(
                    summary.missing.size.takeIf { it > 0 }?.let { "$it from repos" },
                    (if (apks == 1) "1 sideloaded APK" else "$apks sideloaded APKs").takeIf { apks > 0 },
                ).joinToString(" · ")
            },
            action = if (result.missingAsked) "Retry" else "Install",
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
                "Updating. Retry for any that didn't"
            } else {
                summary.updates.take(3).joinToString { "${it.name} ${it.fromVersion} → ${it.toVersion}" } +
                    if (summary.updates.size > 3) "…" else ""
            },
            action = if (result.updatesAsked) "Retry" else "Update",
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
                // KMK (r90): the "›" stays when a long subtitle is cut short
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled, onClick = onOpen)
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        " ›",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
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
        // KMK (r90): what this device gets
        subtitle = when (picked) {
            null -> "Get all ${options.size} categories"
            else -> "Get ${options.count { it in picked }} of ${options.size} categories"
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

/**
 * KMK (r90): each switch is what this device GETS; each "›" picks which of this device's own repos, extensions, APKs
 * and sources it SENDS (so the pickers stay usable with the switches off: the other device may still ask for them)
 */
@Composable
private fun ExtensionsPage(
    selection: SyncSelection,
    sourceKeys: List<String>,
    onChange: (SyncSelection) -> Unit,
    onOpen: (Page) -> Unit,
) {
    val repos by produceState(emptyList<Triple<String, String, Boolean>>()) {
        value = withContext(Dispatchers.IO) { runCatching { PairSyncData.repos() }.getOrDefault(emptyList()) }
    }
    val local by localExtensions()
    val installed = local.orEmpty().filter { it.fromRepo }.map { it.pkgName }.distinct()
    val apks = local.orEmpty().filterNot { it.fromRepo }.map { it.pkgName }.distinct()
    val on = selection.extensions
    SwitchCard(
        icon = Icons.Outlined.Extension,
        title = "Sync extensions",
        subtitle = "Get repos, extensions, APKs and code sources",
        checked = on,
        onCheckedChange = { onChange(selection.copy(extensions = it)) },
        highlighted = true,
    )
    SectionLabel("From repos")
    val repoUrls = repos.map { it.first }.distinct()
    SwitchCard(
        icon = Icons.Outlined.Storage,
        title = "Repos",
        subtitle = "Get anime & manga repos missing here",
        checked = selection.repos,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(repos = it)) },
        onOpen = { onOpen(Page.Repos) },
        sendLabel = sendLabel(repoUrls, selection.repoUrls),
    )
    SwitchCard(
        icon = Icons.Outlined.Extension,
        title = "Installed extensions",
        subtitle = "Get missing ones and newer versions",
        checked = selection.installedExtensions,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(installedExtensions = it)) },
        onOpen = { onOpen(Page.InstalledPicker) },
        sendLabel = sendLabel(installed, selection.installedKeys),
    )
    SectionLabel("Not from a repo")
    SwitchCard(
        icon = Icons.Outlined.Android,
        title = "Sideloaded APKs",
        subtitle = "Get the ones missing here",
        checked = selection.sideloadedApks,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(sideloadedApks = it)) },
        onOpen = { onOpen(Page.ApkPicker) },
        sendLabel = sendLabel(apks, selection.apkKeys),
    )
    SwitchCard(
        icon = Icons.Outlined.Code,
        title = "Code sources",
        subtitle = "Get new and newer sources",
        checked = selection.sources,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(sources = it)) },
        onOpen = { onOpen(Page.CodeSources) },
        sendLabel = sendLabel(sourceKeys, selection.sourceKeys),
    )
    Note("Installs always ask you first. Nothing is removed on either device.")
}

/** KMK (r90): "Send: all 14" or "Send: 2 of 14" (of this device's own) */
private fun sendLabel(all: List<String>, picked: Set<String>?): String =
    if (picked == null) "Send: all ${all.size}" else "Send: ${pickedCount(all, picked)} of ${all.size}"

/** KMK (r90): this device's extensions (null while loading) */
@Composable
private fun localExtensions() = produceState<List<LocalExtension>?>(null) {
    value = withContext(Dispatchers.IO) {
        runCatching { PairSyncData.localExtensions() }.getOrDefault(emptyList())
    }
}

/** How many of [all] are picked; [picked] null means all */
private fun pickedCount(all: List<String>, picked: Set<String>?): Int =
    if (picked == null) all.size else all.count { it in picked }

/**
 * KMK (r90): pick which installed extensions ([fromRepo]) or sideloaded APKs to offer, grouped Anime / Manga.
 * [picked] null means all (ones installed later come along too).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtensionPicker(
    fromRepo: Boolean,
    picked: Set<String>?,
    onPick: (Set<String>?) -> Unit,
    note: String,
) {
    val local by localExtensions()
    val list = local
    if (list == null) {
        CircularProgressIndicator(Modifier.size(24.dp))
        return
    }
    val shown = list.filter { it.fromRepo == fromRepo }.distinctBy { it.pkgName }.sortedBy { it.name.lowercase() }
    if (shown.isEmpty()) {
        Note(if (fromRepo) "No extensions from repos on this device." else "No sideloaded APKs on this device.")
        return
    }
    val all = shown.map { it.pkgName }.toSet()
    val toggle = { key: String ->
        val now = picked ?: all
        val next = if (key in now) now - key else now + key
        // Every one picked means all
        onPick(if (next.containsAll(all)) null else next)
    }
    FilterChip(
        selected = picked == null,
        onClick = { onPick(null) },
        label = { Text("All") },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    listOf(true to "Anime", false to "Manga").forEach { (anime, label) ->
        val group = shown.filter { it.isAnime == anime }
        if (group.isEmpty()) return@forEach
        SectionLabel(label)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            group.forEach { ext ->
                FilterChip(
                    selected = picked == null || ext.pkgName in picked,
                    onClick = { toggle(ext.pkgName) },
                    label = { Text(ext.name.ifBlank { ext.pkgName }) },
                )
            }
        }
    }
    Note(note)
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
    val activity = LocalActivity.current
    val code by PairSync.pairingCode.collectAsState()
    val name = remember { PairSyncDiscovery.bluetoothName(context) }
    // KMK (r90): kept across rotation, so the code doesn't change while the other person types it and Android doesn't
    // ask again to make this device visible: the device paired before (by its key), whether a code was made, the time
    val before = rememberSaveable { PairSync.justPaired.value?.secret.orEmpty() }
    var started by rememberSaveable { mutableStateOf(false) }
    var left by rememberSaveable { mutableStateOf(300) }
    var countedFor by rememberSaveable { mutableStateOf<String?>(null) }

    val start = {
        PairSync.startPairing(context.applicationContext)
        started = true
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
        if (!started) start()
        onDispose {
            // KMK (r90): only when the page really closes (Cancel, back, the pop-up closed), not on a rotation
            if (activity?.isChangingConfigurations != true) PairSync.stopPairing()
        }
    }
    LaunchedEffect(Unit) {
        PairSync.justPaired.first { it != null && it.secret != before }
        onPaired()
    }
    LaunchedEffect(code) {
        val shown = code ?: return@LaunchedEffect
        // A new code gets the full five minutes; the same one after a rotation goes on counting
        if (countedFor != shown) {
            countedFor = shown
            left = 300
        }
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
    val activity = LocalActivity.current
    val adapter = remember { PairSync.adapter(context) }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    val leScan = remember { PairSyncDiscovery.Scan(context.applicationContext) }
    val hashes by leScan.hashes.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    // KMK (r90): the picked device (by address) and the typed code survive a rotation; the pairing itself runs in
    // PairSync, so a rotation or back doesn't lose its result
    var chosenAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var code by rememberSaveable { mutableStateOf("") }
    val pairing by PairSync.pairing.collectAsState()
    val error by PairSync.pairError.collectAsState()
    val before = rememberSaveable { PairSync.justPaired.value?.secret.orEmpty() }
    var fresh by rememberSaveable { mutableStateOf(true) }
    var round by remember { mutableIntStateOf(0) }
    var showOthers by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // An old failure from an earlier visit isn't shown
        if (fresh) {
            fresh = false
            if (!PairSync.pairing.value) PairSync.clearPairError()
        }
        PairSync.justPaired.first { it != null && it.secret != before }
        onPaired()
    }
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) PairSync.clearPairError() }
    }

    // KMK (r90): Android 11 and older need location to find nearby devices; asked for here only, so the rest of
    // Sync works without it. The search starts again once it's allowed or Location is turned on.
    var locationGranted by remember { mutableStateOf(hasLocation(context)) }
    var locationOn by remember { mutableStateOf(isLocationOn(context)) }
    var askedLocation by rememberSaveable { mutableStateOf(false) }
    var locationForGood by rememberSaveable { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        locationGranted = hasLocation(context)
        if (locationGranted) {
            round++
        } else {
            locationForGood = deniedForGood(activity, context, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }
    LaunchedEffect(Unit) {
        if (!locationGranted && !askedLocation) {
            askedLocation = true
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val nowGranted = hasLocation(context)
        val nowOn = isLocationOn(context)
        if ((nowGranted && !locationGranted) || (nowOn && !locationOn)) round++
        locationGranted = nowGranted
        locationOn = nowOn
    }

    DisposableEffect(round) {
        // KMK (r90): cancelling the last round's search sends a late "finished"; that one mustn't stop this round
        val startedAt = SystemClock.elapsedRealtime()
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
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> scanning = true
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        val stale = SystemClock.elapsedRealtime() - startedAt < 1_500L &&
                            runCatching { adapter?.isDiscovering == true }.getOrDefault(false)
                        if (!stale) scanning = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
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
    // KMK (r90): phones and computers always stay in view (under "Other nearby devices" next to a tagged one); only
    // headphones, TVs, watches and the like are hidden
    val likely = rest.filterNot { (d, _) -> PairSyncDiscovery.isUnlikely(d) }
    val hidden = rest - likely.toSet()
    LaunchedEffect(tagged.size) {
        if (chosenAddress == null && tagged.size == 1) chosenAddress = tagged.first().first.address
    }
    val chosen = chosenAddress?.let { address ->
        found.firstOrNull { it.address == address } ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
    }
    val pick: (BluetoothDevice) -> Unit = {
        chosenAddress = it.address
        PairSync.clearPairError()
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
    val askLocation: () -> Unit = {
        if (locationForGood) {
            openAppSettings(context)
        } else {
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val openLocationSettings: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }
    if (!locationGranted) {
        Notice(
            "Finding nearby devices needs the Location permission on this Android version.",
            action = (if (locationForGood) "Settings" else "Allow") to askLocation,
        )
    } else if (!locationOn) {
        Notice("Turn on Location to find nearby devices", action = "Settings" to openLocationSettings)
    }

    if (tagged.isNotEmpty()) {
        SectionLabel("Komikku nearby")
        tagged.forEach { (d, name) ->
            DeviceRow(d, name, "Showing a code", tagged = true, selected = chosenAddress == d.address) { pick(d) }
        }
    }
    if (likely.isNotEmpty()) {
        SectionLabel(if (tagged.isEmpty()) "Nearby devices" else "Other nearby devices")
        likely.forEach { (d, name) ->
            DeviceRow(d, name, d.address, tagged = false, selected = chosenAddress == d.address) { pick(d) }
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
                DeviceRow(d, name, d.address, tagged = false, selected = chosenAddress == d.address) { pick(d) }
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
                device?.let { target -> PairSync.startPair(context.applicationContext, target, code) }
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

/**
 * A switch row; with [onOpen], a "›" opens its page. KMK (r90): [enabled] is the switch only; the "›" and
 * [sendLabel] (what this device sends) always work.
 */
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
    sendLabel: String? = null,
) {
    val dim = if (enabled) 1f else 0.55f
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (onOpen == null) 12.dp else 4.dp),
        ) {
            Box(Modifier.alpha(dim)) { Avatar(icon) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.alpha(dim))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(dim),
                )
                if (onOpen != null && sendLabel != null) {
                    Text(
                        sendLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpen)
                            .padding(vertical = 2.dp),
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            if (onOpen != null) {
                // KMK (r90): a small chevron of its own (a TextButton's 58dp minimum squeezed the text at 360dp)
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "Open",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpen)
                        .padding(4.dp)
                        .size(24.dp),
                )
            }
        }
    }
}

/**
 * "All" or some of [options] (value to label); [picked] null means all. KMK (r90): the same model as the extension
 * pickers: under All every chip is selected, and none picked is allowed (none).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipChoices(
    options: List<Pair<String, String>>,
    picked: Set<String>?,
    onPick: (Set<String>?) -> Unit,
) {
    val all = options.map { it.first }.toSet()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        FilterChip(selected = picked == null, onClick = { onPick(null) }, label = { Text("All") })
        options.forEach { (value, label) ->
            FilterChip(
                selected = picked == null || value in picked,
                onClick = {
                    val now = picked ?: all
                    val next = if (value in now) now - value else now + value
                    // Every one picked means all (ones added later come along too)
                    onPick(if (next.containsAll(all)) null else next)
                },
                label = { Text(label) },
            )
        }
    }
}
