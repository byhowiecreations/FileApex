package com.fileapex.data.note

import com.fileapex.data.device.DeviceDisplayNames

/**
 * Incoming Bulletin Board posts use note notifications.
 * Attachment bytes becoming available use file-transfer notifications.
 */
object NoteNotifyPolicy {
    fun incomingPreview(content: String, attachmentFileName: String?): String =
        content.trim().ifBlank { attachmentFileName?.trim().orEmpty() }

    fun notificationTitle(sourceDeviceName: String): String =
        "Bulletin Board · ${DeviceDisplayNames.resolve(sourceDeviceName, null)}"

    fun shouldNotifyIncomingNote(
        isMine: Boolean,
        alreadyNotified: Boolean,
        preview: String
    ): Boolean = !isMine && !alreadyNotified && preview.isNotBlank()

    fun shouldNotifyAttachmentReady(
        isMine: Boolean,
        alreadyHadLocalFile: Boolean,
        fileName: String
    ): Boolean = !isMine && !alreadyHadLocalFile && fileName.isNotBlank()
}
