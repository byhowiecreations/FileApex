package com.fileapex.presentation.devices

import androidx.lifecycle.viewModelScope
import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.di.FileApexServices
import com.fileapex.domain.device.DeviceOrderCoordinator
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.DevicesUiState
import com.fileapex.presentation.DevicesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class DevicesOrderActions(
    private val _uiState: MutableStateFlow<DevicesUiState>,
    private val viewModelScope: CoroutineScope, private val deviceRows: StateFlow<List<DeviceListRow>>
) {
    private val repository = FileApexServices.deviceRepository

    private var freestyleEditSnapshot: FreestyleEditSnapshot? = null

    fun enterDeviceOrderEditMode() {
        val settings = FileApexServices.settings
        val currentRows = deviceRows.value
        val cardOffsets = currentRows.associate { row ->
            val cached = settings.freestyleCardNodeOffsets.value[row.deviceId]
            row.deviceId to Pair(cached?.first ?: row.cardPosX, cached?.second ?: row.cardPosY)
        }
        val tileOffsets = currentRows.associate { row ->
            val cached = settings.freestyleTileNodeOffsets.value[row.deviceId]
            row.deviceId to Pair(cached?.first ?: row.tilePosX, cached?.second ?: row.tilePosY)
        }
        val cardVerticalOffsets = currentRows.associate { row ->
            val cached = settings.freestyleCardVerticalNodeOffsets.value[row.deviceId]
            row.deviceId to Pair(cached?.first ?: row.cardPosX, cached?.second ?: row.cardPosY)
        }
        val cardMenus = currentRows.associate { row ->
            val cached = settings.freestyleCardMenuOrders.value[row.deviceId]
            row.deviceId to (cached ?: row.cardMenuOrder)
        }
        val cardVerticalMenus = currentRows.associate { row ->
            val cached = settings.freestyleCardVerticalMenuOrders.value[row.deviceId]
            row.deviceId to (cached ?: row.cardMenuOrder)
        }
        val tileMenus = currentRows.associate { row ->
            val cached = settings.freestyleTileMenuOrders.value[row.deviceId]
            row.deviceId to (cached ?: row.tileMenuOrder)
        }
        freestyleEditSnapshot = FreestyleEditSnapshot(
            cardOptionsPos = Pair(settings.freestyleCardOptionsPosX.value, settings.freestyleCardOptionsPosY.value),
            cardVerticalOptionsPos = Pair(settings.freestyleCardVerticalOptionsPosX.value, settings.freestyleCardVerticalOptionsPosY.value),
            tileOptionsPos = Pair(settings.freestyleTileOptionsPosX.value, settings.freestyleTileOptionsPosY.value),
            optionsMenuOrder = settings.freestyleOptionsMenuOrder.value,
            cardNodeOffsets = cardOffsets,
            cardVerticalNodeOffsets = cardVerticalOffsets,
            tileNodeOffsets = tileOffsets,
            cardMenuOrders = cardMenus,
            cardVerticalMenuOrders = cardVerticalMenus,
            tileMenuOrders = tileMenus,
            freestyleLayoutMode = settings.freestyleLayoutMode.value
        )
        _uiState.update {
            it.copy(
                deviceOrderEditMode = true,
                editOrderRows = deviceRows.value
            )
        }
    }

    fun exitDeviceOrderEditMode() {
        freestyleEditSnapshot = null
        _uiState.update {
            it.copy(deviceOrderEditMode = false, editOrderRows = emptyList())
        }
    }

    fun reorderEditDevice(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        _uiState.update { state ->
            if (!state.deviceOrderEditMode) return@update state
            val rows = state.editOrderRows.toMutableList()
            if (fromIndex !in rows.indices || toIndex !in rows.indices) return@update state
            val item = rows.removeAt(fromIndex)
            rows.add(toIndex, item)
            state.copy(editOrderRows = rows)
        }
    }

    fun revertDeviceOrderInEditMode() {
        val snapshot = freestyleEditSnapshot
        if (snapshot != null) {
            val settings = FileApexServices.settings
            settings.setFreestyleOptionsPosition(FreestyleLayoutMode.CARDS_HORIZONTAL, snapshot.cardOptionsPos.first, snapshot.cardOptionsPos.second)
            settings.setFreestyleOptionsPosition(FreestyleLayoutMode.CARDS_VERTICAL, snapshot.cardVerticalOptionsPos.first, snapshot.cardVerticalOptionsPos.second)
            settings.setFreestyleOptionsPosition(FreestyleLayoutMode.TILES, snapshot.tileOptionsPos.first, snapshot.tileOptionsPos.second)
            settings.setFreestyleOptionsMenuOrder(snapshot.optionsMenuOrder)
            settings.setFreestyleLayoutMode(snapshot.freestyleLayoutMode)

            snapshot.cardNodeOffsets.forEach { (id, offset) ->
                if (offset.first != null && offset.second != null) {
                    settings.setFreestyleCardNodeOffset(id, offset.first!!, offset.second!!)
                }
                saveDeviceCardPosition(id, offset.first, offset.second)
            }
            snapshot.cardVerticalNodeOffsets.forEach { (id, offset) ->
                if (offset.first != null && offset.second != null) {
                    settings.setFreestyleCardVerticalNodeOffset(id, offset.first!!, offset.second!!)
                }
            }
            snapshot.tileNodeOffsets.forEach { (id, offset) ->
                if (offset.first != null && offset.second != null) {
                    settings.setFreestyleTileNodeOffset(id, offset.first!!, offset.second!!)
                }
                saveDeviceTilePosition(id, offset.first, offset.second)
            }
            snapshot.cardMenuOrders.forEach { (id, order) ->
                settings.setFreestyleCardMenuOrder(id, order)
                saveDeviceCardMenuOrder(id, order)
            }
            snapshot.cardVerticalMenuOrders.forEach { (id, order) ->
                settings.setFreestyleCardVerticalMenuOrder(id, order)
            }
            snapshot.tileMenuOrders.forEach { (id, order) ->
                settings.setFreestyleTileMenuOrder(id, order)
                saveDeviceTileMenuOrder(id, order)
            }
        }
        val rows = _uiState.value.editOrderRows
        if (rows.isNotEmpty()) {
            val alphabetical = DeviceOrderCoordinator.applyOrderIds(
                rows,
                DeviceOrderCoordinator.alphabeticalOrderIds(rows)
            )
            _uiState.update { it.copy(editOrderRows = alphabetical) }
        }
    }

    fun saveDeviceOrderAndExitEditMode() {
        val rows = _uiState.value.editOrderRows
        if (rows.isEmpty()) {
            exitDeviceOrderEditMode()
            return
        }
        DeviceOrderCoordinator.saveLocalOrder(rows.map { it.deviceId })
        exitDeviceOrderEditMode()
    }

    fun saveDeviceCardPosition(deviceId: String, x: Float?, y: Float?) {
        viewModelScope.launch {
            repository.layouts.saveCard(deviceId, x, y)
        }
    }

    fun saveDeviceTilePosition(deviceId: String, x: Float?, y: Float?) {
        viewModelScope.launch {
            repository.layouts.saveTile(deviceId, x, y)
        }
    }

    fun saveDeviceCardMenuOrder(deviceId: String, menuOrder: String) {
        viewModelScope.launch {
            repository.layouts.saveCard(deviceId, null, null, menuOrder = menuOrder)
        }
    }

    fun saveDeviceTileMenuOrder(deviceId: String, menuOrder: String) {
        viewModelScope.launch {
            repository.layouts.saveTile(deviceId, null, null, menuOrder = menuOrder)
        }
    }
}

data class FreestyleEditSnapshot(
    val cardOptionsPos: Pair<Float?, Float?>,
    val cardVerticalOptionsPos: Pair<Float?, Float?>,
    val tileOptionsPos: Pair<Float?, Float?>,
    val optionsMenuOrder: String,
    val cardNodeOffsets: Map<String, Pair<Float?, Float?>>,
    val cardVerticalNodeOffsets: Map<String, Pair<Float?, Float?>>,
    val tileNodeOffsets: Map<String, Pair<Float?, Float?>>,
    val cardMenuOrders: Map<String, String>,
    val cardVerticalMenuOrders: Map<String, String>,
    val tileMenuOrders: Map<String, String>,
    val freestyleLayoutMode: FreestyleLayoutMode
)
