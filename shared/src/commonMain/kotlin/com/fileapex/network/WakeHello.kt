package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import java.security.MessageDigest

/** Keyed proof over [data] that only the two devices of a pair can produce. */
fun interface PairAuthenticator {
    fun authenticate(data: ByteArray, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String): ByteArray
}

object ClipboardPairAuthenticator : PairAuthenticator {
    override fun authenticate(data: ByteArray, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String) =
        com.fileapex.domain.clipboard.ClipboardE2ee.authenticate(data, localDeviceId, peerDeviceId, peerPublicKeyBase64)
}

class WakeRecipient(val deviceId: String, val publicKey: String)

class ParsedWakeHello(
    val isAck: Boolean,
    val senderId: String,
    val port: Int,
    val nonce: ByteArray,
    val sentAtMs: Long,
    internal val signedBytes: ByteArray,
    internal val entries: List<Pair<ByteArray, ByteArray>>
)

/**
 * The wake datagram with a sender: header (magic, sender id, server port, nonce, time) followed by one entry per
 * recipient, each a 4 byte recipient tag and an 8 byte keyed proof of the header under that pair's key. A packet
 * proves nothing by itself; the receiver still fetches the sender's identity before changing anything.
 */
object WakeHello {
    private const val MAGIC = "FAH1"
    private const val ACK_MAGIC = "FAK1"
    private const val TAG_BYTES = 4
    private const val MAC_BYTES = 8
    private const val ENTRY_BYTES = TAG_BYTES + MAC_BYTES
    private const val NONCE_BYTES = 8
    const val MAX_PACKET = 512
    const val MAX_SKEW_MS = 2 * 60_000L

    fun looksSigned(bytes: ByteArray): Boolean = magicOf(bytes) != null

    private fun magicOf(bytes: ByteArray): String? {
        if (bytes.size <= MAGIC.length) return null
        val head = bytes.copyOfRange(0, MAGIC.length).decodeToString()
        return head.takeIf { it == MAGIC || it == ACK_MAGIC }
    }

    fun build(
        localDeviceId: String,
        port: Int,
        recipients: List<WakeRecipient>,
        nowMs: Long,
        nonce: ByteArray,
        auth: PairAuthenticator,
        ack: Boolean = false
    ): List<ByteArray> {
        val usable = recipients.filter { it.publicKey.isNotBlank() && it.deviceId != localDeviceId }
        if (usable.isEmpty() || nonce.size != NONCE_BYTES) return emptyList()
        val id = localDeviceId.encodeToByteArray()
        val header = (if (ack) ACK_MAGIC else MAGIC).encodeToByteArray() + byteArrayOf(id.size.toByte()) + id +
            byteArrayOf((port shr 8).toByte(), port.toByte()) + nonce + longBytes(nowMs)
        val perPacket = (MAX_PACKET - header.size - 1) / ENTRY_BYTES
        return usable.chunked(perPacket).map { group ->
            var packet = header + byteArrayOf(group.size.toByte())
            for (r in group) {
                val proof = auth.authenticate(header, localDeviceId, r.deviceId, r.publicKey).copyOf(MAC_BYTES)
                packet += tag(r.deviceId) + proof
            }
            packet
        }
    }

    fun parse(bytes: ByteArray): ParsedWakeHello? {
        val magic = magicOf(bytes)
        if (bytes.size > MAX_PACKET || magic == null) return null
        var at = MAGIC.length
        val idLen = bytes[at++].toInt() and 0xFF
        if (idLen == 0 || bytes.size < at + idLen + 2 + NONCE_BYTES + 8 + 1) return null
        val senderId = bytes.copyOfRange(at, at + idLen).decodeToString()
        at += idLen
        val port = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        at += 2
        val nonce = bytes.copyOfRange(at, at + NONCE_BYTES)
        at += NONCE_BYTES
        val sentAt = readLong(bytes, at)
        at += 8
        val signed = bytes.copyOfRange(0, at)
        val count = bytes[at++].toInt() and 0xFF
        if (count == 0 || bytes.size != at + count * ENTRY_BYTES) return null
        val entries = (0 until count).map { i ->
            val start = at + i * ENTRY_BYTES
            bytes.copyOfRange(start, start + TAG_BYTES) to bytes.copyOfRange(start + TAG_BYTES, start + ENTRY_BYTES)
        }
        return ParsedWakeHello(magic == ACK_MAGIC, senderId, port, nonce, sentAt, signed, entries)
    }

    /** A cheap check with no key: whether this packet carries an entry for [localDeviceId]. */
    fun addressedTo(hello: ParsedWakeHello, localDeviceId: String): Boolean {
        val mine = tag(localDeviceId)
        return hello.entries.any { MessageDigest.isEqual(it.first, mine) }
    }

    fun verify(
        hello: ParsedWakeHello,
        localDeviceId: String,
        sender: PairedDeviceEntity,
        nowMs: Long,
        auth: PairAuthenticator
    ): Boolean {
        if (sender.isRemoved || sender.deviceId != hello.senderId || sender.publicKey.isBlank()) return false
        if (hello.port !in 1..65535 || kotlin.math.abs(nowMs - hello.sentAtMs) > MAX_SKEW_MS) return false
        val mine = tag(localDeviceId)
        val entry = hello.entries.firstOrNull { MessageDigest.isEqual(it.first, mine) } ?: return false
        val expected = runCatching {
            auth.authenticate(hello.signedBytes, localDeviceId, sender.deviceId, sender.publicKey)
        }.getOrNull() ?: return false
        return MessageDigest.isEqual(entry.second, expected.copyOf(MAC_BYTES))
    }

    private fun tag(deviceId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(deviceId.encodeToByteArray()).copyOf(TAG_BYTES)

    private fun longBytes(value: Long): ByteArray = ByteArray(8) { (value shr (56 - 8 * it)).toByte() }

    private fun readLong(bytes: ByteArray, at: Int): Long =
        (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (bytes[at + i].toLong() and 0xFF) }
}

/** Drops a repeated nonce and a sender that is heard again within [MIN_GAP_MS], so a replay or a flood does one check. */
class WakeHelloGate(private val now: () -> Long) {
    private val lastBySender = HashMap<String, Long>()
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun accept(senderId: String, nonce: ByteArray): Boolean {
        val moment = now()
        seen.entries.removeAll { moment - it.value > NONCE_MEMORY_MS }
        val key = senderId + ":" + nonce.joinToString("") { "%02x".format(it) }
        if (seen.containsKey(key)) return false
        val last = lastBySender[senderId]
        if (last != null && moment - last < MIN_GAP_MS) return false
        seen[key] = moment
        lastBySender[senderId] = moment
        return true
    }

    companion object {
        const val MIN_GAP_MS = 10_000L
        private const val NONCE_MEMORY_MS = 5 * 60_000L
    }
}

/**
 * The wake socket listens on every interface so that broadcast and multicast reach it, so what it accepts is decided
 * by the sender: an IPv4 address in one of this device's own LAN subnets, and not this device.
 */
object WakeSourcePolicy {
    private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun accept(sourceHost: String, localLanIps: List<String>): Boolean {
        val host = sourceHost.trim()
        if (!ipv4.matches(host) || host in localLanIps) return false
        return localLanIps.any { com.fileapex.util.NetworkUtils.sameIpv4Slash24(it, host) }
    }
}
