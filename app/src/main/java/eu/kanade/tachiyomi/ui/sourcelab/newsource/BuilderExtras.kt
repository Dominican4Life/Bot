package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.jssource.arr
import eu.kanade.tachiyomi.jssource.bool
import eu.kanade.tachiyomi.jssource.int
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.Detector
import eu.kanade.tachiyomi.jssource.obj
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.ui.sourcelab.newsource.dir.DirCache
import eu.kanade.tachiyomi.ui.sourcelab.newsource.dir.DirPager
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/*
 * KMK (r100, Source Lab): "Lists and options" of Build by tapping: the search address recorded from a real search
 * on the site, what Popular / Latest show and their names, extra tabs, search filters offered from address changes,
 * and settings, all without writing code. The values go into the generic-list template (v3) as placeholders:
 * paths override Derive's guesses, the lists are JSON text the template parses (`tabsList`, `filtersList`,
 * `settingsList`; never a name ending in `Json`, which Generator fills from its twin).
 *
 * r103: "Pick a menu on the site" (picker.js `menuMode`): one tap gives the site's menu groups ([MenuGroup]: a
 * heading and its entries, sub-headings as their own groups); each group's card makes its own filter, learns its
 * entries' addresses, or fetches its index page's full list, which becomes a Dropdown or a Text box with
 * suggestions (`type: "autocomplete"`, generic-list v6).
 *
 * r104 (the owner's 123av Browse sheet showed only "Trending" of his 5 filters, and Trending searched /en/search?sort=):
 * a filter without a usable address is never dropped silently ([BuilderExtrasLogic.filterProblem]: the editor shows
 * it in red, the write logs it); a param filter made from links keeps the links' own address ([ExtrasFilter.linkPath],
 * `/en/trending?sort={value}&page={page}`) and is written as a path filter when that page isn't the search page
 * ([BuilderExtrasLogic.filtersJson] with the search path); filters can be moved up / down ([BuilderExtrasModel.moveFilter]).
 *
 * r105 (generic-list v8): each filter has a role ([ExtrasFilter.role]): "page" picks the list (a path filter, links to
 * another page, a directory's text box) and "extra" adds its parameter to whichever list is shown (a dropdown learned
 * on a list page: only ?type= changed). Learn addresses presses every entry of a list of up to 30 (codes such as
 * "multi" for "Multiple" are read, never guessed); longer lists use a pattern checked on two more presses. Filters sit
 * in groups ([ExtrasFilter.group]: a sub-menu's filter in its parent menu's group, "Move to group…"). Filters made
 * before r105 (no role) are written in the v7 shape and the template reads them as before.
 *
 * r106 (the owner's mockups "Filters as menu cards"): every picked menu is a card with only the buttons it needs
 * ([MenuGroup.kind]): a dropdown of buttons learns its codes on the card itself (pause, progress rows, Stop; the r102
 * Learn card at the top is gone), links make a filter or tabs, a directory reads its first page by itself
 * ([DirProbe]) and offers "Read all pages" on the card ([GroupRead]), a heading of sub-menus is one card with a row per
 * sub-menu and "Read all N". A filter whose name (or address parameter) another filter already has is not added
 * silently: [PendingFilter] asks Replace "<name>" / Keep both (the owner's two "Sort" filters). The pane scrolls a
 * card into view when it appears or starts working ([BuilderExtras.scrollTarget]).
 *
 * r107 (generic-list v10, mock CScan): a directory card is **scanned** ([BuilderListReader.scan]): its entries' parts
 * (number, name, videos, views, likes, picture; a Follow button is shown as never kept) with ticks, a preview row, the
 * site's sort options and menus; then **Live, as you scroll** (the source reads its pages with getDirectory; default
 * for more than 300 entries or 10 pages) or **Read pages now** (a page range and pause, with progress and Stop). The
 * filter is `type: "collection"` ([CollectionSpec]): the owner's r106 Series directory was saved as a Sort (its
 * "views" read like sort words) and his Makers as "FC FC2 33,977 videos + Follow Following"; restored filters with
 * such labels are cleaned ([BuilderExtrasLogic.repairLabels]). The built-in List row is gone (Browse's tabs pick the
 * list).
 *
 * KMK (r109): a directory's custom drop-downs ("Sort: Most videos", "Height: Any height"; [BuilderDirMenus]) become the
 * collection's sorts and menus: link entries at once, button entries (or lists added only when they open) by "Learn the
 * menus" on the card ([BuilderExtrasModel.learnDirMenus], pressed on the page like r102's learning), or by "Pick the
 * site's Sort menu" / "Pick another menu" ([BuilderExtrasModel.pickDirMenu]). The site's current choice names the
 * default (first, value ""), never "Site default" ([ExtrasFilter.siteDefault]). Cards without pictures offer "Tap the
 * picture on the page" ([BuilderExtrasModel.pickDirPicture], picker.js `pictureMode`). A read's progress box comes to
 * the top of the panel once when it starts ("read:<id>").
 *
 * KMK (r112, mock BEasy): "Do all N" on a heading card ([BuilderExtrasModel.doAllUnder]): its directories scanned, learned
 * and made one after another with one progress box and Stop; parts changed on a made card are re-applied
 * ([BuilderExtrasModel.setDirChoice]); learned rows say "24+ (page 1)" when the page had a next page ([DirLearnRow.more]).
 */

@Immutable
internal data class ExtrasTab(val name: String, val path: String)

@Immutable
internal data class ExtrasOption(
    val label: String,
    val value: String,
    // r107: a collection entry's parts as the site shows them ("" = none): its place, counts (unit words dropped) and
    // picture address
    val number: String = "",
    val videos: String = "",
    val views: String = "",
    val likes: String = "",
    val picture: String = "",
)

/** r107: one of a directory page's own menus (Height, Cup, Age): sent as [key]=value when the app's drop-down reads it */
@Immutable
internal data class CollectionMenu(val key: String, val name: String, val options: List<ExtrasOption>)

/**
 * r107: what a collection filter (`type: "collection"`, generic-list v10) carries besides its entries: the parts the
 * owner kept ([fields], "title" always), the site's [sorts] and [menus], [live] (the source reads the directory page by
 * page with getDirectory), the entry count the site shows ([total], 0 = unknown), and how to read the directory: page 1
 * ([first]) and page N ([page] with `{page}`, "" = one page only) relative to the site, its [pages], the entries'
 * selector ([item]), the link inside one ([link]) and each part's selector ([parts]); the sort goes as [sortParam]=value,
 * ascending as [orderParam]=[ascValue] when the site offers it ([reverse])
 */
@Immutable
internal data class CollectionSpec(
    val fields: List<String>,
    val sorts: List<ExtrasOption> = emptyList(),
    val reverse: Boolean = false,
    val menus: List<CollectionMenu> = emptyList(),
    val live: Boolean = false,
    val total: Int = 0,
    val first: String = "",
    val page: String = "",
    val pages: Int = 1,
    val item: String = "",
    val link: String = "",
    val parts: List<BuilderListReader.DirPart> = emptyList(),
    val sortParam: String = "",
    val orderParam: String = "",
    val ascValue: String = "",
    val descValue: String = "",
)

/**
 * r107: the owner's choices on a scanned directory card: the parts kept, live or read now, and the page range to read
 * (1-based, inclusive)
 */
@Immutable
internal data class DirChoice(val fields: Set<String>, val live: Boolean, val from: Int, val to: Int)

@Immutable
internal data class ExtrasFilter(
    val key: String,
    val name: String,
    val type: String, // select|sort|checkbox|text; r103: autocomplete (a text box with the options as suggestions)
    val options: List<ExtrasOption> = emptyList(),
    val onValue: String = "1",
    /** The options as the editor shows them, one `Label = value` per line ([options] is parsed from it) */
    val optionsText: String = BuilderExtrasLogic.formatOptions(options),
    /** r101: "param" = the value goes in the address as [param]=value; "path" = it replaces `{value}` in [path] */
    val mode: String = "param",
    val param: String = key,
    val path: String = "",
    /** r101: made without an address parameter (by hand, or from entries without links): the editor asks for it */
    val askParam: Boolean = false,
    /**
     * r104: a param filter made from links: the links' own address with `{value}` for the parameter
     * (`/en/trending?sort={value}&page={page}`); written as a path filter when it isn't the search page. "" = none
     */
    val linkPath: String = "",
    /**
     * r104 fix: restored from a build saved before r104 (no `linkPath` saved) and made from a menu's links (a param
     * filter with its options): its links' own page is unknown, so it may search the wrong page (the owner's
     * Trending → /en/search?sort=today). The editor says to make it again from the menu ([BuilderExtrasLogic.OLD_LINKS])
     */
    val noLinkAddress: Boolean = false,
    /**
     * r105: "page" = picks which list is browsed (its option opens [path] / [linkPath] with the value: Types →
     * /en/{value}, a directory → /en/makers/{value}); "extra" = adds [param]=value on top of whichever list is chosen
     * (Type, Year, Sort learned on a list page); "" = made before r105 (written as before: a path filter is a page
     * filter, a parameter filter one of the search page)
     */
    val role: String = "",
    /** r105: the heading it sits under in the Browse sheet ("Collections"); "" = none */
    val group: String = "",
    /** r105: an extra also sent while searching for words (seen working on the search page, or ticked by the owner) */
    val onSearch: Boolean = false,
    /** r105: the path of the page it was made on (/en/recent): an extra made on the search page works there */
    val seenOn: String = "",
    /** r105 fix: [role] was set by the owner on the card: making the filter again keeps it */
    val roleSet: Boolean = false,
    /** r107: a directory's collection (type "collection"), null = none */
    val collection: CollectionSpec? = null,
    /**
     * KMK (r109): the label of the site's own default option (the learned entry that left the address as it was, or the
     * one the menu's button showed): the empty first option is named after it, never "Site default". "" = not known
     */
    val siteDefault: String = "",
)

/**
 * r101: how the site numbers its pages, read from the owner's Next pick: `query` (`?name=2`), `pathWord`
 * (`/name/2`) or `pathEnd` (`/2` after the list's own path)
 */
@Immutable
internal data class PageRule(val kind: String, val name: String) {
    val shown: String
        get() = when (kind) {
            "query" -> "?$name={page}"
            "pathWord" -> "/$name/{page}"
            else -> "/{page}"
        }
}

/** r101: an entry the owner tapped opens a page whose whole list can be read ("Read the whole list") */
@Immutable
internal data class ListOffer(val url: String, val text: String)

@Immutable
internal data class ExtrasCustomSetting(
    val key: String,
    val name: String,
    val type: String, // switch|list|text
    val options: List<String> = emptyList(),
    /** A list's entries as the editor shows them, one per line ([options] is parsed from it) */
    val optionsText: String = options.joinToString("\n"),
)

@Immutable
internal data class FilterOffer(val param: String, val seen: List<String>, val path: String)

/** r103: one entry of a menu group (picker.js `menuPick`): its text, link, value and exact css path */
@Immutable
internal data class MenuItem(
    val text: String,
    val href: String?,
    val value: String?,
    val path: String?,
    /** KMK (r109): marked as the site's current choice (active / selected / aria-current), from picker.js */
    val current: Boolean = false,
)

/**
 * KMK (r109): "Learn the menus" on a scanned directory card: the custom drop-down being pressed ([label]), presses done
 * of [total], what was learned so far ([found]: "Sort: 7 codes") and why it ended ([note])
 */
@Immutable
internal data class DirLearn(
    val running: Boolean,
    val label: String,
    val done: Int,
    val total: Int,
    val found: List<String> = emptyList(),
    val note: String = "",
    /** KMK (r110): the entry being pressed now ("D cup"), "" = none */
    val entry: String = "",
    /** KMK (r110): one row per menu (mock CLearn): done / running / waiting / can't be learned */
    val menus: List<DirLearnMenu> = emptyList(),
    /** KMK (r110): about how long the presses left take (ms), -1 = not known yet */
    val leftMs: Long = -1L,
    /** KMK (r110): the directory's name ("Actresses"): learned menus are "only for" it */
    val collection: String = "",
)

/**
 * KMK (r110): one menu of "Learn the menus" as its row shows it: [state] waiting|running|done|failed ("can't be
 * learned": no entry changed any value)|stopped (KMK (r110) fix: Stop cut it short or it was never reached; not saved), the address parameter, how many options, the default's label, entries whose
 * page showed nothing ([empty], kept) or whose press did nothing ([failed]), each entry's row, why it failed ([note])
 */
@Immutable
internal data class DirLearnMenu(
    val label: String,
    val sort: Boolean,
    val state: String,
    val param: String = "",
    val pathMode: Boolean = false,
    val options: Int = 0,
    val defaultLabel: String = "",
    val empty: Int = 0,
    val failed: Int = 0,
    val entries: Int = 0,
    val rows: List<DirLearnRow> = emptyList(),
    val note: String = "",
)

/**
 * KMK (r110): one pressed entry: its text, what it changed ("height=150-154", "" = nothing), [status]
 * default|learned|empty (the address changed, the page showed no cards: kept)|failed (the press did nothing / the
 * entry wasn't found)|pressing, and the cards its page showed (-1 = not counted)
 */
@Immutable
internal data class DirLearnRow(
    val text: String,
    val code: String,
    val status: String,
    val cards: Int = -1,
    /** KMK (r112): the page it opened has a next page too: [cards] is page 1's count ("24+ (page 1)") */
    val more: Boolean = false,
)

/**
 * r103: a menu group of the site as its card shows it: [name] (the heading, cleaned: "Year: All ▾" → "Year"; "" when
 * the list has no heading: the filter is then named by its entries, e.g. "Sort"), the
 * headings above it ([headingPath]), the css path of the element that opens it ([opener]), its own link ([href]: an
 * index page whose full list can be fetched), its [items], the page it was picked on, the `<select name>` when it is
 * one ([param]); [made] = the key of the filter made from it
 */
@Immutable
internal data class MenuGroup(
    val id: Int,
    val name: String,
    val headingPath: List<String>,
    val opener: String?,
    val href: String?,
    val index: Boolean,
    val items: List<MenuItem>,
    val pageUrl: String,
    val param: String? = null,
    val made: String? = null,
    /** r106: the card of the heading this sub-menu sits under (shown as a row of that card), null = a card of its own */
    val parent: Int? = null,
    /** r106: a directory's first page, read when the card appears (null = not read: no own link) */
    val probe: DirProbe? = null,
    /** r106: "Read all pages" on this card, running or done */
    val read: GroupRead? = null,
    /** r107: the owner's ticks, mode and page range on a scanned directory card (null = the defaults) */
    val choice: DirChoice? = null,
    /** KMK (r109): the value its button shows ("Sort: Most videos" → "Most videos"): the site's default, "" = none */
    val current: String = "",
    /** KMK (r109): a scanned directory's "Learn the menus" running or done, null = not started */
    val dirLearn: DirLearn? = null,
    /** KMK (r112): a heading card's "Do all N" running or done, null = not started */
    val doAll: DoAllRun? = null,
) {
    /** Every entry is a button / text without a link: "Learn addresses" can find where each goes */
    val noLinks: Boolean get() = items.isNotEmpty() && items.none { !it.href.isNullOrBlank() }

    /**
     * r106: what the card offers: "select" (a `<select>` with a name: its values are the codes, Make filter), "buttons"
     * (entries without links: Learn the codes), "links" (Make filter / Add as tabs), "directory" (its own link opens a
     * list of names: Read all pages) or "heading" (only sub-menus: one row each)
     */
    val kind: String
        get() = when {
            items.size >= 2 && param != null -> "select"
            items.size >= 2 && noLinks -> "buttons"
            items.size >= 2 -> "links"
            href != null -> "directory"
            else -> "heading"
        }
}

/**
 * KMK (r112, mock BEasy): "Do all N" on a heading card whose sub-menus are directories (Collections ▸ Genres, Actresses,
 * Makers, Series): each one in turn is scanned (its first page), its parts ticked by the usual defaults, its sort and
 * menus learned on the page ([BuilderExtrasModel.runDirLearn]) and its collection made (live by the usual rule). [kids]
 * in run order, the one being done ([current]) and what it is doing ([phase]: scanning|learning|reading|making), one
 * row per directory ([rows]), when it started and why it ended ([note])
 */
@Immutable
internal data class DoAllRun(
    val running: Boolean,
    val kids: List<Int>,
    val current: Int? = null,
    val phase: String = "",
    val rows: Map<Int, DoAllRow> = emptyMap(),
    val started: Long = 0L,
    val note: String = "",
)

/**
 * KMK (r112): one directory's row of "Do all N": [state] waiting|running|ready|needs (the owner has to step in: [note]
 * says why)|stopped (Stop came first: nothing made for it)
 */
@Immutable
internal data class DoAllRow(val state: String, val note: String = "")

/**
 * r106: a directory card's first page (BuilderListReader.probe): [state] reading|done|failed, how many names it
 * holds, the highest page number its pager shows ([pages], 1 = none) and whether it has a next page
 */
@Immutable
internal data class DirProbe(
    val state: String,
    val entries: Int = 0,
    val pages: Int = 1,
    val hasNext: Boolean = false,
    val note: String = "",
    /** r107: the directory read part by part (the scan card), null = not scanned */
    val scan: BuilderListReader.DirScan? = null,
)

/** r106: "Read all pages" of a card: running, the page being read, the names so far, the page limit, why it ended */
@Immutable
internal data class GroupRead(
    val running: Boolean,
    val page: Int,
    val entries: Int,
    val maxPages: Int,
    val reason: String? = null,
    /** r107: the first page of the range read ([page] counts from it: page 1 of [maxPages] is this page) */
    val first: Int = 1,
    /** r107: when the next page is read (epoch ms, 0 = reading now), and the time a page has taken on average */
    val waitUntil: Long = 0L,
    val pageMs: Long = 0L,
    /** KMK (r119): the last page of the set in flight ([page]..[pageTo], counted like [page]; 0 = one page) */
    val pageTo: Int = 0,
    /** KMK (r119): when the names shown were read, when they came from the directory cache (0 = read now) */
    val cachedAt: Long = 0L,
) {
    /** KMK (r119): "pages 4–6 of 12" / "page 4 of 12" */
    fun pagesText(): String = if (pageTo > page) "pages $page–$pageTo of $maxPages" else "page $page of $maxPages"
}

/**
 * r106: a filter that has the name (or the address parameter) of filters already there ([conflicts]): the card it came
 * from ([menuId], null = no card) asks Replace "<name>" / Keep both before it is added
 */
@Immutable
internal data class PendingFilter(
    val filter: ExtrasFilter,
    val conflicts: List<ExtrasFilter>,
    val menuId: Int?,
    val from: String,
    /** "learned" = made from a learning run (its card closes when the filter is added) */
    val kind: String = "",
    /**
     * r106 fix: the filters the new one replaces whatever the answer (a learned list's texts-only filter), found when it
     * asked: Keep both still drops them, as an answer-free make would
     */
    val replaced: List<ExtrasFilter> = emptyList(),
)

/** r102: one entry of a picked list, for "Learn this list": its text, its css path when the page sent one, its link */
@Immutable
internal data class LearnEntry(val text: String, val path: String?, val href: String?)

/**
 * r102: the last list picked for a filter, kept so "Learn this list" can press its entries: the page it was picked on,
 * the css path of its menu's button ([opener], from picker.js), the entries' look-alike selector, the filter's name
 */
@Immutable
internal data class LearnGroup(
    val pageUrl: String,
    val opener: String?,
    val likeSelector: String,
    val name: String,
    val entries: List<LearnEntry>,
    /** Some entries are links: the card offers "Check where each entry goes" instead of "Learn this list" */
    val hasLinks: Boolean,
    /** r106: the menu card this list was picked on (its learning shows inside that card), null = a group pick */
    val menuId: Int? = null,
    /** KMK (r109): the value the menu's button shows ("Most videos"): names the default when no press left the address */
    val current: String = "",
    /** KMK (r109): the menu button's label ("Sort"): learn.js finds the button by "Sort:" when [opener] finds nothing */
    val openerLabel: String = "",
)

/**
 * r102: an entry of a learning run: [status] waiting|pressing|learned|nochange|missing|filled|missed; [url] where it
 * went. r102 fix: "missing" = learn.js didn't find the entry on the page (never made the filter's "Any")
 */
@Immutable
internal data class LearnRow(val text: String, val url: String?, val status: String)

/** r102: a "Learn this list" run as the card shows it */
@Immutable
internal data class LearnRun(
    val name: String,
    val rows: List<LearnRow>,
    val running: Boolean,
    /** The pattern found ("year=<text>", "/en/<text>"), or null */
    val pattern: String?,
    val note: String?,
    val pressed: Int,
    val pauseMinMs: Long,
    val pauseMaxMs: Long,
)

@Immutable
internal data class BuilderExtras(
    val searchWord: String = "test",
    val searchPath: String? = null,
    val popularPath: String? = null,
    val popularName: String = "",
    val latestPath: String? = null,
    val latestName: String = "",
    val tabs: List<ExtrasTab> = emptyList(),
    val filters: List<ExtrasFilter> = emptyList(),
    val qualitySetting: Boolean = false,
    val serverSetting: Boolean = false,
    val hideWordsSetting: Boolean = false,
    val custom: List<ExtrasCustomSetting> = emptyList(),
    val offers: List<FilterOffer> = emptyList(),
    val optionsFor: Map<String, List<ExtrasOption>> = emptyMap(),
    val lastUrl: String? = null,
    val message: String? = null,
    /**
     * Bumped whenever the model itself changes what a text field shows (a recorded search, "Use this page", options
     * from the page, a row added or removed), never by typing: the pane's fields re-read their text when it changes
     */
    val rev: Int = 0,
    /** The address the search was recorded from (the "Recorded ✓" line) */
    val searchFrom: String? = null,
    /** r101: [searchPath] came from the site's search box (a real search recorded later replaces it) */
    val searchFromForm: Boolean = false,
    /** r101: the page numbering read from the Next pick (null: the `page={page}` guess) */
    val pageRule: PageRule? = null,
    /** r101: "Pick a list on the site" is waiting for a tap (BuilderScreenModel sends it to the page) */
    val groupMode: Boolean = false,
    /** r101: the last tapped entry that opens a list page */
    val listOffer: ListOffer? = null,
    /** r102: the last list picked for a filter ("Learn this list" presses its entries), null when there is none */
    val learnGroup: LearnGroup? = null,
    /** r102: the current or last "Learn this list" run */
    val learn: LearnRun? = null,
    /** r102: the owner tapped a menu's button ("Year: All ▾") instead of one of its entries: the card says so */
    val menuHint: Boolean = false,
    /** r103: the groups of the last menu picked on the site ("Pick a menu on the site"), one card each */
    val menuGroups: List<MenuGroup> = emptyList(),
    /**
     * r103 fix: the menu hint card's text when it isn't the r102 "menu's button" one (picker.js `hint`: a dropdown
     * whose menu the site adds only when it opens); null = the r102 text
     */
    val menuHintText: String? = null,
    /**
     * r106 fix: the filters waiting for Replace / Keep both (their name is taken), one per card: by the card's id
     * ([BuilderExtrasLogic.NO_CARD] = made without a card). Only the answer (or Not now / the card closed) clears one
     */
    val pendings: Map<Int, PendingFilter> = emptyMap(),
    /** r106: what the pane scrolls into view ("menu:<id>", "pending"), with [scrollTick] bumped on every request */
    val scrollTarget: String? = null,
    val scrollTick: Int = 0,
    /**
     * r106 fix: the request at [scrollTick] wants the panel to show Lists & filters (a new card, learning starting);
     * false = scroll only when the pane is already shown (a sub-menu's read, a question on a card)
     */
    val scrollReveal: Boolean = false,
    /** r106 fix: the last request the pane opened Filters for / brought into view (coming back doesn't redo it) */
    val scrollOpened: Int = 0,
    val scrollDone: Int = 0,
    /** r106 fix: the pane's open section, kept here so a tab switch doesn't lose it */
    val openSection: String = "",
    /**
     * KMK (r109): "Pick the site's Sort menu" / "Pick another menu" of a scanned directory card: that card's id (the
     * next menu pick attaches its codes to the card's collection) and whether it is the sort; null = none waiting
     */
    val dirPick: Int? = null,
    val dirPickSort: Boolean = false,
    /** KMK (r109): "Tap the picture on the page" waiting on that card's id, null = none */
    val picturePick: Int? = null,
) {
    /** The filter waiting without a card, else the first waiting one (r106 single-slot readers) */
    val pending: PendingFilter? get() = pendings[BuilderExtrasLogic.NO_CARD] ?: pendings.values.firstOrNull()
}

/** Pure functions of "Lists and options", kept apart from the model so they can be checked on their own */
internal object BuilderExtrasLogic {

    /** r106 fix: the [BuilderExtras.pendings] key of a filter made without a menu card */
    const val NO_CARD = 0

    const val NO_PAGE_MESSAGE = "This page has no page number in its address: the Lab will try ?page=2 for more"

    // No braces in these patterns (ICU)
    private val PAGE_QUERY = Regex("""([?&](?:page|p)=)(\d+)""", RegexOption.IGNORE_CASE)
    private val PAGE_PATH = Regex("""(/page/)(\d+)""", RegexOption.IGNORE_CASE)
    private const val WORD_BEFORE = "/=?&+"
    private const val WORD_AFTER = "/&?#+."
    private val PAGE_PARAMS = setOf("page", "p")

    data class PathResult(val path: String, val addedPage: Boolean)

    /** [url] without [site]'s origin (other origins keep the whole address) and without the #fragment */
    fun relative(url: String, site: String): String {
        val clean = url.trim().substringBefore('#')
        val origin = site.trim().trimEnd('/')
        if (origin.isEmpty() || !clean.startsWith(origin, ignoreCase = true)) return clean
        val rest = clean.substring(origin.length)
        return when {
            rest.isEmpty() -> "/"
            rest.startsWith("/") -> rest
            rest.startsWith("?") -> "/$rest"
            else -> clean // "https://example.com.evil.net/…": another host that starts the same
        }
    }

    /**
     * The path of a page as a list path: [relative] to [site], the page number (`page=N`, `p=N`, `/page/N`) turned
     * into `{page}`; with none, `page={page}` is appended (`?` or `&`) and [PathResult.addedPage] says so.
     */
    fun pathOf(url: String, site: String, rule: PageRule? = null): PathResult = withPage(relative(url, site), rule)

    /** r101: with a [rule] (from the Next pick) the page number follows it; else the r100 guess */
    fun withPage(path: String, rule: PageRule? = null): PathResult {
        if ("{page}" in path) return PathResult(path, false)
        if (rule != null) return PathResult(applyRule(path, rule), false)
        PAGE_QUERY.find(path)?.let { m ->
            val digits = m.groups[2] ?: return@let
            return PathResult(path.replaceRange(digits.range, "{page}"), false)
        }
        PAGE_PATH.find(path)?.let { m ->
            val digits = m.groups[2] ?: return@let
            return PathResult(path.replaceRange(digits.range, "{page}"), false)
        }
        val joiner = when {
            '?' !in path -> "?"
            path.endsWith("?") || path.endsWith("&") -> ""
            else -> "&"
        }
        return PathResult("$path${joiner}page={page}", true)
    }

    /**
     * The search path from a loaded address that holds [word] (raw, `+`-joined or %-encoded, any case) as a whole
     * value or path segment: the word becomes `{q}` and the page rule of [pathOf] applies. Null when it isn't there.
     * A match in the query wins over one in the path; the host is never searched.
     */
    fun searchPathOf(url: String, word: String, site: String, rule: PageRule? = null): PathResult? {
        val w = word.trim()
        if (w.isEmpty()) return null
        val rel = relative(url, site)
        val pathStart = if (rel.startsWith("/")) {
            0
        } else {
            val afterScheme = rel.indexOf("://").let { if (it < 0) 0 else it + 3 }
            rel.indexOf('/', afterScheme).let { if (it < 0) rel.length else it }
        }
        val queryStart = rel.indexOf('?', pathStart).let { if (it < 0) rel.length else it }
        val encoded = runCatching { URLEncoder.encode(w, "UTF-8") }.getOrDefault(w)
        val candidates = listOf(w, w.replace(' ', '+'), w.replace(" ", "%20"), encoded, encoded.replace("+", "%20"))
            .distinct()
            .sortedByDescending { it.length }
        var best: IntRange? = null
        for (candidate in candidates) {
            var from = pathStart
            while (true) {
                val at = rel.indexOf(candidate, from, ignoreCase = true)
                if (at < 0) break
                val end = at + candidate.length
                val before = rel.getOrNull(at - 1)
                val after = rel.getOrNull(end)
                if (before != null && before in WORD_BEFORE && (after == null || after in WORD_AFTER)) {
                    val range = at until end
                    val current = best
                    val better = current == null ||
                        (range.first >= queryStart && current.first < queryStart) ||
                        ((range.first >= queryStart) == (current.first >= queryStart) && range.first < current.first)
                    if (better) best = range
                }
                from = at + 1
            }
        }
        val range = best ?: return null
        return withPage(rel.replaceRange(range, "{q}"), rule)
    }

    /**
     * r101: the search path from the site's GET search form: the action's path (a GET form drops the action's own
     * query), `field={q}`, the hidden fields, and the page rule ([withPage])
     */
    fun searchPathOfForm(action: HttpUrl, field: String, hidden: List<Pair<String, String>>, site: String, rule: PageRule?): String {
        val base = relative(action.newBuilder().query(null).fragment(null).build().toString(), site)
        fun enc(v: String) = runCatching { URLEncoder.encode(v, "UTF-8") }.getOrDefault(v)
        val pairs = listOf(enc(field) + "={q}") + hidden.map { (k, v) -> enc(k) + "=" + enc(v) }
        return withPage(base + "?" + pairs.joinToString("&"), rule).path
    }

    // ---------- r101: the page rule from the Next pick ----------

    private val PAGE_WORDS = setOf("page", "p", "pg", "pages", "seite", "pagina")

    /** The page number put where [rule] says, replacing a number already there */
    fun applyRule(path: String, rule: PageRule): String {
        val q = path.indexOf('?')
        val pathPart = if (q < 0) path else path.substring(0, q)
        val query = if (q < 0) "" else path.substring(q)
        val trailing = pathPart.length > 1 && pathPart.endsWith("/") && !pathPart.endsWith("://")
        val base = if (trailing) pathPart.trimEnd('/') else pathPart
        val slash = if (trailing) "/" else ""
        return when (rule.kind) {
            "query" -> {
                val re = Regex("([?&]" + Regex.escape(rule.name) + "=)(\\d+)")
                val digits = re.find(query)?.groups?.get(2)
                if (digits != null) {
                    pathPart + query.replaceRange(digits.range, "{page}")
                } else {
                    val joiner = when {
                        query.isEmpty() -> "?"
                        query.endsWith("?") || query.endsWith("&") -> ""
                        else -> "&"
                    }
                    "$pathPart$query$joiner${rule.name}={page}"
                }
            }
            "pathWord" -> {
                val re = Regex("/" + Regex.escape(rule.name) + "/(\\d+)")
                val digits = re.find(pathPart)?.groups?.get(1)
                if (digits != null) {
                    pathPart.replaceRange(digits.range, "{page}") + query
                } else {
                    "$base/${rule.name}/{page}$slash$query"
                }
            }
            else -> {
                val last = base.substringAfterLast('/')
                if (last.isNotEmpty() && last.all(Char::isDigit) && base.count { it == '/' } > 1) {
                    base.dropLast(last.length) + "{page}" + slash + query
                } else {
                    "$base/{page}$slash$query"
                }
            }
        }
    }

    /**
     * The page rule from the Next link [href] (absolute or relative) on [pageUrl]: a query parameter that is 2 while
     * the page has none (or one more than the page's), `/page/2` (a page word and its number added, or one more), or
     * a number added at the end of the path. Null when the link has no page number the Lab can read.
     */
    fun pageRuleOf(href: String, pageUrl: String): PageRule? {
        val page = pageUrl.trim().toHttpUrlOrNull() ?: return null
        val next = page.resolve(href.trim()) ?: return null
        val queryNames = next.queryParameterNames.filter { name ->
            val nv = next.queryParameter(name)?.toIntOrNull() ?: return@filter false
            val pv = page.queryParameter(name)?.toIntOrNull()
            if (pv == null) nv == 2 && page.queryParameter(name) == null else nv == pv + 1
        }
        val queryName = queryNames.firstOrNull { it.lowercase() in PAGE_WORDS } ?: queryNames.firstOrNull()
        if (queryName != null) return PageRule("query", queryName)
        val hs = next.pathSegments.filter { it.isNotEmpty() }
        val ps = page.pathSegments.filter { it.isNotEmpty() }
        for (i in 1 until hs.size) {
            val nv = hs[i].toIntOrNull() ?: continue
            val word = hs[i - 1]
            if (word.isEmpty() || word.any(Char::isDigit)) continue
            val added = nv == 2 && ps == hs.filterIndexed { at, _ -> at != i - 1 && at != i }
            val moved = word.lowercase() in PAGE_WORDS && ps.size == hs.size &&
                ps.indices.all { at -> at == i || ps[at] == hs[at] } && ps[i].toIntOrNull() == nv - 1
            if (added || moved) return PageRule("pathWord", word)
        }
        val nv = hs.lastOrNull()?.toIntOrNull()
        if (nv != null) {
            val added = nv == 2 && ps == hs.dropLast(1)
            val moved = ps.size == hs.size && ps.dropLast(1) == hs.dropLast(1) && ps.lastOrNull()?.toIntOrNull() == nv - 1
            if (added || moved) return PageRule("pathEnd", "")
        }
        return null
    }

    /**
     * A path written before the Next pick, with the guessed `page={page}` replaced by [rule]. Paths without the
     * guess (a page number seen in the address, or typed) stay as they are.
     */
    fun reapplyRule(path: String?, rule: PageRule): String? {
        if (path == null) return null
        if (rule.kind == "query" && rule.name == "page") return path
        if ("page={page}" !in path) return path
        var p = path.replace("&page={page}", "")
        p = p.replace("?page={page}&", "?")
        if (p.endsWith("?page={page}")) p = p.removeSuffix("?page={page}")
        return if ("{page}" in p) p else applyRule(p, rule)
    }

    // ---------- r101: filters from a group of the site's entries ----------

    /** One entry of a tapped group (or of a list read by [BuilderListReader]) */
    data class GroupEntry(val text: String, val href: String?, val value: String?)

    data class GroupDerived(
        val filter: ExtrasFilter?,
        val note: String,
        /** Offer "Read the whole list" of this page (the tapped entry's link), or null */
        val listUrl: String?,
        val listText: String,
        /** For the log: entries used, and how */
        val used: Int,
        val how: String,
    )

    private class Link(val index: Int, val text: String, val url: HttpUrl, val segs: List<String>, val trailing: Boolean) {
        val shape: String = url.host + "|" + segs.size + "|" + url.queryParameterNames.sorted().joinToString(",")
    }

    // No braces in these patterns (ICU)
    private val COUNT_SUFFIX = Regex("""\s*\(\s*[\d.,]+\s*[kKmM]?\s*\)\s*$""")

    /** r101: "Action 1234 videos", "Action (1,234 items)": a number followed by what it counts */
    private val COUNT_WORDS = Regex(
        """\s+[(\[]?\s*\d[\d.,]*\s*[kKmM]?\+?\s*(?:videos?|items?|titles?|posts?|works?|galleries|gallery|entries|results|books|mangas?|comics?|movies?|episodes?|chapters?)\s*[)\]]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** r101: "Action 1,234" — a thousands-grouped number only (never "Top 100", "4K" or a year) */
    private val COUNT_GROUPED = Regex("""(?<=[^\s\d,.~–-])\s+\d+(?:,\d\d\d)+\s*$""")
    private val SPACES = Regex("""\s+""")
    private val SORT_WORDS = Regex(
        """\b(new|newest|latest|recent|recently|hot|popular|popularity|trending|top|rated|rating|views|viewed|most|oldest|release|released|date|updated|update|alphabetical|random|longest|shortest|relevance|a-z|z-a)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SORT_PARAMS = setOf("sort", "order", "orderby", "order_by", "sort_by", "sortby", "sortorder", "sort_order", "s_order")
    private const val MENU_SIZE = 12

    /**
     * An entry's text as an option label: its first line, spaces collapsed, a trailing count dropped ("(1,234)",
     * "1,234", "1234 videos"); a label that is only a number (or a year range) is kept as it is
     */
    fun cleanLabel(text: String): String {
        val line = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().replace(SPACES, " ")
        // r107: a Follow button's words and "N videos · M views" go too (a whole card read as one link's text)
        val cleaned = BuilderListReader.entryLabel(line).replace(COUNT_WORDS, "").replace(COUNT_SUFFIX, "").replace(COUNT_GROUPED, "").trim()
        return cleaned.ifEmpty { line.trim() }
    }

    /** Most of [labels] read like sort orders ("Release date", "Hot", "Recently added"), or the parameter says so */
    fun sortLike(labels: List<String>, param: String?): Boolean {
        if (param != null && param.lowercase() in SORT_PARAMS) return true
        if (labels.size < 2) return labels.size == 1 && SORT_WORDS.containsMatchIn(labels[0])
        val hits = labels.count { SORT_WORDS.containsMatchIn(it) }
        return hits >= 2 && hits * 5 >= labels.size * 3
    }

    /** A path segment that names a list ("genres", "category"), not a language code or a number */
    private fun meaningful(segment: String?): Boolean =
        segment != null && segment.length >= 3 && segment.any(Char::isLetter) && '{' !in segment

    private fun withoutFragment(url: HttpUrl): String = url.newBuilder().fragment(null).build().toString()

    /**
     * What a group of entries becomes. Links on the page's host are grouped by shape (host, number of path segments,
     * query names); the tapped entry's group is the consecutive run of like-shaped links around it (a menu's "New,
     * Hot, Recent" apart from its "Genres, Actresses, Makers"), else all like-shaped links, else the largest shape.
     * Same path, one query parameter differing → a param filter; one path segment differing → a path filter
     * (`{value}` there, the page rule added). Entries with values and no links → a param filter whose parameter the
     * owner types. A tapped link to another page (not a sort option) is offered for "Read the whole list".
     */
    fun deriveGroup(
        entries: List<GroupEntry>,
        tappedText: String,
        tappedHref: String?,
        pageUrl: String,
        rule: PageRule?,
        heading: String?,
        offers: List<FilterOffer> = emptyList(),
        /** r101: the address parameter the entries set when it is known (a `<select name=…>`), before [offers] */
        knownParam: String? = null,
        /** r103: how many options the filter keeps (a text box with suggestions keeps more) */
        maxOptions: Int = MAX_GROUP_OPTIONS,
    ): GroupDerived {
        val page = pageUrl.trim().toHttpUrlOrNull()
        val here = page?.let { withoutFragment(it) }
        val links = ArrayList<Link>()
        entries.forEachIndexed { index, e ->
            val raw = e.href?.trim().orEmpty()
            if (raw.isEmpty() || raw.startsWith("javascript:", ignoreCase = true)) return@forEachIndexed
            val url = (page?.resolve(raw) ?: raw.toHttpUrlOrNull()) ?: return@forEachIndexed
            if (page != null && url.host != page.host) return@forEachIndexed
            // "#" links resolve to this page with a fragment: a menu toggle, not an address
            if (url.fragment != null && withoutFragment(url) == here) return@forEachIndexed
            val segs = url.encodedPathSegments.filter { it.isNotEmpty() }
            val trailing = url.encodedPath.length > 1 && url.encodedPath.endsWith("/")
            links += Link(index, cleanLabel(e.text), url, segs, trailing)
        }
        val tappedClean = cleanLabel(tappedText)
        val tapped = tappedHref?.trim()?.takeIf { it.isNotEmpty() }?.let { h ->
            val abs = page?.resolve(h)?.toString() ?: h
            links.firstOrNull { it.url.toString() == abs }
        } ?: links.firstOrNull { tappedClean.isNotEmpty() && it.text == tappedClean }

        var group: List<Link> = emptyList()
        if (tapped != null) {
            val byIndex = links.associateBy { it.index }
            val around = ArrayList<Link>()
            var j = tapped.index - 1
            while (j >= 0) {
                val l = byIndex[j]
                if (l != null) {
                    if (l.shape != tapped.shape) break
                    around.add(0, l)
                }
                j--
            }
            around += tapped
            j = tapped.index + 1
            while (j < entries.size) {
                val l = byIndex[j]
                if (l != null) {
                    if (l.shape != tapped.shape) break
                    around += l
                }
                j++
            }
            group = around
            if (group.distinctBy { it.url.toString() }.size < 2) group = links.filter { it.shape == tapped.shape }
        } else if (links.isNotEmpty()) {
            group = links.groupBy { it.shape }.values.maxByOrNull { it.size }.orEmpty()
        }
        group = group.distinctBy { it.url.toString() }
        // r101: a sort option tapped in a menu that mixes sort orders with other pages ("New, Hot, Recent, Genres,
        // Actresses"): only its sort-like entries make the filter
        val tappedSort = tapped != null && sortLike(listOf(tapped.text), null)
        if (tappedSort) {
            val sorts = group.filter { sortLike(listOf(it.text), null) }
            if (sorts.size >= 2 && sorts.size < group.size) group = sorts
        }

        var filter: ExtrasFilter? = null
        var how = ""
        var note = ""
        var total = 0
        if (group.size >= 2) {
            val first = group.first()
            if (group.all { it.url.encodedPath == first.url.encodedPath }) {
                val names = first.url.queryParameterNames
                val varying = names.filter { name -> group.map { it.url.queryParameter(name).orEmpty() }.distinct().size > 1 }
                    .filter { it.lowercase() !in PAGE_WORDS || names.size == 1 }
                if (varying.size == 1) {
                    val param = varying.first()
                    val options = group.map { l ->
                        val v = l.url.queryParameter(param).orEmpty()
                        ExtrasOption(l.text.ifEmpty { v.ifEmpty { "Any" } }, v)
                    }
                    // r104: the links' own page with {value} for the parameter (Trending → /en/trending?sort={value})
                    val marked = first.url.newBuilder().setQueryParameter(param, LINK_MARK).build()
                    val linkRaw = marked.encodedPath + (marked.encodedQuery?.let { "?$it" } ?: "")
                    val linkPath = withPage(linkRaw.replace(LINK_MARK, "{value}"), rule).path
                    // r105: the entries change a parameter of the page they are on (Type, Year, Sort on /en/recent) →
                    // an extra; links to another page (Trending → /en/all?sort=today) → a page filter
                    val samePage = page != null && first.url.encodedPath.trimEnd('/') == page.encodedPath.trimEnd('/')
                    filter = makeFilter(options, "param", param, "", param, heading, maxOptions)
                        .copy(linkPath = linkPath, role = if (samePage) "extra" else "page", seenOn = page?.encodedPath.orEmpty())
                    total = optionCount(options, "param")
                    how = "param $param"
                } else {
                    note = if (varying.isEmpty()) {
                        "The tapped entries all open the same address: no filter was made."
                    } else {
                        "The tapped entries change ${varying.joinToString(", ")} at once: no filter was made."
                    }
                }
            } else {
                fun differing(g: List<Link>): List<Int> =
                    g.first().segs.indices.filter { at -> g.map { it.segs[at] }.distinct().size > 1 }
                var differ = differing(group)
                if (differ.size > 1) {
                    // A folded submenu next to another ("/en/trending/today" by "/en/types/censored"): keep the
                    // entries that differ from the tapped one in a single segment
                    val anchor = tapped ?: first
                    val narrowed = differ.map { at ->
                        group.filter { l -> l.segs.indices.all { k -> k == at || l.segs[k] == anchor.segs[k] } }
                    }.maxByOrNull { it.size }
                    if (narrowed != null && narrowed.size >= 2) {
                        group = narrowed
                        differ = differing(group)
                    }
                }
                val lead = group.first()
                val sameQuery = group.all { it.url.encodedQuery == lead.url.encodedQuery }
                if (differ.size == 1 && sameQuery) {
                    val at = differ.first()
                    val segs = lead.segs.toMutableList().also { it[at] = "{value}" }
                    val raw = "/" + segs.joinToString("/") + (if (lead.trailing) "/" else "") +
                        (lead.url.encodedQuery?.let { "?$it" } ?: "")
                    val result = withPage(raw, rule)
                    val options = group.map { l -> ExtrasOption(l.text.ifEmpty { l.segs[at] }, l.segs[at]) }
                    val before = lead.segs.getOrNull(at - 1)
                    val candidate = makeFilter(options, "path", "", result.path, before, heading, maxOptions)
                    // r101: a menu entry that opens an index page ("Genres" next to "Actresses", "Makers") whose
                    // neighbours share no list name in the address and aren't sort orders: those are other pages,
                    // not a list's options — no filter, only "Read the whole list" of the tapped page
                    val tappedLabel = tapped?.text.orEmpty()
                    val opensPage = tapped != null && !tappedSort && withoutFragment(tapped.url) != here
                    if (opensPage && candidate.type != "sort" && candidate.key == "list") {
                        how = "none (entries open other pages: ${result.path})"
                        note = "The entries next to \"$tappedLabel\" open other pages, not one list's " +
                            "options: no filter was made (tap a sort option for a Sort filter)."
                    } else {
                        filter = candidate.copy(role = "page", seenOn = page?.encodedPath.orEmpty())
                        total = optionCount(options, "path")
                        how = "path ${result.path}"
                        if (result.addedPage) note = " Pages: guessed page={page} (tap the site's Next link as Next to read it)."
                    }
                } else {
                    note = "The tapped entries don't differ in one part of the address " +
                        "(${lead.url.encodedPath}, ${group[1].url.encodedPath}…): no filter was made."
                }
            }
        }
        if (filter == null && group.size < 2) {
            val valued = entries.filter { !it.value.isNullOrBlank() }
            if (valued.size >= 2) {
                val options = valued.map { e ->
                    val v = e.value.orEmpty().trim()
                    ExtrasOption(cleanLabel(e.text).ifEmpty { v }, v)
                }
                val values = options.map { it.value }.toSet()
                val param = knownParam?.trim()?.takeIf { it.isNotEmpty() }
                    ?: offers.firstOrNull { o -> o.seen.isNotEmpty() && o.seen.all { it in values } }?.param.orEmpty()
                filter = makeFilter(options, "param", param, "", param.ifEmpty { null }, heading, maxOptions)
                    .let { if (param.isEmpty()) it.copy(key = "", askParam = true) else it }
                    // r105: a <select>'s values on this page: an extra of this page
                    .copy(role = "extra", seenOn = page?.encodedPath.orEmpty())
                total = optionCount(options, "param")
                how = if (param.isEmpty()) "values, parameter not known" else "values of $param"
                note = if (param.isEmpty()) {
                    " These entries have values but no links: type the address parameter they set " +
                        "(choose one on the site with nothing armed and look at the address: the part before =)."
                } else {
                    ""
                }
            } else if (links.size < 2) {
                // r102: entries without links and values (a dropdown of buttons) keep their texts as the options;
                // "All" / "Any" is the empty first option. "Learn this list" finds where each one goes.
                val options = entries.map { cleanLabel(it.text) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .map { label -> ExtrasOption(label, if (label.lowercase() in ANY_WORDS) "" else label) }
                if (options.size >= 2) {
                    val values = options.map { it.value }.filter { it.isNotEmpty() }.toSet()
                    val param = knownParam?.trim()?.takeIf { it.isNotEmpty() }
                        ?: offers.firstOrNull { o -> o.seen.isNotEmpty() && o.seen.all { it in values } }?.param.orEmpty()
                    filter = makeFilter(options, "param", param, "", param.ifEmpty { null }, heading, maxOptions)
                        .let { if (param.isEmpty()) it.copy(key = "", askParam = true) else it }
                    total = optionCount(options, "param")
                    how = if (param.isEmpty()) "texts (no links), parameter not known" else "texts of $param"
                    note = if (param.isEmpty()) {
                        " These entries are buttons without links: their texts are the options. Learn this list " +
                            "to find the address each one opens (or type the parameter they set)."
                    } else {
                        ""
                    }
                }
            }
        }

        val made = filter
        val tappedLink = tapped ?: links.singleOrNull()
        val listUrl = tappedLink?.takeIf { l ->
            withoutFragment(l.url) != here && !sortLike(listOf(l.text), null) &&
                (made == null || (made.mode == "path" && group.size <= MENU_SIZE))
        }?.let { withoutFragment(it.url) }
        val listText = tappedLink?.text?.ifEmpty { null } ?: tappedClean.ifEmpty { "this entry" }

        val parts = ArrayList<String>()
        if (made != null) {
            val where = if (made.mode == "path") {
                "picks change the address to ${made.path}"
            } else {
                "picks add ${made.param.ifEmpty { "…" }}=… to it"
            }
            parts += "Made the filter \"${made.name}\" from ${made.options.count { it.value.isNotEmpty() }} entries: $where."
            if (total > maxOptions) parts += "Kept the first $maxOptions of $total entries."
        } else if (group.size < 2 && note.isEmpty()) {
            parts += "Only one entry of that kind was found: no filter was made."
        }
        if (note.isNotBlank()) parts += note.trim()
        if (listUrl != null) parts += "\"$listText\" opens a list page: Read the whole list to make a filter of all its entries."
        return GroupDerived(
            filter = made,
            note = parts.joinToString(" "),
            listUrl = listUrl,
            listText = listText,
            used = made?.options?.size ?: 0,
            how = how,
        )
    }

    /** r102: entry texts that mean "no filter" (the dropdown's first, empty option) */
    private val ANY_WORDS = setOf("all", "any", "everything", "all types", "all years", "show all", "-", "—")

    // r103: + the side menus' ▸ ▹ ► ▶
    private val ARROWS = Regex("""[\s▾▴▼▲⌄⌃∨∧˅˄›»⏷⏶▸▹►▶]+$""")

    /**
     * r102: the list's name as a filter name: "Year: All ▾" (a menu button above the list) → "Year"; arrows and a
     * value after the colon dropped. Null when nothing is left.
     */
    fun cleanHeading(raw: String?): String? {
        var t = raw?.replace(SPACES, " ")?.trim().orEmpty().replace(ARROWS, "").trim()
        val colon = t.indexOf(':')
        if (colon > 0) t = t.substring(0, colon).trim()
        return t.takeIf { it.isNotEmpty() && it.length <= 40 }
    }

    /**
     * r102: the tapped entries are a menu's buttons ("Type: All ▾ / Year: All ▾ / Sort: Hot ▾"), not the entries of
     * one list: the tapped element says it opens a popup, or most of 2–8 link-less entries read "Name: value" and the
     * tapped one does too
     */
    fun menuButtons(entries: List<GroupEntry>, tappedText: String, tappedPopup: Boolean): Boolean {
        if (tappedPopup) return true
        val plain = entries.filter { it.href.isNullOrBlank() && it.text.isNotBlank() }
        if (plain.size < 2 || plain.size > 8 || plain.size < entries.size) return false
        // A menu's button reads "Type: All", "Year: 2024 ▾", "Sort: Hot": a short name, a colon, a short value
        fun buttonLike(raw: String): Boolean {
            val text = raw.replace(SPACES, " ").trim().replace(ARROWS, "").trim()
            val at = text.indexOf(':')
            return at in 1..24 && text.length - at in 2..42 && text.substring(at + 1).isNotBlank()
        }
        val hits = plain.count { buttonLike(it.text) }
        val tapped = tappedText.trim()
        return hits >= 2 && hits * 5 >= plain.size * 3 && (tapped.isEmpty() || buttonLike(tapped))
    }

    // ---------- r102: Learn this list: the pattern of the learned addresses ----------

    /**
     * Where an entry's text goes in its address: query parameter [name] (`kind` "param") or path segment [index]
     * (`kind` "path"), written as [variant] (text|lower|dash|underscore); [template] is a learned address to fill
     */
    data class LearnPattern(val kind: String, val name: String, val index: Int, val variant: String, val template: String, val shown: String)

    private val VARIANTS = listOf("text", "lower", "dash", "underscore")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private const val MARK = "labvalue0mark"

    /** An entry's text as the address writes it: as is, lowercased, or slugified with - or _ */
    fun variantOf(text: String, variant: String): String = when (variant) {
        "lower" -> text.lowercase()
        "dash" -> text.lowercase().replace(NON_WORD, "-").trim('-')
        "underscore" -> text.lowercase().replace(NON_WORD, "_").trim('_')
        else -> text
    }

    /**
     * One query parameter or path segment whose value is each learned entry's text (as is, lowercased or slugified,
     * the same way for all), the rest of the addresses being the same: [learned] is (text, address), ≥ 2 of them.
     * Null when there is none.
     */
    fun findPattern(learned: List<Pair<String, String>>): LearnPattern? {
        if (learned.size < 2) return null
        val urls = learned.mapNotNull { (text, url) ->
            val u = url.trim().toHttpUrlOrNull() ?: return@mapNotNull null
            val t = cleanLabel(text)
            if (t.isEmpty()) null else t to u.newBuilder().fragment(null).build()
        }
        if (urls.size < learned.size) return null
        val first = urls.first().second
        for (name in first.queryParameterNames) {
            for (variant in VARIANTS) {
                val values = urls.map { (t, u) -> u.queryParameter(name)?.takeIf { it == variantOf(t, variant) } }
                if (values.any { it.isNullOrEmpty() } || values.distinct().size < 2) continue
                val rest = urls.map { (_, u) -> u.newBuilder().setQueryParameter(name, MARK).build().toString() }
                if (rest.distinct().size != 1) continue
                return LearnPattern("param", name, -1, variant, first.toString(), "$name=<text>" + shownVariant(variant))
            }
        }
        val segs = first.pathSegments
        for (index in segs.indices) {
            for (variant in VARIANTS) {
                val values = urls.map { (t, u) ->
                    u.pathSegments.takeIf { it.size == segs.size }?.get(index)?.takeIf { it == variantOf(t, variant) }
                }
                if (values.any { it.isNullOrEmpty() } || values.distinct().size < 2) continue
                val rest = urls.map { (_, u) -> u.newBuilder().setPathSegment(index, MARK).build().toString() }
                if (rest.distinct().size != 1) continue
                val shownPath = "/" + segs.mapIndexed { at, seg -> if (at == index) "<text>" else seg }.joinToString("/") +
                    (first.encodedQuery?.let { "?$it" } ?: "")
                return LearnPattern("path", "", index, variant, first.toString(), shownPath + shownVariant(variant))
            }
        }
        return null
    }

    private fun shownVariant(variant: String): String = when (variant) {
        "lower" -> " (lowercase)"
        "dash" -> " (a-b)"
        "underscore" -> " (a_b)"
        else -> ""
    }

    /** The address [pattern] gives [text], or null (an empty value) */
    fun fillPattern(pattern: LearnPattern, text: String): String? {
        val value = variantOf(cleanLabel(text), pattern.variant).takeIf { it.isNotEmpty() } ?: return null
        val base = pattern.template.toHttpUrlOrNull() ?: return null
        return when (pattern.kind) {
            "param" -> base.newBuilder().setQueryParameter(pattern.name, value).build().toString()
            else -> if (pattern.index < base.pathSize) base.newBuilder().setPathSegment(pattern.index, value).build().toString() else null
        }
    }

    /** How many distinct options [makeFilter] sees before its [MAX_GROUP_OPTIONS] cap */
    private fun optionCount(options: List<ExtrasOption>, mode: String): Int =
        options.filter { it.value.isNotEmpty() || mode == "param" }.distinctBy { it.value }.size

    private fun makeFilter(
        options: List<ExtrasOption>,
        mode: String,
        param: String,
        path: String,
        keyBase: String?,
        heading: String?,
        maxOptions: Int = MAX_GROUP_OPTIONS,
    ): ExtrasFilter {
        val distinct = options.filter { it.value.isNotEmpty() || mode == "param" }.distinctBy { it.value }.take(maxOptions)
        val sort = sortLike(distinct.map { it.label }, param.ifEmpty { null })
        val key = when {
            mode == "param" && param.isNotEmpty() -> param
            meaningful(keyBase) -> keyBase.orEmpty()
            sort -> "sort"
            else -> "list"
        }
        val name = heading?.trim()?.takeIf { it.isNotEmpty() } ?: when {
            sort -> "Sort"
            mode == "param" && param.isNotEmpty() -> prettyName(param)
            meaningful(keyBase) -> prettyName(keyBase.orEmpty())
            else -> "Genres"
        }
        val all = if (sort) distinct else withAny(distinct)
        return ExtrasFilter(
            key = key,
            name = name,
            type = if (sort) "sort" else "select",
            options = all,
            mode = mode,
            param = param,
            path = path,
        )
    }

    private const val LINK_MARK = "labvalue0link"

    /** r104: what the editor says (in red) about a filter the source can't use yet; null = usable */
    const val NOT_READY = "Not in the source yet — Learn addresses or type the address name"

    /** r104 fix: the note on a filter made from links before r104 ([ExtrasFilter.noLinkAddress]) */
    const val OLD_LINKS = "Made before r104 without its links' address: it may search the wrong page. " +
        "Pick this menu again on the site and press Make filter to read its own page"

    /**
     * r104: why [f] can't go into the source (no parameter and no address: a dropdown of buttons not learned yet, a
     * filter added by hand), or null when it can. Such filters stay in the editor and are left out of `filtersList`
     * with a log line, never silently.
     */
    fun filterProblem(f: ExtrasFilter): String? {
        val param = f.param.trim().ifEmpty { if (f.askParam) "" else f.key.trim() }
        return when {
            f.mode == "path" && "{value}" !in f.path -> NOT_READY
            f.mode != "path" && param.isEmpty() && "{value}" !in f.linkPath -> NOT_READY
            else -> null
        }
    }

    /** r104: the path part of a list / search address (no query, no trailing slash, lowercase) for comparing pages */
    fun pagePathOf(path: String?): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val pathOnly = p.toHttpUrlOrNull()?.encodedPath ?: p.substringBefore('#').substringBefore('?')
        return pathOnly.trimEnd('/').lowercase().ifEmpty { "/" }
    }

    /**
     * r104: [f] opens its own links' page instead of adding a parameter to the search address: a param filter made
     * from links whose page isn't the search page ([searchPath]). r104 fix: no search known (none recorded, none in
     * the source's code) = a parameter filter as before (not every link-made filter as another page)
     */
    fun usesLinkPage(f: ExtrasFilter, searchPath: String?): Boolean {
        if (f.mode == "path" || "{value}" !in f.linkPath) return false
        val own = pagePathOf(f.linkPath) ?: return false
        val search = pagePathOf(searchPath) ?: return false
        return own != search
    }

    /**
     * r105: what [f] does in the source: "page" (picks the list) or "extra" (a parameter added to it). Filters made
     * before r105 (no role): a path filter or one opening its links' own page is a page filter, else an extra
     */
    fun roleOf(f: ExtrasFilter, searchPath: String?): String = when {
        f.mode == "path" -> "page"
        // links to the search page itself (?sort= of the search) are a parameter of the search page
        f.role == "page" && "{value}" in f.linkPath && !linksToSearch(f, searchPath) -> "page"
        f.role.isNotEmpty() -> "extra"
        usesLinkPage(f, searchPath) -> "page"
        else -> "extra"
    }

    /** r105: [f] was made on the search page (its parameter was seen there, not on a list page) */
    fun madeOnSearchPage(f: ExtrasFilter, searchPath: String?): Boolean {
        if (linksToSearch(f, searchPath)) return true
        val made = pagePathOf(f.seenOn) ?: return false
        return made == pagePathOf(searchPath)
    }

    /** r105: [f]'s links open the search page itself (a parameter of the search, not another list) */
    fun linksToSearch(f: ExtrasFilter, searchPath: String?): Boolean {
        if ("{value}" !in f.linkPath) return false
        val own = pagePathOf(f.linkPath) ?: return false
        return own == pagePathOf(searchPath)
    }

    /** r105: an extra sent while searching for words: ticked by the owner, or made on the search page */
    fun worksOnSearch(f: ExtrasFilter, searchPath: String?): Boolean = f.onSearch || madeOnSearchPage(f, searchPath)

    /** r101: [b] is a new pick of [a]'s list (same type, key, mode and path / parameter): it replaces [a] */
    fun sameFilter(a: ExtrasFilter, b: ExtrasFilter): Boolean =
        a.type == b.type && a.key.isNotEmpty() && a.key == b.key && a.mode == b.mode &&
            if (a.mode == "path") a.path == b.path else a.param.isNotBlank() && a.param == b.param

    /**
     * r106: [b] is [a]'s list made again (r104: it replaces [a], keeping its place): the same filter ([sameFilter]) whose
     * option labels mostly agree. The owner's old Sort (Today, This week, This month: his Trending menu) and a learned
     * Sort dropdown (Release date, Recently added…) are two lists, never one replacing the other
     */
    fun sameList(a: ExtrasFilter, b: ExtrasFilter): Boolean {
        if (!sameFilter(a, b)) return false
        fun labels(f: ExtrasFilter) = f.options.filter { it.value.isNotEmpty() }.map { it.label.trim().lowercase() }.toSet()
        val la = labels(a)
        val lb = labels(b)
        if (la.isEmpty() || lb.isEmpty()) return true
        return la.intersect(lb).size * 2 >= minOf(la.size, lb.size)
    }

    /**
     * r106: the filters [made] would stand beside under the same name in the Browse sheet, or that send the same address
     * parameter: Replace / Keep both is asked. A remake of the same list ([sameList]) and what [replaces] says are
     * replaced as before, never asked
     */
    fun conflicts(filters: List<ExtrasFilter>, made: ExtrasFilter, replaces: (ExtrasFilter) -> Boolean = { false }): List<ExtrasFilter> {
        val name = made.name.trim()
        val param = made.param.trim()
        return filters.filter { f ->
            if (sameList(f, made) || replaces(f)) return@filter false
            val sameName = name.isNotEmpty() && f.name.trim().equals(name, ignoreCase = true)
            val sameParam = param.isNotEmpty() && made.mode != "path" && f.mode != "path" && f.param.trim() == param &&
                !(made.role == "page" && f.role == "page")
            sameName || sameParam
        }
    }

    /**
     * r106 (lint `filter-value-is-label`): a dropdown / sort that sends its labels as codes ("Release date" for
     * release_date): most options have a value equal to their label with a space or a capital in it
     */
    fun sendsLabels(f: ExtrasFilter): Boolean {
        if (f.type != "select" && f.type != "sort") return false
        val real = f.options.filter { it.value.isNotEmpty() }
        if (real.size < 2) return false
        val labelLike = real.count { o -> o.value == o.label.trim() && (o.value.any(Char::isWhitespace) || o.value.any(Char::isUpperCase)) }
        return labelLike * 2 >= real.size || (f.askParam && f.param.isBlank())
    }

    // ---------- r107: collections (a scanned directory as a "collection" filter, generic-list v10) ----------

    /** r107: the parts of an entry in the order the card and the template list them */
    val COLLECTION_FIELDS = listOf("number", "title", "videos", "views", "likes", "picture")

    // No braces in these patterns (ICU). KMK (r107) fix: creator / artist too, the same words as the app's drop-down
    private val PEOPLE = Regex(
        """actress|actor|model|performer|cast|idol|star|people|person|creator|artist""",
        RegexOption.IGNORE_CASE,
    )

    /** r107: a directory of people (its name or address): its pictures are faces, kept by default */
    fun peopleLike(vararg words: String?): Boolean = words.any { !it.isNullOrBlank() && PEOPLE.containsMatchIn(it) }

    /** r107: more entries or pages than this and the card picks "Live, as you scroll" first */
    const val LIVE_ENTRIES = 300
    const val LIVE_PAGES = 10

    /**
     * r107: a scanned card's defaults: number, name, videos and views ticked (people: every part, the picture too); Live
     * for big directories when page N has an address (else Read pages now); pages 1–[READ_PAGES]
     */
    fun defaultChoice(name: String?, scan: BuilderListReader.DirScan): DirChoice {
        val people = peopleLike(name, scan.url)
        val found = scan.parts.map { it.kind }.filter { it in COLLECTION_FIELDS }
        val fields = found.filter { people || it in setOf("number", "title", "videos", "views") }.toSet() + "title"
        val big = scan.total > LIVE_ENTRIES || scan.pages > LIVE_PAGES
        val canLive = scan.pageTemplate != null || scan.pages <= 1
        return DirChoice(fields = fields, live = big && canLive, from = 1, to = scan.pages.coerceIn(1, READ_PAGES))
    }

    /** r107: "about 1.5 min" for reading [pages] pages with a pause of [lowMs]–[highMs] between them */
    fun readEstimate(pages: Int, lowMs: Long, highMs: Long, inFlight: Int = 1): String {
        val n = pages.coerceAtLeast(1)
        // KMK (r119): [inFlight] pages at a time, the pause between each set
        val sets = (n + inFlight.coerceAtLeast(1) - 1) / inFlight.coerceAtLeast(1)
        val ms = (sets - 1) * (lowMs + highMs) / 2 + sets * PAGE_FETCH_MS
        return durationText(ms)
    }

    /** r107: a time left as "about 55 s" / "about 1.5 min" / "about 12 min" */
    fun durationText(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(1L)
        return when {
            s < 60 -> "about $s s"
            s < 600 -> "about ${(s / 6L) / 10f} min".replace(".0 min", " min")
            else -> "about ${s / 60L} min"
        }
    }

    /** r107: what one page takes besides the pause (the request and reading it), for the estimates */
    const val PAGE_FETCH_MS = 600L

    /** r107: "25,786" */
    fun thousands(n: Int): String = String.format(java.util.Locale.US, "%,d", n)

    /**
     * r107: the collection filter of a scanned directory: the entries' address form comes from their links (r101's
     * [deriveGroup]: the part that differs is `{value}`), each entry's value read from its link the way the template's
     * getDirectory reads it ([BuilderListReader.valueOfHref]), only the parts in [choice] kept, the scan's selectors,
     * sorts and menus in [CollectionSpec]. Null filter (with a note) when the links don't share one address form
     */
    fun collectionFilter(
        name: String?,
        group: String,
        scan: BuilderListReader.DirScan,
        entries: List<BuilderListReader.DirEntry>,
        choice: DirChoice,
        site: String,
        rule: PageRule?,
    ): GroupDerived {
        val heading = name?.takeIf { it.isNotBlank() } ?: nameFromUrl(scan.url)
        val derived = deriveGroup(
            entries = entries.map { GroupEntry(it.label, it.href, null) },
            tappedText = "",
            tappedHref = null,
            pageUrl = scan.url,
            rule = rule,
            heading = heading,
            maxOptions = MAX_SUGGESTIONS,
        )
        val made = derived.filter ?: return derived
        val path = when {
            made.mode == "path" -> made.path
            "{value}" in made.linkPath -> made.linkPath
            else -> return derived.copy(filter = null, note = (derived.note + " The entries' addresses don't share one form: no collection was made.").trim())
        }
        val keep = COLLECTION_FIELDS.filter { it == "title" || (it in choice.fields && scan.part(it) != null) }
        val options = entries.mapNotNull { e ->
            val value = BuilderListReader.valueOfHref(path, e.href)
            if (value.isEmpty()) {
                null
            } else {
                ExtrasOption(
                    label = e.label,
                    value = value,
                    number = if ("number" in keep) e.number else "",
                    videos = if ("videos" in keep) e.videos else "",
                    views = if ("views" in keep) e.views else "",
                    likes = if ("likes" in keep) e.likes else "",
                    picture = if ("picture" in keep) e.picture else "",
                )
            }
        }.distinctBy { it.value }.take(MAX_SUGGESTIONS)
        if (options.isEmpty()) {
            return derived.copy(filter = null, note = "None of the entries' links fits $path: no collection was made.")
        }
        val keyBase = made.key.takeIf { it.isNotEmpty() && it != "sort" && it != "list" }
            ?: nameFromUrl(scan.url)?.lowercase()?.replace(' ', '_')
            ?: "collection"
        val live = choice.live && (scan.pageTemplate != null || scan.pages <= 1)
        val spec = CollectionSpec(
            fields = keep,
            sorts = scan.sorts,
            reverse = scan.orderParam.isNotEmpty(),
            menus = scan.menus,
            live = live,
            total = scan.total,
            first = relative(scan.url, site),
            page = scan.pageTemplate?.let { relative(it, site) }.orEmpty(),
            pages = scan.pages,
            item = scan.item,
            link = scan.link,
            parts = scan.parts.filter { it.kind != "follow" },
            sortParam = scan.sortParam,
            orderParam = scan.orderParam,
            ascValue = scan.ascValue,
            descValue = scan.descValue,
        )
        val filter = ExtrasFilter(
            key = keyBase,
            name = heading ?: prettyName(keyBase),
            type = "collection",
            options = options,
            mode = "path",
            param = "",
            path = path,
            role = "page",
            group = group,
            seenOn = made.seenOn,
            collection = spec,
        )
        val how = if (spec.live) "live (page 1 kept, the rest read as Browse scrolls)" else "${options.size} entries kept"
        return derived.copy(
            filter = filter,
            note = "Made the collection \"${filter.name}\": picks open $path; $how; parts: ${keep.joinToString(", ")}" +
                (if (spec.sorts.isNotEmpty()) "; ${spec.sorts.size} sort options" else "") +
                (if (spec.menus.isNotEmpty()) "; menus ${spec.menus.joinToString(", ") { it.name }}" else "") + ".",
            used = options.size,
            how = "collection $path",
        )
    }

    // No braces in these patterns (ICU). KMK (r107) fix: up to 4 digits (a list's places 1000–1800)
    private val PLACE_PREFIX = Regex("""^(\d\d?\d?\d?)\s+(?=\S)""")

    /**
     * KMK (r107) fix: the names carry a place number to strip: most of them count up one by one ("01 …", "02 …" …
     * "1800 …"), or nearly all start with one. A list whose names merely start with a number keeps them
     */
    private fun placeNumbered(labels: List<String>): Boolean {
        if (labels.size < 2) return false
        val nums = labels.map { PLACE_PREFIX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val up = (1 until nums.size).count { i -> nums[i] != null && nums[i - 1] != null && nums[i] == nums[i - 1]!! + 1 }
        if (up * 5 >= (labels.size - 1) * 4) return true
        return nums.count { it != null } * 5 >= labels.size * 4
    }

    /**
     * r107: a directory filter saved before r107 with whole cards as names (the owner's Makers "FC FC2 33,977 videos +
     * Follow Following", Series "01 Acrobatic Sex! 4,476 videos · 6.2M views + Follow Following" saved as a Sort):
     * the names cleaned (counts, Follow, a place number before every name, a logo's letters), a Sort made a text box
     * with suggestions. Null = nothing to repair
     */
    fun repairLabels(f: ExtrasFilter): ExtrasFilter? {
        if (f.mode != "path" || f.collection != null || f.type !in setOf("sort", "select", "autocomplete")) return null
        val real = f.options.filter { it.value.isNotEmpty() }
        if (real.size < 5) return null
        var labels = real.map { BuilderListReader.entryLabel(it.label) }
        val changed = real.indices.count { labels[it] != real[it].label }
        if (changed * 2 < real.size) return null
        if (placeNumbered(labels)) labels = labels.map { it.replace(PLACE_PREFIX, "") }
        labels = labels.map { BuilderListReader.dropInitials(it) }
        val cleaned = real.mapIndexed { i, o -> o.copy(label = labels[i].ifEmpty { o.label }) }
        val type = if (f.type == "sort") "autocomplete" else f.type
        val all = if (type == "select") withAny(cleaned) else cleaned
        return f.copy(type = type, options = all, optionsText = formatOptions(all))
    }

    /**
     * KMK (r109): a scanned directory with a custom drop-down's learned codes: a sort becomes its sort options (the
     * default first, value "") and parameter ("" = each option is an address of its own); another menu is added (or
     * replaces the one on the same parameter). The drop-down is no longer waiting to be pressed
     */
    fun withDirMenu(scan: BuilderListReader.DirScan, m: BuilderDirMenus.Menu, coded: BuilderDirMenus.Coded): BuilderListReader.DirScan {
        val rest = scan.custom.filterNot { it.label.equals(m.label, ignoreCase = true) }
        return when {
            // KMK (r110) fix: a menu never shares the sort's parameter (r110's Height / Cup / Age learned as `sort`
            // replaced each other and the sort); the sort takes it over from a menu
            m.sort -> scan.copy(
                sorts = coded.options,
                sortParam = coded.param,
                menus = if (coded.param.isEmpty()) scan.menus else scan.menus.filter { it.key != coded.param },
                custom = rest,
            )
            coded.param.isEmpty() -> scan.copy(custom = rest)
            coded.param == scan.sortParam -> scan.copy(custom = rest)
            else -> scan.copy(
                menus = (scan.menus.filter { it.key != coded.param } + CollectionMenu(coded.param, m.label, coded.options)).take(8),
                custom = rest,
            )
        }
    }

    /** KMK (r110): an address's path ("/en/actresses"), without "?…" and a trailing "/"; relative ones too */
    fun pathOnly(address: String): String {
        val t = address.trim()
        if (t.isEmpty()) return ""
        val path = t.toHttpUrlOrNull()?.encodedPath ?: t.substringBefore('#').substringBefore('?')
        return path.trimEnd('/')
    }

    /** KMK (r110): the path of a collection's directory page ("/en/actresses"), "" = not a collection / unknown */
    fun directoryPath(f: ExtrasFilter): String {
        val c = f.collection ?: return ""
        if (f.type != "collection") return ""
        return pathOnly(c.first)
    }

    /**
     * KMK (r110): [f] was made on directory page [dir] (picked there: [ExtrasFilter.seenOn]) or opens it ([path] /
     * [linkPath]). [dir] "" or the site's root never matches
     */
    fun madeOnDirectory(f: ExtrasFilter, dir: String): Boolean {
        if (dir.isEmpty()) return false
        return listOf(f.seenOn, f.path, f.linkPath).any { it.isNotBlank() && pathOnly(it) == dir }
    }

    /**
     * KMK (r110): a parameter filter made from a menu of a directory page (Height / Cup / Age / Sort picked on the
     * Actresses page) as that directory's own menu: its options with the default ("" value) first, named after the
     * site's default when known. Null = not one ([f] is a collection, a path filter, has no parameter or no codes)
     */
    fun asDirectoryMenu(f: ExtrasFilter): CollectionMenu? {
        if (f.type == "collection" || f.collection != null || f.mode != "param" || f.param.isBlank()) return null
        if (f.options.none { it.value.isNotEmpty() }) return null
        val name = f.siteDefault.trim()
        val opts = withAny(f.options).map { if (it.value.isEmpty() && name.isNotEmpty()) it.copy(label = name) else it }
        return CollectionMenu(f.param, f.name.ifBlank { f.param }, opts)
    }

    /**
     * KMK (r110): a scan with [menu] kept as its sort ([sort]) or one of its menus (replacing the one on the same
     * parameter), as [withDirMenu] does for a learned drop-down
     */
    fun withMenu(scan: BuilderListReader.DirScan, menu: CollectionMenu, sort: Boolean): BuilderListReader.DirScan =
        if (sort) {
            scan.copy(sorts = menu.options, sortParam = menu.key)
        } else if (menu.key == scan.sortParam) {
            scan // KMK (r110) fix: never a menu on the sort's parameter
        } else {
            scan.copy(menus = (scan.menus.filter { it.key != menu.key } + menu).take(8))
        }

    /** KMK (r110): the same for a collection already made */
    fun withMenu(c: CollectionSpec, menu: CollectionMenu, sort: Boolean): CollectionSpec =
        if (sort) {
            c.copy(sorts = menu.options, sortParam = menu.key)
        } else if (menu.key == c.sortParam) {
            c // KMK (r110) fix: never a menu on the sort's parameter
        } else {
            c.copy(menus = (c.menus.filter { it.key != menu.key } + menu).take(8))
        }

    /**
     * KMK (r110) repair: the owner's r109 save had Height / Cup / Age (picked on the Actresses page) as top-level
     * filters in Browse's Options next to Type / Year. A directory's menus belong only to its collection: a top-level
     * parameter filter is dropped when its parameter is one of a collection's menus (or its sort parameter) AND it was
     * made on that directory's page (or its path is the directory's). Returns the filters kept and those dropped
     *
     * KMK (r110) fix: also when its name is one of the directory page's menu labels ([menuLabels]: directory path →
     * "Height", "Cup", "Age"… of the scanned card, learned or not; a collection's own menu names count too), even when
     * the collection has no such menu yet or isn't made
     */
    fun dropDirectoryMenus(
        filters: List<ExtrasFilter>,
        menuLabels: Map<String, Collection<String>> = emptyMap(),
    ): Pair<List<ExtrasFilter>, List<ExtrasFilter>> {
        val dirs = filters.filter { it.type == "collection" && it.collection != null }
        // directory path → its parameters and its menu labels (lower case)
        val known = LinkedHashMap<String, Pair<MutableSet<String>, MutableSet<String>>>()
        fun at(path: String) = known.getOrPut(path) { HashSet<String>() to HashSet() }
        for (d in dirs) {
            val c = d.collection ?: continue
            val path = directoryPath(d)
            if (path.isEmpty()) continue
            at(path).first.addAll(c.menus.map { it.key } + listOfNotNull(c.sortParam.takeIf { it.isNotEmpty() }))
            at(path).second.addAll(c.menus.map { it.name.trim().lowercase() }.filter { it.isNotEmpty() })
        }
        for ((path, labels) in menuLabels) {
            if (path.isEmpty()) continue
            at(path).second.addAll(labels.map { it.trim().lowercase() }.filter { it.isNotEmpty() })
        }
        if (known.isEmpty()) return filters to emptyList()
        val kept = ArrayList<ExtrasFilter>()
        val dropped = ArrayList<ExtrasFilter>()
        for (f in filters) {
            val isMenu = f.type != "collection" && f.collection == null && f.mode == "param" && f.param.isNotBlank() &&
                known.any { (path, k) ->
                    (f.param in k.first || f.name.trim().lowercase() in k.second) && madeOnDirectory(f, path)
                }
            if (isMenu) dropped.add(f) else kept.add(f)
        }
        return kept to dropped
    }

    /** r107: a collection's entries edited as text keep the parts of the entries whose value stayed */
    fun keepParts(old: List<ExtrasOption>, edited: List<ExtrasOption>): List<ExtrasOption> {
        val byValue = old.associateBy { it.value }
        return edited.map { e ->
            val o = byValue[e.value] ?: return@map e
            e.copy(number = o.number, videos = o.videos, views = o.views, likes = o.likes, picture = o.picture)
        }
    }

    /** r107: a collection filter as generic-list v10's FILTERS entry (the contract the app's Browse sheet reads) */
    private fun collectionJson(f: ExtrasFilter, c: CollectionSpec, key: String): JsonObject {
        val keep = COLLECTION_FIELDS.filter { it == "title" || it in c.fields }
        fun option(o: ExtrasOption): JsonObject {
            val m = LinkedHashMap<String, JsonElement>()
            m["label"] = JsonPrimitive(o.label)
            m["value"] = JsonPrimitive(o.value)
            if ("number" in keep && o.number.isNotEmpty()) m["number"] = JsonPrimitive(o.number)
            if ("videos" in keep && o.videos.isNotEmpty()) m["videos"] = JsonPrimitive(o.videos)
            if ("views" in keep && o.views.isNotEmpty()) m["views"] = JsonPrimitive(o.views)
            if ("likes" in keep && o.likes.isNotEmpty()) m["likes"] = JsonPrimitive(o.likes)
            if ("picture" in keep && o.picture.isNotEmpty()) m["picture"] = JsonPrimitive(o.picture)
            return JsonObject(m)
        }
        fun plain(o: ExtrasOption) = JsonObject(mapOf("label" to JsonPrimitive(o.label), "value" to JsonPrimitive(o.value)))
        val parts = LinkedHashMap<String, JsonElement>()
        c.parts.filter { it.kind in COLLECTION_FIELDS }.forEach { p ->
            val m = LinkedHashMap<String, JsonElement>()
            m["sel"] = JsonPrimitive(p.selector)
            if (p.attr.isNotEmpty()) m["attr"] = JsonPrimitive(p.attr)
            if (p.own) m["own"] = JsonPrimitive(true)
            if (p.bare) m["bare"] = JsonPrimitive(true)
            parts[p.kind] = JsonObject(m)
        }
        val directory = LinkedHashMap<String, JsonElement>()
        directory["first"] = JsonPrimitive(c.first)
        directory["page"] = JsonPrimitive(c.page)
        directory["pages"] = JsonPrimitive(c.pages)
        directory["item"] = JsonPrimitive(c.item)
        directory["link"] = JsonPrimitive(c.link)
        directory["parts"] = JsonObject(parts)
        directory["sortParam"] = JsonPrimitive(c.sortParam)
        if (c.orderParam.isNotEmpty()) {
            directory["orderParam"] = JsonPrimitive(c.orderParam)
            directory["ascValue"] = JsonPrimitive(c.ascValue)
            directory["descValue"] = JsonPrimitive(c.descValue)
        }
        val map = LinkedHashMap<String, JsonElement>()
        map["key"] = JsonPrimitive(key)
        map["name"] = JsonPrimitive(f.name.trim().ifEmpty { prettyName(key) })
        map["type"] = JsonPrimitive("collection")
        map["mode"] = JsonPrimitive("path")
        map["path"] = JsonPrimitive(f.path.trim())
        map["role"] = JsonPrimitive("page")
        f.group.trim().takeIf { it.isNotEmpty() }?.let { map["group"] = JsonPrimitive(it) }
        map["fields"] = JsonArray(keep.map { JsonPrimitive(it) })
        map["options"] = JsonArray(f.options.filter { it.value.isNotEmpty() }.map(::option))
        // KMK (r109): the site's default sort may come first with value "" under its own name ("Most videos"): picking it
        // asks getDirectory for the site's own order; other empty values are left out
        map["sorts"] = JsonArray(c.sorts.filterIndexed { i, o -> o.label.isNotBlank() && (o.value.isNotEmpty() || i == 0) }.map(::plain))
        map["reverse"] = JsonPrimitive(c.reverse && c.orderParam.isNotEmpty())
        // KMK (r109): the menus are written for stored collections too: Browse switches a stored list to live paging
        // (getDirectory) when one of them is picked (r107 wrote them only when live)
        map["menus"] = JsonArray(
            (if (c.item.isNotEmpty()) c.menus else emptyList()).map { m ->
                JsonObject(mapOf("key" to JsonPrimitive(m.key), "name" to JsonPrimitive(m.name), "options" to JsonArray(m.options.map(::plain))))
            },
        )
        map["live"] = JsonPrimitive(c.live && c.item.isNotEmpty())
        map["total"] = JsonPrimitive(c.total)
        map["directory"] = JsonObject(directory)
        return JsonObject(map)
    }

    /** r106: a list read in full is a text box with suggestions when it is longer than a dropdown reads well */
    fun formFor(entries: Int): String = if (entries > 60) "autocomplete" else "select"

    /** [base], or base2, base3… when another filter has it */
    fun uniqueKey(base: String, taken: Collection<String>): String {
        val b = base.trim().ifEmpty { "filter" }
        if (b !in taken) return b
        var n = 2
        while ("$b$n" in taken) n++
        return "$b$n"
    }

    /** The list's name from its address: `/en/genres` → "Genres" */
    fun nameFromUrl(url: String): String? {
        val segs = url.toHttpUrlOrNull()?.pathSegments?.filter { it.isNotEmpty() }.orEmpty()
        val word = segs.lastOrNull { meaningful(it) && it.lowercase() !in PAGE_WORDS && !it.all(Char::isDigit) }
        return word?.let(::prettyName)
    }

    const val MAX_GROUP_OPTIONS = 400

    /** r106: "Read all pages" on a card reads up to this many pages ("30 pages at most") */
    const val READ_PAGES = 30

    /** r103: a text box with suggestions keeps more of a long list (every actress, every maker) */
    const val MAX_SUGGESTIONS = 3000

    /**
     * r103: a filter made from a fetched list as a Text box with suggestions: type "autocomplete", the options
     * without the empty "Any" (an empty box means no filter); a Sort stays a Sort
     */
    /**
     * r103 fix: [f] as [type]: "select" (Dropdown) puts the empty "Any" option first ([withAny]: the app sends the
     * first option when nothing was picked), "autocomplete" drops it (an empty box is no filter), others keep theirs
     */
    fun withType(f: ExtrasFilter, type: String): ExtrasFilter {
        if (f.type == type) return f
        return when (type) {
            "select" -> {
                val options = withAny(f.options)
                f.copy(type = type, options = options, optionsText = formatOptions(options))
            }
            "autocomplete" -> {
                val options = f.options.filter { it.value.isNotEmpty() }
                f.copy(type = type, options = options, optionsText = formatOptions(options))
            }
            // r107: back to its collection (only a scanned directory's filter has one)
            "collection" -> if (f.collection == null) {
                f
            } else {
                val options = f.options.filter { it.value.isNotEmpty() }
                f.copy(type = type, options = options, optionsText = formatOptions(options))
            }
            else -> f.copy(type = type)
        }
    }

    fun asAutocomplete(f: ExtrasFilter): ExtrasFilter {
        if (f.type == "sort") return f
        val options = f.options.filter { it.value.isNotEmpty() }
        return f.copy(type = "autocomplete", options = options, optionsText = formatOptions(options))
    }

    /**
     * r103: picker.js `menuPick`'s groups flattened into cards: a group with two or more entries, or with its own
     * link (an index page), is a card; its subgroups follow it (Collections → Genres, Actresses…)
     */
    fun menuGroupsOf(groups: List<JsonObject>, pageUrl: String, firstId: Int): List<MenuGroup> {
        val out = ArrayList<MenuGroup>()
        // r106: [parentId] = the card of the heading above (a sub-menu is a row of that card)
        fun walk(g: JsonObject, parents: List<String>, parentId: Int?) {
            val rawHeading = g.str("heading").orEmpty()
            val path = g.arr("headingPath")?.mapNotNull { (it as? JsonPrimitive)?.content }?.filter { it.isNotBlank() }
                ?: (parents + rawHeading).filter { it.isNotBlank() }
            val items = g.arr("items")?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                MenuItem(
                    o.str("text").orEmpty(),
                    o.str("href")?.takeIf { it.isNotBlank() },
                    o.str("value"),
                    o.str("path")?.takeIf { it.isNotBlank() },
                    // KMK (r109): picker.js marks the site's current entry
                    current = o.bool("current") == true,
                )
            }.orEmpty().filter { it.text.isNotBlank() || !it.href.isNullOrBlank() || it.value != null }
            val href = g.str("href")?.trim()?.takeIf { it.startsWith("http") }
            val name = cleanHeading(rawHeading)
                ?: path.lastOrNull()?.let(::cleanHeading)
                ?: href?.let(::nameFromUrl)
                ?: ""
            val subs = g.arr("subgroups")?.mapNotNull { it as? JsonObject }.orEmpty()
            // r106: a heading holding only sub-menus (Collections → Genre, Actress, Maker, Series) is a card too
            val heading = items.size < 2 && href == null && subs.size >= 2
            var mine: Int? = null
            if (items.size >= 2 || href != null || heading) {
                mine = firstId + out.size
                out += MenuGroup(
                    id = mine,
                    name = name,
                    headingPath = path.map { cleanHeading(it) ?: it },
                    opener = g.str("opener")?.trim()?.takeIf { it.isNotEmpty() },
                    href = href,
                    index = g.bool("index") == true,
                    items = items.take(MAX_GROUP_OPTIONS),
                    pageUrl = pageUrl,
                    param = g.str("param")?.trim()?.takeIf { it.isNotEmpty() },
                    parent = parentId,
                    // KMK (r109): "Sort: Most videos" (its heading or its button) → the site's default "Most videos"
                    current = BuilderDirMenus.currentOf(rawHeading).ifEmpty { BuilderDirMenus.currentOf(g.str("openerText")) }
                        .ifEmpty { items.firstOrNull { it.current }?.text.orEmpty() },
                )
            }
            subs.forEach { walk(it, path, mine ?: parentId) }
        }
        groups.forEach { walk(it, emptyList(), null) }
        return out
    }

    /** The query parameter whose value is `{q}` in a search path ("q" in `/search?q={q}&page={page}`) */
    fun searchParam(searchPath: String?): String? {
        val query = searchPath?.substringAfter('?', "") ?: return null
        return query.split('&').firstOrNull { it.substringAfter('=', "") == "{q}" }?.substringBefore('=')
    }

    /**
     * The query parameters that changed between two consecutive addresses of the same page (same scheme, host and
     * path), page numbers, the search word's parameter, [exclude] and tracking ones left out: param → the values
     * seen (decoded, non-blank, in order).
     */
    fun changedParams(previous: String, current: String, word: String, exclude: Set<String>): List<Pair<String, List<String>>> {
        val a = previous.toHttpUrlOrNull() ?: return emptyList()
        val b = current.toHttpUrlOrNull() ?: return emptyList()
        if (a.scheme != b.scheme || a.host != b.host || a.encodedPath != b.encodedPath) return emptyList()
        val w = word.trim().lowercase()
        val names = LinkedHashSet<String>().apply {
            addAll(a.queryParameterNames)
            addAll(b.queryParameterNames)
        }
        val out = ArrayList<Pair<String, List<String>>>()
        for (name in names) {
            if (name.isBlank() || name.lowercase() in PAGE_PARAMS || name in exclude) continue
            if (name.startsWith("utm_") || name == "_") continue
            val before = a.queryParameter(name).orEmpty()
            val after = b.queryParameter(name).orEmpty()
            if (before == after) continue
            if (w.isNotEmpty() && (looksLike(before, w) || looksLike(after, w))) continue
            out.add(name to listOf(before, after).filter { it.isNotBlank() }.distinct())
        }
        return out
    }

    private fun looksLike(value: String, word: String): Boolean {
        val v = value.lowercase()
        return word in v || word.replace(' ', '+') in v
    }

    /** `Label = value` per line; a line without `=` is both label and value */
    fun parseOptions(text: String): List<ExtrasOption> = text.lines().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val at = line.indexOf('=')
        if (at < 0) {
            ExtrasOption(line.trim(), line.trim())
        } else {
            val label = line.substring(0, at).trim()
            val value = line.substring(at + 1).trim()
            ExtrasOption(label.ifEmpty { value.ifEmpty { "Any" } }, value)
        }
    }

    fun formatOptions(options: List<ExtrasOption>): String = options.joinToString("\n") { "${it.label} = ${it.value}" }

    /** A dropdown's first option is what the app sends when nothing was picked: an empty one ("Any") goes first */
    fun withAny(options: List<ExtrasOption>): List<ExtrasOption> {
        val distinct = options.distinctBy { it.value }
        return if (distinct.any { it.value.isEmpty() }) {
            distinct.sortedBy { if (it.value.isEmpty()) 0 else 1 }
        } else {
            listOf(ExtrasOption("Any", "")) + distinct
        }
    }

    /** r105 fix: the label of the empty option a parameter's Dropdown / Sort gets when it has none */
    const val SITE_DEFAULT = "Site default"

    /**
     * KMK (r109): a filter whose empty first option is "Site default" (or "Any") while its learned default is known
     * ([ExtrasFilter.siteDefault]): that option is named after the default and the default's own coded entry goes
     * (picking it sends nothing, the site's own order). Null = nothing to repair. Filters saved before r109 have no
     * [ExtrasFilter.siteDefault]: learning the menu again names their default
     */
    fun repairSiteDefault(f: ExtrasFilter): ExtrasFilter? {
        val name = f.siteDefault.trim()
        if (name.isEmpty() || f.type == "collection") return null
        val first = f.options.firstOrNull() ?: return null
        if (first.value.isNotEmpty() || first.label.equals(name, ignoreCase = true)) return null
        if (first.label != SITE_DEFAULT && first.label != "Any") return null
        val rest = f.options.drop(1).filterNot { it.value.isNotEmpty() && it.label.equals(name, ignoreCase = true) }
        val all = listOf(first.copy(label = name)) + rest
        return f.copy(options = all, optionsText = formatOptions(all))
    }

    /**
     * KMK (r109) fix: a parameter's Dropdown / Sort made from a link menu (or saved before r109) whose default isn't
     * named yet: [current] (the value the menu's button / heading showed, "Sort: Most videos" → "Most videos") names its
     * empty first option and that entry's own code goes. Null = unchanged (not known, a path filter, already named,
     * an empty option of its own like "All", or [current] isn't one of its entries)
     */
    fun nameDefault(f: ExtrasFilter, current: String): ExtrasFilter? {
        val name = current.trim()
        if (name.isEmpty() || f.siteDefault.isNotBlank() || f.mode == "path") return null
        if ((f.type != "sort" && f.type != "select") || f.options.isEmpty()) return null
        if (f.options.none { it.value.isNotEmpty() && it.label.trim().equals(name, ignoreCase = true) }) return null
        val opts = withSiteDefault(f.options)
        return repairSiteDefault(f.copy(options = opts, optionsText = formatOptions(opts), siteDefault = name))
    }

    /**
     * KMK (r109) fix: the filter's empty first option will be "Site default" (its default isn't known): its card says
     * "Learn this menu again to name its default" (the caller leaves out one that opens its links' page: "Any" first)
     */
    fun unnamedDefault(f: ExtrasFilter): Boolean {
        if (f.siteDefault.isNotBlank() || f.mode == "path" || (f.type != "sort" && f.type != "select") || f.options.isEmpty()) return false
        val first = withSiteDefault(f.options).first()
        return first.value.isEmpty() && first.label == SITE_DEFAULT
    }

    /**
     * r105 fix: [options] with an empty option first (moved there, or "Site default" added): the app sends the first
     * option of an untouched sheet, and the template sends every non-empty value, so the site's first one can be picked
     */
    fun withSiteDefault(options: List<ExtrasOption>): List<ExtrasOption> {
        val at = options.indexOfFirst { it.value.isEmpty() }
        return when {
            at == 0 -> options
            at > 0 -> listOf(options[at]) + options.filterIndexed { i, _ -> i != at }
            else -> listOf(ExtrasOption(SITE_DEFAULT, "")) + options
        }
    }

    /**
     * r105 fix: a filter made again (Learn again, Fetch again, the menu picked again) keeps what the owner set on the
     * one it replaces: its group, "Also while searching for words" and a role chosen on the card (a page role only
     * when the new one has an address)
     */
    fun keepOwnerSettings(old: ExtrasFilter, made: ExtrasFilter): ExtrasFilter {
        val canPage = made.mode == "path" || "{value}" in made.linkPath
        val keepRole = old.roleSet && old.role.isNotEmpty() && (old.role != "page" || canPage)
        return made.copy(
            group = old.group,
            onSearch = old.onSearch,
            role = if (keepRole) old.role else made.role,
            roleSet = keepRole,
        )
    }

    /**
     * r105 fix: the filters with each group's filters together where its first one is (as the Browse sheet shows them:
     * one heading per group), the others in their order
     */
    fun groupsTogether(filters: List<ExtrasFilter>): List<ExtrasFilter> {
        val out = ArrayList<ExtrasFilter>(filters.size)
        val done = HashSet<String>()
        filters.forEach { f ->
            val g = f.group.trim()
            if (g.isEmpty()) {
                out += f
            } else if (done.add(g)) {
                out += filters.filter { it.group.trim() == g }
            }
        }
        return out
    }

    /**
     * r105 fix: ▲ / ▼ of filter [index] ([delta] < 0 = up) with groups kept together: inside its group it swaps with
     * its neighbour; at its group's edge it leaves the group (staying where it is); outside groups, next to a group, it
     * joins that group at that end; the only filter of a group moves past its neighbour (a whole group when that is
     * one), keeping its group. Null = nothing to move
     */
    fun moveInGroups(filters: List<ExtrasFilter>, index: Int, delta: Int): List<ExtrasFilter>? {
        if (index !in filters.indices || delta == 0) return null
        val step = if (delta < 0) -1 else 1
        val f = filters[index]
        val g = f.group.trim()
        val to = index + step
        val n = filters.getOrNull(to)
        val ng = n?.group?.trim().orEmpty()
        val out = filters.toMutableList()
        when {
            n == null -> {
                if (g.isEmpty()) return null
                out[index] = f.copy(group = "")
            }
            g.isNotEmpty() && ng == g -> {
                out[index] = n
                out[to] = f
            }
            g.isNotEmpty() && filters.count { it.group.trim() == g } == 1 -> {
                if (ng.isEmpty()) {
                    out[index] = n
                    out[to] = f
                } else {
                    // past the neighbour group's far end
                    var end = to
                    while (filters.getOrNull(end + step)?.group?.trim() == ng) end += step
                    out.removeAt(index)
                    out.add(end.coerceIn(0, out.size), f)
                }
            }
            g.isNotEmpty() -> out[index] = f.copy(group = "")
            ng.isNotEmpty() -> out[index] = f.copy(group = ng)
            else -> {
                out[index] = n
                out[to] = f
            }
        }
        return groupsTogether(out)
    }

    /** "sort_by" → "Sort by" */
    fun prettyName(param: String): String =
        param.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }.ifEmpty { param }

    /** A recorded path as the template takes it: relative to [site] when it is on it */
    fun forSite(path: String, site: String): String = if (path.startsWith("/")) path else relative(path, site)

    fun tabsJson(tabs: List<ExtrasTab>, site: String): String = JsonArray(
        tabs.filter { it.path.isNotBlank() }
            .distinctBy { it.path.trim() }
            .map { tab ->
                val path = forSite(tab.path.trim(), site)
                JsonObject(mapOf("name" to JsonPrimitive(tab.name.trim().ifEmpty { path }), "path" to JsonPrimitive(path)))
            },
    ).toString()

    /**
     * The getFilters format (JsFilters) plus how a value changes the address (r101): `mode` "param" (`param`=value)
     * or "path" (`{value}` in `path` replaced), and `on` (a ticked checkbox's value). `type` "sort" becomes a
     * JsFilters Sort group in the template. A param filter without a parameter, or a path filter without `{value}`,
     * is left out. r100 filters (no mode) are param filters named by their key.
     */
    fun filtersJson(filters: List<ExtrasFilter>, searchPath: String? = null): String {
        val keys = HashSet<String>()
        return JsonArray(
            filters.mapNotNull { f ->
                // r104: not usable yet: left out here; the editor shows it in red and templateValues logs it
                if (filterProblem(f) != null) return@mapNotNull null
                // r104: a filter made from links of another page than the search page opens that page. r105: a page
                // filter made from links opens its links' page; an extra stays a parameter
                val role = roleOf(f, searchPath)
                val linkPage = f.mode != "path" && "{value}" in f.linkPath &&
                    if (f.role.isEmpty()) usesLinkPage(f, searchPath) else role == "page"
                val mode = if (f.mode == "path" || linkPage) "path" else "param"
                val param = f.param.trim().ifEmpty { if (f.askParam) "" else f.key.trim() }
                val path = if (linkPage) f.linkPath.trim() else f.path.trim()
                if (mode == "param" && param.isEmpty()) return@mapNotNull null
                if (mode == "path" && "{value}" !in path) return@mapNotNull null
                val key = uniqueKey(f.key.trim().ifEmpty { param.ifEmpty { "filter" } }, keys)
                keys += key
                // r107 (generic-list v10): a directory's collection, with its parts, sorts, menus and how to read it
                val c = f.collection
                if (f.type == "collection" && c != null && mode == "path") return@mapNotNull collectionJson(f, c, key)
                // r104 fix: the app's Dropdown / Sort always sends an option (the first when nothing was picked): a
                // filter that opens its own page gets an empty "Any" first, so it opens that page only when picked.
                // r105 fix: a parameter's Dropdown / Sort gets its empty option first too ("Site default" when it has
                // none), so the site's own first option is a pick like the others (generic-list v8 sends any value)
                val listLike = (f.type == "select" || f.type == "sort") && f.options.isNotEmpty()
                val options = when {
                    listLike && mode == "path" -> withAny(f.options)
                    listLike -> withSiteDefault(f.options)
                    else -> f.options
                }
                val type = when (f.type) {
                    "checkbox" -> "checkbox"
                    "select" -> if (options.isEmpty()) "text" else "select"
                    "sort" -> if (options.isEmpty()) "text" else "sort"
                    // r103: a text box with suggestions (generic-list v6 maps the typed labels to values); r107: a collection
                    // without its directory (not scanned) is one too
                    "autocomplete", "collection" -> "autocomplete"
                    else -> "text"
                }
                val map = LinkedHashMap<String, JsonElement>()
                map["key"] = JsonPrimitive(key)
                map["name"] = JsonPrimitive(f.name.trim().ifEmpty { prettyName(key) })
                map["type"] = JsonPrimitive(type)
                map["mode"] = JsonPrimitive(mode)
                if (mode == "param") map["param"] = JsonPrimitive(param) else map["path"] = JsonPrimitive(path)
                // r105 (generic-list v8): the role ("" = a filter made before r105: the template reads it as v7 did)
                if (f.role.isNotEmpty()) {
                    map["role"] = JsonPrimitive(if (mode == "path") "page" else "extra")
                    if (mode == "param") {
                        map["onSearch"] = JsonPrimitive(worksOnSearch(f, searchPath))
                        map["onLists"] = JsonPrimitive(!madeOnSearchPage(f, searchPath))
                    }
                }
                f.group.trim().takeIf { it.isNotEmpty() }?.let { map["group"] = JsonPrimitive(it) }
                map["options"] = JsonArray(
                    if (type == "select" || type == "sort" || type == "autocomplete") {
                        options.map { JsonObject(mapOf("label" to JsonPrimitive(it.label), "value" to JsonPrimitive(it.value))) }
                    } else {
                        emptyList()
                    },
                )
                if (type == "checkbox") map["on"] = JsonPrimitive(f.onValue.trim().ifEmpty { "1" })
                JsonObject(map)
            },
        ).toString()
    }

    /** The getSourcePreferences format (JsPreferences.parse): key, title, type, options [{label, value}], defaultValue */
    fun settingsJson(s: BuilderExtras): String {
        val out = ArrayList<JsonElement>()
        fun option(v: String) = JsonObject(mapOf("label" to JsonPrimitive(v), "value" to JsonPrimitive(v)))
        if (s.qualitySetting) {
            out += JsonObject(
                mapOf(
                    "key" to JsonPrimitive("quality"),
                    "title" to JsonPrimitive("Preferred quality"),
                    "summary" to JsonPrimitive("Videos of this quality are listed first"),
                    "type" to JsonPrimitive("list"),
                    "options" to JsonArray(QUALITIES.map(::option)),
                    "defaultValue" to JsonPrimitive(QUALITIES.first()),
                ),
            )
        }
        if (s.serverSetting) {
            out += JsonObject(
                mapOf(
                    "key" to JsonPrimitive("server"),
                    "title" to JsonPrimitive("Preferred server"),
                    "summary" to JsonPrimitive("Videos from a server whose name contains this are listed first"),
                    "type" to JsonPrimitive("text"),
                    "defaultValue" to JsonPrimitive(""),
                ),
            )
        }
        if (s.hideWordsSetting) {
            out += JsonObject(
                mapOf(
                    "key" to JsonPrimitive("hideWords"),
                    "title" to JsonPrimitive("Hide titles containing"),
                    "summary" to JsonPrimitive("Words separated by commas; titles containing any of them are not listed"),
                    "type" to JsonPrimitive("text"),
                    "defaultValue" to JsonPrimitive(""),
                ),
            )
        }
        val taken = out.mapNotNull { (it as? JsonObject)?.str("key") }.toMutableSet()
        s.custom.forEach { c ->
            val key = c.key.trim()
            if (key.isEmpty() || !taken.add(key)) return@forEach
            val map = LinkedHashMap<String, JsonElement>()
            map["key"] = JsonPrimitive(key)
            map["title"] = JsonPrimitive(c.name.trim().ifEmpty { prettyName(key) })
            val entries = c.options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            when {
                c.type == "switch" -> {
                    map["type"] = JsonPrimitive("switch")
                    map["defaultValue"] = JsonPrimitive(false)
                }
                c.type == "list" && entries.isNotEmpty() -> {
                    map["type"] = JsonPrimitive("list")
                    map["options"] = JsonArray(entries.map(::option))
                    map["defaultValue"] = JsonPrimitive(entries.first())
                }
                else -> {
                    map["type"] = JsonPrimitive("text")
                    map["defaultValue"] = JsonPrimitive("")
                }
            }
            out += JsonObject(map)
        }
        return JsonArray(out).toString()
    }

    val QUALITIES = listOf("Best", "1080p", "720p", "480p", "360p")

    /** Generator refuses `{{name}}` in filled code: a typed `{{` can't make one */
    fun safe(value: String): String = value.replace("{{", "{ {")
}

/**
 * The state of "Lists and options" for one Build by tapping session. Plain (no screen scope): page loads come from
 * the WebView client on the main thread, options from the picker's bridge thread; every change goes through
 * [MutableStateFlow.update], so both are safe.
 */
internal class BuilderExtrasModel {

    private val mutable = MutableStateFlow(BuilderExtras())
    val state: StateFlow<BuilderExtras> = mutable.asStateFlow()

    /** Filter offers the owner dismissed: not offered again */
    private val dismissed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** Set on the main thread (the pane), read there and on the picker's bridge thread */
    @Volatile
    private var site: String? = null

    /**
     * r102: pushes a script into the builder's WebView (BuilderScreenModel sets it). "Learn this list" loads the list
     * page with it (`location.assign`) and presses entries (`window.__labLearn.press`). Called on the main thread.
     */
    @Volatile
    var runInPage: ((String) -> Unit)? = null

    /** r102: the learning runs; [dispose] cancels them */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var learnJob: Job? = null

    @Volatile
    private var stopLearning = false

    /** r102: every address the WebView reports ([onPageUrl]), numbered, so a run can wait for the next one */
    private data class SeenUrl(val tick: Long, val url: String)

    private val seen = MutableStateFlow(SeenUrl(0L, ""))

    /** r102 fix: only the pages that finished loading ([onPageFinished]), so a run presses on a loaded page */
    private val finished = MutableStateFlow(SeenUrl(0L, ""))

    /** r102 fix: learn.js's answer to the last press ([onLearnLog]): the press id and pressed|missing|error */
    private data class PressOutcome(val id: Long, val state: String)

    private val outcome = MutableStateFlow(PressOutcome(0L, ""))

    @Volatile
    private var pressId = 0L

    /**
     * Anything besides the defaults set: the source is written as JS (the JSON format has no tabs, filters, settings
     * or names, and its paths are joined to the site, so an address on another site can't be said either)
     */
    val used: Boolean
        get() {
            val s = mutable.value
            val foreignPath = listOfNotNull(s.popularPath, s.latestPath, s.searchPath).any { it.isNotBlank() && !it.startsWith("/") }
            return s.popularName.isNotBlank() || s.latestName.isNotBlank() || s.tabs.any { it.path.isNotBlank() } ||
                s.filters.any { it.key.isNotBlank() } || s.qualitySetting || s.serverSetting || s.hideWordsSetting ||
                s.custom.any { it.key.isNotBlank() } || foreignPath
        }

    /** The site the paths are made relative to (the pane passes the builder's); else each page's own origin */
    fun setSite(siteUrl: String?) {
        site = siteUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun siteFor(url: String): String = site ?: Detector.normalize(url)

    /**
     * A page finished loading (or an in-page navigation): kept as [BuilderExtras.lastUrl]; an address with the
     * search word records the search path; a change of query parameters on the same page offers them as filters.
     */
    fun onPageLoaded(url: String?) {
        val u = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        val logs = ArrayList<Pair<Char, String>>()
        mutable.update { s ->
            logs.clear()
            reduceLoaded(s, u, logs)
        }
        logs.forEach { (level, text) -> AppLog.add(level, "lab", text) }
    }

    private fun reduceLoaded(s: BuilderExtras, url: String, logs: MutableList<Pair<Char, String>>): BuilderExtras {
        // onPageFinished and doUpdateVisitedHistory both report the same address
        if (s.lastUrl == url) return s
        val site = siteFor(url)
        var n = s.copy(lastUrl = url)
        // Recorded once (the first results page): a later sort / filter change on the results must not be baked into
        // the search address; "Forget it and record again" (or clearing the field) records anew
        // r101: a path from the site's search box is replaced by a real recorded search
        val found = if (s.searchPath == null || s.searchFromForm) {
            BuilderExtrasLogic.searchPathOf(url, s.searchWord, site, s.pageRule)
        } else {
            null
        }
        if (found != null) {
            n = n.copy(
                searchPath = found.path,
                searchFrom = url,
                searchFromForm = false,
                rev = n.rev + 1,
                message = if (found.addedPage) BuilderExtrasLogic.NO_PAGE_MESSAGE else null,
            )
            logs += 'I' to "extras search recorded: ${found.path}"
        }
        val previous = s.lastUrl ?: return n
        val exclude = HashSet<String>().apply {
            n.filters.forEach {
                add(it.key)
                if (it.param.isNotBlank()) add(it.param)
            }
            n.pageRule?.takeIf { it.kind == "query" }?.let { add(it.name) }
            addAll(dismissed)
            BuilderExtrasLogic.searchParam(n.searchPath)?.let { param -> add(param) }
        }
        val changed = BuilderExtrasLogic.changedParams(previous, url, s.searchWord, exclude)
        if (changed.isEmpty()) return n
        val offers = n.offers.toMutableList()
        val path = BuilderExtrasLogic.pathOf(url, site, n.pageRule).path
        changed.forEach { (param, seen) ->
            val at = offers.indexOfFirst { it.param == param }
            if (at < 0) {
                offers += FilterOffer(param, seen, path)
                logs += 'D' to "extras filter offer $param (${seen.joinToString(", ")})"
            } else {
                offers[at] = offers[at].copy(seen = (offers[at].seen + seen).distinct().take(MAX_SEEN), path = path)
            }
        }
        return n.copy(offers = offers)
    }

    /** From picker.js `optionsFor(param)`: `{ param, options: [{ label, value }] }` */
    fun onPageOptions(json: String) {
        val obj = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        val param = obj?.str("param")
        if (obj == null || param.isNullOrBlank()) {
            AppLog.add('W', "lab", "extras options: unreadable answer from the page (${json.take(120)})")
            return
        }
        val options = obj.arr("options")?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val value = o.str("value") ?: return@mapNotNull null
            ExtrasOption(o.str("label")?.trim()?.ifEmpty { null } ?: value, value)
        }.orEmpty().take(MAX_OPTIONS)
        AppLog.add('D', "lab", "extras options for $param: ${options.size} from the page")
        var applied: ExtrasFilter? = null
        mutable.update { s ->
            applied = null
            val at = s.filters.indexOfFirst { it.mode != "path" && (it.param == param || it.key == param) }
            val filters = if (at >= 0 && options.isNotEmpty()) {
                val sort = s.filters[at].type == "sort"
                val all = if (sort) options.distinctBy { it.value } else BuilderExtrasLogic.withAny(options)
                val f = s.filters[at].copy(
                    type = if (sort) "sort" else "select",
                    options = all,
                    optionsText = BuilderExtrasLogic.formatOptions(all),
                )
                applied = f
                s.filters.toMutableList().also { it[at] = f }
            } else {
                s.filters
            }
            s.copy(
                optionsFor = s.optionsFor + (param to options),
                filters = filters,
                rev = s.rev + 1,
                message = if (options.isEmpty()) {
                    "The page has no dropdown or links for \"$param\": the options are the values seen so far"
                } else {
                    s.message
                },
            )
        }
        applied?.let { AppLog.add('I', "lab", "extras filter ${it.key} (${it.type}, ${it.options.size} options)") }
    }

    /**
     * The template placeholders: the paths only when set (they override Derive's guesses), the tab names, and the
     * lists as JSON text ("[]" when empty)
     */
    fun templateValues(site: String, codeSearchPath: String? = null): Map<String, String> {
        val s = mutable.value
        val out = LinkedHashMap<String, String>()
        // r104 fix: the search page the filters are compared with: the recorded one, else the edited source's own
        // (no search known: link-made filters stay parameter filters)
        val searchPath = s.searchPath?.trim()?.ifEmpty { null } ?: codeSearchPath?.trim()?.ifEmpty { null }
        s.popularPath?.trim()?.takeIf { it.isNotEmpty() }?.let { out["popularPath"] = BuilderExtrasLogic.forSite(it, site) }
        s.latestPath?.trim()?.takeIf { it.isNotEmpty() }?.let { out["latestPath"] = BuilderExtrasLogic.forSite(it, site) }
        s.searchPath?.trim()?.takeIf { it.isNotEmpty() }?.let { out["searchPath"] = BuilderExtrasLogic.forSite(it, site) }
        out["tabPopularName"] = s.popularName.trim()
        out["tabLatestName"] = s.latestName.trim()
        out["tabsList"] = BuilderExtrasLogic.tabsJson(s.tabs, site)
        out["filtersList"] = BuilderExtrasLogic.filtersJson(s.filters, searchPath)
        // r104: never left out silently
        s.filters.forEachIndexed { i, f ->
            if (BuilderExtrasLogic.filterProblem(f) != null) {
                AppLog.add('W', "lab", "extras filter ${i + 1} \"${f.name}\" left out: no address parameter or path yet (Learn addresses or type it)")
            } else if (BuilderExtrasLogic.usesLinkPage(f, searchPath)) {
                AppLog.add('I', "lab", "extras filter ${i + 1} \"${f.name}\" opens its own page ${f.linkPath} (not the search page)")
            }
            if (f.noLinkAddress) {
                AppLog.add('W', "lab", "extras filter ${i + 1} \"${f.name}\" was made from links before r104: its own page is unknown (?${f.param}= on the search page)")
            }
        }
        out["settingsList"] = BuilderExtrasLogic.settingsJson(s)
        AppLog.add(
            'D',
            "lab",
            "extras written: search ${out["searchPath"] ?: "(Derive's)"}, ${s.tabs.size} tab(s), ${s.filters.size} filter(s), " +
                "settings ${out["settingsList"]?.length ?: 0} chars",
        )
        return out.mapValues { BuilderExtrasLogic.safe(it.value) }
    }

    // ---------- Search ----------

    fun setSearchWord(word: String) = mutable.update { it.copy(searchWord = word) }

    fun setSearchPath(path: String) = mutable.update { it.copy(searchPath = path.ifBlank { null }, searchFromForm = false) }

    /** Forget the recorded search (the next address with the word records again) */
    fun clearSearch() = mutable.update {
        it.copy(searchPath = null, searchFrom = null, searchFromForm = false, rev = it.rev + 1)
    }

    /**
     * r101, from picker.js (every page load and `readForm()`): the site's search box,
     * `{ action, method, field, hidden: { name: value } }`. A GET form gives the search path when none was recorded
     * yet (a recorded search, or one typed, wins); the page rule applies.
     */
    fun onSearchForm(json: String) {
        val obj = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        val action = obj?.str("action")?.trim().orEmpty()
        val field = obj?.str("field")?.trim().orEmpty()
        if (obj == null || field.isEmpty()) {
            AppLog.add('W', "lab", "extras search form: unreadable answer from the page (${json.take(120)})")
            return
        }
        val method = obj.str("method")?.trim()?.lowercase().orEmpty().ifEmpty { "get" }
        if (method != "get") {
            AppLog.add('D', "lab", "extras search form uses $method: not usable as an address")
            return
        }
        val hidden = obj.obj("hidden")?.mapNotNull { (name, value) ->
            val v = (value as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (name.isBlank() || name == field) null else name to v
        }.orEmpty()
        val current = mutable.value
        if (current.searchPath != null && !current.searchFromForm) return
        val url = action.toHttpUrlOrNull() ?: current.lastUrl?.toHttpUrlOrNull()?.resolve(action)
        if (url == null) {
            AppLog.add('W', "lab", "extras search form: no usable action ($action)")
            return
        }
        val path = BuilderExtrasLogic.searchPathOfForm(url, field, hidden, siteFor(url.toString()), current.pageRule)
        var set = false
        mutable.update { s ->
            set = false
            if (s.searchPath != null && (!s.searchFromForm || s.searchPath == path)) return@update s
            set = true
            s.copy(
                searchPath = path,
                searchFrom = "the site's search box",
                searchFromForm = true,
                rev = s.rev + 1,
                message = "Found the site's search box ✓",
            )
        }
        if (set) AppLog.add('I', "lab", "extras search from the site's form: $path")
    }

    // ---------- Pages (r101) ----------

    /**
     * r101, from the Next pick (label "next"): the link's page number pattern becomes the page rule for lists, tabs,
     * search and filters; paths written with the `page={page}` guess are rewritten to it
     */
    fun setNextHref(href: String, pageUrl: String) {
        val rule = BuilderExtrasLogic.pageRuleOf(href, pageUrl)
        if (rule == null) {
            AppLog.add('W', "lab", "extras page rule: the Next link $href on $pageUrl has no page number the Lab can read")
            mutable.update {
                it.copy(message = "The Next link ($href) has no page number the Lab can read: lists keep the ?page=2 guess")
            }
            return
        }
        mutable.update { s ->
            fun fix(path: String?) = BuilderExtrasLogic.reapplyRule(path, rule)
            s.copy(
                pageRule = rule,
                popularPath = fix(s.popularPath),
                latestPath = fix(s.latestPath),
                searchPath = fix(s.searchPath),
                tabs = s.tabs.map { it.copy(path = fix(it.path).orEmpty()) },
                filters = s.filters.map { f -> if (f.mode == "path") f.copy(path = fix(f.path).orEmpty()) else f },
                offers = s.offers.map { o -> o.copy(path = fix(o.path).orEmpty()) },
                message = if (s.message == BuilderExtrasLogic.NO_PAGE_MESSAGE) null else s.message,
                rev = s.rev + 1,
            )
        }
        AppLog.add('I', "lab", "extras page rule from the Next link: ${rule.shown} ($href)")
    }

    // ---------- Filters from the site (r101) ----------

    /** "Pick a list on the site" (on) and its Cancel (off); BuilderScreenModel passes it to the page */
    fun setGroupMode(on: Boolean) {
        // KMK (r109): turned off (Cancel, a step change, a label armed) ends a card's "Pick the site's Sort menu" too
        mutable.update { it.copy(groupMode = on, dirPick = if (on) it.dirPick else null) }
        AppLog.add('D', "lab", "extras group mode ${if (on) "on" else "off"}")
    }

    /**
     * r101, from picker.js group mode:
     * `{ pageUrl, tapped: { text, href }, likeSelector, count, items: [{ text, href, value }], isSelect }`
     * r102: also `opener` (css path of the list's menu button, "" when none), `tappedOpener` (the tapped element is a
     * menu's own button) and items' `path` when the page sends them (all optional). Taps on a menu's
     * buttons show the menu hint instead; entries without links keep their texts; the list is kept for
     * "Learn this list".
     */
    fun onGroupPicked(json: String) {
        val obj = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        mutable.update { it.copy(groupMode = false) }
        if (obj == null) {
            AppLog.add('W', "lab", "extras group: unreadable answer from the page (${json.take(120)})")
            return
        }
        val pageUrl = obj.str("pageUrl")?.trim().orEmpty().ifEmpty { mutable.value.lastUrl.orEmpty() }
        val tapped = obj.obj("tapped")
        val entries = obj.arr("items")?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            BuilderExtrasLogic.GroupEntry(o.str("text").orEmpty(), o.str("href"), o.str("value"))
        }.orEmpty().take(BuilderExtrasLogic.MAX_GROUP_OPTIONS)
        AppLog.add(
            'D',
            "lab",
            "extras group picked: ${entries.size} entries like ${obj.str("likeSelector").orEmpty()} " +
                "(tapped \"${tapped?.str("text").orEmpty().take(40)}\"${if (obj.bool("isSelect") == true) ", a dropdown" else ""})",
        )
        val isSelect = obj.bool("isSelect") == true
        val tappedText = tapped?.str("text").orEmpty()
        // r102: "Type: All ▾ / Year: All ▾ / Sort: Hot ▾" are the menus' buttons, not one list's entries
        val popup = obj.bool("tappedOpener") == true || tapped?.bool("popup") == true
        if (!isSelect && BuilderExtrasLogic.menuButtons(entries, tappedText, popup)) {
            mutable.update { it.copy(menuHint = true, menuHintText = null, message = null, rev = it.rev + 1) }
            AppLog.add(
                'I',
                "lab",
                "extras group: \"${tappedText.take(40)}\" is a menu's button (${entries.size} like it" +
                    (if (popup) ", opens a popup" else "") + "): no filter, hint shown",
            )
            return
        }
        val s = mutable.value
        val heading = BuilderExtrasLogic.cleanHeading(obj.str("heading"))
        val derived = BuilderExtrasLogic.deriveGroup(
            entries = entries,
            tappedText = tappedText,
            tappedHref = tapped?.str("href"),
            pageUrl = pageUrl,
            rule = s.pageRule,
            heading = heading,
            offers = s.offers,
            // r101: a <select name="sort"> sets that parameter (picker.js sends its name)
            knownParam = if (isSelect) obj.str("name") else null,
        )
        applyDerived(derived, "the tapped list")
        // r102: kept for "Learn this list" (not a <select>: its options are read, never pressed)
        val paths = obj.arr("items")?.map { element -> (element as? JsonObject)?.str("path")?.takeIf { it.isNotBlank() } }.orEmpty()
        val learnEntries = entries.mapIndexedNotNull { at, e ->
            val text = e.text.trim()
            if (text.isEmpty()) null else LearnEntry(text, paths.getOrNull(at), e.href?.trim()?.takeIf { it.isNotEmpty() })
        }.distinctBy { it.text }
        // r105: a <select> without a name (the site's script reads it) is learned like a dropdown of buttons
        val learnable = !isSelect || obj.str("name").isNullOrBlank()
        val group = if (learnable && learnEntries.size >= 2 && pageUrl.startsWith("http")) {
            LearnGroup(
                pageUrl = pageUrl.substringBefore('#'),
                opener = obj.str("opener")?.trim()?.takeIf { it.isNotEmpty() },
                likeSelector = obj.str("likeSelector").orEmpty(),
                name = derived.filter?.name?.takeIf { it.isNotBlank() } ?: heading ?: "List",
                entries = learnEntries.take(BuilderExtrasLogic.MAX_GROUP_OPTIONS),
                hasLinks = learnEntries.any { e -> e.href?.let { !it.startsWith("javascript:", ignoreCase = true) } == true },
            )
        } else {
            null
        }
        mutable.update { st ->
            if (st.learn?.running == true) st.copy(menuHint = false) else st.copy(learnGroup = group, learn = null, menuHint = false)
        }
        if (group != null) {
            AppLog.add(
                'D',
                "lab",
                "extras learnable list \"${group.name}\": ${group.entries.size} entries" +
                    (if (group.hasLinks) ", with links" else ", no links") + ", menu button ${group.opener ?: "(none sent)"}",
            )
        }
    }

    /** r102: the menu hint's OK */
    fun dismissMenuHint() = mutable.update { it.copy(menuHint = false, menuHintText = null) }

    /**
     * r101: what [BuilderListReader] read from [url] becomes a filter, derived like a group pick. r103: [form] "select"
     * (a dropdown) or "autocomplete" (a text box with the entries as suggestions, up to
     * [BuilderExtrasLogic.MAX_SUGGESTIONS]); [name] the menu group's name (else the address's last word)
     */
    fun onListRead(url: String, result: BuilderListReader.Result, form: String = "select", name: String? = null, menuId: Int? = null) {
        val suggest = form == "autocomplete"
        val entries = result.items.map { BuilderExtrasLogic.GroupEntry(it.text, it.href, null) }
        val derived = BuilderExtrasLogic.deriveGroup(
            entries = entries,
            tappedText = "",
            tappedHref = null,
            pageUrl = url,
            rule = mutable.value.pageRule,
            heading = name?.takeIf { it.isNotBlank() } ?: BuilderExtrasLogic.nameFromUrl(url),
            maxOptions = if (suggest) BuilderExtrasLogic.MAX_SUGGESTIONS else BuilderExtrasLogic.MAX_GROUP_OPTIONS,
        )
        // r105: a directory (Genres, Makers…) opens one list per entry: a page filter; it sits in the group of the
        // menu it was opened from (Collections ▸ Makers → "Collections")
        val group = menuGroupOf(url)
        val made = derived.filter
            ?.let { if (suggest) BuilderExtrasLogic.asAutocomplete(it) else it }
            ?.let { if (group.isNotEmpty() && it.group.isEmpty()) it.copy(group = group) else it }
        AppLog.add(
            'I',
            "lab",
            "extras full list of $url as ${if (suggest) "a text box with suggestions" else "a dropdown"}" +
                (made?.let { " (${it.role.ifEmpty { "role as before" }} ${it.path.ifEmpty { it.param }}${if (group.isNotEmpty()) ", group $group" else ""})" } ?: ""),
        )
        val added = applyDerived(derived.copy(filter = made, listUrl = null), "the whole list (${result.pages} pages)", menuId = menuId)
        // r106: the card says it made its filter (unless it asks Replace / Keep both)
        if (menuId != null) afterMade(added, menuId, "")
    }

    /**
     * r105: the menu heading a directory [url] was opened from: the card whose entry links to it (Collections, holding
     * Makers), or the parent heading of the card that is the directory itself; "" when no menu card has it
     */
    private fun menuGroupOf(url: String): String {
        val target = url.trim().substringBefore('#').trimEnd('/')
        fun same(href: String?, base: String): Boolean {
            val h = href?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val abs = base.toHttpUrlOrNull()?.resolve(h)?.toString() ?: h
            return abs.substringBefore('#').trimEnd('/') == target
        }
        val groups = mutable.value.menuGroups
        groups.firstOrNull { g -> same(g.href, g.pageUrl) }?.let { g ->
            return g.headingPath.dropLast(1).lastOrNull()?.let(BuilderExtrasLogic::cleanHeading).orEmpty()
        }
        groups.firstOrNull { g -> g.items.any { same(it.href, g.pageUrl) } }?.let { g -> return g.name }
        return ""
    }

    // ---------- Pick a menu on the site (r103) ----------

    @Volatile
    private var nextMenuId = 1

    /**
     * r103, from picker.js `menuPick` (LabPicker.menu / console "LABMENU:"):
     * `{ pageUrl, tapped: { text, href }, groups: [{ heading, headingPath, opener, href, index, items, subgroups }] }`.
     * Every group with entries (or an index link) becomes a card; nothing is made until the owner says so.
     */
    fun onMenuPicked(json: String) {
        val obj = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        // KMK (r109): a scanned directory card asked for this pick ("Pick the site's Sort menu"): it learns it for itself
        val dirTarget = mutable.value.dirPick
        val dirSort = mutable.value.dirPickSort
        mutable.update { it.copy(groupMode = false, dirPick = null) }
        if (obj == null) {
            AppLog.add('W', "lab", "extras menu: unreadable answer from the page (${json.take(120)})")
            return
        }
        val pageUrl = obj.str("pageUrl")?.trim().orEmpty().ifEmpty { mutable.value.lastUrl.orEmpty() }.substringBefore('#')
        if (dirTarget != null) {
            onDirMenuPicked(dirTarget, dirSort, obj, pageUrl)
            return
        }
        val raw = obj.arr("groups")?.mapNotNull { it as? JsonObject }.orEmpty()
        val cards = BuilderExtrasLogic.menuGroupsOf(raw, pageUrl, nextMenuId)
        nextMenuId += cards.size + 1
        val tapped = obj.obj("tapped")?.str("text").orEmpty().take(40)
        // r103 fix: a dropdown whose menu isn't in the page until it opens: no groups, the hint card says what to do
        val hint = obj.str("hint")?.trim()?.takeIf { it.isNotEmpty() && cards.isEmpty() }
        if (hint != null) {
            mutable.update { s -> s.copy(menuGroups = emptyList(), menuHint = true, menuHintText = hint, message = null, rev = s.rev + 1) }
            AppLog.add('I', "lab", "extras menu picked at \"$tapped\": its menu isn't in the page until it opens (hint shown)")
            return
        }
        mutable.update { s ->
            s.copy(
                menuGroups = cards,
                // KMK (r109) fix: a filter saved before r109 (its default "Site default") named after its menu's shown value
                filters = s.filters.map { f ->
                    cards.firstNotNullOfOrNull { g ->
                        if (g.name.isNotBlank() && g.name.equals(f.name.trim(), ignoreCase = true)) BuilderExtrasLogic.nameDefault(f, g.current) else null
                    }?.also { r -> AppLog.add('I', "lab", "extras filter \"${f.name}\": its default named \"${r.siteDefault}\" from the menu") } ?: f
                },
                menuHint = false,
                menuHintText = null,
                message = if (cards.isEmpty()) {
                    "No menu found at \"$tapped\": tap a menu's heading (Trending, Types…), a dropdown's button or one entry of a list."
                } else {
                    null
                },
                rev = s.rev + 1,
                // r106: the first new card scrolls into view
                scrollTarget = cards.firstOrNull()?.let { "menu:${it.id}" } ?: s.scrollTarget,
                scrollTick = if (cards.isEmpty()) s.scrollTick else s.scrollTick + 1,
                scrollReveal = if (cards.isEmpty()) s.scrollReveal else true,
            )
        }
        probeCards(cards)
        AppLog.add(
            'I',
            "lab",
            "extras menu picked at \"$tapped\": ${cards.size} group(s): " +
                cards.joinToString(" · ") { g -> "${g.headingPath.joinToString(" › ")} (${g.items.size}${if (g.href != null) ", list page" else ""})" },
        )
    }

    /** r103: a menu group card's "Make filter": a filter of that group's entries only (r101 derivation) */
    fun makeMenuFilter(id: Int, onConflict: Boolean? = null) {
        val s = mutable.value
        val g = s.menuGroups.firstOrNull { it.id == id } ?: return
        val entries = g.items.map { BuilderExtrasLogic.GroupEntry(it.text, it.href, it.value) }
        val derived = BuilderExtrasLogic.deriveGroup(
            entries = entries,
            tappedText = "",
            tappedHref = null,
            pageUrl = g.pageUrl,
            rule = s.pageRule,
            heading = g.name.ifBlank { null },
            offers = s.offers,
            knownParam = g.param,
        )
        // r105: a sub-menu's filter sits in its parent menu's group (Collections ▸ Genres)
        val parent = g.headingPath.dropLast(1).lastOrNull()?.let(BuilderExtrasLogic::cleanHeading).orEmpty()
        val grouped = derived.filter?.let { if (parent.isNotEmpty()) it.copy(group = parent) else it }
            // KMK (r109) fix: a link menu's default is named after the value its button showed
            ?.let { f -> BuilderExtrasLogic.nameDefault(f, g.current.ifEmpty { g.items.firstOrNull { it.current }?.text.orEmpty() }) ?: f }
        val made = applyDerived(
            derived.copy(filter = grouped, listUrl = null),
            "the menu group \"${g.headingPath.joinToString(" › ")}\"",
            onConflict = onConflict,
            menuId = id,
        )
        // r106: waiting for Replace / Keep both: the card asks, nothing is marked yet
        val waiting = made == null && mutable.value.pendings.containsKey(id)
        if (!waiting) {
            mutable.update { st ->
                st.copy(menuGroups = st.menuGroups.map { if (it.id == id) it.copy(made = made?.key?.ifEmpty { "?" } ?: "") else it })
            }
        }
        requestScroll("menu:$id", reveal = false)
        outlineGroup(g)
    }

    /**
     * r106: a links card's "Add as tabs instead": each entry's page becomes a tab (Browse's buttons at the top), with
     * the page rule; pages already a tab are skipped
     */
    fun addMenuAsTabs(id: Int) {
        val s = mutable.value
        val g = s.menuGroups.firstOrNull { it.id == id } ?: return
        val base = g.pageUrl.toHttpUrlOrNull()
        val added = ArrayList<ExtrasTab>()
        g.items.forEach { item ->
            val href = item.href?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("javascript:", ignoreCase = true) } ?: return@forEach
            val abs = base?.resolve(href)?.toString() ?: href
            if (!abs.startsWith("http")) return@forEach
            val path = BuilderExtrasLogic.pathOf(abs, siteFor(abs), s.pageRule).path
            if (s.tabs.any { it.path == path } || added.any { it.path == path }) return@forEach
            added += ExtrasTab(BuilderExtrasLogic.cleanLabel(item.text).ifEmpty { nameFromPath(path) }, path)
        }
        if (added.isEmpty()) {
            mutable.update { it.copy(message = "None of \"${g.name.ifBlank { "this menu" }}\"'s entries is a new page to add as a tab") }
            return
        }
        mutable.update { st ->
            st.copy(
                tabs = st.tabs + added,
                menuGroups = st.menuGroups.map { if (it.id == id) it.copy(made = "tabs") else it },
                message = "Added ${added.size} tab${if (added.size == 1) "" else "s"}: ${added.joinToString(", ") { it.name }}",
                rev = st.rev + 1,
            )
        }
        AppLog.add('I', "lab", "extras menu \"${g.name}\" as tabs: ${added.joinToString(", ") { "${it.name} = ${it.path}" }}")
    }

    /** r103: "Learn addresses": the group's entries become the list "Learn this list" presses (its card opens) */
    fun learnMenuGroup(id: Int) {
        val g = mutable.value.menuGroups.firstOrNull { it.id == id } ?: return
        if (learnJob?.isActive == true) return
        val entries = g.items.mapNotNull { e ->
            val text = e.text.trim()
            if (text.isEmpty()) null else LearnEntry(text, e.path, e.href?.trim()?.takeIf { it.isNotEmpty() })
        }.distinctBy { it.text }
        if (entries.size < 2 || !g.pageUrl.startsWith("http")) return
        val group = LearnGroup(
            pageUrl = g.pageUrl,
            opener = g.opener,
            likeSelector = "",
            name = g.name.ifBlank { "List" },
            entries = entries,
            hasLinks = entries.any { e -> e.href?.let { !it.startsWith("javascript:", ignoreCase = true) } == true },
            menuId = id,
            // KMK (r109): the site's current entry names the default when every press changes the address
            current = g.current,
            openerLabel = g.name,
        )
        mutable.update {
            it.copy(learnGroup = group, learn = null, rev = it.rev + 1, scrollTarget = "menu:$id", scrollTick = it.scrollTick + 1, scrollReveal = true)
        }
        AppLog.add('D', "lab", "extras learn the menu group \"${g.name.ifBlank { "List" }}\": ${entries.size} entries, menu button ${g.opener ?: "(none)"}")
        outlineGroup(g)
    }

    /**
     * r106: the card's "Learn the codes" with the pause set on the card: the card's entries are pressed one at a time
     * (the progress rows show inside the card), the card scrolled into view
     */
    fun startMenuLearn(id: Int, pauseMinMs: Long, pauseMaxMs: Long) {
        if (learnJob?.isActive == true) return
        setReadPause(pauseMinMs, pauseMaxMs)
        if (mutable.value.learnGroup?.menuId != id) learnMenuGroup(id)
        if (mutable.value.learnGroup?.menuId != id) return
        startLearn(pauseMinMs, pauseMaxMs)
        requestScroll("menu:$id")
    }

    /**
     * r106: asks the pane to scroll [target] ("menu:<id>", "pending") into view. r106 fix: [reveal] = the panel switches
     * to Lists & filters (and a phone's sheet opens) for it: new cards and learning only, not every read or question
     */
    fun requestScroll(target: String, reveal: Boolean = true) =
        mutable.update { it.copy(scrollTarget = target, scrollTick = it.scrollTick + 1, scrollReveal = reveal) }

    /** r106 fix: the pane opened Filters for the request at [tick] (a tab switch back doesn't open it again) */
    fun scrollOpened(tick: Int) = mutable.update { if (it.scrollOpened >= tick) it else it.copy(scrollOpened = tick) }

    /** r106 fix: the card asked for at [tick] was brought into view (coming back to the pane doesn't jump to it) */
    fun scrollDone(tick: Int) = mutable.update { if (it.scrollDone >= tick) it else it.copy(scrollDone = tick) }

    /** r106 fix: the pane's open section ("" = none), kept across tab switches */
    fun setOpenSection(section: String) = mutable.update { if (it.openSection == section) it else it.copy(openSection = section) }

    // ---------- r106: directories on their cards (probe, Read all pages) ----------

    /** r106: the pause between pages / presses the owner set last (a card's slider), null = the app's default */
    @Volatile
    private var pauseRange: Pair<Long, Long>? = null

    fun setReadPause(minMs: Long, maxMs: Long) {
        pauseRange = minOf(minMs, maxMs).coerceAtLeast(0L) to maxOf(minMs, maxMs).coerceAtLeast(0L)
    }

    private fun readPause(): Pair<Long, Long> = pauseRange ?: runCatching {
        val r = BuilderListReader.defaultPause()
        (r.start * 1000f).toLong() to (r.endInclusive * 1000f).toLong()
    }.getOrDefault(1_000L to 3_000L)

    /** r106: the pause the cards show before anything was set (seconds) */
    fun pauseSeconds(): Pair<Float, Float> = readPause().let { (a, b) -> a / 1000f to b / 1000f }

    private val readStops = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /**
     * r106 fix: the heading card whose "Read all N" is running, null = none. Only then does a sub-menu's Stop stop
     * its parent too (a single card's Stop or "Not this" never stops the siblings)
     */
    @Volatile
    private var readAllParent: Int? = null

    @Volatile
    private var readJob: Job? = null

    /**
     * r106: the directory cards of a new menu pick read their first page (one after another): names and pages. r107: the
     * first page is scanned part by part ([BuilderListReader.scan]): the card shows its parts, sorts and menus
     */
    private fun probeCards(cards: List<MenuGroup>) {
        val todo = cards.filter { it.href != null && (it.kind == "directory" || it.items.isEmpty()) }
        if (todo.isEmpty()) return
        mutable.update { s -> s.copy(menuGroups = s.menuGroups.map { g -> if (todo.any { it.id == g.id }) g.copy(probe = DirProbe("reading")) else g }) }
        scope.launch {
            // KMK (r119): a few directories at a time (one at a time while the site is asked slowly, its pace kept by
            // the client); a bot check stops the ones not read yet (shown on their cards, never solved)
            var i = 0
            var blocked = false
            while (i < todo.size) {
                val width = DirPager.paceFor(todo[i].href.orEmpty()).inFlight
                val set = todo.subList(i, minOf(todo.size, i + width))
                i += set.size
                if (blocked) {
                    set.forEach { g -> showProbe(g, DirProbe("failed", note = "not read: " + DirPager.BOT_CHECK_TEXT), null) }
                    continue
                }
                val probes = coroutineScope {
                    set.mapIndexed { k, g ->
                        async {
                            if (k > 0) delay(200L * k)
                            probeOne(g)
                        }
                    }.awaitAll()
                }
                if (probes.any { it.note == DirPager.BOT_CHECK_TEXT }) blocked = true
            }
        }
    }

    /** KMK (r119): one directory card's first page scanned, shown on its card with a remembered read when there is one */
    private suspend fun probeOne(g: MenuGroup): DirProbe {
        val url = g.href ?: return DirProbe("failed", note = "no address")
        val probe = try {
            val scan = BuilderListReader.scan(url)
            DirProbe("done", scan.entries.size, scan.pages, scan.hasNext, scan.note, scan = scan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DirProbe("failed", note = if (DirPager.isBotCheckError(e)) DirPager.BOT_CHECK_TEXT else e.message ?: e.javaClass.simpleName)
        }
        AppLog.add('D', "lab", "extras directory \"${g.name}\" $url: ${probe.state}, ${probe.entries} names, pages ${probe.pages}${if (probe.hasNext) ", next page" else ""} ${probe.note}".trim())
        // KMK (r119): a read of this directory remembered in the last 7 days shows at once ("Read 2 h ago · Refresh")
        val remembered = if (probe.state != "done") {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val scan = probe.scan?.takeIf { it.ok }
                    if (scan != null) {
                        val (from, to) = rangeOf(g, scan)
                        DirCache.getDir(scan, from, to)?.let { (r, at) ->
                            GroupRead(false, r.pages, r.entries.size, to - from + 1, r.reason, first = from, cachedAt = at)
                        }
                    } else {
                        DirCache.getList(url, BuilderExtrasLogic.READ_PAGES)?.let { (r, at) ->
                            GroupRead(false, r.pages, r.items.size, BuilderExtrasLogic.READ_PAGES, r.reason, cachedAt = at)
                        }
                    }
                }.getOrNull()
            }
        }
        showProbe(g, probe, remembered)
        return probe
    }

    private fun showProbe(g: MenuGroup, probe: DirProbe, remembered: GroupRead?) = mutable.update { s ->
        s.copy(
            menuGroups = s.menuGroups.map {
                if (it.id != g.id) it else it.copy(probe = probe, read = if (it.read == null && remembered != null) remembered else it.read)
            },
        )
    }

    /** KMK (r119): the page range a scanned directory's "Read pages now" reads (the card's choice, else the defaults) */
    private fun rangeOf(g: MenuGroup, scan: BuilderListReader.DirScan): Pair<Int, Int> {
        val choice = g.choice ?: BuilderExtrasLogic.defaultChoice(g.name, scan)
        val from = if (scan.pageTemplate == null) 1 else choice.from.coerceIn(1, scan.pages.coerceAtLeast(1))
        val to = choice.to.coerceIn(from, maxOf(from, scan.pages))
        return from to to
    }

    /**
     * r106: a card's "Read all pages" (or "Make filter" of a one-page directory, [maxPages] 1): its own link's list is
     * read page after page with the pause the owner set, the progress on the card; the names become a filter (a text
     * box with suggestions when long) in the card's parent group
     */
    fun readMenuGroup(id: Int, maxPages: Int = BuilderExtrasLogic.READ_PAGES, refresh: Boolean = false) {
        if (readJob?.isActive == true) return
        val g = mutable.value.menuGroups.firstOrNull { it.id == id } ?: return
        if (g.href == null) return
        readStops.remove(id)
        // r106 fix: a parent flag left by an earlier Stop / Read all doesn't stop this card before its first page
        g.parent?.let { readStops.remove(it) }
        // KMK (r119): [refresh] = read the site again instead of the remembered list
        readJob = scope.launch { readOne(g, maxPages, refresh = refresh) }
    }

    /** r106: a heading card's "Read all N": its sub-menus' directories one after another, with the same pause */
    fun readAllUnder(parentId: Int, maxPages: Int = BuilderExtrasLogic.READ_PAGES) {
        if (readJob?.isActive == true) return
        val waiting = mutable.value.pendings
        // r106 fix: a sub-menu still asking Replace / Keep both keeps its question (it isn't read again)
        val kids = mutable.value.menuGroups.filter { it.parent == parentId && it.href != null && it.made.isNullOrEmpty() && !waiting.containsKey(it.id) }
        if (kids.isEmpty()) return
        kids.forEach { readStops.remove(it.id) }
        readStops.remove(parentId)
        readAllParent = parentId
        AppLog.add('I', "lab", "extras read all ${kids.size} lists under \"${mutable.value.menuGroups.firstOrNull { it.id == parentId }?.name.orEmpty()}\"")
        readJob = scope.launch {
            try {
                for (k in kids) {
                    if (readStops[parentId] == true) break
                    // r106 fix: a card closed meanwhile is skipped; one that asks leaves its question open and the
                    // run goes on to the next
                    val card = mutable.value.menuGroups.firstOrNull { it.id == k.id } ?: continue
                    if (!card.made.isNullOrEmpty() || readStops[k.id] == true) continue
                    // r107: a scanned directory is made with the choices on its card (Live ones at once)
                    if (card.probe?.scan?.ok == true) collectOne(card, maxPages) else readOne(card, maxPages)
                }
            } finally {
                if (readAllParent == parentId) readAllParent = null
                readStops.remove(parentId)
            }
        }
    }

    /**
     * r106: the card's Stop: the read ends after the current page and keeps what it read. r106 fix: it stops the
     * parent's "Read all N" only while that run is going (else the siblings stay readable)
     */
    fun stopRead(id: Int) {
        readStops[id] = true
        val all = readAllParent
        // KMK (r112) fix: inside "Do all N" a directory's own Stop ends that directory only (the group's Stop ends the run)
        val kidOfDoAll = all != null && all == doAllParent && all != id
        if (all != null && !kidOfDoAll && (all == id || mutable.value.menuGroups.firstOrNull { it.id == id }?.parent == all)) readStops[all] = true
        AppLog.add('D', "lab", "extras read: stop asked on card $id")
    }

    // ---------- KMK (r112): "Do all N" on a heading card (mock BEasy) ----------

    /** KMK (r112): the group Stop of the running "Do all N" (checked between steps; Stop of its learning / read too) */
    @Volatile
    private var doAllStop = false

    /** KMK (r112) fix: the heading card whose "Do all N" is running, null = none ([stopRead] leaves its flag alone) */
    @Volatile
    private var doAllParent: Int? = null

    private fun showDoAll(parentId: Int, change: (DoAllRun?) -> DoAllRun?) = mutable.update { s ->
        s.copy(menuGroups = s.menuGroups.map { if (it.id == parentId) it.copy(doAll = change(it.doAll)) else it })
    }

    private fun doAllRow(parentId: Int, kid: Int, row: DoAllRow) = showDoAll(parentId) { it?.copy(rows = it.rows + (kid to row)) }

    /** KMK (r112): the sub-menus "Do all N" takes on: with their own link, not made yet, not asking Replace / Keep both */
    fun doAllKids(s: BuilderExtras, parentId: Int): List<MenuGroup> =
        s.menuGroups.filter { it.parent == parentId && it.href != null && it.made.isNullOrEmpty() && !s.pendings.containsKey(it.id) }

    /**
     * KMK (r112, mock BEasy): a heading card's "Do all N": its directories one after another, each scanned (its first
     * page, read when the card appeared), its parts ticked by the usual defaults (or as the owner set them on its card),
     * its sort and menus learned on the page (the r110 [runDirLearn]) and its collection made (live by the usual rule,
     * else its pages read with the pause set). One progress box and Stop on the group's card; a row per directory. Stop
     * keeps what finished; a menu cut short is never saved (r110 rule); the directory being done when Stop came is not made
     */
    fun doAllUnder(parentId: Int) {
        if (readJob?.isActive == true || learnJob?.isActive == true) {
            mutable.update { it.copy(message = "Something is being read or learned: wait for it to end (or stop it), then Do all again") }
            return
        }
        val st = mutable.value
        val parent = st.menuGroups.firstOrNull { it.id == parentId } ?: return
        val kids = doAllKids(st, parentId)
        if (kids.isEmpty()) return
        // KMK (r112) fix: a menu pick, picture tap or "Pick a list" waiting is cancelled before the run moves the page
        if (st.dirPick != null) cancelDirPick()
        if (st.picturePick != null) cancelPicturePick()
        if (mutable.value.groupMode) setGroupMode(false)
        doAllStop = false
        stopLearning = false
        kids.forEach { readStops.remove(it.id) }
        readStops.remove(parentId)
        readAllParent = parentId
        doAllParent = parentId
        // rows of directories done in an earlier run stay as they were. KMK (r112) fix: an earlier "stopped" row is
        // dropped (made by hand since: "ready"), so the progress can reach the end
        val earlier = parent.doAll?.rows.orEmpty().filterKeys { k -> kids.none { it.id == k } }.mapNotNull { (k, r) ->
            when {
                r.state != "stopped" -> k to r
                st.menuGroups.any { it.id == k && !it.made.isNullOrEmpty() } -> k to DoAllRow("ready")
                else -> null
            }
        }.toMap()
        val order = (parent.doAll?.kids.orEmpty().filter { k -> kids.none { it.id == k } && earlier.containsKey(k) } + kids.map { it.id })
        showDoAll(parentId) {
            DoAllRun(
                running = true,
                kids = order,
                rows = earlier + kids.associate { it.id to DoAllRow("waiting") },
                started = System.currentTimeMillis(),
            )
        }
        AppLog.add('I', "lab", "extras do all ${kids.size} under \"${parent.name}\": ${kids.joinToString(", ") { it.name }}")
        val job = scope.launch {
            try {
                runDoAll(parentId, kids.map { it.id })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.add('E', "lab", "extras do all failed: ${e.message ?: e.javaClass.simpleName}")
                showDoAll(parentId) { it?.copy(running = false, current = null, phase = "", note = "Stopped: ${e.message ?: "an error"}") }
            } finally {
                if (readAllParent == parentId) readAllParent = null
                if (doAllParent == parentId) doAllParent = null
                readStops.remove(parentId)
            }
        }
        // one job: nothing else reads or learns on the page meanwhile; leaving the builder cancels it (dispose)
        readJob = job
        learnJob = job
        requestScroll("menu:$parentId", reveal = false)
    }

    /** KMK (r112): the group card's Stop: the current step ends (a learning press, a page read), what finished stays */
    fun stopDoAll(parentId: Int) {
        doAllStop = true
        readStops[parentId] = true
        stopLearning = true
        AppLog.add('D', "lab", "extras do all: stop asked on card $parentId")
        scope.launch { runCatching { inPage("window.__labLearn&&window.__labLearn.banner&&window.__labLearn.banner(null);") } }
    }

    private suspend fun runDoAll(parentId: Int, kidIds: List<Int>) {
        val (low, high) = readPause()
        var stopped = false
        var made = 0
        var needs = 0
        fun current(kid: Int?, phase: String) = showDoAll(parentId) { it?.copy(current = kid, phase = phase) }
        try {
            for (kid in kidIds) {
                if (doAllStop) {
                    stopped = true
                    break
                }
                // the group's card closed: nothing more; a directory's card closed meanwhile is skipped
                if (mutable.value.menuGroups.none { it.id == parentId }) return
                var card = mutable.value.menuGroups.firstOrNull { it.id == kid } ?: continue
                if (!card.made.isNullOrEmpty()) {
                    doAllRow(parentId, kid, DoAllRow("ready"))
                    continue
                }
                doAllRow(parentId, kid, DoAllRow("running"))
                // 1. its first page, scanned when its card appeared (wait while that read is still going)
                current(kid, "scanning")
                var waited = 0L
                while (card.probe?.state == "reading" && !doAllStop && waited < PROBE_WAIT_MS) {
                    delay(WAIT_STEP_MS)
                    waited += WAIT_STEP_MS
                    card = mutable.value.menuGroups.firstOrNull { it.id == kid } ?: break
                }
                if (doAllStop) {
                    stopped = true
                    break
                }
                card = mutable.value.menuGroups.firstOrNull { it.id == kid } ?: continue
                val probe = card.probe
                val scan = probe?.scan?.takeIf { it.ok }
                if (scan == null) {
                    when {
                        probe?.state == "reading" -> {
                            doAllRow(parentId, kid, DoAllRow("needs", "its first page is still being read"))
                            needs++
                        }
                        probe == null || probe.state == "done" && probe.entries > 0 -> {
                            // r106: a list of names that couldn't be scanned part by part: its pages are read as before
                            current(kid, "reading")
                            readOne(card, BuilderExtrasLogic.READ_PAGES, scroll = false, inDoAll = true)
                            if (doAllStop && mutable.value.menuGroups.firstOrNull { it.id == kid }?.made.isNullOrEmpty()) {
                                stopped = true
                                break
                            }
                            val row = rowAfterMake(kid, emptyList())
                            if (row.state == "ready") made++ else needs++
                            doAllRow(parentId, kid, row)
                        }
                        else -> {
                            val why = if (probe?.state == "failed") "its first page couldn't be read (${probe?.note.orEmpty()})" else "no list of names found on it"
                            doAllRow(parentId, kid, DoAllRow("needs", why))
                            needs++
                        }
                    }
                    continue
                }
                // 2. its sort and menus learned on the page (codes kept with this directory only)
                val notes = ArrayList<String>()
                if (scan.custom.isNotEmpty()) {
                    if (runInPage == null) {
                        notes += "menus not learned: the page isn't ready"
                    } else {
                        current(kid, "learning")
                        stopLearning = false
                        try {
                            runDirLearn(kid, scan.url, scan.custom, low, high)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.add('E', "lab", "learn menus failed: ${e.message ?: e.javaClass.simpleName}")
                            showDirLearn(kid) { it?.copy(running = false, note = "Learning stopped: ${e.message ?: "an error"}") }
                        }
                        if (doAllStop) {
                            stopped = true
                            break
                        }
                        val learned = mutable.value.menuGroups.firstOrNull { it.id == kid }?.dirLearn
                        // its own Stop (on the directory's card) ends its learning only: the run goes on
                        if (stopLearning) notes += "menus not learned (stopped)"
                        learned?.menus.orEmpty().filter { it.state == "failed" }.forEach { m -> notes += "${m.label} can't be learned · pick it on the page" }
                        if (!stopLearning) learned?.menus.orEmpty().filter { it.state == "stopped" }.forEach { m -> notes += "${m.label} ${m.note}" }
                        stopLearning = false
                    }
                }
                // 3. its collection, with the parts ticked now (the owner may have changed them on its card meanwhile)
                if (doAllStop) {
                    stopped = true
                    break
                }
                card = mutable.value.menuGroups.firstOrNull { it.id == kid } ?: continue
                val now = card.probe?.scan?.takeIf { it.ok } ?: scan
                val choice = card.choice ?: BuilderExtrasLogic.defaultChoice(card.name, now)
                current(kid, if (choice.live && (now.pageTemplate != null || now.pages <= 1)) "making" else "reading")
                collectOne(card, BuilderExtrasLogic.READ_PAGES, scroll = false, inDoAll = true)
                if (doAllStop && mutable.value.menuGroups.firstOrNull { it.id == kid }?.made.isNullOrEmpty()) {
                    stopped = true
                    break
                }
                val row = rowAfterMake(kid, notes)
                if (row.state == "ready") made++ else needs++
                doAllRow(parentId, kid, row)
            }
        } finally {
            val byYou = stopped || doAllStop
            showDoAll(parentId) { d ->
                d?.copy(
                    running = false,
                    current = null,
                    phase = "",
                    rows = d.rows.mapValues { (_, r) ->
                        when {
                            r.state == "running" -> DoAllRow("stopped", "stopped · not made")
                            byYou && r.state == "waiting" -> DoAllRow("stopped", "not reached (stopped)")
                            else -> r
                        }
                    },
                    note = (if (byYou) "Stopped by you: what finished is kept. " else "Done: ") +
                        "$made made" + if (needs > 0) ", $needs need you" else "",
                )
            }
            AppLog.add('I', "lab", "extras do all under card $parentId: $made made, $needs need you" + if (byYou) " (stopped)" else "")
            doAllStop = false
            stopLearning = false
        }
    }

    /** KMK (r112): a directory's row once "Do all N" made (or tried to make) its collection, with the [notes] gathered */
    private fun rowAfterMake(kid: Int, notes: List<String>): DoAllRow {
        val s = mutable.value
        val card = s.menuGroups.firstOrNull { it.id == kid } ?: return DoAllRow("needs", "its card was closed")
        return when {
            s.pendings.containsKey(kid) -> DoAllRow("needs", "a filter with its name is there: Replace or Keep both on its card")
            card.made == "" -> DoAllRow("needs", "no filter could be made: its links don't share one address")
            card.made.isNullOrEmpty() -> DoAllRow("needs", card.read?.reason?.let { "nothing made ($it)" } ?: "nothing made")
            notes.isNotEmpty() -> DoAllRow("needs", notes.joinToString(" · "))
            else -> DoAllRow("ready")
        }
    }

    private suspend fun readOne(g: MenuGroup, maxPages: Int, scroll: Boolean = true, inDoAll: Boolean = false, refresh: Boolean = false) {
        val url = g.href ?: return
        val (low, high) = readPause()
        fun show(r: GroupRead) = mutable.update { s -> s.copy(menuGroups = s.menuGroups.map { if (it.id == g.id) it.copy(read = r) else it }) }
        // KMK (r119): the list read in the last 7 days is used at once (Refresh reads it again)
        val remembered = if (refresh) null else withContext(Dispatchers.IO) { runCatching { DirCache.getList(url, maxPages) }.getOrNull() }
        if (remembered != null) {
            val (r, at) = remembered
            show(GroupRead(running = false, page = r.pages, entries = r.items.size, maxPages = maxPages, reason = r.reason, cachedAt = at))
            AppLog.add('I', "lab", "extras read ${g.name}: ${r.items.size} names remembered (${DirCache.agoText(at)})")
            if (mutable.value.menuGroups.none { it.id == g.id }) return
            if (inDoAll && doAllStop) return
            onListRead(url, r, BuilderExtrasLogic.formFor(r.items.size), g.name.ifBlank { null }, menuId = g.id)
            return
        }
        show(GroupRead(running = true, page = 1, entries = 0, maxPages = maxPages))
        // r106 fix: scrolled to, but the panel doesn't switch tabs for every sub-menu read
        // KMK (r109): the card's progress box comes to the top of the panel once, when the read starts
        // KMK (r112): not inside "Do all N" (its one progress box is on the group's card)
        if (scroll) requestScroll("read:${g.id}", reveal = false)
        val result = try {
            BuilderListReader.read(
                url = url,
                maxPages = maxPages,
                pauseMinMs = low,
                pauseMaxMs = high,
                onProgress = { p ->
                    // KMK (r119): the pages in flight ("pages 4–6 of 12"), or the next one during a pause
                    show(
                        GroupRead(
                            running = true,
                            page = (if (p.from > 0) p.from else p.done + 1).coerceAtMost(maxPages),
                            entries = p.entries,
                            maxPages = maxPages,
                            waitUntil = if (p.pauseMs > 0) System.currentTimeMillis() + p.pauseMs else 0L,
                            pageTo = p.to.coerceAtMost(maxPages),
                        ),
                    )
                },
                shouldStop = { readStops[g.id] == true || (g.parent != null && g.parent == readAllParent && readStops[g.parent] == true) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.add('W', "lab", "extras read ${g.name}: ${e.message ?: e.javaClass.simpleName}")
            show(GroupRead(running = false, page = 0, entries = 0, maxPages = maxPages, reason = "couldn't be read (${e.message ?: "an error"})"))
            return
        }
        show(GroupRead(running = false, page = result.pages, entries = result.items.size, maxPages = maxPages, reason = result.reason))
        withContext(Dispatchers.IO) { runCatching { DirCache.putList(url, result, maxPages) } }
        if (result.items.isEmpty()) return
        // r106 fix: a card closed ("Not this") while it was read makes nothing
        if (mutable.value.menuGroups.none { it.id == g.id }) return
        // KMK (r112) fix: the directory being read when the group's Stop came is not made
        if (inDoAll && doAllStop) return
        onListRead(url, result, BuilderExtrasLogic.formFor(result.items.size), g.name.ifBlank { null }, menuId = g.id)
    }

    // ---------- r107: a scanned directory made into a collection ----------

    /**
     * r107: the ticks, mode and page range on a scanned directory card. KMK (r112): a part ticked or unticked on a card
     * whose collection is already made (by "Do all N" or by hand) is re-applied to that collection ([reapplyParts])
     */
    fun setDirChoice(id: Int, choice: DirChoice) {
        val before = mutable.value.menuGroups.firstOrNull { it.id == id }
        mutable.update { s ->
            s.copy(
                menuGroups = s.menuGroups.map { g ->
                    if (g.id != id) {
                        g
                    } else {
                        // KMK (r119): a remembered read of another page range no longer describes the card
                        val old = g.choice ?: g.probe?.scan?.takeIf { it.ok }?.let { BuilderExtrasLogic.defaultChoice(g.name, it) }
                        val moved = old == null || old.from != choice.from || old.to != choice.to
                        g.copy(choice = choice, read = if (moved && g.read?.cachedAt?.let { it > 0 } == true && g.made.isNullOrEmpty()) null else g.read)
                    }
                },
            )
        }
        val g = before ?: return
        val scan = g.probe?.scan?.takeIf { it.ok } ?: return
        val old = g.choice ?: BuilderExtrasLogic.defaultChoice(g.name, scan)
        if (old.fields == choice.fields) return
        val key = g.made?.takeIf { it.isNotEmpty() && it != "tabs" && it != "?" } ?: return
        reapplyParts(g.copy(choice = choice), scan, key, choice)
    }

    /**
     * KMK (r112): the parts kept changed on a card whose collection [key] is made: a live collection is made again from
     * page 1 (it replaces itself in place: the same list); a stored one drops the unticked parts from its entries at
     * once, and a newly ticked part needs its pages read again ("Make it again" says so)
     */
    private fun reapplyParts(g: MenuGroup, scan: BuilderListReader.DirScan, key: String, choice: DirChoice) {
        val made = mutable.value.filters.firstOrNull { it.key == key && it.type == "collection" } ?: return
        val c = made.collection ?: return
        if (c.live) {
            AppLog.add('I', "lab", "extras collection \"${made.name}\": parts changed on its card (${choice.fields.joinToString(",")}), made again")
            // made again in place: the filter it replaces is never asked Replace / Keep both about
            finishCollection(g, scan, scan.entries, choice.copy(live = true), 1, "live", replaces = { it.key == key && it.type == "collection" })
            return
        }
        val keep = BuilderExtrasLogic.COLLECTION_FIELDS.filter { it == "title" || (it in choice.fields && scan.part(it) != null) }
        val added = keep.filter { it !in c.fields }
        mutable.update { s ->
            s.copy(
                filters = s.filters.map { f ->
                    if (f.key != key || f.type != "collection") {
                        f
                    } else {
                        f.copy(
                            options = f.options.map { o ->
                                o.copy(
                                    number = if ("number" in keep) o.number else "",
                                    videos = if ("videos" in keep) o.videos else "",
                                    views = if ("views" in keep) o.views else "",
                                    likes = if ("likes" in keep) o.likes else "",
                                    picture = if ("picture" in keep) o.picture else "",
                                )
                            },
                            collection = c.copy(fields = keep.filter { it == "title" || it in c.fields }),
                        )
                    }
                },
                message = if (added.isEmpty()) {
                    s.message
                } else {
                    "\"${made.name}\" keeps its read pages: tap Make it again on its card to read them with ${added.joinToString(", ")}."
                },
                rev = s.rev + 1,
            )
        }
        AppLog.add('I', "lab", "extras collection \"${made.name}\": parts now ${keep.joinToString(",")}" + if (added.isEmpty()) "" else " (${added.joinToString(",")} need the pages read again)")
    }

    /**
     * r107: the scanned card's "Make filter": Live keeps page 1's entries and lets the source read the rest as Browse
     * scrolls; Read pages now reads the page range first (pause, progress, Stop). Not scanned: the r106 read
     */
    fun makeCollection(id: Int, refresh: Boolean = false) {
        val g = mutable.value.menuGroups.firstOrNull { it.id == id } ?: return
        if (g.href == null) return
        if (readJob?.isActive == true) {
            // KMK (r107) fix: a Live collection reads nothing now: made while another card reads; else say why not
            val scan = g.probe?.scan?.takeIf { it.ok }
            val choice = scan?.let { g.choice ?: BuilderExtrasLogic.defaultChoice(g.name, it) }
            if (scan != null && choice != null && choice.live && (scan.pageTemplate != null || scan.pages <= 1)) {
                scope.launch { finishCollection(g, scan, scan.entries, choice, 1, "live") }
            } else {
                mutable.update {
                    it.copy(message = "Another directory is being read: make \"${g.name.ifBlank { "this one" }}\" when it ends (or stop it)")
                }
            }
            return
        }
        readStops.remove(id)
        g.parent?.let { readStops.remove(it) }
        // KMK (r119): [refresh] = read the pages again instead of the remembered ones
        readJob = scope.launch { collectOne(g, BuilderExtrasLogic.READ_PAGES, refresh = refresh) }
    }

    private suspend fun collectOne(g: MenuGroup, maxPages: Int, scroll: Boolean = true, inDoAll: Boolean = false, refresh: Boolean = false) {
        val scan = g.probe?.scan?.takeIf { it.ok }
        if (scan == null) {
            readOne(g, maxPages, scroll, inDoAll, refresh)
            return
        }
        val choice = g.choice ?: BuilderExtrasLogic.defaultChoice(g.name, scan)
        val canLive = scan.pageTemplate != null || scan.pages <= 1
        if (choice.live && canLive) {
            if (inDoAll && doAllStop) return
            finishCollection(g, scan, scan.entries, choice, 1, "live")
            return
        }
        val (low, high) = readPause()
        val from = if (scan.pageTemplate == null) 1 else choice.from.coerceIn(1, scan.pages.coerceAtLeast(1))
        val to = choice.to.coerceIn(from, maxOf(from, scan.pages))
        val count = to - from + 1
        fun show(r: GroupRead) = mutable.update { s -> s.copy(menuGroups = s.menuGroups.map { if (it.id == g.id) it.copy(read = r) else it }) }
        // KMK (r119): these pages read in the last 7 days (same cards, parts and range) are used at once
        val remembered = if (refresh) null else withContext(Dispatchers.IO) { runCatching { DirCache.getDir(scan, from, to) }.getOrNull() }
        if (remembered != null) {
            val (r, at) = remembered
            show(GroupRead(running = false, page = r.pages, entries = r.entries.size, maxPages = count, reason = r.reason, first = from, cachedAt = at))
            AppLog.add('I', "lab", "extras read directory ${g.name}: ${r.entries.size} entries remembered (${DirCache.agoText(at)})")
            if (mutable.value.menuGroups.none { it.id == g.id }) return
            if (inDoAll && doAllStop) return
            finishCollection(g, scan, r.entries, choice.copy(live = false), r.pages, "remembered, ${r.reason}")
            return
        }
        show(GroupRead(running = true, page = 1, entries = 0, maxPages = count, first = from))
        // KMK (r109): the card's progress box comes to the top of the panel once, when the read starts
        // KMK (r112): not inside "Do all N"
        if (scroll) requestScroll("read:${g.id}", reveal = false)
        val started = System.currentTimeMillis()
        val result = try {
            BuilderListReader.readDirectory(
                scan = scan,
                from = from,
                to = to,
                pauseMinMs = low,
                pauseMaxMs = high,
                onProgress = { p ->
                    val now = System.currentTimeMillis()
                    // KMK (r119): the pages in flight ("pages 4–6 of 12"), or the next one during a pause
                    show(
                        GroupRead(
                            running = true,
                            page = (if (p.from > 0) p.from else p.done + 1).coerceAtMost(count),
                            entries = p.entries,
                            maxPages = count,
                            first = from,
                            waitUntil = if (p.pauseMs > 0) now + p.pauseMs else 0L,
                            pageMs = if (p.done > 0) (now - started + p.pauseMs) / p.done else 0L,
                            pageTo = p.to.coerceAtMost(count),
                        ),
                    )
                },
                shouldStop = { readStops[g.id] == true || (g.parent != null && g.parent == readAllParent && readStops[g.parent] == true) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.add('W', "lab", "extras read directory ${g.name}: ${e.message ?: e.javaClass.simpleName}")
            show(GroupRead(running = false, page = 0, entries = 0, maxPages = count, reason = "couldn't be read (${e.message ?: "an error"})", first = from))
            return
        }
        show(GroupRead(running = false, page = result.pages, entries = result.entries.size, maxPages = count, reason = result.reason, first = from))
        withContext(Dispatchers.IO) { runCatching { DirCache.putDir(scan, from, to, result) } }
        if (result.entries.isEmpty()) return
        // a card closed ("Not this") while it was read makes nothing
        if (mutable.value.menuGroups.none { it.id == g.id }) return
        // KMK (r112) fix: the directory being read when the group's Stop came is not made
        if (inDoAll && doAllStop) return
        finishCollection(g, scan, result.entries, choice.copy(live = false), result.pages, result.reason)
    }

    /** r107: the collection filter of [entries] (in the card's parent group), asked Replace / Keep both like any card's */
    private fun finishCollection(
        g: MenuGroup,
        scan: BuilderListReader.DirScan,
        entries: List<BuilderListReader.DirEntry>,
        choice: DirChoice,
        pages: Int,
        how: String,
        replaces: (ExtrasFilter) -> Boolean = { false },
    ) {
        // KMK (r112) fix: parts ticked / unticked on the card while its pages were read count (the card's current ticks)
        val ticks = mutable.value.menuGroups.firstOrNull { it.id == g.id }?.choice?.let { choice.copy(fields = it.fields) } ?: choice
        val url = g.href ?: scan.url
        val group = menuGroupOf(url).ifEmpty { g.headingPath.dropLast(1).lastOrNull()?.let(BuilderExtrasLogic::cleanHeading).orEmpty() }
        val derived = BuilderExtrasLogic.collectionFilter(
            name = g.name.ifBlank { null },
            group = group,
            scan = scan,
            entries = entries,
            choice = ticks,
            site = siteFor(scan.url),
            rule = mutable.value.pageRule,
        )
        val made = derived.filter
        AppLog.add(
            'I',
            "lab",
            "extras collection \"${g.name}\" from $url: ${made?.options?.size ?: 0} entries ($how, $pages page${if (pages == 1) "" else "s"}), " +
                "parts ${made?.collection?.fields?.joinToString(",") ?: "-"}, live ${made?.collection?.live ?: false}" +
                (if (group.isNotEmpty()) ", group $group" else ""),
        )
        val added = applyDerived(derived.copy(listUrl = null), "the directory \"${g.name}\"", replaces = replaces, menuId = g.id)
        if (made == null) {
            mutable.update { st -> st.copy(menuGroups = st.menuGroups.map { if (it.id == g.id) it.copy(made = "") else it }) }
            return
        }
        afterMade(added, g.id, "")
    }

    fun dismissMenuGroup(id: Int) {
        // r106: its learning or reading stops with it; a filter it was asking about is dropped
        if (mutable.value.learnGroup?.menuId == id) dismissLearn()
        // KMK (r109): its "Learn the menus" stops; a menu pick / picture tap it waits for is cancelled
        if (mutable.value.menuGroups.firstOrNull { it.id == id }?.dirLearn?.running == true) stopLearn()
        if (mutable.value.dirPick == id) cancelDirPick()
        if (mutable.value.picturePick == id) cancelPicturePick()
        // KMK (r112): a group's card closed while its "Do all N" runs: the run stops (what finished stays)
        if (mutable.value.menuGroups.firstOrNull { it.id == id }?.doAll?.running == true) stopDoAll(id)
        // r106 fix: only this card's read (and its sub-menus') stops: never its parent's, so the siblings stay readable
        val gone = mutable.value.menuGroups.filter { it.id == id || it.parent == id }.map { it.id }.toSet() + id
        gone.forEach { readStops[it] = true }
        mutable.update { s ->
            s.copy(
                menuGroups = s.menuGroups.filter { it.id !in gone },
                pendings = s.pendings.filterKeys { it !in gone },
            )
        }
    }

    /** Dashed green on this group's entries only */
    private fun outlineGroup(g: MenuGroup) {
        val paths = g.items.mapNotNull { it.path }.take(BuilderExtrasLogic.MAX_GROUP_OPTIONS)
        if (paths.isEmpty()) return
        val push = runInPage ?: return
        val list = JsonArray(paths.map { JsonPrimitive(it) })
        runCatching { push("window.__labPicker && window.__labPicker.outlineItems && window.__labPicker.outlineItems($list);") }
    }

    fun dismissListOffer() = mutable.update { it.copy(listOffer = null) }

    /**
     * Adds [derived]'s filter. r106: when filters with its name (or its address parameter) are there
     * ([BuilderExtrasLogic.conflicts]): [onConflict] null = ask on the card ([PendingFilter], nothing added yet), true =
     * Replace them (the new one takes the first one's place and group), false = Keep both. Returns the filter added,
     * or null (none made, or waiting for the answer)
     */
    private fun applyDerived(
        derived: BuilderExtrasLogic.GroupDerived,
        from: String,
        /** r102: other filters the new one replaces (the texts-only filter of a list that was then learned) */
        replaces: (ExtrasFilter) -> Boolean = { false },
        onConflict: Boolean? = null,
        menuId: Int? = null,
        kind: String = "",
    ): ExtrasFilter? {
        val f = derived.filter
        // KMK (r110): a menu picked / learned on a directory's page goes to that directory, never to the top level
        if (f != null) toDirectory(f, from)?.let { return it }
        if (f != null && onConflict == null) {
            val clash = BuilderExtrasLogic.conflicts(mutable.value.filters, f, replaces)
            if (clash.isNotEmpty()) {
                val replaced = mutable.value.filters.filter(replaces)
                mutable.update { s ->
                    s.copy(
                        // r106 fix: one question per card ("Read all N" asks on each sub-menu that clashes)
                        pendings = s.pendings + ((menuId ?: BuilderExtrasLogic.NO_CARD) to PendingFilter(f, clash, menuId, from, kind, replaced)),
                        message = derived.note.ifEmpty { null },
                        groupMode = false,
                        scrollTarget = if (menuId != null) "menu:$menuId" else "pending",
                        scrollTick = s.scrollTick + 1,
                        scrollReveal = false,
                        rev = s.rev + 1,
                    )
                }
                AppLog.add('I', "lab", "extras filter \"${f.name}\" from $from: ${clash.size} filter(s) with its name or parameter (${clash.joinToString(", ") { it.name }}): Replace or Keep both asked")
                return null
            }
        }
        val clash = if (f != null && onConflict == true) BuilderExtrasLogic.conflicts(mutable.value.filters, f, replaces) else emptyList()
        var added: ExtrasFilter? = null
        mutable.update { s ->
            added = null
            val filters = if (f == null) {
                s.filters
            } else {
                // r104 fix: a filter made again (the same list) keeps its place in the order the owner set. r105 fix:
                // and its group, "Also while searching for words" and a role set on its card. r106: the same list
                // only ([BuilderExtrasLogic.sameList]); Replace takes the replaced filter's place and group
                val gone: (ExtrasFilter) -> Boolean = { old -> BuilderExtrasLogic.sameList(old, f) || replaces(old) || old in clash }
                val at = s.filters.indexOfFirst(gone)
                val others = s.filters.filterNot(gone)
                val old = s.filters.getOrNull(at)
                val kept = when {
                    old == null -> f
                    old in clash -> f.copy(group = old.group.ifBlank { f.group })
                    else -> BuilderExtrasLogic.keepOwnerSettings(old, f)
                }
                val made = if (kept.key.isEmpty()) kept else kept.copy(key = BuilderExtrasLogic.uniqueKey(kept.key, others.map { it.key }))
                added = made
                // r105 fix: a group's filters stay together (the builder's headings as the Browse sheet's)
                BuilderExtrasLogic.groupsTogether(
                    if (at < 0) others + made else others.toMutableList().also { it.add(at.coerceAtMost(it.size), made) },
                )
            }
            s.copy(
                filters = filters,
                listOffer = derived.listUrl?.let { ListOffer(it, derived.listText) },
                message = derived.note.ifEmpty { null },
                groupMode = false,
                rev = s.rev + 1,
            )
        }
        val made = added
        if (made != null) {
            val where = if (made.mode == "path") made.path else made.param.ifEmpty { "(parameter to type)" }
            AppLog.add('I', "lab", "extras filter ${made.key.ifEmpty { "?" }} (${made.type}, ${made.mode} $where, ${made.options.size} options) from $from")
            if (clash.isNotEmpty()) AppLog.add('I', "lab", "extras filter \"${made.name}\" replaced ${clash.joinToString(", ") { "\"${it.name}\"" }}")
        } else {
            AppLog.add('D', "lab", "extras no filter from $from: ${derived.note}")
        }
        derived.listUrl?.let { AppLog.add('D', "lab", "extras offer: read the whole list of $it") }
        return made
    }

    /**
     * KMK (r110): [f] made from a menu on a directory's page (a scanned directory card's page, or a collection's): it
     * becomes that directory's sort / menu (its scan, and its collection when made) and is NOT added to the top-level
     * filters; top-level filters already made from that menu are dropped ([BuilderExtrasLogic.dropDirectoryMenus]).
     * Returns [f] when it went there (the card that made it says so), null = not a directory's menu
     */
    private fun toDirectory(f: ExtrasFilter, from: String): ExtrasFilter? {
        val menu = BuilderExtrasLogic.asDirectoryMenu(f) ?: return null
        val s = mutable.value
        val sort = f.type == "sort"
        val card = s.menuGroups.firstOrNull { g ->
            val url = g.probe?.scan?.url ?: return@firstOrNull false
            BuilderExtrasLogic.madeOnDirectory(f, BuilderExtrasLogic.pathOnly(url))
        }
        val cardMade = card?.made?.takeIf { it.isNotEmpty() && it != "tabs" && it != "?" }
        val dir = s.filters.firstOrNull { d ->
            d.type == "collection" && d.collection != null && BuilderExtrasLogic.madeOnDirectory(f, BuilderExtrasLogic.directoryPath(d))
        }
        if (card == null && dir == null) return null
        if (card != null) updateScan(card.id) { sc -> BuilderExtrasLogic.withMenu(sc, menu, sort) }
        val where = dir?.name ?: card?.name.orEmpty()
        mutable.update { st ->
            // the collection the card didn't make (updateScan changed that one already)
            val withMenu = st.filters.map { d ->
                val c = d.collection
                if (dir != null && d.key == dir.key && d.key != cardMade && c != null) d.copy(collection = BuilderExtrasLogic.withMenu(c, menu, sort)) else d
            }
            val (kept, dropped) = BuilderExtrasLogic.dropDirectoryMenus(withMenu, dirMenuLabels(st.menuGroups))
            if (dropped.isNotEmpty()) {
                AppLog.add('I', "lab", "extras filters ${dropped.joinToString(", ") { "\"${it.name}\"" }}: menus of a directory, dropped from the top level")
            }
            st.copy(
                filters = kept,
                message = "\"${f.name}\" is a menu of the directory \"$where\": kept with it only (in its drop-down), not as a filter of its own.",
                groupMode = false,
                rev = st.rev + 1,
            )
        }
        AppLog.add('I', "lab", "extras \"${f.name}\" (${f.param}, ${menu.options.size} options) from $from → the directory \"$where\"'s ${if (sort) "sort" else "menus"}")
        return f
    }

    /**
     * r106: the card's Replace "<name>" ([replace] true) or Keep both (false) for the waiting filter. A learned list's
     * card closes; a menu card says the filter was made
     */
    fun resolvePending(replace: Boolean, menuId: Int? = null) {
        val p = pendingFor(menuId) ?: return
        val key = p.menuId ?: BuilderExtrasLogic.NO_CARD
        // r106 fix: the filters it replaces whatever the answer (a learned list's texts-only one) are still dropped
        val replaced = p.replaced
        val made = applyDerived(
            BuilderExtrasLogic.GroupDerived(p.filter, "", null, "", p.filter.options.size, "pending"),
            p.from,
            replaces = { old -> replaced.any { r -> r == old || (r.key.isNotEmpty() && r.key == old.key && r.name == old.name) } },
            onConflict = replace,
        )
        AppLog.add('I', "lab", "extras filter \"${p.filter.name}\": ${if (replace) "Replace" else "Keep both"}")
        mutable.update { s -> s.copy(pendings = s.pendings - key, message = null) }
        afterMade(made, p.menuId, p.kind)
    }

    /** r106: the waiting filter's card closed without an answer ("Not now"): nothing is added */
    fun dismissPending(menuId: Int? = null) {
        val p = pendingFor(menuId) ?: return
        val key = p.menuId ?: BuilderExtrasLogic.NO_CARD
        mutable.update { it.copy(pendings = it.pendings - key) }
    }

    /** r106 fix: the question of card [menuId]; null = the one without a card, else the first one waiting */
    private fun pendingFor(menuId: Int?): PendingFilter? {
        val all = mutable.value.pendings
        return if (menuId != null) all[menuId] else all[BuilderExtrasLogic.NO_CARD] ?: all.values.firstOrNull()
    }

    /** r106: after a card's filter was added: the card says so; a learned list's run is closed */
    private fun afterMade(made: ExtrasFilter?, menuId: Int?, kind: String) {
        if (made == null) return
        mutable.update { st ->
            st.copy(
                menuGroups = if (menuId == null) st.menuGroups else st.menuGroups.map { if (it.id == menuId) it.copy(made = made.key.ifEmpty { "?" }) else it },
                learnGroup = if (kind == "learned") null else st.learnGroup,
                learn = if (kind == "learned") null else st.learn,
                offers = if (kind == "learned" && made.mode == "param") st.offers.filter { it.param != made.param } else st.offers,
            )
        }
    }

    /**
     * A new pick of the same list replaces its filter; a filter of another type or key (a Sort on the same
     * `/en/{value}` path as a menu's other pages) is never replaced: the new one is added under a unique key
     */
    private fun sameFilter(a: ExtrasFilter, b: ExtrasFilter): Boolean = BuilderExtrasLogic.sameFilter(a, b)

    // ---------- KMK (r109): a scanned directory's custom drop-downs and pictures ----------

    /** r109: changes the scan of card [id]; its collection, when already made, takes the new sorts and menus too */
    private fun updateScan(id: Int, change: (BuilderListReader.DirScan) -> BuilderListReader.DirScan) {
        mutable.update { s ->
            val g = s.menuGroups.firstOrNull { it.id == id } ?: return@update s
            val scan = g.probe?.scan ?: return@update s
            val next = change(scan)
            val made = g.made?.takeIf { it.isNotEmpty() && it != "tabs" && it != "?" }
            val filters = if (made == null) {
                s.filters
            } else {
                s.filters.map { f ->
                    val c = f.collection
                    if (f.key == made && f.type == "collection" && c != null) {
                        f.copy(collection = c.copy(sorts = next.sorts, sortParam = next.sortParam, menus = next.menus))
                    } else {
                        f
                    }
                }
            }
            s.copy(
                menuGroups = s.menuGroups.map { if (it.id == id) it.copy(probe = it.probe?.copy(scan = next)) else it },
                filters = filters,
                rev = s.rev + 1,
            )
        }
    }

    /**
     * KMK (r110) fix: the scanned directory cards' menu labels by directory path ("/en/actresses" → Height, Cup, Age,
     * Sort): learned (its menus) or still to learn (its custom drop-downs)
     */
    private fun dirMenuLabels(groups: List<MenuGroup>): Map<String, Collection<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        for (g in groups) {
            val scan = g.probe?.scan ?: continue
            val path = BuilderExtrasLogic.pathOnly(scan.url)
            if (path.isEmpty()) continue
            out.getOrPut(path) { LinkedHashSet() }.addAll(scan.menus.map { it.name } + scan.custom.map { it.label })
        }
        return out
    }

    /** KMK (r110) fix: top-level filters that are a directory's menus go (right after a learning run saved its codes) */
    private fun dropDirectoryMenusNow() {
        var gone: List<ExtrasFilter> = emptyList()
        mutable.update { st ->
            val (kept, dropped) = BuilderExtrasLogic.dropDirectoryMenus(st.filters, dirMenuLabels(st.menuGroups))
            gone = dropped
            if (dropped.isEmpty()) st else st.copy(filters = kept, rev = st.rev + 1)
        }
        if (gone.isNotEmpty()) {
            AppLog.add('I', "lab", "extras filters ${gone.joinToString(", ") { "\"${it.name}\" (${it.param})" }}: menus of a directory, dropped from the top level")
        }
    }

    private fun showDirLearn(id: Int, change: (DirLearn?) -> DirLearn?) = mutable.update { s ->
        s.copy(menuGroups = s.menuGroups.map { if (it.id == id) it.copy(dirLearn = change(it.dirLearn)) else it })
    }

    /**
     * r109: the scanned card's "Learn the menus": its custom drop-downs that aren't codes yet (Sort, Height, Cup, Age
     * whose entries are buttons, or lists the site adds only when they open) are pressed on the directory page in the
     * builder's page, one entry at a time with the pause set for reading, and the address each press makes is read
     * (the r102 learning); the codes go to this card's collection (its sort options and menus)
     */
    fun learnDirMenus(id: Int) {
        val g = mutable.value.menuGroups.firstOrNull { it.id == id } ?: return
        val scan = g.probe?.scan ?: return
        if (scan.custom.isEmpty()) return
        startDirLearn(id, scan.url, scan.custom)
    }

    private fun startDirLearn(id: Int, base: String, menus: List<BuilderDirMenus.Menu>) {
        if (learnJob?.isActive == true) {
            mutable.update { it.copy(message = "Something is being learned on the page: wait for it to end (or stop it)") }
            return
        }
        if (runInPage == null) {
            mutable.update { it.copy(message = "The page isn't ready: open the directory page in the builder first") }
            return
        }
        val (low, high) = readPause()
        stopLearning = false
        learnJob = scope.launch {
            try {
                runDirLearn(id, base, menus, low, high)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.add('E', "lab", "learn menus failed: ${e.message ?: e.javaClass.simpleName}")
                showDirLearn(id) { it?.copy(running = false, note = "Learning stopped: ${e.message ?: "an error"}") }
            }
        }
        requestScroll("menu:$id", reveal = false)
    }

    /**
     * KMK (r110) rewrite after the owner's r109 run on the Actresses directory (Height, Cup, Age, Sort all "no entry
     * changed the address", nothing saved):
     * - a press counts when it changes a VALUE of the address ([BuilderDirMenus.sameValues]); the site writes every
     *   parameter even when empty (`?page=1&q=&height=&cup=&age=&sort=count`), so "the parameter is there" says nothing
     * - before each menu and after it the page goes back to the plain directory address (every menu at its default), so
     *   Height's choice isn't still set while Cup is learned; the address the site shows then is the codes' base
     * - an entry whose page shows no cards is kept ("nothing found"); one the press did nothing for is "failed"; neither
     *   stops the menu. A menu "can't be learned" only when no entry changed any value
     * - the codes go to this card's scan (and its collection), never to the top-level filters
     */
    private suspend fun runDirLearn(id: Int, base: String, menus: List<BuilderDirMenus.Menu>, low: Long, high: Long) {
        val card = mutable.value.menuGroups.firstOrNull { it.id == id }
        val itemSelector = card?.probe?.scan?.item.orEmpty()
        val collection = card?.name.orEmpty().ifBlank { BuilderExtrasLogic.nameFromUrl(base).orEmpty() }
        val found = ArrayList<String>()
        val states = menus.map { DirLearnMenu(it.label, it.sort, "waiting", entries = it.items.size) }.toMutableList()
        // the rows of menus learned before (a "Pick it on the page" learns one menu again) stay
        val earlier = card?.dirLearn?.menus.orEmpty().filter { e -> menus.none { it.label.equals(e.label, ignoreCase = true) } }
        var done = 0
        var total = menus.sumOf { it.items.size.coerceAtLeast(1) }
        var stopReason: String? = null
        var label = menus.first().label
        var entryNow = ""
        val started = System.currentTimeMillis()
        fun left(): Long = if (done <= 0) -1L else (System.currentTimeMillis() - started) / done * (total - done).coerceAtLeast(0)
        fun show() = showDirLearn(id) {
            DirLearn(
                running = true,
                label = label,
                done = done,
                total = total,
                found = found.toList(),
                entry = entryNow,
                menus = earlier + states,
                leftMs = left(),
                collection = collection,
            )
        }
        show()
        AppLog.add('I', "lab", "learn menus on $base: ${menus.joinToString(", ") { "${it.label} (${it.items.size})" }}, pause $low–$high ms")
        // The plain directory address as the site shows it (it may fill in every parameter: `?page=1&q=&sort=count`)
        var plain = base
        var siteFilled = emptySet<String>()
        var loadFails = 0

        // Back to the plain address unless the page already picks the same
        suspend fun reset(): Boolean {
            if (BuilderDirMenus.sameValues(currentUrl(), plain)) return true
            if (!loadPage(base)) return false
            pause(SETTLE_MS)
            return true
        }
        try {
            if (!loadPage(base)) {
                stopReason = "The directory page didn't load ($base)."
                return
            }
            pause(SETTLE_MS)
            // KMK (r110) fix: a new run: learn.js's presses aren't cancelled any more (banner(null) of the last run)
            inPage("window.__labLearn&&window.__labLearn.begin&&window.__labLearn.begin();")
            val shown = currentUrl()
            if (BuilderDirMenus.samePage(shown, base)) {
                plain = clean(shown)
                siteFilled = BuilderDirMenus.siteFilled(base, plain)
            }
            AppLog.add('D', "lab", "learn menus: plain address $plain" + if (siteFilled.isEmpty()) "" else " (the site filled in ${siteFilled.joinToString(", ")})")
            for ((mi, m) in menus.withIndex()) {
                if (stopLearning) {
                    stopReason = "Stopped by you."
                    break
                }
                label = m.label
                entryNow = ""
                states[mi] = states[mi].copy(state = "running")
                show()
                if (!reset()) {
                    if (stopLearning) {
                        // KMK (r110) fix: Stop while the page went back to the plain address: not a load failure
                        stopReason = "Stopped by you."
                        break
                    }
                    states[mi] = states[mi].copy(state = "failed", note = "the directory page didn't load")
                    found += "${m.label}: the directory page didn't load"
                    if (++loadFails >= LOAD_FAILS) {
                        stopReason = "The directory page didn't load ($base)."
                        break
                    }
                    continue
                }
                var items = m.items
                if (items.isEmpty()) {
                    // its list isn't in the page until it opens: learn.js opens it and reads the entries
                    items = readMenuItems(m)
                    total += (items.size - 1).coerceAtLeast(0)
                    states[mi] = states[mi].copy(entries = items.size)
                    AppLog.add('D', "lab", "learn menus: ${m.label} opened: ${items.size} entries")
                }
                if (items.size < 2) {
                    states[mi] = states[mi].copy(state = "failed", note = "its entries didn't show when it was pressed")
                    found += "${m.label}: its entries didn't show when it was pressed"
                    continue
                }
                val rows = ArrayList<BuilderDirMenus.Row>()
                val shownRows = ArrayList<DirLearnRow>()
                // KMK (r110) fix: the address each shown row opened (its code is redone for the menu's parameter only)
                val shownUrls = ArrayList<String?>()
                // KMK (r110) fix: Stop cut this menu short: it isn't saved
                var cut = false
                if (items.count { it.href != null } * 2 >= items.size) {
                    // links: where each goes is in the page, nothing to press
                    items.filter { it.href != null || it.current }.forEach { rows += BuilderDirMenus.Row(it.text, it.href ?: plain, it.current) }
                    done += items.size
                } else {
                    val group = LearnGroup(
                        pageUrl = plain,
                        opener = m.opener.ifEmpty { null },
                        likeSelector = "",
                        name = m.label,
                        entries = items.map { LearnEntry(it.text, null, null) },
                        hasLinks = false,
                        current = m.current,
                        openerLabel = m.label,
                    )
                    for ((k, item) in items.withIndex()) {
                        if (stopLearning) {
                            stopReason = "Stopped by you."
                            cut = true
                            break
                        }
                        if (done >= MAX_PRESSES * 2) {
                            stopReason = "Pressed ${MAX_PRESSES * 2} entries, the most one run presses."
                            break
                        }
                        if (k > 0 && high > 0) pause(Random.nextLong(low, high + 1))
                        entryNow = item.text
                        states[mi] = states[mi].copy(rows = shownRows + DirLearnRow(item.text, "", "pressing"))
                        show()
                        val banner = "Learning the menus · pressing \"${m.label}: ${item.text}\" (${done + 1} of $total)"
                        val r = pressOnce(group, LearnEntry(item.text, null, null), plain, base, itemSelector, banner)
                        done++
                        AppLog.add('D', "lab", "learn menus: ${m.label} › ${item.text} → ${r.url ?: r.status}" + if (r.cards >= 0) " (${r.cards} cards)" else "")
                        when (r.status) {
                            "stopped" -> {
                                stopReason = "Stopped by you."
                                cut = true
                            }
                            "learned" -> {
                                loadFails = 0
                                rows += BuilderDirMenus.Row(item.text, r.url, item.current)
                                // back at the plain address's values ("Any height" pressed after "150–154 cm"): the default
                                val code = BuilderDirMenus.changeOf(plain, r.url)
                                val status = when {
                                    code.isEmpty() -> "default"
                                    r.cards == 0 -> "empty"
                                    else -> "learned"
                                }
                                shownRows += DirLearnRow(item.text, code, status, r.cards, r.more)
                                shownUrls.add(r.url)
                            }
                            "nochange" -> {
                                loadFails = 0
                                rows += BuilderDirMenus.Row(item.text, null, item.current)
                                shownRows += DirLearnRow(item.text, "", "default", r.cards, r.more)
                                shownUrls.add(null)
                            }
                            else -> {
                                // "missing" (not found on the page) or "failed" (the page didn't load again): kept as
                                // failed, the next entry is pressed
                                shownRows += DirLearnRow(item.text, "", "failed")
                                shownUrls.add(null)
                                if (r.status == "failed" && ++loadFails >= LOAD_FAILS) stopReason = "The directory page didn't load again ($base)."
                            }
                        }
                        states[mi] = states[mi].copy(rows = shownRows.toList())
                        if (stopReason != null) break
                    }
                    entryNow = ""
                }
                if (cut) {
                    // KMK (r110) fix: Stop interrupted this menu: its few codes aren't saved (they'd replace a whole
                    // menu); its row says "stopped" and offers "Learn again"
                    states[mi] = states[mi].copy(state = "stopped", note = "stopped", rows = shownRows.toList())
                    found += "${m.label}: stopped, not saved"
                    AppLog.add('I', "lab", "learn menus: ${found.last()}")
                    show()
                    break
                }
                val coded = BuilderDirMenus.codesOf(plain, rows, m.current, m.sort, siteFilled)
                // After the menu: back to the plain address (the next menu starts there too)
                if (!stopLearning) reset()
                if (coded.ok && (m.sort || coded.param.isNotEmpty())) {
                    updateScan(id) { sc -> BuilderExtrasLogic.withDirMenu(sc, m, coded) }
                    // KMK (r110) fix: Height / Cup / Age already made as top-level filters on this page go now
                    dropDirectoryMenusNow()
                    // an entry that changed nothing and isn't the default: its press did nothing. KMK (r110) fix: each
                    // row's code is the menu's parameter only ("height=150-154", not "…&sort=count"); "" = no change
                    val finalRows = shownRows.mapIndexed { i, row0 ->
                        val row = if (row0.status == "learned" || row0.status == "empty" || row0.status == "default") {
                            val code = BuilderDirMenus.codeFor(coded, plain, shownUrls.getOrNull(i))
                            when {
                                code.isEmpty() -> row0.copy(code = "", status = "default")
                                row0.status == "default" -> row0.copy(code = code, status = "learned")
                                else -> row0.copy(code = code)
                            }
                        } else {
                            row0
                        }
                        if (row.status == "default" && !row.text.equals(coded.defaultLabel, ignoreCase = true)) row.copy(status = "failed") else row
                    }.ifEmpty {
                        coded.each.map { (t, v) -> DirLearnRow(t, if (v.isEmpty()) "" else if (coded.pathMode) v else "${coded.param}=$v", if (v.isEmpty()) "default" else "learned") }
                    }
                    val empty = finalRows.count { it.status == "empty" }
                    val failed = finalRows.count { it.status == "failed" }
                    states[mi] = states[mi].copy(
                        state = "done",
                        param = coded.param,
                        pathMode = coded.pathMode,
                        options = coded.options.size,
                        defaultLabel = coded.defaultLabel,
                        empty = empty,
                        failed = failed,
                        rows = finalRows,
                    )
                    found += "${m.label}: ${coded.options.size} options" +
                        (if (coded.defaultLabel.isNotEmpty()) " (default ${coded.defaultLabel})" else "") +
                        (if (coded.pathMode) ", each its own address" else ", ${coded.param}=…") +
                        (if (empty > 0) ", $empty showed nothing, kept" else "") +
                        (if (failed > 0) ", $failed failed" else "")
                } else {
                    val why = coded.note.ifEmpty { "no entry changed a value in the address" }
                    states[mi] = states[mi].copy(state = "failed", note = why, rows = shownRows.toList())
                    found += "${m.label}: $why"
                }
                AppLog.add('I', "lab", "learn menus: ${found.last()}")
                show()
                if (stopReason != null) break
            }
        } finally {
            val note = stopReason ?: "Done: the codes are kept with this directory only."
            // menus never reached stay "waiting"; the one cut short says so. KMK (r110) fix: after Stop they are
            // "not learned (stopped)" (with "Learn again"), never "can't be learned"
            val byYou = stopLearning || found.any { it.endsWith(": stopped, not saved") }
            val reason = stopReason
            val ended = states.map {
                when {
                    byYou && it.state == "running" -> it.copy(state = "stopped", note = "stopped")
                    byYou && it.state == "waiting" -> it.copy(state = "stopped", note = "not learned (stopped)")
                    // KMK (r112) fix: the run ended early (the page didn't load, the most presses): not reached, said so
                    reason != null && it.state == "waiting" -> it.copy(state = "stopped", note = "not reached (${reason.removeSuffix(".")})")
                    it.state == "running" -> it.copy(state = "failed", note = stopReason ?: "stopped")
                    else -> it
                }
            }
            showDirLearn(id) {
                DirLearn(
                    running = false,
                    label = "",
                    done = done,
                    total = total,
                    found = found.toList(),
                    note = note,
                    menus = earlier + ended,
                    collection = collection,
                )
            }
            AppLog.add('I', "lab", "learn menus on $base: ${found.joinToString(" · ")} ($note)")
            runCatching { inPage("window.__labLearn&&window.__labLearn.banner&&window.__labLearn.banner(null);") }
        }
        // Back to the plain directory page, so the page shows what was learned from
        if (!BuilderDirMenus.sameValues(currentUrl(), plain)) runCatching { loadPage(base) }
    }

    /** KMK (r110): one press's outcome: learned|nochange|missing|stopped|failed, the address it opened, cards shown */
    private data class PressResult(val status: String, val url: String?, val cards: Int = -1, val more: Boolean = false) // KMK (r112): more

    /**
     * r109: one press of [entry] on the directory page (loaded again from [base] when the page left it): "learned" with
     * the address it opened, "nochange", "missing" (learn.js didn't find it), "stopped" or "failed" (the page didn't
     * load). KMK (r110): an address counts as changed only when a value differs (`?height=` → `?height=150-154`); a
     * press that changed nothing while the page wasn't at [plain] (a previous entry's choice still set) is pressed again
     * from [plain] once; the cards ([itemSelector]) are counted on the page it opened (0 = "nothing found")
     */
    private suspend fun pressOnce(
        group: LearnGroup,
        entry: LearnEntry,
        plain: String,
        base: String,
        itemSelector: String,
        banner: String,
        again: Boolean = true,
    ): PressResult {
        if (!BuilderDirMenus.samePage(currentUrl(), plain)) {
            if (!loadPage(base)) return PressResult(if (stopLearning) "stopped" else "failed", null)
        }
        pause(SETTLE_MS)
        if (stopLearning) return PressResult("stopped", null)
        val from = currentUrl()
        val before = seen.value.tick
        val id = press(group, entry, banner)
        val answer = waitFor(ANSWER_MS) { outcome.value.takeIf { it.id == id } }
        fun moved() = seen.value.takeIf { it.tick > before && !BuilderDirMenus.sameValues(it.url, from) }
        val gone = waitFor(0L) { moved() }
        val changed = when {
            gone != null -> gone
            answer == null || answer.state != "pressed" -> null
            else -> waitFor(CHANGE_MS) { moved() }
        }
        return when {
            changed != null -> {
                pause(SETTLE_MS)
                // the address may still move a little (the site settling): the latest one on the same page
                val url = seen.value.url.takeIf { BuilderDirMenus.samePage(it, changed.url) } ?: changed.url
                // KMK (r112): with whether that page has a next page ("24+ (page 1)")
                val (cards, more) = countCards(itemSelector)
                PressResult("learned", url.substringBefore('#'), cards, more)
            }
            stopLearning -> PressResult("stopped", null)
            answer == null || answer.state != "pressed" -> PressResult("missing", null)
            again && !BuilderDirMenus.sameValues(from, plain) -> {
                AppLog.add('D', "lab", "learn menus: \"${entry.text}\" changed nothing on $from: pressed again from the plain address")
                if (!loadPage(base)) return PressResult(if (stopLearning) "stopped" else "failed", null)
                pressOnce(group, entry, plain, base, itemSelector, banner, again = false)
            }
            else -> PressResult("nochange", null)
        }
    }

    /** KMK (r110): learn.js `count`'s answer ("learn: count #<id> <n>"); KMK (r112): + " next" = the page has a next page */
    private val countAnswer = MutableStateFlow(Triple(0L, -1, false))

    /**
     * KMK (r110): how many cards ([itemSelector]) the page shows now; looked at again after 1.5 s when none (a list
     * still loading). -1 = not known (no selector, no answer). KMK (r112): and whether the page has a next page
     */
    private suspend fun countCards(itemSelector: String): Pair<Int, Boolean> {
        if (itemSelector.isBlank()) return -1 to false
        var n = -1
        var more = false
        for (attempt in 0 until 2) {
            if (attempt > 0) pause(COUNT_AGAIN_MS)
            if (stopLearning) return n to more
            val id = ++pressId
            val request = JsonObject(mapOf("id" to JsonPrimitive(id), "selector" to JsonPrimitive(itemSelector)))
            inPage(
                "(function(){var r=$request;function go(k){if(window.__labLearn&&window.__labLearn.count){window.__labLearn.count(r);}" +
                    "else if(k<30){setTimeout(function(){go(k+1);},100);}}go(0);})();",
            )
            val answer = waitFor(COUNT_MS) { countAnswer.value.takeIf { it.first == id } }
            n = answer?.second ?: -1
            more = answer?.third == true
            if (n != 0) return n to more
        }
        return n to more
    }

    /** r109: learn.js `items`'s answer ("learn: items #<id> <json>"), by the request's id */
    private data class ItemsAnswer(val id: Long, val json: String)

    private val itemsAnswer = MutableStateFlow(ItemsAnswer(0L, ""))

    /** r109: opens [m] on the page (learn.js `items`: its button pressed, the entries that show read) */
    private suspend fun readMenuItems(m: BuilderDirMenus.Menu): List<BuilderDirMenus.Item> {
        val id = ++pressId
        val request = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "opener" to (m.opener.ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull),
                "label" to JsonPrimitive(m.label),
                "current" to JsonPrimitive(m.current),
            ),
        )
        inPage(
            "(function(){var r=$request;function go(k){if(window.__labLearn&&window.__labLearn.items){window.__labLearn.items(r);}" +
                "else if(k<50){setTimeout(function(){go(k+1);},100);}}go(0);})();",
        )
        val got = waitFor(ITEMS_MS) { itemsAnswer.value.takeIf { it.id == id } } ?: return emptyList()
        val o = runCatching { jsJson.parseToJsonElement(got.json) as? JsonObject }.getOrNull() ?: return emptyList()
        if (o.bool("ok") != true) {
            AppLog.add('D', "lab", "learn menus: ${m.label} didn't open: ${o.str("reason").orEmpty()}")
            return emptyList()
        }
        return o.arr("items")?.mapNotNull { e ->
            val one = e as? JsonObject ?: return@mapNotNull null
            val text = one.str("text")?.trim().orEmpty()
            if (text.isEmpty()) {
                null
            } else {
                BuilderDirMenus.Item(text, one.str("href")?.takeIf { h -> h.startsWith("http") }, one.str("value"), one.bool("current") == true)
            }
        }.orEmpty().distinctBy { it.text.lowercase() }
    }

    /**
     * r109: the card's "Pick the site's Sort menu" ([sort]) / "Pick another menu": the directory page opens in the
     * builder's page and the next menu pick is this card's (its entries' codes are learned and kept with it)
     */
    fun pickDirMenu(id: Int, sort: Boolean) {
        val g = mutable.value.menuGroups.firstOrNull { it.id == id } ?: return
        val scan = g.probe?.scan ?: return
        if (learnJob?.isActive == true) {
            mutable.update { it.copy(message = "Something is being learned on the page: wait for it to end (or stop it)") }
            return
        }
        mutable.update { it.copy(dirPick = id, dirPickSort = sort, picturePick = null, message = null) }
        AppLog.add('I', "lab", "extras directory \"${g.name}\": pick the site's ${if (sort) "sort menu" else "menu"} on ${scan.url}")
        scope.launch {
            stopLearning = false
            if (!onPage(scan.url)) loadPage(scan.url)
            if (mutable.value.dirPick == id) setGroupMode(true)
        }
    }

    /** r109: the waiting "Pick the site's … menu" is cancelled */
    fun cancelDirPick() {
        mutable.update { it.copy(dirPick = null) }
        if (mutable.value.groupMode) setGroupMode(false)
    }

    /** r109: the menu pick a card asked for: a menu of its entries (or, not in the page yet, opened by its label) */
    private fun onDirMenuPicked(id: Int, sort: Boolean, obj: JsonObject, pageUrl: String) {
        val scan = mutable.value.menuGroups.firstOrNull { it.id == id }?.probe?.scan ?: return
        val raw = obj.arr("groups")?.mapNotNull { it as? JsonObject }.orEmpty()
        val picked = BuilderExtrasLogic.menuGroupsOf(raw, pageUrl, 0).firstOrNull { it.items.size >= 2 }
        val tapped = obj.obj("tapped")?.str("text").orEmpty()
        val menu = if (picked != null) {
            val named = picked.name.takeIf { it.isNotBlank() && it.length <= 24 }
            BuilderDirMenus.Menu(
                label = named ?: BuilderDirMenus.labelValue(tapped)?.first ?: if (sort) "Sort" else "Menu",
                current = picked.current.ifEmpty { BuilderDirMenus.currentOf(tapped) },
                opener = picked.opener.orEmpty(),
                items = picked.items.map { BuilderDirMenus.Item(it.text, it.href, it.value, it.current) },
                sort = sort,
            )
        } else {
            val lv = BuilderDirMenus.labelValue(tapped)
            if (lv == null) {
                mutable.update {
                    it.copy(message = "No menu found where you tapped: tap the menu's button (\"Sort: …\") or one of its entries.")
                }
                AppLog.add('I', "lab", "extras directory menu pick at \"${tapped.take(40)}\": no menu")
                return
            }
            // its list is added only when it opens: learn.js opens it by its label
            BuilderDirMenus.Menu(lv.first, lv.second, "", emptyList(), sort)
        }
        AppLog.add('I', "lab", "extras directory menu picked: ${menu.label} (${menu.items.size} entries, current \"${menu.current}\")")
        startDirLearn(id, scan.url, listOf(menu))
    }

    /**
     * r109: the card's "Tap the picture on the page": the directory page opens in the builder's page and the next tap
     * (picker.js `pictureMode`, with the cards' selector) names one card's picture
     */
    fun pickDirPicture(id: Int) {
        val g = mutable.value.menuGroups.firstOrNull { it.id == id } ?: return
        val scan = g.probe?.scan ?: return
        if (learnJob?.isActive == true) {
            mutable.update { it.copy(message = "Something is being learned on the page: wait for it to end (or stop it)") }
            return
        }
        if (mutable.value.groupMode) setGroupMode(false)
        mutable.update { it.copy(picturePick = id, dirPick = null, message = null) }
        AppLog.add('I', "lab", "extras directory \"${g.name}\": tap one card's picture on ${scan.url}")
        scope.launch {
            stopLearning = false
            if (!onPage(scan.url)) loadPage(scan.url)
            if (mutable.value.picturePick != id) return@launch
            picturePickScript()?.let { inPage(it) }
        }
    }

    /**
     * KMK (r109) fix: the script that arms picker.js `pictureMode` for the waiting picture tap (null when none waits);
     * also sent after every injection (BuilderScreen), so a page reload doesn't drop the tap
     */
    fun picturePickScript(): String? {
        val id = mutable.value.picturePick ?: return null
        val scan = mutable.value.menuGroups.firstOrNull { it.id == id }?.probe?.scan ?: return null
        val request = JsonObject(mapOf("item" to JsonPrimitive(scan.item)))
        return "(function(){var r=$request;function go(k){if(window.__labPicker&&window.__labPicker.pictureMode){window.__labPicker.pictureMode(r);}" +
            "else if(k<50){setTimeout(function(){go(k+1);},100);}}go(0);})();"
    }

    /** r109: the waiting picture tap is cancelled */
    fun cancelPicturePick() {
        mutable.update { it.copy(picturePick = null) }
        runCatching { runInPage?.invoke("window.__labPicker && window.__labPicker.pictureMode && window.__labPicker.pictureMode(null);") }
    }

    /**
     * r109: picker.js `pictureMode`'s answer (a pick with via "picture"): `{ ok, selectors: [...], attrs: [...], url,
     * reason }`. The directory's first page is read again with it ([BuilderListReader.withPicture]); the card's picture
     * part is ticked
     */
    fun onPicturePicked(json: String) {
        val id = mutable.value.picturePick ?: return
        mutable.update { it.copy(picturePick = null) }
        val o = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        if (o == null || o.bool("ok") != true) {
            val why = o?.str("reason").orEmpty().ifEmpty { "the tap wasn't on a card's picture" }
            mutable.update { it.copy(message = "No picture found there: $why. Try again on another card's picture.") }
            AppLog.add('I', "lab", "extras directory picture tap: $why")
            return
        }
        val scan = mutable.value.menuGroups.firstOrNull { it.id == id }?.probe?.scan ?: return
        val selectors = o.arr("selectors")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val attrs = o.arr("attrs")?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { a -> a.isNotEmpty() } }.orEmpty()
        AppLog.add('D', "lab", "extras directory picture tap: ${selectors.joinToString(" | ")} attrs ${attrs.joinToString(",")} (${o.str("url").orEmpty().take(120)})")
        scope.launch {
            val (next, why) = try {
                BuilderListReader.withPicture(scan, selectors, attrs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            if (next == null) {
                mutable.update { it.copy(message = "The picture you tapped can't be read for every card: $why.") }
                AppLog.add('I', "lab", "extras directory picture: $why")
                return@launch
            }
            val made = mutable.value.menuGroups.firstOrNull { it.id == id }?.made?.isNotEmpty() == true
            mutable.update { s ->
                s.copy(
                    menuGroups = s.menuGroups.map { g ->
                        if (g.id != id) {
                            g
                        } else {
                            val c = g.choice ?: BuilderExtrasLogic.defaultChoice(g.name, next)
                            g.copy(probe = g.probe?.copy(scan = next), choice = c.copy(fields = c.fields + "picture"))
                        }
                    },
                    message = "Pictures found on the cards" + if (made) ": make the filter again to keep them." else ".",
                    rev = s.rev + 1,
                )
            }
        }
    }

    // ---------- Learn this list (r102) ----------

    /** r102: the WebView loaded an address or its history changed (BuilderScreenModel calls it every time) */
    fun onPageUrl(url: String) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return
        seen.update { SeenUrl(it.tick + 1, u) }
    }

    /** r102 fix: a page finished loading (BuilderScreenModel calls it from the WebView's onPageFinished only) */
    fun onPageFinished(url: String) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return
        // (onPageUrl heard the same address just before: [seen] isn't ticked twice)
        finished.update { SeenUrl(it.tick + 1, u) }
    }

    /**
     * r102 fix: a "learn: …" line of learn.js (BuilderScreenModel.onPickerLog forwards them): "learn: pressed #<id> …",
     * "learn: missing #<id> …" or "learn: error #<id> …" says what became of press <id>.
     */
    fun onLearnLog(line: String) {
        // KMK (r109): learn.js `items`'s answer (a menu opened on the page and its entries read)
        LEARN_ITEMS.find(line)?.let { im ->
            im.groupValues[1].toLongOrNull()?.let { itemsAnswer.value = ItemsAnswer(it, im.groupValues[2]) }
            return
        }
        // KMK (r110): learn.js `count`'s answer (the cards a learned press's page shows)
        LEARN_COUNT.find(line)?.let { cm ->
            val cid = cm.groupValues[1].toLongOrNull()
            val n = cm.groupValues[2].toIntOrNull()
            if (cid != null && n != null) countAnswer.value = Triple(cid, n, cm.groupValues[3].isNotEmpty())
            return
        }
        val m = LEARN_OUTCOME.find(line) ?: return
        val id = m.groupValues[2].toLongOrNull() ?: return
        outcome.value = PressOutcome(id, m.groupValues[1])
    }

    /** r102: the card's "Not now": forget the picked list (a run in progress is stopped) */
    fun dismissLearn() {
        // KMK (r112) fix: during "Do all N" stopLearning belongs to the run's directory: left alone
        if (doAllParent == null) stopLearning = true
        mutable.update { it.copy(learnGroup = null, learn = null, pendings = it.pendings.filterValues { p -> p.kind != "learned" }) }
    }

    /** r102: the card's Stop: the run ends after the current press, keeping what it learned */
    fun stopLearn() {
        if (learnJob?.isActive == true) {
            stopLearning = true
            AppLog.add('D', "lab", "learn: stop asked")
            // KMK (r110) fix: at once on the page: a press waiting for its menu to open is cancelled (no outline/press)
            scope.launch { runCatching { inPage("window.__labLearn&&window.__labLearn.banner&&window.__labLearn.banner(null);") } }
        }
    }

    /** r102: the builder is closed: a run in progress ends at once (BuilderScreenModel.onDispose) */
    fun dispose() {
        stopLearning = true
        learnJob?.cancel()
        learnJob = null
    }

    /**
     * r102: "Learn this list": presses the picked list's entries one at a time on its page and records where each
     * goes, pausing a random [pauseMinMs]..[pauseMaxMs] between presses. After 3 learned addresses a pattern (one
     * parameter or path segment holding the entry's text) fills in the rest without pressing.
     */
    fun startLearn(pauseMinMs: Long, pauseMaxMs: Long) {
        val group = mutable.value.learnGroup ?: return
        if (learnJob?.isActive == true) return
        if (runInPage == null) {
            mutable.update { it.copy(message = "The page isn't ready: open the list page in the builder first") }
            AppLog.add('W', "lab", "learn: no page to press in (runInPage not set)")
            return
        }
        val low = minOf(pauseMinMs, pauseMaxMs).coerceAtLeast(0L)
        val high = maxOf(pauseMinMs, pauseMaxMs).coerceAtLeast(0L)
        stopLearning = false
        learnJob = scope.launch {
            try {
                runLearn(group, low, high)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.add('E', "lab", "learn failed: ${e.message ?: e.javaClass.simpleName}")
                mutable.update { s ->
                    val run = s.learn ?: return@update s
                    s.copy(learn = run.copy(running = false, note = "Learning stopped: ${e.message ?: "an error"}"))
                }
            }
        }
    }

    private suspend fun runLearn(group: LearnGroup, low: Long, high: Long) {
        val rows = group.entries.map { e ->
            LearnRow(BuilderExtrasLogic.cleanLabel(e.text).ifEmpty { e.text }, null, "waiting")
        }.toMutableList()
        val base = group.pageUrl
        var pressed = 0
        var pattern: BuilderExtrasLogic.LearnPattern? = null
        var note: String? = null
        fun publish(running: Boolean) {
            val run = LearnRun(
                name = group.name,
                rows = rows.toList(),
                running = running,
                pattern = pattern?.shown,
                note = note,
                pressed = pressed,
                pauseMinMs = low,
                pauseMaxMs = high,
            )
            // "Not now" / "Make filter" during the run forgot the list: the card stays closed
            mutable.update { if (it.learnGroup == null) it else it.copy(learn = run) }
        }
        publish(true)
        AppLog.add('I', "lab", "learn \"${group.name}\": ${rows.size} entries on $base, pause $low–$high ms, menu button ${group.opener ?: "(none)"}")
        var triedPattern = false
        var verifyLeft = 0
        var stopReason: String? = null
        try {
            for (i in rows.indices) {
                if (stopLearning) {
                    stopReason = "Stopped by you after $pressed presses."
                    break
                }
                if (pressed >= MAX_PRESSES) {
                    stopReason = "Pressed $MAX_PRESSES entries, the most one run presses."
                    break
                }
                if (pressed > 0 && high > 0) {
                    pause(Random.nextLong(low, high + 1))
                    if (stopLearning) {
                        stopReason = "Stopped by you after $pressed presses."
                        break
                    }
                }
                rows[i] = rows[i].copy(status = "pressing")
                publish(true)
                if (!onPage(base) && !loadPage(base)) {
                    rows[i] = rows[i].copy(status = "waiting")
                    stopReason = "The list page didn't load again ($base): learning stopped."
                    AppLog.add('W', "lab", "learn: the list page $base didn't load in ${LOAD_MS / 1000} s")
                    break
                }
                pause(SETTLE_MS)
                if (stopLearning) {
                    rows[i] = rows[i].copy(status = "waiting")
                    stopReason = "Stopped by you after $pressed presses."
                    break
                }
                val from = clean(currentUrl())
                val before = seen.value.tick
                val e = group.entries[i]
                val id = press(group, e)
                pressed++
                // r102 fix: learn.js says whether it found the entry; a missing one is never "no change" (= "Any")
                val answer = waitFor(ANSWER_MS) { outcome.value.takeIf { it.id == id } }
                val gone = waitFor(0L) { seen.value.takeIf { it.tick > before && clean(it.url) != from } }
                val changed = when {
                    gone != null -> gone
                    answer == null || answer.state != "pressed" -> null
                    else -> waitFor(CHANGE_MS) { seen.value.takeIf { it.tick > before && clean(it.url) != from } }
                }
                if (changed == null && stopLearning) {
                    rows[i] = rows[i].copy(status = "waiting")
                    stopReason = "Stopped by you after $pressed presses."
                    break
                }
                if (changed == null && (answer == null || answer.state != "pressed")) {
                    rows[i] = rows[i].copy(status = "missing")
                    val why = answer?.state ?: "no answer from the page"
                    AppLog.add('D', "lab", "learn ${rows[i].text} → missing ($why)")
                } else if (changed == null) {
                    rows[i] = rows[i].copy(status = "nochange")
                    AppLog.add('D', "lab", "learn ${rows[i].text} → no change")
                } else {
                    rows[i] = rows[i].copy(status = "learned", url = changed.url.substringBefore('#'))
                    AppLog.add('D', "lab", "learn ${rows[i].text} → ${changed.url}")
                }
                // r105: a pattern being checked: the next presses must land where it says (codes such as "multi" for
                // "Multiple" never follow the texts: then every entry is pressed)
                val checking = pattern
                if (checking != null && rows[i].status == "learned") {
                    val expected = BuilderExtrasLogic.fillPattern(checking, rows[i].text)
                    if (expected == null || clean(expected) != clean(rows[i].url.orEmpty())) {
                        AppLog.add('D', "lab", "learn: pattern ${checking.shown} wrong for ${rows[i].text} (${rows[i].url}): pressing all")
                        pattern = null
                        verifyLeft = 0
                    } else {
                        verifyLeft--
                    }
                }
                val confirmed = pattern
                if (confirmed != null && verifyLeft <= 0) {
                    for (k in rows.indices) {
                        if (rows[k].status != "waiting") continue
                        val url = BuilderExtrasLogic.fillPattern(confirmed, rows[k].text)
                        rows[k] = rows[k].copy(status = if (url == null) "missed" else "filled", url = url)
                    }
                    publish(true)
                    AppLog.add('D', "lab", "learn: pattern ${confirmed.shown} checked on $PATTERN_CHECKS more entries")
                    break
                }
                val learned = rows.filter { it.status == "learned" && it.url != null }
                // r105: lists of up to PRESS_ALL entries are pressed entry by entry (each code as the site sends it)
                if (!triedPattern && rows.size > PRESS_ALL && learned.size >= PATTERN_AFTER) {
                    triedPattern = true
                    val found = BuilderExtrasLogic.findPattern(learned.map { it.text to it.url.orEmpty() })
                    if (found != null) {
                        pattern = found
                        verifyLeft = PATTERN_CHECKS
                        AppLog.add('D', "lab", "learn: pattern ${found.shown} after $PATTERN_AFTER entries, checking $PATTERN_CHECKS more")
                    } else {
                        AppLog.add('D', "lab", "learn: no pattern in the first $PATTERN_AFTER addresses, pressing all")
                    }
                }
                publish(true)
            }
        } finally {
            val learned = rows.count { it.status == "learned" || it.status == "filled" }
            note = when {
                stopReason != null -> stopReason
                pattern != null && verifyLeft <= 0 ->
                    "Pattern found after $PATTERN_AFTER and checked on $PATTERN_CHECKS more: ${pattern?.shown} — the rest are filled in without pressing."
                learned == 0 -> "No entry changed the address: this list can't be learned (the site changes the page without a new address)."
                else -> "Pressed all ${rows.count { it.status == "learned" || it.status == "nochange" }}: no pattern, each address is the one seen." +
                    rows.count { it.status == "missing" }.let { m -> if (m > 0) " $m not found on the page." else "" }
            }
            publish(false)
            val how = pattern?.let { "pattern ${it.shown}" } ?: "pressed all"
            AppLog.add('I', "lab", "learn: $learned entries, $how" + (stopReason?.let { " ($it)" } ?: ""))
        }
        // Back to the list page, so the page shows what was learned from
        if (!onPage(base)) runCatching { loadPage(base) }
    }

    /**
     * r106: what the learning run would make, without adding it (the card's "Learned" view: the address name, every
     * code, Works on, and Replace / Keep both when [BuilderExtrasLogic.conflicts] finds filters with its name); null =
     * nothing to make yet
     */
    fun learnedPreview(): ExtrasFilter? {
        val s = mutable.value
        val run = s.learn ?: return null
        val group = s.learnGroup ?: return null
        // r105: only the entries that changed the address give the parameter and its codes: the list page itself
        // ("All", no change) has none of the dropdown's other parameters (sort=recent…), so it would hide which one
        // the entries change; it becomes the empty first option below
        val entries = run.rows.mapNotNull { r ->
            when (r.status) {
                "learned", "filled" -> r.url?.let { BuilderExtrasLogic.GroupEntry(r.text, it, null) }
                else -> null
            }
        }
        val derived = BuilderExtrasLogic.deriveGroup(
            entries = entries,
            tappedText = "",
            tappedHref = null,
            pageUrl = group.pageUrl,
            rule = s.pageRule,
            heading = group.name,
            offers = s.offers,
        )
        // A "no change" entry ("All") names the empty option. r105 fix: a Sort's too ("Release date" when the list page
        // already shows that order): picking it sends nothing, the site's own order
        val anyLabel = run.rows.firstOrNull { it.status == "nochange" }?.text
        // KMK (r109): every press changed the address: the entry the menu's button shows ("Sort: Recently added") is the
        // site's default: it becomes the empty first option under its own name (its code dropped), never "Site default"
        // KMK (r109) fix: only while the learning page's address has no value for the learned parameter (on
        // ?sort=views the active entry is that pick, not the site's default)
        val learnedParam = derived.filter?.takeIf { it.mode != "path" }?.param?.trim().orEmpty()
        val pageHasValue = learnedParam.isNotEmpty() &&
            group.pageUrl.trim().toHttpUrlOrNull()?.queryParameter(learnedParam).isNullOrEmpty().not()
        val currentRow = if (anyLabel == null && group.current.isNotBlank() && !pageHasValue) {
            run.rows.firstOrNull { it.text.equals(group.current, ignoreCase = true) && (it.status == "learned" || it.status == "filled") }?.text
        } else {
            null
        }
        val defaultLabel = anyLabel ?: currentRow
        return derived.filter?.let { f ->
            if (defaultLabel == null) {
                f
            } else {
                val base = if (currentRow == null) f.options else f.options.filterNot { it.value.isNotEmpty() && it.label.equals(currentRow, ignoreCase = true) }
                val withEmpty = if (f.type == "sort") BuilderExtrasLogic.withSiteDefault(base) else BuilderExtrasLogic.withAny(base)
                val options = withEmpty.map { if (it.value.isEmpty()) it.copy(label = defaultLabel) else it }
                f.copy(options = options, optionsText = BuilderExtrasLogic.formatOptions(options), siteDefault = defaultLabel)
            }
        }?.let { f ->
            // r106: a sub-menu learned on its card sits in its parent menu's group
            val parent = s.menuGroups.firstOrNull { it.id == group.menuId }?.headingPath?.dropLast(1)?.lastOrNull()
                ?.let(BuilderExtrasLogic::cleanHeading).orEmpty()
            if (parent.isNotEmpty() && f.group.isEmpty()) f.copy(group = parent) else f
        }
    }

    /** r106: the filters the learned one would replace or stand beside ([learnedPreview]'s Replace "<name>") */
    fun learnedConflicts(made: ExtrasFilter): List<ExtrasFilter> {
        val texts = mutable.value.learn?.rows?.map { it.text }?.toSet().orEmpty()
        return BuilderExtrasLogic.conflicts(mutable.value.filters, made) { old -> textsOnly(old, texts) }
    }

    /** the texts-only filter made when the list was picked (r102): the learned one replaces it */
    private fun textsOnly(old: ExtrasFilter, texts: Set<String>): Boolean =
        old.askParam && old.param.isBlank() && old.options.count { it.label in texts } >= 2

    /**
     * "Make "Year" filter": what was learned becomes a filter (the r101 derivation: param / path / sort). r106: [role]
     * the card's Works on ("extra" = Any list, "page" = This page only: its own page with the code; null = as derived),
     * [onSearch] its Search too, [onConflict] Replace (true) / Keep both (false) / ask (null) when filters have its name
     */
    fun makeLearnedFilter(role: String? = null, onSearch: Boolean? = null, onConflict: Boolean? = null) {
        val s = mutable.value
        val group = s.learnGroup ?: return
        val run = s.learn ?: return
        // KMK (r112) fix: during "Do all N" stopLearning belongs to the run's directory: left alone
        if (doAllParent == null) stopLearning = true
        val derivedFilter = learnedPreview()
        val made = derivedFilter?.let { f ->
            var out = f
            if (role != null && role != f.role && (role != "page" || f.mode == "path" || "{value}" in f.linkPath)) {
                out = out.copy(role = role, roleSet = true)
            }
            if (onSearch != null) out = out.copy(onSearch = onSearch)
            out
        }
        made?.let { f ->
            AppLog.add(
                'I',
                "lab",
                "learn \"${group.name}\": " + if (f.role == "page") {
                    "the address's path changes → a page filter (${f.path.ifEmpty { f.linkPath }})"
                } else {
                    "only ?${f.param}= changes → an extra on any list page, codes " +
                        f.options.filter { it.value.isNotEmpty() }.take(6).joinToString(", ") { "${it.label}=${it.value}" }
                },
            )
        }
        val texts = run.rows.map { it.text }.toSet()
        val derived = BuilderExtrasLogic.GroupDerived(made, if (made == null) "No entry of \"${group.name}\" changed the address: no filter was made." else "", null, "", made?.options?.size ?: 0, "learned")
        val added = applyDerived(
            derived,
            "the learned list \"${group.name}\"",
            replaces = { old -> textsOnly(old, texts) },
            onConflict = onConflict,
            menuId = group.menuId,
            kind = "learned",
        )
        afterMade(added, group.menuId, "learned")
    }

    private fun currentUrl(): String = seen.value.url.ifEmpty { mutable.value.lastUrl.orEmpty() }

    private fun clean(url: String): String = url.trim().substringBefore('#').trimEnd('/')

    private fun onPage(url: String): Boolean = clean(currentUrl()) == clean(url)

    /**
     * Loads [url] in the WebView and waits until it finished loading (r102 fix: onPageFinished, not the first history
     * event, which comes before the page's scripts ran), or any page finished, when the site redirects
     */
    private suspend fun loadPage(url: String): Boolean {
        val before = finished.value.tick
        inPage("location.assign(${JsonPrimitive(url)});")
        val loaded = waitFor(LOAD_MS) { finished.value.takeIf { it.tick > before && clean(it.url) == clean(url) } }
        if (loaded != null) return true
        return finished.value.tick > before
    }

    /**
     * r102 fix: waits up to [ms] for [find] to give a value, looking every 200 ms, and gives up at once when Stop was
     * pressed (so Stop answers within a fifth of a second); [ms] 0 = look once
     */
    private suspend fun <T : Any> waitFor(ms: Long, find: () -> T?): T? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            find()?.let { return it }
            val left = end - System.currentTimeMillis()
            if (left <= 0L || stopLearning) return null
            delay(minOf(left, WAIT_STEP_MS))
        }
    }

    /**
     * Presses [entry] through learn.js, waiting up to 5 s for learn.js to be injected after a load; returns the press
     * id learn.js reports the outcome under ([onLearnLog])
     */
    private suspend fun press(group: LearnGroup, entry: LearnEntry, banner: String? = null): Long {
        val id = ++pressId
        val request = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                // KMK (r110): "Learn the menus" shows it at the page's top and outlines what it presses
                "banner" to (banner?.let { JsonPrimitive(it) } ?: JsonNull),
                "opener" to (group.opener?.let { JsonPrimitive(it) } ?: JsonNull),
                // KMK (r109): the menu's button found by its label ("Sort:") when [opener] finds nothing
                "label" to JsonPrimitive(group.openerLabel),
                "entry" to JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(entry.path ?: group.likeSelector),
                        "text" to JsonPrimitive(entry.text),
                    ),
                ),
            ),
        )
        inPage(
            "(function(){var r=$request;function go(k){if(window.__labLearn){window.__labLearn.press(r);}" +
                "else if(k<50){setTimeout(function(){go(k+1);},100);}}go(0);})();",
        )
        return id
    }

    private suspend fun inPage(script: String) {
        val push = runInPage ?: return
        withContext(Dispatchers.Main) { push(script) }
    }

    private suspend fun pause(ms: Long) {
        var left = ms
        while (left > 0 && !stopLearning) {
            val step = minOf(left, WAIT_STEP_MS)
            delay(step)
            left -= step
        }
    }

    // ---------- Saved with the source (r102: Edit by tapping) ----------

    /** r102: what the owner set here, for the builder state saved with the source ([restore] reads it back) */
    fun snapshot(): JsonElement {
        val s = mutable.value
        fun str(v: String?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
        return JsonObject(
            mapOf(
                "v" to JsonPrimitive(1),
                "searchWord" to JsonPrimitive(s.searchWord),
                "searchPath" to str(s.searchPath),
                "searchFrom" to str(s.searchFrom),
                "searchFromForm" to JsonPrimitive(s.searchFromForm),
                "popularPath" to str(s.popularPath),
                "popularName" to JsonPrimitive(s.popularName),
                "latestPath" to str(s.latestPath),
                "latestName" to JsonPrimitive(s.latestName),
                "tabs" to JsonArray(s.tabs.map { JsonObject(mapOf("name" to JsonPrimitive(it.name), "path" to JsonPrimitive(it.path))) }),
                "filters" to JsonArray(
                    s.filters.map { f ->
                        JsonObject(
                            mapOf(
                                "key" to JsonPrimitive(f.key),
                                "name" to JsonPrimitive(f.name),
                                "type" to JsonPrimitive(f.type),
                                "optionsText" to JsonPrimitive(f.optionsText),
                                // r107: a collection entry's parts too
                                "options" to JsonArray(f.options.map { optionJson(it) }),
                                "onValue" to JsonPrimitive(f.onValue),
                                "mode" to JsonPrimitive(f.mode),
                                "param" to JsonPrimitive(f.param),
                                "path" to JsonPrimitive(f.path),
                                "askParam" to JsonPrimitive(f.askParam),
                                "linkPath" to JsonPrimitive(f.linkPath),
                                "noLinkAddress" to JsonPrimitive(f.noLinkAddress),
                                "role" to JsonPrimitive(f.role),
                                "group" to JsonPrimitive(f.group),
                                "onSearch" to JsonPrimitive(f.onSearch),
                                "seenOn" to JsonPrimitive(f.seenOn),
                                "roleSet" to JsonPrimitive(f.roleSet),
                                "collection" to (f.collection?.let { collectionJson(it) } ?: JsonNull),
                                // KMK (r109): the site's own default option's name
                                "siteDefault" to JsonPrimitive(f.siteDefault),
                            ),
                        )
                    },
                ),
                "qualitySetting" to JsonPrimitive(s.qualitySetting),
                "serverSetting" to JsonPrimitive(s.serverSetting),
                "hideWordsSetting" to JsonPrimitive(s.hideWordsSetting),
                "custom" to JsonArray(
                    s.custom.map { c ->
                        JsonObject(
                            mapOf(
                                "key" to JsonPrimitive(c.key),
                                "name" to JsonPrimitive(c.name),
                                "type" to JsonPrimitive(c.type),
                                "optionsText" to JsonPrimitive(c.optionsText),
                            ),
                        )
                    },
                ),
                "pageRule" to (s.pageRule?.let { JsonObject(mapOf("kind" to JsonPrimitive(it.kind), "name" to JsonPrimitive(it.name))) } ?: JsonNull),
                "dismissed" to JsonArray(synchronized(dismissed) { dismissed.toList() }.map { JsonPrimitive(it) }),
            ),
        )
    }

    /** r107: an option as saved with the builder: label, value and a collection entry's parts when it has them */
    private fun optionJson(o: ExtrasOption): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["label"] = JsonPrimitive(o.label)
        m["value"] = JsonPrimitive(o.value)
        if (o.number.isNotEmpty()) m["number"] = JsonPrimitive(o.number)
        if (o.videos.isNotEmpty()) m["videos"] = JsonPrimitive(o.videos)
        if (o.views.isNotEmpty()) m["views"] = JsonPrimitive(o.views)
        if (o.likes.isNotEmpty()) m["likes"] = JsonPrimitive(o.likes)
        if (o.picture.isNotEmpty()) m["picture"] = JsonPrimitive(o.picture)
        return JsonObject(m)
    }

    private fun optionOf(op: JsonObject): ExtrasOption = ExtrasOption(
        label = op.str("label").orEmpty(),
        value = op.str("value").orEmpty(),
        number = op.str("number").orEmpty(),
        videos = op.str("videos").orEmpty(),
        views = op.str("views").orEmpty(),
        likes = op.str("likes").orEmpty(),
        picture = op.str("picture").orEmpty(),
    )

    /** r107: a collection's spec as saved with the builder ([collectionOf] reads it back) */
    private fun collectionJson(c: CollectionSpec): JsonObject {
        fun plain(o: ExtrasOption) = JsonObject(mapOf("label" to JsonPrimitive(o.label), "value" to JsonPrimitive(o.value)))
        return JsonObject(
            mapOf(
                "fields" to JsonArray(c.fields.map { JsonPrimitive(it) }),
                "sorts" to JsonArray(c.sorts.map(::plain)),
                "reverse" to JsonPrimitive(c.reverse),
                "menus" to JsonArray(
                    c.menus.map { m ->
                        JsonObject(mapOf("key" to JsonPrimitive(m.key), "name" to JsonPrimitive(m.name), "options" to JsonArray(m.options.map(::plain))))
                    },
                ),
                "live" to JsonPrimitive(c.live),
                "total" to JsonPrimitive(c.total),
                "first" to JsonPrimitive(c.first),
                "page" to JsonPrimitive(c.page),
                "pages" to JsonPrimitive(c.pages),
                "item" to JsonPrimitive(c.item),
                "link" to JsonPrimitive(c.link),
                "parts" to JsonArray(
                    c.parts.map { p ->
                        JsonObject(
                            mapOf(
                                "kind" to JsonPrimitive(p.kind),
                                "selector" to JsonPrimitive(p.selector),
                                "example" to JsonPrimitive(p.example),
                                "attr" to JsonPrimitive(p.attr),
                                "own" to JsonPrimitive(p.own),
                                "bare" to JsonPrimitive(p.bare),
                            ),
                        )
                    },
                ),
                "sortParam" to JsonPrimitive(c.sortParam),
                "orderParam" to JsonPrimitive(c.orderParam),
                "ascValue" to JsonPrimitive(c.ascValue),
                "descValue" to JsonPrimitive(c.descValue),
            ),
        )
    }

    private fun collectionOf(o: JsonObject?): CollectionSpec? {
        if (o == null) return null
        fun plains(a: JsonArray?) = a?.mapNotNull { e -> (e as? JsonObject)?.let { ExtrasOption(it.str("label").orEmpty(), it.str("value").orEmpty()) } }.orEmpty()
        return CollectionSpec(
            fields = o.arr("fields")?.mapNotNull { (it as? JsonPrimitive)?.content }?.ifEmpty { null } ?: listOf("title"),
            sorts = plains(o.arr("sorts")),
            reverse = o.bool("reverse") == true,
            menus = o.arr("menus")?.mapNotNull { e ->
                val m = e as? JsonObject ?: return@mapNotNull null
                CollectionMenu(m.str("key").orEmpty(), m.str("name").orEmpty(), plains(m.arr("options")))
            }.orEmpty().filter { it.key.isNotEmpty() },
            live = o.bool("live") == true,
            total = o.int("total") ?: 0,
            first = o.str("first").orEmpty(),
            page = o.str("page").orEmpty(),
            pages = o.int("pages") ?: 1,
            item = o.str("item").orEmpty(),
            link = o.str("link").orEmpty(),
            parts = o.arr("parts")?.mapNotNull { e ->
                val p = e as? JsonObject ?: return@mapNotNull null
                BuilderListReader.DirPart(
                    kind = p.str("kind").orEmpty(),
                    selector = p.str("selector").orEmpty(),
                    example = p.str("example").orEmpty(),
                    attr = p.str("attr").orEmpty(),
                    own = p.bool("own") == true,
                    bare = p.bool("bare") == true,
                )
            }.orEmpty().filter { it.kind.isNotEmpty() },
            sortParam = o.str("sortParam").orEmpty(),
            orderParam = o.str("orderParam").orEmpty(),
            ascValue = o.str("ascValue").orEmpty(),
            descValue = o.str("descValue").orEmpty(),
        )
    }

    /** r102: puts back what [snapshot] saved (missing parts keep their defaults); the fields re-read their text */
    fun restore(json: JsonElement) {
        val o = json as? JsonObject
        if (o == null) {
            AppLog.add('W', "lab", "extras restore: not an object")
            return
        }
        val tabs = o.arr("tabs")?.mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            ExtrasTab(t.str("name").orEmpty(), t.str("path").orEmpty())
        }.orEmpty()
        val filters = o.arr("filters")?.mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            val options = f.arr("options")?.mapNotNull { oe -> (oe as? JsonObject)?.let { optionOf(it) } }.orEmpty()
            val key = f.str("key").orEmpty()
            val mode = f.str("mode") ?: "param"
            val askParam = f.bool("askParam") == true
            val type = f.str("type") ?: "select"
            // r104 fix: saved before r104 (no linkPath key) from a menu's links: a param filter with its options
            val oldLinks = f.bool("noLinkAddress") == true || (
                "linkPath" !in f && mode == "param" && !askParam && (type == "select" || type == "sort") &&
                    options.count { it.value.isNotEmpty() } >= 2 && !f.str("param").isNullOrBlank()
                )
            ExtrasFilter(
                key = key,
                name = f.str("name").orEmpty(),
                type = type,
                options = options,
                onValue = f.str("onValue") ?: "1",
                optionsText = f.str("optionsText") ?: BuilderExtrasLogic.formatOptions(options),
                mode = mode,
                param = f.str("param") ?: key,
                path = f.str("path").orEmpty(),
                askParam = askParam,
                linkPath = f.str("linkPath").orEmpty(),
                noLinkAddress = oldLinks,
                role = f.str("role").orEmpty().takeIf { it == "page" || it == "extra" }.orEmpty(),
                group = f.str("group").orEmpty(),
                onSearch = f.bool("onSearch") == true,
                seenOn = f.str("seenOn").orEmpty(),
                roleSet = f.bool("roleSet") == true,
                collection = collectionOf(f.obj("collection")),
                siteDefault = f.str("siteDefault").orEmpty(),
            )
        }.orEmpty().map { f ->
            // r107: a directory saved before r107 with whole cards as names (or as a Sort) comes back cleaned
            BuilderExtrasLogic.repairLabels(f)?.also { r ->
                AppLog.add('I', "lab", "extras filter \"${f.name}\": names cleaned (${f.options.firstOrNull { it.value.isNotEmpty() }?.label?.take(60)} → ${r.options.firstOrNull { it.value.isNotEmpty() }?.label}), ${f.type} → ${r.type}")
            } ?: f
        }.map { f ->
            // KMK (r109): a "Site default" first option whose learned default is known is named after it
            BuilderExtrasLogic.repairSiteDefault(f)?.also { r ->
                AppLog.add('I', "lab", "extras filter \"${f.name}\": \"${BuilderExtrasLogic.SITE_DEFAULT}\" → \"${r.options.firstOrNull()?.label}\"")
            } ?: f
        }.let { all ->
            // KMK (r110) repair: a directory's menus (Height / Cup / Age picked on its page) saved as top-level filters
            // are dropped: they belong to that collection only
            val (kept, dropped) = BuilderExtrasLogic.dropDirectoryMenus(all, dirMenuLabels(mutable.value.menuGroups))
            if (dropped.isNotEmpty()) {
                AppLog.add('I', "lab", "extras filters ${dropped.joinToString(", ") { "\"${it.name}\" (${it.param})" }}: a directory's own menus, dropped from the top-level filters")
            }
            kept
        }
        val custom = o.arr("custom")?.mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val text = c.str("optionsText").orEmpty()
            ExtrasCustomSetting(
                key = c.str("key").orEmpty(),
                name = c.str("name").orEmpty(),
                type = c.str("type") ?: "switch",
                options = text.lines().map { it.trim() }.filter { it.isNotEmpty() },
                optionsText = text,
            )
        }.orEmpty()
        val rule = o.obj("pageRule")?.let { r -> r.str("kind")?.let { PageRule(it, r.str("name").orEmpty()) } }
        o.arr("dismissed")?.forEach { e -> (e as? JsonPrimitive)?.content?.let { dismissed.add(it) } }
        mutable.update { s ->
            s.copy(
                searchWord = o.str("searchWord") ?: s.searchWord,
                searchPath = o.str("searchPath"),
                searchFrom = o.str("searchFrom"),
                searchFromForm = o.bool("searchFromForm") == true,
                popularPath = o.str("popularPath"),
                popularName = o.str("popularName").orEmpty(),
                latestPath = o.str("latestPath"),
                latestName = o.str("latestName").orEmpty(),
                tabs = tabs,
                filters = filters,
                qualitySetting = o.bool("qualitySetting") == true,
                serverSetting = o.bool("serverSetting") == true,
                hideWordsSetting = o.bool("hideWordsSetting") == true,
                custom = custom,
                pageRule = rule,
                rev = s.rev + 1,
            )
        }
        AppLog.add('I', "lab", "extras restored: ${tabs.size} tab(s), ${filters.size} filter(s), ${custom.size} setting(s)")
    }

    // ---------- Tabs ----------

    fun setPopularName(name: String) = mutable.update { it.copy(popularName = name) }

    fun setLatestName(name: String) = mutable.update { it.copy(latestName = name) }

    fun setPopularPath(path: String) = mutable.update { it.copy(popularPath = path.ifBlank { null }) }

    fun setLatestPath(path: String) = mutable.update { it.copy(latestPath = path.ifBlank { null }) }

    /** "Use this page" on the Popular row */
    fun usePageForPopular(pageUrl: String?) {
        val result = pagePath(pageUrl) ?: return
        mutable.update { it.copy(popularPath = result.path, rev = it.rev + 1, message = noteFor(result)) }
        AppLog.add('I', "lab", "extras tab \"${state.value.popularName.ifBlank { "Popular" }}\" = ${result.path}")
    }

    /** "Use this page" on the Latest row */
    fun usePageForLatest(pageUrl: String?) {
        val result = pagePath(pageUrl) ?: return
        mutable.update { it.copy(latestPath = result.path, rev = it.rev + 1, message = noteFor(result)) }
        AppLog.add('I', "lab", "extras tab \"${state.value.latestName.ifBlank { "Latest" }}\" = ${result.path}")
    }

    /** "Add a tab": [name] (blank → the path's last word) showing the current page */
    fun addTab(name: String, pageUrl: String?) {
        val result = pagePath(pageUrl) ?: return
        val shown = name.trim().ifEmpty { nameFromPath(result.path) }
        mutable.update { it.copy(tabs = it.tabs + ExtrasTab(shown, result.path), rev = it.rev + 1, message = noteFor(result)) }
        AppLog.add('I', "lab", "extras tab \"$shown\" = ${result.path}")
    }

    fun useThisPageForTab(index: Int, pageUrl: String?) {
        val result = pagePath(pageUrl) ?: return
        var name: String? = null
        mutable.update { s ->
            val tab = s.tabs.getOrNull(index) ?: return@update s
            name = tab.name
            s.copy(tabs = s.tabs.toMutableList().also { it[index] = tab.copy(path = result.path) }, rev = s.rev + 1, message = noteFor(result))
        }
        name?.let { AppLog.add('I', "lab", "extras tab \"$it\" = ${result.path}") }
    }

    fun setTabName(index: Int, name: String) = updateTab(index) { it.copy(name = name) }

    fun setTabPath(index: Int, path: String) = updateTab(index) { it.copy(path = path) }

    fun removeTab(index: Int) = mutable.update { s ->
        if (index !in s.tabs.indices) s else s.copy(tabs = s.tabs.filterIndexed { i, _ -> i != index }, rev = s.rev + 1)
    }

    private fun updateTab(index: Int, change: (ExtrasTab) -> ExtrasTab) = mutable.update { s ->
        val tab = s.tabs.getOrNull(index) ?: return@update s
        s.copy(tabs = s.tabs.toMutableList().also { it[index] = change(tab) })
    }

    // ---------- Filters ----------

    /** "Make this a filter": a dropdown from the values seen (the page's options replace them when they come) */
    fun acceptOffer(param: String) {
        var made: ExtrasFilter? = null
        mutable.update { s ->
            made = null
            val offer = s.offers.firstOrNull { it.param == param } ?: return@update s
            val fromPage = s.optionsFor[param].orEmpty()
            val options = BuilderExtrasLogic.withAny(fromPage.ifEmpty { offer.seen.map { ExtrasOption(it, it) } })
            val filter = ExtrasFilter(key = param, name = BuilderExtrasLogic.prettyName(param), type = "select", options = options)
            made = filter
            s.copy(
                offers = s.offers.filter { it.param != param },
                filters = s.filters.filter { it.key != param } + filter,
                rev = s.rev + 1,
            )
        }
        made?.let { AppLog.add('I', "lab", "extras filter ${it.key} (${it.type}, ${it.options.size} options)") }
    }

    fun dismissOffer(param: String) {
        dismissed.add(param)
        mutable.update { s -> s.copy(offers = s.offers.filter { it.param != param }) }
        AppLog.add('D', "lab", "extras filter offer $param dismissed")
    }

    /** "Add a filter by hand": a dropdown whose address parameter the owner types (r101: no "filter1" default) */
    fun addFilter() {
        mutable.update { s ->
            val n = s.filters.size + 1
            s.copy(
                filters = s.filters + ExtrasFilter(
                    key = "",
                    name = "Filter $n",
                    type = "select",
                    options = listOf(ExtrasOption("Any", "")),
                    param = "",
                    askParam = true,
                ),
                rev = s.rev + 1,
            )
        }
        AppLog.add('I', "lab", "extras filter added by hand (select, parameter to type)")
    }

    fun setFilterKey(index: Int, key: String) = updateFilter(index) { it.copy(key = key.trim()) }

    /** r101: the address parameter of a param filter; its key follows it (unique among the filters) */
    fun setFilterParam(index: Int, param: String) = mutable.update { s ->
        val f = s.filters.getOrNull(index) ?: return@update s
        val p = param.trim()
        val others = s.filters.filterIndexed { i, _ -> i != index }.map { it.key }
        val key = if (p.isEmpty()) "" else BuilderExtrasLogic.uniqueKey(p, others)
        s.copy(filters = s.filters.toMutableList().also { it[index] = f.copy(param = p, key = key) })
    }

    fun setFilterName(index: Int, name: String) = updateFilter(index) { it.copy(name = name) }

    fun setFilterType(index: Int, type: String) {
        // r103 fix: Dropdown gets its empty "Any" option back first; a text box with suggestions drops it (rev: the
        // options editor shows the new lines)
        mutable.update { s ->
            val f = s.filters.getOrNull(index) ?: return@update s
            s.copy(filters = s.filters.toMutableList().also { it[index] = BuilderExtrasLogic.withType(f, type) }, rev = s.rev + 1)
        }
        state.value.filters.getOrNull(index)?.let { f ->
            AppLog.add('I', "lab", "extras filter ${f.key} (${f.type}, ${f.options.size} options)")
        }
    }

    fun setFilterOptionsText(index: Int, text: String) =
        updateFilter(index) {
            // r107: a collection's entries keep their parts (by value)
            val parsed = BuilderExtrasLogic.parseOptions(text)
            it.copy(optionsText = text, options = if (it.collection != null) BuilderExtrasLogic.keepParts(it.options, parsed) else parsed)
        }

    /** r107: a collection's "Live, as you scroll" in the filter editor (only when its directory has page addresses) */
    fun setCollectionLive(index: Int, on: Boolean) = updateFilter(index) { f ->
        val c = f.collection ?: return@updateFilter f
        f.copy(collection = c.copy(live = on && c.item.isNotEmpty() && (c.page.isNotEmpty() || c.pages <= 1)))
    }

    /** r107: a part kept or not in a collection's drop-down (the name always is) */
    fun setCollectionField(index: Int, field: String, on: Boolean) = updateFilter(index) { f ->
        val c = f.collection ?: return@updateFilter f
        if (field == "title") return@updateFilter f
        val fields = BuilderExtrasLogic.COLLECTION_FIELDS.filter { it == "title" || (if (it == field) on else it in c.fields) }
        f.copy(collection = c.copy(fields = fields))
    }

    fun setFilterOnValue(index: Int, value: String) = updateFilter(index) { it.copy(onValue = value) }

    /**
     * r104: the filter card's ▲ / ▼: the order is the source's (filtersList, the Browse sheet). r105 fix: a group's
     * filters stay together; past its group's edge a filter leaves it, next to a group it joins it
     * ([BuilderExtrasLogic.moveInGroups])
     */
    fun moveFilter(index: Int, delta: Int) {
        var moved: String? = null
        mutable.update { s ->
            moved = null
            val list = BuilderExtrasLogic.moveInGroups(s.filters, index, delta) ?: return@update s
            val before = s.filters[index]
            val after = list.firstOrNull { it.key == before.key && it.name == before.name } ?: before
            moved = when {
                after.group == before.group -> if (delta < 0) "up" else "down"
                after.group.isEmpty() -> "out of the group \"${before.group}\""
                else -> "into the group \"${after.group}\""
            }
            s.copy(filters = list, rev = s.rev + 1)
        }
        moved?.let { AppLog.add('D', "lab", "extras filter ${index + 1} moved $it") }
    }

    /**
     * r105: the filter card's role: "page" (picks the list; only for a filter with an address: a path, or links to
     * another page) or "extra" (its parameter added to whichever list is chosen)
     */
    fun setFilterRole(index: Int, role: String) {
        updateFilter(index) { f ->
            if (role == "page" && f.mode != "path" && "{value}" !in f.linkPath) f else f.copy(role = role, roleSet = true)
        }
        state.value.filters.getOrNull(index)?.let { AppLog.add('I', "lab", "extras filter ${it.key} role ${it.role.ifEmpty { "(as before r105)" }}") }
    }

    /** r105: "Also while searching for words" of an extra */
    fun setFilterOnSearch(index: Int, on: Boolean) = updateFilter(index) { it.copy(onSearch = on) }

    /**
     * r105: "Move to group…" / "Out of group": [group] "" = out of its group. A filter moved into a group goes right
     * after that group's last filter, so the group stays together in the Browse sheet
     */
    fun setFilterGroup(index: Int, group: String) {
        val name = group.trim().take(40)
        mutable.update { s ->
            val f = s.filters.getOrNull(index) ?: return@update s
            val moved = f.copy(group = name)
            val list = s.filters.toMutableList()
            list.removeAt(index)
            val last = if (name.isEmpty()) -1 else list.indexOfLast { it.group == name }
            if (last >= 0) list.add(last + 1, moved) else list.add(index.coerceAtMost(list.size), moved)
            s.copy(filters = list, rev = s.rev + 1)
        }
        AppLog.add('I', "lab", "extras filter ${index + 1} " + if (name.isEmpty()) "out of its group" else "in the group \"$name\"")
    }

    /** r105: the group names the filters have, in order (for "Move to group…") */
    fun groupNames(): List<String> = state.value.filters.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct()

    fun removeFilter(index: Int) = mutable.update { s ->
        if (index !in s.filters.indices) s else s.copy(filters = s.filters.filterIndexed { i, _ -> i != index }, rev = s.rev + 1)
    }

    private fun updateFilter(index: Int, change: (ExtrasFilter) -> ExtrasFilter) = mutable.update { s ->
        val f = s.filters.getOrNull(index) ?: return@update s
        s.copy(filters = s.filters.toMutableList().also { it[index] = change(f) })
    }

    // ---------- Settings ----------

    fun setQualitySetting(on: Boolean) {
        mutable.update { it.copy(qualitySetting = on) }
        if (on) AppLog.add('I', "lab", "extras setting quality")
    }

    fun setServerSetting(on: Boolean) {
        mutable.update { it.copy(serverSetting = on) }
        if (on) AppLog.add('I', "lab", "extras setting server")
    }

    fun setHideWordsSetting(on: Boolean) {
        mutable.update { it.copy(hideWordsSetting = on) }
        if (on) AppLog.add('I', "lab", "extras setting hideWords")
    }

    fun addCustomSetting() {
        var key = ""
        mutable.update { s ->
            var n = s.custom.size + 1
            while (s.custom.any { it.key == "setting$n" }) n++
            key = "setting$n"
            s.copy(custom = s.custom + ExtrasCustomSetting(key = key, name = "Setting $n", type = "switch"), rev = s.rev + 1)
        }
        AppLog.add('I', "lab", "extras setting $key")
    }

    fun setCustomKey(index: Int, key: String) = updateCustom(index) { it.copy(key = key.trim()) }

    fun setCustomName(index: Int, name: String) = updateCustom(index) { it.copy(name = name) }

    fun setCustomType(index: Int, type: String) = updateCustom(index) { it.copy(type = type) }

    fun setCustomOptionsText(index: Int, text: String) = updateCustom(index) {
        it.copy(optionsText = text, options = text.lines().map { line -> line.trim() }.filter { line -> line.isNotEmpty() })
    }

    fun removeCustomSetting(index: Int) = mutable.update { s ->
        if (index !in s.custom.indices) s else s.copy(custom = s.custom.filterIndexed { i, _ -> i != index }, rev = s.rev + 1)
    }

    private fun updateCustom(index: Int, change: (ExtrasCustomSetting) -> ExtrasCustomSetting) = mutable.update { s ->
        val c = s.custom.getOrNull(index) ?: return@update s
        s.copy(custom = s.custom.toMutableList().also { it[index] = change(c) })
    }

    fun clearMessage() = mutable.update { it.copy(message = null) }

    // ---------- helpers ----------

    private fun pagePath(pageUrl: String?): BuilderExtrasLogic.PathResult? {
        val url = (pageUrl ?: mutable.value.lastUrl)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            mutable.update { it.copy(message = "No page is open yet: open the list on the site first") }
            return null
        }
        return BuilderExtrasLogic.pathOf(url, siteFor(url), mutable.value.pageRule)
    }

    /** The "no page number" guess note, only while there is no Next pick (with one, the rule is used) */
    private fun noteFor(result: BuilderExtrasLogic.PathResult): String? =
        if (result.addedPage && mutable.value.pageRule == null) BuilderExtrasLogic.NO_PAGE_MESSAGE else null

    private fun nameFromPath(path: String): String {
        val word = path.substringBefore('?').trim('/').substringAfterLast('/')
            .let { if (it == "{page}" || it.all(Char::isDigit)) path.substringBefore('?').trim('/').split('/').dropLast(2).lastOrNull().orEmpty() else it }
        return BuilderExtrasLogic.prettyName(word.ifEmpty { "Tab" })
    }

    private companion object {
        const val MAX_SEEN = 30
        const val MAX_OPTIONS = 60

        // r102: Learn this list
        const val MAX_PRESSES = 60
        const val PATTERN_AFTER = 3

        /** r105: lists up to this size are pressed entry by entry; longer ones use a pattern checked on 2 more */
        const val PRESS_ALL = 30
        const val PATTERN_CHECKS = 2
        const val CHANGE_MS = 6_000L

        const val LOAD_MS = 15_000L
        const val SETTLE_MS = 700L

        /** r102 fix: how long learn.js may take to say what became of a press (5 s injection wait + menu opening) */
        const val ANSWER_MS = 8_000L

        /** r102 fix: how often a wait looks at Stop */
        const val WAIT_STEP_MS = 200L

        /** r102 fix: learn.js's outcome lines ("learn: pressed #12 …") */
        val LEARN_OUTCOME = Regex("^learn: (pressed|missing|error|stopped) #([0-9]+)") // KMK (r110) fix: stopped

        /** KMK (r109): learn.js `items`'s answer ("learn: items #12 <json>") and how long it may take (3 ways to open) */
        val LEARN_ITEMS = Regex("^learn: items #([0-9]+) (.*)$")
        const val ITEMS_MS = 12_000L

        /** KMK (r110): learn.js `count`'s answer ("learn: count #12 38"), how long to wait, the second look's pause */
        val LEARN_COUNT = Regex("^learn: count #([0-9]+) (-?[0-9]+)( next)?") // KMK (r112): " next"
        const val COUNT_MS = 4_000L
        const val COUNT_AGAIN_MS = 1_500L

        /** KMK (r110): "Learn the menus" stops only when the directory page failed to load this many times in a row */
        const val LOAD_FAILS = 3

        /** KMK (r112): how long "Do all N" waits for a directory's first page still being read */
        const val PROBE_WAIT_MS = 60_000L
    }
}
