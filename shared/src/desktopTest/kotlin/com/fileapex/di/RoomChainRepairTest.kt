package com.fileapex.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.RoomTlsPinStore
import com.fileapex.membership.ChainApplier
import com.fileapex.membership.EcEventSigner
import com.fileapex.membership.MembershipKeys
import java.io.File
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Device C already holds B as removed. After A re-admits B with a new key, applying the chain on C reactivates B in
 * the pin store and in [paired_devices_view].
 */
class RoomChainRepairTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val amanda = Dev("amanda")
    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private lateinit var db: FileApexDatabase
    private lateinit var effects: RoomChainEffects

    @Before
    fun setUp() {
        val file = File.createTempFile("repair", ".db").also { it.deleteOnExit() }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        effects = RoomChainEffects(RoomTlsPinStore(db.tlsPinDao()), db.membershipDao())
    }

    @After
    fun close() = runCatching { db.close() }.let { }

    private lateinit var seed: ChainSeed

    @Test
    fun aThirdDeviceReactivatesAReAdmittedPeerInThePinStoreAndTheDeviceList() = runBlocking {
        seed = ChainSeed(db, selfId = "mac", rootId = "amanda").start()
        seed.admit("mac", mac.pin, key = mac.key, trustPin = false)
        seed.admit("phone", phone.pin, key = phone.key)
        seed.remove("phone")
        effects.ensureProfile("phone")
        assertEquals(true, db.deviceDao().getDevice("phone")!!.isRemoved)

        val fresh = ChainSeed.Dev("phone")
        seed.admit("phone", fresh.pin, key = fresh.key, trustPin = false, project = false)

        ChainApplier("mac", effects).apply(seed.log.view())

        val device = db.deviceDao().getDevice("phone")!!
        assertFalse(device.isRemoved)
        assertEquals(fresh.pin, device.tlsPin)
        assertEquals(fresh.pin, effects.knownPin("phone"))
    }

    @Test
    fun aPinTheUserTrustedForALegacyMemberSurvivesEveryApplyOfTheChain() = runBlocking {
        seed = ChainSeed(db).start()
        val oldPin = "ab".repeat(32)
        seed.admitKeyless("old", oldPin)
        val store = RoomTlsPinStore(db.tlsPinDao())
        val trusted = "cd".repeat(32)

        assertEquals(true, store.record("old", trusted, 8081, userConfirmed = true))
        repeat(3) { ChainApplier("mac", effects).apply(seed.log.view()) }

        val row = db.tlsPinDao().member("old")!!
        assertEquals(trusted, row.tlsPin)
        assertEquals("", row.tlsPinAlt)
        assertEquals(trusted, db.deviceDao().getDevice("old")!!.tlsPin)
    }

    @Test
    fun removingALegacyMemberKeepsThePinTheUserTrustedAsItsRevokedPin() = runBlocking {
        seed = ChainSeed(db).start()
        val oldPin = "ab".repeat(32)
        seed.admitKeyless("old", oldPin)
        val trusted = "cd".repeat(32)
        assertEquals(true, RoomTlsPinStore(db.tlsPinDao()).record("old", trusted, 8081, userConfirmed = true))
        seed.remove("old", project = false)

        ChainApplier("mac", effects).apply(seed.log.view())

        val row = db.tlsPinDao().member("old")!!
        assertEquals("REMOVED", row.state)
        assertEquals("the key the device really presents stays recognised as revoked", trusted, row.tlsPin)
    }

    @Test
    fun aDeviceTheChainRemovedCanBePairedAgainAndItsNewKeyIsPinned() = runBlocking {
        seed = ChainSeed(db).start()
        val oldPin = "ab".repeat(32)
        val newPin = "cd".repeat(32)
        seed.admit("phone", oldPin)
        seed.remove("phone")
        effects.ensureProfile("phone")
        val repo = com.fileapex.data.device.DeviceRepository(
            db.deviceDao(), { com.fileapex.data.device.LocalDeviceRef("mac", emptySet()) }, pins = RoomTlsPinStore(db.tlsPinDao())
        )
        assertEquals(true, db.deviceDao().getDevice("phone")!!.isRemoved)

        repo.adoptFromPairing(
            com.fileapex.data.db.PairedDeviceEntity(
                deviceId = "phone", deviceName = "phone", lastKnownIp = "192.168.1.9", port = 8080,
                publicKeyHash = "h", publicKey = "", rootPath = "/", clusterVersion = 1L
            )
        )
        assertEquals("pairing in person lets the new key in", true, repo.recordTlsPin("phone", newPin, 8081, rejoin = true))

        val device = repo.getDevice("phone")!!
        assertFalse(device.isRemoved)
        assertEquals(newPin, device.tlsPin)
    }
}
