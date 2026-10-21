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
import androidx.compose.material3.Button
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal class FreestylePlacement(
    val minDeviceClearancePx: Float,
    val nameOffsetPx: Float,
    val minDeviceX: Float,
    val maxDeviceX: Float,
    val minDeviceY: Float,
    val maxDeviceY: Float,
    val placedCenters: MutableList<Offset>,
    val optCenterX: Float,
    val optCenterY: Float,
    val optRadiusPx: Float
)

@Composable
internal fun FreestyleScope.FreestyleDeviceNodes() {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        val minDeviceClearancePx = with(density) { 92.dp.toPx() }
        val nameOffsetPx = with(density) { 36.dp.toPx() }
        val minDeviceX = marginPx
        val maxDeviceX = (widthPx - marginPx - deviceButtonSizePx).coerceAtLeast(marginPx)
        val minDeviceY = topMarginPx
        val maxDeviceY = (heightPx - bottomMarginPx - deviceButtonSizePx - nameOffsetPx).coerceAtLeast(topMarginPx)
        // Track occupied centers for non-overlapping placement
        val placedCenters = mutableListOf<Offset>()
        val currentOptPos = liveOptionsPos
        val optCenterX = currentOptPos.x + (optionsButtonWidthPx / 2f)
        val optCenterY = currentOptPos.y + (optionsButtonHeightPx / 2f)
        val optRadiusPx = (optionsButtonWidthPx / 2f) + (deviceButtonSizePx / 2f) + 8f
        // Pre-populate placedCenters with devices that already have a persisted / user-assigned position
        deviceRows.forEach { r ->
            val scopedKey = "$layoutScopePrefix${r.deviceId}"
            val legacyKey = "$legacyScopePrefix${r.deviceId}"
            val cached = when (freestyleMode) {
                FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardNodeOffsets[scopedKey]
                    ?: persistedCardNodeOffsets[legacyKey]
                    ?: persistedCardNodeOffsets[r.deviceId]
                FreestyleLayoutMode.CARDS_VERTICAL -> persistedCardVerticalNodeOffsets[scopedKey]
                    ?: persistedCardNodeOffsets[scopedKey]
                    ?: persistedCardVerticalNodeOffsets[legacyKey]
                    ?: persistedCardNodeOffsets[legacyKey]
                    ?: persistedCardVerticalNodeOffsets[r.deviceId]
                    ?: persistedCardNodeOffsets[r.deviceId]
                FreestyleLayoutMode.TILES -> persistedTileNodeOffsets[scopedKey]
                    ?: persistedTileNodeOffsets[legacyKey]
                    ?: persistedTileNodeOffsets[r.deviceId]
            }
            val sfx = cached?.first ?: (if (freestyleMode == FreestyleLayoutMode.TILES) r.tilePosX else r.cardPosX)
            val sfy = cached?.second ?: (if (freestyleMode == FreestyleLayoutMode.TILES) r.tilePosY else r.cardPosY)
            if (sfx != null && sfy != null) {
                val cx = (sfx * widthPx).coerceIn(minDeviceX + deviceButtonSizePx / 2f, maxDeviceX + deviceButtonSizePx / 2f)
                val cy = (sfy * heightPx).coerceIn(minDeviceY + deviceButtonSizePx / 2f, maxDeviceY + deviceButtonSizePx / 2f)
                placedCenters.add(Offset(cx, cy))
            }
        }
        val placement = FreestylePlacement(minDeviceClearancePx, nameOffsetPx, minDeviceX, maxDeviceX, minDeviceY, maxDeviceY, placedCenters, optCenterX, optCenterY, optRadiusPx)
        deviceRows.forEachIndexed { index, row ->
            FreestyleDeviceNode(index, row, placement)
        }
    } } } }
}

@Composable
internal fun FreestyleScope.FreestyleDeviceNode(index: Int, row: DeviceListRow, placement: FreestylePlacement) {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        with(placement) {
            val scopedDeviceId = "$layoutScopePrefix${row.deviceId}"
            val legacyDeviceId = "$legacyScopePrefix${row.deviceId}"
            val cachedOffset = when (freestyleMode) {
                FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardNodeOffsets[scopedDeviceId]
                    ?: persistedCardNodeOffsets[legacyDeviceId]
                    ?: persistedCardNodeOffsets[row.deviceId]
                FreestyleLayoutMode.CARDS_VERTICAL -> persistedCardVerticalNodeOffsets[scopedDeviceId]
                    ?: persistedCardNodeOffsets[scopedDeviceId]
                    ?: persistedCardVerticalNodeOffsets[legacyDeviceId]
                    ?: persistedCardNodeOffsets[legacyDeviceId]
                    ?: persistedCardVerticalNodeOffsets[row.deviceId]
                    ?: persistedCardNodeOffsets[row.deviceId]
                FreestyleLayoutMode.TILES -> persistedTileNodeOffsets[scopedDeviceId]
                    ?: persistedTileNodeOffsets[legacyDeviceId]
                    ?: persistedTileNodeOffsets[row.deviceId]
            }
            val savedFractionX = cachedOffset?.first ?: (if (freestyleMode == FreestyleLayoutMode.TILES) row.tilePosX else row.cardPosX)
            val savedFractionY = cachedOffset?.second ?: (if (freestyleMode == FreestyleLayoutMode.TILES) row.tilePosY else row.cardPosY)

            val livePos = rememberFreestyleNodePosition(index, row, placement, scopedDeviceId, savedFractionX, savedFractionY)

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

            // Single Device Node Box (Button + Label below)
            Box(
                modifier = Modifier
                    .offset { IntOffset(livePos.x.roundToInt(), livePos.y.roundToInt()) }
                    .zIndex(elementZIndex)
                    .alpha(deviceAlpha)
            ) {
                // Circular Device Action Button (Representing device type via DeviceEntryIcon)
                Box(
                    modifier = Modifier
                        .size(deviceButtonSizeDp)
                        .pointerInput(scopedDeviceId, freestyleMode, widthPx, heightPx) {
                            detectDragGestures(
                                onDragStart = {
                                    if (!isEditMode) {
                                        activeDeviceId = null
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val cur = liveNodePositions[scopedDeviceId] ?: livePos
                                    val nextX = (cur.x + dragAmount.x).coerceIn(minDeviceX, maxDeviceX)
                                    val nextY = (cur.y + dragAmount.y).coerceIn(minDeviceY, maxDeviceY)
                                    liveNodePositions[scopedDeviceId] = Offset(nextX, nextY)
                                },
                                onDragEnd = {
                                    val finalPos = liveNodePositions[scopedDeviceId] ?: livePos
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

                            // Online / Status Dot
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

                // Device Name placed directly below the button
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

                // Expanded Context Actions for this device
                if (isExpanded) {
                    FreestyleDeviceMenu(row, livePos)
                }
            }
        }
    } } } }
}


@Composable
internal fun FreestyleScope.rememberFreestyleNodePosition(
    index: Int,
    row: DeviceListRow,
    placement: FreestylePlacement,
    scopedDeviceId: String,
    savedFractionX: Float?,
    savedFractionY: Float?
): Offset {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        with(placement) {
        val defaultPos = remember(index, deviceRows.size, widthPx, heightPx) {
            val count = deviceRows.size
            if (count == 1) {
                Offset(
                    x = (widthPx - deviceButtonSizePx) / 2f,
                    y = (heightPx * 0.40f) - (deviceButtonSizePx / 2f)
                )
            } else {
                val angle = (2 * PI * index / count) - (PI / 2)
                val rx = (widthPx * 0.32f).coerceAtLeast(100f)
                val ry = (heightPx * 0.28f).coerceAtLeast(100f)
                val cx = widthPx / 2f
                val cy = heightPx * 0.40f
                Offset(
                    x = (cx + (rx * cos(angle)).toFloat() - (deviceButtonSizePx / 2f)),
                    y = (cy + (ry * sin(angle)).toFloat() - (deviceButtonSizePx / 2f))
                )
            }
        }

        return liveNodePositions.getOrPut(scopedDeviceId) {
            if (savedFractionX != null && savedFractionY != null) {
                val initialX = savedFractionX * widthPx - (deviceButtonSizePx / 2f)
                val initialY = savedFractionY * heightPx - (deviceButtonSizePx / 2f)
                Offset(
                    initialX.coerceIn(minDeviceX, maxDeviceX),
                    initialY.coerceIn(minDeviceY, maxDeviceY)
                )
            } else {
                // For new/unassigned devices: find a non-overlapping location
                fun isClear(candidate: Offset): Boolean {
                    val cCenterX = candidate.x + (deviceButtonSizePx / 2f)
                    val cCenterY = candidate.y + (deviceButtonSizePx / 2f)
                    val dxOpt = cCenterX - optCenterX
                    val dyOpt = cCenterY - optCenterY
                    if (kotlin.math.sqrt(dxOpt * dxOpt + dyOpt * dyOpt) < optRadiusPx) return false

                    return placedCenters.none { placed ->
                        val dx = cCenterX - placed.x
                        val dy = cCenterY - placed.y
                        kotlin.math.sqrt(dx * dx + dy * dy) < minDeviceClearancePx
                    }
                }

                val initialDefault = Offset(
                    defaultPos.x.coerceIn(minDeviceX, maxDeviceX),
                    defaultPos.y.coerceIn(minDeviceY, maxDeviceY)
                )

                val resolved = if (isClear(initialDefault)) {
                    initialDefault
                } else {
                    var best = initialDefault
                    var maxMinDist = -1f
                    val centerCanvasX = widthPx / 2f
                    val centerCanvasY = heightPx * 0.40f
                    val candidateRadii = listOf(
                        (widthPx * 0.32f).coerceAtLeast(100f),
                        (widthPx * 0.22f).coerceAtLeast(80f),
                        (widthPx * 0.42f).coerceAtLeast(120f)
                    )
                    val steps = 24
                    var found = false

                    for (r in candidateRadii) {
                        if (found) break
                        for (s in 0 until steps) {
                            val angle = (2.0 * PI * s / steps) - (PI / 2.0)
                            val cx = (centerCanvasX + (r * cos(angle)).toFloat() - (deviceButtonSizePx / 2f)).coerceIn(minDeviceX, maxDeviceX)
                            val cy = (centerCanvasY + (r * sin(angle)).toFloat() - (deviceButtonSizePx / 2f)).coerceIn(minDeviceY, maxDeviceY)
                            val candidate = Offset(cx, cy)

                            if (isClear(candidate)) {
                                best = candidate
                                found = true
                                break
                            } else {
                                val cCenterX = candidate.x + (deviceButtonSizePx / 2f)
                                val cCenterY = candidate.y + (deviceButtonSizePx / 2f)
                                val minDist = if (placedCenters.isEmpty()) Float.MAX_VALUE else {
                                    placedCenters.minOf { placed ->
                                        val dx = cCenterX - placed.x
                                        val dy = cCenterY - placed.y
                                        kotlin.math.sqrt(dx * dx + dy * dy)
                                    }
                                }
                                if (minDist > maxMinDist) {
                                    maxMinDist = minDist
                                    best = candidate
                                }
                            }
                        }
                    }
                    best
                }
                placedCenters.add(Offset(resolved.x + deviceButtonSizePx / 2f, resolved.y + deviceButtonSizePx / 2f))
                resolved
            }
        }
        }
    } } } }
}
