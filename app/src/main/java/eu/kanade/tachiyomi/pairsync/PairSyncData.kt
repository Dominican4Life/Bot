package eu.kanade.tachiyomi.pairsync

import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.backup.AnimeBackupHelper
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.devicefiles.DeviceFilesIds
import eu.kanade.tachiyomi.sumizuri.SumizuriManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * KMK: What to sync, chosen on the device that presses Sync (the other device sends the same choice back).
 * A null list means all: [mangaCategories] are category names ("" is Default), matched by name on each device since
 * their ids differ; [sourceKeys] are Code editor source keys, the same on both devices once synced.
 */
@Serializable
data class SyncSelection(
    val mangaLibrary: Boolean = true,
    val mangaCategories: Set<String>? = null,
    val animeLibrary: Boolean = true,
    val sources: Boolean = true,
    val sourceKeys: Set<String>? = null,
    val mangaHistory: Boolean = true,
    val animeHistory: Boolean = true,
) {
    val isEmpty: Boolean get() = !mangaLibrary && !animeLibrary && !sources && !mangaHistory && !animeHistory

    fun describe(): String = buildList {
        if (mangaLibrary) add(if (mangaCategories == null) "manga library" else "manga in ${mangaCategories.size} categories")
        if (animeLibrary) add("anime library")
        if (sources) add(if (sourceKeys == null) "all sources" else "${sourceKeys.size} sources")
        when {
            mangaHistory && animeHistory -> add("all history")
            mangaHistory -> add("manga history")
            animeHistory -> add("anime history")
        }
    }.joinToString(", ")
}

@Serializable
private class SyncSource(val info: String, val code: String)

/** Everything one device sends: its Code editor sources, and its manga and anime as a backup */
@Serializable
private class SyncPayload(
    val sources: List<SyncSource> = emptyList(),
    /** A gzipped app backup with the chosen manga and anime, in Base64 */
    val backup: String? = null,
)

data class SyncSummary(
    val sourcesAdded: Int = 0,
    val manga: Int = 0,
    val anime: Int = 0,
    val errors: Int = 0,
) {
    override fun toString(): String = buildList {
        add(if (sourcesAdded == 1) "1 source" else "$sourcesAdded sources")
        add("$manga manga")
        add("$anime anime")
        if (errors > 0) add("$errors couldn't be added")
    }.joinToString(", ")
}

/**
 * KMK: Builds what this device sends to a paired one and merges what it gets back. Both ways use the backup
 * restore's merge: entries are added, a chapter or episode read on either device stays read, the further progress
 * and the latest history time are kept, and nothing is taken away. Device files entries stay on their device (the
 * files are only there).
 */
object PairSyncData {

    private val json = Json { ignoreUnknownKeys = true }
    private val parser: ProtoBuf get() = Injekt.get()

    suspend fun export(selection: SyncSelection): ByteArray {
        val sources = if (selection.sources) {
            val manager = Injekt.get<SumizuriManager>()
            val keys = selection.sourceKeys
            manager.exportSources(keys).map { (info, code) -> SyncSource(info, code) }
        } else {
            emptyList()
        }
        val backup = exportBackup(selection)?.let { Base64.getEncoder().encodeToString(it) }
        val bytes = json.encodeToString(SyncPayload.serializer(), SyncPayload(sources, backup)).toByteArray()
        return gzip(bytes)
    }

    private suspend fun exportBackup(selection: SyncSelection): ByteArray? {
        val mangaLibrary = selection.mangaLibrary
        val mangaHistory = selection.mangaHistory
        val animeLibrary = selection.animeLibrary
        val animeHistory = selection.animeHistory
        val getCategories = Injekt.get<GetCategories>()
        // Picked categories, by name (an entry in no category counts as Default, "")
        val pickedCategories = selection.mangaCategories
        if (!mangaLibrary && !mangaHistory && !animeLibrary && !animeHistory) return null

        // Manga
        val getManga = Injekt.get<GetManga>()
        val mangaList = buildMap {
            if (mangaLibrary) {
                Injekt.get<GetFavorites>().await()
                    .filter { manga ->
                        pickedCategories == null ||
                            getCategories.await(manga.id).map { it.name }.ifEmpty { listOf("") }
                                .any { it in pickedCategories }
                    }
                    .forEach { put(it.id, it) }
            }
            if (mangaHistory) {
                Injekt.get<GetHistory>().subscribe("", null, null, null).first()
                    .map { it.mangaId }
                    .distinct()
                    .forEach { id -> if (id !in this) getManga.await(id)?.let { put(id, it) } }
            }
        }.values.filter { it.source != DeviceFilesIds.MANGA }
        val options = BackupOptions(
            libraryEntries = true,
            categories = mangaLibrary,
            chapters = true,
            tracking = mangaLibrary,
            history = mangaHistory,
            readEntries = false,
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
            privateSettings = false,
            customInfo = mangaLibrary,
            savedSearchesFeeds = false,
        )
        val backupManga = if (mangaList.isEmpty()) emptyList() else MangaBackupCreator()(mangaList, options)
        if (!mangaLibrary) {
            // History only: the other device's library stays as it is
            backupManga.forEach {
                it.favorite = false
                it.categories = emptyList()
            }
        }

        // Anime
        val animeRepository = Injekt.get<AnimeRepository>()
        val animeList = buildMap {
            if (animeLibrary) animeRepository.getFavorites().forEach { put(it.id, it) }
            if (animeHistory) {
                animeRepository.subscribeHistory().first()
                    .map { it.animeId }
                    .forEach { id -> if (id !in this) animeRepository.getAnime(id)?.let { put(id, it) } }
            }
        }.values.filter { it.source != DeviceFilesIds.ANIME }
        val backupAnime = AnimeBackupHelper().createAnime(animeList, includeHistory = animeHistory)
            .map { if (animeLibrary) it else it.copy(favorite = false) }

        val backup = Backup(
            backupManga = backupManga,
            backupCategories = if (mangaLibrary) {
                CategoriesBackupCreator()().filter { pickedCategories == null || it.name in pickedCategories }
            } else {
                emptyList()
            },
            backupSources = SourcesBackupCreator()(backupManga),
            backupAnime = backupAnime,
        )
        return gzip(parser.encodeToByteArray(Backup.serializer(), backup))
    }

    /** Merges what a paired device sent; [log] tells the screen how it's going */
    suspend fun import(data: ByteArray, log: (String) -> Unit): SyncSummary {
        val payload = json.decodeFromString(SyncPayload.serializer(), String(gunzip(data)))
        var errors = 0

        val manager = Injekt.get<SumizuriManager>()
        val added = payload.sources.count { source ->
            runCatching { manager.importSource(source.info, source.code) }
                .onFailure { errors++ }
                .getOrDefault(false)
        }
        if (payload.sources.isNotEmpty()) log("Code editor sources: $added new or updated of ${payload.sources.size}")

        val backupBytes = payload.backup ?: return SyncSummary(added, 0, 0, errors)
        val backup = parser.decodeFromByteArray(Backup.serializer(), gunzip(Base64.getDecoder().decode(backupBytes)))

        if (backup.backupCategories.isNotEmpty()) {
            runCatching { CategoriesRestorer()(backup.backupCategories) }.onFailure { errors++ }
        }
        if (backup.backupManga.isNotEmpty()) log("Merging ${backup.backupManga.size} manga…")
        val restorer = MangaRestorer(isSync = false)
        var manga = 0
        backup.backupManga.forEach {
            runCatching { restorer.restore(it, backup.backupCategories) }
                .onSuccess { manga++ }
                .onFailure { errors++ }
        }
        if (backup.backupAnime.isNotEmpty()) log("Merging ${backup.backupAnime.size} anime…")
        val helper = AnimeBackupHelper()
        var anime = 0
        backup.backupAnime.forEach {
            runCatching { helper.restoreAnime(it) }
                .onSuccess { anime++ }
                .onFailure { errors++ }
        }
        return SyncSummary(added, manga, anime, errors)
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
