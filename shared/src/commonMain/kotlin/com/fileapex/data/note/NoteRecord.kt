package com.fileapex.data.note

import kotlinx.serialization.Serializable

@Serializable
data class NoteRecord(
    val noteId: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val content: String,
    val driveFileId: String? = null,
    val checksum: String? = null,
    val epochMs: Long,
    val isMine: Boolean = false,
    val attachmentFileName: String? = null,
    val attachmentSizeBytes: Long = 0L,
    val attachmentPinned: Boolean = false,
    val attachmentLocalPath: String? = null,
    /** Compressed inline preview for bulletin image messages (not the full file). */
    val attachmentPreviewBase64: String? = null,
    val contentType: Int = 0
)
