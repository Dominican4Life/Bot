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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
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
    private val peersJson = store.getString("pair_sync_peers", "[]")
    /** What to sync, as JSON (see [SyncSelection]) */
    val selectionJson = store.getString("pair_sync_selection", "")
    val peersChanges get() = peersJson.changes()

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
 * The device that isn't pressing Sync needs its Sync page open (it listens while the page is showing).
 */
@SuppressLint("MissingPermission")
object PairSync {

    /** The app's Bluetooth service: only Komikku answers on it */
    private val SERVICE_UUID: UUID = UUID.fromString("6f1c2a7e-4b8d-4e5a-9c3f-2d7b8e1a5c90")
    private const val MAX_FRAME = 256 * 1024 * 1024

    data class Status(val busy: Boolean = false, val lines: List<String> = emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs get() = PairSyncPreferences.instance
    private val random = SecureRandom()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The code this device is showing for pairing, or null */
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    /** Set when a pairing finishes on this device (either side) */
    private val _justPaired = MutableStateFlow<Peer?>(null)
    val justPaired: StateFlow<Peer?> = _justPaired.asStateFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var serverJob: Job? = null

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
                } catch (_: IOException) {
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
    }

    /** Shows a new code for pairing (the screen shows it); [stopPairing] hides it */
    fun startPairing(): String {
        val code = (random.nextInt(900_000) + 100_000).toString()
        _justPaired.value = null
        _pairingCode.value = code
        return code
    }

    fun stopPairing() {
        _pairingCode.value = null
    }

    private suspend fun answer(context: Context, socket: BluetoothSocket) {
        Link(socket).use { link ->
            val first = link.readFrame()
            val remote = socket.remoteDevice.address
            // A paired device (its own address first)
            val peers = prefs.peers().sortedByDescending { it.address == remote }
            for (peer in peers) {
                val key = Base64.getDecoder().decode(peer.secret)
                val message = runCatching { decrypt(key, first) }.getOrNull() ?: continue
                link.key = key
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
        _justPaired.value = peer
        log("Paired with $name")
    }

    private suspend fun answerSync(context: Context, link: Link, peer: Peer, message: JsonObject) {
        if (message.string("t") != "sync") return
        val selection = PairSyncPreferences.parseSelection(message.string("selection").orEmpty())
        _status.update { it.copy(busy = true) }
        try {
            log("${peer.name} is syncing with this device: ${selection.describe()}")
            link.send(PairSyncData.export(selection))
            val theirs = link.receive()
            log("Got ${size(theirs)} from ${peer.name}")
            val summary = PairSyncData.import(theirs, ::log)
            link.sendJson(buildJsonObject { put("t", "done") })
            runCatching { link.receive() }
            prefs.savePeer(peer.copy(lastSync = System.currentTimeMillis(), name = message.string("name") ?: peer.name))
            log("Synced with ${peer.name}: $summary")
        } catch (e: Exception) {
            log("Sync with ${peer.name} stopped: ${e.message ?: e::class.simpleName}")
        } finally {
            _status.update { it.copy(busy = false) }
        }
    }

    // Connecting (the device that presses Pair or Sync)

    /** Pairs with [device], which is showing [code] on its Sync page */
    suspend fun pair(context: Context, device: BluetoothDevice, code: String): Result<Peer> = withContext(Dispatchers.IO) {
        _status.update { it.copy(busy = true) }
        val name = runCatching { device.name }.getOrNull() ?: device.address
        try {
            log("Pairing with $name…")
            connect(context, device).use { link ->
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
        } catch (e: Exception) {
            log("Couldn't pair: ${e.message ?: e::class.simpleName}")
            Result.failure(e)
        } finally {
            _status.update { it.copy(busy = false) }
        }
    }

    /** Syncs [selection] both ways with [peer], which needs its Sync page open */
    fun sync(context: Context, peer: Peer, selection: SyncSelection) {
        if (_status.value.busy) return
        _status.update { it.copy(busy = true) }
        scope.launch {
            try {
                if (selection.isEmpty) throw IOException("Pick something to sync first")
                log("Getting ready to sync ${selection.describe()}…")
                val mine = PairSyncData.export(selection)
                val device = adapter(context)?.getRemoteDevice(peer.address) ?: throw IOException("Bluetooth is off")
                log("Connecting to ${peer.name}…")
                connect(context, device).use { link ->
                    link.key = Base64.getDecoder().decode(peer.secret)
                    link.sendJson(
                        buildJsonObject {
                            put("t", "sync")
                            put("name", deviceName(context))
                            put("selection", Json.encodeToString(SyncSelection.serializer(), selection))
                        },
                    )
                    val theirs = try {
                        link.receive()
                    } catch (_: IOException) {
                        throw IOException("${peer.name} didn't answer. Open Sync on it, then try again")
                    }
                    log("Got ${size(theirs)} from ${peer.name}, sending ${size(mine)}…")
                    link.send(mine)
                    val summary = PairSyncData.import(theirs, ::log)
                    link.sendJson(buildJsonObject { put("t", "done") })
                    runCatching { link.receive() }
                    prefs.savePeer(peer.copy(lastSync = System.currentTimeMillis()))
                    log("Synced with ${peer.name}: $summary")
                }
            } catch (e: Exception) {
                log("Sync stopped: ${e.message ?: e::class.simpleName}")
            } finally {
                _status.update { it.copy(busy = false) }
            }
        }
    }

    private fun connect(context: Context, device: BluetoothDevice): Link {
        adapter(context)?.cancelDiscovery()
        val socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
        try {
            socket.connect()
        } catch (e: IOException) {
            runCatching { socket.close() }
            val name = runCatching { device.name }.getOrNull() ?: device.address
            throw IOException("Couldn't reach $name. It needs Bluetooth on and its Sync page open", e)
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

        fun readFrame(): ByteArray {
            val length = input.readInt()
            if (length !in 13..MAX_FRAME) throw IOException("Bad data from the other device")
            return ByteArray(length).also { input.readFully(it) }
        }

        fun send(data: ByteArray) {
            val frame = encrypt(key, data)
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
        }

        fun receive(): ByteArray = decrypt(key, readFrame())

        fun sendJson(message: JsonObject) = send(message.toString().toByteArray())

        fun receiveJson(): JsonObject = parse(receive())

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
