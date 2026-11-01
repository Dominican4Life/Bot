package eu.kanade.tachiyomi.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Debug
import android.os.StatFs
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import coil3.imageLoader
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.pairsync.PairSync
import eu.kanade.tachiyomi.pairsync.SyncPhase
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.data.DbStats
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * KMK (r97): The Live tab's tick (design §2.1): one snapshot per sampling interval while a graph is on screen,
 * into direct-buffer sample rings (fine 300 points, coarse 1 h / 24 h / 7 d), published as [SampleWindow]s only
 * for the (resource, window) keys someone collects. A 1 s strip-only tick runs while a strip is composed and Live
 * is not. Nothing runs when nothing is visible; `App.onStop` stops it too.
 */
internal object LiveSampler {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope

    val strip = MutableStateFlow(StatusStrip())
    val memory = MutableStateFlow(MemoryBreakdown())
    val workers = MutableStateFlow(WorkerSnapshot())
    val tables = MutableStateFlow(LiveTables())
    val player = MutableStateFlow<PlayerLive?>(null)

    private val liveRefs = AtomicInteger(0)
    private val stripRefs = AtomicInteger(0)
    private val _playerVisible = MutableStateFlow(false)

    /** True while the Live page is composed (PlayerViewModel's poll runs on this or the player trace) */
    val playerVisibleFlow: StateFlow<Boolean> = _playerVisible.asStateFlow()

    private var tickJob: Job? = null
    private var workJob: Job? = null
    private val lock = Any()

    /** Set by the engine when the app goes to the background; the tick stops and resumes with the next visibility */
    @Volatile
    private var background = false

    val rings = SampleRings()

    fun init(app: Application, scope: CoroutineScope) {
        this.app = app
        this.scope = scope
        // The coarse rings come back from samples.bin on the IO worker, not on the main thread at start
        DiagIo.post { runCatching { rings.load(File(app.cacheDir, DiagPaths.SAMPLES_BIN)) } }
    }

    // --- visibility ---------------------------------------------------------------------------------------------

    fun setLiveVisible(visible: Boolean) {
        val n = if (visible) liveRefs.incrementAndGet() else liveRefs.decrementAndGet().coerceAtLeast(0)
        if (!visible && n == 0) liveRefs.set(0)
        _playerVisible.value = n > 0
        update()
    }

    fun setStripVisible(visible: Boolean) {
        val n = if (visible) stripRefs.incrementAndGet() else stripRefs.decrementAndGet()
        if (n < 0) stripRefs.set(0)
        update()
    }

    fun onFloatingChanged() = update()

    fun onBackground() {
        background = true
        update()
        DiagIo.post { runCatching { rings.save(File(app.cacheDir, DiagPaths.SAMPLES_BIN)) } }
    }

    fun onForeground() {
        background = false
        update()
    }

    private val fullWanted: Boolean get() = !background && (liveRefs.get() > 0 || FloatingLogState.visible.value)
    private val stripWanted: Boolean get() = !background && stripRefs.get() > 0

    /** MemoryLog's 10 s tick asks before reading the Coil size and the JS runtimes' memory */
    fun wantsMemoryDetail(): Boolean = fullWanted || stripWanted

    private fun update() {
        synchronized(lock) {
            val want = fullWanted || stripWanted
            if (want && tickJob?.isActive != true) {
                tickJob = scope.launch { loop() }
            } else if (!want) {
                tickJob?.cancel()
                tickJob = null
            }
            val wantWork = fullWanted
            if (wantWork && workJob?.isActive != true) {
                workJob = scope.launch { collectWork() }
            } else if (!wantWork) {
                workJob?.cancel()
                workJob = null
            }
        }
    }

    private suspend fun loop() {
        var n = 0
        while (currentCoroutineIsActive()) {
            val full = fullWanted
            val interval = if (full) LogCapture.sampleMs else 1_000L
            delay(interval)
            if (!currentCoroutineIsActive()) break
            runCatching {
                if (fullWanted) tick(n++, interval) else stripTick()
            }
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean = kotlinx.coroutines.currentCoroutineContext().isActive

    // --- the tick --------------------------------------------------------------------------------------------------

    private var lastBytesIn = -1L
    private var lastBytesOut = 0L
    private var lastSlots = LongArray(NetStats.SLOTS)
    private var lastQueries = -1L
    private var lastDropped = -1L
    private var lastTickAt = 0L
    private var jsRows: List<JsEngineRow> = emptyList()
    private var jsMb = 0f
    private var jsBusy = 0
    private var threadArray = arrayOfNulls<Thread>(192)
    private var dbSizeAt = 0L
    private var dbBytes = 0L
    private var walBytes = 0L
    private var netMax = 4f
    private var lastTrimAt: Long? = null
    private var lastTrimText: String? = null
    private var lastPssAt = 0L
    private var lastPssMb = 0
    private var deviceTotalMb = -1

    /** MemoryLog.trim tells the sampler (shown on the Memory page) */
    fun noteTrim(text: String) {
        lastTrimAt = System.currentTimeMillis()
        lastTrimText = text
    }

    private suspend fun tick(n: Int, intervalMs: Long) {
        val now = System.currentTimeMillis()
        val dt = if (lastTickAt == 0L) intervalMs else (now - lastTickAt).coerceAtLeast(1L)
        lastTickAt = now
        val seconds = dt / 1000f

        // Memory
        val rt = Runtime.getRuntime()
        val heapUsed = rt.totalMemory() - rt.freeMemory()
        val heapMax = rt.maxMemory()
        val native = Debug.getNativeHeapAllocatedSize()
        val cache = runCatching { app.imageLoader.memoryCache }.getOrNull()
        val imagesBytes = cache?.size ?: 0L
        val imagesMax = cache?.maxSize ?: 0L
        // JS: through tryLock, and every 4th tick at 0.25 s
        if (intervalMs >= 1_000L || n % 4 == 0) {
            val stats = runCatching { Injekt.get<JsSourceManager>().liveStats() }.getOrDefault(emptyList())
            jsRows = stats
            jsMb = stats.sumOf { it.mb.toDouble() }.toFloat()
            jsBusy = stats.count { it.busy }
        }
        val jsBytes = (jsMb * MB).toLong()
        val heapMb = (heapUsed / MB).toInt()
        val heapMaxMb = (heapMax / MB).toInt()
        val nativeMb = (native / MB).toInt()
        // The log ring, the sample rings, the trace rings' written bytes (untouched pages cost nothing), the request ring
        val diagBytes = DiagBudget.LOG_RING_BYTES + rings.bytes + TraceWriter.totalBytes() + 128L * 1024
        rings.write(
            Resource.MEMORY, now, intervalMs,
            floatArrayOf(
                ((heapUsed - imagesBytes).coerceAtLeast(0L) / MB).toFloat(),
                (imagesBytes / MB).toFloat(),
                jsMb,
                ((native - jsBytes).coerceAtLeast(0L) / MB).toFloat(),
            ),
        )

        // Workers
        val w = countThreads()
        // One honest "busy workers" series (Default and IO share the DefaultDispatcher-worker threads) + JS
        rings.write(Resource.WORKERS, now, intervalMs, floatArrayOf(w.ioBusy.toFloat(), jsBusy.toFloat()))

        // Network
        val bytesIn = NetStats.bytesIn.get()
        val bytesOut = NetStats.bytesOut.get()
        val first = lastBytesIn < 0
        val inDelta = if (first) 0L else bytesIn - lastBytesIn
        val outDelta = if (first) 0L else bytesOut - lastBytesOut
        lastBytesIn = bytesIn
        lastBytesOut = bytesOut
        val netValues = FloatArray(NetStats.SLOTS + 1)
        for (s in 0 until NetStats.SLOTS) {
            val v = NetStats.bytesBySlot.get(s)
            val d = if (first) 0L else v - lastSlots[s]
            lastSlots[s] = v
            netValues[s] = (d.coerceAtLeast(0L) / MB.toFloat()) / seconds
        }
        netValues[NetStats.SLOTS] = (outDelta.coerceAtLeast(0L) / MB.toFloat()) / seconds
        rings.write(Resource.NETWORK, now, intervalMs, netValues)
        val mbps = (inDelta.coerceAtLeast(0L) / MB.toFloat()) / seconds
        if (mbps > netMax) netMax = niceCeiling(mbps)

        // Database
        val queries = DbStats.queries.get()
        val qps = if (lastQueries < 0) 0f else (queries - lastQueries) / seconds
        lastQueries = queries
        if (now - dbSizeAt > 5_000L) {
            dbSizeAt = now
            runCatching {
                val db = app.getDatabasePath(dbName())
                dbBytes = db.length()
                walBytes = File(db.path + "-wal").length()
            }
        }
        rings.write(Resource.DATABASE, now, intervalMs, floatArrayOf(qps, walBytes / MB.toFloat()))

        // Player
        val p = player.value
        if (p != null) {
            val droppedRate = if (lastDropped < 0) 0f else (p.droppedFrames - lastDropped).coerceAtLeast(0L) / seconds
            lastDropped = p.droppedFrames
            rings.write(Resource.PLAYER, now, intervalMs, floatArrayOf(p.demuxerCacheS, droppedRate))
        } else {
            lastDropped = -1L
        }

        // Health score → strip
        val percent = if (heapMax > 0) (heapUsed * 100 / heapMax).toInt() else 0
        val trimRecently = lastTrimAt?.let { now - it < 600_000L } == true
        val score = Health.score(percent, Watchdogs.ioStarved, trimRecently, jsRows.count { !it.busy })
        strip.value = StatusStrip(
            health = score.state,
            sentence = score.sentence,
            action = score.action,
            workersBusy = w.ioBusy,
            workersTotal = w.ioTotal,
            heapMb = heapMb,
            heapMaxMb = heapMaxMb,
            nativeMb = nativeMb,
            netMbps = mbps,
            netMaxMbps = netMax,
            sampleMs = intervalMs,
            lastStallText = Health.lastStallText,
        )
        if (deviceTotalMb < 0) {
            // The device's total memory doesn't change: one read
            val am = runCatching { app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }.getOrNull()
            val memInfo = am?.let { ActivityManager.MemoryInfo().also { mi -> runCatching { it.getMemoryInfo(mi) } } }
            deviceTotalMb = ((memInfo?.totalMem ?: 0L) / MB).toInt()
        }
        // Debug.getPss() walks /proc/self/smaps (milliseconds): at most every 10 s, and only for a Memory graph
        if (memorySubscribed() && now - lastPssAt >= 10_000L) {
            lastPssAt = now
            lastPssMb = runCatching { (Debug.getPss() / 1024).toInt() }.getOrDefault(lastPssMb)
        }
        val caches = eu.kanade.tachiyomi.util.system.MemoryLog.cacheStats()
        memory.value = MemoryBreakdown(
            javaMb = heapMb,
            javaMaxMb = heapMaxMb,
            imagesMb = (imagesBytes / MB).toInt(),
            imagesMaxMb = (imagesMax / MB).toInt(),
            jsMb = jsMb.toInt(),
            jsCapMb = 128,
            nativeMb = nativeMb,
            diagMb = (diagBytes / MB).toInt(),
            rssMb = lastPssMb,
            deviceTotalMb = deviceTotalMb,
            trimAtMb = (heapMax * 3 / 4 / MB).toInt(),
            lastTrimAt = lastTrimAt,
            lastTrimText = lastTrimText,
            browseTabs = caches.browseTabs,
            folderListings = caches.folderListings,
            animatedTiles = caches.animatedTiles,
            jsPages = caches.jsPages,
            jsPageTextMb = (caches.jsPageTextBytes / MB).toInt(),
            jsDomMb = (caches.jsDomBytes / MB).toInt(),
            engines = jsRows,
        )
        workers.value = w.copy(
            jsBusy = jsBusy,
            starved = Watchdogs.ioStarved,
            lastDump = Watchdogs.lastDump,
            lastStallAt = Watchdogs.lastStallAt(),
            lastStallText = Health.lastStallText,
        )
        publishTables(now)
        publishWindows(now, intervalMs)
    }

    /** Strip only: Runtime reads, native size, one enumerate, the net delta (< 0.3 ms) */
    private fun stripTick() {
        val now = System.currentTimeMillis()
        val dt = if (lastTickAt == 0L) 1_000L else (now - lastTickAt).coerceAtLeast(1L)
        lastTickAt = now
        val rt = Runtime.getRuntime()
        val heapUsed = rt.totalMemory() - rt.freeMemory()
        val heapMax = rt.maxMemory()
        val bytesIn = NetStats.bytesIn.get()
        val inDelta = if (lastBytesIn < 0) 0L else bytesIn - lastBytesIn
        lastBytesIn = bytesIn
        lastBytesOut = NetStats.bytesOut.get()
        for (s in 0 until NetStats.SLOTS) lastSlots[s] = NetStats.bytesBySlot.get(s)
        lastQueries = DbStats.queries.get()
        val mbps = (inDelta.coerceAtLeast(0L) / MB.toFloat()) / (dt / 1000f)
        if (mbps > netMax) netMax = niceCeiling(mbps)
        val w = countThreads()
        val percent = if (heapMax > 0) (heapUsed * 100 / heapMax).toInt() else 0
        val trimRecently = lastTrimAt?.let { now - it < 600_000L } == true
        val score = Health.score(percent, Watchdogs.ioStarved, trimRecently, 0)
        strip.value = StatusStrip(
            health = score.state,
            sentence = score.sentence,
            action = score.action,
            workersBusy = w.ioBusy,
            workersTotal = w.ioTotal,
            heapMb = (heapUsed / MB).toInt(),
            heapMaxMb = (heapMax / MB).toInt(),
            nativeMb = (Debug.getNativeHeapAllocatedSize() / MB).toInt(),
            netMbps = mbps,
            netMaxMbps = netMax,
            sampleMs = 1_000L,
            lastStallText = Health.lastStallText,
        )
    }

    private fun countThreads(): WorkerSnapshot {
        val group = rootGroup(Thread.currentThread().threadGroup ?: return WorkerSnapshot())
        var count = group.enumerate(threadArray, true)
        while (count >= threadArray.size && threadArray.size < 2048) {
            threadArray = arrayOfNulls(threadArray.size * 2)
            count = group.enumerate(threadArray, true)
        }
        // KMK (r98): the pool is the IO limit (64), not the highest worker index. IO and Default share the
        // DefaultDispatcher-worker threads (a release build had 72 of them, so "2/75"); a thread's name doesn't say
        // which dispatcher it serves, and the scheduler keeps idle workers for 60 s. So the 64 slots are filled by
        // state — running, then blocked, then parked — and threads past 64 (idle Default/IO workers) are not slots.
        var busy = 0
        var parked = 0
        var blocked = 0
        for (i in 0 until count) {
            val t = threadArray[i] ?: continue
            if (!t.name.startsWith("DefaultDispatcher-worker-")) continue
            when (t.state) {
                Thread.State.RUNNABLE -> busy++
                Thread.State.BLOCKED -> blocked++
                else -> parked++
            }
        }
        val total = DiagBudget.IO_POOL_DEFAULT
        busy = busy.coerceAtMost(total)
        blocked = blocked.coerceAtMost(total - busy)
        parked = parked.coerceAtMost(total - busy - blocked)
        val slots = ByteArray(total)
        java.util.Arrays.fill(slots, 0, busy, 1.toByte())
        java.util.Arrays.fill(slots, busy, busy + blocked, 3.toByte())
        java.util.Arrays.fill(slots, busy + blocked, busy + blocked + parked, 2.toByte())
        java.util.Arrays.fill(threadArray, 0, count, null)
        val cores = Runtime.getRuntime().availableProcessors()
        return WorkerSnapshot(
            ioBusy = busy,
            ioParked = parked,
            ioBlocked = blocked,
            ioTotal = total,
            // Default and IO share the same worker threads: there is no separate "Default busy" count to give
            defaultBusy = busy.coerceAtMost(cores),
            defaultTotal = cores,
            threads = count,
            slots = slots,
        )
    }

    private fun rootGroup(g: ThreadGroup): ThreadGroup {
        var root = g
        while (true) root = root.parent ?: return root
    }

    private fun dbName(): String = runCatching {
        if (Injekt.get<eu.kanade.tachiyomi.core.security.SecurityPreferences>().encryptDatabase().get()) {
            mihon.core.archive.CbzCrypto.DATABASE_NAME
        } else {
            "tachiyomi.db"
        }
    }.getOrDefault("tachiyomi.db")

    // --- tables ----------------------------------------------------------------------------------------------------

    @Volatile
    private var workInfos: List<WorkInfo> = emptyList()

    private suspend fun collectWork() {
        runCatching {
            app.workManager.getWorkInfosFlow(WorkQuery.fromStates(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED))
                .collectLatest { workInfos = it }
        }
    }

    private val jobNames = listOf(
        "LibraryUpdateJob" to ("library-update" to "Library update"),
        "AnimeLibraryUpdateJob" to ("anime-library-update" to "Anime library update"),
        "BackupCreateJob" to ("backup" to "Backup"),
        "BackupRestoreJob" to ("restore" to "Restore"),
        "SyncDataJob" to ("sync-data" to "Sync data"),
        "MetadataUpdateJob" to ("metadata" to "Metadata update"),
        "AppUpdateDownloadJob" to ("app-update" to "App update"),
        "EHentaiUpdateWorker" to ("ehentai" to "E-Hentai update"),
        "AnimeMigration" to ("anime-migration" to "Anime migration"),
        "Migration" to ("migration" to "Migration"),
        "LabRetest" to ("source-lab-retest" to "Source Lab re-test"),
    )

    private fun publishTables(now: Long) {
        val running = ArrayList<JobRow>()
        val infos = workInfos
        for ((tag, pair) in jobNames) {
            val (id, name) = pair
            val info = infos.firstOrNull { wi -> wi.tags.any { it.endsWith(tag) } } ?: continue
            if (running.any { it.id == id }) continue
            val done = info.progress.getInt("done", -1)
            val total = info.progress.getInt("total", -1)
            val progress = if (done >= 0 && total > 0) "$done / $total" else if (info.state == WorkInfo.State.ENQUEUED) "queued" else "running"
            running.add(JobRow(id, name, null, progress, JobState.RUNNING, null))
        }
        runCatching {
            val dm = Injekt.get<DownloadManager>()
            val queue = dm.queueState.value
            if (dm.isRunning || queue.isNotEmpty()) {
                running.add(
                    JobRow(
                        "downloader", "Downloader", null, "${queue.size} chapter${if (queue.size == 1) "" else "s"}",
                        if (dm.isRunning) JobState.RUNNING else JobState.WARN, Resource.NETWORK,
                    ),
                )
            }
        }
        runCatching {
            val adm = Injekt.get<AnimeDownloadManager>()
            val queue = adm.queue.value
            if (queue.isNotEmpty()) {
                running.add(
                    JobRow(
                        "anime-downloader", "Anime downloader", null,
                        "${queue.size} episode${if (queue.size == 1) "" else "s"}", JobState.RUNNING, Resource.NETWORK,
                    ),
                )
            }
        }
        runCatching {
            val s = PairSync.status.value
            if (s.busy || s.phase != SyncPhase.Idle) {
                val text = s.applyText ?: s.step ?: s.phase.name
                running.add(JobRow("pair-sync", "Pair sync", s.peerAddress, text, JobState.RUNNING, null))
            }
        }
        val engines = ArrayList<JobRow>()
        engines.add(
            JobRow(
                "js-sources", "JS sources", "${jsRows.size} running", "${jsMb.toInt()} MB native",
                if (jsBusy > 0) JobState.RUNNING else JobState.IDLE, Resource.MEMORY,
            ),
        )
        val m = memory.value
        engines.add(JobRow("image-cache", "Image cache", null, "${m.imagesMb} / ${m.imagesMaxMb} MB", JobState.OK, Resource.MEMORY))
        val storage = storageSnapshot(now)
        engines.add(
            JobRow(
                "storage", "Storage",
                storage.downloadsBytes?.let { "downloads ${gb(it)}" } ?: "indexing…",
                "${gb(storage.freeBytes)} free",
                if (storage.computedAt == null) JobState.RUNNING else JobState.OK, null,
            ),
        )
        engines.add(JobRow("database", "Database", null, "${mb(dbBytes)} · WAL ${mb(walBytes)}", JobState.OK, Resource.DATABASE))
        engines.add(
            JobRow(
                "relay", "HLS relay", null, "${HlsTrace.served.get()} served · ${mb(HlsTrace.bytes.get())}",
                if (HlsTrace.errors.get() > 0) JobState.WARN else JobState.IDLE, Resource.PLAYER,
            ),
        )
        tables.value = LiveTables(running, engines, storage)
    }

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    private fun mb(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024))

    // --- storage (10-min TTL tree walk on IO) --------------------------------------------------------------------

    @Volatile
    private var storageWalk: Triple<Long?, Long?, Long?>? = null

    @Volatile
    private var storageWalkAt = 0L

    @Volatile
    private var storageWalking = false

    private fun storageSnapshot(now: Long): StorageSnapshot {
        val free = runCatching { StatFs(app.filesDir.path).availableBytes }.getOrDefault(0L)
        if (now - storageWalkAt > 600_000L && !storageWalking) {
            storageWalking = true
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val downloads = runCatching {
                        Injekt.get<StorageManager>().getDownloadsDirectory()?.let { sizeOf(it) }
                    }.getOrNull()
                    val cache = runCatching { dirSize(app.cacheDir) + (app.externalCacheDir?.let { dirSize(it) } ?: 0L) }.getOrNull()
                    val reader = runCatching { dirSize(File(app.cacheDir, "chapter_disk_cache")) }.getOrNull()
                    storageWalk = Triple(downloads, cache, reader)
                    storageWalkAt = System.currentTimeMillis()
                }
                storageWalking = false
            }
        }
        val walk = storageWalk
        return StorageSnapshot(
            downloadsBytes = walk?.first,
            cacheBytes = walk?.second,
            readerCacheBytes = walk?.third,
            freeBytes = free,
            dbBytes = dbBytes,
            walBytes = walBytes,
            computedAt = storageWalkAt.takeIf { it > 0L && walk != null },
        )
    }

    /** Forces the next tick to walk the download tree again */
    fun invalidateStorage() {
        storageWalkAt = 0L
    }

    private fun sizeOf(file: com.hippo.unifile.UniFile): Long {
        if (file.isFile) return file.length()
        var total = 0L
        file.listFiles()?.forEach { total += sizeOf(it) }
        return total
    }

    private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // --- windows ----------------------------------------------------------------------------------------------------

    private val windows = HashMap<Pair<Resource, TimeWindow>, MutableStateFlow<SampleWindow>>()

    fun samples(resource: Resource, window: TimeWindow): StateFlow<SampleWindow> = synchronized(windows) {
        windows.getOrPut(resource to window) { MutableStateFlow(buildWindow(resource, window, System.currentTimeMillis())) }
    }

    private fun publishWindows(now: Long, intervalMs: Long) {
        val keys = synchronized(windows) { windows.entries.filter { it.value.subscriptionCount.value > 0 }.map { it.key to it.value } }
        for ((key, flow) in keys) flow.value = buildWindow(key.first, key.second, now)
    }

    /** True while a Memory graph (any window) has a collector */
    private fun memorySubscribed(): Boolean = synchronized(windows) {
        windows.entries.any { it.key.first == Resource.MEMORY && it.value.subscriptionCount.value > 0 }
    }

    /** A window built from the rings right now (also used by the report's live.json and by cursor()) */
    fun buildWindow(resource: Resource, window: TimeWindow, now: Long): SampleWindow {
        val read = rings.read(resource, window, now)
        val stepMs = read.stepMs
        val markers = Health.markers.value.filter { it.timeMs >= read.startMs && it.timeMs <= now }
        val p = player.value
        return when (resource) {
            Resource.NETWORK -> {
                val series = ArrayList<SampleSeries>()
                var peak = 0f
                for (s in 0 until NetStats.SLOTS) {
                    val values = read.series[s]
                    val name = when (s) {
                        NetStats.SLOT_COVERS -> "covers CDN"
                        NetStats.SLOT_OTHER -> "other"
                        else -> NetStats.slotName(s) ?: continue
                    }
                    val current = values.lastOrNull { !it.isNaN() } ?: 0f
                    series.add(SampleSeries("net-$s", name, NetStats.colorOf(s), values, current))
                }
                for (i in read.series[0].indices) {
                    var sum = 0f
                    for (s in series) {
                        val v = s.values[i]
                        if (!v.isNaN()) sum += v
                    }
                    if (sum > peak) peak = sum
                }
                val upload = read.series[NetStats.SLOTS]
                val total = series.sumOf { it.current.toDouble() }.toFloat()
                SampleWindow(
                    resource, window, read.startMs, stepMs, series,
                    SampleSeries("upload", "upload", ColorKey.UPLOAD, upload, upload.lastOrNull { !it.isNaN() } ?: 0f),
                    maxOf(4f, niceCeiling(peak)),
                    "${"%.2f".format(total)} MB/s",
                    "${NetStats.calls.get()} requests · ${mb(NetStats.bytesIn.get())} this run",
                    markers,
                )
            }
            Resource.MEMORY -> {
                val names = listOf(
                    "heap" to ColorKey.HEAP,
                    "images" to ColorKey.IMAGES,
                    "JS" to ColorKey.JS,
                    "native" to ColorKey.NATIVE,
                )
                val series = names.mapIndexed { i, (name, color) ->
                    val values = read.series[i]
                    SampleSeries("mem-$i", name, color, values, values.lastOrNull { !it.isNaN() } ?: 0f)
                }
                val maxMb = (Runtime.getRuntime().maxMemory() / MB).toFloat()
                val trim = FloatArray(read.series[0].size) { maxMb * 0.75f }
                val m = memory.value
                SampleWindow(
                    resource, window, read.startMs, stepMs, series,
                    SampleSeries(
                        "trim",
                        "trim at ${(maxMb * 0.75f).toInt()} MB",
                        ColorKey.TRIM_LINE,
                        trim,
                        maxMb * 0.75f,
                    ),
                    maxMb,
                    "${m.javaMb} / ${m.javaMaxMb} MB",
                    "${if (m.javaMaxMb > 0) m.javaMb * 100 / m.javaMaxMb else 0} % of ${m.javaMaxMb} · native ${m.nativeMb}",
                    markers,
                )
            }
            Resource.WORKERS -> {
                val names = listOf(
                    "busy workers" to ColorKey.IO_BUSY,
                    "JS busy" to ColorKey.JS_BUSY,
                )
                val series = names.mapIndexed { i, (name, color) ->
                    val values = read.series[i]
                    SampleSeries("work-$i", name, color, values, values.lastOrNull { !it.isNaN() } ?: 0f)
                }
                val w = workers.value
                SampleWindow(
                    resource, window, read.startMs, stepMs, series, null, w.ioTotal.toFloat(),
                    "${w.ioBusy} / ${w.ioTotal} workers busy",
                    "${w.ioParked} parked · ${w.ioBlocked} blocked · JS ${w.jsBusy} busy · ${w.threads} threads",
                    markers,
                )
            }
            Resource.DATABASE -> {
                val q = read.series[0]
                val wal = read.series[1]
                val peak = q.filter { !it.isNaN() }.maxOrNull() ?: 0f
                SampleWindow(
                    resource, window, read.startMs, stepMs,
                    listOf(SampleSeries("db-q", "queries/s", ColorKey.DB_QUERIES, q, q.lastOrNull { !it.isNaN() } ?: 0f)),
                    SampleSeries("db-wal", "WAL MB", ColorKey.DB_WAL, wal, wal.lastOrNull { !it.isNaN() } ?: 0f),
                    maxOf(60f, niceCeiling(peak)),
                    "${(q.lastOrNull { !it.isNaN() } ?: 0f).toInt()} q/s",
                    "${mb(dbBytes)} · WAL ${mb(walBytes)}",
                    markers,
                )
            }
            Resource.PLAYER -> {
                val cache = read.series[0]
                val dropped = read.series[1]
                SampleWindow(
                    resource, window, read.startMs, stepMs,
                    if (p == null) emptyList() else listOf(SampleSeries("pl-cache", "demuxer cache s", ColorKey.PLAYER_CACHE, cache, p.demuxerCacheS)),
                    if (p == null) null else SampleSeries(
                        "pl-drop",
                        "dropped/s",
                        ColorKey.PLAYER_DROPPED,
                        dropped,
                        dropped.lastOrNull { !it.isNaN() } ?: 0f,
                    ),
                    maxOf(30f, niceCeiling(cache.filter { !it.isNaN() }.maxOrNull() ?: 0f)),
                    if (p == null) "No episode playing" else "${"%.1f".format(p.demuxerCacheS)} s cached",
                    p?.let { "${it.title} · ${it.width}×${it.height} · ${it.vcodec ?: "?"} · ${it.hwdec ?: "sw"}" } ?: "",
                    markers,
                )
            }
        }
    }

    private var cursorCache: Triple<Resource, TimeWindow, SampleWindow>? = null
    private var cursorCacheAt = 0L
    private var cursorRows: List<NetRequestRow>? = null
    private var cursorRowsAt = 0L

    /** Values at the nearest sample to [timeMs] (and for Network the requests of that step) */
    fun cursor(resource: Resource, window: TimeWindow, timeMs: Long): CursorInfo {
        // A scrub asks many times a second: the window and the request rows are rebuilt at most once per 250 ms
        val now = System.currentTimeMillis()
        val w = synchronized(windows) {
            val cached = cursorCache
            if (cached != null && cached.first == resource && cached.second == window && now - cursorCacheAt < 250L) {
                cached.third
            } else {
                buildWindow(resource, window, now).also {
                    cursorCache = Triple(resource, window, it)
                    cursorCacheAt = now
                }
            }
        }
        val step = w.stepMs.coerceAtLeast(1L)
        val idx = ((timeMs - w.startMs) / step).toInt().coerceIn(0, (w.series.firstOrNull()?.values?.size ?: 1) - 1)
        val per = w.series.map { it to (it.values.getOrNull(idx)?.takeIf { v -> !v.isNaN() } ?: 0f) }
        val total = per.sumOf { it.second.toDouble() }.toFloat()
        val at = w.startMs + idx * step
        val requests = if (resource == Resource.NETWORK) {
            val rows = synchronized(windows) {
                cursorRows?.takeIf { now - cursorRowsAt < 250L } ?: NetStats.rows().also {
                    cursorRows = it
                    cursorRowsAt = now
                }
            }
            rows.filter { it.timeMs in at until at + step }
        } else {
            emptyList()
        }
        return CursorInfo(
            at,
            total,
            resource.unit,
            per,
            requests,
            w.overlay?.let { "${it.label}: ${it.values.getOrNull(idx) ?: 0f}" },
        )
    }

    /** MemoryLog's 10 s tick (always on): the coarse memory rings exist without the screen open */
    fun memoryTick(heapMb: Long, imagesMb: Long, jsMb: Float, nativeMb: Long) {
        if (fullWanted) return // the full tick writes them already
        runCatching {
            rings.writeCoarse(
                Resource.MEMORY, System.currentTimeMillis(),
                floatArrayOf(
                    (heapMb - imagesMb).coerceAtLeast(0L).toFloat(),
                    imagesMb.toFloat(),
                    jsMb,
                    (nativeMb - jsMb.toLong()).coerceAtLeast(0L).toFloat(),
                ),
            )
        }
    }

    private fun niceCeiling(v: Float): Float {
        if (v <= 0f) return 1f
        val mag = Math.pow(10.0, Math.floor(Math.log10(v.toDouble()))).toFloat()
        val n = v / mag
        val nice = when {
            n <= 1f -> 1f
            n <= 2f -> 2f
            n <= 2.5f -> 2.5f
            n <= 4f -> 4f
            n <= 5f -> 5f
            else -> 10f
        }
        return nice * mag
    }

    private const val MB = 1024L * 1024L
}

/**
 * KMK (r97): The sample rings: one direct float buffer holding, per series, a fine ring (300 points at the sampling
 * step) and three coarse rings (1 h / 10 s, 24 h / 5 min, 7 d / 1 h). Coarse slots carry a step index so a gap
 * (screen closed) reads as NaN. Persisted to `samples.bin` on stop.
 */
internal class SampleRings {

    private val seriesCount = mapOf(
        Resource.NETWORK to NetStats.SLOTS + 1,
        Resource.MEMORY to 4,
        Resource.WORKERS to 2,
        Resource.DATABASE to 2,
        Resource.PLAYER to 2,
    )
    private val coarse = listOf(TimeWindow.H1, TimeWindow.H24, TimeWindow.D7)
    private val fineLen = 300
    private fun coarseLen(w: TimeWindow): Int = (w.seconds * 1000 / w.stepMs).toInt()
    private val perSeries = fineLen + coarse.sumOf { coarseLen(it) }
    private val totalFloats = seriesCount.values.sum() * perSeries
    private val buffer: FloatBuffer = ByteBuffer.allocateDirect(totalFloats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    val bytes: Long get() = totalFloats * 4L

    private class Fine(var count: Int = 0, var next: Int = 0, var lastMs: Long = 0L, var stepMs: Long = 1_000L)

    private class Coarse(n: Int) {
        val stamps = IntArray(n) { Int.MIN_VALUE }
        var accIdx = Int.MIN_VALUE
        var accSum: FloatArray = FloatArray(0)
        var accN = 0
    }

    private val fine = Resource.entries.associateWith { Fine() }
    private val coarseState = Resource.entries.associateWith { r -> coarse.associateWith { Coarse(coarseLen(it)) } }
    private val base = HashMap<Resource, Int>().also { map ->
        var at = 0
        for (r in Resource.entries) {
            map[r] = at
            at += (seriesCount[r] ?: 0) * perSeries
        }
    }

    private fun seriesBase(r: Resource, s: Int): Int = (base[r] ?: 0) + s * perSeries
    private fun coarseOffset(w: TimeWindow): Int = fineLen + coarse.takeWhile { it != w }.sumOf { coarseLen(it) }

    class Read(val startMs: Long, val stepMs: Long, val series: List<FloatArray>)

    @Synchronized
    fun write(r: Resource, now: Long, stepMs: Long, values: FloatArray) {
        val f = fine[r] ?: return
        val n = seriesCount[r] ?: return
        if (f.stepMs != stepMs) {
            // A new step changes the x scale: start over
            f.count = 0
            f.next = 0
            f.stepMs = stepMs
        } else if (f.count > 0 && now - f.lastMs > stepMs * 5 / 2) {
            // A gap (screen closed for a while): pad it with NaN so the graph shows a hole, not a jump
            val missing = ((now - f.lastMs) / stepMs - 1).coerceIn(0L, fineLen.toLong()).toInt()
            for (k in 0 until missing) {
                for (s in 0 until n) buffer.put(seriesBase(r, s) + f.next, Float.NaN)
                f.next = (f.next + 1) % fineLen
                if (f.count < fineLen) f.count++
            }
        }
        for (s in 0 until n) buffer.put(seriesBase(r, s) + f.next, values.getOrElse(s) { Float.NaN })
        f.next = (f.next + 1) % fineLen
        if (f.count < fineLen) f.count++
        f.lastMs = now
        writeCoarseLocked(r, now, values)
    }

    @Synchronized
    fun writeCoarse(r: Resource, now: Long, values: FloatArray) = writeCoarseLocked(r, now, values)

    private fun writeCoarseLocked(r: Resource, now: Long, values: FloatArray) {
        val n = seriesCount[r] ?: return
        val states = coarseState[r] ?: return
        for (w in coarse) {
            val c = states[w] ?: continue
            val idx = (now / w.stepMs).toInt()
            if (c.accIdx != idx) {
                if (c.accN > 0 && c.accIdx != Int.MIN_VALUE) commit(r, w, c, n)
                c.accIdx = idx
                c.accSum = FloatArray(n)
                c.accN = 0
            }
            for (s in 0 until n) {
                val v = values.getOrElse(s) { Float.NaN }
                if (!v.isNaN()) c.accSum[s] += v
            }
            c.accN++
        }
    }

    private fun commit(r: Resource, w: TimeWindow, c: Coarse, n: Int) {
        val len = coarseLen(w)
        val slot = Math.floorMod(c.accIdx, len)
        val off = coarseOffset(w)
        for (s in 0 until n) buffer.put(seriesBase(r, s) + off + slot, c.accSum[s] / c.accN)
        c.stamps[slot] = c.accIdx
    }

    @Synchronized
    fun read(r: Resource, w: TimeWindow, now: Long): Read {
        val n = seriesCount[r] ?: 0
        if (w.isFine) {
            val f = fine[r] ?: return Read(now, 1_000L, List(n) { FloatArray(0) })
            val points = if (w == TimeWindow.M1) (60_000L / f.stepMs).toInt().coerceIn(1, fineLen) else fineLen
            val count = f.count.coerceAtMost(points)
            val start = Math.floorMod(f.next - count, fineLen)
            val series = List(n) { s ->
                FloatArray(count) { i -> buffer.get(seriesBase(r, s) + (start + i) % fineLen) }
            }
            return Read(f.lastMs - (count - 1).coerceAtLeast(0) * f.stepMs, f.stepMs, series)
        }
        val len = coarseLen(w)
        val c = coarseState[r]?.get(w) ?: return Read(now, w.stepMs, List(n) { FloatArray(0) })
        val endIdx = (now / w.stepMs).toInt()
        val firstIdx = endIdx - len + 1
        val off = coarseOffset(w)
        val series = List(n) { s ->
            FloatArray(len) { i ->
                val idx = firstIdx + i
                val slot = Math.floorMod(idx, len)
                when {
                    c.stamps[slot] == idx -> buffer.get(seriesBase(r, s) + off + slot)
                    idx == c.accIdx && c.accN > 0 -> c.accSum[s] / c.accN
                    else -> Float.NaN
                }
            }
        }
        return Read(firstIdx.toLong() * w.stepMs, w.stepMs, series)
    }

    /** Coarse rings → file (magic, per resource/window the stamps and values) */
    @Synchronized
    fun save(file: File) {
        file.parentFile?.mkdirs()
        java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            for (r in Resource.entries) {
                val n = seriesCount[r] ?: 0
                for (w in coarse) {
                    val c = coarseState[r]?.get(w) ?: continue
                    if (c.accN > 0 && c.accIdx != Int.MIN_VALUE) commit(r, w, c, n)
                    val len = coarseLen(w)
                    val off = coarseOffset(w)
                    out.writeInt(len)
                    out.writeInt(n)
                    for (i in 0 until len) out.writeInt(c.stamps[i])
                    for (s in 0 until n) for (i in 0 until len) out.writeFloat(buffer.get(seriesBase(r, s) + off + i))
                }
            }
        }
    }

    @Synchronized
    fun load(file: File) {
        if (!file.isFile) return
        java.io.DataInputStream(file.inputStream().buffered()).use { inp ->
            if (inp.readInt() != MAGIC) return
            for (r in Resource.entries) {
                val n = seriesCount[r] ?: 0
                for (w in coarse) {
                    val c = coarseState[r]?.get(w) ?: continue
                    val len = inp.readInt()
                    val sn = inp.readInt()
                    if (len != coarseLen(w) || sn != n) return
                    val off = coarseOffset(w)
                    for (i in 0 until len) c.stamps[i] = inp.readInt()
                    for (s in 0 until n) for (i in 0 until len) buffer.put(seriesBase(r, s) + off + i, inp.readFloat())
                }
            }
        }
    }

    private companion object {
        const val MAGIC = 0x4B4D4B53
    }
}
