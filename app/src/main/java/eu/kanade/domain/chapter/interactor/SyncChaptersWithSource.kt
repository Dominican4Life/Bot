package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.isEhBasedManga
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.chapter.ChapterSanitizer
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.injectLazy
import java.lang.Long.max
import java.time.ZonedDateTime
import java.util.TreeSet
import kotlin.math.roundToLong

class SyncChaptersWithSource(
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterRepository: ChapterRepository,
    private val shouldUpdateDbChapter: ShouldUpdateDbChapter,
    private val updateManga: UpdateManga,
    private val updateChapter: UpdateChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val libraryPreferences: LibraryPreferences,
) {
    // KMK: the chapter writes below run in one transaction (not a constructor parameter, so DomainModule stays)
    private val handler: DatabaseHandler by injectLazy()

    /**
     * Method to synchronize db chapters with source ones
     *
     * @param rawSourceChapters the chapters from the source.
     * @param manga the manga the chapters belong to.
     * @param source the source the manga belongs to.
     * @return Newly added chapters
     */
    suspend fun await(
        rawSourceChapters: List<SChapter>,
        manga: Manga,
        source: Source,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): List<Chapter> {
        if (rawSourceChapters.isEmpty() && !source.isLocal()) {
            throw NoChaptersException()
        }

        val now = ZonedDateTime.now()
        val nowMillis = now.toInstant().toEpochMilli()

        val sourceChapters = rawSourceChapters
            .distinctBy { it.url }
            .mapIndexed { i, sChapter ->
                Chapter.create()
                    .copyFromSChapter(sChapter)
                    .copy(name = with(ChapterSanitizer) { sChapter.name.sanitize(manga.title) })
                    .copy(mangaId = manga.id, sourceOrder = i.toLong())
            }

        val dbChapters = getChaptersByMangaId.await(manga.id)

        val newChapters = mutableListOf<Chapter>()
        val updatedChapters = mutableListOf<Chapter>()
        // KMK: mutable so chapters whose url changed can be taken out (see matchChangedUrls)
        val removedChapters = dbChapters.filterNot { dbChapter ->
            sourceChapters.any { sourceChapter ->
                dbChapter.url == sourceChapter.url
            }
        }.toMutableList()
        // KMK: urls of added chapters that came without an upload date (they get a made-up one below)
        val undatedNewUrls = mutableSetOf<String>()
        // KMK: download folders to rename (old, new); done together with the database writes, so a cancelled
        // sync can't leave a renamed folder behind a chapter row that still has the old name
        val renames = mutableListOf<Pair<Chapter, Chapter>>()

        // Used to not set upload date of older chapters
        // to a higher value than newer chapters
        var maxSeenUploadDate = 0L

        for (sourceChapter in sourceChapters) {
            var chapter = sourceChapter

            // Update metadata from source if necessary.
            if (source is HttpSource) {
                val sChapter = chapter.toSChapter()
                @Suppress("DEPRECATION")
                source.prepareNewChapter(sChapter, manga.toSManga())
                chapter = chapter.copyFromSChapter(sChapter)
            }

            // Recognize chapter number for the chapter.
            val chapterNumber = ChapterRecognition.parseChapterNumber(
                manga.title,
                chapter.name,
                chapter.chapterNumber,
            )
            chapter = chapter.copy(chapterNumber = chapterNumber)

            val dbChapter = dbChapters.find { it.url == chapter.url }

            if (dbChapter == null) {
                if (chapter.dateUpload == 0L) undatedNewUrls.add(chapter.url)
                val toAddChapter = if (chapter.dateUpload == 0L) {
                    val altDateUpload = if (maxSeenUploadDate == 0L) nowMillis else maxSeenUploadDate
                    chapter.copy(dateUpload = altDateUpload)
                } else {
                    maxSeenUploadDate = max(maxSeenUploadDate, sourceChapter.dateUpload)
                    chapter
                }
                newChapters.add(toAddChapter)
            } else {
                if (shouldUpdateDbChapter.await(dbChapter, chapter)) {
                    val shouldRenameChapter = downloadProvider.isChapterDirNameChanged(dbChapter, chapter) &&
                        downloadManager.isChapterDownloaded(
                            dbChapter.name,
                            dbChapter.scanlator,
                            dbChapter.url,
                            // SY -->
                            // manga.title,
                            manga.ogTitle,
                            // SY <--
                            manga.source,
                        )

                    if (shouldRenameChapter) {
                        renames.add(dbChapter to chapter)
                    }

                    var toChangeChapter = dbChapter.copy(
                        name = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        scanlator = chapter.scanlator,
                        sourceOrder = chapter.sourceOrder,
                        memo = chapter.memo,
                    )

                    if (chapter.dateUpload != 0L) {
                        toChangeChapter = toChangeChapter.copy(dateUpload = chapter.dateUpload)
                    }
                    updatedChapters.add(toChangeChapter)
                }
            }
        }

        // KMK: a chapter whose url changed (e.g. "/manga/x/1.0" saved by an extension, "/manga/x/1" from the JS
        // source that replaced it) used to be deleted and added again: its history went with it (so the title
        // dropped under the "Last read" sort), and its last page read and id too. A removed and an added chapter
        // with the same chapter number are the same chapter: the saved row gets the new url instead.
        if (!manga.isEhBasedManga() && newChapters.isNotEmpty() && removedChapters.isNotEmpty()) {
            val pairs = matchChangedUrls(removedChapters, newChapters)
            // KMK: one pass each over the lists instead of one per pair
            val movedUrls = pairs.mapTo(HashSet()) { it.second.url }
            val movedIds = pairs.mapTo(HashSet()) { it.first.id }
            newChapters.removeAll { it.url in movedUrls }
            removedChapters.removeAll { it.id in movedIds }
            for ((old, added) in pairs) {
                val shouldRenameChapter = downloadProvider.isChapterDirNameChanged(old, added) &&
                    downloadManager.isChapterDownloaded(
                        old.name,
                        old.scanlator,
                        old.url,
                        manga.ogTitle,
                        manga.source,
                    )
                if (shouldRenameChapter) {
                    renames.add(old to added)
                }
                var moved = old.copy(
                    url = added.url,
                    name = added.name,
                    chapterNumber = added.chapterNumber,
                    scanlator = added.scanlator,
                    sourceOrder = added.sourceOrder,
                    memo = added.memo,
                )
                if (added.url !in undatedNewUrls) {
                    moved = moved.copy(dateUpload = added.dateUpload)
                }
                updatedChapters.add(moved)
            }
        }

        // Return if there's nothing to add, delete, or update to avoid unnecessary db transactions.
        if (newChapters.isEmpty() && removedChapters.isEmpty() && updatedChapters.isEmpty()) {
            if (manualFetch || manga.fetchInterval == 0 || manga.nextUpdate < fetchWindow.first) {
                updateManga.awaitUpdateFetchInterval(
                    manga,
                    now,
                    fetchWindow,
                )
            }
            return emptyList()
        }

        val changedOrDuplicateReadUrls = mutableSetOf<String>()

        val deletedChapterNumbers = TreeSet<Double>()
        val deletedReadChapterNumbers = TreeSet<Double>()
        val deletedBookmarkedChapterNumbers = TreeSet<Double>()

        val readChapterNumbers = dbChapters
            .asSequence()
            .filter { it.read && it.isRecognizedNumber }
            .map { it.chapterNumber }
            .toSet()

        removedChapters.forEach { chapter ->
            if (chapter.read) deletedReadChapterNumbers.add(chapter.chapterNumber)
            if (chapter.bookmark) deletedBookmarkedChapterNumbers.add(chapter.chapterNumber)
            deletedChapterNumbers.add(chapter.chapterNumber)
        }

        val deletedChapterNumberDateFetchMap = removedChapters.sortedByDescending { it.dateFetch }
            .associate { it.chapterNumber to it.dateFetch }

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead().get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW)

        // Date fetch is set in such a way that the upper ones will have bigger value than the lower ones
        // Sources MUST return the chapters from most to less recent, which is common.
        var itemCount = newChapters.size
        var updatedToAdd = newChapters.map { toAddItem ->
            var chapter = toAddItem.copy(dateFetch = nowMillis + itemCount--)

            if (chapter.chapterNumber in readChapterNumbers && markDuplicateAsRead) {
                changedOrDuplicateReadUrls.add(chapter.url)
                chapter = chapter.copy(read = true)
            }

            if (!chapter.isRecognizedNumber || chapter.chapterNumber !in deletedChapterNumbers) return@map chapter

            chapter = chapter.copy(
                read = chapter.chapterNumber in deletedReadChapterNumbers,
                bookmark = chapter.chapterNumber in deletedBookmarkedChapterNumbers,
            )

            // Try to use the fetch date of the original entry to not pollute 'Updates' tab
            deletedChapterNumberDateFetchMap[chapter.chapterNumber]?.let {
                chapter = chapter.copy(dateFetch = it)
            }

            changedOrDuplicateReadUrls.add(chapter.url)

            chapter
        }

        // --> EXH (carry over reading progress)
        if (manga.isEhBasedManga()) {
            val hasNewChapters = updatedToAdd.any { it.url !in changedOrDuplicateReadUrls }
            if (hasNewChapters) {
                val max = dbChapters.maxOfOrNull { it.lastPageRead }
                if (max != null && max > 0) {
                    updatedToAdd = updatedToAdd.map {
                        if (it.url !in changedOrDuplicateReadUrls) {
                            it.copy(lastPageRead = max)
                        } else {
                            it
                        }
                    }
                }
            }
        }
        // <-- EXH

        // KMK: the folder renames and the removals, additions and updates aren't cut by a cancellation half way,
        // and the three writes are one transaction (a chapter whose url changed is never deleted without its
        // update); the repository calls join that transaction
        val toAdd = updatedToAdd
        updatedToAdd = withContext(NonCancellable) {
            for ((old, new) in renames) {
                downloadManager.renameChapter(source, manga, old, new)
            }
            handler.await(inTransaction = true) {
                if (removedChapters.isNotEmpty()) {
                    val toDeleteIds = removedChapters.map { it.id }
                    chapterRepository.removeChaptersWithIds(toDeleteIds)
                }

                val added = if (toAdd.isNotEmpty()) chapterRepository.addAll(toAdd) else toAdd

                if (updatedChapters.isNotEmpty()) {
                    val chapterUpdates = updatedChapters.map { it.toChapterUpdate() }
                    updateChapter.awaitAll(chapterUpdates)
                }
                added
            }
        }
        updateManga.awaitUpdateFetchInterval(manga, now, fetchWindow)

        // Set this manga as updated since chapters were changed
        // Note that last_update actually represents last time the chapter list changed at all
        updateManga.awaitUpdateLastUpdate(manga.id)

        val excludedScanlators = getExcludedScanlators.await(manga.id).toHashSet()

        return updatedToAdd.filterNot { it.url in changedOrDuplicateReadUrls || it.scanlator in excludedScanlators }
    }

    /**
     * KMK: pairs of (removed saved chapter, added source chapter) that are the same chapter under a new url: the
     * same recognized chapter number, when that number is on one removed and one added chapter (and their
     * scanlators agree, or one of them has none); when several share the number (several scanlators), only
     * one-to-one matches with the same scanlator. Anything unclear stays a removal plus an addition, as before.
     * KMK: numbers are compared to 1/1000, so float noise (1.1 parsed two ways) still matches.
     */
    private fun matchChangedUrls(removed: List<Chapter>, added: List<Chapter>): List<Pair<Chapter, Chapter>> {
        val removedByNumber = removed.filter { it.isRecognizedNumber }.groupBy { numberKey(it) }
        val addedByNumber = added.filter { it.isRecognizedNumber }.groupBy { numberKey(it) }
        val pairs = mutableListOf<Pair<Chapter, Chapter>>()
        for ((number, adds) in addedByNumber) {
            val olds = removedByNumber[number] ?: continue
            if (olds.size == 1 && adds.size == 1) {
                val oldScanlator = olds[0].scanlator.orEmpty()
                val newScanlator = adds[0].scanlator.orEmpty()
                if (oldScanlator.isBlank() || newScanlator.isBlank() || oldScanlator == newScanlator) {
                    pairs.add(olds[0] to adds[0])
                }
                continue
            }
            val oldsByScanlator = olds.groupBy { it.scanlator.orEmpty() }
            for ((scanlator, sameAdds) in adds.groupBy { it.scanlator.orEmpty() }) {
                val sameOlds = oldsByScanlator[scanlator] ?: continue
                if (sameOlds.size == 1 && sameAdds.size == 1) pairs.add(sameOlds[0] to sameAdds[0])
            }
        }
        return pairs
    }

    private fun numberKey(chapter: Chapter): Long = (chapter.chapterNumber * 1000).roundToLong()
}
