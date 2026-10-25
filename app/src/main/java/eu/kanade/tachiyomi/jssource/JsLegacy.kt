package eu.kanade.tachiyomi.jssource

import android.content.Context
import eu.kanade.tachiyomi.util.system.AppLog
import java.io.File

/**
 * KMK: The names older builds kept JS / JSON source data under. Their data is moved to today's names once, the
 * first time the source manager starts; the old name also still makes the ids of sources that don't set their own
 * (so they keep their library) and is still read in old video page links.
 */
internal object JsLegacy {
    private const val OLD = "sumizuri"

    /** What a source without an `id` of its own gets its id from; must never change */
    const val ID_SEED = OLD

    /** How a video page link began in older builds */
    const val OLD_VIDEO_MARK = "$OLD-video:"

    private val folders = listOf(
        "${OLD}_sources" to "js_sources",
        "${OLD}_storage" to "js_storage",
        "${OLD}_tags" to "js_tags",
    )

    /** Moves saved sources, their storage, tag lists and settings files to the new names */
    fun migrate(context: Context) {
        runCatching {
            folders.forEach { (old, new) ->
                val from = File(context.filesDir, old)
                if (!from.exists()) return@forEach
                val to = File(context.filesDir, new)
                if (!to.exists() || to.list().isNullOrEmpty()) {
                    to.deleteRecursively()
                    if (!from.renameTo(to)) {
                        from.copyRecursively(to, overwrite = true)
                        from.deleteRecursively()
                    }
                } else {
                    from.copyRecursively(to, overwrite = false)
                    from.deleteRecursively()
                }
            }
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            prefsDir.listFiles()?.forEach { file ->
                if (!file.name.startsWith("${OLD}_")) return@forEach
                val target = File(prefsDir, "js_" + file.name.removePrefix("${OLD}_"))
                if (!target.exists()) file.renameTo(target) else file.delete()
            }
        }.onFailure { AppLog.add('W', "js", "Moving saved JS sources to the new folders failed: ${it.message}") }
    }
}
