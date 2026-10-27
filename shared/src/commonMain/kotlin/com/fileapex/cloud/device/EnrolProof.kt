package com.fileapex.cloud.device

import java.security.SecureRandom
import java.util.Base64

/**
 * The proof a device sends with an enrol request: an HMAC under the secret it shares with the relay's X25519 key,
 * made with the key its device id is derived from. The relay builds the same HMAC (relay Worker `ProofKey.verify`).
 */
internal object EnrolProof {
    const val RELAY_ID = "fileapex-relay"

    fun message(deviceId: String, tsSeconds: Long, nonce: String): ByteArray =
        "FileApex-Enrol-v1|$deviceId|$tsSeconds|$nonce".encodeToByteArray()

    /** [authenticate] is `ClipboardE2ee.authenticate(it, deviceId, RELAY_ID, relayKey)` in the app. */
    fun tag(deviceId: String, tsSeconds: Long, nonce: String, authenticate: (ByteArray) -> ByteArray): String =
        Base64.getEncoder().encodeToString(authenticate(message(deviceId, tsSeconds, nonce)))

    fun newNonce(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
