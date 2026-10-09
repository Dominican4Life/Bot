package eu.kanade.tachiyomi.ui.devicefiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.devicefiles.DeviceFile
import eu.kanade.tachiyomi.devicefiles.DeviceFilesPreferences
import eu.kanade.tachiyomi.devicefiles.FileKind
import eu.kanade.tachiyomi.devicefiles.FileSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.util.collectAsState
import java.io.File

// Thumbnails

/** Video frames, app icons and album art, made once and kept while the app runs */
private object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing = HashSet<String>()

    fun load(context: Context, file: DeviceFile): Bitmap? {
        val key = "${file.path}@${file.modified}"
        cache.get(key)?.let { return it }
        if (synchronized(missing) { key in missing }) return null
        val bitmap = runCatching {
            when (file.kind) {
                FileKind.Video -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ThumbnailUtils.createVideoThumbnail(file.file, Size(384, 384), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Images.Thumbnails.MINI_KIND)
                }
                FileKind.Apk -> {
                    val pm = context.packageManager
                    pm.getPackageArchiveInfo(file.path, 0)?.applicationInfo?.let { info ->
                        info.sourceDir = file.path
                        info.publicSourceDir = file.path
                        info.loadIcon(pm).toBitmap(144, 144)
                    }
                }
                FileKind.Audio -> MediaMetadataRetriever().run {
                    try {
                        setDataSource(file.path)
                        embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    } finally {
                        release()
                    }
                }
                else -> null
            }
        }.getOrNull()
        if (bitmap != null) cache.put(key, bitmap) else synchronized(missing) { missing += key }
        return bitmap
    }
}

private fun FileKind.icon(): ImageVector = when (this) {
    FileKind.Folder -> Icons.Filled.Folder
    FileKind.Image -> Icons.Outlined.Image
    FileKind.Video -> Icons.Outlined.Movie
    FileKind.Audio -> Icons.Outlined.MusicNote
    FileKind.Document -> Icons.Outlined.Description
    FileKind.Archive -> Icons.AutoMirrored.Outlined.MenuBook
    FileKind.Apk -> Icons.Outlined.Android
    FileKind.Other -> Icons.Outlined.InsertDriveFile
}

/** Each kind of file has its own colour, like the Files app's category icons */
fun FileKind.tint(): Color = when (this) {
    FileKind.Folder -> Color(0xFF5B8DEF)
    FileKind.Image -> Color(0xFF3FA66B)
    FileKind.Video -> Color(0xFFE5534B)
    FileKind.Audio -> Color(0xFFB45FD8)
    FileKind.Document -> Color(0xFF4A90C2)
    FileKind.Archive -> Color(0xFFE39A2D)
    FileKind.Apk -> Color(0xFF34A853)
    FileKind.Other -> Color(0xFF8A8F98)
}

@Composable
fun KindIcon(kind: FileKind, modifier: Modifier = Modifier, iconSize: Dp = 24.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(kind.tint().copy(alpha = 0.16f)),
    ) {
        Icon(kind.icon(), contentDescription = null, tint = kind.tint(), modifier = Modifier.size(iconSize))
    }
}

/** A picture of the file: the image itself, a video frame, an app's icon, album art, or its kind's icon */
@Composable
fun FileThumbnail(file: DeviceFile, modifier: Modifier = Modifier, iconSize: Dp = 24.dp) {
    val context = LocalContext.current
    when (file.kind) {
        FileKind.Image -> AsyncImage(
            model = file.file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(FileKind.Image.tint().copy(alpha = 0.12f)),
        )
        FileKind.Video, FileKind.Apk, FileKind.Audio -> {
            val bitmap by produceState<ImageBitmap?>(null, file.path, file.modified) {
                value = withContext(Dispatchers.IO) { ThumbnailCache.load(context, file)?.asImageBitmap() }
            }
            val image = bitmap
            if (image == null) {
                KindIcon(file.kind, modifier, iconSize)
            } else {
                Box(modifier) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = if (file.kind == FileKind.Apk) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (file.kind == FileKind.Apk) 6.dp else 0.dp),
                    )
                    if (file.kind == FileKind.Video) {
                        Icon(
                            Icons.Filled.PlayCircle,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.align(Alignment.Center).size(iconSize),
                        )
                    }
                }
            }
        }
        else -> KindIcon(file.kind, modifier, iconSize)
    }
}

// Text

fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

fun formatDate(millis: Long): String =
    if (millis <= 0) "" else DateUtils.getRelativeTimeSpanString(millis).toString()

fun subtitleOf(context: Context, file: DeviceFile): String = if (file.isDirectory) {
    listOfNotNull(
        file.childCount?.let { if (it == 1) "1 item" else "$it items" },
        formatDate(file.modified).takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
} else {
    listOf(formatSize(context, file.size), formatDate(file.modified)).filter { it.isNotEmpty() }.joinToString(" · ")
}

// Rows and tiles

/** What can be done to a file from its ⋮ menu */
enum class FileAction(val title: String, val icon: ImageVector) {
    OpenWith("Open with", Icons.AutoMirrored.Outlined.OpenInNew),
    Share("Share", Icons.Outlined.Share),
    Favourite("Add to favourites", Icons.Outlined.StarBorder),
    Unfavourite("Remove from favourites", Icons.Filled.Star),
    Rename("Rename", Icons.Outlined.Edit),
    Delete("Delete", Icons.Outlined.Delete),
    Info("File info", Icons.Outlined.Info),
}

fun actionsFor(file: DeviceFile, favourite: Boolean): List<FileAction> = buildList {
    if (!file.isDirectory) {
        add(FileAction.OpenWith)
        add(FileAction.Share)
    }
    add(if (favourite) FileAction.Unfavourite else FileAction.Favourite)
    add(FileAction.Rename)
    add(FileAction.Delete)
    add(FileAction.Info)
}

@Composable
private fun ActionsMenu(file: DeviceFile, favourite: Boolean, onAction: (FileAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actionsFor(file, favourite).forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.title) },
                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                    onClick = {
                        open = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    file: DeviceFile,
    favourite: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: (FileAction) -> Unit,
    subtitle: String? = null,
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box {
            FileThumbnail(file, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (favourite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Favourite",
                        tint = Color(0xFFF2B01E),
                        modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                }
            }
            Text(
                subtitle ?: subtitleOf(context, file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selecting) ActionsMenu(file, favourite, onAction) else Spacer(Modifier.width(48.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTile(
    file: DeviceFile,
    favourite: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    cover: DeviceFile = file,
    subtitle: String? = null,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            )
            .padding(6.dp),
    ) {
        Box {
            FileThumbnail(
                cover,
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
                iconSize = 40.dp,
            )
            if (selected || favourite) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.Star,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFF2B01E),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
            if (cover != file && file.isDirectory) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
        }
        Text(
            file.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            subtitle ?: subtitleOf(context, file),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

// Dialogs

@Composable
fun RenameDialog(file: DeviceFile, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(file.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(name) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank() && name != file.name) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeleteDialog(files: List<DeviceFile>, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val folders = files.count { it.isDirectory }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (files.size == 1) "Delete ${files.first().name}?" else "Delete ${files.size} items?") },
        text = {
            Text(
                buildString {
                    append("This permanently deletes ")
                    append(if (files.size == 1) "it" else "them")
                    append(" from the device")
                    if (folders > 0) append(", with everything inside the ${if (folders == 1) "folder" else "folders"}")
                    append(".")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InfoDialog(file: DeviceFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rows = remember(file) {
        buildList {
            add("Name" to file.name)
            add("Location" to file.parent)
            if (file.isDirectory) {
                add("Contains" to "${file.childCount ?: File(file.path).list()?.size ?: 0} items")
            } else {
                add("Size" to "${formatSize(context, file.size)} (${"%,d".format(file.size)} bytes)")
                add("Type" to (eu.kanade.tachiyomi.devicefiles.DeviceFilesOpener.mimeOf(file.file)))
            }
            add(
                "Modified" to DateUtils.formatDateTime(
                    context,
                    file.modified,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
                ),
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Device files' settings: what shows, how lists are sorted and laid out, and where files open */
@Composable
fun FilesSettingsDialog(onDismiss: () -> Unit) {
    val prefs = DeviceFilesPreferences.instance
    val showHidden by prefs.showHidden.collectAsState()
    val showRecent by prefs.showRecent.collectAsState()
    val grid by prefs.gridView.collectAsState()
    val sortName by prefs.sort.collectAsState()
    val descending by prefs.sortDescending.collectAsState()
    val playInApp by prefs.playVideosInApp.collectAsState()
    val readInApp by prefs.readComicsInApp.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device files settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsLabel("Show")
                SwitchRow("Hidden files", "Files and folders whose name starts with a dot", showHidden) {
                    prefs.showHidden.set(it)
                }
                SwitchRow("Recent files", "The newest files at the top of the main page", showRecent) {
                    prefs.showRecent.set(it)
                }
                SwitchRow("Grid view", "Pictures instead of a list", grid) { prefs.gridView.set(it) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingsLabel("Sort by")
                FileSort.entries.forEach { sort ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = sortName == sort.name, role = Role.RadioButton) {
                                prefs.sort.set(sort.name)
                            }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = sortName == sort.name, onClick = null)
                        Text(sort.title, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                SwitchRow("Reverse order", "Newest, largest or Z first", descending) { prefs.sortDescending.set(it) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingsLabel("Open in this app")
                SwitchRow("Videos", "Play them in the app's player, with the folder as the episode list", playInApp) {
                    prefs.playVideosInApp.set(it)
                }
                SwitchRow("Comics and pictures", "Read .cbz, .zip, .rar, .7z, .epub and pictures in the reader", readInApp) {
                    prefs.readComicsInApp.set(it)
                }
                Text(
                    "Anything else opens in another app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The card asking for access to all files, shown until it's given */
@Composable
fun AccessCard(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Allow access to your files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Device files needs permission to see everything on this device's storage, " +
                    "like a file manager. Turn on \"Allow access to manage all files\" for this app.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            TextButton(onClick = onAllow, modifier = Modifier.align(Alignment.End)) { Text("Allow access") }
        }
    }
}

/** Space for the storage bar */
val StorageBarHeight = 6.dp

@Composable
fun StorageBar(used: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(StorageBarHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(used.coerceIn(0f, 1f))
                .height(StorageBarHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
