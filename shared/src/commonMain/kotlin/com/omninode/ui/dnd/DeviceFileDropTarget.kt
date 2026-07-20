package com.omninode.ui.dnd

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop: accept Finder/OS file drops. Android: no-op (returns [this]).
 */
@Composable
expect fun Modifier.deviceFileDropTarget(
    enabled: Boolean = true,
    onHoverChange: (Boolean) -> Unit,
    onFilesDropped: (paths: List<String>) -> Unit
): Modifier
