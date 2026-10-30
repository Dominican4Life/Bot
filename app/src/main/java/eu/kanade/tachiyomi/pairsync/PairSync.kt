package eu.kanade.tachiyomi.pairsync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.provider.Settings
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * KMK: the pair sync protocol (r90).
 * 1. Bluetooth RFCOMM (insecure, SERVICE_UUID) is the control channel: finding the device, pairing, the key check and
 *    choosing how the data moves. Every frame is an int length + AES-GCM (12-byte IV, ciphertext, 16-byte tag).
 * 2. Pairing: {"t":"pair","name"} with the code's key -> {"t":"paired","name","secret"}; the secret is the key after.
 * 3. Sync: the initiator sends {"t":"sync","name","selection","have","transport","nonce","p2p"}; "selection" is what
 *    IT receives, "p2p": false = it can't use Wi-Fi Direct (not offered then; no key = it may). When both devices
 *    press Sync at once, the one whose request has the lower "nonce" keeps its sync and the other drops its own and
 *    answers.
 * 4. A busy waiting device answers {"t":"busy"}. Else it offers ways in the order "transport" implies (auto: p2p,
 *    lan, bt): {"t":"p2p","ssid","pass","port","ip"}, {"t":"lan","ips","port"}, {"t":"bt"}, or {"t":"none"} when
 *    none is left.
 * 5. The initiator joins / connects, sends {"t":"hello"} on the new socket, then answers over Bluetooth
 *    {"t":"ok","via"} or {"t":"fail"} (the next way is offered). After "ok" the waiting device takes the connection
 *    and checks "hello": it answers {"t":"go"}, or offers the next way (the initiator drops that connection). After
 *    "go" every data frame goes over the new socket, with the same key.
 * 6. Data: the waiting device sends its payload (with "wants", its own selection), then the backup frame and one raw
 *    frame per APK; the initiator sends its own (built for "wants"), both merge, then {"t":"done"} each way (only a
 *    courtesy: each side saves its result before it). Over Wi-Fi, a device building its export sends {"t":"wait"}
 *    every 30 s first; the reader skips those before the payload.
 * 7. An r89 waiting device ignores "transport" and sends its gzipped payload at once: the initiator sees a first frame
 *    that isn't one of the JSON offers and carries on over Bluetooth the r89 way. An r89 initiator (no "transport")
 *    gets pure Bluetooth, the backup inside the JSON, and no APKs.
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

    /**
     * What's going on. While [busy], [peerAddress] is the device being synced with, and [done] of [total] bytes are
     * sent ([sending]) or received; [step] says what's being done when no bytes are moving. KMK (r90): [via] is how
     * the data moves once known, [bytesPerSecond] the current speed; [lastError] why the last sync with
     * [errorAddress] failed (cleared when the next one starts).
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
        _result.update {
            it?.copy(missingAsked = it.missingAsked || missing, updatesAsked = it.updatesAsked || updates)
        }
    }

    /** KMK (r90): marks this device busy for a new sync with [peerAddress]; its number and session, or null if busy */
    private fun begin(peerAddress: String): Pair<Int, Session>? = synchronized(lock) {
        if (_status.value.busy) return@synchronized null
        val id = ++runId
        val newSession = Session()
        session = newSession
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
            )
        }
        id to newSession
    }

    /** KMK (r90): sync [id] with [address] failed: logged, and shown under that device until the next sync */
    private fun failed(id: Int, address: String, text: String, error: String) {
        if (runId != id) return
        log(text)
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
    }

    /**
     * KMK (r90): a new result keeps the previous one's untapped Install / Update offers unless it has its own (an
     * incoming sync used to clear them)
     */
    private fun publish(new: SyncResult) {
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
        _result.value = new.copy(summary = summary)
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
                    answerSync(context, link, peer.copy(address = remote), parse(message))
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
        val started = begin(peer.address)
            ?: if (yieldTo(peer.secret, theirNonce, name)) begin(peer.address) else null
        if (started == null) {
            runCatching { bt.sendJson(buildJsonObject { put("t", "busy") }) }
            // KMK (r90): both pressed Sync and this device's request won: not a sync "while busy"
            if (keepsOwn(peer.secret, theirNonce)) {
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
        // What the other device wants to receive, and what this one does
        val theirs = PairSyncPreferences.parseSelection(message.string("selection").orEmpty())
        val own = prefs.selection()
        // KMK (r89): the packages the other device has (null from an older version), so only APKs it lacks are sent
        val otherHas = (message["have"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
        // KMK (r90): no "transport" = an r89 device: Bluetooth only, the r89 payload
        val transportKey = message.string("transport")
        val modern = transportKey != null
        try {
            session.add(bt)
            bt.counter = rate::add
            log("$name is syncing with this device")
            PairSyncData.cleanApks(offeredApks())
            var link = bt
            var via = SyncTransport.Bluetooth
            // KMK (r90): an r89 device's payload can be bigger (its APKs are inside, in Base64)
            if (!modern) bt.maxFrame = LEGACY_FRAME
            if (modern) {
                val transport = SyncTransport.of(transportKey)
                // KMK (r90): "p2p": false = the other device can't use Wi-Fi Direct (no key: it may)
                val p2p = message.string("p2p") != "false"
                val chosen = offerWays(context, bt, transport, session, p2p) ?: throw IOException(noWay(transport))
                link = chosen.first
                via = chosen.second
            }
            _status.update { it.copy(via = via) }
            if (via != SyncTransport.Bluetooth) log("Connected over ${via.label}")
            step("Getting ready…")
            if (!modern && theirs.syncsApks) log("Sideloaded APKs are only sent to a device on r90 or newer")
            val mine = keepAlive(link, via != SyncTransport.Bluetooth) {
                PairSyncData.export(theirs, own, otherHas, raw = modern)
            }
            sendExport(link, mine)
            val received = receivePayload(link, own)
            log("Got ${size(rate.total)} in all from $name")
            step("Merging…")
            val summary = PairSyncData.import(received, own, ::log).withSent(mine.summary)
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
            }
            runCatching {
                link.sendJson(buildJsonObject { put("t", "done") })
                link.receive()
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
     * KMK (r90): the waiting device offers the ways [transport] allows, one by one, until the other device takes one;
     * the data link and the way, or null after telling it none is left
     */
    private suspend fun offerWays(
        context: Context,
        bt: Link,
        transport: SyncTransport,
        session: Session,
        p2p: Boolean,
    ): Pair<Link, SyncTransport>? {
        val order = when (transport) {
            SyncTransport.Auto -> listOf(SyncTransport.WifiDirect, SyncTransport.Wifi, SyncTransport.Bluetooth)
            SyncTransport.Wifi -> listOf(SyncTransport.Wifi)
            SyncTransport.WifiDirect -> listOf(SyncTransport.WifiDirect)
            SyncTransport.Bluetooth -> listOf(SyncTransport.Bluetooth)
        }.filter { p2p || it != SyncTransport.WifiDirect }
        // KMK (r90): a faster way failed already (the step says so)
        var tried = false
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
                        tried = true
                    }
                }
                SyncTransport.WifiDirect -> {
                    step("Starting Wi-Fi Direct…")
                    val owner = session.add(PairSyncWifi.Owner(context))
                    val group = owner.start(::log)
                    val link = if (group != null) {
                        // KMK (r90): on all addresses: the group's own one may not be set up yet ("hello" checks who
                        // connected)
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
                        null
                    }
                    // The group stays up for the whole sync (the session removes it at the end)
                    if (link != null) return link to SyncTransport.WifiDirect
                    session.remove(owner)
                    owner.close()
                    tried = true
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
    private fun pair(context: Context, device: BluetoothDevice, code: String): Result<Peer> {
        val name = runCatching { device.name }.getOrNull() ?: device.address
        return try {
            log("Pairing with $name…")
            connect(context, device, null).use { link ->
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
     * Syncs with [peer], which needs its Sync pop-up open. KMK (r90): [selection] is what this device receives; the
     * other device says what it wants, and this device's export is built for that after the other's arrives (so it
     * also knows which sideloaded APKs the other device lacks). An all-off selection only sends.
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
                log(
                    if (selection.isEmpty) {
                        "Getting ready to sync with ${peer.name} (receiving nothing, only sending)…"
                    } else {
                        "Getting ready to sync with ${peer.name}, receiving ${selection.describe()}…"
                    },
                )
                val device = adapter(context)?.getRemoteDevice(peer.address) ?: throw IOException("Bluetooth is off")
                log("Connecting to ${peer.name}…")
                step("Connecting…")
                connect(context, device, session).use { bt ->
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
                            put("selection", Json.encodeToString(SyncSelection.serializer(), selection))
                            put("have", JsonArray(PairSyncData.installedPackages().map { JsonPrimitive(it) }))
                            put("transport", transport.key)
                            put("nonce", nonce.toString())
                            put("p2p", p2pWhy == null)
                        },
                    )
                    step("Waiting for ${peer.name}…")
                    var frame = try {
                        bt.receive(progress(sending = false, minTotal = CONTROL_FRAME))
                    } catch (_: IOException) {
                        throw IOException("${peer.name} didn't answer. Open Sync on it, then try again")
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
                            "busy" -> throw IOException("${peer.name} is busy syncing")
                            "none" -> throw IOException(noWay(transport))
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
                            continue
                        }
                        // KMK (r90): "go" = the other device took the connection; else it offers the next way
                        if (offerIn(frame)?.string("t") == "go") {
                            link = fast.link
                            via = fast.via
                            break
                        }
                        log("${peer.name} couldn't use the ${fast.via.label} connection")
                        fast.close(session)
                        failedWay = failedWay ?: fast.via
                    }
                    synchronized(lock) { if (outgoing?.id == id) outgoing = null }
                    _status.update { it.copy(via = via) }
                    if (via != SyncTransport.Bluetooth) log("Connected over ${via.label}")
                    val received = if (legacyPayload != null) {
                        readPayload(link, legacyPayload, selection)
                    } else {
                        receivePayload(link, selection)
                    }
                    // What the other device wants; an r89 device doesn't say, so this device's choice goes both ways
                    val wants = received.wants ?: selection
                    step("Getting ready to send…")
                    val mine = keepAlive(link, via != SyncTransport.Bluetooth) {
                        PairSyncData.export(wants, selection, received.installed, raw = modern)
                    }
                    log("Got ${size(rate.total)} from ${peer.name}, sending ${size(mine.payload.size.toLong())}…")
                    sendExport(link, mine)
                    step("Merging…")
                    val summary = PairSyncData.import(received, selection, ::log).withSent(mine.summary)
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
                    }
                    runCatching {
                        link.sendJson(buildJsonObject { put("t", "done") })
                        link.receive()
                    }
                }
            } catch (e: CancellationException) {
                throw e
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

    /** KMK (r90): an offer (or refusal) from an r90 device, or null when [frame] is an r89 payload (gzip) */
    private fun offerIn(frame: ByteArray): JsonObject? {
        if (frame.isEmpty() || frame[0] != '{'.code.toByte()) return null
        val message = runCatching { parse(frame) }.getOrNull() ?: return null
        return message.takeIf { m -> m.string("t")?.let { it in OFFERS } == true }
    }

    private fun noWay(transport: SyncTransport): String = when (transport) {
        SyncTransport.WifiDirect -> "Couldn't connect over Wi-Fi Direct"
        SyncTransport.Wifi -> "The two devices aren't on the same Wi-Fi"
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
            if (owner != null) log("Couldn't reach $name over Wi-Fi Direct")
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

    private fun receivePayload(link: Link, own: SyncSelection): ReceivedPayload {
        // KMK (r90): the bar ignores the tiny keep-alive frames (they'd show a full bar while nothing moves)
        val progress = progress(sending = false, minTotal = 1024L)
        var frame = link.receive(progress)
        while (isWait(frame)) frame = link.receive(progress)
        return readPayload(link, frame, own)
    }

    /**
     * KMK (r90): decodes [payload] and reads the frames it announces: the backup, then each APK, saved to the cache
     * when [own] takes APKs and it's within the limits (else read and dropped)
     */
    private fun readPayload(link: Link, payload: ByteArray, own: SyncSelection): ReceivedPayload {
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
                val take = own.syncsApks && info.pkgName !in have && data > 0 && data.toLong() == info.size &&
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

    /**
     * KMK (r89): stops the running sync (either side): closes its connections and frees the pop-up. r90: also the
     * sync this device is answering (its job is cancelled, so the merge stops between titles).
     */
    fun stopSync() {
        val (stopped, job) = synchronized(lock) {
            // KMK (r90): pairing sets busy too, but there's no sync to stop then
            if (!_status.value.busy || _pairing.value) return
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
        log("Sync stopped")
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

    private fun encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data)
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

        fun send(data: ByteArray, progress: ((Long, Long) -> Unit)? = null) {
            val frame = encrypt(key, data)
            output.writeInt(frame.size)
            var written = 0
            while (written < frame.size) {
                val n = minOf(chunk, frame.size - written)
                output.write(frame, written, n)
                written += n
                counter?.invoke(n)
                progress?.invoke(written.toLong(), frame.size.toLong())
            }
            output.flush()
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
