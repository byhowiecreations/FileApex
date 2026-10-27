package com.fileapex.security.tls

import com.fileapex.cloud.seal.ClusterRecordKey
import com.fileapex.cloud.seal.ClusterRecordKeys
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.domain.clipboard.ClipboardE2ee
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** [ciphertext] is [TlsPinPayload] sealed with the pair's clipboard E2EE key. */
@Serializable
data class TlsPinAnnouncement(
    val senderDeviceId: String,
    val senderPublicKey: String,
    val ciphertext: String
)

/**
 * [subjectDeviceId] is blank when the sender announces its own key. When it names another device, the sender vouches
 * that this device now uses [pin] (the user confirmed it there). [rotation] is a [TlsRotationProof] as JSON.
 */
@Serializable
internal data class TlsPinPayload(
    val pin: String,
    val tlsPort: Int,
    val issuedAtEpochMs: Long,
    val subjectDeviceId: String = "",
    val rotation: String = "",
    /** The cluster key for cloud device records ([ClusterRecordKey] as JSON); blank from older builds. */
    val clusterKey: String = ""
)

enum class TlsAnnouncementOutcome {
    Recorded,
    Unchanged,
    /** A paired peer announced a different key and its pairing key vouched for it: replaced without asking. */
    Rotated,
    /** Another device of the cluster confirmed a peer's new key; kept until that peer shows the key. */
    Vouched,
    UnknownPeer,
    SenderKeyMismatch,
    Undecryptable,
    Invalid
}

/**
 * Hands a device's TLS pin to an already paired peer. The sender is authenticated by the clipboard key
 * stored for it at pairing, never by the key carried in the request, so a LAN attacker cannot forge one.
 */
/** The pair key operations the announcement needs; the clipboard E2EE in production. */
interface PairCrypto {
    fun publicKeyBase64(): String
    fun encrypt(plaintext: ByteArray, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String): String
    fun decrypt(ciphertextBase64: String, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String): ByteArray
}

object ClipboardPairCrypto : PairCrypto {
    override fun publicKeyBase64() = ClipboardE2ee.publicKeyBase64()
    override fun encrypt(plaintext: ByteArray, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String) =
        ClipboardE2ee.encrypt(plaintext, localDeviceId, peerDeviceId, peerPublicKeyBase64)

    override fun decrypt(ciphertextBase64: String, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String) =
        ClipboardE2ee.decrypt(ciphertextBase64, localDeviceId, peerDeviceId, peerPublicKeyBase64)
}

object TlsPinAnnouncements {
    private val json = Json { ignoreUnknownKeys = true }
    internal const val MAX_SKEW_MS = 24L * 60L * 60L * 1000L
    private val lastIssued = java.util.concurrent.ConcurrentHashMap<String, Long>()

    internal fun forgetSeenForTests() = lastIssued.clear()

    /** Null when this device has no TLS identity or the peer has no clipboard key to seal with. */
    fun build(
        localDeviceId: String,
        peer: PairedDeviceEntity,
        nowMs: Long,
        crypto: PairCrypto = ClipboardPairCrypto
    ): TlsPinAnnouncement? {
        val pin = LocalTlsInfo.pin
        val port = LocalTlsInfo.port
        if (pin.isEmpty() || port !in 1..65535 || peer.publicKey.isBlank()) return null
        val payload = json.encodeToString(
            TlsPinPayload.serializer(),
            TlsPinPayload(pin, port, nowMs, rotation = LocalTlsInfo.rotation, clusterKey = ClusterRecordKeys.current()?.toJson().orEmpty())
        )
        val ciphertext = crypto.encrypt(
            plaintext = payload.encodeToByteArray(),
            localDeviceId = localDeviceId,
            peerDeviceId = peer.deviceId,
            peerPublicKeyBase64 = peer.publicKey
        )
        return TlsPinAnnouncement(localDeviceId, crypto.publicKeyBase64(), ciphertext)
    }

    /** Tells [recipient] that [subject] uses [pin] now, because the user confirmed it on this device. */
    fun buildVouch(
        localDeviceId: String,
        recipient: PairedDeviceEntity,
        subject: String,
        pin: String,
        port: Int,
        nowMs: Long,
        crypto: PairCrypto = ClipboardPairCrypto
    ): TlsPinAnnouncement? {
        if (recipient.publicKey.isBlank() || !TlsPinFormat.isValid(pin) || subject == recipient.deviceId) return null
        val payload = json.encodeToString(TlsPinPayload.serializer(), TlsPinPayload(pin, port, nowMs, subjectDeviceId = subject))
        val ciphertext = crypto.encrypt(payload.encodeToByteArray(), localDeviceId, recipient.deviceId, recipient.publicKey)
        return TlsPinAnnouncement(localDeviceId, crypto.publicKeyBase64(), ciphertext)
    }

    suspend fun accept(
        repository: DeviceRepository,
        announcement: TlsPinAnnouncement,
        localDeviceId: String,
        nowMs: Long,
        crypto: PairCrypto = ClipboardPairCrypto
    ): TlsAnnouncementOutcome {
        val device = repository.getDevice(announcement.senderDeviceId) ?: return TlsAnnouncementOutcome.UnknownPeer
        if (device.isRemoved) return TlsAnnouncementOutcome.UnknownPeer
        if (device.publicKey.isBlank() || device.publicKey != announcement.senderPublicKey) {
            return TlsAnnouncementOutcome.SenderKeyMismatch
        }
        val plain = runCatching {
            crypto.decrypt(
                ciphertextBase64 = announcement.ciphertext,
                localDeviceId = localDeviceId,
                peerDeviceId = device.deviceId,
                peerPublicKeyBase64 = device.publicKey
            )
        }.getOrNull() ?: return TlsAnnouncementOutcome.Undecryptable
        val payload = runCatching {
            json.decodeFromString(TlsPinPayload.serializer(), plain.decodeToString())
        }.getOrNull() ?: return TlsAnnouncementOutcome.Invalid
        val pin = payload.pin.lowercase()
        if (!TlsPinFormat.isValid(pin) || payload.tlsPort !in 1..65535) return TlsAnnouncementOutcome.Invalid
        if (kotlin.math.abs(nowMs - payload.issuedAtEpochMs) > MAX_SKEW_MS) return TlsAnnouncementOutcome.Invalid
        // An older copy of an announcement must not undo a newer one (a replay of a retired key).
        val seen = lastIssued.merge("${device.deviceId}|${payload.subjectDeviceId}", payload.issuedAtEpochMs, ::maxOf)
        if (payload.issuedAtEpochMs < (seen ?: 0L)) return TlsAnnouncementOutcome.Invalid
        if (payload.subjectDeviceId.isBlank()) ClusterRecordKey.fromJson(payload.clusterKey)?.let(ClusterRecordKeys::adopt)
        if (payload.subjectDeviceId.isNotBlank()) return acceptVouch(repository, device, payload.subjectDeviceId, pin, payload.tlsPort, nowMs)
        return applyAuthenticatedPin(repository, device, pin, payload.tlsPort)
    }

    private suspend fun acceptVouch(
        repository: DeviceRepository,
        voucher: PairedDeviceEntity,
        subjectId: String,
        pin: String,
        port: Int,
        nowMs: Long
    ): TlsAnnouncementOutcome {
        val subject = repository.getDevice(subjectId)
        if (subject == null || subject.isRemoved || subjectId == voucher.deviceId) return TlsAnnouncementOutcome.UnknownPeer
        TlsVouches.add(subjectId, pin, voucher.deviceId, nowMs)
        // The subject already showed this key and is waiting for an answer: that answer is now known.
        val waiting = PeerTlsStatus.prompts.value.firstOrNull { it.deviceId == subjectId && it.pin.equals(pin, ignoreCase = true) }
        if (waiting != null) {
            repository.recordTlsPin(subjectId, pin, port)
            PeerTlsStatus.dismiss(subjectId)
        }
        return TlsAnnouncementOutcome.Vouched
    }

    /**
     * A first pin is recorded. A different pin from a peer whose pairing key vouched for it replaces the old one: the
     * announcement could only have been written by that device, so there is nothing for the user to compare.
     */
    suspend fun applyAuthenticatedPin(
        repository: DeviceRepository,
        device: PairedDeviceEntity,
        pin: String,
        port: Int
    ): TlsAnnouncementOutcome = when {
        device.tlsPin.isEmpty() -> {
            repository.recordTlsPin(device.deviceId, pin, port)
            PeerTlsStatus.dismiss(device.deviceId)
            TlsAnnouncementOutcome.Recorded
        }
        device.tlsPin == pin -> {
            repository.recordTlsPin(device.deviceId, pin, port, alt = device.tlsPinAlt)
            TlsAnnouncementOutcome.Unchanged
        }
        else -> {
            repository.recordTlsPin(device.deviceId, pin, port)
            PeerTlsStatus.dismiss(device.deviceId)
            TlsAnnouncementOutcome.Rotated
        }
    }

    /** The user agreed. The other devices of the cluster are told, so they do not have to ask. */
    suspend fun confirm(repository: DeviceRepository, prompt: TlsPinPrompt) {
        repository.recordTlsPin(prompt.deviceId, prompt.pin, prompt.port)
        PeerTlsStatus.dismiss(prompt.deviceId)
        PeerTlsStatus.onUserConfirmed?.invoke(prompt)
    }

    fun decline(prompt: TlsPinPrompt) = PeerTlsStatus.dismiss(prompt.deviceId)
}
