package tachiyomi.data.updates

import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.logFirstQuery
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository

class UpdatesRepositoryImpl(
    private val databaseHandler: DatabaseHandler,
) : UpdatesRepository {

    override suspend fun awaitWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): List<UpdatesWithRelations> {
        return databaseHandler.awaitList {
            // KMK (r104): same rows and order as getUpdatesByReadStatus, newest-fetched first on the date_fetch index
            updatesViewQueries.updatesByReadStatus(
                read = read,
                after = after,
                limit = limit,
                mapper = ::mapUpdatesWithRelations,
            )
        }
    }

    override fun subscribeAll(
        after: Long,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
    ): Flow<List<UpdatesWithRelations>> {
        // KMK (r104): `updatesWithFilters` (same rows and order) walks all chapters newest-fetched first and stops
        // at the limit, which is fast while plenty of chapters match. The unread, started and bookmarked filters
        // can match few, and then the walk reads every chapter, so those keep the old query (favourites first,
        // then a sort).
        val narrowFilter = unread != null || started != null || bookmarked != null
        return databaseHandler.subscribeToList {
            if (narrowFilter) {
                updatesViewQueries.getRecentUpdatesWithFilters(
                    after = after,
                    limit = limit,
                    // invert because unread in Kotlin -> read column in SQL
                    read = unread?.let { !it },
                    started = started?.toLong(),
                    bookmarked = bookmarked,
                    hideExcludedScanlators = hideExcludedScanlators.toLong(),
                    mapper = ::mapUpdatesWithRelations,
                )
            } else {
                updatesViewQueries.updatesWithFilters(
                    after = after,
                    limit = limit,
                    read = null,
                    started = null,
                    bookmarked = null,
                    hideExcludedScanlators = hideExcludedScanlators.toLong(),
                    mapper = ::mapUpdatesWithRelations,
                )
            }
        }
            // KMK (r104)
            .logFirstQuery("Updates")
    }

    override fun subscribeWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): Flow<List<UpdatesWithRelations>> {
        return databaseHandler.subscribeToList {
            // KMK (r104): same rows and order as getUpdatesByReadStatus, newest-fetched first on the date_fetch index
            updatesViewQueries.updatesByReadStatus(
                read = read,
                after = after,
                limit = limit,
                mapper = ::mapUpdatesWithRelations,
            )
        }
    }

    private fun mapUpdatesWithRelations(
        mangaId: Long,
        mangaTitle: String,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        sourceId: Long,
        favorite: Boolean,
        thumbnailUrl: String?,
        coverLastModified: Long,
        @Suppress("UNUSED_PARAMETER") dateUpload: Long,
        dateFetch: Long,
        excludedScanlator: String?,
    ): UpdatesWithRelations = UpdatesWithRelations(
        mangaId = mangaId,
        // SY -->
        ogMangaTitle = mangaTitle,
        // SY <--
        chapterId = chapterId,
        chapterName = chapterName,
        scanlator = scanlator,
        chapterUrl = chapterUrl,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        sourceId = sourceId,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = sourceId,
            isMangaFavorite = favorite,
            ogUrl = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
