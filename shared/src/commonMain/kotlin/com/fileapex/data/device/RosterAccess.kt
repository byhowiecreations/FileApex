package com.fileapex.data.device

import com.fileapex.data.db.PairedDeviceEntity

/** What ingest, aliases and tombstones need from the roster; [DeviceRepository] supplies it so the lock stays in one place. */
internal interface RosterAccess {
    suspend fun tombstoneVersionLocked(deviceId: String, publicKeyHash: String): Long
    suspend fun tombstoneBlocksLocked(device: PairedDeviceEntity): Boolean
    suspend fun writeDevice(device: PairedDeviceEntity)
    suspend fun aliasesLocked(canonical: PairedDeviceEntity): List<PairedDeviceEntity>
}
