package eu.kanade.presentation.more.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.components.MediaTab
import eu.kanade.presentation.more.stats.components.StatsItem
import eu.kanade.presentation.more.stats.components.StatsOverviewItem
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.presentation.util.toDurationString
import eu.kanade.tachiyomi.ui.stats.AnimeStatsData
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Composable
fun StatsScreenContent(
    state: StatsScreenState.Success?,
    paddingValues: PaddingValues,
    // KMK -->
    animeStats: AnimeStatsData? = null,
    mediaTab: Int = MediaTab.ANIME,
    // KMK <--
) {
    LazyColumn(
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        // KMK
        if (state != null && MediaTab.showsManga(mediaTab)) {
            item {
                OverviewSection(state.overview)
            }
            item {
                TitlesStats(state.titles)
            }
            item {
                ChapterStats(state.chapters)
            }
            item {
                TrackerStats(state.trackers)
            }
        }
        // KMK -->
        if (MediaTab.showsAnime(mediaTab)) {
            if (animeStats == null) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.padding.large),
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item { AnimeOverviewSection(animeStats) }
                item { AnimeTitlesStats(animeStats) }
                item { AnimeEpisodeStats(animeStats) }
                item { AnimeTrackerStats(animeStats) }
            }
        }
        // KMK <--
    }
}

// KMK --> The Anime tab, laid out and named like the manga one (and Aniyomi's)
@Composable
private fun LazyItemScope.AnimeOverviewSection(data: AnimeStatsData) {
    val none = stringResource(MR.strings.none)
    val context = LocalContext.current
    val watchTime = remember(data.totalWatchTime) {
        data.totalWatchTime
            .toDuration(DurationUnit.MILLISECONDS)
            .toDurationString(context, fallback = none)
    }
    SectionCard(MR.strings.label_overview_section) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            StatsOverviewItem(
                title = data.libraryCount.toString(),
                subtitle = stringResource(MR.strings.in_library),
                icon = Icons.Outlined.CollectionsBookmark,
            )
            StatsOverviewItem(
                title = watchTime,
                subtitle = stringResource(KMR.strings.anime_stats_watched_duration),
                icon = Icons.Outlined.Schedule,
            )
            StatsOverviewItem(
                title = data.completedCount.toString(),
                subtitle = stringResource(MR.strings.label_completed_titles),
                icon = Icons.Outlined.LocalLibrary,
            )
        }
    }
}

@Composable
private fun LazyItemScope.AnimeTitlesStats(data: AnimeStatsData) {
    SectionCard(MR.strings.label_titles_section) {
        Row {
            StatsItem(
                data.globalUpdateCount.toString(),
                stringResource(MR.strings.label_titles_in_global_update),
            )
            StatsItem(
                data.startedCount.toString(),
                stringResource(MR.strings.label_started),
            )
            StatsItem(
                data.localCount.toString(),
                stringResource(MR.strings.label_local),
            )
        }
    }
}

@Composable
private fun LazyItemScope.AnimeEpisodeStats(data: AnimeStatsData) {
    SectionCard(KMR.strings.anime_stats_episodes) {
        Row {
            StatsItem(
                data.totalEpisodes.toString(),
                stringResource(MR.strings.label_total_chapters),
            )
            StatsItem(
                data.seenEpisodes.toString(),
                stringResource(KMR.strings.anime_stats_watched),
            )
            StatsItem(
                data.downloadedEpisodes.toString(),
                stringResource(MR.strings.label_downloaded),
            )
        }
    }
}

@Composable
private fun LazyItemScope.AnimeTrackerStats(data: AnimeStatsData) {
    val notApplicable = stringResource(MR.strings.not_applicable)
    val meanScoreStr = remember(data.trackedCount, data.meanScore) {
        if (data.trackedCount > 0 && !data.meanScore.isNaN()) {
            "%.2f ★".format(Locale.ENGLISH, data.meanScore)
        } else {
            notApplicable
        }
    }
    SectionCard(MR.strings.label_tracker_section) {
        Row {
            StatsItem(
                data.trackedCount.toString(),
                stringResource(MR.strings.label_tracked_titles),
            )
            StatsItem(
                meanScoreStr,
                stringResource(MR.strings.label_mean_score),
            )
            StatsItem(
                data.trackerCount.toString(),
                stringResource(MR.strings.label_used),
            )
        }
    }
}
// KMK <--

@Composable
private fun LazyItemScope.OverviewSection(
    data: StatsData.Overview,
) {
    val none = stringResource(MR.strings.none)
    val context = LocalContext.current
    val readDurationString = remember(data.totalReadDuration) {
        data.totalReadDuration
            .toDuration(DurationUnit.MILLISECONDS)
            .toDurationString(context, fallback = none)
    }
    SectionCard(MR.strings.label_overview_section) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            StatsOverviewItem(
                title = data.libraryMangaCount.toString(),
                subtitle = stringResource(MR.strings.in_library),
                icon = Icons.Outlined.CollectionsBookmark,
            )
            StatsOverviewItem(
                title = readDurationString,
                subtitle = stringResource(MR.strings.label_read_duration),
                icon = Icons.Outlined.Schedule,
            )
            StatsOverviewItem(
                title = data.completedMangaCount.toString(),
                subtitle = stringResource(MR.strings.label_completed_titles),
                icon = Icons.Outlined.LocalLibrary,
            )
        }
    }
}

@Composable
private fun LazyItemScope.TitlesStats(
    data: StatsData.Titles,
) {
    SectionCard(MR.strings.label_titles_section) {
        Row {
            StatsItem(
                data.globalUpdateItemCount.toString(),
                stringResource(MR.strings.label_titles_in_global_update),
            )
            StatsItem(
                data.startedMangaCount.toString(),
                stringResource(MR.strings.label_started),
            )
            StatsItem(
                data.localMangaCount.toString(),
                stringResource(MR.strings.label_local),
            )
        }
    }
}

@Composable
private fun LazyItemScope.ChapterStats(
    data: StatsData.Chapters,
) {
    SectionCard(MR.strings.chapters) {
        Row {
            StatsItem(
                data.totalChapterCount.toString(),
                stringResource(MR.strings.label_total_chapters),
            )
            StatsItem(
                data.readChapterCount.toString(),
                stringResource(MR.strings.label_read_chapters),
            )
            StatsItem(
                data.downloadCount.toString(),
                stringResource(MR.strings.label_downloaded),
            )
        }
    }
}

@Composable
private fun LazyItemScope.TrackerStats(
    data: StatsData.Trackers,
) {
    val notApplicable = stringResource(MR.strings.not_applicable)
    val meanScoreStr = remember(data.trackedTitleCount, data.meanScore) {
        if (data.trackedTitleCount > 0 && !data.meanScore.isNaN()) {
            // All other numbers are localized in English
            "%.2f ★".format(Locale.ENGLISH, data.meanScore)
        } else {
            notApplicable
        }
    }
    SectionCard(MR.strings.label_tracker_section) {
        Row {
            StatsItem(
                data.trackedTitleCount.toString(),
                stringResource(MR.strings.label_tracked_titles),
            )
            StatsItem(
                meanScoreStr,
                stringResource(MR.strings.label_mean_score),
            )
            StatsItem(
                data.trackerCount.toString(),
                stringResource(MR.strings.label_used),
            )
        }
    }
}
