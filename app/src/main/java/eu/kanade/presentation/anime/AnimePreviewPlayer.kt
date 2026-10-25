package eu.kanade.presentation.anime

import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeVideoFetcher
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.ui.player.VideoFilterTheme
import eu.kanade.tachiyomi.ui.player.VideoFilters
import eu.kanade.tachiyomi.ui.player.applyAnime4K
import eu.kanade.tachiyomi.ui.player.resolveUri
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.utils.Anime4KManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.ank.AMR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** How the preview shows the video filters */
private enum class PreviewView { Filtered, Original, Split }

private sealed interface PreviewPhase {
    data object Idle : PreviewPhase
    data object Loading : PreviewPhase
    data object Playing : PreviewPhase
    data object NoVideos : PreviewPhase
    data class Failed(val message: String) : PreviewPhase
}

/**
 * KMK: Small video player at the top of the browse preview panel, with the video settings under it.
 * Uses its own mpv instance (closed as soon as the panel goes away) and the same filter settings as the
 * full player, so whatever is tuned here carries over. Split view puts the untouched picture on the left
 * and the filtered one on the right; "Hold to compare" shows the original while pressed.
 *
 * [episode] is what to play; bumping [playToken] starts it (tapping an episode row does that).
 */
@Composable
fun AnimePreviewSection(
    anime: Anime,
    source: AnimeSource?,
    episode: Episode?,
    playToken: Int,
    onOpenPlayer: (episode: Episode, positionMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decoderPreferences = remember { Injekt.get<DecoderPreferences>() }
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    val anime4kManager = remember { Injekt.get<Anime4KManager>() }

    var phase by remember { mutableStateOf<PreviewPhase>(PreviewPhase.Idle) }
    var mpv by remember { mutableStateOf<MPV?>(null) }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var videoIndex by remember { mutableIntStateOf(0) }
    var position by remember { mutableDoubleStateOf(0.0) }
    var duration by remember { mutableDoubleStateOf(0.0) }
    var paused by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    var view by remember { mutableStateOf(PreviewView.Filtered) }
    var comparing by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(true) }
    var filterRevision by remember { mutableIntStateOf(0) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var playingEpisode by remember { mutableStateOf<Episode?>(null) }

    fun releasePlayer() {
        val instance = mpv ?: return
        mpv = null
        runCatching { instance.command("stop") }
        // Let the video surface detach first (it happens when the view leaves the screen), then free mpv
        Handler(Looper.getMainLooper()).post { runCatching { instance.close() } }
    }

    fun ensurePlayer(): MPV {
        mpv?.let { return it }
        val cacheDir = context.cacheDir.path
        val caFile = File(context.filesDir, "mpv/cacert.pem")
        return MPV(context.applicationContext) {
            it.setOptionString("vo", "null")
            it.setOptionString("gpu-context", "android")
            it.setOptionString("opengl-es", "yes")
            it.setOptionString("ao", "audiotrack,opensles")
            // Copy-back decoding so the split view's filters can run
            it.setOptionString("hwdec", if (decoderPreferences.tryHWDecoding().get()) "mediacodec-copy" else "no")
            it.setOptionString("keep-open", "yes")
            it.setOptionString("force-window", "no")
            it.setOptionString("ytdl", "no")
            it.setOptionString("sid", "no")
            it.setOptionString("demuxer-max-bytes", "${24 * 1024 * 1024}")
            it.setOptionString("demuxer-max-back-bytes", "${8 * 1024 * 1024}")
            it.setOptionString("gpu-shader-cache-dir", cacheDir)
            it.setOptionString("msg-level", "all=warn")
            if (caFile.exists()) {
                it.setOptionString("tls-verify", "yes")
                it.setOptionString("tls-ca-file", caFile.path)
            }
        }.also {
            it.setPropertyBoolean("mute", muted)
            it.setPropertyDouble("speed", playerPreferences.playerSpeed().get().toDouble())
            mpv = it
        }
    }

    fun applyView(instance: MPV?) {
        instance ?: return
        val effective = if (comparing) PreviewView.Original else view
        val values = VideoFilters.entries.associateWith { it.preference(decoderPreferences).get() }
        runCatching {
            when (effective) {
                PreviewView.Filtered -> {
                    values.forEach { (filter, value) -> instance.setPropertyInt(filter.mpvProperty, value) }
                    instance.command("vf", "set", "")
                    applyAnime4K(instance, decoderPreferences, anime4kManager)
                }
                PreviewView.Original -> {
                    VideoFilters.entries.forEach { instance.setPropertyInt(it.mpvProperty, 0) }
                    instance.command("vf", "set", "")
                    instance.setPropertyString("glsl-shaders", "")
                }
                PreviewView.Split -> {
                    VideoFilters.entries.forEach { instance.setPropertyInt(it.mpvProperty, 0) }
                    instance.setPropertyString("glsl-shaders", "")
                    instance.command("vf", "set", splitGraph(values))
                }
            }
        }.onFailure { e -> context.logcat(LogPriority.WARN, e) { "Preview filters failed" } }
    }

    fun startVideo(index: Int, list: List<Video>, ep: Episode) {
        loadJob?.cancel()
        loadJob = scope.launch {
            phase = PreviewPhase.Loading
            buffering = true
            try {
                val video = withIOContext { AnimeVideoFetcher.resolve(source, list[index]) }
                if (video.videoUrl.isBlank() || video.videoUrl == "null") {
                    phase = PreviewPhase.NoVideos
                    return@launch
                }
                val instance = ensurePlayer()
                val headers = video.headers ?: (source as? AnimeHttpSource)?.headers
                if (headers != null) {
                    val headerString = headers.toMultimap()
                        .map { (name, values) -> name + ": " + (values.firstOrNull() ?: "").replace(",", "\\,") }
                        .joinToString(",")
                    instance.setOptionString("http-header-fields", headerString)
                }
                videoIndex = index
                playingEpisode = ep
                phase = PreviewPhase.Playing
                instance.command("loadfile", video.videoUrl, "replace")
                instance.setPropertyBoolean("pause", false)
                applyView(instance)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Preview failed" }
                phase = PreviewPhase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun loadEpisode(ep: Episode) {
        loadJob?.cancel()
        loadJob = scope.launch {
            phase = PreviewPhase.Loading
            // Downloaded: play the file, no need to ask the site
            val localUrl = withIOContext {
                Injekt.get<AnimeDownloadManager>().findEpisodeFile(anime, ep)?.uri?.resolveUri(context)
            }
            if (localUrl != null) {
                videos = listOf(Video(videoUrl = localUrl, videoTitle = "Downloaded", initialized = true))
                startVideo(0, videos, ep)
                return@launch
            }
            val src = source ?: run {
                phase = PreviewPhase.NoVideos
                return@launch
            }
            try {
                val list = withIOContext { AnimeVideoFetcher.fetchVideos(src, ep.toSEpisode()) }
                videos = list
                if (list.isEmpty()) {
                    phase = PreviewPhase.NoVideos
                } else {
                    startVideo(0, list, ep)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Preview videos failed" }
                phase = PreviewPhase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // A tap on an episode row starts it here
    LaunchedEffect(playToken) {
        if (playToken > 0 && episode != null) loadEpisode(episode)
    }

    // Keep the time and play state fresh while something is loaded
    LaunchedEffect(mpv) {
        val instance = mpv ?: return@LaunchedEffect
        while (isActive) {
            runCatching {
                position = instance.getPropertyDouble("time-pos") ?: 0.0
                duration = instance.getPropertyDouble("duration") ?: 0.0
                paused = instance.getPropertyBoolean("pause") ?: false
                buffering = instance.getPropertyBoolean("paused-for-cache") == true ||
                    instance.getPropertyBoolean("core-idle") == true && !paused
            }
            delay(400)
        }
    }

    LaunchedEffect(view, comparing, filterRevision, mpv) { applyView(mpv) }

    // Hide the controls a few seconds after the last touch while playing
    LaunchedEffect(showControls, paused, phase) {
        if (showControls && !paused && phase == PreviewPhase.Playing) {
            delay(3000)
            showControls = false
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        runCatching { mpv?.setPropertyBoolean("pause", true) }
    }

    DisposableEffect(Unit) {
        onDispose {
            loadJob?.cancel()
            releasePlayer()
        }
    }

    Column(modifier = modifier) {
        // Player
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .clickable { showControls = !showControls },
            contentAlignment = Alignment.Center,
        ) {
            val instance = mpv
            if (phase == PreviewPhase.Playing && instance != null) {
                AndroidView(
                    factory = { ctx -> BaseMPVView(ctx, null).also { it.mpv = instance; it.setVo("gpu") } },
                    modifier = Modifier.fillMaxSize(),
                )
                if (view == PreviewView.Split && !comparing) {
                    SplitLabels()
                }
            } else {
                AsyncImage(
                    model = anime.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            }

            when (val p = phase) {
                PreviewPhase.Idle -> IdleOverlay(
                    episodeName = episode?.name,
                    onClick = { episode?.let(::loadEpisode) },
                )
                PreviewPhase.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(KMR.strings.anime_preview_loading), color = Color.White)
                }
                PreviewPhase.NoVideos -> Text(
                    text = stringResource(KMR.strings.anime_preview_no_videos),
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                )
                is PreviewPhase.Failed -> Text(
                    text = p.message,
                    color = Color.White,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(16.dp),
                )
                PreviewPhase.Playing -> {
                    if (buffering && !paused) CircularProgressIndicator(color = Color.White)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showControls || paused,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        PlayerControls(
                            paused = paused,
                            muted = muted,
                            position = position,
                            duration = duration,
                            videos = videos,
                            videoIndex = videoIndex,
                            onTogglePause = {
                                showControls = true
                                runCatching { mpv?.setPropertyBoolean("pause", !paused) }
                            },
                            onToggleMute = {
                                muted = !muted
                                runCatching { mpv?.setPropertyBoolean("mute", muted) }
                            },
                            onSeek = { seconds ->
                                showControls = true
                                position = seconds
                                runCatching { mpv?.command("seek", seconds.toString(), "absolute") }
                            },
                            onPickVideo = { index -> playingEpisode?.let { startVideo(index, videos, it) } },
                            onOpenPlayer = {
                                val ep = playingEpisode ?: return@PlayerControls
                                val positionMs = (position * 1000).toLong()
                                loadJob?.cancel()
                                phase = PreviewPhase.Idle
                                releasePlayer()
                                onOpenPlayer(ep, positionMs)
                            },
                        )
                    }
                }
            }
        }

        // Video settings
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { settingsOpen = !settingsOpen }
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(KMR.strings.anime_preview_video_settings),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
            Icon(
                imageVector = if (settingsOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
            )
        }
        AnimatedVisibility(visible = settingsOpen) {
            VideoSettings(
                view = view,
                onViewChange = { view = it },
                onComparePressed = { comparing = it },
                decoderPreferences = decoderPreferences,
                playerPreferences = playerPreferences,
                onFiltersChanged = { filterRevision++ },
                onSpeedChanged = { speed -> runCatching { mpv?.setPropertyDouble("speed", speed) } },
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun IdleOverlay(episodeName: String?, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = episodeName != null, onClick = onClick),
    ) {
        // KMK: the reader's video play button: dark circle, white ring, white arrow
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 170f / 255f))
                .border(androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 200f / 255f)), CircleShape),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
        if (episodeName != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(KMR.strings.anime_preview_tap_to_play, episodeName),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun SplitLabels() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PillLabel(stringResource(KMR.strings.anime_preview_original))
        PillLabel(stringResource(KMR.strings.anime_preview_filtered))
    }
}

@Composable
private fun PillLabel(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun PlayerControls(
    paused: Boolean,
    muted: Boolean,
    position: Double,
    duration: Double,
    videos: List<Video>,
    videoIndex: Int,
    onTogglePause: () -> Unit,
    onToggleMute: () -> Unit,
    onSeek: (Double) -> Unit,
    onPickVideo: (Int) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
        // Top: quality, mute, full player
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
            if (videos.size > 1) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    IconButton(onClick = { open = true }) {
                        Icon(
                            Icons.Outlined.HighQuality,
                            contentDescription = stringResource(KMR.strings.anime_preview_quality),
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        videos.forEachIndexed { index, video ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = video.videoTitle.ifBlank { video.videoUrl },
                                        fontWeight = if (index == videoIndex) FontWeight.Bold else null,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = {
                                    open = false
                                    onPickVideo(index)
                                },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = stringResource(
                        if (muted) KMR.strings.anime_preview_unmute else KMR.strings.anime_preview_mute,
                    ),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onOpenPlayer) {
                Icon(
                    Icons.Outlined.Fullscreen,
                    contentDescription = stringResource(KMR.strings.anime_preview_open_player),
                    tint = Color.White,
                )
            }
        }
        // Middle: play / pause
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.5f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(52.dp)
                .clickable(onClick = onTogglePause),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        // Bottom: time and seek bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Text(formatSeconds(position), color = Color.White, style = MaterialTheme.typography.labelSmall)
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(0.1f)),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek(it.toDouble()) }
                    dragging = null
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(0.1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(formatSeconds(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VideoSettings(
    view: PreviewView,
    onViewChange: (PreviewView) -> Unit,
    onComparePressed: (Boolean) -> Unit,
    decoderPreferences: DecoderPreferences,
    playerPreferences: PlayerPreferences,
    onFiltersChanged: () -> Unit,
    onSpeedChanged: (Double) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // View: filtered / original / split, plus hold-to-compare
        SettingsGroup(stringResource(KMR.strings.anime_preview_view), "view") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            listOf(
                PreviewView.Filtered to KMR.strings.anime_preview_filtered,
                PreviewView.Original to KMR.strings.anime_preview_original,
                PreviewView.Split to KMR.strings.anime_preview_split,
            ).forEach { (option, label) ->
                FilterChip(
                    selected = view == option,
                    onClick = { onViewChange(option) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        OutlinedButton(
            onClick = {},
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onComparePressed(true)
                            tryAwaitRelease()
                            onComparePressed(false)
                        },
                    )
                },
        ) {
            Icon(Icons.Outlined.Compare, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(KMR.strings.anime_preview_hold_compare))
        }
        }

        // Looks (the player's filter presets)
        var themeIndex by remember { mutableIntStateOf(decoderPreferences.videoFilterTheme().get()) }
        SettingsGroup(stringResource(AMR.strings.player_sheets_filters_themes), "presets") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            VideoFilterTheme.entries.forEachIndexed { index, theme ->
                FilterChip(
                    selected = themeIndex == index,
                    onClick = {
                        themeIndex = index
                        decoderPreferences.videoFilterTheme().set(index)
                        decoderPreferences.brightnessFilter().set(theme.brightness)
                        decoderPreferences.contrastFilter().set(theme.contrast)
                        decoderPreferences.saturationFilter().set(theme.saturation)
                        decoderPreferences.gammaFilter().set(theme.gamma)
                        decoderPreferences.hueFilter().set(theme.hue)
                        decoderPreferences.sharpenFilter().set(theme.sharpen)
                        onFiltersChanged()
                    },
                    label = { Text(stringResource(theme.titleRes)) },
                )
            }
        }
        }

        // Fine adjustments
        SettingsGroup(stringResource(KMR.strings.anime_preview_adjust), "adjust") {
        VideoFilters.entries.forEach { filter ->
            val pref = remember(filter) { filter.preference(decoderPreferences) }
            var value by remember(filter) { mutableIntStateOf(pref.get()) }
            // Presets and reset change the values from outside
            LaunchedEffect(pref) { pref.changes().collect { value = it } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(filter.titleRes),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(84.dp),
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = { value = it.roundToInt() },
                    onValueChangeFinished = {
                        pref.set(value)
                        onFiltersChanged()
                    },
                    valueRange = filter.min.toFloat()..filter.max.toFloat(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(32.dp),
                )
            }
        }
        }

        // Anime4K upscaler
        val gpuNext = remember { decoderPreferences.gpuNext().get() }
        var upscaler by remember { mutableStateOf(decoderPreferences.enableAnime4K().get()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(AMR.strings.pref_anime4k_title), modifier = Modifier.weight(1f))
            Switch(
                checked = upscaler,
                enabled = !gpuNext,
                onCheckedChange = {
                    upscaler = it
                    decoderPreferences.enableAnime4K().set(it)
                    if (it && decoderPreferences.anime4kMode().get() == Anime4KManager.Mode.OFF.name) {
                        decoderPreferences.anime4kMode().set(Anime4KManager.Mode.A.name)
                    }
                    onFiltersChanged()
                },
            )
        }
        if (gpuNext) {
            NoteText(stringResource(KMR.strings.anime_preview_gpu_next_note))
        } else if (upscaler) {
            var mode by remember { mutableStateOf(decoderPreferences.anime4kMode().get()) }
            var quality by remember { mutableStateOf(decoderPreferences.anime4kQuality().get()) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Anime4KManager.Mode.entries.filter { it != Anime4KManager.Mode.OFF }.forEach { option ->
                    FilterChip(
                        selected = mode == option.name,
                        onClick = {
                            mode = option.name
                            decoderPreferences.anime4kMode().set(option.name)
                            onFiltersChanged()
                        },
                        label = { Text(option.name.replace("_PLUS", "+")) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Anime4KManager.Quality.FAST to AMR.strings.anime4k_quality_fast,
                    Anime4KManager.Quality.BALANCED to AMR.strings.anime4k_quality_balanced,
                    Anime4KManager.Quality.HIGH to AMR.strings.anime4k_quality_high,
                ).forEach { (option, label) ->
                    FilterChip(
                        selected = quality == option.name,
                        onClick = {
                            quality = option.name
                            decoderPreferences.anime4kQuality().set(option.name)
                            onFiltersChanged()
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            if (view == PreviewView.Split) NoteText(stringResource(KMR.strings.anime_preview_upscaler_note))
        }

        // Speed
        var speed by remember { mutableStateOf(playerPreferences.playerSpeed().get()) }
        SettingsGroup(stringResource(KMR.strings.anime_preview_speed), "speed") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { option ->
                FilterChip(
                    selected = speed == option,
                    onClick = {
                        speed = option
                        playerPreferences.playerSpeed().set(option)
                        onSpeedChanged(option.toDouble())
                    },
                    label = { Text("${option}x".replace(".0x", "x")) },
                )
            }
        }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            NoteText(stringResource(KMR.strings.anime_preview_shared_note), modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    VideoFilters.entries.forEach { it.preference(decoderPreferences).set(0) }
                    decoderPreferences.videoFilterTheme().set(0)
                    themeIndex = 0
                    onFiltersChanged()
                },
            ) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(KMR.strings.anime_preview_reset))
            }
        }
    }
}

/**
 * KMK: A group of the video settings (View, Presets, Adjust, Speed) under its own header, folded or opened on its
 * own by tapping the header. Which groups are folded is remembered.
 */
@Composable
private fun SettingsGroup(
    title: String,
    id: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val pref = remember {
        uy.kohesive.injekt.Injekt.get<tachiyomi.core.common.preference.PreferenceStore>()
            .getStringSet("anime_preview_folded_groups", emptySet())
    }
    var folded by remember { mutableStateOf(pref.get()) }
    val open = id !in folded
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                folded = if (open) folded + id else folded - id
                pref.set(folded)
            },
    ) {
        SectionLabel(title, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    AnimatedVisibility(visible = open) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp),
    )
}

@Composable
private fun NoteText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Left half untouched, right half run through ffmpeg filters that match the player's sliders
 * (mpv's own sliders only work on the whole picture).
 */
private fun splitGraph(values: Map<VideoFilters, Int>): String {
    val brightness = (values[VideoFilters.BRIGHTNESS] ?: 0) / 100.0
    val contrast = 1 + (values[VideoFilters.CONTRAST] ?: 0) / 100.0
    val saturation = 1 + (values[VideoFilters.SATURATION] ?: 0) / 100.0
    val gamma = 2.0.pow((values[VideoFilters.GAMMA] ?: 0) / 100.0)
    val hue = (values[VideoFilters.HUE] ?: 0) * 1.8
    val sharpen = (values[VideoFilters.SHARPEN] ?: 0) * 0.4
    val filters = buildList {
        add(
            "eq=brightness=%.3f:contrast=%.3f:saturation=%.3f:gamma=%.3f"
                .format(Locale.US, brightness, contrast, saturation, gamma),
        )
        if (hue != 0.0) add("hue=h=%.1f".format(Locale.US, hue))
        if (sharpen != 0.0) add("unsharp=5:5:%.2f".format(Locale.US, sharpen))
    }.joinToString(",")
    val graph = "split[o][f];[f]$filters[g];[o]crop=iw/2:ih:0:0[l];[g]crop=iw/2:ih:iw/2:0[r];[l][r]hstack"
    // mpv's %length% quoting keeps the commas and brackets inside the graph intact
    return "lavfi=graph=%${graph.toByteArray().size}%$graph"
}

private fun formatSeconds(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
