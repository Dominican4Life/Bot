package eu.kanade.tachiyomi.ui.sourcelab.pagesource

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.sitecheck.PageFetch
import eu.kanade.tachiyomi.jssource.lab.sitecheck.SiteRead
import eu.kanade.tachiyomi.ui.sourcelab.newsource.BuilderScreenModel
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r115, mockup XSource): the state behind Page source. One address is read twice at the same time: Raw (the
 * app's client, what a source receives) and Rendered (the hidden browser's outerHTML after the scripts) with the
 * requests the page made. [State.itemSelector] (the builder's Item, or the source's `ITEM` from its code) is counted
 * in both, for the "only in Rendered" banner. Nothing is saved.
 */
internal class PageSourceScreenModel(
    url: String,
    private val sourceKey: String?,
    itemSelector: String?,
) : StateScreenModel<PageSourceScreenModel.State>(State(url = url, address = url, itemSelector = itemSelector?.takeIf { it.isNotBlank() })) {

    @Immutable
    data class State(
        val url: String,
        val address: String,
        val itemSelector: String?,
        val sourceName: String? = null,
        val rawLoading: Boolean = true,
        val renderedLoading: Boolean = true,
        val raw: PageFetch.Raw? = null,
        val rendered: PageFetch.Rendered? = null,
        val rawLines: HtmlLines.Lines = HtmlLines.Lines(emptyList(), false),
        val renderedLines: HtmlLines.Lines = HtmlLines.Lines(emptyList(), false),
        /** the Item selector's matches in Raw / Rendered; -1 = not counted (no selector, or it can't be read) */
        val rawCount: Int = -1,
        val renderedCount: Int = -1,
        /** "Next page: …", "Embedded data: …" (from Raw, else Rendered) */
        val found: List<String> = emptyList(),
        /** the request opened in the Requests tab and its reply as lines (null = none open) */
        val request: PageFetch.Req? = null,
        val requestLines: List<String>? = null,
        val requestNote: String? = null,
    ) {
        val onlyRendered: Boolean get() = rawCount == 0 && renderedCount > 0
    }

    private val context get() = Injekt.get<Application>()
    private var job: Job? = null
    private var requestJob: Job? = null
    private val pretty = Json { prettyPrint = true }

    // declared before init: init's coroutines count at once
    private val countLock = Any()

    init {
        if (sourceKey != null) {
            screenModelScope.launchIO {
                val manager = Injekt.get<JsSourceManager>()
                val info = runCatching { manager.get(sourceKey) }.getOrNull()
                // The source's own Item selector when none came with the screen (the code editor's ⋮)
                val fromCode = if (state.value.itemSelector == null) {
                    runCatching { manager.code(sourceKey)?.let { BuilderScreenModel.codeValues(it)["itemSelector"] } }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                mutableState.update { it.copy(sourceName = info?.name, itemSelector = it.itemSelector ?: fromCode) }
                if (fromCode != null) recount()
            }
        }
        load(url)
    }

    fun setAddress(text: String) = mutableState.update { it.copy(address = text) }

    /** Reads [url] both ways (Raw first shows as soon as it's in; Rendered takes a few seconds) */
    fun load(url: String = state.value.address) {
        val target = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        job?.cancel()
        mutableState.update {
            it.copy(
                url = target,
                address = target,
                rawLoading = true,
                renderedLoading = true,
                raw = null,
                rendered = null,
                rawLines = HtmlLines.Lines(emptyList(), false),
                renderedLines = HtmlLines.Lines(emptyList(), false),
                rawCount = -1,
                renderedCount = -1,
                found = emptyList(),
                request = null,
                requestLines = null,
                requestNote = null,
            )
        }
        AppLog.add('I', "lab", "Page source: $target${sourceKey?.let { " (source $it)" }.orEmpty()}")
        job = screenModelScope.launchIO {
            val referer = target.toRefererOrNull()
            // a child of this load: a new address cancels it too
            val renderJob = launch {
                val rendered = PageFetch.rendered(context, target)
                val lines = HtmlLines.split(rendered.html)
                mutableState.update { it.copy(rendered = rendered, renderedLines = lines, renderedLoading = false) }
                recount()
            }
            val raw = PageFetch.raw(target, referer)
            val text = if (raw.json) prettyJson(raw.body) ?: raw.body else raw.body
            mutableState.update { it.copy(raw = raw, rawLines = HtmlLines.split(text), rawLoading = false) }
            recount()
            renderJob.join()
        }
    }

    /** The Item selector in both pages, and what the page shows of its next page and embedded data */
    private fun recount() = synchronized(countLock) {
        val s = state.value
        val selector = s.itemSelector
        val rawDoc = s.raw?.takeIf { it.body.isNotBlank() }?.let { runCatching { Jsoup.parse(it.body, it.url) }.getOrNull() }
        val renderedDoc = s.rendered?.takeIf { it.html.isNotBlank() }?.let { runCatching { Jsoup.parse(it.html, it.url) }.getOrNull() }
        val rawCount = if (s.rawLoading) -1 else SiteRead.count(rawDoc, selector)
        val renderedCount = if (s.renderedLoading) -1 else SiteRead.count(renderedDoc, selector)
        val found = buildList {
            val doc = rawDoc ?: renderedDoc
            val base = if (rawDoc != null) s.raw?.url.orEmpty() else s.rendered?.url.orEmpty()
            if (doc != null) {
                val next = SiteRead.nextPage(doc, base)
                add(if (next != null) "Next page: ${SiteRead.shortAddress(next, base)}" else "Next page: none found")
                val embedded = SiteRead.embedded(doc)
                if (embedded.isNotEmpty()) add("Embedded data: " + embedded.joinToString(", "))
                val cards = SiteRead.cards(doc, base)
                if (cards.count > 0) add("Title links: ${cards.count} like each other")
            }
            if (renderedDoc != null && rawDoc != null) {
                val rawCards = SiteRead.cards(rawDoc, s.raw?.url.orEmpty()).count
                val shown = SiteRead.cards(renderedDoc, s.rendered?.url.orEmpty()).count
                if (shown > rawCards) add("Title links: $rawCards in Raw, $shown in Rendered")
            }
        }
        mutableState.update { it.copy(rawCount = rawCount, renderedCount = renderedCount, found = found) }
    }

    /** The Requests tab: the reply of [req] read again with the app's client (JSON shown pretty) */
    fun openRequest(req: PageFetch.Req) {
        requestJob?.cancel()
        mutableState.update { it.copy(request = req, requestLines = null, requestNote = "Reading…") }
        requestJob = screenModelScope.launchIO {
            try {
                val reply = PageFetch.raw(req.url, state.value.url.toRefererOrNull())
                val body = if (reply.json) prettyJson(reply.body) ?: reply.body else reply.body
                val list = if (reply.json) SiteRead.dataList(reply.body) else null
                val note = when {
                    reply.error != null -> "Not read: ${reply.error}"
                    list != null -> "${reply.code} · ${PageFetch.size(reply.body.length.toLong())} · a list of ${list.items} at ${list.path}: " +
                        list.keys.take(8).joinToString(", ")
                    else -> "${reply.code} · ${reply.contentType.substringBefore(';').ifBlank { "no type" }} · ${PageFetch.size(reply.body.length.toLong())}"
                }
                mutableState.update { it.copy(requestLines = HtmlLines.split(body).lines.take(3000), requestNote = note) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                mutableState.update { it.copy(requestLines = emptyList(), requestNote = "Not read: ${e.message}") }
            }
        }
    }

    fun closeRequest() {
        requestJob?.cancel()
        mutableState.update { it.copy(request = null, requestLines = null, requestNote = null) }
    }

    private fun prettyJson(text: String): String? = runCatching {
        val element: JsonElement = jsJson.parseToJsonElement(text.trim())
        pretty.encodeToString(JsonElement.serializer(), element)
    }.getOrNull()

    private fun String.toRefererOrNull(): String? {
        val scheme = substringBefore("://", "")
        if (scheme.isEmpty()) return null
        val host = substringAfter("://").substringBefore('/')
        return "$scheme://$host/"
    }

    override fun onDispose() {
        job?.cancel()
        requestJob?.cancel()
        super.onDispose()
    }
}
