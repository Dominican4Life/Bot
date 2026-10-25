package eu.kanade.tachiyomi.data.coil

import android.content.Context
import coil3.fetch.SourceFetchResult
import coil3.imageLoader
import coil3.request.Options
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.Hash.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import java.io.File

/**
 * KMK: A copy of each page shown as a thumbnail in the page grids (the reader's Pages panel and the Browse preview
 * window), kept apart from the reader's own page cache. Big animated pages pushed each other out of that cache, so
 * scrolling back meant downloading them again; these copies stay until [MAX_BYTES] is reached, oldest used first out.
 */
object PageThumbFiles {

    private const val MAX_BYTES = 600L * 1024 * 1024

    /** Three pages are copied at a time, so the grid doesn't take the network and disk from the page being read */
    private val gate = Semaphore(3)

    /** What a page goes by here, or null when it can't be told apart from others */
    fun keyOf(model: Any): String? = when (model) {
        is ReaderPage -> runCatching { "reader;${model.chapter.chapter.id};${model.index}" }.getOrNull()
        is SourcePageThumbnail -> "source;${model.source.id};${model.chapterUrl};${model.page.index}"
        is LocalPageThumbnail -> "local;${model.chapterId};${model.index};${model.fileUri ?: model.archiveUri}"
        else -> null
    }

    private fun dir(context: Context) = File(context.cacheDir, "page_thumbs").apply { mkdirs() }

    private fun fileFor(context: Context, key: String) = File(dir(context), md5(key))

    /** The saved copy, when there is one */
    fun saved(context: Context, key: String): File? = fileFor(context, key).takeIf { it.exists() && it.length() > 0 }

    /** The page's copy, made from [model] (through the image loader's fetchers) when there's none yet */
    suspend fun get(context: Context, key: String, model: Any): File? {
        saved(context, key)?.let {
            it.setLastModified(System.currentTimeMillis())
            return it
        }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                saved(context, key)?.let { return@withContext it }
                val loader = context.imageLoader
                val options = Options(context)
                val fetcher = loader.components.newFetcher(model, options, loader)?.first ?: return@withContext null
                val result = fetcher.fetch() as? SourceFetchResult ?: return@withContext null
                val target = fileFor(context, key)
                val tmp = File(target.parentFile, target.name + ".tmp")
                runCatching {
                    result.source.source().use { input -> tmp.sink().buffer().use { it.writeAll(input) } }
                    tmp.renameTo(target)
                }.onFailure { tmp.delete() }
                trim(context)
                saved(context, key)
            }
        }
    }

    private fun trim(context: Context) {
        val files = dir(context).listFiles { f -> !f.name.endsWith(".tmp") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    enum class Kind { ANIMATED_WEBP, GIF, STILL }

    /** Whether the file is an animated WebP, a GIF, or anything else (shown as a still picture) */
    fun kindOf(file: File): Kind {
        val head = ByteArray(64)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (read >= 6 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()) {
            return Kind.GIF
        }
        if (read >= 21 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WEBP" && String(head, 12, 4, Charsets.US_ASCII) == "VP8X"
        ) {
            // VP8X flags: bit 1 means animated
            if (head[20].toInt() and 0x02 != 0) return Kind.ANIMATED_WEBP
        }
        return Kind.STILL
    }
}
