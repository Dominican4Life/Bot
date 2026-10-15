package eu.kanade.tachiyomi.sumizuri

import android.app.Application
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.StatisticsCallback
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.model.Video
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
    suspend fun save(page: Page, dir: UniFile, filename: String, onPercent: (Int) -> Unit = {}) = withIOContext {
        val prefix = "$filename$MARK"
        val existing = dir.listFiles()?.firstOrNull { f ->
            val n = f.name.orEmpty()
            n.startsWith(prefix) && !n.endsWith(TMP)
        }
        if (existing != null) return@withIOContext
        dir.listFiles()?.filter { it.name.orEmpty().startsWith(prefix) }?.forEach { it.delete() }

        val videos = ReaderVideos.videosOf(page.url)
        if (videos.isEmpty()) error("The source found no video")
        var lastError: Throwable? = null
        for (video in videos.take(MAX_TRIES)) {
            try {
                if (video.videoUrl.isBlank()) continue
                val headers = video.headers ?: Headers.Builder().build()
                if (isStream(video)) {
                    ffmpegSave(video, headers, dir, prefix, onPercent)
                } else {
                    httpSave(video, headers, dir, prefix, onPercent)
                }
                return@withIOContext
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastError = e
                dir.findFile("$prefix$TMP")?.delete()
            }
        }
        throw lastError ?: IllegalStateException("No video could be saved")
    }

    /** Saves every video page of a chapter; failures are logged and skipped */
    suspend fun saveAll(pages: List<Page>, dir: UniFile, filenameOf: (Page) -> String, chapterName: String) {
        pages.filter { ReaderVideos.isVideo(it.url) }.forEach { page ->
            try {
                save(page, dir, filenameOf(page))
                logcat(LogPriority.INFO) { "Chapter video saved: $chapterName, page ${page.number}" }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) {
                    "Couldn't save the video of page ${page.number} in $chapterName; it stays a picture: ${e.message}"
                }
            }
        }
    }

    private fun isStream(video: Video): Boolean {
        val url = video.videoUrl.substringBefore('?').lowercase()
        return url.endsWith(".m3u8") || url.endsWith(".mpd") || url.endsWith("list.m3u8") || "m3u8" in url ||
            "/hls/" in url || video.videoUrl.contains("x=list.m3u8")
    }

    private suspend fun httpSave(video: Video, headers: Headers, dir: UniFile, prefix: String, onPercent: (Int) -> Unit) {
        val client = Injekt.get<NetworkHelper>().client
        val request = Request.Builder().url(video.videoUrl).headers(headers).build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = checkNotNull(response.body) { "Empty response" }
            val type = body.contentType()?.toString().orEmpty()
            if (type.contains("mpegurl", ignoreCase = true) || type.contains("dash", ignoreCase = true)) {
                // It was a playlist after all
                response.close()
                ffmpegSave(video, headers, dir, prefix, onPercent)
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
            val ext = when {
                type.contains("webm") || video.videoUrl.contains(".webm") -> "webm"
                type.contains("matroska") || video.videoUrl.contains(".mkv") -> "mkv"
                else -> "mp4"
            }
            tmp.renameTo("${prefix}$ext")
        }
    }

    private suspend fun ffmpegSave(video: Video, headers: Headers, dir: UniFile, prefix: String, onPercent: (Int) -> Unit) {
        val headerBlock = headers.joinToString("") { (name, value) -> "$name: $value\r\n" }
        // ffmpeg writes to app storage first, then the file is copied into the chapter folder
        val workDir = File(context.cacheDir, "chapter_video_download").apply { mkdirs() }
        val out = File(workDir, "${System.currentTimeMillis()}.mkv")
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
        try {
            suspendCancellableCoroutine { continuation ->
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
                    null,
                    StatisticsCallback { stats -> onPercent((stats.time / 1000).toInt()) },
                )
                continuation.invokeOnCancellation { session.cancel() }
            }
            if (!out.exists() || out.length() == 0L) error("ffmpeg saved nothing")
            val tmp = dir.createFile("$prefix$TMP") ?: error("Can't create the file")
            tmp.openOutputStream().use { output -> out.inputStream().use { it.copyTo(output, BUFFER) } }
            tmp.renameTo("${prefix}mkv")
        } finally {
            out.delete()
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
