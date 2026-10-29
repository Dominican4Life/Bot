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
    // KMK: kept in the image disk cache, so a page shown once loads from the tablet after that
    private val imageLoader: ImageLoader,
) : Fetcher {

    private val diskCacheKey: String
        get() = diskCacheKeyOf(data.source.id, data.chapterUrl, data.page.index)

    override suspend fun fetch(): FetchResult = withIOContext {
        val diskCache = imageLoader.diskCache
        // KMK: a page downloaded before (for its still picture) plays from the copy on the tablet: before, tapping
        // an animated page downloaded the whole file again (5-20 s for a big one)
        diskCache?.openSnapshot(diskCacheKey)?.let { snapshot ->
            return@withIOContext SourceFetchResult(
                source = ImageSource(
                    file = snapshot.data,
                    fileSystem = diskCache.fileSystem,
                    diskCacheKey = diskCacheKey,
                    closeable = snapshot,
                ),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }
        val page = data.page
        if (page.imageUrl.isNullOrEmpty()) {
            page.imageUrl = data.source.getImageUrl(page)
        }
        val response = data.source.getImage(page)
        val body = checkNotNull(response.body) { "Empty response for page ${page.number}" }
        val editor = diskCache?.openEditor(diskCacheKey)
        if (diskCache != null && editor != null) {
            val snapshot = try {
                diskCache.fileSystem.write(editor.data) { body.source().use { writeAll(it) } }
                // KMK: the picture's address, so the reader can reuse this download (HttpPageLoader) only when it
                // is the same picture it would download
                page.imageUrl?.let { url -> diskCache.fileSystem.write(editor.metadata) { writeUtf8(url) } }
                editor.commitAndOpenSnapshot()
            } catch (e: Exception) {
                runCatching { editor.abort() }
                throw e
            } finally {
                runCatching { response.close() }
            }
            if (snapshot != null) {
                return@withIOContext SourceFetchResult(
                    source = ImageSource(
                        file = snapshot.data,
                        fileSystem = diskCache.fileSystem,
                        diskCacheKey = diskCacheKey,
                        closeable = snapshot,
                    ),
                    mimeType = body.contentType()?.toString(),
                    dataSource = DataSource.NETWORK,
                )
            }
            // The copy couldn't be opened again: load it once more, straight
            return@withIOContext SourceFetchResult(
                source = ImageSource(source = data.source.getImage(page).body.source(), fileSystem = options.fileSystem),
                mimeType = body.contentType()?.toString(),
                dataSource = DataSource.NETWORK,
            )
        }
        SourceFetchResult(
            source = ImageSource(
                source = body.source(),
                fileSystem = options.fileSystem,
            ),
            mimeType = body.contentType()?.toString(),
            dataSource = DataSource.NETWORK,
        )
    }

    companion object {
        /** KMK: also used by the reader (HttpPageLoader) to find a page the preview already downloaded */
        fun diskCacheKeyOf(sourceId: Long, chapterUrl: String, pageIndex: Int): String =
            "source-page;$sourceId;$chapterUrl;$pageIndex"
    }

    class Factory : Fetcher.Factory<SourcePageThumbnail> {
        override fun create(data: SourcePageThumbnail, options: Options, imageLoader: ImageLoader): Fetcher {
            return SourcePageFetcher(data, options, imageLoader)
        }
    }
}

class SourcePageKeyer : Keyer<SourcePageThumbnail> {
    override fun key(data: SourcePageThumbnail, options: Options): String {
        return SourcePageFetcher.diskCacheKeyOf(data.source.id, data.chapterUrl, data.page.index)
    }
}
