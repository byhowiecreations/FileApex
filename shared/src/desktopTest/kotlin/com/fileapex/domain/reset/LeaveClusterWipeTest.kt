package com.fileapex.domain.reset

import com.fileapex.data.settings.AppSettings
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.data.settings.BulletinBoardStyle
import com.fileapex.data.settings.BulletinRemoteFilePurgePreference
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.DriveRelayMaxMb
import com.fileapex.data.settings.PinIdleTimeout
import com.fileapex.data.settings.SettingsKvStore
import com.fileapex.data.settings.UpdateCheckUnit
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.domain.pairing.RemovedDeviceRecord
import com.fileapex.presentation.ExplorerViewMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaveClusterWipeTest {

    private class InMemoryKvStore : SettingsKvStore {
        val map = mutableMapOf<String, Any>()
        var clearCalled = false

        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun getBoolean(key: String, default: Boolean): Boolean = (map[key] as? Boolean) ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, default: String): String = (map[key] as? String) ?: default
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String, default: Int): Int = (map[key] as? Int) ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getLong(key: String, default: Long): Long = (map[key] as? Long) ?: default
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun clear() {
            clearCalled = true
            map.clear()
        }
    }

    @Test
    fun `resetToDefaults clears store and restores pristine settings state`() {
        val store = InMemoryKvStore()
        val settings = BaseAppSettings(store)

        // Modify some settings to non-default state
        settings.setGoogleAccountLinkEnabled(true)
        settings.setGoogleAccountEmail("user@example.com")
        settings.setClipboardSharingEnabled(true)
        settings.setClipboardShareMode(ClipboardShareMode.ALL)
        settings.setPinRequiredEnabled(true)
        settings.setDevicePin("1234")
        settings.setAppTheme(AppTheme.FLUX_GLASS)
        settings.setBulletinBoardStyle(BulletinBoardStyle.IOS_MODERN)
        settings.setCellularEnabled(true)
        settings.setGoogleDriveRelayEnabled(true)

        assertTrue(settings.googleAccountLinkEnabled.value)
        assertEquals("user@example.com", settings.googleAccountEmail.value)
        assertTrue(settings.clipboardSharingEnabled.value)
        assertEquals(ClipboardShareMode.ALL, settings.clipboardShareMode.value)
        assertTrue(settings.pinRequiredEnabled.value)
        assertEquals("1234", settings.devicePin.value)
        assertEquals(AppTheme.FLUX_GLASS, settings.appTheme.value)
        assertEquals(BulletinBoardStyle.IOS_MODERN, settings.bulletinBoardStyle.value)
        assertTrue(settings.cellularEnabled.value)
        assertTrue(settings.googleDriveRelayEnabled.value)

        // Execute resetToDefaults
        settings.resetToDefaults()

        // Verify store was cleared
        assertTrue(store.clearCalled)
        assertTrue(store.map.isEmpty())

        // Verify flows are reset to pristine defaults
        assertFalse(settings.googleAccountLinkEnabled.value)
        assertEquals("", settings.googleAccountEmail.value)
        assertFalse(settings.clipboardSharingEnabled.value)
        assertEquals(ClipboardShareMode.UNSET, settings.clipboardShareMode.value)
        assertFalse(settings.pinRequiredEnabled.value)
        assertEquals("", settings.devicePin.value)
        assertEquals(PinIdleTimeout.DEFAULT, settings.pinIdleTimeout.value)
        assertEquals(AppTheme.CLEAN, settings.appTheme.value)
        assertEquals(BulletinBoardStyle.DEFAULT, settings.bulletinBoardStyle.value)
        assertFalse(settings.cellularEnabled.value)
        assertFalse(settings.googleDriveRelayEnabled.value)
        assertEquals(DriveRelayMaxMb.DEFAULT, settings.driveRelayMaxMb.value)
        assertEquals(DesktopLayoutMode.DEFAULT, settings.desktopLayoutMode.value)
        assertEquals(DesktopUiStyle.DEFAULT, settings.desktopUiStyle.value)
        assertEquals(ExplorerViewMode.List, settings.explorerViewMode.value)
        assertEquals(ExplorerViewMode.List, settings.devicesViewMode.value)
        assertEquals(BulletinRemoteFilePurgePreference.UNCONFIGURED, settings.bulletinRemoteFilePurgePreference.value)
    }

    @Test
    fun `removed device record serializes and deserializes cleanly for cluster remove endpoint`() {
        val record = RemovedDeviceRecord(
            deviceId = "test-device-uuid-1234",
            publicKeyHash = "",
            lastKnownIp = "192.168.1.100",
            port = 8080,
            clusterVersion = 1700000000000L,
            removedAt = 1700000000000L
        )

        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(RemovedDeviceRecord.serializer(), record)
        val decoded = json.decodeFromString(RemovedDeviceRecord.serializer(), encoded)

        assertEquals("test-device-uuid-1234", decoded.deviceId)
        assertEquals(8080, decoded.port)
        assertEquals(1700000000000L, decoded.clusterVersion)
        assertEquals(1700000000000L, decoded.removedAt)
    }

    @Test
    fun `demo devices support in-memory rename, remove, and simulated diagnostics`() {
        val demo = com.fileapex.domain.demo.DemoModeState
        demo.launchDemo()
        assertTrue(demo.isDemoModeActive.value)
        assertEquals(2, demo.demoDeviceRows.value.size)

        // Rename demo device
        demo.renameDemoDevice("demo_macbook", "Office MacBook")
        val renamed = demo.demoDeviceRows.value.first { it.deviceId == "demo_macbook" }
        assertEquals("Office MacBook", renamed.deviceName)
        assertEquals("Office MacBook", demo.getBrowseTarget("demo_macbook").displayName)

        // Simulated diagnostics
        val macDiag = demo.getDemoDeviceDiagnostics("demo_macbook")
        assertEquals("macOS", macDiag.platform)
        assertEquals("Office MacBook", macDiag.device.model)
        assertNotNull(macDiag.battery.levelPercent)
        assertNotNull(macDiag.storage.totalBytes)

        val tabDiag = demo.getDemoDeviceDiagnostics("demo_tablet")
        assertEquals("Android", tabDiag.platform)
        assertEquals("Google", tabDiag.device.make)
        assertNotNull(tabDiag.battery.levelPercent)

        // Remove demo device
        demo.removeDemoDevice("demo_macbook")
        assertEquals(1, demo.demoDeviceRows.value.size)
        assertEquals("demo_tablet", demo.demoDeviceRows.value[0].deviceId)

        // Relaunching demo restores defaults
        demo.launchDemo()
        assertEquals(2, demo.demoDeviceRows.value.size)
        assertEquals("MacBook Pro 16\"", demo.demoDeviceRows.value[0].deviceName)
        demo.exitDemo()
        assertFalse(demo.isDemoModeActive.value)
    }
}
