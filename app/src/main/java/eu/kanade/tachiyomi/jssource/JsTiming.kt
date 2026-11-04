package eu.kanade.tachiyomi.jssource

import android.app.Application
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK (r119): how much time each JS source costs, per source id: time spent running its JS (every call into the
 * engine: popular / latest / search / details / episodes / videos / pages / tags…, the load of its code, metadata)
 * against the time those calls spent waiting on the network (`fetchUrl`, `render`, `findVideo` host calls), the call
 * count, errors and the slowest call, for this session and the last 24 h, plus the last 20 calls of each source.
 * Shown by Diagnostics' "Source Lab sources · JS time" card (`ui/logs/JsTimeCard.kt`).
 *
 * "JS ms" = the call's wall time minus its network waits and `host.sleep` (so it also holds the app-side helpers the
 * JS asked for, such as HTML parsing and selecting). Waits that overlap (several fetches at once) count once.
 *
 * Only the runtimes serving the app are counted: the Code editor's / Source Lab's test copies and probes are not.
 * Cost on the hot path: two `System.nanoTime()` reads, one small [Call] per call, one lock held for a few array
 * writes. The 24 h buckets and the recent calls are kept in `filesDir/js_timing.json` (written ≤ every 30 s).
 */
object JsTiming {

    /** One call into the engine */
    class Call(val at: Long, val name: String, val jsMs: Long, val netMs: Long, val ok: Boolean)

    /** One row of the card */
    data class SourceRow(
        val id: Long,
        val name: String,
        val jsMs: Long,
        val netMs: Long,
        val calls: Int,
        val errors: Int,
        val slowestName: String?,
        val slowestMs: Long,
    )

    enum class Period(val label: String) { SESSION("this session"), DAY("last 24 h") }

    private const val RECENT = 20
    private const val HOURS = 24
    private const val HOUR_MS = 3_600_000L
    private const val SAVE_AFTER_MS = 30_000L
    private const val FILE_NAME = "js_timing.json"

    private class Src(val id: Long) {
        @Volatile
        var name: String = ""

        // This session
        var sJsMs = 0L
        var sNetMs = 0L
        var sCalls = 0
        var sErrors = 0
        var sSlowMs = 0L
        var sSlowName: String? = null

        // Last 24 h: one bucket per hour (epoch hour in [hour], -1 = empty)
        val hour = LongArray(HOURS) { -1L }
        val bJs = LongArray(HOURS)
        val bNet = LongArray(HOURS)
        val bCalls = IntArray(HOURS)
        val bErrors = IntArray(HOURS)
        val bSlowMs = LongArray(HOURS)
        val bSlowName = arrayOfNulls<String>(HOURS)

        // Last calls, a ring
        val recent = arrayOfNulls<Call>(RECENT)
        var recentNext = 0
        var recentCount = 0

        fun bucket(h: Long): Int {
            val i = (h % HOURS).toInt()
            if (hour[i] != h) {
                hour[i] = h
                bJs[i] = 0L
                bNet[i] = 0L
                bCalls[i] = 0
                bErrors[i] = 0
                bSlowMs[i] = 0L
                bSlowName[i] = null
            }
            return i
        }

        fun push(call: Call) {
            recent[recentNext] = call
            recentNext = (recentNext + 1) % RECENT
            if (recentCount < RECENT) recentCount++
        }

        /** Newest first */
        fun recentList(): List<Call> = buildList {
            for (k in 1..recentCount) recent[(recentNext - k + RECENT) % RECENT]?.let { add(it) }
        }
    }

    private val lock = Any()
    private val sources = HashMap<Long, Src>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loadStarted = false

    @Volatile
    private var savePending = false

    private fun file(): File? = runCatching { File(Injekt.get<Application>().filesDir, FILE_NAME) }.getOrNull()

    /**
     * A call of source [sourceId] ([sourceName]) named [method] ended: [wallNs] from start to end, [netNs] of it
     * waiting on the network, [sleepNs] in `host.sleep`.
     */
    fun record(
        sourceId: Long,
        sourceName: String,
        method: String,
        wallNs: Long,
        netNs: Long,
        sleepNs: Long,
        ok: Boolean,
    ) {
        if (sourceId == 0L) return
        ensureLoaded()
        val wallMs = wallNs / 1_000_000L
        val netMs = (netNs / 1_000_000L).coerceIn(0L, wallMs)
        val jsMs = ((wallNs - netNs - sleepNs) / 1_000_000L).coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val call = Call(now, method, jsMs, netMs, ok)
        synchronized(lock) {
            val s = sources[sourceId] ?: Src(sourceId).also { sources[sourceId] = it }
            s.name = sourceName
            s.sJsMs += jsMs
            s.sNetMs += netMs
            s.sCalls++
            if (!ok) s.sErrors++
            if (wallMs >= s.sSlowMs) {
                s.sSlowMs = wallMs
                s.sSlowName = method
            }
            val i = s.bucket(now / HOUR_MS)
            s.bJs[i] += jsMs
            s.bNet[i] += netMs
            s.bCalls[i]++
            if (!ok) s.bErrors[i]++
            if (wallMs >= s.bSlowMs[i]) {
                s.bSlowMs[i] = wallMs
                s.bSlowName[i] = method
            }
            s.push(call)
        }
        scheduleSave()
    }

    /** One row per source with calls in [period], sorted by JS time (most first) */
    fun rows(period: Period): List<SourceRow> {
        ensureLoaded()
        val nowHour = System.currentTimeMillis() / HOUR_MS
        return synchronized(lock) {
            sources.values.mapNotNull { s ->
                when (period) {
                    Period.SESSION ->
                        SourceRow(s.id, s.name, s.sJsMs, s.sNetMs, s.sCalls, s.sErrors, s.sSlowName, s.sSlowMs)
                            .takeIf { it.calls > 0 }
                    Period.DAY -> {
                        var js = 0L
                        var net = 0L
                        var calls = 0
                        var errors = 0
                        var slowMs = 0L
                        var slowName: String? = null
                        for (i in 0 until HOURS) {
                            val h = s.hour[i]
                            if (h < 0L || h <= nowHour - HOURS || h > nowHour) continue
                            js += s.bJs[i]
                            net += s.bNet[i]
                            calls += s.bCalls[i]
                            errors += s.bErrors[i]
                            if (s.bSlowMs[i] >= slowMs && s.bSlowName[i] != null) {
                                slowMs = s.bSlowMs[i]
                                slowName = s.bSlowName[i]
                            }
                        }
                        SourceRow(s.id, s.name, js, net, calls, errors, slowName, slowMs).takeIf { it.calls > 0 }
                    }
                }
            }
        }.sortedWith(compareByDescending<SourceRow> { it.jsMs }.thenByDescending { it.calls })
    }

    /** The last 20 calls of [sourceId], newest first */
    fun recent(sourceId: Long): List<Call> = synchronized(lock) { sources[sourceId]?.recentList().orEmpty() }

    /** Forgets everything this card shows (session, 24 h, recent calls) and its file */
    fun reset() {
        synchronized(lock) { sources.clear() }
        scope.launch {
            runCatching { file()?.delete() }
            AppLog.add('I', "js", "JS time per source was reset")
        }
    }

    // --- File -------------------------------------------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loadStarted) return
        synchronized(lock) {
            if (loadStarted) return
            loadStarted = true
        }
        scope.launch {
            runCatching { load() }.onFailure { AppLog.add('W', "js", "JS time file unreadable: ${it.message}") }
        }
    }

    private fun scheduleSave() {
        if (savePending) return
        savePending = true
        scope.launch {
            delay(SAVE_AFTER_MS)
            savePending = false
            runCatching { save() }
        }
    }

    /** Merges the file into memory: calls recorded before it was read are kept */
    private fun load() {
        val f = file()?.takeIf { it.isFile } ?: return
        val root = JSONObject(f.readText())
        val nowHour = System.currentTimeMillis() / HOUR_MS
        val list = root.optJSONArray("sources") ?: return
        synchronized(lock) {
            for (n in 0 until list.length()) {
                val o = list.optJSONObject(n) ?: continue
                val id = o.optLong("id", 0L).takeIf { it != 0L } ?: continue
                val s = sources[id] ?: Src(id).also { sources[id] = it }
                if (s.name.isEmpty()) s.name = o.optString("name")
                o.optJSONArray("hours")?.let { hours ->
                    for (k in 0 until hours.length()) {
                        val b = hours.optJSONObject(k) ?: continue
                        val h = b.optLong("h", -1L)
                        if (h < 0L || h <= nowHour - HOURS || h > nowHour) continue
                        val i = (h % HOURS).toInt()
                        if (s.hour[i] > h) continue // a newer hour is already there
                        val idx = s.bucket(h)
                        s.bJs[idx] += b.optLong("js")
                        s.bNet[idx] += b.optLong("net")
                        s.bCalls[idx] += b.optInt("calls")
                        s.bErrors[idx] += b.optInt("errors")
                        val slow = b.optLong("slowMs")
                        if (slow >= s.bSlowMs[idx] && b.has("slow")) {
                            s.bSlowMs[idx] = slow
                            s.bSlowName[idx] = b.optString("slow")
                        }
                    }
                }
                // Older calls from the file go before the ones of this run
                val mine = s.recentList().asReversed()
                val old = o.optJSONArray("recent")
                if (old != null) {
                    s.recentCount = 0
                    s.recentNext = 0
                    for (k in 0 until old.length()) {
                        val c = old.optJSONObject(k) ?: continue
                        val call = Call(
                            c.optLong("at"),
                            c.optString("n"),
                            c.optLong("js"),
                            c.optLong("net"),
                            c.optBoolean("ok", true),
                        )
                        s.push(call)
                    }
                    mine.forEach { s.push(it) }
                }
            }
        }
    }

    private fun save() {
        val f = file() ?: return
        val nowHour = System.currentTimeMillis() / HOUR_MS
        val root = JSONObject()
        val list = JSONArray()
        synchronized(lock) {
            for (s in sources.values) {
                val o = JSONObject().put("id", s.id).put("name", s.name)
                val hours = JSONArray()
                for (i in 0 until HOURS) {
                    val h = s.hour[i]
                    if (h < 0L || h <= nowHour - HOURS || s.bCalls[i] == 0) continue
                    val b = JSONObject().put("h", h).put("js", s.bJs[i]).put("net", s.bNet[i])
                        .put("calls", s.bCalls[i]).put("errors", s.bErrors[i]).put("slowMs", s.bSlowMs[i])
                    s.bSlowName[i]?.let { b.put("slow", it) }
                    hours.put(b)
                }
                val recent = JSONArray()
                s.recentList().asReversed().forEach { c ->
                    recent.put(
                        JSONObject().put("at", c.at).put("n", c.name).put("js", c.jsMs).put("net", c.netMs)
                            .put("ok", c.ok),
                    )
                }
                if (hours.length() == 0 && recent.length() == 0) continue
                list.put(o.put("hours", hours).put("recent", recent))
            }
        }
        root.put("v", 1).put("sources", list)
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /**
     * Wall time during which at least one wait of a kind (network, sleep) was in progress, so waits that overlap
     * (several fetches at once from `Promise.all`) count once. No allocation.
     */
    internal class WaitClock {
        private var inFlight = 0
        private var since = 0L
        private var total = 0L

        fun start() = synchronized(this) {
            if (inFlight++ == 0) since = System.nanoTime()
        }

        fun end() = synchronized(this) {
            if (inFlight > 0 && --inFlight == 0) total += System.nanoTime() - since
        }

        /** Waited so far, the running wait included */
        fun totalNs(): Long = synchronized(this) {
            if (inFlight > 0) total + System.nanoTime() - since else total
        }
    }
}
