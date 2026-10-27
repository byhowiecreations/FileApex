package com.fileapex.domain.clipboard

import com.fileapex.security.E2eePrimitives.hkdfSha256
import com.fileapex.security.wipe
import com.fileapex.security.E2eePrimitives.hmacSha256
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
import org.bouncycastle.math.ec.rfc7748.X25519

object ClipboardCrypto {
    private const val HKDF_INFO = "FileApex-Clipboard-v1"
    private const val HELLO_HKDF_INFO = "FileApex-Hello-v1"
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

    fun decodeKey(base64: String, label: String): ByteArray {
        val raw = Base64.getDecoder().decode(base64.trim())
        require(raw.size == 32) { "Invalid clipboard $label length" }
        return raw
    }

    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        val publicKey = ByteArray(X25519.POINT_SIZE)
        X25519.generatePublicKey(privateKey, 0, publicKey, 0)
        return publicKey
    }

    fun encrypt(
        plaintext: ByteArray,
        localPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        salt: String,
        aad: ByteArray? = null
    ): String {
        val key = deriveAesKey(localPrivateKey, peerPublicKey, salt)
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
        salt: String,
        aad: ByteArray? = null
    ): ByteArray {
        val payload = Base64.getDecoder().decode(payloadBase64.trim())
        require(payload.size > GCM_IV_BYTES) { "Encrypted clipboard payload too short" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val key = deriveAesKey(localPrivateKey, peerPublicKey, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            aad?.let(cipher::updateAAD)
            return cipher.doFinal(ciphertext)
        } finally {
            key.wipe()
        }
    }

    /** HMAC-SHA256 of [data] under a key derived for wake hellos only, never the clipboard encryption key. */
    fun authenticate(data: ByteArray, localPrivateKey: ByteArray, peerPublicKey: ByteArray, salt: String): ByteArray {
        val shared = x25519SharedSecret(localPrivateKey, peerPublicKey)
        try {
            val key = hkdfSha256(shared, salt.encodeToByteArray(), HELLO_HKDF_INFO.encodeToByteArray(), 32)
            try {
                return hmacSha256(key, data)
            } finally {
                key.wipe()
            }
        } finally {
            shared.wipe()
        }
    }

    fun pairSalt(deviceIdA: String, deviceIdB: String): String {
        return listOf(deviceIdA.trim(), deviceIdB.trim()).sorted().joinToString("|")
    }

    private fun deriveAesKey(
        localPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        salt: String
    ): ByteArray {
        val shared = x25519SharedSecret(localPrivateKey, peerPublicKey)
        try {
            return hkdfSha256(
                ikm = shared,
                salt = salt.encodeToByteArray(),
                info = HKDF_INFO.encodeToByteArray(),
                length = 32
            )
        } finally {
            shared.wipe()
        }
    }
}
