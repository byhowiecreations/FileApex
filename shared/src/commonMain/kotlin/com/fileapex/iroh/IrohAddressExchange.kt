package com.fileapex.iroh

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.security.tls.ClipboardPairCrypto
import com.fileapex.security.tls.PairCrypto
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
data class IrohAddressMessage(
    val senderDeviceId: String,
    val senderPublicKey: String,
    val ciphertext: String
)

@Serializable
internal data class IrohAddressPayload(val stamp: String, val sentAtUtcMs: Long, val address: String)

sealed interface AddressAnswer {
    data object Denied : AddressAnswer
    data object Invalid : AddressAnswer
    class Reply(val message: IrohAddressMessage, val learned: String) : AddressAnswer
}

/**
 * One round trip hands each side the other's Iroh address (`id|relayUrl|direct,direct`). The id is the
 * peer's public key, and Iroh itself proves the peer holds it when a stream is opened, so the address
 * is a dialing hint and never grants access: the HTTP layer still checks the pairing.
 */
object IrohAddressExchange {
    const val MAX_SKEW_MS = 10 * 60_000L
    private const val ID_HEX_LENGTH = 64
    private const val MAX_ADDRESS_LENGTH = 2048
    private const val MAX_DIRECT_ADDRESSES = 16
    private const val MAX_RELAY_LENGTH = 200
    private const val MAX_DIRECT_LENGTH = 64

    private val json = Json { ignoreUnknownKeys = true }
    private val hex = Regex("^[0-9a-f]{$ID_HEX_LENGTH}$")
    private val directAddress = Regex("^[0-9A-Fa-f:.\\[\\]]+:[0-9]{1,5}$")

    fun newStamp(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidAddress(address: String): Boolean {
        if (address.isEmpty() || address.length > MAX_ADDRESS_LENGTH) return false
        val parts = address.split("|")
        if (parts.size != 3) return false
        val (id, relay, direct) = parts
        if (!hex.matches(id)) return false
        if (relay.isNotEmpty()) {
            if (relay.length > MAX_RELAY_LENGTH || !relay.startsWith("https://")) return false
            if (relay.any { it.isWhitespace() || it.isISOControl() }) return false
        }
        if (direct.isEmpty()) return true
        val entries = direct.split(",")
        if (entries.size > MAX_DIRECT_ADDRESSES) return false
        return entries.all { it.length <= MAX_DIRECT_LENGTH && directAddress.matches(it) }
    }

    fun buildAsk(
        localDeviceId: String,
        peer: PairedDeviceEntity,
        ownAddress: String,
        stamp: String,
        nowMs: Long,
        crypto: PairCrypto = ClipboardPairCrypto
    ): IrohAddressMessage? {
        if (peer.publicKey.isBlank()) return null
        val plain = json.encodeToString(IrohAddressPayload.serializer(), IrohAddressPayload(stamp, nowMs, ownAddress))
        val sealed = crypto.encrypt(plain.encodeToByteArray(), localDeviceId, peer.deviceId, peer.publicKey)
        return IrohAddressMessage(localDeviceId, crypto.publicKeyBase64(), sealed)
    }

    fun answer(
        message: IrohAddressMessage,
        sender: PairedDeviceEntity?,
        localDeviceId: String,
        ownAddress: String,
        nowMs: Long,
        crypto: PairCrypto = ClipboardPairCrypto
    ): AddressAnswer {
        if (sender == null || sender.isRemoved || sender.deviceId != message.senderDeviceId) return AddressAnswer.Denied
        if (sender.publicKey.isBlank() || sender.publicKey != message.senderPublicKey) return AddressAnswer.Denied
        val plain = runCatching {
            crypto.decrypt(message.ciphertext, localDeviceId, sender.deviceId, sender.publicKey)
        }.getOrNull() ?: return AddressAnswer.Denied
        val ask = runCatching {
            json.decodeFromString(IrohAddressPayload.serializer(), plain.decodeToString())
        }.getOrNull() ?: return AddressAnswer.Invalid
        if (ask.stamp.length !in 16..64) return AddressAnswer.Invalid
        if (abs(nowMs - ask.sentAtUtcMs) > MAX_SKEW_MS) return AddressAnswer.Invalid
        if (!isValidAddress(ask.address)) return AddressAnswer.Invalid

        val payload = json.encodeToString(
            IrohAddressPayload.serializer(),
            IrohAddressPayload(ask.stamp, nowMs, ownAddress)
        )
        val sealed = crypto.encrypt(payload.encodeToByteArray(), localDeviceId, sender.deviceId, sender.publicKey)
        return AddressAnswer.Reply(IrohAddressMessage(localDeviceId, crypto.publicKeyBase64(), sealed), ask.address)
    }

    /** The peer's address, or null when the reply is not from [source], not for [stamp], or malformed. */
    fun open(
        reply: IrohAddressMessage,
        source: PairedDeviceEntity,
        stamp: String,
        localDeviceId: String,
        crypto: PairCrypto = ClipboardPairCrypto
    ): String? {
        if (source.isRemoved || reply.senderDeviceId != source.deviceId) return null
        if (source.publicKey.isBlank() || reply.senderPublicKey != source.publicKey) return null
        val plain = runCatching {
            crypto.decrypt(reply.ciphertext, localDeviceId, source.deviceId, source.publicKey)
        }.getOrNull() ?: return null
        val payload = runCatching {
            json.decodeFromString(IrohAddressPayload.serializer(), plain.decodeToString())
        }.getOrNull() ?: return null
        if (payload.stamp != stamp) return null
        return payload.address.takeIf { isValidAddress(it) }
    }
}

class IrohPeerBook private constructor(private val entries: Map<String, String>) {
    val size: Int get() = entries.size

    fun address(deviceId: String): String? = entries[deviceId]

    fun deviceIds(): Set<String> = entries.keys

    fun endpointId(deviceId: String): String? = entries[deviceId]?.substringBefore('|')

    fun deviceIdFor(endpointId: String): String? =
        entries.entries.firstOrNull { it.value.substringBefore('|') == endpointId }?.key

    fun with(deviceId: String, address: String): IrohPeerBook {
        if (deviceId.isBlank() || !IrohAddressExchange.isValidAddress(address)) return this
        if (entries[deviceId] == address) return this
        if (deviceId !in entries && entries.size >= MAX_PEERS) return this
        return IrohPeerBook(entries + (deviceId to address))
    }

    fun toJson(): String = json.encodeToString(serializer, entries)

    companion object {
        const val MAX_PEERS = 64
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), String.serializer())

        fun empty() = IrohPeerBook(emptyMap())

        fun parse(raw: String): IrohPeerBook {
            if (raw.isBlank()) return empty()
            val map = runCatching { json.decodeFromString(serializer, raw) }.getOrNull() ?: return empty()
            return IrohPeerBook(
                map.filter { (id, address) -> id.isNotBlank() && IrohAddressExchange.isValidAddress(address) }
                    .entries.take(MAX_PEERS).associate { it.key to it.value }
            )
        }
    }
}
