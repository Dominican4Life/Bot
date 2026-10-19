package mihon.feature.migration.anime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.Navigator
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.PauseBetweenTitlesItem
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.domain.migration.models.ANIME_MIGRATION_FLAGS
import mihon.domain.migration.models.MigrationFlag
import mihon.feature.migration.config.DeleteDownloadsSwitch
import mihon.feature.migration.config.PrefSwitch
import mihon.feature.migration.config.SectionHeader
import mihon.feature.migration.config.SourcesBox
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Opens the anime Migrate window from anywhere (the library, an anime's page, Browse › Migrate), like
 * MigrationStart for manga. An empty list means nothing was selected, so the window asks for the source first.
 */
object AnimeMigrationStart {
    private val _request = MutableStateFlow<List<Long>?>(null)
    val request = _request.asStateFlow()

    fun open(animeIds: Collection<Long>) {
        _request.value = animeIds.toList()
    }

    fun close() {
        _request.value = null
    }
}

/** KMK: Shows the anime Migrate window when asked, over any screen; Continue opens the matching screen */
@Composable
fun AnimeMigrationStartHost(navigator: Navigator) {
    val request by AnimeMigrationStart.request.collectFlowAsState()
    val ids = request ?: return
    val preferences = remember { Injekt.get<SourcePreferences>() }
    AnimeMigrationStartSheet(
        initialAnimeIds = ids,
        preferences = preferences,
        onDismissRequest = AnimeMigrationStart::close,
        onStart = { animeIds, extraSearchQuery ->
            AnimeMigrationStart.close()
            navigator.push(AnimeMigrationListScreen(animeIds, extraSearchQuery))
        },
    )
}

/**
 * KMK: The anime Migrate window, laid out like the manga one: what's being migrated, the sources to search (in
 * order), the data to migrate, the pause between titles, and the rest under "More options".
 */
@Composable
fun AnimeMigrationStartSheet(
    initialAnimeIds: List<Long>,
    preferences: SourcePreferences,
    onDismissRequest: () -> Unit,
    onStart: (animeIds: List<Long>, extraSearchQuery: String?) -> Unit,
) {
    val pickFromSource = initialAnimeIds.isEmpty()

    var fromPicked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var fromSources by remember { mutableStateOf<List<Source>>(emptyList()) }
    val animeIds = if (pickFromSource) fromPicked.toList() else initialAnimeIds

    // The sources to search, in order; empty every time the window opens
    var searchSources by remember { mutableStateOf<List<Source>>(emptyList()) }

    var showFromPicker by remember { mutableStateOf(false) }
    var showSearchPicker by remember { mutableStateOf(false) }
    var moreOptions by rememberSaveable { mutableStateOf(false) }
    var extraSearchQuery by rememberSaveable { mutableStateOf("") }

    val migrationFlags by preferences.animeMigrationFlags().collectAsState()

    // Where the selected anime come from, for the subtitle
    val fromNames by produceState("", initialAnimeIds) {
        if (initialAnimeIds.isEmpty()) return@produceState
        value = withIOContext {
            val repository = Injekt.get<AnimeRepository>()
            val sourceManager = Injekt.get<AnimeSourceManager>()
            initialAnimeIds.mapNotNull { repository.getAnime(it)?.source }.distinct()
                .map { sourceManager.get(it)?.name ?: it.toString() }
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
                        sources = fromSources,
                        emptyText = stringResource(KMR.strings.migrate_pick_source_to_migrate),
                        countText = { names, total ->
                            stringResource(KMR.strings.migrate_sources_titles, names, animeIds.size)
                                .takeIf { total > 0 } ?: names
                        },
                        onClick = { showFromPicker = true },
                    )
                } else {
                    Text(
                        text = pluralStringResource(
                            KMR.plurals.migrate_n_entries,
                            initialAnimeIds.size,
                            initialAnimeIds.size,
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
                AnimeMigrationFlagChips(
                    flags = migrationFlags,
                    onToggle = { flag ->
                        preferences.animeMigrationFlags().getAndSet { if (flag in it) it - flag else it + flag }
                    },
                )

                // Pause between titles, always in view (shared with manga)
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
                                preferences.animeMigrationFlags().getAndSet {
                                    if (MigrationFlag.REMOVE_DOWNLOAD in it) {
                                        it - MigrationFlag.REMOVE_DOWNLOAD
                                    } else {
                                        it + MigrationFlag.REMOVE_DOWNLOAD
                                    }
                                }
                            },
                        )
                        AnimeMigrationOptionSwitches(preferences)
                    }
                }
            }

            HorizontalDivider()
            Button(
                onClick = {
                    // The order picked here is the order the sources are searched in
                    preferences.animeMigrationSources().set(searchSources.map { it.id })
                    onStart(animeIds, extraSearchQuery.trim().ifBlank { null })
                },
                enabled = animeIds.isNotEmpty() && searchSources.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
            ) {
                Text(stringResource(MR.strings.migrationConfigScreen_continueButtonText))
            }
        }
    }

    if (showFromPicker) {
        AnimeSourceToMigratePickerDialog(
            initial = fromPicked,
            onDismissRequest = { showFromPicker = false },
            onConfirm = { picked, sources ->
                showFromPicker = false
                fromPicked = picked
                fromSources = sources
            },
        )
    }
    if (showSearchPicker) {
        AnimeSearchSourcesPickerDialog(
            initial = searchSources.map { it.id },
            onDismissRequest = { showSearchPicker = false },
            onConfirm = {
                showSearchPicker = false
                searchSources = it
            },
        )
    }
}

/** KMK: How to match: hide unmatched / without newer episodes, advanced search, match by episode number */
@Composable
private fun AnimeMigrationOptionSwitches(preferences: SourcePreferences) {
    PrefSwitch(
        title = stringResource(MR.strings.migrationConfigScreen_hideUnmatchedTitle),
        subtitle = null,
        preference = preferences.animeMigrationHideUnmatched(),
    )
    PrefSwitch(
        title = stringResource(KMR.strings.anime_migrate_hide_without_updates),
        subtitle = stringResource(KMR.strings.anime_migrate_hide_without_updates_summary),
        preference = preferences.animeMigrationHideWithoutUpdates(),
    )
    PrefSwitch(
        title = stringResource(MR.strings.migrationConfigScreen_deepSearchModeTitle),
        subtitle = stringResource(MR.strings.migrationConfigScreen_deepSearchModeSubtitle),
        preference = preferences.animeMigrationDeepSearchMode(),
    )
    PrefSwitch(
        title = stringResource(KMR.strings.anime_migrate_prioritize_by_episodes),
        subtitle = stringResource(KMR.strings.anime_migrate_prioritize_by_episodes_summary),
        preference = preferences.animeMigrationPrioritizeByEpisodes(),
    )
}

/** KMK: The matching screen's settings: the same switches as "More options" */
@Composable
fun AnimeMigrationOptionsSheet(
    preferences: SourcePreferences,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.padding.medium),
        ) {
            SectionHeader(stringResource(KMR.strings.anime_migrate_options))
            AnimeMigrationOptionSwitches(preferences)
        }
    }
}

/** KMK: "Data to migrate" chips for anime: Episodes, Categories, Tracking, Extra */
@Composable
fun AnimeMigrationFlagChips(flags: Set<MigrationFlag>, onToggle: (MigrationFlag) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
    ) {
        ANIME_MIGRATION_FLAGS
            .filter { it != MigrationFlag.REMOVE_DOWNLOAD }
            .forEach { flag ->
                FilterChip(
                    selected = flag in flags,
                    onClick = { onToggle(flag) },
                    label = { Text(stringResource(flag.animeLabel())) },
                )
            }
    }
}

private fun MigrationFlag.animeLabel(): StringResource = when (this) {
    MigrationFlag.CHAPTER -> KMR.strings.anime_track_episodes
    MigrationFlag.CATEGORY -> MR.strings.categories
    MigrationFlag.TRACK -> MR.strings.track
    MigrationFlag.CUSTOM_COVER -> KMR.strings.migrate_covers
    MigrationFlag.NOTES -> MR.strings.action_notes
    MigrationFlag.REMOVE_DOWNLOAD -> MR.strings.delete_downloaded
    MigrationFlag.EXTRA -> SYMR.strings.log_extra
}
