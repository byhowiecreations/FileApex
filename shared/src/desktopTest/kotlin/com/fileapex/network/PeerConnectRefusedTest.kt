package com.fileapex.network

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerConnectRefusedTest {
    @Test
    fun aClosedPortOnAnAnsweringHostIsRefused() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertTrue(peerConnectRefused("127.0.0.1", port, 500L))
    }

    @Test
    fun aListeningPortIsNotRefused() = runBlocking {
        ServerSocket(0).use { server ->
            assertFalse(peerConnectRefused("127.0.0.1", server.localPort, 500L))
        }
    }

    @Test
    fun anAddressThatNeverAnswersIsNotRefused() = runBlocking {
        val started = System.currentTimeMillis()
        assertFalse(peerConnectRefused("10.255.255.1", 8080, 300L))
        assertTrue(System.currentTimeMillis() - started < 3_000L)
    }

    @Test
    fun onlyRefusalWordingCounts() {
        assertTrue(isConnectionRefused(java.net.ConnectException("Connection refused")))
        assertTrue(isConnectionRefused(java.net.ConnectException("failed to connect to /192.168.1.5 (port 8080): connect failed: ECONNREFUSED (Connection refused)")))
        assertFalse(isConnectionRefused(java.net.ConnectException("Network is unreachable")))
        assertFalse(isConnectionRefused(java.net.ConnectException("Connection timed out")))
        assertFalse(isConnectionRefused(java.net.ConnectException()))
    }
}
