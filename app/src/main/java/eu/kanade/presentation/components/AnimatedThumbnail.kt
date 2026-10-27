package eu.kanade.presentation.components

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.crossfade
import eu.kanade.tachiyomi.data.coil.LocalPageThumbnail
import eu.kanade.tachiyomi.data.coil.SourcePageThumbnail
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KMK: A page thumbnail for the page grids (the reader's Pages panel and the Browse preview window), loaded the
 * same way as in r63 (straight from the page, through the image loader).
 *
 * Animated pages (GIF, animated WebP) hold still on their first frame, with a small play button in the corner
 * (the video one, smaller), and play while [playing]: the first tap on one plays it, the second opens it. Only one
 * plays at a time, so the page being read keeps its frame rate. [onAnimated] says once whether the page moves.
 */
@Composable
fun AnimatedThumbnail(
    model: Any,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onAnimated: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val key = remember(model) { thumbKeyOf(model) }
    var drawable by remember(model) { mutableStateOf<Drawable?>(null) }
    // The copies r66–r70 kept of shown pages aren't used any more: cleared once, to give the space back
    LaunchedEffect(Unit) {
        if (oldCopiesCleared.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) { File(context.cacheDir, "page_thumbs").deleteRecursively() }
        }
    }
    Box(modifier = modifier) {
        AsyncImage(
            model = remember(model) {
                ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(true)
                    .decoderFactory(KindDecoderFactory(key))
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            onState = { state ->
                drawable = (state as? AsyncImagePainter.State.Success)?.result?.image?.asDrawable(context.resources)
            },
            modifier = Modifier.matchParentSize(),
        )
        val animatable = drawable as? Animatable
        LaunchedEffect(drawable) {
            if (drawable != null) onAnimated(animatable != null)
        }
        // Still unless picked; picked plays from where it stopped
        LaunchedEffect(animatable, playing) {
            animatable ?: return@LaunchedEffect
            if (playing) animatable.start() else animatable.stop()
        }
        DisposableEffect(animatable) {
            onDispose { animatable?.stop() }
        }
        if (animatable != null && !playing) {
            // The reader's video play button (dark circle, white ring, white arrow), smaller
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 170f / 255f))
                    .border(1.dp, Color.White.copy(alpha = 200f / 255f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

private val oldCopiesCleared = AtomicBoolean(false)

private enum class Kind { GIF, WEBP, OTHER }

/** What each page turned out to be, so the label is right when the picture comes back from memory */
private val kinds = ConcurrentHashMap<String, Kind>()

private fun thumbKeyOf(model: Any): String? = when (model) {
    is ReaderPage -> runCatching { "reader;${model.chapter.chapter.id};${model.index}" }.getOrNull()
    is SourcePageThumbnail -> "source;${model.source.id};${model.chapterUrl};${model.page.index}"
    is LocalPageThumbnail -> "local;${model.chapterId};${model.index};${model.fileUri ?: model.archiveUri}"
    else -> null
}

private fun kindOf(source: BufferedSource): Kind {
    val peek = source.peek()
    if (!peek.request(21)) return Kind.OTHER
    val head = peek.readByteArray(21)
    if (head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()) return Kind.GIF
    if (String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP") {
        return Kind.WEBP
    }
    return Kind.OTHER
}

/** The app's usual decoders, in the usual order, noting on the way whether the page is a GIF or a WebP */
private class KindDecoderFactory(private val key: String?) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        key?.let { k -> runCatching { kinds[k] = kindOf(result.source.source()) } }
        val animated: Decoder.Factory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            AnimatedImageDecoder.Factory()
        } else {
            GifDecoder.Factory()
        }
        return TachiyomiImageDecoder.Factory().create(result, options, imageLoader)
            ?: animated.create(result, options, imageLoader)
            ?: BitmapFactoryDecoder.Factory().create(result, options, imageLoader)
    }
}
