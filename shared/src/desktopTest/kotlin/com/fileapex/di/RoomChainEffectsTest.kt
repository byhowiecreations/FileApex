package com.fileapex.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.RoomTlsPinStore
import com.fileapex.data.db.getAllDevicesOnce
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.LocalDeviceRef
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoomChainEffectsTest {
    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)
    private lateinit var db: FileApexDatabase
    private val removals = mutableListOf<String>()
    private val tornDown = mutableListOf<String>()
    private lateinit var effects: RoomChainEffects

    @Before
    fun setUp() {
        val file = File.createTempFile("effects", ".db").also { it.deleteOnExit() }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        effects = RoomChainEffects(RoomTlsPinStore(db.tlsPinDao()), db.membershipDao(), { removals += it }, { tornDown += it })
        seed = ChainSeed(db)
        runBlocking { seed.start() }
    }

    @After
    fun close() = runCatching { db.close() }.let { }

    private lateinit var seed: ChainSeed

    private suspend fun seedSigned(id: String, pin: String) = seed.admit(id, pin)

    @Test
    fun knownPinIsTheStoredPinOrNull() = runBlocking {
        seedSigned("phone", pinA)
        assertEquals(pinA, effects.knownPin("phone"))
        assertNull(effects.knownPin("nobody"))
    }

    @Test
    fun theChainReplacesAMembersPinEvenWhenItIsSigned() = runBlocking {
        seedSigned("phone", pinA)
        effects.setPin("phone", pinB)
        assertEquals(pinB, effects.knownPin("phone"))
    }

    @Test
    fun aUserConfirmedPinStillWorksAfterTheChainWritesTheOldOneBack() = runBlocking {
        seedSigned("phone", pinA)
        effects.setPin("phone", pinB)
        effects.setPin("phone", pinA)
        val entry = RoomTlsPinStore(db.tlsPinDao()).entries().single { it.deviceId == "phone" }
        assertEquals(pinA, entry.pin)
        assertEquals(pinB, entry.alt)
    }

    @Test
    fun theHandledRemovalIsRememberedAcrossCalls() = runBlocking {
        assertEquals("", effects.handledRemoval())
        effects.markHandled("hash-1")
        assertEquals("hash-1", effects.handledRemoval())
    }

    @Test
    fun beingRemovedIsHandedToTheOneSelfWipePath() = runBlocking {
        effects.selfRemoved("mac", "hash-1")
        assertEquals(listOf("mac"), removals)
    }

    @Test
    fun aRemovalFromTheChainMarksTheMemberRemovedWithItsReasonAndActor() = runBlocking {
        seedSigned("phone", pinA)
        assertEquals(false, effects.knownRemoved("phone"))
        seed.remove("phone", project = false)
        com.fileapex.membership.ChainApplier("mac", effects).apply(seed.log.view())
        assertEquals(true, effects.knownRemoved("phone"))
        val row = db.membershipDao().get("phone")!!
        assertEquals(listOf("REMOVED", "user_removed", "mac"), listOf(row.state, row.eventReason, row.eventActor))
    }

    @Test
    fun aDeviceThisRosterNeverHeardOfIsRecordedAsRemoved() = runBlocking {
        seed.admit("fx-new", pinB, trustPin = false)
        seed.leave("fx-new", project = false)
        com.fileapex.membership.ChainApplier("mac", effects).apply(seed.log.view())
        assertEquals(true, effects.knownRemoved("fx-new"))
        assertEquals("left_cluster", db.membershipDao().get("fx-new")!!.eventReason)
    }

    @Test
    fun aSignedRemovalAlreadyThereIsLeftAlone() = runBlocking {
        seedSigned("phone", pinA)
        seed.remove("phone")
        effects.markRemoved("phone", "someone", "other", pinB)
        val row = db.membershipDao().get("phone")!!
        assertEquals(listOf("mac", "user_removed"), listOf(row.eventActor, row.eventReason))
    }

    @Test
    fun aMemberThisRosterNeverHeardOfIsKeptWithItsPinAsRevoked() = runBlocking {
        seed.admit("fx-new", pinB, trustPin = false)
        seed.remove("fx-new", project = false)
        effects.markRemoved("fx-new", "mac", "user_removed", pinB)
        val entry = RoomTlsPinStore(db.tlsPinDao()).entries().single { it.deviceId == "fx-new" }
        assertEquals(true, entry.revoked)
    }

    @Test
    fun aRemovedMembersPinIsKeptWhenTheRowAlreadyHadOne() = runBlocking {
        seedSigned("phone", pinA)
        seed.remove("phone", project = false)
        effects.markRemoved("phone", "mac", "user_removed", pinA)
        assertEquals(pinA, db.tlsPinDao().member("phone")!!.tlsPin)
    }

    @Test
    fun aRemovalTearsDownTheDevicesSessionPresenceAndCloudLink() = runBlocking {
        effects.markRemoved("phone", "mac", "user_removed", pinA)
        assertEquals(listOf("phone"), tornDown)
    }

    @Test
    fun aRevokedPinIsNotTheKnownPin() = runBlocking {
        seedSigned("phone", pinA)
        seed.remove("phone")
        assertNull(effects.knownPin("phone"))
    }

    @Test
    fun aReAdmittedDevicesNewPinReactivatesItsRemovedRow() = runBlocking {
        seedSigned("phone", pinA)
        seed.remove("phone")
        effects.setPin("phone", pinB)
        val row = db.tlsPinDao().member("phone")!!
        assertEquals(listOf("ACTIVE", pinB), listOf(row.state, row.tlsPin))
        assertEquals(pinB, effects.knownPin("phone"))
    }

    @Test
    fun aChainMemberWithNoProfileGetsAStubThatShowsInTheDeviceListWithItsChainPin() = runBlocking {
        seedSigned("phone", pinA)
        assertNull(db.deviceDao().getDevice("phone"))
        effects.ensureProfile("phone")
        val device = db.deviceDao().getDevice("phone")!!
        assertEquals(listOf(pinA, 8080, false), listOf(device.tlsPin, device.port, device.isRemoved))
        assertEquals(listOf("phone"), db.deviceDao().getAllDevicesOnce().map { it.deviceId })
    }

    @Test
    fun anExistingProfileIsNeverReplacedByTheStub() = runBlocking {
        seedSigned("phone", pinA)
        effects.ensureProfile("phone")
        db.deviceDao().renameDevice("phone", "Honor")
        effects.ensureProfile("phone")
        assertEquals("Honor", db.deviceDao().getDevice("phone")!!.deviceName)
    }

    @Test
    fun aRemovalInvalidatesTheTombstoneIndexSoTheRevokedGateSeesItAtOnce() = runBlocking {
        val pins = RoomTlsPinStore(db.tlsPinDao())
        val repo = DeviceRepository(db.deviceDao(), { LocalDeviceRef("mac", emptySet()) }, pins = pins)
        val wired = RoomChainEffects(pins, db.membershipDao(), invalidateTombstones = { repo.invalidateTombstones() })
        seedSigned("phone", pinA)
        wired.ensureProfile("phone")
        assertFalse(repo.isDeviceIdRevoked("phone"))
        seed.remove("phone", project = false)
        wired.markRemoved("phone", "mac", "user_removed", pinA)
        assertTrue(repo.isDeviceIdRevoked("phone"))
    }

    @Test
    fun applyingTheChainRebuildsTheRosterEvenWhenNoPinAndNoRemovalChanged() = runBlocking {
        seedSigned("phone", pinA)
        seed.remove("phone")
        assertTrue(RoomTlsPinStore(db.tlsPinDao()).record("phone", pinA, 8081, rejoin = true, userConfirmed = true))
        assertEquals("the hold keeps it active with no key until the admit lands", "", db.membershipDao().get("phone")!!.publicKey)
        val fresh = ChainSeed.Dev("phone")
        seed.admit("phone", pinA, key = fresh.key, trustPin = false, project = false)

        com.fileapex.membership.ChainApplier("mac", effects).apply(seed.log.view())

        assertEquals(fresh.key, db.membershipDao().get("phone")!!.publicKey)
    }
}
