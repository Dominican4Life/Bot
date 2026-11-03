package eu.kanade.presentation.more.settings.screen.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

class CreateBackupScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { CreateBackupScreenModel() }
        val state by model.state.collectAsState()
        val cardModifier = Modifier.padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        )

        val chooseBackupDir = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/*"),
        ) {
            if (it != null) {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                model.createBackup(context, it)
                navigator.pop()
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_create_backup),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_create),
                actionEnabled = state.options.canCreate(),
                onClickAction = {
                    if (!BackupCreateJob.isManualJobRunning(context)) {
                        try {
                            chooseBackupDir.launch(BackupCreator.getFilename())
                        } catch (e: ActivityNotFoundException) {
                            context.toast(MR.strings.file_picker_error)
                        }
                    } else {
                        context.toast(MR.strings.backup_in_progress)
                    }
                },
            ) {
                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                    item {
                        WarningBanner(MR.strings.restore_miui_warning)
                    }
                }

                // KMK (r114): the options as two chip groups (titles, then settings); same options, same enabled
                // rules (BackupOptions.Entry.enabled), same category choice
                item {
                    BackupCard(modifier = cardModifier) {
                        BackupMuted("What goes in · titles")
                        Options(BackupOptions.libraryOptions, state, model)
                    }
                }

                item {
                    BackupCard(modifier = cardModifier) {
                        BackupMuted("What goes in · settings")
                        Options(BackupOptions.settingsOptions, state, model)
                        BackupMuted(
                            "Logins & tokens are tracker logins and other private settings; keep that file safe.",
                        )
                    }
                }

                // KMK -->
                if (state.categoryEntries.isNotEmpty()) {
                    item {
                        CategoryChoiceCard(
                            entries = state.categoryEntries,
                            picked = state.pickedCategories,
                            enabled = state.options.libraryEntries,
                            onToggle = model::toggleCategory,
                        )
                    }
                }
                // KMK <--
            }
        }
    }

    @Composable
    private fun Options(
        options: ImmutableList<BackupOptions.Entry>,
        state: CreateBackupScreenModel.State,
        model: CreateBackupScreenModel,
    ) {
        // KMK (r114): FilterChips instead of checkboxes
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { option ->
                val checked = option.getter(state.options)
                FilterChip(
                    selected = checked,
                    onClick = { model.toggle(option.setter, !checked) },
                    label = { Text(option.chip ?: stringResource(option.label)) },
                    enabled = option.enabled(state.options),
                )
            }
        }
    }
}

private class CreateBackupScreenModel : StateScreenModel<CreateBackupScreenModel.State>(State()) {

    // KMK -->
    init {
        screenModelScope.launchIO {
            val entries = libraryCategoryChoices()
            mutableState.update {
                it.copy(categoryEntries = entries, pickedCategories = entries.map { entry -> entry.key }.toSet())
            }
        }
    }

    fun toggleCategory(key: String, picked: Boolean) {
        mutableState.update {
            it.copy(pickedCategories = if (picked) it.pickedCategories + key else it.pickedCategories - key)
        }
    }
    // KMK <--

    fun toggle(setter: (BackupOptions, Boolean) -> BackupOptions, enabled: Boolean) {
        mutableState.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    fun createBackup(context: Context, uri: Uri) {
        // KMK: every category ticked means everything, as before
        val state = state.value
        val keys = state.pickedCategories.takeIf { it.size < state.categoryEntries.size }
        BackupCreateJob.startNow(context, uri, state.options.copy(categoryKeys = keys))
    }

    @Immutable
    data class State(
        val options: BackupOptions = BackupOptions(),
        // KMK -->
        val categoryEntries: List<CategoryChoiceEntry> = emptyList(),
        val pickedCategories: Set<String> = emptySet(),
        // KMK <--
    )
}
