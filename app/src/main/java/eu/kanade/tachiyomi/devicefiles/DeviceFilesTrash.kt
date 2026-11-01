package eu.kanade.tachiyomi.devicefiles

import android.content.Context
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * KMK (r97): Device files' trash bin. A delete moves the file into `<storage root>/.komikku-trash/` keeping its path
 * under the root (a rename on the same volume, so it's instant and needs no free space), and an `index.json` in that
 * folder remembers where each one came from. Restore is the rename back. One trash folder per storage device, since
 * a rename can't cross volumes.
 *
 * Why: a delete that removed the wrong file (a second row silently in the selection, or a MediaStore row whose path
 * differed only in letter case) was unrecoverable. With the bin, a wrong delete is an Undo away for [trashDays].
 */
object DeviceFilesTrash {

    const val DIR_NAME = ".komikku-trash"
    private const val INDEX_NAME = "index.json"
    private const val NOMEDIA = ".nomedia"

    /** One trashed file or folder */
    @Serializable
    data class Entry(
        /** Where it is now, inside the trash folder */
        val trashed: String,
        /** Where it was */
        val original: String,
        val deletedAt: Long,
        val size: Long,
        val isDirectory: Boolean = false,
        /** For a folder: how many files were inside */
        val files: Int = 0,
    ) {
        val name: String get() = original.substringAfterLast('/')
        val originalParent: String get() = original.substringBeforeLast('/', "")

        fun asDeviceFile(): DeviceFile = DeviceFile(
            path = trashed,
            name = name,
            isDirectory = isDirectory,
            size = size,
            modified = deletedAt,
            childCount = if (isDirectory) files else null,
        )
    }

    @Serializable
    private data class Index(val entries: List<Entry> = emptyList())

    sealed interface RestoreResult {
        data class Restored(val path: String) : RestoreResult

        /** Something is already at the original path: the caller may offer "restore as a copy" */
        data class Exists(val entry: Entry) : RestoreResult
        data object Failed : RestoreResult
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** One lock for every index file: writes are rare and small */
    private val lock = Any()

    /** The trash folder's own paths, which no listing or search shows */
    fun isTrashPath(path: String): Boolean = path.endsWith("/$DIR_NAME") || "/$DIR_NAME/" in path

    /** The trash folder of the storage device [path] is on, or null when [path] isn't under any known root */
    fun trashDirFor(context: Context, path: String): File? {
        val root = DeviceFilesRepository.storageRoots(context)
            .filter { path == it.path || path.startsWith(it.path + "/") }
            .maxByOrNull { it.path.length } ?: return null
        return File(root.path, DIR_NAME)
    }

    private fun trashDirs(context: Context): List<File> =
        DeviceFilesRepository.storageRoots(context).map { File(it.path, DIR_NAME) }.filter { it.isDirectory }

    private fun ensure(trashDir: File): Boolean {
        if (!trashDir.isDirectory && !trashDir.mkdirs()) return false
        runCatching { File(trashDir, NOMEDIA).createNewFile() }
        return true
    }

    private fun readIndex(trashDir: File): List<Entry> {
        val f = File(trashDir, INDEX_NAME)
        if (!f.isFile) return emptyList()
        return runCatching { json.decodeFromString(Index.serializer(), f.readText()).entries }.getOrDefault(emptyList())
    }

    private fun writeIndex(trashDir: File, entries: List<Entry>) {
        val f = File(trashDir, INDEX_NAME)
        val temp = File(trashDir, "$INDEX_NAME.tmp")
        runCatching {
            temp.writeText(json.encodeToString(Index.serializer(), Index(entries)))
            if (!temp.renameTo(f)) {
                f.delete()
                temp.renameTo(f)
            }
        }
    }

    private fun updateIndex(trashDir: File, change: (MutableList<Entry>) -> Unit) {
        synchronized(lock) {
            val entries = readIndex(trashDir).toMutableList()
            change(entries)
            writeIndex(trashDir, entries)
        }
    }

    /** Size and file count of a file or a folder tree (symbolic links aren't followed) */
    fun measure(file: File): Pair<Long, Int> {
        if (!file.isDirectory || isLink(file)) return (if (file.isFile) file.length() else 0L) to (if (file.isFile) 1 else 0)
        var size = 0L
        var count = 0
        val pending = ArrayDeque<File>().apply { add(file) }
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            for (child in dir.listFiles().orEmpty()) {
                if (child.isDirectory && !isLink(child)) {
                    pending.addLast(child)
                } else if (child.isFile) {
                    size += child.length()
                    count++
                }
            }
        }
        return size to count
    }

    /**
     * Moves [file] into its storage device's trash folder. Returns the index entry, or null when it couldn't be moved
     * (no known root, the trash folder couldn't be made, or the rename failed): the caller decides what to do then.
     */
    fun trash(context: Context, file: File): Entry? {
        val trashDir = trashDirFor(context, file.path) ?: return null
        if (!ensure(trashDir)) return null
        val rootPath = trashDir.parent ?: return null
        val relative = file.path.removePrefix("$rootPath/")
        var target = File(trashDir, relative)
        target.parentFile?.mkdirs()
        if (target.exists()) target = File(trashDir, "$relative~${System.currentTimeMillis()}")
        val isDirectory = file.isDirectory && !isLink(file)
        val (size, count) = measure(file)
        if (!file.renameTo(target)) return null
        val entry = Entry(
            trashed = target.path,
            original = file.path,
            deletedAt = System.currentTimeMillis(),
            size = size,
            isDirectory = isDirectory,
            files = count,
        )
        updateIndex(trashDir) { it += entry }
        return entry
    }

    /** Every trashed item on every storage device, newest first. Entries whose file is gone are dropped. */
    fun entries(context: Context): List<Entry> = trashDirs(context).flatMap { dir ->
        val all = readIndex(dir)
        val present = all.filter { File(it.trashed).exists() }
        if (present.size != all.size) updateIndex(dir) { list -> list.retainAll { File(it.trashed).exists() } }
        present
    }.sortedByDescending { it.deletedAt }

    /** How many items are in the trash, and their size */
    fun totals(context: Context): Pair<Int, Long> {
        val all = entries(context)
        return all.size to all.sumOf { it.size }
    }

    private fun removeEntry(entry: Entry) {
        val trashDir = File(entry.trashed.substringBefore("/$DIR_NAME/") + "/$DIR_NAME")
        if (trashDir.isDirectory) updateIndex(trashDir) { list -> list.removeAll { it.trashed == entry.trashed } }
    }

    /** Moves [entry] back where it came from. Refuses when something is already there (see [RestoreResult.Exists]). */
    fun restore(context: Context, entry: Entry, asCopy: Boolean = false): RestoreResult {
        val source = File(entry.trashed)
        if (!source.exists()) {
            removeEntry(entry)
            return RestoreResult.Failed
        }
        var target = File(entry.original)
        target.parentFile?.mkdirs()
        if (target.exists()) {
            if (!asCopy) return RestoreResult.Exists(entry)
            target = uniqueName(target)
        }
        if (!source.renameTo(target)) return RestoreResult.Failed
        removeEntry(entry)
        DeviceFilesRepository.rescan(context, target.path)
        return RestoreResult.Restored(target.path)
    }

    /** "name (2).ext", "name (3).ext"… the first that doesn't exist */
    fun uniqueName(target: File): File {
        val parent = target.parentFile
        val stem = if (target.isDirectory) target.name else target.name.substringBeforeLast('.', target.name)
        val ext = if (target.isDirectory || '.' !in target.name) "" else "." + target.name.substringAfterLast('.')
        var n = 2
        while (true) {
            val candidate = File(parent, "$stem ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /** Deletes [entry]'s file for good (overwriting first when [shred]). True when it's gone. */
    fun deleteForever(entry: Entry, shred: Boolean): Boolean {
        val file = File(entry.trashed)
        val gone = DeviceFilesDelete.delete(file, shred)
        if (gone) removeEntry(entry)
        return gone
    }

    /** Deletes everything in the trash. [onProgress] gets (done, total). Returns how many were deleted. */
    fun empty(context: Context, shred: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val all = entries(context)
        var done = 0
        all.forEachIndexed { i, entry ->
            onProgress(i + 1, all.size)
            if (deleteForever(entry, shred)) done++
        }
        // Stray files that lost their index entry
        trashDirs(context).forEach { dir ->
            dir.listFiles().orEmpty()
                .filter { it.name != INDEX_NAME && it.name != NOMEDIA }
                .forEach { DeviceFilesDelete.delete(it, shred) }
        }
        return done
    }

    /** Deletes what has been in the trash for more than [days] days. Returns how many. */
    fun autoEmpty(context: Context, days: Int): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        var done = 0
        entries(context).filter { it.deletedAt < cutoff }.forEach { entry ->
            if (deleteForever(entry, shred = false)) {
                done++
                DeviceFilesDeletionLog.log(context, "expire", entry.original, entry.size, entry.files, "deleted")
            }
        }
        return done
    }

    internal fun isLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}

/**
 * KMK (r97): the real delete. Kotlin's `File.deleteRecursively()` follows directory symbolic links (it uses
 * `isDirectory`, which follows them), so a linked folder's contents went too. This walk deletes a link as the link.
 */
object DeviceFilesDelete {

    /** The largest file that's overwritten; bigger ones are only deleted */
    const val SHRED_MAX_BYTES = 2L * 1024 * 1024 * 1024

    /**
     * Deletes [file] (a folder with everything in it). When [shred], every file's bytes are overwritten first, best
     * effort. Returns true when nothing is left at the path.
     */
    fun delete(file: File, shred: Boolean, onFile: ((File) -> Unit)? = null): Boolean {
        if (!file.isDirectory || DeviceFilesTrash.isLink(file)) {
            if (shred && file.isFile) DeviceFilesShred.shred(file)
            onFile?.invoke(file)
            return file.delete() || !exists(file)
        }
        // bottom-up: children first
        val children = file.listFiles().orEmpty()
        var ok = true
        for (child in children) ok = delete(child, shred, onFile) && ok
        onFile?.invoke(file)
        return (ok && file.delete()) || !exists(file)
    }

    /** `exists()` without following a link, so a dangling link counts as present */
    private fun exists(file: File): Boolean =
        runCatching { Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) }.getOrDefault(file.exists())
}

/**
 * KMK (r97): what Device files deleted, moved to the trash or restored: one `I files` log line each, and the last
 * [MAX] operations in `<filesDir>/devicefiles/deletions.json` for the Recover page.
 */
object DeviceFilesDeletionLog {

    private const val MAX = 200

    @Serializable
    data class Op(
        /** trash, delete, restore, empty, expire, refuse */
        val action: String,
        val path: String,
        val size: Long,
        val time: Long,
        val result: String,
        /** For a folder: how many files were inside */
        val files: Int = 0,
    ) {
        val name: String get() = path.substringAfterLast('/')
    }

    @Serializable
    private data class Log(val ops: List<Op> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private fun file(context: Context): File = File(File(context.filesDir, "devicefiles"), "deletions.json")

    fun read(context: Context): List<Op> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching { json.decodeFromString(Log.serializer(), f.readText()).ops }.getOrDefault(emptyList())
    }

    /** Writes one operation to the app log and to the file (newest first) */
    fun log(context: Context, action: String, path: String, size: Long, files: Int, result: String) {
        if (files > 0) {
            AppLog.add('I', "files", "$action $path", "size" to size, "files" to files, "result" to result)
        } else {
            AppLog.add('I', "files", "$action $path", "size" to size, "result" to result)
        }
        val op = Op(action, path, size, System.currentTimeMillis(), result, files)
        synchronized(lock) {
            val f = file(context)
            runCatching {
                f.parentFile?.mkdirs()
                val ops = (listOf(op) + read(context)).take(MAX)
                f.writeText(json.encodeToString(Log.serializer(), Log(ops)))
            }
        }
    }
}
