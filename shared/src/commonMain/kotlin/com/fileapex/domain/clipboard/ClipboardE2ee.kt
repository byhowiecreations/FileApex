package com.fileapex.domain.clipboard

expect object ClipboardE2ee {
    fun publicKeyBase64(): String

    fun encrypt(
        plaintext: ByteArray,
        localDeviceId: String,
        peerDeviceId: String,
        peerPublicKeyBase64: String
    ): String

    fun decrypt(
        ciphertextBase64: String,
        localDeviceId: String,
        peerDeviceId: String,
        peerPublicKeyBase64: String
    ): ByteArray
}
