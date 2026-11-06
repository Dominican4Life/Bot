package eu.kanade.tachiyomi.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * KMK: Tells the app when a site asked for a bot check (like Cloudflare's) the app couldn't pass by itself, or
 * answered "too many requests", so it can show a notification for that source. The app sets [listener] when it
 * starts.
 */
object BotCheckReporter {

    enum class Kind { BOT_CHECK, TOO_MANY_REQUESTS }

    /** url, what happened, and the source it's for when the caller knows it */
    @Volatile
    var listener: ((url: String, kind: Kind, sourceId: Long?) -> Unit)? = null

    fun report(url: String, kind: Kind = Kind.BOT_CHECK, sourceId: Long? = null) {
        try {
            listener?.invoke(url, kind, sourceId)
        } catch (_: Exception) {
            // A notification problem must never break the request that found the bot check
        }
    }

    /** What a failed reply means: a bot check, too many requests, or null for any other error */
    fun classify(response: Response): Kind? {
        val code = response.code
        if (code == 429) return Kind.TOO_MANY_REQUESTS
        if (code != 403 && code != 503) return null
        if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) return Kind.BOT_CHECK
        val server = response.header("Server").orEmpty().lowercase()
        if ("cloudflare" !in server && "ddos-guard" !in server) return null
        val body = try {
            response.peekBody(PEEK_BYTES).string()
        } catch (_: Exception) {
            return null
        }
        return if (CHALLENGE.containsMatchIn(body)) Kind.BOT_CHECK else null
    }

    /**
     * KMK (r134): some sites answer a bot check with a normal "200 OK" page, so the list only got an empty or broken
     * page and never asked. True for an HTML reply that is a check page (Cloudflare / DDoS-Guard markers). Only
     * looked at when the reply says it came through one of them, and only the first bytes are read.
     */
    fun isCheckPage(response: Response): Boolean {
        if (!response.isSuccessful) return false
        if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
        val type = response.header("Content-Type").orEmpty().lowercase()
        if ("html" !in type) return false
        val server = response.header("Server").orEmpty().lowercase()
        if ("cloudflare" !in server && "ddos-guard" !in server) return false
        val body = try {
            response.peekBody(SMALL_PEEK_BYTES).string()
        } catch (_: Exception) {
            return false
        }
        return CHECK_PAGE.containsMatchIn(body)
    }

    private const val PEEK_BYTES = 64L * 1024
    private const val SMALL_PEEK_BYTES = 16L * 1024

    /** Markers only a check page has (not "Just a moment", which a normal page could contain) */
    private val CHECK_PAGE = Regex(
        "cf_chl_opt|/cdn-cgi/challenge-platform/|challenge-error-title|ddos-guard/js-challenge|__ddg1_",
        RegexOption.IGNORE_CASE,
    )

    private val CHALLENGE = Regex(
        "challenge-platform|cf-chl|cf_chl_opt|challenge-error-title|Just a moment|Attention Required|" +
            "Checking your browser|ddos-guard",
        RegexOption.IGNORE_CASE,
    )
}

/** KMK: A request stopped by a bot check or "too many requests", so callers can wait instead of failing */
class SiteBlockedException(val kind: BotCheckReporter.Kind, message: String) : IOException(message)

/**
 * KMK: Spots replies that are still a bot check or "too many requests" after the Cloudflare helper had its go,
 * and reports them. The reply itself is passed on unchanged.
 *
 * KMK (r116): a bot check here is never opened by itself any more: it's registered as waiting (BotCheckGate), the
 * user is asked (notification, Browse, Source Lab) and the request waits for their pass in WebView, then is sent
 * once more with that session. "Too many requests" is only reported here (the pace interceptor waits and retries).
 */
class BotCheckInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        // KMK (r134): a check page sent back as "200 OK" is a bot check too (asked for, never solved by the app)
        if (BotCheckReporter.isCheckPage(response)) {
            response.close()
            return BotCheckGate.waitAndRetry(chain, request) {
                BotCheckReporter.isCheckPage(it) ||
                    (!it.isSuccessful && BotCheckReporter.classify(it) == BotCheckReporter.Kind.BOT_CHECK)
            }
        }
        if (!response.isSuccessful) {
            val kind = BotCheckReporter.classify(response) ?: return response
            if (kind == BotCheckReporter.Kind.BOT_CHECK) {
                response.close()
                return BotCheckGate.waitAndRetry(chain, request) {
                    (!it.isSuccessful && BotCheckReporter.classify(it) == BotCheckReporter.Kind.BOT_CHECK) ||
                        BotCheckReporter.isCheckPage(it)
                }
            }
            BotCheckReporter.report(request.url.toString(), kind)
        }
        return response
    }
}
