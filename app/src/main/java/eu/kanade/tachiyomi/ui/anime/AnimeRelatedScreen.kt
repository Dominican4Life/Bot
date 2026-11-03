package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.browse.RelatedMangaTitle
import eu.kanade.presentation.browse.header
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.jssource.JsAnimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Full list of related anime, opened from the "Related titles" header on the anime page.
 * r105: a source's named groups ("Related videos", "You may like") are sections with a heading each, in the source's
 * order; [focusGroup] (the row whose arrow was tapped) is scrolled to once.
 * KMK (r109): sections whose name says "related" come first, then the others in the source's order
 * ([AnimeSuggestions.arrange]); the anime page shows them all in one Suggestions row that opens this screen at the top.
 */
class AnimeRelatedScreen(
    private val animeId: Long,
    private val focusGroup: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { AnimeRelatedScreenModel(animeId) }
        val state by screenModel.state.collectAsState()
        val gridState = rememberLazyGridState()
        var scrolled by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(state.groups, state.loading) {
            if (scrolled || focusGroup == null || state.loading) return@LaunchedEffect
            val index = AnimeSuggestions.headerIndex(state.groups, focusGroup) ?: return@LaunchedEffect
            scrolled = true
            gridState.scrollToItem(index)
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.pref_source_related_mangas),
                    subtitle = state.title,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when {
                state.items.isEmpty() && state.loading -> LoadingScreen(Modifier.padding(contentPadding))
                state.items.isEmpty() -> EmptyScreen(
                    message = stringResource(MR.strings.no_results_found),
                    modifier = Modifier.padding(contentPadding),
                )
                else -> FastScrollLazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                    horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
                ) {
                    val withHeaders = AnimeSuggestions.hasNamed(state.groups)
                    state.groups.forEachIndexed { groupIndex, group ->
                        // Only a source's named groups get headings: the plain list looks as it always did
                        if (withHeaders) {
                            header(key = "related-header-$groupIndex") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background),
                                ) {
                                    if (groupIndex > 0) HorizontalDivider()
                                    RelatedMangaTitle(
                                        title = group.name ?: stringResource(KMR.strings.pref_source_related_mangas),
                                        subtitle = null,
                                        onClick = {},
                                        onLongClick = null,
                                        showArrow = false,
                                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
                                    )
                                }
                            }
                        }
                        items(group.items, key = { it.id }) { anime ->
                            MangaComfortableGridItem(
                                coverData = anime.asMangaCover(),
                                title = anime.title,
                                onClick = { navigator.push(AnimeScreen(anime.id)) },
                                onLongClick = { navigator.push(AnimeScreen(anime.id)) },
                                usePanoramaCover = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

class AnimeRelatedScreenModel(
    private val animeId: Long,
    private val repository: AnimeRepository = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
) : StateScreenModel<AnimeRelatedScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            val anime = repository.getAnime(animeId)
            mutableState.update { it.copy(title = anime?.title) }
            val source = anime?.let { sourceManager.get(it.source) } as? AnimeCatalogueSource
            if (anime == null || source == null) {
                mutableState.update { it.copy(loading = false) }
                return@launchIO
            }
            // r105: a source's own named groups the anime page just read are shown as they are (not capped there),
            // without asking the site again. The plain list is capped on the anime page, so it is fetched in full.
            val cached = AnimeSuggestions.cached(source.id, animeId)
            if (cached != null && AnimeSuggestions.hasNamed(cached)) {
                // KMK (r109): related sections first
                val arranged = AnimeSuggestions.arrange(cached, plainCap = null)
                mutableState.update { it.copy(groups = arranged, loading = false) }
                return@launchIO
            }
            try {
                source.getRelatedAnimeList(
                    anime = anime.toSAnime(),
                    exceptionHandler = { e -> logcat(LogPriority.WARN, e) { "Related anime failed" } },
                    pushResults = { (name, list), _ ->
                        val found = list.mapNotNull {
                            runCatching { repository.networkToLocal(it, source.id) }.getOrNull()
                        }.filter { it.id != animeId }
                        val group = AnimeSuggestions.groupName(source, name)
                        mutableState.update { state ->
                            state.copy(groups = AnimeSuggestions.add(state.groups, group, found, plainCap = null))
                        }
                    },
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Related anime failed" }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    @Immutable
    data class State(
        val title: String? = null,
        val groups: List<AnimeSuggestionGroup> = emptyList(),
        val loading: Boolean = true,
    ) {
        val items: List<Anime>
            get() = groups.flatMap { it.items }
    }
}

/**
 * r105: suggestions shown under one heading. [name] is a section a source named itself (a JS source's getRelated
 * groups, e.g. "Related videos", "You may like"); null is the plain "Suggestions" list (APK extensions, title
 * searches, a JS getRelated that gives one list).
 */
@Immutable
data class AnimeSuggestionGroup(val name: String?, val items: List<Anime>)

/** r105: grouping and the per-page cache, shared by the anime page and the full Suggestions screen */
internal object AnimeSuggestions {
    /** The plain list on the anime page stops here (title searches add up), as before r105 */
    const val PLAIN_CAP = 40

    /** r95: suggestions by (source id, anime id), with when they were found; oldest dropped past the size */
    private val cache = LinkedHashMap<Pair<Long, Long>, Pair<Long, List<AnimeSuggestionGroup>>>()
    private const val CACHE_SIZE = 20
    private const val CACHE_MS = 30 * 60_000L

    fun cached(sourceId: Long, animeId: Long): List<AnimeSuggestionGroup>? = synchronized(cache) {
        cache[sourceId to animeId]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.second
    }

    fun store(sourceId: Long, animeId: Long, groups: List<AnimeSuggestionGroup>) {
        synchronized(cache) {
            val key = sourceId to animeId
            if (key !in cache && cache.size >= CACHE_SIZE) cache.remove(cache.keys.first())
            cache[key] = System.currentTimeMillis() to groups
        }
    }

    /**
     * The section a pushed list goes under. Only a JS source's pushes are names (its getRelated groups, r104); an
     * APK extension pushes search keywords or its own relations, which stay in the one Suggestions row as before.
     */
    fun groupName(source: AnimeCatalogueSource, pushed: String): String? =
        pushed.trim().takeIf { source is JsAnimeSource && it.isNotEmpty() }

    fun hasNamed(groups: List<AnimeSuggestionGroup>): Boolean = groups.any { it.name != null }

    /**
     * [groups] with [found] added: to the section of the same name (a new one otherwise), then [arrange]d. The plain
     * list is last and capped at [plainCap] when given.
     * KMK (r109): a title is left out of a section only when a section shown before it has it (related sections come
     * first, so a title in both "You may like" and "Related videos" stays under Related videos)
     */
    fun add(
        groups: List<AnimeSuggestionGroup>,
        name: String?,
        found: List<Anime>,
        plainCap: Int?,
    ): List<AnimeSuggestionGroup> {
        val index = groups.indexOfFirst { it.name == name }
        val updated = if (index >= 0) {
            val have = groups[index].items.mapTo(HashSet<Long>()) { it.id }
            val fresh = found.distinctBy { it.id }.filter { it.id !in have }
            if (fresh.isEmpty()) return groups
            groups.mapIndexed { i, group -> if (i == index) group.copy(items = group.items + fresh) else group }
        } else {
            val fresh = found.distinctBy { it.id }
            if (fresh.isEmpty()) return groups
            groups + AnimeSuggestionGroup(name, fresh)
        }
        return arrange(updated, plainCap)
    }

    /** KMK (r109): a source's section about related titles ("Related videos"): shown first */
    fun isRelated(name: String?): Boolean = name != null && RELATED.containsMatchIn(name)

    private val RELATED = Regex("related", RegexOption.IGNORE_CASE)

    /**
     * KMK (r109): the sections in the order they are shown: named ones whose name says "related" first, then the other
     * named ones (each in the source's order), then the plain list (capped at [plainCap] when given); a title shows
     * only in the first section that has it (same id = same address in this source), and empty sections are dropped
     */
    fun arrange(groups: List<AnimeSuggestionGroup>, plainCap: Int?): List<AnimeSuggestionGroup> {
        val named = groups.filter { it.name != null }
        val plain = groups.filter { it.name == null }
            .map { group -> if (plainCap != null) group.copy(items = group.items.take(plainCap)) else group }
        val ordered = named.filter { isRelated(it.name) } + named.filterNot { isRelated(it.name) } + plain
        val seen = HashSet<Long>()
        return ordered
            .map { group -> group.copy(items = group.items.filter { seen.add(it.id) }) }
            .filter { it.items.isNotEmpty() }
    }

    /**
     * KMK (r109): every suggestion for the anime page's one Suggestions row: the sections in [arrange]'s order (related
     * ones first), each title once
     */
    fun row(groups: List<AnimeSuggestionGroup>): List<Anime> = groups.flatMap { it.items }.distinctBy { it.id }

    /** Index of [name]'s heading in the full screen's grid (headings are shown when any group is named) */
    fun headerIndex(groups: List<AnimeSuggestionGroup>, name: String): Int? {
        if (!hasNamed(groups)) return null
        var index = 0
        groups.forEach { group ->
            if (group.name == name) return index
            index += 1 + group.items.size
        }
        return null
    }
}
