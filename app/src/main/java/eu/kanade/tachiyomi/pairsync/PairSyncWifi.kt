package eu.kanade.tachiyomi.pairsync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * KMK (r90): The fast ways for pair sync to move its data once Bluetooth has found and checked the other device:
 * a Wi-Fi Direct group (the waiting device is the group owner, the other one joins with the name and passphrase it got
 * over Bluetooth, so no accept prompt shows on Android 10+), or a plain TCP connection over the Wi-Fi both are on.
 * Everything here is best effort: any failure returns null and pair sync tries the next way (or Bluetooth).
 */
@SuppressLint("MissingPermission")
internal object PairSyncWifi {

    /** Socket buffers for the fast connection */
    const val SOCKET_BUFFER = 1024 * 1024

    private const val ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    /** Interfaces that aren't a Wi-Fi or wired LAN (mobile data, VPN, Wi-Fi Direct, ...) */
    private val NOT_LAN = listOf("p2p", "rmnet", "ccmni", "tun", "ipsec", "dummy", "v4-", "clat", "lo", "radio")

    private const val OK = -100
    private const val TIMED_OUT = -101
    private const val THREW = -102

    private val random = SecureRandom()

    private val ignore = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) = Unit
    }

    /**
     * The runtime permissions Wi-Fi Direct needs on this Android version. KMK (r90): none below Android 10, where pair
     * sync doesn't use Wi-Fi Direct (it asked for location on every Sync for nothing)
     */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }

    /** Why Wi-Fi Direct can't be used on this device right now, or null when it can */
    fun p2pUnavailable(context: Context): String? = runCatching {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "it needs Android 10 or newer"
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) ->
                "this device has no Wi-Fi Direct"
            context.getSystemService(WifiManager::class.java)?.isWifiEnabled == false -> "Wi-Fi is off"
            permissions().any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "the Nearby devices permission isn't allowed"
                } else {
                    "the Location permission isn't allowed"
                }
            else -> null
        }
    }.getOrElse { "it couldn't be checked" }

    private fun randomText(length: Int): String =
        buildString { repeat(length) { append(ALNUM[random.nextInt(ALNUM.length)]) } }

    private fun reason(code: Int): String = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported"
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
        WifiP2pManager.ERROR -> "refused; is Location on?"
        TIMED_OUT -> "no answer"
        THREW -> "not allowed"
        else -> "error $code"
    }

    private fun open(context: Context): Pair<WifiP2pManager, WifiP2pManager.Channel>? = runCatching {
        val manager = context.getSystemService(WifiP2pManager::class.java) ?: return null
        val channel = manager.initialize(context.applicationContext, Looper.getMainLooper(), null) ?: return null
        manager to channel
    }.getOrNull()

    private fun closeChannel(channel: WifiP2pManager.Channel?) {
        if (channel != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { channel.close() }
    }

    /** Runs a Wi-Fi Direct request; [OK] or the failure reason */
    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit): Int =
        withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine<Int> { cont ->
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resume(OK)
                    }

                    override fun onFailure(reason: Int) {
                        if (cont.isActive) cont.resume(reason)
                    }
                }
                try {
                    block(listener)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(THREW)
                }
            }
        } ?: TIMED_OUT

    private suspend fun groupInfo(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pGroup? =
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine<WifiP2pGroup?> { cont ->
                try {
                    manager.requestGroupInfo(channel) { group -> if (cont.isActive) cont.resume(group) }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private suspend fun connectionInfo(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pInfo? =
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine<WifiP2pInfo?> { cont ->
                try {
                    manager.requestConnectionInfo(channel) { info -> if (cont.isActive) cont.resume(info) }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /**
     * KMK (r90): removes the group this device is in (a stale one of this app's or another app's), then waits up to
     * about 3 s until it's really gone: removeGroup's success only means the request was taken, and a group made
     * during the removal is refused as BUSY
     */
    private suspend fun clearGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        if (groupInfo(manager, channel) == null) return
        action { manager.removeGroup(channel, it) }
        withTimeoutOrNull(3_000L) {
            while (groupInfo(manager, channel) != null) delay(250L)
        }
    }

    /** The IPv4 address of this device's Wi-Fi Direct interface, if it has one */
    private fun p2pAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.name.startsWith("p2p") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
    }.getOrNull()

    /** A Wi-Fi Direct group this device made: its name, passphrase and this device's address in it */
    class Group(val ssid: String, val pass: String, val address: InetAddress)

    /** The waiting device's side: makes a group the other device joins. [close] removes it */
    class Owner(private val context: Context) : Closeable {
        // KMK (r90): set on the IO thread, read by close() on any thread (Stop runs on the main one)
        @Volatile private var manager: WifiP2pManager? = null

        @Volatile private var channel: WifiP2pManager.Channel? = null

        // KMK (r90): set by close(), so a Stop while the channel is opening doesn't leave it open
        @Volatile private var closed = false

        suspend fun start(log: (String) -> Unit): Group? {
            p2pUnavailable(context)?.let {
                log("Not using Wi-Fi Direct: $it")
                return null
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val (m, c) = open(context) ?: run {
                log("Not using Wi-Fi Direct: it didn't start")
                return null
            }
            manager = m
            channel = c
            // KMK (r90): close() ran while open() did (it saw no channel): closed here instead
            if (closed) {
                close()
                return null
            }
            return try {
                createIn(m, c, log)
            } catch (e: Throwable) {
                close()
                throw e
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private suspend fun createIn(m: WifiP2pManager, c: WifiP2pManager.Channel, log: (String) -> Unit): Group? {
            // A group left from before (this app's or another's) would stop a new one
            clearGroup(m, c)
            val ssid = "DIRECT-KM-" + randomText(4)
            val pass = randomText(12)
            val config = try {
                WifiP2pConfig.Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(pass)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    .build()
            } catch (e: Exception) {
                log("Not using Wi-Fi Direct: ${e.message ?: "bad settings"}")
                return null
            }
            // KMK (r90): close() removes the group whatever happens from here (Stop or a slow start used to leave it
            // up); one more try when the framework is still busy removing the old one
            var result = action { m.createGroup(c, config, it) }
            if (result == WifiP2pManager.BUSY) {
                delay(1_000L)
                result = action { m.createGroup(c, config, it) }
            }
            if (result != OK) {
                log("Wi-Fi Direct didn't start (${reason(result)})")
                return null
            }
            var address: InetAddress? = null
            withTimeoutOrNull(10_000L) {
                while (address == null) {
                    val info = connectionInfo(m, c)
                    if (info != null && info.groupFormed && info.isGroupOwner && info.groupOwnerAddress != null) {
                        address = info.groupOwnerAddress
                    } else {
                        delay(300L)
                    }
                }
            }
            val owner = address ?: p2pAddress() ?: runCatching { InetAddress.getByName("192.168.49.1") }.getOrNull()
            if (owner == null) {
                log("Wi-Fi Direct started without an address")
                return null
            }
            return Group(ssid, pass, owner)
        }

        override fun close() {
            closed = true
            val m = manager
            val c = channel
            manager = null
            channel = null
            // KMK (r90): always, once the channel exists: a group asked for may still form after a stop or time-out
            if (m != null && c != null) runCatching { m.removeGroup(c, ignore) }
            closeChannel(c)
        }
    }

    /** The device pressing Sync: joins the other device's group. [close] leaves it */
    class Client(private val context: Context) : Closeable {
        // KMK (r90): set on the IO thread, read by close() on any thread
        @Volatile private var manager: WifiP2pManager? = null

        @Volatile private var channel: WifiP2pManager.Channel? = null

        // KMK (r90): set by close(), so a Stop while the channel is opening doesn't leave it open
        @Volatile private var closed = false

        /** Joins the group [ssid]; the group owner's address, or null */
        suspend fun join(ssid: String, pass: String, log: (String) -> Unit): InetAddress? {
            p2pUnavailable(context)?.let {
                log("Not using Wi-Fi Direct: $it")
                return null
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val (m, c) = open(context) ?: run {
                log("Not using Wi-Fi Direct: it didn't start")
                return null
            }
            manager = m
            channel = c
            // KMK (r90): close() ran while open() did (it saw no channel): closed here instead
            if (closed) {
                close()
                return null
            }
            return try {
                joinIn(m, c, ssid, pass, log)
            } catch (e: Throwable) {
                close()
                throw e
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private suspend fun joinIn(
            m: WifiP2pManager,
            c: WifiP2pManager.Channel,
            ssid: String,
            pass: String,
            log: (String) -> Unit,
        ): InetAddress? {
            val config = try {
                WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).build()
            } catch (e: Exception) {
                log("Not using Wi-Fi Direct: ${e.message ?: "bad settings"}")
                return null
            }
            // KMK (r90): a group this device was left in (e.g. as the owner from an earlier sync) stops the join
            clearGroup(m, c)
            var result = action { m.connect(c, config, it) }
            if (result == WifiP2pManager.BUSY) {
                delay(1_000L)
                result = action { m.connect(c, config, it) }
            }
            if (result != OK) {
                log("Couldn't join the other device's Wi-Fi Direct (${reason(result)})")
                return null
            }
            var address: InetAddress? = null
            withTimeoutOrNull(15_000L) {
                while (address == null) {
                    val info = connectionInfo(m, c)
                    val owner = info?.groupOwnerAddress
                    if (info != null && info.groupFormed && !info.isGroupOwner && owner != null) {
                        val group = groupInfo(m, c)
                        if (group == null || group.networkName == ssid) address = owner else delay(500L)
                    } else {
                        delay(500L)
                    }
                }
            }
            if (address == null) log("Joining the other device's Wi-Fi Direct took too long")
            return address
        }

        override fun close() {
            closed = true
            val m = manager
            val c = channel
            manager = null
            channel = null
            // KMK (r90): always, once the channel exists: a join asked for may still go through after a stop
            if (m != null && c != null) {
                runCatching { m.cancelConnect(c, ignore) }
                runCatching { m.removeGroup(c, ignore) }
            }
            closeChannel(c)
        }
    }

    /** This device's IPv4 addresses on Wi-Fi (first) and other local networks */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { nif ->
                runCatching { nif.isUp && !nif.isLoopback && !nif.isVirtual }.getOrDefault(false) &&
                    NOT_LAN.none { nif.name.startsWith(it) }
            }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filter { it is Inet4Address && it.isSiteLocalAddress }
                    .mapNotNull { it.hostAddress }
            }
            .distinct()
    }.getOrDefault(emptyList())

    /**
     * KMK (r90): how long a data socket may wait for the other device before the sync ends (a link that died). A
     * device building what it sends says {"t":"wait"} every [KEEP_ALIVE] ms meanwhile, so a big library doesn't hit it.
     */
    const val READ_TIMEOUT = 180_000

    /** KMK (r90): how often a device still building its export tells the other one over Wi-Fi */
    const val KEEP_ALIVE = 30_000L

    fun tune(socket: Socket) {
        runCatching { socket.sendBufferSize = SOCKET_BUFFER }
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER }
        runCatching { socket.tcpNoDelay = true }
        runCatching { socket.keepAlive = true }
    }

    /** A server for one connection, on [bindTo] (all addresses when null) and a free port */
    fun server(bindTo: InetAddress?): ServerSocket {
        val server = ServerSocket()
        try {
            runCatching { server.receiveBufferSize = SOCKET_BUFFER }
            server.reuseAddress = true
            server.bind(if (bindTo != null) InetSocketAddress(bindTo, 0) else InetSocketAddress(0), 1)
        } catch (e: Exception) {
            runCatching { server.close() }
            throw e
        }
        return server
    }

    /** Connects to the first of [hosts] that answers on [port], each tried for [timeoutMs] */
    fun connect(hosts: List<String>, port: Int, timeoutMs: Int): Socket? {
        for (host in hosts) {
            val socket = Socket()
            try {
                tune(socket)
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                return socket
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
        return null
    }
}
