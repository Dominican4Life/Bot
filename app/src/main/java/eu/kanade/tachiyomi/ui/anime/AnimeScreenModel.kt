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
) : StateScreenModel<AnimeScreenModel.State>(State()) {

    var source: AnimeSource? = null
        private set

    // KMK: anime rows have no notes or scanlator columns yet, so these live in preferences
    private val preferenceStore: PreferenceStore = Injekt.get()
    private val excludedScanlatorsPref = preferenceStore.getStringSet("anime_excluded_scanlators_$animeId", emptySet())
    private val notesPref = preferenceStore.getString("anime_notes_$animeId", "")

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
                // Downloads aren't merged yet, so nothing counts as downloaded
                anime.downloadedFilter.keep(false) &&
                (ep.scanlator.isNullOrBlank() || ep.scanlator !in excluded)
        }
        // "Descending" is the default: newest first
        val descending = !anime.sortAscending
        val sorted = when (anime.sortMode) {
            Anime.SORT_BY_NUMBER -> shown.sortedBy { it.episodeNumber }.let { if (descending) it.reversed() else it }
            Anime.SORT_BY_UPLOAD_DATE -> shown.sortedBy { it.dateUpload }.let { if (descending) it.reversed() else it }
            Anime.SORT_BY_ALPHABET -> shown.sortedBy { it.name.lowercase() }.let { if (descending) it.reversed() else it }
            else -> shown.sortedBy { it.sourceOrder }.let { if (descending) it else it.reversed() }
        }
        return sorted
    }

    private var refreshJob: Job? = null
    private var seedColorUrl: String? = null
    private var videoJob: Job? = null

    init {
        screenModelScope.launchIO {
            combine(
                repository.subscribeAnime(animeId),
                repository.subscribeEpisodes(animeId),
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
            // KMK: same "Name (flag)" label as manga sources
            val label = found?.let { src -> "${src.name} (${FlagEmoji.getEmojiLangFlag(src.lang)})" }
            mutableState.update { it.copy(sourceName = label, sourceMissing = found == null) }
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

    /** "Clear anime": removes the saved episodes (and their watch history), then fetches the list again */
    fun clearAnime() {
        screenModelScope.launchIO {
            repository.deleteEpisodes(animeId)
            refresh()
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
        val scanlators: Set<String> = emptySet(),
        val excludedScanlators: Set<String> = emptySet(),
        val notes: String = "",
    ) {
        val filterActive: Boolean
            get() = ((anime?.episodeFlags ?: 0L) and Anime.ALL_FILTERS) != 0L || excludedScanlators.isNotEmpty()

        /** The episode the Start/Resume button plays: the first unwatched one, oldest first */
        val nextEpisode: Episode?
            get() = allEpisodes.sortedByDescending { it.sourceOrder }.firstOrNull { !it.seen }

        val hasStarted: Boolean
            get() = allEpisodes.any { it.seen || it.lastSecondSeen > 0 }
    }
}
