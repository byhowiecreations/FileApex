package com.fileapex.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.RoomTlsPinStore
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.domain.presence.LanScanBackoff
import com.fileapex.domain.presence.PeerPresenceMonitor
import com.fileapex.network.FileApexClient
import com.fileapex.util.TimeUtils
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The real roster and presence monitor: a goodbye from a member takes it offline inside the grace window, anything newer brings it back. */
class PresenceGoodbyeLifecycleTest {
    private lateinit var db: FileApexDatabase
    private lateinit var repo: DeviceRepository
    private lateinit var monitor: PeerPresenceMonitor

    @Before
    fun setUp() = runBlocking {
        val file = File.createTempFile("presence", ".db").also { it.deleteOnExit() }
        db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        val pins = RoomTlsPinStore(db.tlsPinDao())
        repo = DeviceRepository(db.deviceDao(), { LocalDeviceRef("mac", emptySet()) }, pins = pins)
        val seed = ChainSeed(db, selfId = "mac").start()
        val effects = RoomChainEffects(pins, db.membershipDao())
        seed.admit("A")
        seed.admit("B")
        effects.ensureProfile("A")
        effects.ensureProfile("B")
        monitor = PeerPresenceMonitor(repo, FileApexClient(localDeviceId = { "mac" }), LanScanBackoff(1_000L, 60_000L, { TimeUtils.now() }))
        repo.touchPeerLastSeen("A", "192.168.1.50", 8081)
        repo.touchPeerLastSeen("B", "192.168.1.51", 8081)
        monitor.notifyPassiveReachability("A", "B")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun online(id: String) = monitor.isDeviceOnline(repo.getDevice(id)!!)

    @Test
    fun aGoodbyeTakesAnOnlinePeerOfflineAndLeavesTheOthers() = runBlocking {
        assertTrue(online("A") && online("B"))
        monitor.onPeerGoodbye("A")
        assertFalse(online("A"))
        assertTrue(online("B"))
    }

    @Test
    fun anyLaterContactBringsThePeerBack() = runBlocking {
        monitor.onPeerGoodbye("A")
        assertFalse(online("A"))
        monitor.notifyPassiveReachability("A", epochMs = TimeUtils.now() + 5_000L)
        assertTrue(online("A"))
    }

    @Test
    fun aGoodbyeFromAnUnknownDeviceChangesNothing() = runBlocking {
        monitor.onPeerGoodbye("NOBODY")
        assertTrue(online("A") && online("B"))
    }
}
