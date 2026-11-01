package eu.kanade.tachiyomi.jssource

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r102): the global "JS sources: debug logs" switch (Settings › Advanced › Extensions). On (the default), every
 * console line of every JS / JSON source goes to the Logs screen tagged `js:<name>`; off, only its warnings and
 * errors do. Read by `JsRuntime.Host.log` on every line, so it takes effect at once, no restart.
 */
object JsDebugLogs {

    const val KEY = "pref_js_debug_logs"

    fun preference(): Preference<Boolean> = Injekt.get<PreferenceStore>().getBoolean(KEY, true)

    /**
     * KMK (r103): the switch as a StateFlow kept up to date by the preference's change listener, so the check on
     * every console line is one volatile read instead of a SharedPreferences lookup. A change still applies at once.
     */
    private val state: StateFlow<Boolean>? by lazy {
        runCatching { preference().stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }.getOrNull()
    }

    /** True when every console line should reach Logs; true when the setting can't be read */
    fun enabled(): Boolean = state?.value ?: true
}
