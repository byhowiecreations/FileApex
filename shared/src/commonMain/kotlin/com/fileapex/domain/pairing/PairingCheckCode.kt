package com.fileapex.domain.pairing

import com.fileapex.util.sha256Hex

/**
 * The short code both screens show while two devices pair. It is derived from both devices' TLS keys and the one-time
 * pairing code, so if anyone sits in the middle with a different key the two screens show different numbers. The
 * Docker daemon computes the same value (native/tsnet/daemon/paircheck.go, same test vectors).
 */
object PairingCheckCode {
    private const val CONTEXT = "fileapex-pair-check-v1"

    /** A device's key for the check: its TLS pin, or its id when it has none. Lower case, trimmed. */
    fun key(pin: String, deviceId: String): String = pin.trim().lowercase().ifEmpty { deviceId.trim() }

    /** `hostKey` is the device showing the pairing code, `joinerKey` the one that entered or scanned it. */
    fun derive(hostKey: String, joinerKey: String, pairingCode: String): String {
        val code = pairingCode.filter { it.isDigit() }
        val digest = sha256Hex("$CONTEXT|$hostKey|$joinerKey|$code".encodeToByteArray())
        val number = digest.substring(0, 8).toLong(16) % 1_000_000L
        val digits = number.toString().padStart(6, '0')
        return digits.substring(0, 3) + " " + digits.substring(3)
    }
}
