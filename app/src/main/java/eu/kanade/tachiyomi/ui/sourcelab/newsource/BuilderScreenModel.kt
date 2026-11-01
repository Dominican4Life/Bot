package eu.kanade.tachiyomi.ui.sourcelab.newsource

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.VideoFinder
import eu.kanade.tachiyomi.jssource.arr
import eu.kanade.tachiyomi.jssource.bool
import eu.kanade.tachiyomi.jssource.int
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.BuilderPick
import eu.kanade.tachiyomi.jssource.lab.BuilderSelection
import eu.kanade.tachiyomi.jssource.lab.Derive
import eu.kanade.tachiyomi.jssource.lab.Detector
import eu.kanade.tachiyomi.jssource.lab.ExplainFile
import eu.kanade.tachiyomi.jssource.lab.Generator
import eu.kanade.tachiyomi.jssource.lab.History
import eu.kanade.tachiyomi.jssource.lab.LabHandoff
import eu.kanade.tachiyomi.jssource.lab.LabMeta
import eu.kanade.tachiyomi.jssource.lab.LabStore
import eu.kanade.tachiyomi.jssource.lab.PartIndex
import eu.kanade.tachiyomi.jssource.lab.PickCheck
import eu.kanade.tachiyomi.jssource.lab.PickInfo
import eu.kanade.tachiyomi.jssource.lab.Templates
import eu.kanade.tachiyomi.jssource.lab.labMeta
import eu.kanade.tachiyomi.jssource.lab.toJson
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 *
 * r102: "Tapping for…" replaces Pick / Browse: [arm] a label (the page's next tap is a pick for it, then it disarms;
 * Tags stays armed and each tap adds a [Derive.TagGroup]), nothing armed = the page is a browser and a long press
 * gives a pending pick as before. The step follows the page ([followStep]: a page shaped like the Title / Chapter
 * links, or the list page). Every label can be picked on any step. [editKey] = "Edit by tapping": the saved builder
 * state (`LabMeta.picks` = `{ selection, builder }`, [builderState]) is restored, or for older sources the code's
 * `var X = "…"` values are kept ([State.fromCode]); Save regenerates the template into the same source key.
 *
 * r103: the "Tapping for…" rows are gone: a label is armed from the Labels card only (the screen shows one slim
 * "Tapping for: X · Stop" bar over the page); "Pick a menu on the site" (the extras' `groupMode`) sends
 * `__labPicker.menuMode`. Tags: every tapped row adds its own group (identified by its selector; unlabelled rows are
 * "Tags", "Tags 2"… by their exact row), kept in the page's order (picker.js `docIndex`); a tap in the site's menus
 * makes its group with a helper Warn (r103 fix; a [State.notice] when the helper is off). Title-page fields in a labelled row keep that row's selector ([Derive.fieldSelector]).
 */
internal class BuilderScreenModel(private val startUrl: String?, anime: Boolean, private val editKey: String? = null) :
    StateScreenModel<BuilderScreenModel.State>(State(url = startUrl.orEmpty(), anime = anime, editKey = editKey)) {

    /** The three pages of the flow and the labels each offers */
    enum class Step(val number: Int, val title: String, val labels: List<String>) {
        ListPage(1, "home page", listOf("item", "title", "cover", "next")),
        // r101: + the title page's own fields (optional), after chapter / date / number
        TitlePage(2, "title page", listOf("chapter", "date", "number") + PickCheck.TITLE_FIELDS),
        ChapterPage(3, "chapter page", listOf("image", "video")),
    }

    /** What the picker script sent for one tap, before a label is given (r102: serializable, kept for Edit by tapping) */
    @Immutable
    @Serializable
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
        // r102: the row the element sits in (picker.js `row`): its label, a Jsoup selector for its values, where they
        // go ("tags" / "description"), the first values and how many links / chips it holds
        val rowLabel: String = "",
        val rowKind: String = "",
        val rowSelector: String = "",
        val rowTarget: String = "",
        val rowValues: List<String> = emptyList(),
        val rowChips: Int = 0,
        /** r103: the element's (a row: its label's) place in the page, -1 unknown: Tags groups follow the page's order */
        val docIndex: Int = -1,
        /** r103: an unlabelled tag list's exact row selector (two such lists are two groups) */
        val rowExact: String = "",
        /** r103: the element sits in the site's menus (nav, aside, a sidebar…); r103 fix: Tags warns, never refuses */
        val inMenu: Boolean = false,
        /** r103 fix: every row of this look on the page (picker.js `likeRows`): a restored group finds its own row */
        val likeRows: List<LikeRow> = emptyList(),
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
            rowLabel = rowLabel,
            rowValues = rowValues,
            rowChips = rowChips,
            inMenu = inMenu,
        )
    }

    /** r103 fix: one row of a Tags pick's look on the page: its exact selector, its place, its first texts */
    @Immutable
    @Serializable
    data class LikeRow(val exact: String, val order: Int = -1, val values: List<String> = emptyList())

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
        /**
         * r102: "Tapping for…": the label the page's next tap is for (the page disarms itself after it, except Tags);
         * null = nothing armed, the page works like a browser (a long press still gives a pending pick)
         */
        val armed: String? = null,
        val pickerScript: String? = null,
        /** r102 (W2's learn list): `sourcelab/learn.js`, put on every page after picker.js; null when missing */
        val learnScript: String? = null,
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
        /** r102: the Details rows tapped as Tags, one group each (Derive writes them as `tagGroups`) */
        val tagGroups: List<Derive.TagGroup> = emptyList(),
        /** r102 fix: the tag groups' selectors in the order they were last tapped (Undo removes the last-tapped one) */
        val tagTapOrder: List<String> = emptyList(),
        /** r102: Edit by tapping: the saved source's key (Save writes into it), its name, and a banner line */
        val editKey: String? = null,
        val editName: String? = null,
        val banner: String? = null,
        /**
         * r102: values read from an older source's code (`var ITEM = "…"` → itemSelector …), used for every label not
         * tapped again (its row says "from the code")
         */
        val fromCode: Map<String, String> = emptyMap(),
        /** r103: the edit banner's ✕ was pressed: it stays closed for this builder session */
        val bannerDismissed: Boolean = false,
        /** r103: a short note over the page (a Tags tap in the site's menu, kept), null = none */
        val notice: String? = null,
    ) {
        val site: String get() = (pageUrl ?: loadUrl ?: url).let { Detector.normalize(it) }
        val host: String get() = site.removePrefix("https://").removePrefix("http://")
        fun pick(label: String): Derive.Picked? = picks.lastOrNull { it.pick.label == label }
        val labels: List<String> get() = if (step == Step.ChapterPage) listOf(if (anime) "video" else "image") else step.labels

        /** r102: every label of every step, in the chooser's order (any of them can be picked on any page) */
        val allLabels: List<String> get() = Step.ListPage.labels + Step.TitlePage.labels + listOf(if (anime) "video" else "image")

        /** r102: [label] has a value: picked, or (Edit by tapping, older source) read from the code */
        fun done(label: String): Boolean = pick(label) != null || fromCodeOnly(label) || (label == "tags" && tagGroups.isNotEmpty())

        /** r102: [label] is taken from the code (not tapped again) */
        fun fromCodeOnly(label: String): Boolean = pick(label) == null && codeKeys(label).any { fromCode[it]?.isNotBlank() == true }
    }

    private val context get() = Injekt.get<Application>()

    private val manager: JsSourceManager by lazy { Injekt.get<JsSourceManager>() }

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

    /** r102: a script for the page as is (no picker check): W2's learn list through `extras.runInPage` */
    private fun pageRaw(script: String) {
        if (!pageScriptFlow.tryEmit(script)) AppLog.add('W', "lab", "Builder: a page script was dropped (queue full)")
    }

    init {
        screenModelScope.launchIO {
            val script = runCatching { context.assets.open("sourcelab/picker.js").bufferedReader().use { it.readText() } }
                .onFailure { AppLog.add('E', "lab", "picker.js not read: ${it.message}") }
                .getOrNull()
            // r102 (W2): learn.js presses a list's entries for "Learn this list"; put on every page after picker.js
            val learn = runCatching { context.assets.open("sourcelab/learn.js").bufferedReader().use { it.readText() } }
                .onFailure { AppLog.add('W', "lab", "learn.js not read: ${it.message}") }
                .getOrNull()
            mutableState.update { it.copy(pickerScript = script, learnScript = learn) }
        }
        // r102 (W2): the extras push scripts into the page (learn.js's press) through the same ordered flow
        extras.runInPage = { script -> pageRaw(script) }
        if (editKey != null) screenModelScope.launchIO { loadForEdit(editKey) }
        if (!startUrl.isNullOrBlank()) load(startUrl)
        // r101 (W4's "Pick a list on the site"): the page's group mode follows the extras' state. r103: it is "Pick a
        // menu on the site" now (picker.js menuMode: one filter per menu group); it disarms the armed label
        screenModelScope.launchIO {
            extras.state.map { it.groupMode }.distinctUntilChanged().collect { on ->
                if (on && state.value.armed != null) mutableState.update { it.copy(armed = null, pending = null) }
                page("if (window.__labPicker.menuMode) window.__labPicker.menuMode($on);")
                AppLog.add('D', "lab", "Builder: menu pick mode = $on")
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

    override fun onDispose() {
        extras.runInPage = null
        runCatching { extras.dispose() }.onFailure { AppLog.add('W', "lab", "extras: not disposed: ${it.message}") }
        super.onDispose()
    }

    /**
     * From the WebView: the page finished loading, or its history changed (an in-page navigation too). r102: [url] is
     * the WebView's own current address (BuilderScreen passes `view.url`), so the bar shows the page after Back too;
     * the extras hear every address (`onPageUrl`, W2's learn list) and the step follows the page ([followStep]).
     */
    fun onPageLoaded(url: String?, canGoBack: Boolean, canGoForward: Boolean, finished: Boolean = false) {
        mutableState.update {
            it.copy(pageUrl = url ?: it.pageUrl, url = url ?: it.url, canGoBack = canGoBack, canGoForward = canGoForward)
        }
        // r100 (W3): a search / filter change shows in the address
        runCatching { extras.onPageLoaded(url) }
            .onFailure { AppLog.add('W', "lab", "extras: page not read: ${it.message}") }
        if (url != null) {
            runCatching { extras.onPageUrl(url) }
                .onFailure { AppLog.add('W', "lab", "extras: address not passed on: ${it.message}") }
            // r102 fix: learning presses only on a page that finished loading (not on its first history event)
            if (finished) {
                runCatching { extras.onPageFinished(url) }
                    .onFailure { AppLog.add('W', "lab", "extras: page finish not passed on: ${it.message}") }
            }
        }
        runCatching { followStep(url) }
            .onFailure { AppLog.add('W', "lab", "Builder: step not followed: ${it.message}") }
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

    /** r103: a menu group pick (LabPicker.menu or the console's LABMENU line): "Pick a menu on the site" */
    fun onMenuPicked(json: String) {
        AppLog.add('D', "lab", "picker menu pick (${json.length} chars)")
        runCatching { extras.onMenuPicked(json) }
            .onFailure { AppLog.add('W', "lab", "extras: menu pick not read: ${it.message}") }
    }

    /** r101: a group pick (LabPicker.group or the console's LABGROUP line): a whole list for a filter */
    fun onGroupPicked(json: String) {
        AppLog.add('D', "lab", "picker group pick (${json.length} chars)")
        runCatching { extras.onGroupPicked(json) }
            .onFailure { AppLog.add('W', "lab", "extras: group pick not read: ${it.message}") }
    }

    /**
     * KMK (r97): the WebView's own Back (or a link) brought an earlier step's page back: the step follows. r102: any
     * page ([followStep]): opening a title in the site moves to the title page step (the owner's run stayed on
     * "home page · step 1" after opening a title, so only list labels were offered).
     */
    fun syncStepWithUrl(url: String?) = followStep(url)

    /**
     * r102: the step this page looks like: the list page (where the list labels were tapped, the start address, or
     * the recorded Popular / Latest / tab paths), a chapter (the shape of the Chapter pick's links), or a title (the
     * shape of the Title / Item links, e.g. `/en/v/…`). Moves the step when it differs; never back from the video step
     * of an only-episode title (that step runs on the title page itself).
     */
    fun followStep(url: String?) {
        val shown = url?.let { norm(it) }?.takeIf { it.startsWith("http") } ?: return
        val s = state.value
        val target = stepFor(shown, s) ?: return
        if (target == s.step) return
        if (s.singleEpisode && s.step == Step.ChapterPage && target == Step.TitlePage) return
        AppLog.add('D', "lab", "Builder: page looks like the ${target.title}, step moved (${s.step.number} → ${target.number}) · $shown")
        mutableState.update { it.copy(step = target, pending = null, helperFor = null, suggestion = null, relabel = null) }
    }

    private fun stepFor(shown: String, s: State): Step? {
        val shownUrl = shown.toHttpUrlOrNull() ?: return null
        val path = shownUrl.encodedPath.trimEnd('/')
        fun pagesOf(step: Step): Set<String> =
            s.picks.filter { it.pick.label in step.labels }.map { norm(it.pick.pageUrl) }.filter { it.isNotBlank() }.toSet()
        val listPages = pagesOf(Step.ListPage) + listOfNotNull(startUrl?.let { norm(it) })
        if (shown in listPages) return Step.ListPage
        // The list page with a filter or page number in its query (?year=2025)
        if (listPages.any { it.toHttpUrlOrNull()?.encodedPath?.trimEnd('/') == path }) return Step.ListPage
        val titlePages = pagesOf(Step.TitlePage)
        if (shown in titlePages) return Step.TitlePage
        if (shown in pagesOf(Step.ChapterPage)) return Step.ChapterPage
        // The recorded list paths (Popular, Latest, tabs): the part before {page} / the query
        val ex = runCatching { extras.state.value }.getOrNull()
        val listPaths = listOfNotNull(ex?.popularPath, ex?.latestPath) + ex?.tabs.orEmpty().map { it.path }
        if (listPaths.any { listPathMatches(it, path) }) return Step.ListPage
        val chapterLike = looksLike(path, s.pick("chapter")?.hrefs.orEmpty())
        val titleLike = looksLikeTitle(path, (s.pick("title") ?: s.pick("item"))?.hrefs.orEmpty())
        return when {
            chapterLike && titleLike -> when (s.step) {
                Step.TitlePage, Step.ChapterPage -> null // the same shape: stay where the owner is
                Step.ListPage -> Step.TitlePage
            }
            chapterLike -> Step.ChapterPage
            titleLike -> Step.TitlePage
            else -> null
        }
    }

    /**
     * From the picker script: one tap, as JSON. Through the `LabPicker` bridge (a WebView thread) or, r98, through
     * the console fallback ([viaConsole], the main thread) when the page can't see the bridge. Every tap is logged
     * (`D lab picker tap …`), so a Log without these lines means the page never reached the app.
     */
    fun onPick(json: String, viaConsole: Boolean = false) {
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
            rowLabel = o.str("rowLabel").orEmpty(),
            rowKind = o.obj("row")?.str("kind").orEmpty(),
            rowSelector = o.obj("row")?.str("selector").orEmpty(),
            rowTarget = o.obj("row")?.str("target").orEmpty(),
            rowValues = o.obj("row")?.arr("values")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            rowChips = o.obj("row")?.int("chips") ?: 0,
            docIndex = o.obj("row")?.int("order")?.takeIf { it >= 0 } ?: o.int("docIndex") ?: -1,
            rowExact = o.str("rowExact").orEmpty(),
            inMenu = o.bool("inMenu") == true,
            likeRows = o.arr("likeRows")?.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val exact = row.str("exact")?.trim().orEmpty()
                if (exact.isEmpty()) {
                    null
                } else {
                    LikeRow(exact, row.int("order") ?: -1, row.arr("values")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty())
                }
            }.orEmpty().take(30),
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
        // r102: "Tapping for…": the tap was for an armed label; the page has disarmed itself (Tags stays armed)
        val armedFor = o.str("armed")?.takeIf { it.isNotBlank() && raw.via == "tap" }
        if (armedFor != null && armedFor in state.value.allLabels) {
            if (armedFor != "tags") mutableState.update { it.copy(armed = null) }
            AppLog.add('D', "lab", "Builder: tap for $armedFor${if (raw.rowLabel.isNotBlank()) " · row \"${raw.rowLabel}\"" else ""}")
            applyLabel(armedFor, raw)
            return
        }
        mutableState.update { it.copy(pending = raw, relabel = null, helperFor = null, suggestion = null) }
    }

    /**
     * r102: "Tapping for…": [label] = the page's next tap is a pick for it (Tags: every tap, until tapped off);
     * null = nothing armed (the page is a browser; a long press still picks). Arming ends "List for a filter".
     * BuilderScreen sends the state to the page (`__labPicker.arm`) whenever it changes and after every injection.
     */
    fun arm(label: String?) {
        val s = state.value
        val next = label?.takeIf { it in s.allLabels }
        if (next != null && runCatching { extras.state.value.groupMode }.getOrDefault(false)) extras.setGroupMode(false)
        mutableState.update { it.copy(armed = next, pending = if (next != null) null else it.pending) }
        if (s.armed != next) AppLog.add('D', "lab", "Builder: tapping for ${next ?: "nothing (the page works as a browser)"}")
    }

    /** r102: the "List for a filter" chip: nothing armed, the next tap picks a whole list (W2's group pick) */
    fun toggleListForFilter() {
        val on = !runCatching { extras.state.value.groupMode }.getOrDefault(false)
        if (on) mutableState.update { it.copy(armed = null, pending = null) }
        extras.setGroupMode(on)
    }

    fun dismissBanner() = mutableState.update { it.copy(banner = null, bannerDismissed = true) }

    fun dismissNotice() = mutableState.update { it.copy(notice = null) }

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
        if (text.startsWith("learn:")) runCatching { extras.onLearnLog(text) } // r102 fix: learn.js's press outcome
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
        // r103 fix: a Tags tap (or a long press named Tags) in the site's own menus makes its group like any other;
        // the helper warns ("This looks like the site's menu…": Keep mine / Not this). r103 refused it, and the
        // owner's WordPress tags (footer.entry-footer) and other normal title layouts were refused with it
        val menuTags = label == "tags" && raw.inMenu
        if (menuTags) {
            AppLog.add('I', "lab", "Builder: Tags tap in what looks like the site's menu, kept with a warning (${raw.likeSelector} · \"${raw.snippet.take(40)}\")")
        }
        // r103: Author / Status (and a plain-text Description row) keep their row's label-anchored selector
        val rowSelector = if (label == "author" || label == "status" || (label == "description" && raw.rowTarget == "description")) {
            raw.rowSelector
        } else {
            ""
        }
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
            rowSelector = rowSelector,
        )
        // r102: Tags is a group per Details row (Cast, Maker…). r103: every tap adds a group (an unlabelled row too)
        val tagged = if (label == "tags") tagGroupOf(raw, state.value.tagGroups) else null
        val group = tagged?.first
        mutableState.update { s ->
            s.copy(
                picks = s.picks.filter { it.pick.label != label } + picked,
                raws = s.raws + (label to raw),
                tagGroups = if (tagged != null) inPageOrder(withGroup(tagged.second, tagged.first)) else s.tagGroups,
                // the helper card warns; with "Check each pick" off a notice says it instead
                notice = if (menuTags && !s.helper) {
                    "This looks like the site's menu, not this title's tags — kept. Undo removes it."
                } else {
                    null
                },
                tagTapOrder = if (group != null) s.tagTapOrder.filter { it != group.selector } + group.selector else s.tagTapOrder,
                fromCode = s.fromCode - codeKeys(label),
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
        if (group != null) {
            AppLog.add('I', "lab", "Builder: tags group \"${group.name}\" = ${group.selector} (${group.target}; ${group.values.take(3).joinToString(", ")})")
        }
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
            // r103 fix: the menu warning asks Keep mine / Not this (no suggestion, which would swap Not this away)
            if (check.level == PickCheck.Level.Warn && !menuTags) {
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

    /**
     * r102: the group a Tags tap makes: its row (picker.js). r103: a tap outside a labelled row is a group too (the
     * owner's r102 card showed "1 group: Tags" after several taps): its tags' look-alike selector, or its exact row
     * when another group already reads that look; named "Tags", "Tags 2"… so names stay distinct
     */
    private fun tagGroupOf(raw: RawPick, groups: List<Derive.TagGroup>): Pair<Derive.TagGroup, List<Derive.TagGroup>> {
        if (raw.rowSelector.isNotBlank()) {
            val same = groups.firstOrNull { it.selector == raw.rowSelector }
            val group = Derive.TagGroup(
                name = same?.name ?: distinctName(raw.rowLabel.ifBlank { "Tags" }, groups),
                selector = raw.rowSelector,
                target = if (raw.rowTarget == "description") "description" else "tags",
                values = raw.rowValues,
                order = raw.docIndex,
            )
            return group to groups
        }
        val one = raw.tag == "a" || raw.count >= 2
        val like = Derive.clean(if (one) raw.likeSelector else "${raw.cssPath.ifBlank { raw.likeSelector }} a").first
        val exact = raw.rowExact.takeIf { it.isNotBlank() }?.let { Derive.clean(it).first }.orEmpty()
        // The same row again (its exact place, or the same look with the same place) keeps its group
        val again = groups.firstOrNull { g ->
            (exact.isNotEmpty() && (g.selector == exact || g.exact == exact)) || (g.selector == like && g.order == raw.docIndex)
        }
        if (again != null) {
            return again.copy(values = raw.rowValues.ifEmpty { raw.samples }, order = raw.docIndex) to groups
        }
        // The same look as another row's group: both are read by their exact row from now on
        val owner = groups.firstOrNull { it.selector == like }
        var list = groups
        var selector = like
        if (owner != null && exact.isNotEmpty()) {
            selector = exact
            if (owner.exact.isNotBlank()) {
                list = groups.map { if (it === owner) it.copy(selector = it.exact) else it }
            } else {
                // r103 fix: a group restored from a saved build (no exact row): it read every row of this look, the
                // new one's too (read twice). It gets its own row on this page (its saved texts, else the first
                // other row); when its row is this tap's, this tap is that group again
                val rows = raw.likeRows.mapNotNull { r ->
                    Derive.clean(r.exact).first.takeIf { it.isNotEmpty() }?.let { it to r }
                }.distinctBy { it.first }
                val mine = restoredRowOf(owner, rows)
                if (rows.isNotEmpty() && (rows.size == 1 || mine?.first == exact)) {
                    AppLog.add('D', "lab", "Builder: tags group \"${owner.name}\" (restored) is this row: $exact")
                    val again2 = owner.copy(selector = exact, exact = exact, values = raw.rowValues.ifEmpty { raw.samples }, order = raw.docIndex)
                    return again2 to groups.filter { it !== owner }
                }
                val other = mine ?: rows.filter { it.first != exact }.minByOrNull { if (it.second.order < 0) Int.MAX_VALUE else it.second.order }
                if (other != null) {
                    AppLog.add('D', "lab", "Builder: tags group \"${owner.name}\" (restored) now reads its own row ${other.first}${if (mine == null) " (guessed: the first other row)" else ""}")
                    list = groups.map {
                        if (it === owner) it.copy(selector = other.first, exact = other.first, order = other.second.order) else it
                    }
                }
            }
        }
        val group = Derive.TagGroup(
            name = distinctName("Tags", groups),
            selector = selector,
            target = "tags",
            values = raw.rowValues.ifEmpty { raw.samples },
            order = raw.docIndex,
            exact = exact,
        )
        return group to list
    }

    /** r103 fix: the row (of this tap's look) whose texts best match a restored group's saved values; null if none */
    private fun restoredRowOf(owner: Derive.TagGroup, rows: List<Pair<String, LikeRow>>): Pair<String, LikeRow>? {
        val saved = owner.values.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        if (saved.isEmpty()) return null
        var best: Pair<String, LikeRow>? = null
        var bestScore = 0
        for (row in rows) {
            val score = row.second.values.count { it.trim().lowercase(Locale.ROOT) in saved }
            if (score > bestScore) {
                best = row
                bestScore = score
            }
        }
        return best
    }

    /** r103: [base], or "base 2", "base 3"… when another group has that name */
    private fun distinctName(base: String, groups: List<Derive.TagGroup>): String {
        val taken = groups.map { it.name.lowercase(Locale.ROOT) }.toSet()
        if (base.lowercase(Locale.ROOT) !in taken) return base
        var n = 2
        while ("${base.lowercase(Locale.ROOT)} $n" in taken) n++
        return "$base $n"
    }

    /** r102: re-tapping the same row replaces its group. r103: the same row = the same selector (never the name) */
    private fun withGroup(groups: List<Derive.TagGroup>, group: Derive.TagGroup): List<Derive.TagGroup> {
        val i = groups.indexOfFirst { it.selector == group.selector }
        if (i < 0) return groups + group
        // The owner's name and target for that row stay; the values and place are the new tap's
        return groups.toMutableList().also { it[i] = group.copy(name = groups[i].name, target = groups[i].target) }
    }

    /**
     * r103: the groups in the order their rows appear on the page. r103 fix: groups without a place (restored from
     * a build saved before places were kept) come first in their saved order; the others follow by place (a stable
     * sort, so a new group no longer jumps ahead of the restored ones)
     */
    private fun inPageOrder(groups: List<Derive.TagGroup>): List<Derive.TagGroup> =
        groups.sortedBy { if (it.order < 0) -1 else it.order }

    /** r102: the Tags card's ⋮ → Rename */
    fun renameTagGroup(index: Int, name: String) {
        val clean = name.trim().replace(":", "").take(30)
        if (clean.isEmpty()) return
        mutableState.update { s ->
            s.copy(tagGroups = s.tagGroups.mapIndexed { i, g -> if (i == index) g.copy(name = clean) else g })
        }
        AppLog.add('D', "lab", "Builder: tags group ${index + 1} renamed \"$clean\"")
        rederive()
    }

    /** r102: the Tags card's ⋮ → Remove (the last one gone = no Tags pick) */
    fun removeTagGroup(index: Int) {
        val s = state.value
        val gone = s.tagGroups.getOrNull(index) ?: return
        val left = s.tagGroups.filterIndexed { i, _ -> i != index }
        AppLog.add('D', "lab", "Builder: tags group \"${gone.name}\" removed")
        // r103: every Tags tap is a group now: the last one gone = no Tags pick
        if (left.isEmpty() && (s.raws["tags"] != null || s.pick("tags") != null)) {
            removePick("tags")
        } else {
            mutableState.update { it.copy(tagGroups = left) }
            rederive()
        }
    }

    /** r102: the Tags card's target chips: "tags", "description" or "both" */
    fun setTagGroupTarget(index: Int, target: String) {
        val t = target.takeIf { it == "description" || it == "both" } ?: "tags"
        mutableState.update { s ->
            s.copy(tagGroups = s.tagGroups.mapIndexed { i, g -> if (i == index) g.copy(target = t) else g })
        }
        AppLog.add('D', "lab", "Builder: tags group ${index + 1} → $t")
        rederive()
    }

    fun dismissPending() = mutableState.update { it.copy(pending = null) }

    fun removePick(label: String) {
        mutableState.update { s ->
            s.copy(
                picks = s.picks.filter { it.pick.label != label },
                raws = s.raws - label,
                tagGroups = if (label == "tags") emptyList() else s.tagGroups,
                fromCode = s.fromCode - codeKeys(label),
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
            } else if (s.picks.lastOrNull()?.pick?.label == "tags" && s.tagGroups.size > 1) {
                // r102: Tags: the last group goes, the others stay. r102 fix: the last-tapped one (a re-tapped row
                // keeps its place in the list), else the last in the list
                val lastTapped = s.tagTapOrder.lastOrNull { sel -> s.tagGroups.any { it.selector == sel } }
                val at = lastTapped?.let { sel -> s.tagGroups.indexOfLast { it.selector == sel } } ?: -1
                val left = if (at >= 0) s.tagGroups.filterIndexed { i, _ -> i != at } else s.tagGroups.dropLast(1)
                s.copy(tagGroups = left, tagTapOrder = s.tagTapOrder.filter { it != lastTapped })
            } else {
                val picks = s.picks.dropLast(1)
                val kept = picks.map { it.pick.label }.toSet()
                s.copy(
                    picks = picks,
                    raws = s.raws.filterKeys { it in kept },
                    tagGroups = if ("tags" in kept) s.tagGroups else emptyList(),
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
        // r103 fix: Tags with other groups: only the group this tap made goes (a menu tap's "Not this")
        if (label == "tags" && s.tagGroups.size > 1) {
            val lastTapped = s.tagTapOrder.lastOrNull { sel -> s.tagGroups.any { it.selector == sel } }
            val at = lastTapped?.let { sel -> s.tagGroups.indexOfLast { it.selector == sel } } ?: -1
            if (at >= 0) {
                AppLog.add('D', "lab", "helper tags: group \"${s.tagGroups[at].name}\" removed (Not this)")
                mutableState.update {
                    it.copy(
                        tagGroups = it.tagGroups.filterIndexed { i, _ -> i != at },
                        tagTapOrder = it.tagTapOrder.filter { sel -> sel != lastTapped },
                        // the check was this tap's: the other groups keep no verdict of their own
                        raws = it.raws - "tags",
                        helperFor = null,
                        suggestion = null,
                        relabel = null,
                    )
                }
                rederive()
                page("if (window.__labPicker.drop) window.__labPicker.drop(${JsonPrimitive(selector.orEmpty())});")
                return
            }
        }
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
                // KMK (r103 fix pass): a test always loads the page (never a result kept from an earlier find)
                val r = VideoFinder.find(context, pageUrl, frame, referer, fresh = true)
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
            !s.done(label) && !(label == "chapter" && s.singleEpisode) && label !in optionalLabels
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

    // ---------- r102: Edit by tapping ----------

    /**
     * The builder's own state for `LabMeta.picks`: `{ "selection": BuilderSelection, "builder": { version, raws, picks,
     * singleEpisode, anime, json, tagGroups, extras, fromCode } }` (r96–r101 stored the selection alone; [loadForEdit]
     * reads both shapes).
     */
    private fun builderState(s: State, selection: BuilderSelection): JsonElement {
        val extrasSnapshot = runCatching { extras.snapshot() }
            .onFailure { AppLog.add('W', "lab", "Builder: lists and options not kept for editing: ${it.message}") }
            .getOrNull()
        val builder = LinkedHashMap<String, JsonElement>()
        builder["version"] = JsonPrimitive(1)
        builder["raws"] = JsonObject(s.raws.mapValues { jsJson.encodeToJsonElement(RawPick.serializer(), it.value) })
        builder["picks"] = JsonArray(s.picks.map { jsJson.encodeToJsonElement(Derive.Picked.serializer(), it) })
        builder["singleEpisode"] = JsonPrimitive(s.singleEpisode)
        builder["anime"] = JsonPrimitive(s.anime)
        builder["json"] = JsonPrimitive(s.json)
        builder["tagGroups"] = JsonArray(s.tagGroups.map { jsJson.encodeToJsonElement(Derive.TagGroup.serializer(), it) })
        if (extrasSnapshot != null) builder["extras"] = extrasSnapshot
        if (s.fromCode.isNotEmpty()) builder["fromCode"] = JsonObject(s.fromCode.mapValues { JsonPrimitive(it.value) })
        return JsonObject(
            mapOf(
                "selection" to jsJson.encodeToJsonElement(BuilderSelection.serializer(), selection),
                "builder" to JsonObject(builder),
            ),
        )
    }

    /**
     * Opens [key] for editing: its saved builder state (picks, tag groups, lists and options) when it has one; else the
     * r96–r101 selection's picks (no page facts) and the code's `var X = "…"` values, said in the banner.
     */
    private fun loadForEdit(key: String) {
        val info = manager.get(key)
        if (info == null) {
            AppLog.add('W', "lab", "Builder: edit $key: the source is gone")
            mutableState.update {
                it.copy(banner = if (it.bannerDismissed) null else "This source is gone: what you pick here is written as a new source.", editKey = null)
            }
            return
        }
        val code = runCatching { manager.code(key) }.getOrNull().orEmpty()
        val stored = info.labMeta?.picks
        val obj = stored as? JsonObject
        val builder = obj?.obj("builder")
        val banner: String
        if (builder != null) {
            val raws = builder.obj("raws")?.mapNotNull { (label, el) ->
                runCatching { label to jsJson.decodeFromJsonElement(RawPick.serializer(), el) }.getOrNull()
            }?.toMap().orEmpty()
            val picks = builder.arr("picks")?.mapNotNull { el ->
                runCatching { jsJson.decodeFromJsonElement(Derive.Picked.serializer(), el) }.getOrNull()
            }.orEmpty()
            val groups = builder.arr("tagGroups")?.mapNotNull { el ->
                runCatching { jsJson.decodeFromJsonElement(Derive.TagGroup.serializer(), el) }.getOrNull()
            }.orEmpty()
            val fromCode = builder.obj("fromCode")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
            builder["extras"]?.let { snapshot ->
                runCatching { extras.restore(snapshot) }
                    .onFailure { AppLog.add('W', "lab", "Builder: lists and options not restored: ${it.message}") }
            }
            mutableState.update {
                it.copy(
                    raws = raws,
                    picks = picks,
                    tagGroups = groups,
                    fromCode = fromCode,
                    singleEpisode = builder.bool("singleEpisode") ?: false,
                    anime = builder.bool("anime") ?: info.anime,
                    json = builder.bool("json") ?: info.json,
                )
            }
            banner = if (editedByHand(key, code)) {
                // r102 fix: Save to this source regenerates from the picks: the hand edits would go (History keeps them)
                AppLog.add('I', "lab", "Builder: edit ${info.name} ($key): its code was edited by hand after it was built")
                "This source was edited by hand after it was built; Save to this source rewrites it from your picks " +
                    "(the current code stays in History)"
            } else {
                "Editing ${info.name}: your ${picks.size} picks${if (groups.isNotEmpty()) ", ${groups.size} tag groups" else ""} " +
                    "and lists are back. Change what's wrong, then Save to this source."
            }
            AppLog.add('I', "lab", "Builder: edit ${info.name} ($key): builder state restored (${picks.size} picks, ${groups.size} tag groups)")
        } else {
            val selection = (obj?.get("selection") ?: stored)?.let { el ->
                runCatching { jsJson.decodeFromJsonElement(BuilderSelection.serializer(), el) }.getOrNull()
            }
            // The code is what runs now (maybe edited by hand since): its values win; the r96–r101 picks (no page
            // facts) come back only for labels the code doesn't give (a JSON source: all of them)
            val fromCode = if (info.json) emptyMap() else codeValues(code)
            val given = fromCode.filterValues { it.isNotBlank() }.keys.mapNotNull { labelOfKey(it) }.toSet()
            val picks = selection?.picks.orEmpty().filter { it.label !in given }.map { p ->
                Derive.Picked(pick = p, likeSelector = p.cssPath.replace(POSITION, ""), hrefs = emptyList(), attrsAllHttp = emptyList())
            }
            val kept = fromCode
            val single = fromCode["singleEpisode"] == "true"
            mutableState.update {
                it.copy(picks = picks, fromCode = kept, singleEpisode = single, anime = info.anime, json = info.json)
            }
            banner = when {
                picks.isNotEmpty() && kept.isNotEmpty() ->
                    "Editing ${info.name}, made before the builder kept its taps: ${picks.size} picks are back without their page facts, " +
                        "the rest is read from its code (rows say “from the code”). Tap a label again to change it."
                picks.isNotEmpty() ->
                    "Editing ${info.name}: ${picks.size} picks are back without their page facts. Tap a label again to change it."
                kept.isNotEmpty() ->
                    "Editing ${info.name}: its values were read from its code (rows say “from the code”). Tap a label again to change it."
                else -> "Editing ${info.name}: nothing could be read from it. Pick what it needs, then Save to this source."
            }
            AppLog.add(
                'I',
                "lab",
                "Builder: edit ${info.name} ($key): no builder state; ${picks.size} saved picks, ${kept.size} values from the code (${kept.keys.joinToString(", ")})",
            )
        }
        // r103: a banner closed before this finished stays closed
        mutableState.update { it.copy(editName = info.name, banner = if (it.bannerDismissed) null else banner) }
        rederive()
    }

    /**
     * r102 fix: whether [code] (the source's code now) differs from what the builder last wrote from the saved state:
     * the newest History version the builder (or a template update) wrote — note "Generated from …", "Edited by
     * tapping" or "Updated template …" — holds the code that state generated. Its code differing from [code], or that
     * version updated in place since, means hand edits. No such version left (history lost): not said.
     */
    private fun editedByHand(key: String, code: String): Boolean = runCatching {
        val index = History.index(key)
        val built = index.versions.lastOrNull { v ->
            val note = v.note.removeSuffix(History.UPDATED_SUFFIX)
            note.startsWith("Generated from") || note == "Edited by tapping" || note.startsWith("Updated template")
        } ?: return@runCatching false
        if (built.note.endsWith(History.UPDATED_SUFFIX)) return@runCatching true
        val builtCode = History.code(key, built.n) ?: return@runCatching false
        builtCode.trimEnd() != code.trimEnd()
    }.getOrDefault(false)

    /**
     * Save to this source: the regenerated code under the same key (a new History version; the id, library and
     * downloads stay), keeping the source's own `id` line and its name, language and icon.
     */
    private suspend fun saveInto(info: JsSourceInfo, generated: eu.kanade.tachiyomi.jssource.lab.GeneratedSource) {
        var code = generated.code
        info.customId?.let { code = LabStore.injectId(code, generated.json, it) }
        val saved = manager.save(
            key = info.key,
            code = code,
            json = generated.json,
            animeHint = generated.animeHint,
            overrides = JsSourceInfo(
                key = info.key,
                name = info.name,
                lang = info.lang,
                iconUrl = info.iconUrl,
                baseUrl = info.baseUrl,
                lab = generated.lab.toJson(),
            ),
            note = "Edited by tapping",
        )
        if (generated.explain.isNotEmpty()) {
            runCatching {
                LabStore.writeExplain(
                    info.key,
                    ExplainFile(
                        entries = PartIndex.of(code, generated.json, generated.explain),
                        templateId = generated.lab.templateId,
                        templateVersion = generated.lab.templateVersion,
                    ),
                )
            }.onFailure { AppLog.add('W', "lab", "Builder: explain.json of ${info.key} not written: ${it.message}") }
        }
        LabStore.invalidate(info.key)
        if (info.customId != null && saved.customId != info.customId) {
            AppLog.add('W', "lab", "Builder: ${info.name} had id ${info.customId}, the saved code has ${saved.customId}")
        }
        AppLog.add('I', "lab", "Builder: edited ${saved.name} (${info.key})")
    }

    companion object {
        /** r102: placeholder ← the generic-list code's `var NAME` (what an older source's code tells the builder) */
        val CODE_VARS: Map<String, String> = linkedMapOf(
            "POPULAR_PATH" to "popularPath",
            "LATEST_PATH" to "latestPath",
            "SEARCH_PATH" to "searchPath",
            "ITEM" to "itemSelector",
            "TITLE" to "titleSelector",
            "COVER" to "coverSelector",
            "COVER_ATTR" to "coverAttr",
            "NEXT" to "nextSelector",
            "CHAPTER" to "chapterSelector",
            "CHAPTER_DATE" to "dateSelector",
            "SINGLE_EPISODE" to "singleEpisode",
            "EPISODE_NUMBER" to "numberSelector",
            "IMAGE" to "imageSelector",
            "IMAGE_ATTR" to "imageAttr",
            "VIDEO_FRAME" to "videoFrame",
            "DETAILS_COVER" to "detailsCover",
            "DETAILS_COVER_ATTR" to "detailsCoverAttr",
            "DESCRIPTION" to "descriptionSelector",
            "TAGS" to "tagsSelector",
            "AUTHOR" to "authorSelector",
            "STATUS" to "statusSelector",
            "TAB_POPULAR_NAME" to "tabPopularName",
            "TAB_LATEST_NAME" to "tabLatestName",
            "TABS" to "tabsList",
            "FILTERS" to "filtersList",
            "SETTINGS" to "settingsList",
            "TAG_GROUPS" to "tagGroups",
        )

        /** r102: values that are lists / names (not a label's): kept when the extras recorded nothing for them */
        val LIST_KEYS = setOf("tabPopularName", "tabLatestName", "tabsList", "filtersList", "settingsList")

        /** r102: the placeholders each label gives (the first is the one its row checks) */
        private val LABEL_KEYS: Map<String, List<String>> = mapOf(
            "item" to listOf("itemSelector"),
            "title" to listOf("titleSelector"),
            "cover" to listOf("coverSelector", "coverAttr"),
            "next" to listOf("nextSelector"),
            "chapter" to listOf("chapterSelector"),
            "date" to listOf("dateSelector"),
            "number" to listOf("numberSelector"),
            "image" to listOf("imageSelector", "imageAttr"),
            "video" to listOf("videoFrame"),
            "bigCover" to listOf("detailsCover", "detailsCoverAttr"),
            "description" to listOf("descriptionSelector"),
            "tags" to listOf("tagsSelector", "tagGroups"),
            "author" to listOf("authorSelector"),
            "status" to listOf("statusSelector"),
        )

        fun codeKey(label: String): String? = LABEL_KEYS[label]?.firstOrNull()

        fun codeKeys(label: String): List<String> = LABEL_KEYS[label].orEmpty()

        fun labelOfKey(key: String): String? = LABEL_KEYS.entries.firstOrNull { key in it.value }?.key

        private val POSITION = Regex(""":nth-of-type\(\d+\)""")

        // No braces in these patterns (ICU): `var NAME = "…";` and `var NAME = JSON.parse("…" || "[]");`
        private val STRING_VAR = Regex("""(?m)^var ([A-Z_]+) = "((?:[^"\\]|\\.)*)"""")
        private val JSON_VAR = Regex("""(?m)^var ([A-Z_]+) = JSON\.parse\("((?:[^"\\]|\\.)*)"""")

        /**
         * r102: an older generic-list source's values from its code (`var ITEM = "div.card";` → itemSelector), the
         * string literals read back as JSON strings (the Generator writes only \\ and \" escapes)
         */
        fun codeValues(code: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            fun read(raw: String): String =
                runCatching { jsJson.decodeFromString(String.serializer(), "\"$raw\"") }.getOrDefault(raw)
            for (m in JSON_VAR.findAll(code)) {
                val key = CODE_VARS[m.groupValues[1]] ?: continue
                out[key] = read(m.groupValues[2])
            }
            for (m in STRING_VAR.findAll(code)) {
                val key = CODE_VARS[m.groupValues[1]] ?: continue
                if (key !in out) out[key] = read(m.groupValues[2])
            }
            // Only real values: a `{{…}}` left in an untouched template is no value
            return out.filterValues { !it.contains("{{") }
        }

        /** r102: an address without its #fragment and trailing slash, for comparing pages */
        fun norm(url: String): String = url.trim().substringBefore('#').trimEnd('/')

        /**
         * r102: [path] (a page's path) has the shape of [hrefs]' paths: the same number of segments and the same
         * segments before the last (`/en/v/…`), or one of them exactly
         */
        fun looksLike(path: String, hrefs: List<String>): Boolean {
            val page = path.trim('/').split('/')
            if (page.isEmpty() || page.first().isEmpty()) return false
            return hrefs.asSequence().mapNotNull { it.toHttpUrlOrNull()?.encodedPath?.trim('/')?.split('/') }
                .filter { it.isNotEmpty() && it.first().isNotEmpty() }
                .take(30)
                .any { h -> h == page || (h.size >= 2 && h.size == page.size && h.dropLast(1) == page.dropLast(1)) }
        }

        /**
         * r102 fix: [looksLike] for a Title page, stricter: list pages beside the titles (`/en/new`, `/en/release` next
         * to `/en/abc-123`) mustn't move the step. The page is one of the hrefs, or has their shape and its last segment
         * looks like a slug (a digit or a hyphen in it), or that segment's letter/digit shape is exactly the last
         * segment's shape of ≥ 2 hrefs (`naruto` among `bleach`, `one`… on a site whose title slugs are one word)
         */
        fun looksLikeTitle(path: String, hrefs: List<String>): Boolean {
            if (!looksLike(path, hrefs)) return false
            val page = path.trim('/').split('/')
            val paths = hrefs.asSequence().mapNotNull { it.toHttpUrlOrNull()?.encodedPath?.trim('/')?.split('/') }
                .filter { it.isNotEmpty() && it.first().isNotEmpty() }
                .take(30)
                .toList()
            if (paths.any { it == page }) return true
            val last = page.last()
            if (last.any { it.isDigit() } || '-' in last) return true
            val shape = segmentShape(last)
            return paths.count { it.size == page.size && it.dropLast(1) == page.dropLast(1) && segmentShape(it.last()) == shape } >= 2
        }

        /** r102 fix: a path segment's shape: letters → a, digits → 9, runs collapsed (`abc-123` → `a-9`) */
        fun segmentShape(segment: String): String {
            val out = StringBuilder()
            for (c in segment) {
                val k = when {
                    c.isLetter() -> 'a'
                    c.isDigit() -> '9'
                    else -> c
                }
                if (out.isEmpty() || out.last() != k || (k != 'a' && k != '9')) out.append(k)
            }
            return out.toString()
        }

        /** r102: a recorded list path (`/en/hot?page={page}`, maybe a full address) is this page's [path] */
        fun listPathMatches(listPath: String, path: String): Boolean {
            val p = listPath.trim()
            if (p.isEmpty()) return false
            val pathOnly = (p.toHttpUrlOrNull()?.encodedPath ?: p.substringBefore('?')).substringBefore('{').trimEnd('/')
            if (pathOnly.isEmpty()) return false
            return if ('{' in p.substringBefore('?')) path.startsWith(pathOnly) else path == pathOnly
        }

        /** r101: labels nothing needs (the title page's own fields; the template has a fallback for each) */
        val optionalLabels: Set<String> = PickCheck.TITLE_FIELDS.toSet()

        /** The last segment of a selector, as the hint shows it: `div.item > a.title` → `a.title` */
        fun shortSelector(selector: String): String = selector.substringAfterLast(" > ").ifBlank { selector }
    }

    private fun rederive() {
        val s = state.value
        val selection = runCatching { withCode(Derive.derive(s.site, s.anime, s.picks, s.singleEpisode, s.tagGroups), s) }
            .onFailure { AppLog.add('W', "lab", "Derive failed: ${it.message}") }
            .getOrNull()
        mutableState.update { it.copy(selection = selection) }
        recheck()
    }

    /** r102: Edit by tapping on an older source: the code's values for every label not tapped again */
    private fun withCode(selection: BuilderSelection, s: State): BuilderSelection {
        if (s.fromCode.isEmpty()) return selection
        // The list paths only while no list label was tapped again here (then Derive reads them from that page)
        val listTapped = Step.ListPage.labels.any { it in s.raws }
        val kept = s.fromCode.filter { (key, value) ->
            value.isNotBlank() && key !in LIST_KEYS && key != "singleEpisode" &&
                (labelOfKey(key)?.let { s.pick(it) == null } ?: !listTapped) &&
                !(s.tagGroups.isNotEmpty() && (key == "tagsSelector" || key == "tagGroups"))
        }
        if (kept.isEmpty()) return selection
        return selection.copy(
            derived = selection.derived + kept,
            reasons = selection.reasons + kept.mapValues { "from the source's code (not tapped again)" },
        )
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
        val selection = s.selection ?: withCode(Derive.derive(s.site, s.anime, s.picks, s.singleEpisode, s.tagGroups), s)
        // r99: the JSON format has no way to say "the title page is the one episode": written as JS then
        // r99 fix: nor "no chapter link was picked" (its empty CHAPTER selector would fail without saying why): JS too,
        // whose getChapterList throws the clear error
        val noChapter = selection.derived["chapterSelector"].isNullOrBlank() && !s.singleEpisode
        // r100 (W3): tabs, filters, settings and a recorded search exist only in the JS template
        val extrasUsed = runCatching { extras.used }.getOrDefault(false)
        // r101: the title page's own fields (Cover, Description, Tags, Author, Status) are read only by the JS template
        val detailsPicked = s.picks.any { it.pick.label in optionalLabels } || s.tagGroups.isNotEmpty() ||
            s.fromCode.any { (k, v) -> v.isNotBlank() && labelOfKey(k)?.let { l -> l in optionalLabels } == true }
        val json = s.json && !s.singleEpisode && !noChapter && !extrasUsed && !detailsPicked
        if (s.json && detailsPicked) AppLog.add('I', "lab", "Builder: title page fields picked: written as JS (the JSON format reads only og:image / the meta description)")
        if (s.json && extrasUsed) AppLog.add('I', "lab", "Builder: lists and options set: written as JS (the JSON format has no tabs, filters or settings)")
        if (s.json && s.singleEpisode) AppLog.add('I', "lab", "Builder: only-episode source written as JS (the JSON format lists episodes from links)")
        if (s.json && noChapter) AppLog.add('I', "lab", "Builder: no chapter link picked: written as JS (its chapter list says to tap one)")
        val editing = s.editKey?.let { key -> runCatching { manager.get(key) }.getOrNull() }
        if (s.editKey != null && editing == null) {
            mutableState.update { it.copy(error = "The source being edited is gone: nothing was saved") }
            return
        }
        mutableState.update { it.copy(busy = if (editing != null) "Saving to ${editing.name}…" else "Writing the source…", error = null) }
        screenModelScope.launchIO {
            try {
                val generated = withIOContext {
                    val template = Templates.get(if (json) "generic-list.json" else "generic-list")
                        ?: throw IllegalStateException("The generic-list template is missing from the app")
                    val host = s.host
                    val values = LinkedHashMap<String, String>()
                    values["name"] = editing?.name ?: host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
                    values["lang"] = editing?.lang?.ifBlank { null } ?: "en"
                    values["baseUrl"] = editing?.baseUrl?.ifBlank { null } ?: s.site
                    values["iconUrl"] = editing?.iconUrl?.ifBlank { null } ?: "https://www.google.com/s2/favicons?domain=$host&sz=128"
                    values.putAll(selection.derived)
                    // r100 (W3): recorded search / tab paths override Derive's guesses; tabs, filters, settings
                    // r102: an older source's own lists / names stay when nothing was recorded for them here
                    val extraValues = extras.templateValues(s.site)
                    extraValues.forEach { (k, v) ->
                        val code = s.fromCode[k]
                        if ((v.isBlank() || v == "[]") && !code.isNullOrBlank()) values[k] = code else values[k] = v
                    }
                    // r102 fix: as in withCode, the code's list paths only while no list label was tapped again
                    // here (then Derive's paths from that page stay; the code's fill only what Derive left empty)
                    val listTapped = Step.ListPage.labels.any { it in s.raws }
                    listOf("popularPath", "latestPath", "searchPath").forEach { k ->
                        if (k in extraValues || (listTapped && !selection.derived[k].isNullOrBlank())) return@forEach
                        s.fromCode[k]?.takeIf { it.isNotBlank() }?.let { values[k] = it }
                    }
                    LIST_KEYS.forEach { k -> if (k !in values) s.fromCode[k]?.takeIf { it.isNotBlank() }?.let { values[k] = it } }
                    val lab = (editing?.labMeta ?: LabMeta()).copy(
                        engine = "generic-list",
                        templateId = template.id,
                        templateVersion = template.version,
                        // r102: the selection and the builder's own state (Edit by tapping restores it)
                        picks = builderState(s, selection),
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
                if (editing != null) {
                    saveInto(editing, generated)
                    mutableState.update { it.copy(busy = null, delivered = true) }
                    return@launchIO
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
