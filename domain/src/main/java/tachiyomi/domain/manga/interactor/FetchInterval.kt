package tachiyomi.domain.manga.interactor

import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.applyFilter
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

class FetchInterval(
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    suspend fun toMangaUpdate(
        manga: Manga,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
    ): MangaUpdate {
        val interval = manga.fetchInterval.takeIf { it < 0 } ?: calculateInterval(
            chapters = getChaptersByMangaId.await(manga.id, applyFilter = true),
            zone = dateTime.zone,
        )
        return toMangaUpdate(manga, interval, dateTime, window)
    }

    // KMK -->
    /**
     * KMK (r96): [toMangaUpdate] from chapters the caller already has, so the chapter sync and the backup restore
     * don't read them again. [chapters] must be the title's whole list; [excludedScanlators] are left out and the
     * bookmark filter applied here, as `getChaptersByMangaId(applyFilter = true)` does in SQL.
     */
    fun toMangaUpdate(
        manga: Manga,
        chapters: List<Chapter>,
        excludedScanlators: Set<String>,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
    ): MangaUpdate {
        val interval = manga.fetchInterval.takeIf { it < 0 } ?: calculateInterval(
            chapters = filterForInterval(manga, chapters, excludedScanlators),
            zone = dateTime.zone,
        )
        return toMangaUpdate(manga, interval, dateTime, window)
    }

    /** The chapters `getChaptersByMangaId(applyFilter = true)` would return: no excluded scanlator, bookmark filter */
    private fun filterForInterval(manga: Manga, chapters: List<Chapter>, excludedScanlators: Set<String>): List<Chapter> {
        val bookmarkedFilter = manga.bookmarkedFilter
        return chapters.filter { chapter ->
            chapter.scanlator !in excludedScanlators && applyFilter(bookmarkedFilter) { chapter.bookmark }
        }
    }
    // KMK <--

    private fun toMangaUpdate(
        manga: Manga,
        interval: Int,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
    ): MangaUpdate {
        val currentWindow = if (window.first == 0L && window.second == 0L) {
            getWindow(ZonedDateTime.now())
        } else {
            window
        }
        val nextUpdate = calculateNextUpdate(manga, interval, dateTime, currentWindow)

        return MangaUpdate(id = manga.id, nextUpdate = nextUpdate, fetchInterval = interval)
    }

    fun getWindow(dateTime: ZonedDateTime): Pair<Long, Long> {
        val today = dateTime.toLocalDate().atStartOfDay(dateTime.zone)
        val lowerBound = today.minusDays(GRACE_PERIOD)
        val upperBound = today.plusDays(GRACE_PERIOD)
        return Pair(lowerBound.toEpochSecond() * 1000, upperBound.toEpochSecond() * 1000 - 1)
    }

    internal fun calculateInterval(chapters: List<Chapter>, zone: ZoneId): Int {
        val chapterWindow = if (chapters.size <= 8) 3 else 10

        val uploadDates = chapters.asSequence()
            .filter { it.dateUpload > 0L }
            .sortedByDescending { it.dateUpload }
            .map {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.dateUpload), zone)
                    .toLocalDate()
                    .atStartOfDay()
            }
            .distinct()
            .take(chapterWindow)
            .toList()

        val fetchDates = chapters.asSequence()
            .sortedByDescending { it.dateFetch }
            .map {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.dateFetch), zone)
                    .toLocalDate()
                    .atStartOfDay()
            }
            .distinct()
            .take(chapterWindow)
            .toList()

        val interval = when {
            // Enough upload date from source
            uploadDates.size >= 3 -> {
                val ranges = uploadDates.windowed(2).map { x -> x[1].until(x[0], ChronoUnit.DAYS) }.sorted()
                ranges[(ranges.size - 1) / 2].toInt()
            }
            // Enough fetch date from client
            fetchDates.size >= 3 -> {
                val ranges = fetchDates.windowed(2).map { x -> x[1].until(x[0], ChronoUnit.DAYS) }.sorted()
                ranges[(ranges.size - 1) / 2].toInt()
            }
            // Default to 7 days
            else -> 7
        }

        return interval.coerceIn(1, MAX_INTERVAL)
    }

    private fun calculateNextUpdate(
        manga: Manga,
        interval: Int,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
    ): Long {
        if (manga.nextUpdate in window.first.rangeTo(window.second + 1)) {
            return manga.nextUpdate
        }

        val latestDate = ZonedDateTime.ofInstant(
            if (manga.lastUpdate > 0) Instant.ofEpochMilli(manga.lastUpdate) else Instant.now(),
            dateTime.zone,
        )
            .toLocalDate()
            .atStartOfDay()
        val timeSinceLatest = ChronoUnit.DAYS.between(latestDate, dateTime).toInt()
        val cycle = timeSinceLatest.floorDiv(
            interval.absoluteValue.takeIf { interval < 0 }
                ?: increaseInterval(interval, timeSinceLatest, increaseWhenOver = 10),
        )
        return latestDate.plusDays((cycle + 1) * interval.absoluteValue.toLong()).toEpochSecond(dateTime.offset) * 1000
    }

    private fun increaseInterval(delta: Int, timeSinceLatest: Int, increaseWhenOver: Int): Int {
        if (delta >= MAX_INTERVAL) return MAX_INTERVAL

        // double delta again if missed more than 9 check in new delta
        val cycle = timeSinceLatest.floorDiv(delta) + 1
        return if (cycle > increaseWhenOver) {
            increaseInterval(delta * 2, timeSinceLatest, increaseWhenOver)
        } else {
            delta
        }
    }

    companion object {
        const val MAX_INTERVAL = 28

        private const val GRACE_PERIOD = 1L

        // KMK -->
        const val MANUAL_DISABLE = 99999 // 274 years in future
        // KMK <--
    }
}
