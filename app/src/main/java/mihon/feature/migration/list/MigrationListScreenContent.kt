package mihon.feature.migration.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.animateItemFastScroll
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.combine
import mihon.feature.migration.list.models.MigratingManga
import mihon.feature.migration.list.models.MigratingManga.Candidate
import mihon.feature.migration.list.models.MigratingManga.SearchResult
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** KMK: Which titles the matching screen shows */
private enum class MatchFilter { ALL, MATCHED, NOT_FOUND, FEWER }

/**
 * KMK: The matching screen, laid out like Kototoro's in the app's own look: "Matching 3 / 7" with a progress line,
 * filter chips, one row per title (old source → new source), and Copy / Migrate at the bottom.
 */
@Composable
fun MigrationListScreenContent(
    items: ImmutableList<MigratingManga>,
    migrationComplete: Boolean,
    finishedCount: Int,
    matchingId: Long?,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onItemClick: (Manga) -> Unit,
    onSearchManually: (MigratingManga) -> Unit,
    onSkip: (Long) -> Unit,
    onMigrate: (Long) -> Unit,
    onCopy: (Long) -> Unit,
    openMigrationDialog: (Boolean) -> Unit,
    onCancel: (Long) -> Unit,
    openOptionsDialog: () -> Unit,
    onPickCandidate: (Long, Candidate) -> Unit,
    searchCandidates: suspend (String) -> List<Candidate>,
) {
    // The title whose Candidates window is open
    var candidatesFor by remember { mutableStateOf<MigratingManga?>(null) }

    // Every title's result, kept up to date as the search goes
    val resultsFlow = remember(items) { combine(items.map { it.searchResult }) { it.toList() } }
    val results by resultsFlow.collectAsState(initial = items.map { it.searchResult.value })
    val byId = remember(items, results) {
        items.zip(results).associate { (item, result) -> item.manga.id to result }
    }
    fun resultOf(item: MigratingManga) = byId[item.manga.id] ?: item.searchResult.value
    fun isFewer(item: MigratingManga): Boolean {
        val result = resultOf(item) as? SearchResult.Success ?: return false
        return result.chapterCount < item.chapterCount
    }

    val matched = items.count { resultOf(it) is SearchResult.Success }
    val notFound = items.count { resultOf(it) == SearchResult.NotFound }
    val fewer = items.count(::isFewer)

    var filter by rememberSaveable { mutableStateOf(MatchFilter.ALL) }
    val shown = remember(items, byId, filter) {
        when (filter) {
            MatchFilter.ALL -> items
            MatchFilter.MATCHED -> items.filter { resultOf(it) is SearchResult.Success }
            MatchFilter.NOT_FOUND -> items.filter { resultOf(it) == SearchResult.NotFound }
            MatchFilter.FEWER -> items.filter(::isFewer)
        }
    }

    val allDone = items.isNotEmpty() && finishedCount >= items.size

    Scaffold(
        topBar = { scrollBehavior ->
            Column {
                AppBar(
                    title = if (allDone) {
                        stringResource(KMR.strings.migrate_matching_done)
                    } else {
                        stringResource(KMR.strings.migrate_matching_progress, finishedCount, items.size)
                    },
                    navigateUp = onBack,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_settings),
                                    icon = Icons.Outlined.Settings,
                                    onClick = openOptionsDialog,
                                ),
                                // Stop migrating: asks first
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationListScreen_exitDialogTitle),
                                    icon = Icons.Outlined.Close,
                                    onClick = onStop,
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (items.isNotEmpty()) {
                    LinearProgressIndicator(
                        progress = { (finishedCount.toFloat() / items.size).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                ) {
                    FilterChip(
                        selected = filter == MatchFilter.ALL,
                        onClick = { filter = MatchFilter.ALL },
                        label = { Text(stringResource(KMR.strings.migrate_filter_all, items.size)) },
                    )
                    FilterChip(
                        selected = filter == MatchFilter.MATCHED,
                        onClick = { filter = MatchFilter.MATCHED },
                        label = { Text(stringResource(KMR.strings.migrate_filter_matched, matched)) },
                    )
                    FilterChip(
                        selected = filter == MatchFilter.NOT_FOUND,
                        onClick = { filter = MatchFilter.NOT_FOUND },
                        label = { Text(stringResource(KMR.strings.migrate_filter_not_found, notFound)) },
                    )
                    FilterChip(
                        selected = filter == MatchFilter.FEWER,
                        onClick = { filter = MatchFilter.FEWER },
                        label = { Text(stringResource(KMR.strings.migrate_filter_fewer, fewer)) },
                    )
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                ) {
                    OutlinedButton(
                        onClick = { openMigrationDialog(true) },
                        enabled = migrationComplete,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(KMR.strings.migrate_copy_n, matched))
                    }
                    Button(
                        onClick = { openMigrationDialog(false) },
                        enabled = migrationComplete,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(KMR.strings.migrate_migrate_n, matched))
                    }
                }
            }
        },
    ) { contentPadding ->
        FastScrollLazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.manga.id }) { item ->
                MigrationRow(
                    item = item,
                    result = resultOf(item),
                    matching = item.manga.id == matchingId,
                    onClick = { onItemClick(item.manga) },
                    onSearchManually = { onSearchManually(item) },
                    onSkip = { onSkip(item.manga.id) },
                    onMigrate = { onMigrate(item.manga.id) },
                    onCopy = { onCopy(item.manga.id) },
                    onCancel = { onCancel(item.manga.id) },
                    modifier = Modifier.animateItemFastScroll(),
                )
                // The match and other close results, with covers; more in the Candidates window
                val candidates by item.candidates.collectAsState()
                CandidatesSection(
                    result = resultOf(item),
                    candidates = candidates,
                    onPick = { onPickCandidate(item.manga.id, it) },
                    onInfo = { onItemClick(it.manga) },
                    onOpenCandidates = { candidatesFor = item },
                )
                HorizontalDivider()
            }
        }
    }

    candidatesFor?.let { item ->
        val candidates by item.candidates.collectAsState()
        val result by item.searchResult.collectAsState()
        CandidatesWindow(
            title = item.manga.title,
            candidates = candidates,
            selectedId = (result as? SearchResult.Success)?.manga?.id,
            search = searchCandidates,
            onPick = {
                onPickCandidate(item.manga.id, it)
                candidatesFor = null
            },
            onInfo = { onItemClick(it.manga) },
            onDismissRequest = { candidatesFor = null },
        )
    }
}

@Composable
private fun MigrationRow(
    item: MigratingManga,
    result: SearchResult,
    matching: Boolean,
    onClick: () -> Unit,
    onSearchManually: () -> Unit,
    onSkip: () -> Unit,
    onMigrate: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = MaterialTheme.padding.medium, top = MaterialTheme.padding.small)
            .padding(bottom = MaterialTheme.padding.small),
    ) {
        MangaCover.Book(
            data = item.manga,
            modifier = Modifier.width(48.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MaterialTheme.padding.medium),
        ) {
            Text(
                text = item.manga.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        KMR.strings.migrate_source_chapters,
                        item.source,
                        item.chapterCount,
                    ) + " → ",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when (result) {
                    SearchResult.Searching -> {
                        if (matching) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = if (matching) {
                                stringResource(KMR.strings.migrate_status_matching)
                            } else {
                                stringResource(KMR.strings.migrate_status_waiting)
                            },
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SearchResult.NotFound -> Text(
                        text = stringResource(KMR.strings.migrate_status_not_found),
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is SearchResult.Success -> Text(
                        text = stringResource(KMR.strings.migrate_source_chapters, result.source, result.chapterCount),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.chapterCount < item.chapterCount) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }
        RowMenu(
            result = result,
            onSearchManually = onSearchManually,
            onSkip = onSkip,
            onMigrate = onMigrate,
            onCopy = onCopy,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun RowMenu(
    result: SearchResult,
    onSearchManually: () -> Unit,
    onSkip: () -> Unit,
    onMigrate: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val close = { expanded = false }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = close) {
            if (result == SearchResult.Searching) {
                // Stop searching this one, so it can be searched by hand
                DropdownMenuItem(
                    text = { Text(stringResource(MR.strings.action_cancel)) },
                    onClick = {
                        close()
                        onCancel()
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.strings.migrationListScreen_searchManuallyActionLabel)) },
                    onClick = {
                        close()
                        onSearchManually()
                    },
                )
                if (result is SearchResult.Success) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.migrationListScreen_migrateNowActionLabel)) },
                        onClick = {
                            close()
                            onMigrate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.migrationListScreen_copyNowActionLabel)) },
                        onClick = {
                            close()
                            onCopy()
                        },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.migrationListScreen_skipActionLabel)) },
                onClick = {
                    close()
                    onSkip()
                },
            )
        }
    }
}
