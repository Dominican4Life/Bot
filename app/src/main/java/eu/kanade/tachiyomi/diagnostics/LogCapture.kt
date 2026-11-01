package eu.kanade.tachiyomi.diagnostics

import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * KMK (r97): What gets logged (design §3.1–3.3, §3.5). The tag → category map, the two volatile masks the gate in
 * [AppLog.add] reads, the presets, the 24 h auto-off and the persistence in the `diagnostics` preferences file.
 * [init] runs inside `AppLog.init` before the first line; everything else is driven by the engine.
 *
 * Normal is today's behaviour byte for byte: with both masks at zero the gate drops only `V` lines, which no
 * existing call site writes.
 */
internal object LogCapture {

    @Volatile
    private var verboseMask: Int = 0

    @Volatile
    private var offMask: Int = 0

    /** True while any category is Verbose (the logcat Logger lets VERBOSE through then) */
    val anyVerbose: Boolean get() = verboseMask != 0

    fun isVerbose(c: DiagCategory): Boolean = verboseMask and c.mask != 0

    fun isOff(c: DiagCategory): Boolean = offMask and c.mask != 0

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _recording = MutableStateFlow<RecordingState?>(null)
    val recording: StateFlow<RecordingState?> = _recording.asStateFlow()

    /** Set while a recording runs: [AppLog.add] counts its lines through [onAdded] */
    @Volatile
    private var recordingOn = false
    val recordedLines = AtomicInteger(0)

    /** W/E lines this run (the Log tab's "new problem" rule reads [AppLog.version] instead; this is for the strip) */
    val problemCount = AtomicInteger(0)

    private var prefs: SharedPreferences? = null

    /** Called by the engine once it exists, so a level change can re-arm the auto-off timer */
    @Volatile
    var onChanged: (() -> Unit)? = null

    // --- tag → category -----------------------------------------------------------------------------------------

    private val exact: Map<String, DiagCategory> = buildMap {
        for (t in listOf("js", "JsSourceManager", "JsRuntime", "JsVideos", "ChapterVideoDownloads", "js.call", "js.parse")) {
            put(t, DiagCategory.JS)
        }
        for (t in listOf("net", "botcheck", "NetworkHelper", "CloudflareInterceptor", "net.v")) put(t, DiagCategory.NET)
        for (t in listOf(
            "extensions", "Sideload", "cover", "ExtensionManager", "ExtensionInstaller", "SourcesScreenModel",
            "BrowseTabCache", "lab",
        )) {
            put(t, DiagCategory.BROWSE)
        }
        for (t in listOf(
            "reader", "pages", "ReaderViewModel", "ChapterCache", "ImageUtil", "AnimatedThumbnail", "reader.page",
        )) {
            put(t, DiagCategory.READER)
        }
        for (t in listOf(
            "Player", "hls", "Anime4K", "PlayerViewModel", "PlayerActivity", "EpisodeLoader", "HosterLoader",
            "HlsVariants", "HlsRelay", "player.ev", "player.hls", "player.hoster",
        )) {
            put(t, DiagCategory.PLAYER)
        }
        for (t in listOf(
            "Restore", "anime", "LibraryUpdateJob", "AnimeLibraryUpdateJob", "Downloader", "AnimeDownloader",
            "DownloadJob", "MetadataUpdateJob", "EHentaiUpdateWorker", "anilist",
        )) {
            put(t, DiagCategory.LIBRARY)
        }
        for (t in listOf("sync", "SyncService", "SyncManager", "sync.frame")) put(t, DiagCategory.SYNC)
        for (t in listOf("Memory", "db", "DbStats")) put(t, DiagCategory.MEMORY)
        for (t in listOf("app", "diag", "ScreenTransition", "GlobalExceptionHandler")) put(t, DiagCategory.APP)
    }

    /** Class-name prefixes from design §3.1, checked on a miss (the result is then cached in [cache]) */
    private val prefixes: List<Pair<String, DiagCategory>> = listOf(
        "js:" to DiagCategory.JS,
        "probe:" to DiagCategory.APP,
        "mpv/" to DiagCategory.PLAYER,
        "Browse" to DiagCategory.BROWSE,
        "Extension" to DiagCategory.BROWSE,
        "PageLoader" to DiagCategory.READER,
        "Reader" to DiagCategory.READER,
        "Player" to DiagCategory.PLAYER,
        "Hls" to DiagCategory.PLAYER,
        "Backup" to DiagCategory.LIBRARY,
        "Download" to DiagCategory.LIBRARY,
        "Library" to DiagCategory.LIBRARY,
        "PairSync" to DiagCategory.SYNC,
        "Sync" to DiagCategory.SYNC,
    )

    private val cache = ConcurrentHashMap<String, DiagCategory>(exact)

    /** One hash lookup; a prefix scan only the first time an unknown tag is seen */
    fun categoryOf(tag: String): DiagCategory {
        cache[tag]?.let { return it }
        val resolved = _state.value.tagOverrides[tag]
            ?: prefixes.firstOrNull { tag.startsWith(it.first) }?.second
            ?: (if (tag.endsWith("SourceScreenModel")) DiagCategory.BROWSE else null)
            ?: DiagCategory.APP
        // Unbounded tags (a class name per line is bounded; keep a cap anyway)
        if (cache.size < 4_000) cache[tag] = resolved
        return resolved
    }

    fun setTagCategory(tag: String, category: DiagCategory) {
        _state.update { it.copy(tagOverrides = it.tagOverrides + (tag to category)) }
        cache[tag] = category
        persist()
    }

    // --- init / persistence --------------------------------------------------------------------------------------

    /** Reads the saved capture state. Called from `AppLog.init` on the main thread: one preferences read */
    fun init(context: Context) {
        val p = context.getSharedPreferences(DiagKeys.PREFS_FILE, Context.MODE_PRIVATE)
        prefs = p
        val now = System.currentTimeMillis()
        val levels = DiagCategory.entries.associateWith { DiagLevel.NORMAL }.toMutableMap()
        p.getString(DiagKeys.CAPTURE, null)?.split(';')?.forEach { part ->
            val name = part.substringBefore('=')
            val category = DiagCategory.entries.firstOrNull { it.name == name } ?: return@forEach
            when (part.substringAfter('=', "")) {
                "V" -> levels[category] = DiagLevel.VERBOSE
                "O" -> if (category != DiagCategory.APP) levels[category] = DiagLevel.OFF
            }
        }
        val until = mutableMapOf<DiagCategory, Long>()
        for ((k, t) in parseLongMap(p.getString(DiagKeys.VERBOSE_UNTIL, null))) {
            DiagCategory.entries.firstOrNull { it.name == k }?.let { until[it] = t }
        }
        // Expired Verbose entries drop back to Normal before the masks are built
        for ((c, t) in until.toMap()) {
            if (t <= now) {
                until.remove(c)
                if (levels[c] == DiagLevel.VERBOSE) levels[c] = DiagLevel.NORMAL
            }
        }
        for (c in DiagCategory.entries) if (levels[c] == DiagLevel.VERBOSE && until[c] == null) until[c] = now + DiagBudget.VERBOSE_AUTO_OFF_MS
        val traces = p.getString(DiagKeys.TRACES, "").orEmpty().split(',')
            .mapNotNull { n -> TraceKind.entries.firstOrNull { it.name == n } }.toSet()
        val overrides = parseStringMap(p.getString(DiagKeys.TAG_OVERRIDES, null))
            .mapNotNull { (k, v) -> DiagCategory.entries.firstOrNull { it.name == v }?.let { k to it } }.toMap()
        val preset = p.getString(DiagKeys.PRESET, null)?.let { n -> DiagPreset.entries.firstOrNull { it.name == n } }
        _state.value = CaptureState(
            levels = levels,
            verboseUntil = until,
            traces = traces,
            netTraceSources = p.getString(DiagKeys.NET_TRACE_SOURCES, "").orEmpty().split(',')
                .filter { it.isNotBlank() }.toSet(),
            netTraceHeaders = p.getBoolean(DiagKeys.NET_TRACE_HEADERS, false),
            netTraceBodyHead = p.getBoolean(DiagKeys.NET_TRACE_BODY, false),
            sampleMs = p.getLong(DiagKeys.SAMPLE_MS, SampleRate.NORMAL.ms),
            preset = preset,
            tagOverrides = overrides,
        )
        cache.putAll(overrides)
        rebuildMasks()
        Traces.apply(_state.value)
        // A recording cut by a process death: its saved levels are restored at the next start (ended by stopRecording)
        p.getString(DiagKeys.RECORDING, null)?.let { json ->
            runCatching {
                val o = jsJsonLenient.parseToJsonElement(json).jsonObject
                val startedAt = o.longOf("startedAt") ?: 0L
                val duration = o["duration"]?.jsonPrimitive?.content
                    ?.let { d -> RecordDuration.entries.firstOrNull { it.name == d } } ?: RecordDuration.M5
                _recording.value = RecordingState(startedAt, duration, o.longOf("endsAt"), 0, 0L)
                recordingOn = true
                // So the net/js rings open at their 2 MB recording capacity and keep what the cut run wrote
                Traces.recording = true
                interruptedRecording = true
            }
        }
    }

    /** True when [init] found a recording the previous run didn't end; the engine ends it right after start */
    @Volatile
    var interruptedRecording = false
        private set

    /** Updates the state and derives the masks from the very state that was written (no read-after-write race) */
    private inline fun updateState(block: (CaptureState) -> CaptureState) {
        _state.update { s -> block(s).also { rebuildMasks(it) } }
    }

    private fun rebuildMasks(state: CaptureState = _state.value) {
        var v = 0
        var o = 0
        for ((c, l) in state.levels) {
            when (l) {
                DiagLevel.VERBOSE -> v = v or c.mask
                DiagLevel.OFF -> if (c != DiagCategory.APP) o = o or c.mask
                DiagLevel.NORMAL -> Unit
            }
        }
        verboseMask = v
        offMask = o
    }

    private fun persist() {
        val p = prefs ?: return
        val s = _state.value
        p.edit()
            .putString(
                DiagKeys.CAPTURE,
                s.levels.filterValues { it != DiagLevel.NORMAL }
                    .entries.joinToString(";") { (c, l) -> "${c.name}=${if (l == DiagLevel.VERBOSE) "V" else "O"}" },
            )
            .putString(
                DiagKeys.VERBOSE_UNTIL,
                s.verboseUntil.entries.joinToString(",", "{", "}") { (c, t) -> "\"${c.name}\":$t" },
            )
            .putString(DiagKeys.TRACES, s.traces.joinToString(",") { it.name })
            .putString(DiagKeys.NET_TRACE_SOURCES, s.netTraceSources.joinToString(","))
            .putBoolean(DiagKeys.NET_TRACE_HEADERS, s.netTraceHeaders)
            .putBoolean(DiagKeys.NET_TRACE_BODY, s.netTraceBodyHead)
            .putLong(DiagKeys.SAMPLE_MS, s.sampleMs)
            .putString(DiagKeys.PRESET, s.preset?.name)
            .putString(
                DiagKeys.TAG_OVERRIDES,
                s.tagOverrides.entries.joinToString(",", "{", "}") { (k, c) -> "${JsonPrimitive(k)}:\"${c.name}\"" },
            )
            .apply()
        Traces.apply(s)
        onChanged?.invoke()
    }

    private fun parseLongMap(json: String?): Map<String, Long> = runCatching {
        if (json.isNullOrBlank()) return emptyMap()
        jsJsonLenient.parseToJsonElement(json).jsonObject.mapValues { it.value.jsonPrimitive.longOrNull ?: 0L }
    }.getOrDefault(emptyMap())

    private fun parseStringMap(json: String?): Map<String, String> = runCatching {
        if (json.isNullOrBlank()) return emptyMap()
        jsJsonLenient.parseToJsonElement(json).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())

    private fun JsonObject.longOf(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private val jsJsonLenient = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    // --- levels, presets, traces ------------------------------------------------------------------------------------

    private var appOffLogged = false

    fun setLevel(category: DiagCategory, level: DiagLevel, byPreset: Boolean = false) {
        if (category == DiagCategory.APP && level == DiagLevel.OFF) {
            if (!appOffLogged) {
                appOffLogged = true
                AppLog.add('I', "diag", "App can't be switched off: it carries the start line, marks and crashes")
            }
            return
        }
        val now = System.currentTimeMillis()
        updateState { s ->
            val until = s.verboseUntil.toMutableMap()
            if (level == DiagLevel.VERBOSE) until[category] = now + DiagBudget.VERBOSE_AUTO_OFF_MS else until.remove(category)
            s.copy(
                levels = s.levels + (category to level),
                verboseUntil = until,
                preset = if (byPreset) s.preset else null,
            )
        }
        persist()
    }

    fun applyPreset(preset: DiagPreset) {
        val now = System.currentTimeMillis()
        updateState { s ->
            val levels = DiagCategory.entries.associateWith { c ->
                when {
                    c in preset.verbose -> DiagLevel.VERBOSE
                    c in preset.off && c != DiagCategory.APP -> DiagLevel.OFF
                    else -> DiagLevel.NORMAL
                }
            }
            s.copy(
                levels = levels,
                verboseUntil = preset.verbose.associateWith { now + DiagBudget.VERBOSE_AUTO_OFF_MS },
                traces = preset.traces,
                netTraceSources = if (preset == DiagPreset.EVERYTHING) emptySet() else s.netTraceSources,
                sampleMs = preset.sampleMs ?: s.sampleMs,
                preset = preset,
            )
        }
        persist()
        AppLog.add('I', "diag", "Capture preset: ${preset.label}")
    }

    fun setTrace(kind: TraceKind, on: Boolean) {
        _state.update { s -> s.copy(traces = if (on) s.traces + kind else s.traces - kind, preset = null) }
        persist()
    }

    fun setNetTraceSources(sources: Set<String>) {
        _state.update { it.copy(netTraceSources = sources) }
        persist()
    }

    fun setNetTraceOptions(headers: Boolean, bodyHead: Boolean) {
        _state.update { it.copy(netTraceHeaders = headers, netTraceBodyHead = bodyHead) }
        persist()
    }

    fun setSampleMs(ms: Long) {
        _state.update { it.copy(sampleMs = SampleRate.of(ms).ms) }
        persist()
    }

    /** The current sampling interval (read by MemoryLog for its cadence and by the sampler) */
    val sampleMs: Long get() = _state.value.sampleMs

    fun setTraceBytes(bytes: Map<TraceKind, Long>) {
        if (bytes != _state.value.traceBytes) _state.update { it.copy(traceBytes = bytes) }
    }

    /**
     * The engine's minute tick: expired Verbose categories drop to Normal with one line each. Returns the epoch ms
     * of the next thing to wait for (a Verbose expiry or the recording end), or null.
     */
    fun autoOffTick(now: Long): Long? {
        val expired = _state.value.verboseUntil.filterValues { it <= now }.keys
        if (expired.isNotEmpty()) {
            updateState { s ->
                s.copy(
                    levels = s.levels + expired.associateWith { DiagLevel.NORMAL },
                    verboseUntil = s.verboseUntil - expired,
                )
            }
            persist()
            expired.forEach { AppLog.add('I', "app", "Verbose ended for ${it.label} (24 h)") }
        }
        val rec = _recording.value
        if (rec?.endsAt != null && rec.endsAt <= now) stopRecording(byTimer = true)
        return listOfNotNull(_state.value.verboseUntil.values.minOrNull(), _recording.value?.endsAt).minOrNull()
    }

    // --- recording (design §2.3) --------------------------------------------------------------------------------

    private var savedLevels: Map<DiagCategory, DiagLevel>? = null
    private var savedTraces: Set<TraceKind>? = null
    private var savedSampleMs: Long? = null

    /** Called when a recording ends with the report to build; set by the engine */
    @Volatile
    var onRecordingEnded: ((RecordingState, Int) -> Unit)? = null

    fun record(duration: RecordDuration) {
        if (_recording.value != null) return
        val now = System.currentTimeMillis()
        val s = _state.value
        savedLevels = s.levels
        savedTraces = s.traces
        savedSampleMs = s.sampleMs
        val p = prefs
        p?.edit()?.putString(
            DiagKeys.RECORDING_SAVED_LEVELS,
            s.levels.entries.joinToString(";") { (c, l) -> "${c.name}=${l.name}" } + "|" +
                s.traces.joinToString(",") { it.name } + "|" + s.sampleMs,
        )?.apply()
        val endsAt = duration.millis?.let { now + it.coerceAtMost(DiagBudget.RECORD_MAX_MS) }
        updateState { st ->
            st.copy(
                levels = DiagCategory.entries.associateWith { DiagLevel.VERBOSE },
                verboseUntil = DiagCategory.entries.associateWith { now + DiagBudget.VERBOSE_AUTO_OFF_MS },
                traces = TraceKind.entries.toSet(),
                netTraceSources = emptySet(),
                preset = DiagPreset.EVERYTHING,
            )
        }
        recordedLines.set(0)
        recordingOn = true
        _recording.value = RecordingState(now, duration, endsAt, 0, 0L)
        p?.edit()?.putString(
            DiagKeys.RECORDING,
            "{\"startedAt\":$now,\"duration\":\"${duration.name}\"" + (endsAt?.let { ",\"endsAt\":$it" } ?: "") + "}",
        )?.apply()
        persist()
        Traces.recording = true
        AppLog.add('I', "app", "=== RECORDING STARTED (${duration.label}) ===")
        Health.event(HealthKind.RECORDING, "Recording started", duration.label)
    }

    fun stopRecording(byTimer: Boolean = false) {
        val rec = _recording.value ?: return
        val lines = recordedLines.get()
        recordingOn = false
        Traces.recording = false
        val p = prefs
        // Levels saved at start (or, after a process death, from the preference)
        var levels = savedLevels
        var traces = savedTraces
        var sampleMs = savedSampleMs
        if (levels == null) {
            p?.getString(DiagKeys.RECORDING_SAVED_LEVELS, null)?.let { saved ->
                val parts = saved.split('|')
                levels = parts.getOrNull(0)?.split(';')?.mapNotNull { e ->
                    val c = DiagCategory.entries.firstOrNull { it.name == e.substringBefore('=') }
                    val l = DiagLevel.entries.firstOrNull { it.name == e.substringAfter('=', "") }
                    if (c != null && l != null) c to l else null
                }?.toMap()
                traces = parts.getOrNull(1)?.split(',')?.mapNotNull { n -> TraceKind.entries.firstOrNull { it.name == n } }?.toSet()
                sampleMs = parts.getOrNull(2)?.toLongOrNull()
            }
        }
        val now = System.currentTimeMillis()
        updateState { s ->
            val restored = levels ?: DiagCategory.entries.associateWith { DiagLevel.NORMAL }
            s.copy(
                levels = restored,
                verboseUntil = restored.filterValues { it == DiagLevel.VERBOSE }.keys
                    .associateWith { now + DiagBudget.VERBOSE_AUTO_OFF_MS },
                traces = traces ?: emptySet(),
                sampleMs = sampleMs ?: s.sampleMs,
                preset = null,
            )
        }
        savedLevels = null
        savedTraces = null
        savedSampleMs = null
        p?.edit()?.remove(DiagKeys.RECORDING)?.remove(DiagKeys.RECORDING_SAVED_LEVELS)?.apply()
        _recording.value = null
        interruptedRecording = false
        persist()
        val elapsed = ((if (byTimer) rec.endsAt ?: now else now) - rec.startedAt).coerceAtLeast(0L) / 1000
        val length = if (elapsed >= 60 && elapsed % 60 == 0L) "${elapsed / 60} min" else "%d:%02d".format(
            elapsed / 60,
            elapsed % 60,
        )
        AppLog.add('I', "app", "=== RECORDING ENDED ($length, ${"%,d".format(lines)} lines) ===")
        Health.event(HealthKind.RECORDING, "Recording ended", "$length · $lines lines")
        onRecordingEnded?.invoke(rec, lines)
    }

    /** Called by [AppLog.add] after a line went in: recording line count, floating bubble badge */
    fun onAdded(level: Char) {
        if (recordingOn) recordedLines.incrementAndGet()
        if (level == 'W' || level == 'E') {
            problemCount.incrementAndGet()
            DiagIo.forceSoon()
            if (FloatingLogState.visible.value && FloatingLogState.collapsed.value) {
                FloatingLogState.errorsSinceSeen.update { it + 1 }
            }
        }
    }

    /** The recording state with the live counters filled in (for the strip) */
    fun recordingNow(): RecordingState? = _recording.value?.copy(
        lines = recordedLines.get(),
        traceBytes = TraceWriter.totalBytes(),
    )

    /** The engine's one-second recording tick: publishes the counters to the strip */
    fun tickRecording() {
        recordingNow()?.let { if (_recording.value != null) _recording.value = it }
    }
}

/**
 * KMK (r97): The trace switches read at every emit site: one volatile read, nothing built when off. [netSources]
 * holds JS source keys and APK source ids as decimal strings; empty = every source.
 */
internal object Traces {
    @Volatile var net = false
    @Volatile var js = false
    @Volatile var player = false
    @Volatile var sync = false
    @Volatile var reader = false
    @Volatile var events = false
    @Volatile var netSources: Set<String> = emptySet()
    @Volatile var netHeaders = false
    @Volatile var netBody = false
    @Volatile var recording = false

    private val _playerFlow = MutableStateFlow(false)

    /** For PlayerViewModel's collectors (gated on this or the Live page showing the player) */
    val playerFlow: StateFlow<Boolean> = _playerFlow.asStateFlow()

    fun apply(s: CaptureState) {
        net = TraceKind.NET in s.traces
        js = TraceKind.JS in s.traces
        player = TraceKind.PLAYER in s.traces
        sync = TraceKind.SYNC in s.traces
        reader = TraceKind.READER in s.traces
        events = TraceKind.EVENTS in s.traces
        netSources = s.netTraceSources
        netHeaders = s.netTraceHeaders
        netBody = s.netTraceBodyHead
        _playerFlow.value = player
        TraceWriter.onSwitches(s.traces)
    }

    fun isOn(kind: TraceKind): Boolean = when (kind) {
        TraceKind.NET -> net
        TraceKind.JS -> js
        TraceKind.PLAYER -> player
        TraceKind.SYNC -> sync
        TraceKind.READER -> reader
        TraceKind.EVENTS -> events
    }

    /** Whether the network trace wants this source (null = untagged request, kept when the set is empty) */
    fun wantsSource(key: String?, id: Long?): Boolean {
        val set = netSources
        if (set.isEmpty()) return true
        return (key != null && key in set) || (id != null && id.toString() in set)
    }
}
