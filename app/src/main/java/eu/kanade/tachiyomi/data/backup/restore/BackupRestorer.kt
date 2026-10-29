package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.CategoryChoice
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupJsSource
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.FeedRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val extensionStoreRestorer: ExtensionStoreRestorer = ExtensionStoreRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(isSync),
    // SY -->
    private val savedSearchRestorer: SavedSearchRestorer = SavedSearchRestorer(),
    // SY <--
    // KMK -->
    private val feedRestorer: FeedRestorer = FeedRestorer(),
    // KMK <--
) {

    private var restoreAmount = 0
    private var restoreProgress = 0

    // KMK -->
    @Volatile
    private var lastProgressAt = 0L

    /** True when the progress notification is due an update: at most every 300 ms */
    private fun progressDue(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < PROGRESS_EVERY_MS) return false
        lastProgressAt = now
        return true
    }
    // KMK <--
    private val errors = mutableListOf<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()
        // KMK: the libraries hold off on their own work (like looking at every download folder) until the end
        eu.kanade.tachiyomi.ui.library.LibraryRefresh.restoring.value = true
        try {
            restoreFromFile(uri, options)

            // KMK: library covers that aren't on the device come back from the titles' download folders
            if (options.libraryEntries) {
                val started = System.currentTimeMillis()
                eu.kanade.tachiyomi.data.cache.CoverFolders.fillMissingAfterRestore()
                timing("Covers from download folders", started)
            }
            // KMK: then look at the download folders again (Reindex downloads), so the downloads already on the
            // device show up in the restored library
            if (options.libraryEntries) {
                runCatching { Injekt.get<eu.kanade.tachiyomi.data.download.DownloadCache>().invalidateCache() }
            }
        } finally {
            eu.kanade.tachiyomi.ui.library.LibraryRefresh.restoring.value = false
        }
        // KMK: the libraries work out their badges again with what was restored
        eu.kanade.tachiyomi.ui.library.LibraryRefresh.request()
        // KMK: sideloaded extensions come back from the Sideloaded folder, or from their repos when not there
        if (options.appSettings || options.extensionStores) {
            val started = System.currentTimeMillis()
            runCatching { eu.kanade.tachiyomi.extension.util.SideloadFolder.afterRestore(context) }
            timing("Sideloaded extensions", started)
        }
        timing("Whole restore", startTime)

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val decodeStarted = System.currentTimeMillis()
        val decoded = BackupDecoder(context).decode(uri)
        timing("Reading the backup file", decodeStarted)
        // KMK: only library titles in the chosen categories
        val backup = options.categoryKeys?.let { keys ->
            val orderToName = decoded.backupCategories.associate { it.order to it.name }
            decoded.copy(
                // KMK: titles only in your history (not in the library) count as "No category" now; they used to
                // all come back whatever was picked, which looked like a full restore
                backupManga = decoded.backupManga.filter { manga ->
                    CategoryChoice.keepManga(keys, manga.categories.mapNotNull { orderToName[it] })
                },
                backupAnime = decoded.backupAnime.filter { CategoryChoice.keepAnime(keys, it.categories) },
                backupAnimeCategories = decoded.backupAnimeCategories.filter { CategoryChoice.anime(it.name) in keys },
            )
        } ?: decoded

        // Store source mapping for error messages
        val backupMaps = backup.backupSources
        sourceMapping = backupMaps.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += backup.backupManga.size
            // KMK -->
            restoreAmount += backup.backupAnime.size
            // KMK <--
        }
        if (options.categories) {
            restoreAmount += 1
        }
        // SY -->
        if (options.savedSearchesFeeds) {
            restoreAmount += 1
        }
        // SY <--
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionStores) {
            restoreAmount += backup.backupExtensionStores.size
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }
        // KMK -->
        if (options.jsSources && backup.backupJsSources.isNotEmpty()) {
            restoreAmount += 1
        }
        // KMK <--

        coroutineScope {
            if (options.categories) {
                // KMK: only the picked manga categories are made (titles still map to them by the whole list)
                restoreCategories(
                    options.categoryKeys?.let { keys ->
                        backup.backupCategories.filter { CategoryChoice.manga(it.name) in keys }
                    } ?: backup.backupCategories,
                )
            }
            // SY -->
            if (options.savedSearchesFeeds) {
                restoreSavedSearches(
                    backup.backupSavedSearches,
                    // KMK -->
                    backup.backupFeeds,
                    // KMK <--
                )
            }
            // SY <--
            if (options.appSettings) {
                restoreAppPreferences(backup.backupPreferences, backup.backupCategories.takeIf { options.categories })
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(
                    backup.backupSourcePreferences,
                    // KMK (r86): anime extension settings, empty in older backups
                    backup.backupAnimeSourcePreferences,
                )
            }
            if (options.libraryEntries) {
                restoreManga(backup.backupManga, if (options.categories) backup.backupCategories else emptyList())
            }
            if (options.extensionStores) {
                restoreExtensionStores(backup.backupExtensionStores)
            }
            // KMK -->
            // KMK (r85): with app settings, the ones holding anime category ids are mapped once the categories exist
            val animeCategoryPrefs = if (options.appSettings) backup.backupPreferences else emptyList()
            // KMK (r88): the settings are mapped with all the backup's anime categories, not only the picked ones
            // (a category choice used to narrow them to the picked categories)
            val allAnimeCategories = decoded.backupAnimeCategories
            if (options.libraryEntries) {
                restoreAnime(backup.backupAnime, backup.backupAnimeCategories, animeCategoryPrefs, allAnimeCategories)
            } else if (options.categories) {
                restoreAnime(emptyList(), backup.backupAnimeCategories, animeCategoryPrefs, allAnimeCategories)
            } else if (options.appSettings) {
                // KMK (r88): app settings alone: map them onto the anime categories already here (they were skipped)
                restoreAnimeCategoryPreferences(animeCategoryPrefs, allAnimeCategories)
            }
            if (options.extensionStores) {
                restoreAnimeExtensionStores(backup.backupAnimeExtensionStores)
            }
            if (options.jsSources && backup.backupJsSources.isNotEmpty()) {
                restoreJsSources(backup.backupJsSources)
            }
            // KMK (r86): player custom buttons go with app settings (none in older backups)
            if (options.appSettings && backup.backupCustomButtons.isNotEmpty()) {
                restoreCustomButtons(backup.backupCustomButtons)
            }
            // KMK <--

            // TODO: optionally trigger online library + tracker update
        }
    }

    context(scope: CoroutineScope)
    private /* KMK --> */suspend /* KMK <-- */ fun restoreCategories(backupCategories: List<BackupCategory>) {
        scope.ensureActive()
        categoriesRestorer(backupCategories)

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.categories),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    // SY -->
    private fun CoroutineScope.restoreSavedSearches(
        backupSavedSearches: List<BackupSavedSearch>,
        // KMK -->
        backupFeeds: List<BackupFeed>,
        // KMK <--
    ) = launch {
        ensureActive()
        savedSearchRestorer.restoreSavedSearches(backupSavedSearches)
        // KMK -->
        feedRestorer.restoreFeeds(backupFeeds)
        // KMK <--

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(KMR.strings.saved_searches_feeds),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }
    // SY <--

    private fun CoroutineScope.restoreManga(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) = launch {
        // KMK: one title at a time, each in its own transaction (restoring 50 per transaction got stuck). The
        // libraries stop reading the database while a restore runs (see LibraryRefresh.restoring), which is where
        // most of the time went: they reloaded the whole library after every title.
        val started = System.currentTimeMillis()
        mangaRestorer.sortByNew(backupMangas)
            .forEach {
                ensureActive()

                try {
                    mangaRestorer.restore(it, backupCategories)
                } catch (e: Exception) {
                    val sourceName = sourceMapping[it.source] ?: it.source.toString()
                    errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                }

                restoreProgress += 1
                // KMK: a few times a second at most; posting it for every title slowed big restores down
                if (progressDue()) {
                    with(notifier) {
                        showRestoreProgress(it.title, restoreProgress, restoreAmount, isSync)
                            .show(Notifications.ID_RESTORE_PROGRESS)
                    }
                }
                // KMK <--
            }
        timing("Manga (${backupMangas.size})", started)
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.app_settings),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreSourcePreferences(
        preferences: List<BackupSourcePreferences>,
        // KMK -->
        animePreferences: List<BackupSourcePreferences> = emptyList(),
        // KMK <--
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)
        // KMK (r86): anime extension settings live in the same kind of source_<id> files
        if (animePreferences.isNotEmpty()) {
            try {
                preferenceRestorer.restoreSource(animePreferences)
                log("Anime extension settings: ${animePreferences.size} sources")
            } catch (e: Exception) {
                errors.add(Date() to "Anime extension settings: ${e.message}")
            }
        }

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.source_settings),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupExtensionStores: List<BackupExtensionStore>,
    ) = launch {
        backupExtensionStores
            .forEach {
                ensureActive()

                try {
                    extensionStoreRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error adding extension store: ${it.name} : ${e.message}")
                }

                restoreProgress += 1
                // KMK -->
                with(notifier) {
                    // KMK <--
                    showRestoreProgress(
                        context.stringResource(MR.strings.extensionStores),
                        restoreProgress,
                        restoreAmount,
                        isSync,
                    )
                        // KMK -->
                        .show(Notifications.ID_RESTORE_PROGRESS)
                    // KMK <--
                }
            }
    }

    // KMK -->
    /** KMK (r88): only the app settings holding anime category ids, mapped by name onto the categories here */
    private fun CoroutineScope.restoreAnimeCategoryPreferences(
        preferences: List<BackupPreference>,
        categories: List<eu.kanade.tachiyomi.data.backup.models.BackupAnimeCategory>,
    ) = launch {
        if (preferences.isEmpty()) return@launch
        try {
            eu.kanade.tachiyomi.data.backup.AnimeBackupHelper().restoreCategoryPreferences(preferences, categories)
        } catch (e: Exception) {
            errors.add(Date() to "Anime categories: ${e.message}")
        }
    }

    private fun CoroutineScope.restoreAnime(
        backupAnime: List<eu.kanade.tachiyomi.data.backup.models.BackupAnime>,
        categories: List<eu.kanade.tachiyomi.data.backup.models.BackupAnimeCategory>,
        preferences: List<BackupPreference> = emptyList(),
        // KMK (r88): every anime category in the backup, for mapping the category settings' old ids to names
        allCategories: List<eu.kanade.tachiyomi.data.backup.models.BackupAnimeCategory> = categories,
    ) =
        launch {
            val helper = eu.kanade.tachiyomi.data.backup.AnimeBackupHelper()
            // Categories first, so they keep the backup's order
            try {
                helper.restoreCategories(categories)
                helper.restoreCategoryPreferences(preferences, allCategories)
            } catch (e: Exception) {
                errors.add(Date() to "Anime categories: ${e.message}")
            }
            val started = System.currentTimeMillis()
            backupAnime.forEach {
                ensureActive()
                try {
                    helper.restoreAnime(it)
                } catch (e: Exception) {
                    errors.add(Date() to "${it.title} [anime]: ${e.message}")
                }
                restoreProgress += 1
                if (progressDue()) {
                    with(notifier) {
                        showRestoreProgress(it.title, restoreProgress, restoreAmount, isSync)
                            .show(Notifications.ID_RESTORE_PROGRESS)
                    }
                }
            }
            timing("Anime (${backupAnime.size})", started)
            // KMK (r86): watch time (Stats) that came back with the anime's history
            if (backupAnime.any { anime -> anime.history.any { it.timeWatch > 0L } }) {
                val hours = "%.1f".format(Locale.US, helper.timeWatchMillis / 3_600_000.0)
                log("Anime watch time: ${helper.timeWatchEpisodes} episodes ($hours h in the backup)")
            }
            // Seasons find their parent once every anime is in
            backupAnime.filter { it.parentUrl != null }.forEach {
                ensureActive()
                try {
                    helper.restoreSeasonLink(it)
                } catch (e: Exception) {
                    errors.add(Date() to "${it.title} [anime season]: ${e.message}")
                }
            }
        }

    /** Sources made in the Code editor; a source already here is only replaced by a newer copy */
    private fun CoroutineScope.restoreJsSources(sources: List<BackupJsSource>) = launch {
        val manager = Injekt.get<JsSourceManager>()
        sources.forEach { source ->
            ensureActive()
            try {
                manager.restoreSource(source.info, source.code) { editor ->
                    source.settings.forEach { pref ->
                        when (val value = pref.value) {
                            is IntPreferenceValue -> editor.putInt(pref.key, value.value)
                            is LongPreferenceValue -> editor.putLong(pref.key, value.value)
                            is FloatPreferenceValue -> editor.putFloat(pref.key, value.value)
                            is StringPreferenceValue -> editor.putString(pref.key, value.value)
                            is BooleanPreferenceValue -> editor.putBoolean(pref.key, value.value)
                            is StringSetPreferenceValue -> editor.putStringSet(pref.key, value.value)
                        }
                    }
                }
            } catch (e: Exception) {
                errors.add(Date() to "JS source: ${e.message}")
            }
        }
        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(KMR.strings.backup_js_sources),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                .show(Notifications.ID_RESTORE_PROGRESS)
        }
    }

    /** KMK (r86): player custom buttons; one with the same name here is replaced rather than added twice */
    private fun CoroutineScope.restoreCustomButtons(
        buttons: List<eu.kanade.tachiyomi.data.backup.models.BackupCustomButton>,
    ) = launch {
        ensureActive()
        try {
            val (added, replaced) = eu.kanade.tachiyomi.data.backup.AnimeBackupHelper().restoreCustomButtons(buttons)
            log("Player custom buttons: $added added, $replaced replaced (${buttons.size} in the backup)")
        } catch (e: Exception) {
            errors.add(Date() to "Player custom buttons: ${e.message}")
        }
    }

    private fun CoroutineScope.restoreAnimeExtensionStores(stores: List<BackupExtensionStore>) = launch {
        val helper = eu.kanade.tachiyomi.data.backup.AnimeBackupHelper()
        stores.forEach {
            ensureActive()
            try {
                helper.restoreStore(it)
            } catch (e: Exception) {
                errors.add(Date() to "Error adding anime extension store: ${it.name} : ${e.message}")
            }
        }
    }
    // KMK <--

    /** KMK: how long a step took, in the app's Logs (Restore), to see where the time of a slow restore goes */
    private fun timing(step: String, startedAt: Long) {
        val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
        eu.kanade.tachiyomi.util.system.AppLog.add('I', "Restore", "$step: ${"%.1f".format(Locale.US, seconds)} s")
    }

    /** KMK (r86): a line in the app's Logs (Restore) */
    private fun log(message: String) {
        eu.kanade.tachiyomi.util.system.AppLog.add('I', "Restore", message)
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("komikku_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }
}

// KMK
private const val PROGRESS_EVERY_MS = 300L
