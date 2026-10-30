package eu.kanade.tachiyomi.jssource

import android.net.Uri
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.AppLog
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.util.concurrent.TimeUnit
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

    // KMK: when each stream was last handed out or served (a playlist or a piece), so trimming drops the least
    // recently used ones, not a long video that started early and is still playing
    private val lastUsed = ConcurrentHashMap<String, Long>()
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
    @Synchronized
    fun wrap(url: String, headers: Headers): String {
        val id = nextId.getAndIncrement().toString()
        sessions[id] = headers
        originals[id] = url
        lastUsed[id] = System.currentTimeMillis()
        // KMK: past 500, the 100 least recently used go (r87; r86 dropped the 100 handed out first, which could be a
        // video still playing: its next playlist or piece then got 404). Same time: the older id goes first
        if (sessions.size > 500) {
            // KMK: sorted on a snapshot: serving threads update lastUsed meanwhile, and a sort whose values change
            // under it can throw ("Comparison method violates its general contract")
            val unused = sessions.keys.toList()
                .map { id -> Triple(id, lastUsed[id] ?: 0L, id.toLongOrNull() ?: Long.MIN_VALUE) }
                .sortedWith(compareBy<Triple<String, Long, Long>>({ it.second }, { it.third }))
                .take(100)
                .map { it.first }
            unused.forEach {
                sessions.remove(it)
                originals.remove(it)
                lastUsed.remove(it)
            }
            AppLog.add('D', "hls", "Let go of the ${unused.size} oldest stream addresses (more than 500 handed out)")
        }
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
            // Skip the request headers (KMK: but keep Range, for an address that turns out to be a plain file)
            var range: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
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
            // KMK: this stream is in use (see lastUsed)
            if (id != null) lastUsed[id] = System.currentTimeMillis()
            val request = Request.Builder().url(target).headers(headers)
                .apply { range?.takeIf { kind == "p" && it.isNotEmpty() }?.let { header("Range", it) } }
                .build()
            var call = try {
                execute(request, stream = kind == "p")
            } catch (e: java.io.IOException) {
                AppLog.add('W', "hls", "Couldn't reach ${target.toHttpUrlOrNull()?.host ?: target}: ${e.message}")
                respond(out, 502, "text/plain", ByteArray(0))
                return
            }
            // KMK: pieces kept on another site (some hosts put them on image servers) may refuse the source's
            // Referer / Origin: asked again with only the browser's user agent
            if (!call.isSuccessful && kind == "s") {
                val first = call.code
                call.close()
                val plain = Headers.Builder().apply {
                    headers["User-Agent"]?.let { add("User-Agent", it) }
                    add("Accept", "*/*")
                }.build()
                call = try {
                    execute(Request.Builder().url(target).headers(plain).build())
                } catch (e: java.io.IOException) {
                    AppLog.add('W', "hls", "Couldn't reach ${target.toHttpUrlOrNull()?.host ?: target}: ${e.message}")
                    respond(out, 502, "text/plain", ByteArray(0))
                    return
                }
                AppLog.add(
                    if (call.isSuccessful) 'D' else 'W',
                    "hls",
                    "Stream piece answered $first with the source's headers" +
                        (if (call.isSuccessful) ", worked without them" else " and ${call.code} without them") +
                        ": ${target.toHttpUrlOrNull()?.host ?: target}",
                )
            }
            call.use { response ->
                if (!response.isSuccessful) {
                    if (kind == "p") AppLog.add('W', "hls", "Playlist answered ${response.code}: ${target.toHttpUrlOrNull()?.host ?: target}")
                    respond(out, response.code, "text/plain", ByteArray(0))
                    return
                }
                // KMK: "m3u8" only in the query (`video.mp4?fallback=m3u8`) also comes here as a playlist: unless the
                // answer is one (its type, or #EXTM3U at the start), it's passed through as it is, never read whole
                if (kind == "p" && !isPlaylist(response)) {
                    passThrough(out, response)
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

    // KMK --> a host whose address comes back as 0.0.0.0 (what ad blockers and filtering DNS answer for blocked
    // names; some video hosts keep their pieces on ad servers) is looked up again with Cloudflare's and Google's DNS
    private val bypassClient by lazy {
        val bootstrap = network.client.newBuilder().dns(okhttp3.Dns.SYSTEM).build()
        val resolvers = listOf(
            "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1"),
            "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4"),
        ).map { (url, hosts) ->
            okhttp3.dnsoverhttps.DnsOverHttps.Builder().client(bootstrap)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(hosts.map { InetAddress.getByName(it) })
                .build()
        }
        network.client.newBuilder().dns(
            object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    var last: Exception? = null
                    for (resolver in resolvers) {
                        try {
                            val found = resolver.lookup(hostname).filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
                            if (found.isNotEmpty()) return found
                        } catch (e: Exception) {
                            last = e
                        }
                    }
                    throw java.net.UnknownHostException("$hostname: no address from Cloudflare or Google DNS (${last?.message})")
                }
            },
        ).build()
    }

    // KMK: `p` requests can turn out to be a whole plain video passed through (passThrough): the app client's 120 s
    // call timeout would cut it off after ~2 minutes, and its 5 MiB HTTP cache ignores Range. These have no overall
    // deadline (connect/read timeouts still apply) and no cache.
    private val streamClient by lazy {
        network.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).cache(null).build()
    }
    private val bypassStreamClient by lazy {
        bypassClient.newBuilder().callTimeout(0, TimeUnit.SECONDS).cache(null).build()
    }

    private val bypassedHosts = ConcurrentHashMap.newKeySet<String>()

    /** The request through the app's client, or around a DNS that blocked its host ([stream]: no call deadline) */
    private fun execute(request: Request, stream: Boolean = false): okhttp3.Response {
        val host = request.url.host
        val direct = if (stream) streamClient else network.client
        val bypass = if (stream) bypassStreamClient else bypassClient
        if (host in bypassedHosts) return bypass.newCall(request).execute()
        return try {
            direct.newCall(request).execute()
        } catch (e: java.io.IOException) {
            val blocked = e.message?.contains("/0.0.0.0") == true || e.message?.contains("/127.0.0.1:") == true ||
                runCatching { InetAddress.getAllByName(host).all { it.isAnyLocalAddress || it.isLoopbackAddress } }
                    .getOrDefault(false)
            if (!blocked) throw e
            AppLog.add(
                'W',
                "hls",
                "$host is blocked by this device's DNS or ad blocker (it answers 0.0.0.0); asking Cloudflare / Google DNS instead",
            )
            val response = bypass.newCall(request).execute()
            bypassedHosts += host
            AppLog.add('I', "hls", "$host reached through Cloudflare / Google DNS (${response.code})")
            response
        }
    }
    // KMK <--

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
                (data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte()) ||
                // KMK: WebP ("RIFF....WEBP")
                (data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() &&
                    data[3] == 'F'.code.toByte())
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

    /** True when [response] is an HLS playlist: by its type, or by #EXTM3U at the start (only a peek is read) */
    private fun isPlaylist(response: okhttp3.Response): Boolean {
        val type = response.header("Content-Type").orEmpty().lowercase()
        if ("mpegurl" in type) return true
        val head = runCatching { response.peekBody(64).bytes() }.getOrDefault(ByteArray(0))
        val text = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return text.startsWith("#EXTM3U")
    }

    /** Sends [response] on as it came (status, type, length, range), streamed, without reading it into memory */
    private fun passThrough(out: OutputStream, response: okhttp3.Response) {
        val body = response.body
        val type = response.header("Content-Type") ?: "application/octet-stream"
        val length = body.contentLength()
        val head = StringBuilder()
            .append("HTTP/1.1 ${response.code} ${if (response.code == 206) "Partial Content" else "OK"}\r\n")
            .append("Content-Type: $type\r\n")
        if (length >= 0) head.append("Content-Length: $length\r\n")
        response.header("Content-Range")?.let { head.append("Content-Range: $it\r\n") }
        response.header("Accept-Ranges")?.let { head.append("Accept-Ranges: $it\r\n") }
        head.append("Connection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n")
        out.write(head.toString().toByteArray())
        body.byteStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
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
