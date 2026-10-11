package eu.kanade.tachiyomi.sumizuri

/** KMK: A source's own names for the Popular and Latest buttons; null keeps the app's name */
data class SumizuriTabNames(
    val popular: String? = null,
    val latest: String? = null,
)

/** KMK: One of a source's catalogs marked `tab: true`, shown as a button before Filter in Browse */
data class SumizuriCatalogTab(val id: String, val name: String)

/**
 * KMK: What a Sumizuri source adds to the buttons at the top of Browse: its names for Popular and Latest, and
 * its `tab: true` catalogs as buttons. A catalog button lists that catalog the way picking it in the filter sheet
 * does. A source with tab catalogs shows them instead of Popular and Latest, and opens on the one used last.
 */
interface SumizuriTabs {
    val tabNames: SumizuriTabNames

    val catalogTabs: List<SumizuriCatalogTab>

    /** Picks catalog [id] in [filters], a filter list this source made. False when there is nothing to pick */
    fun selectCatalog(filters: Iterable<*>, id: String): Boolean

    /** The catalog picked in [filters], or null */
    fun selectedCatalog(filters: Iterable<*>): String?

    /** The tab catalog last opened in this source, if any */
    fun lastTab(): String?

    fun rememberTab(id: String)

    /** True when the source's tab catalogs replace Popular and Latest */
    val hasCatalogTabs: Boolean get() = catalogTabs.isNotEmpty()

    /** The tab catalog Browse opens on: the one used last, or the first. Null when the source has none */
    fun homeTab(): String? {
        val tabs = catalogTabs.ifEmpty { return null }
        return lastTab()?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
    }
}
