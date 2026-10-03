package com.fileapex.ui.dnd

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView

actual fun Modifier.deviceFileDragSource(
    absolutePath: String?,
    sourceDeviceId: String?,
    fileName: String?,
    fileSize: Long,
    enabled: Boolean
): Modifier = composed {
    if (!enabled || absolutePath.isNullOrBlank()) return@composed this
    val view = LocalView.current
    val safeName = fileName?.ifBlank { null }
        ?: absolutePath.substringAfterLast('/').ifBlank { absolutePath }
    val payload = if (sourceDeviceId == null) {
        "fileapex-transfer://local/$absolutePath?name=$safeName&size=$fileSize"
    } else {
        "fileapex-transfer://$sourceDeviceId/$absolutePath?name=$safeName&size=$fileSize"
    }
    pointerInput(payload) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            val slop = viewConfiguration.touchSlop
            val deadline = System.nanoTime() + viewConfiguration.longPressTimeoutMillis * 1_000_000L
            var held = true
            while (System.nanoTime() < deadline) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) {
                    held = false
                    break
                }
                if ((change.position - start).getDistance() > slop) return@awaitEachGesture
            }
            if (!held) return@awaitEachGesture
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                if ((change.position - start).getDistance() > slop) {
                    change.consume()
                    startFileDrag(view, payload, safeName, absolutePath)
                    return@awaitEachGesture
                }
            }
        }
    }
}

private fun startFileDrag(view: View, payload: String, label: String, absolutePath: String) {
    val clip = android.content.ClipData.newPlainText("fileapex-transfer", payload)
    val shadow = object : View.DragShadowBuilder(view) {
        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            outShadowSize.set(420, 120)
            outShadowTouchPoint.set(48, 60)
        }

        override fun onDrawShadow(canvas: Canvas) {
            canvas.drawColor(0xF01B2836.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 36f
            }
            canvas.drawText(label.take(28), 28f, 74f, paint)
        }
    }
    ActiveDrag.sourcePath = absolutePath
    view.startDragAndDrop(clip, shadow, null, View.DRAG_FLAG_GLOBAL)
}
