package com.fileapex.domain.clipboard

import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.data.settings.SecretAvailability
import com.fileapex.di.FileApexServices
import java.util.Base64

actual object ClipboardE2ee {
    actual fun publicKeyBase64(): String = ClipboardIdentityStore.ensureKeyPair().publicKeyBase64()

    actual fun encrypt(
        plaintext: ByteArray,
        localDeviceId: String,
        peerDeviceId: String,
        peerPublicKeyBase64: String,
        context: String
    ): String {
        val keys = ClipboardIdentityStore.ensureKeyPair()
        val bound = PeerE2eeVersions.supportsBoundFormat(peerDeviceId)
        val sealed = ClipboardCrypto.encrypt(
            plaintext = plaintext,
            localPrivateKey = keys.privateKey,
            peerPublicKey = ClipboardCrypto.decodeKey(peerPublicKeyBase64, "public key"),
            salt = ClipboardCrypto.pairSalt(localDeviceId, peerDeviceId),
            aad = if (bound) boundData(localDeviceId, peerDeviceId, context) else null
        )
        return if (bound) BOUND_PREFIX + sealed else sealed
    }

    actual fun decrypt(
        ciphertextBase64: String,
        localDeviceId: String,
        peerDeviceId: String,
        peerPublicKeyBase64: String,
        context: String
    ): ByteArray {
        val keys = ClipboardIdentityStore.ensureKeyPair()
        val bound = ciphertextBase64.startsWith(BOUND_PREFIX)
        return ClipboardCrypto.decrypt(
            payloadBase64 = ciphertextBase64.removePrefix(BOUND_PREFIX),
            localPrivateKey = keys.privateKey,
            peerPublicKey = ClipboardCrypto.decodeKey(peerPublicKeyBase64, "public key"),
            salt = ClipboardCrypto.pairSalt(localDeviceId, peerDeviceId),
            aad = if (bound) boundData(peerDeviceId, localDeviceId, context) else null
        )
    }

    /** Sender, recipient and context are authenticated with the ciphertext, so a message cannot be reflected back or replayed under another timestamp. */
    private fun boundData(from: String, to: String, context: String): ByteArray =
        "FileApex-E2EE-v2|${from.trim()}|${to.trim()}|$context".encodeToByteArray()

    private const val BOUND_PREFIX = "v2."

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
// The device id comes from this key; a key that exists but cannot be opened right now must not be replaced.
        SecretAvailability.requireAvailable(BaseAppSettings.KEY_CLIPBOARD_PRIVATE_KEY)
        val generated = ClipboardCrypto.generateKeyPair()
        FileApexServices.settings.setClipboardPrivateKeyBase64(
            Base64.getEncoder().encodeToString(generated.privateKey)
        )
        return generated
    }
}
