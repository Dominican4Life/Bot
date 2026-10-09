package eu.kanade.tachiyomi.ui.browse.extension.unified

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.AnimeExtensionStoreRepository
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: State of the unified Extensions screen (Kototoro-style): every source, repository and package, whatever
 * kind of extension it comes from, with the same filters on top.
 */
class UnifiedExtensionsScreenModel(
    private val mangaManager: ExtensionManager = Injekt.get(),
    private val animeManager: AnimeExtensionManager = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    private val jarManager: eu.kanade.tachiyomi.jarextension.JarExtensionManager = Injekt.get(),
    private val csManager: eu.kanade.tachiyomi.cloudstream.CloudstreamManager = Injekt.get(),
    mangaStores: ExtensionStoreRepository = Injekt.get(),
    animeStores: AnimeExtensionStoreRepository = Injekt.get(),
) : StateScreenModel<UnifiedExtensionsScreenModel.State>(State()) {

    private val steps = MutableStateFlow(emptyMap<String, InstallStep>())

    private data class MangaData(
        val installed: List<Extension.Installed>,
        val untrusted: List<Extension.Untrusted>,
        val available: List<Extension.Available>,
        val stores: List<ExtensionStore>,
    )

    private data class AnimeData(
        val installed: List<AnimeExtension>,
        val untrusted: List<Extension.Untrusted>,
        val available: List<Extension.Available>,
        val stores: List<ExtensionStore>,
    )

    private data class JarData(
        val installed: List<eu.kanade.tachiyomi.jarextension.JarExtensionManager.Installed>,
        val available: List<eu.kanade.tachiyomi.jarextension.JarExtensionManager.Available>,
        val repos: List<eu.kanade.tachiyomi.jarextension.JarExtensionManager.Repo>,
        val installing: Set<String>,
    )

    private data class CsData(
        val installed: List<eu.kanade.tachiyomi.cloudstream.CloudstreamManager.Installed>,
        val available: List<eu.kanade.tachiyomi.cloudstream.CloudstreamManager.Available>,
        val repos: List<eu.kanade.tachiyomi.cloudstream.CloudstreamManager.Repo>,
        val installing: Set<String>,
    )

    private data class Prefs(
        val disabled: Set<String>,
        val pinned: Set<String>,
        val animeDisabled: Set<String>,
        val animePinned: Set<String>,
        val languages: Set<String>,
        val disabledRepos: Set<String>,
    )

    init {
        val mangaFlow = combine(
            mangaManager.installedExtensionsFlow,
            mangaManager.untrustedExtensionsFlow,
            mangaManager.availableExtensionsFlow,
            mangaStores.getAllAsFlow(),
        ) { installed, untrusted, available, stores -> MangaData(installed, untrusted, available, stores) }
        val animeFlow = combine(
            animeManager.installedExtensionsFlow,
            animeManager.untrustedExtensionsFlow,
            animeManager.availableExtensionsFlow,
            animeStores.getAllAsFlow(),
        ) { installed, untrusted, available, stores -> AnimeData(installed, untrusted, available, stores) }
        val prefsFlow = combine(
            combine(
                preferences.disabledSources().changes(),
                preferences.pinnedSources().changes(),
                preferences.animeDisabledSources().changes(),
            ) { a, b, c -> Triple(a, b, c) },
            preferences.animePinnedSources().changes(),
            preferences.enabledLanguages().changes(),
            preferences.disabledRepos().changes(),
        ) { (disabled, pinned, animeDisabled), animePinned, languages, disabledRepos ->
            Prefs(disabled, pinned, animeDisabled, animePinned, languages, disabledRepos)
        }

        val jarFlow = combine(
            jarManager.installedFlow,
            jarManager.availableFlow,
            jarManager.reposFlow,
            jarManager.installingFlow,
        ) { installed, available, repos, installing -> JarData(installed, available, repos, installing) }
        val csFlow = combine(
            csManager.installedFlow,
            csManager.availableFlow,
            csManager.reposFlow,
            csManager.installingFlow,
        ) { installed, available, repos, installing -> CsData(installed, available, repos, installing) }
        val extraFlow = combine(jarFlow, csFlow) { jar, cs -> jar to cs }

        screenModelScope.launchIO {
            combine(mangaFlow, animeFlow, prefsFlow, steps, extraFlow) { manga, anime, prefs, steps, (jar, cs) ->
                build(manga, anime, prefs, steps, jar, cs)
            }.collectLatest { built ->
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        allPackages = built.first,
                        allSources = built.second,
                        allRepos = built.third,
                    )
                }
            }
        }
        refresh()
    }

    private fun build(
        manga: MangaData,
        anime: AnimeData,
        prefs: Prefs,
        steps: Map<String, InstallStep>,
        jar: JarData,
        cs: CsData,
    ): Triple<List<UnifiedPackage>, List<UnifiedSource>, List<UnifiedRepo>> {
        val showNsfw = preferences.showNsfwSource().get()
        val packages = mutableListOf<UnifiedPackage>()
        val sources = mutableListOf<UnifiedSource>()

        // Mihon (manga) extensions
        val mangaAvailable = manga.available.associateBy { it.pkgName }
        manga.installed.forEach { ext ->
            val key = key(ExtensionKind.MIHON, ext.pkgName)
            val repoUrl = ext.store?.indexUrl
            val repoName = ext.store?.name ?: ext.storeName
            val active = ext.sources.count { it.id.toString() !in prefs.disabled }
            packages += UnifiedPackage(
                key = key,
                kind = ExtensionKind.MIHON,
                name = ext.name,
                pkgName = ext.pkgName,
                versionName = if (ext.hasUpdate) mangaAvailable[ext.pkgName]?.versionName ?: ext.versionName else ext.versionName,
                installedVersionName = ext.versionName.takeIf { ext.hasUpdate },
                lang = ext.lang,
                isNsfw = ext.isNsfw,
                repoUrl = repoUrl,
                repoName = repoName,
                state = when {
                    steps[key]?.isCompleted() == false -> PackageState.INSTALLING
                    ext.hasUpdate -> PackageState.UPDATE_AVAILABLE
                    ext.isObsolete -> PackageState.OBSOLETE
                    else -> PackageState.INSTALLED
                },
                installStep = steps[key] ?: InstallStep.Idle,
                icon = ext.icon,
                iconUrl = mangaAvailable[ext.pkgName]?.iconUrl,
                sourceNames = ext.sources.map { it.name }.distinct(),
                activeSources = active,
                totalSources = ext.sources.size,
                signatureHash = ext.signatureHash,
                manga = ext,
            )
            ext.sources.forEach { source ->
                sources += UnifiedSource(
                    id = source.id,
                    kind = ExtensionKind.MIHON,
                    name = source.name,
                    lang = source.lang,
                    enabled = source.id.toString() !in prefs.disabled,
                    pinned = source.id.toString() in prefs.pinned,
                    isNsfw = ext.isNsfw,
                    pkgName = ext.pkgName,
                    packageName = ext.name,
                    repoUrl = repoUrl,
                    repoName = repoName,
                    icon = ext.icon,
                )
            }
        }
        manga.untrusted.forEach { ext ->
            packages += untrustedPackage(ExtensionKind.MIHON, ext, steps).copy(manga = ext)
        }
        val mangaTaken = manga.installed.map { it.pkgName }.toSet() + manga.untrusted.map { it.pkgName }
        manga.available
            .filter { it.pkgName !in mangaTaken }
            .filter { showNsfw || !it.isNsfw }
            .filter { ext -> ext.sources.any { it.lang in prefs.languages } || ext.lang in prefs.languages }
            .forEach { ext -> packages += availablePackage(ExtensionKind.MIHON, ext, steps).copy(manga = ext) }

        // Aniyomi (anime) extensions
        val animeAvailable = anime.available.associateBy { it.pkgName }
        anime.installed.forEach { ext ->
            val key = key(ExtensionKind.ANIYOMI, ext.pkgName)
            val repoUrl = ext.store?.indexUrl
            val repoName = ext.store?.name ?: ext.storeName
            packages += UnifiedPackage(
                key = key,
                kind = ExtensionKind.ANIYOMI,
                name = ext.name,
                pkgName = ext.pkgName,
                versionName = if (ext.hasUpdate) animeAvailable[ext.pkgName]?.versionName ?: ext.versionName else ext.versionName,
                installedVersionName = ext.versionName.takeIf { ext.hasUpdate },
                lang = ext.lang,
                isNsfw = ext.isNsfw,
                repoUrl = repoUrl,
                repoName = repoName,
                state = when {
                    steps[key]?.isCompleted() == false -> PackageState.INSTALLING
                    ext.hasUpdate -> PackageState.UPDATE_AVAILABLE
                    ext.isObsolete -> PackageState.OBSOLETE
                    else -> PackageState.INSTALLED
                },
                installStep = steps[key] ?: InstallStep.Idle,
                icon = ext.icon,
                iconUrl = animeAvailable[ext.pkgName]?.iconUrl,
                sourceNames = ext.sources.map { it.name }.distinct(),
                activeSources = ext.sources.count { it.id.toString() !in prefs.animeDisabled },
                totalSources = ext.sources.size,
                signatureHash = ext.signatureHash,
                anime = ext,
            )
            ext.sources.forEach { source ->
                sources += UnifiedSource(
                    id = source.id,
                    kind = ExtensionKind.ANIYOMI,
                    name = source.name,
                    lang = source.lang,
                    enabled = source.id.toString() !in prefs.animeDisabled,
                    pinned = source.id.toString() in prefs.animePinned,
                    isNsfw = ext.isNsfw,
                    pkgName = ext.pkgName,
                    packageName = ext.name,
                    repoUrl = repoUrl,
                    repoName = repoName,
                    icon = ext.icon,
                )
            }
        }
        anime.untrusted.forEach { ext ->
            packages += untrustedPackage(ExtensionKind.ANIYOMI, ext, steps).copy(animeOther = ext)
        }
        val animeTaken = anime.installed.map { it.pkgName }.toSet() + anime.untrusted.map { it.pkgName }
        anime.available
            .filter { it.pkgName !in animeTaken }
            .distinctBy { it.pkgName }
            .filter { showNsfw || !it.isNsfw }
            .forEach { ext -> packages += availablePackage(ExtensionKind.ANIYOMI, ext, steps).copy(animeOther = ext) }

        // JAR (Kotatsu / Tsuki parser) extensions
        val jarAvailable = jar.available.associateBy { it.pkgName }
        jar.installed.forEach { installed ->
            val meta = installed.meta
            val offer = jarAvailable[meta.pkgName]
            val hasUpdate = offer != null && offer.versionName != meta.versionName
            val key = key(ExtensionKind.JAR, meta.pkgName)
            packages += UnifiedPackage(
                key = key,
                kind = ExtensionKind.JAR,
                name = meta.name,
                pkgName = meta.pkgName,
                versionName = if (hasUpdate) offer!!.versionName else meta.versionName,
                installedVersionName = meta.versionName.takeIf { hasUpdate },
                lang = installed.sources.map { it.lang }.distinct().singleOrNull() ?: "all",
                isNsfw = installed.sources.any { it.info.isNsfw },
                repoUrl = meta.repoUrl,
                repoName = meta.repoName ?: installed.architecture?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
                state = when {
                    meta.pkgName in jar.installing -> PackageState.INSTALLING
                    hasUpdate -> PackageState.UPDATE_AVAILABLE
                    installed.error != null -> PackageState.OBSOLETE
                    else -> PackageState.INSTALLED
                },
                installStep = if (meta.pkgName in jar.installing) InstallStep.Downloading else InstallStep.Idle,
                icon = null,
                iconUrl = null,
                sourceNames = installed.sources.map { it.name },
                activeSources = installed.sources.count { it.id.toString() !in prefs.disabled },
                totalSources = installed.sources.size,
                signatureHash = "",
                jarAvailable = offer,
                error = installed.error,
            )
            installed.sources.forEach { source ->
                sources += UnifiedSource(
                    id = source.id,
                    kind = ExtensionKind.JAR,
                    name = source.name,
                    lang = source.lang,
                    enabled = source.id.toString() !in prefs.disabled,
                    pinned = source.id.toString() in prefs.pinned,
                    isNsfw = source.info.isNsfw,
                    pkgName = meta.pkgName,
                    packageName = meta.name,
                    repoUrl = meta.repoUrl,
                    repoName = meta.repoName,
                    icon = null,
                )
            }
        }
        val jarTaken = jar.installed.map { it.pkgName }.toSet()
        jar.available.filter { it.pkgName !in jarTaken }.forEach { offer ->
            packages += UnifiedPackage(
                key = key(ExtensionKind.JAR, offer.pkgName),
                kind = ExtensionKind.JAR,
                name = offer.name,
                pkgName = offer.pkgName,
                versionName = offer.versionName,
                installedVersionName = null,
                lang = "all",
                isNsfw = false,
                repoUrl = offer.repoUrl,
                repoName = offer.repoName,
                state = if (offer.pkgName in jar.installing) PackageState.INSTALLING else PackageState.AVAILABLE,
                installStep = if (offer.pkgName in jar.installing) InstallStep.Downloading else InstallStep.Idle,
                icon = null,
                iconUrl = null,
                sourceNames = listOf(offer.fileName),
                activeSources = 0,
                totalSources = 0,
                signatureHash = "",
                jarAvailable = offer,
            )
        }

        // Cloudstream plugins (anime sources)
        val csAvailable = cs.available.associateBy { it.internalName }
        cs.installed.forEach { installed ->
            val meta = installed.meta
            val offer = csAvailable[meta.internalName]
            val hasUpdate = offer != null && offer.version > meta.version
            val busy = meta.internalName in cs.installing
            packages += UnifiedPackage(
                key = key(ExtensionKind.CLOUDSTREAM, meta.internalName),
                kind = ExtensionKind.CLOUDSTREAM,
                name = meta.name,
                pkgName = meta.internalName,
                versionName = (if (hasUpdate) offer!!.version else meta.version).toString(),
                installedVersionName = meta.version.toString().takeIf { hasUpdate },
                lang = meta.language ?: installed.sources.map { it.lang }.distinct().singleOrNull() ?: "all",
                isNsfw = offer?.tvTypes?.any { it.equals("NSFW", true) } == true,
                repoUrl = meta.repoUrl,
                repoName = meta.repoName,
                state = when {
                    busy -> PackageState.INSTALLING
                    hasUpdate -> PackageState.UPDATE_AVAILABLE
                    installed.error != null -> PackageState.OBSOLETE
                    else -> PackageState.INSTALLED
                },
                installStep = if (busy) InstallStep.Downloading else InstallStep.Idle,
                icon = null,
                iconUrl = meta.iconUrl ?: offer?.iconUrl,
                sourceNames = installed.sources.map { it.name },
                activeSources = installed.sources.count { it.id.toString() !in prefs.animeDisabled },
                totalSources = installed.sources.size,
                signatureHash = "",
                csAvailable = offer,
                error = installed.error,
            )
            installed.sources.forEach { source ->
                sources += UnifiedSource(
                    id = source.id,
                    kind = ExtensionKind.CLOUDSTREAM,
                    name = source.name,
                    lang = source.lang,
                    enabled = source.id.toString() !in prefs.animeDisabled,
                    pinned = source.id.toString() in prefs.animePinned,
                    isNsfw = false,
                    pkgName = meta.internalName,
                    packageName = meta.name,
                    repoUrl = meta.repoUrl,
                    repoName = meta.repoName,
                    icon = null,
                )
            }
        }
        val csTaken = cs.installed.map { it.internalName }.toSet()
        cs.available.filter { it.internalName !in csTaken }.forEach { offer ->
            val busy = offer.internalName in cs.installing
            packages += UnifiedPackage(
                key = key(ExtensionKind.CLOUDSTREAM, offer.internalName),
                kind = ExtensionKind.CLOUDSTREAM,
                name = offer.name,
                pkgName = offer.internalName,
                versionName = offer.version.toString(),
                installedVersionName = null,
                lang = offer.language ?: "all",
                isNsfw = offer.tvTypes.any { it.equals("NSFW", true) },
                repoUrl = offer.repoUrl,
                repoName = offer.repoName,
                state = if (busy) PackageState.INSTALLING else PackageState.AVAILABLE,
                installStep = if (busy) InstallStep.Downloading else InstallStep.Idle,
                icon = null,
                iconUrl = offer.iconUrl,
                sourceNames = offer.tvTypes,
                activeSources = 0,
                totalSources = 0,
                signatureHash = "",
                csAvailable = offer,
            )
        }

        // Repositories
        fun repos(kind: ExtensionKind, stores: List<ExtensionStore>) = stores.map { store ->
            val inRepo = packages.filter { it.kind == kind && it.repoUrl == store.indexUrl }
            UnifiedRepo(
                kind = kind,
                indexUrl = store.indexUrl,
                name = store.name,
                website = store.contact.website.takeIf { it.isNotBlank() },
                packageCount = inRepo.size,
                installedCount = inRepo.count { it.state.isInstalled },
                enabled = store.indexUrl !in prefs.disabledRepos,
            )
        }
        val jarRepos = jar.repos.map { repo ->
            val inRepo = packages.filter { it.kind == ExtensionKind.JAR && it.repoUrl == repo.url }
            UnifiedRepo(
                kind = ExtensionKind.JAR,
                indexUrl = repo.url,
                name = repo.name,
                website = repo.url,
                packageCount = inRepo.size,
                installedCount = inRepo.count { it.state.isInstalled },
                enabled = true,
                error = repo.error,
            )
        }
        val csRepos = cs.repos.map { repo ->
            val inRepo = packages.filter { it.kind == ExtensionKind.CLOUDSTREAM && it.repoUrl == repo.url }
            UnifiedRepo(
                kind = ExtensionKind.CLOUDSTREAM,
                indexUrl = repo.url,
                name = repo.name,
                website = repo.url,
                packageCount = inRepo.size,
                installedCount = inRepo.count { it.state.isInstalled },
                enabled = true,
                error = repo.error,
            )
        }
        val repos = repos(ExtensionKind.MIHON, manga.stores) + repos(ExtensionKind.ANIYOMI, anime.stores) +
            jarRepos + csRepos

        val order = compareBy<UnifiedPackage>(
            { it.state != PackageState.UPDATE_AVAILABLE && it.state != PackageState.INSTALLING },
            { !it.state.isInstalled },
            { it.name.lowercase() },
        )
        return Triple(
            packages.sortedWith(order),
            sources.sortedWith(compareBy({ !it.pinned }, { it.name.lowercase() })),
            repos.sortedBy { it.name.lowercase() },
        )
    }

    private fun availablePackage(kind: ExtensionKind, ext: Extension.Available, steps: Map<String, InstallStep>): UnifiedPackage {
        val key = key(kind, ext.pkgName)
        return UnifiedPackage(
            key = key,
            kind = kind,
            name = ext.name,
            pkgName = ext.pkgName,
            versionName = ext.versionName,
            installedVersionName = null,
            lang = ext.lang,
            isNsfw = ext.isNsfw,
            repoUrl = ext.store.indexUrl,
            repoName = ext.store.name,
            state = if (steps[key]?.isCompleted() == false) PackageState.INSTALLING else PackageState.AVAILABLE,
            installStep = steps[key] ?: InstallStep.Idle,
            icon = null,
            iconUrl = ext.iconUrl,
            sourceNames = ext.sources.map { it.name }.distinct(),
            activeSources = 0,
            totalSources = ext.sources.size,
            signatureHash = ext.signatureHash,
        )
    }

    private fun untrustedPackage(kind: ExtensionKind, ext: Extension.Untrusted, steps: Map<String, InstallStep>): UnifiedPackage {
        val key = key(kind, ext.pkgName)
        return UnifiedPackage(
            key = key,
            kind = kind,
            name = ext.name,
            pkgName = ext.pkgName,
            versionName = ext.versionName,
            installedVersionName = null,
            lang = ext.lang.orEmpty(),
            isNsfw = ext.isNsfw,
            repoUrl = null,
            repoName = ext.storeName,
            state = PackageState.UNTRUSTED,
            installStep = steps[key] ?: InstallStep.Idle,
            icon = null,
            iconUrl = null,
            sourceNames = emptyList(),
            activeSources = 0,
            totalSources = 0,
            signatureHash = ext.signatureHash,
        )
    }

    private fun key(kind: ExtensionKind, pkgName: String) = "${kind.name}:$pkgName"

    // Filters

    fun search(query: String) = mutableState.update { it.copy(filters = it.filters.copy(query = query)) }

    fun toggleKind(kind: ExtensionKind?) = mutableState.update {
        val kinds = when {
            kind == null -> emptySet()
            kind in it.filters.kinds -> it.filters.kinds - kind
            else -> it.filters.kinds + kind
        }
        it.copy(filters = it.filters.copy(kinds = kinds))
    }

    fun selectRepo(url: String?) = mutableState.update {
        it.copy(filters = it.filters.copy(repoUrl = url.takeIf { u -> u != it.filters.repoUrl }))
    }

    fun selectStatus(status: PackageStatusFilter) = mutableState.update {
        it.copy(filters = it.filters.copy(status = status))
    }

    // Actions

    fun refresh() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }
            runCatching { mangaManager.findAvailableExtensions() }
            runCatching { animeManager.findAvailableExtensions() }
            runCatching { jarManager.refreshRepos() }
            runCatching { csManager.refreshRepos() }
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    /** Install, update or trust: whatever the package's main button does */
    fun primaryAction(item: UnifiedPackage) {
        when (item.state) {
            PackageState.AVAILABLE -> install(item)
            PackageState.UPDATE_AVAILABLE -> update(item)
            PackageState.UNTRUSTED -> trust(item)
            else -> Unit
        }
    }

    fun install(item: UnifiedPackage) {
        if (item.kind == ExtensionKind.JAR) {
            installJar(item)
            return
        }
        if (item.kind == ExtensionKind.CLOUDSTREAM) {
            installCloudstream(item)
            return
        }
        when (val ext = item.manga ?: item.animeOther) {
            is Extension.Available -> {
                if (item.kind == ExtensionKind.ANIYOMI) {
                    animeManager.installExtension(ext).track(item.key)
                } else {
                    mangaManager.installExtension(ext).track(item.key)
                }
            }
            else -> Unit
        }
    }

    private fun installCloudstream(item: UnifiedPackage) {
        val offer = item.csAvailable ?: return
        screenModelScope.launchIO {
            csManager.install(offer).onFailure { e ->
                mutableState.update { it.copy(message = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Imports a picked file: `.cs3` as a Cloudstream plugin, anything else as a JAR */
    fun importFile(context: android.content.Context, uri: android.net.Uri) {
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }
        }.getOrNull().orEmpty()
        if (name.endsWith(".cs3", ignoreCase = true)) importCloudstream(uri) else importJar(uri)
    }

    fun importCloudstream(uri: android.net.Uri) {
        screenModelScope.launchIO {
            csManager.importLocal(uri)
                .onSuccess { name -> mutableState.update { it.copy(message = "Imported $name") } }
                .onFailure { e -> mutableState.update { it.copy(message = e.message ?: e.javaClass.simpleName) } }
        }
    }

    /**
     * Adds a repository of the right kind: a GitHub repository is a JAR repo, a `repo.json` with plugin lists is a
     * Cloudstream repo, and anything else goes to [fallback] (the Mihon / Aniyomi repo screens).
     */
    fun addRepo(url: String, onAdded: () -> Unit, fallback: () -> Unit) {
        if (addJarRepoIfMatches(url)) {
            onAdded()
            return
        }
        screenModelScope.launchIO {
            val probe = csManager.probeRepo(url)
            if (probe != null) {
                csManager.addRepo(probe)
                mutableState.update { it.copy(message = "Added Cloudstream repository ${probe.name}") }
                tachiyomi.core.common.util.lang.withUIContext { onAdded() }
            } else {
                tachiyomi.core.common.util.lang.withUIContext { fallback() }
            }
        }
    }

    fun removeCloudstreamRepo(url: String) = csManager.removeRepo(url)

    private fun installJar(item: UnifiedPackage) {
        val offer = item.jarAvailable ?: return
        screenModelScope.launchIO {
            jarManager.install(offer).onFailure { e ->
                mutableState.update { it.copy(message = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun importJar(uri: android.net.Uri) {
        screenModelScope.launchIO {
            jarManager.importLocal(uri)
                .onSuccess { name -> mutableState.update { it.copy(message = "Imported $name") } }
                .onFailure { e -> mutableState.update { it.copy(message = e.message ?: e.javaClass.simpleName) } }
        }
    }

    /** Adds a JAR repository when [url] is one; false when it's for the APK repo screens */
    fun addJarRepoIfMatches(url: String): Boolean {
        if (!jarManager.isJarRepoUrl(url)) return false
        jarManager.addRepo(url)
        return true
    }

    fun removeJarRepo(url: String) = jarManager.removeRepo(url)

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    fun update(item: UnifiedPackage) {
        if (item.kind == ExtensionKind.JAR) {
            installJar(item)
            return
        }
        if (item.kind == ExtensionKind.CLOUDSTREAM) {
            installCloudstream(item)
            return
        }
        item.anime?.let { animeManager.updateExtension(it).track(item.key) }
        (item.manga as? Extension.Installed)?.let { mangaManager.updateExtension(it).track(item.key) }
    }

    fun updateAll() {
        state.value.allPackages.filter { it.state == PackageState.UPDATE_AVAILABLE }.forEach(::update)
    }

    fun cancel(item: UnifiedPackage) {
        if (item.kind == ExtensionKind.ANIYOMI) {
            animeManager.cancelInstallUpdateExtension(item.pkgName, item.signatureHash)
        } else {
            item.manga?.let { mangaManager.cancelInstallUpdateExtension(it) }
        }
        steps.update { it - item.key }
    }

    fun uninstall(item: UnifiedPackage) {
        if (item.kind == ExtensionKind.JAR) {
            jarManager.uninstall(item.pkgName)
            return
        }
        if (item.kind == ExtensionKind.CLOUDSTREAM) {
            csManager.uninstall(item.pkgName)
            return
        }
        if (item.kind == ExtensionKind.ANIYOMI) {
            animeManager.uninstallExtension(item.pkgName)
        } else {
            item.manga?.let { mangaManager.uninstallExtension(it) }
        }
    }

    fun trust(item: UnifiedPackage) {
        screenModelScope.launchIO {
            (item.manga as? Extension.Untrusted)?.let { mangaManager.trust(it) }
            (item.animeOther as? Extension.Untrusted)?.let { animeManager.trust(it) }
        }
    }

    fun setSourceEnabled(source: UnifiedSource, enabled: Boolean) {
        val pref = if (source.isAnime) preferences.animeDisabledSources() else preferences.disabledSources()
        val id = source.id.toString()
        pref.set(if (enabled) pref.get() - id else pref.get() + id)
    }

    fun setAllSourcesEnabled(sources: List<UnifiedSource>, enabled: Boolean) {
        sources.groupBy { it.isAnime }.forEach { (isAnime, list) ->
            val pref = if (isAnime) preferences.animeDisabledSources() else preferences.disabledSources()
            val ids = list.map { it.id.toString() }.toSet()
            pref.set(if (enabled) pref.get() - ids else pref.get() + ids)
        }
    }

    fun togglePin(source: UnifiedSource) {
        val pref = if (source.isAnime) preferences.animePinnedSources() else preferences.pinnedSources()
        val id = source.id.toString()
        pref.set(if (id in pref.get()) pref.get() - id else pref.get() + id)
    }

    fun setRepoEnabled(repo: UnifiedRepo, enabled: Boolean) {
        val pref = preferences.disabledRepos()
        pref.set(if (enabled) pref.get() - repo.indexUrl else pref.get() + repo.indexUrl)
        refresh()
    }

    private fun Flow<InstallStep>.track(key: String) {
        screenModelScope.launchIO {
            this@track
                .takeWhile { it != InstallStep.Installed }
                .onCompletion { steps.update { it - key } }
                .collectLatest { step -> steps.update { it + (key to step) } }
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val allPackages: List<UnifiedPackage> = emptyList(),
        val allSources: List<UnifiedSource> = emptyList(),
        val allRepos: List<UnifiedRepo> = emptyList(),
        val filters: UnifiedFilters = UnifiedFilters(),
        val message: String? = null,
    ) {
        private fun matchesQuery(vararg text: String?): Boolean {
            val query = filters.query.trim()
            if (query.isEmpty()) return true
            return text.any { it?.contains(query, ignoreCase = true) == true }
        }

        private fun matchesKind(kind: ExtensionKind) = filters.kinds.isEmpty() || kind in filters.kinds

        val packages: List<UnifiedPackage>
            get() = allPackages.filter { p ->
                matchesKind(p.kind) &&
                    (filters.repoUrl == null || p.repoUrl == filters.repoUrl) &&
                    filters.status.matches(p.state) &&
                    (matchesQuery(p.name, p.pkgName) || p.sourceNames.any { matchesQuery(it) })
            }

        val sources: List<UnifiedSource>
            get() = allSources.filter { s ->
                matchesKind(s.kind) &&
                    (filters.repoUrl == null || s.repoUrl == filters.repoUrl) &&
                    matchesQuery(s.name, s.packageName)
            }

        val repos: List<UnifiedRepo>
            get() = allRepos.filter { r -> matchesKind(r.kind) && matchesQuery(r.name, r.indexUrl) }

        val updateCount: Int
            get() = allPackages.count { it.state == PackageState.UPDATE_AVAILABLE }
    }
}
