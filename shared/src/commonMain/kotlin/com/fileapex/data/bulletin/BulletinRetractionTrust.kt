package com.fileapex.data.bulletin

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import kotlinx.serialization.json.Json

/**
 * A delete or retract is taken only when it names this device or a paired one, so a stranger's id cannot remove
 * notes. The sender is acknowledged either way, so it does not keep retrying an item that will never be applied.
 */
internal suspend fun BulletinSyncItem.retractsForAnUntrustedDevice(json: Json): Boolean {
    val origin = when (payloadType) {
        BulletinPayloadType.TOMBSTONE -> json.decodeFromString<BulletinTombstonePayload>(body).originDeviceId
        BulletinPayloadType.RETRACT_BY_KIND -> json.decodeFromString<BulletinRetractByKindPayload>(body).originDeviceId
        else -> return false
    }.trim()
    if (origin.isNotEmpty() && origin == loadLocalIdentity().deviceId) return false
    return FileApexServices.deviceRepository.getDevice(origin)?.isRemoved != false
}
