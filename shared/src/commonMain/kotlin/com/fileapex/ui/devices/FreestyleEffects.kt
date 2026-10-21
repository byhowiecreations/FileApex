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
                    val farthest = deviceRows.maxByOrNull { row ->
                        val savedX = when (freestyleMode) {
                            FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardNodeOffsets[row.deviceId]?.first
                            FreestyleLayoutMode.CARDS_VERTICAL -> (persistedCardVerticalNodeOffsets[row.deviceId] ?: persistedCardNodeOffsets[row.deviceId])?.first
                            FreestyleLayoutMode.TILES -> persistedTileNodeOffsets[row.deviceId]?.first
                        }
                        val savedY = when (freestyleMode) {
                            FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardNodeOffsets[row.deviceId]?.second
                            FreestyleLayoutMode.CARDS_VERTICAL -> (persistedCardVerticalNodeOffsets[row.deviceId] ?: persistedCardNodeOffsets[row.deviceId])?.second
                            FreestyleLayoutMode.TILES -> persistedTileNodeOffsets[row.deviceId]?.second
                        }
                        val pxX = savedX?.let { it * widthPx } ?: (widthPx / 2f)
                        val pxY = savedY?.let { it * heightPx } ?: (heightPx / 2f)
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
