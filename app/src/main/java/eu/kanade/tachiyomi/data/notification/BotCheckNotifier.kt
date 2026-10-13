package eu.kanade.tachiyomi.data.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.PermissionChecker
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.BotCheckReporter
import eu.kanade.tachiyomi.sumizuri.SumizuriAnimeSource
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Like Kototoro, a notification for each source that hit a bot check the app couldn't pass by itself. Tapping
 * it opens the source in WebView to pass the check. When several sources are waiting, a summary at the top lists
 * them all. A source drops off once it's opened in WebView.
 */
object BotCheckNotifier {

    private data class Entry(val host: String, val name: String, val url: String, val sourceId: Long?, val at: Long)

    private val entries = LinkedHashMap<String, Entry>()

    private lateinit var appContext: Context

    fun install(context: Context) {
        appContext = context.applicationContext
        BotCheckReporter.listener = ::onBotCheck
    }

    private fun onBotCheck(url: String) {
        val host = hostOf(url) ?: return
        val now = System.currentTimeMillis()
        synchronized(entries) {
            // Already showing for this site: don't post it again for every failed request
            val shown = entries[host]
            if (shown != null && now - shown.at < REPEAT_AFTER_MS) return
            val source = findSource(host)
            entries[host] = Entry(
                host = host,
                name = source?.second ?: host,
                url = source?.third?.takeIf { it.isNotBlank() } ?: originOf(url) ?: url,
                sourceId = source?.first,
                at = now,
            )
        }
        show()
    }

    /** Called when a site is opened in WebView: its notification goes away */
    fun clear(url: String) {
        if (!::appContext.isInitialized) return
        val host = hostOf(url) ?: return
        val removed = synchronized(entries) { entries.remove(host) } ?: return
        NotificationManagerCompat.from(appContext).cancel(TAG, removed.host.hashCode())
        show()
    }

    @SuppressLint("MissingPermission") // checked at the top
    private fun show() {
        val context = appContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            PermissionChecker.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PermissionChecker.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        val list = synchronized(entries) { entries.values.toList() }

        list.forEach { entry ->
            val intent = WebViewActivity.newIntent(context, entry.url, entry.sourceId, entry.name)
            val pending = PendingIntent.getActivity(
                context,
                entry.host.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = context.notificationBuilder(Notifications.CHANNEL_BOT_CHECK) {
                setSmallIcon(R.drawable.ic_warning_white_24dp)
                setContentTitle(context.stringResource(KMR.strings.bot_check_title, entry.name))
                setContentText(context.stringResource(KMR.strings.bot_check_text))
                setContentIntent(pending)
                setAutoCancel(true)
                setOnlyAlertOnce(true)
                setGroup(Notifications.GROUP_BOT_CHECK)
                priority = NotificationCompat.PRIORITY_DEFAULT
            }.build()
            manager.notify(TAG, entry.host.hashCode(), notification)
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

    /** The source a site belongs to: id, name and its home page */
    private fun findSource(host: String): Triple<Long, String, String>? {
        val manga = runCatching {
            Injekt.get<SourceManager>().getOnlineSources().map { Triple(it.id, it.name, it.baseUrl) }
        }.getOrDefault(emptyList())
        val anime = runCatching {
            Injekt.get<AnimeSourceManager>().getCatalogueSources().mapNotNull {
                when (it) {
                    is AnimeHttpSource -> Triple(it.id, it.name, it.baseUrl)
                    is SumizuriAnimeSource -> Triple(it.id, it.name, it.baseUrl)
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
        val all = manga + anime
        return all.firstOrNull { hostOf(it.third) == host }
            // A source on a sub-site (api.example.com) or the main site of one
            ?: all.firstOrNull { source ->
                val other = hostOf(source.third) ?: return@firstOrNull false
                host.endsWith(".$other") || other.endsWith(".$host")
            }
    }

    private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host?.removePrefix("www.")

    private fun originOf(url: String): String? = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" }

    private const val TAG = "bot_check"
    private const val REPEAT_AFTER_MS = 10 * 60 * 1000L
}
