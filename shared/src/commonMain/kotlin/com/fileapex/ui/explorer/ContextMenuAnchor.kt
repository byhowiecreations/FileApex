package com.fileapex.ui.explorer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * Remembers where an item was last pressed so its context menu opens there. The popup sits at the item's bottom-left
 * corner plus an offset, so the offset is the press point minus the item's height; a press that was never seen keeps
 * the default place.
 */
class ContextMenuAnchor {
    private var press: Offset? = null
    private var size = IntSize.Zero

    fun onPress(at: Offset) { press = at }

    fun onSize(newSize: IntSize) { size = newSize }

    fun menuOffsetPx(): Offset {
        val at = press ?: return Offset.Zero
        val x = at.x.coerceIn(0f, size.width.toFloat())
        val y = at.y.coerceIn(0f, size.height.toFloat())
        return Offset(x, y - size.height)
    }
}

/** Watches presses without consuming them, so every other gesture on the item works as before. */
fun Modifier.trackContextMenuAnchor(anchor: ContextMenuAnchor): Modifier = this
    .onSizeChanged(anchor::onSize)
    .pointerInput(anchor) {
        awaitEachGesture { anchor.onPress(awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position) }
    }
