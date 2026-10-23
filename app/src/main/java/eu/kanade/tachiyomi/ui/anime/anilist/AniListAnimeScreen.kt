package eu.kanade.tachiyomi.ui.anime.anilist

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.swipeBetweenTabs
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.anime.AnimeGlobalSearchScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * KMK: An anime opened from the globe tab of anime global search, all from AniList: the three titles, Watch now
 * (search your anime sources for it), Trailer, AniList and MAL, then Episodes, Relations, Themes and
 * Recommendations, like Hayase.
 */
class AniListAnimeScreen(private val anilistId: Int) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { AniListAnimeScreenModel(anilistId) }
        val state by screenModel.state.collectAsState()
        var showWatchNow by remember { mutableStateOf(false) }

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

        if (showWatchNow) {
            AniListWatchNowDialog(
                media = card,
                onDismissRequest = { showWatchNow = false },
            )
        }

        val actions = AniListActions(
            onWatchNow = { showWatchNow = true },
            onWebView = { navigator.push(WebViewScreen("https://anilist.co/anime/${card.id}", card.title)) },
            onTrailer = details.trailer?.let { trailer -> { context.openLink(trailer.url) } },
            onAniList = { context.openLink("https://anilist.co/anime/${card.id}") },
            onMal = card.idMal?.let { mal -> { context.openLink("https://myanimelist.net/anime/$mal") } },
            onSearch = { navigator.push(AnimeGlobalSearchScreen(it)) },
            onOpenAniList = { navigator.push(AniListAnimeScreen(it)) },
            onCopy = { text ->
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(text, text))
                context.toast(MR.strings.copied_to_clipboard_plain)
            },
            onPlayTheme = { url -> context.playVideo(url) },
        )

        val tablet = isTabletUi()
        Scaffold(
            topBar = {
                AppBar(
                    title = card.title,
                    navigateUp = navigator::pop,
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
            },
        ) { contentPadding ->
            if (tablet) {
                TwoPanelBox(
                    modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
                    startContent = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            AniListInfo(details, state, isTablet = true, actions = actions)
                        }
                    },
                    endContent = {
                        AniListTabs(details, state, screenModel, actions, episodeColumns = 2)
                    },
                )
            } else {
                Column(
                    modifier = Modifier
                        .padding(top = contentPadding.calculateTopPadding())
                        .verticalScroll(rememberScrollState()),
                ) {
                    AniListInfo(details, state, isTablet = false, actions = actions)
                    AniListTabContent(
                        details = details,
                        state = state,
                        screenModel = screenModel,
                        actions = actions,
                        episodeColumns = 1,
                        scrollable = false,
                    )
                }
            }
        }
    }
}

/** What the buttons of the AniList anime page do */
@Immutable
class AniListActions(
    val onWatchNow: () -> Unit,
    val onWebView: () -> Unit,
    val onTrailer: (() -> Unit)?,
    val onAniList: () -> Unit,
    val onMal: (() -> Unit)?,
    val onSearch: (String) -> Unit,
    val onOpenAniList: (Int) -> Unit,
    val onCopy: (String) -> Unit,
    val onPlayTheme: (String) -> Unit,
)

@Composable
private fun AniListInfo(
    details: AniListDetails,
    state: AniListAnimeScreenModel.State,
    isTablet: Boolean,
    actions: AniListActions,
) {
    Column {
        AniListHeader(details = details, isTablet = isTablet, onWatchNow = actions.onWatchNow, onSearch = actions.onSearch)
        AniListActionRow(details = details, actions = actions)
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
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private val TabTitles = listOf(
    KMR.strings.anilist_episodes,
    KMR.strings.anilist_relations,
    KMR.strings.anilist_themes,
    KMR.strings.anilist_recommendations,
)

/** Tablet: the four tabs on the right, swiped between */
@Composable
private fun AniListTabs(
    details: AniListDetails,
    state: AniListAnimeScreenModel.State,
    screenModel: AniListAnimeScreenModel,
    actions: AniListActions,
    episodeColumns: Int,
) {
    val pagerState = rememberPagerState { TabTitles.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage) { screenModel.onTabShown(pagerState.currentPage) }
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 8.dp) {
            TabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(stringResource(title)) },
                    unselectedContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = androidx.compose.ui.Alignment.Top,
        ) { page ->
            Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AniListPage(page, details, state, screenModel, actions, episodeColumns)
            }
        }
    }
}

/** Phone: the four tabs under the details, in the same scrolling page */
@Composable
private fun AniListTabContent(
    details: AniListDetails,
    state: AniListAnimeScreenModel.State,
    screenModel: AniListAnimeScreenModel,
    actions: AniListActions,
    episodeColumns: Int,
    @Suppress("UNUSED_PARAMETER") scrollable: Boolean,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(page) { screenModel.onTabShown(page) }
    PrimaryScrollableTabRow(selectedTabIndex = page, edgePadding = 8.dp) {
        TabTitles.forEachIndexed { index, title ->
            Tab(
                selected = page == index,
                onClick = { page = index },
                text = { Text(stringResource(title)) },
                unselectedContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    Box(
        modifier = Modifier.swipeBetweenTabs(count = TabTitles.size, selected = page, onSelect = { page = it }),
    ) {
        AniListPage(page, details, state, screenModel, actions, episodeColumns)
    }
}

@Composable
private fun AniListPage(
    page: Int,
    details: AniListDetails,
    state: AniListAnimeScreenModel.State,
    screenModel: AniListAnimeScreenModel,
    actions: AniListActions,
    episodeColumns: Int,
) {
    when (page) {
        0 -> AniListEpisodes(
            episodes = state.episodes,
            loading = state.episodesLoading,
            columns = episodeColumns,
            onClick = { actions.onWatchNow() },
        )
        1 -> AniListRelationsGraph(
            graph = state.graph,
            loading = state.graphLoading,
            currentId = details.card.id,
            onClickNode = actions.onOpenAniList,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        2 -> AniListThemes(themes = state.themes, loading = state.themesLoading, onPlay = actions.onPlayTheme)
        else -> AniListRecommendations(details.recommendations, onClick = actions.onOpenAniList)
    }
}

private fun Context.openLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        openInBrowser(url)
    }
}

/** An opening or ending video: the phone's video player, or the browser */
private fun Context.playVideo(url: String) {
    try {
        startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(url.toUri(), "video/webm").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        try {
            openInBrowser(url)
        } catch (_: Exception) {
            toast(KMR.strings.anilist_no_video_app)
        }
    }
}

class AniListAnimeScreenModel(private val anilistId: Int) :
    StateScreenModel<AniListAnimeScreenModel.State>(State()) {

    init {
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)

    private fun load(refresh: Boolean) {
        screenModelScope.launch {
            try {
                val details = AniListApi.details(anilistId, refresh)
                mutableState.update { it.copy(details = details, error = null, episodesLoading = true) }
                val episodes = runCatching { AniListApi.episodes(details, refresh) }.getOrDefault(emptyList())
                mutableState.update { it.copy(episodes = episodes, episodesLoading = false) }
                if (refresh) {
                    mutableState.update { it.copy(graph = null, themes = null) }
                    loadedTabs.clear()
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(error = e.message ?: e.javaClass.simpleName, episodesLoading = false) }
            }
        }
    }

    private val loadedTabs = HashSet<Int>()

    /** Relations and themes load the first time their tab is shown */
    fun onTabShown(page: Int) {
        val details = state.value.details ?: return
        if (!loadedTabs.add(page)) return
        when (page) {
            1 -> screenModelScope.launch {
                mutableState.update { it.copy(graphLoading = true) }
                try {
                    AniListApi.relationsGraph(details) { graph -> mutableState.update { it.copy(graph = graph) } }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                } finally {
                    mutableState.update { it.copy(graphLoading = false) }
                }
            }
            2 -> screenModelScope.launch {
                mutableState.update { it.copy(themesLoading = true) }
                val themes = runCatching { AniListApi.themes(details.card.id) }.getOrDefault(emptyList())
                mutableState.update { it.copy(themes = themes, themesLoading = false) }
            }
        }
    }

    @Immutable
    data class State(
        val details: AniListDetails? = null,
        val error: String? = null,
        val episodes: List<AniListEpisode> = emptyList(),
        val episodesLoading: Boolean = false,
        val graph: AniListGraph? = null,
        val graphLoading: Boolean = false,
        val themes: List<AniListTheme>? = null,
        val themesLoading: Boolean = false,
    )
}
