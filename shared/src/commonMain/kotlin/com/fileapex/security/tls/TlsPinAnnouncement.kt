package com.fileapex.security.tls

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

@Serializable
internal data class TlsPinPayload(val pin: String, val tlsPort: Int, val issuedAtEpochMs: Long)

enum class TlsAnnouncementOutcome {
    Recorded,
    Unchanged,
    NeedsConfirmation,
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
    private const val MAX_SKEW_MS = 24L * 60L * 60L * 1000L
    private val PIN_FORMAT = Regex("[0-9a-f]{64}")

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
        val payload = json.encodeToString(TlsPinPayload.serializer(), TlsPinPayload(pin, port, nowMs))
        val ciphertext = crypto.encrypt(
            plaintext = payload.encodeToByteArray(),
            localDeviceId = localDeviceId,
            peerDeviceId = peer.deviceId,
            peerPublicKeyBase64 = peer.publicKey
        )
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
        if (!PIN_FORMAT.matches(pin) || payload.tlsPort !in 1..65535) return TlsAnnouncementOutcome.Invalid
        if (kotlin.math.abs(nowMs - payload.issuedAtEpochMs) > MAX_SKEW_MS) return TlsAnnouncementOutcome.Invalid
        return applyAuthenticatedPin(repository, device, pin, payload.tlsPort)
    }

    /** A first pin is recorded; a different pin for a pinned peer always waits for the user. */
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
            PeerTlsStatus.requestConfirmation(TlsPinPrompt(device.deviceId, TlsPromptKind.KEY_CHANGED, pin, port))
            TlsAnnouncementOutcome.NeedsConfirmation
        }
    }

    /** The user compared fingerprints and agreed. */
    suspend fun confirm(repository: DeviceRepository, prompt: TlsPinPrompt) {
        repository.recordTlsPin(prompt.deviceId, prompt.pin, prompt.port)
        PeerTlsStatus.dismiss(prompt.deviceId)
        PeerTlsStatus.reportHealthy(prompt.deviceId)
    }

    fun decline(prompt: TlsPinPrompt) = PeerTlsStatus.dismiss(prompt.deviceId)
}
