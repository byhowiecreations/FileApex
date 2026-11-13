package com.fileapex.platform

import android.app.Application
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.fileapex.data.db.PairedDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectSharePublishTest {

    @Test
    fun pairedDevicesRankAheadOfBulletinBoardAndStillIncludeBulletin() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val mac = PairedDeviceEntity(
            deviceId = "mac-1",
            deviceName = "MacBook Pro",
            lastKnownIp = "192.168.1.10",
            port = 17420,
            publicKeyHash = "hash-mac",
            rootPath = "/",
            platform = "desktop",
            os = "macos"
        )
        val fold = mac.copy(
            deviceId = "fold-1",
            deviceName = "Samsung Fold8",
            platform = "android",
            os = "android"
        )

        val entries = DirectShareShortcutCoordinator.buildPublishShortcutsForTest(
            context = context,
            peers = listOf(mac, fold),
            friends = emptyList()
        )
        assertEquals(3, entries.size)
        val macEntry = entries.first { it.id == DirectShareShortcutCoordinator.shortcutId("mac-1") }
        val foldEntry = entries.first { it.id == DirectShareShortcutCoordinator.shortcutId("fold-1") }
        val bulletinEntry = entries.first { it.id == DirectShareShortcutCoordinator.BULLETIN_SHORTCUT_ID }

        assertTrue(macEntry.rank < bulletinEntry.rank)
        assertTrue(foldEntry.rank < bulletinEntry.rank)
        assertEquals(0, macEntry.rank)
        assertEquals(1, foldEntry.rank)
        assertEquals(2, bulletinEntry.rank)
    }

    @Test
    fun publishVerificationDetectsMissingDeviceShortcuts() {
        assertFalse(
            DirectShareShortcutCoordinator.isPublishComplete(
                ok = true,
                publishedIds = listOf(DirectShareShortcutCoordinator.BULLETIN_SHORTCUT_ID),
                expectedPeerCount = 2
            )
        )
        assertTrue(
            DirectShareShortcutCoordinator.isPublishComplete(
                ok = true,
                publishedIds = listOf(
                    DirectShareShortcutCoordinator.shortcutId("mac-1"),
                    DirectShareShortcutCoordinator.BULLETIN_SHORTCUT_ID
                ),
                expectedPeerCount = 1
            )
        )
    }

    @Test
    fun buildPeerShortcutsSkipsSingleFailingBuilderAndKeepsOthers() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val mac = PairedDeviceEntity(
            deviceId = "mac-1",
            deviceName = "MacBook Pro",
            lastKnownIp = "192.168.1.10",
            port = 17420,
            publicKeyHash = "hash-mac",
            rootPath = "/",
            platform = "desktop",
            os = "macos"
        )
        val fold = mac.copy(deviceId = "fold-1", deviceName = "Samsung Fold8")

        val built = DirectShareShortcutCoordinator.buildPeerShortcutsSafely(
            peers = listOf(mac, fold),
            builder = { peer, rank ->
                if (peer.deviceId == "mac-1") error("boom")
                DirectShareShortcutCoordinator.buildShortcutForTest(context, peer, rank)
            }
        )
        assertEquals(1, built.size)
        assertEquals(DirectShareShortcutCoordinator.shortcutId("fold-1"), built.single().id)
        assertEquals(0, built.single().rank)
    }
}
