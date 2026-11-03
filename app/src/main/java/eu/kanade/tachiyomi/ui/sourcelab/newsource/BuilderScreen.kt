package eu.kanade.tachiyomi.ui.sourcelab.newsource

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.jssource.lab.Derive
import eu.kanade.tachiyomi.jssource.lab.LabFlowScreen
import eu.kanade.tachiyomi.jssource.lab.PickCheck
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.sourcelab.pagesource.PageSourceScreen
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.UUID

/**
 * KMK (r96, Source Lab): "Build by tapping" (D§2.4, still 04). The site in a WebView card with picker.js
 * injected after every page load; a tap outlines the element and its look-alikes and posts it through
 * `LabPicker.pick`; the label sheet under the card names it (Item · Title · Cover · Next page on a list page,
 * Chapter · Date · Number on a title, Page image or Video frame on a chapter). "What the Lab derived" shows
 * every decision with its reason as it is made. Done → the generic-list template, handed to Half A.
 *
 * r100: the helper card (over the page on a tablet, under it on a phone; mockups `scratchpad/r100-helper/`), the
 * Labels rows' verdict glyphs, the "Check each pick" switch, W3's "Lists and options" pane, and the bridge's
 * `options` (picker.js `optionsFor`, console fallback `LABOPTS:<token>:`). Page scripts from the model (keep / mark
 * / suggest / adopt / drop / up / down) arrive through `pageScripts`, in order.
 *
 * r101: the title page's own fields (Cover, Description, Tags, Author, Status) as optional rows; the bridge's `form`
 * (the site's search box, console `LABFORM:<token>:`) and `group` (a group pick, console `LABGROUP:<token>:`); the
 * extras' group mode is put back on the page after every injection.
 *
 * r102: "Tapping for…" (mockup `scratchpad/r102-mock/1-tapping-for.png`) replaces the Pick / Browse buttons: a row
 * of every label under the address bar; a chip arms its label on the page (`__labPicker.arm`), nothing armed = the
 * site works as a browser and a long press picks. The Tags card (mockup 2) lists the tag groups. learn.js (W2) is put
 * on every page after picker.js; the address bar shows the WebView's own address. [editKey]: Edit by tapping (the
 * source's builder state comes back; the main button is "Save to this source", into the same key).
 *
 * r103 (the owner's r102 video: the "Tapping for…" rows, banner and hint ate the page): the rows are gone; a label is
 * armed by tapping its row in the Labels card (highlighted, "tap again to stop"); while something is armed one slim
 * bar over the page says "Tapping for: X · Stop" (also for "Pick a menu on the site"); the edit banner's ✕ closes it
 * for the session; the one-line hint under the address bar shows only until the first pick. The bridge's `menu`
 * (console `LABMENU:<token>:`) is a menu group pick.
 *
 * r104: the Tags card shows "Split into rows" on a restored group that reads whole rows or labels; a Suggestions card
 * (like Tags) lists the sections tapped as Suggestions ("You may like", "Related videos") with remove.
 *
 * r106 (W1, mockups `scratchpad/design106/project/TLabels`, `TTitle`, `TTags`, `TCode`, `TAddress`; the views are in
 * BuilderChrome.kt): the stat strip and the edit banner are gone (the banner's note shows once as a snackbar). One top
 * bar: back, title + source · host, the step pills (tap: go back / Next), "N of M picked · +k", Undo, Save ▾, ⋮ (Check
 * each pick, Kind, Format, previous step, leave). The address bar sits over the page only with its icons inside (Search
 * / Back · Forward · Reload, Go when edited, Stop while loading); words search the site. The right panel (below the
 * page on a phone) has the tabs Labels · Title page (Tags and Suggestions open sub-views) · Lists & filters (W2's
 * BuilderExtrasPane) · Code (what the Lab derived, readable, copyable); its footer holds the step's next action. The
 * tab follows the step. While a label is armed one strip over the page's bottom says "Tapping for X · hint · Stop".
 */
internal class BuilderScreen(
    private val startUrl: String? = null,
    private val anime: Boolean = false,
    private val editKey: String? = null,
    /**
     * r106 fix: the panel's tab to open first: "labels" | "title" | "filters" | "code" (null = the step's tab); the
     * code editor's Lint "Learn the codes" opens the builder on "filters"
     */
    private val startTab: String? = null,
    /** KMK (r115): the Site check's note for this site (its plan), shown once over the page; null = none */
    private val siteNote: String? = null,
) : Screen(), LabFlowScreen {

    /**
     * r98 fix: a random token for this screen, put on the page as `window.__labToken` right before picker.js (which
     * takes it off window at once). A console line is a pick only when it carries it ("LABPICK:<token>:<json>"), so
     * the site's own `console.log` can't fake one. The bridge path doesn't need it (only this app adds LabPicker).
     */
    private val labToken: String = UUID.randomUUID().toString().replace("-", "")

    /**
     * What the page calls: `LabPicker.pick(json)` and `LabPicker.log(text)`; runs on a WebView thread. r98: both
     * methods are kept from R8 by `-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }` in
     * proguard-rules.pro (without it the release build removed them as unused: the page saw a `LabPicker` with no
     * `pick`, so no tap ever reached the app). picker.js also falls back to `console.log("LABPICK:<token>:" + json)`,
     * taken by the WebChromeClient below only with this screen's [labToken].
     */
    private class Bridge(private val model: BuilderScreenModel) {
        @JavascriptInterface
        fun pick(json: String) {
            model.onPick(json)
        }

        @JavascriptInterface
        fun log(text: String) {
            model.onPickerLog(text)
        }

        /** r100: picker.js `optionsFor(param)` answers here (W3's filter editor); console fallback `LABOPTS:` */
        @JavascriptInterface
        fun options(json: String) {
            model.onPageOptions(json)
        }

        /** r101: the site's search box (picker.js `readForm`, on every page load); console fallback `LABFORM:` */
        @JavascriptInterface
        fun form(json: String) {
            model.onSearchForm(json)
        }

        /** r101: a group pick (a whole list: genres, sort options, a select); console fallback `LABGROUP:` */
        @JavascriptInterface
        fun group(json: String) {
            model.onGroupPicked(json)
        }

        /** r103: a menu group pick ("Pick a menu on the site"); console fallback `LABMENU:` */
        @JavascriptInterface
        fun menu(json: String) {
            model.onMenuPicked(json)
        }
    }


    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { BuilderScreenModel(startUrl, anime, editKey, siteNote) }
        val state by model.state.collectAsState()
        val extrasState by model.extras.state.collectAsState()
        val tablet = isTabletUi()
        val holder = remember { arrayOfNulls<WebView>(1) }
        val context = LocalContext.current
        val focusManager = LocalFocusManager.current
        val snackbar = remember { SnackbarHostState() }
        // r106: the page's loading progress (the address bar's Stop and its thin line); 100 = not loading
        var progress by remember { mutableIntStateOf(100) }
        // r106: the panel's tab, the step it last followed, and the Title page tab's sub-view ("tags" / "suggestions")
        val firstTab = tabOf(startTab)
        var tab by rememberSaveable { mutableIntStateOf(firstTab ?: TAB_LABELS) }
        // r106 fix: a start tab isn't overridden by the first step's tab (the tab follows later step changes)
        var tabStep by rememberSaveable { mutableStateOf(if (firstTab != null) model.state.value.step.name else "") }
        var titleSub by rememberSaveable { mutableStateOf<String?>(null) }
        val panelScroll = rememberScrollState()
        val panelScrollRef = remember(panelScroll) { PanelScroll(panelScroll) } // KMK (r109) fix
        // r106 (phone): bumped to open a peeked sheet to half (a menu card to look at, the helper's verdict)
        var sheetOpenTick by remember { mutableIntStateOf(0) }
        // r106 (W2): the Lists & filters pane's scroll requests already handled (-1: none seen yet)
        var handledScrollTick by rememberSaveable { mutableIntStateOf(-1) }
        // r106 fix: a start tab of Lists & filters opens the phone's sheet once
        var startShown by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!startShown && firstTab != null) {
                startShown = true
                if (firstTab == TAB_LISTS) sheetOpenTick++
            }
        }

        LaunchedEffect(state.delivered) {
            // r102: Edit by tapping goes back to the source it saved (one screen); a new source leaves the Lab flow
            if (state.delivered) {
                if (editKey != null) navigator.pop() else navigator.popUntil { it !is LabFlowScreen }
            }
        }
        // Page requests from the model (Go, Next: open a title / a chapter). Each request is loaded once: after a
        // rotation the tick is already handled (the recreated WebView reloads the page shown, see WebCard's factory).
        // r98: a request that comes before the WebView exists waits for it (webGeneration), instead of being
        // marked handled with nothing loaded
        var handledLoadTick by rememberSaveable { mutableIntStateOf(-1) }
        var webGeneration by remember { mutableIntStateOf(0) }
        LaunchedEffect(state.loadTick, webGeneration) {
            if (state.loadTick == handledLoadTick) return@LaunchedEffect
            val web = holder[0] ?: return@LaunchedEffect
            handledLoadTick = state.loadTick
            val url = state.loadUrl ?: return@LaunchedEffect
            web.loadUrl(url)
        }
        // picker.js arrives from the asset read after the first page may have loaded: injected once per arrival
        // (not from AndroidView.update, which ran on every recomposition)
        LaunchedEffect(state.pickerScript) {
            if (state.pickerScript == null || state.pageUrl == null) return@LaunchedEffect
            holder[0]?.let { inject(it, model) }
        }
        // r102: the armed label, told to the page's picker as it changes (and before picker.js on every injection).
        // r106: arming Tags or Suggestions shows its sub-view in the Title page tab
        LaunchedEffect(state.armed) {
            holder[0]?.evaluateJavascript(armScript(state.armed), null)
            val armedNow = state.armed
            if (armedNow == "tags" || armedNow == "suggestions") {
                tab = TAB_TITLE
                titleSub = armedNow
            }
        }
        // r106: the tab follows the step (List page → Labels; Title page and the video step → Title page) when the
        // step changes; the owner can switch tabs any time
        LaunchedEffect(state.step) {
            val name = state.step.name
            if (tabStep != name) {
                tabStep = name
                tab = if (state.step == BuilderScreenModel.Step.ListPage) TAB_LABELS else TAB_TITLE
            }
        }
        // r106 (W2): a new menu card, or one that starts learning / reading ("menu:<id>", "pending"): the panel shows
        // Lists & filters (W2's pane scrolls the card into view); on a phone the sheet opens to half
        LaunchedEffect(extrasState.scrollTick) {
            val tick = extrasState.scrollTick
            if (handledScrollTick == -1) {
                handledScrollTick = tick
                return@LaunchedEffect
            }
            if (tick == handledScrollTick) return@LaunchedEffect
            handledScrollTick = tick
            val target = extrasState.scrollTarget.orEmpty()
            // r106 fix: only new cards and learning switch the tab / open the sheet (not every sub-menu read of a
            // "Read all N", nor a question on a card the owner is looking at)
            if (extrasState.scrollReveal && (target.startsWith("menu:") || target == "pending")) {
                tab = TAB_LISTS
                sheetOpenTick++
            }
            // KMK (r109) fix: a read's progress box is in Lists & filters: that tab shows (the sheet stays as it is)
            if (target.startsWith("read:") && tab != TAB_LISTS) tab = TAB_LISTS
        }
        // r106: Edit by tapping's note ("Editing X: your picks are back", "edited by hand since") shows once, as a
        // snackbar (r102–r105: a banner over the page)
        LaunchedEffect(state.banner) {
            val text = state.banner ?: return@LaunchedEffect
            snackbar.showSnackbar(text, actionLabel = "OK", duration = SnackbarDuration.Long)
            model.dismissBanner()
        }
        // Android Back: the page's own history first, then this screen only (back to New source, not out of the Lab)
        BackHandler(enabled = true) {
            val web = holder[0]
            if (web != null && web.canGoBack()) web.goBack() else navigator.pop()
        }
        // After a label: keep the outline on what was labelled; r100: the helper's mark / suggest / adopt …, in the
        // order the model sent them (a flow: none is lost between two recompositions)
        LaunchedEffect(model) {
            model.pageScripts.collect { script -> holder[0]?.evaluateJavascript(script, null) }
        }
        DisposableEffect(Unit) {
            onDispose {
                // r101: leaving the builder ends "Pick a list on the site"
                runCatching { if (model.extras.state.value.groupMode) model.extras.setGroupMode(false) }
                holder[0]?.let { web ->
                    runCatching {
                        web.stopLoading()
                        web.removeJavascriptInterface("LabPicker")
                        web.destroy()
                    }
                }
                holder[0] = null
            }
        }

        // r101: the title page's own fields are optional: counted apart ("5 of 8 picked · +2")
        val picked = state.picks.count { it.pick.label !in BuilderScreenModel.optionalLabels }
        val extraPicked = state.picks.size - picked
        val wanted = BuilderScreenModel.Step.ListPage.labels.size + BuilderScreenModel.Step.TitlePage.labels.size + 1 -
            BuilderScreenModel.optionalLabels.size
        val pickedText = "$picked of $wanted picked" + if (extraPicked > 0) " · +$extraPicked" else ""
        // r100 (W3): a filter's choices are read from the page (a <select>, or links carrying the parameter)
        val askOptions: (String) -> Unit = { param ->
            holder[0]?.evaluateJavascript(
                "window.__labPicker && window.__labPicker.optionsFor(${JsonPrimitive(param)})",
                null,
            )
            Unit
        }
        // r100: the helper card shows after a label while no other tap waits for one
        val showHelper = state.helper && state.pending == null && state.helperFor?.let { state.checks[it] } != null
        LaunchedEffect(showHelper) {
            if (showHelper) sheetOpenTick++
        }
        // r106: the armed strip over the page's bottom (a label, or "Pick a menu on the site")
        val armed = state.armed
        val armedWhat: String? = when {
            armed == "tags" -> "Tags"
            armed == "suggestions" -> "Suggestions"
            armed != null -> labelName(armed)
            extrasState.groupMode -> "a menu on the site"
            else -> null
        }
        val armedHint = when {
            armed == "tags" -> "one tap on the details gives every row"
            armed == "suggestions" -> "tap a card in a “You may like” section"
            armed != null -> "tap ${whatToTap(armed)}"
            else -> "a heading, a dropdown's button or an entry"
        }
        val stopArmed: () -> Unit = { if (model.state.value.armed != null) model.arm(null) else model.extras.setGroupMode(false) }
        val notice = state.notice
        // r106: the address bar (mock TAddress)
        val pageUrl = state.pageUrl
        val loading = progress < 100
        val edited = state.url.isNotBlank() && state.url.trim() != pageUrl && state.url.trim() != state.loadUrl
        val go: () -> Unit = {
            val target = addressFor(model.state.value, model.extras.state.value.searchPath)
            if (target != null) model.load(target)
            focusManager.clearFocus()
        }
        val copyAll: () -> Unit = {
            val selection = model.state.value.selection
            if (selection != null && selection.derived.isNotEmpty()) {
                context.copyToClipboard("Build by tapping", selection.derived.entries.joinToString("\n") { (key, value) -> "$key = $value" })
            }
        }
        // r106: the Save split button (W2's write(inPlace)): Edit by tapping saves to the open version ("Save to v3",
        // overwritten in place; its Baseline asks first) and ▾ = a new version; a new source is written (and opens in
        // the code editor). No open version (no History, or blocked): the main button saves a new version
        val saveEnabled = state.busy == null && (state.editKey != null || state.picks.isNotEmpty() || state.fromCode.isNotEmpty())
        val openVersion = state.openVersion
        val saveInPlace = state.editKey != null && openVersion != null
        val saveMain: () -> Unit = { model.write(inPlace = saveInPlace) }
        val saveMenu = if (state.editKey != null) {
            listOf(
                BuilderSaveItem(
                    "Save as a new version (v${state.nextVersion})",
                    if (openVersion != null) "Keeps v$openVersion as it is and adds v${state.nextVersion}" else "History gets the new version",
                    { model.write(inPlace = false) },
                ),
                BuilderSaveItem("Leave without saving", "What you picked here is dropped", { navigator.pop() }),
            )
        } else {
            listOf(
                BuilderSaveItem("Write the source now", "With what is picked so far; it opens in the code editor", { model.write(inPlace = false) }),
                BuilderSaveItem("Leave without writing", "What you picked here is dropped", { navigator.pop() }),
            )
        }
        val onStep: (Int) -> Unit = { number ->
            val now = model.state.value.step.number
            when {
                number < now -> repeat(now - number) { model.previousStep() }
                // r106 fix: 1 → 3 goes on step by step, as long as each step lets it (a refused step stays put)
                number > now -> {
                    var guard = 0
                    while (model.state.value.step.number < number && guard++ < 3) {
                        val before = model.state.value.step
                        model.nextStep()
                        if (model.state.value.step == before) break
                    }
                }
            }
        }
        val onBack: () -> Unit = {
            if (model.state.value.step != BuilderScreenModel.Step.ListPage) model.previousStep() else navigator.pop()
        }
        // KMK (r112, mock BEasy): "What's left" jumps: the panel's tab; for a label, back to its step's page when that
        // step came earlier, and the label armed when its page is the one shown (a later step's page isn't open yet)
        val jumpTo: (Int, String?) -> Unit = { toTab, label ->
            var armed = false
            if (label != null) {
                val want = when (label) {
                    in BuilderScreenModel.Step.ListPage.labels -> BuilderScreenModel.Step.ListPage
                    in BuilderScreenModel.Step.TitlePage.labels -> BuilderScreenModel.Step.TitlePage
                    else -> BuilderScreenModel.Step.ChapterPage
                }
                if (want.number < model.state.value.step.number) onStep(want.number)
                // the tab asked for wins over the tab that follows a step change
                tabStep = model.state.value.step.name
                if (model.state.value.step == want) {
                    model.arm(label)
                    armed = true
                }
            }
            tab = toTab
            if (toTab == TAB_TITLE && label != "tags" && label != "suggestions") titleSub = null
            // (an armed label drops the phone's sheet to its peek by itself)
            if (!armed) sheetOpenTick++
        }
        val media = if (state.anime) "video" else "image"
        val listMissing = BuilderScreenModel.Step.ListPage.labels.filter { !state.done(it) }
        val episodeMissing = listOf("chapter", "date", "number").filter { !state.done(it) && !(it == "chapter" && state.singleEpisode) }
        val titleFields = BuilderScreenModel.optionalLabels.toList()
        val titleMissing = titleFields.filter { !state.done(it) }
        val unmade = extrasState.menuGroups.filter { g ->
            g.made.isNullOrEmpty() && g.kind != "heading" && extrasState.menuGroups.none { it.parent == g.id }
        }
        val leftItems = buildList {
            if (listMissing.isNotEmpty()) {
                add(
                    BuilderLeftItem("List page: " + listMissing.joinToString(", ") { labelName(it) }, false, "Labels") {
                        jumpTo(TAB_LABELS, listMissing.first())
                    },
                )
            }
            if (episodeMissing.isNotEmpty()) {
                add(
                    BuilderLeftItem("Episodes: " + episodeMissing.joinToString(", ") { labelName(it) }, false, "Labels") {
                        jumpTo(TAB_LABELS, episodeMissing.first())
                    },
                )
            }
            if (!state.done(media)) {
                val text = when {
                    !state.anime -> "Page image: tap a page picture on a chapter"
                    state.singleEpisode -> "Video: tap the player on the title page"
                    else -> "Video: tap the player on an episode"
                }
                add(BuilderLeftItem(text, false, "Title page") { jumpTo(TAB_TITLE, media) })
            }
            if (titleMissing.isNotEmpty()) {
                add(
                    BuilderLeftItem("Title page (optional): " + titleMissing.joinToString(", ") { labelName(it) }, true, "Title page") {
                        jumpTo(TAB_TITLE, null)
                    },
                )
            }
            // KMK (r112) fix: menu cards not made yet are named (with or without filters made)
            if (unmade.isEmpty() && extrasState.filters.isEmpty()) {
                add(BuilderLeftItem("Filters (optional): pick a menu on the site", true, "Lists & filters") { jumpTo(TAB_LISTS, null) })
            } else if (unmade.isNotEmpty()) {
                add(
                    BuilderLeftItem(
                        "Filters (optional): " + unmade.take(4).joinToString(", ") { it.name.ifBlank { "a menu" } } +
                            (if (unmade.size > 4) " and ${unmade.size - 4} more" else "") + " not made yet",
                        true,
                        "Lists & filters",
                    ) {
                        jumpTo(TAB_LISTS, null)
                        model.extras.requestScroll("menu:${unmade.first().id}", reveal = false)
                    },
                )
            }
        }
        val leftDone = buildList {
            if (listMissing.isEmpty()) add("list page")
            if (episodeMissing.isEmpty()) add(if (state.singleEpisode) "episode" else "episodes")
            if (state.done(media)) add(if (state.anime) "video" else "page image")
            titleFields.filter { state.done(it) }.forEach { add(labelName(it).lowercase()) }
            if (extrasState.filters.isNotEmpty()) add("${extrasState.filters.size} filter" + if (extrasState.filters.size == 1) "" else "s")
        }.joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { null }

        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                val wideBar = maxWidth >= 1000.dp
                val narrow = maxWidth < 600.dp
                val undoInMenu = maxWidth < 400.dp
                val panelWidth = if (maxWidth >= 1100.dp) 460.dp else 400.dp
                Column(modifier = Modifier.fillMaxSize()) {
                    TopBar(
                        state = state,
                        model = model,
                        wide = wideBar,
                        phone = !tablet,
                        undoInMenu = undoInMenu,
                        pickedText = pickedText,
                        saveLabel = when {
                            state.editKey == null -> if (narrow) "Write" else "Write the source"
                            openVersion != null -> if (narrow) "Save v$openVersion" else "Save to v$openVersion"
                            narrow -> "Save"
                            else -> "Save as v${state.nextVersion}"
                        },
                        saveEnabled = saveEnabled,
                        saveMenu = saveMenu,
                        onSave = saveMain,
                        onBack = onBack,
                        onStep = onStep,
                        onLeave = { navigator.pop() },
                    )
                    if (state.busy != null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    val addressBar: @Composable (Modifier) -> Unit = { barModifier ->
                        BuilderAddressBar(
                            text = state.url,
                            pageOpen = pageUrl != null,
                            edited = edited,
                            canGoBack = state.canGoBack,
                            canGoForward = state.canGoForward,
                            loading = loading,
                            progress = progress,
                            onText = model::setUrl,
                            onBack = { holder[0]?.takeIf { it.canGoBack() }?.goBack() },
                            onForward = { holder[0]?.takeIf { it.canGoForward() }?.goForward() },
                            onReload = { holder[0]?.reload() },
                            onStop = {
                                holder[0]?.stopLoading()
                                progress = 100
                            },
                            onGo = go,
                            modifier = barModifier,
                        )
                    }
                    if (tablet) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                            ) {
                                addressBar(Modifier.fillMaxWidth())
                                // r99: Up / Down move the pick on the page; picker.js sends the new element back as a pick
                                PickHint(state, model) { direction ->
                                    holder[0]?.evaluateJavascript("window.__labPicker && window.__labPicker.$direction()", null)
                                    Unit
                                }
                                WebCard(
                                    model,
                                    holder,
                                    onCreated = { webGeneration++ },
                                    onProgress = { progress = it },
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                ) {
                                    if (notice != null) {
                                        BuilderNoticeBar(notice, model::dismissNotice, Modifier.align(Alignment.TopCenter).padding(8.dp))
                                    }
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                                    ) {
                                        // r100: the helper card over the bottom of the page, as in the mockups
                                        if (showHelper) HelperCard(state, model, wide = true, modifier = Modifier.fillMaxWidth())
                                        if (armedWhat != null) BuilderArmedStrip(armedWhat, armedHint, stopArmed)
                                    }
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                            Column(modifier = Modifier.width(panelWidth).fillMaxHeight()) {
                                BuilderPanelTabs(TAB_NAMES, tab, { tab = it })
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        // KMK (r109) fix: the viewport's top, for a card brought to the panel's top
                                        .onGloballyPositioned { panelScrollRef.viewportTop = it.positionInRoot().y }
                                        .verticalScroll(panelScroll)
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                ) {
                                    CompositionLocalProvider(LocalPanelScroll provides panelScrollRef) {
                                        PanelBody(state, model, tab, titleSub, { titleSub = it }, askOptions, leftItems, leftDone)
                                    }
                                }
                                PanelFooter(state, model, tab, copyAll, saveMain)
                            }
                        }
                    } else {
                        // r106 (phone): the page on top (about 55 %, its address bar over it), the panel as a draggable
                        // sheet below with the same tabs; arming a label drops the sheet to its peek
                        BuilderPhoneSheet(
                            tabs = TAB_NAMES,
                            tab = tab,
                            onTab = { tab = it },
                            tapping = armedWhat != null,
                            openTick = sheetOpenTick,
                            page = { pageModifier ->
                                Column(modifier = pageModifier) {
                                    addressBar(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp))
                                    PickHint(state, model) { direction ->
                                        holder[0]?.evaluateJavascript("window.__labPicker && window.__labPicker.$direction()", null)
                                        Unit
                                    }
                                    WebCard(
                                        model,
                                        holder,
                                        onCreated = { webGeneration++ },
                                        onProgress = { progress = it },
                                        modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                                    ) {
                                        if (notice != null) {
                                            BuilderNoticeBar(notice, model::dismissNotice, Modifier.align(Alignment.TopCenter).padding(8.dp))
                                        }
                                        if (armedWhat != null) {
                                            BuilderArmedStrip(
                                                armedWhat,
                                                armedHint,
                                                stopArmed,
                                                Modifier.align(Alignment.BottomCenter).padding(8.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            body = {
                                // On a phone the helper's verdict sits at the top of the sheet (the page is small)
                                if (showHelper) HelperCard(state, model, wide = false, modifier = Modifier.fillMaxWidth())
                                PanelBody(state, model, tab, titleSub, { titleSub = it }, askOptions, leftItems, leftDone)
                            },
                            footer = { PanelFooter(state, model, tab, copyAll, saveMain) },
                        )
                    }
                }
            }
        }

        // r106 (W2): Save to the open version when it is the Baseline asks first (the code editor's r103 question)
        val confirm = state.saveConfirm
        if (confirm != null) {
            AlertDialog(
                onDismissRequest = model::cancelSave,
                title = { Text("Overwrite v${state.openVersion ?: ""}?") },
                text = { Text(confirm) },
                confirmButton = { TextButton(onClick = model::confirmSave) { Text("Overwrite") } },
                dismissButton = {
                    TextButton(
                        onClick = {
                            model.cancelSave()
                            model.write(inPlace = false)
                        },
                    ) { Text("Save as new") }
                },
            )
        }
        val error = state.error
        if (error != null) {
            AlertDialog(
                onDismissRequest = model::clearError,
                title = { Text(if (state.editKey != null) "Edit by tapping" else "Build by tapping") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = model::clearError) { Text("OK") } },
            )
        }
    }

    /**
     * KMK (r115): Page source on the page shown; the list's Item selector goes along (its "only in Rendered" banner)
     * once a list label was picked (Derive's placeholder `article` is not sent)
     */
    private fun openPageSource(navigator: Navigator, state: BuilderScreenModel.State) {
        val url = state.pageUrl ?: state.loadUrl ?: return
        val listPicked = state.pick("item") != null || state.pick("title") != null || state.fromCodeOnly("item")
        val item = state.selection?.derived?.get("itemSelector")?.takeIf { listPicked && it.isNotBlank() }
        navigator.push(PageSourceScreen(url = url, sourceKey = state.editKey, itemSelector = item))
    }

    /**
     * r106: what the address bar's Enter / Go loads: an address as typed (the model adds https://); words (no dot, or
     * spaces) search the site: its recorded search (Lists & filters), the edited source's own, Derive's guess, else
     * `/search?q=`. Before any page is open, the text is loaded as is.
     */
    private fun addressFor(state: BuilderScreenModel.State, recordedSearch: String?): String? {
        val text = state.url.trim()
        if (text.isEmpty()) return null
        val address = "://" in text || (' ' !in text && '.' in text) || text.startsWith("localhost")
        if (address || (state.pageUrl == null && state.loadUrl == null)) return text
        val path = listOfNotNull(recordedSearch, state.fromCode["searchPath"], state.selection?.derived?.get("searchPath"))
            .map { it.trim() }
            .firstOrNull { "{q}" in it }
            ?: "/search?q={q}"
        val words = runCatching { URLEncoder.encode(text, "UTF-8") }.getOrDefault(text)
        val filled = path.replace("{q}", words).replace("{page}", "1")
        AppLog.add('D', "lab", "Builder: address bar words searched on the site ($path)")
        return if (filled.startsWith("http://") || filled.startsWith("https://")) {
            filled
        } else {
            state.site.trimEnd('/') + if (filled.startsWith("/")) filled else "/$filled"
        }
    }

    /**
     * r106: the top bar (mock TLabels): back, "Edit by tapping" + source · host, the step pills, "N of M picked", Undo,
     * Save ▾, ⋮. [wide]: one row (as in the mockups); else two rows (the pills and the count go to the second).
     */
    @Composable
    private fun TopBar(
        state: BuilderScreenModel.State,
        model: BuilderScreenModel,
        wide: Boolean,
        phone: Boolean,
        undoInMenu: Boolean,
        pickedText: String,
        saveLabel: String,
        saveEnabled: Boolean,
        saveMenu: List<BuilderSaveItem>,
        onSave: () -> Unit,
        onBack: () -> Unit,
        onStep: (Int) -> Unit,
        onLeave: () -> Unit,
    ) {
        val stepsScroll = rememberScrollState()
        val current = state.step.number
        val steps = listOf(
            BuilderStepPill(1, "List page", done = current > 1, current = current == 1),
            BuilderStepPill(2, "Title page", done = current > 2, current = current == 2),
            BuilderStepPill(3, if (state.anime) "Video" else "Pages", done = false, current = current == 3),
        )
        val title = if (state.editKey != null) "Edit by tapping" else "Build by tapping"
        val subtitle = listOfNotNull(state.editName, state.host.ifBlank { null }).joinToString(" · ").ifBlank { stepTitle(state) }
        val undoEnabled = state.picks.isNotEmpty() || state.pending != null
        if (wide) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(start = 6.dp, end = 12.dp),
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                TitleBlock(title, subtitle, Modifier.widthIn(max = 240.dp))
                Spacer(Modifier.weight(1f))
                BuilderStepPills(steps, onStep)
                Spacer(Modifier.weight(1f))
                BuilderPill(pickedText)
                IconButton(onClick = model::undo, enabled = undoEnabled) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                }
                BuilderSaveButton(saveLabel, saveEnabled, onSave, saveMenu)
                MoreMenu(state, model, onLeave, onUndo = null)
            }
        } else if (phone) {
            // r106 (phone): back, the title with the step indicator ("2/3 · Title page ▾", the picked count under it;
            // tap to switch steps), Undo when there is room (else in ⋮), Save ▾, ⋮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 2.dp, end = 2.dp),
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                BuilderStepIndicator(title, steps, pickedText, onStep, Modifier.weight(1f))
                if (!undoInMenu) {
                    IconButton(onClick = model::undo, enabled = undoEnabled) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                    }
                }
                BuilderSaveButton(saveLabel, saveEnabled, onSave, saveMenu)
                MoreMenu(state, model, onLeave, onUndo = if (undoInMenu && undoEnabled) model::undo else null)
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    TitleBlock(title, subtitle, Modifier.weight(1f).padding(end = 4.dp))
                    IconButton(onClick = model::undo, enabled = undoEnabled) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                    }
                    BuilderSaveButton(saveLabel, saveEnabled, onSave, saveMenu)
                    MoreMenu(state, model, onLeave, onUndo = null)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(stepsScroll)
                        .padding(start = 8.dp, end = 12.dp, bottom = 6.dp),
                ) {
                    BuilderStepPills(steps, onStep)
                    BuilderPill(pickedText)
                }
            }
        }
    }

    @Composable
    private fun TitleBlock(title: String, subtitle: String, modifier: Modifier) {
        Column(modifier = modifier) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /**
     * r106: ⋮ holds what lived in the panel: the helper ("Check each pick"), Kind (Manga / Anime), Format (JS / JSON),
     * back to the previous step, and leaving
     */
    @Composable
    private fun MoreMenu(state: BuilderScreenModel.State, model: BuilderScreenModel, onLeave: () -> Unit, onUndo: (() -> Unit)?) {
        var open by remember { mutableStateOf(false) }
        val navigator = LocalNavigator.currentOrThrow
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                // r106 (phone, narrow): Undo lives here when the top bar has no room for it
                if (onUndo != null) {
                    DropdownMenuItem(
                        text = { Text("Undo") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null) },
                        onClick = {
                            open = false
                            onUndo()
                        },
                    )
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Check each pick")
                            Text(
                                "Every label is checked and a better match suggested",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = { Switch(checked = state.helper, onCheckedChange = model::setHelper) },
                    onClick = { model.setHelper(!state.helper) },
                )
                HorizontalDivider()
                MenuHead("Kind")
                MenuChoice("Manga", !state.anime) { model.setAnime(false) }
                MenuChoice("Anime", state.anime) { model.setAnime(true) }
                HorizontalDivider()
                MenuHead("Format")
                MenuChoice("JS", !state.json) { model.setJson(false) }
                MenuChoice("JSON", state.json) { model.setJson(true) }
                HorizontalDivider()
                // KMK (r115, mockup XSource): what the source receives (Raw) next to what you see (Rendered)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Page source")
                            Text(
                                "Raw, Rendered and the page's requests",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                    enabled = (state.pageUrl ?: state.loadUrl) != null,
                    onClick = {
                        open = false
                        openPageSource(navigator, state)
                    },
                )
                HorizontalDivider()
                if (state.step != BuilderScreenModel.Step.ListPage) {
                    DropdownMenuItem(
                        text = { Text("Back to the previous step") },
                        onClick = {
                            open = false
                            model.previousStep()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (state.editKey != null) "Leave without saving" else "Leave the builder") },
                    onClick = {
                        open = false
                        onLeave()
                    },
                )
            }
        }
    }

    @Composable
    private fun MenuHead(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        )
    }

    @Composable
    private fun MenuChoice(text: String, selected: Boolean, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(text) },
            leadingIcon = {
                if (selected) {
                    Icon(Icons.Outlined.Check, contentDescription = null)
                } else {
                    Spacer(Modifier.size(24.dp))
                }
            },
            onClick = onClick,
        )
    }

    /** r106: the panel's body for [tab] (the Title page tab shows its Tags / Suggestions sub-view when one is open) */
    @Composable
    private fun PanelBody(
        state: BuilderScreenModel.State,
        model: BuilderScreenModel,
        tab: Int,
        titleSub: String?,
        onTitleSub: (String?) -> Unit,
        askOptions: (String) -> Unit,
        /** KMK (r112): the "What's left" card's items and its done line (not on the Code tab) */
        leftItems: List<BuilderLeftItem>,
        leftDone: String?,
    ) {
        // r106 fix: each tab keeps its saved state (open sections, fields) while another tab is shown
        val tabStates = rememberSaveableStateHolder()
        tabStates.SaveableStateProvider(tab) {
            when (tab) {
                TAB_LABELS -> LabelsTab(state, model)
                TAB_TITLE -> when (titleSub) {
                    "tags" -> TagsView(state, model, onBack = { onTitleSub(null) })
                    "suggestions" -> SuggestionsView(state, model, onBack = { onTitleSub(null) })
                    else -> TitleTab(state, model, onOpen = { onTitleSub(it) })
                }
                TAB_LISTS -> BuilderExtrasPane(
                    extras = model.extras,
                    pageUrl = state.pageUrl,
                    siteUrl = state.site,
                    onAskOptions = askOptions,
                    codeSearchPath = state.fromCode["searchPath"],
                )
                else -> CodeTab(state)
            }
        }
        // KMK (r112, mock BEasy): at the bottom of the panel, whatever the tab (the Code tab shows code only)
        if (tab != TAB_CODE) BuilderWhatsLeft(leftItems, leftDone)
    }

    /** r106: the panel's footer: the step's next action (Code tab: Open in the code editor · Copy all) */
    @Composable
    private fun PanelFooter(state: BuilderScreenModel.State, model: BuilderScreenModel, tab: Int, onCopyAll: () -> Unit, onSave: () -> Unit) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (tab == TAB_CODE) {
                    // A new source is written and opens in the code editor; Edit by tapping saves, then goes back to it
                    // r106 fix: Edit by tapping saves like the top bar's Save (to the open version, in place)
                    OutlinedButton(
                        onClick = if (state.editKey != null) onSave else ({ model.write(inPlace = false) }),
                        enabled = state.busy == null && (state.picks.isNotEmpty() || state.editKey != null || state.fromCode.isNotEmpty()),
                    ) {
                        Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.editKey != null) "Save, open code" else "Open in editor", maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onCopyAll, enabled = state.selection?.derived?.isNotEmpty() == true) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy all", maxLines = 1)
                    }
                } else {
                    BuilderMuted(stepSummary(state), modifier = Modifier.weight(1f), maxLines = 2)
                    // The last step saves like the top bar's Save (to the open version when editing)
                    val last = state.step == BuilderScreenModel.Step.ChapterPage
                    Button(onClick = if (last) onSave else model::nextStep, enabled = state.busy == null) {
                        Text(
                            when (state.step) {
                                BuilderScreenModel.Step.ListPage -> "Next: open a title →"
                                BuilderScreenModel.Step.TitlePage ->
                                    if (state.singleEpisode) "Next: pick the video →" else "Next: open a chapter →"
                                BuilderScreenModel.Step.ChapterPage -> when {
                                    state.editKey == null -> "Write the source"
                                    state.openVersion != null -> "Save to v${state.openVersion}"
                                    else -> "Save to this source"
                                }
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    /** "List page done · 4 of 4", "Title page · 4 picked", "Video frame picked" */
    private fun stepSummary(state: BuilderScreenModel.State): String = when (state.step) {
        BuilderScreenModel.Step.ListPage -> {
            val labels = BuilderScreenModel.Step.ListPage.labels
            val done = labels.count { state.done(it) }
            (if (done == labels.size) "List page done" else "List page") + " · $done of ${labels.size}"
        }
        BuilderScreenModel.Step.TitlePage -> "Title page · ${BuilderScreenModel.Step.TitlePage.labels.count { state.done(it) }} picked"
        BuilderScreenModel.Step.ChapterPage -> {
            val label = if (state.anime) "video" else "image"
            when {
                state.done(label) -> "${labelName(label)} picked"
                state.anime -> "Tap the player, then save"
                else -> "Tap a page picture, then save"
            }
        }
    }

    /**
     * KMK (r98): the step after a tap, above the page on phone and tablet alike: "Picked a.title ×24 — now tap a
     * label" with this page's labels as chips (the Labels card's rows take a tap too). r96 asked in a dialog that
     * covered the page and its outlines.
     */
    @Composable
    private fun PickHint(state: BuilderScreenModel.State, model: BuilderScreenModel, refine: (String) -> Unit) {
        val pending = state.pending ?: return
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "Picked ${BuilderScreenModel.shortSelector(pending.likeSelector)} ×${pending.count} — now tap a label",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // r99: what the element shows (text or alt, 60 characters) and how many match, so a wrong element
                // (an invisible link, a menu) is seen before it is labelled; Up / Down to adjust
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        (if (pending.snippet.isNotBlank()) "\"${pending.snippet}\"" else "(no text)") +
                            " · ${pending.count} ${if (pending.count == 1) "match" else "matches"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { refine("up") }) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Up")
                    }
                    TextButton(onClick = { refine("down") }) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Down")
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    // r102: every label of every page (this page's first), whatever the step
                    (state.labels + state.allLabels.filter { it !in state.labels }).forEach { label ->
                        FilterChip(
                            selected = state.pick(label) != null,
                            onClick = { model.label(label) },
                            label = { Text(labelName(label)) },
                        )
                    }
                    TextButton(onClick = model::dismissPending) { Text("Not this") }
                }
            }
        }
    }

    /** r102: `window.__labPicker.arm(label | null)`: a no-op until the picker is on the page */
    private fun armScript(armed: String?): String =
        "window.__labPicker && window.__labPicker.arm && window.__labPicker.arm(${armed?.let { JsonPrimitive(it).toString() } ?: "null"})"

    /**
     * Puts picker.js on the page as it is **now** in the model (not as it was when the WebView was made: in r96
     * `onPageFinished` closed over the first composition's `pickerScript`, null while the asset was still being
     * read, so the script never reached a page from there), then tells it the mode. Idempotent on the page side.
     */
    private fun inject(view: WebView, model: BuilderScreenModel) {
        val state = model.state.value
        val script = state.pickerScript ?: return
        // One evaluation: no page script runs between setting the token and picker.js taking it. r102: the armed
        // label goes first too (`__labArmed`), so a fresh page never starts in the old "every tap picks" mode
        val armed = state.armed?.let { JsonPrimitive(it).toString() } ?: "null"
        view.evaluateJavascript("window.__labToken = ${JsonPrimitive(labToken)};\nwindow.__labArmed = $armed;\n$script", null)
        view.evaluateJavascript(armScript(state.armed), null)
        // r102 (W2): learn.js after picker.js, its own evaluation (an error in one doesn't stop the other)
        state.learnScript?.let { learn -> view.evaluateJavascript(learn, null) }
        // r101: a group pick still waiting for its tap survives a page load (the new page's picker starts off).
        // r103: it is "Pick a menu on the site" (menuMode)
        if (runCatching { model.extras.state.value.groupMode }.getOrDefault(false)) {
            view.evaluateJavascript("window.__labPicker && window.__labPicker.menuMode && window.__labPicker.menuMode(true)", null)
        }
        // KMK (r109) fix: so does a waiting "Tap the picture on the page"
        runCatching { model.extras.picturePickScript() }.getOrNull()?.let { view.evaluateJavascript(it, null) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WebCard(
        model: BuilderScreenModel,
        holder: Array<WebView?>,
        onCreated: () -> Unit,
        /** r106: the page's loading progress (0–100) for the address bar's Stop and its thin line */
        onProgress: (Int) -> Unit,
        modifier: Modifier,
        overlay: @Composable BoxScope.() -> Unit = {},
    ) {
        val userAgent = remember { Injekt.get<NetworkHelper>().defaultUserAgentProvider() }
        Surface(
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = modifier,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                    factory = { context ->
                        // On the main thread: AndroidView's factory runs there. Same settings, cookies and user agent
                        // as the source's own requests (JsRender / JsBrowserFetch), pictures on.
                        WebView(context).apply {
                            setDefaultSettings()
                            settings.userAgentString = userAgent
                            settings.blockNetworkImage = false
                            settings.loadsImagesAutomatically = true
                            // Before any loadUrl: an interface is only visible to pages loaded after it was added
                            addJavascriptInterface(Bridge(model), "LabPicker")
                            // r98: picker.js's fallback when the bridge isn't there: console.log("LABPICK:<token>:" + json)
                            webChromeClient = object : WebChromeClient() {
                                // r106: the address bar's Stop and progress line
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    onProgress(newProgress)
                                }

                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    val text = consoleMessage?.message() ?: return false
                                    // r98 fix: only lines that carry this screen's token; any other is the page's own
                                    val pick = "$PICK_PREFIX$labToken:"
                                    val log = "$LOG_PREFIX$labToken:"
                                    val options = "$OPTS_PREFIX$labToken:"
                                    val form = "$FORM_PREFIX$labToken:"
                                    val group = "$GROUP_PREFIX$labToken:"
                                    val menu = "$MENU_PREFIX$labToken:"
                                    return when {
                                        text.startsWith(pick) -> {
                                            model.onPick(text.removePrefix(pick), viaConsole = true)
                                            true
                                        }
                                        text.startsWith(options) -> {
                                            model.onPageOptions(text.removePrefix(options))
                                            true
                                        }
                                        text.startsWith(form) -> {
                                            model.onSearchForm(text.removePrefix(form))
                                            true
                                        }
                                        text.startsWith(group) -> {
                                            model.onGroupPicked(text.removePrefix(group))
                                            true
                                        }
                                        text.startsWith(menu) -> {
                                            model.onMenuPicked(text.removePrefix(menu))
                                            true
                                        }
                                        text.startsWith(log) -> {
                                            model.onPickerLog(text.removePrefix(log))
                                            true
                                        }
                                        else -> false
                                    }
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                // Navigations stay in this WebView (Browse mode, Go, Next); in Pick mode the page never
                                // starts one, picker.js takes the tap first
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                                // r105: the page's own requests (a "You may like" section its script fills in later is
                                // read from one of them, BuilderLateSections); nothing is changed, the WebView loads it
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val r = request ?: return null
                                    if (r.isForMainFrame || !r.method.equals("GET", ignoreCase = true)) return null
                                    runCatching { BuilderLateSections.record(r.url.toString(), model.state.value.pageUrl) }
                                    return null
                                }

                                // r106: a page starts: the address bar shows Stop until it finishes
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    onProgress(10)
                                }

                                // r102: the WebView's own address (view.url), not the callback's: after Back a late
                                // callback could put the previous page in the bar
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    onProgress(100)
                                    view ?: return
                                    model.onPageLoaded(view.url ?: url, view.canGoBack(), view.canGoForward(), finished = true)
                                    inject(view, model)
                                }

                                // In-page navigations (history.pushState / SPA routes) don't finish a page: the picker
                                // is put back (a no-op when the window still has it) and the bar's Back / Forward follow
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    view ?: return
                                    // r102: onPageLoaded also moves the step to the page's kind (followStep)
                                    model.onPageLoaded(view.url ?: url, view.canGoBack(), view.canGoForward())
                                    inject(view, model)
                                }
                            }
                            holder[0] = this
                            onCreated()
                            // A recreated WebView (rotation) shows the page it showed; the first one waits for the
                            // loadTick effect in Content (so the start URL isn't loaded twice)
                            val shown = model.state.value.pageUrl
                            if (shown != null) loadUrl(shown)
                        }
                    },
                )
                // r100: the helper card (tablet), r106: the armed strip and the notice, drawn over the page
                overlay()
            }
        }
    }

    /**
     * r106: the Labels tab (mock TLabels): the list page's labels, the episodes' (Chapter link, Date, Number), "This
     * page is the only episode" and Kind. A row arms its label (tap again to stop); with a tap waiting, it names it.
     */
    @Composable
    private fun LabelsTab(state: BuilderScreenModel.State, model: BuilderScreenModel) {
        BuilderMuted(
            if (state.pending != null) {
                "A tap is waiting: tap the label it is (or Not this, above the page)."
            } else {
                "Tap a label to arm it, then tap that thing on the page. Each pick is checked on every card."
            },
        )
        ChecklistHead(state, model) // KMK (r115)
        BuilderSectionHead("List page")
        BuilderCard {
            BuilderScreenModel.Step.ListPage.labels.forEach { label -> LabelLine(state, model, label) }
        }
        BuilderSectionHead(if (state.singleEpisode) "Episodes · one episode" else "Episodes")
        BuilderCard {
            BuilderScreenModel.Step.TitlePage.labels
                .filter { it !in BuilderScreenModel.optionalLabels }
                .forEach { label -> LabelLine(state, model, label) }
        }
        BuilderCard {
            // r99: a title page that is its own only episode (one video per page, no episode list)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { model.setSingleEpisode(!state.singleEpisode) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("This page is the only episode", style = MaterialTheme.typography.bodyLarge)
                    BuilderMuted(
                        if (state.singleEpisode) {
                            "No chapter link needed: Date / Number are read on this page, and the video frame is picked on it next" +
                                if (state.json) ". Written as JS (the JSON format lists episodes from links)." else "."
                        } else {
                            "Turn on when the title has one video and no episode list"
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.singleEpisode, onCheckedChange = model::setSingleEpisode)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text("Kind", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                BuilderPill("Anime", selected = state.anime, onClick = { model.setAnime(true) })
                BuilderPill("Manga", selected = !state.anime, onClick = { model.setAnime(false) })
            }
        }
    }

    /**
     * One label's row: ● armed, ✓ / ! / i by the helper's verdict, ✓ picked (or from the code), – not needed, ○ not
     * picked; the selector chip and count when picked, else what to do; ✕ removes the pick.
     *
     * KMK (r115, Helper 2): the row is the checklist's: its mark (✓ ! ✕ ○) and one line come from [PickCheck.row]
     * (the tap's verdict + the re-run on the raw page and 3 titles), and its one action shows at the end.
     */
    @Composable
    private fun LabelLine(state: BuilderScreenModel.State, model: BuilderScreenModel, label: String) {
        val colors = MaterialTheme.colorScheme
        val helper = helperColors()
        val navigator = LocalNavigator.currentOrThrow
        val pick = state.pick(label)
        // r98: with a tap waiting, the rows take it; r102/r103: otherwise a row arms its label ("Tapping for…")
        val choosable = state.pending != null
        val armedHere = state.armed == label
        val notNeeded = state.singleEpisode && label == "chapter"
        val row = if (pick != null && state.helper) model.checklistRow(state, label) else null
        val mark = when {
            armedHere -> "●"
            row != null -> checklistGlyph(row.mark)
            pick != null || state.done(label) -> "✓"
            notNeeded -> "–"
            else -> "○"
        }
        val markColor = when {
            armedHere -> colors.primary
            row != null -> checklistColor(row.mark, helper)
            pick != null || state.done(label) -> helper.ok
            else -> colors.outline
        }
        val hint = when {
            choosable -> "tap: it is the ${labelName(label).lowercase()}"
            armedHere -> "tap it on the page"
            pick != null -> null
            state.fromCodeOnly(label) -> "from the code"
            notNeeded -> "not needed: one episode"
            label == "bigCover" -> "uses the list's cover"
            state.nextLabel == label -> "next: tap to arm"
            else -> "tap to arm"
        }
        val remove: (() -> Unit)? = if (pick != null) ({ model.removePick(label) }) else null
        val fix = if (row != null && !choosable && !armedHere && row.mark != PickCheck.Mark.Ok) row.fix else null
        BuilderLabelRow(
            glyph = mark,
            glyphColor = markColor,
            name = labelName(label),
            selector = pick?.let { BuilderScreenModel.shortSelector(it.likeSelector) },
            count = pick?.pick?.count,
            hint = hint,
            highlighted = choosable || armedHere,
            onClick = { if (choosable) model.label(label) else model.arm(if (armedHere) null else label) },
            onRemove = remove,
            subtitle = row?.line,
            trailing = if (fix != null) {
                { BuilderLinkButton(fix.text, { runFix(navigator, state, model, label, fix) }) }
            } else {
                null
            },
        )
    }

    /** KMK (r115): a checklist row's action; Page source is opened here (the screen owns the navigator) */
    private fun runFix(navigator: Navigator, state: BuilderScreenModel.State, model: BuilderScreenModel, label: String, fix: PickCheck.Fix) {
        if (fix.kind == PickCheck.FixKind.PageSource) {
            val raw = state.raws[label]?.pageUrl?.takeIf { it.isNotBlank() }
            if (raw != null) {
                val listPicked = state.pick("item") != null || state.pick("title") != null
                val item = state.selection?.derived?.get("itemSelector")?.takeIf { listPicked && it.isNotBlank() }
                navigator.push(PageSourceScreen(url = raw, sourceKey = state.editKey, itemSelector = item))
            } else {
                openPageSource(navigator, state)
            }
        } else {
            model.applyFix(label, fix)
        }
    }

    private fun checklistGlyph(mark: PickCheck.Mark): String = when (mark) {
        PickCheck.Mark.Ok -> "\u2713"
        PickCheck.Mark.Warn -> "!"
        PickCheck.Mark.Fail -> "\u2715"
        PickCheck.Mark.Skip -> "\u25CB"
    }

    private fun checklistColor(mark: PickCheck.Mark, colors: HelperColors): Color = when (mark) {
        PickCheck.Mark.Ok -> colors.ok
        PickCheck.Mark.Warn -> colors.warn
        PickCheck.Mark.Fail -> colors.fail
        PickCheck.Mark.Skip -> colors.info
    }

    /**
     * KMK (r115, mockup EHelper2): the checklist's head: what every pick is tested on, the re-run's state and "Check
     * again" (pages already read are reused, so it costs no request unless a new title is picked)
     */
    @Composable
    private fun ChecklistHead(state: BuilderScreenModel.State, model: BuilderScreenModel) {
        if (!state.helper || (state.picks.isEmpty() && state.tagGroups.isEmpty())) return
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Checklist", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                BuilderMuted(
                    when {
                        state.recheckBusy -> "Testing every pick on the raw page and on 3 titles…"
                        state.recheckNote != null -> "Tested on ${state.recheckNote}, in what the source really receives"
                        else -> "Every pick is tested on 3 titles and in what the source really receives"
                    },
                )
            }
            if (state.recheckBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                BuilderLinkButton("Check again", model::rerunNow)
            }
        }
    }

    /**
     * r106: the Title page tab (mock TTitle): Cover, Description, Author, Status, Video frame (Page image on manga),
     * then Tags and Suggestions, each opening its sub-view, and "Check each pick"
     */
    @Composable
    private fun TitleTab(state: BuilderScreenModel.State, model: BuilderScreenModel, onOpen: (String) -> Unit) {
        val colors = MaterialTheme.colorScheme
        val ok = builderOkColor()
        val warn = builderWarnColor()
        BuilderMuted(
            if (state.pending != null) {
                "A tap is waiting: tap the label it is."
            } else {
                "All optional. Tap a label, then tap that part of the title page."
            },
        )
        ChecklistHead(state, model) // KMK (r115)
        BuilderCard {
            listOf("bigCover", "description", "author", "status", if (state.anime) "video" else "image")
                .forEach { label -> LabelLine(state, model, label) }
        }
        BuilderCard {
            val tags = state.tagGroups
            val tagsArmed = state.armed == "tags"
            // KMK (r115, Helper 2): the Tags row is the checklist's too (every row on 3 titles, Split into rows)
            val navigator = LocalNavigator.currentOrThrow
            val tagsRow = if (state.helper && tags.isNotEmpty()) model.checklistRow(state, "tags") else null
            val tagsFix = if (tagsRow != null && !tagsArmed && state.pending == null && tagsRow.mark != PickCheck.Mark.Ok) tagsRow.fix else null
            val helper = helperColors()
            BuilderLabelRow(
                glyph = when {
                    tagsArmed -> "●"
                    tagsRow != null -> checklistGlyph(tagsRow.mark)
                    tags.isNotEmpty() -> "✓"
                    else -> "○"
                },
                glyphColor = when {
                    tagsArmed -> colors.primary
                    tagsRow != null -> checklistColor(tagsRow.mark, helper)
                    tags.isNotEmpty() -> ok
                    else -> colors.outline
                },
                name = "Tags",
                selector = null,
                count = null,
                hint = null,
                highlighted = tagsArmed || state.pending != null,
                onClick = { if (state.pending != null) model.label("tags") else onOpen("tags") },
                onRemove = null,
                subtitle = if (tags.isEmpty()) {
                    "Tap the details: one group per row"
                } else {
                    "${tags.size} ${if (tags.size == 1) "row" else "rows"}: " + tags.joinToString(", ") { it.name } +
                        (tagsRow?.takeIf { it.mark != PickCheck.Mark.Ok }?.let { "\n" + it.line } ?: "")
                },
                trailing = {
                    if (tagsFix != null) {
                        BuilderLinkButton(tagsFix.text, { runFix(navigator, state, model, "tags", tagsFix) })
                    } else {
                        BuilderLinkButton("Open ›", { onOpen("tags") })
                    }
                },
            )
            val groups = state.suggestionGroups
            val suggestionsArmed = state.armed == "suggestions"
            val late = groups.count { it.late.isNotBlank() }
            val warned = groups.any { it.lateWarning.isNotBlank() }
            BuilderLabelRow(
                glyph = when {
                    suggestionsArmed -> "●"
                    warned -> "!"
                    groups.isNotEmpty() -> "✓"
                    else -> "○"
                },
                glyphColor = when {
                    suggestionsArmed -> colors.primary
                    warned -> warn
                    groups.isNotEmpty() -> ok
                    else -> colors.outline
                },
                name = "Suggestions",
                selector = null,
                count = null,
                hint = null,
                highlighted = suggestionsArmed || state.pending != null,
                onClick = { if (state.pending != null) model.label("suggestions") else onOpen("suggestions") },
                onRemove = null,
                subtitle = if (groups.isEmpty()) {
                    "Tap a card in a “You may like” section"
                } else {
                    "${groups.size} ${if (groups.size == 1) "section" else "sections"}" +
                        if (late > 0) " · $late ${if (late == 1) "loads" else "load"} after the page" else ""
                },
                trailing = { BuilderLinkButton("Open ›", { onOpen("suggestions") }) },
            )
        }
        BuilderCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { model.setHelper(!state.helper) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Check each pick", style = MaterialTheme.typography.bodyLarge)
                    BuilderMuted("Every label is checked and a better match is suggested")
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.helper, onCheckedChange = model::setHelper)
            }
        }
    }

    /** r106: the sub-views' arm button: arms [label] (Stop while armed); with a tap waiting, names it [label] */
    @Composable
    private fun ArmButton(state: BuilderScreenModel.State, model: BuilderScreenModel, label: String, idleText: String) {
        when {
            state.pending != null -> Button(onClick = { model.label(label) }) { Text("Use the tap waiting") }
            state.armed == label -> OutlinedButton(onClick = { model.arm(null) }) { Text("Stop tapping") }
            else -> Button(onClick = { model.arm(label) }) {
                Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(idleText)
            }
        }
    }

    /**
     * r106: the Tags sub-view (mock TTags; r102–r104's Tags card): one row per group in page order with Tags /
     * Description / Both; hold a row to rename or remove it; "Split into rows" on a restored whole-row group
     */
    @Composable
    private fun TagsView(state: BuilderScreenModel.State, model: BuilderScreenModel, onBack: () -> Unit) {
        // Remembered before anything else (Compose rule)
        var held by remember { mutableStateOf<Int?>(null) }
        var renaming by remember { mutableStateOf<Int?>(null) }
        var renameText by remember { mutableStateOf("") }
        val groups = state.tagGroups
        BuilderSubHead(
            "Tags",
            if (groups.isEmpty()) "no rows yet" else "${groups.size} ${if (groups.size == 1) "row" else "rows"}, in page order",
            onBack,
        )
        ArmButton(state, model, "tags", "Tap the details on the page")
        BuilderMuted("Where each row shows on the title screen: as tappable Tags, as a line in the Description, or both.")
        if (groups.isNotEmpty()) {
            BuilderCard {
                groups.forEachIndexed { index, group ->
                    key(index, group.selector) {
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        BuilderTagRow(
                            name = group.name,
                            values = group.values.take(3).joinToString(", ") +
                                if (group.values.size > 3) " … (${group.values.size})" else "",
                            target = group.target,
                            onTarget = { target -> model.setTagGroupTarget(index, target) },
                            onHold = { held = index },
                            splitNote = Derive.needsSplit(group),
                            onSplit = { model.splitTagGroup(index) },
                        )
                    }
                }
            }
        }
        BuilderMuted(
            "A tap on the details (a row, its label or its value) makes one group per row, named by its label. " +
                "Hold a row to rename or remove it. Tapping the details again only adds rows that are missing.",
        )
        val heldIndex = held
        if (heldIndex != null) {
            val group = groups.getOrNull(heldIndex)
            AlertDialog(
                onDismissRequest = { held = null },
                title = { Text(group?.name ?: "Row") },
                text = { Text(group?.values?.take(5)?.joinToString(", ").orEmpty().ifEmpty { "No values read yet" }) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            renameText = group?.name.orEmpty()
                            renaming = heldIndex
                            held = null
                        },
                    ) { Text("Rename") }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                model.removeTagGroup(heldIndex)
                                held = null
                            },
                        ) { Text("Remove") }
                        TextButton(onClick = { held = null }) { Text("Cancel") }
                    }
                },
            )
        }
        val renameIndex = renaming
        if (renameIndex != null) {
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Rename the group") },
                text = {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it.take(30) }, singleLine = true)
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            model.renameTagGroup(renameIndex, renameText)
                            renaming = null
                        },
                        enabled = renameText.isNotBlank(),
                    ) { Text("Rename") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            )
        }
    }

    /**
     * r106: the Suggestions sub-view (mock TSuggest): ‹ back, the arm button, then W2's `SuggestionsSection` (one card
     * per section: in the page / loads later / Load more)
     */
    @Composable
    private fun SuggestionsView(state: BuilderScreenModel.State, model: BuilderScreenModel, onBack: () -> Unit) {
        val groups = state.suggestionGroups
        BuilderSubHead("Suggestions", "${groups.size} ${if (groups.size == 1) "section" else "sections"}", onBack)
        ArmButton(state, model, "suggestions", "Tap a card of a section on the page")
        // W2's sections (BuilderSuggestions.kt): in the page / loads later / Load more
        SuggestionsSection(model)
    }

    /**
     * r106: the Code tab (mock TCode): what the Lab derived, as readable rows (label, the selector in a mono chip, tap
     * = copy, and what it means); JSON lists summed up ("8 rows · Code, Type…") with "Show the selectors ›"
     */
    @Composable
    private fun CodeTab(state: BuilderScreenModel.State) {
        var open by remember { mutableStateOf(setOf<String>()) }
        val context = LocalContext.current
        val selection = state.selection
        BuilderMuted("What the Lab wrote from your picks. Tap a value to copy it.")
        if (selection == null || selection.derived.isEmpty()) {
            BuilderMuted("Nothing yet: every value comes here with the reason it was chosen.")
        } else {
            // r99: `warning…` entries (an Item that doesn't hold the title, a player that isn't one) first, in red
            val (warnings, values) = selection.derived.entries.partition { it.key.startsWith("warning") }
            warnings.forEach { entry ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            entry.value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            selection.reasons[entry.key].orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            BuilderCard {
                values.forEachIndexed { index, entry ->
                    val key = entry.key
                    val rows = listRows(entry.value)
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BuilderDerivedRow(
                        label = derivedName(key),
                        value = entry.value,
                        meaning = selection.reasons[key].orEmpty(),
                        summary = rows?.let { list ->
                            if (list.isEmpty()) {
                                "none"
                            } else {
                                "${list.size} ${if (list.size == 1) "row" else "rows"} · " + list.joinToString(", ") { it.first }
                            }
                        },
                        expanded = key in open,
                        onCopy = { text -> context.copyToClipboard(key, text) },
                        onToggle = { open = if (key in open) open - key else open + key },
                        details = rows.orEmpty(),
                    )
                }
            }
        }
    }

    /** A JSON list value (tagGroups, suggestionGroups …) as (name, selector) rows; null when it isn't one */
    private fun listRows(value: String): List<Pair<String, String>>? {
        if (!value.trimStart().startsWith("[")) return null
        val array = runCatching { Json.parseToJsonElement(value) as? JsonArray }.getOrNull() ?: return null
        return array.mapIndexed { index, element ->
            val obj = element as? JsonObject
            if (obj == null) {
                "#${index + 1}" to element.toString()
            } else {
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "#${index + 1}"
                val selector = listOf("selector", "item", "path", "link")
                    .firstNotNullOfOrNull { field -> (obj[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
                    ?: obj.toString()
                name to selector
            }
        }
    }

    /** The Code tab's names for Derive's keys (the key itself when unknown) */
    private fun derivedName(key: String): String = when (key) {
        "itemSelector" -> "Item"
        "titleSelector" -> "Title"
        "coverSelector" -> "Cover"
        "coverAttr" -> "Cover from"
        "popularPath" -> "Popular"
        "latestPath" -> "Latest"
        "searchPath" -> "Search"
        "nextSelector" -> "Next page"
        "urlPattern" -> "Title links"
        "singleEpisode" -> "One episode"
        "chapterSelector" -> "Chapter"
        "chapterUrlPattern" -> "Chapter links"
        "dateSelector" -> "Date"
        "numberSelector" -> "Number"
        "detailsCover" -> "Title cover"
        "detailsCoverAttr" -> "Title cover from"
        "descriptionSelector" -> "Description"
        "tagsSelector", "tagGroups" -> "Tags"
        "tagBlock" -> "Tags block" // KMK (r112): Writer C's details block (Derive)
        "suggestionGroups" -> "Suggestions"
        "authorSelector" -> "Author"
        "statusSelector" -> "Status"
        "imageSelector" -> "Page image"
        "imageAttr" -> "Image from"
        "videoFrame" -> "Video"
        "videoNote" -> "Video note"
        else -> key
    }

    /**
     * r100: the tap helper's card for the label just given (mockups `scratchpad/r100-helper/`): a badge (✓ green,
     * ! amber, i blue), the verdict and one sentence, the checks as chips, the suggestion when the page found one,
     * a few sample texts, and the buttons: Warn → Use suggestion / Keep mine; Ok → OK, next / Not this; a Video →
     * Test it now / OK; Up / Down move the pick and label it again. KMK (r115): one primary action ([HelperActions])
     * and ✕ (keep).
     */
    @Composable
    private fun HelperCard(state: BuilderScreenModel.State, model: BuilderScreenModel, wide: Boolean, modifier: Modifier) {
        // Remembered unconditionally, before any early return or branch (Compose rule)
        val cardScroll = rememberScrollState()
        val chipsScroll = rememberScrollState()
        val actionsScroll = rememberScrollState()
        val label = state.helperFor ?: return
        val result = state.checks[label] ?: return
        val colors = helperColors()
        val accent = colors.of(result.level)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
            modifier = modifier,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(max = if (wide) 340.dp else 520.dp)
                    .verticalScroll(cardScroll)
                    .padding(16.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
                ) {
                    Text(glyph(result.level), color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(result.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (result.body.isNotBlank()) {
                        Text(
                            result.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (result.checks.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(chipsScroll),
                        ) {
                            result.checks.forEach { check ->
                                // A failed check of an Info verdict is a note ("! address set by script"), not an error
                                val mark = when {
                                    check.ok -> "\u2713"
                                    result.level == PickCheck.Level.Info -> "!"
                                    else -> "\u2715"
                                }
                                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                    Text(
                                        "$mark ${check.text}",
                                        fontSize = 13.sp,
                                        color = if (check.ok) colors.ok else colors.warn,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    }
                    val suggestion = state.suggestion
                    if (result.level == PickCheck.Level.Warn && suggestion != null) {
                        Text(
                            "Did you mean the ${suggestionNoun(label)} with the dashed outline? ${suggestion.count} found${suggestionTail(label)}.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    if (result.samples.isNotEmpty()) {
                        val count = state.raws[label]?.count ?: result.samples.size
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            result.samples.forEachIndexed { i, sample ->
                                val more = if (i == result.samples.lastIndex && count > result.samples.size) "  · and ${count - result.samples.size} more" else ""
                                Text(
                                    sample + more,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (wide) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                            HelperActions(state, model, label, result)
                            Spacer(Modifier.weight(1f))
                            HelperMoves(model)
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).horizontalScroll(actionsScroll),
                        ) {
                            HelperActions(state, model, label, result)
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            HelperMoves(model)
                        }
                    }
                    val test = state.videoTest
                    if (label == "video" && test != null) {
                        Text(
                            test.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                test.running -> MaterialTheme.colorScheme.onSurfaceVariant
                                test.ok -> colors.ok
                                else -> colors.warn
                            },
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                // KMK (r115): ✕ keeps the pick as it is (the old "Keep mine" / "OK")
                IconButton(onClick = model::dismissHelper, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Keep it and close", modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    /**
     * KMK (r115, Helper 2): one primary action per verdict (was Use suggestion / Keep mine, Not this / Keep mine, OK,
     * next / Not this stacked): a Warn with a suggestion uses it, a Warn without one taps again (the pick goes and the
     * label stays armed), a Video tests, a lone chapter link says it's the only episode, an Ok goes on. ✕ on the card
     * keeps the pick; Remove is the label row's ✕.
     */
    @Composable
    private fun HelperActions(state: BuilderScreenModel.State, model: BuilderScreenModel, label: String, result: PickCheck.Result) {
        when {
            result.level == PickCheck.Level.Warn && state.suggestion != null -> {
                Button(onClick = model::useSuggestion) { Text("Use the dashed one") }
            }
            result.level == PickCheck.Level.Warn -> {
                Button(onClick = { model.applyFix(label, PickCheck.Fix(PickCheck.FixKind.TapAgain, "Tap again")) }) {
                    Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tap again")
                }
            }
            label == "video" -> {
                Button(onClick = model::testVideo, enabled = state.videoTest?.running != true) { Text("Test it now") }
            }
            label == "chapter" && result.level == PickCheck.Level.Info -> {
                Button(
                    onClick = {
                        model.setSingleEpisode(true)
                        model.dismissHelper()
                    },
                ) { Text("It's the only episode") }
            }
            else -> {
                val next = model.nextUnpicked(state)
                Button(onClick = model::helperNext) {
                    Text(
                        when {
                            next != null -> "OK, next: ${PickCheck.labelName(next, state.anime)}"
                            state.step == BuilderScreenModel.Step.ListPage -> "OK, next: open a title"
                            state.step == BuilderScreenModel.Step.TitlePage ->
                                if (state.singleEpisode) "OK, next: pick the video here" else "OK, next: open a chapter"
                            else -> "OK"
                        },
                    )
                }
            }
        }
    }

    /** Up / Down on the card: the pick moves and keeps its label */
    @Composable
    private fun HelperMoves(model: BuilderScreenModel) {
        TextButton(onClick = { model.helperMove("up") }) {
            Icon(Icons.Outlined.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Up")
        }
        TextButton(onClick = { model.helperMove("down") }) {
            Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Down")
        }
    }

    private class HelperColors(val ok: Color, val warn: Color, val info: Color, val fail: Color = Color(0xFFE5484D)) {
        fun of(level: PickCheck.Level): Color = when (level) {
            PickCheck.Level.Ok -> ok
            PickCheck.Level.Warn -> warn
            PickCheck.Level.Info -> info
        }
    }

    /** The mockups' green / amber / blue on a dark theme; deeper ones that read on a light theme */
    @Composable
    private fun helperColors(): HelperColors {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        return if (dark) {
            HelperColors(ok = Color(0xFF4CAF6A), warn = Color(0xFFF2B33D), info = Color(0xFF7CB4FF))
        } else {
            HelperColors(ok = Color(0xFF2E7D32), warn = Color(0xFFB26A00), info = Color(0xFF1565C0))
        }
    }

    private fun glyph(level: PickCheck.Level): String = when (level) {
        PickCheck.Level.Ok -> "\u2713"
        PickCheck.Level.Warn -> "!"
        PickCheck.Level.Info -> "i"
    }

    private fun suggestionNoun(label: String): String = when (label) {
        "item" -> "boxes"
        "cover", "image" -> "pictures"
        "bigCover" -> "picture"
        "description" -> "text"
        "tags" -> "tags"
        "video" -> "player"
        "next" -> "link"
        else -> "links"
    }

    private fun suggestionTail(label: String): String = when (label) {
        "item" -> ", each with a picture and a title link"
        "title" -> ", one in every box, each with the title's name"
        "cover" -> ", one in every box"
        "chapter" -> ", each opening a different one"
        else -> ""
    }

    private fun whatToTap(label: String): String = when (label) {
        "item" -> "a whole card"
        "title" -> "a title link"
        "cover" -> "a picture"
        "next" -> "the next-page link"
        "chapter" -> "an episode link"
        "date" -> "a date"
        "number" -> "a number"
        "image" -> "a page picture"
        "video" -> "the player"
        "bigCover" -> "the title's picture"
        "description" -> "the description"
        "tags" -> "the details (a row, its label or value)"
        "suggestions" -> "a card in a \u201cYou may like\u201d section"
        "author" -> "the author's name"
        "status" -> "the status"
        else -> "it on the page"
    }

    private companion object {
        // r106: the panel's tabs
        const val TAB_LABELS = 0
        const val TAB_TITLE = 1
        const val TAB_LISTS = 2
        const val TAB_CODE = 3
        val TAB_NAMES = listOf("Labels", "Title page", "Lists & filters", "Code")

        /** r106 fix: [BuilderScreen]'s startTab name as a tab (null = none / unknown) */
        fun tabOf(name: String?): Int? = when (name?.trim()?.lowercase()) {
            "labels" -> TAB_LABELS
            "title" -> TAB_TITLE
            "filters" -> TAB_LISTS
            "code" -> TAB_CODE
            else -> null
        }

        const val PICK_PREFIX = "LABPICK:"
        const val LOG_PREFIX = "LABLOG:"
        const val OPTS_PREFIX = "LABOPTS:"
        const val FORM_PREFIX = "LABFORM:"
        const val GROUP_PREFIX = "LABGROUP:"
        const val MENU_PREFIX = "LABMENU:"
    }

    /** r99: the step's page; the video step of an only-episode title is that title page */
    private fun stepTitle(state: BuilderScreenModel.State): String =
        if (state.step == BuilderScreenModel.Step.ChapterPage && state.singleEpisode) "title page (the only episode)" else state.step.title

    private fun labelName(label: String): String = when (label) {
        "item" -> "Item"
        "title" -> "Title · link"
        "cover" -> "Cover"
        "next" -> "Next page"
        "chapter" -> "Chapter link"
        "date" -> "Date"
        "number" -> "Number"
        "image" -> "Page image"
        "video" -> "Video frame"
        // r101: the title page's own fields
        "bigCover" -> "Cover"
        "description" -> "Description"
        "tags" -> "Tags"
        "author" -> "Author"
        "status" -> "Status"
        // r104: a title page's "You may like" / "Related" sections
        "suggestions" -> "Suggestions"
        else -> label
    }
}
