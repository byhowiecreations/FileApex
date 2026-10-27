package com.fileapex.iroh

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.fileapex.security.tls.BridgedPeer
import com.fileapex.security.tls.TlsBridgeRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IrohBridge(
    private val link: IrohLink,
    private val serverPort: Int,
    private val isPeerAllowed: suspend (String) -> Boolean,
    private val scope: CoroutineScope,
    private val maxConcurrentStreams: Int = DEFAULT_MAX_STREAMS,
    private val bufferSize: Int = DEFAULT_BUFFER_BYTES,
    private val onPeerActive: (String) -> Unit = {},
    private val bridgePort: () -> Int = { 0 },
    private val identify: suspend (String) -> BridgedPeer? = { null }
) {
    private class Listener(val socket: ServerSocket, val job: Job)

    private val listeners = mutableMapOf<String, Listener>()
    private val listenersLock = Mutex()
    private val activeInbound = AtomicInteger()
    private var acceptJob: Job? = null

    @Synchronized fun start() {
        if (acceptJob != null) return
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val inbound = link.accept() ?: break
                handleInbound(inbound)
            }
        }
    }

    /** Loopback port that reaches [peerId]. The same port comes back on repeat calls. */
    suspend fun portFor(peerId: String): Int = listenersLock.withLock {
        listeners.getOrPut(peerId) { openListener(peerId) }.socket.localPort
    }

    suspend fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        val open = listenersLock.withLock { listeners.values.toList().also { listeners.clear() } }
        for (listener in open) {
            IrohBridgePorts.remove(listener.socket.localPort)
            runCatching { listener.socket.close() }
            listener.job.cancel()
        }
        link.close()
    }

    private fun openListener(peerId: String): Listener {
        val socket = ServerSocket(0, BACKLOG, LOOPBACK_V4)
        IrohBridgePorts.add(socket.localPort)
        val job = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                scope.launch(Dispatchers.IO) { carryOutbound(client, peerId) }
            }
        }
        return Listener(socket, job)
    }

    private suspend fun carryOutbound(client: Socket, peerId: String) {
        val stream = try {
            link.open(peerId)
        } catch (e: CancellationException) {
            client.closeQuietly()
            throw e
        } catch (e: IOException) {
            logDrop("open", e)
            client.closeQuietly()
            return
        } catch (e: IllegalStateException) {
            logDrop("open", e)
            client.closeQuietly()
            return
        } catch (e: Exception) {
            // Coroutine boundary: one failed stream must never take the listener down with it.
            logDrop("open", e)
            client.closeQuietly()
            return
        }
        pump(client, stream, requestsFromSocket = true, peerId = peerId)
    }

    private suspend fun handleInbound(inbound: IrohInbound) {
        if (!isPeerAllowed(inbound.peerId)) {
            IrohLog.add("bridge refused stream from unknown ${IrohLog.short(inbound.peerId)}")
            inbound.stream.abort()
            return
        }
        if (activeInbound.incrementAndGet() > maxConcurrentStreams) {
            activeInbound.decrementAndGet()
            inbound.stream.abort()
            return
        }
        scope.launch(Dispatchers.IO) {
            var registeredPort = -1
            try {
                // The endpoint id is proven by the QUIC handshake. Entering through the bridge connector under a
                // registered source port is how the server learns which paired device this stream belongs to.
                val peer = identify(inbound.peerId)
                val bridge = bridgePort()
                val viaBridge = peer != null && bridge > 0
                val server = Socket()
                try {
                    server.connect(InetSocketAddress(LOOPBACK_V4, if (viaBridge) bridge else serverPort), CONNECT_TIMEOUT_MS)
                } catch (e: IOException) {
                    logDrop("server", e)
                    server.closeQuietly()
                    inbound.stream.abort()
                    return@launch
                }
                if (viaBridge && peer != null) {
                    registeredPort = server.localPort
                    TlsBridgeRegistry.register(registeredPort, peer)
                }
                pump(server, inbound.stream, requestsFromSocket = false, peerId = inbound.peerId)
            } finally {
                if (registeredPort > 0) TlsBridgeRegistry.unregister(registeredPort)
                activeInbound.decrementAndGet()
            }
        }
    }

    /** Copies both ways until each side has finished, then closes. A failure on either side drops both. */
    private suspend fun pump(socket: Socket, stream: IrohStream, requestsFromSocket: Boolean, peerId: String) {
        val label = IrohLog.short(peerId)
        val direction = if (requestsFromSocket) "to" else "from"
        val startedAt = System.nanoTime()
        var answered = false
        val lastActivity = AtomicLong(startedAt)
        val sniffer = HttpExchangeSniffer(
            onStart = { request ->
                val what = IrohActivity.describe(request)
                if (what != IrohActivity.PRESENCE_CHECK) {
                    IrohLog.add("$what started ($direction $label)")
                    onPeerActive(peerId)
                }
            }
        ) {
            answered = true
            IrohLog.add("${IrohActivity.describe(it)} ($direction $label) after ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        }
        socket.tcpNoDelay = true
        val watchdog = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val idleMs = (System.nanoTime() - lastActivity.get()) / 1_000_000
                if (IrohStallPolicy.isStalled(0L, idleMs)) {
                    IrohLog.add("request ($direction $label) moved no bytes for ${idleMs / 1000} s: closing it")
                    socket.closeQuietly()
                    stream.abort()
                    break
                }
            }
        }
        try {
            coroutineScope {
                launch(Dispatchers.IO) {
                    guarded(socket, stream) {
                        val input = socket.getInputStream()
                        val buffer = ByteArray(bufferSize)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            val chunk = buffer.copyOf(n)
                            lastActivity.set(System.nanoTime())
                            if (requestsFromSocket) sniffer.onRequest(chunk) else sniffer.onResponse(chunk)
                            stream.write(chunk)
                        }
                        stream.finish()
                    }
                }
                launch(Dispatchers.IO) {
                    guarded(socket, stream) {
                        val output = socket.getOutputStream()
                        while (true) {
                            val chunk = stream.read(bufferSize) ?: break
                            lastActivity.set(System.nanoTime())
                            if (requestsFromSocket) sniffer.onResponse(chunk) else sniffer.onRequest(chunk)
                            output.write(chunk)
                            output.flush()
                        }
                        socket.shutdownOutput()
                    }
                }
            }
        } finally {
            watchdog.cancel()
            if (!answered) {
                IrohLog.add("no HTTP response ${if (requestsFromSocket) "from" else "sent to"} $label after ${(System.nanoTime() - startedAt) / 1_000_000} ms")
            }
            socket.closeQuietly()
        }
    }

    /** Coroutine boundary: closing the socket also wakes the sibling blocked on a read. */
    private suspend fun guarded(socket: Socket, stream: IrohStream, body: suspend () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            stream.abort()
            socket.closeQuietly()
            throw e
        } catch (e: IOException) {
            logDrop("pump", e)
            stream.abort()
            socket.closeQuietly()
        } catch (e: Exception) {
            // Coroutine boundary: report the class and drop only this stream.
            logDrop("pump", e)
            stream.abort()
            socket.closeQuietly()
        }
    }

    private fun logDrop(stage: String, error: Exception) {
        IrohLog.add("bridge $stage dropped: ${error::class.simpleName} ${error.message.orEmpty().take(80)}")
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }

    companion object {
        const val DEFAULT_MAX_STREAMS = 64
        private const val WATCHDOG_TICK_MS = 10_000L
        const val DEFAULT_BUFFER_BYTES = 64 * 1024
        /** Always IPv4: the HTTP client dials the literal 127.0.0.1, and some Android versions report ::1 as the loopback address. */
        internal val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val BACKLOG = 50
        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}
