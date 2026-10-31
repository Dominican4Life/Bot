package eu.kanade.tachiyomi.data.notification

import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.network.BotCheckReporter
import eu.kanade.tachiyomi.network.SiteBlockedException
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.DisabledSources
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
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
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

    /**
     * When the app opens: the sources of your library titles, once the sources are loaded.
     *
     * KMK (r95): waits until the first page is drawn ([ready], capped at [READY_WAIT_MS]) so the home-page requests
     * don't compete with the first covers, and skips sources that passed a check in the last [SKIP_IF_PASSED_MS]
     * (remembered across starts in [passedAt]). A source that needed attention is checked again on every start.
     */
    fun checkLibrarySources(ready: () -> Boolean = { true }) {
        if (startedOnOpen) return
        startedOnOpen = true
        scope.launch {
            runCatching { Injekt.get<SourceManager>().isInitialized.first { it } }
            val waitStart = System.currentTimeMillis()
            while (!ready() && System.currentTimeMillis() - waitStart < READY_WAIT_MS) delay(READY_POLL_MS)
            // Anime sources load a little after manga sources
            delay(ANIME_SOURCES_DELAY_MS)
            val manga = runCatching { Injekt.get<GetFavorites>().await().map { it.source } }
                .getOrDefault(emptyList())
            val anime = runCatching { Injekt.get<AnimeRepository>().getFavorites().map { it.source } }
                .getOrDefault(emptyList())
            // Turned-off sources are left alone
            val all = (manga + anime).toSet() - DisabledSources.ids()
            val now = System.currentTimeMillis()
            val passed = readPassedAt().filterValues { now - it < SKIP_IF_PASSED_MS }
            val ids = all - passed.keys
            val skippedNote = if (passed.isEmpty()) "" else " (${passed.size} passed within ${SKIP_IF_PASSED_H} h)"
            logcat(TAG) { "Checking ${ids.size} library sources for bot checks$skippedNote" }
            val semaphore = Semaphore(MAX_CHECKS_AT_ONCE)
            val fine = coroutineScope {
                ids.map { id -> async { semaphore.withPermit { id.takeIf { checkAndNotify(id) } } } }.awaitAll()
            }.filterNotNull()
            // One write per start: the sources that passed now, plus the ones still inside the window
            val kept = passed.filterKeys { it in all } + fine.associateWith { now }
            runCatching { passedAt.set(kept.map { (id, at) -> "$id|$at" }.toSet()) }
        }
    }

    private val passedAt by lazy {
        Injekt.get<PreferenceStore>().getStringSet(Preference.appStateKey("source_check_passed"))
    }

    private fun readPassedAt(): Map<Long, Long> = runCatching {
        passedAt.get().mapNotNull { entry ->
            val id = entry.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            val at = entry.substringAfter('|', "").toLongOrNull() ?: return@mapNotNull null
            id to at
        }.toMap()
    }.getOrDefault(emptyMap())

    /** A cover of [sourceId] failed to load: its source is checked, unless it was checked a short while ago */
    fun onCoverFailed(sourceId: Long) {
        if (DisabledSources.isDisabled(sourceId)) return
        val now = System.currentTimeMillis()
        val last = lastChecked[sourceId]
        if (last != null && now - last < RECHECK_AFTER_MS) return
        lastChecked[sourceId] = now
        scope.launch { checkAndNotify(sourceId) }
    }

    /** After WebView closes: the source is checked again, and its notification clears when it's fine */
    fun recheck(sourceId: Long) {
        if (DisabledSources.isDisabled(sourceId)) return
        scope.launch {
            val site = BotCheckNotifier.siteInfo(sourceId) ?: return@launch
            when (val kind = check(sourceId)) {
                null -> BotCheckNotifier.passed(sourceId)
                else -> BotCheckNotifier.onReport(site.baseUrl, kind, sourceId, force = true)
            }
        }
    }

    /** Returns true when the source is fine (or couldn't be checked), false when it needs attention */
    private suspend fun checkAndNotify(sourceId: Long): Boolean {
        lastChecked[sourceId] = System.currentTimeMillis()
        val site = BotCheckNotifier.siteInfo(sourceId) ?: return true
        val kind = check(sourceId) ?: return true
        logcat(TAG, LogPriority.WARN) { "${site.name} needs attention: $kind" }
        BotCheckNotifier.onReport(site.baseUrl, kind, sourceId, force = false)
        return false
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
    private const val READY_WAIT_MS = 20_000L
    private const val READY_POLL_MS = 250L
    /** A source that passed its check this recently isn't asked again on the next starts */
    private const val SKIP_IF_PASSED_H = 8
    private const val SKIP_IF_PASSED_MS = SKIP_IF_PASSED_H * 3_600_000L
    private const val RECHECK_AFTER_MS = 5 * 60_000L

    private val BOT_CHECK_ERROR = Regex("cloudflare|bot check|captcha|challenge", RegexOption.IGNORE_CASE)
}
