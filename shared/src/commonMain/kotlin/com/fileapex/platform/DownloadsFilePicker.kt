package com.fileapex.platform

import androidx.compose.runtime.Composable

data class PickedLocalFile(
    val displayName: String,
    val sizeBytes: Long,
    val absolutePath: String
)

/**
 * Native file picker that opens in the user Downloads folder when the OS allows an initial directory.
 */
@Composable
expect fun rememberDownloadsFilePicker(
    onPicked: (PickedLocalFile?) -> Unit
): () -> Unit
