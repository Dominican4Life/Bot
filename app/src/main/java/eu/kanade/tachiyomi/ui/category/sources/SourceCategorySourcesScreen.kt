package eu.kanade.tachiyomi.ui.category.sources

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.jssource.JsAnimeSource
import eu.kanade.tachiyomi.jssource.JsMangaSource
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Picks the sources of one source category. The button in the toolbar switches between manga and anime
 * sources. Both are saved as "sourceId|category", like the long-press menu of Browse › Sources does.
 */
class SourceCategorySourcesScreen(private val category: String) : Screen() {

    private data class Row(val id: String, val label: String)

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val pref = remember { Injekt.get<SourcePreferences>().sourcesTabSourcesInCategories() }
        val inCategories by pref.collectAsState()
        var anime by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }

        val mangaSources = remember {
            Injekt.get<SourceManager>().getVisibleOnlineSources()
                .map { source ->
                    val js = if (source is JsMangaSource) " · JS" else ""
                    Row(source.id.toString(), "${source.name} (${source.lang.uppercase()})$js")
                }
                .sortedBy { it.label.lowercase() }
        }
        val animeSources = remember {
            Injekt.get<AnimeSourceManager>().getCatalogueSources()
                .map { source ->
                    val js = if (source is JsAnimeSource) " · JS" else ""
                    Row(source.id.toString(), "${source.name} (${source.lang.uppercase()})$js")
                }
                .sortedBy { it.label.lowercase() }
        }
        val sources = if (anime) animeSources else mangaSources
        val checked = remember(inCategories) {
            inCategories.filter { it.substringAfter('|') == category }.map { it.substringBefore('|') }.toSet()
        }

        fun toggle(id: String) {
            val entry = "$id|$category"
            pref.getAndSet { if (entry in it) it - entry else it + entry }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = category,
                    subtitle = stringResource(
                        if (anime) KMR.strings.source_category_anime_sources else KMR.strings.source_category_manga_sources,
                    ),
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(KMR.strings.source_category_switch),
                                    icon = if (anime) Icons.Outlined.Movie else Icons.AutoMirrored.Outlined.MenuBook,
                                    iconTint = MaterialTheme.colorScheme.primary,
                                    onClick = { anime = !anime },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val shown = remember(query, sources) {
                val q = query.trim()
                if (q.isEmpty()) sources else sources.filter { it.label.contains(q, ignoreCase = true) }
            }
            LazyColumn(contentPadding = contentPadding) {
                item(key = "help") {
                    Text(
                        text = stringResource(KMR.strings.source_category_pick_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
                    )
                }
                item(key = "search") {
                    SourcesSearchBox(
                        searchQuery = query,
                        onChangeSearchQuery = { query = it.orEmpty() },
                        placeholderText = stringResource(KMR.strings.sources_search_hint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
                    )
                }
                items(shown, key = { (if (anime) "a" else "m") + it.id }) { source ->
                    CheckboxItem(
                        label = source.label,
                        checked = source.id in checked,
                        onClick = { toggle(source.id) },
                    )
                }
            }
        }
    }
}
