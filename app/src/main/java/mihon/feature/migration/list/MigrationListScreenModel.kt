package mihon.feature.migration.list

import android.app.Application
import androidx.annotation.FloatRange
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import exh.source.MERGED_SOURCE_ID
import exh.util.ThrottleManager
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.feature.migration.list.models.MigratingManga
import mihon.feature.migration.list.models.MigratingManga.SearchResult
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrationListScreenModel(
    mangaIds: Collection<Long>,
    extraSearchQuery: String?,
    // KMK -->
    runManually: Boolean = false,
    // KMK <--
    val preferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val migrateManga: MigrateMangaUseCase = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
) : StateScreenModel<MigrationListScreenModel.State>(State()) {

    private val smartSearchEngine = SmartSourceSearchEngine(extraSearchQuery)

    // SY -->
    private val throttleManager = ThrottleManager()
    // SY <--

    // KMK
    private val context: Application = Injekt.get()

    val items
        inline get() = state.value.items

    private var hideUnmatched = preferences.migrationHideUnmatched().get()
    private var hideWithoutUpdates = preferences.migrationHideWithoutUpdates().get()
    // KMK -->
    private var prioritizeByChapters = preferences.migrationPrioritizeByChapters().get()
    private var deepSearchMode = preferences.migrationDeepSearchMode().get()
    // KMK <--

    // KMK: conflated, so a migration that finishes after the screen is gone doesn't wait for it forever
    private val navigateBackChannel = Channel<Unit>(Channel.CONFLATED)
    val navigateBackEvent = navigateBackChannel.receiveAsFlow()

    private var migrateJob: Job? = null

    init {
        screenModelScope.launchIO {
            val manga = mangaIds
                .map {
                    async {
                        val manga = getManga.await(it) ?: return@async null
                        val chapterInfo = getChapterInfo(it)
                        MigratingManga(
                            manga = manga,
                            chapterCount = chapterInfo.chapterCount,
                            latestChapter = chapterInfo.latestChapter,
                            source = sourceManager.getOrStub(manga.source).getNameForMangaInfo(
                                // KMK -->
                                if (manga.source == MERGED_SOURCE_ID) {
                                    sourceManager.getMergedSources(manga.id)
                                } else {
                                    null
                                },
                                // KMK <--
                            ),
                            parentContext = screenModelScope.coroutineContext,
                            // KMK -->
                        ).apply {
                            if (runManually) searchResult.value = SearchResult.NotFound
                            // KMK <--
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
            mutableState.update { it.copy(items = manga.toImmutableList()) }
            // KMK -->
            if (runManually) return@launchIO
            // KMK <--
            runMigrations(manga)
        }
    }

    private suspend fun getChapterInfo(id: Long) = getChaptersByMangaId.await(id).let { chapters ->
        ChapterInfo(
            latestChapter = chapters.maxOfOrNull { it.chapterNumber },
            chapterCount = chapters.size,
        )
    }

    private suspend fun Manga.toSuccessSearchResult(): SearchResult.Success {
        val chapterInfo = getChapterInfo(id)
        val source = sourceManager.getOrStub(source).getNameForMangaInfo()
        return SearchResult.Success(
            manga = this,
            chapterCount = chapterInfo.chapterCount,
            latestChapter = chapterInfo.latestChapter,
            source = source,
        )
    }

    private suspend fun runMigrations(mangas: List<MigratingManga>) {
        // SY -->
        throttleManager.resetThrottle()
        // SY <--
        // KMK --> progress notification, which also keeps the app running in the background
        MigrationProgress.update(MigrationProgress.Status(moving = false, done = 0, total = mangas.size))
        MigrationProgressJob.start(context)
        var searched = 0
        // KMK <--
        // KMK -->
        // val prioritizeByChapters = preferences.migrationPrioritizeByChapters().get()
        // val deepSearchMode = preferences.migrationDeepSearchMode().get()
        // KMK <--

        val sources = preferences.migrationSources().get()
            .mapNotNull { sourceManager.get(it) }

        for (manga in mangas) {
            if (!currentCoroutineContext().isActive) break
            if (manga.manga.id !in state.value.mangaIds) continue
            if (manga.searchResult.value != SearchResult.Searching) continue
            if (!manga.migrationScope.isActive) continue

            // KMK: a random pause between titles, so the sources don't see a bot-like stream of searches
            if (searched++ > 0) migrationPause(preferences.migrationDelay().get())

            val result = try {
                // KMK -->
                manga.searchingJob = manga.migrationScope.async {
                    // KMK <--
                    if (prioritizeByChapters) {
                        val sourceSemaphore = Semaphore(5)
                        sources.map { source ->
                            async innerAsync@{
                                sourceSemaphore.withPermit {
                                    val result = searchSource(manga.manga, source, deepSearchMode)
                                    if (result == null || result.second.chapterCount == 0) return@innerAsync null
                                    result
                                }
                            }
                        }
                            .mapNotNull { it.await() }
                            .maxByOrNull { it.second.latestChapter ?: 0.0 }
                    } else {
                        sources.forEach { source ->
                            val result = searchSource(manga.manga, source, deepSearchMode)
                            if (result != null) return@async result
                        }
                        null
                    }
                }
                // KMK -->
                manga.searchingJob?.await()
                // KMK <--
            } catch (_: CancellationException) {
                continue
            }

            if (result != null && result.first.thumbnailUrl == null) {
                try {
                    updateMangaFromRemote(result.first, fetchDetails = true, manualFetch = true).getOrThrow().manga
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }

            manga.searchResult.value = result?.first?.toSuccessSearchResult() ?: SearchResult.NotFound

            if (result == null && hideUnmatched) {
                removeManga(manga)
            }
            if (result != null &&
                hideWithoutUpdates &&
                (result.second.latestChapter ?: 0.0) <= (manga.latestChapter ?: 0.0)
            ) {
                removeManga(manga)
            }

            updateMigrationProgress()
        }
        // KMK: searching is done; moving shows its own progress
        if (migrateJob == null) MigrationProgress.clear()
    }

    private suspend fun searchSource(
        manga: Manga,
        source: Source,
        deepSearchMode: Boolean,
    ): Pair<Manga, ChapterInfo>? {
        // KMK: when the site asks to slow down (429, bot check), wait longer and try again, up to 3 times
        var attempt = 0
        while (true) {
            try {
                return searchSourceOnce(manga, source, deepSearchMode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSlowDownError(e) || attempt >= 3) return null
                val wait = 30 shl attempt // 30 s, 60 s, 120 s
                attempt++
                logcat(LogPriority.WARN) { "Migration: ${source.name} asked to slow down, waiting ${wait}s" }
                MigrationProgress.state.value?.let { MigrationProgress.update(it.copy(pausedSeconds = wait)) }
                delay(wait * 1000L)
                MigrationProgress.state.value?.let { MigrationProgress.update(it.copy(pausedSeconds = null)) }
            }
        }
    }

    /** One search; throws on a network error so [searchSource] can tell a slow-down from a miss */
    private suspend fun searchSourceOnce(
        manga: Manga,
        source: Source,
        deepSearchMode: Boolean,
    ): Pair<Manga, ChapterInfo>? {
        val searchResult = if (deepSearchMode) {
            smartSearchEngine.deepSearch(source, manga.title)
        } else {
            smartSearchEngine.regularSearch(source, manga.title)
        }

        if (searchResult == null || (searchResult.url == manga.url && source.id == manga.source)) return null

        val localManga = networkToLocalManga(searchResult)
        try {
            updateMangaFromRemote(
                manga = localManga,
                fetchChapters = true,
                // SY -->
                throttleFunc = throttleManager::throttle,
                // SY <--
            ).getOrThrow()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
        return localManga to getChapterInfo(localManga.id)
    }

    private suspend fun updateMigrationProgress() {
        // KMK: the notification follows the search
        val current = MigrationProgress.state.value
        if (current != null && !current.moving) {
            MigrationProgress.update(
                current.copy(
                    done = items.count { it.searchResult.value != SearchResult.Searching },
                    total = items.size,
                ),
            )
        }
        mutableState.update { state ->
            state.copy(
                // KMK -->
                finishedCount = state.items.count { it.searchResult.value != SearchResult.Searching },
                migrationComplete = state.migrationComplete(),
                // KMK <--
            )
        }
        if (items.isEmpty()) {
            navigateBack()
        }
    }

    // KMK -->
    private fun State.migrationComplete() =
        // KMK <--
        items.all { it.searchResult.value != SearchResult.Searching } &&
            items.any { it.searchResult.value is SearchResult.Success }

    /** Set a manga picked from manual search to be used as migration target */
    fun useMangaForMigration(current: Long, target: Long, onMissingChapters: () -> Unit) {
        val migratingManga = items.find { it.manga.id == current } ?: return
        migratingManga.searchResult.value = SearchResult.Searching
        screenModelScope.launchIO {
            val result = migratingManga.migrationScope.async {
                val manga = getManga.await(target) ?: return@async null
                try {
                    val source = sourceManager.get(manga.source)!!
                    updateMangaFromRemote(
                        source = source,
                        manga = manga,
                        fetchChapters = true,
                        // SY -->
                        throttleFunc = throttleManager::throttle,
                        // SY <--
                    ).getOrThrow().manga
                } catch (_: Exception) {
                    null
                }
            }
                .await()

            if (result == null) {
                migratingManga.searchResult.value = SearchResult.NotFound
                withUIContext { onMissingChapters() }
                return@launchIO
            }

            migratingManga.searchResult.value = result.toSuccessSearchResult()
            updateMigrationProgress()
        }
    }

    fun migrateMangas() {
        migrateMangas(replace = true)
    }

    fun copyMangas() {
        migrateMangas(replace = false)
    }

    private fun migrateMangas(replace: Boolean) {
        // KMK: runs in the app's scope, so it carries on when you leave this screen or the app
        migrateJob = MigrationScope.launchIO {
            mutableState.update { it.copy(dialog = Dialog.Progress(0f)) }
            val items = items
            MigrationProgress.update(MigrationProgress.Status(moving = true, done = 0, total = items.size))
            MigrationProgressJob.start(context)
            var moved = 0
            try {
                items.forEachIndexed { index, manga ->
                    try {
                        ensureActive()
                        val target = manga.searchResult.value.let {
                            if (it is SearchResult.Success) {
                                it.manga
                            } else {
                                null
                            }
                        }
                        if (target != null) {
                            // KMK: the same random pause between titles as the search
                            if (moved++ > 0) migrationPause(preferences.migrationDelay().get())
                            migrateManga(
                                current = manga.manga,
                                target = target,
                                replace = replace,
                                // SY -->
                                throttleFunc = throttleManager::throttle,
                                // SY <--
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.WARN, throwable = e)
                    }
                    mutableState.update {
                        it.copy(dialog = Dialog.Progress((index.toFloat() / items.size).coerceAtMost(1f)))
                    }
                    MigrationProgress.update(MigrationProgress.Status(moving = true, done = index + 1, total = items.size))
                }

                navigateBack()
            } finally {
                mutableState.update { it.copy(dialog = null) }
                migrateJob = null
                MigrationProgress.clear()
            }
        }
    }

    fun cancelMigrate() {
        migrateJob?.cancel()
        migrateJob = null
    }

    private fun navigateBack() {
        navigateBackChannel.trySend(Unit)
    }

    fun migrateNow(mangaId: Long, replace: Boolean) {
        screenModelScope.launchIO {
            val manga = items.find { it.manga.id == mangaId } ?: return@launchIO
            val target = (manga.searchResult.value as? SearchResult.Success)?.manga ?: return@launchIO
            migrateManga(current = manga.manga, target = target, replace = replace)

            removeManga(mangaId)
        }
    }

    // KMK -->
    /** Cancel searching without remove it from list so user can perform manual search */
    fun cancelManga(mangaId: Long) {
        screenModelScope.launchIO {
            val item = items.find { it.manga.id == mangaId } ?: return@launchIO
            item.searchingJob?.cancel()
            item.searchingJob = null
            item.searchResult.value = SearchResult.NotFound
            updateMigrationProgress()
        }
    }
    // KMK <--

    fun removeManga(mangaId: Long) {
        screenModelScope.launchIO {
            val item = items.find { it.manga.id == mangaId } ?: return@launchIO
            removeManga(item)
            item.migrationScope.cancel()
            updateMigrationProgress()
        }
    }

    private fun removeManga(item: MigratingManga) {
        mutableState.update { it.copy(items = items.toPersistentList().remove(item)) }
    }

    override fun onDispose() {
        super.onDispose()
        items.forEach {
            it.migrationScope.cancel()
        }
        // KMK: moving carries on without the screen; searching stops with it
        if (migrateJob == null) MigrationProgress.clear()
    }

    fun showMigrateDialog(copy: Boolean) {
        mutableState.update { state ->
            state.copy(
                dialog = Dialog.Migrate(
                    copy = copy,
                    // KMK -->
                    totalCount = state.items.size,
                    skippedCount = state.items.count { it.searchResult.value == SearchResult.NotFound },
                    // KMK <--
                ),
            )
        }
    }

    fun showExitDialog() {
        mutableState.update {
            it.copy(dialog = Dialog.Exit)
        }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    // KMK -->
    fun openOptionsDialog() {
        mutableState.update {
            it.copy(dialog = Dialog.Options)
        }
    }

    fun updateOptions() {
        hideUnmatched = preferences.migrationHideUnmatched().get()
        hideWithoutUpdates = preferences.migrationHideWithoutUpdates().get()
        prioritizeByChapters = preferences.migrationPrioritizeByChapters().get()
        deepSearchMode = preferences.migrationDeepSearchMode().get()
    }
    // KMK <--

    data class ChapterInfo(
        val latestChapter: Double?,
        val chapterCount: Int,
    )

    sealed interface Dialog {
        data class Migrate(val copy: Boolean, val totalCount: Int, val skippedCount: Int) : Dialog
        data class Progress(@FloatRange(0.0, 1.0) val progress: Float) : Dialog
        data object Exit : Dialog

        // KMK -->
        data object Options : Dialog
        // KMK <--
    }

    data class State(
        val items: ImmutableList<MigratingManga> = persistentListOf(),
        val finishedCount: Int = 0,
        val migrationComplete: Boolean = false,
        val dialog: Dialog? = null,
    ) {
        val mangaIds: List<Long> = items.map { it.manga.id }
    }
}

/** KMK: Where moving titles runs, so it isn't cancelled when the migration screen closes */
private object MigrationScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)
