package com.fileapex.platform

import com.fileapex.domain.diagnostics.ProcessorDiagnostics
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs on a real Windows machine only; skipped elsewhere. */
class WindowsDiagnosticsLiveTest {
    @Test
    fun theRealRegistryDescribesThisMachine() {
        assumeTrue(DesktopPlatformPaths.isWindows())
        val id = WindowsDiagnostics.identity(WindowsRegistry, System.getProperty("os.name").orEmpty(), System.getProperty("os.version").orEmpty())
        val cpu = WindowsDiagnostics.processor(WindowsRegistry, ProcessorDiagnostics(hardware = "x"))
        val gpus = WindowsDiagnostics.gpus(WindowsRegistry)
        println("LIVE identity=$id cpu=$cpu gpus=$gpus")
        assertTrue(id.osBuildVersion, id.osBuildVersion.contains("(build "))
        val platform = WindowsDiagnostics.platformName(WindowsRegistry, System.getProperty("os.name").orEmpty())
        println("LIVE platform=$platform")
        assertTrue(platform, platform.startsWith("Windows"))
        assertTrue(id.kernelVersion, id.kernelVersion.startsWith("10.0."))
        assertTrue(cpu.hardware, cpu.hardware.length > 3 && cpu.hardware != "x")
        assertTrue(cpu.frequencyScaling, cpu.frequencyScaling.contains("GHz"))
        assertTrue(gpus.toString(), gpus.isNotEmpty())
        val card = localDeviceHardwareProfile()
        println("LIVE card=$card")
        assertTrue(card.toString(), card.os == "windows" && card.deviceMake != "Microsoft" && card.deviceModel != "amd64")
    }
}
