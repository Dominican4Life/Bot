package eu.kanade.presentation.anime

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.presentation.components.PanoramaIcons
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeAiring
import eu.kanade.tachiyomi.data.anime.AnimeLibraryStats
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.track.AnimeTrack
import eu.kanade.tachiyomi.data.anime.track.AnimeTracking
import exh.util.isLewd
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.BaseSortItem
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.IconItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/*
 * KMK: the anime library's own Filter / Sort / Display settings (from Anikku's library sheet). The Display mode,
 * columns and Panorama style are shared with the manga library; filters, sorting and badges are anime's own.
 */

enum class AnimeLibrarySort {
    ALPHABETICAL,
    LAST_SEEN,
    LAST_UPDATE,
    UNSEEN_COUNT,
    TOTAL_EPISODES,
    LATEST_EPISODE,
    EPISODE_FETCH_DATE,
    DATE_ADDED,
    TRACKER_SCORE,

    // KMK: when the next episode airs (AniList); anime with no known airing time go last
    NEXT_AIRING,
    RANDOM,
}

object AnimeLibraryPrefs {
    private val store get() = Injekt.get<PreferenceStore>()

    fun filterDownloaded() = store.getEnum("anime_library_filter_downloaded", TriState.DISABLED)
    fun filterUnseen() = store.getEnum("anime_library_filter_unseen", TriState.DISABLED)
    fun filterStarted() = store.getEnum("anime_library_filter_started", TriState.DISABLED)
    fun filterBookmarked() = store.getEnum("anime_library_filter_bookmarked", TriState.DISABLED)
    fun filterCompleted() = store.getEnum("anime_library_filter_completed", TriState.DISABLED)
    fun filterTracked(trackerId: Long) = store.getEnum("anime_library_filter_tracked_$trackerId", TriState.DISABLED)

    /** Tracked by any logged-in tracker */
    fun filterTrackedAny() = store.getEnum("anime_library_filter_tracked_any", TriState.DISABLED)
    fun filterLewd() = store.getEnum("anime_library_filter_lewd", TriState.DISABLED)

    /** The Categories filter: on/off, and the anime categories it keeps or hides (ids) */
    fun filterCategories() = store.getBoolean("anime_library_filter_categories", false)
    fun filterCategoriesInclude() = store.getStringSet("anime_library_filter_categories_include", emptySet())
    fun filterCategoriesExclude() = store.getStringSet("anime_library_filter_categories_exclude", emptySet())

    /** How the library's pages are made, one of [LibraryGroup]'s types */
    fun groupType() = store.getInt("anime_library_group_type", LibraryGroup.BY_DEFAULT)

    fun sort() = store.getEnum("anime_library_sort", AnimeLibrarySort.ALPHABETICAL)
    fun sortAscending() = store.getBoolean("anime_library_sort_ascending", true)

    /** Changes each time "Random" is picked, so the order shuffles again */
    fun randomSeed() = store.getLong("anime_library_random_seed", 0L)

    fun unseenBadge() = store.getBoolean("anime_library_badge_unseen", true)
    fun downloadBadge() = store.getBoolean("anime_library_badge_downloaded", false)
    fun languageBadge() = store.getBoolean("anime_library_badge_language", false)
    fun useLangIcon() = store.getBoolean("anime_library_badge_language_icon", true)
    fun sourceBadge() = store.getBoolean("anime_library_badge_source", false)
    fun continueButton() = store.getBoolean("anime_library_continue_button", false)
}

/** Filters and sorts the anime of one library page */
fun List<Anime>.filterAndSortAnime(
    stats: Map<Long, AnimeLibraryStats>,
    downloaded: Map<Long, Int>,
    tracks: Map<Long, List<AnimeTrack>>,
    filters: AnimeLibraryFilters,
    sort: AnimeLibrarySort,
    ascending: Boolean,
    randomSeed: Long,
    // KMK: next airing episodes, for [AnimeLibrarySort.NEXT_AIRING]
    airing: Map<Long, AnimeAiring> = emptyMap(),
    // KMK: the anime categories of each anime, for the Categories filter
    links: Map<Long, Set<Long>> = emptyMap(),
): List<Anime> {
    fun TriState.keep(value: Boolean) = when (this) {
        TriState.DISABLED -> true
        TriState.ENABLED_IS -> value
        TriState.ENABLED_NOT -> !value
    }
    val filtered = filter { anime ->
        val s = stats[anime.id]
        filters.downloaded.keep((downloaded[anime.id] ?: 0) > 0) &&
            filters.unseen.keep((s?.unseen ?: 0L) > 0) &&
            filters.started.keep(s?.started == true) &&
            filters.bookmarked.keep((s?.bookmarked ?: 0L) > 0) &&
            // SAnime.COMPLETED = 2
            filters.completed.keep(anime.status == 2L) &&
            filters.tracked.all { (trackerId, state) ->
                state.keep(tracks[anime.id].orEmpty().any { it.trackerId == trackerId })
            } &&
            filters.trackedAny.keep(tracks[anime.id].orEmpty().isNotEmpty()) &&
            (filters.lewd == TriState.DISABLED || filters.lewd.keep(anime.isLewdAnime())) &&
            filters.keepCategories(links[anime.id].orEmpty())
    }
    if (sort == AnimeLibrarySort.RANDOM) {
        return filtered.shuffled(java.util.Random(randomSeed))
    }
    val byTitle = compareBy<Anime, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
    if (sort == AnimeLibrarySort.NEXT_AIRING) {
        // Unknown airing times always go last, whichever the direction
        val (known, unknown) = filtered.partition { airing[it.id] != null }
        val byAiring = compareBy<Anime> { airing[it.id]?.airingAt ?: Long.MAX_VALUE }
        val ordered = if (ascending) byAiring else byAiring.reversed()
        return known.sortedWith(ordered.then(byTitle)) + unknown.sortedWith(byTitle)
    }
    val primary: Comparator<Anime> = when (sort) {
        AnimeLibrarySort.ALPHABETICAL -> byTitle
        AnimeLibrarySort.LAST_SEEN -> compareBy { stats[it.id]?.lastSeen ?: 0L }
        AnimeLibrarySort.LAST_UPDATE -> compareBy { it.lastUpdate }
        AnimeLibrarySort.UNSEEN_COUNT -> compareBy { stats[it.id]?.unseen ?: 0L }
        AnimeLibrarySort.TOTAL_EPISODES -> compareBy { stats[it.id]?.total ?: 0L }
        AnimeLibrarySort.LATEST_EPISODE -> compareBy { stats[it.id]?.latestUpload ?: 0L }
        AnimeLibrarySort.EPISODE_FETCH_DATE -> compareBy { stats[it.id]?.latestFetch ?: 0L }
        AnimeLibrarySort.DATE_ADDED -> compareBy { it.dateAdded }
        AnimeLibrarySort.TRACKER_SCORE -> compareBy { anime ->
            tracks[anime.id].orEmpty().map { it.score }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        }
        AnimeLibrarySort.NEXT_AIRING, AnimeLibrarySort.RANDOM -> byTitle
    }
    val ordered = if (ascending) primary else primary.reversed()
    return filtered.sortedWith(ordered.then(byTitle))
}

/**
 * KMK: Lewd like the manga library's filter: the same adult tags in the genres, or a source with "hentai" in its
 * name (the manga check only knows manga sources by name).
 */
fun Anime.isLewdAnime(): Boolean {
    if (asDisplayManga().isLewd()) return true
    val sourceName = Injekt.get<AnimeSourceManager>().get(source)?.name ?: return false
    return sourceName.contains("hentai", ignoreCase = true)
}

data class AnimeLibraryFilters(
    val downloaded: TriState = TriState.DISABLED,
    val unseen: TriState = TriState.DISABLED,
    val started: TriState = TriState.DISABLED,
    val bookmarked: TriState = TriState.DISABLED,
    val completed: TriState = TriState.DISABLED,
    val tracked: Map<Long, TriState> = emptyMap(),
    // KMK -->
    val trackedAny: TriState = TriState.DISABLED,
    val lewd: TriState = TriState.DISABLED,
    val categories: Boolean = false,
    val includedCategories: Set<Long> = emptySet(),
    val excludedCategories: Set<Long> = emptySet(),
    // KMK <--
) {
    val isActive: Boolean
        get() = listOf(downloaded, unseen, started, bookmarked, completed, trackedAny, lewd)
            .any { it != TriState.DISABLED } ||
            tracked.values.any { it != TriState.DISABLED } ||
            categories

    /** Whether the tracks are needed to filter */
    val usesTracks: Boolean
        get() = trackedAny != TriState.DISABLED || tracked.values.any { it != TriState.DISABLED }

    /** The Categories filter, the way the manga library applies it */
    fun keepCategories(animeCategories: Set<Long>): Boolean {
        if (!categories) return true
        if (animeCategories.isEmpty()) return includedCategories.isEmpty()
        val isExcluded = excludedCategories.any { it in animeCategories }
        val isIncluded = includedCategories.isEmpty() || includedCategories.all { it in animeCategories }
        return !isExcluded && isIncluded
    }
}

/** The filters as they are now, following every change */
@Composable
fun rememberAnimeLibraryFilters(): AnimeLibraryFilters {
    val downloaded by remember { AnimeLibraryPrefs.filterDownloaded() }.collectAsState()
    val unseen by remember { AnimeLibraryPrefs.filterUnseen() }.collectAsState()
    val started by remember { AnimeLibraryPrefs.filterStarted() }.collectAsState()
    val bookmarked by remember { AnimeLibraryPrefs.filterBookmarked() }.collectAsState()
    val completed by remember { AnimeLibraryPrefs.filterCompleted() }.collectAsState()
    val trackers = remember { AnimeTracking.services.filter { it.isLoggedIn } }
    // KMK: one tracker is filtered with "Tracked" alone, like the manga library
    val tracked = trackers.takeIf { it.size > 1 }.orEmpty().associate { service ->
        val state by remember(service.id) { AnimeLibraryPrefs.filterTracked(service.id) }.collectAsState()
        service.id to state
    }
    // KMK -->
    val trackedAnyPref by remember { AnimeLibraryPrefs.filterTrackedAny() }.collectAsState()
    val trackedAny = if (trackers.isEmpty()) TriState.DISABLED else trackedAnyPref
    val lewd by remember { AnimeLibraryPrefs.filterLewd() }.collectAsState()
    val categories by remember { AnimeLibraryPrefs.filterCategories() }.collectAsState()
    val included by remember { AnimeLibraryPrefs.filterCategoriesInclude() }.collectAsState()
    val excluded by remember { AnimeLibraryPrefs.filterCategoriesExclude() }.collectAsState()
    // KMK <--
    return AnimeLibraryFilters(
        downloaded = downloaded,
        unseen = unseen,
        started = started,
        bookmarked = bookmarked,
        completed = completed,
        tracked = tracked,
        trackedAny = trackedAny,
        lewd = lewd,
        categories = categories,
        includedCategories = remember(included) { included.mapNotNull(String::toLongOrNull).toSet() },
        excludedCategories = remember(excluded) { excluded.mapNotNull(String::toLongOrNull).toSet() },
    )
}

@Composable
fun AnimeLibrarySettingsDialog(onDismissRequest: () -> Unit) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
            // KMK: pages by category, source, status or tracking status, like the manga library
            stringResource(SYMR.strings.group),
        ),
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> FilterPage()
                1 -> SortPage()
                2 -> DisplayPage()
                3 -> GroupPage()
            }
        }
    }
}

@Composable
private fun TriStatePref(label: String, pref: Preference<TriState>) {
    val state by remember(pref) { pref }.collectAsState()
    TriStateItem(label = label, state = state, onClick = { pref.set(it) })
}

@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.FilterPage() {
    TriStatePref(stringResource(MR.strings.label_downloaded), AnimeLibraryPrefs.filterDownloaded())
    TriStatePref(stringResource(KMR.strings.anime_filter_unseen), AnimeLibraryPrefs.filterUnseen())
    TriStatePref(stringResource(KMR.strings.anime_library_filter_started), AnimeLibraryPrefs.filterStarted())
    TriStatePref(stringResource(KMR.strings.anime_library_filter_bookmarked), AnimeLibraryPrefs.filterBookmarked())
    TriStatePref(stringResource(KMR.strings.anime_library_filter_completed), AnimeLibraryPrefs.filterCompleted())
    // KMK -->
    TriStatePref(stringResource(SYMR.strings.lewd), AnimeLibraryPrefs.filterLewd())
    CategoriesFilter()
    // KMK <--
    val trackers = remember { AnimeTracking.services.filter { it.isLoggedIn } }
    if (trackers.isNotEmpty()) {
        // KMK: tracked by any tracker, then each tracker when there are several
        TriStatePref(stringResource(MR.strings.action_filter_tracked), AnimeLibraryPrefs.filterTrackedAny())
        if (trackers.size > 1) {
            HeadingItem(KMR.strings.anime_library_filter_tracking)
            trackers.forEach { service ->
                TriStatePref(service.name, AnimeLibraryPrefs.filterTracked(service.id))
            }
        }
    }
}

/** KMK: the Categories filter with its Edit dialog, like the manga library's */
@Composable
private fun CategoriesFilter() {
    val repository = remember { Injekt.get<AnimeRepository>() }
    val categories by remember { repository.subscribeCategories() }.collectAsState(initial = emptyList())
    val enabledPref = remember { AnimeLibraryPrefs.filterCategories() }
    val includePref = remember { AnimeLibraryPrefs.filterCategoriesInclude() }
    val excludePref = remember { AnimeLibraryPrefs.filterCategoriesExclude() }
    val enabled by enabledPref.collectAsState()
    val included by includePref.collectAsState()
    val excluded by excludePref.collectAsState()

    var showDialog by rememberSaveable { mutableStateOf(false) }
    if (showDialog) {
        TriStateListDialog(
            title = stringResource(MR.strings.categories),
            message = stringResource(KMR.strings.pref_library_filter_categories_details),
            items = categories,
            initialChecked = categories.filter { it.id.toString() in included },
            initialInversed = categories.filter { it.id.toString() in excluded },
            itemLabel = { it.name },
            onDismissRequest = { showDialog = false },
            onValueChanged = { newIncluded, newExcluded ->
                includePref.set(newIncluded.map { it.id.toString() }.toSet())
                excludePref.set(newExcluded.map { it.id.toString() }.toSet())
                showDialog = false
            },
        )
    }

    Row(
        modifier = Modifier
            .clickable { enabledPref.set(!enabled) }
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Checkbox(checked = enabled, onCheckedChange = null)
        Text(
            text = stringResource(MR.strings.categories),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { showDialog = true }) {
            Text(stringResource(MR.strings.action_edit))
        }
    }
}

private val sortOptions = listOf(
    MR.strings.action_sort_alpha to AnimeLibrarySort.ALPHABETICAL,
    KMR.strings.anime_library_sort_total to AnimeLibrarySort.TOTAL_EPISODES,
    KMR.strings.anime_library_sort_last_seen to AnimeLibrarySort.LAST_SEEN,
    MR.strings.action_sort_last_manga_update to AnimeLibrarySort.LAST_UPDATE,
    KMR.strings.anime_library_sort_unseen to AnimeLibrarySort.UNSEEN_COUNT,
    KMR.strings.anime_library_sort_latest_episode to AnimeLibrarySort.LATEST_EPISODE,
    KMR.strings.anime_library_sort_episode_fetch to AnimeLibrarySort.EPISODE_FETCH_DATE,
    MR.strings.action_sort_date_added to AnimeLibrarySort.DATE_ADDED,
    MR.strings.action_sort_tracker_score to AnimeLibrarySort.TRACKER_SCORE,
    KMR.strings.anime_library_sort_next_airing to AnimeLibrarySort.NEXT_AIRING,
    MR.strings.action_sort_random to AnimeLibrarySort.RANDOM,
)

@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.SortPage() {
    val sortPref = remember { AnimeLibraryPrefs.sort() }
    val ascendingPref = remember { AnimeLibraryPrefs.sortAscending() }
    val sort by sortPref.collectAsState()
    val ascending by ascendingPref.collectAsState()
    sortOptions.forEach { (label, mode) ->
        if (mode == AnimeLibrarySort.RANDOM) {
            BaseSortItem(
                label = stringResource(label),
                icon = Icons.Default.Refresh.takeIf { sort == AnimeLibrarySort.RANDOM },
                onClick = {
                    AnimeLibraryPrefs.randomSeed().set(System.currentTimeMillis())
                    sortPref.set(mode)
                },
            )
        } else {
            SortItem(
                label = stringResource(label),
                sortDescending = (!ascending).takeIf { sort == mode },
                onClick = {
                    if (sort == mode) ascendingPref.set(!ascending) else sortPref.set(mode)
                },
            )
        }
    }
}

private val displayModes = listOf(
    MR.strings.action_display_grid to LibraryDisplayMode.CompactGrid,
    MR.strings.action_display_comfortable_grid to LibraryDisplayMode.ComfortableGrid,
    MR.strings.action_display_list to LibraryDisplayMode.List,
    MR.strings.action_display_cover_only_grid to LibraryDisplayMode.CoverOnlyGrid,
)

private val panoramaStyles = listOf(
    KMR.strings.panorama_style_fill to PanoramaStyle.FILL,
    KMR.strings.panorama_style_fit to PanoramaStyle.FIT,
    KMR.strings.panorama_style_wide to PanoramaStyle.WIDE,
)

@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.DisplayPage() {
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val displayModePref = remember { libraryPreferences.displayMode() }
    val displayMode by displayModePref.collectAsState()
    val panoramaStylePref = remember { Injekt.get<UiPreferences>().libraryPanoramaStyle() }
    val panoramaStyle by panoramaStylePref.collectAsState()
    SettingsChipRow(MR.strings.action_display_mode) {
        displayModes.map { (titleRes, mode) ->
            FilterChip(
                selected = displayMode == mode,
                onClick = { displayModePref.set(mode) },
                label = { Text(stringResource(titleRes)) },
            )
        }
        panoramaStyles.map { (titleRes, style) ->
            FilterChip(
                selected = displayMode == LibraryDisplayMode.ComfortableGridPanorama && panoramaStyle == style,
                onClick = {
                    panoramaStylePref.set(style)
                    displayModePref.set(LibraryDisplayMode.ComfortableGridPanorama)
                },
                label = { Text(stringResource(titleRes)) },
                leadingIcon = {
                    Icon(
                        imageVector = PanoramaIcons.of(style),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
    if (displayMode != LibraryDisplayMode.List) {
        val configuration = LocalConfiguration.current
        val columnPreference = remember {
            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                libraryPreferences.landscapeColumns()
            } else {
                libraryPreferences.portraitColumns()
            }
        }
        val columns by columnPreference.collectAsState()
        SliderItem(
            value = columns,
            valueRange = 0..15,
            label = stringResource(MR.strings.pref_library_columns),
            valueString = if (columns > 0) columns.toString() else stringResource(MR.strings.label_auto),
            onChange = columnPreference::set,
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
    // KMK: the same badges as the manga library (no Local source badge: anime have no local source)
    HeadingItem(MR.strings.overlay_header)
    CheckboxItem(
        label = stringResource(KMR.strings.anime_library_badge_downloaded),
        pref = AnimeLibraryPrefs.downloadBadge(),
    )
    CheckboxItem(
        label = stringResource(KMR.strings.anime_library_badge_unseen),
        pref = AnimeLibraryPrefs.unseenBadge(),
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_language_badge),
        pref = AnimeLibraryPrefs.languageBadge(),
    )
    val showLang by remember { AnimeLibraryPrefs.languageBadge() }.collectAsState()
    if (showLang) {
        CheckboxItem(
            label = stringResource(KMR.strings.action_display_language_icon),
            pref = AnimeLibraryPrefs.useLangIcon(),
        )
    }
    CheckboxItem(
        label = stringResource(KMR.strings.action_display_source_badge),
        pref = AnimeLibraryPrefs.sourceBadge(),
    )
    CheckboxItem(
        label = stringResource(KMR.strings.anime_library_continue_button),
        pref = AnimeLibraryPrefs.continueButton(),
    )
    HeadingItem(MR.strings.tabs_header)
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_tabs),
        pref = libraryPreferences.categoryTabs(),
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_number_of_items),
        pref = libraryPreferences.categoryNumberOfItems(),
    )
}

// KMK -->
private fun groupIcon(type: Int): Int = when (type) {
    LibraryGroup.BY_STATUS -> R.drawable.ic_progress_clock_24dp
    LibraryGroup.BY_TRACK_STATUS -> R.drawable.ic_sync_24dp
    LibraryGroup.BY_SOURCE -> R.drawable.ic_browse_filled_24dp
    LibraryGroup.UNGROUPED -> R.drawable.ic_ungroup_24dp
    else -> R.drawable.ic_label_24dp
}

@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.GroupPage() {
    val groupPref = remember { AnimeLibraryPrefs.groupType() }
    val grouping by groupPref.collectAsState()
    val hasTrackers = remember { AnimeTracking.services.any { it.isLoggedIn } }
    val hasCategories = rememberHasAnimeCategories()
    val groups = buildList {
        add(LibraryGroup.BY_DEFAULT)
        add(LibraryGroup.BY_SOURCE)
        add(LibraryGroup.BY_STATUS)
        if (hasTrackers) add(LibraryGroup.BY_TRACK_STATUS)
        if (hasCategories || grouping == LibraryGroup.UNGROUPED) add(LibraryGroup.UNGROUPED)
    }
    groups.forEach { type ->
        IconItem(
            label = stringResource(LibraryGroup.groupTypeStringRes(type)),
            icon = painterResource(groupIcon(type)),
            selected = grouping == type,
            onClick = { groupPref.set(type) },
        )
    }
}
// KMK <--
