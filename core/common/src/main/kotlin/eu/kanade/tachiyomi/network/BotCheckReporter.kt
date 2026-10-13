package eu.kanade.tachiyomi.network

/**
 * KMK: Tells the app when a site asked for a bot check (like Cloudflare's) that couldn't be passed by itself, so
 * it can show a notification to open the site in WebView. The app sets [listener] when it starts.
 */
object BotCheckReporter {
    @Volatile
    var listener: ((url: String) -> Unit)? = null

    fun report(url: String) {
        try {
            listener?.invoke(url)
        } catch (_: Exception) {
            // A notification problem must never break the request that found the bot check
        }
    }
}
