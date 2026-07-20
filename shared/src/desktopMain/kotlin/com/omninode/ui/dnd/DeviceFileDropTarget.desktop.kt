package com.omninode.ui.dnd

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.deviceFileDropTarget(
    enabled: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onFilesDropped: (paths: List<String>) -> Unit
): Modifier {
    if (!enabled) return this

    val hoverHandler = rememberUpdatedState(onHoverChange)
    val dropHandler = rememberUpdatedState(onFilesDropped)

    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                hoverHandler.value(true)
            }

            override fun onExited(event: DragAndDropEvent) {
                hoverHandler.value(false)
            }

            override fun onEnded(event: DragAndDropEvent) {
                hoverHandler.value(false)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hoverHandler.value(false)
                val paths = extractAbsoluteFilePaths(event)
                if (paths.isEmpty()) return false
                dropHandler.value(paths)
                return true
            }
        }
    }

    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            runCatching {
                event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            }.getOrDefault(true)
        },
        target = target
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("UNCHECKED_CAST")
private fun extractAbsoluteFilePaths(event: DragAndDropEvent): List<String> {
    val transferable = runCatching { event.awtTransferable }.getOrNull() ?: return emptyList()
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    val files = runCatching {
        transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
    }.getOrNull().orEmpty()
    return files.mapNotNull { entry ->
        val file = when (entry) {
            is File -> entry
            is String -> File(entry)
            else -> null
        } ?: return@mapNotNull null
        if (file.isFile) file.absolutePath else null
    }
}
