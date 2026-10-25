package eu.kanade.tachiyomi.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.anime.AnimeEpisodePanel
import eu.kanade.presentation.anime.AnimePlayerPanel
import eu.kanade.presentation.browse.components.PreviewPanelButtons
import eu.kanade.presentation.reader.sidePanelWidth
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.ui.anime.AnimeGlobalSearchScreen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreenModel
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.presentation.core.util.collectAsState as collectPrefAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: The anime Browse preview panels in the anime library: episodes on the left, the player on the right. With
 * the Left / Right Panel buttons (right of the category row) on, the first tap on a cover previews it.
 */
class AnimeLibraryPreviewModel(
    private val repository: AnimeRepository = Injekt.get(),
    private val sources: AnimeSourceManager = Injekt.get(),
) : ScreenModel {

    private val _preview = MutableStateFlow(AnimeSourceScreenModel.Preview())
    val preview: StateFlow<AnimeSourceScreenModel.Preview> = _preview
    private var previewJob: Job? = null
    private var refreshJob: Job? = null

    fun sourceOf(anime: Anime?) = anime?.let { sources.get(it.source) }

    fun select(anime: Anime, showEpisodes: Boolean, showPlayer: Boolean) {
        previewJob?.cancel()
        refreshJob?.cancel()
        _preview.value = AnimeSourceScreenModel.Preview(
            anime = anime,
            panelOpen = showEpisodes,
            playerPanelOpen = showPlayer,
            loading = true,
        )
        eu.kanade.presentation.anime.loadAnimeSeedColor(anime) { color ->
            _preview.update { if (it.anime?.id == anime.id) it.copy(seedColor = color) else it }
        }
        previewJob = screenModelScope.launchIO {
            combine(repository.subscribeAnime(anime.id), repository.subscribeEpisodes(anime.id)) { a, eps -> a to eps }
                .collect { (a, eps) ->
                    _preview.update {
                        it.copy(
                            anime = a ?: it.anime,
                            episodes = if (a?.sortAscending == true) {
                                eps.sortedByDescending { e -> e.sourceOrder }
                            } else {
                                eps.sortedBy { e -> e.sourceOrder }
                            },
                        )
                    }
                }
        }
        val source = sources.get(anime.source)
        refreshJob = screenModelScope.launchIO {
            try {
                val current = repository.getAnime(anime.id) ?: anime
                if (source != null && (!current.initialized || repository.getEpisodes(anime.id).isEmpty())) {
                    repository.refreshFromSource(current, source)
                }
                _preview.update { if (it.anime?.id == anime.id) it.copy(loading = false) else it }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _preview.update {
                    if (it.anime?.id == anime.id) it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) else it
                }
            }
        }
    }

    fun clear() {
        previewJob?.cancel()
        refreshJob?.cancel()
        _preview.value = AnimeSourceScreenModel.Preview()
    }

    fun closeEpisodePanel() {
        _preview.update { it.copy(panelOpen = false) }
        if (!_preview.value.playerPanelOpen) clear()
    }

    fun closePlayerPanel() {
        _preview.update { it.copy(playerPanelOpen = false) }
        if (!_preview.value.panelOpen) clear()
    }

    fun openEpisodePanel() {
        if (_preview.value.anime != null) _preview.update { it.copy(panelOpen = true) }
    }

    fun openPlayerPanel() {
        if (_preview.value.anime != null) _preview.update { it.copy(playerPanelOpen = true) }
    }

    fun toggleFavorite(anime: Anime) {
        screenModelScope.launchIO {
            val current = repository.getAnime(anime.id) ?: anime
            repository.setFavorite(current, !current.favorite)
        }
    }

    fun toggleSeen(episode: Episode) {
        screenModelScope.launchIO { repository.setSeen(listOf(episode), !episode.seen) }
    }

    fun toggleBookmark(episode: Episode) {
        screenModelScope.launchIO { repository.setBookmark(episode, !episode.bookmark) }
    }

    fun toggleEpisodeSort() {
        val anime = _preview.value.anime ?: return
        screenModelScope.launchIO {
            val current = repository.getAnime(anime.id) ?: return@launchIO
            repository.setEpisodeFlags(current, current.episodeFlags xor Anime.SORT_ASCENDING)
        }
    }

    fun savePosition(episodeId: Long, positionMs: Long, then: () -> Unit) {
        screenModelScope.launchIO {
            if (positionMs > 0) repository.updateEpisode(episodeId, lastSecondSeen = positionMs)
            withUIContext { then() }
        }
    }

    /** True when the tap previewed the cover; false when it should open the anime as before */
    fun onCoverClick(anime: Anime): Boolean {
        val panels = Injekt.get<SourcePreferences>().libraryPreviewPanels().get()
        if (panels == SourcePreferences.PREVIEW_PANELS_NONE) return false
        if (_preview.value.anime?.id == anime.id) return false
        select(
            anime,
            showEpisodes = SourcePreferences.previewLeftOn(panels),
            showPlayer = SourcePreferences.previewRightOn(panels),
        )
        return true
    }
}

/** The Left Panel / Right Panel buttons for the anime category row */
@Composable
fun AnimeLibraryPreviewModel.Buttons(scope: RowScope) {
    val pref = remember { Injekt.get<SourcePreferences>().libraryPreviewPanels() }
    val panels by pref.collectPrefAsState()
    with(scope) {
        PreviewPanelButtons(
            leftOn = SourcePreferences.previewLeftOn(panels),
            rightOn = SourcePreferences.previewRightOn(panels),
            onLeftChange = { on ->
                pref.set(SourcePreferences.previewPanels(left = on, right = SourcePreferences.previewRightOn(panels)))
                if (on) openEpisodePanel() else closeEpisodePanel()
            },
            onRightChange = { on ->
                pref.set(SourcePreferences.previewPanels(left = SourcePreferences.previewLeftOn(panels), right = on))
                if (on) openPlayerPanel() else closePlayerPanel()
            },
        )
    }
}

/** How much room the open panels take on each side */
@Composable
fun AnimeLibraryPreviewModel.panelPadding(maxWidth: Dp): Pair<Dp, Dp> {
    val state by preview.collectAsState()
    val width = sidePanelWidth(maxWidth)
    val start by animateDpAsState(if (state.panelOpen) width else 0.dp, label = "animeLibraryPanelStart")
    val end by animateDpAsState(if (state.playerPanelOpen) width else 0.dp, label = "animeLibraryPanelEnd")
    return start to end
}

/** The two panels, drawn over the covers */
@Composable
fun AnimeLibraryPreviewModel.Panels(scope: BoxScope) {
    val state by preview.collectAsState()
    val anime = state.anime ?: return
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val source = remember(anime.source) { sourceOf(anime) }
    var chosenEpisode by remember(anime.id) { mutableStateOf<Episode?>(null) }
    var playToken by remember(anime.id) { mutableIntStateOf(0) }
    with(scope) {
        AnimeEpisodePanel(
            preview = state,
            sourceName = source?.name.orEmpty(),
            onClose = ::closeEpisodePanel,
            onAddToLibrary = ::toggleFavorite,
            onWebView = { a ->
                val url = (source as? AnimeHttpSource)?.let { http ->
                    runCatching { http.getAnimeUrl(a.toSAnime()) }.getOrElse { http.baseUrl + a.url }
                } ?: (source as? eu.kanade.tachiyomi.jssource.JsAnimeSource)?.getAnimeUrl(a.toSAnime())
                if (url != null) navigator.push(WebViewScreen(url, a.title))
            },
            onOpenDetails = { navigator.push(AnimeScreen(it.id)) },
            onSearch = { query -> navigator.push(AnimeGlobalSearchScreen(query)) },
            onToggleSort = ::toggleEpisodeSort,
            onPlay = { episode -> context.startActivity(PlayerActivity.newIntent(context, episode.animeId, episode.id)) },
            onToggleSeen = ::toggleSeen,
            onToggleBookmark = ::toggleBookmark,
            selectedEpisodeId = chosenEpisode?.id,
            onSelectEpisode = { episode ->
                chosenEpisode = episode
                playToken++
                openPlayerPanel()
            },
        )
        AnimePlayerPanel(
            preview = state,
            source = source,
            episode = chosenEpisode ?: state.episodes.firstOrNull(),
            playToken = playToken,
            onClose = ::closePlayerPanel,
            onOpenPlayer = { episode, positionMs ->
                savePosition(episode.id, positionMs) {
                    context.startActivity(PlayerActivity.newIntent(context, episode.animeId, episode.id))
                }
            },
        )
    }
}
