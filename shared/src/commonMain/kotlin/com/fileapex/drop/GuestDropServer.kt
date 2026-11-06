package com.fileapex.drop

import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.friends.transfer.FriendsWire
import com.fileapex.iroh.IrohStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a browser and the sender say on a drop stream. The browser sends one request frame. The sender answers with one frame,
 * `ok`, size, name, and then exactly that many raw bytes, or `no` and a reason. After the last byte the browser sends one
 * receipt byte, so the sender knows it may close. There is no resume and no piece list.
 */
object GuestDropWire {
    private val ID = Regex("[0-9a-f]{32}")

    /** What the browser sends once it holds every byte. */
    val RECEIPT = byteArrayOf('k'.code.toByte())

    fun encodeRequest(id: String, secret: String): ByteArray = "v1\n$id\n$secret".encodeToByteArray()

    /** Null for anything but a well-formed request. */
    fun decodeRequest(bytes: ByteArray): Pair<String, String>? {
        val p = bytes.decodeToString().split("\n")
        if (p.size != 3 || p[0] != "v1" || !ID.matches(p[1]) || !ID.matches(p[2])) return null
        return p[1] to p[2]
    }

    fun encodeOk(sizeBytes: Long, name: String): ByteArray = "ok\n$sizeBytes\n$name".encodeToByteArray()

    fun encodeNo(code: String): ByteArray = "no\n$code".encodeToByteArray()
}

sealed interface DropServe {
    data object Served : DropServe
    data class Refused(val reason: DropRefusal) : DropServe
    data class Failed(val why: String) : DropServe
    data object BadRequest : DropServe
}

object GuestDropServer {
    private const val CHUNK = 256 * 1024
    private const val GONE = "gone"

    /**
     * Answers one stream. The link is spent when the last byte and the end of the stream were written. The stream is then
     * held open until the browser's receipt arrives or [receiptTimeoutMs] passes, because closing the endpoint at once would
     * cut bytes that are written but not yet delivered. [onGranted] runs once when a request is accepted, before any byte; [onSpent] runs when the last byte went out, before the wait for the receipt.
     * A running download counts as a transfer for the app, which holds the wake and Wi-Fi locks while it lasts.
     */
    suspend fun serve(
        stream: IrohStream,
        drops: GuestDrops,
        requestTimeoutMs: Long = 5_000L,
        receiptTimeoutMs: Long = 30_000L,
        onGranted: suspend () -> Unit = {},
        onSpent: suspend () -> Unit = {}
    ): DropServe {
        val request = try {
            GuestDropWire.decodeRequest(FriendsWire.readFrame(stream, requestTimeoutMs))
        } catch (_: IOException) {
            null
        }
        if (request == null) {
            stream.abort()
            return DropServe.BadRequest
        }
        val lease = when (val begin = drops.begin(request.first, request.second)) {
            is DropBegin.Refused -> return refuse(stream, begin.reason.code, DropServe.Refused(begin.reason))
            is DropBegin.Granted -> begin.lease
        }
        var spent = false
        var counted = false
        try {
            val file = File(lease.path)
            val intact = withContext(Dispatchers.IO) { file.isFile && file.length() == lease.sizeBytes && unchanged(file, lease) }
            if (!intact) return refuse(stream, GONE, DropServe.Failed("file changed"))
            onGranted()
            FriendsWire.writeFrame(stream, GuestDropWire.encodeOk(lease.sizeBytes, lease.name))
            TransferActivityGuard.beginTransfer(fileName = lease.name, streamKey = guardKey(lease))
            counted = true
            val sent = withContext(Dispatchers.IO) { file.inputStream().use { push(stream, file, it, lease) } }
            if (sent != null) {
                stream.abort()
                return DropServe.Failed(sent)
            }
            stream.finish()
            lease.complete()
            spent = true
            onSpent()
            awaitReceipt(stream, receiptTimeoutMs)
            return DropServe.Served
        } catch (_: IOException) {
            stream.abort()
            return DropServe.Failed("connection")
        } finally {
            if (counted) TransferActivityGuard.endTransfer(streamKey = guardKey(lease))
            if (!spent) lease.release()
        }
    }

    private fun guardKey(lease: DropLease) = "drop:${lease.id}"

    private fun unchanged(file: File, lease: DropLease): Boolean = lease.lastModifiedMs?.let { it == file.lastModified() } ?: true

    /** Null when every byte went out; otherwise the reason it stopped. */
    private suspend fun push(stream: IrohStream, file: File, input: java.io.InputStream, lease: DropLease): String? {
        val buffer = ByteArray(CHUNK)
        var left = lease.sizeBytes
        while (left > 0) {
            if (lease.expired()) return "time limit"
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) return "file changed"
            FriendsWire.writeRaw(stream, buffer.copyOf(n))
            left -= n
            TransferActivityGuard.updateProgress(lease.sizeBytes - left, lease.sizeBytes, streamKey = guardKey(lease))
        }
        if (!unchanged(file, lease)) return "file changed"
        return null
    }

    private suspend fun awaitReceipt(stream: IrohStream, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            try {
                stream.read(1)
            } catch (_: IOException) {
                null
            }
        }
    }

    private suspend fun refuse(stream: IrohStream, code: String, result: DropServe): DropServe {
        try {
            FriendsWire.writeFrame(stream, GuestDropWire.encodeNo(code))
            stream.finish()
        } catch (_: IOException) {
            stream.abort()
        }
        return result
    }
}
