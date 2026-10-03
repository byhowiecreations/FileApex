package com.fileapex.ui.dnd

import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset

private fun dragPosition(event: DragAndDropEvent): Offset {
    val drag = event.toAndroidDragEvent()
    return Offset(drag.x, drag.y)
}

@OptIn(ExperimentalFoundationApi::class)
actual fun Modifier.localFolderDropTarget(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
    onHoverChanged: (Boolean) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnded: () -> Unit,
): Modifier {
    if (!enabled) return this
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.toAndroidDragEvent().clipDescription
                ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
        },
        target = object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                onHoverChanged(true)
                onDragMove(dragPosition(event))
            }

            override fun onMoved(event: DragAndDropEvent) {
                onDragMove(dragPosition(event))
            }

            override fun onExited(event: DragAndDropEvent) {
                onHoverChanged(false)
            }

            override fun onEnded(event: DragAndDropEvent) {
                onDragEnded()
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                onDragMove(dragPosition(event))
                val text = event.toAndroidDragEvent().clipData
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
                    .orEmpty()
                if (!text.startsWith("fileapex-transfer://")) return false
                onFiles(listOf(text))
                return true
            }
        }
    )
}
