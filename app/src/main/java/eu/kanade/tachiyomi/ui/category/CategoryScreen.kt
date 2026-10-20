package eu.kanade.tachiyomi.ui.category

import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.CategoryScreen
import eu.kanade.presentation.category.SourceCategoryScreen
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.category.anime.AnimeCategoryScreenModel
import eu.kanade.tachiyomi.ui.category.anime.asCategory
import eu.kanade.tachiyomi.ui.category.sources.SourceCategoryDialog
import eu.kanade.tachiyomi.ui.category.sources.SourceCategoryEvent
import eu.kanade.tachiyomi.ui.category.sources.SourceCategoryScreenModel
import eu.kanade.tachiyomi.ui.category.sources.SourceCategoryScreenState
import eu.kanade.tachiyomi.ui.category.sources.SourceCategorySourcesScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Edit categories. KMK: three sections, switched with tabs under the toolbar: manga categories, anime categories and
 * source categories (which group sources in Browse › Sources).
 */
class CategoryScreen(
    // KMK -->
    private val initialTab: Int = TAB_MANGA,
    // KMK <--
) : Screen() {

    @Composable
    override fun Content() {
        // KMK -->
        var tab by rememberSaveable { mutableIntStateOf(initialTab) }
        val tabs: @Composable () -> Unit = {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(
                    stringResource(KMR.strings.feed_tab_manga),
                    stringResource(KMR.strings.feed_tab_anime),
                    stringResource(MR.strings.label_sources),
                ).forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                        // KMK: like the library's tabs, only the chosen one is colored
                        unselectedContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        when (tab) {
            TAB_ANIME -> AnimeCategories(tabs)
            TAB_SOURCES -> SourceCategories(tabs)
            else -> MangaCategories(tabs)
        }
        // KMK <--
    }

    @Composable
    private fun MangaCategories(tabs: @Composable () -> Unit) {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CategoryScreenModel() }

        val state by screenModel.state.collectAsState()

        if (state is CategoryScreenState.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as CategoryScreenState.Success

        CategoryScreen(
            state = successState,
            onClickCreate = { screenModel.showDialog(CategoryDialog.Create) },
            onClickRename = { screenModel.showDialog(CategoryDialog.Rename(it)) },
            onClickDelete = { screenModel.showDialog(CategoryDialog.Delete(it)) },
            onChangeOrder = screenModel::changeOrder,
            // KMK -->
            onClickHide = screenModel::hideCategory,
            // KMK <--
            navigateUp = navigator::pop,
            // KMK
            tabs = tabs,
        )

        when (val dialog = successState.dialog) {
            null -> {}
            CategoryDialog.Create -> {
                CategoryCreateDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onCreate = screenModel::createCategory,
                    categories = successState.categories.fastMap { it.name }.toImmutableList(),
                )
            }
            is CategoryDialog.Rename -> {
                CategoryRenameDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onRename = { screenModel.renameCategory(dialog.category, it) },
                    categories = successState.categories.fastMap { it.name }.toImmutableList(),
                    category = dialog.category.name,
                )
            }
            is CategoryDialog.Delete -> {
                CategoryDeleteDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onDelete = { screenModel.deleteCategory(dialog.category.id) },
                    category = dialog.category.name,
                )
            }
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                if (event is CategoryEvent.LocalizedMessage) {
                    context.toast(event.stringRes)
                }
            }
        }
    }

    // KMK -->
    @Composable
    private fun AnimeCategories(tabs: @Composable () -> Unit) {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { AnimeCategoryScreenModel() }
        val state by screenModel.state.collectAsState()

        if (!state.loaded) {
            LoadingScreen()
            return
        }
        // Re-read when a category is hidden or shown
        val hidden by remember { eu.kanade.presentation.anime.AnimeLibraryPrefs.hiddenCategories() }.collectAsState()
        val categories = remember(state.categories, hidden) {
            state.categories.map { it.asCategory() }.toImmutableList()
        }
        val names = categories.fastMap { it.name }.toImmutableList()

        CategoryScreen(
            state = CategoryScreenState.Success(categories),
            onClickCreate = { screenModel.showDialog(AnimeCategoryScreenModel.Dialog.Create) },
            onClickRename = { screenModel.showDialog(AnimeCategoryScreenModel.Dialog.Rename(it)) },
            onClickDelete = { screenModel.showDialog(AnimeCategoryScreenModel.Dialog.Delete(it)) },
            onChangeOrder = screenModel::move,
            onClickHide = screenModel::toggleHidden,
            navigateUp = navigator::pop,
            tabs = tabs,
            emptyText = KMR.strings.anime_categories_empty,
        )

        val dismiss = { screenModel.showDialog(null) }
        when (val dialog = state.dialog) {
            null -> {}
            AnimeCategoryScreenModel.Dialog.Create -> CategoryCreateDialog(
                onDismissRequest = dismiss,
                onCreate = screenModel::create,
                categories = names,
            )
            is AnimeCategoryScreenModel.Dialog.Rename -> CategoryRenameDialog(
                onDismissRequest = dismiss,
                onRename = { screenModel.rename(dialog.category.id, it) },
                categories = names,
                category = dialog.category.name,
            )
            is AnimeCategoryScreenModel.Dialog.Delete -> CategoryDeleteDialog(
                onDismissRequest = dismiss,
                onDelete = { screenModel.delete(dialog.category.id) },
                category = dialog.category.name,
            )
        }
    }

    @Composable
    private fun SourceCategories(tabs: @Composable () -> Unit) {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { SourceCategoryScreenModel() }
        val state by screenModel.state.collectAsState()

        if (state is SourceCategoryScreenState.Loading) {
            LoadingScreen()
            return
        }
        val successState = state as SourceCategoryScreenState.Success

        SourceCategoryScreen(
            state = successState,
            onClickCreate = { screenModel.showDialog(SourceCategoryDialog.Create) },
            onClickRename = { screenModel.showDialog(SourceCategoryDialog.Rename(it)) },
            onClickDelete = { screenModel.showDialog(SourceCategoryDialog.Delete(it)) },
            navigateUp = navigator::pop,
            onClickCategory = { navigator.push(SourceCategorySourcesScreen(it)) },
            tabs = tabs,
        )

        when (val dialog = successState.dialog) {
            null -> {}
            SourceCategoryDialog.Create -> CategoryCreateDialog(
                onDismissRequest = screenModel::dismissDialog,
                onCreate = { screenModel.createCategory(it) },
                categories = successState.categories,
                title = stringResource(MR.strings.action_add_category),
            )
            is SourceCategoryDialog.Rename -> CategoryRenameDialog(
                onDismissRequest = screenModel::dismissDialog,
                onRename = { screenModel.renameCategory(dialog.category, it) },
                categories = successState.categories,
                category = dialog.category,
            )
            is SourceCategoryDialog.Delete -> CategoryDeleteDialog(
                onDismissRequest = screenModel::dismissDialog,
                onDelete = { screenModel.deleteCategory(dialog.category) },
                title = stringResource(MR.strings.delete_category),
                text = stringResource(MR.strings.delete_category_confirmation, dialog.category),
            )
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                if (event is SourceCategoryEvent.LocalizedMessage) {
                    context.toast(event.stringRes)
                }
            }
        }
    }

    companion object {
        const val TAB_MANGA = 0
        const val TAB_ANIME = 1
        const val TAB_SOURCES = 2
    }
    // KMK <--
}
