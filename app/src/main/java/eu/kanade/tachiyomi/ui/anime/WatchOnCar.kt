package eu.kanade.tachiyomi.ui.anime

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.net.toUri
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoType
import eu.kanade.tachiyomi.jssource.HlsRelay
import okhttp3.Headers
import tachiyomi.core.common.util.lang.withIOContext

/**
 * KMK (r143): Watch on Car. Sends what is playing to the car screen through the "Watch on car" share screen of an
 * Android Auto video player (Local Video Player, from AA Store). That store gives the app a random package name on
 * every install, so it is found by its share screen's class name, which stays the same.
 *
 * - A download goes as the file itself (a readable content:// link).
 * - A stream goes as a link: an HLS stream through the app's local relay (with the source's headers and cookies; the
 *   address ends in .m3u8, so the player's ExoPlayer reads it as a stream and skipping works). The relay keeps
 *   working while this app runs in the background. A DASH manifest or plain file goes as its own link (a plain file
 *   through the relay too, for its cookies).
 */
object WatchOnCar {

    private const val SCREEN = "ShareFromWatchLocalAppActivity"

    @Volatile
    private var cached: Pair<Long, ComponentName?>? = null

    /** The player's Watch on car screen, or null when it isn't installed (looked up at most every 20 s) */
    fun target(context: Context): ComponentName? {
        cached?.let { (at, found) -> if (SystemClock.elapsedRealtime() - at < 20_000) return found }
        val found = runCatching {
            listOf("video/*", "text/plain").firstNotNullOfOrNull { type ->
                context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(type), 0)
                    .firstOrNull { it.activityInfo?.name?.endsWith(SCREEN) == true }
                    ?.activityInfo?.let { ComponentName(it.packageName, it.name) }
            }
        }.getOrNull()
        cached = SystemClock.elapsedRealtime() to found
        return found
    }

    fun available(context: Context): Boolean = target(context) != null

    /** The intent that hands [video] to the car screen; null when there is nothing to send. Call from a coroutine */
    suspend fun intent(context: Context, video: Video, title: String, sourceHeaders: Headers?): Intent? {
        val target = target(context) ?: return null
        val raw = video.videoUrl
        if (raw.isBlank() || raw == "null" || raw.startsWith("fd://")) return null
        val remote = raw.startsWith("http://") || raw.startsWith("https://")
        if (!remote) {
            val uri = ExternalPlayer.videoUri(context, video)
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "video/*"
            return Intent(Intent.ACTION_SEND).apply {
                component = target
                setType(type)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, title)
                clipData = ClipData.newRawUri(title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val headers = video.headers ?: sourceHeaders ?: Headers.Builder().build()
        // A code editor source's video is already a relay address: its original stream is wrapped again, so the
        // address has the r143 form (ending in .m3u8)
        val source = if (raw.startsWith("http://127.0.0.1") || raw.startsWith("http://localhost")) {
            HlsRelay.original(raw).takeUnless { it.startsWith("http://127.0.0.1") } ?: raw.toUri().getQueryParameter("u")
        } else {
            raw
        } ?: return null
        val path = source.substringBefore('?').lowercase()
        val link = when {
            path.endsWith(".mpd") || video.type == VideoType.DASH -> source
            video.type == VideoType.HLS || source.contains("m3u8", ignoreCase = true) ->
                withIOContext { runCatching { HlsRelay.wrap(source, headers) }.getOrDefault(source) }
            else -> withIOContext { runCatching { HlsRelay.wrapFile(source, headers) }.getOrDefault(source) }
        }
        return Intent(Intent.ACTION_SEND).apply {
            component = target
            setType("text/plain")
            putExtra(Intent.EXTRA_TEXT, link)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
