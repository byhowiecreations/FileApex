package com.fileapex.membership

import com.fileapex.security.IdentityBinding
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * A device that lost its identity key and so got a new id still holds the signing key the chain knows under its old id.
 * It signs the change with that key; the member it asks checks it against the chain before showing the user a code.
 */
@Serializable
class IdentityChangeRequest(
    val oldId: String,
    val newId: String,
    val newE2eeKey: String,
    val signingKey: String,
    val hardwareHash: String,
    val nonce: String,
    val signature: String
) {
    internal fun signedBytes(): ByteArray = IdentityChangeProof.bytes(oldId, newId, newE2eeKey, signingKey, hardwareHash, nonce)
}

object IdentityChangeProof {
    /** Why a member refuses to take an id change. */
    enum class Refusal { NOT_A_MEMBER, WRONG_KEY, BAD_SIGNATURE, ID_NOT_FROM_KEY, SAME_ID, WRONG_TLS_KEY, HARDWARE_DIFFERS, OLD_ID_ANSWERS }

    internal fun bytes(oldId: String, newId: String, newE2eeKey: String, signingKey: String, hardwareHash: String, nonce: String): ByteArray =
        listOf("identity-change-v1", oldId, newId, newE2eeKey, signingKey, hardwareHash, nonce).joinToString("\n").encodeToByteArray()

    /** Signed by [signer], whose key is the one the chain holds for [oldId]. */
    fun request(signer: EventSigner, oldId: String, newId: String, newE2eeKey: String, hardwareHash: String, nonce: String): IdentityChangeRequest =
        IdentityChangeRequest(oldId, newId, newE2eeKey, signer.publicKey, hardwareHash, nonce, signer.sign(bytes(oldId, newId, newE2eeKey, signer.publicKey, hardwareHash, nonce)))

    /**
     * The id this device had before, read from the chain itself: an active member holding this device's own signing key
     * under another id, while this id is not an active member. Needs no remembered id, so a device whose id changed
     * before it began remembering still finds its place.
     */
    fun formerId(state: ChainState, selfId: String, signingKey: String): String? {
        if (state.member(selfId)?.active == true) return null
        return state.members.entries.firstOrNull { (id, m) -> id != selfId && m.active && !m.isLegacyAdmit && m.key == signingKey }?.key
    }

    /** Six digits both screens show; the user compares them as in pairing. They depend on every field of the request. */
    fun code(request: IdentityChangeRequest): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(request.signedBytes())
        val value = digest.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) } % 1_000_000L
        return "%06d".format(value)
    }

    /**
     * Null when the request may go on to the user's confirmation. [member] is the chain's entry for the old id,
     * [connectionPin] the TLS key the request arrived on, [knownHardwareHash] what this member stored for the old id.
     */
    fun verify(request: IdentityChangeRequest, member: ChainMember?, connectionPin: String, knownHardwareHash: String, oldIdAnswers: Boolean): Refusal? = when {
        member == null || !member.active || member.isLegacyAdmit -> Refusal.NOT_A_MEMBER
        request.oldId == request.newId -> Refusal.SAME_ID
        request.signingKey != member.key -> Refusal.WRONG_KEY
        !signatureHolds(request) -> Refusal.BAD_SIGNATURE
        !IdentityBinding.matches(request.newId, request.newE2eeKey) -> Refusal.ID_NOT_FROM_KEY
        member.tlsPin.isEmpty() || connectionPin != member.tlsPin -> Refusal.WRONG_TLS_KEY
        knownHardwareHash.isEmpty() || request.hardwareHash != knownHardwareHash -> Refusal.HARDWARE_DIFFERS
        oldIdAnswers -> Refusal.OLD_ID_ANSWERS
        else -> null
    }

    private fun signatureHolds(request: IdentityChangeRequest): Boolean = try {
        Signature.getInstance(EcEventSigner.ALGORITHM).run {
            initVerify(MembershipKeys.decode(request.signingKey))
            update(request.signedBytes())
            verify(Base64.getDecoder().decode(request.signature))
        }
    } catch (_: java.security.GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
