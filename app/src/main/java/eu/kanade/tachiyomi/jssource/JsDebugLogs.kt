package eu.kanade.tachiyomi.jssource

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

    private val cached: Preference<Boolean>? by lazy { runCatching { preference() }.getOrNull() }

    /** True when every console line should reach Logs; true when the setting can't be read */
    fun enabled(): Boolean = cached?.let { runCatching { it.get() }.getOrDefault(true) } ?: true
}
