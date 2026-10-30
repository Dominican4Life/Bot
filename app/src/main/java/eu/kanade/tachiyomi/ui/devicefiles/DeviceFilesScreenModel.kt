package eu.kanade.tachiyomi.ui.devicefiles

import android.content.Context
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.devicefiles.CategorySummary
import eu.kanade.tachiyomi.devicefiles.DeviceFile
import eu.kanade.tachiyomi.devicefiles.DeviceFilesPreferences
import eu.kanade.tachiyomi.devicefiles.DeviceFilesRepository
import eu.kanade.tachiyomi.devicefiles.FileCategory
import eu.kanade.tachiyomi.devicefiles.StorageRoot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File

/** KMK: Where a Device files page is: the main page, a category, a folder, or a collection */
sealed interface FilesLocation {
    data object Home : FilesLocation
    data class Category(val category: FileCategory) : FilesLocation
    data class Folder(val path: String) : FilesLocation
    data object Favourites : FilesLocation
    data object Recent : FilesLocation

    fun encode(): String = when (this) {
        Home -> ""
        is Category -> "cat:${category.name}"
        is Folder -> "dir:$path"
        Favourites -> "fav"
        Recent -> "recent"
    }

    companion object {
        fun decode(text: String): FilesLocation = when {
            text.startsWith("cat:") ->
                runCatching { Category(FileCategory.valueOf(text.removePrefix("cat:"))) }.getOrDefault(Home)
            text.startsWith("dir:") -> Folder(text.removePrefix("dir:"))
            text == "fav" -> Favourites
            text == "recent" -> Recent
            else -> Home
        }
    }
}

/** A folder of a category's files, with one of them as its cover */
data class Album(val folder: DeviceFile, val cover: DeviceFile)

class DeviceFilesScreenModel(
    private val context: Context,
    val location: FilesLocation,
) : ScreenModel {

    data class State(
        val loading: Boolean = true,
        val hasAccess: Boolean = true,
        val files: List<DeviceFile> = emptyList(),
        val albums: List<Album> = emptyList(),
        val recent: List<DeviceFile> = emptyList(),
        val summaries: Map<FileCategory, CategorySummary> = emptyMap(),
        val favouriteCount: Int = 0,
        val roots: List<StorageRoot> = emptyList(),
    )

    private val prefs = DeviceFilesPreferences.instance

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Results of the search on the main page; null while not searching */
    private val _searchResults = MutableStateFlow<List<DeviceFile>?>(null)
    val searchResults: StateFlow<List<DeviceFile>?> = _searchResults.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = screenModelScope.launchIO {
            val access = DeviceFilesRepository.hasAccess(context)
            val hidden = prefs.showHidden.get()
            if (!access) {
                _state.update { it.copy(loading = false, hasAccess = false) }
                return@launchIO
            }
            when (val where = location) {
                FilesLocation.Home -> {
                    // The quick parts first, then the counts that read the whole index
                    _state.update {
                        it.copy(
                            hasAccess = true,
                            roots = DeviceFilesRepository.storageRoots(context),
                            favouriteCount = prefs.favourites.get().count { path -> File(path).exists() },
                            recent = if (prefs.showRecent.get()) {
                                DeviceFilesRepository.recent(context, 24, hidden)
                            } else {
                                emptyList()
                            },
                        )
                    }
                    val summaries = FileCategory.entries.associateWith { DeviceFilesRepository.summary(context, it, hidden) }
                    _state.update { it.copy(loading = false, summaries = summaries) }
                }
                is FilesLocation.Category -> {
                    val files = DeviceFilesRepository.category(context, where.category, hidden)
                    _state.update {
                        it.copy(loading = false, hasAccess = true, files = files, albums = albumsOf(files))
                    }
                }
                is FilesLocation.Folder -> {
                    val files = DeviceFilesRepository.list(File(where.path), hidden)
                    val roots = DeviceFilesRepository.storageRoots(context)
                    _state.update { it.copy(loading = false, hasAccess = true, files = files, roots = roots) }
                }
                FilesLocation.Favourites -> {
                    val files = prefs.favourites.get().map(::File).filter { it.exists() }.map { DeviceFile.of(it, true) }
                    _state.update { it.copy(loading = false, hasAccess = true, files = files) }
                }
                FilesLocation.Recent -> {
                    val files = DeviceFilesRepository.recent(context, 300, hidden)
                    _state.update { it.copy(loading = false, hasAccess = true, files = files) }
                }
            }
        }
    }

    private fun albumsOf(files: List<DeviceFile>): List<Album> =
        files.groupBy { it.parent }
            .map { (dir, inside) ->
                val newest = inside.maxBy { it.modified }
                Album(
                    folder = DeviceFile(
                        path = dir,
                        name = dir.substringAfterLast('/'),
                        isDirectory = true,
                        size = inside.sumOf { it.size },
                        modified = newest.modified,
                        childCount = inside.size,
                    ),
                    cover = newest,
                )
            }
            .sortedByDescending { it.folder.modified }

    /** Searches the whole device by file name (the main page's search) */
    fun search(text: String?) {
        searchJob?.cancel()
        if (text == null) {
            _searchResults.value = null
            return
        }
        searchJob = screenModelScope.launchIO {
            delay(250)
            val results = DeviceFilesRepository.search(context, text, prefs.showHidden.get()) { !isActive }
            if (isActive) _searchResults.value = results
        }
    }

    suspend fun rename(file: DeviceFile, newName: String): Boolean = withIOContext {
        val name = newName.trim()
        if (name.isEmpty() || '/' in name) return@withIOContext false
        val target = File(file.file.parentFile, name)
        if (target.exists()) return@withIOContext false
        val ok = file.file.renameTo(target)
        if (ok) {
            if (prefs.isFavourite(file.path)) {
                prefs.toggleFavourite(file.path)
                prefs.toggleFavourite(target.path)
            }
            DeviceFilesRepository.rescan(context, file.path, target.path)
            refresh()
        }
        ok
    }

    suspend fun delete(files: List<DeviceFile>): Int = withIOContext {
        var deleted = 0
        files.forEach { f ->
            if (f.file.deleteRecursively()) {
                deleted++
                if (prefs.isFavourite(f.path)) prefs.toggleFavourite(f.path)
            }
        }
        DeviceFilesRepository.rescan(context, *files.map { it.path }.toTypedArray())
        refresh()
        deleted
    }

    fun toggleFavourites(files: List<DeviceFile>) {
        // All on when any is off, otherwise all off
        val turnOn = files.any { !prefs.isFavourite(it.path) }
        files.forEach { if (prefs.isFavourite(it.path) != turnOn) prefs.toggleFavourite(it.path) }
        if (location == FilesLocation.Favourites) refresh()
    }
}
