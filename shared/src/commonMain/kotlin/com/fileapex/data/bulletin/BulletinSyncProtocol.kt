package com.fileapex.data.bulletin

import kotlinx.serialization.Serializable

@Serializable
data class BulletinFileMetadata(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val originNode: String,
    val localPath: String? = null,
    val driveFileId: String? = null,
    val previewBase64: String? = null
)

@Serializable
data class BulletinSyncItem(
    val payloadType: Int,
    val payloadId: String,
    val body: String
)

@Serializable
data class BulletinSyncBatch(
    val packetId: String,
    val originDeviceId: String,
    val items: List<BulletinSyncItem>
)

@Serializable
data class BulletinSyncAck(
    val packetId: String,
    val originDeviceId: String,
    val acceptedPayloadIds: List<String>
)

@Serializable
data class BulletinMessagePayload(
    val id: String,
    val originDeviceId: String,
    val senderName: String,
    val content: String,
    val contentType: Int,
    val timestamp: Long,
    val isPinned: Boolean = false
)

@Serializable
data class BulletinTombstonePayload(
    val id: String,
    val deletedAt: Long,
    val originDeviceId: String,
    val remotePurge: Boolean = false
)

@Serializable
data class BulletinRetractByKindPayload(
    val originDeviceId: String,
    val contentType: Int,
    val deletedAt: Long
)
