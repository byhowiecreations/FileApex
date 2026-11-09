package com.fileapex.domain.launch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** FileApex runs as a launcher process and its child process, so the installer lists it twice. The installer text must say so. */
class WindowsInstallerCloseMessageTest {
    private fun script(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "windows/FileApex.iss").exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "windows/FileApex.iss not found" }, "windows/FileApex.iss").readText()
    }

    private fun messageLine(name: String): String =
        script().lines().firstOrNull { it.startsWith("$name=") } ?: error("FileApex.iss has no $name message")

    @Test
    fun bothCloseMessagesExplainWhyFileApexIsListedTwice() {
        for (name in listOf("ApplicationsFound", "ApplicationsFound2")) {
            val line = messageLine(name)
            assertTrue("$name should say FileApex is listed twice", line.contains("twice"))
            assertTrue("$name should say both belong to FileApex", line.contains("both"))
        }
    }

    @Test
    fun theRestartMessageStillSaysSetupRestartsTheApp() {
        assertTrue(messageLine("ApplicationsFound2").contains("restart"))
    }

    @Test
    fun anUpdateRemovesTheOldAppFolderSoRenamedJarsDoNotPileUp() {
        val section = script().substringAfter("[InstallDelete]", "").substringBefore("[")
        assertTrue(section.contains("{app}\\app"))
        assertTrue(section.contains("filesandordirs"))
    }
}
