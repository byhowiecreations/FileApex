package com.fileapex.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One saved layout per orientation and width class.
 * Folded portrait, unfolded portrait, and unfolded landscape do not share coordinates.
 */
internal fun deviceLayoutBucket(width: Dp, height: Dp): String {
    val orientation = if (width >= height) "land" else "port"
    val size = when {
        width < 600.dp -> "cmp"
        width < 840.dp -> "med"
        else -> "exp"
    }
    return "$orientation-$size"
}

/** Pre-orientation storage. Wide and narrow only, no portrait/landscape split. */
internal fun legacySizeScopePrefix(width: Dp): String =
    if (width < 600.dp) "cmp:" else "exp:"

internal fun kineticFractionKey(bucket: String, deviceId: String): String = "frac:$bucket:$deviceId"

internal data class KineticStoredNode(
    val x: Float,
    val y: Float,
    val fractional: Boolean
)

/**
 * Absolute pixel records win. A bucket fraction is used only when no pixel
 * record exists, so a lost or replaced fraction cannot hide the saved layout.
 */
internal fun kineticStoredNode(
    offsets: Map<String, Pair<Float, Float>>,
    bucket: String,
    deviceId: String,
    widthPx: Float,
    heightPx: Float,
    marginPx: Float,
    topMarginPx: Float
): KineticStoredNode? {
    val pixel = closestAbsolutePixel(offsets, bucket, deviceId, widthPx, heightPx, marginPx, topMarginPx)
    if (pixel != null) return pixel
    val fraction = offsets[kineticFractionKey(bucket, deviceId)] ?: return null
    return KineticStoredNode(fraction.first, fraction.second, fractional = true)
}

private fun closestAbsolutePixel(
    offsets: Map<String, Pair<Float, Float>>,
    bucket: String,
    deviceId: String,
    widthPx: Float,
    heightPx: Float,
    marginPx: Float,
    topMarginPx: Float
): KineticStoredNode? {
    val maxX = widthPx - marginPx
    val maxY = heightPx - marginPx
    if (maxX <= marginPx || maxY <= topMarginPx) return null
    val scoped = if (bucket.endsWith("-cmp")) "pos:cmp:$deviceId" else "pos:exp:$deviceId"
    val other = if (bucket.endsWith("-cmp")) "pos:exp:$deviceId" else "pos:cmp:$deviceId"
    val records = listOf(scoped, "pos:$deviceId", other).mapNotNull { key -> offsets[key] }
    if (records.isEmpty()) return null
    val ranked = records.mapIndexed { index, point ->
        if (point.first < 0f || point.second < 0f) return@mapIndexed null
        val snapped = snapToWholePixel(point.first, point.second, marginPx, topMarginPx, maxX, maxY)
        val dx = snapped.first - point.first
        val dy = snapped.second - point.second
        RankedPixel(dx * dx + dy * dy, index, snapped)
    }.filterNotNull()
    val best = ranked.minWithOrNull(compareBy<RankedPixel> { it.clampDistance }.thenBy { it.preference })
        ?: return null
    return KineticStoredNode(best.point.first, best.point.second, fractional = false)
}

private data class RankedPixel(
    val clampDistance: Float,
    val preference: Int,
    val point: Pair<Float, Float>
)

private fun snapToWholePixel(
    x: Float,
    y: Float,
    minX: Float,
    minY: Float,
    maxX: Float,
    maxY: Float
): Pair<Float, Float> {
    val sx = x.coerceIn(minX, maxX).roundToInt().toFloat().coerceIn(minX, maxX)
    val sy = y.coerceIn(minY, maxY).roundToInt().toFloat().coerceIn(minY, maxY)
    return sx to sy
}
