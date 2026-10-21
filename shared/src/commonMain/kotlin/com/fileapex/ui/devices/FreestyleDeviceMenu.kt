package com.fileapex.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.presentation.DeviceListRow

@Composable
internal fun FreestyleScope.FreestyleDeviceMenu(row: DeviceListRow, livePos: Offset) {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
                    val savedMenuOrder = when (freestyleMode) {
                        FreestyleLayoutMode.CARDS_HORIZONTAL -> {
                            persistedCardMenuOrders[row.deviceId]
                                ?: persistedCardMenuOrders.values.firstOrNull { it.isNotBlank() }
                                ?: deviceRows.mapNotNull { it.cardMenuOrder.takeIf { o -> o.isNotBlank() } }.firstOrNull()
                                ?: row.cardMenuOrder
                        }
                        FreestyleLayoutMode.CARDS_VERTICAL -> {
                            persistedCardVerticalMenuOrders[row.deviceId]
                                ?: persistedCardVerticalMenuOrders.values.firstOrNull { it.isNotBlank() }
                                ?: persistedCardMenuOrders[row.deviceId]
                                ?: persistedCardMenuOrders.values.firstOrNull { it.isNotBlank() }
                                ?: deviceRows.mapNotNull { it.cardMenuOrder.takeIf { o -> o.isNotBlank() } }.firstOrNull()
                                ?: row.cardMenuOrder
                        }
                        FreestyleLayoutMode.TILES -> {
                            persistedTileMenuOrders[row.deviceId]
                                ?: persistedTileMenuOrders.values.firstOrNull { it.isNotBlank() }
                                ?: deviceRows.mapNotNull { it.tileMenuOrder.takeIf { o -> o.isNotBlank() } }.firstOrNull()
                                ?: row.tileMenuOrder
                        }
                    }
                    val actionsListState = remember(row.deviceId, freestyleMode, savedMenuOrder) {
                        mutableStateOf(FreestyleMenuAction.parseOrder(savedMenuOrder))
                    }

        when (freestyleMode) {
            FreestyleLayoutMode.CARDS_HORIZONTAL -> FreestyleHorizontalCardMenu(row, livePos, actionsListState)
            FreestyleLayoutMode.CARDS_VERTICAL -> FreestyleVerticalCardMenu(row, livePos, actionsListState)
            FreestyleLayoutMode.TILES -> FreestyleTileMenu(row, livePos, actionsListState)
        }
    } } } }
}
