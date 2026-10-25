package eu.kanade.tachiyomi.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.anime.AnimeLibraryContent
import eu.kanade.presentation.anime.AnimeLibrarySettingsDialog
import eu.kanade.presentation.anime.rememberAnimeLibraryState
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.anime.AnimeRefreshMissingCovers
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.anime.AnimeComingUpScreen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.anime.AnimeMigrationStart
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: The Anime library, its own tab in the home navigation (like Aniyomi), next to the Manga library
 * ([LibraryTab]). Its menu acts on anime only.
 */
data object AnimeLibraryTab : Tab {
    @Suppress("unused")
    private fun readResolve(): Any = AnimeLibraryTab

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_animelibrary_leave)
            return TabOptions(
                index = 0u,
                title = stringResource(KMR.strings.label_anime_library_tab),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestSettingsSheetEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        // KMK: the anime Browse preview panels, in the library too
        val preview = cafe.adriel.voyager.core.model.rememberScreenModel { AnimeLibraryPreviewModel() }
        val previewOpen by preview.preview.collectAsState()
        androidx.activity.compose.BackHandler(enabled = previewOpen.anime != null) { preview.clear() }

        var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
        var currentPage by rememberSaveable { mutableIntStateOf(0) }
        var showSettings by remember { mutableStateOf(false) }
        val state = rememberAnimeLibraryState(searchQuery)
        val page = state.page(currentPage)

        if (showSettings) {
            AnimeLibrarySettingsDialog(onDismissRequest = { showSettings = false })
        }

        val showSnackbar: (String) -> Unit = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
        // The whole anime library, or the anime of one page (category or group)
        val onClickRefresh: (Boolean) -> Boolean = { wholeLibrary ->
            val ids = if (wholeLibrary || page == null) null else page.anime.map { it.id }
            val started = AnimeLibraryUpdateJob.startNow(context, ids)
            showSnackbar(
                context.stringResource(
                    when {
                        !started -> MR.strings.update_already_running
                        ids != null -> MR.strings.updating_category
                        else -> KMR.strings.anime_updating_library
                    },
                ),
            )
            started
        }

        Scaffold(
            topBar = { scrollBehavior ->
                val defaultTitle = stringResource(KMR.strings.label_anime_library_tab)
                val pageName = page?.category?.visualName
                val title = when {
                    page == null || state.showCategoryTabs || state.groupType == LibraryGroup.UNGROUPED ->
                        LibraryToolbarTitle(
                            text = defaultTitle,
                            numberOfManga = state.favorites?.size?.takeIf { state.showItemCount },
                        )
                    else -> LibraryToolbarTitle(
                        text = pageName ?: defaultTitle,
                        numberOfManga = page?.shown?.size?.takeIf { state.showItemCount },
                    )
                }
                LibraryToolbar(
                    hasActiveFilters = state.filters.isActive,
                    // The anime selection has its own bar at the bottom
                    selectedCount = 0,
                    title = title,
                    onClickUnselectAll = {},
                    onClickSelectAll = {},
                    onClickInvertSelection = {},
                    onClickFilter = { showSettings = true },
                    onClickRefresh = { onClickRefresh(false) },
                    onClickGlobalUpdate = { onClickRefresh(true) },
                    onClickOpenRandomManga = {
                        val random = page?.shown?.randomOrNull()
                        if (random != null) {
                            navigator.push(AnimeScreen(random.id))
                        } else {
                            showSnackbar(context.stringResource(MR.strings.information_no_entries_found))
                        }
                    },
                    onClickSyncNow = {},
                    isSyncEnabled = false,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    // Reads every anime's download folder again
                    onInvalidateDownloadCache = { ctx ->
                        val downloadManager = Injekt.get<AnimeDownloadManager>()
                        state.favorites.orEmpty().forEach { downloadManager.refresh(it) }
                        ctx.toast(MR.strings.download_cache_invalidated)
                    },
                    scrollBehavior = scrollBehavior.takeIf { !state.showCategoryTabs },
                    onClickRefreshMissingCovers = {
                        val started = AnimeRefreshMissingCovers.start(context, page?.shown.orEmpty())
                        showSnackbar(
                            context.stringResource(
                                if (started) {
                                    KMR.strings.refresh_missing_covers_started
                                } else {
                                    KMR.strings.refresh_missing_covers_running
                                },
                            ),
                        )
                    },
                    onClickMigrate = { AnimeMigrationStart.open(emptyList()) },
                    onClickComingUp = { navigator.push(AnimeComingUpScreen()) },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            androidx.compose.foundation.layout.BoxWithConstraints {
                val (panelStart, panelEnd) = preview.panelPadding(maxWidth)
                val previewState by preview.preview.collectAsState()
                AnimeLibraryContent(
                    state = state,
                    contentPadding = contentPadding,
                    searchQuery = searchQuery,
                    currentPage = currentPage,
                    onChangeCurrentPage = { currentPage = it },
                    // KMK: with a panel button on, the first tap previews the cover in the panels
                    onClickAnime = { if (!preview.onCoverClick(it)) navigator.push(AnimeScreen(it.id)) },
                    onRefresh = { onClickRefresh(false) },
                    onMigrate = { ids -> AnimeMigrationStart.open(ids) },
                    highlighted = previewState.anime?.id,
                    panelButtons = { preview.Buttons(this) },
                    panelStart = panelStart,
                    panelEnd = panelEnd,
                    panels = { preview.Panels(this) },
                )
            }
        }

        BackHandler(enabled = searchQuery != null) { searchQuery = null }

        val loaded = state.favorites != null
        LaunchedEffect(loaded) {
            if (loaded) {
                (context as? MainActivity)?.ready = true
                with(DiscordRPCService) {
                    discordScope.launchIO { setScreen(context, DiscordScreen.LIBRARY) }
                }
            }
        }

        LaunchedEffect(Unit) {
            requestSettingsSheetEvent.receiveAsFlow().collectLatest { showSettings = true }
        }
    }

    // For opening the settings sheet when the tab is tapped again
    private val requestSettingsSheetEvent = Channel<Unit>()
}
