package com.fileapex.domain.launch

import com.fileapex.platform.LinuxDesktopIntegration
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DesktopLaunchRegistrationTest {
    @Test fun legacyIntegrationMarkersCannotSkipUriRegistration() {
        val launcher = "/opt/File Apex/FileApex"
        val stamp = LinuxDesktopIntegration.desktopEntry(launcher, "fileapex")
        assertNotEquals(launcher, stamp)
        assertTrue(stamp.contains("MimeType=x-scheme-handler/fileapex;"))
        assertNotEquals(stamp, LinuxDesktopIntegration.desktopEntry("/opt/other/FileApex", "fileapex"))
        assertEquals(stamp, LinuxDesktopIntegration.desktopEntry(launcher, "fileapex"))
    }

    @Test fun linuxLaunchersDeliverUriArgumentsToTheNormalAppEntry() {
        val entry = LinuxDesktopIntegration.desktopEntry("/opt/File Apex/FileApex", "fileapex")
        assertTrue(entry.lines().contains("Exec=\"/opt/File Apex/FileApex\" %U"))
        assertTrue(entry.lines().contains("MimeType=x-scheme-handler/fileapex;"))
        val template = File("../composeApp/linux/FileApex.desktop").readText()
        assertTrue(template.lines().contains("Exec=APPLICATION_LAUNCHER %U"))
        assertTrue(template.lines().contains("MimeType=x-scheme-handler/fileapex;"))
    }

    @Test fun windowsInstallerRegistersAQuotedProtocolCommandWithoutAShell() {
        val source = File("../windows/FileApex.iss").readLines()
        val root = source.filter { it.contains("Subkey: \"Software\\Classes\\fileapex\"") }
        assertTrue(root.any { it.contains("ValueName: \"URL Protocol\"") && it.contains("ValueData: \"\"") })
        val command = source.single { it.contains("Subkey: \"Software\\Classes\\fileapex\\shell\\open\\command\"") }
        assertTrue(command.contains("ValueData: \"\"\"{app}\\FileApex.exe\"\" \"\"%1\"\"\""))
        assertFalse(command.contains("cmd.exe", true))
        assertFalse(command.contains("powershell", true))
    }
}
