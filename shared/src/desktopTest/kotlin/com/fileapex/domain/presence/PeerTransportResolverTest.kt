package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.tailscale.TailscaleObservedPeer
import com.fileapex.tailscale.TailscalePhase
import com.fileapex.tailscale.parseTailnetPeers
import com.fileapex.tailscale.tailnetPeerMatchesDevice
import com.fileapex.tailscale.tailscaleHostname
import com.fileapex.tailscale.tailscaleNodeHostname
import com.fileapex.tailscale.usesUserspaceDial
import com.fileapex.tailscale.verifiedTailnetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTransportResolverTest {
    @Test
    fun disabledNodeUsesTheLanAddress() {
        val peer = paired(lastKnownIp = "192.168.1.20", tailnetIpv4 = "100.64.1.2")
        val resolved = resolvePeerEndpoint(peer, tailnetUp = false)
        assertEquals("192.168.1.20", resolved?.host)
        assertFalse(resolved!!.tailnet)
    }

    @Test
    fun upNodeUsesVerifiedTailnetAddress() {
        val peer = paired(lastKnownIp = "192.168.1.20", tailnetIpv4 = "100.64.1.2")
        val resolved = resolvePeerEndpoint(peer, tailnetUp = true)
        assertEquals("100.64.1.2", resolved?.host)
        assertTrue(resolved!!.tailnet)
    }

    @Test
    fun upNodeWithoutTailnetAddressStaysOnLan() {
        val peer = paired(lastKnownIp = "10.0.0.8", tailnetIpv4 = "")
        val resolved = resolvePeerEndpoint(peer, tailnetUp = true)
        assertEquals("10.0.0.8", resolved?.host)
        assertFalse(resolved!!.tailnet)
    }

    @Test
    fun disabledPeerNeverSelectsTheUserspaceDial() {
        val peer = paired(lastKnownIp = "192.168.1.20", tailnetIpv4 = "100.64.1.2")
        val resolved = resolvePeerEndpoint(peer, tailnetUp = false)
        assertEquals("192.168.1.20", resolved?.host)
        assertFalse(resolved!!.tailnet)
        for (phase in TailscalePhase.entries) {
            assertFalse(usesUserspaceDial(resolved.host, phase))
        }
    }

    @Test
    fun enabledPeerUsesTheDialOnlyWhileTheNodeIsUp() {
        val peer = paired(lastKnownIp = "192.168.1.20", tailnetIpv4 = "100.64.1.2")
        val resolved = resolvePeerEndpoint(peer, tailnetUp = true)
        assertEquals("100.64.1.2", resolved?.host)
        assertTrue(usesUserspaceDial(resolved!!.host, TailscalePhase.Up))
        for (phase in TailscalePhase.entries) {
            if (phase == TailscalePhase.Up) continue
            assertFalse(phase.name, usesUserspaceDial(resolved.host, phase))
        }
    }

    @Test
    fun enabledPeerWithoutATailnetAddressStaysOnTheLanSocket() {
        val resolved = resolvePeerEndpoint(paired(lastKnownIp = "10.0.0.8"), tailnetUp = true)
        assertEquals("10.0.0.8", resolved?.host)
        assertFalse(usesUserspaceDial(resolved!!.host, TailscalePhase.Up))
    }

    @Test
    fun failedTailnetDialRetriesTheLanAddress() {
        val peer = paired(lastKnownIp = "192.168.1.20", tailnetIpv4 = "100.64.1.2")
        val retry = resolvePeerEndpoint(peer, tailnetUp = false)
        assertEquals("192.168.1.20", retry?.host)
        assertFalse(retry!!.tailnet)
        assertFalse(usesUserspaceDial(retry.host, TailscalePhase.Up))
    }

    @Test
    fun publicAndLoopbackLanAddressesAreNotChosen() {
        assertNull(resolvePeerEndpoint(paired(lastKnownIp = "8.8.8.8"), tailnetUp = false))
        assertNull(resolvePeerEndpoint(paired(lastKnownIp = "127.0.0.1"), tailnetUp = false))
        assertNull(resolvePeerEndpoint(paired(lastKnownIp = ""), tailnetUp = true))
    }

    @Test
    fun identityMustMatchThePairedDevice() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        val host = tailscaleNodeHostname(id)
        val observed = TailscaleObservedPeer(
            hostName = host,
            dnsName = "$host.tail.ts.net.",
            ipv4 = "100.64.9.9",
            online = true
        )
        assertTrue(tailnetPeerMatchesDevice(observed, id, "Desk"))
        assertEquals(host, verifiedTailnetAddress(id, "Desk", observed, id)?.hostname)
        assertNull(verifiedTailnetAddress(id, "Desk", observed, "other-device"))
        assertNull(
            verifiedTailnetAddress(
                id,
                "Desk",
                observed.copy(hostName = "someone-else", dnsName = "someone-else.tail.ts.net."),
                id
            )
        )
        assertNull(verifiedTailnetAddress(id, "Desk", observed.copy(ipv4 = "192.168.1.1"), id))
        val named = tailscaleHostname("Moto Signature")
        val byName = observed.copy(hostName = named, dnsName = "$named.tail.ts.net.")
        assertTrue(tailnetPeerMatchesDevice(byName, id, "Moto Signature"))
        assertEquals(named, verifiedTailnetAddress(id, "Moto Signature", byName, id)?.hostname)
        assertTrue(
            tailnetPeerMatchesDevice(
                byName.copy(hostName = "$named-2", dnsName = "$named-2.tail.ts.net."),
                id,
                "Moto Signature"
            )
        )
        assertFalse(
            tailnetPeerMatchesDevice(
                byName.copy(hostName = "$named-pro", dnsName = "$named-pro.tail.ts.net."),
                id,
                "Moto Signature"
            )
        )
    }

    @Test
    fun peerJsonDropsUnknownFields() {
        val peers = parseTailnetPeers(
            """[{"hostName":"fileapex-abc","dnsName":"fileapex-abc.tail.ts.net.","ipv4":"100.64.1.1","online":true,"nodeKey":"secret"}]"""
        )
        assertEquals(1, peers.size)
        assertEquals("100.64.1.1", peers[0].ipv4)
        assertEquals(emptyList<TailscaleObservedPeer>(), parseTailnetPeers("not-json"))
    }

    private fun paired(lastKnownIp: String, tailnetIpv4: String = "") = PairedDeviceEntity(
        deviceId = "550e8400-e29b-41d4-a716-446655440000",
        deviceName = "Desk",
        lastKnownIp = lastKnownIp,
        port = 8080,
        publicKeyHash = "hash",
        rootPath = "/",
        tailnetIpv4 = tailnetIpv4,
        tailnetHostname = if (tailnetIpv4.isBlank()) "" else "fileapex-550e8400e29b41d4a716446655440000"
    )
}
