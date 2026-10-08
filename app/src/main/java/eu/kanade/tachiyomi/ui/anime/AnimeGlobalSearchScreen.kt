package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.MangaItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Searches every enabled anime source at once, one row per source, like manga global search.
 * Opened with [mergeIntoId] it is the "Merge" picker: tapping a result adds it to that anime's page.
 */
class AnimeGlobalSearchScreen(
    private val initialQuery: String = "",
    private val mergeIntoId: Long? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { AnimeGlobalSearchScreenModel(initialQuery, mergeIntoId) }
        val state by screenModel.state.collectAsState()
        var query by remember { mutableStateOf<String?>(initialQuery) }
        var pendingMerge by remember { mutableStateOf<Pair<Anime, String>?>(null) }

        pendingMerge?.let { (anime, sourceName) ->
            AlertDialog(
                onDismissRequest = { pendingMerge = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingMerge = null
                            screenModel.merge(anime) { navigator.pop() }
                        },
                    ) { Text(stringResource(SYMR_MERGE)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingMerge = null }) { Text(stringResource(MR.strings.action_cancel)) }
                },
                title = { Text(stringResource(KMR.strings.anime_merge_into)) },
                text = { Text(stringResource(KMR.strings.anime_merge_into_message, anime.title, sourceName)) },
            )
        }

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    searchQuery = query,
                    onChangeSearchQuery = { query = it },
                    titleContent = { Text(stringResource(KMR.strings.anime_global_search)) },
                    navigateUp = navigator::pop,
                    placeholderText = stringResource(KMR.strings.anime_global_search),
                    onSearch = screenModel::search,
                    onClickCloseSearch = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            ScrollbarLazyColumn(contentPadding = paddingValues) {
                state.rows.forEach { row ->
                    item(key = row.source.id) {
                        GlobalSearchResultItem(
                            title = row.source.name,
                            subtitle = row.source.lang.uppercase(),
                            onClick = { navigator.push(AnimeSourceScreen(row.source.id, state.query)) },
                        ) {
                            when {
                                row.items == null -> GlobalSearchLoadingResultItem()
                                row.items.isEmpty() -> GlobalSearchErrorResultItem(
                                    message = row.error ?: stringResource(MR.strings.no_results_found),
                                )
                                else -> LazyRow(
                                    contentPadding = PaddingValues(MaterialTheme.padding.small),
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                                ) {
                                    items(row.items, key = { it.id }) { anime ->
                                        MangaItem(
                                            title = anime.title,
                                            cover = anime.asMangaCover(),
                                            isFavorite = anime.favorite,
                                            onClick = {
                                                if (mergeIntoId != null) {
                                                    pendingMerge = anime to row.source.name
                                                } else {
                                                    navigator.push(AnimeScreen(anime.id))
                                                }
                                            },
                                            onLongClick = { navigator.push(AnimeScreen(anime.id)) },
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

    private companion object {
        val SYMR_MERGE = tachiyomi.i18n.sy.SYMR.strings.merge
    }
}

class AnimeGlobalSearchScreenModel(
    initialQuery: String,
    private val mergeIntoId: Long?,
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val repository: AnimeRepository = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<AnimeGlobalSearchScreenModel.State>(State()) {

    private var searchJob: Job? = null

    init {
        if (initialQuery.isNotBlank()) search(initialQuery)
    }

    fun search(query: String) {
        searchJob?.cancel()
        val disabledLangs = sourcePreferences.animeDisabledLanguages().get()
        val disabledSources = sourcePreferences.animeDisabledSources().get()
        val pinned = sourcePreferences.animePinnedSources().get()
        val sources = sourceManager.getCatalogueSources()
            .filter { it.lang !in disabledLangs && it.id.toString() !in disabledSources }
            .sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name.lowercase() }))
        mutableState.update { State(query = query, rows = sources.map { Row(it) }) }
        val semaphore = Semaphore(5)
        searchJob = screenModelScope.launchIO {
            sources.forEach { source ->
                launch {
                    semaphore.withPermit {
                        val result = try {
                            val page = source.getSearchAnime(1, query, source.getFilterList())
                            Row(
                                source = source,
                                items = page.animes.mapNotNull {
                                    runCatching { repository.networkToLocal(it, source.id) }.getOrNull()
                                }.filter { it.id != mergeIntoId },
                            )
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            Row(source, emptyList(), e.message ?: e.javaClass.simpleName)
                        }
                        mutableState.update { state ->
                            // Sources with results first, keeping the original order otherwise
                            val rows = state.rows.map { if (it.source.id == source.id) result else it }
                            state.copy(rows = rows.sortedBy { it.items?.isEmpty() == true })
                        }
                    }
                }
            }
        }
    }

    fun merge(anime: Anime, onDone: () -> Unit) {
        val rootId = mergeIntoId ?: return
        screenModelScope.launchIO {
            repository.mergeInto(rootId, listOf(anime.id))
            withUIContext { onDone() }
        }
    }

    @Immutable
    data class Row(
        val source: AnimeCatalogueSource,
        val items: List<Anime>? = null,
        val error: String? = null,
    )

    @Immutable
    data class State(
        val query: String = "",
        val rows: List<Row> = emptyList(),
    )
}
