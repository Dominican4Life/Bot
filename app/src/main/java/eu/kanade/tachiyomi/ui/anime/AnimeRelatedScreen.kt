package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: Full list of related anime, opened from the "Related titles" header on the anime page. */
class AnimeRelatedScreen(private val animeId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { AnimeRelatedScreenModel(animeId) }
        val state by screenModel.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.pref_source_related_mangas),
                    subtitle = state.title,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when {
                state.items.isEmpty() && state.loading -> LoadingScreen(Modifier.padding(contentPadding))
                state.items.isEmpty() -> EmptyScreen(
                    message = stringResource(MR.strings.no_results_found),
                    modifier = Modifier.padding(contentPadding),
                )
                else -> FastScrollLazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                    horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
                ) {
                    items(state.items, key = { it.id }) { anime ->
                        MangaComfortableGridItem(
                            coverData = anime.asMangaCover(),
                            title = anime.title,
                            onClick = { navigator.push(AnimeScreen(anime.id)) },
                            onLongClick = { navigator.push(AnimeScreen(anime.id)) },
                        )
                    }
                }
            }
        }
    }
}

class AnimeRelatedScreenModel(
    private val animeId: Long,
    private val repository: AnimeRepository = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
) : StateScreenModel<AnimeRelatedScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            val anime = repository.getAnime(animeId)
            mutableState.update { it.copy(title = anime?.title) }
            val source = anime?.let { sourceManager.get(it.source) } as? AnimeCatalogueSource
            if (anime == null || source == null) {
                mutableState.update { it.copy(loading = false) }
                return@launchIO
            }
            try {
                source.getRelatedAnimeList(
                    anime = anime.toSAnime(),
                    exceptionHandler = { e -> logcat(LogPriority.WARN, e) { "Related anime failed" } },
                    pushResults = { (_, list), _ ->
                        val found = list.mapNotNull {
                            runCatching { repository.networkToLocal(it, source.id) }.getOrNull()
                        }.filter { it.id != animeId }
                        mutableState.update { state -> state.copy(items = (state.items + found).distinctBy { it.id }) }
                    },
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Related anime failed" }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    @Immutable
    data class State(
        val title: String? = null,
        val items: List<Anime> = emptyList(),
        val loading: Boolean = true,
    )
}
