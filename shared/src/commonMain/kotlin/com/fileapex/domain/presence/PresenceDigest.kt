package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.security.tls.ClipboardPairCrypto
import com.fileapex.security.tls.PairCrypto
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Sealed with the pair key: TLS here is one-way, so the roster must not depend on who connected. */
@Serializable
data class PresenceDigestMessage(
    val senderDeviceId: String,
    val senderPublicKey: String,
    val ciphertext: String
)

@Serializable
internal data class PresenceDigestAsk(val stamp: String, val issuedAtUtcMs: Long)

@Serializable
data class SeenPeer(val id: String, val ageMs: Long)

@Serializable
internal data class PresenceDigestPayload(
    val askStamp: String,
    val sentAtUtcMs: Long,
    val seen: List<SeenPeer>
)

class OpenedDigest(val sentAtUtcMs: Long, val seen: List<SeenPeer>)

sealed interface DigestAnswer {
    data object Denied : DigestAnswer
    data object Invalid : DigestAnswer
    class Reply(val message: PresenceDigestMessage) : DigestAnswer
}

/** A hint only: it never marks anyone online, never supplies an address, and never revives a removed device. */
object PresenceDigests {
    const val MAX_ENTRY_AGE_MS = 5 * 60_000L
    const val MAX_ENTRIES = 64
    const val MIN_ANSWER_GAP_MS = 15_000L

    private val json = Json { ignoreUnknownKeys = true }

    fun newStamp(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun buildAsk(
        localDeviceId: String,
        peer: PairedDeviceEntity,
        stamp: String,
        nowMs: Long,
        crypto: PairCrypto = ClipboardPairCrypto
    ): PresenceDigestMessage? {
        if (peer.publicKey.isBlank()) return null
        val plain = json.encodeToString(PresenceDigestAsk.serializer(), PresenceDigestAsk(stamp, nowMs))
        val sealed = crypto.encrypt(plain.encodeToByteArray(), localDeviceId, peer.deviceId, peer.publicKey)
        return PresenceDigestMessage(localDeviceId, crypto.publicKeyBase64(), sealed)
    }

    fun answer(
        message: PresenceDigestMessage,
        sender: PairedDeviceEntity?,
        localDeviceId: String,
        members: Set<String>,
        verifiedAtMs: Map<String, Long>,
        nowMs: Long,
        crypto: PairCrypto = ClipboardPairCrypto
    ): DigestAnswer {
        if (sender == null || sender.isRemoved || sender.deviceId != message.senderDeviceId) return DigestAnswer.Denied
        if (sender.publicKey.isBlank() || sender.publicKey != message.senderPublicKey) return DigestAnswer.Denied
        val plain = runCatching {
            crypto.decrypt(message.ciphertext, localDeviceId, sender.deviceId, sender.publicKey)
        }.getOrNull() ?: return DigestAnswer.Denied
        val ask = runCatching {
            json.decodeFromString(PresenceDigestAsk.serializer(), plain.decodeToString())
        }.getOrNull() ?: return DigestAnswer.Invalid
        if (ask.stamp.length !in 16..64) return DigestAnswer.Invalid

        val seen = verifiedAtMs.asSequence()
            .filter { (id, _) -> id != sender.deviceId && id != localDeviceId && id in members }
            .map { (id, at) -> SeenPeer(id, (nowMs - at).coerceAtLeast(0L)) }
            .filter { it.ageMs <= MAX_ENTRY_AGE_MS }
            .sortedBy { it.ageMs }
            .take(MAX_ENTRIES)
            .toList()
        val payload = json.encodeToString(
            PresenceDigestPayload.serializer(),
            PresenceDigestPayload(askStamp = ask.stamp, sentAtUtcMs = nowMs, seen = seen)
        )
        val sealed = crypto.encrypt(payload.encodeToByteArray(), localDeviceId, sender.deviceId, sender.publicKey)
        return DigestAnswer.Reply(PresenceDigestMessage(localDeviceId, crypto.publicKeyBase64(), sealed))
    }

    fun open(
        reply: PresenceDigestMessage,
        source: PairedDeviceEntity,
        askStamp: String,
        localDeviceId: String,
        crypto: PairCrypto = ClipboardPairCrypto
    ): OpenedDigest? {
        if (source.isRemoved || reply.senderDeviceId != source.deviceId) return null
        if (source.publicKey.isBlank() || reply.senderPublicKey != source.publicKey) return null
        val plain = runCatching {
            crypto.decrypt(reply.ciphertext, localDeviceId, source.deviceId, source.publicKey)
        }.getOrNull() ?: return null
        val payload = runCatching {
            json.decodeFromString(PresenceDigestPayload.serializer(), plain.decodeToString())
        }.getOrNull() ?: return null
        if (payload.askStamp != askStamp) return null
        val seen = payload.seen
            .filter { it.id.isNotBlank() && it.ageMs in 0..MAX_ENTRY_AGE_MS }
            .take(MAX_ENTRIES)
        return OpenedDigest(payload.sentAtUtcMs, seen)
    }
}

/** A peer may be skipped once on a digest's word and must then be reached directly. */
internal class DigestSkipLog {
    private val skipped = HashSet<String>()

    @Synchronized
    fun trySkip(deviceId: String): Boolean = skipped.add(deviceId)

    @Synchronized
    fun directContact(deviceId: String) {
        skipped.remove(deviceId)
    }
}
