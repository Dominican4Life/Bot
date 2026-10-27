package eu.kanade.tachiyomi.data.coil

import android.os.SystemClock
import coil3.EventListener
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import logcat.LogPriority
import tachiyomi.domain.manga.model.Manga as DomainManga
import tachiyomi.domain.manga.model.MangaCover as DomainMangaCover
import java.util.Collections
import java.util.WeakHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * KMK: What the image loader writes to the Logs screen.
 *
 * - A cover that downloaded but couldn't be shown (a broken or unreadable file), with the reason. Covers that failed
 *   to download are already written by [MangaCoverFetcher], so they aren't written twice.
 * - Page tiles of the Pages panel and the preview window that took long to show, and every tile that was tapped to
 *   play, with where the time went: waiting for a free reader, reading the file, waiting for a free decoder, decoding.
 */
object ImageEvents : EventListener.Factory {

    /** Errors [MangaCoverFetcher] has written already */
    private val logged: MutableSet<Throwable> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun markLogged(error: Throwable) {
        logged += error
    }

    override fun create(request: ImageRequest): EventListener = when (val data = request.data) {
        is DomainMangaCover -> CoverListener(data.url)
        is DomainManga -> CoverListener(data.thumbnailUrl)
        is ReaderPage -> TileListener(data.number)
        is SourcePageThumbnail -> TileListener(data.page.index + 1)
        is LocalPageThumbnail -> TileListener(data.index + 1)
        else -> EventListener.NONE
    }

    private class CoverListener(private val url: String?) : EventListener() {
        override fun onError(request: ImageRequest, result: ErrorResult) {
            val error = result.throwable
            if (error is CancellationException || error in logged || url.isNullOrBlank()) return
            logcat.logcat("cover", LogPriority.WARN) {
                "Cover downloaded but couldn't be shown (${error.javaClass.simpleName}: ${error.message}): $url"
            }
        }
    }

    private class TileListener(private val number: Int) : EventListener() {
        private var startAt = 0L
        private var fetchStartAt = 0L
        private var fetchEndAt = 0L
        private var decodeStartAt = 0L
        private var decodeEndAt = 0L
        private var decoderName = ""

        private fun now() = SystemClock.uptimeMillis()

        override fun onStart(request: ImageRequest) {
            startAt = now()
        }

        override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
            fetchStartAt = now()
        }

        override fun fetchEnd(request: ImageRequest, fetcher: Fetcher, options: Options, result: FetchResult?) {
            fetchEndAt = now()
        }

        override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
            decodeStartAt = now()
            decoderName = decoder.javaClass.simpleName
        }

        override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
            decodeEndAt = now()
        }

        private fun playing(request: ImageRequest) = request.memoryCacheKeyExtras["thumb_play"] == "true"

        private fun steps(): String {
            val parts = mutableListOf<String>()
            if (fetchStartAt > 0) parts += "waiting for a reader ${fetchStartAt - startAt} ms"
            if (fetchEndAt > 0) parts += "reading the file ${fetchEndAt - fetchStartAt} ms"
            if (decodeStartAt > 0) parts += "waiting for a decoder ${decodeStartAt - fetchEndAt} ms"
            if (decodeEndAt > 0) parts += "decoding ${decodeEndAt - decodeStartAt} ms ($decoderName)"
            return parts.joinToString(", ")
        }

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            if (result.dataSource == DataSource.MEMORY_CACHE) return
            val total = now() - startAt
            val playing = playing(request)
            if (!playing && total < SLOW_MS) return
            logcat.logcat("pages", LogPriority.INFO) {
                "Page $number ${if (playing) "playing" else "still frame"} shown in $total ms: ${steps()}"
            }
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            val error = result.throwable
            if (error is CancellationException) return
            logcat.logcat("pages", LogPriority.WARN) {
                "Page $number ${if (playing(request)) "playing" else "still frame"} failed after " +
                    "${now() - startAt} ms (${error.javaClass.simpleName}: ${error.message}): ${steps()}"
            }
        }

        override fun onCancel(request: ImageRequest) {
            if (!playing(request)) return
            logcat.logcat("pages", LogPriority.INFO) {
                "Page $number playing stopped before it showed, after ${now() - startAt} ms: ${steps()}"
            }
        }
    }

    /** A still tile that took longer than this is written to Logs */
    private const val SLOW_MS = 1500L
}
