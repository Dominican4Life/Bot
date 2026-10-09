package eu.kanade.tachiyomi.ui.player.cast

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.sumizuri.HlsRelay
import eu.kanade.tachiyomi.util.system.AppLog

/** KMK: Google Cast setup: the default media receiver, which plays plain video, HLS and DASH addresses */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * KMK: Casting the player's video to a Google Cast device (a Chromecast or a TV with Cast built in), like Sumizuri.
 * Only the video's address is sent, so a video that needs extra request headers may not play on the device.
 */
object PlayerCast {

    private val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID),
            )
            .build()
    }

    /** Null when Google Play services can't cast on this device */
    fun castContext(context: Context): CastContext? = runCatching {
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(context.applicationContext)
    }.onFailure { AppLog.add('W', "cast", "Casting isn't available: ${it.message}") }.getOrNull()

    fun connectedDevice(context: Context): String? =
        castContext(context)?.sessionManager?.currentCastSession?.takeIf { it.isConnected }?.castDevice?.friendlyName

    /** The Cast devices on the network, kept up to date while shown */
    @Composable
    fun rememberDevices(): State<List<MediaRouter.RouteInfo>> {
        val context = LocalContext.current
        val devices = remember { mutableStateOf(emptyList<MediaRouter.RouteInfo>()) }
        DisposableEffect(Unit) {
            castContext(context)
            val router = MediaRouter.getInstance(context)
            fun refresh() {
                devices.value = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            }
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            }
            router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            refresh()
            onDispose { router.removeCallback(callback) }
        }
        return devices
    }

    /**
     * Connects to [route] (unless already connected to it) and plays [video] there from [positionSeconds].
     * [onStarted] gets the device's name once the video was sent, or an error message.
     */
    fun cast(
        context: Context,
        route: MediaRouter.RouteInfo,
        video: Video,
        title: String,
        subtitle: String,
        positionSeconds: Int,
        onStarted: (Result<String>) -> Unit,
    ) {
        val cast = castContext(context) ?: return onStarted(Result.failure(IllegalStateException("Casting isn't available on this device")))
        val sessions = cast.sessionManager
        val current = sessions.currentCastSession
        if (current != null && current.isConnected && route.isSelected) {
            load(current, video, title, subtitle, positionSeconds, onStarted)
            return
        }
        val listener = object : SessionManagerListener<CastSession> {
            private fun done() = sessions.removeSessionManagerListener(this, CastSession::class.java)
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                done()
                load(session, video, title, subtitle, positionSeconds, onStarted)
            }
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                done()
                load(session, video, title, subtitle, positionSeconds, onStarted)
            }
            override fun onSessionStartFailed(session: CastSession, error: Int) {
                done()
                onStarted(Result.failure(IllegalStateException("Couldn't connect to ${route.name} (error $error)")))
            }
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionEnded(session: CastSession, error: Int) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        sessions.addSessionManagerListener(listener, CastSession::class.java)
        MediaRouter.getInstance(context).selectRoute(route)
    }

    fun stop(context: Context) {
        castContext(context)?.sessionManager?.endCurrentSession(true)
    }

    private fun contentType(url: String): String {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") || "m3u8" in url.lowercase() -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".ts") -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    private fun load(
        session: CastSession,
        video: Video,
        title: String,
        subtitle: String,
        positionSeconds: Int,
        onStarted: (Result<String>) -> Unit,
    ) {
        val client = session.remoteMediaClient
            ?: return onStarted(Result.failure(IllegalStateException("The device isn't ready to play")))
        // A Code editor source's stream goes through this device's relay, which the TV can't reach
        val url = HlsRelay.original(video.videoUrl)
        if (!url.startsWith("http")) {
            return onStarted(Result.failure(IllegalStateException("Only videos from the internet can be cast")))
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            putString(MediaMetadata.KEY_SUBTITLE, subtitle)
        }
        // Subtitles the device can show (WebVTT)
        val tracks = video.subtitleTracks
            .filter { it.url.startsWith("http") && Uri.parse(it.url).path.orEmpty().lowercase().endsWith(".vtt") }
            .mapIndexed { index, track ->
                MediaTrack.Builder(index + 1L, MediaTrack.TYPE_TEXT)
                    .setName(track.lang)
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(track.url)
                    .setContentType("text/vtt")
                    .build()
            }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType(url))
            .setMetadata(metadata)
            .apply { if (tracks.isNotEmpty()) setMediaTracks(tracks) }
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(positionSeconds * 1000L)
            .apply { if (tracks.isNotEmpty()) setActiveTrackIds(longArrayOf(1L)) }
            .build()
        val device = session.castDevice?.friendlyName ?: "the device"
        AppLog.add('I', "cast", "Casting $url to $device")
        client.load(request).setResultCallback { result ->
            if (result.status.isSuccess) {
                onStarted(Result.success(device))
            } else {
                AppLog.add('W', "cast", "$device couldn't play it: ${result.status.statusMessage ?: result.status.statusCode}")
                onStarted(
                    Result.failure(
                        IllegalStateException(
                            "$device couldn't play this video. It may need request headers the device can't send.",
                        ),
                    ),
                )
            }
        }
    }
}
