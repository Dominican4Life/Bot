package eu.kanade.presentation.anime

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/*
 * KMK: several anime at once, from Browse (add to the library) and from the Library (categories, seen, remove).
 * The work isn't tied to the screen, which may close right away.
 */

private val bulkScope get() = CoroutineScope(Dispatchers.IO)

/** Adds the anime to the library */
fun addAnimeToLibrary(animeIds: Collection<Long>, then: () -> Unit = {}) {
    val repository = Injekt.get<AnimeRepository>()
    bulkScope.launch {
        animeIds.forEach { id ->
            repository.getAnime(id)?.takeUnless { it.favorite }?.let { repository.setFavorite(it, true) }
        }
        then()
    }
}

/** Takes the anime out of the library */
fun removeAnimeFromLibrary(animeIds: Collection<Long>) {
    val repository = Injekt.get<AnimeRepository>()
    bulkScope.launch {
        animeIds.forEach { id -> repository.getAnime(id)?.let { repository.setFavorite(it, false) } }
    }
}

/** Marks every episode of the anime seen or unseen */
fun markAnimeSeen(animeIds: Collection<Long>, seen: Boolean) {
    val repository = Injekt.get<AnimeRepository>()
    bulkScope.launch {
        animeIds.forEach { id -> repository.setSeen(repository.getEpisodes(id), seen) }
    }
}

/**
 * "Set categories" for several anime. A category only some of them are in starts as "mixed" and is left as it is
 * unless changed. With [addToLibrary], they're also added to the library (straight away when there are no
 * categories).
 */
@Composable
fun AnimeBulkCategoryDialog(
    animeIds: List<Long>,
    addToLibrary: Boolean,
    onDismissRequest: () -> Unit,
    onDone: () -> Unit = {},
) {
    val navigator = LocalNavigator.current
    val repository = remember { Injekt.get<AnimeRepository>() }
    val selection = produceState<ImmutableList<CheckboxState<Category>>?>(null, animeIds) {
        value = withIOContext {
            val links = animeIds.associateWith { repository.getCategoryIds(it) }
            repository.getCategories().map { category ->
                val asCategory = category.asCategory()
                val count = links.values.count { category.id in it }
                when {
                    animeIds.isNotEmpty() && count == animeIds.size -> CheckboxState.State.Checked(asCategory)
                    count > 0 -> CheckboxState.TriState.Exclude(asCategory)
                    else -> CheckboxState.State.None(asCategory)
                }
            }.toImmutableList()
        }
    }.value ?: return

    if (selection.isEmpty() && addToLibrary) {
        // No categories to pick from: just add them
        LaunchedEffect(animeIds) {
            addAnimeToLibrary(animeIds)
            onDone()
            onDismissRequest()
        }
        return
    }

    ChangeCategoryDialog(
        initialSelection = selection,
        onDismissRequest = onDismissRequest,
        onEditCategories = { navigator?.push(CategoryScreen(CategoryScreen.TAB_ANIME)) },
        onConfirm = { include, exclude ->
            bulkScope.launch {
                animeIds.forEach { id ->
                    if (addToLibrary) {
                        repository.getAnime(id)?.takeUnless { it.favorite }?.let { repository.setFavorite(it, true) }
                    }
                    val now = repository.getCategoryIds(id)
                    repository.setCategories(id, (now - exclude.toSet()) + include)
                }
            }
            onDone()
        },
    )
}

/** Asks before taking the anime out of the library */
@Composable
fun AnimeRemoveDialog(count: Int, onDismissRequest: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.remove_from_library)) },
        text = { Text(stringResource(KMR.strings.anime_remove_selected_confirm, count)) },
        confirmButton = {
            TextButton(onClick = {
                onDismissRequest()
                onConfirm()
            }) { Text(stringResource(MR.strings.action_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

/** The bar shown at the bottom of the anime library while titles are selected */
@Composable
fun AnimeSelectionBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onSetCategories: () -> Unit,
    onMarkSeen: () -> Unit,
    onMarkUnseen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large.copy(
            bottomStart = androidx.compose.foundation.shape.ZeroCornerSize,
            bottomEnd = androidx.compose.foundation.shape.ZeroCornerSize,
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            BarButton(Icons.Outlined.Close, stringResource(MR.strings.action_cancel), onClear)
            Text(
                text = "$count",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp).weight(1f),
            )
            BarButton(Icons.Outlined.SelectAll, stringResource(MR.strings.action_select_all), onSelectAll)
            BarButton(Icons.Outlined.FlipToBack, stringResource(MR.strings.action_select_inverse), onInvert)
            BarButton(Icons.AutoMirrored.Outlined.Label, stringResource(MR.strings.action_move_category), onSetCategories)
            BarButton(Icons.Outlined.DoneAll, stringResource(KMR.strings.anime_action_mark_seen), onMarkSeen)
            BarButton(Icons.Outlined.RemoveDone, stringResource(KMR.strings.anime_action_mark_unseen), onMarkUnseen)
            BarButton(Icons.Outlined.DeleteOutline, stringResource(MR.strings.remove_from_library), onRemove)
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = label)
    }
}
