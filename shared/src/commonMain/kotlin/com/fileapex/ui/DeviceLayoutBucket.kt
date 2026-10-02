package com.fileapex.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

internal fun kineticPixelFits(
    x: Float,
    y: Float,
    widthPx: Float,
    heightPx: Float,
    marginPx: Float,
    topMarginPx: Float
): Boolean {
    if (widthPx <= marginPx * 2f || heightPx <= topMarginPx + marginPx) return false
    return x in marginPx..(widthPx - marginPx) && y in topMarginPx..(heightPx - marginPx)
}

/**
 * Fraction for this bucket wins. Older absolute-pixel records are landscape-only,
 * and only when they still sit inside the current canvas.
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
    val fraction = offsets[kineticFractionKey(bucket, deviceId)]
    if (fraction != null) {
        return KineticStoredNode(fraction.first, fraction.second, fractional = true)
    }
    if (!bucket.startsWith("land-")) return null
    val scoped = if (bucket.endsWith("-cmp")) "pos:cmp:$deviceId" else "pos:exp:$deviceId"
    val pixel = offsets[scoped] ?: offsets["pos:$deviceId"] ?: return null
    if (pixel.first < 0f || pixel.second < 0f) return null
    if (pixel.first > widthPx * 1.25f || pixel.second > heightPx * 1.25f) return null
    val x = if (kineticPixelFits(pixel.first, pixel.second, widthPx, heightPx, marginPx, topMarginPx)) {
        pixel.first
    } else {
        pixel.first.coerceIn(marginPx, widthPx - marginPx)
    }
    val y = if (kineticPixelFits(pixel.first, pixel.second, widthPx, heightPx, marginPx, topMarginPx)) {
        pixel.second
    } else {
        pixel.second.coerceIn(topMarginPx, heightPx - marginPx)
    }
    return KineticStoredNode(x, y, fractional = false)
}
