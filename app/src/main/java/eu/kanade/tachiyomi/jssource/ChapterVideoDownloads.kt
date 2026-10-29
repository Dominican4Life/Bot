package eu.kanade.tachiyomi.jssource

import android.app.Application
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.StatisticsCallback
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.download.video.ParallelVideoDownload
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import mihon.core.archive.archiveReader
import okhttp3.Headers
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * KMK: Videos inside downloaded manga chapters. When a chapter with video pages (see [ReaderVideos]) is downloaded,
 * each video is saved next to its page's picture as `NNN.video.mkv` (streams, saved with ffmpeg without re-encoding)
 * or `NNN.video.mp4` / `.webm` (plain files). Reading the chapter offline gives those pages their play button back,
 * and they play the saved file. A video that can't be saved leaves the page as a picture, and the reason goes to
 * the Logs screen; the chapter still downloads.
 */
object ChapterVideoDownloads {

    private const val MARK = ".video."
    private val DURATION = Regex("""Duration: (\d+):(\d{2}):(\d{2}(?:\.\d+)?)""")
    private const val TMP = ".tmp"
    private const val MAX_TRIES = 3
    private const val BUFFER = 64 * 1024

    private val context: Application by lazy { Injekt.get<Application>() }

    /** True for a saved chapter video (or its unfinished `.tmp`), which is never a page of its own */
    fun isVideoFile(name: String?): Boolean = name?.contains(MARK) == true

    // Downloading

    /**
     * Saves the video of [page] into [dir] as `[filename].video.*`, unless it's already there. Tries the
     * source's videos in order (best first) until one saves; throws the last error when none does.
     */
    suspend fun save(
        page: Page,
        dir: UniFile,
        filename: String,
        // KMK (r87): names the video in the "download" log lines
        label: String = filename,
        reportPercent: (Int) -> Unit = {},
    ) = withIOContext {
        // KMK (r88): the percent never steps back (pieces finish out of order; a fallback starts again at 0)
        val onPercent = ParallelVideoDownload.monotonic(reportPercent)
        val prefix = "$filename$MARK"
        val existing = dir.listFiles()?.firstOrNull { f ->
            val n = f.name.orEmpty()
            n.startsWith(prefix) && !n.endsWith(TMP)
        }
        if (existing != null) return@withIOContext
        dir.listFiles()?.filter { it.name.orEmpty().startsWith(prefix) }?.forEach { it.delete() }

        val videos = ReaderVideos.videosOf(page.url)
        if (videos.isEmpty()) error("The source found no video")
        // KMK (r87): the same chapter folder and page give the same pieces folder, so a retry continues
        val key = "chapter_" + (dir.uri.toString() + "|" + filename).hashCode().toUInt().toString(16)
        var lastError: Throwable? = null
        for (video in videos.take(MAX_TRIES)) {
            try {
                if (video.videoUrl.isBlank()) continue
                val headers = video.headers ?: Headers.Builder().build()
                if (isStream(video)) {
                    ffmpegSave(video, headers, dir, prefix, key, label, onPercent)
                } else {
                    httpSave(video, headers, dir, prefix, key, label, onPercent)
                }
                return@withIOContext
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastError = e
                dir.findFile("$prefix$TMP")?.delete()
            }
        }
        // KMK (r88): no video saved: its pieces (can be GBs) aren't kept until the chapter is downloaded again
        ParallelVideoDownload.discard(key, label)
        throw lastError ?: IllegalStateException("No video could be saved")
    }

    /** Saves every video page of a chapter; failures are logged and skipped */
    suspend fun saveAll(
        pages: List<Page>,
        dir: UniFile,
        filenameOf: (Page) -> String,
        chapterName: String,
        // KMK: 0-100 over all the chapter's videos, for the download's progress
        onPercent: (Int) -> Unit = {},
    ): Int {
        var failed = 0
        val videoPages = pages.filter { ReaderVideos.isVideo(it.url) }
        videoPages.forEachIndexed { index, page ->
            onPercent(index * 100 / videoPages.size)
            try {
                save(page, dir, filenameOf(page), "$chapterName, page ${page.number}") { percent ->
                    onPercent((index * 100 + percent.coerceIn(0, 100)) / videoPages.size)
                }
                logcat(LogPriority.INFO) { "Chapter video saved: $chapterName, page ${page.number}" }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                failed++
                logcat(LogPriority.ERROR, e) {
                    "Couldn't save the video of page ${page.number} in $chapterName; it stays a picture: ${e.message}"
                }
            }
        }
        onPercent(100)
        // KMK: the chapter still counts as downloaded (its pictures are there), so say which videos are missing
        if (failed > 0) {
            eu.kanade.tachiyomi.util.system.AppLog.add(
                'W',
                "download",
                "$chapterName: $failed of ${videoPages.size} videos couldn't be saved (they stay pictures); " +
                    "delete and download the chapter again to retry",
            )
        }
        return failed
    }

    /**
     * KMK (r88): as for anime episodes: "m3u8" anywhere in the whole address (a relayed or tokenized playlist), or
     * a playlist mimeType. It only looked before "?" and at a literal "x=list.m3u8".
     */
    private fun isStream(video: Video): Boolean {
        val url = video.videoUrl.substringBefore('?').lowercase()
        return url.endsWith(".m3u8") || url.endsWith(".mpd") || url.contains("/hls/") ||
            video.videoUrl.contains("m3u8", ignoreCase = true) ||
            video.mimeType?.contains("mpegurl", ignoreCase = true) == true
    }

    private suspend fun httpSave(
        video: Video,
        headers: Headers,
        dir: UniFile,
        prefix: String,
        key: String,
        label: String,
        onPercent: (Int) -> Unit,
    ) {
        val client = Injekt.get<NetworkHelper>().client
        // KMK (r87): a server that sends parts gets several connections at once ("Connections per download")
        val connections = ParallelVideoDownload.connections()
        if (connections > 1) {
            val probe = ParallelVideoDownload.probeRanges(client, video.videoUrl, headers, label)
            if (probe != null) {
                val workDir = File(context.cacheDir, "chapter_video_download").apply { mkdirs() }
                // KMK (r88): a random name; two pages saved in the same millisecond shared one file
                val out = File(workDir, "${UUID.randomUUID()}.part")
                try {
                    val saved = ParallelVideoDownload.saveRanges(
                        client, video.videoUrl, headers, probe, out, connections, label, onPercent,
                    )
                    if (saved) {
                        val tmp = dir.createFile("$prefix$TMP") ?: error("Can't create the file")
                        tmp.openOutputStream().use { output -> out.inputStream().use { it.copyTo(output, BUFFER) } }
                        if (!tmp.renameTo("${prefix}${extensionOf(probe.contentType, video.videoUrl)}")) {
                            error("Couldn't rename the saved video")
                        }
                        return
                    }
                } finally {
                    out.delete()
                }
            }
        }
        val request = Request.Builder().url(video.videoUrl).headers(headers).build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = checkNotNull(response.body) { "Empty response" }
            val type = body.contentType()?.toString().orEmpty()
            if (type.contains("mpegurl", ignoreCase = true) || type.contains("dash", ignoreCase = true)) {
                // It was a playlist after all
                response.close()
                ffmpegSave(video, headers, dir, prefix, key, label, onPercent)
                return
            }
            val total = body.contentLength()
            val tmp = dir.createFile("$prefix$TMP") ?: error("Can't create the file")
            tmp.openOutputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    var written = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) onPercent((written * 100 / total).toInt())
                    }
                }
            }
            val ext = extensionOf(type, video.videoUrl)
            if (!tmp.renameTo("${prefix}$ext")) error("Couldn't rename the saved video")
        }
    }

    private fun extensionOf(type: String, url: String): String = when {
        type.contains("webm") || url.contains(".webm") -> "webm"
        type.contains("matroska") || url.contains(".mkv") -> "mkv"
        else -> "mp4"
    }

    private suspend fun ffmpegSave(
        stream: Video,
        headers: Headers,
        dir: UniFile,
        prefix: String,
        key: String,
        label: String,
        onPercent: (Int) -> Unit,
    ) {
        // KMK: a stream holding several qualities is saved in one (the best), not all of them in one file
        val video = eu.kanade.tachiyomi.ui.player.loader.HlsVariants.forDownload(stream, headers)
        // ffmpeg writes to app storage first, then the file is copied into the chapter folder
        val workDir = File(context.cacheDir, "chapter_video_download").apply { mkdirs() }
        val out = File(workDir, "${UUID.randomUUID()}.mkv")
        try {
            // KMK (r87): pieces fetched several at a time and joined from the local copy; else ffmpeg reads the link
            if (!ParallelVideoDownload.saveHls(video, headers, out, key, label, onPercent)) {
                val started = System.currentTimeMillis()
                ffmpegFromLink(video, headers, out, onPercent)
                ParallelVideoDownload.discard(key)
                val seconds = (System.currentTimeMillis() - started) / 1000
                eu.kanade.tachiyomi.util.system.AppLog.add(
                    'I',
                    "download",
                    "$label: saved by ffmpeg from the link in $seconds s",
                )
            }
            if (!out.exists() || out.length() == 0L) error("ffmpeg saved nothing")
            val tmp = dir.createFile("$prefix$TMP") ?: error("Can't create the file")
            tmp.openOutputStream().use { output -> out.inputStream().use { it.copyTo(output, BUFFER) } }
            if (!tmp.renameTo("${prefix}mkv")) error("Couldn't rename the saved video")
        } finally {
            out.delete()
        }
    }

    /** The way up to r86: ffmpeg reads the stream from its link (one piece at a time) into [out] */
    private suspend fun ffmpegFromLink(video: Video, headers: Headers, out: File, onPercent: (Int) -> Unit) {
        val headerBlock = headers.joinToString("") { (name, value) -> "$name: $value\r\n" }
        val durationMs = java.util.concurrent.atomic.AtomicLong(0L)
        val args = buildList {
            if (headerBlock.isNotEmpty()) {
                add("-headers")
                add(headerBlock)
            }
            video.ffmpegStreamArgs.forEach { (key, value) ->
                add("-$key")
                if (value.isNotBlank()) add(value)
            }
            addAll(listOf("-i", video.videoUrl, "-map", "0:v?", "-map", "0:a?", "-map", "0:s?"))
            addAll(listOf("-c", "copy", "-f", "matroska", "-y", out.absolutePath))
        }.toTypedArray()
        suspendCancellableCoroutine<Unit> { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args,
                { session ->
                    if (session.returnCode?.isValueSuccess == true) {
                        continuation.resume(Unit)
                    } else {
                        val tail = session.allLogsAsString?.takeLast(300).orEmpty()
                        continuation.resumeWithException(IllegalStateException("ffmpeg failed: $tail"))
                    }
                },
                // KMK: the length ffmpeg reads ("Duration: 00:24:01.50") turns its time into a percent
                LogCallback { log ->
                    if (durationMs.get() <= 0L) {
                        DURATION.find(log.message.orEmpty())?.destructured?.let { (h, m, sec) ->
                            val ms = (h.toLong() * 3600 + m.toLong() * 60) * 1000 + (sec.toDouble() * 1000).toLong()
                            durationMs.set(ms)
                        }
                    }
                },
                StatisticsCallback { stats ->
                    val total = durationMs.get()
                    if (total > 0L) onPercent((stats.time * 100 / total).toInt().coerceIn(0, 99))
                },
            )
            continuation.invokeOnCancellation { session.cancel() }
        }
    }

    // Reading offline

    /**
     * Gives the downloaded pages of a chapter folder their videos back: a page whose picture is `NNN.*` (or the
     * first part `NNN__001.*` of a split picture) and that has a saved `NNN.video.*` becomes a video page again.
     */
    fun attachFromDirectory(pages: List<ReaderPage>, dir: UniFile?) {
        val files = dir?.listFiles().orEmpty()
        val videos = files.filter { isVideoFile(it.name) && !it.name.orEmpty().endsWith(TMP) }
            .associateBy { it.name.orEmpty().substringBefore(MARK) }
        if (videos.isEmpty()) return
        val names = files.associate { it.uri.toString() to it.name.orEmpty() }
        pages.forEach { page ->
            val name = page.uri?.let { names[it.toString()] } ?: return@forEach
            val video = videos[numberOf(name) ?: return@forEach] ?: return@forEach
            page.url = ReaderVideos.encode(
                buildJsonObject {
                    put("url", video.uri.toString())
                    put("title", "Video · page ${page.number}")
                },
            )
        }
    }

    /**
     * The same for a chapter saved as an archive (CBZ): the videos are inside it, so a page points at its entry,
     * and the entry is copied out the first time it's played.
     */
    fun attachFromArchive(pages: List<ReaderPage>, archive: UniFile) {
        val (images, videos) = runCatching {
            archive.archiveReader(context).use { reader ->
                reader.useEntries { entries ->
                    val all = entries.filter { it.isFile }.toList()
                    val images = all
                        .filter { !isVideoFile(it.name) && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
                        .map { it.name }
                    val videos = all.filter { isVideoFile(it.name) && !it.name.endsWith(TMP) }
                        .associate { it.name.substringAfterLast('/').substringBefore(MARK) to it.name }
                    images to videos
                }
            }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Couldn't look for videos in ${archive.name}" }
            return
        }
        if (videos.isEmpty()) return
        pages.forEachIndexed { i, page ->
            val imageName = images.getOrNull(i)?.substringAfterLast('/') ?: return@forEachIndexed
            val entry = videos[numberOf(imageName) ?: return@forEachIndexed] ?: return@forEachIndexed
            page.url = ReaderVideos.encode(
                buildJsonObject {
                    put("archive", archive.uri.toString())
                    put("entry", entry)
                    put("title", "Video · page ${page.number}")
                },
            )
        }
    }

    /**
     * KMK: of all the file (or archive entry) names of a downloaded chapter, the pictures that have a saved video,
     * so the Browse page panel can mark them with ▶ ("005.jpg" when "005.video.mp4" is there).
     */
    fun picturesWithVideo(allNames: List<String>): Set<String> {
        val videos = allNames
            .filter { isVideoFile(it) && !it.endsWith(TMP) }
            .map { it.substringAfterLast('/').substringBefore(MARK) }
            .toSet()
        if (videos.isEmpty()) return emptySet()
        return allNames.filter { !isVideoFile(it) && numberOf(it.substringAfterLast('/')) in videos }.toSet()
    }

    /** "005.jpg" -> "005"; "005__001.jpg" -> "005"; the other parts of a split picture -> null */
    private fun numberOf(fileName: String): String? {
        val base = fileName.substringBeforeLast('.')
        if ("__" in base) return base.substringBefore("__").takeIf { base.endsWith("__001") }
        return base
    }

    /** A video saved inside an archive, copied to the cache so the player can open it */
    fun extract(archiveUri: String, entry: String): String {
        val dir = File(context.externalCacheDir ?: context.cacheDir, "chapter_videos").apply { mkdirs() }
        val ext = entry.substringAfterLast('.', "mkv")
        val file = File(dir, "${(archiveUri + "|" + entry).hashCode().toUInt()}.$ext")
        if (file.exists() && file.length() > 0) return file.absolutePath
        val archive = UniFile.fromUri(context, Uri.parse(archiveUri)) ?: error("The downloaded chapter is gone")
        val tmp = File(dir, file.name + TMP)
        archive.archiveReader(context).use { reader ->
            val input = reader.getInputStream(entry) ?: error("The video isn't in the downloaded chapter")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, BUFFER) } }
        }
        tmp.renameTo(file)
        return file.absolutePath
    }
}
