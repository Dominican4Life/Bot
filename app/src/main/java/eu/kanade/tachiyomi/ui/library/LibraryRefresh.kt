package eu.kanade.tachiyomi.ui.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}
