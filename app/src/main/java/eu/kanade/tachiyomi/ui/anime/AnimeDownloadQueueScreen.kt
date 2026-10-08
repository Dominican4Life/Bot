package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: Anime episodes waiting to download or downloading, with progress, cancel and retry. */
class AnimeDownloadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { Injekt.get<AnimeDownloadManager>() }
        val queue by manager.queue.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.anime_download_queue),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (queue.isNotEmpty()) {
                            TextButton(onClick = manager::cancelAll) {
                                Text(stringResource(MR.strings.action_cancel_all))
                            }
                        }
                    },
                )
            },
        ) { contentPadding ->
            if (queue.isEmpty()) {
                EmptyScreen(
                    message = stringResource(KMR.strings.anime_download_queue_empty),
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                items(queue, key = { it.episode.id }) { item ->
                    val state by item.state.collectAsState()
                    val progress by item.progress.collectAsState()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navigator.push(AnimeScreen(item.anime.id)) }
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
            }
        }
    }
}
