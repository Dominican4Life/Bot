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
import com.hippo.unifile.UniFile
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
import exh.source.getMainSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

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

    // KMK: one chapter sync at a time per preview: the refresh from the source and fetchPageList's retry share it
    private val chapterSyncMutex = Mutex()

    // KMK: successful syncs per title, so the retry can skip its own sync when one finished while it waited
    private val chapterSyncs = ConcurrentHashMap<Long, Int>()

    // KMK: the chapter last shown, so a replacement can keep its chapter number (see selectDefaultChapter)
    @Volatile
    private var lastSelectedChapter: Chapter? = null

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
                // KMK: queueState only changes when downloads are added or removed; a download starting or failing
                // only changes its status, so without this the row stayed on "queued" with no progress ring
                // KMK: only this title's downloads (a merged title's chapters belong to its parts, so all of them)
                downloadManager.statusFlow()
                    .filter { it.manga.id == manga.id || manga.source == exh.source.MERGED_SOURCE_ID }
                    .map { }
                    .onStart { emit(Unit) },
            ) { (m, chapters), _, queue, _ -> Triple(m, chapters, queue) }
                .collectLatest { (m, chapters, queue) ->
                    val isLocal = m.isLocal()
                    // KMK: one lookup per chapter instead of a scan of the whole queue
                    val queueById = queue.associateBy { it.chapter.id }
                    val all = chapters.map { chapter ->
                        val active = queueById[chapter.id]
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
                    // KMK: the first chapter is previewed when the refresh from the source ends (refreshFromSource);
                    // here only a selected chapter that left the list is replaced, from the chapters as saved now
                    val selected = state.value.selectedChapterId
                    if (selected != null && all.none { it.chapter.id == selected }) {
                        selectDefaultChapter(m.id, getMangaWithChapters.awaitChapters(m.id, applyFilter = true))
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
                selectDefaultChapter(manga.id, getMangaWithChapters.awaitChapters(manga.id, applyFilter = true))
                return@launchIO
            }
            if (force) {
                mutableState.update { if (it.manga?.id != manga.id) it else it.copy(chaptersLoading = true) }
            }
            val result = chapterSyncMutex.withLock {
                updateMangaFromRemote(source = source, manga = manga, fetchDetails = true, fetchChapters = true)
                    .also { if (it.isSuccess) chapterSyncs.merge(manga.id, 1) { a, b -> a + b } }
            }
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
            // KMK: the first chapter's pages are read now, from the chapter list as the source just gave it
            selectDefaultChapter(manga.id, getMangaWithChapters.awaitChapters(manga.id, applyFilter = true))
        }
    }

    private val defaultChapterLock = Any()

    /**
     * KMK: Previews the first chapter of [chapters] (the title's saved chapters) when no chapter is selected, or when
     * the selected one is gone from the list (the source removed it; before, the page grid stayed empty with its
     * image requests cancelled). Waits while the chapters are being refreshed from the source: reading pages of a
     * saved chapter before that used its old url (a JS source answers "Refresh the chapter list"), and the refresh
     * then replaced the chapter under the grid. Called by the chapter listener and when the refresh ends; the lock
     * makes sure only one of them starts the pages.
     */
    private fun selectDefaultChapter(mangaId: Long, chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        val pick = synchronized(defaultChapterLock) {
            val s = state.value
            if (s.manga?.id != mangaId || s.chaptersLoading) return
            val selected = s.selectedChapterId
            if (selected != null && chapters.any { it.id == selected }) return
            // KMK: a selected chapter whose id changed (re-added by the sync) is replaced by the one with its
            // chapter number (same scanlator first), not by the first chapter
            val previous = lastSelectedChapter
                ?.takeIf { selected != null && it.id == selected && it.isRecognizedNumber }
            val sameNumber = previous?.let { p -> chapters.filter { it.chapterNumber == p.chapterNumber } }.orEmpty()
            val first = sameNumber.firstOrNull { it.scanlator == previous?.scanlator }
                ?: sameNumber.firstOrNull()
                ?: chapters.maxBy { it.sourceOrder }
            mutableState.update { if (it.manga?.id != mangaId) it else it.copy(selectedChapterId = first.id) }
            first
        }
        selectChapter(pick)
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
        // KMK: a late call for a title that's no longer previewed is dropped (a merged title's chapters belong to
        // its parts, so their manga id differs from the title's)
        var applied = false
        mutableState.update {
            val m = it.manga
            applied = m != null && (m.id == chapter.mangaId || m.source == exh.source.MERGED_SOURCE_ID)
            if (!applied) {
                it
            } else {
                it.copy(
                    selectedChapterId = chapter.id,
                    pages = null,
                    pagesLoading = true,
                    pagesError = null,
                    previewModels = null,
                )
            }
        }
        if (!applied) return
        pagesJob?.cancel()
        lastSelectedChapter = chapter
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
            // KMK: reading the files doesn't stop when this job is cancelled (a newer chapter was tapped)
            ensureActive()
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
            // KMK: only for the chapter still selected: a slow, older selection mustn't clear a newer one's pages
            mutableState.update {
                if (it.selectedChapterId != chapter.id) it else it.copy(localPages = null, previewModels = null)
            }
            if (httpSource == null) {
                mutableState.update { if (it.selectedChapterId != chapter.id) it else it.copy(pagesLoading = false) }
                return@launchIO
            }
            // KMK: small previews when the source has them (E-Hentai, NHentai, Lanraragi, or a JS source's
            // getPagePreviews), like TachiyomiSY: a few small images instead of every full page
            val previews = runCatching { previewModels(chapter, httpSource) }
                .onFailure {
                    if (it is CancellationException) throw it
                    logcat(LogPriority.WARN, it) { "Page previews failed, loading the pages" }
                }
                .getOrNull()
            if (!previews.isNullOrEmpty()) {
                mutableState.update {
                    if (it.selectedChapterId != chapter.id) {
                        it
                    } else {
                        it.copy(
                            pages = previews.indices.map { index -> Page(index) },
                            previewModels = previews,
                            pagesLoading = false,
                        )
                    }
                }
                return@launchIO
            }
            try {
                val pages = try {
                    chapterCache.getPageListFromCache(chapter)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    fetchPageList(chapter, httpSource)
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

    /**
     * KMK: [chapter]'s pages from the source (then cached). A source that says its chapter list must be refreshed
     * first (a JS source throwing "Refresh the chapter list.", when the saved chapter url is out of date) gets one
     * refresh of the chapters, and one more try with the chapter as saved after it.
     */
    private suspend fun fetchPageList(chapter: Chapter, httpSource: HttpSource): List<Page> {
        val (used, pages) = try {
            chapter to httpSource.getPageList(chapter.toSChapter())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e.message?.contains(REFRESH_CHAPTERS_ERROR, ignoreCase = true) != true) throw e
            // KMK: the chapter's own title (the preview may have moved on), and only one of this source's titles
            val manga = runCatching { getMangaWithChapters.awaitManga(chapter.mangaId) }.getOrNull()
            if (manga == null || manga.source != source.id) throw e
            // KMK: never a second sync next to the preview's refresh; one that ended while waiting is enough
            val syncsBefore = chapterSyncs[manga.id] ?: 0
            chapterSyncMutex.withLock {
                if ((chapterSyncs[manga.id] ?: 0) == syncsBefore) {
                    updateMangaFromRemote(source = source, manga = manga, fetchChapters = true).getOrThrow()
                    chapterSyncs.merge(manga.id, 1) { a, b -> a + b }
                }
            }
            val saved = getMangaWithChapters.awaitChapters(manga.id)
            val current = saved.firstOrNull { it.id == chapter.id }
                ?: saved.firstOrNull { chapter.isRecognizedNumber && it.chapterNumber == chapter.chapterNumber }
                ?: throw e
            current to httpSource.getPageList(current.toSChapter())
        }
        runCatching { chapterCache.putPageListToCache(used, pages) }
        return pages
    }

    /**
     * KMK: The small previews of [chapter]'s pages, as image models, or null when the source has none: a JS
     * source's getPagePreviews, or SY's page previews (E-Hentai, NHentai, Lanraragi) for a one-chapter title
     */
    private suspend fun previewModels(chapter: Chapter, httpSource: HttpSource): List<Any>? {
        (source as? eu.kanade.tachiyomi.jssource.JsMangaSource)?.let { js ->
            val pages = js.pagePreviews(chapter.url) ?: return null
            eu.kanade.tachiyomi.util.system.MemoryLog.event(
                "previews: ${source.name}, ${pages.size} from getPagePreviews",
            )
            return pages.map {
                eu.kanade.tachiyomi.data.coil.SourcePageThumbnail(httpSource, "preview:" + chapter.url, it)
            }
        }
        val manga = state.value.manga ?: return null
        if (source.getMainSource<eu.kanade.tachiyomi.source.PagePreviewSource>() == null) return null
        // Previews are of the title, so only for a title with one chapter (a gallery)
        if (state.value.allChapters.size != 1) return null
        val getPagePreviews = Injekt.get<eu.kanade.domain.manga.interactor.GetPagePreviews>()
        eu.kanade.tachiyomi.util.system.MemoryLog.event("previews started: ${source.name}, ${manga.title.take(60)}")
        val out = mutableListOf<Any>()
        var page = 1
        while (page <= MAX_PREVIEW_PAGES) {
            when (val result = getPagePreviews.await(manga, source, page)) {
                is eu.kanade.domain.manga.interactor.GetPagePreviews.Result.Success -> {
                    out.addAll(result.pagePreviews)
                    if (!result.hasNextPage) break
                }
                is eu.kanade.domain.manga.interactor.GetPagePreviews.Result.Error -> {
                    if (out.isEmpty()) throw result.error else break
                }
                else -> return null
            }
            page++
        }
        eu.kanade.tachiyomi.util.system.MemoryLog.event(
            "previews done: ${out.size} previews from ${minOf(page, MAX_PREVIEW_PAGES)} preview page(s)",
        )
        return out.ifEmpty { null }
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
        // KMK: a Local source title's chapters are its own files (never "downloaded"), so read them the way the
        // reader does (ChapterLoader): a folder of images or an archive. EPUBs get no grid.
        (source as? LocalSource)?.let { local ->
            return when (val format = local.getFormat(chapter.toSChapter())) {
                is Format.Archive -> archivePages(chapter, format.file)
                is Format.Directory -> format.file.listFiles().orEmpty()
                    .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
                    .sortedWith { a, b -> a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty()) }
                    .mapIndexed { index, file -> LocalPageThumbnail(chapter.id, index, fileUri = file.uri) }
                is Format.Epub -> null
            }
        }
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
        return if (dir.isFile) {
            archivePages(chapter, dir)
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

    /** KMK: the pages of an archive (a downloaded CBZ or a Local source archive), in the reader's order */
    private fun archivePages(chapter: Chapter, file: UniFile): List<LocalPageThumbnail> {
        val context = Injekt.get<Application>()
        val (names, videoPictures) = file.archiveReader(context).use { reader ->
            reader.useEntries { entries ->
                val all = entries.filter { it.isFile }.toList()
                val images = all
                    .filter { !ChapterVideoDownloads.isVideoFile(it.name) }
                    .filter { ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .map { it.name }
                images to ChapterVideoDownloads.picturesWithVideo(all.map { it.name })
            }
        }
        return names
            .sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
            .mapIndexed { index, name ->
                LocalPageThumbnail(
                    chapter.id,
                    index,
                    archiveUri = file.uri,
                    entryName = name,
                    isVideo = name in videoPictures,
                )
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
        /** KMK: set when the source's small previews are shown instead of the pages: one image model per page */
        val previewModels: List<Any>? = null,
    ) {
        val selectedChapter: Chapter?
            get() = allChapters.firstOrNull { it.chapter.id == selectedChapterId }?.chapter

        val scanlatorFilterActive: Boolean
            get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()
    }
}

// KMK: at most this many pages of previews are read (E-Hentai gives 20 or 40 per page)
private const val MAX_PREVIEW_PAGES = 30

// KMK: what a source's getPageList says when the saved chapter url is out of date (see fetchPageList)
private const val REFRESH_CHAPTERS_ERROR = "Refresh the chapter list"
