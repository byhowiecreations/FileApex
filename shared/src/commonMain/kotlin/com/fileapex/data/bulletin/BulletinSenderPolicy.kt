package com.fileapex.data.bulletin

import com.fileapex.data.device.DeviceDisplayNames
import com.fileapex.di.FileApexServices

object BulletinSenderPolicy {
    suspend fun displayName(originDeviceId: String, legacySenderName: String = ""): String {
        val id = originDeviceId.trim()
        if (id.isEmpty()) {
            return DeviceDisplayNames.resolve(legacySenderName, null)
        }
        return runCatching {
            FileApexServices.deviceRepository.displayNameFor(id, legacySenderName)
        }.getOrDefault(DeviceDisplayNames.resolve(legacySenderName, null))
    }
}
