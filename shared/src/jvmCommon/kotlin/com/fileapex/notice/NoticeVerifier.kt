package com.fileapex.notice

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Ed25519 through BouncyCastle's lightweight API: Android's bundled "BC" provider has no Ed25519, so no provider lookup happens here. */
object NoticeVerifier {
    const val PUBLIC_KEY_BYTES = 32

    fun verify(message: ByteArray, signature: ByteArray, publicKeys: List<ByteArray>): Boolean {
        if (signature.size != NoticeEnvelopeParser.SIGNATURE_BYTES) return false
        return publicKeys.any { key ->
            key.size == PUBLIC_KEY_BYTES && Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(key, 0))
                update(message, 0, message.size)
                verifySignature(signature)
            }
        }
    }
}
