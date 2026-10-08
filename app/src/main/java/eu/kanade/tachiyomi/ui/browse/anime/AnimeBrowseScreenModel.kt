package eu.kanade.tachiyomi.ui.browse.anime

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: State for the anime half of the Browse screen: anime sources and anime extensions.
 */
class AnimeBrowseScreenModel(
    private val extensionManager: AnimeExtensionManager = Injekt.get(),
    sourceManager: AnimeSourceManager = Injekt.get(),
) : StateScreenModel<AnimeBrowseScreenModel.State>(State()) {

    private val installSteps = MutableStateFlow(emptyMap<String, InstallStep>())

    init {
        screenModelScope.launchIO {
            combine(
                sourceManager.catalogueSources,
                extensionManager.installedExtensionsFlow,
                extensionManager.availableExtensionsFlow,
                extensionManager.untrustedExtensionsFlow,
                installSteps,
            ) { sources, installed, available, untrusted, steps ->
                val installedPkgs = installed.map { it.pkgName }.toSet() + untrusted.map { it.pkgName }
                State(
                    sources = sources.sortedWith(compareBy({ it.lang }, { it.name.lowercase() })),
                    updates = installed.filter { it.hasUpdate }.sortedBy { it.name.lowercase() },
                    installed = installed.filterNot { it.hasUpdate }.sortedBy { it.name.lowercase() },
                    untrusted = untrusted.sortedBy { it.name.lowercase() },
                    available = available
                        .filter { it.pkgName !in installedPkgs }
                        .distinctBy { it.pkgName }
                        .sortedWith(compareBy({ it.lang }, { it.name.lowercase() })),
                    installSteps = steps,
                )
            }.collectLatest { newState ->
                mutableState.update {
                    newState.copy(
                        sourceQuery = it.sourceQuery,
                        extensionQuery = it.extensionQuery,
                        isRefreshing = it.isRefreshing,
                    )
                }
            }
        }
        refreshExtensions()
    }

    fun refreshExtensions() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }
            extensionManager.findAvailableExtensions()
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    fun searchSources(query: String?) {
        mutableState.update { it.copy(sourceQuery = query) }
    }

    fun searchExtensions(query: String?) {
        mutableState.update { it.copy(extensionQuery = query) }
    }

    fun install(extension: Extension.Available) {
        extensionManager.installExtension(extension).track(extension.pkgName)
    }

    fun update(extension: AnimeExtension) {
        extensionManager.updateExtension(extension).track(extension.pkgName)
    }

    fun cancelInstall(pkgName: String, signatureHash: String) {
        extensionManager.cancelInstallUpdateExtension(pkgName, signatureHash)
        installSteps.update { it - pkgName }
    }

    fun uninstall(pkgName: String) {
        extensionManager.uninstallExtension(pkgName)
    }

    fun trust(extension: Extension.Untrusted) {
        screenModelScope.launchIO { extensionManager.trust(extension) }
    }

    private fun Flow<InstallStep>.track(pkgName: String) {
        screenModelScope.launchIO {
            onCompletion { installSteps.update { it - pkgName } }
                .collectLatest { step -> installSteps.update { it + (pkgName to step) } }
        }
    }

    @Immutable
    data class State(
        val sources: List<AnimeCatalogueSource> = emptyList(),
        val updates: List<AnimeExtension> = emptyList(),
        val installed: List<AnimeExtension> = emptyList(),
        val untrusted: List<Extension.Untrusted> = emptyList(),
        val available: List<Extension.Available> = emptyList(),
        val installSteps: Map<String, InstallStep> = emptyMap(),
        val sourceQuery: String? = null,
        val extensionQuery: String? = null,
        val isRefreshing: Boolean = false,
    ) {
        val filteredSources: List<AnimeCatalogueSource>
            get() = sourceQuery?.takeIf { it.isNotBlank() }
                ?.let { q -> sources.filter { it.name.contains(q, ignoreCase = true) } }
                ?: sources

        private fun matches(name: String) =
            extensionQuery.isNullOrBlank() || name.contains(extensionQuery, ignoreCase = true)

        val filteredUpdates get() = updates.filter { matches(it.name) }
        val filteredInstalled get() = installed.filter { matches(it.name) }
        val filteredUntrusted get() = untrusted.filter { matches(it.name) }
        val filteredAvailable get() = available.filter { matches(it.name) }
    }
}
