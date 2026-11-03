package eu.kanade.tachiyomi.jssource.lab

import android.app.Application
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK (r115): the owner's changes to one rule of the rules file: switched off, another level, other wording.
 * A null field keeps the rules file's value, so a newer built-in file still reaches every field nobody changed.
 */
@Serializable
internal data class RuleOverride(
    val enabled: Boolean = true,
    val severity: LintSeverity? = null,
    val title: String? = null,
    val fix: String? = null,
) {
    /** Nothing changed: the rule is as the file says (such an entry isn't written) */
    val isEmpty: Boolean get() = enabled && severity == null && title == null && fix == null
}

/** KMK (r115): `filesDir/sourcelab/rule_overrides.json` */
@Serializable
internal data class RuleOverridesFile(
    val version: Int = 1,
    val rules: Map<String, RuleOverride> = emptyMap(),
)

/**
 * KMK (r115): the per-rule overrides of the Rules screen, kept apart from rules.md so importing or updating the
 * rules file keeps them, and under `sourcelab/` so the Source Lab files backup carries them. Lint applies them
 * ([effective]): an off rule is skipped, a level / title / fix replaces the file's. An id the current rules file
 * doesn't have is kept (a roll back may bring the rule back) and simply never matches.
 *
 * Reads are cached; the file is read again when its time or size changed (a backup restore writes it behind our
 * back). Writes go to a temporary file first and replace the old one, so a crash never leaves half a file.
 */
internal object RuleOverrides {

    private const val FILE_NAME = "rule_overrides.json"

    private val file: File get() = File(LabPaths.labRoot(Injekt.get<Application>().filesDir), FILE_NAME)

    private val state = MutableStateFlow<Map<String, RuleOverride>>(emptyMap())

    /** The overrides by rule id; [all] or [refresh] fill it */
    val flow: StateFlow<Map<String, RuleOverride>> = state.asStateFlow()

    private var stamp: Pair<Long, Long>? = null

    /** The current overrides (read from disk when the file changed since the last read). Call off the main thread. */
    @Synchronized
    fun all(): Map<String, RuleOverride> {
        val f = file
        val now = if (f.exists()) f.lastModified() to f.length() else 0L to -1L
        if (stamp == now) return state.value
        val read = if (!f.exists()) {
            emptyMap()
        } else {
            runCatching { jsJson.decodeFromString(RuleOverridesFile.serializer(), f.readText()).rules }
                .onFailure { AppLog.add('W', "lab", "The rule overrides file doesn't read (${it.message}); using none") }
                .getOrDefault(emptyMap())
                .filterValues { !it.isEmpty }
        }
        stamp = now
        state.value = read
        return read
    }

    /** Re-reads the file when it changed; for screens that show [flow] */
    fun refresh() {
        all()
    }

    fun get(id: String): RuleOverride? = all()[id]

    /** Stores [override] for [id]; an empty one (nothing changed) removes the entry. Call off the main thread. */
    @Synchronized
    fun set(id: String, override: RuleOverride?) {
        val current = all()
        val next = LinkedHashMap(current)
        if (override == null || override.isEmpty) next.remove(id) else next[id] = override
        if (next == current) return
        write(next)
        AppLog.add('I', "lab", "Rule $id: ${describe(override)}")
    }

    /** Reset to built-in: drops the entry for [id] */
    fun reset(id: String) = set(id, null)

    /** Drops every override */
    @Synchronized
    fun resetAll() {
        if (all().isEmpty()) return
        write(emptyMap())
        AppLog.add('I', "lab", "Every rule reset to the rules file")
    }

    /** [rule] with its override applied, or null when the owner switched it off */
    fun effective(rule: LintRule, overrides: Map<String, RuleOverride> = all()): LintRule? {
        val o = overrides[rule.id] ?: return rule
        if (!o.enabled) return null
        return rule.copy(
            severity = o.severity ?: rule.severity,
            title = o.title?.takeIf { it.isNotBlank() } ?: rule.title,
            fix = o.fix?.takeIf { it.isNotBlank() } ?: rule.fix,
        )
    }

    private fun write(rules: Map<String, RuleOverride>) {
        val f = file
        f.parentFile?.mkdirs()
        val text = jsJson.encodeToString(RuleOverridesFile.serializer(), RuleOverridesFile(rules = rules.toSortedMap()))
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            // Some file systems don't replace on rename
            f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }
        stamp = f.lastModified() to f.length()
        state.value = rules
    }

    private fun describe(o: RuleOverride?): String = when {
        o == null || o.isEmpty -> "back to the rules file"
        !o.enabled -> "switched off"
        else -> listOfNotNull(
            o.severity?.let { "level ${it.name.lowercase()}" },
            o.title?.let { "own title" },
            o.fix?.let { "own wording" },
        ).joinToString(", ")
    }
}
