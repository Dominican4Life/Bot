package eu.kanade.presentation.anime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.AnimeHistoryItem
import eu.kanade.tachiyomi.data.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.AnimeUpdateItem
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/** KMK: Plays an episode in the built-in player, or opens the anime when the outside player is chosen. */
@Composable
private fun rememberPlayEpisode(): (animeId: Long, episodeId: Long) -> Unit {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    return { animeId, episodeId ->
        if (playerPreferences.alwaysUseExternalPlayer().get()) {
            navigator.push(AnimeScreen(animeId))
        } else {
            context.startActivity(PlayerActivity.newIntent(context, animeId, episodeId))
        }
    }
}

/** KMK: Anime side of the History tab */
@Composable
fun AnimeHistoryPane(
    contentPadding: PaddingValues,
    searchQuery: String?,
) {
    val navigator = LocalNavigator.currentOrThrow
    val repository = remember { Injekt.get<AnimeRepository>() }
    val sourceManager = remember { Injekt.get<AnimeSourceManager>() }
    val scope = rememberCoroutineScope()
    val history by remember { repository.subscribeHistory() }.collectAsState(initial = null)
    val playEpisode = rememberPlayEpisode()
    var removeAnimeId by remember { mutableStateOf<Long?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    // KMK: History's "Device files only" button
    val deviceOnlyPref = remember { eu.kanade.tachiyomi.devicefiles.DeviceFilesPreferences.instance.historyDeviceOnly }
    val deviceOnly by remember { deviceOnlyPref.changes() }.collectAsState(initial = deviceOnlyPref.get())
    val all = history ?: return
    val items = remember(all, deviceOnly) {
        if (deviceOnly) all.filter { it.source == eu.kanade.tachiyomi.devicefiles.DeviceFilesIds.ANIME } else all
    }
    val filtered = remember(items, searchQuery) {
        if (searchQuery.isNullOrBlank()) items else items.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        if (items.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.history),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { confirmClearAll = true }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(KMR.strings.anime_clear_history))
                }
            }
        }
        if (filtered.isEmpty()) {
            EmptyScreen(
                message = if (deviceOnly && searchQuery.isNullOrBlank()) {
                    "Nothing played from Device files yet"
                } else {
                    stringResource(
                        if (searchQuery.isNullOrBlank()) KMR.strings.anime_no_history else MR.strings.no_results_found,
                    )
                },
            )
        } else {
            FastScrollLazyColumn(
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 8.dp),
            ) {
                items(filtered, key = { it.animeId }) { item ->
                    HistoryRow(
                        item = item,
                        sourceManager = sourceManager,
                        onClick = { navigator.push(AnimeScreen(item.animeId)) },
                        onResume = { playEpisode(item.animeId, item.episodeId) },
                        onDelete = { removeAnimeId = item.animeId },
                    )
                }
            }
        }
    }

    removeAnimeId?.let { animeId ->
        ConfirmDialog(
            text = stringResource(KMR.strings.anime_remove_history_confirm),
            onConfirm = { scope.launchIO { repository.removeHistory(animeId) } },
            onDismiss = { removeAnimeId = null },
        )
    }
    if (confirmClearAll) {
        ConfirmDialog(
            text = stringResource(KMR.strings.anime_clear_history_confirm),
            onConfirm = { scope.launchIO { repository.removeAllHistory() } },
            onDismiss = { confirmClearAll = false },
        )
    }
}

@Composable
private fun HistoryRow(
    item: AnimeHistoryItem,
    sourceManager: AnimeSourceManager,
    onClick: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        AnimeCoverImage(
            url = item.thumbnailUrl,
            source = sourceManager.get(item.source),
            modifier = Modifier
                .width(48.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = item.episodeName + " • " + formatWhen(item.lastSeen, dateFormat, timeFormat),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.secondaryItemAlpha(),
            )
            if (item.totalSeconds > 0 && item.lastSecondSeen > 0) {
                LinearProgressIndicator(
                    progress = { (item.lastSecondSeen.toFloat() / item.totalSeconds).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.action_delete))
        }
        IconButton(onClick = onResume) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(KMR.strings.anime_resume_episode))
        }
    }
}

/** KMK: Anime side of the Updates tab */
@Composable
fun AnimeUpdatesPane(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val repository = remember { Injekt.get<AnimeRepository>() }
    val sourceManager = remember { Injekt.get<AnimeSourceManager>() }
    val scope = rememberCoroutineScope()
    val after = remember { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90) }
    val updates by remember { repository.subscribeUpdates(after) }.collectAsState(initial = null)
    val playEpisode = rememberPlayEpisode()
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    val items = updates ?: return
    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        ) {
            Text(
                text = stringResource(MR.strings.label_recent_updates),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    AnimeLibraryUpdateJob.startNow(context)
                    context.toast(KMR.strings.anime_updating_library)
                },
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.action_update_library))
            }
        }
        if (items.isEmpty()) {
            EmptyScreen(message = stringResource(KMR.strings.anime_no_updates))
            return@Column
        }
        val grouped = remember(items) { items.groupBy { startOfDay(it.dateFetch) } }
        FastScrollLazyColumn(
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 8.dp),
        ) {
            grouped.forEach { (day, dayItems) ->
                item(key = "day-$day") {
                    Text(
                        text = dateFormat.format(Date(day)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .secondaryItemAlpha(),
                    )
                }
                items(dayItems, key = { "ep-${it.episodeId}" }) { item ->
                    UpdateRow(
                        item = item,
                        sourceManager = sourceManager,
                        onClick = { playEpisode(item.animeId, item.episodeId) },
                        onClickCover = { navigator.push(AnimeScreen(item.animeId)) },
                        onToggleSeen = {
                            scope.launch { repository.updateEpisode(id = item.episodeId, seen = !item.seen) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(
    item: AnimeUpdateItem,
    sourceManager: AnimeSourceManager,
    onClick: () -> Unit,
    onClickCover: () -> Unit,
    onToggleSeen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        AnimeCoverImage(
            url = item.thumbnailUrl,
            source = sourceManager.get(item.source),
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClickCover),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .alpha(if (item.seen) 0.45f else 1f),
        ) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!item.seen) {
                    val dotColor = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.size(6.dp)) { drawCircle(color = dotColor) }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = item.episodeName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleSeen) {
            Icon(
                imageVector = if (item.seen) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = stringResource(
                    if (item.seen) MR.strings.action_mark_as_unread else MR.strings.action_mark_as_read,
                ),
            )
        }
    }
}

@Composable
private fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
        text = { Text(text) },
    )
}

private fun formatWhen(time: Long, dateFormat: DateFormat, timeFormat: DateFormat): String {
    val date = Date(time)
    return if (startOfDay(time) == startOfDay(System.currentTimeMillis())) {
        timeFormat.format(date)
    } else {
        dateFormat.format(date)
    }
}

private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
