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
 * r98: `currentN` in the index is the version the code is now. Restore points it at an older version instead of
 * adding one; Delete removes chosen versions (never the current one, never `generatedFrom`); a source with no
 * history gets a "Baseline" v1 when the Lab's Home loads.
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

    /**
     * KMK (r98): the version the source's code is now: [LabHistoryIndex.currentN] when it names a kept version
     * (after a Restore), else the newest. 0 when there is no history yet.
     */
    fun currentOf(index: LabHistoryIndex): Int =
        index.currentN?.takeIf { c -> index.versions.any { it.n == c } } ?: index.versions.lastOrNull()?.n ?: 0

    /**
     * The version the code is now (r98: the restored one after a Restore, not necessarily the newest), 0 when there
     * is no history yet. Test Runs and Issue Reports record this number.
     */
    fun latestVersion(key: String): Int = currentOf(index(key))

    /** The newest version number, 0 when there is no history yet */
    fun newestVersion(key: String): Int = index(key).versions.lastOrNull()?.n ?: 0

    /** The code of version [n], or null when it was trimmed or never existed */
    @Synchronized
    fun code(key: String, n: Int): String? {
        val entry = index(key).versions.firstOrNull { it.n == n } ?: return null
        return runCatching { versionFile(key, n, entry.json).readText() }.getOrNull()
    }

    /**
     * Adds [code] as the next version with [note]. When the current version (r98: the restored one, else the newest)
     * already holds exactly this code nothing is added (a save that changed only the Details fields isn't a new
     * version). The new version becomes current. Returns the version written, or the unchanged current one.
     */
    @Synchronized
    fun append(
        key: String,
        code: String,
        json: Boolean,
        note: String,
        testRunId: String? = null,
        generatedFrom: Boolean = false,
        onlyIfEmpty: Boolean = false,
    ): LabVersion? {
        val index = index(key)
        // Baseline: decided on the index read here, under the same lock as the write below
        if (onlyIfEmpty && index.versions.isNotEmpty()) return null
        val last = index.versions.lastOrNull()
        val currentN = currentOf(index)
        val current = index.versions.firstOrNull { it.n == currentN }
        val previous = current?.let { runCatching { versionFile(key, it.n, it.json).readText() }.getOrNull() }
        if (current != null && previous == code) return current
        val (added, removed) = counts(previous.orEmpty(), code)
        // r98 fix: after the highest number ever given (lastN), so a deleted version's number is never reused
        val n = maxOf(index.lastN, last?.n ?: 0) + 1
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
        val generated = index.generatedFrom ?: n.takeIf { generatedFrom }
        // KMK (r106 fix): the implied baseline (Baseline note / generated-from / first version) is kept too, not only baselineN
        val baselineKeep = baselineOf(index.copy(versions = index.versions + version, generatedFrom = generated))
        val kept = trim(key, index.versions + version, generated, n, baselineKeep)
        // The new version is the newest, so it is current: currentN goes back to null ("the newest").
        // KMK (r106): the baseline pointer is carried over
        write(key, LabHistoryIndex(kept, generated, currentN = null, lastN = n, baselineN = index.baselineN))
        AppLog.add('I', "lab", "$key: history v$n ($note, +$added −$removed)")
        return version
    }

    /**
     * KMK (r102, r103): Save → "Save to vN": the current version's file (the one open: the newest, or a restored older
     * one) is overwritten with [code] in place: same number, note "<its note> · updated", time now, +added −removed
     * against the version listed before it, and its Test Run mark cleared (that run tested the old code; the next run
     * marks it again). The source's own code was already written by the manager's save; only this bookkeeping differs
     * from [append]. A restored version stays current (currentN is kept); the generated-from mark stays on its number.
     *
     * r103: nothing is refused any more (r102 kept generated-from, Baseline and restored versions): a Baseline is
     * confirmed by the Save button first ([overwriteConfirm]). Falls back to [append] with [fallbackNote] (a new
     * version, as a plain Save) only when there is no current version yet. Returns the version written and whether it
     * was updated in place; null when the source's folder is gone.
     */
    @Synchronized
    fun updateCurrent(key: String, code: String, json: Boolean, fallbackNote: String): Pair<LabVersion, Boolean>? {
        val index = index(key)
        val currentN = currentOf(index)
        val current = index.versions.firstOrNull { it.n == currentN }
        if (current == null) {
            AppLog.add('I', "lab", "$key: update in place not possible (no version yet): saving as a new version")
            return append(key, code, json, fallbackNote)?.let { it to false }
        }
        val old = runCatching { versionFile(key, current.n, current.json).readText() }.getOrNull()
        if (old == code && current.json == json) return current to true
        val folder = dir(key)
        // KMK: only next to an existing source (a sidecar never makes a source exist, C§4)
        if (folder.parentFile?.isDirectory != true) return null
        folder.mkdirs()
        val position = index.versions.indexOfFirst { it.n == current.n }
        val before = index.versions.getOrNull(position - 1)?.let { v ->
            runCatching { versionFile(key, v.n, v.json).readText() }.getOrNull()
        }
        val (added, removed) = counts(before.orEmpty(), code)
        versionFile(key, current.n, json).writeText(code)
        // JS ↔ JSON (only possible while the code was a template): the old file's extension no longer applies
        if (current.json != json) runCatching { versionFile(key, current.n, current.json).delete() }
        val updated = current.copy(
            time = System.currentTimeMillis(),
            note = current.note.removeSuffix(UPDATED_SUFFIX) + UPDATED_SUFFIX,
            added = added,
            removed = removed,
            testRunId = null,
            json = json,
        )
        write(key, index.copy(versions = index.versions.map { if (it.n == current.n) updated else it }))
        val what = when {
            current.n == index.generatedFrom -> " (the generated-from version)"
            current.n != index.versions.lastOrNull()?.n -> " (restored, not the newest)"
            else -> ""
        }
        AppLog.add('I', "lab", "$key: history v${current.n}$what updated in place (+$added −$removed against the version before it)")
        return updated to true
    }

    /**
     * KMK (r102, r103): why "Save to vN" can't overwrite the current version, null when it can. r103: only when there
     * is no version yet (generated-from, Baseline and restored versions are overwritten too; Baseline after a confirm).
     */
    fun updateBlockedReason(index: LabHistoryIndex): String? =
        if (index.versions.none { it.n == currentOf(index) }) "no version yet" else null

    /**
     * KMK (r103): the question the Save button asks before overwriting the current version in place, null when it
     * saves without asking: only a Baseline (the pre-Lab original code of a hand-written / imported source) that was
     * never overwritten yet. Generated-from and restored versions are saved in place without asking.
     */
    fun overwriteConfirm(index: LabHistoryIndex): String? {
        val currentN = currentOf(index)
        val current = index.versions.firstOrNull { it.n == currentN } ?: return null
        return when {
            current.note == BASELINE_NOTE -> "v${current.n} is the original code from before the Lab. Overwrite it?"
            // KMK (r106): a version made the baseline by hand asks too
            index.baselineN == current.n -> "v${current.n} is this source's baseline. Overwrite it?"
            else -> null
        }
    }

    /**
     * KMK (r106): the source's baseline version: the one made the baseline in History ([LabHistoryIndex.baselineN])
     * while it is kept, else the version noted "Baseline", else the generated-from version, else the first version
     * (old sources). Null when there is no version.
     */
    fun baselineOf(index: LabHistoryIndex): Int? {
        val kept = index.versions.map { it.n }.toSet()
        return index.baselineN?.takeIf { it in kept }
            ?: index.versions.firstOrNull { it.note.removeSuffix(UPDATED_SUFFIX) == BASELINE_NOTE }?.n
            ?: index.generatedFrom?.takeIf { it in kept }
            ?: index.versions.firstOrNull()?.n
    }

    /**
     * KMK (r106): History's "Make vN the baseline": [n] becomes [LabHistoryIndex.baselineN] (never trimmed or deleted
     * while it is). False when [n] isn't a kept version.
     */
    @Synchronized
    fun setBaseline(key: String, n: Int): Boolean {
        val index = index(key)
        if (index.versions.none { it.n == n }) return false
        write(key, index.copy(baselineN = n))
        return true
    }

    /** KMK (r103): the note of the version [baseline] writes */
    const val BASELINE_NOTE = "Baseline"

    /** KMK (r102): what an in-place update adds to a version's note (once, however often it is updated) */
    const val UPDATED_SUFFIX = " · updated"

    /**
     * KMK (r98): a source saved before the Lab existed (or whose history was lost) gets its code as v1 "Baseline",
     * so every source shows a version to come back to. Nothing happens when the source already has a version.
     */
    @Synchronized
    fun baseline(key: String, code: String, json: Boolean): LabVersion? =
        // r98 fix: the "no versions yet" check is made inside append, on the index it writes, under History's lock
        // (the one every append takes), so a save that got there first is never followed by a Baseline
        append(key, code, json, BASELINE_NOTE, onlyIfEmpty = true)?.also { v ->
            // KMK (r106 fix): the Baseline written here is also the stored baseline pointer
            val written = index(key)
            if (written.baselineN == null && written.versions.any { it.n == v.n }) write(key, written.copy(baselineN = v.n))
        }

    /**
     * KMK (r98 fix): when a Restore's [setCurrent] fails (the version went meanwhile), the newest version whose code
     * equals [code] becomes current; when none does, [code] is added as a version with [note]. Returns its number.
     */
    @Synchronized
    fun markCurrentByCode(key: String, code: String, json: Boolean, note: String): Int? {
        val index = index(key)
        val same = index.versions.lastOrNull { v -> runCatching { versionFile(key, v.n, v.json).readText() }.getOrNull() == code }
        if (same == null) return append(key, code, json, note)?.n
        val newest = index.versions.last().n
        write(key, index.copy(currentN = same.n.takeIf { it != newest }))
        AppLog.add('I', "lab", "$key: v${same.n} is current (same code as the restored version)")
        return same.n
    }

    /**
     * KMK (r98): Restore: version [n] becomes current without a new version (the source's code was already written
     * by the manager's save, see LabStore.restore). A later Save appends after the newest as usual.
     */
    @Synchronized
    fun setCurrent(key: String, n: Int): Boolean {
        val index = index(key)
        if (index.versions.none { it.n == n }) return false
        val newest = index.versions.last().n
        write(key, index.copy(currentN = n.takeIf { it != newest }))
        AppLog.add('I', "lab", "$key: v$n is current (restored)")
        return true
    }

    /**
     * KMK (r98): deletes versions [ns] (their `v<n>` files and index lines). The current version and the one the code
     * was generated from are never deleted; returns the numbers really removed.
     */
    @Synchronized
    fun delete(key: String, ns: Set<Int>): List<Int> {
        val index = index(key)
        val current = currentOf(index)
        // KMK (r106): nor the baseline (r106 fix: the implied one too, History.baselineOf)
        val baseline = baselineOf(index)
        val victims = index.versions.filter { it.n in ns && it.n != current && it.n != index.generatedFrom && it.n != baseline }
        if (victims.isEmpty()) return emptyList()
        victims.forEach { v -> runCatching { versionFile(key, v.n, v.json).delete() } }
        val gone = victims.map { it.n }.toSet()
        val kept = index.versions.filterNot { it.n in gone }
        // currentN stays pointing at the same version; null again when that one is now the newest
        val currentN = current.takeIf { c -> kept.lastOrNull()?.n != c }
        // lastN keeps the highest number ever given (an index from before r98 has 0: the newest before the delete)
        val lastN = maxOf(index.lastN, index.versions.lastOrNull()?.n ?: 0)
        write(key, index.copy(versions = kept, currentN = currentN, lastN = lastN))
        AppLog.add('I', "lab", "$key: history deleted ${gone.sorted().joinToString { "v$it" }}")
        return gone.sorted()
    }

    /** The newest version becomes the one the code was generated from, unless one is set already */
    @Synchronized
    fun markGeneratedFrom(key: String) {
        val index = index(key)
        if (index.generatedFrom != null) return
        val last = index.versions.lastOrNull() ?: return
        write(key, index.copy(generatedFrom = last.n))
    }

    /** Marks a Test Run as the one made on the current version (r98: the restored one, else the newest) */
    @Synchronized
    fun noteTestRun(key: String, runId: String) {
        val index = index(key)
        val currentN = currentOf(index)
        val current = index.versions.firstOrNull { it.n == currentN } ?: return
        if (current.testRunId != null) return
        val versions = index.versions.map { if (it.n == currentN) it.copy(testRunId = runId) else it }
        write(key, index.copy(versions = versions))
    }

    /** Keeps the newest [CAP], the generated-from version, [keep] and the newest; deletes the files of the rest */
    private fun trim(key: String, versions: List<LabVersion>, generatedFrom: Int?, keep: Int?, baseline: Int? = null): List<LabVersion> {
        if (versions.size <= CAP) return versions
        val newest = versions.last().n
        val out = versions.toMutableList()
        val removable = out.filter { it.n != generatedFrom && it.n != newest && it.n != keep && it.n != baseline }.toMutableList()
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
