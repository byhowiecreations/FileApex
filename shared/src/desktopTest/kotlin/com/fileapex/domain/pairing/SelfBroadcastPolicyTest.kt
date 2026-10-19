package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SelfBroadcastPolicyTest {
    private fun peer(id: String, ip: String, removed: Boolean = false) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = ip, port = 8080, publicKeyHash = "",
        rootPath = "/", clientVersion = "0.19.0", clientVersionCode = 206, isRemoved = removed
    )

    @Test
    fun onlyOnlinePeersAreTargeted() {
        val peers = listOf(peer("a", "172.16.16.10"), peer("b", "172.16.16.11"), peer("c", "172.16.16.12"))
        val targets = SelfBroadcastPolicy.targets(peers, onlineIds = setOf("a", "c"))
        assertEquals(listOf("a", "c"), targets.map { it.deviceId })
    }

    @Test
    fun nobodyIsTargetedWhenNoPeerIsOnline() {
        val peers = listOf(peer("a", "172.16.16.10"), peer("b", "172.16.16.11"))
        assertEquals(emptyList<PairedDeviceEntity>(), SelfBroadcastPolicy.targets(peers, onlineIds = emptySet()))
    }

    @Test
    fun onlinePeerWithoutAUsableLanAddressIsSkipped() {
        val peers = listOf(peer("a", ""), peer("b", "169.254.3.4"), peer("c", "172.16.16.12"))
        val targets = SelfBroadcastPolicy.targets(peers, onlineIds = setOf("a", "b", "c"))
        assertEquals(listOf("c"), targets.map { it.deviceId })
    }

    @Test
    fun removedPeerIsSkippedEvenWhenOnline() {
        val peers = listOf(peer("a", "172.16.16.10", removed = true), peer("b", "172.16.16.11"))
        val targets = SelfBroadcastPolicy.targets(peers, onlineIds = setOf("a", "b"))
        assertEquals(listOf("b"), targets.map { it.deviceId })
    }
}
