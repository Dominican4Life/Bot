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
import eu.kanade.tachiyomi.ui.anime.anilist.AniListSearchPane
import eu.kanade.tachiyomi.ui.anime.anilist.GlobalSearchMode
import eu.kanade.tachiyomi.ui.anime.anilist.GlobalSearchModeRow
import eu.kanade.tachiyomi.ui.anime.anilist.swipeBetweenModes
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

        val mode = state.mode
        val selectMode: (GlobalSearchMode) -> Unit = { screenModel.selectMode(it, query.orEmpty()) }

        val pickScreenshot = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            screenModel.selectMode(GlobalSearchMode.ANILIST, query.orEmpty())
            scope.launch {
                val bytes = withIOContext {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                } ?: return@launch
                aniList.searchScreenshot(bytes, context.contentResolver.getType(uri))
            }
        }

        LaunchedEffect(mode) {
            if (mode == GlobalSearchMode.ANILIST) aniList.start(query.orEmpty())
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

        Box(modifier = Modifier.fillMaxSize()) {
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
                            if (mode == GlobalSearchMode.RESULTS && progress in 1..<state.rows.size) {
                                LinearProgressIndicator(
                                    progress = { progress / state.rows.size.toFloat() },
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth(),
                                )
                            }
                        }
                        GlobalSearchModeRow(
                            mode = mode,
                            onMode = selectMode,
                            pinnedOnly = state.pinnedOnly.takeIf { state.hasPinned },
                            onPinnedOnly = { screenModel.setPinnedOnly(it) },
                            onlyShowHasResults = state.onlyShowHasResults,
                            onToggleHasResults = screenModel::toggleHasResults,
                            openFilter = openFilter,
                            onOpenFilter = { openFilter = it },
                            onRefreshGenres = { aniList.refreshGenres() },
                            onScreenshot = {
                                pickScreenshot.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            showAniList = mergeIntoId == null,
                            onRefreshAll = screenModel::refreshAll,
                        )
                        if (mode == GlobalSearchMode.ANILIST) AniListChipBar(model = aniList)
                        HorizontalDivider()
                    }
                },
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .swipeBetweenModes(mode, selectMode, enabled = mergeIntoId == null),
                ) {
                    if (mode == GlobalSearchMode.ANILIST) {
                        AniListSearchPane(
                            model = aniList,
                            openFilter = openFilter,
                            onOpenFilter = { openFilter = it },
                            onClick = { navigator.push(AniListAnimeScreen(it.id)) },
                            modifier = Modifier.padding(top = paddingValues.calculateTopPadding()),
                            bottomPadding = paddingValues.calculateBottomPadding(),
                        )
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
                            onRefreshSource = screenModel::refreshSource,
                        )
                    }
                }
            }
            // Shiru's toast, over everything at the top right
            AniListNoticeHost(notice = aniList.notice, onDismiss = aniList::dismissNotice)
        }
    }

    @Composable
    private fun SourceRows(
        state: AnimeGlobalSearchScreenModel.State,
        paddingValues: PaddingValues,
        onOpenSource: (AnimeCatalogueSource) -> Unit,
        onClickAnime: (Anime, String) -> Unit,
        onLongClickAnime: (Anime) -> Unit,
        onRefreshSource: (AnimeCatalogueSource) -> Unit,
    ) {
        val rows = if (state.onlyShowHasResults) state.rows.filter { it.items == null || it.items.isNotEmpty() } else state.rows
        ScrollbarLazyColumn(contentPadding = paddingValues) {
            rows.forEach { row ->
                item(key = row.source.id) {
                    GlobalSearchResultItem(
                        title = row.source.name,
                        subtitle = row.source.lang.uppercase(),
                        onClick = { onOpenSource(row.source) },
                        // Holding a source's name searches it again
                        onLongClick = { onRefreshSource(row.source) },
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

class AnimeGlobalSearchScreenModel(
    initialQuery: String,
    private val mergeIntoId: Long?,
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val repository: AnimeRepository = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    preferenceStore: PreferenceStore = Injekt.get(),
) : StateScreenModel<AnimeGlobalSearchScreenModel.State>(State()) {

    private var searchJob: Job? = null
    private val modePref = preferenceStore.getString("anime_global_search_mode", GlobalSearchMode.RESULTS.name)
    private val pinnedPref = preferenceStore.getBoolean("anime_global_search_pinned_only", false)

    /** The AniList tab's search, kept here so it stays when coming back from a title */
    val aniList = AniListSearchModel(screenModelScope)

    /** What was last searched with the search button, for the tab that's opened next */
    private var submitted = initialQuery

    init {
        val hasPinned = sourcePreferences.animePinnedSources().get().isNotEmpty()
        val savedMode = runCatching { GlobalSearchMode.valueOf(modePref.get()) }.getOrDefault(GlobalSearchMode.RESULTS)
        val mode = if (mergeIntoId != null) GlobalSearchMode.RESULTS else savedMode
        mutableState.update {
            it.copy(mode = mode, hasPinned = hasPinned, pinnedOnly = hasPinned && pinnedPref.get() && mergeIntoId == null)
        }
        if (mode == GlobalSearchMode.RESULTS && initialQuery.isNotBlank()) search(initialQuery)
    }

    fun submit(query: String) {
        submitted = query
        if (state.value.mode == GlobalSearchMode.ANILIST) aniList.search(query) else search(query)
    }

    fun selectMode(mode: GlobalSearchMode, typed: String) {
        if (typed.isNotBlank()) submitted = typed
        mutableState.update { it.copy(mode = mode) }
        if (mergeIntoId == null) modePref.set(mode.name)
        when {
            mode == GlobalSearchMode.ANILIST -> {
                if (aniList.query != submitted && aniList.traceMatches == null) aniList.search(submitted)
            }
            submitted.isNotBlank() && state.value.query != submitted -> search(submitted)
        }
    }

    fun setPinnedOnly(pinnedOnly: Boolean) {
        if (state.value.pinnedOnly == pinnedOnly) return
        mutableState.update { it.copy(pinnedOnly = pinnedOnly) }
        if (mergeIntoId == null) pinnedPref.set(pinnedOnly)
        if (submitted.isNotBlank()) search(submitted)
    }

    fun toggleHasResults() {
        mutableState.update { it.copy(onlyShowHasResults = !it.onlyShowHasResults) }
    }

    /** Searches every source again for the current words, not using what was kept (holding Pinned or All) */
    fun refreshAll() {
        val query = state.value.query.ifBlank { submitted }.takeIf { it.isNotBlank() } ?: return
        resultCache.remove(query)
        search(query)
    }

    /** Searches one source again (holding its name) */
    fun refreshSource(source: AnimeCatalogueSource) {
        val query = state.value.query.takeIf { it.isNotBlank() } ?: return
        resultCache[query]?.remove(source.id)
        mutableState.update { state ->
            state.copy(rows = state.rows.map { if (it.source.id == source.id) Row(source) else it })
        }
        screenModelScope.launchIO { fetch(source, query) }
    }

    fun search(query: String) {
        searchJob?.cancel()
        val disabledLangs = sourcePreferences.animeDisabledLanguages().get()
        val disabledSources = sourcePreferences.animeDisabledSources().get()
        val pinned = sourcePreferences.animePinnedSources().get()
        val pinnedOnly = state.value.pinnedOnly
        val sources = sourceManager.getCatalogueSources()
            .filter { it.lang !in disabledLangs && it.id.toString() !in disabledSources }
            .filter { !pinnedOnly || it.id.toString() in pinned }
            .sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name.lowercase() }))
        // What an earlier search with these words found stays until the app is closed (not when merging)
        val kept = if (mergeIntoId == null) resultCache[query].orEmpty() else emptyMap()
        mutableState.update {
            it.copy(
                query = query,
                rows = sources.map { source -> kept[source.id]?.copy(source = source) ?: Row(source) }
                    .sortedBy { row -> row.items?.isEmpty() == true },
            )
        }
        val semaphore = Semaphore(5)
        searchJob = screenModelScope.launchIO {
            sources.filter { it.id !in kept }.forEach { source ->
                launch {
                    semaphore.withPermit { fetch(source, query) }
                }
            }
        }
    }

    private suspend fun fetch(source: AnimeCatalogueSource, query: String) {
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
        if (state.value.query != query) return
        if (mergeIntoId == null) {
            resultCache.getOrPut(query) { java.util.concurrent.ConcurrentHashMap() }[source.id] = result
        }
        mutableState.update { state ->
            // Sources with results first, keeping the original order otherwise
            val rows = state.rows.map { if (it.source.id == source.id) result else it }
            state.copy(rows = rows.sortedBy { it.items?.isEmpty() == true })
        }
    }

    private companion object {
        /** Finished results per search words and source, kept while the app is open, like a source's Browse */
        val resultCache = java.util.concurrent.ConcurrentHashMap<String, MutableMap<Long, Row>>()
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
        val mode: GlobalSearchMode = GlobalSearchMode.RESULTS,
        val hasPinned: Boolean = false,
        val pinnedOnly: Boolean = false,
        val onlyShowHasResults: Boolean = false,
    )
}
