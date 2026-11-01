package eu.kanade.tachiyomi.diagnostics

import android.content.Context
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.util.system.AppLog
import java.io.File
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong

/**
 * KMK (r97): The trace rings (design §3.4, §6): one [DiagRing] per [TraceKind], mapped on the first switch-on,
 * header-cleared when switched off, 1 MB each (`net`/`js` 2 MB while a recording runs). Records are JSON lines
 * written from the IO worker so the measuring thread never touches the map. The emitters below each start with the
 * volatile switch check and also feed the probes (one `if` guards both).
 */
internal object TraceWriter {

    private val rings = EnumMap<TraceKind, DiagRing>(TraceKind::class.java)
    private val lock = Any()
    private var dir: File? = null
    private var wanted: Set<TraceKind> = emptySet()
    private val written = EnumMap<TraceKind, AtomicLong>(TraceKind::class.java).apply {
        TraceKind.entries.forEach { put(it, AtomicLong()) }
    }

    fun init(context: Context) {
        dir = context.cacheDir
        DiagIo.post { openWanted() }
    }

    /** The switches changed (from Traces.apply): rings open on the IO worker; a kind switched off is emptied */
    fun onSwitches(on: Set<TraceKind>) {
        synchronized(lock) {
            val off = wanted - on
            wanted = on
            off.forEach { kind ->
                rings[kind]?.clear()
                written[kind]?.set(0L)
            }
        }
        if (dir != null) DiagIo.post { openWanted() }
    }

    /** Recording raises the `net` and `js` rings to 2 MB */
    fun setRecording(on: Boolean) {
        DiagIo.post {
            for (kind in listOf(TraceKind.NET, TraceKind.JS)) {
                ring(kind)?.setCapacity(if (on) DiagBudget.TRACE_RING_RECORD_BYTES else DiagBudget.TRACE_RING_BYTES)
            }
        }
    }

    /** IO worker only */
    private fun openWanted() {
        val kinds = synchronized(lock) { wanted.toList() }
        kinds.forEach { ring(it) }
    }

    /** Opens (once) and returns the ring of [kind]; IO worker only */
    private fun ring(kind: TraceKind): DiagRing? {
        synchronized(lock) { rings[kind] }?.let { return it }
        val d = dir ?: return null
        val mapped = if (kind == TraceKind.NET || kind == TraceKind.JS) {
            DiagBudget.TRACE_RING_RECORD_BYTES
        } else {
            DiagBudget.TRACE_RING_BYTES
        }
        val capacity = if (Traces.recording && mapped > DiagBudget.TRACE_RING_BYTES) mapped else DiagBudget.TRACE_RING_BYTES
        val ring = DiagRing.open(File(d, DiagPaths.traceRing(kind)), mapped, capacity, AppLog.sessionStart) ?: return null
        // A ring from an earlier run holds old records: a trace starts empty, unless a recording was cut by a process
        // death and its report is about to be built from what the rings kept
        if (!LogCapture.interruptedRecording) ring.clear()
        synchronized(lock) { rings[kind] = ring }
        return ring
    }

    /** Queues one JSON line for [kind] (the caller checked the switch) */
    fun emit(kind: TraceKind, json: String) {
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        written[kind]?.addAndGet(bytes.size.toLong())
        DiagIo.post { ring(kind)?.append(bytes) }
    }

    /** Bytes in the ring of [kind] right now (0 when off) */
    fun bytes(kind: TraceKind): Long = synchronized(lock) { rings[kind]?.used?.toLong() ?: 0L }

    fun totalBytes(): Long = TraceKind.entries.sumOf { bytes(it) }

    fun bytesMap(): Map<TraceKind, Long> = TraceKind.entries.associateWith { bytes(it) }

    /** The ring's lines, oldest first; empty when the kind was never on */
    fun lines(kind: TraceKind): List<String> = synchronized(lock) { rings[kind] }?.lines() ?: emptyList()

    /** The newest [n] lines of [kind], oldest first */
    fun tail(kind: TraceKind, n: Int): List<String> = synchronized(lock) { rings[kind] }?.tail(n) ?: emptyList()

    /** IO worker: pushes the rings' pages to their files */
    fun forceAll() {
        synchronized(lock) { rings.values.toList() }.forEach { it.force() }
    }

    fun mappedBytes(): Long = synchronized(lock) { rings.values.sumOf { (it.mapped + DiagRing.HEADER).toLong() } }

    /** The same mark record in every open trace, so the moment is findable in each */
    fun markAll(note: String) {
        val t = System.currentTimeMillis()
        val kinds = synchronized(lock) { wanted.toList() }
        for (kind in kinds) emit(
            kind,
            jsJson.encodeToString(DumpTraceEvent.serializer(), DumpTraceEvent(t, "mark", note)),
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Emitters: `if (!Traces.x && no probe) return` first, then build, encode, emit, V summary line, probe dispatch
// ---------------------------------------------------------------------------------------------------------------------

/** Network: emitted by [NetStats] once a response's body is consumed */
internal object NetTrace {
    fun event(e: NetTraceEvent) {
        if (!Traces.net) return
        TraceWriter.emit(TraceKind.NET, jsJson.encodeToString(NetTraceEvent.serializer(), e))
    }
}

/** mpv events and the 1 s poll (PlayerActivity.event, PlayerViewModel) */
internal object PlayerTrace {
    fun event(e: PlayerTraceEvent) {
        val probe = ProbeRuntime.mask and ProbeHook.PLAYER_EVENT.mask != 0
        if (!Traces.player && !probe) return
        val json = jsJson.encodeToString(PlayerTraceEvent.serializer(), e)
        if (Traces.player) {
            TraceWriter.emit(TraceKind.PLAYER, json)
            if (e.event != "POLL") {
                AppLog.v(DiagCategory.PLAYER, "player.ev") {
                    "${e.event} at ${"%.1f".format(e.posS)} s · cache ${"%.1f".format(e.demuxerCacheS)} s" +
                        (e.detail?.let { " · $it" } ?: "") + " · duration_ms=${e.ms} count=${e.dropped}"
                }
            }
        }
        if (probe) ProbeRuntime.dispatch(ProbeHook.PLAYER_EVENT, json)
    }

    /** The player closed: the Live page's Player resource empties */
    fun closed() {
        LiveSampler.player.value = null
    }

    /** The 1 s poll's figures (PlayerViewModel, only while the trace is on or the Live page shows the player) */
    fun poll(live: PlayerLive) {
        LiveSampler.player.value = live
        if (!Traces.player && ProbeRuntime.mask and ProbeHook.PLAYER_EVENT.mask == 0) return
        event(
            PlayerTraceEvent(
                t = System.currentTimeMillis(),
                event = "POLL",
                posS = live.posS.toFloat(),
                demuxerCacheS = live.demuxerCacheS,
                pausedForCache = live.pausedForCache,
                dropped = live.droppedFrames,
                vcodec = live.vcodec,
                acodec = live.acodec,
                hwdec = live.hwdec,
                width = live.width,
                height = live.height,
            ),
        )
    }
}

/** HLS relay pieces and playlists (HlsRelay.serve) */
internal object HlsTrace {
    val served = AtomicLong()
    val bytes = AtomicLong()
    val errors = AtomicLong()

    /** Anything beyond the counters to do for a piece? One volatile read each (HlsRelay parses the host only then) */
    fun wanted(): Boolean = Traces.player || ProbeRuntime.mask and ProbeHook.HLS_SEGMENT.mask != 0

    fun piece(
        kind: String,
        host: String,
        ms: Long,
        bytes: Long,
        status: Int,
        retries: Int = 0,
        rewritten: Boolean = false,
        fakeImageStripped: Boolean = false,
        error: String? = null,
    ) {
        served.incrementAndGet()
        this.bytes.addAndGet(bytes.coerceAtLeast(0L))
        if (error != null || status >= 400) errors.incrementAndGet()
        val probe = ProbeRuntime.mask and ProbeHook.HLS_SEGMENT.mask != 0
        if (!Traces.player && !probe) return
        val e = HlsTraceEvent(
            System.currentTimeMillis(),
            kind,
            host,
            ms,
            bytes,
            status,
            retries,
            rewritten,
            fakeImageStripped,
            error,
        )
        val json = jsJson.encodeToString(HlsTraceEvent.serializer(), e)
        if (Traces.player) {
            TraceWriter.emit(TraceKind.PLAYER, json)
            AppLog.vFields(DiagCategory.PLAYER, "player.hls") {
                "$kind $host -> $status in $ms ms" to arrayOf<Pair<String, Any?>>(
                    "status" to status, "duration_ms" to ms, "bytes" to bytes,
                )
            }
        }
        if (probe) ProbeRuntime.dispatch(ProbeHook.HLS_SEGMENT, json)
    }
}

/** Hoster list, video list, the quality choice, the relay wrap (PlayerViewModel.loadHosters) */
internal object HosterTrace {
    private val lastSteps = ArrayDeque<String>()

    fun step(step: String, source: String?, hoster: String?, ms: Long, count: Int = 0, error: String? = null) {
        val probe = ProbeRuntime.mask and ProbeHook.HOSTER_STEP.mask != 0
        // The "last steps" list is for the Live Player page: built only while something shows or records it
        if (Traces.player || LiveSampler.playerVisibleFlow.value) {
            synchronized(lastSteps) {
                lastSteps.addLast("$step${hoster?.let { " $it" } ?: ""} · $ms ms${if (count > 0) " · $count" else ""}${error?.let { " · $it" } ?: ""}")
                while (lastSteps.size > 8) lastSteps.removeFirst()
            }
        }
        if (!Traces.player && !probe) return
        val e = HosterTraceEvent(System.currentTimeMillis(), step, source, hoster, ms, count, error)
        val json = jsJson.encodeToString(HosterTraceEvent.serializer(), e)
        if (Traces.player) {
            TraceWriter.emit(TraceKind.PLAYER, json)
            AppLog.vFields(DiagCategory.PLAYER, "player.hoster") {
                "$step${hoster?.let { " $it" } ?: ""}${if (count > 0) " → $count" else ""} in $ms ms${error?.let { ": $it" } ?: ""}" to
                    arrayOf<Pair<String, Any?>>("source" to source, "duration_ms" to ms, "count" to count)
            }
        }
        if (probe) ProbeRuntime.dispatch(ProbeHook.HOSTER_STEP, json)
    }

    fun lastSteps(): List<String> = synchronized(lastSteps) { lastSteps.toList() }
}

/** JS source calls (JsRuntime.call) */
internal object JsTrace {
    /** Anything to do at all for a call? One volatile read each */
    fun wanted(): Boolean = Traces.js || ProbeRuntime.mask and (ProbeHook.JS_CALL.mask or ProbeHook.JS_RESULT.mask) != 0

    fun before(source: String, fn: String, args: String) {
        if (ProbeRuntime.mask and ProbeHook.JS_CALL.mask == 0) return
        val e = JsTraceEvent(System.currentTimeMillis(), source, fn, args, 0L, 0, 0, 0L, 0L, 0L)
        ProbeRuntime.dispatch(ProbeHook.JS_CALL, jsJson.encodeToString(JsTraceEvent.serializer(), e))
    }

    fun after(e: JsTraceEvent) {
        val probe = ProbeRuntime.mask and ProbeHook.JS_RESULT.mask != 0
        if (!Traces.js && !probe) return
        val json = jsJson.encodeToString(JsTraceEvent.serializer(), e)
        if (Traces.js) {
            TraceWriter.emit(TraceKind.JS, json)
            AppLog.vFields(DiagCategory.JS, "js.call") {
                val items = if (e.items >= 0) " → ${e.items} items" else ""
                val mem = if (e.memAfter > 0) " · mem ${e.memAfter / (1024 * 1024)} MB" else ""
                "${e.source} ${e.fn}(${e.args.take(60)})$items in ${e.ms} ms (parse ${e.parseMs} ms)$mem" +
                    (e.error?.let { ": $it" } ?: "") to arrayOf<Pair<String, Any?>>(
                    "source" to e.source, "fn" to e.fn, "duration_ms" to e.ms, "count" to e.items.takeIf { it >= 0 },
                    "mem_mb" to (e.memAfter / (1024 * 1024)).takeIf { e.memAfter > 0 },
                )
            }
        }
        if (probe) ProbeRuntime.dispatch(ProbeHook.JS_RESULT, json)
    }
}

/** Pair sync frames and phases (PairSync) */
internal object SyncTrace {
    /** Anything to do at all? One volatile read each */
    fun wanted(): Boolean = Traces.sync || ProbeRuntime.mask and ProbeHook.SYNC_PHASE.mask != 0

    fun frame(phase: String, direction: String, frameType: String?, bytes: Long, ms: Long, peer: String?) {
        val probe = ProbeRuntime.mask and ProbeHook.SYNC_PHASE.mask != 0
        if (!Traces.sync && !probe) return
        val e = SyncTraceEvent(System.currentTimeMillis(), phase, direction, frameType, bytes, ms, peer)
        val json = jsJson.encodeToString(SyncTraceEvent.serializer(), e)
        if (Traces.sync) {
            TraceWriter.emit(TraceKind.SYNC, json)
            AppLog.vFields(DiagCategory.SYNC, "sync.frame") {
                "$direction ${frameType ?: "frame"} ${"%,d".format(bytes)} B in $ms ms" to arrayOf<Pair<String, Any?>>(
                    "bytes" to bytes, "duration_ms" to ms, "peer" to peer,
                )
            }
        }
        if (probe) ProbeRuntime.dispatch(ProbeHook.SYNC_PHASE, json)
    }

    fun phase(phase: String, sincePreviousMs: Long, peer: String?) {
        val probe = ProbeRuntime.mask and ProbeHook.SYNC_PHASE.mask != 0
        if (!Traces.sync && !probe) return
        val e = SyncTraceEvent(System.currentTimeMillis(), phase, "phase", null, 0L, sincePreviousMs, peer)
        val json = jsJson.encodeToString(SyncTraceEvent.serializer(), e)
        if (Traces.sync) TraceWriter.emit(TraceKind.SYNC, json)
        if (probe) ProbeRuntime.dispatch(ProbeHook.SYNC_PHASE, json)
    }
}

/** Reader page loads and decodes (the page loaders, ReaderPageImageView) */
internal object ReaderTrace {
    /** The last fetch, so the decode figures (which come from a view that doesn't know the page) attach to it */
    @Volatile
    private var lastFetch: ReaderTraceEvent? = null

    fun wanted(): Boolean = Traces.reader || ProbeRuntime.mask and ProbeHook.READER_PAGE.mask != 0

    fun page(
        chapter: String,
        page: Int,
        loader: String,
        fetchMs: Long,
        cacheHit: Boolean,
        bytes: Long,
        error: String? = null,
    ) {
        if (!wanted()) return
        val e = ReaderTraceEvent(
            System.currentTimeMillis(),
            chapter,
            page,
            loader,
            fetchMs,
            cacheHit,
            bytes,
            0L,
            0,
            0,
            false,
            error,
        )
        lastFetch = e
        emit(e)
    }

    fun decoded(ms: Long, width: Int, height: Int, animated: Boolean) {
        if (!wanted()) return
        val last = lastFetch
        val e = if (last != null) {
            last.copy(
                t = System.currentTimeMillis(),
                decodeMs = ms,
                width = width,
                height = height,
                animated = animated,
            )
        } else {
            ReaderTraceEvent(System.currentTimeMillis(), "", -1, "decode", 0L, false, 0L, ms, width, height, animated)
        }
        emit(e)
    }

    private fun emit(e: ReaderTraceEvent) {
        val probe = ProbeRuntime.mask and ProbeHook.READER_PAGE.mask != 0
        val json = jsJson.encodeToString(ReaderTraceEvent.serializer(), e)
        if (Traces.reader) {
            TraceWriter.emit(TraceKind.READER, json)
            AppLog.vFields(DiagCategory.READER, "reader.page") {
                val what = if (e.decodeMs > 0) "decoded ${e.width}×${e.height}${if (e.animated) " animated" else ""} in ${e.decodeMs} ms" else
                    "${e.loader} fetch in ${e.fetchMs} ms${if (e.cacheHit) " (cache)" else ""}${e.error?.let { ": $it" } ?: ""}"
                "page ${e.page + 1} $what" to arrayOf<Pair<String, Any?>>(
                    "chapter" to e.chapter.takeIf { it.isNotEmpty() }, "page" to e.page, "duration_ms" to (e.fetchMs + e.decodeMs),
                    "bytes" to e.bytes.takeIf { it > 0 },
                )
            }
        }
        if (probe) ProbeRuntime.dispatch(ProbeHook.READER_PAGE, json)
    }
}

/** Watchdog dumps and marks, into the EVENTS ring (allocated on the first stall when the trace is on) */
internal object DumpTrace {
    fun event(kind: String, title: String, dump: ThreadDump?) {
        if (!Traces.events) return
        TraceWriter.emit(
            TraceKind.EVENTS,
            jsJson.encodeToString(
                DumpTraceEvent.serializer(),
                DumpTraceEvent(System.currentTimeMillis(), kind, title, dump),
            ),
        )
    }
}
