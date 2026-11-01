package eu.kanade.tachiyomi.jssource.lab

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs

/**
 * KMK (r100, Source Lab): the facts picker.js sends about one tapped element, as the tap helper reads them. Plain
 * data (no Android), so [PickCheck] can be tested on its own.
 *
 * @param count how many look-alikes the page holds (same tag + class path)
 * @param imgCount pictures in the element or the element itself (img, picture, a background image)
 * @param linkCount `a[href]` in the element or the element itself
 * @param distinctHrefs different addresses the look-alikes' links open (picker caps it at 200)
 * @param boundByScript a player (iframe / video) whose address the site's script fills in (`:src`, `v-bind:src` …)
 * @param nextLike rel=next, an aria-label saying next, or text starting with next / › / » / > / →
 * @param samples the first three look-alikes' texts
 * @param pageDate r101: the first date-looking text of the page ("Release date 2026-10-29"), "" when none
 * @param rowLabel r102: the label of the row the element sits in ("Cast", "Release date"), "" when none
 * @param rowValues r102: that row's first values (its chips' texts, or its text)
 * @param rowChips r102: how many links / chips the row holds (0 = a plain text row)
 * @param inMenu r103: it sits in the site's own menus (picker.js `inSiteMenu`)
 */
internal data class PickInfo(
    val tag: String,
    val count: Int,
    val text: String = "",
    val snippet: String = "",
    val likeSelector: String = "",
    val cssPath: String = "",
    val hrefs: List<String> = emptyList(),
    val attrs: Map<String, String> = emptyMap(),
    val attrsAllHttp: List<String> = emptyList(),
    val pageUrl: String = "",
    val imgCount: Int = 0,
    val linkCount: Int = 0,
    val textLen: Int = 0,
    val distinctHrefs: Int = 0,
    val boundByScript: Boolean = false,
    val nextLike: Boolean = false,
    val samples: List<String> = emptyList(),
    val pageDate: String = "",
    val rowLabel: String = "",
    val rowValues: List<String> = emptyList(),
    val rowChips: Int = 0,
    /** r103 fix: picker.js says it sits in the site's menus (nav, a sidebar…): Tags warns instead of refusing */
    val inMenu: Boolean = false,
)

/**
 * KMK (r100, Source Lab): the tap helper's verdict on one labelled pick in Build by tapping. The owner's r99 Item
 * tap landed on a "New Releases" heading bar (once on the page, no picture, no link) and nothing told him; every
 * label now gets a short list of checks (✓ / ✕), a level (Ok green, Warn amber, Info blue), a title and one
 * sentence, and the builder shows them over the page (mockups `scratchpad/r100-helper/`). Pure: same facts, same
 * verdict.
 *
 * r101: the title page's own fields (Cover, Description, Tags, Author, Status: labels `bigCover`, `description`,
 * `tags`, `author`, `status`), and a hint on the title-page cards when no Date was picked but the page shows a
 * written date ("Tap the release date (e.g. in Details) as Date").
 *
 * r102: Tags in a labelled row (Cast, Maker, Release date…) are a group: checked for values, plain text rows are
 * fine (they go to the description). A Video frame from an ad network ([AD_HOSTS], the list VideoFinder uses) is a
 * Warn ("This frame is an ad (host), not the player").
 */
internal object PickCheck {
    enum class Level { Ok, Warn, Info }

    data class Check(val ok: Boolean, val text: String)

    data class Result(
        val label: String,
        val level: Level,
        val title: String,
        val body: String,
        val checks: List<Check>,
        val samples: List<String> = emptyList(),
    )

    /** The label as the helper names it */
    fun labelName(label: String, anime: Boolean): String = when (label) {
        "item" -> "Item"
        "title" -> "Title"
        "cover" -> "Cover"
        "next" -> "Next page"
        "chapter" -> if (anime) "Episode" else "Chapter"
        "date" -> "Date"
        "number" -> "Number"
        "image" -> "Page image"
        "video" -> "Video"
        "bigCover" -> "Cover"
        "description" -> "Description"
        "tags" -> "Tags"
        "author" -> "Author"
        "status" -> "Status"
        else -> label.replaceFirstChar { it.uppercase() }
    }

    /** r101: the title page's own fields, picked on the title page (none is needed: each has a fallback) */
    val TITLE_FIELDS = listOf("bigCover", "description", "tags", "author", "status")

    private val PLAYER_TAGS = setOf("iframe", "video", "source", "embed", "object")

    /** r102: ad / traffic networks that put frames over players (the same list as VideoFinder's) */
    val AD_HOSTS = Regex(
        "(?i)pnonolet|pemsrv|juicyads|exoclick|exosrv|trafficjunky|trafficstars|tsyndicate|adsterra|popads|" +
            "propellerads|magsrv|realsrv|clickadu|hilltopads|adspyglass|ero-advertising|onclickads|a-ads\\.com|adnxs|" +
            "doubleclick|googlesyndication",
    )

    private val DATE = Regex(
        """(?i)(\d{1,4}[-/.]\d{1,2}([-/.]\d{1,4})?|\b(jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sep(t(ember)?)?|oct(ober)?|nov(ember)?|dec(ember)?)\.?(\s|,|$)""" +
            """|\bago\b|\btoday\b|\byesterday\b|\b\d+\s*(mins?|minutes?|h|hours?|days?|weeks?|months?|years?)\b)""",
    )
    private val DIGIT = Regex("""\d""")

    /** r103: a selector segment whose class says chip / tag / badge (a tag link, not a text block) */
    private val CHIP_SEGMENT = Regex("""(?i)\.(\w+[-_])?(chips?|tags?|badges?|pills?)([-_][\w-]*)?(?!\w)""")
    private val PAGE_TWO = Regex("""[?&](page|p)=2(\D|$)|/page/2(\D|$)""")

    fun check(label: String, pick: PickInfo, others: Map<String, PickInfo>, anime: Boolean, singleEpisode: Boolean): Result {
        val name = labelName(label, anime)
        val checks = ArrayList<Check>()
        fun add(ok: Boolean, pass: String, fail: String) {
            checks += Check(ok, if (ok) pass else fail)
        }
        val item = others["item"]?.takeIf { label != "item" }
        val tapped = pick.snippet.ifBlank { pick.text }.trim().take(60)
        val youTapped = if (tapped.isNotBlank()) "You tapped “$tapped”. " else "You tapped a <${pick.tag}>. "
        var level: Level? = null
        var title: String? = null
        var okBody = ""
        var warnBody = ""

        when (label) {
            "item" -> {
                add(pick.count >= 3, "${pick.count} found", "appears ${times(pick.count)} (items repeat)")
                add(pick.imgCount >= 1, "picture inside", "no picture inside")
                add(pick.linkCount >= 1, "link inside", "no link to a title")
                others["title"]?.let { add(holdsPick(pick, it), "holds the Title", "doesn't hold the Title you picked") }
                others["cover"]?.let { add(holdsPick(pick, it), "holds the Cover", "doesn't hold the Cover you picked") }
                okBody = "${pick.count} boxes, each with a picture and a link to its title."
                val missing = listOfNotNull(
                    if (pick.count < 3) (if (pick.count <= 1) "appears only once" else "appears only ${pick.count} times") else null,
                    if (pick.imgCount < 1 && pick.linkCount < 1) "holds no picture or link" else null,
                    if (pick.imgCount < 1 && pick.linkCount >= 1) "holds no picture" else null,
                    if (pick.linkCount < 1 && pick.imgCount >= 1) "holds no link" else null,
                )
                warnBody = youTapped + "An Item is one box per title, repeated down the list, holding its picture and title link" +
                    (if (missing.isNotEmpty()) " — this ${missing.joinToString(" and ")}." else ".")
            }
            "title" -> {
                if (item != null && pick.count >= 3 && within20(pick.count, item.count)) {
                    add(true, "${pick.count} found, matches ${items(item.count)}", "")
                } else {
                    add(pick.count >= 3, "${pick.count} found", "appears ${times(pick.count)} (titles repeat)")
                    if (item != null && !within20(pick.count, item.count)) {
                        add(false, "", "${pick.count} found but ${items(item.count)}")
                    }
                }
                add(pick.textLen > 0 || pick.text.isNotBlank(), "text in every one", "no text (the title's name is read from it)")
                val isLink = (pick.tag == "a" || pick.linkCount >= 1) && pick.hrefs.isNotEmpty()
                add(isLink, "links to ${linkShape(pick.hrefs)}", "not a link (the title must open its page)")
                if (isLink) {
                    add(
                        pick.distinctHrefs * 5 >= pick.count * 4,
                        "no duplicates",
                        "${pick.count} links but only ${pick.distinctHrefs} different addresses",
                    )
                }
                okBody = "${pick.count} titles${if (item != null) ", one in every Item" else ""}, each one opens a title page."
                warnBody = youTapped + "A Title is the link with the title's name, one in every Item, that opens its page."
            }
            "cover" -> {
                val picture = pick.tag == "img" || pick.tag == "picture" || pick.imgCount >= 1
                add(picture, "picture (${pick.tag})", "not a picture")
                add(pick.count >= 3, "${pick.count} found", "appears ${times(pick.count)} (covers repeat)")
                item?.let { add(within20(pick.count, it.count), "matches ${items(it.count)}", "${pick.count} found but ${items(it.count)}") }
                val attr = Derive.COVER_ATTRS.firstOrNull { it in pick.attrsAllHttp }
                add(attr != null, "address in $attr", "no full address on every one")
                okBody = "${pick.count} pictures${if (item != null) ", one in every Item" else ""}, read from ${attr ?: "src"}."
                warnBody = youTapped + "A Cover is the picture in every Item."
            }
            "next" -> {
                val href = pick.attrs["href"]?.takeIf { it.isNotBlank() } ?: pick.hrefs.firstOrNull()
                add(href != null, "a link", "not a link")
                val resolved = href?.let { resolve(pick.pageUrl, it) }
                val pageTwo = resolved?.let { PAGE_TWO.containsMatchIn(it) } == true || pick.text.trim() == "2"
                add(pick.nextLike || pageTwo, if (pick.nextLike) "says next" else "goes to page 2", "doesn't say next or go to page 2")
                if (resolved != null) {
                    add(sameSite(resolved, pick.pageUrl), "same site", "goes to another site")
                }
                okBody = "Opens the next page of this list on the same site."
                warnBody = youTapped + "Next page is the link to page 2 of the list (“›”, “Next” or “2”)."
            }
            "chapter" -> {
                val here = pick.pageUrl.substringBefore('#').trimEnd('/')
                val word = name.lowercase()
                if (pick.count < 2) {
                    add(false, "", "only one link")
                    if (!singleEpisode) {
                        level = Level.Info
                        title = "Only one $word link — is this the only $word?"
                        warnBody = "Only one link like this is on the page. If the title page is the only $word, turn on " +
                            "“This page is the only episode”; else tap another $word link."
                    }
                } else {
                    add(true, "${pick.count} found", "")
                }
                if (pick.count >= 2) {
                    add(pick.distinctHrefs >= 2, "different addresses", "all go to the same address")
                }
                add(
                    pick.hrefs.isNotEmpty() && pick.hrefs.none { it.substringBefore('#').trimEnd('/') == here },
                    "opens other pages",
                    if (pick.hrefs.isEmpty()) "not a link" else "links to this same page",
                )
                okBody = "${pick.count} links, each opens a different $word."
                if (level == null) warnBody = youTapped + "A $word link is one of the repeated links on the title page, each opening a different one."
            }
            "date" -> {
                val t = pick.text.ifBlank { pick.snippet }
                add(DATE.containsMatchIn(t) || pick.attrs.containsKey("datetime"), "looks like a date", "doesn't look like a date")
                okBody = "Reads as a date: “${t.take(40)}”."
                warnBody = youTapped + "A Date is text like “1 Nov 2026”, “2026-11-01” or “3 days ago”."
            }
            "number" -> {
                val t = pick.text.ifBlank { pick.snippet }
                add(DIGIT.containsMatchIn(t), "has a number", "no number in it")
                okBody = "Has a number: “${t.take(40)}”."
                warnBody = youTapped + "A Number is the text with the chapter or episode number."
            }
            "image" -> {
                add(pick.tag == "img", "picture (img)", "not a picture (img)")
                add(pick.count >= 2, "${pick.count} found", "appears ${times(pick.count)} (pages repeat)")
                val address = pick.attrsAllHttp.isNotEmpty() || Derive.COVER_ATTRS.any { pick.attrs[it]?.isNotBlank() == true }
                add(address, "address present", "no address")
                okBody = "${pick.count} page pictures."
                warnBody = youTapped + "A Page image is one of the big pictures repeated down the chapter page."
            }
            "video" -> {
                val player = pick.tag in PLAYER_TAGS
                // r102: an ad network's frame over the player (the owner's pnonolet.com pick)
                val frameSrc = pick.attrs["src"]?.takeIf { it.isNotBlank() } ?: pick.attrs["data-src"].orEmpty()
                val adHost = if (AD_HOSTS.containsMatchIn(frameSrc)) {
                    resolve(pick.pageUrl, frameSrc)?.toHttpUrlOrNull()?.host ?: frameSrc.take(60)
                } else {
                    null
                }
                add(player, "player box (${pick.tag})", "not a player: try Up, or tap the video's edge")
                if (adHost != null) {
                    add(false, "", "an ad ($adHost)")
                    level = Level.Warn
                    title = "This frame is an ad ($adHost), not the player"
                } else if (player) {
                    add(true, "${pick.count} on the page", "")
                    val src = pick.attrs["src"]?.takeIf { it.isNotBlank() && it != "about:blank" }
                        ?: pick.attrs["data-src"].orEmpty()
                    if (pick.boundByScript || src.isBlank()) {
                        // No address in the page (a script sets it): a note, not a mistake (123av, r99)
                        add(false, "", if (pick.boundByScript) "address set by script" else "no address in the page")
                        level = Level.Info
                        title = "Video player found — needs the hidden browser"
                        warnBody = "This is the player, but its address is filled in by the site's own script after the page loads, " +
                            "so the plain page has none. The Lab will open this page in the hidden browser, press Play, and catch " +
                            "the video it asks for."
                    } else {
                        val host = resolve(pick.pageUrl, src)?.toHttpUrlOrNull()?.host
                        add(true, if (host != null) "address in the page ($host)" else "address in the page", "")
                    }
                }
                okBody = "A player box (${pick.tag}): the Lab opens this page in the hidden browser, presses Play and catches the video."
                warnBody = when {
                    adHost != null -> "This frame is an ad ($adHost), not the player — tap the video itself or use Down / Up."
                    // r102 fix: the Info sentence above ("needs the hidden browser") was overwritten here
                    level == Level.Info -> warnBody
                    else -> "You tapped a <${pick.tag}>, not the player. Try Up, or tap the video's edge."
                }
            }
            // r101: the title page's own fields
            "bigCover" -> {
                val picture = pick.tag == "img" || pick.tag == "picture" || pick.imgCount >= 1
                add(picture, "picture (${pick.tag})", "not a picture")
                val attr = Derive.COVER_ATTRS.firstOrNull { it in pick.attrsAllHttp }
                    ?: Derive.COVER_ATTRS.firstOrNull { pick.attrs[it]?.isNotBlank() == true }
                val address = attr != null || (pick.tag != "img" && pick.imgCount >= 1)
                add(address, if (attr != null) "address in $attr" else "address on the picture inside", "no address")
                okBody = "The title's picture${if (attr != null) ", read from $attr" else ""}; the list's cover stays when a page has none."
                warnBody = youTapped + "Cover is the title's big picture on its page."
            }
            "description" -> {
                val length = maxOf(pick.textLen, pick.text.trim().length)
                add(length >= 40, "$length characters", "only $length characters (a description is a sentence or more)")
                // r103: the owner's Description became `a.chip ×12`: a link / chip is never the description
                val last = pick.likeSelector.substringAfterLast(" > ")
                val chip = pick.tag == "a" || CHIP_SEGMENT.containsMatchIn(last)
                add(!chip, "a text block (${pick.tag})", "a link / chip (${last.ifBlank { pick.tag }}), not the description")
                okBody = "Reads as the description: “${pick.text.trim().take(60)}…”."
                warnBody = youTapped + "Description is the paragraph about the title."
            }
            "tags" -> {
                if (pick.rowLabel.isNotBlank()) {
                    // r102: a row of the Details block: one group, named by its label
                    val values = pick.rowValues.filter { it.isNotBlank() }
                    add(true, "row “${pick.rowLabel}”", "")
                    add(values.isNotEmpty(), if (pick.rowChips > 0) "${pick.rowChips} ${if (pick.rowChips == 1) "value" else "values"}" else "text", "the row is empty")
                    okBody = "Group “${pick.rowLabel}”: ${values.take(4).joinToString(", ")}" +
                        (if (values.size > 4) " …" else "") +
                        (if (pick.rowChips > 0) " (shown as tags)." else " (a text row: added to the description).") +
                        " Tap another row to add a group."
                    warnBody = youTapped + "The row “${pick.rowLabel}” shows nothing to read."
                } else {
                    val shortTexts = pick.samples.filter { it.isNotBlank() }
                    val chips = if (pick.count >= 2) pick.count else pick.linkCount
                    add(chips >= 2, "$chips tags", if (chips == 1) "only one tag" else "no tags")
                    val short = if (pick.count >= 2) {
                        shortTexts.isNotEmpty() && shortTexts.all { it.length <= 40 }
                    } else {
                        pick.linkCount >= 2 && pick.textLen <= pick.linkCount * 40
                    }
                    add(short, "short texts", "long texts (tags are a word or two each)")
                    okBody = "$chips tags${if (shortTexts.isNotEmpty() && pick.count >= 2) ": " + shortTexts.joinToString(", ") else ""}."
                    warnBody = youTapped + "Tags are the genre / tag links of the title (Tap one of them, or the box around them)."
                }
                // r103 fix: a tap in the site's menus (Trending, Types, Collections…) makes its group but warns (r103
                // refused it, and the owner's WordPress tags in the post's footer were refused too)
                if (pick.inMenu) {
                    add(false, "", "in the site's menu")
                    level = Level.Warn
                    title = "This looks like the site's menu, not this title's tags — keep it?"
                    warnBody = youTapped + "It sits in the site's menus (navigation, a sidebar…). Keep mine keeps the group; " +
                        "Not this removes it."
                }
            }
            "author", "status" -> {
                val t = pick.text.ifBlank { pick.snippet }.trim()
                val limit = if (label == "author") 80 else 40
                add(t.isNotEmpty(), "has text", "no text")
                if (t.isNotEmpty()) add(t.length <= limit, "short text", "${t.length} characters (a ${name.lowercase()} is short)")
                okBody = "Reads as the ${name.lowercase()}: “${t.take(40)}”."
                warnBody = youTapped + if (label == "author") {
                    "Author is the short text with the author's (or maker's) name."
                } else {
                    "Status is the short word like “Ongoing” or “Completed”."
                }
            }
            else -> {
                okBody = "Picked ${pick.count} like it."
            }
        }

        val finalLevel = level ?: if (checks.any { !it.ok }) Level.Warn else Level.Ok
        val article = if (name.first().lowercaseChar() in "aeiou") "an" else "a"
        val finalTitle = title ?: when (finalLevel) {
            Level.Ok -> "Looks right — $name"
            Level.Warn -> "This doesn't look like $article $name"
            Level.Info -> name
        }
        var body = if (finalLevel == Level.Ok) okBody else warnBody
        // r101: a title-page card while no Date was picked and the page shows a written date
        if (others["date"] == null && pick.pageDate.isNotBlank() && label in DATE_HINT_LABELS &&
            (label != "video" || singleEpisode)
        ) {
            body = (if (body.isBlank()) "" else "$body ") +
                "Tap the release date (e.g. in Details) as Date: the page shows “${pick.pageDate.take(50)}”."
        }
        val samples = if (label in SAMPLED) pick.samples.filter { it.isNotBlank() }.take(3) else emptyList()
        return Result(label = label, level = finalLevel, title = finalTitle, body = body, checks = checks, samples = samples)
    }

    private val SAMPLED = setOf("item", "title", "chapter", "date", "number", "tags")

    /** r101: the cards that say "tap the release date as Date" (title-page labels; the video one only on that page) */
    private val DATE_HINT_LABELS = setOf("chapter", "number", "video") + TITLE_FIELDS

    private fun items(n: Int): String = if (n == 1) "1 Item" else "$n Items"

    private fun times(n: Int): String = if (n == 1) "1 time" else "$n times"

    /** Counts within ±20 % of each other (a few cards without a cover or a title are normal) */
    private fun within20(count: Int, of: Int): Boolean = of > 0 && abs(count - of) * 5 <= of

    /** The item's own segment (classes, no position) is on the other pick's path: the item holds it */
    private fun holdsPick(item: PickInfo, other: PickInfo): Boolean {
        val itemPath = item.cssPath.ifBlank { item.likeSelector }
        val otherPath = other.cssPath.ifBlank { other.likeSelector }
        if (itemPath.isBlank() || otherPath.isBlank()) return true
        val own = Derive.likeSegment(Derive.lastSegment(itemPath))
        return otherPath.split(" > ").any { Derive.likeSegment(it.trim()) == own }
    }

    /** `/en/v/…`: the first path segments the title links share */
    private fun linkShape(hrefs: List<String>): String {
        val paths = hrefs.mapNotNull { it.toHttpUrlOrNull()?.encodedPath?.trim('/')?.split('/') }
        val first = paths.firstOrNull() ?: return "its page"
        val shared = first.indices.takeWhile { i -> paths.all { it.size > i + 1 && it[i] == first[i] } }.size
        return if (shared == 0) "its page" else "/" + first.take(shared).joinToString("/") + "/…"
    }

    private fun resolve(base: String, href: String): String? {
        if (href.isBlank()) return null
        return base.toHttpUrlOrNull()?.resolve(href)?.toString() ?: href.toHttpUrlOrNull()?.toString()
    }

    private fun sameSite(url: String, page: String): Boolean {
        val a = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return true
        val b = page.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return true
        return a == b || a.endsWith(".$b") || b.endsWith(".$a")
    }
}
