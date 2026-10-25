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
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.launch
import mihon.data.extension.service.ExtensionStoreStatus
import mihon.data.extension.service.ExtensionStoreUrls
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
    mangaStores: ExtensionStoreRepository = Injekt.get(),
    animeStores: AnimeExtensionStoreRepository = Injekt.get(),
    // KMK: JS / JSON sources for the JS tab
    private val jsSources: JsSourceManager = Injekt.get(),
) : StateScreenModel<UnifiedExtensionsScreenModel.State>(
    // KMK: what was shown last time, until the lists are read again
    lastBuilt?.let { (packages, sources, repos) ->
        State(isLoading = false, allPackages = packages, allSources = sources, allRepos = repos)
    } ?: State(),
) {

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
            // KMK: APK sources switched off for a JS source with their id show as off too
            jsSources.apkOffFlow,
        ) { (disabled, pinned, animeDisabled), animePinned, languages, disabledRepos, apkOff ->
            val off = apkOff.map { it.toString() }.toSet()
            Prefs(disabled + off, pinned, animeDisabled + off, animePinned, languages, disabledRepos)
        }

        screenModelScope.launchIO {
            combine(mangaFlow, animeFlow, prefsFlow, steps, ExtensionStoreStatus.entries) { manga, anime, prefs, steps, status ->
                // KMK: a failed rebuild is logged and skipped, so later changes (like a switch) still show
                runCatching { build(manga, anime, prefs, steps, status) }
                    .onFailure { AppLog.add('E', "extensions", "Rebuilding the extension list failed: ${it.message}") }
                    .getOrNull()
            }.collectLatest { built ->
                if (built == null) return@collectLatest
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        allPackages = built.first,
                        allSources = built.second,
                        allRepos = built.third,
                    )
                }
                // KMK: kept for the next time the screen opens, so it shows at once
                lastBuilt = built
            }
        }
        // KMK --> the saved JS / JSON sources, listed with the other sources under a JS tag
        screenModelScope.launchIO {
            combine(
                jsSources.savedFlow,
                preferences.disabledSources().changes(),
                preferences.animeDisabledSources().changes(),
                preferences.pinnedSources().changes(),
                preferences.animePinnedSources().changes(),
            ) { saved, disabled, animeDisabled, pinned, animePinned ->
                saved
                    .map { info ->
                        val id = info.id.toString()
                        val enabled = info.enabled && id !in (if (info.anime) animeDisabled else disabled)
                        UnifiedSource(
                            id = info.id,
                            kind = if (info.json) ExtensionKind.JSON else ExtensionKind.JS,
                            name = info.name,
                            lang = info.displayLang,
                            enabled = enabled,
                            pinned = id in (if (info.anime) animePinned else pinned),
                            isNsfw = false,
                            pkgName = "",
                            packageName = info.baseUrl,
                            repoUrl = null,
                            repoName = null,
                            icon = null,
                            js = UnifiedJsSource(
                                key = info.key,
                                id = info.id,
                                name = info.name,
                                lang = info.displayLang,
                                baseUrl = info.baseUrl,
                                iconUrl = info.iconUrl,
                                json = info.json,
                                anime = info.anime,
                                enabled = enabled,
                            ),
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            }.collectLatest { list -> mutableState.update { it.copy(allJsSources = list) } }
        }
        // KMK <--
        refresh()
    }

    private fun build(
        manga: MangaData,
        anime: AnimeData,
        prefs: Prefs,
        steps: Map<String, InstallStep>,
        status: Map<String, ExtensionStoreStatus.Entry> = emptyMap(),
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
            // KMK: every package of every repo is listed (anime ones weren't filtered by language either); the
            // language setting still decides which sources show in Browse
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

        // Repositories
        fun repos(kind: ExtensionKind, stores: List<ExtensionStore>) = stores.map { store ->
            // KMK: by repository, not exact address (a refresh can rewrite it)
            val storeKey = ExtensionStoreUrls.key(store.indexUrl)
            val inRepo = packages.filter { it.kind == kind && it.repoUrl?.let(ExtensionStoreUrls::key) == storeKey }
            val st = status[store.indexUrl]
            UnifiedRepo(
                lastSuccess = st?.lastSuccess,
                lastError = st?.lastError,
                kind = kind,
                indexUrl = store.indexUrl,
                name = store.name,
                website = store.contact.website.takeIf { it.isNotBlank() },
                packageCount = inRepo.size,
                installedCount = inRepo.count { it.state.isInstalled },
                enabled = store.indexUrl !in prefs.disabledRepos,
            )
        }
        val repos = repos(ExtensionKind.MIHON, manga.stores) + repos(ExtensionKind.ANIYOMI, anime.stores)

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
            // One at a time, like the other rows (tapping the picked one again shows all sources)
            kind in it.filters.kinds -> emptySet()
            else -> setOf(kind)
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

    /** KMK: Reads one kind of repository again (the refresh button on a repository's row) */
    fun refreshRepos(kind: ExtensionKind) {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }
            if (kind == ExtensionKind.ANIYOMI) {
                runCatching { animeManager.findAvailableExtensions() }
            } else {
                runCatching { Injekt.get<ExtensionStoreRepository>().refreshAll() }
                runCatching { mangaManager.findAvailableExtensions() }
            }
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    // KMK --> picking several sources at once
    fun toggleSelection(source: UnifiedSource) = mutableState.update {
        val key = selectionKey(source)
        it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key)
    }

    fun selectAll(sources: List<UnifiedSource>) = mutableState.update {
        it.copy(selected = it.selected + sources.map(::selectionKey))
    }

    fun clearSelection() = mutableState.update { it.copy(selected = emptySet()) }

    fun selectedSources(): List<UnifiedSource> = state.value.let { st ->
        (st.allSources + st.allJsSources).filter { selectionKey(it) in st.selected }
    }

    fun setSelectedEnabled(enabled: Boolean) {
        selectedSources().forEach { setSourceEnabled(it, enabled) }
        clearSelection()
    }

    fun setSelectedPinned(pinned: Boolean) {
        selectedSources().filter { it.pinned != pinned }.forEach { togglePin(it) }
        clearSelection()
    }

    /** Removes the selected JS / JSON sources (APK sources are removed by uninstalling their package) */
    fun deleteSelectedJs() {
        selectedSources().forEach { deleteJs(it) }
        clearSelection()
    }

    // KMK --> testing whether sources work: each one's first popular page, four at a time
    private var testJob: kotlinx.coroutines.Job? = null

    fun testSources(sources: List<UnifiedSource>) {
        testJob?.cancel()
        val list = sources.distinctBy(::selectionKey)
        mutableState.update { it.copy(tests = list.associate { s -> selectionKey(s) to SourceTest.Running }, testing = true) }
        testJob = screenModelScope.launchIO {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                list.forEach { source ->
                    launch {
                        gate.acquire()
                        try {
                            val result = SourceTester.test(source)
                            mutableState.update { it.copy(tests = it.tests + (selectionKey(source) to result)) }
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
            val failed = state.value.tests.values.count { it is SourceTest.Failed }
            AppLog.add('I', "extensions", "Tested ${list.size} sources: ${list.size - failed} working, $failed failed")
            mutableState.update { it.copy(testing = false) }
        }
    }

    fun stopTests() {
        testJob?.cancel()
        mutableState.update { st ->
            st.copy(testing = false, tests = st.tests.filterValues { it !is SourceTest.Running })
        }
    }

    fun clearTests() = mutableState.update { it.copy(tests = emptyMap(), failedOnly = false) }

    fun toggleFailedOnly() = mutableState.update { it.copy(failedOnly = !it.failedOnly) }
    // KMK <--

    fun refresh() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }
            runCatching { mangaManager.findAvailableExtensions() }
            runCatching { animeManager.findAvailableExtensions() }
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

    fun update(item: UnifiedPackage) {
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
        // KMK: the switch moves straight away instead of waiting for the list to be rebuilt
        mutableState.update { state ->
            state.copy(
                allSources = state.allSources.map {
                    if (it.id == source.id && it.kind == source.kind) it.copy(enabled = enabled) else it
                },
            )
        }
        if (source.js == null) {
            AppLog.add('I', "extensions", "${source.name} (${source.kind.label}) switched ${if (enabled) "on" else "off"}")
        }
        val pref = if (source.isAnime) preferences.animeDisabledSources() else preferences.disabledSources()
        val id = source.id.toString()
        // KMK --> a JS source and an APK can share an id: each keeps its own switch, so turning one off hands the
        // id to the other instead of hiding both
        val jsKey = source.js?.key
        if (jsKey != null) {
            jsSources.setEnabled(jsKey, enabled)
            if (enabled) pref.set(pref.get() - id)
            return
        }
        if (jsSources.sharesId(source.id, source.isAnime)) {
            jsSources.setApkEnabled(source.id, enabled)
            if (enabled) pref.set(pref.get() - id)
            return
        }
        // KMK <--
        pref.set(if (enabled) pref.get() - id else pref.get() + id)
    }

    fun setAllSourcesEnabled(sources: List<UnifiedSource>, enabled: Boolean) {
        sources.forEach { setSourceEnabled(it, enabled) }
    }

    // KMK -->
    /** Removes a JS source and everything it saved; its manga stay in the library as unavailable */
    fun deleteJs(source: UnifiedSource) {
        source.js?.let { jsSources.delete(it.key) }
    }
    // KMK <--

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
        // KMK
        val allJsSources: List<UnifiedSource> = emptyList(),
        val filters: UnifiedFilters = UnifiedFilters(),
        // KMK: sources picked for a bulk action ("kind_id")
        val selected: Set<String> = emptySet(),
        // KMK: source test results by "kind_id"
        val tests: Map<String, SourceTest> = emptyMap(),
        val testing: Boolean = false,
        val failedOnly: Boolean = false,
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
            // KMK: JS sources join the list, under their own kind chip
            get() = (allSources + allJsSources).filter { s ->
                (!failedOnly || tests[selectionKey(s)] is SourceTest.Failed) &&
                    matchesKind(s.kind) &&
                    (filters.repoUrl == null || s.repoUrl == filters.repoUrl) &&
                    matchesQuery(s.name, s.packageName)
            }

        val selectionMode: Boolean get() = selected.isNotEmpty()

        val repos: List<UnifiedRepo>
            get() = allRepos.filter { r -> matchesKind(r.kind) && matchesQuery(r.name, r.indexUrl) }

        val updateCount: Int
            get() = allPackages.count { it.state == PackageState.UPDATE_AVAILABLE }
    }
}

// KMK --> the lists as last built, for opening the screen at once next time (this run of the app)
@Volatile
private var lastBuilt: Triple<List<UnifiedPackage>, List<UnifiedSource>, List<UnifiedRepo>>? = null

/** How a source is told apart in selections and tests */
fun selectionKey(source: UnifiedSource): String = "${source.kind}_${source.id}"
// KMK <--
