package com.fileapex.friends.transfer

import com.fileapex.iroh.IrohStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.withTimeoutOrNull

/** How a received file ended. Anything but [Received] leaves a resumable partial file unless noted. */
sealed interface FriendReceiveResult {
    data class Received(val file: File) : FriendReceiveResult
    data object Refused : FriendReceiveResult
    data object Interrupted : FriendReceiveResult
    data object HashMismatch : FriendReceiveResult
    data object Malformed : FriendReceiveResult
    /** The sender pressed Cancel; the part file is already deleted. */
    data object CanceledBySender : FriendReceiveResult
}

/** What the receiver tells the hub while a file arrives, so the Groups window and the notification can show it. */
sealed interface FriendReceiveEvent {
    /** The user said yes (or Auto-accept did); [offset] bytes are already on disk from an earlier try. */
    data class Started(val offer: FriendOffer, val offset: Long, val partName: String) : FriendReceiveEvent
    data class Progress(val offer: FriendOffer, val received: Long) : FriendReceiveEvent
    data class Verifying(val offer: FriendOffer) : FriendReceiveEvent
    /** Refused without asking: [reason] is `size` or `space`. */
    data class Declined(val offer: FriendOffer, val reason: String) : FriendReceiveEvent
}

/**
 * Receiving side of one friends stream. Nothing is written until [decide] says yes; bytes land in the quarantine folder
 * under a name derived from the friend key and transfer id (never from the sender's file name); the file is moved to its
 * final flat name only after the SHA-256 of the whole file matches the offer.
 */
class FriendsReceiver(
    private val quarantine: File,
    private val maxBytes: Long,
    private val acceptTimeoutMs: Long = 120_000L,
    private val onEvent: (FriendReceiveEvent) -> Unit = {},
    private val decide: suspend (FriendOffer) -> Boolean
) {
    suspend fun handle(stream: IrohStream, friendKey: String): FriendReceiveResult {
        val offer = try { FriendsWire.decodeOffer(FriendsWire.readFrame(stream)) } catch (_: IOException) { null }
            ?: return FriendReceiveResult.Malformed.also { stream.abort() }
        quarantine.mkdirs()
        val remainingNeeded = offer.sizeBytes
        val fitsLimit = offer.sizeBytes <= maxBytes
        val fitsDisk = quarantine.usableSpace > remainingNeeded + SPARE
        if (!fitsLimit) onEvent(FriendReceiveEvent.Declined(offer, "size")) else if (!fitsDisk) onEvent(FriendReceiveEvent.Declined(offer, "space"))
        val ok = fitsLimit && fitsDisk && (withTimeoutOrNull(acceptTimeoutMs) { decide(offer) } ?: false)
        if (!ok) {
            runCatching { FriendsWire.writeFrame(stream, FriendsWire.encodeReply(false, 0)) }
            stream.abort()
            return FriendReceiveResult.Refused
        }
        val part = File(quarantine, partName(friendKey, offer.transferId))
        // Only whole verified pieces count, so a partial file resumes at the last piece boundary.
        var offset = if (part.exists() && part.length() <= offer.sizeBytes) part.length() / offer.chunkBytes * offer.chunkBytes else 0L
        if (offset == 0L) part.delete()
        FriendsWire.writeFrame(stream, FriendsWire.encodeReply(true, offset))
        onEvent(FriendReceiveEvent.Started(offer, offset, part.name))
        try {
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(offset)
                val piece = ByteArray(minOf(offer.chunkBytes.toLong(), maxOf(offer.sizeBytes, 1L)).toInt())
                while (offset < offer.sizeBytes) {
                    when (FriendsWire.readExact(stream, 1)[0].toInt()) {
                        FriendsWire.TAG_CANCEL -> { part.delete(); return FriendReceiveResult.CanceledBySender }
                        FriendsWire.TAG_CHUNK -> Unit
                        else -> return FriendReceiveResult.Malformed
                    }
                    val expected = FriendsWire.readExact(stream, FriendsWire.HASH_BYTES)
                    val length = minOf(offer.chunkBytes.toLong(), offer.sizeBytes - offset).toInt()
                    var got = 0
                    while (got < length) {
                        val bytes = stream.read(minOf(FriendsWire.CHUNK, length - got)) ?: return FriendReceiveResult.Interrupted
                        bytes.copyInto(piece, got)
                        got += bytes.size
                        onEvent(FriendReceiveEvent.Progress(offer, offset + got))
                    }
                    if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").also { it.update(piece, 0, length) }.digest(), expected)) {
                        runCatching { FriendsWire.writeFrame(stream, "bad".encodeToByteArray()) }
                        return FriendReceiveResult.HashMismatch
                    }
                    raf.seek(offset)
                    raf.write(piece, 0, length)
                    offset += length
                }
            }
        } catch (_: IOException) {
            return FriendReceiveResult.Interrupted
        }
        onEvent(FriendReceiveEvent.Verifying(offer))
        val target = uniqueTarget(FriendsWire.safeName(offer.fileName))
        if (!part.renameTo(target)) { part.copyTo(target); part.delete() }
        runCatching { FriendsWire.writeFrame(stream, "ok".encodeToByteArray()); stream.finish() }
        return FriendReceiveResult.Received(target)
    }

    private fun uniqueTarget(name: String): File {
        var f = File(quarantine, name)
        var i = 1
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (f.exists()) f = File(quarantine, "$stem ($i)$ext").also { i++ }
        return f
    }

    companion object {
        private const val SPARE = 50L * 1024 * 1024

        /** Derived, so a sender can neither pick a path nor collide with another friend's transfer. */
        fun partName(friendKey: String, transferId: String): String {
            val h = MessageDigest.getInstance("SHA-256").digest("$friendKey|$transferId".encodeToByteArray())
            return h.take(16).joinToString("") { "%02x".format(it) } + ".part"
        }

        fun sha256(file: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(FriendsWire.CHUNK)
                while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
            }
            return d.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

sealed interface FriendSendResult {
    data object Done : FriendSendResult
    data object Refused : FriendSendResult
    data object Failed : FriendSendResult
    /** [FriendsSender.send] stopped because the user cancelled and told the receiver. */
    data object Canceled : FriendSendResult
}

/** Where a send is, for the Groups window: waiting for the friend's yes, sending from [offset], waiting for the verified ok. */
sealed interface FriendSendPhase {
    data object Preparing : FriendSendPhase
    data object Waiting : FriendSendPhase
    data class Sending(val offset: Long) : FriendSendPhase
    data object Verifying : FriendSendPhase
}

/**
 * Sending side: offer, wait for the friend's yes and the resume offset, then push the file in pieces of [FriendsWire.DATA_CHUNK],
 * each led by its own SHA-256 so the receiver verifies as it goes. There is no hashing pass before the first byte moves.
 * [shouldCancel] is checked between pieces; when it is true the receiver is told to delete its part file.
 */
object FriendsSender {
    suspend fun send(
        stream: IrohStream,
        file: File,
        transferId: String,
        onPhase: (FriendSendPhase) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {}
    ): FriendSendResult {
        try {
            val offer = FriendOffer(transferId, file.length(), file.name)
            onPhase(FriendSendPhase.Waiting)
            FriendsWire.writeFrame(stream, FriendsWire.encodeOffer(offer))
            val (accepted, offset) = FriendsWire.decodeReply(FriendsWire.readFrame(stream)) ?: return FriendSendResult.Failed
            if (!accepted) return FriendSendResult.Refused
            if (offset < 0 || offset > offer.sizeBytes || offset % offer.chunkBytes != 0L) return FriendSendResult.Failed
            onPhase(FriendSendPhase.Sending(offset))
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val piece = ByteArray(minOf(offer.chunkBytes.toLong(), maxOf(offer.sizeBytes, 1L)).toInt())
                var sent = offset
                while (sent < offer.sizeBytes) {
                    if (shouldCancel()) {
                        stream.write(byteArrayOf(FriendsWire.TAG_CANCEL.toByte()))
                        stream.finish()
                        return FriendSendResult.Canceled
                    }
                    val length = minOf(offer.chunkBytes.toLong(), offer.sizeBytes - sent).toInt()
                    raf.readFully(piece, 0, length)
                    val digest = MessageDigest.getInstance("SHA-256").also { it.update(piece, 0, length) }.digest()
                    stream.write(byteArrayOf(FriendsWire.TAG_CHUNK.toByte()) + digest)
                    var written = 0
                    while (written < length) {
                        val n = minOf(FriendsWire.CHUNK, length - written)
                        stream.write(piece.copyOfRange(written, written + n))
                        written += n
                        onProgress(sent + written)
                    }
                    sent += length
                }
            }
            stream.finish()
            onPhase(FriendSendPhase.Verifying)
            return if (FriendsWire.readFrame(stream).decodeToString() == "ok") FriendSendResult.Done else FriendSendResult.Failed
        } catch (_: IOException) {
            return FriendSendResult.Failed
        }
    }
}
