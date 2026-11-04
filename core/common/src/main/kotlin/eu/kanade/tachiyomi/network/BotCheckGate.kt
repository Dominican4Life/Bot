package eu.kanade.tachiyomi.network

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * KMK (r116): Bot checks a person has to pass, and how gently the app asks a site afterwards.
 *
 * The app never solves, clicks, spoofs or bypasses a check. When a site asks for one that the app can't get past
 * (the hidden browser page still shows it, or the Cloudflare helper gave up), the request is registered here and
 * WAITS: the app shows it (notification, Browse, Source Lab) and the user passes it in WebView. WebView says when
 * the page stopped being a check ([finished]); the waiting requests are then sent once more with the session the
 * user's own pass produced (the shared cookies and WebView's user agent). Every request of one site shares one
 * wait, and nothing waits longer than [MAX_WAIT_MS].
 *
 * After a check or a "too many requests" the site is asked more slowly for [SLOW_FOR_MS]: [DEFAULT_PACE_MS]
 * between page requests and at most [DEFAULT_COVER_LIMIT] images at a time, or the values the user set for it.
 *
 * Everything is kept per site (host without "www."); the app maps sources onto sites (`BotChecks`).
 */
object BotCheckGate {

    /** How a wait ended */
    enum class Outcome {
        /** WebView saw the check passed (new pass cookie): try again once with the user's session */
        PASSED,

        /** WebView closed without a sign either way: try once more, it may have been passed */
        CLOSED,

        /** Nobody passed it within [MAX_WAIT_MS] */
        EXPIRED,

        /** The caller's own time ran out first (e.g. an OkHttp call's timeout); the wait itself stays open */
        OUT_OF_TIME,

        /** The request was given up */
        CANCELED,
    }

    /** A site waiting for its check, as the app shows it */
    class WaitInfo(
        val host: String,
        val url: String,
        val userAgent: String?,
        val sourceId: Long?,
        val since: Long,
        val pending: Int,
    )

    /** A site's session and pace, as the app shows it */
    class SiteState(
        val lastPassedAt: Long?,
        val passUserAgent: String?,
        val slowUntil: Long?,
        val userPaceMs: Long,
        val userCoverLimit: Int,
        /** Pace between page requests while slow (after a check or a 429): the user's value, else the default */
        val paceMs: Long,
        /** Images at a time on this site while slow */
        val coverLimit: Int,
    )

    /** Set by the app: called (on the caller's thread) whenever the waiting sites change */
    @Volatile
    var listener: ((List<WaitInfo>) -> Unit)? = null

    /** Set by the app: each step goes to its Logs */
    @Volatile
    var logger: ((String) -> Unit)? = null

    // --- Waiting -----------------------------------------------------------------------------------------------

    private class Wait(val host: String, val url: String, val userAgent: String?, val sourceId: Long?, val since: Long) {
        val done = CompletableDeferred<Outcome>()
        val pending = AtomicInteger(0)

        @Volatile var outcome: Outcome? = null

        fun end(how: Outcome) {
            if (outcome == null) outcome = how
            done.complete(how)
        }
    }

    private val waits = HashMap<String, Wait>()

    fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host?.removePrefix("www.")

    /** Whether a check is waiting for the user on this site */
    fun isWaiting(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val now = System.currentTimeMillis()
        return synchronized(waits) { waits[host]?.let { now - it.since < MAX_WAIT_MS } == true }
    }

    /** The sites waiting now (older than [MAX_WAIT_MS] dropped) */
    fun waiting(): List<WaitInfo> {
        expire()
        return synchronized(waits) {
            waits.values.map { WaitInfo(it.host, it.url, it.userAgent, it.sourceId, it.since, it.pending.get()) }
        }
    }

    /**
     * Registers [url]'s site as waiting for a check without waiting here (images, callers that can't block): the
     * app shows it, and later requests wait with the others.
     */
    fun register(url: String, userAgent: String?, sourceId: Long?) {
        join(url, userAgent, sourceId)?.let { publish() }
    }

    /**
     * Registers (or joins) the site's wait and blocks until it ends, [budgetMs] passes or [isCanceled]. Never waits
     * on the main thread (only registers there).
     */
    fun awaitBlocking(
        url: String,
        userAgent: String?,
        sourceId: Long?,
        budgetMs: Long,
        isCanceled: () -> Boolean = { false },
    ): Outcome {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            register(url, userAgent, sourceId)
            return Outcome.OUT_OF_TIME
        }
        val wait = join(url, userAgent, sourceId) ?: return Outcome.EXPIRED
        wait.pending.incrementAndGet()
        publish()
        try {
            val end = System.currentTimeMillis() + minOf(budgetMs, wait.since + MAX_WAIT_MS - System.currentTimeMillis())
            while (wait.outcome == null) {
                if (isCanceled()) return Outcome.CANCELED
                val now = System.currentTimeMillis()
                if (now >= end) return if (now - wait.since >= MAX_WAIT_MS) expire(wait) else Outcome.OUT_OF_TIME
                try {
                    Thread.sleep(minOf(POLL_MS, end - now))
                } catch (_: InterruptedException) {
                    return Outcome.CANCELED
                }
            }
            return wait.outcome ?: Outcome.EXPIRED
        } finally {
            wait.pending.decrementAndGet()
            publish()
        }
    }

    /** Registers (or joins) the site's wait and suspends until it ends or [budgetMs] passes */
    suspend fun await(url: String, userAgent: String?, sourceId: Long?, budgetMs: Long = MAX_WAIT_MS): Outcome {
        val wait = join(url, userAgent, sourceId) ?: return Outcome.EXPIRED
        wait.pending.incrementAndGet()
        publish()
        try {
            val left = minOf(budgetMs, wait.since + MAX_WAIT_MS - System.currentTimeMillis())
            return withTimeoutOrNull(left.coerceAtLeast(0L)) { wait.done.await() }
                ?: if (System.currentTimeMillis() - wait.since >= MAX_WAIT_MS) expire(wait) else Outcome.OUT_OF_TIME
        } finally {
            wait.pending.decrementAndGet()
            publish()
        }
    }

    /**
     * Called by WebView for the sites it was opened for: [passed] when it saw the check passed (a new pass cookie),
     * false when it closed without a sign. [userAgent] is the one WebView used, kept for the retries.
     */
    fun finished(hosts: Collection<String>, passed: Boolean, userAgent: String?) {
        val now = System.currentTimeMillis()
        val ended = mutableListOf<Wait>()
        hosts.mapNotNull { it.removePrefix("www.").takeIf(String::isNotBlank) }.toSet().forEach { host ->
            if (passed) {
                // Recorded before the waits are released, so whoever reloads sees the pass
                state(host).apply {
                    lastPassedAt = now
                    if (!userAgent.isNullOrBlank()) passUserAgent = userAgent
                    // After a check: slower for a while, so the site asks less often
                    slowUntil = now + SLOW_FOR_MS
                }
            }
            synchronized(waits) { waits.remove(host) }?.let(ended::add)
        }
        ended.forEach { wait ->
            log("${wait.host}: check ${if (passed) "passed" else "closed in WebView"}, ${wait.pending.get()} waiting request(s) try again")
            wait.end(if (passed) Outcome.PASSED else Outcome.CLOSED)
        }
        if (ended.isNotEmpty()) publish()
    }

    private fun join(url: String, userAgent: String?, sourceId: Long?): Wait? {
        val host = hostOf(url) ?: return null
        val now = System.currentTimeMillis()
        var created: Wait? = null
        val wait = synchronized(waits) {
            val shown = waits[host]
            if (shown != null && now - shown.since < MAX_WAIT_MS) {
                shown
            } else {
                shown?.end(Outcome.EXPIRED)
                Wait(host, url, userAgent, sourceId, now).also {
                    waits[host] = it
                    created = it
                }
            }
        }
        if (created != null) {
            markSlow(host)
            log("$host asks for a bot check the app can't pass: waiting for you to solve it (up to 10 min)")
            publish()
        }
        return wait
    }

    private fun expire(wait: Wait): Outcome {
        val removed = synchronized(waits) { if (waits[wait.host] === wait) waits.remove(wait.host) else null }
        if (removed != null) {
            log("${wait.host}: bot check not solved within 10 min")
            removed.end(Outcome.EXPIRED)
            publish()
        }
        return Outcome.EXPIRED
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        val old = synchronized(waits) { waits.values.filter { now - it.since >= MAX_WAIT_MS } }
        old.forEach(::expire)
    }

    private fun publish() {
        val snapshot = synchronized(waits) {
            waits.values.map { WaitInfo(it.host, it.url, it.userAgent, it.sourceId, it.since, it.pending.get()) }
        }
        try {
            listener?.invoke(snapshot)
        } catch (_: Exception) {
            // Showing it must never break the request
        }
    }

    // --- Session and pace --------------------------------------------------------------------------------------

    private class Site {
        @Volatile var lastPassedAt: Long? = null

        @Volatile var passUserAgent: String? = null

        @Volatile var slowUntil: Long = 0L

        @Volatile var userPaceMs: Long = 0L

        @Volatile var userCoverLimit: Int = 0
        var nextAt: Long = 0L
        var images: Semaphore? = null
        var imagesLimit: Int = 0
    }

    private val sites = java.util.concurrent.ConcurrentHashMap<String, Site>()

    private fun state(host: String): Site = sites.getOrPut(host) { Site() }

    /** The site's session and pace; null when nothing is known about it */
    fun siteState(host: String): SiteState? {
        val site = sites[host.removePrefix("www.")] ?: return null
        val now = System.currentTimeMillis()
        return SiteState(
            lastPassedAt = site.lastPassedAt,
            passUserAgent = site.passUserAgent,
            slowUntil = site.slowUntil.takeIf { it > now },
            userPaceMs = site.userPaceMs,
            userCoverLimit = site.userCoverLimit,
            paceMs = afterCheckPace(site),
            coverLimit = afterCheckCovers(site),
        )
    }

    /** The user agent of the user's last pass on this site, for requests sent again with that session */
    fun sessionUserAgent(url: String): String? = hostOf(url)?.let { sites[it]?.passUserAgent }

    /** The user's own pace for a site ("Ask less often"), used after a check or a 429; 0 = the default */
    fun setPace(host: String, paceMs: Long, coverLimit: Int) {
        state(host.removePrefix("www.")).apply {
            userPaceMs = paceMs.coerceIn(0L, MAX_PACE_MS)
            userCoverLimit = coverLimit.coerceIn(0, MAX_COVER_LIMIT)
        }
    }

    /** After a check or a "too many requests": the site is asked more slowly for [SLOW_FOR_MS] */
    fun markSlow(hostOrUrl: String) {
        val host = (if ("://" in hostOrUrl) hostOf(hostOrUrl) else hostOrUrl.removePrefix("www.")) ?: return
        val until = System.currentTimeMillis() + SLOW_FOR_MS
        val site = state(host)
        if (site.slowUntil < until - 60_000L) log("$host: asking more slowly for 10 min")
        site.slowUntil = until
    }

    // The pace applies while the site is slow (after a check or a 429): the user's own values, else the defaults
    private fun paceOf(site: Site, now: Long): Long =
        if (site.slowUntil > now) afterCheckPace(site) else 0L

    private fun coverLimitOf(site: Site, now: Long): Int =
        if (site.slowUntil > now) afterCheckCovers(site) else 0

    private fun afterCheckPace(site: Site): Long = site.userPaceMs.takeIf { it > 0 } ?: DEFAULT_PACE_MS

    private fun afterCheckCovers(site: Site): Int = site.userCoverLimit.takeIf { it > 0 } ?: DEFAULT_COVER_LIMIT

    /** How long a page request to [url] has to wait for its turn (and takes the turn) */
    private fun reserveTurn(url: String): Long {
        val host = hostOf(url) ?: return 0L
        val site = sites[host] ?: return 0L
        val now = System.currentTimeMillis()
        val pace = paceOf(site, now)
        if (pace <= 0L) return 0L
        return synchronized(site) {
            val at = maxOf(now, site.nextAt)
            site.nextAt = at + pace
            at - now
        }
    }

    /** Waits for the site's pace (suspending); used by the browser-page requests */
    suspend fun paceSuspend(url: String) {
        val wait = reserveTurn(url)
        if (wait > 0) kotlinx.coroutines.delay(wait)
    }

    /** The image limit of the site now, or null for none */
    private fun imageGate(host: String): Semaphore? {
        val site = sites[host] ?: return null
        val limit = coverLimitOf(site, System.currentTimeMillis())
        if (limit <= 0) return null
        return synchronized(site) {
            if (site.images == null || site.imagesLimit != limit) {
                site.images = Semaphore(limit)
                site.imagesLimit = limit
            }
            site.images
        }
    }

    fun isImageRequest(request: Request): Boolean {
        val path = request.url.encodedPath.lowercase()
        if (IMAGE_ENDINGS.any { path.endsWith(it) }) return true
        val accept = request.header("Accept").orEmpty()
        return accept.startsWith("image/")
    }

    // --- For interceptors --------------------------------------------------------------------------------------

    private val callStart = ThreadLocal<Long?>()

    /** How long an OkHttp call may still wait here: its call timeout minus what it used (10 min when none) */
    fun budgetOf(chain: Interceptor.Chain): Long {
        val timeoutMs = try {
            TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos())
        } catch (_: Exception) {
            0L
        }
        if (timeoutMs <= 0L) return MAX_WAIT_MS
        val used = callStart.get()?.let { System.currentTimeMillis() - it } ?: 0L
        return (timeoutMs - used - BUDGET_MARGIN_MS).coerceAtLeast(0L)
    }

    /**
     * For an interceptor that got a check it can't pass: registers it and waits for the user (as long as the call
     * allows), then sends [request] once more with the user's session. Images and calls with no time left only
     * register and fail. [stillBlocked] tells whether a reply is still the check.
     */
    fun waitAndRetry(chain: Interceptor.Chain, request: Request, stillBlocked: (Response) -> Boolean): Response {
        val url = request.url.toString()
        val host = hostOf(url) ?: url
        val userAgent = request.header("User-Agent")
        val sourceId = request.tag(SourceTag::class.java)?.id
        if (isImageRequest(request)) {
            register(url, userAgent, sourceId)
            throw waitingError(host)
        }
        while (true) {
            val budget = budgetOf(chain)
            if (budget <= 0L) {
                register(url, userAgent, sourceId)
                throw waitingError(host)
            }
            val outcome = awaitBlocking(url, userAgent, sourceId, budget) { chain.call().isCanceled() }
            when (outcome) {
                Outcome.PASSED, Outcome.CLOSED -> {
                    val retry = chain.proceed(withSession(request))
                    if (!stillBlocked(retry)) {
                        log("$host: sent again with your session -> ${retry.code}")
                        return retry
                    }
                    retry.close()
                    // A pass that didn't carry over is reported, not tried again and again
                    if (outcome == Outcome.PASSED) throw notSolvedError(host)
                }
                Outcome.EXPIRED -> throw notSolvedError(host)
                Outcome.OUT_OF_TIME -> throw waitingError(host)
                Outcome.CANCELED -> throw IOException("Canceled")
            }
        }
    }

    /** [request] with the user agent of the user's pass on its site, when there is one */
    fun withSession(request: Request): Request {
        val userAgent = sessionUserAgent(request.url.toString()) ?: return request
        if (request.header("User-Agent") == userAgent) return request
        return request.newBuilder().header("User-Agent", userAgent).build()
    }

    fun waitingError(host: String) = SiteBlockedException(
        BotCheckReporter.Kind.BOT_CHECK,
        "$host is waiting for a bot check: solve it to continue",
    )

    fun notSolvedError(host: String) = SiteBlockedException(
        BotCheckReporter.Kind.BOT_CHECK,
        "Bot check not solved for $host: open it in WebView, pass the check, then try again",
    )

    /**
     * In every client: the site's pace (page requests one at a time, images a few at a time) and "too many
     * requests" (the site's Retry-After is waited out, then the request is sent once more).
     */
    class PaceInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val previousStart = callStart.get()
            if (previousStart == null) callStart.set(System.currentTimeMillis())
            try {
                val request = chain.request()
                val host = hostOf(request.url.toString())
                val image = isImageRequest(request)
                val gate = if (image && host != null) imageGate(host) else null
                if (gate != null) {
                    if (!acquire(gate, chain)) throw IOException("Canceled")
                } else if (!image) {
                    sleep(reserveTurn(request.url.toString()), chain)
                }
                var released = false
                val release = {
                    if (gate != null && !released) {
                        released = true
                        gate.release()
                    }
                }
                try {
                    var response = chain.proceed(request)
                    if (response.code == 429 && host != null) {
                        markSlow(host)
                        val waitMs = retryAfterMs(response) ?: DEFAULT_PACE_MS
                        if (waitMs <= minOf(MAX_RETRY_AFTER_MS, budgetOf(chain))) {
                            log("$host: too many requests, waiting ${waitMs / 1000} s as asked, then once more")
                            response.close()
                            sleep(waitMs, chain)
                            response = chain.proceed(request)
                        }
                    }
                    if (gate == null) return response
                    // The image's turn ends when its bytes were read (or it failed)
                    return response.newBuilder()
                        .body(CountingBody(response.body, onBytes = {}, onClose = { release() }))
                        .build()
                } catch (e: Throwable) {
                    release()
                    throw e
                }
            } finally {
                if (previousStart == null) callStart.remove()
            }
        }

        private fun sleep(ms: Long, chain: Interceptor.Chain) {
            var left = ms
            while (left > 0) {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                val step = minOf(left, POLL_MS)
                try {
                    Thread.sleep(step)
                } catch (_: InterruptedException) {
                    throw IOException("Canceled")
                }
                left -= step
            }
        }

        private fun acquire(gate: Semaphore, chain: Interceptor.Chain): Boolean {
            while (true) {
                if (chain.call().isCanceled()) return false
                try {
                    if (gate.tryAcquire(POLL_MS, TimeUnit.MILLISECONDS)) return true
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
    }

    /** The reply's Retry-After in ms (seconds or a date), or null */
    fun retryAfterMs(response: Response): Long? {
        val value = response.header("Retry-After")?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0L) }
        return try {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - System.currentTimeMillis()).coerceAtLeast(0L)
        } catch (_: Exception) {
            null
        }
    }

    private fun log(text: String) {
        try {
            logger?.invoke(text) ?: android.util.Log.i("BotCheck", text)
        } catch (_: Exception) {
        }
    }

    const val MAX_WAIT_MS = 10 * 60_000L
    const val SLOW_FOR_MS = 10 * 60_000L
    const val DEFAULT_PACE_MS = 1_500L
    const val DEFAULT_COVER_LIMIT = 4
    private const val MAX_PACE_MS = 60_000L
    private const val MAX_COVER_LIMIT = 16
    private const val MAX_RETRY_AFTER_MS = 60_000L
    private const val BUDGET_MARGIN_MS = 5_000L
    private const val POLL_MS = 250L
    private val IMAGE_ENDINGS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp", ".jxl", ".heic")
}
