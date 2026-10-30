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
        return out
    }

    fun summary(context: Context, category: FileCategory, showHidden: Boolean): CategorySummary {
        val (selection, args) = selectionFor(category)
        val files = query(context, selection, args, showHidden = showHidden)
        return CategorySummary(files.size, files.sumOf { it.size })
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
        val known = fromIndex.mapTo(HashSet()) { it.path }
        val walked = runCatching { walkSearch(context, text, showHidden, limit = 500, isCancelled) }
            .getOrDefault(emptyList())
        return fromIndex + walked.filter { it.path !in known }.take((500 - fromIndex.size).coerceAtLeast(0))
    }

    // KMK: the entries are read several at a time (a folder of ~2,300 items took over ten seconds one by one)
    fun list(dir: File, showHidden: Boolean): List<DeviceFile> {
        val files = dir.listFiles().orEmpty().filter { showHidden || !it.name.startsWith(".") }
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
            dir.listFiles().orEmpty().forEach { child ->
                val name = child.name
                if (!showHidden && name.startsWith(".")) return@forEach
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
