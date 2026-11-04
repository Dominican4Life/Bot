package eu.kanade.tachiyomi.ui.sourcelab.newsource

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/*
 * KMK (r116): the helper's suggestions are back (r115 cut them to one primary action). Every pick (Ok and Warn) gets
 * a suggestion card: first the page's own guess (picker.js `suggest`, [pageReason]), then "Suggest another" walks
 * through [of]'s candidates one at a time (each sent to the page as `suggestSelector`, which outlines it dashed green
 * and answers with its count and sample texts). Candidates come from the raw page the checklist already read
 * (BuilderRecheck.Pages), so the list costs no extra request in the usual case. Pure Jsoup work, no network here.
 */
internal object BuilderCandidates {

    /** One alternative for a label: a selector the page can outline, and why it might be the one */
    data class Candidate(val selector: String, val reason: String)

    private const val MAX = 8

    /** Why the page's own first guess (picker.js FINDERS) was offered, by label */
    fun pageReason(label: String): String = when (label) {
        "item" -> "the smallest repeated box near your tap with a picture and a link"
        "title" -> "the heading link inside the card around your tap"
        "cover" -> "the picture inside the card around your tap"
        "next" -> "a link that reads like Next near the page's numbers"
        "chapter" -> "the longest list of links like an episode list"
        "image" -> "the biggest pictures in the page's reading area"
        "video" -> "the player on the page (ad frames are skipped)"
        "bigCover" -> "the biggest picture near the title"
        "description" -> "the longest block of text near the title"
        else -> "what looks most like it near your tap"
    }

    /** List-page labels: their candidates are read inside the Item's cards when one is known */
    private val LIST_LABELS = setOf("title", "cover")

    /**
     * Up to [MAX] candidates for [label] on [doc] (the raw page the pick was made on), best first, none equal to the
     * pick itself ([pickSelector]: its look-alike selector) and none matching the same elements as it.
     */
    fun of(
        label: String,
        pickSelector: String,
        pickPath: String,
        itemSelector: String?,
        itemFromPicks: String?,
        doc: Document?,
    ): List<Candidate> {
        if (doc == null) return emptyList()
        val pickEls = sel(doc, pickSelector).ifEmpty { sel(doc, pickPath) }
        val pickSet = pickEls.toSet()
        val tapped = pickEls.firstOrNull()
        val item = itemSelector?.trim()?.takeIf { it.isNotBlank() && it != pickSelector && sel(doc, it).size >= 2 }
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        // the element sets already offered: two selectors for the same elements are one candidate
        val sets = ArrayList<Set<Element>>()
        if (pickSet.isNotEmpty()) sets.add(pickSet)

        fun add(selector: String, reason: (List<Element>) -> String?, min: Int = 1) {
            if (out.size >= MAX) return
            val css = selector.trim()
            if (css.isBlank() || css == pickSelector || !seen.add(css)) return
            val els = sel(doc, css)
            if (els.size < min) return
            // mostly the kind of thing the label is (a link for Title, a picture for Cover…)
            if (els.count { fits(label, it) } * 2 < els.size) return
            val set = els.toSet()
            if (sets.any { it == set }) return
            val why = reason(els) ?: return
            sets.add(set)
            out.add(Candidate(css, why))
        }

        // Inside each card: what the label usually is there (on N of M cards)
        fun inCards(subs: List<Pair<String, String>>, ok: (Element) -> Boolean) {
            val cards = item?.let { sel(doc, it) }.orEmpty()
            if (cards.isEmpty()) return
            subs.forEach { (sub, what) ->
                val hits = cards.count { card -> sel(card, sub).any(ok) }
                if (hits * 5 >= cards.size * 3) add("$item $sub", { "$what in each card · on $hits of ${cards.size} cards" })
            }
        }

        // Anywhere on the page: what the label usually is ([min] matches at least)
        fun onPage(subs: List<Pair<String, String>>, min: Int = 1, ok: (Element) -> Boolean = { true }) {
            subs.forEach { (sub, what) ->
                add(sub, { els -> els.count(ok).takeIf { it >= min }?.let { n -> "$what · $n on this page" } }, min)
            }
        }

        when (label) {
            "item" -> {
                if (!itemFromPicks.isNullOrBlank()) add(itemFromPicks, { els -> "the box that holds your Title and Cover picks · ${els.size} on this page" }, 2)
                // the repeated boxes around the tap (bigger) and inside it (smaller)
                var node = tapped?.parent()
                var level = 0
                while (node != null && level < 6 && node.normalName() !in STOP) {
                    val css = simple(node)
                    if (css != null) add(css, { els -> "a bigger box around your tap · repeats ${els.size}×" }, 3)
                    node = node.parent()
                    level++
                }
                tapped?.children()?.forEach { child ->
                    simple(child)?.let { css -> add(css, { els -> "a smaller box inside your tap · repeats ${els.size}×" }, 3) }
                }
            }
            "title" -> inCards(
                listOf(
                    "h1 a[href]" to "the heading link",
                    "h2 a[href]" to "the heading link",
                    "h3 a[href]" to "the heading link",
                    "h4 a[href]" to "the heading link",
                    "[class*=title] a[href]" to "the link in the part named title",
                    "a[href][title]" to "the link with a title",
                    "a[href]" to "the first link",
                ),
            ) { it.text().trim().length >= 2 }
            "cover" -> inCards(
                listOf(
                    "img[data-src]" to "the picture with a data-src",
                    "img[data-original]" to "the picture with a data-original",
                    "img[data-lazy-src]" to "the lazy picture",
                    "picture img" to "the picture",
                    "img" to "the first picture",
                ),
            ) { true }
            "next" -> onPage(
                listOf(
                    "a[rel=next]" to "the link the page marks as next",
                    "a.next" to "the link named next",
                    ".next a" to "the link in the part named next",
                    "a[aria-label*=Next]" to "the link labelled Next",
                    "[class*=pagination] a:last-child" to "the pager's last link",
                ),
            ) { it.hasAttr("href") }
            "chapter" -> repeatedLinks(doc, pickSet).forEach { (css, n) -> add(css, { "a list of $n links" }, 2) }
            "bigCover" -> onPage(
                listOf(
                    "[class*=cover] img" to "the picture in the part named cover",
                    "[class*=poster] img" to "the picture in the part named poster",
                    "[class*=thumb] img" to "the picture in the part named thumb",
                    "article img" to "the first picture of the article",
                    "main img" to "the first picture of the main part",
                ),
            )
            "description" -> onPage(
                listOf(
                    "[itemprop=description]" to "the text the page marks as description",
                    "[class*=desc]" to "the text named desc",
                    "[class*=synopsis]" to "the text named synopsis",
                    "[class*=summary]" to "the text named summary",
                    "article p" to "the article's paragraphs",
                ),
            ) { it.text().trim().length >= 40 }
            "author" -> onPage(
                listOf(
                    "[itemprop=author]" to "what the page marks as author",
                    "[class*=author] a" to "the link in the part named author",
                    "[class*=author]" to "the part named author",
                    "a[href*=author]" to "a link to an author page",
                    "a[href*=artist]" to "a link to an artist page",
                ),
            ) { it.text().isNotBlank() }
            "status" -> onPage(listOf("[class*=status]" to "the part named status"), ok = { it.text().isNotBlank() })
            "date" -> onPage(
                listOf(
                    "time" to "the page's time marks",
                    "[datetime]" to "the parts with a datetime",
                    "[class*=date]" to "the parts named date",
                    "[class*=time]" to "the parts named time",
                ),
            ) { it.text().isNotBlank() || it.hasAttr("datetime") }
            "number" -> onPage(
                listOf(
                    "[class*=episode]" to "the parts named episode",
                    "[class*=chapter]" to "the parts named chapter",
                    "[class*=num]" to "the parts named num",
                ),
            ) { it.text().any(Char::isDigit) }
            "image" -> onPage(
                listOf(
                    "[class*=reader] img" to "the pictures of the reading area",
                    "[class*=page] img" to "the pictures named page",
                    "img[data-src]" to "the pictures with a data-src",
                    "#content img" to "the pictures in the content",
                    "img" to "every picture",
                ),
                min = 2,
            )
            "video" -> onPage(
                listOf(
                    "video" to "the page's own player",
                    "video source" to "the player's source",
                    "[id*=player] iframe" to "the frame in the part named player",
                    "[class*=player] iframe" to "the frame in the part named player",
                    "iframe[src]" to "a frame",
                ),
            )
        }
        // Near the tap, for every label: the box around it, inside it, next to it
        if (tapped != null) {
            val scope = if (label in LIST_LABELS && item != null) "$item " else ""
            tapped.parent()?.takeIf { it.normalName() !in STOP }?.let { p -> simple(p)?.let { add(scope + it, { els -> "the box around your tap · ${els.size} on this page" }) } }
            tapped.children().firstOrNull()?.let { c -> simple(c)?.let { add(scope + it, { els -> "the part inside your tap · ${els.size} on this page" }) } }
            tapped.nextElementSibling()?.let { n -> simple(n)?.let { add(scope + it, { els -> "the part next to your tap · ${els.size} on this page" }) } }
        }
        return out
    }

    private val STOP = setOf("html", "body", "head", "main")

    /** Tags that say enough without a class */
    private val PLAIN_OK = setOf("h1", "h2", "h3", "h4", "h5", "h6", "img", "picture", "time", "video", "iframe", "article", "figure", "li", "p")

    /** [el] is the kind of thing [label] reads */
    private fun fits(label: String, el: Element): Boolean = when (label) {
        "title", "chapter", "next" -> el.normalName() == "a" || el.selectFirst("a[href]") != null
        "cover", "bigCover", "image" -> el.normalName() == "img" || el.selectFirst("img") != null
        "video" -> el.normalName() in setOf("video", "iframe", "source") || el.selectFirst("video, iframe") != null
        "item" -> el.selectFirst("a[href]") != null || el.parent()?.normalName() == "a"
        else -> el.text().isNotBlank() || el.hasAttr("datetime")
    }

    /** A safe class token (no braces, no escapes needed) */
    private val CLASS_OK = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,40}$")

    /** Classes that only say how it looks right now */
    private val STATE_CLASS = Regex("^(active|selected|current|hover|open|show|hidden|on|off|is-.*|js-.*)$", RegexOption.IGNORE_CASE)

    /** tag + up to 2 classes, or null for an element with nothing to tell it apart */
    private fun simple(el: Element): String? {
        val tag = el.normalName()
        if (tag.isBlank() || tag.startsWith("#") || ':' in tag) return null
        val classes = el.classNames().filter { CLASS_OK.matches(it) && !STATE_CLASS.matches(it) }.take(2)
        if (classes.isEmpty() && tag !in PLAIN_OK) return null
        return tag + classes.joinToString("") { ".$it" }
    }

    /** Repeated links grouped by their row's look (`li.ep > a`), biggest groups first, never the pick's own set */
    private fun repeatedLinks(doc: Document, pickSet: Set<Element>): List<Pair<String, Int>> {
        val groups = LinkedHashMap<String, Int>()
        doc.select("a[href]").take(3000).forEach { a ->
            val row = a.parent() ?: return@forEach
            val rowCss = simple(row) ?: return@forEach
            val css = "$rowCss > a"
            groups[css] = (groups[css] ?: 0) + 1
        }
        return groups.entries
            .filter { it.value >= 3 }
            .sortedByDescending { it.value }
            .map { it.key to it.value }
            .filter { (css, _) -> pickSet.isEmpty() || sel(doc, css).toSet() != pickSet }
            .take(3)
    }

    private fun sel(root: Element, selector: String): List<Element> {
        if (selector.isBlank()) return emptyList()
        return runCatching { root.select(selector).toList() }.getOrDefault(emptyList())
    }
}
