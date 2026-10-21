package com.fileapex.domain.clipboard

expect object ClipboardE2ee {
    fun publicKeyBase64(): String

    /** [context] is bound into the ciphertext when the peer takes the newer format (the clipboard push passes its timestamp). */
    fun encrypt(
        plaintext: ByteArray,
        localDeviceId: String,
        peerDeviceId: String,
        peerPublicKeyBase64: String,
        context: String = ""
    ): String

    fun decrypt(
        ciphertextBase64: String,
        localDeviceId: String,
        peerDeviceId: String,
        peerPublicKeyBase64: String,
        context: String = ""
    ): ByteArray

    fun authenticate(
        data: ByteArray,
        localDeviceId: String,
        peerDeviceId: String,
        peerPublicKeyBase64: String
    ): ByteArray
}
