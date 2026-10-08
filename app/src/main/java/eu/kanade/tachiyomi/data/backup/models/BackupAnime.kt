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
