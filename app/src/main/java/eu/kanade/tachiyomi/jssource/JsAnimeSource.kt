package eu.kanade.tachiyomi.jssource

import android.app.Application
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.OkHttpClient
import rx.Observable
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: A JS / JSON anime source: browse, search with filters and catalogs, details, episodes (with their
 * thumbnails, summaries and seasons) and videos (headers, subtitles, dubs, intro / outro) from the source's code.
 */
class JsAnimeSource(
    val info: JsSourceInfo,
    private val manager: JsSourceManager,
) : AnimeCatalogueSource, ConfigurableAnimeSource, JsTabs {

    override val name: String = info.name
    override val lang: String = info.displayLang
    override val id: Long = info.id
    // KMK: only when the source has getLatest (older saved sources, not described again yet, say true)
    override val supportsLatest: Boolean = info.hasLatest

    // Related titles by searching each word of the title would fire a search per word at the site at once,
    // which sites answer with 429 (too many requests) and which blocks this source's other calls meanwhile
    override val disableRelatedAnimesBySearch: Boolean = true

    // KMK (r104): a source with getRelated(url) gives its own related titles (generic-list v7's Suggestions: the title
    // page's "You may like" / "Related videos" sections), like JsMangaSource; the title searches stay off (above)
    override val supportsRelatedAnimes: Boolean get() = info.hasRelated

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> =
        relatedGroups(anime).flatMap { it.second }

    /**
     * r104: getRelated may give one list, or named groups `{ "You may like": [...], "Related videos": [...] }`: each
     * group is pushed under its name, in the source's order (r105: the anime title screen shows one named row per group)
     */
    override suspend fun getRelatedAnimeListByExtension(
        anime: SAnime,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ) {
        // KMK (r107): a cancellation (the screen was left) is let through, not logged as a failure (it was caught
        // here and logged as "getRelated failed" with no message); a real failure is logged with its message
        val groups = try {
            relatedGroups(anime)
        } catch (e: Throwable) {
            if (JsRelatedCalls.isCancellation(e)) throw e
            logcat(LogPriority.ERROR, e) { "${info.name}: getRelated failed: ${e.message ?: e.javaClass.simpleName}" }
            return
        }
        groups.filter { it.second.isNotEmpty() }.forEach { pushResults(it, false) }
    }

    private suspend fun relatedGroups(anime: SAnime): List<Pair<String, List<SAnime>>> {
        if (!info.hasRelated) return emptyList()
        // KMK (r107): one call per title shared by the title page and the Suggestions screen (see JsRelatedCalls)
        val result = JsRelatedCalls.get("${info.key}|${anime.url}") { runtime().call("getRelated", anime.url) }
        fun entries(element: JsonElement): List<SAnime> {
            val list = JsMapping.list(element)
            webUrls.remember(list)
            return list.mapNotNull { JsMapping.toSAnime(it) }.filter { it.url != anime.url }
        }
        return if (result is JsonObject) {
            result.map { (name, value) -> name to entries(value) }
        } else {
            listOf("" to entries(result))
        }
    }

    val baseUrl: String = info.baseUrl.trimEnd('/')

    /**
     * Covers and video thumbnails load through this, like an Aniyomi extension's: the app's connection (shared
     * cookies, Cloudflare handling) and the source's own headers, with the source's site as the Referer rather than
     * the image host. A source's `headers` replace these.
     */
    val client: OkHttpClient get() = Injekt.get<NetworkHelper>().client

    val headers: Headers by lazy {
        Headers.Builder().apply {
            set("User-Agent", Injekt.get<NetworkHelper>().defaultUserAgentProvider())
            if (baseUrl.isNotBlank()) set("Referer", "$baseUrl/")
            info.headers.forEach { (k, v) -> runCatching { set(k, v) } }
        }.build()
    }

    @Volatile
    private var described = false

    private val webUrls by lazy { JsWebUrls(Injekt.get<Application>(), id, baseUrl) }

    /** The title's page on the site, for WebView and sharing */
    fun getAnimeUrl(anime: SAnime): String = webUrls.pageFor(anime.url)

    fun getEpisodeUrl(episode: SEpisode): String = webUrls.pageFor(episode.url)

    private suspend fun runtime(): JsRuntime = manager.runtime(info).also {
        if (!described) {
            described = true
            manager.refreshDescription(info)
        }
    }

    private suspend fun listing(method: String, vararg args: Any?): AnimesPage {
        // A source whose tab catalogs replace Popular and Latest may leave those out: an empty list, not an error
        val animes = JsMapping.list(optionalListing(runtime(), method, *args))
            .also(webUrls::remember)
            .mapNotNull { JsMapping.toSAnime(it) }
        return AnimesPage(animes, hasNextPage = animes.isNotEmpty())
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        browsing = "popular"
        return listing("getPopular", page)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        browsing = "latest"
        return listing("getLatest", page)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val catalog = filters.filterIsInstance<CatalogFilter>().firstOrNull()?.selected()
            ?: tabOf(filters)?.let { id -> catalogs.firstOrNull { it.id == id } }
        if (catalog != null && query.isBlank()) {
            browsing = "tab:${catalog.id}"
            return listing("getCatalog", catalog.id, page)
        }
        return listing("search", query, page, withBrowsing(filterValues(filters), filters))
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val fresh = if (info.hasDetails) {
            (runtime().call("getDetails", anime.url) as? JsonObject)?.let {
                webUrls.remember(listOf(JsonObject(it + ("url" to JsonPrimitive(anime.url)))))
                JsMapping.toSAnime(it, anime)
            }
        } else {
            null
        }
        return (fresh ?: anime).apply { initialized = true }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        JsMapping.list(runtime().call("getChapterList", anime.url))
            .also(webUrls::remember)
            .let(JsMapping::toSEpisodes)

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = serverVideos(episode).map { it.second }

    /**
     * The videos grouped by their `server`, so the player's quality list shows one group per server, like an Aniyomi
     * extension with hosters. A source whose videos name no server gets the plain list.
     */
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val videos = serverVideos(episode)
        if (videos.all { it.first == null }) return videos.map { it.second }.toHosterList()
        return videos.groupBy({ it.first ?: "Other" }, { it.second })
            .map { (server, list) -> Hoster(hosterName = server, videoList = list) }
    }

    /** Each video of the episode with the server it names (`server`, or `hoster`), if any */
    private suspend fun serverVideos(episode: SEpisode): List<Pair<String?, Video>> =
        JsVideos.map(JsMapping.list(runtime().call("getVideoList", episode.url)), baseUrl, info.headers)

    // Filters and catalogs

    private val groups by lazy { JsFilters.groups(info.filters) }
    private val catalogs by lazy { JsFilters.catalogs(info.catalogs) }

    /**
     * KMK (r105): the list Browse showed last ("popular", "latest" or "tab:<id>"), for a Source Lab list source's
     * filter search ([withBrowsing])
     */
    @Volatile
    private var browsing: String? = null

    /**
     * KMK (r105): a Source Lab list source (generic-list v8: its sheet has the built-in "List" filter, key `__list`)
     * gets the list Browse was showing as `__from` (the picked tab button, else the list loaded last): a filter search
     * on Popular or a tab stays on that list unless List is changed. Other sources get their values unchanged
     */
    private fun withBrowsing(values: Map<String, JsonElement>, filters: Iterable<*>): Map<String, JsonElement> {
        // KMK (r107): v10 has no "List" filter any more, so its filters' roles say it is such a source
        if (!JsFilters.readsFrom(groups)) return values
        val from = filters.filterIsInstance<TabHolder>().firstOrNull()?.id?.let { "tab:$it" } ?: browsing ?: return values
        return values + ("__from" to JsonPrimitive(from))
    }

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
            is CollectionFilter -> filter.state.isNotEmpty() // KMK (r107)
            else -> false
        }
    }

    override fun lastTab(): String? = manager.lastTab(info.key)

    override fun rememberTab(id: String) = manager.rememberTab(info.key, id)

    override fun getFilterList(): AnimeFilterList {
        // KMK: the tag boxes' suggestions are read from the site in the background, when missing or old
        if (JsFilters.flat(groups).any { it.type == JsFilters.Type.AutoComplete }) manager.tags.ensure(info)
        val filters = mutableListOf<AnimeFilter<*>>()
        if (catalogs.isNotEmpty() && catalogs.all { it.tab }) {
            // KMK: every catalog is a tab button: no Catalog box in the sheet, just an invisible holder for the tab
            filters += TabHolder()
        } else if (catalogs.isNotEmpty()) {
            filters += CatalogFilter(catalogs)
            filters += AnimeFilter.Header("A catalog is listed when the search is empty")
            filters += AnimeFilter.Separator()
        }
        // KMK (r107): the built-in "List" filter is gone from the sheet (the tabs pick the list; __from still says
        // which). A blank header (not shown) keeps its place: saved searches restore filters by position
        groups.forEach { filters.add(if (it.key == JsFilters.BUILT_IN_LIST) AnimeFilter.Header("") else filterOf(it)) }
        return AnimeFilterList(filters)
    }

    private fun filterOf(group: JsFilters.Group): AnimeFilter<*> = when (group.type) {
        JsFilters.Type.Select -> SelectFilter(group)
        JsFilters.Type.MultiSelect -> MultiFilter(group)
        JsFilters.Type.Checkbox -> CheckFilter(group)
        JsFilters.Type.Header -> AnimeFilter.Header(group.name)
        JsFilters.Type.Separator -> AnimeFilter.Separator(group.name)
        JsFilters.Type.Text -> TextFilter(group)
        JsFilters.Type.TriState -> TriFilter(group)
        JsFilters.Type.Sort -> SortFilter(group)
        JsFilters.Type.Group -> GroupFilter(group, group.children.map { filterOf(it) })
        JsFilters.Type.Collection -> CollectionFilter(directoryOf(group)) // KMK (r107)
        JsFilters.Type.AutoComplete -> TagFilter(
            group,
            manager.tags.suggestions(info.key, group.tagLists, group.options.map { it.label }),
        )
    }

    private fun filterValues(filters: Iterable<*>): Map<String, JsonElement> = buildMap {
        filters.forEach { filter ->
            when (filter) {
                is SelectFilter -> filter.group.options.getOrNull(filter.state)?.let { put(filter.group.key, it.value) }
                is MultiFilter -> put(filter.group.key, JsonArray(filter.state.filter { it.state }.map { it.option.value }))
                is CheckFilter -> put(filter.group.key, JsonPrimitive(filter.state))
                is TagFilter -> put(filter.group.key, JsonArray(filter.state.map { JsonPrimitive(it) }))
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
                // KMK (r107): the picked entry's value, like a page autocomplete's ("" = none picked)
                is CollectionFilter -> put(filter.directory.key, JsonPrimitive(filter.state))
                else -> Unit
            }
        }
    }

    /** KMK: the selected tab button, kept in the filters without showing (a blank header the sheet skips) */
    private class TabHolder : AnimeFilter.Header("") {
        var id: String? = null
    }

    private class CatalogFilter(val catalogs: List<JsFilters.Catalog>) :
        AnimeFilter.Select<String>("Catalog", (listOf("None") + catalogs.map { it.name }).toTypedArray()) {
        fun selected(): JsFilters.Catalog? = if (state == 0) null else catalogs.getOrNull(state - 1)
    }

    private class SelectFilter(val group: JsFilters.Group) :
        AnimeFilter.Select<String>(
            group.name,
            group.options.map { it.label }.toTypedArray(),
            group.defaultIndex.coerceIn(0, (group.options.size - 1).coerceAtLeast(0)),
        )

    private class OptionBox(val option: JsFilters.Option) : AnimeFilter.CheckBox(option.label)

    private class MultiFilter(val group: JsFilters.Group) :
        AnimeFilter.Group<OptionBox>(group.name, group.options.map { OptionBox(it) })

    private class CheckFilter(val group: JsFilters.Group) : AnimeFilter.CheckBox(group.name, group.defaultChecked)

    private class TextFilter(val group: JsFilters.Group) : AnimeFilter.Text(group.name, group.defaultText)

    private class TriFilter(val group: JsFilters.Group) : AnimeFilter.TriState(group.name, group.defaultState)

    private class SortFilter(val group: JsFilters.Group) :
        AnimeFilter.Sort(
            group.name,
            group.options.map { it.label }.toTypedArray(),
            AnimeFilter.Sort.Selection(group.startIndex, group.defaultAscending),
        ) {
        val start = AnimeFilter.Sort.Selection(group.startIndex, group.defaultAscending)
    }

    // KMK (r107): a "group" from getFilters is a section of the sheet (its own card)
    private class GroupFilter(val group: JsFilters.Group, children: List<AnimeFilter<*>>) :
        AnimeFilter.Group<AnimeFilter<*>>(group.name, children),
        JsFilters.SectionGroup

    /**
     * KMK (r107): a collection drop-down. Its state is the picked entry's value ("" = any), a text filter's so saved
     * searches and copies of the filters keep it; the drop-down's own state is the source's shared [JsDirectory]
     */
    private class CollectionFilter(override val directory: JsDirectory) :
        AnimeFilter.Text(directory.name, ""),
        JsDirectory.Holder

    /** KMK (r107): one directory per collection filter, shared by every copy of this source's filters */
    private val directories = java.util.concurrent.ConcurrentHashMap<String, JsDirectory>()

    private fun directoryOf(group: JsFilters.Group): JsDirectory = directories.getOrPut(group.key) {
        JsDirectory(
            group = group,
            sourceKey = info.key,
            imageHeaders = headers.names().associateWith { headers[it].orEmpty() },
            // KMK (r109): stored lists offer site-only sorts / menus only when the source can read the site live
            hasDirectory = { runtime().has("getDirectory") },
        ) { page, sort, ascending, menus ->
            runtime().call("getDirectory", group.key, page, sort, ascending, menus)
        }
    }

    private class TagFilter(val group: JsFilters.Group, suggestions: List<String>) :
        AnimeFilter.AutoComplete(
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

    // RxJava entry points are not used by this source
    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getPopularAnime"))
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> = throw UnsupportedOperationException()

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getSearchAnime"))
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> =
        throw UnsupportedOperationException()

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> = throw UnsupportedOperationException()

    override fun toString(): String = "$name (${if (info.json) "JSON" else "JS"})"
}
