package eu.kanade.tachiyomi.ui.browse.extension.details

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.forEach
import androidx.preference.getOnBindEditTextListener
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.data.preference.SharedPreferencesDataStore
import eu.kanade.tachiyomi.animesource.sourcePreferences as animeSourcePreferences
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText.Companion.setIncognito
import exh.source.EnhancedHttpSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourcePreferencesScreen(
    val sourceId: Long,
    // KMK: settings of an anime source
    val anime: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                AppBar(
                    title = if (anime) {
                        Injekt.get<AnimeSourceManager>().get(sourceId)?.name.orEmpty()
                    } else {
                        Injekt.get<SourceManager>().getOrStub(sourceId).toString()
                    },
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            FragmentContainer(
                fragmentManager = (context as FragmentActivity).supportFragmentManager,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                add(it, SourcePreferencesFragment.getInstance(sourceId, anime), null)
            }
        }
    }

    /**
     * From https://stackoverflow.com/questions/60520145/fragment-container-in-jetpack-compose/70817794#70817794
     */
    @Composable
    private fun FragmentContainer(
        fragmentManager: FragmentManager,
        modifier: Modifier = Modifier,
        commit: FragmentTransaction.(containerId: Int) -> Unit,
    ) {
        val containerId by rememberSaveable {
            mutableIntStateOf(View.generateViewId())
        }
        var initialized by rememberSaveable { mutableStateOf(false) }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                FragmentContainerView(context)
                    .apply { id = containerId }
            },
            update = { view ->
                if (!initialized) {
                    fragmentManager.commit { commit(view.id) }
                    initialized = true
                } else {
                    fragmentManager.onContainerAvailable(view)
                }
            },
        )
    }

    /** Access to package-private method in FragmentManager through reflection */
    private fun FragmentManager.onContainerAvailable(view: FragmentContainerView) {
        val method = FragmentManager::class.java.getDeclaredMethod(
            "onContainerAvailable",
            FragmentContainerView::class.java,
        )
        method.isAccessible = true
        method.invoke(this, view)
    }
}

/**
 * KMK: A source's settings inside another screen, such as the Settings panel of the Browse screen: the same
 * settings the full page shows. The settings are removed again when this leaves the screen.
 */
@Composable
fun SourceSettingsContent(
    sourceId: Long,
    modifier: Modifier = Modifier,
    anime: Boolean = false,
) {
    val context = LocalContext.current
    // KMK (r135): glass switches in the glass panel
    SourcePreferencesFragment.glassAccent = if (eu.kanade.presentation.components.LocalGlassPanel.current) {
        tachiyomi.presentation.core.components.LocalGlassAccent.current.toArgb()
    } else {
        null
    }
    val fragmentManager = (context as FragmentActivity).supportFragmentManager
    val containerId = remember { View.generateViewId() }
    val tag = remember(sourceId, anime) { "source_settings_panel_${if (anime) "anime" else "manga"}_$sourceId" }
    // Added once, without recomposing
    val added = remember { booleanArrayOf(false) }
    AndroidView(
        modifier = modifier,
        factory = { FragmentContainerView(it).apply { id = containerId } },
        update = { view ->
            if (!added[0]) {
                added[0] = true
                fragmentManager.findFragmentByTag(tag)?.let { old ->
                    fragmentManager.commit(allowStateLoss = true) { remove(old) }
                }
                fragmentManager.commit(allowStateLoss = true) {
                    add(view.id, SourcePreferencesFragment.getInstance(sourceId, anime), tag)
                }
            }
        },
    )
    DisposableEffect(tag) {
        onDispose {
            fragmentManager.findFragmentByTag(tag)?.let { fragment ->
                if (!fragmentManager.isStateSaved) {
                    fragmentManager.commit(allowStateLoss = true) { remove(fragment) }
                }
            }
        }
    }
}

class SourcePreferencesFragment : PreferenceFragmentCompat() {

    override fun getContext(): Context? {
        val superCtx = super.getContext() ?: return null
        val tv = TypedValue()
        superCtx.theme.resolveAttribute(R.attr.preferenceTheme, tv, true)
        return ContextThemeWrapper(superCtx, tv.resourceId)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = populateScreen()
    }

    private fun populateScreen(): PreferenceScreen {
        val sourceId = requireArguments().getLong(SOURCE_ID)
        // KMK -->
        if (requireArguments().getBoolean(ANIME)) {
            val animeScreen = preferenceManager.createPreferenceScreen(requireContext())
            val animeSource = Injekt.get<AnimeSourceManager>().get(sourceId) as? ConfigurableAnimeSource
                ?: return animeScreen
            preferenceManager.preferenceDataStore = SharedPreferencesDataStore(animeSource.animeSourcePreferences())
            animeSource.setupPreferenceScreen(animeScreen)
            animeScreen.tidyUp()
            return animeScreen
        }
        // KMK <--
        // SY -->
        val source = Injekt.get<SourceManager>()
            .getOrStub(sourceId)
            .let { source ->
                if (source is EnhancedHttpSource) {
                    if (source.enhancedSource is ConfigurableSource) {
                        source.source()
                    } else {
                        source.originalSource
                    }
                } else {
                    source
                }
            }
        // SY <--
        val sourceScreen = preferenceManager.createPreferenceScreen(requireContext())

        if (source is ConfigurableSource) {
            val dataStore = SharedPreferencesDataStore(source.sourcePreferences())
            preferenceManager.preferenceDataStore = dataStore

            source.setupPreferenceScreen(sourceScreen)
            sourceScreen.tidyUp()
        }

        return sourceScreen
    }

    // KMK: shared by manga and anime source settings; also goes into categories
    private fun PreferenceGroup.tidyUp() {
        run {
            forEach { pref ->
                if (pref is PreferenceGroup) pref.tidyUp()
                pref.isIconSpaceReserved = false
                pref.isSingleLineTitle = false
                // KMK (r135): setting names in Title Case, like the rest of the app
                pref.title = pref.title?.toString()?.let(::titleCase)
                if (pref is DialogPreference && pref.dialogTitle.isNullOrEmpty()) {
                    pref.dialogTitle = pref.title
                }

                // Apply incognito IME for EditTextPreference
                if (pref is EditTextPreference) {
                    val setListener = pref.getOnBindEditTextListener()
                    pref.setOnBindEditTextListener {
                        setListener?.onBindEditText(it)
                        it.setIncognito(lifecycleScope)
                    }
                }
            }
        }
    }

    // KMK (r136): in the glass panel the switches are painted on every bind (the r135 attach listener didn't take)
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): androidx.recyclerview.widget.RecyclerView.Adapter<*> {
        val accent = glassAccent ?: return super.onCreateAdapter(preferenceScreen)
        return object : androidx.preference.PreferenceGroupAdapter(preferenceScreen) {
            override fun onBindViewHolder(holder: androidx.preference.PreferenceViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                styleSwitches(holder.itemView, accent)
            }
        }
    }

    private fun styleSwitches(view: View, accent: Int) {
        if (view is androidx.appcompat.widget.SwitchCompat) {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            view.thumbTintList = android.content.res.ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFE6E8EE.toInt()))
            view.trackTintList = android.content.res.ColorStateList(states, intArrayOf(accent, 0x40FFFFFF))
            (view as? com.google.android.material.materialswitch.MaterialSwitch)?.apply {
                trackDecorationTintList = android.content.res.ColorStateList.valueOf(0x00000000)
                thumbIconTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) styleSwitches(view.getChildAt(i), accent)
        }
    }

    companion object {
        /** KMK (r135): the glass accent to paint the switches with (set by the glass panel), null = the theme's */
        @Volatile
        var glassAccent: Int? = null

        /** KMK (r135): "Pause between tag pages" -> "Pause Between Tag Pages" (small words stay small) */
        fun titleCase(text: String): String {
            val small = setOf("a", "an", "and", "or", "of", "the", "to", "in", "on", "for", "by", "at", "vs")
            return text.split(" ").mapIndexed { i, word ->
                val bare = word.trimStart { !it.isLetter() }
                if (bare.isEmpty() || (i > 0 && bare.lowercase() in small) || !bare[0].isLowerCase()) {
                    word
                } else {
                    word.substring(0, word.length - bare.length) + bare.replaceFirstChar { it.uppercase() }
                }
            }.joinToString(" ")
        }

        private const val SOURCE_ID = "source_id"
        private const val ANIME = "anime"

        fun getInstance(sourceId: Long, anime: Boolean = false): SourcePreferencesFragment {
            return SourcePreferencesFragment().apply {
                arguments = Bundle().apply {
                    putLong(SOURCE_ID, sourceId)
                    putBoolean(ANIME, anime)
                }
            }
        }
    }
}
