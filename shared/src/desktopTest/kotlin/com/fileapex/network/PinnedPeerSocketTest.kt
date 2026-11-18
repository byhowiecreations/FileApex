package com.fileapex.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedPeerSocketTest {
    @Test fun closingItEndsTheConnectionAtThePeerAndIsSafeToRepeat() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var peerSaw = -2
        val reader = thread(isDaemon = true) {
            server.accept().use { peerSaw = it.getInputStream().read() }
        }
        val socket = PinnedPeerSocket("", "", ConnectFailure())
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 2_000)
        assertTrue(socket.isConnected)
        socket.close()
        socket.close()
        reader.join(3_000)
        server.close()
        assertEquals("the peer must see the connection end", -1, peerSaw)
        assertTrue(socket.isClosed)
    }

    @Test fun aSocketClosedBeforeItConnectsNeverLeavesAConnectionOpen() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val socket = PinnedPeerSocket("", "", ConnectFailure())
        socket.close()
        runCatching { socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 2_000) }
        server.close()
        assertTrue(socket.isClosed)
    }

    @Test fun aRefusedConnectIsRecordedAndRaisedAsAnIoError() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val failure = ConnectFailure()
        val socket = PinnedPeerSocket("", "", failure)
        val raised = runCatching { socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000) }.exceptionOrNull()
        assertTrue(raised is java.io.IOException)
        assertTrue(failure.error != null)
    }
}
