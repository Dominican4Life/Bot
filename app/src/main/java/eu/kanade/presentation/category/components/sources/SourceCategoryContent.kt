package eu.kanade.presentation.category.components.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun SourceCategoryContent(
    categories: ImmutableList<String>,
    lazyListState: LazyListState,
    paddingValues: PaddingValues,
    onClickRename: (String) -> Unit,
    onClickDelete: (String) -> Unit,
    // KMK -->
    onClickCategory: (String) -> Unit = onClickRename,
    // KMK <--
) {
    // KMK: how many manga and anime sources each category holds
    val inCategories by remember {
        Injekt.get<SourcePreferences>().sourcesTabSourcesInCategories()
    }.collectAsState()
    val animeIds = remember { Injekt.get<AnimeSourceManager>().getCatalogueSources().map { it.id.toString() }.toSet() }
    LazyColumn(
        state = lazyListState,
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        items(categories, key = { "source-category-$it" }) { category ->
            SourceCategoryListItem(
                modifier = Modifier.animateItem(),
                category = category,
                onRename = { onClickRename(category) },
                onDelete = { onClickDelete(category) },
                // KMK -->
                onClick = { onClickCategory(category) },
                summary = run {
                    val ids = inCategories.filter { it.substringAfter('|') == category }.map { it.substringBefore('|') }
                    val anime = ids.count { it in animeIds }
                    stringResource(KMR.strings.source_category_counts, ids.size - anime, anime)
                },
                // KMK <--
            )
        }
    }
}
