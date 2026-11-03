package eu.kanade.tachiyomi.jssource.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * KMK (r96, Source Lab): what "Build by tapping" decides from the picks, with one reason per decision (D§2.4).
 * Pure functions over the picks, so the same taps always give the same selectors. Every derived key has a
 * sentence in [BuilderSelection.reasons]; the keys are the generic-list template's placeholders.
 *
 * r99 (owner's 123av run): the Item pick was a dropdown option (`button.sel__opt.is-on`) and the source listed
 * nothing; NEXT came out as a bare `a`; CHAPTER defaulted to `a[href*='chapter']` with nothing picked. Now ITEM is
 * always an element that holds the title link (and the cover): a tapped Item that doesn't is replaced by the
 * smallest common ancestor of the Title and Cover picks, with a `warning…` entry the panel shows; NEXT comes from
 * the pager around the tapped link; CHAPTER is empty (the part is "not written") when no chapter was tapped; and
 * [singleEpisode] makes the title page its own only episode (Date / Number read on that page).
 *
 * r101: the title page's own fields tapped on it (Cover, Description, Tags, Author, Status) give `detailsCover` /
 * `detailsCoverAttr`, `descriptionSelector`, `tagsSelector`, `authorSelector`, `statusSelector` (the template's
 * getDetails reads them first; empty = not picked, the template's fallbacks then). [itemSuggestion] is the Item the
 * tap helper offers when the tapped one doesn't hold the Title.
 *
 * r102: Tags are groups ([TagGroup]: one per Details row tapped, named by the row's label): written as `tagGroups`, a
 * JSON text list `[{"name":"Cast","selector":"…","target":"tags"}]` the template's TAG_GROUPS parses; with groups,
 * `tagsSelector` is "" (r101's single selector stays for sources without groups). [Picked] is serializable: the
 * builder keeps its own state in `LabMeta.picks` for "Edit by tapping".
 *
 * r104: [SuggestionGroup]s (the Suggestions label: a title page's "You may like" / "Related videos" sections) are
 * written as `suggestionGroups` (JSON text, "[]" when none), which generic-list v7's getRelated reads; [needsSplit]
 * finds a restored Tags group that reads whole Details rows or their labels (the owner's r103 "Tags 2" / "Tags").
 */
internal object Derive {

    /**
     * r102: one Tags group: the Details row's [name] ("Cast"), a Jsoup [selector] for its values (by the row's label
     * text, from picker.js), and where the values go: [target] "tags" (genres "Cast: Reina Ohara"), "description"
     * (a line "Cast: …" under the description) or "both". [values] are the first ones seen (for the Tags card only).
     */
    @Serializable
    data class TagGroup(
        val name: String,
        val selector: String,
        val target: String = "tags",
        val values: List<String> = emptyList(),
        /** r103: the row's place on the page (picker.js docIndex); groups are kept in the page's order; -1 unknown */
        val order: Int = -1,
        /** r103: an unlabelled row's exact place (picker.js rowExact): used when two rows share one look; "" = none */
        val exact: String = "",
    )

    /**
     * r104: one Suggestions group: a section of the title page ([name] = its heading) whose repeated cards are other
     * titles. [item] = the section's card (a Jsoup selector scoped under the section), [link] / [title] / [cover] inside
     * the card ("" = the card itself is the link / the link's text is the name / the card's first picture), [coverAttr]
     * the picture's attribute. [count], [order] (the section's place on the page) and [values] are for the card only.
     */
    @Serializable
    data class SuggestionGroup(
        val name: String,
        val item: String,
        val link: String = "",
        val title: String = "",
        val cover: String = "",
        val coverAttr: String = "src",
        val count: Int = 0,
        val order: Int = -1,
        val values: List<String> = emptyList(),
        /**
         * r105: the section is filled by the site's script after the page loads: the address its cards come from,
         * with the title's parts as {seg1}… / {path} / {attr:name} (generic-list v8 reads it there); "" = in the page
         */
        val late: String = "",
        /** r105: the section isn't in the page and no request of the page had it (the card's warning); "" = none */
        val lateWarning: String = "",
        /**
         * r106: how the source reads a section that loads late: "" = from its own address ([late]) when one was found,
         * "browser" = the title page opened in the hidden browser (host.render), waiting for the cards
         */
        val read: String = "",
        /** r106: what the address check found ("Checked: 12 cards in its reply"), for the card; "" = nothing said */
        val lateNote: String = "",
        /** r106: the section's "Load more" / "Show more" button (picker.js), null = none found or tapped */
        val more: LoadMore? = null,
    )

    /**
     * r106: a Suggestions section's "Load more" button: [button] its css path on the title page, [text] its label;
     * [presses] how many times the source presses it (0 = off, up to 3); after "Scan it now": [request] the address one
     * press asks for with `{page}` (and the late tokens {seg1}, {attr:…}), the page number of the first press [from]
     * and [step] between presses ("" = no address found: the hidden browser presses the button), [perPress] the cards
     * one press added and [scanned] the line the card shows
     */
    @Serializable
    data class LoadMore(
        val button: String,
        val text: String = "",
        val presses: Int = 0,
        val request: String = "",
        val from: Int = 2,
        val step: Int = 1,
        val perPress: Int = 0,
        val scanned: String = "",
    )

    // No braces (ICU): Jsoup's text pseudo-classes, which a browser's querySelector can't read
    private val TEXT_PSEUDO = Regex(""":(?:matchesOwn|matches|containsOwn|contains)\([^()]*\)""")

    /** r106: [item] as a browser selector (host.render's waitFor): Jsoup's :matches(…) / :contains(…) left out */
    fun browserSelector(item: String): String = item.replace(TEXT_PSEUDO, "").trim()

    /** r104: the `suggestionGroups` placeholder (name, item, link, title, cover, coverAttr of each group) */
    fun suggestionGroupsJson(groups: List<SuggestionGroup>): String = JsonArray(
        groups.filter { it.item.isNotBlank() }.map { g ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(g.name.ifBlank { "Suggestions" }),
                    "item" to JsonPrimitive(g.item),
                    "link" to JsonPrimitive(g.link),
                    "title" to JsonPrimitive(g.title),
                    "cover" to JsonPrimitive(g.cover),
                    "coverAttr" to JsonPrimitive(g.coverAttr.ifBlank { "src" }),
                ) + (if (g.late.isNotBlank()) mapOf("late" to JsonPrimitive(g.late.trim())) else emptyMap()) +
                    // r106 (generic-list v9): a late section read in the hidden browser, waiting for its cards
                    (if (g.read == "browser") mapOf("read" to JsonPrimitive("browser"), "wait" to JsonPrimitive(browserSelector(g.item))) else emptyMap()) +
                    (g.more?.takeIf { it.presses > 0 && it.button.isNotBlank() }?.let { m -> mapOf("more" to moreJson(m, g.item)) } ?: emptyMap()),
            )
        },
    ).toString()

    /** r106: a section's Load more as generic-list v9 reads it */
    private fun moreJson(m: LoadMore, item: String): JsonObject {
        val map = LinkedHashMap<String, JsonElement>()
        map["button"] = JsonPrimitive(m.button)
        if (m.text.isNotBlank()) map["text"] = JsonPrimitive(m.text.take(40))
        map["presses"] = JsonPrimitive(m.presses.coerceIn(0, 3))
        map["wait"] = JsonPrimitive(browserSelector(item))
        if (m.request.isNotBlank() && "{page}" in m.request) {
            map["request"] = JsonPrimitive(m.request.trim())
            map["from"] = JsonPrimitive(m.from)
            map["step"] = JsonPrimitive(m.step.coerceAtLeast(1))
        }
        return JsonObject(map)
    }

    // No braces (ICU): a selector's last element: after the last " > ", "+", "~" or space
    private val LAST_SEGMENT = Regex("""[^\s>+~]+$""")
    // r104 fix: the word as a whole class part (`.row`, `.info-row`, `.watch__info-row`), never inside another word
    // (`.arrow`, `.browse-list`); no braces (ICU)
    private val ROW_CLASS = Regex("""(?i)(^|[ .#_-])(row|field|meta-item|info-item|detail-item)([ .:#_\[-]|$)""")

    /**
     * r104: a Tags group that reads whole Details rows (`dl.info > div.info-row`, `tr`) or the rows' labels (`… > dt`,
     * `th`): its values are "Code FC2-PPV…" or "Code, Type…", never the values alone. A label-anchored group
     * (`:matches(`) never needs it. The Tags card offers "Split into rows" for these.
     */
    fun needsSplit(g: TagGroup): Boolean {
        val sel = g.selector.trim()
        if (sel.isEmpty() || ":matches(" in sel || ":containsOwn(" in sel) return false
        val last = LAST_SEGMENT.find(sel.substringAfterLast(',').trim())?.value.orEmpty()
        val tag = last.substringBefore('.').substringBefore(':').substringBefore('[').lowercase()
        return tag in setOf("dt", "th", "tr", "dl", "table") || ROW_CLASS.containsMatchIn(last)
    }

    /** r102: the `tagGroups` placeholder: name, selector, target of each group (values stay in the builder) */
    fun tagGroupsJson(groups: List<TagGroup>): String = JsonArray(
        groups.filter { it.selector.isNotBlank() }.map { g ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(g.name.ifBlank { "Tags" }),
                    "selector" to JsonPrimitive(g.selector),
                    "target" to JsonPrimitive(g.target.takeIf { it == "description" || it == "both" } ?: "tags"),
                ),
            )
        },
    ).toString()

    /** A pick as the picker script sent it: the saved [BuilderPick] plus what the derivations need */
    @Serializable
    data class Picked(
        val pick: BuilderPick,
        val likeSelector: String,            // the css path without positions: "others like this"
        val hrefs: List<String>,             // the links of the like elements (up to 30)
        val attrsAllHttp: List<String>,      // attributes that hold an absolute address on every like element
        /**
         * r103: the row's selector anchored on its label (`dt:matches(^\s*Author…) + dd`) when the pick sits in a
         * labelled row: the same on every title, unlike positions ("" = none)
         */
        val rowSelector: String = "",
    )

    val COVER_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "srcset", "src")

    /** Class names a build tool generated: they change on every deploy, so a selector on them dies */
    val GENERATED_CLASS = Regex("""\.(css|sc|jss|emotion)-[a-z0-9]{4,}\b""")

    fun derive(
        siteUrl: String,
        anime: Boolean,
        picks: List<Picked>,
        singleEpisode: Boolean = false,
        tagGroups: List<TagGroup> = emptyList(),
        suggestionGroups: List<SuggestionGroup> = emptyList(),
    ): BuilderSelection {
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
        val bigCover = byLabel["bigCover"]?.lastOrNull()
        val description = byLabel["description"]?.lastOrNull()
        val tags = byLabel["tags"]?.lastOrNull()
        val author = byLabel["author"]?.lastOrNull()
        val status = byLabel["status"]?.lastOrNull()

        // Item selector: the smallest element that holds the title link and the cover on every card. A tapped Item
        // is kept only when it holds them (its segment is on their paths); else the derived one is used, with a warning.
        // r99 fix: compared on the full paths (classes, and positions of classless elements), so two different
        // `div`s no longer count as the same element
        val titlePath = title?.let { fullPath(it) }
        val coverPath = cover?.let { fullPath(it) }
        // (full segment, the path it was found on, why)
        val derivedItem: Triple<String, String, String>? = when {
            titlePath != null && coverPath != null -> commonAncestor(titlePath, coverPath)?.let {
                Triple(it, titlePath, "the smallest element that holds both the title link and the cover you tapped")
            } ?: parentOf(titlePath).takeIf { it.isNotBlank() }?.let {
                Triple(lastSegment(it), titlePath, "the element around the title link (the title and cover paths share no element: tap a whole card to be sure)")
            }
            titlePath != null -> parentOf(titlePath).takeIf { it.isNotBlank() }?.let {
                Triple(lastSegment(it), titlePath, "the element around the title link; tap a cover too so the Lab finds the whole card")
            }
            coverPath != null -> parentOf(coverPath).takeIf { it.isNotBlank() }?.let {
                Triple(lastSegment(it), coverPath, "the element around the cover; tap the title link too so the Lab finds the whole card")
            }
            else -> null
        }
        // r99 fix: a bare div / li / span with no class or id on it or the two elements above it matches far more
        // than the cards (every div of the page): not trusted over a tapped Item
        val derivedGeneric = derivedItem != null && isVague(derivedItem.first, derivedItem.second)
        val itemSelector: String
        val tappedItem = item?.let { clean(lastSegment(it.likeSelector)) }
        val tappedFull = item?.let { lastSegment(fullPath(it)) }
        val checks = listOfNotNull(titlePath, coverPath)
        val itemHolds = tappedFull != null && checks.isNotEmpty() && checks.all { holds(tappedFull, it) }
        // The tapped Item is at least the same kind of element as one around the title / cover (maybe another card)
        val itemLikeHolds = tappedFull != null && checks.isNotEmpty() &&
            checks.all { path -> segments(path).any { likeSegment(it) == likeSegment(tappedFull) } }
        when {
            tappedItem != null && itemHolds -> {
                itemSelector = tappedItem.first
                put("itemSelector", itemSelector, "the card you tapped, ${item!!.pick.count} like it on the page; it holds the ${if (cover != null) "title link and the cover" else "title link"}${tappedItem.second}")
            }
            tappedItem != null && derivedGeneric && itemLikeHolds -> {
                itemSelector = tappedItem.first
                val vague = likeSegment(derivedItem!!.first)
                put(
                    "warningItem",
                    "Item: the card found from the title is a bare <$vague> — using ${tappedItem.first} as tapped",
                    "the element around the title link and cover is <$vague> with no class or id on it or the two elements above it, which would match far more than the cards; the Item you tapped is used instead. Check the list shows one entry per card, else tap the whole card again.",
                )
                put("itemSelector", itemSelector, "the card you tapped, ${item!!.pick.count} like it on the page${tappedItem.second}")
            }
            derivedItem != null -> {
                val (sel, why) = clean(likeSegment(derivedItem.first))
                itemSelector = sel
                if (tappedItem != null) {
                    val missing = listOfNotNull(
                        titlePath?.takeIf { !holds(tappedFull!!, it) }?.let { "the title" },
                        coverPath?.takeIf { !holds(tappedFull!!, it) }?.let { "the cover" },
                    ).joinToString(" or ")
                    put(
                        "warningItem",
                        "Item doesn't contain $missing — using $sel instead",
                        "the Item you tapped (${tappedItem.first}) is not around $missing on the page: a card must hold its title link, so the derived card is used. Tap the whole card again if this one is wrong." +
                            (if (derivedGeneric) " The derived <$sel> has no class or id near it and may match more than the cards." else ""),
                    )
                } else if (derivedGeneric) {
                    put(
                        "warningItem",
                        "Item is a bare <$sel> — tap the whole card",
                        "the element around the title link has no class or id on it or the two elements above it, so <$sel> may match far more than the cards. Tap the whole card as Item so the Lab can use it.",
                    )
                }
                put("itemSelector", sel, derivedItem.third + why)
            }
            tappedItem != null -> {
                itemSelector = tappedItem.first
                put("itemSelector", itemSelector, "the card you tapped, ${item!!.pick.count} like it on the page; tap its title link so the Lab can check it holds one${tappedItem.second}")
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
        // r99: the title link's page first (a wrong Item pick may come from anywhere)
        val listUrl = (title ?: item ?: cover)?.pick?.pageUrl ?: siteUrl
        val (popularPath, pagingWhy) = pagePattern(listUrl, next)
        put("popularPath", popularPath, pagingWhy)
        put("latestPath", popularPath, "the same list as Popular; change it when the site has a latest page")
        put("searchPath", "/search?q={q}&page={page}", "a guess: no search was tapped; /?s={q} on WordPress sites")
        if (next != null) {
            val (sel, why) = nextSelector(next)
            put("nextSelector", sel, why)
        }

        // URL pattern of the titles
        val hrefs = (title ?: item)?.hrefs.orEmpty()
        if (hrefs.isNotEmpty()) {
            put("urlPattern", urlPattern(hrefs), "the common shape of the ${hrefs.size} title addresses on the page")
        }

        // Chapters
        if (singleEpisode) {
            put("singleEpisode", "true", "you said the title page is the only episode: getChapterList returns that page as episode 1, and the video is read on it")
        } else {
            put("singleEpisode", "false", "episodes are the chapter links of the title page")
        }
        if (singleEpisode) {
            put("chapterSelector", "", "only episode: no chapter links are read")
        } else if (chapter != null) {
            val (sel, why) = clean(toLink(chapter.likeSelector))
            put("chapterSelector", sel, "the chapter link you tapped, ${chapter.pick.count} like it on the title page$why")
            if (chapter.hrefs.isNotEmpty()) {
                put("chapterUrlPattern", urlPattern(chapter.hrefs), "the common shape of the ${chapter.hrefs.size} chapter addresses")
            }
        } else {
            put("chapterSelector", "", "no chapter link tapped: the episodes part is not written until you tap one (or say the title page is the only episode)")
        }
        if (singleEpisode) {
            // On the title page itself: the exact element tapped (with its positions), read once
            if (date != null) {
                put("dateSelector", pageSelector(date), "the date you tapped on the title page, read once for its only episode (datetime, title or text)")
            } else {
                put("dateSelector", "", "no date tapped: the only episode has no date")
            }
            if (number != null) {
                put("numberSelector", pageSelector(number), "the number you tapped on the title page; the code reads its last number (1 when it has none)")
            } else {
                put("numberSelector", "", "no number tapped: the only episode is number 1")
            }
        } else if (date != null) {
            put("dateSelector", relative(date.likeSelector, derived["chapterSelector"].orEmpty()), "the date you tapped, read inside each chapter row (datetime, title or text)")
        } else {
            put("dateSelector", "time", "no date tapped: a <time> element when the row has one")
        }
        if (!singleEpisode && number != null) {
            put("numberSelector", relative(number.likeSelector, derived["chapterSelector"].orEmpty()), "the number you tapped; the code reads the last number in the link text")
        }

        // r101: the title page's own fields: exact paths (one element per page), like-selectors for the tags
        if (bigCover != null) {
            put("detailsCover", fieldSelector(bigCover), "the title's picture you tapped on the title page (${fieldWhere(bigCover)}); og:image and the player's poster when a page has none there")
            val attr = COVER_ATTRS.firstOrNull { it in bigCover.attrsAllHttp }
                ?: COVER_ATTRS.firstOrNull { bigCover.pick.attrs[it]?.isNotBlank() == true } ?: "src"
            put(
                "detailsCoverAttr",
                attr,
                if (attr in bigCover.attrsAllHttp) "holds the picture's full address" else "the first picture attribute present; the code also tries the lazy-load ones and src",
            )
        }
        if (description != null) {
            put("descriptionSelector", fieldSelector(description), "the description you tapped on the title page (${fieldWhere(description)}); the page's meta description when it isn't there")
        }
        // r102: the rows tapped as Tags, one group each
        val groups = tagGroups.filter { it.selector.isNotBlank() }
        if (groups.isNotEmpty()) {
            val where = groups.joinToString(", ") { g ->
                g.name + when (g.target) {
                    "description" -> " (description)"
                    "both" -> " (tags + description)"
                    else -> ""
                }
            }
            put(
                "tagGroups",
                tagGroupsJson(groups),
                "${groups.size} ${if (groups.size == 1) "row" else "rows"} you tapped as Tags, each a group named by its label: $where. " +
                    "Tags show as “Cast: Reina Ohara”, so the app groups them",
            )
            put("tagsSelector", "", "the tags come from the groups above")
            // KMK (r112): the details block they sit in: generic-list v13 also reads its rows no group lists (a "Genres"
            // row the sample title didn't have)
            val block = TagBlock.json(groups.map { it.selector })
            if (block.isNotEmpty()) {
                put(
                    "tagBlock",
                    block,
                    "the details block the Tags rows sit in: on other titles its rows no group lists (Genres, Series…) " +
                        "come too, links / chips as “Genres: Solowork” tags, a short plain row as a line under the description",
                )
            }
        } else if (tags != null) {
            val one = tags.pick.tag == "a" || tags.pick.count >= 2
            val sel = if (one) tags.likeSelector else "${pageSelector(tags)} a"
            put(
                "tagsSelector",
                clean(sel).first,
                if (one) "the tag you tapped and the ${tags.pick.count} like it on the title page" else "the links inside the tag box you tapped",
            )
        }
        // r104: the title page's "You may like" / "Related" sections tapped as Suggestions, one group each
        val related = suggestionGroups.filter { it.item.isNotBlank() }
        if (related.isNotEmpty()) {
            put(
                "suggestionGroups",
                suggestionGroupsJson(related),
                "${related.size} ${if (related.size == 1) "section" else "sections"} you tapped as Suggestions: " +
                    related.joinToString(", ") { "${it.name} (${it.count} cards)" } +
                    // r106: anime and manga title screens both show one named row per section
                    ". The title screen shows each section as its own named row (getRelated)",
            )
        }
        if (author != null) {
            put("authorSelector", fieldSelector(author), "the author you tapped on the title page (${fieldWhere(author)})")
        }
        if (status != null) {
            put("statusSelector", fieldSelector(status), "the status you tapped on the title page (${fieldWhere(status)}); words like Ongoing / Completed are understood")
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
            // r99: the 123av run labelled a text block (div.watch__headinfo) as the player
            if (tag != "video" && tag != "source" && tag != "iframe" && tag != "embed" && tag != "object") {
                put(
                    "warningVideo",
                    "Video frame is a <$tag>, not a player",
                    "the code reads src / data-src of the frame; a <$tag> has none. Tap the player again, or use Up / Down in the hint until it says iframe or video",
                )
            }
            // r100: no TODO any more: the app's video finder (host.findVideo) plays any player, whatever its host
            val where = if (tag == "video" || tag == "source") "a <$tag> on the page" else if (host.isBlank()) "a <$tag> whose address the site's script fills in" else "a <$tag> from $host"
            put("videoNote", "The player tapped is $where.", "the video finder plays it in the hidden browser and catches the stream it asks for, whatever the host")
        } else if (anime) {
            put("videoFrame", "iframe", "no player tapped: the first <iframe> of the episode page")
            put("videoNote", "No player was tapped: every iframe and <video> of the page is tried.", "no player tapped: the video finder tries the page's iframes and videos")
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

    /**
     * r99: true when [path] (an ancestor chain, a pick's full css path) passes through [item] (a full segment): the
     * item holds that element. r99 fix: whole segments only, classes and positions included (`div:nth-of-type(2)`
     * is not `div:nth-of-type(1)`, and `div` is not `div.card`)
     */
    fun holds(item: String, path: String): Boolean = segments(path).any { it == item.trim() }

    /**
     * r99: the smallest element both chains pass through: the deepest segment of [a] (its own element excluded)
     * that is also a segment of [b] above its element. Matched by whole segment (r99 fix: classes, and the position
     * of a classless element, included), so paths cut at different heights still meet
     * (`div.grid > div.card > div.card__body > h3 > a` and `div.card > div.card__poster > a > img` → `div.card`)
     * but two different classless `div`s don't. Pass the picks' full css paths; [likeSegment] gives the selector.
     */
    fun commonAncestor(a: String, b: String): String? {
        val x = segments(a).dropLast(1)
        val y = segments(b).dropLast(1).toSet()
        return x.lastOrNull { it in y && it != "body" }
    }

    private val POSITION = Regex(""":nth-of-type\(\d+\)""")

    private fun segments(path: String): List<String> = path.split(" > ").map { it.trim() }

    /** A segment without its position: what "every card like it" means */
    fun likeSegment(segment: String): String = segment.replace(POSITION, "").trim()

    /** A pick's full path (positions of classless elements kept), else its like selector */
    private fun fullPath(p: Picked): String = p.pick.cssPath.ifBlank { p.likeSelector }

    private val GENERIC_TAGS = setOf("div", "li", "span")

    /** r99 fix: [segment] is a bare div / li / span and neither it nor the two segments above it on [path] has a class or id */
    private fun isVague(segment: String, path: String): Boolean {
        if (likeSegment(segment) !in GENERIC_TAGS) return false
        val all = segments(path)
        val i = all.indexOfLast { it == segment.trim() }
        if (i < 0) return true
        return all.subList(maxOf(0, i - 2), i + 1).none { it.contains('.') || it.startsWith("#") }
    }

    /** r99: an element of a page read once (not per row): its exact path, positions kept */
    private fun pageSelector(p: Picked): String =
        p.pick.cssPath.ifBlank { p.likeSelector } // "body > " kept: without it a path can be a bare tag

    /**
     * r103: a title page field's selector that holds on every title of the source: its labelled row's selector
     * (`dt:matches(Author) + dd`), else the look-alike path without positions when it is the only one on the page
     * (positions differ from title to title), else the exact path; generated class names left out
     */
    fun fieldSelector(p: Picked): String {
        val raw = when {
            p.rowSelector.isNotBlank() -> p.rowSelector
            p.pick.count <= 1 && p.likeSelector.isNotBlank() -> p.likeSelector
            else -> pageSelector(p)
        }
        return clean(raw).first
    }

    private fun fieldWhere(p: Picked): String = when {
        p.rowSelector.isNotBlank() -> "by its row's label, the same on every title"
        p.pick.count <= 1 && p.likeSelector.isNotBlank() -> "the only one like it on the page, without positions"
        else -> "its exact place"
    }

    /**
     * r99: the next-page link, never a bare `a` (r98 wrote `a`, which is every link of the page): inside the pager
     * that holds the tapped link (the nearest ancestor with a class or id, one that says pag/nav first), by
     * `rel=next`, its aria-label, its words ("Next", "›", "»"), or for a page number the numbered links (page N+1
     * is what POPULAR_PATH's {page} asks for).
     */
    fun nextSelector(next: Picked): Pair<String, String> {
        val segments = next.likeSelector.split(" > ").map { it.trim() }
        val ancestors = segments.dropLast(1).filter { it.contains('.') || it.startsWith("#") }
        val container = ancestors.lastOrNull { Regex("(?i)pag|nav").containsMatchIn(it) } ?: ancestors.lastOrNull()
        val own = lastSegment(next.likeSelector).replace(Regex(""":nth-of-type\(\d+\)"""), "").ifBlank { "a" }
        val element = own
        val prefix = container?.let { "$it " }.orEmpty()
        val rel = next.pick.attrs["rel"].orEmpty()
        val aria = next.pick.attrs["aria-label"].orEmpty()
        val text = next.pick.text.trim()
        return when {
            rel.split(' ').any { it.equals("next", true) } ->
                "$prefix$element[rel~=next]" to "the next-page link you tapped has rel=next${container?.let { ", inside $it" }.orEmpty()}"
            aria.isNotBlank() && aria.length <= 40 && !aria.contains('"') ->
                "$prefix$element[aria-label=\"$aria\"]" to "the next-page link you tapped, by its label \"$aria\"${container?.let { ", inside $it" }.orEmpty()}"
            Regex("""(?i)^(next|next page|›|»|→|older)$""").matches(text) || (Regex("""(?i)\bnext\b""").containsMatchIn(text) && text.length <= 20) ->
                "$prefix$element:containsOwn(${text.replace(")", "").replace("(", "")})" to "the next-page link you tapped, by its words \"$text\"${container?.let { ", inside $it" }.orEmpty()}"
            Regex("""^\d+$""").matches(text) && container != null ->
                "$container $element:matchesOwn(^\\s*\\d+\\s*\$)" to "you tapped page $text of the numbered pager $container: page N+1 is the next one ({page} in the list path)"
            container != null ->
                "$container $element" to "the links of the pager $container around the link you tapped"
            element != "a" && element.contains('.') ->
                element to "the next-page link you tapped (no pager element around it was found)"
            else ->
                "a[rel~=next]" to "the tapped link had no class and no pager around it: a bare `a` would be every link, so rel=next is used"
        }
    }

    fun parentOf(path: String): String = path.split(" > ").dropLast(1).joinToString(" > ")

    /**
     * r101: the Item the tap helper suggests when the tapped one doesn't hold the Title (or Cover): the like-path
     * (positions dropped) down to the smallest element around both the Title and the Cover pick ([commonAncestor] on
     * their full css paths), else down to the parent of the one that was picked. Null with neither, or when only
     * `body` is left.
     */
    fun itemSuggestion(titlePath: String?, coverPath: String?): String? {
        val base = titlePath?.takeIf { it.isNotBlank() } ?: coverPath?.takeIf { it.isNotBlank() } ?: return null
        val other = if (base == titlePath) coverPath?.takeIf { it.isNotBlank() } else null
        val all = segments(base)
        val anchor = other?.let { commonAncestor(base, it) }
        val end = if (anchor != null) all.indexOfLast { it == anchor } else all.lastIndex - 1
        if (end < 0) return null
        val selector = all.subList(0, end + 1).joinToString(" > ") { likeSegment(it) }
        return selector.takeIf { it.isNotBlank() && it != "body" }
    }

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
