package tachiyomi.presentation.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.LifecycleCoroutineScope
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.updates.interactor.GetUpdates

class WidgetManager(
    private val getUpdates: GetUpdates,
    private val securityPreferences: SecurityPreferences,
) {

    /**
     * KMK (r95): the Updates query used to run for the life of the process on every chapter/manga write, whether or
     * not a widget was on the home screen, and it wasn't paused during restores and pair syncs (one full Updates
     * query per merged title, about 32 ms each). Now it runs only while a widget is placed ([placementChanged] and
     * [recheck] re-test that) and while [paused] is false.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun Context.init(
        scope: LifecycleCoroutineScope,
        paused: Flow<Boolean> = flowOf(false),
        recheck: Flow<Unit> = emptyFlow(),
    ) {
        val updates = combine(
            getUpdates.subscribe(read = false, after = BaseUpdatesGridGlanceWidget.DateLimit.toEpochMilli()),
            securityPreferences.useAuthenticator().changes(),
            transform = { a, b -> a to b },
        )
            .distinctUntilChanged { old, new ->
                old.second == new.second &&
                    old.first.map { it.chapterId }.toSet() == new.first.map { it.chapterId }.toSet()
            }

        // placementChanged replays once, so this runs at start, then on every re-check or placement change
        val placed = merge(recheck, placementChanged)
            .map { anyWidgetPlaced() }
            .distinctUntilChanged()

        combine(paused, placed) { p, isPlaced -> !p && isPlaced }
            .distinctUntilChanged()
            .onEach { on -> logcat(tag = "widget") { "Updates subscription ${if (on) "on" else "off"}" } }
            .flatMapLatest { on -> if (on) updates else emptyFlow() }
            .onEach {
                try {
                    UpdatesGridGlanceWidget().updateAll(this)
                    UpdatesGridCoverScreenGlanceWidget().updateAll(this)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to update widget" }
                }
            }
            .flowOn(Dispatchers.Default)
            .launchIn(scope)
    }

    private suspend fun Context.anyWidgetPlaced(): Boolean = try {
        val manager = GlanceAppWidgetManager(this)
        manager.getGlanceIds(UpdatesGridGlanceWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(UpdatesGridCoverScreenGlanceWidget::class.java).isNotEmpty()
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Couldn't tell whether an Updates widget is placed; assuming one is" }
        true
    }

    companion object {
        /** Emitted by the widget receivers when a widget is added or the last one removed */
        val placementChanged = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1).apply { tryEmit(Unit) }
    }
}
