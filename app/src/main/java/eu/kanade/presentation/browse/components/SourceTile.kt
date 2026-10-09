package eu.kanade.presentation.browse.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import eu.kanade.domain.ui.BadgeCorner
import eu.kanade.domain.ui.SourceBadge
import eu.kanade.presentation.components.BadgeCorners
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** How wide a tile may grow and how tall it always is (same as Sumizuri's source wall) */
val SourceTileMinWidth = 112.dp
val SourceTileHeight = 138.dp
val SourceTileGap = 10.dp

/** The site a source reads from, as a person would say it: "mkissa.to" */
fun sourceSiteOf(baseUrl: String?): String {
    val host = runCatching { baseUrl?.toUri()?.host }.getOrNull().orEmpty()
    return host.removePrefix("www.")
}

/**
 * KMK: One source on the Browse wall (design from Sumizuri): its icon over a wash of its own colours, its name
 * and the site it reads from. Badges (language, 18+, settings, pin, latest) go in the corners chosen in
 * Settings › Library › Badges › Source badges.
 */
@Composable
fun SourceTile(
    name: String,
    site: String,
    icon: ImageBitmap?,
    lang: String,
    isNsfw: Boolean,
    isPinned: Boolean,
    supportsLatest: Boolean,
    corners: BadgeCorners,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickSettings: (() -> Unit)?,
    onClickPin: (() -> Unit)?,
    onClickLatest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fallbackIcon: @Composable () -> Unit = { DefaultTileIcon() },
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .height(SourceTileHeight)
            .alpha(if (enabled) 1f else 0.55f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            if (icon != null) IconWash(icon)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .shadow(6.dp, RoundedCornerShape(15.dp))
                        .clip(RoundedCornerShape(15.dp)),
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        fallbackIcon()
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (site.isNotEmpty()) {
                    Text(
                        text = site,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }

            val badges: Map<String, @Composable () -> Unit> = buildMap {
                if (lang.isNotEmpty()) put(SourceBadge.LANGUAGE) { LanguageChip(lang) }
                if (isNsfw) put(SourceBadge.NSFW) { NsfwChip() }
                if (onClickSettings != null) {
                    put(SourceBadge.SETTINGS) {
                        TileButton(
                            icon = { Icon(Icons.Outlined.Settings, contentDescription = stringResource(MR.strings.action_settings), modifier = Modifier.size(18.dp)) },
                            onClick = onClickSettings,
                        )
                    }
                }
                if (onClickPin != null) {
                    put(SourceBadge.PIN) {
                        TileButton(
                            icon = {
                                Icon(
                                    imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                    contentDescription = stringResource(if (isPinned) MR.strings.action_unpin else MR.strings.action_pin),
                                    tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = onClickPin,
                        )
                    }
                }
                if (supportsLatest && onClickLatest != null) {
                    put(SourceBadge.LATEST) {
                        TileButton(
                            icon = { Icon(Icons.Outlined.NewReleases, contentDescription = stringResource(MR.strings.latest), modifier = Modifier.size(18.dp)) },
                            onClick = onClickLatest,
                        )
                    }
                }
            }
            CornerColumn(corners, BadgeCorner.TopLeft, badges, Alignment.TopStart, Alignment.Start)
            CornerColumn(corners, BadgeCorner.TopRight, badges, Alignment.TopEnd, Alignment.End)
            CornerColumn(corners, BadgeCorner.BottomLeft, badges, Alignment.BottomStart, Alignment.Start)
            CornerColumn(corners, BadgeCorner.BottomRight, badges, Alignment.BottomEnd, Alignment.End)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerColumn(
    corners: BadgeCorners,
    corner: BadgeCorner,
    badges: Map<String, @Composable () -> Unit>,
    alignment: Alignment,
    horizontal: Alignment.Horizontal,
) {
    val content = corners.badgesIn(corner).mapNotNull { badges[it] }
    if (content.isEmpty()) return
    Column(
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .align(alignment)
            .padding(6.dp),
    ) { content.forEach { it() } }
}

/** The icon's colours washed across the tile: the icon shrunk to a few pixels, then stretched back out */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.IconWash(icon: ImageBitmap) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tiny = remember(icon) {
        runCatching {
            Bitmap.createScaledBitmap(icon.asAndroidBitmap(), 6, 6, true).asImageBitmap()
        }.getOrNull()
    }
    if (tiny == null) return
    Image(
        painter = remember(tiny) { BitmapPainter(tiny, filterQuality = FilterQuality.Low) },
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        alpha = if (dark) 0.34f else 0.24f,
        modifier = Modifier.matchParentSize(),
    )
}

private fun androidx.compose.ui.graphics.Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
private fun LanguageChip(lang: String) {
    val label = when (lang) {
        "all", "multi", "mul", "" -> "ALL"
        "other" -> "?"
        else -> lang.split('-', '_').first().uppercase()
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun NsfwChip() {
    Text(
        text = "18+",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun TileButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) { icon() }
}

@Composable
fun DefaultTileIcon() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Icon(
            imageVector = Icons.Outlined.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** A section title over a group of tiles ("Pinned", "English" ...) */
@Composable
fun SourceTileHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = MaterialTheme.padding.medium, bottom = 4.dp),
    )
}
