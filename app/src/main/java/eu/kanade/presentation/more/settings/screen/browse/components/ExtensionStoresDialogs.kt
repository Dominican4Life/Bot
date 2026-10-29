package eu.kanade.presentation.more.settings.screen.browse.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.screen.browse.FoundRepo
import eu.kanade.presentation.more.settings.screen.browse.RepoInput
import eu.kanade.presentation.more.settings.screen.browse.RepoKind
import eu.kanade.presentation.more.settings.screen.browse.RepoMemory
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.delay
import mihon.data.extension.service.ExtensionStoreUrls
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

/**
 * KMK: Add a repository, the way Kototoro does it: any form of its address (folder, repo.json, index.min.json,
 * index.pb, with or without https), what it is added as (Auto goes by its packages), several addresses at once or a
 * file of them, and the repositories added here before that aren't added now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtensionStoreCreateDialog(
    onDismissRequest: () -> Unit,
    onCreate: (String, RepoKind) -> Unit,
    storeIndexUrls: Set<String>,
    processing: Boolean,
    errorMessage: String?,
) {
    val state = rememberTextFieldState()
    val context = LocalContext.current
    var kind by remember { mutableStateOf(RepoKind.AUTO) }
    val addedKeys = remember(storeIndexUrls) { storeIndexUrls.map { ExtensionStoreUrls.key(it) }.toSet() }
    val storeAlreadyExists by remember(addedKeys) {
        derivedStateOf {
            val urls = RepoInput.urls(state.text.toString())
            urls.size == 1 && ExtensionStoreUrls.key(urls.first()) in addedKeys
        }
    }
    var known by remember(addedKeys) { mutableStateOf(RepoMemory.all().filter { ExtensionStoreUrls.key(it.url) !in addedKeys }) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            context.toast("Couldn't read that file")
        } else {
            onCreate(text, kind)
        }
    }

    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_addStore_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    state = state,
                    label = {
                        Text(text = stringResource(MR.strings.extensionStoresScreen_addStoreInput_inputLabel))
                    },
                    supportingText = {
                        Text(
                            text = errorMessage ?: if (storeAlreadyExists) {
                                stringResource(MR.strings.extensionStoresScreen_addStore_alreadyExists)
                            } else {
                                "A repo address, its folder, or several addresses / pasted JSON"
                            },
                        )
                    },
                    isError = errorMessage != null || storeAlreadyExists,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                )
                Text("Add as", style = MaterialTheme.typography.labelLarge)
                // KMK: wraps onto a second line instead of cutting the last choice off ("Aniyo")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RepoKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k.label, maxLines = 1) },
                        )
                    }
                }
                TextButton(onClick = { pickFile.launch("*/*") }, enabled = !processing) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Add from a file (.json / .txt)")
                }
                if (known.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Added before", style = MaterialTheme.typography.labelLarge)
                    known.forEach { repo ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !processing) { onCreate(repo.url, kind) }
                                .padding(vertical = 4.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(repo.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    text = listOf(repo.kinds, repo.url).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = {
                                    RepoMemory.forget(repo.url)
                                    known = known - repo
                                },
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(state.text.toString(), kind) },
                enabled = !processing && state.text.isNotBlank() && !storeAlreadyExists,
            ) {
                Text(
                    text = stringResource(
                        resource = if (processing) {
                            MR.strings.extensionStoresScreen_addStore_processing
                        } else {
                            MR.strings.action_add
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )

    LaunchedEffect(focusRequester) {
        // TODO: https://issuetracker.google.com/issues/204502668
        delay(0.1.seconds)
        focusRequester.requestFocus()
    }
}

/**
 * KMK: What was found before it's added (Kototoro's trust step): the repository's name, website, signing key, its
 * packages, and what it'll be added as.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtensionStorePreviewDialog(
    found: FoundRepo,
    kind: RepoKind,
    processing: Boolean,
    errorMessage: String?,
    onDismissRequest: () -> Unit,
    onConfirm: (RepoKind) -> Unit,
) {
    var picked by remember(kind) { mutableStateOf(kind) }
    val store = found.store
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(store.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoLine("Website", store.contact.website)
                InfoLine("Address", store.indexUrl)
                InfoLine(
                    "Signing key",
                    store.signingKey.takeIf { it.isNotBlank() && it != "NO_SIGNING_KEY" }
                        ?.chunked(4)?.take(8)?.joinToString(" ")?.plus(" …")
                        ?: "None given",
                )
                InfoLine(
                    "Packages",
                    buildList {
                        if (found.mangaPackages > 0) add("${found.mangaPackages} manga")
                        if (found.animePackages > 0) add("${found.animePackages} anime")
                    }.joinToString(", ").ifEmpty { "None" },
                )
                found.alreadyAddedAs?.let {
                    Text(
                        "Already added as $it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Add as", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RepoKind.entries.forEach { k ->
                        FilterChip(selected = picked == k, onClick = { picked = k }, label = { Text(k.label, maxLines = 1) })
                    }
                }
                if (picked == RepoKind.AUTO) {
                    Text(
                        text = when {
                            found.mangaPackages > 0 && found.animePackages > 0 -> "Both: Mihon for manga, Aniyomi for anime"
                            found.animePackages > 0 -> "Aniyomi (anime)"
                            else -> "Mihon (manga)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Only add repositories you trust: their extensions run in the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }, enabled = !processing) {
                Text(
                    stringResource(
                        if (processing) MR.strings.extensionStoresScreen_addStore_processing else MR.strings.action_add,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ExtensionStoreDeleteDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    storeName: String,
    storeIndexUrl: String,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_deleteStore_title))
        },
        text = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_deleteStore_body, storeName, storeIndexUrl))
        },
        confirmButton = {
            TextButton(onClick = {
                onDelete()
                onDismissRequest()
            }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
fun ExtensionStoreConfirmDialog(
    onDismissRequest: () -> Unit,
    onCreate: () -> Unit,
    storeIndexUrl: String,
    storeAlreadyExists: Boolean,
    processing: Boolean,
    errorMessage: String?,
) {
    val state = rememberTextFieldState(initialText = storeIndexUrl)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_addStore_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(MR.strings.extensionStoresScreen_addStoreDeeplink_bodyText))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    state = state,
                    readOnly = true,
                    supportingText = when {
                        storeAlreadyExists -> {
                            {
                                Text(text = stringResource(MR.strings.extensionStoresScreen_addStore_alreadyExists))
                            }
                        }
                        errorMessage != null -> {
                            {
                                Text(text = errorMessage)
                            }
                        }
                        else -> null
                    },
                    isError = errorMessage != null || storeAlreadyExists,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate, enabled = !storeAlreadyExists && !processing) {
                Text(
                    text = stringResource(
                        resource = if (processing) {
                            MR.strings.extensionStoresScreen_addStore_processing
                        } else {
                            MR.strings.action_add
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
