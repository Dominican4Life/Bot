package eu.kanade.tachiyomi.ui.browse.extension.unified

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep

/** KMK: The kinds of extension the unified Extensions screen manages */
enum class ExtensionKind(val label: String) {
    MIHON("Mihon"),
    ANIYOMI("Aniyomi"),

    /** Sumizuri JS / JSON sources from the Code editor; they have their own tab, not packages or repos */
    JS("JS"),
}

enum class PackageState {
    AVAILABLE,
    UPDATE_AVAILABLE,
    INSTALLED,
    INSTALLING,
    UNTRUSTED,
    OBSOLETE,
    ;

    val isInstalled: Boolean
        get() = this == UPDATE_AVAILABLE || this == INSTALLED || this == UNTRUSTED || this == OBSOLETE
}

enum class PackageStatusFilter {
    ALL,
    UPDATES,
    INSTALLED,
    NOT_INSTALLED,
    ;

    fun matches(state: PackageState): Boolean = when (this) {
        ALL -> true
        UPDATES -> state == PackageState.UPDATE_AVAILABLE
        INSTALLED -> state.isInstalled
        NOT_INSTALLED -> !state.isInstalled
    }
}

/** One installable package (an APK extension today; JAR and Cloudstream plugins later) */
@Immutable
data class UnifiedPackage(
    val key: String,
    val kind: ExtensionKind,
    val name: String,
    val pkgName: String,
    val versionName: String,
    /** Set when an update is waiting: the version on the device */
    val installedVersionName: String?,
    val lang: String,
    val isNsfw: Boolean,
    val repoUrl: String?,
    val repoName: String?,
    val state: PackageState,
    val installStep: InstallStep,
    val icon: Drawable?,
    val iconUrl: String?,
    val sourceNames: List<String>,
    val activeSources: Int,
    val totalSources: Int,
    val signatureHash: String,
    /** The manga or anime extension behind this row, to act on */
    val manga: Extension? = null,
    val anime: AnimeExtension? = null,
    val animeOther: Extension? = null,
)

/** One source of an installed package */
@Immutable
data class UnifiedSource(
    val id: Long,
    val kind: ExtensionKind,
    val name: String,
    val lang: String,
    val enabled: Boolean,
    val pinned: Boolean,
    val isNsfw: Boolean,
    val pkgName: String,
    val packageName: String,
    val repoUrl: String?,
    val repoName: String?,
    val icon: Drawable?,
)

/** One extension repository */
@Immutable
data class UnifiedRepo(
    val kind: ExtensionKind,
    val indexUrl: String,
    val name: String,
    val website: String?,
    val packageCount: Int,
    val installedCount: Int,
    val enabled: Boolean,
)

@Immutable
data class UnifiedFilters(
    val query: String = "",
    val kinds: Set<ExtensionKind> = emptySet(),
    val repoUrl: String? = null,
    val status: PackageStatusFilter = PackageStatusFilter.ALL,
)

/** One JS / JSON source made in the Code editor or imported, for the JS tab */
@Immutable
data class UnifiedJsSource(
    /** The source's key in SumizuriManager */
    val key: String,
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val iconUrl: String?,
    /** A JSON definition rather than JavaScript */
    val json: Boolean,
    val anime: Boolean,
    val enabled: Boolean,
)
