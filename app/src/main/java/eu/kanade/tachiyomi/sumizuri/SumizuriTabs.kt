package eu.kanade.tachiyomi.sumizuri

/** KMK: A source's own names for the Popular and Latest buttons; null keeps the app's name */
data class SumizuriTabNames(
    val popular: String? = null,
    val latest: String? = null,
)

/** KMK: One of a source's catalogs, shown as a button between Latest and Filter in Browse */
data class SumizuriCatalogTab(val id: String, val name: String)

/**
 * KMK: What a Sumizuri source adds to the buttons at the top of Browse: its names for Popular and Latest, and
 * its catalogs as buttons. A catalog button lists that catalog the way picking it in the filter sheet does.
 */
interface SumizuriTabs {
    val tabNames: SumizuriTabNames

    val catalogTabs: List<SumizuriCatalogTab>

    /** Picks catalog [id] in [filters], a filter list this source made. False when there is nothing to pick */
    fun selectCatalog(filters: Iterable<*>, id: String): Boolean

    /** The catalog picked in [filters], or null */
    fun selectedCatalog(filters: Iterable<*>): String?
}
