package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.browse.anime.AnimeSourcesPane
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreenModel
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.browse.anime.AnimeBrowseScreenModel
import eu.kanade.tachiyomi.ui.browse.anime.AnimeExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.unified.UnifiedExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.unified.unifiedExtensionsTab
import eu.kanade.tachiyomi.ui.browse.feed.FeedScreenModel
import eu.kanade.tachiyomi.ui.browse.feed.feedTab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.sourcesTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object BrowseTab : Tab {
    private fun readResolve(): Any = BrowseTab

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(
                index = 3u,
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(GlobalSearchScreen())
    }

    private val switchToExtensionTabChannel = Channel<Unit>(1, BufferOverflow.DROP_OLDEST)

    fun showExtension() {
        switchToExtensionTabChannel.trySend(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        // SY -->
        // KMK: bring the Feed tab back once for anyone who lost it to the "Hide feed tab" setting
        remember {
            val prefs = Injekt.get<eu.kanade.domain.base.BasePreferences>()
            val ui = Injekt.get<UiPreferences>()
            val flag = Injekt.get<tachiyomi.core.common.preference.PreferenceStore>().getBoolean("kmk_feed_tab_restored", false)
            if (!flag.get()) {
                ui.hideFeedTab().set(false)
                flag.set(true)
            }
            prefs
        }
        val hideFeedTab by remember { Injekt.get<UiPreferences>().hideFeedTab().asState(scope) }
        val feedTabInFront by remember { Injekt.get<UiPreferences>().feedTabInFront().asState(scope) }
        // SY <--

        // Hoisted for extensions tab's search bar
        val extensionsScreenModel = rememberScreenModel { ExtensionsScreenModel() }
        val extensionsState by extensionsScreenModel.state.collectAsState()

        // KMK -->
        val feedScreenModel = rememberScreenModel { FeedScreenModel() }
        val bulkFavoriteScreenModel = rememberScreenModel { BulkFavoriteScreenModel() }
        // KMK <--

        // KMK -->
        // Anime half of Browse (merged from Anikku): manga on the left, anime on the right
        val navigator = LocalNavigator.currentOrThrow
        val animeScreenModel = rememberScreenModel { AnimeBrowseScreenModel() }
        val animeState by animeScreenModel.state.collectAsState()

        // Sources: one panel, manga or anime, switched from the toolbar like the library
        val browseAnimePref = remember { Injekt.get<SourcePreferences>().browseShowAnime() }
        val browseAnime by browseAnimePref.collectAsState()

        val switchTitle = stringResource(KMR.strings.library_switch_type)
        val switchTint = MaterialTheme.colorScheme.primary

        fun TabContent.withAnimeSwitch(anime: @Composable (PaddingValues) -> Unit): TabContent {
            val mangaContent = content
            return copy(
                actions = (
                    listOf(
                        AppBar.Action(
                            title = switchTitle,
                            icon = if (browseAnime) Icons.Outlined.Movie else Icons.AutoMirrored.Outlined.MenuBook,
                            iconTint = switchTint,
                            onClick = { browseAnimePref.set(!browseAnime) },
                        ),
                    ) + actions
                    ).toImmutableList(),
                content = { contentPadding, snackbarHostState ->
                    if (browseAnime) anime(contentPadding) else mangaContent(contentPadding, snackbarHostState)
                },
            )
        }

        val animeSources: @Composable (PaddingValues) -> Unit = { padding ->
            AnimeSourcesPane(
                state = animeState,
                contentPadding = padding,
                onSearch = animeScreenModel::searchSources,
                onTogglePin = animeScreenModel::togglePin,
                onClickSource = {
                    // Same as manga: the source home page (Latest + Browse) when "Replace latest button" is on
                    if (Injekt.get<eu.kanade.domain.ui.UiPreferences>().useNewSourceNavigation().get()) {
                        navigator.push(eu.kanade.tachiyomi.ui.anime.source.AnimeSourceFeedScreen(it))
                    } else {
                        navigator.push(eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen(it))
                    }
                },
                onClickFilter = { navigator.push(eu.kanade.tachiyomi.ui.browse.anime.AnimeSourcesFilterScreen()) },
                onClickGlobalSearch = { navigator.push(eu.kanade.tachiyomi.ui.anime.AnimeGlobalSearchScreen()) },
                onClickSettings = { navigator.push(AnimeExtensionDetailsScreen(it)) },
                onClickLatest = {
                    navigator.push(eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen(it, initialLatest = true))
                },
            )
        }
        // Extensions: one Kototoro-style screen for every kind of extension
        val unifiedExtensionsModel = rememberScreenModel { UnifiedExtensionsScreenModel() }
        val mangaStoresModel = rememberScreenModel(tag = "manga_stores") { ExtensionStoresScreenModel() }
        val animeStoresModel = rememberScreenModel(tag = "anime_stores") { ExtensionStoresScreenModel(anime = true) }
        val extensions = unifiedExtensionsTab(unifiedExtensionsModel, mangaStoresModel, animeStoresModel)
        // KMK <--

        // SY -->
        // KMK: Migrate moved to the Library menu, so Browse is Sources, Feed and Extensions
        val tabs = when {
            hideFeedTab ->
                persistentListOf(
                    sourcesTab().withAnimeSwitch(animeSources),
                    extensions,
                )

            feedTabInFront ->
                persistentListOf(
                    feedTab(
                        // KMK -->
                        feedScreenModel,
                        bulkFavoriteScreenModel,
                        // KMK <--
                    ),
                    sourcesTab().withAnimeSwitch(animeSources),
                    extensions,
                )

            // KMK: Sources, Feed, then Extensions
            else ->
                persistentListOf(
                    sourcesTab().withAnimeSwitch(animeSources),
                    feedTab(
                        // KMK -->
                        feedScreenModel,
                        bulkFavoriteScreenModel,
                        // KMK <--
                    ),
                    extensions,
                )
        }
        // SY <--

        val state = rememberPagerState { tabs.size }

        TabbedScreen(
            titleRes = MR.strings.browse,
            tabs = tabs,
            state = state,
            searchQuery = extensionsState.searchQuery,
            onChangeSearchQuery = extensionsScreenModel::search,
            // KMK -->
            feedScreenModel = feedScreenModel,
            bulkFavoriteScreenModel = bulkFavoriteScreenModel,
            // KMK <--
        )
        LaunchedEffect(Unit) {
            switchToExtensionTabChannel.receiveAsFlow()
                .collectLatest {
                    // KMK: Extensions is the last tab: 2nd when Feed is hidden, otherwise 3rd
                    state.scrollToPage(if (hideFeedTab) 1 else 2)
                }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true

            // AM (DISCORD) -->
            with(DiscordRPCService) {
                discordScope.launchIO { setScreen(context, DiscordScreen.BROWSE) }
            }
            // <-- AM (DISCORD)
        }
    }
}
