package eu.kanade.tachiyomi.network

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * KMK: Bot checks the app can't pass by itself (Cloudflare's "Verify you are human" box) are opened in WebView
 * right away when the app is on screen, instead of only a notification. WebView closes by itself once the check is
 * passed, and whoever was waiting tries again.
 *
 * Only one site is opened at a time, and each site at most once per [COOLDOWN_MS], so it can never loop. When it
 * can't open (app in the background, setting off, another site already open), callers fail as before.
 */
object BotCheckCoordinator {

    /**
     * Set by the app: opens [url] in WebView in auto-close mode, with [userAgent] when known. Returns false when it
     * didn't open it (turned off, app not on screen, not a source's site).
     */
    @Volatile
    var opener: ((url: String, userAgent: String?) -> Boolean)? = null

    private class Pending(val result: CompletableDeferred<Boolean>, val at: Long)

    private val pending = HashMap<String, Pending>()
    private val lastOpened = HashMap<String, Long>()

    /** Whether WebView is open now for this site, waiting for its check to be passed */
    fun isWaiting(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return synchronized(this) { pending.containsKey(host) }
    }

    /**
     * Opens the check and blocks until it's passed (true) or not (false). Never waits on the main thread.
     * [isCanceled] stops waiting early, e.g. when the request it's for was given up.
     */
    fun awaitBlocking(url: String, userAgent: String?, isCanceled: () -> Boolean = { false }): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val result = request(url, userAgent) ?: return false
        return runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                while (!result.isCompleted) {
                    if (isCanceled()) return@withTimeoutOrNull false
                    delay(POLL_MS)
                }
                result.await()
            } ?: false
        }
    }

    /** Opens the check and waits until it's passed (true) or not (false) */
    suspend fun await(url: String, userAgent: String?): Boolean {
        val result = request(url, userAgent) ?: return false
        return withTimeoutOrNull(TIMEOUT_MS) { result.await() } ?: false
    }

    /** Called by WebView when it closes: [passed] tells whether the site's check was passed */
    fun finished(url: String, passed: Boolean) {
        val host = hostOf(url) ?: return
        val done = synchronized(this) { pending.remove(host) } ?: return
        done.result.complete(passed)
    }

    private fun request(url: String, userAgent: String?): CompletableDeferred<Boolean>? {
        val host = hostOf(url) ?: return null
        val now = System.currentTimeMillis()
        val result = CompletableDeferred<Boolean>()
        synchronized(this) {
            // Someone already waits on this site: wait with them
            pending[host]?.let { shown ->
                if (now - shown.at < STALE_MS) return shown.result
                // WebView never told us it closed: give up on it
                pending.remove(host)?.result?.complete(false)
            }
            // One site at a time
            pending.entries.removeAll { (_, other) ->
                (now - other.at >= STALE_MS).also { if (it) other.result.complete(false) }
            }
            if (pending.isNotEmpty()) return null
            // Never open the same site again and again
            if (now - (lastOpened[host] ?: 0L) < COOLDOWN_MS) return null
            pending[host] = Pending(result, now)
            lastOpened[host] = now
        }
        val opened = try {
            opener?.invoke(url, userAgent) == true
        } catch (_: Exception) {
            false
        }
        if (!opened) {
            synchronized(this) {
                pending.remove(host)
                // It wasn't shown, so it may be shown next time
                lastOpened.remove(host)
            }
            result.complete(false)
            return null
        }
        return result
    }

    private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host?.removePrefix("www.")

    private const val TIMEOUT_MS = 2 * 60_000L
    private const val STALE_MS = TIMEOUT_MS + 60_000L
    private const val COOLDOWN_MS = 2 * 60_000L
    private const val POLL_MS = 250L
}
