package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.anime.anilist.AniListSearchModel

class GlobalSearchScreenModel(
    initialQuery: String = "",
    initialExtensionFilter: String? = null,
) : SearchScreenModel(State(searchQuery = initialQuery)) {

    // KMK: results stay until the app is closed, like a source's Browse
    override val keepsResults: Boolean = true

    // KMK: the AniList tab, searching AniList's manga
    val aniList = AniListSearchModel(screenModelScope, manga = true)

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (extensionFilter != null) {
                // we're going to use custom extension filter instead
                setSourceFilter(SourceFilter.All)
            }
            search()
        }

        // KMK -->
        shouldPinnedSourcesHidden()
        // KMK <--
    }

    override fun getEnabledSources(): List<Source> {
        return super.getEnabledSources()
            .filter { state.value.sourceFilter != SourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
    }
}
