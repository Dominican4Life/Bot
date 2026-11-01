package eu.kanade.tachiyomi.ui.sourcelab.newsource

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.jssource.arr
import eu.kanade.tachiyomi.jssource.int
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.BuilderPick
import eu.kanade.tachiyomi.jssource.lab.BuilderSelection
import eu.kanade.tachiyomi.jssource.lab.Derive
import eu.kanade.tachiyomi.jssource.lab.Detector
import eu.kanade.tachiyomi.jssource.lab.Generator
import eu.kanade.tachiyomi.jssource.lab.LabHandoff
import eu.kanade.tachiyomi.jssource.lab.LabMeta
import eu.kanade.tachiyomi.jssource.lab.Templates
import eu.kanade.tachiyomi.jssource.obj
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r96, Source Lab): the state behind "Build by tapping" (D§2.4): the page in the WebView, the element just
 * tapped and waiting for a label, the labelled picks, and what [Derive] made of them, re-derived after every
 * pick so the reasons are shown live. Done → the generic-list template filled with the derived values, handed to
 * Half A through LabHandoff with the picks in `LabMeta.picks`.
 */
internal class BuilderScreenModel(private val startUrl: String?, anime: Boolean) :
    StateScreenModel<BuilderScreenModel.State>(State(url = startUrl.orEmpty(), anime = anime)) {

    /** The three pages of the flow and the labels each offers */
    enum class Step(val number: Int, val title: String, val labels: List<String>) {
        ListPage(1, "home page", listOf("item", "title", "cover", "next")),
        TitlePage(2, "title page", listOf("chapter", "date", "number")),
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
        /** r99: "tap", or "up" / "down" when the hint's Up / Down moved the pick */
        val via: String = "tap",
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
        /** Pick mode: every tap selects an element and nothing navigates; off = browse the site normally */
        val pickMode: Boolean = true,
        val pickerScript: String? = null,
        val pending: RawPick? = null,
        val picks: List<Derive.Picked> = emptyList(),
        val selection: BuilderSelection? = null,
        val keepSelector: String? = null,        // the selector to outline as kept, with its tick
        val keepTick: Int = 0,
        val busy: String? = null,
        val error: String? = null,
        val delivered: Boolean = false,
        /**
         * r99: "This page is the only episode" (123av: one video per title page, no episode list). The title
         * page is then the single episode: no chapter link is needed, Date / Number picks describe that episode,
         * and the Video frame step runs on the title page itself.
         */
        val singleEpisode: Boolean = false,
    ) {
        val site: String get() = (pageUrl ?: loadUrl ?: url).let { Detector.normalize(it) }
        val host: String get() = site.removePrefix("https://").removePrefix("http://")
        fun pick(label: String): Derive.Picked? = picks.lastOrNull { it.pick.label == label }
        val labels: List<String> get() = if (step == Step.ChapterPage) listOf(if (anime) "video" else "image") else step.labels
    }

    private val context get() = Injekt.get<Application>()

    init {
        screenModelScope.launchIO {
            val script = runCatching { context.assets.open("sourcelab/picker.js").bufferedReader().use { it.readText() } }
                .onFailure { AppLog.add('E', "lab", "picker.js not read: ${it.message}") }
                .getOrNull()
            mutableState.update { it.copy(pickerScript = script) }
        }
        if (!startUrl.isNullOrBlank()) load(startUrl)
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
        mutableState.update { it.copy(url = target, loadUrl = target, loadTick = it.loadTick + 1, pending = null, error = null) }
    }

    /** From the WebView: the page finished loading, or its history changed (an in-page navigation too) */
    fun onPageLoaded(url: String?, canGoBack: Boolean, canGoForward: Boolean) {
        mutableState.update {
            it.copy(pageUrl = url ?: it.pageUrl, url = url ?: it.url, canGoBack = canGoBack, canGoForward = canGoForward)
        }
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
        mutableState.update { it.copy(step = target, pending = null) }
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
        )
        if (raw.cssPath.isBlank()) return
        // r99: the text it shows and how it was reached (a tap, or Up / Down in the hint)
        val how = if (raw.via == "tap") "tap" else "tap (${raw.via})"
        val shown = if (raw.snippet.isNotBlank()) " · \"${raw.snippet}\"" else ""
        AppLog.add(
            'D',
            "lab",
            "picker $how ${shortSelector(raw.likeSelector)} ×${raw.count} · ${raw.cssPath}$shown${if (viaConsole) " (console)" else ""}",
        )
        mutableState.update { it.copy(pending = raw) }
    }

    fun onPickerLog(text: String) = AppLog.add('D', "lab", "picker $text")

    /** Gives the pending tap a label; the same label picked again replaces the earlier pick */
    fun label(label: String) {
        val raw = state.value.pending ?: return
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
                pending = null,
                keepSelector = raw.likeSelector,
                keepTick = s.keepTick + 1,
            )
        }
        rederive()
        AppLog.add('I', "lab", "Builder: $label = ${raw.likeSelector} (${raw.count} like it) on ${raw.pageUrl}")
    }

    fun dismissPending() = mutableState.update { it.copy(pending = null) }

    fun removePick(label: String) {
        mutableState.update { s -> s.copy(picks = s.picks.filter { it.pick.label != label }) }
        rederive()
    }

    /** Undo: the last pick goes, or the pending tap */
    fun undo() {
        mutableState.update { s ->
            if (s.pending != null) s.copy(pending = null) else s.copy(picks = s.picks.dropLast(1))
        }
        rederive()
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
        /** The last segment of a selector, as the hint shows it: `div.item > a.title` → `a.title` */
        fun shortSelector(selector: String): String = selector.substringAfterLast(" > ").ifBlank { selector }
    }

    private fun rederive() {
        val s = state.value
        val selection = runCatching { Derive.derive(s.site, s.anime, s.picks, s.singleEpisode) }
            .onFailure { AppLog.add('W', "lab", "Derive failed: ${it.message}") }
            .getOrNull()
        mutableState.update { it.copy(selection = selection) }
    }

    /** Done: the generic-list template filled with the derived values, handed to Half A */
    fun write() {
        val s = state.value
        val selection = s.selection ?: Derive.derive(s.site, s.anime, s.picks, s.singleEpisode)
        // r99: the JSON format has no way to say "the title page is the one episode": written as JS then
        // r99 fix: nor "no chapter link was picked" (its empty CHAPTER selector would fail without saying why): JS too,
        // whose getChapterList throws the clear error
        val noChapter = selection.derived["chapterSelector"].isNullOrBlank() && !s.singleEpisode
        val json = s.json && !s.singleEpisode && !noChapter
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
