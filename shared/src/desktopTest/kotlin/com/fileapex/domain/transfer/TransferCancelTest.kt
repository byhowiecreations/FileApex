package com.fileapex.domain.transfer

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferCancelTest {
    @Test
    fun cancelClosesTheSocketABlockedWriteIsUsing() {
        val server = ServerSocket(0)
        val client = Socket()
        try {
            val accepted = Thread { server.accept() }
            accepted.start()
            client.connect(InetSocketAddress("127.0.0.1", server.localPort), 2_000)
            accepted.join(2_000)
            TransferActivityGuard.trackTransferSocket(client)
            val writer = Thread {
                try {
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        client.getOutputStream().write(buffer)
                    }
                } catch (_: IOException) {
                }
            }
            writer.isDaemon = true
            writer.start()
            Thread.sleep(100)
            assertTrue(TransferActivityGuard.cancelActiveTransfers())
            writer.join(2_000)
            assertFalse(writer.isAlive)
            assertTrue(client.isClosed)
        } finally {
            TransferActivityGuard.releaseTransferSocket(client)
            runCatching { client.close() }
            runCatching { server.close() }
            TransferActivityGuard.reset()
        }
    }
}
