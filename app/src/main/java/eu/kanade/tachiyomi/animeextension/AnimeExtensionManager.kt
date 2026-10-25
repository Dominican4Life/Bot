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
    val availableExtensionsFlow: StateFlow<List<Extension.Available>> = availableExtensionMapFlow
        .map { it.values.toList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val untrustedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Untrusted>())
    val untrustedExtensionsFlow: StateFlow<List<Extension.Untrusted>> = untrustedExtensionMapFlow
        .map { it.values.toList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launchIO { initExtensions() }
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
    suspend fun findAvailableExtensions() {
        val extensions: List<Extension.Available> = try {
            withIOContext {
                // Keep the store index up to date (name, signing key)
                storeRepository.refreshAll()
                val disabledRepos = preferences.disabledRepos().get()
                storeRepository.fetchExtensions(disabledRepos)
                    .map { it.copy(name = it.name.substringAfter("Aniyomi: ")) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withUIContext { context.toast(MR.strings.extension_api_error) }
            return
        }

        availableExtensionMapFlow.value = extensions.associateBy { it.pkgName + "_${it.signatureHash}" }
        updatedInstalledExtensionsStatuses(extensions)
    }

    private fun updatedInstalledExtensionsStatuses(availableExtensions: List<Extension.Available>) {
        val installedExtensionsMap = installedExtensionMapFlow.value.toMutableMap()
        var changed = false
        for ((pkgName, extension) in installedExtensionsMap) {
            val availableExt = availableExtensions.find {
                it.signatureHash == extension.signatureHash && it.pkgName == pkgName
            }
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

    fun installExtension(extension: Extension.Available): Flow<InstallStep> {
        return installer.downloadAndInstall(extension.apkUrl, extension)
    }

    fun updateExtension(extension: AnimeExtension): Flow<InstallStep> {
        val availableExt = availableExtensionMapFlow.value[extension.pkgName + "_${extension.signatureHash}"]
            ?: return emptyFlow()
        return installExtension(availableExt)
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
            ?: availableExtensionMapFlow.value[pkgName + "_$signatureHash"]
            ?: return false
        return availableExt.versionCode > versionCode || availableExt.libVersion > libVersion
    }
}
