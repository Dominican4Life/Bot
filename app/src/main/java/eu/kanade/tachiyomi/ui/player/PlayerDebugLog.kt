package eu.kanade.tachiyomi.ui.player

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * KMK: Keeps the last few hundred player and mpv messages so a playback problem can be copied and reported.
 */
object PlayerDebugLog {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(tag: String, message: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast("${timeFormat.format(Date())} [$tag] $message")
    }

    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
