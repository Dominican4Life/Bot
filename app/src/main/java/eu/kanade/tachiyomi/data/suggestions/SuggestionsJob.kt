package eu.kanade.tachiyomi.data.suggestions

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.suggestions.SuggestionsPreferences
import eu.kanade.domain.suggestions.splitGenres
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * KMK: Finds manga you may like, in the background (ported from Kototoro's suggestions, adapted to Komikku's
 * sources):
 * - seeds are the library entries read most recently, and the genres they share;
 * - candidates come from each seed's related titles and from genre searches on a few sources
 *   (preferred sources first, excluded ones skipped);
 * - candidates are scored (seen through several seeds, preferred genres/sources), anything already in the library
 *   or with an excluded genre is dropped, and no source fills more than a dozen spots.
 */
class SuggestionsJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val preferences: SuggestionsPreferences = Injekt.get()
    private val getLibraryManga: GetLibraryManga = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences = Injekt.get()

    private class Candidate(val sourceId: Long, val manga: SManga) {
        var score = 0.0
    }

    override suspend fun doWork(): Result {
        if (!preferences.enabled().get()) return Result.success()
        running.value = true
        try {
            withTimeoutOrNull(TimeUnit.MINUTES.toMillis(5)) { collect() }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Suggestions failed" }
        } finally {
            running.value = false
        }
        return Result.success()
    }

    private suspend fun collect() {
        val library = getLibraryManga.await().sortedByDescending { it.lastRead }.map { it.manga }
        val inLibrary = library.map { it.source to it.url }.toSet()
        val excludedGenres = preferences.excludedGenres().get().splitGenres().map { it.lowercase() }.toSet()
        val preferredGenres = preferences.preferredGenres().get().splitGenres().map { it.lowercase() }
        val preferredSources = preferences.preferredSources().get().mapNotNull { it.toLongOrNull() }.toSet()
        val excludedSources = preferences.excludedSources().get().mapNotNull { it.toLongOrNull() }.toSet()

        // Genres the recent seeds share, most common first
        val seeds = library.take(SEED_COUNT)
        val seedGenres = seeds.flatMap { it.genre.orEmpty() }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it !in excludedGenres }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }
            .take(SEED_GENRES)
        val searchGenres = (preferredGenres + seedGenres).distinct()

        // Sources to ask
        val disabled = sourcePreferences.disabledSources().get()
        val languages = sourcePreferences.enabledLanguages().get()
        val includeDisabled = preferences.includeDisabledSources().get()
        val sources = sourceManager.getVisibleOnlineSources()
            .filterIsInstance<CatalogueSource>()
            .filter { it.id !in excludedSources }
            .filter { includeDisabled || (it.id.toString() !in disabled && it.lang in languages) }
        val askSources = sources.filter { it.id in preferredSources }.shuffled() +
            sources.filter { it.id !in preferredSources }.shuffled()

        val candidates = linkedMapOf<Pair<Long, String>, Candidate>()
        fun add(sourceId: Long, list: List<SManga>, weight: Double) = synchronized(candidates) {
            list.take(PER_QUERY).forEach { manga ->
                if ((sourceId to manga.url) in inLibrary) return@forEach
                val candidate = candidates.getOrPut(sourceId to manga.url) { Candidate(sourceId, manga) }
                candidate.score += weight
            }
        }

        // 1. Titles related to what's being read
        seeds.shuffled().take(RELATED_SEEDS).forEach { seed ->
            val source = sourceManager.get(seed.source) as? CatalogueSource ?: return@forEach
            if (source.id in excludedSources) return@forEach
            withTimeoutOrNull(TimeUnit.SECONDS.toMillis(40)) {
                runCatching {
                    source.getRelatedMangaList(
                        manga = seed.toSManga(),
                        exceptionHandler = {},
                        pushResults = { (_, list), _ -> add(source.id, list, RELATED_WEIGHT) },
                    )
                }
            }
        }

        // 2. Genre searches on a few sources
        askSources.take(SEARCH_SOURCES).forEachIndexed { index, source ->
            withTimeoutOrNull(TimeUnit.SECONDS.toMillis(30)) {
                runCatching {
                    val page = if (searchGenres.isNotEmpty()) {
                        source.getSearchManga(1, searchGenres[index % searchGenres.size], source.getFilterList())
                    } else {
                        source.getPopularManga(1)
                    }
                    add(source.id, page.mangas, SEARCH_WEIGHT)
                }.onFailure { logcat(LogPriority.WARN, it) { "Suggestions: ${source.name} failed" } }
            }
        }

        // Rank
        val ranked = candidates.values
            .filter { c ->
                val genres = c.manga.genre?.split(',')?.map { it.trim().lowercase() }.orEmpty()
                genres.none { it in excludedGenres }
            }
            .onEach { c ->
                val genres = c.manga.genre?.split(',')?.map { it.trim().lowercase() }.orEmpty()
                c.score += genres.count { it in preferredGenres } * PREFERRED_GENRE_WEIGHT
                c.score += genres.count { it in seedGenres } * SEED_GENRE_WEIGHT
                if (c.sourceId in preferredSources) c.score += PREFERRED_SOURCE_WEIGHT
            }
            .sortedByDescending { it.score }
            .groupBy { it.sourceId }
            .flatMap { (_, list) -> list.take(MAX_PER_SOURCE) }
            .sortedByDescending { it.score }
            .take(MAX_RESULTS)

        if (ranked.isEmpty()) return
        val local = networkToLocalManga(ranked.map { it.manga.toDomainManga(it.sourceId) }, updateInfo = false)
        preferences.results().set(local.joinToString(",") { it.id.toString() })
        preferences.lastUpdated().set(System.currentTimeMillis())

        if (preferences.notifications().get()) {
            local.take((local.size / 3).coerceAtLeast(1)).randomOrNull()?.let { manga ->
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, manga.id)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                applicationContext.notify(Notifications.ID_SUGGESTION, Notifications.CHANNEL_COMMON) {
                    setSmallIcon(eu.kanade.tachiyomi.R.drawable.ic_komikku)
                    setContentTitle(applicationContext.stringResource(KMR.strings.suggestions_notification_title))
                    setContentText(manga.title)
                    setAutoCancel(true)
                    setContentIntent(
                        PendingIntent.getActivity(
                            applicationContext,
                            manga.id.toInt(),
                            intent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "Suggestions"
        private const val TAG_NOW = "SuggestionsNow"

        private const val SEED_COUNT = 20
        private const val SEED_GENRES = 8
        private const val RELATED_SEEDS = 4
        private const val SEARCH_SOURCES = 6
        private const val PER_QUERY = 20
        private const val MAX_PER_SOURCE = 12
        private const val MAX_RESULTS = 120
        private const val RELATED_WEIGHT = 3.0
        private const val SEARCH_WEIGHT = 2.0
        private const val PREFERRED_GENRE_WEIGHT = 2.0
        private const val SEED_GENRE_WEIGHT = 0.5
        private const val PREFERRED_SOURCE_WEIGHT = 1.5

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        private fun constraints(): Constraints {
            val wifi = Injekt.get<SuggestionsPreferences>().onlyOnWifi().get()
            return Constraints.Builder()
                .setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        }

        /** Every 6 hours while suggestions are on */
        fun setupTask(context: Context) {
            val prefs = Injekt.get<SuggestionsPreferences>()
            if (!prefs.enabled().get()) {
                context.workManager.cancelUniqueWork(TAG)
                return
            }
            val request = PeriodicWorkRequestBuilder<SuggestionsJob>(6, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                .addTag(TAG)
                .setConstraints(constraints())
                .build()
            context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SuggestionsJob>()
                .addTag(TAG_NOW)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            context.workManager.enqueueUniqueWork(TAG_NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
