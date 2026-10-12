package eu.kanade.tachiyomi.sumizuri

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
import okhttp3.Headers
import okhttp3.OkHttpClient
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: A Sumizuri JS / JSON anime source: browse, search with filters and catalogs, details, episodes (with their
 * thumbnails, summaries and seasons) and videos (headers, subtitles, dubs, intro / outro) from the source's code.
 */
class SumizuriAnimeSource(
    val info: SumizuriSourceInfo,
    private val manager: SumizuriManager,
) : AnimeCatalogueSource, ConfigurableAnimeSource, SumizuriTabs {

    override val name: String = info.name
    override val lang: String = info.displayLang
    override val id: Long = info.id
    override val supportsLatest: Boolean = true

    // Related titles by searching each word of the title would fire a search per word at the site at once,
    // which sites answer with 429 (too many requests) and which blocks this source's other calls meanwhile
    override val disableRelatedAnimesBySearch: Boolean = true

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

    private val webUrls by lazy { SumizuriWebUrls(Injekt.get<Application>(), id, baseUrl) }

    /** The title's page on the site, for WebView and sharing */
    fun getAnimeUrl(anime: SAnime): String = webUrls.pageFor(anime.url)

    fun getEpisodeUrl(episode: SEpisode): String = webUrls.pageFor(episode.url)

    private suspend fun runtime(): SumizuriRuntime = manager.runtime(info).also {
        if (!described) {
            described = true
            manager.refreshDescription(info)
        }
    }

    private suspend fun listing(method: String, vararg args: Any?): AnimesPage {
        // A source whose tab catalogs replace Popular and Latest may leave those out: an empty list, not an error
        val animes = SumizuriMapping.list(optionalListing(runtime(), method, *args))
            .also(webUrls::remember)
            .mapNotNull { SumizuriMapping.toSAnime(it) }
        return AnimesPage(animes, hasNextPage = animes.isNotEmpty())
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = listing("getPopular", page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = listing("getLatest", page)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val catalog = filters.filterIsInstance<CatalogFilter>().firstOrNull()?.selected()
            ?: tabOf(filters)?.let { id -> catalogs.firstOrNull { it.id == id } }
        if (catalog != null && query.isBlank()) return listing("getCatalog", catalog.id, page)
        return listing("search", query, page, filterValues(filters))
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val fresh = if (info.hasDetails) {
            (runtime().call("getDetails", anime.url) as? JsonObject)?.let {
                webUrls.remember(listOf(JsonObject(it + ("url" to JsonPrimitive(anime.url)))))
                SumizuriMapping.toSAnime(it, anime)
            }
        } else {
            null
        }
        return (fresh ?: anime).apply { initialized = true }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        SumizuriMapping.list(runtime().call("getChapterList", anime.url))
            .also(webUrls::remember)
            .let(SumizuriMapping::toSEpisodes)

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
        SumizuriVideos.map(SumizuriMapping.list(runtime().call("getVideoList", episode.url)), baseUrl, info.headers)

    // Filters and catalogs

    private val groups by lazy { SumizuriFilters.groups(info.filters) }
    private val catalogs by lazy { SumizuriFilters.catalogs(info.catalogs) }

    // KMK: the source's tab catalogs, as buttons at the top of Browse
    override val catalogTabs: List<SumizuriCatalogTab> by lazy {
        catalogs.filter { it.tab }.map { SumizuriCatalogTab(it.id, it.name) }
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

    override fun getFilterList(): AnimeFilterList {
        // KMK: the tag boxes' suggestions are read from the site in the background, when missing or old
        if (SumizuriFilters.flat(groups).any { it.type == SumizuriFilters.Type.AutoComplete }) manager.tags.ensure(info)
        val filters = mutableListOf<AnimeFilter<*>>()
        if (catalogs.isNotEmpty() && catalogs.all { it.tab }) {
            // KMK: every catalog is a tab button: no Catalog box in the sheet, just an invisible holder for the tab
            filters += TabHolder()
        } else if (catalogs.isNotEmpty()) {
            filters += CatalogFilter(catalogs)
            filters += AnimeFilter.Header("A catalog is listed when the search is empty")
            filters += AnimeFilter.Separator()
        }
        groups.forEach { filters += filterOf(it) }
        return AnimeFilterList(filters)
    }

    private fun filterOf(group: SumizuriFilters.Group): AnimeFilter<*> = when (group.type) {
        SumizuriFilters.Type.Select -> SelectFilter(group)
        SumizuriFilters.Type.MultiSelect -> MultiFilter(group)
        SumizuriFilters.Type.Checkbox -> CheckFilter(group)
        SumizuriFilters.Type.Header -> AnimeFilter.Header(group.name)
        SumizuriFilters.Type.Separator -> AnimeFilter.Separator(group.name)
        SumizuriFilters.Type.Text -> TextFilter(group)
        SumizuriFilters.Type.TriState -> TriFilter(group)
        SumizuriFilters.Type.Sort -> SortFilter(group)
        SumizuriFilters.Type.Group -> GroupFilter(group, group.children.map { filterOf(it) })
        SumizuriFilters.Type.AutoComplete -> TagFilter(
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
                else -> Unit
            }
        }
    }

    /** KMK: the selected tab button, kept in the filters without showing (a blank header the sheet skips) */
    private class TabHolder : AnimeFilter.Header("") {
        var id: String? = null
    }

    private class CatalogFilter(val catalogs: List<SumizuriFilters.Catalog>) :
        AnimeFilter.Select<String>("Catalog", (listOf("None") + catalogs.map { it.name }).toTypedArray()) {
        fun selected(): SumizuriFilters.Catalog? = if (state == 0) null else catalogs.getOrNull(state - 1)
    }

    private class SelectFilter(val group: SumizuriFilters.Group) :
        AnimeFilter.Select<String>(
            group.name,
            group.options.map { it.label }.toTypedArray(),
            group.defaultIndex.coerceIn(0, (group.options.size - 1).coerceAtLeast(0)),
        )

    private class OptionBox(val option: SumizuriFilters.Option) : AnimeFilter.CheckBox(option.label)

    private class MultiFilter(val group: SumizuriFilters.Group) :
        AnimeFilter.Group<OptionBox>(group.name, group.options.map { OptionBox(it) })

    private class CheckFilter(val group: SumizuriFilters.Group) : AnimeFilter.CheckBox(group.name, group.defaultChecked)

    private class TextFilter(val group: SumizuriFilters.Group) : AnimeFilter.Text(group.name, group.defaultText)

    private class TriFilter(val group: SumizuriFilters.Group) : AnimeFilter.TriState(group.name, group.defaultState)

    private class SortFilter(val group: SumizuriFilters.Group) :
        AnimeFilter.Sort(
            group.name,
            group.options.map { it.label }.toTypedArray(),
            AnimeFilter.Sort.Selection(group.startIndex, group.defaultAscending),
        ) {
        val start = AnimeFilter.Sort.Selection(group.startIndex, group.defaultAscending)
    }

    private class GroupFilter(val group: SumizuriFilters.Group, children: List<AnimeFilter<*>>) :
        AnimeFilter.Group<AnimeFilter<*>>(group.name, children)

    private class TagFilter(val group: SumizuriFilters.Group, suggestions: List<String>) :
        AnimeFilter.AutoComplete(
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
