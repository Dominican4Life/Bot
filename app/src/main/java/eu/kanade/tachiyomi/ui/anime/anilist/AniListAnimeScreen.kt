package eu.kanade.tachiyomi.ui.anime.anilist

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.swipeBetweenTabs
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.anime.AnimeGlobalSearchScreen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: The tabs of the AniList page (anime: Episodes, Relations, Themes, Recommendations; manga: Chapters, …) */
enum class AniListTab(val title: StringResource) {
    EPISODES(KMR.strings.anilist_episodes),
    CHAPTERS(KMR.strings.anilist_chapters),
    RELATIONS(KMR.strings.anilist_relations),
    THEMES(KMR.strings.anilist_themes),
    RECOMMENDATIONS(KMR.strings.anilist_recommendations),
    ;

    companion object {
        fun of(manga: Boolean) = if (manga) {
            listOf(CHAPTERS, RELATIONS, RECOMMENDATIONS)
        } else {
            listOf(EPISODES, RELATIONS, THEMES, RECOMMENDATIONS)
        }
    }
}

/**
 * KMK: An anime (or manga) opened from the AniList tab of global search, all from AniList: the three titles,
 * Watch now / Read now (plays straight from a source you pick), Trailer, AniList and MAL, then the tabs, like
 * Hayase. Tapping a title in Relations or Recommendations switches this page to it (Back comes back).
 */
class AniListAnimeScreen(private val anilistId: Int, private val manga: Boolean = false) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { AniListAnimeScreenModel(anilistId, manga) }
        val state by screenModel.state.collectAsState()
        var picker by remember { mutableStateOf<PickRequest?>(null) }
        var trailer by remember { mutableStateOf<AniListTrailerVideo?>(null) }

        BackHandler(enabled = trailer != null) { trailer = null }
        BackHandler(enabled = trailer == null && state.canGoBack) { screenModel.back() }

        val details = state.details
        if (details == null) {
            Scaffold(
                topBar = { AppBar(title = "", navigateUp = navigator::pop, scrollBehavior = it) },
            ) { padding ->
                if (state.error != null) {
                    EmptyScreen(message = state.error!!, modifier = Modifier.padding(padding))
                } else {
                    LoadingScreen(Modifier.padding(padding))
                }
            }
            return
        }
        val card = details.card

        val openPage: (Long) -> Unit = { localId ->
            if (manga) navigator.push(MangaScreen(localId, true)) else navigator.push(AnimeScreen(localId))
        }
        // Play (or read) from the remembered source, or ask for one first
        val watch: (Int?, Boolean) -> Unit = { target, pickAgain ->
            val link = state.link
            if (link == null || pickAgain) {
                picker = PickRequest(target)
            } else {
                screenModel.play(context, link, target, openPage)
            }
        }

        picker?.let { request ->
            AniListSourcePicker(
                media = card,
                manga = manga,
                onDismissRequest = { picker = null },
                onPick = { row, item ->
                    val link = AniListLink(row.sourceId, item.id, row.sourceName)
                    screenModel.setLink(link)
                    screenModel.play(context, link, request.target, openPage)
                },
            )
        }

        val actions = AniListActions(
            onWatchNow = { watch(null, false) },
            onPickSource = { watch(null, true) },
            onWebView = { navigator.push(WebViewScreen(siteUrl(card), card.title)) },
            onTrailer = {
                scope.launch {
                    val video = screenModel.trailer()
                    if (video != null) trailer = video else context.toast(KMR.strings.anilist_no_trailer)
                }
            },
            onAniList = { navigator.push(WebViewScreen(siteUrl(card), card.title)) },
            onMal = card.idMal?.let { mal ->
                {
                    val kind = if (manga) "manga" else "anime"
                    navigator.push(WebViewScreen("https://myanimelist.net/$kind/$mal", card.title))
                }
            },
            onSearch = { query ->
                if (manga) navigator.push(GlobalSearchScreen(query)) else navigator.push(AnimeGlobalSearchScreen(query))
            },
            onOpenAniList = screenModel::open,
            onCopy = { text ->
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(text, text))
                context.toast(MR.strings.copied_to_clipboard_plain)
            },
            onEpisode = { watch(it.number, false) },
            onChapter = { chapter ->
                state.link?.let { link ->
                    context.startActivity(ReaderActivity.newIntent(context, link.localId, chapter.id))
                }
            },
            // Openings and endings play in the same small window as the trailer
            onPlayTheme = { themeIndex, entryIndex ->
                state.themes?.getOrNull(themeIndex)?.entries?.getOrNull(entryIndex)?.videoUrl?.let { url ->
                    trailer = AniListTrailerVideo("video", url)
                }
            },
        )

        val tablet = isTabletUi()
        val tabs = remember(manga) { AniListTab.of(manga) }
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    Column {
                        AppBar(
                            title = card.title,
                            navigateUp = { if (state.canGoBack) screenModel.back() else navigator.pop() },
                            scrollBehavior = it,
                            actions = {
                                AppBarActions(
                                    persistentListOf(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_webview_refresh),
                                            icon = Icons.Outlined.Refresh,
                                            onClick = screenModel::refresh,
                                        ),
                                    ),
                                )
                            },
                        )
                        if (state.switching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                },
            ) { contentPadding ->
                if (tablet) {
                    TwoPanelBox(
                        modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
                        startContent = {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                AniListInfo(details, state, isTablet = true, manga = manga, actions = actions)
                            }
                        },
                        endContent = {
                            AniListTabsPanel(tabs, details, state, screenModel, actions)
                        },
                    )
                } else {
                    AniListPhoneContent(
                        tabs = tabs,
                        details = details,
                        state = state,
                        screenModel = screenModel,
                        actions = actions,
                        topPadding = contentPadding.calculateTopPadding(),
                    )
                }
            }
            trailer?.let { AniListTrailerWindow(video = it, onClose = { trailer = null }) }
            AniListNoticeHost(notice = state.notice, onDismiss = screenModel::dismissNotice)
        }
    }

    private fun siteUrl(card: AniListMedia) = "https://anilist.co/${if (manga) "manga" else "anime"}/${card.id}"
}

/** Which episode or chapter the source picker was opened for (null: the next one) */
@Immutable
data class PickRequest(val target: Int?)

/** What the buttons of the AniList page do */
@Immutable
class AniListActions(
    val onWatchNow: () -> Unit,
    val onPickSource: () -> Unit,
    val onWebView: () -> Unit,
    val onTrailer: (() -> Unit)?,
    val onAniList: () -> Unit,
    val onMal: (() -> Unit)?,
    val onSearch: (String) -> Unit,
    val onOpenAniList: (Int) -> Unit,
    val onCopy: (String) -> Unit,
    val onEpisode: (AniListEpisode) -> Unit,
    val onChapter: (Chapter) -> Unit,
    val onPlayTheme: (themeIndex: Int, entryIndex: Int) -> Unit,
)

@Composable
private fun AniListInfo(
    details: AniListDetails,
    state: AniListAnimeScreenModel.State,
    isTablet: Boolean,
    manga: Boolean,
    actions: AniListActions,
) {
    Column {
        AniListHeader(
            details = details,
            isTablet = isTablet,
            manga = manga,
            linkedSource = state.link?.sourceName,
            onWatchNow = actions.onWatchNow,
            onPickSource = actions.onPickSource,
            onSearch = actions.onSearch,
        )
        AniListActionRow(actions = actions, manga = manga)
        AniListStatsRow(details = details, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val tags = remember(details) {
            details.card.genres + details.tags.filter { !it.spoiler && it.rank >= 50 }.map { it.name }
        }
        ExpandableMangaDescription(
            defaultExpandState = isTablet,
            description = details.card.description,
            tagsProvider = { tags },
            notes = "",
            onTagSearch = actions.onSearch,
            onCopyTagToClipboard = actions.onCopy,
            onEditNotes = {},
            searchMetadataChips = null,
            doSearch = { query, _ -> actions.onSearch(query) },
        )
        if (state.error != null) {
            Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AniListTabRow(tabs: List<AniListTab>, selected: Int, onSelect: (Int) -> Unit) {
    PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                text = { Text(stringResource(tab.title)) },
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The list items of one tab; Relations is drawn on its own (it needs the whole space) */
private fun LazyListScope.tabItems(
    tab: AniListTab,
    details: AniListDetails,
    state: AniListAnimeScreenModel.State,
    actions: AniListActions,
    episodeColumns: Int,
) {
    when (tab) {
        AniListTab.EPISODES -> aniListEpisodeItems(state.episodes, state.episodesLoading, episodeColumns, actions.onEpisode)
        AniListTab.CHAPTERS -> aniListChapterItems(
            chapters = state.chapters,
            loading = state.chaptersLoading,
            linkedSource = state.link?.sourceName,
            onPickSource = actions.onPickSource,
            onClick = actions.onChapter,
        )
        AniListTab.THEMES -> aniListThemeItems(state.themes, state.themesLoading, actions.onPlayTheme)
        AniListTab.RECOMMENDATIONS -> aniListRecommendationItems(details.recommendations, actions.onOpenAniList)
        AniListTab.RELATIONS -> {}
    }
}

/** Tablet: the tabs on the right, swiped between; Relations fills the whole right side */
@Composable
private fun AniListTabsPanel(
    tabs: List<AniListTab>,
    details: AniListDetails,
    state: AniListAnimeScreenModel.State,
    screenModel: AniListAnimeScreenModel,
    actions: AniListActions,
) {
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage, details.card.id) { screenModel.onTabShown(tabs[pagerState.currentPage]) }
    Column(modifier = Modifier.fillMaxSize()) {
        AniListTabRow(tabs, pagerState.currentPage) { scope.launch { pagerState.animateScrollToPage(it) } }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
            // The diagram takes the drags inside it
            userScrollEnabled = tabs[pagerState.currentPage] != AniListTab.RELATIONS,
        ) { page ->
            val tab = tabs[page]
            if (tab == AniListTab.RELATIONS) {
                AniListRelationsGraph(
                    graph = state.graph,
                    loading = state.graphLoading,
                    currentId = details.card.id,
                    onClickNode = actions.onOpenAniList,
                    onRefresh = screenModel::refreshGraph,
                    fill = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                ) {
                    tabItems(tab, details, state, actions, episodeColumns = 2)
                    item(key = "nav-bar") { Box(modifier = Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

/** Phone: one list: the details, the tabs, then the chosen tab (everything loaded, just scroll) */
@Composable
private fun AniListPhoneContent(
    tabs: List<AniListTab>,
    details: AniListDetails,
    state: AniListAnimeScreenModel.State,
    screenModel: AniListAnimeScreenModel,
    actions: AniListActions,
    topPadding: androidx.compose.ui.unit.Dp,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val tab = tabs[page.coerceIn(0, tabs.lastIndex)]
    LaunchedEffect(tab, details.card.id) { screenModel.onTabShown(tab) }
    val graphHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
    val manga = screenModel.manga
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding)
            .swipeBetweenTabs(count = tabs.size, selected = page, onSelect = { page = it }),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "info") { AniListInfo(details, state, isTablet = false, manga = manga, actions = actions) }
        item(key = "tabs") { AniListTabRow(tabs, page) { page = it } }
        if (tab == AniListTab.RELATIONS) {
            item(key = "relations") {
                AniListRelationsGraph(
                    graph = state.graph,
                    loading = state.graphLoading,
                    currentId = details.card.id,
                    onClickNode = actions.onOpenAniList,
                    onRefresh = screenModel::refreshGraph,
                    height = graphHeight,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        } else {
            tabItems(tab, details, state, actions, episodeColumns = 1)
        }
        item(key = "nav-bar") { Box(modifier = Modifier.navigationBarsPadding()) }
    }
}

class AniListAnimeScreenModel(initialId: Int, val manga: Boolean) :
    StateScreenModel<AniListAnimeScreenModel.State>(State(currentId = initialId)) {

    private val app = Injekt.get<Application>()
    private val history = ArrayDeque<Int>()
    private var loadJob: Job? = null
    private val loadedTabs = HashSet<AniListTab>()

    init {
        load(initialId, refresh = false)
    }

    fun refresh() = load(state.value.currentId, refresh = true)

    /** Switches the page to another title (from Relations or Recommendations), keeping the diagram */
    fun open(id: Int) {
        if (id == state.value.currentId) return
        history.addLast(state.value.currentId)
        load(id, refresh = false)
    }

    /** Back to the title before; false when there's none */
    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        load(previous, refresh = false)
        return true
    }

    private fun load(id: Int, refresh: Boolean) {
        loadJob?.cancel()
        loadedTabs.clear()
        mutableState.update {
            it.copy(
                currentId = id,
                switching = it.details != null,
                canGoBack = history.isNotEmpty(),
                link = AniListLinks.get(id, manga),
                // The diagram stays when the new title is in it, like Hayase
                graph = it.graph?.takeIf { graph -> !refresh && graph.nodes.any { node -> node.id == id } },
            )
        }
        loadJob = screenModelScope.launch {
            try {
                val details = AniListApi.details(id, refresh)
                mutableState.update {
                    it.copy(
                        details = details,
                        error = null,
                        switching = false,
                        episodes = emptyList(),
                        episodesLoading = !manga,
                        themes = null,
                        chapters = null,
                    )
                }
                if (!manga) {
                    val episodes = runCatching { AniListApi.episodes(details, refresh) }.getOrDefault(emptyList())
                    mutableState.update { it.copy(episodes = episodes, episodesLoading = false) }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                mutableState.update {
                    it.copy(error = e.message ?: e.javaClass.simpleName, episodesLoading = false, switching = false)
                }
            }
        }
    }

    /** Relations, themes and chapters load the first time their tab is shown */
    fun onTabShown(tab: AniListTab) {
        val details = state.value.details ?: return
        if (details.card.id != state.value.currentId) return
        if (!loadedTabs.add(tab)) return
        when (tab) {
            AniListTab.RELATIONS -> {
                if (state.value.graph != null) return
                screenModelScope.launch {
                    mutableState.update { it.copy(graphLoading = true) }
                    try {
                        AniListApi.relationsGraph(details) { graph -> mutableState.update { it.copy(graph = graph) } }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                    } finally {
                        mutableState.update { it.copy(graphLoading = false) }
                    }
                }
            }
            AniListTab.THEMES -> screenModelScope.launch {
                mutableState.update { it.copy(themesLoading = true) }
                val themes = runCatching { AniListApi.themes(details.card.id) }.getOrDefault(emptyList())
                mutableState.update { it.copy(themes = themes, themesLoading = false) }
            }
            AniListTab.CHAPTERS -> loadChapters()
            else -> {}
        }
    }

    /** The diagram's refresh button: asks AniList again */
    fun refreshGraph() {
        mutableState.update { it.copy(graph = null) }
        loadedTabs.remove(AniListTab.RELATIONS)
        onTabShown(AniListTab.RELATIONS)
    }

    private fun loadChapters() {
        val link = state.value.link ?: return
        screenModelScope.launch {
            mutableState.update { it.copy(chaptersLoading = true) }
            // What's saved shows at once, then the source's list
            val saved = runCatching { AniListPlayback.mangaChapters(link.localId, refresh = false) }.getOrNull()
            if (!saved.isNullOrEmpty()) mutableState.update { it.copy(chapters = saved) }
            val fresh = runCatching { AniListPlayback.mangaChapters(link.localId) }.getOrNull()
            mutableState.update { it.copy(chapters = fresh ?: saved ?: emptyList(), chaptersLoading = false) }
        }
    }

    fun setLink(link: AniListLink) {
        AniListLinks.set(state.value.currentId, manga, link)
        mutableState.update { it.copy(link = link, chapters = null) }
        if (AniListTab.CHAPTERS in loadedTabs) loadChapters()
    }

    /** Plays episode [target] (or opens chapter [target]; null: the next one) straight from the linked source */
    fun play(context: Context, link: AniListLink, target: Int?, openPage: (Long) -> Unit) {
        screenModelScope.launch {
            notify(
                AniListNotice(
                    title = if (manga) {
                        app.stringResource(KMR.strings.anilist_loading_chapters, link.sourceName)
                    } else {
                        app.stringResource(KMR.strings.anilist_loading_episode, target?.toString() ?: "", link.sourceName)
                    },
                    description = app.stringResource(KMR.strings.anilist_pick_source_hint),
                    loading = true,
                ),
            )
            val outcome = try {
                if (manga) {
                    AniListPlayback.readManga(context, link.localId, target)
                } else {
                    AniListPlayback.playAnime(context, link.localId, target)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                AniListPlayback.Outcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            when (outcome) {
                AniListPlayback.Outcome.Started -> mutableState.update { it.copy(notice = null) }
                AniListPlayback.Outcome.OpenPage -> {
                    mutableState.update { it.copy(notice = null) }
                    openPage(link.localId)
                }
                is AniListPlayback.Outcome.Failed -> {
                    notify(
                        AniListNotice(
                            title = app.stringResource(
                                KMR.strings.anilist_episode_not_found,
                                link.sourceName,
                                target?.toString() ?: "",
                            ),
                            description = app.stringResource(KMR.strings.anilist_open_source_page),
                            isError = true,
                        ),
                    )
                    openPage(link.localId)
                }
            }
        }
    }

    /** The trailer: AniList's, or MyAnimeList's like Shiru when AniList has none */
    suspend fun trailer(): AniListTrailerVideo? {
        val details = state.value.details ?: return null
        details.trailer?.let { return AniListTrailerVideo(it.site, it.id) }
        val mal = details.card.idMal ?: return null
        return AniListApi.malTrailer(mal)?.let { AniListTrailerVideo("youtube", it) }
    }

    private fun notify(notice: AniListNotice) {
        mutableState.update { it.copy(notice = notice) }
    }

    fun dismissNotice(id: Long) {
        mutableState.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    @Immutable
    data class State(
        val currentId: Int,
        val details: AniListDetails? = null,
        val error: String? = null,
        val switching: Boolean = false,
        val canGoBack: Boolean = false,
        val episodes: List<AniListEpisode> = emptyList(),
        val episodesLoading: Boolean = false,
        val graph: AniListGraph? = null,
        val graphLoading: Boolean = false,
        val themes: List<AniListTheme>? = null,
        val themesLoading: Boolean = false,
        val link: AniListLink? = null,
        val chapters: List<Chapter>? = null,
        val chaptersLoading: Boolean = false,
        val notice: AniListNotice? = null,
    )
}
