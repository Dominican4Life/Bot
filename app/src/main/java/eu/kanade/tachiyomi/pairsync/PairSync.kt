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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** KMK: A device this one has been paired with: its Bluetooth address, its name, and the key the two share */
@Serializable
data class Peer(
    val address: String,
    val name: String,
    val secret: String,
    val lastSync: Long = 0L,
)

class PairSyncPreferences(private val store: PreferenceStore) {
    // KMK (r89): a private key, so the shared secrets stay out of backups unless "Private settings" is ticked (a
    // restored backup let another device sync as this one). The old plain key is moved once, then deleted.
    private val peersJson = store.getString(Preference.privateKey("pair_sync_peers"), "[]")

    /** What to sync, as JSON (see [SyncSelection]) */
    val selectionJson = store.getString("pair_sync_selection", "")
    val peersChanges get() = peersJson.changes()

    init {
        runCatching {
            val old = store.getString("pair_sync_peers", "[]")
            if (old.isSet()) {
                if (!peersJson.isSet()) peersJson.set(old.get())
                old.delete()
            }
        }
    }

    fun peers(): List<Peer> =
        runCatching { Json.decodeFromString(ListSerializer(Peer.serializer()), peersJson.get()) }.getOrDefault(emptyList())

    fun savePeer(peer: Peer) {
        peersJson.set(
            Json.encodeToString(ListSerializer(Peer.serializer()), peers().filterNot { it.address == peer.address } + peer),
        )
    }

    fun removePeer(address: String) {
        peersJson.set(Json.encodeToString(ListSerializer(Peer.serializer()), peers().filterNot { it.address == address }))
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
    }
}

/**
 * KMK: Syncs two devices over Bluetooth. One shows a 6-digit code and the other, nearby, enters it; after that the
 * two share a secret key and Sync talks to the paired device straight away, everything sent encrypted with that key.
 * The device that isn't pressing Sync needs its Sync pop-up open (it listens while the pop-up is showing).
 */
@SuppressLint("MissingPermission")
object PairSync {

    /** The app's Bluetooth service: only Komikku answers on it */
    private val SERVICE_UUID: UUID = UUID.fromString("6f1c2a7e-4b8d-4e5a-9c3f-2d7b8e1a5c90")

    /** KMK (r89): the most a frame can be once its sender is known (large libraries, APKs) */
    private const val MAX_FRAME = 512 * 1024 * 1024

    /**
     * KMK (r89): the most the first frame can be, before a key has shown who sent it. Up to r88 any nearby device
     * could make the waiting device allocate 256 MiB with one length.
     */
    private const val FIRST_FRAME = 256 * 1024

    /** How often (bytes) the progress bar moves */
    private const val PROGRESS_STEP = 128L * 1024

    /**
     * What's going on. While [busy], [peerAddress] is the device being synced with, and [done] of [total] bytes are
     * sent ([sending]) or received; [step] says what's being done when no bytes are moving.
     */
    data class Status(
        val busy: Boolean = false,
        val lines: List<String> = emptyList(),
        val peerAddress: String? = null,
        val sending: Boolean = false,
        val done: Long = 0L,
        val total: Long = 0L,
        val step: String? = null,
    )

    /** KMK (r89): the last finished sync on this device: what came from [peerName] and what can be installed */
    data class SyncResult(
        val peerAddress: String,
        val peerName: String,
        val time: Long,
        val summary: SyncSummary,
        val missingAsked: Boolean = false,
        val updatesAsked: Boolean = false,
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

    private var serverSocket: BluetoothServerSocket? = null
    private var serverJob: Job? = null

    // KMK (r89): Stop closes the running sync's socket (that ends its blocking reads and writes) and cancels it
    @Volatile private var activeSocket: BluetoothSocket? = null
    private var syncJob: Job? = null

    /** Each sync's number: a stopped sync's late finish mustn't touch the next one's state */
    @Volatile private var runId = 0

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun log(text: String) {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        _status.update { it.copy(lines = (it.lines + "$time  $text").takeLast(40)) }
        AppLog.add('I', "sync", text)
    }

    fun clearLog() = _status.update { it.copy(lines = emptyList()) }

    private fun step(text: String) = _status.update { it.copy(step = text, done = 0L, total = 0L) }

    private fun idle() = _status.update {
        it.copy(busy = false, peerAddress = null, sending = false, done = 0L, total = 0L, step = null)
    }

    /** Moves the progress bar, every [PROGRESS_STEP] bytes */
    private fun progress(sending: Boolean): (Long, Long) -> Unit {
        var last = -1L
        return { done, total ->
            if (last < 0L || done == total || done - last >= PROGRESS_STEP) {
                last = done
                _status.update { it.copy(sending = sending, done = done, total = total, step = null) }
            }
        }
    }

    /** KMK (r89): the result card's Install / Update were tapped */
    fun markAsked(missing: Boolean = false, updates: Boolean = false) {
        _result.update {
            it?.copy(missingAsked = it.missingAsked || missing, updatesAsked = it.updatesAsked || updates)
        }
    }

    // Listening (the device that waits)

    /** Answers paired devices (and, while a code is showing, a device pairing) until [stopListening] */
    fun startListening(context: Context) {
        if (serverJob?.isActive == true) return
        val adapter = adapter(context)?.takeIf { it.isEnabled } ?: return
        serverJob = scope.launch {
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
                launch { answer(context, socket) }
            }
        }
    }

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
            Link(socket).use { link ->
                val first = link.readFrame()
                val remote = socket.remoteDevice.address
                // A paired device (its own address first)
                val peers = prefs.peers().sortedByDescending { it.address == remote }
                for (peer in peers) {
                    val key = Base64.getDecoder().decode(peer.secret)
                    val message = runCatching { decrypt(key, first) }.getOrNull() ?: continue
                    link.key = key
                    link.maxFrame = MAX_FRAME
                    answerSync(context, link, socket, peer.copy(address = remote), parse(message))
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

    private suspend fun answerSync(
        context: Context,
        link: Link,
        socket: BluetoothSocket,
        peer: Peer,
        message: JsonObject,
    ) {
        if (message.string("t") != "sync") return
        val selection = PairSyncPreferences.parseSelection(message.string("selection").orEmpty())
        // KMK (r89): the packages the other device has (null from an older version), so only APKs it lacks are sent
        val otherHas = (message["have"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
        val name = message.string("name") ?: peer.name
        val id = ++runId
        activeSocket = socket
        _result.value = null
        _status.update { it.copy(busy = true, peerAddress = peer.address) }
        try {
            log("$name is syncing with this device: ${selection.describe()}")
            step("Getting ready…")
            val mine = PairSyncData.export(selection, otherHas)
            link.send(mine, progress(sending = true))
            val theirs = link.receive(progress(sending = false))
            log("Got ${size(theirs)} from $name")
            step("Merging…")
            val summary = PairSyncData.import(PairSyncData.decode(theirs), ::log)
            link.sendJson(buildJsonObject { put("t", "done") })
            runCatching { link.receive() }
            val synced = peer.copy(lastSync = System.currentTimeMillis(), name = name)
            prefs.savePeer(synced)
            _result.value = SyncResult(synced.address, synced.name, synced.lastSync, summary)
            log("Synced with $name: $summary")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (runId == id) log("Sync with $name stopped: ${e.message ?: e::class.simpleName}")
        } finally {
            if (runId == id) {
                activeSocket = null
                idle()
            }
        }
    }

    // Connecting (the device that presses Pair or Sync)

    /** Pairs with [device], which is showing [code] on its Sync pop-up */
    suspend fun pair(context: Context, device: BluetoothDevice, code: String): Result<Peer> = withContext(Dispatchers.IO) {
        _status.update { it.copy(busy = true) }
        val name = runCatching { device.name }.getOrNull() ?: device.address
        try {
            log("Pairing with $name…")
            connect(context, device, track = false).use { link ->
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
        } finally {
            _status.update { it.copy(busy = false) }
        }
    }

    /**
     * Syncs [selection] both ways with [peer], which needs its Sync pop-up open. KMK (r89): this device's own export
     * is built after the other device's arrives, so it knows which sideloaded APKs the other device lacks.
     */
    fun sync(context: Context, peer: Peer, selection: SyncSelection) {
        if (_status.value.busy) return
        val id = ++runId
        _result.value = null
        _status.update {
            it.copy(
                busy = true,
                peerAddress = peer.address,
                sending = false,
                done = 0L,
                total = 0L,
                step = "Getting ready…",
            )
        }
        syncJob = scope.launch {
            try {
                if (selection.isEmpty) throw IOException("Pick something to sync first")
                log("Getting ready to sync ${selection.describe()}…")
                val device = adapter(context)?.getRemoteDevice(peer.address) ?: throw IOException("Bluetooth is off")
                log("Connecting to ${peer.name}…")
                step("Connecting…")
                connect(context, device, track = true).use { link ->
                    link.key = Base64.getDecoder().decode(peer.secret)
                    link.maxFrame = MAX_FRAME
                    link.sendJson(
                        buildJsonObject {
                            put("t", "sync")
                            put("name", deviceName(context))
                            put("selection", Json.encodeToString(SyncSelection.serializer(), selection))
                            put("have", JsonArray(PairSyncData.installedPackages().map { JsonPrimitive(it) }))
                        },
                    )
                    step("Waiting for ${peer.name}…")
                    val theirs = try {
                        link.receive(progress(sending = false))
                    } catch (_: IOException) {
                        throw IOException("${peer.name} didn't answer. Open Sync on it, then try again")
                    }
                    val theirSize = size(theirs)
                    val received = PairSyncData.decode(theirs)
                    step("Getting ready to send…")
                    val mine = PairSyncData.export(selection, received.installed)
                    log("Got $theirSize from ${peer.name}, sending ${size(mine)}…")
                    link.send(mine, progress(sending = true))
                    step("Merging…")
                    val summary = PairSyncData.import(received, ::log)
                    link.sendJson(buildJsonObject { put("t", "done") })
                    runCatching { link.receive() }
                    val synced = peer.copy(lastSync = System.currentTimeMillis())
                    prefs.savePeer(synced)
                    _result.value = SyncResult(synced.address, synced.name, synced.lastSync, summary)
                    log("Synced with ${peer.name}: $summary")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (runId == id) log("Sync stopped: ${e.message ?: e::class.simpleName}")
            } finally {
                if (runId == id) {
                    activeSocket = null
                    idle()
                }
            }
        }
    }

    /** KMK (r89): stops the running sync (either side): closes its connection and frees the pop-up */
    fun stopSync() {
        if (!_status.value.busy) return
        runId++
        runCatching { activeSocket?.close() }
        activeSocket = null
        syncJob?.cancel()
        syncJob = null
        idle()
        log("Sync stopped")
    }

    private fun connect(context: Context, device: BluetoothDevice, track: Boolean): Link {
        runCatching { adapter(context)?.cancelDiscovery() }
        val socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
        if (track) activeSocket = socket
        try {
            socket.connect()
        } catch (e: IOException) {
            runCatching { socket.close() }
            val name = runCatching { device.name }.getOrNull() ?: device.address
            throw IOException("Couldn't reach $name. It needs Bluetooth on and its Sync pop-up open", e)
        }
        return Link(socket)
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

    private fun size(bytes: ByteArray): String = when {
        bytes.size >= 1024 * 1024 -> "%.1f MB".format(bytes.size / 1024f / 1024f)
        else -> "${(bytes.size + 1023) / 1024} KB"
    }

    /** A connection: length-prefixed frames, each encrypted with [key] */
    private class Link(private val socket: BluetoothSocket) : Closeable {
        private val input = DataInputStream(BufferedInputStream(socket.inputStream, 64 * 1024))
        private val output = DataOutputStream(BufferedOutputStream(socket.outputStream, 64 * 1024))
        var key: ByteArray = ByteArray(0)

        /** The largest frame accepted: small until a key has shown who's on the other end */
        var maxFrame: Int = FIRST_FRAME

        /**
         * KMK (r89): read in pieces into a growing buffer, so a length that lies can't make a huge allocation before
         * the bytes really arrive
         */
        fun readFrame(progress: ((Long, Long) -> Unit)? = null): ByteArray {
            val length = input.readInt()
            if (length !in 13..maxFrame) throw IOException("Bad data from the other device")
            val out = ByteArrayOutputStream(minOf(length, 1024 * 1024))
            val buffer = ByteArray(64 * 1024)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, 0, minOf(buffer.size, length - read))
                if (n < 0) throw EOFException("The other device stopped sending")
                out.write(buffer, 0, n)
                read += n
                progress?.invoke(read.toLong(), length.toLong())
            }
            return out.toByteArray()
        }

        fun send(data: ByteArray, progress: ((Long, Long) -> Unit)? = null) {
            val frame = encrypt(key, data)
            output.writeInt(frame.size)
            var written = 0
            while (written < frame.size) {
                val n = minOf(64 * 1024, frame.size - written)
                output.write(frame, written, n)
                written += n
                progress?.invoke(written.toLong(), frame.size.toLong())
            }
            output.flush()
        }

        fun receive(progress: ((Long, Long) -> Unit)? = null): ByteArray = decrypt(key, readFrame(progress))

        fun sendJson(message: JsonObject) = send(message.toString().toByteArray())

        fun receiveJson(): JsonObject = parse(receive())

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
