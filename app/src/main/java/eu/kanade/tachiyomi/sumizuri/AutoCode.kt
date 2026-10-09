package eu.kanade.tachiyomi.sumizuri

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: "Auto Code" in the Code editor. Visits a site the way the extensions guide describes and writes a source
 * for it: the site's name and icon, its list pages (popular, latest, search and how they page), the repeated box
 * of each title (link, title, cover with its lazy-loading attribute), a title page (description, cover, genres,
 * the chapter or episode list), and a chapter's pages or an episode's videos (a video tag, stream addresses in the
 * page, or the streams a player loads, caught by the hidden browser).
 *
 * Sites built on Madara or MangaThemesia get those themes' full definitions. Everything it found, and every guess
 * it had to make, is written to [log] so the editor can show it.
 */
object AutoCode {

    enum class Kind { Auto, Manga, Anime }

    class Result(
        val code: String,
        val json: Boolean,
        val anime: Boolean,
        val name: String,
        val lang: String,
        val baseUrl: String,
        val iconUrl: String,
    )

    /** One way to read a value: a CSS selector inside the element (null: the element itself) and an attribute */
    private data class Pick(val selector: String?, val attr: String)

    private sealed interface Content {
        data class Images(val selector: String, val render: Boolean) : Content
        data class ImageUrls(val regex: String, val render: Boolean) : Content
        data class VideoTags(val selector: String, val referer: String) : Content
        data class VideoUrls(val regex: String, val referer: String) : Content
        data class CapturedVideos(val referer: String) : Content
    }

    private class Plan(
        val name: String,
        val lang: String,
        val baseUrl: String,
        val iconUrl: String,
        val anime: Boolean,
        val renderLists: Boolean,
        val popularUrl: String,
        val latestUrl: String,
        val searchUrl: String,
        val itemSelector: String,
        val itemUrl: List<Pick>,
        val itemTitle: List<Pick>,
        val itemCover: List<Pick>,
        val detailsTitle: List<Pick>,
        val detailsCover: List<Pick>,
        val detailsDescription: List<Pick>,
        val detailsGenres: String?,
        val chapterSelector: String?,
        val chapterUrl: List<Pick>,
        val chapterTitle: List<Pick>,
        val content: Content,
    )

    private val network: NetworkHelper by lazy { Injekt.get() }

    suspend fun run(context: Context, address: String, kind: Kind, json: Boolean, log: (String) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val start = normalize(address)
            start.toHttpUrlOrNull() ?: throw IllegalArgumentException("\"$address\" is not a web address")
            log("Opening $start")
            var renderLists = false
            var home = fetch(context, start, log)
            // The site's address after redirects (pornhub.com -> www.pornhub.com)
            val base = home.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: start.toHttpUrlOrNull()!!.let { "${it.scheme}://${it.host}" }
            if (home.render) renderLists = true

            // A known theme: its full definition
            val theme = detectTheme(home.doc)
            if (theme != null && kind != Kind.Anime) {
                log("This site is built on the $theme theme: using its full definition")
                val guess = ApkImport.SourceGuess(siteName(home.doc, base), siteLang(home.doc), base)
                val icon = siteIcon(home.doc, base)
                val code = if (theme == "Madara") SourceThemes.madara(guess, icon) else SourceThemes.mangaThemesia(guess, icon)
                if (!json) log("Theme definitions are JSON, so the JSON tab is used")
                return@withContext Result(code, true, false, guess.name, guess.lang, base, icon)
            }

            // The repeated box of each title
            var items = findItems(home.doc, base)
            if (items == null && !home.render) {
                log("No list of titles in the page source: the site builds it with JavaScript, trying the hidden browser")
                home = fetch(context, start, log, render = true)
                renderLists = true
                items = findItems(home.doc, base)
            }
            items ?: throw IllegalStateException(
                "Couldn't find a list of titles on $start. Try the address of a page that lists titles " +
                    "(such as Popular or Latest), or write the source by hand.",
            )
            log("Titles: ${items.count} boxes matching \"${items.selector}\"")

            // List pages
            val popularLink = findNavLink(home.doc, base, POPULAR_WORDS)
            val latestLink = findNavLink(home.doc, base, LATEST_WORDS)
            log("Popular: ${popularLink ?: "not found, using the start page"}")
            log("Latest: ${latestLink ?: "not found, using the start page"}")
            val popularUrl = paged(context, popularLink ?: start, home.takeIf { popularLink == null }, renderLists, log)
            val latestUrl = paged(context, latestLink ?: start, home.takeIf { latestLink == null }, renderLists, log)
            val searchUrl = findSearch(home.doc, base, popularUrl).also { log("Search: $it") }

            // A title page
            val sample = items.firstUrl
            log("Opening a title: $sample")
            val title = fetch(context, sample, log, render = renderLists)
            val details = readDetails(title.doc)
            details.notes.forEach(log)
            val chapters = findChapters(title.doc, base)
            val anime = when (kind) {
                Kind.Anime -> true
                Kind.Manga -> false
                Kind.Auto -> looksLikeAnime(title.doc, chapters)
            }
            log(if (anime) "Content: anime (videos)" else "Content: manga (pages)")
            val firstChapter = when {
                chapters != null -> {
                    log("${if (anime) "Episodes" else "Chapters"}: ${chapters.count} matching \"${chapters.selector}\"")
                    chapters.firstUrl
                }
                else -> {
                    log("No ${if (anime) "episode" else "chapter"} list: the title page itself is used as its only ${if (anime) "episode" else "chapter"}")
                    sample
                }
            }

            // Pages or videos
            log("Opening ${if (anime) "an episode" else "a chapter"}: $firstChapter")
            val chapterPage = if (firstChapter == sample) title else fetch(context, firstChapter, log, render = renderLists)
            val content = if (anime) findVideos(chapterPage.doc, chapterPage.body, base) else findImages(context, chapterPage, firstChapter, log)
            log(
                when (content) {
                    is Content.Images -> "Pages: images matching \"${content.selector}\""
                    is Content.ImageUrls -> "Pages: image addresses written in the page's scripts"
                    is Content.VideoTags -> "Videos: video tags matching \"${content.selector}\""
                    is Content.VideoUrls -> "Videos: stream addresses written in the page"
                    is Content.CapturedVideos -> "Videos: no stream in the page source, so the hidden browser plays the page and catches the .m3u8 / .mp4 it loads"
                },
            )

            val plan = Plan(
                name = siteName(home.doc, base),
                lang = siteLang(home.doc),
                baseUrl = base,
                iconUrl = siteIcon(home.doc, base),
                anime = anime,
                renderLists = renderLists,
                popularUrl = popularUrl,
                latestUrl = latestUrl,
                searchUrl = searchUrl,
                itemSelector = items.selector,
                itemUrl = items.url,
                itemTitle = items.title,
                itemCover = items.cover,
                detailsTitle = details.title,
                detailsCover = details.cover,
                detailsDescription = details.description,
                detailsGenres = details.genres,
                chapterSelector = chapters?.selector,
                chapterUrl = chapters?.url.orEmpty(),
                chapterTitle = chapters?.title.orEmpty(),
                content = content,
            )
            Result(
                code = if (json) jsonSource(plan) else jsSource(plan),
                json = json,
                anime = anime,
                name = plan.name,
                lang = plan.lang,
                baseUrl = base,
                iconUrl = plan.iconUrl,
            )
        }

    // Fetching

    private class Page(val url: String, val body: String, val doc: Document, val render: Boolean)

    private fun normalize(address: String): String {
        val trimmed = address.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private suspend fun fetch(context: Context, url: String, log: (String) -> Unit, render: Boolean = false): Page {
        if (!render) {
            val request = Request.Builder().url(url)
                .header("User-Agent", network.defaultUserAgentProvider())
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .build()
            val (code, body, finalUrl) = runCatching {
                network.client.newCall(request).execute().use { Triple(it.code, it.body.string(), it.request.url.toString()) }
            }.getOrElse { Triple(0, "", url) }
            val challenge = code in listOf(403, 503) &&
                listOf("cf-chl", "Just a moment", "challenge-platform", "cf_chl").any { it in body }
            if (code in 200..299 && !challenge) return Page(finalUrl, body, Jsoup.parse(body, finalUrl), false)
            log(if (challenge) "A bot check answered ($code): passing it in the hidden browser" else "The site answered $code: trying the hidden browser")
        }
        val rendered = SumizuriRender.render(context, JsonObject(mapOf("url" to JsonPrimitive(url), "timeout" to JsonPrimitive(25_000))))
        val html = rendered["html"] as? String ?: ""
        val finalUrl = rendered["url"] as? String ?: url
        if (html.length < 200) throw IllegalStateException("$url couldn't be loaded, even in the hidden browser")
        return Page(finalUrl, html, Jsoup.parse(html, finalUrl), true)
    }

    // Site details

    private fun detectTheme(doc: Document): String? {
        val html = doc.outerHtml()
        return when {
            doc.selectFirst("div.page-item-detail, div.c-tabs-item__content, body.wp-manga-template-default") != null ||
                ("wp-manga" in html && "madara" in html.lowercase()) -> "Madara"
            doc.selectFirst("div.bs div.bsx, div.listupd div.bsx") != null || "ts_reader" in html -> "MangaThemesia"
            else -> null
        }
    }

    private fun siteName(doc: Document, base: String): String =
        doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().split(" | ", " - ", " – ", " — ", " :: ").map { it.trim() }.filter { it.isNotBlank() }
                .minByOrNull { it.length }
            ?: base.substringAfter("://").removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }

    private fun siteLang(doc: Document): String =
        doc.selectFirst("html[lang]")?.attr("lang")?.take(2)?.lowercase()?.takeIf { it.length == 2 } ?: "en"

    private fun siteIcon(doc: Document, base: String): String =
        doc.select("link[rel~=(?i)apple-touch-icon], link[rel~=(?i)icon]")
            .maxByOrNull { el -> el.attr("sizes").substringBefore('x').toIntOrNull() ?: if ("apple" in el.attr("rel")) 180 else 16 }
            ?.absUrl("href")?.takeIf { it.isNotBlank() }
            ?: "$base/favicon.ico"

    // The titles of a list

    private class Items(
        val selector: String,
        val count: Int,
        val firstUrl: String,
        val url: List<Pick>,
        val title: List<Pick>,
        val cover: List<Pick>,
    )

    /** [href] is on the same site as [base] (with or without www.), and the part after the host */
    private fun sameSite(href: String, base: String): Boolean {
        val a = href.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return false
        val b = base.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return false
        return a == b || a.endsWith(".$b")
    }

    private fun pathOf(href: String): String =
        href.toHttpUrlOrNull()?.let { it.encodedPath + (it.encodedQuery?.let { q -> "?$q" } ?: "") } ?: href

    private fun isTitleLink(href: String, base: String): Boolean {
        if (!sameSite(href, base) || pathOf(href).trim('/').isEmpty()) return false
        val path = pathOf(href).lowercase()
        return NOT_TITLE_PATHS.none { it in path } && path.count { it == '/' } >= 1 && !path.startsWith("/#")
    }

    /** The class-based selector of an element, such as "div.item" */
    private fun signature(el: Element): String {
        val classes = el.classNames().filter { c -> c.isNotBlank() && !c.contains(Regex("\\d{3,}|^css-|^sc-|active|selected")) }
        return el.tagName() + classes.take(2).joinToString("") { ".${it.replace(Regex("([^\\w-])"), "\\\\$1")}" }
    }

    private fun findItems(doc: Document, base: String): Items? {
        // Each link to a title page that comes with a picture, climbed up to its repeated box
        val groups = HashMap<String, MutableList<Element>>()
        doc.select("a[href]").forEach { a ->
            val href = a.absUrl("href")
            if (!isTitleLink(href, base)) return@forEach
            var box: Element = a
            var depth = 0
            while (depth < 5) {
                val parent = box.parent() ?: break
                val sig = signature(box)
                val siblings = parent.children().count { signature(it) == sig }
                if (siblings >= 4 && box.selectFirst("img") != null) {
                    groups.getOrPut(signature(parent) + " > " + sig) { mutableListOf() }.add(box)
                    return@forEach
                }
                box = parent
                depth++
            }
        }
        val best = groups.values
            .map { it.distinct() }
            .filter { it.size >= 4 }
            .maxByOrNull { list -> list.size + if (list.all { it.selectFirst("img") != null }) 1000 else 0 }
            ?: return null
        val sample = best.first()
        val inner = signature(sample)
        val parentSig = sample.parent()?.let(::signature).orEmpty()
        val selector = when {
            sample.classNames().isNotEmpty() && doc.select(inner).size <= best.size + 2 -> inner
            parentSig.contains('.') -> "$parentSig > $inner"
            else -> (listOfNotNull(sample.parent()?.parent()?.let(::signature)?.takeIf { '.' in it }, parentSig).joinToString(" ") + " > $inner").trim()
        }
        val selfIsLink = sample.tagName() == "a"
        val link = if (selfIsLink) null else "a[href]"
        val title = buildList {
            listOf("h1", "h2", "h3", "h4", "h5", "[class*=title]", "[class*=name]").firstOrNull { sample.selectFirst(it) != null }
                ?.let { add(Pick(it, "text")) }
            add(Pick(link, "title"))
            add(Pick("img", "alt"))
            add(Pick(link, "text"))
        }
        return Items(
            selector = selector,
            count = best.size,
            firstUrl = (if (selfIsLink) sample else sample.selectFirst("a[href]")!!).absUrl("href"),
            url = listOf(Pick(link, "href")),
            title = title,
            cover = coverPicks("img"),
        )
    }

    /** The image's own lazy-loading attributes, for an item that is the img itself */
    private fun selfImage() = coverPicks("").map { it.copy(selector = null) }

    private fun coverPicks(img: String) = listOf(
        Pick(img, "data-src"), Pick(img, "data-lazy-src"), Pick(img, "data-original"), Pick(img, "src"),
    )

    // List pages

    private fun findNavLink(doc: Document, base: String, words: Regex): String? =
        doc.select("nav a[href], header a[href], .menu a[href], ul a[href], a[href]")
            .firstOrNull { a ->
                val href = a.absUrl("href")
                sameSite(href, base) && (words.containsMatchIn(a.text()) || words.containsMatchIn(pathOf(href)))
            }
            ?.absUrl("href")

    /** The list address with {page} where the page number goes, from its "page 2" link */
    private suspend fun paged(context: Context, url: String, loaded: Page?, render: Boolean, log: (String) -> Unit): String {
        val page = loaded ?: runCatching { fetch(context, url, log, render) }.getOrNull() ?: return url
        val two = page.doc.select("a[href]").map { it.absUrl("href") }.firstOrNull { href ->
            Regex("""([?&](page|p|pg)=2(?![\d])|/page/2/?(?=$|[?#])|/2/?$)""").containsMatchIn(href)
        } ?: return url
        val template = two
            .replace(Regex("""([?&](?:page|p|pg)=)2(?![\d])"""), "$1{page}")
            .replace(Regex("""/page/2(/?)(?=$|[?#])"""), "/page/{page}$1")
            .let { if ("{page}" in it) it else it.replace(Regex("""/2(/?)$"""), "/{page}$1") }
        return if ("{page}" in template) template else url
    }

    private fun findSearch(doc: Document, base: String, popularUrl: String): String {
        val form = doc.select("form").firstOrNull { f ->
            f.selectFirst("input[type=search], input[name=s], input[name=q], input[name=keyword], input[name=query], input[name=search], input[name=k]") != null
        }
        if (form != null) {
            val input = form.selectFirst("input[type=search], input[name=s], input[name=q], input[name=keyword], input[name=query], input[name=search], input[name=k]")!!
            val name = input.attr("name").ifBlank { "q" }
            val action = form.absUrl("action").ifBlank { "$base/" }
            val hidden = form.select("input[type=hidden][name]").joinToString("") { "&${it.attr("name")}=${it.attr("value")}" }
            val pageParam = when {
                "/page/{page}" in popularUrl -> null
                Regex("""[?&](page|p|pg)=\{page\}""").containsMatchIn(popularUrl) ->
                    Regex("""[?&](page|p|pg)=\{page\}""").find(popularUrl)!!.groupValues[1]
                else -> null
            }
            return if ("/page/{page}" in popularUrl && name == "s") {
                "$base/page/{page}/?s={query}$hidden"
            } else {
                action + (if ('?' in action) "&" else "?") + "$name={query}$hidden" + (pageParam?.let { "&$it={page}" } ?: "")
            }
        }
        return "$base/?s={query}"
    }

    // A title page

    private class Details(
        val title: List<Pick>,
        val cover: List<Pick>,
        val description: List<Pick>,
        val genres: String?,
        val notes: List<String>,
    )

    private fun readDetails(doc: Document): Details {
        val notes = mutableListOf<String>()
        val descriptionSelector = DESCRIPTION_SELECTORS.firstOrNull { sel -> (doc.selectFirst(sel)?.text()?.length ?: 0) > 60 }
        val description = buildList {
            descriptionSelector?.let { add(Pick(it, "text")) }
            add(Pick("meta[property=og:description]", "content"))
            add(Pick("meta[name=description]", "content"))
        }
        notes += "Description: ${descriptionSelector ?: "the page's og:description"}"
        val genres = GENRE_SELECTORS.firstOrNull { doc.select(it).size in 1..40 }
        notes += "Genres: ${genres ?: "none found"}"
        return Details(
            title = listOf(Pick("h1", "text"), Pick("meta[property=og:title]", "content")),
            cover = listOf(Pick("meta[property=og:image]", "content")) + coverPicks("[class*=thumb] img, [class*=cover] img, [class*=poster] img"),
            description = description,
            genres = genres,
            notes = notes,
        )
    }

    private class Chapters(val selector: String, val count: Int, val firstUrl: String, val url: List<Pick>, val title: List<Pick>)

    private fun findChapters(doc: Document, base: String): Chapters? {
        val links = doc.select("a[href]").filter { a ->
            val text = a.text()
            val href = a.absUrl("href")
            sameSite(href, base) && (CHAPTER_TEXT.containsMatchIn(text) || CHAPTER_HREF.containsMatchIn(pathOf(href)))
        }
        if (links.size < 1) return null
        // The repeated row each link sits in (often li), grouped by its parent
        val rows = links.map { a ->
            var row: Element = a
            for (i in 0 until 3) {
                val parent = row.parent() ?: break
                if (parent.children().count { signature(it) == signature(row) } >= 2) break
                row = parent
            }
            row
        }
        val best = rows.groupBy { (it.parent()?.let(::signature).orEmpty()) + " > " + signature(it) }
            .maxByOrNull { it.value.size } ?: return null
        val sample = best.value.first()
        val parentSig = sample.parent()?.let(::signature).orEmpty()
        val selector = if ('.' in parentSig || '#' in parentSig) "$parentSig > ${signature(sample)}" else {
            val grand = sample.parent()?.parent()?.let(::signature).orEmpty()
            listOf(grand, parentSig, signature(sample)).filter { it.isNotBlank() }.joinToString(" > ")
        }
        val isLink = sample.tagName() == "a"
        val link = if (isLink) null else "a[href]"
        return Chapters(
            selector = selector,
            count = best.value.size,
            firstUrl = (if (isLink) sample else sample.selectFirst("a[href]") ?: sample).absUrl("href"),
            url = listOf(Pick(link, "href")),
            title = listOf(Pick(link, "title"), Pick(link, "text")),
        )
    }

    private fun looksLikeAnime(doc: Document, chapters: Chapters?): Boolean {
        val text = doc.text().lowercase()
        return chapters?.selector?.contains("ep", true) == true ||
            doc.selectFirst("video, iframe[src*=embed], iframe[src*=player], iframe[allowfullscreen]") != null ||
            Regex("""\bepisode\b""").containsMatchIn(text) && !Regex("""\bchapter\b""").containsMatchIn(text) ||
            Regex("""\.m3u8|\.mp4""").containsMatchIn(doc.outerHtml())
    }

    // Pages and videos

    private suspend fun findImages(context: Context, page: Page, url: String, log: (String) -> Unit): Content {
        fun inDoc(doc: Document): String? {
            val best = doc.select("div, section, article, main").mapNotNull { box ->
                val images = box.children().flatMap { child -> if (child.tagName() == "img") listOf(child) else child.select("> img, > a > img, > picture img, > div > img") }
                if (images.size >= 3) box to images.size else null
            }.maxByOrNull { it.second } ?: return null
            val box = best.first
            val sel = when {
                box.id().isNotBlank() -> "#${box.id()}"
                else -> signature(box)
            }
            return "$sel img"
        }
        inDoc(page.doc)?.let { return Content.Images(it, page.render) }
        val urls = Regex(IMAGE_URL_REGEX).findAll(page.body).map { it.groupValues[1] }.distinct().toList()
        if (urls.size >= 3) return Content.ImageUrls(IMAGE_URL_REGEX, page.render)
        if (!page.render) {
            log("No pages in the chapter's page source: trying the hidden browser")
            val rendered = fetch(context, url, log, render = true)
            inDoc(rendered.doc)?.let { return Content.Images(it, true) }
        }
        log("Couldn't find the pages: they may come from an API. The source lists every image on the page meanwhile")
        return Content.Images("img", page.render)
    }

    private fun findVideos(doc: Document, body: String, base: String): Content {
        val iframe = doc.selectFirst("iframe[src]")?.absUrl("src")
        val referer = iframe?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: "$base/"
        if (doc.selectFirst("video source[src], video[src]") != null) return Content.VideoTags("video source[src], video[src]", "$base/")
        if (Regex(VIDEO_URL_REGEX).containsMatchIn(body)) return Content.VideoUrls(VIDEO_URL_REGEX, "$base/")
        return Content.CapturedVideos(referer)
    }

    // Writing the source

    private fun jsonPicks(picks: List<Pick>, resolve: Boolean = false): String {
        val parts = picks.map { p ->
            buildString {
                append("{ ")
                p.selector?.let { append("\"selector\": ").append(it.jsQuoted()).append(", ") }
                if (p.attr != "text") append("\"attr\": ").append(p.attr.jsQuoted()).append(", ")
                append("\"transform\": \"trim\"")
                append(" }")
            }
        }
        val spec = if (parts.size == 1) parts.first().removeSuffix(" }") else "{ \"or\": [ ${parts.joinToString(", ")} ]"
        return spec + (if (resolve) ", \"resolve\": true" else "") + " }"
    }

    private fun jsonSource(plan: Plan): String {
        val render = if (plan.renderLists) "\n    \"render\": true," else ""
        fun list(url: String) = """{
    "url": ${url.replace(plan.baseUrl, "{baseUrl}").jsQuoted()},$render
    "itemSelector": ${plan.itemSelector.jsQuoted()},
    "fields": {
      "url": ${jsonPicks(plan.itemUrl, resolve = true)},
      "title": ${jsonPicks(plan.itemTitle)},
      "coverUrl": ${jsonPicks(plan.itemCover, resolve = true)}
    }
  }"""
        val chapters = if (plan.chapterSelector != null) {
            """{
    "url": "{entryUrl}",$render
    "itemSelector": ${plan.chapterSelector.jsQuoted()},
    "fields": {
      "url": ${jsonPicks(plan.chapterUrl, resolve = true)},
      "title": ${jsonPicks(plan.chapterTitle)},
      "number": { ${plan.chapterTitle.last().selector?.let { "\"selector\": ${it.jsQuoted()}, " }.orEmpty()}"regex": "(\\d+(?:\\.\\d+)?)", "transform": "number" }
    }
  }"""
        } else {
            // The title page is its only chapter / episode (single videos, one-shots)
            """{
    "url": "{entryUrl}",
    "itemSelector": "head",
    "fields": {
      "url": { "template": "{entryUrl}" },
      "title": { "template": "${if (plan.anime) "Episode" else "Chapter"} 1" },
      "number": { "template": "1", "transform": "number" }
    }
  }"""
        }
        val content = when (val c = plan.content) {
            is Content.Images -> """"pages": {
    "url": "{chapterUrl}",${if (c.render) "\n    \"render\": true," else ""}
    "itemSelector": ${c.selector.jsQuoted()},
    "fields": {
      "imageUrl": ${jsonPicks(selfImage(), resolve = true)}
    }
  }"""
            is Content.ImageUrls -> """"pages": {
    "url": "{chapterUrl}",${if (c.render) "\n    \"render\": true," else ""}
    "itemsRegex": ${c.regex.jsQuoted()},
    "fields": {
      "imageUrl": { "template": "{value}" }
    }
  }"""
            is Content.VideoTags -> """"videos": {
    "url": "{chapterUrl}",
    "itemSelector": ${c.selector.jsQuoted()},
    "fields": {
      "url": { "attr": "src", "resolve": true },
      "quality": { "or": [ { "attr": "label" }, { "attr": "res" } ], "default": "Video" }
    },
    "videoHeaders": { "Referer": ${c.referer.jsQuoted()} }
  }"""
            is Content.VideoUrls -> """"videos": {
    "url": "{chapterUrl}",
    "itemsRegex": ${c.regex.jsQuoted()},
    "fields": {
      "url": { "template": "{value}" },
      "quality": { "template": "{value}", "regex": "(\\d{3,4}p|m3u8|mp4)", "default": "Video" }
    },
    "videoHeaders": { "Referer": ${c.referer.jsQuoted()} },
    "expandPlaylists": true
  }"""
            is Content.CapturedVideos -> """"videos": {
    "url": "{chapterUrl}",
    "render": { "capture": "\\.m3u8|\\.mp4", "waitForResource": "\\.m3u8|\\.mp4", "timeout": 30000 },
    "itemSelector": "#sumizuri-captured a",
    "fields": {
      "url": { "attr": "href" },
      "quality": { "attr": "href", "regex": "(\\d{3,4}p|m3u8|mp4)", "default": "Auto" }
    },
    "videoHeaders": { "Referer": ${c.referer.jsQuoted()} },
    "expandPlaylists": true
  }"""
        }
        val genres = plan.detailsGenres?.let { ",\n      \"genres\": { \"selector\": ${it.jsQuoted()}, \"all\": true }" }.orEmpty()
        return """// Written by Auto Code from ${plan.baseUrl}. Run it on the Test tab and adjust what's wrong.
{
  "name": ${plan.name.jsQuoted()},
  "lang": ${plan.lang.jsQuoted()},
  "baseUrl": ${plan.baseUrl.jsQuoted()},
  "iconUrl": ${plan.iconUrl.jsQuoted()},
  "headers": { "Referer": "{baseUrl}/" },
  "popular": ${list(plan.popularUrl)},
  "latest": ${list(plan.latestUrl)},
  "search": ${list(plan.searchUrl)},
  "details": {
    "url": "{entryUrl}",$render
    "fields": {
      "title": ${jsonPicks(plan.detailsTitle)},
      "coverUrl": ${jsonPicks(plan.detailsCover, resolve = true)},
      "description": ${jsonPicks(plan.detailsDescription)}$genres,
      "webUrl": { "template": "{entryUrl}" }
    }
  },
  "chapters": $chapters,
  $content
}
"""
    }

    private fun jsPicks(picks: List<Pick>): String =
        picks.joinToString(", ", "[", "]") { "[${it.selector?.jsQuoted() ?: "null"}, ${it.attr.jsQuoted()}]" }

    private fun jsSource(plan: Plan): String {
        /** A JS expression for an address with {page} and {query} in it */
        fun url(template: String): String {
            val out = mutableListOf<String>()
            var rest = template
            if (rest.startsWith(plan.baseUrl)) {
                out += "extension.baseUrl"
                rest = rest.removePrefix(plan.baseUrl)
            }
            var last = 0
            Regex("""\{(page|query)\}""").findAll(rest).forEach { m ->
                if (m.range.first > last) out += rest.substring(last, m.range.first).jsQuoted()
                out += if (m.groupValues[1] == "page") "page" else "encodeURIComponent(query)"
                last = m.range.last + 1
            }
            if (last < rest.length) out += rest.substring(last).jsQuoted()
            return out.joinToString(" + ").ifBlank { "extension.baseUrl" }
        }
        val render = plan.renderLists
        val chapters = if (plan.chapterSelector != null) {
            """
  getChapterList: async function (url) {
    var doc = await page(url, $render);
    var rows = await doc.select(${plan.chapterSelector.jsQuoted()});
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var title = await pick(rows[i], ${jsPicks(plan.chapterTitle)});
      var number = parseFloat(((title || "").match(/(\d+(\.\d+)?)/) || [])[1]);
      out.push({ url: abs(await pick(rows[i], ${jsPicks(plan.chapterUrl)})), title: title, number: isNaN(number) ? undefined : number });
    }
    return out;
  },"""
        } else {
            """
  // The title page is its only ${if (plan.anime) "episode" else "chapter"}
  getChapterList: async function (url) {
    return [{ url: url, title: "${if (plan.anime) "Episode" else "Chapter"} 1", number: 1 }];
  },"""
        }
        val content = when (val c = plan.content) {
            is Content.Images -> """
  getPageList: async function (url) {
    var doc = await page(url, ${c.render});
    var images = await doc.select(${c.selector.jsQuoted()});
    var out = [];
    for (var i = 0; i < images.length; i++) {
      var src = await pick(images[i], ${jsPicks(selfImage())});
      if (src) out.push({ index: out.length, imageUrl: abs(src) });
    }
    return out;
  },"""
            is Content.ImageUrls -> """
  getPageList: async function (url) {
    var html = await text(url, ${c.render});
    var found = [];
    var re = new RegExp(${c.regex.jsQuoted()}, "g"), m;
    while ((m = re.exec(html))) if (found.indexOf(m[1]) < 0) found.push(m[1]);
    return found.map(function (src, i) { return { index: i, imageUrl: src }; });
  },"""
            is Content.VideoTags -> """
  getVideoList: async function (url) {
    var doc = await page(url, false);
    var tags = await doc.select(${c.selector.jsQuoted()});
    return tags.map(function (v) {
      return { url: abs(v.attr("src")), quality: v.attr("label") || v.attr("res") || "Video", headers: { Referer: ${c.referer.jsQuoted()} } };
    });
  },"""
            is Content.VideoUrls -> """
  getVideoList: async function (url) {
    var html = await text(url, false);
    var found = [];
    var re = new RegExp(${c.regex.jsQuoted()}, "g"), m;
    while ((m = re.exec(html))) if (found.indexOf(m[1]) < 0) found.push(m[1]);
    return found.map(function (src) {
      var q = (src.match(/(\d{3,4}p)/) || [])[1] || (src.indexOf(".m3u8") > 0 ? "HLS" : "Video");
      return { url: src, quality: q, headers: { Referer: ${c.referer.jsQuoted()} } };
    });
  },"""
            is Content.CapturedVideos -> """
  // No stream in the page source: the hidden browser plays the page and catches what it loads
  getVideoList: async function (url) {
    var r = await host.render(url, { capture: /\.m3u8|\.mp4/, waitForResource: /\.m3u8|\.mp4/, timeout: 30000 });
    return r.resources.map(function (src) {
      return { url: src, quality: (src.match(/(\d{3,4}p)/) || [])[1] || "Auto", headers: { Referer: ${c.referer.jsQuoted()} } };
    });
  },"""
        }
        val genres = plan.detailsGenres?.let {
            """
      genres: (await doc.select(${it.jsQuoted()})).map(function (g) { return g.text; }),"""
        }.orEmpty()
        return """// Written by Auto Code from ${plan.baseUrl}. Run it on the Test tab and adjust what's wrong.
var extension = {
  name: ${plan.name.jsQuoted()},
  lang: ${plan.lang.jsQuoted()},
  baseUrl: ${plan.baseUrl.jsQuoted()},
  iconUrl: ${plan.iconUrl.jsQuoted()},
  headers: { Referer: ${(plan.baseUrl + "/").jsQuoted()} },

  getPopular: async function (page) {
    return list(${url(plan.popularUrl)});
  },
  getLatest: async function (page) {
    return list(${url(plan.latestUrl)});
  },
  search: async function (query, page, filters) {
    return list(${url(plan.searchUrl)});
  },

  getDetails: async function (url) {
    var doc = await page(url, $render);
    return {
      url: url,
      webUrl: url,
      title: await pickDoc(doc, ${jsPicks(plan.detailsTitle)}),
      coverUrl: abs(await pickDoc(doc, ${jsPicks(plan.detailsCover)})),
      description: await pickDoc(doc, ${jsPicks(plan.detailsDescription)}),$genres
    };
  },
$chapters
$content
};

// One page of titles
async function list(url) {
  var doc = await page(url, $render);
  var items = await doc.select(${plan.itemSelector.jsQuoted()});
  var out = [];
  for (var i = 0; i < items.length; i++) {
    var link = await pick(items[i], ${jsPicks(plan.itemUrl)});
    if (!link) continue;
    out.push({
      url: abs(link),
      title: (await pick(items[i], ${jsPicks(plan.itemTitle)})) || "",
      coverUrl: abs(await pick(items[i], ${jsPicks(plan.itemCover)})),
    });
  }
  return out;
}

// The text of a page, through the hidden browser when the site builds it with JavaScript
async function text(url, render) {
  if (render) return (await host.render(abs(url))).html;
  var response = await fetch(abs(url), { headers: { Referer: extension.baseUrl + "/" } });
  if (!response.ok) throw new Error("The site answered " + response.status + " for " + url);
  return response.text();
}

async function page(url, render) {
  return host.parse(await text(url, render));
}

// The first value found: each pick is [selector inside the element (null: the element itself), attribute or "text"]
async function pick(el, picks) {
  for (var i = 0; i < picks.length; i++) {
    var target = picks[i][0] ? await el.selectOne(picks[i][0]) : el;
    if (!target) continue;
    var value = picks[i][1] === "text" ? target.text : target.attr(picks[i][1]);
    if (value && String(value).trim() && String(value).indexOf("data:") !== 0) return String(value).trim();
  }
  return null;
}

// The same, for a whole page
async function pickDoc(doc, picks) {
  for (var i = 0; i < picks.length; i++) {
    var value = picks[i][1] === "text" ? await doc.text(picks[i][0]) : await doc.attr(picks[i][0], picks[i][1]);
    if (value && String(value).trim()) return String(value).trim();
  }
  return null;
}

function abs(url) {
  if (!url) return url;
  return require("url").resolve(extension.baseUrl, url);
}
"""
    }

    private val POPULAR_WORDS = Regex("""(?i)\b(popular|trending|most[ -]?viewed|top|hot|ranking|views)\b""")
    private val LATEST_WORDS = Regex("""(?i)\b(latest|new|recent|updates?|updated)\b""")
    private val CHAPTER_TEXT = Regex("""(?i)\b(chapter|chap|ch\.?|episode|ep\.?|eps)\s*#?\s*\d""")
    private val CHAPTER_HREF = Regex("""(?i)(chapter|chap|episode|/ep-?\d|-ep-\d|/watch/)""")
    private val NOT_TITLE_PATHS = listOf(
        "/genre", "/tag", "/category", "/page/", "/login", "/register", "/account", "/user", "/search", "/contact",
        "/privacy", "/terms", "/dmca", "/about", "/feed", "/wp-", "/author/", "/bookmark", "/faq", "?page=", "#",
    )
    private val DESCRIPTION_SELECTORS = listOf(
        "[itemprop=description]", ".summary__content", ".description", ".synopsis", ".entry-content",
        "#description", ".desc", ".summary", ".story", ".plot", ".overview", "[class*=description]", "[class*=synopsis]",
    )
    private val GENRE_SELECTORS = listOf(
        ".genres a", ".genre a", "[class*=genre] a", "a[rel=tag]", "a[href*=/genre]", "a[href*=/tag/]", "a[href*=/category/]",
    )
    private const val IMAGE_URL_REGEX = """["'](https?://[^"'\s]+?\.(?:jpe?g|png|webp|gif|avif)(?:\?[^"'\s]*)?)["']"""
    private const val VIDEO_URL_REGEX = """["'](https?://[^"'\s]+?\.(?:m3u8|mp4)(?:\?[^"'\s]*)?)["']"""
}
