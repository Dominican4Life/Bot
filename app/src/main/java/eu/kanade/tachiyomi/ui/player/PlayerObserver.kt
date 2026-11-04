package eu.kanade.tachiyomi.ui.player

import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import logcat.LogPriority
import logcat.logcat

// ANZ -->
class PlayerObserver(val activity: PlayerActivity) :
    MPV.EventObserver,
    MPV.LogObserver {

    override fun eventProperty(property: String) {
        activity.runOnUiThread { activity.onObserverEvent(property) }
    }

    override fun eventProperty(property: String, value: Long) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: MPVNode) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun event(eventId: Int, data: MPVNode) {
        activity.event(eventId, data)
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        // KMK: the answer of the quality check on a downloaded file isn't an error (HlsVariants.localQualities)
        if (prefix == "ffmpeg" && eu.kanade.tachiyomi.ui.player.loader.HlsVariants.isProbeOutput(text)) return
        if (level == MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR) {
            if (text.startsWith(TRACK_LOAD_FAILURE)) {
                val url = text.removePrefix(TRACK_LOAD_FAILURE).substringBeforeLast(".")
                activity.onTrackLoadedFailure(url)
            }
        }

        if (prefix == "ffmpeg" && text.startsWith("Failed to open an HTTP connection:")) {
            httpError = text.removePrefix("Failed to open an HTTP connection: ")
        }

        // KMK (r119) -->
        // "Both surface and native_window are NULL" is expected with mediacodec-copy (mpv hands the decoder no
        // surface on purpose): a Debug line, not an Error
        val expectedCopyNote = SURFACE_NULL.containsMatchIn(text)
        // KMK <--

        val logPriority = when {
            expectedCopyNote -> LogPriority.DEBUG // KMK (r119)
            level == MPV.mpvLogLevel.MPV_LOG_LEVEL_FATAL ||
                level == MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR -> LogPriority.ERROR
            level == MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN -> LogPriority.WARN
            level == MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO -> LogPriority.INFO
            else -> LogPriority.VERBOSE
        }
        logcat("mpv/$prefix", logPriority) { text }
        // KMK -->
        if (expectedCopyNote) {
            // KMK (r119)
            PlayerDebugLog.add("mpv/$prefix", "${text.trimEnd()} (normal with copy decoding)")
        } else if (level <= 50) {
            PlayerDebugLog.add("mpv/$prefix", text.trimEnd())
        }
        // KMK <--
    }

    var httpError: String? = null

    companion object {
        const val TRACK_LOAD_FAILURE = "Can not open external file "

        // KMK (r119) -->
        private val SURFACE_NULL = Regex(
            """(?i)(h264|hevc|h265|vp8|vp9|av1|mpeg2|mpeg4)_mediacodec: Both surface and native_window are NULL""",
        )
        // KMK <--
    }
}
// ANZ <--
