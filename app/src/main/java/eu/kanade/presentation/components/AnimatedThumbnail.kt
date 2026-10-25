package eu.kanade.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.data.coil.PageFlipbooks
import kotlinx.coroutines.delay

/**
 * KMK: A page thumbnail for the page grids (the reader's Pages panel and the Browse preview window), drawn from
 * the page's flipbook ([PageFlipbooks]): small frames captured once, so a grid of animated pages costs almost
 * nothing to play and the page being read keeps its frame rate.
 *
 * It flips while [playing] (on screen in the grid), stays on its frame otherwise and goes on from there. [canMake]
 * false only shows a flipbook made before (the reader's panel waits until the page is loaded to make one).
 */
@Composable
fun AnimatedThumbnail(
    model: Any,
    playing: Boolean,
    modifier: Modifier = Modifier,
    canMake: Boolean = true,
) {
    val context = LocalContext.current
    val key = remember(model) { PageFlipbooks.keyOf(model) } ?: return
    var frames by remember(key) {
        mutableStateOf(PageFlipbooks.inMemory(key)?.frames?.map { it.asImageBitmap() })
    }
    var frameMs by remember(key) { mutableStateOf(100L) }
    LaunchedEffect(key, canMake) {
        if (frames != null) return@LaunchedEffect
        val book = PageFlipbooks.load(context, key)
            ?: if (canMake) PageFlipbooks.make(context, key, model) else null
        if (book != null) {
            frameMs = book.frameMs
            frames = book.frames.map { it.asImageBitmap() }
        }
    }
    val list = frames
    if (list.isNullOrEmpty()) {
        // While it's being captured: its first frame, as soon as there is one
        val first by PageFlipbooks.firstFrames.collectAsState()
        val bitmap: ImageBitmap? = remember(first[key]) { first[key]?.asImageBitmap() }
        bitmap?.let {
            Image(it, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = modifier)
        }
        return
    }
    var index by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(list, playing) {
        if (!playing || list.size < 2) return@LaunchedEffect
        while (true) {
            delay(frameMs)
            index = (index + 1) % list.size
        }
    }
    Image(
        bitmap = list[index.coerceIn(0, list.lastIndex)],
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier,
    )
}
