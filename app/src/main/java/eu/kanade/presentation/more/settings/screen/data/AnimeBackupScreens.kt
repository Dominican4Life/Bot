package eu.kanade.presentation.more.settings.screen.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.backup.anime.AnikkuBackup
import eu.kanade.tachiyomi.data.backup.anime.AnimeBackupFile
import eu.kanade.tachiyomi.data.backup.anime.AnimeBackupOptions
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/*
 * KMK: The anime library's own backup and restore screens, apart from the app's (manga) ones. They offer the options
 * Anikku's backup has that apply to this app, plus picking which categories to take.
 */

/** A category to tick: its id when creating a backup, its order in the file when restoring one */
@Immutable
private data class CategoryChoice(val key: Long, val name: String)

class CreateAnimeBackupScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { CreateAnimeBackupScreenModel() }
        val state by model.state.collectAsState()

        val chooseFile = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/*"),
        ) {
            if (it != null) {
                model.createBackup(context, it)
                navigator.pop()
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(KMR.strings.anime_backup_create),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_create),
                actionEnabled = state.options.canRun(),
                onClickAction = {
                    try {
                        chooseFile.launch(AnimeBackupFile.getFilename())
                    } catch (e: ActivityNotFoundException) {
                        context.toast(MR.strings.file_picker_error)
                    }
                },
            ) {
                item {
                    AnimeBackupOptionCards(
                        options = state.options,
                        categories = state.categories,
                        selectedCategories = state.selectedCategories,
                        showPrivateSettings = true,
                        onChange = model::setOptions,
                        onToggleCategory = model::toggleCategory,
                    )
                }
            }
        }
    }
}

private class CreateAnimeBackupScreenModel(
    private val repository: AnimeRepository = Injekt.get(),
) : StateScreenModel<AnimeBackupState>(AnimeBackupState()) {

    init {
        screenModelScope.launchIO {
            val categories = repository.getCategories().map { CategoryChoice(it.id, it.name) }
            mutableState.update {
                it.copy(
                    categories = categories,
                    selectedCategories = categories.map(CategoryChoice::key).toSet() + AnimeBackupOptions.UNCATEGORIZED,
                )
            }
        }
    }

    fun setOptions(options: AnimeBackupOptions) = mutableState.update { it.copy(options = options) }

    fun toggleCategory(key: Long) = mutableState.update { it.withToggled(key) }

    fun createBackup(context: Context, uri: Uri) {
        val options = state.value.resolvedOptions()
        // Not tied to this screen, which closes right away
        launchIO {
            val message = try {
                val count = AnimeBackupFile().create(context, uri, options)
                context.stringResource(KMR.strings.anime_backup_created, count)
            } catch (e: Exception) {
                context.stringResource(KMR.strings.anime_backup_failed, e.message.orEmpty())
            }
            withUIContext { context.toast(message) }
        }
    }
}

class RestoreAnimeBackupScreen(
    private val uri: String,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { RestoreAnimeBackupScreenModel(context, uri.toUri()) }
        val state by model.state.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(KMR.strings.anime_backup_restore),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            val backup = state.backup
            when {
                state.error != null -> EmptyScreen(
                    message = stringResource(KMR.strings.anime_backup_restore_failed, state.error.orEmpty()),
                    modifier = Modifier.padding(contentPadding),
                )
                backup == null -> LoadingScreen(Modifier.padding(contentPadding))
                else -> LazyColumnWithAction(
                    contentPadding = contentPadding,
                    actionLabel = stringResource(MR.strings.action_restore),
                    actionEnabled = state.options.canRun(),
                    onClickAction = {
                        model.restore(context)
                        navigator.pop()
                    },
                ) {
                    item {
                        Text(
                            text = stringResource(
                                KMR.strings.anime_backup_contents,
                                backup.backupAnime.size,
                                backup.backupCategories.size,
                                backup.backupExtensionRepo.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(
                                horizontal = MaterialTheme.padding.large,
                                vertical = MaterialTheme.padding.small,
                            ),
                        )
                    }
                    item {
                        AnimeBackupOptionCards(
                            options = state.options,
                            categories = state.categories,
                            selectedCategories = state.selectedCategories,
                            // Whatever sensitive settings the file holds come with its source settings
                            showPrivateSettings = false,
                            onChange = model::setOptions,
                            onToggleCategory = model::toggleCategory,
                        )
                    }
                }
            }
        }
    }
}

private class RestoreAnimeBackupScreenModel(
    context: Context,
    uri: Uri,
) : StateScreenModel<AnimeBackupState>(AnimeBackupState()) {

    init {
        screenModelScope.launchIO {
            try {
                val backup = AnimeBackupFile().decode(context, uri)
                val categories = backup.backupCategories.sortedBy { it.order }.map { CategoryChoice(it.order, it.name) }
                mutableState.update {
                    it.copy(
                        backup = backup,
                        categories = categories,
                        selectedCategories = categories.map(CategoryChoice::key).toSet() +
                            AnimeBackupOptions.UNCATEGORIZED,
                    )
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    fun setOptions(options: AnimeBackupOptions) = mutableState.update { it.copy(options = options) }

    fun toggleCategory(key: Long) = mutableState.update { it.withToggled(key) }

    fun restore(context: Context) {
        val backup = state.value.backup ?: return
        val options = state.value.resolvedOptions()
        // Not tied to this screen, which closes right away
        launchIO {
            val message = try {
                val result = AnimeBackupFile().restore(context, backup, options)
                if (result.errors.isEmpty()) {
                    context.stringResource(KMR.strings.anime_backup_restored, result.restored)
                } else {
                    context.stringResource(
                        KMR.strings.anime_backup_restored_errors,
                        result.restored,
                        result.errors.size,
                    )
                }
            } catch (e: Exception) {
                context.stringResource(KMR.strings.anime_backup_restore_failed, e.message.orEmpty())
            }
            withUIContext { context.toast(message) }
        }
    }
}

@Immutable
private data class AnimeBackupState(
    val options: AnimeBackupOptions = AnimeBackupOptions(),
    val categories: List<CategoryChoice> = emptyList(),
    val selectedCategories: Set<Long> = emptySet(),
    /** Restoring: the file that was picked, once read */
    val backup: AnikkuBackup? = null,
    val error: String? = null,
) {
    fun withToggled(key: Long) = copy(
        selectedCategories = if (key in selectedCategories) selectedCategories - key else selectedCategories + key,
    )

    /** The options with the ticked categories; no category filter at all when every one is ticked */
    fun resolvedOptions(): AnimeBackupOptions {
        val all = categories.map(CategoryChoice::key).toSet() + AnimeBackupOptions.UNCATEGORIZED
        return options.copy(selectedCategories = selectedCategories.takeIf { it != all })
    }
}

/** The option list both screens share, in the order Anikku's backup screen has them */
@Composable
private fun LazyItemScope.AnimeBackupOptionCards(
    options: AnimeBackupOptions,
    categories: List<CategoryChoice>,
    selectedCategories: Set<Long>,
    showPrivateSettings: Boolean,
    onChange: (AnimeBackupOptions) -> Unit,
    onToggleCategory: (Long) -> Unit,
) {
    SectionCard(MR.strings.label_library) {
        LabeledCheckbox(
            label = stringResource(MR.strings.manga),
            checked = options.libraryEntries,
            onCheckedChange = { onChange(options.copy(libraryEntries = it)) },
        )
        LabeledCheckbox(
            label = stringResource(AMR.strings.episodes),
            checked = options.episodes,
            onCheckedChange = { onChange(options.copy(episodes = it)) },
            enabled = options.libraryEntries,
        )
        LabeledCheckbox(
            label = stringResource(MR.strings.history),
            checked = options.history,
            onCheckedChange = { onChange(options.copy(history = it)) },
            enabled = options.libraryEntries && options.episodes,
        )
        LabeledCheckbox(
            label = stringResource(MR.strings.categories),
            checked = options.categories,
            onCheckedChange = { onChange(options.copy(categories = it)) },
            enabled = options.libraryEntries,
        )
        LabeledCheckbox(
            label = stringResource(KMR.strings.anime_backup_watched_entries),
            checked = options.watchedEntries,
            onCheckedChange = { onChange(options.copy(watchedEntries = it)) },
            enabled = options.libraryEntries,
        )
    }

    // Only worth asking once there are categories to choose between
    if (categories.isNotEmpty()) {
        SectionCard(KMR.strings.anime_backup_only_categories) {
            (listOf(CategoryChoice(AnimeBackupOptions.UNCATEGORIZED, stringResource(MR.strings.label_default))) + categories)
                .forEach { category ->
                    LabeledCheckbox(
                        label = category.name,
                        checked = category.key in selectedCategories,
                        onCheckedChange = { onToggleCategory(category.key) },
                        enabled = options.libraryEntries,
                    )
                }
        }
    }

    SectionCard(MR.strings.label_settings) {
        LabeledCheckbox(
            label = stringResource(KMR.strings.anime_backup_extension_repos),
            checked = options.extensionRepos,
            onCheckedChange = { onChange(options.copy(extensionRepos = it)) },
        )
        LabeledCheckbox(
            label = stringResource(MR.strings.source_settings),
            checked = options.sourceSettings,
            onCheckedChange = { onChange(options.copy(sourceSettings = it)) },
        )
        if (showPrivateSettings) {
            LabeledCheckbox(
                label = stringResource(MR.strings.private_settings),
                checked = options.privateSettings,
                onCheckedChange = { onChange(options.copy(privateSettings = it)) },
                enabled = options.sourceSettings,
            )
        }
    }
}
