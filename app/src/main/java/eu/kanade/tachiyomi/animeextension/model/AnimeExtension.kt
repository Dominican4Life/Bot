package eu.kanade.tachiyomi.animeextension.model

import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.extension.model.Extension
import mihon.domain.extension.model.ExtensionStore

/**
 * KMK: An installed anime (Aniyomi) extension.
 *
 * Available and untrusted anime extensions reuse [Extension.Available] and [Extension.Untrusted], since
 * their repo index and install details are the same as for manga extensions.
 */
data class AnimeExtension(
    val name: String,
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: Double,
    val lang: String,
    val isNsfw: Boolean,
    val isTorrent: Boolean,
    val signatureHash: String,
    val storeName: String? = null,
    val pkgFactory: String?,
    val sources: List<AnimeSource>,
    val icon: Drawable?,
    val hasUpdate: Boolean = false,
    val isObsolete: Boolean = false,
    val isShared: Boolean,
    val store: ExtensionStore? = null,
)

sealed interface AnimeLoadResult {
    data class Success(val extension: AnimeExtension) : AnimeLoadResult
    data class Untrusted(val extension: Extension.Untrusted) : AnimeLoadResult
    data object Error : AnimeLoadResult
}
