package com.fileapex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.ThemeShapeStyle
import com.fileapex.data.settings.traits
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DeviceListRow
import com.fileapex.util.PathUtils

@Composable
fun ExplorerBreadcrumb(
    label: String?,
    path: String,
    rootPath: String,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val traits = LocalAppTheme.current.traits
    val shape = when (traits.shapeStyle) {
        ThemeShapeStyle.Pill -> RoundedCornerShape(percent = 50)
        ThemeShapeStyle.RoundedSquare -> RoundedCornerShape(12.dp)
    }
    val ancestors = remember(path, rootPath) { ancestorPaths(path, rootPath) }
    var open by remember { mutableStateOf(false) }
    val shown = buildString {
        if (!label.isNullOrBlank()) {
            append(label)
            if (path.isNotBlank()) append("  ")
        }
        append(path)
    }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(enabled = ancestors.isNotEmpty()) { open = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = shown,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (ancestors.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = shape,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = traits.surfaceAlpha.coerceIn(0.85f, 1f)),
        ) {
            ancestors.forEach { ancestor ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = ancestor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        open = false
                        onJump(ancestor)
                    },
                )
            }
        }
    }
}

private fun ancestorPaths(path: String, rootPath: String): List<String> {
    val chain = ArrayList<String>()
    var current = PathUtils.parentWithinRoot(path, rootPath)
    while (current != null) {
        chain.add(current)
        current = PathUtils.parentWithinRoot(current, rootPath)
    }
    val root = PathUtils.normalize(rootPath)
    if (chain.none { PathUtils.normalize(it) == root }) {
        chain.add(root)
    }
    return chain
}

@Composable
fun PaneSourceLabel(text: String) {
    val color = paneSourceColor()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun paneSourceColor() = if (
    LocalAppTheme.current.traits.orbitalHome &&
    LocalKineticStyle.current == KineticStyle.JADED_STEEL
) {
    KineticStyleLook.ink
} else {
    MaterialTheme.colorScheme.onSurface
}

@Composable
fun SecondarySourcePicker(
    secondaryTarget: BrowseTarget?,
    readyDevices: List<DeviceListRow>,
    onSelectLocal: () -> Unit,
    onSelectDevice: (String) -> Unit,
) {
    val traits = LocalAppTheme.current.traits
    val shape = when (traits.shapeStyle) {
        ThemeShapeStyle.Pill -> RoundedCornerShape(percent = 50)
        ThemeShapeStyle.RoundedSquare -> RoundedCornerShape(12.dp)
    }
    var open by remember { mutableStateOf(false) }
    val labelColor = paneSourceColor()
    val label = when (secondaryTarget) {
        is BrowseTarget.Remote -> secondaryTarget.displayName
        else -> stringRes("pane_local")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .clickable { open = true }
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = shape) {
            DropdownMenuItem(
                text = { Text(stringRes("pane_local")) },
                onClick = {
                    open = false
                    onSelectLocal()
                }
            )
            readyDevices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onSelectDevice(device.deviceId)
                    }
                )
            }
        }
    }
}
