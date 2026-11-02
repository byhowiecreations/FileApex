package com.fileapex.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.di.ChainSeed
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `members` is rebuilt from the chain and the local facts inside the transaction of the write that changed one of them. */
class MemberStoreTest {
    private lateinit var db: FileApexDatabase
    private lateinit var seed: ChainSeed
    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)
    private val pinC = "c".repeat(64)
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        val file = File.createTempFile("members", ".db").also { it.deleteOnExit() }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).addCallback(MembershipSchemaCallback()).build()
        seed = ChainSeed(db, selfId = "mac")
        runBlocking { seed.start() }
    }

    @After
    fun close() = runCatching { db.close() }.let { }

    private suspend fun rows() = db.membershipDao().storedMembers().map { listOf(it.deviceId, it.state, it.publicKey.isNotEmpty().toString(), it.eventActor, it.eventReason) }

    @Test
    fun droppingTheRosterAndRebuildingItGivesTheSameRows() = runBlocking {
        seed.admit("phone", pinA)
        seed.admitKeyless("old", pinB)
        seed.admit("gone", pinC)
        seed.remove("gone")
        db.tlsPinDao().addMember("pending", "d".repeat(64), "", 8081, now)
        db.deviceDao().insertRemovedDevice(RemovedDeviceEntity("ghost", "hg", "", 0, 1L, "user_removed", "mac"))
        val before = db.membershipDao().storedMembers()
        before.forEach { db.membershipDao().deleteMember(it.deviceId) }
        assertTrue(db.membershipDao().storedMembers().isEmpty())
        db.membershipDao().refreshMembers()
        assertEquals(before, db.membershipDao().storedMembers())
        assertEquals(listOf("ghost", "gone", "mac", "old", "pending", "phone").sorted(), before.map { it.deviceId })
    }

    @Test
    fun aDamagedRowIsHealedByARebuild() = runBlocking {
        seed.admit("phone", pinA)
        db.membershipDao().writeMember("phone", "REMOVED", "", "nobody", "wrong")
        db.membershipDao().writeMember("stranger", "ACTIVE", "", "", "")
        db.membershipDao().refreshMembers()
        assertEquals("ACTIVE", db.membershipDao().get("phone")!!.state)
        assertNull(db.membershipDao().get("stranger"))
    }

    @Test
    fun aPeerPairedHereThatTheChainDoesNotKnowIsPendingUntilTheAdmitLandsAndThenTheChainDecides() = runBlocking {
        db.tlsPinDao().addMember("old", pinA, "", 8081, now)
        assertEquals(now, db.membershipDao().localRow("old")!!.pendingAdmitAt)
        assertEquals("ACTIVE", db.membershipDao().get("old")!!.state)
        seed.admitKeyless("old", pinA)
        assertEquals(0L, db.membershipDao().localRow("old")!!.pendingAdmitAt)
        assertEquals("ACTIVE", db.membershipDao().get("old")!!.state)
    }

    @Test
    fun aPendingAdmitOlderThanSevenDaysIsDropped() = runBlocking {
        db.tlsPinDao().addMember("old", pinA, "", 8081, now - 8 * 24 * 60 * 60_000L)
        assertNull(db.membershipDao().get("old"))
    }

    @Test
    fun aMemberTheChainNamesGetsOnlyItsPinWhenItIsFirstRecorded() = runBlocking {
        seed.admit("phone", pinA, trustPin = false)
        db.tlsPinDao().addMember("phone", pinA, "", 8081, now)
        assertEquals(0L, db.membershipDao().localRow("phone")!!.pendingAdmitAt)
        assertEquals(pinA, db.tlsPinDao().member("phone")!!.tlsPin)
    }

    @Test
    fun aTombstoneRemovesAPendingPeerAndTheChainsOwnRemovalNeedsNoLegacyRow() = runBlocking {
        db.tlsPinDao().addMember("old", pinA, "", 8081, now)
        db.deviceDao().insertRemovedDevice(RemovedDeviceEntity("old", "h", "", 0, 1L, "user_removed", "mac"))
        assertEquals(listOf("REMOVED", "user_removed", "mac"), db.membershipDao().get("old")!!.let { listOf(it.state, it.eventReason, it.eventActor) })
        assertEquals(0L, db.membershipDao().localRow("old")!!.pendingAdmitAt)
        seed.admit("phone", pinB)
        seed.remove("phone")
        db.deviceDao().insertRemovedDevice(RemovedDeviceEntity("phone", "h2", "", 0, 1L, "user_removed", "mac"))
        assertTrue(db.membershipDao().storedLegacyRemoved().none { it.deviceId == "phone" })
        assertEquals("h2", db.tlsPinDao().member("phone")!!.keyHash)
    }

    @Test
    fun clearingARemovalLetsAPeerBePairedAgainButNeverUndoesTheChainsRemoval() = runBlocking {
        db.deviceDao().insertRemovedDevice(RemovedDeviceEntity("ghost", "hg", "", 0, 1L, "user_removed", "mac"))
        seed.admit("phone", pinB)
        seed.remove("phone")
        db.deviceDao().clearRemovedDevice("ghost")
        db.deviceDao().clearRemovedDevice("phone")
        db.deviceDao().clearRemovedByPublicKeyHash("nothing")
        assertNull(db.membershipDao().get("ghost"))
        assertEquals("REMOVED", db.membershipDao().get("phone")!!.state)
    }

    @Test
    fun deletingEveryRemovalClearsThePreChainOnes() = runBlocking {
        db.deviceDao().insertRemovedDevice(RemovedDeviceEntity("ghost", "hg", "", 0, 1L, "", ""))
        db.deviceDao().deleteAllRemovedDevices()
        assertTrue(db.membershipDao().storedLegacyRemoved().isEmpty())
        assertNull(db.membershipDao().get("ghost"))
    }

    @Test
    fun pairingAgainInPersonBringsARemovedPeerBackThroughTheHoldAndClearsItsPreChainRemoval() = runBlocking {
        seed.admit("phone", pinA)
        seed.remove("phone")
        db.deviceDao().insertRemovedDevice(RemovedDeviceEntity("ghost", "hg", "", 0, 1L, "", ""))
        val store = RoomTlsPinStore(db.tlsPinDao())
        assertTrue(store.record("phone", pinB, 8081, rejoin = true, userConfirmed = true))
        assertTrue(store.record("ghost", pinC, 8081, rejoin = true, userConfirmed = true))
        assertEquals(listOf("ACTIVE", "ACTIVE"), listOf("phone", "ghost").map { db.membershipDao().get(it)!!.state })
        assertTrue(db.membershipDao().storedLegacyRemoved().isEmpty())
        assertTrue(db.membershipDao().localRow("phone")!!.pairedAtEpochMs > 0L)
    }

    @Test
    fun leavingForAnotherClusterForgetsEveryLocalFactExceptTheHosts() = runBlocking {
        db.tlsPinDao().addMember("old", pinA, "", 8081, now)
        db.deviceDao().insertRemovedDevice(RemovedDeviceEntity("ghost", "hg", "", 0, 1L, "", ""))
        db.tlsPinDao().addMember("host", pinB, "", 8081, now)
        db.deviceDao().forgetLocalExcept("host")
        assertEquals(listOf("host"), db.membershipDao().storedLocal().map { it.deviceId })
        assertTrue(db.membershipDao().storedLegacyRemoved().isEmpty())
        assertEquals(listOf("host", "mac"), db.membershipDao().storedMembers().map { it.deviceId })
    }

    @Test
    fun theRevokedPinOfARemovedMemberStaysAndTheHoldEndsWithTheWindow() = runBlocking {
        seed.admit("phone", pinA)
        seed.remove("phone")
        assertEquals(pinA, db.tlsPinDao().member("phone")!!.tlsPin)
        assertEquals(listOf("phone"), db.tlsPinDao().pinned().filter { it.state == "REMOVED" }.map { it.deviceId })
    }

    @Test
    fun twoPendingPeersWithOnePinMakeOnlyTheFirstActive() = runBlocking {
        db.tlsPinDao().putPending("a-first", pinA, "", 8081, now)
        db.tlsPinDao().putPending("b-second", pinA, "", 8081, now)
        db.membershipDao().refreshMembers()
        assertEquals(listOf("a-first", "mac"), db.membershipDao().storedMembers().map { it.deviceId })
    }

    @Test
    fun theRowsOfTheMembersTableAreOnlyWhatTheChainAndTheLocalFactsSay() = runBlocking {
        seed.admit("phone", pinA)
        seed.admitKeyless("old", pinB)
        assertEquals(
            listOf(listOf("mac", "ACTIVE", "true", "", ""), listOf("old", "ACTIVE", "false", "", ""), listOf("phone", "ACTIVE", "true", "", "")),
            rows()
        )
    }

    @Test
    fun aMemberThatIsAlreadyActiveKeepsItsPinWhenALaterIdClaimsTheSameOne() = runBlocking {
        seed.admit("z-old", pinA)
        db.tlsPinDao().putPending("a-new", pinA, "", 8081, now)
        db.membershipDao().refreshMembers()
        assertEquals(listOf("mac", "z-old"), db.membershipDao().storedMembers().map { it.deviceId })
    }

    @Test
    fun aPinHandedFromARemovedMemberToANewOneInOneRebuildNeverHoldsTwoActiveRows() = runBlocking {
        seed.admit("m-old", pinA)
        db.tlsPinDao().putPending("b-new", pinA, "", 8081, now)
        seed.remove("m-old")
        assertEquals(listOf("ACTIVE", "REMOVED"), listOf("b-new", "m-old").map { db.membershipDao().get(it)!!.state })
    }

    @Test
    fun theHoldAfterAnInPersonPairingUsesTheSeqOfTheChainThatCountsNotALongerLosingBranch() = runBlocking {
        seed.admit("phone", pinA)
        seed.admit("tablet", pinB)
        val prefix = seed.log.recordsAfter(0L)
        seed.remove("phone")
        val other = com.fileapex.membership.MembershipChainLog(com.fileapex.membership.InMemoryChainStore()) { "mac" }
        other.ingest(prefix)
        val mac = seed.dev("mac").signer
        for (id in listOf("laptop", "desktop", "watch")) {
            val dev = seed.dev(id)
            other.append(mac, com.fileapex.membership.RecordKind.ADMIT, id, dev.key, dev.pin, "paired")
        }
        seed.log.ingest(other.recordsAfter(0L))
        assertEquals(4L, seed.log.view().state.seq)
        assertEquals(6L, db.membershipDao().storedChain().maxOf { it.seq })

        assertTrue(RoomTlsPinStore(db.tlsPinDao()).record("phone", pinC, 8081, rejoin = true, userConfirmed = true))

        assertEquals(4L, db.membershipDao().localRow("phone")!!.pairedChainSeq)
    }
}
