package eu.kanade.tachiyomi.ui.library.checkdownloads

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.hippo.unifile.UniFile
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.coil.LocalPageThumbnail
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.jssource.ChapterVideoDownloads
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import exh.source.MERGED_SOURCE_ID
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.update
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: "Check downloads" from the library's selection menu. For each selected entry, compares what is in its
 * download folder with its chapters, and lists what doesn't belong to any chapter (a post the source renamed or
 * removed, an unfinished download): open it to look at it, or delete it. Reindex downloads in Advanced is unchanged.
 */
class CheckDownloadsScreen(private val mangaIds: List<Long>) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CheckDownloadsScreenModel(mangaIds) }
        val state by screenModel.state.collectAsState()
        var confirmDeleteAll by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.action_check_downloads),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            if (state.loading) {
                LoadingScreen(Modifier.padding(padding))
                return@Scaffold
            }
            if (state.groups.isEmpty()) {
                EmptyScreen(
                    message = stringResource(KMR.strings.check_downloads_not_checked),
                    modifier = Modifier.padding(padding),
                )
                return@Scaffold
            }
            val extraCount = state.groups.sumOf { it.extras.size }
            LazyColumn(contentPadding = padding) {
                if (extraCount > 0) {
                    item(key = "delete-all") {
                        OutlinedButton(
                            onClick = { confirmDeleteAll = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                            Text(
                                text = stringResource(KMR.strings.check_downloads_delete_all) + " ($extraCount)",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                state.groups.forEach { group ->
                    item(key = "manga-${group.manga.id}") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(group.manga.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when {
                                    !group.folderFound -> stringResource(KMR.strings.check_downloads_no_folder)
                                    else -> stringResource(
                                        KMR.strings.check_downloads_summary,
                                        group.chapterCount,
                                        group.matched,
                                        group.extras.size,
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (group.folderFound && group.extras.isEmpty()) {
                                Text(
                                    text = stringResource(KMR.strings.check_downloads_all_match),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    items(group.extras, key = { "extra-${group.manga.id}-${it.name}" }) { extra ->
                        ExtraRow(
                            extra = extra,
                            onOpen = { navigator.push(CheckDownloadsViewerScreen(extra.uri.toString(), extra.name)) },
                            onDelete = { screenModel.delete(listOf(extra)) },
                        )
                    }
                    item(key = "divider-${group.manga.id}") { HorizontalDivider() }
                }
                if (state.skipped > 0) {
                    item(key = "skipped") {
                        Text(
                            text = stringResource(KMR.strings.check_downloads_not_checked) + " (${state.skipped})",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }

        if (confirmDeleteAll) {
            val extras = state.groups.flatMap { it.extras }
            AlertDialog(
                onDismissRequest = { confirmDeleteAll = false },
                text = { Text(stringResource(KMR.strings.check_downloads_delete_confirm, extras.size)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDeleteAll = false
                            screenModel.delete(extras)
                        },
                    ) { Text(stringResource(MR.strings.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun ExtraRow(extra: CheckDownloadsScreenModel.Extra, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(extra.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = when {
                    extra.unfinished -> stringResource(KMR.strings.check_downloads_unfinished)
                    extra.archive -> stringResource(KMR.strings.check_downloads_archive)
                    else -> stringResource(KMR.strings.check_downloads_pictures, extra.pictures)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpen) { Text(stringResource(KMR.strings.check_downloads_open)) }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.action_delete))
        }
    }
}

class CheckDownloadsScreenModel(
    private val mangaIds: List<Long>,
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
) : StateScreenModel<CheckDownloadsScreenModel.State>(State()) {

    @Immutable
    data class Extra(
        val mangaId: Long,
        val name: String,
        val uri: Uri,
        val archive: Boolean,
        val unfinished: Boolean,
        val pictures: Int,
    )

    @Immutable
    data class Group(
        val manga: Manga,
        val folderFound: Boolean,
        val chapterCount: Int,
        val matched: Int,
        val extras: ImmutableList<Extra>,
    )

    @Immutable
    data class State(
        val loading: Boolean = true,
        val groups: List<Group> = emptyList(),
        val skipped: Int = 0,
    )

    init {
        scan()
    }

    private fun scan() {
        screenModelScope.launchIO {
            var skipped = 0
            val groups = mangaIds.mapNotNull { id ->
                val manga = getManga.await(id) ?: return@mapNotNull null
                if (manga.source == MERGED_SOURCE_ID || manga.isLocal()) {
                    skipped++
                    return@mapNotNull null
                }
                runCatching { check(manga) }.getOrNull()
            }
            mutableState.update { State(loading = false, groups = groups, skipped = skipped) }
        }
    }

    private suspend fun check(manga: Manga): Group {
        val source = sourceManager.getOrStub(manga.source)
        val chapters = getChaptersByMangaId.await(manga.id)
        val dir = downloadProvider.findMangaDir(manga.ogTitle, source)
            ?: return Group(manga, false, chapters.size, 0, emptyList<Extra>().toImmutableList())
        // Every name a chapter's download may have (folder, CBZ, older naming)
        val valid = chapters
            .flatMap { downloadProvider.getValidChapterDirNames(it.name, it.scanlator, it.url) }
            .toSet()
        var matched = 0
        val extras = mutableListOf<Extra>()
        dir.listFiles().orEmpty().forEach { file ->
            val name = file.name ?: return@forEach
            val archive = file.isFile && (name.endsWith(".cbz", true) || name.endsWith(".zip", true))
            // Only chapter downloads: folders and archives (not the cover or other files the folder keeps)
            if (!file.isDirectory && !archive) return@forEach
            val unfinished = name.endsWith(Downloader.TMP_DIR_SUFFIX)
            if (!unfinished && name in valid) {
                matched++
                return@forEach
            }
            val pictures = if (file.isDirectory) {
                file.listFiles().orEmpty().count { it.isFile && !ChapterVideoDownloads.isVideoFile(it.name) }
            } else {
                0
            }
            extras += Extra(manga.id, name, file.uri, archive, unfinished, pictures)
        }
        extras.sortWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
        return Group(manga, true, chapters.size, matched, extras.toImmutableList())
    }

    fun delete(extras: List<Extra>) {
        if (extras.isEmpty()) return
        screenModelScope.launchIO {
            val context = Injekt.get<Application>()
            extras.forEach { extra -> runCatching { UniFile.fromUri(context, extra.uri)?.delete() } }
            val gone = extras.map { it.mangaId to it.name }.toSet()
            mutableState.update { state ->
                state.copy(
                    groups = state.groups.map { group ->
                        group.copy(
                            extras = group.extras
                                .filterNot { (it.mangaId to it.name) in gone }
                                .toImmutableList(),
                        )
                    },
                )
            }
            // The library's download counts come from the download cache: look at the folders again
            downloadCache.invalidateCache()
        }
    }
}

/** KMK: The pictures of an extra download (a folder or a CBZ), one under the other; its videos open in a player */
class CheckDownloadsViewerScreen(private val uri: String, private val title: String) : Screen() {

    private sealed interface Item {
        data class Picture(val model: LocalPageThumbnail) : Item
        data class Video(val name: String, val uri: Uri) : Item
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        var items by remember { mutableStateOf<List<Item>?>(null) }
        androidx.compose.runtime.LaunchedEffect(uri) {
            items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { load(context, Uri.parse(uri)) }.getOrDefault(emptyList())
            }
        }
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(title = title, navigateUp = navigator::pop, scrollBehavior = scrollBehavior)
            },
        ) { padding ->
            val list = items
            when {
                list == null -> LoadingScreen(Modifier.padding(padding))
                list.isEmpty() -> EmptyScreen(
                    message = stringResource(KMR.strings.check_downloads_no_folder),
                    modifier = Modifier.padding(padding),
                )
                else -> LazyColumn(
                    contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(list) { item ->
                        when (item) {
                            is Item.Picture -> AsyncImage(
                                model = item.model,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            is Item.Video -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW)
                                                    .setDataAndType(item.uri, "video/*")
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                            )
                                        }
                                    }
                                    .padding(16.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text(item.name)
                                    Text(
                                        text = stringResource(KMR.strings.check_downloads_video),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun load(context: android.content.Context, uri: Uri): List<Item> {
        val file = UniFile.fromUri(context, uri) ?: return emptyList()
        return if (file.isDirectory) {
            val files = file.listFiles().orEmpty()
                .filter { it.isFile }
                .sortedWith { a, b -> a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty()) }
            var index = 0
            files.mapNotNull { f ->
                val name = f.name.orEmpty()
                when {
                    ChapterVideoDownloads.isVideoFile(name) -> Item.Video(name, f.uri)
                    ImageUtil.isImage(name) { f.openInputStream() } ->
                        Item.Picture(LocalPageThumbnail(chapterId = -1, index = index++, fileUri = f.uri))
                    else -> null
                }
            }
        } else {
            val names = file.archiveReader(context).use { reader ->
                reader.useEntries { entries ->
                    entries
                        .filter { it.isFile && !ChapterVideoDownloads.isVideoFile(it.name) }
                        .filter { ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        .map { it.name }
                        .toList()
                }
            }
            names
                .sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
                .mapIndexed { i, name ->
                    Item.Picture(LocalPageThumbnail(chapterId = -1, index = i, archiveUri = file.uri, entryName = name))
                }
        }
    }
}
