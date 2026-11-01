package eu.kanade.tachiyomi.ui.sourcelab.newsource

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.jssource.lab.Fingerprints
import eu.kanade.tachiyomi.jssource.lab.LabFileKind
import eu.kanade.tachiyomi.jssource.lab.LabFiles
import eu.kanade.tachiyomi.jssource.lab.LabTemplate
import eu.kanade.tachiyomi.jssource.lab.Templates
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext

/**
 * KMK (r96, Source Lab): the Templates card (tablet rail "Templates", Home's Templates card): every template with
 * its kind, version and where it comes from, Import template (a .js / .json file, then its optional
 * .explain.json), Export, and the fingerprints row with Update from file / Export. Self-contained: `remember`
 * state only, so Half A can place it inside its own screen (CONTRACT-LAB §5.4).
 */
@Composable
internal fun TemplatesPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var templates by remember { mutableStateOf<List<LabTemplate>>(emptyList()) }
    var fingerprintsVersion by remember { mutableIntStateOf(0) }
    var engines by remember { mutableIntStateOf(0) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingTemplate by remember { mutableStateOf<Pair<String, String>?>(null) }   // file name to text, waiting for the explain file

    LaunchedEffect(reloadTick) {
        val loaded = withIOContext { runCatching { Templates.list() }.getOrElse { emptyList() } }
        val fp = withIOContext { runCatching { Fingerprints.load() }.getOrNull() }
        templates = loaded
        fingerprintsVersion = fp?.version ?: 0
        engines = fp?.engines?.size ?: 0
    }

    fun share(name: String, kind: LabFileKind) {
        scope.launchIO {
            try {
                val file = LabFiles.store(kind).export(name)
                val uri = file.getUriCompat(context)
                withUIContext { context.startActivity(uri.toShareIntent(context, type = "text/plain")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withUIContext { context.toast("Couldn't export $name: ${e.message}") }
            }
        }
    }

    val explainPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingTemplate
        pendingTemplate = null
        if (pending == null) return@rememberLauncherForActivityResult
        scope.launchIO {
            try {
                val explain = uri?.let { readText(context, it) }
                val version = Templates.import(pending.first, pending.second, explain?.second, pending.first)
                withUIContext {
                    message = "Imported ${pending.first} v$version" + (if (explain != null) " with its explain file" else "")
                    reloadTick++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('W', "lab", "Template import failed: ${e.message}")
                withUIContext { message = "Not imported: ${e.message}" }
            }
        }
    }
    val templatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launchIO {
            val read = runCatching { readText(context, uri) }.getOrNull()
            withUIContext {
                if (read == null) {
                    message = "The file could not be read"
                } else {
                    pendingTemplate = read
                }
            }
        }
    }
    val fingerprintsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launchIO {
            try {
                val (name, text) = readText(context, uri)
                val version = LabFiles.store(LabFileKind.Fingerprints).import(Fingerprints.FILE_NAME, text, name)
                withUIContext {
                    message = "Fingerprints v$version are current"
                    reloadTick++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withUIContext { message = "Not imported: ${e.message}" }
            }
        }
    }

    Column(modifier = modifier) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Templates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${templates.size} files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "A template is a .js or .json source with {{placeholders}} and part markers, plus a .explain.json next to it. " +
                        "Import one with a higher // @version to replace the built-in one; the asset stays as the fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                templates.forEach { template ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(template.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${template.kind} · ${if (template.json) "JSON" else "JS"} · v${template.version} · ${template.source} · ${template.explain.size} parts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { share(Templates.fileNameOf(template), LabFileKind.Templates) }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Export", modifier = Modifier.size(20.dp))
                        }
                    }
                    HorizontalDivider()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedButton(onClick = { templatePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import template")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fingerprints", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("v$fingerprintsVersion · $engines engines", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "The markers detection tests a site against. A file with a higher \"version\" replaces the built-in one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { fingerprintsPicker.launch(arrayOf("application/json", "*/*")) }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Update from file")
                    }
                    OutlinedButton(onClick = { share(Fingerprints.FILE_NAME, LabFileKind.Fingerprints) }) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export")
                    }
                }
            }
        }
    }

    val pending = pendingTemplate
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text("Its explain file?") },
            text = { Text("${pending.first} is ready. Pick its .explain.json too, or import the code alone (the Explain pane then shows no descriptions).") },
            confirmButton = { TextButton(onClick = { explainPicker.launch(arrayOf("application/json", "*/*")) }) { Text("Pick .explain.json") } },
            dismissButton = {
                TextButton(
                    onClick = {
                        val toImport = pending
                        pendingTemplate = null
                        scope.launchIO {
                            try {
                                val version = Templates.import(toImport.first, toImport.second, null, toImport.first)
                                withUIContext {
                                    message = "Imported ${toImport.first} v$version"
                                    reloadTick++
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                withUIContext { message = "Not imported: ${e.message}" }
                            }
                        }
                    },
                ) { Text("Code only") }
            },
        )
    }
    val shown = message
    if (shown != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Templates") },
            text = { Text(shown) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

/** The display name and text of a picked file */
private fun readText(context: Context, uri: Uri): Pair<String, String> {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
    val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        ?: throw IllegalStateException("The file could not be opened")
    return name to text
}
