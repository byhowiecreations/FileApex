package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.network.FileApexClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredEndpointProbeTest {
    private fun peer(root: String = "/data") = PairedDeviceEntity(
        deviceId = "P", deviceName = "P", lastKnownIp = "192.168.1.20", port = 8080, publicKeyHash = "", rootPath = root
    )

    private class Host(var health: FileApexClient.HealthProbe, var state: PeerNodeState? = null) : StoredEndpointHost {
        val log = mutableListOf<String>()
        override suspend fun probeHealth(host: String, port: Int, timeoutMs: Long, companion: Boolean?): FileApexClient.HealthProbe {
            log += "probe"
            return health
        }
        override suspend fun recordEndpointMismatch(peerId: String, host: String, answeredAs: String) { log += "mismatch:$answeredAs" }
        override suspend fun markReachable(deviceId: String) { log += "reach:$deviceId" }
        override fun onBulletinVersion(peer: PairedDeviceEntity, version: Long) { log += "bulletin:$version" }
        override fun pullRemovals(peer: PairedDeviceEntity, endpoint: ResolvedPeerEndpoint, theirs: String) { log += "removals:$theirs" }
        override fun reconcileCluster(peer: PairedDeviceEntity, endpoint: ResolvedPeerEndpoint, theirs: String) { log += "cluster:$theirs" }
        override suspend fun touchLastSeenEpoch(deviceId: String) { log += "epoch" }
        override suspend fun touchLastSeen(deviceId: String, host: String, port: Int) { log += "seen:$host" }
        override suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long): PeerNodeState {
            log += "state"
            return state ?: error("none")
        }
        override suspend fun applyNodeState(state: PeerNodeState, rosterDeviceId: String) { log += "apply:$rosterDeviceId" }
        override suspend fun supersedeHardwareTwins(state: PeerNodeState) { log += "twins" }
    }

    private fun nodeState(id: String) = PeerNodeState(deviceId = id, deviceName = id, port = 8080)

    @Test
    fun unreachedPeerRetriesThenFails() = runBlocking {
        val host = Host(FileApexClient.HealthProbe(reached = false))
        val ok = StoredEndpointProbe(host, NodeStateClaims()).tryStoredEndpoint(peer(), 2, 1L, 100L)
        assertFalse(ok)
        assertEquals(listOf("probe", "probe"), host.log)
    }

    @Test
    fun anotherDeviceAnsweringAtTheAddressIsAMismatchNotReach() = runBlocking {
        val host = Host(FileApexClient.HealthProbe(reached = true, respondedAs = "OTHER"))
        val ok = StoredEndpointProbe(host, NodeStateClaims()).tryStoredEndpoint(peer(), 2, 1L, 100L)
        assertFalse(ok)
        assertEquals(listOf("probe", "mismatch:OTHER"), host.log)
    }

    @Test
    fun reachedPeerIsMarkedTouchedAndAsksItsStateOnce() = runBlocking {
        val host = Host(FileApexClient.HealthProbe(reached = true, respondedAs = "P", bulletinVersion = 3L, removals = "fp"), nodeState("P"))
        val probe = StoredEndpointProbe(host, NodeStateClaims())
        assertTrue(probe.tryStoredEndpoint(peer(root = "/"), 1, 1L, 100L))
        assertEquals(
            listOf("probe", "reach:P", "bulletin:3", "removals:fp", "seen:192.168.1.20", "state", "apply:P", "reach:P"),
            host.log
        )
    }

    @Test
    fun stateFromAnotherDeviceIsRejected() = runBlocking {
        val host = Host(FileApexClient.HealthProbe(reached = true), nodeState("OTHER"))
        assertFalse(StoredEndpointProbe(host, NodeStateClaims()).tryStoredEndpoint(peer(root = "/"), 1, 1L, 100L))
        assertTrue("mismatch:OTHER" in host.log)
        assertFalse(host.log.any { it.startsWith("apply") })
    }

    @Test
    fun noStateFetchWhenTheCallerAsksForNone() = runBlocking {
        val host = Host(FileApexClient.HealthProbe(reached = true))
        assertTrue(StoredEndpointProbe(host, NodeStateClaims()).tryStoredEndpoint(peer(root = "/"), 1, 1L, 100L, fetchNodeState = false))
        assertFalse("state" in host.log)
    }

    @Test
    fun nodeStateSlotIsClaimedOncePerVersion() {
        var now = 1_000L
        val claims = NodeStateClaims { now }
        assertTrue(claims.claim(peer(), 5L))
        now += 1_000L
        assertFalse(claims.claim(peer(), 5L))
        assertTrue(claims.claim(peer(), 6L))
    }

    @Test
    fun aClusterDigestReplacesTheRemovalFingerprintWhenBothArrive() = runBlocking {
        val host = Host(FileApexClient.HealthProbe(reached = true, respondedAs = "P", removals = "fp", clusterDigest = "dg"))
        StoredEndpointProbe(host, NodeStateClaims()).tryStoredEndpoint(peer(), 1, 1L, 100L, fetchNodeState = false)
        assertTrue("cluster:dg" in host.log)
        assertFalse(host.log.any { it.startsWith("removals") })
    }

    @Test
    fun withoutADigestTheRemovalFingerprintStillWorks() = runBlocking {
        val host = Host(FileApexClient.HealthProbe(reached = true, respondedAs = "P", removals = "fp"))
        StoredEndpointProbe(host, NodeStateClaims()).tryStoredEndpoint(peer(), 1, 1L, 100L, fetchNodeState = false)
        assertTrue("removals:fp" in host.log)
        assertFalse(host.log.any { it.startsWith("cluster") })
    }
}
