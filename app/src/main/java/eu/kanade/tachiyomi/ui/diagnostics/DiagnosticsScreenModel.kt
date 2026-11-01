package eu.kanade.tachiyomi.ui.diagnostics

import android.content.Context
import androidx.core.content.edit
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.diagnostics.Diag
import eu.kanade.tachiyomi.diagnostics.DiagCategory
import eu.kanade.tachiyomi.diagnostics.DiagKeys
import eu.kanade.tachiyomi.diagnostics.DiagTab
import eu.kanade.tachiyomi.diagnostics.Diagnostics
import eu.kanade.tachiyomi.diagnostics.LogFilter
import eu.kanade.tachiyomi.diagnostics.LogLevelFilter
import eu.kanade.tachiyomi.diagnostics.Resource
import eu.kanade.tachiyomi.diagnostics.TimeWindow
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * KMK (r97): UI state of the Diagnostics screen only (contract §1 Half F): the tab, the Live resource and window,
 * the Log tab's filter and the pending "jump to a time" a Health dot or a marker asked for. Every measurement comes
 * from [Diag.get]; nothing here samples, sorts or copies engine data.
 */
internal class DiagnosticsScreenModel(
    context: Context,
    initialTab: DiagTab?,
    val probeId: String?,
) : ScreenModel {

    val diag: Diagnostics = Diag.get()
    private val prefs = diagPrefs(context.applicationContext)

    /**
     * True when the screen opened on Log because a W/E arrived since the last visit: the tab scrolls to it once.
     * Declared before [_tab]: [pickStartTab] sets it from that initializer (a later initializer would reset it).
     */
    var scrollToNewestProblem: Boolean = false
        private set

    private val _tab = MutableStateFlow(pickStartTab(initialTab))
    val tab: StateFlow<DiagTab> = _tab.asStateFlow()

    private var probeIdTaken = false

    /** The probe a notification asked to open, once (the Probes tab pushes its editor on first composition). */
    fun takeProbeId(): String? {
        if (probeIdTaken) return null
        probeIdTaken = true
        return probeId
    }

    // --- Live ---
    private val _resource = MutableStateFlow(Resource.NETWORK)
    val resource: StateFlow<Resource> = _resource.asStateFlow()
    private val _window = MutableStateFlow(TimeWindow.M1)
    val window: StateFlow<TimeWindow> = _window.asStateFlow()
    private val _liveAdvanced = MutableStateFlow(prefs.getBoolean(DiagKeys.LIVE_ADVANCED, false))
    val liveAdvanced: StateFlow<Boolean> = _liveAdvanced.asStateFlow()

    // --- Log ---
    private val _logLevel = MutableStateFlow(LogLevelFilter.ALL)
    val logLevel: StateFlow<LogLevelFilter> = _logLevel.asStateFlow()
    private val _logChipKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Selected chips as keys: `cat:<NAME>`, `tag:<tag>`, `src:<source>` */
    val logChipKeys: StateFlow<Set<String>> = _logChipKeys.asStateFlow()
    private val _logQuery = MutableStateFlow("")
    val logQuery: StateFlow<String> = _logQuery.asStateFlow()

    /** ±30 s around a time a Health dot or a marker asked for; cleared by the Log tab's "×" chip */
    private val _logAround = MutableStateFlow<Long?>(null)
    val logAround: StateFlow<Long?> = _logAround.asStateFlow()

    /**
     * The Log tab's filter as one flow: chips, level and the time apply at once, the search box after a 150 ms
     * pause in typing (so the list isn't rebuilt on every character).
     */
    @OptIn(FlowPreview::class)
    val logFilterFlow: StateFlow<LogFilter> = combine(
        _logLevel,
        _logChipKeys,
        _logQuery.debounce(150L),
        _logAround,
    ) { level, keys, query, around -> buildLogFilter(level, keys, query, around) }
        .stateIn(screenModelScope, SharingStarted.Eagerly, logFilter())

    private fun pickStartTab(initialTab: DiagTab?): DiagTab {
        if (initialTab != null) return initialTab
        // Contract §5 / D§2.2: a W or E newer than the last visit lands on Log, scrolled to it
        val version = diag.logVersion.value
        val seen = prefs.getInt(DiagKeys.SEEN_VERSION, -1)
        if (seen in 0 until version) {
            val newer = (version - seen).coerceAtMost(4000)
            // KMK (r103): a walk over the kept lines (no LogEntry built per line); it decides the first tab, so it
            // stays synchronous
            val hasProblem = runCatching { AppLog.problemsInNewest(newer) > 0 }.getOrDefault(false)
            if (hasProblem) {
                scrollToNewestProblem = true
                return DiagTab.LOG
            }
        }
        val last = prefs.getString(DiagKeys.LAST_TAB, null)
        return DiagTab.entries.firstOrNull { it.name == last } ?: DiagTab.LIVE
    }

    fun setTab(tab: DiagTab) {
        _tab.value = tab
        prefs.edit { putString(DiagKeys.LAST_TAB, tab.name) }
    }

    /** The Log tab was shown: W/E lines up to now count as seen. */
    fun markLogSeen() {
        scrollToNewestProblem = false
        prefs.edit { putInt(DiagKeys.SEEN_VERSION, diag.logVersion.value) }
    }

    fun setResource(resource: Resource) {
        _resource.value = resource
    }

    fun setWindow(window: TimeWindow) {
        _window.value = window
    }

    fun setLiveAdvanced(on: Boolean) {
        _liveAdvanced.value = on
        prefs.edit { putBoolean(DiagKeys.LIVE_ADVANCED, on) }
    }

    fun setLogLevel(level: LogLevelFilter) {
        _logLevel.value = level
    }

    fun toggleLogChip(key: String) {
        _logChipKeys.update { if (key in it) it - key else it + key }
    }

    fun setLogQuery(query: String) {
        _logQuery.value = query
    }

    /** Opens the Log tab on the lines ±30 s around [timeMs], optionally with one tag chip (a marker's). */
    fun openLogAround(timeMs: Long, tag: String? = null) {
        _logAround.value = timeMs
        _logLevel.value = LogLevelFilter.ALL
        _logChipKeys.value = if (tag.isNullOrBlank()) emptySet() else setOf("tag:$tag")
        _logQuery.value = ""
        setTab(DiagTab.LOG)
    }

    fun clearLogAround() {
        _logAround.value = null
    }

    /** The engine's filter built from the chips, the search box and the pending time (the engine parses `k=v`). */
    fun logFilter(): LogFilter = buildLogFilter(_logLevel.value, _logChipKeys.value, _logQuery.value, _logAround.value)

    private fun buildLogFilter(level: LogLevelFilter, keys: Set<String>, query: String, around: Long?): LogFilter =
        LogFilter(
            level = level,
            categories = keys.filter { it.startsWith("cat:") }
                .mapNotNull { k -> DiagCategory.entries.firstOrNull { it.name == k.substring(4) } }
                .toSet(),
            tags = keys.filter { it.startsWith("tag:") }.map { it.substring(4) }.toSet(),
            // Free text wins; a source chip alone becomes the structured `source=<name>` query
            query = query.trim().ifBlank {
                keys.firstOrNull { it.startsWith("src:") }?.let { "source=" + it.substring(4) } ?: ""
            },
            aroundTime = around,
        )
}
