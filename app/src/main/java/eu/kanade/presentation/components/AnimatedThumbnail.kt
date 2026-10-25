package eu.kanade.presentation.components

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import kotlinx.coroutines.Dispatchers

/**
 * KMK: A page thumbnail for the page grids (the reader's Pages panel and the Browse preview window).
 *
 * Animated pages keep animating, but cheaply:
 * - decoded at tile size, so each frame costs a small part of a full page's;
 * - playing only while [playing] (on screen in the grid), paused on the frame they were on otherwise, and picked
 *   up again from there;
 * - loaded two at a time, so opening the grid doesn't take the device away from the page being read or watched.
 */
@Composable
fun AnimatedThumbnail(
    model: Any,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var drawable by remember(model) { mutableStateOf<Drawable?>(null) }
    AsyncImage(
        model = remember(model) {
            ImageRequest.Builder(context)
                .data(model)
                .size(THUMB_WIDTH_PX, THUMB_HEIGHT_PX)
                .precision(Precision.INEXACT)
                .crossfade(false)
                .fetcherCoroutineContext(thumbnailDispatcher)
                .decoderCoroutineContext(thumbnailDispatcher)
                .build()
        },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        onState = { state ->
            drawable = (state as? AsyncImagePainter.State.Success)?.result?.image?.asDrawable(context.resources)
        },
        modifier = modifier,
    )
    val animatable = drawable as? Animatable
    LaunchedEffect(animatable, playing) {
        animatable ?: return@LaunchedEffect
        if (playing) {
            if (!animatable.isRunning) animatable.start()
        } else if (animatable.isRunning) {
            animatable.stop()
        }
    }
    DisposableEffect(animatable) {
        onDispose { if (animatable?.isRunning == true) animatable.stop() }
    }
}

/** Big enough to look sharp in a three-across grid on a tablet, small enough to be cheap to animate */
private const val THUMB_WIDTH_PX = 360
private const val THUMB_HEIGHT_PX = 520

/** Two thumbnails load at a time */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(2)
