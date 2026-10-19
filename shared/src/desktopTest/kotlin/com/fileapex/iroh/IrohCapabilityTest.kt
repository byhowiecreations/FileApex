package com.fileapex.iroh

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeProtocols
import com.fileapex.domain.peer.PeerNodeStateMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohCapabilityTest {
    private fun device(version: String, protocols: List<String>?) =
        PairedDeviceEntity(
            deviceId = "d", deviceName = "d", lastKnownIp = "10.0.0.1", port = 8080, publicKeyHash = "",
            rootPath = "/", clientVersion = version,
            supportedProtocolsJson = protocols?.let { PeerNodeStateMapper.encodeProtocols(it) } ?: "[]"
        )

    @Test
    fun aDeviceAdvertisingIrohOnOrOffIsReadAsSuch() {
        assertEquals(true, IrohCapability.advertised(PeerNodeStateMapper.encodeProtocols(PeerNodeProtocols.DEFAULT + IrohCapability.ON)))
        assertEquals(false, IrohCapability.advertised(PeerNodeStateMapper.encodeProtocols(PeerNodeProtocols.DEFAULT + IrohCapability.OFF)))
    }

    @Test
    fun noMarkerOrNoListMeansUnknown() {
        assertNull(IrohCapability.advertised(PeerNodeStateMapper.encodeProtocols(PeerNodeProtocols.DEFAULT)))
        assertNull(IrohCapability.advertised("[]"))
        assertNull(IrohCapability.advertised(""))
        assertNull(IrohCapability.advertised("not json"))
    }

    @Test
    fun theMarkerDecidesBeforeTheVersionDoes() {
        assertFalse(IrohRuntime.supportsIroh(device("0.19.0", PeerNodeProtocols.DEFAULT + IrohCapability.OFF)))
        assertTrue(IrohRuntime.supportsIroh(device("0.19.0", PeerNodeProtocols.DEFAULT + IrohCapability.ON)))
    }

    @Test
    fun withoutAMarkerTheVersionRuleStillApplies() {
        assertTrue(IrohRuntime.supportsIroh(device("0.19.0", PeerNodeProtocols.DEFAULT)))
        assertFalse(IrohRuntime.supportsIroh(device("0.18.1", PeerNodeProtocols.DEFAULT)))
        assertFalse(IrohRuntime.supportsIroh(device("docker", PeerNodeProtocols.DEFAULT + IrohCapability.ON)))
    }

    @Test
    fun ourOwnListCarriesOnlyTheMarkerThatMatchesTheState() {
        assertEquals(PeerNodeProtocols.DEFAULT + IrohCapability.ON, IrohCapability.ownProtocols(builtIn = true, enabled = true))
        assertEquals(PeerNodeProtocols.DEFAULT + IrohCapability.OFF, IrohCapability.ownProtocols(builtIn = true, enabled = false))
        assertEquals(PeerNodeProtocols.DEFAULT + IrohCapability.OFF, IrohCapability.ownProtocols(builtIn = false, enabled = true))
    }
}
