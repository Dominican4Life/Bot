package eu.kanade.tachiyomi.ui.category.anime

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.anime.AnimeCategory
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: The anime section of More › Categories: create, rename, delete and reorder anime categories. */
class AnimeCategoryScreenModel(
    private val repository: AnimeRepository = Injekt.get(),
) : StateScreenModel<AnimeCategoryScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            repository.subscribeCategories().collectLatest { list ->
                mutableState.update { it.copy(categories = list.toImmutableList(), loaded = true) }
            }
        }
    }

    fun create(name: String) {
        screenModelScope.launchIO { repository.createCategory(name.trim()) }
    }

    fun rename(id: Long, name: String) {
        screenModelScope.launchIO { repository.renameCategory(id, name.trim()) }
    }

    fun delete(id: Long) {
        screenModelScope.launchIO { repository.deleteCategory(id) }
    }

    fun move(category: Category, newIndex: Int) {
        val target = state.value.categories.find { it.id == category.id } ?: return
        screenModelScope.launchIO { repository.moveCategory(target, newIndex) }
    }

    fun showDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    sealed interface Dialog {
        data object Create : Dialog
        data class Rename(val category: Category) : Dialog
        data class Delete(val category: Category) : Dialog
    }

    @Immutable
    data class State(
        val categories: ImmutableList<AnimeCategory> = persistentListOf(),
        val loaded: Boolean = false,
        val dialog: Dialog? = null,
    )
}

/** Shown with the same list items and dialogs as manga categories */
fun AnimeCategory.asCategory() = Category(id = id, name = name, order = order, flags = 0L, hidden = false)
