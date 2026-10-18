package eu.kanade.tachiyomi.ui.browse.source.browse

import android.app.Application
import android.content.Context
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: When each title's details and chapters were last got from its source by the Browse preview, so tapping a
 * cover again within a day shows the saved ones instead of asking the source. Kept in its own small file, read the
 * first time the preview needs it.
 */
object PreviewFetchTimes {

    private const val FRESH_MS = 24L * 60 * 60 * 1000

    private val prefs by lazy {
        Injekt.get<Application>().getSharedPreferences("browse_preview_fetched", Context.MODE_PRIVATE)
    }

    fun isFresh(mangaId: Long): Boolean =
        System.currentTimeMillis() - prefs.getLong(mangaId.toString(), 0L) < FRESH_MS

    fun mark(mangaId: Long) {
        val now = System.currentTimeMillis()
        val editor = prefs.edit().putLong(mangaId.toString(), now)
        // Forget the old ones now and then, so the file stays small
        if (prefs.all.size > 2000) {
            prefs.all.forEach { (key, value) ->
                if (value is Long && now - value > FRESH_MS) editor.remove(key)
            }
        }
        editor.apply()
    }
}
