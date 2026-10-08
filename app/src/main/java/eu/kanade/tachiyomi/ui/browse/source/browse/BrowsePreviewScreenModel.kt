package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Backs the two preview panels on the browse source screen.
 * Tapping a cover selects it: the left panel lists its chapters and the right panel shows the pages of
 * the selected chapter (the first chapter by default).
 */
class BrowsePreviewScreenModel(
    private val source: Source,
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
) : StateScreenModel<BrowsePreviewScreenModel.State>(State()) {

    private var chaptersJob: Job? = null
    private var pagesJob: Job? = null

    val httpSource: HttpSource? = source as? HttpSource

    /** Selects a cover and opens both panels */
    fun select(manga: Manga) {
        if (state.value.manga?.id == manga.id) {
            mutableState.update { it.copy(chapterPanelOpen = true, pagePanelOpen = true) }
            return
        }
        chaptersJob?.cancel()
        pagesJob?.cancel()
        mutableState.update {
            State(
                manga = manga,
                chapterPanelOpen = true,
                pagePanelOpen = true,
                chaptersLoading = true,
            )
        }
        chaptersJob = screenModelScope.launchIO {
            // Show what's already saved right away, then refresh from the source
            val saved = getChaptersByMangaId.await(manga.id)
            if (saved.isNotEmpty()) onChaptersLoaded(manga, saved, stillLoading = true)

            val result = updateMangaFromRemote(source = source, manga = manga, fetchChapters = true)
            val error = result.exceptionOrNull()
            val chapters = getChaptersByMangaId.await(manga.id)
            onChaptersLoaded(manga, chapters, stillLoading = false, error = error.takeIf { chapters.isEmpty() })
        }
    }

    private fun onChaptersLoaded(manga: Manga, chapters: List<Chapter>, stillLoading: Boolean, error: Throwable? = null) {
        if (state.value.manga?.id != manga.id) return
        // Oldest chapter first, like the reader's chapter list
        val sorted = chapters.sortedByDescending { it.sourceOrder }
        val previous = state.value.selectedChapterId
        mutableState.update {
            it.copy(
                chapters = sorted,
                chaptersLoading = stillLoading,
                chaptersError = error?.message,
            )
        }
        val toSelect = sorted.firstOrNull { it.id == previous } ?: sorted.firstOrNull()
        if (toSelect != null && toSelect.id != previous) selectChapter(toSelect)
    }

    /** Shows the pages of [chapter] in the right panel */
    fun selectChapter(chapter: Chapter) {
        pagesJob?.cancel()
        mutableState.update {
            it.copy(
                selectedChapterId = chapter.id,
                pages = null,
                pagesLoading = true,
                pagesError = null,
                pagePanelOpen = true,
            )
        }
        val httpSource = httpSource
        if (httpSource == null) {
            mutableState.update { it.copy(pagesLoading = false) }
            return
        }
        pagesJob = screenModelScope.launchIO {
            try {
                val pages = try {
                    chapterCache.getPageListFromCache(chapter)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    httpSource.getPageList(chapter.toSChapter()).also {
                        runCatching { chapterCache.putPageListToCache(chapter, it) }
                    }
                }
                // Use our own indexing, like the reader does
                val indexed = pages.mapIndexed { index, page -> Page(index, page.url, page.imageUrl) }
                mutableState.update {
                    if (it.selectedChapterId != chapter.id) it else it.copy(pages = indexed, pagesLoading = false)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                mutableState.update {
                    if (it.selectedChapterId != chapter.id) {
                        it
                    } else {
                        it.copy(pagesLoading = false, pagesError = e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
    }

    fun closeChapterPanel() {
        mutableState.update { it.copy(chapterPanelOpen = false) }
        clearIfBothClosed()
    }

    fun closePagePanel() {
        mutableState.update { it.copy(pagePanelOpen = false) }
        clearIfBothClosed()
    }

    /** Closing both panels also removes the highlight from the cover */
    private fun clearIfBothClosed() {
        val s = state.value
        if (!s.chapterPanelOpen && !s.pagePanelOpen) {
            chaptersJob?.cancel()
            pagesJob?.cancel()
            mutableState.update { State() }
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val chapterPanelOpen: Boolean = false,
        val pagePanelOpen: Boolean = false,
        val chapters: List<Chapter> = emptyList(),
        val chaptersLoading: Boolean = false,
        val chaptersError: String? = null,
        val selectedChapterId: Long? = null,
        val pages: List<Page>? = null,
        val pagesLoading: Boolean = false,
        val pagesError: String? = null,
    ) {
        val selectedChapter: Chapter?
            get() = chapters.firstOrNull { it.id == selectedChapterId }
    }
}
