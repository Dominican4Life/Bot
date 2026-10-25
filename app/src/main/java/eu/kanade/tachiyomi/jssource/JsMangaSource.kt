package eu.kanade.tachiyomi.jssource

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
import eu.kanade.tachiyomi.ui.manga.RelatedManga
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: A JS / JSON manga source as a regular source: Browse (popular, latest, search with its filters and
 * catalogs), details, chapters and pages all come from the source's own code.
 */
class JsMangaSource(
    val info: JsSourceInfo,
    private val manager: JsSourceManager,
) : HttpSource(), ConfigurableSource, JsTabs {

    override val name: String = info.name
    override val lang: String = info.displayLang
    override val id: Long = info.id
    override val versionId: Int = 1
    override val supportsLatest: Boolean = true

    // Suggestions work as for APK sources: the title and each of its words are searched (Source's default).
    // KMK: a source with getRelated(url) gives its own instead, the way an APK with its own related list does,
    // and then the title searches are left out
    override val disableRelatedMangasBySearch: Boolean get() = info.hasRelated

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> =
        relatedGroups(manga).flatMap { it.second }

    /**
     * getRelated may give one list, or named groups as `{ "Related Comics": [...], "Same genres": [...] }`. Each named
     * group is shown under its name, in the source's order
     */
    override suspend fun getRelatedMangaListByExtension(
        manga: SManga,
        pushResults: suspend (relatedManga: Pair<String, List<SManga>>, completed: Boolean) -> Unit,
    ) {
        runCatching { relatedGroups(manga) }
            .onSuccess { groups ->
                groups.filter { it.second.isNotEmpty() }.forEach { pushResults(it, false) }
            }
            .onFailure { e -> logcat(LogPriority.ERROR, e) { "${info.name}: getRelated failed" } }
    }

    private suspend fun relatedGroups(manga: SManga): List<Pair<String, List<SManga>>> {
        if (!info.hasRelated) return emptyList()
        val result = runtime().call("getRelated", manga.url)
        fun entries(element: JsonElement): List<SManga> {
            val list = JsMapping.list(element)
            webUrls.remember(list)
            return list.mapNotNull { JsMapping.toSManga(it) }.filter { it.url != manga.url }
        }
        return if (result is JsonObject) {
            result.map { (name, value) -> (RelatedManga.Success.GROUP_PREFIX + name) to entries(value) }
        } else {
            listOf("" to entries(result))
        }
    }

    override val baseUrl: String = info.baseUrl.trimEnd('/')

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        if (baseUrl.isNotBlank()) set("Referer", "$baseUrl/")
        info.headers.forEach { (key, value) -> runCatching { set(key, value) } }
    }

    @Volatile
    private var described = false

    private val webUrls by lazy { JsWebUrls(Injekt.get<Application>(), id, baseUrl) }

    private suspend fun runtime(): JsRuntime = manager.runtime(info).also {
        if (!described) {
            described = true
            manager.refreshDescription(info)
        }
    }

    private suspend fun listing(method: String, vararg args: Any?): MangasPage {
        // A source whose tab catalogs replace Popular and Latest may leave those out: an empty list, not an error
        val list = JsMapping.list(optionalListing(runtime(), method, *args))
        webUrls.remember(list)
        val mangas = list.mapNotNull { entry ->
            JsMapping.toSManga(entry)?.also { manga ->
                // KMK: an entry's `badge` shows after its name in Browse only (see JsListBadges)
                JsListBadges.put(id, manga.url, entry.str("badge") ?: entry.str("count"))
            }
        }
        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    override suspend fun getPopularManga(page: Int): MangasPage = listing("getPopular", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing("getLatest", page)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val catalog = filters.filterIsInstance<CatalogFilter>().firstOrNull()?.selected()
            ?: tabOf(filters)?.let { id -> catalogs.firstOrNull { it.id == id } }
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
                    JsMapping.toSManga(it, manga)
                }
            } else {
                null
            }
            (fresh ?: manga).apply { initialized = true }
        } else {
            manga
        }
        val newChapters = if (fetchChapters) {
            JsMapping.list(rt.call("getChapterList", manga.url))
                .also(webUrls::remember)
                .let(JsMapping::toSChapters)
        } else {
            chapters
        }
        return SMangaUpdate(details, newChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        JsMapping.list(runtime().call("getPageList", chapter.url))
            .sortedBy { it.int("index") ?: 0 }
            .mapIndexedNotNull { index, page ->
                // KMK: a page with a videoUrl is a video, played from the reader in the app's player
                ReaderVideos.pageOf(index, page, videoHeaders(), id)?.let { return@mapIndexedNotNull it }
                val image = page.str("imageUrl") ?: return@mapIndexedNotNull null
                Page(index, imageUrl = image)
            }

    /** The headers a video page plays with unless it names its own: the site as Referer, then the source's headers */
    private fun videoHeaders(): Map<String, String> = buildMap {
        if (baseUrl.isNotBlank()) put("Referer", "$baseUrl/")
        putAll(info.headers)
    }

    /**
     * KMK: The videos the source's `getVideoList` gives for a video page's `videoListUrl`, played from the reader.
     * [pageHeaders] are the page's own; a video's `headers` win over them.
     */
    suspend fun videoList(url: String, pageHeaders: Map<String, String>): List<eu.kanade.tachiyomi.animesource.model.Video> =
        JsVideos.map(JsMapping.list(runtime().call("getVideoList", url)), baseUrl, info.headers + pageHeaders)
            .map { it.second }

    override suspend fun getImageUrl(page: Page): String = page.imageUrl.orEmpty()

    // KMK: a video page with no thumbnail shows a dark tile, made here rather than fetched; a thumbnail that
    // fails to load falls back to the tile too, so the play button is still there
    override suspend fun getImage(page: Page): Response {
        if (!ReaderVideos.isVideo(page.url)) return super.getImage(page)
        if (ReaderVideos.usesTile(page)) return ReaderVideos.tileResponse()
        return try {
            super.getImage(page)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ReaderVideos.tileResponse()
        }
    }

    override fun getMangaUrl(manga: SManga): String = webUrls.pageFor(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = webUrls.pageFor(chapter.url)

    // Filters and catalogs

    private val groups by lazy { JsFilters.groups(info.filters) }
    private val catalogs by lazy { JsFilters.catalogs(info.catalogs) }

    // KMK: the source's tab catalogs, as buttons at the top of Browse
    override val popularName: String? get() = info.popularName
    override val latestName: String? get() = info.latestName

    override val catalogTabs: List<JsCatalogTab> by lazy {
        catalogs.filter { it.tab }.map { JsCatalogTab(it.id, it.name) }
    }

    override fun selectCatalog(filters: Iterable<*>, id: String): Boolean {
        filters.filterIsInstance<TabHolder>().firstOrNull()?.let { holder ->
            if (catalogs.none { it.id == id }) return false
            holder.id = id
            return true
        }
        val filter = filters.filterIsInstance<CatalogFilter>().firstOrNull() ?: return false
        val index = filter.catalogs.indexOfFirst { it.id == id }
        if (index < 0) return false
        filter.state = index + 1
        return true
    }

    override fun selectedCatalog(filters: Iterable<*>): String? =
        tabOf(filters) ?: filters.filterIsInstance<CatalogFilter>().firstOrNull()?.selected()?.id

    /** The tab button picked, while every filter is still at its default: changing one in the sheet searches */
    private fun tabOf(filters: Iterable<*>): String? {
        val id = filters.filterIsInstance<TabHolder>().firstOrNull()?.id ?: return null
        return if (touched(filters)) null else id
    }

    private fun touched(filters: Iterable<*>): Boolean = filters.any { filter ->
        when (filter) {
            is SelectFilter -> filter.state != filter.group.startIndex
            is MultiFilter -> filter.state.any { it.state }
            is CheckFilter -> filter.state != filter.group.defaultChecked
            is TagFilter -> filter.state.isNotEmpty()
            is TextFilter -> filter.state != filter.group.defaultText
            is TriFilter -> filter.state != filter.group.defaultState
            is SortFilter -> filter.state != filter.start
            is GroupFilter -> touched(filter.state)
            else -> false
        }
    }

    override fun lastTab(): String? = manager.lastTab(info.key)

    override fun rememberTab(id: String) = manager.rememberTab(info.key, id)

    override fun getFilterList(): FilterList {
        // KMK: the tag boxes' suggestions are read from the site in the background, when missing or old
        if (JsFilters.flat(groups).any { it.type == JsFilters.Type.AutoComplete }) manager.tags.ensure(info)
        val filters = mutableListOf<Filter<*>>()
        if (catalogs.isNotEmpty() && catalogs.all { it.tab }) {
            // KMK: every catalog is a tab button: no Catalog box in the sheet, just an invisible holder for the tab
            filters += TabHolder()
        } else if (catalogs.isNotEmpty()) {
            filters += CatalogFilter(catalogs)
            filters += Filter.Header("A catalog is listed when the search is empty")
            filters += Filter.Separator()
        }
        groups.forEach { filters += filterOf(it) }
        return FilterList(filters)
    }

    private fun filterOf(group: JsFilters.Group): Filter<*> = when (group.type) {
        JsFilters.Type.Select -> SelectFilter(group)
        JsFilters.Type.MultiSelect -> MultiFilter(group)
        JsFilters.Type.Checkbox -> CheckFilter(group)
        JsFilters.Type.Header -> Filter.Header(group.name)
        JsFilters.Type.Separator -> Filter.Separator(group.name)
        JsFilters.Type.Text -> TextFilter(group)
        JsFilters.Type.TriState -> TriFilter(group)
        JsFilters.Type.Sort -> SortFilter(group)
        JsFilters.Type.Group -> GroupFilter(group, group.children.map { filterOf(it) })
        JsFilters.Type.AutoComplete -> TagFilter(
            group,
            manager.tags.suggestions(info.key, group.tagLists, group.options.map { it.label }),
        )
    }

    private fun filterValues(filters: Iterable<*>): Map<String, JsonElement> = buildMap {
        filters.forEach { filter ->
            when (filter) {
                is SelectFilter -> filter.group.options.getOrNull(filter.state)?.let { put(filter.group.key, it.value) }
                is MultiFilter -> put(
                    filter.group.key,
                    kotlinx.serialization.json.JsonArray(filter.state.filter { it.state }.map { it.option.value }),
                )
                is CheckFilter -> put(filter.group.key, JsonPrimitive(filter.state))
                is TagFilter -> put(filter.group.key, kotlinx.serialization.json.JsonArray(filter.state.map { JsonPrimitive(it) }))
                is TextFilter -> put(filter.group.key, JsonPrimitive(filter.state))
                is TriFilter -> put(filter.group.key, JsonPrimitive(filter.state))
                is SortFilter -> filter.state?.let { sel ->
                    put(
                        filter.group.key,
                        kotlinx.serialization.json.buildJsonObject {
                            put("index", JsonPrimitive(sel.index))
                            put("value", filter.group.options.getOrNull(sel.index)?.value ?: JsonPrimitive(""))
                            put("ascending", JsonPrimitive(sel.ascending))
                        },
                    )
                }
                // what a group holds goes out under each one's own key
                is GroupFilter -> putAll(filterValues(filter.state))
                else -> Unit
            }
        }
    }

    /** KMK: the selected tab button, kept in the filters without showing (a blank header the sheet skips) */
    private class TabHolder : Filter.Header("") {
        var id: String? = null
    }

    private class CatalogFilter(val catalogs: List<JsFilters.Catalog>) :
        Filter.Select<String>("Catalog", (listOf("None") + catalogs.map { it.name }).toTypedArray()) {
        fun selected(): JsFilters.Catalog? = if (state == 0) null else catalogs.getOrNull(state - 1)
    }

    private class SelectFilter(val group: JsFilters.Group) :
        Filter.Select<String>(
            group.name,
            group.options.map { it.label }.toTypedArray(),
            group.defaultIndex.coerceIn(0, (group.options.size - 1).coerceAtLeast(0)),
        )

    private class OptionBox(val option: JsFilters.Option) : Filter.CheckBox(option.label)

    private class MultiFilter(val group: JsFilters.Group) :
        Filter.Group<OptionBox>(group.name, group.options.map { OptionBox(it) })

    private class CheckFilter(val group: JsFilters.Group) : Filter.CheckBox(group.name, group.defaultChecked)

    private class TextFilter(val group: JsFilters.Group) : Filter.Text(group.name, group.defaultText)

    private class TriFilter(val group: JsFilters.Group) : Filter.TriState(group.name, group.defaultState)

    private class SortFilter(val group: JsFilters.Group) :
        Filter.Sort(
            group.name,
            group.options.map { it.label }.toTypedArray(),
            Filter.Sort.Selection(group.startIndex, group.defaultAscending),
        ) {
        val start = Filter.Sort.Selection(group.startIndex, group.defaultAscending)
    }

    private class GroupFilter(val group: JsFilters.Group, children: List<Filter<*>>) :
        Filter.Group<Filter<*>>(group.name, children)

    private class TagFilter(val group: JsFilters.Group, suggestions: List<String>) :
        Filter.AutoComplete(
            name = group.name,
            hint = group.hint,
            values = suggestions,
            validPrefixes = group.prefixes,
            state = emptyList(),
        )

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        info.preferences?.let { JsPreferences.setup(screen, JsPreferences.parse(it)) }
        if (info.hasTags) manager.tags.addRefreshButton(screen, info)
    }

    override fun toString(): String = "$name (${if (info.json) "JSON" else "JS"})"
}
