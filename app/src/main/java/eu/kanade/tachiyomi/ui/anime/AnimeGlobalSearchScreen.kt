package eu.kanade.tachiyomi.ui.anime

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.browse.components.BrowseTabChip
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.MangaItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.swipeBetweenTabs
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.ui.anime.anilist.AniListAnimeScreen
import eu.kanade.tachiyomi.ui.anime.anilist.AniListChipBar
import eu.kanade.tachiyomi.ui.anime.anilist.AniListFilterPanel
import eu.kanade.tachiyomi.ui.anime.anilist.AniListFilterTab
import eu.kanade.tachiyomi.ui.anime.anilist.AniListFilterTabRow
import eu.kanade.tachiyomi.ui.anime.anilist.AniListNoticeHost
import eu.kanade.tachiyomi.ui.anime.anilist.AniListResults
import eu.kanade.tachiyomi.ui.anime.anilist.AniListSearchModel
import eu.kanade.tachiyomi.ui.anime.anilist.AniListTabChip
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
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
 *
 * Tabs like the Browse buttons: Pinned, All and the globe (AniList, searched like Shiru and Hayase), with the
 * screenshot search at the end of the row. Swiping left or right moves between them.
 */
class AnimeGlobalSearchScreen(
    private val initialQuery: String = "",
    private val mergeIntoId: Long? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { AnimeGlobalSearchScreenModel(initialQuery, mergeIntoId) }
        val state by screenModel.state.collectAsState()
        val aniList = screenModel.aniList
        var query by remember { mutableStateOf<String?>(initialQuery) }
        var pendingMerge by remember { mutableStateOf<Pair<Anime, String>?>(null) }
        var openFilter by rememberSaveable { mutableStateOf<AniListFilterTab?>(null) }

        // The tabs: Pinned (when some sources are pinned), All and AniList (not when merging)
        val tabs = remember(state.hasPinned) {
            buildList {
                if (state.hasPinned) add(SearchTab.PINNED)
                add(SearchTab.ALL)
                if (mergeIntoId == null) add(SearchTab.ANILIST)
            }
        }
        val tab = state.tab.takeIf { it in tabs } ?: SearchTab.ALL
        val selectTab: (SearchTab) -> Unit = { screenModel.selectTab(it, query.orEmpty()) }

        val pickScreenshot = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            screenModel.selectTab(SearchTab.ANILIST, query.orEmpty())
            scope.launch {
                val bytes = withIOContext {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                } ?: return@launch
                aniList.searchScreenshot(bytes, context.contentResolver.getType(uri))
            }
        }

        LaunchedEffect(tab) {
            if (tab == SearchTab.ANILIST) aniList.start(query.orEmpty())
        }

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
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    Box {
                        SearchToolbar(
                            searchQuery = query,
                            onChangeSearchQuery = { query = it },
                            titleContent = { Text(stringResource(KMR.strings.anime_global_search)) },
                            navigateUp = navigator::pop,
                            placeholderText = stringResource(KMR.strings.anime_global_search),
                            onSearch = { screenModel.submit(it) },
                            onClickCloseSearch = navigator::pop,
                            scrollBehavior = scrollBehavior,
                        )
                        val progress = state.rows.count { it.items != null }
                        if (tab != SearchTab.ANILIST && progress in 1..<state.rows.size) {
                            LinearProgressIndicator(
                                progress = { progress / state.rows.size.toFloat() },
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.padding.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            tabs.forEach { item ->
                                when (item) {
                                    SearchTab.PINNED -> BrowseTabChip(
                                        selected = tab == item,
                                        label = stringResource(MR.strings.pinned_sources),
                                        icon = Icons.Outlined.PushPin,
                                        onClick = { selectTab(item) },
                                    )
                                    SearchTab.ALL -> BrowseTabChip(
                                        selected = tab == item,
                                        label = stringResource(MR.strings.all),
                                        icon = Icons.Outlined.DoneAll,
                                        onClick = { selectTab(item) },
                                    )
                                    SearchTab.ANILIST -> BrowseTabChip(
                                        selected = tab == item,
                                        label = stringResource(KMR.strings.anilist_tab),
                                        icon = Icons.Outlined.Public,
                                        filledIcon = Icons.Filled.Public,
                                        onClick = { selectTab(item) },
                                    )
                                }
                            }
                            if (tab != SearchTab.ANILIST) {
                                VerticalDivider(modifier = Modifier.height(24.dp))
                                BrowseTabChip(
                                    selected = state.onlyShowHasResults,
                                    label = stringResource(MR.strings.has_results),
                                    icon = Icons.Outlined.FilterList,
                                    onClick = screenModel::toggleHasResults,
                                )
                            }
                        }
                        // Screenshot search (trace.moe), at the end of the row like Shiru
                        if (mergeIntoId == null) {
                            AniListTabChip(
                                selected = false,
                                label = stringResource(KMR.strings.anilist_screenshot_search),
                                icon = Icons.Outlined.ImageSearch,
                                onClick = {
                                    pickScreenshot.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    if (tab == SearchTab.ANILIST) {
                        AniListFilterTabRow(
                            open = openFilter,
                            onOpen = { openFilter = it },
                            onRefreshGenres = { aniList.refreshGenres() },
                        )
                        AniListChipBar(model = aniList)
                    }
                    HorizontalDivider()
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .swipeBetweenTabs(
                        count = tabs.size,
                        selected = tabs.indexOf(tab),
                        onSelect = { selectTab(tabs[it]) },
                    ),
            ) {
                if (tab == SearchTab.ANILIST) {
                    Box(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
                        AniListResults(
                            model = aniList,
                            onClick = { navigator.push(AniListAnimeScreen(it.id)) },
                            contentPadding = PaddingValues(bottom = paddingValues.calculateBottomPadding()),
                        )
                        AniListFilterPanel(open = openFilter, model = aniList, onOpen = { openFilter = it })
                    }
                } else {
                    SourceRows(
                        state = state,
                        paddingValues = paddingValues,
                        onOpenSource = { navigator.push(AnimeSourceScreen(it.id, state.query)) },
                        onClickAnime = { anime, sourceName ->
                            if (mergeIntoId != null) {
                                pendingMerge = anime to sourceName
                            } else {
                                navigator.push(AnimeScreen(anime.id))
                            }
                        },
                        onLongClickAnime = { navigator.push(AnimeScreen(it.id)) },
                    )
                }
                AniListNoticeHost(
                    notice = aniList.notice,
                    onDismiss = aniList::dismissNotice,
                    modifier = Modifier.padding(top = paddingValues.calculateTopPadding()),
                )
            }
        }
    }

    @Composable
    private fun SourceRows(
        state: AnimeGlobalSearchScreenModel.State,
        paddingValues: PaddingValues,
        onOpenSource: (AnimeCatalogueSource) -> Unit,
        onClickAnime: (Anime, String) -> Unit,
        onLongClickAnime: (Anime) -> Unit,
    ) {
        val rows = if (state.onlyShowHasResults) state.rows.filter { it.items == null || it.items.isNotEmpty() } else state.rows
        ScrollbarLazyColumn(contentPadding = paddingValues) {
            rows.forEach { row ->
                item(key = row.source.id) {
                    GlobalSearchResultItem(
                        title = row.source.name,
                        subtitle = row.source.lang.uppercase(),
                        onClick = { onOpenSource(row.source) },
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
                                        onClick = { onClickAnime(anime, row.source.name) },
                                        onLongClick = { onLongClickAnime(anime) },
                                    )
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

/** KMK: The tabs of anime global search */
enum class SearchTab { PINNED, ALL, ANILIST }

class AnimeGlobalSearchScreenModel(
    initialQuery: String,
    private val mergeIntoId: Long?,
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val repository: AnimeRepository = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    preferenceStore: PreferenceStore = Injekt.get(),
) : StateScreenModel<AnimeGlobalSearchScreenModel.State>(State()) {

    private var searchJob: Job? = null
    private val tabPref = preferenceStore.getString("anime_global_search_tab", SearchTab.ALL.name)

    /** The globe tab's AniList search, kept here so it stays when coming back from a title */
    val aniList = AniListSearchModel(screenModelScope)

    /** What was last searched with the search button, for the tab that's opened next */
    private var submitted = initialQuery

    init {
        val hasPinned = sourcePreferences.animePinnedSources().get().isNotEmpty()
        val savedTab = runCatching { SearchTab.valueOf(tabPref.get()) }.getOrDefault(SearchTab.ALL)
        val tab = when {
            mergeIntoId != null -> SearchTab.ALL
            savedTab == SearchTab.PINNED && !hasPinned -> SearchTab.ALL
            else -> savedTab
        }
        mutableState.update { it.copy(tab = tab, hasPinned = hasPinned) }
        if (tab != SearchTab.ANILIST && initialQuery.isNotBlank()) search(initialQuery)
    }

    fun submit(query: String) {
        submitted = query
        if (state.value.tab == SearchTab.ANILIST) aniList.search(query) else search(query)
    }

    fun selectTab(tab: SearchTab, typed: String) {
        val previous = state.value.tab
        if (typed.isNotBlank()) submitted = typed
        mutableState.update { it.copy(tab = tab) }
        if (mergeIntoId == null) tabPref.set(tab.name)
        when {
            tab == SearchTab.ANILIST -> if (aniList.query != submitted && aniList.traceMatches == null) aniList.search(submitted)
            submitted.isBlank() -> {}
            // Pinned and All search different sources
            previous != tab || state.value.query != submitted -> search(submitted)
        }
    }

    fun toggleHasResults() {
        mutableState.update { it.copy(onlyShowHasResults = !it.onlyShowHasResults) }
    }

    fun search(query: String) {
        searchJob?.cancel()
        val disabledLangs = sourcePreferences.animeDisabledLanguages().get()
        val disabledSources = sourcePreferences.animeDisabledSources().get()
        val pinned = sourcePreferences.animePinnedSources().get()
        val pinnedOnly = state.value.tab == SearchTab.PINNED
        val sources = sourceManager.getCatalogueSources()
            .filter { it.lang !in disabledLangs && it.id.toString() !in disabledSources }
            .filter { !pinnedOnly || it.id.toString() in pinned }
            .sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name.lowercase() }))
        mutableState.update { it.copy(query = query, rows = sources.map { source -> Row(source) }) }
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
        val tab: SearchTab = SearchTab.ALL,
        val hasPinned: Boolean = false,
        val onlyShowHasResults: Boolean = false,
    )
}
