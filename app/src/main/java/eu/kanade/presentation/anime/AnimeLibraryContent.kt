package eu.kanade.presentation.anime

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import tachiyomi.domain.library.service.LibraryPreferences
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
    val filtered = remember(list, searchQuery) {
        if (searchQuery.isNullOrBlank()) {
            list
        } else {
            list.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
    }
    if (filtered.isEmpty()) {
        EmptyScreen(
            message = stringResource(KMR.strings.anime_library_empty),
            modifier = Modifier.padding(contentPadding),
        )
        return
    }
    FastScrollLazyVerticalGrid(
        columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(128.dp),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filtered, key = { it.id }) { anime ->
            AnimeGridItem(
                anime = anime.copy(favorite = false),
                source = sourceManager.get(anime.source),
                onClick = { onClickAnime(anime) },
            )
        }
    }
}
