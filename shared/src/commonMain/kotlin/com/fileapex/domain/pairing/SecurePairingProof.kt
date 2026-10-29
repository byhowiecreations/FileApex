package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

/**
 * Proof that both ends of a pairing hold the same secret and saw the same TLS keys. Nothing here reads a hardware
 * value: only the pairing secret or code, random nonces, app-generated TLS pins and app-generated device ids.
 *
 * A man in the middle holds two TLS sessions with different keys on each leg, so the pins in the proof differ from
 * what each end computed and the check fails. The QR secret is 128 random bits that are never broadcast, so nobody
 * who saw the network can forge it. Without a QR, [PairingSas] makes both screens show the same number instead.
 */
object SecurePairingProof {
    const val PROTOCOL = "fileapex.v1.pairing-secure"
    private const val SALT = "fileapex-pair-secure-v1"
    private val random = SecureRandom()

    fun newSecretHex(): String = randomBytes(16).toHex()

    fun newNonceHex(): String = randomBytes(16).toHex()

    /** The QR secret as its 16 bytes. Null when malformed. */
    fun passwordFromSecret(secretHex: String): ByteArray? =
        secretHex.trim().lowercase().takeIf { it.length == 32 && it.all { c -> c in "0123456789abcdef" } }
            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

    fun macA(password: ByteArray, nonceA: String, hostPin: String, joinerPin: String, hostId: String, joinerId: String): String =
        mac(password, "A", nonceA, "", hostPin, joinerPin, hostId, joinerId)

    fun macB(
        password: ByteArray, nonceA: String, nonceB: String, hostPin: String, joinerPin: String, hostId: String, joinerId: String
    ): String = mac(password, "B", nonceA, nonceB, hostPin, joinerPin, hostId, joinerId)

    fun equal(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.lowercase().encodeToByteArray(), right.lowercase().encodeToByteArray())

    private fun mac(
        password: ByteArray, role: String, nonceA: String, nonceB: String,
        hostPin: String, joinerPin: String, hostId: String, joinerId: String
    ): String {
        val key = hmac(SALT.encodeToByteArray(), password)
        try {
            return macWith(key, role, nonceA, nonceB, hostPin, joinerPin, hostId, joinerId)
        } finally {
            key.fill(0)
        }
    }

    private fun macWith(
        key: ByteArray, role: String, nonceA: String, nonceB: String,
        hostPin: String, joinerPin: String, hostId: String, joinerId: String
    ): String {
        val fields = listOf(role, nonceA, nonceB, hostPin.lowercase(), joinerPin.lowercase(), hostId, joinerId)
        // Length-prefixed so no two different field lists produce the same bytes.
        val message = fields.joinToString("") { "${it.length}:$it;" }
        return hmac(key, message.encodeToByteArray()).toHex()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** QR path: one message, proof of the QR secret. */
@Serializable
data class SecurePairRequest(
    val nonceA: String,
    val macA: String,
    val scannerDevice: PairedDeviceEntity,
    val pin: String = ""
)

@Serializable
data class SecurePairResponse(val nonceB: String, val macB: String)

/** Comparison path, step 1: the joiner commits to a random value before it sees the host's. */
@Serializable
data class SasOfferRequest(val commit: String, val scannerDevice: PairedDeviceEntity, val pin: String = "")

@Serializable
data class SasOfferResponse(val sessionId: String, val nonceA: String)

/** Step 2: the joiner reveals its value; both screens now show the same number. */
@Serializable
data class SasRevealRequest(val sessionId: String, val nonceB: String)

/** Step 3: the joiner's user said whether the numbers match; the host answers when its own user has decided. */
@Serializable
data class SasDecideRequest(val sessionId: String, val approved: Boolean)
