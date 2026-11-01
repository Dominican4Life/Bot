package eu.kanade.tachiyomi.ui.sourcelab.newsource

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.runtime.remember
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
        // Page requests from the model (Go, Next: open a title / a chapter)
        LaunchedEffect(state.loadTick) {
            val url = state.loadUrl ?: return@LaunchedEffect
            holder[0]?.loadUrl(url)
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
                    text = "Tap a thing on the page, then the label it is. Repeated boxes light up together.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                UrlRow(state, model)
                if (tablet) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        WebCard(model, holder, state.pickerScript, modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, bottom = 16.dp))
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
                        WebCard(model, holder, state.pickerScript, modifier = Modifier.fillMaxWidth().height(420.dp).padding(horizontal = 16.dp))
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

    @Composable
    private fun UrlRow(state: BuilderScreenModel.State, model: BuilderScreenModel) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WebCard(model: BuilderScreenModel, holder: Array<WebView?>, pickerScript: String?, modifier: Modifier) {
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
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                            override fun onPageFinished(view: WebView?, url: String?) {
                                model.onPageLoaded(url)
                                val script = pickerScript ?: return
                                view?.evaluateJavascript(script, null)
                            }
                        }
                        holder[0] = this
                        val start = model.state.value.loadUrl
                        if (start != null) loadUrl(start)
                    }
                },
                update = { view ->
                    // A picker script that arrived after the first page: inject it now
                    if (pickerScript != null && model.state.value.pageUrl != null) {
                        view.evaluateJavascript(pickerScript, null)
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
