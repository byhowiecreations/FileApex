package com.fileapex.domain.clipboard

import com.fileapex.di.FileApexServices
import java.util.Base64

actual object ClipboardE2ee {
    actual fun publicKeyBase64(): String = ClipboardIdentityStore.ensureKeyPair().publicKeyBase64()

    actual fun encrypt(
        plaintext: ByteArray,
        localDeviceId: String,
        peerDeviceId: String,
        peerPublicKeyBase64: String
    ): String {
        val keys = ClipboardIdentityStore.ensureKeyPair()
        return ClipboardCrypto.encrypt(
            plaintext = plaintext,
            localPrivateKey = keys.privateKey,
            peerPublicKey = ClipboardCrypto.decodeKey(peerPublicKeyBase64, "public key"),
            salt = ClipboardCrypto.pairSalt(localDeviceId, peerDeviceId)
        )
    }

    actual fun decrypt(
        ciphertextBase64: String,
        localDeviceId: String,
        peerDeviceId: String,
        peerPublicKeyBase64: String
    ): ByteArray {
        val keys = ClipboardIdentityStore.ensureKeyPair()
        return ClipboardCrypto.decrypt(
            payloadBase64 = ciphertextBase64,
            localPrivateKey = keys.privateKey,
            peerPublicKey = ClipboardCrypto.decodeKey(peerPublicKeyBase64, "public key"),
            salt = ClipboardCrypto.pairSalt(localDeviceId, peerDeviceId)
        )
    }

    actual fun authenticate(
        data: ByteArray,
        localDeviceId: String,
        peerDeviceId: String,
        peerPublicKeyBase64: String
    ): ByteArray {
        val keys = ClipboardIdentityStore.ensureKeyPair()
        return ClipboardCrypto.authenticate(
            data = data,
            localPrivateKey = keys.privateKey,
            peerPublicKey = ClipboardCrypto.decodeKey(peerPublicKeyBase64, "public key"),
            salt = ClipboardCrypto.pairSalt(localDeviceId, peerDeviceId)
        )
    }
}

internal object ClipboardIdentityStore {
    @Volatile
    private var cached: Pair<String, ClipboardCrypto.KeyPairMaterial>? = null

    fun ensureKeyPair(): ClipboardCrypto.KeyPairMaterial {
        val stored = FileApexServices.settings.clipboardPrivateKeyBase64().trim()
        if (stored.isNotEmpty()) {
            cached?.takeIf { it.first == stored }?.let { return it.second }
            val privateKey = ClipboardCrypto.decodeKey(stored, "private key")
            return ClipboardCrypto.KeyPairMaterial(
                privateKey = privateKey,
                publicKey = ClipboardCrypto.publicKeyFromPrivate(privateKey)
            ).also { cached = stored to it }
        }
        val generated = ClipboardCrypto.generateKeyPair()
        FileApexServices.settings.setClipboardPrivateKeyBase64(
            Base64.getEncoder().encodeToString(generated.privateKey)
        )
        return generated
    }
}
