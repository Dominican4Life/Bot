package eu.kanade.tachiyomi.ui.anime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.ui.player.AMNIS
import eu.kanade.tachiyomi.ui.player.JUST_PLAYER
import eu.kanade.tachiyomi.ui.player.MPV_KT
import eu.kanade.tachiyomi.ui.player.MPV_KT_PREVIEW
import eu.kanade.tachiyomi.ui.player.MPV_PLAYER
import eu.kanade.tachiyomi.ui.player.MPV_REMOTE
import eu.kanade.tachiyomi.ui.player.MX_PLAYER
import eu.kanade.tachiyomi.ui.player.MX_PLAYER_FREE
import eu.kanade.tachiyomi.ui.player.MX_PLAYER_PRO
import eu.kanade.tachiyomi.ui.player.NEXT_PLAYER
import eu.kanade.tachiyomi.ui.player.VLC_PLAYER
import eu.kanade.tachiyomi.ui.player.WEB_VIDEO_CASTER
import eu.kanade.tachiyomi.ui.player.X_PLAYER
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import okhttp3.Headers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Locale

/**
 * KMK: Sends a video to an outside player app (modeled on Anikku's ExternalIntents).
 *
 * Uses the player picked in the player settings when there is one, passes the title, the video's headers,
 * where to start and the subtitles the way MX Player, VLC, mpv, Just Player and Next Player read them, and reads
 * back how far the episode was watched when the player returns.
 */
object ExternalPlayer {

    private val playerPreferences: PlayerPreferences get() = Injekt.get()

    /** Where the episode should start, in milliseconds (from the start when it was finished) */
    fun startPosition(episode: Episode): Long {
        val preserve = playerPreferences.preserveWatchingPosition().get()
        val finished = episode.totalSeconds > 0 && episode.lastSecondSeen >= episode.totalSeconds
        return if (episode.seen && (!preserve || finished)) 0L else episode.lastSecondSeen
    }

    /**
     * Intents to try in order: the player chosen in settings first (when there is one), then any app that plays
     * video. Start them for a result (no new task flag), so the watched position comes back.
     */
    fun intents(
        context: Context,
        video: Video,
        title: String,
        startPositionMs: Long,
        sourceHeaders: Headers?,
    ): List<Intent> {
        val uri = videoUri(context, video)
        val headers = video.headers ?: sourceHeaders
        val pkgName = playerPreferences.externalPlayerPreference().get()

        val generic = Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(uri, mimeOf(uri))
            addCommonExtras(title, startPositionMs)
            addHeaders(headers, supported = false)
            addSubtitles(video)
        }
        if (pkgName.isBlank()) return listOf(generic)

        val forPackage = Intent(Intent.ACTION_VIEW).apply {
            if (pkgName == WEB_VIDEO_CASTER) {
                setPackage(pkgName)
            } else {
                val component = componentOf(pkgName)
                if (component != null && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && pkgName == VLC_PLAYER)) {
                    this.component = component
                } else {
                    setPackage(pkgName)
                }
            }
            setDataAndType(uri, "video/*")
            addCommonExtras(title, startPositionMs)
            putExtra("secure_uri", true)
            addHeaders(headers, supported = true)
            addSubtitles(video)
        }
        return listOf(forPackage, generic)
    }

    /**
     * Intent for a download manager app (1DM, ADM and the like), with the headers each of them reads.
     * Wrapped in a chooser so any downloader can be picked.
     */
    fun downloadIntent(video: Video, fileName: String, sourceHeaders: Headers?, chooserTitle: String): Intent {
        val headers = video.headers ?: sourceHeaders
        val bundle = Bundle().apply { headers?.forEach { (name, value) -> putString(name, value) } }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(video.videoUrl.toUri(), "video/*")
            val safeName = fileName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            // 1DM
            putExtra("extra_filename", "$safeName.mp4")
            putExtra("extra_headers", bundle)
            // ADM and others
            putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
            putExtra("title", safeName)
            if (headers != null) putExtra("headers", headers.toFlatArray())
        }
        return Intent.createChooser(intent, chooserTitle)
    }

    /** What an outside player sent back */
    data class Result(val positionMs: Long, val durationMs: Long, val completed: Boolean)

    /**
     * Reads the position and duration a player returns: MX Player and mpv use "position"/"duration" (with
     * "end_by"), VLC uses "extra_position"/"extra_duration". All in milliseconds. Null when nothing came back.
     */
    @Suppress("DEPRECATION")
    fun parseResult(data: Intent?): Result? {
        val extras = data?.extras ?: return null
        fun number(key: String): Long? = when (val value = extras.get(key)) {
            is Int -> value.toLong()
            is Long -> value
            is Float -> value.toLong()
            is Double -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        val endBy = data.getStringExtra("end_by")
        val position = number("extra_position") ?: number("position")
        val duration = number("extra_duration") ?: number("duration")
        if (position == null && duration == null && endBy == null) return null
        return Result(
            positionMs = (position ?: 0L).coerceAtLeast(0L),
            durationMs = (duration ?: 0L).coerceAtLeast(0L),
            completed = endBy == "playback_completion",
        )
    }

    private fun Intent.addCommonExtras(title: String, startPositionMs: Long) {
        putExtra("title", title)
        putExtra(Intent.EXTRA_TITLE, title)
        // Start position in milliseconds, as an Int (like Anikku)
        putExtra("position", startPositionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        putExtra("return_result", true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun Intent.addHeaders(headers: Headers?, supported: Boolean) {
        if (headers == null || headers.size == 0) return
        val flat = headers.toFlatArray()
        // MX Player / mpv / Just Player: [name, value, name, value, ...]
        putExtra("headers", flat)
        // Web Video Caster and some others
        val bundle = Bundle().apply { headers.forEach { (name, value) -> putString(name, value) } }
        putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
        if (!supported) {
            // mpv-style "Name: value" lines for players that only take one string
            putExtra("http-header-fields", headers.joinToString(",") { (name, value) -> "$name: $value" })
        }
    }

    private fun Intent.addSubtitles(video: Video) {
        val tracks = video.subtitleTracks
        if (tracks.isEmpty()) return
        val language = Locale.getDefault().displayLanguage
        val preferred = tracks.indexOfFirst { it.lang.contains(language, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        val preferredUrl = tracks.getOrNull(preferred)?.url
        // MX Player, mpv, Just Player, Next Player
        putExtra("subs", tracks.map { it.url.toUri() }.toTypedArray())
        putExtra("subs.name", tracks.map { it.lang }.toTypedArray())
        putExtra("subs.enable", preferredUrl?.let { arrayOf(it.toUri()) } ?: emptyArray<Uri>())
        // VLC (works best with local files) and Web Video Caster
        preferredUrl?.let {
            putExtra("subtitles_location", it)
            putExtra("subtitle", it)
        }
    }

    private fun Headers.toFlatArray(): Array<String> = flatMap { (name, value) -> listOf(name, value) }.toTypedArray()

    /** Downloaded files are shared through the app's file provider, so the player is allowed to read them */
    private fun videoUri(context: Context, video: Video): Uri {
        val uri = video.videoUrl.toUri()
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return uri
        return runCatching {
            FileProvider.getUriForFile(context, context.applicationContext.packageName + ".provider", File(path))
        }.getOrDefault(uri)
    }

    private fun mimeOf(uri: Uri): String = when (uri.path?.substringAfterLast('.', "")?.lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        else -> "video/*"
    }

    private fun componentOf(packageName: String): ComponentName? = when (packageName) {
        MPV_PLAYER -> ComponentName(packageName, "$packageName.MPVActivity")
        MX_PLAYER, MX_PLAYER_FREE, MX_PLAYER_PRO -> ComponentName(packageName, "$packageName.ActivityScreen")
        VLC_PLAYER -> ComponentName(packageName, "$packageName.gui.video.VideoPlayerActivity")
        MPV_KT, MPV_KT_PREVIEW -> ComponentName(packageName, "live.mehiz.mpvkt.ui.player.PlayerActivity")
        MPV_REMOTE -> ComponentName(packageName, "$packageName.MainActivity")
        JUST_PLAYER -> ComponentName(packageName, "$packageName.PlayerActivity")
        NEXT_PLAYER -> ComponentName(packageName, "$packageName.feature.player.PlayerActivity")
        X_PLAYER -> ComponentName(packageName, "com.inshot.xplayer.activities.PlayerActivity")
        AMNIS -> ComponentName(packageName, "$packageName.gui.player.PlayerActivity")
        else -> null
    }
}
