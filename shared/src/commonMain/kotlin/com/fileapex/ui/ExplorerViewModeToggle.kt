package com.fileapex.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.fileapex.presentation.ExplorerViewMode

@Composable
fun ExplorerViewModeToggle(
    viewMode: ExplorerViewMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.White
) {
    val showGridNext = viewMode == ExplorerViewMode.List
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (showGridNext) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
            contentDescription = if (showGridNext) "Switch to grid view" else "Switch to list view",
            tint = iconTint
        )
    }
}
