package eu.kanade.tachiyomi.data.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.Hash.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * KMK: Page thumbnails as small flipbooks, for the reader's Pages panel and the Browse preview window.
 *
 * Playing an animated page means decoding every frame at full size, even for a small tile, so a grid of them took
 * the device away from the page being read or watched. Instead each page is played once, in the background and one
 * page at a time, and captured as small frames (about ten a second, at most [MAX_FRAMES]). A tile then only flips
 * through those pictures, which costs almost nothing.
 *
 * The frames are kept in memory for the tiles in use and saved as one picture per page in the app's cache, apart
 * from the reader's own page cache, so scrolling back or opening the panel again shows them at once.
 */
object PageFlipbooks {

    class Flipbook(val frames: List<Bitmap>, val frameMs: Long) {
        val bytes: Int get() = frames.sumOf { it.allocationByteCount }
    }

    const val WIDTH = 240
    const val HEIGHT = 346
    private const val FRAME_MS = 100L
    private const val MAX_FRAMES = 50
    private const val MAX_CAPTURE_MS = 8_000L
    private const val MEMORY_BYTES = 120 * 1024 * 1024
    private const val DISK_BYTES = 300L * 1024 * 1024

    private val memory = object : LruCache<String, Flipbook>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Flipbook) = value.bytes
    }

    /** One page is captured at a time */
    private val worker = Mutex()

    private val _firstFrames = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

    /** The first frame of pages being captured, to show until their flipbook is ready */
    val firstFrames: StateFlow<Map<String, Bitmap>> = _firstFrames.asStateFlow()

    /** What a page goes by here, or null when it can't be told apart from others */
    fun keyOf(model: Any): String? = when (model) {
        is ReaderPage -> runCatching { "reader;${model.chapter.chapter.id};${model.index}" }.getOrNull()
        is SourcePageThumbnail -> "source;${model.source.id};${model.chapterUrl};${model.page.index}"
        is LocalPageThumbnail -> "local;${model.chapterId};${model.index};${model.fileUri ?: model.archiveUri}"
        else -> null
    }

    fun inMemory(key: String): Flipbook? = memory.get(key)

    private fun dir(context: Context) = File(context.cacheDir, "page_flipbooks").apply { mkdirs() }

    private fun file(context: Context, key: String) = File(dir(context), md5(key) + ".jpg")

    /** True when the page was captured before (in memory or saved) */
    fun has(context: Context, key: String): Boolean = memory.get(key) != null || file(context, key).exists()

    /** The page's flipbook from memory or from its saved picture */
    suspend fun load(context: Context, key: String): Flipbook? {
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(context, key)
            if (!f.exists()) return@withContext null
            runCatching { readSheet(f) }.getOrNull()?.also {
                memory.put(key, it)
                f.setLastModified(System.currentTimeMillis())
            }
        }
    }

    /** Captures the page (or waits its turn to), saves it and returns it; null when the page couldn't be read */
    suspend fun make(context: Context, key: String, model: Any): Flipbook? = worker.withLock {
        load(context, key)?.let { return@withLock it }
        val drawable = decode(context, model) ?: return@withLock null
        val frames = capture(key, drawable)
        _firstFrames.update { it - key }
        if (frames.isEmpty()) return@withLock null
        val book = Flipbook(frames, FRAME_MS)
        memory.put(key, book)
        withContext(Dispatchers.IO) {
            runCatching { writeSheet(file(context, key), frames) }
            trimDisk(context)
        }
        book
    }

    /** The page as a drawable at tile size (an animated one stays animated) */
    private suspend fun decode(context: Context, model: Any): Drawable? {
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(WIDTH, HEIGHT)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        return result.image.asDrawable(context.resources)
    }

    /** Plays the drawable once and takes a frame every [FRAME_MS]; a still picture gives one frame */
    private suspend fun capture(key: String, drawable: Drawable): List<Bitmap> = withContext(Dispatchers.Main) {
        // Fill the tile, cut at the sides or the bottom, top kept (like the tiles' crop)
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: WIDTH
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: HEIGHT
        val scale = maxOf(WIDTH / w.toFloat(), HEIGHT / h.toFloat())
        val sw = (w * scale).toInt()
        val sh = (h * scale).toInt()
        val left = (WIDTH - sw) / 2
        drawable.setBounds(left, 0, left + sw, sh)
        val animatable = drawable as? Animatable
        if (animatable == null) return@withContext listOf(snapshot(drawable))
        var ended = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            drawable.repeatCount = 0
        }
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && drawable is Animatable2) {
            object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(d: Drawable?) {
                    ended = true
                }
            }.also { drawable.registerAnimationCallback(it) }
        } else {
            null
        }
        val frames = mutableListOf<Bitmap>()
        try {
            animatable.start()
            val start = System.currentTimeMillis()
            while (!ended && frames.size < MAX_FRAMES && System.currentTimeMillis() - start < MAX_CAPTURE_MS) {
                frames += snapshot(drawable)
                if (frames.size == 1) _firstFrames.update { it + (key to frames.first()) }
                delay(FRAME_MS)
            }
        } finally {
            animatable.stop()
            if (callback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (drawable as Animatable2).unregisterAnimationCallback(callback)
            }
        }
        frames
    }

    private fun snapshot(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    // One picture per page: the frames side by side, rows of [cols]; the frame count is in the file name's sidecar
    private fun writeSheet(target: File, frames: List<Bitmap>) {
        val cols = ceil(sqrt(frames.size.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(frames.size / cols.toDouble()).toInt().coerceAtLeast(1)
        val sheet = Bitmap.createBitmap(cols * WIDTH, rows * HEIGHT, Bitmap.Config.RGB_565)
        val canvas = Canvas(sheet)
        frames.forEachIndexed { i, frame ->
            canvas.drawBitmap(frame, ((i % cols) * WIDTH).toFloat(), ((i / cols) * HEIGHT).toFloat(), null)
        }
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { sheet.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        sheet.recycle()
        File(target.parentFile, target.name + ".n").writeText(frames.size.toString())
        tmp.renameTo(target)
    }

    private fun readSheet(source: File): Flipbook? {
        val count = File(source.parentFile, source.name + ".n").takeIf { it.exists() }
            ?.readText()?.trim()?.toIntOrNull() ?: 1
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val sheet = BitmapFactory.decodeFile(source.path, options) ?: return null
        val cols = (sheet.width / WIDTH).coerceAtLeast(1)
        val frames = (0 until count).mapNotNull { i ->
            val x = (i % cols) * WIDTH
            val y = (i / cols) * HEIGHT
            if (x + WIDTH > sheet.width || y + HEIGHT > sheet.height) return@mapNotNull null
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565).also {
                Canvas(it).drawBitmap(sheet, Rect(x, y, x + WIDTH, y + HEIGHT), Rect(0, 0, WIDTH, HEIGHT), null)
            }
        }
        sheet.recycle()
        return Flipbook(frames, FRAME_MS).takeIf { frames.isNotEmpty() }
    }

    /** Keeps the saved flipbooks under [DISK_BYTES], oldest used first out */
    private fun trimDisk(context: Context) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DISK_BYTES) break
            total -= f.length()
            f.delete()
            File(f.parentFile, f.name + ".n").delete()
        }
    }
}
