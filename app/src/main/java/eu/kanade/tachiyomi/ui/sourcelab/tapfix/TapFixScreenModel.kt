package eu.kanade.tachiyomi.ui.sourcelab.tapfix

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.lab.Derive
import eu.kanade.tachiyomi.jssource.lab.labMeta
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r118): the state behind Tap to fix. Reads the source's code ([JsSourceManager.code]), lists the places that
 * pick something ([TapFixScanner]), talks to the page script ([TapFixPageScript]) through [pageScripts] (evaluated by
 * the screen on the main thread) and [onReport] (the page's answers, any thread), and saves the changed code through
 * [JsSourceManager.save] with a History note, as the Lab's editor and the builder's "Save to this source" do (a new
 * History version; the source's name, language, icon, address and Lab record stay).
 */
internal class TapFixScreenModel(private val sourceKey: String) : StateScreenModel<TapFixScreenModel.State>(State()) {

    @Immutable
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val name: String = "",
        val json: Boolean = false,
        /** the site's address the page opens on (Lab record's site, else the source's baseUrl); null = none */
        val startUrl: String? = null,
        val places: List<TapFixScanner.Place> = emptyList(),
        val selectedId: String? = null,
        /** Browse: links work (to reach a title or episode page); Tap: a tap picks */
        val browse: Boolean = false,
        val pageUrl: String? = null,
        val pageTitle: String? = null,
        /** the page shows a bot check: Tap is off, the user passes it in WebView */
        val botCheck: Boolean = false,
        /** the old selector on this page */
        val old: Measure? = null,
        /** the last tap's new selector */
        val pick: Pick? = null,
        val saving: Boolean = false,
        /** a snackbar line */
        val message: String? = null,
    ) {
        val selected: TapFixScanner.Place? get() = places.firstOrNull { it.id == selectedId }

        /** The page picks on a tap only now */
        val tapActive: Boolean get() = selected?.fixable == true && !browse && !botCheck && !saving
    }

    @Immutable
    data class Measure(
        val ok: Boolean,
        val count: Int,
        /** Items on the page when counted inside each Item; -1 = counted on the whole page */
        val cards: Int,
        val samples: List<String>,
        /** Jsoup-only parts (`:containsOwn(…)`, …) were left out to run it in the browser */
        val approx: Boolean,
        val empty: Boolean,
        val error: String?,
    )

    @Immutable
    data class Pick(
        val selector: String,
        val count: Int,
        val cards: Int,
        val relative: Boolean,
        val samples: List<String>,
        /** no short selector found the same set: the plain path was used */
        val weak: Boolean,
        val note: String?,
        val tag: String,
        val snippet: String,
        val via: String,
    )

    private val manager: JsSourceManager by lazy { Injekt.get<JsSourceManager>() }

    /** The source's id (WebView uses it for the source's headers and bot-check prompts) */
    val sourceId: Long? get() = runCatching { manager.idOf(sourceKey) }.getOrNull()

    private val _pageScripts = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /** Scripts for the page, in order (the screen evaluates them on the WebView, main thread) */
    val pageScripts: SharedFlow<String> = _pageScripts.asSharedFlow()

    private val parser = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    /** Reads the code again and lists its places (after a save the ranges after the changed literal move) */
    fun load() {
        screenModelScope.launchIO {
            val info = runCatching { manager.get(sourceKey) }.getOrNull()
            if (info == null) {
                mutableState.update { it.copy(loading = false, error = "This source isn't saved in the Source Lab") }
                return@launchIO
            }
            val code = runCatching { manager.code(sourceKey) }.getOrNull()
            if (code == null) {
                mutableState.update { it.copy(loading = false, name = info.name, error = "Couldn't read the source's code") }
                return@launchIO
            }
            val places = TapFixScanner.scan(code, info.json)
            val start = info.labMeta?.siteUrl?.trim()?.takeIf { it.startsWith("http") }
                ?: info.baseUrl.trim().takeIf { it.startsWith("http") }
            mutableState.update { s ->
                s.copy(
                    loading = false,
                    error = null,
                    name = info.name,
                    json = info.json,
                    startUrl = s.startUrl ?: start,
                    places = places,
                    // the same place keeps its id across a save of it (its literal starts where it did)
                    selectedId = s.selectedId?.takeIf { id -> places.any { it.id == id } },
                )
            }
            AppLog.add(
                'D',
                "lab",
                "Tap to fix: ${info.name}: ${places.count { it.fixable }} places to tap, ${places.count { !it.fixable }} built from pieces",
            )
            pushContext(force = true)
        }
    }

    // ---------- choosing ----------

    /** Opens [id] (null = back to the list): the page measures its selector and waits for a tap */
    fun select(id: String?) {
        mutableState.update { it.copy(selectedId = id, pick = null, old = null, browse = false) }
        val place = state.value.selected
        if (place != null) AppLog.add('D', "lab", "Tap to fix: ${state.value.name}: ${place.label} (line ${place.line}) '${place.selector}'")
        pushContext(force = true)
    }

    fun setBrowse(browse: Boolean) {
        mutableState.update { it.copy(browse = browse) }
        emit(TapFixPageScript.mode(state.value.tapActive))
    }

    fun up() = emit(TapFixPageScript.UP)

    fun down() = emit(TapFixPageScript.DOWN)

    /** Drops the last tap; the page waits for another */
    fun tapAgain() {
        mutableState.update { it.copy(pick = null) }
        emit(TapFixPageScript.CLEAR)
        emit(TapFixPageScript.mode(state.value.tapActive))
    }

    /** Asks the page again whether it is a bot check (after the user passed it in WebView and came back) */
    fun recheckPage() = emit(TapFixPageScript.HELLO)

    fun consumeMessage(text: String) = mutableState.update { if (it.message == text) it.copy(message = null) else it }

    private fun message(text: String) = mutableState.update { it.copy(message = text) }

    private fun emit(script: String) {
        _pageScripts.tryEmit(script)
    }

    /** The page's context for the open place ([force]: set now; else only when the page doesn't have it: a new page) */
    private fun pushContext(force: Boolean) {
        val s = state.value
        val place = s.selected?.takeIf { it.fixable }
        if (place == null) {
            emit(TapFixPageScript.CLEAR)
            emit(TapFixPageScript.mode(false))
            return
        }
        val ctx = contextJson(place)
        emit(TapFixPageScript.mode(s.tapActive))
        emit(if (force) TapFixPageScript.setContext(ctx) else TapFixPageScript.ensure(ctx))
    }

    private fun contextJson(place: TapFixScanner.Place): String {
        val item = place.relativeTo?.takeIf { it.isNotBlank() }
        val oldAlt = runCatching { Derive.browserSelector(place.selector) }.getOrNull()?.takeIf { it.isNotBlank() && it != place.selector }
        val itemAlt = item?.let { runCatching { Derive.browserSelector(it) }.getOrNull() }?.takeIf { it.isNotBlank() && it != item }
        return TapFixPageScript.context(place, item, oldAlt, itemAlt)
    }

    /** The address changed since the page last got its context (an in-page route keeps the old one): set it again */
    @Volatile
    private var contextStale = false

    /**
     * What to evaluate after the page script on a loaded page: the mode, and the place's context (set again after the
     * address changed; else only when the page has none, so a repeated "page finished" keeps the pick)
     */
    fun afterInstall(): String {
        val s = state.value
        val place = s.selected?.takeIf { it.fixable }
        val force = contextStale
        contextStale = false
        return buildString {
            append(TapFixPageScript.mode(s.tapActive)).append(";\n")
            if (place != null) {
                val ctx = contextJson(place)
                append(if (force) TapFixPageScript.setContext(ctx) else TapFixPageScript.ensure(ctx)).append(";\n")
            }
        }
    }

    // ---------- the page ----------

    /** A page finished or the address changed in place; returns whether the page script should be put on it */
    fun onPageLoaded(url: String?, soft: Boolean = false): Boolean {
        val s = state.value
        if (soft && url == s.pageUrl) return false
        if (url != s.pageUrl) {
            contextStale = true
            mutableState.update { it.copy(pageUrl = url, pick = null, old = null) }
            AppLog.add('D', "lab", "Tap to fix page: $url")
        }
        return true
    }

    /** The page script's answer: `{type: ready | measure | pick | log, …}`; runs on a WebView thread */
    fun onReport(text: String) {
        val obj = runCatching { parser.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (obj.str("type")) {
            "ready" -> {
                val bot = obj.bool("botCheck") == true
                val before = state.value.botCheck
                mutableState.update { it.copy(botCheck = bot, pageTitle = obj.str("title")) }
                if (bot && !before) {
                    AppLog.add('I', "lab", "Tap to fix: ${state.value.name}: the site shows a bot check; left to the user in WebView")
                }
                if (bot != before) emit(TapFixPageScript.mode(state.value.tapActive))
            }
            "measure" -> {
                if (obj.str("id") != state.value.selectedId) return
                val m = Measure(
                    ok = obj.bool("ok") == true,
                    count = obj.int("count") ?: 0,
                    cards = obj.int("cards") ?: -1,
                    samples = obj.strings("samples"),
                    approx = obj.bool("approx") == true,
                    empty = obj.bool("empty") == true,
                    error = obj.str("error"),
                )
                mutableState.update { it.copy(old = m) }
            }
            "pick" -> {
                val s = state.value
                if (obj.str("id") != s.selectedId || s.saving) return
                val selector = obj.str("selector")?.trim().orEmpty()
                if (selector.isEmpty()) return
                val p = Pick(
                    selector = selector,
                    count = obj.int("count") ?: 0,
                    cards = obj.int("cards") ?: -1,
                    relative = obj.bool("relative") == true,
                    samples = obj.strings("samples"),
                    weak = obj.bool("weak") == true,
                    note = obj.str("note"),
                    tag = obj.str("tag").orEmpty(),
                    snippet = obj.str("snippet").orEmpty(),
                    via = obj.str("via") ?: "tap",
                )
                mutableState.update { it.copy(pick = p) }
                AppLog.add(
                    'D',
                    "lab",
                    "Tap to fix ${p.via}: ${p.tag} → $selector ×${p.count}" + (if (p.cards >= 0) " of ${p.cards} Items" else "") +
                        (if (p.weak) " (plain path)" else "") + " · \"${p.snippet}\"",
                )
            }
            "log" -> obj.str("text")?.let { AppLog.add('D', "lab", "Tap to fix page: ${it.take(300)}") }
        }
    }

    // ---------- saving ----------

    /**
     * "Use this": the open place's literal gets the new selector (escaped for its quotes, the quotes kept) and the
     * code is saved like the Lab's Save: [JsSourceManager.save] with the source's own name / language / icon /
     * address, so History gets a new version noted "Tap to fix: <label>". The code is read again first; when the
     * literal isn't where it was (edited meanwhile), nothing is saved and the list is made again.
     */
    fun useThis() {
        val s = state.value
        val place = s.selected ?: return
        val pick = s.pick ?: return
        if (s.saving || !place.fixable) return
        val newSelector = pick.selector.trim()
        if (newSelector.isEmpty()) return
        if (newSelector == place.selector.trim()) {
            message("That's what the code says already")
            return
        }
        mutableState.update { it.copy(saving = true) }
        emit(TapFixPageScript.mode(false))
        screenModelScope.launchIO {
            try {
                val info: JsSourceInfo = manager.get(sourceKey) ?: throw IllegalStateException("the source isn't saved any more")
                val code = manager.code(sourceKey) ?: throw IllegalStateException("couldn't read its code")
                val changed = TapFixScanner.replace(code, place, newSelector)
                if (changed == null) {
                    AppLog.add('W', "lab", "Tap to fix: ${info.name}: line ${place.line} changed since the list was made; not saved")
                    mutableState.update { it.copy(saving = false, pick = null) }
                    message("The code changed since this list was made: pick it again")
                    load()
                    return@launchIO
                }
                manager.save(
                    key = info.key,
                    code = changed,
                    json = info.json,
                    animeHint = info.anime,
                    overrides = JsSourceInfo(
                        key = info.key,
                        name = info.name,
                        lang = info.lang,
                        iconUrl = info.iconUrl,
                        baseUrl = info.baseUrl,
                        lab = info.lab,
                    ),
                    note = "Tap to fix: ${place.label}",
                )
                AppLog.add('I', "lab", "Tap to fix: ${info.name}: ${place.label} '${place.selector}' → '$newSelector'")
                mutableState.update { it.copy(saving = false, pick = null, old = null) }
                message("Saved · old version is in History")
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('E', "lab", "Tap to fix: ${s.name}: saving ${place.label} failed: ${e.message ?: e}")
                mutableState.update { it.copy(saving = false) }
                message("Not saved: ${e.message ?: e}")
                emit(TapFixPageScript.mode(state.value.tapActive))
            }
        }
    }

    // ---------- JSON helpers ----------

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }.orEmpty()
}
