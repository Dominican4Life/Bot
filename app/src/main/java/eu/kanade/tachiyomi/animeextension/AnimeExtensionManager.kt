package eu.kanade.tachiyomi.animeextension

import android.content.Context
import android.graphics.drawable.Drawable
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.animeextension.model.AnimeLoadResult
import eu.kanade.tachiyomi.animeextension.util.AnimeExtensionInstallReceiver
import eu.kanade.tachiyomi.animeextension.util.AnimeExtensionInstaller
import eu.kanade.tachiyomi.animeextension.util.AnimeExtensionLoader
import eu.kanade.tachiyomi.extension.availableKey
import eu.kanade.tachiyomi.extension.copyFor
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import mihon.domain.extension.repository.AnimeExtensionStoreRepository
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Manages anime (Aniyomi) extensions: loading the installed ones, finding available ones in the
 * anime repos, and installing/updating/removing them. Works like the manga ExtensionManager, and shares
 * its install service, package installer and trust list.
 */
class AnimeExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
    private val storeRepository: AnimeExtensionStoreRepository = Injekt.get(),
) {

    val scope = CoroutineScope(SupervisorJob())

    private val trustExtension by lazy { TrustExtension(storeRepository, preferences) }

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val installer by lazy { AnimeExtensionInstaller(context) }

    private val iconMap = mutableMapOf<String, Drawable>()

    private val installedExtensionMapFlow = MutableStateFlow(emptyMap<String, AnimeExtension>())
    val installedExtensionsFlow: StateFlow<List<AnimeExtension>> = installedExtensionMapFlow
        .map { it.values.toList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val availableExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Available>())
    /** KMK: the packages the repos offer, as last read */
    fun availableNow(): List<Extension.Available> = availableExtensionMapFlow.value.values.toList()

    val availableExtensionsFlow: StateFlow<List<Extension.Available>> = availableExtensionMapFlow
        .map { it.values.toList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val untrustedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Untrusted>())
    val untrustedExtensionsFlow: StateFlow<List<Extension.Untrusted>> = untrustedExtensionMapFlow
        .map { it.values.toList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launchIO {
            // KMK: the Sideloaded folder and the app made to agree first, so what it put back loads now
            runCatching {
                eu.kanade.tachiyomi.extension.util.SideloadFolder.sync(
                    context,
                    eu.kanade.tachiyomi.extension.util.SideloadFolder.Kind.ANIME,
                )
            }
            initExtensions()
        }
        AnimeExtensionInstallReceiver(InstallationListener()).register(context)
    }

    fun getExtensionPackage(sourceId: Long): String? {
        return installedExtensionMapFlow.value.values
            .find { extension -> extension.sources.any { it.id == sourceId } }
            ?.pkgName
    }

    fun getAppIconForSource(sourceId: Long): Drawable? {
        // KMK: a Code editor source shows its own icon, even when it took an extension's id
        eu.kanade.tachiyomi.jssource.JsIcons.drawable(context, sourceId)?.let { return it }
        val pkgName = getExtensionPackage(sourceId)
            ?: return eu.kanade.tachiyomi.jssource.JsIcons.drawable(context, sourceId)
        return iconMap[pkgName] ?: installedExtensionMapFlow.value[pkgName]?.icon?.also { iconMap[pkgName] = it }
    }

    private fun initExtensions() {
        val extensions = AnimeExtensionLoader.loadExtensions(context)

        installedExtensionMapFlow.value = extensions
            .filterIsInstance<AnimeLoadResult.Success>()
            .associate { it.extension.pkgName to it.extension }

        untrustedExtensionMapFlow.value = extensions
            .filterIsInstance<AnimeLoadResult.Untrusted>()
            .associate { it.extension.pkgName to it.extension }

        _isInitialized.value = true
    }

    /**
     * Finds the extensions available in the anime repos.
     */
    // KMK: one read of the repos at a time: a read asked for while one runs waits for it and uses what it found
    // (opening Extensions, a restore and a refresh each read every repo, at the same moment)
    private val findLock = kotlinx.coroutines.sync.Mutex()

    suspend fun findAvailableExtensions() {
        if (findLock.isLocked) {
            findLock.lock()
            findLock.unlock()
            return
        }
        findLock.lock()
        try {
            findAvailableExtensionsNow()
        } finally {
            findLock.unlock()
        }
    }

    private suspend fun findAvailableExtensionsNow() {
        val extensions: List<Extension.Available> = try {
            withIOContext {
                // Keep the store index up to date (name, signing key)
                storeRepository.refreshAll()
                val disabledRepos = preferences.disabledRepos().get()
                storeRepository.fetchExtensions(disabledRepos)
                    // KMK: manga packages of a repo that has both are listed on the manga side
                    .filterNot { it.pkgName.contains(".tachiyomi.extension.") }
                    .map { it.copy(name = it.name.substringAfter("Aniyomi: ")) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withUIContext { context.toast(MR.strings.extension_api_error) }
            return
        }

        // KMK: one entry per package, signature and repo: two repos with the same (or no) signing key offering the
        // same package used to overwrite each other, so one of them showed fewer packages
        availableExtensionMapFlow.value = extensions.associateBy { availableKey(it) }
        updatedInstalledExtensionsStatuses(extensions)
    }

    private fun updatedInstalledExtensionsStatuses(availableExtensions: List<Extension.Available>) {
        val installedExtensionsMap = installedExtensionMapFlow.value.toMutableMap()
        var changed = false
        for ((pkgName, extension) in installedExtensionsMap) {
            // KMK: same signature (or, for a repo with no signing fingerprint, the package name), from the repo it
            // came from when that one still offers it
            val availableExt = availableExtensions.copyFor(pkgName, extension.signatureHash, extension.store)
            if (availableExt == null) {
                if (!extension.isObsolete || extension.hasUpdate) {
                    installedExtensionsMap[pkgName] = extension.copy(isObsolete = true, hasUpdate = false)
                    changed = true
                }
            } else {
                installedExtensionsMap[pkgName] = extension.copy(
                    hasUpdate = extension.updateExists(availableExt),
                    store = availableExt.store,
                    isObsolete = false,
                    storeName = extension.storeName ?: availableExt.storeName,
                )
                changed = true
            }
        }
        if (changed) installedExtensionMapFlow.value = installedExtensionsMap
    }

    // KMK: [sideload] true/false from the Sideload / Install buttons, null as set in the installer setting
    fun installExtension(extension: Extension.Available, sideload: Boolean? = null): Flow<InstallStep> {
        return installer.downloadAndInstall(extension.apkUrl, extension, sideload)
    }

    fun updateExtension(extension: AnimeExtension): Flow<InstallStep> {
        // KMK: the copy of the repo it came from (else same signature, else a repo with no fingerprint by name)
        val availableExt = availableExtensionMapFlow.value.values
            .copyFor(extension.pkgName, extension.signatureHash, extension.store)
            ?: return emptyFlow()
        // KMK: updated the way it was installed: sideloaded stays sideloaded
        return installExtension(availableExt, sideload = !extension.isShared)
    }

    fun cancelInstallUpdateExtension(pkgName: String, signatureHash: String) {
        installer.cancelInstall("${pkgName}_$signatureHash")
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        installer.updateInstallStep(downloadId, step)
    }

    fun uninstallExtension(pkgName: String) {
        installer.uninstallApk(pkgName)
    }

    suspend fun trust(extension: Extension.Untrusted) {
        untrustedExtensionMapFlow.value[extension.pkgName] ?: return

        trustExtension.trust(extension.pkgName, extension.versionCode, extension.signatureHash)

        untrustedExtensionMapFlow.value -= extension.pkgName

        (AnimeExtensionLoader.loadExtensionFromPkgName(context, extension.pkgName) as? AnimeLoadResult.Success)
            ?.let { registerExtension(it.extension) }
    }

    private fun registerExtension(extension: AnimeExtension) {
        installedExtensionMapFlow.value += (extension.pkgName to extension)
    }

    private fun unregisterExtension(pkgName: String) {
        installedExtensionMapFlow.value -= pkgName
        untrustedExtensionMapFlow.value -= pkgName
        iconMap.remove(pkgName)
    }

    private inner class InstallationListener : AnimeExtensionInstallReceiver.Listener {
        override fun onExtensionInstalled(extension: AnimeExtension) {
            registerExtension(extension.withUpdateCheck())
        }

        override fun onExtensionUpdated(extension: AnimeExtension) {
            iconMap.remove(extension.pkgName)
            registerExtension(extension.withUpdateCheck())
        }

        override fun onExtensionUntrusted(extension: Extension.Untrusted) {
            installedExtensionMapFlow.value -= extension.pkgName
            untrustedExtensionMapFlow.value += (extension.pkgName to extension)
        }

        override fun onPackageUninstalled(pkgName: String) {
            // Only care about packages that are anime extensions
            if (pkgName !in installedExtensionMapFlow.value && pkgName !in untrustedExtensionMapFlow.value) return
            AnimeExtensionLoader.uninstallPrivateExtension(context, pkgName)
            unregisterExtension(pkgName)
        }
    }

    private fun AnimeExtension.withUpdateCheck(): AnimeExtension {
        return if (updateExists()) copy(hasUpdate = true) else this
    }

    private fun AnimeExtension.updateExists(availableExtension: Extension.Available? = null): Boolean {
        val availableExt = availableExtension
            // KMK: the map is keyed by package, signature and repo
            ?: availableExtensionMapFlow.value.values.copyFor(pkgName, signatureHash, store)
            ?: return false
        return availableExt.versionCode > versionCode || availableExt.libVersion > libVersion
    }
}
