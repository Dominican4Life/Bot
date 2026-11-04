package eu.kanade.tachiyomi.ui.sourcelab.newsource.dir

import eu.kanade.tachiyomi.jssource.JsBrowserFetch
import eu.kanade.tachiyomi.network.BotCheckGate
import eu.kanade.tachiyomi.network.BotCheckReporter
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.SiteBlockedException
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * KMK (r119): faster directory reading in the builder. Pages whose address has a page number are fetched a few at a
 * time ([DEFAULT_IN_FLIGHT], never more than [MAX_IN_FLIGHT]) and handed back **in page order**; the owner's random
 * pause comes between each set. While the site is being asked slowly (after a bot check or a 429, [BotCheckGate]'s
 * pace for that site, the same one `BotChecks.session(sourceId)` shows) or after a 429 in this read, pages go one at a
 * time with that pace. A bot check page stops everything: it is registered as waiting (so it shows where the app shows
 * checks) and the read ends with a reason; the app never solves, clicks or gets around it.
 */
internal object DirPager {

    const val DEFAULT_IN_FLIGHT = 3
    const val MAX_IN_FLIGHT = 4

    /** Starts inside one set are spread a little, so the site never gets them in the same instant */
    private const val STAGGER_MIN_MS = 150L
    private const val STAGGER_MAX_MS = 400L
    private const val POLL_MS = 250L

    /** One page as the site answered it */
    class Page(val url: String, val code: Int, val body: String)

    /** The site answered with a bot check page (or a request is waiting for one): every read of it stops */
    class BotCheckFound(val url: String) : IOException(BOT_CHECK_TEXT)

    const val BOT_CHECK_TEXT =
        "the site showed a bot check. The app never solves it: pass it on the site's page yourself, then read again"

    /** How a read goes now: [inFlight] pages at a time, at least [paceMs] between pages ([why] for the log) */
    data class Pace(val inFlight: Int, val paceMs: Long, val why: String)

    /** Progress of a read: [done] pages read, [entries] so far, pages [from]..[to] in flight (0 = none), a pause */
    data class Progress(val done: Int, val entries: Int, val from: Int, val to: Int, val pauseMs: Long)

    /** The pace for [url]'s site now; [saw429] = this read got a "too many requests" */
    fun paceFor(url: String, saw429: Boolean = false): Pace {
        val host = BotCheckGate.hostOf(url)
        val state = host?.let { runCatching { BotCheckGate.siteState(it) }.getOrNull() }
        val pace = state?.paceMs?.takeIf { it > 0 } ?: BotCheckGate.DEFAULT_PACE_MS
        return when {
            saw429 -> Pace(1, pace, "the site answered 429")
            state?.slowUntil != null -> Pace(1, pace, "the site is asked slowly after a check or a 429")
            else -> Pace(DEFAULT_IN_FLIGHT.coerceIn(1, MAX_IN_FLIGHT), 0L, "")
        }
    }

    /**
     * One page through the app's client (its cookies and pace), the app's user agent and [referer]. A bot check
     * reply (or a request that waited for one) throws [BotCheckFound] after registering it; a 429 is returned as is
     */
    suspend fun fetch(url: String, referer: String): Page = withContext(Dispatchers.IO) {
        val network = Injekt.get<NetworkHelper>()
        val builder = Request.Builder().url(url).header("Referer", referer)
        val agent = runCatching { network.defaultUserAgentProvider() }.getOrNull().orEmpty()
        if (agent.isNotBlank()) builder.header("User-Agent", agent)
        if (BotCheckGate.isWaiting(url)) throw BotCheckFound(url)
        val response = try {
            network.client.newCall(builder.build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isBotCheckError(e)) throw found(url, agent)
            throw e
        }
        response.use { r ->
            val kind = runCatching { BotCheckReporter.classify(r) }.getOrNull()
            if (kind == BotCheckReporter.Kind.BOT_CHECK) throw found(url, agent)
            val body = r.body.string()
            if (r.code in setOf(403, 503) && JsBrowserFetch.isChallenge(r.code, r.header("Server"), body)) throw found(url, agent)
            Page(url, r.code, body)
        }
    }

    private fun found(url: String, agent: String): BotCheckFound {
        // Shown where the app shows checks (when the site belongs to a source); never solved here
        runCatching { BotCheckGate.register(url, agent.ifBlank { null }, null) }
        AppLog.add('W', "lab", "extras directory: bot check on $url, every read of the site stops (never solved by the app)")
        return BotCheckFound(url)
    }

    /** A bot check error: ours, the gate's, or the Cloudflare interceptor's (a private class, known by its name) */
    fun isBotCheckError(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(6).any {
        it is BotCheckFound ||
            (it is SiteBlockedException && it.kind == BotCheckReporter.Kind.BOT_CHECK) ||
            it.javaClass.simpleName == "CloudflareBypassException"
    }

    /** Waits [ms] in small steps, ending early when [shouldStop] says so */
    suspend fun pauseFor(ms: Long, shouldStop: () -> Boolean) {
        var left = ms
        while (left > 0 && !shouldStop()) {
            val step = minOf(left, POLL_MS)
            delay(step)
            left -= step
        }
    }

    /** How a range read ended: [kind] end|stopped|botcheck|failed, at page [page], [reason] (null = range done) */
    data class RangeEnd(val kind: String, val page: Int, val reason: String?)

    /**
     * Reads pages [first]..[last] (addresses from [urlOf]) a few at a time, in page order. Before each set after the
     * first, a random [pauseMinMs]..[pauseMaxMs] pause (at least the site's pace when it is asked slowly). [onPage]
     * gets each page in order and returns null to go on, or why the read ends there (pages after it are dropped).
     * [shouldStop] cancels the pages in flight; what was handed to [onPage] stays read.
     */
    suspend fun readRange(
        first: Int,
        last: Int,
        urlOf: (Int) -> String,
        referer: String,
        pauseMinMs: Long,
        pauseMaxMs: Long,
        shouldStop: () -> Boolean,
        onSet: (from: Int, to: Int, pauseMs: Long) -> Unit,
        onPage: suspend (n: Int, page: Page) -> String?,
    ): RangeEnd {
        val low = minOf(pauseMinMs, pauseMaxMs).coerceAtLeast(0L)
        val high = maxOf(pauseMinMs, pauseMaxMs).coerceAtLeast(0L)
        var n = first
        var saw429 = false
        val retried = HashSet<Int>()
        var sets = 0
        var lastWhy = ""
        while (n <= last) {
            if (shouldStop()) return RangeEnd("stopped", n - 1, null)
            val pace = paceFor(urlOf(n), saw429)
            if (pace.why != lastWhy) {
                lastWhy = pace.why
                AppLog.add(
                    'I',
                    "lab",
                    "extras directory read: ${pace.inFlight} page(s) at a time" +
                        if (pace.why.isNotEmpty()) " (${pace.why}, ${pace.paceMs} ms apart)" else "",
                )
            }
            val to = minOf(last, n + pace.inFlight - 1)
            if (sets > 0) {
                val pause = maxOf(Random.nextLong(low, high + 1), pace.paceMs)
                onSet(n, to, pause)
                pauseFor(pause, shouldStop)
                if (shouldStop()) return RangeEnd("stopped", n - 1, null)
            }
            onSet(n, to, 0L)
            val urls = (n..to).map(urlOf)
            val set = fetchSet(urls, referer, shouldStop)
            sets++
            var again = -1
            for ((i, r) in set.pages.withIndex()) {
                val pn = n + i
                if (r == null) {
                    // (a page cancelled because another page of its set was a bot check)
                    return if (set.botCheck) RangeEnd("botcheck", pn, BOT_CHECK_TEXT) else RangeEnd("stopped", pn - 1, null)
                }
                val page = r.getOrElse { e ->
                    if (isBotCheckError(e)) return RangeEnd("botcheck", pn, "page $pn: $BOT_CHECK_TEXT")
                    return RangeEnd("failed", pn, "page $pn could not be read (${e.message ?: e.javaClass.simpleName})")
                }
                if (page.code == 429) {
                    // One at a time with the site's pace from here; this page is asked once more
                    runCatching { BotCheckGate.markSlow(page.url) }
                    if (!retried.add(pn)) return RangeEnd("failed", pn, "the site answered 429 (too many requests) again for page $pn")
                    saw429 = true
                    again = pn
                    break
                }
                val end = onPage(pn, page)
                if (end != null) return RangeEnd("end", pn, end)
            }
            n = if (again > 0) again else to + 1
        }
        return RangeEnd("end", last, null)
    }

    /** A set's pages in order (null = cancelled), and whether one of them was a bot check */
    private class SetResult(val pages: List<Result<Page>?>, val botCheck: Boolean)

    /** [urls] at once (starts spread a little); a page cancelled by [shouldStop] or a bot check is null */
    private suspend fun fetchSet(urls: List<String>, referer: String, shouldStop: () -> Boolean): SetResult = coroutineScope {
        val botHit = AtomicBoolean(false)
        val jobs: List<Deferred<Result<Page>>> = urls.mapIndexed { i, u ->
            async(Dispatchers.IO) {
                if (i > 0) delay(Random.nextLong(STAGGER_MIN_MS, STAGGER_MAX_MS + 1) * i)
                try {
                    Result.success(fetch(u, referer))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isBotCheckError(e)) botHit.set(true)
                    Result.failure(e)
                }
            }
        }
        val watcher = launch {
            while (isActive) {
                // Cancel: the pages in flight end now. A bot check on one page: the others are not asked any more
                if (shouldStop() || botHit.get()) {
                    jobs.forEach { if (!it.isCompleted) it.cancel() }
                    break
                }
                delay(POLL_MS)
            }
        }
        val out = jobs.map { d ->
            try {
                d.await()
            } catch (e: CancellationException) {
                ensureActive()
                null
            }
        }
        watcher.cancel()
        SetResult(out, botHit.get())
    }
}
