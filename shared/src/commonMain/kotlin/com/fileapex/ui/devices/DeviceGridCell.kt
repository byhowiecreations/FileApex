package com.fileapex.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.i18n.stringRes
import com.fileapex.platform.isDesktopHost
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.DeviceEntryIcon
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.theme.CleanModernPalette
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.FileApexTealDark
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.LocalFileApexUiStyle
import com.fileapex.ui.theme.isFileApexCleanModern
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
internal fun DeviceGridCell(
    row: DeviceListRow,
    grid: DeviceGridLayoutSpec,
    selected: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDeviceDetails: () -> Unit,
    onSendClipboard: () -> Unit,
    onRemove: () -> Unit,
    dropDeviceId: String,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var dropHover by remember { mutableStateOf(false) }
    var cellOriginInWindow by remember { mutableStateOf<Offset?>(null) }
    var titleOverflow by remember { mutableStateOf(false) }
    var subtitleOverflow by remember { mutableStateOf(false) }
    var statOverflow by remember { mutableStateOf(false) }
    val hasOverflow = titleOverflow || subtitleOverflow || statOverflow

    val currentTheme = LocalAppTheme.current
    val isFlux = currentTheme.traits.fluxSurfaces
    val allowHoverPopOver = isDesktopHost() && currentTheme.traits.desktopHoverPopOver && hasOverflow
    val cardInteractionSource = remember { MutableInteractionSource() }
    val isCardHovered by cardInteractionSource.collectIsHoveredAsState()
    val popupInteractionSource = remember { MutableInteractionSource() }
    val isPopupHovered by popupInteractionSource.collectIsHoveredAsState()

    var isHoverDwellActive by remember { mutableStateOf(false) }
    LaunchedEffect(isCardHovered, isPopupHovered) {
        if (isCardHovered || isPopupHovered) {
            if (!isHoverDwellActive) {
                delay(800L)
                isHoverDwellActive = true
            }
        } else {
            isHoverDwellActive = false
        }
    }
    val showPopOver = allowHoverPopOver && isHoverDwellActive

    val popupPositionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val origin = cellOriginInWindow
                val cellX = origin?.x?.roundToInt() ?: anchorBounds.left
                val cellY = origin?.y?.roundToInt() ?: anchorBounds.top
                val x = cellX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = cellY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        }
    }

    val highlighted = selected || dropHover
    val fluent = LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent
    val cleanModern = isFileApexCleanModern()
    val cellShape = if (fluent || cleanModern || isFlux) RoundedCornerShape(10.dp) else RoundedCornerShape(12.dp)
    val titleStyle = if (grid.compactTypography) {
        MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    }
    val subtitleStyle = if (grid.compactTypography) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodySmall
    }
    val titleColor = if (isFlux) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (isFlux) Color.White.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant
    val containerColor = when {
        dropHover -> FileApexTeal.copy(alpha = 0.22f)
        selected && isFlux -> FluxGlassPalette.navSelectedPill
        selected && cleanModern -> CleanModernPalette.NavSelectedPill
        selected -> FileApexTeal.copy(alpha = 0.12f)
        isFlux -> FluxGlassPalette.deviceCard
        cleanModern -> CleanModernPalette.CardSurface
        fluent -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    val liveStats by TransferActivityGuard.statsFlow.collectAsState()
    val pendingItems by if (FileApexServices.isDatabaseReady()) FileApexServices.transferQueue.pendingItems.collectAsState(initial = emptyList()) else remember { mutableStateOf(emptyList()) }
    val activeSending = pendingItems.firstOrNull { it.isSending }
    val isSendingToThis = liveStats.isActive && (row.deviceId == dropDeviceId || (activeSending != null && (row.deviceId in activeSending.pendingDeviceIds || activeSending.pendingDeviceNames.any { it.equals(row.title, ignoreCase = true) })))

    Box(modifier = Modifier.fillMaxWidth().height(grid.cellHeight)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val next = coords.localToWindow(Offset.Zero)
                    if (cellOriginInWindow != next) cellOriginInWindow = next
                }
                .then(if (allowHoverPopOver) Modifier.hoverable(cardInteractionSource) else Modifier)
                .deviceFileDropTarget(
                    enabled = true,
                    onHoverChange = { dropHover = it },
                    onFilesDropped = { paths -> onFilesDropped(dropDeviceId, paths) }
                )
                .clip(cellShape)
                .clickable(enabled = !connecting, onClick = onClick),
        shape = cellShape,
        color = containerColor,
        contentColor = titleColor,
        tonalElevation = 0.dp,
        shadowElevation = if (fluent || highlighted || cleanModern || isFlux) 0.dp else 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = when {
                highlighted -> if (isFlux) FluxGlassPalette.navSelectedTint else FileApexTeal
                fluent -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                cleanModern -> CleanModernPalette.CardBorder
                isFlux -> FluxGlassPalette.cardBorder
                else -> FileApexTeal.copy(alpha = 0.10f)
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(grid.innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = grid.menuSize / 2),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val iconTint = if (cleanModern || (isFlux && LocalThemeIconStyle.current == ThemeIconStyle.STANDARD)) {
                    Color.Unspecified
                } else {
                    FileApexTealDark
                }
                DeviceEntryIcon(
                    row = row,
                    modifier = Modifier.size(grid.iconSize),
                    tint = iconTint
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = row.title,
                    style = titleStyle.copy(textAlign = TextAlign.Center),
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth(),
                    onTextLayout = { titleOverflow = it.hasVisualOverflow }
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (isSendingToThis) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { liveStats.progress },
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp)),
                            color = FileApexTeal,
                            trackColor = FileApexTeal.copy(alpha = 0.2f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val statText = buildList {
                            if (liveStats.speedFormatted.isNotBlank()) add(liveStats.speedFormatted)
                            if (liveStats.etaFormatted.isNotBlank()) add(liveStats.etaFormatted)
                            val pct = (liveStats.progress * 100).toInt().coerceIn(0, 100)
                            if (pct in 1..99) add("$pct%")
                        }.joinToString(" • ")
                        Text(
                            text = if (statText.isNotBlank()) statText else stringRes("sending"),
                            style = subtitleStyle.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
                            color = FileApexTeal,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            onTextLayout = { statOverflow = it.hasVisualOverflow }
                        )
                    }
                } else if (connecting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (grid.compactTypography) 10.dp else 12.dp),
                            strokeWidth = 1.5.dp,
                            color = FileApexTeal
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringRes("connecting"),
                            style = subtitleStyle.copy(textAlign = TextAlign.Center),
                            color = FileApexTeal,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                } else {
                    Text(
                        text = DeviceListRow.localizedSubtitle(row),
                        style = subtitleStyle.copy(textAlign = TextAlign.Center),
                        color = subtitleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                        onTextLayout = { subtitleOverflow = it.hasVisualOverflow }
                    )
                }
            }
            DeviceGridMenuButton(
                row = row,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                grid = grid,
                subtitleColor = subtitleColor,
                onRename = onRename,
                onDeviceDetails = onDeviceDetails,
                onSendClipboard = onSendClipboard,
                onRemove = onRemove,
                modifier = Modifier.align(Alignment.TopEnd)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringRes("selected"),
                    tint = FileApexTeal,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(14.dp)
                )
            }
        }
    }
    if (showPopOver) {
            Popup(
                popupPositionProvider = popupPositionProvider,
                properties = PopupProperties(focusable = false)
            ) {
                DeviceCardPopOver(
                    row = row,
                    connecting = connecting,
                    onClick = onClick,
                    onRename = onRename,
                    onDeviceDetails = onDeviceDetails,
                    onSendClipboard = onSendClipboard,
                    onRemove = onRemove,
                    interactionSource = popupInteractionSource
                )
            }
        }
    }
}

@Composable
private fun DeviceGridMenuButton(
    row: DeviceListRow,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    grid: DeviceGridLayoutSpec,
    subtitleColor: Color,
    onRename: () -> Unit,
    onDeviceDetails: () -> Unit,
    onSendClipboard: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(grid.menuSize)) {
        IconButton(onClick = { onMenuOpenChange(true) }, modifier = Modifier.size(grid.menuSize)) {
            Icon(Icons.Filled.MoreHoriz, stringRes("device_options"), Modifier.size(if (grid.compactTypography) 16.dp else 18.dp), tint = subtitleColor)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
            DropdownMenuItem(text = { Text(stringRes("rename")) }, onClick = { onMenuOpenChange(false); onRename() })
            DropdownMenuItem(text = { Text(deviceMenuText(row, "device_details")) }, onClick = { onMenuOpenChange(false); onDeviceDetails() })
            if (!row.isFriend) {
                DropdownMenuItem(text = { Text(stringRes("send_clipboard")) }, onClick = { onMenuOpenChange(false); onSendClipboard() })
            }
            DropdownMenuItem(text = { Text(deviceMenuText(row, "remove")) }, onClick = { onMenuOpenChange(false); onRemove() })
        }
    }
}
