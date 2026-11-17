package com.fileapex.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow

/** How many file manager screens are on screen; the desktop window widens while at least one is. */
object FileManagerPresence {
    private const val CLOSE_QUIET_MILLIS = 60L
    private val open = MutableStateFlow(0)

    val isActive: Boolean get() = open.value > 0
    val active: Flow<Boolean> get() = open.map { it > 0 }.distinctUntilChanged()

    /** Opening is reported at once, closing after a short quiet time that only absorbs a screen being rebuilt (leave then enter within a frame or two). */
    @OptIn(FlowPreview::class)
    val settled: Flow<Boolean> get() = active.debounce { if (it) 0L else CLOSE_QUIET_MILLIS }

    fun enter() {
        open.update { it + 1 }
    }

    fun leave() {
        open.update { (it - 1).coerceAtLeast(0) }
    }

    /** The width, in dp, the desktop window is moving to; null while it is not moving. */
    val windowTargetWidthDp = MutableStateFlow<Float?>(null)

    /** The width the shells should lay out for: the target while the window moves, so the layout class never flips mid-motion. */
    fun layoutWidthDp(actualDp: Float): Float = windowTargetWidthDp.value ?: actualDp

    internal fun reset() {
        windowTargetWidthDp.value = null
        open.value = 0
    }
}
