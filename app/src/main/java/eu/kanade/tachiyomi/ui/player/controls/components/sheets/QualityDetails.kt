package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.animesource.model.VideoType
import eu.kanade.tachiyomi.ui.player.PlayerMemory
import eu.kanade.tachiyomi.ui.player.TrackNode
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

/**
 * KMK (r119): what mpv says it is playing (the Quality sheet's "Now playing" card). Read from mpv properties, so it
 * shows what really plays: the same figures the player log has.
 */
data class NowPlaying(
    val videoCodec: String?,
    val width: Int,
    val height: Int,
    val fps: Double?,
    val audioCodec: String?,
    val channels: Int?,
    val sampleRate: Int?,
    val audioBitrate: Double?,
    val videoBitrate: Double?,
    val hwdec: String?,
    val fileFormat: String?,
    val url: String?,
    val videoType: VideoType?,
) {
    val throughApp: Boolean get() = url?.startsWith("http://127.0.0.1") == true

    val isHls: Boolean
        get() = videoType == VideoType.HLS || fileFormat?.contains("hls") == true ||
            url?.contains("m3u8", ignoreCase = true) == true

    val isDash: Boolean
        get() = videoType == VideoType.DASH || fileFormat?.contains("dash") == true ||
            url?.substringBefore('?')?.lowercase()?.endsWith(".mpd") == true

    /** "mp4", "mkv", … for a file (the relay keeps the original's extension in its x= part) */
    val fileExtension: String
        get() {
            val path = url.orEmpty().substringBefore('#')
            val ext = if (throughApp) {
                path.substringAfterLast("x=", "").substringAfterLast('.', "")
            } else {
                path.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            }
            return ext.lowercase().takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } }
                ?: fileFormat?.substringBefore(',')?.takeIf { it.isNotBlank() }
                ?: "video"
        }
}

/** "H.264", "HEVC", "AAC", … from mpv's short codec names */
internal fun codecLabel(codec: String?): String? {
    val c = codec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when (c) {
        "h264", "avc", "avc1" -> "H.264"
        "hevc", "h265", "hvc1", "hev1" -> "HEVC"
        "vp8" -> "VP8"
        "vp9" -> "VP9"
        "av1" -> "AV1"
        "mpeg2video" -> "MPEG-2"
        "mpeg4" -> "MPEG-4"
        "aac" -> "AAC"
        "mp3" -> "MP3"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "ac3" -> "AC-3"
        "eac3" -> "E-AC-3"
        "flac" -> "FLAC"
        else -> c.uppercase(Locale.ROOT)
    }
}

private fun channelLabel(count: Int?): String? = when (count) {
    null, 0 -> null
    1 -> "mono"
    2 -> "stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$count ch"
}

private fun fpsLabel(fps: Double?): String? {
    val f = fps?.takeIf { it > 0.0 } ?: return null
    val rounded = Math.round(f * 100) / 100.0
    val text = if (rounded == Math.floor(rounded)) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", rounded)
    }
    return "$text fps"
}

/** "4.8 Mbps" / "128 kbps" from bits per second */
internal fun bitrateLabel(bps: Double?): String? {
    val b = bps?.takeIf { it > 0.0 } ?: return null
    return if (b >= 1_000_000) String.format(Locale.US, "%.1f Mbps", b / 1_000_000) else "${(b / 1000).toLong()} kbps"
}

private val CardShape = RoundedCornerShape(16.dp)
private val RowShape = RoundedCornerShape(12.dp)

@Composable
private fun Caption(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/**
 * KMK (r119): the Quality sheet's top part (XHQ1 / XHQ2): "Now playing", then either "Inside this stream" (mpv has
 * several picture tracks: HLS variants) with Auto, or "This server sent one quality" when the server gave one video
 * and mpv has one picture track.
 */
@Composable
fun QualityDetailsHeader(
    nowPlaying: NowPlaying?,
    videoTracks: List<TrackNode>,
    chosenTrack: Int?,
    onPickTrack: (Int?) -> Unit,
    serverHasOneVideo: Boolean,
    showServersCaption: Boolean,
    modifier: Modifier = Modifier,
    /** KMK (r119): Source Lab sources only: opens the Lab's Test on this source */
    onCheckInLab: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (nowPlaying != null && (nowPlaying.width > 0 || nowPlaying.videoCodec != null)) {
            Caption(stringResource(KMR.strings.player_quality_now_playing))
            DetailCard {
                val video = listOfNotNull(
                    codecLabel(nowPlaying.videoCodec),
                    if (nowPlaying.width > 0 && nowPlaying.height > 0) "${nowPlaying.width}×${nowPlaying.height}" else null,
                    fpsLabel(nowPlaying.fps),
                ).joinToString(" · ")
                if (video.isNotEmpty()) KeyValue(stringResource(KMR.strings.player_quality_video), video)
                val audio = listOfNotNull(
                    codecLabel(nowPlaying.audioCodec),
                    channelLabel(nowPlaying.channels),
                    nowPlaying.sampleRate?.takeIf { it > 0 }?.let {
                        val khz = it / 1000.0
                        if (khz == Math.floor(khz)) "${khz.toLong()} kHz" else String.format(Locale.US, "%.1f kHz", khz)
                    },
                    bitrateLabel(nowPlaying.audioBitrate),
                ).joinToString(" · ")
                if (audio.isNotEmpty()) KeyValue(stringResource(KMR.strings.player_quality_audio), audio)
                bitrateLabel(nowPlaying.videoBitrate)?.let {
                    KeyValue(stringResource(KMR.strings.player_quality_bitrate), "≈ $it")
                }
                val hw = nowPlaying.hwdec?.let { it.isNotEmpty() && it != "no" } == true
                KeyValue(
                    stringResource(KMR.strings.player_quality_decoding),
                    stringResource(if (hw) KMR.strings.player_quality_hardware else KMR.strings.player_quality_software),
                )
                val type = when {
                    nowPlaying.isHls -> stringResource(KMR.strings.player_quality_type_hls)
                    nowPlaying.isDash -> stringResource(KMR.strings.player_quality_type_dash)
                    else -> stringResource(KMR.strings.player_quality_type_file, nowPlaying.fileExtension)
                }
                KeyValue(
                    stringResource(KMR.strings.player_quality_type),
                    if (nowPlaying.throughApp) "$type · ${stringResource(KMR.strings.player_quality_through_app)}" else type,
                )
            }
        }

        if (videoTracks.size > 1) {
            Caption(stringResource(KMR.strings.player_quality_inside_stream))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                VariantRow(
                    label = stringResource(KMR.strings.player_quality_auto),
                    detail = stringResource(KMR.strings.player_quality_auto_hint),
                    selected = chosenTrack == null,
                    onClick = { onPickTrack(null) },
                )
                videoTracks
                    .sortedWith(
                        compareByDescending<TrackNode> { it.demuxH ?: 0L }.thenByDescending { it.hlsBitrate ?: 0L },
                    )
                    .forEach { track ->
                        val label = track.demuxH?.takeIf { it > 0 }?.let { "${it}p" }
                            ?: track.title?.takeIf { it.isNotBlank() }
                            ?: "#${track.id}"
                        val detail = listOfNotNull(
                            bitrateLabel(track.hlsBitrate?.toDouble() ?: track.demuxBitrate?.toDouble()),
                            codecLabel(track.codec),
                        ).joinToString(" · ")
                        VariantRow(
                            label = label,
                            detail = detail,
                            selected = chosenTrack != null && track.isSelected,
                            onClick = { onPickTrack(track.id) },
                        )
                    }
            }
        } else if (serverHasOneVideo && videoTracks.size <= 1) {
            Caption(stringResource(KMR.strings.player_quality_qualities))
            DetailCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = nowPlaying?.height?.takeIf { it > 0 }?.let { "${it}p" } ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(KMR.strings.player_quality_only_one),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(KMR.strings.player_quality_one_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(KMR.strings.player_quality_one_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // KMK (r119): "Check the site in Source Lab" (MainActivity's OPEN_SOURCE_LAB route)
                if (onCheckInLab != null) {
                    androidx.compose.material3.OutlinedButton(onClick = onCheckInLab) {
                        Text("Check the site in Source Lab")
                    }
                }
            }
        }

        if (showServersCaption) Caption(stringResource(KMR.strings.player_quality_servers))
    }
}

@Composable
private fun VariantRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** KMK (r119): the server dot: green played, yellow mixed, red failed, grey not tried */
@Composable
fun ServerDot(dot: PlayerMemory.Dot, modifier: Modifier = Modifier) {
    val color = when (dot) {
        PlayerMemory.Dot.Played -> Color(0xFF4CD27A)
        PlayerMemory.Dot.Mixed -> Color(0xFFF2B84B)
        PlayerMemory.Dot.Failed -> Color(0xFFFF6B6B)
        PlayerMemory.Dot.NotTried -> Color(0xFF5D6068)
    }
    Box(modifier = modifier.size(8.dp).clip(CircleShape).background(color))
}

/** KMK (r119): the server chip: "Remembered · played 9×", "played 3× · failed 2×", "failed 2× (403)", "not tried" */
@Composable
fun ServerChip(chip: PlayerMemory.Chip, modifier: Modifier = Modifier) {
    val text = when {
        chip.remembered -> stringResource(KMR.strings.player_memory_chip_remembered, chip.played)
        chip.played > 0 && chip.failed > 0 ->
            stringResource(KMR.strings.player_memory_chip_played_failed, chip.played, chip.failed)
        chip.played > 0 -> stringResource(KMR.strings.player_memory_chip_played, chip.played)
        chip.failed > 0 && chip.lastError == "403" ->
            stringResource(KMR.strings.player_memory_chip_failed_reason, chip.failed, "403")
        chip.failed > 0 -> stringResource(KMR.strings.player_memory_chip_failed, chip.failed)
        else -> stringResource(KMR.strings.player_memory_chip_not_tried)
    }
    val (bg, fg) = when {
        chip.remembered -> Color(0xFF13301F) to Color(0xFF8EE0A8)
        chip.dot == PlayerMemory.Dot.Failed -> Color(0xFF3A1D1D) to Color(0xFFFFB4AB)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** KMK (r119): the muted note under the servers when a title lists Sub and Dub servers */
@Composable
fun KindsApartNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(KMR.strings.player_quality_kinds_apart),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

/**
 * KMK (r119): ⋮ › "Remembered for this source" (XHQ3): server first, quality, decoding, connection, and a Reset that
 * clears only PlayerMemory for this source.
 */
@Composable
fun RememberedForSourceDialog(
    sourceName: String,
    memory: PlayerMemory.Memory?,
    isLabSource: Boolean,
    onReset: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val nothing = stringResource(KMR.strings.player_memory_nothing)
    val server = memory?.let { PlayerMemory.firstServer(it) }
    val serverValue = server?.let {
        stringResource(KMR.strings.player_memory_server_value, PlayerMemory.Slot(it.name, it.kind).label, it.played)
    } ?: nothing
    val rows = listOf(
        stringResource(KMR.strings.player_memory_server) to serverValue,
        stringResource(KMR.strings.player_memory_quality) to (memory?.quality ?: nothing),
        stringResource(KMR.strings.player_memory_decoding) to when (memory?.decoding) {
            PlayerMemory.DECODING_HARDWARE -> stringResource(KMR.strings.player_memory_hardware_worked)
            PlayerMemory.DECODING_SOFTWARE -> stringResource(KMR.strings.player_memory_software_after)
            else -> nothing
        },
        stringResource(KMR.strings.player_memory_connection) to when (memory?.connection) {
            PlayerMemory.CONNECTION_DIRECT -> stringResource(KMR.strings.player_memory_direct_worked)
            PlayerMemory.CONNECTION_APP -> stringResource(
                if (isLabSource) KMR.strings.player_memory_through_app else KMR.strings.player_memory_through_app_cookies,
            )
            else -> nothing
        },
    )
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(KMR.strings.player_memory_title, sourceName)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(KMR.strings.player_memory_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    rows.forEachIndexed { index, (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(text = key, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < rows.lastIndex) HorizontalDivider()
                    }
                }
                Text(
                    text = stringResource(KMR.strings.player_memory_reset_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(tachiyomi.i18n.MR.strings.action_close))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onReset()
                    onDismissRequest()
                },
                enabled = memory?.isEmpty == false,
            ) {
                Text(stringResource(KMR.strings.player_memory_reset))
            }
        },
    )
}
