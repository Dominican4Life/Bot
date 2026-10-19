package mihon.feature.migration.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState as collectFlowAsState
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
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.MigrateSourcePickerList
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AnimatedFloatingSearchBox
import eu.kanade.presentation.components.SOURCE_SEARCH_BOX_HEIGHT
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.collections.immutable.toImmutableList
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: A window over the screen for the Migrate window's source pickers: back, the title, optional buttons and OK
 * on top, the picker below.
 */
@Composable
internal fun PickerWindow(
    title: String,
    okEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    actions: @Composable () -> Unit = {},
    /** What the back arrow does; closes the window when not set */
    onBack: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = MaterialTheme.padding.small, top = MaterialTheme.padding.small),
                ) {
                    IconButton(onClick = onBack ?: onDismissRequest) {
                        Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    actions()
                    TextButton(onClick = onConfirm, enabled = okEnabled) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                }
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        }
    }
}

/**
 * KMK: Picks the sources to search, like the old "Select sources" page, in a window: tap a source to select it (it
 * moves up to Selected), then drag the selected ones by their handles into the order they're searched in.
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

    val all by produceState<List<Source>?>(null) {
        value = withIOContext {
            val languages = sourcePreferences.enabledLanguages().get()
            Injekt.get<SourceManager>().getVisibleSources()
                .filterIsInstance<HttpSource>()
                .filter { it.lang in languages }
                // KMK: turned-off sources aren't offered to search
                .filterNot { it.id in disabled }
                .map {
                    Source(id = it.id, lang = it.lang, name = it.name, supportsLatest = false, isStub = false)
                }
                .sortedBy { "${it.name} (${it.lang})".lowercase() }
        }
    }
    var selected by remember { mutableStateOf(initial) }
    var searchQuery by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    fun selectWhere(predicate: (Long) -> Boolean) {
        selected = all.orEmpty().map { it.id }.filter(predicate)
    }

    PickerWindow(
        title = stringResource(SYMR.strings.select_sources),
        okEnabled = selected.isNotEmpty(),
        onDismissRequest = onDismissRequest,
        onConfirm = {
            val byId = all.orEmpty().associateBy { it.id }
            onConfirm(selected.mapNotNull { byId[it] })
        },
        actions = {
            IconButton(onClick = { selectWhere { it in pinned } }) {
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = stringResource(MR.strings.migrationConfigScreen_selectPinnedLabel),
                )
            }
            IconButton(onClick = { selected = emptyList() }) {
                Icon(
                    imageVector = Icons.Outlined.Deselect,
                    contentDescription = stringResource(MR.strings.migrationConfigScreen_selectNoneLabel),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.migrationConfigScreen_selectEnabledLabel)) },
                        onClick = {
                            menuOpen = false
                            selectWhere { it !in disabled }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.migrationConfigScreen_selectAllLabel)) },
                        onClick = {
                            menuOpen = false
                            selectWhere { true }
                        },
                    )
                }
            }
        },
    ) {
        val sources = all
        if (sources == null) {
            LoadingScreen()
            return@PickerWindow
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

        val listState = rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
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
        val density = LocalDensity.current
        var searchBoxHeight by remember { mutableStateOf(SOURCE_SEARCH_BOX_HEIGHT) }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = searchBoxHeight, bottom = MaterialTheme.padding.medium),
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
                searchBoxHeight = with(density) { coordinates.size.height.toDp() } + MaterialTheme.padding.small * 2
            },
        )
    }
}

/**
 * KMK: Picks the library titles to migrate, when nothing was selected, like Browse › Migrate used to: the source list
 * (search, sorting, title counts) in a window. All picks every title of a source; tapping a source opens its titles
 * in the same window to pick some. Sources with picked titles are highlighted; OK finishes.
 */
@Composable
fun SourceToMigratePickerDialog(
    initial: Set<Long>,
    onDismissRequest: () -> Unit,
    onConfirm: (picked: Set<Long>, sources: List<Source>) -> Unit,
) {
    val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
    val setMigrateSorting = remember { Injekt.get<SetMigrateSorting>() }
    val sortingMode by sourcePreferences.migrationSortingMode().collectAsState()
    val sortingDirection by sourcePreferences.migrationSortingDirection().collectAsState()
    val flow = remember { Injekt.get<GetSourcesWithFavoriteCount>().subscribe() }
    val withCounts by flow.collectFlowAsState(initial = null)
    // Every library title by source
    val bySource by produceState<Map<Long, List<Manga>>?>(null) {
        value = withIOContext { Injekt.get<GetFavorites>().await().groupBy { it.source } }
    }

    var picked by remember { mutableStateOf(initial) }
    var searchQuery by remember { mutableStateOf<String?>(null) }
    // The source whose titles are open, if any
    var open by remember { mutableStateOf<Source?>(null) }

    val titles = bySource.orEmpty()
    val pickedSources = remember(picked, titles) {
        titles.filterValues { list -> list.any { it.id in picked } }.keys
    }
    fun confirm() {
        val sources = withCounts.orEmpty().map { it.first }.filter { it.id in pickedSources }
        onConfirm(picked, sources)
    }

    val openSource = open
    PickerWindow(
        title = openSource?.name ?: stringResource(KMR.strings.migrate_source_to_migrate),
        okEnabled = picked.isNotEmpty(),
        onDismissRequest = onDismissRequest,
        onConfirm = ::confirm,
        onBack = if (openSource != null) ({ open = null }) else null,
        actions = {
            if (openSource != null) {
                val ids = titles[openSource.id].orEmpty().map { it.id }
                Text(
                    text = "${ids.count { it in picked }}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
                )
                // Select all / invert, like the old titles list
                IconButton(onClick = { picked = picked + ids }) {
                    Icon(
                        imageVector = Icons.Outlined.SelectAll,
                        contentDescription = stringResource(MR.strings.action_select_all),
                    )
                }
                IconButton(onClick = {
                    picked = picked - ids.filter { it in picked }.toSet() + ids.filter { it !in picked }
                }) {
                    Icon(
                        imageVector = Icons.Outlined.FlipToBack,
                        contentDescription = stringResource(MR.strings.action_select_inverse),
                    )
                }
            }
        },
    ) {
        val list = withCounts
        if (list == null || bySource == null) {
            LoadingScreen()
            return@PickerWindow
        }
        if (openSource != null) {
            MigrateTitlesPicker(
                titles = titles[openSource.id].orEmpty().sortedBy { it.title.lowercase() },
                picked = picked,
                onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
            )
            return@PickerWindow
        }
        val shown = remember(list, searchQuery) {
            val query = searchQuery?.trim().orEmpty()
            list.filter { (source, _) ->
                query.isEmpty() || query.split(",").any {
                    val input = it.trim()
                    input.isNotEmpty() && (source.name.contains(input, true) || source.id == input.toLongOrNull())
                }
            }.toImmutableList()
        }
        MigrateSourcePickerList(
            items = shown,
            searchQuery = searchQuery,
            onChangeSearchQuery = { searchQuery = it },
            sortingMode = sortingMode,
            onToggleSortingMode = {
                val mode = when (sortingMode) {
                    SetMigrateSorting.Mode.ALPHABETICAL -> SetMigrateSorting.Mode.TOTAL
                    SetMigrateSorting.Mode.TOTAL -> SetMigrateSorting.Mode.ALPHABETICAL
                }
                setMigrateSorting.await(mode, sortingDirection)
            },
            sortingDirection = sortingDirection,
            onToggleSortingDirection = {
                val direction = when (sortingDirection) {
                    SetMigrateSorting.Direction.ASCENDING -> SetMigrateSorting.Direction.DESCENDING
                    SetMigrateSorting.Direction.DESCENDING -> SetMigrateSorting.Direction.ASCENDING
                }
                setMigrateSorting.await(sortingMode, direction)
            },
            selectedIds = pickedSources,
            onClickItem = { source -> open = source },
            onClickAll = { source ->
                val ids = titles[source.id].orEmpty().map { it.id }.toSet()
                picked = if (ids.all { it in picked }) picked - ids else picked + ids
            },
        )
    }
}

/**
 * KMK: A source's library titles to pick from, in the "Source to migrate" window: cover and title, picked ones
 * highlighted
 */
@Composable
private fun MigrateTitlesPicker(
    titles: List<Manga>,
    picked: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(titles, key = { _, manga -> manga.id }) { _, manga ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (manga.id in picked) {
                            Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onToggle(manga.id) }
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
            ) {
                MangaCover.Square(
                    data = manga,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = MaterialTheme.padding.medium),
                )
            }
        }
    }
}

@Composable
internal fun PickerHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(MaterialTheme.padding.medium),
    )
}

@Composable
internal fun PickerCard(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
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
internal fun PickerRow(
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
