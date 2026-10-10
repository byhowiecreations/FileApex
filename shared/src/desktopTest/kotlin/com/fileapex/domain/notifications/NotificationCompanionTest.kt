package com.fileapex.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCompanionTest {

    private fun stored(grantor: String, active: Boolean, expiresAt: Long) =
        NotificationCompanionGrant.encode(CompanionGrantRecord(grantor, active, expiresAt))

    @Test
    fun aPhoneThatNeverGotAGrantKeepsSending() {
        assertTrue(NotificationCompanionGrant.allows("", "mac", now = 1_000L))
    }

    @Test
    fun garbageStorageReadsAsNoGrant() {
        assertNull(NotificationCompanionGrant.decode("{not json"))
        assertTrue(NotificationCompanionGrant.allows("{not json", "mac", now = 1_000L))
    }

    @Test
    fun anActiveGrantAllowsUntilItExpires() {
        val grant = stored("mac", active = true, expiresAt = 5_000L)
        assertTrue(NotificationCompanionGrant.allows(grant, "mac", now = 4_999L))
        assertFalse(NotificationCompanionGrant.allows(grant, "mac", now = 5_000L))
    }

    @Test
    fun aStandDownBlocksEvenBeforeExpiry() {
        assertFalse(NotificationCompanionGrant.allows(stored("mac", active = false, expiresAt = 99_000L), "mac", now = 1_000L))
    }

    @Test
    fun locatingNeedsALiveGrantFromThatComputer() {
        assertFalse(NotificationCompanionGrant.isActive("", "mac", now = 1_000L))
        assertFalse(NotificationCompanionGrant.isActive(stored("other", true, 5_000L), "mac", now = 1_000L))
        assertFalse(NotificationCompanionGrant.isActive(stored("mac", false, 5_000L), "mac", now = 1_000L))
        assertFalse(NotificationCompanionGrant.isActive(stored("mac", true, 5_000L), "mac", now = 5_000L))
        assertTrue(NotificationCompanionGrant.isActive(stored("mac", true, 5_000L), "mac", now = 4_999L))
    }

    @Test
    fun theStoredGrantSurvivesARestart() {
        val record = CompanionGrantRecord("mac", true, 7_000L)
        assertEquals(record, NotificationCompanionGrant.decode(NotificationCompanionGrant.encode(record)))
    }

    @Test
    fun onlyPinnedAndroidPhonesAreGranted() {
        assertTrue(NotificationCompanion.canBeGranted(false, "android", "", "pin"))
        assertFalse(NotificationCompanion.canBeGranted(true, "android", "", "pin"))
        assertFalse(NotificationCompanion.canBeGranted(false, "macos", "", "pin"))
        assertFalse(NotificationCompanion.canBeGranted(false, "android", "", ""))
    }

    @Test
    fun theComputerKnowsOnlyTheShownPhoneAsCompanion() {
        NotificationCompanion.setDesired("pixel")
        assertTrue(NotificationCompanion.isCompanion("pixel"))
        assertFalse(NotificationCompanion.isCompanion("fold"))
        assertEquals(true, NotificationCompanion.pollValueFor("pixel", false, "android", "", "pin"))
        assertEquals(false, NotificationCompanion.pollValueFor("fold", false, "android", "", "pin"))
        assertNull(NotificationCompanion.pollValueFor("fold", false, "android", "", ""))
        NotificationCompanion.setDesired("")
        assertFalse(NotificationCompanion.isCompanion("pixel"))
        assertFalse(NotificationCompanion.isCompanion(""))
    }
}
