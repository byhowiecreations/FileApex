package com.fileapex.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.RoomTlsPinStore
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.domain.peer.PeerNodeState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Profiles that arrive with the chain fill in names and addresses; they never decide who is a member or which pin is trusted. */
class RoomChainProfilesTest {
    private val pinA = "a".repeat(64)
    private val pinEvil = "c".repeat(64)
    private lateinit var db: FileApexDatabase
    private lateinit var repo: DeviceRepository
    private lateinit var effects: RoomChainEffects
    private lateinit var profiles: RoomChainProfiles

    private fun state(id: String, name: String = "Honor", ip: String = "192.168.1.50") = PeerNodeState(
        deviceId = id, deviceName = name, ipAddress = ip, port = 8081, tlsPort = 8443, tlsPin = pinEvil, publicKey = "e2ee-$id", publicKeyHash = "evil-hash"
    )

    @Before
    fun setUp() {
        val file = File.createTempFile("profiles", ".db").also { it.deleteOnExit() }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        val pins = RoomTlsPinStore(db.tlsPinDao())
        repo = DeviceRepository(db.deviceDao(), { LocalDeviceRef("self", emptySet()) }, pins = pins)
        effects = RoomChainEffects(pins, db.membershipDao())
        profiles = RoomChainProfiles(repo, "self") { PeerNodeState(deviceId = "self", deviceName = "Mac", port = 8080) }
        seed = ChainSeed(db, selfId = "self")
        runBlocking { seed.start() }
    }

    @After
    fun close() = runCatching { db.close() }.let { }

    private lateinit var seed: ChainSeed

    private suspend fun member(id: String, state: String = "ACTIVE", pin: String = pinA) {
        seed.admit(id, pin)
        if (state != "ACTIVE") seed.remove(id)
    }

    @Test
    fun aProfileFillsNameEndpointAndTlsPortOfAnActiveMember() = runBlocking {
        member("phone")
        effects.ensureProfile("phone")
        profiles.incoming(listOf(state("phone")), setOf("phone"), from = "phone")
        val device = repo.getDevice("phone")!!
        assertEquals(listOf("Honor", "192.168.1.50", 8081, 8443, "e2ee-phone"), listOf(device.deviceName, device.lastKnownIp, device.port, device.tlsPort, device.publicKey))
    }

    @Test
    fun aProfileNeverChangesTheMembersPinOrState() = runBlocking {
        member("phone")
        effects.ensureProfile("phone")
        profiles.incoming(listOf(state("phone").copy(isRemoved = true)), setOf("phone"), from = "phone")
        val row = db.tlsPinDao().member("phone")!!
        assertEquals(listOf("ACTIVE", pinA, ""), listOf(row.state, row.tlsPin, row.keyHash))
    }

    @Test
    fun aProfileDoesNotBringARemovedMemberBack() = runBlocking {
        member("phone", state = "REMOVED")
        effects.ensureProfile("phone")
        profiles.incoming(listOf(state("phone")), setOf("phone"), from = "phone")
        assertEquals("REMOVED", db.membershipDao().get("phone")!!.state)
        assertNull(repo.getDevice("phone"))
    }

    @Test
    fun aDeviceTheChainDoesNotHoldAsActiveGetsNoRow() = runBlocking {
        profiles.incoming(listOf(state("stranger"), state("self")), setOf("phone"), from = "phone")
        assertNull(db.deviceDao().getDevice("stranger"))
        assertNull(db.deviceDao().getDevice("self"))
    }

    @Test
    fun theOutgoingProfilesAreThisDeviceFirstThenTheActivePeersWithNoPresence() = runBlocking {
        member("phone")
        effects.ensureProfile("phone")
        db.deviceDao().touchLastSeenEpoch("phone", 12345L)
        val out = profiles.outgoing()
        assertEquals(listOf("self", "phone"), out.map { it.deviceId })
        assertEquals(0L, out.last().lastSeenTimestamp)
    }

    private fun named(id: String, name: String) = state(id, name).copy(deviceMake = "Motorola", deviceModel = "Signature")

    @Test
    fun aRelayedProfileNeverChangesANameThisDeviceAlreadyHolds() = runBlocking {
        member("phone")
        effects.ensureProfile("phone")
        profiles.incoming(listOf(named("phone", "Signature 2")), setOf("phone"), from = "phone")
        profiles.incoming(listOf(named("phone", "Signature")), setOf("phone"), from = "mac")
        assertEquals("Signature 2", repo.getDevice("phone")!!.deviceName)
    }

    @Test
    fun theDevicesOwnProfileChangesItsName() = runBlocking {
        member("phone")
        effects.ensureProfile("phone")
        profiles.incoming(listOf(named("phone", "Signature")), setOf("phone"), from = "phone")
        profiles.incoming(listOf(named("phone", "Signature 2")), setOf("phone"), from = "phone")
        assertEquals("Signature 2", repo.getDevice("phone")!!.deviceName)
    }

    @Test
    fun aRelayedProfileFillsANameThatIsStillAPlaceholder() = runBlocking {
        member("phone")
        effects.ensureProfile("phone")
        assertEquals("Paired Device", repo.getDevice("phone")!!.deviceName)
        profiles.incoming(listOf(named("phone", "Signature")), setOf("phone"), from = "mac")
        assertEquals("Signature", repo.getDevice("phone")!!.deviceName)
    }
}
