package com.fileapex.data.settings

import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSettingsTest {
    private val kv = MapKvStore()
    private fun settings() = BaseAppSettings(kv)

    @Test
    fun everythingStartsOffExceptImagesAndTheModeIsUnset() {
        val s = settings()
        assertFalse(s.clipboardSharingEnabled.value)
        assertFalse(s.clipboardDisclosureAcknowledged.value)
        assertFalse(s.accessibilityDisclosureAcknowledged.value)
        assertFalse(s.installPackagesDisclosureAcknowledged.value)
        assertFalse(s.multiCopyIntroAcknowledged.value)
        assertFalse(s.clipboardTargetConfigured.value)
        assertFalse(s.clipboardOptInPromptShown.value)
        assertFalse(s.clipboardViaCellularEnabled.value)
        assertFalse(s.clipboardAccessibilityEnabled.value)
        assertFalse(s.clipboardSendNotificationEnabled.value)
        assertFalse(s.clipboardShizukuEnabled.value)
        assertFalse(s.clipboardAutoSendEnabled.value)
        assertTrue(s.clipboardImagesEnabled.value)
        assertEquals(ClipboardShareMode.UNSET, s.clipboardShareMode.value)
        assertTrue(s.clipboardTargetDeviceIds.value.isEmpty())
    }

    @Test
    fun everyChoiceSurvivesARestart() {
        val first = settings()
        first.setClipboardSharingEnabled(true)
        first.setClipboardDisclosureAcknowledged(true)
        first.setAccessibilityDisclosureAcknowledged(true)
        first.setInstallPackagesDisclosureAcknowledged(true)
        first.setMultiCopyIntroAcknowledged(true)
        first.setClipboardTargetConfigured(true)
        first.setClipboardOptInPromptShown(true)
        first.setClipboardViaCellularEnabled(true)
        first.setClipboardAccessibilityEnabled(true)
        first.setClipboardSendNotificationEnabled(true)
        first.setClipboardShizukuEnabled(true)
        first.setClipboardAutoSendEnabled(true)
        first.setClipboardImagesEnabled(false)
        first.setClipboardShareMode(ClipboardShareMode.entries.first { it != ClipboardShareMode.UNSET })
        val second = settings()
        assertTrue(second.clipboardSharingEnabled.value)
        assertTrue(second.clipboardDisclosureAcknowledged.value)
        assertTrue(second.accessibilityDisclosureAcknowledged.value)
        assertTrue(second.installPackagesDisclosureAcknowledged.value)
        assertTrue(second.multiCopyIntroAcknowledged.value)
        assertTrue(second.clipboardTargetConfigured.value)
        assertTrue(second.clipboardOptInPromptShown.value)
        assertTrue(second.clipboardViaCellularEnabled.value)
        assertTrue(second.clipboardAccessibilityEnabled.value)
        assertTrue(second.clipboardSendNotificationEnabled.value)
        assertTrue(second.clipboardShizukuEnabled.value)
        assertTrue(second.clipboardAutoSendEnabled.value)
        assertFalse(second.clipboardImagesEnabled.value)
        assertEquals(first.clipboardShareMode.value, second.clipboardShareMode.value)
        assertTrue(first.clipboardShareMode.value != ClipboardShareMode.UNSET)
    }

    @Test
    fun targetDevicesAreTrimmedAddedAndRemovedAndSurviveARestart() {
        val first = settings()
        first.setClipboardTargetDevice("  fx-a ", true)
        first.setClipboardTargetDevice("fx-b", true)
        first.setClipboardTargetDevice("   ", true)
        first.setClipboardTargetDevice("fx-b", false)
        assertEquals(setOf("fx-a"), first.clipboardTargetDeviceIds.value)
        assertEquals(setOf("fx-a"), settings().clipboardTargetDeviceIds.value)
        first.setClipboardTargetDeviceIds(setOf("fx-c", "fx-d"))
        assertEquals(setOf("fx-c", "fx-d"), settings().clipboardTargetDeviceIds.value)
    }

    @Test
    fun resetToDefaultsPutsEverythingBack() {
        val s = settings()
        s.setClipboardSharingEnabled(true)
        s.setClipboardImagesEnabled(false)
        s.setClipboardTargetDevice("fx-a", true)
        s.setClipboardShareMode(ClipboardShareMode.entries.first { it != ClipboardShareMode.UNSET })
        s.resetToDefaults()
        assertFalse(s.clipboardSharingEnabled.value)
        assertTrue(s.clipboardImagesEnabled.value)
        assertTrue(s.clipboardTargetDeviceIds.value.isEmpty())
        assertEquals(ClipboardShareMode.UNSET, s.clipboardShareMode.value)
    }
}
