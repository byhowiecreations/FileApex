package com.fileapex.ui.devices

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.ui.deviceLayoutBucket
import com.fileapex.ui.legacySizeScopePrefix

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
