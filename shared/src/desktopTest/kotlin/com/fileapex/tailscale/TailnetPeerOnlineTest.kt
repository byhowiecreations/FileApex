package com.fileapex.tailscale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetPeerOnlineTest {
    private val id = "40fc26be-5337-4b37-a9d3-9fc359e60de3"
    private val name = "Moto Signature"
    private fun peer(host: String, online: Boolean) =
        TailscaleObservedPeer(hostName = host, dnsName = "$host.tail1234.ts.net.", ipv4 = "100.93.210.88", online = online)

    @Test
    fun anOnlinePeerThatMatchesTheDeviceIsOnline() {
        assertTrue(tailnetPeerIsOnline(listOf(peer("fileapex-moto-signature", online = true)), id, name))
    }

    @Test
    fun anOfflinePeerIsNotOnline() {
        assertFalse(tailnetPeerIsOnline(listOf(peer("fileapex-moto-signature", online = false)), id, name))
    }

    @Test
    fun aPeerMissingFromTheListIsNotOnline() {
        assertFalse(tailnetPeerIsOnline(listOf(peer("fileapex-nimo", online = true)), id, name))
        assertFalse(tailnetPeerIsOnline(emptyList(), id, name))
    }

    @Test
    fun anUnreadablePeerListLeavesTheDecisionToTheHealthCheck() {
        assertTrue(tailnetPeerIsOnline(null, id, name))
    }
}
