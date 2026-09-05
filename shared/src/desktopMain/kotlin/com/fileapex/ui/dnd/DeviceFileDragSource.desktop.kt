package com.fileapex.ui.dnd

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

private class FileApexTransferable(
    private val localFiles: List<File>,
    private val payloadUri: String
) : Transferable {
    private val flavors: Array<DataFlavor> = if (localFiles.isNotEmpty()) {
        arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)
    } else {
        arrayOf(DataFlavor.stringFlavor)
    }

    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor in flavors

    override fun getTransferData(flavor: DataFlavor): Any {
        if (flavor == DataFlavor.javaFileListFlavor && localFiles.isNotEmpty()) {
            return localFiles
        }
        if (flavor == DataFlavor.stringFlavor) {
            return payloadUri
        }
        throw UnsupportedFlavorException(flavor)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
actual fun Modifier.deviceFileDragSource(
    absolutePath: String?,
    sourceDeviceId: String?,
    fileName: String?,
    fileSize: Long,
    enabled: Boolean
): Modifier {
    if (!enabled || absolutePath.isNullOrBlank()) return this

    val isLocal = sourceDeviceId == null
    val localFile = if (isLocal) File(absolutePath) else null
    if (isLocal && (localFile == null || !localFile.exists())) return this

    val safeName = fileName ?: (if (isLocal) localFile!!.name else absolutePath.substringAfterLast('/'))
    val safeSize = if (isLocal) (localFile?.length() ?: 0L) else fileSize

    val encodedUri = if (isLocal) {
        "fileapex-transfer://local/${localFile!!.absolutePath}?name=${safeName}&size=${safeSize}"
    } else {
        "fileapex-transfer://$sourceDeviceId/$absolutePath?name=${safeName}&size=${safeSize}"
    }

    val transferable = FileApexTransferable(
        localFiles = if (localFile != null) listOf(localFile) else emptyList(),
        payloadUri = encodedUri
    )

    return this.dragAndDropSource(
        drawDragDecoration = {
            drawRoundRect(
                color = androidx.compose.ui.graphics.Color(0xF00E2238),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
            )
            drawRoundRect(
                color = androidx.compose.ui.graphics.Color(0xFF00E5FF),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
            )
        }
    ) { offset ->
        DragAndDropTransferData(
            transferable = DragAndDropTransferable(transferable),
            supportedActions = listOf(DragAndDropTransferAction.Copy)
        )
    }
}
