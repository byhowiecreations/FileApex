package com.fileapex.cloud

/** Silent FCM data payload keys — no notification channel; high-priority data-only wake. */
object FcmWakeProtocol {
    const val TYPE_PRESENCE_WAKE = "presence_wake"
    const val TYPE_DIAGNOSTICS_REQUEST = "diagnostics_request"
    const val TYPE_NOTE_INLINE = "note_inline"
    const val TYPE_NOTE_SYNC = "note_sync"
    const val TYPE_NOTE_DELETE = "note_delete"
    const val TYPE_DRIVE_RELAY = "drive_relay"

    const val KEY_TYPE = "type"
    const val KEY_SOURCE_DEVICE_ID = "sourceDeviceId"
    const val KEY_SESSION_ID = "sessionId"
    const val KEY_NOTE_ID = "noteId"
    const val KEY_CONTENT = "content"
    const val KEY_DRIVE_FILE_ID = "driveFileId"
    const val KEY_CHECKSUM = "checksum"
    const val KEY_EPOCH_MS = "epochMs"
    const val KEY_ENTRY_ID = "entryId"
    const val KEY_ATTACHMENT_NAME = "attachmentName"
    const val KEY_ATTACHMENT_SIZE = "attachmentSize"

    object Keys {
        const val TYPE = "type"
        const val SOURCE_DEVICE_ID = "sourceDeviceId"
        const val SESSION_ID = "sessionId"
        const val NOTE_ID = "noteId"
        const val CONTENT = "content"
        const val DRIVE_FILE_ID = "driveFileId"
        const val CHECKSUM = "checksum"
        const val EPOCH_MS = "epochMs"
        const val ENTRY_ID = "entryId"
        const val ATTACHMENT_NAME = "attachmentName"
        const val ATTACHMENT_SIZE = "attachmentSize"
    }
}
