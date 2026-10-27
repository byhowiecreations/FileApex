package com.fileapex.data.device

import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.RemovedDeviceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class InMemoryDeviceDao : DeviceDao {
    val devices = mutableMapOf<String, PairedDeviceEntity>()
    val removed = mutableMapOf<String, RemovedDeviceEntity>()

    override fun getAllDevicesUnordered(): Flow<List<PairedDeviceEntity>> = emptyFlow()
    override suspend fun getAllDevicesOnceUnordered() = devices.values.filter { !it.isRemoved }.toList()
    override suspend fun getDevice(deviceId: String) = devices[deviceId]
    override suspend fun getTombstonedDevices() = devices.values.filter { it.isRemoved }.toList()
    override suspend fun getAllDevicesIncludingTombstonesUnordered() = devices.values.toList()
    override suspend fun upsertDevice(device: PairedDeviceEntity) { devices[device.deviceId] = device }
    override suspend fun deleteDevice(deviceId: String) { devices.remove(deviceId) }
    override suspend fun deleteAllDevices() = devices.clear()
    override suspend fun insertRemovedDevice(device: RemovedDeviceEntity) { removed[device.deviceId] = device }
    override suspend fun countRemovedById(deviceId: String) = if (removed.containsKey(deviceId)) 1 else 0
    override suspend fun clearRemovedDevice(deviceId: String) { removed.remove(deviceId) }
    override suspend fun clearRemovedByPublicKeyHash(publicKeyHash: String) {
        removed.values.removeAll { it.publicKeyHash == publicKeyHash }
    }
    override suspend fun getAllRemovedDevices() = removed.values.toList()
    override suspend fun deleteAllRemovedDevices() = removed.clear()
    override suspend fun touchLastSeen(deviceId: String, ip: String, port: Int, epochMs: Long) {
        devices[deviceId]?.let { devices[deviceId] = it.copy(lastKnownIp = ip, port = port, lastSeenEpochMs = epochMs) }
    }
    override suspend fun touchLastSeenEpoch(deviceId: String, epochMs: Long) {
        devices[deviceId]?.let { devices[deviceId] = it.copy(lastSeenEpochMs = epochMs) }
    }
    override suspend fun updateTailnet(deviceId: String, hostname: String, ipv4: String) {
        devices[deviceId]?.let { devices[deviceId] = it.copy(tailnetHostname = hostname, tailnetIpv4 = ipv4) }
    }
    override suspend fun updateTls(deviceId: String, pin: String, alt: String, port: Int) {
        devices[deviceId]?.let { devices[deviceId] = it.copy(tlsPin = pin, tlsPinAlt = alt, tlsPort = port) }
    }
    override suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) {
        devices[deviceId]?.let { devices[deviceId] = it.copy(lastKnownIp = ip, port = port) }
    }
    override suspend fun updateCardLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String) {}
    override suspend fun updateTileLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String) {}
    override suspend fun renameDevice(deviceId: String, deviceName: String) {
        devices[deviceId]?.let { devices[deviceId] = it.copy(deviceName = deviceName) }
    }
}
