package com.fileapex.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing between devices shares a lock, loop or budget: these shapes brought a dead phone's timeout onto every other device. */
class DeviceIndependenceGuardTest {
    private val root = File("src/commonMain/kotlin/com/fileapex")

    private fun text(path: String) = File(root, path).readText()

    @Test
    fun bulletinDrainHasNoGlobalLock() {
        val source = text("data/bulletin/BulletinBoardSyncEngine.kt")
        assertFalse(source.contains("drainMutex"))
        assertFalse(source.contains("pullMutex"))
    }

    @Test
    fun streamBudgetIsPerDevice() {
        assertFalse(Regex("""streamBudget\.withPermit""").containsMatchIn(text("network/FileApexClient.kt")))
    }

    @Test
    fun finalizeLockIsPerFile() {
        assertFalse(text("network/routes/ServerFileRoutes.kt").contains("segmentFinalizeMutex"))
    }

    @Test
    fun pinAnnouncerHoldsNoLockAcrossTheNetwork() {
        assertFalse(text("security/tls/TlsPinAnnouncer.kt").contains("lock.withLock"))
    }

    @Test
    fun presenceHasNoPassOverAllDevices() {
        val source = text("domain/presence/PeerPresenceMonitor.kt")
        assertFalse(source.contains("sweepMutex"))
        assertFalse(source.contains("runSweepOnce"))
        assertFalse(source.contains("coalescePendingMode"))
        assertFalse(source.contains("TransferActivityGuard.isTransferActive()"))
    }

    @Test
    fun aNewPostAndACameBackDeviceBypassBackoff() {
        val source = text("data/bulletin/BulletinBoardSyncEngine.kt")
        // online flip, message, tombstone, retract and pairing each clear the device's old failures
        assertTrue(Regex("peerBackoff\\.reset").findAll(source).count() >= 5)
    }
}
