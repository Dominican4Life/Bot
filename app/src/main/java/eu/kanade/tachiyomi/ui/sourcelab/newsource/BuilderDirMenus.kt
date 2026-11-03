package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.runtime.Immutable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Collections
import java.util.IdentityHashMap

/**
 * KMK (r109, Source Lab): a directory page's **custom drop-downs**: a button reading "<Label>: <value>" ("Sort: Most
 * videos", "Height: Any height", "Cup: Any cup", "Age: Any age") that opens a list of entries the site's script shows.
 * The owner's r108 scan found none of them (it looked for `<select>`s and plain links only), so Browse offered only the
 * app's 2 local sorts. [find] reads them from the page's HTML (the list may be hidden; it may also be added only when
 * the button is pressed: then it has no items here). [resolve] turns the menus whose entries are links into codes
 * (the address parameter that changes, [codesOf]); the others stay pending: the builder presses them on the page
 * (learn.js `items` / `press`, [BuilderExtrasModel.learnDirMenus]) and reads the address each one makes.
 *
 * The site's current choice (the value after the colon, an entry marked active / selected, or the press that left the
 * address as it was) is the menu's default: it comes first with value "" ("Most videos"), never a "Site default" entry.
 */
internal object BuilderDirMenus {

    /** One entry of a custom drop-down: its text, its link (null = a button), its value attribute, marked current */
    @Immutable
    data class Item(val text: String, val href: String? = null, val value: String? = null, val current: Boolean = false)

    /**
     * A custom drop-down: its [label] ("Sort"), the [current] value its button shows ("Most videos"), the button's css
     * path for learn.js ([opener], "" = found by the label), its [items] (empty = added only when it opens) and whether
     * it orders the list ([sort])
     */
    @Immutable
    data class Menu(val label: String, val current: String, val opener: String, val items: List<Item>, val sort: Boolean)

    /** One learned / read entry: its text and the address it opened (null = the address stayed as it was) */
    data class Row(val text: String, val url: String?, val current: Boolean = false)

    /**
     * The codes of a menu: the address parameter ([param], "" with [pathMode] = each entry is a path of its own), the
     * options (the default first, value ""), the default's label ("" = none known) and why nothing came out ([note])
     */
    data class Coded(
        val param: String,
        val options: List<ExtrasOption>,
        val defaultLabel: String,
        val note: String,
        val pathMode: Boolean = false,
    ) {
        val ok: Boolean get() = options.count { it.value.isNotEmpty() } >= 1 && options.size >= 2 && (param.isNotEmpty() || pathMode)
    }

    /** What [resolve] made of a page's custom menus: the sort (if any), the other menus, and those left to press */
    data class Found(
        val sorts: List<ExtrasOption>,
        val sortParam: String,
        val menus: List<CollectionMenu>,
        val pending: List<Menu>,
        val claimed: Set<String>,
    )

    // No braces in these patterns (ICU)
    private val SPACES = Regex("""\s+""")
    private val ARROWS = Regex("""[\s▾▴▼▲⌄⌃∨∧˅˄›»⏷⏶▸▹►▶]+$""")
    private val LABEL_VALUE = Regex("""^(\p{L}[\p{L} ]*?)\s*:\s*(\S.*)$""")
    private val SORT_LABEL = Regex("""^(?:sort(?:ed)?(?:\s+by)?|order(?:\s+by)?|ordering|ordered\s+by)$""", RegexOption.IGNORE_CASE)
    private val SORT_TEXT = Regex(
        """\b(most|least|name|a\s*[-–]\s*z|z\s*[-–]\s*a|newest|oldest|latest|recent|recently|popular|popularity|trending|top|rated|rating|views?|viewed|videos?|followers?|likes?|liked|today|daily|weekly|monthly|yearly|alphabetical|random|updated|release|added)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CURRENT_CLASS = Regex("""(?:^|\s)(?:is-|o-)?(?:active|selected|current|checked|chosen)(?:\s|$)""", RegexOption.IGNORE_CASE)
    private val CLICK_ATTR = Regex(
        """^(?:@click|x-on:click|v-on:click|onclick|ng-click|wire:click|data-toggle|data-bs-toggle|data-action|data-dropdown|hx-get)""",
        RegexOption.IGNORE_CASE,
    )
    private val TOGGLE_CLASS = Regex("""dropdown|select|toggle|sort|filter|picker|combo|trigger""", RegexOption.IGNORE_CASE)
    private const val ENTRY = "a[href], button, li, [role=option], [role=menuitem], [role=menuitemradio], [role=radio], [data-value]"
    private val SKIP_TAGS = setOf("script", "style", "noscript", "select", "option", "optgroup", "svg", "path", "head", "title", "textarea")
    private val NOT_LABELS = setOf(
        "page", "pages", "showing", "total", "results", "views", "videos", "likes", "time", "date", "duration",
        "release date", "code", "http", "https", "updated", "added", "language", "lang",
    )
    private val PAGE_PARAMS = setOf("page", "p", "pg", "paged", "offset", "start", "from", "skip")
    private const val MAX_MENUS = 8
    private const val MAX_ITEMS = 60

    private fun identitySet(): MutableSet<Element> = Collections.newSetFromMap(IdentityHashMap())

    private fun norm(t: String?): String = t.orEmpty().replace(SPACES, " ").trim()

    /** An element's text as a menu button shows it (arrows at the end dropped), null when too long to be one */
    private fun buttonText(el: Element): String? {
        val t = norm(el.text()).replace(ARROWS, "").trim()
        return t.takeIf { it.isNotEmpty() && it.length <= 64 }
    }

    /** "Sort: Most videos" → ("Sort", "Most videos"); null when [text] doesn't read like a menu's button */
    fun labelValue(text: String?): Pair<String, String>? {
        val t = norm(text).replace(ARROWS, "").trim()
        val m = LABEL_VALUE.matchEntire(t) ?: return null
        val label = m.groupValues[1].trim()
        val value = m.groupValues[2].trim()
        if (label.length > 21 || value.length > 45) return null
        if (label.lowercase() in NOT_LABELS || value.startsWith("//")) return null
        return label to value
    }

    /** The element that is pressed for [el]: itself or one of 2 parents that looks clickable; null = none */
    private fun clickable(el: Element): Element? {
        var at: Element? = el
        for (level in 0..2) {
            val n = at ?: return null
            if (n.normalName() == "body" || n.normalName() == "html") return null
            val name = n.normalName()
            val role = n.attr("role").lowercase()
            if (name == "button" || name == "summary" || (name == "a" && n.hasAttr("href"))) return n
            if (role == "button" || role == "combobox" || role == "listbox") return n
            if (n.hasAttr("aria-haspopup") || n.hasAttr("aria-expanded") || n.hasAttr("tabindex")) return n
            if (n.attributes().any { CLICK_ATTR.containsMatchIn(it.key) }) return n
            if (TOGGLE_CLASS.containsMatchIn(n.className())) return n
            at = n.parent()
        }
        return null
    }

    /**
     * The custom drop-downs of a directory page [doc]: an element reading "<Label>: <value>" (the innermost one), pressed
     * through itself or a parent that looks clickable, outside the entries' [cards] and the site's nav / footer, with the
     * entries of the list it opens when the page already holds them (hidden ones too)
     */
    fun find(doc: Document, pageUrl: String, cards: Set<Element>): List<Menu> {
        val out = ArrayList<Menu>()
        val openers = identitySet()
        val body = doc.body() ?: return emptyList()
        for (el in body.allElements) {
            if (out.size >= MAX_MENUS) break
            if (el.normalName() in SKIP_TAGS) continue
            val text = buttonText(el) ?: continue
            val (label, current) = labelValue(text) ?: continue
            // the innermost element reading it: a box holding the button and its (hidden) list may read "Cup: Any cup Any
            // cup A B C" too; the element inside it that reads "Cup: …" is the button
            if (el.allElements.any { d -> d !== el && buttonText(d)?.let { labelValue(it)?.first?.equals(label, ignoreCase = true) } == true }) continue
            if (el in cards || el.parents().any { it in cards || it.normalName() == "nav" || it.normalName() == "footer" }) continue
            if (el.parents().any { it.normalName() in SKIP_TAGS }) continue
            val opener = clickable(el) ?: continue
            if (opener in openers || opener.parents().any { it in openers }) continue
            if (out.any { it.label.equals(label, ignoreCase = true) }) continue
            openers += opener
            val items = itemsOf(doc, opener, pageUrl, current, cards)
            val sort = SORT_LABEL.matches(label) ||
                (items.size >= 2 && items.count { SORT_TEXT.containsMatchIn(it.text) } * 5 >= items.size * 3)
            val css = runCatching { opener.cssSelector() }.getOrDefault("")
            out += Menu(label, current, css, items, sort)
        }
        return out
    }

    /** The entries inside [box] that aren't [opener], around it or inside it: the innermost ones */
    private fun entriesIn(box: Element, opener: Element): List<Element> {
        val around = identitySet().apply { addAll(opener.parents()) }
        val found = runCatching { box.select(ENTRY) }.getOrNull().orEmpty().filter { e ->
            e !== opener && e !in around && e.parents().none { it === opener }
        }.take(MAX_ITEMS * 3)
        val all = identitySet().apply { addAll(found) }
        return found.filter { e -> e.allElements.none { d -> d !== e && d in all } }
    }

    /**
     * The entries of the list [opener] opens: its aria-controls box, else the nearest box around it (4 levels) that
     * holds 2 or more entries and no other menu's button; none when the list isn't in the page or the box is the
     * cards' grid
     */
    private fun itemsOf(doc: Document, opener: Element, pageUrl: String, current: String, cards: Set<Element>): List<Item> {
        val controls = opener.attr("aria-controls").split(' ').map { it.trim() }.filter { it.isNotEmpty() }
        for (id in controls) {
            val box = doc.getElementById(id) ?: continue
            val found = entriesIn(box, opener)
            if (found.size >= 2) return found.mapNotNull { itemOf(it, pageUrl, current) }.take(MAX_ITEMS)
        }
        var node: Element = opener
        for (level in 0 until 4) {
            val up = node.parent() ?: break
            val name = up.normalName()
            if (name == "body" || name == "html" || name == "main") break
            val found = entriesIn(up, opener)
            if (found.size >= 2) {
                // the bar holding every drop-down, or the cards' grid: not this menu's list
                if (found.any { e -> labelValue(e.text()) != null }) break
                if (found.any { e -> e in cards || e.parents().any { it in cards } }) break
                if (found.size > MAX_ITEMS) break
                return found.mapNotNull { itemOf(it, pageUrl, current) }.distinctBy { it.text.lowercase() + "|" + it.href }
            }
            node = up
        }
        return emptyList()
    }

    private fun itemOf(e: Element, pageUrl: String, current: String): Item? {
        val text = norm(e.text()).ifEmpty { norm(e.attr("aria-label")) }.ifEmpty { norm(e.attr("title")) }
        if (text.isEmpty() || text.length > 60) return null
        val raw = e.attr("href").trim()
        val href = if (e.normalName() == "a" && raw.isNotEmpty() && !raw.startsWith("#") && !raw.startsWith("javascript:", ignoreCase = true)) {
            e.absUrl("href").ifEmpty { pageUrl.toHttpUrlOrNull()?.resolve(raw)?.toString().orEmpty() }.takeIf { it.startsWith("http") }
        } else {
            null
        }
        val value = e.attr("data-value").takeIf { e.hasAttr("data-value") } ?: e.attr("value").takeIf { e.hasAttr("value") }
        return Item(text, href, value, isCurrent(e, text, current))
    }

    /** Marked as the site's current choice (active / selected / aria-current…), or reading the button's value */
    private fun isCurrent(e: Element, text: String, current: String): Boolean {
        val boxes = listOfNotNull(e, e.parent()?.takeIf { it.normalName() == "li" })
        if (boxes.any { b ->
                CURRENT_CLASS.containsMatchIn(b.className()) ||
                    b.attr("aria-selected").equals("true", ignoreCase = true) ||
                    b.attr("aria-checked").equals("true", ignoreCase = true) ||
                    (b.hasAttr("aria-current") && !b.attr("aria-current").equals("false", ignoreCase = true))
            }
        ) {
            return true
        }
        return current.isNotEmpty() && text.equals(current, ignoreCase = true)
    }

    /**
     * The menus whose entries are links to this directory page become codes (sorts / menus); the others (buttons, or
     * no list in the page) are [Found.pending]: the builder presses them on the page
     */
    fun resolve(menus: List<Menu>, pageUrl: String): Found {
        var sorts = emptyList<ExtrasOption>()
        var sortParam = ""
        val out = ArrayList<CollectionMenu>()
        val pending = ArrayList<Menu>()
        val claimed = HashSet<String>()
        for (m in menus) {
            val links = m.items.count { it.href != null }
            if (m.items.size < 2 || links * 2 < m.items.size) {
                pending += m
                continue
            }
            val rows = m.items.filter { it.href != null || it.current }.map { Row(it.text, it.href ?: pageUrl, it.current) }
            val coded = codesOf(pageUrl, rows, m.current, m.sort)
            if (!coded.ok) {
                pending += m
                continue
            }
            if (m.sort && sorts.isEmpty()) {
                sorts = coded.options
                sortParam = coded.param
            } else if (coded.param.isNotEmpty() && coded.param !in claimed) {
                out += CollectionMenu(coded.param, m.label, coded.options)
            } else {
                continue
            }
            if (coded.param.isNotEmpty()) claimed += coded.param
        }
        return Found(sorts, sortParam, out, pending, claimed)
    }

    /**
     * The codes of a menu from where each entry goes ([rows], [base] = the directory page): the query parameter most
     * entries change (page numbers left out); when none and [sort], entries that open other paths are paths of their
     * own. The default (the entry that left the address as it was or has no code, else the one marked current or reading
     * [current]) comes first with value "" and its own label; options deduped by value
     */
    fun codesOf(base: String, rows: List<Row>, current: String, sort: Boolean): Coded {
        val b = base.toHttpUrlOrNull() ?: return Coded("", emptyList(), "", "the page has no address")
        val basePath = b.encodedPath.trimEnd('/')
        val parsed = rows.map { r -> r to r.url?.toHttpUrlOrNull()?.takeIf { it.host == b.host } }
        val counts = LinkedHashMap<String, Int>()
        val otherPaths = HashSet<String>()
        for ((r, u) in parsed) {
            if (r.url == null || u == null) continue
            if (u.encodedPath.trimEnd('/') != basePath) {
                otherPaths += u.encodedPath
                continue
            }
            u.queryParameterNames
                .filter { it.lowercase() !in PAGE_PARAMS && u.queryParameter(it) != b.queryParameter(it) }
                .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val param = counts.maxByOrNull { it.value }?.key.orEmpty()
        val pathMode = param.isEmpty() && sort && otherPaths.size >= 2
        if (param.isEmpty() && !pathMode) {
            return Coded("", emptyList(), "", "no entry changed an address parameter of ${b.encodedPath}")
        }
        val values = ArrayList<Pair<Row, String>>()
        for ((r, u) in parsed) {
            val v = when {
                r.url == null -> ""
                u == null -> null
                pathMode -> if (u.encodedPath.trimEnd('/') == basePath && u.query.isNullOrEmpty()) "" else u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "")
                u.encodedPath.trimEnd('/') != basePath -> null
                else -> u.queryParameter(param) ?: ""
            } ?: continue
            values += r to v
        }
        // the default: no code (or no change), else marked current, else the button's value
        var at = values.indexOfFirst { it.second.isEmpty() }
        // KMK (r109) fix: the marked / shown entry is the site's default only when the page's address has no value for
        // the parameter (on ?sort=views it is that pick)
        val pageHasValue = !pathMode && !b.queryParameter(param).isNullOrEmpty()
        if (at < 0 && !pageHasValue) at = values.indexOfFirst { it.first.current }
        if (at < 0 && !pageHasValue && current.isNotBlank()) at = values.indexOfFirst { it.first.text.equals(current, ignoreCase = true) }
        val defaultLabel = if (at >= 0) values[at].first.text else ""
        val ordered = ArrayList<ExtrasOption>()
        if (at >= 0) ordered += ExtrasOption(defaultLabel, "")
        values.forEachIndexed { i, (r, v) ->
            if (i == at || v.isEmpty() || r.text.isBlank()) return@forEachIndexed
            ordered += ExtrasOption(r.text, v)
        }
        val options = ordered.distinctBy { it.value }.take(MAX_ITEMS)
        return Coded(if (pathMode) "" else param, options, defaultLabel, "", pathMode)
    }

    /** The value a menu's button shows after its colon ("Sort: Most videos ▾" → "Most videos"), "" when none */
    fun currentOf(raw: String?): String = labelValue(raw)?.second.orEmpty()
}
