package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.CategoryScreen
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: "Edit categories" for the anime library. Same screen as the manga one, working on the anime categories.
 */
class AnimeCategoryScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { AnimeCategoryScreenModel() }

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
            onClickHide = screenModel::hideCategory,
            navigateUp = navigator::pop,
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
    }
}

class AnimeCategoryScreenModel(
    private val repository: AnimeRepository = Injekt.get(),
) : StateScreenModel<CategoryScreenState>(CategoryScreenState.Loading) {

    init {
        screenModelScope.launchIO {
            repository.subscribeCategories().collectLatest { categories ->
                mutableState.update {
                    CategoryScreenState.Success(
                        categories = categories.toImmutableList(),
                        dialog = (it as? CategoryScreenState.Success)?.dialog,
                    )
                }
            }
        }
    }

    fun createCategory(name: String) {
        screenModelScope.launchIO { repository.createCategory(name) }
    }

    fun renameCategory(category: Category, name: String) {
        screenModelScope.launchIO { repository.renameCategory(category.id, name) }
    }

    fun hideCategory(category: Category) {
        screenModelScope.launchIO { repository.setCategoryHidden(category.id, !category.hidden) }
    }

    fun deleteCategory(categoryId: Long) {
        screenModelScope.launchIO { repository.deleteCategory(categoryId) }
    }

    fun changeOrder(category: Category, newIndex: Int) {
        val categories = (state.value as? CategoryScreenState.Success)?.categories ?: return
        val reordered = categories.toMutableList()
        if (!reordered.remove(category)) return
        reordered.add(newIndex.coerceIn(0, reordered.size), category)
        screenModelScope.launchIO { repository.reorderCategories(reordered.map { it.id }) }
    }

    fun showDialog(dialog: CategoryDialog) {
        mutableState.update { if (it is CategoryScreenState.Success) it.copy(dialog = dialog) else it }
    }

    fun dismissDialog() {
        mutableState.update { if (it is CategoryScreenState.Success) it.copy(dialog = null) else it }
    }
}
