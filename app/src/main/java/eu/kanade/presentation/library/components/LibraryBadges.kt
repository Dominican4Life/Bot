package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.domain.extension.interactor.GetExtensionLanguages.Companion.getLanguageIconID
import eu.kanade.domain.source.model.icon
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.R
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.source.local.isLocal

@Composable
internal fun DownloadsBadge(count: Long) {
    if (count > 0) {
        Badge(
            text = "$count",
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@Composable
internal fun UnreadBadge(count: Long) {
    if (count > 0) {
        Badge(text = "$count")
    }
}

@Composable
internal fun LanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
    // KMK -->
    useLangIcon: Boolean = true,
    // KMK <--
) {
    // KMK -->
    if (!isLocal && sourceLanguage.isNotEmpty()) {
        if (useLangIcon) {
            val iconResId = getLanguageIconID(sourceLanguage) ?: R.drawable.globe
            Badge(
                painter = painterResource(id = iconResId),
                color = Color.Transparent,
                modifier = Modifier
                    .width(25.dp)
                    .height(18.dp),
            )
        } else {
            // KMK <--
            Badge(
                text = sourceLanguage.uppercase(),
                color = MaterialTheme.colorScheme.tertiary,
                textColor = MaterialTheme.colorScheme.onTertiary,
            )
        }
    }
}

// KMK -->
@Composable
fun SourceIconBadge(
    source: Source?,
) {
    if (source == null) return
    val icon = source.icon

    when {
        source.isStub && icon == null -> {
            Badge(
                imageVector = Icons.Filled.Warning,
                iconColor = MaterialTheme.colorScheme.error,
                color = MaterialTheme.colorScheme.errorContainer,
            )
        }
        icon != null -> {
            Badge(
                imageBitmap = icon,
                modifier = Modifier
                    .scale(1.3f)
                    .height(18.dp),
            )
        }
        source.isLocal() -> {
            Badge(
                imageVector = Icons.Outlined.Folder,
                color = MaterialTheme.colorScheme.tertiary,
                iconColor = MaterialTheme.colorScheme.onTertiary,
            )
        }
        else -> {
            // Default source icon (if source doesn't have an icon)
            Badge(
                imageVector = Icons.Outlined.LocalLibrary,
                color = MaterialTheme.colorScheme.tertiary,
                iconColor = MaterialTheme.colorScheme.onTertiary,
            )
        }
    }
}
// KMK <--

@PreviewLightDark
@Composable
private fun BadgePreview() {
    TachiyomiPreviewTheme {
        BadgeGroup {
            DownloadsBadge(count = 10)
            UnreadBadge(count = 10)
            LanguageBadge(isLocal = true, sourceLanguage = "en")
            LanguageBadge(isLocal = false, sourceLanguage = "en", useLangIcon = false)
            LanguageBadge(isLocal = false, sourceLanguage = "vi")
        }
    }
}

// KMK -->
/** Every badge a library cover can show, keyed like [eu.kanade.domain.ui.CoverBadge], for [eu.kanade.presentation.components.BadgeCorners] */
internal fun libraryBadges(
    item: eu.kanade.tachiyomi.ui.library.LibraryItem,
): Map<String, @Composable androidx.compose.foundation.layout.RowScope.() -> Unit> = mapOf(
    eu.kanade.domain.ui.CoverBadge.DOWNLOADED to { DownloadsBadge(count = item.downloadCount) },
    eu.kanade.domain.ui.CoverBadge.UNREAD to { UnreadBadge(count = item.unreadCount) },
    eu.kanade.domain.ui.CoverBadge.LANGUAGE to {
        LanguageBadge(
            isLocal = item.isLocal,
            sourceLanguage = item.sourceLanguage,
            useLangIcon = item.useLangIcon,
        )
    },
    eu.kanade.domain.ui.CoverBadge.SOURCE to { SourceIconBadge(source = item.source) },
)
// KMK <--
