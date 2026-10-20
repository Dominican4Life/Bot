package eu.kanade.tachiyomi.data.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.BotCheckCoordinator
import eu.kanade.tachiyomi.network.BotCheckReporter
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.DisabledSources
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.sumizuri.SumizuriAnimeSource
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Like Kototoro, a notification for each source that hit a bot check the app couldn't pass by itself (tap to
 * open the source in WebView and pass it), or that answered "too many requests". When several sources are
 * waiting, a summary at the top lists them all. A bot check drops off once the source is opened in WebView.
 */
object BotCheckNotifier {

    /** A source's name and home page */
    class SiteInfo(val id: Long, val name: String, val baseUrl: String)

    /** How to ask a source's site for something: its connection and headers */
    class Connection(val client: OkHttpClient, val headers: Headers)

    private data class Entry(
        val key: String,
        val name: String,
        val url: String,
        val sourceId: Long?,
        val kind: BotCheckReporter.Kind,
        val at: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    private lateinit var appContext: Context

    fun install(context: Context) {
        appContext = context.applicationContext
        BotCheckReporter.listener = { url, kind, sourceId -> onReport(url, kind, sourceId, force = false) }
        BotCheckCoordinator.opener = ::openInWebView
    }

    /**
     * KMK: Opens a source's bot check in WebView right away (it closes by itself once passed), when the app is on
     * screen and the setting is on. Only a source's own site is opened, not image servers and the like.
     */
    private fun openInWebView(url: String, userAgent: String?): Boolean {
        if (!::appContext.isInitialized) return false
        val enabled = runCatching { Injekt.get<NetworkPreferences>().autoOpenBotChecks().get() }.getOrDefault(false)
        if (!enabled) return false
        val onScreen = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!onScreen) return false
        val site = hostOf(url)?.let(::siteForHost) ?: return false
        if (DisabledSources.isDisabled(site.id)) return false
        val intent = WebViewActivity.newAutoCloseIntent(appContext, url, site.id, site.name, userAgent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return true
    }

    /** A source found to need a bot check (or to be asking for fewer requests); [force] shows it again right away */
    fun onReport(url: String, kind: BotCheckReporter.Kind, sourceId: Long?, force: Boolean) {
        if (!::appContext.isInitialized) return
        val site = sourceId?.let(::siteInfo) ?: hostOf(url)?.let(::siteForHost)
        // Image servers and other addresses that aren't a source of yours are left out, and so are turned-off sources
        if (site == null || DisabledSources.isDisabled(site.id)) return
        val key = site.id.toString()
        val now = System.currentTimeMillis()
        synchronized(entries) {
            val shown = entries[key]
            // Already showing: don't post it again for every failed request
            if (!force && shown != null && shown.kind == kind && now - shown.at < REPEAT_AFTER_MS) return
            // A bot check matters more than "too many requests": keep it if both happen
            val newKind = if (shown?.kind == BotCheckReporter.Kind.BOT_CHECK && !force) shown.kind else kind
            entries[key] = Entry(
                key = key,
                name = site.name,
                url = site.baseUrl.ifBlank { originOf(url) ?: url },
                sourceId = site.id,
                kind = newKind,
                at = now,
            )
        }
        show()
    }

    /** Called when a site is opened in WebView: its notification goes away while the check is being passed */
    fun clear(url: String, sourceId: Long?) {
        if (!::appContext.isInitialized) return
        val key = (sourceId?.takeIf { it != 0L } ?: hostOf(url)?.let(::siteForHost)?.id)?.toString() ?: return
        val removed = synchronized(entries) { entries.remove(key) } ?: return
        NotificationManagerCompat.from(appContext).cancel(TAG, removed.key.hashCode())
        show()
    }

    /** Takes a source off the list, after it passed its check */
    fun passed(sourceId: Long) {
        if (!::appContext.isInitialized) return
        val removed = synchronized(entries) { entries.remove(sourceId.toString()) } ?: return
        NotificationManagerCompat.from(appContext).cancel(TAG, removed.key.hashCode())
        show()
    }

    @SuppressLint("MissingPermission") // checked at the top
    private fun show() {
        val context = appContext
        if (!canNotify(context)) return
        val manager = NotificationManagerCompat.from(context)
        val list = synchronized(entries) { entries.values.toList() }

        list.forEach { entry ->
            val botCheck = entry.kind == BotCheckReporter.Kind.BOT_CHECK
            val notification = context.notificationBuilder(Notifications.CHANNEL_BOT_CHECK) {
                setSmallIcon(R.drawable.ic_warning_white_24dp)
                if (botCheck) {
                    // Like Kototoro: "Cloudflare required" / "NHentai requires Cloudflare to be resolved…"
                    val text = context.stringResource(KMR.strings.bot_check_text, entry.name)
                    setContentTitle(context.stringResource(KMR.strings.bot_check_title))
                    setContentText(text)
                    setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    val intent = WebViewActivity.newIntent(context, entry.url, entry.sourceId, entry.name)
                    setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            entry.key.hashCode(),
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                } else {
                    setContentTitle(context.stringResource(KMR.strings.too_many_requests_title, entry.name))
                    setContentText(context.stringResource(KMR.strings.too_many_requests_text))
                }
                setAutoCancel(true)
                setOnlyAlertOnce(true)
                setGroup(Notifications.GROUP_BOT_CHECK)
                priority = NotificationCompat.PRIORITY_DEFAULT
            }.build()
            manager.notify(TAG, entry.key.hashCode(), notification)
        }

        if (list.size < 2) {
            manager.cancel(Notifications.ID_BOT_CHECK_SUMMARY)
            return
        }
        val title = context.stringResource(KMR.strings.bot_check_summary, list.size)
        val summary = context.notificationBuilder(Notifications.CHANNEL_BOT_CHECK) {
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setContentTitle(title)
            setContentText(list.joinToString { it.name })
            setStyle(
                NotificationCompat.InboxStyle()
                    .setBigContentTitle(title)
                    .also { style -> list.forEach { style.addLine(it.name) } },
            )
            setGroup(Notifications.GROUP_BOT_CHECK)
            setGroupSummary(true)
            setOnlyAlertOnce(true)
            setAutoCancel(true)
        }.build()
        manager.notify(Notifications.ID_BOT_CHECK_SUMMARY, summary)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            PermissionChecker.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PermissionChecker.PERMISSION_GRANTED

    /** A manga or anime source with a web address, by id */
    fun siteInfo(sourceId: Long): SiteInfo? {
        val manga = runCatching { Injekt.get<SourceManager>().get(sourceId) }.getOrNull()
        if (manga is HttpSource) return SiteInfo(manga.id, manga.name, manga.baseUrl)
        return when (val anime = runCatching { Injekt.get<AnimeSourceManager>().get(sourceId) }.getOrNull()) {
            is AnimeHttpSource -> SiteInfo(anime.id, anime.name, anime.baseUrl)
            is SumizuriAnimeSource -> SiteInfo(anime.id, anime.name, anime.baseUrl)
            else -> null
        }
    }

    /** The connection a source uses for its site */
    fun connection(sourceId: Long): Connection? = runCatching {
        when (val manga = Injekt.get<SourceManager>().get(sourceId)) {
            is HttpSource -> return@runCatching Connection(manga.client, manga.headers)
            else -> Unit
        }
        when (val anime = Injekt.get<AnimeSourceManager>().get(sourceId)) {
            is AnimeHttpSource -> Connection(anime.client, anime.headers)
            is SumizuriAnimeSource -> Connection(anime.client, anime.headers)
            else -> null
        }
    }.getOrNull()

    /** Site → source id, so failed requests don't look through every source each time */
    private val hostCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The source a site belongs to */
    private fun siteForHost(host: String): SiteInfo? {
        hostCache[host]?.let { id -> siteInfo(id)?.let { return it } }
        val manga = runCatching {
            Injekt.get<SourceManager>().getOnlineSources().map { SiteInfo(it.id, it.name, it.baseUrl) }
        }.getOrDefault(emptyList())
        val anime = runCatching {
            Injekt.get<AnimeSourceManager>().getCatalogueSources().mapNotNull {
                when (it) {
                    is AnimeHttpSource -> SiteInfo(it.id, it.name, it.baseUrl)
                    is SumizuriAnimeSource -> SiteInfo(it.id, it.name, it.baseUrl)
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
        val all = manga + anime
        val found = all.firstOrNull { hostOf(it.baseUrl) == host }
            // A source on a sub-site (api.example.com) or the main site of one
            ?: all.firstOrNull { site ->
                val other = hostOf(site.baseUrl) ?: return@firstOrNull false
                host.endsWith(".$other") || other.endsWith(".$host")
            }
        // Only matches are kept: extensions may still be loading when a miss happens
        if (found != null) hostCache[host] = found.id
        return found
    }

    private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host?.removePrefix("www.")

    private fun originOf(url: String): String? = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" }

    private const val TAG = "bot_check"
    private const val REPEAT_AFTER_MS = 10 * 60 * 1000L
}
