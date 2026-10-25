package eu.kanade.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import eu.kanade.tachiyomi.data.coil.PageThumbFiles
import eu.kanade.tachiyomi.util.system.AppLog

/**
 * KMK: A page thumbnail for the page grids (the reader's Pages panel and the Browse preview window).
 *
 * Every page shows as a still picture, so the grid loads quickly and costs nothing to show. An animated page (GIF,
 * animated WebP) gets a small badge, and plays only while it's the [playing] one: the tile picked by holding and
 * sliding over the grid ([holdToPlay]). Only one tile plays at a time, so the page being read keeps its frame rate.
 *
 * The page is shown from its own kept copy ([PageThumbFiles]), so scrolling back doesn't load it again. [canMake]
 * false only shows a copy kept before (the reader's panel waits until the page is loaded).
 */
@Composable
fun AnimatedThumbnail(
    model: Any,
    playing: Boolean,
    modifier: Modifier = Modifier,
    canMake: Boolean = true,
) {
    val context = LocalContext.current
    val key = remember(model) { PageThumbFiles.keyOf(model) }
    var file by remember(key) { mutableStateOf(key?.let { PageThumbFiles.saved(context, it) }) }
    LaunchedEffect(key, canMake) {
        if (file != null || key == null || !canMake) return@LaunchedEffect
        file = runCatching { PageThumbFiles.get(context, key, model) }
            .onFailure { AppLog.add('W', "thumbs", "Couldn't keep page $key: ${it.message}") }
            .getOrNull()
    }
    val f = file
    if (f == null) {
        // A loading circle until the copy is there
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        return
    }
    val animated = remember(f) { PageThumbFiles.kindOf(f) != PageThumbFiles.Kind.STILL }
    val play = animated && playing
    Box(modifier = modifier) {
        AsyncImage(
            model = remember(f, play) {
                ImageRequest.Builder(context)
                    .data(f)
                    .size(THUMB_WIDTH_PX, THUMB_HEIGHT_PX)
                    .precision(Precision.INEXACT)
                    .crossfade(!play)
                    .apply {
                        // Not playing: the first frame only, as a plain picture
                        if (!play) decoderFactory(BitmapFactoryDecoder.Factory())
                    }
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.matchParentSize(),
        )
        // Animated pages say so, until they play
        if (animated && !play) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Animation,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * KMK: Hold a tile of the grid and slide: the tile under the finger becomes the playing one ([onPick] with its
 * index). Letting go keeps it. A tap (open the page) and a normal swipe (scroll) work as before: only a hold that
 * stays still past the long-press time starts it, and from then on the grid doesn't scroll and no tile is tapped.
 */
fun Modifier.holdToPlay(state: LazyGridState, onPick: (Int) -> Unit): Modifier = pointerInput(state) {
    val longPress = viewConfiguration.longPressTimeoutMillis
    val slop = viewConfiguration.touchSlop
    fun indexAt(position: Offset): Int? = state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
        position.x >= item.offset.x && position.x < item.offset.x + item.size.width &&
            position.y >= item.offset.y && position.y < item.offset.y + item.size.height
    }?.index
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Wait out the long-press time: moving or lifting before then is a normal swipe or tap
        var released = false
        val timedOut = withTimeoutOrNull(longPress) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || (change.position - down.position).getDistance() > slop) {
                    released = true
                    break
                }
            }
        } == null
        if (!timedOut || released) return@awaitEachGesture
        indexAt(down.position)?.let(onPick)
        // Sliding: follow the finger, and keep the grid and the tiles out of it
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            indexAt(change.position)?.let(onPick)
        }
    }
}

/** Big enough to look sharp in a three-across grid, small enough to be cheap */
private const val THUMB_WIDTH_PX = 360
private const val THUMB_HEIGHT_PX = 520
