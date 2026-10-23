package eu.kanade.tachiyomi.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.MediaTab
import eu.kanade.presentation.components.MediaTabPager
import eu.kanade.presentation.components.MediaTabRow
import eu.kanade.presentation.more.stats.StatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

class StatsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel { StatsScreenModel() }
        val state by screenModel.state.collectAsState()
        // KMK -->
        val animeStats by screenModel.animeStats.collectAsState()
        // Anime | Manga, opening on Anime
        var mediaTab by rememberSaveable { mutableIntStateOf(MediaTab.ANIME) }
        // KMK <--

        Scaffold(
            topBar = { scrollBehavior ->
                // KMK -->
                Column {
                    // KMK <--
                    AppBar(
                        title = stringResource(MR.strings.label_stats),
                        navigateUp = navigator::pop,
                        scrollBehavior = scrollBehavior,
                        // SY -->
                        actions = {
                            val allRead by screenModel.allRead.collectAsState()
                            AppBarActions(
                                persistentListOf(
                                    AppBar.OverflowAction(
                                        title = if (allRead) {
                                            stringResource(SYMR.strings.ignore_non_library_entries)
                                        } else {
                                            stringResource(SYMR.strings.include_all_read_entries)
                                        },
                                        onClick = screenModel::toggleReadManga,
                                    ),
                                ),
                            )
                        },
                        // SY <--
                    )
                    // KMK -->
                    MediaTabRow(selected = mediaTab, onSelect = { mediaTab = it })
                }
                // KMK <--
            },
        ) { paddingValues ->
            // KMK: swipe left or right between Anime and Manga
            MediaTabPager(selected = mediaTab, onSelect = { mediaTab = it }) { mediaTab ->
                if (state is StatsScreenState.Loading && /* KMK */ mediaTab == MediaTab.MANGA) {
                    LoadingScreen()
                    return@MediaTabPager
                }

                StatsScreenContent(
                    // KMK: the anime tab doesn't wait on the manga numbers
                    state = state as? StatsScreenState.Success,
                    paddingValues = paddingValues,
                    // KMK -->
                    animeStats = animeStats,
                    mediaTab = mediaTab,
                    // KMK <--
                )
            }
        }
    }
}
