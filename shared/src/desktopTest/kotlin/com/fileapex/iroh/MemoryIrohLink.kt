package com.fileapex.iroh

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/** In-memory stand-in for two Iroh endpoints. The pipes are bounded, so a slow reader slows the writer. */
class MemoryIrohLink(private val id: String) : IrohLink {
    private val inbox = Channel<IrohInbound>(Channel.UNLIMITED)
    private val peers = mutableMapOf<String, MemoryIrohLink>()
    @Volatile private var closed = false
    val openedStreams = java.util.concurrent.atomic.AtomicInteger()

    fun connectTo(other: MemoryIrohLink) {
        peers[other.id] = other
        other.peers[id] = this
    }

    override suspend fun accept(): IrohInbound? =
        try {
            inbox.receive()
        } catch (_: ClosedReceiveChannelException) {
            null
        }

    override suspend fun open(peerId: String): IrohStream {
        val peer = peers[peerId] ?: error("no route to $peerId")
        check(!closed && !peer.closed) { "link closed" }
        val up = Pipe()
        val down = Pipe()
        openedStreams.incrementAndGet()
        peer.inbox.send(IrohInbound(id, MemoryStream(read = up, write = down)))
        return MemoryStream(read = down, write = up)
    }

    override suspend fun close() {
        closed = true
        inbox.close()
    }

    private class Pipe {
        val chunks = Channel<ByteArray>(capacity = 8)
        val aborted = AtomicBoolean(false)
    }

    private class MemoryStream(private val read: Pipe, private val write: Pipe) : IrohStream {
        override suspend fun read(max: Int): ByteArray? {
            if (read.aborted.get()) throw CancellationException("stream aborted")
            val chunk = try {
                read.chunks.receive()
            } catch (_: ClosedReceiveChannelException) {
                return null
            }
            if (read.aborted.get()) throw CancellationException("stream aborted")
            return chunk
        }

        override suspend fun write(bytes: ByteArray) {
            if (write.aborted.get()) throw CancellationException("stream aborted")
            write.chunks.send(bytes.copyOf())
        }

        override suspend fun finish() {
            write.chunks.close()
        }

        override fun abort() {
            read.aborted.set(true)
            write.aborted.set(true)
            read.chunks.close()
            write.chunks.close()
        }
    }
}

fun memoryLinkPair(idA: String = "peer-a", idB: String = "peer-b"): Pair<MemoryIrohLink, MemoryIrohLink> {
    val a = MemoryIrohLink(idA)
    val b = MemoryIrohLink(idB)
    a.connectTo(b)
    return a to b
}
