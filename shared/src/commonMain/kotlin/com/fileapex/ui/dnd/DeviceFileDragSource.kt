package com.fileapex.ui.dnd

import androidx.compose.ui.Modifier

/**
 * Desktop: Initiates an AWT drag (javaFileListFlavor for local, fileapex-transfer string for remote).
 * Android: no-op.
 */
expect fun Modifier.deviceFileDragSource(
    absolutePath: String?,
    sourceDeviceId: String? = null,
    fileName: String? = null,
    fileSize: Long = 0L,
    enabled: Boolean = true
): Modifier
