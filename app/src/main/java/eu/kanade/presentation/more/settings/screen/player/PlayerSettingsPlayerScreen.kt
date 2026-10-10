package eu.kanade.presentation.more.settings.screen.player

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.ui.player.AMNIS
import eu.kanade.tachiyomi.ui.player.JUST_PLAYER
import eu.kanade.tachiyomi.ui.player.MPV_EX
import eu.kanade.tachiyomi.ui.player.MPV_KT
import eu.kanade.tachiyomi.ui.player.MPV_KT_PREVIEW
import eu.kanade.tachiyomi.ui.player.MPV_PLAYER
import eu.kanade.tachiyomi.ui.player.MPV_REMOTE
import eu.kanade.tachiyomi.ui.player.MPV_REX
import eu.kanade.tachiyomi.ui.player.MPV_RX
import eu.kanade.tachiyomi.ui.player.MX_PLAYER
import eu.kanade.tachiyomi.ui.player.MX_PLAYER_FREE
import eu.kanade.tachiyomi.ui.player.MX_PLAYER_PRO
import eu.kanade.tachiyomi.ui.player.NEXT_PLAYER
import eu.kanade.tachiyomi.ui.player.PlayerOrientation
import eu.kanade.tachiyomi.ui.player.VLC_PLAYER
import eu.kanade.tachiyomi.ui.player.WEB_VIDEO_CASTER
import eu.kanade.tachiyomi.ui.player.X_PLAYER
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.utils.DefaultStreamPreferenceStore
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.NumberFormat

object PlayerSettingsPlayerScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AMR.strings.pref_player_internal

    @Composable
    override fun getPreferences(): List<Preference> {
        val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
        val decoderPreferences = remember { Injekt.get<DecoderPreferences>() }
        val basePreferences = remember { Injekt.get<BasePreferences>() }
        val context = LocalContext.current
        val deviceSupportsPip = remember {
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }

        return listOfNotNull(
            Preference.PreferenceItem.ListPreference(
                preference = playerPreferences.progressPreference(),
                title = stringResource(AMR.strings.pref_progress_mark_as_seen),
                entries = persistentMapOf(
                    1.00F to stringResource(AMR.strings.pref_progress_100),
                    0.95F to stringResource(AMR.strings.pref_progress_95),
                    0.90F to stringResource(AMR.strings.pref_progress_90),
                    0.85F to stringResource(AMR.strings.pref_progress_85),
                    0.80F to stringResource(AMR.strings.pref_progress_80),
                    0.75F to stringResource(AMR.strings.pref_progress_75),
                    0.70F to stringResource(AMR.strings.pref_progress_70),
                ),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = playerPreferences.preserveWatchingPosition(),
                title = stringResource(AMR.strings.pref_preserve_watching_position),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = playerPreferences.defaultPlayerOrientationType(),
                title = stringResource(AMR.strings.pref_category_player_orientation),
                entries = PlayerOrientation.entries.associateWith {
                    stringResource(it.titleRes)
                }.toPersistentMap(),
            ),
            getControlsGroup(playerPreferences = playerPreferences),
            getHosterGroup(playerPreferences = playerPreferences),
            getPerformanceGroup(
                playerPreferences = playerPreferences,
                decoderPreferences = decoderPreferences,
            ),
            getDisplayGroup(playerPreferences = playerPreferences),
            getIntroSkipGroup(playerPreferences = playerPreferences),
            getFillerSkipGroup(playerPreferences = playerPreferences),
            if (deviceSupportsPip) getPipGroup(playerPreferences = playerPreferences) else null,
            getExternalPlayerGroup(
                playerPreferences = playerPreferences,
                basePreferences = basePreferences,
            ),
        )
    }

    @Composable
    private fun getPerformanceGroup(
        playerPreferences: PlayerPreferences,
        decoderPreferences: DecoderPreferences,
    ): Preference.PreferenceGroup {
        val performanceProfile = decoderPreferences.performanceProfile()
        val preloadMode = playerPreferences.preloadMode()
        val networkAwareThrottling = playerPreferences.networkAwareThrottling()
        val selfHealingLinks = playerPreferences.selfHealingLinks()
        val intelligentBufferHandoff = playerPreferences.intelligentBufferHandoff()

        return Preference.PreferenceGroup(
            title = stringResource(AMR.strings.pref_category_player_performance),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = performanceProfile,
                    title = stringResource(AMR.strings.pref_performance_profile),
                    subtitle = stringResource(AMR.strings.pref_performance_profile_summary),
                    entries = eu.kanade.tachiyomi.ui.player.PlayerEfficiency.entries.associateWith {
                        stringResource(it.titleRes)
                    }.toPersistentMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preloadMode,
                    title = stringResource(AMR.strings.pref_preload_mode),
                    subtitle = stringResource(AMR.strings.pref_preload_mode_summary),
                    entries = eu.kanade.tachiyomi.ui.player.PreloadMode.entries.associateWith {
                        stringResource(it.titleRes)
                    }.toPersistentMap(),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = networkAwareThrottling,
                    title = stringResource(AMR.strings.pref_network_aware_throttling),
                    subtitle = stringResource(AMR.strings.pref_network_aware_throttling_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = selfHealingLinks,
                    title = stringResource(AMR.strings.pref_self_healing_links),
                    subtitle = stringResource(AMR.strings.pref_self_healing_links_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = intelligentBufferHandoff,
                    title = stringResource(AMR.strings.pref_intelligent_buffer_handoff),
                    subtitle = stringResource(AMR.strings.pref_intelligent_buffer_handoff_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getControlsGroup(playerPreferences: PlayerPreferences): Preference.PreferenceGroup {
        val allowGestures = playerPreferences.allowGestures()
        val showLoading = playerPreferences.showLoadingCircle()
        val showChapter = playerPreferences.showCurrentChapter()
        val rememberPlayerBrightness = playerPreferences.rememberPlayerBrightness()
        val rememberPlayerVolume = playerPreferences.rememberPlayerVolume()
        val rememberAspectRatio = playerPreferences.rememberAspectRatio()

        return Preference.PreferenceGroup(
            title = stringResource(AMR.strings.pref_category_controls),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = allowGestures,
                    title = stringResource(AMR.strings.pref_controls_allow_gestures_in_panels),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = showLoading,
                    title = stringResource(AMR.strings.pref_controls_show_loading),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = showChapter,
                    title = stringResource(AMR.strings.pref_controls_show_chapter_indicator),
                    subtitle = stringResource(AMR.strings.pref_controls_show_chapter_indicator_info),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = rememberPlayerBrightness,
                    title = stringResource(AMR.strings.pref_remember_brightness),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = rememberPlayerVolume,
                    title = stringResource(AMR.strings.pref_remember_volume),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = rememberAspectRatio,
                    title = stringResource(AMR.strings.pref_remember_aspect_ratio),
                ),
            ),
        )
    }

    @Composable
    private fun getHosterGroup(playerPreferences: PlayerPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val showFailure = playerPreferences.showFailedHosters()
        val showEmpty = playerPreferences.showEmptyHosters()
        val preferredQuality = playerPreferences.preferredQuality()
        val perAnimeDefaultStream = playerPreferences.perAnimeDefaultStream()
        val showDefaultStreamHighlight = playerPreferences.showDefaultStreamHighlight()
        val autoScrollDefaultStream = playerPreferences.autoScrollDefaultStream()
        val perAnimeDefaultStreamEnabled by perAnimeDefaultStream.collectAsState()
        val streamStore = remember(playerPreferences) { DefaultStreamPreferenceStore(playerPreferences) }
        val perAnimeData by playerPreferences.perAnimeDefaultStreamData().collectAsState()
        var clearGeneration by remember { mutableIntStateOf(0) }
        val savedCount = remember(perAnimeData, clearGeneration) {
            streamStore.savedAnimeCount()
        }

        return Preference.PreferenceGroup(
            title = stringResource(AMR.strings.pref_hosters),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = preferredQuality,
                    title = stringResource(AMR.strings.pref_preferred_quality),
                    entries = persistentMapOf(
                        "1080" to stringResource(AMR.strings.pref_high_quality),
                        "720" to "720p",
                        "480" to "480p",
                        "360" to "360p",
                    ),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = perAnimeDefaultStream,
                    title = stringResource(AMR.strings.pref_default_stream_per_anime),
                    subtitle = stringResource(AMR.strings.pref_default_stream_per_anime_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = showDefaultStreamHighlight,
                    title = stringResource(AMR.strings.pref_default_stream_highlight),
                    subtitle = stringResource(AMR.strings.pref_default_stream_highlight_summary),
                    enabled = perAnimeDefaultStreamEnabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = autoScrollDefaultStream,
                    title = stringResource(AMR.strings.pref_default_stream_auto_scroll),
                    subtitle = stringResource(AMR.strings.pref_default_stream_auto_scroll_summary),
                    enabled = perAnimeDefaultStreamEnabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = showFailure,
                    title = stringResource(AMR.strings.pref_hosters_show_failure),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = showEmpty,
                    title = stringResource(AMR.strings.pref_hosters_show_empty),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(AMR.strings.pref_default_stream_clear),
                    subtitle = stringResource(AMR.strings.pref_default_stream_clear_summary, savedCount),
                    onClick = {
                        streamStore.clearAll()
                        clearGeneration++
                        context.toast(AMR.strings.pref_default_stream_cleared)
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getDisplayGroup(playerPreferences: PlayerPreferences): Preference.PreferenceGroup {
        val fullScreen = playerPreferences.playerFullscreen()
        val hideControls = playerPreferences.hideControls()
        val displayVol = playerPreferences.displayVolPer()
        val showSystemBar = playerPreferences.showSystemStatusBar()
        val reduceMotion = playerPreferences.reduceMotion()
        val hideTime = playerPreferences.playerTimeToDisappear()

        val panelOpacityPref = playerPreferences.panelOpacity()
        val panelOpacity by panelOpacityPref.collectAsState()
        val numberFormat = remember { NumberFormat.getPercentInstance() }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = fullScreen,
                    title = stringResource(AMR.strings.pref_player_fullscreen),
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = hideControls,
                    title = stringResource(AMR.strings.pref_player_hide_controls),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = displayVol,
                    title = stringResource(AMR.strings.pref_controls_display_volume_percentage),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = showSystemBar,
                    title = stringResource(AMR.strings.pref_show_system_bar),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = reduceMotion,
                    title = stringResource(AMR.strings.pref_reduce_motion),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = hideTime,
                    title = stringResource(AMR.strings.pref_player_time_to_disappear),
                    entries = listOf(500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000).associateWith {
                        stringResource(AMR.strings.pref_player_time_to_disappear_summary, it)
                    }.toPersistentMap(),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = panelOpacity,
                    title = stringResource(AMR.strings.pref_panel_opacity),
                    subtitle = numberFormat.format(panelOpacity / 100f),
                    valueRange = 0..100,
                    onValueChanged = {
                        panelOpacityPref.set(it)
                        true
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getIntroSkipGroup(playerPreferences: PlayerPreferences): Preference.PreferenceGroup {
        val enableSkipIntro = playerPreferences.enableSkipIntro()
        val isIntroSkipEnabled by enableSkipIntro.collectAsState()

        val enableAutoAniSkip = playerPreferences.autoSkipIntro()
        val enableNetflixAniSkip = playerPreferences.enableNetflixStyleIntroSkip()
        val waitingTimeAniSkip = playerPreferences.waitingTimeIntroSkip()

        // AniSkip
        val enableAniSkip = playerPreferences.aniSkipEnabled()
        val disableAniSkipChapters = playerPreferences.disableAniSkipOnChapters()
        val isAniSkipEnabled by enableAniSkip.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(AMR.strings.pref_category_intro_skip),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = enableSkipIntro,
                    title = stringResource(AMR.strings.pref_enable_intro_skip),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = enableAutoAniSkip,
                    title = stringResource(AMR.strings.pref_enable_auto_skip_ani_skip),
                    enabled = isIntroSkipEnabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = enableNetflixAniSkip,
                    title = stringResource(AMR.strings.pref_enable_netflix_style_aniskip),
                    enabled = isIntroSkipEnabled,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = waitingTimeAniSkip,
                    title = stringResource(AMR.strings.pref_waiting_time_aniskip),
                    entries = persistentMapOf(
                        5 to stringResource(AMR.strings.pref_waiting_time_aniskip_5),
                        6 to stringResource(AMR.strings.pref_waiting_time_aniskip_6),
                        7 to stringResource(AMR.strings.pref_waiting_time_aniskip_7),
                        8 to stringResource(AMR.strings.pref_waiting_time_aniskip_8),
                        9 to stringResource(AMR.strings.pref_waiting_time_aniskip_9),
                        10 to stringResource(AMR.strings.pref_waiting_time_aniskip_10),
                    ),
                    enabled = isIntroSkipEnabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = enableAniSkip,
                    title = stringResource(AMR.strings.pref_enable_aniskip),
                    enabled = isIntroSkipEnabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = disableAniSkipChapters,
                    title = stringResource(AMR.strings.pref_disable_aniskip_chapter),
                    enabled = isIntroSkipEnabled && isAniSkipEnabled,
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(AMR.strings.pref_category_player_aniskip_info),
                ),
            ),
        )
    }

    @Composable
    private fun getFillerSkipGroup(playerPreferences: PlayerPreferences): Preference.PreferenceGroup {
        val skipFillerEpisodes = playerPreferences.skipFillerEpisodes()

        return Preference.PreferenceGroup(
            title = stringResource(AMR.strings.pref_category_filler),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = skipFillerEpisodes,
                    title = stringResource(AMR.strings.pref_skip_filler_episodes),
                    subtitle = stringResource(AMR.strings.pref_skip_filler_episodes_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getPipGroup(playerPreferences: PlayerPreferences): Preference.PreferenceGroup {
        val enablePip = playerPreferences.enablePip()
        val pipEpisodeToasts = playerPreferences.pipEpisodeToasts()
        val pipOnExit = playerPreferences.pipOnExit()
        val pipReplaceWithPrevious = playerPreferences.pipReplaceWithPrevious()

        val isPipEnabled by enablePip.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(AMR.strings.pref_category_pip),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = enablePip,
                    title = stringResource(AMR.strings.pref_enable_pip),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = pipEpisodeToasts,
                    title = stringResource(AMR.strings.pref_pip_episode_toasts),
                    enabled = isPipEnabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = pipOnExit,
                    title = stringResource(AMR.strings.pref_pip_on_exit),
                    enabled = isPipEnabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = pipReplaceWithPrevious,
                    title = stringResource(AMR.strings.pref_pip_replace_with_previous),
                    enabled = isPipEnabled,
                ),
            ),
        )
    }

    @Composable
    private fun getExternalPlayerGroup(
        playerPreferences: PlayerPreferences,
        basePreferences: BasePreferences,
    ): Preference.PreferenceGroup {
        val alwaysUseExternalPlayer = playerPreferences.alwaysUseExternalPlayer()
        val externalPlayerPreference = playerPreferences.externalPlayerPreference()

        val pm = LocalContext.current.packageManager
        val installedPackages = pm.getInstalledPackages(0)
        val supportedPlayers = installedPackages.filter { it.packageName in externalPlayers }

        val packageNames = supportedPlayers.map { it.packageName }
        val packageNamesReadable = supportedPlayers
            .map { pm.getApplicationLabel(it.applicationInfo!!).toString() }

        val packageNamesMap: Map<String, String> =
            packageNames.zip(packageNamesReadable)
                .toMap()

        return Preference.PreferenceGroup(
            title = stringResource(AMR.strings.pref_category_external_player),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = alwaysUseExternalPlayer,
                    title = stringResource(AMR.strings.pref_always_use_external_player),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = externalPlayerPreference,
                    title = stringResource(AMR.strings.pref_external_player_preference),
                    entries = (mapOf("" to "None") + packageNamesMap).toPersistentMap(),
                ),
            ),
        )
    }
}

val externalPlayers = listOf(
    MPV_PLAYER,
    MX_PLAYER,
    MX_PLAYER_FREE,
    MX_PLAYER_PRO,
    VLC_PLAYER,
    MPV_KT,
    MPV_KT_PREVIEW,
    MPV_REMOTE,
    JUST_PLAYER,
    NEXT_PLAYER,
    X_PLAYER,
    WEB_VIDEO_CASTER,
    AMNIS,
    MPV_REX,
    MPV_RX,
    MPV_EX,
)
