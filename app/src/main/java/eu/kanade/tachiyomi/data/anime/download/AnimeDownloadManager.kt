package eu.kanade.tachiyomi.data.anime.download

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.StatisticsCallback
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.AnimeVideoFetcher
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.Request
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * KMK: Downloads anime episodes to `<downloads>/anime/<source>/<anime>/<episode>.mkv|mp4`.
 *
 * Plain video files are streamed with the source's own HTTP client (so its cookies and Cloudflare
 * handling apply); HLS/DASH streams are saved with ffmpeg without re-encoding. One episode at a time,
 * run by [AnimeDownloadJob] so it keeps going in the background. The queue survives app restarts.
 */
class AnimeDownloadManager(
    private val context: Context,
    private val storageManager: StorageManager = Injekt.get(),
    private val repository: AnimeRepository = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    preferenceStore: PreferenceStore = Injekt.get(),
) {

    /** One queued episode */
    class Item(val anime: Anime, val episode: Episode) {
        val state = MutableStateFlow(Download.State.QUEUE)
        val progress = MutableStateFlow(0)
        var error: String? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queuePref = preferenceStore.getStringSet("anime_download_queue", emptySet())

    private val _queue = MutableStateFlow<List<Item>>(emptyList())
    val queue: StateFlow<List<Item>> = _queue.asStateFlow()

    /** Bumped whenever a file appears or disappears, so screens can re-check what's downloaded */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Episode file names on disk, per anime id (filled the first time an anime is looked at) */
    private val fileCache = mutableMapOf<Long, Set<String>>()

    init {
        // Bring back what was still queued when the app closed
        scope.launch {
            val saved = queuePref.get().mapNotNull { it.substringAfter(':').toLongOrNull() }
            if (saved.isEmpty()) return@launch
            val items = saved.mapNotNull { episodeId ->
                val episode = repository.getEpisode(episodeId) ?: return@mapNotNull null
                val anime = repository.getAnime(episode.animeId) ?: return@mapNotNull null
                Item(anime, episode)
            }
            _queue.update { current -> current + items.filter { new -> current.none { it.episode.id == new.episode.id } } }
        }
    }

    // Queue

    fun enqueue(anime: Anime, episodes: List<Episode>, startNow: Boolean = false) {
        // Failed ones go back in line
        _queue.value
            .filter { item -> item.state.value == Download.State.ERROR && episodes.any { it.id == item.episode.id } }
            .forEach { it.state.value = Download.State.QUEUE }
        val fresh = episodes
            .filter { ep -> !isDownloaded(anime, ep) && _queue.value.none { it.episode.id == ep.id } }
            .map { Item(anime, it) }
        _queue.update { current ->
            if (startNow) {
                val moved = episodes.mapNotNull { ep -> current.firstOrNull { it.episode.id == ep.id } }
                fresh + moved + (current - moved.toSet())
            } else {
                current + fresh
            }
        }
        saveQueue()
        AnimeDownloadJob.start(context)
    }

    fun cancel(episodeIds: Collection<Long>) {
        val removed = _queue.value.filter { it.episode.id in episodeIds }
        _queue.update { it - removed.toSet() }
        removed.forEach { item -> item.state.value = Download.State.NOT_DOWNLOADED }
        saveQueue()
        _changes.update { it + 1 }
    }

    fun cancelAll() = cancel(_queue.value.map { it.episode.id })

    private fun saveQueue() {
        queuePref.set(
            _queue.value
                .filter { it.state.value != Download.State.ERROR }
                .map { "${it.anime.id}:${it.episode.id}" }
                .toSet(),
        )
    }

    private val failures = mutableListOf<Item>()

    /** Episodes that failed during the last run */
    fun lastFailures(): List<Item> = failures.toList()

    /** Called by [AnimeDownloadJob]: works through the queue until it is empty */
    suspend fun runQueue(onProgress: (Item, Int, Int) -> Unit) {
        failures.clear()
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = _queue.value.firstOrNull { it.state.value == Download.State.QUEUE } ?: break
            val remaining = _queue.value.count { it.state.value == Download.State.QUEUE }
            next.state.value = Download.State.DOWNLOADING
            onProgress(next, 0, remaining)
            try {
                download(next) { percent ->
                    next.progress.value = percent
                    onProgress(next, percent, remaining)
                }
                next.state.value = Download.State.DOWNLOADED
                _queue.update { it - next }
                saveQueue()
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    // Paused by the system: leave it queued
                    next.state.value = Download.State.QUEUE
                    throw e
                }
                logcat(LogPriority.ERROR, e) { "Anime download failed: ${next.episode.name}" }
                next.error = e.message ?: e.javaClass.simpleName
                next.state.value = Download.State.ERROR
                failures += next
                saveQueue()
            }
            _changes.update { it + 1 }
        }
    }

    /** Puts a failed download back in the queue */
    fun retry(anime: Anime, episode: Episode) = enqueue(anime, listOf(episode))

    // Status

    fun stateOf(anime: Anime, episode: Episode): Download.State {
        _queue.value.firstOrNull { it.episode.id == episode.id }?.let { return it.state.value }
        return if (isDownloaded(anime, episode)) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED
    }

    fun progressOf(episodeId: Long): Int = _queue.value.firstOrNull { it.episode.id == episodeId }?.progress?.value ?: 0

    fun isDownloaded(anime: Anime, episode: Episode): Boolean {
        val names = fileCache.getOrPut(anime.id) { listFiles(anime) }
        val base = episodeBaseName(episode)
        return names.any { it.startsWith("$base.") && !it.endsWith(TMP) }
    }

    /** Reads the anime's folder again (call when its page opens) */
    fun refresh(anime: Anime) {
        scope.launch {
            fileCache[anime.id] = listFiles(anime)
            _changes.update { it + 1 }
        }
    }

    fun findEpisodeFile(anime: Anime, episode: Episode): UniFile? {
        val base = episodeBaseName(episode)
        return animeDir(anime, create = false)?.listFiles()
            ?.firstOrNull { file -> file.name?.let { it.startsWith("$base.") && !it.endsWith(TMP) } == true }
    }

    fun downloadedCount(anime: Anime): Int = fileCache[anime.id]?.count { !it.endsWith(TMP) } ?: 0

    /** Episodes of [anime] on disk; unlike [downloadedCount] it reads the folder the first time, so call it off the main thread */
    fun countDownloads(anime: Anime): Int =
        fileCache.getOrPut(anime.id) { listFiles(anime) }.count { !it.endsWith(TMP) }

    // Deleting

    fun delete(anime: Anime, episodes: List<Episode>) {
        cancel(episodes.map { it.id })
        scope.launch {
            episodes.forEach { findEpisodeFile(anime, it)?.delete() }
            fileCache[anime.id] = listFiles(anime)
            _changes.update { it + 1 }
        }
    }

    fun deleteAnime(anime: Anime) {
        cancel(_queue.value.filter { it.anime.id == anime.id }.map { it.episode.id })
        scope.launch {
            animeDir(anime, create = false)?.delete()
            fileCache[anime.id] = emptySet()
            _changes.update { it + 1 }
        }
    }

    // Files

    private fun sourceDirName(anime: Anime): String {
        val source = sourceManager.get(anime.source)
        val name = source?.let { "${it.name} (${it.lang.uppercase()})" } ?: anime.source.toString()
        return DiskUtil.buildValidFilename(name)
    }

    private fun animeDir(anime: Anime, create: Boolean): UniFile? {
        val root = storageManager.getDownloadsDirectory() ?: return null
        val animeRoot = if (create) root.createDirectory(ANIME_DIR) else root.findFile(ANIME_DIR)
        val sourceDir = animeRoot?.let { if (create) it.createDirectory(sourceDirName(anime)) else it.findFile(sourceDirName(anime)) }
        val title = DiskUtil.buildValidFilename(anime.title)
        return sourceDir?.let { if (create) it.createDirectory(title) else it.findFile(title) }
    }

    private fun listFiles(anime: Anime): Set<String> =
        animeDir(anime, create = false)?.listFiles()?.mapNotNull { it.name }?.toSet() ?: emptySet()

    private fun episodeBaseName(episode: Episode): String {
        val name = listOfNotNull(episode.scanlator?.takeIf { it.isNotBlank() }, episode.name).joinToString("_")
        return DiskUtil.buildValidFilename(name)
    }

    // Downloading

    private suspend fun download(item: Item, onPercent: (Int) -> Unit) = withIOContext {
        val source = sourceManager.get(item.anime.source) ?: error("Source not installed")
        val videos = AnimeVideoFetcher.fetchVideos(source, item.episode.toSEpisode())
        if (videos.isEmpty()) error("No videos found")

        val dir = animeDir(item.anime, create = true) ?: error("Can't open the downloads folder")
        val base = episodeBaseName(item.episode)
        var lastError: Throwable? = null
        // Best video first; fall back to the next one when a server fails
        for (candidate in videos.take(MAX_TRIES)) {
            try {
                val video = AnimeVideoFetcher.resolve(source, candidate)
                if (video.videoUrl.isBlank() || video.videoUrl == "null") continue
                val headers = video.headers ?: (source as? AnimeHttpSource)?.headers ?: Headers.Builder().build()
                if (isStream(video)) {
                    ffmpegDownload(video, headers, dir, base, onPercent)
                } else {
                    httpDownload(source, video, headers, dir, base, onPercent)
                }
                fileCache[item.anime.id] = listFiles(item.anime)
                return@withIOContext
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Video ${candidate.videoTitle} failed, trying the next one" }
                lastError = e
                dir.findFile("$base$TMP")?.delete()
            }
        }
        throw lastError ?: IllegalStateException("No playable video")
    }

    private fun isStream(video: Video): Boolean {
        val url = video.videoUrl.substringBefore('?').lowercase()
        return url.endsWith(".m3u8") || url.endsWith(".mpd") || url.contains("/hls/") || url.contains("m3u8")
    }

    private suspend fun httpDownload(
        source: AnimeSource,
        video: Video,
        headers: Headers,
        dir: UniFile,
        base: String,
        onPercent: (Int) -> Unit,
    ) {
        val client = (source as? AnimeHttpSource)?.client ?: Injekt.get<NetworkHelper>().client
        val request = Request.Builder().url(video.videoUrl).headers(headers).build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = checkNotNull(response.body) { "Empty response" }
            val type = body.contentType()?.toString().orEmpty()
            if (type.contains("mpegurl", ignoreCase = true) || type.contains("dash", ignoreCase = true)) {
                // It was a playlist after all
                response.close()
                ffmpegDownload(video, headers, dir, base, onPercent)
                return
            }
            val total = body.contentLength()
            val tmp = dir.findFile("$base$TMP") ?: dir.createFile("$base$TMP") ?: error("Can't create the file")
            tmp.openOutputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onPercent(percent)
                            }
                        }
                    }
                }
            }
            val ext = when {
                type.contains("matroska") || video.videoUrl.contains(".mkv") -> "mkv"
                type.contains("webm") || video.videoUrl.contains(".webm") -> "webm"
                else -> "mp4"
            }
            dir.findFile("$base.$ext")?.delete()
            tmp.renameTo("$base.$ext")
        }
    }

    private suspend fun ffmpegDownload(
        video: Video,
        headers: Headers,
        dir: UniFile,
        base: String,
        onPercent: (Int) -> Unit,
    ) {
        val headerBlock = headers.joinToString("") { (name, value) -> "$name: $value\r\n" }
        val duration = probeDuration(video.videoUrl, headerBlock)
        // ffmpeg writes to app storage first, then the file is copied into the downloads folder
        val workDir = File(context.cacheDir, "anime_download").apply { mkdirs() }
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
                    StatisticsCallback { stats ->
                        if (duration > 0) onPercent(((stats.time / 1000.0) * 100 / duration).toInt().coerceIn(0, 99))
                    },
                )
                continuation.invokeOnCancellation { session.cancel() }
            }
            val tmp = dir.findFile("$base$TMP") ?: dir.createFile("$base$TMP") ?: error("Can't create the file")
            tmp.openOutputStream().use { output -> out.inputStream().use { it.copyTo(output, DEFAULT_BUFFER) } }
            dir.findFile("$base.mkv")?.delete()
            tmp.renameTo("$base.mkv")
            onPercent(100)
        } finally {
            out.delete()
        }
    }

    /** Length in seconds, for the progress bar (0 when unknown) */
    private fun probeDuration(url: String, headerBlock: String): Double {
        return runCatching {
            val args = buildList {
                if (headerBlock.isNotEmpty()) {
                    add("-headers")
                    add(headerBlock)
                }
                addAll(listOf("-v", "quiet", "-show_entries", "format=duration", "-of", "csv=p=0", url))
            }.toTypedArray()
            val session = FFprobeKit.executeWithArguments(args)
            session.output?.trim()?.lines()?.firstOrNull()?.toDoubleOrNull() ?: 0.0
        }.getOrDefault(0.0)
    }

    companion object {
        const val ANIME_DIR = "anime"
        private const val TMP = ".tmp"
        private const val MAX_TRIES = 3
        private const val DEFAULT_BUFFER = 64 * 1024
    }
}
