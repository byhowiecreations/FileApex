package com.fileapex.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityChangeNoticeTest {
    @After
    fun reset() = IdentityChangeNotice.acknowledge()

    @Test
    fun aDeviceWhoseIdChangedSaysSoWithBothIds() {
        IdentityChangeNotice.report("fx-old", "fx-new")
        assertEquals(IdentityChange("fx-old", "fx-new"), IdentityChangeNotice.pending.value)
    }

    @Test
    fun noPreviousIdOrTheSameIdIsNoChange() {
        IdentityChangeNotice.report("", "fx-new")
        assertNull(IdentityChangeNotice.pending.value)
        IdentityChangeNotice.report("fx-new", "fx-new")
        assertNull(IdentityChangeNotice.pending.value)
    }

    private fun device(id: String, removed: Boolean) = com.fileapex.data.db.PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "10.0.0.2", port = 8080, publicKeyHash = "h", rootPath = "/", isRemoved = removed
    )

    @Test
    fun onlyDevicesThatAreStillInTheClusterCountAsACluster() {
        assertFalse(IdentityChangeNotice.hasCluster(emptyList()))
        assertFalse("devices that were removed are not a cluster", IdentityChangeNotice.hasCluster(listOf(device("a", true), device("b", true))))
        assertTrue(IdentityChangeNotice.hasCluster(listOf(device("a", true), device("b", false))))
    }

    @Test
    fun aDeviceWithNoClusterIsNotToldAndForgetsTheOldId() {
        var forgotten = 0
        IdentityChangeNotice.report("fx-old", "fx-new", inCluster = false) { forgotten++ }
        assertNull(IdentityChangeNotice.pending.value)
        assertEquals(1, forgotten)
        IdentityChangeNotice.report("", "fx-new", inCluster = false) { forgotten++ }
        assertEquals("nothing stored, nothing to forget", 1, forgotten)
    }

    @Test
    fun aDeviceInAClusterIsStillToldAndKeepsTheOldId() {
        var forgotten = 0
        IdentityChangeNotice.report("fx-old", "fx-new", inCluster = true) { forgotten++ }
        assertEquals(IdentityChange("fx-old", "fx-new"), IdentityChangeNotice.pending.value)
        assertEquals(0, forgotten)
    }

    @Test
    fun acknowledgingHidesTheNoticeForThisRun() {
        IdentityChangeNotice.report("fx-old", "fx-new")
        IdentityChangeNotice.acknowledge()
        assertNull(IdentityChangeNotice.pending.value)
    }

    @Test
    fun theChangeIsSettledOnlyWhenTheClusterHoldsTheNewIdActive() {
        var cleared = 0
        assertFalse(IdentityChangeNotice.settled(isActiveMember = false, storedPrevious = "fx-old") { cleared++ })
        assertFalse(IdentityChangeNotice.settled(isActiveMember = true, storedPrevious = "") { cleared++ })
        assertEquals(0, cleared)
        IdentityChangeNotice.report("fx-old", "fx-new")
        assertTrue(IdentityChangeNotice.settled(isActiveMember = true, storedPrevious = "fx-old") { cleared++ })
        assertEquals(1, cleared)
        assertNull(IdentityChangeNotice.pending.value)
    }

    @Test
    fun theCodeAskedForIsShownWhileAMemberDecidesAndGoneOnceSettled() {
        IdentityChangeNotice.report("fx-old", "fx-new")
        IdentityChangeNotice.requesting(com.fileapex.membership.IdentityChangeRequester.State.Asking("mac", "123456"))
        assertEquals(com.fileapex.membership.IdentityChangeRequester.State.Asking("mac", "123456"), IdentityChangeNotice.request.value)
        IdentityChangeNotice.settled(isActiveMember = true, storedPrevious = "fx-old") {}
        assertEquals(com.fileapex.membership.IdentityChangeRequester.State.Idle, IdentityChangeNotice.request.value)
    }
}
