package eu.kanade.tachiyomi.jssource.lab.heal

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * KMK (r116): self-healing picks. When a site renames its classes or wraps its cards differently, the selectors the
 * builder wrote stop finding anything. [heal] looks for the element that resembles the saved [Fingerprint] most and
 * writes the shortest stable selector that finds the same set again. It never changes code by itself:
 * [applyToCode] is only called when the owner taps "Use the new picks".
 *
 * Scoring (0–100, the Lab's own weights):
 * - tag 20 (same tag; headings, pictures and plain boxes count partly as each other)
 * - class tokens 18 (class names, and the words inside them: "card-title" ~ "item-title")
 * - attribute names 10
 * - text shape 12 (length, word count, share of digits, never the words themselves)
 * - position 12 (the tag path from the item card down to it; outside a card, the ancestor tags)
 * - parent and siblings 10 (parent tag and classes, previous / next sibling tags)
 * - children and link 18 (child tags, the tag paths two levels down, the first path segment of its link)
 *
 * The selector: candidates built from what every found element shares (classes, attributes, parent), checked on
 * the page itself; the cheapest one that finds the same set wins (class / attribute forms cost less than tag-only
 * forms, `:nth-of-type` is the last resort). Below [MIN_SCORE] nothing is offered.
 */
object PickHealer {

    const val MIN_SCORE = 70

    /** The builder's pick label → the generic-list code's `var NAME = "…"` (BuilderScreenModel.CODE_VARS / LABEL_KEYS) */
    val CODE_VARS: Map<String, String> = linkedMapOf(
        "item" to "ITEM",
        "title" to "TITLE",
        "cover" to "COVER",
        "next" to "NEXT",
        "chapter" to "CHAPTER",
        "date" to "CHAPTER_DATE",
        "number" to "EPISODE_NUMBER",
        "image" to "IMAGE",
        "video" to "VIDEO_FRAME",
        "bigCover" to "DETAILS_COVER",
        "description" to "DESCRIPTION",
        "tags" to "TAGS",
        "author" to "AUTHOR",
        "status" to "STATUS",
    )

    /** Labels the code reads inside each ITEM card (their selectors are relative to the card) */
    val CARD_LABELS: Set<String> = setOf("title", "cover")

    /** Labels the code reads with one selectFirst on the page (one element is the whole set) */
    private val SINGLE_LABELS = setOf("next", "bigCover", "description", "author", "status", "video")

    // ---------- fingerprint ----------

    /** The fingerprint of [el]; its item card is guessed (the ancestor-or-self that repeats most among its siblings) */
    fun fingerprint(el: Element): Fingerprint = fingerprint(el, findCard(el))

    /** The fingerprint of [el] inside the known item [card] (null: no card) */
    fun fingerprint(el: Element, card: Element?): Fingerprint {
        val text = shortText(el)
        val parent = el.parent()
        return Fingerprint(
            tag = el.normalName(),
            classes = el.classNames().filter { it.isNotBlank() }.take(16),
            attrs = attrNames(el),
            textLength = text.length,
            words = wordCount(text),
            digitShare = digitShare(text),
            path = card?.let { pathFrom(it, el) },
            ancestors = ancestorTags(el),
            parentTag = parent?.normalName(),
            parentClasses = parent?.classNames()?.filter { it.isNotBlank() }?.take(16).orEmpty(),
            prevTag = el.previousElementSibling()?.normalName(),
            nextTag = el.nextElementSibling()?.normalName(),
            children = el.children().take(12).map { it.normalName() },
            shape = shapeOf(el),
            linkPrefix = linkPrefix(el),
            sample = text.take(80),
            savedAt = System.currentTimeMillis(),
        )
    }

    /** The ancestor-or-self of [el] (up to 10 levels) with the most look-alike siblings (at least 2 in all), else null */
    private fun findCard(el: Element): Element? {
        var best: Element? = null
        var bestCount = 1
        var node: Element? = el
        var level = 0
        while (node != null && level < 10) {
            val name = node.normalName()
            if (name == "body" || name == "html") break
            val parent = node.parent() ?: break
            val mine = node
            val count = parent.children().count { looksAlike(it, mine) }
            if (count > bestCount) {
                best = node
                bestCount = count
            }
            node = parent
            level++
        }
        return best
    }

    private fun looksAlike(a: Element, b: Element): Boolean {
        if (a.normalName() != b.normalName()) return false
        val ca = a.classNames()
        val cb = b.classNames()
        return (ca.isEmpty() && cb.isEmpty()) || ca.any { it in cb }
    }

    /** Tags from [card] (left out) down to [el]; empty when they are the same; null when [el] isn't inside [card] */
    private fun pathFrom(card: Element, el: Element): List<String>? {
        if (card === el) return emptyList()
        val out = ArrayList<String>()
        var node: Element? = el
        while (node != null && node !== card) {
            out.add(node.normalName())
            node = node.parent()
        }
        return if (node === card) out.reversed() else null
    }

    /** The tag paths below [el], two levels deep ("a", "a/img", "h3"), at most 24 */
    private fun shapeOf(el: Element): List<String> {
        val out = LinkedHashSet<String>()
        for (child in el.children()) {
            if (out.size >= 24) break
            val c = child.normalName()
            out.add(c)
            for (grand in child.children()) {
                if (out.size >= 24) break
                out.add(c + "/" + grand.normalName())
            }
        }
        return out.toList()
    }

    private fun ancestorTags(el: Element): List<String> {
        val out = ArrayList<String>()
        var node = el.parent()
        while (node != null && out.size < 4) {
            if (node.normalName() == "#root") break
            out.add(node.normalName())
            node = node.parent()
        }
        return out
    }

    private val SKIP_ATTRS = setOf("class", "id", "style")

    private fun attrNames(el: Element): List<String> =
        el.attributes().asList().map { it.key.lowercase() }.filter { it !in SKIP_ATTRS && it.isNotBlank() }.distinct().sorted().take(16)

    private val LINK_ATTRS = listOf("href", "src", "data-src", "data-lazy-src", "data-original")

    private fun linkPrefix(el: Element): String? {
        val own = LINK_ATTRS.firstNotNullOfOrNull { a -> el.attr(a).trim().takeIf { it.isNotEmpty() } }
        val value = own ?: el.selectFirst("a[href]")?.attr("href")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.startsWith("#") || value.startsWith("javascript:", ignoreCase = true) || value.startsWith("data:")) return null
        val path = when {
            value.startsWith("//") -> value.removePrefix("//").substringAfter('/', "")
            "://" in value -> value.substringAfter("://").substringAfter('/', "")
            else -> value.removePrefix("./").removePrefix("/")
        }
        return path.substringBefore('?').substringBefore('#').substringBefore('/').lowercase()
    }

    private val NO_TEXT_TAGS = setOf("script", "style", "template", "noscript", "svg")

    /** The element's text with whitespace collapsed, at most [cap] characters (cheap on huge elements) */
    private fun shortText(el: Element, cap: Int = 2000): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            if (sb.length >= cap) return
            when (n) {
                is TextNode -> {
                    val t = n.wholeText
                    for (ch in t) {
                        if (sb.length >= cap) return
                        if (ch.isWhitespace()) {
                            if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
                        } else {
                            sb.append(ch)
                        }
                    }
                }
                is Element -> if (n.normalName() !in NO_TEXT_TAGS) {
                    // Element boundaries count as spaces ("Title" + "Chapter 10" → "Title Chapter 10")
                    if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
                    for (child in n.childNodes()) {
                        if (sb.length >= cap) return
                        walk(child)
                    }
                    if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
                }
                else -> Unit
            }
        }
        walk(el)
        return sb.toString().trim()
    }

    private fun wordCount(text: String): Int = text.split(' ').count { w -> w.any { it.isLetterOrDigit() } }

    private fun digitShare(text: String): Int =
        if (text.isEmpty()) 0 else (text.count { it.isDigit() } * 100 / text.length)

    // ---------- scoring ----------

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val PICTURES = setOf("img", "picture", "source", "image")
    private val BOXES = setOf("div", "span", "p", "section", "article", "li", "figure", "header", "footer")

    private fun tagSim(a: String, b: String): Double = when {
        a == b -> 1.0
        a in HEADINGS && b in HEADINGS -> 0.7
        a in PICTURES && b in PICTURES -> 0.5
        a in BOXES && b in BOXES -> 0.35
        else -> 0.0
    }

    private fun <T> setSim(a: Collection<T>, b: Collection<T>, oneEmpty: Double): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return oneEmpty
        val sa = a.toSet()
        val sb = b.toSet()
        val inter = sa.count { it in sb }
        return inter.toDouble() / (sa.size + sb.size - inter)
    }

    private val CAMEL = Regex("([a-z])([A-Z])")
    private val WORD_SPLIT = Regex("[-_]+")

    /** "cardTitle__main" → card, title, main */
    private fun classWords(classes: List<String>): Set<String> = classes.flatMap { c ->
        CAMEL.replace(c, "$1-$2").split(WORD_SPLIT).map { it.lowercase() }.filter { w -> w.length >= 2 && !w.all { it.isDigit() } }
    }.toSet()

    /** 1 when equal, 0 when one is 8 times the other (or more) */
    private fun ratioSim(a: Int, b: Int): Double {
        val r = abs(ln((a + 1.0) / (b + 1.0))) / ln(8.0)
        return (1.0 - r).coerceIn(0.0, 1.0)
    }

    private fun textSim(fp: Fingerprint, c: Fingerprint): Double {
        if (fp.textLength == 0 && c.textLength == 0) return 1.0
        if (fp.textLength == 0 || c.textLength == 0) return 0.2
        val digits = (1.0 - abs(fp.digitShare - c.digitShare) / 50.0).coerceIn(0.0, 1.0)
        return 0.5 * ratioSim(fp.textLength, c.textLength) + 0.25 * ratioSim(fp.words, c.words) + 0.25 * digits
    }

    private fun positionSim(fp: Fingerprint, c: Fingerprint, inCard: Boolean): Double {
        val a = fp.path
        val b = c.path
        if (inCard && a != null && b != null) {
            var k = 0
            while (k < a.size && k < b.size && a[a.size - 1 - k] == b[b.size - 1 - k]) k++
            val same = if (a.size == b.size) 1 else 0
            return (k + same).toDouble() / (maxOf(a.size, b.size) + 1)
        }
        val x = fp.ancestors
        val y = c.ancestors
        if (x.isEmpty() && y.isEmpty()) return 1.0
        val n = maxOf(x.size, y.size)
        var same = 0
        for (i in 0 until minOf(x.size, y.size)) if (x[i] == y[i]) same++
        return same.toDouble() / n
    }

    private fun parentSim(fp: Fingerprint, c: Fingerprint): Double =
        0.3 * (if (fp.parentTag == c.parentTag) 1.0 else 0.0) +
            0.3 * setSim(fp.parentClasses, c.parentClasses, oneEmpty = 0.3) +
            0.2 * (if (fp.prevTag == c.prevTag) 1.0 else 0.0) +
            0.2 * (if (fp.nextTag == c.nextTag) 1.0 else 0.0)

    private fun childSim(fp: Fingerprint, c: Fingerprint): Double =
        0.3 * setSim(fp.children, c.children, oneEmpty = 0.2) +
            0.4 * setSim(fp.shape, c.shape, oneEmpty = 0.2) +
            0.3 * (if (fp.linkPrefix == c.linkPrefix) 1.0 else 0.0)

    /** How much [c] looks like [fp], 0–100 */
    internal fun similarity(fp: Fingerprint, c: Fingerprint, inCard: Boolean): Int {
        val classes = 0.6 * setSim(fp.classes, c.classes, oneEmpty = 0.25) +
            0.4 * setSim(classWords(fp.classes), classWords(c.classes), oneEmpty = 0.25)
        val total = 20 * tagSim(fp.tag, c.tag) +
            18 * classes +
            10 * setSim(fp.attrs, c.attrs, oneEmpty = 0.3) +
            12 * textSim(fp, c) +
            12 * positionSim(fp, c, inCard) +
            10 * parentSim(fp, c) +
            18 * childSim(fp, c)
        return total.roundToInt().coerceIn(0, 100)
    }

    // ---------- healing ----------

    private val NEVER = setOf("html", "head", "body", "script", "style", "meta", "link", "title", "template", "#root", "br", "hr")

    private class Found(val el: Element, val score: Int, val card: Element?)

    /**
     * Finds [label]'s element on [doc] again from [fp]. With [itemSelector] (the cards' selector) only elements inside
     * each card are candidates, one per card, and the new selector is relative to the card (as the code's TITLE /
     * COVER are); without it the whole page is searched and the selector is for the page. Null when nothing scores
     * [MIN_SCORE] or more, when no selector finds the same set, or when the selector found is [oldSelector] itself.
     */
    fun heal(doc: Document, label: String, oldSelector: String, fp: Fingerprint, itemSelector: String?): HealedPick? {
        val cards = itemSelector?.takeIf { it.isNotBlank() }?.let { sel -> runCatching { doc.select(sel).toList() }.getOrNull() ?: return null }
        return if (cards != null) healInCards(label, oldSelector, fp, cards) else healOnPage(doc, label, oldSelector, fp)
    }

    private fun healInCards(label: String, oldSelector: String, fp: Fingerprint, cards: List<Element>): HealedPick? {
        if (cards.isEmpty()) return null
        val found = ArrayList<Found>()
        for (card in cards) {
            var best: Found? = null
            for (el in card.allElements) {
                if (el.normalName() in NEVER) continue
                val score = similarity(fp, fingerprint(el, card), inCard = true)
                if (best == null || score > best.score) best = Found(el, score, card)
            }
            if (best != null && best.score >= MIN_SCORE - 10) found.add(best)
        }
        // Found in fewer than half the cards: not the same thing
        if (found.isEmpty() || found.size * 2 < cards.size) return null
        val chosen = IdentityHashMap<Element, Element>()   // card → its element
        found.forEach { f -> f.card?.let { chosen[it] = f.el } }
        val avg = found.sumOf { it.score }.toDouble() / found.size
        /** precision / recall of [sel] against the chosen elements, null when it finds nothing (or isn't valid) */
        fun rate(sel: String): Pair<Double, Double>? {
            var hits = 0
            var correct = 0
            for (card in cards) {
                val first = runCatching { card.selectFirst(sel) }.getOrNull() ?: continue
                hits++
                if (chosen[card] === first) correct++
            }
            if (hits == 0) return null
            return correct.toDouble() / hits to correct.toDouble() / found.size
        }
        // The code's selector still finds them: nothing moved
        if (stillWorks(oldSelector, ::rate)) return null
        var best: Pair<String, Double>? = null
        var bestCost = Double.MAX_VALUE
        for (sel in candidates(found.map { it.el }, relative = true)) {
            val (precision, recall) = rate(sel) ?: continue
            val c = cost(sel, precision, recall, relative = true) ?: continue
            if (c < bestCost) {
                bestCost = c
                best = sel to f1(precision, recall)
            }
        }
        val (sel, f1) = best ?: return null
        if (sel == oldSelector.trim()) return null
        val score = (avg * (0.5 + 0.5 * f1)).roundToInt().coerceIn(0, 100)
        if (score < MIN_SCORE) return null
        val firsts = cards.mapNotNull { card -> runCatching { card.selectFirst(sel) }.getOrNull() }
        return HealedPick(
            label = label,
            oldSelector = oldSelector,
            newSelector = sel,
            score = score,
            matches = firsts.size,
            total = cards.size,
            samples = firsts.asSequence().map { sampleOf(it, label) }.filter { it.isNotBlank() }.take(3).toList(),
        )
    }

    private fun healOnPage(doc: Document, label: String, oldSelector: String, fp: Fingerprint): HealedPick? {
        val scored = ArrayList<Found>()
        for (el in doc.allElements) {
            if (el.normalName() in NEVER || el.parents().any { it.normalName() == "head" }) continue
            val score = similarity(fp, fingerprint(el, null), inCard = false)
            if (score >= MIN_SCORE - 10) scored.add(Found(el, score, null))
        }
        if (scored.isEmpty()) return null
        val top = scored.maxOf { it.score }
        if (top < MIN_SCORE) return null
        val single = label in SINGLE_LABELS
        val outerWins = label == "item"
        // The set: everything close to the best score, one element per nested chain (the higher score; on a tie the
        // card itself for items, the inner element otherwise)
        val near = scored.filter { it.score >= maxOf(MIN_SCORE, top - 10) }
            .sortedWith(compareByDescending<Found> { it.score }.thenBy { if (outerWins) depth(it.el) else -depth(it.el) })
        val set = ArrayList<Found>()
        for (f in near) {
            if (set.any { s -> isAncestor(s.el, f.el) || isAncestor(f.el, s.el) }) continue
            set.add(f)
            if (single) break
        }
        if (label == "item" && set.size < 2) return null
        val members = IdentityHashMap<Element, Boolean>()
        set.forEach { members[it.el] = true }
        val avg = set.sumOf { it.score }.toDouble() / set.size
        var lastCount = 0
        fun rate(sel: String): Pair<Double, Double>? {
            val matched = runCatching { doc.select(sel).toList() }.getOrNull() ?: return null
            if (matched.isEmpty()) return null
            lastCount = matched.size
            if (single) {
                val ok = if (members.containsKey(matched.first())) 1.0 else 0.0
                return ok to ok
            }
            val inter = matched.count { members.containsKey(it) }
            return inter.toDouble() / matched.size to inter.toDouble() / set.size
        }
        if (stillWorks(oldSelector, ::rate)) return null
        var best: Pair<String, Double>? = null
        var bestCost = Double.MAX_VALUE
        for (sel in candidates(set.map { it.el }, relative = false)) {
            val (precision, recall) = rate(sel) ?: continue
            val c = cost(sel, precision, recall, relative = false)?.let { if (single) it + (lastCount - 1).coerceAtMost(20) else it } ?: continue
            if (c < bestCost) {
                bestCost = c
                best = sel to f1(precision, recall)
            }
        }
        val (sel, f1) = best ?: return null
        if (sel == oldSelector.trim()) return null
        val score = (avg * (0.5 + 0.5 * f1)).roundToInt().coerceIn(0, 100)
        if (score < MIN_SCORE) return null
        val matched = doc.select(sel)
        return HealedPick(
            label = label,
            oldSelector = oldSelector,
            newSelector = sel,
            score = score,
            matches = if (single) 1 else matched.size,
            total = set.size,
            samples = matched.asSequence().map { sampleOf(it, label) }.filter { it.isNotBlank() }.take(if (single) 1 else 3).toList(),
        )
    }

    private fun depth(el: Element): Int {
        var d = 0
        var n = el.parent()
        while (n != null) {
            d++
            n = n.parent()
        }
        return d
    }

    private fun isAncestor(a: Element, b: Element): Boolean {
        var n = b.parent()
        while (n != null) {
            if (n === a) return true
            n = n.parent()
        }
        return false
    }

    /** [old] (the code's selector) still finds the same set: precision and recall both ≥ 0.9 */
    private fun stillWorks(old: String, rate: (String) -> Pair<Double, Double>?): Boolean {
        if (old.isBlank()) return false
        val (precision, recall) = rate(old.trim()) ?: return false
        return precision >= 0.9 && recall >= 0.9
    }

    private fun f1(p: Double, r: Double): Double = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)

    /**
     * What a selector costs: its length plus penalties (no class / attribute / id: +8 inside a card, +16 on the page;
     * starting with a bare attribute +8; each combinator +3; `:nth-of-type` +40). Only
     * selectors that find (nearly) the same set count: both precision and recall ≥ 0.9, or ≥ 0.75 with the miss
     * added to the cost. Null = not good enough.
     */
    private fun cost(sel: String, precision: Double, recall: Double, relative: Boolean): Double? {
        val base = sel.length.toDouble() +
            (if (sel.none { it == '.' || it == '[' || it == '#' }) (if (relative) 8 else 16) else 0) +
            (if (sel.startsWith("[")) 8 else 0) +
            3 * sel.count { it == ' ' } +
            (if (":nth-of-type" in sel) 40 else 0)
        return when {
            precision >= 0.9 && recall >= 0.9 -> base + (2 - precision - recall) * 20
            precision >= 0.75 && recall >= 0.75 -> base + 60 + (1 - f1(precision, recall)) * 100
            else -> null
        }
    }

    // ---------- selector candidates ----------

    private val NAME_OK = Regex("^-?[A-Za-z_][A-Za-z0-9_-]*$")
    private val GENERATED_PREFIX = Regex("^(css|sc|jsx|svelte|emotion|chakra|styled|tw)-", RegexOption.IGNORE_CASE)
    private val STATE_CLASSES = setOf(
        "active", "selected", "current", "show", "shown", "hidden", "hide", "open", "hover", "focus", "visible", "lazy",
        "lazyload", "lazyloaded", "lazyloading", "loaded", "loading", "fade", "in", "odd", "even", "first", "last",
    )

    /** Letter↔digit switches: "a1b2c3" has 5, "col-12" one */
    private fun switches(s: String): Int {
        var n = 0
        var prev = 0
        for (ch in s) {
            val kind = when {
                ch.isDigit() -> 1
                ch.isLetter() -> 2
                else -> 0
            }
            if (kind != 0 && prev != 0 && kind != prev) n++
            if (kind != 0) prev = kind
        }
        return n
    }

    /** A class name that is written by hand and stays (no build hash, no state like "active", no CSS escapes needed) */
    internal fun stableClass(c: String): Boolean =
        NAME_OK.matches(c) && !GENERATED_PREFIX.containsMatchIn(c) && c.lowercase() !in STATE_CLASSES &&
            switches(c) < 3 && c.count { it.isDigit() } < 4

    private fun stableId(id: String): Boolean = id.isNotBlank() && NAME_OK.matches(id) && switches(id) < 3 && id.count { it.isDigit() } < 3

    /** Attributes every such element has anyway: never what identifies it */
    private val PLAIN_ATTRS = setOf(
        "src", "href", "alt", "title", "width", "height", "loading", "decoding", "srcset", "sizes", "target", "rel", "type",
        "tabindex", "draggable",
    )

    private fun stableAttr(a: String): Boolean = NAME_OK.matches(a) && a !in SKIP_ATTRS && a !in PLAIN_ATTRS

    /** What [els] all have in common, in the first element's order */
    private fun <T> common(els: List<Element>, of: (Element) -> List<T>): List<T> {
        val first = of(els.first())
        return first.filter { v -> els.all { v in of(it) } }.distinct()
    }

    private fun stableClasses(el: Element): List<String> = el.classNames().filter(::stableClass)

    /** Candidate selectors for the set [els] (relative = inside each card) */
    private fun candidates(els: List<Element>, relative: Boolean): List<String> {
        if (els.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val tag = els.first().normalName().takeIf { t -> NAME_OK.matches(t) && els.all { it.normalName() == t } }
        val classes = common(els, ::stableClasses).take(6)
        val attrs = common(els) { attrNames(it).filter(::stableAttr) }.take(6)
        val t = tag.orEmpty()
        classes.forEach { c ->
            out.add(".$c")
            if (tag != null) out.add("$t.$c")
        }
        for (i in classes.indices) {
            for (j in i + 1 until classes.size) {
                out.add(".${classes[i]}.${classes[j]}")
                if (tag != null) out.add("$t.${classes[i]}.${classes[j]}")
            }
        }
        if (tag != null) out.add(t)
        attrs.forEach { a ->
            if (tag != null) out.add("$t[$a]")
            out.add("[$a]")
            classes.firstOrNull()?.let { c -> out.add("$t.$c[$a]") }
        }
        // With the parent: `.pc > img`, `div.pc > .c`, `.pc img`
        val parents = els.mapNotNull { it.parent() }
        if (parents.size == els.size) {
            val pTag = parents.first().normalName().takeIf { p -> NAME_OK.matches(p) && parents.all { it.normalName() == p } }
            val pClasses = common(parents, ::stableClasses).take(3)
            val parentForms = buildList {
                pClasses.forEach { pc ->
                    add(".$pc")
                    if (pTag != null) add("$pTag.$pc")
                }
                if (pTag != null && pTag !in NEVER) add(pTag)
            }
            val selfForms = buildList {
                if (tag != null) add(t)
                classes.take(2).forEach { c -> add(".$c") }
                if (tag != null) classes.firstOrNull()?.let { c -> add("$t.$c") }
            }
            for (p in parentForms) {
                for (s in selfForms) {
                    out.add("$p > $s")
                    out.add("$p $s")
                }
            }
            // Last resort: the same position among its parent's children of that tag
            if (tag != null) {
                val index = els.first().elementSiblingIndexOfType()
                if (els.all { it.elementSiblingIndexOfType() == index }) {
                    parentForms.forEach { p -> out.add("$p > $t:nth-of-type(${index + 1})") }
                    if (relative) out.add("$t:nth-of-type(${index + 1})")
                }
            }
        }
        // On the page: under the nearest ancestor every element shares that has a stable id or class
        if (!relative) {
            anchor(els)?.let { a ->
                if (tag != null) out.add("$a $t")
                classes.take(2).forEach { c ->
                    out.add("$a .$c")
                    if (tag != null) out.add("$a $t.$c")
                }
            }
        }
        return out.toList()
    }

    private fun Element.elementSiblingIndexOfType(): Int {
        val name = normalName()
        var i = 0
        var n = previousElementSibling()
        while (n != null) {
            if (n.normalName() == name) i++
            n = n.previousElementSibling()
        }
        return i
    }

    /** `#id` or `.class` of the nearest ancestor shared by all of [els] (up to 6 levels above the first one) */
    private fun anchor(els: List<Element>): String? {
        var node = els.first().parent()
        var level = 0
        while (node != null && level < 6) {
            val name = node.normalName()
            if (name == "body" || name == "html" || name == "#root") return null
            val candidate = node
            if (els.all { isAncestor(candidate, it) }) {
                val id = node.id()
                if (stableId(id)) return "#$id"
                node.classNames().firstOrNull(::stableClass)?.let { return ".$it" }
            }
            node = node.parent()
            level++
        }
        return null
    }

    private val PICTURE_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "src", "srcset", "data-srcset")

    /** A sample of what [el] gives: a picture's address for picture labels, else its text, else its link */
    private fun sampleOf(el: Element, label: String): String {
        val picture = label == "cover" || label == "bigCover" || label == "image" || el.normalName() == "img"
        if (picture) {
            val img = if (el.normalName() == "img") el else el.selectFirst("img")
            val src = img?.let { i -> PICTURE_ATTRS.firstNotNullOfOrNull { a -> i.attr(a).trim().takeIf { it.isNotEmpty() } } }
            if (src != null) return src.substringBefore(' ').take(160)
        }
        val text = shortText(el, cap = 200)
        if (text.isNotBlank()) return text.take(80)
        return (el.attr("href").ifBlank { el.selectFirst("a[href]")?.attr("href").orEmpty() }).take(160)
    }

    // ---------- the code ----------

    // ICU-safe (no braces): `var NAME = "…"` at the start of a line, the literal with \\ and \" escapes
    private fun varRegex(name: String) = Regex("""(?m)^(\s*var\s+$name\s*=\s*")((?:[^"\\]|\\.)*)(")""")

    private fun decode(literal: String): String = buildString {
        var i = 0
        while (i < literal.length) {
            val ch = literal[i]
            if (ch == '\\' && i + 1 < literal.length) {
                when (val next = literal[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    else -> append(next)
                }
                i += 2
            } else {
                append(ch)
                i++
            }
        }
    }

    /** As the Generator writes values: backslash and quote escaped, line breaks and tabs as spaces */
    private fun encode(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n', '\r', '\t' -> append(' ')
                else -> append(ch)
            }
        }
    }

    /** The selectors a generic-list source's code has now, by label (only the labels in [CODE_VARS] it writes) */
    fun selectorsInCode(code: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        CODE_VARS.forEach { (label, name) ->
            varRegex(name).find(code)?.let { m -> out[label] = decode(m.groupValues[2]) }
        }
        return out
    }

    /**
     * [code] with each pick's `var NAME = "…"` literal set to its new selector. A pick is left out when its label has
     * no variable, the code has no such line, or the line no longer holds [HealedPick.oldSelector] (edited since the
     * test); the first pick of a label wins. Everything else in the code stays byte for byte.
     */
    fun applyToCode(code: String, picks: List<HealedPick>): String {
        var out = code
        val done = HashSet<String>()
        for (pick in picks) {
            if (!done.add(pick.label)) continue
            val name = CODE_VARS[pick.label] ?: continue
            val regex = varRegex(name)
            val m = regex.find(out) ?: continue
            if (decode(m.groupValues[2]).trim() != pick.oldSelector.trim()) continue
            val literal = m.groups[2]?.range ?: continue
            out = out.substring(0, literal.first) + encode(pick.newSelector) + out.substring(literal.last + 1)
        }
        return out
    }
}
