package com.fileapex.data.note

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.fileapex.data.bulletin.BulletinContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteNotifyPolicyTest {

    @Before
    fun setUp() {
        com.fileapex.data.settings.initAndroidAppSettings(
            ApplicationProvider.getApplicationContext<Application>()
        )
    }

    @Test
    fun incomingPreviewPrefersCaptionThenFileName() {
        assertEquals("hello", NoteNotifyPolicy.incomingPreview("hello", "file.apk"))
        assertEquals("file.apk", NoteNotifyPolicy.incomingPreview("  ", "file.apk"))
        assertEquals("", NoteNotifyPolicy.incomingPreview("", null))
    }

    @Test
    fun incomingNoteUsesBulletinChannelRules() {
        assertTrue(
            NoteNotifyPolicy.shouldNotifyIncomingNote(
                isMine = false,
                alreadyNotified = false,
                preview = "file.apk"
            )
        )
        assertFalse(
            NoteNotifyPolicy.shouldNotifyIncomingNote(
                isMine = true,
                alreadyNotified = false,
                preview = "file.apk"
            )
        )
        assertFalse(
            NoteNotifyPolicy.shouldNotifyIncomingNote(
                isMine = false,
                alreadyNotified = true,
                preview = "file.apk"
            )
        )
    }

    @Test
    fun attachmentReadyUsesTransferChannelRules() {
        assertTrue(
            NoteNotifyPolicy.shouldNotifyAttachmentReady(
                isMine = false,
                alreadyHadLocalFile = false,
                fileName = "file.apk"
            )
        )
        assertFalse(
            NoteNotifyPolicy.shouldNotifyAttachmentReady(
                isMine = false,
                alreadyHadLocalFile = true,
                fileName = "file.apk"
            )
        )
        assertFalse(
            NoteNotifyPolicy.shouldNotifyAttachmentReady(
                isMine = true,
                alreadyHadLocalFile = false,
                fileName = "file.apk"
            )
        )
        assertFalse(
            NoteNotifyPolicy.shouldNotifyAttachmentReady(
                isMine = false,
                alreadyHadLocalFile = false,
                fileName = ""
            )
        )
    }

    @Test
    fun criticalBulletinDetectsLowBatteryText() {
        assertTrue(
            NoteNotifyPolicy.isCriticalBulletin("The battery level is 12% on Pixel 8")
        )
        assertTrue(
            NoteNotifyPolicy.isCriticalBulletin("The battery is low on MacBook Pro")
        )
        assertTrue(
            NoteNotifyPolicy.isCriticalBulletin("The battery level is 15%.")
        )
        assertTrue(
            NoteNotifyPolicy.isCriticalBulletin(
                content = "ignored",
                contentType = BulletinContentType.BATTERY_LOW
            )
        )
        assertFalse(NoteNotifyPolicy.isCriticalBulletin("Hello from the office"))
        assertFalse(NoteNotifyPolicy.isCriticalBulletin(""))
    }

    @Test
    fun notificationTitleUsesSenderDeviceName() {
        assertTrue(NoteNotifyPolicy.notificationTitle("MacBook Pro").contains("MacBook Pro"))
        val emptyTitle = NoteNotifyPolicy.notificationTitle("")
        assertTrue(emptyTitle.isNotBlank())
        val pairedTitle = NoteNotifyPolicy.notificationTitle("Paired Device")
        assertTrue(pairedTitle.contains("Paired Device"))
    }

    @Test
    fun rewriteBatteryDeviceNameSwapsStaleFactoryName() {
        val original = "The battery level is 15% on HONOR MBH-N49"
        assertEquals(
            "The battery level is 15% on HONOR Magic8 Pro",
            NoteNotifyPolicy.rewriteBatteryDeviceName(
                content = original,
                storedName = "HONOR MBH-N49",
                displayName = "HONOR Magic8 Pro"
            )
        )
        assertEquals(
            "Hello team",
            NoteNotifyPolicy.rewriteBatteryDeviceName(
                content = "Hello team",
                storedName = "HONOR MBH-N49",
                displayName = "HONOR Magic8 Pro"
            )
        )
    }
}
