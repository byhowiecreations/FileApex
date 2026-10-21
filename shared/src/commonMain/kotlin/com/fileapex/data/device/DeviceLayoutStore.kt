package com.fileapex.data.device

import com.fileapex.data.db.DeviceDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Saves where a device sits in the card and tile layouts. Takes the repository's lock because the save reads the row and
 * then writes it, and a roster update can replace the row in between.
 */
class DeviceLayoutStore internal constructor(private val deviceDao: DeviceDao, private val lock: Mutex) {
    suspend fun saveCard(
        deviceId: String,
        x: Float?,
        y: Float?,
        sortOrder: Int = 0,
        menuOrder: String = ""
    ) {
        lock.withLock {
            val existing = deviceDao.getDevice(deviceId) ?: return@withLock
            val finalX = x ?: existing.cardPosX
            val finalY = y ?: existing.cardPosY
            val finalOrder = if (sortOrder != 0) sortOrder else existing.cardSortOrder
            val finalMenu = menuOrder.ifBlank { existing.cardMenuOrder }
            deviceDao.updateCardLayout(deviceId, finalX, finalY, finalOrder, finalMenu)
        }
    }

    suspend fun saveTile(
        deviceId: String,
        x: Float?,
        y: Float?,
        sortOrder: Int = 0,
        menuOrder: String = ""
    ) {
        lock.withLock {
            val existing = deviceDao.getDevice(deviceId) ?: return@withLock
            val finalX = x ?: existing.tilePosX
            val finalY = y ?: existing.tilePosY
            val finalOrder = if (sortOrder != 0) sortOrder else existing.tileSortOrder
            val finalMenu = menuOrder.ifBlank { existing.tileMenuOrder }
            deviceDao.updateTileLayout(deviceId, finalX, finalY, finalOrder, finalMenu)
        }
    }
}
