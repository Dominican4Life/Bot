package eu.kanade.presentation.components

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * KMK: Which covers are wide, for the "Panorama: wide" style, where a wide cover takes two places in the row.
 *
 * Grids read [ids] while composing and give their span lambdas a plain copy, so the spans never read changing state
 * while the grid is being measured (that made a grid place an item twice and crash).
 */
@Stable
class WideCovers {
    var ids: Set<Long> by mutableStateOf(emptySet())
        private set

    /** Records the height / width of a loaded cover */
    fun report(id: Long, ratio: Float) {
        if (ratio <= WIDE_RATIO && id !in ids) ids = ids + id
    }

    companion object {
        /** Same limit as the covers' own switch to panorama */
        const val WIDE_RATIO = 1.0f
    }
}

@Composable
fun rememberWideCovers(): WideCovers = remember { WideCovers() }

/** Two places for a wide cover when [enabled], one otherwise */
fun LazyGridItemSpanScope.wideSpan(enabled: Boolean, wide: Set<Long>, id: Long?): GridItemSpan =
    if (enabled && id != null && id in wide) GridItemSpan(minOf(2, maxLineSpan)) else GridItemSpan(1)
