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
    val isMine: Boolean = false
)
