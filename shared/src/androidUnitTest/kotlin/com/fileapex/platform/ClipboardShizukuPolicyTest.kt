package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardShizukuPolicyTest {

    @Test
    fun binderReadyNeedsPingAndPermission() {
        assertFalse(ClipboardShizukuPolicy.binderReady(pingBinder = false, permissionGranted = true))
        assertFalse(ClipboardShizukuPolicy.binderReady(pingBinder = true, permissionGranted = false))
        assertTrue(ClipboardShizukuPolicy.binderReady(pingBinder = true, permissionGranted = true))
    }

    @Test
    fun privilegedReadNeedsOptInAndLiveBinder() {
        assertFalse(
            ClipboardShizukuPolicy.shouldUsePrivilegedClipboard(
                optedIn = false,
                pingBinder = true,
                permissionGranted = true
            )
        )
        assertFalse(
            ClipboardShizukuPolicy.shouldUsePrivilegedClipboard(
                optedIn = true,
                pingBinder = false,
                permissionGranted = true
            )
        )
        assertTrue(
            ClipboardShizukuPolicy.shouldUsePrivilegedClipboard(
                optedIn = true,
                pingBinder = true,
                permissionGranted = true
            )
        )
    }

    @Test
    fun toggleHintSeparatesConnectedFromUsing() {
        assertEquals(
            ClipboardShizukuPolicy.ToggleHint.CONNECTED_UNUSED,
            ClipboardShizukuPolicy.toggleHint(
                optedIn = false,
                installed = true,
                running = true,
                active = true
            )
        )
        assertEquals(
            ClipboardShizukuPolicy.ToggleHint.USING,
            ClipboardShizukuPolicy.toggleHint(
                optedIn = true,
                installed = true,
                running = true,
                active = true
            )
        )
        assertEquals(
            ClipboardShizukuPolicy.ToggleHint.AUTHORIZE,
            ClipboardShizukuPolicy.toggleHint(
                optedIn = true,
                installed = true,
                running = true,
                active = false
            )
        )
        assertEquals(
            ClipboardShizukuPolicy.ToggleHint.SUBTITLE,
            ClipboardShizukuPolicy.toggleHint(
                optedIn = false,
                installed = true,
                running = true,
                active = false
            )
        )
    }
}
