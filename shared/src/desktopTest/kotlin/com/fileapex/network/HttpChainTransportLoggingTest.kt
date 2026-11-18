package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.InMemoryDeviceDao
import com.fileapex.data.device.LocalDeviceRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A peer the policy refuses is unreachable for the chain, but never silently. */
class HttpChainTransportLoggingTest {
    private val logged = mutableListOf<String>()
    private val repo = DeviceRepository(InMemoryDeviceDao(), { LocalDeviceRef("self", emptySet()) })
    private val transport = HttpChainTransport(repo, FileApexClient(localDeviceId = { "self" }), log = { logged += it })

    private fun withPeer(block: suspend () -> Unit) = runBlocking {
        assertTrue(
            repo.adoptFromPairing(
                PairedDeviceEntity(deviceId = "peer-1", deviceName = "P", lastKnownIp = "192.0.2.1", port = 8080, publicKeyHash = "h", publicKey = "k", rootPath = "/", clusterVersion = 1L),
                1L
            )
        )
        block()
    }

    @Test fun pushPullAndJoinReturnNullAndEachFailureIsLogged() = withPeer {
        assertNull(transport.push("peer-1", emptyList(), emptyList(), ""))
        assertNull(transport.pull("peer-1", ""))
        assertNull(transport.join("peer-1", "key", "pin", null))
        assertEquals(3, logged.size)
        assertTrue(logged.all { it.contains("peer-1") })
        assertTrue(logged.any { it.contains("chain push") } && logged.any { it.contains("chain pull") } && logged.any { it.contains("chain join") })
    }

    @Test fun anUnknownPeerIsNullWithoutALog() = withPeer {
        assertNull(transport.pull("nobody", ""))
        assertTrue(logged.isEmpty())
    }
}

class UnpinnedChainTransportLoggingTest {
    private val logged = mutableListOf<String>()
    private val repo = DeviceRepository(InMemoryDeviceDao(), { LocalDeviceRef("self", emptySet()) })
    private val transport = UnpinnedChainTransport(repo, FileApexClient(localDeviceId = { "self" }), log = { logged += it })

    @Test fun anUnansweredReadReturnsNullAndIsLogged() = runBlocking {
        assertTrue(
            repo.adoptFromPairing(
                PairedDeviceEntity(deviceId = "peer-1", deviceName = "P", lastKnownIp = "192.0.2.1", port = 8080, publicKeyHash = "h", publicKey = "k", rootPath = "/", tlsPort = 8081, clusterVersion = 1L),
                1L
            )
        )
        repo.recordTlsPin("peer-1", "a".repeat(64), 8081)
        assertEquals(8081, repo.getDevice("peer-1")!!.tlsPort)
        assertNull(transport.pull("peer-1"))
        assertTrue("the unanswered read must be logged: $logged", logged.any { it.contains("peer-1") })
    }
}
