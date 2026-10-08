package eu.kanade.tachiyomi.animeextension

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * KMK: Keeps track of the anime sources provided by the installed anime extensions.
 */
class AnimeSourceManager(
    extensionManager: AnimeExtensionManager,
) {
    private val scope = CoroutineScope(SupervisorJob())

    private val sourcesMapFlow: StateFlow<Map<Long, AnimeSource>> = extensionManager.installedExtensionsFlow
        .map { extensions ->
            extensions.flatMap { it.sources }.associateBy { it.id }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val catalogueSources: StateFlow<List<AnimeCatalogueSource>> = sourcesMapFlow
        .map { it.values.filterIsInstance<AnimeCatalogueSource>() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun get(sourceKey: Long): AnimeSource? = sourcesMapFlow.value[sourceKey]

    fun getCatalogueSources(): List<AnimeCatalogueSource> = catalogueSources.value
}
