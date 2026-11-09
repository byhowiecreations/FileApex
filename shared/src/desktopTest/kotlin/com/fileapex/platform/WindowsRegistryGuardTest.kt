package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class WindowsRegistryGuardTest {
    @Test
    fun noStartupCodeStartsWmicOrRegExe() {
        val dir = File("src/desktopMain/kotlin/com/fileapex/platform").takeIf { it.isDirectory }
            ?: File("shared/src/desktopMain/kotlin/com/fileapex/platform")
        val offenders = dir.listFiles { f -> f.extension == "kt" }.orEmpty().filter { it.name != "DesktopAutostart.kt" }.filter { file ->
            val text = file.readText()
            text.contains("\"wmic\"") || text.contains("\"reg\", \"query\"")
        }.map { it.name }
        assertTrue("process starts left in: $offenders", offenders.isEmpty())
    }

    @Test
    fun theSharedCollectorHoldsNoWindowsCode() {
        val file = File("src/desktopMain/kotlin/com/fileapex/platform/DeviceDiagnosticsCollector.desktop.kt").takeIf { it.isFile }
            ?: File("shared/src/desktopMain/kotlin/com/fileapex/platform/DeviceDiagnosticsCollector.desktop.kt")
        val text = file.readText()
        assertFalse(text.contains("netsh"))
        assertFalse(text.contains("readWindows"))
        assertFalse(text.contains("isWindowsOs"))
    }

    @Test
    fun theDeviceCardProfileRunsNoMacCommandOnWindows() {
        val file = File("src/desktopMain/kotlin/com/fileapex/platform/DeviceHardwareProfileCollector.desktop.kt").takeIf { it.isFile }
            ?: File("shared/src/desktopMain/kotlin/com/fileapex/platform/DeviceHardwareProfileCollector.desktop.kt")
        val text = file.readText()
        val windowsPart = text.substringAfter("DesktopOs.Windows ->").substringBefore("DesktopOs.MacOs")
        assertFalse(windowsPart.contains("sysctl"))
        assertTrue(text.contains("WindowsDiagnostics.hardwareProfile"))
    }

    @Test
    fun thePathIsSetByTheInstallerNotByPowerShellAtRuntime() {
        val cli = File("src/desktopMain/kotlin/com/fileapex/cli/CliPathIntegration.kt").takeIf { it.isFile }
            ?: File("shared/src/desktopMain/kotlin/com/fileapex/cli/CliPathIntegration.kt")
        assertFalse(cli.readText().contains("powershell"))
    }

    @Test
    fun theMachineGuidIsReadWithoutStartingAProcessOnWindows() {
        assumeTrue(DesktopPlatformPaths.isWindows())
        val guid = WindowsRegistry.readLocalMachineString("SOFTWARE\\Microsoft\\Cryptography", "MachineGuid").orEmpty()
        assertTrue("guid=<$guid>", guid.length >= 32)
        assertFalse(HardwareId.hash(guid).isEmpty())
    }

    @Test
    fun aNumberValueIsReadOnWindows() {
        assumeTrue(DesktopPlatformPaths.isWindows())
        val build = WindowsRegistry.readLocalMachineNumber("SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "UBR")
        assertTrue("UBR=$build", build != null && build > 0)
    }

    @Test
    fun aMissingRegistryValueIsNullOnWindows() {
        assumeTrue(DesktopPlatformPaths.isWindows())
        assertEquals(null, WindowsRegistry.readLocalMachineString("SOFTWARE\\Microsoft\\Cryptography", "NoSuchValueFileApex"))
    }
}
