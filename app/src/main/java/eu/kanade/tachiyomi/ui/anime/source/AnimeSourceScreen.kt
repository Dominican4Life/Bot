package eu.kanade.tachiyomi.ui.anime.source

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.anime.AnimeFilterDialog
import eu.kanade.presentation.anime.AnimeGridItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Browse one anime source (popular, latest, search and filters).
 */
class AnimeSourceScreen(
    private val sourceId: Long,
    private val initialQuery: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { AnimeSourceScreenModel(sourceId) }
        val state by screenModel.state.collectAsState()
        val source = screenModel.source

        var searchQuery by rememberSaveable { mutableStateOf(initialQuery) }
        var showFilters by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (!initialQuery.isNullOrBlank()) screenModel.search(initialQuery)
        }

        val preferences = remember { Injekt.get<SourcePreferences>() }
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val columnsPref = remember(isLandscape) {
            if (isLandscape) preferences.browseLandscapeColumns() else preferences.browsePortraitColumns()
        }
        val columns by columnsPref.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                Column {
                    SearchToolbar(
                        searchQuery = searchQuery,
                        onChangeSearchQuery = { searchQuery = it },
                        titleContent = {
                            Text(text = source?.name ?: sourceId.toString())
                        },
                        navigateUp = navigator::pop,
                        placeholderText = stringResource(MR.strings.action_search_hint),
                        onSearch = { screenModel.search(it) },
                        onClickCloseSearch = {
                            searchQuery = null
                            screenModel.setListing(AnimeSourceScreenModel.Listing.Popular)
                        },
                        actions = {
                            if (source is AnimeHttpSource) {
                                IconButton(
                                    onClick = {
                                        navigator.push(WebViewScreen(source.baseUrl, source.name, source.id))
                                    },
                                ) {
                                    Icon(Icons.Outlined.Public, contentDescription = stringResource(MR.strings.action_web_view))
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                    ) {
                        FilterChip(
                            selected = state.listing == AnimeSourceScreenModel.Listing.Popular,
                            onClick = {
                                searchQuery = null
                                screenModel.setListing(AnimeSourceScreenModel.Listing.Popular)
                            },
                            label = { Text(stringResource(MR.strings.popular)) },
                        )
                        if (source?.supportsLatest == true) {
                            FilterChip(
                                selected = state.listing == AnimeSourceScreenModel.Listing.Latest,
                                onClick = {
                                    searchQuery = null
                                    screenModel.setListing(AnimeSourceScreenModel.Listing.Latest)
                                },
                                label = { Text(stringResource(MR.strings.latest)) },
                            )
                        }
                        if (state.filters.list.isNotEmpty()) {
                            FilterChip(
                                selected = state.listing is AnimeSourceScreenModel.Listing.Search,
                                onClick = { showFilters = true },
                                leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                                label = { Text(stringResource(MR.strings.action_filter)) },
                            )
                        }
                    }
                }
            },
        ) { contentPadding ->
            when {
                source == null -> EmptyScreen(
                    message = stringResource(MR.strings.source_not_installed, sourceId.toString()),
                    modifier = Modifier.padding(contentPadding),
                )
                state.items.isEmpty() && state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                state.items.isEmpty() && state.error != null -> ErrorWithRetry(
                    message = state.error!!,
                    onRetry = screenModel::retry,
                    contentPadding = contentPadding,
                )
                state.items.isEmpty() && !state.hasNextPage -> EmptyScreen(
                    message = stringResource(MR.strings.no_results_found),
                    modifier = Modifier.padding(contentPadding),
                )
                else -> {
                    val gridState = rememberLazyGridState()
                    val shouldLoadMore by remember {
                        derivedStateOf {
                            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            last >= gridState.layoutInfo.totalItemsCount - 6
                        }
                    }
                    LaunchedEffect(shouldLoadMore, state.items.size) {
                        if (shouldLoadMore) screenModel.loadNextPage()
                    }
                    FastScrollLazyVerticalGrid(
                        columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(128.dp),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = contentPadding.calculateTopPadding() + 8.dp,
                            bottom = contentPadding.calculateBottomPadding() + 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.items, key = { it.id }) { anime ->
                            AnimeGridItem(
                                anime = anime,
                                source = source,
                                onClick = { navigator.push(AnimeScreen(anime.id)) },
                            )
                        }
                        if (state.isLoading) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.CircularProgressIndicator()
                                }
                            }
                        }
                        val error = state.error
                        if (error != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                ) {
                                    Text(error)
                                    TextButton(onClick = screenModel::retry) {
                                        Text(stringResource(MR.strings.action_retry))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showFilters) {
            AnimeFilterDialog(
                filters = state.filters,
                onDismissRequest = { showFilters = false },
                onReset = screenModel::resetFilters,
                onFilter = { screenModel.search(searchQuery.orEmpty()) },
            )
        }
    }
}

@Composable
private fun ErrorWithRetry(message: String, onRetry: () -> Unit, contentPadding: PaddingValues) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
    ) {
        Text(message)
        TextButton(onClick = onRetry) { Text(stringResource(MR.strings.action_retry)) }
    }
}
