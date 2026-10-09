@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
@file:OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)

package eu.kanade.tachiyomi.jarextension

import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** KMK: The app side of the Kotatsu ABI for one loaded JAR */
internal class KotatsuJarContext(
    private val factory: Method,
    private val sources: List<MangaSource>,
) : MangaLoaderContext() {

    private val parsers = ConcurrentHashMap<String, MangaParser>()

    override val cookieJar: CookieJar get() = JarHost.network.cookieJar

    /** Requests a parser makes are tagged with its source: let that parser intercept them */
    override val httpClient: OkHttpClient by lazy {
        JarHost.network.client.newBuilder()
            .addInterceptor { chain ->
                val source = chain.request().tag(MangaSource::class.java)
                val parser = source?.let { parsers[it.name] }
                parser?.intercept(chain) ?: chain.proceed(chain.request())
            }
            .build()
    }

    fun parser(source: MangaSource): MangaParser = parsers.getOrPut(source.name) {
        factory.isAccessible = true
        factory.invoke(null, source, this) as MangaParser
    }

    override fun newParserInstance(source: MangaSource): MangaParser {
        val match = sources.firstOrNull { it.name == source.name } ?: source
        return parser(match)
    }

    override fun newLinkResolver(link: HttpUrl): LinkResolver = object : LinkResolver {
        override val link: HttpUrl = link
        override suspend fun getSource(): MangaSource? = null
        override suspend fun getManga(): Manga? = null
    }

    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = JarHost.evaluateJs(null, script)

    override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
        JarHost.evaluateJs(baseUrl, script, timeout)

    override fun getConfig(source: MangaSource): MangaSourceConfig = object : MangaSourceConfig {
        override fun <T> get(key: ConfigKey<T>): T = JarHost.readConfig(source.name, key.key, key.defaultValue)
    }

    override fun getDefaultUserAgent(): String = JarHost.network.defaultUserAgentProvider()

    override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response =
        JarHost.redraw(response) { bitmap -> (redraw(KotatsuBitmap(bitmap)) as KotatsuBitmap).bitmap }

    override fun createBitmap(width: Int, height: Int): Bitmap = KotatsuBitmap(JarHost.createBitmap(width, height))
}

private class KotatsuBitmap(val bitmap: android.graphics.Bitmap) : Bitmap {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
        JarHost.draw(bitmap, (sourceBitmap as KotatsuBitmap).bitmap, src.toAndroid(), dst.toAndroid())
    }

    private fun Rect.toAndroid() = android.graphics.Rect(left, top, right, bottom)
}

/** KMK: [JParser] over a Kotatsu [MangaParser] */
internal class KotatsuParserBridge(private val parser: MangaParser) : JParser {

    private val source: MangaSource get() = parser.source
    private val knownTags = ConcurrentHashMap<String, MangaTag>()

    override val sortOrders: List<String> get() = parser.availableSortOrders.map { it.name }

    override val capabilities: JCapabilities
        get() = parser.filterCapabilities.let {
            JCapabilities(
                multipleTags = it.isMultipleTagsSupported,
                tagsExclusion = it.isTagsExclusionSupported,
                search = it.isSearchSupported,
                searchWithFilters = it.isSearchWithFiltersSupported,
                year = it.isYearSupported,
                yearRange = it.isYearRangeSupported,
                author = it.isAuthorSearchSupported,
            )
        }

    override val domain: String get() = parser.domain
    override val requestHeaders: Headers get() = parser.getRequestHeaders()

    override fun tag(key: String): JTag? = knownTags[key]?.let { JTag(it.title, it.key) }

    override suspend fun list(offset: Int, order: String, filter: JFilter): List<JManga> {
        val sortOrder = SortOrder.entries.firstOrNull { it.name == order } ?: parser.availableSortOrders.first()
        return parser.getList(offset, sortOrder, filter.toKotatsu()).map { it.toJ() }
    }

    override suspend fun details(manga: JManga): JManga = parser.getDetails(manga.toKotatsu()).toJ()

    override suspend fun pages(chapter: JChapter): List<JPage> =
        parser.getPages(chapter.toKotatsu()).map { JPage(it.url, it.preview, it) }

    override suspend fun pageUrl(page: JPage): String =
        parser.getPageUrl(page.raw as? MangaPage ?: MangaPage(parser.generateUid(page.url), page.url, page.preview, source))

    override suspend fun filterOptions(): JFilterOptions {
        val options = parser.getFilterOptions()
        options.availableTags.forEach { knownTags[it.key] = it }
        return JFilterOptions(
            tags = options.availableTags.sortedBy { it.title.lowercase() }.map { JTag(it.title, it.key) },
            states = options.availableStates.map { it.name },
            contentRatings = options.availableContentRating.map { it.name },
            types = options.availableContentTypes.map { it.name },
            demographics = options.availableDemographics.map { it.name },
            locales = options.availableLocales.toList(),
        )
    }

    override suspend fun related(manga: JManga): List<JManga> = parser.getRelatedManga(manga.toKotatsu()).map { it.toJ() }

    override fun intercept(chain: Interceptor.Chain): Response = parser.intercept(chain)

    // Mapping

    private fun Manga.toJ() = JManga(
        url = url,
        title = title,
        altTitles = altTitles,
        publicUrl = publicUrl,
        coverUrl = coverUrl,
        largeCoverUrl = largeCoverUrl,
        description = description,
        authors = authors,
        tags = tags.map { JTag(it.title, it.key) },
        state = state?.name,
        isNsfw = contentRating == ContentRating.ADULT,
        chapters = chapters?.map { it.toJ() },
        raw = this,
    )

    private fun MangaChapter.toJ() = JChapter(url, title, number, volume, scanlator, uploadDate, branch, this)

    private fun JManga.toKotatsu(): Manga = raw as? Manga ?: Manga(
        id = parser.generateUid(url),
        title = title,
        altTitles = altTitles,
        url = url,
        publicUrl = publicUrl.ifBlank { url.toAbsoluteUrl(parser.domain) },
        rating = RATING_UNKNOWN,
        contentRating = if (isNsfw) ContentRating.ADULT else null,
        coverUrl = coverUrl,
        tags = tags.map { MangaTag(it.title, it.key, source) }.toSet(),
        state = state?.let { s -> MangaState.entries.firstOrNull { it.name == s } },
        authors = authors,
        largeCoverUrl = largeCoverUrl,
        description = description,
        chapters = chapters?.map { it.toKotatsu() },
        source = source,
    )

    private fun JChapter.toKotatsu(): MangaChapter = raw as? MangaChapter ?: MangaChapter(
        id = parser.generateUid(url),
        title = title,
        number = number,
        volume = volume,
        url = url,
        scanlator = scanlator,
        uploadDate = uploadDate,
        branch = branch,
        source = source,
    )

    private fun JFilter.toKotatsu() = MangaListFilter(
        query = query,
        tags = tags.map { knownTags[it] ?: MangaTag(it, it, source) }.toSet(),
        tagsExclude = tagsExclude.map { knownTags[it] ?: MangaTag(it, it, source) }.toSet(),
        locale = locale,
        states = states.mapNotNull { s -> MangaState.entries.firstOrNull { it.name == s } }.toSet(),
        contentRating = contentRatings.mapNotNull { s -> ContentRating.entries.firstOrNull { it.name == s } }.toSet(),
        types = types.mapNotNull { s -> ContentType.entries.firstOrNull { it.name == s } }.toSet(),
        demographics = demographics.mapNotNull { s -> Demographic.entries.firstOrNull { it.name == s } }.toSet(),
        year = year,
        yearFrom = yearFrom,
        yearTo = yearTo,
        author = author,
    )
}

/** Reads what a Kotatsu source enum constant says about itself, tolerating older ABIs */
internal fun kotatsuSourceInfo(source: MangaSource, broken: Set<String>): JarSourceInfo {
    fun prop(name: String): Any? = runCatching {
        source.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(source)
    }.getOrNull()
    val title = prop("getTitle") as? String ?: source.name
    val locale = (prop("getLocale") as? String).orEmpty()
    val type = prop("getContentType")?.toString() ?: "MANGA"
    return JarSourceInfo(
        name = source.name,
        title = title,
        lang = locale.ifBlank { "all" },
        contentType = type,
        isNsfw = type == "HENTAI" || prop("isNsfw") == true,
        isBroken = source.name in broken,
        raw = source,
    )
}
