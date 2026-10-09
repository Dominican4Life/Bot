package eu.kanade.tachiyomi.jarextension

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: A source from a Kotatsu / Tsuki parser JAR, as a regular Komikku source: browse, latest, search with the
 * parser's filters, details, chapters and pages all go through the parser.
 */
class JarSource(
    private val jar: LoadedJar,
    val info: JarSourceInfo,
    /** The installed JAR package this source comes from */
    val pkgName: String,
) : HttpSource() {

    private val parser: JParser by lazy { jar.parser(info) }

    override val name: String = info.title
    override val lang: String = normalizeLang(info.lang)
    override val id: Long = generateId("jar/${info.name}", lang, 1)
    override val versionId: Int = 1

    override val supportsLatest: Boolean
        get() = runCatching { "UPDATED" in parser.sortOrders }.getOrDefault(false)

    override val baseUrl: String
        get() = runCatching { "https://${parser.domain}" }.getOrDefault("")

    /** Every request this source makes (images too) goes through the parser, for its headers and fixes */
    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor { chain -> parser.intercept(chain) }
            .build()
    }

    override val headers: Headers by lazy {
        val base = headersBuilder()
        runCatching { parser.requestHeaders }.getOrNull()?.forEach { (key, value) -> base.set(key, value) }
        base.build()
    }

    // Objects from the parser, kept so they can be handed back as they were
    private val mangas = BoundedCache<JManga>()
    private val chapters = BoundedCache<JChapter>()
    private val pages = BoundedCache<JPage>()

    /** Where each page of a listing starts: offsets of earlier pages, per sort + filter */
    private val offsets = ConcurrentHashMap<String, MutableMap<Int, Int>>()

    // Browse

    override suspend fun getPopularManga(page: Int): MangasPage =
        listPage(page, popularOrder(), JFilter())

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        listPage(page, "UPDATED", JFilter())

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val (order, filter) = filters.toJFilter(query.trim().takeIf { it.isNotEmpty() })
        return listPage(page, order, filter)
    }

    private suspend fun listPage(page: Int, order: String, filter: JFilter): MangasPage {
        val key = "$order|$filter"
        val known = offsets.getOrPut(key) { ConcurrentHashMap() }
        if (page == 1) known.clear()
        val offset = if (page == 1) 0 else known[page] ?: 0
        val result = parser.list(offset, order, filter)
        known[page + 1] = offset + result.size
        result.forEach { mangas.put(it.url, it) }
        return MangasPage(result.map { it.toSManga() }, hasNextPage = result.isNotEmpty())
    }

    private fun popularOrder(): String {
        val orders = runCatching { parser.sortOrders }.getOrDefault(emptyList())
        return listOf("POPULARITY", "POPULARITY_WEEK", "POPULARITY_MONTH", "RATING", "UPDATED")
            .firstOrNull { it in orders }
            ?: orders.firstOrNull()
            ?: "POPULARITY"
    }

    // Details

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = parser.details(mangas.get(manga.url) ?: manga.toJManga())
        mangas.put(details.url, details)
        val newChapters = details.chapters.orEmpty()
            .onEach { this.chapters.put(it.url, it) }
            .asReversed()
            .map { it.toSChapter() }
        return SMangaUpdate(
            manga = if (fetchDetails) details.toSManga().apply { initialized = true } else manga,
            chapters = if (fetchChapters) newChapters else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val source = chapters.get(chapter.url) ?: JChapter(
            url = chapter.url,
            title = chapter.name,
            number = chapter.chapter_number,
            volume = 0,
            scanlator = chapter.scanlator,
            uploadDate = chapter.date_upload,
            branch = null,
        )
        return parser.pages(source).mapIndexed { index, page ->
            pages.put(page.url, page)
            Page(index, url = page.url)
        }
    }

    override suspend fun getImageUrl(page: Page): String =
        parser.pageUrl(pages.get(page.url) ?: JPage(page.url, null))

    override fun getMangaUrl(manga: SManga): String =
        mangas.get(manga.url)?.publicUrl?.takeIf { it.isNotBlank() } ?: jarAbsoluteUrl(manga.url, parser.domain)

    override fun getChapterUrl(chapter: SChapter): String = jarAbsoluteUrl(chapter.url, parser.domain)

    // Filters

    @Volatile
    private var filterOptions: JFilterOptions? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getFilterList(): FilterList {
        val options = filterOptions
        if (options == null) {
            scope.launch { runCatching { filterOptions = parser.filterOptions() } }
        }
        val caps = runCatching { parser.capabilities }.getOrNull()
        val orders = runCatching { parser.sortOrders }.getOrDefault(emptyList())
        val filters = mutableListOf<Filter<*>>()
        if (orders.isNotEmpty()) filters += SortFilter(orders)
        if (options == null) {
            filters += Filter.Header("Press reset to load the filters")
            return FilterList(filters)
        }
        if (options.tags.isNotEmpty()) {
            filters += TagGroup(options.tags, exclusion = caps?.tagsExclusion == true)
        }
        if (options.states.isNotEmpty()) filters += CheckGroup("Status", KEY_STATES, options.states)
        if (options.types.isNotEmpty()) filters += CheckGroup("Type", KEY_TYPES, options.types)
        if (options.demographics.isNotEmpty()) filters += CheckGroup("Demographic", KEY_DEMOGRAPHICS, options.demographics)
        if (options.contentRatings.isNotEmpty()) filters += CheckGroup("Content rating", KEY_RATINGS, options.contentRatings)
        if (options.locales.isNotEmpty()) filters += LocaleFilter(options.locales)
        if (caps?.year == true) filters += YearFilter()
        if (caps?.author == true) filters += AuthorFilter()
        return FilterList(filters)
    }

    private fun FilterList.toJFilter(query: String?): Pair<String, JFilter> {
        var order = popularOrder()
        var filter = JFilter(query = query)
        val caps = runCatching { parser.capabilities }.getOrNull()
        forEach { f ->
            when (f) {
                is SortFilter -> order = f.selected()
                is TagGroup -> {
                    filter = filter.copy(
                        tags = f.state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.key }.toSet(),
                        tagsExclude = f.state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.key }.toSet(),
                    )
                }
                is CheckGroup -> {
                    val checked = f.state.filter { it.state }.map { it.value }.toSet()
                    filter = when (f.key) {
                        KEY_STATES -> filter.copy(states = checked)
                        KEY_TYPES -> filter.copy(types = checked)
                        KEY_DEMOGRAPHICS -> filter.copy(demographics = checked)
                        else -> filter.copy(contentRatings = checked)
                    }
                }
                is LocaleFilter -> filter = filter.copy(locale = f.selected())
                is YearFilter -> filter = filter.copy(year = f.state.trim().toIntOrNull() ?: 0)
                is AuthorFilter -> filter = filter.copy(author = f.state.trim().takeIf { it.isNotEmpty() })
                else -> Unit
            }
        }
        // Parsers that can't search and filter at once: the query wins
        if (query != null && caps?.searchWithFilters == false) {
            filter = JFilter(query = query)
        }
        if (caps?.multipleTags == false && filter.tags.size > 1) {
            filter = filter.copy(tags = setOf(filter.tags.first()))
        }
        return order to filter
    }

    private class SortFilter(private val orders: List<String>) :
        Filter.Select<String>("Sort", orders.map { it.prettify() }.toTypedArray()) {
        fun selected(): String = orders.getOrElse(state) { orders.first() }
    }

    private class TagFilter(name: String, val key: String) : Filter.TriState(name)
    private class TagGroup(tags: List<JTag>, exclusion: Boolean) : Filter.Group<TagFilter>(
        if (exclusion) "Genres (tap twice to exclude)" else "Genres",
        tags.map { TagFilter(it.title, it.key) },
    )

    private class CheckOption(name: String, val value: String) : Filter.CheckBox(name)
    private class CheckGroup(name: String, val key: String, values: List<String>) :
        Filter.Group<CheckOption>(name, values.map { CheckOption(it.prettify(), it) })

    private class LocaleFilter(private val locales: List<Locale>) :
        Filter.Select<String>(
            "Language",
            (listOf("Any") + locales.map { it.getDisplayName(Locale.getDefault()).ifBlank { it.toString() } }).toTypedArray(),
        ) {
        fun selected(): Locale? = if (state == 0) null else locales.getOrNull(state - 1)
    }

    private class YearFilter : Filter.Text("Year")
    private class AuthorFilter : Filter.Text("Author")

    // Mapping

    private fun JManga.toSManga() = SManga.create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = largeCoverUrl ?: coverUrl
        it.description = description
        it.author = authors.joinToString().takeIf { a -> a.isNotBlank() }
        it.genre = tags.joinToString { t -> t.title }.takeIf { g -> g.isNotBlank() }
        it.status = when (state) {
            "ONGOING" -> SManga.ONGOING
            "FINISHED" -> SManga.COMPLETED
            "ABANDONED" -> SManga.CANCELLED
            "PAUSED" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun SManga.toJManga() = JManga(
        url = url,
        title = title,
        coverUrl = thumbnail_url,
        description = description,
        authors = author?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
        tags = genre?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { JTag(it, it) }.orEmpty(),
    )

    private fun JChapter.toSChapter() = SChapter.create().also {
        it.url = url
        it.name = title?.takeIf { t -> t.isNotBlank() } ?: buildString {
            if (volume > 0) append("Vol. ").append(volume).append(' ')
            append("Chapter ")
            append(if (number % 1f == 0f) number.toInt().toString() else number.toString())
        }
        it.chapter_number = number
        it.date_upload = uploadDate
        it.scanlator = listOfNotNull(scanlator, branch).distinct().joinToString(" · ").takeIf { s -> s.isNotBlank() }
    }

    override fun toString(): String = "$name (JAR)"

    private class BoundedCache<T> {
        private val map = ConcurrentHashMap<String, T>()
        fun put(key: String, value: T) {
            if (map.size > 3000) map.clear()
            map[key] = value
        }
        fun get(key: String): T? = map[key]
    }

    companion object {
        private const val KEY_STATES = "states"
        private const val KEY_TYPES = "types"
        private const val KEY_DEMOGRAPHICS = "demographics"
        private const val KEY_RATINGS = "ratings"

        /** "POPULARITY_WEEK" -> "Popularity week" */
        private fun String.prettify(): String =
            lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

        /** Kotatsu uses "" for multi-language sources and sometimes "en_US" style tags */
        fun normalizeLang(lang: String): String = when {
            lang.isBlank() || lang == "multi" -> "all"
            else -> lang.replace('_', '-').let { if (it.length > 2 && it[2] == '-') it.substring(0, 2) else it }
        }
    }
}
