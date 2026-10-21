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

    @Test
    fun anAnswerWithNothingSavedIsKeptAsIs() {
        assertSame(full, DeviceDetailsCache.merge(null, full))
    }

    @Test
    fun whatNeverChangesSurvivesAThinnerAnswerAndWhatChangesIsTakenFromTheNewOne() {
        val thin = PeerDeviceDiagnostics(
            collectedAtEpochMs = 200,
            battery = BatteryDiagnostics(levelPercent = 55, chargingState = "AC"),
            storage = StorageDiagnostics(usedBytes = 40),
            memory = MemoryDiagnostics(availableBytes = 2, usedBytes = 14)
        )
        val merged = DeviceDetailsCache.merge(full, thin)
        assertEquals("Linux", merged.platform)
        assertEquals("Dell", merged.device.make)
        assertEquals("6.8", merged.device.kernelVersion)
        assertEquals("x86_64", merged.processor.architecture)
        assertEquals(8, merged.processor.totalCoreCount)
        assertEquals("2560x1440", merged.display.resolution)
        assertEquals(100L, merged.storage.totalBytes)
        assertEquals(16L, merged.memory.totalBytes)
        assertEquals(200L, merged.collectedAtEpochMs)
        assertEquals(55, merged.battery.levelPercent)
        assertEquals(40L, merged.storage.usedBytes)
        assertEquals(2L, merged.memory.availableBytes)
        assertEquals(null, merged.processor.activeCoreCount)
    }

    @Test
    fun aNewValueReplacesAnOldOneEvenForTheStableFields() {
        val updated = full.copy(device = full.device.copy(osBuildVersion = "26.10"), collectedAtEpochMs = 300)
        assertEquals("26.10", DeviceDetailsCache.merge(full, updated).device.osBuildVersion)
    }

    @Test
    fun theSavedFormRoundTripsAndBadTextReadsAsEmpty() {
        val raw = DeviceDetailsCache.encode(listOf(CachedDeviceDetails("a", full)))
        assertEquals(full, DeviceDetailsCache.decode(raw).single().snapshot)
        assertEquals(emptyList<CachedDeviceDetails>(), DeviceDetailsCache.decode("not json"))
        assertEquals(emptyList<CachedDeviceDetails>(), DeviceDetailsCache.decode(""))
    }
}
