package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeCustomInfo
import eu.kanade.tachiyomi.data.anime.AnimeExtraPrefs
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.track.AnimeTrack
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository
import eu.kanade.tachiyomi.data.anime.track.AnimeTracking
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeCategory
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeCustomInfo
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeTrack
import eu.kanade.tachiyomi.data.backup.models.BackupCustomButton
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.backupExtensionStoreMapper
import mihon.domain.extension.repository.AnimeExtensionStoreRepository
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Backs up and restores the anime library, episode progress, watch history and anime repos.
 */
class AnimeBackupHelper(
    private val repository: AnimeRepository = Injekt.get(),
    private val storeRepository: AnimeExtensionStoreRepository = Injekt.get(),
    private val database: Database = Injekt.get(),
    private val trackRepository: AnimeTrackRepository = Injekt.get(),
) {

    // KMK: the library plus the seasons of its anime (favorite or not), each once
    suspend fun createAnime(): List<BackupAnime> = createAnime(
        (repository.getFavoritesRaw() + repository.getSeasonsOfFavoritesRaw()).distinctBy { it.id },
        includeHistory = true,
    )

    /** KMK: [animes] with their episodes, and their watch history when [includeHistory] (device sync picks these) */
    suspend fun createAnime(animes: List<Anime>, includeHistory: Boolean): List<BackupAnime> {
        val history = if (includeHistory) repository.getAllHistory() else emptyMap()
        // KMK (r86): watched milliseconds per episode (Stats), with the history
        val timeWatch = if (includeHistory) timeWatched() else emptyMap()
        val categoryNames = repository.getCategories().associate { it.id to it.name }
        val tracks = trackRepository.getAll().groupBy { it.animeId }
        val customInfo = database.anime_custom_infoQueries
            .getAll { animeId, title, author, artist, description, genre, status, thumbnailUrl ->
                animeId to BackupAnimeCustomInfo(
                    title = title,
                    author = author,
                    artist = artist,
                    description = description,
                    genre = genre,
                    status = status,
                    // A local cover file can't come along
                    thumbnailUrl = thumbnailUrl?.takeUnless { it.startsWith("file:") },
                )
            }
            .executeAsList()
            .toMap()
        // KMK: seasons point at their parent by source + url, ids differ between devices
        val parents = animes.mapNotNull { it.parentId }.distinct()
            .mapNotNull { id -> repository.getAnime(id)?.let { id to it } }
            .toMap()
        return animes.map { anime ->
            val parent = anime.parentId?.let { parents[it] }
            val episodes = repository.getEpisodes(anime.id)
            BackupAnime(
                source = anime.source,
                url = anime.url,
                title = anime.title,
                artist = anime.artist,
                author = anime.author,
                description = anime.description,
                genre = anime.genre.orEmpty(),
                status = anime.status.toInt(),
                thumbnailUrl = anime.thumbnailUrl,
                backgroundUrl = anime.backgroundUrl,
                dateAdded = anime.dateAdded,
                favorite = anime.favorite,
                initialized = anime.initialized,
                fetchType = anime.fetchType.ordinal,
                episodeFlags = anime.episodeFlags,
                episodes = episodes.map { ep ->
                    BackupEpisode(
                        url = ep.url,
                        name = ep.name,
                        scanlator = ep.scanlator,
                        summary = ep.summary,
                        previewUrl = ep.previewUrl,
                        seen = ep.seen,
                        bookmark = ep.bookmark,
                        fillermark = ep.fillermark,
                        lastSecondSeen = ep.lastSecondSeen,
                        totalSeconds = ep.totalSeconds,
                        episodeNumber = ep.episodeNumber.toFloat(),
                        sourceOrder = ep.sourceOrder,
                        dateFetch = ep.dateFetch,
                        dateUpload = ep.dateUpload,
                    )
                },
                history = episodes.mapNotNull { ep ->
                    history[ep.id]?.let {
                        BackupAnimeHistory(episodeUrl = ep.url, lastSeen = it, timeWatch = timeWatch[ep.id] ?: 0L)
                    }
                },
                categories = repository.getCategoryIds(anime.id).mapNotNull { categoryNames[it] },
                tracking = tracks[anime.id].orEmpty().map { track ->
                    BackupAnimeTrack(
                        trackerId = track.trackerId,
                        remoteId = track.remoteId,
                        libraryId = track.libraryId,
                        title = track.title,
                        lastEpisodeSeen = track.lastEpisodeSeen,
                        totalEpisodes = track.totalEpisodes,
                        status = track.status,
                        score = track.score,
                        remoteUrl = track.remoteUrl,
                        startDate = track.startDate,
                        finishDate = track.finishDate,
                        private = track.private,
                    )
                },
                notes = AnimeExtraPrefs.notes(anime.id).get(),
                excludedScanlators = AnimeExtraPrefs.excludedScanlators(anime.id).get().toList(),
                customInfo = customInfo[anime.id]?.takeIf { it != BackupAnimeCustomInfo() },
                parentSource = parent?.source,
                parentUrl = parent?.url,
                seasonFlags = anime.seasonFlags,
                seasonNumber = anime.seasonNumber,
                seasonSourceOrder = anime.seasonSourceOrder,
            )
        }
    }

    /** KMK: every anime category, with its place in the list */
    suspend fun createCategories(): List<BackupAnimeCategory> =
        repository.getCategories().map { BackupAnimeCategory(name = it.name, order = it.order, id = it.id) }

    /**
     * KMK (r85): the app settings that hold anime category ids, restored with the ids of the categories with the same
     * names here (run after [restoreCategories]). Older backups don't carry the ids, so those settings keep their
     * current values instead of pointing at other categories.
     */
    fun restoreCategoryPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupAnimeCategory>,
    ) {
        val nameOfOld = categories.filter { it.id > 0 }.associate { it.id.toString() to it.name.lowercase() }
        if (nameOfOld.isEmpty()) return
        val idOfName = database.anime_categoriesQueries.getAll { id, name, _ -> name.lowercase() to id.toString() }
            .executeAsList()
            .toMap()
        val store = Injekt.get<PreferenceStore>()
        preferences.forEach { (key, value) ->
            if (key !in CATEGORY_KEYS || value !is StringSetPreferenceValue) return@forEach
            val mapped = value.value.mapNotNull { old -> nameOfOld[old]?.let { idOfName[it] } }.toSet()
            store.getStringSet(key).set(mapped)
        }
    }

    /**
     * KMK: Adds the backed up anime categories missing here, in the backup's order, after the ones already here.
     * Run before [restoreAnime] so categories it links to are created in order rather than as anime come.
     */
    fun restoreCategories(categories: List<BackupAnimeCategory>) {
        if (categories.isEmpty()) return
        database.transaction {
            val existing = database.anime_categoriesQueries.getAll { _, name, sort -> name.lowercase() to sort }
                .executeAsList()
            val names = existing.map { it.first }.toMutableSet()
            var next = (existing.maxOfOrNull { it.second } ?: -1L) + 1
            categories.sortedBy { it.order }.forEach { category ->
                if (names.add(category.name.lowercase())) {
                    database.anime_categoriesQueries.insert(category.name, next++)
                }
            }
        }
    }

    suspend fun createStores(): List<BackupExtensionStore> =
        storeRepository.getAll().map(backupExtensionStoreMapper)

    suspend fun restoreAnime(backup: BackupAnime) {
        val anime = repository.restoreAnime(
            Anime(
                id = -1L,
                source = backup.source,
                url = backup.url,
                title = backup.title,
                artist = backup.artist,
                author = backup.author,
                description = backup.description,
                genre = backup.genre,
                status = backup.status.toLong(),
                thumbnailUrl = backup.thumbnailUrl,
                backgroundUrl = backup.backgroundUrl,
                favorite = backup.favorite,
                initialized = backup.initialized,
                dateAdded = backup.dateAdded,
                lastUpdate = 0L,
                fetchType = FetchType.entries.getOrElse(backup.fetchType) { FetchType.Episodes },
                episodeFlags = backup.episodeFlags,
                seasonFlags = backup.seasonFlags,
                seasonNumber = backup.seasonNumber,
                seasonSourceOrder = backup.seasonSourceOrder,
            ),
            categories = backup.categories,
        )
        repository.restoreEpisodes(
            anime = anime,
            episodes = backup.episodes.map { ep ->
                Episode(
                    id = -1L,
                    animeId = anime.id,
                    url = ep.url,
                    name = ep.name,
                    scanlator = ep.scanlator,
                    summary = ep.summary,
                    previewUrl = ep.previewUrl,
                    seen = ep.seen,
                    bookmark = ep.bookmark,
                    fillermark = ep.fillermark,
                    lastSecondSeen = ep.lastSecondSeen,
                    totalSeconds = ep.totalSeconds,
                    episodeNumber = ep.episodeNumber.toDouble(),
                    sourceOrder = ep.sourceOrder,
                    dateFetch = ep.dateFetch,
                    dateUpload = ep.dateUpload,
                )
            },
            history = backup.history.associate { it.episodeUrl to it.lastSeen },
        )
        // KMK (r86): a failure here must not cost the anime its tracks, notes and Edit info
        runCatching { restoreTimeWatch(anime.id, backup.history) }
        restoreExtras(anime.id, backup)
    }

    /** KMK (r86): episodes whose watch time came back during this helper's restore, and the milliseconds added */
    var timeWatchEpisodes = 0
        private set
    var timeWatchMillis = 0L
        private set

    /** KMK (r86): every episode's watched milliseconds, by episode id (no generated query reads time_watch) */
    private fun timeWatched(): Map<Long, Long> = runCatching {
        Injekt.get<SqlDriver>().executeQuery(
            identifier = null,
            sql = "SELECT episode_id, time_watch FROM anime_history WHERE time_watch > 0",
            mapper = { cursor ->
                val found = mutableMapOf<Long, Long>()
                while (cursor.next().value) {
                    val episodeId = cursor.getLong(0)
                    val millis = cursor.getLong(1)
                    if (episodeId != null && millis != null) found[episodeId] = millis
                }
                QueryResult.Value(found.toMap())
            },
            parameters = 0,
        ).value
    }.getOrDefault(emptyMap())

    /**
     * KMK (r86): the backup's watch time of each episode, keeping the larger of what is here and the backup's.
     * Older backups have 0 everywhere, so nothing is written for them.
     */
    private suspend fun restoreTimeWatch(animeId: Long, history: List<BackupAnimeHistory>) {
        val watched = history.filter { it.timeWatch > 0L }
        if (watched.isEmpty()) return
        val episodeIds = repository.getEpisodes(animeId).associate { it.url to it.id }
        val driver = Injekt.get<SqlDriver>()
        watched.forEach { entry ->
            val episodeId = episodeIds[entry.episodeUrl] ?: return@forEach
            driver.execute(
                identifier = null,
                sql = "INSERT INTO anime_history(episode_id, last_seen, time_watch) VALUES (?, ?, ?) " +
                    "ON CONFLICT(episode_id) DO UPDATE SET time_watch = MAX(time_watch, excluded.time_watch)",
                parameters = 3,
            ) {
                bindLong(0, episodeId)
                bindLong(1, entry.lastSeen)
                bindLong(2, entry.timeWatch)
            }
            timeWatchEpisodes++
            timeWatchMillis += entry.timeWatch
        }
    }

    /** KMK (r86): every player custom button, in their order */
    fun createCustomButtons(): List<BackupCustomButton> = runCatching {
        database.custom_buttonsQueries
            .findAll { _, name, isFavorite, sortIndex, content, longPressContent, onStartup ->
                BackupCustomButton(
                    name = name,
                    isFavorite = isFavorite,
                    sortIndex = sortIndex,
                    content = content,
                    longPressContent = longPressContent,
                    onStartup = onStartup,
                )
            }
            .executeAsList()
    }.getOrDefault(emptyList())

    /**
     * KMK (r86): puts back the backed up player custom buttons. A button with the same name here (like the seeded
     * "+85 s") gets the backup's code instead of a second copy; the others are added after the ones here, in the
     * backup's order. When the backup has a favorite (primary) button, it becomes the only favorite.
     *
     * @return the buttons added and the buttons replaced
     */
    fun restoreCustomButtons(buttons: List<BackupCustomButton>): Pair<Int, Int> {
        val unique = buttons.filter { it.name.isNotBlank() }.distinctBy { it.name.trim().lowercase() }
        if (unique.isEmpty()) return 0 to 0
        var added = 0
        var replaced = 0
        database.transaction {
            val existing = database.custom_buttonsQueries
                .findAll { id, name, isFavorite, sortIndex, _, _, _ -> ExistingButton(id, name, isFavorite, sortIndex) }
                .executeAsList()
            val byName = existing.associateBy { it.name.trim().lowercase() }
            val backupHasFavorite = unique.any { it.isFavorite }
            if (backupHasFavorite) {
                existing.filter { it.isFavorite }.forEach {
                    database.custom_buttonsQueries.update(
                        name = null,
                        isFavorite = false,
                        sortIndex = null,
                        content = null,
                        longPressContent = null,
                        onStartup = null,
                        customButtonId = it.id,
                    )
                }
            }
            var next = (existing.maxOfOrNull { it.sortIndex } ?: -1L) + 1
            unique.sortedBy { it.sortIndex }.forEach { button ->
                val here = byName[button.name.trim().lowercase()]
                if (here != null) {
                    database.custom_buttonsQueries.update(
                        name = null,
                        // Without a favorite in the backup, the one here stays
                        isFavorite = if (backupHasFavorite) button.isFavorite else null,
                        sortIndex = null,
                        content = button.content,
                        longPressContent = button.longPressContent,
                        onStartup = button.onStartup,
                        customButtonId = here.id,
                    )
                    replaced++
                } else {
                    database.custom_buttonsQueries.insert(
                        name = button.name,
                        isFavorite = button.isFavorite,
                        sortIndex = next++,
                        content = button.content,
                        longPressContent = button.longPressContent,
                        onStartup = button.onStartup,
                    )
                    added++
                }
            }
        }
        return added to replaced
    }

    private class ExistingButton(val id: Long, val name: String, val isFavorite: Boolean, val sortIndex: Long)

    /**
     * KMK: Links a restored season to its parent. Run after every anime of the backup is restored, so the
     * parent is there whichever order they came in.
     */
    suspend fun restoreSeasonLink(backup: BackupAnime) {
        val parentSource = backup.parentSource ?: return
        val parentUrl = backup.parentUrl ?: return
        val season = repository.getAnimeByUrl(backup.url, backup.source) ?: return
        repository.restoreSeasonLink(
            season = season,
            parentSource = parentSource,
            parentUrl = parentUrl,
            seasonNumber = backup.seasonNumber,
            seasonSourceOrder = backup.seasonSourceOrder,
        )
    }

    /** KMK: tracks, notes, hidden scanlators and edited info, under the anime's id on this device */
    private suspend fun restoreExtras(animeId: Long, backup: BackupAnime) {
        if (backup.tracking.isNotEmpty()) {
            val existing = trackRepository.getTracks(animeId).associateBy { it.trackerId }
            backup.tracking.forEach { track ->
                // Trackers that were removed (Jellyfin) aren't restored
                if (AnimeTracking.service(track.trackerId) == null) return@forEach
                val here = existing[track.trackerId]
                trackRepository.upsert(
                    AnimeTrack(
                        animeId = animeId,
                        trackerId = track.trackerId,
                        remoteId = track.remoteId,
                        libraryId = track.libraryId,
                        title = track.title,
                        // Never lose progress made on this device
                        lastEpisodeSeen = maxOf(track.lastEpisodeSeen, here?.lastEpisodeSeen ?: 0.0),
                        totalEpisodes = track.totalEpisodes,
                        status = track.status,
                        score = track.score,
                        remoteUrl = track.remoteUrl,
                        startDate = track.startDate,
                        finishDate = track.finishDate,
                        private = track.private,
                    ),
                )
            }
        }
        if (backup.notes.isNotBlank()) AnimeExtraPrefs.notes(animeId).set(backup.notes)
        if (backup.excludedScanlators.isNotEmpty()) {
            AnimeExtraPrefs.excludedScanlators(animeId).set(backup.excludedScanlators.toSet())
        }
        backup.customInfo?.let { info ->
            // Fields the backup doesn't have keep what's here (a local custom cover, say)
            val here = database.anime_custom_infoQueries
                .getByAnimeId(animeId) { _, title, author, artist, description, genre, status, thumb ->
                    BackupAnimeCustomInfo(title, author, artist, description, genre, status, thumb)
                }
                .executeAsOneOrNull()
            // Through the repository, so its in-memory Edit info stays in step
            repository.setCustomInfo(
                AnimeCustomInfo(
                    animeId = animeId,
                    title = info.title ?: here?.title,
                    author = info.author ?: here?.author,
                    artist = info.artist ?: here?.artist,
                    description = info.description ?: here?.description,
                    genre = info.genre ?: here?.genre,
                    status = info.status ?: here?.status,
                    thumbnailUrl = info.thumbnailUrl?.takeUnless { it.startsWith("file:") } ?: here?.thumbnailUrl,
                ),
            )
        }
    }

    fun restoreStore(backupStore: BackupExtensionStore) {
        database.anime_extension_storeQueries.upsert(
            indexUrl = backupStore.indexUrl,
            name = backupStore.name,
            badgeLabel = backupStore.badgeLabel ?: backupStore.name,
            signingKey = backupStore.signingKey,
            contactWebsite = backupStore.contactWebsite,
            contactDiscord = backupStore.contactDiscord,
            isLegacy = backupStore.isLegacy ?: true,
            extensionListUrl = backupStore.extensionListUrl,
        )
    }

    companion object {
        /** KMK (r85): app settings holding anime category ids, mapped by name on restore */
        val CATEGORY_KEYS = setOf(
            "anime_update_categories",
            "anime_update_categories_exclude",
            "anime_download_new_categories",
            "anime_download_new_categories_exclude",
            "anime_library_hidden_categories",
            "anime_library_filter_categories_include",
            "anime_library_filter_categories_exclude",
        )

        /**
         * KMK (r85): app settings named after, or holding, anime or episode ids. Ids change on a restore, so these
         * would land on other anime; they are left out of the app settings restore (notes and hidden scanlators come
         * back with each anime instead).
         */
        private val ID_KEY_PREFIXES = listOf(
            "anime_notes_",
            "anime_excluded_scanlators_",
            "anime_skip_intro_length_",
            "anime_last_selected_season_",
        )
        private val ID_KEYS = setOf("pref_last_aspect_ratio_anime_id", "anime_download_queue")

        /** True for an app setting the plain app-settings restore must skip (see [CATEGORY_KEYS] and above) */
        fun isAnimeIdKey(key: String): Boolean =
            key in CATEGORY_KEYS || key in ID_KEYS || ID_KEY_PREFIXES.any { key.startsWith(it) }
    }
}
