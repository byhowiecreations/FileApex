package com.fileapex.platform

import com.fileapex.security.CommandRunner
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacFullDiskAccessTest {
    private fun tempDir() = Files.createTempDirectory("fileapex-fda").toFile().also { it.deleteOnExit() }

    @Test
    fun aReadableProbeMeansAccessIsGranted() {
        val dir = tempDir()
        File(dir, "a.txt").writeText("x")
        assertTrue(MacFullDiskAccess.isGranted(listOf(dir)))
    }

    @Test
    fun aReadableProbeFileMeansAccessIsGranted() {
        val file = File(tempDir(), "TCC.db").also { it.writeText("x") }
        assertTrue(MacFullDiskAccess.isGranted(listOf(file)))
    }

    @Test
    fun aProbeThatExistsButCannotBeReadMeansAccessIsMissing() {
        val dir = tempDir()
        assertTrue(dir.setReadable(false))
        try {
            assertFalse(MacFullDiskAccess.isGranted(listOf(dir)))
        } finally {
            dir.setReadable(true)
        }
    }

    @Test
    fun oneReadableProbeIsEnoughWhenAnotherIsBlocked() {
        val blocked = tempDir()
        assertTrue(blocked.setReadable(false))
        try {
            assertTrue(MacFullDiskAccess.isGranted(listOf(blocked, tempDir())))
        } finally {
            blocked.setReadable(true)
        }
    }

    @Test
    fun whenNoProbeExistsNothingIsAskedOfTheUser() {
        assertTrue(MacFullDiskAccess.isGranted(listOf(File(tempDir(), "missing"))))
    }

    @Test
    fun theSettingsButtonOpensThePrivacyPaneForFullDiskAccess() {
        val calls = mutableListOf<List<String>>()
        MacFullDiskAccess.openSettings(CommandRunner { args, _ -> calls += args; 0 to "" })
        assertEquals(
            listOf(listOf("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles")),
            calls
        )
    }

    @Test
    fun theDefaultProbesAreInsideTheUserAndSystemTccAreas() {
        val paths = MacFullDiskAccess.defaultProbes(File("/Users/me")).map { it.path }
        assertTrue(paths.contains("/Users/me/Library/Safari"))
        assertTrue(paths.contains("/Library/Application Support/com.apple.TCC/TCC.db"))
    }
}
