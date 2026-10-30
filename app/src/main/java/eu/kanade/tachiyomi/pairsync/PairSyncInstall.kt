package eu.kanade.tachiyomi.pairsync

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r89): Installs what a sync offered, only when the result card's Install / Update is tapped. Repo extensions go
 * through the normal install path (the installer setting decides how, and it asks as usual); APKs the other device
 * sent open the system installer, which asks first.
 */
object PairSyncInstall {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Installs each missing extension from a repo here (the copy with the other device's signature first). KMK (r90):
     * ones installed by now are skipped, so Install can be tapped again for the ones that were cancelled.
     */
    fun installMissing(offers: List<ExtensionOffer>) {
        val manga = Injekt.get<ExtensionManager>()
        val anime = Injekt.get<AnimeExtensionManager>()
        val installed = runCatching { PairSyncData.installedPackages().toSet() }.getOrDefault(emptySet())
        offers.filter { it.pkgName !in installed }.forEach { offer ->
            val available = (if (offer.isAnime) anime.availableNow() else manga.availableNow())
                .filter { it.pkgName == offer.pkgName }
            val pick: Extension.Available = available.firstOrNull { it.signatureHash == offer.signatureHash }
                ?: available.maxByOrNull { it.versionCode }
                ?: return@forEach
            val flow = runCatching {
                if (offer.isAnime) anime.installExtension(pick) else manga.installExtension(pick)
            }.getOrNull() ?: return@forEach
            follow(flow)
        }
    }

    /**
     * Updates each extension installed here in an older version than the other device's. KMK (r90): ones already at
     * that version are skipped, so Update can be tapped again.
     */
    fun installUpdates(offers: List<ExtensionOffer>) {
        val manga = Injekt.get<ExtensionManager>()
        val anime = Injekt.get<AnimeExtensionManager>()
        offers.forEach { offer ->
            val flow = runCatching {
                if (offer.isAnime) {
                    anime.installedExtensionsFlow.value
                        .firstOrNull { it.pkgName == offer.pkgName && it.versionCode < offer.toVersion }
                        ?.let { anime.updateExtension(it) }
                } else {
                    manga.installedExtensionsFlow.value
                        .firstOrNull { it.pkgName == offer.pkgName && it.versionCode < offer.toVersion }
                        ?.let { manga.updateExtension(it) }
                }
            }.getOrNull() ?: return@forEach
            follow(flow)
        }
    }

    /** Opens the system installer for each APK the other device sent (KMK (r90): not for ones installed by now) */
    fun installApks(context: Context, apks: List<ReceivedApk>) {
        val installed = runCatching { PairSyncData.installedPackages().toSet() }.getOrDefault(emptySet())
        val files = apks.filter { it.file.isFile && it.pkgName !in installed }
        files.forEach { apk ->
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk.file.getUriCompat(context), ExtensionInstaller.APK_MIME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
            }
        }
        if (files.isNotEmpty()) deleteWhenInstalled(files)
    }

    /**
     * KMK (r90): the installer reads each APK only once it shows, so a file is deleted when its package is installed
     * here (checked every few seconds for an hour). Whatever is left goes at the next sync or after a day.
     */
    private fun deleteWhenInstalled(apks: List<ReceivedApk>) {
        scope.launch {
            val left = apks.toMutableList()
            val until = System.currentTimeMillis() + 60L * 60 * 1000
            while (left.isNotEmpty() && System.currentTimeMillis() < until) {
                delay(5_000L)
                val installed = runCatching { PairSyncData.installedPackages().toSet() }.getOrDefault(emptySet())
                val done = left.filter { it.pkgName in installed }
                done.forEach { runCatching { it.file.delete() } }
                left.removeAll(done)
            }
        }
    }

    /** An install's steps only run while collected; this follows it until it's done or failed */
    private fun follow(flow: Flow<InstallStep>) {
        scope.launch {
            runCatching { flow.firstOrNull { it.isCompleted() } }
        }
    }
}
