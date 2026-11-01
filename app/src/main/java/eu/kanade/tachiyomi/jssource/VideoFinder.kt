package eu.kanade.tachiyomi.jssource

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * KMK (r100): the shared video finder behind `host.findVideo(url, { frame, referer, timeout })`, which every
 * template's `getVideoList` calls (and the builder's "Test it now").
 *
 * Why: a player whose address the site's script fills in after the page loads (an `<iframe :src="…">` in Vue, no
 * `src` in the plain HTML) can't be found by parsing the page. So the episode page is opened in a hidden WebView
 * (like [JsRender]), Play is pressed, and the stream the player asks for (.m3u8 / .mpd / .mp4 / .mkv / .webm) is
 * caught from the requests of every frame. When nothing comes, each player frame found on the page (max 3) is
 * opened as the main page with the episode page as Referer, where Play can be pressed inside the player itself.
 *
 * Main thread only for the WebViews (each destroyed in `finally`); never throws for a site's failure: the
 * [Result.reason] says in plain words why nothing was caught. Every step is logged as `D video find <url>: …`.
 */
internal object VideoFinder {

    data class Found(
        val url: String,
        val kind: String, // "hls" | "dash" | "file"
        val quality: String,
        val server: String,
        val headers: Map<String, String>,
    )

    data class Result(
        val videos: List<Found>,
        val reason: String,
        val steps: List<String>,
        val frames: List<String>,
        val tookMs: Long,
    ) {
        /** The answer of the `findVideo` host call (turned into JSON by `toJsonElement`) */
        fun toMap(): Map<String, Any?> = mapOf(
            "videos" to videos.map { v ->
                mapOf(
                    "url" to v.url,
                    "kind" to v.kind,
                    "quality" to v.quality,
                    "server" to v.server,
                    "headers" to v.headers,
                )
            },
            "reason" to reason,
            "steps" to steps,
            "frames" to frames,
            "tookMs" to tookMs,
        )
    }

    private const val DEFAULT_TIMEOUT_MS = 45_000L
    private const val MIN_TIMEOUT_MS = 5_000L
    private const val MAX_TIMEOUT_MS = 120_000L
    private const val FIRST_PASS_SHARE = 0.45
    private const val POLL_MS = 250L
    private const val PLAY_EVERY_MS = 1_000L
    private const val SIBLINGS_MS = 1_500L
    private const val EVAL_LIMIT_MS = 3_000L
    private const val MAX_VIDEOS = 6
    private const val MAX_FRAMES = 3
    private const val MAX_REQUESTS = 5_000
    private const val VIEW_WIDTH = 1280
    private const val VIEW_HEIGHT = 720
    private val TAPS_AT_MS = longArrayOf(2_000L, 5_000L)

    // No braces in any of these (ICU regex: a bare brace throws at class init)
    // r102: plus the adult ad / traffic networks that put iframes over players (pnonolet, pemsrv, …): their frames
    // are ads, never the video, and opening them as "the player" in pass 2 wastes the budget. r102 fix: `vast` only as
    // its own path / host part (not "vastporn"/"canvas…"), no bare `syndication.` (googlesyndication is listed)
    private val AD = Regex(
        """doubleclick|googlesyndication|adservice|/ads/|[/._-]vast([/._?-]|$)|preroll|pnonolet|pemsrv|juicyads|exoclick|exosrv|""" +
            """trafficjunky|trafficstars|tsyndicate|adsterra|popads|propellerads|magsrv|realsrv|clickadu|hilltopads|""" +
            """adspyglass|ero-advertising|onclickads|a-ads\.com|adnxs""",
        RegexOption.IGNORE_CASE,
    )

    // r100 fix pass: matched against the address's PATH (not its query), after the script / style / page / picture
    // addresses are set aside (`m3u8-parser.min.js`, `thumbs/a.mp4.jpg`, `videos.mp4player.html` aren't videos)
    private val NOT_MEDIA = Regex("""\.(?:js|css|json|html?|jpe?g|png|webp|gif|svg|ico|woff2?)$""", RegexOption.IGNORE_CASE)
    private val SEGMENT_END = Regex("""\.(?:ts|m4s|m4v|aac)$""", RegexOption.IGNORE_CASE)
    private val SEGMENT_DIR = Regex("""/seg-""", RegexOption.IGNORE_CASE)
    private val HLS = Regex("""m3u8""", RegexOption.IGNORE_CASE)
    private val DASH = Regex("""\.mpd(?:/|$)""", RegexOption.IGNORE_CASE)
    private val FILE = Regex("""\.(?:mp4|mkv|webm)(?:/|$)""", RegexOption.IGNORE_CASE)

    // A playlist named in the query: a value that is the type itself (`type=m3u8`, `ext=m3u8`), or an address
    // ending in .m3u8 under an address-like key (`?url=…/master.m3u8`); never words inside free text (a beacon)
    private val HLS_VALUE = Regex("""^(?:m3u8|application/x-mpegurl|application/vnd\.apple\.mpegurl)$""", RegexOption.IGNORE_CASE)
    private val HLS_VALUE_URL = Regex("""\.m3u8$""", RegexOption.IGNORE_CASE)
    private val URL_KEYS = setOf("url", "u", "src", "file", "source", "stream", "link", "playlist", "v", "video", "hls", "manifest")

    // "ad" only as a word of the address (not inside "upload" / "head")
    private val PREVIEW = Regex("""preview|thumb|trailer|teaser|(?:^|[^a-z])ads?(?:[^a-z]|$)""", RegexOption.IGNORE_CASE)
    private val INIT_NAME = Regex("""^init""", RegexOption.IGNORE_CASE)
    private val MASTER_NAME = Regex("""master""", RegexOption.IGNORE_CASE)

    // r101: reading the caught HLS playlists (master or media, and the qualities a master lists)
    private const val PLAYLIST_TIMEOUT_S = 8L
    private const val PLAYLIST_MAX_BYTES = 256L * 1024
    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val NAME_ATTR = Regex("""(?:^|[,:])NAME="([^"]*)"""")
    private val URI_ATTR = Regex("""(?:^|[,:])URI="([^"]*)"""")

    // "720p" in a playlist's address (`/720p/index.m3u8`, `video_1080p.m3u8`, `?res=480p`); not "p" words
    private val HEIGHT_IN_URL = Regex("""(?<![0-9a-z])(\d\d\d\d?)p(?![a-z])""", RegexOption.IGNORE_CASE)

    private enum class Kind { HLS, DASH, FILE, SEGMENT }

    /**
     * One stream request: its address, kind, the Referer it was sent with, when, the address its pass loaded and
     * whether that pass opened a player frame itself (pass 2)
     */
    private class Hit(
        val url: String,
        val kind: Kind,
        val referer: String?,
        val atMs: Long,
        val passUrl: String,
        val framePass: Boolean,
    )

    /** What every WebView of one find asked for (shouldInterceptRequest runs off the main thread) */
    private class Capture {
        val hits: MutableList<Hit> = Collections.synchronizedList(ArrayList())
        val segments = AtomicInteger(0)
        val requests = AtomicInteger(0)

        /** Folders (host + path without the file name) the pieces of a stream came from */
        val segmentFolders: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /** Addresses of the `<video>` elements that are the player (in the frame selector, or the largest one) */
        val playerSrcs: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /** Hosts of the player frames (not the page's own site) and of the pages a player pass was sent on to */
        val playerHosts: MutableSet<String> = Collections.synchronizedSet(HashSet())

        @Volatile
        var segmentReferer: String? = null

        fun record(url: String, referer: String?, passUrl: String, framePass: Boolean) {
            if (requests.incrementAndGet() > MAX_REQUESTS) return
            val kind = classify(url) ?: return
            if (kind == Kind.SEGMENT) {
                segments.incrementAndGet()
                if (segmentFolders.size < 100) segmentFolders.add(folderOf(url))
                if (segmentReferer == null) segmentReferer = referer ?: passUrl
                return
            }
            hits.add(Hit(url.substringBefore('#'), kind, referer, System.currentTimeMillis(), passUrl, framePass))
        }

        /**
         * A hit that ends a pass: a playlist (HLS / DASH), or a plain file the player asked for (its address is the
         * player `<video>`'s, or its Referer is the player frame / the opened player's host). Other plain files
         * (hover previews of other titles, say) are only a fallback.
         */
        fun strong(hit: Hit): Boolean {
            if (hit.kind != Kind.FILE) return true
            if (hit.url in playerSrcs) return true
            val refererHost = hit.referer?.toHttpUrlOrNull()?.host?.lowercase()
            if (refererHost != null && refererHost in playerHosts) return true
            if (hit.framePass) {
                val passHost = hit.passUrl.toHttpUrlOrNull()?.host.orEmpty()
                return refererHost == null || sameSite(refererHost, passHost)
            }
            return false
        }

        fun count(): Int = hits.size

        fun strongCount(): Int = snapshot().count { strong(it) }

        fun snapshot(): List<Hit> = synchronized(hits) { hits.toList() }

        /** A `<video>` address read off the player: a strong hit even when no request showed it (cache) */
        fun addPlayerVideo(src: String, passUrl: String, framePass: Boolean) {
            if (!src.startsWith("http://") && !src.startsWith("https://")) return
            if (AD.containsMatchIn(src)) return
            val clean = src.substringBefore('#')
            val classified = classify(clean)
            if (classified == Kind.SEGMENT) return
            if (classified == null && isNotMedia(clean)) return
            val kind = classified ?: Kind.FILE // a player address without an extension
            playerSrcs.add(clean)
            if (snapshot().none { it.url == clean }) {
                hits.add(Hit(clean, kind, passUrl, System.currentTimeMillis(), passUrl, framePass))
            }
        }
    }

    /** What a request address is: a playlist, a file, a piece of a stream, or nothing for us (null) */
    private fun classify(url: String): Kind? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        if (AD.containsMatchIn(url)) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        val path = parsed.encodedPath
        if (NOT_MEDIA.containsMatchIn(path)) return null
        return when {
            SEGMENT_END.containsMatchIn(path) -> Kind.SEGMENT // index.m3u8.ts is a piece, not a playlist
            HLS.containsMatchIn(path) -> Kind.HLS
            SEGMENT_DIR.containsMatchIn(path) -> Kind.SEGMENT
            DASH.containsMatchIn(path) -> Kind.DASH
            FILE.containsMatchIn(path) -> Kind.FILE
            hlsInQuery(parsed) -> Kind.HLS
            else -> null
        }
    }

    private fun hlsInQuery(url: HttpUrl): Boolean {
        for (i in 0 until url.querySize) {
            val value = url.queryParameterValue(i) ?: continue
            if (HLS_VALUE.matches(value)) return true
            if (url.queryParameterName(i).lowercase() in URL_KEYS && HLS_VALUE_URL.containsMatchIn(value.substringBefore('?'))) {
                return true
            }
        }
        return false
    }

    private fun isNotMedia(url: String): Boolean =
        NOT_MEDIA.containsMatchIn(url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?'))

    /** host + path without the file name ("cdn.example.net/hls/abc") */
    private fun folderOf(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.substringBefore('?').substringBeforeLast('/')
        return parsed.host + parsed.encodedPath.substringBeforeLast('/')
    }

    private fun fileNameOf(url: String): String =
        (url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?')).substringAfterLast('/')

    /** What one pass saw */
    private class Pass(
        val loadMs: Long?,
        val error: String?,
        val frames: List<String>,
        val videoSources: List<String>,
        val matched: Int,
        val iframes: Int,
        val videoTags: Int,
    )

    suspend fun find(
        context: Context,
        pageUrl: String,
        frame: String?,
        referer: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        val started = System.currentTimeMillis()
        val steps = ArrayList<String>()
        val frames = ArrayList<String>()
        val url = pageUrl.trim()
        fun step(text: String) {
            steps.add(text)
            AppLog.add('D', "video", "find $url: $text")
        }
        fun done(videos: List<Found>, reason: String): Result {
            val result = Result(videos, reason, steps.toList(), frames.toList(), System.currentTimeMillis() - started)
            AppLog.add('I', "video", "find $url: ${videos.size} videos / $reason")
            return result
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return done(emptyList(), "the page didn't load (not an http(s) address: $url)")
        }
        if (!WebViewUtil.supportsWebView(context)) {
            return done(emptyList(), "the page didn't load (this device has no WebView)")
        }
        val timeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val frameSelector = frame?.trim()?.takeIf { it.isNotEmpty() }
        val pageReferer = referer?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val userAgent = Injekt.get<NetworkHelper>().defaultUserAgentProvider()
        val capture = Capture()
        val pageHost = url.toHttpUrlOrNull()?.host.orEmpty()

        return withContext(Dispatchers.Main) {
            // ---------- pass 1: the episode page ----------
            val first = pass(
                context = context,
                url = url,
                referer = pageReferer,
                frameSelector = frameSelector,
                budgetMs = (timeout * FIRST_PASS_SHARE).toLong(),
                userAgent = userAgent,
                capture = capture,
                framePass = false,
                step = ::step,
            )
            frames.addAll(first.frames)
            val frameWords = when (first.frames.size) {
                0 -> "no player frame"
                1 -> "1 player frame: ${label(first.frames[0])}"
                else -> "${first.frames.size} player frames: ${first.frames.joinToString(", ") { label(it) }}"
            }
            step(
                (first.loadMs?.let { "page loaded in ${seconds(it)} s" } ?: "page still loading after ${seconds(System.currentTimeMillis() - started)} s") +
                    " · $frameWords" +
                    (if (first.videoTags > 0) " · ${first.videoTags} <video>" else ""),
            )
            // The player <video>'s plain http address, also when no request showed it (served from cache, say)
            first.videoSources.forEach { src -> capture.addPlayerVideo(src, url, framePass = false) }
            // The first player frame of another site: the Referer of a request that came without one (pass 1)
            val playerFrame = first.frames.firstOrNull { f ->
                val host = f.toHttpUrlOrNull()?.host
                host != null && !sameSite(host, pageHost) && !AD.containsMatchIn(f)
            }
            if (capture.strongCount() > 0) {
                return@withContext done(build(capture, started, url, playerFrame, userAgent, ::step), "found")
            }
            if (first.error != null && first.frames.isEmpty() && first.videoTags == 0) {
                if (capture.count() > 0) {
                    return@withContext done(build(capture, started, url, playerFrame, userAgent, ::step), "found")
                }
                return@withContext done(emptyList(), "the page didn't load (${first.error})")
            }
            if (capture.count() > 0) {
                step("only plain video files outside the player so far (kept as a fallback); trying the player")
            }

            // ---------- pass 2: each player frame on its own, the page as Referer ----------
            val queue = ArrayList(first.frames.filter { usableFrame(it, url) }.take(MAX_FRAMES))
            var opened = 0
            var lastPlayer: String? = null
            while (opened < queue.size && opened < MAX_FRAMES) {
                val remaining = timeout - (System.currentTimeMillis() - started)
                val left = (queue.size.coerceAtMost(MAX_FRAMES) - opened).coerceAtLeast(1)
                val budget = remaining / left
                if (budget < 2_000L) {
                    step("no time left to open ${label(queue[opened])}")
                    break
                }
                val frameUrl = queue[opened]
                opened++
                lastPlayer = frameUrl
                step("opened the player itself (${label(frameUrl)}) with the page as Referer")
                val inner = pass(
                    context = context,
                    url = frameUrl,
                    referer = url,
                    frameSelector = null,
                    budgetMs = budget,
                    userAgent = userAgent,
                    capture = capture,
                    framePass = true,
                    step = ::step,
                )
                inner.videoSources.forEach { src -> capture.addPlayerVideo(src, frameUrl, framePass = true) }
                if (capture.strongCount() > 0) break
                if (inner.error != null) step("${label(frameUrl)} didn't load (${inner.error})")
                // A player that nests its real player one frame deeper
                inner.frames.filter { usableFrame(it, frameUrl) && it !in queue }.forEach { nested ->
                    if (queue.size < MAX_FRAMES) {
                        queue.add(nested)
                        frames.add(nested)
                        step("${label(frameUrl)} holds another player frame: ${label(nested)}")
                    }
                }
            }
            // A playlist / the player's file; else the plain files seen on the way (a fallback)
            if (capture.count() > 0) {
                return@withContext done(build(capture, started, url, playerFrame, userAgent, ::step), "found")
            }

            // ---------- nothing caught: why ----------
            val took = seconds(System.currentTimeMillis() - started)
            val reason = when {
                capture.segments.get() > 0 -> {
                    val host = hostWords(capture.segmentReferer ?: lastPlayer ?: url)
                    "the player at $host streams in pieces without a playlist address the app can use " +
                        "(${capture.segments.get()} pieces seen)"
                }
                first.frames.isEmpty() && first.videoTags == 0 && first.matched == 0 ->
                    "no player on the page (nothing matched \"${frameSelector ?: "iframe"}\", no <video> or <iframe>)"
                else -> {
                    val host = hostWords(lastPlayer ?: first.frames.firstOrNull() ?: url)
                    "the player at $host never asked for a video in $took s (it may need a real tap, or it blocks hidden browsers)"
                }
            }
            done(emptyList(), reason)
        }
    }

    /** Not a blank frame, not an ad, not the page itself */
    private fun usableFrame(frameUrl: String, pageUrl: String): Boolean =
        (frameUrl.startsWith("http://") || frameUrl.startsWith("https://")) &&
            !frameUrl.startsWith("about:") &&
            !AD.containsMatchIn(frameUrl) &&
            frameUrl.substringBefore('#') != pageUrl.substringBefore('#')

    /**
     * The caught streams as videos: the strong hits (playlists, the player's own file; the other plain files only
     * when nothing else came), deduped; next to a playlist the plain files that are pieces of it (`init.mp4`, a file
     * in a playlist's or a piece's folder) or previews are dropped; of a master playlist and its variants only the
     * master is kept (the app's `hls.expand` makes the quality list). HLS, then DASH, then files; max 6.
     * r101: the HLS playlists are read first ([inspect]) to know which is a master and what quality a media playlist
     * is ([realQualities]); the r100 guess by name / folder ([mastersOnly]) only when none could be read.
     */
    private suspend fun build(
        capture: Capture,
        started: Long,
        pageUrl: String,
        playerFrame: String?,
        userAgent: String,
        step: (String) -> Unit,
    ): List<Found> {
        val all = capture.snapshot().sortedBy { it.atMs }
        val strong = all.filter { capture.strong(it) }
        val pool = if (strong.isNotEmpty()) {
            if (strong.size < all.size) {
                AppLog.add('D', "video", "find $pageUrl: set aside ${all.size - strong.size} plain files outside the player")
            }
            strong
        } else {
            step("only plain video files outside the player were caught: kept as a fallback")
            all
        }
        val seen = HashSet<String>()
        val unique = pool.filter { seen.add(it.url) }

        val playlists = unique.filter { it.kind != Kind.FILE }
        val playlistFolders = playlists.map { folderOf(it.url) }.toSet()
        val pieceFolders = synchronized(capture.segmentFolders) { capture.segmentFolders.toSet() }
        var droppedFiles = 0
        val files = unique.filter { it.kind == Kind.FILE }.filter { hit ->
            val folder = folderOf(hit.url)
            val drop = playlists.isNotEmpty() && (
                PREVIEW.containsMatchIn(hit.url) ||
                    INIT_NAME.containsMatchIn(fileNameOf(hit.url)) ||
                    folder in pieceFolders ||
                    folder in playlistFolders
                )
            if (drop) droppedFiles++
            !drop
        }
        if (droppedFiles > 0) step("left out $droppedFiles plain files next to the playlist (previews, init, pieces)")
        val hlsAll = unique.filter { it.kind == Kind.HLS }
        // r101: each caught playlist is read (off the main thread) to tell a master (its qualities) from a media
        // playlist (one quality): a master's variants are left out, a media playlist gets its height as quality.
        // Before, a master + one of its variants in another folder came out as "Auto, Auto".
        val read = inspect(
            hlsAll.take(MAX_VIDEOS),
            hlsAll.take(MAX_VIDEOS).associate { it.url to headersOf(refererOf(it, playerFrame).first) },
            userAgent,
            pageUrl,
        )
        val hlsLabels = HashMap<String, String>()
        val hls = if (read.isEmpty()) {
            // None could be read: the r100 guess by file name / folder
            mastersOnly(hlsAll).also { kept ->
                if (kept.size < hlsAll.size) step("kept the master playlist, left out ${hlsAll.size - kept.size} of its variants")
            }
        } else {
            realQualities(hlsAll, read, hlsLabels, step)
        }
        val ordered = (hls + unique.filter { it.kind == Kind.DASH } + files).take(MAX_VIDEOS)

        val fallbackSaid = HashSet<String>()
        val found = ordered.map { hit ->
            val (refererUrl, which) = refererOf(hit, playerFrame)
            if (which != REFERER_SENT && fallbackSaid.add(which)) {
                AppLog.add('D', "video", "find $pageUrl: no Referer on ${fileNameOf(hit.url)}: used $which (${hostWords(refererUrl)})")
            }
            hit to Found(
                url = hit.url,
                kind = when (hit.kind) {
                    Kind.HLS -> "hls"
                    Kind.DASH -> "dash"
                    else -> "file"
                },
                quality = when (hit.kind) {
                    Kind.HLS -> hlsLabels[hit.url] ?: "Auto"
                    Kind.DASH -> "Auto (DASH)"
                    else -> "Direct"
                },
                server = label(refererUrl).ifBlank { label(pageUrl) },
                headers = headersOf(refererUrl),
            )
        }
        found.firstOrNull()?.let { (hit, f) ->
            val kindWords = when (f.kind) {
                "hls" -> "HLS"
                "dash" -> "DASH"
                else -> "a video file"
            }
            step("caught $kindWords from ${f.server} after ${seconds(hit.atMs - started)} s")
        }
        if (found.size > 1) step("${found.size} videos in all")
        return found.map { it.second }
    }

    private const val REFERER_SENT = "the request's Referer"

    /**
     * The Referer a video is played with, and where it came from: the request's own Referer; with none, the player's
     * address (the opened player in pass 2, the first player frame of another site in pass 1), else the page itself
     */
    private fun refererOf(hit: Hit, playerFrame: String?): Pair<String, String> {
        val sent = hit.referer?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return when {
            sent != null -> sent to REFERER_SENT
            hit.framePass -> hit.passUrl to "the opened player"
            playerFrame != null -> playerFrame to "the player frame"
            else -> hit.passUrl to "the episode page"
        }
    }

    private fun headersOf(refererUrl: String): Map<String, String> = buildMap {
        put("Referer", refererUrl)
        originOf(refererUrl)?.let { put("Origin", it) }
    }

    /** A quality / rendition a master playlist lists: its height (RESOLUTION) and NAME when given */
    private class Rendition(val height: Int?, val name: String?)

    /** A read playlist: a master (its variant / rendition addresses, absolute) or a media playlist (no parts) */
    private class Playlist(val master: Boolean, val qualities: Int, val parts: Map<String, Rendition>)

    /**
     * r101: reads every [hits] playlist in parallel on IO (the video's Referer / Origin + the WebView's User-Agent,
     * 8 s each). Answers the ones that are a master or a media playlist, by caught address; one that fails or isn't
     * a playlist is left out (kept as is by the caller).
     */
    private suspend fun inspect(
        hits: List<Hit>,
        headers: Map<String, Map<String, String>>,
        userAgent: String,
        pageUrl: String,
    ): Map<String, Playlist> {
        if (hits.isEmpty()) return emptyMap()
        val client = Injekt.get<NetworkHelper>().client.newBuilder()
            .callTimeout(PLAYLIST_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        val answers = withContext(Dispatchers.IO) {
            hits.map { hit ->
                async { hit.url to readPlaylist(client, hit.url, headers[hit.url].orEmpty(), userAgent, pageUrl) }
            }.awaitAll()
        }
        val out = LinkedHashMap<String, Playlist>()
        answers.forEach { (url, playlist) -> if (playlist != null) out[url] = playlist }
        return out
    }

    /** Blocking (IO only): GET [url] and read the start of it as a playlist; null when it fails or isn't one */
    private fun readPlaylist(
        client: OkHttpClient,
        url: String,
        sendHeaders: Map<String, String>,
        userAgent: String,
        pageUrl: String,
    ): Playlist? {
        val request = try {
            Request.Builder().url(url).apply {
                sendHeaders.forEach { (name, value) -> header(name, value) }
                header("User-Agent", userAgent)
            }.build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        val (text, finalUrl) = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.add('D', "video", "find $pageUrl: playlist ${tailOf(url)} answered ${response.code}, left as is")
                    return null
                }
                response.peekBody(PLAYLIST_MAX_BYTES).string() to response.request.url
            }
        } catch (e: Exception) {
            AppLog.add('D', "video", "find $pageUrl: couldn't read playlist ${tailOf(url)} (${e.message ?: e.javaClass.simpleName}), left as is")
            return null
        }
        return parsePlaylist(text, finalUrl)
    }

    /**
     * `#EXT-X-STREAM-INF` → a master: each variant's address (resolved against [base], the address after
     * redirects) with its RESOLUTION height and NAME; the I-frame and `#EXT-X-MEDIA` playlists (sound, subtitles)
     * it names are its parts too. `#EXTINF` → a media playlist. Else null.
     */
    private fun parsePlaylist(text: String, base: HttpUrl): Playlist? {
        val lines = text.lines().map { it.trim() }
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) {
            return if (lines.any { it.startsWith("#EXTINF") }) Playlist(master = false, qualities = 0, parts = emptyMap()) else null
        }
        val parts = LinkedHashMap<String, Rendition>()
        val qualityKeys = HashSet<String>()
        lines.forEachIndexed { index, line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    var next: String? = null
                    for (j in index + 1 until lines.size) {
                        val candidate = lines[j]
                        if (candidate.isEmpty() || candidate.startsWith("#")) continue
                        next = candidate
                        break
                    }
                    val absolute = next?.let { resolveRef(base, it) } ?: return@forEachIndexed
                    val height = RESOLUTION.find(line)?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 }
                    val name = NAME_ATTR.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    qualityKeys.add(height?.toString() ?: absolute)
                    if (absolute !in parts) parts[absolute] = Rendition(height, name)
                }
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") || line.startsWith("#EXT-X-MEDIA:") -> {
                    val absolute = URI_ATTR.find(line)?.groupValues?.get(1)?.let { resolveRef(base, it) } ?: return@forEachIndexed
                    if (absolute !in parts) {
                        parts[absolute] = Rendition(
                            RESOLUTION.find(line)?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 },
                            NAME_ATTR.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
                        )
                    }
                }
            }
        }
        return Playlist(master = true, qualities = qualityKeys.size, parts = parts)
    }

    private fun resolveRef(base: HttpUrl, ref: String): String? =
        ref.toHttpUrlOrNull()?.toString() ?: base.resolve(ref)?.toString()

    /** An address as OkHttp writes it, so a caught address and one resolved from a playlist compare equal */
    private fun normalUrl(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url

    /** host + path of a `.m3u8` address (the same playlist with another token in its query), else null */
    private fun playlistPathOf(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!parsed.encodedPath.endsWith(".m3u8", ignoreCase = true)) return null
        return parsed.host.lowercase() + parsed.encodedPath
    }

    /** The last two parts of an address's path ("720/index.m3u8"), for the log */
    private fun tailOf(url: String): String {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?')
        return path.trimEnd('/').split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/").ifEmpty { url }
    }

    /**
     * r101: of the caught HLS playlists ([hlsAll], in the order they were asked for), the ones to offer, with their
     * quality in [labels]: a caught address that a caught master lists (a variant, or its sound / subtitles) is
     * dropped; a master is "Auto" (the app splits it into qualities); a media playlist is the height the master
     * gives it, else a "720p" in its address, else "Auto". A playlist that couldn't be read is kept as is ("Auto").
     */
    private fun realQualities(
        hlsAll: List<Hit>,
        read: Map<String, Playlist>,
        labels: MutableMap<String, String>,
        step: (String) -> Unit,
    ): List<Hit> {
        val listed = HashMap<String, Rendition>()
        val listedPaths = HashMap<String, Rendition>()
        read.values.filter { it.master }.forEach { master ->
            master.parts.forEach { (address, rendition) ->
                listed.getOrPut(normalUrl(address)) { rendition }
                playlistPathOf(address)?.let { listedPaths.getOrPut(it) { rendition } }
            }
        }
        val kept = hlsAll.filter { hit ->
            if (read[hit.url]?.master == true) return@filter true
            val variant = normalUrl(hit.url) in listed || playlistPathOf(hit.url)?.let { it in listedPaths } == true
            if (variant) step("playlist ${tailOf(hit.url)}: dropped variant (its master playlist was caught too)")
            !variant
        }.let { list ->
            // The same playlist caught twice with another token ("Auto, Auto"): host + path, the first one kept
            val seen = HashSet<String>()
            list.filter { hit ->
                val first = seen.add(playlistPathOf(hit.url) ?: normalUrl(hit.url))
                if (!first) step("playlist ${tailOf(hit.url)}: dropped duplicate (same playlist, another token)")
                first
            }
        }
        kept.forEach { hit ->
            val playlist = read[hit.url] ?: return@forEach
            if (playlist.master) {
                labels[hit.url] = "Auto"
                step("playlist ${tailOf(hit.url)} is a master with ${playlist.qualities} qualities")
            } else {
                val rendition = listed[normalUrl(hit.url)] ?: playlistPathOf(hit.url)?.let { listedPaths[it] }
                val label = mediaLabel(hit.url, rendition)
                labels[hit.url] = label
                step("playlist ${tailOf(hit.url)} is a media playlist, labelled $label")
            }
        }
        return kept
    }

    /** A media playlist's quality: the master's RESOLUTION height, else its NAME, else "720p" in the address, else Auto */
    private fun mediaLabel(url: String, fromMaster: Rendition?): String {
        fromMaster?.height?.let { return "${it}p" }
        fromMaster?.name?.let { return it }
        HEIGHT_IN_URL.find(url)?.groupValues?.get(1)?.let { return "${it}p" }
        return "Auto"
    }

    /**
     * Of HLS playlists, one per folder, and none under a master playlist's folder (its variants): the masters (file
     * name with "master") come first, then the others in the order they were asked for
     */
    private fun mastersOnly(list: List<Hit>): List<Hit> {
        if (list.size < 2) return list
        val isMaster: (Hit) -> Boolean = { MASTER_NAME.containsMatchIn(fileNameOf(it.url)) }
        val masters = list.filter(isMaster)
        val masterFolders = masters.map { folderOf(it.url) }
        val folders = HashSet<String>()
        val kept = HashSet<Hit>()
        for (hit in masters + list.filterNot(isMaster)) {
            val folder = folderOf(hit.url)
            val variant = !isMaster(hit) && masterFolders.any { mf -> folder == mf || folder.startsWith("$mf/") }
            if (variant || !folders.add(folder)) continue
            kept.add(hit)
        }
        return list.filter { it in kept }
    }

    /**
     * Loads [url] in a fresh WebView for at most [budgetMs]: presses Play every second, taps the middle at 2 s and
     * 5 s, stops 1.5 s after the first stream; then reads the player frames and `<video>` addresses off the page.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun pass(
        context: Context,
        url: String,
        referer: String?,
        frameSelector: String?,
        budgetMs: Long,
        userAgent: String,
        capture: Capture,
        framePass: Boolean,
        step: (String) -> Unit,
    ): Pass {
        val loaded = CompletableDeferred<Unit>()
        val passHost = url.toHttpUrlOrNull()?.host.orEmpty()
        var mainError: String? = null
        var httpStatus: Int? = null
        // Navigation callbacks run on the main thread, like this loop: plain sets are enough
        val blocked = LinkedHashSet<String>()
        val followed = LinkedHashSet<String>()
        if (framePass && passHost.isNotEmpty()) capture.playerHosts.add(passHost.lowercase())
        val webView = WebView(context)
        try {
            webView.setDefaultSettings()
            webView.settings.userAgentString = userAgent
            webView.settings.blockNetworkImage = true
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (request == null) return null
                    val requested = request.url?.toString() ?: return null
                    val sentReferer = request.requestHeaders?.entries
                        ?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                    capture.record(requested, sentReferer, url, framePass)
                    return null
                }

                // r100 fix pass: the main frame may not jump to another site after a tap (an ad behind it, our
                // synthetic tap included), at any time; a jump by the page's own script (an embed host sending
                // the player on to its mirror) goes through
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request == null || !request.isForMainFrame) return false
                    val target = request.url ?: return false
                    val host = target.host ?: return false
                    if (sameSite(host, passHost)) return false
                    if (request.hasGesture()) {
                        if (blocked.add(host)) AppLog.add('D', "video", "find $url: blocked a tap's jump to $target")
                        return true
                    }
                    if (followed.add(host)) AppLog.add('D', "video", "find $url: followed the page's own redirect to $target")
                    if (framePass) capture.playerHosts.add(host.lowercase())
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.complete(Unit)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        mainError = error?.description?.toString()?.takeIf { it.isNotBlank() } ?: "error ${error?.errorCode}"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) httpStatus = errorResponse?.statusCode
                }
            }
            // A size, so the page lays out and a synthetic tap has somewhere to land
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)

            val before = capture.strongCount()
            val passStart = System.currentTimeMillis()
            if (referer != null) webView.loadUrl(url, mapOf("Referer" to referer)) else webView.loadUrl(url)

            var loadMs: Long? = null
            var lastPlay = passStart
            var tapIndex = 0
            var firstHitAt: Long? = null
            var pressedSaid = false
            var startedSaid = false
            while (true) {
                val now = System.currentTimeMillis()
                val elapsed = now - passStart
                if (loadMs == null && loaded.isCompleted) loadMs = elapsed
                // Only a playlist or the player's own file ends the pass (a hover preview doesn't)
                if (firstHitAt == null && capture.strongCount() > before) firstHitAt = now
                if (firstHitAt != null && now - firstHitAt >= SIBLINGS_MS) break
                if (elapsed >= budgetMs) break
                if (mainError != null && loaded.isCompleted) break
                if (now - lastPlay >= PLAY_EVERY_MS) {
                    lastPlay = now
                    val played = webView.evalJson(playScript(frameSelector))
                    played.strings("srcs").forEach { src ->
                        if (src.startsWith("http://") || src.startsWith("https://")) capture.playerSrcs.add(src.substringBefore('#'))
                    }
                    played.strings("frames").forEach { f ->
                        val host = f.toHttpUrlOrNull()?.host
                        if (host != null && !AD.containsMatchIn(f) && !sameSite(host, passHost)) capture.playerHosts.add(host.lowercase())
                    }
                    val clicked = played.str("clicked")
                    if (clicked != null && !pressedSaid) {
                        pressedSaid = true
                        step("pressed Play ($clicked) on ${label(url)}")
                    }
                    val startedVideos = played.long("started") ?: 0L
                    if (startedVideos > 0 && !startedSaid) {
                        startedSaid = true
                        step("started $startedVideos <video> muted on ${label(url)}")
                    }
                }
                if (tapIndex < TAPS_AT_MS.size && elapsed >= TAPS_AT_MS[tapIndex]) {
                    if (tapMiddle(webView) && tapIndex == 0) step("tapped the middle of the player on ${label(url)}")
                    tapIndex++
                }
                delay(POLL_MS)
            }
            if (blocked.isNotEmpty()) step("kept the page from jumping to ${blocked.take(3).joinToString(", ")} after a tap")
            if (followed.isNotEmpty()) step("followed the page's own redirect to ${followed.take(3).joinToString(", ")}")

            val dom = webView.evalJson(domScript(frameSelector))
            val error = mainError ?: httpStatus?.takeIf { it >= 400 }?.let { "the site answered $it" }
            return Pass(
                loadMs = loadMs,
                error = error,
                frames = dom.strings("frames").distinct(),
                videoSources = dom.strings("videos").filter { it.startsWith("http://") || it.startsWith("https://") }.distinct(),
                matched = dom.long("matched")?.toInt() ?: 0,
                iframes = dom.long("iframes")?.toInt() ?: 0,
                videoTags = dom.long("videoTags")?.toInt() ?: 0,
            )
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    /** ACTION_DOWN / ACTION_UP at the view's centre; best effort (an unattached view may ignore it) */
    private fun tapMiddle(view: WebView): Boolean = runCatching {
        val x = (if (view.width > 0) view.width else VIEW_WIDTH) / 2f
        val y = (if (view.height > 0) view.height else VIEW_HEIGHT) / 2f
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downAt, downAt + 60, MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        true
    }.getOrDefault(false)

    // ---------- small helpers ----------

    private fun seconds(ms: Long): String = String.format(Locale.US, "%.1f", ms.coerceAtLeast(0L) / 1000.0)

    /** The first label of an address's host ("host-5" for https://host-5.example.net/e/1), "www." skipped */
    private fun label(address: String): String {
        val host = address.toHttpUrlOrNull()?.host ?: return ""
        return host.removePrefix("www.").substringBefore('.')
    }

    /** The host of an address without "www.", or the address itself when it has none */
    private fun hostWords(address: String): String =
        address.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: address

    private fun originOf(address: String): String? {
        val parsed = address.toHttpUrlOrNull() ?: return null
        val port = if (parsed.port != HttpUrl.defaultPort(parsed.scheme)) ":${parsed.port}" else ""
        return "${parsed.scheme}://${parsed.host}$port"
    }

    /** Same site: equal hosts, or one a subdomain of the other's last two labels */
    private fun sameSite(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        fun base(h: String) = h.lowercase().split('.').takeLast(2).joinToString(".")
        return base(a) == base(b)
    }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    private suspend fun WebView.evalRaw(script: String): String? = withTimeoutOrNull(EVAL_LIMIT_MS) {
        suspendCancellableCoroutine<String?> { cont ->
            evaluateJavascript(script) { if (cont.isActive) cont.resume(it) }
        }
    }

    /** Runs a script that returns JSON.stringify(...) and reads it back */
    private suspend fun WebView.evalJson(script: String): JsonObject {
        val raw = evalRaw(script) ?: return JsonObject(emptyMap())
        return runCatching {
            val text = jsJson.parseToJsonElement(raw).jsonPrimitive.content
            jsJson.parseToJsonElement(text).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
    }

    /**
     * Mutes and plays the player's `<video>` (r100 fix pass: only inside the frame selector's elements, else the
     * largest `<video>` of the page, not every hover preview of other titles), and clicks the first visible play
     * button (inside the selector's elements and around their video, else anywhere) while it doesn't play
     * (clicking it again would pause). Answers `{ started, clicked, srcs, frames }`: the player video's addresses
     * and the player frames' addresses (the selector's iframes, else every iframe).
     */
    private fun playScript(selector: String?): String = """
        (function (sel) {
          var started = 0, playing = false, clicked = null, srcs = [], frames = [];
          function each(list, fn) { for (var i = 0; i < list.length; i++) fn(list[i]); }
          function abs(u) {
            if (!u) return null;
            try { return new URL(u, location.href).href; } catch (e) { return null; }
          }
          function addFrame(f) {
            var s = abs(f.src || f.getAttribute('src') || f.getAttribute('data-src'));
            if (s) frames.push(s);
          }
          function area(el) {
            var r = el.getBoundingClientRect();
            return Math.max(0, r.width) * Math.max(0, r.height);
          }
          var scope = [], targets = [], roots = [];
          if (sel) {
            try { scope = Array.prototype.slice.call(document.querySelectorAll(sel)); } catch (e) { scope = []; }
          }
          each(scope, function (el) {
            var tag = (el.tagName || '').toLowerCase();
            if (tag === 'video') { targets.push(el); return; }
            if (tag === 'iframe' || tag === 'embed' || tag === 'frame') { addFrame(el); return; }
            roots.push(el);
            each(el.querySelectorAll('video'), function (v) { targets.push(v); });
            each(el.querySelectorAll('iframe, embed'), addFrame);
          });
          if (!scope.length) {
            var best = null, bestArea = -1;
            each(document.querySelectorAll('video'), function (v) {
              var a = area(v);
              if (a > bestArea) { best = v; bestArea = a; }
            });
            if (best) targets.push(best);
            each(document.querySelectorAll('iframe'), addFrame);
          }
          try {
            each(targets, function (v) {
              var s = v.currentSrc || v.src || v.getAttribute('src') || '';
              if (/^https?:/i.test(s)) srcs.push(s);
              each(v.querySelectorAll('source'), function (so) {
                var ss = abs(so.getAttribute('src'));
                if (ss && /^https?:/i.test(ss)) srcs.push(ss);
              });
              if (!v.paused && !v.ended) { playing = true; return; }
              try {
                v.muted = true;
                var p = v.play();
                if (p && p.catch) p.catch(function () {});
                started++;
              } catch (e) {}
            });
          } catch (e) {}
          // The play buttons: inside the selector's elements and around their <video> (its controls sit next to
          // it); with no selector match, anywhere on the page (a player that makes its <video> only after Play)
          if (scope.length) {
            each(targets, function (v) {
              var box = v.parentElement && (v.parentElement.parentElement || v.parentElement);
              if (box) roots.push(box);
            });
          } else {
            roots.push(document);
          }
          if (!playing) {
            var selectors = ['.vjs-big-play-button', '.jw-display-icon-container', '.jw-icon-display',
              '.plyr__control--overlaid', '.play-button', 'button[aria-label*="play" i]',
              '[class*="play" i][role=button]', '#play', '.fp-play'];
            for (var s = 0; s < selectors.length && !clicked; s++) {
              for (var r = 0; r < roots.length && !clicked; r++) {
                var found = [];
                try { found = roots[r].querySelectorAll(selectors[s]); } catch (e) { found = []; }
                for (var j = 0; j < found.length; j++) {
                  var el = found[j];
                  var rect = el.getBoundingClientRect();
                  var style = window.getComputedStyle(el);
                  if (rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none') {
                    try { el.click(); clicked = selectors[s]; } catch (e) {}
                    break;
                  }
                }
              }
            }
          }
          return JSON.stringify({ started: started, clicked: clicked, srcs: srcs, frames: frames });
        })(${selector?.jsQuoted() ?: "null"})
    """.trimIndent()

    /**
     * The player frames (the [selector]'s elements, or every iframe when it is blank or matched nothing; a matched
     * container gives the frames inside it) as absolute addresses, and every `<video>`'s address.
     */
    private fun domScript(selector: String?): String = """
        (function (sel) {
          function abs(u) {
            if (!u) return null;
            try { return new URL(u, location.href).href; } catch (e) { return null; }
          }
          var frames = [], videos = [], matched = 0, list = [];
          if (sel) {
            try { list = Array.prototype.slice.call(document.querySelectorAll(sel)); } catch (e) { list = []; }
          }
          matched = list.length;
          if (!list.length) list = Array.prototype.slice.call(document.querySelectorAll('iframe'));
          function addVideo(v) {
            var s = v.currentSrc || v.src || v.getAttribute('src') || '';
            if (/^https?:/i.test(s)) videos.push(s);
            var sources = v.querySelectorAll('source');
            for (var k = 0; k < sources.length; k++) {
              var ss = abs(sources[k].getAttribute('src'));
              if (ss && /^https?:/i.test(ss)) videos.push(ss);
            }
          }
          function addFrame(f) {
            var s = abs(f.src || f.getAttribute('src') || f.getAttribute('data-src'));
            if (s && /^https?:/i.test(s)) frames.push(s);
          }
          list.forEach(function (el) {
            var tag = (el.tagName || '').toLowerCase();
            if (tag === 'iframe' || tag === 'embed' || tag === 'frame') { addFrame(el); return; }
            if (tag === 'video') { addVideo(el); return; }
            if (tag === 'source') { var s = abs(el.getAttribute('src')); if (s) videos.push(s); return; }
            var inner = el.querySelectorAll('iframe, embed');
            for (var i = 0; i < inner.length; i++) addFrame(inner[i]);
          });
          // r100 fix pass: only the player's <video> (inside the selector's elements, else the largest one)
          var allVideos = document.querySelectorAll('video');
          if (matched) {
            list.forEach(function (el) {
              var tag = (el.tagName || '').toLowerCase();
              if (tag === 'video' || tag === 'iframe' || tag === 'embed' || tag === 'frame' || tag === 'source') return;
              var inner = el.querySelectorAll('video');
              for (var q = 0; q < inner.length; q++) addVideo(inner[q]);
            });
          } else {
            var best = null, bestArea = -1;
            for (var n = 0; n < allVideos.length; n++) {
              var rr = allVideos[n].getBoundingClientRect();
              var a = Math.max(0, rr.width) * Math.max(0, rr.height);
              if (a > bestArea) { best = allVideos[n]; bestArea = a; }
            }
            if (best) addVideo(best);
          }
          return JSON.stringify({
            frames: frames,
            videos: videos,
            matched: matched,
            iframes: document.querySelectorAll('iframe').length,
            videoTags: allVideos.length
          });
        })(${selector?.jsQuoted() ?: "null"})
    """.trimIndent()
}
