package com.fileapex.security

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.security.tls.ClipboardPairCrypto
import com.fileapex.security.tls.PairCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One device vouching to one other device that a third device is a member of the cluster.
 * [ciphertext] is an [IntroductionPayload] sealed with the pair key the introducer shares with the recipient,
 * so only that introducer can have produced it. It is built per recipient and is never forwarded.
 */
@Serializable
data class SealedIntroduction(
    val introducerId: String,
    val introducerPublicKey: String,
    val ciphertext: String
)

@Serializable
internal data class IntroductionPayload(
    val recipientId: String,
    val subject: PeerNodeState,
    val issuedAtEpochMs: Long
)

data class OpenedIntroduction(val introducerId: String, val subject: PeerNodeState)

enum class IntroductionRejection {
    UnknownIntroducer,
    IntroducerKeyMismatch,
    Undecryptable,
    Invalid,
    NotForThisDevice,
    Stale
}

sealed interface IntroductionResult {
    data class Accepted(val introduction: OpenedIntroduction) : IntroductionResult
    data class Rejected(val reason: IntroductionRejection) : IntroductionResult
}

object PeerIntroductions {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Introductions older than a candidate's lifetime are not worth acting on. */
    const val MAX_AGE_MS = 2L * 60L * 60L * 1000L
    private const val MAX_FUTURE_SKEW_MS = 10L * 60L * 1000L

    /** Null when the recipient has no stored key to seal with. */
    fun seal(
        localDeviceId: String,
        recipient: PairedDeviceEntity,
        subject: PairedDeviceEntity,
        nowMs: Long,
        crypto: PairCrypto = ClipboardPairCrypto
    ): SealedIntroduction? {
        if (recipient.publicKey.isBlank() || subject.deviceId == recipient.deviceId) return null
        val payload = IntroductionPayload(
            recipientId = recipient.deviceId,
            subject = PeerNodeStateMapper.fromEntity(subject).copy(
                lastSeenTimestamp = 0L,
                tlsPin = "",
                tlsPort = 0
            ),
            issuedAtEpochMs = nowMs
        )
        val ciphertext = crypto.encrypt(
            plaintext = json.encodeToString(IntroductionPayload.serializer(), payload).encodeToByteArray(),
            localDeviceId = localDeviceId,
            peerDeviceId = recipient.deviceId,
            peerPublicKeyBase64 = recipient.publicKey
        )
        return SealedIntroduction(localDeviceId, crypto.publicKeyBase64(), ciphertext)
    }

    /**
     * [introducer] is this device's stored row for the sender. The key in the request is only compared
     * with it, never trusted on its own.
     */
    fun open(
        introduction: SealedIntroduction,
        introducer: PairedDeviceEntity?,
        localDeviceId: String,
        nowMs: Long,
        crypto: PairCrypto = ClipboardPairCrypto
    ): IntroductionResult {
        if (introducer == null || introducer.isRemoved || introducer.deviceId != introduction.introducerId) {
            return IntroductionResult.Rejected(IntroductionRejection.UnknownIntroducer)
        }
        if (introducer.publicKey.isBlank() || introducer.publicKey != introduction.introducerPublicKey) {
            return IntroductionResult.Rejected(IntroductionRejection.IntroducerKeyMismatch)
        }
        val plain = runCatching {
            crypto.decrypt(
                ciphertextBase64 = introduction.ciphertext,
                localDeviceId = localDeviceId,
                peerDeviceId = introducer.deviceId,
                peerPublicKeyBase64 = introducer.publicKey
            )
        }.getOrNull() ?: return IntroductionResult.Rejected(IntroductionRejection.Undecryptable)
        val payload = runCatching {
            json.decodeFromString(IntroductionPayload.serializer(), plain.decodeToString())
        }.getOrNull() ?: return IntroductionResult.Rejected(IntroductionRejection.Invalid)
        if (payload.recipientId != localDeviceId) {
            return IntroductionResult.Rejected(IntroductionRejection.NotForThisDevice)
        }
        val age = nowMs - payload.issuedAtEpochMs
        if (age > MAX_AGE_MS || age < -MAX_FUTURE_SKEW_MS) {
            return IntroductionResult.Rejected(IntroductionRejection.Stale)
        }
        val subject = payload.subject
        if (subject.deviceId.isBlank() || subject.deviceId == localDeviceId || subject.deviceId == introducer.deviceId) {
            return IntroductionResult.Rejected(IntroductionRejection.Invalid)
        }
        return IntroductionResult.Accepted(OpenedIntroduction(introducer.deviceId, subject))
    }
}
