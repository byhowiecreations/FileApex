package com.fileapex.domain.clipboard

import kotlinx.serialization.Serializable

@Serializable
data class ClipboardSendRequest(
    val senderDeviceId: String,
    val senderDeviceName: String,
    val senderPublicKey: String = "",
    val ciphertext: String = "",
    val capturedAtEpochMs: Long = 0L,
    /** Always empty on the wire. Kept so older clients can decode the payload. */
    val text: String = ""
)

@Serializable
data class ClipboardSendResponse(
    val status: String,
    val recipientDeviceName: String,
    val message: String? = null
)
