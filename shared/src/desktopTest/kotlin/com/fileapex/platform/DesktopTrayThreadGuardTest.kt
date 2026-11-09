package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Showing the main window from the tray runs on the UI thread. The foreground refresh can wait on the network, so it must run off that thread, as it does on the Mac. */
class DesktopTrayThreadGuardTest {
    private fun source(name: String): String =
        File(System.getProperty("user.dir"), "src/desktopMain/kotlin/com/fileapex/platform/$name").readText()

    @Test
    fun theWindowsTrayRunsTheForegroundRefreshOffTheUiThread() {
        val lines = source("DesktopAwtTrayCoordinator.kt").lines().filter { it.contains("PresenceForegroundRefresh.onAppForegrounded()") }
        assertTrue("expected the foreground refresh call", lines.isNotEmpty())
        for (line in lines) {
            assertTrue("foreground refresh must be launched on the background scope: $line", line.contains("scope.launch"))
        }
    }

    @Test
    fun theWindowsTrayLogsHowLongShowingTheWindowTakes() {
        assertTrue(source("DesktopAwtTrayCoordinator.kt").contains("showMainWindow returned after"))
    }
}
