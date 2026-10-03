package com.fileapex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.i18n.stringRes
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.fileApexHeaderActionTint
import com.fileapex.ui.theme.isFileApexJadedSteel

@Composable
fun ExplorerViewModeToggle(
    viewMode: ExplorerViewMode,
    onToggle: () -> Unit,
    includeSplit: Boolean = false,
    modifier: Modifier = Modifier,
    iconTint: Color = fileApexHeaderActionTint()
) {
    val next = if (includeSplit) viewMode.cycled() else viewMode.toggled()
    val icon = when (next) {
        ExplorerViewMode.Grid -> Icons.Filled.GridView
        ExplorerViewMode.Split -> Icons.Filled.VerticalSplit
        ExplorerViewMode.List -> Icons.AutoMirrored.Filled.ViewList
    }
    val desc = when (next) {
        ExplorerViewMode.Grid -> stringRes("switch_to_grid")
        ExplorerViewMode.Split -> stringRes("split_view")
        ExplorerViewMode.List -> stringRes("switch_to_list")
    }
    val jaded = isFileApexJadedSteel()
    if (jaded) {
        Box(
            modifier = modifier
                .size(40.dp)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            JadedRaisedTile(tileSize = 28.dp) {
                Icon(
                    imageVector = icon,
                    contentDescription = desc,
                    tint = KineticStyleLook.steel,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    } else {
        IconButton(onClick = onToggle, modifier = modifier) {
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = iconTint
            )
        }
    }
}

@Composable
fun ExplorerSplitToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = fileApexHeaderActionTint()
) {
    val desc = stringRes("split_view")
    val tint = if (enabled) iconTint else iconTint.copy(alpha = 0.45f)
    val jaded = isFileApexJadedSteel()
    if (jaded) {
        Box(
            modifier = modifier
                .size(40.dp)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            JadedRaisedTile(tileSize = 28.dp) {
                Icon(
                    imageVector = Icons.Filled.VerticalSplit,
                    contentDescription = desc,
                    tint = if (enabled) KineticStyleLook.steel else KineticStyleLook.steel.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    } else {
        IconButton(onClick = onToggle, modifier = modifier) {
            Icon(
                imageVector = Icons.Filled.VerticalSplit,
                contentDescription = desc,
                tint = tint
            )
        }
    }
}

