package eu.kanade.tachiyomi.jssource.lab

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * KMK (r116): does a caught video address really play? Follows it the way a player would, with the same [headers] on
 * every request (the app's client):
 * - HLS: playlist → the first quality (lowest bandwidth) → its key (when `#EXT-X-KEY` METHOD isn't NONE) → the first
 *   piece (`Range: bytes=0-65535`)
 * - DASH: the manifest (DRM → not ok, "DRM"; reported only, never worked around) → the first segment
 * - a file: `Range: bytes=0-65535` and a sniff of the bytes (mp4 ftyp, mkv / webm, MPEG-TS)
 *
 * [Result.stage] is the last stage reached ("playlist", "quality", "key", "piece", "file"); [Result.detail] says what
 * was found or what failed ("piece 403 · no Referer sent"). Never throws (cancellation goes through).
 */
object VideoCheck {

    data class Result(val ok: Boolean, val stage: String, val detail: String, val pieceBytes: Int)

    /** One request the check made */
    data class Hop(val stage: String, val url: String, val status: Int?, val ms: Long, val bytes: Int, val error: String?)

    data class Trace(val result: Result, val hops: List<Hop>)

    suspend fun check(url: String, headers: Map<String, String>): Result = trace(url, headers).result

    /** [check] with every request it made (the Test Run lists them) */
    suspend fun trace(url: String, headers: Map<String, String>): Trace = withContext(Dispatchers.IO) {
        val run = Run(headers)
        val result = try {
            run.start(url.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result(false, "playlist", "check failed: ${e.message ?: e.javaClass.simpleName}", 0)
        }
        Trace(result, run.hops.toList())
    }

    private const val PIECE_RANGE = 65535
    private const val MAX_TEXT = 2 * 1024 * 1024

    private val client by lazy {
        Injekt.get<NetworkHelper>().client.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private class Reply(val status: Int?, val bytes: ByteArray, val contentType: String?, val finalUrl: String, val error: String?) {
        val ok: Boolean get() = status != null && status in 200..299
        val text: String get() = String(bytes, Charsets.UTF_8)
    }

    private class Run(private val sourceHeaders: Map<String, String>) {
        val hops = ArrayList<Hop>()

        private val hasReferer = sourceHeaders.keys.any { it.equals("Referer", ignoreCase = true) }

        private fun get(stage: String, url: String, rangeEnd: Int?, limit: Int): Reply {
            val started = System.currentTimeMillis()
            val builder = Headers.Builder()
            sourceHeaders.forEach { (k, v) -> runCatching { builder.set(k, v) } }
            if (builder["User-Agent"] == null) {
                runCatching { builder.set("User-Agent", Injekt.get<NetworkHelper>().defaultUserAgentProvider()) }
            }
            if (rangeEnd != null) builder.set("Range", "bytes=0-$rangeEnd")
            val reply = try {
                val request = Request.Builder().url(url).headers(builder.build()).get().build()
                client.newCall(request).execute().use { response ->
                    val body = runCatching {
                        response.body.byteStream().use { stream ->
                            val buffer = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(16 * 1024)
                            while (buffer.size() < limit) {
                                val n = stream.read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
                                if (n <= 0) break
                                buffer.write(chunk, 0, n)
                            }
                            buffer.toByteArray()
                        }
                    }.getOrDefault(ByteArray(0))
                    Reply(response.code, body, response.header("Content-Type"), response.request.url.toString(), null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Reply(null, ByteArray(0), null, url, e.message ?: e.javaClass.simpleName)
            }
            hops.add(Hop(stage, url, reply.status, System.currentTimeMillis() - started, reply.bytes.size, reply.error))
            return reply
        }

        /** "403 · no Referer sent", "timeout" … */
        private fun failure(stage: String, reply: Reply): String {
            val what = reply.status?.toString() ?: reply.error ?: "no reply"
            val hint = when {
                reply.status == 403 && !hasReferer -> " · no Referer sent"
                reply.status == 403 -> " · refused with these headers"
                reply.status == 404 || reply.status == 410 -> " · gone (the address may have expired)"
                reply.status == 429 -> " · too many requests"
                else -> ""
            }
            return "$stage $what$hint"
        }

        fun start(url: String): Result {
            if (url.toHttpUrlOrNull() == null) return Result(false, "file", "not an http address", 0)
            val lower = url.lowercase()
            val looksHls = "m3u8" in lower
            val looksDash = ".mpd" in lower
            // A playlist by its address: whole (capped); anything else: its first 64 KB, then decided by content
            val first = if (looksHls || looksDash) get("playlist", url, null, MAX_TEXT) else get("file", url, PIECE_RANGE, PIECE_RANGE + 1)
            if (!first.ok) return Result(false, if (looksHls || looksDash) "playlist" else "file", failure(if (looksHls || looksDash) "playlist" else "file", first), 0)
            val head = String(first.bytes, 0, minOf(first.bytes.size, 512), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\n', '\r', '\t')
            return when {
                head.startsWith("#EXTM3U") -> {
                    // Read in 64 KB only: a longer playlist is fetched whole
                    val reply = if (!looksHls && first.bytes.size > PIECE_RANGE) get("playlist", url, null, MAX_TEXT) else first
                    if (!reply.ok) Result(false, "playlist", failure("playlist", reply), 0) else hls(reply.finalUrl, reply.text, depth = 0)
                }
                head.contains("<MPD", ignoreCase = true) || (head.startsWith("<?xml") && looksDash) -> {
                    val reply = if (!looksDash && first.bytes.size > PIECE_RANGE) get("playlist", url, null, MAX_TEXT) else first
                    if (!reply.ok) Result(false, "playlist", failure("playlist", reply), 0) else dash(reply.finalUrl, reply.text)
                }
                looksHls || looksDash -> Result(
                    false,
                    "playlist",
                    "playlist answered ${first.status} but isn't a playlist (${kindOfBytes(first.bytes, first.contentType) ?: first.contentType ?: "unknown bytes"})",
                    0,
                )
                else -> file(first)
            }
        }

        // ---------- HLS ----------

        private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

        private fun attrs(line: String): Map<String, String> =
            ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

        private fun resolve(base: String, ref: String): String? =
            base.toHttpUrlOrNull()?.resolve(ref.trim())?.toString()

        private fun hls(url: String, text: String, depth: Int): Result {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val variants = ArrayList<Pair<Long, String>>()   // bandwidth → uri
            for (i in lines.indices) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                    val a = attrs(lines[i])
                    val uri = lines.drop(i + 1).firstOrNull { !it.startsWith("#") } ?: continue
                    variants.add((a["BANDWIDTH"]?.toLongOrNull() ?: Long.MAX_VALUE) to uri)
                }
            }
            if (variants.isNotEmpty()) {
                if (depth >= 2) return Result(false, "quality", "playlists nested too deep", 0)
                val lowest = variants.minBy { it.first }
                val lowestLine = lines.indexOfFirst { it == lowest.second }
                val resolution = lines.getOrNull(lowestLine - 1)?.takeIf { it.startsWith("#EXT-X-STREAM-INF") }?.let { attrs(it)["RESOLUTION"] }
                val target = resolve(url, lowest.second) ?: return Result(false, "quality", "quality address unreadable: ${lowest.second.take(80)}", 0)
                val reply = get("quality", target, null, MAX_TEXT)
                if (!reply.ok) return Result(false, "quality", failure("quality", reply), 0)
                if (!reply.text.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U")) {
                    return Result(false, "quality", "quality answered ${reply.status} but isn't a playlist", 0)
                }
                val inner = hls(reply.finalUrl, reply.text, depth + 1)
                // KMK (r120): every quality the stream holds ("holds 1080p / 720p / 480p"): a source that sends this as
                // one video ("Auto") still lets the player list them (Quality sheet › Inside this stream)
                val heights = lines.filter { it.startsWith("#EXT-X-STREAM-INF") }
                    .mapNotNull { attrs(it)["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull() }
                    .distinct().sortedDescending()
                val holds = if (heights.isNotEmpty()) " · holds ${heights.joinToString(" / ") { "${it}p" }}" else ""
                val prefix = "${variants.size} qualit${if (variants.size == 1) "y" else "ies"}" + holds +
                    (resolution?.let { " · checked the lowest ($it)" } ?: "") +
                    (if (variants.size > 1) " · the player lists them under Inside this stream" else "")
                return inner.copy(detail = "$prefix · ${inner.detail}")
            }
            // A media playlist: its key, then its first piece
            var keyNote = ""
            val keyLine = lines.firstOrNull { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-SESSION-KEY") }
            if (keyLine != null) {
                val a = attrs(keyLine)
                val method = a["METHOD"].orEmpty().uppercase()
                if (method.isNotEmpty() && method != "NONE") {
                    val keyFormat = a["KEYFORMAT"].orEmpty()
                    val keyUri = a["URI"].orEmpty()
                    if (keyFormat.isNotEmpty() && keyFormat != "identity") {
                        return Result(false, "key", "DRM ($method, $keyFormat): can't be played", 0)
                    }
                    if (keyUri.isBlank() || keyUri.startsWith("skd:", ignoreCase = true)) {
                        return Result(false, "key", "DRM ($method without a key address): can't be played", 0)
                    }
                    if (!keyUri.startsWith("data:", true)) {
                        val target = resolve(url, keyUri) ?: return Result(false, "key", "key address unreadable: ${keyUri.take(80)}", 0)
                        val reply = get("key", target, null, 4096)
                        if (!reply.ok) return Result(false, "key", failure("key", reply), 0)
                        if (method == "AES-128" && reply.bytes.size != 16) {
                            return Result(false, "key", "key answered ${reply.status} with ${reply.bytes.size} bytes (an AES-128 key is 16)", 0)
                        }
                        keyNote = "key ok ($method) · "
                    } else {
                        keyNote = "key in the playlist ($method) · "
                    }
                }
            }
            val pieceRef = lines.firstOrNull { !it.startsWith("#") }
                ?: lines.firstOrNull { it.startsWith("#EXT-X-MAP") }?.let { attrs(it)["URI"] }
                ?: return Result(false, "piece", "${keyNote}playlist has no pieces", 0)
            val target = resolve(url, pieceRef) ?: return Result(false, "piece", "${keyNote}piece address unreadable: ${pieceRef.take(80)}", 0)
            return piece(target, keyNote, encrypted = keyNote.startsWith("key"))
        }

        private fun piece(target: String, prefix: String, encrypted: Boolean): Result {
            val reply = get("piece", target, PIECE_RANGE, PIECE_RANGE + 1)
            if (!reply.ok) return Result(false, "piece", prefix + failure("piece", reply), reply.bytes.size)
            if (reply.bytes.isEmpty()) return Result(false, "piece", "${prefix}piece ${reply.status} but empty", 0)
            val kind = kindOfBytes(reply.bytes, reply.contentType)
            if (kind == "web page") return Result(false, "piece", "${prefix}piece ${reply.status} is a web page, not video", reply.bytes.size)
            val what = when {
                kind != null -> kind
                encrypted -> "encrypted"
                else -> "unrecognised bytes"
            }
            return Result(true, "piece", "${prefix}piece ${reply.status} · ${reply.bytes.size} bytes ($what)", reply.bytes.size)
        }

        // ---------- DASH ----------

        private fun dash(url: String, text: String): Result {
            val doc = runCatching { Jsoup.parse(text, url, Parser.xmlParser()) }.getOrNull()
                ?: return Result(false, "playlist", "manifest isn't readable XML", 0)
            if (doc.getElementsByTag("ContentProtection").isNotEmpty()) {
                return Result(false, "playlist", "DRM (ContentProtection): can't be played", 0)
            }
            val mpd = doc.getElementsByTag("MPD").firstOrNull() ?: return Result(false, "playlist", "manifest has no MPD", 0)
            val reps = mpd.getElementsByTag("Representation")
            if (reps.isEmpty()) return Result(false, "quality", "manifest has no Representation", 0)
            val video = reps.filter { r -> mimeOf(r).startsWith("video") }.ifEmpty { reps.toList() }
            val rep = video.minBy { it.attr("bandwidth").toLongOrNull() ?: Long.MAX_VALUE }
            var base = url
            listOfNotNull(mpd, rep.parents().firstOrNull { it.tagName() == "Period" }, rep.parent(), rep).forEach { el ->
                el.children().firstOrNull { it.tagName() == "BaseURL" }?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { b ->
                    base = resolve(base, b) ?: base
                }
            }
            val prefix = "${video.size} qualit${if (video.size == 1) "y" else "ies"}" +
                (rep.attr("height").takeIf { it.isNotBlank() }?.let { " · lowest ${it}p" } ?: "") + " · "
            val template = rep.children().firstOrNull { it.tagName() == "SegmentTemplate" }
                ?: rep.parent()?.children()?.firstOrNull { it.tagName() == "SegmentTemplate" }
            val list = rep.children().firstOrNull { it.tagName() == "SegmentList" }
                ?: rep.parent()?.children()?.firstOrNull { it.tagName() == "SegmentList" }
            val segment: String? = when {
                template != null -> {
                    val media = template.attr("media")
                    if (media.isBlank()) {
                        template.attr("initialization").takeIf { it.isNotBlank() }?.let { fill(it, rep, number = 0, time = 0) }
                    } else {
                        val startNumber = template.attr("startNumber").toLongOrNull() ?: 1L
                        val time = template.getElementsByTag("S").firstOrNull()?.attr("t")?.toLongOrNull() ?: 0L
                        fill(media, rep, startNumber, time)
                    }
                }
                list != null -> list.getElementsByTag("SegmentURL").firstOrNull()?.attr("media")?.takeIf { it.isNotBlank() }
                    ?: list.getElementsByTag("Initialization").firstOrNull()?.attr("sourceURL")?.takeIf { it.isNotBlank() }
                else -> ""   // SegmentBase or a single file: the BaseURL itself
            }
            segment ?: return Result(false, "piece", "${prefix}no segment address in the manifest", 0)
            val target = (if (segment.isEmpty()) base else resolve(base, segment)) ?: return Result(false, "piece", "${prefix}segment address unreadable", 0)
            if (target == url) return Result(false, "piece", "${prefix}no segment address in the manifest", 0)
            return piece(target, prefix, encrypted = false)
        }

        private fun mimeOf(rep: Element): String =
            rep.attr("mimeType").ifBlank { rep.parent()?.attr("mimeType").orEmpty() }.ifBlank { rep.parent()?.attr("contentType").orEmpty() }

        private val TEMPLATE_VAR = Regex("""\$(RepresentationID|Number|Bandwidth|Time)(%0(\d+)d)?\$""")

        private fun fill(template: String, rep: Element, number: Long, time: Long): String =
            TEMPLATE_VAR.replace(template) { m ->
                val value = when (m.groupValues[1]) {
                    "RepresentationID" -> return@replace rep.attr("id")
                    "Number" -> number
                    "Bandwidth" -> rep.attr("bandwidth").toLongOrNull() ?: 0L
                    else -> time
                }
                val width = m.groupValues[3].toIntOrNull() ?: 0
                value.toString().padStart(width, '0')
            }.replace("$$", "$")

        // ---------- files ----------

        /**
         * KMK (r118): asks for the file once more without the app's cookies. A 401/403 then means the host wants the
         * site's cookies: the player gets them (plain files of Code editor sources play through the app's
         * connection since r118), but an outside player or a copied link won't. Empty when there's nothing to say.
         */
        private fun cookieNote(url: String): String {
            val httpUrl = url.toHttpUrlOrNull() ?: return ""
            val hasCookies = runCatching { Injekt.get<NetworkHelper>().cookieJar.loadForRequest(httpUrl).isNotEmpty() }
                .getOrDefault(false)
            if (!hasCookies) return ""
            val builder = Headers.Builder()
            sourceHeaders.forEach { (k, v) -> runCatching { builder.set(k, v) } }
            if (builder["User-Agent"] == null) {
                runCatching { builder.set("User-Agent", Injekt.get<NetworkHelper>().defaultUserAgentProvider()) }
            }
            builder.set("Range", "bytes=0-1023")
            val started = System.currentTimeMillis()
            val status = runCatching {
                client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
                    .newCall(Request.Builder().url(httpUrl).headers(builder.build()).get().build())
                    .execute().use { it.code }
            }.getOrNull()
            hops.add(Hop("file (no cookies)", url, status, System.currentTimeMillis() - started, 0, null))
            return if (status == 401 || status == 403) " · needs the site's cookies ($status without them; the app's player sends them)" else ""
        }

        private fun file(reply: Reply): Result {
            if (reply.bytes.isEmpty()) return Result(false, "file", "file ${reply.status} but empty", 0)
            val kind = kindOfBytes(reply.bytes, reply.contentType)
            return when {
                kind == "web page" -> Result(false, "file", "file ${reply.status} is a web page, not video", reply.bytes.size)
                kind != null -> Result(
                    true,
                    "file",
                    "file ${reply.status} · ${reply.bytes.size} bytes ($kind)" + cookieNote(reply.finalUrl),
                    reply.bytes.size,
                )
                else -> Result(false, "file", "file ${reply.status} but not a video (${reply.contentType ?: "no content type"})", reply.bytes.size)
            }
        }
    }

    /** "mp4", "mkv", "webm", "ts", "fmp4 piece", "video" (by content type), "web page" or null */
    internal fun kindOfBytes(b: ByteArray, contentType: String?): String? {
        fun at(i: Int, s: String) = b.size >= i + s.length && String(b, i, s.length, Charsets.ISO_8859_1) == s
        val head = String(b, 0, minOf(b.size, 64), Charsets.ISO_8859_1).trimStart().lowercase()
        return when {
            at(4, "ftyp") -> "mp4"
            at(4, "styp") || at(4, "moof") || at(4, "sidx") -> "fmp4 piece"
            at(4, "moov") -> "mp4"
            b.size >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() ->
                if (String(b, 0, minOf(b.size, 64), Charsets.ISO_8859_1).contains("webm")) "webm" else "mkv"
            b.isNotEmpty() && b[0] == 0x47.toByte() && (b.size < 189 || b[188] == 0x47.toByte()) -> "ts"
            b.size >= 4 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte() && b[3] == 0xBA.toByte() -> "mpeg"
            at(0, "ID3") -> "ts (ID3)"
            head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") || head.startsWith("<body") -> "web page"
            contentType?.startsWith("video/", ignoreCase = true) == true -> "video"
            contentType?.contains("mp2t", ignoreCase = true) == true -> "ts"
            else -> null
        }
    }
}
