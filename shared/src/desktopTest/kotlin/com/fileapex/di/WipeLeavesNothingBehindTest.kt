package com.fileapex.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.MemberProfileEntity
import com.fileapex.data.db.MembershipSchemaCallback
import com.fileapex.data.db.RoomTlsPinStore
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.LocalDeviceRef
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A device the cluster removed lets go of everything: no peer it knew, active or already gone, may come back from what the wipe left. */
class WipeLeavesNothingBehindTest {
    private lateinit var db: FileApexDatabase
    private lateinit var seed: ChainSeed
    private lateinit var repo: DeviceRepository

    @Before
    fun setUp() {
        val file = File.createTempFile("wipe", ".db").also { it.deleteOnExit() }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).addCallback(MembershipSchemaCallback()).build()
        seed = ChainSeed(db, selfId = "honor", rootId = "mac")
        repo = DeviceRepository(db.deviceDao(), { LocalDeviceRef("honor", emptySet()) }, pins = RoomTlsPinStore(db.tlsPinDao()))
        runBlocking {
            seed.start()
            seed.admit("honor"); seed.admit("fold"); seed.admit("sig")
            listOf("mac", "fold", "sig").forEach { db.membershipDao().putProfileIfAbsent(MemberProfileEntity.stub(it, "name-$it")) }
            // The Signature left the cluster before this device was removed: this device already knew it as gone.
            seed.leave("sig")
            db.membershipDao().retainRevokedPin("sig", seed.dev("sig").pin)
            seed.remove("honor")
        }
    }

    @After
    fun close() = runCatching { db.close() }.let { }

    @Test fun afterTheWipeNoPeerIsLeftNotEvenOneTheDeviceAlreadyKnewAsGone() = runBlocking {
        seed.log.clear()
        repo.forgetCluster()
        db.membershipDao().refreshMembers()
        assertEquals(emptyList<String>(), repo.listDevices().filterNot { it.isRemoved }.map { it.deviceId })
        assertEquals(emptyList<String>(), db.membershipDao().allEffective().filter { it.state == "ACTIVE" }.map { it.deviceId })
    }
}
