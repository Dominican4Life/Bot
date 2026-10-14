package eu.kanade.tachiyomi.data.notification

import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.network.BotCheckReporter
import eu.kanade.tachiyomi.network.SiteBlockedException
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import logcat.logcat
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: Checks whether sources need a bot check (Cloudflare) passed, and shows the "Cloudflare required" notification
 * when one does. Nothing is ever blocked: this only tells you. Sources are checked:
 * - when the app opens: every source your library titles come from, 8 at a time
 * - when a cover fails to load: that cover's source, at most once every few minutes
 * - when WebView closes after passing a check: that source, so its notification clears once it's fine
 */
object SourceStatusCheck {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** When each source was last checked, so failing covers don't each send a check */
    private val lastChecked = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var startedOnOpen = false

    /** When the app opens: the sources of your library titles, once the sources are loaded */
    fun checkLibrarySources() {
        if (startedOnOpen) return
        startedOnOpen = true
        scope.launch {
            runCatching { Injekt.get<SourceManager>().isInitialized.first { it } }
            // Anime sources load a little after manga sources
            delay(ANIME_SOURCES_DELAY_MS)
            val manga = runCatching { Injekt.get<GetFavorites>().await().map { it.source } }
                .getOrDefault(emptyList())
            val anime = runCatching { Injekt.get<AnimeRepository>().getFavorites().map { it.source } }
                .getOrDefault(emptyList())
            val ids = (manga + anime).toSet()
            logcat(TAG) { "Checking ${ids.size} library sources for bot checks" }
            val semaphore = Semaphore(MAX_CHECKS_AT_ONCE)
            coroutineScope {
                ids.map { id -> async { semaphore.withPermit { checkAndNotify(id) } } }.awaitAll()
            }
        }
    }

    /** A cover of [sourceId] failed to load: its source is checked, unless it was checked a short while ago */
    fun onCoverFailed(sourceId: Long) {
        val now = System.currentTimeMillis()
        val last = lastChecked[sourceId]
        if (last != null && now - last < RECHECK_AFTER_MS) return
        lastChecked[sourceId] = now
        scope.launch { checkAndNotify(sourceId) }
    }

    /** After WebView closes: the source is checked again, and its notification clears when it's fine */
    fun recheck(sourceId: Long) {
        scope.launch {
            val site = BotCheckNotifier.siteInfo(sourceId) ?: return@launch
            when (val kind = check(sourceId)) {
                null -> BotCheckNotifier.passed(sourceId)
                else -> BotCheckNotifier.onReport(site.baseUrl, kind, sourceId, force = true)
            }
        }
    }

    private suspend fun checkAndNotify(sourceId: Long) {
        lastChecked[sourceId] = System.currentTimeMillis()
        val site = BotCheckNotifier.siteInfo(sourceId) ?: return
        val kind = check(sourceId) ?: return
        logcat(TAG, LogPriority.WARN) { "${site.name} needs attention: $kind" }
        BotCheckNotifier.onReport(site.baseUrl, kind, sourceId, force = false)
    }

    /** One request to the source's home page: a bot check, too many requests, or null when it's fine */
    private suspend fun check(sourceId: Long): BotCheckReporter.Kind? {
        val site = BotCheckNotifier.siteInfo(sourceId) ?: return null
        if (site.baseUrl.toHttpUrlOrNull() == null) return null
        val connection = BotCheckNotifier.connection(sourceId) ?: return null
        return try {
            val request = Request.Builder().url(site.baseUrl).headers(connection.headers).build()
            connection.client.newCall(request).await().use { response ->
                if (response.isSuccessful) null else BotCheckReporter.classify(response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The Cloudflare helper gives up with an error rather than a reply
            if (isBotCheckError(e)) BotCheckReporter.Kind.BOT_CHECK else null
        }
    }

    private fun isBotCheckError(e: Throwable): Boolean {
        if (e is SiteBlockedException) return e.kind == BotCheckReporter.Kind.BOT_CHECK
        val text = generateSequence(e) { it.cause }.take(5).joinToString(" ") { it.message.orEmpty() }
        return BOT_CHECK_ERROR.containsMatchIn(text)
    }

    private const val TAG = "source-check"
    private const val MAX_CHECKS_AT_ONCE = 8
    private const val ANIME_SOURCES_DELAY_MS = 5_000L
    private const val RECHECK_AFTER_MS = 5 * 60_000L

    private val BOT_CHECK_ERROR = Regex("cloudflare|bot check|captcha|challenge", RegexOption.IGNORE_CASE)
}
