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
            DeviceDetailsFieldId.CpuArchitecture, DeviceDetailsFieldId.CpuHardware, DeviceDetailsFieldId.DisplayResolution,
            DeviceDetailsFieldId.Graphics
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

    @Test
    fun graphicsShowsEachCardWithItsMemoryInWholeGigabytes() {
        val on = DeviceDetailsDisplayPreferences(DeviceDetailsFieldId.entries.map { DeviceDetailsFieldPreference(it.name, true) })
        val snapshot = PeerDeviceDiagnostics(
            collectedAtEpochMs = 1, platform = "Windows",
            display = DisplayDiagnostics(gpus = listOf(GpuDiagnostics("AMD Radeon RX 6800", 17_163_091_968L), GpuDiagnostics("Intel UHD", null), GpuDiagnostics("Old Card", 536_870_912L)))
        )
        val row = DeviceDiagnosticsFormatter.detailFieldRows(snapshot, on).first { it.field == DeviceDetailsFieldId.Graphics }
        assertEquals("AMD Radeon RX 6800 (16 GB)\nIntel UHD\nOld Card (512 MB)", row.value)
    }

    @Test
    fun graphicsIsAskedForWithTheDisplayGroupAndShownByDefault() {
        assertEquals(DiagnosticGroup.Display, DeviceDetailsFieldId.Graphics.group)
        assertTrue(DeviceDetailsFieldId.Graphics in DeviceDetailsFieldId.basicFields)
        assertTrue(DeviceDetailsFieldId.Graphics in DeviceDetailsDisplayPreferences.defaults().visibleFieldIds())
    }

    @Test
    fun theWindowsPlatformRowShowsTheEditionAndWifiRowsStillShow() {
        val on = DeviceDetailsDisplayPreferences(DeviceDetailsFieldId.entries.map { DeviceDetailsFieldPreference(it.name, true) })
        val network = NetworkDiagnostics(interfaceType = "Wi-Fi", ssid = "home")
        val rows = DeviceDiagnosticsFormatter.detailFieldRows(PeerDeviceDiagnostics(collectedAtEpochMs = 1, platform = "Windows 11 Pro", network = network), on)
        assertEquals("Windows 11 Pro", rows.first { it.field == DeviceDetailsFieldId.Platform }.value)
        assertTrue(rows.any { it.field == DeviceDetailsFieldId.Ssid })
    }

    @Test
    fun theFamilyOfAPlatformIgnoresTheEdition() {
        assertEquals("Windows", DiagnosticsPlatform.family("Windows 11 Pro"))
        assertEquals("Windows", DiagnosticsPlatform.family("Windows 10 Home"))
        assertEquals("Windows", DiagnosticsPlatform.family("Windows"))
        assertEquals("Windows", DiagnosticsPlatform.family("  windows 11 Enterprise "))
        assertEquals("Mac", DiagnosticsPlatform.family("Mac"))
        assertEquals("Android", DiagnosticsPlatform.family("Android"))
        assertEquals("Linux", DiagnosticsPlatform.family("Linux"))
        assertEquals("", DiagnosticsPlatform.family(""))
    }

    @Test
    fun aWindowsSnapshotSurvivesTheWireForEveryViewer() {
        val sent = PeerDeviceDiagnostics(
            collectedAtEpochMs = 5, platform = "Windows 11 Pro",
            device = DeviceIdentityDiagnostics(make = "MSI", model = "MS-7D75", kernelVersion = "10.0.26200", osBuildVersion = "25H2 (build 26200.9711)"),
            processor = ProcessorDiagnostics(architecture = "amd64", hardware = "AMD Ryzen 7 7800X3D 8-Core Processor", totalCoreCount = 16, frequencyScaling = "4.20 GHz \u00b7 CPU load 1%"),
            display = DisplayDiagnostics(resolution = "2560 x 1440", gpus = listOf(GpuDiagnostics("AMD Radeon RX 6800", 17_163_091_968L)))
        )
        val wire = com.fileapex.network.FileApexHttpClientFactory.defaultJson.encodeToString(PeerDeviceDiagnostics.serializer(), sent)
        val received = com.fileapex.network.FileApexHttpClientFactory.defaultJson.decodeFromString(PeerDeviceDiagnostics.serializer(), wire)
        assertEquals(sent, received)
    }

    @Test
    fun aWindowsPcThatPredatesGraphicsStillDecodesWithNoCards() {
        val old = """{"collectedAtEpochMs":5,"platform":"Windows","display":{"resolution":"1920 x 1080"}}"""
        val received = com.fileapex.network.FileApexHttpClientFactory.defaultJson.decodeFromString(PeerDeviceDiagnostics.serializer(), old)
        assertTrue(received.display.gpus.isEmpty())
        assertEquals("-", DeviceDiagnosticsFormatter.graphicsSummary(received.display.gpus))
    }

    @Test
    fun anOlderViewerIgnoresTheCardsItDoesNotKnow() {
        val wire = """{"collectedAtEpochMs":5,"platform":"Windows 11 Pro","display":{"resolution":"1920 x 1080","gpus":[{"name":"X","memoryBytes":1}]},"somethingNew":1}"""
        val received = com.fileapex.network.FileApexHttpClientFactory.defaultJson.decodeFromString(PeerDeviceDiagnostics.serializer(), wire)
        assertEquals("1920 x 1080", received.display.resolution)
    }

    @Test
    fun aSnapshotWithoutGraphicsShowsADash() {
        val on = DeviceDetailsDisplayPreferences(DeviceDetailsFieldId.entries.map { DeviceDetailsFieldPreference(it.name, true) })
        val row = DeviceDiagnosticsFormatter.detailFieldRows(PeerDeviceDiagnostics(collectedAtEpochMs = 1), on).first { it.field == DeviceDetailsFieldId.Graphics }
        assertEquals("-", row.value)
    }
}
