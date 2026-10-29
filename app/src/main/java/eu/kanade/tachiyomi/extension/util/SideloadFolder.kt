package eu.kanade.tachiyomi.extension.util

import android.content.Context
import eu.kanade.tachiyomi.animeextension.util.AnimeExtensionLoader
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.flow.first
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK: Sideloaded extensions, kept in the app's folder: Sideloaded/Manga and Sideloaded/Anime.
 *
 * Android only runs an extension from the app's own storage, so the app keeps its working copy there too; the APK in
 * the folder is the one that counts. It outlasts a reinstall: [sync] copies what's in the folder back into the app,
 * and takes out of the app what was deleted from the folder. Removing an extension in the app deletes its APK from
 * the folder. Which packages were sideloaded is a setting ([TRACKED_KEY]), so it comes back with a backup; a
 * restore downloads again those whose APK isn't in the folder ([missing]).
 */
object SideloadFolder {

    enum class Kind(val folder: String, val prefix: String) {
        MANGA("Manga", "manga:"),
        ANIME("Anime", "anime:"),
    }

    private const val TRACKED_KEY = "sideloaded_extensions"

    private val tracked get() = Injekt.get<PreferenceStore>().getStringSet(TRACKED_KEY, emptySet())
    private val storage get() = Injekt.get<StorageManager>()

    // KMK (r88): both managers run [sync] at the same time on start; the tracked set is changed by read-modify-write,
    // so one could write back a set without the other's change. Every change goes through this lock
    private val trackedLock = Any()

    private fun track(entry: String, add: Boolean) {
        synchronized(trackedLock) {
            val now = tracked.get()
            tracked.set(if (add) now + entry else now - entry)
        }
    }

    private fun dir(kind: Kind, create: Boolean) = storage.getSideloadedDirectory(kind.folder, create)

    private fun fileName(pkgName: String) = "$pkgName.apk"

    /** KMK: the package an APK file name stands for: the name without ".apk" in any case (".APK", ".Apk") */
    private fun packageOf(name: String): String =
        if (name.endsWith(".apk", ignoreCase = true)) name.dropLast(4) else name

    fun isSideloaded(kind: Kind, pkgName: String): Boolean = (kind.prefix + pkgName) in tracked.get()

    /** A sideloaded APK goes into the folder too (after the app took its own copy) */
    fun save(kind: Kind, pkgName: String, apk: File) {
        runCatching {
            val folder = dir(kind, create = true) ?: error("the app's folder isn't set or can't be reached")
            folder.findFile(fileName(pkgName))?.delete()
            val target = folder.createFile(fileName(pkgName)) ?: error("couldn't create ${fileName(pkgName)}")
            target.openOutputStream().use { out -> apk.inputStream().use { it.copyTo(out) } }
        }.onSuccess {
            // Only what's really in the folder counts as sideloaded through it (else a later check would find it
            // missing there and take it out of the app)
            track(kind.prefix + pkgName, add = true)
        }.onFailure {
            AppLog.add('W', "Sideload", "Couldn't save $pkgName to the Sideloaded folder: ${it.message}")
        }
    }

    /** Removed in the app: its APK leaves the folder */
    fun remove(kind: Kind, pkgName: String) {
        runCatching { dir(kind, create = false)?.findFile(fileName(pkgName))?.delete() }
        track(kind.prefix + pkgName, add = false)
    }

    /**
     * The folder and the app made to agree: an APK in the folder the app doesn't have is copied in, and a sideloaded
     * extension whose APK was deleted from the folder is taken out of the app. Nothing is taken out when the folder
     * can't be read (no folder picked, permission lost), so a missing folder never removes extensions, nor when it
     * has no APKs at all (KMK: moved or cleared storage); only an APK gone from a folder that still lists others.
     */
    fun sync(context: Context, kind: Kind) {
        // KMK: what this kind has tracked as sideloaded, for the checks below
        val trackedHere = tracked.get().filter { it.startsWith(kind.prefix) }
        val folder = dir(kind, create = false)
        if (folder == null || !folder.isDirectory) {
            // A missing folder (not picked yet, moved, permission lost) never removes extensions
            if (trackedHere.isNotEmpty()) {
                AppLog.add(
                    'W',
                    "Sideload",
                    "The Sideloaded/${kind.folder} folder is missing or can't be reached; " +
                        "kept the ${trackedHere.size} sideloaded extensions",
                )
            }
            return
        }
        // KMK: null means the folder couldn't be listed (unreadable): nothing is removed then either
        val listed = runCatching { folder.listFiles() }.getOrNull()
        if (listed == null) {
            if (trackedHere.isNotEmpty()) {
                AppLog.add(
                    'W',
                    "Sideload",
                    "The Sideloaded/${kind.folder} folder couldn't be read; " +
                        "kept the ${trackedHere.size} sideloaded extensions",
                )
            }
            return
        }
        val apks = listed
            .filter { it.isFile && it.name?.endsWith(".apk", ignoreCase = true) == true }
        // KMK: the package name is the file name without its ".apk" in any case ("x.Apk" too); r86 stripped only
        // ".apk"/".APK" here but any extension below, so "x.Apk" looked gone and was uninstalled on every start
        val inFolder = apks.mapNotNull { it.name?.let { name -> packageOf(name) } }.toSet()
        val inApp = when (kind) {
            Kind.MANGA -> ExtensionLoader.privateExtensionPackages(context)
            Kind.ANIME -> AnimeExtensionLoader.privateExtensionPackages(context)
        }.toSet()

        // Folder -> app
        apks.forEach { apk ->
            val pkgName = apk.name?.let { name -> packageOf(name) } ?: return@forEach
            if (pkgName in inApp) {
                track(kind.prefix + pkgName, add = true)
                return@forEach
            }
            val tmp = File(context.cacheDir, "sideload_$pkgName.apk")
            runCatching {
                apk.openInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val ok = when (kind) {
                    Kind.MANGA -> ExtensionLoader.installPrivateExtensionFile(context, tmp)
                    Kind.ANIME -> AnimeExtensionLoader.installPrivateExtensionFile(context, tmp)
                }
                if (ok) {
                    track(kind.prefix + pkgName, add = true)
                    AppLog.add('I', "Sideload", "Put back $pkgName from the Sideloaded folder")
                }
            }.onFailure {
                AppLog.add('W', "Sideload", "Couldn't put back $pkgName from the Sideloaded folder: ${it.message}")
            }
            tmp.delete()
        }

        // Deleted from the folder -> out of the app (only what was sideloaded through the folder)
        val gone = tracked.get().filter { it.startsWith(kind.prefix) }.map { it.removePrefix(kind.prefix) }
            .filter { it !in inFolder && it in inApp }
        // KMK: a folder with no APKs at all while extensions are tracked was most likely moved or cleared (storage
        // wiped, another folder picked), not emptied one by one: nothing is removed then
        if (gone.isNotEmpty() && apks.isEmpty()) {
            AppLog.add(
                'W',
                "Sideload",
                "The Sideloaded/${kind.folder} folder has no APKs; kept the ${gone.size} sideloaded extensions " +
                    "(put their APKs back, or remove them in Extensions)",
            )
            return
        }
        gone.forEach { pkgName ->
            when (kind) {
                Kind.MANGA -> ExtensionLoader.uninstallPrivateExtension(context, pkgName)
                Kind.ANIME -> AnimeExtensionLoader.uninstallPrivateExtension(context, pkgName)
            }
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
            track(kind.prefix + pkgName, add = false)
            AppLog.add('I', "Sideload", "Removed $pkgName: its APK was deleted from the Sideloaded folder")
        }
    }

    /**
     * After a restore: what's in the folder is put back, and what was sideloaded but isn't in the folder is
     * downloaded again from its repo and sideloaded (saved to the folder too).
     */
    suspend fun afterRestore(context: Context) {
        Kind.entries.forEach { kind -> runCatching { sync(context, kind) } }
        val mangaMissing = missing(context, Kind.MANGA)
        val animeMissing = missing(context, Kind.ANIME)
        if (mangaMissing.isEmpty() && animeMissing.isEmpty()) return
        val manga = Injekt.get<eu.kanade.tachiyomi.extension.ExtensionManager>()
        val anime = Injekt.get<eu.kanade.tachiyomi.animeextension.AnimeExtensionManager>()
        if (mangaMissing.isNotEmpty()) runCatching { manga.findAvailableExtensions() }
        if (animeMissing.isNotEmpty()) runCatching { anime.findAvailableExtensions() }
        suspend fun install(kind: Kind, pkgName: String, flow: (eu.kanade.tachiyomi.extension.model.Extension.Available) -> kotlinx.coroutines.flow.Flow<eu.kanade.tachiyomi.extension.model.InstallStep>, available: List<eu.kanade.tachiyomi.extension.model.Extension.Available>) {
            val ext = available.firstOrNull { it.pkgName == pkgName }
            if (ext == null) {
                AppLog.add('W', "Sideload", "$pkgName was sideloaded, but no repo offers it now")
                return
            }
            AppLog.add('I', "Sideload", "Downloading $pkgName again from ${ext.store.name}")
            // Waits for this one to finish before the next
            runCatching { flow(ext).first { it.isCompleted() } }
        }
        mangaMissing.forEach { pkg ->
            install(Kind.MANGA, pkg, { manga.installExtension(it, sideload = true) }, manga.availableNow())
        }
        animeMissing.forEach { pkg ->
            install(Kind.ANIME, pkg, { anime.installExtension(it, sideload = true) }, anime.availableNow())
        }
    }

    /** Packages remembered as sideloaded that neither the folder nor the app has (after a restore, say) */
    fun missing(context: Context, kind: Kind): List<String> {
        val inApp = when (kind) {
            Kind.MANGA -> ExtensionLoader.privateExtensionPackages(context)
            Kind.ANIME -> AnimeExtensionLoader.privateExtensionPackages(context)
        }.toSet()
        return tracked.get().filter { it.startsWith(kind.prefix) }.map { it.removePrefix(kind.prefix) }
            .filter { it !in inApp }
    }
}
