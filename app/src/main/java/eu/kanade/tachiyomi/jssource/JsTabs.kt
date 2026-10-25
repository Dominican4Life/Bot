package eu.kanade.tachiyomi.jssource

/** KMK: One of a source's catalogs marked `tab: true`, shown as a button before Filter in Browse */
data class JsCatalogTab(val id: String, val name: String)

/**
 * KMK: What a JS source adds to the buttons at the top of Browse: its `tab: true` catalogs as buttons. A catalog button lists that catalog the way picking it in the filter sheet
 * does. A source with tab catalogs shows them instead of Popular and Latest, and opens on the one used last.
 */
interface JsTabs {
    val catalogTabs: List<JsCatalogTab>

    /** Picks catalog [id] in [filters], a filter list this source made. False when there is nothing to pick */
    fun selectCatalog(filters: Iterable<*>, id: String): Boolean

    /** The catalog picked in [filters], or null */
    fun selectedCatalog(filters: Iterable<*>): String?

    /** The tab catalog last opened in this source, if any */
    fun lastTab(): String?

    fun rememberTab(id: String)

    /** The name the source gives its Popular button (`popularName`), or null for the app's "Popular" */
    val popularName: String? get() = null

    /** The name the source gives its Latest button (`latestName`), or null for the app's "Latest" */
    val latestName: String? get() = null

    /** True when the source's tab catalogs replace Popular and Latest */
    val hasCatalogTabs: Boolean get() = catalogTabs.isNotEmpty()

    /** The tab catalog Browse opens on: the one used last, or the first. Null when the source has none */
    fun homeTab(): String? {
        val tabs = catalogTabs.ifEmpty { return null }
        return lastTab()?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
    }
}
