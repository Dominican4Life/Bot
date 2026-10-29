package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import eu.kanade.tachiyomi.data.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.anime.AnimePrefs
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadJob
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.core.common.preference.Preference as PreferenceData
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: the "Anime" groups of Settings › Library (library update) and Settings › Downloads. The values live in
 * [AnimePrefs].
 */

/** Anime categories as [Category] rows, with the "Default" row (anime in no category) first */
@Composable
private fun rememberAnimeCategories(): List<Category> {
    val repository = remember { Injekt.get<AnimeRepository>() }
    val flow = remember { repository.subscribeCategories() }
    val categories by flow.collectAsState(initial = emptyList())
    return remember(categories) {
        listOf(Category(Category.UNCATEGORIZED_ID, "", -1, 0, false)) +
            categories.map { Category(it.id, it.name, it.order, 0, false) }
    }
}

/** The include / exclude categories dialog and its settings row */
@Composable
private fun animeCategoriesItem(
    allCategories: List<Category>,
    includedPref: PreferenceData<Set<String>>,
    excludedPref: PreferenceData<Set<String>>,
    message: String,
    enabled: Boolean = true,
): Preference.PreferenceItem.TextPreference {
    val included by includedPref.collectAsState()
    val excluded by excludedPref.collectAsState()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    if (showDialog) {
        TriStateListDialog(
            title = stringResource(KMR.strings.pref_anime_categories),
            message = message,
            items = allCategories,
            initialChecked = included.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
            initialInversed = excluded.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
            itemLabel = { it.visualName },
            onDismissRequest = { showDialog = false },
            onValueChanged = { newIncluded, newExcluded ->
                includedPref.set(newIncluded.map { it.id.toString() }.toSet())
                excludedPref.set(newExcluded.map { it.id.toString() }.toSet())
                showDialog = false
            },
        )
    }
    return Preference.PreferenceItem.TextPreference(
        title = stringResource(KMR.strings.pref_anime_categories),
        subtitle = getCategoriesLabel(allCategories = allCategories, included = included, excluded = excluded),
        enabled = enabled,
        onClick = { showDialog = true },
    )
}

/** Settings › Library: when and which anime the library update checks */
@Composable
fun getAnimeLibraryUpdateGroup(): Preference.PreferenceGroup {
    val context = LocalContext.current
    val allCategories = rememberAnimeCategories()
    val intervalPref = remember { AnimePrefs.updateInterval() }

    return Preference.PreferenceGroup(
        title = stringResource(KMR.strings.pref_anime_group),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.ListPreference(
                preference = intervalPref,
                entries = persistentMapOf(
                    AnimePrefs.UPDATE_WITH_MANGA to stringResource(KMR.strings.anime_update_with_manga),
                    0 to stringResource(MR.strings.update_never),
                    12 to stringResource(MR.strings.update_12hour),
                    24 to stringResource(MR.strings.update_24hour),
                    48 to stringResource(MR.strings.update_48hour),
                    72 to stringResource(MR.strings.update_72hour),
                    168 to stringResource(MR.strings.update_weekly),
                ),
                title = stringResource(KMR.strings.pref_anime_update_interval),
                onValueChanged = {
                    AnimeLibraryUpdateJob.setupTask(context, it)
                    true
                },
            ),
            animeCategoriesItem(
                allCategories = allCategories,
                includedPref = remember { AnimePrefs.updateCategories() },
                excludedPref = remember { AnimePrefs.updateCategoriesExclude() },
                message = stringResource(KMR.strings.pref_anime_update_categories_details),
            ),
            Preference.PreferenceItem.MultiSelectListPreference(
                preference = remember { AnimePrefs.updateSkip() },
                entries = persistentMapOf(
                    AnimePrefs.SKIP_COMPLETED to stringResource(KMR.strings.anime_update_skip_completed),
                    AnimePrefs.SKIP_HAS_UNSEEN to stringResource(KMR.strings.anime_update_skip_has_unseen),
                    AnimePrefs.SKIP_NOT_STARTED to stringResource(KMR.strings.anime_update_skip_not_started),
                    AnimePrefs.SKIP_SMART to stringResource(KMR.strings.anime_update_skip_smart),
                ),
                title = stringResource(KMR.strings.pref_anime_update_skip),
            ),
            Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.anime_update_schedule_info)),
        ),
    )
}

/** Settings › Downloads: anime episode downloads */
@Composable
fun getAnimeDownloadGroup(): Preference.PreferenceGroup {
    val context = LocalContext.current
    val allCategories = rememberAnimeCategories()
    val concurrentPref = remember { AnimePrefs.concurrentDownloads() }
    val concurrent by concurrentPref.collectAsState()
    // KMK (r87): parallel pieces per download
    val connectionsPref = remember { AnimePrefs.videoDownloadConnections() }
    val connections by connectionsPref.collectAsState()
    val deleteAfterSeenPref = remember { AnimePrefs.deleteAfterSeen() }
    val deleteAfterSeen by deleteAfterSeenPref.collectAsState()
    val downloadNewPref = remember { AnimePrefs.downloadNewEpisodes() }
    val downloadNew by downloadNewPref.collectAsState()

    return Preference.PreferenceGroup(
        title = stringResource(KMR.strings.pref_anime_group),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = remember { AnimePrefs.downloadOnlyOverWifi() },
                title = stringResource(KMR.strings.pref_anime_download_wifi),
                subtitle = stringResource(KMR.strings.pref_anime_download_wifi_summary),
                onValueChanged = {
                    // Posted so the new value is saved first; restarts the queue with the new network rule
                    ContextCompat.getMainExecutor(context).execute {
                        if (Injekt.get<AnimeDownloadManager>().queue.value.isNotEmpty()) {
                            AnimeDownloadJob.restart(context)
                        }
                    }
                    true
                },
            ),
            Preference.PreferenceItem.SliderPreference(
                value = concurrent,
                valueRange = 1..AnimeDownloadManager.MAX_CONCURRENT,
                title = stringResource(KMR.strings.pref_anime_download_concurrent),
                onValueChanged = { concurrentPref.set(it) },
            ),
            Preference.PreferenceItem.SliderPreference(
                value = connections,
                valueRange = 1..eu.kanade.tachiyomi.data.download.video.ParallelVideoDownload.MAX_CONNECTIONS,
                title = stringResource(KMR.strings.pref_video_download_connections),
                subtitle = stringResource(KMR.strings.pref_video_download_connections_summary),
                onValueChanged = { connectionsPref.set(it) },
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = deleteAfterSeenPref,
                title = stringResource(KMR.strings.pref_anime_delete_after_seen),
                subtitle = stringResource(KMR.strings.pref_anime_delete_after_seen_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = remember { AnimePrefs.deleteKeepBookmarked() },
                title = stringResource(KMR.strings.pref_anime_delete_keep_bookmarked),
                enabled = deleteAfterSeen,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = remember { AnimePrefs.downloadAhead() },
                entries = listOf(0, 2, 3, 5, 10)
                    .associateWith {
                        if (it == 0) {
                            stringResource(MR.strings.disabled)
                        } else {
                            stringResource(KMR.strings.anime_download_ahead_next, it)
                        }
                    }
                    .toImmutableMap(),
                title = stringResource(KMR.strings.pref_anime_download_ahead),
            ),
            Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.pref_anime_download_ahead_info)),
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadNewPref,
                title = stringResource(KMR.strings.pref_anime_download_new),
            ),
            animeCategoriesItem(
                allCategories = allCategories,
                includedPref = remember { AnimePrefs.downloadNewCategories() },
                excludedPref = remember { AnimePrefs.downloadNewCategoriesExclude() },
                message = stringResource(KMR.strings.pref_anime_download_new_categories_details),
                enabled = downloadNew,
            ),
        ),
    )
}
