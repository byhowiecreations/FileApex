package com.fileapex.domain.transfer

import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.i18n.AppI18n
import com.fileapex.notice.MapKvStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the ongoing progress notification says, and that the live transfer capsule is on unless the user turned it off. */
class TransferNotificationContentTest {
    private fun stats(
        file: String = "holiday.mp4",
        device: String = "MacBook Pro",
        progress: Float = 0.43f,
        total: Long = 1_000L,
        speed: String = "12 MB/s",
        eta: String = "2 min",
        cancelable: Boolean = true,
        isReceiving: Boolean = false
    ) = LiveTransferStats(
        isActive = true,
        destinationDeviceName = device,
        currentFileName = file,
        totalBytes = total,
        progress = progress,
        speedFormatted = speed,
        etaFormatted = eta,
        cancelable = cancelable,
        isReceiving = isReceiving
    )

    @Test
    fun `the file name is the title and the device is the sub text`() {
        val content = transferNotificationContent(stats(), queueCount = 0)
        assertEquals("holiday.mp4", content.title)
        assertEquals("MacBook Pro", content.subText)
    }

    @Test
    fun `the text shows percent, speed and time left`() {
        val content = transferNotificationContent(stats(), queueCount = 0)
        assertEquals("43% • 12 MB/s • 2 min", content.text)
        assertEquals(43, content.percent)
        assertFalse(content.indeterminate)
    }

    @Test
    fun `no file name falls back to a generic title`() {
        val content = transferNotificationContent(stats(file = ""), queueCount = 0)
        assertEquals(AppI18n.t("sending_files"), content.title)
        val receiving = transferNotificationContent(stats(file = "", isReceiving = true), queueCount = 0)
        assertEquals(AppI18n.t("receiving_file"), receiving.title)
    }

    @Test
    fun `an unknown size is shown as indeterminate`() {
        val content = transferNotificationContent(stats(progress = 0f, total = 0L, speed = "", eta = ""), queueCount = 0)
        assertTrue(content.indeterminate)
        assertEquals(AppI18n.t("active_transfer"), content.text)
    }

    @Test
    fun `percent never leaves 0 to 100`() {
        assertEquals(100, transferNotificationContent(stats(progress = 1.4f), queueCount = 0).percent)
        assertEquals(0, transferNotificationContent(stats(progress = -0.2f), queueCount = 0).percent)
    }

    @Test
    fun `waiting items are counted in the text`() {
        val content = transferNotificationContent(stats(), queueCount = 3)
        assertTrue(content.text, content.text.endsWith(AppI18n.plural("items_in_queue", 3, "3")))
    }

    @Test
    fun `cancel is offered only when the transfer can be cancelled`() {
        assertTrue(transferNotificationContent(stats(cancelable = true), 0).cancelable)
        assertFalse(transferNotificationContent(stats(cancelable = false), 0).cancelable)
    }

    @Test
    fun `transfer notifications are on by default, because the live capsule needs them`() {
        val fresh = BaseAppSettings(MapKvStore())
        assertTrue(fresh.fileTransferNotificationsEnabled.value)
        assertTrue(fresh.liveTransferCapsuleEnabled.value)
    }

    @Test
    fun `a user who turned transfer notifications off keeps them off`() {
        val store = MapKvStore()
        BaseAppSettings(store).setFileTransferNotificationsEnabled(false)
        assertFalse(BaseAppSettings(store, existingInstall = true).fileTransferNotificationsEnabled.value)
    }

    @Test
    fun `the live capsule is on by default and stays off once the user turns it off`() {
        val store = MapKvStore()
        val fresh = BaseAppSettings(store)
        assertTrue(fresh.liveTransferCapsuleEnabled.value)
        fresh.setLiveTransferCapsuleEnabled(false)
        assertFalse(BaseAppSettings(store, existingInstall = true).liveTransferCapsuleEnabled.value)
    }

    @Test
    fun `android manifest declares promoted notifications permission without play removal`() {
        val mainManifest = File("../composeApp/src/androidMain/AndroidManifest.xml").readText()
        val playManifest = File("../composeApp/src/play/AndroidManifest.xml").readText()
        assertTrue(mainManifest.contains("android.permission.POST_PROMOTED_NOTIFICATIONS"))
        assertFalse(playManifest.contains("POST_PROMOTED_NOTIFICATIONS"))
    }
}
