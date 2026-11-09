package com.fileapex.platform

import com.fileapex.domain.diagnostics.GpuDiagnostics
import com.fileapex.domain.diagnostics.ProcessorDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsDiagnosticsTest {
    private class FakeRegistry(
        val strings: Map<String, String> = emptyMap(),
        val numbers: Map<String, Long> = emptyMap()
    ) : WindowsRegistryReader {
        override fun readLocalMachineString(subKey: String, name: String) = strings["$subKey|$name"]
        override fun readLocalMachineNumber(subKey: String, name: String) = numbers["$subKey|$name"]
    }

    private val version = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion"
    private val bios = "HARDWARE\\DESCRIPTION\\System\\BIOS"
    private val cpu = "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0"
    private val display = "SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}"

    private fun windows11Pro() = FakeRegistry(
        strings = mapOf(
            "$version|ProductName" to "Windows 10 Pro",
            "$version|CurrentBuildNumber" to "26200",
            "$version|DisplayVersion" to "25H2"
        ),
        numbers = mapOf("$version|UBR" to 9711L)
    )

    @Test
    fun windows11IsNamedWithVersionAndBuild() {
        val id = WindowsDiagnostics.identity(windows11Pro(), osName = "Windows 11", osVersion = "10.0")
        assertEquals("25H2 (build 26200.9711)", id.osBuildVersion)
        assertEquals("10.0.26200", id.kernelVersion)
    }

    @Test
    fun thePlatformNamesTheEdition() {
        assertEquals("Windows 11 Pro", WindowsDiagnostics.platformName(windows11Pro(), "Windows 11"))
    }

    @Test
    fun theRegistryProductNameSaysWindows10OnWindows11AndIsCorrected() {
        assertTrue(WindowsDiagnostics.platformName(windows11Pro(), "Windows 11").startsWith("Windows 11"))
    }

    @Test
    fun withoutAProductNameThePlatformIsJustWindows() {
        assertEquals("Windows", WindowsDiagnostics.platformName(FakeRegistry(), "Windows 11"))
    }

    @Test
    fun windows10KeepsItsName() {
        val registry = FakeRegistry(
            strings = mapOf("$version|ProductName" to "Windows 10 Home", "$version|CurrentBuildNumber" to "19045", "$version|DisplayVersion" to "22H2"),
            numbers = mapOf("$version|UBR" to 5000L)
        )
        assertEquals("22H2 (build 19045.5000)", WindowsDiagnostics.identity(registry, "Windows 10", "10.0").osBuildVersion)
        assertEquals("Windows 10 Home", WindowsDiagnostics.platformName(registry, "Windows 10"))
    }

    @Test
    fun anOlderWindowsWithoutDisplayVersionFallsBackToReleaseId() {
        val registry = FakeRegistry(
            strings = mapOf("$version|ProductName" to "Windows 10 Enterprise", "$version|CurrentBuildNumber" to "18363", "$version|ReleaseId" to "1909"),
            numbers = mapOf("$version|UBR" to 1L)
        )
        assertEquals("1909 (build 18363.1)", WindowsDiagnostics.identity(registry, "Windows 10", "10.0").osBuildVersion)
    }

    @Test
    fun missingVersionKeysFallBackToTheJvmNameAndVersion() {
        val id = WindowsDiagnostics.identity(FakeRegistry(), "Windows 11", "10.0")
        assertEquals("Windows 11 10.0", id.osBuildVersion)
        assertEquals("10.0", id.kernelVersion)
    }

    @Test
    fun aCustomBuiltPcWithPlaceholderFirmwareNamesShowsNoMakeOrModel() {
        for (placeholder in listOf("System manufacturer", "System Product Name", "To Be Filled By O.E.M.", "Default string", "O.E.M.", "Not Applicable")) {
            val registry = FakeRegistry(strings = mapOf("$bios|SystemManufacturer" to placeholder, "$bios|SystemProductName" to placeholder))
            val id = WindowsDiagnostics.identity(registry, "Windows 11", "10.0")
            assertEquals(placeholder, "", id.make)
            assertEquals(placeholder, "", id.model)
        }
    }

    @Test
    fun realFirmwareNamesAreKeptAndTrimmed() {
        val registry = FakeRegistry(strings = mapOf("$bios|SystemManufacturer" to "LENOVO ", "$bios|SystemProductName" to " 21HD"))
        val id = WindowsDiagnostics.identity(registry, "Windows 11", "10.0")
        assertEquals("LENOVO", id.make)
        assertEquals("21HD", id.model)
    }

    @Test
    fun theProcessorIsNamedByTheCpuAndItsBaseClock() {
        val registry = FakeRegistry(
            strings = mapOf("$cpu|ProcessorNameString" to "AMD Ryzen 7 7800X3D 8-Core Processor           "),
            numbers = mapOf("$cpu|~MHz" to 4201L)
        )
        val generic = ProcessorDiagnostics(architecture = "amd64", hardware = "Windows 11", activeCoreCount = 16, totalCoreCount = 16, frequencyScaling = "CPU load 3%")
        val merged = WindowsDiagnostics.processor(registry, generic)
        assertEquals("AMD Ryzen 7 7800X3D 8-Core Processor", merged.hardware)
        assertEquals("4.20 GHz · CPU load 3%", merged.frequencyScaling)
        assertEquals("amd64", merged.architecture)
        assertEquals(16, merged.totalCoreCount)
    }

    @Test
    fun withoutRegistryValuesTheGenericProcessorStaysAsIs() {
        val generic = ProcessorDiagnostics(architecture = "amd64", hardware = "Windows 11", totalCoreCount = 8, frequencyScaling = "CPU load 1%")
        assertEquals(generic, WindowsDiagnostics.processor(FakeRegistry(), generic))
    }

    @Test
    fun graphicsCardsListTheRealAdaptersWithTheirVideoMemory() {
        val registry = FakeRegistry(
            strings = mapOf(
                "$display\\0000|DriverDesc" to "AMD Radeon RX 6800",
                "$display\\0001|DriverDesc" to "Microsoft Basic Render Driver",
                "$display\\0002|DriverDesc" to "Honor Virtual Display Device",
                "$display\\0003|DriverDesc" to "Microsoft Remote Display Adapter"
            ),
            numbers = mapOf("$display\\0000|HardwareInformation.qwMemorySize" to 17_163_091_968L)
        )
        assertEquals(listOf(GpuDiagnostics("AMD Radeon RX 6800", 17_163_091_968L)), WindowsDiagnostics.gpus(registry))
    }

    @Test
    fun anAdapterWithoutMemoryInfoStillCountsWhenItIsARealCard() {
        val registry = FakeRegistry(strings = mapOf("$display\\0000|DriverDesc" to "Intel(R) UHD Graphics 770"))
        assertEquals(listOf(GpuDiagnostics("Intel(R) UHD Graphics 770", null)), WindowsDiagnostics.gpus(registry))
    }

    @Test
    fun theOldFourByteMemoryValueIsUsedWhenTheQwordIsMissing() {
        val registry = FakeRegistry(
            strings = mapOf("$display\\0000|DriverDesc" to "NVIDIA GeForce GTX 750"),
            numbers = mapOf("$display\\0000|HardwareInformation.MemorySize" to 1_073_741_824L)
        )
        assertEquals(1_073_741_824L, WindowsDiagnostics.gpus(registry).single().memoryBytes)
    }

    @Test
    fun theDeviceCardProfileUsesTheFirmwareNamesNotAMacCommand() {
        val registry = FakeRegistry(strings = mapOf("$bios|SystemManufacturer" to "Micro-Star International Co., Ltd.", "$bios|SystemProductName" to "MS-7D75"))
        val profile = WindowsDiagnostics.hardwareProfile(registry, platform = "Windows")
        assertEquals("windows", profile.os)
        assertEquals("Windows", profile.platform)
        assertEquals("Micro-Star International Co., Ltd.", profile.deviceMake)
        assertEquals("MS-7D75", profile.deviceModel)
    }

    @Test
    fun aCustomPcHasNoMakeOrModelOnTheDeviceCardEither() {
        val registry = FakeRegistry(strings = mapOf("$bios|SystemManufacturer" to "System manufacturer", "$bios|SystemProductName" to "System Product Name"))
        val profile = WindowsDiagnostics.hardwareProfile(registry, platform = "Windows")
        assertEquals("", profile.deviceMake)
        assertEquals("", profile.deviceModel)
    }
}
