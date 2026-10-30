package eu.kanade.tachiyomi.ui.pairsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.pairsync.DownloadedTitle
import eu.kanade.tachiyomi.pairsync.LocalExtension
import eu.kanade.tachiyomi.pairsync.PairSync
import eu.kanade.tachiyomi.pairsync.PairSyncData
import eu.kanade.tachiyomi.pairsync.PairSyncDiscovery
import eu.kanade.tachiyomi.pairsync.PairSyncInstall
import eu.kanade.tachiyomi.pairsync.PairSyncNotifier
import eu.kanade.tachiyomi.pairsync.PairSyncPreferences
import eu.kanade.tachiyomi.pairsync.Peer
import eu.kanade.tachiyomi.pairsync.SyncPhase
import eu.kanade.tachiyomi.pairsync.SyncSelection
import eu.kanade.tachiyomi.pairsync.SyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** KMK (r93): the notifications permission, when Android 13+ hasn't granted it */
private fun missingNotify(context: Context): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
    ) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

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

/**
 * KMK (r93): whether the Sync pop-up is open ([isOpen]) and showing in front ([visible]: the service posts the other
 * device's request as a notification only when it isn't), and a request to open it (a tapped sync notification,
 * through MainActivity). HomeScreen and MoreTab each watch [openRequest]; [takeOpenRequest] gives it to one of them,
 * and to none when a pop-up is open already.
 */
object PairSyncPopup {
    private val lock = Any()
    private val opened = mutableSetOf<Any>()
    private val shown = mutableSetOf<Any>()

    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _openRequest = MutableStateFlow(false)
    val openRequest: StateFlow<Boolean> = _openRequest.asStateFlow()

    fun requestOpen() {
        _openRequest.value = true
    }

    /** True for the one caller that should open the pop-up now */
    fun takeOpenRequest(): Boolean {
        if (!_openRequest.compareAndSet(expect = true, update = false)) return false
        return !_isOpen.value
    }

    internal fun opened(token: Any, open: Boolean) {
        synchronized(lock) {
            if (open) {
                opened.add(token)
            } else {
                opened.remove(token)
                shown.remove(token)
            }
            _isOpen.value = opened.isNotEmpty()
            _visible.value = shown.isNotEmpty()
        }
    }

    internal fun shown(token: Any, show: Boolean) {
        synchronized(lock) {
            if (show) {
                shown.add(token)
            } else {
                shown.remove(token)
            }
            _visible.value = shown.isNotEmpty()
        }
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

    // KMK (r92): which downloaded titles go, per kind
    MangaDownloads(Manga),
    AnimeDownloads(Anime),
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
    // KMK (r91): the tiles are what this device sends. An empty category set (an old saved one) means all, as in the
    // core: the pickers turn the tile off instead of storing one
    val selection = remember(selectionJson) {
        PairSyncPreferences.parseSelection(selectionJson).let {
            it.copy(
                mangaCategories = it.mangaCategories?.takeIf { set -> set.isNotEmpty() },
                animeCategories = it.animeCategories?.takeIf { set -> set.isNotEmpty() },
            )
        }
    }

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
    // KMK (r93): the service posts the other device's request as a notification only while the pop-up isn't showing
    val popupToken = remember { Any() }
    DisposableEffect(popupToken) {
        PairSyncPopup.opened(popupToken, true)
        onDispose { PairSyncPopup.opened(popupToken, false) }
    }
    // KMK (r91): in the background nobody sees the pop-up (it kept checking in, and a prompt went unseen for 60 s):
    // not live, and a prompt showing is declined; back in front, live again. Not on a rotation.
    // KMK (r93): the prompt now waits as a notification (Accept / Decline there); declined only when this app's
    // notifications, or its "Sync requests" channel, are off
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        PairSyncPopup.shown(popupToken, false)
        if (activity?.isChangingConfigurations != true) {
            PairSync.leaveLive()
            if (!PairSyncNotifier.requestsCanShow(context)) PairSync.declineIncoming()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        PairSyncPopup.shown(popupToken, true)
        if (ready) PairSync.goLive(context.applicationContext)
    }
    // KMK (r91): the pop-up really closed: a prompt showing is declined (the other device waited the full minute)
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) PairSync.declineIncoming() }
    }
    DisposableEffect(ready) {
        if (ready) {
            PairSync.startListening(context.applicationContext)
            // KMK (r91): check in with the paired devices, so both pop-ups show each other's choices live
            PairSync.goLive(context.applicationContext)
        }
        onDispose {
            // KMK (r90): a rotation keeps listening (and a code showing); a real close or Bluetooth off stops it
            if (activity?.isChangingConfigurations != true) {
                PairSync.stopListening()
                PairSync.leaveLive()
            }
        }
    }
    // KMK (r93): the sync's notifications (progress, requests, result) need this on Android 13+; asked once per
    // opening, with the Wi-Fi Direct ones when those are asked (one request at a time). Denied: sync still works.
    var askedNotify by rememberSaveable { mutableStateOf(false) }
    // KMK (r90): the waiting device makes the Wi-Fi Direct group, so it's asked here too (once per opening)
    LaunchedEffect(ready, wifiNeeded) {
        if (ready && wifiNeeded && !askedWifi) {
            val missing = missingWifi(context)
            if (missing.isNotEmpty()) {
                askedWifi = true
                val notify = if (askedNotify) emptyList() else missingNotify(context)
                askedNotify = true
                listenWifiLauncher.launch((missing + notify).toTypedArray())
            }
        }
        if (ready && !askedNotify) {
            askedNotify = true
            val notify = missingNotify(context)
            if (notify.isNotEmpty()) listenWifiLauncher.launch(notify.toTypedArray())
        }
    }

    // KMK (r91): null until read, so a pick isn't checked against a list that isn't there yet
    val mangaCategoryNames by produceState<List<String>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { Injekt.get<GetCategories>().await().map { it.name } }.getOrDefault(emptyList())
        }
    }
    val animeCategoryNames by produceState<List<String>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { Injekt.get<AnimeRepository>().getCategories().map { it.name } }.getOrDefault(emptyList())
        }
    }
    val mangaCategories = mangaCategoryNames.orEmpty()
    val animeCategories = animeCategoryNames.orEmpty()
    val sources by remember { Injekt.get<JsSourceManager>().savedFlow }.collectAsState()
    // KMK (r91): live peers hear about the change (the core waits a moment, so a run of taps is sent once)
    val onChange: (SyncSelection) -> Unit = {
        prefs.setSelection(it)
        PairSync.selectionChanged(context.applicationContext)
    }
    val incoming by PairSync.incoming.collectAsState()
    // KMK (r91): the extension offers hidden with their ×, by the result's time (the card's × is kept in the core's
    // result, so it sticks when the pop-up opens again)
    var hiddenOffers by rememberSaveable { mutableStateOf(0L) }

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
        // KMK (r92): the screen stays on while a sync runs and the pop-up shows it (a long transfer stalled once the
        // screen went off)
        val view = LocalView.current
        val running = status.busy && status.peerAddress != null
        DisposableEffect(view, running) {
            view.keepScreenOn = running
            onDispose { view.keepScreenOn = false }
        }
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
                            mangaCategories = mangaCategoryNames,
                            animeCategories = animeCategoryNames,
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
                            hiddenOffers = hiddenOffers,
                            onHideOffers = { hiddenOffers = it },
                        )
                        Page.Anime -> {
                            PageTitle("Anime", back)
                            LibraryPage(
                                icon = Icons.Outlined.Movie,
                                title = "Send anime library",
                                checked = selection.animeLibrary,
                                onCheckedChange = { onChange(selection.copy(animeLibrary = it)) },
                                categories = animeCategories,
                                loaded = animeCategoryNames != null,
                                picked = selection.animeCategories,
                                // KMK (r91): no category picked turns the tile off (an empty set is never stored)
                                onPick = {
                                    onChange(
                                        if (it != null && it.isEmpty()) {
                                            selection.copy(animeLibrary = false)
                                        } else {
                                            selection.copy(animeCategories = it)
                                        },
                                    )
                                },
                                note = "This device's anime in the picked categories, with seen episodes and " +
                                    "where you stopped. The other device is asked to accept first.",
                            )
                            // KMK (r92): covers and downloaded episodes can go too
                            AlsoSend(
                                anime = true,
                                selection = selection,
                                onChange = onChange,
                                onOpenDownloads = { goTo(Page.AnimeDownloads) },
                            )
                        }
                        Page.Manga -> {
                            PageTitle("Manga", back)
                            LibraryPage(
                                icon = Icons.Outlined.CollectionsBookmark,
                                title = "Send manga library",
                                checked = selection.mangaLibrary,
                                onCheckedChange = { onChange(selection.copy(mangaLibrary = it)) },
                                categories = mangaCategories,
                                loaded = mangaCategoryNames != null,
                                picked = selection.mangaCategories,
                                // KMK (r91): no category picked turns the tile off (an empty set is never stored)
                                onPick = {
                                    onChange(
                                        if (it != null && it.isEmpty()) {
                                            selection.copy(mangaLibrary = false)
                                        } else {
                                            selection.copy(mangaCategories = it)
                                        },
                                    )
                                },
                                note = "This device's manga in the picked categories, with read chapters, " +
                                    "bookmarks and page progress (progress keeps whichever device is further).",
                            )
                            // KMK (r92): covers and downloaded chapters can go too
                            AlsoSend(
                                anime = false,
                                selection = selection,
                                onChange = onChange,
                                onOpenDownloads = { goTo(Page.MangaDownloads) },
                            )
                        }
                        Page.MangaDownloads -> {
                            PageTitle("Manga › Downloaded chapters", back)
                            DownloadsPicker(anime = false, selection = selection, onChange = onChange)
                        }
                        Page.AnimeDownloads -> {
                            PageTitle("Anime › Downloaded episodes", back)
                            DownloadsPicker(anime = true, selection = selection, onChange = onChange)
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
                                    "Only sources picked here are sent, and only new or newer ones."
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
        // KMK (r91): the other device asks before it syncs: shown over any page; back or Decline declines
        incoming?.let { request ->
            key(request.id) {
                IncomingPrompt(
                    peerName = request.peerName,
                    youGet = request.youGet.map { it.title to it.detail },
                    youSend = request.youSend.map { it.title to it.detail },
                    sizeText = request.sizeText,
                    via = request.via,
                    expiresAt = request.expiresAt,
                    totalBytes = request.totalBytes,
                    freeBytes = request.freeBytes,
                    enoughSpace = request.enoughSpace,
                    etaText = request.etaText,
                    extrasNeedWifi = request.extrasNeedWifi,
                    onAnswer = { accept -> PairSync.answerIncoming(request.id, accept) },
                )
            }
        }
    }
}

/**
 * KMK (r91): "Tablet wants to send you": what comes here ([youGet]) and, for a two-way sync, what goes back
 * ([youSend]), each as title to detail. Decline, back or the 60 s running out all decline; a tap outside does nothing
 * (a stray tap declined).
 */
@Composable
private fun IncomingPrompt(
    peerName: String,
    youGet: List<Pair<String, String>>,
    youSend: List<Pair<String, String>>,
    sizeText: String?,
    via: SyncTransport?,
    expiresAt: Long,
    totalBytes: Long?,
    freeBytes: Long?,
    enoughSpace: Boolean,
    etaText: String?,
    extrasNeedWifi: Boolean,
    onAnswer: (Boolean) -> Unit,
) {
    val left by produceState(((expiresAt - System.currentTimeMillis()) / 1000L).coerceIn(0L, 60L), expiresAt) {
        while (true) {
            value = ((expiresAt - System.currentTimeMillis() + 999L) / 1000L).coerceIn(0L, 60L)
            delay(1000L)
        }
    }
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                if (youSend.isEmpty()) "$peerName wants to send you" else "$peerName wants to sync",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (youGet.isEmpty()) {
                    Text(
                        "Nothing comes to this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                youGet.forEach { (title, detail) -> BulletRow(title, detail) }
                if (youSend.isNotEmpty()) {
                    Text(
                        "You'll send back:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    youSend.forEach { (title, detail) -> BulletRow(title, detail) }
                }
                // KMK (r92): "Up to 0.8 GB · 41 GB free on this device" when the free space is known (never over
                // Bluetooth, nor without covers and downloads): only a warning when it may not fit, Accept stays on
                // (the estimate counts what's here already; what doesn't fit is skipped)
                val spaceLine = totalBytes != null && totalBytes > 0L && freeBytes != null
                // KMK (r92): "Up to 0.8 GB · over Wi-Fi Direct · ≈ 40 s" (the size there only without the line below)
                val line = listOfNotNull(
                    if (spaceLine) {
                        null
                    } else {
                        totalBytes?.takeIf { it > 0L }?.let { "Up to ${bigSize(it)}" }
                            ?: sizeText?.takeIf { it.isNotBlank() }
                    },
                    via?.takeIf { it != SyncTransport.Auto }?.let { "over ${it.label}" },
                    etaText?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("≈")) it else "≈ $it" },
                ).joinToString(" · ")
                if (line.isNotEmpty()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (spaceLine) {
                    Text(
                        (if (enoughSpace) "✓ " else "") +
                            "Up to ${bigSize(totalBytes ?: 0L)} · ${bigSize(freeBytes ?: 0L)} free on this device",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enoughSpace) LiveGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!enoughSpace) {
                        Text(
                            "May not all fit: what doesn't fit is skipped",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Amber,
                        )
                    }
                }
                if (extrasNeedWifi) {
                    Text(
                        "Covers and downloads need Wi-Fi; only the library comes over Bluetooth.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Cancelled in $left s if you don't answer. Nothing changes until you accept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        // KMK (r92): never off for the free space (see above)
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text("Accept") } },
        dismissButton = { OutlinedButton(onClick = { onAnswer(false) }) { Text("Decline") } },
    )
}

/** KMK (r91): "• Anime  2 of 4 categories · 8 titles" */
@Composable
private fun BulletRow(title: String, detail: String) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 7.dp, end = 10.dp)
                .size(7.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
                if (detail.isNotBlank()) {
                    append("  ")
                    withStyle(SpanStyle(color = variant)) { append(detail) }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MainPage(
    ready: Boolean,
    hasAdapter: Boolean,
    granted: Boolean,
    selection: SyncSelection,
    transport: SyncTransport,
    mangaCategories: List<String>?,
    animeCategories: List<String>?,
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
    hiddenOffers: Long,
    onHideOffers: (Long) -> Unit,
) {
    val context = LocalContext.current
    val prefs = PairSyncPreferences.instance
    val status by PairSync.status.collectAsState()
    val result by PairSync.result.collectAsState()
    // KMK (r93): received data waiting to be applied (or being applied)
    val pendingApply by PairSync.pendingApply.collectAsState()
    // KMK (r91): what each paired device sends (live while its pop-up is open, else from the last sync)
    val peerViews by PairSync.peerViews.collectAsState()
    // KMK (r91): this device's own Send: line, again whenever the tiles change (null until the first one is ready)
    val sendRows by produceState<List<Pair<String, String>>?>(null, selection) {
        var tries = 0
        while (true) {
            val rows = withContext(Dispatchers.IO) {
                runCatching { PairSyncData.summarizeSend(selection).map { it.title to it.detail } }
                    .getOrDefault(emptyList())
            }
            value = rows
            // KMK (r91): the APK count waits for the repos' lists (read in the background): asked again until then
            val waitApks = tries < 10 && rows.any { (_, detail) -> PairSyncData.APKS_CHECKING in detail }
            // KMK (r92): and the covers and downloads, still counted (a big downloads folder takes minutes)
            val waitExtras = tries < 60 && rows.any { (_, detail) -> detail == PairSyncData.EXTRAS_CHECKING }
            if (!waitApks && !waitExtras) break
            tries++
            delay(3_000L)
        }
    }
    val sendsSomething = sendRows?.isNotEmpty() ?: !selection.isEmpty
    // KMK (r92): covers and downloads never go over a forced Bluetooth: the lines leave them out then
    val extrasGo = transport != SyncTransport.Bluetooth
    // KMK (r92): the picked downloads still on this device (the tiles count only those)
    val mangaDownloaded by downloadedKeys(anime = false, wanted = selection.mangaDownloadKeys != null)
    val animeDownloaded by downloadedKeys(anime = true, wanted = selection.animeDownloadKeys != null)
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
        // KMK (r93): not while applying (the apply card has its own Stop)
        if (status.busy && status.peerAddress != null && status.phase != SyncPhase.Applying &&
            peers.none { it.address == status.peerAddress }
        ) {
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
        val view = peerViews[peer.address]
        val theirSend = view?.theirSend?.map { it.title to it.detail }
        // KMK (r91): unknown counts as sending something (the button then says Sync)
        val peerSends = view?.theirSendsNothing != true && theirSend?.isEmpty() != true
        // KMK (r91): neither side sends anything: the button is off (it ran an empty sync)
        val nothingEitherWay = !sendsSomething && !peerSends
        PeerRow(
            peer = peer,
            status = status,
            syncedAt = result?.takeIf { it.peerAddress == peer.address }?.time,
            syncedVia = result?.takeIf { it.peerAddress == peer.address }?.via,
            now = now,
            // KMK (r93): not while received data is applied
            canSync = ready && !status.busy && status.phase != SyncPhase.Applying && !nothingEitherWay,
            nothingEitherWay = nothingEitherWay,
            // KMK (r91): Sync both ways, Send only from here, Get only from there; all start the same sync
            buttonLabel = when {
                sendsSomething && peerSends -> "Sync"
                sendsSomething -> "Send"
                peerSends -> "Get"
                else -> "Sync"
            },
            live = view?.live == true,
            getLine = when {
                theirSend == null -> "unknown · sync once to see"
                theirSend.isEmpty() || view?.theirSendsNothing == true -> "nothing"
                // KMK (r91): every tile on there, as in the approved design. r92: "+ covers 210 MB" after it
                else -> lineText(theirSend, everything = view?.theirSendsEverything == true, extras = extrasGo)
            } + if (view != null && !view.live && theirSend != null && view.asOf > 0L) {
                " · as of ${ago(view.asOf, now)}"
            } else {
                ""
            },
            sendLine = sendRows.let { rows ->
                when {
                    rows == null -> "…"
                    rows.isEmpty() -> "nothing"
                    else -> lineText(rows, everything = selection.isEverything, extras = extrasGo)
                }
            },
            onSync = { onSync(peer) },
            onUnpair = { removing = peer },
            onActivity = { onOpen(Page.Activity) },
        )
    }
    // KMK (r93): the received data is applied after the devices disconnect; a stopped apply finishes later
    val applyingFrom = pendingApply?.peerName
        ?: peers.firstOrNull { it.address == status.peerAddress }?.name
        ?: "the other device"
    if (status.phase == SyncPhase.Applying) {
        // KMK (r93): only when no device row shows it already
        if (peers.none { it.address == status.peerAddress }) {
            ApplyCard(title = "Applying sync from $applyingFrom…", status = status)
        }
    } else if (pendingApply != null) {
        Note("Sync from $applyingFrom will finish applying")
    }
    // KMK (r91): Clear removes the card and the offers (and waiting APKs); each × removes its own box
    result?.let { last ->
        val showCard = !last.cardDismissed
        val showOffers = hiddenOffers != last.time && last.summary.let {
            it.missing.isNotEmpty() || it.apks.isNotEmpty() || it.updates.isNotEmpty()
        }
        if (showCard || showOffers) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionLabel("Last sync") }
                TextButton(
                    onClick = {
                        onHideOffers(last.time)
                        PairSync.clearResult()
                    },
                    enabled = !status.busy,
                ) { Text("Clear") }
            }
            ResultCard(
                result = last,
                showCard = showCard,
                showOffers = showOffers,
                onDismissCard = PairSync::dismissResultCard,
                onDismissOffers = {
                    onHideOffers(last.time)
                    PairSync.dismissOffers()
                },
            )
        }
    }

    // What this device sends
    SectionLabel("What this device sends")
    val tilesEnabled = !status.busy
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(
            icon = Icons.Outlined.Movie,
            title = "Anime",
            // KMK (r90): counted like the Anime page does (Default once, even when a category is named "")
            // r92: with the extras ("All · covers · 3 downloads")
            subtitle = libraryTileSubtitle(selection, anime = true, animeCategories, animeDownloaded),
            // KMK (r92): two lines, so "All · covers · 3 downloads" fits at 360dp
            subtitleLines = 2,
            checked = selection.animeLibrary,
            enabled = tilesEnabled,
            onToggle = { onChange(selection.copy(animeLibrary = !selection.animeLibrary)) },
            onOpen = { onOpen(Page.Anime) },
            modifier = Modifier.weight(1f),
        )
        Tile(
            icon = Icons.Outlined.CollectionsBookmark,
            title = "Manga",
            subtitle = libraryTileSubtitle(selection, anime = false, mangaCategories, mangaDownloaded),
            subtitleLines = 2,
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
    Note("Tiles are what this device sends. The other device is asked to accept.")

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

/** KMK (r91): the green of the "Live" dot (Material has no green of its own) */
private val LiveGreen = Color(0xFF34A853)

/** KMK (r92): the prompt's "may not all fit" warning (not an error: Accept stays on) */
private val Amber = Color(0xFFE8A000)

private val PART_OF = Regex("""^(\d+ of \d+)""")

/** KMK (r91): "Anime (2 of 4), Manga, History": each title, with "x of y" when only some of it goes */
private fun joinRows(rows: List<Pair<String, String>>): String = rows.joinToString(", ") { (title, detail) ->
    PART_OF.find(detail.trim())?.let { "$title (${it.groupValues[1]})" } ?: title
}

/** KMK (r92): a Covers or Downloads row of a send summary */
private fun isExtraRow(title: String): Boolean =
    title.contains("cover", ignoreCase = true) || title.contains("download", ignoreCase = true)

private val LEADING_COUNT = Regex("""^\d[\d,.]*""")

/** KMK (r92): "3 manga" and "2 anime" in "3 manga + 2 anime titles · 1.0 GB" */
private val KIND_COUNT = Regex("""(\d[\d,]*) (?:manga|anime)""")

/**
 * KMK (r92): ("Covers", "up to 1,520 · 210 MB") to "covers 210 MB"; ("Downloads", "3 manga + 2 anime titles ·
 * 1.0 GB") to "5 downloads 1.0 GB"; still counted: "counting covers / downloads…"
 */
private fun extraText(title: String, detail: String): String {
    if (detail == PairSyncData.EXTRAS_CHECKING) return "counting covers / downloads…"
    val size = detail.substringAfterLast(" · ", "").removePrefix("≈").trim()
    return if (title.contains("cover", ignoreCase = true)) {
        listOf("covers", size).filter { it.isNotEmpty() }.joinToString(" ")
    } else {
        val kinds = KIND_COUNT.findAll(detail).sumOf { it.groupValues[1].replace(",", "").toIntOrNull() ?: 0 }
        val count = kinds.takeIf { it > 0 }
            ?: LEADING_COUNT.find(detail.trim())?.value?.replace(",", "")?.toDoubleOrNull()?.toInt()
        val what = when (count) {
            null -> "downloads"
            1 -> "1 download"
            else -> "%,d downloads".format(count)
        }
        listOfNotNull(what, size.ifEmpty { null }).joinToString(" ")
    }
}

/**
 * KMK (r92): "everything + covers 210 MB + 3 downloads 0.6 GB", or "Anime (2 of 4), Manga + covers 210 MB";
 * without [extras] (a forced Bluetooth: they don't go) only the rest
 */
private fun lineText(rows: List<Pair<String, String>>, everything: Boolean, extras: Boolean = true): String {
    val (extraRows, others) = rows.partition { (title, _) -> isExtraRow(title) }
    val base = if (everything) "everything" else joinRows(others)
    val shown = if (extras) extraRows.map { (title, detail) -> extraText(title, detail) } else emptyList()
    return (listOf(base) + shown)
        .filter { it.isNotEmpty() }
        .joinToString(" + ")
        .ifEmpty { "nothing" }
}

/**
 * KMK (r92): the Anime / Manga tile's subtitle: the categories, then the extras ("All · covers · 3 downloads");
 * [downloaded]: the titles with downloads here (picks deleted since don't count), null = not known
 */
private fun libraryTileSubtitle(
    selection: SyncSelection,
    anime: Boolean,
    categories: List<String>?,
    downloaded: Set<String>?,
): String {
    val on = if (anime) selection.animeLibrary else selection.mangaLibrary
    val picked = if (anime) selection.animeCategories else selection.mangaCategories
    val base = categorySubtitle(on, picked, categories)
    val coversOn = selection.covers(anime)
    val downloadsOn = selection.downloads(anime)
    if (!on || (!coversOn && !downloadsOn)) return base
    val keys = selection.downloadKeys(anime)
    val count = keys?.let { picks -> downloaded?.let { here -> picks.count { it in here } } ?: picks.size }
    return listOfNotNull(
        if (picked == null) "All" else base.removeSuffix(" categories"),
        "covers".takeIf { coversOn },
        when {
            !downloadsOn -> null
            count == null -> "downloads"
            count == 1 -> "1 download"
            else -> "$count downloads"
        },
    ).joinToString(" · ")
}

// KMK (r92): the covers / downloads fields of one kind
private fun SyncSelection.covers(anime: Boolean): Boolean = if (anime) animeCovers else mangaCovers

private fun SyncSelection.downloads(anime: Boolean): Boolean = if (anime) animeDownloads else mangaDownloads

private fun SyncSelection.downloadKeys(anime: Boolean): Set<String>? =
    if (anime) animeDownloadKeys else mangaDownloadKeys

private fun SyncSelection.downloadMode(anime: Boolean): String = if (anime) animeDownloadMode else mangaDownloadMode

private fun SyncSelection.withCovers(anime: Boolean, on: Boolean): SyncSelection =
    if (anime) copy(animeCovers = on) else copy(mangaCovers = on)

private fun SyncSelection.withDownloads(anime: Boolean, on: Boolean, keys: Set<String>?): SyncSelection =
    if (anime) {
        copy(animeDownloads = on, animeDownloadKeys = keys)
    } else {
        copy(mangaDownloads = on, mangaDownloadKeys = keys)
    }

private fun SyncSelection.withDownloadMode(anime: Boolean, mode: String): SyncSelection =
    if (anime) copy(animeDownloadMode = mode) else copy(mangaDownloadMode = mode)

/** KMK (r91): "just now", else "5 minutes ago" */
private fun ago(time: Long, now: Long): String = if (now - time < 60_000L) {
    "just now"
} else {
    DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/**
 * KMK (r91): only picks that still exist count (a renamed or deleted category said "1 of 4" while nothing went);
 * [categories] null = not read yet
 */
private fun categorySubtitle(on: Boolean, picked: Set<String>?, categories: List<String>?): String {
    val options = categories?.let { (listOf("") + it).distinct() }
    return when {
        !on -> "Off"
        picked == null -> "All categories"
        options == null -> "Some categories"
        else -> "${options.count { it in picked }} of ${options.size} categories"
    }
}

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1024f / 1024f)

private const val MIB = 1024L * 1024L

/** KMK (r92): "41 GB", "0.6 GB" (from 512 MB up), "210 MB": the core's way, so the pop-up and its lines agree */
private fun bigSize(bytes: Long): String = PairSyncData.sizeText(bytes)

/** KMK (r90): "64 MB", "3.5 MB" or "420 KB" */
private fun sizeLabel(bytes: Long): String = when {
    bytes >= 10 * MIB -> "%.0f MB".format(bytes / MIB.toFloat())
    bytes >= MIB -> "%.1f MB".format(bytes / MIB.toFloat())
    else -> "${(bytes / 1024L).coerceAtLeast(1L)} KB"
}

/** KMK (r90): "26 MB/s"; KB/s under 1 MB/s */
private fun speed(bytesPerSecond: Long): String = "${sizeLabel(bytesPerSecond)}/s"

/** KMK (r92): "4,097 manga · 102 anime" from the non-zero counts, or "nothing" */
private fun countsText(vararg counts: Pair<Int, String>): String =
    counts.filter { it.first > 0 }
        .joinToString(" · ") { (count, what) -> "%,d %s".format(count, what) }
        .ifEmpty { "nothing" }

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeerRow(
    peer: Peer,
    status: PairSync.Status,
    syncedAt: Long?,
    syncedVia: SyncTransport?,
    now: Long,
    canSync: Boolean,
    nothingEitherWay: Boolean,
    buttonLabel: String,
    live: Boolean,
    getLine: String,
    sendLine: String,
    onSync: () -> Unit,
    onUnpair: () -> Unit,
    onActivity: () -> Unit,
) {
    // KMK (r91): waiting for the other device to accept counts as this device's sync (Cancel stops it)
    val waiting = status.waitingForAccept && status.peerAddress == peer.address
    // KMK (r93): after the transfer, the data from this device is applied offline (Stop: it carries on later)
    val applying = status.phase == SyncPhase.Applying && status.peerAddress == peer.address
    val syncing = (status.busy || waiting || applying) && status.peerAddress == peer.address
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
                // KMK (r91): the name has the row's room (at 360dp "Live" and the way squeezed it to nothing); both
                // are on the line below now
                Text(
                    peer.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                if (syncing) {
                    // KMK (r93): also while applying (the rest is applied the next time the app opens)
                    TextButton(onClick = PairSync::stopSync) { Text(if (waiting) "Cancel" else "Stop") }
                } else {
                    // KMK (r90): narrower, so the name keeps its room at phone width
                    Button(
                        onClick = onSync,
                        enabled = canSync,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) { Text(buttonLabel, maxLines = 1) }
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
            // KMK (r90): the subtitle on its own full-width line (it wrapped to 3-4 lines next to the buttons).
            // r91: with the way the data moves at its end while syncing, and "● Live" first while the other pop-up
            // is open (hidden while syncing)
            val showLive = live && !syncing
            val viaText = status.via?.takeIf { syncing && !waiting }?.let { " · ${it.label}" }.orEmpty()
            val subtitle = when {
                waiting -> "Waiting for ${peer.name} to accept…"
                applying -> "Applying sync from ${peer.name}…"
                syncing && status.total > 0L ->
                    "${if (status.sending) "Sending" else "Receiving"} ${mb(status.done)} of " +
                        "${mb(status.total)} MB" +
                        (if (status.bytesPerSecond > 0L) " · ${speed(status.bytesPerSecond)}" else "…") + viaText
                syncing -> (status.step ?: "Syncing…") + viaText
                showLive -> syncedAt?.let { "synced ${ago(it, now)}" } ?: "their pop-up is open"
                syncedAt != null ->
                    "✓ Synced ${ago(syncedAt, now)}" + (syncedVia?.let { " · via ${it.label}" } ?: "")
                peer.lastSync > 0L -> "Last synced " +
                    DateUtils.getRelativeTimeSpanString(peer.lastSync, now, DateUtils.MINUTE_IN_MILLIS)
                else -> "Not synced yet"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (showLive) {
                    Box(Modifier.size(8.dp).background(LiveGreen, CircleShape))
                    Text(
                        "Live",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LiveGreen,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Text(
                        " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncedAt != null && !syncing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (waiting) {
                Text(
                    "It has 60 seconds, then it's cancelled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
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
            // KMK (r91): what this device gets from it and sends to it
            SendGetLine("Get:", getLine, Modifier.padding(top = 8.dp))
            SendGetLine("Send:", sendLine, Modifier.padding(top = 2.dp))
            if (nothingEitherWay && !syncing) {
                Text(
                    "Nothing to send either way",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            if (applying) {
                // KMK (r93): "Applying manga 1,800 of 4,097"
                status.applyText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                ApplyBar(status)
            } else if (syncing && !waiting) {
                if (status.total > 0L) {
                    LinearProgressIndicator(
                        progress = { (status.done.toFloat() / status.total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                // KMK (r92): one bar per part (library, covers, downloads)
                if (status.parts.isNotEmpty()) {
                    status.parts.forEach { part ->
                        PartCard(name = part.name, detail = part.detail, fraction = part.fraction, done = part.done)
                    }
                    Text(
                        "Stopped halfway? The next sync carries on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** KMK (r93): the apply's bar: done of total, or moving while the total isn't known */
@Composable
private fun ApplyBar(status: PairSync.Status) {
    if (status.applyTotal > 0) {
        LinearProgressIndicator(
            progress = { (status.applyDone.toFloat() / status.applyTotal.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
    }
}

/** KMK (r93): "Applying sync from Tablet…", "Applying manga 1,800 of 4,097" and its bar */
@Composable
private fun ApplyCard(title: String, status: PairSync.Status) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                // KMK (r93): the rest is applied the next time the app opens
                TextButton(onClick = PairSync::stopSync) { Text("Stop") }
            }
            val detail = status.applyText
                ?: if (status.applyTotal > 0) "%,d of %,d".format(status.applyDone, status.applyTotal) else null
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ApplyBar(status)
            Text(
                "Stopped or interrupted? It carries on the next time the app opens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** KMK (r91): "Get:  Anime (2 of 4), Manga" (dimmer when it's nothing) */
@Composable
private fun SendGetLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // KMK (r91): a gap of its own, so a large font's "Send:" doesn't touch the value
            modifier = Modifier.widthIn(min = 44.dp).padding(end = 6.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (value == "nothing" || value == "…" || value.startsWith("unknown")) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What the last sync brought, and the extensions it can install here (only when tapped). KMK (r91): the card and
 * the offers each have an ×.
 */
@Composable
private fun ResultCard(
    result: PairSync.SyncResult,
    showCard: Boolean,
    showOffers: Boolean,
    onDismissCard: () -> Unit,
    onDismissOffers: () -> Unit,
) {
    val context = LocalContext.current
    val summary = result.summary
    if (showCard) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(start = 14.dp, top = 14.dp, bottom = 14.dp)) {
                    Text(
                        // KMK (r92): the bytes count both ways, so they're labelled as moved, and the two directions
                        // get their own lines (r91 showed only "Got 0 …" under "3.3 MB", which read as received)
                        "Synced with ${result.peerName}" + if (result.bytes > 0L && result.millis > 0L) {
                            " · ${sizeLabel(result.bytes)} moved in ${duration(result.millis)}"
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Sent: " + countsText(
                            summary.sentAnime to "anime",
                            summary.sentManga to "manga",
                            summary.sentHistoryEntries to "history",
                            summary.sentSources to "code sources",
                            summary.sentRepos to "repos",
                            summary.sentApks to "APKs",
                            // KMK (r92): covers and downloaded chapters / episodes
                            summary.sentCovers to "covers",
                            summary.sentDownloadItems to "downloads",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Got: " + countsText(
                            summary.anime to "anime",
                            summary.manga to "manga",
                            summary.historyEntries to "history",
                            summary.sourcesAdded to "code sources",
                            summary.reposAdded to "repos",
                            summary.apks.size to "APKs",
                            summary.gotCovers to "covers",
                            summary.gotDownloadItems to "downloads",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // KMK (r92): why covers and downloads didn't go (e.g. over Bluetooth)
                    summary.extrasSkipped?.takeIf { it.isNotBlank() }?.let { skipped ->
                        Text(
                            skipped,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (summary.errors > 0) {
                        Text(
                            "${summary.errors} couldn't be added (see Activity)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                CloseX(onClick = onDismissCard, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    if (!showOffers) return
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
            onDismiss = onDismissOffers,
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
            onDismiss = onDismissOffers,
        )
    }
}

/** KMK (r91): the × in a box's top-right corner */
@Composable
private fun CloseX(onClick: () -> Unit, tint: Color) {
    Icon(
        Icons.Filled.Close,
        contentDescription = "Dismiss",
        tint = tint,
        modifier = Modifier
            .padding(6.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(6.dp)
            .size(20.dp),
    )
}

@Composable
private fun OfferRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: String?,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (onDismiss == null) 12.dp else 0.dp),
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
            if (action != null) {
                FilledTonalButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(action, maxLines = 1)
                }
            }
            // KMK (r91): hides the offers and deletes the APKs waiting for them
            if (onDismiss != null) {
                Box(Modifier.align(Alignment.Top)) {
                    CloseX(onClick = onDismiss, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A "What this device sends" tile: tap it to turn it on or off, tap its subtitle to open its page */
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
    // KMK (r92): the library tiles' subtitles carry the extras too
    subtitleLines: Int = 1,
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
                        maxLines = subtitleLines,
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
    loaded: Boolean,
    picked: Set<String>?,
    onPick: (Set<String>?) -> Unit,
    note: String,
) {
    // Default ("") first: entries in no category
    val options = (listOf("") + categories).distinct()
    val optionSet = options.toSet()
    SwitchCard(
        icon = icon,
        title = title,
        // KMK (r91): which of this device's own categories it sends
        subtitle = when (picked) {
            null -> "All ${options.size} categories"
            else -> "${options.count { it in picked }} of ${options.size} categories"
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
        highlighted = true,
    )
    if (checked) {
        SectionLabel("Categories")
        // KMK (r91): picked only once the categories are read; a pick that no longer exists (renamed, deleted) is
        // dropped, so unpicking the real ones turns the tile off
        if (loaded) {
            ChipChoices(
                options = options.map { it to it.ifEmpty { "Default" } },
                picked = picked,
                onPick = { onPick(it?.intersect(optionSet)) },
            )
        } else {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
    Note(note)
}

/**
 * KMK (r92): this device's downloaded titles of one kind ([titles], biggest first; null while they're counted), or
 * [failed] when they couldn't be counted (it looked like an empty device)
 */
private class DownloadedList(val titles: List<DownloadedTitle>?, val failed: Boolean = false)

/**
 * KMK (r92): [DownloadedList] for the picker and the Also send row. Counted already (the core keeps the scan): shown
 * at once, so a rotation keeps the list (and its scroll) instead of a "Counting…" frame
 */
@Composable
private fun downloadedList(anime: Boolean): State<DownloadedList> {
    val counted = remember(anime) { runCatching { PairSyncData.downloadedTitlesNow(anime) }.getOrNull() }
    return produceState(DownloadedList(counted), anime) {
        value = withContext(Dispatchers.IO) {
            runCatching { DownloadedList(PairSyncData.downloadedTitles(anime)) }
                .getOrElse { DownloadedList(null, failed = true) }
        }
    }
}

/** KMK (r92): the keys of this device's downloaded titles of one kind, when [wanted] (null until known) */
@Composable
private fun downloadedKeys(anime: Boolean, wanted: Boolean): State<Set<String>?> {
    val counted = remember(anime) {
        runCatching { PairSyncData.downloadedTitlesNow(anime)?.map { it.key }?.toSet() }.getOrNull()
    }
    return produceState(counted, anime, wanted) {
        if (wanted) {
            value = withContext(Dispatchers.IO) {
                runCatching { PairSyncData.downloadedTitles(anime).map { it.key }.toSet() }.getOrNull()
            }
        }
    }
}

/** KMK (r92): "1 chapter", "2,140 chapters" */
private fun itemsText(count: Int, anime: Boolean): String {
    val word = if (anime) "episode" else "chapter"
    return if (count == 1) "1 $word" else "%,d ${word}s".format(count)
}

/** KMK (r92): "1 title", "38 titles" */
private fun titlesText(count: Int): String = if (count == 1) "1 title" else "%,d titles".format(count)

/** KMK (r92): " · unread only" / " · last 10" after a downloads count; nothing for all */
private fun modeSuffix(mode: String, anime: Boolean): String = when (mode) {
    "unread" -> if (anime) " · unwatched only" else " · unread only"
    "last10" -> " · last 10"
    else -> ""
}

/** KMK (r92): the covers this device would send of one kind, or [failed] when they couldn't be counted */
private class CoversCount(val count: Int, val bytes: Long, val failed: Boolean = false)

/**
 * KMK (r92): under a library page's categories: what each title already carries, then "Also send": covers (r93:
 * library titles only; "up to": only ones the other device lacks go) and downloaded chapters / episodes (› picks
 * which; only with the library tile on), with sizes that follow the Unread only / Last 10 choice
 */
@Composable
private fun AlsoSend(
    anime: Boolean,
    selection: SyncSelection,
    onChange: (SyncSelection) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val covers by produceState<CoversCount?>(null, anime, selection) {
        value = withContext(Dispatchers.IO) {
            runCatching { PairSyncData.coversEstimate(anime, selection).let { CoversCount(it.count, it.bytes) } }
                .getOrElse { CoversCount(0, 0L, failed = true) }
        }
    }
    val downloaded by downloadedList(anime)
    Note(
        if (anime) {
            "Each title already includes: info, episode list, seen marks, watch progress, categories, tracking."
        } else {
            "Each title already includes: info, chapter list, read marks, page progress, categories, tracking."
        },
    )
    SectionLabel("Also send")
    val coverCount = covers
    ExtraRow(
        title = "Covers",
        // KMK (r93): the library's titles only; history titles' covers are on the History page
        subtitle = "Library titles",
        size = when {
            coverCount == null -> "…"
            coverCount.failed -> "Couldn't count"
            coverCount.count > 0 -> "Up to %,d · %s".format(coverCount.count, bigSize(coverCount.bytes))
            else -> "None to send"
        },
        checked = selection.covers(anime),
        onCheckedChange = { onChange(selection.withCovers(anime, it)) },
    )
    val list = downloaded.titles
    val library = if (anime) selection.animeLibrary else selection.mangaLibrary
    val on = selection.downloads(anime)
    val keys = selection.downloadKeys(anime)
    val mode = selection.downloadMode(anime)
    val picked = list?.let { all -> if (keys == null) all else all.filter { it.key in keys } }.orEmpty()
    val pickedBytes = picked.sumOf { it.bytesFor(mode) }
    val pickedItems = itemsText(picked.sumOf { it.itemsFor(mode) }, anime)
    val subtitle = when {
        !library -> if (anime) "Turn on Send anime library first" else "Turn on Send manga library first"
        downloaded.failed -> "Couldn't count this device's downloads"
        list == null -> "Counting…"
        list.isEmpty() -> "Nothing downloaded on this device"
        keys == null -> "${titlesText(list.size)} · $pickedItems" + modeSuffix(mode, anime)
        else -> "${picked.size} of ${titlesText(list.size)} · $pickedItems" + modeSuffix(mode, anime)
    }
    ExtraRow(
        title = if (anime) "Downloaded episodes" else "Downloaded chapters",
        subtitle = subtitle,
        size = if (!library || list.isNullOrEmpty()) "" else bigSize(pickedBytes),
        // KMK (r92): only with the library tile on (the other device takes downloads only for titles it has)
        checked = on && library,
        // Nothing downloaded: it can only be turned off
        enabled = library && (on || !list.isNullOrEmpty()),
        onCheckedChange = { onChange(selection.withDownloads(anime, it, keys)) },
        onOpen = if (library) onOpenDownloads else null,
    )
    Note("Covers and downloads go over Wi-Fi or Wi-Fi Direct. Over Bluetooth only the library list goes.")
}

/**
 * KMK (r92): an "Also send" row: title, subtitle and size on the left (no avatar, so the switch and "›" fit at
 * 360dp), the switch, and with [onOpen] a "›" to its picker
 */
@Composable
private fun ExtraRow(
    title: String,
    subtitle: String,
    size: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    onOpen: (() -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = if (onOpen == null) 12.dp else 4.dp),
        ) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (size.isNotEmpty()) {
                    Text(
                        size,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            if (onOpen != null) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "Choose",
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
 * KMK (r92): which downloaded titles go (keys null = all; none ticked turns the downloads switch off) and how much
 * of each: all, unread / unwatched only, or the last 10. The list scrolls on its own (lazy: a few hundred titles
 * don't compose, or load covers, all at once).
 */
@Composable
private fun DownloadsPicker(anime: Boolean, selection: SyncSelection, onChange: (SyncSelection) -> Unit) {
    val downloaded by downloadedList(anime)
    val listState = rememberLazyListState()
    val list = downloaded.titles
    if (list == null) {
        if (downloaded.failed) {
            Note("Couldn't count this device's downloads. Try again in a moment.")
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                "Counting this device's downloads…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        return
    }
    if (list.isEmpty()) {
        Note(if (anime) "No downloaded episodes on this device." else "No downloaded chapters on this device.")
        return
    }
    val on = selection.downloads(anime)
    val keys = selection.downloadKeys(anime)
    val all = list.map { it.key }.toSet()
    val ticked: Set<String> = when {
        !on -> emptySet()
        keys == null -> all
        else -> keys.intersect(all)
    }
    val setTicked = { next: Set<String> ->
        onChange(
            when {
                next.isEmpty() -> selection.withDownloads(anime, false, null)
                next.containsAll(all) -> selection.withDownloads(anime, true, null)
                else -> selection.withDownloads(anime, true, next)
            },
        )
    }
    val mode = selection.downloadMode(anime)
    val bytes = list.filter { it.key in ticked }.sumOf { it.bytesFor(mode) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${ticked.size} of ${titlesText(list.size)} · ${bigSize(bytes)}" + modeSuffix(mode, anime),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        val everyOne = ticked.size == all.size
        TextButton(onClick = { setTicked(if (everyOne) emptySet() else all) }) {
            Text(if (everyOne) "Select none" else "Select all", maxLines = 1)
        }
    }
    val modes = if (anime) {
        listOf("all" to "All episodes", "unread" to "Unwatched only", "last10" to "Last 10")
    } else {
        listOf("all" to "All chapters", "unread" to "Unread only", "last10" to "Last 10")
    }
    val chosen = modes.indexOfFirst { it.first == mode }.coerceAtLeast(0)
    // KMK (r92): one choice (screen readers said checkboxes with the multi-choice row)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = chosen == index,
                onClick = { onChange(selection.withDownloadMode(anime, value)) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                // No tick, so the labels fit at 360dp
                icon = {},
            ) { FitText(label, color = MaterialTheme.colorScheme.onSurface) }
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        ) {
            items(list, key = { it.key }) { row ->
                val toggle = { setTicked(if (row.key in ticked) ticked - row.key else ticked + row.key) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = toggle)
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    // KMK (r92): through the app's cover loader (source headers, the library's copy, custom covers)
                    MangaCover.Book(data = row.cover ?: row.thumbnailUrl, modifier = Modifier.width(32.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${itemsText(row.itemsFor(mode), anime)} · ${bigSize(row.bytesFor(mode))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Checkbox(checked = row.key in ticked, onCheckedChange = { toggle() })
                }
            }
        }
    }
    Note(
        "Biggest first. " + if (anime) {
            "Unwatched only and Last 10 apply to each title."
        } else {
            "Unread only and Last 10 apply to each title."
        },
    )
}

/** KMK (r92): one part of a running sync: its name, its detail ("812 of 1,520") on its own line, its bar, ✓ */
@Composable
private fun PartCard(name: String, detail: String, fraction: Float, done: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (done) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = LiveGreen,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            // KMK (r92): on its own line (next to the name it squeezed "Manga downloads" to "Mang…" at 360dp)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { if (done) 1f else fraction.coerceIn(0f, 1f) },
                color = if (done) LiveGreen else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun HistoryPage(selection: SyncSelection, onChange: (SyncSelection) -> Unit) {
    val on = selection.mangaHistory || selection.animeHistory
    SwitchCard(
        icon = Icons.Outlined.History,
        title = "Send history",
        subtitle = "This device's recently watched and read",
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
    // KMK (r93): history titles' covers belong to this page (the Manga/Anime pages' Covers are library titles only)
    SectionLabel("Also send")
    ExtraRow(
        title = "Covers of history titles",
        subtitle = "So history shows them on the other device",
        size = "",
        checked = on && selection.historyCovers,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(historyCovers = it)) },
    )
    Note("History only adds entries on the other device; it never adds titles to its library.")
}

/**
 * KMK (r91): everything here is what this device SENDS: each switch turns a kind on or off, each "›" picks which of
 * this device's own repos, extensions, APKs and sources go (r90's switches were what it got)
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
        title = "Send extensions",
        subtitle = "Repos, extensions, APKs and code sources",
        checked = on,
        onCheckedChange = { onChange(selection.copy(extensions = it)) },
        highlighted = true,
    )
    SectionLabel("From repos")
    val repoUrls = repos.map { it.first }.distinct()
    SwitchCard(
        icon = Icons.Outlined.Storage,
        title = "Repos",
        subtitle = "The anime & manga repos the other device lacks",
        checked = selection.repos,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(repos = it)) },
        onOpen = { onOpen(Page.Repos) },
        sendLabel = sendLabel(repoUrls, selection.repoUrls),
    )
    SwitchCard(
        icon = Icons.Outlined.Extension,
        title = "Installed extensions",
        subtitle = "Offered there when missing or newer here",
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
        subtitle = "The APKs the other device lacks",
        checked = selection.sideloadedApks,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(sideloadedApks = it)) },
        onOpen = { onOpen(Page.ApkPicker) },
        sendLabel = sendLabel(apks, selection.apkKeys),
    )
    SwitchCard(
        icon = Icons.Outlined.Code,
        title = "Code sources",
        subtitle = "New and newer Code editor sources",
        checked = selection.sources,
        enabled = on,
        onCheckedChange = { onChange(selection.copy(sources = it)) },
        onOpen = { onOpen(Page.CodeSources) },
        sendLabel = sendLabel(sourceKeys, selection.sourceKeys),
    )
    Note("The other device is asked to accept, and each install there asks first. Nothing is removed.")
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
    // KMK (r91): back to r89: only Komikku devices in view; every other device (phones and computers first) folded
    // into one row
    val others = (named - tagged.toSet()).sortedBy { (d, _) -> PairSyncDiscovery.isUnlikely(d) }
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
            when {
                tagged.isNotEmpty() -> "Found it"
                scanning -> "Looking for Komikku nearby…"
                else -> "On the other device tap Pair device → Show a code first."
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

    SectionLabel("Komikku nearby")
    if (tagged.isEmpty()) {
        Note("On the other device tap Pair device → Show a code. It shows here when found.")
    }
    tagged.forEach { (d, name) ->
        DeviceRow(
            d,
            name,
            // KMK (r91): only while it's still the one picked
            if (tagged.size == 1 && chosenAddress == d.address) {
                "Showing a code · picked for you"
            } else {
                "Showing a code"
            },
            tagged = true,
            selected = chosenAddress == d.address,
        ) { pick(d) }
    }
    if (others.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { showOthers = !showOthers }
                .padding(vertical = 8.dp, horizontal = 4.dp),
        ) {
            Icon(
                if (showOthers) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${others.size} other ${if (others.size == 1) "device" else "devices"}" +
                    if (showOthers) "" else " hidden",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (showOthers) {
            others.forEach { (d, name) ->
                DeviceRow(d, name, d.address, tagged = false, selected = chosenAddress == d.address) { pick(d) }
            }
        }
    }

    val device = chosen
    SectionLabel("Enter its code")
    // KMK (r91): six boxes, one digit each (r89 style), driven by one hidden text field so the keyboard, backspace
    // and pasting all work
    CodeBoxes(
        code = code,
        enabled = !pairing,
        onCode = { code = it },
        // KMK (r91): the keyboard's Done pairs, like the button
        onDone = {
            if (device != null && code.length == 6 && !pairing) {
                PairSync.startPair(context.applicationContext, device, code)
            }
        },
    )
    if (device != null) {
        Note("Showing on ${runCatching { device.name }.getOrNull() ?: device.address}")
    }
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

/**
 * KMK (r91): the 6-digit code as six boxes. One [BasicTextField] holds the digits (its own text is laid out but not
 * shown): typing moves on a box, backspace goes back, a pasted code fills them all; the box being typed in is
 * outlined while the field has focus. Focus brings the boxes into view (above the keyboard), the keyboard is digits
 * only and its Done runs [onDone]; "Paste" takes the digits from the clipboard.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeBoxes(code: String, enabled: Boolean, onCode: (String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        // The cursor always at the end, so backspace takes the last digit
        value = TextFieldValue(code, TextRange(code.length)),
        onValueChange = { onCode(it.text.filter(Char::isDigit).take(6)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) {
                    scope.launch {
                        // After the keyboard has opened
                        delay(300L)
                        bringIntoView.bringIntoView()
                    }
                }
            },
        decorationBox = { innerTextField ->
            Box {
                // Laid out (so the field works fully) but not seen
                Box(Modifier.size(1.dp).alpha(0f)) { innerTextField() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    repeat(6) { index ->
                        val current = enabled && focused && index == code.length
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                // Before the background and border, so a disabled box dims as a whole
                                .alpha(if (enabled) 1f else 0.55f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .border(
                                    width = 2.dp,
                                    color = if (current) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp),
                                ),
                        ) {
                            Text(
                                code.getOrNull(index)?.toString().orEmpty(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
    )
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = {
                val text = runCatching {
                    context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                }.getOrNull().orEmpty()
                val digits = text.filter(Char::isDigit).take(6)
                if (digits.isNotEmpty()) onCode(digits)
            },
            enabled = enabled,
        ) { Text("Paste") }
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
        // KMK (r92): one line ("Anime › Downloaded episodes" wrapped at 360dp with a large font)
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    // KMK (r91): in capitals, as in the approved design
    Text(
        text.uppercase(),
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
                    // KMK (r91): picks that are no longer offered don't count
                    val now = (picked ?: all).intersect(all)
                    val next = if (value in now) now - value else now + value
                    // Every one picked means all (ones added later come along too)
                    onPick(if (next.containsAll(all)) null else next)
                },
                label = { Text(label) },
            )
        }
    }
}
