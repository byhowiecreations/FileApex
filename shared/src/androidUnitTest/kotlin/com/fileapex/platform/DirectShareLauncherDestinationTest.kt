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
            DirectShareShortcutIds.LauncherDestination.BulletinBoard,
            DirectShareShortcutIds.parseOpenUri(
                DirectShareShortcutIds.bulletinOpenUri()
            )
        )
        assertEquals(
            DirectShareShortcutIds.LauncherDestination.Device("abc-123"),
            DirectShareShortcutIds.parseOpenUri(
                DirectShareShortcutIds.deviceOpenUri("abc-123")
            )
        )
    }

    @Test
    fun ignoresPairingAndUnknownOpenPaths() {
        assertNull(DirectShareShortcutIds.parseOpenUri(Uri.parse("fileapex://pair?k=1")))
        assertNull(DirectShareShortcutIds.parseOpenUri(Uri.parse("fileapex://open/other")))
        assertNull(DirectShareShortcutIds.parseOpenUri(Uri.parse("fileapex://open/device")))
        assertNull(DirectShareShortcutIds.parseOpenUri(null))
    }

    @Test
    fun launcherIntentUsesUriThenExtras() {
        val fromUri = Intent(Intent.ACTION_VIEW).apply {
            data = DirectShareShortcutIds.deviceOpenUri("peer-1")
        }
        assertEquals(
            DirectShareShortcutIds.LauncherDestination.Device("peer-1"),
            DirectShareShortcutIds.parseLauncherDestination(fromUri)
        )

        val fromExtra = Intent(Intent.ACTION_VIEW).apply {
            putExtra(DirectShareShortcutIds.EXTRA_TARGET_DEVICE_ID, "peer-2")
        }
        assertEquals(
            DirectShareShortcutIds.LauncherDestination.Device("peer-2"),
            DirectShareShortcutIds.parseLauncherDestination(fromExtra)
        )

        val bulletin = Intent(Intent.ACTION_VIEW).apply {
            putExtra(DirectShareShortcutIds.EXTRA_OPEN_BULLETIN, true)
        }
        assertEquals(
            DirectShareShortcutIds.LauncherDestination.BulletinBoard,
            DirectShareShortcutIds.parseLauncherDestination(bulletin)
        )
    }

    @Test
    fun shareActionsNeverCountAsLauncherOpens() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            data = DirectShareShortcutIds.deviceOpenUri("peer-1")
            putExtra(DirectShareShortcutIds.EXTRA_TARGET_DEVICE_ID, "peer-1")
            putExtra(DirectShareShortcutIds.EXTRA_SHORTCUT_ID, "share-device-peer-1")
        }
        assertNull(DirectShareShortcutIds.parseLauncherDestination(send))

        val bulletinShare = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            data = DirectShareShortcutIds.bulletinOpenUri()
            putExtra(DirectShareShortcutIds.EXTRA_SHORTCUT_ID, "share-bulletin-board")
        }
        assertNull(DirectShareShortcutIds.parseLauncherDestination(bulletinShare))
        assertTrue(DirectShareShortcutIds.isBulletinShortcut("share-bulletin-board"))
        assertEquals("peer-1", DirectShareShortcutIds.deviceIdFromShortcutId("share-device-peer-1"))
    }
}
