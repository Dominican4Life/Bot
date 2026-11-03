package eu.kanade.presentation.more.settings.screen.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.CategoryChoice
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.DeviceUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RestoreBackupScreen(
    private val uri: String,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { RestoreBackupScreenModel(context, uri) }
        val state by model.state.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_restore_backup),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                // KMK (r114): "Restore N titles" when titles are picked
                actionLabel = if (state.titleCount > 0) {
                    "Restore ${formatCount(state.titleCount)} titles"
                } else {
                    stringResource(MR.strings.action_restore)
                },
                actionEnabled = state.canRestore && state.options.canRestore() && state.categoriesLoaded &&
                    state.contentsLoaded,
                onClickAction = {
                    model.startRestore()
                    navigator.pop()
                },
            ) {
                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                    item {
                        WarningBanner(MR.strings.restore_miui_warning)
                    }
                }

                // KMK (r114): "what's inside" first: file, size, where it comes from, and one row per part with a
                // count read from the backup; ticks map to the same RestoreOptions as before
                item {
                    BackupCard(
                        modifier = Modifier.padding(
                            horizontal = MaterialTheme.padding.medium,
                            vertical = MaterialTheme.padding.small,
                        ),
                    ) {
                        val contents = state.contents
                        when {
                            !state.contentsLoaded -> ReadingRow()
                            contents != null -> ContentsRows(contents, state.options, model::toggle)
                            state.canRestore -> RestoreOptions.options.forEach { option ->
                                // Couldn't count what's inside: the plain options, as before
                                LabeledCheckbox(
                                    label = stringResource(option.label),
                                    checked = option.getter(state.options),
                                    onCheckedChange = {
                                        model.toggle(option.setter, it)
                                    },
                                )
                            }
                            else -> BackupMuted("This file can't be restored (see below).")
                        }
                    }
                }

                // KMK -->
                if (state.canRestore && state.categoryEntries.isNotEmpty()) {
                    item {
                        CategoryChoiceCard(
                            entries = state.categoryEntries,
                            picked = state.pickedCategories,
                            enabled = state.options.libraryEntries,
                            onToggle = model::toggleCategory,
                            onOnly = model::onlyCategory,
                        )
                    }
                }
                // KMK <--

                if (state.error != null) {
                    errorMessageItem(state.error)
                }
            }
        }
    }

    @Composable
    private fun ReadingRow() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            BackupMuted("Reading the backup…")
        }
    }

    /** KMK (r114): the file's header and a row per part of the backup */
    @Composable
    private fun ContentsRows(
        contents: BackupContents,
        options: RestoreOptions,
        toggle: ((RestoreOptions, Boolean) -> RestoreOptions, Boolean) -> Unit,
    ) {
        val context = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupOriginBadge(contents.origin)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contents.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                BackupMuted(
                    listOfNotNull(
                        contents.size.takeIf { it >= 0 }?.let { Formatter.formatShortFileSize(context, it) },
                        contents.madeOn?.let { "made $it" },
                        "read in %.1f s".format(contents.readMs / 1000f),
                    ).joinToString(" · "),
                )
            }
        }
        BackupMuted("Restore")

        val library = options.libraryEntries
        PartRow(
            title = "Library",
            detail = listOfNotNull(
                if (contents.manga > 0) "${formatCount(contents.manga)} manga" else null,
                if (contents.mangaHere > 0) "${formatCount(contents.mangaHere)} already here" else null,
                if (contents.anime > 0) "${formatCount(contents.anime)} anime" else null,
                if (contents.animeHere > 0) "${formatCount(contents.animeHere)} already here" else null,
                // KMK (r115): anime watched outside the library ("All read & watched")
                if (contents.animeWatched > 0) "${formatCount(contents.animeWatched)} watched only" else null,
            ).joinToString(" · "),
            present = contents.manga + contents.anime > 0,
            checked = library,
            onCheckedChange = { toggle({ o, on -> o.copy(libraryEntries = on) }, it) },
        )
        // Chapters, history and tracking come back with each title (no switch of their own)
        DetailRow(
            title = "Chapters & episodes read",
            detail = listOfNotNull(
                if (contents.manga > 0) "${formatCount(contents.chaptersRead)} chapters" else null,
                if (contents.anime > 0) "${formatCount(contents.episodesSeen)} episodes" else null,
            ).joinToString(" · "),
            active = library,
            present = contents.chaptersRead + contents.episodesSeen > 0,
        )
        DetailRow(
            title = "History",
            detail = formatCount(contents.history),
            active = library,
            present = contents.history > 0,
        )
        DetailRow(
            title = "Tracking",
            detail = contents.tracking.joinToString(" · ") { (name, count) -> "$name ${formatCount(count)}" },
            active = library,
            present = contents.tracking.isNotEmpty(),
        )
        PartRow(
            title = "Categories",
            detail = formatCount(contents.categories) +
                if (contents.newCategories > 0) " (${formatCount(contents.newCategories)} new)" else "",
            present = contents.categories > 0,
            checked = options.categories,
            onCheckedChange = { toggle({ o, on -> o.copy(categories = on) }, it) },
        )
        PartRow(
            title = "App settings",
            detail = if (contents.fromThisApp) {
                formatCount(contents.appSettings)
            } else {
                "from another app · may change your look"
            },
            present = contents.appSettings > 0,
            checked = options.appSettings,
            onCheckedChange = { toggle({ o, on -> o.copy(appSettings = on) }, it) },
        )
        PartRow(
            title = "Source settings",
            detail = formatCount(contents.sourceSettings),
            present = contents.sourceSettings > 0,
            checked = options.sourceSettings,
            onCheckedChange = { toggle({ o, on -> o.copy(sourceSettings = on) }, it) },
        )
        PartRow(
            title = "Extension repos",
            detail = formatCount(contents.extensionRepos),
            present = contents.extensionRepos > 0,
            checked = options.extensionStores,
            onCheckedChange = { toggle({ o, on -> o.copy(extensionStores = on) }, it) },
        )
        PartRow(
            title = "Saved searches & feeds",
            detail = formatCount(contents.searchesFeeds),
            present = contents.searchesFeeds > 0,
            checked = options.savedSearchesFeeds,
            onCheckedChange = { toggle({ o, on -> o.copy(savedSearchesFeeds = on) }, it) },
        )
        PartRow(
            title = "JS sources",
            detail = formatCount(contents.jsSources),
            present = contents.jsSources > 0,
            checked = options.jsSources,
            onCheckedChange = { toggle({ o, on -> o.copy(jsSources = on) }, it) },
        )
        // KMK (r115): the file parts (proto 900–902); older backups show "not in this backup"
        PartRow(
            title = "Source Lab files",
            detail = "${formatCount(contents.sourceLabFiles)} files · " +
                "${formatCount(contents.sourceLabSources)} sources",
            present = contents.sourceLabFiles > 0,
            checked = options.sourceLabFiles,
            onCheckedChange = { toggle({ o, on -> o.copy(sourceLabFiles = on) }, it) },
        )
        PartRow(
            title = "JS source storage",
            detail = "${formatCount(contents.jsStorageFiles)} files · may hold logins",
            present = contents.jsStorageFiles > 0,
            checked = options.jsStorage,
            onCheckedChange = { toggle({ o, on -> o.copy(jsStorage = on) }, it) },
        )
        PartRow(
            title = "Custom covers",
            detail = listOfNotNull(
                if (contents.mangaCovers > 0) "${formatCount(contents.mangaCovers)} manga" else null,
                if (contents.animeCovers > 0) "${formatCount(contents.animeCovers)} anime" else null,
            ).joinToString(" · ") + if (library) "" else " · needs Library",
            present = contents.mangaCovers + contents.animeCovers > 0,
            checked = options.customCovers && library,
            onCheckedChange = { toggle({ o, on -> o.copy(customCovers = on) }, it) },
        )
    }

    @Composable
    private fun PartRow(
        title: String,
        detail: String,
        present: Boolean,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (present) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = present && checked,
                onCheckedChange = onCheckedChange,
                enabled = present,
            )
            Text(text = title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            BackupMuted(
                text = if (present) detail else "not in this backup",
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }

    @Composable
    private fun DetailRow(title: String, detail: String, active: Boolean, present: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                .alpha(if (active && present) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            BackupMuted(if (present) detail else "not in this backup")
        }
    }

    private fun LazyListScope.errorMessageItem(
        error: Any?,
    ) {
        item {
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    val msg = buildAnnotatedString {
                        when (error) {
                            is MissingRestoreComponents -> {
                                appendLine(stringResource(MR.strings.backup_restore_content_full))
                                if (error.sources.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(stringResource(MR.strings.backup_restore_missing_sources))
                                    }
                                    error.sources.joinTo(
                                        this,
                                        separator = "\n- ",
                                        prefix = "- ",
                                    )
                                }
                                if (error.trackers.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(stringResource(MR.strings.backup_restore_missing_trackers))
                                    }
                                    error.trackers.joinTo(
                                        this,
                                        separator = "\n- ",
                                        prefix = "- ",
                                    )
                                }
                            }

                            is InvalidRestore -> {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    appendLine(stringResource(MR.strings.invalid_backup_file))
                                }
                                appendLine(error.uri.toString())

                                appendLine()

                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    appendLine(stringResource(MR.strings.invalid_backup_file_error))
                                }
                                appendLine(error.message)
                            }

                            else -> {
                                appendLine(error.toString())
                            }
                        }
                    }

                    SelectionContainer {
                        Text(text = msg)
                    }
                }
            }
        }
    }
}

private class RestoreBackupScreenModel(
    private val context: Context,
    private val uri: String,
) : StateScreenModel<RestoreBackupScreenModel.State>(State()) {

    // KMK (r114): category names of each title in the backup, to count what "Restore N titles" restores
    private var mangaCategoryNames: List<List<String>> = emptyList()
    private var animeCategoryNames: List<List<String>> = emptyList()

    init {
        // KMK (r114): checked on IO now (it reads the whole file), then the backup is read once more for what's
        // inside and its categories
        screenModelScope.launchIO {
            validate(uri.toUri())
            val started = System.currentTimeMillis()
            val backup = runCatching { BackupDecoder(context).decode(uri.toUri()) }.getOrNull()
            val readMs = System.currentTimeMillis() - started
            // KMK: the categories inside the backup, to restore only some of them
            val entries = backup?.let { runCatching { backupCategoryChoices(it) }.getOrNull() }.orEmpty()
            val contents = backup?.let {
                runCatching { readBackupContents(context, uri.toUri(), it, readMs) }
                    .onFailure { e -> logcat(LogPriority.WARN, e) { "Couldn't count what's in the backup" } }
                    .getOrNull()
            }
            if (backup != null) {
                val orderToName = backup.backupCategories.associate { it.order to it.name }
                mangaCategoryNames = backup.backupManga.map { manga -> manga.categories.mapNotNull { orderToName[it] } }
                animeCategoryNames = backup.backupAnime.map { it.categories }
            }
            mutableState.update {
                val options = if (contents == null) {
                    it.options
                } else {
                    // Parts the file doesn't have start unticked; another app's settings too
                    it.options.copy(
                        libraryEntries = it.options.libraryEntries && contents.manga + contents.anime > 0,
                        categories = it.options.categories && contents.categories > 0,
                        appSettings = it.options.appSettings && contents.appSettings > 0 && contents.fromThisApp,
                        extensionStores = it.options.extensionStores && contents.extensionRepos > 0,
                        sourceSettings = it.options.sourceSettings && contents.sourceSettings > 0,
                        savedSearchesFeeds = it.options.savedSearchesFeeds && contents.searchesFeeds > 0,
                        jsSources = it.options.jsSources && contents.jsSources > 0,
                        // KMK (r115)
                        sourceLabFiles = it.options.sourceLabFiles && contents.sourceLabFiles > 0,
                        jsStorage = it.options.jsStorage && contents.jsStorageFiles > 0,
                        customCovers = it.options.customCovers && contents.mangaCovers + contents.animeCovers > 0,
                    )
                }
                it.copy(
                    categoryEntries = entries,
                    pickedCategories = entries.map { entry -> entry.key }.toSet(),
                    categoriesLoaded = true,
                    contents = contents,
                    contentsLoaded = true,
                    options = options,
                ).withCount()
            }
        }
    }

    /** KMK (r114): how many titles a restore with these choices brings back */
    private fun State.withCount(): State {
        val keys = pickedCategories.takeIf { it.size < categoryEntries.size }
        val count = if (!options.libraryEntries) {
            0
        } else {
            mangaCategoryNames.count { CategoryChoice.keepManga(keys, it) } +
                animeCategoryNames.count { CategoryChoice.keepAnime(keys, it) }
        }
        return copy(titleCount = count)
    }

    // KMK -->
    fun toggleCategory(key: String, picked: Boolean) {
        mutableState.update {
            pickSome(it, if (picked) it.pickedCategories + key else it.pickedCategories - key).withCount()
        }
    }

    /** Just this category's titles: every other row unticked */
    fun onlyCategory(key: String) {
        mutableState.update { pickSome(it, setOf(key)).withCount() }
    }

    /**
     * The first time only some categories are picked, the parts that aren't per title (settings, repos, feeds, JS
     * sources) are unticked too, so restoring one category doesn't bring everything else back. They can be ticked
     * again.
     */
    private fun pickSome(state: State, picked: Set<String>): State {
        val some = picked.size < state.categoryEntries.size
        val options = if (some && !state.trimmedOthers) {
            state.options.copy(
                appSettings = false,
                extensionStores = false,
                sourceSettings = false,
                savedSearchesFeeds = false,
                jsSources = false,
                // KMK (r115): not per title either (custom covers are, they stay)
                sourceLabFiles = false,
                jsStorage = false,
            )
        } else {
            state.options
        }
        return state.copy(pickedCategories = picked, options = options, trimmedOthers = state.trimmedOthers || some)
    }
    // KMK <--

    fun toggle(setter: (RestoreOptions, Boolean) -> RestoreOptions, enabled: Boolean) {
        mutableState.update {
            it.copy(
                options = setter(it.options, enabled),
            ).withCount()
        }
    }

    fun startRestore() {
        // KMK: every category ticked means everything, as before
        val state = state.value
        val keys = state.pickedCategories.takeIf { it.size < state.categoryEntries.size }
        BackupRestoreJob.start(
            context = context,
            uri = uri.toUri(),
            options = state.options.copy(categoryKeys = keys),
        )
    }

    private fun validate(uri: Uri) {
        val results = try {
            BackupFileValidator(context).validate(uri)
        } catch (e: Exception) {
            setError(
                error = InvalidRestore(uri, e.message.toString()),
                canRestore = false,
            )
            return
        }

        if (results.missingSources.isNotEmpty() || results.missingTrackers.isNotEmpty()) {
            setError(
                error = MissingRestoreComponents(uri, results.missingSources, results.missingTrackers),
                canRestore = true,
            )
            return
        }

        setError(error = null, canRestore = true)
    }

    private fun setError(error: Any?, canRestore: Boolean) {
        mutableState.update {
            it.copy(
                error = error,
                canRestore = canRestore,
            )
        }
    }

    @Immutable
    data class State(
        val error: Any? = null,
        val canRestore: Boolean = false,
        val options: RestoreOptions = RestoreOptions(),
        // KMK -->
        val categoryEntries: List<CategoryChoiceEntry> = emptyList(),
        val pickedCategories: Set<String> = emptySet(),
        /** The backup's categories have been read (Restore waits for them: before, it restored everything) */
        val categoriesLoaded: Boolean = false,
        val trimmedOthers: Boolean = false,
        // KMK <--
        // KMK (r114): what's inside the file (null when it couldn't be counted) and how many titles Restore brings
        val contents: BackupContents? = null,
        val contentsLoaded: Boolean = false,
        val titleCount: Int = 0,
    )
}

private data class MissingRestoreComponents(
    val uri: Uri,
    val sources: List<String>,
    val trackers: List<String>,
)

private data class InvalidRestore(
    val uri: Uri? = null,
    val message: String,
)

/** KMK (r114): what a backup file holds, counted before anything is restored */
@Immutable
private data class BackupContents(
    val fileName: String,
    /** Bytes; -1 when the file doesn't say */
    val size: Long,
    val madeOn: String?,
    val readMs: Long,
    val origin: BackupOrigin,
    /** Made by this app: its name says so, or it carries parts only this app writes (JS sources, player buttons) */
    val fromThisApp: Boolean,
    val manga: Int,
    val mangaHere: Int,
    val anime: Int,
    val animeHere: Int,
    val chaptersRead: Int,
    val episodesSeen: Int,
    val history: Int,
    /** Tracker name to entries, most first */
    val tracking: List<Pair<String, Int>>,
    val categories: Int,
    val newCategories: Int,
    val appSettings: Int,
    val sourceSettings: Int,
    val extensionRepos: Int,
    val searchesFeeds: Int,
    val jsSources: Int,
    // KMK (r115) -->
    /** Anime outside the library that come with their watch history (part of [anime]) */
    val animeWatched: Int = 0,
    val sourceLabFiles: Int = 0,
    /** JS source keys the Source Lab files belong to */
    val sourceLabSources: Int = 0,
    val jsStorageFiles: Int = 0,
    val mangaCovers: Int = 0,
    val animeCovers: Int = 0,
    // KMK (r115) <--
)

private val MADE_ON_REGEX = Regex("""(\d{4}-\d{2}-\d{2})""")

/** KMK (r114): counts only; reads the library once (favorites, categories) to say what's already here. On IO. */
private suspend fun readBackupContents(context: Context, uri: Uri, backup: Backup, readMs: Long): BackupContents {
    var name: String? = null
    var size = -1L
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    }
    val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()

    val nameOrigin = backupOriginOf(fileName)
    val fromThisApp = nameOrigin.kind == BackupOriginKind.ThisApp ||
        backup.backupJsSources.isNotEmpty() || backup.backupCustomButtons.isNotEmpty() ||
        // KMK (r115): parts only this app writes
        backup.backupSourceLabFiles.isNotEmpty() || backup.backupJsStorageFiles.isNotEmpty() ||
        backup.backupCustomCovers.isNotEmpty()
    val origin = if (fromThisApp) BackupOrigin("THIS APP", BackupOriginKind.ThisApp) else nameOrigin

    val favorites = runCatching { Injekt.get<GetFavorites>().await() }.getOrDefault(emptyList())
        .map { it.source to it.url }.toSet()
    val animeRepository = Injekt.get<AnimeRepository>()
    val animeFavorites = runCatching { animeRepository.subscribeFavorites().first() }.getOrDefault(emptyList())
        .map { it.source to it.url }.toSet()

    val trackerNames = Injekt.get<TrackerManager>().let { manager ->
        (manager.trackers + manager.animeOnlyTrackers).associate { it.id to it.name }
    }
    val trackerIds = backup.backupManga.flatMap { manga -> manga.tracking.map { it.syncId.toLong() } } +
        backup.backupAnime.flatMap { anime -> anime.tracking.map { it.trackerId } }
    val tracking = trackerIds
        .groupingBy { trackerNames[it] ?: "Other" }
        .eachCount()
        .toList()
        .sortedByDescending { it.second }

    val mangaCategoryNames = backup.backupCategories.map { it.name }.distinct()
    val animeCategoryNames =
        (backup.backupAnimeCategories.map { it.name } + backup.backupAnime.flatMap { it.categories }).distinct()
    val existingManga = runCatching {
        Injekt.get<GetCategories>().await().filterNot { it.isSystemCategory }.map { it.name.lowercase() }.toSet()
    }.getOrDefault(emptySet())
    val existingAnime = runCatching { animeRepository.getCategories().map { it.name.lowercase() }.toSet() }
        .getOrDefault(emptySet())
    val newCategories = mangaCategoryNames.count { it.lowercase() !in existingManga } +
        animeCategoryNames.count { it.lowercase() !in existingAnime }

    return BackupContents(
        fileName = fileName,
        size = size,
        madeOn = MADE_ON_REGEX.find(fileName)?.value,
        readMs = readMs,
        origin = origin,
        fromThisApp = fromThisApp,
        manga = backup.backupManga.size,
        mangaHere = backup.backupManga.count { (it.source to it.url) in favorites },
        anime = backup.backupAnime.size,
        animeHere = backup.backupAnime.count { (it.source to it.url) in animeFavorites },
        chaptersRead = backup.backupManga.sumOf { manga -> manga.chapters.count { it.read } },
        episodesSeen = backup.backupAnime.sumOf { anime -> anime.episodes.count { it.seen } },
        history = backup.backupManga.sumOf { it.history.size } + backup.backupAnime.sumOf { it.history.size },
        tracking = tracking,
        categories = mangaCategoryNames.size + animeCategoryNames.size,
        newCategories = newCategories,
        appSettings = backup.backupPreferences.size + backup.backupCustomButtons.size,
        sourceSettings = backup.backupSourcePreferences.size + backup.backupAnimeSourcePreferences.size,
        extensionRepos = backup.backupExtensionStores.size + backup.backupAnimeExtensionStores.size,
        searchesFeeds = backup.backupSavedSearches.size + backup.backupFeeds.size,
        jsSources = backup.backupJsSources.size,
        // KMK (r115)
        animeWatched = backup.backupAnime.count { !it.favorite },
        sourceLabFiles = backup.backupSourceLabFiles.size,
        sourceLabSources = backup.backupSourceLabFiles
            .mapNotNull { file -> file.path.split('/').takeIf { it.size > 2 && it[0] == "js_sources" }?.get(1) }
            .distinct()
            .size,
        jsStorageFiles = backup.backupJsStorageFiles.size,
        mangaCovers = backup.backupCustomCovers.count { !it.anime },
        animeCovers = backup.backupCustomCovers.count { it.anime },
    )
}
