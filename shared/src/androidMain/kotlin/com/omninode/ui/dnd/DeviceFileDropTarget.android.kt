package com.omninode.ui.dnd

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun Modifier.deviceFileDropTarget(
    enabled: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onFilesDropped: (paths: List<String>) -> Unit
): Modifier = this
