package com.fileapex.di

import com.fileapex.data.db.MemberEffectiveEntity
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeProtocols
import com.fileapex.domain.peer.PeerNodeStateMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class ChainTrustedPeersTest {
    private val pin = "ab".repeat(32)

    private fun member(id: String, state: String = "ACTIVE") =
        MemberEffectiveEntity(deviceId = id, state = state, publicKey = "", eventActor = "", eventReason = "", tlsPin = pin, tlsPinAlt = "", tlsPort = 0, keyHash = "")

    private fun device(id: String, protocols: List<String>?) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "192.168.1.9", port = 8080, publicKeyHash = "", rootPath = "/",
        supportedProtocolsJson = protocols?.let { PeerNodeStateMapper.encodeProtocols(it) } ?: "[]"
    )

    @Test
    fun onlyAPeerKnownToLackTheChainMayBeAdmittedWithoutAKey() {
        val old = listOf(PeerNodeProtocols.IDENTITY_V1, PeerNodeProtocols.FILES_V1, PeerNodeProtocols.CLUSTER_V1)
        val peers = trustedPeersOf(
            members = listOf(member("new"), member("old"), member("unknown"), member("gone", "REMOVED")),
            devices = listOf(device("new", PeerNodeProtocols.DEFAULT), device("old", old), device("unknown", null)),
            selfId = "me"
        ).associateBy { it.deviceId }
        assertEquals(false, peers.getValue("new").mayBeLegacy)
        assertEquals(true, peers.getValue("old").mayBeLegacy)
        assertEquals(false, peers.getValue("unknown").mayBeLegacy)
        assertEquals(setOf("new", "old", "unknown"), peers.keys)
    }
}
