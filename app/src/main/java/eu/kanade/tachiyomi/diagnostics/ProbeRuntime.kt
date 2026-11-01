package eu.kanade.tachiyomi.diagnostics

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import eu.kanade.tachiyomi.jssource.JsRuntime
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.jsQuoted
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * KMK (r97): The probe runtime (contract §3.7): one small QuickJS context of its own (not a source runtime), created
 * when the first probe is enabled and closed 60 s after the last one is disabled, on a single thread `diag-probe`.
 * Hook sites check [mask] first (zero cost with no probe on), then hand the event JSON to [dispatch]: the async path
 * queues it (256, drop-oldest); the sync path (Experimental probes on `net.response`) waits up to 200 ms so
 * `r.replaceBody` can rebuild the response. Budgets, the CPU cap, the 1 s watchdog and auto-off switch a probe off
 * with an `offReason`.
 */
internal object ProbeRuntime {

    /** OR of the enabled probes' hook masks; every hook site reads it first */
    @Volatile
    var mask: Int = 0
        private set

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var store: ProbeStore

    /** One thread, created on first use and gone 60 s after its last task (no probe = no thread) */
    private val executor = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 60L, TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(),
    ) { r -> Thread(r, "diag-probe").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var qjs: QuickJs? = null

    /** Ids whose script is loaded in the runtime (read from the interceptor thread too) */
    private val loaded: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var closeJob: Job? = null

    @Volatile
    private var queueJob: Job? = null

    /** The probe whose handler is running on the probe thread (the bindings check it, e.g. Experimental) */
    @Volatile
    private var currentProbeId: String? = null

    private class Stat {
        val events = AtomicLong()
        val dropped = AtomicLong()
        var errors = 0
        var lastError: String? = null
        var lastRunAt: Long? = null
        val recent = FloatArray(50)
        var recentN = 0
        var recentAt = 0
        var maxMs = 0f
        var overBudget = 0
        var minuteMs = 0f
        var minuteAt = 0L
        var offReason: String? = null
        var wantsBody = false
        var experimental = false

        /**
         * Source filters of the probe's `net.response` handlers, from `__probeLoad`'s result: "" = a handler with
         * no source filter (matches every source). Empty set = no `net.response` handler.
         */
        @Volatile var netResponseSources: Set<String> = emptySet()

        fun avg(): Float = if (recentN == 0) 0f else recent.take(recentN).sum() / recentN
    }

    private val stats = ConcurrentHashMap<String, Stat>()

    private val _snapshot = MutableStateFlow(ProbesSnapshot())
    val snapshot: StateFlow<ProbesSnapshot> = _snapshot.asStateFlow()

    private val consoleLines = ArrayDeque<ConsoleLine>(DiagBudget.PROBE_CONSOLE_LINES + 1)
    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console.asStateFlow()

    /** The console snapshot is published at most every 100 ms (a chatty probe doesn't copy 500 lines per line) */
    @Volatile
    private var consolePublishJob: Job? = null

    @Volatile
    private var consoleLastPublishedAt = 0L

    /** Body heads and headers handed to a running dispatch; freed after it */
    private class Handle(val headers: Map<String, String>?, val bodyHead: String?) {
        @Volatile var replacement: String? = null
    }

    private val handles = ConcurrentHashMap<Int, Handle>()
    private val handleRefs = ConcurrentHashMap<Int, AtomicInteger>()
    private val nextHandle = AtomicInteger(1)

    private fun newHandle(h: Handle): Int = nextHandle.getAndIncrement().also { handles[it] = h }

    /** One queued event more refers to [handle] */
    private fun retain(handle: Int) {
        if (handle >= 0) handleRefs.getOrPut(handle) { AtomicInteger(0) }.incrementAndGet()
    }

    /** One event done with [handle]; the body head is freed with the last one */
    private fun release(handle: Int) {
        if (handle < 0) return
        val left = handleRefs[handle]?.decrementAndGet() ?: 0
        if (left <= 0) {
            handleRefs.remove(handle)
            handles.remove(handle)
        }
    }

    private fun send(event: Event) {
        retain(event.handle)
        queue.trySend(event)
    }

    private class Event(
        val hook: ProbeHook,
        val json: String,
        val handle: Int,
        val done: CountDownLatch?,
        val onlyProbe: String? = null,
    )

    private val queue = Channel<Event>(DiagBudget.PROBE_QUEUE, BufferOverflow.DROP_OLDEST) { dropped ->
        // Counted on every probe of that hook, one W line per minute
        for (p in enabledFor(dropped.hook)) stat(p.id).dropped.incrementAndGet()
        dropped.done?.countDown()
        release(dropped.handle)
        noteDropped()
    }

    fun init(context: Context, scope: CoroutineScope, store: ProbeStore) {
        this.context = context.applicationContext
        this.scope = scope
        this.store = store
        // probes.json and the scripts are read on the IO worker, not on the main thread at start; the mask and the
        // runtime follow once they are known (nothing is dispatched before: the mask is 0 until then)
        DiagIo.post {
            store.load()
            for (p in store.probes.value) stat(p.id).apply { wantsBody = p.hooks.isNotEmpty() && store.code(p.id).contains(".text("); experimental = p.experimental }
            refresh()
        }
    }

    private fun stat(id: String): Stat = stats.getOrPut(id) { Stat() }

    private fun enabledFor(hook: ProbeHook): List<ProbeDescriptor> =
        store.probes.value.filter { it.enabled && hook.id in it.hooks }

    /** Recomputes the mask, loads/unloads scripts, starts or schedules the close of the runtime */
    fun refresh() {
        val enabled = store.probes.value.filter { it.enabled }
        var m = 0
        for (p in enabled) for (h in p.hookSet) m = m or h.mask
        // Bits that went away are cleared at once; new bits are set once the scripts are loaded (an event for a
        // probe that isn't loaded yet would only be queued and dropped)
        mask = mask and m
        if (enabled.isEmpty() && qjs == null) {
            publish()
            return
        }
        scope.launch(dispatcher) {
            if (enabled.isNotEmpty()) {
                closeJob?.cancel()
                closeJob = null
                val engine = ensureRuntime() ?: return@launch
                val ids = enabled.map { it.id }.toSet()
                for (id in loaded.toList()) {
                    if (id !in ids) {
                        runCatching { engine.evaluate<Any?>("__probeUnload(${id.jsQuoted()})", "<unload>") }
                        loaded.remove(id)
                        stat(id).netResponseSources = emptySet()
                    }
                }
                for (p in enabled) if (p.id !in loaded) load(engine, p)
                var loadedMask = 0
                for (p in store.probes.value) if (p.enabled && p.id in loaded) for (h in p.hookSet) loadedMask = loadedMask or h.mask
                mask = loadedMask
            } else if (qjs != null && closeJob == null) {
                closeJob = scope.launch(dispatcher) {
                    delay(60_000L)
                    if (store.probes.value.none { it.enabled }) closeRuntime()
                }
            }
            publish()
        }
    }

    /** Probe thread only */
    private suspend fun ensureRuntime(): QuickJs? {
        qjs?.let { return it }
        return runCatching {
            JsRuntime.loadNativeLibrary()
            val engine = QuickJs.create(jobDispatcher = dispatcher)
            engine.memoryLimit = DiagBudget.PROBE_MEMORY_LIMIT_BYTES
            engine.maxStackSize = 1L shl 20
            engine.defineBinding(
                "__probeEmit",
                FunctionBinding<Any?> { args ->
                    emit(
                        args.getOrNull(0)?.toString().orEmpty(),
                        args.getOrNull(1)?.toString()?.firstOrNull() ?: 'I',
                        args.getOrNull(2)?.toString().orEmpty(),
                    )
                    null
                },
            )
            engine.defineBinding(
                "__probeBody",
                FunctionBinding<String> { args ->
                    val h = handles[(args.getOrNull(0) as? Number)?.toInt() ?: -1]
                    val max = ((args.getOrNull(1) as? Number)?.toInt() ?: DiagBudget.PROBE_BODY_MAX_BYTES).coerceIn(
                        0,
                        DiagBudget.PROBE_BODY_MAX_BYTES,
                    )
                    h?.bodyHead?.take(max).orEmpty()
                },
            )
            engine.defineBinding(
                "__probeHeader",
                FunctionBinding<String?> { args ->
                    val h = handles[(args.getOrNull(0) as? Number)?.toInt() ?: -1]
                    val name = args.getOrNull(1)?.toString().orEmpty().lowercase()
                    if (name in secretHeaders || name.startsWith("x-auth") || name.contains("token")) null else h?.headers?.entries?.firstOrNull { it.key.lowercase() == name }?.value
                },
            )
            engine.defineBinding(
                "__probeReplace",
                FunctionBinding<Boolean> { args ->
                    // Experimental is enforced here, from the descriptor, not from what the script says about itself
                    val current = currentProbeId ?: return@FunctionBinding false
                    if (store.get(current)?.experimental != true) return@FunctionBinding false
                    val h = handles[(args.getOrNull(0) as? Number)?.toInt() ?: -1] ?: return@FunctionBinding false
                    h.replacement = args.getOrNull(1)?.toString().orEmpty()
                    true
                },
            )
            val prelude = context.assets.open(DiagPaths.PROBE_PRELUDE_ASSET).bufferedReader().use { it.readText() }
            engine.evaluate<Any?>(prelude, "probe-prelude.js")
            qjs = engine
            if (queueJob?.isActive != true) queueJob = scope.launch(dispatcher) { drain() }
            AppLog.add('I', "diag", "Probe runtime started")
            engine
        }.onFailure { AppLog.add('W', "diag", "Probe runtime couldn't start: ${it.message}") }.getOrNull()
    }

    private val secretHeaders = setOf("cookie", "set-cookie", "authorization", "proxy-authorization")

    private suspend fun load(engine: QuickJs, p: ProbeDescriptor) {
        val code = store.code(p.id)
        val s = stat(p.id)
        s.wantsBody = code.contains(".text(")
        s.experimental = p.experimental
        val result = runCatching {
            guarded(p.id) {
                engine.evaluate<String?>(
                    "__probeLoad(${p.id.jsQuoted()}, ${p.name.jsQuoted()}, ${p.experimental}, ${code.jsQuoted()})",
                    "<load ${p.name}>",
                )
            }
        }
        result.onSuccess { json ->
            s.netResponseSources = netResponseSourcesOf(json)
            loaded.add(p.id)
            s.offReason = null
        }.onFailure { e ->
            val msg = e.message?.substringBefore("\n    at ")?.ifBlank { null } ?: e.toString()
            s.lastError = msg
            s.errors++
            emit(p.id, 'E', "load failed: $msg")
            switchOff(p.id, "failed to load: $msg")
        }
    }

    /** `__probeLoad` returns `[{"hook":…,"source":…}]`: the source filters of the net.response handlers */
    private fun netResponseSourcesOf(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return runCatching {
            val out = HashSet<String>()
            val arr = jsJson.parseToJsonElement(json) as? JsonArray ?: return emptySet()
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val hook = (o["hook"] as? JsonPrimitive)?.content ?: continue
                if (hook != ProbeHook.NET_RESPONSE.id) continue
                val source = (o["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                out.add(source?.trim().orEmpty())
            }
            out
        }.getOrDefault(emptySet())
    }

    /** Whether a `net.response` handler of the probe applies to [source] (null source: only an unfiltered handler) */
    private fun netResponseMatches(s: Stat, source: String?): Boolean {
        val filters = s.netResponseSources
        if (filters.isEmpty()) return false
        if ("" in filters) return true
        if (source == null) return false
        return filters.any { it.equals(source, ignoreCase = true) }
    }

    private suspend fun closeRuntime() {
        queueJob?.cancel()
        queueJob = null
        runCatching { qjs?.close() }
        qjs = null
        loaded.clear()
        AppLog.add('I', "diag", "Probe runtime closed (no probe enabled for 60 s)")
    }

    // --- dispatch --------------------------------------------------------------------------------------------------

    /** The async path for every hook but the network ones */
    fun dispatch(hook: ProbeHook, json: String) {
        if (mask and hook.mask == 0) return
        send(Event(hook, json, -1, null))
    }

    /** `net.request`, and the async `net.response`: headers and (when a probe wants it) the body head travel along */
    fun dispatchNet(hook: ProbeHook, json: String, headers: Headers?, bodyHead: String?, source: String?) {
        if (mask and hook.mask == 0) return
        val handle = if (headers != null || bodyHead != null) {
            newHandle(Handle(headers?.let { h -> NetStats.filteredHeaders(h) }, bodyHead))
        } else {
            -1
        }
        send(Event(hook, json, handle, null))
    }

    /**
     * `net.response` from the interceptor thread: Experimental probes run synchronously with a 200 ms deadline so
     * `r.replaceBody` can rebuild the response; the others get the event queued. The body is peeked only when a
     * matching enabled probe calls `r.text(`.
     */
    fun dispatchNetResponse(json: String, response: Response, source: String?): Response {
        if (mask and ProbeHook.NET_RESPONSE.mask == 0) return response
        // Loaded probes whose net.response handlers apply to this source (the filters come from the script itself)
        val probes = enabledFor(ProbeHook.NET_RESPONSE).filter { p -> p.id in loaded && netResponseMatches(stat(p.id), source) }
        if (probes.isEmpty()) return response
        val wantsBody = probes.any { stat(it.id).wantsBody }
        val head = if (wantsBody && NetStats.isText(response.header("Content-Type"))) {
            runCatching { response.peekBody(DiagBudget.PROBE_BODY_MAX_BYTES.toLong()).string() }.getOrNull()
        } else {
            null
        }
        val h = Handle(NetStats.filteredHeaders(response.headers), head)
        val handle = newHandle(h)
        // The sync path needs a runtime that is draining; otherwise nothing could ever count the latch down
        val sync = if (qjs != null && queueJob?.isActive == true) probes.filter { it.experimental } else emptyList()
        if (sync.isEmpty()) {
            send(Event(ProbeHook.NET_RESPONSE, json, handle, null))
            return response
        }
        // Async probes first (queued), then the Experimental ones, one latch each, all within the one deadline
        val asyncOnly = probes.filter { !it.experimental }
        for (p in asyncOnly) send(Event(ProbeHook.NET_RESPONSE, json, handle, null, onlyProbe = p.id))
        val latches = sync.map { p -> CountDownLatch(1).also { send(Event(ProbeHook.NET_RESPONSE, json, handle, it, onlyProbe = p.id)) } }
        val deadline = System.nanoTime() + DiagBudget.PROBE_SYNC_DEADLINE_MS * 1_000_000L
        var inTime = true
        for (latch in latches) {
            val left = deadline - System.nanoTime()
            if (left <= 0L || !runCatching { latch.await(left, TimeUnit.NANOSECONDS) }.getOrDefault(false)) {
                inTime = false
                break
            }
        }
        if (!inTime) {
            for (p in sync) emit(
                p.id,
                'W',
                "response handed on unchanged: the probe took more than ${DiagBudget.PROBE_SYNC_DEADLINE_MS} ms",
            )
            return response
        }
        val replacement = h.replacement ?: return response
        return NetStats.replaceBody(response, replacement)
    }

    /** Probe thread: one event at a time, per probe, with the budget clock */
    private suspend fun drain() {
        for (event in queue) {
            try {
                val engine = qjs ?: continue
                val probes = enabledFor(event.hook).filter { event.onlyProbe == null || it.id == event.onlyProbe }
                for (p in probes) {
                    if (p.id !in loaded) continue
                    runOne(engine, p, event.hook, event.json, event.handle)
                }
            } finally {
                // The interceptor waiting on a sync event is released on every path (no runtime, no probe, an error)
                event.done?.let { while (it.count > 0) it.countDown() }
                // A handle is shared by the events of one response: freed when no queued event refers to it
                release(event.handle)
            }
        }
    }

    private suspend fun runOne(engine: QuickJs, p: ProbeDescriptor, hook: ProbeHook, json: String, handle: Int) {
        val s = stat(p.id)
        val started = System.nanoTime()
        currentProbeId = p.id
        val error = try {
            runCatching {
                guarded(p.id) {
                    engine.evaluate<String?>(
                        "__dispatch(${p.id.jsQuoted()}, ${hook.id.jsQuoted()}, ${json.jsQuoted()}, $handle)",
                        "<dispatch>",
                    )
                }
            }.fold({ it.orEmpty() }, { it.message ?: it.toString() })
        } finally {
            currentProbeId = null
        }
        val ms = (System.nanoTime() - started) / 1_000_000f
        s.events.incrementAndGet()
        s.lastRunAt = System.currentTimeMillis()
        s.recent[s.recentAt] = ms
        s.recentAt = (s.recentAt + 1) % s.recent.size
        if (s.recentN < s.recent.size) s.recentN++
        if (ms > s.maxMs) s.maxMs = ms
        val now = System.currentTimeMillis()
        if (now - s.minuteAt > 60_000L) {
            s.minuteAt = now
            s.minuteMs = 0f
        }
        s.minuteMs += ms
        if (error.isNotEmpty()) {
            s.errors++
            s.lastError = error
            emit(p.id, 'E', error)
            if (s.errors >= 50 && s.errors > s.events.get() / 2) switchOff(p.id, "errors on most events")
        }
        s.overBudget = if (ms > p.budget.msPerEvent) s.overBudget + 1 else 0
        if (s.overBudget >= 50) switchOff(p.id, "over its ${p.budget.label} budget for 50 events in a row")
        else if (s.minuteMs > 3_000f) switchOff(p.id, "used more than 5 % of a core for a minute")
        if (s.events.get() % 20 == 0L) publish()
    }

    /** Runs [block] with the 1 s watchdog: an evaluation past it is interrupted and the probe switched off */
    private suspend fun <T> guarded(id: String, block: suspend () -> T): T {
        val engine = qjs
        val fired = AtomicBoolean(false)
        val watcher = scope.launch {
            delay(1_000L)
            fired.set(true)
            runCatching { engine?.interruptEvaluation() }
        }
        try {
            return block()
        } catch (e: Throwable) {
            if (fired.get()) {
                switchOff(id, "ran 1 s without returning")
                throw IllegalStateException("ran 1 s without returning", e)
            }
            throw e
        } finally {
            watcher.cancel()
        }
    }

    private fun switchOff(id: String, reason: String) {
        val p = store.get(id) ?: return
        if (!p.enabled) return
        stat(id).offReason = reason
        store.update(id) { it.copy(enabled = false) }
        AppLog.add('W', "app", "Probe ${p.name} switched off: $reason")
        emit(id, 'W', "switched off: $reason")
        refresh()
    }

    @Volatile
    private var lastDroppedLineAt = 0L

    private fun noteDropped() {
        val now = System.currentTimeMillis()
        if (now - lastDroppedLineAt < 60_000L) return
        lastDroppedLineAt = now
        for (p in store.probes.value.filter { it.enabled }) {
            val d = stat(p.id).dropped.get()
            if (d > 0) AppLog.add('W', "probe:${p.name}", "dropped $d events (busy)")
        }
    }

    // --- console ---------------------------------------------------------------------------------------------------

    private fun emit(id: String, level: Char, text: String) {
        val p = store.get(id)
        val name = p?.name ?: id
        val line = ConsoleLine(System.currentTimeMillis(), level, id, name, text)
        synchronized(consoleLines) {
            consoleLines.addLast(line)
            while (consoleLines.size > DiagBudget.PROBE_CONSOLE_LINES) consoleLines.removeFirst()
        }
        publishConsole()
        AppLog.add(level, "probe:$name", text)
        if (p?.markerOnGraph == true && level != 'D') Health.marker(Marker(line.time, MarkerKind.PROBE, name, "probe:$name"))
    }

    fun clearConsole(probeId: String?) {
        synchronized(consoleLines) {
            if (probeId == null) consoleLines.clear() else consoleLines.removeAll { it.probeId == probeId }
        }
        publishConsole(now = true)
    }

    /** Publishes the console list at once when 100 ms have passed since the last snapshot, else once, 100 ms later */
    private fun publishConsole(now: Boolean = false) {
        val t = System.currentTimeMillis()
        if (now || t - consoleLastPublishedAt >= 100L) {
            consoleLastPublishedAt = t
            _console.value = synchronized(consoleLines) { consoleLines.toList() }
            return
        }
        if (consolePublishJob?.isActive == true) return
        consolePublishJob = scope.launch {
            delay(100L)
            consoleLastPublishedAt = System.currentTimeMillis()
            _console.value = synchronized(consoleLines) { consoleLines.toList() }
        }
    }

    fun consoleText(): String = synchronized(consoleLines) {
        consoleLines.joinToString("\n") { "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(it.time))} ${it.level} ${it.probeName} ${it.text}" }
    }

    // --- facade helpers ----------------------------------------------------------------------------------------------

    fun publish() {
        val probes = store.probes.value.sortedByDescending { it.updatedAt }
        val status = probes.associate { p ->
            val s = stat(p.id)
            p.id to ProbeStatus(
                id = p.id,
                running = p.enabled && p.id in loaded,
                lastRunAt = s.lastRunAt,
                events = s.events.get(),
                avgMs = s.avg(),
                maxMs = s.maxMs,
                errors = s.errors,
                lastError = s.lastError,
                offReason = if (p.enabled) null else s.offReason,
                dropped = s.dropped.get(),
            )
        }
        val running = probes.filter { it.enabled }
        _snapshot.value = ProbesSnapshot(
            probes = probes,
            status = status,
            runningCount = running.size,
            totalMsPerEvent = running.sumOf { stat(it.id).avg().toDouble() }.toFloat(),
            experimentalCount = running.count { it.experimental },
            runtimeMb = runtimeMb,
        )
    }

    @Volatile
    private var runtimeMb = 0f

    /** The engine's minute tick: auto-off and the runtime's memory (taken on the probe thread, idle) */
    fun minuteTick(now: Long) {
        for (p in store.probes.value) {
            if (p.enabled && p.autoOffAt > 0L && p.autoOffAt <= now) switchOff(p.id, "auto-off ${p.autoOff.label}")
        }
        if (qjs == null) {
            runtimeMb = 0f
            publish()
            return
        }
        scope.launch(dispatcher) {
            runtimeMb = runCatching { (qjs?.memoryUsage?.memoryUsedSize ?: 0L) / (1024f * 1024f) }.getOrDefault(0f)
            publish()
        }
    }

    fun setEnabled(id: String, on: Boolean) {
        val p = store.get(id) ?: return
        val now = System.currentTimeMillis()
        store.update(id) {
            it.copy(
                enabled = on,
                autoOffAt = if (on && p.autoOff.hours > 0) now + p.autoOff.hours * 3_600_000L else 0L,
                updatedAt = if (on) now else it.updatedAt,
            )
        }
        if (on) {
            stat(id).offReason = null
            stat(id).overBudget = 0
            stat(id).minuteMs = 0f
            // Re-enabling reloads the script (the body scan and a changed Experimental flag take effect)
            if (qjs != null) scope.launch(dispatcher) { loaded.remove(id) }
        }
        refresh()
    }

    fun setExperimental(id: String, on: Boolean) {
        store.update(id) { it.copy(experimental = on) }
        if (qjs != null) scope.launch(dispatcher) { loaded.remove(id) }
        refresh()
    }

    fun setAutoOff(id: String, autoOff: ProbeAutoOff) {
        store.update(id) {
            it.copy(autoOff = autoOff, autoOffAt = if (it.enabled && autoOff.hours > 0) System.currentTimeMillis() + autoOff.hours * 3_600_000L else 0L)
        }
        publish()
    }

    fun setBudget(id: String, budget: ProbeBudget) {
        store.update(id) { it.copy(budget = budget) }
        publish()
    }

    fun setMarker(id: String, on: Boolean) {
        store.update(id) { it.copy(markerOnGraph = on) }
        publish()
    }

    suspend fun save(draft: ProbeDraft): ProbeDescriptor {
        val d = store.save(draft)
        if (qjs != null) withContext(dispatcher) { loaded.remove(d.id) }
        stat(d.id).wantsBody = draft.code.contains(".text(")
        refresh()
        return d
    }

    suspend fun delete(id: String) {
        store.delete(id)
        if (qjs != null) {
            withContext(dispatcher) {
                runCatching { qjs?.evaluate<Any?>("__probeUnload(${id.jsQuoted()})", "<unload>") }
                loaded.remove(id)
            }
        }
        stats.remove(id)
        refresh()
    }

    /** Replays the newest ≤ 50 matching events from the rings through the saved code */
    suspend fun runOnce(id: String): ProbeRunResult {
        val p = store.get(id) ?: return ProbeRunResult(false, 0, 0f, "probe not found", null)
        return withContext(dispatcher) {
            val engine = ensureRuntime() ?: return@withContext ProbeRunResult(false, 0, 0f, "runtime unavailable", null)
            closeJob?.cancel()
            closeJob = null
            // Fresh load of the saved code
            runCatching { engine.evaluate<Any?>("__probeUnload(${id.jsQuoted()})", "<unload>") }
            loaded.remove(id)
            load(engine, p)
            if (id !in loaded) {
                val err = stat(id).lastError
                return@withContext ProbeRunResult(false, 0, 0f, err, lineOf(err))
            }
            val started = System.nanoTime()
            var replayed = 0
            var sourceName = "the rings"
            for (hook in p.hookSet) {
                val events = replayEvents(hook)
                if (events.isNotEmpty()) sourceName = ringName(hook)
                for (json in events) {
                    runOne(engine, p, hook, json, -1)
                    replayed++
                }
            }
            val ms = (System.nanoTime() - started) / 1_000_000f
            emit(id, 'D', "run once · $replayed events replayed from $sourceName in ${"%.0f".format(ms)} ms")
            // Not a real enable: unload unless it is enabled
            if (!p.enabled) {
                runCatching { engine.evaluate<Any?>("__probeUnload(${id.jsQuoted()})", "<unload>") }
                loaded.remove(id)
                stat(id).netResponseSources = emptySet()
                refresh()
            }
            publish()
            ProbeRunResult(true, replayed, ms, stat(id).lastError.takeIf { stat(id).errors > 0 }, null)
        }
    }

    private fun lineOf(error: String?): Int? = error?.let { Regex("""(?:\.js|<load[^>]*>):(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun ringName(hook: ProbeHook): String = when (hook) {
        ProbeHook.NET_REQUEST, ProbeHook.NET_RESPONSE -> if (Traces.net) "the net ring" else "the request ring"
        ProbeHook.JS_CALL, ProbeHook.JS_RESULT -> "the js ring"
        ProbeHook.PLAYER_EVENT, ProbeHook.HLS_SEGMENT, ProbeHook.HOSTER_STEP -> "the player ring"
        ProbeHook.READER_PAGE -> "the reader ring"
        ProbeHook.SYNC_PHASE -> "the sync ring"
    }

    private fun replayEvents(hook: ProbeHook): List<String> {
        val n = 50
        return when (hook) {
            ProbeHook.NET_REQUEST, ProbeHook.NET_RESPONSE -> {
                val fromRing = if (Traces.net) TraceWriter.tail(TraceKind.NET, n) else emptyList()
                if (fromRing.isNotEmpty()) {
                    fromRing
                } else {
                    NetStats.rows().takeLast(n).map { r ->
                        jsJson.encodeToString(
                            NetTraceEvent.serializer(),
                            NetTraceEvent(
                                r.timeMs,
                                r.source,
                                r.method,
                                "https://${r.host}${r.path}",
                                r.status,
                                r.ms,
                                r.bytes,
                            ),
                        )
                    }
                }
            }
            ProbeHook.JS_CALL, ProbeHook.JS_RESULT -> TraceWriter.tail(TraceKind.JS, n)
            ProbeHook.PLAYER_EVENT -> TraceWriter.tail(TraceKind.PLAYER, 200).filter { it.contains("\"event\"") }.takeLast(n)
            ProbeHook.HLS_SEGMENT -> TraceWriter.tail(TraceKind.PLAYER, 200).filter { it.contains("\"kind\"") }.takeLast(n)
            ProbeHook.HOSTER_STEP -> TraceWriter.tail(TraceKind.PLAYER, 200).filter { it.contains("\"step\"") }.takeLast(n)
            ProbeHook.READER_PAGE -> TraceWriter.tail(TraceKind.READER, n)
            ProbeHook.SYNC_PHASE -> TraceWriter.tail(TraceKind.SYNC, n)
        }
    }

    // --- lint ----------------------------------------------------------------------------------------------------------

    private val FORBIDDEN = listOf(
        "await" to Regex("""\bawait\b"""),
        "fetch" to Regex("""\bfetch\s*\("""),
        "setTimeout" to Regex("""\bsetTimeout\s*\("""),
        "setInterval" to Regex("""\bsetInterval\s*\("""),
        "require" to Regex("""\brequire\s*\("""),
    )

    fun lint(code: String, experimental: Boolean): List<ProbeLint> {
        val out = ArrayList<ProbeLint>()
        if (code.length > 64 * 1024) out.add(ProbeLint(1, "the script is over 64 KB", true))
        val lines = code.lines()
        var anyHook = false
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//")) continue
            ProbeStore.hooksOf(line).forEach { id ->
                anyHook = true
                if (ProbeHook.byId(id) == null) out.add(ProbeLint(i + 1, "unknown hook '$id' · known: ${ProbeHook.entries.joinToString(", ") { it.id }}", true))
            }
            for ((name, pattern) in FORBIDDEN) {
                if (pattern.containsMatchIn(line)) out.add(ProbeLint(i + 1, "$name is not available in a probe", true))
            }
            if (line.contains("replaceBody(") && !experimental) {
                out.add(ProbeLint(i + 1, "replaceBody needs Experimental on; it is skipped otherwise", false))
            }
        }
        if (!anyHook && ProbeStore.hooksOf(code).isEmpty()) out.add(ProbeLint(1, "no probe.on('hook', filter, fn) call", true))
        return out
    }
}
