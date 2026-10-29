package eu.kanade.presentation.more.settings.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.ui.player.Debanding
import eu.kanade.tachiyomi.ui.player.PlayerEfficiency
import eu.kanade.tachiyomi.ui.player.effectiveVideoSettings
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.ank.AMR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object PlayerSettingsDecoderScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AMR.strings.pref_player_decoder

    @Composable
    override fun getPreferences(): List<Preference> {
        val decoderPreferences = remember { Injekt.get<DecoderPreferences>() }

        val tryHw = decoderPreferences.tryHWDecoding()
        val useGpuNext = decoderPreferences.gpuNext()
        val debanding = decoderPreferences.videoDebanding()
        val yuv420p = decoderPreferences.useYUV420P()
        val highQualityScaling = decoderPreferences.highQualityScaling()
        val smoothMotion = decoderPreferences.smoothMotion()
        val smoothMotionValue by smoothMotion.collectAsState()
        val interpolationMode = decoderPreferences.interpolationMode()
        val interpolationFPSLimit = decoderPreferences.interpolationFPSLimit()
        val interpolationFPSLimitValue by interpolationFPSLimit.collectAsState()
        val adaptiveScaling = decoderPreferences.adaptiveShaderScaling()

        // KMK -->
        // A performance profile other than Custom overrides some of these switches (PlayerProfiles.kt). Show the
        // value the player really uses, greyed, with a note, instead of the user's own (unused) value.
        val profile by decoderPreferences.performanceProfile().collectAsState()
        val tryHwValue by tryHw.collectAsState()
        val highQualityScalingValue by highQualityScaling.collectAsState()
        val effective = remember(profile, tryHwValue, highQualityScalingValue, smoothMotionValue) {
            decoderPreferences.effectiveVideoSettings()
        }
        // Must match what effectiveVideoSettings() overrides for each profile
        val forcesHwAndSmoothMotion = profile == PlayerEfficiency.Balanced || profile == PlayerEfficiency.PowerSaver
        val hwLocked = forcesHwAndSmoothMotion
        val highQualityScalingLocked = profile != PlayerEfficiency.Automatic
        val smoothMotionLocked = forcesHwAndSmoothMotion
        val lockedNote = stringResource(KMR.strings.pref_decoder_set_by_profile, stringResource(profile.titleRes))
        val hwShown = remember(effective.hardwareDecoding) {
            InMemoryPreferenceStore.InMemoryPreference(tryHw.key(), effective.hardwareDecoding, false)
        }
        val highQualityScalingShown = remember(effective.highQualityScaling) {
            InMemoryPreferenceStore.InMemoryPreference(
                highQualityScaling.key(),
                effective.highQualityScaling,
                false,
            )
        }
        val smoothMotionShown = remember(effective.smoothMotion) {
            InMemoryPreferenceStore.InMemoryPreference(smoothMotion.key(), effective.smoothMotion, false)
        }
        val highQualityScalingSummary = stringResource(AMR.strings.pref_high_quality_scaling_summary)
        val smoothMotionSummary = stringResource(AMR.strings.pref_interpolation_summary)
        // KMK <--

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                // KMK -->
                preference = if (hwLocked) hwShown else tryHw,
                // KMK <--
                title = stringResource(AMR.strings.pref_try_hw),
                // KMK -->
                subtitle = if (hwLocked) lockedNote else null,
                onValueChanged = { !hwLocked },
                locked = hwLocked,
                // KMK <--
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = useGpuNext,
                title = stringResource(AMR.strings.pref_gpu_next_title),
                subtitle = stringResource(AMR.strings.pref_gpu_next_subtitle),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = debanding,
                title = stringResource(AMR.strings.pref_debanding_title),
                entries = Debanding.entries.associateWith {
                    it.name
                }.toImmutableMap(),
            ),
            Preference.PreferenceItem.SwitchPreference(
                // KMK -->
                preference = if (highQualityScalingLocked) highQualityScalingShown else highQualityScaling,
                // KMK <--
                title = stringResource(AMR.strings.pref_high_quality_scaling),
                // KMK -->
                subtitle = if (highQualityScalingLocked) {
                    "$highQualityScalingSummary\n$lockedNote"
                } else {
                    highQualityScalingSummary
                },
                onValueChanged = { !highQualityScalingLocked },
                locked = highQualityScalingLocked,
                // KMK <--
            ),
            Preference.PreferenceItem.SwitchPreference(
                // KMK -->
                preference = if (smoothMotionLocked) smoothMotionShown else smoothMotion,
                // KMK <--
                title = stringResource(AMR.strings.pref_interpolation),
                // KMK -->
                subtitle = if (smoothMotionLocked) "$smoothMotionSummary\n$lockedNote" else smoothMotionSummary,
                onValueChanged = { !smoothMotionLocked },
                locked = smoothMotionLocked,
                // KMK <--
            ),
            Preference.PreferenceItem.ListPreference(
                preference = interpolationMode,
                title = "Smooth Motion Mode",
                subtitle = "Select the algorithm used for smoothness",
                entries = eu.kanade.tachiyomi.ui.player.settings.InterpolationMode.entries.associateWith {
                    it.title
                }.toImmutableMap(),
                enabled = effective.smoothMotion, // KMK: the value in use (a profile can force it off)
            ),
            Preference.PreferenceItem.SliderPreference(
                value = interpolationFPSLimitValue,
                valueRange = 0..144,
                title = "Smooth Motion FPS Limit",
                subtitle = if (interpolationFPSLimitValue == 0) "Auto (Matches Display)" else "$interpolationFPSLimitValue FPS",
                enabled = effective.smoothMotion, // KMK: the value in use (a profile can force it off)
                onValueChanged = {
                    interpolationFPSLimit.set(it)
                    true
                },
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = adaptiveScaling,
                title = stringResource(AMR.strings.pref_ai_performance_intelligence),
                subtitle = stringResource(KMR.strings.pref_adaptive_shader_scaling_details), // KMK
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = yuv420p,
                title = stringResource(AMR.strings.pref_use_yuv420p_title),
                subtitle = stringResource(AMR.strings.pref_use_yuv420p_subtitle),
            ),
        )
    }
}
