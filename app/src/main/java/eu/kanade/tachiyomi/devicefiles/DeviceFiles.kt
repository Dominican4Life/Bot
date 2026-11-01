package eu.kanade.tachiyomi.devicefiles

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK: Device files, a browser for everything on the device's storage, laid out like the Browse page of Google's
 * Files app: categories, recent files, favourites and the storage devices, folder by folder.
 */
enum class FileKind { Folder, Image, Video, Audio, Document, Archive, Apk, Other }

object FileTypes {
    val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "jxl")
    val videos = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv", "3gp", "mpg", "mpeg", "m2ts", "ogv",
    )
    val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "mka")

    /** Comic and book archives the reader opens */
    val archives = setOf("cbz", "zip", "cbr", "rar", "cb7", "7z", "cbt", "tar", "epub")
    val documents = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "odt", "ods", "odp", "rtf", "csv",
        "json", "xml", "html", "htm", "log", "srt", "ass", "vtt",
    )

    fun kindOf(name: String, isDirectory: Boolean = false): FileKind {
        if (isDirectory) return FileKind.Folder
        return when (name.substringAfterLast('.', "").lowercase()) {
            in images -> FileKind.Image
            in videos -> FileKind.Video
            in audio -> FileKind.Audio
            in archives -> FileKind.Archive
            in documents -> FileKind.Document
            "apk" -> FileKind.Apk
            else -> FileKind.Other
        }
    }
}

data class DeviceFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Last changed, in milliseconds */
    val modified: Long,
    /** For a folder: how many things are in it */
    val childCount: Int? = null,
) {
    val kind: FileKind get() = FileTypes.kindOf(name, isDirectory)
    val file: File get() = File(path)
    val parent: String get() = path.substringBeforeLast('/', "")
    val isHidden: Boolean get() = name.startsWith(".")

    companion object {
        fun of(file: File, countChildren: Boolean = false): DeviceFile {
            // KMK: one stat for type, size and date (three separate ones were slow for big folders on shared storage)
            val attrs = runCatching {
                java.nio.file.Files.readAttributes(
                    file.toPath(),
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                )
            }.getOrNull()
            val isDirectory = attrs?.isDirectory ?: file.isDirectory
            return DeviceFile(
                path = file.path,
                name = file.name,
                isDirectory = isDirectory,
                size = if (isDirectory) 0L else attrs?.size() ?: file.length(),
                modified = attrs?.lastModifiedTime()?.toMillis() ?: file.lastModified(),
                childCount = if (isDirectory && countChildren) file.list()?.size ?: 0 else null,
            )
        }
    }
}

enum class FileCategory(val title: String) {
    Downloads("Downloads"),
    Images("Images"),
    Videos("Videos"),
    Audio("Audio"),
    Documents("Documents & other"),
    Apps("Apps"),
}

data class CategorySummary(val count: Int, val size: Long)

data class StorageRoot(
    val path: String,
    val label: String,
    val isPrimary: Boolean,
    val total: Long,
    val free: Long,
)

enum class FileSort(val title: String) { Name("Name"), Date("Date modified"), Size("Size"), Type("Type") }

/** Device files settings, and the favourites collection */
class DeviceFilesPreferences(store: PreferenceStore) {
    val showHidden = store.getBoolean("device_files_show_hidden", false)
    val sort = store.getString("device_files_sort", FileSort.Name.name)
    val sortDescending = store.getBoolean("device_files_sort_desc", false)
    val gridView = store.getBoolean("device_files_grid", false)
    val playVideosInApp = store.getBoolean("device_files_play_in_app", true)
    val readComicsInApp = store.getBoolean("device_files_read_in_app", true)
    val showRecent = store.getBoolean("device_files_show_recent", true)
    val favourites = store.getStringSet("device_files_favourites", emptySet())

    /** History shows only what was played or read from Device files */
    val historyDeviceOnly = store.getBoolean("history_device_files_only", false)

    // KMK (r97): deleting
    /** Delete moves to `<storage>/.komikku-trash/` instead of removing the file */
    val trashEnabled = store.getBoolean("device_files_trash", true)

    /** Days a trashed file is kept before it's deleted for good */
    val trashDays = store.getInt("device_files_trash_days", 30)

    /** Overwrite a file's bytes before a delete-forever (best effort on flash storage) */
    val shredEnabled = store.getBoolean("device_files_shred", false)

    fun sortOrder(): FileSort = runCatching { FileSort.valueOf(sort.get()) }.getOrDefault(FileSort.Name)

    fun isFavourite(path: String): Boolean = path in favourites.get()

    fun toggleFavourite(path: String) {
        val now = favourites.get()
        favourites.set(if (path in now) now - path else now + path)
    }

    companion object {
        val instance: DeviceFilesPreferences by lazy { DeviceFilesPreferences(Injekt.get()) }
    }
}

object DeviceFilesRepository {

    private val filesUri get() = MediaStore.Files.getContentUri("external")

    /** MTP's code for a folder, in MediaStore's format column */
    private const val FORMAT_FOLDER = 0x3001

    /** Whether the app may read all of the device's files */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    val downloadsDir: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private fun selectionFor(category: FileCategory): Pair<String, Array<String>> {
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val data = MediaStore.Files.FileColumns.DATA
        // MTP format column (not in the public constants)
        val format = "format"
        return when (category) {
            FileCategory.Images -> "$type = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            FileCategory.Videos -> "$type = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            FileCategory.Audio -> "$type = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
            FileCategory.Downloads ->
                "$data LIKE ? AND ($format IS NULL OR $format != $FORMAT_FOLDER)" to arrayOf("${downloadsDir.path}/%")
            FileCategory.Apps -> "$data LIKE ?" to arrayOf("%.apk")
            // Everything that isn't a picture, video, song or app: MEDIA_TYPE_NONE, or MEDIA_TYPE_DOCUMENT (6)
            FileCategory.Documents ->
                "($type = 0 OR $type = 6) AND ($format IS NULL OR $format != $FORMAT_FOLDER) AND $data NOT LIKE ?" to
                    arrayOf("%.apk")
        }
    }

    private fun query(
        context: Context,
        selection: String?,
        args: Array<String>?,
        sortColumn: String = MediaStore.Files.FileColumns.DATE_MODIFIED,
        limit: Int = Int.MAX_VALUE,
        showHidden: Boolean,
    ): List<DeviceFile> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val out = ArrayList<DeviceFile>()
        runCatching {
            context.contentResolver.query(filesUri, projection, selection, args, "$sortColumn DESC")?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (cursor.moveToNext() && out.size < limit) {
                    val path = cursor.getString(dataCol) ?: continue
                    if (!showHidden && "/." in path) continue
                    // KMK (r97): the trash bin's contents only show on the Trash page
                    if (DeviceFilesTrash.isTrashPath(path)) continue
                    out += DeviceFile(
                        path = path,
                        name = path.substringAfterLast('/'),
                        isDirectory = false,
                        size = cursor.getLong(sizeCol),
                        modified = cursor.getLong(dateCol) * 1000,
                    )
                }
            }
        }
        // KMK (r97): the index can hold a path whose letter case no longer matches the file (after a case-only
        // rename, until the scan lands). Shared storage ignores case, so `exists()` is true for that stale row and
        // deleting it deletes the real file. Only rows whose exact name is in their folder's listing stay.
        return existingByExactName(out)
    }

    /**
     * KMK (r97): keeps the rows whose exact-case name is in their parent folder's listing. Each parent is listed
     * once per call. A folder that can't be listed (gone, or not readable) keeps its rows unchanged.
     */
    fun existingByExactName(files: List<DeviceFile>): List<DeviceFile> {
        if (files.isEmpty()) return files
        val unlistable = HashSet<String>()
        val listings = HashMap<String, Set<String>>()
        return files.filter { f ->
            val names = listings.getOrPut(f.parent) { File(f.parent).list()?.toHashSet() ?: unlistable }
            names === unlistable || f.name in names
        }
    }

    fun summary(context: Context, category: FileCategory, showHidden: Boolean): CategorySummary {
        // KMK: counted while walking the cursor (no file objects, no sorting). A COUNT(*) projection isn't allowed on
        // Android 10+, and hidden files are told apart by their path anyway
        val (selection, args) = selectionFor(category)
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE)
        var count = 0
        var size = 0L
        runCatching {
            context.contentResolver.query(filesUri, projection, selection, args, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: continue
                    if (!showHidden && "/." in path) continue
                    if (DeviceFilesTrash.isTrashPath(path)) continue
                    count++
                    size += cursor.getLong(sizeCol)
                }
            }
        }
        return CategorySummary(count, size)
    }

    fun category(context: Context, category: FileCategory, showHidden: Boolean): List<DeviceFile> {
        val (selection, args) = selectionFor(category)
        return query(context, selection, args, showHidden = showHidden)
    }

    /** The newest files, leaving out apps' own data and thumbnails */
    fun recent(context: Context, limit: Int, showHidden: Boolean): List<DeviceFile> {
        // MTP format column (not in the public constants)
        val format = "format"
        val data = MediaStore.Files.FileColumns.DATA
        return query(
            context,
            "($format IS NULL OR $format != $FORMAT_FOLDER) AND $data NOT LIKE ? AND ${MediaStore.Files.FileColumns.SIZE} > 0",
            arrayOf("%/Android/%"),
            sortColumn = MediaStore.Files.FileColumns.DATE_ADDED,
            limit = limit,
            showHidden = showHidden,
        )
    }

    /** Files whose name has [text] in it, anywhere on the device */
    fun search(
        context: Context,
        text: String,
        showHidden: Boolean,
        // KMK: the folder walk stops early once a newer search replaced this one
        isCancelled: () -> Boolean = { false },
    ): List<DeviceFile> {
        if (text.isBlank()) return emptyList()
        val escaped = text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        // MTP format column (not in the public constants)
        val format = "format"
        val fromIndex = query(
            context,
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\' AND ($format IS NULL OR $format != $FORMAT_FOLDER)",
            arrayOf("%$escaped%"),
            limit = 500,
            showHidden = showHidden,
        )
        // KMK: plus files the media index doesn't know yet
        // KMK (r97): keyed without letter case. Shared storage ignores case, so two spellings of one path are one
        // file, and listing both gave a second row whose delete removed the file the other row showed
        val known = fromIndex.mapTo(HashSet()) { it.path.lowercase() }
        val walked = runCatching { walkSearch(context, text, showHidden, limit = 500, isCancelled) }
            .getOrDefault(emptyList())
        return fromIndex + walked.filter { known.add(it.path.lowercase()) }.take((500 - fromIndex.size).coerceAtLeast(0))
    }

    // KMK: the entries are read several at a time (a folder of ~2,300 items took over ten seconds one by one)
    fun list(dir: File, showHidden: Boolean): List<DeviceFile> {
        val files = dir.listFiles().orEmpty()
            .filter { (showHidden || !it.name.startsWith(".")) && it.name != DeviceFilesTrash.DIR_NAME }
        if (files.size < PARALLEL_FROM) return files.map { DeviceFile.of(it, countChildren = true) }
        return runBlocking(Dispatchers.IO.limitedParallelism(LIST_THREADS)) {
            files.map { async { DeviceFile.of(it, countChildren = true) } }.awaitAll()
        }
    }

    /**
     * KMK: Android's media index misses files it hasn't scanned yet (just copied or downloaded), so the folders are
     * also walked by name, for a few seconds at most, and the two results are merged.
     */
    private fun walkSearch(
        context: Context,
        text: String,
        showHidden: Boolean,
        limit: Int,
        isCancelled: () -> Boolean,
    ): List<DeviceFile> {
        val needle = text.trim().lowercase()
        val found = ArrayList<DeviceFile>()
        val deadline = System.currentTimeMillis() + WALK_MS
        val pending = ArrayDeque(storageRoots(context).map { File(it.path) })
        while (pending.isNotEmpty() && found.size < limit && System.currentTimeMillis() < deadline && !isCancelled()) {
            val dir = pending.removeFirst()
            // KMK: the limit, time and cancel are checked per entry too: one huge folder ran far past them
            for (child in dir.listFiles().orEmpty()) {
                if (found.size >= limit || System.currentTimeMillis() >= deadline || isCancelled()) break
                val name = child.name
                if (!showHidden && name.startsWith(".")) continue
                if (name == DeviceFilesTrash.DIR_NAME) continue
                if (child.isDirectory) {
                    // app-private data: not readable, and huge
                    if (!(name == "data" || name == "obb") || dir.name != "Android") pending.addLast(child)
                } else if (needle in name.lowercase()) {
                    found += DeviceFile.of(child)
                }
            }
        }
        return found
    }

    private const val PARALLEL_FROM = 64
    private const val LIST_THREADS = 8
    private const val WALK_MS = 4_000L

    fun sorted(files: List<DeviceFile>, sort: FileSort, descending: Boolean): List<DeviceFile> {
        val byName = Comparator<DeviceFile> { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
        val comparator: Comparator<DeviceFile> = when (sort) {
            FileSort.Name -> byName
            FileSort.Date -> compareBy<DeviceFile> { it.modified }.then(byName)
            FileSort.Size -> compareBy<DeviceFile> { it.size }.then(byName)
            FileSort.Type -> compareBy<DeviceFile> { it.name.substringAfterLast('.', "").lowercase() }.then(byName)
        }
        val ordered = if (descending) comparator.reversed() else comparator
        // Folders first, like a file manager
        return files.sortedWith(compareBy<DeviceFile> { !it.isDirectory }.then(ordered))
    }

    fun storageRoots(context: Context): List<StorageRoot> {
        val roots = LinkedHashMap<String, Pair<String, Boolean>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(StorageManager::class.java)
            manager?.storageVolumes?.forEach { volume ->
                val dir = volume.directory ?: return@forEach
                roots[dir.path] = volume.getDescription(context) to volume.isPrimary
            }
        }
        if (roots.isEmpty()) {
            roots[Environment.getExternalStorageDirectory().path] = "Internal storage" to true
            // SD cards: the part of each app folder before /Android/data
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { dir ->
                val root = dir.path.substringBefore("/Android/data")
                roots[root] = "SD card" to false
            }
        }
        return roots.map { (path, info) ->
            val stat = runCatching { StatFs(path) }.getOrNull()
            StorageRoot(
                path = path,
                label = if (info.second) "Internal storage" else info.first,
                isPrimary = info.second,
                total = stat?.totalBytes ?: 0L,
                free = stat?.availableBytes ?: 0L,
            )
        }
    }

    /** Asks the media index to pick up a change made to [paths] */
    fun rescan(context: Context, vararg paths: String) {
        runCatching { android.media.MediaScannerConnection.scanFile(context, paths, null, null) }
    }
}
