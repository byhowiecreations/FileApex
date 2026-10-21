package com.fileapex.ui.devices

import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fileapex.presentation.DeviceListRow
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun FreestyleScope.FreestyleTileMenu(
    row: DeviceListRow,
    livePos: Offset,
    actionsListState: MutableState<List<FreestyleMenuAction>>
) {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        var actionsList by actionsListState
        // Layout 3: Tiles (Directional Biasing & Quadrant Arc-Shifting Layout Engine)
        val baseRadiusDp = 96.dp
        val baseRadiusPx = with(density) { baseRadiusDp.toPx() }
        val tileItemWidthDp = 46.dp
        val tileItemHeightDp = 50.dp
        val tileItemWidthPx = with(density) { tileItemWidthDp.toPx() }
        val tileItemHeightPx = with(density) { tileItemHeightDp.toPx() }

        val centerNodeX = livePos.x + (deviceButtonSizePx / 2f)
        val centerNodeY = livePos.y + (deviceButtonSizePx / 2f)

        var draggingTileIndex by remember(row.deviceId) { mutableStateOf<Int?>(null) }
        var draggingTileAngularOffset by remember(row.deviceId) { mutableStateOf(0.0) }

        val total = actionsList.size
        val safePaddingPx = with(density) { 10.dp.toPx() }

        // Absolute boundary bounds where tile centers can exist without crossing margins
        val minSafeX = marginPx + (tileItemWidthPx / 2f)
        val maxSafeX = widthPx - marginPx - (tileItemWidthPx / 2f)
        val minSafeY = topMarginPx + (tileItemHeightPx / 2f)
        val maxSafeY = heightPx - bottomMarginPx - (tileItemHeightPx / 2f)

        val distLeft = centerNodeX - minSafeX
        val distRight = maxSafeX - centerNodeX
        val distTop = centerNodeY - minSafeY
        val distBottom = maxSafeY - centerNodeY

        val boundaryReachPx = baseRadiusPx + safePaddingPx

        val isNearLeft = distLeft < boundaryReachPx
        val isNearRight = distRight < boundaryReachPx
        val isNearTop = distTop < boundaryReachPx
        val isNearBottom = distBottom < boundaryReachPx

        val isBoundaryConstrained = isNearLeft || isNearRight || isNearTop || isNearBottom

        val isTopLeft = isNearTop && isNearLeft
        val isTopRight = isNearTop && isNearRight
        val isBottomLeft = isNearBottom && isNearLeft
        val isBottomRight = isNearBottom && isNearRight

        val (baseAngles, slotStep, itemRadiusPx) = remember(
            total, isBoundaryConstrained, isTopLeft, isTopRight, isBottomLeft, isBottomRight,
            isNearLeft, isNearRight, isNearTop, isNearBottom,
            centerNodeX, centerNodeY, minSafeX, maxSafeX, minSafeY, maxSafeY,
            baseRadiusPx, tileItemWidthPx, tileItemHeightPx
        ) {
            tileRingAngles(
                density, total, isBoundaryConstrained, isTopLeft, isTopRight, isBottomLeft, isBottomRight,
                isNearLeft, isNearRight, isNearTop, isNearBottom, centerNodeX, centerNodeY,
                minSafeX, maxSafeX, minSafeY, maxSafeY, baseRadiusPx, tileItemWidthPx, tileItemHeightPx
            )
        }

        actionsList.forEachIndexed { actionIndex, action ->
            val baseAngle = baseAngles.getOrElse(actionIndex) { (2 * PI * actionIndex / total) - (PI / 2) }
            val isDragging = draggingTileIndex == actionIndex
            val effectiveAngle = baseAngle + (if (isDragging) draggingTileAngularOffset else 0.0)

            val rawScreenX = centerNodeX + (itemRadiusPx * cos(effectiveAngle)).toFloat() - (tileItemWidthPx / 2f)
            val rawScreenY = centerNodeY + (itemRadiusPx * sin(effectiveAngle)).toFloat() - (tileItemHeightPx / 2f)

            // Boundary clamping ensures tiles never touch or cross display edges
            val safeScreenX = rawScreenX.coerceIn(marginPx, (widthPx - marginPx - tileItemWidthPx).coerceAtLeast(marginPx))
            val safeScreenY = rawScreenY.coerceIn(topMarginPx, (heightPx - bottomMarginPx - tileItemHeightPx).coerceAtLeast(topMarginPx))

            val tileOffsetDpX = with(density) { (safeScreenX - livePos.x).toDp() }
            val tileOffsetDpY = with(density) { (safeScreenY - livePos.y).toDp() }

            key(action.name) {
                Box(
                    modifier = Modifier
                        .offset(x = tileOffsetDpX, y = tileOffsetDpY)
                        .zIndex(if (isDragging) 40f else 10f)
                ) {
                    FreestyleTileRingItem(
                        action = action,
                        isEditMode = isEditMode,
                        isDragging = isDragging,
                        onDragStart = {
                            draggingTileIndex = actionIndex
                            draggingTileAngularOffset = 0.0
                        },
                        onDrag = { dragAmount ->
                            val curAngle = baseAngle + draggingTileAngularOffset
                            val tilePos = Offset(
                                x = centerNodeX + (itemRadiusPx * cos(curAngle)).toFloat(),
                                y = centerNodeY + (itemRadiusPx * sin(curAngle)).toFloat()
                            )
                            val nextPos = tilePos + dragAmount
                            val nextAngle = atan2(
                                y = (nextPos.y - centerNodeY).toDouble(),
                                x = (nextPos.x - centerNodeX).toDouble()
                            )
                            var delta = nextAngle - curAngle
                            while (delta > PI) delta -= 2 * PI
                            while (delta < -PI) delta += 2 * PI
                            val newOffset = draggingTileAngularOffset + delta
                            draggingTileAngularOffset = newOffset

                            if (newOffset > slotStep * 0.5) {
                                val currentIdx = draggingTileIndex ?: actionIndex
                                if (!isBoundaryConstrained || currentIdx < total - 1) {
                                    val targetIdx = (currentIdx + 1) % total
                                    val mutable = actionsList.toMutableList()
                                    val temp = mutable[currentIdx]
                                    mutable[currentIdx] = mutable[targetIdx]
                                    mutable[targetIdx] = temp
                                    actionsList = mutable
                                    draggingTileIndex = targetIdx
                                    draggingTileAngularOffset = newOffset - slotStep
                                    val encoded = FreestyleMenuAction.encodeOrder(mutable)
                                    syncOrderToAllDevices(encoded)
                                }
                            } else if (newOffset < -slotStep * 0.5) {
                                val currentIdx = draggingTileIndex ?: actionIndex
                                if (!isBoundaryConstrained || currentIdx > 0) {
                                    val targetIdx = if (currentIdx > 0) currentIdx - 1 else total - 1
                                    val mutable = actionsList.toMutableList()
                                    val temp = mutable[currentIdx]
                                    mutable[currentIdx] = mutable[targetIdx]
                                    mutable[targetIdx] = temp
                                    actionsList = mutable
                                    draggingTileIndex = targetIdx
                                    draggingTileAngularOffset = newOffset + slotStep
                                    val encoded = FreestyleMenuAction.encodeOrder(mutable)
                                    syncOrderToAllDevices(encoded)
                                }
                            }
                        },
                        onDragEnd = {
                            draggingTileIndex = null
                            draggingTileAngularOffset = 0.0
                        },
                        onClick = {
                            if (!isEditMode) {
                                activeDeviceId = null
                                when (action) {
                                    FreestyleMenuAction.BROWSE -> handleBrowseDevice(row.deviceId, row.deviceName)
                                    FreestyleMenuAction.CLIPBOARD -> onSendClipboardDevice(row.deviceId)
                                    FreestyleMenuAction.INFO -> onDeviceDetails(row.deviceId)
                                    FreestyleMenuAction.RENAME -> onRenameDevice(row.deviceId, row.deviceName)
                                    FreestyleMenuAction.REMOVE -> onRemoveDevice(row.deviceId, row.deviceName)
                                }
                            }
                        }
                    )
                }
            }
        }
    } } } }
}


/**
 * Where the tiles of a ring sit: their angles, the step between them and the ring radius. A device near an edge or
 * corner gets a fan into the open side instead of a full circle, widened when the tiles would touch.
 */
internal fun tileRingAngles(
    density: Density,
    total: Int,
    isBoundaryConstrained: Boolean,
    isTopLeft: Boolean,
    isTopRight: Boolean,
    isBottomLeft: Boolean,
    isBottomRight: Boolean,
    isNearLeft: Boolean,
    isNearRight: Boolean,
    isNearTop: Boolean,
    isNearBottom: Boolean,
    centerNodeX: Float,
    centerNodeY: Float,
    minSafeX: Float,
    maxSafeX: Float,
    minSafeY: Float,
    maxSafeY: Float,
    baseRadiusPx: Float,
    tileItemWidthPx: Float,
    tileItemHeightPx: Float
): Triple<List<Double>, Double, Float> {
    return if (!isBoundaryConstrained || total <= 1) {
        val step = 2 * PI / total
        val startAngle = -PI / 2
        val list = (0 until total).map { idx ->
            startAngle + (idx * step)
        }
        Triple(list, step, baseRadiusPx)
    } else {
        var startAngle: Double
        var endAngle: Double

        when {
            isTopLeft -> {
                // Top-Left Corner: open quadrant is Down-Right (angles ~ -10° to ~ 115°)
                val ratioY = ((minSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                val minThetaY = asin(ratioY.toDouble())
                val ratioX = ((minSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                val maxThetaX = acos(ratioX.toDouble())
                startAngle = max(-0.06 * PI, minThetaY)
                endAngle = min(0.66 * PI, maxThetaX)
            }
            isTopRight -> {
                // Top-Right Corner: open quadrant is Down-Left (angles ~ 65° to ~ 190°)
                val ratioY = ((minSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                val maxThetaY = PI - asin(ratioY.toDouble())
                val ratioX = ((maxSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                val minThetaX = acos(ratioX.toDouble())
                startAngle = max(0.34 * PI, minThetaX)
                endAngle = min(1.06 * PI, maxThetaY)
            }
            isBottomLeft -> {
                // Bottom-Left Corner: open quadrant is Up-Right (angles ~ -115° to ~ 10°)
                val ratioY = ((maxSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                val maxThetaY = asin(ratioY.toDouble())
                val ratioX = ((minSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                val minThetaX = -acos(ratioX.toDouble())
                startAngle = max(-0.66 * PI, minThetaX)
                endAngle = min(0.06 * PI, maxThetaY)
            }
            isBottomRight -> {
                // Bottom-Right Corner: open quadrant is Up-Left (angles ~ -190° to ~ -65°)
                val ratioY = ((maxSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                val minThetaY = -PI - asin(ratioY.toDouble())
                val ratioX = ((maxSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                val maxThetaX = -acos(ratioX.toDouble())
                startAngle = max(-1.06 * PI, minThetaY)
                endAngle = min(-0.34 * PI, maxThetaX)
            }
            isNearTop -> {
                // Top Edge: fan downward into bottom hemisphere
                val ratioY = ((minSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                val minThetaY = asin(ratioY.toDouble())
                startAngle = max(0.10 * PI, minThetaY)
                endAngle = min(0.90 * PI, PI - minThetaY)
            }
            isNearBottom -> {
                // Bottom Edge: fan upward into top hemisphere
                val ratioY = ((maxSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                val maxThetaY = asin(ratioY.toDouble())
                startAngle = max(-0.90 * PI, -PI - maxThetaY)
                endAngle = min(-0.10 * PI, maxThetaY)
            }
            isNearLeft -> {
                // Left Edge: fan rightward into right hemisphere
                val ratioX = ((minSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                val maxThetaX = acos(ratioX.toDouble())
                startAngle = max(-0.40 * PI, -maxThetaX)
                endAngle = min(0.40 * PI, maxThetaX)
            }
            else -> {
                // Right Edge: fan leftward into left hemisphere
                val ratioX = ((maxSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                val minThetaX = acos(ratioX.toDouble())
                startAngle = max(0.60 * PI, minThetaX)
                endAngle = min(1.40 * PI, 2 * PI - minThetaX)
            }
        }

        // Ensure guaranteed forward sweep
        if (endAngle <= startAngle + 0.15) {
            val mid = (startAngle + endAngle) / 2.0
            startAngle = mid - (PI / 4.0)
            endAngle = mid + (PI / 4.0)
        }

        val span = endAngle - startAngle
        val step = if (total > 1) span / (total - 1) else 0.0

        // Vector Repulsion & Spacing: Ensure fixed padding gaps between adjacent tiles
        val minTileGapPx = with(density) { 8.dp.toPx() }
        val neededTileSpacingPx = max(tileItemWidthPx, tileItemHeightPx) + minTileGapPx
        val chordAtBaseR = (2 * baseRadiusPx * sin(step / 2.0)).toFloat().coerceAtLeast(1f)

        val resolvedRadiusPx = if (chordAtBaseR < neededTileSpacingPx && step > 0.02) {
            val expandedR = neededTileSpacingPx / (2 * sin(step / 2.0).toFloat())
            expandedR.coerceIn(baseRadiusPx, baseRadiusPx * 1.30f)
        } else {
            baseRadiusPx
        }

        val list = (0 until total).map { idx ->
            startAngle + (idx * step)
        }
        Triple(list, step, resolvedRadiusPx)
    }
}
