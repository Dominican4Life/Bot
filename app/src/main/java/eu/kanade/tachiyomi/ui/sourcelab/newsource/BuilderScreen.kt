package eu.kanade.tachiyomi.ui.sourcelab.newsource

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.jssource.lab.Derive
import eu.kanade.tachiyomi.jssource.lab.LabFlowScreen
import eu.kanade.tachiyomi.jssource.lab.PickCheck
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.sourcelab.LabStatusStrip
import eu.kanade.tachiyomi.ui.sourcelab.StripColor
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
 */
internal class BuilderScreen(
    private val startUrl: String? = null,
    private val anime: Boolean = false,
    private val editKey: String? = null,
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
        val model = rememberScreenModel { BuilderScreenModel(startUrl, anime, editKey) }
        val state by model.state.collectAsState()
        val tablet = isTabletUi()
        val holder = remember { arrayOfNulls<WebView>(1) }

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
        // r102: the armed label, told to the page's picker as it changes (and before picker.js on every injection)
        LaunchedEffect(state.armed) {
            holder[0]?.evaluateJavascript(armScript(state.armed), null)
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

        // r101: the title page's own fields are optional: counted apart ("5 of 8 · +2")
        val picked = state.picks.count { it.pick.label !in BuilderScreenModel.optionalLabels }
        val extraPicked = state.picks.size - picked
        val wanted = BuilderScreenModel.Step.ListPage.labels.size + BuilderScreenModel.Step.TitlePage.labels.size + 1 -
            BuilderScreenModel.optionalLabels.size
        Scaffold(
            topBar = {
                AppBar(
                    title = if (state.editKey != null) "Edit by tapping" else "Build by tapping",
                    subtitle = (state.editName?.let { "$it · " } ?: "") +
                        "${state.host} · ${stepTitle(state)} · step ${state.step.number} of 3",
                    navigateUp = {
                        if (state.step != BuilderScreenModel.Step.ListPage) model.previousStep() else navigator.pop()
                        Unit
                    },
                    navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                LabStatusStrip(
                    head = "Picking",
                    color = StripColor.Blue,
                    figures = listOf(
                        "Picked" to ("$picked of $wanted" + (if (extraPicked > 0) " · +$extraPicked" else "")),
                        "Kind" to if (state.anime) "Anime" else "Manga",
                        "Step" to "${state.step.number} / 3",
                    ),
                )
                if (state.busy != null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                // r102: Edit by tapping says what came back (or what was read from the code)
                EditBanner(state, model)
                UrlRow(state, model, holder)
                // r103: one line, only until the first pick (the "Tapping for…" rows are gone: the page gets the room)
                if (state.picks.isEmpty() && state.raws.isEmpty() && state.pending == null && state.armed == null) {
                    Text(
                        "Tap a label in Labels${if (tablet) " (right)" else " (below the page)"}, then tap it on the page; or " +
                            "long-press anything on the page and name it after.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                // r99: Up / Down move the pick on the page; picker.js sends the new element back as a pick
                PickHint(state, model) { direction ->
                    holder[0]?.evaluateJavascript("window.__labPicker && window.__labPicker.$direction()", null)
                    Unit
                }
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
                if (tablet) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        WebCard(
                            model,
                            holder,
                            onCreated = { webGeneration++ },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, bottom = 16.dp),
                        ) {
                            // r103: "Tapping for: X · Stop" (and a refused Tags tap's note) over the top of the page
                            TopBars(state, model)
                            // Over the bottom of the page, as in the mockups
                            if (showHelper) {
                                HelperCard(
                                    state,
                                    model,
                                    wide = true,
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .width(400.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                        ) {
                            TagsCard(state, model)
                            LabelsPane(state, model)
                            Spacer(Modifier.height(12.dp))
                            HelperSwitch(state, model)
                            Spacer(Modifier.height(12.dp))
                            BuilderExtrasPane(extras = model.extras, pageUrl = state.pageUrl, siteUrl = state.site, onAskOptions = askOptions)
                            Spacer(Modifier.height(12.dp))
                            DerivedCard(state)
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        WebCard(
                            model,
                            holder,
                            onCreated = { webGeneration++ },
                            modifier = Modifier.fillMaxWidth().height(420.dp).padding(horizontal = 16.dp),
                        ) {
                            TopBars(state, model)
                        }
                        // On a phone the page is small: the helper goes under it instead of over it
                        if (showHelper) {
                            HelperCard(state, model, wide = false, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp))
                        }
                        Column(modifier = Modifier.padding(16.dp)) {
                            TagsCard(state, model)
                            LabelsPane(state, model)
                            Spacer(Modifier.height(12.dp))
                            HelperSwitch(state, model)
                            Spacer(Modifier.height(12.dp))
                            BuilderExtrasPane(extras = model.extras, pageUrl = state.pageUrl, siteUrl = state.site, onAskOptions = askOptions)
                            Spacer(Modifier.height(12.dp))
                            DerivedCard(state)
                        }
                    }
                }
            }
        }

        val error = state.error
        if (error != null) {
            AlertDialog(
                onDismissRequest = model::clearError,
                title = { Text("Build by tapping") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = model::clearError) { Text("OK") } },
            )
        }
    }

    /** The browser bar: Back · Forward · Refresh, the address, Go */
    @Composable
    private fun UrlRow(state: BuilderScreenModel.State, model: BuilderScreenModel, holder: Array<WebView?>) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            IconButton(onClick = { holder[0]?.takeIf { it.canGoBack() }?.goBack() }, enabled = state.canGoBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = { holder[0]?.takeIf { it.canGoForward() }?.goForward() }, enabled = state.canGoForward) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Forward")
            }
            IconButton(onClick = { holder[0]?.reload() }, enabled = state.pageUrl != null) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
            }
            OutlinedTextField(
                value = state.url,
                onValueChange = model::setUrl,
                singleLine = true,
                placeholder = { Text("https://…") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { model.load(state.url) }, enabled = state.url.isNotBlank()) { Text("Go") }
        }
    }

    /**
     * r103: over the top of the page: one slim "Tapping for: <Label> · Stop" bar while a label (or "Pick a menu on the
     * site") is armed, and a short note (a Tags tap in the site's menu, kept while the helper is off) with its OK
     */
    @Composable
    private fun BoxScope.TopBars(state: BuilderScreenModel.State, model: BuilderScreenModel) {
        val extrasState by model.extras.state.collectAsState()
        val armed = state.armed
        val what = when {
            armed == "tags" -> "Tags (each row you tap is a group)"
            armed != null -> PickCheck.labelName(armed, state.anime)
            extrasState.groupMode -> "a menu on the site (a heading, a dropdown's button or an entry)"
            else -> null
        }
        val notice = state.notice
        if (what == null && notice == null) return
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
        ) {
            if (what != null) {
                SlimBar(
                    text = "Tapping for: $what",
                    action = "Stop",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                ) {
                    if (armed != null) model.arm(null) else model.extras.setGroupMode(false)
                }
            }
            if (notice != null) {
                SlimBar(
                    text = notice,
                    action = "OK",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                    onAction = model::dismissNotice,
                )
            }
        }
    }

    @Composable
    private fun SlimBar(text: String, action: String, container: Color, content: Color, onAction: () -> Unit) {
        Surface(shape = RoundedCornerShape(10.dp), color = container, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Icon(Icons.Outlined.TouchApp, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAction) { Text(action, color = content) }
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WebCard(
        model: BuilderScreenModel,
        holder: Array<WebView?>,
        onCreated: () -> Unit,
        modifier: Modifier,
        overlay: @Composable BoxScope.() -> Unit = {},
    ) {
        val userAgent = remember { Injekt.get<NetworkHelper>().defaultUserAgentProvider() }
        Surface(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = modifier,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
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

                                // r102: the WebView's own address (view.url), not the callback's: after Back a late
                                // callback could put the previous page in the bar
                                override fun onPageFinished(view: WebView?, url: String?) {
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
                // r100: the helper card (tablet), drawn over the page
                overlay()
            }
        }
    }

    @Composable
    private fun LabelsPane(state: BuilderScreenModel.State, model: BuilderScreenModel) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Labels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            state.pending != null -> "now tap a label"
                            state.armed != null -> "tap the page · tap the row again to stop"
                            state.picks.isEmpty() -> "tap a label, then tap it on the page"
                            else -> labelsSummary(state)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val all = state.allLabels
                all.forEach { label ->
                    // r101: the title page's own fields, under their own heading (none is needed)
                    if (label == BuilderScreenModel.optionalLabels.firstOrNull { it in all }) {
                        Text(
                            "Title page details · optional",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                    val pick = state.pick(label)
                    val here = label in state.labels
                    // r98: with a tap waiting, the labels take it (the same as the chips above the page); r102: every
                    // label, on any page; with no tap waiting a row arms its label ("Tapping for…")
                    val choosable = state.pending != null
                    val armedHere = state.armed == label
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    choosable || armedHere -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else -> Color.Transparent
                                },
                            )
                            .clickable {
                                if (choosable) model.label(label) else model.arm(if (armedHere) null else label)
                            }
                            .padding(vertical = 6.dp),
                    ) {
                        // r100: the helper's verdict per row: ✓ green, ! amber, i blue; ○ grey when not picked
                        val check = if (pick != null) state.checks[label] else null
                        StatusGlyph(
                            level = check?.level,
                            picked = pick != null || state.done(label),
                            idleColor = if (choosable) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(labelName(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(110.dp))
                        Text(
                            text = when {
                                choosable -> "tap here: it is the ${labelName(label).lowercase()}"
                                armedHere -> "tapping for this now: tap ${whatToTap(label)} · tap again to stop"
                                label == "tags" && state.tagGroups.isNotEmpty() ->
                                    "${state.tagGroups.size} ${if (state.tagGroups.size == 1) "group" else "groups"}: " +
                                        state.tagGroups.joinToString(", ") { it.name }
                                pick != null -> pick.likeSelector.substringAfterLast(" > ") + " · ${pick.pick.count}"
                                state.fromCodeOnly(label) -> "from the code"
                                state.singleEpisode && label == "chapter" -> "not needed: this page is the episode"
                                here && state.nextLabel == label -> "next: tap here, then ${whatToTap(label)}"
                                here && label in BuilderScreenModel.optionalLabels -> "optional: tap here, then ${whatToTap(label)}"
                                here -> "tap here, then tap it on the page"
                                label in BuilderScreenModel.Step.TitlePage.labels -> "on a title page: tap here, then tap it"
                                label in BuilderScreenModel.Step.ListPage.labels -> "on the list page: tap here, then tap it"
                                else -> "on an episode page: tap here, then tap it"
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (pick != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (pick != null || (label == "tags" && state.tagGroups.isNotEmpty())) {
                            IconButton(onClick = { model.removePick(label) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    HorizontalDivider()
                }
                // r99: a title page that is its own only episode (one video per page, no episode list)
                if (state.step == BuilderScreenModel.Step.TitlePage || state.singleEpisode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { model.setSingleEpisode(!state.singleEpisode) }
                            .padding(top = 10.dp, bottom = 4.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("This page is the only episode", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (state.singleEpisode) {
                                    "No chapter link needed: Date / Number are read on this page, and the video frame is picked on it next" +
                                        if (state.json) ". Written as JS (the JSON format lists episodes from links)." else "."
                                } else {
                                    "Turn on when the title has one video and no episode list"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = state.singleEpisode, onCheckedChange = model::setSingleEpisode)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    FilterChip(selected = !state.anime, onClick = { model.setAnime(false) }, label = { Text("Manga") })
                    FilterChip(selected = state.anime, onClick = { model.setAnime(true) }, label = { Text("Anime") })
                    Spacer(Modifier.weight(1f))
                    FilterChip(selected = !state.json, onClick = { model.setJson(false) }, label = { Text("JS") })
                    FilterChip(selected = state.json, onClick = { model.setJson(true) }, label = { Text("JSON") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Button(onClick = model::nextStep, enabled = state.busy == null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (state.step) {
                                BuilderScreenModel.Step.ListPage -> "Next: open a title"
                                BuilderScreenModel.Step.TitlePage -> if (state.singleEpisode) "Next: pick the video here" else "Next: open a chapter"
                                BuilderScreenModel.Step.ChapterPage -> if (state.editKey != null) "Save to this source" else "Write the source"
                            },
                        )
                    }
                    OutlinedButton(onClick = model::undo, enabled = state.picks.isNotEmpty() || state.pending != null) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Undo")
                    }
                }
                if (state.editKey != null && state.step != BuilderScreenModel.Step.ChapterPage) {
                    // r102: Edit by tapping saves from any step (a new History version of the same source)
                    Button(onClick = model::write, enabled = state.busy == null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("Save to this source")
                    }
                } else if (state.step != BuilderScreenModel.Step.ChapterPage) {
                    TextButton(onClick = model::write, enabled = state.picks.isNotEmpty() && state.busy == null, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Write the source with what is picked so far")
                    }
                }
            }
        }
    }

    /** r102: Edit by tapping: what came back from the saved source (or was read from its code) */
    @Composable
    private fun EditBanner(state: BuilderScreenModel.State, model: BuilderScreenModel) {
        val text = state.banner ?: return
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                IconButton(onClick = model::dismissBanner) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }

    /**
     * r102: the Tags card (mockup 2): one row per group (the Details row's name, its first values, ⋮ → Rename /
     * Remove) and where its values go (Tags · Description · Both). Shown while there are groups or Tags is armed.
     */
    @Composable
    private fun TagsCard(state: BuilderScreenModel.State, model: BuilderScreenModel) {
        // Remembered before any return (Compose rule)
        var renaming by remember { mutableStateOf<Int?>(null) }
        var renameText by remember { mutableStateOf("") }
        if (state.tagGroups.isEmpty() && state.armed != "tags") return
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tags", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            state.tagGroups.isEmpty() -> "tap a row of the details"
                            state.armed == "tags" -> "${state.tagGroups.size} ${if (state.tagGroups.size == 1) "group" else "groups"} \u00b7 tap more to add"
                            else -> "${state.tagGroups.size} ${if (state.tagGroups.size == 1) "group" else "groups"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.tagGroups.forEachIndexed { index, group ->
                    key(index, group.selector) {
                        TagGroupRow(
                            index = index,
                            group = group,
                            model = model,
                            onRename = {
                                renameText = group.name
                                renaming = index
                            },
                        )
                    }
                }
                Text(
                    "Each row you tap is a group named by its own label (Cast, Maker\u2026); rename it with \u22ee. A group's values " +
                        "show as tags (\u201cCast: Reina Ohara\u201d), in the description, or both. Tapping the same row again replaces it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        val index = renaming
        if (index != null) {
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Rename the group") },
                text = {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it.take(30) }, singleLine = true)
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            model.renameTagGroup(index, renameText)
                            renaming = null
                        },
                        enabled = renameText.isNotBlank(),
                    ) { Text("Rename") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun TagGroupRow(index: Int, group: Derive.TagGroup, model: BuilderScreenModel, onRename: () -> Unit) {
        var menu by remember { mutableStateOf(false) }
        val scroll = rememberScrollState()
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\u2713", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                Text(group.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
                Text(
                    group.values.take(3).joinToString(", ") + if (group.values.size > 3) " \u2026 (${group.values.size})" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            onClick = {
                                menu = false
                                model.removeTagGroup(index)
                            },
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 22.dp).horizontalScroll(scroll),
            ) {
                listOf("tags" to "Tags", "description" to "Description", "both" to "Both").forEach { (target, name) ->
                    FilterChip(
                        selected = group.target == target,
                        onClick = { model.setTagGroupTarget(index, target) },
                        label = { Text(name) },
                    )
                }
            }
        }
    }

    /** r100: "Check each pick" (the helper), its own small card in the side pane as in the mockups */
    @Composable
    private fun HelperSwitch(state: BuilderScreenModel.State, model: BuilderScreenModel) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { model.setHelper(!state.helper) }
                    .padding(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Helper", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.helper) "Check each pick: every label is checked and a better match suggested" else "Check each pick",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.helper, onCheckedChange = model::setHelper)
            }
        }
    }

    /**
     * r100: the tap helper's card for the label just given (mockups `scratchpad/r100-helper/`): a badge (✓ green,
     * ! amber, i blue), the verdict and one sentence, the checks as chips, the suggestion when the page found one,
     * a few sample texts, and the buttons: Warn → Use suggestion / Keep mine; Ok → OK, next / Not this; a Video →
     * Test it now / OK; Up / Down move the pick and label it again.
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
            }
        }
    }

    /** The card's two main buttons, by verdict and label */
    @Composable
    private fun HelperActions(state: BuilderScreenModel.State, model: BuilderScreenModel, label: String, result: PickCheck.Result) {
        when {
            result.level == PickCheck.Level.Warn && state.suggestion != null -> {
                Button(onClick = model::useSuggestion) { Text("Use suggestion") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = model::dismissHelper) { Text("Keep mine") }
            }
            result.level == PickCheck.Level.Warn -> {
                Button(onClick = model::rejectHelperPick) { Text("Not this") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = model::dismissHelper) { Text("Keep mine") }
            }
            label == "video" -> {
                Button(onClick = model::testVideo, enabled = state.videoTest?.running != true) { Text("Test it now") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = model::dismissHelper) { Text("OK") }
            }
            label == "chapter" && result.level == PickCheck.Level.Info -> {
                Button(
                    onClick = {
                        model.setSingleEpisode(true)
                        model.dismissHelper()
                    },
                ) { Text("It's the only episode") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = model::dismissHelper) { Text("OK") }
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
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = model::rejectHelperPick) { Text("Not this") }
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

    /** A Labels row's status: ✓ ! i by the helper's verdict, ✓ when picked with no verdict, ○ when not picked */
    @Composable
    private fun StatusGlyph(level: PickCheck.Level?, picked: Boolean, idleColor: Color) {
        val colors = helperColors()
        val (text, color) = when {
            level != null -> glyph(level) to colors.of(level)
            picked -> "\u2713" to MaterialTheme.colorScheme.primary
            else -> "\u25CB" to idleColor
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
            Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }

    private class HelperColors(val ok: Color, val warn: Color, val info: Color) {
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

    /** "Labels" head: "2 picked · all good", "1 picked · 1 to check", "5 picked · 1 note" */
    private fun labelsSummary(state: BuilderScreenModel.State): String {
        val levels = state.picks.mapNotNull { state.checks[it.pick.label]?.level }
        val warn = levels.count { it == PickCheck.Level.Warn }
        val info = levels.count { it == PickCheck.Level.Info }
        val tail = when {
            warn > 0 -> "$warn to check"
            info > 0 -> if (info == 1) "1 note" else "$info notes"
            levels.isNotEmpty() -> "all good"
            else -> "tap the page, then a label"
        }
        return "${state.picks.size} picked · $tail"
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
        "tags" -> "a genre / tag"
        "author" -> "the author's name"
        "status" -> "the status"
        else -> "it on the page"
    }

    @Composable
    private fun DerivedCard(state: BuilderScreenModel.State) {
        val selection = state.selection
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("What the Lab derived", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (selection == null || selection.derived.isEmpty()) {
                    Text(
                        "Nothing yet: every value below comes with the reason it was chosen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    // r99: `warning…` entries (an Item that doesn't hold the title, a player that isn't one) first, in red
                    val (warnings, values) = selection.derived.entries.partition { it.key.startsWith("warning") }
                    warnings.forEach { (key, value) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(selection.reasons[key].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    values.forEach { (key, value) ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row {
                                Text(key, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(120.dp))
                                Text(value.ifEmpty { "(none)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            }
                            Text(
                                selection.reasons[key].orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 120.dp, top = 2.dp),
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    private companion object {
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
        else -> label
    }
}
