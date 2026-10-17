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
import mihon.core.archive.archiveReader
import okio.Buffer
import okio.buffer
import okio.source
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

    override suspend fun fetch(): FetchResult = withIOContext {
        val context = Injekt.get<Application>()
        val source = when {
            data.fileUri != null -> checkNotNull(context.contentResolver.openInputStream(data.fileUri)) {
                "Can't open page ${data.index + 1}"
            }.source().buffer()
            data.archiveUri != null && data.entryName != null -> {
                val file = checkNotNull(UniFile.fromUri(context, data.archiveUri)) { "Archive is gone" }
                // Copy the entry out so the archive can be closed right away
                file.archiveReader(context).use { reader ->
                    val input = checkNotNull(reader.getInputStream(data.entryName)) { "Missing ${data.entryName}" }
                    Buffer().also { buffer -> input.use { buffer.readFrom(it) } }
                }
            }
            else -> error("Nothing to load for page ${data.index + 1}")
        }
        SourceFetchResult(
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

class LocalPageKeyer : Keyer<LocalPageThumbnail> {
    override fun key(data: LocalPageThumbnail, options: Options): String {
        return "local-page;${data.chapterId};${data.index};${data.fileUri ?: data.archiveUri}"
    }
}
