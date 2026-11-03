package eu.kanade.presentation.more.settings.screen.data

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.jssource.JsSourceManager
import kotlinx.coroutines.flow.first
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.NumberFormat

// KMK (r114): the cards of the Backup & storage page (Back up now, Automatic backups, Restore) and the pieces the
// restore screen shares with them (where a backup file comes from, its badge). Nothing here writes or reads a backup's
// contents: creating and restoring still go through CreateBackupScreen / RestoreBackupScreen and their jobs.

/** KMK (r114): where a .tachibk file comes from, guessed from its name */
enum class BackupOriginKind { ThisApp, AnimeApp, Mihon, Other }

@Immutable
data class BackupOrigin(val label: String, val kind: BackupOriginKind)

/**
 * KMK (r114): THIS APP when the name starts with this app's package or name, else by a known app's name in it
 * (anime apps first, then Mihon), else OTHER.
 */
fun backupOriginOf(fileName: String?): BackupOrigin {
    val name = fileName.orEmpty().lowercase()
    val own = BuildConfig.APPLICATION_ID.lowercase()
    return when {
        name.startsWith(own) || name.startsWith("app.komikku") || name.startsWith("komikku") ->
            BackupOrigin("THIS APP", BackupOriginKind.ThisApp)
        "anizen" in name -> BackupOrigin("ANIZEN", BackupOriginKind.AnimeApp)
        "anikku" in name -> BackupOrigin("ANIKKU", BackupOriginKind.AnimeApp)
        "aniyomi" in name -> BackupOrigin("ANIYOMI", BackupOriginKind.AnimeApp)
        "mihon" in name -> BackupOrigin("MIHON", BackupOriginKind.Mihon)
        else -> BackupOrigin("OTHER", BackupOriginKind.Other)
    }
}

/** KMK (r114): a backup file in the automatic-backup folder */
@Immutable
data class AutoBackupFile(val uri: Uri, val name: String, val size: Long, val modified: Long)

/** KMK (r114): the newest .tachibk files in the automatic-backup folder (any app's), newest first; on IO */
suspend fun autoBackupFiles(limit: Int): List<AutoBackupFile> = withIOContext {
    runCatching {
        Injekt.get<StorageManager>().getAutomaticBackupsDirectory()
            ?.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.orEmpty().endsWith(".tachibk", ignoreCase = true) }
            .map { AutoBackupFile(it.uri, it.name.orEmpty(), it.length(), it.lastModified()) }
            .sortedByDescending { it.modified }
            .take(limit)
    }.getOrDefault(emptyList())
}

/** KMK (r114): where automatic backups go, as shown to people; null when no storage folder is set */
suspend fun autoBackupFolderPath(): String? = withIOContext {
    runCatching { Injekt.get<StorageManager>().getAutomaticBackupsDirectory()?.displayablePath }.getOrNull()
}

/** KMK (r114): what a backup made now would hold */
@Immutable
data class LibraryBackupStats(val manga: Int, val anime: Int, val categories: Int, val jsSources: Int)

suspend fun libraryBackupStats(): LibraryBackupStats = withIOContext {
    val manga = runCatching { Injekt.get<GetFavorites>().await().size }.getOrDefault(0)
    val anime = runCatching { Injekt.get<AnimeRepository>().subscribeFavorites().first().size }.getOrDefault(0)
    val mangaCategories = runCatching {
        Injekt.get<GetCategories>().await().count { !it.isSystemCategory }
    }.getOrDefault(0)
    val animeCategories = runCatching { Injekt.get<AnimeRepository>().getCategories().size }.getOrDefault(0)
    val js = runCatching { Injekt.get<JsSourceManager>().savedFlow.value.size }.getOrDefault(0)
    LibraryBackupStats(manga, anime, mangaCategories + animeCategories, js)
}

fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** KMK (r114): "today", "yesterday", "Oct 19"… */
fun backupDayText(epochMillis: Long): String =
    if (epochMillis <= 0L) {
        "unknown date"
    } else {
        DateUtils.getRelativeTimeSpanString(epochMillis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
            .toString()
    }

/** KMK (r114): the rounded surface every card on these pages uses */
@Composable
fun BackupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun BackupCardTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun BackupMuted(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun BackupOriginBadge(origin: BackupOrigin, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (origin.kind) {
        BackupOriginKind.ThisApp -> colors.primaryContainer to colors.onPrimaryContainer
        BackupOriginKind.AnimeApp -> colors.tertiaryContainer to colors.onTertiaryContainer
        BackupOriginKind.Mihon -> colors.secondaryContainer to colors.onSecondaryContainer
        BackupOriginKind.Other -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Text(
        text = origin.label,
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        color = fg,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

/**
 * KMK (r114): "Back up now" and "Automatic backups" side by side when there's room (600 dp), stacked on phones.
 */
@Composable
fun BackupNowAndAutoCards(
    interval: Int,
    intervalEntries: Map<Int, String>,
    lastAutoBackup: Long,
    autoInfo: String,
    onCreate: () -> Unit,
    onIntervalChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val intervalText = intervalEntries[interval].orEmpty()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BackupNowCard(interval, intervalText, lastAutoBackup, onCreate, Modifier.weight(1f))
                AutomaticBackupsCard(interval, intervalEntries, autoInfo, onIntervalChange, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BackupNowCard(interval, intervalText, lastAutoBackup, onCreate)
                AutomaticBackupsCard(interval, intervalEntries, autoInfo, onIntervalChange)
            }
        }
    }
}

@Composable
private fun BackupNowCard(
    interval: Int,
    intervalText: String,
    lastAutoBackup: Long,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val stats by produceState<LibraryBackupStats?>(null) { value = libraryBackupStats() }
    val newest by produceState<AutoBackupFile?>(null, lastAutoBackup) { value = autoBackupFiles(1).firstOrNull() }

    val lastLine = remember(newest, lastAutoBackup, interval, intervalText) {
        val file = newest
        val last = when {
            file != null -> backupDayText(file.modified) + " " +
                DateUtils.formatDateTime(context, file.modified, DateUtils.FORMAT_SHOW_TIME) + " · " +
                Formatter.formatShortFileSize(context, file.size)
            lastAutoBackup > 0L -> backupDayText(lastAutoBackup) + " " +
                DateUtils.formatDateTime(context, lastAutoBackup, DateUtils.FORMAT_SHOW_TIME)
            else -> "never"
        }
        val auto = if (interval > 0) "automatic: ${intervalText.lowercase()}" else "automatic backups off"
        "Last backup: $last · $auto"
    }

    BackupCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                BackupCardTitle("Back up now")
                BackupMuted(lastLine)
            }
            Button(onClick = onCreate) { Text("Create backup") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            val s = stats
            Stat(s?.manga, "manga")
            Stat(s?.anime, "anime")
            Stat(s?.categories, "categories")
            Stat(s?.jsSources, "JS sources")
        }
    }
}

@Composable
private fun Stat(value: Int?, label: String) {
    Column {
        Text(
            text = value?.let(::formatCount) ?: "…",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        BackupMuted(label)
    }
}

@Composable
private fun AutomaticBackupsCard(
    interval: Int,
    intervalEntries: Map<Int, String>,
    info: String,
    onIntervalChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val folder by produceState<String?>(null) { value = autoBackupFolderPath() }
    var menuOpen by remember { mutableStateOf(false) }

    BackupCard(modifier) {
        BackupCardTitle("Automatic backups")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("How often", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Box {
                FilterChip(
                    selected = interval > 0,
                    onClick = { menuOpen = true },
                    label = { Text(intervalEntries[interval].orEmpty()) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    intervalEntries.forEach { (hours, text) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            onClick = {
                                menuOpen = false
                                onIntervalChange(hours)
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Where", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = folder ?: "No storage location set",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()
        BackupMuted(info)
    }
}

/**
 * KMK (r114): "Choose a file…" (the existing picker) and the 3 newest backups in the automatic-backup folder, each
 * with where it comes from, its size and date and a Restore button.
 */
@Composable
fun RestoreCard(
    refreshKey: Any?,
    onChooseFile: () -> Unit,
    onRestoreFile: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val files by produceState<List<AutoBackupFile>?>(null, refreshKey) { value = autoBackupFiles(3) }

    BackupCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                BackupCardTitle("Restore")
                BackupMuted(
                    "Pick a backup from this app, Mihon or any fork, or an anime app. Nothing changes until you " +
                        "press Restore.",
                )
            }
            OutlinedButton(onClick = onChooseFile) { Text("Choose a file…") }
        }
        val list = files
        when {
            list == null -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            list.isEmpty() -> BackupMuted("No backups in the automatic-backup folder yet.")
            else -> list.forEach { file ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            BackupOriginBadge(backupOriginOf(file.name))
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            BackupMuted(
                                Formatter.formatShortFileSize(context, file.size) + " · " +
                                    backupDayText(file.modified),
                            )
                        }
                        OutlinedButton(
                            onClick = { onRestoreFile(file.uri) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Restore", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
