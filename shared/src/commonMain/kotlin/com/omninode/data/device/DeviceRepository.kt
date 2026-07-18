package com.omninode.data.device

import com.omninode.data.db.DeviceDao
import com.omninode.data.db.PairedDeviceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for paired-device CRUD.
 */
class DeviceRepository(private val deviceDao: DeviceDao) {
    fun observeDevices(): Flow<List<PairedDeviceEntity>> = deviceDao.getAllDevices()

    suspend fun listDevices(): List<PairedDeviceEntity> = deviceDao.getAllDevicesOnce()

    suspend fun getDevice(deviceId: String): PairedDeviceEntity? = deviceDao.getDevice(deviceId)

    suspend fun upsert(device: PairedDeviceEntity) {
        deviceDao.upsertDevice(device)
    }

    suspend fun rename(deviceId: String, newName: String) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Device name cannot be empty" }
        deviceDao.renameDevice(deviceId, trimmed)
    }

    suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) {
        deviceDao.updateEndpoint(deviceId, ip, port)
    }

    suspend fun remove(deviceId: String) {
        deviceDao.deleteDevice(deviceId)
    }
}
