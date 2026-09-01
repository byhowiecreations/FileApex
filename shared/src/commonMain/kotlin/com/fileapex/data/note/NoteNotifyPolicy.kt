package com.fileapex.data.note

import com.fileapex.data.bulletin.BulletinContentType
import com.fileapex.data.device.DeviceDisplayNames

/**
 * Incoming Bulletin Board posts use note notifications.
 * Attachment bytes becoming available use file-transfer notifications.
 */
object NoteNotifyPolicy {
    fun incomingPreview(content: String, attachmentFileName: String?): String =
        content.trim().ifBlank { attachmentFileName?.trim().orEmpty() }

    fun notificationTitle(sourceDeviceName: String): String =
        com.fileapex.i18n.AppI18n.t(
            "bulletin_board_from",
            DeviceDisplayNames.resolve(sourceDeviceName, null)
        )

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

    fun isCriticalBulletin(content: String, contentType: Int = BulletinContentType.TEXT): Boolean {
        if (contentType == BulletinContentType.BATTERY_LOW) return true
        val lower = content.trim().lowercase()
        if (lower.isBlank()) return false
        return lower.startsWith("the battery level is") ||
            lower.startsWith("the battery is low on") ||
            lower == "the battery is low."
    }

    /** Swap a stale factory name embedded in auto battery posts for the roster display name. */
    fun rewriteBatteryDeviceName(content: String, storedName: String, displayName: String): String {
        if (storedName.isBlank() || storedName.equals(displayName, ignoreCase = true)) return content
        val trimmed = content.trim()
        if (!isCriticalBulletin(trimmed)) return content
        return trimmed.replace(storedName, displayName, ignoreCase = false)
    }
}
