package com.fileapex.platform

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectShareLauncherDestinationTest {

    @Test
    fun parsesBulletinAndDeviceOpenUris() {
        assertEquals(
            DirectShareShortcutCoordinator.LauncherDestination.BulletinBoard,
            DirectShareShortcutCoordinator.parseOpenUri(
                DirectShareShortcutCoordinator.bulletinOpenUri()
            )
        )
        assertEquals(
            DirectShareShortcutCoordinator.LauncherDestination.Device("abc-123"),
            DirectShareShortcutCoordinator.parseOpenUri(
                DirectShareShortcutCoordinator.deviceOpenUri("abc-123")
            )
        )
    }

    @Test
    fun ignoresPairingAndUnknownOpenPaths() {
        assertNull(DirectShareShortcutCoordinator.parseOpenUri(Uri.parse("fileapex://pair?k=1")))
        assertNull(DirectShareShortcutCoordinator.parseOpenUri(Uri.parse("fileapex://open/other")))
        assertNull(DirectShareShortcutCoordinator.parseOpenUri(Uri.parse("fileapex://open/device")))
        assertNull(DirectShareShortcutCoordinator.parseOpenUri(null))
    }

    @Test
    fun launcherIntentUsesUriThenExtras() {
        val fromUri = Intent(Intent.ACTION_VIEW).apply {
            data = DirectShareShortcutCoordinator.deviceOpenUri("peer-1")
        }
        assertEquals(
            DirectShareShortcutCoordinator.LauncherDestination.Device("peer-1"),
            DirectShareShortcutCoordinator.parseLauncherDestination(fromUri)
        )

        val fromExtra = Intent(Intent.ACTION_VIEW).apply {
            putExtra(DirectShareShortcutCoordinator.EXTRA_TARGET_DEVICE_ID, "peer-2")
        }
        assertEquals(
            DirectShareShortcutCoordinator.LauncherDestination.Device("peer-2"),
            DirectShareShortcutCoordinator.parseLauncherDestination(fromExtra)
        )

        val bulletin = Intent(Intent.ACTION_VIEW).apply {
            putExtra(DirectShareShortcutCoordinator.EXTRA_OPEN_BULLETIN, true)
        }
        assertEquals(
            DirectShareShortcutCoordinator.LauncherDestination.BulletinBoard,
            DirectShareShortcutCoordinator.parseLauncherDestination(bulletin)
        )
    }

    @Test
    fun shareActionsNeverCountAsLauncherOpens() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            data = DirectShareShortcutCoordinator.deviceOpenUri("peer-1")
            putExtra(DirectShareShortcutCoordinator.EXTRA_TARGET_DEVICE_ID, "peer-1")
            putExtra(DirectShareShortcutCoordinator.EXTRA_SHORTCUT_ID, "share-device-peer-1")
        }
        assertNull(DirectShareShortcutCoordinator.parseLauncherDestination(send))

        val bulletinShare = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            data = DirectShareShortcutCoordinator.bulletinOpenUri()
            putExtra(DirectShareShortcutCoordinator.EXTRA_SHORTCUT_ID, "share-bulletin-board")
        }
        assertNull(DirectShareShortcutCoordinator.parseLauncherDestination(bulletinShare))
        assertTrue(DirectShareShortcutCoordinator.isBulletinShortcut("share-bulletin-board"))
        assertEquals("peer-1", DirectShareShortcutCoordinator.deviceIdFromShortcutId("share-device-peer-1"))
    }
}
