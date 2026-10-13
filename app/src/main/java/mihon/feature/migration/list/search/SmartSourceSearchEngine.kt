package mihon.feature.migration.list.search

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.model.Manga

class SmartSourceSearchEngine(extraSearchParams: String?) : BaseSmartSearchEngine<SManga>(extraSearchParams) {

    override fun getTitle(result: SManga) = result.originalTitle

    suspend fun regularSearch(source: Source, title: String): Manga? {
        return regularSearch(makeSearchAction(source), title).let {
            it?.toDomainManga(source.id)
        }
    }

    suspend fun deepSearch(source: Source, title: String): Manga? {
        return deepSearch(makeSearchAction(source), title).let {
            it?.toDomainManga(source.id)
        }
    }

    // KMK -->
    /** Every close enough result, best first */
    suspend fun searchAll(source: Source, title: String, deep: Boolean): List<Manga> {
        val action = makeSearchAction(source)
        val found = if (deep) deepSearchAll(action, title) else regularSearchAll(action, title)
        return found.map { it.toDomainManga(source.id) }
    }
    // KMK <--

    private fun makeSearchAction(source: Source): SearchAction<SManga> = { query ->
        source.getSearchManga(1, query, source.getFilterList()).mangas
    }
}
