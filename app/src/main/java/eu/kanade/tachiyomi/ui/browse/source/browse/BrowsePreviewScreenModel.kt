package eu.kanade.tachiyomi.ui.browse.source.browse

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.coil.LocalPageThumbnail
import eu.kanade.tachiyomi.jssource.ChapterVideoDownloads
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Backs the two preview panels on the browse source screen.
 * Tapping a cover selects it: the left panel lists its chapters (with the same filter, sort, read,
 * bookmark and download controls as the details page) and the right panel shows the pages of the
 * selected chapter (the first chapter by default).
 */
class BrowsePreviewScreenModel(
    private val source: Source,
    private val getMangaWithChapters: GetMangaWithChapters = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setMangaChapterFlags: SetMangaChapterFlags = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val getAvailableScanlators: GetAvailableScanlators = Injekt.get(),
    private val getExcludedScanlators: GetExcludedScanlators = Injekt.get(),
    private val setExcludedScanlators: SetExcludedScanlators = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
) : StateScreenModel<BrowsePreviewScreenModel.State>(State()) {

    private val jobs = mutableListOf<Job>()
    private var pagesJob: Job? = null

    val httpSource: HttpSource? = source as? HttpSource

    /**
     * Selects a cover and opens the panels.
     * @param showChapters open the left (chapters) panel
     * @param showPages open the right (pages) panel
     */
    fun select(manga: Manga, showChapters: Boolean = true, showPages: Boolean = true) {
        if (state.value.manga?.id == manga.id) {
            mutableState.update { it.copy(chapterPanelOpen = showChapters, pagePanelOpen = showPages) }
            return
        }
        cancelAll()
        mutableState.update {
            State(
                manga = manga,
                chapterPanelOpen = showChapters,
                pagePanelOpen = showPages,
                chaptersLoading = true,
            )
        }

        // Keep the chapter list in sync with the database and downloads
        jobs += screenModelScope.launchIO {
            combine(
                getMangaWithChapters.subscribe(manga.id, applyFilter = true),
                downloadCache.changes,
                downloadManager.queueState,
            ) { (m, chapters), _, queue -> Triple(m, chapters, queue) }
                .collectLatest { (m, chapters, queue) ->
                    val isLocal = m.isLocal()
                    val all = chapters.map { chapter ->
                        val active = queue.find { it.chapter.id == chapter.id }
                        val downloaded = isLocal || downloadManager.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            m.ogTitle,
                            m.source,
                        )
                        ChapterItem(
                            chapter = chapter,
                            downloadState = when {
                                active != null -> active.status
                                downloaded -> Download.State.DOWNLOADED
                                else -> Download.State.NOT_DOWNLOADED
                            },
                        )
                    }
                    val chapterSort = getChapterSort(m)
                    val shown = all
                        .filter { applyFilter(m.unreadFilter) { !it.chapter.read } }
                        .filter { applyFilter(m.bookmarkedFilter) { it.chapter.bookmark } }
                        .filter { applyFilter(m.downloadedFilter) { it.isDownloaded || isLocal } }
                        .sortedWith { a, b -> chapterSort(a.chapter, b.chapter) }
                    mutableState.update {
                        if (it.manga?.id != m.id) it else it.copy(manga = m, allChapters = all, chapters = shown)
                    }
                    // Preview the first chapter by default
                    if (state.value.selectedChapterId == null && all.isNotEmpty()) {
                        selectChapter(all.maxBy { it.chapter.sourceOrder }.chapter)
                    }
                }
        }

        jobs += screenModelScope.launchIO {
            combine(
                getAvailableScanlators.subscribe(manga.id),
                getExcludedScanlators.subscribe(manga.id),
            ) { available, excluded -> available to excluded }
                .collectLatest { (available, excluded) ->
                    mutableState.update {
                        it.copy(availableScanlators = available, excludedScanlators = excluded)
                    }
                }
        }

        // Refresh the details and chapter list from the source, unless they were saved less than a day ago
        refreshFromSource(manga, force = false)

        loadSeedColor(manga)
    }

    /**
     * KMK: Gets the title's details and chapters from the source. A title opened in the last day (with its details
     * and chapters saved) shows what's saved instead, with no request; pulling down in the chapter panel ([refresh])
     * always asks the source.
     */
    private fun refreshFromSource(manga: Manga, force: Boolean) {
        jobs += screenModelScope.launchIO {
            if (!force && manga.initialized && PreviewFetchTimes.isFresh(manga.id) &&
                getMangaWithChapters.awaitChapters(manga.id).isNotEmpty()
            ) {
                mutableState.update { if (it.manga?.id != manga.id) it else it.copy(chaptersLoading = false) }
                return@launchIO
            }
            if (force) {
                mutableState.update { if (it.manga?.id != manga.id) it else it.copy(chaptersLoading = true) }
            }
            val result = updateMangaFromRemote(source = source, manga = manga, fetchDetails = true, fetchChapters = true)
            if (result.isSuccess) PreviewFetchTimes.mark(manga.id)
            mutableState.update {
                if (it.manga?.id != manga.id) {
                    it
                } else {
                    it.copy(
                        chaptersLoading = false,
                        chaptersError = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    /** Pull down in the chapter panel: get the details and chapters from the source again */
    fun refresh() {
        val manga = state.value.manga ?: return
        if (state.value.chaptersLoading) return
        refreshFromSource(manga, force = true)
    }

    /** Colors the panels to match the cover, like the details page does */
    private fun loadSeedColor(manga: Manga) {
        if (!uiPreferences.themeCoverBased().get()) return
        val mangaCover = manga.asMangaCover()
        mangaCover.vibrantCoverColor?.let { color ->
            mutableState.update { it.copy(seedColor = Color(color)) }
            return
        }
        val context = Injekt.get<Application>()
        val request = ImageRequest.Builder(context)
            .data(mangaCover)
            .allowHardware(false)
            .target(
                onSuccess = { image ->
                    val bitmap = image.asDrawable(context.resources).getBitmapOrNull() ?: return@target
                    Palette.from(bitmap).generate { palette ->
                        val color = palette?.getBestColor() ?: return@generate
                        mangaCover.vibrantCoverColor = color
                        mutableState.update {
                            if (it.manga?.id != manga.id) it else it.copy(seedColor = Color(color))
                        }
                    }
                },
            )
            .build()
        context.imageLoader.enqueue(request)
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
            )
        }
        val httpSource = httpSource
        if (!state.value.pagePanelOpen) {
            mutableState.update { it.copy(pagesLoading = false, localPages = null) }
            return
        }
        pagesJob = screenModelScope.launchIO {
            // KMK: a downloaded chapter shows its pages from the files, without contacting the site
            val local = runCatching { localPages(chapter) }
                .onFailure { logcat(LogPriority.WARN, it) { "Couldn't read downloaded pages" } }
                .getOrNull()
            if (!local.isNullOrEmpty()) {
                mutableState.update {
                    if (it.selectedChapterId != chapter.id) {
                        it
                    } else {
                        it.copy(
                            pages = local.map { page -> Page(page.index) },
                            localPages = local,
                            pagesLoading = false,
                        )
                    }
                }
                return@launchIO
            }
            mutableState.update { it.copy(localPages = null) }
            if (httpSource == null) {
                mutableState.update { it.copy(pagesLoading = false) }
                return@launchIO
            }
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

    // Chapter actions

    fun toggleRead(chapter: Chapter) {
        screenModelScope.launchIO {
            setReadStatus.await(!chapter.read, chapter)
        }
    }

    fun toggleBookmark(chapter: Chapter) {
        screenModelScope.launchIO {
            updateChapter.await(ChapterUpdate(id = chapter.id, bookmark = !chapter.bookmark))
        }
    }

    fun runChapterDownloadAction(chapter: Chapter, action: ChapterDownloadAction) {
        val manga = state.value.manga ?: return
        when (action) {
            ChapterDownloadAction.START -> downloadManager.downloadChapters(manga, listOf(chapter))
            ChapterDownloadAction.START_NOW -> downloadManager.startDownloadNow(chapter.id)
            ChapterDownloadAction.CANCEL -> {
                val download = downloadManager.getQueuedDownloadOrNull(chapter.id) ?: return
                downloadManager.cancelQueuedDownloads(listOf(download))
            }
            ChapterDownloadAction.DELETE -> screenModelScope.launchNonCancellable {
                try {
                    downloadManager.deleteChapters(listOf(chapter), manga, source, ignoreCategoryExclusion = true)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }
        }
    }

    /** [action] null means download every chapter that isn't downloaded yet */
    fun runDownloadAction(action: DownloadAction?) {
        val manga = state.value.manga ?: return
        val all = state.value.allChapters
        // In reading order: oldest first
        val notDownloaded = all
            .filter { it.downloadState == Download.State.NOT_DOWNLOADED || it.downloadState == Download.State.ERROR }
            .map { it.chapter }
            .sortedByDescending { it.sourceOrder }
        val unread = notDownloaded.filter { !it.read }
        val toDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> unread.take(1)
            DownloadAction.NEXT_5_CHAPTERS -> unread.take(5)
            DownloadAction.NEXT_10_CHAPTERS -> unread.take(10)
            DownloadAction.NEXT_25_CHAPTERS -> unread.take(25)
            DownloadAction.UNREAD_CHAPTERS -> unread
            DownloadAction.BOOKMARKED_CHAPTERS -> notDownloaded.filter { it.bookmark }
            DownloadAction.ALL_CHAPTERS, null -> notDownloaded
        }
        if (toDownload.isNotEmpty()) downloadManager.downloadChapters(manga, toDownload)
    }

    // Filter / sort / display, same as the details page

    private fun withManga(block: suspend (Manga) -> Unit) {
        val manga = state.value.manga ?: return
        screenModelScope.launchNonCancellable { block(manga) }
    }

    fun setUnreadFilter(state: TriState) = withManga {
        setMangaChapterFlags.awaitSetUnreadFilter(
            it,
            when (state) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
            },
        )
    }

    fun setDownloadedFilter(state: TriState) = withManga {
        setMangaChapterFlags.awaitSetDownloadedFilter(
            it,
            when (state) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
            },
        )
    }

    fun setBookmarkedFilter(state: TriState) = withManga {
        setMangaChapterFlags.awaitSetBookmarkFilter(
            it,
            when (state) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
            },
        )
    }

    fun setSorting(sort: Long) = withManga { setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(it, sort) }

    fun setDisplayMode(mode: Long) = withManga { setMangaChapterFlags.awaitSetDisplayMode(it, mode) }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) = withManga {
        libraryPreferences.setChapterSettingsDefault(it)
        if (applyToExisting) setMangaDefaultChapterFlags.awaitAll()
    }

    fun resetToDefaultSettings() = withManga { setMangaDefaultChapterFlags.await(it) }

    fun setExcludedScanlators(excluded: Set<String>) = withManga { setExcludedScanlators.await(it.id, excluded) }

    /** Pages of a downloaded chapter (a folder of images or a CBZ), or null when it isn't downloaded */
    private fun localPages(chapter: Chapter): List<LocalPageThumbnail>? {
        val manga = state.value.manga ?: return null
        val downloaded = downloadManager.isChapterDownloaded(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.ogTitle,
            source.id,
        )
        if (!downloaded) return null
        val dir = downloadProvider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.ogTitle, source)
            ?: return null
        val context = Injekt.get<Application>()
        return if (dir.isFile) {
            val (names, videoPictures) = dir.archiveReader(context).use { reader ->
                reader.useEntries { entries ->
                    val all = entries.filter { it.isFile }.toList()
                    val images = all
                        .filter { !ChapterVideoDownloads.isVideoFile(it.name) }
                        .filter { ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        .map { it.name }
                    images to ChapterVideoDownloads.picturesWithVideo(all.map { it.name })
                }
            }
            names
                .sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
                .mapIndexed { index, name ->
                    LocalPageThumbnail(
                        chapter.id,
                        index,
                        archiveUri = dir.uri,
                        entryName = name,
                        isVideo = name in videoPictures,
                    )
                }
        } else {
            val files = dir.listFiles().orEmpty()
            val nameOf = files.associate { it.uri.toString() to it.name.orEmpty() }
            val videoPictures = ChapterVideoDownloads.picturesWithVideo(files.map { it.name.orEmpty() })
            downloadManager.buildPageList(source, manga, chapter).map { page ->
                LocalPageThumbnail(
                    chapter.id,
                    page.index,
                    fileUri = page.uri,
                    isVideo = nameOf[page.uri.toString()]?.let { it in videoPictures } == true,
                )
            }
        }
    }

    // Panels

    // KMK: the Left Panel / Right Panel buttons open a panel for the cover that is already selected

    fun openChapterPanel() {
        if (state.value.manga == null) return
        mutableState.update { it.copy(chapterPanelOpen = true) }
    }

    fun openPagePanel() {
        val s = state.value
        if (s.manga == null) return
        mutableState.update { it.copy(pagePanelOpen = true) }
        // The pages are only read while the panel is open
        if (s.pages == null && s.localPages == null) {
            s.allChapters.firstOrNull { it.chapter.id == s.selectedChapterId }?.let { selectChapter(it.chapter) }
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
            cancelAll()
            mutableState.update { State() }
        }
    }

    /** Closes both panels and removes the highlight from the cover */
    fun clear() {
        cancelAll()
        mutableState.update { State() }
    }

    private fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        pagesJob?.cancel()
    }

    @Immutable
    data class ChapterItem(
        val chapter: Chapter,
        val downloadState: Download.State,
    ) {
        val isDownloaded: Boolean
            get() = downloadState == Download.State.DOWNLOADED
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val seedColor: Color? = null,
        val chapterPanelOpen: Boolean = false,
        val pagePanelOpen: Boolean = false,
        /** Every chapter (only the scanlator filter applied) */
        val allChapters: List<ChapterItem> = emptyList(),
        /** Chapters shown in the panel: filtered and sorted with the manga's chapter settings */
        val chapters: List<ChapterItem> = emptyList(),
        val chaptersLoading: Boolean = false,
        val chaptersError: String? = null,
        val availableScanlators: Set<String> = emptySet(),
        val excludedScanlators: Set<String> = emptySet(),
        val selectedChapterId: Long? = null,
        val pages: List<Page>? = null,
        /** Set when the selected chapter is downloaded: its pages come from the files */
        val localPages: List<LocalPageThumbnail>? = null,
        val pagesLoading: Boolean = false,
        val pagesError: String? = null,
    ) {
        val selectedChapter: Chapter?
            get() = allChapters.firstOrNull { it.chapter.id == selectedChapterId }?.chapter

        val scanlatorFilterActive: Boolean
            get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()
    }
}
