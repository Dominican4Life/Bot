package eu.kanade.tachiyomi.jssource.lab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * KMK (r112): the details block the Tags groups were tapped in, written as the `tagBlock` placeholder (generic-list
 * v13's TAG_BLOCK). The owner's source was built from a title whose block had 7 rows; another title has an extra
 * "Genres" row that TAG_GROUPS (the rows seen on the sample) never lists. With the block saved, getDetails also reads
 * the rows no group lists ("Genres: Solowork" tags; a short plain row a "Series: …" description line).
 *
 * Made from the groups' label-anchored selectors (picker.js rowInfo's three shapes):
 * - dl: `dt:matches(R) + dd[ item]` → block `dl:has(> dt:matches(R)), dl:has(> div > dt:matches(R))`, labels `dt`,
 *   value `+ dd`, items `+ dd a.chip`
 * - table: `th:matches(R) ~ td[ item]` → block `table:has(th:matches(R))`, labels `tr > :first-child`, value `~ td`
 * - a box of label / value rows: `L:matches(R) ~ item, L:matches(R) ~ * item` (chips) or `L:matches(R) + V` (plain) →
 *   block `:has(> * > L:matches(R))`, labels `> * > L:first-child`
 * The value item is the one most groups use (`a.chip`), "" when the rows were plain text. Target "tags" (a plain row
 * with one short value becomes a description line in the template). The template derives the same object from
 * TAG_GROUPS when a source has no TAG_BLOCK (sources updated to v13 keep their header), so keep the two in step:
 * generic-list.js getDetails `blockFromGroups`.
 */
internal object TagBlock {

    private val CELL = Regex("""^(dt|th|td):matches\(([^()]*)\)\s*([+~])\s*(dd|td)(?:\s+([^,]+))?$""")
    private val ROW_CHIPS = Regex("""^([a-zA-Z][\w.-]*):matches\(([^()]*)\)\s*~\s*([^,]+?)\s*,\s*\1:matches\(\2\)\s*~\s*\*\s+\3$""")
    private val ROW_PLAIN = Regex("""^([a-zA-Z][\w.-]*):matches\(([^()]*)\)\s*\+\s*([^,\s]+)$""")

    private data class Shape(val kind: String, val anchor: String, val re: String, val value: String)

    /** The block of the groups' [selectors] as a JSON object, or null when none has a label-anchored row shape */
    fun of(selectors: List<String>): JsonObject? {
        var found: Shape? = null
        val shapes = ArrayList<Shape>()
        val items = LinkedHashMap<String, Int>()
        for (raw in selectors) {
            val sel = raw.trim()
            val cell = CELL.find(sel)
            val shape = if (cell != null) {
                val (tag, re, comb, valueTag, item) = cell.destructured
                if (item.isNotBlank()) items.merge(item.trim(), 1, Int::plus)
                if (tag == "dt") Shape("dl", "dt", re, "$comb $valueTag") else Shape("table", tag, re, "~ td")
            } else {
                val chips = ROW_CHIPS.find(sel)
                val plain = if (chips == null) ROW_PLAIN.find(sel) else null
                when {
                    chips != null -> {
                        items.merge(chips.groupValues[3], 1, Int::plus)
                        Shape("row", chips.groupValues[1], chips.groupValues[2], "+ *")
                    }
                    plain != null -> Shape("row", plain.groupValues[1], plain.groupValues[2], "+ " + plain.groupValues[3])
                    else -> null
                }
            }
            if (shape != null) shapes.add(shape)
            if (shape != null && (found == null || (found.kind != "dl" && shape.kind == "dl"))) found = shape
        }
        val s = found ?: return null
        // the most used value item (stable for ties: the first seen)
        val item = items.entries.maxByOrNull { it.value }?.key.orEmpty()
        // KMK (r112) fix: the block is found by any group's label of the same row shape (a title without the first
        // group's row still has its block read)
        val anchored = shapes.filter { it.kind == s.kind && it.anchor == s.anchor }.map { it.re }.distinct()
            .map { "${s.anchor}:matches($it)" }
        val (block, label, itemList) = when (s.kind) {
            "dl" -> Triple(anchored.joinToString(", ") { a -> "dl:has(> $a), dl:has(> div > $a)" }, "dt", if (item.isNotEmpty()) listOf("${s.value} $item") else emptyList())
            "table" -> Triple(anchored.joinToString(", ") { a -> "table:has($a)" }, "tr > :first-child", if (item.isNotEmpty()) listOf("~ td $item") else emptyList())
            else -> Triple(anchored.joinToString(", ") { a -> ":has(> * > $a)" }, "> * > ${s.anchor}:first-child", if (item.isNotEmpty()) listOf("~ $item", "~ * $item") else emptyList())
        }
        return JsonObject(
            mapOf(
                "block" to JsonPrimitive(block),
                "label" to JsonPrimitive(label),
                "anchor" to JsonPrimitive(s.anchor),
                "value" to JsonPrimitive(s.value),
                "item" to JsonPrimitive(item),
                "items" to JsonArray(itemList.map { JsonPrimitive(it) }),
                "target" to JsonPrimitive("tags"),
            ),
        )
    }

    /** The `tagBlock` placeholder: the block's JSON text, "" when there is none (the template reads null) */
    fun json(selectors: List<String>): String = of(selectors)?.toString().orEmpty()
}
