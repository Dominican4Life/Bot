package eu.kanade.presentation.more

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.LiveIcon
import eu.kanade.presentation.components.LiveIconKind
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r114): More, revamped. Every row has a live icon that plays its move once on tap. What each row opens is
 * unchanged.
 * KMK (r115): C3 "soft groups" under the logo: (1) Downloaded only + Incognito switch rows, (2) Your library,
 * (3) App — rounded groups with thin lines between rows. ≥ 600 dp the page fills the width (see [MoreList]).
 */
@Composable
fun MoreScreen(
    downloadQueueStateProvider: () -> DownloadQueueState,
    downloadedOnly: Boolean,
    onDownloadedOnlyChange: (Boolean) -> Unit,
    incognitoMode: Boolean,
    onIncognitoModeChange: (Boolean) -> Unit,
    // SY -->
    showNavUpdates: Boolean,
    showNavHistory: Boolean,
    // SY <--
    onClickDownloadQueue: () -> Unit,
    onClickCategories: () -> Unit,
    onClickStats: () -> Unit,
    onClickDataAndStorage: () -> Unit,
    onClickSettings: () -> Unit,
    // KMK -->
    onClickPlayerSettings: () -> Unit,
    // KMK <--
    onClickAbout: () -> Unit,
    onClickBatchAdd: () -> Unit,
    onClickUpdates: () -> Unit,
    onClickHistory: () -> Unit,
    // KMK -->
    onClickLibraryUpdateErrors: () -> Unit,
    /** The in-app Logs (the tablet rail has its own button; phones had no way in) */
    onClickLogs: () -> Unit = {},
    /** KMK (r89): the Sync devices pop-up (the tablet rail has its own button; phones had no way in) */
    onClickPairSync: () -> Unit = {},
    /** KMK (r114): Source Lab (moved here from the Browse › Sources toolbar) */
    onClickSourceLab: () -> Unit = {},
    /** KMK (r114): shown at the end of the Categories row when above 0 */
    categoryCount: Int = 0,
    /** KMK (r114): red badge on the Update errors row when above 0 */
    updateErrorCount: Int = 0,
    // KMK <--
) {
    // SY -->
    val exhPreferences = remember { Injekt.get<ExhPreferences>() }
    val delegateSourcePreferences = remember { Injekt.get<DelegateSourcePreferences>() }
    // SY <--
    // KMK: one queue for manga and anime
    val animeManager = remember { Injekt.get<eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager>() }
    val animeQueue by animeManager.queue.collectAsState()

    // KMK (r114): each icon's play counter; a tap bumps it and the icon makes its move once
    val plays = remember { mutableStateMapOf<LiveIconKind, Int>() }
    val tap: (LiveIconKind, () -> Unit) -> Unit = { kind, action ->
        plays[kind] = (plays[kind] ?: 0) + 1
        action()
    }

    val downloadedOnlyIcon = rememberAnimatedVectorPainter(
        AnimatedImageVector.animatedVectorResource(R.drawable.anim_downloaded_only),
        downloadedOnly,
    )
    val incognitoIcon = rememberAnimatedVectorPainter(
        AnimatedImageVector.animatedVectorResource(R.drawable.anim_incognito),
        incognitoMode,
    )

    // SY: read on each recomposition, as before
    val showBatchAdd = exhPreferences.isHentaiEnabled().get() || delegateSourcePreferences.delegateSources().get()

    val downloadQueueState = downloadQueueStateProvider()
    val mangaSummary = when (downloadQueueState) {
        DownloadQueueState.Stopped -> null
        is DownloadQueueState.Paused -> {
            val pending = downloadQueueState.pending
            if (pending == 0) {
                stringResource(MR.strings.paused)
            } else {
                "${stringResource(MR.strings.paused)} • ${
                    pluralStringResource(
                        MR.plurals.download_queue_summary,
                        count = pending,
                        pending,
                    )
                }"
            }
        }
        is DownloadQueueState.Downloading -> {
            val pending = downloadQueueState.pending
            pluralStringResource(MR.plurals.download_queue_summary, count = pending, pending)
        }
    }
    val animeSummary = animeQueue.size.takeIf { it > 0 }?.let {
        stringResource(KMR.strings.download_queue_anime_count, it)
    }
    val queueSummary = listOfNotNull(mangaSummary, animeSummary).joinToString(" • ").ifEmpty { null }
    val downloading = downloadQueueState is DownloadQueueState.Downloading

    // KMK (r115): Source Lab is tablet-only now, so its row shows only on screens at least 600 dp wide
    // KMK (r115): Source Lab works on phones too (its own phone layout)
    val showSourceLab = true

    // KMK (r115): each group is a list of rows; MoreGroup puts the thin lines between them, so a row that is left out
    // (Recent updates / History in the nav, Batch add off, Source Lab on a phone) never leaves a double line
    val switchRows: List<@Composable (Boolean) -> Unit> = buildList<@Composable (Boolean) -> Unit> {
        row { tall ->
            MoreSwitchRow(
                icon = downloadedOnlyIcon,
                title = stringResource(MR.strings.label_downloaded_only),
                subtitle = stringResource(MR.strings.downloaded_only_summary),
                checked = downloadedOnly,
                onCheckedChange = onDownloadedOnlyChange,
                tall = tall,
            )
        }
        row { tall ->
            MoreSwitchRow(
                icon = incognitoIcon,
                title = stringResource(MR.strings.pref_incognito_mode),
                subtitle = stringResource(MR.strings.pref_incognito_mode_summary),
                checked = incognitoMode,
                onCheckedChange = onIncognitoModeChange,
                tall = tall,
            )
        }
    }

    val libraryRows: List<@Composable (Boolean) -> Unit> = buildList<@Composable (Boolean) -> Unit> {
        // SY -->
        if (!showNavUpdates) {
            row { tall ->
                MoreRow(
                    kind = LiveIconKind.Updates,
                    play = plays[LiveIconKind.Updates] ?: 0,
                    title = stringResource(MR.strings.label_recent_updates),
                    onClick = { tap(LiveIconKind.Updates, onClickUpdates) },
                    tall = tall,
                )
            }
        }
        if (!showNavHistory) {
            row { tall ->
                MoreRow(
                    kind = LiveIconKind.History,
                    play = plays[LiveIconKind.History] ?: 0,
                    title = stringResource(MR.strings.label_recent_manga),
                    onClick = { tap(LiveIconKind.History, onClickHistory) },
                    tall = tall,
                )
            }
        }
        // SY <--
        row { tall ->
            MoreRow(
                kind = LiveIconKind.Queue,
                play = plays[LiveIconKind.Queue] ?: 0,
                title = stringResource(MR.strings.label_download_queue),
                subtitle = queueSummary,
                onClick = { tap(LiveIconKind.Queue, onClickDownloadQueue) },
                tall = tall,
                trailing = if (downloading) {
                    { LinearProgressIndicator(modifier = Modifier.width(if (tall) 90.dp else 56.dp)) }
                } else {
                    null
                },
            )
        }
        row { tall ->
            MoreRow(
                kind = LiveIconKind.Categories,
                play = plays[LiveIconKind.Categories] ?: 0,
                title = stringResource(MR.strings.categories),
                onClick = { tap(LiveIconKind.Categories, onClickCategories) },
                tall = tall,
                trailing = if (categoryCount > 0) {
                    { TrailingText(categoryCount.toString()) }
                } else {
                    null
                },
            )
        }
        row { tall ->
            MoreRow(
                kind = LiveIconKind.Statistics,
                play = plays[LiveIconKind.Statistics] ?: 0,
                title = stringResource(MR.strings.label_stats),
                onClick = { tap(LiveIconKind.Statistics, onClickStats) },
                tall = tall,
            )
        }
        // KMK -->
        row { tall ->
            MoreRow(
                kind = LiveIconKind.UpdateErrors,
                play = plays[LiveIconKind.UpdateErrors] ?: 0,
                title = stringResource(KMR.strings.option_label_library_update_errors),
                onClick = { tap(LiveIconKind.UpdateErrors, onClickLibraryUpdateErrors) },
                tall = tall,
                trailing = if (updateErrorCount > 0) {
                    {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ) {
                            Text(text = updateErrorCount.toString(), fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    null
                },
            )
        }
        // KMK <--
        row { tall ->
            MoreRow(
                kind = LiveIconKind.Database,
                play = plays[LiveIconKind.Database] ?: 0,
                title = stringResource(MR.strings.label_data_storage),
                subtitle = stringResource(MR.strings.pref_backup_summary),
                onClick = { tap(LiveIconKind.Database, onClickDataAndStorage) },
                tall = tall,
            )
        }
        // SY -->
        if (showBatchAdd) {
            row { tall ->
                MoreRow(
                    kind = LiveIconKind.BatchAdd,
                    play = plays[LiveIconKind.BatchAdd] ?: 0,
                    title = stringResource(SYMR.strings.eh_batch_add),
                    onClick = { tap(LiveIconKind.BatchAdd, onClickBatchAdd) },
                    tall = tall,
                )
            }
        }
        // SY <--
    }

    val appRows: List<@Composable (Boolean) -> Unit> = buildList<@Composable (Boolean) -> Unit> {
        row { tall ->
            MoreRow(
                kind = LiveIconKind.Settings,
                play = plays[LiveIconKind.Settings] ?: 0,
                title = stringResource(MR.strings.label_settings),
                onClick = { tap(LiveIconKind.Settings, onClickSettings) },
                tall = tall,
            )
        }
        // KMK --> Aniyomi's place for the player settings
        row { tall ->
            MoreRow(
                kind = LiveIconKind.PlayerSettings,
                play = plays[LiveIconKind.PlayerSettings] ?: 0,
                title = stringResource(AMR.strings.label_player_settings),
                onClick = { tap(LiveIconKind.PlayerSettings, onClickPlayerSettings) },
                tall = tall,
            )
        }
        // KMK (r115): on every screen size
        if (showSourceLab) {
            row { tall ->
                MoreRow(
                    kind = LiveIconKind.SourceLab,
                    play = plays[LiveIconKind.SourceLab] ?: 0,
                    title = "Source Lab",
                    onClick = { tap(LiveIconKind.SourceLab, onClickSourceLab) },
                    tall = tall,
                )
            }
        }
        row { tall ->
            MoreRow(
                kind = LiveIconKind.SyncDevices,
                play = plays[LiveIconKind.SyncDevices] ?: 0,
                title = "Sync devices",
                onClick = { tap(LiveIconKind.SyncDevices, onClickPairSync) },
                tall = tall,
            )
        }
        row { tall ->
            MoreRow(
                kind = LiveIconKind.Logs,
                play = plays[LiveIconKind.Logs] ?: 0,
                title = "Logs",
                onClick = { tap(LiveIconKind.Logs, onClickLogs) },
                tall = tall,
            )
        }
        // KMK <--
        row { tall ->
            MoreRow(
                kind = LiveIconKind.About,
                play = plays[LiveIconKind.About] ?: 0,
                title = stringResource(MR.strings.pref_category_about),
                subtitle = AboutScreen.getVersionName(withBuildDate = false),
                onClick = { tap(LiveIconKind.About, onClickAbout) },
                tall = tall,
            )
        }
        // KMK: Help entry and Sponsor button removed
    }

    Scaffold { contentPadding ->
        // KMK (r115): C3 "soft groups". Phone: one column of three rounded groups. ≥ 600 dp: fill the width — the two
        // switches side by side, then Your library and App side by side, each stretching, with taller rows.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            MoreList(
                wide = wide,
                contentPadding = contentPadding,
                switchRows = switchRows,
                libraryRows = libraryRows,
                appRows = appRows,
            )
        }
    }
}

/** KMK (r115): adds one row to a group's list (a plain @Composable parameter, so the lambda is always composable) */
private fun MutableList<@Composable (Boolean) -> Unit>.row(content: @Composable (Boolean) -> Unit) {
    add(content)
}

/** KMK (r115): the scrolling list under the logo, phone (one column) or wide (fills the width) */
@Composable
private fun MoreList(
    wide: Boolean,
    contentPadding: PaddingValues,
    switchRows: List<@Composable (Boolean) -> Unit>,
    libraryRows: List<@Composable (Boolean) -> Unit>,
    appRows: List<@Composable (Boolean) -> Unit>,
) {
    val gutter = if (wide) 28.dp else 16.dp
    val gap = if (wide) 14.dp else 10.dp
    ScrollbarLazyColumn(
        // KMK: use contentPadding as preferable padding for ScrollbarLazyColumn when not using stickyHeader
        contentPadding = contentPadding,
    ) {
        item {
            LogoHeader()
        }
        if (wide) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = gutter, end = gutter, top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    switchRows.forEach { row ->
                        MoreGroup(rows = listOf(row), tall = true, modifier = Modifier.weight(1f))
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = gutter, end = gutter, top = 16.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        GroupHeading("Your library")
                        MoreGroup(rows = libraryRows, tall = true, modifier = Modifier.fillMaxWidth())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        GroupHeading("App")
                        MoreGroup(rows = appRows, tall = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = gutter, end = gutter, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    MoreGroup(rows = switchRows, tall = false, modifier = Modifier.fillMaxWidth())
                    MoreGroup(rows = libraryRows, tall = false, modifier = Modifier.fillMaxWidth())
                    MoreGroup(rows = appRows, tall = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** KMK (r115): small primary caps heading above a wide-screen group */
@Composable
private fun GroupHeading(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
    )
}

/** KMK (r115): a soft rounded group (18 dp corners) with thin lines between its rows */
@Composable
private fun MoreGroup(
    rows: List<@Composable (Boolean) -> Unit>,
    tall: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
                row(tall)
            }
        }
    }
}

/** KMK (r114): one More row: live icon, title, optional subtitle, optional trailing part */
@Composable
private fun MoreRow(
    kind: LiveIconKind,
    play: Int,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    // KMK (r115): wide screens: taller rows (min 56 dp) with more room
    tall: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (tall) 56.dp else 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = if (tall) 20.dp else 14.dp, vertical = if (tall) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (tall) 22.dp else 20.dp),
    ) {
        LiveIcon(kind = kind, play = play)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun TrailingText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** KMK (r115): Downloaded only / Incognito as a switch row inside a group (the whole row toggles) */
@Composable
private fun MoreSwitchRow(
    icon: Painter,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tall: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (tall) 56.dp else 52.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = if (tall) 20.dp else 14.dp, vertical = if (tall) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (tall) 22.dp else 20.dp),
    ) {
        Icon(painter = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
