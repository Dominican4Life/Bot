package eu.kanade.tachiyomi.ui.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.suggestions.SuggestionsPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.more.settings.screen.SettingsSuggestionsScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.data.suggestions.SuggestionsJob
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPrefAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Suggestions tab (below Browse): manga picked from what you read, refreshed in the background
 * by [SuggestionsJob]. Settings live in [SettingsSuggestionsScreen].
 */
data object SuggestionsTab : Tab {
    private fun readResolve(): Any = SuggestionsTab

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            return TabOptions(
                index = 5u,
                title = stringResource(KMR.strings.label_suggestions),
                icon = rememberVectorPainter(if (isSelected) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb),
            )
        }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { SuggestionsScreenModel() }
        val state by screenModel.state.collectAsState()
        val running by SuggestionsJob.isRunning.collectAsState()
        val prefs = remember { Injekt.get<SuggestionsPreferences>() }
        val enabled by prefs.enabled().collectPrefAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.label_suggestions),
                    actions = {
                        if (running) {
                            CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        } else if (enabled) {
                            IconButton(onClick = { SuggestionsJob.startNow(context) }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.action_webview_refresh))
                            }
                        }
                        IconButton(onClick = { navigator.push(SettingsSuggestionsScreen) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(MR.strings.action_settings))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when {
                !enabled -> Message(
                    text = stringResource(KMR.strings.suggestions_disabled),
                    button = stringResource(KMR.strings.suggestions_turn_on),
                    onButton = {
                        prefs.enabled().set(true)
                        SuggestionsJob.setupTask(context)
                        SuggestionsJob.startNow(context)
                    },
                    modifier = Modifier.padding(contentPadding),
                )
                state.items.isEmpty() && running -> Message(
                    text = stringResource(KMR.strings.suggestions_finding),
                    modifier = Modifier.padding(contentPadding),
                )
                state.items.isEmpty() && state.loaded -> Message(
                    text = stringResource(KMR.strings.suggestions_empty),
                    button = stringResource(MR.strings.action_webview_refresh),
                    onButton = { SuggestionsJob.startNow(context) },
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
                    items(state.items, key = { it.id }) { manga ->
                        MangaComfortableGridItem(
                            title = manga.title,
                            coverData = manga.asMangaCover(),
                            onClick = { navigator.push(MangaScreen(manga.id, true)) },
                            onLongClick = { navigator.push(MangaScreen(manga.id, true)) },
                            usePanoramaCover = false,
                            coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(
    text: String,
    modifier: Modifier = Modifier,
    button: String? = null,
    onButton: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().padding(32.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Lightbulb,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (button != null) {
            Button(onClick = onButton, modifier = Modifier.padding(top = 16.dp)) { Text(button) }
        }
    }
}

class SuggestionsScreenModel(
    private val preferences: SuggestionsPreferences = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
) : StateScreenModel<SuggestionsScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            preferences.results().changes().collectLatest { raw ->
                val ids = raw.split(',').mapNotNull { it.toLongOrNull() }
                val items = ids.mapNotNull { getManga.await(it) }
                mutableState.update { State(items = items, loaded = true) }
            }
        }
    }

    @Immutable
    data class State(
        val items: List<Manga> = emptyList(),
        val loaded: Boolean = false,
    )
}
