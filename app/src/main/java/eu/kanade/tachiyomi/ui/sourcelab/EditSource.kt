package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.lab.LabMeta
import eu.kanade.tachiyomi.jssource.lab.labMeta
import eu.kanade.tachiyomi.ui.sourcelab.newsource.BuilderScreen
import eu.kanade.tachiyomi.ui.sourcelab.tapfix.TapFixScreen
import eu.kanade.tachiyomi.util.system.AppLog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r118): the Edit button (Source Lab's source ⋮, Browse ⋮ "Edit source"). A source the builder wrote opens in
 * the builder (Edit by tapping); any other Source Lab source opens Tap to fix (only the picked selector text in its
 * code changes). "Edit the code" opens the Lab's code editor on it.
 */
internal object EditSource {

    fun madeByTapping(lab: LabMeta?): Boolean =
        lab != null && (lab.engine == "generic-list" || lab.templateId?.startsWith("generic-list") == true)

    fun info(key: String): JsSourceInfo? = runCatching { Injekt.get<JsSourceManager>().get(key) }.getOrNull()

    /** The Source Lab source behind a Browse source id, or null when it isn't one */
    fun keyOf(sourceId: Long): String? = runCatching {
        Injekt.get<JsSourceManager>().savedFlow.value.firstOrNull { it.id == sourceId }?.key
    }.getOrNull()

    fun openByTapping(navigator: Navigator, key: String) {
        val info = info(key) ?: return
        val lab = info.labMeta
        val start = lab?.siteUrl?.trim()?.takeIf { it.startsWith("http") }
            ?: info.baseUrl.trim().takeIf { it.startsWith("http") }
        if (madeByTapping(lab)) {
            AppLog.add('I', "lab", "Edit: ${info.name} opens in the builder (made by tapping)")
            navigator.push(BuilderScreen(startUrl = start, anime = info.anime, editKey = key))
        } else {
            AppLog.add('I', "lab", "Edit: ${info.name} opens in Tap to fix")
            navigator.push(TapFixScreen(key))
        }
    }

    fun openCode(navigator: Navigator, key: String) {
        navigator.push(SourceLabScreen(sourceKey = key, initialTab = LabTab.Code))
    }
}

/** KMK (r118): "Edit <name>": by tapping (builder or Tap to fix) or the code */
@Composable
internal fun EditSourceDialog(
    key: String,
    name: String,
    navigator: Navigator,
    onDismiss: () -> Unit,
) {
    val tapped = EditSource.madeByTapping(EditSource.info(key)?.labMeta)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${name.ifBlank { "this source" }}") },
        text = {
            Column {
                EditChoice(
                    icon = { Icon(Icons.Outlined.TouchApp, contentDescription = null) },
                    title = if (tapped) "Edit by tapping" else "Tap to fix",
                    subtitle = if (tapped) {
                        "Opens the site with your picks; tap to change them"
                    } else {
                        "Tap the right thing on the site; only that part of the code changes"
                    },
                    onClick = {
                        onDismiss()
                        EditSource.openByTapping(navigator, key)
                    },
                )
                EditChoice(
                    icon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                    title = "Edit the code",
                    subtitle = "Opens the code editor. Every save is kept in History",
                    onClick = {
                        onDismiss()
                        EditSource.openCode(navigator, key)
                    },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditChoice(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    )
}

/** KMK (r118): Browse ⋮'s edit row: [copy] = "Make an editable copy" (an installed extension) else "Edit source" */
data class BrowseEditAction(val copy: Boolean, val onClick: () -> Unit)

/**
 * KMK (r118): Browse ⋮ for [sourceId]: a Source Lab source gets "Edit source" (the Edit dialog, drawn here when
 * open); another online source gets "Make an editable copy" (Record and convert, the Lab underneath so the new source
 * opens there). Null for the local source and when there's no navigator.
 */
@Composable
fun rememberBrowseEditAction(sourceId: Long?, anime: Boolean, allowCopy: Boolean = true): BrowseEditAction? {
    val navigator = cafe.adriel.voyager.navigator.LocalNavigator.current
    var editing by rememberSaveable { mutableStateOf(false) }
    val key = remember(sourceId) { sourceId?.let(EditSource::keyOf) }
    if (sourceId == null || navigator == null) return null
    if (key != null) {
        if (editing) {
            EditSourceDialog(
                key = key,
                name = EditSource.info(key)?.name.orEmpty(),
                navigator = navigator,
                onDismiss = { editing = false },
            )
        }
        return BrowseEditAction(copy = false) { editing = true }
    }
    if (!allowCopy || sourceId == tachiyomi.source.local.LocalSource.ID) return null
    // Only for a source of an installed extension (built-in sources have no APK to record)
    val installed by androidx.compose.runtime.produceState(initialValue = false, sourceId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { eu.kanade.tachiyomi.jssource.lab.ApkImport.installed().any { it.sourceId == sourceId } }
                .getOrDefault(false)
        }
    }
    if (!installed) return null
    return BrowseEditAction(copy = true) {
        AppLog.add('I', "lab", "Make an editable copy of source $sourceId (from Browse)")
        navigator.push(
            listOf(
                SourceLabScreen(),
                eu.kanade.tachiyomi.ui.sourcelab.newsource.NewSourceScreen(kindHint = anime, recordSourceId = sourceId),
            ),
        )
    }
}
