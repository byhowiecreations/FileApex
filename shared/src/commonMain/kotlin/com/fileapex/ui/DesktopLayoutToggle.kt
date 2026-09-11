package com.fileapex.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.i18n.stringRes
import com.fileapex.ui.theme.fileApexHeaderActionTint

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

    IconButton(
        onClick = {
            val next = if (isExpanded) DesktopLayoutMode.Compact else DesktopLayoutMode.Expanded
            FileApexServices.settings.setDesktopLayoutMode(next)
        },
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
