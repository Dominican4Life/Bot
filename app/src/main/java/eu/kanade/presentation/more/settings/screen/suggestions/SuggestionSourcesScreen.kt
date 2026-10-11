package eu.kanade.presentation.more.settings.screen.suggestions

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.suggestions.SuggestionsPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.suggestions.SuggestionsJob
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.sumizuri.SumizuriAnimeSource
import eu.kanade.tachiyomi.sumizuri.SumizuriMangaSource
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Preferred and excluded sources for suggestions, manga and anime on their own tabs, in one list each, with the tri-state boxes of Mihon's library
 * filters: tap once to prefer a source (✓), again to exclude it (✕), again to clear it. JS sources are listed too.
 * Suggestions are refreshed once, when leaving the screen after a change.
 */
class SuggestionSourcesScreen : Screen() {

    private data class Row(val id: Long, val label: String)

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val prefs = remember { Injekt.get<SuggestionsPreferences>() }
        // KMK: Manga | Anime, each with its own preferred / excluded lists
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val isAnime = tab == 1
        val preferredPref = if (isAnime) prefs.animePreferredSources() else prefs.preferredSources()
        val excludedPref = if (isAnime) prefs.animeExcludedSources() else prefs.excludedSources()
        val preferred by preferredPref.collectAsState()
        val excluded by excludedPref.collectAsState()
        var query by rememberSaveable { mutableStateOf("") }
        var changed by remember { mutableStateOf(false) }

        val mangaSources = remember {
            Injekt.get<SourceManager>().getVisibleOnlineSources()
                .filterIsInstance<CatalogueSource>()
                .map { source ->
                    val js = if (source is SumizuriMangaSource) " · JS" else ""
                    Row(source.id, "${source.name} (${source.lang.uppercase()})$js")
                }
                .sortedBy { it.label.lowercase() }
        }
        val animeSources = remember {
            Injekt.get<AnimeSourceManager>().getCatalogueSources()
                .map { source ->
                    val js = if (source is SumizuriAnimeSource) " · JS" else ""
                    Row(source.id, "${source.name} (${source.lang.uppercase()})$js")
                }
                .sortedBy { it.label.lowercase() }
        }
        val sources = if (isAnime) animeSources else mangaSources

        DisposableEffect(Unit) {
            onDispose { if (changed) SuggestionsJob.startNow(context) }
        }

        fun set(id: Long, state: TriState) {
            val key = id.toString()
            preferredPref.set(
                if (state == TriState.ENABLED_IS) preferred + key else preferred - key,
            )
            excludedPref.set(
                if (state == TriState.ENABLED_NOT) excluded + key else excluded - key,
            )
            changed = true
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.suggestions_sources),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val shown = remember(query, sources) {
                val q = query.trim()
                if (q.isEmpty()) sources else sources.filter { it.label.contains(q, ignoreCase = true) }
            }
            LazyColumn(contentPadding = contentPadding) {
                item(key = "tabs") {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        listOf(
                            stringResource(KMR.strings.feed_tab_manga) + " (${mangaSources.size})",
                            stringResource(KMR.strings.feed_tab_anime) + " (${animeSources.size})",
                        ).forEachIndexed { index, title ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                        }
                    }
                }
                item(key = "help") {
                    Text(
                        text = stringResource(KMR.strings.suggestions_sources_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = SettingsItemsPaddings.Horizontal,
                            vertical = 8.dp,
                        ),
                    )
                }
                item(key = "search") {
                    SourcesSearchBox(
                        searchQuery = query,
                        onChangeSearchQuery = { query = it.orEmpty() },
                        placeholderText = stringResource(KMR.strings.suggestions_sources_search),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
                    )
                }
                items(shown, key = { (if (isAnime) "a" else "m") + it.id }) { source ->
                    val key = source.id.toString()
                    TriStateItem(
                        label = source.label,
                        state = when (key) {
                            in preferred -> TriState.ENABLED_IS
                            in excluded -> TriState.ENABLED_NOT
                            else -> TriState.DISABLED
                        },
                        onClick = { set(source.id, it) },
                    )
                }
            }
        }
    }
}
