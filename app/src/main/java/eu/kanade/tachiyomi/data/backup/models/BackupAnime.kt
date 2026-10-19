package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** KMK: An anime in the library, with its episodes and watch history (merged from Anikku). */
@Serializable
data class BackupAnime(
    @ProtoNumber(1) var source: Long,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var artist: String? = null,
    @ProtoNumber(5) var author: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: List<String> = emptyList(),
    @ProtoNumber(8) var status: Int = 0,
    @ProtoNumber(9) var thumbnailUrl: String? = null,
    @ProtoNumber(10) var backgroundUrl: String? = null,
    @ProtoNumber(11) var dateAdded: Long = 0,
    @ProtoNumber(12) var favorite: Boolean = true,
    @ProtoNumber(13) var initialized: Boolean = false,
    @ProtoNumber(14) var fetchType: Int = 1,
    @ProtoNumber(15) var episodeFlags: Long = 0,
    @ProtoNumber(16) var episodes: List<BackupEpisode> = emptyList(),
    @ProtoNumber(17) var history: List<BackupAnimeHistory> = emptyList(),
    // KMK: names of its anime categories
    @ProtoNumber(18) var categories: List<String> = emptyList(),
    // KMK: tracker entries (anime_sync)
    @ProtoNumber(19) var tracking: List<BackupAnimeTrack> = emptyList(),
    // KMK: the user's notes and hidden scanlators (kept in preferences by anime id)
    @ProtoNumber(20) var notes: String = "",
    @ProtoNumber(21) var excludedScanlators: List<String> = emptyList(),
    // KMK: edited info (anime_custom_info); a local custom cover file is not backed up
    @ProtoNumber(22) var customInfo: BackupAnimeCustomInfo? = null,
    // KMK: seasons (migration 56). The parent is found by its source + url when restoring.
    @ProtoNumber(23) var parentSource: Long? = null,
    @ProtoNumber(24) var parentUrl: String? = null,
    @ProtoNumber(25) var seasonFlags: Long = 0,
    @ProtoNumber(26) var seasonNumber: Double = -1.0,
    @ProtoNumber(27) var seasonSourceOrder: Long = 0,
)

/** KMK: one tracker entry of an anime (anime_sync) */
@Serializable
data class BackupAnimeTrack(
    @ProtoNumber(1) var trackerId: Long,
    @ProtoNumber(2) var remoteId: Long,
    @ProtoNumber(3) var libraryId: Long? = null,
    @ProtoNumber(4) var title: String = "",
    @ProtoNumber(5) var lastEpisodeSeen: Double = 0.0,
    @ProtoNumber(6) var totalEpisodes: Long = 0,
    @ProtoNumber(7) var status: Long = 0,
    @ProtoNumber(8) var score: Double = 0.0,
    @ProtoNumber(9) var remoteUrl: String = "",
    @ProtoNumber(10) var startDate: Long = 0,
    @ProtoNumber(11) var finishDate: Long = 0,
    @ProtoNumber(12) var private: Boolean = false,
)

/** KMK: what the user edited on an anime (anime_custom_info); null keeps the source's value */
@Serializable
data class BackupAnimeCustomInfo(
    @ProtoNumber(1) var title: String? = null,
    @ProtoNumber(2) var author: String? = null,
    @ProtoNumber(3) var artist: String? = null,
    @ProtoNumber(4) var description: String? = null,
    @ProtoNumber(5) var genre: String? = null,
    @ProtoNumber(6) var status: Long? = null,
    @ProtoNumber(7) var thumbnailUrl: String? = null,
)

/** KMK: an anime category with its place in the category list */
@Serializable
data class BackupAnimeCategory(
    @ProtoNumber(1) var name: String,
    @ProtoNumber(2) var order: Long = 0,
)

@Serializable
data class BackupEpisode(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var scanlator: String? = null,
    @ProtoNumber(4) var summary: String? = null,
    @ProtoNumber(5) var previewUrl: String? = null,
    @ProtoNumber(6) var seen: Boolean = false,
    @ProtoNumber(7) var bookmark: Boolean = false,
    @ProtoNumber(8) var fillermark: Boolean = false,
    @ProtoNumber(9) var lastSecondSeen: Long = 0,
    @ProtoNumber(10) var totalSeconds: Long = 0,
    @ProtoNumber(11) var episodeNumber: Float = 0f,
    @ProtoNumber(12) var sourceOrder: Long = 0,
    @ProtoNumber(13) var dateFetch: Long = 0,
    @ProtoNumber(14) var dateUpload: Long = 0,
)

@Serializable
data class BackupAnimeHistory(
    @ProtoNumber(1) var episodeUrl: String,
    @ProtoNumber(2) var lastSeen: Long,
)
