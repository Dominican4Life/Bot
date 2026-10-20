package eu.kanade.presentation.more.settings.screen.advanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.AnimeSourceIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.data.DatabaseHandler
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: "Clear Anime database" (Aniyomi's): removes non-library anime, with their episodes and watch history, from
 * the chosen sources. Seasons and merged members of library anime are kept (see anime_cleanup.sq).
 */
class ClearAnimeDatabaseScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { ClearAnimeDatabaseScreenModel() }
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()

        when (val s = state) {
            is ClearAnimeDatabaseScreenModel.State.Loading -> LoadingScreen()
            is ClearAnimeDatabaseScreenModel.State.Ready -> {
                if (s.showConfirmation) {
                    var keepSeenAnime by remember { mutableStateOf(true) }
                    AlertDialog(
                        title = {
                            Text(text = stringResource(MR.strings.are_you_sure))
                        },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                            ) {
                                Text(text = stringResource(MR.strings.clear_database_text))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(AMR.strings.clear_db_exclude_seen),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = keepSeenAnime,
                                        onCheckedChange = { keepSeenAnime = it },
                                    )
                                }
                                if (!keepSeenAnime) {
                                    Text(
                                        text = stringResource(AMR.strings.clear_database_seen_history_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        onDismissRequest = model::hideConfirmation,
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    scope.launchUI {
                                        model.removeAnimeBySourceId(keepSeenAnime)
                                        model.clearSelection()
                                        model.hideConfirmation()
                                        context.toast(MR.strings.clear_database_completed)
                                    }
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = model::hideConfirmation) {
                                Text(text = stringResource(MR.strings.action_cancel))
                            }
                        },
                    )
                }

                Scaffold(
                    topBar = { scrollBehavior ->
                        AppBar(
                            title = stringResource(KMR.strings.pref_clear_anime_database),
                            navigateUp = navigator::pop,
                            actions = {
                                if (s.items.isNotEmpty()) {
                                    AppBarActions(
                                        actions = persistentListOf(
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_select_all),
                                                icon = Icons.Outlined.SelectAll,
                                                onClick = model::selectAll,
                                            ),
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_select_inverse),
                                                icon = Icons.Outlined.FlipToBack,
                                                onClick = model::invertSelection,
                                            ),
                                        ),
                                    )
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { contentPadding ->
                    if (s.items.isEmpty()) {
                        EmptyScreen(
                            message = stringResource(MR.strings.database_clean),
                            modifier = Modifier.padding(contentPadding),
                        )
                    } else {
                        LazyColumnWithAction(
                            contentPadding = contentPadding,
                            actionLabel = stringResource(MR.strings.action_delete),
                            actionEnabled = s.selection.isNotEmpty(),
                            onClickAction = model::showConfirmation,
                        ) {
                            items(s.items, key = { it.id }) { item ->
                                ClearAnimeDatabaseItem(
                                    item = item,
                                    isSelected = item.id in s.selection,
                                    onClickSelect = { model.toggleSelection(item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ClearAnimeDatabaseItem(
        item: ClearAnimeDatabaseScreenModel.SourceItem,
        isSelected: Boolean,
        onClickSelect: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .selectedBackground(isSelected)
                .clickable(onClick = onClickSelect)
                .padding(horizontal = 8.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimeSourceIcon(sourceId = item.id)
            Column(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text = stringResource(MR.strings.clear_database_source_item_count, item.count))
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClickSelect() },
            )
        }
    }
}

private class ClearAnimeDatabaseScreenModel :
    StateScreenModel<ClearAnimeDatabaseScreenModel.State>(State.Loading) {
    private val handler: DatabaseHandler = Injekt.get()
    private val sourceManager: AnimeSourceManager = Injekt.get()

    init {
        screenModelScope.launchIO {
            handler
                .subscribeToList {
                    anime_cleanupQueries.getSourceIdsWithNonLibraryAnime { source, count ->
                        val animeSource = sourceManager.get(source)
                        SourceItem(
                            id = source,
                            name = animeSource
                                ?.let { if (it.lang.isBlank()) it.name else "${it.name} (${it.lang.uppercase()})" }
                                ?: source.toString(),
                            count = count,
                        )
                    }
                }
                .collectLatest { list ->
                    mutableState.update { old ->
                        val items = list.sortedBy { it.name.lowercase() }
                        when (old) {
                            State.Loading -> State.Ready(items)
                            is State.Ready -> old.copy(items = items)
                        }
                    }
                }
        }
    }

    suspend fun removeAnimeBySourceId(keepSeenAnime: Boolean) = withNonCancellableContext {
        val state = state.value as? State.Ready ?: return@withNonCancellableContext
        handler.await(inTransaction = true) {
            anime_cleanupQueries.deleteNonLibraryAnime(state.selection, keepSeenAnime.toLong())
            anime_cleanupQueries.clearMissingParents()
        }
    }

    fun toggleSelection(sourceId: Long) = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        val selection = if (sourceId in state.selection) {
            state.selection - sourceId
        } else {
            state.selection + sourceId
        }
        state.copy(selection = selection)
    }

    fun clearSelection() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selection = emptyList())
    }

    fun selectAll() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selection = state.items.fastMap { it.id })
    }

    fun invertSelection() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(
            selection = state.items
                .fastMap { it.id }
                .filterNot { it in state.selection },
        )
    }

    fun showConfirmation() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = true)
    }

    fun hideConfirmation() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = false)
    }

    @Immutable
    data class SourceItem(
        val id: Long,
        val name: String,
        val count: Long,
    )

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Ready(
            val items: List<SourceItem>,
            val selection: List<Long> = emptyList(),
            val showConfirmation: Boolean = false,
        ) : State
    }
}
