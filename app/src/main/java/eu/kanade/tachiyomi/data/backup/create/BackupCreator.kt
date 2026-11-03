package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.AnimeBackupHelper
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.BackupFiles
import eu.kanade.tachiyomi.data.backup.CategoryChoice
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.FeedBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SavedSearchBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupJsSource
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.jssource.JsSourceManager
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class BackupCreator(
    private val context: Context,
    private val isAutoBackup: Boolean,

    private val parser: ProtoBuf = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val backupPreferences: BackupPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),

    private val categoriesBackupCreator: CategoriesBackupCreator = CategoriesBackupCreator(),
    private val mangaBackupCreator: MangaBackupCreator = MangaBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator = PreferenceBackupCreator(),
    private val extensionStoresBackupCreator: ExtensionStoresBackupCreator = ExtensionStoresBackupCreator(),
    private val sourcesBackupCreator: SourcesBackupCreator = SourcesBackupCreator(),
    // KMK -->
    private val feedBackupCreator: FeedBackupCreator = FeedBackupCreator(),
    // KMK <--
    // SY -->
    private val savedSearchBackupCreator: SavedSearchBackupCreator = SavedSearchBackupCreator(),
    private val getMergedManga: GetMergedManga = Injekt.get(),
    // SY <--
) {

    suspend fun backup(uri: Uri, options: BackupOptions): String {
        var file: UniFile? = null
        try {
            file = if (isAutoBackup) {
                // Get dir of file and create
                val dir = UniFile.fromUri(context, uri)

                // Delete older backups
                dir?.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }
                    .orEmpty()
                    .sortedByDescending { it.name }
                    .drop(MAX_AUTO_BACKUPS - 1)
                    .forEach { it.delete() }

                // Create new file to place backup
                dir?.createFile(getFilename())
            } else {
                UniFile.fromUri(context, uri)
            }

            if (file == null || !file.isFile) {
                throw IllegalStateException(context.stringResource(MR.strings.create_backup_file_error))
            }

            val nonFavoriteManga = if (options.readEntries) mangaRepository.getReadMangaNotInLibrary() else emptyList()
            // SY -->
            val mergedManga = getMergedManga.await()
            // SY <--
            // KMK (r115): kept, the titles' custom covers are taken from this list (manga ids are needed)
            val allManga = getFavorites.await() + nonFavoriteManga /* SY --> */ + mergedManga /* SY <-- */
            val backupManga =
                backupMangas(allManga, options)
                    // KMK -->
                    .let { list ->
                        // Only library titles in the chosen categories (titles read outside the library stay)
                        val keys = options.categoryKeys ?: return@let list
                        val orderToName = categoriesBackupCreator().associate { it.order to it.name }
                        list.filter { manga ->
                            !manga.favorite ||
                                CategoryChoice.keepManga(keys, manga.categories.mapNotNull { orderToName[it] })
                        }
                    }
            // KMK <--

            // KMK (r115): anime follow the Chapters & episodes / History / Tracking ticks, and "All read & watched"
            // brings anime watched outside the library
            val backupAnime = if (options.libraryEntries) {
                AnimeBackupHelper().createAnime(
                    episodes = options.chapters,
                    history = options.history,
                    tracking = options.tracking,
                    watched = options.readEntries,
                ).filter {
                    CategoryChoice.keepAnime(options.categoryKeys, it.categories)
                }
            } else {
                emptyList()
            }
            val backupFiles = BackupFiles(context)

            val backup = Backup(
                backupManga = backupManga,
                backupCategories = backupCategories(options),
                backupSources = backupSources(backupManga),
                backupPreferences = backupAppPreferences(options),
                backupExtensionStores = backupExtensionStores(options),
                backupSourcePreferences = backupSourcePreferences(options),

                // SY -->
                backupSavedSearches = backupSavedSearches(options),
                // SY <--

                // KMK -->
                backupFeeds = backupFeeds(options),
                backupAnime = backupAnime,
                backupJsSources = if (options.jsSources) backupJsSources() else emptyList(),
                backupAnimeExtensionStores = if (options.extensionStores) {
                    AnimeBackupHelper().createStores()
                } else {
                    emptyList()
                },
                backupAnimeCategories = if (options.libraryEntries || options.categories) {
                    val keys = options.categoryKeys
                    AnimeBackupHelper().createCategories().filter {
                        keys == null || CategoryChoice.anime(it.name) in keys
                    }
                } else {
                    emptyList()
                },
                // KMK (r86): anime extension settings go with "Source settings", player buttons with "App settings"
                backupAnimeSourcePreferences = if (options.sourceSettings) {
                    preferenceBackupCreator.createAnimeSource(includePrivatePreferences = options.privateSettings)
                } else {
                    emptyList()
                },
                backupCustomButtons = if (options.appSettings) {
                    AnimeBackupHelper().createCustomButtons()
                } else {
                    emptyList()
                },
                // KMK <--
                // KMK (r115): Source Lab files, JS source storage and custom covers (proto 900–902)
                backupSourceLabFiles = if (options.sourceLabFiles) {
                    backupFiles.createSourceLabFiles()
                } else {
                    emptyList()
                },
                backupJsStorageFiles = if (options.jsStorage && options.jsSources) {
                    backupFiles.createJsStorageFiles()
                } else {
                    emptyList()
                },
                backupCustomCovers = if (options.customCovers && options.libraryEntries) {
                    backupFiles.createCustomCovers(
                        manga = allManga,
                        keep = backupManga.map { it.source to it.url }.toSet(),
                        anime = backupAnime,
                    )
                } else {
                    emptyList()
                },
            )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            file.openOutputStream()
                .also {
                    // Force overwrite old file
                    (it as? FileOutputStream)?.channel?.truncate(0)
                }
                .sink().gzip().buffer().use {
                    it.write(byteArray)
                }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator(context).validate(fileUri)

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp().set(Instant.now().toEpochMilli())
            }

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        // KMK (r104): also without library entries, for the source settings written after this (backup and sync)
        mangaBackupCreator.awaitSources()
        if (!options.libraryEntries) return emptyList()

        return mangaBackupCreator(mangas, options)
    }

    // KMK -->
    /** Sources made in the Code editor, with their settings */
    private fun backupJsSources(): List<BackupJsSource> = runCatching {
        Injekt.get<JsSourceManager>().backupSources().map { (info, code, settings) ->
            BackupJsSource(info = info, code = code, settings = settings.toBackupPreferences())
        }
    }.getOrDefault(emptyList())

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, *>.toBackupPreferences(): List<BackupPreference> = mapNotNull { (key, value) ->
        when (value) {
            is Int -> BackupPreference(key, IntPreferenceValue(value))
            is Long -> BackupPreference(key, LongPreferenceValue(value))
            is Float -> BackupPreference(key, FloatPreferenceValue(value))
            is String -> BackupPreference(key, StringPreferenceValue(value))
            is Boolean -> BackupPreference(key, BooleanPreferenceValue(value))
            is Set<*> -> (value as? Set<String>)?.let { BackupPreference(key, StringSetPreferenceValue(it)) }
            else -> null
        }
    }
    // KMK <--

    fun backupSources(mangas: List<BackupManga>): List<BackupSource> {
        return sourcesBackupCreator(mangas)
    }

    internal fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    internal suspend fun backupExtensionStores(options: BackupOptions): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return extensionStoresBackupCreator()
    }

    fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.privateSettings)
    }

    // SY -->
    suspend fun backupSavedSearches(options: BackupOptions): List<BackupSavedSearch> {
        if (!options.savedSearchesFeeds) return emptyList()

        return savedSearchBackupCreator()
    }
    // SY <--

    // KMK -->
    /**
     * Backup global Popular/Latest feeds
     */
    suspend fun backupFeeds(options: BackupOptions): List<BackupFeed> {
        if (!options.savedSearchesFeeds) return emptyList()

        return feedBackupCreator()
    }
    // KMK <--

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4
        private val FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}.tachibk""".toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }
    }
}
