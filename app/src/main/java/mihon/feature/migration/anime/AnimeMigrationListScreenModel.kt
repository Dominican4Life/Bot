package mihon.feature.migration.anime

import android.app.Application
import androidx.annotation.FloatRange
import cafe.adriel.voyager.core.model.StateScreenModel
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.util.PauseBetweenTitles
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.feature.migration.anime.MigratingAnime.Candidate
import mihon.feature.migration.anime.MigratingAnime.SearchResult
import mihon.feature.migration.list.MigrationProgress
import mihon.feature.migration.list.isSlowDownError
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: The anime matching screen's work, like MigrationListScreenModel: looks for each anime in the chosen sources
 * one by one (with the pause between titles), keeps close results as candidates, then migrates or copies them. It
 * runs in its own scope, so it carries on in the background after the back arrow.
 */
class AnimeMigrationListScreenModel(
    animeIds: Collection<Long>,
    extraSearchQuery: String?,
    val preferences: SourcePreferences = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val repository: AnimeRepository = Injekt.get(),
    private val migrateAnime: MigrateAnimeUseCase = MigrateAnimeUseCase(),
) : StateScreenModel<AnimeMigrationListScreenModel.State>(State()) {

    private val smartSearchEngine = AnimeSmartSearchEngine(extraSearchQuery)

    private val context: Application = Injekt.get()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while the matching screen shows this migration */
    @Volatile
    var attached = false
        private set

    /** The anime being searched right now, to tell "Matching…" from "Waiting" */
    val matchingId = MutableStateFlow<Long?>(null)

    @Volatile
    private var finished = false

    @Volatile
    private var stopped = false

    fun setAttached(value: Boolean) {
        attached = value
        if (value) ActiveAnimeMigration.cancelDoneNotification(context)
    }

    /** Stops everything (the X button, or Cancel in the notification) */
    fun stop() {
        stopped = true
        cancelMigrate()
        cleanUp()
    }

    private fun cleanUp() {
        items.forEach { it.migrationScope.cancel() }
        scope.cancel()
        AnimeMigrationProgress.clear()
        ActiveAnimeMigration.clear(this, context)
    }

    private suspend fun pauseBetweenTitles() =
        PauseBetweenTitles.pause(preferences.migrationDelayEnabled().get(), preferences.migrationDelay().get())

    val items
        inline get() = state.value.items

    private var hideUnmatched = preferences.animeMigrationHideUnmatched().get()
    private var hideWithoutUpdates = preferences.animeMigrationHideWithoutUpdates().get()
    private var prioritizeByEpisodes = preferences.animeMigrationPrioritizeByEpisodes().get()
    private var deepSearchMode = preferences.animeMigrationDeepSearchMode().get()

    private val navigateBackChannel = Channel<Unit>(Channel.CONFLATED)
    val navigateBackEvent = navigateBackChannel.receiveAsFlow()

    private var migrateJob: Job? = null

    init {
        scope.launchIO {
            val animes = animeIds
                .map { id ->
                    async {
                        val anime = repository.getAnime(id) ?: return@async null
                        val info = getEpisodeInfo(id)
                        MigratingAnime(
                            anime = anime,
                            episodeCount = info.episodeCount,
                            latestEpisode = info.latestEpisode,
                            source = sourceName(anime.source),
                            parentContext = scope.coroutineContext,
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
            mutableState.update { it.copy(items = animes.toImmutableList()) }
            runMigrations(animes)
        }
    }

    private fun sourceName(sourceId: Long): String {
        val source = sourceManager.get(sourceId) ?: return sourceId.toString()
        return if (source.lang.isBlank()) source.name else "${source.name} (${source.lang.uppercase()})"
    }

    /** The sources picked in the Migrate window, in order, without the turned-off ones */
    private fun searchSources(): List<AnimeCatalogueSource> {
        val disabled = preferences.animeDisabledSources().get()
        return preferences.animeMigrationSources().get()
            .filterNot { it.toString() in disabled }
            .mapNotNull { sourceManager.get(it) as? AnimeCatalogueSource }
    }

    private suspend fun getEpisodeInfo(id: Long) = repository.getEpisodes(id).let { episodes ->
        EpisodeInfo(
            latestEpisode = episodes.maxOfOrNull { it.episodeNumber },
            episodeCount = episodes.size,
        )
    }

    private suspend fun Anime.toSuccessSearchResult(): SearchResult.Success {
        val info = getEpisodeInfo(id)
        return SearchResult.Success(
            anime = this,
            episodeCount = info.episodeCount,
            latestEpisode = info.latestEpisode,
            source = sourceName(source),
        )
    }

    private suspend fun runMigrations(animes: List<MigratingAnime>) {
        AnimeMigrationProgress.update(MigrationProgress.Status(moving = false, done = 0, total = animes.size))
        AnimeMigrationProgressJob.start(context)
        var searched = 0

        val sources = searchSources()

        for (anime in animes) {
            if (!currentCoroutineContext().isActive) break
            if (anime.anime.id !in state.value.animeIds) continue
            if (anime.searchResult.value != SearchResult.Searching) continue
            if (!anime.migrationScope.isActive) continue

            // A random pause between titles, so the sources don't see a bot-like stream of searches
            if (searched++ > 0) pauseBetweenTitles()

            matchingId.value = anime.anime.id
            val extras = java.util.Collections.synchronizedList(mutableListOf<Pair<Anime, String>>())
            val result = try {
                anime.searchingJob = anime.migrationScope.async {
                    if (prioritizeByEpisodes) {
                        val sourceSemaphore = Semaphore(5)
                        sources.map { source ->
                            async innerAsync@{
                                sourceSemaphore.withPermit {
                                    val result = searchSource(anime.anime, source, deepSearchMode, extras)
                                    if (result == null || result.second.episodeCount == 0) return@innerAsync null
                                    result
                                }
                            }
                        }
                            .mapNotNull { it.await() }
                            .maxByOrNull { it.second.latestEpisode ?: 0.0 }
                    } else {
                        sources.forEach { source ->
                            val result = searchSource(anime.anime, source, deepSearchMode, extras)
                            if (result != null) return@async result
                        }
                        null
                    }
                }
                anime.searchingJob?.await()
            } catch (_: CancellationException) {
                continue
            }

            val success = result?.first?.let { repository.getAnime(it.id) ?: it }?.toSuccessSearchResult()
            anime.searchResult.value = success ?: SearchResult.NotFound
            // The match first, then the other close results
            anime.candidates.value = buildList {
                if (success != null) add(Candidate(success.anime, success.source, success.episodeCount))
                synchronized(extras) { extras.toList() }.forEach { (extra, sourceName) ->
                    if (none { it.anime.id == extra.id }) add(Candidate(extra, sourceName, null))
                }
            }

            if (result == null && hideUnmatched) {
                removeAnime(anime)
            }
            if (result != null &&
                hideWithoutUpdates &&
                (result.second.latestEpisode ?: 0.0) <= (anime.latestEpisode ?: 0.0)
            ) {
                removeAnime(anime)
            }

            updateMigrationProgress()
        }
        matchingId.value = null
        if (migrateJob == null) AnimeMigrationProgress.clear()
        // Done while nobody is looking: a notification to come back and pick Migrate or Copy
        if (!attached && !stopped && items.isNotEmpty()) ActiveAnimeMigration.showDoneNotification(context)
    }

    private suspend fun searchSource(
        anime: Anime,
        source: AnimeCatalogueSource,
        deepSearchMode: Boolean,
        extras: MutableList<Pair<Anime, String>>? = null,
    ): Pair<Anime, EpisodeInfo>? {
        // When the site asks to slow down (429, bot check), wait longer and try again, up to 3 times
        var attempt = 0
        while (true) {
            try {
                return searchSourceOnce(anime, source, deepSearchMode, extras)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSlowDownError(e) || attempt >= 3) return null
                val wait = 30 shl attempt // 30 s, 60 s, 120 s
                attempt++
                logcat(LogPriority.WARN) { "Anime migration: ${source.name} asked to slow down, waiting ${wait}s" }
                AnimeMigrationProgress.state.value?.let { AnimeMigrationProgress.update(it.copy(pausedSeconds = wait)) }
                delay(wait * 1000L)
                AnimeMigrationProgress.state.value?.let { AnimeMigrationProgress.update(it.copy(pausedSeconds = null)) }
            }
        }
    }

    /** One search; throws on a network error so [searchSource] can tell a slow-down from a miss */
    private suspend fun searchSourceOnce(
        anime: Anime,
        source: AnimeCatalogueSource,
        deepSearchMode: Boolean,
        extras: MutableList<Pair<Anime, String>>? = null,
    ): Pair<Anime, EpisodeInfo>? {
        val found = smartSearchEngine.searchAll(source, anime.title, deepSearchMode)
            .filterNot { it.url == anime.url && source.id == anime.source }
        val searchResult = found.firstOrNull() ?: return null
        found.drop(1).take(MAX_EXTRA_CANDIDATES).forEach { extra ->
            runCatching { extras?.add(repository.networkToLocal(extra, source.id) to sourceName(source.id)) }
        }

        val localAnime = repository.networkToLocal(searchResult, source.id)
        try {
            repository.refreshFromSource(localAnime, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
        return localAnime to getEpisodeInfo(localAnime.id)
    }

    private fun updateMigrationProgress() {
        val current = AnimeMigrationProgress.state.value
        if (current != null && !current.moving) {
            AnimeMigrationProgress.update(
                current.copy(
                    done = items.count { it.searchResult.value != SearchResult.Searching },
                    total = items.size,
                ),
            )
        }
        mutableState.update { state ->
            state.copy(
                finishedCount = state.items.count { it.searchResult.value != SearchResult.Searching },
                migrationComplete = state.migrationComplete(),
            )
        }
        if (items.isEmpty()) {
            navigateBack()
        }
    }

    private fun State.migrationComplete() =
        items.all { it.searchResult.value != SearchResult.Searching } &&
            items.any { it.searchResult.value is SearchResult.Success }

    /** Uses one of an anime's candidates as its match */
    fun pickCandidate(current: Long, candidate: Candidate, onMissingEpisodes: () -> Unit) {
        val item = items.find { it.anime.id == current } ?: return
        if (item.candidates.value.none { it.anime.id == candidate.anime.id }) {
            item.candidates.value = item.candidates.value + candidate
        }
        useAnimeForMigration(current, candidate.anime.id, onMissingEpisodes)
    }

    /** Searches the chosen sources for [query], for the Candidates window (and "Search manually") */
    suspend fun searchCandidates(query: String): List<Candidate> = withIOContext {
        val semaphore = Semaphore(3)
        searchSources().map { source ->
            async {
                semaphore.withPermit {
                    try {
                        source.getSearchAnime(1, query, source.getFilterList()).animes
                            .take(MAX_SEARCH_CANDIDATES)
                            .map { Candidate(repository.networkToLocal(it, source.id), sourceName(source.id), null) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()
    }

    /** Sets an anime picked by hand as the one to migrate to */
    fun useAnimeForMigration(current: Long, target: Long, onMissingEpisodes: () -> Unit) {
        val migratingAnime = items.find { it.anime.id == current } ?: return
        migratingAnime.searchResult.value = SearchResult.Searching
        scope.launchIO {
            val result = migratingAnime.migrationScope.async {
                val anime = repository.getAnime(target) ?: return@async null
                val source = sourceManager.get(anime.source) ?: return@async null
                try {
                    repository.refreshFromSource(anime, source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e)
                }
                repository.getAnime(target)?.takeIf { repository.getEpisodes(it.id).isNotEmpty() }
            }
                .await()

            if (result == null) {
                migratingAnime.searchResult.value = SearchResult.NotFound
                withUIContext { onMissingEpisodes() }
                updateMigrationProgress()
                return@launchIO
            }

            val success = result.toSuccessSearchResult()
            migratingAnime.searchResult.value = success
            migratingAnime.candidates.value = migratingAnime.candidates.value.map {
                if (it.anime.id == success.anime.id) it.copy(episodeCount = success.episodeCount) else it
            }
            updateMigrationProgress()
        }
    }

    fun migrateAnimes() {
        migrateAnimes(replace = true)
    }

    fun copyAnimes() {
        migrateAnimes(replace = false)
    }

    private fun migrateAnimes(replace: Boolean) {
        // Runs in the app's scope, so it carries on when you leave this screen or the app
        migrateJob = AnimeMigrationScope.launch {
            mutableState.update { it.copy(dialog = Dialog.Progress(0f)) }
            val items = items
            AnimeMigrationProgress.update(MigrationProgress.Status(moving = true, done = 0, total = items.size))
            AnimeMigrationProgressJob.start(context)
            var moved = 0
            try {
                items.forEachIndexed { index, anime ->
                    try {
                        ensureActive()
                        val target = (anime.searchResult.value as? SearchResult.Success)?.anime
                        if (target != null) {
                            if (moved++ > 0) pauseBetweenTitles()
                            migrateAnime(current = anime.anime, target = target, replace = replace)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.WARN, throwable = e)
                    }
                    mutableState.update {
                        it.copy(dialog = Dialog.Progress((index.toFloat() / items.size).coerceAtMost(1f)))
                    }
                    AnimeMigrationProgress.update(
                        MigrationProgress.Status(moving = true, done = index + 1, total = items.size),
                    )
                }

                navigateBack()
            } finally {
                mutableState.update { it.copy(dialog = null) }
                migrateJob = null
                AnimeMigrationProgress.clear()
            }
        }
    }

    fun cancelMigrate() {
        migrateJob?.cancel()
        migrateJob = null
    }

    private fun navigateBack() {
        // The migration is over; when the screen isn't showing, nothing will close it, so clean up now
        finished = true
        if (!attached) cleanUp()
        navigateBackChannel.trySend(Unit)
    }

    fun migrateNow(animeId: Long, replace: Boolean) {
        scope.launchIO {
            val anime = items.find { it.anime.id == animeId } ?: return@launchIO
            val target = (anime.searchResult.value as? SearchResult.Success)?.anime ?: return@launchIO
            migrateAnime(current = anime.anime, target = target, replace = replace)

            removeAnime(animeId)
        }
    }

    /** Stops searching one anime without removing it, so it can be searched by hand */
    fun cancelAnime(animeId: Long) {
        scope.launchIO {
            val item = items.find { it.anime.id == animeId } ?: return@launchIO
            item.searchingJob?.cancel()
            item.searchingJob = null
            item.searchResult.value = SearchResult.NotFound
            updateMigrationProgress()
        }
    }

    fun removeAnime(animeId: Long) {
        scope.launchIO {
            val item = items.find { it.anime.id == animeId } ?: return@launchIO
            removeAnime(item)
            item.migrationScope.cancel()
            updateMigrationProgress()
        }
    }

    private fun removeAnime(item: MigratingAnime) {
        mutableState.update { it.copy(items = it.items.toPersistentList().remove(item)) }
    }

    override fun onDispose() {
        super.onDispose()
        // Leaving with the back arrow keeps the migration running in the background; it's only cleaned up once
        // it's over or stopped
        attached = false
        if (finished || stopped) {
            cleanUp()
        } else if (migrateJob == null && AnimeMigrationProgress.state.value == null && items.isNotEmpty()) {
            // Matching was already done: a notification to come back to it
            ActiveAnimeMigration.showDoneNotification(context)
        }
    }

    fun showMigrateDialog(copy: Boolean) {
        mutableState.update { state ->
            state.copy(
                dialog = Dialog.Migrate(
                    copy = copy,
                    totalCount = state.items.size,
                    skippedCount = state.items.count { it.searchResult.value == SearchResult.NotFound },
                ),
            )
        }
    }

    fun showExitDialog() {
        mutableState.update { it.copy(dialog = Dialog.Exit) }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun openOptionsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Options) }
    }

    fun updateOptions() {
        hideUnmatched = preferences.animeMigrationHideUnmatched().get()
        hideWithoutUpdates = preferences.animeMigrationHideWithoutUpdates().get()
        prioritizeByEpisodes = preferences.animeMigrationPrioritizeByEpisodes().get()
        deepSearchMode = preferences.animeMigrationDeepSearchMode().get()
    }

    private companion object {
        const val MAX_EXTRA_CANDIDATES = 5
        const val MAX_SEARCH_CANDIDATES = 10
    }

    data class EpisodeInfo(
        val latestEpisode: Double?,
        val episodeCount: Int,
    )

    sealed interface Dialog {
        data class Migrate(val copy: Boolean, val totalCount: Int, val skippedCount: Int) : Dialog
        data class Progress(@FloatRange(0.0, 1.0) val progress: Float) : Dialog
        data object Exit : Dialog
        data object Options : Dialog
    }

    data class State(
        val items: ImmutableList<MigratingAnime> = persistentListOf(),
        val finishedCount: Int = 0,
        val migrationComplete: Boolean = false,
        val dialog: Dialog? = null,
    ) {
        val animeIds: List<Long> = items.map { it.anime.id }
    }
}

/** KMK: Where moving anime runs, so it isn't cancelled when the matching screen closes */
private object AnimeMigrationScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)
