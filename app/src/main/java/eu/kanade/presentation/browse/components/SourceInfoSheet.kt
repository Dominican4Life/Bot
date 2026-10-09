package eu.kanade.presentation.browse.components

import android.webkit.WebStorage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** What the source sheet shows about one source (manga or anime) */
data class SourceSheetInfo(
    val name: String,
    val baseUrl: String,
    val lang: String,
    val isAnime: Boolean,
    val icon: ImageBitmap?,
    val enabled: Boolean,
    val pinned: Boolean,
    val isNsfw: Boolean,
    val extensionName: String?,
    val versionName: String?,
)

/**
 * KMK: Clears one source's site data: its cookies, and the WebView storage (local storage, caches) of its
 * site, without touching any other source. Returns how many cookies were removed.
 */
fun clearSourceSiteData(urls: List<String>): Int {
    val network = Injekt.get<NetworkHelper>()
    var cleared = 0
    urls.filter { it.isNotBlank() }.distinct().forEach { url ->
        try {
            cleared += network.cookieJar.remove(url.toHttpUrl())
            val parsed = url.toHttpUrl()
            WebStorage.getInstance().deleteOrigin("${parsed.scheme}://${parsed.host}")
        } catch (e: Exception) {
            network.logcat(LogPriority.ERROR, e) { "Failed to clear site data for $url" }
        }
    }
    return cleared
}

/**
 * KMK: The sheet a source tile's settings button opens: details, on/off, pin, and per-source WebView, cookie and
 * site-data clearing, with Remove (uninstall) and the extension's full settings at the bottom.
 */
@Composable
fun SourceInfoSheet(
    info: SourceSheetInfo,
    onDismissRequest: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTogglePin: (() -> Unit)?,
    onOpenWebView: (() -> Unit)?,
    onClearCookies: () -> Unit,
    onClearSiteData: () -> Unit,
    onOpenLatest: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onSettings: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (info.icon != null) {
                        Image(
                            bitmap = info.icon,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Outlined.Public, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                ) {
                    Text(
                        text = info.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (info.baseUrl.isNotBlank()) {
                        Text(
                            text = info.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        InfoChip(info.lang.uppercase())
                        InfoChip(stringResource(if (info.isAnime) KMR.strings.browse_anime else KMR.strings.browse_manga))
                        if (info.isNsfw) InfoChip("18+", warning = true)
                        info.versionName?.let { InfoChip("v$it") }
                    }
                }
                if (onTogglePin != null) {
                    androidx.compose.material3.IconButton(onClick = onTogglePin) {
                        Icon(
                            imageVector = if (info.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = stringResource(if (info.pinned) MR.strings.action_unpin else MR.strings.action_pin),
                            tint = if (info.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // On/off
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { onToggleEnabled(!info.enabled) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(KMR.strings.source_sheet_enabled), style = MaterialTheme.typography.titleSmall)
                            info.extensionName?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(checked = info.enabled, onCheckedChange = onToggleEnabled)
                    }
                }
            }

            // Quick actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                if (onOpenWebView != null) {
                    ActionTile(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(MR.strings.action_open_in_web_view), onOpenWebView)
                }
                if (onOpenLatest != null) {
                    ActionTile(Icons.Outlined.NewReleases, stringResource(MR.strings.latest), onOpenLatest)
                }
                ActionTile(Icons.Outlined.Cookie, stringResource(MR.strings.pref_clear_cookies), onClearCookies)
                ActionTile(Icons.Outlined.Storage, stringResource(KMR.strings.source_sheet_clear_site_data), onClearSiteData)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onRemove != null) {
                    OutlinedButton(
                        onClick = onRemove,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(MR.strings.ext_remove))
                    }
                }
                if (onSettings != null) {
                    Button(onClick = onSettings, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(MR.strings.action_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String, warning: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.weight(1f),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
