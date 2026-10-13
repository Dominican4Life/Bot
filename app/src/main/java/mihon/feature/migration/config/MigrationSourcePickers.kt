package mihon.feature.migration.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.domain.source.interactor.GetSourcesWithFavoriteCount
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AnimatedFloatingSearchBox
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SOURCE_SEARCH_BOX_HEIGHT
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.first
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** A window over the whole screen, used by the source pickers of the Migrate window */
@Composable
private fun FullScreenWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

/**
 * KMK: Picks the sources to search and their order, like the old "Select sources" page: nothing is selected at
 * first; tap a source to select it, then drag the selected ones into the order they're searched in.
 */
@Composable
fun SearchSourcesPickerDialog(
    initial: List<Long>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<Source>) -> Unit,
) {
    val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
    val pinned = remember { sourcePreferences.pinnedSources().get().mapNotNull { it.toLongOrNull() }.toSet() }
    val disabled = remember { sourcePreferences.disabledSources().get().mapNotNull { it.toLongOrNull() }.toSet() }

    // Every source you could search, by name
    val all by produceState<List<Source>?>(null) {
        value = withIOContext {
            val languages = sourcePreferences.enabledLanguages().get()
            Injekt.get<SourceManager>().getVisibleSources()
                .filterIsInstance<HttpSource>()
                .filter { it.lang in languages }
                .map { Source(id = it.id, lang = it.lang, name = it.name, supportsLatest = false, isStub = false) }
                .sortedBy { "${it.name} (${it.lang})".lowercase() }
        }
    }
    var selected by remember { mutableStateOf(initial) }
    var searchQuery by remember { mutableStateOf("") }

    FullScreenWindow(onDismissRequest) {
        val sources = all
        if (sources == null) {
            LoadingScreen()
            return@FullScreenWindow
        }
        val byId = remember(sources) { sources.associateBy { it.id } }
        val showLanguage = remember(sources) { sources.distinctBy { it.lang }.size > 1 }
        val matches: (Source) -> Boolean = { source ->
            searchQuery.isBlank() || searchQuery.split(",").any {
                val input = it.trim()
                input.isNotEmpty() && (source.name.contains(input, true) || source.id == input.toLongOrNull())
            }
        }
        val selectedSources = selected.mapNotNull { byId[it] }.filter(matches)
        val availableSources = sources.filter { it.id !in selected && matches(it) }
        fun selectWhere(predicate: (Long) -> Boolean) {
            selected = sources.map { it.id }.filter(predicate)
        }

        val listState = rememberLazyListState()
        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(SYMR.strings.select_sources),
                    navigateUp = onDismissRequest,
                    scrollBehavior = it,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectPinnedLabel),
                                    icon = Icons.Outlined.PushPin,
                                    onClick = { selectWhere { id -> id in pinned } },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectNoneLabel),
                                    icon = Icons.Outlined.Deselect,
                                    onClick = { selected = emptyList() },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectEnabledLabel),
                                    onClick = { selectWhere { id -> id !in disabled } },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectAllLabel),
                                    onClick = { selectWhere { true } },
                                ),
                            ),
                        )
                    },
                )
            },
            floatingActionButton = {
                AnimatedVisibility(visible = selected.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                    SmallExtendedFloatingActionButton(
                        text = { Text(text = stringResource(MR.strings.action_ok)) },
                        icon = { Icon(imageVector = Icons.Outlined.Check, contentDescription = null) },
                        onClick = { onConfirm(selected.mapNotNull { byId[it] }) },
                    )
                }
            },
        ) { contentPadding ->
            val reorderState = rememberReorderableLazyListState(listState, contentPadding) { from, to ->
                // Only the selected sources can be dragged; their keys are their ids
                val fromId = from.key as? Long ?: return@rememberReorderableLazyListState
                val toId = to.key as? Long ?: return@rememberReorderableLazyListState
                val list = selected.toMutableList()
                val fromIndex = list.indexOf(fromId)
                val toIndex = list.indexOf(toId)
                if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
                list.add(toIndex, list.removeAt(fromIndex))
                selected = list
            }
            Box(modifier = Modifier.padding(contentPadding)) {
                val density = LocalDensity.current
                var searchBoxHeight by remember { mutableStateOf(SOURCE_SEARCH_BOX_HEIGHT) }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = searchBoxHeight, bottom = 80.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (selectedSources.isNotEmpty()) {
                        item("selected-header") {
                            PickerHeader(stringResource(MR.strings.migrationConfigScreen_selectedHeader))
                        }
                    }
                    itemsIndexed(selectedSources, key = { _, source -> source.id }) { index, source ->
                        ReorderableItem(reorderState, key = source.id, enabled = selectedSources.size > 1) {
                            PickerCard(index == 0, index == selectedSources.lastIndex) {
                                PickerRow(
                                    source = source,
                                    showLanguage = showLanguage,
                                    onClick = { selected = selected - source.id },
                                    trailing = if (selectedSources.size > 1) {
                                        {
                                            Icon(
                                                imageVector = Icons.Outlined.DragHandle,
                                                contentDescription = null,
                                                modifier = Modifier.draggableHandle(),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                    if (availableSources.isNotEmpty()) {
                        item("available-header") {
                            PickerHeader(stringResource(MR.strings.migrationConfigScreen_availableHeader))
                        }
                    }
                    itemsIndexed(availableSources, key = { _, source -> "available-${source.id}" }) { index, source ->
                        PickerCard(index == 0, index == availableSources.lastIndex) {
                            PickerRow(
                                source = source,
                                showLanguage = showLanguage,
                                onClick = { selected = selected + source.id },
                                trailing = null,
                            )
                        }
                    }
                }
                AnimatedFloatingSearchBox(
                    listState = listState,
                    searchQuery = searchQuery,
                    onChangeSearchQuery = { searchQuery = it ?: "" },
                    placeholderText = stringResource(KMR.strings.action_search_for_source),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small)
                        .align(Alignment.TopCenter),
                    onGloballyPositioned = { coordinates ->
                        searchBoxHeight = with(density) { coordinates.size.height.toDp() } +
                            MaterialTheme.padding.small * 2
                    },
                )
            }
        }
    }
}

/**
 * KMK: Picks the sources whose library titles are migrated, when nothing was selected: the list Browse › Migrate
 * used to show, each source with how many library titles it has.
 */
@Composable
fun SourceToMigratePickerDialog(
    initial: Set<Long>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<Pair<Source, Long>>) -> Unit,
) {
    val sources by produceState<List<Pair<Source, Long>>?>(null) {
        value = Injekt.get<GetSourcesWithFavoriteCount>().subscribe().first()
    }
    var picked by remember { mutableStateOf(initial) }

    FullScreenWindow(onDismissRequest) {
        val list = sources
        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(KMR.strings.migrate_source_to_migrate),
                    navigateUp = onDismissRequest,
                    scrollBehavior = it,
                )
            },
            floatingActionButton = {
                AnimatedVisibility(visible = picked.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                    SmallExtendedFloatingActionButton(
                        text = { Text(text = stringResource(MR.strings.action_ok)) },
                        icon = { Icon(imageVector = Icons.Outlined.Check, contentDescription = null) },
                        onClick = { onConfirm(list.orEmpty().filter { it.first.id in picked }) },
                    )
                }
            },
        ) { contentPadding ->
            if (list == null) {
                LoadingScreen(Modifier.padding(contentPadding))
                return@Scaffold
            }
            LazyColumn(
                contentPadding = PaddingValues(bottom = 80.dp),
                modifier = Modifier.padding(contentPadding),
            ) {
                items(list, key = { it.first.id }) { (source, count) ->
                    val checked = source.id in picked
                    ListItem(
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                SourceIcon(source = source, modifier = Modifier.padding(start = 8.dp))
                            }
                        },
                        headlineContent = { Text(text = source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = if (source.lang.isNotEmpty()) {
                            { Text(text = FlagEmoji.getEmojiLangFlag(source.lang) + " " + source.lang.uppercase()) }
                        } else {
                            null
                        },
                        trailingContent = { Pill(text = "$count", style = MaterialTheme.typography.bodySmall) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            picked = if (checked) picked - source.id else picked + source.id
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(MaterialTheme.padding.medium),
    )
}

@Composable
private fun PickerCard(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val shape = remember(first, last) {
        val top = if (first) 12.dp else 0.dp
        val bottom = if (last) 12.dp else 0.dp
        RoundedCornerShape(top, top, bottom, bottom)
    }
    ElevatedCard(shape = shape, modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium)) {
        content()
    }
    if (!last) HorizontalDivider(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium))
}

@Composable
private fun PickerRow(
    source: Source,
    showLanguage: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    ListItem(
        headlineContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceIcon(source = source)
                Text(
                    text = source.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (showLanguage) {
                    Pill(
                        text = FlagEmoji.getEmojiLangFlag(source.lang) + " (${source.lang.uppercase()})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
