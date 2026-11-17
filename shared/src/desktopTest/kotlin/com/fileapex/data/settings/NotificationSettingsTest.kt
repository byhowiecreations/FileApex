package com.fileapex.data.settings

import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSettingsTest {
    private val kv = MapKvStore()
    private fun settings() = BaseAppSettings(kv)

    @Test
    fun onlyTheNotesNotificationsStartOn() {
        val s = settings()
        assertTrue(s.notesNotificationsEnabled.value)
        assertFalse(s.fileTransferNotificationsEnabled.value)
        assertFalse(s.driveRelayNotificationsEnabled.value)
        assertFalse(s.notesNotificationPromptShown.value)
        assertFalse(s.notificationBroadcastEnabled.value)
        assertFalse(s.notificationDisclosureAccepted.value)
        assertFalse(s.notificationBroadcastSyncDismissal.value)
        assertFalse(s.notificationBroadcastVerificationCodes.value)
        assertFalse(s.deviceNotificationPopups.value)
        assertEquals("", s.notificationBroadcastTargetDeviceId.value)
        assertTrue(s.notificationBroadcastApps.value.isEmpty())
    }

    @Test
    fun aSwitchedOffNotesToggleStaysOffAfterARestart() {
        settings().setNotesNotificationsEnabled(false)
        assertFalse(settings().notesNotificationsEnabled.value)
    }

    @Test
    fun everyChoiceSurvivesARestart() {
        val first = settings()
        first.setFileTransferNotificationsEnabled(true)
        first.setDriveRelayNotificationsEnabled(true)
        first.setNotesNotificationPromptShown(true)
        first.setNotificationBroadcastEnabled(true)
        first.setNotificationBroadcastTargetDeviceId("fx-a")
        first.setNotificationDisclosureAccepted(true)
        first.setNotificationBroadcastSyncDismissal(true)
        first.setNotificationBroadcastVerificationCodes(true)
        first.setDeviceNotificationPopups(true)
        first.setNotificationBroadcastApps(setOf(" com.a ", "", "com.b"))
        val second = settings()
        assertTrue(second.fileTransferNotificationsEnabled.value)
        assertTrue(second.driveRelayNotificationsEnabled.value)
        assertTrue(second.notesNotificationPromptShown.value)
        assertTrue(second.notificationBroadcastEnabled.value)
        assertEquals("fx-a", second.notificationBroadcastTargetDeviceId.value)
        assertTrue(second.notificationDisclosureAccepted.value)
        assertTrue(second.notificationBroadcastSyncDismissal.value)
        assertTrue(second.notificationBroadcastVerificationCodes.value)
        assertTrue(second.deviceNotificationPopups.value)
        assertEquals(setOf("com.a", "com.b"), second.notificationBroadcastApps.value)
    }

    @Test
    fun someoneWhoSharedBeforeTheDisclosureExistedCountsAsHavingAcceptedIt() {
        kv.putBoolean(BaseAppSettings.KEY_NOTIFICATION_BROADCAST_ENABLED, true)
        assertTrue(settings().notificationDisclosureAccepted.value)
        assertTrue(kv.getBoolean(BaseAppSettings.KEY_NOTIFICATION_DISCLOSURE_ACCEPTED, false))
    }

    @Test
    fun resetToDefaultsPutsEverythingBack() {
        val s = settings()
        s.setNotesNotificationsEnabled(false)
        s.setFileTransferNotificationsEnabled(true)
        s.setNotificationBroadcastApps(setOf("com.a"))
        s.resetToDefaults()
        assertTrue(s.notesNotificationsEnabled.value)
        assertFalse(s.fileTransferNotificationsEnabled.value)
        assertTrue(s.notificationBroadcastApps.value.isEmpty())
        assertTrue(settings().notesNotificationsEnabled.value)
    }
}
