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

    private const val PEEK_BYTES = 64L * 1024

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
 */
class BotCheckInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful) {
            BotCheckReporter.classify(response)?.let { BotCheckReporter.report(request.url.toString(), it) }
        }
        return response
    }
}
