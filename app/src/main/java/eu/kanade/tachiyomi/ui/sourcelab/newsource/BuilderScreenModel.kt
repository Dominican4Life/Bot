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
import eu.kanade.tachiyomi.jssource.lab.VideoCheck
import eu.kanade.tachiyomi.jssource.lab.heal.PickFingerprints
import eu.kanade.tachiyomi.jssource.lab.heal.PickHealer
import eu.kanade.tachiyomi.jssource.lab.labMeta
import eu.kanade.tachiyomi.jssource.lab.sitecheck.PageFetch
import eu.kanade.tachiyomi.jssource.lab.toJson
import eu.kanade.tachiyomi.jssource.obj
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
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
 *
 * r104 (the owner's 123av Tags were whole rows "Code FC2-PPV…" and the labels "Code, Type…"): a Tags tap in a details
 * block brings every row of it ([RawPick.rows]): one group per row (missing rows only on a re-tap; Undo removes that
 * tap's rows). A restored group that reads whole rows or labels ([Derive.needsSplit]) gets "Split into rows"
 * ([splitTagGroup]: picker.js `rowsFor`, answered as a pick with via "split"). The **Suggestions** label stays armed
 * like Tags: each tapped card's section ("You may like", "Related videos") is a [Derive.SuggestionGroup]
 * ([State.suggestionGroups], Derive writes `suggestionGroups` for generic-list v7's getRelated).
 *
 * r106 (Suggestions as cards, BuilderSuggestions.kt): a section that loads late is read from its own address or "with
 * the browser" ([setSuggestionRead]: generic-list v9 opens the title page in the hidden browser), "Look for the address
 * again" ([findLateAgain]); a section's "Load more" button (found by picker.js, or tapped with Suggestions armed) can
 * be pressed up to 3 times by the source ([setLoadMorePresses]); "Scan it now" ([scanLoadMore]) presses it once in the
 * page and keeps the request that brought the new cards ([BuilderLateSections.findMore]).
 *
 * KMK (r115, Helper 2): the labels are one checklist. Every pick is run again on the raw page and on up to 3 titles
 * ([scheduleRerun] → [BuilderRecheck], kept in [State.rechecks]); [checklistRow] gives each label one mark (✓ ! ✕ ○),
 * one line and at most one action ([applyFix]: Read another picture attribute, Split into rows, Tap again, Page
 * source). The helper card over the page has one primary action per verdict (and ✕ to keep the pick).
 *
 * KMK (r116): the suggestion box is back for every Ok / Warn pick ([startSuggest]: the page's own guess first, then
 * [suggestAnother] walks [BuilderCandidates] on the raw page, each outlined by `suggestSelector`); Use this / Suggest
 * another / Keep mine. Each labelled pick's [PickHealer.fingerprint] is saved ([saveFingerprints]: at once for an
 * edited source, at write time for a new one once Source Lab gives it its key). "Test it now" also runs
 * [VideoCheck] on the caught address (passed only when ok) and the card offers Preview (VideoPreview, in the screen).
 */
internal class BuilderScreenModel(
    private val startUrl: String?,
    anime: Boolean,
    private val editKey: String? = null,
    /** KMK (r115): the Site check's word on this site (a list made by scripts …), shown once over the page */
    siteNote: String? = null,
) : StateScreenModel<BuilderScreenModel.State>(State(url = startUrl.orEmpty(), anime = anime, editKey = editKey, notice = siteNote)) {

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
        /** r104: a Tags tap in a details block: every row of the block (picker.js `rows`), [] when in none */
        val rows: List<DetailRow> = emptyList(),
        /** r104: a Suggestions tap: the section's group (picker.js `suggest`), null when no section was found */
        val suggest: Derive.SuggestionGroup? = null,
        /** r106: a Suggestions tap on a section's "Load more" button (picker.js `loadMore`), null = not one */
        val loadMore: Derive.LoadMore? = null,
        /** r106: the headings before that button, nearest first, one per line ("" = none: the last section tapped) */
        val loadMoreSection: String = "",
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
            detailRows = rows.map { it.label },
            suggestName = suggest?.name.orEmpty(),
            suggestCount = suggest?.count ?: 0,
        )
    }

    /** r104: one row of a details block (picker.js `detailRows`): its label, label-anchored selector, target, values */
    @Immutable
    @Serializable
    data class DetailRow(
        val label: String,
        val selector: String,
        val target: String = "tags",
        val values: List<String> = emptyList(),
        val chips: Int = 0,
        val order: Int = -1,
        val kind: String = "",
    )

    /** r103 fix: one row of a Tags pick's look on the page: its exact selector, its place, its first texts */
    @Immutable
    @Serializable
    data class LikeRow(val exact: String, val order: Int = -1, val values: List<String> = emptyList())

    /**
     * r100: the helper's "Test it now" on a Video pick: running, then what VideoFinder caught or why not.
     * KMK (r116): [ok] = the caught address also passed [VideoCheck] (playlist → quality → key → piece, or the file's
     * first bytes); [stage] / [detail] say where it stopped; [url] / [headers] are what Preview plays
     */
    @Immutable
    data class VideoTest(
        val running: Boolean,
        val ok: Boolean,
        val text: String,
        val url: String = "",
        val headers: Map<String, String> = emptyMap(),
        val stage: String = "",
        val detail: String = "",
        /** KMK (r116): the address was caught and VideoCheck is still running */
        val checking: Boolean = false,
    )

    /**
     * KMK (r116): the helper card's suggestion box for [label]: why the shown suggestion was offered ([reason]),
     * looking for one ([busy]), a line when there is none ([note]), and whether "Suggest another" has more to try
     */
    @Immutable
    data class SuggestUi(
        val label: String,
        val reason: String = "",
        val busy: Boolean = false,
        val note: String? = null,
        val more: Boolean = true,
    )

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
        /** KMK (r116): the suggestion box of the helper card (shown only while its label is [helperFor]) */
        val suggestUi: SuggestUi? = null,
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
        /** r104: the selectors of the groups the last Tags tap added (a details block adds several; Undo removes them) */
        val lastTagTap: List<String> = emptyList(),
        /**
         * r104 fix: the Tags groups (and their tap order) exactly as they were before the last details-block tap or
         * "Split into rows", which also drop the groups that read whole rows or labels; Undo puts them back. null = none
         */
        val tagsBefore: List<Derive.TagGroup>? = null,
        val tagOrderBefore: List<String> = emptyList(),
        /** r104: the title page's sections tapped as Suggestions, one group each, in the order tapped */
        val suggestionGroups: List<Derive.SuggestionGroup> = emptyList(),
        /** r106: the section ([Derive.SuggestionGroup.item]) whose Load more is being scanned or looked up, null = none */
        val suggestionBusy: String? = null,
        /**
         * r106: Edit by tapping: the History version the source's code is now ("Save to v3" overwrites it in place, as
         * the code editor's Save does since r103); null = a new source, or one with no History version yet
         */
        val openVersion: Int? = null,
        /** r106: the number "Save as new version" gives (the next ever given); 1 for a new source */
        val nextVersion: Int = 1,
        /**
         * r106: Save to the open version waits for this question (the open version is the source's Baseline, the r103
         * wording of [History.overwriteConfirm]): [confirmSave] overwrites it, [cancelSave] doesn't; null = none
         */
        val saveConfirm: String? = null,
        /**
         * KMK (r115, Helper 2): each pick run again on the raw page and on up to 3 titles ([BuilderRecheck]), by label;
         * [recheckBusy] while pages are fetched; [recheckNote] the checklist's one-line summary ("3 titles · raw page")
         */
        val rechecks: Map<String, PickCheck.Recheck> = emptyMap(),
        val recheckBusy: Boolean = false,
        val recheckNote: String? = null,
        /** KMK (r115): one-tap fixes that change a derived value ("Read data-src": coverAttr / detailsCoverAttr) */
        val attrOverrides: Map<String, String> = emptyMap(),
    ) {
        val site: String get() = (pageUrl ?: loadUrl ?: url).let { Detector.normalize(it) }
        val host: String get() = site.removePrefix("https://").removePrefix("http://")
        fun pick(label: String): Derive.Picked? = picks.lastOrNull { it.pick.label == label }
        val labels: List<String> get() = if (step == Step.ChapterPage) listOf(if (anime) "video" else "image") else step.labels

        /** r102: every label of every step, in the chooser's order (any of them can be picked on any page) */
        val allLabels: List<String> get() = Step.ListPage.labels + Step.TitlePage.labels + listOf(if (anime) "video" else "image")

        /** r102: [label] has a value: picked, or (Edit by tapping, older source) read from the code */
        fun done(label: String): Boolean = pick(label) != null || fromCodeOnly(label) || (label == "tags" && tagGroups.isNotEmpty()) ||
            (label == "suggestions" && suggestionGroups.isNotEmpty())

        /** r102: [label] is taken from the code (not tapped again) */
        fun fromCodeOnly(label: String): Boolean = pick(label) == null && codeKeys(label).any { fromCode[it]?.isNotBlank() == true }
    }

    private val context get() = Injekt.get<Application>()

    private val manager: JsSourceManager by lazy { Injekt.get<JsSourceManager>() }

    /** r100 (W3): Search / Tabs / Filters / Settings, recorded while the owner browses the site */
    val extras = BuilderExtrasModel()

    // KMK (r115, Helper 2): the raw pages read by the checklist's re-run, and that run (declared before init: init's
    // coroutines may rederive at once)
    private val rerunPages = BuilderRecheck.Pages()
    private var rerunJob: Job? = null

    // KMK (r116): the helper's suggestions: the candidates still to try for the card's label (null = not read yet),
    // the look-alike selectors already shown or picked, the reason of the one asked for, and whether an answer of
    // "none" should go on to the next candidate
    private var suggestQueue: ArrayDeque<BuilderCandidates.Candidate>? = null
    private val suggestTried: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private var suggestReason: String = ""
    private var suggestSeeking = false
    private var suggestJob: Job? = null
    private var suggestToken = 0

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
        // r106 fix: a page that (re)loaded while "Scan it now" waited for its answer won't answer: the card stops waiting
        if (finished) scanGaveUp("The page changed while pressing it; try again")
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
            rows = o.arr("rows")?.let { detailRowsOf(it) }.orEmpty(),
            suggest = o.obj("suggest")?.let { suggestionOf(it) },
            loadMore = o.obj("loadMore")?.let { loadMoreOf(it) },
            loadMoreSection = o.obj("loadMore")?.str("section")?.trim().orEmpty(),
        )
        // r106: picker.js `scanMore`'s answer ("Scan it now")
        if (raw.via == "more") {
            onScanMore(o)
            return
        }
        // KMK (r109): picker.js `pictureMode`'s answer (a scanned directory card's "Tap the picture on the page")
        if (raw.via == "picture") {
            AppLog.add('D', "lab", "picker picture tap (${json.length} chars)")
            runCatching { extras.onPicturePicked(json) }
                .onFailure { AppLog.add('W', "lab", "extras: picture tap not read: ${it.message}") }
            return
        }
        // r104: picker.js `rowsFor`'s answer ("Split into rows")
        if (raw.via == "split") {
            onSplit(o.str("split").orEmpty(), raw.rows)
            return
        }
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
            if (armedFor !in STICKY_LABELS) mutableState.update { it.copy(armed = null) }
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
        // KMK (r109): arming a label ends a directory card's waiting "Tap the picture on the page"
        if (next != null && runCatching { extras.state.value.picturePick != null }.getOrDefault(false)) extras.cancelPicturePick()
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
        // KMK (r116): one already shown (or the pick itself) counts as none: the next candidate is tried
        if (none || raw.cssPath.isBlank() || raw.likeSelector == s.raws[forLabel]?.likeSelector || raw.likeSelector in suggestTried) {
            AppLog.add('D', "lab", "helper suggestion for $forLabel: none${if (suggestSeeking) " (trying the next candidate)" else ""}")
            mutableState.update { it.copy(suggestion = null) }
            if (!none) page("window.__labPicker.unsuggest();")
            if (suggestSeeking) {
                nextCandidate(forLabel)
            } else {
                suggestToken++
                mutableState.update { it.copy(suggestUi = SuggestUi(forLabel, note = noneNote(forLabel, s))) }
            }
            return
        }
        suggestToken++
        suggestTried.add(raw.likeSelector)
        suggestSeeking = false
        AppLog.add('D', "lab", "helper suggestion for $forLabel: ${raw.likeSelector} ×${raw.count} ($suggestReason)")
        mutableState.update { it.copy(suggestion = raw, suggestUi = SuggestUi(forLabel, reason = suggestReason)) }
    }

    // ---------- KMK (r116): the helper's suggestions (Use this / Suggest another / Keep mine) ----------

    /**
     * A new pick for [label]: the page's own guess is asked for by applyLabel's script ([reason] says why it is
     * offered); a Warn whose page has no guess goes on to [BuilderCandidates] at once, an Ok one stops with a line
     */
    private fun startSuggest(label: String, raw: RawPick, warn: Boolean, reason: String) {
        suggestJob?.cancel()
        suggestQueue = null
        suggestTried.clear()
        suggestTried.add(raw.likeSelector)
        suggestReason = reason
        suggestSeeking = warn
        val token = ++suggestToken
        mutableState.update { it.copy(suggestUi = SuggestUi(label, busy = true)) }
        watchAnswer(label, token)
    }

    /** No answer from the page in 5 s (it navigated, the script is gone): the next candidate, or a quiet stop */
    private fun watchAnswer(label: String, token: Int) {
        screenModelScope.launchIO {
            delay(5_000L)
            if (token != suggestToken || state.value.helperFor != label || state.value.suggestUi?.busy != true) return@launchIO
            AppLog.add('D', "lab", "helper suggestion for $label: no answer from the page")
            if (suggestSeeking) {
                nextCandidate(label)
            } else {
                mutableState.update { it.copy(suggestUi = SuggestUi(label, note = noneNote(label, it))) }
            }
        }
    }

    private fun noneNote(label: String, s: State): String =
        "Nothing else on this page looks more like ${PickCheck.labelName(label, s.anime)}."

    /** "Suggest another": the shown suggestion goes and the next candidate is outlined (dashed green) */
    fun suggestAnother() {
        val s = state.value
        val label = s.helperFor ?: return
        s.suggestion?.likeSelector?.let { suggestTried.add(it) }
        page("window.__labPicker.unsuggest && window.__labPicker.unsuggest();")
        mutableState.update { it.copy(suggestion = null) }
        AppLog.add('D', "lab", "helper $label: suggest another (${suggestTried.size} tried)")
        nextCandidate(label)
    }

    /** Sends the next untried candidate to the page (reading the candidates first, off the main thread) */
    private fun nextCandidate(label: String) {
        suggestJob?.cancel()
        suggestSeeking = true
        val token = ++suggestToken
        mutableState.update { it.copy(suggestUi = SuggestUi(label, busy = true)) }
        suggestJob = screenModelScope.launchIO {
            val queue = suggestQueue ?: ArrayDeque(readCandidates(label)).also { suggestQueue = it }
            if (token != suggestToken || state.value.helperFor != label) return@launchIO
            val next = queue.removeFirstOrNull()
            if (next == null) {
                suggestSeeking = false
                AppLog.add('D', "lab", "helper $label: no other candidate")
                mutableState.update { it.copy(suggestUi = SuggestUi(label, note = "No other candidate on this page.", more = false)) }
                return@launchIO
            }
            suggestReason = next.reason
            AppLog.add('D', "lab", "helper $label: candidate ${next.selector} (${next.reason})")
            page(
                "if (window.__labPicker.suggestSelector) window.__labPicker.suggestSelector(" +
                    "${JsonPrimitive(next.selector)}, ${JsonPrimitive(label)});",
            )
            watchAnswer(label, token)
        }
    }

    /** The candidates for [label] on the raw page its pick was made on (kept by the checklist's re-run, else read) */
    private suspend fun readCandidates(label: String): List<BuilderCandidates.Candidate> {
        val s = state.value
        val raw = s.raws[label] ?: return emptyList()
        val doc = rawDoc(raw.pageUrl.ifBlank { s.pageUrl.orEmpty() }, s)
        val list = runCatching {
            BuilderCandidates.of(
                label = label,
                pickSelector = raw.likeSelector,
                pickPath = raw.cssPath,
                itemSelector = s.selection?.derived?.get("itemSelector")?.takeIf { label != "item" },
                itemFromPicks = if (label == "item") itemSuggestion(s) else null,
                doc = doc,
            )
        }.onFailure { AppLog.add('W', "lab", "helper $label: candidates not read: ${it.message}") }.getOrDefault(emptyList())
        AppLog.add('D', "lab", "helper $label: ${list.size} candidates${if (doc == null) " (the raw page didn't load)" else ""}")
        return list.filter { it.selector !in suggestTried }
    }

    /** The raw page at [url] as the checklist keeps it ([BuilderRecheck.Pages]); fetched once when it isn't there */
    private suspend fun rawDoc(url: String, s: State): org.jsoup.nodes.Document? {
        if (url.isBlank()) return null
        val key = BuilderRecheck.clean(url)
        if (rerunPages.has(key)) return rerunPages.get(key)
        // at most 20 s (a site waiting for a bot check must not keep the helper's spinner going); not kept then
        val page = withTimeoutOrNull(20_000L) { PageFetch.raw(key, s.site.trimEnd('/') + "/") } ?: return null
        val doc = if (page.ok && !page.botCheck) {
            withContext(Dispatchers.Default) { runCatching { Jsoup.parse(page.body, page.url) }.getOrNull() }
        } else {
            null
        }
        rerunPages.put(key, doc)
        return doc
    }

    // ---------- KMK (r116): fingerprints of the picks (Test Run's self-healing reads them) ----------

    /** The labels a fingerprint is kept for: single-selector picks (Tags / Suggestions are groups) */
    private fun fingerprinted(s: State): Set<String> =
        s.picks.map { it.pick.label }.filter { it != "tags" && it != "suggestions" && it in s.raws }.toSet()

    /**
     * Saves [PickHealer.fingerprint] of each of [labels]' picked element, found by its look-alike selector on the raw
     * page the pick was made on, under the source [key]. Runs off the main thread; never fails a pick or a save.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun saveFingerprints(key: String, labels: Set<String>, s: State = state.value) {
        if (labels.isEmpty()) return
        val raws = labels.mapNotNull { l -> s.raws[l]?.let { l to it } }
        // the app's scope: a new source's key arrives after the builder may have closed
        launchIO {
            var saved = 0
            raws.forEach { (label, raw) ->
                try {
                    val doc = rawDoc(raw.pageUrl.ifBlank { s.pageUrl.orEmpty() }, s) ?: return@forEach
                    val el = runCatching { doc.selectFirst(raw.likeSelector) }.getOrNull()
                        ?: runCatching { doc.selectFirst(raw.cssPath) }.getOrNull()
                        ?: return@forEach
                    PickFingerprints.save(key, label, PickHealer.fingerprint(el))
                    saved++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.add('W', "lab", "fingerprint $label of $key not saved: ${e.message}")
                }
            }
            AppLog.add('D', "lab", "fingerprints of $key: $saved of ${raws.size} saved (${labels.joinToString(", ")})")
        }
    }

    /**
     * A new source gets its key when Source Lab saves what the builder delivered ([LabHandoff]): waits (≤ 60 s) for
     * the saved source whose LabMeta has this write's [stamp], then saves the fingerprints under its key
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun saveFingerprintsWhenSaved(stamp: Long, s: State) {
        val labels = fingerprinted(s)
        if (labels.isEmpty()) return
        launchIO {
            val info = withTimeoutOrNull(60_000L) {
                manager.savedFlow.map { list -> list.firstOrNull { it.labMeta?.detectedAt == stamp } }.filterNotNull().first()
            }
            if (info == null) {
                AppLog.add('W', "lab", "fingerprints: the new source of ${s.host} wasn't saved in 60 s: none kept")
                return@launchIO
            }
            saveFingerprints(info.key, labels, s)
        }
    }

    fun onPickerLog(text: String) {
        AppLog.add('D', "lab", "picker $text")
        if (text.startsWith("learn:")) runCatching { extras.onLearnLog(text) } // r102 fix: learn.js's press outcome
        // r106 fix: "Scan it now" found that the Load more is a link to this page's next page (not pressed)
        if (text.startsWith(SCAN_LINK)) runCatching { onScanLink(text.removePrefix(SCAN_LINK)) }
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
        // r106: a Suggestions tap on a section's "Load more" button: that section's card offers to press it
        val more = raw.loadMore
        if (label == "suggestions" && more != null) {
            onLoadMoreTapped(more, raw.loadMoreSection)
            return
        }
        // r104: a Suggestions tap outside a section with a heading makes nothing (the note says what to tap)
        val suggestion = if (label == "suggestions") raw.suggest?.takeIf { it.item.isNotBlank() } else null
        if (label == "suggestions" && suggestion == null) {
            AppLog.add('W', "lab", "Builder: Suggestions tap outside a section with a heading and repeated cards (${raw.likeSelector})")
            mutableState.update {
                it.copy(
                    pending = null,
                    notice = "No “You may like” / “Related” section around that tap: tap one card (its picture or title) inside such a section.",
                )
            }
            return
        }
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
        // r102: Tags is a group per Details row (Cast, Maker…). r103: every tap adds a group (an unlabelled row too).
        // r104: a tap in a details block adds every row of it (the rows not there yet)
        // r104 fix: the groups that read whole rows or labels (a restored "Tags 2" = "Code FC2…", "Tags" = "Code, Type…")
        // go first, so the site's own rows keep their names ("Tags", not "Tags 3"); Undo brings them back
        val replaced = if (label == "tags" && raw.rows.isNotEmpty()) state.value.tagGroups.filter { Derive.needsSplit(it) } else emptyList()
        val rowsTap = if (label == "tags" && raw.rows.isNotEmpty()) {
            withRows(state.value.tagGroups.filterNot { Derive.needsSplit(it) }, raw.rows)
        } else {
            null
        }
        val tagged = if (label == "tags" && rowsTap == null) tagGroupOf(raw, state.value.tagGroups) else null
        val group = tagged?.first
        mutableState.update { s ->
            s.copy(
                picks = s.picks.filter { it.pick.label != label } + picked,
                raws = s.raws + (label to raw),
                tagGroups = when {
                    rowsTap != null -> inPageOrder(rowsTap.first)
                    tagged != null -> inPageOrder(withGroup(tagged.second, tagged.first))
                    else -> s.tagGroups
                },
                lastTagTap = when {
                    // a re-tap that added no row leaves the last tap's rows for Undo
                    rowsTap != null -> rowsTap.second.ifEmpty { s.lastTagTap }
                    tagged != null -> emptyList()
                    else -> s.lastTagTap
                },
                // r104 fix: a block tap that changed the groups can be undone exactly; another Tags tap can't
                tagsBefore = when {
                    rowsTap != null && (rowsTap.second.isNotEmpty() || replaced.isNotEmpty()) -> s.tagGroups
                    rowsTap != null -> s.tagsBefore
                    label == "tags" -> null
                    else -> s.tagsBefore
                },
                tagOrderBefore = when {
                    rowsTap != null && (rowsTap.second.isNotEmpty() || replaced.isNotEmpty()) -> s.tagTapOrder
                    rowsTap != null -> s.tagOrderBefore
                    label == "tags" -> emptyList()
                    else -> s.tagOrderBefore
                },
                suggestionGroups = if (suggestion != null) withSuggestion(s.suggestionGroups, suggestion) else s.suggestionGroups,
                // the helper card warns; with "Check each pick" off a notice says it instead
                notice = if (menuTags && !s.helper) {
                    "This looks like the site's menu, not this title's tags — kept. Undo removes it."
                } else if (replaced.isNotEmpty()) {
                    replacedNotice(replaced)
                } else {
                    null
                },
                tagTapOrder = when {
                    group != null -> s.tagTapOrder.filter { it != group.selector } + group.selector
                    rowsTap != null -> s.tagTapOrder.filter { it !in rowsTap.second } + rowsTap.second
                    else -> s.tagTapOrder
                },
                fromCode = s.fromCode - codeKeys(label),
                pending = null,
                helperFor = if (s.helper) label else null,
                suggestion = null,
                suggestUi = null, // KMK (r116): startSuggest below sets it again when this pick gets one
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
        if (replaced.isNotEmpty()) {
            AppLog.add(
                'I',
                "lab",
                "Builder: tags groups ${replaced.joinToString(", ") { "\"${it.name}\"" }} (whole rows / labels) replaced by the details block's rows",
            )
        }
        if (rowsTap != null) {
            val added = state.value.tagGroups.filter { it.selector in rowsTap.second }
            AppLog.add(
                'I',
                "lab",
                "Builder: tags from a details block: ${raw.rows.size} rows, ${added.size} new " +
                    "(${added.joinToString(", ") { "${it.name} → ${it.target}" }})",
            )
        }
        if (suggestion != null) {
            AppLog.add('I', "lab", "Builder: suggestions group \"${suggestion.name}\" = ${suggestion.item} (${suggestion.count} cards)")
            checkLateSection(suggestion, raw.pageUrl)
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
            // KMK (r116): every pick gets a suggestion (Ok and Warn; Tags only on a Warn, Suggestions never)
            val warn = check.level == PickCheck.Level.Warn
            if (!menuTags && check.level != PickCheck.Level.Info && label != "suggestions" && (warn || label != "tags")) {
                // r101: an Item that doesn't hold the Title / Cover: the card derived from those picks is always
                // offered (the page's own search found nothing the second time in the owner's run)
                val itemCss = if (label == "item" && check.checks.any { !it.ok && it.text.startsWith("doesn't hold") }) {
                    itemSuggestion(s)
                } else {
                    null
                }
                startSuggest(
                    label,
                    raw,
                    warn,
                    if (itemCss != null) "the box that holds your Title and Cover picks" else BuilderCandidates.pageReason(label),
                )
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
        // KMK (r116): an edited source keeps the pick's fingerprint at once (a new one when it is written)
        s.editKey?.let { key -> saveFingerprints(key, setOf(label)) }
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

    /**
     * r104: [groups] with a group for each of [rows] whose selector no group has yet (named by its label, distinct;
     * target tags when the row holds links / chips, else description), and the selectors added
     */
    private fun withRows(groups: List<Derive.TagGroup>, rows: List<DetailRow>): Pair<List<Derive.TagGroup>, List<String>> {
        var list = groups
        val added = ArrayList<String>()
        rows.forEach { r ->
            if (r.selector.isBlank() || list.any { it.selector == r.selector }) return@forEach
            list = list + Derive.TagGroup(
                name = distinctName(r.label.replace(":", "").trim().take(30).ifBlank { "Tags" }, list),
                selector = r.selector,
                target = if (r.target == "description") "description" else "tags",
                values = r.values,
                order = r.order,
            )
            added += r.selector
        }
        return list to added
    }

    private fun detailRowsOf(array: JsonArray): List<DetailRow> = array.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val selector = row.str("selector")?.trim().orEmpty()
        val label = row.str("label")?.trim().orEmpty()
        if (selector.isEmpty() || label.isEmpty()) {
            null
        } else {
            DetailRow(
                label = label,
                selector = selector,
                target = row.str("target") ?: "tags",
                values = row.arr("values")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().take(8),
                chips = row.int("chips") ?: 0,
                order = row.int("order") ?: -1,
                kind = row.str("kind").orEmpty(),
            )
        }
    }.take(40)

    private fun suggestionOf(o: JsonObject): Derive.SuggestionGroup? {
        val item = o.str("item")?.trim().orEmpty()
        if (item.isEmpty()) return null
        return Derive.SuggestionGroup(
            name = o.str("name")?.trim().orEmpty().replace(":", "").take(60).ifBlank { "Suggestions" },
            item = item,
            link = o.str("link")?.trim().orEmpty(),
            title = o.str("title")?.trim().orEmpty(),
            cover = o.str("cover")?.trim().orEmpty(),
            coverAttr = o.str("coverAttr")?.trim().orEmpty().ifBlank { "src" },
            count = o.int("count") ?: 0,
            order = o.int("order") ?: -1,
            values = o.arr("samples")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().take(3),
            // r106: the section's "Load more" button, when picker.js found one under it
            more = o.obj("more")?.let { loadMoreOf(it) },
        )
    }

    /** r106: picker.js's `{ button, text }` of a Load more button */
    private fun loadMoreOf(o: JsonObject): Derive.LoadMore? {
        val button = o.str("button")?.trim().orEmpty()
        if (button.isEmpty()) return null
        return Derive.LoadMore(button = button, text = o.str("text")?.trim().orEmpty().take(40))
    }

    /** r104: the same section again (same item selector or name) replaces its group, keeping its name; else it is added */
    private fun withSuggestion(groups: List<Derive.SuggestionGroup>, group: Derive.SuggestionGroup): List<Derive.SuggestionGroup> {
        val i = groups.indexOfFirst { it.item == group.item || it.name.equals(group.name, ignoreCase = true) }
        if (i < 0) return groups + group
        // r105: the same section again keeps the late address found for it. r106: and how it is read, its Load more
        val same = groups[i].item == group.item
        val old = groups[i]
        val more = when {
            old.more == null -> group.more
            group.more == null || old.more.button == group.more.button -> old.more
            else -> group.more
        }
        return groups.toMutableList().also {
            it[i] = group.copy(
                name = old.name,
                late = if (same) old.late else "",
                lateNote = if (same) old.lateNote else "",
                read = if (same) old.read else "",
                more = more,
            )
        }
    }

    /**
     * r105: a Suggestions section whose cards aren't in the page's own HTML (the site's script adds them later) is read
     * from the request that brought them ([BuilderLateSections]); none found: the card warns
     */
    private fun checkLateSection(group: Derive.SuggestionGroup, pageUrl: String) {
        if (!pageUrl.startsWith("http")) return
        screenModelScope.launchIO {
            val outcome = try {
                BuilderLateSections.check(group.item, group.values, pageUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.add('W', "lab", "Builder: suggestions ${group.item}: the raw page wasn't read (${e.message ?: e.javaClass.simpleName})")
                null
            }
            if (outcome == null) {
                // in the page itself (or the page couldn't be read): the card's "looking…" ends
                mutableState.update { it.copy(suggestionBusy = if (it.suggestionBusy == group.item) null else it.suggestionBusy) }
                return@launchIO
            }
            // r105 fix: the check ran on IO; the state change and rederive (which the screen and the WebView follow) on Main
            withContext(Dispatchers.Main) {
                var changed = false
                mutableState.update { s ->
                    changed = false
                    val i = s.suggestionGroups.indexOfFirst { it.item == group.item }
                    if (i < 0) return@update s
                    changed = true
                    s.copy(
                        suggestionGroups = s.suggestionGroups.toMutableList().also {
                            it[i] = it[i].copy(late = outcome.late, lateWarning = outcome.warning, lateNote = outcome.note)
                        },
                        suggestionBusy = if (s.suggestionBusy == group.item) null else s.suggestionBusy,
                    )
                }
                if (changed) rederive()
            }
        }
    }

    // ---------- r106: Suggestions cards (BuilderSuggestions.kt) ----------

    private fun updateSuggestion(index: Int, change: (Derive.SuggestionGroup) -> Derive.SuggestionGroup): Derive.SuggestionGroup? {
        var out: Derive.SuggestionGroup? = null
        mutableState.update { s ->
            val g = s.suggestionGroups.getOrNull(index) ?: return@update s
            val n = change(g)
            out = n
            s.copy(suggestionGroups = s.suggestionGroups.toMutableList().also { it[index] = n })
        }
        return out
    }

    /** The title page a section was tapped on (its pick's page), else the page shown */
    private fun suggestionPage(): String = state.value.raws["suggestions"]?.pageUrl?.takeIf { it.startsWith("http") }
        ?: state.value.pageUrl.orEmpty()

    /**
     * r106: how the source reads a section that loads late: "" = from the address it comes from (when found),
     * "browser" = the title page in the hidden browser, waiting for the cards (generic-list v9, slower, always works)
     */
    fun setSuggestionRead(index: Int, read: String) {
        val r = if (read == "browser") "browser" else ""
        val g = updateSuggestion(index) { it.copy(read = r) } ?: return
        AppLog.add('I', "lab", "Builder: suggestions \"${g.name}\" read ${if (r == "browser") "with the browser" else "from its own address (${g.late.ifBlank { "none found" }})"}")
        rederive()
    }

    /** r106: "Look for the address again": the raw page and the page's requests are checked again (open the title first) */
    fun findLateAgain(index: Int) {
        val g = state.value.suggestionGroups.getOrNull(index) ?: return
        val page = suggestionPage()
        if (!page.startsWith("http")) return
        mutableState.update { it.copy(suggestionBusy = g.item) }
        AppLog.add('D', "lab", "Builder: suggestions \"${g.name}\": looking for its address again on $page")
        checkLateSection(g, page)
    }

    /** r106: "Also press it" Off / 1× / 2× / 3× of a section's Load more */
    fun setLoadMorePresses(index: Int, presses: Int) {
        val n = presses.coerceIn(0, 3)
        val g = updateSuggestion(index) { g -> g.more?.let { g.copy(more = it.copy(presses = n)) } ?: g } ?: return
        AppLog.add('I', "lab", "Builder: suggestions \"${g.name}\": press Load more ${if (n == 0) "off" else "$n×"}")
        rederive()
    }

    /** r106: the card's "Load more" row closed: the button is forgotten */
    fun forgetLoadMore(index: Int) {
        updateSuggestion(index) { it.copy(more = null) } ?: return
        rederive()
    }

    /**
     * r106: "Scan it now": the page presses the section's Load more once (picker.js `scanMore`), counts the cards
     * before and after and sends the requests the press made; [onScanMore] keeps the one that brought them
     */
    fun scanLoadMore(index: Int) {
        val g = state.value.suggestionGroups.getOrNull(index) ?: return
        val more = g.more ?: return
        mutableState.update { it.copy(suggestionBusy = g.item) }
        // r106 fix: no answer within 15 s (the press opened another page, the picker is gone…): the card stops waiting
        scanWaiting = g.item
        scanTimeout?.cancel()
        scanTimeout = screenModelScope.launchIO {
            delay(SCAN_TIMEOUT_MS)
            if (scanWaiting == g.item) scanGaveUp("No answer from the page; try again")
        }
        val req = JsonObject(
            mapOf(
                "id" to JsonPrimitive(g.item),
                "item" to JsonPrimitive(g.item),
                "button" to JsonPrimitive(more.button),
                "text" to JsonPrimitive(more.text),
            ),
        )
        AppLog.add('I', "lab", "Builder: suggestions \"${g.name}\": scan Load more (${more.button})")
        // r106 fix: a Load more that is a link (`<a href="?page=2">`, same path) would open the next page instead of
        // adding cards: it isn't pressed, its address is sent back ([onScanLink]) and becomes the request directly
        val button = JsonPrimitive(more.button)
        val id = JsonPrimitive(g.item)
        page(
            "var lb=null;try{lb=document.querySelector($button);}catch(e){lb=null;}" +
                "var la=lb&&lb.closest?lb.closest('a[href]'):null;var lh=la?String(la.getAttribute('href')||'').trim():'';" +
                "if(lh&&!/^(#|javascript:)/i.test(lh)){var lu='';try{lu=new URL(lh,location.href).href;}catch(e){lu='';}" +
                "window.__labPicker.log('$SCAN_LINK'+JSON.stringify({id:$id,href:lu,pageUrl:location.href}));}" +
                "else if (window.__labPicker.scanMore) window.__labPicker.scanMore($req); else window.__labPicker.log('scanMore: not in this picker');",
        )
    }

    /** r106 fix: the item whose "Scan it now" waits for the page's answer (null = none), and its 15 s timeout */
    @Volatile
    private var scanWaiting: String? = null

    @Volatile
    private var scanTimeout: Job? = null

    /** r106 fix: the scan waiting for the page ends without an answer: its card says [why] and isn't busy any more */
    private fun scanGaveUp(why: String) {
        val item = scanWaiting ?: return
        scanWaiting = null
        scanTimeout?.cancel()
        scanTimeout = null
        val index = state.value.suggestionGroups.indexOfFirst { it.item == item }
        if (index >= 0) updateSuggestion(index) { g -> g.more?.let { g.copy(more = it.copy(scanned = why)) } ?: g }
        mutableState.update { it.copy(suggestionBusy = if (it.suggestionBusy == item) null else it.suggestionBusy) }
        AppLog.add('W', "lab", "Builder: load more scan: $why")
    }

    /**
     * r106 fix: the section's Load more is a link to this page with another page number (`?page=2`): that address is
     * the request template (with {page}); none found = it can't be pressed for more (its presses go Off)
     */
    private fun onScanLink(json: String) {
        val o = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return
        val item = o.str("id").orEmpty()
        if (item.isEmpty() || scanWaiting != item) return
        scanWaiting = null
        scanTimeout?.cancel()
        scanTimeout = null
        val href = o.str("href").orEmpty()
        val pageUrl = o.str("pageUrl").orEmpty().ifBlank { suggestionPage() }
        val paged = if (href.startsWith("http")) {
            BuilderLateSections.pagedOf(BuilderLateSections.templateOf(href, pageUrl, null), href, 0)
        } else {
            null
        }
        val origin = pageUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }.orEmpty()
        val shown = if (href.startsWith("http")) BuilderExtrasLogic.relative(href, origin) else href
        AppLog.add('I', "lab", "Builder: load more is a link to $href → ${paged?.first ?: "no page number"}")
        screenModelScope.launchIO {
            withContext(Dispatchers.Main) {
                val i = state.value.suggestionGroups.indexOfFirst { it.item == item }
                if (i >= 0) {
                    updateSuggestion(i) { cur ->
                        val m = cur.more ?: return@updateSuggestion cur
                        cur.copy(
                            more = if (paged != null) {
                                m.copy(
                                    request = paged.first,
                                    from = paged.second,
                                    step = paged.third,
                                    presses = if (m.presses == 0) 1 else m.presses,
                                    scanned = "Scanned: it's a link to the next page ($shown), the next pages are read from that address",
                                )
                            } else {
                                m.copy(
                                    request = "",
                                    presses = 0,
                                    scanned = "It's a link that opens another page ($shown), with no page number: it can't add cards here",
                                )
                            },
                        )
                    }
                    rederive()
                }
                mutableState.update { it.copy(suggestionBusy = if (it.suggestionBusy == item) null else it.suggestionBusy) }
            }
        }
    }

    /** r106: picker.js `scanMore`'s answer: `{ via: "more", id, ok, before, after, requests, samples, reason, pageUrl }` */
    private fun onScanMore(o: JsonObject) {
        val item = o.str("id").orEmpty()
        // r106 fix: the page answered: the 15 s timeout is off
        if (scanWaiting == item) {
            scanWaiting = null
            scanTimeout?.cancel()
            scanTimeout = null
        }
        val before = o.int("before") ?: 0
        val after = o.int("after") ?: 0
        val pageUrl = o.str("pageUrl").orEmpty()
        val requests = o.arr("requests")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val samples = o.arr("samples")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().take(6)
        val index = state.value.suggestionGroups.indexOfFirst { it.item == item }
        if (index < 0) {
            mutableState.update { it.copy(suggestionBusy = null) }
            return
        }
        val added = after - before
        AppLog.add('I', "lab", "Builder: load more scan: $before → $after cards, ${requests.size} requests (${o.str("reason").orEmpty()})")
        if (o.bool("ok") != true || added <= 0) {
            val why = o.str("reason")?.takeIf { it.isNotBlank() } ?: "no new cards appeared"
            updateSuggestion(index) { g -> g.more?.let { g.copy(more = it.copy(scanned = "Pressed it once: $why")) } ?: g }
            mutableState.update { it.copy(suggestionBusy = null) }
            return
        }
        val g = state.value.suggestionGroups[index]
        screenModelScope.launchIO {
            val found = try {
                BuilderLateSections.findMore(g.item, samples, pageUrl.ifBlank { suggestionPage() }, requests, before)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.add('W', "lab", "Builder: load more address not checked: ${e.message ?: e.javaClass.simpleName}")
                null
            }
            withContext(Dispatchers.Main) {
                val i = state.value.suggestionGroups.indexOfFirst { it.item == item }
                if (i >= 0) {
                    updateSuggestion(i) { cur ->
                        val m = cur.more ?: return@updateSuggestion cur
                        cur.copy(
                            more = m.copy(
                                request = found?.request.orEmpty(),
                                from = found?.from ?: 2,
                                step = found?.step ?: 1,
                                perPress = added,
                                presses = if (m.presses == 0) 1 else m.presses,
                                scanned = "Scanned: 1 press → $added more cards" + if (found != null) {
                                    " (from ${found.shown})"
                                } else {
                                    " (no address found: the browser presses it)"
                                },
                            ),
                        )
                    }
                    rederive()
                }
                mutableState.update { it.copy(suggestionBusy = null) }
            }
        }
    }

    /**
     * r106: a Suggestions tap on a "Load more" button: the section it sits under (by heading, else the last one tapped)
     * gets it; nothing else is added
     */
    private fun onLoadMoreTapped(more: Derive.LoadMore, section: String) {
        val groups = state.value.suggestionGroups
        if (groups.isEmpty()) {
            mutableState.update { it.copy(pending = null, notice = "Tap a card of the section first, then its \u201cLoad more\u201d button.") }
            return
        }
        // the headings before the button, nearest first: the first one that names a section
        val headings = section.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val index = headings.firstNotNullOfOrNull { h -> groups.indexOfFirst { it.name.equals(h, ignoreCase = true) }.takeIf { it >= 0 } }
            ?: groups.lastIndex
        val g = updateSuggestion(index) { it.copy(more = (it.more?.takeIf { m -> m.button == more.button } ?: more)) } ?: return
        mutableState.update { it.copy(pending = null, notice = null) }
        AppLog.add('I', "lab", "Builder: suggestions \"${g.name}\": Load more button ${more.button} (\"${more.text}\")")
        page("window.__labPicker.keep(${JsonPrimitive(more.button)});")
        rederive()
    }

    /** r104: the Suggestions card's remove (the last one gone = no Suggestions pick) */
    fun removeSuggestionGroup(index: Int) {
        val s = state.value
        val gone = s.suggestionGroups.getOrNull(index) ?: return
        val left = s.suggestionGroups.filterIndexed { i, _ -> i != index }
        AppLog.add('D', "lab", "Builder: suggestions group \"${gone.name}\" removed")
        if (left.isEmpty()) {
            removePick("suggestions")
        } else {
            mutableState.update { it.copy(suggestionGroups = left) }
            rederive()
        }
    }

    /**
     * r104: the Tags card's "Split into rows" on a restored group that reads whole rows or labels ([Derive.needsSplit]):
     * the page finds its details block (picker.js `rowsFor`) and answers with every row ([onSplit])
     */
    fun splitTagGroup(index: Int) {
        val g = state.value.tagGroups.getOrNull(index) ?: return
        val exact = g.exact.trim().removeSuffix(" a").trim()
        val also = JsonArray(listOfNotNull(exact.takeIf { it.isNotEmpty() && it != g.selector }).map { JsonPrimitive(it) })
        AppLog.add('D', "lab", "Builder: split tags group \"${g.name}\" (${g.selector}) into rows")
        mutableState.update { it.copy(notice = "Splitting “${g.name}” into rows… (it needs a title page open)") }
        page("if (window.__labPicker.rowsFor) window.__labPicker.rowsFor(${JsonPrimitive(g.selector)}, $also);")
    }

    /** r104: picker.js `rowsFor`'s answer: the group of [selector] is replaced by its block's rows (missing rows only) */
    private fun onSplit(selector: String, rows: List<DetailRow>) {
        val s = state.value
        val g = s.tagGroups.firstOrNull { it.selector == selector } ?: return
        if (rows.isEmpty()) {
            AppLog.add('W', "lab", "Builder: split \"${g.name}\": no details rows for $selector on ${s.pageUrl.orEmpty()}")
            mutableState.update {
                it.copy(notice = "“${g.name}” isn't on this page: open a title page, then Split into rows again (or tap the details with Tags armed).")
            }
            return
        }
        // r104 fix: every group that reads whole rows or labels goes (the owner's "Tags 2" rows and "Tags" labels are
        // one block: the site's "Tags" row keeps its name); Undo puts the groups back exactly as they were
        val replaced = s.tagGroups.filter { it === g || Derive.needsSplit(it) }
        val (list, added) = withRows(s.tagGroups.filter { it !== g && !Derive.needsSplit(it) }, rows)
        val gone = replaced.map { it.selector }.toSet()
        mutableState.update {
            it.copy(
                tagGroups = inPageOrder(list),
                tagTapOrder = it.tagTapOrder.filter { sel -> sel !in gone } + added,
                lastTagTap = added,
                tagsBefore = it.tagGroups,
                tagOrderBefore = it.tagTapOrder,
                // the split is the last Tags action: Undo undoes it (not another label's pick)
                picks = it.picks.filter { p -> p.pick.label != "tags" } + it.picks.filter { p -> p.pick.label == "tags" },
                notice = if (replaced.size > 1) replacedNotice(replaced) else null,
            )
        }
        AppLog.add(
            'I',
            "lab",
            "Builder: tags group \"${g.name}\" split into ${rows.size} rows (${added.size} new: ${rows.joinToString(", ") { it.label }})" +
                if (replaced.size > 1) "; also replaced ${replaced.filter { it !== g }.joinToString(", ") { "\"${it.name}\"" }}" else "",
        )
        rederive()
    }

    /** r104 fix: the note when groups that read whole rows or labels were replaced by the block's rows */
    private fun replacedNotice(replaced: List<Derive.TagGroup>): String =
        "Replaced ${replaced.joinToString(", ") { "\u201c${it.name}\u201d" }} (whole rows or labels) by the site's " +
            "rows. Undo brings ${if (replaced.size == 1) "it" else "them"} back."

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
            mutableState.update { it.copy(tagGroups = left, tagsBefore = null, tagOrderBefore = emptyList()) }
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
                lastTagTap = if (label == "tags") emptyList() else s.lastTagTap,
                tagsBefore = if (label == "tags") null else s.tagsBefore,
                tagOrderBefore = if (label == "tags") emptyList() else s.tagOrderBefore,
                suggestionGroups = if (label == "suggestions") emptyList() else s.suggestionGroups,
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
            } else if (s.tagsBefore != null &&
                (s.picks.lastOrNull()?.pick?.label == "tags" || s.picks.none { it.pick.label == "tags" })
            ) {
                // r104 fix: the last details-block tap or Split into rows: the groups exactly as they were before
                val back = s.tagsBefore.orEmpty()
                if (back.isEmpty()) {
                    s.copy(
                        picks = s.picks.filter { it.pick.label != "tags" },
                        raws = s.raws - "tags",
                        tagGroups = emptyList(),
                        tagTapOrder = emptyList(),
                        lastTagTap = emptyList(),
                        tagsBefore = null,
                        tagOrderBefore = emptyList(),
                        notice = null,
                    )
                } else {
                    s.copy(
                        tagGroups = back,
                        tagTapOrder = s.tagOrderBefore,
                        lastTagTap = emptyList(),
                        tagsBefore = null,
                        tagOrderBefore = emptyList(),
                        notice = null,
                    )
                }
            } else if (s.picks.lastOrNull()?.pick?.label == "tags" && s.lastTagTap.isNotEmpty() &&
                s.tagGroups.any { it.selector !in s.lastTagTap }
            ) {
                // r104: a details block's tap added several rows: they all go (when they were all the groups, the
                // last branch below removes the Tags pick itself)
                s.copy(
                    tagGroups = s.tagGroups.filter { it.selector !in s.lastTagTap },
                    tagTapOrder = s.tagTapOrder.filter { it !in s.lastTagTap },
                    lastTagTap = emptyList(),
                )
            } else if (s.picks.lastOrNull()?.pick?.label == "suggestions" && s.suggestionGroups.size > 1) {
                // r104: Suggestions: the last section tapped goes, the others stay
                s.copy(suggestionGroups = s.suggestionGroups.dropLast(1))
            } else if (s.picks.lastOrNull()?.pick?.label == "tags" && s.tagGroups.size > 1 &&
                !(s.lastTagTap.isNotEmpty() && s.tagGroups.all { it.selector in s.lastTagTap })
            ) {
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
                    lastTagTap = if ("tags" in kept) s.lastTagTap else emptyList(),
                    tagsBefore = if ("tags" in kept) s.tagsBefore else null,
                    suggestionGroups = if ("suggestions" in kept) s.suggestionGroups else emptyList(),
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
        scheduleRerun() // KMK (r115): the checklist's re-run follows the switch
    }

    /** "Use suggestion": the suggested element gets the card's label (and becomes the page's current element) */
    fun useSuggestion() {
        val s = state.value
        val label = s.helperFor ?: return
        val raw = s.suggestion ?: return
        page("if (window.__labPicker.adopt) window.__labPicker.adopt();")
        AppLog.add('I', "lab", "helper: used suggestion for $label (${raw.likeSelector} ×${raw.count})")
        val old = s.raws[label]?.likeSelector
        applyLabel(label, raw)
        // KMK (r116): the pick it replaced is never suggested back (the new pick's suggestion is asked for above)
        old?.let { suggestTried.add(it) }
    }

    /** "Keep mine" / "OK": the card goes, the pick stays as it is */
    fun dismissHelper() {
        val s = state.value
        val label = s.helperFor ?: return
        if (s.checks[label]?.level == PickCheck.Level.Warn) AppLog.add('D', "lab", "helper $label: kept as picked")
        suggestJob?.cancel() // KMK (r116)
        suggestSeeking = false
        suggestToken++
        mutableState.update { it.copy(helperFor = null, suggestion = null, relabel = null, suggestUi = null) }
        page("window.__labPicker.unsuggest && window.__labPicker.unsuggest();")
    }

    /** "Not this": the pick goes (and its outlines) */
    fun rejectHelperPick() {
        val s = state.value
        val label = s.helperFor ?: return
        val selector = s.raws[label]?.likeSelector
        // r104: Suggestions with other groups: only the last section tapped goes
        if (label == "suggestions" && s.suggestionGroups.size > 1) {
            AppLog.add('D', "lab", "helper suggestions: group \"${s.suggestionGroups.last().name}\" removed (Not this)")
            mutableState.update {
                it.copy(
                    suggestionGroups = it.suggestionGroups.dropLast(1),
                    raws = it.raws - "suggestions",
                    helperFor = null,
                    suggestion = null,
                    relabel = null,
                )
            }
            rederive()
            page("if (window.__labPicker.drop) window.__labPicker.drop(${JsonPrimitive(selector.orEmpty())});")
            return
        }
        // r104: a details block's tap: all the rows it added go
        if (label == "tags" && s.lastTagTap.isNotEmpty() && s.tagGroups.any { it.selector !in s.lastTagTap }) {
            AppLog.add('D', "lab", "helper tags: ${s.lastTagTap.size} rows removed (Not this)")
            mutableState.update {
                it.copy(
                    tagGroups = it.tagGroups.filter { g -> g.selector !in it.lastTagTap },
                    tagTapOrder = it.tagTapOrder.filter { sel -> sel !in it.lastTagTap },
                    lastTagTap = emptyList(),
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
        // r103 fix: Tags with other groups: only the group this tap made goes (a menu tap's "Not this"). r104: a
        // details block's tap that made every group: the Tags pick goes (below)
        if (label == "tags" && s.tagGroups.size > 1 && !(s.lastTagTap.isNotEmpty() && s.tagGroups.all { it.selector in s.lastTagTap })) {
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
        if (s.videoTest?.running == true || s.videoTest?.checking == true) return // KMK (r116): also while VideoCheck runs
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
                    val found = "Found $what in $seconds s · $kinds$from"
                    // KMK (r116): caught is not played: the address must also pass VideoCheck (the first one that
                    // does is kept; else the first one's reason). Shown as "checking…" meanwhile
                    val first = r.videos.first()
                    mutableState.update {
                        it.copy(videoTest = VideoTest(false, false, found, url = first.url, headers = first.headers, checking = true))
                    }
                    checkCaught(found, r.videos)
                } else {
                    VideoTest(false, false, "No video in $seconds s: ${r.reason}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                VideoTest(false, false, "The test failed: ${e.message ?: e.toString()}")
            }
            AppLog.add('I', "lab", "helper test video: ${test.text}${if (test.stage.isNotBlank()) " · ${test.stage}: ${test.detail}" else ""}")
            mutableState.update { it.copy(videoTest = test) }
        }
    }

    /**
     * KMK (r116): runs [VideoCheck] on up to 3 caught addresses (same headers the page used) and keeps the first that
     * passes; "passed" only then. The result names the stage it stopped at (playlist, quality, key, piece, file)
     */
    private suspend fun checkCaught(found: String, videos: List<VideoFinder.Found>): VideoTest {
        var firstFail: Pair<VideoFinder.Found, VideoCheck.Result>? = null
        for (v in videos.take(3)) {
            val c = try {
                VideoCheck.check(v.url, v.headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                VideoCheck.Result(ok = false, stage = "playlist", detail = e.message ?: e.javaClass.simpleName, pieceBytes = 0)
            }
            AppLog.add('D', "lab", "helper video check ${v.url.take(120)}: ${if (c.ok) "ok" else "failed"} at ${c.stage} · ${c.detail}")
            if (c.ok) {
                val bytes = if (c.pieceBytes > 0) " · ${c.pieceBytes / 1024} KB read" else ""
                return VideoTest(false, true, "$found · passed$bytes", url = v.url, headers = v.headers, stage = c.stage, detail = c.detail)
            }
            if (firstFail == null) firstFail = v to c
        }
        val (v, c) = firstFail ?: return VideoTest(false, false, found)
        return VideoTest(false, false, "$found · doesn't play yet", url = v.url, headers = v.headers, stage = c.stage, detail = c.detail)
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
        // r104: the Suggestions sections
        builder["suggestionGroups"] = JsonArray(
            s.suggestionGroups.map { jsJson.encodeToJsonElement(Derive.SuggestionGroup.serializer(), it) },
        )
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
            val related = builder.arr("suggestionGroups")?.mapNotNull { el ->
                runCatching { jsJson.decodeFromJsonElement(Derive.SuggestionGroup.serializer(), el) }.getOrNull()
            }.orEmpty()
            builder["extras"]?.let { snapshot ->
                runCatching { extras.restore(snapshot) }
                    .onFailure { AppLog.add('W', "lab", "Builder: lists and options not restored: ${it.message}") }
            }
            mutableState.update {
                it.copy(
                    raws = raws,
                    picks = picks,
                    tagGroups = groups,
                    suggestionGroups = related,
                    fromCode = fromCode,
                    singleEpisode = builder.bool("singleEpisode") ?: false,
                    anime = builder.bool("anime") ?: info.anime,
                    json = builder.bool("json") ?: info.json,
                )
            }
            // r106: a build saved in place keeps the hash of the code it wrote (its History note can't say "by tapping")
            val written = builder.int("codeHash")
            val byHand = if (written != null) written != code.trimEnd().hashCode() else editedByHand(key, code)
            banner = if (byHand) {
                // r102 fix: Save to this source regenerates from the picks: the hand edits would go (History keeps them)
                AppLog.add('I', "lab", "Builder: edit ${info.name} ($key): its code was edited by hand after it was built")
                "This source was edited by hand after it was built; Save to this source rewrites it from your picks " +
                    "(the current code stays in History)"
            } else {
                "Editing ${info.name}: your ${picks.size} picks${if (groups.isNotEmpty()) ", ${groups.size} tag groups" else ""} " +
                    "and lists are back. Change what's wrong, then Save to this source."
            }
            AppLog.add(
                'I',
                "lab",
                "Builder: edit ${info.name} ($key): builder state restored (${picks.size} picks, ${groups.size} tag groups, " +
                    "${related.size} suggestion groups; ${groups.count { Derive.needsSplit(it) }} tag groups to split into rows)",
            )
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
        refreshVersions(key)
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
    private suspend fun saveInto(info: JsSourceInfo, generated: eu.kanade.tachiyomi.jssource.lab.GeneratedSource, inPlace: Boolean = false) {
        var code = generated.code
        info.customId?.let { code = LabStore.injectId(code, generated.json, it) }
        // r106: the saved builder state remembers the code it wrote (Edit by tapping's "edited by hand" check)
        val lab = withCodeHash(generated.lab, code)
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
                lab = lab.toJson(),
            ),
            // r106: Save to the open version: overwritten in place (History.updateCurrent through LabStore.onSaved; a
            // source with no version yet gets a new one with this note), as the code editor's Save does since r103
            note = if (inPlace) LabStore.UPDATE_NOTE + "Edited by tapping" else "Edited by tapping",
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
        AppLog.add('I', "lab", "Builder: edited ${saved.name} (${info.key})" + if (inPlace) " in place (v${state.value.openVersion ?: "?"})" else " as a new version")
        refreshVersions(info.key)
    }

    /** r106: `builder.codeHash` added to the saved builder state in [lab]'s picks */
    private fun withCodeHash(lab: eu.kanade.tachiyomi.jssource.lab.LabMeta, code: String): eu.kanade.tachiyomi.jssource.lab.LabMeta {
        val picks = lab.picks as? JsonObject ?: return lab
        val builder = picks.obj("builder") ?: return lab
        val hashed = JsonObject(builder + ("codeHash" to JsonPrimitive(code.trimEnd().hashCode())))
        return lab.copy(picks = JsonObject(picks + ("builder" to hashed)))
    }

    /** r106: the open version and the next number of [key]'s History, for "Save to vN" / "Save as new version (vM)" */
    private fun refreshVersions(key: String) {
        val index = runCatching { History.index(key) }.getOrNull() ?: return
        val open = History.currentOf(index).takeIf { it > 0 && History.updateBlockedReason(index) == null }
        val next = maxOf(index.lastN, index.versions.lastOrNull()?.n ?: 0) + 1
        mutableState.update { it.copy(openVersion = open, nextVersion = next) }
    }

    /**
     * r106: Save. [inPlace] true (editing a source) = to the open version ([State.openVersion]), overwritten in place;
     * its Baseline asks first ([State.saveConfirm], the r103 question). false = a new version. A new source is
     * always written as new (v1). [write] with no arguments = a new version, as before
     */
    fun write(inPlace: Boolean) {
        val s = state.value
        val key = s.editKey
        if (!inPlace || key == null || s.openVersion == null) {
            writeSource(inPlace = false)
            return
        }
        val question = runCatching { History.overwriteConfirm(History.index(key)) }.getOrNull()
        if (question != null) {
            AppLog.add('D', "lab", "Builder: save to v${s.openVersion}: asking first ($question)")
            mutableState.update { it.copy(saveConfirm = question) }
            return
        }
        writeSource(inPlace = true)
    }

    /** r106: the Baseline question's Overwrite */
    fun confirmSave() {
        if (state.value.saveConfirm == null) return
        mutableState.update { it.copy(saveConfirm = null) }
        writeSource(inPlace = true)
    }

    /** r106: the Baseline question closed without overwriting (its "Save as new" calls [write] (false) after) */
    fun cancelSave() = mutableState.update { it.copy(saveConfirm = null) }

    companion object {
        /** r106 fix: how long "Scan it now" waits for the page's answer (picker.js gives up after 10 s itself) */
        const val SCAN_TIMEOUT_MS = 15_000L

        /** r106 fix: the picker log line of a Load more that is a link ([onScanLink]) */
        const val SCAN_LINK = "scanlink: "

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
            // KMK (r112) fix: a hand-edited details block is read back too (generic-list v13)
            "TAG_BLOCK" to "tagBlock",
            "SUGGESTION_GROUPS" to "suggestionGroups",
        )

        /** r104: labels that stay armed after a tap (each tap adds a group) */
        val STICKY_LABELS = setOf("tags", "suggestions")

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
            "suggestions" to listOf("suggestionGroups"),
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
        val selection = runCatching {
            withOverrides(withCode(Derive.derive(s.site, s.anime, s.picks, s.singleEpisode, s.tagGroups, s.suggestionGroups), s), s)
        }
            .onFailure { AppLog.add('W', "lab", "Derive failed: ${it.message}") }
            .getOrNull()
        mutableState.update { it.copy(selection = selection) }
        recheck()
        scheduleRerun()
    }

    /** KMK (r115): the checklist's one-tap value changes ([State.attrOverrides]) over what Derive found */
    private fun withOverrides(selection: BuilderSelection, s: State): BuilderSelection {
        if (s.attrOverrides.isEmpty()) return selection
        return selection.copy(
            derived = selection.derived + s.attrOverrides,
            reasons = selection.reasons + s.attrOverrides.mapValues { (_, v) -> "set by the checklist: $v holds the picture in the raw page" },
        )
    }

    // ---------- KMK (r115, mockup EHelper2): Helper 2, one checklist ----------

    /**
     * Runs every pick again on the raw page and on up to 3 titles ([BuilderRecheck]), 0.8 s after the last change (a
     * burst of picks makes one run). Pages already read are reused, so a re-run is only Jsoup work. Off with "Check
     * each pick".
     */
    private fun scheduleRerun(delayMs: Long = 800L) {
        rerunJob?.cancel()
        val s = state.value
        if (!s.helper || s.selection == null || (s.picks.isEmpty() && s.tagGroups.isEmpty())) {
            if (s.rechecks.isNotEmpty() || s.recheckBusy) mutableState.update { it.copy(rechecks = emptyMap(), recheckBusy = false, recheckNote = null) }
            return
        }
        rerunJob = screenModelScope.launchIO {
            delay(delayMs)
            val now = state.value
            val derived = now.selection?.derived ?: return@launchIO
            val input = BuilderRecheck.Input(
                derived = derived,
                raws = now.raws,
                picked = now.picks.map { it.pick.label }.toSet() + (if (now.tagGroups.isNotEmpty()) setOf("tags") else emptySet()),
                tagGroups = now.tagGroups,
                singleEpisode = now.singleEpisode,
            )
            val plan = BuilderRecheck.plan(input)
            mutableState.update { it.copy(recheckBusy = true) }
            try {
                val result = BuilderRecheck.run(input, rerunPages, now.site.trimEnd('/') + "/")
                val note = listOfNotNull(
                    if (plan.list != null) "the raw list page" else null,
                    if (plan.titles.isNotEmpty()) "${plan.titles.size} ${if (plan.titles.size == 1) "title" else "titles"}" else null,
                    if (plan.chapter != null) "a chapter" else null,
                ).joinToString(" · ").ifBlank { null }
                mutableState.update { it.copy(rechecks = result, recheckBusy = false, recheckNote = note) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('W', "lab", "helper2: re-run failed: ${e.message}")
                mutableState.update { it.copy(recheckBusy = false, recheckNote = "not re-run: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** The checklist's "Check again" */
    fun rerunNow() = scheduleRerun(delayMs = 0L)

    /** One checklist row for [label] ([PickCheck.row] over the tap's verdict and the re-run) */
    fun checklistRow(s: State, label: String): PickCheck.Row {
        val picked = if (label == "tags") s.tagGroups.isNotEmpty() else s.pick(label) != null
        return PickCheck.row(
            label = label,
            picked = picked || s.fromCodeOnly(label),
            optional = label in optionalLabels,
            verdict = if (picked) s.checks[label] else null,
            recheck = if (picked && s.helper) s.rechecks[label] else null,
        )
    }

    /** A checklist row's one action (only what the builder already does) */
    fun applyFix(label: String, fix: PickCheck.Fix) {
        AppLog.add('I', "lab", "helper2 $label: ${fix.kind} ${fix.value}")
        when (fix.kind) {
            PickCheck.FixKind.UseAttr -> {
                val key = if (label == "bigCover") "detailsCoverAttr" else "coverAttr"
                mutableState.update { it.copy(attrOverrides = it.attrOverrides + (key to fix.value)) }
                rederive()
            }
            PickCheck.FixKind.SplitRows -> fix.value.toIntOrNull()?.let { splitTagGroup(it) }
            PickCheck.FixKind.TapAgain -> {
                if (state.value.helperFor == label) rejectHelperPick()
                arm(label)
            }
            // opened by the screen (it owns the navigator)
            PickCheck.FixKind.PageSource -> Unit
        }
    }

    /** r102: Edit by tapping on an older source: the code's values for every label not tapped again */
    private fun withCode(selection: BuilderSelection, s: State): BuilderSelection {
        if (s.fromCode.isEmpty()) return selection
        // The list paths only while no list label was tapped again here (then Derive reads them from that page)
        val listTapped = Step.ListPage.labels.any { it in s.raws }
        val kept = s.fromCode.filter { (key, value) ->
            value.isNotBlank() && key !in LIST_KEYS && key != "singleEpisode" &&
                (labelOfKey(key)?.let { s.pick(it) == null } ?: !listTapped) &&
                !(s.tagGroups.isNotEmpty() && (key == "tagsSelector" || key == "tagGroups")) &&
                !(s.suggestionGroups.isNotEmpty() && key == "suggestionGroups")
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

    /** Done: the generic-list template filled with the derived values, handed to Half A (a new version when editing) */
    fun write() = write(inPlace = false)

    private fun writeSource(inPlace: Boolean) {
        val s = state.value
        val selection = s.selection ?: withOverrides(withCode(Derive.derive(s.site, s.anime, s.picks, s.singleEpisode, s.tagGroups, s.suggestionGroups), s), s)
        // r99: the JSON format has no way to say "the title page is the one episode": written as JS then
        // r99 fix: nor "no chapter link was picked" (its empty CHAPTER selector would fail without saying why): JS too,
        // whose getChapterList throws the clear error
        val noChapter = selection.derived["chapterSelector"].isNullOrBlank() && !s.singleEpisode
        // r100 (W3): tabs, filters, settings and a recorded search exist only in the JS template
        val extrasUsed = runCatching { extras.used }.getOrDefault(false)
        // r101: the title page's own fields (Cover, Description, Tags, Author, Status) are read only by the JS template
        val detailsPicked = s.picks.any { it.pick.label in optionalLabels } || s.tagGroups.isNotEmpty() || s.suggestionGroups.isNotEmpty() ||
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
        // KMK (r116): this write's stamp (LabMeta.detectedAt): a new source's fingerprints find their key by it
        val stamp = System.currentTimeMillis()
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
                    // r104 fix: the edited source's own search path tells link-made filters which page is the search's
                    val extraValues = extras.templateValues(s.site, s.fromCode["searchPath"])
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
                        detectedAt = stamp,
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
                    saveInto(editing, generated, inPlace)
                    saveFingerprints(editing.key, fingerprinted(s), s) // KMK (r116)
                    mutableState.update { it.copy(busy = null, delivered = true) }
                    return@launchIO
                }
                saveFingerprintsWhenSaved(stamp, s) // KMK (r116): before the handoff, so the save can't be missed
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
