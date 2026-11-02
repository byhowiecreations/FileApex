package com.fileapex.data.device

import com.fileapex.di.ChainSeed
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.RoomTlsPinStore
import com.fileapex.di.RoomChainEffects
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.util.TimeUtils
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Direct contact and cloud seed may fill a profile of an active chain member. They never write membership and never
 * add a device the chain does not already hold as active.
 */
class ChainGatedIngestTest {
    private val pinA = "a".repeat(64)
    private val pinEvil = "c".repeat(64)
    private lateinit var db: FileApexDatabase
    private lateinit var repo: DeviceRepository
    private lateinit var effects: RoomChainEffects

    @Before
    fun setUp() {
        val file = File.createTempFile("ingest", ".db").also { it.deleteOnExit() }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        val pins = RoomTlsPinStore(db.tlsPinDao())
        repo = DeviceRepository(db.deviceDao(), { LocalDeviceRef("self", emptySet()) }, pins = pins)
        effects = RoomChainEffects(pins, db.membershipDao())
        seed = ChainSeed(db, selfId = "self")
        runBlocking { seed.start() }
    }

    @After
    fun close() = runCatching { db.close() }.let { }

    private lateinit var seed: ChainSeed

    private suspend fun member(id: String, state: String = "ACTIVE") {
        seed.admit(id, pinA)
        if (state != "ACTIVE") seed.remove(id)
    }

    private fun cloud(id: String, name: String = "Cloud") = PairedDeviceEntity(
        deviceId = id, deviceName = name, lastKnownIp = "192.168.1.80", port = 8082, publicKeyHash = "evil-hash",
        publicKey = "evil-key", rootPath = "/", clusterVersion = TimeUtils.now(), tlsPin = pinEvil
    )

    private fun state(id: String) = PeerNodeState(
        deviceId = id, deviceName = "Honor", ipAddress = "192.168.1.50", port = 8081, tlsPin = pinEvil, tlsPort = 8443,
        publicKey = "e2ee-$id", publicKeyHash = "evil-hash", membershipProtocol = 2,
        membershipVersion = TimeUtils.now()
    )

    @Test
    fun aPeerNodeStateFillsTheProfileOfAnActiveMemberAndNeverTouchesMembership() = runBlocking {
        member("phone")
        effects.ensureProfile("phone")
        repo.applyPeerNodeState(state("phone"), observedDirectly = true)
        val device = repo.getDevice("phone")!!
        assertEquals(listOf("Honor", "192.168.1.50", 8081), listOf(device.deviceName, device.lastKnownIp, device.port))
        val row = db.tlsPinDao().member("phone")!!
        assertEquals(listOf("ACTIVE", pinA, ""), listOf(row.state, row.tlsPin, row.keyHash))
    }

    @Test
    fun aCloudSeedFillsTheProfileOfAnActiveMemberAndNeverTouchesMembership() = runBlocking {
        member("phone")
        effects.ensureProfile("phone")
        repo.applyCloudSeed(cloud("phone", "FromCloud"), TimeUtils.now(), 2, TimeUtils.now())
        assertEquals("FromCloud", repo.getDevice("phone")!!.deviceName)
        val row = db.tlsPinDao().member("phone")!!
        assertEquals(listOf("ACTIVE", pinA, ""), listOf(row.state, row.tlsPin, row.keyHash))
    }

    @Test
    fun aDeviceTheChainDoesNotHoldAsActiveGetsNoRowFromNodeStateOrCloud() = runBlocking {
        repo.applyPeerNodeState(state("stranger"), observedDirectly = true)
        repo.applyCloudSeed(cloud("stranger"), TimeUtils.now(), 2, TimeUtils.now())
        assertNull(db.deviceDao().getDevice("stranger"))
        assertNull(db.membershipDao().get("stranger"))
    }

    @Test
    fun aRemovedMemberIsNotBroughtBackByNodeStateOrCloud() = runBlocking {
        member("phone", state = "REMOVED")
        effects.ensureProfile("phone")
        repo.applyPeerNodeState(state("phone").copy(isRemoved = false), observedDirectly = true)
        repo.applyCloudSeed(cloud("phone").copy(isRemoved = false), TimeUtils.now(), 2, TimeUtils.now())
        assertEquals("REMOVED", db.membershipDao().get("phone")!!.state)
        assertNull(repo.getDevice("phone"))
    }
}
