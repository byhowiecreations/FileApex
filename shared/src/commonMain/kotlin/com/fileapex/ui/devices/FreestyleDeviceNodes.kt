package com.fileapex.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.di.FileApexServices
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.DeviceEntryIcon
import com.fileapex.ui.deviceIconFillsOrb
import com.fileapex.ui.dnd.deviceFileDropTarget
import kotlin.math.roundToInt

@Composable
internal fun FreestyleScope.FreestyleDeviceNodes() {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        val primaryOffsets = when (freestyleMode) {
            FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardNodeOffsets
            FreestyleLayoutMode.CARDS_VERTICAL -> persistedCardVerticalNodeOffsets
            FreestyleLayoutMode.TILES -> persistedTileNodeOffsets
        }
        val fallbackOffsets = if (freestyleMode == FreestyleLayoutMode.CARDS_VERTICAL) {
            persistedCardNodeOffsets
        } else {
            emptyMap()
        }
        val resolvedPositions = remember(
            deviceRows,
            freestyleMode,
            layoutScopePrefix,
            legacyScopePrefix,
            widthPx,
            heightPx,
            primaryOffsets,
            fallbackOffsets,
            liveOptionsPos,
            currentPinnedActionsMap
        ) {
            resolveFreestyleNodePositions(
                density = density,
                deviceRows = deviceRows,
                widthPx = widthPx,
                heightPx = heightPx,
                layoutScopePrefix = layoutScopePrefix,
                legacyScopePrefix = legacyScopePrefix,
                persistedOffsets = primaryOffsets,
                useTileRowFallback = freestyleMode == FreestyleLayoutMode.TILES,
                optionsPos = liveOptionsPos,
                pinnedActions = currentPinnedActionsMap,
                fallbackOffsets = fallbackOffsets
            )
        }

        deviceRows.forEach { row ->
            val scopedDeviceId = "$layoutScopePrefix${row.deviceId}"
            val resolved = resolvedPositions[scopedDeviceId] ?: Offset(marginPx, topMarginPx)
            FreestyleDeviceNode(row, scopedDeviceId, resolved)
        }
    } } } }
}

@Composable
internal fun FreestyleScope.FreestyleDeviceNode(
    row: DeviceListRow,
    scopedDeviceId: String,
    resolvedPos: Offset
) {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        val nameOffsetPx = with(density) { 36.dp.toPx() }
        val minDeviceX = marginPx
        val maxDeviceX = (widthPx - marginPx - deviceButtonSizePx).coerceAtLeast(marginPx)
        val minDeviceY = topMarginPx
        val maxDeviceY = (heightPx - bottomMarginPx - deviceButtonSizePx - nameOffsetPx).coerceAtLeast(topMarginPx)

        val livePos = liveNodePositions[scopedDeviceId] ?: resolvedPos

        val isExpanded = activeDeviceId == row.deviceId
        val isConnecting = connectingDeviceId == row.deviceId
        val isSelected = selectedDeviceId == row.deviceId
        val statusColor = when {
            isConnecting -> Color(0xFF00E5FF)
            row.online -> Color(0xFF00E676)
            else -> Color(0xFFFFB300)
        }

        val elementZIndex = when {
            isExpanded -> 100f
            isSelected -> 50f
            else -> 20f
        }

        var dropHover by remember { mutableStateOf(false) }
        val deviceAlpha = if (activeDeviceId != null && !isExpanded) 0.35f else 1f

        Box(
            modifier = Modifier
                .offset { IntOffset(livePos.x.roundToInt(), livePos.y.roundToInt()) }
                .zIndex(elementZIndex)
                .alpha(deviceAlpha)
        ) {
            Box(
                modifier = Modifier
                    .size(deviceButtonSizeDp)
                    .pointerInput(scopedDeviceId, freestyleMode, widthPx, heightPx, resolvedPos) {
                        detectDragGestures(
                            onDragStart = {
                                if (!isEditMode) {
                                    activeDeviceId = null
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val cur = liveNodePositions[scopedDeviceId] ?: resolvedPos
                                val nextX = (cur.x + dragAmount.x).coerceIn(minDeviceX, maxDeviceX)
                                val nextY = (cur.y + dragAmount.y).coerceIn(minDeviceY, maxDeviceY)
                                liveNodePositions[scopedDeviceId] = Offset(nextX, nextY)
                            },
                            onDragCancel = {
                                liveNodePositions.remove(scopedDeviceId)
                            },
                            onDragEnd = {
                                val finalPos = liveNodePositions.remove(scopedDeviceId) ?: resolvedPos
                                val centerX = finalPos.x + (deviceButtonSizePx / 2f)
                                val centerY = finalPos.y + (deviceButtonSizePx / 2f)
                                val fracX = (centerX / widthPx).coerceIn(0f, 1f)
                                val fracY = (centerY / heightPx).coerceIn(0f, 1f)
                                FileApexServices.settings.setFreestyleNodeOffset(freestyleMode, scopedDeviceId, fracX, fracY)
                            }
                        )
                    }
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .deviceFileDropTarget(
                            enabled = true,
                            onHoverChange = { dropHover = it },
                            onFilesDropped = { paths -> onFilesDropped(row.deviceId, paths) }
                        )
                        .clickable {
                            activeDeviceId = if (isExpanded && !isEditMode) null else row.deviceId
                        },
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = BorderStroke(
                        width = if (isExpanded || isSelected || dropHover) 2.5.dp else 1.5.dp,
                        color = if (dropHover) Color(0xFF00E676) else (if (isExpanded) Color(0xFF64B5F6) else statusColor)
                    ),
                    shadowElevation = if (isExpanded || dropHover) 14.dp else 6.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        if (dropHover) Color(0xCC00E676) else (if (isExpanded) Color(0xCC0D3859) else Color(0xDD0D1E2D)),
                                        Color(0xEE050B12)
                                    )
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val iconStyle = LocalThemeIconStyle.current
                        val buttonBorderWidth = if (isExpanded || isSelected || dropHover) 2.5.dp else 1.5.dp
                        DeviceEntryIcon(
                            row = row,
                            modifier = if (deviceIconFillsOrb(iconStyle)) {
                                Modifier.fillMaxSize().padding(buttonBorderWidth)
                            } else {
                                Modifier.size(32.dp)
                            },
                            tint = if (isExpanded) Color(0xFF64B5F6) else Color.White
                        )

                        if (isEditMode) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DragHandle,
                                    contentDescription = null,
                                    tint = Color(0xFF64B5F6),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 6.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                                .border(BorderStroke(1.5.dp, Color(0xFF0D1E2D)), CircleShape)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            -with(density) { 16.dp.toPx() }.roundToInt(),
                            (deviceButtonSizePx + with(density) { 4.dp.toPx() }).roundToInt()
                        )
                    }
                    .width(deviceButtonSizeDp + 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = row.deviceName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        lineHeight = 13.sp
                    ),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            if (isExpanded) {
                FreestyleDeviceMenu(row, livePos)
            }
        }
    } } } }
}
