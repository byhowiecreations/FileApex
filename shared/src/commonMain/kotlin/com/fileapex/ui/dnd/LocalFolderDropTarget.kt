package com.fileapex.ui.dnd

import androidx.compose.ui.Modifier

expect fun Modifier.localFolderDropTarget(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
    onHoverChanged: (Boolean) -> Unit = {},
    onDragMove: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onDragEnded: () -> Unit = {},
): Modifier
