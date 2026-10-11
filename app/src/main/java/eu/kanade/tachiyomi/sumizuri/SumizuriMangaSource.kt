package eu.kanade.tachiyomi.sumizuri

import android.app.Application
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: A Sumizuri JS / JSON manga source as a regular source: Browse (popular, latest, search with its filters and
 * catalogs), details, chapters and pages all come from the source's own code.
 */
class SumizuriMangaSource(
    val info: SumizuriSourceInfo,
    private val manager: SumizuriManager,
) : HttpSource(), ConfigurableSource, SumizuriTabs {

    override val name: String = info.name
    override val lang: String = info.displayLang
    override val id: Long = info.id
    override val versionId: Int = 1
    override val supportsLatest: Boolean = true

    // Related titles by searching each word of the title would fire a search per word at the site at once,
    // which sites answer with 429 (too many requests) and which blocks this source's other calls meanwhile
    override val disableRelatedMangasBySearch: Boolean = true
    override val baseUrl: String = info.baseUrl.trimEnd('/')

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        if (baseUrl.isNotBlank()) set("Referer", "$baseUrl/")
        info.headers.forEach { (key, value) -> runCatching { set(key, value) } }
    }

    @Volatile
    private var described = false

    private val webUrls by lazy { SumizuriWebUrls(Injekt.get<Application>(), id, baseUrl) }

    private suspend fun runtime(): SumizuriRuntime = manager.runtime(info).also {
        if (!described) {
            described = true
            manager.refreshDescription(info)
        }
    }

    private suspend fun listing(method: String, vararg args: Any?): MangasPage {
        val list = SumizuriMapping.list(runtime().call(method, *args))
        webUrls.remember(list)
        val mangas = list.mapNotNull { SumizuriMapping.toSManga(it) }
        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    override suspend fun getPopularManga(page: Int): MangasPage = listing("getPopular", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing("getLatest", page)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val catalog = filters.filterIsInstance<CatalogFilter>().firstOrNull()?.selected()
        if (catalog != null && query.isBlank()) return listing("getCatalog", catalog.id, page)
        return listing("search", query, page, filterValues(filters))
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val rt = runtime()
        val details = if (fetchDetails) {
            val fresh = if (info.hasDetails) {
                (rt.call("getDetails", manga.url) as? JsonObject)?.let {
                    webUrls.remember(listOf(JsonObject(it + ("url" to JsonPrimitive(manga.url)))))
                    SumizuriMapping.toSManga(it, manga)
                }
            } else {
                null
            }
            (fresh ?: manga).apply { initialized = true }
        } else {
            manga
        }
        val newChapters = if (fetchChapters) {
            SumizuriMapping.list(rt.call("getChapterList", manga.url))
                .also(webUrls::remember)
                .let(SumizuriMapping::toSChapters)
        } else {
            chapters
        }
        return SMangaUpdate(details, newChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        SumizuriMapping.list(runtime().call("getPageList", chapter.url))
            .sortedBy { it.int("index") ?: 0 }
            .mapIndexedNotNull { index, page ->
                val image = page.str("imageUrl") ?: return@mapIndexedNotNull null
                Page(index, imageUrl = image)
            }

    override suspend fun getImageUrl(page: Page): String = page.imageUrl.orEmpty()

    override fun getMangaUrl(manga: SManga): String = webUrls.pageFor(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = webUrls.pageFor(chapter.url)

    // Filters and catalogs

    private val groups by lazy { SumizuriFilters.groups(info.filters) }
    private val catalogs by lazy { SumizuriFilters.catalogs(info.catalogs) }

    // KMK: the source's names for Popular / Latest, and its catalogs as buttons
    override val tabNames: SumizuriTabNames by lazy { SumizuriFilters.tabNames(info.tabNames) }

    override val catalogTabs: List<SumizuriCatalogTab> by lazy {
        catalogs.filter { it.tab }.map { SumizuriCatalogTab(it.id, it.name) }
    }

    override fun selectCatalog(filters: Iterable<*>, id: String): Boolean {
        val filter = filters.filterIsInstance<CatalogFilter>().firstOrNull() ?: return false
        val index = filter.catalogs.indexOfFirst { it.id == id }
        if (index < 0) return false
        filter.state = index + 1
        return true
    }

    override fun selectedCatalog(filters: Iterable<*>): String? =
        filters.filterIsInstance<CatalogFilter>().firstOrNull()?.selected()?.id

    override fun getFilterList(): FilterList {
        // KMK: the tag boxes' suggestions are read from the site in the background, when missing or old
        if (groups.any { it.type == SumizuriFilters.Type.AutoComplete }) manager.tags.ensure(info)
        val filters = mutableListOf<Filter<*>>()
        if (catalogs.isNotEmpty()) {
            filters += CatalogFilter(catalogs)
            filters += Filter.Header("A catalog is listed when the search is empty")
            filters += Filter.Separator()
        }
        groups.forEach { group ->
            filters += when (group.type) {
                SumizuriFilters.Type.Select -> SelectFilter(group)
                SumizuriFilters.Type.MultiSelect -> MultiFilter(group)
                SumizuriFilters.Type.Checkbox -> CheckFilter(group)
                SumizuriFilters.Type.AutoComplete -> TagFilter(
                    group,
                    manager.tags.suggestions(info.key, group.tagLists, group.options.map { it.label }),
                )
            }
        }
        return FilterList(filters)
    }

    private fun filterValues(filters: FilterList): Map<String, JsonElement> = buildMap {
        filters.forEach { filter ->
            when (filter) {
                is SelectFilter -> filter.group.options.getOrNull(filter.state)?.let { put(filter.group.key, it.value) }
                is MultiFilter -> put(
                    filter.group.key,
                    kotlinx.serialization.json.JsonArray(filter.state.filter { it.state }.map { it.option.value }),
                )
                is CheckFilter -> put(filter.group.key, JsonPrimitive(filter.state))
                is TagFilter -> put(filter.group.key, kotlinx.serialization.json.JsonArray(filter.state.map { JsonPrimitive(it) }))
                else -> Unit
            }
        }
    }

    private class CatalogFilter(val catalogs: List<SumizuriFilters.Catalog>) :
        Filter.Select<String>("Catalog", (listOf("None") + catalogs.map { it.name }).toTypedArray()) {
        fun selected(): SumizuriFilters.Catalog? = if (state == 0) null else catalogs.getOrNull(state - 1)
    }

    private class SelectFilter(val group: SumizuriFilters.Group) :
        Filter.Select<String>(
            group.name,
            group.options.map { it.label }.toTypedArray(),
            group.defaultIndex.coerceIn(0, (group.options.size - 1).coerceAtLeast(0)),
        )

    private class OptionBox(val option: SumizuriFilters.Option) : Filter.CheckBox(option.label)

    private class MultiFilter(val group: SumizuriFilters.Group) :
        Filter.Group<OptionBox>(group.name, group.options.map { OptionBox(it) })

    private class CheckFilter(val group: SumizuriFilters.Group) : Filter.CheckBox(group.name, group.defaultChecked)

    private class TagFilter(val group: SumizuriFilters.Group, suggestions: List<String>) :
        Filter.AutoComplete(
            name = group.name,
            hint = group.hint,
            values = suggestions,
            validPrefixes = group.prefixes,
            state = emptyList(),
        )

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        info.preferences?.let { SumizuriPreferences.setup(screen, SumizuriPreferences.parse(it)) }
        if (info.hasTags) manager.tags.addRefreshButton(screen, info)
    }

    override fun toString(): String = "$name (${if (info.json) "JSON" else "JS"})"
}
