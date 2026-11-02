package com.fileapex.friends.transfer

import com.fileapex.iroh.IrohStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.withTimeoutOrNull

/** What a sender announces before any file byte moves. Nothing else about the sender's device is ever sent. */
data class FriendOffer(
    val transferId: String,
    val sizeBytes: Long,
    val fileName: String,
    val chunkBytes: Int = FriendsWire.DATA_CHUNK,
    /** Set when the receiver already said yes to a whole batch this file belongs to; empty for a file offered on its own. */
    val batchId: String = "",
    val sha256: String = ""
)

/** Framing for the friends stream: 4-byte length, hard cap, so a hostile peer cannot make us allocate. */
object FriendsWire {
    const val MAX_FRAME = 4096
    const val CHUNK = 64 * 1024
    /** A file moves in verified pieces of this size; the receiver holds one piece in memory at a time. */
    const val DATA_CHUNK = 1024 * 1024
    private const val MIN_DATA_CHUNK = 64 * 1024
    private const val MAX_DATA_CHUNK = 8 * 1024 * 1024
    const val TAG_CHUNK = 1
    /** The sender stopped on purpose: the receiver removes its part file. */
    const val TAG_CANCEL = 2
    const val HASH_BYTES = 32
    val ID = Regex("[0-9a-f]{32}")

    suspend fun readExact(stream: IrohStream, n: Int, timeoutMs: Long = 30_000L): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val chunk = readChunk(stream, n - got, timeoutMs) ?: throw IOException("friend stream ended or timed out")
            chunk.copyInto(out, got)
            got += chunk.size
        }
        return out
    }

    suspend fun writeFrame(stream: IrohStream, bytes: ByteArray) {
        require(bytes.size <= MAX_FRAME) { "frame too large" }
        writeRaw(stream, ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes)
    }

    suspend fun readFrame(stream: IrohStream, timeoutMs: Long = 150_000L): ByteArray = withTimeoutOrNull(timeoutMs) {
        val len = ByteBuffer.wrap(readExact(stream, 4, timeoutMs)).int
        if (len < 0 || len > MAX_FRAME) throw IOException("frame too large")
        readExact(stream, len, timeoutMs)
    } ?: throw IOException("friend reply timed out")

    suspend fun readChunk(stream: IrohStream, max: Int, timeoutMs: Long = 30_000L): ByteArray? =
        withTimeoutOrNull(timeoutMs) { stream.read(max) }

    suspend fun writeRaw(stream: IrohStream, bytes: ByteArray, timeoutMs: Long = 30_000L) {
        if (withTimeoutOrNull(timeoutMs) { stream.write(bytes); true } != true) throw IOException("friend write timed out")
    }

    fun encodeOffer(o: FriendOffer): ByteArray =
        if (o.sha256.isNotEmpty()) "v4\n${o.transferId}\n${o.sizeBytes}\n${o.chunkBytes}\n${o.batchId}\n${o.sha256}\n${o.fileName}".encodeToByteArray()
        else if (o.batchId.isEmpty()) "v2\n${o.transferId}\n${o.sizeBytes}\n${o.chunkBytes}\n${o.fileName}".encodeToByteArray()
        else "v3\n${o.transferId}\n${o.sizeBytes}\n${o.chunkBytes}\n${o.batchId}\n${o.fileName}".encodeToByteArray()

    /** Null for anything malformed; the caller drops the stream. */
    fun decodeOffer(bytes: ByteArray): FriendOffer? {
        val p = bytes.decodeToString().split("\n", limit = 7)
        val verified = p.firstOrNull() == "v4"
        val batched = p.firstOrNull() == "v3"
        if (p.size != (if (verified) 7 else if (batched) 6 else 5) || (p[0] != "v2" && !batched && !verified) || !ID.matches(p[1])) return null
        val size = p[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val chunk = p[3].toIntOrNull()?.takeIf { it in MIN_DATA_CHUNK..MAX_DATA_CHUNK } ?: return null
        if (verified) {
            if ((p[4].isNotEmpty() && !ID.matches(p[4])) || !Regex("[0-9a-f]{64}").matches(p[5])) return null
            return FriendOffer(p[1], size, p[6], chunk, p[4], p[5])
        }
        if (!batched) return FriendOffer(p[1], size, p[4], chunk)
        if (!ID.matches(p[4])) return null
        return FriendOffer(p[1], size, p[5], chunk, p[4])
    }

    /** Receiver reply: 0 = refuse, 1 = accept and send from this offset. */
    fun encodeReply(accept: Boolean, offset: Long): ByteArray = ByteBuffer.allocate(9).put(if (accept) 1 else 0).putLong(offset).array()

    fun decodeReply(bytes: ByteArray): Pair<Boolean, Long>? {
        if (bytes.size != 9) return null
        val b = ByteBuffer.wrap(bytes)
        return (b.get().toInt() == 1) to b.long
    }

    /** A flat, printable file name: no folders, no control characters, no reserved device names, bounded length. */
    fun safeName(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 32 && it !in "<>:\"|?*" }.trim().trimEnd('.', ' ')
        n = n.trimStart('.').trimStart('_')
        if (n.isEmpty()) return "file"
        if (n.length > 120) n = n.take(100) + "." + n.substringAfterLast('.', "").take(10).ifEmpty { "bin" }.let { if (it.length >= 100) "bin" else it }
        val base = n.substringBeforeLast('.').uppercase()
        if (n.isEmpty() || base in RESERVED || Regex("COM\\d|LPT\\d").matches(base)) n = "file_$n"
        return n.ifEmpty { "file" }
    }

    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL")
}
