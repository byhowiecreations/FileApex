package com.fileapex.platform

import androidx.compose.runtime.Composable

data class PickedLocalFile(
    val displayName: String,
    val sizeBytes: Long,
    val absolutePath: String
)

@Composable
expect fun rememberDownloadsFilePicker(
    onPicked: (PickedLocalFile?) -> Unit
): () -> Unit
