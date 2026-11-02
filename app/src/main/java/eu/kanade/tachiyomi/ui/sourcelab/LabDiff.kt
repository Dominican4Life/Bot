package eu.kanade.tachiyomi.ui.sourcelab

import eu.kanade.tachiyomi.jssource.lab.ExplainEntry
import eu.kanade.tachiyomi.jssource.lab.History
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.TestRun

/** KMK (r106): one part's share of a diff ("Tags +3 −1"); [id] is empty for lines outside every part */
internal data class PartChange(val id: String, val title: String, val added: Int, val removed: Int)

/**
 * KMK (r106): small pure helpers of the code editor revamp: which parts a diff touches (History's compare, Lint's fix
 * preview), the diff with a little context, a part's test status (Parts outline dots) and the source line an error
 * names. No Android or Compose here.
 */
internal object LabDiff {

    /** A History / Lint diff keeps this many lines of context around each change */
    const val CONTEXT = 2

    /** The part whose lines hold 1-based [line], or null */
    fun partOf(line: Int?, parts: List<ExplainEntry>): ExplainEntry? {
        if (line == null) return null
        return parts.firstOrNull { p -> p.lines?.let { line in it } == true }
    }

    /**
     * Added / removed lines per part, in the order the parts first change. '+' lines are placed by their line in the
     * new text ([newParts]), '-' lines by their line in the old one ([oldParts]).
     */
    fun partChanges(diff: List<History.DiffLine>, oldParts: List<ExplainEntry>, newParts: List<ExplainEntry>): List<PartChange> {
        val order = ArrayList<String>()
        val titles = HashMap<String, String>()
        val added = HashMap<String, Int>()
        val removed = HashMap<String, Int>()
        diff.forEach { d ->
            if (d.kind == ' ') return@forEach
            val part = if (d.kind == '+') partOf(d.lineB, newParts) else partOf(d.lineA, oldParts)
            val id = part?.id.orEmpty()
            if (id !in titles) {
                order.add(id)
                titles[id] = part?.title ?: "Other lines"
            }
            if (d.kind == '+') added[id] = (added[id] ?: 0) + 1 else removed[id] = (removed[id] ?: 0) + 1
        }
        return order.map { id -> PartChange(id, titles[id].orEmpty(), added[id] ?: 0, removed[id] ?: 0) }
    }

    /** Keeps [context] lines around every change; each run of skipped lines reads as one `…` line */
    fun compact(diff: List<History.DiffLine>, context: Int = CONTEXT): List<History.DiffLine> {
        if (diff.none { it.kind != ' ' }) return diff.take(40)
        val keep = BooleanArray(diff.size)
        diff.forEachIndexed { i, line ->
            if (line.kind != ' ') {
                for (j in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(diff.size - 1)) keep[j] = true
            }
        }
        val out = ArrayList<History.DiffLine>()
        var gap = false
        diff.forEachIndexed { i, line ->
            if (keep[i]) {
                out.add(line)
                gap = false
            } else if (!gap) {
                out.add(History.DiffLine(' ', "…", null, null))
                gap = true
            }
        }
        return out
    }

    /**
     * Each part's status in [run]: the best non-skipped step that exercised it (the reading Explain's pills and
     * Source info's facts use), so a part shared by a passing and a failing step reads as tested.
     */
    fun partStatuses(run: TestRun?): Map<String, StepStatus> {
        if (run == null) return emptyMap()
        return run.steps
            .filter { it.status != StepStatus.Skipped && it.status != StepStatus.Running }
            .flatMap { step -> step.partIds.map { it to step.status } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, statuses) -> statuses.minByOrNull { it.ordinal } ?: StepStatus.Skipped }
    }

    private val JS_LINE = Regex("""source\.js:(\d+)""")
    private val WORD_LINE = Regex("""\bline (\d+)\b""")

    /** The source's own line an error text names: `source.js:381` from a QuickJS stack, else "line 50" */
    fun errorLineOf(text: String?): Int? {
        if (text.isNullOrEmpty()) return null
        return (JS_LINE.find(text) ?: WORD_LINE.find(text))?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    }
}
