package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.InMemoryDeviceDao
import com.fileapex.data.device.InMemoryPeerCandidateStore
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.network.FileApexClient
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Same hardware under a new id is the old device after a reinstall: the old row goes only if it no longer answers as itself. */
class PeerAdmissionHardwareTest {
    private val dao = InMemoryDeviceDao()
    private val repo = DeviceRepository(
        deviceDao = dao,
        localDeviceProvider = { LocalDeviceRef("me", setOf("192.168.1.2:8080")) },
        candidates = InMemoryPeerCandidateStore()
    )
    private val network = mutableMapOf<String, PeerNodeState?>()
    private val superseded = mutableListOf<PairedDeviceEntity>()
    private val admission = PeerAdmission(
        repository = repo,
        client = FileApexClient(),
        identityProvider = { LocalIdentity("me", "Me", "/") },
        onSuperseded = { superseded += it },
        fetchState = PeerStateFetcher { host, _ -> network[host] },
        discover = { null }
    )

    private suspend fun pair(id: String, ip: String, hash: String) {
        repo.adoptFromPairing(
            PairedDeviceEntity(
                deviceId = id, deviceName = "Honor Magic V5", lastKnownIp = ip, port = 8080, publicKeyHash = "h-$id",
                rootPath = "/", hardwareHash = hash, clusterVersion = TimeUtils.now()
            )
        )
    }

    private fun newState(hash: String) = PeerNodeState(
        deviceId = "new-id", deviceName = "Honor Magic V5", ipAddress = "192.168.1.60", port = 8080, hardwareHash = hash
    )

    @Test
    fun anOldRowThatNoLongerAnswersIsReplaced() = runBlocking {
        pair("old-id", "192.168.1.50", "h1")
        network["192.168.1.50"] = null
        assertEquals(1, admission.supersedeHardwareTwins(newState("h1")))
        assertNull(repo.getDevice("old-id")?.takeUnless { it.isRemoved })
        assertEquals("old-id", superseded.single().deviceId)
    }

    @Test
    fun anOldRowThatStillAnswersAsItselfIsKept() = runBlocking {
        pair("old-id", "192.168.1.50", "h1")
        network["192.168.1.50"] = PeerNodeState(deviceId = "old-id", deviceName = "x", port = 8080)
        assertEquals(0, admission.supersedeHardwareTwins(newState("h1")))
        assertNotNull(repo.getDevice("old-id")?.takeUnless { it.isRemoved })
        assertEquals(0, superseded.size)
    }

    @Test
    fun differentHardwareIsNeverATwin() = runBlocking {
        pair("old-id", "192.168.1.50", "h1")
        network["192.168.1.50"] = null
        assertEquals(0, admission.supersedeHardwareTwins(newState("h2")))
        assertNotNull(repo.getDevice("old-id")?.takeUnless { it.isRemoved })
    }

    @Test
    fun noHashMeansNothingIsReplaced() = runBlocking {
        pair("old-id", "192.168.1.50", "h1")
        network["192.168.1.50"] = null
        assertEquals(0, admission.supersedeHardwareTwins(newState("")))
        assertNotNull(repo.getDevice("old-id")?.takeUnless { it.isRemoved })
    }

    @Test
    fun theUserCanReplaceARowAtPairingWithoutAnyReachabilityCheck() = runBlocking {
        pair("old-id", "192.168.1.50", "h1")
        assertEquals(true, admission.supersede("old-id"))
        assertNull(repo.getDevice("old-id")?.takeUnless { it.isRemoved })
        assertEquals("old-id", superseded.single().deviceId)
    }
}
