package eu.kanade.presentation.more

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
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
 * KMK (r114): More, revamped. Under the logo: two switch cards (Downloaded only, Incognito), then a "Your library"
 * card and an "App" card (side by side on wide screens). Every row has a live icon that plays its move once on tap.
 * What each row opens is unchanged.
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

    val libraryCard: @Composable (Modifier) -> Unit = { cardModifier ->
        MoreCard(heading = "Your library", modifier = cardModifier) {
            // SY -->
            if (!showNavUpdates) {
                MoreRow(
                    kind = LiveIconKind.Updates,
                    play = plays[LiveIconKind.Updates] ?: 0,
                    title = stringResource(MR.strings.label_recent_updates),
                    onClick = { tap(LiveIconKind.Updates, onClickUpdates) },
                )
            }
            if (!showNavHistory) {
                MoreRow(
                    kind = LiveIconKind.History,
                    play = plays[LiveIconKind.History] ?: 0,
                    title = stringResource(MR.strings.label_recent_manga),
                    onClick = { tap(LiveIconKind.History, onClickHistory) },
                )
            }
            // SY <--
            MoreRow(
                kind = LiveIconKind.Queue,
                play = plays[LiveIconKind.Queue] ?: 0,
                title = stringResource(MR.strings.label_download_queue),
                subtitle = queueSummary,
                onClick = { tap(LiveIconKind.Queue, onClickDownloadQueue) },
                trailing = if (downloading) {
                    { LinearProgressIndicator(modifier = Modifier.width(64.dp)) }
                } else {
                    null
                },
            )
            MoreRow(
                kind = LiveIconKind.Categories,
                play = plays[LiveIconKind.Categories] ?: 0,
                title = stringResource(MR.strings.categories),
                onClick = { tap(LiveIconKind.Categories, onClickCategories) },
                trailing = if (categoryCount > 0) {
                    { TrailingText(categoryCount.toString()) }
                } else {
                    null
                },
            )
            MoreRow(
                kind = LiveIconKind.Statistics,
                play = plays[LiveIconKind.Statistics] ?: 0,
                title = stringResource(MR.strings.label_stats),
                onClick = { tap(LiveIconKind.Statistics, onClickStats) },
            )
            // KMK -->
            MoreRow(
                kind = LiveIconKind.UpdateErrors,
                play = plays[LiveIconKind.UpdateErrors] ?: 0,
                title = stringResource(KMR.strings.option_label_library_update_errors),
                onClick = { tap(LiveIconKind.UpdateErrors, onClickLibraryUpdateErrors) },
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
            // KMK <--
            MoreRow(
                kind = LiveIconKind.Database,
                play = plays[LiveIconKind.Database] ?: 0,
                title = stringResource(MR.strings.label_data_storage),
                subtitle = stringResource(MR.strings.pref_backup_summary),
                onClick = { tap(LiveIconKind.Database, onClickDataAndStorage) },
            )
            // SY -->
            if (showBatchAdd) {
                MoreRow(
                    kind = LiveIconKind.BatchAdd,
                    play = plays[LiveIconKind.BatchAdd] ?: 0,
                    title = stringResource(SYMR.strings.eh_batch_add),
                    onClick = { tap(LiveIconKind.BatchAdd, onClickBatchAdd) },
                )
            }
            // SY <--
        }
    }

    val appCard: @Composable (Modifier) -> Unit = { cardModifier ->
        MoreCard(heading = "App", modifier = cardModifier) {
            MoreRow(
                kind = LiveIconKind.Settings,
                play = plays[LiveIconKind.Settings] ?: 0,
                title = stringResource(MR.strings.label_settings),
                onClick = { tap(LiveIconKind.Settings, onClickSettings) },
            )
            // KMK --> Aniyomi's place for the player settings
            MoreRow(
                kind = LiveIconKind.PlayerSettings,
                play = plays[LiveIconKind.PlayerSettings] ?: 0,
                title = stringResource(AMR.strings.label_player_settings),
                onClick = { tap(LiveIconKind.PlayerSettings, onClickPlayerSettings) },
            )
            MoreRow(
                kind = LiveIconKind.SourceLab,
                play = plays[LiveIconKind.SourceLab] ?: 0,
                title = "Source Lab",
                subtitle = "Build and test sources",
                onClick = { tap(LiveIconKind.SourceLab, onClickSourceLab) },
            )
            MoreRow(
                kind = LiveIconKind.SyncDevices,
                play = plays[LiveIconKind.SyncDevices] ?: 0,
                title = "Sync devices",
                onClick = { tap(LiveIconKind.SyncDevices, onClickPairSync) },
            )
            MoreRow(
                kind = LiveIconKind.Logs,
                play = plays[LiveIconKind.Logs] ?: 0,
                title = "Logs",
                onClick = { tap(LiveIconKind.Logs, onClickLogs) },
            )
            // KMK <--
            MoreRow(
                kind = LiveIconKind.About,
                play = plays[LiveIconKind.About] ?: 0,
                title = stringResource(MR.strings.pref_category_about),
                subtitle = AboutScreen.getVersionName(withBuildDate = false),
                onClick = { tap(LiveIconKind.About, onClickAbout) },
            )
            // KMK: Help entry and Sponsor button removed
        }
    }

    Scaffold { contentPadding ->
        ScrollbarLazyColumn(
            // KMK: use contentPadding as preferable padding for ScrollbarLazyColumn when not using stickyHeader
            contentPadding = contentPadding,
        ) {
            item {
                LogoHeader()
            }
            item {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    // ≥ 600 dp: icon, words and switch in one line; phones: icon + switch on top, words under
                    val wide = maxWidth >= 600.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SwitchCard(
                            modifier = Modifier.weight(1f),
                            icon = downloadedOnlyIcon,
                            title = stringResource(MR.strings.label_downloaded_only),
                            subtitle = stringResource(MR.strings.downloaded_only_summary),
                            checked = downloadedOnly,
                            onCheckedChange = onDownloadedOnlyChange,
                            wide = wide,
                        )
                        SwitchCard(
                            modifier = Modifier.weight(1f),
                            icon = incognitoIcon,
                            title = stringResource(MR.strings.pref_incognito_mode),
                            subtitle = stringResource(MR.strings.pref_incognito_mode_summary),
                            checked = incognitoMode,
                            onCheckedChange = onIncognitoModeChange,
                            wide = wide,
                        )
                    }
                }
            }
            item {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val wide = maxWidth >= 600.dp
                    val padded = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                    if (wide) {
                        Row(
                            modifier = padded,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            libraryCard(Modifier.weight(1f))
                            appCard(Modifier.weight(1f))
                        }
                    } else {
                        Column(
                            modifier = padded,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            libraryCard(Modifier.fillMaxWidth())
                            appCard(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** KMK (r114): a rounded card with a small primary caps heading */
@Composable
private fun MoreCard(
    heading: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = heading.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
            )
            content()
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
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

/** KMK (r114): Downloaded only / Incognito as a card; a card that is on is tinted */
@Composable
private fun SwitchCard(
    icon: Painter,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val container = if (checked) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val content = if (checked) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = content,
    ) {
        val toggle = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        if (wide) {
            Row(
                modifier = toggle.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(painter = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.72f),
                    )
                }
                Switch(checked = checked, onCheckedChange = null)
            }
        } else {
            Column(
                modifier = toggle.padding(horizontal = 12.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painter = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = checked, onCheckedChange = null)
                }
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.72f),
                )
            }
        }
    }
}
