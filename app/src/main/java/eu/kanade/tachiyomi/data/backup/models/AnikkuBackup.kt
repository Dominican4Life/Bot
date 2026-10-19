package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * KMK: read-only models of Anikku / Aniyomi backups (.tachibk), used only to pull their anime into our
 * [BackupAnime] (see AnikkuBackupConverter). Field numbers follow Anikku's Backup / LegacyBackup /
 * BackupManga / BackupChapter / BackupTracking / BackupHistory / BackupCategory. Never written.
 *
 * - Anikku: anime at 501, categories at 502 (500 = isLegacy, 503 = sources)
 * - Older Aniyomi backups (LegacyBackup): anime at 3, anime categories at 4 (manga stay at 1 / 2)
 */
@Serializable
data class AnikkuBackup(
    // Older Aniyomi
    @ProtoNumber(3) val legacyAnime: List<AnikkuBackupAnime> = emptyList(),
    @ProtoNumber(4) val legacyCategories: List<AnikkuBackupCategory> = emptyList(),
    // Anikku
    @ProtoNumber(501) val anime: List<AnikkuBackupAnime> = emptyList(),
    @ProtoNumber(502) val categories: List<AnikkuBackupCategory> = emptyList(),
)

/** KMK: only the per-source settings of an Anikku backup (same model as ours), read on their own */
@Serializable
data class AnikkuBackupSourcePreferences(
    @ProtoNumber(105) val backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
)

@Serializable
data class AnikkuBackupCategory(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val order: Long = 0,
)

@Serializable
data class AnikkuBackupAnime(
    @ProtoNumber(1) val source: Long = 0,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val title: String = "",
    @ProtoNumber(4) val artist: String? = null,
    @ProtoNumber(5) val author: String? = null,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val genre: List<String> = emptyList(),
    @ProtoNumber(8) val status: Int = 0,
    @ProtoNumber(9) val thumbnailUrl: String? = null,
    @ProtoNumber(13) val dateAdded: Long = 0,
    @ProtoNumber(16) val episodes: List<AnikkuBackupEpisode> = emptyList(),
    // The categories' order values
    @ProtoNumber(17) val categories: List<Long> = emptyList(),
    @ProtoNumber(18) val tracking: List<AnikkuBackupTracking> = emptyList(),
    @ProtoNumber(100) val favorite: Boolean = true,
    @ProtoNumber(101) val episodeFlags: Int = 0,
    @ProtoNumber(104) val history: List<AnikkuBackupHistory> = emptyList(),
    @ProtoNumber(108) val excludedScanlators: List<String> = emptyList(),
    @ProtoNumber(110) val notes: String = "",
    @ProtoNumber(111) val initialized: Boolean = false,
    @ProtoNumber(500) val backgroundUrl: String? = null,
    // Seasons: parentId / id only link entries inside the backup
    @ProtoNumber(502) val parentId: Long? = null,
    @ProtoNumber(503) val id: Long? = null,
    @ProtoNumber(504) val seasonFlags: Long = 0,
    @ProtoNumber(505) val seasonNumber: Double = -1.0,
    @ProtoNumber(506) val seasonSourceOrder: Long = 0,
    // FetchType ordinal (0 = Seasons, 1 = Episodes)
    @ProtoNumber(507) val fetchType: Int = 1,
    @ProtoNumber(602) val customStatus: Int = 0,
    @ProtoNumber(603) val customThumbnailUrl: String? = null,
    @ProtoNumber(800) val customTitle: String? = null,
    @ProtoNumber(801) val customArtist: String? = null,
    @ProtoNumber(802) val customAuthor: String? = null,
    @ProtoNumber(804) val customDescription: String? = null,
    @ProtoNumber(805) val customGenre: List<String> = emptyList(),
)

@Serializable
data class AnikkuBackupEpisode(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val scanlator: String? = null,
    @ProtoNumber(4) val seen: Boolean = false,
    @ProtoNumber(5) val bookmark: Boolean = false,
    @ProtoNumber(6) val lastSecondSeen: Long = 0,
    @ProtoNumber(7) val dateFetch: Long = 0,
    @ProtoNumber(8) val dateUpload: Long = 0,
    @ProtoNumber(9) val episodeNumber: Float = 0f,
    @ProtoNumber(10) val sourceOrder: Long = 0,
    // Older Aniyomi kept the filler mark here
    @ProtoNumber(15) val fillermarkLegacy: Boolean = false,
    @ProtoNumber(16) val totalSeconds: Long = 0,
    @ProtoNumber(501) val fillermark: Boolean = false,
    @ProtoNumber(502) val summary: String? = null,
    @ProtoNumber(503) val previewUrl: String? = null,
)

@Serializable
data class AnikkuBackupHistory(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val lastRead: Long = 0,
    @ProtoNumber(3) val readDuration: Long = 0,
)

@Serializable
data class AnikkuBackupTracking(
    @ProtoNumber(1) val syncId: Int = 0,
    @ProtoNumber(2) val libraryId: Long = 0,
    @ProtoNumber(3) val mediaIdInt: Int = 0,
    @ProtoNumber(4) val trackingUrl: String = "",
    @ProtoNumber(5) val title: String = "",
    @ProtoNumber(6) val lastEpisodeSeen: Float = 0f,
    @ProtoNumber(7) val totalEpisodes: Int = 0,
    @ProtoNumber(8) val score: Float = 0f,
    @ProtoNumber(9) val status: Int = 0,
    @ProtoNumber(10) val startedWatchingDate: Long = 0,
    @ProtoNumber(11) val finishedWatchingDate: Long = 0,
    @ProtoNumber(12) val private: Boolean = false,
    @ProtoNumber(100) val mediaId: Long = 0,
)
