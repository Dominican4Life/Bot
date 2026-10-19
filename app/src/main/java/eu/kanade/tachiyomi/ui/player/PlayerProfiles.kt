package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.utils.Anime4KManager

// KMK -->
/**
 * The video settings the player actually uses. A performance profile overrides the user's own decoder settings
 * while it's picked; [PlayerEfficiency.Automatic] ("Custom") uses them as they are. Nothing here is saved.
 */
data class EffectiveVideoSettings(
    val hardwareDecoding: Boolean,
    val highQualityScaling: Boolean,
    val smoothMotion: Boolean,
    /** The highest Anime4K quality the profile allows, or null when the profile keeps Anime4K off */
    val anime4kQualityCap: Anime4KManager.Quality?,
    /** Multiplier for the demuxer cache size */
    val cacheScale: Float,
)

fun DecoderPreferences.effectiveVideoSettings(): EffectiveVideoSettings {
    return when (performanceProfile().get()) {
        PlayerEfficiency.Automatic -> EffectiveVideoSettings(
            hardwareDecoding = tryHWDecoding().get(),
            highQualityScaling = highQualityScaling().get(),
            smoothMotion = smoothMotion().get(),
            anime4kQualityCap = Anime4KManager.Quality.HIGH,
            cacheScale = 1f,
        )
        // Best quality: sharpest scaling and Anime4K as the user set it. Smooth motion changes how anime looks,
        // so it stays the user's choice.
        PlayerEfficiency.MaxPerformance -> EffectiveVideoSettings(
            hardwareDecoding = tryHWDecoding().get(),
            highQualityScaling = true,
            smoothMotion = smoothMotion().get(),
            anime4kQualityCap = Anime4KManager.Quality.HIGH,
            cacheScale = 2f,
        )
        PlayerEfficiency.Balanced -> EffectiveVideoSettings(
            hardwareDecoding = true,
            highQualityScaling = true,
            smoothMotion = false,
            anime4kQualityCap = Anime4KManager.Quality.FAST,
            cacheScale = 1f,
        )
        PlayerEfficiency.PowerSaver -> EffectiveVideoSettings(
            hardwareDecoding = true,
            highQualityScaling = false,
            smoothMotion = false,
            anime4kQualityCap = null,
            cacheScale = 0.5f,
        )
    }
}

/**
 * The Anime4K mode and quality to use, after the performance profile and [sessionQualityCap] (lowered for this
 * session by adaptive shader scaling) have had their say. Null when Anime4K should be off.
 */
fun DecoderPreferences.effectiveAnime4K(
    sessionQualityCap: Anime4KManager.Quality? = null,
    sessionOff: Boolean = false,
): Pair<Anime4KManager.Mode, Anime4KManager.Quality>? {
    if (sessionOff || !enableAnime4K().get()) return null
    val mode = runCatching { Anime4KManager.Mode.valueOf(anime4kMode().get()) }.getOrDefault(Anime4KManager.Mode.OFF)
    if (mode == Anime4KManager.Mode.OFF) return null
    val profileCap = effectiveVideoSettings().anime4kQualityCap ?: return null
    val quality = runCatching { Anime4KManager.Quality.valueOf(anime4kQuality().get()) }
        .getOrDefault(Anime4KManager.Quality.BALANCED)
    val capped = listOfNotNull(quality, profileCap, sessionQualityCap).minBy { it.ordinal }
    return mode to capped
}
// KMK <--
