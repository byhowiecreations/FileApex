package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.CandidateOrigin
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.InMemoryDeviceDao
import com.fileapex.data.device.InMemoryPeerCandidateStore
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.presence.LanScanBackoff
import com.fileapex.network.FileApexClient
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerAdmissionScanGateTest {
    private val repo = DeviceRepository(
        deviceDao = InMemoryDeviceDao(),
        localDeviceProvider = { LocalDeviceRef("me", setOf("192.168.1.2:8080")) },
        candidates = InMemoryPeerCandidateStore()
    )

    private var clock = 1_000_000L
    private var networkKey = "192.168.1"
    private var scans = 0
    private var found: PeerNodeState? = null
    private val gate = LanScanBackoff(30 * 60 * 1000L, 4 * 60 * 60 * 1000L, now = { clock })

    private val admission = PeerAdmission(
        repository = repo,
        client = FileApexClient(),
        identityProvider = { LocalIdentity("me", "Me", "/") },
        fetchState = PeerStateFetcher { _, _ -> null },
        discover = { scans++; found },
        scanBackoff = gate,
        networkKey = { networkKey }
    )

    private fun entity(id: String) = PairedDeviceEntity(
        deviceId = id, deviceName = "Nimo", lastKnownIp = "192.168.1.50", port = 8080,
        publicKeyHash = "h-$id", publicKey = "key-$id", rootPath = "/", deviceMake = "HONOR",
        deviceModel = "MTN-NX1", clusterVersion = TimeUtils.now() - 60 * 60 * 1000L, lastSeenEpochMs = 0L
    )

    private fun answer(id: String) = PeerNodeState(
        deviceId = id, deviceName = "Nimo", ipAddress = "192.168.1.50", port = 8080,
        deviceMake = "HONOR", deviceModel = "MTN-NX1", publicKey = "key-$id", publicKeyHash = "h-$id",
        membershipVersion = ClusterClock.next(), membershipProtocol = ClusterClock.MEMBERSHIP_PROTOCOL,
        clusterVersion = ClusterClock.next()
    )

    private suspend fun stage(id: String) = repo.stageCandidate(entity(id), "introducer", CandidateOrigin.INTRODUCED)

    @Test
    fun aDeadCandidateIsScannedOnceThenHeldBack() = runBlocking {
        stage("dead")

        admission.verifyPending(force = true)
        admission.verifyPending(force = true)
        clock += 5 * 60 * 1000L
        admission.verifyPending(force = true)

        assertEquals(1, scans)
    }

    @Test
    fun aDeadCandidateIsScannedAgainWhenTheWaitIsOver() = runBlocking {
        stage("dead")
        admission.verifyPending(force = true)

        clock += 31 * 60 * 1000L
        admission.verifyPending(force = true)

        assertEquals(2, scans)
    }

    @Test
    fun aDifferentNetworkScansAtOnce() = runBlocking {
        stage("dead")
        admission.verifyPending(force = true)

        networkKey = "10.0.0"
        admission.verifyPending(force = true)

        assertEquals(2, scans)
    }

    @Test
    fun aCandidateTriedJustNowIsSkippedUnlessForced() = runBlocking {
        stage("dead")
        admission.verifyPending()
        val afterFirst = scans

        networkKey = "10.0.0"
        admission.verifyPending()
        assertEquals(afterFirst, scans)

        admission.verifyPending(force = true)
        assertEquals(afterFirst + 1, scans)
    }

    @Test
    fun aFoundCandidateClearsTheWait() = runBlocking {
        stage("nimo")
        admission.verifyPending(force = true)
        found = answer("nimo")

        clock += 31 * 60 * 1000L
        assertEquals(1, admission.verifyPending(force = true))
        assertEquals(true, gate.shouldScan("nimo", networkKey))
    }
}
