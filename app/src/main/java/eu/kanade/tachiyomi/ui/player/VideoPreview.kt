package eu.kanade.tachiyomi.ui.player

import android.app.Activity
import android.app.ActivityOptions
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoType
import eu.kanade.tachiyomi.jssource.HlsRelay
import eu.kanade.tachiyomi.util.system.AppLog
import okhttp3.Headers

/**
 * KMK (r116): Plays one plain address (with its headers) in the app's mpv player, straight in a small
 * picture-in-picture window, for the Source Lab's Preview buttons.
 *
 * - No anime, episode or history: nothing is saved, tracked or listed (PlayerViewModel.initPreview).
 * - Android 13+: launched INTO picture-in-picture (ActivityOptions.makeLaunchIntoPip), so there is never a
 *   full-screen frame and the launching screen stays exactly as it is (the system parks the player in the pinned
 *   window; expanding it puts it on top of the launching screen, back returns there).
 * - Android 8–12: no launch animation and the player enters picture-in-picture on its first resume, before the
 *   video has loaded, with the player's orientation request held back so the launching screen never rotates.
 * - Closing the small window finishes the preview (PlayerActivity.onStop's dismiss path).
 */
object VideoPreview {

    internal const val EXTRA_PREVIEW = "kmkPreview"
    internal const val EXTRA_URL = "kmkPreviewUrl"
    internal const val EXTRA_TITLE = "kmkPreviewTitle"
    internal const val EXTRA_HEADER_NAMES = "kmkPreviewHeaderNames"
    internal const val EXTRA_HEADER_VALUES = "kmkPreviewHeaderValues"

    /** Set when the system was asked to start the player already in picture-in-picture (Android 13+) */
    internal const val EXTRA_LAUNCHED_INTO_PIP = "kmkLaunchedIntoPip"

    /** Set on any start that should land in picture-in-picture (preview or EXTRA_START_PIP) */
    internal const val EXTRA_SOURCE_RECT = "kmkPipSourceRect"

    internal data class Request(val url: String, val headers: Map<String, String>, val title: String)

    fun open(
        activity: Activity,
        url: String,
        headers: Map<String, String>,
        title: String,
        sourceRect: Rect? = null,
    ) {
        val names = headers.keys.toTypedArray()
        val intent = Intent(activity, PlayerActivity::class.java).apply {
            putExtra(EXTRA_PREVIEW, true)
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_HEADER_NAMES, names)
            putExtra(EXTRA_HEADER_VALUES, names.map { headers[it].orEmpty() }.toTypedArray())
        }
        AppLog.add('I', "player", "Preview requested: $title")
        launchIntoPip(activity, intent, sourceRect)
    }

    /**
     * Starts [intent] (a PlayerActivity intent) so it lands straight in picture-in-picture without covering
     * [activity]. Also usable for `PlayerActivity.EXTRA_START_PIP` starts (an opening, ending or trailer).
     */
    fun launchIntoPip(activity: Activity, intent: Intent, sourceRect: Rect? = null) {
        val rect = sourceRect?.takeIf { it.width() > 0 && it.height() > 0 }
        rect?.let { intent.putExtra(EXTRA_SOURCE_RECT, Rect(it)) }
        val pipSupported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (pipSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .apply { rect?.let { setSourceRectHint(it) } }
                .build()
            val started = runCatching {
                // No NEW_TASK and no CLEAR_TOP: the player starts beside the caller and the system moves it into
                // the pinned window itself, so the caller's task is never torn down or brought back
                activity.startActivity(
                    Intent(intent).putExtra(EXTRA_LAUNCHED_INTO_PIP, true),
                    ActivityOptions.makeLaunchIntoPip(params).toBundle(),
                )
            }
            started.exceptionOrNull()?.let {
                AppLog.add('W', "player", "Launch into picture-in-picture failed (${it.message}); starting normally")
            }
            if (started.isSuccess) return
        }
        // Android 8–12 (or no PiP): no open animation; PlayerActivity enters PiP on its first resume
        activity.startActivity(intent, ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle())
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    internal fun isPreview(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_PREVIEW, false) == true

    internal fun requestOf(intent: Intent): Request? {
        val url = intent.getStringExtra(EXTRA_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val names = intent.getStringArrayExtra(EXTRA_HEADER_NAMES).orEmpty()
        val values = intent.getStringArrayExtra(EXTRA_HEADER_VALUES).orEmpty()
        val headers = names.indices.associate { names[it] to values.getOrElse(it) { "" } }
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Preview"
        return Request(url, headers, title)
    }

    @Suppress("DEPRECATION")
    internal fun sourceRectOf(intent: Intent?): Rect? = intent?.getParcelableExtra(EXTRA_SOURCE_RECT)

    /**
     * The player's video for [request], built the way Code editor sources build theirs (JsVideos): HLS goes through
     * the on-device relay with the same headers. Opens a local socket the first time, so call it off the main thread.
     */
    internal fun videoOf(request: Request): Video {
        var url = request.url
        if (url.startsWith("//")) url = "https:$url"
        val headers = Headers.Builder().apply {
            request.headers.forEach { (k, v) -> runCatching { set(k, v) } }
        }.build()
        val path = url.substringBefore('?').lowercase()
        val isDash = path.endsWith(".mpd")
        val isHls = !isDash && url.contains("m3u8", ignoreCase = true)
        // An address already handed out by the relay (a Test Run's) isn't wrapped a second time
        val isRelayed = url.startsWith("http://127.0.0.1")
        val playUrl = if (isHls && !isRelayed && url.startsWith("http")) {
            runCatching { HlsRelay.wrap(url, headers) }.getOrDefault(url)
        } else {
            url
        }
        return Video(
            videoUrl = playUrl,
            videoTitle = "Preview",
            headers = headers,
            initialized = true,
        ).apply {
            type = when {
                isHls -> VideoType.HLS
                isDash -> VideoType.DASH
                else -> VideoType.VIDEO
            }
        }
    }
}
