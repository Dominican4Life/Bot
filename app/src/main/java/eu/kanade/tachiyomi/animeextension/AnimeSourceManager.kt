package eu.kanade.tachiyomi.animeextension

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Keeps track of the anime sources provided by the installed anime extensions.
 */
class AnimeSourceManager(
    extensionManager: AnimeExtensionManager,
) {
    private val scope = CoroutineScope(SupervisorJob())

    // Sources made in the Code editor are anime sources too
    private val sumizuriManager: eu.kanade.tachiyomi.sumizuri.SumizuriManager by lazy { Injekt.get() }

    // Videos played from Device files
    private val deviceFiles = eu.kanade.tachiyomi.devicefiles.DeviceFilesAnimeSource()

    // Videos found inside manga chapters, played from the reader
    private val readerVideos = eu.kanade.tachiyomi.sumizuri.ReaderVideosSource()

    private val sourcesMapFlow: StateFlow<Map<Long, AnimeSource>> = extensionManager.installedExtensionsFlow
        .combine(sumizuriManager.animeSourcesFlow) { extensions, jsSources ->
            (extensions.flatMap { it.sources } + jsSources + deviceFiles + readerVideos).associateBy { it.id }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val catalogueSources: StateFlow<List<AnimeCatalogueSource>> = sourcesMapFlow
        .map { it.values.filterIsInstance<AnimeCatalogueSource>() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun get(sourceKey: Long): AnimeSource? = sourcesMapFlow.value[sourceKey]

    fun getCatalogueSources(): List<AnimeCatalogueSource> = catalogueSources.value
}
