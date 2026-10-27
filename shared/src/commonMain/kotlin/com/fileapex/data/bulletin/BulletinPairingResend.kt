package com.fileapex.data.bulletin

import com.fileapex.data.db.PairedDeviceEntity

object BulletinPairingResend {
    fun entries(
        peer: PairedDeviceEntity,
        messageIds: List<String>,
        tombstoneIds: List<String>,
        flagIds: List<String>,
        checklistItemIds: List<String>,
        now: Long
    ): List<OutboxEntity> {
        val owed = messageIds.map { BulletinPayloadType.MESSAGE to it } +
            tombstoneIds.map { BulletinPayloadType.TOMBSTONE to it } +
            if (BulletinFlags.supportedBy(peer.clientVersionCode)) {
                flagIds.map { BulletinPayloadType.FLAG to it } + checklistItemIds.map { BulletinPayloadType.CHECKLIST_ITEM to it }
            } else {
                emptyList()
            }
        return owed.map { (type, id) -> OutboxEntity(targetDeviceId = peer.deviceId, payloadType = type, payloadId = id, createdAt = now) }
    }
}
