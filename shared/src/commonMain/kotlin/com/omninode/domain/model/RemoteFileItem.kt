package com.omninode.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RemoteFileItem(
    val id: String,
    val name: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val mimeType: String
)

/**
 * Cross-device transfer clipboard payload.
 * Identity is deviceId; host/port are last-known and refreshed when possible.
 */
data class ClipboardPayload(
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val sourceHost: String,
    val sourcePort: Int,
    val remoteAbsolutePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val isLocalSource: Boolean
)
