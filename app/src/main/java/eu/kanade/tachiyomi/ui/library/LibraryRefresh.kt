package eu.kanade.tachiyomi.ui.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

/**
 * KMK: Asks the manga and anime libraries to work out their covers' badges again (download and unread counts,
 * language), for when something they depend on changed without them hearing of it, like a restore finishing.
 * Before, the badges stayed wrong until a badge setting was switched off and on.
 */
object LibraryRefresh {
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick.asStateFlow()

    fun request() = _tick.update { it + 1 }

    /** True while a restore runs: the libraries leave out work that would be redone for every restored title */
    val restoring = MutableStateFlow(false)

    /**
     * [flow], paused while a restore runs and picked up again once it's done. The libraries read their whole list
     * again after every change to the database; during a restore that was once per restored title, and most of
     * the restore's time.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun <T> pausedWhileRestoring(flow: Flow<T>): Flow<T> =
        restoring.flatMapLatest { if (it) emptyFlow() else flow }
}
