package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: A "tag mix" is a Feed row built from several sources at once: the titles they have for some genres or tags.
 * A source with a matching genre/tag filter (check box, include box or tag autocomplete) gets it ticked; the others
 * are searched for the words. The results of all the sources are mixed into one row.
 */
@Immutable
@Serializable
data class TagMix(
    val id: Long,
    val name: String = "",
    val anime: Boolean = false,
    val sourceIds: List<Long> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    val title: String get() = name.ifBlank { tags.joinToString(", ") }
}

/** The saved tag mixes, in Feed order, kept as JSON in one preference */
class TagMixStore(preferenceStore: PreferenceStore = Injekt.get()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pref = preferenceStore.getString("feed_tag_mixes", "[]")

    fun changes() = pref.changes()

    fun decode(raw: String): List<TagMix> = runCatching { json.decodeFromString<List<TagMix>>(raw) }.getOrDefault(emptyList())

    fun get(): List<TagMix> = decode(pref.get())

    fun find(id: Long): TagMix? = get().find { it.id == id }

    fun save(mix: TagMix) {
        val list = get()
        val updated = if (list.any { it.id == mix.id }) list.map { if (it.id == mix.id) mix else it } else list + mix
        pref.set(json.encodeToString(updated))
    }

    fun delete(id: Long) {
        pref.set(json.encodeToString(get().filterNot { it.id == id }))
    }
}

/** Ticks the filters named like [tags]; true when at least one was ticked */
internal fun applyMangaTags(filters: List<*>, tags: List<String>): Boolean {
    val wanted = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    var matched = false
    filters.forEach { filter ->
        when (filter) {
            is Filter.Group<*> -> if (applyMangaTags(filter.state, tags)) matched = true
            is Filter.CheckBox -> if (filter.name.trim().lowercase() in wanted) {
                filter.state = true
                matched = true
            }
            is Filter.TriState -> if (filter.name.trim().lowercase() in wanted) {
                filter.state = Filter.TriState.STATE_INCLUDE
                matched = true
            }
            is Filter.AutoComplete -> {
                val found = tagsIn(filter.values, wanted)
                if (found.isNotEmpty()) {
                    filter.state = found
                    matched = true
                }
            }
            else -> {}
        }
    }
    return matched
}

/** [applyMangaTags] for anime filters */
internal fun applyAnimeTags(filters: List<*>, tags: List<String>): Boolean {
    val wanted = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    var matched = false
    filters.forEach { filter ->
        when (filter) {
            is AnimeFilter.Group<*> -> if (applyAnimeTags(filter.state, tags)) matched = true
            is AnimeFilter.CheckBox -> if (filter.name.trim().lowercase() in wanted) {
                filter.state = true
                matched = true
            }
            is AnimeFilter.TriState -> if (filter.name.trim().lowercase() in wanted) {
                filter.state = AnimeFilter.TriState.STATE_INCLUDE
                matched = true
            }
            is AnimeFilter.AutoComplete -> {
                val found = tagsIn(filter.values, wanted)
                if (found.isNotEmpty()) {
                    filter.state = found
                    matched = true
                }
            }
            else -> {}
        }
    }
    return matched
}

/** The entries of a tag list that are one of [wanted], also as "namespace:tag" */
private fun tagsIn(values: List<String>, wanted: Set<String>): List<String> =
    values.filter { value ->
        val lower = value.trim().lowercase()
        lower in wanted || lower.substringAfter(':') in wanted
    }

/** Names of the genre/tag filters of the given sources, for suggestions while typing */
internal fun knownMangaTags(sourceIds: List<Long>): List<String> {
    val sourceManager = Injekt.get<SourceManager>()
    return sourceIds.flatMap { id ->
        val source = sourceManager.get(id) as? CatalogueSource ?: return@flatMap emptyList()
        runCatching { mangaFilterNames(source.getFilterList()) }.getOrDefault(emptyList())
    }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
}

internal fun knownAnimeTags(sourceIds: List<Long>): List<String> {
    val sourceManager = Injekt.get<AnimeSourceManager>()
    return sourceIds.flatMap { id ->
        val source = sourceManager.get(id) as? AnimeCatalogueSource ?: return@flatMap emptyList()
        runCatching { animeFilterNames(source.getFilterList()) }.getOrDefault(emptyList())
    }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
}

private fun mangaFilterNames(filters: List<*>): List<String> = filters.flatMap { filter ->
    when (filter) {
        is Filter.Group<*> -> mangaFilterNames(filter.state)
        is Filter.CheckBox -> listOf(filter.name)
        is Filter.TriState -> listOf(filter.name)
        is Filter.AutoComplete -> filter.values.take(2000)
        else -> emptyList()
    }
}

private fun animeFilterNames(filters: List<*>): List<String> = filters.flatMap { filter ->
    when (filter) {
        is AnimeFilter.Group<*> -> animeFilterNames(filter.state)
        is AnimeFilter.CheckBox -> listOf(filter.name)
        is AnimeFilter.TriState -> listOf(filter.name)
        is AnimeFilter.AutoComplete -> filter.values.take(2000)
        else -> emptyList()
    }
}

/** Mixes lists one item of each at a time, so every source shows near the start of the row */
private fun <T> interleave(lists: List<List<T>>): List<T> {
    val result = ArrayList<T>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let(result::add) }
    return result
}

/** Loads the tag mix rows of the Feed */
class TagMixFeedScreenModel(
    private val store: TagMixStore = TagMixStore(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
) : StateScreenModel<TagMixFeedScreenModel.State>(State()) {

    @Immutable
    data class Row(
        val mix: TagMix,
        val manga: List<Manga>? = null,
        val anime: List<Anime>? = null,
        val error: String? = null,
    ) {
        val loaded: Boolean get() = manga != null || anime != null
        val count: Int get() = manga?.size ?: anime?.size ?: 0
    }

    @Immutable
    data class State(val rows: List<Row> = emptyList())

    init {
        screenModelScope.launchIO {
            store.changes().collectLatest { raw ->
                val mixes = store.decode(raw)
                val old = state.value.rows.associateBy { it.mix }
                val rows = mixes.map { old[it] ?: Row(it) }
                mutableState.update { it.copy(rows = rows) }
                rows.filter { !it.loaded }.forEach { load(it.mix) }
            }
        }
    }

    private fun load(mix: TagMix) {
        screenModelScope.launchIO {
            val result = try {
                if (mix.anime) Row(mix, anime = loadAnime(mix)) else Row(mix, manga = loadManga(mix))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Row(mix, manga = emptyList(), error = e.message ?: e.javaClass.simpleName)
            }
            mutableState.update { state -> state.copy(rows = state.rows.map { if (it.mix == mix) result else it }) }
        }
    }

    private suspend fun loadManga(mix: TagMix): List<Manga> = coroutineScope {
        // KMK: at a cold start the row is read before the sources are registered (as the manga Feed waits)
        withTimeoutOrNull(SOURCE_WAIT_MS) { sourceManager.isInitialized.first { it } }
        mix.sourceIds.mapNotNull { sourceManager.get(it) as? CatalogueSource }
            .map { source ->
                async {
                    runCatching {
                        val filters = source.getFilterList()
                        val page = if (applyMangaTags(filters, mix.tags)) {
                            source.getSearchManga(1, "", filters)
                        } else {
                            source.getSearchManga(1, mix.tags.joinToString(" "), source.getFilterList())
                        }
                        networkToLocalManga(page.mangas.take(PER_SOURCE).map { it.toDomainManga(source.id) })
                    }.getOrElse { if (it is CancellationException) throw it else emptyList() }
                }
            }
            .awaitAll()
            .let(::interleave)
            .distinctBy { it.title.trim().lowercase() }
    }

    private suspend fun loadAnime(mix: TagMix): List<Anime> = coroutineScope {
        // KMK: at a cold start the row is read before the sources are registered: wait a while for them
        withTimeoutOrNull(SOURCE_WAIT_MS) {
            animeSourceManager.catalogueSources.first { list ->
                val ids = list.map { it.id }.toSet()
                mix.sourceIds.all { it in ids }
            }
        }
        mix.sourceIds.mapNotNull { animeSourceManager.get(it) as? AnimeCatalogueSource }
            .map { source ->
                async {
                    runCatching {
                        val filters = source.getFilterList()
                        val page = if (applyAnimeTags(filters, mix.tags)) {
                            source.getSearchAnime(1, "", filters)
                        } else {
                            source.getSearchAnime(1, mix.tags.joinToString(" "), source.getFilterList())
                        }
                        page.animes.take(PER_SOURCE).map { animeRepository.networkToLocal(it, source.id) }
                    }.getOrElse { if (it is CancellationException) throw it else emptyList() }
                }
            }
            .awaitAll()
            .let(::interleave)
            .distinctBy { it.title.trim().lowercase() }
    }

    /** Reloads every row (pull to refresh) */
    fun refresh() {
        mutableState.update { state -> state.copy(rows = state.rows.map { Row(it.mix) }) }
        state.value.rows.forEach { load(it.mix) }
    }

    fun delete(mix: TagMix) = store.delete(mix.id)

    fun toggleAnimeFavorite(anime: Anime) {
        screenModelScope.launchIO {
            val current = animeRepository.getAnime(anime.id) ?: anime
            animeRepository.setFavorite(current, !current.favorite)
            val updated = animeRepository.getAnime(anime.id) ?: return@launchIO
            mutableState.update { state ->
                state.copy(rows = state.rows.map { row -> row.copy(anime = row.anime?.map { if (it.id == updated.id) updated else it }) })
            }
        }
    }

    private companion object {
        const val PER_SOURCE = 15
    }
}
