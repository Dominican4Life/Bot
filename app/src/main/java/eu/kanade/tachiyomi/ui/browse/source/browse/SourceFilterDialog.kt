package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.CollectionFilterField
import eu.kanade.presentation.browse.components.FilledFilterField
import eu.kanade.presentation.browse.components.FilterCard
import eu.kanade.presentation.browse.components.FilterCardPadding
import eu.kanade.presentation.browse.components.FilterPills
import eu.kanade.presentation.browse.components.FilterSelectField
import eu.kanade.presentation.browse.components.FilterSortMenu
import eu.kanade.presentation.browse.components.fitsPills
import eu.kanade.presentation.browse.components.sortArrow
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.jssource.JsDirectory
import eu.kanade.tachiyomi.jssource.JsFilters
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.source.model.EXHSavedSearch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.Scroller.STICKY_HEADER_KEY_PREFIX
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun SourceFilterDialog(
    onDismissRequest: () -> Unit,
    filters: FilterList,
    onReset: () -> Unit,
    onFilter: () -> Unit,
    onUpdate: (FilterList) -> Unit,
    // SY -->
    startExpanded: Boolean,
    savedSearches: ImmutableList<EXHSavedSearch>,
    onSave: () -> Unit,
    onSavedSearch: (EXHSavedSearch) -> Unit,
    onSavedSearchPress: (EXHSavedSearch) -> Unit,
    // KMK -->
    onSavedSearchPressDesc: String,
    shouldShowSavingButton: Boolean = true,
    // KMK <--
    openMangaDexRandom: (() -> Unit)?,
    openMangaDexFollows: (() -> Unit)?,
    // SY <--
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        SourceFilterContent(
            onDismissRequest = onDismissRequest,
            filters = filters,
            onReset = onReset,
            onFilter = onFilter,
            onUpdate = onUpdate,
            startExpanded = startExpanded,
            savedSearches = savedSearches,
            onSave = onSave,
            onSavedSearch = onSavedSearch,
            onSavedSearchPress = onSavedSearchPress,
            onSavedSearchPressDesc = onSavedSearchPressDesc,
            shouldShowSavingButton = shouldShowSavingButton,
            openMangaDexRandom = openMangaDexRandom,
            openMangaDexFollows = openMangaDexFollows,
        )
    }
}

/**
 * KMK: The filter sheet's contents, also shown in the Browse screen's Filter panel. A source without filters
 * shows a line saying so, and keeps Save so the list can still be saved for Feed.
 *
 * KMK (r107): restyled after the "CSheet" mockup: a "Filters" header with Clear and Apply on top; the filters in
 * rounded cards (the ungrouped ones under "Options", each section group under its own name); drop-downs and sorts as
 * filled boxes (two to a row when there's room), a select with a few short options as pills, and a JS source's
 * collections as drop-downs with their own sort button ([CollectionFilterField]).
 */
@Composable
fun SourceFilterContent(
    onDismissRequest: () -> Unit,
    filters: FilterList,
    onReset: () -> Unit,
    onFilter: () -> Unit,
    onUpdate: (FilterList) -> Unit,
    startExpanded: Boolean,
    savedSearches: ImmutableList<EXHSavedSearch>,
    onSave: () -> Unit,
    onSavedSearch: (EXHSavedSearch) -> Unit,
    onSavedSearchPress: (EXHSavedSearch) -> Unit,
    onSavedSearchPressDesc: String,
    shouldShowSavingButton: Boolean,
    openMangaDexRandom: (() -> Unit)?,
    openMangaDexFollows: (() -> Unit)?,
    // KMK -->
    /** Something is selected: Clear shows in the accent colour */
    clearHighlighted: Boolean = false,
    // KMK <--
) {
    val updateFilters = { onUpdate(filters) }
    // KMK (r107): the cards, in the order their first filter comes
    val sections = sectionsOf(filters)

    // KMK (r135): in the phone side sheet the sheet has its own title / ✕, and Save, Clear and Apply sit in a bar at
    // the bottom (M4)
    val phoneSheet = eu.kanade.presentation.browse.components.LocalPhoneSheet.current
    // KMK (r137, 4A): the tablet's glass panel also puts Save / Clear / Apply at the bottom; its header says how many
    // filters are set
    val glassPanel = eu.kanade.presentation.components.LocalGlassPanel.current
    val bottomBar = phoneSheet || glassPanel
    val doClear = {
        resetDirectories(filters)
        onReset()
    }
    val doApply = {
        onFilter()
        onDismissRequest()
    }
    val listContent: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            if (glassPanel && !phoneSheet) item(key = "$STICKY_HEADER_KEY_PREFIX-glass-title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Filters",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    FilterSetCount(countSet(filters))
                }
            }
            if (!bottomBar) stickyHeader(
                key = "$STICKY_HEADER_KEY_PREFIX-title",
            ) {
                // KMK (r107): "Filters", then Clear (text), Save (when allowed) and Apply (filled)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // KMK (r133): see-through in the glass panel
                        .background(
                            if (eu.kanade.presentation.components.LocalGlassPanel.current) {
                                Color.Black.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.background
                            },
                        )
                        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Filters",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            // KMK (r107) fix: Clear also resets each collection's sort, direction and menus
                            resetDirectories(filters)
                            onReset()
                        },
                    ) {
                        Text(
                            text = stringResource(KMR.strings.action_clear_filters),
                            color = if (clearHighlighted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    // KMK -->
                    if (shouldShowSavingButton) {
                        // KMK <--
                        // SY -->
                        Surface(
                            onClick = onSave,
                            modifier = Modifier.size(36.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = Color.White.copy(alpha = 0.09f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            border = null,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = stringResource(MR.strings.action_save),
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        }
                        // SY <--
                    }
                    // KMK (r133): Apply as the accent glass button
                    eu.kanade.presentation.components.GlassButton(
                        text = stringResource(MR.strings.action_apply),
                        primary = true,
                        onClick = {
                            onFilter()
                            onDismissRequest()
                        },
                    )
                }
            }

            if (openMangaDexRandom != null && openMangaDexFollows != null) {
                item {
                    MangaDexFilterHeader(
                        openMangaDexRandom = openMangaDexRandom,
                        openMangaDexFollows = openMangaDexFollows,
                    )
                }
            }

            item {
                SavedSearchItem(
                    savedSearches = savedSearches,
                    onSavedSearch = onSavedSearch,
                    onSavedSearchPress = onSavedSearchPress,
                    // KMK -->
                    onSavedSearchPressDesc = onSavedSearchPressDesc,
                    // KMK <--
                )
            }

            // KMK (r107): one card per section
            items(sections) { section ->
                FilterSection(section, updateFilters, startExpanded)
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
            // KMK -->
            // (r107: also when only hidden holders are left)
            if (sections.isEmpty()) {
                item {
                    Text(
                        text = stringResource(KMR.strings.browse_no_filters),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            // KMK <--
    }
    if (bottomBar) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f), content = listContent)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (shouldShowSavingButton) {
                    eu.kanade.presentation.components.GlassButton(
                        text = stringResource(MR.strings.action_save),
                        primary = false,
                        onClick = onSave,
                        icon = Icons.Default.Save,
                        modifier = Modifier.weight(1f),
                    )
                }
                eu.kanade.presentation.components.GlassButton(
                    text = stringResource(KMR.strings.action_clear_filters),
                    primary = false,
                    onClick = doClear,
                    modifier = Modifier.weight(1f),
                )
                eu.kanade.presentation.components.GlassButton(
                    text = stringResource(MR.strings.action_apply),
                    primary = true,
                    onClick = doApply,
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
    } else {
        LazyColumn(content = listContent)
    }
}

// KMK (r107) -->
/** A card of the sheet: its title and the filters in it */
private class FilterSectionData(val title: String, val filters: List<Filter<*>>)

/**
 * The sheet's cards: a section group (a JS source's "group", or any group holding more than check boxes) is a card
 * under its own name; every other filter goes in the "Options" card, placed where the first of them comes. The blank
 * header (a JS source's hidden tab holder) is left out
 */
private fun sectionsOf(filters: FilterList): List<FilterSectionData> {
    val out = mutableListOf<FilterSectionData?>()
    val options = mutableListOf<Filter<*>>()
    var optionsAt = -1
    filters.forEach { filter ->
        if (filter is Filter.Header && filter.name.isBlank()) return@forEach
        if (filter is Filter.Group<*> && isSection(filter)) {
            val children = filter.state.filterIsInstance<Filter<*>>()
            if (children.isNotEmpty()) out.add(FilterSectionData(filter.name, children))
            return@forEach
        }
        if (optionsAt < 0) {
            optionsAt = out.size
            out.add(null)
        }
        options.add(filter)
    }
    return out.mapNotNull { it ?: FilterSectionData("Options", options) }
}

private fun isSection(group: Filter.Group<*>): Boolean =
    group is JsFilters.SectionGroup ||
        group.state.any { it is Filter<*> && it !is Filter.CheckBox && it !is Filter.TriState }

/** A filter shown as a filled box (it can share a row with another) */
private fun isField(filter: Filter<*>): Boolean = when (filter) {
    is Filter.Select<*> -> !fitsPills(filter.values.map { it.toString() })
    is Filter.Sort -> true
    else -> false
}

/** The filters of a card in rows: two boxes side by side when [pair], else one filter per row */
private fun rowsOf(filters: List<Filter<*>>, pair: Boolean): List<List<Filter<*>>> {
    val rows = mutableListOf<List<Filter<*>>>()
    var i = 0
    while (i < filters.size) {
        val filter = filters[i]
        val next = filters.getOrNull(i + 1)
        if (pair && isField(filter) && next != null && isField(next)) {
            rows.add(listOf(filter, next))
            i += 2
        } else {
            rows.add(listOf(filter))
            i++
        }
    }
    return rows
}

@Composable
private fun FilterSection(section: FilterSectionData, onUpdate: () -> Unit, startExpanded: Boolean) {
    FilterCard(
        title = section.title,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val pair = maxWidth >= 320.dp
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rowsOf(section.filters, pair).forEach { row ->
                    val first = row[0]
                    val second = row.getOrNull(1)
                    if (second != null) {
                        Row(
                            modifier = Modifier.padding(horizontal = FilterCardPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FieldFilter(first, onUpdate, Modifier.weight(1f))
                            FieldFilter(second, onUpdate, Modifier.weight(1f))
                        }
                    } else {
                        FilterItem(first, onUpdate, startExpanded)
                    }
                }
            }
        }
    }
}

/** A select or sort as a filled box */
@Composable
private fun FieldFilter(filter: Filter<*>, onUpdate: () -> Unit, modifier: Modifier) {
    when (filter) {
        is Filter.Select<*> -> FilterSelectField(
            label = filter.name,
            options = filter.values.map { it.toString() },
            selectedIndex = filter.state,
            onSelect = {
                filter.state = it
                onUpdate()
            },
            modifier = modifier,
        )
        is Filter.Sort -> SortFilterField(filter, onUpdate, modifier)
        else -> Unit
    }
}

/**
 * A sort filter as a filled box showing the picked option with its arrow; its menu has the arrow on the left of the
 * picked option. Tapping another option picks it, tapping the picked one flips it (as the sort rows did)
 */
@Composable
private fun SortFilterField(filter: Filter.Sort, onUpdate: () -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val selection = filter.state
    Box(modifier = modifier) {
        FilledFilterField(
            label = filter.name,
            value = selection?.let { filter.values.getOrNull(it.index) } ?: "Any",
            open = open,
            set = selection != null && selection.index > 0,
            dimValue = selection == null,
            leading = selection?.let { sortArrow(it.ascending) },
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
        )
        FilterSortMenu(
            expanded = open,
            onDismiss = { open = false },
            options = filter.values.toList(),
            selectedIndex = selection?.index ?: -1,
            ascending = selection?.ascending ?: false,
            onPick = { index ->
                val current = filter.state
                val ascending = if (current != null && index == current.index) {
                    !current.ascending
                } else {
                    current?.ascending ?: true
                }
                filter.state = Filter.Sort.Selection(index = index, ascending = ascending)
                open = false
                onUpdate()
            },
        )
    }
}
// KMK <--

/** KMK (r107) fix: each JS collection's sort, direction and menus back to the site's / stored order (Clear) */
private fun resetDirectories(filters: List<Filter<*>>) {
    filters.forEach { f ->
        (f as? JsDirectory.Holder)?.directory?.resetOrder()
        if (f is Filter.Group<*>) resetDirectories(f.state.filterIsInstance<Filter<*>>())
    }
}

@Composable
private fun FilterItem(filter: Filter<*>, onUpdate: () -> Unit/* SY --> */, startExpanded: Boolean /* SY <-- */) {
    // KMK (r107): a JS source's collection (a text filter holding the picked entry's value)
    val collection = filter as? JsDirectory.Holder
    if (collection != null && filter is Filter.Text) {
        CollectionFilterField(
            directory = collection.directory,
            value = filter.state,
            onPick = {
                filter.state = it
                onUpdate()
            },
            modifier = Modifier.padding(horizontal = FilterCardPadding),
        )
        return
    }
    when (filter) {
        // SY -->
        is Filter.AutoComplete -> {
            AutoCompleteItem(
                name = filter.name,
                state = filter.state.toImmutableList(),
                hint = filter.hint,
                // KMK r103: one kept copy of the tag list (it was copied on every redraw: up to 100,000 tags)
                values = rememberTagValues(filter.values),
                skipAutoFillTags = filter.skipAutoFillTags.toImmutableList(),
                validPrefixes = filter.validPrefixes.toImmutableList(),
            ) {
                filter.state = it
                onUpdate()
            }
        }
        // SY <--
        is Filter.Header -> {
            // KMK: a blank header is a hidden holder (a JS source's selected tab), not a title
            if (filter.name.isNotBlank()) {
                if (eu.kanade.presentation.components.LocalGlassPanel.current) {
                    // KMK (r137, 4A): notes read as small grey text in the glass panel
                    Text(
                        text = filter.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                } else {
                    HeadingItem(filter.name)
                }
            }
        }
        is Filter.Separator -> {
            // KMK (r137, 4A): no lines on glass
            if (!eu.kanade.presentation.components.LocalGlassPanel.current) HorizontalDivider()
        }
        is Filter.CheckBox -> {
            CheckboxItem(
                label = filter.name,
                checked = filter.state,
            ) {
                filter.state = !filter.state
                onUpdate()
            }
        }
        is Filter.TriState -> {
            TriStateItem(
                label = filter.name,
                state = filter.state.toTriStateFilter(),
            ) {
                filter.state = filter.state.toTriStateFilter().next().toTriStateInt()
                onUpdate()
            }
        }
        is Filter.Text -> {
            TextItem(
                label = filter.name,
                value = filter.state,
            ) {
                filter.state = it
                onUpdate()
            }
        }
        is Filter.Select<*> -> {
            // KMK (r107): a few short options as pills, else a filled drop-down box
            val options = filter.values.map { it.toString() }
            if (fitsPills(options)) {
                FilterPills(
                    label = filter.name,
                    options = options,
                    selectedIndex = filter.state,
                    onSelect = {
                        filter.state = it
                        onUpdate()
                    },
                    modifier = Modifier.padding(horizontal = FilterCardPadding),
                )
            } else {
                FieldFilter(filter, onUpdate, Modifier.fillMaxWidth().padding(horizontal = FilterCardPadding))
            }
        }
        is Filter.Sort -> {
            // KMK (r107): a filled box with the sort menu (it was a collapsible list of sort rows)
            SortFilterField(filter, onUpdate, Modifier.fillMaxWidth().padding(horizontal = FilterCardPadding))
        }
        is Filter.Group<*> -> {
            val glass = eu.kanade.presentation.components.LocalGlassPanel.current
            CollapsibleBox(
                heading = filter.name,
                // SY -->
                startExpanded = startExpanded,
                // SY <--
                // KMK (r137, 4A): a glass row showing what is picked
                glassRow = glass,
                summary = if (glass) groupSummary(filter) else null,
            ) {
                Column {
                    filter.state
                        .filterIsInstance<Filter<*>>()
                        .map { FilterItem(filter = it, onUpdate = onUpdate /* SY --> */, startExpanded /* SY <-- */) }
                }
            }
        }
    }
}

// KMK (r137, 4A) -->
/** What is picked in a group of check boxes / tri-states, e.g. "Action, Fantasy, −Horror"; null when nothing is */
private fun groupSummary(group: Filter.Group<*>): String? {
    val picked = group.state.filterIsInstance<Filter<*>>().mapNotNull { f ->
        when (f) {
            is Filter.CheckBox -> f.name.takeIf { f.state }
            is Filter.TriState -> when (f.state) {
                Filter.TriState.STATE_INCLUDE -> f.name
                Filter.TriState.STATE_EXCLUDE -> "\u2212" + f.name
                else -> null
            }
            else -> null
        }
    }
    return picked.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** How many filters differ from their starting value (check boxes, tri-states, texts, drop-downs) */
private fun countSet(filters: List<Filter<*>>): Int = filters.sumOf { f ->
    when (f) {
        is Filter.CheckBox -> if (f.state) 1 else 0
        is Filter.TriState -> if (f.state != Filter.TriState.STATE_IGNORE) 1 else 0
        is Filter.Text -> if (f.state.isNotBlank()) 1 else 0
        is Filter.AutoComplete -> f.state.size
        is Filter.Select<*> -> if (f.state != 0) 1 else 0
        is Filter.Group<*> -> countSet(f.state.filterIsInstance<Filter<*>>())
        else -> 0
    }.toInt()
}

/** The header's "N set" pill (hidden when nothing is set) */
@Composable
private fun FilterSetCount(count: Int) {
    if (count <= 0) return
    val accent = tachiyomi.presentation.core.components.LocalGlassAccent.current
    Text(
        text = "$count set",
        style = MaterialTheme.typography.labelMedium,
        color = accent,
        modifier = Modifier
            .background(accent.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
// KMK (r137, 4A) <--

private fun Int.toTriStateFilter(): TriState {
    return when (this) {
        Filter.TriState.STATE_IGNORE -> TriState.DISABLED
        Filter.TriState.STATE_INCLUDE -> TriState.ENABLED_IS
        Filter.TriState.STATE_EXCLUDE -> TriState.ENABLED_NOT
        else -> throw IllegalStateException("Unknown TriState state: $this")
    }
}

private fun TriState.toTriStateInt(): Int {
    return when (this) {
        TriState.DISABLED -> Filter.TriState.STATE_IGNORE
        TriState.ENABLED_IS -> Filter.TriState.STATE_INCLUDE
        TriState.ENABLED_NOT -> Filter.TriState.STATE_EXCLUDE
    }
}
