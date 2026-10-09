@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package eu.kanade.tachiyomi.jarextension

import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import tsuki.MangaLoaderContext
import tsuki.MangaParser
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig
import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Demographic
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaPage
import tsuki.model.MangaSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.SortOrder
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** KMK: The app side of the Tsuki (Usagi) ABI for one loaded JAR */
internal class TsukiJarContext(
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

    override fun getParserSources(): List<MangaSource> = sources

    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = JarHost.evaluateJs(null, script)

    override suspend fun evaluateJs(baseUrl: String, script: String): String? = JarHost.evaluateJs(baseUrl, script)

    override fun getConfig(source: MangaSource): MangaSourceConfig = object : MangaSourceConfig {
        override fun <T> get(key: ConfigKey<T>): T = JarHost.readConfig(source.name, key.key, key.defaultValue)
    }

    override fun getDefaultUserAgent(): String = JarHost.network.defaultUserAgentProvider()

    override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response =
        JarHost.redraw(response) { bitmap -> (redraw(TsukiBitmap(bitmap)) as TsukiBitmap).bitmap }

    override fun createBitmap(width: Int, height: Int): Bitmap = TsukiBitmap(JarHost.createBitmap(width, height))
}

private class TsukiBitmap(val bitmap: android.graphics.Bitmap) : Bitmap {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
        JarHost.draw(bitmap, (sourceBitmap as TsukiBitmap).bitmap, src.toAndroid(), dst.toAndroid())
    }

    private fun Rect.toAndroid() = android.graphics.Rect(left, top, right, bottom)
}

/** KMK: [JParser] over a Tsuki [MangaParser] */
internal class TsukiParserBridge(private val parser: MangaParser) : JParser {

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
        return parser.getList(offset, sortOrder, filter.toTsuki()).map { it.toJ() }
    }

    override suspend fun details(manga: JManga): JManga = parser.getDetails(manga.toTsuki()).toJ()

    override suspend fun pages(chapter: JChapter): List<JPage> =
        parser.getPages(chapter.toTsuki()).map { JPage(it.url, it.preview, it) }

    override suspend fun pageUrl(page: JPage): String =
        parser.getPageUrl(page.raw as? MangaPage ?: MangaPage(jarUid(source.name, page.url), page.url, page.preview, source))

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

    override suspend fun related(manga: JManga): List<JManga> = parser.getRelatedManga(manga.toTsuki()).map { it.toJ() }

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

    private fun JManga.toTsuki(): Manga = raw as? Manga ?: Manga(
        id = jarUid(source.name, url),
        title = title,
        altTitles = altTitles,
        url = url,
        publicUrl = publicUrl.ifBlank { jarAbsoluteUrl(url, parser.domain) },
        rating = -1f,
        contentRating = if (isNsfw) ContentRating.ADULT else null,
        coverUrl = coverUrl,
        tags = tags.map { MangaTag(it.title, it.key, source) }.toSet(),
        state = state?.let { s -> MangaState.entries.firstOrNull { it.name == s } },
        authors = authors,
        largeCoverUrl = largeCoverUrl,
        description = description,
        chapters = chapters?.map { it.toTsuki() },
        source = source,
    )

    private fun JChapter.toTsuki(): MangaChapter = raw as? MangaChapter ?: MangaChapter(
        id = jarUid(source.name, url),
        title = title,
        number = number,
        volume = volume,
        url = url,
        scanlator = scanlator,
        uploadDate = uploadDate,
        branch = branch,
        source = source,
    )

    private fun JFilter.toTsuki() = MangaListFilter(
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

/** Reads what a Tsuki source enum constant says about itself, tolerating older ABIs */
internal fun tsukiSourceInfo(source: MangaSource, broken: Set<String>): JarSourceInfo {
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

/** Same id scheme as Kotatsu's generateUid: stable per source and url */
internal fun jarUid(sourceName: String, url: String): Long {
    var h = 1125899906842597L
    sourceName.forEach { h = 31 * h + it.code }
    url.forEach { h = 31 * h + it.code }
    return h
}

internal fun jarAbsoluteUrl(url: String, domain: String): String = when {
    url.startsWith("http://") || url.startsWith("https://") -> url
    url.startsWith("//") -> "https:$url"
    url.startsWith("/") -> "https://$domain$url"
    else -> "https://$domain/$url"
}
