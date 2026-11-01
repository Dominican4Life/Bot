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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import eu.kanade.tachiyomi.jssource.lab.LabFlowScreen
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
 */
internal class BuilderScreen(private val startUrl: String? = null, private val anime: Boolean = false) : Screen(), LabFlowScreen {

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
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { BuilderScreenModel(startUrl, anime) }
        val state by model.state.collectAsState()
        val tablet = isTabletUi()
        val holder = remember { arrayOfNulls<WebView>(1) }

        LaunchedEffect(state.delivered) {
            if (state.delivered) navigator.popUntil { it !is LabFlowScreen }
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
        // Pick / Browse: told to the page's picker as it changes (and after every injection, see WebCard)
        LaunchedEffect(state.pickMode) {
            holder[0]?.evaluateJavascript(modeScript(state.pickMode), null)
        }
        // Android Back: the page's own history first, then this screen only (back to New source, not out of the Lab)
        BackHandler(enabled = true) {
            val web = holder[0]
            if (web != null && web.canGoBack()) web.goBack() else navigator.pop()
        }
        // After a label: keep the outline on what was labelled
        LaunchedEffect(state.keepTick) {
            val selector = state.keepSelector ?: return@LaunchedEffect
            holder[0]?.evaluateJavascript("window.__labPicker && window.__labPicker.keep(${JsonPrimitive(selector)})", null)
        }
        DisposableEffect(Unit) {
            onDispose {
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

        val picked = state.picks.size
        val wanted = BuilderScreenModel.Step.ListPage.labels.size + BuilderScreenModel.Step.TitlePage.labels.size + 1
        Scaffold(
            topBar = {
                AppBar(
                    title = "Build by tapping",
                    subtitle = "${state.host} · ${stepTitle(state)} · step ${state.step.number} of 3",
                    navigateUp = {
                        if (state.step != BuilderScreenModel.Step.ListPage) model.previousStep() else navigator.pop()
                        Unit
                    },
                    navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                    actions = {
                        // Pick: taps select, nothing navigates. Browse: the site works as usual (open a title by hand)
                        FilterChip(
                            selected = state.pickMode,
                            onClick = { model.setPickMode(true) },
                            label = { Text("Pick") },
                            leadingIcon = { Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        Spacer(Modifier.width(6.dp))
                        FilterChip(
                            selected = !state.pickMode,
                            onClick = { model.setPickMode(false) },
                            label = { Text("Browse") },
                            leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        Spacer(Modifier.width(8.dp))
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                LabStatusStrip(
                    head = "Picking",
                    color = StripColor.Blue,
                    figures = listOf(
                        "Picked" to "$picked of $wanted",
                        "Kind" to if (state.anime) "Anime" else "Manga",
                        "Step" to "${state.step.number} / 3",
                    ),
                )
                if (state.busy != null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(
                    text = if (state.pickMode) {
                        "Tap a thing on the page, then the label it is. Repeated boxes light up together. Nothing opens in Pick mode."
                    } else {
                        "Browse mode: the site works as usual. Switch back to Pick to select things on this page."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                UrlRow(state, model, holder)
                // r99: Up / Down move the pick on the page; picker.js sends the new element back as a pick
                PickHint(state, model) { direction ->
                    holder[0]?.evaluateJavascript("window.__labPicker && window.__labPicker.$direction()", null)
                    Unit
                }
                if (tablet) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        WebCard(
                            model,
                            holder,
                            onCreated = { webGeneration++ },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, bottom = 16.dp),
                        )
                        Column(
                            modifier = Modifier
                                .width(400.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                        ) {
                            LabelsPane(state, model)
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
                        )
                        Column(modifier = Modifier.padding(16.dp)) {
                            LabelsPane(state, model)
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
                    state.labels.forEach { label ->
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

    /** `window.__labPicker.setMode(...)`: a no-op until the picker is on the page */
    private fun modeScript(pick: Boolean): String =
        "window.__labPicker && window.__labPicker.setMode(${JsonPrimitive(if (pick) "pick" else "browse")})"

    /**
     * Puts picker.js on the page as it is **now** in the model (not as it was when the WebView was made: in r96
     * `onPageFinished` closed over the first composition's `pickerScript`, null while the asset was still being
     * read, so the script never reached a page from there), then tells it the mode. Idempotent on the page side.
     */
    private fun inject(view: WebView, model: BuilderScreenModel) {
        val script = model.state.value.pickerScript ?: return
        // One evaluation: no page script runs between setting the token and picker.js taking it
        view.evaluateJavascript("window.__labToken = ${JsonPrimitive(labToken)};\n$script", null)
        view.evaluateJavascript(modeScript(model.state.value.pickMode), null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WebCard(model: BuilderScreenModel, holder: Array<WebView?>, onCreated: () -> Unit, modifier: Modifier) {
        val userAgent = remember { Injekt.get<NetworkHelper>().defaultUserAgentProvider() }
        Surface(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = modifier,
        ) {
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
                                return when {
                                    text.startsWith(pick) -> {
                                        model.onPick(text.removePrefix(pick), viaConsole = true)
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

                            override fun onPageFinished(view: WebView?, url: String?) {
                                view ?: return
                                model.onPageLoaded(url, view.canGoBack(), view.canGoForward())
                                inject(view, model)
                            }

                            // In-page navigations (history.pushState / SPA routes) don't finish a page: the picker
                            // is put back (a no-op when the window still has it) and the bar's Back / Forward follow
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                view ?: return
                                model.onPageLoaded(url, view.canGoBack(), view.canGoForward())
                                model.syncStepWithUrl(url) // the WebView's Back to the list page = step 1 again
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
        }
    }

    @Composable
    private fun LabelsPane(state: BuilderScreenModel.State, model: BuilderScreenModel) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Labels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        if (state.pending != null) "now tap a label" else "${state.picks.size} picked · tap the page, then a label",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val all = BuilderScreenModel.Step.ListPage.labels + BuilderScreenModel.Step.TitlePage.labels + listOf(if (state.anime) "video" else "image")
                all.forEach { label ->
                    val pick = state.pick(label)
                    val here = label in state.labels
                    // r98: with a tap waiting, this page's labels take it (the same as the chips above the page)
                    val choosable = here && state.pending != null
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (choosable) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                            .clickable(enabled = choosable) { model.label(label) }
                            .padding(vertical = 6.dp),
                    ) {
                        Icon(
                            if (pick != null) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = when {
                                pick != null -> MaterialTheme.colorScheme.primary
                                choosable -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.outline
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(labelName(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(110.dp))
                        Text(
                            text = when {
                                choosable -> "tap here: it is the ${labelName(label).lowercase()}"
                                pick != null -> pick.likeSelector.substringAfterLast(" > ") + " · ${pick.pick.count}"
                                state.singleEpisode && label == "chapter" -> "not needed: this page is the episode"
                                here -> "tap it on the page"
                                label in BuilderScreenModel.Step.TitlePage.labels -> "open a title to pick it"
                                else -> "open a chapter to pick it"
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (pick != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (pick != null) {
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
                                BuilderScreenModel.Step.ChapterPage -> "Write the source"
                            },
                        )
                    }
                    OutlinedButton(onClick = model::undo, enabled = state.picks.isNotEmpty() || state.pending != null) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Undo")
                    }
                }
                if (state.step != BuilderScreenModel.Step.ChapterPage) {
                    TextButton(onClick = model::write, enabled = state.picks.isNotEmpty() && state.busy == null, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Write the source with what is picked so far")
                    }
                }
            }
        }
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
        else -> label
    }
}
