package eu.kanade.tachiyomi.ui.sourcelab.newsource

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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

/**
 * KMK (r96, Source Lab): "Build by tapping" (D§2.4, still 04). The site in a WebView card with picker.js
 * injected after every page load; a tap outlines the element and its look-alikes and posts it through
 * `LabPicker.pick`; the label sheet under the card names it (Item · Title · Cover · Next page on a list page,
 * Chapter · Date · Number on a title, Page image or Video frame on a chapter). "What the Lab derived" shows
 * every decision with its reason as it is made. Done → the generic-list template, handed to Half A.
 */
internal class BuilderScreen(private val startUrl: String? = null, private val anime: Boolean = false) : Screen(), LabFlowScreen {

    /** What the page calls: `LabPicker.pick(json)` and `LabPicker.log(text)`; runs on a WebView thread */
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
        // rotation the tick is already handled (the recreated WebView reloads the page shown, see WebCard's factory)
        var handledLoadTick by rememberSaveable { mutableIntStateOf(-1) }
        LaunchedEffect(state.loadTick) {
            if (state.loadTick == handledLoadTick) return@LaunchedEffect
            handledLoadTick = state.loadTick
            val url = state.loadUrl ?: return@LaunchedEffect
            holder[0]?.loadUrl(url)
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
                    subtitle = "${state.host} · ${state.step.title} · step ${state.step.number} of 3",
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
                if (tablet) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        WebCard(model, holder, modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, bottom = 16.dp))
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
                        WebCard(model, holder, modifier = Modifier.fillMaxWidth().height(420.dp).padding(horizontal = 16.dp))
                        Column(modifier = Modifier.padding(16.dp)) {
                            LabelsPane(state, model)
                            Spacer(Modifier.height(12.dp))
                            DerivedCard(state)
                        }
                    }
                }
            }
        }

        val pending = state.pending
        if (pending != null) {
            AlertDialog(
                onDismissRequest = model::dismissPending,
                title = { Text("What is it?") },
                text = {
                    Column {
                        Text(
                            "${pending.tag} · ${pending.count} like it on the page" + (if (pending.text.isNotBlank()) " · \"${pending.text.take(60)}\"" else ""),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(pending.likeSelector, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.labels.forEach { label ->
                                FilterChip(selected = false, onClick = { model.label(label) }, label = { Text(labelName(label)) })
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = model::dismissPending) { Text("Not this") } },
            )
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
        view.evaluateJavascript(script, null)
        view.evaluateJavascript(modeScript(model.state.value.pickMode), null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WebCard(model: BuilderScreenModel, holder: Array<WebView?>, modifier: Modifier) {
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
                        addJavascriptInterface(Bridge(model), "LabPicker")
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
                        "${state.picks.size} picked · tap the page, then a label",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val all = BuilderScreenModel.Step.ListPage.labels + BuilderScreenModel.Step.TitlePage.labels + listOf(if (state.anime) "video" else "image")
                all.forEach { label ->
                    val pick = state.pick(label)
                    val here = label in state.labels
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Icon(
                            if (pick != null) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (pick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(labelName(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(110.dp))
                        Text(
                            text = when {
                                pick != null -> pick.likeSelector.substringAfterLast(" > ") + " · ${pick.pick.count}"
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
                                BuilderScreenModel.Step.TitlePage -> "Next: open a chapter"
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
                    selection.derived.forEach { (key, value) ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row {
                                Text(key, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(120.dp))
                                Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
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
