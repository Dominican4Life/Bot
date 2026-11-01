package eu.kanade.tachiyomi.diagnostics

import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.network.CountingBody
import eu.kanade.tachiyomi.network.NetworkHooks
import eu.kanade.tachiyomi.network.SourceTag
import eu.kanade.tachiyomi.util.system.AppLog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * KMK (r97): The app side of [NetworkHooks.Listener] (contract §3.8): per-source byte counters, the in-memory
 * request ring (last 600 requests in a direct buffer plus a string table), the host → source map for APK sources,
 * the net trace event and the `V net.v` line once a body is consumed, and the probe hooks. Nothing here allocates
 * on the request path beyond the per-call record.
 */
internal object NetStats : NetworkHooks.Listener {

    /** Stacked network slots: 0–5 sources, 6 covers, 7 other (upload is a separate counter) */
    const val SLOT_SOURCES = 6
    const val SLOT_COVERS = 6
    const val SLOT_OTHER = 7
    const val SLOTS = 8

    val bytesIn = AtomicLong()
    val bytesOut = AtomicLong()
    val calls = AtomicLong()
    val errors = AtomicLong()
    val bytesBySlot = AtomicLongArray(SLOTS)

    /** Source name per slot (0–5), assigned on first appearance */
    private val slotNames = arrayOfNulls<String>(SLOT_SOURCES)
    private val slotBySource = ConcurrentHashMap<String, Int>()
    private val bytesBySource = ConcurrentHashMap<String, AtomicLong>()

    fun slotName(slot: Int): String? = slotNames.getOrNull(slot)

    // KMK (r98) -->
    /** The name the Source Lab's runtimes are shown under (graph legend, usage table, net lines, traces) */
    const val LAB_TESTS = "Source Lab tests"

    /**
     * The display name of a runtime's [SourceTag]. The Lab's detection probe runs as `JsRuntime.create(name = "lab",
     * key = LabProbe.KEY, sourceId = 0)` and a Retest of a source without a name falls back to "lab": both are the
     * Lab's tests, not a source. The editor's and the Retest's test copies of a real source keep that source's name
     * (they fetch what the source fetches).
     */
    private fun nameOf(tag: SourceTag): String =
        if (tag.key == eu.kanade.tachiyomi.jssource.lab.LabProbe.KEY || tag.name == "lab") LAB_TESTS else tag.name
    // KMK (r98) <--

    private fun slotOf(source: String?, image: Boolean): Int {
        if (source == null) return if (image) SLOT_COVERS else SLOT_OTHER
        slotBySource[source]?.let { return it }
        synchronized(slotNames) {
            slotBySource[source]?.let { return it }
            val free = slotNames.indexOfFirst { it == null }
            val slot = if (free >= 0) free.also { slotNames[it] = source } else SLOT_OTHER
            slotBySource[source] = slot
            return slot
        }
    }

    // --- request ring (600 × 32 bytes, direct) ---------------------------------------------------------------------

    private const val ROWS = 600
    private const val ROW = 32
    private val ring = ByteBuffer.allocateDirect(ROWS * ROW).order(ByteOrder.LITTLE_ENDIAN)
    private val paths = arrayOfNulls<String>(ROWS)
    private var next = 0
    private var count = 0
    private val hosts = ArrayList<String>()
    private val hostIndex = HashMap<String, Int>()
    private val sources = ArrayList<String>()
    private val sourceIndex = HashMap<String, Int>()
    private val methods = listOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS")

    /** The string tables are reset (with the ring rows, which index into them) past this many distinct entries */
    private const val TABLE_CAP = 4096

    private fun intern(list: ArrayList<String>, index: HashMap<String, Int>, s: String): Int {
        index[s]?.let { return it }
        if (list.size >= TABLE_CAP) {
            hosts.clear()
            hostIndex.clear()
            sources.clear()
            sourceIndex.clear()
            paths.fill(null)
            next = 0
            count = 0
        }
        list.add(s)
        val idx = list.size - 1
        index[s] = idx
        return idx
    }

    /** Every host seen in the request ring's table (for the report's redactor: these are real hosts) */
    fun knownHosts(): Set<String> = synchronized(ring) { hosts.toHashSet() }

    private fun addRow(
        time: Long,
        method: String,
        host: String,
        path: String,
        status: Int,
        ms: Long,
        bytes: Long,
        source: String?,
        slot: Int,
    ) {
        synchronized(ring) {
            val at = next * ROW
            ring.putLong(at, time)
            ring.putInt(at + 8, status)
            ring.putInt(at + 12, ms.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            ring.putLong(at + 16, bytes)
            ring.put(at + 24, methods.indexOf(method).coerceAtLeast(0).toByte())
            ring.put(at + 25, slot.toByte())
            ring.putShort(at + 26, intern(hosts, hostIndex, host).coerceAtMost(Short.MAX_VALUE.toInt()).toShort())
            ring.putShort(at + 28, (source?.let { intern(sources, sourceIndex, it) } ?: -1).toShort())
            paths[next] = path
            next = (next + 1) % ROWS
            if (count < ROWS) count++
        }
    }

    /** The ring's rows, oldest first */
    fun rows(): List<NetRequestRow> = synchronized(ring) {
        val out = ArrayList<NetRequestRow>(count)
        val start = if (count < ROWS) 0 else next
        for (i in 0 until count) {
            val idx = (start + i) % ROWS
            val at = idx * ROW
            val slot = ring.get(at + 25).toInt()
            val src = ring.getShort(at + 28).toInt()
            out.add(
                NetRequestRow(
                    timeMs = ring.getLong(at),
                    method = methods.getOrNull(ring.get(at + 24).toInt()) ?: "GET",
                    host = hosts.getOrNull(ring.getShort(at + 26).toInt()) ?: "?",
                    path = paths[idx].orEmpty(),
                    status = ring.getInt(at + 8),
                    ms = ring.getInt(at + 12).toLong(),
                    bytes = ring.getLong(at + 16),
                    source = if (src >= 0) sources.getOrNull(src) else null,
                    color = colorOf(slot),
                ),
            )
        }
        out
    }

    fun colorOf(slot: Int): ColorKey = when (slot) {
        0 -> ColorKey.SOURCE_0
        1 -> ColorKey.SOURCE_1
        2 -> ColorKey.SOURCE_2
        3 -> ColorKey.SOURCE_3
        4 -> ColorKey.SOURCE_4
        5 -> ColorKey.SOURCE_5
        SLOT_COVERS -> ColorKey.COVERS
        else -> ColorKey.OTHER
    }

    // --- host → source map (APK sources) ----------------------------------------------------------------------------

    @Volatile
    private var hostMap: Map<String, Pair<Long, String>> = emptyMap()

    @Volatile
    private var hostMapAt = 0L

    private val hostMapBuilding = AtomicBoolean(false)

    private fun sourceOfHost(host: String): Pair<Long, String>? {
        val now = System.currentTimeMillis()
        if (now - hostMapAt > 60_000L && hostMapBuilding.compareAndSet(false, true)) {
            // Rebuilt off the OkHttp thread (the source managers' lists are not a request-path cost)
            DiagIo.post {
                try {
                    runCatching { hostMap = buildHostMap() }
                    hostMapAt = System.currentTimeMillis()
                } finally {
                    hostMapBuilding.set(false)
                }
            }
        }
        hostMap[host]?.let { return it }
        // "www." and a one-level subdomain match the registered host
        val bare = host.removePrefix("www.")
        hostMap[bare]?.let { return it }
        val parent = bare.substringAfter('.', "")
        return if (parent.contains('.')) hostMap[parent] else null
    }

    private fun buildHostMap(): Map<String, Pair<Long, String>> = buildMap {
        runCatching {
            Injekt.get<SourceManager>().getOnlineSources().forEach { s ->
                hostOf(s.baseUrl)?.let { put(it, s.id to s.name) }
            }
        }
        runCatching {
            Injekt.get<AnimeSourceManager>().getCatalogueSources().filterIsInstance<AnimeHttpSource>().forEach { s ->
                hostOf(s.baseUrl)?.let { put(it, s.id to s.name) }
            }
        }
    }

    private fun hostOf(url: String): String? = runCatching { url.toHttpUrlOrNull()?.host?.removePrefix("www.") }.getOrNull()

    /** Candidates for the network trace chips: key/id → name (JS sources by key, APK sources by id) */
    fun traceableSources(): List<Pair<String, String>> = buildList {
        runCatching {
            Injekt.get<eu.kanade.tachiyomi.jssource.JsSourceManager>().savedFlow.value.forEach { add(it.key to it.name) }
        }
        val jsIds = runCatching {
            Injekt.get<eu.kanade.tachiyomi.jssource.JsSourceManager>().savedFlow.value.map { it.id }.toSet()
        }.getOrDefault(emptySet())
        runCatching {
            Injekt.get<SourceManager>().getOnlineSources().filter { it.id !in jsIds }.forEach { add(it.id.toString() to it.name) }
        }
        runCatching {
            Injekt.get<AnimeSourceManager>().getCatalogueSources().filter { it.id !in jsIds }.forEach { add(it.id.toString() to it.name) }
        }
    }.distinctBy { it.first }

    // --- the listener --------------------------------------------------------------------------------------------

    /** What one call knows so far; filled by onCall, finished when the body closes */
    private class CallRecord(val startedAt: Long) {
        @Volatile var done = false
        var method = "GET"
        var url = ""
        var host = ""
        var path = ""
        var status = 0
        var ms = 0L
        var source: String? = null
        var sourceKey: String? = null
        var sourceId: Long? = null
        var image = false
        var contentType: String? = null
        var protocol: String? = null
        var fromCache = false
        var redirects: List<String> = emptyList()
        var reqHeaders: Map<String, String>? = null
        var respHeaders: Map<String, String>? = null
        var bodyHead: String? = null
        var error: String? = null
        var bytesOut = 0L
    }

    private class CountedBody(val record: CallRecord)

    override fun bodyCounter(): Boolean = true

    override fun wrapBody(tag: SourceTag?, response: Response): Response {
        val record = CallRecord(System.currentTimeMillis())
        val body = response.body
        val counting = CountingBody(
            body,
            onBytes = { n ->
                bytesIn.addAndGet(n)
                bytesBySlot.addAndGet(slotOf(record.source, record.image), n)
                record.source?.let { bytesBySource.getOrPut(it) { AtomicLong() }.addAndGet(n) }
            },
            onClose = { total -> finish(record, total) },
        )
        // The record travels with the response through the tag so onCall can fill it in
        return response.newBuilder().body(counting).request(response.request.newBuilder().tag(CountedBody::class.java, CountedBody(record)).build()).build()
    }

    override fun beforeProceed(tag: SourceTag?, request: Request) {
        if (ProbeRuntime.mask and ProbeHook.NET_REQUEST.mask == 0) return
        val source = tag?.let(::nameOf) ?: sourceOfHost(request.url.host)?.second
        val e = NetTraceEvent(
            t = System.currentTimeMillis(), source = source, method = request.method, url = request.url.toString(),
            status = 0, ms = 0L, bytesIn = 0L, bytesOut = request.body?.contentLength()?.coerceAtLeast(0L) ?: 0L,
        )
        ProbeRuntime.dispatchNet(
            ProbeHook.NET_REQUEST,
            jsJson.encodeToString(NetTraceEvent.serializer(), e),
            request.headers,
            null,
            source,
        )
    }

    override fun afterResponse(tag: SourceTag?, request: Request, response: Response): Response {
        if (ProbeRuntime.mask and ProbeHook.NET_RESPONSE.mask == 0) return response
        val source = tag?.let(::nameOf) ?: sourceOfHost(request.url.host)?.second
        val type = response.header("Content-Type")
        val e = NetTraceEvent(
            t = System.currentTimeMillis(), source = source, method = request.method, url = response.request.url.toString(),
            status = response.code, ms = 0L, bytesIn = response.body.contentLength().coerceAtLeast(0L), contentType = type,
            protocol = response.protocol.toString(),
        )
        val json = jsJson.encodeToString(NetTraceEvent.serializer(), e)
        return ProbeRuntime.dispatchNetResponse(json, response, source)
    }

    override fun onCall(tag: SourceTag?, request: Request, response: Response?, error: Throwable?, ms: Long) {
        calls.incrementAndGet()
        request.body?.contentLength()?.takeIf { it > 0 }?.let { bytesOut.addAndGet(it) }
        val record = response?.request?.tag(CountedBody::class.java)?.record ?: CallRecord(System.currentTimeMillis())
        val url = request.url
        record.method = request.method
        record.url = url.toString()
        record.host = url.host
        record.path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        record.ms = ms
        record.bytesOut = request.body?.contentLength()?.coerceAtLeast(0L) ?: 0L
        val apk = if (tag == null) sourceOfHost(url.host) else null
        record.source = tag?.let(::nameOf) ?: apk?.second
        record.sourceKey = tag?.key
        record.sourceId = tag?.id ?: apk?.first
        if (response != null) {
            record.status = response.code
            record.contentType = response.header("Content-Type")
            record.image = record.contentType?.startsWith("image/") == true
            record.protocol = response.protocol.toString()
            record.fromCache = response.cacheResponse != null && response.networkResponse == null
            val chain = ArrayList<String>()
            var prior = response.priorResponse
            while (prior != null && chain.size < 8) {
                chain.add("${prior.code} ${prior.request.url}")
                prior = prior.priorResponse
            }
            if (chain.isNotEmpty()) record.redirects = chain.asReversed()
            // The source filter decides before anything is captured (headers / body head are per-source costs)
            val traced = Traces.net && Traces.wantsSource(record.sourceKey, record.sourceId)
            if (traced && Traces.netHeaders) {
                record.reqHeaders = filteredHeaders(request.headers)
                record.respHeaders = filteredHeaders(response.headers)
            }
            if (traced && Traces.netBody && isText(record.contentType)) {
                record.bodyHead = runCatching { response.peekBody(DiagBudget.NET_BODY_HEAD_BYTES.toLong()).string() }.getOrNull()
            }
            if (response.code >= 400) errors.incrementAndGet()
            // No body to consume (HEAD, 204, empty): the record is final now
            if (response.body.contentLength() == 0L || response.request.tag(CountedBody::class.java) == null) finish(
                record,
                0L,
            )
        } else {
            errors.incrementAndGet()
            record.error = error?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "failed"
            finish(record, 0L)
        }
    }

    /** The body is consumed (or there was none): the ring row, the trace event and the V line */
    private fun finish(record: CallRecord, bytes: Long) {
        synchronized(record) {
            if (record.done) return
            record.done = true
        }
        val slot = slotOf(record.source, record.image)
        addRow(
            record.startedAt,
            record.method,
            record.host,
            record.path,
            record.status,
            record.ms,
            bytes,
            record.source,
            slot,
        )
        if (!Traces.net) return
        if (!Traces.wantsSource(record.sourceKey, record.sourceId)) return
        NetTrace.event(
            NetTraceEvent(
                t = record.startedAt,
                source = record.source,
                method = record.method,
                url = record.url,
                status = record.status,
                ms = record.ms,
                bytesIn = bytes,
                bytesOut = record.bytesOut,
                redirects = record.redirects,
                fromCache = record.fromCache,
                protocol = record.protocol,
                contentType = record.contentType,
                error = record.error,
                reqHeaders = record.reqHeaders,
                respHeaders = record.respHeaders,
                bodyHead = record.bodyHead,
            ),
        )
        AppLog.vFields(DiagCategory.NET, "net.v") {
            val outcome = record.error ?: "${record.status}"
            "${record.method} ${record.url} -> $outcome in ${record.ms} ms, ${"%,d".format(bytes)} B" +
                (if (record.fromCache) " (cache)" else "") to arrayOf<Pair<String, Any?>>(
                "source" to record.source, "status" to record.status, "duration_ms" to record.ms, "bytes" to bytes,
            )
        }
    }

    private val secretHeaders = setOf("cookie", "set-cookie", "authorization", "proxy-authorization")

    /** Header map with secret values replaced by `<n chars>` */
    fun filteredHeaders(headers: okhttp3.Headers): Map<String, String> = buildMap {
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            val lower = name.lowercase()
            val secret = lower in secretHeaders || lower.startsWith("x-auth") || lower.contains("token") || lower.contains("key")
            put(name, if (secret) "<${value.length} chars>" else value)
        }
    }

    fun isText(contentType: String?): Boolean {
        val t = contentType?.lowercase() ?: return false
        return t.startsWith("text/") || t.startsWith("application/json") || t.startsWith("application/xml") ||
            t.startsWith("application/javascript") || t.contains("+json") || t.contains("+xml")
    }

    /**
     * A response with [text] as its body (the Experimental probe's replaceBody), same headers minus the lengths.
     * Called from afterResponse, which runs before wrapBody: the body closed here is OkHttp's own, never a
     * CountingBody, so no record is finished early (the wrapper then counts the replacement as it is read).
     */
    fun replaceBody(response: Response, text: String): Response {
        val type = response.body.contentType()
        runCatching { response.body.close() }
        return response.newBuilder()
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .body(text.toResponseBody(type))
            .build()
    }

    // --- usage --------------------------------------------------------------------------------------------------

    /** Aggregated rows from the request ring (and from the net ring when the trace is on and the window ≥ 1 h) */
    fun usage(by: UsageBy, window: TimeWindow): List<UsageRow> {
        val since = System.currentTimeMillis() - window.seconds * 1000
        data class Acc(
            var bytes: Long = 0L,
            var requests: Int = 0,
            var ms: Long = 0L,
            var errors: Int = 0,
            val hosts: HashSet<String> = HashSet(),
            var color: ColorKey = ColorKey.OTHER,
        )
        val acc = LinkedHashMap<String, Acc>()
        fun add(name: String, host: String, bytes: Long, ms: Long, status: Int, color: ColorKey) {
            val a = acc.getOrPut(name) { Acc(color = color) }
            a.bytes += bytes
            a.requests++
            a.ms += ms
            if (status >= 400 || status == 0) a.errors++
            if (a.hosts.size < 6) a.hosts.add(host)
        }
        if (!window.isFine && Traces.net && TraceWriter.bytes(TraceKind.NET) > 0L) {
            for (line in TraceWriter.lines(TraceKind.NET)) {
                val e = runCatching { jsJson.decodeFromString(NetTraceEvent.serializer(), line) }.getOrNull() ?: continue
                if (e.t < since) continue
                val host = runCatching { e.url.toHttpUrlOrNull()?.host }.getOrNull() ?: "?"
                val name = if (by == UsageBy.SOURCE) e.source ?: (if (e.contentType?.startsWith("image/") == true) "covers CDN" else "other") else host
                add(
                    name,
                    host,
                    e.bytesIn,
                    e.ms,
                    e.status,
                    colorOf(slotOf(e.source, e.contentType?.startsWith("image/") == true)),
                )
            }
        } else {
            for (r in rows()) {
                if (r.timeMs < since) continue
                val name = if (by == UsageBy.SOURCE) r.source ?: (if (r.color == ColorKey.COVERS) "covers CDN" else "other") else r.host
                add(name, r.host, r.bytes, r.ms, r.status, r.color)
            }
        }
        return acc.entries.map { (name, a) ->
            UsageRow(
                name,
                a.hosts.joinToString(", "),
                a.bytes,
                a.requests,
                if (a.requests > 0) a.ms / a.requests else 0L,
                a.errors,
                a.color,
            )
        }.sortedByDescending { it.bytes }
    }

    /** The rows whose time falls in [from, to) */
    fun requestsBetween(from: Long, to: Long): List<NetRequestRow> = rows().filter { it.timeMs in from until to }

    fun install() {
        NetworkHooks.listener = this
    }
}
