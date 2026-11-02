package com.fileapex.platform

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxDesktopIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val launcher = "/opt/File Apex/FileApex"

    private fun layout() = LinuxDesktopIntegration.Layout(
        home = temp.newFolder("home"), dataHome = temp.newFolder("data"), desktop = null,
        supportDirectory = temp.newFolder("support"), icon = null
    )

    @Test fun anOldMarkerWithoutTheUriHandlerIsRewrittenAndRegistered() {
        val layout = layout()
        val marker = File(layout.supportDirectory, ".desktop-integration").apply { writeText(launcher) }
        LinuxDesktopIntegration.install(layout, launcher, appImage = true)
        val menu = File(layout.dataHome, "applications/fileapex.desktop")
        assertTrue(menu.readText().contains("MimeType=x-scheme-handler/fileapex;"))
        assertEquals(LinuxDesktopIntegration.desktopEntry(launcher, "fileapex"), marker.readText())
    }

    @Test fun aCurrentMarkerSkipsTheInstall() {
        val layout = layout()
        File(layout.supportDirectory, ".desktop-integration").writeText(LinuxDesktopIntegration.desktopEntry(launcher, "fileapex"))
        LinuxDesktopIntegration.install(layout, launcher, appImage = true)
        assertFalse(File(layout.dataHome, "applications/fileapex.desktop").exists())
    }

    @Test fun aMovedLauncherIsRegisteredAgain() {
        val layout = layout()
        LinuxDesktopIntegration.install(layout, launcher, appImage = true)
        LinuxDesktopIntegration.install(layout, "/opt/other/FileApex", appImage = true)
        assertTrue(File(layout.dataHome, "applications/fileapex.desktop").readText().contains("Exec=\"/opt/other/FileApex\" %U"))
    }
}
