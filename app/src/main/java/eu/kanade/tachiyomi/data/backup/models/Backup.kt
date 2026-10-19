package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class Backup(
    @ProtoNumber(1) val backupManga: List<BackupManga>,
    @ProtoNumber(2) var backupCategories: List<BackupCategory> = emptyList(),
    // @ProtoNumber(100) var backupBrokenSources, legacy source model with non-compliant proto number,
    @ProtoNumber(101) var backupSources: List<BackupSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    @ProtoNumber(106) var backupExtensionStores: List<BackupExtensionStore> = emptyList(),
    // SY specific values
    @ProtoNumber(600) var backupSavedSearches: List<BackupSavedSearch> = emptyList(),
    // KMK -->
    // Global Popular/Latest feeds
    @ProtoNumber(610) var backupFeeds: List<BackupFeed> = emptyList(),
    // Anime (merged from Anikku)
    @ProtoNumber(700) var backupAnime: List<BackupAnime> = emptyList(),
    @ProtoNumber(701) var backupAnimeExtensionStores: List<BackupExtensionStore> = emptyList(),
    // Anime categories with their order
    @ProtoNumber(702) var backupAnimeCategories: List<BackupAnimeCategory> = emptyList(),
    // Sources made in the Code editor
    @ProtoNumber(710) var backupJsSources: List<BackupJsSource> = emptyList(),
    // KMK <--
)
