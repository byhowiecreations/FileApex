@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.fileapex.ui.dnd

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
actual fun Modifier.localFolderDropTarget(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
    onHoverChanged: (Boolean) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnded: () -> Unit,
): Modifier {
    if (!enabled) return this
    return composed {
        val density = LocalDensity.current
        val target = remember(density, onFiles, onHoverChanged, onDragMove, onDragEnded) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    onHoverChanged(true)
                    onDragMove(event.rootOffset(density))
                }

                override fun onMoved(event: DragAndDropEvent) {
                    onDragMove(event.rootOffset(density))
                }

            override fun onExited(event: DragAndDropEvent) {
                onHoverChanged(false)
            }

            override fun onEnded(event: DragAndDropEvent) {
                onDragEnded()
            }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    onDragMove(event.rootOffset(density))
                    val files = droppedPayloads(event)
                    if (files.isEmpty()) return false
                    onFiles(files)
                    return true
                }
            }
        }
        dragAndDropTarget(
            shouldStartDragAndDrop = { true },
            target = target
        )
    }
}

private fun DragAndDropEvent.rootOffset(density: Density): Offset {
    val point = when (val native = nativeEvent) {
        is DropTargetDragEvent -> native.location
        is DropTargetDropEvent -> native.location
        else -> return Offset.Zero
    }
    return with(density) {
        Offset(point.x.toFloat().dp.toPx(), point.y.toFloat().dp.toPx())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("UNCHECKED_CAST")
private fun droppedPayloads(event: DragAndDropEvent): List<String> {
    val transferable = runCatching { event.awtTransferable }.getOrNull()
    if (transferable != null && transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        val text = runCatching { transferable.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
        if (!text.isNullOrBlank() && text.startsWith("fileapex-transfer://")) {
            return listOf(text)
        }
    }
    if (transferable != null && transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val files = runCatching {
            transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        }.getOrNull().orEmpty()
        val paths = files.mapNotNull { entry ->
            when (entry) {
                is File -> entry.absolutePath
                is String -> entry
                else -> null
            }
        }
        if (paths.isNotEmpty()) return paths
    }
    return (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
}
