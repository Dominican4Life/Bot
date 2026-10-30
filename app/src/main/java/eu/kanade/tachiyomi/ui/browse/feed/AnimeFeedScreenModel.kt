package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Anime rows of the Browse feed. Anime sources have no saved searches, so a row is a source's Latest or
 * Popular list. Rows are kept in order in one preference ("sourceId:latest;sourceId:popular") and shown after the
 * manga rows.
 */
class AnimeFeedScreenModel(
    preferenceStore: PreferenceStore = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val repository: AnimeRepository = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<AnimeFeedScreenModel.State>(State()) {

    private val saved = preferenceStore.getString("feed_anime_rows", "")

    @Immutable
    data class Entry(val sourceId: Long, val latest: Boolean) {
        val key: String get() = "anime_${sourceId}_${if (latest) "latest" else "popular"}"
        fun encode() = "$sourceId:${if (latest) "latest" else "popular"}"
    }

    @Immutable
    data class Row(
        val entry: Entry,
        val title: String,
        val items: List<Anime>? = null,
        val error: String? = null,
    )

    /** An anime source the + dialog offers */
    @Immutable
    data class Option(val id: Long, val name: String, val lang: String, val supportsLatest: Boolean)

    @Immutable
    data class State(val rows: List<Row> = emptyList())

    init {
        screenModelScope.launchIO {
            // KMK: rows of turned-off sources are hidden until the source is turned on again
            saved.changes()
                .combine(sourcePreferences.animeDisabledSources().changes()) { raw, _ -> raw }
                .collectLatest { raw ->
                    val entries = decode(raw)
                        .filterNot { eu.kanade.tachiyomi.source.DisabledSources.isDisabled(it.sourceId) }
                    val old = state.value.rows.associateBy { it.entry }
                    val rows = entries.map { entry ->
                        old[entry] ?: Row(entry, sourceManager.get(entry.sourceId)?.name ?: entry.sourceId.toString())
                    }
                    mutableState.update { it.copy(rows = rows) }
                    rows.filter { it.items == null }.forEach { load(it.entry) }
                }
        }
    }

    private fun decode(raw: String): List<Entry> = raw.split(';').mapNotNull { part ->
        val (id, kind) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        Entry(id.toLongOrNull() ?: return@mapNotNull null, kind == "latest")
    }.distinct()

    // KMK: the load running for each row, so a row isn't loaded twice when the rows are rebuilt while it loads
    private val jobs = HashMap<Entry, Job>()

    private fun load(entry: Entry, force: Boolean = false) {
        synchronized(jobs) {
            val running = jobs[entry]
            if (running?.isActive == true) {
                if (!force) return
                running.cancel()
            }
            jobs[entry] = launchLoad(entry)
        }
    }

    private fun launchLoad(entry: Entry): Job {
        return screenModelScope.launchIO {
            // KMK: at a cold start the rows are read before the sources are registered: wait once for the anime
            // sources to be registered (not per source, so a source that isn't installed doesn't wait 20 s)
            if (sourceManager.get(entry.sourceId) == null) {
                AnimeSourcesReady.await(sourceManager, listOf(entry.sourceId))
            }
            val source = sourceManager.get(entry.sourceId) as? AnimeCatalogueSource
            val result = if (source == null) {
                emptyList<Anime>() to "Source not installed"
            } else {
                try {
                    val page = if (entry.latest && source.supportsLatest) {
                        source.getLatestUpdates(1)
                    } else {
                        source.getPopularAnime(1)
                    }
                    page.animes.map { repository.networkToLocal(it, source.id) }.distinctBy { it.id } to null
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    emptyList<Anime>() to (e.message ?: e.javaClass.simpleName)
                }
            }
            mutableState.update { state ->
                state.copy(
                    rows = state.rows.map {
                        if (it.entry == entry) {
                            // KMK: the row was named before its source was registered (its id)
                            it.copy(items = result.first, error = result.second, title = source?.name ?: it.title)
                        } else {
                            it
                        }
                    },
                )
            }
        }
    }

    /** Reloads every row (pull to refresh) */
    fun refresh() {
        mutableState.update { state -> state.copy(rows = state.rows.map { it.copy(items = null, error = null) }) }
        state.value.rows.forEach { load(it.entry, force = true) }
    }

    fun add(sourceId: Long, latest: Boolean) {
        val entries = decode(saved.get())
        val entry = Entry(sourceId, latest)
        if (entry !in entries) saved.set((entries + entry).joinToString(";") { it.encode() })
    }

    fun remove(entry: Entry) {
        saved.set(decode(saved.get()).filterNot { it == entry }.joinToString(";") { it.encode() })
    }

    /** Enabled anime sources (extensions and JS), for the Anime tab of the + dialog */
    fun options(): List<Option> {
        val disabled = sourcePreferences.animeDisabledSources().get()
        val disabledLanguages = sourcePreferences.animeDisabledLanguages().get()
        return sourceManager.getCatalogueSources()
            .filter { it.id.toString() !in disabled && it.lang !in disabledLanguages }
            .map { Option(it.id, it.name, it.lang, it.supportsLatest) }
            .sortedBy { it.name.lowercase() }
    }

    fun toggleFavorite(anime: Anime) {
        screenModelScope.launchIO {
            val current = repository.getAnime(anime.id) ?: anime
            repository.setFavorite(current, !current.favorite)
            val updated = repository.getAnime(anime.id) ?: return@launchIO
            mutableState.update { state ->
                state.copy(
                    rows = state.rows.map { row ->
                        row.copy(items = row.items?.map { if (it.id == updated.id) updated else it })
                    },
                )
            }
        }
    }
}

/** KMK: how long a Feed row waits for its source to be registered at start before it says it's not installed */
internal const val SOURCE_WAIT_MS = 20_000L

/** KMK: after the anime extensions are loaded, how long to wait for the source list to include them */
private const val SOURCE_SETTLE_MS = 1_500L

/**
 * KMK: waits once per app run for the anime sources to be registered (anime Feed rows and anime tag mixes). The
 * extension manager says when its extensions are loaded; the source list follows a moment later, so [ids] get a short
 * extra wait. A source that isn't installed costs at most that short wait, and only until the first wait finished.
 */
internal object AnimeSourcesReady {
    @Volatile
    private var done = false

    suspend fun await(sourceManager: AnimeSourceManager, ids: Collection<Long>) {
        if (done) return
        val extensionManager = Injekt.get<AnimeExtensionManager>()
        withTimeoutOrNull(SOURCE_WAIT_MS) { extensionManager.isInitialized.first { it } }
        withTimeoutOrNull(SOURCE_SETTLE_MS) {
            sourceManager.catalogueSources.first { list ->
                val have = list.mapTo(HashSet()) { it.id }
                ids.all { it in have }
            }
        }
        done = true
    }
}
