package eu.kanade.tachiyomi.data.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverFolders
import eu.kanade.tachiyomi.network.await
import okhttp3.Call
import okhttp3.Request
import okio.Buffer
import okio.FileSystem
import java.io.IOException

/**
 * KMK: An anime episode's preview image. For an anime in the library it's read from the anime's download folder
 * first (Settings › Downloads › Auto save covers), and kept there once loaded, like covers.
 */
data class EpisodePreview(
    val animeId: Long,
    val key: String,
    val url: String,
    val inLibrary: Boolean,
)

/** An episode's preview file name: its number, and a short mark of its address so two episodes never share one */
fun episodePreviewKey(episodeNumber: Double, episodeUrl: String): String {
    val number = when {
        episodeNumber < 0 -> "x"
        episodeNumber % 1.0 == 0.0 -> episodeNumber.toLong().toString()
        else -> episodeNumber.toString()
    }
    return "ep${number}_${Integer.toHexString(episodeUrl.hashCode())}"
}

class EpisodePreviewKeyer : Keyer<EpisodePreview> {
    override fun key(data: EpisodePreview, options: Options): String = data.url
}

class EpisodePreviewFetcher(
    private val data: EpisodePreview,
    private val options: Options,
    private val callFactory: Call.Factory,
    private val imageLoader: ImageLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // The anime's folder first
        if (data.inLibrary) {
            CoverFolders.readPreview(data.animeId, data.key)?.let { return result(it, DataSource.DISK) }
        }
        // Then the image cache
        val diskCache = imageLoader.diskCache
        diskCache?.openSnapshot(data.url)?.use { snapshot ->
            val bytes = diskCache.fileSystem.read(snapshot.data) { readByteArray() }
            if (bytes.isNotEmpty()) {
                if (data.inLibrary) CoverFolders.savePreview(data.animeId, data.key, bytes)
                return result(bytes, DataSource.DISK)
            }
        }
        // Then the internet
        val bytes = callFactory.newCall(Request.Builder().url(data.url).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.bytes()
        }
        diskCache?.openEditor(data.url)?.let { editor ->
            try {
                diskCache.fileSystem.write(editor.data) { write(bytes) }
                editor.commit()
            } catch (_: Exception) {
                editor.abort()
            }
        }
        if (data.inLibrary) CoverFolders.savePreview(data.animeId, data.key, bytes)
        return result(bytes, DataSource.NETWORK)
    }

    private fun result(bytes: ByteArray, source: DataSource) = SourceFetchResult(
        source = ImageSource(source = Buffer().write(bytes), fileSystem = FileSystem.SYSTEM),
        mimeType = "image/*",
        dataSource = source,
    )

    class Factory(private val callFactoryLazy: Lazy<Call.Factory>) : Fetcher.Factory<EpisodePreview> {
        override fun create(data: EpisodePreview, options: Options, imageLoader: ImageLoader): Fetcher =
            EpisodePreviewFetcher(data, options, callFactoryLazy.value, imageLoader)
    }
}
