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
import eu.kanade.tachiyomi.data.anime.AnimePrefs
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.AnimeVideoFetcher
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.download.video.ParallelVideoDownload
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * KMK: Downloads anime episodes to `<downloads>/anime/<source>/<anime>/<episode>.mkv|mp4`.
 *
 * Plain video files are streamed with the source's own HTTP client (so its cookies and Cloudflare
 * handling apply); HLS/DASH streams are saved with ffmpeg without re-encoding. Up to
 * [AnimePrefs.concurrentDownloads] episodes at a time, run by [AnimeDownloadJob] so it keeps going in the
 * background. The queue survives app restarts.
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

        /**
         * KMK (r87): the stream picked in Episode options, downloaded first (it used to be dropped and the
         * downloader picked its own). Not kept over an app restart: null = the best one found.
         */
        @Volatile
        var video: Video? = null

        /** KMK (r88): cancelled or deleted by the user (a running download is stopped, not marked downloaded) */
        @Volatile
        var cancelled: Boolean = false
    }

    /**
     * KMK (r88): a download that is running (or its pieces being deleted). Cancel used to only take the episode off
     * the list: the worker went on and marked it downloaded, and a new download of the same episode ran in the same
     * pieces folder at the same time (a broken file). [finished] completes once it has stopped and cleaned up.
     */
    private class Running(val item: Item) {
        @Volatile
        var job: Job? = null
        val finished = CompletableDeferred<Unit>()
    }

    private class Claim(val item: Item, val running: Running, val previous: Running?)

    /** KMK (r88): how many of an anime's episodes share each file name, for the older and looser name lookups */
    private class NameCounts(val legacy: Map<String, Int>, val stems: Map<String, Int>)

    /**
     * KMK (r90): an anime's download folder. [shared]: another library title of the same source has the same name
     * apart from letter case, so the folder may hold that title's files too and only this anime's own count.
     */
    private class AnimeFolder(val dir: UniFile, val shared: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queuePref = preferenceStore.getStringSet("anime_download_queue", emptySet())

    private val _queue = MutableStateFlow<List<Item>>(emptyList())
    val queue: StateFlow<List<Item>> = _queue.asStateFlow()

    /** Bumped whenever a file appears or disappears, so screens can re-check what's downloaded */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Episode file names on disk, per anime id (filled the first time an anime is looked at) */
    private val fileCache = ConcurrentHashMap<Long, Set<String>>()

    /** KMK (r88): per anime id, filled with [fileCache] */
    private val nameCounts = ConcurrentHashMap<Long, NameCounts>()

    /** KMK (r88): per episode id */
    private val running = ConcurrentHashMap<Long, Running>()

    /** KMK (r90): folder names of the library's titles per source, (anime id, name), and when they were read */
    @Volatile
    private var libraryNames: Pair<Long, Map<Long, List<Pair<Long, String>>>>? = null

    /** Taking the next queued item and creating folders happen one download at a time */
    private val claimLock = Mutex()
    private val dirLock = Mutex()

    init {
        // Bring back what was still queued when the app closed
        scope.launch {
            val saved = queuePref.get().mapNotNull { it.substringAfter(':').toLongOrNull() }
            if (saved.isNotEmpty()) {
                val items = saved.mapNotNull { episodeId ->
                    val episode = repository.getEpisode(episodeId) ?: return@mapNotNull null
                    val anime = repository.getAnime(episode.animeId) ?: return@mapNotNull null
                    Item(anime, episode)
                }
                _queue.update { current ->
                    current + items.filter { new -> current.none { it.episode.id == new.episode.id } }
                }
            }
            // KMK (r88): pieces of episode downloads that aren't queued any more (cancelled, failed, removed)
            ParallelVideoDownload.cleanUp(KEY_PREFIX, keptKeys())
        }
    }

    // Queue

    fun enqueue(anime: Anime, episodes: List<Episode>, startNow: Boolean = false, video: Video? = null) {
        // Failed ones go back in line
        _queue.value
            .filter { item -> item.state.value == Download.State.ERROR && episodes.any { it.id == item.episode.id } }
            .forEach { it.state.value = Download.State.QUEUE }
        val fresh = episodes
            .filter { ep -> !isDownloaded(anime, ep) && _queue.value.none { it.episode.id == ep.id } }
            .map { Item(anime, it) }
        // KMK (r87): the stream picked in Episode options (one episode), also for one already waiting in line
        if (video != null) {
            val episodeId = episodes.singleOrNull()?.id
            (fresh + _queue.value)
                .firstOrNull { it.episode.id == episodeId && it.state.value != Download.State.DOWNLOADING }
                ?.video = video
        }
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
        removed.forEach { item ->
            item.cancelled = true
            item.state.value = Download.State.NOT_DOWNLOADED
            // KMK (r88): a running download is stopped (its worker deletes its pieces and unfinished file);
            // a waiting one only had pieces from an earlier try
            val active = running[item.episode.id]?.takeIf { it.item === item }
            if (active != null) {
                active.job?.cancel()
                AppLog.add('I', LOG_TAG, "${labelOf(item)}: cancelled, stopping the download")
            } else {
                cleanUpLater(item)
            }
        }
        saveQueue()
        _changes.update { it + 1 }
    }

    /** KMK (r88): deletes the pieces of a cancelled download that wasn't running */
    private fun cleanUpLater(item: Item) {
        val entry = Running(item)
        // Something already running for this episode cleans up after itself
        if (running.putIfAbsent(item.episode.id, entry) != null) return
        scope.launch {
            try {
                cleanUpStopped(item)
            } finally {
                running.remove(item.episode.id, entry)
                entry.finished.complete(Unit)
            }
        }
    }

    /** KMK (r88): the pieces and the unfinished file of a stopped download */
    private fun cleanUpStopped(item: Item) {
        ParallelVideoDownload.discard(pieceKey(item.episode), labelOf(item))
        runCatching {
            animeDir(item.anime, create = false)?.findFile("${episodeBaseName(item.episode)}$TMP")?.delete()
        }
    }

    private fun pieceKey(episode: Episode): String = "$KEY_PREFIX${episode.id}"

    private fun labelOf(item: Item): String = "${item.anime.title} · ${item.episode.name}"

    /** Pieces folders still needed: queued and running downloads */
    private fun keptKeys(): List<String> =
        (_queue.value.map { it.episode } + running.values.map { it.item.episode }).map { pieceKey(it) }

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
    fun lastFailures(): List<Item> = synchronized(failures) { failures.toList() }

    /**
     * Called by [AnimeDownloadJob]: works through the queue until it is empty, with as many downloads at once as
     * set in the anime download settings.
     */
    suspend fun runQueue(onProgress: (Item, Int, Int) -> Unit) {
        synchronized(failures) { failures.clear() }
        val workers = AnimePrefs.concurrentDownloads().get().coerceIn(1, MAX_CONCURRENT)
        coroutineScope {
            repeat(workers) { launch { runWorker(onProgress) } }
        }
        // KMK (r88): the queue is done; pieces nobody will continue go
        withIOContext { ParallelVideoDownload.cleanUp(KEY_PREFIX, keptKeys()) }
    }

    /**
     * Takes the next queued episode, marking it as downloading so no other worker takes it. KMK (r88): also notes
     * it as running, with the earlier run of the same episode it must wait for (a cancelled one still stopping).
     */
    private suspend fun claimNext(): Claim? = claimLock.withLock {
        val item = _queue.value.firstOrNull { it.state.value == Download.State.QUEUE }
        if (item == null) {
            null
        } else {
            item.state.value = Download.State.DOWNLOADING
            val mine = Running(item)
            Claim(item, mine, running.put(item.episode.id, mine))
        }
    }

    private suspend fun runWorker(onProgress: (Item, Int, Int) -> Unit) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val claim = claimNext() ?: break
            try {
                runOne(claim, onProgress)
            } finally {
                running.remove(claim.item.episode.id, claim.running)
                claim.running.finished.complete(Unit)
            }
            _changes.update { it + 1 }
        }
    }

    private suspend fun runOne(claim: Claim, onProgress: (Item, Int, Int) -> Unit) {
        val next = claim.item
        val remaining = _queue.value.count { it.state.value == Download.State.QUEUE }
        onProgress(next, 0, remaining)
        try {
            // KMK (r88): never two downloads of one episode at once (same pieces folder and file)
            claim.previous?.let { previous ->
                if (!previous.finished.isCompleted) {
                    AppLog.add('I', LOG_TAG, "${labelOf(next)}: waiting for the stopped download of it to end")
                }
                previous.finished.await()
            }
            // KMK (r88): the download runs as its own job, so cancel/delete can stop it
            if (!next.cancelled) {
                coroutineScope {
                    val job = launch {
                        download(next) { percent ->
                            next.progress.value = percent
                            onProgress(next, percent, remaining)
                        }
                    }
                    claim.running.job = job
                    // Cancelled before the job was noted
                    if (next.cancelled) job.cancel()
                }
            }
            if (next.cancelled) {
                cleanUpStopped(next)
                return
            }
            next.state.value = Download.State.DOWNLOADED
            _queue.update { it - next }
            saveQueue()
        } catch (e: Throwable) {
            if (next.cancelled) {
                // A stopped download can end with the error its stop caused (a closed connection)
                cleanUpStopped(next)
                if (e is CancellationException) throw e
                return
            }
            if (e is CancellationException) {
                // Paused by the system: leave it queued (its pieces are kept to continue)
                next.state.value = Download.State.QUEUE
                throw e
            }
            logcat(LogPriority.ERROR, e) { "Anime download failed: ${next.episode.name}" }
            next.error = e.message ?: e.javaClass.simpleName
            next.state.value = Download.State.ERROR
            synchronized(failures) { failures += next }
            saveQueue()
            // KMK (r88): a failed download starts afresh when retried
            ParallelVideoDownload.discard(pieceKey(next.episode), labelOf(next))
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
        return matchName(anime, episode, names) != null
    }

    /** Reads the anime's folder again (call when its page opens) */
    fun refresh(anime: Anime) {
        scope.launch {
            reload(anime)
            _changes.update { it + 1 }
        }
    }

    fun findEpisodeFile(anime: Anime, episode: Episode): UniFile? {
        val files = animeDir(anime, create = false)?.listFiles() ?: return null
        val name = matchName(anime, episode, files.mapNotNull { it.name }) ?: return null
        return files.firstOrNull { it.name == name }
    }

    /**
     * KMK (r87): the name with the episode number first, else the older name without it. KMK (r88): the older name
     * only when no other episode of the anime has the same name (an old "Episode 1.mkv" was taken for every
     * "Episode 1": B couldn't be downloaded, and playing or deleting B used A's file). As a last resort (the source
     * renumbered the episode or changed its link), the one file with the same "<scanlator_name>_E…"/"_U…" start.
     */
    private fun matchName(anime: Anime, episode: Episode, names: Collection<String>): String? {
        val base = episodeBaseName(episode)
        names.firstOrNull { isFileOf(it, base) }?.let { return it }
        val counts = nameCountsOf(anime)
        val legacy = legacyBaseName(episode)
        // Unknown counts (the database couldn't be read): as up to r87
        if ((counts?.legacy?.get(legacy) ?: 1) <= 1) {
            names.firstOrNull { isFileOf(it, legacy) }?.let { return it }
        }
        val stem = nameStem(episode)
        if (counts == null || (counts.stems[stem] ?: 1) > 1) return null
        return names.filter { name ->
            isEpisodeVideo(name) && name.startsWith(stem) &&
                NUMBER_TAG.matches(name.substring(stem.length).substringBeforeLast('.'))
        }.singleOrNull()
    }

    /**
     * KMK (r87): [name] is the finished file of [base] ("<base>.mkv"). It used to match any name starting with
     * "<base>.", so "Episode 1" also matched "Episode 1.5.mkv". KMK (r88): only video files ("<base>.srt" isn't).
     */
    private fun isFileOf(name: String, base: String): Boolean =
        isEpisodeVideo(name) && name.substringBeforeLast('.') == base

    /**
     * KMK (r88): only episode videos count. Every file in the folder used to count, so the cover saved there
     * ("Save covers in download folders") showed every anime with a download.
     */
    fun downloadedCount(anime: Anime): Int = fileCache[anime.id]?.count { isEpisodeVideo(it) } ?: 0

    /** KMK (r90): episode videos in the anime's folder, read now (Stats); only its own in a shared folder */
    fun episodeFileCount(anime: Anime): Int = listFiles(anime).count { isEpisodeVideo(it) }

    /** KMK (r88): reads the folder and the episode names again */
    private suspend fun reload(anime: Anime) {
        // KMK (r90): the episode names first, a shared folder's files are picked by them
        val counts = loadNameCounts(anime.id)
        if (counts != null) nameCounts[anime.id] = counts else nameCounts.remove(anime.id)
        fileCache[anime.id] = listFiles(anime)
    }

    /**
     * KMK (r90): [name] is an episode video named after one of the anime's episodes ([episodeBaseName] or the older
     * [legacyBaseName]). Used where two titles share a folder, so one's files don't count for the other.
     */
    private fun ownsFile(name: String, counts: NameCounts): Boolean {
        if (!isEpisodeVideo(name)) return false
        val base = name.substringBeforeLast('.')
        if (base in counts.legacy) return true
        val cut = base.lastIndexOf('_')
        if (cut < 0) return false
        return NUMBER_TAG.matches(base.substring(cut + 1)) && base.substring(0, cut + 1) in counts.stems
    }

    private fun nameCountsOf(anime: Anime): NameCounts? {
        nameCounts[anime.id]?.let { return it }
        // Called from screens too: one short database read per anime, the first time it's looked at
        val counts = try {
            runBlocking(Dispatchers.IO) { loadNameCounts(anime.id) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        if (counts != null) nameCounts[anime.id] = counts
        return counts
    }

    private suspend fun loadNameCounts(animeId: Long): NameCounts? {
        return try {
            val episodes = repository.getEpisodes(animeId)
            NameCounts(
                legacy = episodes.groupingBy { legacyBaseName(it) }.eachCount(),
                stems = episodes.groupingBy { nameStem(it) }.eachCount(),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Couldn't read the episode names of anime $animeId" }
            null
        }
    }

    // Deleting

    fun delete(anime: Anime, episodes: List<Episode>) {
        cancel(episodes.map { it.id })
        // KMK (r88): a running download is stopped first; deleting before its file was there left it behind
        val stopping = episodes.mapNotNull { running[it.id] }
        scope.launch {
            stopping.forEach { it.finished.await() }
            val dir = animeDir(anime, create = false)
            episodes.forEach { episode ->
                findEpisodeFile(anime, episode)?.delete()
                dir?.findFile("${episodeBaseName(episode)}$TMP")?.delete()
                ParallelVideoDownload.discard(pieceKey(episode))
            }
            reload(anime)
            _changes.update { it + 1 }
        }
    }

    fun deleteAnime(anime: Anime) {
        val ids = _queue.value.filter { it.anime.id == anime.id }.map { it.episode.id }
        cancel(ids)
        val stopping = running.values.filter { it.item.anime.id == anime.id }
        scope.launch {
            stopping.forEach { it.finished.await() }
            val episodes = runCatching { repository.getEpisodes(anime.id) }.getOrNull()
            val folder = findAnimeFolder(anime, create = false)
            if (folder != null && folder.shared) {
                // KMK (r90): a folder another title (same name apart from letter case) may use too: only this
                // anime's own episode files and unfinished files go, the folder itself only once it's empty
                val counts = loadNameCounts(anime.id)
                val unfinished = episodes.orEmpty().mapTo(HashSet()) { "${episodeBaseName(it)}$TMP" }
                folder.dir.listFiles().orEmpty()
                    .filter { file ->
                        val name = file.name ?: return@filter false
                        name in unfinished || (counts != null && ownsFile(name, counts))
                    }
                    .forEach { it.delete() }
                if (folder.dir.listFiles().isNullOrEmpty()) folder.dir.delete()
            } else {
                folder?.dir?.delete()
            }
            fileCache[anime.id] = emptySet()
            nameCounts.remove(anime.id)
            // KMK (r88): and the pieces kept for any of its episodes
            episodes?.forEach { ParallelVideoDownload.discard(pieceKey(it)) }
            _changes.update { it + 1 }
        }
    }

    // Files

    private fun sourceDirName(anime: Anime): String {
        val source = sourceManager.get(anime.source)
        val name = source?.let { "${it.name} (${it.lang.uppercase()})" } ?: anime.source.toString()
        return DiskUtil.buildValidFilename(name)
    }

    // KMK: the source's folders under any tag ("Name (EN)", "Name (JS)") and numbered copies count too
    private fun sourceMatch(anime: Anime): eu.kanade.tachiyomi.data.download.SourceFolders.Match {
        val source = sourceManager.get(anime.source)
        return eu.kanade.tachiyomi.data.download.SourceFolders.match(
            exact = sourceDirName(anime),
            baseName = DiskUtil.buildValidFilename(source?.name ?: anime.source.toString()),
            lang = source?.lang.orEmpty(),
            js = source is eu.kanade.tachiyomi.jssource.JsAnimeSource,
        )
    }

    // KMK: also where the anime's cover is kept (CoverFolders)
    internal fun animeDir(anime: Anime, create: Boolean): UniFile? = findAnimeFolder(anime, create)?.dir

    /** KMK (r92): [animeDir] under the downloader's folder lock (pair sync writes episodes too) */
    internal suspend fun animeDirLocked(anime: Anime, create: Boolean): UniFile? =
        dirLock.withLock { animeDir(anime, create) }

    /** KMK (r92): titles were just added (pair sync's merge): the letter-case check reads the library's names again */
    internal fun forgetLibraryNames() {
        libraryNames = null
    }

    private fun findAnimeFolder(anime: Anime, create: Boolean): AnimeFolder? {
        // downloads/Anime (the older "anime" folder counts too)
        val animeRoot = storageManager.getAnimeDownloadsDirectory(create) ?: return null
        val title = DiskUtil.buildValidFilename(anime.title)
        val match = sourceMatch(anime)
        val sourceDirs = eu.kanade.tachiyomi.data.download.SourceFolders.find(animeRoot, match)
        val twins = caseTwinNames(anime, title)
        if (twins.isEmpty()) {
            // KMK: the anime's folder wherever it already is
            sourceDirs.firstNotNullOfOrNull { dir -> dir.findFile(title)?.takeIf { it.isDirectory } }
                ?.let { return AnimeFolder(it, shared = false) }
            if (!create) return null
            val sourceDir = sourceDirs.firstOrNull()
                ?: eu.kanade.tachiyomi.data.download.SourceFolders.findOrCreate(animeRoot, sourceDirName(anime), match)
                ?: return null
            return eu.kanade.tachiyomi.data.download.SourceFolders.findOrCreate(sourceDir, title)
                ?.let { AnimeFolder(it, shared = false) }
        }
        // KMK (r90): another library title of this source has the same name apart from letter case ("Bleach…" and
        // "BLEACH…"). Android's storage finds folders in any case, so both used one folder and each showed the
        // other's downloads. Folders are now told apart by their exact name; a title whose exact name is taken
        // gets "<title> [<link hash>]". Up to r89 both titles' files may sit in one folder, so only the files named
        // after this anime's episodes count ([listFiles]).
        val tagged = taggedDirName(anime)
        val folders = sourceDirs.flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.isDirectory && it.name != null }
        }
        folders.firstOrNull { it.name == title }?.let { return AnimeFolder(it, shared = true) }
        folders.firstOrNull { it.name == tagged }?.let { return AnimeFolder(it, shared = true) }
        val sameButCase = folders.filter { it.name.equals(title, ignoreCase = true) }
        // Named after neither title exactly (a title whose letter case changed since)
        sameButCase.firstOrNull { it.name.orEmpty() !in twins }?.let { return AnimeFolder(it, shared = true) }
        // The other title's folder, where this one's episodes went before r90
        val counts = nameCountsOf(anime)
        if (counts != null) {
            sameButCase
                .firstOrNull { dir ->
                    dir.listFiles().orEmpty().any { file -> file.name?.let { ownsFile(it, counts) } == true }
                }
                ?.let { return AnimeFolder(it, shared = true) }
        }
        if (!create) return null
        val sourceDir = sourceDirs.firstOrNull()
            ?: eu.kanade.tachiyomi.data.download.SourceFolders.findOrCreate(animeRoot, sourceDirName(anime), match)
            ?: return null
        val name = if (sameButCase.isEmpty()) title else tagged
        return eu.kanade.tachiyomi.data.download.SourceFolders.findOrCreate(sourceDir, name)
            ?.let { AnimeFolder(it, shared = true) }
    }

    /** KMK (r90): the folder name of a title whose name (apart from letter case) another title already uses */
    private fun taggedDirName(anime: Anime): String {
        val hash = anime.url.hashCode().toUInt().toString(16)
        return DiskUtil.buildValidFilename(anime.title, maxBytes = 200) + " [$hash]"
    }

    /**
     * KMK (r90): folder names of other library titles (seasons too) of the same source that equal [title] apart from
     * letter case. Read from the database at most every [LIBRARY_NAMES_TTL_MS]; empty when it can't be read.
     */
    private fun caseTwinNames(anime: Anime, title: String): Set<String> {
        val now = System.currentTimeMillis()
        val names = libraryNames?.takeIf { now - it.first < LIBRARY_NAMES_TTL_MS }?.second
            ?: try {
                runBlocking(Dispatchers.IO) { loadLibraryNames() }.also { libraryNames = now to it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Couldn't read the anime library's titles" }
                return emptySet()
            }
        return names[anime.source].orEmpty()
            .filter { (id, name) -> id != anime.id && name != title && name.equals(title, ignoreCase = true) }
            .mapTo(HashSet()) { it.second }
    }

    /** KMK (r90): per source, (anime id, folder name) for each library title, by its own and its Edit info title */
    private suspend fun loadLibraryNames(): Map<Long, List<Pair<Long, String>>> {
        val animes = (repository.getFavoritesRaw() + repository.getSeasonsOfFavoritesRaw()).distinctBy { it.id }
        return animes
            .flatMap { a ->
                val custom = repository.getCustomInfo(a.id)?.title?.takeIf { it.isNotBlank() }
                listOfNotNull(a.title, custom).distinct().map { a.source to (a.id to DiskUtil.buildValidFilename(it)) }
            }
            .groupBy({ it.first }, { it.second })
    }

    /** KMK (r90): in a shared folder, only the files named after this anime's episodes (none if unknown) */
    private fun listFiles(anime: Anime): Set<String> {
        val folder = findAnimeFolder(anime, create = false) ?: return emptySet()
        val names = folder.dir.listFiles()?.mapNotNull { it.name }?.toSet() ?: return emptySet()
        if (!folder.shared) return names
        val counts = nameCountsOf(anime) ?: return emptySet()
        return names.filterTo(HashSet()) { ownsFile(it, counts) }
    }

    /** Up to r86: `scanlator_name`, shared by two episodes with the same name (they overwrote each other) */
    private fun legacyBaseName(episode: Episode): String {
        val name = listOfNotNull(episode.scanlator?.takeIf { it.isNotBlank() }, episode.name).joinToString("_")
        return DiskUtil.buildValidFilename(name)
    }

    /** KMK (r88): `scanlator_name_`, the part of [episodeBaseName] before the number tag */
    private fun nameStem(episode: Episode): String {
        val name = listOfNotNull(episode.scanlator?.takeIf { it.isNotBlank() }, episode.name).joinToString("_")
        return DiskUtil.buildValidFilename(name, maxBytes = 200) + "_"
    }

    /**
     * KMK (r87): new downloads are `scanlator_name_E<number>` (`_U<hash of the episode link>` when the number is
     * unknown), so episodes with the same name get their own files. Files named the old way are still found
     * ([legacyBaseName]).
     */
    private fun episodeBaseName(episode: Episode): String {
        val number = episode.episodeNumber
        val tag = if (number >= 0) {
            "E" + if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
        } else {
            "U" + episode.url.hashCode().toUInt().toString(16)
        }
        return nameStem(episode) + tag
    }

    // Downloading

    private suspend fun download(item: Item, reportPercent: (Int) -> Unit) = withIOContext {
        // KMK (r88): the percent never steps back (pieces finish out of order; a fallback starts again at 0)
        val onPercent = ParallelVideoDownload.monotonic(reportPercent)
        val source = sourceManager.get(item.anime.source) ?: error("Source not installed")
        // Two downloads of the same anime must not both create its folder
        val dir = dirLock.withLock { animeDir(item.anime, create = true) } ?: error("Can't open the downloads folder")
        val base = episodeBaseName(item.episode)
        val label = labelOf(item)
        val key = pieceKey(item.episode)
        // KMK (r87): the episode number is part of new file names, so same-named episodes don't share one
        AppLog.add('I', LOG_TAG, "$label: saving as \"$base\" (episode number in the name)")
        var lastError: Throwable? = null

        // KMK (r87): the stream picked in Episode options first (already resolved there)
        item.video?.let { picked ->
            AppLog.add('I', LOG_TAG, "$label: downloading the stream picked in Episode options, ${picked.videoTitle}")
            try {
                saveVideo(source, picked, dir, base, key, label, onPercent)
                reload(item.anime)
                return@withIOContext
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                AppLog.add('W', LOG_TAG, "$label: the picked stream failed (${e.message}), picking one automatically")
                lastError = e
                dir.findFile("$base$TMP")?.delete()
            }
        }

        val videos = AnimeVideoFetcher.fetchVideos(source, item.episode.toSEpisode())
        if (videos.isEmpty()) throw lastError ?: IllegalStateException("No videos found")
        AppLog.add('I', LOG_TAG, "$label: picking the stream automatically (${videos.size} found)")
        // Best video first; fall back to the next one when a server fails
        for (candidate in videos.take(MAX_TRIES)) {
            try {
                val video = AnimeVideoFetcher.resolve(source, candidate)
                if (video.videoUrl.isBlank() || video.videoUrl == "null") continue
                saveVideo(source, video, dir, base, key, label, onPercent)
                reload(item.anime)
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

    private suspend fun saveVideo(
        source: AnimeSource,
        video: Video,
        dir: UniFile,
        base: String,
        key: String,
        label: String,
        onPercent: (Int) -> Unit,
    ) {
        val headers = video.headers ?: (source as? AnimeHttpSource)?.headers
            // KMK: Code editor sources send their headers too, as in the player
            ?: (source as? eu.kanade.tachiyomi.jssource.JsAnimeSource)?.headers
            ?: Headers.Builder().build()
        if (isStream(video)) {
            ffmpegDownload(video, headers, dir, base, key, label, onPercent)
        } else {
            httpDownload(source, video, headers, dir, base, key, label, onPercent)
        }
    }

    private fun isStream(video: Video): Boolean {
        val url = video.videoUrl.substringBefore('?').lowercase()
        // KMK: "m3u8" anywhere in the whole address: a relayed stream (HlsRelay) reads "…/p/<id>?u=…&x=list.m3u8"
        return url.endsWith(".m3u8") || url.endsWith(".mpd") || url.contains("/hls/") ||
            video.videoUrl.contains("m3u8", ignoreCase = true) ||
            video.mimeType?.contains("mpegurl", ignoreCase = true) == true
    }

    private suspend fun httpDownload(
        source: AnimeSource,
        video: Video,
        headers: Headers,
        dir: UniFile,
        base: String,
        key: String,
        label: String,
        onPercent: (Int) -> Unit,
    ) {
        val client = (source as? AnimeHttpSource)?.client ?: Injekt.get<NetworkHelper>().client
        // KMK (r87): a server that sends parts gets several connections at once ("Connections per download")
        val connections = ParallelVideoDownload.connections()
        if (connections > 1) {
            val probe = ParallelVideoDownload.probeRanges(client, video.videoUrl, headers, label)
            if (probe != null) {
                val workDir = File(context.cacheDir, "anime_download").apply { mkdirs() }
                val out = File(workDir, "${UUID.randomUUID()}.part")
                try {
                    val saved = ParallelVideoDownload.saveRanges(
                        client, video.videoUrl, headers, probe, out, connections, label, onPercent,
                    )
                    if (saved) {
                        val ext = extensionOf(probe.contentType, video.videoUrl)
                        val tmp = dir.findFile("$base$TMP") ?: dir.createFile("$base$TMP")
                            ?: error("Can't create the file")
                        tmp.openOutputStream().use { output ->
                            out.inputStream().use { it.copyTo(output, DEFAULT_BUFFER) }
                        }
                        dir.findFile("$base.$ext")?.delete()
                        // KMK (r88): a failed rename left only the .tmp, and the episode was marked downloaded
                        if (!tmp.renameTo("$base.$ext")) error("Couldn't rename the saved video")
                        onPercent(100)
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
                // KMK: marked as one, so a list of several qualities is still cut down to the best (forDownload)
                val playlist = video.copy(mimeType = type, videoPageUrl = video.videoPageUrl)
                ffmpegDownload(playlist, headers, dir, base, key, label, onPercent)
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
            val ext = extensionOf(type, video.videoUrl)
            dir.findFile("$base.$ext")?.delete()
            if (!tmp.renameTo("$base.$ext")) error("Couldn't rename the saved video")
        }
    }

    private fun extensionOf(type: String, url: String): String = when {
        type.contains("matroska") || url.contains(".mkv") -> "mkv"
        type.contains("webm") || url.contains(".webm") -> "webm"
        else -> "mp4"
    }

    private suspend fun ffmpegDownload(
        stream: Video,
        headers: Headers,
        dir: UniFile,
        base: String,
        key: String,
        label: String,
        onPercent: (Int) -> Unit,
    ) {
        // KMK: a stream holding several qualities is saved in one (the best), not all of them in one file. Its list
        // is read with the download's headers (the source's when the video has none)
        val video = eu.kanade.tachiyomi.ui.player.loader.HlsVariants.forDownload(stream, headers)
        if (video.videoUrl != stream.videoUrl) {
            AppLog.add('I', "anime", "Download: saving one quality, ${video.videoTitle}")
        }
        // ffmpeg writes to app storage first, then the file is copied into the downloads folder
        val workDir = File(context.cacheDir, "anime_download").apply { mkdirs() }
        val out = File(workDir, "${UUID.randomUUID()}.mkv")
        try {
            // KMK (r87): pieces fetched several at a time, then joined by ffmpeg from the local copy; when that
            // can't be done, ffmpeg reads the stream from its link as before
            if (ParallelVideoDownload.saveHls(video, headers, out, key, label, onPercent)) {
                saveFile(out, dir, base, onPercent)
                return
            }
            val started = System.currentTimeMillis()
            ffmpegFromLink(video, headers, out, onPercent)
            ParallelVideoDownload.discard(key)
            val seconds = (System.currentTimeMillis() - started) / 1000
            AppLog.add('I', LOG_TAG, "$label: saved by ffmpeg from the link in $seconds s")
            saveFile(out, dir, base, onPercent)
        } finally {
            out.delete()
        }
    }

    /** Copies ffmpeg's [out] into the downloads folder as `<base>.mkv` */
    private fun saveFile(out: File, dir: UniFile, base: String, onPercent: (Int) -> Unit) {
        val tmp = dir.findFile("$base$TMP") ?: dir.createFile("$base$TMP") ?: error("Can't create the file")
        tmp.openOutputStream().use { output -> out.inputStream().use { it.copyTo(output, DEFAULT_BUFFER) } }
        dir.findFile("$base.mkv")?.delete()
        if (!tmp.renameTo("$base.mkv")) error("Couldn't rename the saved video")
        onPercent(100)
    }

    /** The way up to r86: ffmpeg reads the stream from its link (one piece at a time) */
    private suspend fun ffmpegFromLink(video: Video, headers: Headers, out: File, onPercent: (Int) -> Unit) {
        val headerBlock = headers.joinToString("") { (name, value) -> "$name: $value\r\n" }
        val duration = probeDuration(video.videoUrl, headerBlock)
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
                null,
                StatisticsCallback { stats ->
                    if (duration > 0) onPercent(((stats.time / 1000.0) * 100 / duration).toInt().coerceIn(0, 99))
                },
            )
            continuation.invokeOnCancellation { session.cancel() }
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
        private const val KEY_PREFIX = "anime_"

        /** KMK (r90): how long the library's titles are kept for [caseTwinNames] */
        private const val LIBRARY_NAMES_TTL_MS = 10_000L

        /** KMK (r88): the tag [episodeBaseName] ends with ("E12", "E12.5", "U1a2b3c") */
        private val NUMBER_TAG = Regex("E[0-9][0-9.]*|U[0-9a-f]+")

        private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "webm", "ts", "m4v", "mov", "avi")

        /**
         * KMK (r88): an episode video in an anime's download folder (not the cover, subtitles, dot-files or an
         * unfinished .tmp/.part)
         */
        fun isEpisodeVideo(name: String): Boolean =
            !name.startsWith('.') && name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
        private const val MAX_TRIES = 3
        const val MAX_CONCURRENT = 5
        private const val LOG_TAG = "download"
        private const val DEFAULT_BUFFER = 64 * 1024
    }
}
