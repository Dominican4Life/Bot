package eu.kanade.tachiyomi.ui.anime

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.QualitySheetHosterContent
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.QualitySheetVideoContent
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.getChangedAt
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Episode options (Anikku's long-press quality dialog): the episode's streams grouped by server, and for the
 * picked stream: play it here or in another player, copy its link, or download it.
 */
@Composable
fun EpisodeOptionsDialog(
    anime: Anime,
    episode: Episode,
    source: AnimeSource,
    onDismissRequest: () -> Unit,
    onPlay: (hosters: List<Hoster>, hosterIndex: Int, videoIndex: Int) -> Unit,
    onPlayExternal: (Video) -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = remember(episode.id, source.id) {
        EpisodeStreams(context.applicationContext, anime, episode, source, scope)
    }
    LaunchedEffect(loader) { loader.load() }

    val hosterStates by loader.hosterStates.collectAsState()
    val expanded by loader.expanded.collectAsState()
    val selected by loader.selected.collectAsState()
    val current by loader.current.collectAsState()
    val error by loader.error.collectAsState()
    val pickStream by loader.pickStream.collectAsState()
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
        title = {
            Text(
                text = episode.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val states = hosterStates
                when {
                    error != null -> Text(error!!.ifBlank { stringResource(KMR.strings.anime_no_videos) })
                    states == null -> LoadingRow()
                    current == null || pickStream -> {
                        if (current == null && states.none { it is HosterState.Ready && it.videoList.isNotEmpty() }) {
                            LoadingRow()
                        }
                        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            val only = states.singleOrNull()
                            if (only is HosterState.Ready && only.name == Hoster.NO_HOSTER_LIST) {
                                QualitySheetVideoContent(
                                    videoList = only.videoList,
                                    videoState = only.videoState,
                                    selectedVideoIndex = selected.second,
                                    onClickVideo = loader::onClickVideo,
                                    highlightDefaultStream = false,
                                    autoScrollToDefault = false,
                                )
                            } else {
                                QualitySheetHosterContent(
                                    hosterState = states,
                                    expandedState = expanded,
                                    selectedVideoIndex = selected,
                                    onClickHoster = loader::onClickHoster,
                                    onClickVideo = loader::onClickVideo,
                                    highlightDefaultStream = false,
                                    autoScrollToDefault = false,
                                    displayHosters = Pair(
                                        playerPreferences.showFailedHosters().get(),
                                        playerPreferences.showEmptyHosters().get(),
                                    ),
                                )
                            }
                        }
                    }
                    else -> {
                        val video = current!!
                        val hosterName = states.getOrNull(selected.first)?.name
                            ?.takeIf { it.isNotBlank() && it != Hoster.NO_HOSTER_LIST }
                        val label = listOfNotNull(hosterName, video.videoTitle.ifBlank { null })
                            .joinToString(" • ")
                            .ifBlank { video.videoUrl }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { loader.pickStream.value = true }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(KMR.strings.anime_episode_options_hint),
                                    fontStyle = FontStyle.Italic,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        HorizontalDivider()
                        OptionRow(Icons.Filled.PlayArrow, stringResource(KMR.strings.anime_play_internally)) {
                            onPlay(loader.hostersForPlayer(), selected.first, selected.second)
                            onDismissRequest()
                        }
                        OptionRow(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(KMR.strings.anime_play_externally)) {
                            onPlayExternal(video)
                            onDismissRequest()
                        }
                        OptionRow(Icons.Outlined.ContentCopy, stringResource(MR.strings.action_copy_link)) {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText(episode.name, video.videoUrl))
                            context.toast(MR.strings.copied_to_clipboard_plain)
                        }
                        OptionRow(Icons.Outlined.Download, stringResource(KMR.strings.anime_download_internally)) {
                            onDownload()
                            context.toast(KMR.strings.anime_download_queued)
                            onDismissRequest()
                        }
                        OptionRow(Icons.Outlined.SystemUpdateAlt, stringResource(KMR.strings.anime_download_externally)) {
                            val intent = ExternalPlayer.downloadIntent(
                                video = video,
                                fileName = "${anime.title} - ${episode.name}",
                                sourceHeaders = (source as? AnimeHttpSource)?.headers,
                                chooserTitle = context.stringResource(KMR.strings.anime_download_externally),
                            )
                            try {
                                context.startActivity(intent)
                                onDismissRequest()
                            } catch (_: ActivityNotFoundException) {
                                context.toast(KMR.strings.anime_no_downloader)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun LoadingRow() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(stringResource(KMR.strings.anime_loading_videos))
    }
}

@Composable
private fun OptionRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Loads the servers and streams of one episode, like the player's quality sheet does */
private class EpisodeStreams(
    private val appContext: Context,
    private val anime: Anime,
    private val episode: Episode,
    private val source: AnimeSource,
    private val scope: CoroutineScope,
) {
    private val _hosterStates = MutableStateFlow<List<HosterState>?>(null)
    val hosterStates = _hosterStates.asStateFlow()
    private val _expanded = MutableStateFlow<List<Boolean>>(emptyList())
    val expanded = _expanded.asStateFlow()
    private val _selected = MutableStateFlow(Pair(-1, -1))
    val selected = _selected.asStateFlow()
    private val _current = MutableStateFlow<Video?>(null)
    val current = _current.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** True while the stream list is shown instead of the actions */
    val pickStream = MutableStateFlow(false)

    private var hosters: List<Hoster> = emptyList()
    private val playerPreferences: PlayerPreferences = Injekt.get()

    suspend fun load() {
        val list = try {
            EpisodeLoader.getHosters(episode, anime, source)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Couldn't load the servers of ${episode.name}" }
            _error.value = e.message ?: e.javaClass.simpleName
            return
        }
        if (list.isEmpty()) {
            _error.value = ""
            return
        }
        hosters = list
        _expanded.value = List(list.size) { true }
        _hosterStates.value = list.map { hoster ->
            val videos = hoster.videoList
            when {
                hoster.lazy -> HosterState.Idle(hoster.hosterName)
                videos == null -> HosterState.Loading(hoster.hosterName)
                else -> HosterState.Ready(hoster.hosterName, videos, List(videos.size) { Video.State.QUEUE })
            }
        }
        coroutineScope {
            list.forEachIndexed { index, hoster ->
                if (hoster.lazy) return@forEachIndexed
                launch { setHoster(index, EpisodeLoader.loadHosterVideos(source, hoster)) }
            }
        }
        if (_current.value == null && _selected.value.first == -1) selectBest()
    }

    /** Picks the stream the player would start with (preferred, then the default stream setting, then the first) */
    private suspend fun selectBest() {
        while (true) {
            val states = _hosterStates.value ?: return
            val (hosterIdx, videoIdx) = HosterLoader.selectBestVideo(
                states,
                playerPreferences.defaultStreamSelector().get(),
            )
            if (hosterIdx == -1 || videoIdx == -1) {
                if (_current.value == null &&
                    states.none { it is HosterState.Ready && it.videoList.isNotEmpty() } &&
                    states.none { it is HosterState.Idle || it is HosterState.Loading }
                ) {
                    _error.value = ""
                }
                return
            }
            if (resolve(hosterIdx, videoIdx)) return
            // A stream picked by hand meanwhile wins
            if (_current.value != null || _selected.value.first != -1) return
        }
    }

    fun onClickHoster(hosterIndex: Int) {
        when (val state = _hosterStates.value?.getOrNull(hosterIndex) ?: return) {
            is HosterState.Ready -> _expanded.update { list ->
                list.toMutableList().apply { if (hosterIndex in indices) this[hosterIndex] = !this[hosterIndex] }
            }
            is HosterState.Error, is HosterState.Idle -> {
                val hoster = hosters.getOrNull(hosterIndex) ?: return
                setHoster(hosterIndex, HosterState.Loading(state.name))
                scope.launch { setHoster(hosterIndex, EpisodeLoader.loadHosterVideos(source, hoster)) }
            }
            is HosterState.Loading -> {}
        }
    }

    fun onClickVideo(hosterIndex: Int, videoIndex: Int) {
        scope.launch {
            if (!resolve(hosterIndex, videoIndex)) {
                withUIContext {
                    appContext.toast(KMR.strings.anime_stream_failed)
                }
            }
        }
    }

    /** Works out the stream's real link; on success it becomes the picked stream */
    private suspend fun resolve(hosterIndex: Int, videoIndex: Int): Boolean {
        val ready = _hosterStates.value?.getOrNull(hosterIndex) as? HosterState.Ready ?: return false
        val video = ready.videoList.getOrNull(videoIndex) ?: return false
        val previous = _selected.value
        val previousVideo = _current.value
        _selected.value = Pair(hosterIndex, videoIndex)
        setVideo(hosterIndex, videoIndex, video, Video.State.LOAD_VIDEO)

        val resolved = try {
            HosterLoader.getResolvedVideo(source, video)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Couldn't resolve ${video.videoTitle}" }
            null
        }
        if (resolved == null || resolved.videoUrl.isBlank() || resolved.videoUrl == "null") {
            setVideo(hosterIndex, videoIndex, video, Video.State.ERROR)
            if (_selected.value == Pair(hosterIndex, videoIndex)) {
                _selected.value = if (previousVideo != null) previous else Pair(-1, -1)
            }
            return false
        }
        setVideo(hosterIndex, videoIndex, resolved, Video.State.READY)
        if (_selected.value == Pair(hosterIndex, videoIndex)) {
            _current.value = resolved
            pickStream.value = false
        }
        return true
    }

    /** The servers with the streams loaded so far, for the built-in player */
    fun hostersForPlayer(): List<Hoster> {
        val states = _hosterStates.value.orEmpty()
        return hosters.mapIndexed { index, hoster ->
            Hoster(
                hosterUrl = hoster.hosterUrl,
                hosterName = hoster.hosterName,
                videoList = (states.getOrNull(index) as? HosterState.Ready)?.videoList ?: hoster.videoList,
                internalData = hoster.internalData,
                lazy = hoster.lazy,
                memo = hoster.memo,
            )
        }
    }

    private fun setHoster(index: Int, state: HosterState) {
        _hosterStates.update { list ->
            list?.toMutableList()?.apply { if (index in indices) this[index] = state }
        }
    }

    private fun setVideo(hosterIndex: Int, videoIndex: Int, video: Video, state: Video.State) {
        _hosterStates.update { list ->
            val ready = list?.getOrNull(hosterIndex) as? HosterState.Ready ?: return@update list
            if (videoIndex !in ready.videoList.indices) return@update list
            list.toMutableList().apply { this[hosterIndex] = ready.getChangedAt(videoIndex, video, state) }
        }
    }
}
