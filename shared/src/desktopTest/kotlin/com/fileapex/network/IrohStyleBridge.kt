package com.fileapex.network

import com.fileapex.security.tls.BridgedPeer
import com.fileapex.security.tls.TlsBridgeRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Stands in for the Iroh bridge: each connection is piped to the server's bridge port from a source port that is
 * registered in [TlsBridgeRegistry] as [deviceId], which is how the server learns who is calling. [port] is what a
 * client dials (as an Iroh bridge port, plain HTTP over loopback).
 */
internal class IrohStyleBridge(private val bridgePort: Int, private val deviceId: String) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val pool = Executors.newCachedThreadPool { Thread(it, "iroh-style-bridge").apply { isDaemon = true } }
    val port: Int = listener.localPort

    init {
        pool.execute {
            while (!listener.isClosed) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                pool.execute { pipe(client) }
            }
        }
    }

    private fun pipe(client: Socket) {
        var sourcePort = -1
        try {
            val server = Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), bridgePort)
            sourcePort = server.localPort
            TlsBridgeRegistry.register(sourcePort, BridgedPeer(deviceId, "test-pin", "127.0.0.1"))
            val upstream = pool.submit { copy(client, server) }
            copy(server, client)
            upstream.cancel(true)
            runCatching { server.close() }
        } catch (_: Exception) {
        } finally {
            if (sourcePort > 0) TlsBridgeRegistry.unregister(sourcePort)
            runCatching { client.close() }
        }
    }

    private fun copy(from: Socket, to: Socket) {
        runCatching {
            val buffer = ByteArray(64 * 1024)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
            runCatching { to.shutdownOutput() }
        }
    }

    override fun close() {
        runCatching { listener.close() }
        pool.shutdownNow()
    }
}
