package com.fileapex.iroh

import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointBuilder
import computer.iroh.EndpointId
import computer.iroh.RelayConfig
import computer.iroh.RelayMap
import computer.iroh.RelayMode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IrohEndpointLink private constructor(private val endpoint: Endpoint) : IrohLinkHandle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<IrohInbound>(Channel.UNLIMITED)
    private val known = ConcurrentHashMap<String, EndpointAddr>()
    private val connections = ConcurrentHashMap<String, Connection>()
    private val connectLocks = ConcurrentHashMap<String, Mutex>()
    private var addressWatch: Job? = null
    private val addressNudge = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    override val id: String = endpoint.id().toBytes().toHex()

    init {
        scope.launch { acceptConnections() }
    }

    /** Everything a peer needs to dial this endpoint: id, home relay and direct addresses. */
    override fun address(): String {
        val addr = endpoint.addr()
        return listOf(id, addr.relayUrl().orEmpty(), addr.directAddresses().joinToString(",")).joinToString("|")
    }

    /** Remembers how to reach a peer and returns its id. */
    override fun addPeer(address: String): String {
        val parts = address.split("|")
        require(parts.size == 3) { "bad address" }
        val peerId = parts[0]
        val direct = parts[2].split(",").filter { it.isNotBlank() }
        known[peerId] = EndpointAddr(EndpointId.fromBytes(peerId.hexToBytes()), parts[1].ifBlank { null }, direct)
        return peerId
    }

    /** Polled: the library's own watcher needs its async runtime context, which a plain call from here lacks. */
    override fun onAddressChanged(onChange: () -> Unit) {
        addressWatch?.cancel()
        addressWatch = scope.launch {
            var last = IrohAddressKey.of(address())
            while (true) {
                kotlinx.coroutines.withTimeoutOrNull(ADDRESS_POLL_MS) { addressNudge.receive() }
                val now = runCatching { IrohAddressKey.of(address()) }.getOrNull() ?: continue
                if (now != last) {
                    IrohLog.add("address changed: ${IrohAddressKey.describeChange(last, now)}")
                    last = now
                    onChange()
                }
            }
        }
    }

    override fun checkAddressNow() {
        addressNudge.trySend(Unit)
    }

    override fun pathKind(peerId: String): String? {
        val connection = connections[peerId]?.takeIf { it.closeReason() == null } ?: return null
        val selected = connection.paths().filter { it.isSelected }
        if (selected.isEmpty()) return null
        return if (selected.any { it.isRelay }) "relay" else "direct"
    }

    override suspend fun accept(): IrohInbound? =
        try {
            inbox.receive()
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            null
        }

    override suspend fun open(peerId: String): IrohStream {
        for (attempt in 0..1) {
            try {
                return Wrapped(withTimeout(CONNECT_TIMEOUT_MS) { connectionTo(peerId).openBi() })
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) throw java.io.IOException("iroh connect timed out")
                throw e
            } catch (e: computer.iroh.IrohException) {
                connections.remove(peerId)
                IrohLog.add("open to ${IrohLog.short(peerId)} failed: ${e.message.orEmpty().take(100)}")
                if (attempt == 1) throw java.io.IOException("iroh open: ${e.message.orEmpty().take(120)}", e)
            }
        }
        throw java.io.IOException("iroh open failed")
    }

    override fun isConnected(peerId: String): Boolean {
        val connection = connections[peerId] ?: return false
        return connection.closeReason() == null && connection.paths().any { it.isSelected }
    }

    override suspend fun connect(peerId: String) {
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { connectionTo(peerId) }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) throw java.io.IOException("iroh connect timed out")
            throw e
        } catch (e: computer.iroh.IrohException) {
            connections.remove(peerId)
            throw java.io.IOException("iroh connect: ${e.message.orEmpty().take(120)}", e)
        }
    }

    override suspend fun close() {
        inbox.close()
        addressWatch?.cancel()
        scope.cancel()
        endpoint.shutdown()
    }

    private suspend fun connectionTo(peerId: String): Connection =
        connectLocks.getOrPut(peerId) { Mutex() }.withLock {
            connections[peerId]?.takeIf { usable(it) }?.let { return@withLock it }
            val addr = known[peerId] ?: error("unknown peer")
            val started = System.nanoTime()
            val (connection, how) = raceConnect(addr)
            IrohLog.add("connected to ${IrohLog.short(peerId)} in ${(System.nanoTime() - started) / 1_000_000} ms ($how)")
            connections[peerId] = connection
            scope.launch { acceptStreams(peerId, connection) }
            connection
        }

    private fun usable(connection: Connection): Boolean =
        !IrohConnectionPolicy.shouldReplace(
            storedClosed = connection.closeReason() != null,
            storedHasSelectedPath = connection.paths().any { it.isSelected }
        )

    /**
     * The stored address may be stale (the peer moved networks) and a dial that hangs on it must not cost seconds
     * before discovery gets a turn, so a dial by id with the relay hint starts [BY_ID_DELAY_MS] later and the
     * first connection to succeed wins.
     */
    private suspend fun raceConnect(stored: EndpointAddr): Pair<Connection, String> = coroutineScope {
        val winner = CompletableDeferred<Pair<Connection, String>>()
        val failed = AtomicInteger(0)
        fun attempt(label: String, delayMs: Long, target: EndpointAddr) = launch {
            try {
                if (delayMs > 0) delay(delayMs)
                val connection = endpoint.connect(target, ALPN)
                if (!winner.complete(connection to label)) runCatching { connection.close(0L, ByteArray(0)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: computer.iroh.IrohException) {
                if (failed.incrementAndGet() == 2) winner.completeExceptionally(e)
            }
        }
        val first = attempt("stored address", 0L, stored)
        val second = attempt("by id", BY_ID_DELAY_MS, EndpointAddr(stored.id(), stored.relayUrl(), emptyList()))
        try {
            winner.await()
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    private suspend fun acceptConnections() {
        while (true) {
            val incoming = try {
                endpoint.acceptNext() ?: return
            } catch (e: CancellationException) {
                throw e
            } catch (e: computer.iroh.IrohException) {
                return
            }
            scope.launch {
                try {
                    val connection = incoming.accept().connect()
                    val peerId = connection.remoteId().toBytes().toHex()
                    IrohLog.add("accepted connection from ${IrohLog.short(peerId)}")
                    connections.compute(peerId) { _, old ->
                        if (old == null || !usable(old)) connection else old
                    }
                    acceptStreams(peerId, connection)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: computer.iroh.IrohException) {
                }
            }
        }
    }

    /** Hands every stream the peer opens on [connection] to [accept]; ends when the connection closes. */
    private suspend fun acceptStreams(peerId: String, connection: Connection) {
        try {
            while (true) {
                inbox.send(IrohInbound(peerId, Wrapped(connection.acceptBi())))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: computer.iroh.IrohException) {
            connections.remove(peerId, connection)
            IrohLog.add("connection with ${IrohLog.short(peerId)} ended: ${e.message.orEmpty().take(60)}")
        }
    }

    private inner class Wrapped(private val bi: BiStream) : IrohStream {
        private val recv = bi.recv()
        private val send = bi.send()

        override suspend fun read(max: Int): ByteArray? {
            val chunk = try {
                recv.read(max.toUInt())
            } catch (e: computer.iroh.IrohException) {
                if (e.message.orEmpty().contains("closed", ignoreCase = true)) return null
                throw java.io.IOException("iroh read", e)
            }
            return chunk.takeIf { it.isNotEmpty() }
        }

        override suspend fun write(bytes: ByteArray) {
            try {
                send.writeAll(bytes)
            } catch (e: computer.iroh.IrohException) {
                throw java.io.IOException("iroh write", e)
            }
        }

        override suspend fun finish() {
            try {
                send.finish()
            } catch (e: computer.iroh.IrohException) {
                throw java.io.IOException("iroh finish", e)
            }
        }

        override fun abort() {
            scope.launch {
                runCatching { send.reset(0uL) }
                runCatching { recv.stop(0uL) }
            }
        }
    }

    companion object {
        val ALPN = "fileapex/http/1".encodeToByteArray()
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val BY_ID_DELAY_MS = 1_500L
        private const val ADDRESS_POLL_MS = 30_000L

        /**
         * [bindAddr] null binds all interfaces. [relay] false keeps traffic off any relay, which is
         * how the local speed tests run. [secretKey] null makes a new identity.
         */
        suspend fun bind(
            secretKey: ByteArray? = null,
            bindAddr: String? = null,
            relay: Boolean = true,
            customRelay: IrohRelayConfig? = null
        ): IrohEndpointLink {
            val builder = EndpointBuilder()
            if (relay) builder.applyN0() else builder.applyN0DisableRelay()
            if (relay && customRelay != null) {
                val map = RelayMap.empty()
                map.insert(RelayConfig(customRelay.url, null, customRelay.token.ifBlank { null }))
                builder.relayMode(RelayMode.custom(map))
            }
            builder.alpns(listOf(ALPN))
            if (bindAddr != null) builder.bindAddr(bindAddr)
            if (secretKey != null) builder.secretKey(secretKey)
            val endpoint = builder.bind()
            // Best effort: with a home relay in the address, a peer behind a firewall can still reach us.
            if (relay) {
                try {
                    withTimeout(5_000L) { endpoint.online() }
                } catch (_: TimeoutCancellationException) {
                    // No relay within 5 s: start anyway, the address gains its relay later.
                }
            }
            return IrohEndpointLink(endpoint)
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
