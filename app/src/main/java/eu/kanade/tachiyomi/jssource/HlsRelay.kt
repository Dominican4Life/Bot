package eu.kanade.tachiyomi.jssource

import android.net.Uri
import eu.kanade.tachiyomi.diagnostics.HlsTrace
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NoNetLog
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

    /** `URI="..."` inside a playlist tag (r95: compiled once, not once per tag line) */
    private val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")

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

    /**
     * KMK (r118): the address mpv should open instead of [url], a plain video file (mp4, mkv, webm…) fetched with
     * [headers] through the app's client, so it gets the same cookies the source's own requests and downloads get.
     * Hosts that answer mpv's direct request with 403 (no cookies) play this way. Range is passed on, so seeking
     * works. Call off the main thread the first time (opens the local socket).
     */
    @Synchronized
    fun wrapFile(url: String, headers: Headers): String {
        val id = nextId.getAndIncrement().toString()
        sessions[id] = headers
        originals[id] = url
        lastUsed[id] = System.currentTimeMillis()
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "flv", "ts") } ?: "mp4"
        return "http://127.0.0.1:${port()}/f/$id?u=${Uri.encode(url)}&x=video.$ext"
    }

    /** The stream behind a relayed address, or the address itself when it isn't one */
    fun original(url: String): String {
        val match = Regex("""^http://127\.0\.0\.1:\d+/[pf]/(\d+)\?""").find(url) ?: return url
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
            if (target == null || headers == null || (kind != "p" && kind != "s" && kind != "f")) {
                respond(out, 404, "text/plain", "Not found".toByteArray())
                return
            }
            // KMK: this stream is in use (see lastUsed)
            if (id != null) lastUsed[id] = System.currentTimeMillis()
            // KMK (r118): a plain video file: passed through as it comes (Range kept), with the app client's cookies
            if (kind == "f") {
                serveFile(out, target, headers, range)
                return
            }
            // KMK (r95): pieces go through the stream client too (no 120 s call deadline, no 5 MiB HTTP cache that
            // wrote every piece to flash once) and without the per-request "net" Logs line (one per piece pushed
            // everything else out of Logs)
            val request = Request.Builder().url(target).headers(headers)
                .apply { range?.takeIf { kind == "p" && it.isNotEmpty() }?.let { header("Range", it) } }
                .apply { if (kind == "s") tag(NoNetLog::class.java, NoNetLog) }
                .build()
            // KMK (r97): Diagnostics player trace: one record per served playlist / piece (timer, bytes, status)
            val traceKind = if (kind == "p") "playlist" else "segment"
            val traceHost = if (HlsTrace.wanted()) target.toHttpUrlOrNull()?.host ?: "?" else "" // parsed only when used
            val traceStart = System.nanoTime()
            var traceRetries = 0
            var call = try {
                execute(request, stream = true)
            } catch (e: java.io.IOException) {
                AppLog.add('W', "hls", "Couldn't reach ${target.toHttpUrlOrNull()?.host ?: target}: ${e.message}")
                respond(out, 502, "text/plain", ByteArray(0))
                HlsTrace.piece(traceKind, traceHost, (System.nanoTime() - traceStart) / 1_000_000, 0L, 0, error = e.message)
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
                traceRetries = 1 // KMK (r97)
                call = try {
                    execute(
                        Request.Builder().url(target).headers(plain).tag(NoNetLog::class.java, NoNetLog).build(),
                        stream = true,
                    )
                } catch (e: java.io.IOException) {
                    AppLog.add('W', "hls", "Couldn't reach ${target.toHttpUrlOrNull()?.host ?: target}: ${e.message}")
                    respond(out, 502, "text/plain", ByteArray(0))
                    HlsTrace.piece(traceKind, traceHost, (System.nanoTime() - traceStart) / 1_000_000, 0L, 0, traceRetries, error = e.message)
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
            // KMK (r97): what the trace record says about this piece, filled in below
            var traceStatus = 0
            var traceBytes = 0L
            var traceRewritten = false
            var traceStripped = false
            try {
                call.use { response ->
                    traceStatus = response.code
                    traceBytes = response.body.contentLength().coerceAtLeast(0L)
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
                    // KMK (r95): a piece is decided from its first bytes. Only a playlist in disguise or a piece with a
                    // picture glued in front is read whole; everything else is streamed through as it arrives (before,
                    // every piece was read whole, copied into a String to test for #EXTM3U, and copied again)
                    val head = if (kind == "p") ByteArray(0) else runCatching { response.peekBody(16).bytes() }.getOrDefault(ByteArray(0))
                    val headText = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                    when {
                        kind == "p" || headText.startsWith("#EXTM3U") -> {
                            val text = runCatching { String(response.body.bytes(), Charsets.UTF_8) }.getOrDefault("")
                            val finalUrl = response.request.url.toString()
                            traceBytes = text.length.toLong()
                            traceRewritten = true
                            respond(out, 200, "application/vnd.apple.mpegurl", rewrite(id, finalUrl, text).toByteArray())
                        }
                        looksLikeImage(head) -> {
                            val bytes = response.body.bytes()
                            val cleaned = stripFakeImage(bytes)
                            if (cleaned.size != bytes.size) {
                                AppLog.add('D', "hls", "Removed a ${bytes.size - cleaned.size}-byte fake picture header from a stream piece")
                                traceStripped = true
                            }
                            traceBytes = bytes.size.toLong()
                            respond(out, 200, "video/mp2t", cleaned)
                        }
                        else -> passThrough(out, response, contentType = "video/mp2t")
                    }
                }
            } finally {
                HlsTrace.piece(
                    traceKind, traceHost, (System.nanoTime() - traceStart) / 1_000_000, traceBytes, traceStatus,
                    traceRetries, traceRewritten, traceStripped,
                )
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

    /** KMK (r118): a [wrapFile] request: the file with the source's headers + the app's cookies, streamed on */
    private fun serveFile(out: OutputStream, target: String, headers: Headers, range: String?) {
        val host = target.toHttpUrlOrNull()?.host ?: target
        val request = Request.Builder().url(target).headers(headers)
            .apply { range?.takeIf { it.isNotEmpty() }?.let { header("Range", it) } }
            .tag(NoNetLog::class.java, NoNetLog)
            .build()
        val response = try {
            execute(request, stream = true)
        } catch (e: java.io.IOException) {
            AppLog.add('W', "player", "Video file: couldn't reach $host: ${e.message}")
            respond(out, 502, "text/plain", ByteArray(0))
            return
        }
        response.use {
            if (!it.isSuccessful) {
                val cookies = runCatching { network.cookieJar.loadForRequest(target.toHttpUrl()).size }.getOrDefault(0)
                AppLog.add(
                    'W',
                    "player",
                    "Video file answered ${it.code} through the app's connection: $host " +
                        "(headers: ${headers.names().joinToString().ifEmpty { "none" }}; cookies: $cookies)",
                )
                respond(out, it.code, "text/plain", ByteArray(0))
                return
            }
            if (range == null) {
                AppLog.add('D', "player", "Video file through the app's connection: $host (${it.code})")
            }
            passThrough(out, it)
        }
    }

    /** Points every playlist, piece, key and map of a playlist at the relay (keys go direct) */
    private fun rewrite(id: String, base: String, playlist: String): String {
        val baseUrl = base.toHttpUrlOrNull()
        fun absolute(u: String): String = baseUrl?.resolve(u)?.toString() ?: u
        // KMK (r95): decided once per playlist, not once per line (a long VOD playlist has thousands of lines)
        val isMaster = playlist.contains("#EXT-X-STREAM-INF")
        return playlist.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> raw
                line.startsWith("#") -> {
                    // URI="..." inside tags: sub-playlists (media), init maps; keys stay as they are
                    if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY")) {
                        line.replace(URI_ATTRIBUTE) { "URI=\"${absolute(it.groupValues[1])}\"" }
                    } else {
                        line.replace(URI_ATTRIBUTE) { m ->
                            val u = absolute(m.groupValues[1])
                            val isPlaylist = line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME")
                            "URI=\"${relayUrl(id, u, isPlaylist)}\""
                        }
                    }
                }
                else -> {
                    val u = absolute(line)
                    val isPlaylist = u.substringBefore('?').endsWith(".m3u8", ignoreCase = true) || isMaster
                    relayUrl(id, u, isPlaylist)
                }
            }
        }
    }

    /** True when [data] starts like a PNG, JPEG, GIF, BMP or WebP file (the first 4 bytes are enough) */
    private fun looksLikeImage(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return (data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte()) ||
            (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) ||
            (data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte()) ||
            (data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte()) ||
            // KMK: WebP ("RIFF....WEBP")
            (data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() &&
                data[3] == 'F'.code.toByte())
    }

    /** The data without a picture glued in front of it: from the first MPEG-TS packet, or MP4 box */
    private fun stripFakeImage(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        if (!looksLikeImage(data)) return data
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

    /**
     * Sends [response] on as it came (status, type, length, range), streamed, without reading it into memory.
     * [contentType] replaces the origin's type (stream pieces are always sent as video/mp2t, as before r95).
     */
    private fun passThrough(out: OutputStream, response: okhttp3.Response, contentType: String? = null) {
        val body = response.body
        val type = contentType ?: response.header("Content-Type") ?: "application/octet-stream"
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
