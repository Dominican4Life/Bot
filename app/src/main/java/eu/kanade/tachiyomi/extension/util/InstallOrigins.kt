package eu.kanade.tachiyomi.extension.util

import android.app.Application
import android.content.Context
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r88): the repo each extension package was last installed or updated from, recorded when the install starts
 * (manga and anime apart). `copyFor` prefers that repo's copy. Before, the repo a package was matched to was kept
 * as "the repo it came from", so a package from a repo with no signing fingerprint, matched by name to the first
 * repo offering it, stayed tied to that repo for good.
 *
 * Kept in its own preferences file (package -> repo address), so it isn't in backups.
 */
object InstallOrigins {

    private val prefs by lazy {
        Injekt.get<Application>().getSharedPreferences("extension_install_origins", Context.MODE_PRIVATE)
    }

    private fun key(anime: Boolean, pkgName: String) = (if (anime) "anime:" else "manga:") + pkgName

    /** An install of [pkgName] from the repo at [indexUrl] started */
    fun remember(anime: Boolean, pkgName: String, indexUrl: String) {
        runCatching { prefs.edit().putString(key(anime, pkgName), indexUrl).apply() }
    }

    /** The address of the repo [pkgName] was last installed from, if known */
    fun repoOf(anime: Boolean, pkgName: String): String? =
        runCatching { prefs.getString(key(anime, pkgName), null) }.getOrNull()
}
