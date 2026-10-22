package com.fileapex.ui.devices

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.di.FileApexServices

/**
 * Everything a Freestyle section draws from: the view's inputs, the saved layout, the canvas geometry, the live state,
 * and the values worked out from them (options menu placement and its drag handlers). Sections are extension
 * composables on this class.
 */
internal class FreestyleScope(
    val inputs: FreestyleInputs,
    val persisted: FreestylePersisted,
    val geometry: FreestyleGeometry,
    val live: FreestyleLive,
    val windowOffsetPxState: MutableState<Offset>,
    val liveOptionsPosState: MutableState<Offset>,
    val optionsActionsListState: MutableState<List<FreestyleOptionAction>>,
    val savedOptionsFractionX: Float?,
    val savedOptionsFractionY: Float?,
    val defaultOptionsPx: Offset,
    val liveNodePositions: SnapshotStateMap<String, Offset>,
    val livePinnedPositions: SnapshotStateMap<String, Offset>,
    val visibleOptions: List<FreestyleOptionAction>,
    val dropdownWidthDp: Dp,
    val dropdownWidthPx: Float,
    val dropdownHeightDp: Dp,
    val dropdownHeightPx: Float,
    val clampedDropdownX: Float,
    val clampedDropdownY: Float,
    val relDropdownXDp: Dp,
    val relDropdownYDp: Dp,
    val optRowHeightDp: Dp,
    val optRowHeightPx: Float,
    val startDragAction: (Int) -> Unit,
    val onDragDeltaAction: (Int, Offset) -> Unit,
    val endDragAction: () -> Unit
) {
    var windowOffsetPx by windowOffsetPxState
    var liveOptionsPos by liveOptionsPosState
    var optionsActionsList by optionsActionsListState

    /** Opens the file window on a device, resolving its browse target first. */
    fun handleBrowseDevice(deviceId: String, deviceName: String) {
        live.showFilesWindow = true
        live.isResolvingBrowse = true
        live.resolvingDeviceName = deviceName
        val resolve = inputs.onResolveBrowseTarget
        if (resolve != null) {
            resolve(deviceId) { target ->
                live.browsingTarget = target
                live.isResolvingBrowse = false
            }
        } else {
            live.isResolvingBrowse = false
            inputs.onOpenDevice(deviceId)
        }
    }

    /** One menu order for every device: save it for each and tell the device store. */
    fun syncOrderToAllDevices(newOrder: String) {
        inputs.deviceRows.forEach { dev ->
            FileApexServices.settings.setFreestyleMenuOrder(persisted.freestyleMode, dev.deviceId, newOrder)
            if (persisted.freestyleMode == FreestyleLayoutMode.TILES) {
                inputs.onSaveDeviceTileMenuOrder(dev.deviceId, newOrder)
            } else {
                inputs.onSaveDeviceCardMenuOrder(dev.deviceId, newOrder)
            }
        }
    }
}

@Composable
internal fun rememberFreestyleScope(
    inputs: FreestyleInputs,
    persisted: FreestylePersisted,
    geometry: FreestyleGeometry,
    live: FreestyleLive
): FreestyleScope {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        val windowOffsetPxState = remember(widthPx, heightPx) {
            val initialW = with(density) { 400.dp.toPx() }
            val initialH = with(density) { 480.dp.toPx() }
            val initX = ((widthPx - initialW) / 2f).coerceAtLeast(marginPx)
            val initY = ((heightPx - initialH) / 2f).coerceAtLeast(topMarginPx)
            mutableStateOf(Offset(initX, initY))
        }

        // Draggable Options Button per layout mode
        val savedOptionsFractionX = when (freestyleMode) {
            FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardOptionsX
            FreestyleLayoutMode.CARDS_VERTICAL -> persistedCardVerticalOptionsX ?: persistedCardOptionsX
            FreestyleLayoutMode.TILES -> persistedTileOptionsX
        }
        val savedOptionsFractionY = when (freestyleMode) {
            FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardOptionsY
            FreestyleLayoutMode.CARDS_VERTICAL -> persistedCardVerticalOptionsY ?: persistedCardOptionsY
            FreestyleLayoutMode.TILES -> persistedTileOptionsY
        }

        val defaultOptionsPx = remember(widthPx, heightPx, optionsButtonWidthPx, optionsButtonHeightPx) {
            Offset(
                x = (widthPx - optionsButtonWidthPx) / 2f,
                y = heightPx - bottomMarginPx - optionsButtonHeightPx - 8f
            )
        }

        val liveOptionsPosState = remember(freestyleMode, layoutScopePrefix, widthPx, heightPx) {
            val initialX = savedOptionsFractionX?.let { it * widthPx - (optionsButtonWidthPx / 2f) } ?: defaultOptionsPx.x
            val initialY = savedOptionsFractionY?.let { it * heightPx - (optionsButtonHeightPx / 2f) } ?: defaultOptionsPx.y
            mutableStateOf(
                Offset(
                    initialX.coerceIn(marginPx, widthPx - marginPx - optionsButtonWidthPx),
                    initialY.coerceIn(topMarginPx, heightPx - bottomMarginPx - optionsButtonHeightPx)
                )
            )
        }
        var liveOptionsPos by liveOptionsPosState


        // Live node positions map to eliminate jerkiness on repeated moves
        val liveNodePositions = remember(freestyleMode, layoutScopePrefix, widthPx, heightPx) { mutableStateMapOf<String, Offset>() }
        val livePinnedPositions = remember(freestyleMode, layoutScopePrefix, widthPx, heightPx) { mutableStateMapOf<String, Offset>() }


        val optionsActionsListState = remember(persistedOptionsMenuOrder) {
            mutableStateOf(FreestyleOptionAction.parseOrder(persistedOptionsMenuOrder))
        }
        var optionsActionsList by optionsActionsListState
        val hasFriends = com.fileapex.friends.ui.rememberFriendsCount() > 0
        val visibleOptions = optionsActionsList.filter { !currentPinnedActionsMap.containsKey(it.key) && (hasFriends || it != FreestyleOptionAction.GROUPS) }

        val dropdownWidthDp = if (isEditMode) 200.dp else 200.dp
        val dropdownWidthPx = with(density) { dropdownWidthDp.toPx() }
        val dropdownHeightDp = if (visibleOptions.isEmpty()) 110.dp else (visibleOptions.size * 40 + 24).coerceIn(100, 300).dp
        val dropdownHeightPx = with(density) { dropdownHeightDp.toPx() }

        val idealDropdownX = liveOptionsPos.x
        val clampedDropdownX = idealDropdownX.coerceIn(marginPx, (widthPx - marginPx - dropdownWidthPx).coerceAtLeast(marginPx))
        val relDropdownXDp = with(density) { (clampedDropdownX - liveOptionsPos.x).toDp() }

        val placeBelow = (liveOptionsPos.y + optionsButtonHeightPx + with(density) { 6.dp.toPx() } + dropdownHeightPx) <= (heightPx - bottomMarginPx)
        val idealDropdownY = if (placeBelow) {
            liveOptionsPos.y + optionsButtonHeightPx + with(density) { 6.dp.toPx() }
        } else {
            liveOptionsPos.y - dropdownHeightPx - with(density) { 6.dp.toPx() }
        }
        val clampedDropdownY = idealDropdownY.coerceIn(topMarginPx, (heightPx - bottomMarginPx - dropdownHeightPx).coerceAtLeast(topMarginPx))
        val relDropdownYDp = with(density) { (clampedDropdownY - liveOptionsPos.y).toDp() }

        val optRowHeightDp = 40.dp
        val optRowHeightPx = with(density) { optRowHeightDp.toPx() }

        // The drag handlers outlive the composition that made them (a gesture keeps the lambdas it started with),
        // so they read the menu order and the pinned set fresh each time instead of the values captured here.
        fun pinnedNow(): Map<String, Pair<Float, Float>> = when (freestyleMode) {
            FreestyleLayoutMode.CARDS_HORIZONTAL -> FileApexServices.settings.freestyleCardPinnedActions.value
            FreestyleLayoutMode.CARDS_VERTICAL -> FileApexServices.settings.freestyleCardVerticalPinnedActions.value
            FreestyleLayoutMode.TILES -> FileApexServices.settings.freestyleTilePinnedActions.value
        }
        fun visibleOptionsNow(): List<FreestyleOptionAction> = optionsActionsList.filter { !pinnedNow().containsKey(it.key) && (hasFriends || it != FreestyleOptionAction.GROUPS) }

        val startDragAction = { optIndex: Int ->
            val rowCenterCanvasX = clampedDropdownX + dropdownWidthPx / 2f
            val rowCenterCanvasY = clampedDropdownY + with(density) { 4.dp.toPx() } + (optIndex + 0.5f) * optRowHeightPx
            draggingOptIndex = optIndex
            draggingOptOffsetX = 0f
            draggingOptOffsetY = 0f
            dragCanvasPos = Offset(rowCenterCanvasX, rowCenterCanvasY)
            isDraggingOut = false
        }
        val onDragDeltaAction = { optIndex: Int, dragDelta: Offset ->
            val newOffsetX = draggingOptOffsetX + dragDelta.x
            val newOffsetY = draggingOptOffsetY + dragDelta.y
            draggingOptOffsetX = newOffsetX
            draggingOptOffsetY = newOffsetY

            val curX = (dragCanvasPos?.x ?: (clampedDropdownX + dropdownWidthPx / 2f)) + dragDelta.x
            val curY = (dragCanvasPos?.y ?: (clampedDropdownY + with(density) { 4.dp.toPx() } + ((draggingOptIndex ?: optIndex) + 0.5f) * optRowHeightPx)) + dragDelta.y
            dragCanvasPos = Offset(curX, curY)

            val pad = with(density) { 10.dp.toPx() }
            val outside = curX < (clampedDropdownX - pad) ||
                          curX > (clampedDropdownX + dropdownWidthPx + pad) ||
                          curY < (clampedDropdownY - pad) ||
                          curY > (clampedDropdownY + dropdownHeightPx + pad)
            val escapeHoriz = kotlin.math.abs(newOffsetX) >= dropdownWidthPx * 0.35f

            if (outside || escapeHoriz) {
                isDraggingOut = true
            } else {
                isDraggingOut = false
                if (isEditMode) {
                    val currentIdx = draggingOptIndex ?: optIndex
                    val swap = swapOptionsDuringDrag(optionsActionsList, visibleOptionsNow(), currentIdx, newOffsetY, optRowHeightPx)
                    if (swap != null) {
                        optionsActionsList = swap.order
                        draggingOptIndex = swap.index
                        draggingOptOffsetY = swap.offsetY
                        FileApexServices.settings.setFreestyleOptionsMenuOrder(FreestyleOptionAction.encodeOrder(swap.order))
                    }
                }
            }
        }
        val endDragAction = {
            val currentIdx = draggingOptIndex
            val visibleNow = visibleOptionsNow()
            if (currentIdx != null && currentIdx in visibleNow.indices && isDraggingOut) {
                val actionToPin = visibleNow[currentIdx]
                val dropX = dragCanvasPos?.x ?: (clampedDropdownX + dropdownWidthPx / 2f)
                val dropY = dragCanvasPos?.y ?: (clampedDropdownY + (currentIdx + 0.5f) * optRowHeightPx)
                val fracX = (dropX / widthPx).coerceIn(0.06f, 0.94f)
                val fracY = (dropY / heightPx).coerceIn(0.06f, 0.94f)
                FileApexServices.settings.setFreestylePinnedActionOffset(freestyleMode, actionToPin.key, fracX, fracY)
            }
            draggingOptIndex = null
            dragCanvasPos = null
            isDraggingOut = false
            draggingOptOffsetX = 0f
            draggingOptOffsetY = 0f
        }

        return FreestyleScope(
            inputs, persisted, geometry, live, windowOffsetPxState, liveOptionsPosState, optionsActionsListState,
            savedOptionsFractionX,
            savedOptionsFractionY,
            defaultOptionsPx,
            liveNodePositions,
            livePinnedPositions,
            visibleOptions,
            dropdownWidthDp,
            dropdownWidthPx,
            dropdownHeightDp,
            dropdownHeightPx,
            clampedDropdownX,
            clampedDropdownY,
            relDropdownXDp,
            relDropdownYDp,
            optRowHeightDp,
            optRowHeightPx,
            startDragAction,
            onDragDeltaAction,
            endDragAction
        )
    } } } }
}
