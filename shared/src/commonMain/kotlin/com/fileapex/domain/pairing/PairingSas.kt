package com.fileapex.domain.pairing

import com.fileapex.util.sha256Hex

/**
 * The short number both screens show when two devices pair without a QR. It is derived from both TLS keys, both device
 * ids and a random value from each side, so a man in the middle (who has to use a different key on each leg) shows two
 * different numbers.
 *
 * The joiner commits to its random value before it sees the host's and only then reveals it. Without that, an attacker
 * could keep generating keys until the two legs happened to show the same six digits; with it, each attempt has a
 * one in a million chance and every failed attempt is a visible mismatch on a screen.
 */
object PairingSas {
    private const val COMMIT_CONTEXT = "fileapex-pair-sas-commit-v1"
    private const val CODE_CONTEXT = "fileapex-pair-sas-v1"

    fun newNonce(): String = SecurePairingProof.newNonceHex()

    fun commit(nonceB: String, joinerPin: String, joinerId: String): String =
        sha256Hex("$COMMIT_CONTEXT|${nonceB.lowercase()}|${joinerPin.lowercase()}|$joinerId".encodeToByteArray())

    fun commitMatches(commit: String, nonceB: String, joinerPin: String, joinerId: String): Boolean =
        SecurePairingProof.equal(commit, commit(nonceB, joinerPin, joinerId))

    /** Six digits shown as "123 456". */
    fun code(hostPin: String, joinerPin: String, hostId: String, joinerId: String, nonceA: String, nonceB: String): String {
        val digest = sha256Hex(
            "$CODE_CONTEXT|${hostPin.lowercase()}|${joinerPin.lowercase()}|$hostId|$joinerId|${nonceA.lowercase()}|${nonceB.lowercase()}"
                .encodeToByteArray()
        )
        val digits = (digest.substring(0, 8).toLong(16) % 1_000_000L).toString().padStart(6, '0')
        return digits.substring(0, 3) + " " + digits.substring(3)
    }
}
