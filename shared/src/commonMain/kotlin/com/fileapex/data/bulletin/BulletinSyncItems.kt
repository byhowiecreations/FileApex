package com.fileapex.data.bulletin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class BulletinSyncItems(private val repository: BulletinBoardRepository) {
    private val json = Json { encodeDefaults = true }

    fun message(message: MessageEntity) = BulletinSyncItem(
        payloadType = BulletinPayloadType.MESSAGE,
        payloadId = message.id,
        body = json.encodeToString(
            BulletinMessagePayload(
                id = message.id,
                originDeviceId = message.originDeviceId,
                senderName = message.senderName,
                content = repository.stripRemoteLocalPath(message.content, message.contentType),
                contentType = message.contentType,
                timestamp = message.timestamp,
                // Only for builds before 0.19.8, which read the lock from here; newer builds read the flag.
                isPinned = message.isPinned
            )
        )
    )

    fun tombstone(tombstone: TombstoneEntity) = BulletinSyncItem(
        payloadType = BulletinPayloadType.TOMBSTONE,
        payloadId = tombstone.id,
        body = json.encodeToString(
            BulletinTombstonePayload(tombstone.id, tombstone.deletedAt, tombstone.originDeviceId, tombstone.remotePurge)
        )
    )

    fun checklistItem(item: ChecklistItemEntity) = BulletinSyncItem(
        payloadType = BulletinPayloadType.CHECKLIST_ITEM,
        payloadId = item.itemId,
        body = json.encodeToString(BulletinChecklist.toPayload(item))
    )

    fun flag(flag: MessageFlagEntity) = BulletinSyncItem(
        payloadType = BulletinPayloadType.FLAG,
        payloadId = flag.flagId,
        body = json.encodeToString(BulletinFlags.toPayload(flag))
    )
}
