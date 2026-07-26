package com.fileapex.presentation

import androidx.compose.runtime.Immutable
import com.fileapex.data.db.PairedDeviceEntity
import kotlinx.serialization.Serializable

/**
 * SSOT hardware identity for device list/grid rendering and LAN peer payloads.
 */
@Immutable
@Serializable
data class DeviceHardwareProfile(
    val os: String = "",
    val platform: String = "",
    val deviceMake: String = "",
    val deviceModel: String = ""
) {
    companion object {
        fun from(entity: PairedDeviceEntity): DeviceHardwareProfile =
            DeviceHardwareProfile(
                os = entity.os,
                platform = entity.platform,
                deviceMake = entity.deviceMake,
                deviceModel = entity.deviceModel
            )

        fun from(row: DeviceListRow): DeviceHardwareProfile =
            DeviceHardwareProfile(
                os = row.os,
                platform = row.platform,
                deviceMake = row.deviceMake,
                deviceModel = row.deviceModel
            )
    }
}
