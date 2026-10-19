package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingHintsTest {
    private fun row(id: String, name: String, hash: String, removed: Boolean = false) = PairedDeviceEntity(
        deviceId = id, deviceName = name, lastKnownIp = "192.168.1.5", port = 8080, publicKeyHash = "",
        rootPath = "/", hardwareHash = hash, isRemoved = removed
    )

    private fun hint(
        joinerId: String = "new-id", name: String = "Honor Magic V5", hash: String = "h1",
        active: List<PairedDeviceEntity> = emptyList(), removed: List<PairedDeviceEntity> = emptyList()
    ) = PairingHints.evaluate(joinerId, name, hash, active, removed)

    @Test
    fun sameHardwareUnderAnotherIdIsAReplacement() {
        val result = hint(active = listOf(row("old-id", "Honor Magic V5", "h1")))
        assertEquals(PairingHint.SameHardware(names = listOf("Honor Magic V5"), ids = listOf("old-id")), result)
    }

    @Test
    fun theSameIdIsNotItsOwnTwin() {
        assertEquals(PairingHint.None, hint(joinerId = "old-id", active = listOf(row("old-id", "Honor Magic V5", "h1"))))
    }

    @Test
    fun aDeviceRemovedBeforeIsFlagged() {
        assertEquals(PairingHint.RemovedBefore, hint(removed = listOf(row("gone", "Old phone", "h1", removed = true))))
    }

    @Test
    fun aKnownNameOnDifferentHardwareIsAWarning() {
        val result = hint(name = "honor magic v5", active = listOf(row("old-id", "Honor Magic V5", "h2")))
        assertEquals(PairingHint.SameNameDifferentHardware("Honor Magic V5"), result)
    }

    @Test
    fun noHashFromTheJoinerSaysNothingAboutHardware() {
        assertEquals(PairingHint.None, hint(hash = "", active = listOf(row("old-id", "Honor Magic V5", "h1"))))
    }

    @Test
    fun aRowWithoutAHashCannotBeMatchedOrWarnedAbout() {
        assertEquals(PairingHint.None, hint(active = listOf(row("old-id", "Other", ""))))
    }

    @Test
    fun aNewDeviceWithANewNameIsPlain() {
        assertEquals(PairingHint.None, hint(name = "Brand new tablet", active = listOf(row("old-id", "Honor Magic V5", "h2"))))
    }
}
