package com.fileapex.ui.dnd

import androidx.compose.ui.Modifier

actual fun Modifier.deviceFileDragSource(
    absolutePath: String?,
    sourceDeviceId: String?,
    fileName: String?,
    fileSize: Long,
    enabled: Boolean
): Modifier = this
