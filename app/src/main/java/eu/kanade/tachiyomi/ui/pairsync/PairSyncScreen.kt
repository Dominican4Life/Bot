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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.pairsync.PairSync
import eu.kanade.tachiyomi.pairsync.PairSyncPreferences
import eu.kanade.tachiyomi.pairsync.Peer
import eu.kanade.tachiyomi.pairsync.SyncSelection
import eu.kanade.tachiyomi.sumizuri.SumizuriManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPreference

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

/**
 * KMK: Sync with another device over Bluetooth: pair once with a 6-digit code, pick what to sync, and press Sync.
 * Both devices end up with everything from both. The other device needs this page open while syncing.
 */
class PairSyncScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("MissingPermission")
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val adapter = remember { PairSync.adapter(context) }
        val prefs = PairSyncPreferences.instance
        val status by PairSync.status.collectAsState()
        val code by PairSync.pairingCode.collectAsState()
        val peersVersion by remember { prefs.peersChanges }.collectAsState(initial = "")
        val peers = remember(peersVersion) { prefs.peers() }
        val selectionJson by prefs.selectionJson.collectPreference()
        val selection = remember(selectionJson) { PairSyncPreferences.parseSelection(selectionJson) }

        var granted by remember { mutableStateOf(hasPermissions(context)) }
        var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }
        var finding by remember { mutableStateOf(false) }
        var removing by remember { mutableStateOf<Peer?>(null) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted = hasPermissions(context) }
        val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            enabled = adapter?.isEnabled == true
        }

        // Listen for the other device while this page is open and Bluetooth is ready
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            granted = hasPermissions(context)
            enabled = adapter?.isEnabled == true
        }
        val ready = adapter != null && granted && enabled
        DisposableEffect(ready) {
            if (ready) PairSync.startListening(context.applicationContext)
            onDispose { PairSync.stopListening() }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { Text("Sync devices") },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    when {
                        adapter == null -> Notice("This device has no Bluetooth, so it can't sync this way.")
                        !granted -> Notice(
                            "Sync needs permission to use Bluetooth and find nearby devices.",
                            action = "Allow" to { permissionLauncher.launch(bluetoothPermissions()) },
                        )
                        !enabled -> Notice(
                            "Bluetooth is off.",
                            action = "Turn on" to {
                                enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            },
                        )
                        else -> Card {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(PairSync.deviceName(context), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Ready: a paired device can sync with this one while this page is open.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    SectionTitle("Paired devices")
                    if (peers.isEmpty()) {
                        Text(
                            "None yet. Pair a device below, once; after that, Sync works straight away.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    peers.forEach { peer ->
                        Card {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Devices, null)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(peer.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (peer.lastSync > 0) {
                                            "Last synced " + DateUtils.getRelativeTimeSpanString(peer.lastSync)
                                        } else {
                                            "Not synced yet"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { removing = peer }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Unpair")
                                }
                                Button(
                                    onClick = {
                                        PairSync.sync(context.applicationContext, peer, selection)
                                    },
                                    enabled = ready && !status.busy && !selection.isEmpty,
                                ) {
                                    Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                                    Text("Sync", Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }

                    SectionTitle("What to sync")
                    SelectionCard(selection, onChange = prefs::setSelection)

                    SectionTitle("Pair a new device")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = {
                                PairSync.startPairing()
                                // Let the other device find this one for a few minutes
                                runCatching {
                                    context.startActivity(
                                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
                                    )
                                }
                            },
                            enabled = ready && !status.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Show a code") }
                        OutlinedButton(
                            onClick = { finding = true },
                            enabled = ready && !status.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Enter a code") }
                    }
                    Text(
                        "On one device tap Show a code; on the other tap Enter a code, pick the first device and " +
                            "type its code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (status.busy || status.lines.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Activity", Modifier.weight(1f))
                            if (!status.busy) TextButton(onClick = PairSync::clearLog) { Text("Clear") }
                        }
                        Card {
                            Column {
                                if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
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
                    }
                }
            }
        }

        code?.let { shown ->
            ShowCodeDialog(code = shown, deviceName = PairSync.deviceName(context), onDismiss = PairSync::stopPairing)
        }
        if (finding) {
            FindDeviceDialog(onDismiss = { finding = false })
        }
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
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Notice(text: String, action: Pair<String, () -> Unit>? = null) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Text(text, modifier = Modifier.weight(1f))
            action?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
        }
    }
}

@Composable
private fun ShowCodeDialog(code: String, deviceName: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pairing code") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    code.chunked(3).joinToString(" "),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Text(
                    "On the other device, open Sync, tap Enter a code, pick \"$deviceName\" and type this code. " +
                        "Keep this showing until it says paired.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Waiting for the other device…", Modifier.padding(start = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Finds nearby devices (and ones already paired in Android's Bluetooth settings), then pairs with a code */
@SuppressLint("MissingPermission")
@Composable
private fun FindDeviceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adapter = remember { PairSync.adapter(context) }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<BluetoothDevice?>(null) }
    var code by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }

    DisposableEffect(round) {
        adapter?.bondedDevices.orEmpty().forEach { if (found.none { f -> f.address == it.address }) found += it }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
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
        onDispose {
            runCatching { adapter?.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val device = chosen
    AlertDialog(
        onDismissRequest = { if (!pairing) onDismiss() },
        title = { Text(if (device == null) "Pick the other device" else "Enter its code") },
        text = {
            if (device == null) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (scanning) "Looking for nearby devices…" else "Tap Show a code on the other device first.",
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
                    LazyColumn(Modifier.heightIn(max = 360.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(found.size) { index ->
                            val d = found[index]
                            val name = runCatching { d.name }.getOrNull()
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        chosen = d
                                        error = null
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(name ?: "Unnamed device", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    d.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                Column {
                    Text("The 6-digit code showing on ${runCatching { device.name }.getOrNull() ?: device.address}:")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        singleLine = true,
                        enabled = !pairing,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    if (pairing) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Pairing…", Modifier.padding(start = 10.dp))
                        }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (device != null) {
                TextButton(
                    enabled = code.length == 6 && !pairing,
                    onClick = {
                        pairing = true
                        error = null
                        scope.launch {
                            val result = PairSync.pair(context.applicationContext, device, code)
                            pairing = false
                            if (result.isSuccess) onDismiss() else error = result.exceptionOrNull()?.message
                        }
                    },
                ) { Text("Pair") }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !pairing,
                onClick = { if (device != null) chosen = null else onDismiss() },
            ) { Text(if (device != null) "Back" else "Cancel") }
        },
    )
}

/**
 * What to sync: the library (manga by category, anime), the Code editor sources (all or some), and history (manga,
 * anime, both or none).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionCard(selection: SyncSelection, onChange: (SyncSelection) -> Unit) {
    val categories by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { Injekt.get<GetCategories>().await().map { it.name } }.getOrDefault(emptyList())
        }
    }
    val sources by remember { Injekt.get<SumizuriManager>().savedFlow }.collectAsState()
    var chooseCategories by remember { mutableStateOf(false) }
    var chooseSources by remember { mutableStateOf(false) }

    Card {
        Column {
            GroupLabel("Library")
            ToggleRow(
                title = "Manga",
                subtitle = when (val picked = selection.mangaCategories) {
                    null -> "All categories"
                    else ->
                        "${picked.size} of ${categories.size} categories: " +
                            picked.joinToString { it.ifEmpty { "Default" } }
                },
                checked = selection.mangaLibrary,
                onCheckedChange = { onChange(selection.copy(mangaLibrary = it)) },
                chooseOpen = chooseCategories.takeIf { selection.mangaLibrary && categories.isNotEmpty() },
                onChoose = { chooseCategories = !chooseCategories },
            )
            if (chooseCategories && selection.mangaLibrary) {
                ChipChoices(
                    options = categories.map { it to it.ifEmpty { "Default" } },
                    picked = selection.mangaCategories,
                    onPick = { onChange(selection.copy(mangaCategories = it)) },
                )
            }
            ToggleRow(
                title = "Anime",
                subtitle = "The whole anime library (anime has no categories)",
                checked = selection.animeLibrary,
                onCheckedChange = { onChange(selection.copy(animeLibrary = it)) },
            )

            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            GroupLabel("Code editor sources")
            ToggleRow(
                title = "Sources",
                subtitle = when (val picked = selection.sourceKeys) {
                    null -> "All ${sources.size} sources you made"
                    else ->
                        "${picked.size} of ${sources.size}: " +
                            sources.filter { it.key in picked }.joinToString { it.name }
                },
                checked = selection.sources,
                onCheckedChange = { onChange(selection.copy(sources = it)) },
                chooseOpen = chooseSources.takeIf { selection.sources && sources.isNotEmpty() },
                onChoose = { chooseSources = !chooseSources },
            )
            if (chooseSources && selection.sources) {
                ChipChoices(
                    options = sources.map { it.key to "${it.name} (${if (it.anime) "anime" else "manga"})" },
                    picked = selection.sourceKeys,
                    onPick = { onChange(selection.copy(sourceKeys = it)) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            GroupLabel("History")
            val history = when {
                selection.mangaHistory && selection.animeHistory -> 0
                selection.mangaHistory -> 1
                selection.animeHistory -> 2
                else -> 3
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                listOf("Manga & anime", "Manga only", "Anime only", "None").forEachIndexed { index, label ->
                    FilterChip(
                        selected = history == index,
                        onClick = {
                            onChange(
                                selection.copy(
                                    mangaHistory = index == 0 || index == 1,
                                    animeHistory = index == 0 || index == 2,
                                ),
                            )
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Sync sends what's picked here both ways: each device keeps what it has and gets what the other " +
                    "has. Read and watched progress is kept from whichever is further along.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

/** A part to sync: tick it on or off; [chooseOpen] (when not null) shows a Choose button to narrow it down */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    chooseOpen: Boolean? = null,
    onChoose: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chooseOpen != null) {
            TextButton(onClick = onChoose) {
                Text(if (chooseOpen) "Done" else "Choose")
                Icon(
                    if (chooseOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
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
        modifier = Modifier.padding(start = 52.dp, end = 8.dp, bottom = 8.dp),
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
