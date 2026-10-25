package eu.kanade.tachiyomi.jssource

import android.net.Uri
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.AppLog
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * KMK: A small HTTP relay on this device for the HLS streams of Code editor sources.
 *
 * Some video hosts disguise each piece of a stream as a picture: a PNG (or JPEG / GIF) header comes before the
 * real MPEG-TS or MP4 data, so a browser plays it but mpv takes the piece for an image and stops at once ("Video:
 * png ... error reading packet", then the video ends). The relay fetches the playlists with the source's headers,
 * points every piece back at itself, and hands each piece over without the fake picture in front.
 */
object HlsRelay {

    private val network: NetworkHelper by lazy { Injekt.get() }
    private val pool = Executors.newCachedThreadPool()

    /** The streams handed out: id -> headers to fetch them with */
    private val sessions = ConcurrentHashMap<String, Headers>()

    /** The stream each relayed address stands for, for casting (a TV can't reach this device's relay) */
    private val originals = ConcurrentHashMap<String, String>()
    private val nextId = AtomicInteger(1)

    @Volatile
    private var server: ServerSocket? = null

    @Synchronized
    private fun port(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { serve(client) }.onFailure { runCatching { client.close() } } }
            }
        }, "HlsRelay").apply { isDaemon = true }.start()
        return socket.localPort
    }

    /** The address mpv should open instead of [url], an HLS playlist fetched with [headers] */
    fun wrap(url: String, headers: Headers): String {
        val id = nextId.getAndIncrement().toString()
        sessions[id] = headers
        originals[id] = url
        if (sessions.size > 500) sessions.keys.take(100).forEach { sessions.remove(it); originals.remove(it) }
        return relayUrl(id, url, playlist = true)
    }

    /** The stream behind a relayed address, or the address itself when it isn't one */
    fun original(url: String): String {
        val match = Regex("""^http://127\.0\.0\.1:\d+/p/(\d+)\?""").find(url) ?: return url
        return originals[match.groupValues[1]] ?: url
    }

    private fun relayUrl(id: String, url: String, playlist: Boolean): String =
        // Ends in .m3u8 / .ts: newer FFmpeg checks a piece's extension, and a host's ".png" would be refused
        "http://127.0.0.1:${port()}/${if (playlist) "p" else "s"}/$id?u=${Uri.encode(url)}&x=${if (playlist) "list.m3u8" else "piece.ts"}"

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            // Skip the request headers
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1) ?: return
            val parsed = Uri.parse("http://relay$path")
            val segments = parsed.pathSegments
            val kind = segments.getOrNull(0)
            val id = segments.getOrNull(1)
            val target = parsed.getQueryParameter("u")
            val headers = id?.let { sessions[it] }
            val out = socket.getOutputStream()
            if (target == null || headers == null || (kind != "p" && kind != "s")) {
                respond(out, 404, "text/plain", "Not found".toByteArray())
                return
            }
            val request = Request.Builder().url(target).headers(headers).build()
            network.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    respond(out, response.code, "text/plain", ByteArray(0))
                    return
                }
                val bytes = response.body.bytes()
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                if (kind == "p" || text.startsWith("#EXTM3U")) {
                    val finalUrl = response.request.url.toString()
                    respond(out, 200, "application/vnd.apple.mpegurl", rewrite(id, finalUrl, text).toByteArray())
                } else {
                    val cleaned = stripFakeImage(bytes)
                    if (cleaned.size != bytes.size) {
                        AppLog.add('D', "hls", "Removed a ${bytes.size - cleaned.size}-byte fake picture header from a stream piece")
                    }
                    respond(out, 200, "video/mp2t", cleaned)
                }
            }
        }
    }

    /** Points every playlist, piece, key and map of a playlist at the relay (keys go direct) */
    private fun rewrite(id: String, base: String, playlist: String): String {
        val baseUrl = base.toHttpUrlOrNull()
        fun absolute(u: String): String = baseUrl?.resolve(u)?.toString() ?: u
        return playlist.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> raw
                line.startsWith("#") -> {
                    // URI="..." inside tags: sub-playlists (media), init maps; keys stay as they are
                    if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY")) {
                        line.replace(Regex("""URI="([^"]+)"""")) { "URI=\"${absolute(it.groupValues[1])}\"" }
                    } else {
                        line.replace(Regex("""URI="([^"]+)"""")) { m ->
                            val u = absolute(m.groupValues[1])
                            val isPlaylist = line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME")
                            "URI=\"${relayUrl(id, u, isPlaylist)}\""
                        }
                    }
                }
                else -> {
                    val u = absolute(line)
                    val isPlaylist = u.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                        playlist.contains("#EXT-X-STREAM-INF")
                    relayUrl(id, u, isPlaylist)
                }
            }
        }
    }

    /** The data without a picture glued in front of it: from the first MPEG-TS packet, or MP4 box */
    private fun stripFakeImage(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        val looksLikeImage =
            (data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte()) ||
                (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) ||
                (data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte()) ||
                (data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte())
        if (!looksLikeImage) return data
        val limit = minOf(data.size - 1, 1_000_000)
        // MPEG-TS: three sync bytes 188 apart
        for (i in 0 until limit) {
            if (data[i] == 0x47.toByte() &&
                i + 376 < data.size && data[i + 188] == 0x47.toByte() && data[i + 376] == 0x47.toByte()
            ) {
                return data.copyOfRange(i, data.size)
            }
        }
        // fMP4: a box type right after its 4-byte size
        val boxes = listOf("ftyp", "styp", "moof", "sidx").map { it.toByteArray() }
        for (i in 4 until limit - 4) {
            if (boxes.any { b -> data[i] == b[0] && data[i + 1] == b[1] && data[i + 2] == b[2] && data[i + 3] == b[3] }) {
                return data.copyOfRange(i - 4, data.size)
            }
        }
        return data
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) return sb.toString()
        }
    }
}
