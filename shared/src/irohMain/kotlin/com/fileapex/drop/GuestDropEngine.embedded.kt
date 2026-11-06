package com.fileapex.drop

import com.fileapex.iroh.IrohStream
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointBuilder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout

object GuestDropIroh {
    val ALPN = "fileapex/drop/1".encodeToByteArray()
}

actual fun embeddedGuestDropEngine(): GuestDropEngine? = object : GuestDropEngine {
    override suspend fun start(secretKey: ByteArray, useRelay: Boolean): GuestDropEndpoint =
        GuestDropIrohEndpoint.bind(secretKey, useRelay)
}

/** Drop ALPN only, loopback sockets only, so a visitor can reach this device through the relay and nothing else. */
internal class GuestDropIrohEndpoint private constructor(val raw: Endpoint, private val streamWaitMs: Long) : GuestDropEndpoint {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<IrohStream>(INBOX, onUndeliveredElement = { it.abort() })
    private val connectionSlots = Semaphore(MAX_CONNECTIONS)
    private val alive = AtomicBoolean(true)
    override val isAlive: Boolean get() = alive.get()
    override val id: String = raw.id().toBytes().joinToString("") { "%02x".format(it) }

    init {
        scope.launch { acceptLoop() }
    }

    override fun relayUrl(): String? = raw.addr().relayUrl()

    override suspend fun accept(): IrohStream? = inbox.receiveCatching().getOrNull()

    override suspend fun close() {
        alive.set(false)
        inbox.cancel()
        scope.cancel()
        raw.shutdown()
    }

    private suspend fun acceptLoop() {
        try {
            while (true) {
                val incoming = try {
                    raw.acceptNext() ?: return
                } catch (e: CancellationException) {
                    throw e
                } catch (_: computer.iroh.IrohException) {
                    return
                }
                if (!connectionSlots.tryAcquire()) {
                    scope.launch { turnAway(incoming) }
                    continue
                }
                scope.launch {
                    try {
                        val connection = withTimeout(HANDSHAKE_MS) { incoming.accept().connect() }
                        streamsOf(connection)
                    } catch (e: CancellationException) {
                        if (e !is TimeoutCancellationException) throw e
                    } catch (_: computer.iroh.IrohException) {
                    } finally {
                        connectionSlots.release()
                    }
                }
            }
        } finally {
            alive.set(false)
            inbox.cancel()
        }
    }

    /** A visitor gets one stream; a second one on the same connection is not served, and a visitor who opens none is let go. */
    private suspend fun streamsOf(connection: Connection) {
        val bi = try {
            withTimeout(streamWaitMs) { connection.acceptBi() }
        } catch (_: TimeoutCancellationException) {
            closeQuietly(connection, NO_STREAM)
            return
        }
        inbox.send(Wrapped(bi))
    }

    private suspend fun turnAway(incoming: computer.iroh.Incoming) {
        try {
            closeQuietly(incoming.accept().connect(), BUSY)
        } catch (_: computer.iroh.IrohException) {
            // The visitor already left; there is nothing to turn away.
        }
    }

    private fun closeQuietly(connection: Connection, code: Long) {
        try {
            connection.close(code, "closed".encodeToByteArray())
        } catch (_: computer.iroh.IrohException) {
            // Already closed by the other side.
        }
    }

    private inner class Wrapped(bi: BiStream) : IrohStream {
        private val recv = bi.recv()
        private val send = bi.send()

        override suspend fun read(max: Int): ByteArray? {
            val chunk = try {
                recv.read(max.toUInt())
            } catch (e: computer.iroh.IrohException) {
                if (e.message.orEmpty().contains("closed", ignoreCase = true)) return null
                throw java.io.IOException("drop read", e)
            }
            return chunk.takeIf { it.isNotEmpty() }
        }

        override suspend fun write(bytes: ByteArray) {
            try { send.writeAll(bytes) } catch (e: computer.iroh.IrohException) { throw java.io.IOException("drop write", e) }
        }

        override suspend fun finish() {
            try { send.finish() } catch (e: computer.iroh.IrohException) { throw java.io.IOException("drop finish", e) }
        }

        override fun abort() {
            scope.launch {
                try { send.reset(0uL) } catch (_: computer.iroh.IrohException) { /* stream already ended */ }
                try { recv.stop(0uL) } catch (_: computer.iroh.IrohException) { /* stream already ended */ }
            }
        }
    }

    companion object {
        private const val INBOX = 16
        private const val MAX_CONNECTIONS = 8
        private const val HANDSHAKE_MS = 15_000L
        private const val ONLINE_WAIT_MS = 5_000L
        private const val STREAM_WAIT_MS = 15_000L
        private const val BUSY = 2L
        private const val NO_STREAM = 3L

        suspend fun bind(secretKey: ByteArray, useRelay: Boolean, streamWaitMs: Long = STREAM_WAIT_MS): GuestDropIrohEndpoint {
            val endpoint = try {
                val builder = EndpointBuilder()
                if (useRelay) builder.applyN0() else builder.applyN0DisableRelay()
                builder.alpns(listOf(GuestDropIroh.ALPN))
                builder.bindAddr("127.0.0.1:0")
                builder.secretKey(secretKey)
                builder.bind()
            } catch (e: computer.iroh.IrohException) {
                throw java.io.IOException("drop endpoint: ${e.message.orEmpty().take(120)}", e)
            }
            if (useRelay) {
                try {
                    withTimeout(ONLINE_WAIT_MS) { endpoint.online() }
                } catch (_: TimeoutCancellationException) {
                }
            }
            return GuestDropIrohEndpoint(endpoint, streamWaitMs)
        }
    }
}
