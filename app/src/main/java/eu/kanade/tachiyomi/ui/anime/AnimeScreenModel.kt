package eu.kanade.tachiyomi.ui.anime

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.AnimeVideoFetcher
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.presentation.core.icons.FlagEmoji
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Details and episodes of one anime. Playback goes to an outside video player for now.
 */
class AnimeScreenModel(
    private val animeId: Long,
    private val repository: AnimeRepository = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    val downloadManager: AnimeDownloadManager = Injekt.get(),
) : StateScreenModel<AnimeScreenModel.State>(State()) {

    var source: AnimeSource? = null
        private set

    // KMK: anime rows have no notes or scanlator columns yet, so these live in preferences
    private val preferenceStore: PreferenceStore = Injekt.get()
    private val excludedScanlatorsPref = preferenceStore.getStringSet("anime_excluded_scanlators_$animeId", emptySet())
    private val notesPref = preferenceStore.getString("anime_notes_$animeId", "")

    /** Source order, keeping each merged anime's episodes together in merge order */
    private fun orderKey(episode: Episode): Long =
        (state.value.memberOrder[episode.animeId] ?: 0).toLong() * 1_000_000L + episode.sourceOrder

    private data class Quad(val anime: Anime?, val episodes: List<Episode>, val excluded: Set<String>, val notes: String)

    private fun filterAndSort(anime: Anime, episodes: List<Episode>, excluded: Set<String>): List<Episode> {
        fun TriState.keep(value: Boolean) = when (this) {
            TriState.DISABLED -> true
            TriState.ENABLED_IS -> value
            TriState.ENABLED_NOT -> !value
        }
        val shown = episodes.filter { ep ->
            anime.unseenFilter.keep(!ep.seen) &&
                anime.bookmarkedFilter.keep(ep.bookmark) &&
                anime.fillermarkedFilter.keep(ep.fillermark) &&
                anime.downloadedFilter.keep(downloadManager.isDownloaded(state.value.animeOf(ep) ?: anime, ep)) &&
                (ep.scanlator.isNullOrBlank() || ep.scanlator !in excluded)
        }
        // "Descending" is the default: newest first
        val descending = !anime.sortAscending
        val sorted = when (anime.sortMode) {
            Anime.SORT_BY_NUMBER -> shown.sortedBy { it.episodeNumber }.let { if (descending) it.reversed() else it }
            Anime.SORT_BY_UPLOAD_DATE -> shown.sortedBy { it.dateUpload }.let { if (descending) it.reversed() else it }
            Anime.SORT_BY_ALPHABET -> shown.sortedBy { it.name.lowercase() }.let { if (descending) it.reversed() else it }
            else -> shown.sortedBy { orderKey(it) }.let { if (descending) it else it.reversed() }
        }
        return sorted
    }

    private var refreshJob: Job? = null
    private var seedColorUrl: String? = null
    private var videoJob: Job? = null

    init {
        screenModelScope.launchIO {
            // KMK: a merged page lists the episodes of every merged anime
            val episodesFlow = repository.subscribeMergeMembers(animeId).flatMapLatest { members ->
                mutableState.update { state ->
                    state.copy(
                        members = members,
                        memberOrder = members.mapIndexed { index, a -> a.id to index }.toMap(),
                        memberSources = if (members.size > 1) {
                            members.associate { a -> a.id to (sourceManager.get(a.source)?.name ?: a.source.toString()) }
                        } else {
                            emptyMap()
                        },
                    )
                }
                if (members.size <= 1) {
                    repository.subscribeEpisodes(animeId)
                } else {
                    combine(members.map { repository.subscribeEpisodes(it.id) }) { lists -> lists.flatMap { it } }
                }
            }
            combine(
                repository.subscribeAnime(animeId),
                episodesFlow,
                excludedScanlatorsPref.changes(),
                notesPref.changes(),
            ) { anime, episodes, excluded, notes ->
                Quad(anime, episodes, excluded, notes)
            }.collect { (anime, episodes, excluded, notes) ->
                // KMK: theme color from the cover, like the manga details page
                if (anime != null && anime.thumbnailUrl != seedColorUrl) {
                    seedColorUrl = anime.thumbnailUrl
                    eu.kanade.presentation.anime.loadAnimeSeedColor(anime) { color ->
                        mutableState.update { it.copy(seedColor = color) }
                    }
                }
                mutableState.update { state ->
                    state.copy(
                        anime = anime,
                        allEpisodes = episodes,
                        episodes = anime?.let { filterAndSort(it, episodes, excluded) } ?: episodes,
                        scanlators = episodes.mapNotNull { it.scanlator?.takeIf(String::isNotBlank) }.toSet(),
                        excludedScanlators = excluded,
                        notes = notes,
                        downloadedIds = anime?.let { a ->
                            episodes.filter { downloadManager.isDownloaded(state.animeOf(it) ?: a, it) }
                                .map { it.id }
                                .toSet()
                        } ?: emptySet(),
                        isLoaded = true,
                    )
                }
            }
        }
        // KMK: download progress and finished downloads update the rows and the Downloaded filter
        screenModelScope.launchIO {
            repository.getAnime(animeId)?.let { downloadManager.refresh(it) }
            combine(
                downloadManager.queue.flatMapLatest { items ->
                    if (items.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(
                            items.map { item ->
                                combine(item.state, item.progress) { st, p -> item.episode.id to DownloadInfo(st, p) }
                            },
                        ) { it.toMap() }
                    }
                },
                downloadManager.changes,
            ) { active, _ -> active }.collect { active ->
                mutableState.update { state ->
                    val anime = state.anime
                    state.copy(
                        activeDownloads = active,
                        downloadedIds = anime?.let { a ->
                            state.allEpisodes
                                .filter { downloadManager.isDownloaded(state.animeOf(it) ?: a, it) }
                                .map { it.id }
                                .toSet()
                        } ?: emptySet(),
                        episodes = anime?.let { filterAndSort(it, state.allEpisodes, state.excludedScanlators) }
                            ?: state.episodes,
                    )
                }
            }
        }
        screenModelScope.launchIO {
            // Extensions load in the background after app start
            var found = sourceManager.get(sourceIdOf() ?: return@launchIO)
            var tries = 0
            while (found == null && tries < 20) {
                delay(250)
                found = sourceManager.get(sourceIdOf() ?: return@launchIO)
                tries++
            }
            source = found
            // KMK: same "Name (flag)" label as manga sources
            val label = found?.let { src -> "${src.name} (${FlagEmoji.getEmojiLangFlag(src.lang)})" }
            mutableState.update { it.copy(sourceName = label, sourceMissing = found == null) }
            loadSuggestions()
            val anime = repository.getAnime(animeId) ?: return@launchIO
            if (found != null && (!anime.initialized || anime.fetchType == FetchType.Seasons || !anime.favorite)) {
                refresh()
            } else if (found != null) {
                val episodes = repository.getEpisodes(animeId)
                // KMK: episodes saved before thumbnails / summaries were kept get them once per run
                val bare = episodes.isNotEmpty() &&
                    episodes.all { it.previewUrl.isNullOrBlank() && it.summary.isNullOrBlank() } &&
                    refreshedThisRun.add(animeId)
                if (episodes.isEmpty() || bare) refresh()
            }
        }
    }

    private suspend fun sourceIdOf(): Long? = repository.getAnime(animeId)?.source

    fun refresh() {
        val source = source ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true, error = null) }
            try {
                val anime = repository.getAnime(animeId) ?: return@launchIO
                repository.refreshFromSource(anime, source)
                // KMK: merged anime refresh from their own sources too
                state.value.members.filter { it.id != animeId }.forEach { member ->
                    val memberSource = sourceManager.get(member.source) ?: return@forEach
                    runCatching { repository.refreshFromSource(member, memberSource) }
                        .onFailure { e -> logcat(LogPriority.WARN, e) { "Couldn't refresh ${member.title}" } }
                }
                val updated = repository.getAnime(animeId) ?: anime
                if (updated.fetchType == FetchType.Seasons) {
                    val seasons = source.getAnimeSeasonUpdate(updated.toSAnime(), emptyList(), false, true).seasons
                        .map { repository.networkToLocal(it, source.id) }
                    mutableState.update { it.copy(seasons = seasons) }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                mutableState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                mutableState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    // KMK --> suggestions, like the manga details page
    private fun loadSuggestions() {
        val source = source as? eu.kanade.tachiyomi.animesource.AnimeCatalogueSource ?: return
        if (!Injekt.get<eu.kanade.domain.source.service.SourcePreferences>().relatedMangas().get()) return
        screenModelScope.launchIO {
            val anime = repository.getAnime(animeId) ?: return@launchIO
            mutableState.update { it.copy(suggestionsLoading = true) }
            try {
                source.getRelatedAnimeList(
                    anime = anime.toSAnime(),
                    exceptionHandler = { e -> logcat(LogPriority.WARN, e) { "Suggestions failed" } },
                    pushResults = { (_, list), _ ->
                        val found = list.mapNotNull {
                            runCatching { repository.networkToLocal(it, source.id) }.getOrNull()
                        }.filter { it.id != animeId }
                        mutableState.update { state ->
                            state.copy(suggestions = (state.suggestions + found).distinctBy { it.id }.take(40))
                        }
                    },
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Suggestions failed" }
            } finally {
                mutableState.update { it.copy(suggestionsLoading = false) }
            }
        }
    }

    /** Sets one tri-state filter (only these / hide these / off), like the manga chapter filters */
    fun setEpisodeFilter(include: Long, exclude: Long, state: TriState) {
        val anime = this.state.value.anime ?: return
        screenModelScope.launchIO {
            repository.setEpisodeFlags(anime, Anime.withTriState(anime.episodeFlags, include, exclude, state))
        }
    }

    /** Picking the current sort again flips its direction, like the manga Sort tab */
    fun setSortMode(mode: Long) {
        val anime = state.value.anime ?: return
        val flags = anime.episodeFlags
        val newFlags = if (anime.sortMode == mode) {
            flags xor Anime.SORT_ASCENDING
        } else {
            (flags and Anime.SORT_MODE_MASK.inv() and Anime.SORT_ASCENDING.inv()) or mode
        }
        screenModelScope.launchIO { repository.setEpisodeFlags(anime, newFlags) }
    }

    fun setDisplayNumber(number: Boolean) {
        val anime = state.value.anime ?: return
        val flags = if (number) {
            anime.episodeFlags or Anime.DISPLAY_NUMBER
        } else {
            anime.episodeFlags and Anime.DISPLAY_NUMBER.inv()
        }
        screenModelScope.launchIO { repository.setEpisodeFlags(anime, flags) }
    }

    fun resetEpisodeSettings() {
        val anime = state.value.anime ?: return
        screenModelScope.launchIO { repository.setEpisodeFlags(anime, 0L) }
        excludedScanlatorsPref.delete()
    }

    fun setExcludedScanlators(excluded: Set<String>) {
        excludedScanlatorsPref.set(excluded)
    }

    fun setNotes(notes: String) {
        notesPref.set(notes)
    }

    /** "Clear anime": removes downloaded files and/or the saved episodes (then fetches the list again) */
    fun clearAnime(deleteDownloads: Boolean, removeEpisodes: Boolean) {
        val anime = state.value.anime ?: return
        if (deleteDownloads) (state.value.members.ifEmpty { listOf(anime) }).forEach(downloadManager::deleteAnime)
        if (removeEpisodes) {
            screenModelScope.launchIO {
                repository.deleteEpisodes(animeId)
                refresh()
            }
        }
    }

    /** Takes [member] off this merged page */
    fun removeFromMerge(member: Anime) {
        screenModelScope.launchIO { repository.removeFromMerge(animeId, member.id) }
    }

    /** Toolbar download menu */
    fun downloadEpisodes(action: DownloadAction) {
        val anime = state.value.anime ?: return
        // Watching order: oldest first
        val inOrder = state.value.allEpisodes.sortedByDescending { it.sourceOrder }
        val unseen = inOrder.filter { !it.seen }
        val episodes = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> unseen.take(1)
            DownloadAction.NEXT_5_CHAPTERS -> unseen.take(5)
            DownloadAction.NEXT_10_CHAPTERS -> unseen.take(10)
            DownloadAction.NEXT_25_CHAPTERS -> unseen.take(25)
            DownloadAction.UNREAD_CHAPTERS -> unseen
            DownloadAction.BOOKMARKED_CHAPTERS -> inOrder.filter { it.bookmark }
        }
        episodes.groupBy { state.value.animeOf(it) ?: anime }
            .forEach { (owner, list) -> downloadManager.enqueue(owner, list) }
    }

    /** The download button on an episode row */
    fun episodeDownloadAction(episode: Episode, action: ChapterDownloadAction) {
        val anime = state.value.animeOf(episode) ?: return
        when (action) {
            ChapterDownloadAction.START -> downloadManager.enqueue(anime, listOf(episode))
            ChapterDownloadAction.START_NOW -> downloadManager.enqueue(anime, listOf(episode), startNow = true)
            ChapterDownloadAction.CANCEL -> downloadManager.cancel(listOf(episode.id))
            ChapterDownloadAction.DELETE -> downloadManager.delete(anime, listOf(episode))
        }
    }
    // KMK <--

    fun toggleFavorite() {
        val anime = state.value.anime ?: return
        screenModelScope.launchIO { repository.setFavorite(anime, !anime.favorite) }
    }

    fun toggleSort() {
        val anime = state.value.anime ?: return
        screenModelScope.launchIO {
            repository.setEpisodeFlags(anime, anime.episodeFlags xor Anime.SORT_ASCENDING)
        }
    }

    fun toggleSeen(episode: Episode) {
        screenModelScope.launchIO { repository.setSeen(listOf(episode), !episode.seen) }
    }

    fun toggleBookmark(episode: Episode) {
        screenModelScope.launchIO { repository.setBookmark(episode, !episode.bookmark) }
    }

    fun markSeen(episode: Episode) {
        if (episode.seen) return
        screenModelScope.launchIO { repository.setSeen(listOf(episode), true) }
    }

    /** Loads the videos of an episode, then shows the picker (or plays right away if there's only one). */
    fun loadVideos(episode: Episode) {
        val source = source ?: return
        videoJob?.cancel()
        mutableState.update { it.copy(videoDialog = VideoDialog.Loading(episode)) }
        videoJob = screenModelScope.launchIO {
            try {
                val videos = fetchVideos(source, episode.toSEpisode())
                mutableState.update {
                    it.copy(
                        videoDialog = if (videos.isEmpty()) {
                            VideoDialog.Empty(episode)
                        } else {
                            VideoDialog.Pick(episode, videos)
                        },
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                mutableState.update {
                    it.copy(videoDialog = VideoDialog.Failed(episode, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Finishes a video that still needs its link worked out, then hands it to [onReady]. */
    fun resolveAndPlay(episode: Episode, video: Video, onReady: (Video) -> Unit) {
        val source = source
        videoJob?.cancel()
        mutableState.update { it.copy(videoDialog = VideoDialog.Loading(episode)) }
        videoJob = screenModelScope.launchIO {
            try {
                val resolved = if (!video.initialized && source is AnimeHttpSource) {
                    source.resolveVideo(video) ?: video
                } else {
                    video
                }
                mutableState.update { it.copy(videoDialog = null) }
                if (resolved.videoUrl.isBlank() || resolved.videoUrl == "null") {
                    mutableState.update { it.copy(videoDialog = VideoDialog.Empty(episode)) }
                } else {
                    markSeen(episode)
                    repository.upsertHistory(episode.id, System.currentTimeMillis())
                    onReady(resolved)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                mutableState.update {
                    it.copy(videoDialog = VideoDialog.Failed(episode, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    fun dismissVideoDialog() {
        videoJob?.cancel()
        mutableState.update { it.copy(videoDialog = null) }
    }

    private suspend fun fetchVideos(source: AnimeSource, episode: SEpisode): List<Video> =
        AnimeVideoFetcher.fetchVideos(source, episode)

    sealed interface VideoDialog {
        val episode: Episode

        data class Loading(override val episode: Episode) : VideoDialog
        data class Pick(override val episode: Episode, val videos: List<Video>) : VideoDialog
        data class Empty(override val episode: Episode) : VideoDialog
        data class Failed(override val episode: Episode, val message: String) : VideoDialog
    }

    @Immutable
    data class DownloadInfo(val state: Download.State, val progress: Int)

    @Immutable
    data class State(
        val anime: Anime? = null,
        val episodes: List<Episode> = emptyList(),
        val seasons: List<Anime> = emptyList(),
        val sourceName: String? = null,
        val sourceMissing: Boolean = false,
        val isLoaded: Boolean = false,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val videoDialog: VideoDialog? = null,
        val seedColor: androidx.compose.ui.graphics.Color? = null,
        val allEpisodes: List<Episode> = emptyList(),
        val suggestions: List<Anime> = emptyList(),
        val suggestionsLoading: Boolean = false,
        val scanlators: Set<String> = emptySet(),
        val excludedScanlators: Set<String> = emptySet(),
        val notes: String = "",
        val downloadedIds: Set<Long> = emptySet(),
        val members: List<Anime> = emptyList(),
        val memberOrder: Map<Long, Int> = emptyMap(),
        /** Source name per merged anime id (empty when not merged) */
        val memberSources: Map<Long, String> = emptyMap(),
        val activeDownloads: Map<Long, DownloadInfo> = emptyMap(),
    ) {
        fun downloadState(episode: Episode): Download.State = activeDownloads[episode.id]?.state
            ?: if (episode.id in downloadedIds) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED

        fun downloadProgress(episode: Episode): Int = activeDownloads[episode.id]?.progress ?: 0

        val filterActive: Boolean
            get() = ((anime?.episodeFlags ?: 0L) and Anime.ALL_FILTERS) != 0L || excludedScanlators.isNotEmpty()

        /** The episode the Start/Resume button plays: the first unwatched one, oldest first */
        val nextEpisode: Episode?
            get() = allEpisodes
                .sortedWith(
                    compareBy<Episode> { memberOrder[it.animeId] ?: 0 }.thenByDescending { it.sourceOrder },
                )
                .firstOrNull { !it.seen }

        /** The anime an episode belongs to (another one when this page is merged) */
        fun animeOf(episode: Episode): Anime? =
            if (episode.animeId == anime?.id) anime else members.firstOrNull { it.id == episode.animeId } ?: anime

        val hasStarted: Boolean
            get() = allEpisodes.any { it.seen || it.lastSecondSeen > 0 }
    }

    private companion object {
        /** Anime whose bare episode list was already refreshed in this run */
        val refreshedThisRun: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())
    }
}
