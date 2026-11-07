package eu.kanade.tachiyomi.diagnostics

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * KMK (r97): Shared types of the Diagnostics feature (contract `scratchpad/r97/CONTRACT-DIAG.md` §3).
 *
 * Seeded by the integrator; neither half edits it. Half E (`eu.kanade.tachiyomi.diagnostics`, the engine) implements
 * [Diagnostics]; Half F (`eu.kanade.tachiyomi.ui.diagnostics`, the screens) only reads these types and calls the
 * facade through [Diag.get]. Everything here is `internal`, compiles on its own, and imports nothing from the app.
 *
 * Rules kept here on purpose: no `IntRange` in a `@Serializable` class (no built-in serializer), no Android types,
 * no `AppLog.Line` (the engine maps it to [LogEntry] so this file stays standalone).
 */

// ---------------------------------------------------------------------------------------------------------------------
// 1. Capture model
// ---------------------------------------------------------------------------------------------------------------------

/**
 * One switchable group of log lines (design §3.1). [bit] is the position in the engine's volatile masks. [APP] is
 * always on: it can't be turned Off and carries anything whose tag is unknown.
 */
internal enum class DiagCategory(val label: String, val detail: String, val bit: Int) {
    JS("JS sources", "js, js:<name> · verbose: js.call, js.parse", 0),
    NET("Network", "net, botcheck · verbose: net.v", 1),
    BROWSE("Browse & extensions", "extensions, cover, Sideload", 2),
    READER("Reader & images", "reader, pages, ChapterCache", 3),
    PLAYER("Player", "Player, mpv/*, hls, Anime4K", 4),
    LIBRARY("Library & downloads", "Downloader, LibraryUpdateJob", 5),
    SYNC("Pair sync", "sync, SyncService", 6),
    MEMORY("Memory & database", "Memory, db", 7),
    APP("App", "start line, marks, crashes, anything unmatched", 8),
    ;

    val mask: Int get() = 1 shl bit
}

internal enum class DiagLevel(val label: String) { OFF("Off"), NORMAL("Normal"), VERBOSE("Verbose") }

/** A side file of Verbose-level detail (design §3.4); one off-heap ring each while on. */
internal enum class TraceKind(val label: String, val fileStem: String, val detail: String) {
    NET("Network", "net-trace", "method, url, status, ms, bytes, redirects · per source"),
    JS("JS calls", "js-trace", "call, parse, ms, memory before / after"),
    PLAYER(
        "Player", "player-trace",
        "mpv events, buffering, seeks, demuxer cache, dropped frames, HLS pieces, hoster steps",
    ),
    SYNC("Pair sync", "sync-trace", "phases and frames"),
    READER("Reader", "reader-trace", "page loads, cache hits, decode ms"),
    EVENTS("Events", "events-trace", "watchdog thread dumps, marks (allocated on the first stall)"),
}

/** Design §3.3: one tap sets the categories. Everything not listed goes to Normal; traces not listed go off. */
internal enum class DiagPreset(
    val label: String,
    val verbose: Set<DiagCategory>,
    val off: Set<DiagCategory>,
    val traces: Set<TraceKind>,
    /** A preset may also move the sampling slider; null leaves it */
    val sampleMs: Long?,
    val note: String,
) {
    SYNC(
        "Sync", setOf(DiagCategory.SYNC, DiagCategory.NET), setOf(DiagCategory.PLAYER), setOf(TraceKind.SYNC), null,
        "Verbose on Pair sync and Network; sync protocol trace on.",
    ),
    READER(
        "Reader", setOf(DiagCategory.READER, DiagCategory.MEMORY), setOf(DiagCategory.PLAYER, DiagCategory.SYNC),
        setOf(TraceKind.READER), null, "Verbose on Reader and Memory; reader pages trace on.",
    ),
    PLAYER(
        "Player", setOf(DiagCategory.PLAYER, DiagCategory.NET, DiagCategory.JS), setOf(DiagCategory.SYNC),
        setOf(TraceKind.PLAYER), null, "Verbose on Player, Network and JS; mpv, HLS relay and hoster trace on.",
    ),
    BROWSE(
        "Browse", setOf(DiagCategory.JS, DiagCategory.NET, DiagCategory.BROWSE), setOf(DiagCategory.PLAYER),
        setOf(TraceKind.NET, TraceKind.JS), null,
        "Verbose on Browse: auto-off in 24 h. Network trace for the sources open in Browse tabs, JS call trace.",
    ),
    MEMORY(
        "Memory", setOf(DiagCategory.MEMORY, DiagCategory.READER, DiagCategory.BROWSE, DiagCategory.JS), emptySet(),
        emptySet(), 1_000L, "Verbose on Memory, Reader, Browse and JS; sampling 1 s, Memory line every 10 s.",
    ),
    EVERYTHING(
        "Everything", DiagCategory.entries.toSet(), emptySet(), TraceKind.entries.toSet(), null,
        "About 1 MB of log per minute of browsing; the log keeps the last 4,000 lines, traces keep up to 25 MB.",
    ),
}

/** The four stops of the sampling slider (design §2.3). */
internal enum class SampleRate(val ms: Long, val label: String, val costNote: String) {
    FAST(250L, "0.25 s", "0.25 s ≈ 1 % CPU while Live is open"),
    NORMAL(1_000L, "1 s", "1 s ≈ 0.3 % CPU while Live is open"),
    SLOW(5_000L, "5 s", "5 s ≈ 0.1 % CPU while Live is open"),
    IDLE(30_000L, "30 s", "30 s: next to nothing"),
    ;

    companion object {
        fun of(ms: Long): SampleRate = entries.minByOrNull { kotlin.math.abs(it.ms - ms) } ?: NORMAL
    }
}

internal enum class RecordDuration(val label: String, val millis: Long?) {
    M1("1 min", 60_000L),
    M5("5 min", 300_000L),
    M15("15 min", 900_000L),
    UNTIL_STOPPED("Until stopped", null),
}

/** What the Capture tab shows and edits. One object per change; the engine is the only writer. */
@Immutable
internal data class CaptureState(
    val levels: Map<DiagCategory, DiagLevel> = DiagCategory.entries.associateWith { DiagLevel.NORMAL },
    /** Epoch ms when a Verbose category drops back to Normal (24 h after it was set) */
    val verboseUntil: Map<DiagCategory, Long> = emptyMap(),
    val traces: Set<TraceKind> = emptySet(),
    /** Network trace: JS source keys and APK source ids the owner picked; empty = all sources */
    val netTraceSources: Set<String> = emptySet(),
    val netTraceHeaders: Boolean = false,
    val netTraceBodyHead: Boolean = false,
    /** Bytes written in each trace ring right now (0 when off) */
    val traceBytes: Map<TraceKind, Long> = emptyMap(),
    val sampleMs: Long = SampleRate.NORMAL.ms,
    /** The last preset tapped, until a category is changed by hand */
    val preset: DiagPreset? = null,
    /** Tag → category overrides the owner made from the Log tab ("Move tag … to category") */
    val tagOverrides: Map<String, DiagCategory> = emptyMap(),
) {
    val anyVerbose: Boolean get() = levels.values.any { it == DiagLevel.VERBOSE }
    val earliestAutoOff: Long? get() = verboseUntil.values.minOrNull()
}

@Immutable
internal data class RecordingState(
    val startedAt: Long,
    val duration: RecordDuration,
    /** null while "until stopped" */
    val endsAt: Long?,
    val lines: Int,
    val traceBytes: Long,
) {
    fun remainingMs(now: Long): Long? = endsAt?.let { (it - now).coerceAtLeast(0L) }
}

// ---------------------------------------------------------------------------------------------------------------------
// 2. Live samples and the status strip
// ---------------------------------------------------------------------------------------------------------------------

/** The resource switcher on the Live tab (design D layout: one graph per resource). */
internal enum class Resource(val label: String, val unit: String) {
    NETWORK("Network", "MB/s"),
    MEMORY("Memory", "MB"),
    WORKERS("Workers", "threads"),
    DATABASE("Database", "q/s"),
    PLAYER("Player", "s"),
}

/** The time chips. [stepMs] is the ring resolution the engine keeps for that window; [points] its length. */
internal enum class TimeWindow(val label: String, val seconds: Long, val stepMs: Long) {
    M1("1 min", 60L, 0L),
    M5("5 min", 300L, 0L),
    H1("1 h", 3_600L, 10_000L),
    H24("24 h", 86_400L, 300_000L),
    D7("7 d", 604_800L, 3_600_000L),
    ;

    /** M1 and M5 share the fine ring whose step is the sampling interval; the engine fills stepMs at runtime */
    val isFine: Boolean get() = stepMs == 0L
}

/** Fixed palette slots; Half F maps them to colours so a resource looks the same everywhere (design §2.1). */
internal enum class ColorKey {
    SOURCE_0, SOURCE_1, SOURCE_2, SOURCE_3, SOURCE_4, SOURCE_5, OTHER, UPLOAD, COVERS,
    HEAP, IMAGES, JS, NATIVE, TRIM_LINE,
    IO_BUSY, DEFAULT_BUSY, JS_BUSY, BLOCKED, PARKED, FREE,
    DB_QUERIES, DB_WAL,
    PLAYER_CACHE, PLAYER_DROPPED,
    PROBE, MARK, ERROR, STALL, TRIM, SYNC, UPDATE, CRASH,
}

/**
 * One stacked band (or the dashed overlay) of a graph. [values] run oldest → newest at the window's step; `NaN`
 * means no sample (the Live page wasn't open). The array belongs to this object and is never mutated after
 * publication, so the Canvas may keep a Path built from it until the next [SampleWindow] arrives.
 */
@Immutable
internal class SampleSeries(
    val id: String,
    val label: String,
    val color: ColorKey,
    val values: FloatArray,
    /** Value "now", formatted by F with the resource unit */
    val current: Float,
)

/** What the graph draws: published once per tick for the (resource, window) F asked for. Never per frame. */
@Immutable
internal class SampleWindow(
    val resource: Resource,
    val window: TimeWindow,
    /** Epoch ms of values[0] */
    val startMs: Long,
    val stepMs: Long,
    /** Stacked bottom → top */
    val series: List<SampleSeries>,
    /** Dashed line drawn on top (upload for Network, the trim line for Memory); may be null */
    val overlay: SampleSeries?,
    /** Top-of-scale label value; F rounds it to a nice number only for the label, never for the scale */
    val topOfScale: Float,
    val headline: String,
    val subline: String,
    val markers: List<Marker>,
) {
    val endMs: Long get() = startMs + stepMs * (series.firstOrNull()?.values?.size ?: 0)
}

internal enum class MarkerKind(val label: String, val color: ColorKey, val isIncident: Boolean) {
    MARK("MARK", ColorKey.MARK, false),
    PROBE("PROBE", ColorKey.PROBE, false),
    ERROR("ERROR", ColorKey.ERROR, true),
    STALL("STALL", ColorKey.STALL, true),
    TRIM("TRIM", ColorKey.TRIM, true),
    SYNC("SYNC", ColorKey.SYNC, false),
    UPDATE("UPDATE", ColorKey.UPDATE, false),
    CRASH("CRASH", ColorKey.CRASH, true),
    RECORD("REC", ColorKey.MARK, false),
}

/** An event drawn on the graph's marker row and on the scrub slider; [logTime] jumps the Log tab to ±30 s. */
@Immutable
internal data class Marker(
    val timeMs: Long,
    val kind: MarkerKind,
    val label: String,
    /** `probe:<name>`, `net`, `sync`… for the Log filter */
    val tag: String,
)

/** The card under the cursor when the scrub slider or a tap picks a time (ph-02). */
@Immutable
internal data class CursorInfo(
    val timeMs: Long,
    val total: Float,
    val unit: String,
    val perSeries: List<Pair<SampleSeries, Float>>,
    /** Network only: the requests that finished in that second (from the net ring); empty for other resources */
    val requests: List<NetRequestRow>,
    val note: String?,
)

@Immutable
internal data class NetRequestRow(
    val timeMs: Long,
    val method: String,
    val host: String,
    val path: String,
    val status: Int,
    val ms: Long,
    val bytes: Long,
    val source: String?,
    val color: ColorKey,
)

internal enum class UsageBy(val label: String) { SOURCE("Source"), HOST("Host") }
internal enum class UsageMetric(val label: String) { BYTES("bytes"), REQUESTS("requests"), AVG_MS("avg ms") }

/** One row of the "Network usage" table. */
@Immutable
internal data class UsageRow(
    val name: String,
    val hosts: String,
    val bytes: Long,
    val requests: Int,
    val avgMs: Long,
    val errors: Int,
    val color: ColorKey,
)

internal enum class HealthState(val label: String) { GOOD("Good"), ATTENTION("Attention"), PROBLEM("Problem") }

/** A one-tap fix offered by the strip sentence when something needs doing. */
internal enum class StripAction(val label: String) {
    OPEN_HEALTH("open in Health"),
    FREE_MEMORY("free memory"),
    CLOSE_IDLE_JS("close idle JS sources"),
    THREAD_DUMP("thread dump"),
    OPEN_LOG("open the Log"),
}

/** Option B strip on every tab: pill + four figures with bars (ph-01). Published once per tick while a UI shows it. */
@Immutable
internal data class StatusStrip(
    val health: HealthState = HealthState.GOOD,
    val sentence: String = "",
    val action: StripAction? = null,
    val workersBusy: Int = 0,
    val workersTotal: Int = 64,
    val heapMb: Int = 0,
    val heapMaxMb: Int = 512,
    val nativeMb: Int = 0,
    val netMbps: Float = 0f,
    /** Scale of the network bar; the engine raises it when the rate passes it */
    val netMaxMbps: Float = 4f,
    val sampleMs: Long = SampleRate.NORMAL.ms,
    val lastStallText: String? = null,
)

/** Memory resource page (ph-03): the two limits and the engines table. */
@Immutable
internal data class MemoryBreakdown(
    val javaMb: Int = 0,
    val javaMaxMb: Int = 512,
    val imagesMb: Int = 0,
    val imagesMaxMb: Int = 0,
    val jsMb: Int = 0,
    val jsCapMb: Int = 128,
    val nativeMb: Int = 0,
    val diagMb: Int = 0,
    val rssMb: Int = 0,
    val deviceTotalMb: Int = 0,
    val trimAtMb: Int = 384,
    val lastTrimAt: Long? = null,
    val lastTrimText: String? = null,
    val browseTabs: Int = 0,
    val folderListings: Int = 0,
    val animatedTiles: Int = 0,
    val jsPages: Int = 0,
    val jsPageTextMb: Int = 0,
    val jsDomMb: Int = 0,
    val engines: List<JsEngineRow> = emptyList(),
)

@Immutable
internal data class JsEngineRow(
    val key: String,
    val name: String,
    val mb: Float,
    val pages: Int,
    val busy: Boolean,
    /** The memory figure is from an earlier tick because the runtime was busy (amber dot) */
    val stale: Boolean,
    val color: ColorKey,
)

/** Workers page (ph-04): the slot grid and the last grouped dump. */
@Immutable
internal data class WorkerSnapshot(
    val ioBusy: Int = 0,
    val ioParked: Int = 0,
    val ioBlocked: Int = 0,
    val ioTotal: Int = 64,
    val defaultBusy: Int = 0,
    val defaultTotal: Int = 0,
    val jsBusy: Int = 0,
    val threads: Int = 0,
    /** One byte per IO slot: 0 free, 1 running, 2 parked, 3 blocked; length = ioTotal */
    val slots: ByteArray = ByteArray(0),
    val starved: Boolean = false,
    val lastDump: ThreadDump? = null,
    val mainWatchdogArmed: Boolean = true,
    val ioWatchdogArmed: Boolean = true,
    val lastStallAt: Long? = null,
    val lastStallText: String? = null,
)

@Serializable
internal data class ThreadDumpGroup(
    val count: Int,
    /** RUNNABLE, WAITING, TIMED_WAITING, BLOCKED */
    val state: String,
    /** Top frames, innermost first; at most 6 */
    val frames: List<String>,
    /** "parked in caseTwinNames → runBlocking" */
    val title: String,
)

@Serializable
internal data class ThreadDump(
    val timeMs: Long,
    val threads: Int,
    val groups: List<ThreadDumpGroup>,
    /** "71 threads, 48 parked in caseTwinNames → runBlocking" */
    val summary: String,
)

internal enum class JobState(val label: String) { RUNNING("running"), OK("ok"), WARN("warn"), IDLE("idle") }

/** A row of "Running now" / "Engines" (design §2.1 items 3–4). */
@Immutable
internal data class JobRow(
    val id: String,
    val name: String,
    val sub: String?,
    val progress: String,
    val state: JobState,
    /** Resource page to open on tap, when any */
    val opens: Resource?,
)

@Immutable
internal data class LiveTables(
    val running: List<JobRow> = emptyList(),
    val engines: List<JobRow> = emptyList(),
    val storage: StorageSnapshot? = null,
)

@Immutable
internal data class StorageSnapshot(
    val downloadsBytes: Long?,
    val cacheBytes: Long?,
    val readerCacheBytes: Long?,
    val freeBytes: Long,
    val dbBytes: Long,
    val walBytes: Long,
    /** null while the download tree walk runs ("…") */
    val computedAt: Long?,
)

/** Player page: live mpv figures while an episode plays, null otherwise. */
@Immutable
internal data class PlayerLive(
    val title: String,
    val source: String?,
    val posS: Int,
    val durationS: Int,
    val demuxerCacheS: Float,
    val pausedForCache: Boolean,
    val seeking: Boolean,
    val droppedFrames: Long,
    val vcodec: String?,
    val acodec: String?,
    val hwdec: String?,
    val width: Int,
    val height: Int,
    val fps: Float,
    val relayServed: Int,
    val relayBytes: Long,
    val relayErrors: Int,
    val lastHosterSteps: List<String>,
)

// ---------------------------------------------------------------------------------------------------------------------
// 3. Log entries (the Log tab, the floating window, Health excerpts)
// ---------------------------------------------------------------------------------------------------------------------

/** One log line as the UI sees it: `AppLog.Line` plus the category and the parsed `key=value` tail (design §8). */
@Immutable
internal data class LogEntry(
    val time: Long,
    /** V, D, I, W, E */
    val level: Char,
    val tag: String,
    val message: String,
    val category: DiagCategory,
    /** The structured tail after " · ", already split; empty when the line has none */
    val fields: Map<String, String>,
    /** `+12.345s W tag message`, exactly AppLog.format() */
    val text: String,
) {
    val isProblem: Boolean get() = level == 'W' || level == 'E'
    val isInfo: Boolean get() = level == 'I' || isProblem
    val isMark: Boolean get() = tag == "app" && message.startsWith("=== MARK")
}

internal enum class LogLevelFilter(val label: String) { ALL("All"), INFO("Info"), PROBLEMS("Problems") }

/**
 * The Log tab's filter. [query] is free text, or `key=value` / `key>number` / `key<number` on the structured
 * fields (the engine parses it; F passes it through).
 */
@Immutable
internal data class LogFilter(
    val level: LogLevelFilter = LogLevelFilter.ALL,
    val categories: Set<DiagCategory> = emptySet(),
    /** Exact tags (chips such as `probe:mitaku`, `js`, `sync`) */
    val tags: Set<String> = emptySet(),
    val query: String = "",
    /** ±[aroundMs] around [aroundTime] when a Health dot or a marker was tapped */
    val aroundTime: Long? = null,
    val aroundMs: Long = 30_000L,
)

/** Chips the Log tab offers: categories and tags that have lines in the snapshot, ordered by count. */
@Immutable
internal data class LogChips(
    val problems: Int,
    val marks: Int,
    val categories: List<Pair<DiagCategory, Int>>,
    val tags: List<Pair<String, Int>>,
    /** The three most frequent `source=` values */
    val sources: List<Pair<String, Int>>,
)

// ---------------------------------------------------------------------------------------------------------------------
// 4. Trace events (one JSON line each in the trace rings; also the payload probes see)
// ---------------------------------------------------------------------------------------------------------------------

@Serializable
internal data class NetTraceEvent(
    val t: Long,
    val source: String? = null,
    val method: String,
    val url: String,
    val status: Int,
    val ms: Long,
    val bytesIn: Long,
    val bytesOut: Long = 0L,
    val redirects: List<String> = emptyList(),
    val fromCache: Boolean = false,
    val protocol: String? = null,
    val contentType: String? = null,
    val error: String? = null,
    /** Only with the headers switch; Cookie / Set-Cookie / Authorization values are `<n chars>` */
    val reqHeaders: Map<String, String>? = null,
    val respHeaders: Map<String, String>? = null,
    /** Only with the body-head switch and a text content type; first 8 KB */
    val bodyHead: String? = null,
)

/** mpv events, buffering, seeks, demuxer cache, dropped frames (design §3.4 "Player"). */
@Serializable
internal data class PlayerTraceEvent(
    val t: Long,
    /** FILE_LOADED, PLAYBACK_RESTART, END_FILE, BUFFER_START, BUFFER_END, SEEK_START, SEEK_END, POLL, PROFILE */
    val event: String,
    val posS: Float = 0f,
    val demuxerCacheS: Float = 0f,
    val pausedForCache: Boolean = false,
    val dropped: Long = 0L,
    val vcodec: String? = null,
    val acodec: String? = null,
    val hwdec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val ms: Long = 0L,
    val detail: String? = null,
)

@Serializable
internal data class HlsTraceEvent(
    val t: Long,
    /** playlist or segment */
    val kind: String,
    val host: String,
    val ms: Long,
    val bytes: Long,
    val status: Int,
    val retries: Int = 0,
    val rewritten: Boolean = false,
    val fakeImageStripped: Boolean = false,
    val error: String? = null,
)

@Serializable
internal data class HosterTraceEvent(
    val t: Long,
    /** hosterList, videoList, chosen, relayWrap */
    val step: String,
    val source: String? = null,
    val hoster: String? = null,
    val ms: Long,
    val count: Int = 0,
    val error: String? = null,
)

@Serializable
internal data class JsTraceEvent(
    val t: Long,
    val source: String,
    val fn: String,
    /** First 120 chars, URLs kept whole, strings truncated */
    val args: String,
    val ms: Long,
    val hostCalls: Int,
    val pagesParsed: Int,
    val parseMs: Long,
    val memBefore: Long,
    val memAfter: Long,
    val items: Int = -1,
    val error: String? = null,
    /** First 6 `at fn (source.js:142)` lines of a failure */
    val jsStack: List<String> = emptyList(),
)

@Serializable
internal data class SyncTraceEvent(
    val t: Long,
    /** Idle, Asking, Waiting, Transferring, Applying, or a step name */
    val phase: String,
    /** send / receive / phase */
    val direction: String,
    val frameType: String? = null,
    val bytes: Long = 0L,
    val ms: Long = 0L,
    val peer: String? = null,
    val detail: String? = null,
)

@Serializable
internal data class ReaderTraceEvent(
    val t: Long,
    val chapter: String,
    val page: Int,
    /** Http, Download, Archive, Directory, Epub */
    val loader: String,
    val fetchMs: Long,
    val cacheHit: Boolean,
    val bytes: Long,
    val decodeMs: Long,
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val error: String? = null,
)

/** Written into the EVENTS ring by the watchdogs and "Thread dump now". */
@Serializable
internal data class DumpTraceEvent(
    val t: Long,
    /** main-stall, io-starved, io-recovered, manual, mark */
    val kind: String,
    val title: String,
    val dump: ThreadDump? = null,
)

// ---------------------------------------------------------------------------------------------------------------------
// 5. Health (7-day timeline, previous run)
// ---------------------------------------------------------------------------------------------------------------------

internal enum class HealthKind(val label: String, val badge: String, val marker: MarkerKind, val bad: Boolean) {
    CRASH("Crash", "CRASH", MarkerKind.CRASH, true),
    NATIVE_CRASH("Native crash", "CRASH", MarkerKind.CRASH, true),
    ANR("Not responding (ANR)", "ANR", MarkerKind.CRASH, true),
    LMK("Killed, low memory", "LMK", MarkerKind.CRASH, true),
    EXIT_OTHER("Process ended", "EXIT", MarkerKind.CRASH, false),
    STALL_MAIN("Main thread stalled", "STALL", MarkerKind.STALL, true),
    STALL_IO("IO pool starved", "STALL", MarkerKind.STALL, true),
    TRIM("Memory low, caches cleared", "TRIM", MarkerKind.TRIM, true),
    UPDATE_OK("Library update", "UPDATE", MarkerKind.UPDATE, false),
    UPDATE_FAILED("Library update failed", "UPDATE", MarkerKind.UPDATE, true),
    SYNC_OK("Pair sync", "SYNC", MarkerKind.SYNC, false),
    SYNC_FAILED("Pair sync failed", "SYNC", MarkerKind.SYNC, true),
    RESTORE_OK("Restore", "RESTORE", MarkerKind.UPDATE, false),
    RESTORE_FAILED("Restore failed", "RESTORE", MarkerKind.UPDATE, true),
    DOWNLOAD_ERROR("Download stopped", "DOWNLOAD", MarkerKind.ERROR, true),
    RECORDING("Recording", "REC", MarkerKind.RECORD, false),
    MARK("Mark", "MARK", MarkerKind.MARK, false),
}

@Serializable
internal data class HealthEvent(
    val id: Long,
    val time: Long,
    val kind: HealthKind,
    val title: String,
    val detail: String,
    /** Offset into the log ring of a 4 KB excerpt kept with the event, or -1 */
    val excerptOffset: Long = -1L,
    /** Stalls only */
    val dump: ThreadDump? = null,
    /** ANR exits: `health/anr-<ts>.txt` */
    val traceFile: String? = null,
    /** The run (AppLog.sessionStart) the event belongs to */
    val session: Long = 0L,
)

@Serializable
internal data class PreviousRun(
    val endedAt: Long,
    /** ApplicationExitInfo reason as text: "low memory (LMK)", "crash", "ANR", "user", "excessive resource use"… */
    val reason: String,
    val reasonCode: Int,
    val description: String?,
    val pssKb: Long,
    val rssKb: Long,
    val importance: Int,
    val screen: String?,
    val lastMemoryLine: String?,
    val uptimeMs: Long,
    val linesKept: Int,
    val traceFile: String? = null,
)

@Immutable
internal data class HealthSnapshot(
    val events: List<HealthEvent> = emptyList(),
    val previousRun: PreviousRun? = null,
    val sinceMs: Long = 0L,
    val counts: Map<HealthKind, Int> = emptyMap(),
    val watchdogsOn: Boolean = true,
    val lastStallText: String? = null,
)

// ---------------------------------------------------------------------------------------------------------------------
// 6. Report
// ---------------------------------------------------------------------------------------------------------------------

internal enum class ReportPart(val label: String, val description: String, val defaultOn: Boolean) {
    LOG("Log", "whole log + log-ring.tsv", true),
    LIVE("Live snapshot", "memory, JS, workers, DB, network, jobs · 5-min rings", true),
    DEVICE("Device & app", "build, Android, model, memory, network type", true),
    SOURCES("Extensions & JS sources", "versions, memory, running", true),
    PROBE_CONSOLE("Probe console", "the scripts that ran and their lines", true),
    SETTINGS("Settings summary", "non-default settings only · secrets as <set>", true),
    TRACES("Traces", "sync, net, js traces (gzip)", true),
    HEALTH("Health", "7-day events · previous run · anr-*.txt", true),
    PLAYER_LOG("Player log", "mpv lines from the last playback", false),
}

@Immutable
internal data class ReportOptions(
    val parts: Set<ReportPart> = ReportPart.entries.filter { it.defaultOn }.toSet(),
    /** "Strip source names and URLs": source-<id>, host-<n> in every file */
    val redact: Boolean = true,
    /** Adds capture.json: levels, traces, sampling, probes */
    val includeCapture: Boolean = true,
)

@Immutable
internal data class ReportPartSize(val part: ReportPart, val bytes: Long, val detail: String)

@Immutable
internal data class ReportResult(
    val path: String,
    val bytes: Long,
    val createdAt: Long,
    /** The local-only mapping file next to the zip (not shared) when redaction ran */
    val keysPath: String?,
)

@Immutable
internal data class ReportState(
    val sizes: List<ReportPartSize> = emptyList(),
    val estimateBytes: Long = 0L,
    val building: Boolean = false,
    val progress: String? = null,
    val last: ReportResult? = null,
    /** A recording just ended and built this report; the Report tab opens on it and offers Share */
    val pendingShare: ReportResult? = null,
)

// ---------------------------------------------------------------------------------------------------------------------
// 7. Probes
// ---------------------------------------------------------------------------------------------------------------------

/** The hook chips (ph-08). [id] is what `probe.on('…')` takes; [bit] the position in the engine's probe mask. */
internal enum class ProbeHook(val id: String, val bit: Int, val reference: List<Pair<String, String>>) {
    NET_REQUEST(
        "net.request", 0,
        listOf(
            "r.url · r.method" to "string",
            "r.source" to "source name or null",
            "r.header(n)" to "cookies and auth are never passed",
        ),
    ),
    NET_RESPONSE(
        "net.response", 1,
        listOf(
            "r.url · r.status" to "string · number",
            "r.size · r.ms" to "bytes · duration",
            "r.text(max)" to "body up to max bytes, from the head",
            "r.header(n)" to "cookies and auth are never passed",
            "r.replaceBody(s)" to "Experimental only",
        ),
    ),
    JS_CALL("js.call", 2, listOf("e.source · e.fn" to "string", "e.args" to "first 120 chars")),
    JS_RESULT(
        "js.result", 3,
        listOf(
            "e.ms · e.items" to "duration · count",
            "e.memBefore · e.memAfter" to "bytes",
            "e.error · e.jsStack" to "failure",
        ),
    ),
    PLAYER_EVENT(
        "player.event", 4,
        listOf(
            "e.event" to "FILE_LOADED, BUFFER_START…",
            "e.posS · e.demuxerCacheS" to "seconds",
            "e.dropped" to "frames",
        ),
    ),
    HLS_SEGMENT(
        "hls.segment", 5,
        listOf("e.kind" to "playlist or segment", "e.host · e.ms · e.bytes · e.status" to ""),
    ),
    HOSTER_STEP(
        "hoster.step", 6,
        listOf("e.step" to "hosterList, videoList, chosen, relayWrap", "e.hoster · e.ms · e.count" to ""),
    ),
    READER_PAGE("reader.page", 7, listOf("e.page · e.loader" to "", "e.fetchMs · e.decodeMs · e.cacheHit" to "")),
    SYNC_PHASE("sync.phase", 8, listOf("e.phase · e.direction" to "", "e.bytes · e.ms · e.peer" to "")),
    ;

    val mask: Int get() = 1 shl bit

    companion object {
        fun byId(id: String): ProbeHook? = entries.firstOrNull { it.id == id }
    }
}

internal enum class ProbeAutoOff(val label: String, val hours: Int) {
    H1("1 h", 1), H4("4 h", 4), H24("24 h", 24), NEVER("never", 0),
}

internal enum class ProbeBudget(val label: String, val msPerEvent: Float) {
    HALF("0.5 ms", 0.5f), ONE("1 ms", 1f), TWO("2 ms", 2f), FIVE("5 ms", 5f),
}

/** `filesDir/diag/probes/probes.json` entry; the code is in `<id>.js` next to it. */
@Serializable
internal data class ProbeDescriptor(
    val id: String,
    val name: String,
    /** Hook ids declared by `probe.on` calls, read at save by a static scan and confirmed at first run */
    val hooks: List<String>,
    val enabled: Boolean = false,
    /** May modify data: r.replaceBody() and result edits allowed; badge shown */
    val experimental: Boolean = false,
    val autoOff: ProbeAutoOff = ProbeAutoOff.H24,
    val budget: ProbeBudget = ProbeBudget.TWO,
    val markerOnGraph: Boolean = true,
    /** Filter the owner typed in the editor header ("source mitaku"); the script's own filter still applies */
    val sourceFilter: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Epoch ms when it switches itself off, 0 = never */
    val autoOffAt: Long = 0L,
) {
    val hookSet: Set<ProbeHook> get() = hooks.mapNotNull(ProbeHook::byId).toSet()
}

@Immutable
internal data class ProbeStatus(
    val id: String,
    val running: Boolean,
    val lastRunAt: Long?,
    val events: Long,
    val avgMs: Float,
    val maxMs: Float,
    val errors: Int,
    val lastError: String?,
    /** Why the engine switched it off (budget, auto-off, exception storm), null while on */
    val offReason: String?,
    val dropped: Long,
)

@Immutable
internal data class ProbesSnapshot(
    val probes: List<ProbeDescriptor> = emptyList(),
    val status: Map<String, ProbeStatus> = emptyMap(),
    val runningCount: Int = 0,
    val totalMsPerEvent: Float = 0f,
    val experimentalCount: Int = 0,
    /** The probe runtime's QuickJS memory, MB */
    val runtimeMb: Float = 0f,
)

@Immutable
internal data class ConsoleLine(
    val time: Long,
    /** D, I, W, E */
    val level: Char,
    val probeId: String,
    val probeName: String,
    val text: String,
)

@Immutable
internal data class ProbeDraft(
    /** null = new */
    val id: String?,
    val name: String,
    val code: String,
)

@Immutable
internal data class ProbeRunResult(
    val ok: Boolean,
    /** Events replayed from the rings for Run once */
    val replayed: Int,
    val ms: Float,
    val error: String?,
    /** Line (1-based) of a syntax error, when known */
    val errorLine: Int?,
)

/** Static checks the editor shows before a save (unknown hook id, `replaceBody` without Experimental…). */
@Immutable
internal data class ProbeLint(val line: Int, val message: String, val isError: Boolean)

// ---------------------------------------------------------------------------------------------------------------------
// 8. Screen state shared across halves
// ---------------------------------------------------------------------------------------------------------------------

internal enum class DiagTab(val label: String) {
    LIVE("Live"), LOG("Log"), CAPTURE("Capture"), HEALTH("Health"), REPORT("Report"), PROBES("Probes")
}

/**
 * The floating window (design §2.6): a process singleton so it keeps its content across MainActivity, the reader
 * and the player. F composes it; E reads [visible] to run the reduced sampler, and the notification's "Float"
 * action and the tile flip [visible]. Nothing here allocates while hidden.
 */
internal object FloatingLogState {
    val visible = MutableStateFlow(false)
    val collapsed = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val tagFilter = MutableStateFlow<String?>(null)
    val errorsSinceSeen = MutableStateFlow(0)
    /** KMK (r132): warnings since the bubble was last opened (amber count next to the red one) */
    val warningsSinceSeen = MutableStateFlow(0)
    /** x, y, w, h in px for the current orientation; null = default corner */
    val rect = MutableStateFlow<IntArray?>(null)
}

// ---------------------------------------------------------------------------------------------------------------------
// 9. The engine's public facade
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Everything the screens need. Implemented by Half E (`DiagnosticsEngine`), installed from `App.onCreate` right
 * after `AppLog.init`. All `StateFlow`s are cheap to collect: nothing samples until [setLiveVisible] or
 * [FloatingLogState.visible] says a graph is on screen. `suspend` members run on the engine's own dispatcher; none
 * touches the main thread.
 */
internal interface Diagnostics {

    // --- state the UI collects --------------------------------------------------------------------------------------

    val strip: StateFlow<StatusStrip>
    val capture: StateFlow<CaptureState>
    val recording: StateFlow<RecordingState?>
    val health: StateFlow<HealthSnapshot>
    val report: StateFlow<ReportState>
    val probes: StateFlow<ProbesSnapshot>
    /** Newest last; capped at 500 lines; cleared by [clearConsole] */
    val console: StateFlow<List<ConsoleLine>>
    val memory: StateFlow<MemoryBreakdown>
    val workers: StateFlow<WorkerSnapshot>
    val tables: StateFlow<LiveTables>
    val player: StateFlow<PlayerLive?>
    /** Bumps whenever lines are added or cleared (mirrors AppLog.version) */
    val logVersion: StateFlow<Int>
    /** Markers of the last 7 days (marks, probes, incidents), newest last */
    val markers: StateFlow<List<Marker>>

    /** The window F draws; one flow per (resource, window), created lazily and shared. */
    fun samples(resource: Resource, window: TimeWindow): StateFlow<SampleWindow>

    // --- visibility gates (zero cost when nothing is visible) -------------------------------------------------------

    /** The Live tab or a resource page is composed; the sampler runs at [CaptureState.sampleMs] while true */
    fun setLiveVisible(visible: Boolean)

    /** Any strip is composed (every Diagnostics tab); a 1 s strip-only tick runs while true and Live is off */
    fun setStripVisible(visible: Boolean)

    // --- log --------------------------------------------------------------------------------------------------------

    /** Newest first; at most [limit] after the filter; never more than AppLog.MAX_LINES */
    fun logLines(filter: LogFilter, limit: Int = 4000): List<LogEntry>

    /** The newest [n] lines, oldest first, for the floating window; allocates only the rows asked for */
    fun logTail(n: Int, tag: String?, problemsOnly: Boolean): List<LogEntry>

    fun logChips(): LogChips

    fun categoryOf(tag: String): DiagCategory

    fun setTagCategory(tag: String, category: DiagCategory)

    fun clearLog()

    /** KMK (r140): the log, Health events, saved traces and report files, all back to zero */
    fun clearEverything()

    fun mark(note: String?)

    // --- capture ----------------------------------------------------------------------------------------------------

    fun setLevel(category: DiagCategory, level: DiagLevel)

    fun applyPreset(preset: DiagPreset)

    fun setTrace(kind: TraceKind, on: Boolean)

    fun setNetTraceSources(sources: Set<String>)

    fun setNetTraceOptions(headers: Boolean, bodyHead: Boolean)

    fun setSampleMs(ms: Long)

    /** Candidate sources for the network trace chips: key/id → display name */
    suspend fun traceableSources(): List<Pair<String, String>>

    fun record(duration: RecordDuration)

    fun stopRecording()

    // --- live actions -----------------------------------------------------------------------------------------------

    suspend fun threadDump(): ThreadDump

    suspend fun trimMemory(): String

    suspend fun closeIdleRuntimes(): Int

    suspend fun reindexDownloads()

    suspend fun cursor(resource: Resource, window: TimeWindow, timeMs: Long): CursorInfo

    suspend fun usage(by: UsageBy, window: TimeWindow): List<UsageRow>

    /** Writes a CSV of [usage] into cacheDir and returns its path */
    suspend fun usageCsv(by: UsageBy, window: TimeWindow): String

    /** The Memory-line format followed by one line per tile; under 300 000 chars by construction */
    fun liveSnapshotText(): String

    // --- health -----------------------------------------------------------------------------------------------------

    /** The 4 KB excerpt kept with an event, or the log lines ±30 s when the run is the current one */
    suspend fun healthExcerpt(event: HealthEvent): List<LogEntry>

    /** Writes health.json (and the ANR files) into a cacheDir zip and returns its path */
    suspend fun exportHealth(): String

    // --- report -----------------------------------------------------------------------------------------------------

    suspend fun refreshReportSizes(): List<ReportPartSize>

    suspend fun createReport(options: ReportOptions): ReportResult

    fun consumePendingShare()

    // --- probes -----------------------------------------------------------------------------------------------------

    suspend fun probeCode(id: String): String

    fun lintProbe(code: String): List<ProbeLint>

    suspend fun saveProbe(draft: ProbeDraft): ProbeDescriptor

    suspend fun deleteProbe(id: String)

    fun setProbeEnabled(id: String, on: Boolean)

    fun setProbeExperimental(id: String, on: Boolean)

    fun setProbeAutoOff(id: String, autoOff: ProbeAutoOff)

    fun setProbeBudget(id: String, budget: ProbeBudget)

    fun setProbeMarker(id: String, on: Boolean)

    /** Replays the newest matching events from the rings through the script (saved code) and returns the outcome */
    suspend fun runProbeOnce(id: String): ProbeRunResult

    /** Imports a `.js` file's text as a new probe; the name comes from `// @name` or the file name */
    suspend fun importProbe(fileName: String, code: String): ProbeDescriptor

    fun clearConsole(probeId: String?)

    /** A short script with the hook's event fields, for "New script" */
    fun probeTemplate(hook: ProbeHook): String
}

/** How the screens reach the engine without DI (the engine is installed before any screen exists). */
internal object Diag {
    @Volatile
    var engine: Diagnostics? = null

    fun get(): Diagnostics = engine ?: error("Diagnostics engine not installed")
}

// ---------------------------------------------------------------------------------------------------------------------
// 10. Constants shared by both halves
// ---------------------------------------------------------------------------------------------------------------------

/** Preference keys in the `diagnostics` SharedPreferences file (not the default file, so backups don't carry them). */
internal object DiagKeys {
    const val PREFS_FILE = "diagnostics"
    const val CAPTURE = "capture" // "JS=V;NET=V;PLAYER=O"
    const val VERBOSE_UNTIL = "verbose_until" // JSON {"JS":1730000000000}
    const val TRACES = "traces" // "NET,JS"
    const val NET_TRACE_SOURCES = "net_trace_sources" // comma list
    const val NET_TRACE_HEADERS = "net_trace_headers"
    const val NET_TRACE_BODY = "net_trace_body"
    const val SAMPLE_MS = "sample_ms"
    const val PRESET = "preset"
    const val TAG_OVERRIDES = "tag_overrides" // JSON {"SomeClass":"READER"}
    const val RECORDING = "recording" // JSON {startedAt, duration, endsAt} while a recording runs
    const val RECORDING_SAVED_LEVELS = "recording_saved_levels"
    const val LAST_REPORT = "last_report" // JSON ReportResult
    const val LAST_TAB = "last_tab"
    const val SEEN_VERSION = "seen_version"
    const val LIVE_ADVANCED = "live_advanced"
    const val FLOAT_ON = "float_on"
    const val FLOAT_RECT_PORTRAIT = "float_rect_p"
    const val FLOAT_RECT_LANDSCAPE = "float_rect_l"
    const val LAST_EXIT_SEEN = "last_exit_seen" // timestamp of the newest ApplicationExitInfo turned into an event
    const val HEALTH_NEXT_ID = "health_next_id"
}

/** Files. `cacheDir/diag/` may be wiped by the system; `filesDir/diag/` is what the owner wrote. */
internal object DiagPaths {
    const val CACHE_DIR = "diag"
    /**
     * KMK (r103): under filesDir (the system may wipe cacheDir, and the ring is the log's only store since r103).
     * [LEGACY_LOG_RING] / [LEGACY_LOG_RING_OLD] are the r97–r102 places under cacheDir, moved once by AppLog.init.
     */
    const val LOG_RING = "app_log/log.ring"
    const val LOG_RING_OLD = "app_log/log.ring.old"
    const val LEGACY_LOG_RING = "diag/log.ring"
    const val LEGACY_LOG_RING_OLD = "diag/log.ring.old"
    const val SAMPLES_BIN = "diag/samples.bin"
    const val HEALTH_JSON = "diag/health.json"
    const val HEALTH_DIR = "diag/health"
    const val REPORT_PREFIX = "komikku-report-"
    const val REPORT_KEYS_SUFFIX = "-keys.json"
    const val USAGE_CSV = "komikku-net-usage.csv"
    const val HEALTH_ZIP = "komikku-health.zip"
    const val LOG_TEXT = "komikku-logs.txt"
    const val FILES_DIR = "diag"
    const val PROBES_DIR = "diag/probes"
    const val PROBES_INDEX = "diag/probes/probes.json"
    const val PROBE_PRELUDE_ASSET = "diagnostics/probe-prelude.js"
    const val APP_LOG_FILE = "app_log/log.txt"

    fun traceRing(kind: TraceKind): String = "diag/${kind.fileStem}.ring"
    fun probeFile(id: String): String = "diag/probes/$id.js"
}

/** Byte budgets (design §6) and the always-on costs (design §7, §10). */
internal object DiagBudget {
    const val LOG_RING_BYTES = 4L * 1024 * 1024
    const val TRACE_RING_BYTES = 1L * 1024 * 1024
    const val TRACE_RING_RECORD_BYTES = 2L * 1024 * 1024
    const val TRACE_RINGS_MAPPED_MAX = 4
    const val SAMPLES_BYTES = 256L * 1024
    const val LOG_INDEX_BYTES = 480L * 1024
    const val HEALTH_BYTES = 64L * 1024
    const val HEALTH_EVENTS_MAX = 500
    const val HEALTH_EXCERPT_BYTES = 4 * 1024
    const val HEALTH_DAYS = 7
    const val ANR_TRACE_MAX_BYTES = 256 * 1024
    const val RECORD_MAX_MS = 15 * 60_000L
    const val VERBOSE_AUTO_OFF_MS = 24 * 3_600_000L
    const val NET_BODY_HEAD_BYTES = 8 * 1024
    const val PROBE_BODY_MAX_BYTES = 64 * 1024
    const val PROBE_QUEUE = 256
    const val PROBE_SYNC_DEADLINE_MS = 200L
    const val PROBE_MEMORY_LIMIT_BYTES = 16L shl 20
    const val PROBE_CONSOLE_LINES = 500
    const val MAIN_WATCHDOG_MS = 3_000L
    const val IO_WATCHDOG_MS = 5_000L
    const val IO_STARVED_PARKED = 56
    const val IO_POOL_DEFAULT = 64
    const val FINE_RING_MS = 300_000L
    const val FLOAT_TAIL_LINES = 50
    const val FLOAT_REFRESH_MIN_MS = 100L
    const val REPORTS_KEPT = 3
    const val CLIPBOARD_LIMIT = 300_000
}

/** Intent actions and notification ids (E posts, F handles the open action in MainActivity). */
internal object DiagActions {
    const val OPEN = "app.komikku.diagnostics.OPEN" // extra TAB = DiagTab.name
    const val EXTRA_TAB = "tab"
    const val EXTRA_PROBE_ID = "probe"
    const val STOP_RECORDING = "app.komikku.diagnostics.STOP"
    const val MARK = "app.komikku.diagnostics.MARK"
    const val THREAD_DUMP = "app.komikku.diagnostics.DUMP"
    const val FLOAT = "app.komikku.diagnostics.FLOAT"
    const val CHANNEL = "diagnostics_channel"
    const val ID_RECORDING = -2001
    const val ID_RESULT = -2002
    const val ID_STALL = -2003
}
