package eu.kanade.presentation.anime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.category.anime.asCategory
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: "Set categories" for one anime, the same dialog the manga use. Its Edit button opens the Anime section of
 * More › Categories.
 */
@Composable
fun AnimeCategoryDialog(
    animeId: Long,
    onDismissRequest: () -> Unit,
) {
    val navigator = LocalNavigator.current
    val repository = remember { Injekt.get<AnimeRepository>() }
    val selection = produceState<ImmutableList<CheckboxState<Category>>?>(null, animeId) {
        value = withIOContext {
            val current = repository.getCategoryIds(animeId)
            repository.getCategories().map { category ->
                val asCategory = category.asCategory()
                if (category.id in current) {
                    CheckboxState.State.Checked(asCategory)
                } else {
                    CheckboxState.State.None(asCategory)
                }
            }.toImmutableList()
        }
    }.value ?: return

    ChangeCategoryDialog(
        initialSelection = selection,
        onDismissRequest = onDismissRequest,
        onEditCategories = { navigator?.push(CategoryScreen(CategoryScreen.TAB_ANIME)) },
        onConfirm = { include, _ ->
            // Not tied to the dialog, which leaves the screen right away
            CoroutineScope(Dispatchers.IO).launch { repository.setCategories(animeId, include) }
        },
    )
}

/** True once the anime library has at least one category */
@Composable
fun rememberHasAnimeCategories(): Boolean {
    val repository = remember { Injekt.get<AnimeRepository>() }
    return produceState(false) {
        repository.subscribeCategories().collect { value = it.isNotEmpty() }
    }.value
}
