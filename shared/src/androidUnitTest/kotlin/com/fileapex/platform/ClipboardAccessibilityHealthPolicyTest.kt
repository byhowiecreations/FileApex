package com.fileapex.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardAccessibilityHealthPolicyTest {

    @Test
    fun hiddenWhenSharingOrAccessibilitySettingOff() {
        assertFalse(
            ClipboardAccessibilityHealthPolicy.needsReenablePrompt(
                sharingEnabled = false,
                accessibilitySettingEnabled = true,
                serviceListedEnabled = false,
                serviceBound = false,
                elapsedSinceProcessOrUnbindMs = 60_000L
            )
        )
        assertFalse(
            ClipboardAccessibilityHealthPolicy.needsReenablePrompt(
                sharingEnabled = true,
                accessibilitySettingEnabled = false,
                serviceListedEnabled = false,
                serviceBound = false,
                elapsedSinceProcessOrUnbindMs = 60_000L
            )
        )
    }

    @Test
    fun healthyWhenListedAndBound() {
        assertFalse(
            ClipboardAccessibilityHealthPolicy.needsReenablePrompt(
                sharingEnabled = true,
                accessibilitySettingEnabled = true,
                serviceListedEnabled = true,
                serviceBound = true,
                elapsedSinceProcessOrUnbindMs = 60_000L
            )
        )
    }

    @Test
    fun oemUnlistPromptsImmediately() {
        assertTrue(
            ClipboardAccessibilityHealthPolicy.needsReenablePrompt(
                sharingEnabled = true,
                accessibilitySettingEnabled = true,
                serviceListedEnabled = false,
                serviceBound = false,
                elapsedSinceProcessOrUnbindMs = 0L
            )
        )
    }

    @Test
    fun listedButUnboundWaitsForGraceThenPrompts() {
        assertFalse(
            ClipboardAccessibilityHealthPolicy.needsReenablePrompt(
                sharingEnabled = true,
                accessibilitySettingEnabled = true,
                serviceListedEnabled = true,
                serviceBound = false,
                elapsedSinceProcessOrUnbindMs = ClipboardAccessibilityHealthPolicy.BIND_GRACE_MS - 1
            )
        )
        assertTrue(
            ClipboardAccessibilityHealthPolicy.needsReenablePrompt(
                sharingEnabled = true,
                accessibilitySettingEnabled = true,
                serviceListedEnabled = true,
                serviceBound = false,
                elapsedSinceProcessOrUnbindMs = ClipboardAccessibilityHealthPolicy.BIND_GRACE_MS
            )
        )
    }
}
