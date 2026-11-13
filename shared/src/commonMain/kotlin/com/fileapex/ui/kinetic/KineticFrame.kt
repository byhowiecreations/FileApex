package com.fileapex.ui.kinetic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.di.FileApexServices
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.EllipticalOrbitConfig
import com.fileapex.ui.deviceLayoutBucket
import com.fileapex.ui.kineticStoredNode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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
            resolveKineticNodePositions(
                density = density,
                deviceRows = deviceRows,
                widthPx = widthPx,
                heightPx = heightPx,
                layoutBucket = layoutBucket,
                persistedNodeOffsets = persistedNodeOffsets
            )
        }

        val showConnectedLines by FileApexServices.settings.kineticSphereConnectedLinesEnabled.collectAsState()
        val kineticStyle = LocalKineticStyle.current
        val hubSizeDp = 110.dp
        val hubPx = with(density) { hubSizeDp.toPx() }
        return KineticFrame(
            widthPx,
            heightPx,
            centerX,
            centerY,
            hubCenter,
            nodeCount,
            baseRadiusPx,
            orbitConfigs,
            staticNodePositions,
            layoutBucket,
            persistedNodeOffsets,
            effectiveNodePositions,
            showConnectedLines,
            kineticStyle,
            hubSizeDp,
            hubPx,
            density
        )
    }
}

internal fun resolveKineticNodePositions(
    density: Density,
    deviceRows: List<DeviceListRow>,
    widthPx: Float,
    heightPx: Float,
    layoutBucket: String,
    persistedNodeOffsets: Map<String, Pair<Float, Float>>,
    hasFriendsChip: Boolean = false
): List<Offset> {
    val nodeCount = deviceRows.size
    if (nodeCount == 0) return emptyList()

    val centerX = widthPx / 2f
    val centerY = heightPx * 0.50f
    val hubCenter = Offset(centerX, centerY)
    val minR = with(density) { 120.dp.toPx() }
    val maxR = with(density) { 240.dp.toPx() }
    val baseRadiusPx = (minOf(widthPx, heightPx) * 0.36f).coerceIn(minR, maxR)

    val staticNodePositions = kineticStaticNodePositions(
        density = density,
        deviceRows = deviceRows,
        widthPx = widthPx,
        heightPx = heightPx,
        baseRadiusPx = baseRadiusPx,
        orbitConfigs = emptyList(),
        nodeCount = nodeCount,
        centerX = centerX,
        centerY = centerY,
        hubCenter = hubCenter
    )

    val marginPx = with(density) { 64.dp.toPx() }.coerceAtMost(widthPx * 0.16f)
    val topMarginPx = with(density) { 55.dp.toPx() }.coerceAtMost(heightPx * 0.12f)
    val bottomMarginPx = with(density) { 76.dp.toPx() }.coerceAtMost(heightPx * 0.15f)
    val maxX = (widthPx - marginPx).coerceAtLeast(marginPx)
    val maxY = (heightPx - bottomMarginPx).coerceAtLeast(topMarginPx)

    val maxRx = (centerX - marginPx).coerceAtLeast(1f)
    val maxRy = minOf(centerY - topMarginPx, maxY - centerY).coerceAtLeast(1f)
    val minHubDistancePx = with(density) { 122.dp.toPx() }.coerceAtMost(minOf(maxRx, maxRy) * 0.92f)
    val orbitRx = (minOf(maxRx, with(density) { 330.dp.toPx() }) * 0.85f).coerceIn(minOf(maxRx, minHubDistancePx), maxRx)
    val orbitRy = (minOf(maxRy, with(density) { 290.dp.toPx() }) * 0.85f).coerceIn(minOf(maxRy, minHubDistancePx), maxRy)

    val defaultSeparation = if (nodeCount > 1) {
        (2f * minOf(orbitRx, orbitRy) * sin(PI / nodeCount).toFloat()) * 0.85f
    } else {
        minOf(widthPx, heightPx) * 0.25f
    }
    val minDistancePx = minOf(
        with(density) { 88.dp.toPx() },
        defaultSeparation
    ).coerceAtLeast(with(density) { 44.dp.toPx() })

    val placedPositions = arrayOfNulls<Offset>(nodeCount)

    fun isClear(pos: Offset, placed: List<Offset>, requiredDist: Float = minDistancePx): Boolean {
        if (hypot((pos.x - centerX).toDouble(), (pos.y - centerY).toDouble()) < minHubDistancePx) return false
        return placed.none { other ->
            hypot((pos.x - other.x).toDouble(), (pos.y - other.y).toDouble()) < requiredDist
        }
    }

    fun findClearPosition(preferred: Offset, placed: List<Offset>): Offset {
        if (isClear(preferred, placed)) return preferred

        val preferredAngle = atan2((preferred.y - centerY).toDouble(), (preferred.x - centerX).toDouble())
        val radiusMultipliers = listOf(1.0f, 0.86f, 1.12f, 0.74f)
        val steps = 36
        var bestCandidate = preferred
        var maxMinDistance = -1f

        for (mult in radiusMultipliers) {
            val rx = (orbitRx * mult).coerceIn(minOf(maxRx, minHubDistancePx), maxRx)
            val ry = (orbitRy * mult).coerceIn(minOf(maxRy, minHubDistancePx), maxRy)
            for (step in 0..steps / 2) {
                val signs = if (step == 0 || step == steps / 2) listOf(1) else listOf(1, -1)
                for (sign in signs) {
                    val angle = preferredAngle + sign * (2.0 * PI * step / steps)
                    val candX = (centerX + (rx * cos(angle)).toFloat()).coerceIn(marginPx, maxX)
                    val candY = (centerY + (ry * sin(angle)).toFloat()).coerceIn(topMarginPx, maxY)
                    val candidate = Offset(candX, candY)
                    val hubDist = hypot((candX - centerX).toDouble(), (candY - centerY).toDouble()).toFloat()
                    if (hubDist < minHubDistancePx) continue

                    val minOtherDist = if (placed.isEmpty()) Float.MAX_VALUE else {
                        placed.minOf { other ->
                            hypot((candidate.x - other.x).toDouble(), (candidate.y - other.y).toDouble()).toFloat()
                        }
                    }
                    if (minOtherDist >= minDistancePx) {
                        return candidate
                    }
                    if (minOtherDist > maxMinDistance) {
                        maxMinDistance = minOtherDist
                        bestCandidate = candidate
                    }
                }
            }
        }
        return bestCandidate
    }

    // Place devices with user-assigned offsets for this bucket first, separating any saved collisions
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
        val rawStored = when {
            stored != null -> {
                val rawX = if (stored.fractional) stored.x * widthPx else stored.x
                val rawY = if (stored.fractional) stored.y * heightPx else stored.y
                Offset(
                    rawX.coerceIn(marginPx, maxX),
                    rawY.coerceIn(topMarginPx, maxY)
                )
            }
            layoutBucket == "desktop" || layoutBucket.endsWith("-land") || layoutBucket.startsWith("land-") -> {
                val hasBucketFraction = persistedNodeOffsets.keys.any {
                    it.startsWith("frac:") && it.endsWith(":${row.deviceId}")
                }
                if (hasBucketFraction) {
                    null
                } else {
                    val isExp = layoutBucket == "desktop" || layoutBucket.startsWith("inner-") || layoutBucket.endsWith("-exp") || layoutBucket.endsWith("-med")
                    val deltaKey = if (isExp) "exp:${row.deviceId}" else "cmp:${row.deviceId}"
                    val legacy = persistedNodeOffsets[deltaKey] ?: persistedNodeOffsets[row.deviceId]
                    legacy?.let {
                        val staticPos = staticNodePositions.getOrElse(index) { Offset(centerX, centerY) }
                        Offset(
                            (staticPos.x + it.first).coerceIn(marginPx, maxX),
                            (staticPos.y + it.second).coerceIn(topMarginPx, maxY)
                        )
                    }
                }
            }
            else -> null
        }

        if (rawStored != null) {
            val currentPlaced = placedPositions.filterNotNull()
            val minSeparation = minDistancePx * 0.92f
            val isOverlappingEarlierPlaced = currentPlaced.any { other ->
                hypot((rawStored.x - other.x).toDouble(), (rawStored.y - other.y).toDouble()) < minSeparation
            }
            val minHubDist = with(density) { 90.dp.toPx() }
            val isInsideHub = hypot((rawStored.x - centerX).toDouble(), (rawStored.y - centerY).toDouble()) < minHubDist

            placedPositions[index] = when {
                !isOverlappingEarlierPlaced && !isInsideHub -> rawStored
                isInsideHub && !isOverlappingEarlierPlaced -> {
                    val angle = atan2((rawStored.y - centerY).toDouble(), (rawStored.x - centerX).toDouble())
                    Offset(
                        (centerX + (minHubDist + 4f) * cos(angle).toFloat()).coerceIn(marginPx, maxX),
                        (centerY + (minHubDist + 4f) * sin(angle).toFloat()).coerceIn(topMarginPx, maxY)
                    )
                }
                else -> findClearPosition(rawStored, currentPlaced)
            }
        }
    }

    // Place unassigned devices on the equally spaced orbit, shifting along the orbit only if a user-placed node occupies the slot
    return deviceRows.indices.map { index ->
        val existing = placedPositions[index]
        if (existing != null) return@map existing

        val staticPos = staticNodePositions.getOrElse(index) { Offset(centerX, centerY) }
        val resolved = if (isClear(staticPos, placedPositions.filterNotNull())) {
            staticPos
        } else {
            findClearPosition(staticPos, placedPositions.filterNotNull())
        }
        placedPositions[index] = resolved
        resolved
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
    if (nodeCount <= 0) return emptyList()

    val marginPx = with(density) { 64.dp.toPx() }.coerceAtMost(widthPx * 0.16f)
    val topMarginPx = with(density) { 55.dp.toPx() }.coerceAtMost(heightPx * 0.12f)
    val bottomMarginPx = with(density) { 76.dp.toPx() }.coerceAtMost(heightPx * 0.15f)
    val maxX = (widthPx - marginPx).coerceAtLeast(marginPx)
    val maxY = (heightPx - bottomMarginPx).coerceAtLeast(topMarginPx)

    val maxRx = (centerX - marginPx).coerceAtLeast(1f)
    val maxRy = minOf(centerY - topMarginPx, maxY - centerY).coerceAtLeast(1f)
    val minHubDist = with(density) { 128.dp.toPx() }.coerceAtMost(minOf(maxRx, maxRy) * 0.92f)

    val capRx = with(density) { 330.dp.toPx() }
    val capRy = with(density) { 290.dp.toPx() }
    val scale = when {
        nodeCount <= 2 -> 0.72f
        nodeCount <= 4 -> 0.78f
        nodeCount <= 6 -> 0.85f
        else -> 0.92f
    }

    val rx = (minOf(maxRx, capRx) * scale).coerceIn(minOf(maxRx, minHubDist), maxRx)
    val ry = (minOf(maxRy, capRy) * scale).coerceIn(minOf(maxRy, minHubDist), maxRy)

    val startAngle = when {
        nodeCount == 2 -> PI
        else -> -PI / 2.0
    }
    val angleStep = (2.0 * PI) / nodeCount

    return List(nodeCount) { index ->
        val angle = startAngle + (angleStep * index)
        val cx = (centerX + (rx * cos(angle)).toFloat()).coerceIn(marginPx, maxX)
        val cy = (centerY + (ry * sin(angle)).toFloat()).coerceIn(topMarginPx, maxY)
        Offset(cx, cy)
    }
}
