package eu.kanade.presentation.more.settings.screen.browse

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import mihon.data.extension.service.ExtensionStoreService
import mihon.data.extension.service.ExtensionStoreUrls
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.AnimeExtensionStoreRepository
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionStoresScreenModel(
    // KMK -->
    /** Manage the anime (Aniyomi) extension repos instead of the manga ones */
    private val anime: Boolean = false,
    private val storeRepository: ExtensionStoreRepository = if (anime) {
        Injekt.get<AnimeExtensionStoreRepository>()
    } else {
        Injekt.get<ExtensionStoreRepository>()
    },
    private val getExtensionStores: GetExtensionStores = GetExtensionStores(storeRepository),
    private val removeExtensionStore: RemoveExtensionStore = RemoveExtensionStore(storeRepository),
    private val updateExtensionStores: UpdateExtensionStores = UpdateExtensionStores(storeRepository),
    // KMK <--
    private val extensionManager: ExtensionManager = Injekt.get(),
    // KMK -->
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    // KMK <--
) : StateScreenModel<ExtensionStoreScreenState>(ExtensionStoreScreenState.Loading) {

    private inline fun updateSuccessState(
        func: (ExtensionStoreScreenState.Success) -> ExtensionStoreScreenState.Success,
    ) {
        mutableState.update {
            when (it) {
                ExtensionStoreScreenState.Loading -> it
                is ExtensionStoreScreenState.Success -> func(it)
            }
        }
    }

    init {
        screenModelScope.launchIO {
            getExtensionStores.subscribe()
                .collectLatest { stores ->
                    // KMK: remembered so the add dialog can offer them again after they're removed
                    runCatching {
                        val known = RepoMemory.all().map { ExtensionStoreUrls.key(it.url) }.toSet()
                        stores.filter { ExtensionStoreUrls.key(it.indexUrl) !in known }
                            .forEach { RepoMemory.remember(it.name, it.indexUrl, listOf(if (anime) "Aniyomi" else "Mihon")) }
                    }
                    mutableState.update {
                        when (it) {
                            ExtensionStoreScreenState.Loading -> ExtensionStoreScreenState.Success(
                                stores = stores,
                                // KMK -->
                                disabledRepos = sourcePreferences.disabledRepos().get(),
                                // KMK <--
                            )
                            is ExtensionStoreScreenState.Success -> it.copy(stores = stores)
                        }
                    }
                }
        }

        // KMK -->
        sourcePreferences.disabledRepos().changes()
            .onEach { disabledRepos ->
                mutableState.update {
                    when (it) {
                        is ExtensionStoreScreenState.Success -> it.copy(disabledRepos = disabledRepos)
                        else -> it
                    }
                }
            }
            .launchIn(screenModelScope)
        // KMK <--
    }

    /**
     * KMK: Looks the repository up (any form of its address, Kototoro-style) and shows what was found before it's
     * added: its name, website, signing key and how many manga and anime packages it has. Several addresses pasted
     * at once (or read from a file) are added straight away.
     */
    fun createRepo(input: String, kind: RepoKind = RepoKind.AUTO) {
        val urls = RepoInput.urls(input)
        if (urls.size > 1) {
            addMany(urls)
            return
        }
        screenModelScope.launchIO {
            setProcessing()
            val found = lookUp(urls.firstOrNull() ?: input)
            found
                .onSuccess { repo ->
                    updateSuccessState { it.copy(dialog = ExtensionStoreDialog.Preview(repo, kind)) }
                }
                .onFailure { showError(it) }
        }
    }

    /** Adds what [createRepo] found, as the kind picked (Auto: by its packages, both when it has both) */
    fun confirmRepo(found: FoundRepo, kind: RepoKind) {
        screenModelScope.launchIO {
            updateSuccessState {
                it.copy(dialog = (it.dialog as? ExtensionStoreDialog.Preview)?.copy(processing = true) ?: it.dialog)
            }
            add(found, kind)
                .onSuccess { added ->
                    withUIContext { Injekt.get<Application>().toast("Added ${found.store.name} ($added)") }
                    dismissDialog()
                }
                .onFailure { e ->
                    updateSuccessState {
                        it.copy(
                            dialog = (it.dialog as? ExtensionStoreDialog.Preview)
                                ?.copy(processing = false, errorMessage = e.message ?: "unknown error")
                                ?: it.dialog,
                        )
                    }
                }
        }
    }

    private fun addMany(urls: List<String>) {
        screenModelScope.launchIO {
            setProcessing()
            var added = 0
            val failed = mutableListOf<String>()
            urls.forEach { url ->
                val result = lookUp(url).mapCatching { add(it, RepoKind.AUTO).getOrThrow() }
                if (result.isSuccess) added++ else failed += url
            }
            withUIContext {
                Injekt.get<Application>().toast(
                    "Added $added of ${urls.size} repositories" +
                        if (failed.isNotEmpty()) ". Not added: ${failed.joinToString()}" else "",
                )
            }
            dismissDialog()
        }
    }

    /** The repository at [input] and its packages, or why it can't be added */
    private suspend fun lookUp(input: String): Result<FoundRepo> = runCatching {
        val service = Injekt.get<ExtensionStoreService>()
        val store = service.resolve(input).getOrElse {
            throw IllegalStateException("No repository found at this address (${it.message})")
        }
        val packages = service.getExtensions(store).getOrElse {
            throw IllegalStateException("Found ${store.name}, but couldn't read its package list (${it.message})")
        }
        val anime = packages.count { it.pkgName.contains(".animeextension.") }
        val existing = allStores()
        val key = ExtensionStoreUrls.key(store.indexUrl)
        val same = existing.firstOrNull { (s, _) ->
            ExtensionStoreUrls.key(s.indexUrl) == key ||
                (
                    store.signingKey != ExtensionStoreService.NO_SIGNING_KEY && store.signingKey.isNotBlank() &&
                        s.signingKey == store.signingKey && s.name == store.name
                    )
        }
        FoundRepo(
            store = store,
            input = input,
            mangaPackages = packages.size - anime,
            animePackages = anime,
            alreadyAddedAs = same?.second,
        )
    }

    private suspend fun add(found: FoundRepo, kind: RepoKind): Result<String> = runCatching {
        val asAnime = when (kind) {
            RepoKind.MIHON -> false
            RepoKind.ANIYOMI -> true
            RepoKind.AUTO -> found.animePackages > 0
        }
        val asManga = when (kind) {
            RepoKind.MIHON -> true
            RepoKind.ANIYOMI -> false
            RepoKind.AUTO -> found.mangaPackages > 0 || found.animePackages == 0
        }
        val url = found.store.indexUrl
        val kinds = mutableListOf<String>()
        if (asManga) {
            Injekt.get<ExtensionStoreRepository>().insert(url).getOrThrow()
            kinds += "Mihon"
        }
        if (asAnime) {
            Injekt.get<AnimeExtensionStoreRepository>().insert(url).getOrThrow()
            kinds += "Aniyomi"
        }
        RepoMemory.remember(found.store.name, url, kinds)
        if (asManga) runCatching { extensionManager.findAvailableExtensions() }
        if (asAnime) runCatching { Injekt.get<AnimeExtensionManager>().findAvailableExtensions() }
        kinds.joinToString(" + ")
    }

    /** Every saved repository, manga and anime, with the kind it's saved as */
    private suspend fun allStores(): List<Pair<ExtensionStore, String>> =
        Injekt.get<ExtensionStoreRepository>().getAll().map { it to "Mihon" } +
            Injekt.get<AnimeExtensionStoreRepository>().getAll().map { it to "Aniyomi" }

    private fun setProcessing() {
        updateSuccessState {
            it.copy(
                dialog = when (it.dialog) {
                    is ExtensionStoreDialog.Create -> it.dialog.copy(processing = true, errorMessage = null)
                    is ExtensionStoreDialog.Confirm -> it.dialog.copy(processing = true, errorMessage = null)
                    else -> it.dialog
                },
            )
        }
    }

    private fun showError(throwable: Throwable) {
        updateSuccessState {
            it.copy(
                dialog = when (it.dialog) {
                    is ExtensionStoreDialog.Create -> it.dialog.copy(
                        processing = false,
                        errorMessage = throwable.message ?: "unknown error",
                    )
                    is ExtensionStoreDialog.Confirm -> it.dialog.copy(
                        processing = false,
                        errorMessage = throwable.message ?: "unknown error",
                    )
                    else -> it.dialog
                },
            )
        }
    }
    // KMK <--

    /**
     * Refreshes information for each repository.
     */
    fun refreshRepos() {
        val status = state.value

        if (status is ExtensionStoreScreenState.Success) {
            screenModelScope.launchIO {
                updateExtensionStores()
            }
        }
    }

    /**
     * Deletes the given repo from the database
     */
    fun deleteRepo(indexUrl: String) {
        // KMK -->
        // Remove repo from disabled list
        enableStore(indexUrl)
        // KMK <--
        screenModelScope.launchIO {
            removeExtensionStore(indexUrl)
            findAvailableExtensions()
        }
    }

    // KMK -->
    fun enableStore(indexUrl: String) {
        val disabledRepos = sourcePreferences.disabledRepos().get()
        if (indexUrl in disabledRepos) {
            sourcePreferences.disabledRepos().set(
                disabledRepos.filterNot { it == indexUrl }.toSet(),
            )
        }
    }

    fun disableStore(indexUrl: String) {
        val disabledRepos = sourcePreferences.disabledRepos().get()
        if (indexUrl !in disabledRepos) {
            sourcePreferences.disabledRepos().set(
                disabledRepos + indexUrl,
            )
        }
    }

    fun refreshExtensionList() {
        screenModelScope.launchIO {
            findAvailableExtensions()
        }
    }
    // KMK <--

    // KMK -->
    private suspend fun findAvailableExtensions() {
        if (anime) {
            Injekt.get<AnimeExtensionManager>().findAvailableExtensions()
        } else {
            extensionManager.findAvailableExtensions()
        }
    }
    // KMK <--

    fun addFromDeeplink(storeIndexUrl: String) {
        updateSuccessState { state ->
            state.copy(
                dialog = ExtensionStoreDialog.Confirm(
                    url = storeIndexUrl,
                    alreadyExists = state.stores.any { ExtensionStoreUrls.key(it.indexUrl) == ExtensionStoreUrls.key(storeIndexUrl) },
                ),
            )
        }
    }

    fun showDialog(dialog: ExtensionStoreDialog) {
        updateSuccessState { state ->
            state.copy(dialog = dialog)
        }
    }

    fun dismissDialog() {
        updateSuccessState {
            it.copy(dialog = null)
        }
    }
}

sealed class ExtensionStoreDialog {
    data class Create(val processing: Boolean = false, val errorMessage: String? = null) : ExtensionStoreDialog()
    data class Delete(val store: ExtensionStore) : ExtensionStoreDialog()

    // KMK: what was found at an address, before it's added
    data class Preview(
        val found: FoundRepo,
        val kind: RepoKind,
        val processing: Boolean = false,
        val errorMessage: String? = null,
    ) : ExtensionStoreDialog()
    data class Confirm(
        val url: String,
        val alreadyExists: Boolean = false,
        val processing: Boolean = false,
        val errorMessage: String? = null,
    ) : ExtensionStoreDialog()
}

sealed class ExtensionStoreScreenState {

    @Immutable
    data object Loading : ExtensionStoreScreenState()

    @Immutable
    data class Success(
        val stores: List<ExtensionStore>,
        val dialog: ExtensionStoreDialog? = null,
        // KMK -->
        val disabledRepos: Set<String> = emptySet(),
        // KMK <--
    ) : ExtensionStoreScreenState() {

        val isEmpty: Boolean
            get() = stores.isEmpty()
    }
}
