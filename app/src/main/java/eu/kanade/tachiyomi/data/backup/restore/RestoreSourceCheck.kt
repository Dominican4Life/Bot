package eu.kanade.tachiyomi.data.backup.restore

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.library.RefreshMissingCovers
import eu.kanade.tachiyomi.data.notification.BotCheckNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.notification.SourceHold
import eu.kanade.tachiyomi.network.BotCheckReporter
import eu.kanade.tachiyomi.network.SiteBlockedException
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.PauseBetweenTitles
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.kmk.KMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger

/**
 * KMK: Before a restore, checks every source used in the backup for a bot check, all at the same time, so they can
 * be passed in WebView while the restore runs. Sources waiting for a check are held: no covers are asked from them
 * until the check is passed (or skipped). After the restore, missing covers load in the background, one title at a
 * time per source with the "Pause between titles" from Settings › Library.
 */
object RestoreSourceCheck {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var checksJob: Job? = null
    private var coversJob: Job? = null
    private var watchJob: Job? = null

    /** Sources of this restore waiting for a bot check */
    private val waiting = MutableStateFlow<Set<Long>>(emptySet())

    /** Sources whose covers were skipped from the waiting notification */
    private val skipped = MutableStateFlow<Set<Long>>(emptySet())

    private lateinit var app: Context

    /** Checks every source in the backup, all at once, before the restore starts */
    fun start(context: Context, sourceIds: Set<Long>) {
        app = context.applicationContext
        checksJob?.cancel()
        coversJob?.cancel()
        waiting.value.forEach(SourceHold::release)
        waiting.value = emptySet()
        skipped.value = emptySet()
        watchWaiting()
        checksJob = scope.launch {
            val semaphore = Semaphore(MAX_CHECKS_AT_ONCE)
            coroutineScope {
                sourceIds.map { id -> async { semaphore.withPermit { checkAndHold(id) } } }.awaitAll()
            }
        }
    }

    /** After the restore: the missing covers of the backup's sources load in the background, paced */
    fun afterRestore(sourceIds: Set<Long>) {
        if (!::app.isInitialized) return
        coversJob?.cancel()
        coversJob = scope.launch { loadCovers(sourceIds) }
    }

    /** After WebView closes: a source that was waiting is checked again, and released when it passes */
    fun recheck(sourceId: Long) {
        if (!::app.isInitialized) return
        if (sourceId !in waiting.value && !SourceHold.isHeld(sourceId)) return
        scope.launch {
            val site = BotCheckNotifier.siteInfo(sourceId) ?: return@launch
            when (val kind = check(sourceId)) {
                BotCheckReporter.Kind.BOT_CHECK -> BotCheckNotifier.onReport(site.baseUrl, kind, sourceId, force = true)
                else -> {
                    SourceHold.release(sourceId)
                    waiting.update { it - sourceId }
                    BotCheckNotifier.passed(sourceId)
                }
            }
        }
    }

    /** Stops waiting: the sources still waiting are let go, and their covers are skipped for now */
    fun skip() {
        val ids = waiting.value
        skipped.update { it + ids }
        ids.forEach(SourceHold::release)
        waiting.value = emptySet()
    }

    private suspend fun checkAndHold(sourceId: Long) {
        val site = BotCheckNotifier.siteInfo(sourceId) ?: return
        val kind = check(sourceId) ?: return
        if (kind == BotCheckReporter.Kind.BOT_CHECK) hold(sourceId)
        BotCheckNotifier.onReport(site.baseUrl, kind, sourceId, force = true)
    }

    private fun hold(sourceId: Long) {
        SourceHold.hold(sourceId)
        waiting.update { it + sourceId }
    }

    /** One request to the source's home page: a bot check, too many requests, or null when it's fine */
    suspend fun check(sourceId: Long): BotCheckReporter.Kind? {
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

    /** Waits until a held source is let go; false when its covers were skipped instead */
    private suspend fun awaitRelease(sourceId: Long): Boolean {
        if (sourceId in skipped.value) return false
        if (!SourceHold.isHeld(sourceId)) return true
        val (_, skippedNow) = combine(SourceHold.held, skipped) { held, skip -> held to skip }
            .first { (held, skip) -> sourceId !in held || sourceId in skip }
        return sourceId !in skippedNow
    }

    private enum class Load { OK, FAILED, BOT_CHECK, TOO_MANY }

    private suspend fun loadCovers(sourceIds: Set<Long>) {
        checksJob?.join()
        val coverCache = Injekt.get<CoverCache>()
        val missing = Injekt.get<GetFavorites>().await()
            .filter { it.source in sourceIds && !it.isLocal() && RefreshMissingCovers.isMissing(it, coverCache) }
        if (missing.isEmpty()) return

        val libraryPreferences = Injekt.get<LibraryPreferences>()
        val pauseEnabled = libraryPreferences.updatePauseEnabled().get()
        val pauseRange = libraryPreferences.updatePause().get()
        val title = app.stringResource(KMR.strings.restore_covers_title)
        val done = AtomicInteger(0)
        val loaded = AtomicInteger(0)
        val requests = Semaphore(MAX_COVERS_AT_ONCE)
        progress(title, 0, missing.size)

        try {
            coroutineScope {
                missing.groupBy { it.source }.map { (sourceId, mangas) ->
                    async {
                        var first = true
                        for (manga in mangas) {
                            if (!awaitRelease(sourceId)) break
                            if (!first) PauseBetweenTitles.pause(pauseEnabled, pauseRange)
                            first = false
                            var tries = 0
                            while (true) {
                                when (requests.withPermit { load(manga) }) {
                                    Load.OK -> {
                                        loaded.incrementAndGet()
                                        break
                                    }
                                    Load.FAILED -> break
                                    Load.BOT_CHECK -> {
                                        val site = BotCheckNotifier.siteInfo(sourceId)
                                        hold(sourceId)
                                        if (site != null) {
                                            BotCheckNotifier.onReport(
                                                site.baseUrl,
                                                BotCheckReporter.Kind.BOT_CHECK,
                                                sourceId,
                                                force = false,
                                            )
                                        }
                                        if (!awaitRelease(sourceId)) break
                                    }
                                    // The site asked to slow down: wait longer each time, then try again
                                    Load.TOO_MANY -> {
                                        if (++tries > MAX_SLOW_DOWN_TRIES) break
                                        delay(SLOW_DOWN_MS * tries)
                                    }
                                }
                            }
                            progress(title, done.incrementAndGet(), missing.size)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            app.cancelNotification(Notifications.ID_RESTORE_COVERS)
        }
        finish(title, app.stringResource(KMR.strings.refresh_missing_covers_done, loaded.get(), missing.size))
    }

    private suspend fun load(manga: Manga): Load {
        if (manga.thumbnailUrl.isNullOrBlank()) return Load.FAILED
        return try {
            val request = ImageRequest.Builder(app).data(manga.asMangaCover()).build()
            when (val result = app.imageLoader.execute(request)) {
                is SuccessResult -> Load.OK
                is ErrorResult -> {
                    val blocked = generateSequence(result.throwable) { it.cause }.take(5)
                        .filterIsInstance<SiteBlockedException>().firstOrNull()
                    when (blocked?.kind) {
                        BotCheckReporter.Kind.BOT_CHECK -> Load.BOT_CHECK
                        BotCheckReporter.Kind.TOO_MANY_REQUESTS -> Load.TOO_MANY
                        null -> Load.FAILED
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Load.FAILED
        }
    }

    /** Keeps the "Waiting for bot checks" notification up to date while sources are waiting */
    private fun watchWaiting() {
        watchJob?.cancel()
        watchJob = scope.launch {
            waiting.collect { ids ->
                if (ids.isEmpty()) {
                    app.cancelNotification(Notifications.ID_BOT_CHECK_WAITING)
                } else {
                    showWaiting(ids)
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // checked by canNotify
    private fun showWaiting(ids: Set<Long>) {
        if (!BotCheckNotifier.canNotify(app)) return
        val names = ids.mapNotNull { BotCheckNotifier.siteInfo(it)?.name }
        val title = app.stringResource(KMR.strings.bot_check_waiting_title, ids.size)
        val skipIntent = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, SkipReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = app.notificationBuilder(Notifications.CHANNEL_BOT_CHECK) {
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setContentTitle(title)
            setContentText(names.joinToString())
            setStyle(
                NotificationCompat.InboxStyle()
                    .setBigContentTitle(title)
                    .also { style -> names.forEach { style.addLine(it) } },
            )
            addAction(R.drawable.ic_close_24dp, app.stringResource(KMR.strings.bot_check_skip), skipIntent)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()
        NotificationManagerCompat.from(app).notify(Notifications.ID_BOT_CHECK_WAITING, notification)
    }

    @SuppressLint("MissingPermission") // checked by canNotify
    private fun progress(title: String, current: Int, total: Int) {
        if (!BotCheckNotifier.canNotify(app)) return
        val notification = app.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setContentTitle(title)
            setContentText("$current / $total")
            setProgress(total, current, false)
            setOnlyAlertOnce(true)
            setOngoing(true)
            priority = NotificationCompat.PRIORITY_LOW
        }.build()
        NotificationManagerCompat.from(app).notify(Notifications.ID_RESTORE_COVERS, notification)
    }

    @SuppressLint("MissingPermission") // checked by canNotify
    private fun finish(title: String, text: String) {
        if (!BotCheckNotifier.canNotify(app)) return
        val notification = app.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setContentTitle(title)
            setContentText(text)
            setAutoCancel(true)
        }.build()
        NotificationManagerCompat.from(app).notify(Notifications.ID_RESTORE_COVERS, notification)
    }

    /** The Skip button on the "Waiting for bot checks" notification */
    class SkipReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            skip()
        }
    }

    private const val MAX_CHECKS_AT_ONCE = 8
    private const val MAX_COVERS_AT_ONCE = 4
    private const val MAX_SLOW_DOWN_TRIES = 3
    private const val SLOW_DOWN_MS = 60_000L

    private val BOT_CHECK_ERROR = Regex("cloudflare|bot check|captcha|challenge", RegexOption.IGNORE_CASE)
}
