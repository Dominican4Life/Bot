package eu.kanade.presentation.library

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.library.AnimeLibraryScreenModel
import eu.kanade.tachiyomi.ui.library.LibrarySettingsScreenModel
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.BaseSortItem
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.IconItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun LibrarySettingsDialog(
    onDismissRequest: () -> Unit,
    screenModel: LibrarySettingsScreenModel,
    category: Category?,
    // SY -->
    hasCategories: Boolean,
    // SY <--
    // KMK -->
    categories: List<Category>,
    showingAnime: Boolean = false,
    animeCategories: List<Category> = emptyList(),
    // KMK <--
) {
    // KMK -->
    // The anime library has its own filter, sort and group options (the ones Anikku has)
    if (showingAnime) {
        TabbedDialog(
            onDismissRequest = onDismissRequest,
            tabTitles = persistentListOf(
                stringResource(MR.strings.action_filter),
                stringResource(MR.strings.action_sort),
                stringResource(MR.strings.action_display),
                stringResource(SYMR.strings.group),
            ),
        ) { page ->
            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (page) {
                    0 -> AnimeFilterPage(screenModel = screenModel, categories = animeCategories)
                    1 -> AnimeSortPage(screenModel = screenModel)
                    2 -> DisplayPage(screenModel = screenModel, showingAnime = true)
                    3 -> AnimeGroupPage(screenModel = screenModel, hasCategories = animeCategories.isNotEmpty())
                }
            }
        }
        return
    }
    // KMK <--
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
            // SY -->
            stringResource(SYMR.strings.group),
            // SY <--
        ),
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> FilterPage(
                    screenModel = screenModel,
                    // KMK -->
                    categories = categories,
                    // KMK <--
                )
                1 -> SortPage(
                    category = category,
                    screenModel = screenModel,
                )
                2 -> DisplayPage(
                    screenModel = screenModel,
                )
                // SY -->
                3 -> GroupPage(
                    screenModel = screenModel,
                    hasCategories = hasCategories,
                )
                // SY <--
            }
        }
    }
}

@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.FilterPage(
    screenModel: LibrarySettingsScreenModel,
    categories: List<Category>,
) {
    val filterDownloaded by screenModel.libraryPreferences.filterDownloaded().collectAsState()
    val downloadedOnly by screenModel.preferences.downloadedOnly().collectAsState()
    val autoUpdateMangaRestrictions by screenModel.libraryPreferences.autoUpdateMangaRestrictions().collectAsState()

    TriStateItem(
        label = stringResource(MR.strings.label_downloaded),
        state = if (downloadedOnly) {
            TriState.ENABLED_IS
        } else {
            filterDownloaded
        },
        enabled = !downloadedOnly,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterDownloaded) },
    )
    val filterUnread by screenModel.libraryPreferences.filterUnread().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_unread),
        state = filterUnread,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterUnread) },
    )
    val filterStarted by screenModel.libraryPreferences.filterStarted().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_started),
        state = filterStarted,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterStarted) },
    )
    val filterBookmarked by screenModel.libraryPreferences.filterBookmarked().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_bookmarked),
        state = filterBookmarked,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterBookmarked) },
    )
    val filterCompleted by screenModel.libraryPreferences.filterCompleted().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.completed),
        state = filterCompleted,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterCompleted) },
    )
    // TODO: re-enable when custom intervals are ready for stable
    if ((!isReleaseBuildType) && LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in autoUpdateMangaRestrictions) {
        val filterIntervalCustom by screenModel.libraryPreferences.filterIntervalCustom().collectAsState()
        TriStateItem(
            label = stringResource(MR.strings.action_filter_interval_custom),
            state = filterIntervalCustom,
            onClick = { screenModel.toggleFilter(LibraryPreferences::filterIntervalCustom) },
        )
    }
    // SY -->
    val filterLewd by screenModel.libraryPreferences.filterLewd().collectAsState()
    TriStateItem(
        label = stringResource(SYMR.strings.lewd),
        state = filterLewd,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterLewd) },
    )
    // SY <--

    // KMK -->
    CategoriesFilter(
        filterCategories = screenModel.libraryPreferences.filterCategories(),
        filterCategoriesInclude = screenModel.libraryPreferences.filterCategoriesInclude(),
        filterCategoriesExclude = screenModel.libraryPreferences.filterCategoriesExclude(),
        categories = categories,
    )
    // KMK <--

    val trackers by screenModel.trackersFlow.collectAsState()
    when (trackers.size) {
        0 -> {
            // No trackers
        }
        1 -> {
            val service = trackers[0]
            val filterTracker by screenModel.libraryPreferences.filterTracking(service.id.toInt()).collectAsState()
            TriStateItem(
                label = stringResource(MR.strings.action_filter_tracked),
                state = filterTracker,
                onClick = { screenModel.toggleTracker(service.id.toInt()) },
            )
        }
        else -> {
            HeadingItem(MR.strings.action_filter_tracked)
            trackers.map { service ->
                val filterTracker by screenModel.libraryPreferences.filterTracking(service.id.toInt()).collectAsState()
                TriStateItem(
                    label = service.name,
                    state = filterTracker,
                    onClick = { screenModel.toggleTracker(service.id.toInt()) },
                )
            }
        }
    }
}

// KMK -->
/** The filters Anikku's library has, for the anime half of the library */
@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.AnimeFilterPage(
    screenModel: LibrarySettingsScreenModel,
    categories: List<Category>,
) {
    val filterDownloaded by screenModel.libraryPreferences.animeFilterDownloaded().collectAsState()
    val downloadedOnly by screenModel.preferences.downloadedOnly().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_downloaded),
        state = if (downloadedOnly) {
            TriState.ENABLED_IS
        } else {
            filterDownloaded
        },
        enabled = !downloadedOnly,
        onClick = { screenModel.toggleFilter(LibraryPreferences::animeFilterDownloaded) },
    )
    val filterUnseen by screenModel.libraryPreferences.animeFilterUnseen().collectAsState()
    TriStateItem(
        label = stringResource(KMR.strings.anime_filter_unseen),
        state = filterUnseen,
        onClick = { screenModel.toggleFilter(LibraryPreferences::animeFilterUnseen) },
    )
    val filterStarted by screenModel.libraryPreferences.animeFilterStarted().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_started),
        state = filterStarted,
        onClick = { screenModel.toggleFilter(LibraryPreferences::animeFilterStarted) },
    )
    val filterBookmarked by screenModel.libraryPreferences.animeFilterBookmarked().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_bookmarked),
        state = filterBookmarked,
        onClick = { screenModel.toggleFilter(LibraryPreferences::animeFilterBookmarked) },
    )
    val filterCompleted by screenModel.libraryPreferences.animeFilterCompleted().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.completed),
        state = filterCompleted,
        onClick = { screenModel.toggleFilter(LibraryPreferences::animeFilterCompleted) },
    )
    val filterLewd by screenModel.libraryPreferences.animeFilterLewd().collectAsState()
    TriStateItem(
        label = stringResource(SYMR.strings.lewd),
        state = filterLewd,
        onClick = { screenModel.toggleFilter(LibraryPreferences::animeFilterLewd) },
    )

    CategoriesFilter(
        filterCategories = screenModel.libraryPreferences.animeFilterCategories(),
        filterCategoriesInclude = screenModel.libraryPreferences.animeFilterCategoriesInclude(),
        filterCategoriesExclude = screenModel.libraryPreferences.animeFilterCategoriesExclude(),
        categories = categories,
    )
}

/** Anikku's sort options (tracker score is left for when anime tracking exists) */
@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.AnimeSortPage(
    screenModel: LibrarySettingsScreenModel,
) {
    val sort by screenModel.libraryPreferences.animeSortingMode().collectAsState()
    val sortingMode = sort.type
    val sortDescending = !sort.isAscending
    val hasSortTags by remember {
        screenModel.libraryPreferences.sortTagsForLibrary().changes()
            .map { it.isNotEmpty() }
    }.collectAsState(initial = screenModel.libraryPreferences.sortTagsForLibrary().get().isNotEmpty())

    val options = remember(hasSortTags) {
        listOfNotNull(
            MR.strings.action_sort_alpha to LibrarySort.Type.Alphabetical,
            KMR.strings.anime_sort_total_episodes to LibrarySort.Type.TotalChapters,
            KMR.strings.anime_sort_last_seen to LibrarySort.Type.LastRead,
            AMR.strings.action_sort_last_update_check to LibrarySort.Type.LastUpdate,
            KMR.strings.anime_sort_unseen_count to LibrarySort.Type.UnreadCount,
            KMR.strings.anime_sort_latest_episode to LibrarySort.Type.LatestChapter,
            KMR.strings.anime_sort_episode_fetch_date to LibrarySort.Type.ChapterFetchDate,
            MR.strings.action_sort_date_added to LibrarySort.Type.DateAdded,
            KMR.strings.anime_sort_airing_time to LibrarySort.Type.AiringTime,
            (SYMR.strings.tag_sorting to LibrarySort.Type.TagList).takeIf { hasSortTags },
            MR.strings.action_sort_random to LibrarySort.Type.Random,
        )
    }

    options.map { (titleRes, mode) ->
        if (mode == LibrarySort.Type.Random) {
            BaseSortItem(
                label = stringResource(titleRes),
                icon = Icons.Default.Refresh
                    .takeIf { sortingMode == LibrarySort.Type.Random },
                onClick = {
                    screenModel.setAnimeSort(mode, LibrarySort.Direction.Ascending)
                },
            )
            return@map
        }
        SortItem(
            label = stringResource(titleRes),
            sortDescending = sortDescending.takeIf { sortingMode == mode },
            onClick = {
                // Tapping the current mode flips its direction; a new mode keeps the direction
                val descending = if (sortingMode == mode) !sortDescending else sortDescending
                screenModel.setAnimeSort(
                    mode,
                    if (descending) LibrarySort.Direction.Descending else LibrarySort.Direction.Ascending,
                )
            },
        )
    }
}

/** Anikku's group options */
@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.AnimeGroupPage(
    screenModel: LibrarySettingsScreenModel,
    hasCategories: Boolean,
) {
    val groups = remember(hasCategories) {
        listOfNotNull(
            GroupMode(LibraryGroup.BY_DEFAULT, MR.strings.categories, R.drawable.ic_label_24dp),
            GroupMode(LibraryGroup.BY_SOURCE, MR.strings.label_sources, R.drawable.ic_browse_filled_24dp),
            GroupMode(AnimeLibraryScreenModel.GROUP_BY_TAG, AMR.strings.tag, R.drawable.ic_tag_24dp),
            GroupMode(LibraryGroup.BY_STATUS, MR.strings.status, R.drawable.ic_progress_clock_24dp),
            // Like the manga library: only offered once there are categories to ungroup
            GroupMode(LibraryGroup.UNGROUPED, SYMR.strings.ungrouped, R.drawable.ic_ungroup_24dp)
                .takeIf { hasCategories || screenModel.animeGrouping == LibraryGroup.UNGROUPED },
        ).toImmutableList()
    }

    groups.fastForEach {
        IconItem(
            label = stringResource(it.nameRes),
            icon = painterResource(it.drawableRes),
            selected = it.int == screenModel.animeGrouping,
            onClick = {
                screenModel.setAnimeGrouping(it.int)
            },
        )
    }
}
// KMK <--

@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.SortPage(
    category: Category?,
    screenModel: LibrarySettingsScreenModel,
) {
    val trackers by screenModel.trackersFlow.collectAsState()
    // SY -->
    val globalSortMode by screenModel.libraryPreferences.sortingMode().collectAsState()
    val sortingMode = if (screenModel.grouping == LibraryGroup.BY_DEFAULT) {
        category.sort.type
    } else {
        globalSortMode.type
    }
    val sortDescending = if (screenModel.grouping == LibraryGroup.BY_DEFAULT) {
        !category.sort.isAscending
    } else {
        !globalSortMode.isAscending
    }
    val hasSortTags by remember {
        screenModel.libraryPreferences.sortTagsForLibrary().changes()
            .map { it.isNotEmpty() }
    }.collectAsState(initial = screenModel.libraryPreferences.sortTagsForLibrary().get().isNotEmpty())
    // SY <--

    val options = remember(trackers.isEmpty()/* SY --> */, hasSortTags/* SY <-- */) {
        val trackerMeanPair = if (trackers.isNotEmpty()) {
            MR.strings.action_sort_tracker_score to LibrarySort.Type.TrackerMean
        } else {
            null
        }
        // SY -->
        val tagSortPair = if (hasSortTags) {
            SYMR.strings.tag_sorting to LibrarySort.Type.TagList
        } else {
            null
        }
        // SY <--
        listOfNotNull(
            MR.strings.action_sort_alpha to LibrarySort.Type.Alphabetical,
            MR.strings.action_sort_total to LibrarySort.Type.TotalChapters,
            MR.strings.action_sort_last_read to LibrarySort.Type.LastRead,
            MR.strings.action_sort_last_manga_update to LibrarySort.Type.LastUpdate,
            MR.strings.action_sort_unread_count to LibrarySort.Type.UnreadCount,
            MR.strings.action_sort_latest_chapter to LibrarySort.Type.LatestChapter,
            MR.strings.action_sort_chapter_fetch_date to LibrarySort.Type.ChapterFetchDate,
            MR.strings.action_sort_date_added to LibrarySort.Type.DateAdded,
            trackerMeanPair,
            // SY -->
            tagSortPair,
            // SY <--
            MR.strings.action_sort_random to LibrarySort.Type.Random,
        )
    }

    options.map { (titleRes, mode) ->
        if (mode == LibrarySort.Type.Random) {
            BaseSortItem(
                label = stringResource(titleRes),
                icon = Icons.Default.Refresh
                    .takeIf { sortingMode == LibrarySort.Type.Random },
                onClick = {
                    screenModel.setSort(category, mode, LibrarySort.Direction.Ascending)
                },
            )
            return@map
        }
        SortItem(
            label = stringResource(titleRes),
            sortDescending = sortDescending.takeIf { sortingMode == mode },
            onClick = {
                val isTogglingDirection = sortingMode == mode
                val direction = when {
                    isTogglingDirection -> if (sortDescending) {
                        LibrarySort.Direction.Ascending
                    } else {
                        LibrarySort.Direction.Descending
                    }
                    else -> if (sortDescending) {
                        LibrarySort.Direction.Descending
                    } else {
                        LibrarySort.Direction.Ascending
                    }
                }
                screenModel.setSort(category, mode, direction)
            },
        )
    }
}

private val displayModes = listOf(
    MR.strings.action_display_grid to LibraryDisplayMode.CompactGrid,
    MR.strings.action_display_comfortable_grid to LibraryDisplayMode.ComfortableGrid,
    MR.strings.action_display_list to LibraryDisplayMode.List,
    MR.strings.action_display_cover_only_grid to LibraryDisplayMode.CoverOnlyGrid,
    // KMK -->
    KMR.strings.action_display_comfortable_grid_panorama to LibraryDisplayMode.ComfortableGridPanorama,
    // KMK <--
)

@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.DisplayPage(
    screenModel: LibrarySettingsScreenModel,
    // KMK -->
    showingAnime: Boolean = false,
    // KMK <--
) {
    // KMK -->
    // The anime library keeps its own display settings
    val prefs = screenModel.libraryPreferences
    val displayModePref = if (showingAnime) prefs.animeDisplayMode() else prefs.displayMode()
    // KMK <--
    val displayMode by displayModePref.collectAsState()
    SettingsChipRow(MR.strings.action_display_mode) {
        displayModes.map { (titleRes, mode) ->
            FilterChip(
                selected = displayMode == mode,
                onClick = {
                    // KMK -->
                    if (showingAnime) {
                        displayModePref.set(mode)
                    } else {
                        // KMK <--
                        screenModel.setDisplayMode(mode)
                    }
                },
                label = { Text(stringResource(titleRes)) },
            )
        }
    }

    if (displayMode != LibraryDisplayMode.List) {
        val configuration = LocalConfiguration.current
        val columnPreference = remember {
            val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            // KMK -->
            if (showingAnime) {
                if (landscape) prefs.animeLandscapeColumns() else prefs.animePortraitColumns()
            } else if (landscape) {
                // KMK <--
                prefs.landscapeColumns()
            } else {
                prefs.portraitColumns()
            }
        }

        val columns by columnPreference.collectAsState()
        SliderItem(
            value = columns,
            // KMK: up to 15 covers per row
            valueRange = 0..15,
            label = stringResource(MR.strings.pref_library_columns),
            valueString = if (columns > 0) {
                columns.toString()
            } else {
                stringResource(MR.strings.label_auto)
            },
            onChange = columnPreference::set,
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    HeadingItem(MR.strings.overlay_header)
    CheckboxItem(
        label = stringResource(
            if (showingAnime) KMR.strings.anime_display_download_badge else MR.strings.action_display_download_badge,
        ),
        pref = if (showingAnime) prefs.animeDownloadBadge() else prefs.downloadBadge(),
    )
    CheckboxItem(
        label = stringResource(
            if (showingAnime) AMR.strings.action_display_unseen_badge else MR.strings.action_display_unread_badge,
        ),
        pref = if (showingAnime) prefs.animeUnseenBadge() else prefs.unreadBadge(),
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_local_badge),
        pref = if (showingAnime) prefs.animeLocalBadge() else prefs.localBadge(),
    )
    val languageBadgePref = if (showingAnime) prefs.animeLanguageBadge() else prefs.languageBadge()
    CheckboxItem(
        label = stringResource(MR.strings.action_display_language_badge),
        pref = languageBadgePref,
    )
    // KMK -->
    val showLang by languageBadgePref.collectAsState()
    if (showLang) {
        CheckboxItem(
            label = stringResource(KMR.strings.action_display_language_icon),
            pref = if (showingAnime) prefs.animeUseLangIcon() else prefs.useLangIcon(),
        )
    }
    CheckboxItem(
        label = stringResource(KMR.strings.action_display_source_badge),
        pref = if (showingAnime) prefs.animeSourceBadge() else prefs.sourceBadge(),
    )
    // KMK <--
    CheckboxItem(
        label = stringResource(
            if (showingAnime) {
                AMR.strings.action_display_show_continue_watching_button
            } else {
                MR.strings.action_display_show_continue_reading_button
            },
        ),
        pref = if (showingAnime) prefs.animeShowContinueWatchingButton() else prefs.showContinueReadingButton(),
    )

    HeadingItem(MR.strings.tabs_header)
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_tabs),
        pref = if (showingAnime) prefs.animeCategoryTabs() else prefs.categoryTabs(),
    )
    // KMK -->
    CheckboxItem(
        label = stringResource(KMR.strings.action_show_hidden_categories),
        pref = if (showingAnime) prefs.animeShowHiddenCategories() else prefs.showHiddenCategories(),
    )
    // KMK <--
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_number_of_items),
        pref = if (showingAnime) prefs.animeCategoryNumberOfItems() else prefs.categoryNumberOfItems(),
    )
}

// SY -->
data class GroupMode(
    val int: Int,
    val nameRes: StringResource,
    val drawableRes: Int,
)

private fun groupTypeDrawableRes(type: Int): Int {
    return when (type) {
        LibraryGroup.BY_STATUS -> R.drawable.ic_progress_clock_24dp
        LibraryGroup.BY_TRACK_STATUS -> R.drawable.ic_sync_24dp
        LibraryGroup.BY_SOURCE -> R.drawable.ic_browse_filled_24dp
        LibraryGroup.UNGROUPED -> R.drawable.ic_ungroup_24dp
        else -> R.drawable.ic_label_24dp
    }
}

@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.GroupPage(
    screenModel: LibrarySettingsScreenModel,
    hasCategories: Boolean,
) {
    val trackers by screenModel.trackersFlow.collectAsState()
    val groups = remember(hasCategories, trackers) {
        buildList {
            add(LibraryGroup.BY_DEFAULT)
            add(LibraryGroup.BY_SOURCE)
            add(LibraryGroup.BY_STATUS)
            if (trackers.isNotEmpty()) {
                add(LibraryGroup.BY_TRACK_STATUS)
            }
            if (hasCategories || screenModel.grouping == LibraryGroup.UNGROUPED) {
                add(LibraryGroup.UNGROUPED)
            }
        }.map {
            GroupMode(
                it,
                LibraryGroup.groupTypeStringRes(it),
                groupTypeDrawableRes(it),
            )
        }.toImmutableList()
    }

    groups.fastForEach {
        IconItem(
            label = stringResource(it.nameRes),
            icon = painterResource(it.drawableRes),
            selected = it.int == screenModel.grouping,
            onClick = {
                screenModel.setGrouping(it.int)
            },
        )
    }
}
// SY <--

// KMK -->
@Composable
private fun CategoriesFilter(
    filterCategories: Preference<Boolean>,
    filterCategoriesInclude: Preference<Set<String>>,
    filterCategoriesExclude: Preference<Set<String>>,
    categories: List<Category>,
) {
    val isFiltering by filterCategories.collectAsState()

    val included by filterCategoriesInclude.collectAsState()
    val excluded by filterCategoriesExclude.collectAsState()

    var showCategoriesDialog by rememberSaveable { mutableStateOf(false) }
    if (showCategoriesDialog) {
        TriStateListDialog(
            title = stringResource(MR.strings.categories),
            message = stringResource(KMR.strings.pref_library_filter_categories_details),
            items = categories,
            initialChecked = included.mapNotNull { id -> categories.find { it.id.toString() == id } },
            initialInversed = excluded.mapNotNull { id -> categories.find { it.id.toString() == id } },
            itemLabel = { it.visualName },
            onDismissRequest = { showCategoriesDialog = false },
            onValueChanged = { newIncluded, newExcluded ->
                filterCategoriesInclude.set(newIncluded.map { it.id.toString() }.toSet())
                filterCategoriesExclude.set(newExcluded.map { it.id.toString() }.toSet())
                showCategoriesDialog = false
            },
        )
    }

    Row(
        modifier = Modifier
            .clickable(onClick = { filterCategories.toggle() })
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Checkbox(
            checked = isFiltering,
            onCheckedChange = null,
        )
        Text(
            text = stringResource(MR.strings.categories),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { showCategoriesDialog = true }) {
            Text(stringResource(MR.strings.action_edit))
        }
    }
}
// KMK <--
