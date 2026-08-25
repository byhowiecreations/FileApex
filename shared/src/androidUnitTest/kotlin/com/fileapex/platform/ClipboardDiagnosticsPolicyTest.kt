package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardDiagnosticsPolicyTest {

    @Test
    fun hiddenWhenSharingOff() {
        assertFalse(ClipboardDiagnosticsPolicy.shouldShowEntry(sharingEnabled = false))
    }

    @Test
    fun shownWhenSharingOn() {
        assertTrue(ClipboardDiagnosticsPolicy.shouldShowEntry(sharingEnabled = true))
    }

    @Test
    fun recipientsChosenRequiresAllOrCheckedSpecific() {
        assertFalse(ClipboardDiagnosticsPolicy.recipientsChosen(false, false, 2))
        assertTrue(ClipboardDiagnosticsPolicy.recipientsChosen(true, false, 0))
        assertFalse(ClipboardDiagnosticsPolicy.recipientsChosen(false, true, 0))
        assertTrue(ClipboardDiagnosticsPolicy.recipientsChosen(false, true, 1))
    }

    @Test
    fun requiredRowsShowGrantedAndMissing() {
        val ready = ClipboardDiagnosticsPolicy.checks(
            sharingEnabled = true,
            recipientsChosen = true,
            accessibilitySettingEnabled = true,
            accessibilityListed = true,
            accessibilityBound = true,
            batteryWhitelisted = true,
            notificationsEnabled = true,
            restrictedSettingsRelevant = true,
            restrictedSettingsBlocked = false,
            shizukuActive = false,
            shizukuOptedIn = false
        )
        assertTrue(ClipboardDiagnosticsPolicy.allRequiredGranted(ready))
        assertEquals(
            ClipboardCheckStatus.NOT_REQUIRED,
            ready.first { it.id == ClipboardDiagnosticsPolicy.ID_SHIZUKU }.status
        )

        val missingBound = ClipboardDiagnosticsPolicy.checks(
            sharingEnabled = true,
            recipientsChosen = true,
            accessibilitySettingEnabled = true,
            accessibilityListed = true,
            accessibilityBound = false,
            batteryWhitelisted = true,
            notificationsEnabled = true,
            restrictedSettingsRelevant = false,
            restrictedSettingsBlocked = false,
            shizukuActive = true,
            shizukuOptedIn = false
        )
        assertFalse(ClipboardDiagnosticsPolicy.allRequiredGranted(missingBound))
        assertEquals(
            ClipboardCheckStatus.MISSING,
            missingBound.first { it.id == ClipboardDiagnosticsPolicy.ID_A11Y_BOUND }.status
        )
        assertEquals(
            ClipboardCheckStatus.NOT_REQUIRED,
            missingBound.first { it.id == ClipboardDiagnosticsPolicy.ID_SHIZUKU }.status
        )
        assertFalse(missingBound.any { it.id == ClipboardDiagnosticsPolicy.ID_RESTRICTED })
    }

    @Test
    fun shizukuGrantedOnlyWhenFileApexOptedIn() {
        val unused = ClipboardDiagnosticsPolicy.checks(
            sharingEnabled = true,
            recipientsChosen = true,
            accessibilitySettingEnabled = true,
            accessibilityListed = true,
            accessibilityBound = true,
            batteryWhitelisted = true,
            notificationsEnabled = true,
            restrictedSettingsRelevant = false,
            restrictedSettingsBlocked = false,
            shizukuActive = true,
            shizukuOptedIn = false
        )
        assertEquals(
            ClipboardCheckStatus.NOT_REQUIRED,
            unused.first { it.id == ClipboardDiagnosticsPolicy.ID_SHIZUKU }.status
        )
        val using = ClipboardDiagnosticsPolicy.checks(
            sharingEnabled = true,
            recipientsChosen = true,
            accessibilitySettingEnabled = true,
            accessibilityListed = true,
            accessibilityBound = true,
            batteryWhitelisted = true,
            notificationsEnabled = true,
            restrictedSettingsRelevant = false,
            restrictedSettingsBlocked = false,
            shizukuActive = true,
            shizukuOptedIn = true
        )
        assertEquals(
            ClipboardCheckStatus.GRANTED,
            using.first { it.id == ClipboardDiagnosticsPolicy.ID_SHIZUKU }.status
        )
    }
}
