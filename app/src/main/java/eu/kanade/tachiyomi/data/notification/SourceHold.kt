package eu.kanade.tachiyomi.data.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * KMK: Sources waiting for a bot check to be passed in WebView. No covers are asked from them until they're
 * released, so a blocked site isn't hammered while it waits.
 */
object SourceHold {
    private val _held = MutableStateFlow<Set<Long>>(emptySet())
    val held: StateFlow<Set<Long>> = _held.asStateFlow()

    fun isHeld(sourceId: Long): Boolean = sourceId in _held.value

    fun hold(sourceId: Long) = _held.update { it + sourceId }

    fun release(sourceId: Long) = _held.update { it - sourceId }

    fun releaseAll() {
        _held.value = emptySet()
    }
}
