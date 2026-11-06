package com.fileapex.drop

import com.fileapex.iroh.IrohStream

/** A real stream returns at most what was asked and reports a peer that left as an IOException; the in-memory one does neither, so this makes it match. */
internal class BoundedStream(private val inner: IrohStream) : IrohStream {
    private var rest = ByteArray(0)

    override suspend fun read(max: Int): ByteArray? {
        if (rest.isEmpty()) rest = inner.read(max) ?: return null
        val n = minOf(max, rest.size)
        return rest.copyOf(n).also { rest = rest.copyOfRange(n, rest.size) }
    }

    override suspend fun write(bytes: ByteArray) {
        try {
            inner.write(bytes)
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            throw java.io.IOException("peer left", e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e.message == "stream aborted") throw java.io.IOException("peer left", e)
            throw e
        }
    }

    override suspend fun finish() = inner.finish()

    override fun abort() = inner.abort()
}
