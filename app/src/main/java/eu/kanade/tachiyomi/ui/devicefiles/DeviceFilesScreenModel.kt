package eu.kanade.tachiyomi.ui.devicefiles

import android.content.Context
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.devicefiles.CategorySummary
import eu.kanade.tachiyomi.devicefiles.DeviceFile
import eu.kanade.tachiyomi.devicefiles.DeviceFilesDelete
import eu.kanade.tachiyomi.devicefiles.DeviceFilesDeletionLog
import eu.kanade.tachiyomi.devicefiles.DeviceFilesPreferences
import eu.kanade.tachiyomi.devicefiles.DeviceFilesRepository
import eu.kanade.tachiyomi.devicefiles.DeviceFilesTrash
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

    // KMK (r97)
    /** The trash bin: what Delete moved out of the way */
    data object Trash : FilesLocation

    /** Where to look for files deleted for good */
    data object Recover : FilesLocation

    fun encode(): String = when (this) {
        Home -> ""
        is Category -> "cat:${category.name}"
        is Folder -> "dir:$path"
        Favourites -> "fav"
        Recent -> "recent"
        Trash -> "trash"
        Recover -> "recover"
    }

    companion object {
        fun decode(text: String): FilesLocation = when {
            text.startsWith("cat:") ->
                runCatching { Category(FileCategory.valueOf(text.removePrefix("cat:"))) }.getOrDefault(Home)
            text.startsWith("dir:") -> Folder(text.removePrefix("dir:"))
            text == "fav" -> Favourites
            text == "recent" -> Recent
            text == "trash" -> Trash
            text == "recover" -> Recover
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
        // KMK (r97)
        /** How many items the trash bin holds (main page tile) */
        val trashCount: Int = 0,
        /** The trash page's entries, in the same order as [files] */
        val trash: List<DeviceFilesTrash.Entry> = emptyList(),
        /** A running delete: (done, total), shown as "Deleting… n of N" */
        val progress: Pair<Int, Int>? = null,
    )

    /** KMK (r97): what a delete did, so the page can offer Undo and say what was refused */
    data class DeleteOutcome(
        val deleted: Int,
        val total: Int,
        /** Moved to the trash: Undo restores these */
        val trashed: List<DeviceFilesTrash.Entry>,
        /** Rows that were refused: the row said file, the disk says folder */
        val refused: List<String>,
    )

    /** KMK (r97): what a restore did */
    data class RestoreOutcome(
        val restored: Int,
        /** Something already sits at the original path: the page may offer "restore as a copy" */
        val conflicts: List<DeviceFilesTrash.Entry>,
    )

    private val prefs = DeviceFilesPreferences.instance

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Results of the search on the main page; null while not searching */
    private val _searchResults = MutableStateFlow<List<DeviceFile>?>(null)
    val searchResults: StateFlow<List<DeviceFile>?> = _searchResults.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    /** KMK: the text of the running or shown search, so it can run again after a rename or delete */
    private var searchText: String? = null

    init {
        // KMK (r97): old trash goes first, so the counts below are right; at most once an hour per process
        screenModelScope.launchIO {
            val now = System.currentTimeMillis()
            if (prefs.trashEnabled.get() && now - lastAutoEmpty > AUTO_EMPTY_EVERY_MS) {
                lastAutoEmpty = now
                val gone = runCatching { DeviceFilesTrash.autoEmpty(context, prefs.trashDays.get()) }.getOrDefault(0)
                if (gone > 0) refresh()
            }
        }
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
                    val trashCount = if (prefs.trashEnabled.get()) DeviceFilesTrash.entries(context).size else 0
                    _state.update { it.copy(trashCount = trashCount) }
                    val summaries = FileCategory.entries.associateWith { DeviceFilesRepository.summary(context, it, hidden) }
                    _state.update { it.copy(loading = false, summaries = summaries) }
                }
                is FilesLocation.Category -> {
                    val files = DeviceFilesRepository.category(context, where.category, hidden)
                    // KMK: same for the slow category listings
                    if (!isActive) return@launchIO
                    _state.update {
                        it.copy(loading = false, hasAccess = true, files = files, albums = albumsOf(files))
                    }
                }
                is FilesLocation.Folder -> {
                    val files = DeviceFilesRepository.list(File(where.path), hidden)
                    val roots = DeviceFilesRepository.storageRoots(context)
                    // KMK: the listing can't be stopped part way; a newer load (after a rename or delete) replaced
                    // this one meanwhile, so its older listing isn't shown over the newer one
                    if (!isActive) return@launchIO
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
                FilesLocation.Trash -> {
                    val entries = DeviceFilesTrash.entries(context)
                    if (!isActive) return@launchIO
                    _state.update {
                        it.copy(
                            loading = false,
                            hasAccess = true,
                            trash = entries,
                            trashCount = entries.size,
                            files = entries.map { e -> e.asDeviceFile() },
                        )
                    }
                }
                FilesLocation.Recover -> _state.update { it.copy(loading = false, hasAccess = true) }
            }
        }
    }

    /** KMK (r97): the trash entry behind a Trash-page row */
    fun trashEntryOf(file: DeviceFile): DeviceFilesTrash.Entry? = _state.value.trash.firstOrNull { it.trashed == file.path }

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
    fun search(text: String?, wait: Boolean = true) {
        searchJob?.cancel()
        searchText = text
        if (text == null) {
            _searchResults.value = null
            return
        }
        searchJob = screenModelScope.launchIO {
            if (wait) delay(250)
            val found = DeviceFilesRepository.search(context, text, prefs.showHidden.get()) { !isActive }
            // KMK: a rerun after a rename/delete comes before the media index catches up: old paths are dropped
            // KMK (r97): by exact-case name in the folder's listing, not `exists()`, which shared storage answers
            // true for any letter case (the stale row of a case-only rename survived and deleted the real file)
            val results = if (wait) found else DeviceFilesRepository.existingByExactName(found)
            if (isActive) _searchResults.value = results
        }
    }

    /** KMK: runs the shown search again, so renamed or deleted files don't stay listed under old paths */
    private fun rerunSearch() {
        val text = searchText ?: return
        if (_searchResults.value != null) search(text, wait = false)
    }

    suspend fun rename(file: DeviceFile, newName: String): Boolean = withIOContext {
        val name = newName.trim()
        if (name.isEmpty() || '/' in name) return@withIOContext false
        if (name == file.name) return@withIOContext true
        val target = File(file.file.parentFile, name)
        // KMK: shared storage ignores letter case, so "ep1" -> "EP1" finds the file itself: go through a temp name
        val caseOnly = target.path.equals(file.path, ignoreCase = true)
        if (target.exists() && !caseOnly) return@withIOContext false
        // KMK: on a volume that keeps letter case apart, "EP1" can be another file next to "ep1": never replace it
        if (caseOnly && file.file.parentFile?.list()?.contains(name) == true) return@withIOContext false
        val ok = if (caseOnly) {
            // KMK: no leading dot, so the file isn't hidden (and skipped by the media index) if the second step fails
            val temp = File(file.file.parentFile, "${file.name}.renaming-${System.currentTimeMillis()}")
            if (file.file.renameTo(temp)) {
                temp.renameTo(target) || run {
                    temp.renameTo(file.file)
                    false
                }
            } else {
                false
            }
        } else {
            file.file.renameTo(target)
        }
        if (ok) {
            // KMK: favourites inside a renamed folder move with it
            val oldPrefix = file.path.trimEnd('/') + "/"
            val newPrefix = target.path.trimEnd('/') + "/"
            val favourites = prefs.favourites.get()
            if (favourites.any { it == file.path || it.startsWith(oldPrefix) }) {
                prefs.favourites.set(
                    favourites.map {
                        when {
                            it == file.path -> target.path
                            it.startsWith(oldPrefix) -> newPrefix + it.removePrefix(oldPrefix)
                            else -> it
                        }
                    }.toSet(),
                )
            }
            DeviceFilesRepository.rescan(context, file.path, target.path)
            refresh()
            rerunSearch()
        }
        ok
    }

    /**
     * KMK (r97): moves [files] to the trash, or deletes them for good when [forever] (the user confirmed "Delete
     * forever", the trash is off, or the file couldn't be moved). Rows whose kind doesn't match the disk are refused.
     */
    suspend fun delete(files: List<DeviceFile>, forever: Boolean): DeleteOutcome = withIOContext {
        val toTrash = !forever && prefs.trashEnabled.get()
        val shred = forever && prefs.shredEnabled.get()
        var deleted = 0
        val trashed = ArrayList<DeviceFilesTrash.Entry>()
        val refused = ArrayList<String>()
        _state.update { it.copy(progress = 0 to files.size) }
        try {
            files.forEachIndexed { i, f ->
                _state.update { it.copy(progress = i + 1 to files.size) }
                val onDisk = f.file
                // The row came from the media index as a file; a folder here would go with everything inside it
                if (!f.isDirectory && onDisk.isDirectory && !DeviceFilesTrash.isLink(onDisk)) {
                    refused += f.name
                    DeviceFilesDeletionLog.log(context, "refuse", f.path, f.size, 0, "row says file, disk says folder")
                    return@forEachIndexed
                }
                if (!onDisk.exists()) {
                    // gone already (part of a folder deleted earlier in this batch, or by another app)
                    deleted++
                    if (prefs.isFavourite(f.path)) prefs.toggleFavourite(f.path)
                    return@forEachIndexed
                }
                val (size, count) = DeviceFilesTrash.measure(onDisk)
                val fileCount = if (f.isDirectory) count else 0
                if (toTrash) {
                    val entry = DeviceFilesTrash.trash(context, onDisk)
                    if (entry != null) {
                        trashed += entry
                        deleted++
                        if (prefs.isFavourite(f.path)) prefs.toggleFavourite(f.path)
                        DeviceFilesDeletionLog.log(context, "trash", f.path, size, fileCount, "moved")
                        return@forEachIndexed
                    }
                    // KMK: no real delete behind the user's back: the row stays, and the message says why
                    DeviceFilesDeletionLog.log(context, "trash", f.path, size, fileCount, "couldn't move")
                    return@forEachIndexed
                }
                // KMK: counted as deleted when it's gone, even if the walk reported a problem on the way
                val gone = DeviceFilesDelete.delete(onDisk, shred)
                if (gone) {
                    deleted++
                    if (prefs.isFavourite(f.path)) prefs.toggleFavourite(f.path)
                }
                DeviceFilesDeletionLog.log(
                    context,
                    if (shred) "shred" else "delete",
                    f.path,
                    size,
                    fileCount,
                    if (gone) "deleted" else "failed",
                )
            }
        } finally {
            _state.update { it.copy(progress = null) }
        }
        DeviceFilesRepository.rescan(context, *files.map { it.path }.toTypedArray())
        refresh()
        rerunSearch()
        DeleteOutcome(deleted, files.size, trashed, refused)
    }

    /** KMK (r97): deletes trash entries for good (the Trash page's "Delete forever") */
    suspend fun deleteForever(entries: List<DeviceFilesTrash.Entry>): Int = withIOContext {
        val shred = prefs.shredEnabled.get()
        var deleted = 0
        _state.update { it.copy(progress = 0 to entries.size) }
        try {
            entries.forEachIndexed { i, entry ->
                _state.update { it.copy(progress = i + 1 to entries.size) }
                val gone = DeviceFilesTrash.deleteForever(entry, shred)
                if (gone) deleted++
                DeviceFilesDeletionLog.log(
                    context,
                    if (shred) "shred" else "delete",
                    entry.original,
                    entry.size,
                    entry.files,
                    if (gone) "deleted" else "failed",
                )
            }
        } finally {
            _state.update { it.copy(progress = null) }
        }
        refresh()
        deleted
    }

    /** KMK (r97): puts trash entries back where they came from */
    suspend fun restore(entries: List<DeviceFilesTrash.Entry>, asCopy: Boolean = false): RestoreOutcome = withIOContext {
        var restored = 0
        val conflicts = ArrayList<DeviceFilesTrash.Entry>()
        entries.forEach { entry ->
            when (val result = DeviceFilesTrash.restore(context, entry, asCopy)) {
                is DeviceFilesTrash.RestoreResult.Restored -> {
                    restored++
                    DeviceFilesDeletionLog.log(context, "restore", result.path, entry.size, entry.files, "restored")
                }
                is DeviceFilesTrash.RestoreResult.Exists -> conflicts += entry
                DeviceFilesTrash.RestoreResult.Failed ->
                    DeviceFilesDeletionLog.log(context, "restore", entry.original, entry.size, entry.files, "failed")
            }
        }
        refresh()
        rerunSearch()
        RestoreOutcome(restored, conflicts)
    }

    /** KMK (r97): deletes everything in the trash for good. Returns how many. */
    suspend fun emptyTrash(): Int = withIOContext {
        val shred = prefs.shredEnabled.get()
        val before = DeviceFilesTrash.totals(context)
        _state.update { it.copy(progress = 0 to before.first) }
        val done = try {
            DeviceFilesTrash.empty(context, shred) { n, total -> _state.update { it.copy(progress = n to total) } }
        } finally {
            _state.update { it.copy(progress = null) }
        }
        DeviceFilesDeletionLog.log(context, "empty", "trash", before.second, before.first, "$done deleted")
        refresh()
        done
    }

    companion object {
        private const val AUTO_EMPTY_EVERY_MS = 60L * 60 * 1000

        @Volatile
        private var lastAutoEmpty = 0L
    }

    fun toggleFavourites(files: List<DeviceFile>) {
        // All on when any is off, otherwise all off
        val turnOn = files.any { !prefs.isFavourite(it.path) }
        files.forEach { if (prefs.isFavourite(it.path) != turnOn) prefs.toggleFavourite(it.path) }
        if (location == FilesLocation.Favourites) refresh()
    }
}
