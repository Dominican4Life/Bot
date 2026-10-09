package eu.kanade.tachiyomi.ui.anime.source

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.browse.SourceFeedToolbar
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.MangaItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: The source home page for anime sources, like manga's: a Latest row and a Browse (popular) row.
 * Used when "Replace latest button" is on.
 */
class AnimeSourceFeedScreen(private val sourceId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val haptic = LocalHapticFeedback.current
        val screenModel = rememberScreenModel { AnimeSourceFeedScreenModel(sourceId) }
        val state by screenModel.state.collectAsState()
        val source = screenModel.source
        var searchQuery by remember { mutableStateOf<String?>(null) }

        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
            topBar = { scrollBehavior ->
                SourceFeedToolbar(
                    title = source?.name ?: sourceId.toString(),
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    scrollBehavior = scrollBehavior,
                    onClickSearch = { navigator.push(AnimeSourceScreen(sourceId, initialQuery = it)) },
                    navigateUp = {
                        if (searchQuery != null) searchQuery = null else navigator.pop()
                    },
                    onWebViewClick = (source as? AnimeHttpSource)?.let { http ->
                        { navigator.push(WebViewScreen(http.baseUrl, http.name)) }
                    } ?: (source as? eu.kanade.tachiyomi.sumizuri.SumizuriAnimeSource)?.let { js ->
                        { navigator.push(WebViewScreen(js.baseUrl, js.name)) }
                    },
                    onToggleIncognito = {},
                    onSourceSettingClick = {
                        navigator.push(SourcePreferencesScreen(sourceId, anime = true))
                    }.takeIf { source is ConfigurableAnimeSource },
                    onSortFeedClick = null,
                    toggleSelectionMode = {},
                    isRunning = false,
                    showBulkSelection = false,
                    showIncognito = false,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.action_filter)) },
                    icon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                    onClick = { navigator.push(AnimeSourceScreen(sourceId, openFilters = true)) },
                )
            },
        ) { paddingValues ->
            ScrollbarLazyColumn(contentPadding = paddingValues) {
                state.rows.forEach { row ->
                    item(key = row.key) {
                        GlobalSearchResultItem(
                            title = stringResource(if (row.latest) MR.strings.latest else MR.strings.browse),
                            subtitle = null,
                            onClick = {
                                navigator.push(AnimeSourceScreen(sourceId, initialLatest = row.latest))
                            },
                        ) {
                            when {
                                row.items == null -> GlobalSearchLoadingResultItem()
                                row.items.isEmpty() -> GlobalSearchErrorResultItem(
                                    message = row.error ?: stringResource(MR.strings.no_results_found),
                                )
                                else -> LazyRow(
                                    contentPadding = PaddingValues(MaterialTheme.padding.small),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                        MaterialTheme.padding.extraSmall,
                                    ),
                                ) {
                                    items(row.items, key = { it.id }) { anime ->
                                        MangaItem(
                                            title = anime.title,
                                            cover = anime.asMangaCover(),
                                            isFavorite = anime.favorite,
                                            onClick = { navigator.push(AnimeScreen(anime.id)) },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                screenModel.toggleFavorite(anime)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class AnimeSourceFeedScreenModel(
    sourceId: Long,
    private val repository: AnimeRepository = Injekt.get(),
    sourceManager: AnimeSourceManager = Injekt.get(),
) : StateScreenModel<AnimeSourceFeedScreenModel.State>(State()) {

    val source: AnimeCatalogueSource? = sourceManager.get(sourceId) as? AnimeCatalogueSource

    init {
        val source = source
        if (source != null) {
            val rows = buildList {
                if (source.supportsLatest) add(Row(key = "latest", latest = true))
                add(Row(key = "browse", latest = false))
            }
            mutableState.update { it.copy(rows = rows) }
            rows.forEach { row -> load(source, row) }
        }
    }

    private fun load(source: AnimeCatalogueSource, row: Row) {
        screenModelScope.launchIO {
            val result = try {
                val page = if (row.latest) source.getLatestUpdates(1) else source.getPopularAnime(1)
                page.animes.map { repository.networkToLocal(it, source.id) }.distinctBy { it.id } to null
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                emptyList<Anime>() to (e.message ?: e.javaClass.simpleName)
            }
            mutableState.update { state ->
                state.copy(
                    rows = state.rows.map {
                        if (it.key == row.key) it.copy(items = result.first, error = result.second) else it
                    },
                )
            }
        }
    }

    fun toggleFavorite(anime: Anime) {
        screenModelScope.launchIO {
            val current = repository.getAnime(anime.id) ?: anime
            repository.setFavorite(current, !current.favorite)
            val updated = repository.getAnime(anime.id) ?: return@launchIO
            mutableState.update { state ->
                state.copy(
                    rows = state.rows.map { row ->
                        row.copy(items = row.items?.map { if (it.id == updated.id) updated else it })
                    },
                )
            }
        }
    }

    @Immutable
    data class Row(
        val key: String,
        val latest: Boolean,
        val items: List<Anime>? = null,
        val error: String? = null,
    )

    @Immutable
    data class State(val rows: List<Row> = emptyList())
}
