package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerOutboundRoutesTest {
    private fun peer(ip: String = "10.0.0.2", port: Int = 8080, hash: String = "") = PairedDeviceEntity(
        deviceId = "P", deviceName = "P", lastKnownIp = ip, port = port, publicKeyHash = hash, rootPath = "/data"
    )

    private fun state(id: String, hash: String = "") =
        PeerNodeState(deviceId = id, deviceName = id, port = 8080, publicKeyHash = hash)

    private class Host : RouteHost {
        var fetches = 0
        override suspend fun liveDevice(deviceId: String): PairedDeviceEntity? = null
        override suspend fun markReachableOverLan(deviceId: String) {}
        override suspend fun markReachableRemote(deviceId: String, via: RouteKind) {}
        override fun noteRoute(deviceId: String, kind: RouteKind) {}
        override suspend fun touchLastSeenEpoch(deviceId: String) {}
        override fun msSinceReached(deviceId: String): Long? = null
        override fun recordRemoteReach(deviceId: String, via: RouteKind) {}
        override suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long): PeerNodeState {
            fetches++
            error("no network in tests")
        }
        override suspend fun pingHealth(host: String, port: Int, timeoutMs: Long) = false
    }

    @Test
    fun sameIdIsTheSameDevice() {
        assertTrue(PeerOutboundRoutes.sameDevice(peer(), state("P")))
    }

    @Test
    fun sameKeyHashUnderAnotherIdIsTheSameDevice() {
        assertTrue(PeerOutboundRoutes.sameDevice(peer(hash = "h"), state("Q", hash = "h")))
    }

    @Test
    fun blankHashNeverMatchesAnotherId() {
        assertFalse(PeerOutboundRoutes.sameDevice(peer(hash = ""), state("Q", hash = "")))
        assertFalse(PeerOutboundRoutes.sameDevice(peer(hash = "h"), state("Q", hash = "other")))
    }

    @Test
    fun publicOrMissingAddressIsNeverAskedOnTheLan() = runBlocking {
        val host = Host()
        val routes = PeerOutboundRoutes(host)
        assertNull(routes.verifiedLanEndpoint(peer(ip = "8.8.8.8")))
        assertNull(routes.verifiedLanEndpoint(peer(ip = "")))
        assertNull(routes.verifiedLanEndpoint(peer(port = 0)))
        assertEquals(0, host.fetches)
    }
}
