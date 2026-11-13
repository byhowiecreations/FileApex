package com.fileapex.ui.devices

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.deviceLayoutBucket
import com.fileapex.ui.legacySizeScopePrefix
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal class FreestyleGeometry(
    val density: Density,
    val maxWidth: Dp,
    val maxHeight: Dp,
    val widthPx: Float,
    val heightPx: Float,
    val layoutScopePrefix: String,
    val legacyScopePrefix: String,
    val marginPx: Float,
    val topMarginPx: Float,
    val bottomMarginPx: Float,
    val deviceButtonSizeDp: Dp,
    val deviceButtonSizePx: Float,
    val optionsButtonWidthDp: Dp,
    val optionsButtonHeightDp: Dp,
    val optionsButtonWidthPx: Float,
    val optionsButtonHeightPx: Float
)

internal fun freestyleGeometry(density: Density, maxWidth: Dp, maxHeight: Dp): FreestyleGeometry {
    val widthPx = with(density) { maxWidth.toPx() }
    val heightPx = with(density) { maxHeight.toPx() }
    val layoutScopePrefix = "${deviceLayoutBucket(maxWidth, maxHeight)}:"
    val legacyScopePrefix = legacySizeScopePrefix(maxWidth)
    val marginPx = with(density) { 16.dp.toPx() }
    val topMarginPx = with(density) { 16.dp.toPx() }
    // Full height utilization down to true bottom of app
    val bottomMarginPx = with(density) { 16.dp.toPx() }
    val deviceButtonSizeDp = 68.dp
    val deviceButtonSizePx = with(density) { deviceButtonSizeDp.toPx() }
    val optionsButtonWidthDp = 110.dp
    val optionsButtonHeightDp = 42.dp
    val optionsButtonWidthPx = with(density) { optionsButtonWidthDp.toPx() }
    val optionsButtonHeightPx = with(density) { optionsButtonHeightDp.toPx() }
    return FreestyleGeometry(density, maxWidth, maxHeight, widthPx, heightPx, layoutScopePrefix, legacyScopePrefix, marginPx, topMarginPx, bottomMarginPx, deviceButtonSizeDp, deviceButtonSizePx, optionsButtonWidthDp, optionsButtonHeightDp, optionsButtonWidthPx, optionsButtonHeightPx)
}

private class FreestyleLayoutBounds(
    val density: Density,
    val widthPx: Float,
    val heightPx: Float,
    val deviceButtonSizePx: Float,
    val minDeviceClearancePx: Float,
    val minDeviceX: Float,
    val maxDeviceX: Float,
    val minDeviceY: Float,
    val maxDeviceY: Float,
    val minCenterX: Float,
    val maxCenterX: Float,
    val minCenterY: Float,
    val maxCenterY: Float,
    val optCenterX: Float,
    val optCenterY: Float,
    val optRadiusPx: Float,
    val formationCenterX: Float,
    val formationCenterY: Float,
    val availRx: Float,
    val availRy: Float
)

private fun freestyleDefaultCenter(bounds: FreestyleLayoutBounds, index: Int, count: Int): Offset = with(bounds) {
    when (count) {
        1 -> Offset(formationCenterX, formationCenterY)
        2 -> {
            val dx = (availRx * 0.52f).coerceIn(
                minOf(availRx, with(density) { 80.dp.toPx() }),
                with(density) { 180.dp.toPx() }.coerceAtMost(availRx)
            )
            if (index == 0) Offset(formationCenterX - dx, formationCenterY)
            else Offset(formationCenterX + dx, formationCenterY)
        }
        3 -> {
            val span = minOf(availRx, availRy, with(density) { 200.dp.toPx() })
                .coerceAtLeast(minOf(availRx, availRy) * 0.65f)
            val dx = (span * 0.86f).coerceIn(minOf(availRx, with(density) { 75.dp.toPx() }), availRx)
            val dy = (span * 0.75f).coerceIn(minOf(availRy, with(density) { 70.dp.toPx() }), availRy)
            when (index) {
                0 -> Offset(formationCenterX, formationCenterY - dy)
                1 -> Offset(formationCenterX - dx, formationCenterY + dy)
                else -> Offset(formationCenterX + dx, formationCenterY + dy)
            }
        }
        4 -> {
            val halfSide = (minOf(availRx, availRy) * 0.58f).coerceIn(
                minOf(availRx, availRy, with(density) { 85.dp.toPx() }),
                minOf(availRx, availRy, with(density) { 180.dp.toPx() })
            )
            val sx = halfSide.coerceAtMost(availRx)
            val sy = halfSide.coerceAtMost(availRy)
            when (index) {
                0 -> Offset(formationCenterX - sx, formationCenterY - sy)
                1 -> Offset(formationCenterX + sx, formationCenterY - sy)
                2 -> Offset(formationCenterX - sx, formationCenterY + sy)
                else -> Offset(formationCenterX + sx, formationCenterY + sy)
            }
        }
        else -> {
            val scale = if (count <= 6) 0.72f else 0.84f
            val idealR = (minOf(availRx, availRy) * scale).coerceIn(
                minOf(availRx, availRy, with(density) { 125.dp.toPx() }),
                with(density) { 260.dp.toPx() }
            )
            val rx = idealR.coerceIn(minOf(availRx, with(density) { 110.dp.toPx() }), availRx)
            val ry = idealR.coerceIn(minOf(availRy, with(density) { 110.dp.toPx() }), availRy)
            val angle = -PI / 2.0 + (2.0 * PI * index / count)
            Offset(
                formationCenterX + (rx * cos(angle)).toFloat(),
                formationCenterY + (ry * sin(angle)).toFloat()
            )
        }
    }
}

private fun isFreestyleCenterClear(
    bounds: FreestyleLayoutBounds,
    candidateCenter: Offset,
    placedCenters: List<Offset>,
    clearancePx: Float = bounds.minDeviceClearancePx
): Boolean {
    if (hypot((candidateCenter.x - bounds.optCenterX).toDouble(), (candidateCenter.y - bounds.optCenterY).toDouble()) < bounds.optRadiusPx) {
        return false
    }
    return placedCenters.none { placed ->
        hypot((candidateCenter.x - placed.x).toDouble(), (candidateCenter.y - placed.y).toDouble()) < clearancePx
    }
}

private fun findClearFreestyleCenter(
    bounds: FreestyleLayoutBounds,
    preferredCenter: Offset,
    placedCenters: List<Offset>
): Offset = with(bounds) {
    val clamped = Offset(
        preferredCenter.x.coerceIn(minCenterX, maxCenterX),
        preferredCenter.y.coerceIn(minCenterY, maxCenterY)
    )
    if (isFreestyleCenterClear(bounds, clamped, placedCenters)) return clamped

    val baseAngle = atan2((clamped.y - formationCenterY).toDouble(), (clamped.x - formationCenterX).toDouble())
    val candidateScales = listOf(0.72f, 0.88f, 0.56f, 0.96f)
    val steps = 36
    var best = clamped
    var maxMinDist = -1f

    for (scale in candidateScales) {
        val rx = (availRx * scale).coerceAtLeast(minDeviceClearancePx * 0.6f)
        val ry = (availRy * scale).coerceAtLeast(minDeviceClearancePx * 0.6f)
        for (step in 0..steps / 2) {
            val signs = if (step == 0 || step == steps / 2) listOf(1) else listOf(1, -1)
            for (sign in signs) {
                val angle = baseAngle + sign * (2.0 * PI * step / steps)
                val cx = (formationCenterX + (rx * cos(angle)).toFloat()).coerceIn(minCenterX, maxCenterX)
                val cy = (formationCenterY + (ry * sin(angle)).toFloat()).coerceIn(minCenterY, maxCenterY)
                val candidate = Offset(cx, cy)
                if (hypot((cx - optCenterX).toDouble(), (cy - optCenterY).toDouble()) < optRadiusPx) continue
                if (isFreestyleCenterClear(bounds, candidate, placedCenters)) return candidate

                val minDist = if (placedCenters.isEmpty()) Float.MAX_VALUE else {
                    placedCenters.minOf { placed ->
                        hypot((cx - placed.x).toDouble(), (cy - placed.y).toDouble()).toFloat()
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

private fun storedFreestyleFractionFor(
    row: DeviceListRow,
    layoutScopePrefix: String,
    legacyScopePrefix: String,
    persistedOffsets: Map<String, Pair<Float, Float>>,
    fallbackOffsets: Map<String, Pair<Float, Float>>,
    useTileRowFallback: Boolean
): Pair<Float, Float>? {
    val scopedKey = "$layoutScopePrefix${row.deviceId}"
    val direct = persistedOffsets[scopedKey] ?: fallbackOffsets[scopedKey]
    if (direct != null) return direct

    val fallbackKeys = when (layoutScopePrefix) {
        "desktop:" -> listOf(
            "desktop:${row.deviceId}",
            "land-cmp:${row.deviceId}",
            "land-med:${row.deviceId}",
            "land-exp:${row.deviceId}",
            "port-cmp:${row.deviceId}",
            "port-med:${row.deviceId}",
            "port-exp:${row.deviceId}"
        )
        "outer-port:" -> listOf("port-cmp:${row.deviceId}", "port-med:${row.deviceId}")
        "outer-land:" -> listOf("land-cmp:${row.deviceId}", "land-med:${row.deviceId}", "land-exp:${row.deviceId}")
        "inner-port:" -> listOf("port-med:${row.deviceId}", "port-exp:${row.deviceId}")
        "inner-land:" -> listOf("land-med:${row.deviceId}", "land-exp:${row.deviceId}")
        else -> emptyList()
    }
    for (k in fallbackKeys) {
        val f = persistedOffsets[k] ?: fallbackOffsets[k]
        if (f != null) return f
    }

    val hasAnyBucketOffset = persistedOffsets.keys.any {
        (it.startsWith("desktop:") || it.startsWith("outer-") || it.startsWith("inner-") ||
         it.startsWith("port-") || it.startsWith("land-")) && it.endsWith(":${row.deviceId}")
    } || fallbackOffsets.keys.any {
        (it.startsWith("desktop:") || it.startsWith("outer-") || it.startsWith("inner-") ||
         it.startsWith("port-") || it.startsWith("land-")) && it.endsWith(":${row.deviceId}")
    }
    if (hasAnyBucketOffset) return null

    val legacyKey = "$legacyScopePrefix${row.deviceId}"
    val legacy = persistedOffsets[legacyKey]
        ?: fallbackOffsets[legacyKey]
        ?: persistedOffsets[row.deviceId]
        ?: fallbackOffsets[row.deviceId]
    if (legacy != null) return legacy

    val rowX = if (useTileRowFallback) row.tilePosX else row.cardPosX
    val rowY = if (useTileRowFallback) row.tilePosY else row.cardPosY
    return if (rowX != null && rowY != null) Pair(rowX, rowY) else null
}

internal fun resolveFreestyleNodePositions(
    density: Density,
    deviceRows: List<DeviceListRow>,
    widthPx: Float,
    heightPx: Float,
    layoutScopePrefix: String,
    legacyScopePrefix: String,
    persistedOffsets: Map<String, Pair<Float, Float>>,
    useTileRowFallback: Boolean,
    optionsPos: Offset,
    pinnedActions: Map<String, Pair<Float, Float>>,
    fallbackOffsets: Map<String, Pair<Float, Float>> = emptyMap()
): Map<String, Offset> {
    val count = deviceRows.size
    if (count == 0) return emptyMap()

    val marginPx = with(density) { 16.dp.toPx() }
    val topMarginPx = with(density) { 16.dp.toPx() }
    val bottomMarginPx = with(density) { 16.dp.toPx() }
    val deviceButtonSizePx = with(density) { 68.dp.toPx() }
    val nameOffsetPx = with(density) { 36.dp.toPx() }
    val optionsButtonWidthPx = with(density) { 110.dp.toPx() }
    val optionsButtonHeightPx = with(density) { 42.dp.toPx() }
    val minDeviceClearancePx = with(density) { 96.dp.toPx() }.coerceAtMost(minOf(widthPx, heightPx) * 0.26f)

    val minDeviceX = marginPx
    val maxDeviceX = (widthPx - marginPx - deviceButtonSizePx).coerceAtLeast(marginPx)
    val minDeviceY = topMarginPx
    val maxDeviceY = (heightPx - bottomMarginPx - deviceButtonSizePx - nameOffsetPx).coerceAtLeast(topMarginPx)

    val minCenterX = minDeviceX + deviceButtonSizePx / 2f
    val maxCenterX = maxDeviceX + deviceButtonSizePx / 2f
    val minCenterY = minDeviceY + deviceButtonSizePx / 2f
    val maxCenterY = maxDeviceY + deviceButtonSizePx / 2f

    val optCenterX = optionsPos.x + optionsButtonWidthPx / 2f
    val optCenterY = optionsPos.y + optionsButtonHeightPx / 2f
    val optRadiusPx = (optionsButtonWidthPx / 2f) + (deviceButtonSizePx / 2f) + 8f

    val usableBottomCenterY = if (optionsPos.y > heightPx * 0.65f) {
        (optionsPos.y - nameOffsetPx - deviceButtonSizePx / 2f - 8f).coerceIn(minCenterY, maxCenterY)
    } else {
        maxCenterY
    }
    val bounds = FreestyleLayoutBounds(
        density = density,
        widthPx = widthPx,
        heightPx = heightPx,
        deviceButtonSizePx = deviceButtonSizePx,
        minDeviceClearancePx = minDeviceClearancePx,
        minDeviceX = minDeviceX,
        maxDeviceX = maxDeviceX,
        minDeviceY = minDeviceY,
        maxDeviceY = maxDeviceY,
        minCenterX = minCenterX,
        maxCenterX = maxCenterX,
        minCenterY = minCenterY,
        maxCenterY = maxCenterY,
        optCenterX = optCenterX,
        optCenterY = optCenterY,
        optRadiusPx = optRadiusPx,
        formationCenterX = widthPx / 2f,
        formationCenterY = (minCenterY + usableBottomCenterY) / 2f,
        availRx = ((maxCenterX - minCenterX) / 2f).coerceAtLeast(1f),
        availRy = ((usableBottomCenterY - minCenterY) / 2f).coerceAtLeast(1f)
    )

    val placedCenters = mutableListOf<Offset>()
    pinnedActions.values.forEach { (fx, fy) ->
        placedCenters.add(
            Offset(
                (fx * widthPx).coerceIn(minCenterX, maxCenterX),
                (fy * heightPx).coerceIn(minCenterY, maxCenterY)
            )
        )
    }

    val resolvedCenters = arrayOfNulls<Offset>(count)

    deviceRows.forEachIndexed { index, row ->
        val saved = storedFreestyleFractionFor(
            row, layoutScopePrefix, legacyScopePrefix, persistedOffsets, fallbackOffsets, useTileRowFallback
        ) ?: return@forEachIndexed
        val rawCenter = Offset(
            (saved.first * widthPx).coerceIn(minCenterX, maxCenterX),
            (saved.second * heightPx).coerceIn(minCenterY, maxCenterY)
        )
        val optDist = hypot((rawCenter.x - bounds.optCenterX).toDouble(), (rawCenter.y - bounds.optCenterY).toDouble()).toFloat()
        var center = if (optDist < bounds.optRadiusPx) {
            val angle = if (optDist > 0.1f) atan2((rawCenter.y - bounds.optCenterY).toDouble(), (rawCenter.x - bounds.optCenterX).toDouble()) else -PI / 2.0
            val push = bounds.optRadiusPx - optDist
            Offset(
                (rawCenter.x + push * cos(angle).toFloat()).coerceIn(minCenterX, maxCenterX),
                (rawCenter.y + push * sin(angle).toFloat()).coerceIn(minCenterY, maxCenterY)
            )
        } else {
            rawCenter
        }
        for (placed in placedCenters) {
            val dist = hypot((center.x - placed.x).toDouble(), (center.y - placed.y).toDouble()).toFloat()
            val minSep = minDeviceClearancePx * 0.85f
            if (dist < minSep) {
                val angle = if (dist > 0.1f) atan2((center.y - placed.y).toDouble(), (center.x - placed.x).toDouble()) else (PI / 4.0)
                val push = minSep - dist
                center = Offset(
                    (center.x + push * cos(angle).toFloat()).coerceIn(minCenterX, maxCenterX),
                    (center.y + push * sin(angle).toFloat()).coerceIn(minCenterY, maxCenterY)
                )
            }
        }
        placedCenters.add(center)
        resolvedCenters[index] = center
    }

    deviceRows.indices.forEach { index ->
        if (resolvedCenters[index] != null) return@forEach
        val center = findClearFreestyleCenter(bounds, freestyleDefaultCenter(bounds, index, count), placedCenters)
        placedCenters.add(center)
        resolvedCenters[index] = center
    }

    return deviceRows.mapIndexed { index, row ->
        val center = resolvedCenters[index] ?: freestyleDefaultCenter(bounds, index, count)
        val topLeft = Offset(
            (center.x - deviceButtonSizePx / 2f).coerceIn(minDeviceX, maxDeviceX),
            (center.y - deviceButtonSizePx / 2f).coerceIn(minDeviceY, maxDeviceY)
        )
        "$layoutScopePrefix${row.deviceId}" to topLeft
    }.toMap()
}
