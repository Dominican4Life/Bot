package eu.kanade.tachiyomi.ui.anime.anilist

import android.content.Context
import androidx.compose.runtime.Immutable
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.floor

/** KMK: The source an AniList title is watched or read from, remembered per title */
@Immutable
data class AniListLink(val sourceId: Long, val localId: Long, val sourceName: String)

object AniListLinks {
    private val store get() = Injekt.get<PreferenceStore>()

    private fun pref(anilistId: Int, manga: Boolean) =
        store.getString("anilist_link_${if (manga) "manga" else "anime"}_$anilistId", "")

    fun get(anilistId: Int, manga: Boolean): AniListLink? {
        val parts = pref(anilistId, manga).get().split("|")
        if (parts.size < 3) return null
        return AniListLink(
            sourceId = parts[0].toLongOrNull() ?: return null,
            localId = parts[1].toLongOrNull() ?: return null,
            sourceName = parts.drop(2).joinToString("|"),
        )
    }

    fun set(anilistId: Int, manga: Boolean, link: AniListLink) {
        pref(anilistId, manga).set("${link.sourceId}|${link.localId}|${link.sourceName}")
    }
}

/** KMK: One title found in a source by Watch now / Read now */
@Immutable
data class PickItem(val id: Long, val title: String, val cover: MangaCover, val favorite: Boolean)

/** KMK: One source's results in the Watch now / Read now picker; null items while it's still searching */
@Immutable
data class PickRow(val sourceId: Long, val sourceName: String, val lang: String, val items: List<PickItem>? = null)

/** KMK: Searching your sources for an AniList title, and playing or reading straight from the one picked */
object AniListPlayback {

    /** The enabled sources to search, pinned first: anime (APK and JS) or manga */
    fun sources(manga: Boolean): List<Triple<Long, String, String>> {
        val prefs = Injekt.get<SourcePreferences>()
        return if (manga) {
            val langs = prefs.enabledLanguages().get()
            val disabled = prefs.disabledSources().get()
            val pinned = prefs.pinnedSources().get()
            Injekt.get<SourceManager>().getVisibleSources()
                .filterIsInstance<CatalogueSource>()
                .filter { it.lang in langs && it.id.toString() !in disabled }
                .sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name.lowercase() }))
                .map { Triple(it.id, it.name, it.lang) }
        } else {
            val langs = prefs.animeDisabledLanguages().get()
            val disabled = prefs.animeDisabledSources().get()
            val pinned = prefs.animePinnedSources().get()
            Injekt.get<AnimeSourceManager>().getCatalogueSources()
                .filter { it.lang !in langs && it.id.toString() !in disabled }
                .sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name.lowercase() }))
                .map { Triple(it.id, it.name, it.lang) }
        }
    }

    /** One source's results for [query], saved so they can be opened */
    suspend fun search(sourceId: Long, query: String, manga: Boolean): List<PickItem> = withIOContext {
        if (manga) {
            val source = Injekt.get<SourceManager>().get(sourceId) as? CatalogueSource ?: return@withIOContext emptyList()
            val found = source.getSearchManga(1, query, source.getFilterList()).mangas
                .map { it.toDomainManga(source.id) }
                .distinctBy { it.url }
            Injekt.get<NetworkToLocalManga>().invoke(found).map {
                PickItem(it.id, it.title, it.asMangaCover(), it.favorite)
            }
        } else {
            val source = Injekt.get<AnimeSourceManager>().getCatalogueSources().firstOrNull { it.id == sourceId }
                ?: return@withIOContext emptyList()
            val repository = Injekt.get<AnimeRepository>()
            source.getSearchAnime(1, query, source.getFilterList()).animes.mapNotNull {
                runCatching { repository.networkToLocal(it, source.id) }.getOrNull()
            }.map { PickItem(it.id, it.title, it.asMangaCover(), it.favorite) }
        }
    }

    /** How alike a found title is to the AniList names (0 to 1), to put the best match first */
    fun similarity(title: String, names: List<String>): Double {
        fun clean(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.isNotBlank() }
        val words = clean(title).toSet()
        if (words.isEmpty()) return 0.0
        return names.maxOfOrNull { name ->
            val other = clean(name).toSet()
            if (other.isEmpty()) {
                0.0
            } else {
                val same = (words intersect other).size.toDouble()
                val exact = if (clean(name).joinToString(" ") == clean(title).joinToString(" ")) 1.0 else 0.0
                maxOf(exact, same / (words union other).size)
            }
        } ?: 0.0
    }

    sealed interface Outcome {
        data object Started : Outcome

        /** The source's page should open instead (seasons, or the player is set to an external app) */
        data object OpenPage : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Loads the anime's episodes from its source and plays [episode] (or the next one not watched yet) */
    suspend fun playAnime(context: Context, animeId: Long, episode: Int?): Outcome {
        val repository = Injekt.get<AnimeRepository>()
        val anime = repository.getAnime(animeId) ?: return Outcome.Failed("?")
        val source = Injekt.get<AnimeSourceManager>().get(anime.source) ?: return Outcome.OpenPage
        // KMK (r87): every Watch now / episode tap reloaded the whole list. Not again within 60 s of the last load,
        // when what's saved already has the episode asked for
        val ago = System.currentTimeMillis() - repository.lastRefreshed(animeId)
        val saved = if (ago < RELOAD_AFTER_MS) repository.getEpisodes(animeId) else emptyList()
        val skip = saved.isNotEmpty() &&
            (episode == null || saved.any { floor(it.episodeNumber).toInt() == episode })
        val loadError = if (skip) {
            null
        } else {
            withIOContext { runCatching { repository.refreshFromSource(anime, source) }.exceptionOrNull() }
        }
        val fresh = repository.getAnime(animeId) ?: anime
        if (fresh.fetchType != FetchType.Episodes) return Outcome.OpenPage
        val episodes = repository.getEpisodes(animeId)
        if (episodes.isEmpty()) return Outcome.Failed(loadError?.message ?: "No episodes")
        val target = if (episode != null) {
            episodes.firstOrNull { it.episodeNumber == episode.toDouble() }
                ?: episodes.firstOrNull { floor(it.episodeNumber).toInt() == episode }
                ?: return Outcome.Failed("$episode")
        } else {
            episodes.filter { !it.seen && it.episodeNumber >= 0 }.minByOrNull { it.episodeNumber }
                ?: episodes.minBy { it.episodeNumber }
        }
        if (Injekt.get<PlayerPreferences>().alwaysUseExternalPlayer().get()) return Outcome.OpenPage
        withUIContext { context.startActivity(PlayerActivity.newIntent(context, animeId, target.id)) }
        return Outcome.Started
    }

    private const val RELOAD_AFTER_MS = 60_000L

    /** The manga's chapters from its source, oldest first */
    suspend fun mangaChapters(mangaId: Long, refresh: Boolean = true): List<Chapter> = withIOContext {
        val manga = Injekt.get<GetManga>().await(mangaId) ?: return@withIOContext emptyList()
        if (refresh) {
            val source = Injekt.get<SourceManager>().getOrStub(manga.source)
            Injekt.get<UpdateMangaFromRemote>().invoke(
                source = source,
                manga = manga,
                fetchDetails = !manga.initialized,
                fetchChapters = true,
            )
        }
        Injekt.get<GetChaptersByMangaId>().await(mangaId).sortedBy { it.chapterNumber }
    }

    /** Opens [chapter] (or the first one not read yet) of the manga in the reader */
    suspend fun readManga(context: Context, mangaId: Long, chapter: Int?): Outcome {
        val chapters = mangaChapters(mangaId)
        if (chapters.isEmpty()) return Outcome.Failed("No chapters")
        val target = if (chapter != null) {
            chapters.firstOrNull { floor(it.chapterNumber).toInt() == chapter } ?: return Outcome.Failed("$chapter")
        } else {
            chapters.firstOrNull { !it.read && it.chapterNumber >= 0 } ?: chapters.first()
        }
        withUIContext { context.startActivity(ReaderActivity.newIntent(context, mangaId, target.id)) }
        return Outcome.Started
    }
}
