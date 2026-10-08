package eu.kanade.tachiyomi.data.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import okio.buffer
import okio.source

/**
 * KMK: Loads an already-loaded reader page (from the chapter cache, a download or an archive) so it can be
 * shown as a thumbnail in the reader's page grid.
 */
class ReaderPageFetcher(
    private val page: ReaderPage,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val stream = checkNotNull(page.stream) { "Page ${page.number} is not loaded yet" }
        return SourceFetchResult(
            source = ImageSource(
                source = stream().source().buffer(),
                fileSystem = options.fileSystem,
            ),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ReaderPage> {
        override fun create(data: ReaderPage, options: Options, imageLoader: ImageLoader): Fetcher {
            return ReaderPageFetcher(data, options)
        }
    }
}

class ReaderPageKeyer : Keyer<ReaderPage> {
    override fun key(data: ReaderPage, options: Options): String? {
        val chapterId = runCatching { data.chapter.chapter.id }.getOrNull() ?: return null
        return "reader-page;$chapterId;${data.index};${data.imageUrl.orEmpty()}"
    }
}
