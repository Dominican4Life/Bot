package eu.kanade.presentation.anime

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: The anime half of the library: a grid of anime added from Browse. */
@Composable
fun AnimeLibraryContent(
    contentPadding: PaddingValues,
    searchQuery: String?,
    onClickAnime: (Anime) -> Unit,
) {
    val repository = remember { Injekt.get<AnimeRepository>() }
    val sourceManager = remember { Injekt.get<AnimeSourceManager>() }
    val favorites by remember { repository.subscribeFavorites() }.collectAsState(initial = null)
    // Follow the library's columns setting
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columnsPref = remember(isLandscape) {
        if (isLandscape) libraryPreferences.landscapeColumns() else libraryPreferences.portraitColumns()
    }
    val columns by columnsPref.collectAsState()

    val list = favorites ?: return
    // KMK: category tabs, like the manga library. "Default" holds the anime in no category.
    val categories by remember { repository.subscribeCategories() }.collectAsState(initial = emptyList())
    val links by remember { repository.subscribeCategoryLinks() }.collectAsState(initial = emptyMap())
    val defaultName = stringResource(MR.strings.label_default)
    val pages = remember(categories, links, list) {
        val uncategorized = list.filter { links[it.id].isNullOrEmpty() }
        buildList {
            if (categories.isEmpty() || uncategorized.isNotEmpty()) add(Triple(-1L, defaultName, uncategorized))
            categories.forEach { category ->
                add(Triple(category.id, category.name, list.filter { category.id in links[it.id].orEmpty() }))
            }
        }
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val current = pages.getOrNull(page.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
    var categoryDialogFor by remember { mutableStateOf<Long?>(null) }
    categoryDialogFor?.let { id -> AnimeCategoryDialog(animeId = id, onDismissRequest = { categoryDialogFor = null }) }
    val onLongClickAnime: (Anime) -> Unit = { categoryDialogFor = it.id }

    val filtered = remember(current, searchQuery) {
        val inPage = current?.third.orEmpty()
        if (searchQuery.isNullOrBlank()) {
            inPage
        } else {
            inPage.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
    }
    val showTabs = pages.size > 1
    val tabRow: @Composable () -> Unit = {
        if (showTabs) {
            PrimaryScrollableTabRow(
                selectedTabIndex = page.coerceIn(0, pages.size - 1),
                edgePadding = 0.dp,
                modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
            ) {
                pages.forEachIndexed { index, (_, name, items) ->
                    Tab(
                        selected = page == index,
                        onClick = { page = index },
                        text = { Text("$name (${items.size})") },
                    )
                }
            }
        }
    }
    val topPadding = if (showTabs) 0.dp else contentPadding.calculateTopPadding()
    if (filtered.isEmpty()) {
        Column {
            tabRow()
            EmptyScreen(
                message = stringResource(KMR.strings.anime_library_empty),
                modifier = Modifier.padding(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
            )
        }
        return
    }
    // Same items and display mode as the manga library
    val displayMode by remember { libraryPreferences.displayMode() }.collectAsState()
    Column {
        tabRow()
        FastScrollLazyVerticalGrid(
            columns = when {
                displayMode == LibraryDisplayMode.List -> GridCells.Fixed(1)
                columns > 0 -> GridCells.Fixed(columns)
                else -> GridCells.Adaptive(128.dp)
            },
            contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = topPadding + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
            horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
        ) {
            items(filtered, key = { it.id }) { anime ->
                when (displayMode) {
                    LibraryDisplayMode.List -> MangaListItem(
                        coverData = anime.asMangaCover(),
                        title = anime.title,
                        onClick = { onClickAnime(anime) },
                        onLongClick = { onLongClickAnime(anime) },
                        badge = {},
                    )
                    LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> MangaCompactGridItem(
                        coverData = anime.asMangaCover(),
                        title = anime.title.takeIf { displayMode == LibraryDisplayMode.CompactGrid },
                        onClick = { onClickAnime(anime) },
                        onLongClick = { onLongClickAnime(anime) },
                    )
                    else -> MangaComfortableGridItem(
                        coverData = anime.asMangaCover(),
                        title = anime.title,
                        onClick = { onClickAnime(anime) },
                        onLongClick = { onLongClickAnime(anime) },
                        usePanoramaCover = displayMode == LibraryDisplayMode.ComfortableGridPanorama,
                    )
                }
            }
        }
    }
}
