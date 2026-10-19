package mihon.feature.migration.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.browse.MigrateSourcePickerList
import eu.kanade.presentation.components.AnimatedFloatingSearchBox
import eu.kanade.presentation.components.SOURCE_SEARCH_BOX_HEIGHT
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import mihon.feature.migration.config.PickerCard
import mihon.feature.migration.config.PickerHeader
import mihon.feature.migration.config.PickerRow
import mihon.feature.migration.config.PickerWindow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections

/**
 * KMK: The anime sources with library anime and how many, sorted like Browse › Migrate (the sorting is shared with
 * manga). The anime sources are shown as manga [Source]s, so the manga source rows can show them.
 */
object AnimeMigrateSources {

    fun subscribe(): Flow<List<Pair<Source, Long>>> {
        val preferences = Injekt.get<SourcePreferences>()
        return combine(
            preferences.migrationSortingDirection().changes(),
            preferences.migrationSortingMode().changes(),
            Injekt.get<AnimeRepository>().subscribeFavorites(),
        ) { direction, mode, favorites ->
            favorites.groupBy { it.source }
                .map { (sourceId, list) -> asSource(sourceId) to list.size.toLong() }
                .sortedWith(sortFn(direction, mode))
        }
    }

    /** An anime source as a manga [Source]; a stub when its extension isn't installed */
    fun asSource(sourceId: Long): Source {
        val source = Injekt.get<AnimeSourceManager>().get(sourceId)
        return Source(
            id = sourceId,
            lang = source?.lang.orEmpty(),
            name = source?.name ?: sourceId.toString(),
            supportsLatest = false,
            isStub = source == null,
        )
    }

    private fun sortFn(
        direction: SetMigrateSorting.Direction,
        sorting: SetMigrateSorting.Mode,
    ): Comparator<Pair<Source, Long>> {
        val sortFn: (Pair<Source, Long>, Pair<Source, Long>) -> Int = { a, b ->
            when {
                a.first.isStub && !b.first.isStub -> -1
                b.first.isStub && !a.first.isStub -> 1
                sorting == SetMigrateSorting.Mode.ALPHABETICAL ->
                    a.first.name.lowercase().compareToWithCollator(b.first.name.lowercase())
                else -> a.second.compareTo(b.second)
            }
        }
        return when (direction) {
            SetMigrateSorting.Direction.ASCENDING -> Comparator(sortFn)
            SetMigrateSorting.Direction.DESCENDING -> Collections.reverseOrder(sortFn)
        }
    }

    /** Keeps the sources whose name or id matches one of the comma separated words */
    fun matches(source: Source, query: String?): Boolean {
        val text = query?.trim().orEmpty()
        return text.isEmpty() || text.split(",").any {
            val input = it.trim()
            input.isNotEmpty() && (source.name.contains(input, true) || source.id == input.toLongOrNull())
        }
    }
}

/**
 * KMK: Picks the anime sources to search, like the manga picker: tap a source to select it (it moves up to
 * Selected), then drag the selected ones by their handles into the order they're searched in. Turned-off sources
 * and languages aren't offered.
 */
@Composable
fun AnimeSearchSourcesPickerDialog(
    initial: List<Long>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<Source>) -> Unit,
) {
    val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
    val pinned = remember { sourcePreferences.animePinnedSources().get().mapNotNull { it.toLongOrNull() }.toSet() }
    val disabled = remember {
        sourcePreferences.animeDisabledSources().get().mapNotNull { it.toLongOrNull() }.toSet()
    }

    val all by produceState<List<Source>?>(null) {
        value = withIOContext {
            val disabledLanguages = sourcePreferences.animeDisabledLanguages().get()
            Injekt.get<AnimeSourceManager>().getCatalogueSources()
                .filter { it.lang !in disabledLanguages }
                .filterNot { it.id in disabled }
                .map { Source(id = it.id, lang = it.lang, name = it.name, supportsLatest = false, isStub = false) }
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
        val selectedSources = selected.mapNotNull { byId[it] }.filter { AnimeMigrateSources.matches(it, searchQuery) }
        val availableSources = sources.filter { it.id !in selected && AnimeMigrateSources.matches(it, searchQuery) }

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
            placeholderText = stringResource(KMR.strings.browse_search_anime_sources),
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
 * KMK: Picks the library anime to migrate, like the manga "Source to migrate": the anime source list (search,
 * sorting, counts) in a window. All picks every anime of a source; tapping a source opens its anime to pick some.
 * [openSourceId] opens that source's anime straight away (tapping a source in Browse › Migrate).
 */
@Composable
fun AnimeSourceToMigratePickerDialog(
    initial: Set<Long>,
    onDismissRequest: () -> Unit,
    onConfirm: (picked: Set<Long>, sources: List<Source>) -> Unit,
    openSourceId: Long? = null,
) {
    val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
    val setMigrateSorting = remember { Injekt.get<SetMigrateSorting>() }
    val sortingMode by sourcePreferences.migrationSortingMode().collectAsState()
    val sortingDirection by sourcePreferences.migrationSortingDirection().collectAsState()
    val withCountsFlow = remember { AnimeMigrateSources.subscribe() }
    val withCounts by withCountsFlow.collectFlowAsState(initial = null)
    // Every library anime by source
    val favoritesFlow = remember { Injekt.get<AnimeRepository>().subscribeFavorites() }
    val favorites by favoritesFlow.collectFlowAsState(initial = null)
    val bySource = remember(favorites) { favorites?.groupBy { it.source } }

    var picked by remember { mutableStateOf(initial) }
    var searchQuery by remember { mutableStateOf<String?>(null) }
    // The source whose anime are open, if any
    var open by remember { mutableStateOf(openSourceId?.let(AnimeMigrateSources::asSource)) }

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
            AnimeTitlesPicker(
                titles = titles[openSource.id].orEmpty().sortedBy { it.title.lowercase() },
                picked = picked,
                onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
            )
            return@PickerWindow
        }
        val shown = remember(list, searchQuery) {
            list.filter { (source, _) -> AnimeMigrateSources.matches(source, searchQuery) }.toImmutableList()
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

/** KMK: A source's library anime to pick from: cover and title, picked ones highlighted */
@Composable
private fun AnimeTitlesPicker(
    titles: List<Anime>,
    picked: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(titles, key = { _, anime -> anime.id }) { _, anime ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (anime.id in picked) {
                            Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onToggle(anime.id) }
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
            ) {
                MangaCover.Square(
                    data = anime.asMangaCover(),
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = anime.title,
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
