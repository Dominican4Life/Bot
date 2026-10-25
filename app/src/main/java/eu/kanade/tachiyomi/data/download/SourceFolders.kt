package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile

/**
 * KMK: Finds a source's download folder the way a person would: "Toonily JS (EN)" and "Toonily JS (JS)" are the
 * same source, and so is a "Hiperdex (EN) (1)" copy Android made when two downloads raced to make the folder.
 * Folders are only ever made through [findOrCreate], one at a time, so no more numbered copies get made.
 */
object SourceFolders {

    /** The " (1)" Android adds when a folder with that name already exists */
    private val copySuffix = Regex("""\s\(\d+\)$""")
    private val tagged = Regex("""^(.*) \(([^()]*)\)$""")
    private val lock = Any()

    /**
     * What a source's folders may be called: [exact] is today's name ("Name (EN)"), [baseName] the source name made
     * safe for a file name, [tags] the brackets that count for it (its language, ALL, JS, JSON).
     */
    class Match(val exact: String, val baseName: String, val tags: Set<String>) {

        /** 0 for today's name, 1 for the same source under another tag, 2 for a numbered copy; null: not this source */
        fun rank(folder: String): Int? {
            if (folder.equals(exact, ignoreCase = true)) return 0
            val copy = copySuffix.containsMatchIn(folder)
            val plain = folder.replace(copySuffix, "")
            if (plain.equals(exact, ignoreCase = true)) return 2
            val m = tagged.matchEntire(plain) ?: return null
            if (!m.groupValues[1].equals(baseName, ignoreCase = true)) return null
            if (m.groupValues[2].uppercase() !in tags) return null
            return if (copy) 2 else 1
        }
    }

    fun match(exact: String, baseName: String, lang: String, js: Boolean): Match {
        val tags = buildSet {
            add(lang.uppercase())
            add(lang.split('-', '_').first().uppercase())
            if (lang.isBlank() || lang in listOf("all", "multi", "mul")) add("ALL")
            if (js) {
                add("JS")
                add("JSON")
            }
        }
        return Match(exact, baseName, tags)
    }

    /** Every folder under [parent] that belongs to the source, best first */
    fun find(parent: UniFile?, match: Match): List<UniFile> {
        parent ?: return emptyList()
        return parent.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.isNullOrBlank() }
            .mapNotNull { dir -> match.rank(dir.name!!)?.let { dir to it } }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * The folder [name] under [parent]: an existing one (any case, or any folder [match] accepts) or a new one.
     * Made one at a time so two downloads never both make it.
     */
    fun findOrCreate(parent: UniFile, name: String, match: Match? = null): UniFile? = synchronized(lock) {
        parent.findFile(name)?.takeIf { it.isDirectory }?.let { return it }
        val existing = if (match != null) {
            find(parent, match).firstOrNull()
        } else {
            parent.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
        }
        if (existing != null) return existing
        parent.createDirectory(name)
    }
}
