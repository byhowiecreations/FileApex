package com.fileapex.ui.dnd

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

@Composable
actual fun Modifier.deviceFileDropTarget(
    enabled: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onFilesDropped: (paths: List<String>) -> Unit,
    onDropPosition: (Offset) -> Unit
): Modifier = this
