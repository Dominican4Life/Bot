package mihon.feature.migration.anime

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.MigrateSourceScreen
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.ui.browse.migration.sources.MigrateSourceScreenModel
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Browse › Migrate for anime, the same list as the manga one: the anime sources with library anime and how
 * many, search and sorting. All opens the Migrate window with every anime of that source; tapping a source picks
 * some of its anime first.
 */
@Composable
fun AnimeMigrateSourcesContent(contentPadding: PaddingValues) {
    val scope = rememberCoroutineScope()
    val preferences = remember { Injekt.get<SourcePreferences>() }
    val setMigrateSorting = remember { Injekt.get<SetMigrateSorting>() }
    val sortingMode by preferences.migrationSortingMode().collectAsState()
    val sortingDirection by preferences.migrationSortingDirection().collectAsState()
    val flow = remember { AnimeMigrateSources.subscribe() }
    val sources by flow.collectFlowAsState(initial = null)
    var searchQuery by remember { mutableStateOf<String?>(null) }
    // The source whose anime are being picked
    var openSourceId by remember { mutableStateOf<Long?>(null) }

    val items = remember(sources, searchQuery) {
        sources.orEmpty().filter { (source, _) -> AnimeMigrateSources.matches(source, searchQuery) }.toImmutableList()
    }
    MigrateSourceScreen(
        state = MigrateSourceScreenModel.State(
            isLoading = sources == null,
            items = items,
            sortingMode = sortingMode,
            sortingDirection = sortingDirection,
            searchQuery = searchQuery,
        ),
        contentPadding = contentPadding,
        onClickItem = { source -> openSourceId = source.id },
        onToggleSortingDirection = {
            val direction = when (sortingDirection) {
                SetMigrateSorting.Direction.ASCENDING -> SetMigrateSorting.Direction.DESCENDING
                SetMigrateSorting.Direction.DESCENDING -> SetMigrateSorting.Direction.ASCENDING
            }
            setMigrateSorting.await(sortingMode, direction)
        },
        onToggleSortingMode = {
            val mode = when (sortingMode) {
                SetMigrateSorting.Mode.ALPHABETICAL -> SetMigrateSorting.Mode.TOTAL
                SetMigrateSorting.Mode.TOTAL -> SetMigrateSorting.Mode.ALPHABETICAL
            }
            setMigrateSorting.await(mode, sortingDirection)
        },
        onChangeSearchQuery = { searchQuery = it },
        onClickAll = { source ->
            scope.launchIO {
                val animeIds = Injekt.get<AnimeRepository>().getFavorites()
                    .filter { it.source == source.id }
                    .map { it.id }
                if (animeIds.isNotEmpty()) AnimeMigrationStart.open(animeIds)
            }
        },
    )

    openSourceId?.let { sourceId ->
        AnimeSourceToMigratePickerDialog(
            initial = emptySet(),
            openSourceId = sourceId,
            onDismissRequest = { openSourceId = null },
            onConfirm = { picked, _ ->
                openSourceId = null
                if (picked.isNotEmpty()) AnimeMigrationStart.open(picked)
            },
        )
    }
}
