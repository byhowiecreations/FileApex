package com.fileapex.data.bulletin

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.note.NoteRecord

fun PairedDeviceEntity.supportsBulletinSync(): Boolean =
    clientVersionCode >= BulletinBoardPolicy.BULLETIN_SYNC_MIN_VERSION_CODE

fun MessageEntity.toNoteRecord(): NoteRecord {
    val selfId = runCatching { loadLocalIdentity().deviceId }.getOrDefault("")
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    var contentText = content
    var attachmentFileName: String? = null
    var attachmentSizeBytes = 0L
    var attachmentLocalPath: String? = null
    var checksum: String? = null
    var driveFileId: String? = null
    var previewBase64: String? = null
    var pinned = isPinned

    if (contentType == BulletinContentType.FILE_METADATA || contentType == BulletinContentType.IMAGE_PREVIEW) {
        val meta = runCatching {
            val marker = "\n{"
            val idx = content.indexOf(marker)
            val jsonBody = if (idx >= 0) content.substring(idx + 1) else content
            json.decodeFromString<BulletinFileMetadata>(jsonBody)
        }.getOrNull()
        if (meta != null) {
            val caption = run {
                val marker = "\n{"
                val idx = content.indexOf(marker)
                if (idx >= 0) content.substring(0, idx).trim() else ""
            }
            contentText = caption
            attachmentFileName = meta.fileName
            attachmentSizeBytes = meta.sizeBytes
            attachmentLocalPath = meta.localPath
            checksum = meta.sha256
            driveFileId = meta.driveFileId
            previewBase64 = meta.previewBase64
            pinned = isPinned || meta.previewBase64 != null
        }
    }

    return NoteRecord(
        noteId = id,
        sourceDeviceId = originDeviceId,
        sourceDeviceName = senderName,
        content = contentText,
        driveFileId = driveFileId,
        checksum = checksum,
        epochMs = timestamp,
        isMine = originDeviceId == selfId,
        attachmentFileName = attachmentFileName,
        attachmentSizeBytes = attachmentSizeBytes,
        attachmentPinned = pinned,
        attachmentLocalPath = attachmentLocalPath,
        attachmentPreviewBase64 = previewBase64
    )
}
