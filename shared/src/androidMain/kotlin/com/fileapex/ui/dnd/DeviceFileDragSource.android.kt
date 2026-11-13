package com.fileapex.ui.dnd

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView
import com.fileapex.presentation.TransferDropUri

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
    val payload = TransferDropUri.build(sourceDeviceId, absolutePath, safeName, fileSize)
    holdAndDragAfterLongPress(
        key = payload,
        onHoldCommitted = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
        onDragStart = { startFileDrag(view, payload, safeName, absolutePath) }
    )
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
    view.startDragAndDrop(clip, shadow, null, 0)
}
