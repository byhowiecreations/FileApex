package com.fileapex.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.MemberProfileEntity
import com.fileapex.data.db.MembershipSchemaCallback
import com.fileapex.data.db.RoomTlsPinStore
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.domain.peer.PeerNodeProtocols
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.membership.ChainBootstrap
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A peer that never ran the chain is paired here, owed an admit by this device, admitted on the next tick, and from then on the chain decides. */
class KeylessPairingFlowTest {
    private lateinit var db: FileApexDatabase
    private lateinit var seed: ChainSeed
    private val pin = "ab".repeat(32)

    @Before
    fun setUp() {
        val file = File.createTempFile("keyless", ".db").also { it.deleteOnExit() }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).addCallback(MembershipSchemaCallback()).build()
        seed = ChainSeed(db, selfId = "mac")
        runBlocking { seed.start() }
    }

    @After
    fun close() = runCatching { db.close() }.let { }

    @Test
    fun aPeerPairedHereIsAdmittedFromTheOwedRowAndThenTheChainOwnsIt() = runBlocking {
        val pins = RoomTlsPinStore(db.tlsPinDao())
        val repo = DeviceRepository(db.deviceDao(), { LocalDeviceRef("mac", emptySet()) }, pins = pins)
        db.membershipDao().putProfileIfAbsent(
            MemberProfileEntity.stub("old", "Old Docker").copy(
                supportedProtocolsJson = PeerNodeStateMapper.encodeProtocols(listOf(PeerNodeProtocols.IDENTITY_V1, PeerNodeProtocols.FILES_V1, PeerNodeProtocols.CLUSTER_V1))
            )
        )
        assertTrue(pins.record("old", pin, 8081))
        assertEquals("ACTIVE", db.membershipDao().get("old")!!.state)
        assertTrue("the admit is owed", db.membershipDao().localRow("old")!!.pendingAdmitAt > 0L)
        assertEquals(null, seed.log.view().state.member("old"))

        val peers = trustedPeersOf(db.membershipDao().allEffective(), repo.listDevices(), "mac")
        assertTrue(ChainBootstrap(seed.log) { seed.dev("mac").signer }.admitTrusted(peers))
        seed.refresh()

        assertEquals(true, seed.log.view().state.member("old")!!.isLegacyAdmit)
        assertEquals(0L, db.membershipDao().localRow("old")!!.pendingAdmitAt)
        assertEquals(listOf("ACTIVE", pin), db.tlsPinDao().member("old")!!.let { listOf(it.state, it.tlsPin) })
    }
}
