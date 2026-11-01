package eu.kanade.tachiyomi.jssource.lab

import android.content.Context
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * KMK (r96, Source Lab): tells which engine a site runs by testing the fixed markers of `fingerprints.json`
 * against at most three pages (home → a title page it links to → the chapters request a candidate needs), then
 * scores every engine: confidence = matched weight ÷ total weight. Nothing here guesses: the same pages always
 * give the same card, and every marker is shown with its evidence whether it matched or not (D§2.2, C§8.4).
 *
 * Fetches go through [LabProbe] (one runtime, closed when detection ends). Budget: 3 `get` + 1 `head`.
 */
internal class Detector(
    private val context: Context,
    private val fingerprints: FingerprintFile = Fingerprints.load(),
) {

    private class Page(val url: String, val reply: LabProbe.ProbeReply) {
        val doc: Document by lazy { Jsoup.parse(reply.body, url) }
        val scripts: String by lazy { doc.select("script").joinToString("\n") { it.data() } }
    }

    /** The pages gathered for one site, read-only for the marker tests */
    private class Pages(
        val siteUrl: String,
        val home: Page?,
        val title: Page?,
        val request: Page?,
        val requestMarkerId: String?,
    )

    private var gets = 0
    private var heads = 0

    /**
     * Runs the sequence for [siteUrl]; [kindHint] null = any engine, false = manga engines, true = anime engines.
     * [onStep] gets the step list every time it changes (the UI shows it as it runs).
     */
    suspend fun detect(siteUrl: String, kindHint: Boolean?, onStep: (List<DetectionStep>) -> Unit): DetectionResult {
        val site = normalize(siteUrl)
        val engines = fingerprints.engines.filter { engine ->
            when (kindHint) {
                null -> true
                true -> engine.kind != "manga"
                false -> engine.kind != "anime"
            }
        }
        val steps = ArrayList<DetectionStep>()
        fun emit() = onStep(steps.toList())
        val probe = LabProbe(context)
        gets = 0
        heads = 0
        try {
            // 1. Home page
            val home = fetchStep(probe, steps, ::emit, "Home page", site, null, "GET") { emit() }
                ?: return finish(site, steps, null, emptyList(), ::emit)

            // 2. The first title page the home page links to, picked by the leading candidates' link selectors
            val afterHome = engines.map { score(it, Pages(site, home, null, null, null)) }.sortedWith(order)
            val titleUrl = titleLink(home, afterHome.map { it.engine })
            val title = if (titleUrl != null) {
                fetchStep(probe, steps, ::emit, "A title page", titleUrl, "first title linked from the home page", "GET") {}
            } else {
                steps.add(DetectionStep("A title page", StepStatus.Skipped, 0, 0, null, "no title link found on the home page"))
                emit()
                null
            }

            // 3. The chapters / episodes request, only when the leading candidate has a request marker
            val afterTitle = engines.map { score(it, Pages(site, home, title, null, null)) }.sortedWith(order)
            val leader = afterTitle.firstOrNull { it.confidence > 0 }?.engine
            val requestMarker = leader?.markers?.firstOrNull { it.where == "request" && it.test.isRequest }
            var request: Page? = null
            if (leader != null && requestMarker != null && gets < MAX_GETS) {
                val test = requestMarker.test
                val path = test.post ?: test.getPath.orEmpty()
                val url = requestUrl(site, title?.url ?: site, path)
                val method = if (test.post != null) "POST" else "GET"
                val headers = if (method == "POST") {
                    mapOf("X-Requested-With" to "XMLHttpRequest", "Content-Type" to "application/x-www-form-urlencoded")
                } else {
                    mapOf("X-Requested-With" to "XMLHttpRequest")
                }
                val name = if (leader.kind == "anime") "Episode list request" else "Chapter list request"
                request = fetchStep(probe, steps, ::emit, name, url, "${leader.name} marker ${requestMarker.id}", method, headers, test.body) {}
            }

            // 4. Compare
            val pages = Pages(site, home, title, request, requestMarker?.id)
            val scored = engines.map { score(it, pages) }.sortedWith(order)
            val best = scored.firstOrNull()?.takeIf { it.confidence > 0 }
            val totalMarkers = engines.sumOf { it.markers.size }
            val next = scored.getOrNull(1)
            steps.add(
                DetectionStep(
                    name = "Compared with ${engines.size} engines",
                    status = if (best != null) StepStatus.Green else StepStatus.Amber,
                    ms = 0,
                    bytes = 0,
                    url = null,
                    note = buildString {
                        append("$totalMarkers markers")
                        if (best != null) {
                            append(" · ${best.matched} of ${best.engine.markers.size} ${best.engine.name} markers matched")
                            if (next != null && next.confidence > 0) append(" · next best ${next.engine.name} ${next.confidence} %")
                        } else {
                            append(" · nothing matched: Build by tapping is the way")
                        }
                    },
                ),
            )
            emit()

            // 5. Fill for the best candidate (the HEAD check counts towards the budget)
            val guesses = scored.map { s ->
                EngineGuess(
                    engineId = s.engine.id,
                    engineName = s.engine.name,
                    kind = s.engine.kind,
                    confidence = s.confidence,
                    markers = s.results,
                    fill = if (s === best) fill(probe, s.engine, pages) else emptyMap(),
                    templateId = s.engine.template,
                )
            }
            val bestGuess = guesses.firstOrNull()?.takeIf { best != null }
            return finish(site, steps, bestGuess, guesses.drop(if (bestGuess != null) 1 else 0), ::emit)
        } finally {
            probe.close()
        }
    }

    private fun finish(
        site: String,
        steps: List<DetectionStep>,
        best: EngineGuess?,
        others: List<EngineGuess>,
        emit: () -> Unit,
    ): DetectionResult {
        emit()
        AppLog.add(
            'I',
            "lab",
            "Detected $site: ${best?.let { "${it.engineName} ${it.confidence} %" } ?: "no engine"} after $gets requests",
        )
        return DetectionResult(
            siteUrl = site,
            steps = steps,
            best = best,
            others = others,
            writeOffered = best != null && best.confidence >= WRITE_THRESHOLD,
        )
    }

    // ---------- fetching ----------

    /** One fetch as a step: Running while in flight, then Green / Amber (not 2xx) / Red (failed, note = the reason) */
    private suspend fun fetchStep(
        probe: LabProbe,
        steps: MutableList<DetectionStep>,
        emit: () -> Unit,
        name: String,
        url: String,
        note: String?,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        onStarted: () -> Unit,
    ): Page? {
        val index = steps.size
        steps.add(DetectionStep(name, StepStatus.Running, 0, 0, url, note))
        emit()
        onStarted()
        if (gets >= MAX_GETS) {
            steps[index] = DetectionStep(name, StepStatus.Skipped, 0, 0, url, "request budget used ($MAX_GETS per detection)")
            emit()
            return null
        }
        gets++
        return try {
            val reply = probe.get(url, headers, method, body)
            val how = if (reply.viaBrowser) "through the browser page" else "app connection"
            val status = if (reply.status in 200..299) StepStatus.Green else StepStatus.Amber
            val shown = "$method ${short(url)} · $how" + (note?.let { " · $it" } ?: "")
            steps[index] = DetectionStep(name, status, reply.ms, reply.bytes, url, "${reply.status} · $shown")
            emit()
            if (status == StepStatus.Green) Page(reply.finalUrl.ifBlank { url }, reply) else Page(url, reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = e.message ?: e.toString()
            steps[index] = DetectionStep(name, StepStatus.Red, 0, 0, url, message)
            emit()
            AppLog.add('W', "lab", "$name failed for $url: $message")
            null
        }
    }

    // ---------- scoring ----------

    private class Scored(val engine: EngineFingerprint, val results: List<EngineMarkerResult>) {
        val matched: Int = results.count { it.matched }
        val matchedWeight: Int = results.filter { it.matched }.sumOf { it.weight }
        val confidence: Int = if (engine.totalWeight == 0) 0 else matchedWeight * 100 / engine.totalWeight
    }

    private val order = compareByDescending<Scored> { it.confidence }.thenByDescending { it.matched }.thenBy { it.engine.id }

    private fun score(engine: EngineFingerprint, pages: Pages): Scored =
        Scored(engine, engine.markers.map { marker -> test(marker, pages) })

    private fun test(marker: Marker, pages: Pages): EngineMarkerResult {
        val t = marker.test
        fun result(matched: Boolean, evidence: String) = EngineMarkerResult(marker.id, matched, marker.weight, evidence)
        if (marker.where == "request") {
            val page = pages.request
            if (page == null || pages.requestMarkerId != marker.id) return result(false, "not requested (${marker.evidence})")
            val wanted = t.status ?: 200
            val ok = page.reply.status == wanted && (t.bodyContains == null || t.bodyContains in page.reply.body)
            return result(ok, if (ok) marker.evidence else "answered ${page.reply.status}; ${marker.evidence} expected")
        }
        val page = when (marker.where) {
            "title" -> pages.title
            else -> pages.home
        }
        if (page == null) return result(false, "page not loaded (${marker.evidence})")
        val text = when (marker.where) {
            "url" -> pages.siteUrl + "\n" + page.url
            "script" -> page.scripts
            else -> page.reply.body
        }
        t.selectorCount?.let { selector ->
            val count = runCatching { page.doc.select(selector).size }.getOrDefault(0)
            val ok = count >= t.min
            val evidence = marker.evidence.replace("{count}", count.toString())
            return result(ok, if (ok) evidence else "$count found, ${t.min} needed · ${marker.evidence.replace("{count}", "n")}")
        }
        t.contains?.let { needle ->
            val ok = needle in text
            return result(ok, if (ok) marker.evidence else "not on this site (${marker.evidence})")
        }
        t.regex?.let { pattern ->
            val ok = runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
            return result(ok, if (ok) marker.evidence else "not on this site (${marker.evidence})")
        }
        return result(false, "unknown test kind (${marker.evidence})")
    }

    // ---------- fill ----------

    private suspend fun fill(probe: LabProbe, engine: EngineFingerprint, pages: Pages): Map<String, String> {
        val home = pages.home ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val origin = home.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: pages.siteUrl
        out["baseUrl"] = origin
        out["name"] = siteName(home.doc, origin)
        out["lang"] = home.doc.selectFirst("html")?.attr("lang")?.take(2)?.lowercase()?.ifBlank { "en" } ?: "en"
        out["iconUrl"] = iconUrl(home.doc, origin)
        for (spec in engine.fill) {
            out[spec.key] = when (spec.from) {
                "regex" -> {
                    val page = if (spec.where == "title") pages.title else home
                    val text = when (spec.where) {
                        "script" -> page?.scripts.orEmpty()
                        else -> page?.reply?.body.orEmpty()
                    }
                    val match = spec.pattern?.let { p -> runCatching { Regex(p).find(text) }.getOrNull() }
                    match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: match?.value ?: spec.default
                }
                "firstAttrWithHttp" -> {
                    val elements = runCatching { home.doc.select(spec.selector.orEmpty()) }.getOrNull()
                    spec.attrs.firstOrNull { attr ->
                        elements != null && elements.isNotEmpty() &&
                            elements.any { el -> el.attr(attr).trim().let { it.startsWith("http") || it.startsWith("//") } }
                    } ?: spec.default
                }
                "probe" -> {
                    val done = pages.request
                    spec.candidates.firstOrNull { c -> done != null && done.reply.status == 200 && done.url.contains(c) }
                        ?: spec.default.ifBlank { spec.candidates.firstOrNull().orEmpty() }
                }
                "head" -> headCheck(probe, home, spec)
                else -> spec.default
            }
        }
        return out
    }

    /** One HEAD of the first cover without a Referer: "yes" when the image host refuses (403), else "no" */
    private suspend fun headCheck(probe: LabProbe, home: Page, spec: FillSpec): String {
        if (!spec.withoutReferer403 || heads >= MAX_HEADS) return spec.default
        val image = runCatching { home.doc.select(spec.selector.orEmpty()) }.getOrNull()
            ?.firstNotNullOfOrNull { el ->
                listOf("data-src", "data-lazy-src", "data-original", "src").firstNotNullOfOrNull { attr ->
                    el.attr(attr).trim().takeIf { it.startsWith("http") || it.startsWith("//") }
                }
            }
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?: return spec.default
        heads++
        return try {
            val reply = probe.head(image)
            if (reply.status == 403) "yes" else "no"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            spec.default
        }
    }

    // ---------- helpers ----------

    private fun titleLink(home: Page, candidates: List<EngineFingerprint>): String? {
        val host = home.url.toHttpUrlOrNull()?.host ?: return null
        fun sameHost(href: String): Boolean {
            val u = href.toHttpUrlOrNull() ?: return false
            return u.host == host && u.encodedPath.length > 1
        }
        for (engine in candidates) {
            if (engine.entryLink.isBlank()) continue
            val links = runCatching { home.doc.select(engine.entryLink) }.getOrNull() ?: continue
            links.firstNotNullOfOrNull { a -> a.absUrl("href").takeIf { sameHost(it) } }?.let { return it }
        }
        home.doc.select("a[href]:has(img)").firstNotNullOfOrNull { a -> a.absUrl("href").takeIf { sameHost(it) } }
            ?.let { return it }
        return home.doc.select("a[href]").firstNotNullOfOrNull { a -> a.absUrl("href").takeIf { sameHost(it) } }
    }

    private fun requestUrl(site: String, titleUrl: String, path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("/") -> site.trimEnd('/') + path
        else -> titleUrl.trimEnd('/') + "/" + path
    }

    private fun siteName(doc: Document, origin: String): String {
        val og = doc.selectFirst("meta[property='og:site_name']")?.attr("content")?.trim()
        val title = doc.title().trim().split(" - ", " | ", " – ", " — ").firstOrNull()?.trim()
        return og?.ifBlank { null } ?: title?.ifBlank { null }
            ?: origin.toHttpUrlOrNull()?.host?.removePrefix("www.")?.substringBefore('.').orEmpty().ifBlank { "Source" }
    }

    private fun iconUrl(doc: Document, origin: String): String {
        val link = doc.select("link[rel~=(?i)icon]").firstNotNullOfOrNull { l -> l.absUrl("href").takeIf { it.startsWith("http") } }
        val host = origin.toHttpUrlOrNull()?.host.orEmpty()
        return link ?: "https://www.google.com/s2/favicons?domain=$host&sz=128"
    }

    private fun short(url: String): String = url.toHttpUrlOrNull()?.let { u ->
        val path = u.encodedPath
        if (path.length > 40) "…" + path.takeLast(38) else "${u.host}$path"
    } ?: url

    companion object {
        const val MAX_GETS = 3
        const val MAX_HEADS = 1
        const val WRITE_THRESHOLD = 60

        /** A site address as the Lab uses it: scheme added, no trailing slash, no query */
        fun normalize(input: String): String {
            val trimmed = input.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            val url = trimmed.toHttpUrlOrNull() ?: return trimmed.trimEnd('/')
            return "${url.scheme}://${url.host}" + (if (url.port != 80 && url.port != 443) ":${url.port}" else "")
        }
    }
}
