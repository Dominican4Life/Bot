package eu.kanade.presentation.components

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Animated pages (GIF, animated WebP) hold still on their first frame, with a GIF or WEBP label, and play only
 * while they're the [playing] one: the tile picked by holding and sliding over the grid ([holdToPlay]). Only one
 * plays at a time, so the page being read keeps its frame rate.
 */
@Composable
fun AnimatedThumbnail(model: Any, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val key = remember(model) { thumbKeyOf(model) }
    var drawable by remember(model) { mutableStateOf<Drawable?>(null) }
    var kind by remember(key) { mutableStateOf(key?.let { kinds[it] }) }
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
                if (key != null) kind = kinds[key]
            },
            modifier = Modifier.matchParentSize(),
        )
        val animatable = drawable as? Animatable
        // Still unless picked; picked plays from where it stopped
        LaunchedEffect(animatable, playing) {
            animatable ?: return@LaunchedEffect
            if (playing) animatable.start() else animatable.stop()
        }
        DisposableEffect(animatable) {
            onDispose { animatable?.stop() }
        }
        if (animatable != null && !playing) {
            Text(
                text = if (kind == Kind.WEBP) "WEBP" else "GIF",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
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
        if (key != null) runCatching { kinds[key] = kindOf(result.source.source()) }
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
