package eu.kanade.tachiyomi.util

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * KMK: "Pause between titles": a random wait between titles, so sites don't bot-check a big library update or
 * migration. Saved as a range of seconds ("1-3"), with its own on/off switch.
 */
object PauseBetweenTitles {
    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 15
    const val DEFAULT = "1-3"

    /** The saved range, kept within 1–15 seconds, or null when it's empty or unreadable (like the old "0-0") */
    fun range(value: String): IntRange? {
        val parts = value.split('-')
        if (parts.size != 2) return null
        val from = parts[0].trim().toIntOrNull() ?: return null
        val to = parts[1].trim().toIntOrNull() ?: return null
        if (to <= 0) return null
        val low = from.coerceIn(MIN_SECONDS, MAX_SECONDS)
        val high = to.coerceIn(low, MAX_SECONDS)
        return low..high
    }

    fun format(range: IntRange): String = "${range.first}-${range.last}"

    /** The range to wait, or null when the switch is off or there's no range */
    fun active(enabled: Boolean, value: String): IntRange? = if (enabled) range(value) else null

    /** Waits a random time in the range, so a site doesn't see requests at a fixed, bot-like pace */
    suspend fun pause(enabled: Boolean, value: String) {
        val range = active(enabled, value) ?: return
        delay(Random.nextLong(range.first * 1000L, range.last * 1000L + 1))
    }
}
