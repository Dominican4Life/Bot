package eu.kanade.tachiyomi.ui.sourcelab.newsource

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.jssource.VideoFinder
import eu.kanade.tachiyomi.jssource.arr
import eu.kanade.tachiyomi.jssource.bool
import eu.kanade.tachiyomi.jssource.int
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.BuilderPick
import eu.kanade.tachiyomi.jssource.lab.BuilderSelection
import eu.kanade.tachiyomi.jssource.lab.Derive
import eu.kanade.tachiyomi.jssource.lab.Detector
import eu.kanade.tachiyomi.jssource.lab.Generator
import eu.kanade.tachiyomi.jssource.lab.LabHandoff
import eu.kanade.tachiyomi.jssource.lab.LabMeta
import eu.kanade.tachiyomi.jssource.lab.PickCheck
import eu.kanade.tachiyomi.jssource.lab.PickInfo
import eu.kanade.tachiyomi.jssource.lab.Templates
import eu.kanade.tachiyomi.jssource.obj
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * KMK (r96, Source Lab): the state behind "Build by tapping" (D§2.4): the page in the WebView, the element just
 * tapped and waiting for a label, the labelled picks, and what [Derive] made of them, re-derived after every
 * pick so the reasons are shown live. Done → the generic-list template filled with the derived values, handed to
 * Half A through LabHandoff with the picks in `LabMeta.picks`.
 *
 * r100: the tap helper. Every label is checked ([PickCheck], facts from picker.js) and the verdict kept in
 * `State.checks`; the page colours the pick by it, and on a Warn suggests what the label most likely means (a pick
 * with via "suggest", kept in `State.suggestion`, never pending). Use suggestion / Keep mine / Not this / OK, next /
 * Up / Down (re-labelled through `relabel`) / Test it now (W1's VideoFinder). [extras] is W3's Search / Tabs /
 * Filters / Settings: its values go into the template and force the JS format when used.
 *
 * r101: the title page also offers Cover, Description, Tags, Author and Status (optional: each has a fallback in
 * the template's getDetails; [optionalLabels]). An Item that doesn't hold the Title gets a suggestion computed here
 * ([Derive.itemSuggestion], sent as `suggestSelector`), so it is always offered. The Next pick tells the extras its
 * address (`setNextHref`); the page's search form (`onSearchForm`) and group picks (`onGroupPicked`) go to the extras,
 * and the extras' `groupMode` is sent to the page whenever it changes (and after every injection, see BuilderScreen).
 */
internal class BuilderScreenModel(private val startUrl: String?, anime: Boolean) :
    StateScreenModel<BuilderScreenModel.State>(State(url = startUrl.orEmpty(), anime = anime)) {

    /** The three pages of the flow and the labels each offers */
    enum class Step(val number: Int, val title: String, val labels: List<String>) {
        ListPage(1, "home page", listOf("item", "title", "cover", "next")),
        // r101: + the title page's own fields (optional), after chapter / date / number
        TitlePage(2, "title page", listOf("chapter", "date", "number") + PickCheck.TITLE_FIELDS),
        ChapterPage(3, "chapter page", listOf("image", "video")),
    }

    /** What the picker script sent for one tap, before a label is given */
    @Immutable
    data class RawPick(
        val cssPath: String,
        val likeSelector: String,
        val tag: String,
        val attrs: Map<String, String>,
        val text: String,
        val count: Int,
        val sameParentCount: Int,
        val hrefs: List<String>,
        val attrsAllHttp: List<String>,
        val pageUrl: String,
        /** r99: the element's text (or alt / title) as shown in the hint, first 60 characters */
        val snippet: String = "",
        /** r99: "tap", or "up" / "down" when the hint's Up / Down moved the pick; r100: "suggest" (the helper's) */
        val via: String = "tap",
        // r100: the facts the tap helper checks (PickCheck)
        val imgCount: Int = 0,
        val linkCount: Int = 0,
        val textLen: Int = 0,
        val distinctHrefs: Int = 0,
        val boundByScript: Boolean = false,
        val nextLike: Boolean = false,
        val samples: List<String> = emptyList(),
        /** r101: the page's first date-looking text (the helper's "tap the release date as Date") */
        val pageDate: String = "",
    ) {
        fun info(): PickInfo = PickInfo(
            tag = tag,
            count = count,
            text = text,
            snippet = snippet,
            likeSelector = likeSelector,
            cssPath = cssPath,
            hrefs = hrefs,
            attrs = attrs,
            attrsAllHttp = attrsAllHttp,
            pageUrl = pageUrl,
            imgCount = imgCount,
            linkCount = linkCount,
            textLen = textLen,
            distinctHrefs = distinctHrefs,
            boundByScript = boundByScript,
            nextLike = nextLike,
            samples = samples,
            pageDate = pageDate,
        )
    }

    /** r100: the helper's "Test it now" on a Video pick: running, then what VideoFinder caught or why not */
    @Immutable
    data class VideoTest(val running: Boolean, val ok: Boolean, val text: String)

    @Immutable
    data class State(
        val url: String,
        val anime: Boolean,
        val json: Boolean = false,
        val step: Step = Step.ListPage,
        val loadUrl: String? = null,             // what the WebView should show; loadTick changes on every request
        val loadTick: Int = 0,
        val pageUrl: String? = null,             // what it shows now
        val canGoBack: Boolean = false,          // the WebView's history, for the Back / Forward buttons
        val canGoForward: Boolean = false,
        /** Pick mode: every tap selects an element and nothing navigates; off = browse the site normally */
        val pickMode: Boolean = true,
        val pickerScript: String? = null,
        val pending: RawPick? = null,
        val picks: List<Derive.Picked> = emptyList(),
        val selection: BuilderSelection? = null,
        val busy: String? = null,
        val error: String? = null,
        val delivered: Boolean = false,
        /**
         * r99: "This page is the only episode" (123av: one video per title page, no episode list). The title
         * page is then the single episode: no chapter link is needed, Date / Number picks describe that episode,
         * and the Video frame step runs on the title page itself.
         */
        val singleEpisode: Boolean = false,
        /** r100: the tap each label was given (what the helper checks), by label */
        val raws: Map<String, RawPick> = emptyMap(),
        /** r100: the helper's verdict per labelled pick */
        val checks: Map<String, PickCheck.Result> = emptyMap(),
        /** r100: "Check each pick" (the helper card, the coloured outline and the suggestion) */
        val helper: Boolean = true,
        /** r100: the label whose helper card shows (the one just given); null = no card */
        val helperFor: String? = null,
        /** r100: the element the page suggested for [helperFor] (via "suggest"), never a pending tap */
        val suggestion: RawPick? = null,
        /** r100: the helper card's Up / Down: the element they reach gets this label again */
        val relabel: String? = null,
        /** r100: "OK, next: Cover": the label the Labels card points at next */
        val nextLabel: String? = null,
        /** r100: "Test it now" on the Video pick */
        val videoTest: VideoTest? = null,
    ) {
        val site: String get() = (pageUrl ?: loadUrl ?: url).let { Detector.normalize(it) }
        val host: String get() = site.removePrefix("https://").removePrefix("http://")
        fun pick(label: String): Derive.Picked? = picks.lastOrNull { it.pick.label == label }
        val labels: List<String> get() = if (step == Step.ChapterPage) listOf(if (anime) "video" else "image") else step.labels
    }

    private val context get() = Injekt.get<Application>()

    /** r100 (W3): Search / Tabs / Filters / Settings, recorded while the owner browses the site */
    val extras = BuilderExtrasModel()

    /**
     * r100: scripts for the page (keep / mark / suggest / adopt …), evaluated by the screen in this order. A flow,
     * not state: two scripts sent before a recomposition would otherwise lose the first.
     */
    private val pageScriptFlow = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val pageScripts: SharedFlow<String> = pageScriptFlow.asSharedFlow()

    private fun page(script: String) {
        pageScriptFlow.tryEmit("if (window.__labPicker) { $script }")
    }

    init {
        screenModelScope.launchIO {
            val script = runCatching { context.assets.open("sourcelab/picker.js").bufferedReader().use { it.readText() } }
                .onFailure { AppLog.add('E', "lab", "picker.js not read: ${it.message}") }
                .getOrNull()
            mutableState.update { it.copy(pickerScript = script) }
        }
        if (!startUrl.isNullOrBlank()) load(startUrl)
        // r101 (W4's "Pick a list on the site"): the page's group mode follows the extras' state
        screenModelScope.launchIO {
            extras.state.map { it.groupMode }.distinctUntilChanged().collect { on ->
                page("if (window.__labPicker.groupMode) window.__labPicker.groupMode($on);")
                AppLog.add('D', "lab", "Builder: group pick mode = $on")
            }
        }
        // r101: a step change ends "Pick a list on the site" (it must not stay armed out of sight)
        screenModelScope.launchIO {
            state.map { it.step }.distinctUntilChanged().collect {
                if (extras.state.value.groupMode) extras.setGroupMode(false)
            }
        }
    }

    fun setUrl(url: String) = mutableState.update { it.copy(url = url, error = null) }

    fun setJson(json: Boolean) = mutableState.update { it.copy(json = json) }

    /** r99: the title-page toggle "This page is the only episode" */
    fun setSingleEpisode(single: Boolean) {
        mutableState.update { it.copy(singleEpisode = single) }
        AppLog.add('D', "lab", "Builder: this page is the only episode = $single")
        rederive()
    }

    fun setAnime(anime: Boolean) {
        mutableState.update { it.copy(anime = anime) }
        rederive()
    }

    /** Loads a page in the WebView (the URL box's Go, "Next: open a title", "Next: open a chapter") */
    fun load(url: String) {
        val target = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        mutableState.update {
            it.copy(
                url = target,
                loadUrl = target,
                loadTick = it.loadTick + 1,
                pending = null,
                error = null,
                helperFor = null,
                suggestion = null,
                relabel = null,
            )
        }
    }

    /** From the WebView: the page finished loading, or its history changed (an in-page navigation too) */
    fun onPageLoaded(url: String?, canGoBack: Boolean, canGoForward: Boolean) {
        mutableState.update {
            it.copy(pageUrl = url ?: it.pageUrl, url = url ?: it.url, canGoBack = canGoBack, canGoForward = canGoForward)
        }
        // r100 (W3): a search / filter change shows in the address
        runCatching { extras.onPageLoaded(url) }
            .onFailure { AppLog.add('W', "lab", "extras: page not read: ${it.message}") }
    }

    /** r100 (W3): picker.js's optionsFor answer (LabPicker.options or the console's LABOPTS line) */
    fun onPageOptions(json: String) {
        runCatching { extras.onPageOptions(json) }
            .onFailure { AppLog.add('W', "lab", "extras: options not read: ${it.message}") }
    }

    /** r101: picker.js found the site's search box (LabPicker.form or the console's LABFORM line) */
    fun onSearchForm(json: String) {
        AppLog.add('D', "lab", "picker search form: ${json.take(300)}")
        runCatching { extras.onSearchForm(json) }
            .onFailure { AppLog.add('W', "lab", "extras: search form not read: ${it.message}") }
    }

    /** r101: a group pick (LabPicker.group or the console's LABGROUP line): a whole list for a filter */
    fun onGroupPicked(json: String) {
        AppLog.add('D', "lab", "picker group pick (${json.length} chars)")
        runCatching { extras.onGroupPicked(json) }
            .onFailure { AppLog.add('W', "lab", "extras: group pick not read: ${it.message}") }
    }

    /**
     * KMK (r97): the WebView's own Back (or a link) brought an earlier step's page back: the step follows, so the
     * labels offered are that page's (the list page → step 1, the title page → step 2 while on step 3).
     */
    fun syncStepWithUrl(url: String?) {
        val shown = url?.substringBefore('#')?.trimEnd('/') ?: return
        val s = state.value
        fun pageOf(step: Step): String? =
            s.picks.firstOrNull { it.pick.label in step.labels }?.pick?.pageUrl?.substringBefore('#')?.trimEnd('/')
        val listPage = pageOf(Step.ListPage) ?: startUrl?.trim()?.substringBefore('#')?.trimEnd('/')
        val target = when {
            s.step != Step.ListPage && listPage != null && shown == listPage -> Step.ListPage
            // r99: with "only episode" the video step runs on the title page itself, so that page is no way back
            s.step == Step.ChapterPage && !s.singleEpisode && shown == pageOf(Step.TitlePage) -> Step.TitlePage
            else -> return
        }
        mutableState.update { it.copy(step = target, pending = null, helperFor = null, suggestion = null, relabel = null) }
    }

    /** Pick mode (taps select, nothing navigates) or browse mode (the site works as usual) */
    fun setPickMode(pick: Boolean) = mutableState.update { it.copy(pickMode = pick, pending = if (pick) it.pending else null) }

    /**
     * From the picker script: one tap, as JSON. Through the `LabPicker` bridge (a WebView thread) or, r98, through
     * the console fallback ([viaConsole], the main thread) when the page can't see the bridge. Every tap is logged
     * (`D lab picker tap …`), so a Log without these lines means the page never reached the app.
     */
    fun onPick(json: String, viaConsole: Boolean = false) {
        if (!state.value.pickMode) {
            AppLog.add('D', "lab", "picker tap ignored: Browse mode")
            return
        }
        val o = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        if (o == null) {
            AppLog.add('W', "lab", "picker tap not understood (${json.length} chars${if (viaConsole) ", console" else ""})")
            return
        }
        val raw = RawPick(
            cssPath = o.str("cssPath").orEmpty(),
            likeSelector = o.str("likeSelector") ?: o.str("cssPath").orEmpty(),
            tag = o.str("tag").orEmpty(),
            attrs = o.obj("attrs")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty(),
            text = o.str("text").orEmpty(),
            count = o.int("count") ?: 1,
            sameParentCount = o.int("sameParentCount") ?: 1,
            hrefs = o.arr("hrefs")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            attrsAllHttp = o.arr("attrsAllHttp")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            pageUrl = o.str("pageUrl") ?: state.value.pageUrl.orEmpty(),
            snippet = o.str("snippet").orEmpty(),
            via = o.str("via") ?: "tap",
            imgCount = o.int("imgCount") ?: 0,
            linkCount = o.int("linkCount") ?: 0,
            textLen = o.int("textLen") ?: 0,
            distinctHrefs = o.int("distinctHrefs") ?: 0,
            boundByScript = o.bool("boundByScript") ?: false,
            nextLike = o.bool("nextLike") ?: false,
            samples = o.arr("samples")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            pageDate = o.str("pageDate").orEmpty(),
        )
        // r100: the helper's suggestion is shown by its card, never taken as a tap waiting for a label
        if (raw.via == "suggest") {
            onSuggestion(raw, o.str("forLabel").orEmpty(), o.bool("none") == true)
            return
        }
        if (raw.cssPath.isBlank()) return
        // r99: the text it shows and how it was reached (a tap, or Up / Down in the hint)
        val how = if (raw.via == "tap") "tap" else "tap (${raw.via})"
        val shown = if (raw.snippet.isNotBlank()) " · \"${raw.snippet}\"" else ""
        AppLog.add(
            'D',
            "lab",
            "picker $how ${shortSelector(raw.likeSelector)} ×${raw.count} · ${raw.cssPath}$shown${if (viaConsole) " (console)" else ""}",
        )
        // r100: the helper card's Up / Down: the element reached keeps the label it was moving
        val relabel = state.value.relabel
        if (relabel != null && (raw.via == "up" || raw.via == "down")) {
            applyLabel(relabel, raw)
            return
        }
        mutableState.update { it.copy(pending = raw, relabel = null, helperFor = null, suggestion = null) }
    }

    private fun onSuggestion(raw: RawPick, forLabel: String, none: Boolean) {
        val s = state.value
        if (forLabel.isBlank() || forLabel != s.helperFor) return // a late answer for a card that went
        if (none || raw.cssPath.isBlank() || raw.likeSelector == s.raws[forLabel]?.likeSelector) {
            AppLog.add('D', "lab", "helper suggestion for $forLabel: none")
            mutableState.update { it.copy(suggestion = null) }
            if (!none) page("window.__labPicker.unsuggest();")
            return
        }
        AppLog.add('D', "lab", "helper suggestion for $forLabel: ${raw.likeSelector} ×${raw.count}")
        mutableState.update { it.copy(suggestion = raw) }
    }

    fun onPickerLog(text: String) {
        AppLog.add('D', "lab", "picker $text")
        // r100 fix pass: picker.js logs "up: …" / "down: …" only when it can't move (a move sends a pick instead):
        // the helper's Up / Down then has nothing to re-label, so the next real tap isn't labelled by surprise
        if ((text.startsWith("up: ") || text.startsWith("down: ")) && state.value.relabel != null) {
            AppLog.add('D', "lab", "helper move: nothing to move to, re-label cleared")
            mutableState.update { it.copy(relabel = null) }
        }
    }

    /** Gives the pending tap a label; the same label picked again replaces the earlier pick */
    fun label(label: String) {
        val raw = state.value.pending ?: return
        applyLabel(label, raw)
    }

    /**
     * r100: gives [raw] the [label] (a tap, the helper's suggestion, or the element its Up / Down reached), checks
     * it (PickCheck), and tells the page: keep its outline, colour it by the verdict, and on a Warn look for what
     * the label most likely means (`suggest`).
     */
    private fun applyLabel(label: String, raw: RawPick) {
        val picked = Derive.Picked(
            pick = BuilderPick(
                label = label,
                cssPath = raw.cssPath,
                tag = raw.tag,
                attrs = raw.attrs,
                text = raw.text,
                count = raw.count,
                sameParentCount = raw.sameParentCount,
                pageUrl = raw.pageUrl,
            ),
            likeSelector = raw.likeSelector,
            hrefs = raw.hrefs,
            attrsAllHttp = raw.attrsAllHttp,
        )
        mutableState.update { s ->
            s.copy(
                picks = s.picks.filter { it.pick.label != label } + picked,
                raws = s.raws + (label to raw),
                pending = null,
                helperFor = if (s.helper) label else null,
                suggestion = null,
                relabel = null,
                nextLabel = null,
                videoTest = if (label == "video") null else s.videoTest,
            )
        }
        rederive()
        AppLog.add('I', "lab", "Builder: $label = ${raw.likeSelector} (${raw.count} like it) on ${raw.pageUrl}")
        // r101: the Next link's address tells the extras how this site numbers its pages (page=2, /page/2 …)
        if (label == "next") {
            // The tapped link's own href first: hrefs[0] is the first look-alike (page 1 of a numbered pager)
            val nextPageUrl = raw.pageUrl.ifBlank { state.value.pageUrl.orEmpty() }
            val own = raw.attrs["href"]?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("javascript:", ignoreCase = true) }
                ?.let { h -> nextPageUrl.trim().toHttpUrlOrNull()?.resolve(h)?.toString() ?: h }
            val href = own ?: raw.hrefs.firstOrNull { it.isNotBlank() }
            if (href != null) {
                runCatching { extras.setNextHref(href, nextPageUrl) }
                    .onFailure { AppLog.add('W', "lab", "extras: next link not read: ${it.message}") }
            }
        }
        val s = state.value
        val check = s.checks[label]
        val script = StringBuilder("window.__labPicker.keep(${JsonPrimitive(raw.likeSelector)});")
        if (s.helper && check != null) {
            val failed = check.checks.filterNot { it.ok }.joinToString(" · ") { it.text }.ifBlank { "all checks passed" }
            AppLog.add('D', "lab", "helper $label: ${check.level} · $failed")
            val level = when (check.level) {
                PickCheck.Level.Ok -> "ok"
                PickCheck.Level.Warn -> "warn"
                PickCheck.Level.Info -> "info"
            }
            val name = PickCheck.labelName(label, s.anime)
            script.append(" if (window.__labPicker.mark) window.__labPicker.mark(${JsonPrimitive(level)}, ${JsonPrimitive(name)});")
            if (check.level == PickCheck.Level.Warn) {
                // r101: an Item that doesn't hold the Title / Cover: the card derived from those picks is always
                // offered (the page's own search found nothing the second time in the owner's run)
                val itemCss = if (label == "item" && check.checks.any { !it.ok && it.text.startsWith("doesn't hold") }) {
                    itemSuggestion(s)
                } else {
                    null
                }
                if (itemCss != null) {
                    AppLog.add('D', "lab", "helper suggestion for item from the Title / Cover picks: $itemCss")
                    script.append(
                        " if (window.__labPicker.suggestSelector) window.__labPicker.suggestSelector(" +
                            "${JsonPrimitive(itemCss)}, ${JsonPrimitive(label)});" +
                            " else if (window.__labPicker.suggest) window.__labPicker.suggest(${JsonPrimitive(label)});",
                    )
                } else {
                    script.append(" if (window.__labPicker.suggest) window.__labPicker.suggest(${JsonPrimitive(label)});")
                }
            }
        }
        page(script.toString())
    }

    /** r101: the Item the Title and Cover picks point at (Derive's common ancestor), as a selector for the page */
    private fun itemSuggestion(s: State): String? {
        val title = s.raws["title"]?.let { it.cssPath.ifBlank { it.likeSelector } }
        val cover = s.raws["cover"]?.let { it.cssPath.ifBlank { it.likeSelector } }
        return runCatching { Derive.itemSuggestion(title, cover) }.getOrNull()
    }

    fun dismissPending() = mutableState.update { it.copy(pending = null) }

    fun removePick(label: String) {
        mutableState.update { s ->
            s.copy(
                picks = s.picks.filter { it.pick.label != label },
                raws = s.raws - label,
                helperFor = s.helperFor?.takeIf { it != label },
                suggestion = if (s.helperFor == label) null else s.suggestion,
                videoTest = if (label == "video") null else s.videoTest,
            )
        }
        rederive()
    }

    /** Undo: the last pick goes, or the pending tap */
    fun undo() {
        mutableState.update { s ->
            if (s.pending != null) {
                s.copy(pending = null)
            } else {
                val picks = s.picks.dropLast(1)
                val kept = picks.map { it.pick.label }.toSet()
                s.copy(
                    picks = picks,
                    raws = s.raws.filterKeys { it in kept },
                    helperFor = s.helperFor?.takeIf { it in kept },
                    suggestion = if (s.helperFor?.let { it in kept } == true) s.suggestion else null,
                )
            }
        }
        rederive()
    }

    // ---------- r100: the tap helper ----------

    /** "Check each pick": off = no card, no coloured outline, no suggestion (the checks still mark the rows) */
    fun setHelper(on: Boolean) {
        mutableState.update {
            it.copy(helper = on, helperFor = if (on) it.helperFor else null, suggestion = if (on) it.suggestion else null)
        }
        if (!on) page("window.__labPicker.unsuggest && window.__labPicker.unsuggest();")
        AppLog.add('D', "lab", "helper: check each pick = $on")
    }

    /** "Use suggestion": the suggested element gets the card's label (and becomes the page's current element) */
    fun useSuggestion() {
        val s = state.value
        val label = s.helperFor ?: return
        val raw = s.suggestion ?: return
        page("if (window.__labPicker.adopt) window.__labPicker.adopt();")
        AppLog.add('I', "lab", "helper: used suggestion for $label (${raw.likeSelector} ×${raw.count})")
        applyLabel(label, raw)
    }

    /** "Keep mine" / "OK": the card goes, the pick stays as it is */
    fun dismissHelper() {
        val s = state.value
        val label = s.helperFor ?: return
        if (s.checks[label]?.level == PickCheck.Level.Warn) AppLog.add('D', "lab", "helper $label: kept as picked")
        mutableState.update { it.copy(helperFor = null, suggestion = null, relabel = null) }
        page("window.__labPicker.unsuggest && window.__labPicker.unsuggest();")
    }

    /** "Not this": the pick goes (and its outlines) */
    fun rejectHelperPick() {
        val s = state.value
        val label = s.helperFor ?: return
        val selector = s.raws[label]?.likeSelector
        AppLog.add('D', "lab", "helper $label: removed (Not this)")
        removePick(label)
        mutableState.update { it.copy(helperFor = null, suggestion = null, relabel = null) }
        page("if (window.__labPicker.drop) window.__labPicker.drop(${JsonPrimitive(selector.orEmpty())});")
    }

    /** "OK, next: Cover": the next label of this page still to pick, or the next step when all are */
    fun helperNext() {
        val s = state.value
        val next = nextUnpicked(s)
        mutableState.update { it.copy(helperFor = null, suggestion = null, relabel = null, nextLabel = next) }
        page("window.__labPicker.unsuggest && window.__labPicker.unsuggest();")
        if (next == null && s.step != Step.ChapterPage) nextStep()
    }

    /** The helper card's Up / Down: the element reached comes back as a pick and gets the card's label again */
    fun helperMove(direction: String) {
        val label = state.value.helperFor ?: return
        mutableState.update { it.copy(relabel = label, suggestion = null) }
        page("window.__labPicker.$direction();")
    }

    /** "Test it now" on the Video pick: VideoFinder opens the page in the hidden browser and presses Play */
    fun testVideo() {
        val s = state.value
        val pick = s.raws["video"] ?: return
        if (s.videoTest?.running == true) return
        val pageUrl = pick.pageUrl.ifBlank { s.pageUrl.orEmpty() }
        if (pageUrl.isBlank()) return
        val frame = s.selection?.derived?.get("videoFrame")?.takeIf { it.isNotBlank() }
        val referer = s.site.trimEnd('/') + "/"
        val waiting = VideoTest(running = true, ok = false, text = "Testing: the hidden browser opens the page and presses Play (up to 45 s)…")
        mutableState.update { it.copy(videoTest = waiting) }
        AppLog.add('I', "lab", "helper test video: $pageUrl (frame ${frame ?: "any"})")
        screenModelScope.launchIO {
            val test = try {
                val r = VideoFinder.find(context, pageUrl, frame, referer)
                val seconds = String.format(Locale.ROOT, "%.1f", r.tookMs / 1000.0)
                if (r.videos.isNotEmpty()) {
                    val kinds = r.videos.map { kindName(it.kind) }.distinct().joinToString(" / ")
                    val servers = r.videos.map { it.server }.filter { it.isNotBlank() }.distinct().joinToString(", ")
                    val what = if (r.videos.size == 1) "a video" else "${r.videos.size} videos"
                    val from = if (servers.isNotEmpty()) " · from $servers" else ""
                    VideoTest(false, true, "Found $what in $seconds s · $kinds$from")
                } else {
                    VideoTest(false, false, "No video in $seconds s: ${r.reason}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                VideoTest(false, false, "The test failed: ${e.message ?: e.toString()}")
            }
            AppLog.add('I', "lab", "helper test video: ${test.text}")
            mutableState.update { it.copy(videoTest = test) }
        }
    }

    private fun kindName(kind: String): String = when (kind) {
        "hls" -> "HLS"
        "dash" -> "DASH"
        else -> "file"
    }

    /**
     * The first label of this page's step with no pick yet ("chapter" is not wanted for an only-episode title).
     * r101: the title page's own fields are optional: never "next" (OK, next goes on to the chapter)
     */
    fun nextUnpicked(s: State = state.value): String? =
        s.labels.firstOrNull { label ->
            s.pick(label) == null && !(label == "chapter" && s.singleEpisode) && label !in optionalLabels
        }

    /** "Next: open a title" / "Next: open a chapter": the first link of the pick that leads there */
    fun nextStep() {
        val s = state.value
        when (s.step) {
            Step.ListPage -> {
                val href = (s.pick("title") ?: s.pick("item"))?.hrefs?.firstOrNull()
                if (href == null) {
                    mutableState.update { it.copy(error = "Tap a title link (or a card) first, so the Lab knows which title to open") }
                    return
                }
                mutableState.update { it.copy(step = Step.TitlePage) }
                load(href)
            }
            Step.TitlePage -> {
                // r99: the title page is the only episode: the video frame is picked on this same page
                if (s.singleEpisode) {
                    mutableState.update { it.copy(step = Step.ChapterPage, pending = null) }
                    AppLog.add('D', "lab", "Builder: video step on the title page itself (only episode) ${s.pageUrl.orEmpty()}")
                    return
                }
                val href = s.pick("chapter")?.hrefs?.firstOrNull()
                if (href == null) {
                    mutableState.update { it.copy(error = "Tap a chapter link first, so the Lab knows which chapter to open, or turn on \"This page is the only episode\" when the title has no list") }
                    return
                }
                mutableState.update { it.copy(step = Step.ChapterPage) }
                load(href)
            }
            Step.ChapterPage -> write()
        }
    }

    fun previousStep() {
        val s = state.value
        val previous = when (s.step) {
            Step.ListPage -> return
            Step.TitlePage -> Step.ListPage
            Step.ChapterPage -> Step.TitlePage
        }
        mutableState.update { it.copy(step = previous, pending = null) }
        // r99: from the video step of an only-episode title the page shown is already the title page
        if (s.step == Step.ChapterPage && s.singleEpisode) return
        val back = s.picks.lastOrNull { it.pick.label in previous.labels }?.pick?.pageUrl
        if (back != null) load(back)
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    companion object {
        /** r101: labels nothing needs (the title page's own fields; the template has a fallback for each) */
        val optionalLabels: Set<String> = PickCheck.TITLE_FIELDS.toSet()

        /** The last segment of a selector, as the hint shows it: `div.item > a.title` → `a.title` */
        fun shortSelector(selector: String): String = selector.substringAfterLast(" > ").ifBlank { selector }
    }

    private fun rederive() {
        val s = state.value
        val selection = runCatching { Derive.derive(s.site, s.anime, s.picks, s.singleEpisode) }
            .onFailure { AppLog.add('W', "lab", "Derive failed: ${it.message}") }
            .getOrNull()
        mutableState.update { it.copy(selection = selection) }
        recheck()
    }

    /** r100: the helper's verdict on every labelled pick (they depend on each other: Title vs Item counts …) */
    private fun recheck() {
        mutableState.update { s ->
            val infos = s.picks.mapNotNull { p -> s.raws[p.pick.label]?.let { p.pick.label to it.info() } }.toMap()
            val checks = infos.mapNotNull { (label, info) ->
                runCatching { label to PickCheck.check(label, info, infos - label, s.anime, s.singleEpisode) }
                    .onFailure { AppLog.add('W', "lab", "helper $label: check failed: ${it.message}") }
                    .getOrNull()
            }.toMap()
            s.copy(checks = checks)
        }
    }

    /** Done: the generic-list template filled with the derived values, handed to Half A */
    fun write() {
        val s = state.value
        val selection = s.selection ?: Derive.derive(s.site, s.anime, s.picks, s.singleEpisode)
        // r99: the JSON format has no way to say "the title page is the one episode": written as JS then
        // r99 fix: nor "no chapter link was picked" (its empty CHAPTER selector would fail without saying why): JS too,
        // whose getChapterList throws the clear error
        val noChapter = selection.derived["chapterSelector"].isNullOrBlank() && !s.singleEpisode
        // r100 (W3): tabs, filters, settings and a recorded search exist only in the JS template
        val extrasUsed = runCatching { extras.used }.getOrDefault(false)
        // r101: the title page's own fields (Cover, Description, Tags, Author, Status) are read only by the JS template
        val detailsPicked = s.picks.any { it.pick.label in optionalLabels }
        val json = s.json && !s.singleEpisode && !noChapter && !extrasUsed && !detailsPicked
        if (s.json && detailsPicked) AppLog.add('I', "lab", "Builder: title page fields picked: written as JS (the JSON format reads only og:image / the meta description)")
        if (s.json && extrasUsed) AppLog.add('I', "lab", "Builder: lists and options set: written as JS (the JSON format has no tabs, filters or settings)")
        if (s.json && s.singleEpisode) AppLog.add('I', "lab", "Builder: only-episode source written as JS (the JSON format lists episodes from links)")
        if (s.json && noChapter) AppLog.add('I', "lab", "Builder: no chapter link picked: written as JS (its chapter list says to tap one)")
        mutableState.update { it.copy(busy = "Writing the source…", error = null) }
        screenModelScope.launchIO {
            try {
                val generated = withIOContext {
                    val template = Templates.get(if (json) "generic-list.json" else "generic-list")
                        ?: throw IllegalStateException("The generic-list template is missing from the app")
                    val host = s.host
                    val values = LinkedHashMap<String, String>()
                    values["name"] = host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
                    values["lang"] = "en"
                    values["baseUrl"] = s.site
                    values["iconUrl"] = "https://www.google.com/s2/favicons?domain=$host&sz=128"
                    values.putAll(selection.derived)
                    // r100 (W3): recorded search / tab paths override Derive's guesses; tabs, filters, settings
                    values.putAll(extras.templateValues(s.site))
                    val lab = LabMeta(
                        engine = "generic-list",
                        templateId = template.id,
                        templateVersion = template.version,
                        picks = jsJson.encodeToJsonElement(BuilderSelection.serializer(), selection),
                        detectedAt = System.currentTimeMillis(),
                        siteUrl = s.site,
                    )
                    val videoTodo = selection.derived["videoNote"]?.startsWith("TODO") == true
                    // r99: no chapter link picked (and not an only-episode title): the chapter part can't be written
                    val chapterTodo = selection.derived["chapterSelector"].isNullOrBlank() && !s.singleEpisode
                    Generator.generate(
                        template = template,
                        values = values,
                        lab = lab,
                        drop = setOf(if (s.anime) "getPageList" else "getVideoList"),
                        notWritten = buildSet {
                            if (s.anime && videoTodo) add("getVideoList")
                            if (chapterTodo) add("getChapterList")
                        },
                        anime = s.anime,
                    )
                }
                LabHandoff.deliver(generated)
                AppLog.add('I', "lab", "Built by tapping on ${s.host}: ${s.picks.size} picks, ${selection.derived.size} values derived")
                mutableState.update { it.copy(busy = null, delivered = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('E', "lab", "Build by tapping failed: ${e.message}")
                mutableState.update { it.copy(busy = null, error = e.message ?: e.toString()) }
            }
        }
    }
}
