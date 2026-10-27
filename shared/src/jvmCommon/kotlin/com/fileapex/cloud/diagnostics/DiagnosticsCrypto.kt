package com.fileapex.cloud.diagnostics

import com.fileapex.security.E2eePrimitives.hkdfSha256
import com.fileapex.security.wipe
import com.fileapex.security.E2eePrimitives.x25519SharedSecret
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/** X25519 + HKDF + AES-256-GCM for diagnostics relay only (not LAN / control plane). */
object DiagnosticsCrypto {
    private const val HKDF_INFO = "FileApex-Diagnostics-v1"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val secureRandom = SecureRandom()

    data class KeyPairMaterial(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    ) {
        fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(publicKey)
    }

    fun generateKeyPair(): KeyPairMaterial {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val pair = generator.generateKeyPair()
        val privateKey = (pair.private as X25519PrivateKeyParameters).encoded
        val publicKey = (pair.public as X25519PublicKeyParameters).encoded
        return KeyPairMaterial(privateKey = privateKey, publicKey = publicKey)
    }

    fun decodePublicKey(base64: String): ByteArray {
        val raw = Base64.getDecoder().decode(base64.trim())
        require(raw.size == 32) { "Invalid diagnostics public key length" }
        return raw
    }

    fun decodePrivateKey(base64: String): ByteArray {
        val raw = Base64.getDecoder().decode(base64.trim())
        require(raw.size == 32) { "Invalid diagnostics private key length" }
        return raw
    }

    fun encrypt(
        plaintext: ByteArray,
        localPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        googleUid: String,
        aad: ByteArray? = null
    ): String {
        val key = deriveAesKey(localPrivateKey, peerPublicKey, googleUid)
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val encrypted = try {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            aad?.let(cipher::updateAAD)
            cipher.doFinal(plaintext)
        } finally {
            key.wipe()
        }
        val payload = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(encrypted, 0, payload, iv.size, encrypted.size)
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(
        payloadBase64: String,
        localPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        googleUid: String,
        aad: ByteArray? = null
    ): ByteArray {
        val payload = Base64.getDecoder().decode(payloadBase64.trim())
        require(payload.size > GCM_IV_BYTES) { "Encrypted payload too short" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val key = deriveAesKey(localPrivateKey, peerPublicKey, googleUid)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            aad?.let(cipher::updateAAD)
            return cipher.doFinal(ciphertext)
        } finally {
            key.wipe()
        }
    }

    private const val BOUND_PREFIX = "v2."

    /** Sender, recipient, session and direction are authenticated with the ciphertext, so a captured message cannot be reflected or moved to another session. */
    fun boundData(from: String, to: String, sessionId: String, response: Boolean): ByteArray =
        "FileApex-Diag-v2|${from.trim()}|${to.trim()}|$sessionId|${if (response) "response" else "request"}".encodeToByteArray()

    fun encryptBound(
        plaintext: ByteArray,
        localPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        googleUid: String,
        aad: ByteArray
    ): String = BOUND_PREFIX + encrypt(plaintext, localPrivateKey, peerPublicKey, googleUid, aad)

    fun isBound(payload: String): Boolean = payload.startsWith(BOUND_PREFIX)

    /** Reads either format; the bound one needs the [aad] the sender used. */
    fun decryptEither(
        payload: String,
        localPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        googleUid: String,
        aad: ByteArray
    ): ByteArray = if (isBound(payload)) {
        decrypt(payload.removePrefix(BOUND_PREFIX), localPrivateKey, peerPublicKey, googleUid, aad)
    } else {
        decrypt(payload, localPrivateKey, peerPublicKey, googleUid)
    }

    private fun deriveAesKey(
        localPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        googleUid: String
    ): ByteArray {
        val shared = x25519SharedSecret(localPrivateKey, peerPublicKey)
        try {
            return hkdfSha256(
                ikm = shared,
                salt = googleUid.trim().encodeToByteArray(),
                info = HKDF_INFO.encodeToByteArray(),
                length = 32
            )
        } finally {
            shared.wipe()
        }
    }
}
