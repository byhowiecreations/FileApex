package com.fileapex.ui.devices

import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import com.fileapex.data.settings.FreestyleLayoutMode

@Composable
internal fun FreestyleScope.FreestyleEffects() {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        LaunchedEffect(freestyleMode, isEditMode, layoutScopePrefix, widthPx, heightPx) {
            if (!isEditMode) {
                val initialX = savedOptionsFractionX?.let { it * widthPx - (optionsButtonWidthPx / 2f) } ?: defaultOptionsPx.x
                val initialY = savedOptionsFractionY?.let { it * heightPx - (optionsButtonHeightPx / 2f) } ?: defaultOptionsPx.y
                liveOptionsPos = Offset(
                    initialX.coerceIn(marginPx, widthPx - marginPx - optionsButtonWidthPx),
                    initialY.coerceIn(topMarginPx, heightPx - bottomMarginPx - optionsButtonHeightPx)
                )
            }
        }

        // Auto-open Options and the farthest device (to avoid overlap) when Edit Mode is toggled
        LaunchedEffect(isEditMode) {
            if (isEditMode) {
                optionsMenuExpanded = true
                if (activeDeviceId == null && deviceRows.isNotEmpty()) {
                    val optCenterX = liveOptionsPos.x + (optionsButtonWidthPx / 2f)
                    val optCenterY = liveOptionsPos.y + (optionsButtonHeightPx / 2f)
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
                    val resolved = resolveFreestyleNodePositions(
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
                    val farthest = deviceRows.maxByOrNull { row ->
                        val topLeft = resolved["$layoutScopePrefix${row.deviceId}"]
                        val pxX = (topLeft?.x ?: (widthPx / 2f)) + (deviceButtonSizePx / 2f)
                        val pxY = (topLeft?.y ?: (heightPx / 2f)) + (deviceButtonSizePx / 2f)
                        val dx = pxX - optCenterX
                        val dy = pxY - optCenterY
                        dx * dx + dy * dy
                    }
                    activeDeviceId = farthest?.deviceId ?: deviceRows.first().deviceId
                }
                if (!editTutorialShown) {
                    showTutorialDialog = true
                }
            } else {
                showTutorialDialog = false
                optionsMenuExpanded = false
                activeDeviceId = null
            }
        }

        LaunchedEffect(isEditMode) {
            if (!isEditMode) {
                liveNodePositions.clear()
                livePinnedPositions.clear()
            }
        }

    } } } }
}
