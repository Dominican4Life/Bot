package eu.kanade.tachiyomi.pairsync

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * KMK: the pair sync protocol (r91).
 * 1. Bluetooth RFCOMM (insecure, SERVICE_UUID) is the control channel: finding the device, pairing, the key check, the
 *    accept prompt and choosing how the data moves. Every frame is an int length + AES-GCM (12-byte IV, ciphertext,
 *    16-byte tag).
 * 2. Pairing: {"t":"pair","name"} with the code's key -> {"t":"paired","name","secret"}; the secret is the key after.
 * 3. Live (r91, while the pop-up is open): a short connection, {"t":"live","send":[{"title","detail"}],"nothing"}
 *    both ways; each side shows what the other sends. Never busy, never a prompt.
 * 4. Sync: the initiator sends {"t":"sync","name","selection","have","transport","nonce","p2p","ask","send",
 *    "nothing","repos","vpn"}. A device's own selection is what it SENDS (r91). "selection" is only for an r90
 *    answerer (to it: what the initiator receives, so "everything"). "send" = the initiator's summary rows; "repos" =
 *    the packages its repos offer (gzip + Base64, one per line), so no APKs are sent for them; "p2p": false = it
 *    can't use Wi-Fi Direct; "vpn" = a VPN is on there. When both devices press Sync at once, the one whose request
 *    has the lower "nonce" keeps its sync and the other drops its own and answers it (accepted at once).
 * 5. A busy waiting device answers {"t":"busy"}. An r91 one ("ask") answers {"t":"asking"} and asks its user (60 s):
 *    {"t":"declined"} / {"t":"timeout"} end it with nothing changed; {"t":"accepted","send","nothing","repos","vpn"}
 *    goes on, and the initiator answers {"t":"start"} (or {"t":"cancel"} when it stopped; a dropped link is a cancel
 *    too). An r90 initiator (no "ask") is asked about silently; a decline answers it "busy".
 * 6. The waiting device offers ways in the order "transport" implies (auto: p2p, lan, bt; with a VPN on: p2p once,
 *    then bt): {"t":"p2p","ssid","pass","port","ip"}, {"t":"lan","ips","port"}, {"t":"bt"}, or {"t":"none"} when none
 *    is left. A failed Wi-Fi Direct is offered once more (a new group), except in Auto with a VPN on.
 * 7. The initiator joins / connects, sends {"t":"hello"} on the new socket, then answers over Bluetooth
 *    {"t":"ok","via"} or {"t":"fail"} (the next way is offered). After "ok" the waiting device takes the connection
 *    and checks "hello": it answers {"t":"go"}, or offers the next way (the initiator drops that connection). After
 *    "go" every data frame goes over the new socket, with the same key.
 * 8. Data: the waiting device sends its payload, then the backup frame and one raw frame per APK; the initiator merges
 *    the repos that came, then sends its own the same way; both merge everything that came (the sender's tiles chose
 *    it), then {"t":"done"} each way (only a courtesy: each side saves its result before it). Over Wi-Fi, a device
 *    building its export sends {"t":"wait"} every 30 s first; the reader skips those before the payload.
 * 9. Older versions: an r90 answerer sends an offer straight away (no "asking"): logged, and the sync goes on as r90
 *    did; what is sent is cut to what its payload's "wants" takes. An r89 waiting device ignores "transport" and sends
 *    its gzipped payload at once: the initiator sees a first frame that isn't JSON and carries on over Bluetooth the
 *    r89 way. An r89 initiator (no "transport") gets pure Bluetooth, the backup inside the JSON, and no APKs.
 * 10. Covers and downloads (r92): the request has "extras" (the version), "extrasOn" (its tiles send some),
 *    "extrasBytes" / "downloadBytes" (estimates, for the prompt's sizes); "accepted" has "extras" and "extrasOn". Only
 *    when both said "extras" and the data link isn't Bluetooth: after the library both ways, each side merges (with
 *    "wait"s over Wi-Fi meanwhile), then: the waiting device sends its manifest (gzipped JSON of items: covers, then
 *    downloaded chapters / episodes, each with its files' names and sizes), the initiator sends its own; the waiting
 *    device answers which of the initiator's items it needs (indexes), the initiator answers likewise; the waiting
 *    device sends the files it was asked for, then the initiator does. Each file goes in frames of at most 4 MiB, the
 *    first byte 0 (more follows), 1 (the end) or 2 (couldn't be read: dropped). Then "done" as before. The receiver
 *    finds each title by source + url and writes to its own places (temporary names, renamed when complete).
 */

/** KMK: A device this one has been paired with: its Bluetooth address, its name, and the key the two share */
@Serializable
data class Peer(
    val address: String,
    val name: String,
    val secret: String,
    val lastSync: Long = 0L,
)

/** KMK (r90): how the data moves once Bluetooth has checked the other device */
enum class SyncTransport(val key: String, val label: String) {
    Auto("auto", "Auto"),
    Wifi("wifi", "Wi-Fi"),
    WifiDirect("direct", "Wi-Fi Direct"),
    Bluetooth("bluetooth", "Bluetooth"),
    ;

    companion object {
        fun of(key: String?): SyncTransport = entries.firstOrNull { it.key == key } ?: Auto
    }
}

class PairSyncPreferences(private val store: PreferenceStore) {
    // KMK (r89): a private key, so the shared secrets stay out of backups unless "Private settings" is ticked (a
    // restored backup let another device sync as this one). The old plain key is merged in, then deleted.
    private val peersJson = store.getString(Preference.privateKey("pair_sync_peers"), "[]")

    /** What to sync, as JSON (see [SyncSelection]) */
    val selectionJson = store.getString("pair_sync_selection", "")
    val peersChanges get() = peersJson.changes()

    /** KMK (r90): how the data moves, a [SyncTransport] key */
    val transport: Preference<String> = store.getString("pair_sync_transport", SyncTransport.Auto.key)

    // KMK (r91): what each paired device sends, as last learned (live or a sync). App state: never in backups
    private val viewsJson = store.getString(Preference.appStateKey("pair_sync_views"), "[]")

    init {
        runCatching { migrateOld() }
    }

    /**
     * KMK (r90): merges the peers under the old plain key (r88, or a restored old backup) into the private list, by
     * secret and address (the ones here win), then deletes the old key. r89 deleted them when the list was set.
     */
    internal fun migrateOld() {
        val old = store.getString("pair_sync_peers", "[]")
        if (!old.isSet()) return
        val oldPeers = decodePeers(old.get())
        if (oldPeers.isNotEmpty()) {
            val here = peers()
            val merged = here + oldPeers.filter { o -> here.none { it.secret == o.secret || it.address == o.address } }
            if (merged.size != here.size || !peersJson.isSet()) peersJson.set(encodePeers(merged))
        }
        old.delete()
    }

    fun peers(): List<Peer> = decodePeers(peersJson.get())

    /** KMK (r90): a peer with the same key (its address changed) is replaced too, not kept twice */
    fun savePeer(peer: Peer) {
        peersJson.set(encodePeers(peers().filterNot { it.address == peer.address || it.secret == peer.secret } + peer))
    }

    fun removePeer(address: String) {
        peersJson.set(encodePeers(peers().filterNot { it.address == address }))
    }

    fun selection(): SyncSelection = parseSelection(selectionJson.get())

    fun setSelection(selection: SyncSelection) {
        selectionJson.set(Json.encodeToString(SyncSelection.serializer(), selection))
    }

    /** KMK (r91): the saved "what they send" of each paired device */
    internal fun views(): List<StoredView> =
        runCatching { lenient.decodeFromString(ListSerializer(StoredView.serializer()), viewsJson.get()) }
            .getOrDefault(emptyList())

    internal fun saveViews(views: List<StoredView>) {
        viewsJson.set(Json.encodeToString(ListSerializer(StoredView.serializer()), views))
    }

    companion object {
        val instance: PairSyncPreferences by lazy { PairSyncPreferences(Injekt.get()) }

        private val lenient = Json { ignoreUnknownKeys = true }

        fun parseSelection(json: String): SyncSelection =
            runCatching { lenient.decodeFromString(SyncSelection.serializer(), json) }.getOrDefault(SyncSelection())

        /** KMK (r90): run at app start, so old peers are merged even before pair sync is opened */
        fun migrate() {
            instance.migrateOld()
        }

        private fun decodePeers(json: String): List<Peer> =
            runCatching { Json.decodeFromString(ListSerializer(Peer.serializer()), json) }.getOrDefault(emptyList())

        private fun encodePeers(peers: List<Peer>): String =
            Json.encodeToString(ListSerializer(Peer.serializer()), peers)
    }
}

/** KMK (r91): what a paired device sends, saved (see [PairSyncPreferences.views]) */
@Serializable
internal class StoredView(
    val address: String,
    val send: List<SummaryRow> = emptyList(),
    val nothing: Boolean = false,
    val asOf: Long = 0L,
    val all: Boolean = false,
)

/**
 * KMK (r91): what this device knows about a paired one. [live]: its pop-up is open and answered recently.
 * [theirSend]: what it will send (= what this device gets), null = unknown; [asOf] when that was learned (live or
 * the last sync).
 */
data class PeerView(
    val address: String,
    val live: Boolean,
    val theirSend: List<SummaryRow>?,
    val theirSendsNothing: Boolean?,
    val asOf: Long,
    /** KMK (r91): it sends everything (every tile and switch on, nothing narrowed) */
    val theirSendsEverything: Boolean = false,
)

/**
 * KMK (r91): a paired device wants to sync with this one, which must Accept (within 60 s, until [expiresAt]).
 * [youGet] is what it sends to this device, [youSend] what this device sends back (its own tiles).
 */
data class IncomingRequest(
    val id: Long,
    val peerAddress: String,
    val peerName: String,
    val youGet: List<SummaryRow>,
    val youSend: List<SummaryRow>,
    val sizeText: String?,
    val via: SyncTransport?,
    val expiresAt: Long,
    // KMK (r92): the covers and downloads coming (estimated), this device's free space for them, the time they take
    // at the way's usual speed; [extrasNeedWifi]: they'd come but the link will be Bluetooth (skipped then)
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
    val enoughSpace: Boolean = true,
    val etaText: String? = null,
    val extrasNeedWifi: Boolean = false,
)

/**
 * KMK: Syncs two devices. One shows a 6-digit code and the other, nearby, enters it; after that the two share a
 * secret key and Sync talks to the paired device straight away, everything sent encrypted with that key. Bluetooth
 * finds and checks the other device; since r90 the data moves over Wi-Fi Direct or the same Wi-Fi when it can.
 * The device that isn't pressing Sync needs its Sync pop-up open (it listens while the pop-up is showing).
 */
@SuppressLint("MissingPermission")
object PairSync {

    /** The app's Bluetooth service: only Komikku answers on it */
    private val SERVICE_UUID: UUID = UUID.fromString("6f1c2a7e-4b8d-4e5a-9c3f-2d7b8e1a5c90")

    /** KMK (r90): the most a frame can be once its sender is known (the payload and backup frames are capped) */
    private const val MAX_FRAME = PairSyncData.MAX_PAYLOAD + 64

    /** KMK (r90): an r89 device's frames carry its APKs in Base64 inside the JSON; r89 allowed up to 512 MB */
    private const val LEGACY_FRAME = 512 * 1024 * 1024 + 64

    /** KMK (r90): smaller frames (offers, answers) don't move the progress bar */
    private const val CONTROL_FRAME = 64L * 1024

    /** IV and tag around each frame's data */
    private const val OVERHEAD = 28

    /**
     * KMK (r89): the most the first frame can be, before a key has shown who sent it. Up to r88 any nearby device
     * could make the waiting device allocate 256 MiB with one length.
     */
    private const val FIRST_FRAME = 256 * 1024

    /** How often (bytes) the progress bar moves */
    private const val PROGRESS_STEP = 128L * 1024

    /** KMK (r90): the Bluetooth answers that are offers (or refusals), not an r89 payload */
    private val OFFERS = setOf("p2p", "lan", "bt", "none", "busy", "go")

    /** KMK (r91): an r91 waiting device's answers to a sync request, before the offers */
    private val ANSWERS = setOf("asking", "accepted", "declined", "timeout")

    /** KMK (r91): how long the waiting device's prompt waits for Accept / Decline */
    private const val ASK_TIMEOUT = 60_000L

    /** KMK (r91): how long the device pressing Sync waits for that answer (the prompt, and a little more) */
    private const val ANSWER_WAIT = 90_000L

    /** KMK (r91): a live check-in's whole connection (Bluetooth connect + one message each way) */
    private const val LIVE_TIMEOUT = 4_000L

    /** KMK (r91): how often the paired devices are checked while the pop-up is open */
    private const val LIVE_EVERY = 20_000L

    /** KMK (r91): tile changes are sent to live devices once this long passed without another */
    private const val PUSH_DELAY = 400L

    /** KMK (r91): the most added to [LIVE_EVERY] at random, so two devices don't check in at the same moment */
    private const val LIVE_JITTER = 4_000

    /**
     * KMK (r91): after this long this device's own summary is read again in the background (the cached one answers
     * meanwhile); the tiles changing or a sync finishing clears it
     */
    private const val OWN_ROWS_FOR = 10_000L

    /** KMK (r91): how long a sync waits for the repos' lists when they weren't read yet (the read goes on) */
    private const val REPO_WAIT = 3_000L

    /** KMK (r91): how long this device's lost tie-break counts (the other device's request comes right after) */
    private const val TIE_FOR = 15_000L

    /** KMK (r92): how long a sync waits for its covers and downloads to be counted (for the other device's prompt) */
    private const val ESTIMATE_WAIT = 10_000L

    /** KMK (r92): the covers and downloads bars move at most this often (ms; covers come hundreds a second) */
    private const val EXTRAS_EVERY = 150L

    /** KMK (r92): how often the extras phase checks that its writes still move (see [Link.stalledMillis]) */
    private const val WRITE_CHECK = 10_000L

    // KMK (r92): the first byte of a file's frames in the extras phase
    private const val FILE_MORE: Byte = 0
    private const val FILE_END: Byte = 1
    private const val FILE_FAILED: Byte = 2

    /** KMK (r92): the result card's line when covers and downloads couldn't go over Bluetooth */
    private const val EXTRAS_NEED_WIFI = "Covers and downloads need Wi-Fi or Wi-Fi Direct"

    // KMK (r91): the prompt's outcomes
    private const val ACCEPT = 1
    private const val DECLINE = 2
    private const val TIMEOUT = 3
    private const val CANCELLED = 4

    /**
     * What's going on. While [busy], [peerAddress] is the device being synced with, and [done] of [total] bytes are
     * sent ([sending]) or received; [step] says what's being done when no bytes are moving. KMK (r90): [via] is how
     * the data moves once known, [bytesPerSecond] the current speed; [lastError] why the last sync with
     * [errorAddress] failed (cleared when the next one starts). r91: [waitingForAccept] while the other device's user
     * is asked to accept this device's sync.
     */
    data class Status(
        val busy: Boolean = false,
        val lines: List<String> = emptyList(),
        val peerAddress: String? = null,
        val sending: Boolean = false,
        val done: Long = 0L,
        val total: Long = 0L,
        val step: String? = null,
        val via: SyncTransport? = null,
        val bytesPerSecond: Long = 0L,
        val lastError: String? = null,
        val errorAddress: String? = null,
        val waitingForAccept: Boolean = false,
        /** KMK (r92): one bar per part (library, covers, downloads) while covers and downloads move */
        val parts: List<TransferPart> = emptyList(),
    )

    /**
     * KMK (r89): the last finished sync on this device: what came from [peerName] and what can be installed. r90: how
     * it went ([via]), the bytes moved both ways and how long it took.
     */
    data class SyncResult(
        val peerAddress: String,
        val peerName: String,
        val time: Long,
        val summary: SyncSummary,
        val missingAsked: Boolean = false,
        val updatesAsked: Boolean = false,
        val via: SyncTransport? = null,
        val bytes: Long = 0L,
        val millis: Long = 0L,
        /** KMK (r91): × on the card was tapped (its offers may still show); kept here so it sticks */
        val cardDismissed: Boolean = false,
    )

    // KMK (r89): nothing thrown in a connection may reach the app's crash handler
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            log("Sync error: ${e.message ?: e::class.simpleName}")
        },
    )
    private val prefs get() = PairSyncPreferences.instance
    private val random = SecureRandom()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _result = MutableStateFlow<SyncResult?>(null)
    val result: StateFlow<SyncResult?> = _result.asStateFlow()

    /** The code this device is showing for pairing, or null */
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    /** Set when a pairing finishes on this device (either side) */
    private val _justPaired = MutableStateFlow<Peer?>(null)
    val justPaired: StateFlow<Peer?> = _justPaired.asStateFlow()

    // KMK (r90): pairing from the Enter a code page runs here, so a rotation or back doesn't lose its result
    private val _pairing = MutableStateFlow(false)
    val pairing: StateFlow<Boolean> = _pairing.asStateFlow()

    /** KMK (r90): why the last pairing from this device failed, or null */
    private val _pairError = MutableStateFlow<String?>(null)
    val pairError: StateFlow<String?> = _pairError.asStateFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var serverJob: Job? = null

    // KMK (r90): Stop closes everything the running sync opened (Bluetooth and Wi-Fi sockets, the Wi-Fi Direct group;
    // that ends its blocking reads and writes) and cancels its job, on either side
    private val lock = Any()

    @Volatile private var session: Session? = null

    @Volatile private var syncJob: Job? = null

    /** Each sync's number: a stopped sync's late finish mustn't touch the next one's state */
    @Volatile private var runId = 0

    /** KMK (r90): the sync this device started, until its data starts moving (see [yieldTo]) */
    private class Outgoing(val id: Int, val secret: String, val nonce: Long)

    @Volatile private var outgoing: Outgoing? = null

    private val rate = Rate()

    // KMK (r91): the accept prompt (this device is asked), and the request this device is waiting on (asked there)
    private class Pending(val request: IncomingRequest, val decision: CompletableDeferred<Int>, val run: Int)

    @Volatile private var pending: Pending? = null

    private var incomingIds = 0L

    private val _incoming = MutableStateFlow<IncomingRequest?>(null)
    val incoming: StateFlow<IncomingRequest?> = _incoming.asStateFlow()

    /** KMK (r91): the Bluetooth link of this device's sync while it waits for the other's Accept (Stop says cancel) */
    @Volatile private var waitLink: Link? = null

    // KMK (r91): the live preview while the pop-up is open
    private val _peerViews = MutableStateFlow<Map<String, PeerView>>(emptyMap())
    val peerViews: StateFlow<Map<String, PeerView>> = _peerViews.asStateFlow()

    init {
        runCatching { _peerViews.value = loadViews() }
    }

    @Volatile private var liveJob: Job? = null

    @Volatile private var pushJob: Job? = null

    // KMK (r91): this device's own send summary (see [ownRows]); [ownVersion] changes when it's cleared, so a read
    // that started before doesn't put back an old one
    private class OwnSend(val rows: List<SummaryRow>, val all: Boolean, val at: Long)

    @Volatile private var ownSend: OwnSend? = null

    @Volatile private var ownVersion = 0

    private val ownRefreshing = AtomicBoolean(false)

    /** KMK (r91): changes on every goLive / leaveLive, so a check-in that ends late doesn't mark a device live */
    @Volatile private var liveGen = 0

    @Volatile private var liveContext: Context? = null

    /** KMK (r91): when each paired device was last heard from live (either way), by address */
    private val lastHeard = ConcurrentHashMap<String, Long>()

    /** KMK (r91): one outgoing Bluetooth connect at a time (two at once to the same device can fail on some phones) */
    private val btConnect = Mutex()

    /** KMK (r91): the check-ins' sockets, closed when a sync or pairing starts connecting */
    private val pingSockets: MutableSet<BluetoothSocket> = ConcurrentHashMap.newKeySet()

    /**
     * KMK (r91): both devices pressed Sync and the other one's request won, but this one's ended first: the other's
     * request (with this secret, until the time) is answered without a prompt
     */
    @Volatile private var tie: Pair<String, Long>? = null

    /** KMK (r91): when the running sync started (its received APKs stay when Clear empties the folder) */
    @Volatile private var runStartedAt = 0L

    /** KMK (r91): the sync that logged the VPN message already (said once) */
    @Volatile private var vpnSaidRun = -1

    /** KMK (r91): the result's read-change-write steps (a new result, Clear, the ×) one at a time */
    private val resultLock = Any()

    /** KMK (r91): the sync ended because both pressed Sync and the other device's won (it's answered instead) */
    private class Tied : IOException("Both devices pressed Sync")

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    /** KMK (r90): the runtime permissions Wi-Fi Direct needs on this Android version (the pop-up asks for them) */
    fun wifiPermissions(): Array<String> = PairSyncWifi.permissions()

    private fun log(text: String) {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        _status.update { it.copy(lines = (it.lines + "$time  $text").takeLast(40)) }
        AppLog.add('I', "sync", text)
    }

    fun clearLog() = _status.update { it.copy(lines = emptyList()) }

    private fun step(text: String) {
        rate.pause()
        _status.update { it.copy(step = text, done = 0L, total = 0L, bytesPerSecond = 0L) }
    }

    private fun idle() = _status.update {
        it.copy(
            busy = false,
            peerAddress = null,
            sending = false,
            done = 0L,
            total = 0L,
            step = null,
            via = null,
            bytesPerSecond = 0L,
            waitingForAccept = false,
            parts = emptyList(),
        )
    }

    /** Moves the progress bar, every [PROGRESS_STEP] bytes; KMK (r90): not for frames under [minTotal] */
    private fun progress(sending: Boolean, minTotal: Long = 0L): (Long, Long) -> Unit {
        var last = -1L
        return { done, total ->
            if (total >= minTotal && (last < 0L || done == total || done - last >= PROGRESS_STEP)) {
                last = done
                _status.update {
                    it.copy(sending = sending, done = done, total = total, step = null, bytesPerSecond = rate.perSecond)
                }
            }
        }
    }

    /** KMK (r89): the result card's Install / Update were tapped */
    fun markAsked(missing: Boolean = false, updates: Boolean = false) {
        synchronized(resultLock) {
            _result.update {
                it?.copy(missingAsked = it.missingAsked || missing, updatesAsked = it.updatesAsked || updates)
            }
        }
    }

    /** KMK (r90): marks this device busy for a new sync with [peerAddress]; its number and session, or null if busy */
    private fun begin(peerAddress: String): Pair<Int, Session>? = synchronized(lock) {
        if (_status.value.busy) return@synchronized null
        val id = ++runId
        val newSession = Session()
        session = newSession
        runStartedAt = System.currentTimeMillis()
        rate.reset()
        _status.update {
            it.copy(
                busy = true,
                peerAddress = peerAddress,
                sending = false,
                done = 0L,
                total = 0L,
                step = "Getting ready…",
                via = null,
                bytesPerSecond = 0L,
                lastError = null,
                errorAddress = null,
                waitingForAccept = false,
                parts = emptyList(),
            )
        }
        id to newSession
    }

    /** KMK (r90): sync [id] with [address] failed: logged, and shown under that device until the next sync */
    private fun failed(id: Int, address: String, text: String, error: String) {
        if (runId != id) return
        // KMK (r91): the VPN message was logged already in this sync: not twice
        log(if (error == PairSyncWifi.VPN_TEXT && vpnSaidRun == id) text.removeSuffix(": $error") else text)
        _status.update { it.copy(lastError = error, errorAddress = address) }
    }

    /**
     * KMK (r90): both devices pressed Sync at once. This one drops its own sync (to the same device, still choosing
     * the way) when the other's request has the lower [theirNonce], and answers it; the other one keeps its sync and
     * gets "busy" from here for its own. True when this device's sync was dropped.
     */
    private fun yieldTo(secret: String, theirNonce: Long?, name: String): Boolean {
        if (theirNonce == null) return false
        val (stopped, job) = synchronized(lock) {
            val out = outgoing
            if (out == null || out.id != runId || out.secret != secret || theirNonce >= out.nonce) return false
            runId++
            val running = session to syncJob
            session = null
            syncJob = null
            outgoing = null
            idle()
            running
        }
        stopped?.close()
        job?.cancel()
        log("Both devices pressed Sync: answering $name's")
        return true
    }

    /** KMK (r90): this device's own sync to the device with [secret] won the tie-break against [theirNonce] */
    private fun keepsOwn(secret: String, theirNonce: Long?): Boolean = synchronized(lock) {
        val out = outgoing
        theirNonce != null && out != null && out.id == runId && out.secret == secret && theirNonce >= out.nonce
    }

    /** KMK (r90): frees the pop-up after sync [id], unless it was stopped (a newer one may be running) */
    private fun finish(id: Int) {
        synchronized(lock) {
            if (runId == id) {
                session = null
                syncJob = null
                outgoing = null
                idle()
            }
        }
        // KMK (r91): the library may have changed: this device's own summary is read again
        clearOwnRows()
        // KMK (r92): and its covers counted again (the downloads scan only after downloads came, see afterReceive)
        PairSyncExtras.forget()
    }

    /** KMK (r91): logs the VPN message once per sync, worded for [transport] (Auto goes on over Bluetooth itself) */
    private fun sayVpn(id: Int, transport: SyncTransport) {
        if (vpnSaidRun == id) return
        vpnSaidRun = id
        log(if (transport == SyncTransport.Auto) PairSyncWifi.VPN_AUTO_TEXT else PairSyncWifi.VPN_TEXT)
    }

    /** KMK (r91): this device lost a tie-break with [secret]'s device but its request comes after: no prompt for it */
    private fun takeTie(secret: String): Boolean = synchronized(lock) {
        val t = tie
        if (t != null && t.first == secret && System.currentTimeMillis() < t.second) {
            tie = null
            true
        } else {
            false
        }
    }

    /**
     * KMK (r90): a new result keeps the previous one's untapped Install / Update offers unless it has its own (an
     * incoming sync used to clear them)
     */
    private fun publish(new: SyncResult) {
        // KMK (r91): under the lock, so a Clear or × meanwhile isn't undone with the old offers
        synchronized(resultLock) { publishLocked(new) }
    }

    private fun publishLocked(new: SyncResult) {
        val old = _result.value
        var summary = new.summary
        if (old != null) {
            if (summary.missing.isEmpty() && summary.apks.isEmpty() && !old.missingAsked) {
                val installed = runCatching { PairSyncData.installedPackages().toSet() }.getOrDefault(emptySet())
                summary = summary.copy(
                    missing = old.summary.missing.filter { it.pkgName !in installed },
                    apks = old.summary.apks.filter { it.file.isFile && it.pkgName !in installed },
                )
            }
            if (summary.updates.isEmpty() && !old.updatesAsked) summary = summary.copy(updates = old.summary.updates)
        }
        _result.value = new.copy(summary = summary, cardDismissed = false)
    }

    private fun hasOffers(result: SyncResult): Boolean =
        result.summary.missing.isNotEmpty() || result.summary.updates.isNotEmpty() || result.summary.apks.isNotEmpty()

    /**
     * KMK (r91): deletes the APK files [result] still offers; not once Install was tapped (the installer may be
     * reading them: they go when installed, at the next sync or after a day)
     */
    private fun deleteOffered(result: SyncResult?) {
        if (result == null || result.missingAsked) return
        result.summary.apks.forEach { runCatching { it.file.delete() } }
    }

    /** KMK (r91): "Clear": removes the result card and the extension offers, and deletes the waiting APK files */
    fun clearResult() {
        val last = synchronized(resultLock) {
            val value = _result.value
            _result.value = null
            value
        }
        deleteOffered(last)
        val installing = if (last != null && last.missingAsked) {
            last.summary.apks.map { it.file }.toSet()
        } else {
            emptySet()
        }
        // KMK (r91): a running sync's own new files stay (saved after it started; a little early for coarse times)
        PairSyncData.cleanApks(installing, if (_status.value.busy) runStartedAt - 2_000L else Long.MAX_VALUE)
    }

    /** KMK (r91): × on the result card; the offers stay until they're dismissed too */
    fun dismissResultCard() {
        synchronized(resultLock) {
            val last = _result.value ?: return
            _result.value = if (hasOffers(last)) last.copy(cardDismissed = true) else null
        }
    }

    /** KMK (r91): × on the extensions box: removes the offers and deletes their waiting APK files */
    fun dismissOffers() {
        val last = synchronized(resultLock) {
            val value = _result.value ?: return
            _result.value = if (value.cardDismissed) {
                null
            } else {
                value.copy(
                    summary = value.summary.copy(missing = emptyList(), updates = emptyList(), apks = emptyList()),
                )
            }
            value
        }
        deleteOffered(last)
    }

    /** KMK (r90): APK files the result card still offers (kept when the folder is emptied) */
    private fun offeredApks(): Set<java.io.File> =
        _result.value?.takeIf { !it.missingAsked }?.summary?.apks?.map { it.file }?.toSet() ?: emptySet()

    // Listening (the device that waits)

    /** Answers paired devices (and, while a code is showing, a device pairing) until [stopListening] */
    fun startListening(context: Context) {
        if (serverJob?.isActive == true) return
        val adapter = adapter(context)?.takeIf { it.isEnabled } ?: return
        serverJob = scope.launch {
            PairSyncData.cleanOldApks()
            val server = try {
                adapter.listenUsingInsecureRfcommWithServiceRecord("Komikku sync", SERVICE_UUID)
            } catch (e: Exception) {
                log("Couldn't start listening for the other device: ${e.message}")
                return@launch
            }
            serverSocket = server
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                // KMK (r90): not a child of this job, so closing the pop-up doesn't kill a running sync
                scope.launch { answer(context, socket) }
            }
        }
    }

    /** Stops answering new connections; a sync already running goes on (Stop ends it) */
    fun stopListening() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        _pairingCode.value = null
        PairSyncDiscovery.stopAdvertising()
    }

    /**
     * Shows a new code for pairing (the pop-up shows it); [stopPairing] hides it. With [context], this device also
     * advertises over Bluetooth LE so the other one tags it as Komikku.
     */
    fun startPairing(context: Context? = null): String {
        val code = (random.nextInt(900_000) + 100_000).toString()
        _justPaired.value = null
        _pairingCode.value = code
        if (context != null) PairSyncDiscovery.startAdvertising(context)
        return code
    }

    fun stopPairing() {
        _pairingCode.value = null
        PairSyncDiscovery.stopAdvertising()
    }

    private suspend fun answer(context: Context, socket: BluetoothSocket) {
        // KMK (r89): a dropped link or bad data used to escape here and crash the app later
        try {
            Link.bluetooth(socket).use { link ->
                val first = link.readFrame()
                val remote = socket.remoteDevice.address
                // A paired device (its own address first)
                val peers = prefs.peers().sortedByDescending { it.address == remote }
                for (peer in peers) {
                    val key = Base64.getDecoder().decode(peer.secret)
                    val message = runCatching { decrypt(key, first) }.getOrNull() ?: continue
                    link.key = key
                    link.maxFrame = MAX_FRAME
                    val parsed = parse(message)
                    // KMK (r91): a live check-in is answered at once, even while busy; "leaving" = its pop-up closed
                    when (parsed.string("t")) {
                        "live" -> answerLive(link, peer, parsed)
                        "leaving" -> {
                            lastHeard.remove(peer.address)
                            notLive(peer.address)
                        }
                        else -> answerSync(context, link, peer.copy(address = remote), parsed)
                    }
                    return
                }
                // A device pairing with the code showing here
                val code = _pairingCode.value
                if (code != null) {
                    val key = codeKey(code)
                    val message = runCatching { decrypt(key, first) }.getOrNull()
                    if (message != null) {
                        link.key = key
                        answerPair(context, link, remote, parse(message))
                        return
                    }
                    log("A device tried the wrong code")
                } else {
                    log("A device that isn't paired tried to connect")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("A connection stopped: ${e.message ?: e::class.simpleName}")
        }
    }

    /** KMK (r91): the other device's pop-up checked in: what it sends is noted, and this device's own goes back */
    private suspend fun answerLive(link: Link, peer: Peer, message: JsonObject) {
        val theirs = rowsIn(message).orEmpty()
        // KMK (r91): this pop-up is in the background (still listening): "away", so the other device shows it not live
        val here = liveJob != null
        seen(
            peer.address,
            theirs,
            message.string("nothing") == "true" || theirs.isEmpty(),
            live = if (here) true else null,
            all = message.string("all") == "true",
        )
        if (!here) {
            link.sendJson(buildJsonObject { put("t", "away") })
            return
        }
        link.sendJson(liveMessage(ownRows()))
    }

    private fun answerPair(context: Context, link: Link, remote: String, message: JsonObject) {
        if (message.string("t") != "pair") return
        val secret = ByteArray(32).also(random::nextBytes)
        val name = message.string("name") ?: "Other device"
        link.sendJson(
            buildJsonObject {
                put("t", "paired")
                put("name", deviceName(context))
                put("secret", Base64.getEncoder().encodeToString(secret))
            },
        )
        val peer = Peer(address = remote, name = name, secret = Base64.getEncoder().encodeToString(secret))
        prefs.savePeer(peer)
        _pairingCode.value = null
        PairSyncDiscovery.stopAdvertising()
        _justPaired.value = peer
        log("Paired with $name")
    }

    private suspend fun answerSync(context: Context, bt: Link, peer: Peer, message: JsonObject) {
        if (message.string("t") != "sync") return
        val name = message.string("name") ?: peer.name
        // KMK (r90): one sync at a time; the other device is told and shows it. Both pressing Sync at once: one wins.
        val theirNonce = message.string("nonce")?.toLongOrNull()
        var yielded = false
        var started = begin(peer.address)
        if (started != null) {
            // KMK (r91): both pressed Sync, the other's request won and this device's own sync ended first ("tie"):
            // it agreed already, no prompt
            if (takeTie(peer.secret)) {
                yielded = true
                log("Both devices pressed Sync: answering $name's")
            }
        } else if (yieldTo(peer.secret, theirNonce, name)) {
            // KMK (r91): this device pressed Sync too, so it agreed already: no prompt
            yielded = true
            started = begin(peer.address)
        }
        if (started == null) {
            // KMK (r91): "tie" = this device keeps its own sync; the other one answers it without a prompt (an
            // older version ignores the key)
            val tied = keepsOwn(peer.secret, theirNonce)
            runCatching {
                bt.sendJson(
                    buildJsonObject {
                        put("t", "busy")
                        if (tied) put("tie", true)
                    },
                )
            }
            // KMK (r90): both pressed Sync and this device's request won: not a sync "while busy"
            if (tied) {
                log("Both devices pressed Sync: keeping this device's")
            } else {
                log("$name tried to sync while this device is busy syncing")
            }
            return
        }
        val (id, session) = started
        val job = currentCoroutineContext()[Job]
        synchronized(lock) { if (runId == id) syncJob = job }
        val startTime = System.currentTimeMillis()
        // KMK (r91): "ask" = r91: the request has its summary and this device's user accepts it first
        val asks = message.string("ask") == "true"
        // KMK (r89): the packages the other device has (null from an older version), so only APKs it lacks are sent
        val otherHas = (message["have"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
        // KMK (r91): and the ones its repos offer (it's offered those from its repo instead)
        val otherRepos = PairSyncData.decodePackages(message.string("repos"))
        // KMK (r90): no "transport" = an r89 device: Bluetooth only, the r89 payload
        val transportKey = message.string("transport")
        val modern = transportKey != null
        // KMK (r91): what this device sends: its own tiles; an older device's "selection" is what it receives
        val own = prefs.selection().normalized()
        val sends = if (asks) {
            own
        } else {
            own.within(PairSyncPreferences.parseSelection(message.string("selection").orEmpty()))
        }
        val vpn = PairSyncWifi.vpnOn(context) || message.string("vpn") == "true"
        // KMK (r92): an r92 device sends and takes covers and downloads too (only over Wi-Fi or Wi-Fi Direct)
        val theirExtras = asks && (message.int("extras") ?: 0) >= PairSyncExtras.VERSION
        val theirExtrasOn = message.string("extrasOn") == "true"
        try {
            session.add(bt)
            bt.counter = rate::add
            if (!asks) log("$name is on an older version")
            if (!ask(context, bt, id, peer, name, message, sends, asks, modern, yielded)) return
            log("$name is syncing with this device")
            // KMK (r91): an r90 device that was asked silently can't say it stopped waiting: its link just drops
            val oldPrompt = !asks && !yielded
            PairSyncData.cleanApks(offeredApks())
            var link = bt
            var via = SyncTransport.Bluetooth
            // KMK (r90): an r89 device's payload can be bigger (its APKs are inside, in Base64)
            if (!modern) bt.maxFrame = LEGACY_FRAME
            if (modern) {
                val transport = SyncTransport.of(transportKey)
                // KMK (r90): "p2p": false = the other device can't use Wi-Fi Direct (no key: it may)
                val p2p = message.string("p2p") != "false"
                // KMK (r91): a forced Wi-Fi Direct that can't run on either device says why (not the VPN)
                val p2pProblem = when {
                    transport != SyncTransport.WifiDirect -> null
                    !p2p -> "Wi-Fi Direct can't run on $name"
                    else -> PairSyncWifi.p2pUnavailable(context)?.let { "Wi-Fi Direct can't run on this device: $it" }
                }
                val offered = try {
                    offerWays(context, bt, id, transport, session, p2p, vpn, name)
                } catch (e: IOException) {
                    if (oldPrompt && !session.isClosed) throw IOException("$name stopped waiting", e)
                    throw e
                }
                val chosen = offered ?: throw IOException(noWay(transport, vpn, p2pProblem))
                link = chosen.first
                via = chosen.second
            }
            _status.update { it.copy(via = via) }
            if (via != SyncTransport.Bluetooth) log("Connected over ${via.label}")
            step("Getting ready…")
            if (!modern && sends.syncsApks) log("Sideloaded APKs are only sent to a device on r90 or newer")
            val mine = keepAlive(link, via != SyncTransport.Bluetooth) {
                PairSyncData.export(sends, otherHas, otherRepos, raw = modern)
            }
            sendExport(link, mine)
            val received = receivePayload(link)
            log("Got ${size(rate.total)} in all from $name")
            step("Merging…")
            // KMK (r92): covers and downloads follow, so the other device waits for this merge ("wait"s meanwhile)
            val extras = theirExtras && via != SyncTransport.Bluetooth
            val summary = keepAlive(link, extras) { PairSyncData.import(received, ::log) }.withSent(mine.summary)
                .copy(
                    extrasSkipped = extrasSkipped(
                        name,
                        theirExtras,
                        via,
                        sends.hasExtras || theirExtrasOn,
                        sends.hasExtras,
                    ),
                )
            // KMK (r91): what it really sent is what it sends, until the next live check
            received.sentRows?.let { seen(peer.address, it, it.isEmpty(), live = null) }
            // KMK (r90): merged, so saved and shown first; "done" only tells the other device, which may have stopped
            // already (a failed "done" turned a finished sync into a failed one)
            val synced = peer.copy(lastSync = System.currentTimeMillis(), name = name)
            prefs.savePeer(synced)
            if (runId == id) {
                publish(
                    SyncResult(
                        peerAddress = synced.address,
                        peerName = synced.name,
                        time = synced.lastSync,
                        summary = summary,
                        via = via,
                        bytes = rate.total,
                        millis = synced.lastSync - startTime,
                    ),
                )
                log("Synced with $name over ${via.label}: $summary")
                summary.extrasSkipped?.let { log(it) }
            }
            // KMK (r92): then covers and downloads, this device first (it sent its library first too)
            val extrasOk = !extras ||
                extrasPhase(id, link, waiting = true, sends, name, peer.address, startTime, synced.lastSync)
            if (extrasOk) {
                runCatching {
                    link.sendJson(buildJsonObject { put("t", "done") })
                    link.receive()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val why = e.message ?: e::class.simpleName.orEmpty()
            failed(id, peer.address, "Sync with $name stopped: $why", why)
        } finally {
            session.close()
            finish(id)
        }
    }

    /**
     * KMK (r91): asks this device's user to accept [name]'s sync (60 s; [incoming] shows it). True = go on. An r91
     * device ([asks]) is told "asking" first and "accepted" / "declined" / "timeout" after, and answers "start" (or
     * "cancel" when it stopped); an older one isn't told (a decline answers an r90 device "busy"). After [yielded]
     * (this device pressed Sync too) it's accepted at once.
     */
    private suspend fun ask(
        context: Context,
        bt: Link,
        run: Int,
        peer: Peer,
        name: String,
        message: JsonObject,
        sends: SyncSelection,
        asks: Boolean,
        modern: Boolean,
        yielded: Boolean,
    ): Boolean {
        // KMK (r91): this device's repo packages, read while the prompt shows (they go with "accepted")
        val repos = if (asks) scope.async { reposText() } else null
        try {
            val youSend = runCatching { PairSyncData.summarizeSend(sends) }.getOrDefault(emptyList())
            val theirRows = rowsIn(message)
            theirRows?.let {
                seen(
                    peer.address,
                    it,
                    message.string("nothing") == "true" || it.isEmpty(),
                    live = null,
                    all = message.string("all") == "true",
                )
            }
            if (yielded) {
                if (!asks) return true
                sendAccepted(context, bt, youSend, sends.isEverything, repos?.await(), sends.hasExtras)
                return isStart(bt.receiveJson(), name, run, peer.address)
            }
            if (asks) bt.sendJson(buildJsonObject { put("t", "asking") })
            val decision = CompletableDeferred<Int>()
            // KMK (r92): the covers and downloads coming, and the room for them here
            val sizes = if (asks) runCatching { promptSize(context, message, modern) }.getOrNull() else null
            val request = IncomingRequest(
                id = synchronized(lock) { ++incomingIds },
                peerAddress = peer.address,
                peerName = name,
                youGet = theirRows ?: listOf(SummaryRow("Unknown", "$name is on an older version")),
                youSend = youSend,
                sizeText = null,
                via = if (modern) SyncTransport.of(message.string("transport")) else SyncTransport.Bluetooth,
                expiresAt = System.currentTimeMillis() + ASK_TIMEOUT,
                totalBytes = sizes?.total,
                freeBytes = sizes?.free,
                enoughSpace = sizes?.enough ?: true,
                etaText = sizes?.eta,
                extrasNeedWifi = sizes?.needWifi ?: false,
            )
            val asked = Pending(request, decision, run)
            synchronized(lock) { pending = asked }
            _incoming.value = request
            step("$name wants to sync")
            log("$name wants to sync: Accept or Decline")
            // KMK (r92): covers and downloads still counted ("checking…"): the prompt's rows follow once they are
            val latestSend = AtomicReference(youSend)
            val refresher = if (youSend.any { it.detail == PairSyncData.EXTRAS_CHECKING }) {
                scope.launch {
                    val rows = runCatching { PairSyncData.summarizeSend(sends, extrasWait = ASK_TIMEOUT) }.getOrNull()
                        ?: return@launch
                    latestSend.set(rows)
                    _incoming.update { if (it != null && it.id == request.id) it.copy(youSend = rows) else it }
                }
            } else {
                null
            }
            // KMK (r91): the other device's next frame is "start" (after Accept) or "cancel"; a dropped link cancels
            // too. Read apart, so the prompt closes as soon as it comes.
            val reader = if (asks) scope.async { runCatching { bt.receiveJson() } } else null
            reader?.invokeOnCompletion { decision.complete(CANCELLED) }
            val answer = try {
                withTimeoutOrNull(ASK_TIMEOUT) { decision.await() } ?: TIMEOUT
            } finally {
                refresher?.cancel()
                synchronized(lock) { if (pending === asked) pending = null }
                _incoming.update { if (it?.id == request.id) null else it }
            }
            when (answer) {
                ACCEPT -> {
                    if (reader == null) return true
                    sendAccepted(context, bt, latestSend.get(), sends.isEverything, repos?.await(), sends.hasExtras)
                    return isStart(reader.await().getOrNull(), name, run, peer.address)
                }
                CANCELLED -> {
                    // KMK (r91): shown under the device too (the prompt just closed)
                    failed(run, peer.address, "$name cancelled", "$name cancelled")
                    return false
                }
                else -> {
                    runCatching {
                        if (asks) {
                            bt.sendJson(buildJsonObject { put("t", if (answer == TIMEOUT) "timeout" else "declined") })
                        } else if (modern) {
                            bt.sendJson(buildJsonObject { put("t", "busy") })
                        }
                    }
                    log(if (answer == TIMEOUT) "$name's sync wasn't answered in time" else "Declined $name's sync")
                    return false
                }
            }
        } finally {
            repos?.cancel()
        }
    }

    private fun sendAccepted(
        context: Context,
        bt: Link,
        youSend: List<SummaryRow>,
        all: Boolean,
        repos: String?,
        extrasOn: Boolean,
    ) {
        bt.sendJson(
            buildJsonObject {
                put("t", "accepted")
                put("send", rowsJson(youSend))
                put("nothing", youSend.isEmpty())
                put("all", all)
                repos?.let { put("repos", it) }
                put("vpn", PairSyncWifi.vpnOn(context))
                // KMK (r92): this device does covers and downloads too
                put("extras", PairSyncExtras.VERSION)
                put("extrasOn", extrasOn)
            },
        )
    }

    /** KMK (r92): the prompt's sizes (see [IncomingRequest]) */
    private class PromptSize(
        val total: Long?,
        val free: Long?,
        val enough: Boolean,
        val eta: String?,
        val needWifi: Boolean,
    )

    /**
     * KMK (r92): what comes besides the library (the other device's estimate), this device's free space for it and the
     * time it takes at the usual speed of the way; null when nothing comes (or an older version asks)
     */
    private fun promptSize(context: Context, message: JsonObject, modern: Boolean): PromptSize? {
        if ((message.int("extras") ?: 0) < PairSyncExtras.VERSION) return null
        val bytes = message.string("extrasBytes")?.toLongOrNull() ?: 0L
        if (bytes <= 0L) return null
        val transport = if (modern) SyncTransport.of(message.string("transport")) else SyncTransport.Bluetooth
        if (transport == SyncTransport.Bluetooth) {
            return PromptSize(total = null, free = null, enough = true, eta = null, needWifi = true)
        }
        val downloads = (message.string("downloadBytes")?.toLongOrNull() ?: 0L) > 0L
        val free = PairSyncExtras.freeBytes(downloads)
        val direct = message.string("p2p") != "false" && PairSyncWifi.p2pUnavailable(context) == null
        val speed = when (transport) {
            SyncTransport.WifiDirect -> PairSyncExtras.P2P_SPEED
            SyncTransport.Wifi -> PairSyncExtras.LAN_SPEED
            else -> if (direct) PairSyncExtras.P2P_SPEED else PairSyncExtras.LAN_SPEED
        }
        return PromptSize(
            total = bytes,
            free = free,
            // KMK (r92): only a warning now (Accept stays on): the estimate counts things this device already has,
            // and the plan cuts what's asked for to the free space
            enough = free == null || bytes <= free,
            eta = PairSyncExtras.etaText(bytes, speed),
            needWifi = false,
        )
    }

    /** KMK (r91): the other device's answer to "accepted": "start" goes on; anything else (or nothing) cancelled */
    private fun isStart(reply: JsonObject?, name: String, run: Int, address: String): Boolean {
        if (reply?.string("t") == "start") return true
        failed(run, address, "$name cancelled", "$name cancelled")
        return false
    }

    /** KMK (r91): Accept / Decline on the prompt [id] ([incoming]) */
    fun answerIncoming(id: Long, accept: Boolean) {
        val asked = pending ?: return
        if (asked.request.id != id) return
        asked.decision.complete(if (accept) ACCEPT else DECLINE)
    }

    /** KMK (r91): the pop-up closed or went to the background: a prompt showing is declined (nobody can answer it) */
    fun declineIncoming() {
        pending?.let { answerIncoming(it.request.id, accept = false) }
    }

    /**
     * KMK (r91): this device's repo packages for the other device; the repos' lists are read first only when they
     * weren't yet, and waited for [REPO_WAIT] ms at most (the read goes on; what's in memory is sent)
     */
    private suspend fun reposText(): String? {
        if (!runCatching { PairSyncData.repoListsLoaded() }.getOrDefault(true)) {
            val read = scope.async { runCatching { PairSyncData.loadRepoLists() } }
            withTimeoutOrNull(REPO_WAIT) { read.await() }
        }
        return runCatching { PairSyncData.encodePackages(PairSyncData.repoCopies()) }.getOrNull()
    }

    /**
     * KMK (r90): the waiting device offers the ways [transport] allows, one by one, until the other device takes one;
     * the data link and the way, or null after telling it none is left. r91: a failed Wi-Fi Direct is tried once
     * more with a new group; with a [vpn] on (either device), Auto tries Wi-Fi Direct once, then Bluetooth.
     */
    private suspend fun offerWays(
        context: Context,
        bt: Link,
        run: Int,
        transport: SyncTransport,
        session: Session,
        p2p: Boolean,
        vpn: Boolean,
        name: String,
    ): Pair<Link, SyncTransport>? {
        val order = when (transport) {
            SyncTransport.Auto -> if (vpn) {
                log("A VPN is on: not trying your Wi-Fi")
                listOf(SyncTransport.WifiDirect, SyncTransport.Bluetooth)
            } else {
                listOf(SyncTransport.WifiDirect, SyncTransport.Wifi, SyncTransport.Bluetooth)
            }
            SyncTransport.Wifi -> listOf(SyncTransport.Wifi)
            SyncTransport.WifiDirect -> listOf(SyncTransport.WifiDirect)
            SyncTransport.Bluetooth -> listOf(SyncTransport.Bluetooth)
        }.filter { p2p || it != SyncTransport.WifiDirect }
        // KMK (r90): a faster way failed already (the step says so)
        var tried = false
        fun failedFast() {
            tried = true
            // KMK (r91): said once per sync
            if (vpn) sayVpn(run, transport)
        }
        for (way in order) {
            currentCoroutineContext().ensureActive()
            when (way) {
                SyncTransport.Bluetooth -> {
                    bt.sendJson(buildJsonObject { put("t", "bt") })
                    return bt to SyncTransport.Bluetooth
                }
                SyncTransport.Wifi -> {
                    val ips = PairSyncWifi.lanAddresses()
                    if (ips.isEmpty()) {
                        log("Not using Wi-Fi: this device isn't on a Wi-Fi network")
                    } else {
                        step(if (tried) "Trying your Wi-Fi…" else "Opening Wi-Fi…")
                        val link = offerSocket(bt, session) { port ->
                            buildJsonObject {
                                put("t", "lan")
                                put("ips", JsonArray(ips.map { JsonPrimitive(it) }))
                                put("port", port)
                            }
                        }
                        if (link != null) return link to SyncTransport.Wifi
                        failedFast()
                    }
                }
                SyncTransport.WifiDirect -> {
                    // KMK (r91): the first try often failed and the second worked: once more, from scratch
                    val attempts = if (transport == SyncTransport.Auto && vpn) 1 else 2
                    for (attempt in 1..attempts) {
                        currentCoroutineContext().ensureActive()
                        step(if (attempt == 1) "Starting Wi-Fi Direct…" else "Trying Wi-Fi Direct again…")
                        val owner = session.add(PairSyncWifi.Owner(context))
                        val group = owner.start(::log)
                        val link = if (group != null) {
                            // KMK (r90): on all addresses: the group's own one may not be set up yet ("hello" checks
                            // who connected)
                            offerSocket(bt, session) { port ->
                                buildJsonObject {
                                    put("t", "p2p")
                                    put("ssid", group.ssid)
                                    put("pass", group.pass)
                                    put("port", port)
                                    group.address.hostAddress?.let { put("ip", it) }
                                }
                            }
                        } else {
                            log("Wi-Fi Direct: the group didn't start")
                            null
                        }
                        // The group stays up for the whole sync (the session removes it at the end)
                        if (link != null) return link to SyncTransport.WifiDirect
                        if (group != null) log("Wi-Fi Direct: $name couldn't join or connect")
                        session.remove(owner)
                        owner.close()
                        if (attempt < attempts) {
                            // Not again when it can't work here (permission, Wi-Fi off, ...)
                            if (PairSyncWifi.p2pUnavailable(context) != null) break
                            log("Trying Wi-Fi Direct once more")
                            // The framework removes the group in the background; the next one waits for it too
                            delay(1_500L)
                        }
                    }
                    failedFast()
                }
                SyncTransport.Auto -> Unit
            }
        }
        bt.sendJson(buildJsonObject { put("t", "none") })
        return null
    }

    /**
     * KMK (r90): opens a server for one connection (on all addresses), sends [offer] (with its port) over Bluetooth
     * and, when the other device says it connected, takes that one connection. It must send "hello" with the key
     * first, else it's closed. "go" tells the other device the connection is taken; null (after a refusal, or when
     * the connection didn't come) means the next way is offered, and the other device drops its connection then.
     */
    private fun offerSocket(bt: Link, session: Session, offer: (Int) -> JsonObject): Link? {
        val server = try {
            session.add(PairSyncWifi.server(null))
        } catch (e: IOException) {
            if (e.message == "Sync stopped") throw e
            log("Couldn't open a connection here: ${e.message}")
            return null
        } catch (e: Exception) {
            log("Couldn't open a connection here: ${e.message}")
            return null
        }
        try {
            bt.sendJson(offer(server.localPort))
            val reply = bt.receiveJson()
            if (reply.string("t") != "ok") return null
            val link = acceptLink(server, bt.key, session) ?: return null
            bt.sendJson(buildJsonObject { put("t", "go") })
            return link
        } finally {
            session.remove(server)
            runCatching { server.close() }
        }
    }

    /** KMK (r90): the one connection to [server], once it said "hello" with [key]; null when none came */
    private fun acceptLink(server: ServerSocket, key: ByteArray, session: Session): Link? {
        val socket = try {
            server.soTimeout = 15_000
            server.accept()
        } catch (_: IOException) {
            // KMK (r90): Stop closed the server: nothing more is tried (and the other device did nothing wrong)
            if (session.isClosed) throw IOException("Sync stopped")
            log("The other device didn't connect")
            return null
        }
        PairSyncWifi.tune(socket)
        val link = session.add(Link.tcp(socket))
        link.key = key
        link.counter = rate::add
        runCatching { socket.soTimeout = 15_000 }
        val hello = runCatching { link.receiveJson() }.getOrNull()
        if (hello?.string("t") != "hello") {
            session.remove(link)
            link.close()
            if (session.isClosed) throw IOException("Sync stopped")
            log("Something other than the paired device connected")
            return null
        }
        // KMK (r90): a link that dies without a reset (Wi-Fi off, out of range) ends the sync instead of hanging it
        runCatching { socket.soTimeout = PairSyncWifi.READ_TIMEOUT }
        link.maxFrame = MAX_FRAME
        return link
    }

    // Connecting (the device that presses Pair or Sync)

    /**
     * KMK (r90): pairs with [device], which is showing [code], in this object's scope: [pairing] while it runs, then
     * [justPaired] or [pairError]. Nothing happens while a sync or another pairing is running.
     */
    fun startPair(context: Context, device: BluetoothDevice, code: String) {
        val started = synchronized(lock) {
            if (_pairing.value || _status.value.busy) return@synchronized false
            _pairing.value = true
            // Busy, so no sync starts meanwhile (no peerAddress: there's no sync to stop)
            _status.update { it.copy(busy = true, peerAddress = null) }
            true
        }
        if (!started) {
            if (!_pairing.value) _pairError.value = "Wait for the sync to finish, then try again"
            return
        }
        _pairError.value = null
        scope.launch {
            try {
                val result = pair(context, device, code)
                _pairError.value = result.exceptionOrNull()?.let { it.message ?: it::class.simpleName }
            } finally {
                synchronized(lock) {
                    _pairing.value = false
                    _status.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun clearPairError() {
        _pairError.value = null
    }

    /** Pairs with [device], which is showing [code] on its Sync pop-up (blocking; run from [scope]) */
    private suspend fun pair(context: Context, device: BluetoothDevice, code: String): Result<Peer> {
        val name = runCatching { device.name }.getOrNull() ?: device.address
        return try {
            log("Pairing with $name…")
            connectLocked(context, device, null).use { link ->
                link.key = codeKey(code)
                link.sendJson(
                    buildJsonObject {
                        put("t", "pair")
                        put("name", deviceName(context))
                    },
                )
                val reply = try {
                    link.receiveJson()
                } catch (_: IOException) {
                    throw IOException("$name didn't accept the code. Check it, and that $name is showing it")
                }
                if (reply.string("t") != "paired") throw IOException("$name answered something unexpected")
                val peer = Peer(
                    address = device.address,
                    name = reply.string("name") ?: name,
                    secret = reply.string("secret") ?: throw IOException("No key from $name"),
                )
                prefs.savePeer(peer)
                _justPaired.value = peer
                log("Paired with ${peer.name}")
                Result.success(peer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Couldn't pair: ${e.message ?: e::class.simpleName}")
            Result.failure(e)
        }
    }

    /**
     * Syncs with [peer], which needs its Sync pop-up open. KMK (r91): [selection] is what this device SENDS; the
     * other device's user accepts first ([Status.waitingForAccept] meanwhile). This device's export is built after the
     * other's arrives (so it knows which sideloaded APKs the other device lacks, and has merged its repos). An all-off
     * selection only gets.
     */
    fun sync(context: Context, peer: Peer, selection: SyncSelection) {
        val (id, session) = begin(peer.address) ?: return
        val transport = SyncTransport.of(runCatching { prefs.transport.get() }.getOrNull())
        // KMK (r90): for when both devices press Sync at once (see yieldTo)
        val nonce = random.nextLong()
        synchronized(lock) { if (runId == id) outgoing = Outgoing(id, peer.secret, nonce) }
        // KMK (r90): started once it's stored, so a Stop right away cancels it too
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val startTime = System.currentTimeMillis()
            try {
                PairSyncData.cleanOldApks()
                PairSyncData.cleanApks(offeredApks())
                val own = selection.normalized()
                log(
                    if (own.isEmpty) {
                        "Getting ready to sync with ${peer.name} (sending nothing, only getting)…"
                    } else {
                        "Getting ready to sync with ${peer.name}, sending ${own.describe()}…"
                    },
                )
                // KMK (r91): for the other device's prompt, and the packages its repos offer here (it sends no APKs
                // for them)
                // KMK (r92): the covers and downloads this device sends, for the other device's prompt (counted
                // first, so the rows below have them too instead of "checking…")
                val extrasEstimate = if (own.hasExtras) {
                    runCatching { withTimeoutOrNull(ESTIMATE_WAIT) { PairSyncExtras.estimate(own) } }.getOrNull()
                } else {
                    null
                }
                val sendRows = runCatching { PairSyncData.summarizeSend(own) }.getOrDefault(emptyList())
                val myRepos = reposText()
                val vpnHere = PairSyncWifi.vpnOn(context)
                val device = adapter(context)?.getRemoteDevice(peer.address) ?: throw IOException("Bluetooth is off")
                log("Connecting to ${peer.name}…")
                step("Connecting…")
                connectLocked(context, device, session).use { bt ->
                    val key = Base64.getDecoder().decode(peer.secret)
                    bt.key = key
                    // KMK (r90): an r89 device answers with its whole payload (up to r89's limit); offers are small
                    bt.maxFrame = LEGACY_FRAME
                    bt.counter = rate::add
                    // KMK (r90): the other device doesn't offer Wi-Fi Direct when this one can't use it (Auto used to
                    // wait about a minute for it before trying the Wi-Fi)
                    val p2pWhy = if (transport == SyncTransport.Auto || transport == SyncTransport.WifiDirect) {
                        PairSyncWifi.p2pUnavailable(context)
                    } else {
                        null
                    }
                    if (p2pWhy != null) log("Not using Wi-Fi Direct: $p2pWhy")
                    bt.sendJson(
                        buildJsonObject {
                            put("t", "sync")
                            put("name", deviceName(context))
                            // KMK (r91): only for an r90 device, to which it's what this one receives: everything
                            // (pressing Sync is the consent); r91 reads "send"
                            put("selection", Json.encodeToString(SyncSelection.serializer(), SyncSelection()))
                            put("have", JsonArray(PairSyncData.installedPackages().map { JsonPrimitive(it) }))
                            put("transport", transport.key)
                            put("nonce", nonce.toString())
                            put("p2p", p2pWhy == null)
                            // KMK (r91) -->
                            put("ask", true)
                            put("send", rowsJson(sendRows))
                            put("nothing", sendRows.isEmpty())
                            put("all", own.isEverything)
                            myRepos?.let { put("repos", it) }
                            put("vpn", vpnHere)
                            // KMK <--
                            // KMK (r92): covers and downloads (see the protocol, 10)
                            put("extras", PairSyncExtras.VERSION)
                            put("extrasOn", own.hasExtras)
                            extrasEstimate?.let { (bytes, downloadBytes) ->
                                put("extrasBytes", bytes)
                                put("downloadBytes", downloadBytes)
                            }
                        },
                    )
                    val answer = awaitAnswer(bt, id, peer)
                    var frame = answer.frame
                    val vpn = vpnHere || answer.theirVpn
                    // KMK (r91): an r90 device offers a way at once (it doesn't ask its user)
                    if (!answer.asked && offerIn(frame)?.string("t") != "busy") {
                        log("${peer.name} is on an older version")
                    }
                    // KMK (r90): the offers, until one works; an r89 device sends its payload instead
                    var link = bt
                    var via = SyncTransport.Bluetooth
                    var modern = false
                    var legacyPayload: ByteArray? = null
                    var failedWay: SyncTransport? = null
                    while (true) {
                        val offer = offerIn(frame)
                        if (offer == null) {
                            if (modern) throw IOException("${peer.name} answered something unexpected")
                            // KMK (r90): Wi-Fi or Wi-Fi Direct was picked, but an r89 device only does Bluetooth
                            if (transport == SyncTransport.Wifi || transport == SyncTransport.WifiDirect) {
                                log("${peer.name} is on an older version: using Bluetooth")
                            }
                            legacyPayload = frame
                            break
                        }
                        if (!modern) {
                            modern = true
                            bt.maxFrame = MAX_FRAME
                        }
                        val fast = when (offer.string("t")) {
                            "busy" -> if (offer.string("tie") == "true") {
                                // KMK (r91): both pressed Sync and the other device keeps its own: its request
                                // comes next and is answered here without a prompt (this user pressed Sync too)
                                // (not when this sync was dropped already: it answered that request then)
                                synchronized(lock) {
                                    if (runId == id) tie = peer.secret to System.currentTimeMillis() + TIE_FOR
                                }
                                throw Tied()
                            } else {
                                throw IOException("${peer.name} is busy syncing")
                            }
                            "none" -> throw IOException(
                                noWay(transport, vpn, p2pWhy?.let { "Wi-Fi Direct can't run on this device: $it" }),
                            )
                            "bt" -> break
                            "lan" -> joinLan(offer, key, session, peer.name, failedWay)
                            "p2p" -> joinP2p(context, offer, key, session, peer.name)
                            else -> throw IOException("${peer.name} answered something unexpected")
                        }
                        if (fast == null) {
                            bt.sendJson(buildJsonObject { put("t", "fail") })
                        } else {
                            bt.sendJson(
                                buildJsonObject {
                                    put("t", "ok")
                                    put("via", fast.via.key)
                                },
                            )
                        }
                        step("Waiting for ${peer.name}…")
                        frame = bt.receive()
                        if (fast == null) {
                            failedWay = failedWay ?: offerWay(offer)
                        } else if (offerIn(frame)?.string("t") == "go") {
                            // KMK (r90): "go" = the other device took the connection; else it offers the next way
                            link = fast.link
                            via = fast.via
                            break
                        } else {
                            log("${peer.name} couldn't use the ${fast.via.label} connection")
                            fast.close(session)
                            failedWay = failedWay ?: fast.via
                        }
                        // KMK (r91): said once per sync
                        if (vpn) sayVpn(id, transport)
                    }
                    synchronized(lock) { if (outgoing?.id == id) outgoing = null }
                    _status.update { it.copy(via = via) }
                    if (via != SyncTransport.Bluetooth) log("Connected over ${via.label}")
                    val received = if (legacyPayload != null) {
                        readPayload(link, legacyPayload)
                    } else {
                        receivePayload(link)
                    }
                    // KMK (r91): an r90 device's "wants" is what it receives: only that is sent (r89 doesn't say)
                    val sends = if (answer.asked) own else received.wants?.let { own.within(it) } ?: own
                    step("Getting ready to send…")
                    val mine = keepAlive(link, via != SyncTransport.Bluetooth) {
                        // KMK (r91): which extensions are sideloaded here, from before the merge below (after it, one
                        // the other device's repos offer counted as from a repo: neither listed nor sent)
                        val sideloadedBefore = if (sends.syncsApks) {
                            runCatching {
                                PairSyncData.loadRepoLists()
                                PairSyncData.sideloaded()
                            }.getOrNull()
                        } else {
                            null
                        }
                        // KMK (r91): the other device's repos first: an extension here they offer isn't sideloaded
                        PairSyncData.mergeRepos(received, ::log)
                        PairSyncData.export(
                            sends,
                            received.installed,
                            answer.theirRepos,
                            raw = modern,
                            sideloadedBefore = sideloadedBefore,
                        )
                    }
                    log("Got ${size(rate.total)} from ${peer.name}, sending ${size(mine.payload.size.toLong())}…")
                    sendExport(link, mine)
                    step("Merging…")
                    // KMK (r92): covers and downloads follow, so the other device waits for this merge ("wait"s)
                    val theirExtras = answer.asked && answer.theirExtras
                    val extras = theirExtras && via != SyncTransport.Bluetooth
                    val summary = keepAlive(link, extras) { PairSyncData.import(received, ::log) }
                        .withSent(mine.summary)
                        .copy(
                            extrasSkipped = extrasSkipped(
                                peer.name,
                                theirExtras,
                                via,
                                sends.hasExtras || answer.theirExtrasOn,
                                sends.hasExtras,
                            ),
                        )
                    // KMK (r91): what it really sent is what it sends, until the next live check
                    received.sentRows?.let { seen(peer.address, it, it.isEmpty(), live = null) }
                    // KMK (r90): merged, so saved and shown first; "done" only tells the other device, which may have
                    // stopped already (a failed "done" turned a finished sync into a failed one)
                    val synced = peer.copy(lastSync = System.currentTimeMillis())
                    prefs.savePeer(synced)
                    if (runId == id) {
                        publish(
                            SyncResult(
                                peerAddress = synced.address,
                                peerName = synced.name,
                                time = synced.lastSync,
                                summary = summary,
                                via = via,
                                bytes = rate.total,
                                millis = synced.lastSync - startTime,
                            ),
                        )
                        log("Synced with ${peer.name} over ${via.label}: $summary")
                        summary.extrasSkipped?.let { log(it) }
                    }
                    // KMK (r92): then covers and downloads, the other device first
                    val extrasOk = !extras ||
                        extrasPhase(
                            id,
                            link,
                            waiting = false,
                            sends,
                            peer.name,
                            peer.address,
                            startTime,
                            synced.lastSync,
                        )
                    if (extrasOk) {
                        runCatching {
                            link.sendJson(buildJsonObject { put("t", "done") })
                            link.receive()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Tied) {
                if (runId == id) log("Both devices pressed Sync: ${peer.name}'s goes on")
            } catch (e: Throwable) {
                val why = e.message ?: e::class.simpleName.orEmpty()
                failed(id, peer.address, "Sync stopped: $why", why)
            } finally {
                session.close()
                finish(id)
            }
        }
        synchronized(lock) { if (runId == id) syncJob = job }
        job.start()
    }

    /**
     * KMK (r91): the waiting device's answer to the request: [frame] is its first frame after it (an offer, or an r89
     * payload); [asked] = it's r91 and its user accepted; [theirRepos] the packages its repos offer
     */
    private class Answer(
        val frame: ByteArray,
        val asked: Boolean,
        val theirRepos: Map<String, List<RepoCopy>>?,
        val theirVpn: Boolean,
        /** KMK (r92): it does covers and downloads ([theirExtrasOn]: its tiles send some) */
        val theirExtras: Boolean = false,
        val theirExtrasOn: Boolean = false,
    )

    /**
     * KMK (r91): waits for the other device's user to Accept (up to [ANSWER_WAIT]; Stop sends "cancel" meanwhile).
     * Declined or not answered throws, with the message shown under the device.
     */
    private fun awaitAnswer(bt: Link, id: Int, peer: Peer): Answer {
        synchronized(lock) { if (runId == id) waitLink = bt }
        step("Waiting for ${peer.name}…")
        val gaveUp = AtomicBoolean(false)
        val watchdog = scope.launch {
            delay(ANSWER_WAIT)
            gaveUp.set(true)
            runCatching { bt.close() }
        }
        var asked = false
        fun next(): ByteArray = try {
            bt.receive(progress(sending = false, minTotal = CONTROL_FRAME))
        } catch (e: IOException) {
            when {
                gaveUp.get() -> throw IOException("${peer.name} didn't answer")
                asked -> throw e
                else -> throw IOException("${peer.name} didn't answer. Open Sync on it, then try again")
            }
        }
        try {
            var frame = next()
            while (true) {
                val reply = answerIn(frame) ?: return Answer(frame, asked = false, theirRepos = null, theirVpn = false)
                when (reply.string("t")) {
                    "asking" -> {
                        asked = true
                        synchronized(lock) { if (runId == id) _status.update { it.copy(waitingForAccept = true) } }
                        step("Waiting for ${peer.name} to accept…")
                    }
                    "accepted" -> {
                        watchdog.cancel()
                        rowsIn(reply)?.let {
                            seen(
                                peer.address,
                                it,
                                reply.string("nothing") == "true",
                                live = null,
                                all = reply.string("all") == "true",
                            )
                        }
                        synchronized(lock) {
                            if (waitLink === bt) waitLink = null
                            if (runId == id) _status.update { it.copy(waitingForAccept = false) }
                        }
                        log("${peer.name} accepted")
                        bt.sendJson(buildJsonObject { put("t", "start") })
                        step("Waiting for ${peer.name}…")
                        return Answer(
                            frame = next(),
                            asked = true,
                            theirRepos = PairSyncData.decodePackages(reply.string("repos")),
                            theirVpn = reply.string("vpn") == "true",
                            theirExtras = (reply.int("extras") ?: 0) >= PairSyncExtras.VERSION,
                            theirExtrasOn = reply.string("extrasOn") == "true",
                        )
                    }
                    "declined" -> throw IOException("${peer.name} declined")
                    else -> throw IOException("${peer.name} didn't answer")
                }
                frame = next()
            }
        } finally {
            watchdog.cancel()
            synchronized(lock) {
                if (waitLink === bt) waitLink = null
                if (runId == id) _status.update { it.copy(waitingForAccept = false) }
            }
        }
    }

    /** KMK (r91): an r91 waiting device's answer to the request ("asking", "accepted", ...), or null */
    private fun answerIn(frame: ByteArray): JsonObject? {
        if (frame.isEmpty() || frame[0] != '{'.code.toByte()) return null
        val message = runCatching { parse(frame) }.getOrNull() ?: return null
        return message.takeIf { m -> m.string("t")?.let { it in ANSWERS } == true }
    }

    /** KMK (r90): an offer (or refusal) from an r90 device, or null when [frame] is an r89 payload (gzip) */
    private fun offerIn(frame: ByteArray): JsonObject? {
        if (frame.isEmpty() || frame[0] != '{'.code.toByte()) return null
        val message = runCatching { parse(frame) }.getOrNull() ?: return null
        return message.takeIf { m -> m.string("t")?.let { it in OFFERS } == true }
    }

    private fun noWay(transport: SyncTransport, vpn: Boolean, p2pProblem: String? = null): String = when {
        // KMK (r91): the real reason when Wi-Fi Direct can't run on a device (not the VPN)
        transport == SyncTransport.WifiDirect && p2pProblem != null -> p2pProblem
        // KMK (r91): the likely reason, said plainly
        vpn && (transport == SyncTransport.WifiDirect || transport == SyncTransport.Wifi) -> PairSyncWifi.VPN_TEXT
        transport == SyncTransport.WifiDirect -> "Couldn't connect over Wi-Fi Direct"
        transport == SyncTransport.Wifi -> "The two devices aren't on the same Wi-Fi"
        else -> "Couldn't connect to the other device"
    }

    /** KMK (r90): the way an offer is for */
    private fun offerWay(offer: JsonObject): SyncTransport? = when (offer.string("t")) {
        "lan" -> SyncTransport.Wifi
        "p2p" -> SyncTransport.WifiDirect
        else -> null
    }

    /**
     * KMK (r90): a data connection this device made, and what else it opened for it (the Wi-Fi Direct group it
     * joined); [close] drops both when the other device didn't take it
     */
    private class Joined(val link: Link, val via: SyncTransport, val extra: Closeable? = null) {
        fun close(session: Session) {
            session.remove(link)
            link.close()
            extra?.let {
                session.remove(it)
                runCatching { it.close() }
            }
        }
    }

    /** KMK (r90): the other device's Wi-Fi addresses, tried one by one (again for about 5 s) */
    private suspend fun joinLan(
        offer: JsonObject,
        key: ByteArray,
        session: Session,
        name: String,
        failedWay: SyncTransport?,
    ): Joined? {
        val ips = (offer["ips"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val port = offer.int("port") ?: return null
        step(
            if (failedWay != null) {
                "${failedWay.label} didn't work, using your Wi-Fi…"
            } else {
                "Connecting over Wi-Fi…"
            },
        )
        // KMK (r90): a server that was just opened, or a Wi-Fi that's waking up, can miss a single 3 s try
        val socket = connectRetrying(ips, port, 5_000L)
        if (socket == null) {
            log("Couldn't reach $name over Wi-Fi")
            return null
        }
        return fastLink(socket, key, session)?.let { Joined(it, SyncTransport.Wifi) }
    }

    /** KMK (r90): joins the other device's Wi-Fi Direct group, then connects to it */
    private suspend fun joinP2p(
        context: Context,
        offer: JsonObject,
        key: ByteArray,
        session: Session,
        name: String,
    ): Joined? {
        val ssid = offer.string("ssid") ?: return null
        val pass = offer.string("pass") ?: return null
        val port = offer.int("port") ?: return null
        step("Joining $name's Wi-Fi Direct…")
        val client = session.add(PairSyncWifi.Client(context))
        val owner = client.join(ssid, pass, ::log)
        val socket = if (owner != null) {
            connectRetrying(listOfNotNull(owner.hostAddress, offer.string("ip")).distinct(), port)
        } else {
            null
        }
        val link = socket?.let { fastLink(it, key, session) }
        if (link == null) {
            // KMK (r91): which step failed
            log(
                if (owner == null) {
                    "Wi-Fi Direct: couldn't join $name's group"
                } else {
                    "Wi-Fi Direct: couldn't connect to $name"
                },
            )
            session.remove(client)
            client.close()
            return null
        }
        // The group is left when the sync ends (the session closes the client)
        return Joined(link, SyncTransport.WifiDirect, client)
    }

    /**
     * KMK (r90): the group owner's address can show up just before the route to it works, so the connection is tried
     * every half second for about 10 s ([windowMs])
     */
    private suspend fun connectRetrying(hosts: List<String>, port: Int, windowMs: Long = 10_000L): Socket? {
        if (hosts.isEmpty()) return null
        val until = System.currentTimeMillis() + windowMs
        while (true) {
            PairSyncWifi.connect(hosts, port, 2_000)?.let { return it }
            if (System.currentTimeMillis() >= until) return null
            delay(500L)
        }
    }

    /** KMK (r90): the data link over [socket]; "hello" shows the other device it's this one */
    private fun fastLink(socket: Socket, key: ByteArray, session: Session): Link? {
        val link = session.add(Link.tcp(socket))
        return try {
            link.key = key
            link.maxFrame = MAX_FRAME
            link.counter = rate::add
            // KMK (r90): a link that dies without a reset ends the sync instead of hanging it
            socket.soTimeout = PairSyncWifi.READ_TIMEOUT
            link.sendJson(buildJsonObject { put("t", "hello") })
            link
        } catch (e: IOException) {
            session.remove(link)
            link.close()
            null
        }
    }

    /** KMK (r90): the payload, then the backup frame and the APK frames it announces */
    private fun sendExport(link: Link, export: SyncExport) {
        link.send(export.payload, progress(sending = true))
        export.backup?.let { link.send(it, progress(sending = true)) }
        export.apks.forEach { (info, file) ->
            // A file that can't be read goes as an empty frame (the other side drops it), so the frames still match
            val bytes = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size.toLong() == info.size }
            link.send(bytes ?: ByteArray(0), progress(sending = true))
        }
    }

    /**
     * KMK (r90): runs [build] (this device's export) and, when [wifi], sends {"t":"wait"} every
     * [PairSyncWifi.KEEP_ALIVE] ms meanwhile, so the other device's read doesn't time out while a big library is
     * read here. Only before the payload, which [receivePayload] skips them for; never over Bluetooth (no time-out).
     */
    private suspend fun <T> keepAlive(link: Link, wifi: Boolean, build: suspend () -> T): T {
        if (!wifi) return build()
        return coroutineScope {
            val ping = launch {
                while (true) {
                    delay(PairSyncWifi.KEEP_ALIVE)
                    // A dead link shows in the next read anyway
                    if (runCatching { link.sendJson(buildJsonObject { put("t", "wait") }) }.isFailure) break
                }
            }
            try {
                build()
            } finally {
                // Waits for a "wait" being written, so the payload never goes in the middle of one
                ping.cancelAndJoin()
            }
        }
    }

    /** KMK (r90): the keep-alive [keepAlive] sends; the payload itself is gzip, never JSON */
    private fun isWait(frame: ByteArray): Boolean =
        frame.size < 64 && frame.firstOrNull() == '{'.code.toByte() &&
            runCatching { parse(frame).string("t") }.getOrNull() == "wait"

    private fun receivePayload(link: Link): ReceivedPayload {
        // KMK (r90): the bar ignores the tiny keep-alive frames (they'd show a full bar while nothing moves)
        val progress = progress(sending = false, minTotal = 1024L)
        var frame = link.receive(progress)
        while (isWait(frame)) frame = link.receive(progress)
        return readPayload(link, frame)
    }

    /**
     * KMK (r90): decodes [payload] and reads the frames it announces: the backup, then each APK, saved to the cache
     * when it's within the limits and not installed here (else read and dropped). r91: this device accepted the sync,
     * so its own tiles don't filter what comes.
     */
    private fun readPayload(link: Link, payload: ByteArray): ReceivedPayload {
        val received = PairSyncData.decode(payload)
        val announced = received.payload
        if (announced.backupFrame) received.backupBytes = link.receive(progress(sending = false))
        if (announced.apkFiles.isNotEmpty()) {
            val have = PairSyncData.installedPackages().toSet()
            var total = 0L
            val saved = mutableListOf<ReceivedApk>()
            announced.apkFiles.forEach { info ->
                val length = link.readLength()
                val data = length - OVERHEAD
                val take = info.pkgName !in have && data > 0 && data.toLong() == info.size &&
                    data <= PairSyncData.MAX_APK && total + data <= PairSyncData.MAX_APKS
                if (!take) {
                    link.skip(length)
                    return@forEach
                }
                total += data
                val bytes = link.open(link.readBody(length, progress(sending = false)))
                PairSyncData.saveApk(info, bytes)?.let { saved += it }
            }
            received.receivedApks = saved
        }
        return received
    }

    private fun SyncSummary.withSent(sent: SyncSummary): SyncSummary = copy(
        sentManga = sent.sentManga,
        sentAnime = sent.sentAnime,
        sentHistoryEntries = sent.sentHistoryEntries,
        sentSources = sent.sentSources,
        sentRepos = sent.sentRepos,
        sentExtensions = sent.sentExtensions,
        sentApks = sent.sentApks,
    )

    // Covers and downloads (KMK (r92); see the protocol, 10)

    /** KMK (r92): why covers and downloads didn't go, for the result card; null when they did (or none would) */
    private fun extrasSkipped(
        name: String,
        theirExtras: Boolean,
        via: SyncTransport,
        anyWould: Boolean,
        ownWould: Boolean,
    ): String? = when {
        !theirExtras -> if (ownWould) "$name is on an older version: covers and downloads weren't sent" else null
        via == SyncTransport.Bluetooth && anyWould -> EXTRAS_NEED_WIFI
        else -> null
    }

    /** KMK (r92): what the extras phase moved so far (kept when it stops halfway: the next sync carries on) */
    private class ExtrasCount {
        var sentCovers = 0
        var gotCovers = 0
        var sentItems = 0
        var gotItems = 0
        var sentBytes = 0L
        var gotBytes = 0L

        /** What this device needed but had no room for */
        var noRoom = 0

        val any: Boolean get() = sentCovers + gotCovers + sentItems + gotItems > 0

        fun text(): String = "sent $sentCovers covers and $sentItems downloads, got $gotCovers covers and " +
            "$gotItems downloads (${size(sentBytes + gotBytes)})"
    }

    /**
     * KMK (r92): covers and downloads both ways, after the library ([waiting]: this device answered the sync and goes
     * first). What moved goes on the result (published at [resultTime]) even when it stops halfway, Stop on this
     * device too, with why it stopped. False when it failed (the link may be broken: no "done" then). The device
     * stays awake meanwhile (a partial wake lock and a Wi-Fi lock).
     */
    private suspend fun extrasPhase(
        id: Int,
        link: Link,
        waiting: Boolean,
        own: SyncSelection,
        name: String,
        address: String,
        startTime: Long,
        resultTime: Long,
    ): Boolean {
        val count = ExtrasCount()
        var why: String? = "Stop was pressed"
        val awake = Awake(Injekt.get<Application>())
        try {
            awake.acquire()
            extras(link, waiting, own, name, count)
            why = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A Stop here closes the link first: that shows as a dropped connection
            why = if (runId != id) "Stop was pressed" else e.message ?: e::class.simpleName.orEmpty()
            if (runId == id) log("Covers and downloads stopped: $why")
        } finally {
            awake.release()
            val stopped = why?.let { "Covers and downloads stopped: $it" }
            addExtras(address, resultTime, count, System.currentTimeMillis() - startTime, stopped)
            if (count.any) log("Covers and downloads with $name: ${count.text()}")
        }
        return why == null
    }

    /**
     * KMK (r92): what the extras phase moved on the result card (and the bytes and time with it), with why it stopped
     * ([stopped]) and what didn't fit. Matched by device and time, not by the sync's number: on the device that pressed
     * Stop the number has moved on already.
     */
    private fun addExtras(address: String, resultTime: Long, count: ExtrasCount, millis: Long, stopped: String?) {
        val noRoom = count.noRoom.takeIf { it > 0 }?.let {
            if (it == 1) "1 item skipped: not enough space" else "%,d items skipped: not enough space".format(it)
        }
        synchronized(resultLock) {
            _result.update { last ->
                if (last == null || last.peerAddress != address || last.time != resultTime) {
                    last
                } else {
                    last.copy(
                        summary = last.summary.copy(
                            sentCovers = count.sentCovers,
                            gotCovers = count.gotCovers,
                            sentDownloadItems = count.sentItems,
                            gotDownloadItems = count.gotItems,
                            sentExtraBytes = count.sentBytes,
                            gotExtraBytes = count.gotBytes,
                            extrasSkipped = listOfNotNull(last.summary.extrasSkipped, stopped, noRoom)
                                .joinToString("\n")
                                .ifEmpty { null },
                        ),
                        bytes = rate.total,
                        millis = millis,
                    )
                }
            }
        }
    }

    /** KMK (r92): keeps the device and its Wi-Fi awake while covers and downloads move (a long transfer, screen off) */
    private class Awake(context: Context) {
        private val wake: PowerManager.WakeLock? = runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "komikku:pairsync")
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()

        private val wifi: List<WifiManager.WifiLock> = runCatching {
            val manager = context.applicationContext.getSystemService(WifiManager::class.java)
            if (manager == null) {
                emptyList()
            } else {
                buildList<WifiManager.WifiLock> {
                    add(manager.createWifiLock(highPerfMode(), "komikku:pairsync"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "komikku:pairsync-ll"))
                    }
                }
            }
        }.getOrDefault(emptyList())

        @Suppress("DEPRECATION")
        private fun highPerfMode(): Int = WifiManager.WIFI_MODE_FULL_HIGH_PERF

        fun acquire() {
            runCatching { wake?.acquire(AWAKE_FOR) }
            wifi.forEach { lock ->
                runCatching {
                    lock.setReferenceCounted(false)
                    lock.acquire()
                }
            }
        }

        fun release() {
            runCatching { wake?.let { if (it.isHeld) it.release() } }
            wifi.forEach { lock -> runCatching { if (lock.isHeld) lock.release() } }
        }
    }

    /** KMK (r92): the most the wake lock is held (it's released when the phase ends; this is only a safety net) */
    private const val AWAKE_FOR = 6L * 60L * 60L * 1000L

    /**
     * KMK (r92): the manifests both ways, the answers (what each needs) both ways, then the files: the waiting device
     * ([waiting]) sends each first. Frames in the same order on both sides; building a manifest or an answer sends
     * "wait"s meanwhile, which every read here skips. A write that doesn't move for [PairSyncWifi.READ_TIMEOUT] ms
     * closes the link (a Wi-Fi link that died left the sender waiting about 15 minutes).
     */
    private suspend fun extras(link: Link, waiting: Boolean, own: SyncSelection, name: String, count: ExtrasCount) {
        coroutineScope {
            val watchdog = launch {
                while (true) {
                    delay(WRITE_CHECK)
                    if (link.stalledMillis() > PairSyncWifi.READ_TIMEOUT) {
                        log("Covers and downloads: nothing could be sent for a while, closing the connection")
                        link.close()
                        break
                    }
                }
            }
            try {
                extrasBoth(link, waiting, own, name, count)
            } finally {
                watchdog.cancel()
            }
        }
    }

    private suspend fun extrasBoth(link: Link, waiting: Boolean, own: SyncSelection, name: String, count: ExtrasCount) {
        val library = TransferPart("Library + history", size(rate.total), 1f, true)
        step("Finding covers and downloads…")
        val mine = keepAlive(link, true) { PairSyncExtras.outgoing(own) }
        // KMK (r92): a list too big for the other device goes in part (the rest next sync)
        val encoded = PairSyncExtras.encodeManifest(mine)
        val mineCount = encoded.items
        val left = mine.manifest.items.size - mineCount
        if (left > 0) log("Covers and downloads: the list is too long, %,d items wait for the next sync".format(left))
        val theirs = if (waiting) {
            link.send(encoded.bytes)
            PairSyncExtras.decodeManifest(receiveData(link))
        } else {
            val read = PairSyncExtras.decodeManifest(receiveData(link))
            link.send(encoded.bytes)
            read
        }
        step("Checking what's here already…")
        val plan = keepAlive(link, true) { PairSyncExtras.plan(theirs) }
        count.noRoom = plan.noRoom
        val myNeed = PairSyncExtras.encodeNeed(plan.need)
        val theirNeed = if (waiting) {
            link.send(myNeed)
            PairSyncExtras.decodeNeed(receiveData(link), mineCount)
        } else {
            val read = PairSyncExtras.decodeNeed(receiveData(link), mineCount)
            link.send(myNeed)
            read
        }
        log(
            "Covers and downloads: sending ${theirNeed.size} of $mineCount, " +
                "getting ${plan.need.size} of ${theirs.items.size} from $name",
        )
        if (plan.noRoom > 0) log("Covers and downloads: ${plan.noRoom} don't fit in the free space here")
        if (waiting) {
            sendExtras(link, mine, theirNeed, library, count)
            receiveExtras(link, theirs, plan, library, count)
        } else {
            receiveExtras(link, theirs, plan, library, count)
            sendExtras(link, mine, theirNeed, library, count)
        }
    }

    /** KMK (r92): the next frame that isn't a keep-alive "wait" */
    private fun receiveData(link: Link): ByteArray {
        var frame = link.receive()
        while (isWait(frame)) frame = link.receive()
        return frame
    }

    /** KMK (r92): the bars of the extras phase ([sending] or receiving) */
    private fun showExtras(sending: Boolean, progress: PairSyncExtras.Progress, library: TransferPart) {
        _status.update {
            it.copy(
                sending = sending,
                done = progress.doneBytes,
                total = progress.totalBytes,
                step = null,
                bytesPerSecond = rate.perSecond,
                parts = progress.parts(library),
            )
        }
    }

    /** KMK (r92): true at most every [EXTRAS_EVERY] ms (the bars' updates) */
    private class Every {
        private var last = 0L

        fun due(): Boolean {
            val now = System.nanoTime() / 1_000_000L
            if (last != 0L && now - last < EXTRAS_EVERY) return false
            last = now
            return true
        }
    }

    /** KMK (r92): sends the items the other device asked for ([indexes] of [out]'s manifest), file by file */
    private suspend fun sendExtras(
        link: Link,
        out: PairSyncExtras.Outgoing,
        indexes: List<Int>,
        library: TransferPart,
        count: ExtrasCount,
    ) {
        if (indexes.isEmpty()) return
        val items = out.manifest.items
        val progress = PairSyncExtras.Progress(items, indexes)
        val buffer = ByteArray(PairSyncExtras.CHUNK + 1)
        val every = Every()
        showExtras(sending = true, progress, library)
        for (index in indexes) {
            currentCoroutineContext().ensureActive()
            val item = items[index]
            val reader = out.readers.getOrNull(index)
            var ok = true
            try {
                for (file in item.files) {
                    val open: (() -> InputStream?)? = reader?.let { r -> fun(): InputStream? = r.open(file.name) }
                    val sent = sendFile(link, open, file.size, buffer) { bytes ->
                        progress.bytes(item, bytes)
                        if (every.due()) showExtras(sending = true, progress, library)
                    }
                    if (!sent) ok = false
                }
            } finally {
                reader?.let { runCatching { it.release() } }
            }
            progress.itemDone(item)
            if (ok) {
                count.sentBytes += item.bytes()
                if (item.kind == PairSyncExtras.KIND_COVER) count.sentCovers++ else count.sentItems++
            }
            if (every.due()) showExtras(sending = true, progress, library)
        }
        showExtras(sending = true, progress, library)
    }

    /**
     * KMK (r92): one file as frames of at most [PairSyncExtras.CHUNK] bytes, each starting with [FILE_MORE] or, the
     * last, [FILE_END]; one that can't be read (or stops being readable) ends with [FILE_FAILED], and so does one whose
     * size isn't the [expected] one (it changed since the manifest: a short page or .cbz isn't saved as complete).
     * False when it failed.
     */
    private fun sendFile(
        link: Link,
        open: (() -> InputStream?)?,
        expected: Long,
        buffer: ByteArray,
        onBytes: (Long) -> Unit,
    ): Boolean {
        val input = open?.let { runCatching { it() }.getOrNull() }
        if (input == null) {
            link.send(byteArrayOf(FILE_FAILED))
            return false
        }
        try {
            var total = 0L
            var end = false
            while (!end) {
                var filled = 0
                try {
                    while (filled < PairSyncExtras.CHUNK && !end) {
                        val n = input.read(buffer, 1 + filled, PairSyncExtras.CHUNK - filled)
                        if (n < 0) end = true else filled += n
                    }
                } catch (_: Exception) {
                    link.send(byteArrayOf(FILE_FAILED))
                    return false
                }
                total += filled
                // Grown past its size: what came so far is dropped there
                val wrong = total > expected || (end && total != expected)
                buffer[0] = when {
                    wrong -> FILE_FAILED
                    end -> FILE_END
                    else -> FILE_MORE
                }
                link.sendPart(buffer, 1 + filled)
                onBytes(filled.toLong())
                if (wrong) return false
            }
            return true
        } finally {
            runCatching { input.close() }
        }
    }

    /**
     * KMK (r92): receives the items this device asked for ([plan]), each written where this device keeps it (a
     * temporary name until complete); one that fails is dropped and asked for again next sync
     */
    private suspend fun receiveExtras(
        link: Link,
        theirs: ExtrasManifest,
        plan: PairSyncExtras.Plan,
        library: TransferPart,
        count: ExtrasCount,
    ) {
        if (plan.need.isEmpty()) return
        val items = theirs.items
        val progress = PairSyncExtras.Progress(items, plan.need)
        val every = Every()
        showExtras(sending = false, progress, library)
        try {
            for (index in plan.need) {
                currentCoroutineContext().ensureActive()
                val item = items[index]
                val writer = runCatching { PairSyncExtras.writer(plan, index, item) }.getOrNull()
                var ok = writer != null
                try {
                    for (file in item.files.indices) {
                        val out = if (ok && writer != null) {
                            runCatching { writer.output(file, item.files[file].name) }.getOrNull()
                        } else {
                            null
                        }
                        if (out == null) ok = false
                        val fileOk = try {
                            receiveFile(link, out, item.files[file].size) { bytes ->
                                progress.bytes(item, bytes)
                                if (every.due()) showExtras(sending = false, progress, library)
                            }
                        } finally {
                            runCatching { out?.close() }
                        }
                        if (!fileOk) ok = false
                    }
                } catch (e: Throwable) {
                    writer?.let { runCatching { it.abort() } }
                    throw e
                }
                val saved = writer != null && runCatching { writer.finish(ok) }.getOrDefault(false)
                progress.itemDone(item)
                if (saved) {
                    count.gotBytes += item.bytes()
                    if (item.kind == PairSyncExtras.KIND_COVER) count.gotCovers++ else count.gotItems++
                }
                if (every.due()) showExtras(sending = false, progress, library)
            }
            showExtras(sending = false, progress, library)
        } finally {
            runCatching { PairSyncExtras.afterReceive(plan) }
        }
    }

    /**
     * KMK (r92): one file's frames (see [sendFile]) into [out] (null: read and dropped); true when it all came (exactly
     * [expected] bytes) and was written
     */
    private fun receiveFile(link: Link, out: OutputStream?, expected: Long, onBytes: (Long) -> Unit): Boolean {
        var writing = out != null
        var total = 0L
        while (true) {
            val frame = link.receive()
            if (frame.isEmpty()) throw IOException("Bad data from the other device")
            val length = frame.size - 1
            if (length > 0) {
                total += length
                if (writing) {
                    try {
                        out?.write(frame, 1, length)
                    } catch (_: Exception) {
                        writing = false
                    }
                }
                onBytes(length.toLong())
            }
            when (frame[0]) {
                FILE_MORE -> Unit
                // KMK (r92): a short (or long) file is dropped like a failed one
                FILE_END -> return writing && total == expected
                FILE_FAILED -> return false
                else -> throw IOException("Bad data from the other device")
            }
        }
    }

    /**
     * KMK (r89): stops the running sync (either side): closes its connections and frees the pop-up. r90: also the
     * sync this device is answering (its job is cancelled, so the merge stops between titles).
     */
    fun stopSync() {
        // KMK (r91): Stop while this device is asked to accept = Decline
        val asked = pending
        if (asked != null && asked.run == runId) {
            answerIncoming(asked.request.id, accept = false)
            return
        }
        val (stopped, job, waiting) = synchronized(lock) {
            // KMK (r90): pairing sets busy too, but there's no sync to stop then
            if (!_status.value.busy || _pairing.value) return
            runId++
            val running = Triple(session, syncJob, waitLink)
            session = null
            syncJob = null
            outgoing = null
            waitLink = null
            idle()
            running
        }
        log("Sync stopped")
        if (waiting == null) {
            stopped?.close()
            job?.cancel()
            return
        }
        // KMK (r91): waiting for the other device's Accept: its prompt closes ("<name> cancelled"), then all is closed
        scope.launch {
            runCatching { waiting.sendJson(buildJsonObject { put("t", "cancel") }) }
            stopped?.close()
            job?.cancel()
        }
    }

    // Live preview (KMK (r91)): while the pop-up is open, each paired device is checked in with over Bluetooth

    /**
     * KMK (r91): the pop-up opened (or came back from the background): checks in with every paired device now and
     * every [LIVE_EVERY] ms (plus a little at random). Already live (a rotation, a second call): nothing changes.
     */
    fun goLive(context: Context) {
        val app = context.applicationContext
        liveContext = app
        synchronized(lock) {
            if (liveJob?.isActive == true) return
            val gen = ++liveGen
            liveJob = scope.launch {
                // This device's summary first, so check-ins are answered from it
                ownRows()
                // The repos' lists, when they weren't read yet (the APK count needs them); live devices hear after
                scope.launch { warmRepoLists(app) }
                while (true) {
                    pingAll(app, gen)
                    delay(LIVE_EVERY + random.nextInt(LIVE_JITTER))
                }
            }
        }
    }

    /**
     * KMK (r91): the pop-up closed or went to the background: no more check-ins, no device counts as live, and the
     * live ones are told (so their "Live" goes off at once)
     */
    fun leaveLive() {
        synchronized(lock) {
            liveGen++
            liveJob?.cancel()
            liveJob = null
            pushJob?.cancel()
            pushJob = null
        }
        val live = _peerViews.value.values.filter { it.live }.map { it.address }.toSet()
        lastHeard.clear()
        _peerViews.update { views -> views.mapValues { it.value.copy(live = false) } }
        val app = liveContext ?: return
        if (live.isEmpty()) return
        val peers = prefs.peers().filter { it.address in live }
        scope.launch { peers.forEach { peer -> launch { sayLeaving(app, peer) } } }
    }

    /** KMK (r91): this device's tiles changed: live devices hear it (once the taps stop for [PUSH_DELAY] ms) */
    fun selectionChanged(context: Context) {
        val app = context.applicationContext
        clearOwnRows()
        pushJob?.cancel()
        pushJob = null
        if (liveJob == null) return
        pushJob = scope.launch {
            delay(PUSH_DELAY)
            clearOwnRows()
            val gen = liveGen
            val live = _peerViews.value.values.filter { it.live }.map { it.address }.toSet()
            if (live.isEmpty()) return@launch
            pingAll(app, gen, prefs.peers().filter { it.address in live }, skipRecent = false)
        }
    }

    /** KMK (r91): reads the repos' lists once when they weren't yet, then this device's summary again */
    private suspend fun warmRepoLists(context: Context) {
        if (runCatching { PairSyncData.repoListsLoaded() }.getOrDefault(true)) return
        runCatching { PairSyncData.loadRepoLists() }
        if (liveJob != null) selectionChanged(context) else clearOwnRows()
    }

    /**
     * KMK (r91): checks in with [peers]; with [skipRecent], not with one heard from in the last [LIVE_EVERY] ms
     * (its own check-in here already swapped both summaries)
     */
    private suspend fun pingAll(
        context: Context,
        gen: Int,
        peers: List<Peer> = prefs.peers(),
        skipRecent: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        coroutineScope {
            peers.forEach { peer ->
                if (skipRecent && now - (lastHeard[peer.address] ?: 0L) in 0L until LIVE_EVERY) return@forEach
                launch {
                    // KMK (r91): not when its own check-in here just worked (two connects at once can fail)
                    if (ping(context, peer, gen) == false && gen == liveGen && !heardJustNow(peer.address)) {
                        notLive(peer.address)
                    }
                }
            }
        }
    }

    /** KMK (r91): the device at [address] checked in live within the last [LIVE_TIMEOUT] ms */
    private fun heardJustNow(address: String): Boolean =
        System.currentTimeMillis() - (lastHeard[address] ?: 0L) in 0L until LIVE_TIMEOUT

    /** KMK (r91): closes the check-ins' connections (a sync or pairing is about to connect) */
    private fun closePings() {
        pingSockets.toList().forEach { runCatching { it.close() } }
    }

    /** KMK (r91): [connect], one outgoing connect at a time, after the check-ins' connections are closed */
    private suspend fun connectLocked(context: Context, device: BluetoothDevice, session: Session?): Link {
        closePings()
        return btConnect.withLock { connect(context, device, session) }
    }

    /**
     * KMK (r91): one check-in with [peer]: this device's summary goes, its summary comes back (within
     * [LIVE_TIMEOUT]). True when it answered, false when it didn't (or Bluetooth is off: nobody is live then), null
     * when it wasn't tried (scanning, a sync or pairing running here, or the pop-up closed meanwhile).
     */
    private suspend fun ping(context: Context, peer: Peer, gen: Int): Boolean? {
        if (_status.value.busy) return null
        val adapter = adapter(context) ?: return false
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return false
        // Connecting stops a scan (the Pair page's), so none then
        if (runCatching { adapter.isDiscovering }.getOrDefault(true)) return null
        val own = ownRows()
        val socket = runCatching {
            adapter.getRemoteDevice(peer.address).createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
        }.getOrNull() ?: return false
        pingSockets.add(socket)
        // Bluetooth has no time-outs: closing the socket ends a connect or read that takes too long
        val watchdog = scope.launch {
            delay(LIVE_TIMEOUT)
            runCatching { socket.close() }
        }
        return try {
            val tried = btConnect.withLock {
                if (_status.value.busy || gen != liveGen) {
                    false
                } else {
                    socket.connect()
                    true
                }
            }
            if (!tried) return null
            val link = Link.bluetooth(socket)
            link.key = Base64.getDecoder().decode(peer.secret)
            link.maxFrame = CONTROL_FRAME.toInt()
            link.sendJson(liveMessage(own))
            val reply = link.receiveJson()
            if (reply.string("t") == "live") {
                // The pop-up closed meanwhile: not marked live
                if (gen != liveGen) return null
                val theirs = rowsIn(reply).orEmpty()
                seen(
                    peer.address,
                    theirs,
                    reply.string("nothing") == "true" || theirs.isEmpty(),
                    live = true,
                    all = reply.string("all") == "true",
                )
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            watchdog.cancel()
            pingSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    /** KMK (r91): tells [peer] this pop-up closed ({"t":"leaving"}), so its "Live" for this device goes off */
    private suspend fun sayLeaving(context: Context, peer: Peer) {
        if (_status.value.busy) return
        val adapter = adapter(context)?.takeIf { runCatching { it.isEnabled }.getOrDefault(false) } ?: return
        val socket = runCatching {
            adapter.getRemoteDevice(peer.address).createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
        }.getOrNull() ?: return
        pingSockets.add(socket)
        val watchdog = scope.launch {
            delay(LIVE_TIMEOUT)
            runCatching { socket.close() }
        }
        try {
            val tried = btConnect.withLock {
                if (_status.value.busy) {
                    false
                } else {
                    socket.connect()
                    true
                }
            }
            if (!tried) return
            val link = Link.bluetooth(socket)
            link.key = Base64.getDecoder().decode(peer.secret)
            link.maxFrame = CONTROL_FRAME.toInt()
            link.sendJson(buildJsonObject { put("t", "leaving") })
            // The other device closes the connection once it read it (the watchdog ends the wait otherwise)
            runCatching { link.readLength() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing to do: its check-in finds this device gone soon anyway
        } finally {
            watchdog.cancel()
            pingSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun liveMessage(own: OwnSend): JsonObject = buildJsonObject {
        put("t", "live")
        put("send", rowsJson(own.rows))
        put("nothing", own.rows.isEmpty())
        put("all", own.all)
    }

    /** KMK (r91): forgets this device's own summary (the tiles changed, a sync finished, the repos' lists came) */
    private fun clearOwnRows() {
        synchronized(lock) {
            ownVersion++
            ownSend = null
        }
    }

    private suspend fun readOwnRows(): OwnSend {
        val version = ownVersion
        val selection = prefs.selection()
        val rows = runCatching { PairSyncData.summarizeSend(selection) }.getOrDefault(emptyList())
        val fresh = OwnSend(rows, selection.isEverything, System.currentTimeMillis())
        synchronized(lock) { if (ownVersion == version) ownSend = fresh }
        if (rows.any { it.detail == PairSyncData.EXTRAS_CHECKING }) pushWhenCounted(selection, version)
        return fresh
    }

    /** KMK (r92): covers and downloads are being counted: live devices get the summary with them once they are */
    private val extrasPushing = AtomicBoolean(false)

    private fun pushWhenCounted(selection: SyncSelection, version: Int) {
        if (!extrasPushing.compareAndSet(false, true)) return
        scope.launch {
            try {
                // The count goes on in the background; this only waits for it (a failed one isn't pushed)
                val counted = runCatching { PairSyncExtras.summaryRows(selection) }.isSuccess
                val context = liveContext
                if (counted && ownVersion == version && liveJob != null && context != null) selectionChanged(context)
            } finally {
                extrasPushing.set(false)
            }
        }
    }

    /**
     * KMK (r91): this device's own summary. Kept until the tiles change or a sync finishes; one older than
     * [OWN_ROWS_FOR] ms still answers at once and is read again in the background (a big library took longer than a
     * check-in may).
     */
    private suspend fun ownRows(): OwnSend {
        val cached = ownSend ?: return readOwnRows()
        val age = System.currentTimeMillis() - cached.at
        if (age !in 0L until OWN_ROWS_FOR && ownRefreshing.compareAndSet(false, true)) {
            scope.launch {
                try {
                    readOwnRows()
                } finally {
                    ownRefreshing.set(false)
                }
            }
        }
        return cached
    }

    /**
     * KMK (r91): notes what the device at [address] sends ([rows], null = unchanged), whether it's [live] and whether
     * it sends everything ([all]) (null = unchanged); a new summary is saved
     */
    private fun seen(
        address: String,
        rows: List<SummaryRow>?,
        nothing: Boolean?,
        live: Boolean?,
        all: Boolean? = null,
    ) {
        val now = System.currentTimeMillis()
        if (live == true) lastHeard[address] = now
        _peerViews.update { views ->
            val old = views[address]
            val view = PeerView(
                address = address,
                live = live ?: old?.live ?: false,
                theirSend = rows ?: old?.theirSend,
                theirSendsNothing = if (rows != null) nothing ?: rows.isEmpty() else old?.theirSendsNothing,
                asOf = if (rows != null) now else old?.asOf ?: 0L,
                theirSendsEverything = all ?: old?.theirSendsEverything ?: false,
            )
            views + (address to view)
        }
        if (rows != null) saveViews()
    }

    private fun notLive(address: String) {
        _peerViews.update { views -> views[address]?.let { views + (address to it.copy(live = false)) } ?: views }
    }

    private fun saveViews() {
        runCatching {
            prefs.saveViews(
                _peerViews.value.values.mapNotNull { view ->
                    view.theirSend?.let {
                        StoredView(
                            view.address,
                            it,
                            view.theirSendsNothing == true,
                            view.asOf,
                            view.theirSendsEverything,
                        )
                    }
                },
            )
        }
    }

    private fun loadViews(): Map<String, PeerView> = prefs.views().associate {
        it.address to PeerView(
            address = it.address,
            live = false,
            theirSend = it.send,
            theirSendsNothing = it.nothing,
            asOf = it.asOf,
            theirSendsEverything = it.all,
        )
    }

    private fun rowsJson(rows: List<SummaryRow>): JsonArray = JsonArray(
        rows.map {
            buildJsonObject {
                put("title", it.title)
                put("detail", it.detail)
            }
        },
    )

    /** KMK (r91): the summary rows in [message] ("send"), or null when it has none (an older version) */
    private fun rowsIn(message: JsonObject): List<SummaryRow>? =
        (message["send"] as? JsonArray)?.mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val title = row.string("title") ?: return@mapNotNull null
            SummaryRow(title, row.string("detail").orEmpty())
        }

    private fun connect(context: Context, device: BluetoothDevice, session: Session?): Link {
        runCatching { adapter(context)?.cancelDiscovery() }
        val socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
        session?.add(socket)
        try {
            socket.connect()
        } catch (e: IOException) {
            runCatching { socket.close() }
            val name = runCatching { device.name }.getOrNull() ?: device.address
            throw IOException("Couldn't reach $name. It needs Bluetooth on and its Sync pop-up open", e)
        }
        return Link.bluetooth(socket)
    }

    // Encryption: AES-GCM with the pairing code's key, then the shared secret

    private fun codeKey(code: String): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), "komikku-pair-sync".toByteArray(), 60_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun encrypt(key: ByteArray, data: ByteArray, length: Int = data.size): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data, 0, length)
    }

    private fun decrypt(key: ByteArray, frame: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, frame, 0, 12))
        return cipher.doFinal(frame, 12, frame.size - 12)
    }

    private fun parse(bytes: ByteArray): JsonObject = Json.parseToJsonElement(String(bytes)).jsonObject

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.jsonPrimitive?.content

    private fun JsonObject.int(name: String): Int? = string(name)?.toIntOrNull()

    private fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
        else -> "${(bytes + 1023) / 1024} KB"
    }

    /** KMK (r90): everything one sync opened, closed together at its end or on Stop */
    private class Session : Closeable {
        private val items = mutableListOf<Closeable>()
        private var closed = false

        /** KMK (r90): Stop (or the end of the sync) closed it */
        val isClosed: Boolean get() = synchronized(items) { closed }

        /** Adds [item]; when the sync was stopped already, it's closed and this throws */
        fun <T : Closeable> add(item: T): T {
            val added = synchronized(items) {
                if (!closed) items += item
                !closed
            }
            if (!added) {
                runCatching { item.close() }
                throw IOException("Sync stopped")
            }
            return item
        }

        fun remove(item: Closeable) {
            synchronized(items) { items.remove(item) }
        }

        override fun close() {
            val all = synchronized(items) {
                closed = true
                items.toList().also { items.clear() }
            }
            all.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /** KMK (r90): the bytes moved in this sync, and the speed (bytes per second, smoothed over about a second) */
    private class Rate {
        private var windowStart = 0L
        private var windowBytes = 0L

        @Volatile var perSecond = 0L
            private set

        @Volatile var total = 0L
            private set

        @Synchronized fun reset() {
            windowStart = 0L
            windowBytes = 0L
            perSecond = 0L
            total = 0L
        }

        /** Nothing moves for a while (merging, building the export) */
        @Synchronized fun pause() {
            windowStart = 0L
            windowBytes = 0L
            perSecond = 0L
        }

        @Synchronized fun add(bytes: Int) {
            total += bytes
            windowBytes += bytes
            val now = System.nanoTime() / 1_000_000L
            if (windowStart == 0L) windowStart = now
            val elapsed = now - windowStart
            if (elapsed >= 500L) {
                val current = windowBytes * 1000L / elapsed
                perSecond = if (perSecond == 0L) current else (perSecond + current) / 2
                windowStart = now
                windowBytes = 0L
            }
        }
    }

    /**
     * A connection: length-prefixed frames, each encrypted with [key]. KMK (r90): over any pair of streams (Bluetooth
     * or TCP); [counter] is told every chunk moved, [onClose] closes the socket.
     */
    private class Link(
        rawInput: InputStream,
        rawOutput: OutputStream,
        private val chunk: Int,
        private val onClose: () -> Unit,
    ) : Closeable {
        private val input = DataInputStream(BufferedInputStream(rawInput, chunk))
        private val output = DataOutputStream(BufferedOutputStream(rawOutput, chunk))
        var key: ByteArray = ByteArray(0)

        /** The largest frame accepted: small until a key has shown who's on the other end */
        var maxFrame: Int = FIRST_FRAME

        var counter: ((Int) -> Unit)? = null

        /** A frame's length, checked against [limit] */
        fun readLength(limit: Int = maxFrame): Int {
            val length = try {
                input.readInt()
            } catch (e: IOException) {
                throw plain(e)
            }
            if (length !in 13..limit) throw IOException("Bad data from the other device")
            return length
        }

        /** KMK (r90): a closed connection, said plainly (it showed as "EOFException" or "bt socket closed, …") */
        private fun plain(e: IOException): IOException =
            if (e is EOFException || e is SocketException || e.message?.startsWith("bt socket closed") == true) {
                IOException("The other device stopped the sync or the connection dropped", e)
            } else {
                e
            }

        /** KMK (r90): [input]'s read, with [plain] errors */
        private fun readSome(buffer: ByteArray, max: Int): Int = try {
            input.read(buffer, 0, max)
        } catch (e: IOException) {
            throw plain(e)
        }

        /**
         * KMK (r89): read in pieces into a growing buffer, so a length that lies can't make a huge allocation before
         * the bytes really arrive
         */
        fun readBody(length: Int, progress: ((Long, Long) -> Unit)? = null): ByteArray {
            val out = ByteArrayOutputStream(minOf(length, 1024 * 1024))
            val buffer = ByteArray(chunk)
            var read = 0
            while (read < length) {
                val n = readSome(buffer, minOf(buffer.size, length - read))
                if (n < 0) throw EOFException("The other device stopped sending")
                out.write(buffer, 0, n)
                read += n
                counter?.invoke(n)
                progress?.invoke(read.toLong(), length.toLong())
            }
            return out.toByteArray()
        }

        fun readFrame(progress: ((Long, Long) -> Unit)? = null): ByteArray = readBody(readLength(), progress)

        /** KMK (r90): reads a frame's [length] bytes and drops them */
        fun skip(length: Int) {
            val buffer = ByteArray(chunk)
            var left = length
            while (left > 0) {
                val n = readSome(buffer, minOf(buffer.size, left))
                if (n < 0) throw EOFException("The other device stopped sending")
                left -= n
                counter?.invoke(n)
            }
        }

        fun send(data: ByteArray, progress: ((Long, Long) -> Unit)? = null) = write(encrypt(key, data), progress)

        /** KMK (r92): the first [length] bytes of [data] as one frame (a file's piece, not copied first) */
        fun sendPart(data: ByteArray, length: Int) = write(encrypt(key, data, length), null)

        // KMK (r92): whether a write is going on and when it last moved, for [stalledMillis]
        @Volatile private var writing = false

        @Volatile private var lastWrite = 0L

        /** KMK (r92): how long the write going on hasn't moved (0 when none is going on) */
        fun stalledMillis(): Long = if (writing) System.currentTimeMillis() - lastWrite else 0L

        private fun write(frame: ByteArray, progress: ((Long, Long) -> Unit)?) {
            lastWrite = System.currentTimeMillis()
            writing = true
            try {
                output.writeInt(frame.size)
                var written = 0
                while (written < frame.size) {
                    val n = minOf(chunk, frame.size - written)
                    output.write(frame, written, n)
                    written += n
                    lastWrite = System.currentTimeMillis()
                    counter?.invoke(n)
                    progress?.invoke(written.toLong(), frame.size.toLong())
                }
                output.flush()
            } finally {
                writing = false
            }
        }

        /** Decrypts a frame read with [readBody] */
        fun open(frame: ByteArray): ByteArray = decrypt(key, frame)

        fun receive(progress: ((Long, Long) -> Unit)? = null): ByteArray = decrypt(key, readFrame(progress))

        fun sendJson(message: JsonObject) = send(message.toString().toByteArray())

        fun receiveJson(): JsonObject = parse(receive())

        override fun close() {
            runCatching { onClose() }
        }

        companion object {
            fun bluetooth(socket: BluetoothSocket) =
                Link(socket.inputStream, socket.outputStream, 64 * 1024) { socket.close() }

            fun tcp(socket: Socket) =
                Link(socket.getInputStream(), socket.getOutputStream(), 256 * 1024) { socket.close() }
        }
    }
}
