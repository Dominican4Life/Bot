package eu.kanade.tachiyomi.jssource.lab.videodoctor

import android.content.Context
import android.webkit.CookieManager
import eu.kanade.tachiyomi.jssource.JsRuntime
import eu.kanade.tachiyomi.jssource.VideoFinder
import eu.kanade.tachiyomi.jssource.lab.StepId
import eu.kanade.tachiyomi.jssource.lab.TestRun
import eu.kanade.tachiyomi.jssource.lab.TestRuns
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * KMK (r115): Source Lab's "Why won't it play" for the Test Run's Video step.
 *
 * 1. The source's own way: `getVideoList(episode)` on the Lab's runtime (its `[net]` lines are the requests it
 *    made), then each returned video (one per server, max 4) is fetched with the source's headers; an HLS playlist
 *    is followed to its first quality, its key and its first piece, a DASH manifest is read for DRM.
 * 2. When nothing played: the hidden browser ([VideoFinder] with a trace) opens the episode page and presses Play;
 *    what the player asked for is replayed with the source's headers and with the browser's, so a request the
 *    site refuses to the source (401 / 403 / 404) but answers for the browser shows, with the headers that differ
 *    and the fewest of them that make it work.
 * 3. The episode page, the player frames and their own scripts are searched for a hidden address
 *    ([ScrambleScanner]: AES with the key in the page, base64, packed scripts) and for DRM (EME, licence calls).
 *
 * The four causes ([VideoCause]) are judged from that; several may apply. DRM is only named, never worked around.
 * Never throws for a site's failure (the steps say what happened); cancellation goes through.
 */
internal object VideoDoctor {

    private const val MAX_VIDEOS = 4
    private const val MAX_PAGES = 4
    private const val MAX_SCRIPTS = 6
    private const val MAX_BROWSER_STREAMS = 2
    private const val PAGE_BYTES = 768L * 1024
    private const val PLAYLIST_BYTES = 256L * 1024
    private const val SMALL_BYTES = 2048L
    private const val CALL_TIMEOUT_S = 12L

    // No bare braces (ICU regex)
    private val SCRIPT_SRC = Regex("""<script\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val LIB_SCRIPT = Regex(
        """jquery|bootstrap|analytics|gtag|googletag|recaptcha|hcaptcha|turnstile|cloudflare|fontawesome|polyfill|""" +
            """sentry|lazysizes|swiper|disqus|/wp-includes/""",
        RegexOption.IGNORE_CASE,
    )
    private val EME = Regex("""requestMediaKeySystemAccess|com\.widevine\.alpha|com\.microsoft\.playready|com\.apple\.fps""")
    private val LICENSE_LITERAL = Regex("""["'](https?://[^"'\s]*(?:license|widevine|playready|drm)[^"'\s]*)["']""", RegexOption.IGNORE_CASE)
    private val KEY_ATTR = Regex("""([A-Z-]+)=("[^"]*"|[^,]*)""")
    private val MEDIA_PATH = Regex("""\.(?:m3u8|mpd|mp4|mkv|webm|ts|m4s|key)(?:$|[/?])""", RegexOption.IGNORE_CASE)
    private val SEGMENT_PATH = Regex("""\.(?:ts|m4s|m4v|aac)$""", RegexOption.IGNORE_CASE)
    private val STREAM_PATH = Regex("""m3u8|\.mpd$|\.(?:mp4|mkv|webm)$""", RegexOption.IGNORE_CASE)

    private const val WIDEVINE = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    private const val PLAYREADY = "9a04f079-9840-4286-ab92-e65be0885f95"
    private const val FAIRPLAY = "94ce86fb-07ff-4f43-adb8-93d2fa968ca2"

    /** The headers compared between the two sides (and tried, in this order, for the fewest that work) */
    private val DIFF_NAMES = listOf("Referer", "Origin", "Cookie", "User-Agent")

    private val client: OkHttpClient by lazy {
        Injekt.get<NetworkHelper>().client.newBuilder()
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /** The episode the Test Run opened (the first item of its Episodes step), null when it opened none */
    fun episodeOf(run: TestRun?): String? =
        run?.steps?.firstOrNull { it.id == StepId.CHAPTERS }?.items?.firstOrNull()?.url?.takeIf { it.isNotBlank() }

    /**
     * Diagnoses [episodeUrl] of the source behind [rt] (the Lab's test runtime; its JS lock is taken by each call).
     * [baseUrl] makes a relative episode link absolute; [sourceHeaders] the source's headers (a Referer of the
     * base URL is added when it has none, like the Test Run). [onStep] gets each step in words as it happens (any
     * thread). Runs on IO; the hidden browser's WebViews run on the main thread inside [VideoFinder].
     */
    suspend fun diagnose(
        context: Context,
        rt: JsRuntime,
        episodeUrl: String,
        baseUrl: String,
        sourceHeaders: Map<String, String>,
        sourceKey: String? = null,
        timeoutMs: Long = 40_000L,
        onStep: (String) -> Unit = {},
    ): VideoDiagnosis = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val steps = ArrayList<String>()
        fun step(text: String) {
            steps.add(text)
            AppLog.add('D', "lab", "why won't it play: $text")
            runCatching { onStep(text) }
        }
        val requests = ArrayList<AskedRequest>()
        val userAgent = Injekt.get<NetworkHelper>().defaultUserAgentProvider()
        val base = LinkedHashMap<String, String>()
        sourceHeaders.forEach { (k, v) -> base[k] = v }
        if (base.keys.none { it.equals("Referer", ignoreCase = true) } && baseUrl.startsWith("http")) {
            base["Referer"] = baseUrl.trimEnd('/') + "/"
        }
        if (base.keys.none { it.equals("User-Agent", ignoreCase = true) }) base["User-Agent"] = userAgent

        // ---------- 1. the source's own way ----------
        val before = rt.console.size
        val listed: JsonElement? = try {
            rt.call("getVideoList", episodeUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            step("getVideoList failed: ${e.message ?: e.javaClass.simpleName}")
            null
        }
        val lines = rt.console.let { all -> if (all.size >= before) all.drop(before) else all }
        val net = lines.mapNotNull { TestRuns.parseNetLine(it) }.filter { it.url.startsWith("http") }
        net.forEach { r ->
            requests.add(
                AskedRequest(
                    url = r.url,
                    kind = kindOf(r.url, fallback = AskedKind.Page),
                    side = if (r.viaBrowser) AskedSide.SourceBrowser else AskedSide.Source,
                    status = r.status,
                    error = r.error,
                ),
            )
        }
        val videos = videosOf(listed)
        step("the source returned ${videos.size} video${if (videos.size == 1) "" else "s"} after ${net.size} requests")
        var sourcePlayed = false
        val drmSigns = ArrayList<DrmSign>()
        val byServer = videos.filter { it.url.startsWith("http") }.distinctBy { it.server ?: it.url }.take(MAX_VIDEOS)
        for (video in byServer) {
            val headers = merged(base, video.headers)
            val check = checkStream(video.url, headers, AskedSide.Source)
            requests.addAll(check.requests)
            check.drm?.let { drmSigns.add(it) }
            if (check.played) sourcePlayed = true
            step("${video.server ?: shortUrl(video.url)}: ${check.words}")
        }

        // ---------- 2. the hidden browser, when nothing played ----------
        val episodePage = absolute(episodeUrl, baseUrl)
            ?: net.firstOrNull { kindOf(it.url, AskedKind.Page) == AskedKind.Page && !it.viaBrowser }?.url
        var browserUsed = false
        var browserReason: String? = null
        var foundVideos: List<VideoFinder.Found> = emptyList()
        var frames: List<String> = emptyList()
        val caught = ArrayList<VideoFinder.Caught>()
        if (!sourcePlayed && episodePage != null) {
            browserUsed = true
            step("opening ${shortUrl(episodePage)} in the hidden browser and pressing Play")
            val trace = Collections.synchronizedList(ArrayList<VideoFinder.Caught>())
            val result = VideoFinder.find(
                context = context,
                pageUrl = episodePage,
                frame = null,
                referer = base.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value,
                timeoutMs = timeoutMs,
                sourceKey = sourceKey,
                fresh = true,
                trace = trace,
            )
            foundVideos = result.videos
            frames = result.frames
            browserReason = result.reason.takeIf { result.videos.isEmpty() }
            caught.addAll(synchronized(trace) { trace.toList() })
            step("the hidden browser: ${result.videos.size} videos, ${caught.size} requests noted (${result.reason})")
        } else if (!sourcePlayed) {
            step("no http address for the episode page: the hidden browser wasn't opened")
        }
        val framePaths = frames.map { ScrambleScanner.pathKey(it) }.toSet()
        caught.forEach { c ->
            val kind = when {
                ScrambleScanner.pathKey(c.url) in framePaths -> AskedKind.Page
                else -> when (c.kind) {
                    "page" -> AskedKind.Page
                    "stream" -> AskedKind.Stream
                    "key" -> AskedKind.Key
                    "segment" -> AskedKind.Segment
                    "license" -> AskedKind.License
                    else -> AskedKind.Other
                }
            }
            requests.add(AskedRequest(c.url, kind, AskedSide.Browser, c.status, c.headers))
            if (kind == AskedKind.License) {
                drmSigns.add(DrmSign(systemsIn(c.url).ifEmpty { listOf("DRM") }, c.url, "the player called a licence server"))
            }
        }
        // A video read off the player's <video> without a request
        foundVideos.forEach { v ->
            if (requests.none { it.side == AskedSide.Browser && it.url == v.url }) {
                requests.add(AskedRequest(v.url, AskedKind.Stream, AskedSide.Browser, null, v.headers))
            }
        }
        val browserPaths = (caught.map { it.url } + foundVideos.map { it.url }).map { ScrambleScanner.pathKey(it) }.toSet()

        // ---------- replays: what the site answers each side ----------
        val browserStreams = (foundVideos.map { it.url } + caught.filter { it.kind == "stream" }.map { it.url })
            .distinctBy { ScrambleScanner.pathKey(it) }
            .take(MAX_BROWSER_STREAMS)
        for (url in browserStreams) {
            val browserHeaders = browserHeadersFor(url, caught, foundVideos, episodePage)
            val asSource = checkStream(url, base, AskedSide.ReplaySource)
            val asBrowser = checkStream(url, browserHeaders, AskedSide.ReplayBrowser)
            requests.addAll(asSource.requests)
            requests.addAll(asBrowser.requests)
            asBrowser.drm?.let { drmSigns.add(it) }
            step("${shortUrl(url)}: with the source's headers ${asSource.words}; with the browser's ${asBrowser.words}")
        }
        // The source's own refused videos, with the browser's headers
        if (browserUsed) {
            for (video in byServer) {
                val refused = requests.any { it.side == AskedSide.Source && it.url == video.url && it.refused }
                if (!refused) continue
                val check = checkStream(video.url, browserHeadersFor(video.url, caught, foundVideos, episodePage), AskedSide.ReplayBrowser)
                requests.addAll(check.requests)
                step("${shortUrl(video.url)} with the browser's headers: ${check.words}")
            }
        }

        // A request refused to the source but answered for the browser
        val sourceSides = setOf(AskedSide.Source, AskedSide.ReplaySource)
        val blocked = requests.firstOrNull { r ->
            r.side in sourceSides && r.refused &&
                requests.any { b -> b.side == AskedSide.ReplayBrowser && b.url == r.url && b.ok }
        }
        var neededHeaders: Map<String, String>? = null
        var diffs: List<HeaderDiff> = emptyList()
        var headerLine: String? = null
        if (blocked != null) {
            val browserSent = requests.first { it.side == AskedSide.ReplayBrowser && it.url == blocked.url && it.ok }.headers
            diffs = headerDiffs(blocked.headers, browserSent)
            headerLine = headerLine(diffs)
            neededHeaders = fewestHeaders(blocked.url, base, browserSent)
            step(
                "${blocked.kind.words} ${shortUrl(blocked.url)}: ${blocked.status} for the source, answered for the browser" +
                    (neededHeaders?.let { " · needs ${it.keys.joinToString(" + ")}" } ?: ""),
            )
        }

        // ---------- 3. hidden addresses and DRM in the pages and scripts ----------
        val scrambled: Scrambled? = if (sourcePlayed) {
            null
        } else {
            val pages = (listOfNotNull(episodePage) + frames.filter { it.startsWith("http") } + net.filter { !it.viaBrowser && kindOf(it.url, AskedKind.Page) == AskedKind.Page }.map { it.url })
                .distinctBy { ScrambleScanner.pathKey(it) }
                .take(MAX_PAGES)
            val findings = ArrayList<Scrambled>()
            var scripts = 0
            for (page in pages) {
                val referer = if (page == episodePage) base["Referer"] else episodePage
                val pageReply = get(page, merged(base, mapOfNotNull("Referer" to referer)), PAGE_BYTES)
                val html = pageReply.text ?: continue
                findings.addAll(ScrambleScanner.scan(html, page, browserPaths))
                drmIn(html, page)?.let { drmSigns.add(it) }
                val pageUrl = pageReply.finalUrl?.toHttpUrlOrNull() ?: page.toHttpUrlOrNull() ?: continue
                val own = SCRIPT_SRC.findAll(html)
                    .mapNotNull { m -> pageUrl.resolve(m.groupValues[1].trim())?.toString() }
                    .filter { src -> !LIB_SCRIPT.containsMatchIn(src) && sameSite(src, pageUrl.host) }
                    .distinct()
                    .take(2)
                    .toList()
                for (src in own) {
                    if (scripts >= MAX_SCRIPTS) break
                    scripts++
                    val js = get(src, merged(base, mapOf("Referer" to page)), PAGE_BYTES).text ?: continue
                    findings.addAll(ScrambleScanner.scan(js, src, browserPaths))
                    drmIn(js, src)?.let { drmSigns.add(it) }
                }
            }
            val best = findings.sortedWith(compareByDescending<Scrambled> { it.confirmed }.thenBy { it.kind.ordinal }).firstOrNull()
            step(
                "searched ${pages.size} pages and $scripts scripts for a hidden address: " +
                    (best?.let { "${it.kind.words} → ${shortUrl(it.result)}" } ?: "none"),
            )
            best
        }
        val drm = drmSigns.firstOrNull { it.licenseUrl != null } ?: drmSigns.firstOrNull()
        drm?.let { step("DRM: ${it.systems.joinToString(", ")} (${it.via}); named only, never worked around") }

        // ---------- the verdicts ----------
        val browserAskedAnything = caught.any { it.kind == "stream" || it.kind == "segment" } || foundVideos.isNotEmpty()
        val sourceAskedAnything = byServer.isNotEmpty()
        val noStream = !sourcePlayed && browserUsed && !browserAskedAnything && !sourceAskedAnything &&
            scrambled == null && drm == null
        val scrambledHit = scrambled != null && !sourcePlayed
        val causes = listOf(
            CauseVerdict(
                VideoCause.NoStream,
                noStream,
                when {
                    noStream -> "The player never asked for a video." + (browserReason?.let { " ($it)" } ?: "")
                    sourcePlayed -> "The source's video played."
                    else -> "The player asked for a video."
                },
            ),
            CauseVerdict(
                VideoCause.ScrambledLink,
                scrambledHit,
                when {
                    scrambled != null && scrambledHit -> "The page hides the address with ${scrambled.kind.words}" +
                        (if (scrambled.key != null && scrambled.kind != ScrambleKind.Base64) "; the key is in the page." else ".")
                    else -> "No hidden address in the page."
                },
            ),
            CauseVerdict(
                VideoCause.BlockedRequest,
                blocked != null,
                if (blocked != null) {
                    val names = (neededHeaders?.keys ?: diffs.map { it.name }).toList()
                    "The video ${blocked.kind.words} answered ${blocked.status} to the source" +
                        (if (names.isNotEmpty()) "; it needs the site's ${names.joinToString(" + ")}." else ".")
                } else {
                    "No request was refused to the source."
                },
            ),
            CauseVerdict(
                VideoCause.Drm,
                drm != null,
                if (drm != null) {
                    "Needs a licence server (${drm.systems.joinToString(", ")}). Can't be played."
                } else {
                    "Needs a licence server. Can't be played; not this video."
                },
            ),
        )

        // ---------- the fixes and what Try play uses ----------
        val fixes = ArrayList<VideoFix>()
        if (scrambled != null && scrambledHit) {
            fixes.add(
                VideoFix.DecryptWithKey(
                    pattern = scrambled.pattern,
                    key = if (scrambled.kind == ScrambleKind.AesPassphrase || scrambled.kind == ScrambleKind.AesKey) scrambled.key.orEmpty() else "",
                    iv = scrambled.iv,
                    method = when (scrambled.kind) {
                        ScrambleKind.AesPassphrase -> "aes-passphrase"
                        ScrambleKind.AesKey -> "aes-key"
                        ScrambleKind.Base64 -> if (scrambled.key == "reversed") "base64-reversed" else "base64"
                        ScrambleKind.Packed -> "packed"
                    },
                    keyEncoding = if (scrambled.kind == ScrambleKind.AesKey && isHexKey(scrambled.key.orEmpty())) "hex" else "utf8",
                ),
            )
        }
        if (blocked != null) {
            val add = neededHeaders ?: requests.first { it.side == AskedSide.ReplayBrowser && it.url == blocked.url && it.ok }
                .headers.filterKeys { k -> DIFF_NAMES.any { it.equals(k, ignoreCase = true) } }
            if (add.isNotEmpty()) fixes.add(VideoFix.AddHeaders(add))
        }
        if (!sourcePlayed && foundVideos.isNotEmpty()) fixes.add(VideoFix.UseHiddenBrowser)

        val tryUrl = scrambled?.result?.takeIf { scrambledHit }
            ?: foundVideos.firstOrNull()?.url
            ?: caught.firstOrNull { it.kind == "stream" }?.url
        val tryHeaders: Map<String, String> = when {
            tryUrl == null -> emptyMap()
            neededHeaders != null -> merged(base, neededHeaders)
            else -> merged(
                base,
                browserHeadersFor(tryUrl, caught, foundVideos, episodePage)
                    .filterKeys { k -> DIFF_NAMES.any { it.equals(k, ignoreCase = true) } },
            )
        }

        VideoDiagnosis(
            episodeUrl = episodeUrl,
            playerPage = episodePage.takeIf { browserUsed },
            causes = causes,
            requests = requests.distinctBy { Triple(it.url, it.side, it.kind) },
            headerDiffs = diffs,
            headerLine = headerLine,
            scrambled = scrambled,
            drm = drm,
            fixes = fixes,
            tryUrl = tryUrl,
            tryHeaders = tryHeaders,
            sourcePlayed = sourcePlayed,
            browserUsed = browserUsed,
            browserReason = browserReason,
            steps = steps.toList(),
            tookMs = System.currentTimeMillis() - started,
        )
    }

    // ---------- one stream, followed ----------

    private class StreamCheck(val played: Boolean, val words: String, val requests: List<AskedRequest>, val drm: DrmSign?)

    /**
     * GETs [url] with [headers]: an HLS playlist is followed to its first quality, its key (AES-128) and its first
     * piece; a DASH manifest is read for ContentProtection; a file by its first bytes. Blocking (IO).
     */
    private fun checkStream(url: String, headers: Map<String, String>, side: AskedSide): StreamCheck {
        val out = ArrayList<AskedRequest>()
        val playlistLike = url.contains("m3u8", ignoreCase = true) || url.contains(".mpd", ignoreCase = true)
        val reply = get(url, headers, if (playlistLike) PLAYLIST_BYTES else SMALL_BYTES, range = !playlistLike)
        fun add(u: String, kind: AskedKind, r: Reply, detail: String? = null) {
            out.add(AskedRequest(u, kind, side, r.status, headers, detail, r.error))
        }
        if (!reply.ok) {
            add(url, AskedKind.Stream, reply)
            return StreamCheck(false, "${reply.status ?: reply.error ?: "no reply"}", out, null)
        }
        val text = reply.text.orEmpty()
        val baseUrl = (reply.finalUrl ?: url).toHttpUrlOrNull()
        return when {
            text.trimStart().startsWith("#EXTM3U") -> {
                var media = text
                var mediaBase = baseUrl
                val variants = text.lines().count { it.startsWith("#EXT-X-STREAM-INF") }
                add(url, AskedKind.Stream, reply, if (variants > 0) "$variants qualities" else "playlist")
                if (variants > 0) {
                    val first = firstUri(text, after = "#EXT-X-STREAM-INF")?.let { resolve(baseUrl, it) }
                    if (first != null) {
                        val variant = get(first, headers, PLAYLIST_BYTES)
                        add(first, AskedKind.Stream, variant, "first quality")
                        if (!variant.ok) return StreamCheck(false, "the first quality answered ${variant.status ?: variant.error}", out, null)
                        media = variant.text.orEmpty()
                        mediaBase = (variant.finalUrl ?: first).toHttpUrlOrNull()
                    }
                }
                val keyLine = media.lines().firstOrNull { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-SESSION-KEY") }
                    ?: text.lines().firstOrNull { it.startsWith("#EXT-X-SESSION-KEY") }
                val attrs = keyLine?.let { attrsOf(it.substringAfter(':')) }.orEmpty()
                val method = attrs["METHOD"]?.uppercase(Locale.US)
                val format = attrs["KEYFORMAT"]?.lowercase(Locale.US)
                val drm = when {
                    method == null || method == "NONE" -> null
                    method.startsWith("SAMPLE-AES") || (format != null && format != "identity") ->
                        DrmSign(systemsIn(format.orEmpty() + " " + attrs["URI"].orEmpty()).ifEmpty { listOf(method) }, attrs["URI"]?.takeIf { it.startsWith("http") }, "the playlist's key is $method${format?.let { " ($it)" } ?: ""}")
                    else -> null
                }
                var keyOk = true
                if (drm == null && method == "AES-128") {
                    val keyUri = attrs["URI"]?.let { resolve(mediaBase, it) }
                    if (keyUri != null && keyUri.startsWith("http")) {
                        val key = get(keyUri, headers, 64L)
                        add(keyUri, AskedKind.Key, key, "AES-128 key")
                        keyOk = key.ok
                    }
                }
                val segment = firstUri(media, after = "#EXTINF")?.let { resolve(mediaBase, it) }
                var segmentOk = media.lines().any { it.startsWith("#EXTINF") } && segment == null
                if (segment != null) {
                    val piece = get(segment, headers, 1024L, range = true)
                    add(segment, AskedKind.Segment, piece, "first piece")
                    segmentOk = piece.ok
                }
                val played = drm == null && keyOk && segmentOk
                val words = when {
                    drm != null -> "DRM (${drm.via})"
                    !keyOk -> "the key was refused"
                    !segmentOk -> "the first piece was refused"
                    else -> "played (HLS${if (variants > 0) ", $variants qualities" else ""})"
                }
                StreamCheck(played, words, out, drm)
            }
            text.contains("<MPD", ignoreCase = true) -> {
                val drm = dashDrm(text, url)
                add(url, AskedKind.Stream, reply, if (drm != null) "DASH · ContentProtection" else "DASH")
                StreamCheck(drm == null, if (drm != null) "DRM (${drm.systems.joinToString(", ")})" else "played (DASH)", out, drm)
            }
            isMediaFile(reply) -> {
                add(url, AskedKind.Stream, reply, "video file")
                StreamCheck(true, "played (a video file)", out, null)
            }
            else -> {
                add(url, AskedKind.Stream, reply, "not a video (${reply.contentType ?: "no content type"})")
                StreamCheck(false, "${reply.status} but not a playlist or a video (${reply.contentType ?: "no content type"})", out, null)
            }
        }
    }

    /** A 16 / 24 / 32-byte key written as hex (what [ScrambleScanner] tries as hex) */
    private fun isHexKey(key: String): Boolean =
        key.length in listOf(32, 48, 64) && key.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun firstUri(playlist: String, after: String): String? {
        val lines = playlist.lines().map { it.trim() }
        val at = lines.indexOfFirst { it.startsWith(after) }
        if (at < 0) return null
        for (i in at + 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty() || line.startsWith("#")) continue
            return line
        }
        return null
    }

    private fun attrsOf(list: String): Map<String, String> =
        KEY_ATTR.findAll(list).associate { m -> m.groupValues[1].uppercase(Locale.US) to m.groupValues[2].trim().trim('"') }

    /** The key systems a DASH manifest protects with, and its licence address when written (null: no DRM) */
    private fun dashDrm(mpd: String, url: String): DrmSign? {
        if (!mpd.contains("ContentProtection", ignoreCase = true)) return null
        val systems = systemsIn(mpd).ifEmpty { listOf("CENC") }
        val license = Regex("""<(?:dashif:)?laurl[^>]*>([^<]+)<""", RegexOption.IGNORE_CASE).find(mpd)?.groupValues?.get(1)?.trim()
            ?: Regex("""licenseUrl\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(mpd)?.groupValues?.get(1)?.trim()
        return DrmSign(systems, license, "the DASH manifest ${shortUrl(url)} has ContentProtection")
    }

    /** EME use in a page or script (null: none) */
    private fun drmIn(text: String, where: String): DrmSign? {
        if (!EME.containsMatchIn(text)) return null
        val license = LICENSE_LITERAL.find(text)?.groupValues?.get(1)
        return DrmSign(systemsIn(text).ifEmpty { listOf("EME") }, license, "${shortUrl(where)} uses encrypted media (EME)")
    }

    private fun systemsIn(text: String): List<String> {
        val lower = text.lowercase(Locale.US)
        return buildList {
            if ("widevine" in lower || WIDEVINE in lower) add("Widevine")
            if ("playready" in lower || PLAYREADY in lower) add("PlayReady")
            if ("fairplay" in lower || "com.apple.fps" in lower || "streamingkeydelivery" in lower || FAIRPLAY in lower) add("FairPlay")
        }
    }

    private fun isMediaFile(reply: Reply): Boolean {
        val b = reply.bytes
        if (b.size >= 8) {
            val box = String(b, 4, 4, Charsets.ISO_8859_1)
            if (box == "ftyp" || box == "moov" || box == "free" || box == "mdat") return true
        }
        if (b.size >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()) return true
        if (b.isNotEmpty() && b[0] == 0x47.toByte() && (b.size < 189 || b[188] == 0x47.toByte())) return true // MPEG-TS
        return reply.contentType?.startsWith("video/", ignoreCase = true) == true
    }

    // ---------- headers ----------

    /**
     * The headers the browser sent for [url] (its own request, else one to the same host, else the episode page
     * as Referer), with the browser's cookie for the address. Blocking (IO).
     */
    private fun browserHeadersFor(
        url: String,
        caught: List<VideoFinder.Caught>,
        found: List<VideoFinder.Found>,
        episodePage: String?,
    ): Map<String, String> {
        val host = url.toHttpUrlOrNull()?.host
        val path = ScrambleScanner.pathKey(url)
        val media = caught.filter { it.kind != "page" }
        val sent = media.firstOrNull { ScrambleScanner.pathKey(it.url) == path }?.headers
            ?: found.firstOrNull { ScrambleScanner.pathKey(it.url) == path }?.headers
            ?: media.firstOrNull { it.url.toHttpUrlOrNull()?.host == host }?.headers
            ?: found.firstOrNull()?.headers
            ?: mapOfNotNull("Referer" to episodePage)
        val out = LinkedHashMap<String, String>()
        sent.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true) && !k.equals("Accept-Encoding", ignoreCase = true)) out[k] = v
        }
        if (out.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { out["Cookie"] = it }
        }
        if (out.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            out["User-Agent"] = Injekt.get<NetworkHelper>().defaultUserAgentProvider()
        }
        return out
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

    private fun headerDiffs(source: Map<String, String>, browser: Map<String, String>): List<HeaderDiff> =
        DIFF_NAMES.mapNotNull { name ->
            val s = headerValue(source, name)
            val b = headerValue(browser, name)
            if (s == b || b == null) null else HeaderDiff(name, s, b)
        }

    /** "The browser sent Referer: https://example.tv/ and a cookie; the source sent neither." */
    private fun headerLine(diffs: List<HeaderDiff>): String? {
        if (diffs.isEmpty()) return null
        fun sent(d: HeaderDiff): String = when (d.name) {
            "Cookie" -> "a cookie"
            "User-Agent" -> "its own User-Agent"
            else -> "${d.name}: ${d.browser}"
        }
        val missing = diffs.filter { it.source == null }
        val changed = diffs.filter { it.source != null }
        val browserWords = joinWords(diffs.map(::sent))
        val sourceWords = when {
            changed.isEmpty() && missing.size == 1 -> "the source didn't"
            changed.isEmpty() && missing.size == 2 -> "the source sent neither"
            changed.isEmpty() -> "the source sent none of them"
            else -> "the source sent " + joinWords(
                changed.map { d -> if (d.name == "Cookie") "another cookie" else "${d.name}: ${d.source}" } +
                    missing.map { "no ${it.name}" },
            )
        }
        return "The browser sent $browserWords; $sourceWords."
    }

    private fun joinWords(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    /**
     * The fewest of the browser's Referer / Origin / Cookie / User-Agent (added in that order) on top of the
     * source's headers that make [url] answer; null when even all of them don't. Blocking (IO), at most 4 GETs.
     */
    private fun fewestHeaders(url: String, source: Map<String, String>, browser: Map<String, String>): Map<String, String>? {
        val picked = LinkedHashMap<String, String>()
        for (name in DIFF_NAMES) {
            val value = headerValue(browser, name) ?: continue
            if (headerValue(source, name) == value) continue
            picked[name] = value
            if (get(url, merged(source, picked), 64L, range = true).ok) return picked.toMap()
        }
        return null
    }

    /** [extra] over [base], names compared without case */
    private fun merged(base: Map<String, String>, extra: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        base.forEach { (k, v) -> if (extra.keys.none { it.equals(k, ignoreCase = true) }) out[k] = v }
        extra.forEach { (k, v) -> out[k] = v }
        return out
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
        pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    // ---------- network ----------

    private class Reply(
        val status: Int?,
        val bytes: ByteArray,
        val contentType: String?,
        val finalUrl: String?,
        val error: String?,
    ) {
        val ok: Boolean get() = status != null && status in 200..299
        val text: String? get() = if (ok) String(bytes, Charsets.UTF_8) else null
    }

    /** Blocking GET (IO only), at most [maxBytes] read; `Range` asks for just those bytes. Never throws */
    private fun get(url: String, headers: Map<String, String>, maxBytes: Long, range: Boolean = false): Reply {
        val request = try {
            Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> runCatching { header(k, v) } }
                if (range) header("Range", "bytes=0-${maxBytes - 1}")
            }.build()
        } catch (e: IllegalArgumentException) {
            return Reply(null, ByteArray(0), null, null, "not an address")
        }
        return try {
            client.newCall(request).execute().use { response ->
                val bytes = if (response.isSuccessful) {
                    runCatching { response.peekBody(maxBytes).bytes() }.getOrDefault(ByteArray(0))
                } else {
                    ByteArray(0)
                }
                Reply(response.code, bytes, response.header("Content-Type"), response.request.url.toString(), null)
            }
        } catch (e: Exception) {
            Reply(null, ByteArray(0), null, null, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- small helpers ----------

    private class SourceVideo(val url: String, val headers: Map<String, String>, val server: String?)

    private fun videosOf(element: JsonElement?): List<SourceVideo> =
        (element as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = o.text("url")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val headers = (o["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                ?.toMap().orEmpty()
            SourceVideo(url, headers, (o.text("server") ?: o.text("hoster"))?.trim()?.takeIf { it.isNotEmpty() })
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun kindOf(url: String, fallback: AskedKind): AskedKind {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?')
        return when {
            SEGMENT_PATH.containsMatchIn(path) -> AskedKind.Segment
            path.endsWith(".key", ignoreCase = true) -> AskedKind.Key
            STREAM_PATH.containsMatchIn(path) -> AskedKind.Stream
            MEDIA_PATH.containsMatchIn(path) -> AskedKind.Stream
            else -> fallback
        }
    }

    private fun absolute(link: String, baseUrl: String): String? {
        val trimmed = link.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val base = baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (trimmed.isEmpty()) return null
        return base.resolve(trimmed)?.toString()
    }

    private fun resolve(base: HttpUrl?, ref: String): String? =
        ref.toHttpUrlOrNull()?.toString() ?: base?.resolve(ref)?.toString()

    private fun sameSite(url: String, host: String): Boolean {
        val other = url.toHttpUrlOrNull()?.host ?: return false
        fun root(h: String) = h.lowercase(Locale.US).split('.').takeLast(2).joinToString(".")
        return root(other) == root(host)
    }

    /** host/…/file for the screen ("cdn.example.tv/…/master.m3u8") */
    fun shortUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.take(60)
        val parts = parsed.encodedPath.trim('/').split('/').filter { it.isNotEmpty() }
        val path = when {
            parts.isEmpty() -> ""
            parts.size == 1 -> "/" + parts[0]
            else -> "/…/" + parts.last()
        }
        return parsed.host.removePrefix("www.") + path
    }
}
