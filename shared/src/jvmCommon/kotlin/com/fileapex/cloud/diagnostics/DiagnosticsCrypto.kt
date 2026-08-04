package com.fileapex.cloud.diagnostics

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
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
        googleUid: String
    ): String {
        val key = deriveAesKey(localPrivateKey, peerPublicKey, googleUid)
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext)
        val payload = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(encrypted, 0, payload, iv.size, encrypted.size)
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(
        payloadBase64: String,
        localPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        googleUid: String
    ): ByteArray {
        val payload = Base64.getDecoder().decode(payloadBase64.trim())
        require(payload.size > GCM_IV_BYTES) { "Encrypted payload too short" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val key = deriveAesKey(localPrivateKey, peerPublicKey, googleUid)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveAesKey(
        localPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        googleUid: String
    ): ByteArray {
        val shared = x25519SharedSecret(localPrivateKey, peerPublicKey)
        return hkdfSha256(
            ikm = shared,
            salt = googleUid.trim().encodeToByteArray(),
            info = HKDF_INFO.encodeToByteArray(),
            length = 32
        )
    }

    private fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)
        return shared
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val input = t + info + byteArrayOf(counter.toByte())
            t = hmacSha256(prk, input)
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
            counter++
        }
        return okm
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
