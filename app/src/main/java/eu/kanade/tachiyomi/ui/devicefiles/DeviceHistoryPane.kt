package eu.kanade.tachiyomi.ui.devicefiles

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.devicefiles.DeviceFile
import eu.kanade.tachiyomi.devicefiles.DeviceFilesIds
import eu.kanade.tachiyomi.devicefiles.DeviceFilesMangaSource
import eu.kanade.tachiyomi.devicefiles.FileKind
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** KMK: One folder's last read or watched item, for the Device files history */
private data class DeviceHistoryEntry(
    val isVideo: Boolean,
    /** The folder's title id (manga or anime) */
    val titleId: Long,
    /** The chapter or episode id */
    val itemId: Long,
    val folderName: String,
    val time: Long,
    /** Watched part of a video, 0..1 */
    val progress: Float?,
) {
    val key: String get() = (if (isVideo) "v" else "m") + titleId
}

/**
 * KMK: The History page's Device files history: the comics, pictures and videos opened from Device files, newest
 * first, kept apart from the manga and anime history. Tap to carry on; the bin removes that folder from it.
 */
@Composable
fun DeviceHistoryPane(contentPadding: PaddingValues, searchQuery: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animeRepository = remember { Injekt.get<AnimeRepository>() }
    val entriesFlow = remember {
        combine(
            Injekt.get<GetHistory>().subscribe("", null, null, null),
            animeRepository.subscribeHistory(),
        ) { manga, anime ->
            val comics = manga
                .filter { it.coverData.sourceId == DeviceFilesIds.MANGA }
                .map {
                    DeviceHistoryEntry(
                        isVideo = false,
                        titleId = it.mangaId,
                        itemId = it.chapterId,
                        folderName = it.title,
                        time = it.readAt?.time ?: 0L,
                        progress = null,
                    )
                }
            val videos = anime
                .filter { it.source == DeviceFilesIds.ANIME }
                .map {
                    DeviceHistoryEntry(
                        isVideo = true,
                        titleId = it.animeId,
                        itemId = it.episodeId,
                        folderName = it.title,
                        time = it.lastSeen,
                        progress = if (it.totalSeconds > 0) {
                            (it.lastSecondSeen.toFloat() / it.totalSeconds).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                    )
                }
            (comics + videos).sortedByDescending { it.time }
        }
    }
    val entries by entriesFlow.collectAsState(initial = null)
    var confirmClear by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<DeviceHistoryEntry?>(null) }

    val all = entries ?: return
    val shown = remember(all, searchQuery) {
        if (searchQuery.isNullOrBlank()) all else all.filter { it.folderName.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        if (all.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            ) {
                Text(
                    "Device files",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear Device files history")
                }
            }
        }
        if (shown.isEmpty()) {
            EmptyScreen(
                message = if (searchQuery.isNullOrBlank()) {
                    "Nothing opened from Device files yet. Comics, pictures and videos you open there show up here."
                } else {
                    "No results found"
                },
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            var lastDay: LocalDate? = null
            shown.forEach { entry ->
                val day = Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()).toLocalDate()
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") { DayHeader(day) }
                }
                item(key = entry.key) {
                    DeviceHistoryRow(
                        entry = entry,
                        onClick = { scope.launch { resume(context, entry) } },
                        onRemove = { removing = entry },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear Device files history?") },
            text = { Text("The manga and anime history stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launchIO { all.forEach { remove(it, animeRepository) } }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    removing?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove from history?") },
            text = { Text("${entry.folderName} is taken out of the Device files history. The files stay on the device.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launchIO { remove(entry, animeRepository) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

private suspend fun remove(entry: DeviceHistoryEntry, animeRepository: AnimeRepository) {
    if (entry.isVideo) {
        animeRepository.removeHistory(entry.titleId)
    } else {
        Injekt.get<RemoveHistory>().awaitManga(listOf(entry.titleId))
    }
}

/** The file the entry stands for, or null when it's gone */
private suspend fun fileOf(entry: DeviceHistoryEntry): File? = withContext(Dispatchers.IO) {
    val path = if (entry.isVideo) {
        Injekt.get<AnimeRepository>().getEpisode(entry.itemId)?.url
    } else {
        Injekt.get<GetChapter>().await(entry.itemId)?.url
    }
    path?.let(::File)?.takeIf { it.exists() }
}

private suspend fun resume(context: Context, entry: DeviceHistoryEntry) {
    if (fileOf(entry) == null) {
        context.toast("That file isn't on the device any more")
        return
    }
    val intent = if (entry.isVideo) {
        PlayerActivity.newIntent(context, entry.titleId, entry.itemId)
    } else {
        ReaderActivity.newIntent(context, entry.titleId, entry.itemId)
    }
    context.startActivity(intent)
}

@Composable
private fun DayHeader(day: LocalDate) {
    val today = LocalDate.now()
    val text = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateUtils.formatDateTime(
            LocalContext.current,
            day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun DeviceHistoryRow(entry: DeviceHistoryEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    // The file's picture and name, looked up once: a folder of pictures shows its first picture
    val info by produceState<Pair<DeviceFile, String>?>(null, entry.itemId) {
        value = fileOf(entry)?.let { f ->
            if (f.isDirectory) {
                DeviceFile.of(DeviceFilesMangaSource.imagesIn(f).firstOrNull() ?: f) to "Pictures"
            } else {
                DeviceFile.of(f) to f.nameWithoutExtension
            }
        }
    }
    val file = info?.first
    val itemName = info?.second
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        val thumbModifier = Modifier.size(width = 72.dp, height = 56.dp).clip(RoundedCornerShape(10.dp))
        if (file != null) {
            FileThumbnail(file, thumbModifier)
        } else {
            KindIcon(if (entry.isVideo) FileKind.Video else FileKind.Archive, thumbModifier)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                entry.folderName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    itemName,
                    DateUtils.formatDateTime(LocalContext.current, entry.time, DateUtils.FORMAT_SHOW_TIME),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.progress?.takeIf { it > 0f }?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove from history")
        }
    }
}
