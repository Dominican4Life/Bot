package eu.kanade.tachiyomi.ui.player.controls.components.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.cast.PlayerCast
import eu.kanade.tachiyomi.ui.player.compat.Episode
import eu.kanade.tachiyomi.util.lang.toRelativeString
import eu.kanade.tachiyomi.util.system.toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PanelBackground = Color(0xFF1C1C1E)
private val PanelText = Color(0xFFF2F2F7)
private val PanelDim = Color(0xFF9A9AA0)

/**
 * KMK: A panel that slides in on the right of the player over the video, like Sumizuri's player panels
 * (Episodes, Cast to a device). Tapping the video beside it closes it.
 */
@Composable
fun PlayerSidePanel(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 460.dp)
                        .fillMaxWidth(0.42f)
                        .background(PanelBackground)
                        // Taps inside the panel stay inside
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(start = 28.dp, end = 12.dp, top = 24.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            color = PanelText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismissRequest) {
                            Icon(Icons.Outlined.Close, contentDescription = null, tint = PanelText)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp, bottom = 12.dp)
                            .width(56.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                    )
                    content()
                }
            }
        }
    }
}

@Composable
private fun PanelRow(
    title: String,
    subtitle: String?,
    highlighted: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 14.dp),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (highlighted) MaterialTheme.colorScheme.primary else PanelText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = PanelDim,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = PanelDim,
            modifier = Modifier.padding(start = 8.dp).size(22.dp),
        )
    }
}

/** KMK: Every episode, the one playing marked; tap one to play it, long-press to bookmark it */
@Composable
fun EpisodesSidePanel(
    currentEpisodeIndex: Int,
    episodeList: List<Episode>,
    dateRelativeTime: Boolean,
    dateFormat: DateTimeFormatter,
    onBookmarkClicked: (Long?, Boolean) -> Unit,
    onEpisodeClicked: (Long?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    // Oldest first, like a season's running order
    val ordered = remember(episodeList) { episodeList.sortedBy { it.episode_number } }
    val current = episodeList.getOrNull(currentEpisodeIndex)
    val startIndex = remember(ordered, current) { ordered.indexOfFirst { it.id == current?.id }.coerceAtLeast(0) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    PlayerSidePanel(title = "Episodes", onDismissRequest = onDismissRequest) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(ordered, key = { index, it -> "ep-${it.id}-$index" }) { _, episode ->
                val isCurrent = episode.id == current?.id
                var bookmarked by remember(episode.id) { mutableStateOf(episode.bookmark) }
                val number = formatEpisodeNumber(episode.episode_number.toDouble())
                val title = if (episode.name.startsWith(number) || episode.name.contains("Episode", true)) {
                    episode.name
                } else {
                    "$number ${episode.name}"
                }
                val date = episode.date_upload.takeIf { it > 0L }?.let {
                    LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                        .toRelativeString(context = context, relative = dateRelativeTime, dateFormat = dateFormat)
                }
                val subtitle = when {
                    isCurrent -> "Playing now"
                    else -> listOfNotNull(
                        "Watched".takeIf { episode.seen },
                        date,
                        episode.scanlator,
                    ).joinToString(" · ").ifBlank { null }
                }
                Box(modifier = Modifier.alpha(if (episode.seen && !isCurrent) 0.6f else 1f)) {
                    PanelRow(
                        title = title,
                        subtitle = subtitle,
                        highlighted = isCurrent,
                        leading = if (bookmarked) {
                            {
                                Icon(
                                    Icons.Filled.Bookmark,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onLongClick = {
                            bookmarked = !bookmarked
                            episode.bookmark = bookmarked
                            onBookmarkClicked(episode.id, bookmarked)
                        },
                        onClick = { onEpisodeClicked(episode.id) },
                    )
                }
            }
        }
    }
}

/** KMK: Picks a Google Cast device (a Chromecast or a TV with Cast built in) and plays the video there */
@Composable
fun CastSidePanel(
    viewModel: PlayerViewModel,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val devices by PlayerCast.rememberDevices()
    var connected by remember { mutableStateOf(PlayerCast.connectedDevice(context)) }
    var connecting by remember { mutableStateOf<String?>(null) }
    PlayerSidePanel(title = "Cast to a device", onDismissRequest = onDismissRequest) {
        Text(
            "Only sends a plain video address. A source that needs extra request headers to load its video cannot be cast.",
            color = PanelDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 16.dp, bottom = 12.dp),
        )
        connected?.let { name ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(Icons.Outlined.CastConnected, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Casting to $name",
                    color = PanelText,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        PlayerCast.stop(context)
                        connected = null
                    },
                ) { Text("Stop") }
            }
            HorizontalDivider(color = PanelDim.copy(alpha = 0.3f))
        }
        if (devices.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(
                    "Looking for devices on your Wi-Fi…",
                    color = PanelDim,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        LazyColumn {
            itemsIndexed(devices, key = { _, route -> route.id }) { _, route ->
                PanelRow(
                    title = route.name,
                    subtitle = if (connecting == route.id) "Connecting…" else route.description,
                    leading = {
                        Icon(
                            Icons.Outlined.Cast,
                            contentDescription = null,
                            tint = PanelText,
                            modifier = Modifier
                                .size(22.dp)
                                .background(Color.Transparent, RoundedCornerShape(4.dp)),
                        )
                    },
                    onClick = {
                        val video = viewModel.currentVideo.value
                        if (video == null) {
                            context.toast("Nothing is playing yet")
                            return@PanelRow
                        }
                        connecting = route.id
                        PlayerCast.cast(
                            context = context,
                            route = route,
                            video = video,
                            title = viewModel.mediaTitle.value,
                            subtitle = viewModel.animeTitle.value,
                            positionSeconds = viewModel.pos.value ?: 0,
                        ) { result ->
                            connecting = null
                            result.onSuccess { name ->
                                connected = name
                                viewModel.pause()
                                context.toast("Playing on $name")
                                onDismissRequest()
                            }.onFailure { context.toast(it.message) }
                        }
                    },
                )
            }
        }
    }
}
