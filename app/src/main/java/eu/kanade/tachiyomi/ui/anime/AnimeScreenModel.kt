package eu.kanade.tachiyomi.ui.anime

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.Episode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Details and episodes of one anime. Playback goes to an outside video player for now.
 */
class AnimeScreenModel(
    private val animeId: Long,
    private val repository: AnimeRepository = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
) : StateScreenModel<AnimeScreenModel.State>(State()) {

    var source: AnimeSource? = null
        private set

    private var refreshJob: Job? = null
    private var seedColorUrl: String? = null
    private var videoJob: Job? = null

    init {
        screenModelScope.launchIO {
            combine(repository.subscribeAnime(animeId), repository.subscribeEpisodes(animeId)) { anime, episodes ->
                anime to episodes
            }.collect { (anime, episodes) ->
                // KMK: theme color from the cover, like the manga details page
                if (anime != null && anime.thumbnailUrl != seedColorUrl) {
                    seedColorUrl = anime.thumbnailUrl
                    eu.kanade.presentation.anime.loadAnimeSeedColor(anime) { color ->
                        mutableState.update { it.copy(seedColor = color) }
                    }
                }
                val flags = anime?.episodeFlags ?: 0L
                val shown = episodes
                    .filter { (flags and Anime.FILTER_UNSEEN) == 0L || !it.seen }
                    .filter { (flags and Anime.FILTER_BOOKMARKED) == 0L || it.bookmark }
                    .filter { (flags and Anime.FILTER_HIDE_FILLER) == 0L || !it.fillermark }
                mutableState.update { state ->
                    state.copy(
                        anime = anime,
                        allEpisodes = episodes,
                        episodes = if (anime?.sortAscending == true) {
                            shown.sortedBy { it.sourceOrder }.reversed()
                        } else {
                            shown.sortedBy { it.sourceOrder }
                        },
                        isLoaded = true,
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
            mutableState.update { it.copy(sourceName = found?.name, sourceMissing = found == null) }
            loadSuggestions()
            val anime = repository.getAnime(animeId) ?: return@launchIO
            if (found != null && (!anime.initialized || anime.fetchType == FetchType.Seasons || !anime.favorite)) {
                refresh()
            } else if (found != null && repository.getEpisodes(animeId).isEmpty()) {
                refresh()
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
                val updated = repository.getAnime(animeId) ?: anime
                if (updated.fetchType == FetchType.Seasons) {
                    val seasons = source.getSeasonList(updated.toSAnime())
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

    /** Turns one episode filter on or off ([Anime.FILTER_UNSEEN], [Anime.FILTER_BOOKMARKED], [Anime.FILTER_HIDE_FILLER]) */
    fun toggleEpisodeFilter(flag: Long) {
        val anime = state.value.anime ?: return
        screenModelScope.launchIO { repository.setEpisodeFlags(anime, anime.episodeFlags xor flag) }
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

    private suspend fun fetchVideos(source: AnimeSource, episode: SEpisode): List<Video> {
        val hosters: List<Hoster> = if (source is AnimeHttpSource && source.usesHosters()) {
            try {
                source.getHosterList(episode)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Hoster list failed, trying the video list" }
                source.getVideoList(episode).toHosterList()
            }
        } else {
            source.getVideoList(episode).toHosterList()
        }
        val sortedHosters = if (source is AnimeHttpSource) with(source) { hosters.sortHosters() } else hosters
        val videos = sortedHosters.flatMap { hoster ->
            try {
                (hoster.videoList ?: source.getVideoList(hoster)).map { video ->
                    if (hoster.hosterName.isNotBlank() && hoster.hosterName != Hoster.NO_HOSTER_LIST) {
                        video.copy(videoTitle = "${hoster.hosterName} • ${video.videoTitle}")
                    } else {
                        video
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Couldn't load videos of ${hoster.hosterName}" }
                emptyList()
            }
        }
        return if (source is AnimeHttpSource) with(source) { videos.sortVideos() } else videos
    }

    /** Extensions built for the newer API override the hoster methods; older ones only have video lists. */
    private fun AnimeHttpSource.usesHosters(): Boolean {
        var cls: Class<*>? = javaClass
        while (cls != null && cls != AnimeHttpSource::class.java) {
            if (cls.declaredMethods.any { it.name == "hosterListParse" || it.name == "getHosterList" }) {
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    sealed interface VideoDialog {
        val episode: Episode

        data class Loading(override val episode: Episode) : VideoDialog
        data class Pick(override val episode: Episode, val videos: List<Video>) : VideoDialog
        data class Empty(override val episode: Episode) : VideoDialog
        data class Failed(override val episode: Episode, val message: String) : VideoDialog
    }

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
    ) {
        val filterActive: Boolean
            get() = ((anime?.episodeFlags ?: 0L) and
                (Anime.FILTER_UNSEEN or Anime.FILTER_BOOKMARKED or Anime.FILTER_HIDE_FILLER)) != 0L

        /** The episode the Start/Resume button plays: the first unwatched one, oldest first */
        val nextEpisode: Episode?
            get() = allEpisodes.sortedByDescending { it.sourceOrder }.firstOrNull { !it.seen }

        val hasStarted: Boolean
            get() = allEpisodes.any { it.seen || it.lastSecondSeen > 0 }
    }
}
