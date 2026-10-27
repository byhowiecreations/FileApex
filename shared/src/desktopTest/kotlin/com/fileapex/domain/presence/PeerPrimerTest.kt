package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerPrimerTest {
    private val peer = PairedDeviceEntity(
        deviceId = "P", deviceName = "P", lastKnownIp = "192.168.1.20", port = 8080, publicKeyHash = "", rootPath = "/data"
    )

    private class Host(
        var storedOk: Boolean = false,
        var usable: Boolean = true,
        var scanAllowed: Boolean = true,
        var discovered: PeerNodeState? = null
    ) : PrimerHost {
        val log = mutableListOf<String>()
        override suspend fun liveDevice(deviceId: String): PairedDeviceEntity? = null
        override fun hasUsableEndpoint(peer: PairedDeviceEntity) = usable
        override suspend fun tryStoredEndpoint(
            peer: PairedDeviceEntity, attempts: Int, retryMs: Long, timeoutMs: Long, fetchNodeState: Boolean
        ): Boolean {
            log += "stored"
            return storedOk
        }
        override fun shouldScan(deviceId: String, networkKey: String) = scanAllowed
        override fun recordScanFailure(deviceId: String, networkKey: String) { log += "scanFailure" }
        override suspend fun discover(target: PairedDeviceEntity, budgetMs: Long): PeerNodeState? {
            log += "discover:$budgetMs"
            return discovered
        }
        override suspend fun applyDiscovered(state: PeerNodeState, rosterDeviceId: String) { log += "apply:$rosterDeviceId" }
        override suspend fun markReachable(deviceId: String, discoveredId: String) { log += "reach:$deviceId" }
    }

    private fun state(id: String) = PeerNodeState(deviceId = id, deviceName = id, port = 8080)

    @Test
    fun storedEndpointThatAnswersEndsThePrime() = runBlocking {
        val host = Host(storedOk = true)
        assertTrue(PeerPrimer(host).primePeer(peer, includeDiscovery = true, allowPassiveWait = false, discoveryBudgetMs = 1L))
        assertEquals(listOf("stored"), host.log)
    }

    @Test
    fun scanBackoffBlocksDiscoveryUnlessBypassed() = runBlocking {
        val blocked = Host(scanAllowed = false)
        assertFalse(PeerPrimer(blocked).primePeer(peer, includeDiscovery = true, allowPassiveWait = false, discoveryBudgetMs = 1L))
        assertEquals(listOf("stored"), blocked.log)

        val bypassed = Host(scanAllowed = false, discovered = state("P"))
        assertTrue(
            PeerPrimer(bypassed).primePeer(
                peer, includeDiscovery = true, allowPassiveWait = false, discoveryBudgetMs = 1L, bypassScanBackoff = true
            )
        )
        assertEquals(listOf("stored", "discover:${LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS}", "apply:P", "reach:P"), bypassed.log)
    }

    @Test
    fun anotherDeviceFoundByTheScanIsAFailureAndStaysUnreached() = runBlocking {
        val host = Host(usable = true, discovered = state("OTHER"))
        val ok = PeerPrimer(host).primePeer(peer, includeDiscovery = true, allowPassiveWait = false, discoveryBudgetMs = 1L)
        assertTrue(ok)
        assertEquals(listOf("stored", "discover:${LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS}", "scanFailure"), host.log)
    }

    @Test
    fun noDiscoveryWhenNotAskedAndTheEndpointIsUsable() = runBlocking {
        val host = Host()
        assertTrue(PeerPrimer(host).primePeer(peer, includeDiscovery = false, allowPassiveWait = false, discoveryBudgetMs = 1L))
        assertEquals(listOf("stored"), host.log)
    }
}
