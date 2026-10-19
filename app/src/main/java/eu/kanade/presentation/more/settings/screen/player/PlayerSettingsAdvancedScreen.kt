package eu.kanade.presentation.more.settings.screen.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object PlayerSettingsAdvancedScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AMR.strings.pref_player_advanced

    @Composable
    override fun getPreferences(): List<Preference> {
        val advancedPlayerPreferences = remember { Injekt.get<AdvancedPlayerPreferences>() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        val enableScripts = advancedPlayerPreferences.mpvScripts()
        val mpvConf = advancedPlayerPreferences.mpvConf()
        val mpvInput = advancedPlayerPreferences.mpvInput()

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                title = stringResource(AMR.strings.pref_mpv_scripts),
                subtitle = stringResource(AMR.strings.pref_mpv_scripts_summary),
                preference = enableScripts,
                onValueChanged = {
                    // Ask for external storage permission
                    if (it) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.fromParts("package", context.packageName, null)
                            context.startActivity(intent)
                        }
                    }
                    true
                },
            ),
            Preference.PreferenceItem.MPVConfPreference(
                preference = mpvConf,
                title = stringResource(AMR.strings.pref_mpv_conf),
                fileName = "mpv.conf",
                scope = scope,
                context = context,
            ),
            Preference.PreferenceItem.MPVConfPreference(
                preference = mpvInput,
                title = stringResource(AMR.strings.pref_mpv_input),
                fileName = "input.conf",
                scope = scope,
                context = context,
            ),
        )
    }
}
