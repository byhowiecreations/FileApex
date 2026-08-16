package com.fileapex.ui.dnd

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * Desktop: accept Finder/Explorer file **and folder** drops. Android: no-op (returns [this]).
 */
@Composable
expect fun Modifier.deviceFileDropTarget(
    enabled: Boolean = true,
    onHoverChange: (Boolean) -> Unit,
    onFilesDropped: (paths: List<String>) -> Unit,
    onDropPosition: (Offset) -> Unit = {}
): Modifier
