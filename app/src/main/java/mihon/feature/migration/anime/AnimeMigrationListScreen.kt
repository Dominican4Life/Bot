package mihon.feature.migration.anime

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.combine
import mihon.feature.migration.anime.MigratingAnime.Candidate
import mihon.feature.migration.anime.MigratingAnime.SearchResult
import mihon.feature.migration.list.components.MigrationExitDialog
import mihon.feature.migration.list.components.MigrationMangaDialog
import mihon.feature.migration.list.components.MigrationProgressDialog
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * KMK: The anime matching screen, like MigrationListScreen: one row per anime (old source → new source), its
 * candidates, and Copy / Migrate at the bottom.
 */
class AnimeMigrationListScreen(
    private val animeIds: Collection<Long>,
    private val extraSearchQuery: String?,
) : Screen() {

    companion object {
        /** The matching screen of the anime migration running in the background, opened from its notification */
        fun resume() = AnimeMigrationListScreen(emptyList(), null)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // A screen opened from the notification (no anime) comes back to the migration that's running
        val resuming = animeIds.isEmpty()
        val screenModel = rememberScreenModel {
            if (resuming) {
                ActiveAnimeMigration.model ?: AnimeMigrationListScreenModel(emptyList(), null)
            } else {
                AnimeMigrationListScreenModel(animeIds, extraSearchQuery).also(ActiveAnimeMigration::start)
            }
        }
        val state by screenModel.state.collectAsState()
        val context = LocalContext.current

        if (resuming && ActiveAnimeMigration.model == null) {
            LaunchedEffect(Unit) { navigator.pop() }
            return
        }
        DisposableEffect(screenModel) {
            screenModel.setAttached(true)
            onDispose { screenModel.setAttached(false) }
        }
        val matchingId by screenModel.matchingId.collectAsState()

        LaunchedEffect(screenModel) {
            screenModel.navigateBackEvent.collect {
                // A single anime migrated from its page: that page now shows the new entry
                val newId = (state.items.firstOrNull()?.searchResult?.value as? SearchResult.Success)?.anime?.id
                if (animeIds.size == 1 && newId != null && navigator.items.any { it is AnimeScreen }) {
                    val newStack = navigator.items.filter {
                        it !is AnimeScreen && it !is AnimeMigrationListScreen
                    } + AnimeScreen(newId)
                    navigator replaceAll newStack.first()
                    navigator.push(newStack.drop(1))
                    // Set the navigator in a pop state to dispose of everything properly
                    navigator.push(this@AnimeMigrationListScreen)
                    navigator.pop()
                } else {
                    navigator.pop()
                }
            }
        }

        val onMissingEpisodes: () -> Unit = {
            context.toast(KMR.strings.anime_migrate_match_without_episodes, Toast.LENGTH_LONG)
        }
        AnimeMigrationListScreenContent(
            items = state.items,
            migrationComplete = state.migrationComplete,
            finishedCount = state.finishedCount,
            matchingId = matchingId,
            onBack = navigator::pop,
            onStop = screenModel::showExitDialog,
            onItemClick = { navigator.push(AnimeScreen(it.id)) },
            onSkip = { screenModel.removeAnime(it) },
            onMigrate = { screenModel.migrateNow(animeId = it, replace = true) },
            onCopy = { screenModel.migrateNow(animeId = it, replace = false) },
            openMigrationDialog = screenModel::showMigrateDialog,
            onCancel = { screenModel.cancelAnime(it) },
            openOptionsDialog = screenModel::openOptionsDialog,
            onPickCandidate = { current, candidate ->
                screenModel.pickCandidate(
                    current = current,
                    candidate = candidate,
                    onMissingEpisodes = onMissingEpisodes,
                )
            },
            searchCandidates = screenModel::searchCandidates,
        )

        when (val dialog = state.dialog) {
            is AnimeMigrationListScreenModel.Dialog.Migrate -> {
                MigrationMangaDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    copy = dialog.copy,
                    totalCount = dialog.totalCount,
                    skippedCount = dialog.skippedCount,
                    onMigrate = {
                        if (dialog.copy) screenModel.copyAnimes() else screenModel.migrateAnimes()
                    },
                )
            }
            is AnimeMigrationListScreenModel.Dialog.Progress -> {
                MigrationProgressDialog(
                    progress = dialog.progress,
                    exitMigration = screenModel::cancelMigrate,
                )
            }
            AnimeMigrationListScreenModel.Dialog.Exit -> {
                MigrationExitDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    // Stop ends the migration; the back arrow alone keeps it running in the background
                    exitMigration = {
                        screenModel.dismissDialog()
                        screenModel.stop()
                        navigator.pop()
                    },
                )
            }
            AnimeMigrationListScreenModel.Dialog.Options -> {
                AnimeMigrationOptionsSheet(
                    preferences = screenModel.preferences,
                    onDismissRequest = {
                        screenModel.dismissDialog()
                        screenModel.updateOptions()
                    },
                )
            }
            null -> Unit
        }

        // Back keeps the migration going in the background; its notification brings you back here
        BackHandler(true) {
            navigator.pop()
        }
    }
}

/** KMK: Which anime the matching screen shows */
private enum class AnimeMatchFilter { ALL, MATCHED, NOT_FOUND, FEWER }

/**
 * KMK: The anime matching screen's layout, the same as the manga one: "Matching 3 / 7" with a progress line, filter
 * chips, one row per anime with its candidates, and Copy / Migrate at the bottom.
 */
@Composable
private fun AnimeMigrationListScreenContent(
    items: ImmutableList<MigratingAnime>,
    migrationComplete: Boolean,
    finishedCount: Int,
    matchingId: Long?,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onItemClick: (Anime) -> Unit,
    onSkip: (Long) -> Unit,
    onMigrate: (Long) -> Unit,
    onCopy: (Long) -> Unit,
    openMigrationDialog: (Boolean) -> Unit,
    onCancel: (Long) -> Unit,
    openOptionsDialog: () -> Unit,
    onPickCandidate: (Long, Candidate) -> Unit,
    searchCandidates: suspend (String) -> List<Candidate>,
) {
    // The anime whose Candidates window is open, and whether it opened from "Search manually"
    var candidatesFor by remember { mutableStateOf<MigratingAnime?>(null) }
    var searchNow by remember { mutableStateOf(false) }

    // Every anime's result, kept up to date as the search goes
    val resultsFlow = remember(items) { combine(items.map { it.searchResult }) { it.toList() } }
    val results by resultsFlow.collectAsState(initial = items.map { it.searchResult.value })
    val byId = remember(items, results) {
        items.zip(results).associate { (item, result) -> item.anime.id to result }
    }
    fun resultOf(item: MigratingAnime) = byId[item.anime.id] ?: item.searchResult.value
    fun isFewer(item: MigratingAnime): Boolean {
        val result = resultOf(item) as? SearchResult.Success ?: return false
        return result.episodeCount < item.episodeCount
    }

    val matched = items.count { resultOf(it) is SearchResult.Success }
    val notFound = items.count { resultOf(it) == SearchResult.NotFound }
    val fewer = items.count(::isFewer)

    var filter by rememberSaveable { mutableStateOf(AnimeMatchFilter.ALL) }
    val shown = remember(items, byId, filter) {
        when (filter) {
            AnimeMatchFilter.ALL -> items
            AnimeMatchFilter.MATCHED -> items.filter { resultOf(it) is SearchResult.Success }
            AnimeMatchFilter.NOT_FOUND -> items.filter { resultOf(it) == SearchResult.NotFound }
            AnimeMatchFilter.FEWER -> items.filter(::isFewer)
        }
    }

    val allDone = items.isNotEmpty() && finishedCount >= items.size

    Scaffold(
        topBar = { _ ->
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
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
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationListScreen_exitDialogTitle),
                                    icon = Icons.Outlined.Close,
                                    onClick = onStop,
                                ),
                            ),
                        )
                    },
                    scrollBehavior = null,
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
                        selected = filter == AnimeMatchFilter.ALL,
                        onClick = { filter = AnimeMatchFilter.ALL },
                        label = { Text(stringResource(KMR.strings.migrate_filter_all, items.size)) },
                    )
                    FilterChip(
                        selected = filter == AnimeMatchFilter.MATCHED,
                        onClick = { filter = AnimeMatchFilter.MATCHED },
                        label = { Text(stringResource(KMR.strings.migrate_filter_matched, matched)) },
                    )
                    FilterChip(
                        selected = filter == AnimeMatchFilter.NOT_FOUND,
                        onClick = { filter = AnimeMatchFilter.NOT_FOUND },
                        label = { Text(stringResource(KMR.strings.migrate_filter_not_found, notFound)) },
                    )
                    FilterChip(
                        selected = filter == AnimeMatchFilter.FEWER,
                        onClick = { filter = AnimeMatchFilter.FEWER },
                        label = { Text(stringResource(KMR.strings.anime_migrate_filter_fewer, fewer)) },
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
            items(shown, key = { it.anime.id }) { item ->
                AnimeMigrationRow(
                    item = item,
                    result = resultOf(item),
                    matching = item.anime.id == matchingId,
                    onClick = { onItemClick(item.anime) },
                    onSearchManually = {
                        searchNow = true
                        candidatesFor = item
                    },
                    onSkip = { onSkip(item.anime.id) },
                    onMigrate = { onMigrate(item.anime.id) },
                    onCopy = { onCopy(item.anime.id) },
                    onCancel = { onCancel(item.anime.id) },
                    modifier = Modifier.animateItemFastScroll(),
                )
                val candidates by item.candidates.collectAsState()
                AnimeCandidatesSection(
                    result = resultOf(item),
                    candidates = candidates,
                    onPick = { onPickCandidate(item.anime.id, it) },
                    onInfo = { onItemClick(it.anime) },
                    onOpenCandidates = {
                        searchNow = false
                        candidatesFor = item
                    },
                )
                HorizontalDivider()
            }
        }
    }

    candidatesFor?.let { item ->
        val candidates by item.candidates.collectAsState()
        val result by item.searchResult.collectAsState()
        AnimeCandidatesWindow(
            title = item.anime.title,
            candidates = candidates,
            selectedId = (result as? SearchResult.Success)?.anime?.id,
            search = searchCandidates,
            onPick = {
                onPickCandidate(item.anime.id, it)
                candidatesFor = null
            },
            onInfo = { onItemClick(it.anime) },
            onDismissRequest = { candidatesFor = null },
            searchNow = searchNow,
        )
    }
}

@Composable
private fun AnimeMigrationRow(
    item: MigratingAnime,
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
            data = item.anime.asMangaCover(),
            modifier = Modifier.width(48.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MaterialTheme.padding.medium),
        ) {
            Text(
                text = item.anime.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        KMR.strings.anime_migrate_source_episodes,
                        item.source,
                        item.episodeCount,
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
                        text = stringResource(
                            KMR.strings.anime_migrate_source_episodes,
                            result.source,
                            result.episodeCount,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.episodeCount < item.episodeCount) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }
        AnimeRowMenu(
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
private fun AnimeRowMenu(
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
