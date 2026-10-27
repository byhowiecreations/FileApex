package com.fileapex.security.tls

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A device that replaces its TLS key signs the new pin with the old key. A peer that pinned the old key can then accept
 * the new one without asking anyone: only the holder of the old key can have written the proof.
 */
@Serializable
data class TlsRotationProof(
    /** SubjectPublicKeyInfo of the key being replaced, base64. Its SHA-256 is the pin peers stored. */
    val oldPublicKey: String,
    val newPin: String,
    val issuedAtEpochMs: Long,
    val signature: String
)

object TlsRotation {
    const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }

    fun sign(oldPrivateKey: PrivateKey, oldPublicKey: PublicKey, newPin: String, nowMs: Long): TlsRotationProof {
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(oldPrivateKey)
            update(message(newPin, nowMs))
        }
        return TlsRotationProof(
            oldPublicKey = Base64.getEncoder().encodeToString(oldPublicKey.encoded),
            newPin = newPin.lowercase(),
            issuedAtEpochMs = nowMs,
            signature = Base64.getEncoder().encodeToString(signer.sign())
        )
    }

    /** True when [proof] was signed by the key behind one of [storedPins] and names exactly [newPin]. */
    fun verify(proof: TlsRotationProof?, storedPins: Set<String>, newPin: String, nowMs: Long): Boolean {
        proof ?: return false
        if (!TlsPinFormat.isValid(proof.newPin) || !proof.newPin.equals(newPin, ignoreCase = true)) return false
        if (nowMs - proof.issuedAtEpochMs > MAX_AGE_MS || proof.issuedAtEpochMs - nowMs > TlsPinAnnouncements.MAX_SKEW_MS) return false
        return runCatching {
            val encoded = Base64.getDecoder().decode(proof.oldPublicKey)
            val oldPin = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
            if (storedPins.none { it.isNotEmpty() && it.equals(oldPin, ignoreCase = true) }) return false
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(message(proof.newPin, proof.issuedAtEpochMs))
                verify(Base64.getDecoder().decode(proof.signature))
            }
        }.getOrDefault(false)
    }

    fun encode(proof: TlsRotationProof): String = json.encodeToString(TlsRotationProof.serializer(), proof)

    fun decode(text: String): TlsRotationProof? =
        if (text.isBlank()) null else runCatching { json.decodeFromString(TlsRotationProof.serializer(), text) }.getOrNull()

    private fun message(newPin: String, issuedAt: Long) = "fileapex-tls-rotation-v1|${newPin.lowercase()}|$issuedAt".toByteArray()
}

object TlsPinFormat {
    private val FORMAT = Regex("[0-9a-f]{64}")
    fun isValid(pin: String): Boolean = FORMAT.matches(pin.lowercase())
}
