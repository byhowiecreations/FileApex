package com.fileapex.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The device list is a view over `member_profiles` and `members`; these are the rules the old `paired_devices` table gave. */
class DeviceDaoTest {
    private lateinit var db: FileApexDatabase
    private val dao get() = db.deviceDao()
    private val members get() = db.membershipDao()
    private val pin = "a".repeat(64)

    @Before
    fun setUp() {
        val file = File.createTempFile("devices", ".db").also { it.deleteOnExit() }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).addCallback(MembershipSchemaCallback()).build()
        seed = com.fileapex.di.ChainSeed(db, selfId = "mac")
        runBlocking { seed.start() }
    }

    private lateinit var seed: com.fileapex.di.ChainSeed

    @After
    fun close() = runCatching { db.close() }.let { }

    private fun device(id: String, name: String = "Honor", version: Long = 0L) = PairedDeviceEntity(
        deviceId = id, deviceName = name, lastKnownIp = "192.168.1.7", port = 8080, publicKeyHash = "hash-$id", publicKey = "key-$id",
        e2eeEnabled = true, rootPath = "/root", clientVersion = "0.19.9", clientVersionCode = 227, platform = "android", os = "android",
        deviceMake = "Honor", deviceModel = "Magic V5", supportedProtocolsJson = "[1]", lastSeenEpochMs = 99L, cardPosX = 1.5f, cardPosY = 2.5f,
        cardSortOrder = 3, cardMenuOrder = "a,b", tilePosX = null, tilePosY = null, tileSortOrder = 4, tileMenuOrder = "c",
        clusterVersion = version, tailnetHostname = "host", tailnetIpv4 = "100.64.0.9", hardwareHash = "hw"
    )

    private suspend fun signedMember(id: String, state: String, stamp: Long) {
        if (seed.log.view().state.member(id) == null) seed.admit(id, pin)
        if (state == "REMOVED" && seed.log.view().state.member(id)?.active == true) seed.remove(id) else seed.refresh()
    }

    @Test
    fun anActiveDeviceComesBackWithEveryProfileField() = runBlocking {
        dao.upsertDevice(device("d1"))
        assertEquals(device("d1"), dao.getDevice("d1"))
    }

    @Test
    fun theListsSplitActiveAndRemovedDevices() = runBlocking {
        dao.upsertDevice(device("a"))
        dao.upsertDevice(device("gone").copy(isRemoved = true, removedAt = 60L, clusterVersion = 60L))
        assertEquals(listOf("a"), dao.getAllDevicesOnceUnordered().map { it.deviceId })
        assertEquals(listOf("gone"), dao.getTombstonedDevices().map { it.deviceId })
        assertEquals(listOf("a", "gone"), dao.getAllDevicesIncludingTombstonesUnordered().map { it.deviceId })
    }

    @Test
    fun aTombstoneBecomesARemovedMember() = runBlocking {
        dao.upsertDevice(device("gone").copy(isRemoved = true, removedAt = 60L, clusterVersion = 60L))
        val row = members.get("gone")!!
        assertEquals("REMOVED", row.state)
        val read = dao.getDevice("gone")!!
        assertTrue(read.isRemoved)
    }

    @Test
    fun aRemovalAlreadyThereKeepsItsActorAndReasonWhenATombstoneIsWrittenAfterIt() = runBlocking {
        signedMember("gone", "REMOVED", 50L)
        dao.upsertDevice(device("gone").copy(isRemoved = true, removedAt = 60L, clusterVersion = 60L))
        val row = members.get("gone")!!
        assertEquals("mac", row.eventActor)
        assertEquals("user_removed", row.eventReason)
    }

    @Test
    fun aPeerPairedHereThatTheChainDoesNotKnowIsRemovedByATombstone() = runBlocking {
        db.tlsPinDao().addMember("d1", pin, "", 8081, System.currentTimeMillis())
        dao.upsertDevice(device("d1"))
        assertEquals("ACTIVE", members.get("d1")!!.state)
        dao.upsertDevice(device("d1").copy(isRemoved = true, removedAt = 60L, clusterVersion = 60L))
        assertEquals("REMOVED", members.get("d1")!!.state)
        assertTrue(dao.getDevice("d1")!!.isRemoved)
    }

    @Test
    fun aTombstoneFromAnOldBuildNeverRemovesAMemberTheChainHoldsActive() = runBlocking {
        signedMember("d1", "ACTIVE", 10L)
        dao.upsertDevice(device("d1").copy(isRemoved = true, removedAt = 60L, clusterVersion = 60L))
        assertEquals("ACTIVE", members.get("d1")!!.state)
        assertFalse(dao.getDevice("d1")!!.isRemoved)
    }

    @Test
    fun writingAnActiveProfileDoesNotLiftARemoval() = runBlocking {
        signedMember("d1", "REMOVED", 50L)
        dao.upsertDevice(device("d1", version = 70L))
        assertEquals("REMOVED", members.get("d1")!!.state)
        assertTrue(dao.getDevice("d1")!!.isRemoved)
    }

    @Test
    fun theActiveMembersPinShowsOnTheDevice() = runBlocking {
        dao.upsertDevice(device("d1"))
        signedMember("d1", "ACTIVE", 10L)
        assertEquals(pin, dao.getDevice("d1")!!.tlsPin)
    }

    @Test
    fun theSmallUpdatesChangeTheProfile() = runBlocking {
        dao.upsertDevice(device("d1"))
        dao.renameDevice("d1", "Pixel")
        dao.updateEndpoint("d1", "10.0.0.2", 9000)
        dao.touchLastSeenEpoch("d1", 500L)
        dao.updateTailnet("d1", "tail", "100.64.0.2")
        dao.updateCardLayout("d1", 7f, 8f, 2, "x")
        dao.updateTileLayout("d1", null, null, 5, "y")
        val d = dao.getDevice("d1")!!
        assertEquals(listOf("Pixel", "10.0.0.2", 9000, 500L, "tail", "100.64.0.2"), listOf(d.deviceName, d.lastKnownIp, d.port, d.lastSeenEpochMs, d.tailnetHostname, d.tailnetIpv4))
        assertEquals(listOf(7f, 8f, 2, "x"), listOf(d.cardPosX, d.cardPosY, d.cardSortOrder, d.cardMenuOrder))
        assertEquals(listOf(5, "y"), listOf(d.tileSortOrder, d.tileMenuOrder))
    }

    @Test
    fun deletingADeviceRemovesItsProfileOnly() = runBlocking {
        signedMember("d1", "ACTIVE", 10L)
        dao.upsertDevice(device("d1"))
        dao.upsertDevice(device("d2"))
        dao.deleteDevice("d1")
        assertNull(dao.getDevice("d1"))
        assertNotNull(members.get("d1"))
        dao.deleteAllDevices()
        assertTrue(dao.getAllDevicesIncludingTombstonesUnordered().isEmpty())
    }

    @Test
    fun theListFlowFollowsMembershipChanges() = runBlocking {
        dao.upsertDevice(device("d1"))
        assertEquals(1, dao.getAllDevicesUnordered().first().size)
        signedMember("d1", "REMOVED", 50L)
        assertEquals(0, dao.getAllDevicesUnordered().first().size)
    }

    private fun removed(id: String, hash: String = "hash-$id", at: Long = 80L, reason: String = "user_removed", by: String = "fx-mac") =
        RemovedDeviceEntity(deviceId = id, publicKeyHash = hash, lastKnownIp = "", port = 0, removedAtEpochMs = at, reason = reason, removedBy = by)

    @Test
    fun aRemovedDeviceRecordIsAnUnsignedRemovedMemberThatKeepsItsKeyHashReasonAndActor() = runBlocking {
        dao.insertRemovedDevice(removed("fx-gone"))
        val row = members.get("fx-gone")!!
        assertEquals(listOf("REMOVED", "user_removed", "fx-mac"), listOf(row.state, row.eventReason, row.eventActor))
        val read = dao.getAllRemovedDevices().single()
        assertEquals(removed("fx-gone", at = 0L), read)
        assertEquals(1, dao.getAllRemovedDevices().count { it.deviceId == "fx-gone" })
        assertNull("no profile is made for a device that was never listed", dao.getDevice("fx-gone"))
    }

    @Test
    fun aTombstoneAndItsRemovedRecordAreOneRowWithTheReasonFilledIn() = runBlocking {
        dao.upsertDevice(device("d1").copy(isRemoved = true, removedAt = 80L, clusterVersion = 80L, publicKeyHash = ""))
        dao.insertRemovedDevice(removed("d1", hash = "key-hash"))
        assertEquals(1, dao.getAllRemovedDevices().size)
        val read = dao.getAllRemovedDevices().single()
        assertEquals(listOf("key-hash", "user_removed", "fx-mac", "192.168.1.7"), listOf(read.publicKeyHash, read.reason, read.removedBy, read.lastKnownIp))
        assertTrue(dao.getDevice("d1")!!.isRemoved)
    }

    @Test
    fun aSignedRemovalIsListedButItsSignatureReasonAndActorAreNotOverwritten() = runBlocking {
        signedMember("fx-signed", "REMOVED", 50L)
        dao.insertRemovedDevice(removed("fx-signed", at = 90L, reason = "other", by = "someone"))
        val row = members.get("fx-signed")!!
        assertEquals(listOf("user_removed", "mac"), listOf(row.eventReason, row.eventActor))
        assertEquals("fx-signed", dao.getAllRemovedDevices().single().deviceId)
    }

    @Test
    fun clearingARemovedDeviceForgetsAnUnsignedUnpinnedRecordButNeverASignedOne() = runBlocking {
        dao.insertRemovedDevice(removed("fx-gone"))
        signedMember("fx-signed", "REMOVED", 50L)
        dao.clearRemovedDevice("fx-gone")
        dao.clearRemovedDevice("fx-signed")
        assertNull(members.get("fx-gone"))
        assertNotNull(members.get("fx-signed"))
        dao.insertRemovedDevice(removed("fx-twin", hash = "same"))
        dao.insertRemovedDevice(removed("fx-twin2", hash = "same"))
        dao.clearRemovedByPublicKeyHash("same")
        assertEquals(listOf("fx-signed"), dao.getAllRemovedDevices().map { it.deviceId })
    }

    @Test
    fun wipingForgetsTheUnsignedRemovedRecordsOnly() = runBlocking {
        dao.insertRemovedDevice(removed("fx-gone"))
        signedMember("fx-signed", "REMOVED", 50L)
        dao.deleteAllRemovedDevices()
        assertEquals(listOf("fx-signed"), dao.getAllRemovedDevices().map { it.deviceId })
    }
}
