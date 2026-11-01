package eu.kanade.tachiyomi.devicefiles

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.util.system.AppLog
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK (r97): the Recover page's data. A file deleted outside the trash bin can't be brought back by the app (it has
 * no raw access to the storage, and Android's shared storage is encrypted and on flash, so there is no "undelete"
 * through the file system). What the app can do: open the device's own bins (Samsung My Files and Gallery keep their
 * own trash), and look for a copy of the file in the folders the app itself writes: downloads, covers, the reader
 * cache, the preview cache and the image cache.
 */
object DeviceFilesRecover {

    /** Another app with its own recycle bin, and how to reach it */
    data class BinApp(
        val title: String,
        val summary: String,
        val packageName: String,
        /** An activity to try before the app's launcher activity */
        val activity: String?,
    )

    private val binApps = listOf(
        BinApp(
            title = "Samsung My Files › Trash",
            summary = "Files deleted with My Files are kept there for 30 days. Open its menu (⋮) and pick Trash.",
            packageName = "com.sec.android.app.myfiles",
            activity = "com.sec.android.app.myfiles.external.ui.MainActivity",
        ),
        BinApp(
            title = "Samsung Gallery › Recycle bin",
            summary = "Pictures and videos deleted with Gallery stay in its Recycle bin for 30 days (menu ⋮ › Recycle bin).",
            packageName = "com.sec.android.gallery3d",
            activity = "com.samsung.android.gallery.app.activity.GalleryActivity",
        ),
        BinApp(
            title = "Files by Google › Trash",
            summary = "Files deleted with Files by Google are kept in its Trash for 30 days (menu ☰ › Trash).",
            packageName = "com.google.android.apps.nbu.files",
            activity = null,
        ),
        BinApp(
            title = "Google Photos › Trash",
            summary = "Backed-up pictures deleted from Photos stay in its Trash for 60 days (Library › Trash).",
            packageName = "com.google.android.apps.photos",
            activity = null,
        ),
    )

    /** The bin apps installed on this device */
    fun installedBinApps(context: Context): List<BinApp> =
        binApps.filter { context.packageManager.getLaunchIntentForPackage(it.packageName) != null }

    /** Opens the app's bin as directly as it allows: its known activity, else its launcher activity */
    fun openBinApp(context: Context, app: BinApp): Boolean {
        val pm = context.packageManager
        val explicit = app.activity?.let {
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName(app.packageName, it))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launcher = pm.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOfNotNull(explicit, launcher)) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** A folder the app writes to, where a copy of a lost file may still be */
    data class ScanRoot(val label: String, val dir: File)

    fun scanRoots(context: Context): List<ScanRoot> = buildList {
        runCatching { Injekt.get<StorageManager>().getDownloadsDirectory()?.filePath }.getOrNull()
            ?.let { add(ScanRoot("Downloads (manga and anime)", File(it))) }
        (context.getExternalFilesDir("covers") ?: File(context.filesDir, "covers")).let { add(ScanRoot("Covers", it)) }
        add(ScanRoot("Reader cache", File(context.cacheDir, "chapter_disk_cache")))
        add(ScanRoot("Preview cache", File(context.cacheDir, PagePreviewCache.PARAMETER_CACHE_DIRECTORY)))
        add(ScanRoot("Image cache", File(context.cacheDir, "image_cache")))
    }.filter { it.dir.isDirectory }

    data class Match(val file: DeviceFile, val where: String, val bySize: Boolean)

    /**
     * Files under the app's folders whose name contains [query] (letter case ignored), or whose size is exactly
     * [size]. Walks for [WALK_MS] at most and stops at [limit] matches.
     */
    fun searchCaches(
        context: Context,
        query: String,
        size: Long?,
        limit: Int = 200,
        isCancelled: () -> Boolean = { false },
    ): List<Match> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty() && (size == null || size <= 0)) return emptyList()
        val found = ArrayList<Match>()
        val deadline = System.currentTimeMillis() + WALK_MS
        for (root in scanRoots(context)) {
            val pending = ArrayDeque<File>().apply { add(root.dir) }
            while (pending.isNotEmpty() && found.size < limit && System.currentTimeMillis() < deadline && !isCancelled()) {
                val dir = pending.removeFirst()
                for (child in dir.listFiles().orEmpty()) {
                    if (found.size >= limit || System.currentTimeMillis() >= deadline || isCancelled()) break
                    if (child.isDirectory) {
                        if (!DeviceFilesTrash.isLink(child)) pending.addLast(child)
                        continue
                    }
                    val byName = needle.isNotEmpty() && needle in child.name.lowercase()
                    val bySize = size != null && size > 0 && child.length() == size
                    if (byName || bySize) found += Match(DeviceFile.of(child), root.label, bySize && !byName)
                }
            }
        }
        return found
    }

    /** The folder "Copy to Downloads/Recovered" writes to */
    fun recoveredDir(): File = File(DeviceFilesRepository.downloadsDir, "Recovered")

    /**
     * Copies [file] into Downloads/Recovered/ (a unique name when one is there already). Returns the copy, or null.
     * A cache entry has no extension; [name] lets the caller give the copy the lost file's name.
     */
    fun copyToRecovered(context: Context, file: File, name: String = file.name): File? {
        val dir = recoveredDir()
        if (!dir.isDirectory && !dir.mkdirs()) return null
        var target = File(dir, name.ifBlank { file.name })
        if (target.exists()) target = DeviceFilesTrash.uniqueName(target)
        return runCatching {
            file.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            DeviceFilesRepository.rescan(context, target.path)
            AppLog.add('I', "files", "recover ${file.path} -> ${target.path}", "size" to target.length())
            target
        }.onFailure { target.delete() }.getOrNull()
    }

    private const val WALK_MS = 8_000L
}
