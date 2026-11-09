package com.fileapex.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DeviceDetailsCacheTest {
    private val full = PeerDeviceDiagnostics(
        collectedAtEpochMs = 100,
        platform = "Linux",
        device = DeviceIdentityDiagnostics(make = "Dell", model = "XPS", kernelVersion = "6.8", osBuildVersion = "26.04"),
        processor = ProcessorDiagnostics(architecture = "x86_64", hardware = "i7", activeCoreCount = 4, totalCoreCount = 8),
        display = DisplayDiagnostics(resolution = "2560x1440", brightnessPercent = 50),
        battery = BatteryDiagnostics(levelPercent = 80),
        storage = StorageDiagnostics(usedBytes = 10, totalBytes = 100),
        memory = MemoryDiagnostics(totalBytes = 16, availableBytes = 8, usedBytes = 8)
    )

    private fun cache(): DeviceDetailsCache {
        DeviceDetailsCache.resetForTest(reader = { "" }, writer = {})
        return DeviceDetailsCache
    }

    @Test
    fun anAnswerWithNothingSavedIsKeptAsIs() {
        assertSame(full, cache().remember("a", full))
    }

    @Test
    fun theSavedCopyIsThereToShowAtOnceBeforeTheAnswerArrives() {
        cache().remember("a", full)
        assertEquals("Dell", DeviceDetailsCache.get("a")?.device?.make)
    }

    @Test
    fun aFreshAnswerReplacesEverySavedValueIncludingTheFixedFacts() {
        cache().remember("a", full)
        val updated = full.copy(
            collectedAtEpochMs = 300,
            device = DeviceIdentityDiagnostics(make = "MSI", model = "MS-7D75", kernelVersion = "10.0.26200", osBuildVersion = "26.10"),
            processor = ProcessorDiagnostics(architecture = "amd64", hardware = "Ryzen", totalCoreCount = 16),
            display = DisplayDiagnostics(resolution = "1x1", gpus = listOf(GpuDiagnostics("RX 6800", 1L)))
        )
        val shown = DeviceDetailsCache.remember("a", updated, null)
        assertEquals(updated, shown)
        assertEquals(updated, DeviceDetailsCache.get("a"))
    }

    @Test
    fun aBlankFreshValueIsNotPatchedFromTheOldCopy() {
        cache().remember("a", full)
        val shown = DeviceDetailsCache.remember("a", full.copy(device = DeviceIdentityDiagnostics(make = "", model = "")), setOf(DiagnosticGroup.Identity))
        assertEquals("", shown.device.make)
        assertEquals("", shown.device.model)
    }

    @Test
    fun aGroupNotAskedForKeepsItsSavedValuesWhileTheAskedOnesAreReplaced() {
        cache().remember("a", full)
        val partial = PeerDeviceDiagnostics(collectedAtEpochMs = 300, platform = "Linux", battery = BatteryDiagnostics(levelPercent = 40))
        val shown = DeviceDetailsCache.remember("a", partial, setOf(DiagnosticGroup.Battery))
        assertEquals(40, shown.battery.levelPercent)
        assertEquals("Dell", shown.device.make)
        assertEquals(8, shown.processor.totalCoreCount)
    }

    @Test
    fun theSavedFormRoundTripsAndBadTextReadsAsEmpty() {
        val raw = DeviceDetailsCache.encode(listOf(CachedDeviceDetails("a", full)))
        assertEquals(full, DeviceDetailsCache.decode(raw).single().snapshot)
        assertEquals(emptyList<CachedDeviceDetails>(), DeviceDetailsCache.decode("not json"))
        assertEquals(emptyList<CachedDeviceDetails>(), DeviceDetailsCache.decode(""))
    }

    @Test
    fun aGroupThePeerWasNotAskedForKeepsItsSavedValues() {
        val partial = PeerDeviceDiagnostics(collectedAtEpochMs = 300, battery = BatteryDiagnostics(levelPercent = 40))
        val kept = DeviceDetailsCache.keepUnasked(full, partial, setOf(DiagnosticGroup.Battery))
        assertEquals(40, kept.battery.levelPercent)
        assertEquals(100L, kept.storage.totalBytes)
        assertEquals(8, kept.processor.totalCoreCount)
        assertSame(partial, DeviceDetailsCache.keepUnasked(full, partial, null))
        assertSame(partial, DeviceDetailsCache.keepUnasked(null, partial, setOf(DiagnosticGroup.Battery)))
    }
}
