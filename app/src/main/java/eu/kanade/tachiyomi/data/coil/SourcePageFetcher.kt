package eu.kanade.tachiyomi.data.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.core.common.util.lang.withIOContext

/**
 * KMK: A page of a chapter that hasn't been opened in the reader, loaded straight from its source.
 * Used for the page previews when browsing a source.
 */
data class SourcePageThumbnail(
    val source: HttpSource,
    val chapterUrl: String,
    val page: Page,
)

class SourcePageFetcher(
    private val data: SourcePageThumbnail,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withIOContext {
        val page = data.page
        if (page.imageUrl.isNullOrEmpty()) {
            page.imageUrl = data.source.getImageUrl(page)
        }
        val response = data.source.getImage(page)
        val body = checkNotNull(response.body) { "Empty response for page ${page.number}" }
        SourceFetchResult(
            source = ImageSource(
                source = body.source(),
                fileSystem = options.fileSystem,
            ),
            mimeType = body.contentType()?.toString(),
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<SourcePageThumbnail> {
        override fun create(data: SourcePageThumbnail, options: Options, imageLoader: ImageLoader): Fetcher {
            return SourcePageFetcher(data, options)
        }
    }
}

class SourcePageKeyer : Keyer<SourcePageThumbnail> {
    override fun key(data: SourcePageThumbnail, options: Options): String {
        return "source-page;${data.source.id};${data.chapterUrl};${data.page.index}"
    }
}
