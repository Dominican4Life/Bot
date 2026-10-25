package eu.kanade.presentation.components

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.github.penfeizhou.animation.FrameAnimationDrawable
import com.github.penfeizhou.animation.gif.GifDrawable
import com.github.penfeizhou.animation.loader.FileLoader
import com.github.penfeizhou.animation.webp.WebPDrawable
import eu.kanade.tachiyomi.data.coil.PageThumbFiles
import eu.kanade.tachiyomi.util.system.AppLog
import java.io.File

/**
 * KMK: A page thumbnail for the page grids (the reader's Pages panel and the Browse preview window).
 *
 * The page is shown from its own saved copy ([PageThumbFiles]), so scrolling back doesn't load it again. An animated
 * page (GIF, animated WebP) plays through its own decoder, on its own threads and at a smaller size, not through
 * Android's, which plays every animation of the app on one thread: the tiles were making the page being read or
 * watched wait for its frames. It plays while [playing] (on screen) and stops on its frame otherwise.
 *
 * [canMake] false only shows a copy saved before (the reader's panel waits until the page is loaded).
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
            .onFailure { AppLog.add('W', "thumbs", "Couldn't keep page ${key}: ${it.message}") }
            .getOrNull()
    }
    // Nothing until the copy is there (the reader's panel shows its loading circle meanwhile)
    val f = file ?: return
    val kind = remember(f) { PageThumbFiles.kindOf(f) }
    if (kind == PageThumbFiles.Kind.STILL) {
        AsyncImage(
            model = remember(f) {
                ImageRequest.Builder(context)
                    .data(f)
                    .size(THUMB_WIDTH_PX, THUMB_HEIGHT_PX)
                    .precision(Precision.INEXACT)
                    .crossfade(true)
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = modifier,
        )
    } else {
        AnimatedPage(f, kind, playing, modifier)
    }
}

@Composable
private fun AnimatedPage(file: File, kind: PageThumbFiles.Kind, playing: Boolean, modifier: Modifier) {
    val drawable = remember(file) {
        runCatching {
            val loader = FileLoader(file.path)
            val d: FrameAnimationDrawable<*> = if (kind == PageThumbFiles.Kind.GIF) GifDrawable(loader) else WebPDrawable(loader)
            d.setAutoPlay(false)
            d
        }.getOrNull()
    } ?: return
    // Each new frame redraws this tile only
    var frame by remember(drawable) { mutableIntStateOf(0) }
    DisposableEffect(drawable) {
        val handler = Handler(Looper.getMainLooper())
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                frame++
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                handler.postAtTime(what, who, `when`)
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                handler.removeCallbacks(what, who)
            }
        }
        onDispose {
            drawable.stop()
            drawable.callback = null
        }
    }
    // The tile's size sets the decoder's (smaller) frame size, before it starts
    var tile by remember(drawable) { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(drawable, tile) {
        if (tile == IntSize.Zero) return@LaunchedEffect
        // Fill the tile, top kept (like the still thumbnails' crop)
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: tile.width
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: tile.height
        val scale = maxOf(tile.width / w.toFloat(), tile.height / h.toFloat())
        val sw = (w * scale).toInt()
        val sh = (h * scale).toInt()
        val left = (tile.width - sw) / 2
        drawable.setBounds(left, 0, left + sw, sh)
    }
    LaunchedEffect(drawable, playing, tile) {
        if (tile == IntSize.Zero) return@LaunchedEffect
        when {
            playing && drawable.isPaused -> drawable.resume()
            playing && !drawable.isRunning -> drawable.start()
            !playing && drawable.isRunning && !drawable.isPaused -> drawable.pause()
        }
    }
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .onSizeChanged { tile = it }
            .clipToBounds()
            .drawBehind {
                @Suppress("UNUSED_EXPRESSION")
                frame
                drawIntoCanvas { drawable.draw(it.nativeCanvas) }
            },
    )
}

/** Big enough to look sharp in a three-across grid, small enough to be cheap */
private const val THUMB_WIDTH_PX = 360
private const val THUMB_HEIGHT_PX = 520
