package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.CANDIDATE_TTL_MS
import com.fileapex.data.device.CandidateOrigin
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.InMemoryDeviceDao
import com.fileapex.data.device.InMemoryPeerCandidateStore
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.data.device.PeerCandidate
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.network.FileApexClient
import com.fileapex.security.IdentityBinding
import com.fileapex.util.TimeUtils
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerAdmissionTest {
    private val dao = InMemoryDeviceDao()
    private val store = InMemoryPeerCandidateStore()
    private val repo = DeviceRepository(
        deviceDao = dao,
        localDeviceProvider = { LocalDeviceRef("me", setOf("192.168.1.2:8080")) },
        candidates = store
    )

    /** What each address answers with; null means silence. */
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

    private fun version() = TimeUtils.now()

    private fun entity(
        id: String,
        ip: String = "192.168.1.50",
        key: String = "key-$id",
        lastSeen: Long = 0L,
        clusterVersion: Long = version() - 60 * 60 * 1000L,
        model: String = "MTN-NX1"
    ) = PairedDeviceEntity(
        deviceId = id, deviceName = "Honor X9d", lastKnownIp = ip, port = 8080,
        publicKeyHash = "h-$id", publicKey = key, rootPath = "/storage/emulated/0",
        deviceMake = "HONOR", deviceModel = model, clusterVersion = clusterVersion, lastSeenEpochMs = lastSeen
    )

    private fun answer(
        id: String,
        key: String = "key-$id",
        protocol: Int = ClusterClock.MEMBERSHIP_PROTOCOL,
        hash: String = "h-$id"
    ) = PeerNodeState(
        deviceId = id, deviceName = "Honor X9d", ipAddress = "192.168.1.50", port = 8080,
        deviceMake = "HONOR", deviceModel = "MTN-NX1", publicKey = key, publicKeyHash = hash,
        membershipVersion = ClusterClock.next(), membershipProtocol = protocol, clusterVersion = ClusterClock.next()
    )

    private suspend fun stage(device: PairedDeviceEntity, origin: CandidateOrigin = CandidateOrigin.INTRODUCED) =
        repo.stageCandidate(device, "introducer", origin)

    // ---- the roster only grows from a direct contact ----

    @Test
    fun aReturnedRosterNeverAddsAPeer() = runBlocking {
        assertFalse(repo.reconcileRemotePeer(entity("ghost")))
        assertTrue(repo.listDevices().isEmpty())
        assertTrue(repo.listCandidates().isEmpty())
    }

    @Test
    fun aRelayedNodeStateNeverAddsAPeer() = runBlocking {
        assertFalse(repo.applyPeerNodeState(answer("ghost")))
        assertTrue(repo.listDevices().isEmpty())
    }

    @Test
    fun aRosterIntroOnlyStagesACandidate() = runBlocking {
        assertFalse(repo.adoptFromRosterIntro(entity("late"), "someone", CandidateOrigin.TRANSPORT))
        assertTrue(repo.listDevices().isEmpty())
        assertEquals("late", repo.listCandidates().single().deviceId)
    }

    @Test
    fun aCloudDocumentOnlyStagesACandidate() = runBlocking {
        val outcome = repo.applyCloudSeed(entity("cloud"), version(), ClusterClock.MEMBERSHIP_PROTOCOL, version())
        assertEquals(com.fileapex.data.device.CloudSeedOutcome.Skipped, outcome)
        assertTrue(repo.listDevices().isEmpty())
        assertEquals(CandidateOrigin.CLOUD, repo.listCandidates().single().origin)
    }

    @Test
    fun aKnownPeerStillRefreshesFromItsNodeState() = runBlocking {
        repo.adoptFromPairing(entity("known", lastSeen = 5L))
        assertTrue(repo.applyPeerNodeState(answer("known").copy(deviceName = "Renamed Honor"), observedDirectly = true))
        assertEquals("Renamed Honor", repo.getDevice("known")!!.deviceName)
    }

    // ---- candidates ----

    @Test
    fun aCandidateIsNotListedAndKeepsItsFirstDeadline() = runBlocking {
        assertTrue(stage(entity("x")))
        val first = store.get("x")!!
        assertFalse(stage(entity("x", ip = "192.168.1.51")))
        val second = store.get("x")!!
        assertEquals(first.expiresAtEpochMs, second.expiresAtEpochMs)
        assertEquals(CANDIDATE_TTL_MS, 2L * 60 * 60 * 1000)
        assertTrue(repo.listDevices().isEmpty())
    }

    @Test
    fun anExpiredCandidateIsDroppedAndNotStagedAgainRightAway() = runBlocking {
        store.put(PeerCandidate(entity("old"), "introducer", CandidateOrigin.INTRODUCED, 1L, 2L))
        assertEquals(1, repo.expireCandidates())
        assertNull(store.get("old"))
        assertFalse(stage(entity("old")))
    }

    @Test
    fun aRemovedPeerCannotBeStagedUnlessTheIntroductionIsNewer() = runBlocking {
        repo.adoptFromPairing(entity("gone", lastSeen = 5L))
        repo.removePermanently("gone")
        assertFalse(stage(entity("gone", clusterVersion = 1L)))
        val tombstone = dao.removed.getValue("gone").removedAtEpochMs
        assertTrue(stage(entity("gone", clusterVersion = tombstone + 1)))
    }

    @Test
    fun anIntroductionOffTheClusterClockIsRefused() = runBlocking {
        assertFalse(stage(entity("far", clusterVersion = version() + 24 * 60 * 60 * 1000L)))
        assertFalse(stage(entity("zero", clusterVersion = 0L)))
    }

    // ---- verifying a candidate ----

    @Test
    fun aCandidateThatAnswersAsItselfIsPromotedWithPresenceFromThisContact() = runBlocking {
        stage(entity("new"))
        network["192.168.1.50"] = answer("new")
        assertEquals(1, admission.verifyPending())
        val row = repo.getDevice("new")!!
        assertTrue(row.lastSeenEpochMs > 0L)
        assertTrue(PeerTrustRules.reachedDirectly(row))
        assertTrue(repo.listCandidates().isEmpty())
    }

    @Test
    fun anOutdatedCopyAnswersAsAnotherDeviceAndIsNeverAdded() = runBlocking {
        stage(entity("ghost"))
        network["192.168.1.50"] = answer("real")
        assertEquals(0, admission.verifyPending())
        assertNull(repo.getDevice("ghost"))
        assertTrue(repo.listCandidates().isEmpty())
        assertFalse(stage(entity("ghost")))
    }

    @Test
    fun aCandidateStaysPendingWhileItIsUnreachable() = runBlocking {
        stage(entity("away"))
        assertEquals(0, admission.verifyPending())
        val kept = store.get("away")!!
        assertEquals(1, kept.attempts)
        assertTrue(repo.listDevices().isEmpty())
    }

    @Test
    fun aDifferentKeyThanTheIntroductionNamedIsRefused() = runBlocking {
        stage(entity("spoof"))
        network["192.168.1.50"] = answer("spoof", key = "attackers-key")
        admission.verifyPending()
        assertNull(repo.getDevice("spoof"))
        assertNull(store.get("spoof"))
    }

    @Test
    fun aDerivedIdMustMatchTheKeyItClaims() = runBlocking {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val other = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 9).toByte() })
        val id = IdentityBinding.deriveDeviceId(key)
        stage(entity(id, key = ""))
        network["192.168.1.50"] = answer(id, key = other)
        admission.verifyPending()
        assertNull(repo.getDevice(id))

        stage(entity(id, key = ""))
        repo.dropCandidate(id)
        // Second attempt after the drop: the id is remembered as refused, so nothing is staged.
        assertNull(store.get(id))
    }

    @Test
    fun anIntroducedPeerNotOnTheClusterClockIsRefused() = runBlocking {
        stage(entity("legacy"))
        network["192.168.1.50"] = answer("legacy", protocol = 0)
        admission.verifyPending()
        assertNull(repo.getDevice("legacy"))
    }

    @Test
    fun aSecondRowOfTheSameKeyReplacesTheCopyThatNoLongerAnswers() = runBlocking {
        repo.adoptFromPairing(entity("old-install", lastSeen = 10L).copy(publicKeyHash = "same", publicKey = "same-key"))
        stage(entity("new-install").copy(publicKeyHash = "same", publicKey = "same-key"))
        network["192.168.1.50"] = answer("new-install", key = "same-key", hash = "same")
        assertEquals(1, admission.verifyPending())
        assertNotNull(repo.getDevice("new-install"))
        assertNull(repo.listDevices().firstOrNull { it.deviceId == "old-install" })
        assertTrue(superseded.isEmpty())
    }

    @Test
    fun aSecondRowIsRefusedWhileTheOriginalKeyStillAnswers() = runBlocking {
        repo.adoptFromPairing(entity("twin", lastSeen = 10L).copy(port = 8081, publicKeyHash = "same", publicKey = "same-key"))
        stage(entity("copy").copy(publicKeyHash = "same", publicKey = "same-key"))
        val byPort = PeerAdmission(
            repository = repo, client = FileApexClient(),
            identityProvider = { LocalIdentity("me", "Me", "/") },
            fetchState = PeerStateFetcher { _, port ->
                if (port == 8081) answer("twin", key = "same-key", hash = "same") else answer("copy", key = "same-key", hash = "same")
            },
            discover = { null }
        )
        assertEquals(0, byPort.verifyPending())
        assertNull(repo.getDevice("copy"))
        assertNotNull(repo.getDevice("twin"))
        assertTrue(superseded.isEmpty())
    }

    // ---- ghosts found by the health check and the startup check ----

    @Test
    fun aRowNeverReachedDirectlyGoesBackToUnverifiedWhenItsAddressAnswersAsSomeoneElse() = runBlocking {
        repo.adoptFromPairing(entity("ghost", lastSeen = 0L))
        assertTrue(repo.recordEndpointMismatch("ghost", "192.168.1.50", "real"))
        assertTrue(repo.listDevices().isEmpty())
        assertNotNull(store.get("ghost"))
    }

    @Test
    fun aRowReachedBeforeOnlyLosesTheStaleAddress() = runBlocking {
        repo.adoptFromPairing(entity("moved", lastSeen = 5L))
        assertTrue(repo.recordEndpointMismatch("moved", "192.168.1.50", "someone"))
        assertEquals("", repo.getDevice("moved")!!.lastKnownIp)
        assertNull(store.get("moved"))
    }

    @Test
    fun startupMovesRowsNeverReachedDirectlyAndKeepsTheRest() = runBlocking {
        dao.devices["seen"] = entity("seen", lastSeen = 5L)
        dao.devices["hearsay"] = entity("hearsay", lastSeen = 0L)
        dao.devices["just-paired"] = entity("just-paired", lastSeen = 0L, clusterVersion = version())
        assertEquals(1, repo.demoteUnverifiedRows())
        assertEquals(setOf("seen", "just-paired"), repo.listDevices().map { it.deviceId }.toSet())
        assertEquals("hearsay", repo.listCandidates().single().deviceId)
        assertEquals(CandidateOrigin.LOCAL, repo.listCandidates().single().origin)
    }

    // ---- what this device is willing to vouch for ----

    @Test
    fun onlyDevicesReachedDirectlyAndWithoutATwinMayBeIntroduced() {
        val seen = entity("seen", ip = "192.168.1.60", lastSeen = 5L)
        val hearsay = entity("hearsay", ip = "192.168.1.61", lastSeen = 0L)
        val twinA = entity("twin-a", ip = "192.168.1.62", lastSeen = 5L).copy(publicKeyHash = "same", publicKey = "same-key")
        val twinB = entity("twin-b", ip = "192.168.1.70", lastSeen = 5L).copy(publicKeyHash = "same", publicKey = "same-key")
        val neighbor = entity("neighbor", ip = "192.168.1.62", lastSeen = 5L)
        val roster = listOf(seen, hearsay, twinA, twinB, neighbor)
        assertTrue(PeerTrustRules.mayIntroduce(seen, roster))
        assertTrue(PeerTrustRules.mayIntroduce(neighbor, roster))
        assertFalse(PeerTrustRules.mayIntroduce(hearsay, roster))
        assertFalse(PeerTrustRules.mayIntroduce(twinA, roster))
        assertFalse(PeerTrustRules.mayIntroduce(seen.copy(clusterVersion = 0L), roster))
    }
}
