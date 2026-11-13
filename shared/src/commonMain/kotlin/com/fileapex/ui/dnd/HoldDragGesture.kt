package com.fileapex.ui.dnd

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Awaits a stationary long hold via [awaitLongPressOrCancellation] so motionless touches still
 * qualify, then starts drag once the finger moves beyond [dragSlopFactor] * touchSlop while
 * releasing near the original point leaves the gesture to the child context-menu handler.
 */
internal fun Modifier.holdAndDragAfterLongPress(
    key: Any?,
    dragSlopFactor: Float = 2.0f,
    onHoldCommitted: () -> Unit = {},
    onDragStart: () -> Unit
): Modifier = pointerInput(key, dragSlopFactor, onHoldCommitted, onDragStart) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        onHoldCommitted()
        val dragThreshold = viewConfiguration.touchSlop * dragSlopFactor
        var dragStarted = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            // Consume post-hold motion before ancestor LazyColumn/PullToRefresh turns it into a scroll.
            change.consume()
            if ((change.position - down.position).getDistance() > dragThreshold) {
                dragStarted = true
                onDragStart()
                break
            }
        }
        if (dragStarted) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
            }
        }
    }
}
