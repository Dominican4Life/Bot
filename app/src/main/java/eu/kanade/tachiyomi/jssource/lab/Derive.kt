package eu.kanade.tachiyomi.jssource.lab

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * KMK (r96, Source Lab): what "Build by tapping" decides from the picks, with one reason per decision (D§2.4).
 * Pure functions over the picks, so the same taps always give the same selectors. Every derived key has a
 * sentence in [BuilderSelection.reasons]; the keys are the generic-list template's placeholders.
 */
internal object Derive {

    /** A pick as the picker script sent it: the saved [BuilderPick] plus what the derivations need */
    data class Picked(
        val pick: BuilderPick,
        val likeSelector: String,            // the css path without positions: "others like this"
        val hrefs: List<String>,             // the links of the like elements (up to 30)
        val attrsAllHttp: List<String>,      // attributes that hold an absolute address on every like element
    )

    val COVER_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "srcset", "src")

    /** Class names a build tool generated: they change on every deploy, so a selector on them dies */
    val GENERATED_CLASS = Regex("""\.(css|sc|jss|emotion)-[a-z0-9]{4,}\b""")

    /** Embed hosts the anime templates can read through the hidden browser */
    val KNOWN_PLAYER_HOSTS = listOf("streamwish", "filemoon", "vidstream", "vidsrc", "wish", "moon", "dood", "mp4upload", "vidhide", "voe")

    fun derive(siteUrl: String, anime: Boolean, picks: List<Picked>): BuilderSelection {
        val derived = LinkedHashMap<String, String>()
        val reasons = LinkedHashMap<String, String>()
        fun put(key: String, value: String, reason: String) {
            derived[key] = value
            reasons[key] = reason
        }
        val byLabel = picks.groupBy { it.pick.label }
        val item = byLabel["item"]?.lastOrNull()
        val title = byLabel["title"]?.lastOrNull()
        val cover = byLabel["cover"]?.lastOrNull()
        val next = byLabel["next"]?.lastOrNull()
        val chapter = byLabel["chapter"]?.lastOrNull()
        val date = byLabel["date"]?.lastOrNull()
        val number = byLabel["number"]?.lastOrNull()
        val image = byLabel["image"]?.lastOrNull()
        val video = byLabel["video"]?.lastOrNull()

        // Item selector: the tapped card, else the smallest ancestor the title and cover share
        val itemSelector: String
        when {
            item != null -> {
                val (sel, why) = clean(lastSegment(item.likeSelector))
                itemSelector = sel
                put("itemSelector", sel, "the card you tapped, ${item.pick.count} like it on the page$why")
            }
            title != null && cover != null -> {
                val common = commonPrefix(title.likeSelector, cover.likeSelector)
                val (sel, why) = clean(lastSegment(common.ifBlank { parentOf(title.likeSelector) }))
                itemSelector = sel
                put("itemSelector", sel, "the smallest element that holds both the title link and the cover you tapped$why")
            }
            title != null -> {
                val (sel, why) = clean(lastSegment(parentOf(title.likeSelector)))
                itemSelector = sel
                put("itemSelector", sel, "the element around the title link; tap a whole card to be sure$why")
            }
            else -> {
                itemSelector = "article"
                put("itemSelector", itemSelector, "nothing tapped on the list page yet: <article> is a placeholder")
            }
        }

        // Title selector, relative to the item
        if (title != null) {
            val rel = relative(title.likeSelector, itemSelector)
            put("titleSelector", rel, "the link you tapped, inside each card (${title.pick.count} like it); its text is the title, its href the address")
        } else {
            put("titleSelector", "a", "no title tapped: the first link of the card")
        }

        // Cover: the element and the attribute that is an absolute address on every card
        if (cover != null) {
            put("coverSelector", relative(cover.likeSelector, itemSelector), "the picture you tapped, inside each card")
            val attr = COVER_ATTRS.firstOrNull { it in cover.attrsAllHttp }
            if (attr != null) {
                put("coverAttr", attr, "the first of ${COVER_ATTRS.joinToString(", ")} that holds an absolute address on all ${cover.pick.count} covers")
            } else {
                val present = COVER_ATTRS.firstOrNull { cover.pick.attrs[it]?.isNotBlank() == true } ?: "src"
                put("coverAttr", present, "no attribute held an absolute address on every cover; $present is the fallback and the code also tries the lazy-load ones")
            }
        } else {
            put("coverSelector", "img", "no cover tapped: the first picture of the card")
            put("coverAttr", "src", "no cover tapped: src, then the lazy-load attributes")
        }

        // Paging and the list paths
        val listUrl = (item ?: title ?: cover)?.pick?.pageUrl ?: siteUrl
        val (popularPath, pagingWhy) = pagePattern(listUrl, next)
        put("popularPath", popularPath, pagingWhy)
        put("latestPath", popularPath, "the same list as Popular; change it when the site has a latest page")
        put("searchPath", "/search?q={q}&page={page}", "a guess: no search was tapped; /?s={q} on WordPress sites")
        if (next != null) {
            put("nextSelector", lastSegment(next.likeSelector), "the next-page link you tapped")
        }

        // URL pattern of the titles
        val hrefs = (title ?: item)?.hrefs.orEmpty()
        if (hrefs.isNotEmpty()) {
            put("urlPattern", urlPattern(hrefs), "the common shape of the ${hrefs.size} title addresses on the page")
        }

        // Chapters
        if (chapter != null) {
            val (sel, why) = clean(toLink(chapter.likeSelector))
            put("chapterSelector", sel, "the chapter link you tapped, ${chapter.pick.count} like it on the title page$why")
            if (chapter.hrefs.isNotEmpty()) {
                put("chapterUrlPattern", urlPattern(chapter.hrefs), "the common shape of the ${chapter.hrefs.size} chapter addresses")
            }
        } else {
            put("chapterSelector", "a[href*='chapter']", "no chapter tapped: links whose address says chapter")
        }
        if (date != null) {
            put("dateSelector", relative(date.likeSelector, derived["chapterSelector"].orEmpty()), "the date you tapped, read inside each chapter row (datetime, title or text)")
        } else {
            put("dateSelector", "time", "no date tapped: a <time> element when the row has one")
        }
        if (number != null) {
            put("numberSelector", relative(number.likeSelector, derived["chapterSelector"].orEmpty()), "the number you tapped; the code reads the last number in the link text")
        }

        // Pages (manga)
        if (image != null) {
            val (sel, why) = clean(lastSegment(image.likeSelector))
            put("imageSelector", sel, "the page picture you tapped, ${image.pick.count} like it on the chapter page$why")
            val attr = COVER_ATTRS.firstOrNull { it in image.attrsAllHttp }
                ?: COVER_ATTRS.firstOrNull { image.pick.attrs[it]?.isNotBlank() == true } ?: "src"
            put("imageAttr", attr, if (attr in image.attrsAllHttp) "holds an absolute address on all ${image.pick.count} pictures" else "the first attribute present; the code also tries the lazy-load ones")
        } else if (!anime) {
            put("imageSelector", "img", "no page picture tapped: every <img> of the chapter page")
            put("imageAttr", "src", "no page picture tapped: src, then the lazy-load attributes")
        }

        // Videos (anime)
        if (video != null) {
            val tag = video.pick.tag
            val src = video.pick.attrs["src"] ?: video.pick.attrs["data-src"].orEmpty()
            val host = src.toHttpUrlOrNull()?.host ?: src.removePrefix("//").substringBefore('/')
            val frame = if (tag == "video" || tag == "source") "video source, video[src]" else lastSegment(video.likeSelector)
            put("videoFrame", frame, "the player you tapped ($tag)")
            val known = tag == "video" || tag == "source" || KNOWN_PLAYER_HOSTS.any { it in host }
            if (known) {
                put("videoNote", "Player host $host is one the hidden browser reads.", "a known embed host: the .m3u8 it loads is caught")
            } else {
                put("videoNote", "TODO: unknown player $host: the hidden browser is tried; add an extractor if nothing is caught.", "an embed host the Lab has no extractor for: the part is marked not written")
            }
        } else if (anime) {
            put("videoFrame", "iframe", "no player tapped: the first <iframe> of the episode page")
            put("videoNote", "TODO: unknown player: no player was tapped.", "no player tapped: the part is marked not written")
        }

        return BuilderSelection(
            siteUrl = siteUrl,
            anime = anime,
            picks = picks.map { it.pick },
            derived = derived,
            reasons = reasons,
        )
    }

    // ---------- selector helpers ----------

    /** Drops generated class names from a selector; the reason suffix says so when it happened */
    fun clean(selector: String): Pair<String, String> {
        if (!GENERATED_CLASS.containsMatchIn(selector)) return selector to ""
        val cleaned = GENERATED_CLASS.replace(selector, "").replace(Regex("""\s*>\s*(?=>|$)"""), "").trim()
        return cleaned.ifBlank { selector.substringBefore('.') } to
            "; generated class names (css-…) were left out: they change on every deploy (rule selector-generated-class)"
    }

    fun lastSegment(path: String): String = path.split(" > ").last().trim()

    fun parentOf(path: String): String = path.split(" > ").dropLast(1).joinToString(" > ")

    /** The link inside a chapter row: the path itself when it ends in `a`, else `<row> a` */
    private fun toLink(path: String): String {
        val last = lastSegment(path)
        return if (last == "a" || last.startsWith("a.") || last.startsWith("a:")) last else "$last a"
    }

    /** The part of [path] under [item] (the segments after the item's segment), else the path's last segment */
    fun relative(path: String, item: String): String {
        val segments = path.split(" > ")
        val index = segments.indexOfLast { it == item }
        return if (index >= 0 && index < segments.lastIndex) segments.drop(index + 1).joinToString(" > ") else segments.last()
    }

    fun commonPrefix(a: String, b: String): String {
        val x = a.split(" > ")
        val y = b.split(" > ")
        val n = x.zip(y).takeWhile { (p, q) -> p == q }.size
        return x.take(n).joinToString(" > ")
    }

    // ---------- addresses ----------

    /** `/manga/{slug}/`-style shape: segments equal on every address stay, the rest become {slug} or {n} */
    fun urlPattern(hrefs: List<String>): String {
        val paths = hrefs.mapNotNull { it.toHttpUrlOrNull()?.encodedPath }.filter { it.length > 1 }
        if (paths.isEmpty()) return "/"
        val split = paths.map { it.trim('/').split('/') }
        val length = split.map { it.size }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return "/"
        val same = split.filter { it.size == length }
        val out = (0 until length).map { i ->
            val values = same.map { it[i] }.toSet()
            when {
                values.size == 1 -> values.first()
                values.all { it.all(Char::isDigit) } -> "{n}"
                else -> "{slug}"
            }
        }
        return "/" + out.joinToString("/") + (if (paths.first().endsWith("/")) "/" else "")
    }

    /**
     * The list path with `{page}` in place of the page number: taken from the next link when it differs from
     * the page by one number, else from a page number already in the address, else `?page=` is appended.
     */
    fun pagePattern(pageUrl: String, next: Picked?): Pair<String, String> {
        val page = pageUrl.toHttpUrlOrNull()
        val pagePath = page?.let { it.encodedPath + (it.encodedQuery?.let { q -> "?$q" } ?: "") } ?: "/"
        val nextHref = next?.pick?.attrs?.get("href") ?: next?.hrefs?.firstOrNull()
        val nextUrl = nextHref?.let { h -> page?.resolve(h) ?: h.toHttpUrlOrNull() }
        if (nextUrl != null) {
            val nextPath = nextUrl.encodedPath + (nextUrl.encodedQuery?.let { "?$it" } ?: "")
            val numbers = Regex("""\d+""").findAll(nextPath).toList()
            val here = Regex("""\d+""").findAll(pagePath).map { it.value }.toList()
            // The number that changed between this page and the next one; the last number when they can't be paired
            val candidate = if (here.size == numbers.size) {
                numbers.withIndex().lastOrNull { (i, m) -> m.value != here[i] }?.value
            } else {
                null
            } ?: numbers.lastOrNull()
            if (candidate != null) {
                val pattern = nextPath.replaceRange(candidate.range, "{page}")
                return pattern to "the next-page link's address differs from this page by one number (${candidate.value}); {page} goes there"
            }
        }
        Regex("""([?&]page=)(\d+)""").find(pagePath)?.let { m ->
            return pagePath.replaceRange(m.groups[2]!!.range, "{page}") to "the page number already in the address"
        }
        Regex("""/page/(\d+)""").find(pagePath)?.let { m ->
            return pagePath.replaceRange(m.groups[1]!!.range, "{page}") to "the /page/N/ number already in the address"
        }
        val joiner = if ("?" in pagePath) "&" else "?"
        return "$pagePath${joiner}page={page}" to "no page number in the address and no next link tapped: ?page= is the common guess"
    }
}
