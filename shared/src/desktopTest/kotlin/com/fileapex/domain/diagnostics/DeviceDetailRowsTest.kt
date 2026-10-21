package com.fileapex.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDetailRowsTest {
    @Test
    fun factsThatStayTheSameShowNoUpdatingMarker() {
        val fixed = listOf(
            DeviceDetailsFieldId.Platform, DeviceDetailsFieldId.Make, DeviceDetailsFieldId.Model,
            DeviceDetailsFieldId.Kernel, DeviceDetailsFieldId.OsBuild, DeviceDetailsFieldId.Processor,
            DeviceDetailsFieldId.CpuArchitecture, DeviceDetailsFieldId.CpuHardware, DeviceDetailsFieldId.DisplayResolution
        )
        fixed.forEach { assertFalse(it.name, it.changesOverTime) }
        assertEquals(fixed.size, DeviceDetailsFieldId.entries.count { !it.changesOverTime })
    }

    @Test
    fun changingValuesShowTheMarker() {
        listOf(
            DeviceDetailsFieldId.BatteryLevel, DeviceDetailsFieldId.Storage, DeviceDetailsFieldId.Memory,
            DeviceDetailsFieldId.Uptime, DeviceDetailsFieldId.NetworkType, DeviceDetailsFieldId.ThermalState
        ).forEach { assertTrue(it.name, it.changesOverTime) }
    }

    @Test
    fun fieldRowsMatchThePlainRows() {
        val snapshot = PeerDeviceDiagnostics(collectedAtEpochMs = 100, platform = "Linux", battery = BatteryDiagnostics(levelPercent = 80))
        val fields = DeviceDiagnosticsFormatter.detailFieldRows(snapshot)
        assertEquals(DeviceDiagnosticsFormatter.detailRows(snapshot), fields.map { it.label to it.value })
        assertTrue(fields.any { it.field == DeviceDetailsFieldId.Platform })
    }

    @Test
    fun aMacNeverShowsWifiRows() {
        val wifiOn = DeviceDetailsDisplayPreferences(DeviceDetailsFieldId.entries.map { DeviceDetailsFieldPreference(it.name, true) })
        val network = NetworkDiagnostics(interfaceType = "Wi-Fi", ssid = "home")
        val mac = DeviceDiagnosticsFormatter.detailFieldRows(PeerDeviceDiagnostics(collectedAtEpochMs = 1, platform = "Mac", network = network), wifiOn)
        val linux = DeviceDiagnosticsFormatter.detailFieldRows(PeerDeviceDiagnostics(collectedAtEpochMs = 1, platform = "Linux", network = network), wifiOn)
        assertFalse(mac.any { it.field.wifiOnly })
        assertTrue(linux.any { it.field == DeviceDetailsFieldId.Ssid })
    }
}
