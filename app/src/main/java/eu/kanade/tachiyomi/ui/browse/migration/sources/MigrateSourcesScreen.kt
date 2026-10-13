package eu.kanade.tachiyomi.ui.browse.migration.sources

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.anime.AnimeMigratePane
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * KMK: Migrate by source, opened from the Library menu (it used to be a tab in Browse). [anime]: the anime
 * version, when the library is showing anime.
 */
class MigrateSourcesScreen(private val anime: Boolean = false) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val tab = migrateSourceTab()
        val snackbarHostState = remember { SnackbarHostState() }
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(tab.titleRes),
                    navigateUp = navigator::pop,
                    actions = { AppBarActions(tab.actions) },
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { contentPadding ->
            if (anime) AnimeMigratePane(contentPadding) else tab.content(contentPadding, snackbarHostState)
        }
    }
}
