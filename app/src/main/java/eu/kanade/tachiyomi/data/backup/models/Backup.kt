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
    // KMK (r86): anime extension (Aniyomi) source settings, like 105 for manga, under the "Source settings" option
    @ProtoNumber(703) var backupAnimeSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    // KMK (r86): player custom buttons (custom_buttons table), under the "App settings" option
    @ProtoNumber(704) var backupCustomButtons: List<BackupCustomButton> = emptyList(),
    // Sources made in the Code editor
    @ProtoNumber(710) var backupJsSources: List<BackupJsSource> = emptyList(),
    // KMK (r115): files kept as (path under filesDir, bytes). New numbers start at 900 so they never meet another
    // app's fields; empty in older backups, and other apps skip numbers they don't know.
    // Source Lab files: each JS source's history/, testruns/, lint.json, health.json, explain.json, and sourcelab/
    @ProtoNumber(900) var backupSourceLabFiles: List<BackupFile> = emptyList(),
    // JS source storage (js_storage/<key>/): only with its option ticked (may hold logins)
    @ProtoNumber(901) var backupJsStorageFiles: List<BackupFile> = emptyList(),
    // Custom covers of manga (CoverCache) and anime (anime_covers/), matched by source + url on restore
    @ProtoNumber(902) var backupCustomCovers: List<BackupCustomCover> = emptyList(),
    // KMK <--
)
