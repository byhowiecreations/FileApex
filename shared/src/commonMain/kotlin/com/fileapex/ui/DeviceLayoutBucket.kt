package com.fileapex.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.platform.isDesktopHost
import kotlin.math.roundToInt

/**
 * One saved layout per display and orientation.
 * Desktop: single "desktop" bucket that intelligently adjusts fractional coordinates with window resizes and transfer banners.
 * Mobile/Foldable: "outer-port", "outer-land", "inner-port", "inner-land".
 */
internal fun deviceLayoutBucket(
    width: Dp,
    height: Dp,
    isDesktop: Boolean = isDesktopHost()
): String {
    if (isDesktop) return "desktop"
    val orientation = if (width >= height) "land" else "port"
    val display = if (minOf(width, height) < 600.dp) "outer" else "inner"
    return "$display-$orientation"
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
 * Resolves a stored node position for the active bucket.
 * 1. Direct fractional match for this bucket.
 * 2. Compatible fallback for migrated keys (desktop falls back to former land-exp / port-exp fractions;
 *    outer-port falls back to port-cmp, inner-land falls back to land-med/land-exp).
 * 3. Untouched mobile buckets return null if another bucket has a fraction, so they use clean default layout.
 * 4. Pre-fraction legacy pixel records are only used for landscape when no fraction exists yet.
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

    val fallbackKeys = when (bucket) {
        "desktop" -> listOf(
            "frac:desktop:$deviceId",
            "frac:land-cmp:$deviceId",
            "frac:land-med:$deviceId",
            "frac:land-exp:$deviceId",
            "frac:port-cmp:$deviceId",
            "frac:port-med:$deviceId",
            "frac:port-exp:$deviceId"
        )
        "outer-port" -> listOf("frac:port-cmp:$deviceId", "frac:port-med:$deviceId")
        "outer-land" -> listOf("frac:land-cmp:$deviceId", "frac:land-med:$deviceId", "frac:land-exp:$deviceId")
        "inner-port" -> listOf("frac:port-med:$deviceId", "frac:port-exp:$deviceId")
        "inner-land" -> listOf("frac:land-med:$deviceId", "frac:land-exp:$deviceId")
        else -> emptyList()
    }
    for (k in fallbackKeys) {
        val f = offsets[k]
        if (f != null) {
            return KineticStoredNode(f.first, f.second, fractional = true)
        }
    }

    val hasAnyBucketFraction = offsets.keys.any { key ->
        key.startsWith("frac:") && key.endsWith(":$deviceId")
    }
    if (hasAnyBucketFraction) return null

    val isLandscapeCompatible = bucket == "desktop" || bucket.endsWith("-land") || bucket.startsWith("land-")
    if (!isLandscapeCompatible) return null
    return closestAbsolutePixel(offsets, bucket, deviceId, widthPx, heightPx, marginPx, topMarginPx)
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
    val isExp = bucket == "desktop" || bucket.startsWith("inner-") || bucket.endsWith("-exp") || bucket.endsWith("-med")
    val scoped = if (isExp) "pos:exp:$deviceId" else "pos:cmp:$deviceId"
    val other = if (isExp) "pos:cmp:$deviceId" else "pos:exp:$deviceId"
    val point = listOf(scoped, "pos:$deviceId", other).firstNotNullOfOrNull { key ->
        offsets[key]?.takeIf { it.first >= 0f && it.second >= 0f }
    } ?: return null
    val snapped = snapToWholePixel(point.first, point.second, marginPx, topMarginPx, maxX, maxY)
    return KineticStoredNode(snapped.first, snapped.second, fractional = false)
}

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
