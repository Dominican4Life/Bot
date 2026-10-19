package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.anime.track.AnimeTrackStatus
import eu.kanade.tachiyomi.data.backup.models.AnikkuBackup
import eu.kanade.tachiyomi.data.backup.models.AnikkuBackupAnime
import eu.kanade.tachiyomi.data.backup.models.AnikkuBackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.AnikkuBackupTracking
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeCategory
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeCustomInfo
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeTrack
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * KMK: Reads the anime out of an Anikku backup (anime at field 501) or an older Aniyomi backup (anime at 3),
 * and turns them into our [BackupAnime] so the normal anime restore takes them, seasons linked to their parent.
 */
object AnikkuBackupConverter {

    class Result(
        val anime: List<BackupAnime>,
        val categories: List<BackupAnimeCategory>,
        val sourcePreferences: List<BackupSourcePreferences>,
    )

    /** The anime found in [bytes], or null when it isn't an Anikku / Aniyomi backup with anime */
    fun convert(parser: ProtoBuf, bytes: ByteArray): Result? {
        val decoded = try {
            parser.decodeFromByteArray(AnikkuBackup.serializer(), bytes)
        } catch (_: Exception) {
            return null
        }
        val (anime, categories) = when {
            decoded.anime.isNotEmpty() || decoded.categories.isNotEmpty() -> decoded.anime to decoded.categories
            else -> decoded.legacyAnime to decoded.legacyCategories
        }
        val valid = anime.filter { it.url.isNotBlank() && it.source != 0L }
        if (valid.isEmpty() && categories.none { it.name.isNotBlank() }) return null
        val orderToName = categories.associate { it.order to it.name }
        // Seasons name their parent by its id inside the backup
        val byBackupId = valid.filter { it.id != null }.associateBy { it.id!! }
        val sourcePreferences = try {
            parser.decodeFromByteArray(AnikkuBackupSourcePreferences.serializer(), bytes).backupSourcePreferences
        } catch (_: Exception) {
            emptyList()
        }
        return Result(
            anime = valid.map { anime ->
                val parent = anime.parentId?.let { byBackupId[it] }?.takeIf { it !== anime }
                anime.toBackupAnime(orderToName).apply {
                    parentSource = parent?.source
                    parentUrl = parent?.url
                    seasonFlags = anime.seasonFlags
                    seasonNumber = anime.seasonNumber
                    seasonSourceOrder = anime.seasonSourceOrder
                }
            },
            categories = categories.filter { it.name.isNotBlank() }
                .map { BackupAnimeCategory(name = it.name, order = it.order) },
            sourcePreferences = sourcePreferences,
        )
    }

    private fun AnikkuBackupAnime.toBackupAnime(orderToName: Map<Long, String>) = BackupAnime(
        source = source,
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
        backgroundUrl = backgroundUrl,
        dateAdded = dateAdded,
        favorite = favorite,
        initialized = initialized,
        fetchType = fetchType,
        episodeFlags = episodeFlags.toLong(),
        episodes = episodes.filter { it.url.isNotBlank() }.map { ep ->
            BackupEpisode(
                url = ep.url,
                name = ep.name,
                scanlator = ep.scanlator,
                summary = ep.summary,
                previewUrl = ep.previewUrl,
                seen = ep.seen,
                bookmark = ep.bookmark,
                fillermark = ep.fillermark || ep.fillermarkLegacy,
                lastSecondSeen = ep.lastSecondSeen,
                totalSeconds = ep.totalSeconds,
                episodeNumber = ep.episodeNumber,
                sourceOrder = ep.sourceOrder,
                dateFetch = ep.dateFetch,
                dateUpload = ep.dateUpload,
            )
        },
        history = history.filter { it.url.isNotBlank() && it.lastRead > 0 }
            .map { BackupAnimeHistory(episodeUrl = it.url, lastSeen = it.lastRead) },
        categories = categories.mapNotNull { orderToName[it] }.distinct(),
        tracking = tracking.mapNotNull { it.toBackupTrack() },
        notes = notes,
        excludedScanlators = excludedScanlators,
        customInfo = BackupAnimeCustomInfo(
            title = customTitle?.takeIf { it.isNotBlank() },
            author = customAuthor,
            artist = customArtist,
            description = customDescription,
            genre = customGenre.takeIf { it.isNotEmpty() }?.joinToString(", "),
            status = customStatus.takeIf { it != 0 }?.toLong(),
            thumbnailUrl = customThumbnailUrl?.takeUnless { it.isBlank() || it.startsWith("file:") },
        ).takeIf { it != BackupAnimeCustomInfo() },
    )

    private fun AnikkuBackupTracking.toBackupTrack(): BackupAnimeTrack? {
        val trackerId = syncId.toLong()
        val remoteId = if (mediaIdInt != 0) mediaIdInt.toLong() else mediaId
        if (trackerId == 0L || remoteId == 0L) return null
        return BackupAnimeTrack(
            trackerId = trackerId,
            remoteId = remoteId,
            libraryId = libraryId.takeIf { it != 0L },
            title = title,
            lastEpisodeSeen = lastEpisodeSeen.toDouble(),
            totalEpisodes = totalEpisodes.toLong(),
            status = status(trackerId, status.toLong()),
            score = score(trackerId, score.toDouble()),
            remoteUrl = trackingUrl,
            startDate = startedWatchingDate,
            finishDate = finishedWatchingDate,
            private = private,
        )
    }

    /** Anikku keeps each tracker's own status numbers; ours are the shared [AnimeTrackStatus] */
    private fun status(trackerId: Long, status: Long): Long = when (trackerId) {
        // MyAnimeList
        1L -> when (status) {
            11L -> AnimeTrackStatus.WATCHING
            16L -> AnimeTrackStatus.PLAN_TO_WATCH
            17L -> AnimeTrackStatus.REWATCHING
            else -> status
        }
        // AniList, Kitsu
        2L, 3L -> when (status) {
            11L -> AnimeTrackStatus.WATCHING
            15L -> AnimeTrackStatus.PLAN_TO_WATCH
            16L -> AnimeTrackStatus.REWATCHING
            else -> status
        }
        // Bangumi
        5L -> when (status) {
            1L -> AnimeTrackStatus.PLAN_TO_WATCH
            3L -> AnimeTrackStatus.WATCHING
            4L -> AnimeTrackStatus.ON_HOLD
            5L -> AnimeTrackStatus.DROPPED
            else -> status
        }
        // Jellyfin
        102L -> when (status) {
            1L -> AnimeTrackStatus.PLAN_TO_WATCH
            2L -> AnimeTrackStatus.WATCHING
            3L -> AnimeTrackStatus.COMPLETED
            else -> status
        }
        else -> status
    }.takeIf { it in AnimeTrackStatus.all } ?: AnimeTrackStatus.WATCHING

    /** Ours is 0–10; Anikku keeps AniList's as 0–100 */
    private fun score(trackerId: Long, score: Double): Double =
        (if (trackerId == 2L) score / 10.0 else score).coerceIn(0.0, 10.0)
}
