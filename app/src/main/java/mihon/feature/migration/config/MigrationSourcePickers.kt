package mihon.feature.migration.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.domain.source.interactor.GetSourcesWithFavoriteCount
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.first
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: One source in a picker: the source, and the line under its name */
private class PickerSource(val source: Source, val detail: String)

private enum class PickerFilter { ALL, SELECTED, UNSELECTED, PINNED }

/**
 * KMK: Picks the sources to search. Nothing is ticked at first; sources are searched in the order they're ticked,
 * shown by the number on each one.
 */
@Composable
fun SearchSourcesPickerDialog(
    initial: List<Long>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<Source>) -> Unit,
) {
    val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
    val pinned = remember { sourcePreferences.pinnedSources().get().mapNotNull { it.toLongOrNull() }.toSet() }
    val sources by produceState<List<PickerSource>?>(null) {
        value = withIOContext {
            val languages = sourcePreferences.enabledLanguages().get()
            Injekt.get<SourceManager>().getVisibleSources()
                .filterIsInstance<HttpSource>()
                .filter { it.lang in languages }
                .map {
                    val source = Source(
                        id = it.id,
                        lang = it.lang,
                        name = it.name,
                        supportsLatest = false,
                        isStub = false,
                    )
                    PickerSource(source, LocaleHelper.getDisplayName(it.lang))
                }
                .sortedBy { "${it.source.name} (${it.source.lang})".lowercase() }
        }
    }
    SourcePickerWindow(
        title = stringResource(SYMR.strings.select_sources),
        hint = stringResource(KMR.strings.migrate_order_hint),
        sources = sources,
        initial = initial,
        pinned = pinned,
        numbered = true,
        onDismissRequest = onDismissRequest,
        onConfirm = { ids ->
            val byId = sources.orEmpty().associateBy { it.source.id }
            onConfirm(ids.mapNotNull { byId[it]?.source })
        },
    )
}

/**
 * KMK: Picks the sources whose library titles are migrated, when nothing was selected: each source with how many
 * library titles it has.
 */
@Composable
fun SourceToMigratePickerDialog(
    initial: Set<Long>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<Pair<Source, Long>>) -> Unit,
) {
    val pinned = remember {
        Injekt.get<SourcePreferences>().pinnedSources().get().mapNotNull { it.toLongOrNull() }.toSet()
    }
    val withCounts by produceState<List<Pair<Source, Long>>?>(null) {
        value = Injekt.get<GetSourcesWithFavoriteCount>().subscribe().first()
    }
    val sources = withCounts?.map { (source, count) ->
        val language = LocaleHelper.getDisplayName(source.lang)
        PickerSource(source, language + " · " + stringResource(KMR.strings.migrate_n_titles, count.toInt()))
    }
    SourcePickerWindow(
        title = stringResource(KMR.strings.migrate_source_to_migrate),
        hint = null,
        sources = sources,
        initial = initial.toList(),
        pinned = pinned,
        numbered = false,
        onDismissRequest = onDismissRequest,
        onConfirm = { ids -> onConfirm(withCounts.orEmpty().filter { it.first.id in ids }) },
    )
}

/**
 * KMK: A source picker in a window over the screen (like Kototoro's), in the app's look: title with how many are
 * picked and OK, search, All / Selected / Unselected / Pinned, Select shown / Clear shown, and the list.
 */
@Composable
private fun SourcePickerWindow(
    title: String,
    hint: String?,
    sources: List<PickerSource>?,
    initial: List<Long>,
    pinned: Set<Long>,
    numbered: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PickerFilter.ALL) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f),
        ) {
            Column {
                // Title row: back, title with how many are picked, OK
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = MaterialTheme.padding.small, top = MaterialTheme.padding.small),
                ) {
                    IconButton(onClick = onDismissRequest) {
                        Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, style = MaterialTheme.typography.titleLarge)
                        if (sources != null) {
                            Text(
                                text = stringResource(KMR.strings.migrate_n_of_m_selected, selected.size, sources.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    TextButton(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty()) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                }
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                }
                if (sources == null) {
                    LoadingScreen()
                    return@Column
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(KMR.strings.action_search_for_source)) },
                    leadingIcon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = MaterialTheme.padding.medium),
                ) {
                    listOf(
                        PickerFilter.ALL to KMR.strings.migrate_picker_all,
                        PickerFilter.SELECTED to KMR.strings.migrate_picker_selected,
                        PickerFilter.UNSELECTED to KMR.strings.migrate_picker_unselected,
                        PickerFilter.PINNED to KMR.strings.migrate_picker_pinned,
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = filter == value,
                            onClick = { filter = value },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }

                val shown = sources.filter { item ->
                    val id = item.source.id
                    val passesFilter = when (filter) {
                        PickerFilter.ALL -> true
                        PickerFilter.SELECTED -> id in selected
                        PickerFilter.UNSELECTED -> id !in selected
                        PickerFilter.PINNED -> id in pinned
                    }
                    passesFilter && (query.isBlank() || item.source.name.contains(query.trim(), ignoreCase = true))
                }.let { list ->
                    // The picked ones in the order they're searched in, when only those are shown
                    if (filter == PickerFilter.SELECTED) list.sortedBy { selected.indexOf(it.source.id) } else list
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                ) {
                    TextButton(onClick = {
                        selected = selected + shown.map { it.source.id }.filter { it !in selected }
                    }) {
                        Text(stringResource(KMR.strings.migrate_select_shown))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val ids = shown.map { it.source.id }.toSet()
                        selected = selected.filter { it !in ids }
                    }) {
                        Text(stringResource(KMR.strings.migrate_clear_shown))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium))
                Text(
                    text = stringResource(KMR.strings.migrate_showing_sources, shown.size, sources.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(
                        start = MaterialTheme.padding.medium,
                        end = MaterialTheme.padding.medium,
                        bottom = MaterialTheme.padding.medium,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.source.id }) { item ->
                        val id = item.source.id
                        val order = selected.indexOf(id)
                        PickerRow(
                            item = item,
                            picked = order >= 0,
                            number = if (numbered && order >= 0) order + 1 else null,
                            onClick = { selected = if (order >= 0) selected - id else selected + id },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    item: PickerSource,
    picked: Boolean,
    number: Int?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (picked) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = 10.dp),
    ) {
        SourceIcon(source = item.source)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MaterialTheme.padding.medium),
        ) {
            Text(
                text = item.source.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Picked: its place in the search order (or a tick); not picked: an empty box
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .then(
                    if (picked) {
                        Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                    },
                ),
        ) {
            when {
                number != null -> Text(
                    text = "$number",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                picked -> Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
