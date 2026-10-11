package com.fileapex.data.bulletin

import com.fileapex.data.device.DeviceDisplayNames
import com.fileapex.di.FileApexServices
import com.fileapex.util.cancellableCatching

object BulletinSenderPolicy {
    suspend fun displayName(originDeviceId: String, legacySenderName: String = ""): String {
        val id = originDeviceId.trim()
        if (id.isEmpty()) {
            return DeviceDisplayNames.resolve(legacySenderName, null)
        }
        return cancellableCatching {
            FileApexServices.deviceRepository.displayNameFor(id, legacySenderName)
        }.getOrDefault(DeviceDisplayNames.resolve(legacySenderName, null))
    }
}
