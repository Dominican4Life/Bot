package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** KMK: One anime episode of the download queue, with progress, cancel and retry. */
@Composable
fun AnimeDownloadQueueRow(
    item: AnimeDownloadManager.Item,
    manager: AnimeDownloadManager,
    onClick: () -> Unit,
) {
    val state by item.state.collectAsState()
    val progress by item.progress.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.anime.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = if (state == Download.State.ERROR) item.error.orEmpty() else item.episode.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (state == Download.State.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state == Download.State.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
        if (state == Download.State.ERROR) {
            IconButton(onClick = { manager.retry(item.anime, item.episode) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.action_retry))
            }
        }
        IconButton(onClick = { manager.cancel(listOf(item.episode.id)) }) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.strings.action_cancel))
        }
    }
}

/** KMK: One manga chapter of the download queue, for the All tab (the Manga tab keeps the full list) */
@Composable
fun MangaDownloadQueueRow(
    download: Download,
    onCancel: () -> Unit,
    onClick: () -> Unit,
) {
    val state by download.statusFlow.collectAsState()
    val progress by remember(download) { download.progressFlow }.collectAsState(initial = download.progress)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(download.manga.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = download.chapter.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (state == Download.State.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state == Download.State.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.strings.action_cancel))
        }
    }
}

/** KMK: Section title of the All tab */
@Composable
fun DownloadQueueSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
