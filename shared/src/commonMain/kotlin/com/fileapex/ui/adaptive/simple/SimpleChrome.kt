package com.fileapex.ui.adaptive.simple

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.i18n.stringRes
import com.fileapex.platform.isDesktopHost
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.ui.DesktopLayoutToggle
import com.fileapex.ui.ExplorerHeaderCommands
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.ExplorerViewModeToggle
import com.fileapex.ui.NoteHeaderButton
import com.fileapex.ui.NoteIconKind
import com.fileapex.ui.QueuedFilesButton
import com.fileapex.ui.adaptive.AdaptiveRailColumn
import com.fileapex.ui.adaptive.FileApexPowerButton
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
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.theme.fileApexChromeBottomEdge
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.fileApexFloatingChrome
import com.fileapex.ui.theme.fileApexHeaderActionTint
import com.fileapex.ui.theme.fileApexNavSelectedTextColor
import com.fileapex.ui.theme.fileApexNavUnselectedTextColor
import com.fileapex.ui.theme.fileApexNavigationBarItemColors
import com.fileapex.ui.theme.isFileApexCleanCurved
import kotlin.math.roundToInt

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
            imageVector = Icons.Filled.FileUpload,
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
internal fun SimpleTopBar(
    onOpenNotes: (() -> Unit)?,
    onHome: () -> Unit,
    onExit: () -> Unit,
    onOpenTransferQueue: () -> Unit,
    explorerCommands: ExplorerHeaderCommands?,
    splitAvailable: Boolean,
    onRefreshExplorer: (() -> Unit)?,
    explorerRefreshing: Boolean
) {
    val explorerViewMode by FileApexServices.settings.explorerViewMode.collectAsState()
    val tint = fileApexHeaderActionTint(onChromeBar = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fileApexChromeBottomEdge()
            .then(
                if (isFileApexCleanCurved()) {
                    Modifier.fileApexFloatingChrome(
                        fileApexChromeContainerColor(),
                        outer = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    )
                } else {
                    Modifier.background(fileApexChromeContainerColor())
                }
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FileApex",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = fileApexChromeContentColor()
        )
        IconButton(onClick = onHome, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = stringRes("home"),
                tint = tint
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        val liveStats by TransferActivityGuard.statsFlow.collectAsState()
        if (liveStats.isActive) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(onClick = onOpenTransferQueue)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    progress = { liveStats.progress },
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                val rateText = buildList {
                    if (liveStats.speedFormatted.isNotBlank()) add(liveStats.speedFormatted)
                    if (liveStats.etaFormatted.isNotBlank()) add(liveStats.etaFormatted)
                    add("${(liveStats.progress * 100).toInt().coerceIn(0, 100)}%")
                }.joinToString(" • ")
                Text(
                    text = rateText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = fileApexChromeContentColor()
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
        QueuedFilesButton(onClick = onOpenTransferQueue, iconTint = tint)
        if (onOpenNotes != null) {
            val noteIconKind = if (LocalAppTheme.current.traits.glassChrome) NoteIconKind.GREEN else NoteIconKind.WHITE
            NoteHeaderButton(onOpenNotes = onOpenNotes, iconKind = noteIconKind, modifier = Modifier.size(40.dp))
        }
        explorerCommands?.let { commands ->
            TextButton(onClick = commands.onSelect, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringRes("select"), color = fileApexChromeContentColor(), fontWeight = FontWeight.SemiBold)
            }
            commands.onPaste?.let { onPaste ->
                TextButton(onClick = onPaste, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringRes("paste"), color = fileApexChromeContentColor(), fontWeight = FontWeight.SemiBold)
                }
            }
            ExplorerViewModeToggle(
                viewMode = explorerViewMode,
                onToggle = {
                    FileApexServices.settings.setExplorerViewMode(
                        if (splitAvailable) explorerViewMode.cycled() else explorerViewMode.toggled()
                    )
                },
                includeSplit = splitAvailable,
                iconTint = tint,
                modifier = Modifier.size(40.dp)
            )
        }
        if (isDesktopHost()) {
            DesktopLayoutToggle(modifier = Modifier.size(40.dp), iconTint = tint)
        }
        if (onRefreshExplorer != null) {
            IconButton(onClick = onRefreshExplorer, enabled = !explorerRefreshing, modifier = Modifier.size(40.dp)) {
                if (explorerRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = explorerIcon(ExplorerIcon.Refresh),
                        contentDescription = stringRes("refresh"),
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        FileApexPowerButton(onClick = onExit)
    }
}

@Composable
internal fun SimpleRail(
    destination: SimpleDestination,
    popover: SimplePopover?,
    onSelect: (SimpleDestination) -> Unit,
    onSendClipboard: () -> Unit,
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
    val fullLabel = stringRes("simple_device_management")
    val useShort = neededWidth(fullLabel) > RAIL_LABEL_WIDTH_CAP
    val managementLabel = if (useShort) stringRes("simple_device_management_short") else fullLabel
    val managementWidth = neededWidth(managementLabel).coerceAtLeast(RAIL_LABEL_MIN_WIDTH)
    val localFilesLabel = stringRes("local_files")
    val browseLabel = stringRes("browse")
    val clipboardLabel = stringRes("clipboard")
    val settingsLabel = stringRes("settings")
    val moreLabel = stringRes("simple_more")

    NavigationRail(
        modifier = Modifier
            .widthIn(min = 92.dp)
            .padding(horizontal = 4.dp)
            .fillMaxHeight()
            .then(
                if (isFileApexCleanCurved()) {
                    Modifier.padding(top = 8.dp, bottom = 14.dp).clip(RoundedCornerShape(28.dp))
                } else {
                    Modifier
                }
            ),
        containerColor = fileApexChromeContainerColor(),
        contentColor = fileApexChromeContentColor()
    ) {
        AdaptiveRailColumn(
            slots = listOf(
                RailSlot(SimpleRailEntry.LocalFiles) {
                    RailItem(
                        selected = destination == SimpleDestination.LocalFiles,
                        onClick = { onSelect(SimpleDestination.LocalFiles) },
                        icon = Icons.Filled.Folder,
                        label = localFilesLabel
                    )
                },
                RailSlot(SimpleRailEntry.Browse) {
                    RailItem(
                        selected = destination == SimpleDestination.Browse,
                        onClick = { onSelect(SimpleDestination.Browse) },
                        icon = Icons.Filled.Devices,
                        label = browseLabel
                    )
                },
                RailSlot(SimpleRailEntry.Clipboard) {
                    RailItem(
                        selected = false,
                        onClick = onSendClipboard,
                        icon = Icons.Filled.ContentPaste,
                        label = clipboardLabel
                    )
                },
                RailSlot(SimpleRailEntry.DeviceManagement) {
                    Box(modifier = Modifier.onGloballyPositioned { onMenuAnchor(it.boundsInWindow()) }) {
                        RailItem(
                            selected = popover == SimplePopover.DeviceManagement || destination == SimpleDestination.Info,
                            onClick = { onTogglePopover(SimplePopover.DeviceManagement) },
                            icon = Icons.Filled.Link,
                            label = managementLabel,
                            labelMaxWidth = managementWidth
                        )
                    }
                },
                RailSlot(SimpleRailEntry.Settings) {
                    RailItem(
                        selected = destination == SimpleDestination.Settings,
                        onClick = { onSelect(SimpleDestination.Settings) },
                        icon = Icons.Filled.Settings,
                        label = settingsLabel
                    )
                }
            ),
            more = {
                Box(modifier = Modifier.onGloballyPositioned { onMenuAnchor(it.boundsInWindow()) }) {
                    RailItem(
                        selected = popover == SimplePopover.More,
                        onClick = { onTogglePopover(SimplePopover.More) },
                        icon = Icons.Filled.MoreHoriz,
                        label = moreLabel
                    )
                }
            },
            onHiddenChanged = onHiddenChanged,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        )
    }
}

private val RAIL_LABEL_WIDTH_CAP = 104.dp

private val RAIL_LABEL_MIN_WIDTH = 72.dp

@Composable
internal fun SimpleCompactBottom(
    destination: SimpleDestination,
    popoverOpen: Boolean,
    onSelect: (SimpleDestination) -> Unit,
    onSendClipboard: () -> Unit,
    onToggleMenu: () -> Unit,
    onMenuAnchor: (Rect) -> Unit
) {
    Column(modifier = Modifier.navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SimplePill(
                icon = Icons.Filled.ContentPaste,
                label = stringRes("send_clipboard"),
                onClick = onSendClipboard,
                modifier = Modifier.weight(1f)
            )
            SimplePill(
                icon = Icons.Filled.Link,
                label = stringRes("simple_device_management_short"),
                selected = popoverOpen || destination == SimpleDestination.Info,
                onClick = onToggleMenu,
                modifier = Modifier.weight(1f).onGloballyPositioned { onMenuAnchor(it.boundsInWindow()) }
            )
        }
        NavigationBar(
            modifier = Modifier
                .then(
                    if (isFileApexCleanCurved()) {
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).height(72.dp).clip(RoundedCornerShape(28.dp))
                    } else {
                        Modifier
                    }
                ),
            containerColor = fileApexChromeContainerColor(),
            contentColor = fileApexNavSelectedTextColor(),
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0.dp)
        ) {
            SimpleBarItem(
                selected = destination == SimpleDestination.LocalFiles,
                onClick = { onSelect(SimpleDestination.LocalFiles) },
                icon = Icons.Filled.Folder,
                label = stringRes("local_files")
            )
            SimpleBarItem(
                selected = destination == SimpleDestination.Browse,
                onClick = { onSelect(SimpleDestination.Browse) },
                icon = Icons.Filled.Devices,
                label = stringRes("browse")
            )
            SimpleBarItem(
                selected = destination == SimpleDestination.Settings,
                onClick = { onSelect(SimpleDestination.Settings) },
                icon = Icons.Filled.Settings,
                label = stringRes("settings")
            )
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
        icon = { Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(20.dp)) },
        label = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) fileApexNavSelectedTextColor() else fileApexNavUnselectedTextColor()
                )
                if (!caption.isNullOrBlank()) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = fileApexNavUnselectedTextColor()
                    )
                }
            }
        },
        colors = fileApexNavigationBarItemColors()
    )
}

@Composable
private fun SimplePill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    Surface(
        modifier = modifier.height(44.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = fileApexChromeContainerColor(),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = fileApexChromeContentColor())
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                label,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = fileApexChromeContentColor()
            )
        }
    }
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

/**
 * Springs out of the button that opened it and folds back into it. While it is up, a transparent
 * layer over everything else turns any tap, including the button itself, into a close.
 */
@Composable
internal fun SimplePopoverHost(
    visible: Boolean,
    anchor: Rect?,
    overlayOrigin: Offset,
    anchoredBelow: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }
        val popoverWidth = 264.dp
        val gap = with(density) { 8.dp.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val popoverWidthPx = with(density) { popoverWidth.toPx() }
        val estimatedHeightPx = with(density) { 320.dp.toPx() }
        val anchorLocal = anchor?.translate(-overlayOrigin.x, -overlayOrigin.y)
        val placement = if (anchorLocal == null) {
            Modifier.align(Alignment.TopStart)
        } else if (anchoredBelow) {
            val x = (anchorLocal.right + gap).coerceIn(0f, (widthPx - popoverWidthPx).coerceAtLeast(0f))
            val y = anchorLocal.top.coerceIn(0f, (heightPx - estimatedHeightPx).coerceAtLeast(0f))
            Modifier.align(Alignment.TopStart).offset { IntOffset(x.roundToInt(), y.roundToInt()) }
        } else {
            val x = (anchorLocal.right - popoverWidthPx).coerceIn(gap, (widthPx - popoverWidthPx).coerceAtLeast(gap))
            val bottomGap = heightPx - anchorLocal.top + gap
            Modifier.align(Alignment.BottomStart).offset { IntOffset(x.roundToInt(), -bottomGap.roundToInt()) }
        }
        val origin = if (anchoredBelow) TransformOrigin(0f, 0.12f) else TransformOrigin(0.85f, 1f)
        AnimatedVisibility(
            visible = visible && anchorLocal != null,
            modifier = placement.width(popoverWidth),
            enter = scaleIn(
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                transformOrigin = origin
            ) + fadeIn(tween(120)),
            exit = scaleOut(tween(170), transformOrigin = origin) + fadeOut(tween(140))
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp), content = content)
            }
        }
    }
}
