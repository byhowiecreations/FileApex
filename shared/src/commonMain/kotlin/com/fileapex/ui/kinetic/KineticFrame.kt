package com.fileapex.ui.kinetic

import com.fileapex.presentation.DeviceListRow
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.di.FileApexServices
import com.fileapex.ui.EllipticalOrbitConfig
import com.fileapex.ui.deviceLayoutBucket
import com.fileapex.ui.kineticStoredNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal class KineticFrame(
    val widthPx: Float,
    val heightPx: Float,
    val centerX: Float,
    val centerY: Float,
    val hubCenter: Offset,
    val nodeCount: Int,
    val baseRadiusPx: Float,
    val orbitConfigs: List<EllipticalOrbitConfig>,
    val staticNodePositions: List<Offset>,
    val layoutBucket: String,
    val persistedNodeOffsets: Map<String, Pair<Float, Float>>,
    val effectiveNodePositions: List<Offset>,
    val showConnectedLines: Boolean,
    val kineticStyle: KineticStyle,
    val hubSizeDp: Dp,
    val hubPx: Float,
    val density: Density
)

@Composable
internal fun rememberKineticFrame(inputs: KineticInputs, density: Density, maxWidth: Dp, maxHeight: Dp): KineticFrame {
    with(inputs) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val centerX = widthPx / 2f
        val centerY = heightPx * 0.50f
        val hubCenter = Offset(centerX, centerY)

        val nodeCount = deviceRows.size
        val minR = with(density) { 120.dp.toPx() }
        val maxR = with(density) { 240.dp.toPx() }
        val baseRadiusPx = (minOf(widthPx, heightPx) * 0.36f).coerceIn(minR, maxR)

        val orbitConfigs = remember {
            listOf(
                EllipticalOrbitConfig(rotationDeg = -28f, scaleX = 1.28f, scaleY = 0.68f),
                EllipticalOrbitConfig(rotationDeg = 35f, scaleX = 1.42f, scaleY = 0.78f),
                EllipticalOrbitConfig(rotationDeg = 115f, scaleX = 1.58f, scaleY = 0.88f),
                EllipticalOrbitConfig(rotationDeg = 155f, scaleX = 1.32f, scaleY = 0.64f)
            )
        }

        val staticNodePositions = remember(deviceRows, widthPx, heightPx, baseRadiusPx) {
            kineticStaticNodePositions(density, deviceRows, widthPx, heightPx, baseRadiusPx, orbitConfigs, nodeCount, centerX, centerY, hubCenter)
        }

        val layoutBucket = deviceLayoutBucket(maxWidth, maxHeight)

        val persistedNodeOffsets by FileApexServices.settings.kineticNodeOffsets.collectAsState()

        val effectiveNodePositions = remember(
            staticNodePositions,
            persistedNodeOffsets,
            deviceRows,
            widthPx,
            heightPx,
            layoutBucket,
            baseRadiusPx
        ) {
            val marginPx = with(density) { 70.dp.toPx() }
            val topMarginPx = with(density) { 55.dp.toPx() }
            val minDistancePx = with(density) { 135.dp.toPx() }
            val minHubDistancePx = with(density) { 138.dp.toPx() }

            val placedPositions = arrayOfNulls<Offset>(deviceRows.size)

            // Place all devices with user-assigned or persisted offsets first
            deviceRows.forEachIndexed { index, row ->
                val stored = kineticStoredNode(
                    offsets = persistedNodeOffsets,
                    bucket = layoutBucket,
                    deviceId = row.deviceId,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    marginPx = marginPx,
                    topMarginPx = topMarginPx
                )
                if (stored != null) {
                    val rawX = if (stored.fractional) stored.x * widthPx else stored.x
                    val rawY = if (stored.fractional) stored.y * heightPx else stored.y
                    val clampedX = if (widthPx > marginPx * 2) rawX.coerceIn(marginPx, widthPx - marginPx) else rawX
                    val clampedY = if (heightPx > topMarginPx + marginPx) rawY.coerceIn(topMarginPx, heightPx - marginPx) else rawY
                    placedPositions[index] = Offset(clampedX, clampedY)
                } else if (layoutBucket.startsWith("land-")) {
                    val deltaKey = if (layoutBucket.endsWith("-cmp")) "cmp:${row.deviceId}" else "exp:${row.deviceId}"
                    val legacy = persistedNodeOffsets[deltaKey] ?: persistedNodeOffsets[row.deviceId]
                    if (legacy != null) {
                        val staticPos = staticNodePositions.getOrElse(index) { Offset(centerX, centerY) }
                        placedPositions[index] = Offset(
                            (staticPos.x + legacy.first).coerceIn(marginPx, widthPx - marginPx),
                            (staticPos.y + legacy.second).coerceIn(topMarginPx, heightPx - marginPx)
                        )
                    }
                }
            }

            // Next, place unassigned or newly joined devices into non-overlapping areas
            deviceRows.indices.map { index ->
                val existing = placedPositions[index]
                if (existing != null) return@map existing

                val staticPos = staticNodePositions.getOrElse(index) { Offset(centerX, centerY) }
                val currentPlaced = placedPositions.filterNotNull()

                fun isClear(pos: Offset): Boolean {
                    val hubDx = pos.x - centerX
                    val hubDy = pos.y - centerY
                    if (kotlin.math.sqrt(hubDx * hubDx + hubDy * hubDy) < minHubDistancePx) return false
                    return currentPlaced.none { placed ->
                        val dx = pos.x - placed.x
                        val dy = pos.y - placed.y
                        kotlin.math.sqrt(dx * dx + dy * dy) < minDistancePx
                    }
                }

                val resolvedPos = if (isClear(staticPos)) {
                    staticPos
                } else {
                    var bestCandidate: Offset = staticPos
                    var maxMinDistance = -1f
                    val candidateRadii = listOf(baseRadiusPx, baseRadiusPx * 0.82f, baseRadiusPx * 1.25f, baseRadiusPx * 1.45f)
                    val steps = 24
                    var foundNonOverlapping = false

                    for (r in candidateRadii) {
                        if (foundNonOverlapping) break
                        for (s in 0 until steps) {
                            val angle = (2.0 * PI * s / steps) - (PI / 2.0)
                            val candX = (centerX + (r * cos(angle)).toFloat()).coerceIn(marginPx, widthPx - marginPx)
                            val candY = (centerY + (r * sin(angle)).toFloat()).coerceIn(topMarginPx, heightPx - marginPx)
                            val candidate = Offset(candX, candY)

                            val hubDx = candX - centerX
                            val hubDy = candY - centerY
                            val hubDist = kotlin.math.sqrt(hubDx * hubDx + hubDy * hubDy)
                            if (hubDist < minHubDistancePx) continue

                            val minOtherDist = if (currentPlaced.isEmpty()) Float.MAX_VALUE else {
                                currentPlaced.minOf { placed ->
                                    val dx = candidate.x - placed.x
                                    val dy = candidate.y - placed.y
                                    kotlin.math.sqrt(dx * dx + dy * dy)
                                }
                            }

                            if (minOtherDist >= minDistancePx) {
                                bestCandidate = candidate
                                foundNonOverlapping = true
                                break
                            } else if (minOtherDist > maxMinDistance) {
                                maxMinDistance = minOtherDist
                                bestCandidate = candidate
                            }
                        }
                    }
                    bestCandidate
                }

                placedPositions[index] = resolvedPos
                resolvedPos
            }
        }

        val showConnectedLines by FileApexServices.settings.kineticSphereConnectedLinesEnabled.collectAsState()
        val kineticStyle = LocalKineticStyle.current
        val hubSizeDp = 110.dp
        val hubPx = with(density) { hubSizeDp.toPx() }
        return KineticFrame(widthPx, heightPx, centerX, centerY, hubCenter, nodeCount, baseRadiusPx, orbitConfigs, staticNodePositions, layoutBucket, persistedNodeOffsets, effectiveNodePositions, showConnectedLines, kineticStyle, hubSizeDp, hubPx, density)
    }
}


internal fun kineticStaticNodePositions(
    density: Density,
    deviceRows: List<DeviceListRow>,
    widthPx: Float,
    heightPx: Float,
    baseRadiusPx: Float,
    orbitConfigs: List<EllipticalOrbitConfig>,
    nodeCount: Int,
    centerX: Float,
    centerY: Float,
    hubCenter: Offset
): List<Offset> {
    val marginPx = with(density) { 70.dp.toPx() }
    val topMarginPx = with(density) { 55.dp.toPx() }
    val minDistancePx = with(density) { 135.dp.toPx() }
    val minHubDistancePx = with(density) { 138.dp.toPx() }

    val rawPositions = deviceRows.mapIndexed { index, _ ->
        val config = orbitConfigs[index % orbitConfigs.size]
        val radiusMultiplier = if (nodeCount <= 4) 1.0f else (if (index % 2 == 0) 0.85f else 1.38f)
        val angleStep = (2.0 * PI / nodeCount)
        val baseAngle = (angleStep * index) - (PI / 2.0)

        val rx = baseRadiusPx * config.scaleX * radiusMultiplier
        val ry = baseRadiusPx * config.scaleY * radiusMultiplier

        val localX = rx * cos(baseAngle)
        val localY = ry * sin(baseAngle)

        val rad = config.rotationDeg * (PI / 180.0)
        val rotX = (localX * cos(rad) - localY * sin(rad)).toFloat()
        val rotY = (localX * sin(rad) + localY * cos(rad)).toFloat()

        var cx = centerX + rotX
        var cy = centerY + rotY

        val hubDx = cx - centerX
        val hubDy = cy - centerY
        val hubDist = kotlin.math.sqrt(hubDx * hubDx + hubDy * hubDy)
        if (hubDist < minHubDistancePx && hubDist > 0.001f) {
            val factor = minHubDistancePx / hubDist
            cx = centerX + (hubDx * factor)
            cy = centerY + (hubDy * factor)
        }

        cx = cx.coerceIn(marginPx, widthPx - marginPx)
        cy = cy.coerceIn(topMarginPx, heightPx - marginPx)
        Offset(cx, cy)
    }.toMutableList()

    for (iter in 0 until 6) {
        for (i in 0 until rawPositions.size) {
            for (j in i + 1 until rawPositions.size) {
                val posI = rawPositions[i]
                val posJ = rawPositions[j]
                val dx = posJ.x - posI.x
                val dy = posJ.y - posI.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist < minDistancePx && dist > 0.001f) {
                    val overlap = (minDistancePx - dist) / 2f
                    val nx = dx / dist
                    val ny = dy / dist
                    val newIx = (posI.x - nx * overlap).coerceIn(marginPx, widthPx - marginPx)
                    val newIy = (posI.y - ny * overlap).coerceIn(topMarginPx, heightPx - marginPx)
                    val newJx = (posJ.x + nx * overlap).coerceIn(marginPx, widthPx - marginPx)
                    val newJy = (posJ.y + ny * overlap).coerceIn(topMarginPx, heightPx - marginPx)
                    rawPositions[i] = Offset(newIx, newIy)
                    rawPositions[j] = Offset(newJx, newJy)
                }
            }
        }
    }
    return rawPositions
}
