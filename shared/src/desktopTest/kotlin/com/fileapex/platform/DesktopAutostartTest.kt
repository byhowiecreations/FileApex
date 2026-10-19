package com.fileapex.platform

import com.fileapex.security.CommandRunner
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAutostartTest {
    private fun tempHome() = Files.createTempDirectory("fileapex-home").toFile().also { it.deleteOnExit() }

    @Test
    fun theFlagMarksAStartAtSignIn() {
        assertTrue(DesktopAutostart.isAutostartLaunch(arrayOf("--autostart")))
        assertFalse(DesktopAutostart.isAutostartLaunch(arrayOf("photo.png")))
        assertFalse(DesktopAutostart.isAutostartLaunch(emptyArray()))
    }

    @Test
    fun theWindowsRunValueQuotesThePathAndAddsTheFlag() {
        assertEquals("\"C:\\Program Files\\FileApex\\FileApex.exe\" --autostart", DesktopAutostart.windowsRunValue("C:\\Program Files\\FileApex\\FileApex.exe"))
    }

    @Test
    fun theLinuxEntryRunsTheInstalledBinaryMinimized() {
        val entry = DesktopAutostart.linuxDesktopEntry("/opt/fileapex/bin/FileApex")
        assertTrue(entry.contains("Type=Application"))
        assertTrue(entry.contains("Name=FileApex"))
        assertTrue(entry.contains("Exec=\"/opt/fileapex/bin/FileApex\" --autostart"))
        assertTrue(entry.contains("Terminal=false"))
        assertTrue(entry.contains("X-GNOME-Autostart-enabled=true"))
    }

    @Test
    fun anAppImageUsesItsOwnCurrentPath() {
        assertEquals(
            "/home/me/Apps/FileApex.AppImage",
            DesktopAutostart.executablePath(jpackagePath = "/tmp/.mount_FileApexabc/usr/bin/FileApex", appImagePath = "/home/me/Apps/FileApex.AppImage")
        )
    }

    @Test
    fun anInstalledAppUsesTheLauncherPath() {
        assertEquals("/opt/fileapex/bin/FileApex", DesktopAutostart.executablePath(jpackagePath = "/opt/fileapex/bin/FileApex", appImagePath = ""))
    }

    @Test
    fun aDevelopmentRunHasNoPathAndNothingIsRegistered() {
        assertNull(DesktopAutostart.executablePath(jpackagePath = "", appImagePath = ""))
        val home = tempHome()
        assertFalse(DesktopAutostart.setLinux(true, executable = null, home = home))
        assertFalse(File(home, ".config/autostart/fileapex.desktop").exists())
    }

    @Test
    fun linuxWritesAndRemovesTheAutostartFile() {
        val home = tempHome()
        assertTrue(DesktopAutostart.setLinux(true, "/opt/fileapex/bin/FileApex", home))
        val file = File(home, ".config/autostart/fileapex.desktop")
        assertTrue(file.readText().contains("Exec=\"/opt/fileapex/bin/FileApex\" --autostart"))
        assertTrue(DesktopAutostart.setLinux(false, "/opt/fileapex/bin/FileApex", home))
        assertFalse(file.exists())
    }

    @Test
    fun turningItOffWhenNothingWasWrittenIsHarmless() {
        assertTrue(DesktopAutostart.setLinux(false, "/opt/fileapex/bin/FileApex", tempHome()))
    }

    @Test
    fun windowsAddsAndDeletesThePerUserRunValue() {
        val calls = mutableListOf<List<String>>()
        val runner = CommandRunner { args, _ -> calls += args; 0 to "" }
        assertTrue(DesktopAutostart.setWindows(true, "C:\\Apps\\FileApex.exe", runner))
        assertEquals(
            listOf(
                "reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "FileApex",
                "/t", "REG_SZ", "/d", "\"C:\\Apps\\FileApex.exe\" --autostart", "/f"
            ),
            calls.single()
        )
        calls.clear()
        assertTrue(DesktopAutostart.setWindows(false, "C:\\Apps\\FileApex.exe", runner))
        assertEquals(
            listOf(
                listOf("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "FileApex"),
                listOf("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "FileApex", "/f")
            ),
            calls
        )
    }

    @Test
    fun aFailedRegistryWriteIsReported() {
        assertFalse(DesktopAutostart.setWindows(true, "C:\\Apps\\FileApex.exe") { _, _ -> 1 to "" })
    }

    @Test
    fun deletingAValueThatIsNotThereCountsAsDoneWithoutADelete() {
        val calls = mutableListOf<List<String>>()
        assertTrue(DesktopAutostart.setWindows(false, "C:\\Apps\\FileApex.exe") { args, _ -> calls += args; 1 to "" })
        assertEquals(1, calls.size)
        assertEquals("query", calls.single()[1])
    }
}
