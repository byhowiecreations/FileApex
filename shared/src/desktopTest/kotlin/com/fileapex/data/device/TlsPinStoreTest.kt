package com.fileapex.data.device

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.MembershipSchemaCallback
import com.fileapex.data.db.RoomTlsPinStore
import com.fileapex.membership.MemberState
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

abstract class TlsPinStoreTest {
    protected val pinA = "a".repeat(64)
    protected val pinB = "b".repeat(64)
    protected val pinC = "c".repeat(64)

    protected abstract fun create(): TlsPinStore

    /** A member row as a signed admission leaves it, or as the roster of an older build leaves it when [signed] is false. */
    protected abstract suspend fun seedMember(store: TlsPinStore, id: String, pin: String, state: MemberState = MemberState.ACTIVE, signed: Boolean = true)

    private suspend fun TlsPinStore.entry(id: String) = entries().firstOrNull { it.deviceId == id }

    @Test
    fun aDeviceWithoutARowGetsALegacyActiveRowWithItsPinAltAndPort(): Unit = runBlocking {
        val store = create()
        assertTrue(store.record("honor", pinA, 8081, alt = pinB))
        val entry = store.entry("honor")!!
        assertEquals(listOf(pinA, pinB, 8081), listOf(entry.pin, entry.alt, entry.port))
        assertFalse(entry.signed)
        assertFalse(entry.revoked)
    }

    @Test
    fun recordingTheSameValuesAgainChangesNothing(): Unit = runBlocking {
        val store = create()
        store.record("honor", pinA, 8081)
        assertFalse(store.record("honor", pinA, 8081))
    }

    @Test
    fun aLegacyPinIsReplacedByTheOneTheCallerAuthenticated(): Unit = runBlocking {
        val store = create()
        store.record("honor", pinA, 8081)
        assertTrue(store.record("honor", pinB, 9000))
        assertEquals(pinB, store.entry("honor")!!.pin)
        assertEquals(9000, store.entry("honor")!!.port)
    }

    protected open val oldEventSignaturesExist: Boolean = true

    @Test
    fun aSignedMembersPinIsTheHashOfItsKeyAndNoCallerCanReplaceIt(): Unit = runBlocking {
        if (!oldEventSignaturesExist) return@runBlocking
        val store = create()
        seedMember(store, "honor", pinA, signed = true)
        assertFalse("a different pin is refused", store.record("honor", pinB, 8081))
        assertEquals(pinA, store.entry("honor")!!.pin)
        assertTrue("the port and the rotation pin are local facts and can be recorded", store.record("honor", pinA, 9000, alt = pinC))
        val entry = store.entry("honor")!!
        assertEquals(listOf(pinA, pinC, 9000), listOf(entry.pin, entry.alt, entry.port))
    }

    @Test
    fun aKeyTheUserTrustedReplacesASignedMembersPinAndLeavesItUnsignedUntilItsNextAdmission(): Unit = runBlocking {
        if (!oldEventSignaturesExist) return@runBlocking
        val store = create()
        seedMember(store, "mac", pinA, signed = true)
        assertTrue("the user trusted the changed key", store.record("mac", pinB, 8081, userConfirmed = true))
        val entry = store.entry("mac")!!
        assertEquals(pinB, entry.pin)
        assertFalse("the old admission no longer proves the new key", entry.signed)
        assertFalse(entry.revoked)
        assertFalse("asking again changes nothing, so no prompt can loop", store.record("mac", pinB, 8081, userConfirmed = true))
    }

    @Test
    fun aTrustedKeyNeverBringsBackARemovedMemberOrTakesAnotherDevicesPin(): Unit = runBlocking {
        val store = create()
        seedMember(store, "gone", pinA, state = MemberState.REMOVED)
        assertFalse(store.record("gone", pinB, 8081, userConfirmed = true))
        seedMember(store, "mac", pinC, signed = true)
        store.record("honor", pinB, 8081)
        assertFalse(store.record("mac", pinB, 8081, userConfirmed = true))
        assertEquals(pinC, store.entry("mac")!!.pin)
    }

    @Test
    fun aPinAnotherActiveDeviceHoldsIsRefused(): Unit = runBlocking {
        val store = create()
        store.record("honor", pinA, 8081)
        assertFalse(store.record("signature", pinA, 8081))
        assertNull(store.entry("signature"))
    }

    @Test
    fun aRemovedMembersPinStaysAsARevokedEntryAndOnlyAPairingCanBringItBack(): Unit = runBlocking {
        val store = create()
        seedMember(store, "honor", pinA, state = MemberState.REMOVED)
        val entry = store.entry("honor")!!
        assertTrue(entry.revoked)
        assertEquals(pinA, entry.pin)
        assertFalse("a pin announcement does not undo a removal", store.record("honor", pinB, 8081))
        assertTrue(store.entry("honor")!!.revoked)
        assertTrue("a pairing does", store.record("honor", pinB, 8081, rejoin = true))
        val back = store.entry("honor")!!
        assertFalse(back.revoked)
        assertEquals(pinB, back.pin)
        assertFalse(back.signed)
    }

    @Test
    fun aDepartedMembersOldPinCanStillBeRevokedWhileAnotherDeviceTakesItsPlaceAfterwards(): Unit = runBlocking {
        val store = create()
        seedMember(store, "honor", pinA, state = MemberState.REMOVED)
        assertTrue("a revoked pin is not an active pin, so another device may pair with it", store.record("other", pinA, 8081))
    }

    @Test
    fun aRowWithoutAPinIsNotAnEntry(): Unit = runBlocking {
        val store = create()
        seedMember(store, "gone", "", state = MemberState.REMOVED)
        assertNull(store.entry("gone"))
    }

    @Test
    fun malformedPinsAreRefused(): Unit = runBlocking {
        val store = create()
        assertFalse(store.record("honor", "short", 8081))
        assertFalse(store.record("honor", pinA, 8081, alt = "nope"))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun aPortChangeTouchesOnlyTheTlsPortOfAnActiveEntry(): Unit = runBlocking {
        val store = create()
        store.record("honor", pinA, 8081, alt = pinB)
        assertTrue(store.recordPort("honor", 9555))
        assertFalse("same port again", store.recordPort("honor", 9555))
        val entry = store.entry("honor")!!
        assertEquals(listOf(pinA, pinB, 9555), listOf(entry.pin, entry.alt, entry.port))
        assertFalse(store.recordPort("honor", 70_000))
        assertFalse(store.recordPort("nobody", 9555))
        seedMember(store, "gone", pinC, state = MemberState.REMOVED)
        assertFalse(store.recordPort("gone", 9555))
    }

    @Test
    fun observingShowsTheCurrentEntries(): Unit = runBlocking {
        val store = create()
        store.record("honor", pinA, 8081)
        assertEquals(listOf("honor"), store.observe().first().map { it.deviceId })
    }
}

class InMemoryTlsPinStoreTest : TlsPinStoreTest() {
    override fun create(): TlsPinStore = InMemoryTlsPinStore()

    override suspend fun seedMember(store: TlsPinStore, id: String, pin: String, state: MemberState, signed: Boolean) =
        (store as InMemoryTlsPinStore).seed(id, pin, state, signed)
}

class RoomTlsPinStoreTest : TlsPinStoreTest() {
    override val oldEventSignaturesExist: Boolean = false
    private val files = mutableListOf<File>()
    private val dbs = mutableListOf<FileApexDatabase>()
    private lateinit var db: FileApexDatabase

    override fun create(): TlsPinStore {
        val file = File.createTempFile("pins", ".db").also { it.deleteOnExit(); files += it }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addCallback(MembershipSchemaCallback())
            .build()
            .also { dbs += it }
        seed = com.fileapex.di.ChainSeed(db, selfId = "self")
        kotlinx.coroutines.runBlocking { seed.start() }
        return RoomTlsPinStore(db.tlsPinDao())
    }

    private lateinit var seed: com.fileapex.di.ChainSeed

    override suspend fun seedMember(store: TlsPinStore, id: String, pin: String, state: MemberState, signed: Boolean) {
        if (signed) seed.admit(id, pin) else seed.admitKeyless(id, pin)
        if (state != MemberState.ACTIVE) seed.remove(id)
    }

    @After
    fun close() = dbs.forEach { runCatching { it.close() } }
}
