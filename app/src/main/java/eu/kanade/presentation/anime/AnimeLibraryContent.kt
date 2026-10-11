package eu.kanade.presentation.anime

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.tachiyomi.ui.library.AnimeLibraryScreenModel
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * KMK: The anime half of the library. Anime are shown by the manga library's own content (grid or list, group
 * tabs, badges, continue button); [AnimeLibraryScreenModel] turns them into its items.
 */
@Composable
fun AnimeLibraryContent(
    state: AnimeLibraryScreenModel.State,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onChangeCurrentPage: (Int) -> Unit,
    onClickAnime: (Long) -> Unit,
    onLongClickAnime: (Long) -> Unit,
    onClickContinueWatching: (Long) -> Unit,
    onRefresh: () -> Boolean,
    onGlobalSearchClicked: () -> Unit,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
) {
    when {
        state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
        state.isLibraryEmpty -> EmptyScreen(
            message = stringResource(KMR.strings.anime_library_empty),
            modifier = Modifier.padding(contentPadding),
        )
        else -> LibraryContent(
            categories = state.categories,
            activeCategoryIndex = state.coercedActivePage,
            searchQuery = searchQuery,
            // Anime have no selection mode: a long press sets the anime's categories
            selection = emptySet(),
            contentPadding = contentPadding,
            currentPage = state.coercedActivePage,
            hasActiveFilters = state.hasActiveFilters,
            showPageTabs = state.showCategoryTabs || !searchQuery.isNullOrEmpty(),
            onChangeCurrentPage = onChangeCurrentPage,
            // Anime items carry the negated anime id, see Anime.coverId()
            onClickManga = { onClickAnime(-it) },
            onContinueReadingClicked = { anime: LibraryManga ->
                onClickContinueWatching(-anime.id)
            }.takeIf { state.showContinueButton },
            onToggleSelection = { _, _ -> },
            onToggleRangeSelection = { _, anime -> onLongClickAnime(-anime.id) },
            onRefresh = onRefresh,
            onGlobalSearchClicked = onGlobalSearchClicked,
            getItemCountForCategory = { category ->
                state.items[category.id]?.size.takeIf { state.showItemCount }
            },
            getDisplayMode = getDisplayMode,
            getColumnsForOrientation = getColumnsForOrientation,
            getItemsForCategory = { state.items[it.id].orEmpty() },
        )
    }
}
