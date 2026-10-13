package mihon.feature.migration.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.core.util.fastFilterNot
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.PauseBetweenTitlesItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.domain.migration.models.MigrationFlag
import mihon.feature.common.utils.getLabel
import mihon.feature.migration.list.MigrationListScreen
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Opens the Migrate window from anywhere (library, a title's page, migrate by source). An empty list means
 * nothing was selected, so the window asks for the source to migrate first.
 */
object MigrationStart {
    private val _request = MutableStateFlow<List<Long>?>(null)
    val request = _request.asStateFlow()

    fun open(mangaIds: Collection<Long>) {
        _request.value = mangaIds.toList()
    }

    fun close() {
        _request.value = null
    }
}

/** Shows the Migrate window when asked, over any screen; Start matching opens the matching screen */
@Composable
fun MigrationStartHost(navigator: Navigator) {
    val request by MigrationStart.request.collectFlowAsState()
    val ids = request ?: return
    val preferences = remember { Injekt.get<SourcePreferences>() }
    MigrationStartSheet(
        initialMangaIds = ids,
        preferences = preferences,
        onDismissRequest = MigrationStart::close,
        onStart = { mangaIds, extraSearchQuery ->
            MigrationStart.close()
            navigator.push(
                MigrationListScreen(
                    mangaIds,
                    extraSearchQuery,
                    preferences.migrationSmartSearchSingleEntry().get(),
                ),
            )
        },
    )
}

/**
 * KMK: The Migrate window, laid out like Kototoro's in the app's own look: what's being migrated, the sources to
 * search (in order), the data to migrate, how to match, and the rest under "More options".
 */
@Composable
fun MigrationStartSheet(
    initialMangaIds: List<Long>,
    preferences: SourcePreferences,
    onDismissRequest: () -> Unit,
    onStart: (mangaIds: List<Long>, extraSearchQuery: String?) -> Unit,
) {
    val pickFromSource = initialMangaIds.isEmpty()

    // The sources whose titles are migrated, when nothing was selected
    var fromSources by remember { mutableStateOf<List<Pair<Source, Long>>>(emptyList()) }
    var fromSourceIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(fromSources) {
        if (!pickFromSource) return@LaunchedEffect
        val ids = fromSources.map { it.first.id }.toSet()
        fromSourceIds = withIOContext {
            Injekt.get<GetFavorites>().await().filter { it.source in ids }.map { it.id }
        }
    }
    val mangaIds = if (pickFromSource) fromSourceIds else initialMangaIds

    // The sources to search, in order; empty every time the window opens
    var searchSources by remember { mutableStateOf<List<Source>>(emptyList()) }

    var showFromPicker by remember { mutableStateOf(false) }
    var showSearchPicker by remember { mutableStateOf(false) }
    var moreOptions by rememberSaveable { mutableStateOf(false) }
    var extraSearchQuery by rememberSaveable { mutableStateOf("") }

    val migrationFlags by preferences.migrationFlags().collectAsState()

    // Where the selected titles come from, for the subtitle
    val fromNames by produceState("", initialMangaIds) {
        if (initialMangaIds.isEmpty()) return@produceState
        value = withIOContext {
            val getManga = Injekt.get<GetManga>()
            val sourceManager = Injekt.get<SourceManager>()
            initialMangaIds.mapNotNull { getManga.await(it)?.source }.distinct()
                .map { sourceManager.getOrStub(it).name }
                .let { names -> if (names.size <= 2) names.joinToString() else names.take(2).joinToString() + "…" }
        }
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = MaterialTheme.padding.medium),
            ) {
                // What's being migrated
                if (pickFromSource) {
                    SectionHeader(stringResource(KMR.strings.migrate_source_to_migrate))
                    SourcesBox(
                        sources = fromSources.map { it.first },
                        emptyText = stringResource(KMR.strings.migrate_pick_source_to_migrate),
                        countText = { names, total ->
                            stringResource(KMR.strings.migrate_sources_titles, names, mangaIds.size)
                                .takeIf { total > 0 } ?: names
                        },
                        onClick = { showFromPicker = true },
                    )
                } else {
                    Text(
                        text = pluralStringResource(
                            KMR.plurals.migrate_n_entries,
                            initialMangaIds.size,
                            initialMangaIds.size,
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                    if (fromNames.isNotEmpty()) {
                        Text(
                            text = fromNames,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                        )
                    }
                }

                // The sources to search
                Spacer(Modifier.height(MaterialTheme.padding.small))
                SectionHeader(stringResource(KMR.strings.migrate_search_these_sources))
                SourcesBox(
                    sources = searchSources,
                    emptyText = stringResource(KMR.strings.migrate_pick_search_sources),
                    countText = { names, total -> stringResource(KMR.strings.migrate_sources_in_total, names, total) },
                    onClick = { showSearchPicker = true },
                )

                // Data to migrate: one row, a picked chip just turns blue
                Spacer(Modifier.height(MaterialTheme.padding.small))
                SectionHeader(stringResource(MR.strings.migrationConfigScreen_dataToMigrateHeader))
                MigrationFlagChips(
                    flags = migrationFlags,
                    onToggle = { flag ->
                        preferences.migrationFlags().getAndSet { if (flag in it) it - flag else it + flag }
                    },
                )

                // Pause between titles, always in view
                Spacer(Modifier.height(MaterialTheme.padding.small))
                PauseBetweenTitlesItem(
                    enabledPreference = preferences.migrationDelayEnabled(),
                    rangePreference = preferences.migrationDelay(),
                )

                // More options, closed until opened
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { moreOptions = !moreOptions }
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.medium),
                ) {
                    Text(
                        text = stringResource(KMR.strings.migrate_more_options),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (moreOptions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                    )
                }
                AnimatedVisibility(visible = moreOptions) {
                    Column {
                        OutlinedTextField(
                            value = extraSearchQuery,
                            onValueChange = { extraSearchQuery = it },
                            label = {
                                Text(stringResource(MR.strings.migrationConfigScreen_additionalSearchQueryLabel))
                            },
                            supportingText = {
                                val help = MR.strings.migrationConfigScreen_additionalSearchQuerySupportingText
                                Text(stringResource(help))
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = MaterialTheme.padding.medium,
                                    vertical = MaterialTheme.padding.small,
                                ),
                        )
                        DeleteDownloadsSwitch(
                            checked = MigrationFlag.REMOVE_DOWNLOAD in migrationFlags,
                            onClick = {
                                preferences.migrationFlags().getAndSet { it.toggled(MigrationFlag.REMOVE_DOWNLOAD) }
                            },
                        )
                        PrefSwitch(
                            title = stringResource(MR.strings.migrationConfigScreen_hideUnmatchedTitle),
                            subtitle = null,
                            preference = preferences.migrationHideUnmatched(),
                        )
                        PrefSwitch(
                            title = stringResource(MR.strings.migrationConfigScreen_hideWithoutUpdatesTitle),
                            subtitle = stringResource(MR.strings.migrationConfigScreen_hideWithoutUpdatesSubtitle),
                            preference = preferences.migrationHideWithoutUpdates(),
                        )
                        if (mangaIds.size == 1) {
                            PrefSwitch(
                                title = stringResource(KMR.strings.migrationConfigScreen_smartSearchSingleEntryTitle),
                                subtitle = stringResource(
                                    KMR.strings.migrationConfigScreen_smartSearchSingleEntrySubtitle,
                                ),
                                preference = preferences.migrationSmartSearchSingleEntry(),
                            )
                        }
                        PrefSwitch(
                            title = stringResource(MR.strings.migrationConfigScreen_deepSearchModeTitle),
                            subtitle = stringResource(MR.strings.migrationConfigScreen_deepSearchModeSubtitle),
                            preference = preferences.migrationDeepSearchMode(),
                        )
                        PrefSwitch(
                            title = stringResource(MR.strings.migrationConfigScreen_prioritizeByChaptersTitle),
                            subtitle = stringResource(MR.strings.migrationConfigScreen_prioritizeByChaptersSubtitle),
                            preference = preferences.migrationPrioritizeByChapters(),
                        )
                    }
                }
            }

            HorizontalDivider()
            Button(
                onClick = {
                    // The order picked here is the order the sources are searched in
                    preferences.migrationSources().set(searchSources.map { it.id })
                    onStart(mangaIds, extraSearchQuery.trim().ifBlank { null })
                },
                enabled = mangaIds.isNotEmpty() && searchSources.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
            ) {
                Text(stringResource(MR.strings.migrationConfigScreen_continueButtonText))
            }
        }
    }

    if (showFromPicker) {
        SourceToMigratePickerDialog(
            initial = fromSources.map { it.first.id }.toSet(),
            onDismissRequest = { showFromPicker = false },
            onConfirm = {
                showFromPicker = false
                fromSources = it
            },
        )
    }
    if (showSearchPicker) {
        SearchSourcesPickerDialog(
            initial = searchSources.map { it.id },
            onDismissRequest = { showSearchPicker = false },
            onConfirm = {
                showSearchPicker = false
                searchSources = it
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.header,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    )
}

/** A box showing the picked sources (icons, the first names, "· N in total"), or what to pick when empty */
@Composable
private fun SourcesBox(
    sources: List<Source>,
    emptyText: String,
    countText: @Composable (names: String, total: Int) -> String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = 12.dp),
    ) {
        if (sources.isEmpty()) {
            Text(
                text = emptyText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        } else {
            // Up to 4 icons, overlapping a little
            Box(modifier = Modifier.width((28 + (sources.take(4).size - 1) * 18).dp)) {
                sources.take(4).forEachIndexed { index, source ->
                    Box(
                        modifier = Modifier
                            .offset(x = (index * 18).dp)
                            .size(28.dp)
                            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.surfaceContainerHigh), CircleShape)
                            .padding(2.dp),
                    ) {
                        SourceIcon(source = source, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.width(MaterialTheme.padding.small))
            val names = sources.take(3).joinToString { it.name }
            Text(
                text = countText(names, sources.size),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
    }
}

@Composable
private fun PrefSwitch(title: String, subtitle: String?, preference: Preference<Boolean>) {
    SheetSwitch(
        title = title,
        subtitle = subtitle,
        checked = preference.collectAsState().value,
        onClick = { preference.toggle() },
    )
}

@Composable
private fun SheetSwitch(title: String, subtitle: String?, checked: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text = title) },
        supportingContent = subtitle?.let { { Text(text = it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** KMK: "Data to migrate" chips on one row (it scrolls sideways when narrow); a picked chip just turns blue */
@Composable
fun MigrationFlagChips(flags: Set<MigrationFlag>, onToggle: (MigrationFlag) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.medium),
    ) {
        MigrationFlag.entries
            .fastFilterNot { it == MigrationFlag.REMOVE_DOWNLOAD }
            .fastForEach { flag ->
                FilterChip(
                    selected = flag in flags,
                    onClick = { onToggle(flag) },
                    label = { Text(stringResource(flag.getLabel())) },
                )
            }
    }
}

/** KMK: "Delete downloads", for the titles being migrated */
@Composable
fun DeleteDownloadsSwitch(checked: Boolean, onClick: () -> Unit) {
    SheetSwitch(
        title = stringResource(KMR.strings.migrate_delete_downloads),
        subtitle = stringResource(KMR.strings.migrate_delete_downloads_summary),
        checked = checked,
        onClick = onClick,
    )
}

private fun Set<MigrationFlag>.toggled(flag: MigrationFlag) = if (flag in this) this - flag else this + flag
