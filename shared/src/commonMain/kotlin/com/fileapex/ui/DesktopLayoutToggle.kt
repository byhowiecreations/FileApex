package com.fileapex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.i18n.stringRes
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.fileApexHeaderActionTint
import com.fileapex.ui.theme.isFileApexJadedSteel

@Composable
fun DesktopLayoutToggle(
    modifier: Modifier = Modifier,
    iconTint: Color = fileApexHeaderActionTint()
) {
    val layoutMode by FileApexServices.settings.desktopLayoutMode.collectAsState()
    val isExpanded = layoutMode == DesktopLayoutMode.Expanded
    val icon = if (isExpanded) FileApexIcons.RightPanelClose else FileApexIcons.RightPanelOpen
    val desc = if (isExpanded) {
        stringRes("switch_to_compact_layout")
    } else {
        stringRes("switch_to_expanded_layout")
    }
    val onToggle = {
        val next = if (isExpanded) DesktopLayoutMode.Compact else DesktopLayoutMode.Expanded
        FileApexServices.settings.setDesktopLayoutMode(next)
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
        IconButton(
            onClick = onToggle,
            modifier = modifier
        ) {
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
