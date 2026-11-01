package eu.kanade.tachiyomi.ui.logs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.diagnostics.DiagTab
import eu.kanade.tachiyomi.ui.diagnostics.DiagnosticsScreen

/**
 * KMK: The Logs screen. KMK (r97): it became the Log tab of Diagnostics (`ui/diagnostics/DiagnosticsScreen.kt`,
 * body in `LogTab.kt`); this class stays as a thin wrapper so any call site that still pushes it lands there.
 */
class LogsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        LaunchedEffect(Unit) { navigator.replace(DiagnosticsScreen(DiagTab.LOG)) }
    }
}
