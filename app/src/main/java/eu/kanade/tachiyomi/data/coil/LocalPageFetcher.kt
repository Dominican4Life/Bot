package eu.kanade.tachiyomi.data.coil

import android.app.Application
import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.hippo.unifile.UniFile
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okio.source
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KMK: A page of a downloaded chapter, read from disk instead of the source. It is either a single
 * image file ([fileUri]) or an entry of a CBZ archive ([archiveUri] + [entryName]).
 */
data class LocalPageThumbnail(
    val chapterId: Long,
    val index: Int,
    val fileUri: Uri? = null,
    val archiveUri: Uri? = null,
    val entryName: String? = null,
    // KMK: this page has a saved video: the Browse page panel shows ▶ on it
    val isVideo: Boolean = false,
)

class LocalPageFetcher(
    private val data: LocalPageThumbnail,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // KMK (r88): Coil closes the result's source once it has it (EngineInterceptor), but a fetch cancelled while
        // withIOContext hands the result back drops it unseen, which kept the archive mapped. Close it here then.
        var opened: Closeable? = null
        try {
            return withIOContext { openSource { opened = it } }
        } catch (e: Throwable) {
            opened?.let { runCatching { it.close() } }
            throw e
        }
    }

    private fun openSource(onOpened: (Closeable) -> Unit): FetchResult {
        val context = Injekt.get<Application>()
        val source = when {
            data.fileUri != null -> checkNotNull(context.contentResolver.openInputStream(data.fileUri)) {
                "Can't open page ${data.index + 1}"
            }.source().buffer()
            data.archiveUri != null && data.entryName != null -> {
                val file = checkNotNull(UniFile.fromUri(context, data.archiveUri)) { "Archive is gone" }
                // KMK: the entry is streamed to Coil instead of being copied whole into memory (up to 8 tiles at
                // once could hold 20-50 MB GIFs each). The archive stays open until the stream is read or closed.
                val reader = file.archiveReader(context)
                val input = try {
                    reader.getInputStream(data.entryName)
                } catch (e: Throwable) {
                    reader.close()
                    throw e
                }
                if (input == null) {
                    reader.close()
                    error("Missing ${data.entryName}")
                }
                ArchiveEntrySource(input, reader).buffer()
            }
            else -> error("Nothing to load for page ${data.index + 1}")
        }
        onOpened(source)
        return SourceFetchResult(
            source = ImageSource(source = source, fileSystem = options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<LocalPageThumbnail> {
        override fun create(data: LocalPageThumbnail, options: Options, imageLoader: ImageLoader): Fetcher {
            return LocalPageFetcher(data, options)
        }
    }
}

/**
 * KMK: One archive entry as a stream. The archive (a memory-mapped file) is closed once, either when the entry has
 * been read to its end (Coil may copy a source to a temp file without closing it) or when the source is closed.
 */
private class ArchiveEntrySource(
    private val input: InputStream,
    private val reader: ArchiveReader,
) : ForwardingSource(input.source()) {
    private val released = AtomicBoolean(false)

    override fun read(sink: Buffer, byteCount: Long): Long {
        // Never read a freed archive again (okio may ask once more after the end)
        if (released.get()) return -1L
        val read = super.read(sink, byteCount)
        if (read == -1L) release()
        return read
    }

    override fun close() {
        try {
            super.close()
        } finally {
            release()
        }
    }

    private fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { reader.close() }
    }
}

class LocalPageKeyer : Keyer<LocalPageThumbnail> {
    override fun key(data: LocalPageThumbnail, options: Options): String {
        return "local-page;${data.chapterId};${data.index};${data.fileUri ?: data.archiveUri}"
    }
}
