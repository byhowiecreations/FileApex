package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.ui.adaptive.AdaptiveRailColumn
import com.fileapex.ui.adaptive.RailItem
import com.fileapex.ui.adaptive.RailSlot
import com.fileapex.ui.adaptive.SimpleDestination
import com.fileapex.ui.adaptive.SimplePopover
import com.fileapex.ui.adaptive.SimpleRailEntry
import com.fileapex.ui.dnd.EXPLORER_DROP_BOX_DEST
import com.fileapex.ui.dnd.EXPLORER_DROP_BOX_KEY
import com.fileapex.ui.dnd.LocalExplorerDropHighlight
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.dnd.reportDropSpot
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.fileApexNavSelectedTextColor
import com.fileapex.ui.theme.fileApexNavUnselectedTextColor
import com.fileapex.ui.theme.fileApexNavigationBarItemColors
import com.fileapex.ui.theme.isFileApexCleanCurved
import com.fileapex.ui.theme.isFileApexSimpleModern

@Composable
internal fun SimpleDropBox(
    label: String,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onFiles: (List<String>) -> Unit
) {
    var desktopHovered by remember { mutableStateOf(false) }
    val spotHovered = LocalExplorerDropHighlight.current.path == EXPLORER_DROP_BOX_DEST
    val hovered = if (usesDesktopFileSelection()) desktopHovered else spotHovered
    val accent = MaterialTheme.colorScheme.primary
    val dashColor = if (hovered) accent else MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (hovered) accent.copy(alpha = 0.12f) else Color.Transparent)
            .drawBehind {
                drawRoundRect(
                    color = dashColor,
                    cornerRadius = CornerRadius(size.height / 2f),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                )
            }
            .then(
                if (usesDesktopFileSelection()) {
                    Modifier.deviceFileDropTarget(
                        enabled = enabled,
                        onHoverChange = { desktopHovered = it },
                        onFilesDropped = onFiles
                    )
                } else if (enabled) {
                    // Touch drags are resolved by position in the explorer, like folders in split view.
                    Modifier.reportDropSpot(EXPLORER_DROP_BOX_KEY, EXPLORER_DROP_BOX_DEST)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = if (compact) 0.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.FileUpload,
            contentDescription = if (compact) label else null,
            tint = dashColor,
            modifier = Modifier.size(20.dp)
        )
        if (!compact) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                label,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SimpleRail(
    destination: SimpleDestination,
    popover: SimplePopover?,
    bulletinUnreadCount: Int,
    onSelect: (SimpleDestination) -> Unit,
    onOpenNotes: () -> Unit,
    onTogglePopover: (SimplePopover) -> Unit,
    onMenuAnchor: (Rect) -> Unit,
    onHiddenChanged: (Set<SimpleRailEntry>) -> Unit
) {
    // The label gets the width it needs up to a cap; past the cap the short form is used.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelMedium
    fun neededWidth(label: String): Dp = with(density) {
        label.split(' ').maxOf { measurer.measure(it, labelStyle).size.width.toDp() } + 8.dp
    }
    val managementLabel = stringRes(SimpleRailCatalog.managementLabelKey())
    val managementWidth = neededWidth(managementLabel).coerceAtLeast(RAIL_LABEL_MIN_WIDTH)
    val localFilesLabel = stringRes("local_files")
    val browseLabel = stringRes("browse")
    val bulletinLabel = stringRes("bulletin_board")
    val settingsLabel = stringRes("settings")
    val moreLabel = stringRes("simple_more")
    val cleanCurved = isFileApexCleanCurved()

    NavigationRail(
        modifier = Modifier
            .widthIn(min = 96.dp)
            .padding(horizontal = if (cleanCurved) 8.dp else 4.dp, vertical = if (cleanCurved) 8.dp else 0.dp)
            .fillMaxHeight(),
        containerColor = if (cleanCurved) Color.Transparent else fileApexChromeContainerColor(),
        contentColor = fileApexChromeContentColor()
    ) {
        AdaptiveRailColumn(
            slots = listOf(
                RailSlot(SimpleRailEntry.LocalFiles) {
                    RailItem(
                        selected = destination == SimpleDestination.LocalFiles,
                        onClick = { onSelect(SimpleDestination.LocalFiles) },
                        icon = Icons.Outlined.Folder,
                        label = localFilesLabel
                    )
                },
                RailSlot(SimpleRailEntry.Browse) {
                    RailItem(
                        selected = destination == SimpleDestination.Browse,
                        onClick = { onSelect(SimpleDestination.Browse) },
                        icon = Icons.Outlined.Devices,
                        label = browseLabel
                    )
                },
                RailSlot(SimpleRailEntry.BulletinBoard) {
                    Box {
                        RailItem(
                            selected = false,
                            onClick = onOpenNotes,
                            icon = Icons.Outlined.PushPin,
                            label = bulletinLabel,
                            labelMaxWidth = neededWidth(bulletinLabel).coerceAtLeast(RAIL_LABEL_MIN_WIDTH)
                        )
                        if (bulletinUnreadCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 6.dp, end = 16.dp)
                                    .size(8.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(SimpleModernPalette.AccentMint)
                            )
                        }
                    }
                },
                RailSlot(SimpleRailEntry.DeviceManagement) {
                    Box(modifier = Modifier.onGloballyPositioned { onMenuAnchor(it.boundsInWindow()) }) {
                        RailItem(
                            selected = popover == SimplePopover.DeviceManagement || destination == SimpleDestination.Info,
                            onClick = { onTogglePopover(SimplePopover.DeviceManagement) },
                            icon = Icons.Outlined.Link,
                            label = managementLabel,
                            labelMaxWidth = managementWidth
                        )
                    }
                },
                RailSlot(SimpleRailEntry.Settings) {
                    RailItem(
                        selected = destination == SimpleDestination.Settings,
                        onClick = { onSelect(SimpleDestination.Settings) },
                        icon = Icons.Outlined.Settings,
                        label = settingsLabel
                    )
                }
            ),
            more = {
                Box(modifier = Modifier.onGloballyPositioned { onMenuAnchor(it.boundsInWindow()) }) {
                    RailItem(
                        selected = popover == SimplePopover.More,
                        onClick = { onTogglePopover(SimplePopover.More) },
                        icon = Icons.Outlined.MoreHoriz,
                        label = moreLabel
                    )
                }
            },
            onHiddenChanged = onHiddenChanged,
            upperCapsule = if (cleanCurved) {
                {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(34.dp),
                        color = fileApexChromeContainerColor(),
                        border = if (isFileApexSimpleModern()) {
                            BorderStroke(1.dp, SimpleModernPalette.ChromeBorder)
                        } else {
                            null
                        }
                    ) {}
                }
            } else null,
            modifier = Modifier.weight(1f)
        )
    }
}

private val RAIL_LABEL_MIN_WIDTH = 76.dp

@Composable
internal fun SimpleCompactBottom(
    destination: SimpleDestination,
    bulletinUnreadCount: Int,
    onSelect: (SimpleDestination) -> Unit,
    onOpenNotes: () -> Unit
) {
    Column(modifier = Modifier.navigationBarsPadding()) {
        NavigationBar(
            modifier = Modifier
                .then(
                    if (isFileApexCleanCurved()) {
                        Modifier
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                            .height(SimpleBottomBarLayoutPolicy.curvedBarHeightDp.dp)
                            .clip(RoundedCornerShape(28.dp))
                    } else {
                        Modifier
                    }
                ),
            containerColor = fileApexChromeContainerColor(),
            contentColor = fileApexNavSelectedTextColor(),
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0.dp)
        ) {
            Spacer(modifier = Modifier.width(SimpleBottomBarLayoutPolicy.edgeSpacerDp.dp))
            SimpleBarItem(
                selected = destination == SimpleDestination.LocalFiles,
                onClick = { onSelect(SimpleDestination.LocalFiles) },
                icon = Icons.Outlined.Folder,
                label = stringRes("local_files")
            )
            SimpleBarItem(
                selected = destination == SimpleDestination.Browse,
                onClick = { onSelect(SimpleDestination.Browse) },
                icon = Icons.Outlined.Devices,
                label = stringRes("browse")
            )
            SimpleBarItem(
                selected = false,
                onClick = onOpenNotes,
                icon = Icons.Outlined.PushPin,
                label = if (bulletinUnreadCount > 0) "${stringRes("bulletin_board")} ($bulletinUnreadCount)" else stringRes("bulletin_board")
            )
            SimpleBarItem(
                selected = destination == SimpleDestination.Settings,
                onClick = { onSelect(SimpleDestination.Settings) },
                icon = Icons.Outlined.Settings,
                label = stringRes("settings")
            )
            Spacer(modifier = Modifier.width(SimpleBottomBarLayoutPolicy.edgeSpacerDp.dp))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SimpleBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    caption: String? = null
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        alwaysShowLabel = true,
        icon = { Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(20.dp)) },
        label = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    label,
                    textAlign = TextAlign.Center,
                    maxLines = SimpleBottomBarLayoutPolicy.labelMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 12.sp),
                    color = if (selected) fileApexNavSelectedTextColor() else fileApexNavUnselectedTextColor()
                )
                if (!caption.isNullOrBlank()) {
                    Text(
                        caption,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = fileApexNavUnselectedTextColor()
                    )
                }
            }
        },
        colors = fileApexNavigationBarItemColors()
    )
}

@Composable
internal fun SimpleNoDeviceState(
    connecting: Boolean,
    hasPairedDevice: Boolean,
    onAddDevice: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (connecting) {
            CircularProgressIndicator()
        } else {
            Text(stringRes("simple_no_device_title"), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                stringRes("simple_no_device_body"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (hasPairedDevice) {
                Button(onClick = onRetry) { Text(stringRes("refresh")) }
            } else {
                Button(onClick = onAddDevice) { Text(stringRes("add_new_device")) }
            }
        }
    }
}
