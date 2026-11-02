package com.fileapex.friends.transfer

import com.fileapex.domain.transfer.core.PiecePlan
import com.fileapex.iroh.IrohStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val freeBytes: () -> Long = { quarantine.usableSpace },
    private val decide: suspend (FriendOffer) -> Boolean
) {
    suspend fun handle(stream: IrohStream, friendKey: String): FriendReceiveResult {
        val offer = try { FriendsWire.decodeOffer(FriendsWire.readFrame(stream)) } catch (_: IOException) { null }
            ?: return FriendReceiveResult.Malformed.also { stream.abort() }
        val part = File(quarantine, partName(friendKey, offer.transferId))
        if (!FriendReceiveFiles.claim(part)) {
            FriendsWire.writeFrame(stream, FriendsWire.encodeReply(false, 0))
            stream.finish()
            return FriendReceiveResult.Refused
        }
        return try { receiveOffer(stream, offer, part) } finally { FriendReceiveFiles.release(part) }
    }

    private suspend fun receiveOffer(stream: IrohStream, offer: FriendOffer, part: File): FriendReceiveResult {
        quarantine.mkdirs()
        val plan = PiecePlan(offer.sizeBytes, offer.chunkBytes)
        var offset = if (offer.sha256.isNotEmpty() && part.exists()) plan.resumeOffset(part.length()) else 0L
        val fitsLimit = offer.sizeBytes <= maxBytes
        val fitsDisk = fitsLimit && FriendReceiveFiles.reserve(part, offer.sizeBytes - offset, freeBytes)
        if (!fitsLimit) onEvent(FriendReceiveEvent.Declined(offer, "size")) else if (!fitsDisk) onEvent(FriendReceiveEvent.Declined(offer, "space"))
        val ok = fitsLimit && fitsDisk && (withTimeoutOrNull(acceptTimeoutMs) { decide(offer) } ?: false)
        if (!ok) {
            replyIfOpen(stream, FriendsWire.encodeReply(false, 0))
            stream.abort()
            return FriendReceiveResult.Refused
        }
        // Only whole verified pieces count, so a partial file resumes at the last piece boundary.
        if (offset == 0L) part.delete()
        FriendsWire.writeFrame(stream, FriendsWire.encodeReply(true, offset))
        onEvent(FriendReceiveEvent.Started(offer, offset, part.name))
        var senderCanceled = false
        try {
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(offset)
                val piece = ByteArray(plan.bufferBytes)
                while (offset < offer.sizeBytes) {
                    when (FriendsWire.readExact(stream, 1)[0].toInt()) {
                        FriendsWire.TAG_CANCEL -> { senderCanceled = true; break }
                        FriendsWire.TAG_CHUNK -> Unit
                        else -> return FriendReceiveResult.Malformed
                    }
                    val expected = FriendsWire.readExact(stream, FriendsWire.HASH_BYTES)
                    val length = plan.lengthAt(offset)
                    var got = 0
                    while (got < length) {
                        val bytes = FriendsWire.readChunk(stream, minOf(FriendsWire.CHUNK, length - got)) ?: return FriendReceiveResult.Interrupted
                        bytes.copyInto(piece, got)
                        got += bytes.size
                        onEvent(FriendReceiveEvent.Progress(offer, offset + got))
                    }
                    if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").also { it.update(piece, 0, length) }.digest(), expected)) {
                        replyIfOpen(stream, "bad".encodeToByteArray())
                        return FriendReceiveResult.HashMismatch
                    }
                    raf.seek(offset)
                    raf.write(piece, 0, length)
                    offset += length
                    FriendReceiveFiles.written(part, offer.sizeBytes - offset)
                }
            }
        } catch (_: IOException) {
            return FriendReceiveResult.Interrupted
        }
        if (senderCanceled) {
            if (part.exists() && !part.delete()) throw IOException("Could not remove canceled friend partial file")
            return FriendReceiveResult.CanceledBySender
        }
        onEvent(FriendReceiveEvent.Verifying(offer))
        currentCoroutineContext().ensureActive()
        if (offer.sha256.isNotEmpty() && !MessageDigest.isEqual(friendFileSha256(part).encodeToByteArray(), offer.sha256.encodeToByteArray())) {
            if (!part.delete()) throw IOException("Could not remove corrupt friend partial file")
            FriendsWire.writeFrame(stream, "bad".encodeToByteArray())
            stream.finish()
            return FriendReceiveResult.HashMismatch
        }
        currentCoroutineContext().ensureActive()
        val target = try { FriendReceiveFiles.publish(part, offer.fileName) } catch (_: IOException) {
            stream.abort()
            return FriendReceiveResult.Interrupted
        }
        replyIfOpen(stream, "ok".encodeToByteArray(), finish = true)
        return FriendReceiveResult.Received(target)
    }

    /** The sender may already have dropped the stream; that is not an error here, but cancellation of this receive still propagates. */
    private suspend fun replyIfOpen(stream: IrohStream, frame: ByteArray, finish: Boolean = false) {
        try {
            FriendsWire.writeFrame(stream, frame)
            if (finish) stream.finish()
        } catch (_: IOException) { }
    }

    companion object {
        /** Derived, so a sender can neither pick a path nor collide with another friend's transfer. */
        fun partName(friendKey: String, transferId: String): String {
            val h = MessageDigest.getInstance("SHA-256").digest("$friendKey|$transferId".encodeToByteArray())
            return ".fileapex-" + h.take(16).joinToString("") { "%02x".format(it) } + ".part"
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
 * each led by its own SHA-256. A whole-file digest in the offer also verifies resumed bytes before publication.
 * [shouldCancel] is checked between pieces; when it is true the receiver is told to delete its part file.
 */
object FriendsSender {
    suspend fun send(
        stream: IrohStream,
        file: File,
        transferId: String,
        onPhase: (FriendSendPhase) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
        batchId: String = "",
        onFailure: (String) -> Unit = {},
        onProgress: (Long) -> Unit = {}
    ): FriendSendResult {
        fun failed(reason: String): FriendSendResult { onFailure(reason); return FriendSendResult.Failed }
        try {
            if (shouldCancel()) return FriendSendResult.Canceled
            onPhase(FriendSendPhase.Preparing)
            val hash = friendFileSha256(file)
            if (shouldCancel()) return FriendSendResult.Canceled
            val offer = FriendOffer(transferId, file.length(), file.name, batchId = batchId, sha256 = hash)
            onPhase(FriendSendPhase.Waiting)
            FriendsWire.writeFrame(stream, FriendsWire.encodeOffer(offer))
            val (accepted, offset) = FriendsWire.decodeReply(FriendsWire.readFrame(stream)) ?: return failed(FriendFailure.ANSWER)
            if (!accepted) return FriendSendResult.Refused
            val plan = PiecePlan(offer.sizeBytes, offer.chunkBytes)
            if (!plan.isValidOffset(offset)) return failed(FriendFailure.ANSWER)
            onPhase(FriendSendPhase.Sending(offset))
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val piece = ByteArray(plan.bufferBytes)
                var sent = offset
                while (sent < offer.sizeBytes) {
                    if (shouldCancel()) {
                        FriendsWire.writeRaw(stream, byteArrayOf(FriendsWire.TAG_CANCEL.toByte()))
                        stream.finish()
                        return FriendSendResult.Canceled
                    }
                    val length = plan.lengthAt(sent)
                    raf.readFully(piece, 0, length)
                    val digest = MessageDigest.getInstance("SHA-256").also { it.update(piece, 0, length) }.digest()
                    FriendsWire.writeRaw(stream, byteArrayOf(FriendsWire.TAG_CHUNK.toByte()) + digest)
                    var written = 0
                    while (written < length) {
                        val n = minOf(FriendsWire.CHUNK, length - written)
                        FriendsWire.writeRaw(stream, piece.copyOfRange(written, written + n))
                        written += n
                        onProgress(sent + written)
                    }
                    sent += length
                }
            }
            stream.finish()
            onPhase(FriendSendPhase.Verifying)
            return if (FriendsWire.readFrame(stream, timeoutMs = 300_000L).decodeToString() == "ok") FriendSendResult.Done else failed(FriendFailure.ANSWER)
        } catch (e: IOException) {
            return failed(FriendFailure.of(e))
        }
    }
}
