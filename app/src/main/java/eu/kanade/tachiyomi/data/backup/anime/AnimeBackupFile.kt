package eu.kanade.tachiyomi.data.backup.anime

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.backup.AnimeBackupHelper
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.domain.extension.repository.AnimeExtensionStoreRepository
import okio.buffer
import okio.gzip
import okio.sink
import okio.source
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * KMK: The anime library's own backup, apart from the app's (manga) backup. The file is an Anikku backup: the same
 * layout and field numbers, so a backup made in Anikku restores here and one made here restores in Anikku.
 * Fields this app has nothing for (tracking, seasons, custom entry info) are left out when writing and skipped
 * when reading.
 */

@Serializable
data class AnikkuBackup(
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    // Anikku tells its current layout from its older one by this field being there
    @Required @ProtoNumber(500) val isLegacy: Boolean = false,
    @ProtoNumber(501) val backupAnime: List<AnikkuBackupAnime> = emptyList(),
    @ProtoNumber(502) var backupCategories: List<BackupCategory> = emptyList(),
    @ProtoNumber(505) var backupExtensionRepo: List<AnikkuBackupRepo> = emptyList(),
)

/** Anikku's older layout, only read */
@Serializable
private data class AnikkuLegacyBackup(
    @ProtoNumber(3) val backupAnime: List<AnikkuBackupAnime> = emptyList(),
    @ProtoNumber(4) var backupCategories: List<BackupCategory> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    @ProtoNumber(107) var backupExtensionRepo: List<AnikkuBackupRepo> = emptyList(),
)

@Serializable
private data class AnikkuBackupDetector(
    @ProtoNumber(500) val isLegacy: Boolean = true,
)

@Serializable
data class AnikkuBackupAnime(
    @ProtoNumber(1) var source: Long,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var artist: String? = null,
    @ProtoNumber(5) var author: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: List<String> = emptyList(),
    @ProtoNumber(8) var status: Int = 0,
    @ProtoNumber(9) var thumbnailUrl: String? = null,
    @ProtoNumber(13) var dateAdded: Long = 0,
    @ProtoNumber(16) var episodes: List<AnikkuBackupEpisode> = emptyList(),
    /** The [BackupCategory.order] of each category the anime is in */
    @ProtoNumber(17) var categories: List<Long> = emptyList(),
    @ProtoNumber(100) var favorite: Boolean = true,
    @ProtoNumber(104) var history: List<AnikkuBackupHistory> = emptyList(),
    @ProtoNumber(111) var initialized: Boolean = false,
    @ProtoNumber(500) var backgroundUrl: String? = null,
    /** The position of Anikku's FetchType: 0 seasons, 1 episodes */
    @ProtoNumber(507) var fetchType: Int = 1,
)

@Serializable
data class AnikkuBackupEpisode(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var scanlator: String? = null,
    @ProtoNumber(4) var seen: Boolean = false,
    @ProtoNumber(5) var bookmark: Boolean = false,
    @ProtoNumber(6) var lastSecondSeen: Long = 0,
    @ProtoNumber(7) var dateFetch: Long = 0,
    @ProtoNumber(8) var dateUpload: Long = 0,
    @ProtoNumber(9) var episodeNumber: Float = 0F,
    @ProtoNumber(10) var sourceOrder: Long = 0,
    @ProtoNumber(15) var fillermarkLegacy: Boolean = false,
    @ProtoNumber(16) var totalSeconds: Long = 0,
    @ProtoNumber(501) var fillermark: Boolean = false,
    @ProtoNumber(502) var summary: String? = null,
    @ProtoNumber(503) var previewUrl: String? = null,
)

@Serializable
data class AnikkuBackupHistory(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var lastRead: Long,
    @ProtoNumber(3) var readDuration: Long = 0,
)

@Serializable
data class AnikkuBackupRepo(
    @ProtoNumber(1) var baseUrl: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var shortName: String? = null,
    @ProtoNumber(4) var website: String = "",
    @ProtoNumber(5) var signingKeyFingerprint: String = "",
)

/** What goes into an anime backup, or comes out of one. The options Anikku's backup has that apply to this app. */
data class AnimeBackupOptions(
    val libraryEntries: Boolean = true,
    val episodes: Boolean = true,
    val history: Boolean = true,
    val categories: Boolean = true,
    val watchedEntries: Boolean = true,
    val extensionRepos: Boolean = true,
    val sourceSettings: Boolean = true,
    val privateSettings: Boolean = false,
    /**
     * Only anime in these categories; null takes every category. Creating a backup these are category ids,
     * restoring one they are the backup's category orders. [UNCATEGORIZED] stands for anime in no category.
     */
    val selectedCategories: Set<Long>? = null,
) {
    fun canRun() = libraryEntries || extensionRepos || sourceSettings

    private fun takes(categories: List<Long>): Boolean {
        val selected = selectedCategories ?: return true
        return if (categories.isEmpty()) UNCATEGORIZED in selected else categories.any { it in selected }
    }

    /** Whether a library anime in [categories] is part of the backup or restore */
    fun takesLibraryAnime(categories: List<Long>) = libraryEntries && takes(categories)

    companion object {
        const val UNCATEGORIZED = -1L
    }
}

class AnimeBackupFile(
    private val repository: AnimeRepository = Injekt.get(),
    private val storeRepository: AnimeExtensionStoreRepository = Injekt.get(),
    private val helper: AnimeBackupHelper = AnimeBackupHelper(),
    private val parser: ProtoBuf = Injekt.get(),
) {

    // Creating

    /** Writes the anime backup to [uri]; returns how many anime it holds */
    suspend fun create(context: Context, uri: Uri, options: AnimeBackupOptions): Int {
        val categories = repository.getCategories()
        val categoryIds = repository.getLibraryCategoryIds()
        val orderById = categories.associate { it.id to it.order }
        val allHistory = if (options.history) repository.getAllHistory() else emptyMap()

        val library = repository.getFavorites().filter { options.takesLibraryAnime(categoryIds[it.id].orEmpty()) }
        val watched = if (options.libraryEntries && options.watchedEntries) {
            repository.getWatchedNotInLibrary()
        } else {
            emptyList()
        }

        val backupAnime = (library + watched).map { anime ->
            val episodes = if (options.episodes) repository.getEpisodes(anime.id) else emptyList()
            AnikkuBackupAnime(
                source = anime.source,
                url = anime.url,
                title = anime.title,
                artist = anime.artist,
                author = anime.author,
                description = anime.description,
                genre = anime.genre.orEmpty(),
                status = anime.status.toInt(),
                thumbnailUrl = anime.thumbnailUrl,
                dateAdded = anime.dateAdded,
                episodes = episodes.map { ep ->
                    AnikkuBackupEpisode(
                        url = ep.url,
                        name = ep.name,
                        scanlator = ep.scanlator,
                        seen = ep.seen,
                        bookmark = ep.bookmark,
                        lastSecondSeen = ep.lastSecondSeen,
                        dateFetch = ep.dateFetch,
                        dateUpload = ep.dateUpload,
                        episodeNumber = ep.episodeNumber.toFloat(),
                        sourceOrder = ep.sourceOrder,
                        totalSeconds = ep.totalSeconds,
                        fillermark = ep.fillermark,
                        summary = ep.summary,
                        previewUrl = ep.previewUrl,
                    )
                },
                categories = if (options.categories) {
                    categoryIds[anime.id].orEmpty().mapNotNull { orderById[it] }
                } else {
                    emptyList()
                },
                favorite = anime.favorite,
                history = episodes.mapNotNull { ep ->
                    allHistory[ep.id]?.let { AnikkuBackupHistory(url = ep.url, lastRead = it) }
                },
                initialized = anime.initialized,
                backgroundUrl = anime.backgroundUrl,
                fetchType = anime.fetchType.ordinal,
            )
        }

        val backup = AnikkuBackup(
            backupSourcePreferences = if (options.sourceSettings) {
                PreferenceBackupCreator().createAnimeSource(options.privateSettings)
            } else {
                emptyList()
            },
            backupAnime = backupAnime,
            backupCategories = if (options.libraryEntries && options.categories) {
                categories
                    .filter { options.selectedCategories?.contains(it.id) ?: true }
                    .map { BackupCategory(name = it.name, order = it.order, id = it.id, hidden = it.hidden) }
            } else {
                emptyList()
            },
            backupExtensionRepo = if (options.extensionRepos) {
                storeRepository.getAll().map {
                    AnikkuBackupRepo(
                        baseUrl = it.indexUrl,
                        name = it.name,
                        shortName = it.badgeLabel,
                        website = it.contact.website,
                        signingKeyFingerprint = it.signingKey,
                    )
                }
            } else {
                emptyList()
            },
        )

        val bytes = parser.encodeToByteArray(AnikkuBackup.serializer(), backup)
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Couldn't open the backup file for writing")
        output.sink().gzip().buffer().use { it.write(bytes) }
        return backupAnime.size
    }

    // Restoring

    /** Reads an anime backup made here or in Anikku (either of its layouts) */
    fun decode(context: Context, uri: Uri): AnikkuBackup {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { inputStream ->
            val source = inputStream.source().buffer()
            val gzipped = source.peek().apply { require(2) }.readShort().toInt() == GZIP_MAGIC
            (if (gzipped) source.gzip().buffer() else source).use { it.readByteArray() }
        }
        return try {
            val legacy = parser.decodeFromByteArray(AnikkuBackupDetector.serializer(), bytes).isLegacy
            if (legacy) {
                val old = parser.decodeFromByteArray(AnikkuLegacyBackup.serializer(), bytes)
                AnikkuBackup(
                    backupSourcePreferences = old.backupSourcePreferences,
                    backupAnime = old.backupAnime,
                    backupCategories = old.backupCategories,
                    backupExtensionRepo = old.backupExtensionRepo,
                )
            } else {
                parser.decodeFromByteArray(AnikkuBackup.serializer(), bytes)
            }
        } catch (e: SerializationException) {
            throw IOException("This isn't an anime backup file", e)
        }
    }

    /**
     * Whether the file is an anime backup (Anikku's, or one made here) rather than the app's manga backup.
     * The two keep their library and categories in different fields, so a manga backup has none of these.
     */
    fun isAnimeBackup(context: Context, uri: Uri): Boolean =
        try {
            val backup = decode(context, uri)
            backup.backupAnime.isNotEmpty() || backup.backupCategories.isNotEmpty() ||
                backup.backupExtensionRepo.isNotEmpty()
        } catch (e: Exception) {
            false
        }

    /** Restores [backup]; returns how many anime were restored and what went wrong */
    suspend fun restore(context: Context, backup: AnikkuBackup, options: AnimeBackupOptions): RestoreResult {
        val errors = mutableListOf<String>()
        var restored = 0

        // Categories are matched by name; the ones this app doesn't have yet are made
        val idByOrder = if (options.libraryEntries && options.categories) {
            restoreCategories(backup.backupCategories.filter { options.selectedCategories?.contains(it.order) ?: true })
        } else {
            emptyMap()
        }

        if (options.libraryEntries) {
            backup.backupAnime.forEach { entry ->
                val wanted = if (entry.favorite) options.takesLibraryAnime(entry.categories) else options.watchedEntries
                if (!wanted) return@forEach
                try {
                    val anime = helper.restoreAnime(entry.toBackupAnime(options))
                    val categories = entry.categories.mapNotNull { idByOrder[it] }
                    if (categories.isNotEmpty()) {
                        // Added to the ones the anime is already in here
                        repository.setCategories(anime.id, (repository.getCategoryIds(anime.id) + categories).distinct())
                    }
                    restored++
                } catch (e: Exception) {
                    errors += "${entry.title}: ${e.message}"
                }
            }
        }

        if (options.extensionRepos) {
            backup.backupExtensionRepo.forEach { repo ->
                try {
                    helper.restoreStore(
                        BackupExtensionStore(
                            indexUrl = repo.baseUrl,
                            name = repo.name,
                            badgeLabel = repo.shortName,
                            signingKey = repo.signingKeyFingerprint,
                            contactWebsite = repo.website,
                            contactDiscord = null,
                            // Anikku's repos are the older kind, addressed by their folder
                            isLegacy = true,
                            extensionListUrl = null,
                        ),
                    )
                } catch (e: Exception) {
                    errors += "${repo.name}: ${e.message}"
                }
            }
        }

        if (options.sourceSettings) {
            try {
                PreferenceRestorer(context).restoreSource(backup.backupSourcePreferences)
            } catch (e: Exception) {
                errors += "Source settings: ${e.message}"
            }
        }

        return RestoreResult(restored, errors)
    }

    /** Makes sure the backup's categories exist here; returns each one's id here by its order in the backup */
    private suspend fun restoreCategories(backupCategories: List<BackupCategory>): Map<Long, Long> {
        if (backupCategories.isEmpty()) return emptyMap()
        var existing = repository.getCategories()
        backupCategories.sortedBy { it.order }.forEach { backupCategory ->
            if (existing.none { it.name == backupCategory.name }) {
                repository.createCategory(backupCategory.name)
                existing = repository.getCategories()
                if (backupCategory.hidden) {
                    existing.firstOrNull { it.name == backupCategory.name }
                        ?.let { repository.setCategoryHidden(it.id, true) }
                }
            }
        }
        val byName: Map<String, Category> = existing.associateBy { it.name }
        return backupCategories.mapNotNull { backupCategory ->
            byName[backupCategory.name]?.let { backupCategory.order to it.id }
        }.toMap()
    }

    private fun AnikkuBackupAnime.toBackupAnime(options: AnimeBackupOptions) = BackupAnime(
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
        // Anikku's sort and filter flags use other bits than this app's, so the episode list keeps its defaults
        episodeFlags = 0,
        episodes = if (options.episodes) {
            episodes.map { ep ->
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
            }
        } else {
            emptyList()
        },
        history = if (options.episodes && options.history) {
            history.map { BackupAnimeHistory(episodeUrl = it.url, lastSeen = it.lastRead) }
        } else {
            emptyList()
        },
    )

    data class RestoreResult(val restored: Int, val errors: List<String>)

    companion object {
        private const val GZIP_MAGIC = 0x1f8b

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_anime_$date.tachibk"
        }
    }
}
