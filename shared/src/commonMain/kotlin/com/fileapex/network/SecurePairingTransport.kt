package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity

/** Common to both ways of pairing: where the host is and who the joiner is. */
data class SecurePairingExchange(
    val host: String,
    val tlsPort: Int,
    val hostDeviceId: String,
    val scanner: PairedDeviceEntity,
    val devicePin: String,
    val timeoutMs: Long,
    /** Scanned QR: the host key it carried (checked before anything is sent) and the secret that proves both ends. */
    val qrHostPin: String = "",
    val qrSecret: String = ""
)

sealed interface SecurePairingOutcome {
    /** The host accepted; [hostPin] is the key its certificate showed, which the number or the QR secret vouched for. */
    data class Paired(val hostPin: String) : SecurePairingOutcome

    /** The host refused: `pairing_code_invalid`, `pin_required`, `pairing_busy`, `pairing_declined`, `slow_down`. */
    data class Rejected(val code: String) : SecurePairingOutcome

    data class Unreachable(val reason: String) : SecurePairingOutcome

    /** The certificate did not match the QR, or the reply did not prove the secret. Nothing is stored. */
    data class NotVerified(val reason: String) : SecurePairingOutcome

    /** This device's user said the numbers did not match. */
    data object Declined : SecurePairingOutcome
}

/**
 * QR pairing: one TLS exchange that proves the QR secret. [SecurePairingExchange.qrSecret] must be set.
 * Never falls back to plain HTTP.
 */
expect suspend fun securePairingQrExchange(request: SecurePairingExchange): SecurePairingOutcome

/**
 * Pairing by comparison: [onCode] gets the six digits both screens show and returns whether this device's user said
 * they match. Never falls back to plain HTTP.
 */
expect suspend fun securePairingSasExchange(
    request: SecurePairingExchange,
    onCode: suspend (String) -> Boolean
): SecurePairingOutcome
