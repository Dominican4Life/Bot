package eu.kanade.tachiyomi.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.jsQuoted
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.MemoryLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KMK (r97): The Diagnostics engine: implements [Diagnostics] for the screens (contract §1, Half E) over the parts
 * in this package ([LogCapture], [LiveSampler], [NetStats], [Watchdogs], [Health], [TraceWriter], [ProbeRuntime],
 * [DiagReport]). Installed from `App.onCreate` right after `AppLog.init`; owns the engine scope and the single IO
 * worker thread ([DiagIo]). With nothing open and no probe enabled, the only threads alive are
 * `diag-main-watchdog` and `diag-io-worker`.
 */
internal class DiagnosticsEngine(private val app: Application) : Diagnostics {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(2))
    private val probeStore = ProbeStore(app)

    override val strip: StateFlow<StatusStrip> get() = LiveSampler.strip
    override val capture: StateFlow<CaptureState> get() = LogCapture.state
    override val recording: StateFlow<RecordingState?> get() = LogCapture.recording
    override val health: StateFlow<HealthSnapshot> get() = Health.snapshot
    private val _report = MutableStateFlow(ReportState())
    override val report: StateFlow<ReportState> = _report.asStateFlow()
    override val probes: StateFlow<ProbesSnapshot> get() = ProbeRuntime.snapshot
    override val console: StateFlow<List<ConsoleLine>> get() = ProbeRuntime.console
    override val memory: StateFlow<MemoryBreakdown> get() = LiveSampler.memory
    override val workers: StateFlow<WorkerSnapshot> get() = LiveSampler.workers
    override val tables: StateFlow<LiveTables> get() = LiveSampler.tables
    override val player: StateFlow<PlayerLive?> get() = LiveSampler.player
    override val logVersion: StateFlow<Int> get() = AppLog.version
    override val markers: StateFlow<List<Marker>> get() = Health.markers

    private var autoOffJob: Job? = null
    private var recordingJob: Job? = null

    private fun start() {
        DiagIo.start()
        LiveSampler.init(app, scope)
        TraceWriter.init(app)
        NetStats.install()
        // KMK (r103 fix pass): the watchdogs start paused unless an activity is started or the process is being
        // started for one (foreground importance: a launch from the launcher or a notification), so a stall during
        // a cold start is still caught. A process started for a job / broadcast pauses at once, or after 10 s when
        // no activity came by then
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val launching = !started && runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(true)
        Watchdogs.inForeground = started || launching
        if (launching) {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) Watchdogs.inForeground = false
                },
                10_000L,
            )
        }
        AppLog.add(
            'D',
            "diag",
            "Watchdogs start ${if (started || launching) "running" else "paused"} " +
                "(activity started: $started, launching one: $launching)",
        )
        Watchdogs.start(app)
        ProbeRuntime.init(app, scope, probeStore)
        LogCapture.onChanged = { armAutoOff() }
        LogCapture.onRecordingEnded = { rec, lines -> onRecordingEnded(rec, lines) }
        TraceWriter.setRecording(Traces.recording)
        // The last report, for the Report tab's footer
        runCatching {
            app.getSharedPreferences(DiagKeys.PREFS_FILE, android.content.Context.MODE_PRIVATE)
                .getString(DiagKeys.LAST_REPORT, null)?.let { json ->
                    val o = eu.kanade.tachiyomi.jssource.jsJson.parseToJsonElement(json).let { it as kotlinx.serialization.json.JsonObject }
                    val path = (o["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@let
                    if (File(path).isFile) {
                        _report.update {
                            it.copy(
                                last = ReportResult(
                                    path,
                                    (o["bytes"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                                    (o["createdAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                                    (o["keysPath"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                                ),
                            )
                        }
                    }
                }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    Watchdogs.inForeground = true
                    LiveSampler.onForeground()
                }
            },
        )
        scope.launch {
            FloatingLogState.visible.collect { LiveSampler.onFloatingChanged() }
        }
        Health.attach(app) // synchronously: a crash before the store is read can still merge and write
        DiagIo.post {
            Health.init(app)
            Health.readExitInfo(app)
        }
        // A recording cut by a process death ends now (levels restored, its report built from what the ring kept)
        if (LogCapture.interruptedRecording) {
            scope.launch {
                delay(3_000L)
                AppLog.add('I', "app", "Recording interrupted by the previous process end")
                LogCapture.stopRecording()
            }
        } else if (LogCapture.recording.value != null) {
            startRecordingTicker()
        }
        armAutoOff()
    }

    /** App.onStop: flush rings, stop samplers */
    fun onBackground() {
        Watchdogs.lastBackgroundAt = System.currentTimeMillis()
        Watchdogs.inForeground = false
        LiveSampler.onBackground()
        DiagIo.forceSoon()
    }

    // --- auto-off (one coroutine, re-armed on each change; no wake when nothing expires) ---------------------------

    private fun armAutoOff() {
        synchronized(this) {
            autoOffJob?.cancel()
            autoOffJob = scope.launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    val next = LogCapture.autoOffTick(now)
                    ProbeRuntime.minuteTick(now)
                    val probeNext = probeStore.probes.value.filter { it.enabled && it.autoOffAt > 0L }.minOfOrNull { it.autoOffAt }
                    val deadline = listOfNotNull(next, probeNext).minOrNull()
                    // Nothing expiring: keep the minute tick (probe runtime memory, auto-off re-checks) instead of
                    // stopping; the loop costs one wake-up a minute
                    val wait = if (deadline == null) 60_000L else (deadline - now).coerceIn(1_000L, 60_000L)
                    delay(wait)
                }
            }
        }
    }

    // --- recording -------------------------------------------------------------------------------------------------

    private fun startRecordingTicker() {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            while (LogCapture.recording.value != null) {
                LogCapture.tickRecording()
                LogCapture.recordingNow()?.let { RecordingNotification.showRecording(app, it) }
                delay(1_000L)
            }
            RecordingNotification.hideRecording(app)
        }
    }

    override fun record(duration: RecordDuration) {
        if (LogCapture.recording.value != null) return
        LogCapture.record(duration)
        TraceWriter.setRecording(true)
        LiveSampler.strip.update { it.copy(sampleMs = LogCapture.sampleMs) }
        startRecordingTicker()
        armAutoOff()
    }

    override fun stopRecording() {
        LogCapture.stopRecording()
    }

    private fun onRecordingEnded(rec: RecordingState, lines: Int) {
        TraceWriter.setRecording(false)
        recordingJob?.cancel()
        recordingJob = null
        RecordingNotification.hideRecording(app)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val result = buildReport(ReportOptions())
                _report.update { it.copy(pendingShare = result) }
                RecordingNotification.showResult(app, result)
            }.onFailure { AppLog.add('W', "diag", "Report after the recording failed: ${it.message}") }
        }
    }

    // --- samples / visibility ------------------------------------------------------------------------------------

    override fun samples(resource: Resource, window: TimeWindow): StateFlow<SampleWindow> = LiveSampler.samples(
        resource,
        window,
    )

    override fun setLiveVisible(visible: Boolean) = LiveSampler.setLiveVisible(visible)

    override fun setStripVisible(visible: Boolean) = LiveSampler.setStripVisible(visible)

    // --- log ---------------------------------------------------------------------------------------------------------

    private fun entry(line: AppLog.Line): LogEntry = LogEntry(
        time = line.time,
        level = line.level,
        tag = line.tag,
        message = line.message,
        category = LogCapture.categoryOf(line.tag),
        fields = AppLog.fieldsOf(line.message),
        text = AppLog.format(line),
    )

    /** One parsed query: free text plus field conditions (`key=value`, `key>n`, `key<n`, `key>=n`, `key<=n`) */
    private class Query(val text: String, val conditions: List<Triple<String, String, String>>) {
        fun matches(e: LogEntry): Boolean {
            if (text.isNotEmpty() && !e.message.contains(text, true) && !e.tag.contains(text, true)) return false
            for ((key, op, value) in conditions) {
                val actual = e.fields[key] ?: return false
                val ok = when (op) {
                    "=" -> actual.equals(value, true)
                    else -> {
                        val a = actual.toDoubleOrNull() ?: return false
                        val b = value.toDoubleOrNull() ?: return false
                        when (op) {
                            ">" -> a > b
                            "<" -> a < b
                            ">=" -> a >= b
                            "<=" -> a <= b
                            else -> false
                        }
                    }
                }
                if (!ok) return false
            }
            return true
        }

        companion object {
            private val CONDITION = Regex("""^([A-Za-z_][A-Za-z0-9_]*)(>=|<=|=|>|<)(\S+)$""")

            fun parse(query: String): Query {
                val words = ArrayList<String>()
                val conditions = ArrayList<Triple<String, String, String>>()
                for (token in query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                    val m = CONDITION.find(token)
                    if (m != null) conditions.add(Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])) else words.add(token)
                }
                return Query(words.joinToString(" "), conditions)
            }
        }
    }

    private fun passes(e: LogEntry, filter: LogFilter, query: Query): Boolean {
        when (filter.level) {
            LogLevelFilter.ALL -> Unit
            LogLevelFilter.INFO -> if (!e.isInfo) return false
            LogLevelFilter.PROBLEMS -> if (!e.isProblem) return false
        }
        if (filter.categories.isNotEmpty() && e.category !in filter.categories) return false
        if (filter.tags.isNotEmpty() && e.tag !in filter.tags) return false
        filter.aroundTime?.let { t -> if (e.time < t - filter.aroundMs || e.time > t + filter.aroundMs) return false }
        return query.matches(e)
    }

    override fun logLines(filter: LogFilter, limit: Int): List<LogEntry> {
        val query = Query.parse(filter.query)
        val lines = AppLog.snapshot()
        val out = ArrayList<LogEntry>(minOf(limit, lines.size))
        var i = lines.size - 1
        while (i >= 0 && out.size < limit) {
            val e = entry(lines[i])
            if (passes(e, filter, query)) out.add(e)
            i--
        }
        return out
    }

    override fun logTail(n: Int, tag: String?, problemsOnly: Boolean): List<LogEntry> =
        AppLog.lastLines(n) { line -> (tag == null || line.tag == tag) && (!problemsOnly || line.isProblem) }.map(::entry)

    override fun logChips(): LogChips {
        val lines = AppLog.snapshot()
        var problems = 0
        var marks = 0
        val categories = HashMap<DiagCategory, Int>()
        val tags = HashMap<String, Int>()
        val sources = HashMap<String, Int>()
        for (line in lines) {
            if (line.isProblem) problems++
            if (line.tag == "app" && line.message.startsWith("=== MARK")) marks++
            val c = LogCapture.categoryOf(line.tag)
            categories[c] = (categories[c] ?: 0) + 1
            tags[line.tag] = (tags[line.tag] ?: 0) + 1
            AppLog.fieldsOf(line.message)["source"]?.let { sources[it] = (sources[it] ?: 0) + 1 }
        }
        return LogChips(
            problems = problems,
            marks = marks,
            categories = categories.entries.sortedByDescending { it.value }.map { it.key to it.value },
            tags = tags.entries.sortedByDescending { it.value }.take(12).map { it.key to it.value },
            sources = sources.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value },
        )
    }

    override fun categoryOf(tag: String): DiagCategory = LogCapture.categoryOf(tag)

    override fun setTagCategory(tag: String, category: DiagCategory) = LogCapture.setTagCategory(tag, category)

    override fun clearLog() = AppLog.clear()

    override fun clearEverything() {
        AppLog.clear()
        Health.clearAll(app)
    }

    override fun mark(note: String?) {
        AppLog.mark(note)
        val text = note?.trim().orEmpty()
        TraceWriter.markAll(text)
        Health.event(HealthKind.MARK, "Mark", text)
    }

    // --- capture -----------------------------------------------------------------------------------------------------

    override fun setLevel(category: DiagCategory, level: DiagLevel) = LogCapture.setLevel(category, level)

    override fun applyPreset(preset: DiagPreset) = LogCapture.applyPreset(preset)

    override fun setTrace(kind: TraceKind, on: Boolean) {
        LogCapture.setTrace(kind, on)
        scope.launch {
            delay(300L)
            LogCapture.setTraceBytes(TraceWriter.bytesMap())
        }
    }

    override fun setNetTraceSources(sources: Set<String>) = LogCapture.setNetTraceSources(sources)

    override fun setNetTraceOptions(headers: Boolean, bodyHead: Boolean) = LogCapture.setNetTraceOptions(
        headers,
        bodyHead,
    )

    override fun setSampleMs(ms: Long) = LogCapture.setSampleMs(ms)

    override suspend fun traceableSources(): List<Pair<String, String>> = withContext(Dispatchers.IO) { NetStats.traceableSources() }

    // --- live actions ------------------------------------------------------------------------------------------------

    override suspend fun threadDump(): ThreadDump = withContext(Dispatchers.Default) {
        Watchdogs.threadDumpNow().also { dump -> LiveSampler.workers.update { it.copy(lastDump = dump) } }
    }

    override suspend fun trimMemory(): String = withContext(Dispatchers.Default) { MemoryLog.trimNow() }

    override suspend fun closeIdleRuntimes(): Int = Injekt.get<JsSourceManager>().closeIdle().also {
        AppLog.add('I', "diag", "Closed $it idle JS source${if (it == 1) "" else "s"}")
    }

    override suspend fun reindexDownloads() {
        LiveSampler.invalidateStorage()
    }

    override suspend fun cursor(resource: Resource, window: TimeWindow, timeMs: Long): CursorInfo =
        withContext(Dispatchers.Default) { LiveSampler.cursor(resource, window, timeMs) }

    override suspend fun usage(by: UsageBy, window: TimeWindow): List<UsageRow> =
        withContext(Dispatchers.Default) { NetStats.usage(by, window) }

    override suspend fun usageCsv(by: UsageBy, window: TimeWindow): String = withContext(Dispatchers.IO) {
        val rows = NetStats.usage(by, window)
        val file = File(app.cacheDir, DiagPaths.USAGE_CSV)
        file.writeText(
            buildString {
                append("${by.label},hosts,bytes,requests,avg_ms,errors\n")
                for (r in rows) append("\"${r.name}\",\"${r.hosts}\",${r.bytes},${r.requests},${r.avgMs},${r.errors}\n")
            },
        )
        file.path
    }

    override fun liveSnapshotText(): String {
        val m = memory.value
        val w = workers.value
        val s = strip.value
        val t = tables.value
        return buildString {
            append("Memory ${m.javaMb} / ${m.javaMaxMb} MB in use (screen: ${MemoryLog.currentScreen}) · native ${m.nativeMb} MB · ")
            append("images ${m.imagesMb}/${m.imagesMaxMb} MB, Browse tabs ${m.browseTabs}, folder listings ${m.folderListings}, ")
            append("JS sources running ${m.engines.size}, JS parsed pages ${m.jsPages} (page text ${m.jsPageTextMb} MB), ")
            append("animated tiles ${m.animatedTiles}\n")
            append("Health: ${s.health.label} · ${s.sentence}\n")
            append("Workers: ${w.ioBusy} / ${w.ioTotal} workers busy (IO limit ${w.ioTotal}; Default shares the threads) · ${w.ioParked} parked · ${w.ioBlocked} blocked · JS ${w.jsBusy} busy · ${w.threads} threads\n")
            append("Network: ${"%.2f".format(s.netMbps)} MB/s · ${NetStats.calls.get()} requests · ${NetStats.bytesIn.get() / (1024 * 1024)} MB in this run\n")
            append("Native: ${m.nativeMb} MB = QuickJS ${m.jsMb} + diag ${m.diagMb} + other · RSS ${m.rssMb} MB of ${m.deviceTotalMb} MB\n")
            for (e in m.engines) append("  JS ${e.name}: ${"%.1f".format(e.mb)} MB · ${e.pages} pages${if (e.busy) " · busy" else ""}\n")
            if (t.running.isNotEmpty()) append("Running now: ${t.running.joinToString(" · ") { "${it.name} ${it.progress}" }}\n")
            t.storage?.let { st ->
                append("Storage: downloads ${st.downloadsBytes?.let { "${it / (1024 * 1024)} MB" } ?: "…"} · cache ${st.cacheBytes?.let { "${it / (1024 * 1024)} MB" } ?: "…"} · ")
                append("${st.freeBytes / (1024 * 1024 * 1024)} GB free · DB ${st.dbBytes / (1024 * 1024)} MB · WAL ${st.walBytes / 1024} KB\n")
            }
            player.value?.let { p ->
                append("Player: ${p.title} · ${p.posS}/${p.durationS} s · cache ${"%.1f".format(p.demuxerCacheS)} s · dropped ${p.droppedFrames} · ")
                append("${p.vcodec ?: "?"}/${p.acodec ?: "?"} · hwdec ${p.hwdec ?: "?"} · ${p.width}×${p.height} @ ${"%.1f".format(p.fps)}\n")
            }
            val rows = NetStats.rows().takeLast(20)
            if (rows.isNotEmpty()) {
                append("Last ${rows.size} requests:\n")
                for (r in rows) append("  ${r.method} ${r.host}${r.path.take(60)} -> ${r.status} in ${r.ms} ms, ${r.bytes} B${r.source?.let { " · $it" } ?: ""}\n")
            }
        }.take(DiagBudget.CLIPBOARD_LIMIT)
    }

    // --- health ------------------------------------------------------------------------------------------------------

    override suspend fun healthExcerpt(event: HealthEvent): List<LogEntry> = withContext(Dispatchers.IO) {
        if (event.session == AppLog.sessionStart) {
            return@withContext AppLog.linesBetween(event.time - 30_000L, event.time + 30_000L).map(::entry)
        }
        val ring = AppLog.ring
        if (ring != null && event.excerptOffset >= 0L) {
            val bytes = ring.readAround(event.excerptOffset.toInt(), DiagBudget.HEALTH_EXCERPT_BYTES)
            val text = String(bytes, Charsets.UTF_8)
            val lines = text.split('\n').drop(1).dropLast(1).mapNotNull { decodeLine(it) }
                .filter { kotlin.math.abs(it.time - event.time) <= 10 * 60_000L }
            if (lines.isNotEmpty()) return@withContext lines.map(::entry)
        }
        listOf(entry(AppLog.Line(event.time, 'I', "app", "${event.title}: ${event.detail}")))
    }

    private fun decodeLine(text: String): AppLog.Line? {
        val parts = text.split('\t', limit = 4)
        if (parts.size < 4) return null
        val time = parts[0].toLongOrNull() ?: return null
        return AppLog.Line(
            time,
            parts[1].firstOrNull() ?: 'I',
            parts[2],
            parts[3].replace("\\n", "\n").replace("\\\\", "\\"),
        )
    }

    override suspend fun exportHealth(): String = withContext(Dispatchers.IO) {
        val file = File(app.cacheDir, DiagPaths.HEALTH_ZIP)
        java.util.zip.ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("health.json"))
            zip.write(Health.json().toByteArray())
            zip.closeEntry()
            File(app.cacheDir, DiagPaths.HEALTH_DIR).listFiles()?.filter { it.isFile }?.forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry("health/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        file.path
    }

    // --- report ------------------------------------------------------------------------------------------------------

    override suspend fun refreshReportSizes(): List<ReportPartSize> = withContext(Dispatchers.IO) {
        val sizes = DiagReport.sizes(app)
        _report.update { it.copy(sizes = sizes, estimateBytes = sizes.sumOf { s -> s.bytes } / 3) }
        sizes
    }

    override suspend fun createReport(options: ReportOptions): ReportResult = withContext(Dispatchers.IO) { buildReport(options) }

    private fun buildReport(options: ReportOptions): ReportResult {
        _report.update { it.copy(building = true, progress = "starting") }
        try {
            val result = DiagReport.build(app, options) { name -> _report.update { it.copy(progress = name) } }
            _report.update { it.copy(building = false, progress = null, last = result) }
            runCatching {
                app.getSharedPreferences(DiagKeys.PREFS_FILE, android.content.Context.MODE_PRIVATE).edit().putString(
                    DiagKeys.LAST_REPORT,
                    "{\"path\":${result.path.jsQuoted()},\"bytes\":${result.bytes}," +
                        "\"createdAt\":${result.createdAt}" + (result.keysPath?.let { ",\"keysPath\":${it.jsQuoted()}" } ?: "") + "}",
                ).apply()
            }
            return result
        } catch (e: Throwable) {
            _report.update { it.copy(building = false, progress = null) }
            throw e
        }
    }

    override fun consumePendingShare() {
        _report.update { it.copy(pendingShare = null) }
    }

    // --- probes ------------------------------------------------------------------------------------------------------

    override suspend fun probeCode(id: String): String = withContext(Dispatchers.IO) { probeStore.code(id) }

    override fun lintProbe(code: String): List<ProbeLint> = ProbeRuntime.lint(code, experimental = false)

    override suspend fun saveProbe(draft: ProbeDraft): ProbeDescriptor = withContext(Dispatchers.IO) { ProbeRuntime.save(draft) }

    override suspend fun deleteProbe(id: String) = withContext(Dispatchers.IO) { ProbeRuntime.delete(id) }

    override fun setProbeEnabled(id: String, on: Boolean) {
        ProbeRuntime.setEnabled(id, on)
        armAutoOff()
    }

    override fun setProbeExperimental(id: String, on: Boolean) = ProbeRuntime.setExperimental(id, on)

    override fun setProbeAutoOff(id: String, autoOff: ProbeAutoOff) {
        ProbeRuntime.setAutoOff(id, autoOff)
        armAutoOff()
    }

    override fun setProbeBudget(id: String, budget: ProbeBudget) = ProbeRuntime.setBudget(id, budget)

    override fun setProbeMarker(id: String, on: Boolean) = ProbeRuntime.setMarker(id, on)

    override suspend fun runProbeOnce(id: String): ProbeRunResult = ProbeRuntime.runOnce(id)

    override suspend fun importProbe(fileName: String, code: String): ProbeDescriptor =
        withContext(Dispatchers.IO) { ProbeRuntime.save(ProbeDraft(null, ProbeStore.nameOf(code) ?: fileName.substringBeforeLast('.'), code)) }

    override fun clearConsole(probeId: String?) = ProbeRuntime.clearConsole(probeId)

    override fun probeTemplate(hook: ProbeHook): String = ProbeStore.template(hook)

    companion object {
        /** Sets [Diag.engine], starts the watchdogs and the IO worker, reads the exit info, resumes a cut recording */
        fun install(app: Application) {
            val engine = DiagnosticsEngine(app)
            Diag.engine = engine
            runCatching { engine.start() }.onFailure { AppLog.add('E', "diag", "Diagnostics engine failed to start: $it") }
        }
    }
}

/**
 * KMK (r97): The single IO worker thread of the engine, `diag-io-worker`: ring `force()` every 5 s (and at once for
 * W/E lines), trace appends, health.json rewrites, sample persistence. Nothing else of the engine runs when no
 * screen is open.
 */
internal object DiagIo {

    private val queue = LinkedBlockingQueue<Runnable>()
    private val started = AtomicBoolean(false)
    private val forceRequested = AtomicBoolean(false)
    private val wake = Runnable {}

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val t = Thread({ loop() }, "diag-io-worker")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
    }

    fun post(block: () -> Unit) {
        start()
        queue.offer(Runnable(block))
    }

    /** A W/E line was written: push the ring to its file soon */
    fun forceSoon() {
        forceRequested.set(true)
        queue.offer(wake)
    }

    private fun loop() {
        var lastForceAt = 0L
        while (true) {
            val task = try {
                queue.poll(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                return
            }
            if (task != null && task !== wake) {
                try {
                    task.run()
                } catch (e: Throwable) {
                    // The worker never dies on a bad task
                }
            }
            val now = System.currentTimeMillis()
            if (task == null || forceRequested.getAndSet(false) || now - lastForceAt >= 5_000L) {
                lastForceAt = now
                runCatching { AppLog.ring?.force() }
                runCatching { TraceWriter.forceAll() }
            }
        }
    }
}
