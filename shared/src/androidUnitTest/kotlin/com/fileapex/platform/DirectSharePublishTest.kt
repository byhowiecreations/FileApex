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

    @Test
    fun allPairedDevicesSortByLocalFrequencyAndPrioritizeOverFriends() {
        val base = PairedDeviceEntity(
            deviceId = "d-1",
            deviceName = "Device 1",
            lastKnownIp = "192.168.1.10",
            port = 17420,
            publicKeyHash = "h1",
            rootPath = "/",
            platform = "android",
            os = "android"
        )
        val peers = listOf(
            base.copy(deviceId = "d-low", deviceName = "Pixel 9"),
            base.copy(deviceId = "d-high", deviceName = "Mac Studio"),
            base.copy(deviceId = "d-mid", deviceName = "Samsung Fold8"),
            base.copy(deviceId = "d-zero", deviceName = "iPad Pro")
        )
        val usage = mapOf("d-high" to 9, "d-mid" to 3, "d-low" to 1, "d-zero" to 0)
        val plan = DirectShareShortcutCoordinator.planPublishShortcuts(
            peers = peers,
            friendCount = 3,
            maxDynamicCount = 4,
            usageCountFor = { usage[it] ?: 0 }
        )
        assertEquals(
            listOf("d-high", "d-mid", "d-low", "d-zero"),
            plan.pushPeers.map { it.deviceId }
        )
        assertTrue(plan.dynamicPeers.size >= 3)
        assertEquals("d-high", plan.dynamicPeers.first().deviceId)
    }

    @Test
    fun mainActivityShareIntentFiltersOmitCustomShareTargetCategory() {
        val manifest = java.io.File("../composeApp/src/androidMain/AndroidManifest.xml")
            .takeIf { it.isFile }
            ?: java.io.File("composeApp/src/androidMain/AndroidManifest.xml")
        val text = manifest.readText()
        assertFalse(
            "MainActivity intent-filter must not declare SHARE_TARGET category; shortcuts.xml owns it",
            text.contains("""<category android:name="com.fileapex.category.SHARE_TARGET" />""")
        )
    }

    @Test
    fun shortcutsXmlDeclaresExplicitMediaTypesAndDeviceShortcutPersonHasIcon() {
        val shortcutsFile = java.io.File("../composeApp/src/androidMain/res/xml/shortcuts.xml")
            .takeIf { it.isFile }
            ?: java.io.File("composeApp/src/androidMain/res/xml/shortcuts.xml")
        val xml = shortcutsFile.readText()
        listOf("text/plain", "image/*", "video/*", "audio/*", "application/*", "*/*").forEach { mime ->
            assertTrue("shortcuts.xml missing mimeType $mime", xml.contains("""android:mimeType="$mime""""))
        }

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
        val shortcut = DirectShareShortcutCoordinator.buildShortcutForTest(context, mac, rank = 0)
        val person = DirectShareShortcutCoordinator.buildPeerPersonForTest(context, mac)
        assertEquals("MacBook Pro", person.name)
        assertEquals("mac-1", person.key)
        assertTrue(shortcut.icon != null)
        assertTrue(
            "Person attached to Direct Share shortcut should include an icon for modern sharesheet ranking",
            person.icon != null
        )
    }

    @Test
    fun normalShareRecordsLocalDirectShareUsageForSelectedRemotePeers() {
        val recorded = mutableListOf<String>()
        val remote = com.fileapex.domain.transfer.MultiCopyDeviceOption(
            deviceId = "mac-pro-1",
            deviceName = "MacBook Pro",
            host = "192.168.1.10",
            port = 17420,
            destinationRoot = "/",
            isLocal = false
        )
        val localOnly = com.fileapex.domain.transfer.MultiCopyDeviceOption(
            deviceId = "local",
            deviceName = "This Device",
            host = "127.0.0.1",
            port = 0,
            destinationRoot = "/",
            isLocal = true
        )
        com.fileapex.presentation.ShareSendUsageTracker.recordSuccessfulPeerSends(
            selected = listOf(localOnly, remote),
            failedDeviceIds = emptySet(),
            recorder = { recorded += it }
        )
        assertEquals(listOf("mac-pro-1"), recorded)

        recorded.clear()
        com.fileapex.presentation.ShareSendUsageTracker.recordSuccessfulPeerSends(
            selected = listOf(remote),
            failedDeviceIds = setOf("mac-pro-1"),
            recorder = { recorded += it }
        )
        assertTrue(recorded.isEmpty())
    }
}
