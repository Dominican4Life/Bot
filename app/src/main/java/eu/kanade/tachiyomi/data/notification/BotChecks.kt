package eu.kanade.tachiyomi.data.notification

import android.app.Activity
import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.jssource.JsBrowserFetch
import eu.kanade.tachiyomi.network.BotCheckGate
import eu.kanade.tachiyomi.source.DisabledSources
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * KMK (r116): Bot checks of your sources, for the app's screens (Browse, Source Lab) and the notification.
 *
 * The app never solves, clicks, spoofs or bypasses a check. It notices one it can't get past ([BotCheckGate]),
 * shows it here and in a notification, and waits: [solve] opens the source's check page in WebView, where YOU pass
 * it; WebView closes by itself once it sees the pass, the waiting requests carry on with the session your pass made
 * (cookies + WebView's user agent), and the site is asked more slowly for a while ([session], [setPace]).
 */
object BotChecks {

    /** A source waiting for its check: [pending] requests wait for it, since [since] */
    data class Waiting(val sourceId: Long, val name: String, val url: String, val since: Long, val pending: Int)

    /**
     * A source's session: when you last passed its check, whether its requests go through a browser page on the
     * site, until when it's asked slowly (null = not now), and the pace used after a check (page requests every
     * [paceMs], [coverLimit] images at a time).
     */
    data class Session(
        val lastPassedAt: Long?,
        val viaBrowser: Boolean,
        val slowUntil: Long?,
        val paceMs: Long,
        val coverLimit: Int,
    )

    private val _waiting = MutableStateFlow<Map<Long, Waiting>>(emptyMap())
    val waiting: StateFlow<Map<Long, Waiting>> = _waiting.asStateFlow()

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var expiryJob: Job? = null

    /** The user's own pace per source: id → (host, pace, covers); read once at start, kept in memory */
    private val paces = java.util.concurrent.ConcurrentHashMap<Long, Triple<String, Long, Int>>()

    /** Called once when the app starts (from [BotCheckNotifier.install]) */
    fun install(context: Context) {
        appContext = context.applicationContext
        BotCheckGate.logger = { runCatching { AppLog.add('I', "botcheck", it) } }
        BotCheckGate.listener = ::onGateChanged
        runCatching {
            prefs().all.forEach { (key, value) ->
                val id = key.toLongOrNull() ?: return@forEach
                val parts = (value as? String)?.split('|') ?: return@forEach
                val host = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@forEach
                val pace = parts.getOrNull(1)?.toLongOrNull() ?: return@forEach
                val covers = parts.getOrNull(2)?.toIntOrNull() ?: return@forEach
                paces[id] = Triple(host, pace, covers)
                BotCheckGate.setPace(host, pace, covers)
            }
        }
    }

    /** Opens the source's check page in WebView; you pass it there, and it closes by itself once it's passed */
    fun solve(context: Context, sourceId: Long) {
        val site = BotCheckNotifier.siteInfo(sourceId)
        val shown = _waiting.value[sourceId]
        val url = shown?.url ?: site?.baseUrl?.takeIf { it.isNotBlank() } ?: return
        val userAgent = BotCheckGate.waiting()
            .firstOrNull { it.host == BotCheckGate.hostOf(url) }
            ?.userAgent
        AppLog.add('I', "botcheck", "${site?.name ?: url}: opening its check in WebView for you to solve")
        val intent = WebViewActivity.newBotCheckIntent(context, url, sourceId, site?.name ?: shown?.name, userAgent)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** The source's session and pace (in memory only, safe on the main thread); null when it isn't a web source */
    fun session(sourceId: Long): Session? {
        val host = hostOf(sourceId) ?: return null
        val state = BotCheckGate.siteState(host)
        val origin = BotCheckNotifier.siteInfo(sourceId)?.baseUrl?.let(JsBrowserFetch::originOf)
        val pace = paces[sourceId]
        return Session(
            lastPassedAt = state?.lastPassedAt,
            viaBrowser = origin != null && origin in JsBrowserFetch.browserFirst,
            slowUntil = state?.slowUntil,
            paceMs = state?.paceMs ?: pace?.second?.takeIf { it > 0 } ?: BotCheckGate.DEFAULT_PACE_MS,
            coverLimit = state?.coverLimit ?: pace?.third?.takeIf { it > 0 } ?: BotCheckGate.DEFAULT_COVER_LIMIT,
        )
    }

    /** "Ask less often": the pace used for the source's site after a check or a 429 (kept across starts) */
    fun setPace(sourceId: Long, paceMs: Long, coverLimit: Int) {
        val host = hostOf(sourceId) ?: return
        BotCheckGate.setPace(host, paceMs, coverLimit)
        val state = BotCheckGate.siteState(host)
        val pace = state?.userPaceMs ?: paceMs
        val covers = state?.userCoverLimit ?: coverLimit
        paces[sourceId] = Triple(host, pace, covers)
        if (!::appContext.isInitialized) return
        scope.launch {
            runCatching { prefs().edit().putString(sourceId.toString(), "$host|$pace|$covers").apply() }
        }
        AppLog.add('I', "botcheck", "$host: pace after a check set to $pace ms, $covers covers at a time")
    }

    /** WebView saw the check passed ([passed]) or closed without a sign; the waiting requests try again */
    internal fun webViewDone(url: String, sourceId: Long?, passed: Boolean, userAgent: String?) {
        val hosts = buildSet {
            BotCheckGate.hostOf(url)?.let(::add)
            sourceId?.let { id ->
                hostOf(id)?.let(::add)
                _waiting.value[id]?.url?.let(BotCheckGate::hostOf)?.let(::add)
                // Every site waiting for this source (an api. sub-site, say)
                BotCheckGate.waiting().filter { it.sourceId == id }.forEach { add(it.host) }
            }
        }
        BotCheckGate.finished(hosts, passed, userAgent)
        if (passed) sourceId?.let(BotCheckNotifier::passed)
    }

    private fun hostOf(sourceId: Long): String? =
        BotCheckNotifier.siteInfo(sourceId)?.baseUrl?.toHttpUrlOrNull()?.host?.removePrefix("www.")

    /** Site → source, so a wait that changes often doesn't look through every source each time */
    private val hostIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val hostMisses = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun sourceIdFor(host: String): Long? {
        hostIds[host]?.let { return it }
        val now = System.currentTimeMillis()
        if (hostMisses[host]?.let { now - it < 60_000L } == true) return null
        val id = BotCheckNotifier.sourceForHost(host)?.id
        if (id != null) hostIds[host] = id else hostMisses[host] = now
        return id
    }

    @Synchronized
    private fun onGateChanged(list: List<BotCheckGate.WaitInfo>) {
        if (!::appContext.isInitialized) return
        val before = _waiting.value
        val now = HashMap<Long, Waiting>()
        list.forEach { info ->
            val id = info.sourceId?.takeIf { it != 0L } ?: sourceIdFor(info.host) ?: return@forEach
            if (DisabledSources.isDisabled(id)) return@forEach
            val name = BotCheckNotifier.siteInfo(id)?.name ?: info.host
            val merged = now[id]?.let { it.copy(since = minOf(it.since, info.since), pending = it.pending + info.pending) }
                ?: Waiting(id, name, info.url, info.since, info.pending)
            now[id] = merged
        }
        _waiting.value = now
        // New ones: the notification (tap → WebView, you pass it)
        (now.keys - before.keys).forEach { id ->
            val shown = now.getValue(id)
            BotCheckNotifier.onReport(shown.url, eu.kanade.tachiyomi.network.BotCheckReporter.Kind.BOT_CHECK, id, force = false)
        }
        if (now.isNotEmpty()) watchExpiry()
    }

    /** Waits older than 10 min drop off the list even when no request asks again */
    private fun watchExpiry() {
        if (expiryJob?.isActive == true) return
        expiryJob = scope.launch {
            while (isActive) {
                delay(30_000L)
                onGateChanged(BotCheckGate.waiting())
                if (_waiting.value.isEmpty()) break
            }
        }
    }

    private fun prefs() = appContext.getSharedPreferences("bot_check_pace", Context.MODE_PRIVATE)
}
