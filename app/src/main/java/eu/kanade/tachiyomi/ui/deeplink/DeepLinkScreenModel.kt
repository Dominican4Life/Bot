package eu.kanade.tachiyomi.ui.deeplink

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DeepLinkScreenModel(
    query: String = "",
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getChapterByUrlAndMangaId: GetChapterByUrlAndMangaId = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
) : StateScreenModel<DeepLinkScreenModel.State>(State.Loading) {

    init {
        screenModelScope.launchIO {
            // KMK (r104): opened from a link at a cold start, the sources are still loading (in the background): the
            // link's source wouldn't be found and it'd show "no results". Waits for them (at most 30 s)
            if (!sourceManager.isInitialized.value) {
                val ready = withTimeoutOrNull(SOURCES_WAIT_MS) { sourceManager.isInitialized.first { it } } != null
                if (!ready) {
                    eu.kanade.tachiyomi.util.system.AppLog.add(
                        'W',
                        "DeepLink",
                        "Sources not loaded after ${SOURCES_WAIT_MS / 1000} s; resolving the link with the ones loaded",
                    )
                }
            }
            val source = sourceManager.getAll()
                .filterIsInstance<ResolvableSource>()
                .firstOrNull { it.getUriType(query) != UriType.Unknown }

            val manga = source?.getManga(query)?.let {
                networkToLocalManga(it.toDomainManga(source.id))
            }

            val chapter = if (source?.getUriType(query) == UriType.Chapter && manga != null) {
                source.getChapter(query)?.let { getChapterFromSChapter(it, manga, source) }
            } else {
                null
            }

            mutableState.update {
                if (manga == null) {
                    State.NoResults
                } else {
                    if (chapter == null) {
                        State.Result(manga)
                    } else {
                        State.Result(manga, chapter.id)
                    }
                }
            }
        }
    }

    private suspend fun getChapterFromSChapter(sChapter: SChapter, manga: Manga, source: Source): Chapter? {
        val localChapter = getChapterByUrlAndMangaId.await(sChapter.url, manga.id)

        return localChapter
            ?: updateMangaFromRemote(manga = manga, fetchChapters = true)
                .getOrElse { return null }
                .newChapters
                .find { it.url == sChapter.url }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data object NoResults : State

        @Immutable
        data class Result(val manga: Manga, val chapterId: Long? = null) : State
    }
}

/** KMK (r104): how long a link waits for the sources at a cold start */
private const val SOURCES_WAIT_MS = 30_000L
