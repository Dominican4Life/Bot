package eu.kanade.tachiyomi.data.download.video

import android.app.Application
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.StatisticsCallback
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.anime.AnimePrefs
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * KMK (r87): faster video downloads, for anime episodes and videos in manga chapters.
 *
 * Given a stream link, ffmpeg fetched an HLS stream one piece at a time (about 1 s each, so an episode took
 * minutes). Here the pieces (and the init piece and AES-128 keys) are fetched [connections] at a time into
 * `cache/video_pieces/<key>/`, local copies of the playlists are written that point at them, and ffmpeg joins them
 * from the local copy with the same output options as before (`-map 0:v? -map 0:a? -map 0:s? -c copy`, Matroska).
 * Pieces already there are kept, so a retry continues where it stopped.
 *
 * A plain file (mp4…) whose server answers a one-byte `Range` request with 206 is fetched in [connections] parts
 * written at their place in one file.
 *
 * Whatever this can't do returns false (logged as W "download"), and the caller saves the video the old way.
 * Setting: "Connections per download" ([AnimePrefs.videoDownloadConnections]), 1 = always the old way.
 */
object ParallelVideoDownload {

    const val MAX_CONNECTIONS = 8
    private const val TAG = "download"
    private const val PIECES_DIR = "video_pieces"
    private const val MANIFEST = "manifest.txt"
    private const val TRIES = 4
    private const val BUFFER = 64 * 1024
    private const val MAX_PLAYLIST_BYTES = 16L * 1024 * 1024
    private const val MIN_SPLIT_BYTES = 4L * 1024 * 1024
    private const val KEEP_PIECES_MS = 3L * 24 * 60 * 60 * 1000
    private const val MAX_RETRY_LOGS = 10
    private const val SLOW_DOWN_GAP_MS = 2_000L

    /** Piece names ffmpeg's HLS reader accepts (it checks them); anything else is saved as `.ts` */
    private val PIECE_EXTENSIONS = setOf(
        "3gp", "aac", "avi", "ac3", "eac3", "flac", "mkv", "m4a", "m4s", "m4v", "mpg", "mov", "mp2", "mp3", "mp4",
        "mpeg", "mpegts", "ogg", "ogv", "oga", "ts", "vob", "vtt", "wav", "webvtt",
    )

    /** Tags of low-latency live lists, which a finished (ENDLIST) list doesn't need */
    private val DROPPED_TAGS = listOf(
        "#EXT-X-PART:", "#EXT-X-PART-INF", "#EXT-X-PRELOAD-HINT", "#EXT-X-RENDITION-REPORT", "#EXT-X-SERVER-CONTROL",
    )

    private val context: Application by lazy { Injekt.get<Application>() }

    /** Connections per download, 1..[MAX_CONNECTIONS] */
    fun connections(): Int = AnimePrefs.videoDownloadConnections().get().coerceIn(1, MAX_CONNECTIONS)

    // OkHttp's default allows 5 calls at once per host
    private val dispatcher: Dispatcher by lazy {
        Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
    }

    /**
     * The app's network setup (cookies, DNS with IPv4 first / DNS over HTTPS, connections, User-Agent) without the
     * per-request "net" log line (one per piece would push everything else out of Logs) and without the 120 s
     * limit per call.
     */
    private val hlsClient: OkHttpClient by lazy {
        val network = Injekt.get<NetworkHelper>()
        val base = network.client
        OkHttpClient.Builder()
            .cookieJar(network.cookieJar)
            .dns(base.dns)
            .connectionPool(base.connectionPool)
            .dispatcher(dispatcher)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .addInterceptor(UserAgentInterceptor(network::defaultUserAgentProvider))
            .build()
    }

    /** Something this downloader doesn't handle: the caller saves the video the old way */
    private class Unsupported(message: String) : Exception(message)

    private class HttpStatus(val code: Int, val retryAfterMs: Long?) : IOException("HTTP $code")

    /**
     * KMK (r88): [isKey] = an AES-128 key; [checked] = its first bytes must look like video or sound (not a piece
     * under a key, which only ffmpeg decrypts, nor a subtitle)
     */
    private class Piece(val url: String, val file: String, val isKey: Boolean = false, val checked: Boolean = false)

    /** Lowers the number of connections when a server answers 429/503 */
    private class Gate(start: Int) {
        private val current = AtomicInteger(start)
        private val lastChange = AtomicLong(0L)
        val limit: Int get() = current.get()

        /** The new limit, or null when it stays (already 1, or lowered a moment ago) */
        fun slowDown(): Int? {
            val now = System.currentTimeMillis()
            val last = lastChange.get()
            if (now - last < SLOW_DOWN_GAP_MS || !lastChange.compareAndSet(last, now)) return null
            val before = current.getAndUpdate { maxOf(1, it - 1) }
            return if (before > 1) before - 1 else null
        }
    }

    /** One download's log label and counters */
    private class Run(val label: String, val gate: Gate?) {
        val retries = AtomicInteger(0)
    }

    // HLS

    /**
     * Saves the HLS stream of [video] (one quality, already picked with HlsVariants.forDownload) into [out] as
     * Matroska. [key] names its pieces folder, so a retry of the same download reuses what is there. False when it
     * couldn't (logged); the caller then saves it the old way. Throws only on cancellation.
     */
    suspend fun saveHls(
        video: Video,
        headers: Headers,
        out: File,
        key: String,
        label: String,
        onPercent: (Int) -> Unit,
    ): Boolean = withIOContext {
        val connections = connections()
        if (connections <= 1) {
            AppLog.add('I', TAG, "$label: 1 connection set, ffmpeg reads the stream itself")
            return@withIOContext false
        }
        if (video.ffmpegStreamArgs.isNotEmpty()) {
            AppLog.add('I', TAG, "$label: the source gives ffmpeg options for this stream, ffmpeg reads it itself")
            return@withIOContext false
        }
        val root = File(context.cacheDir, PIECES_DIR)
        val dir = File(root, safeKey(key))
        val started = System.currentTimeMillis()
        val done = AtomicInteger(0)
        var total = 0
        try {
            cleanOld(root, dir)
            val run = Run(label, Gate(connections))
            val pieces = mutableListOf<Piece>()
            val plan = plan(video, headers, pieces, run)
            total = pieces.size

            // Pieces from an earlier try of the same stream are kept; another stream starts afresh
            val fingerprint = fingerprint(pieces)
            val manifest = File(dir, MANIFEST)
            val sameStream = dir.exists() && runCatching { manifest.readText() }.getOrNull() == fingerprint
            if (dir.exists() && !sameStream) dir.deleteRecursively()
            dir.mkdirs()
            manifest.writeText(fingerprint)
            plan.files.forEach { (name, text) -> File(dir, name).writeText(text) }
            // KMK (r88): keys can be given per session; pieces saved under an older key can't be decrypted with
            // the new one, so a changed key starts afresh
            if (sameStream && keysChanged(pieces, headers, dir, run)) {
                AppLog.add('I', TAG, "$label: the stream's keys changed since the last try, starting afresh")
                dir.deleteRecursively()
                dir.mkdirs()
                manifest.writeText(fingerprint)
                plan.files.forEach { (name, text) -> File(dir, name).writeText(text) }
            }
            val present = pieces.count { File(dir, it.file).length() > 0 }
            done.set(present)

            AppLog.add(
                'I',
                TAG,
                "$label: fast download of ${plan.quality}: $total pieces" +
                    (if (plan.lists > 1) " in ${plan.lists} lists" else "") + ", $connections connections",
            )
            if (present > 0) AppLog.add('I', TAG, "$label: resuming, $present of $total pieces already here")
            onPercent(present * 95 / total)

            val fetched = fetchPieces(pieces, headers, dir, connections, run, done, started, onPercent)

            val joinStart = System.currentTimeMillis()
            AppLog.add('I', TAG, "$label: joining $total pieces with ffmpeg (${formatSeconds(plan.seconds)} of video)")
            join(File(dir, plan.entry), out, plan.seconds, onPercent)
            if (!out.exists() || out.length() == 0L) throw Unsupported("ffmpeg saved nothing from the pieces")
            AppLog.add('I', TAG, "$label: joined in ${secondsSince(joinStart)} s")

            val bytes = pieces.sumOf { File(dir, it.file).length() }
            val seconds = secondsSince(started)
            // KMK (r88): the speed counts only what this try downloaded (pieces kept from an earlier try made it
            // look faster); the whole size is shown apart
            AppLog.add(
                'I',
                TAG,
                "Fast download: $total pieces with $connections connections in ${seconds}s " +
                    "(${formatSize(fetched)} downloaded at ${speed(fetched, started)}" +
                    (if (present > 0) ", $present pieces from the last try" else "") +
                    ", ${formatSize(bytes)} in all" +
                    (if (run.retries.get() > 0) ", ${run.retries.get()} retries" else "") + "): $label",
            )
            dir.deleteRecursively()
            onPercent(99)
            true
        } catch (e: CancellationException) {
            // KMK (r88): kept only while the download is still queued (stopped by the system); cancel, delete and
            // a failed download delete them (discard)
            AppLog.add('I', TAG, "$label: stopped at ${done.get()} of $total pieces")
            throw e
        } catch (e: Exception) {
            val reason = if (e is Unsupported) e.message else "${e.javaClass.simpleName}: ${e.message}"
            AppLog.add('W', TAG, "$label: fast download didn't work ($reason), ffmpeg reads the stream itself")
            false
        }
    }

    /**
     * Deletes the pieces kept for [key] (after the old way saved the video instead, or when the download was
     * cancelled, deleted or failed). KMK (r88): with a [label], says how much was deleted.
     */
    fun discard(key: String, label: String? = null) {
        runCatching {
            val dir = File(File(context.cacheDir, PIECES_DIR), safeKey(key))
            if (!dir.exists()) return@runCatching
            val size = dir.listFiles()?.sumOf { it.length() } ?: 0L
            dir.deleteRecursively()
            if (label != null && size > 0) {
                AppLog.add('I', TAG, "$label: deleted ${formatSize(size)} of saved pieces")
            }
        }
    }

    /**
     * KMK (r88): deletes kept pieces nobody will continue: any older than a few days, and (with [prefix]) every
     * folder of that kind of download whose key isn't in [keep]. Called at app start and when the anime queue is
     * done; a cancelled or failed download used to leave its pieces (up to GBs) until the same one ran again.
     */
    fun cleanUp(prefix: String? = null, keep: Collection<String> = emptyList()) {
        runCatching {
            val root = File(context.cacheDir, PIECES_DIR)
            val kept = keep.mapTo(HashSet()) { safeKey(it) }
            val now = System.currentTimeMillis()
            var removed = 0
            root.listFiles()?.forEach { dir ->
                val unused = prefix != null && dir.name.startsWith(safeKey(prefix)) && dir.name !in kept
                if (unused || now - dir.lastModified() > KEEP_PIECES_MS) {
                    dir.deleteRecursively()
                    removed++
                }
            }
            if (removed > 0) AppLog.add('I', TAG, "Deleted the saved pieces of $removed unfinished downloads")
        }
    }

    /**
     * KMK (r88): a progress callback that never steps back. Pieces finish out of order on several connections,
     * and a fallback to another way (or another video) starts again from 0.
     */
    fun monotonic(onPercent: (Int) -> Unit): (Int) -> Unit {
        val lock = Any()
        var highest = -1
        return { percent ->
            val value = percent.coerceIn(0, 100)
            synchronized(lock) {
                val higher = value > highest
                if (higher) {
                    highest = value
                    onPercent(value)
                }
                higher
            }
            Unit
        }
    }

    private class Plan(
        /** Local playlist ffmpeg opens */
        val entry: String,
        /** Local playlists to write: name to text */
        val files: List<Pair<String, String>>,
        val seconds: Double,
        val quality: String,
        val lists: Int,
    )

    private class Variant(val line: String, val url: String, val height: Int?, val bandwidth: Int?)

    private suspend fun plan(video: Video, headers: Headers, pieces: MutableList<Piece>, run: Run): Plan {
        val (text, finalUrl) = withRetries(run, "the playlist") { fetchText(video.videoUrl, headers) }
        if (!text.trimStart().startsWith("#EXTM3U")) throw Unsupported("not an HLS playlist")
        val shared = mutableMapOf<String, String>()
        val lines = text.lines().map { it.trim() }

        if (lines.none { it.startsWith("#EXT-X-STREAM-INF:") }) {
            val (local, seconds) = localMedia("v", text, finalUrl, shared, pieces)
            // KMK (r88): the height and bitrate only when the title doesn't already say them ("1080p, 1080p, …")
            val title = video.videoTitle.trim()
            val quality = listOfNotNull(
                title.ifBlank { null },
                video.resolution?.let { "${it}p" }?.takeIf { !title.contains(it, ignoreCase = true) },
                video.bitrate?.let { mbps(it) }?.takeIf { !title.contains("Mbps", ignoreCase = true) },
            ).joinToString(", ").ifBlank { "one quality" }
            return Plan("v.m3u8", listOf("v.m3u8" to local), seconds, quality, 1)
        }

        // A list of qualities (HlsVariants leaves it whole when the sound comes separately): the best one, with
        // the sound and subtitle lists it uses
        val variants = lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapIndexedNotNull null
            val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return@mapIndexedNotNull null
            val url = resolve(finalUrl, uri) ?: return@mapIndexedNotNull null
            val height = attr(line, "RESOLUTION")?.substringAfter('x')?.toIntOrNull()
            Variant(line, url, height, attr(line, "BANDWIDTH")?.toIntOrNull())
        }
        val best = variants.maxWithOrNull(
            compareBy<Variant> { it.height ?: 0 }.thenBy { it.bandwidth ?: 0 },
        ) ?: throw Unsupported("a quality list without qualities")
        if (attr(best.line, "VIDEO") != null) throw Unsupported("separate picture lists")
        val audioGroup = attr(best.line, "AUDIO")
        val subtitleGroup = attr(best.line, "SUBTITLES")

        val files = mutableListOf<Pair<String, String>>()
        val masterLines = mutableListOf("#EXTM3U")
        lines.filter { it.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") || it.startsWith("#EXT-X-VERSION") }
            .forEach { masterLines += it }
        var renditions = 0
        lines.filter { it.startsWith("#EXT-X-MEDIA:") }.forEach { line ->
            val type = attr(line, "TYPE")?.uppercase()
            val group = attr(line, "GROUP-ID")
            val used = (type == "AUDIO" && group == audioGroup && audioGroup != null) ||
                (type == "SUBTITLES" && group == subtitleGroup && subtitleGroup != null)
            if (!used) return@forEach
            val uri = attr(line, "URI")
            if (uri == null) {
                masterLines += line
                return@forEach
            }
            val url = resolve(finalUrl, uri) ?: throw Unsupported("a bad sound or subtitle address")
            val name = "r$renditions"
            renditions++
            val (mediaText, mediaUrl) = withRetries(run, "a sound or subtitle list") { fetchText(url, headers) }
            val (local, _) = localMedia(name, mediaText, mediaUrl, shared, pieces, subtitles = type == "SUBTITLES")
            files += "$name.m3u8" to local
            masterLines += replaceUri(line, "$name.m3u8")
        }
        val (mediaText, mediaUrl) = withRetries(run, "the quality's playlist") { fetchText(best.url, headers) }
        val (local, seconds) = localMedia("v", mediaText, mediaUrl, shared, pieces)
        files += "v.m3u8" to local
        masterLines += best.line
        masterLines += "v.m3u8"
        files += "index.m3u8" to masterLines.joinToString("\n", postfix = "\n")
        val quality = listOfNotNull(best.height?.let { "${it}p" }, best.bandwidth?.let { mbps(it) })
            .joinToString(", ").ifBlank { "the best quality" }
        return Plan("index.m3u8", files, seconds, quality, renditions + 1)
    }

    /**
     * A local copy of a media playlist, pointing at the files its pieces (and init pieces and keys) are saved as;
     * the pieces are added to [pieces]. Returns the text and the length in seconds.
     */
    private fun localMedia(
        name: String,
        text: String,
        base: HttpUrl,
        shared: MutableMap<String, String>,
        pieces: MutableList<Piece>,
        subtitles: Boolean = false,
    ): Pair<String, Double> {
        if (!text.trimStart().startsWith("#EXTM3U")) throw Unsupported("a playlist that isn't HLS")
        val out = mutableListOf<String>()
        var seconds = 0.0
        var segments = 0
        var ended = false
        // KMK (r88): pieces of a list with an init piece are fMP4; saved as ".ts" (when their address has no usable
        // extension) ffmpeg's HLS reader took them for MPEG-TS
        val fallbackExtension = if (text.contains("#EXT-X-MAP")) "m4s" else "ts"
        // KMK (r88): whether the pieces from here on are under an AES-128 key
        var encrypted = false
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-ENDLIST") -> {
                    ended = true
                    out += line
                }
                line.startsWith("#EXT-X-BYTERANGE") -> throw Unsupported("pieces given as byte ranges")
                line.startsWith("#EXT-X-STREAM-INF") -> throw Unsupported("a quality list inside a quality")
                line.startsWith("#EXT-X-SKIP") -> throw Unsupported("a live list")
                line.startsWith("#EXT-X-KEY:") -> {
                    encrypted = (attr(line, "METHOD")?.uppercase() ?: "NONE") != "NONE"
                    out += localKey(line, base, shared, pieces)
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    if (attr(line, "BYTERANGE") != null) throw Unsupported("an init piece given as a byte range")
                    val uri = attr(line, "URI") ?: throw Unsupported("an init piece without an address")
                    val local = localFile(uri, base, "${name}_init", "mp4", shared, pieces, checked = !encrypted)
                    out += replaceUri(line, local)
                }
                line.startsWith("#EXTINF:") -> {
                    seconds += line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    out += line
                }
                DROPPED_TAGS.any { line.startsWith(it) } -> Unit
                line.startsWith("#") -> out += line
                else -> {
                    val url = resolve(base, line) ?: throw Unsupported("a bad piece address")
                    val file = "${name}_" + "%05d".format(Locale.US, segments) + "." +
                        extensionOf(url, fallbackExtension)
                    segments++
                    pieces += Piece(url, file, checked = !encrypted && !subtitles)
                    out += file
                }
            }
        }
        if (!ended) throw Unsupported("a live stream (the list has no end)")
        if (segments == 0) throw Unsupported("a list without pieces")
        return out.joinToString("\n", postfix = "\n") to seconds
    }

    private fun localKey(
        line: String,
        base: HttpUrl,
        shared: MutableMap<String, String>,
        pieces: MutableList<Piece>,
    ): String {
        val method = attr(line, "METHOD")?.uppercase() ?: "NONE"
        if (method == "NONE") return line
        if (method != "AES-128") throw Unsupported("$method encryption")
        val format = attr(line, "KEYFORMAT")
        if (format != null && !format.equals("identity", ignoreCase = true)) throw Unsupported("key format $format")
        val uri = attr(line, "URI") ?: throw Unsupported("a key without an address")
        if (uri.startsWith("data:")) return line
        // The IV (or the piece number when there's none) stays as it is
        return replaceUri(
            line,
            localFile(uri, base, "key", "key", shared, pieces, keepExtension = false, isKey = true),
        )
    }

    /** The local name of an init piece or key, the same file each time the same address comes up */
    private fun localFile(
        uri: String,
        base: HttpUrl,
        prefix: String,
        fallbackExtension: String,
        shared: MutableMap<String, String>,
        pieces: MutableList<Piece>,
        keepExtension: Boolean = true,
        isKey: Boolean = false,
        checked: Boolean = false,
    ): String {
        val url = resolve(base, uri) ?: throw Unsupported("a bad address in the playlist")
        return shared.getOrPut(url) {
            val ext = if (keepExtension) extensionOf(url, fallbackExtension) else fallbackExtension
            val file = "$prefix${shared.size}.$ext"
            pieces += Piece(url, file, isKey = isKey, checked = checked)
            file
        }
    }

    /**
     * KMK (r88): on a resume, fetches the stream's keys again; true when one differs from the saved copy (or can't
     * be fetched), so the pieces saved under it are useless
     */
    private suspend fun keysChanged(pieces: List<Piece>, headers: Headers, dir: File, run: Run): Boolean {
        for (piece in pieces.filter { it.isKey }) {
            val saved = File(dir, piece.file)
            if (saved.length() == 0L) continue
            val check = File(dir, piece.file + ".check")
            try {
                withRetries(run, "a key") { fetchPiece(piece, headers, check) }
                if (!check.readBytes().contentEquals(saved.readBytes())) return true
            } catch (e: IOException) {
                return true
            } finally {
                check.delete()
            }
        }
        return false
    }

    private suspend fun fetchPieces(
        pieces: List<Piece>,
        headers: Headers,
        dir: File,
        connections: Int,
        run: Run,
        done: AtomicInteger,
        started: Long,
        onPercent: (Int) -> Unit,
    ): Long {
        val total = pieces.size
        val next = AtomicInteger(0)
        val bytes = AtomicLong(0L)
        val lastTenth = AtomicInteger(done.get() * 10 / total)
        val gate = run.gate
        coroutineScope {
            repeat(connections.coerceAtMost(total)) { worker ->
                launch {
                    // A worker above the limit stops after a server asked to slow down
                    while (gate == null || worker < gate.limit) {
                        val index = next.getAndIncrement()
                        if (index >= total) break
                        val piece = pieces[index]
                        val file = File(dir, piece.file)
                        if (file.length() > 0) continue
                        val size = withRetries(run, "piece ${index + 1}/$total") {
                            fetchPiece(piece, headers, file)
                        }
                        bytes.addAndGet(size)
                        val now = done.incrementAndGet()
                        onPercent(now * 95 / total)
                        val tenth = now * 10 / total
                        val previous = lastTenth.get()
                        if (tenth > previous && lastTenth.compareAndSet(previous, tenth)) {
                            AppLog.add(
                                'I',
                                TAG,
                                "${run.label}: $now/$total pieces (${tenth * 10}%), ${speed(bytes.get(), started)}",
                            )
                        }
                    }
                }
            }
        }
        val missing = pieces.count { File(dir, it.file).length() == 0L }
        if (missing > 0) throw IOException("$missing pieces are missing")
        // KMK (r88): what this try downloaded, for the speed in the summary
        return bytes.get()
    }

    private suspend fun fetchPiece(piece: Piece, headers: Headers, file: File): Long {
        val part = File(file.parentFile, file.name + ".part")
        val request = Request.Builder().url(piece.url).headers(headers).build()
        return call(hlsClient, request) { response ->
            if (!response.isSuccessful) throw HttpStatus(response.code, retryAfter(response))
            // KMK (r88): an error or login page sent with 200 was saved as a piece, and ffmpeg failed on it later
            val type = response.body.contentType()?.toString().orEmpty()
            if (type.contains("text/html", ignoreCase = true)) throw IOException("a web page instead of a piece")
            var written = 0L
            part.outputStream().use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                    }
                }
            }
            val expected = response.body.contentLength()
            if (written == 0L) throw IOException("an empty piece")
            if (expected > 0 && written < expected) throw IOException("cut short ($written of $expected bytes)")
            if (piece.checked && piece.file.substringAfterLast('.') in CHECKED_EXTENSIONS &&
                !looksLikeMedia(readHead(part))
            ) {
                part.delete()
                throw IOException("not a video piece")
            }
            if (!part.renameTo(file)) throw IOException("couldn't keep the piece")
            written
        }
    }

    /** KMK (r88): piece kinds whose first bytes are checked before they're kept */
    private val CHECKED_EXTENSIONS = setOf("ts", "mpegts", "m4s", "mp4", "m4v", "m4a", "mov", "aac")

    /** fMP4 boxes a piece (or init piece) starts with */
    private val MP4_BOXES = setOf("ftyp", "styp", "moof", "sidx", "moov", "emsg", "prft", "free", "mdat")

    private const val HEAD_BYTES = 64 * 1024
    private const val TS_PACKET = 188

    private fun readHead(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(HEAD_BYTES)
        var size = 0
        while (size < buffer.size) {
            val read = input.read(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
        }
        buffer.copyOf(size)
    }

    /**
     * KMK (r88): MPEG-TS (sync byte 0x47 every 188 bytes, also after a picture some sites put in front), an fMP4
     * box, or packed audio (ID3, ADTS)
     */
    private fun looksLikeMedia(head: ByteArray): Boolean {
        if (head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_BOXES) return true
        if (head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") return true
        if (head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xF0) == 0xF0) return true
        val sync = 0x47.toByte()
        if (head.size <= TS_PACKET) return head.isNotEmpty() && head[0] == sync
        for (offset in 0 until head.size - 2 * TS_PACKET) {
            if (head[offset] == sync && head[offset + TS_PACKET] == sync && head[offset + 2 * TS_PACKET] == sync) {
                return true
            }
        }
        // A short piece: one or two packets
        return head[0] == sync && (head.size < 2 * TS_PACKET + 1 || head[TS_PACKET] == sync)
    }

    private suspend fun fetchText(url: String, headers: Headers): Pair<String, HttpUrl> {
        val request = Request.Builder().url(url).headers(headers).build()
        return call(hlsClient, request) { response ->
            if (!response.isSuccessful) throw HttpStatus(response.code, retryAfter(response))
            val source = response.body.source()
            source.request(MAX_PLAYLIST_BYTES + 1)
            if (source.buffer.size > MAX_PLAYLIST_BYTES) throw Unsupported("a playlist over 16 MB")
            source.buffer.readUtf8() to response.request.url
        }
    }

    /** Runs [request] and closes the response after [block] (waiting for the answer can be cancelled) */
    private suspend fun <T> call(client: OkHttpClient, request: Request, block: suspend (Response) -> T): T {
        val response = client.newCall(request).await()
        return response.use { block(it) }
    }

    /** [block] up to [TRIES] times, waiting longer each time (and with fewer connections after 429/503) */
    private suspend fun <T> withRetries(run: Run, what: String, block: suspend () -> T): T {
        var last: IOException? = null
        for (attempt in 1..TRIES) {
            try {
                return block()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                last = e
                if (attempt == TRIES) break
                val code = (e as? HttpStatus)?.code
                val slow = code == 429 || code == 503
                if (slow) {
                    run.gate?.slowDown()?.let { limit ->
                        AppLog.add(
                            'W',
                            TAG,
                            "${run.label}: the server asks to slow down (HTTP $code), $limit connections now",
                        )
                    }
                }
                val wait = (e as? HttpStatus)?.retryAfterMs?.coerceIn(1_000L, 30_000L)
                    ?: ((1_000L shl (attempt - 1)) * (if (slow) 3 else 1))
                if (run.retries.incrementAndGet() <= MAX_RETRY_LOGS) {
                    val why = if (e is HttpStatus) "HTTP ${e.code}" else "${e.javaClass.simpleName}: ${e.message}"
                    AppLog.add(
                        'W',
                        TAG,
                        "${run.label}: $what failed ($why), try ${attempt + 1} of $TRIES in ${wait / 1000} s",
                    )
                }
                delay(wait)
            }
        }
        throw last ?: IOException("$what failed")
    }

    /** ffmpeg joins the local pieces (same output options as saving from the link) */
    private suspend fun join(entry: File, out: File, seconds: Double, onPercent: (Int) -> Unit) {
        val args = arrayOf(
            // Local lists with keys: ffmpeg opens the key file and decrypts through "crypto"
            "-protocol_whitelist", "file,crypto,data",
            "-allowed_extensions", "ALL",
            "-i", entry.absolutePath,
            "-map", "0:v?", "-map", "0:a?", "-map", "0:s?",
            "-c", "copy", "-f", "matroska", "-y", out.absolutePath,
        )
        suspendCancellableCoroutine<Unit> { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args,
                { session ->
                    if (session.returnCode?.isValueSuccess == true) {
                        continuation.resume(Unit)
                    } else {
                        val tail = session.allLogsAsString?.takeLast(300).orEmpty()
                        continuation.resumeWithException(Unsupported("ffmpeg couldn't join the pieces: $tail"))
                    }
                },
                null,
                StatisticsCallback { stats ->
                    if (seconds > 0) onPercent((95 + (stats.time / 1000.0) * 4 / seconds).toInt().coerceIn(95, 99))
                },
            )
            continuation.invokeOnCancellation { session.cancel() }
        }
    }

    // Plain files in parts

    class RangeProbe(val total: Long, val contentType: String)

    /**
     * Asks [url] for its first byte. A server that answers 206 with the whole size (and not a playlist, and at
     * least 4 MB) gives a [RangeProbe]; null otherwise (the caller downloads in one go, as before).
     */
    suspend fun probeRanges(client: OkHttpClient, url: String, headers: Headers, label: String): RangeProbe? {
        return try {
            val request = Request.Builder().url(url).headers(headers).header("Range", "bytes=0-0").build()
            call(rangeClient(client), request) { response ->
                val type = response.body.contentType()?.toString().orEmpty()
                val total = response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                when {
                    type.contains("mpegurl", ignoreCase = true) || type.contains("dash", ignoreCase = true) -> null
                    response.code != 206 || total == null -> {
                        val code = response.code
                        AppLog.add('I', TAG, "$label: the server doesn't send parts (HTTP $code), one connection")
                        null
                    }
                    total < MIN_SPLIT_BYTES -> null
                    else -> RangeProbe(total, type)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.add('W', TAG, "$label: couldn't ask for parts ($reason), one connection")
            null
        }
    }

    /**
     * Downloads the file [probe] found in [connections] parts, written at their place in [out]. False when that
     * failed (logged); the caller downloads it in one go. Throws only on cancellation.
     */
    suspend fun saveRanges(
        client: OkHttpClient,
        url: String,
        headers: Headers,
        probe: RangeProbe,
        out: File,
        connections: Int,
        label: String,
        onPercent: (Int) -> Unit,
    ): Boolean = withIOContext {
        val started = System.currentTimeMillis()
        val total = probe.total
        val parts = connections.coerceAtLeast(1)
        val size = (total + parts - 1) / parts
        val written = AtomicLong(0L)
        val lastQuarter = AtomicInteger(0)
        val run = Run(label, null)
        val ranged = rangeClient(client)
        AppLog.add('I', TAG, "$label: the server sends parts; ${formatSize(total)} in $parts parts")
        try {
            RandomAccessFile(out, "rw").use { file ->
                file.setLength(total)
                val channel = file.channel
                coroutineScope {
                    repeat(parts) { index ->
                        val start = index * size
                        val end = minOf(total, start + size) - 1
                        if (start > end) return@repeat
                        launch {
                            val got = AtomicLong(0L)
                            withRetries(run, "part ${index + 1}/$parts") {
                                fetchRange(ranged, url, headers, channel, start + got.get(), end) { count ->
                                    got.addAndGet(count)
                                    val now = written.addAndGet(count)
                                    onPercent((now * 100 / total).toInt().coerceIn(0, 99))
                                    val quarter = (now * 4 / total).toInt()
                                    val previous = lastQuarter.get()
                                    if (quarter in 1..3 && quarter > previous &&
                                        lastQuarter.compareAndSet(previous, quarter)
                                    ) {
                                        AppLog.add('I', TAG, "$label: ${quarter * 25}%, ${speed(now, started)}")
                                    }
                                }
                            }
                            if (got.get() != end - start + 1) throw IOException("part ${index + 1} is incomplete")
                        }
                    }
                }
            }
            AppLog.add(
                'I',
                TAG,
                "Fast download: $parts parts with $parts connections in ${secondsSince(started)}s " +
                    "(${formatSize(total)}, ${speed(total, started)}" +
                    (if (run.retries.get() > 0) ", ${run.retries.get()} retries" else "") + "): $label",
            )
            true
        } catch (e: CancellationException) {
            AppLog.add('I', TAG, "$label: cancelled at ${formatSize(written.get())} of ${formatSize(total)}")
            throw e
        } catch (e: Exception) {
            val reason = if (e is Unsupported) e.message else "${e.javaClass.simpleName}: ${e.message}"
            AppLog.add('W', TAG, "$label: download in parts didn't work ($reason), one connection instead")
            false
        }
    }

    private suspend fun fetchRange(
        client: OkHttpClient,
        url: String,
        headers: Headers,
        channel: FileChannel,
        from: Long,
        end: Long,
        onBytes: (Long) -> Unit,
    ) {
        if (from > end) return
        val request = Request.Builder().url(url).headers(headers).header("Range", "bytes=$from-$end").build()
        call(client, request) { response ->
            if (response.code == 200) throw Unsupported("the server sent the whole file instead of a part")
            if (response.code != 206) throw HttpStatus(response.code, retryAfter(response))
            val startOfReply = response.header("Content-Range")?.removePrefix("bytes")?.trim()
                ?.substringBefore('-')?.toLongOrNull()
            if (startOfReply != null && startOfReply != from) throw Unsupported("the server sent another part")
            var position = from
            response.body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (position <= end) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer, 0, minOf(BUFFER.toLong(), end - position + 1).toInt())
                    if (read < 0) break
                    val chunk = ByteBuffer.wrap(buffer, 0, read)
                    var at = position
                    while (chunk.hasRemaining()) at += channel.write(chunk, at)
                    position += read
                    onBytes(read.toLong())
                }
            }
            if (position <= end) throw IOException("the part was cut short")
        }
    }

    private fun rangeClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .dispatcher(dispatcher)
        .build()

    // Helpers

    /** `NAME=value` or `NAME="value"` of a tag line */
    private fun attr(line: String, name: String): String? {
        val match = Regex("""(?:^|[:,])$name=("[^"]*"|[^,]*)""").find(line) ?: return null
        return match.groupValues[1].removeSurrounding("\"")
    }

    private val URI_ATTR = Regex("URI=\"[^\"]*\"")

    private fun replaceUri(line: String, local: String): String = line.replace(URI_ATTR, "URI=\"$local\"")

    private fun resolve(base: HttpUrl, ref: String): String? =
        ref.toHttpUrlOrNull()?.toString() ?: base.resolve(ref)?.toString()

    /** The piece's own extension when ffmpeg accepts it ("…/s/7?x=piece.ts" for relayed pieces), else [fallback] */
    private fun extensionOf(url: String, fallback: String): String {
        val http = url.toHttpUrlOrNull()
        val path = http?.pathSegments?.lastOrNull() ?: url.substringBefore('?').substringAfterLast('/')
        val fromPath = path.substringAfterLast('.', "").lowercase()
        if (fromPath in PIECE_EXTENSIONS) return fromPath
        val fromQuery = http?.queryParameter("x")?.substringAfterLast('.', "")?.lowercase()
        if (fromQuery != null && fromQuery in PIECE_EXTENSIONS) return fromQuery
        return fallback
    }

    /**
     * Same stream = same piece addresses, without their query (tokens change) and through the relay. KMK (r88): a
     * query that tells pieces apart ("…/seg?n=1", "…/seg?n=2") is kept, or any stream with as many pieces looked
     * the same.
     */
    private fun fingerprint(pieces: List<Piece>): String {
        val digest = MessageDigest.getInstance("MD5")
        val full = pieces.map { piece ->
            val http = piece.url.toHttpUrlOrNull()
            val inner = if (http?.host == "127.0.0.1") http.queryParameter("u") else null
            inner ?: piece.url
        }
        val bare = full.map { it.substringBefore('?') }
        val shared = bare.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        pieces.forEachIndexed { index, piece ->
            val address = if (bare[index] in shared) full[index] else bare[index]
            digest.update("${piece.file}|$address\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Pieces of downloads that were never finished go after a few days */
    private fun cleanOld(root: File, keep: File) {
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { dir ->
            if (dir != keep && now - dir.lastModified() > KEEP_PIECES_MS) dir.deleteRecursively()
        }
    }

    private fun safeKey(key: String): String = key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    private fun retryAfter(response: Response): Long? =
        response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)

    private fun secondsSince(start: Long): Long = (System.currentTimeMillis() - start + 500) / 1000

    private fun mbps(bandwidth: Int): String = "%.1f Mbps".format(Locale.US, bandwidth / 1_000_000.0)

    private fun formatSize(bytes: Long): String = "%.1f MB".format(Locale.US, bytes / 1_048_576.0)

    private fun formatSeconds(seconds: Double): String {
        val s = seconds.toLong()
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }

    private fun speed(bytes: Long, start: Long): String {
        val ms = (System.currentTimeMillis() - start).coerceAtLeast(1)
        return "%.1f MB/s".format(Locale.US, bytes / 1_048_576.0 / (ms / 1000.0))
    }
}
