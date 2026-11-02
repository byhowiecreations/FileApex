package com.fileapex.friends

import com.fileapex.iroh.IrohStream
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointBuilder
import computer.iroh.EndpointId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

actual fun embeddedFriendsEngine(): FriendsEngine? = object : FriendsEngine {
    override suspend fun start(secretKey: ByteArray, hideIp: Boolean, admit: suspend (String) -> Boolean): FriendsEndpoint =
        FriendsIrohEndpoint.bind(secretKey, hideIp, admit)
}

/** Friends ALPN only. Never shares a key, router or handler with the main Iroh link. */
internal class FriendsIrohEndpoint private constructor(
    private val endpoint: Endpoint,
    private val hideIp: Boolean,
    private val admit: suspend (String) -> Boolean
) : FriendsEndpoint {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = FriendsInbox()
    private val connections = ConcurrentHashMap<String, Connection>()
    private val refused = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val alive = AtomicBoolean(true)
    override val isAlive: Boolean get() = alive.get()

    override val id: String = endpoint.id().toBytes().toHex()

    init {
        scope.launch { acceptLoop() }
    }

    override fun address(): String {
        val a = endpoint.addr()
        return listOf(id, a.relayUrl().orEmpty(), if (hideIp) "" else a.directAddresses().joinToString(",")).joinToString("|")
    }

    override suspend fun accept(): FriendsInbound? = inbox.accept()

    override suspend fun open(peerId: String, addressHint: String?): IrohStream {
        for (attempt in 0..1) {
            try {
                return Wrapped(withTimeout(CONNECT_TIMEOUT_MS) { connectionTo(peerId, addressHint).openBi() })
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) throw java.io.IOException("friend connect timed out")
                throw e
            } catch (e: computer.iroh.IrohException) {
                connections.remove(peerId)
                if (attempt == 1) throw java.io.IOException("friend open: ${e.message.orEmpty().take(120)}", e)
            }
        }
        throw java.io.IOException("friend open failed")
    }

    override suspend fun connect(peerId: String) {
        try { withTimeout(CONNECT_TIMEOUT_MS) { connectionTo(peerId, null) } }
        catch (e: TimeoutCancellationException) { throw java.io.IOException("friend connect timed out") }
        catch (e: computer.iroh.IrohException) { connections.remove(peerId); throw java.io.IOException("friend open: ${e.message.orEmpty().take(120)}", e) }
    }

    override fun refusedBy(peerId: String) = peerId in refused

    override fun pathInfo(peerId: String): FriendPathInfo? {
        val c = connections[peerId]?.takeIf { it.closeReason() == null } ?: return null
        val path = c.paths().firstOrNull { it.isSelected } ?: return null
        return FriendPathInfo(relay = path.isRelay, local = path.isIp && FriendPathAddress.isLocal(path.remoteAddr), rttMs = path.rttMs.toLong())
    }

    override suspend fun close() {
        alive.set(false)
        inbox.close()
        scope.cancel()
        endpoint.shutdown()
    }

    private fun target(peerId: String, hint: String?): EndpointAddr {
        val parts = hint?.split("|")?.takeIf { it.size == 3 && it[0] == peerId }
        val relay = parts?.get(1)?.ifBlank { null }
        val direct = if (hideIp) emptyList() else parts?.get(2)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        return EndpointAddr(EndpointId.fromBytes(peerId.hexToBytes()), relay, direct)
    }

    private suspend fun connectionTo(peerId: String, hint: String?): Connection =
        locks.getOrPut(peerId) { Mutex() }.withLock {
            connections[peerId]?.takeIf { it.closeReason() == null }?.let { return@withLock it }
            val connection = endpoint.connect(target(peerId, hint), ALPN)
            connections[peerId] = connection
            scope.launch { acceptStreams(peerId, connection) }
            scope.launch { if (NOT_A_FRIEND in connection.closed()) refused.add(peerId) }
            connection
        }

    private suspend fun acceptLoop() {
        try { while (true) {
            val incoming = try { endpoint.acceptNext() ?: return } catch (e: CancellationException) { throw e } catch (_: computer.iroh.IrohException) { return }
            scope.launch {
                try {
                    val connection = incoming.accept().connect()
                    val peer = connection.remoteId().toBytes().toHex()
                    if (!admit(peer)) {
                        runCatching { connection.close(1L, NOT_A_FRIEND.encodeToByteArray()) }
                        return@launch
                    }
                    connections.compute(peer) { _, old -> if (old == null || old.closeReason() != null) connection else old }
                    acceptStreams(peer, connection)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: computer.iroh.IrohException) {
                }
            }
        } } finally {
            alive.set(false)
            inbox.close()
        }
    }

    private suspend fun acceptStreams(peerId: String, connection: Connection) {
        try {
            while (true) inbox.send(FriendsInbound(peerId, Wrapped(connection.acceptBi())))
        } catch (e: CancellationException) {
            throw e
        } catch (_: computer.iroh.IrohException) {
            connections.remove(peerId, connection)
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
                throw java.io.IOException("friend read", e)
            }
            return chunk.takeIf { it.isNotEmpty() }
        }

        override suspend fun write(bytes: ByteArray) {
            try { send.writeAll(bytes) } catch (e: computer.iroh.IrohException) { throw java.io.IOException("friend write", e) }
        }

        override suspend fun finish() {
            try { send.finish() } catch (e: computer.iroh.IrohException) { throw java.io.IOException("friend finish", e) }
        }

        override fun abort() {
            scope.launch {
                runCatching { send.reset(0uL) }
                runCatching { recv.stop(0uL) }
            }
        }
    }

    companion object {
        val ALPN = "fileapex/friends/1".encodeToByteArray()
        private const val CONNECT_TIMEOUT_MS = 30_000L
        /** The close reason a device gives a peer it does not have as a friend. */
        private const val NOT_A_FRIEND = "not a friend"

        suspend fun bind(secretKey: ByteArray, hideIp: Boolean, admit: suspend (String) -> Boolean): FriendsIrohEndpoint {
            val builder = EndpointBuilder()
            builder.applyN0()
            builder.alpns(listOf(ALPN))
            // Hiding the IP: sockets only on loopback, so no direct address exists to publish or punch; only the relay path remains.
            if (hideIp) builder.bindAddr("127.0.0.1:0")
            builder.secretKey(secretKey)
            val endpoint = builder.bind()
            try {
                withTimeout(5_000L) { endpoint.online() }
            } catch (_: TimeoutCancellationException) {
            }
            return FriendsIrohEndpoint(endpoint, hideIp, admit)
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
