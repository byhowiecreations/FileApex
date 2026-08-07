package com.fileapex.domain.clipboard

import kotlinx.serialization.Serializable

@Serializable
data class ClipboardSendRequest(
    val senderDeviceId: String,
    val senderDeviceName: String,
    val text: String
)

@Serializable
data class ClipboardSendResponse(
    val status: String,
    val recipientDeviceName: String,
    val message: String? = null
)
