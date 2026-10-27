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
import androidx.compose.material3.CircularProgressIndicator
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
import coil3.memory.MemoryCache
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
    // Animated or not, read from the file's first bytes while it decodes (remembered per page)
    var animated by remember(model) { mutableStateOf(key?.let { kinds[it] }?.animated) }
    var drawable by remember(model) { mutableStateOf<Drawable?>(null) }
    // KMK: the still frame's place in memory, shown under the frame while the moving picture loads (no blank tile)
    var stillKey by remember(model) { mutableStateOf<MemoryCache.Key?>(null) }
    // The copies r66–r70 kept of shown pages aren't used any more: cleared once, to give the space back
    LaunchedEffect(Unit) {
        if (oldCopiesCleared.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) { File(context.cacheDir, "page_thumbs").deleteRecursively() }
        }
    }
    LaunchedEffect(animated) {
        animated?.let(onAnimated)
    }
    // Only the playing tile decodes the moving picture (it keeps the whole file in memory while shown); every other
    // tile is the first frame as a small plain picture. Kept apart in memory, so a still one isn't reused to play.
    val play = playing && animated == true
    Box(modifier = modifier) {
        AsyncImage(
            model = remember(model, play) {
                ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(!play)
                    .memoryCacheKeyExtra("thumb_play", play.toString())
                    .apply { if (play) placeholderMemoryCacheKey(stillKey) }
                    .decoderFactory(
                        KindDecoderFactory(key, animate = play) { kind -> animated = kind.animated },
                    )
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            onState = { state ->
                val success = state as? AsyncImagePainter.State.Success
                drawable = success?.result?.image?.asDrawable(context.resources)
                if (success != null && !play) stillKey = success.result.memoryCacheKey
            },
            modifier = Modifier.matchParentSize(),
        )
        val animatable = drawable as? Animatable
        LaunchedEffect(animatable, play) {
            animatable ?: return@LaunchedEffect
            if (play) animatable.start() else animatable.stop()
        }
        DisposableEffect(animatable) {
            // KMK: counted for the memory lines in Logs (each holds its whole animated file)
            if (animatable != null) AnimatedTiles.count.incrementAndGet()
            onDispose {
                animatable?.stop()
                if (animatable != null) AnimatedTiles.count.decrementAndGet()
            }
        }
        if (play && animatable == null) {
            // KMK: the moving picture is still loading: a small circle over the still frame
            CircularProgressIndicator(
                color = Color.White,
                trackColor = Color.Black.copy(alpha = 0.4f),
                strokeWidth = 2.5.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            )
        }
        if (animated == true && !play) {
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

/** KMK: page tiles showing a moving picture right now (for the memory lines in Logs) */
object AnimatedTiles {
    val count = java.util.concurrent.atomic.AtomicInteger(0)
}

private enum class Kind(val animated: Boolean) {
    GIF(true),
    ANIMATED_WEBP(true),
    STILL(false),
}

/** What each page turned out to be, so the label is right when the picture comes back from memory */
private val kinds = ConcurrentHashMap<String, Kind>()

private fun thumbKeyOf(model: Any): String? = when (model) {
    is ReaderPage -> runCatching { "reader;${model.chapter.chapter.id};${model.index}" }.getOrNull()
    is SourcePageThumbnail -> "source;${model.source.id};${model.chapterUrl};${model.page.index}"
    is LocalPageThumbnail -> "local;${model.chapterId};${model.index};${model.fileUri ?: model.archiveUri}"
    else -> null
}

/** From the first bytes: a GIF, an animated WebP (VP8X with its animation flag), or a still picture */
private fun kindOf(source: BufferedSource): Kind {
    val peek = source.peek()
    if (!peek.request(21)) return Kind.STILL
    val head = peek.readByteArray(21)
    if (head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()) return Kind.GIF
    if (String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" &&
        String(head, 12, 4, Charsets.US_ASCII) == "VP8X" && (head[20].toInt() and 0x02) != 0
    ) {
        return Kind.ANIMATED_WEBP
    }
    return Kind.STILL
}

/**
 * The app's usual decoders, noting on the way what the page is. [animate] false (a still tile) decodes only the
 * first frame as a plain picture at tile size; true (the playing tile) decodes the moving picture.
 */
private class KindDecoderFactory(
    private val key: String?,
    private val animate: Boolean,
    private val onKind: (Kind) -> Unit,
) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val kind = runCatching { kindOf(result.source.source()) }.getOrDefault(Kind.STILL)
        key?.let { kinds[it] = kind }
        onKind(kind)
        TachiyomiImageDecoder.Factory().create(result, options, imageLoader)?.let { return it }
        if (animate && kind.animated) {
            val moving: Decoder.Factory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AnimatedImageDecoder.Factory()
            } else {
                GifDecoder.Factory()
            }
            moving.create(result, options, imageLoader)?.let { return it }
        }
        return BitmapFactoryDecoder.Factory().create(result, options, imageLoader)
    }
}
