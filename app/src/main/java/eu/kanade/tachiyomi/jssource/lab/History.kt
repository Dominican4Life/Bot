package eu.kanade.tachiyomi.jssource.lab

import android.app.Application
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.util.system.AppLog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK (r96): a source's versions for the Source Lab's History pane: every successful save is kept as
 * `js_sources/<key>/history/v<n>.js|json` with one line in `history/index.json` (note, time, +added −removed, the
 * Test Run at that time). At most 30 versions; the oldest go first, never the version the code was generated from
 * and never the newest. Nothing here ever deletes the source's own code.
 *
 * Callers run on IO. The object is synchronised per call so two saves can't interleave an index write.
 */
internal object History {

    private val filesDir: File get() = Injekt.get<Application>().filesDir

    private const val CAP = 30

    private fun dir(key: String): File {
        require(JsSourceManager.KEY_PATTERN.matches(key)) { "Not a valid source key: $key" }
        return LabPaths.history(filesDir, key)
    }

    private fun indexFile(key: String) = File(dir(key), "index.json")

    private fun versionFile(key: String, n: Int, json: Boolean) = File(dir(key), "v$n.${if (json) "json" else "js"}")

    @Synchronized
    fun index(key: String): LabHistoryIndex = runCatching {
        val f = indexFile(key)
        if (f.exists()) jsJson.decodeFromString(LabHistoryIndex.serializer(), f.readText()) else null
    }.getOrNull() ?: LabHistoryIndex()

    /** The newest version number, 0 when there is no history yet */
    fun latestVersion(key: String): Int = index(key).versions.lastOrNull()?.n ?: 0

    /** The code of version [n], or null when it was trimmed or never existed */
    @Synchronized
    fun code(key: String, n: Int): String? {
        val entry = index(key).versions.firstOrNull { it.n == n } ?: return null
        return runCatching { versionFile(key, n, entry.json).readText() }.getOrNull()
    }

    /**
     * Adds [code] as the next version with [note]. When the newest version already holds exactly this code nothing
     * is added (a save that changed only the Details fields isn't a new version). Returns the version written, or
     * the unchanged newest one.
     */
    @Synchronized
    fun append(
        key: String,
        code: String,
        json: Boolean,
        note: String,
        testRunId: String? = null,
        generatedFrom: Boolean = false,
    ): LabVersion? {
        val index = index(key)
        val last = index.versions.lastOrNull()
        val previous = last?.let { runCatching { versionFile(key, it.n, it.json).readText() }.getOrNull() }
        if (last != null && previous == code) return last
        val (added, removed) = counts(previous.orEmpty(), code)
        val n = (last?.n ?: 0) + 1
        val folder = dir(key)
        // KMK: only next to an existing source (a sidecar never makes a source exist, C§4)
        if (folder.parentFile?.isDirectory != true) return null
        folder.mkdirs()
        versionFile(key, n, json).writeText(code)
        val version = LabVersion(
            n = n,
            time = System.currentTimeMillis(),
            note = note,
            added = added,
            removed = removed,
            testRunId = testRunId,
            json = json,
        )
        val kept = trim(key, index.versions + version, index.generatedFrom ?: n.takeIf { generatedFrom })
        write(key, LabHistoryIndex(kept, index.generatedFrom ?: n.takeIf { generatedFrom }))
        AppLog.add('I', "lab", "$key: history v$n ($note, +$added −$removed)")
        return version
    }

    /** The newest version becomes the one the code was generated from, unless one is set already */
    @Synchronized
    fun markGeneratedFrom(key: String) {
        val index = index(key)
        if (index.generatedFrom != null) return
        val last = index.versions.lastOrNull() ?: return
        write(key, index.copy(generatedFrom = last.n))
    }

    /** Marks a Test Run as the one made on the newest version */
    @Synchronized
    fun noteTestRun(key: String, runId: String) {
        val index = index(key)
        val last = index.versions.lastOrNull() ?: return
        if (last.testRunId != null) return
        val versions = index.versions.dropLast(1) + last.copy(testRunId = runId)
        write(key, index.copy(versions = versions))
    }

    /** Keeps the newest [CAP], the generated-from version and the newest; deletes the files of the rest */
    private fun trim(key: String, versions: List<LabVersion>, generatedFrom: Int?): List<LabVersion> {
        if (versions.size <= CAP) return versions
        val newest = versions.last().n
        val out = versions.toMutableList()
        val removable = out.filter { it.n != generatedFrom && it.n != newest }.toMutableList()
        while (out.size > CAP && removable.isNotEmpty()) {
            val victim = removable.removeAt(0)
            out.remove(victim)
            runCatching { versionFile(key, victim.n, victim.json).delete() }
        }
        return out
    }

    private fun write(key: String, index: LabHistoryIndex) {
        val f = indexFile(key)
        f.parentFile?.mkdirs()
        f.writeText(jsJson.encodeToString(LabHistoryIndex.serializer(), index))
    }

    // ---------- line diff ----------

    /** One line of a diff: ' ' same, '+' only in the new text, '-' only in the old one */
    data class DiffLine(val kind: Char, val text: String, val lineA: Int?, val lineB: Int?)

    /** Lines added and removed between [old] and [new] */
    fun counts(old: String, new: String): Pair<Int, Int> {
        val diff = diff(old, new)
        return diff.count { it.kind == '+' } to diff.count { it.kind == '-' }
    }

    /**
     * A line diff by longest common subsequence. Texts over [MAX_DIFF_LINES] lines fall back to a plain compare
     * (every differing line counted) so a huge paste can't take a quadratic table.
     */
    fun diff(old: String, new: String): List<DiffLine> {
        val a = if (old.isEmpty()) emptyList() else old.lines()
        val b = if (new.isEmpty()) emptyList() else new.lines()
        if (a.size.toLong() * b.size.toLong() > MAX_DIFF_LINES.toLong() * MAX_DIFF_LINES.toLong()) {
            val out = ArrayList<DiffLine>()
            a.forEachIndexed { i, line -> out.add(DiffLine('-', line, i + 1, null)) }
            b.forEachIndexed { i, line -> out.add(DiffLine('+', line, null, i + 1)) }
            return out
        }
        // Common prefix and suffix first: a save usually changes a few lines in the middle
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        val midA = a.subList(start, endA)
        val midB = b.subList(start, endB)
        val n = midA.size
        val m = midB.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (midA[i] == midB[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        val out = ArrayList<DiffLine>()
        for (i in 0 until start) out.add(DiffLine(' ', a[i], i + 1, i + 1))
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                midA[i] == midB[j] -> {
                    out.add(DiffLine(' ', midA[i], start + i + 1, start + j + 1))
                    i++
                    j++
                }
                table[i + 1][j] >= table[i][j + 1] -> {
                    out.add(DiffLine('-', midA[i], start + i + 1, null))
                    i++
                }
                else -> {
                    out.add(DiffLine('+', midB[j], null, start + j + 1))
                    j++
                }
            }
        }
        while (i < n) {
            out.add(DiffLine('-', midA[i], start + i + 1, null))
            i++
        }
        while (j < m) {
            out.add(DiffLine('+', midB[j], null, start + j + 1))
            j++
        }
        for (k in 0 until a.size - endA) {
            out.add(DiffLine(' ', a[endA + k], endA + k + 1, endB + k + 1))
        }
        return out
    }

    private const val MAX_DIFF_LINES = 4000
}
